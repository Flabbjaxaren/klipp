package se.gendut.klipp.engine

import android.content.Context
import java.io.File
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import se.gendut.klipp.Clip
import se.gendut.klipp.ClipKind
import se.gendut.klipp.EditBackend
import se.gendut.klipp.EditorState
import se.gendut.klipp.R
import se.gendut.klipp.Str
import se.gendut.klipp.TRACKS
import kotlin.math.abs

/**
 * Connects the UI to the FilmCraft engine. The engine owns the project; the UI gets snapshots and
 * animates the differences. Video files are read in place via /proc/self/fd – nothing is copied.
 */
class ProjectController(
    private val context: Context,
    val state: EditorState,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
) : EditBackend, se.gendut.klipp.PropsBackend {
    init {
        // The engine uses TMPDIR (previews) and HOME; Android sets no writable values.
        runCatching {
            android.system.Os.setenv("TMPDIR", context.cacheDir.absolutePath, true)
            android.system.Os.setenv("HOME", context.filesDir.absolutePath, true)
        }
        Reversals.onMessage = onMessage
    }
    val engine = Engine()
    var snapshot by mutableStateOf(Snapshot.EMPTY)
        private set
    /** Increases on every timeline change – playback rebuilds its composition. */
    var revision by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set

    private val uris = mutableMapOf<Long, Uri>()
    /** Addresses of media that couldn't be opened – saved anyway, otherwise they're gone after the next save. */
    private val offline = mutableMapOf<Long, Uri>()
    private val projectDir = java.io.File(context.filesDir, "projekt").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("klipp", Context.MODE_PRIVATE)
    /** Current project (file name without extension). "aktuellt" = the project from before the projects page existed. */
    var currentId by mutableStateOf(prefs.getString("current", "aktuellt") ?: "aktuellt")
        private set
    var currentName by mutableStateOf("")
        private set
    private val projectFile get() = java.io.File(projectDir, "$currentId.fcproj")
    private val mediaFile get() = java.io.File(projectDir, "$currentId.media.json")
    private fun thumbFile(id: String) = java.io.File(projectDir, "$id.jpg")
    /** Increases when the project list has changed (the projects page reloads). */
    var projectsRevision by mutableIntStateOf(0)
        private set
    private var saveJob: kotlinx.coroutines.Job? = null
    /** Extra UI state saved with the project (color, LUT, master volume). */
    var extraSave: () -> JSONObject = { JSONObject() }
    var onExtraRestore: (JSONObject) -> Unit = {}
    /** True while a saved project exists and is being opened. */
    var restoring by mutableStateOf(false)
        private set
    private val fds = mutableListOf<ParcelFileDescriptor>()
    /** Edits one at a time – otherwise other changes land in the middle of an undo group. Not locked re-entrantly. */
    private val editLock = Mutex()
    /** Set if a project couldn't be opened and neither could the previous one – nothing is then saved over the files. */
    private var saveBlocked = false

    init {
        state.backend = this
        state.props = this
    }

    /** Set by the UI: opens the file picker for captions. */
    var captionPicker: (() -> Unit)? = null

    /** The media item's file; negative ids = reversed clips' files ([Reversals]). */
    fun uriOf(item: Long): Uri? = if (item < 0) RenderCache.uriOf(item) ?: Reversals.uriOf(item) else uris[item]

    // ------------------------------------------------------------------ import

    fun import(picked: List<Uri>) = scope.launch {
        if (picked.isEmpty()) return@launch
        busy = true
        // the files are opened (and names read) on a background thread – many files at once used to freeze the app
        val opened = withContext(kotlinx.coroutines.Dispatchers.IO) {
            picked.mapNotNull { u ->
                // keep read permission across restarts so the project can be opened again
                runCatching { context.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val pfd = runCatching { context.contentResolver.openFileDescriptor(u, "r") }.getOrNull() ?: return@mapNotNull null
                Triple(u, pfd, displayName(u))
            }
        }
        val used = HashSet<Int>()
        try {
            if (opened.isEmpty()) { onMessage(Str.t(R.string.projectcontroller_files_open_failed)); return@launch }
            val paths = JSONArray().apply { opened.forEach { put("/proc/self/fd/${it.second.fd}") } }
            var count = 0
            editLock.withLock {
                // the whole import (files, names, new sequence) is one undo step
                group(Str.t(R.string.projectcontroller_undo_import)) {
                    val r = engine.exec("file.import", JSONObject().put("paths", paths))
                    val items = r.optJSONArray("items") ?: JSONArray()
                    count = items.length()
                    // files that failed to import are missing from the list – match by path, not position
                    val byPath = engine.snapshot().media.associate { it.id to it.path }
                    for (i in 0 until items.length()) {
                        val id = items.getLong(i)
                        val k = (0 until paths.length()).firstOrNull { paths.getString(it) == byPath[id] }
                            ?: i.takeIf { items.length() == opened.size } ?: continue
                        uris[id] = opened[k].first
                        used += k
                        engine.tryExec("clip.rename", JSONObject().put("item", id).put("name", opened[k].third))
                    }
                    val errors = r.optJSONArray("errors")
                    if (errors != null && errors.length() > 0) onMessage(Str.p(R.plurals.projectcontroller_files_import_failed, errors.length(), errors.length()))
                    val snap = engine.snapshot()
                    if (snap.sequence == null) {
                        val first = snap.media.firstOrNull { it.hasVideo }
                        val p = newSeqParams()
                        if (first != null && first.width > 0) {
                            p.put("width", first.width).put("height", first.height).put("fps", normalizeFps(first.fps))
                        }
                        engine.exec("file.newSequence", p)
                    }
                }
                refresh()
            }
            onMessage(Str.p(R.plurals.projectcontroller_clips_imported, count, count))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onMessage(Str.t(R.string.projectcontroller_import_failed, e.message.orEmpty()))
        } finally {
            // handles to files the engine accepted stay open (it reads through them); the rest are closed
            opened.forEachIndexed { k, (_, pfd, _) -> if (k in used) fds += pfd else runCatching { pfd.close() } }
            busy = false
        }
    }

    private fun normalizeFps(f: Double): Double {
        // phone video often reports e.g. 29.98 or 59.93 – round to standard values
        val std = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0)
        return std.minBy { abs(it - f) }.takeIf { abs(it - f) < 0.6 } ?: f
    }

    private fun displayName(u: Uri): String =
        runCatching {
            context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: u.lastPathSegment ?: Str.t(R.string.projectcontroller_default_media_name)

    // ------------------------------------------------------------------ source + insert

    suspend fun setMarks(item: Long, inSec: Double?, outSec: Double?) {
        val p = JSONObject().put("item", item)
        p.put("in", inSec?.let { frameTicks(it) } ?: JSONObject.NULL)
        p.put("out", outSec?.let { frameTicks(it) } ?: JSONObject.NULL)
        editLock.withLock {
            engine.tryExec("project.setMarks", p).onFailure { onMessage(it.message ?: Str.t(R.string.projectcontroller_set_marks_failed)) }
            refresh()
        }
    }

    // ------------------------------------------------------------------ media panel: bins, names, delete

    /** How many timeline clips use the media item. */
    fun usage(item: Long): Int = snapshot.sequence?.let { q -> (q.video + q.audio).sumOf { t -> t.clips.count { it.item == item } } } ?: 0

    fun newBin(name: String, parent: Long?) = scope.launch { editLock.withLock {
        val p = JSONObject().put("name", name)
        parent?.let { p.put("parent", it) }
        engine.tryExec("file.newBin", p).onFailure { onMessage(it.message ?: Str.t(R.string.projectcontroller_new_bin_failed)) }
        refresh()
    } }

    fun moveToBin(items: List<Long>, bin: Long?) = scope.launch { editLock.withLock {
        engine.tryExec("project.moveToBin", JSONObject().put("items", JSONArray(items)).put("bin", bin ?: JSONObject.NULL))
            .onFailure { onMessage(it.message ?: Str.t(R.string.projectcontroller_move_failed)) }
        refresh()
    } }

    fun renameMedia(item: Long, name: String) = scope.launch { editLock.withLock {
        engine.tryExec("clip.rename", JSONObject().put("item", item).put("name", name)).onFailure { onMessage(it.message ?: Str.t(R.string.projectcontroller_rename_failed)) }
        refresh()
    } }

    fun renameBin(bin: Long, name: String) = scope.launch { editLock.withLock {
        engine.tryExec("klipp.renameBin", JSONObject().put("bin", bin).put("name", name)).onFailure { onMessage(it.message ?: Str.t(R.string.projectcontroller_rename_failed)) }
        refresh()
    } }

    /**
     * Remove media (and bins with everything in them) from the project. Timeline clips using the media are removed
     * first – otherwise the engine would only mark the media offline. All of it is one undo step. The files on
     * the phone aren't touched.
     */
    fun deleteMedia(items: List<Long>, bins: List<Long>) = scope.launch {
        val inBins = bins.flatMap { b -> mediaInBin(b) }
        val all = (items + inBins).toSet()
        val clips = snapshot.sequence?.let { q -> (q.video + q.audio).flatMap { t -> t.clips.filter { it.item in all }.map { it.id } } }.orEmpty()
        editLock.withLock {
            group(Str.t(R.string.projectcontroller_undo_remove_media)) {
                if (clips.isNotEmpty()) engine.tryExec("edit.clear", JSONObject().put("clips", JSONArray(clips))).onFailure { onMessage(friendly(it.message)) }
                engine.tryExec("project.delete", JSONObject().put("items", JSONArray(items + bins))).onFailure { onMessage(it.message ?: Str.t(R.string.projectcontroller_remove_failed)) }
            }
        }
        // (the file's address is kept – undo brings the media back)
        refresh()
    }

    /** All media in the bin and its sub-bins. */
    fun mediaInBin(bin: Long): List<Long> {
        val sub = snapshot.bins.filter { it.parent == bin }.map { it.id }
        return snapshot.media.filter { it.bin == bin }.map { it.id } + sub.flatMap { mediaInBin(it) }
    }

    /** Insert (or overwrite) the source's marked range at the timeline playhead. */
    fun editFromSource(item: Long, atSec: Double, overwrite: Boolean, onDone: () -> Unit = {}) = scope.launch { editLock.withLock {
        try {
            group(Str.t(if (overwrite) R.string.projectcontroller_undo_overwrite else R.string.projectcontroller_undo_insert)) {
                if (snapshot.sequence == null) engine.exec("file.newSequence", newSeqParams())
                engine.exec("source.open", JSONObject().put("item", item))
                engine.exec("playhead.set", JSONObject().put("time", frameTicks(atSec)))
                engine.exec(if (overwrite) "source.overwrite" else "source.insert")
            }
            refresh()
            state.playhead = snapshot.playhead.toFloat()
            onDone()
        } catch (e: Exception) {
            onMessage(e.message ?: Str.t(R.string.projectcontroller_edit_failed))
        }
    } }

    /**
     * Copy a timeline clip to the source: the same media with the clip's In/Out, so it can
     * be placed elsewhere. Returns the media with the new marks.
     */
    suspend fun copyToSource(clipId: Long): MediaInfo? {
        val q = snapshot.sequence ?: return null
        val c = (q.video + q.audio).flatMap { it.clips }.firstOrNull { it.id == clipId } ?: return null
        val frame = 1.0 / snapshot.fps.coerceAtLeast(1.0)
        val inT = c.sourceIn
        val outT = (c.sourceIn + c.duration * c.speed - frame).coerceAtLeast(inT)
        setMarks(c.item, inT, outT)
        return snapshot.media.firstOrNull { it.id == c.item }
    }

    // ------------------------------------------------------------------ EditBackend

    private fun engineTrack(uiTrack: Int): String = when (uiTrack) {
        0 -> "V3"; 1 -> "V2"; 2 -> "V1"; 3 -> "A1"; 4 -> "A2"; else -> "A3"
    }

    /** An engine command under the lock; the timeline is reread before the next edit gets the lock (never stale state). */
    private fun run(id: String, p: JSONObject) = scope.launch {
        editLock.withLock {
            engine.tryExec(id, p).onFailure { onMessage(friendly(it.message)) }
            refresh()
        }
    }

    // ------------------------------------------------------------------ frames
    // Clip times in the timeline are Float seconds: at 1:25 the error is ~1 µs. Without rounding, a clip
    // snapped to its neighbor's edge landed a microsecond off – and the engine kept the overlap as a "piece" with almost no
    // length. Everything that moves edges in the timeline is therefore sent in whole frames.

    /** Nearest whole frame to [sec], in ticks. */
    private fun frameTicks(sec: Double): Long = FrameTime.ticks(sec, snapshot.fps)

    /** The engine's exact tick for a time it reported itself (seconds as f64 – round-trips exactly). */
    private fun exactTicks(sec: Double): Long = Math.round(sec * TICKS_PER_SECOND)

    private fun clipSnap(id: Long): ClipSnap? = snapshot.sequence?.let { it.video + it.audio }?.flatMap { it.clips }?.firstOrNull { it.id == id }

    /**
     * A timeline edit as one undo step, then removal of clips shorter than half a frame
     * (leftover overlaps from older versions or rounding – they show up as tiny "clips" and can't be seen).
     */
    private fun runEdit(id: String, p: JSONObject, label: String) = scope.launch {
        editLock.withLock {
            group(label) {
                engine.tryExec(id, p).onFailure { onMessage(friendly(it.message)) }
                sweepSlivers()
            }
            refresh()
        }
    }

    /** Remove clips shorter than half a frame. The caller holds [editLock]. */
    private suspend fun sliverIds(): List<Long> {
        val s = runCatching { engine.snapshot() }.getOrNull() ?: return emptyList()
        val q = s.sequence ?: return emptyList()
        val half = 0.5 / (s.fps.takeIf { it >= 1.0 } ?: 25.0)
        return (q.video + q.audio).flatMap { it.clips }.filter { it.duration < half }.map { it.id }
    }

    private suspend fun sweepSlivers(): Int {
        val ids = sliverIds()
        if (ids.isEmpty()) return 0
        engine.tryExec("edit.clear", JSONObject().put("clips", JSONArray().apply { ids.forEach { put(it) } }))
        return ids.size
    }

    /** One undo step from several engine calls. The caller holds [editLock]; the group is closed even on cancellation. */
    private suspend inline fun <T> group(label: String, on: Boolean = true, block: () -> T): T {
        if (on) engine.tryExec("klipp.beginGroup", JSONObject())
        try {
            return block()
        } finally {
            if (on) withContext(NonCancellable) { engine.tryExec("klipp.endGroup", JSONObject().put("label", label)) }
        }
    }

    private fun friendly(m: String?): String = when {
        m == null -> Str.t(R.string.projectcontroller_generic_failed)
        m.contains("sync", true) && m.contains("lock", true) -> Str.t(R.string.projectcontroller_sync_lock_blocks_ripple)
        m.contains("locked", true) -> Str.t(R.string.projectcontroller_track_locked)
        else -> m
    }

    override fun move(clip: Clip, start: Float, track: Int, insert: Boolean) {
        val mv = JSONObject().put("clip", clip.id).put("track", engineTrack(track)).put("time", frameTicks(start.toDouble()))
        runEdit("timeline.move", JSONObject().put("moves", JSONArray().put(mv)).put("insert", insert), Str.t(R.string.projectcontroller_undo_move))
    }

    override fun trim(old: Clip, updated: Clip, ripple: Boolean) {
        val inDelta = updated.srcIn - old.srcIn
        val p = JSONObject().put("clip", old.id).put("mode", if (ripple) "ripple" else "regular")
        // the edge lands on a whole frame (computed from the engine's exact position, not from the Float values)
        val c = clipSnap(old.id)
        if (abs(inDelta) > 0.0001f) {
            var delta = if (c != null) frameTicks(c.start + inDelta) - exactTicks(c.start) else frameTicks(inDelta.toDouble())
            // rounding must never go before the file's start (the clip's source start below zero)
            if (c != null && c.text == null) {
                val sp = abs(c.speed).coerceAtLeast(1e-6)
                val minDelta = -exactTicks(c.sourceIn / sp)
                if (delta < minDelta) delta = minDelta
            }
            p.put("edge", "in").put("delta", delta)
        } else {
            val dl = (updated.length - old.length).toDouble()
            var delta = if (c != null) frameTicks(c.start + c.duration + dl) - exactTicks(c.start + c.duration) else frameTicks(dl)
            // …or past the file's end (according to Media3, same as the player)
            if (c != null && c.text == null) {
                val len = MediaProbe.length(uris[c.item], snapshot.media.firstOrNull { it.id == c.item }?.duration)
                if (len != null) {
                    val sp = abs(c.speed).coerceAtLeast(1e-6)
                    val maxDelta = exactTicks((len - (c.sourceIn + c.duration * sp)) / sp)
                    if (delta > maxDelta) delta = maxDelta.coerceAtLeast(minOf(0L, delta))
                }
            }
            p.put("edge", "out").put("delta", delta)
        }
        runEdit("timeline.trim", p, Str.t(R.string.projectcontroller_undo_trim))
    }

    /** Clips created by a new edit (the color page forgets an old grade on the same id). */
    var onNewClips: (List<Long>) -> Unit = {}

    /** After a split: (old clip, new clips) – the color page lets the pieces keep their grade. */
    var onSplit: (Long, List<Long>) -> Unit = { _, _ -> }

    override fun split(id: Long, at: Float) {
        scope.launch { editLock.withLock {
            val before = (snapshot.sequence?.let { q -> q.video + q.audio }?.flatMap { it.clips }?.map { it.id } ?: emptyList()).toSet()
            engine.tryExec("timeline.razor", JSONObject().put("time", frameTicks(at.toDouble())).put("clip", id)).onFailure { onMessage(friendly(it.message)) }
            refresh()
            val after = snapshot.sequence?.let { q -> q.video + q.audio }?.flatMap { it.clips }?.map { it.id }.orEmpty()
            val fresh = after.filter { it !in before }
            if (fresh.isNotEmpty()) onSplit(id, fresh)
        } }
    }

    override fun freezeFrame(id: Long, at: Float, seconds: Float) {
        scope.launch { editLock.withLock {
            val before = (snapshot.sequence?.let { q -> q.video + q.audio }?.flatMap { it.clips }?.map { it.id } ?: emptyList()).toSet()
            engine.tryExec("clip.insertFrameHoldSegment", JSONObject().put("clip", id).put("time", frameTicks(at.toDouble())).put("seconds", seconds.toDouble()))
                .onFailure { onMessage(Str.t(R.string.projectcontroller_freeze_failed, friendly(it.message))) }
            refresh()
            // the freeze frame and the clip's second half get the clip's grade (like a split)
            val after = snapshot.sequence?.let { q -> q.video + q.audio }?.flatMap { it.clips }?.map { it.id }.orEmpty()
            val fresh = after.filter { it !in before }
            if (fresh.isNotEmpty()) onSplit(id, fresh)
        } }
    }

    override fun setReverse(ids: List<Long>, reverse: Boolean) {
        scope.launch { editLock.withLock {
            val all = snapshot.sequence?.let { q -> q.video + q.audio }?.flatMap { it.clips }.orEmpty()
            group(Str.t(R.string.projectcontroller_undo_reverse)) {
                // the engine sets the speed at the same time: the clip's own speed is kept (linked audio follows in the engine)
                for (id in ids) {
                    val c = all.firstOrNull { it.id == id } ?: continue
                    engine.tryExec("clip.speedDuration", JSONObject().put("clips", JSONArray().put(id)).put("speed", kotlin.math.abs(c.speed) * 100.0).put("reverse", reverse))
                        .onFailure { onMessage(friendly(it.message)) }
                }
            }
            refresh()
        } }
    }

    override fun setGrouped(ids: List<Long>, grouped: Boolean) {
        if (ids.isEmpty()) return
        scope.launch { editLock.withLock {
            group(Str.t(if (grouped) R.string.projectcontroller_undo_group else R.string.projectcontroller_undo_ungroup)) {
                // the engine's group applies to the selection
                engine.tryExec("timeline.select", JSONObject().put("clips", JSONArray().apply { ids.forEach { put(it) } }).put("linked", true))
                    .onFailure { onMessage(friendly(it.message)) }
                engine.tryExec(if (grouped) "clip.group" else "clip.ungroup", JSONObject()).onFailure { onMessage(friendly(it.message)) }
            }
            refresh()
        } }
    }

    override fun remove(id: Long, ripple: Boolean) {
        run(if (ripple) "edit.rippleDelete" else "edit.clear", JSONObject().put("clips", JSONArray().put(id)))
    }

    override fun moveMany(moves: List<Triple<Clip, Float, Int>>, insert: Boolean) {
        val arr = JSONArray()
        for ((c, start, track) in moves) arr.put(JSONObject().put("clip", c.id).put("track", engineTrack(track)).put("time", frameTicks(start.toDouble())))
        // the list already contains the partners – the engine must not add them again
        runEdit("timeline.move", JSONObject().put("moves", arr).put("insert", insert).put("linked", false), Str.t(R.string.projectcontroller_undo_move))
    }

    override fun moveGroup(moves: List<Triple<Clip, Float, Int>>, insert: Boolean, captions: List<Long>, dt: Float) {
        val f = frames(dt)
        scope.launch {
            editLock.withLock {
                group(Str.t(R.string.projectcontroller_undo_move)) {
                    if (moves.isNotEmpty()) {
                        val arr = JSONArray()
                        for ((c, start, track) in moves) arr.put(JSONObject().put("clip", c.id).put("track", engineTrack(track)).put("time", frameTicks(start.toDouble())))
                        engine.tryExec("timeline.move", JSONObject().put("moves", arr).put("insert", insert).put("linked", false)).onFailure { onMessage(friendly(it.message)) }
                    }
                    if (captions.isNotEmpty() && f != 0L) {
                        engine.tryExec("captions.move", JSONObject().put("captions", JSONArray().apply { captions.forEach { put(it) } }).put("deltaFrames", f))
                            .onFailure { onMessage(friendly(it.message)) }
                    }
                    sweepSlivers()
                }
                refresh()
            }
        }
    }

    override fun deleteCaptions(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch { editLock.withLock {
            engine.tryExec("captions.delete", JSONObject().put("captions", JSONArray().apply { ids.forEach { put(it) } })).onFailure { onMessage(friendly(it.message)) }
            refresh()
        } }
    }

    override fun removeMany(ids: List<Long>, ripple: Boolean) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        run(if (ripple) "edit.rippleDelete" else "edit.clear", JSONObject().put("clips", arr))
    }

    override fun razorTracks(at: Float, tracks: List<Int>) {
        scope.launch {
            val q0 = snapshot.sequence
            val before = (q0?.let { it.video + it.audio }?.flatMap { it.clips }?.map { it.id } ?: emptyList()).toSet()
            val oldUi = state.clips.associateBy { it.id }
            val t = frameTicks(at.toDouble())
            var cuts = 0
            var err: Throwable? = null
            editLock.withLock {
                group(Str.t(R.string.projectcontroller_undo_razor)) {
                    for (tr in tracks) engine.tryExec("timeline.razor", JSONObject().put("time", t).put("track", engineTrack(tr)))
                        .onSuccess { cuts++ }.onFailure { err = it }
                }
            }
            if (cuts == 0) err?.let { onMessage(friendly(it.message)) }
            refresh()
            // color page: each new piece belongs to the clip that was on the same track at that time
            val fresh = state.clips.filter { it.id !in before }
            for ((old, pieces) in fresh.groupBy { f -> oldUi.values.firstOrNull { o -> o.track == f.track && o.start < at && o.end > at }?.id }) {
                if (old != null) onSplit(old, pieces.map { it.id })
            }
        }
    }

    override fun slip(clip: Clip, dSrc: Float) {
        run("timeline.slip", JSONObject().put("clip", clip.id).put("delta", frameTicks(dSrc.toDouble())))
    }

    override fun roll(left: Clip, right: Clip, dt: Float) {
        val cut = clipSnap(right.id)?.start
        val delta = if (cut != null) frameTicks(cut + dt) - exactTicks(cut) else frameTicks(dt.toDouble())
        runEdit("timeline.roll", JSONObject().put("left", left.id).put("right", right.id).put("delta", delta), Str.t(R.string.projectcontroller_undo_roll))
    }

    override fun setTrackFlag(track: Int, flag: String, on: Boolean) {
        run("timeline.setTrack", JSONObject().put("track", engineTrack(track)).put(flag, on))
    }

    override fun toggleEnabled(ids: List<Long>) {
        if (ids.isEmpty()) return
        run("clip.enable", JSONObject().put("clips", JSONArray().apply { ids.forEach { put(it) } }))
    }

    override fun undo() = run("edit.undo", JSONObject()).let { }
    override fun redo() = run("edit.redo", JSONObject()).let { }
    override fun clearRedo() = run("klipp.clearRedo", JSONObject()).let { }

    // ------------------------------------------------------------------ snapshot → UI

    /**
     * Reread the engine's state into the timeline. The same state as before (no change, or an edit that didn't go
     * through) does nothing – no playback rebuild, no save. [force]: do it anyway (e.g. new file lengths).
     */
    suspend fun refresh(force: Boolean = false) {
        val snap = try {
            engine.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // keep what's shown – an empty timeline would look like the project had vanished
            se.gendut.klipp.CrashReport.note("snapshot failed: ${e.message}")
            onMessage(Str.t(R.string.projectcontroller_read_project_failed, e.message.orEmpty()))
            return
        }
        if (!force && snap == snapshot && revision > 0) return
        // new clips from a new edit (not undo/redo): the engine can reuse ids after an undo – a
        // leftover grade on such an id must not follow the new clip (the split's inheritance is set afterwards)
        val prev = snapshot
        if (revision > 0 && snap.editSerial != prev.editSerial) {
            val before = prev.sequence?.let { q -> q.video + q.audio }?.flatMap { it.clips }?.mapTo(HashSet()) { it.id }.orEmpty()
            val fresh = snap.sequence?.let { q -> q.video + q.audio }?.flatMap { it.clips }?.map { it.id }?.filter { it !in before }.orEmpty()
            if (fresh.isNotEmpty()) onNewClips(fresh)
        }
        snapshot = snap
        se.gendut.klipp.projectFps = snap.fps.takeIf { it.isFinite() && it >= 1.0 } ?: 25.0
        snap.sequence?.let { q -> state.subtitle = "${q.width}×${q.height} · ${"%.2f".format(snap.fps).trimEnd('0').trimEnd('.', ',')}p · ${Str.p(R.plurals.projectcontroller_media_count, snap.media.size, snap.media.size)}" }
        state.applyEngine(toUi(snap), snap.undoIds, snap.redoIds, snap.editSerial)
        state.linkedSelection = snap.linkedSelection
        snap.sequence?.let { q ->
            state.markers = q.markers.map { it.time.toFloat() }
            q.video.indexOfFirst { it.id == q.videoTarget }.takeIf { it in 0..2 }?.let { state.targetVideo = 2 - it }
            q.audio.indexOfFirst { it.id == q.audioTarget }.takeIf { it in 0..2 }?.let { state.targetAudio = 3 + it }
            fun uiOf(t: TrackSnap, track: Int) = t.transitions.map { x ->
                val anchor = when { x.from == null -> -1; x.to == null -> 1; else -> 0 }
                val cut = t.clips.firstOrNull { it.id == x.to }?.start ?: ((x.start + x.end) / 2)
                se.gendut.klipp.UiTransition(x.id, track, x.start.toFloat(), x.end.toFloat(), anchor, cut.toFloat(), x.effect, x.reverse, x.from, x.to)
            }
            // video: V1 = 2, V2 = 1, V3 = 0; audio: A1 = 3 …
            state.transitions = q.video.take(3).flatMapIndexed { k, t -> uiOf(t, 2 - k) } + q.audio.take(3).flatMapIndexed { k, t -> uiOf(t, 3 + k) }
            if (state.selectedTransition != null && state.transitions.none { it.id == state.selectedTransition }) state.selectedTransition = null
            state.captions = (q.captions.map { se.gendut.klipp.UiCaption(it.id, it.start.toFloat(), it.end.toFloat(), it.text, it.track) } +
                q.hiddenCaptions.map { se.gendut.klipp.UiCaption(it.id, it.start.toFloat(), it.end.toFloat(), it.text, it.track, hidden = true) })
                .sortedBy { it.start }
            state.hasCaptionLane = q.captionTracks.isNotEmpty()
            state.captionsOn = q.captionTracks.isEmpty() || q.captionTracks.any { it.enabled }
            if (state.selectedCaption != null && state.captions.none { it.id == state.selectedCaption }) { state.selectedCaption = null; state.editingCaption = false }
            state.trackStates = List(TRACKS.size) { i ->
                val t = if (i <= 2) q.video.getOrNull(2 - i) else q.audio.getOrNull(i - 3)
                if (t == null) se.gendut.klipp.TrackState() else se.gendut.klipp.TrackState(t.locked, t.enabled, t.muted, t.solo, t.syncLock)
            }
            state.trackVolKf = q.audio.take(3).map { t -> if (t.autoMode == "Off") emptyList() else t.volKf.map { it.first.toFloat() to it.second.toFloat() } }
        }
        revision++
        scheduleSave()
        // heavy files (4K, high frame rate, HDR) get proxy files in the background
        runCatching { Proxies.ensure(context, snap.media.mapNotNull { m -> uris[m.id]?.let { it to m } }) }
        scope.launch { runCatching { Perf.prefetchHdr(context, snap.media.filter { it.hasVideo }.mapNotNull { uris[it.id] }) } }
        probeMedia(snap)
    }

    /**
     * File lengths according to Media3 (see [MediaProbe]): the original and the proxy. New values → the timeline and player
     * are recomputed with them. If the engine's length differs by more than a frame, it's noted (diagnostics).
     */
    private fun probeMedia(snap: Snapshot) {
        val todo = snap.media.mapNotNull { m -> uris[m.id]?.let { m to it } }
            .filter { (_, u) -> MediaProbe.cached(u) == null || (Proxies.videoUri(u) != u && MediaProbe.cached(Proxies.videoUri(u)) == null) }
        if (todo.isEmpty()) return
        scope.launch {
            var any = false
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                for ((m, u) in todo) {
                    val a = MediaProbe.cached(u) == null
                    val info = MediaProbe.probe(context, u)
                    Proxies.videoUri(u).takeIf { it != u }?.let { MediaProbe.probe(context, it) }
                    if (a && info != null) {
                        any = true
                        val frame = 1.0 / (m.fps.takeIf { it > 1 } ?: 25.0)
                        if (m.duration > 0 && kotlin.math.abs(info.duration - m.duration) > frame) {
                            se.gendut.klipp.CrashReport.note("length differs: ${m.name} engine %.3f s, Media3 %.3f s".format(m.duration, info.duration))
                        }
                        if ((info.videoStart ?: 0.0) > frame / 2) se.gendut.klipp.CrashReport.note("first frame: ${m.name} %.3f s".format(info.videoStart))
                    }
                }
            }
            if (any) refresh(force = true)
        }
    }

    // ------------------------------------------------------------------ save / open

    override fun requestSave() = scheduleSave()

    private fun scheduleSave() {
        if (restoring || saveBlocked) return
        saveJob?.cancel()
        saveJob = scope.launch {
            kotlinx.coroutines.delay(1200)
            save()
        }
    }

    /** Save right away (the app is going to the background) instead of waiting for the delay. */
    fun saveNow() {
        if (restoring || saveBlocked) return
        saveJob?.cancel()
        saveJob = scope.launch { save() }
    }

    /** Save (waits until an ongoing edit is done – never half an undo step to disk). */
    suspend fun save() = editLock.withLock { saveLocked() }

    /** Like [save]; the caller holds [editLock]. */
    private suspend fun saveLocked() {
        if (restoring || saveBlocked) return
        val snap = snapshot
        if (snap.sequence == null && snap.media.isEmpty()) return
        // everything is read before anything waits – a project switch during the save must not mix up the projects
        val id = currentId
        val pf = projectFile
        val mf = mediaFile
        val links = uris.toMap()
        val arr = JSONArray()
        for ((item, u) in offline + links) arr.put(JSONObject().put("item", item).put("uri", u.toString()))
        val q = snap.sequence
        val meta = JSONObject().put("name", currentName.ifBlank { defaultName() }).put("modified", System.currentTimeMillis())
            .put("duration", q?.duration ?: 0.0).put("width", q?.width ?: 0).put("height", q?.height ?: 0).put("fps", snap.fps)
            .put("clips", (q?.video?.sumOf { it.clips.size } ?: 0) + (q?.audio?.sumOf { it.clips.size } ?: 0))
        val side = JSONObject().put("media", arr).put("extra", extraSave()).put("meta", meta).toString()
        engine.tryExec("file.save", JSONObject().put("path", pf.absolutePath)).onFailure {
            onMessage(Str.t(R.string.projectcontroller_save_failed, it.message.orEmpty()))
            return
        }
        try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                writeAtomic(mf, side, bak = true)
                writeThumb(id, snap, links)
            }
        } catch (e: java.io.IOException) {
            onMessage(Str.t(R.string.projectcontroller_save_media_failed, e.message.orEmpty()))
            return
        }
        projectsRevision++
    }

    /** Writes via a temporary file, so an interrupted write never leaves half a file. [bak]: the previous one becomes .bak. */
    private fun writeAtomic(f: File, text: String, bak: Boolean = false) {
        val tmp = File.createTempFile(f.name, ".tmp", f.parentFile)
        try {
            // to storage before the rename: after a power loss either the old or the new file exists, whole
            java.io.FileOutputStream(tmp).use { o -> o.write(text.toByteArray(Charsets.UTF_8)); o.fd.sync() }
            if (bak && f.exists()) runCatching { f.copyTo(File(f.path + ".bak"), overwrite = true) }
            // replaces atomically (no moment without a file, as with delete + rename)
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }

    /** The side file (the backup if it's damaged) and a note if something went wrong. */
    private fun readSide(f: File): Pair<JSONObject, String?> {
        val bak = File(f.path + ".bak")
        if (!f.exists() && !bak.exists()) return JSONObject() to null
        runCatching { JSONObject(f.readText()) }.getOrNull()?.let { return it to null }
        runCatching { JSONObject(bak.readText()) }.getOrNull()?.let { return it to Str.t(R.string.projectcontroller_side_backup_used) }
        return JSONObject() to Str.t(R.string.projectcontroller_side_broken)
    }

    private fun defaultName() = Str.t(R.string.projectcontroller_default_project_name, java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))

    /** Small image for the projects page: the first frame of the first video clip. */
    private fun writeThumb(id: String, snap: Snapshot, links: Map<Long, Uri>) {
        val c = snap.sequence?.video?.firstOrNull { it.clips.isNotEmpty() }?.clips?.minByOrNull { it.start } ?: return
        val uri = links[c.item] ?: return
        val f = thumbFile(id)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 60_000) return
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val us = ((c.sourceIn + 0.5) * 1_000_000).toLong()
                val b = (if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
                else r.getFrameAtTime(us)) ?: return
                f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            } finally { r.release() }
        }
    }

    /** Opens the most recently saved project and relinks media to new file handles. */
    fun restore() = scope.launch {
        if (!projectFile.exists()) return@launch
        restoring = true
        try {
            editLock.withLock { restoreNow() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the damaged file must not be overwritten by an empty project on the next change
            saveBlocked = true
            runCatching { refresh() }
            onMessage(Str.t(R.string.projectcontroller_restore_last_failed, e.message.orEmpty()))
        } finally {
            restoring = false
        }
    }

    /** Opens [projectFile] in the engine (throws if it fails). The caller sets [restoring] and holds [editLock]. */
    private suspend fun restoreNow() {
        engine.exec("file.open", JSONObject().put("path", projectFile.absolutePath))
        var missing = 0
        val (side, sideNote) = withContext(kotlinx.coroutines.Dispatchers.IO) { readSide(mediaFile) }
        currentName = side.optJSONObject("meta")?.optString("name").orEmpty().ifBlank { if (currentId == "aktuellt") Str.t(R.string.projectcontroller_my_project) else defaultName() }
        val m = side.optJSONArray("media") ?: JSONArray()
        side.optJSONObject("extra")?.let { runCatching { onExtraRestore(it) } }
        // files are opened on a background thread (one system call per file – not on the main thread)
        val opened = withContext(kotlinx.coroutines.Dispatchers.IO) {
            (0 until m.length()).mapNotNull { i ->
                val o = m.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("item", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
                val u = runCatching { Uri.parse(o.getString("uri")) }.getOrNull() ?: return@mapNotNull null
                Triple(id, u, runCatching { context.contentResolver.openFileDescriptor(u, "r") }.getOrNull())
            }
        }
        val unreachable = ArrayList<Long>()
        for ((id, u, pfd) in opened) {
            if (pfd == null) { missing++; offline[id] = u; unreachable += id; continue }
            fds += pfd
            uris[id] = u
            engine.tryExec("media.relink", JSONObject().put("item", id).put("path", "/proc/self/fd/${pfd.fd}").put("force", true))
                .onFailure { missing++; se.gendut.klipp.CrashReport.note("relink $id: ${it.message}") }
        }
        // unreachable files: the engine still has the previous run's address (/proc/self/fd/N) – the same number may now
        // be a different file, and then the export would read the wrong video. Marked as missing.
        if (unreachable.isNotEmpty()) {
            engine.tryExec("media.makeOffline", JSONObject().put("items", JSONArray().apply { unreachable.forEach { put(it) } }))
        }
        // leftovers (clips shorter than half a frame) from earlier versions are cleaned up
        if (sliverIds().isNotEmpty()) group(Str.t(R.string.projectcontroller_undo_clean_slivers)) { sweepSlivers() }
        refresh()
        state.playhead = snapshot.playhead.toFloat()
        onMessage((if (missing == 0) Str.t(R.string.projectcontroller_restored) else Str.p(R.plurals.projectcontroller_restored_missing, missing, missing)) + (sideNote?.let { " ($it)" } ?: ""))
    }

    // ------------------------------------------------------------------ projects

    data class ProjectInfo(
        val id: String, val name: String, val modified: Long, val duration: Double,
        val width: Int, val height: Int, val fps: Double, val clips: Int, val thumb: java.io.File?,
    )

    /** All saved projects, most recently modified first (including those archived before the projects page). */
    fun listProjects(): List<ProjectInfo> = projectDir.listFiles { f -> f.name.endsWith(".fcproj") }.orEmpty().map { f ->
        val id = f.name.removeSuffix(".fcproj")
        val meta = runCatching { JSONObject(java.io.File(projectDir, "$id.media.json").readText()).optJSONObject("meta") }.getOrNull()
        val name = meta?.optString("name").orEmpty().ifBlank {
            when {
                id == "aktuellt" -> Str.t(R.string.projectcontroller_my_project)
                id.startsWith("arkiv_") -> Str.t(R.string.projectcontroller_archive_name, id.removePrefix("arkiv_").let { st -> runCatching {
                    java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).parse(st)!!)
                }.getOrDefault(st) })
                else -> id
            }
        }
        ProjectInfo(
            id, if (id == currentId && currentName.isNotBlank()) currentName else name, meta?.optLong("modified") ?: f.lastModified(),
            meta?.optDouble("duration", 0.0) ?: 0.0, meta?.optInt("width") ?: 0, meta?.optInt("height") ?: 0,
            meta?.optDouble("fps", 0.0) ?: 0.0, meta?.optInt("clips") ?: 0, thumbFile(id).takeIf { it.exists() },
        )
    }.sortedByDescending { it.modified }

    private fun switchTo(id: String) {
        currentId = id
        prefs.edit().putString("current", id).apply()
    }

    /** Empty engine under a new project id: old addresses and file handles are released. */
    private suspend fun clearFor(id: String) {
        engine.tryExec("file.newProject")
        uris.clear()
        offline.clear()
        fds.forEach { runCatching { it.close() } }
        fds.clear()
        switchTo(id)
        currentName = ""
        runCatching { onExtraRestore(JSONObject()) }
    }

    /** Save the current one and start a new one. Resolution/frame rate null = like the first clip. */
    fun newProject(spec: se.gendut.klipp.NewProjectSpec) =
        newProject(spec.name, spec.width, spec.height, spec.fps, spec.sampleRate, spec.mono, spec.export)

    /** Audio for the sequence being created (now, or on the first import): sample rate and mono/stereo. */
    private var seqSampleRate = 48_000
    private var seqMono = false

    /** Parameters for a new sequence, with the project's audio choices. */
    private fun newSeqParams(): JSONObject = JSONObject().put("name", Str.t(R.string.projectcontroller_default_sequence_name)).put("sampleRate", seqSampleRate).put("mix", if (seqMono) "Mono" else "Stereo")

    fun newProject(
        name: String = "", width: Int? = null, height: Int? = null, fps: Double? = null,
        sampleRate: Int = 48_000, mono: Boolean = false, export: ExportSettings? = null,
    ) = scope.launch {
        if (restoring) return@launch
        saveJob?.cancel()
        editLock.withLock {
        saveLocked()
        // no saving while the engine switches projects (it would write the wrong content to the wrong file)
        restoring = true
        try {
            clearFor("p" + System.currentTimeMillis())
            currentName = name.ifBlank { defaultName() }
            seqSampleRate = sampleRate
            seqMono = mono
            export?.let { ExportSettings.save(context, currentId, it) }
            if (width != null && height != null) {
                engine.tryExec("file.newSequence", newSeqParams().put("width", width).put("height", height).put("fps", fps ?: 25.0))
            }
            saveBlocked = false
            refresh()
            state.playhead = 0f
        } finally {
            restoring = false
        }
        saveLocked()
        }
        onMessage(Str.t(R.string.projectcontroller_new_project, currentName))
    }

    /** Open a saved project (the current one is saved first). If that fails, the previous one is reopened. */
    fun openProject(id: String) = scope.launch {
        if (id == currentId || restoring) return@launch
        if (!File(projectDir, "$id.fcproj").exists()) { onMessage(Str.t(R.string.projectcontroller_project_missing)); return@launch }
        saveJob?.cancel()
        editLock.withLock {
        saveLocked()
        val prev = currentId
        restoring = true
        try {
            clearFor(id)
            try {
                restoreNow()
                saveBlocked = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // don't stay in an empty project under the new id – the next change would overwrite it
                clearFor(prev)
                try {
                    if (projectFile.exists()) restoreNow() else refresh()
                    saveBlocked = false
                    onMessage(Str.t(R.string.projectcontroller_open_failed, e.message.orEmpty()))
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    saveBlocked = true
                    refresh()
                    onMessage(Str.t(R.string.projectcontroller_open_failed_blocked, e.message.orEmpty()))
                }
            }
        } finally {
            restoring = false
        }
        }
        projectsRevision++
    }

    fun renameProject(id: String, name: String) = scope.launch {
        if (id == currentId) { currentName = name; save(); return@launch }
        val f = java.io.File(projectDir, "$id.media.json")
        try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val o = readSide(f).first
                o.put("meta", (o.optJSONObject("meta") ?: JSONObject()).put("name", name))
                writeAtomic(f, o.toString(), bak = true)
            }
        } catch (e: java.io.IOException) {
            onMessage(Str.t(R.string.projectcontroller_rename_failed_reason, e.message.orEmpty()))
        }
        projectsRevision++
    }

    fun duplicateProject(id: String) = scope.launch {
        if (id == currentId) save()
        val nid = "p" + System.currentTimeMillis()
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                java.io.File(projectDir, "$id.fcproj").copyTo(java.io.File(projectDir, "$nid.fcproj"))
                val o = readSide(java.io.File(projectDir, "$id.media.json")).first
                val meta = o.optJSONObject("meta") ?: JSONObject()
                o.put("meta", meta.put("name", Str.t(R.string.projectcontroller_copy_name, meta.optString("name", Str.t(R.string.projectcontroller_project_fallback_name)))).put("modified", System.currentTimeMillis()))
                writeAtomic(java.io.File(projectDir, "$nid.media.json"), o.toString())
                thumbFile(id).takeIf { it.exists() }?.let { runCatching { it.copyTo(thumbFile(nid)) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a half copy is removed, and the app doesn't crash
            runCatching { java.io.File(projectDir, "$nid.fcproj").delete(); java.io.File(projectDir, "$nid.media.json").delete() }
            onMessage(Str.t(R.string.projectcontroller_duplicate_failed, e.message.orEmpty()))
        }
        projectsRevision++
    }

    /** Deletes a project (not the open one). The video files on the phone aren't touched. */
    fun deleteProject(id: String) = scope.launch {
        if (id == currentId) { onMessage(Str.t(R.string.projectcontroller_open_another_first)); return@launch }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            for (ext in listOf("fcproj", "media.json", "media.json.bak", "jpg")) java.io.File(projectDir, "$id.$ext").delete()
        }
        projectsRevision++
    }

    /** Project settings: resolution, frame rate, audio sample rate. */
    fun setSequenceSettings(width: Int, height: Int, fps: Double, sampleRate: Int?, scaleMotion: Boolean = false) = scope.launch { editLock.withLock {
        val p = JSONObject().put("width", width).put("height", height).put("fps", fps).put("scaleMotion", scaleMotion)
        sampleRate?.let { p.put("sampleRate", it) }
        if (snapshot.sequence == null) engine.tryExec("file.newSequence", newSeqParams().put("width", width).put("height", height).put("fps", fps).apply { sampleRate?.let { put("sampleRate", it) } })
        else engine.tryExec("sequence.settings", p).onFailure { onMessage(Str.t(R.string.projectcontroller_change_failed, friendly(it.message))) }
        refresh()
        onMessage(Str.t(R.string.projectcontroller_sequence_now, width, height, "%.3f".format(fps).trimEnd('0').trimEnd(',', '.')))
    } }

    /** Linked selection on/off (the engine doesn't save it in the project, so it goes in the side file). */
    fun setLinkedSelection(on: Boolean) = scope.launch { editLock.withLock {
        engine.tryExec("sequence.linkedSelection", JSONObject().put("on", on)).onFailure { onMessage(friendly(it.message)) }
        refresh()
    } }

    /**
     * Speed ramp: the clip is split into short pieces with stepwise changing speed along a smooth curve (the engine has one
     * fixed speed per clip – this way the ramp works the same in playback, audio and export). One undo step.
     */
    fun speedRamp(clipId: Long, ramp: SpeedRamp) = scope.launch {
        editLock.withLock {
            group(Str.t(R.string.projectcontroller_undo_speed_ramp)) {
                val q0 = snapshot.sequence ?: return@group
                val tracks = q0.video + q0.audio
                val tr = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } } ?: return@group
                val c = tr.clips.first { it.id == clipId }
                val plan = SpeedRamp.plan(ramp, c.start, c.duration, c.speed)
                if (plan.speeds.size < 2) return@group
                val before = tracks.flatMap { it.clips }.map { it.id }.toSet()
                // split at every speed change (the clip lying there at that moment – a split creates new ids). The engine's
                // state is read directly (not refresh: no intermediate states to the screen or the save mid-group)
                var cur = q0
                for (t in plan.cuts) {
                    val at = (cur.video + cur.audio).firstOrNull { it.id == tr.id }?.clips?.firstOrNull { t > it.start + 1e-4 && t < it.start + it.duration - 1e-4 } ?: continue
                    engine.tryExec("timeline.razor", JSONObject().put("time", frameTicks(t)).put("clip", at.id)).onFailure { onMessage(friendly(it.message)) }
                    cur = engine.snapshot().sequence ?: break
                }
                val q = cur
                val all = (q.video + q.audio).flatMap { it.clips }
                val pieces = (q.video + q.audio).first { it.id == tr.id }.clips.filter { it.start >= c.start - 1e-4 && it.start < c.start + c.duration - 1e-4 }.sortedBy { it.start }
                val fresh = all.map { it.id }.filter { it !in before }
                if (fresh.isNotEmpty()) onSplit(clipId, fresh)
                // speed per piece (back to front, so ripple doesn't move pieces that haven't got their speed yet)
                for ((i, p) in pieces.withIndex().reversed()) {
                    val speed = plan.speeds.getOrNull(i) ?: continue
                    val ids = JSONArray().put(p.id)
                    // linked audio (same link group, same start) gets the same speed
                    p.link?.let { l -> all.filter { it.id != p.id && it.link == l && kotlin.math.abs(it.start - p.start) < 1e-3 }.forEach { ids.put(it.id) } }
                    engine.tryExec("clip.speedDuration", JSONObject().put("clips", ids).put("speed", speed).put("ripple", true)).onFailure { onMessage(friendly(it.message)) }
                }
            }
        }
        refresh()
    }

    fun setSpeed(clipId: Long, percent: Int) = scope.launch { editLock.withLock {
        engine.tryExec("clip.speedDuration", JSONObject().put("clips", JSONArray().put(clipId)).put("speed", percent).put("ripple", true))
            .onFailure { onMessage(friendly(it.message)) }
        refresh()
    } }

    // ------------------------------------------------------------------ markers, targets, tracks

    fun addMarker(atSec: Float) = scope.launch { editLock.withLock {
        engine.tryExec("markers.add", JSONObject().put("target", "program").put("time", frameTicks(atSec.toDouble())))
            .onFailure { onMessage(it.message ?: Str.t(R.string.projectcontroller_add_marker_failed)) }
        refresh()
    } }

    /** Choose which track the source's video (V) or audio (A) goes on for insert/overwrite. */
    fun setTarget(uiTrack: Int) = scope.launch { editLock.withLock {
        engine.tryExec("timeline.setTargeting", JSONObject().put("track", engineTrack(uiTrack)).put("targeted", true).put("sourcePatch", true))
            .onFailure { onMessage(it.message ?: Str.t(R.string.projectcontroller_target_failed)) }
        refresh()
    } }

    /** Track volume (dB), mute and solo for audio tracks 0..2 (A1..A3). */
    fun setAudioTrack(index: Int, volumeDb: Double? = null, muted: Boolean? = null, solo: Boolean? = null) = scope.launch { editLock.withLock {
        val p = JSONObject().put("track", "A${index + 1}")
        volumeDb?.let { p.put("volumeDb", it) }
        muted?.let { p.put("muted", it) }
        solo?.let { p.put("solo", it) }
        engine.tryExec("timeline.setTrack", p).onFailure { onMessage(it.message ?: Str.t(R.string.projectcontroller_track_change_failed)) }
        refresh()
    } }

    // ------------------------------------------------------------------ audio: automation, loudness, voice-over

    /** Volume automation: a keyframe on track A(index+1) at [at] (dB), or removed if one is already there. */
    fun toggleTrackKeyframe(index: Int, at: Double, db: Double) = scope.launch { editLock.withLock {
        val t = snapshot.sequence?.audio?.getOrNull(index) ?: return@launch
        val frame = 1.0 / snapshot.fps.coerceAtLeast(1.0)
        val near = t.volKf.firstOrNull { kotlin.math.abs(it.first - at) < frame / 2 }
        val p = JSONObject().put("strip", "A${index + 1}").put("lane", "volume")
        if (near != null) engine.tryExec("mixer.deleteKeyframe", p.put("time", secToTicks(near.first))).onFailure { onMessage(friendly(it.message)) }
        else engine.tryExec("mixer.setKeyframe", p.put("time", frameTicks(at)).put("value", db.coerceIn(-60.0, 12.0))).onFailure { onMessage(friendly(it.message)) }
        refresh()
    } }

    /** Set (or move to) the value at [at] – the fader writes automation when the track has keyframes. */
    fun setTrackKeyframe(index: Int, at: Double, db: Double) = scope.launch { editLock.withLock {
        engine.tryExec("mixer.setKeyframe", JSONObject().put("strip", "A${index + 1}").put("lane", "volume").put("time", frameTicks(at)).put("value", db.coerceIn(-60.0, 12.0)))
            .onFailure { onMessage(friendly(it.message)) }
        refresh()
    } }

    fun clearTrackKeyframes(index: Int) = run("mixer.clearLane", JSONObject().put("strip", "A${index + 1}").put("lane", "volume")).let { }

    /** Loudness measurement of the whole mix (the engine's mix, before master): integrated LUFS, true peak, LRA. */
    /**
     * Automatic ducking (the engine's Essential Sound): the music on audio track [track] is lowered [reduceDb] dB where something is heard on
     * the other tracks (speech, voice-over), with smooth transitions – as volume keyframes on the music clips (their previous
     * volume keyframes are replaced). One undo step. Returns the number of ducked clips.
     */
    suspend fun autoDuck(track: Int, reduceDb: Double): Result<Int> = editLock.withLock {
        val q = snapshot.sequence ?: return@withLock Result.failure(IllegalStateException(Str.t(R.string.projectcontroller_timeline_empty)))
        val ids = q.audio.getOrNull(track)?.clips?.filter { it.enabled }?.map { it.id }.orEmpty()
        if (ids.isEmpty()) return@withLock Result.failure(IllegalStateException(Str.t(R.string.projectcontroller_no_music, track + 1)))
        if (q.audio.withIndex().none { (i, t) -> i != track && t.clips.any { it.enabled } })
            return@withLock Result.failure(IllegalStateException(Str.t(R.string.projectcontroller_no_speech)))
        val arr = JSONArray().apply { ids.forEach { put(it) } }
        var result: Result<Int> = Result.success(0)
        group(Str.t(R.string.projectcontroller_undo_duck_music)) {
            // the music is typed as music and ducked against all untyped audio (the speech) – the type doesn't change the sound in Klipp
            engine.tryExec("essentialSound.setType", JSONObject().put("clips", arr).put("type", "music"))
            engine.tryExec("essentialSound.set", JSONObject().put("clips", arr).put("values", JSONObject()
                .put("ducking.against_untyped", true).put("ducking.reduce_db", reduceDb).put("ducking.fade_s", 0.4)))
            result = engine.tryExec("essentialSound.generateDucking", JSONObject().put("clips", arr))
                .map { r -> r.optJSONArray("clips")?.let { a -> (0 until a.length()).count { a.getJSONObject(it).optJSONArray("regions")?.length() ?: 0 > 0 } } ?: 0 }
        }
        refresh()
        result.recoverCatching { throw IllegalStateException(Str.t(R.string.projectcontroller_ducking_failed, friendly(it.message))) }
    }

    /** Remove the ducking (the volume keyframes) from the music on [track]: the clips get their full level back. One undo step. */
    suspend fun removeDucking(track: Int): Result<Int> = editLock.withLock {
        val q = snapshot.sequence ?: return@withLock Result.success(0)
        val clips = q.audio.getOrNull(track)?.clips?.filter { it.kf.containsKey(KfKeys.LEVEL) }.orEmpty()
        if (clips.isEmpty()) return@withLock Result.success(0)
        group(Str.t(R.string.projectcontroller_undo_remove_ducking)) {
            for (c in clips) {
                val base = c.kf[KfKeys.LEVEL]?.maxOfOrNull { it.v.firstOrNull() ?: 0.0 } ?: c.levelDb
                engine.tryExec("effects.toggleAnimation", JSONObject().put("clip", c.id).put("effect", "volume").put("param", "level"))
                setParam(c.id, "volume", "level", base)
            }
        }
        refresh()
        Result.success(clips.size)
    }

    suspend fun measureLoudness(): Result<Triple<Double, Double, Double>> {
        if (snapshot.sequence?.audio?.any { it.clips.isNotEmpty() } != true) return Result.failure(IllegalStateException(Str.t(R.string.projectcontroller_no_audio)))
        val wav = File(context.cacheDir, "loudness_${System.currentTimeMillis()}.wav")
        return try {
            engine.exec("file.exportMedia", JSONObject().put("path", wav.absolutePath).put("format", "wav").put("wait", true))
            val o = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { JSONObject(Engine.nativeMeasureWav(wav.absolutePath)) }
            if (o.has("error")) Result.failure(IllegalStateException(o.optString("error")))
            else {
                val l = o.optDouble("lufs")
                if (l.isNaN() || l < -70) Result.failure(IllegalStateException(Str.t(R.string.projectcontroller_mix_silent))) else Result.success(Triple(l, o.optDouble("truePeak"), o.optDouble("lra")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            wav.delete()
        }
    }

    /** Places a recorded file (voice-over) on audio track A(track+1) at [atSec], audio only, overwriting. */
    fun placeRecording(file: File, atSec: Double, track: Int, name: String) = placeAudio(file, atSec, track, name,
        Str.t(R.string.projectcontroller_undo_voiceover), { Str.t(R.string.projectcontroller_voiceover_placed, it + 1) },
        { Str.t(R.string.projectcontroller_voiceover_failed, it) })

    /** Places a sound effect ([seconds] long) at [atSec] on the first free audio track (see SoundFx.freeTrack). */
    fun placeSound(file: File, atSec: Double, seconds: Double, name: String) {
        val busy = snapshot.sequence?.audio.orEmpty().take(3).map { t -> t.clips.map { it.start to it.end } }
        placeAudio(file, atSec, SoundFx.freeTrack(busy, atSec, seconds), name, Str.t(R.string.projectcontroller_undo_sound),
            { Str.t(R.string.projectcontroller_sound_placed, it + 1) }, { Str.t(R.string.projectcontroller_sound_failed, it) })
    }

    private fun placeAudio(file: File, atSec: Double, track: Int, name: String, undo: String, placed: (Int) -> String, failed: (String) -> String) =
        scope.launch { editLock.withLock {
        try {
            val u = Uri.fromFile(file)
            val pfd = context.contentResolver.openFileDescriptor(u, "r") ?: error("cannot open the file")
            fds += pfd
            group(undo) {
                val r = engine.exec("file.import", JSONObject().put("paths", JSONArray().put("/proc/self/fd/${pfd.fd}")))
                val id = r.optJSONArray("items")?.optLong(0) ?: error("the import produced no clip")
                uris[id] = u
                engine.tryExec("clip.rename", JSONObject().put("item", id).put("name", name))
                engine.exec("timeline.place", JSONObject().put("item", id).put("track", "A${track + 1}").put("time", frameTicks(atSec)).put("insert", false).put("video", false))
            }
            refresh()
            onMessage(placed(track))
        } catch (e: Exception) {
            onMessage(failed(e.message.orEmpty()))
        }
    } }

    // ------------------------------------------------------------------ properties, titles, fades, captions

    private fun find(id: Long): Triple<ClipSnap, TrackSnap, Boolean>? {
        val q = snapshot.sequence ?: return null
        for (t in q.video) t.clips.firstOrNull { it.id == id }?.let { return Triple(it, t, false) }
        for (t in q.audio) t.clips.firstOrNull { it.id == id }?.let { return Triple(it, t, true) }
        return null
    }

    override fun props(id: Long): se.gendut.klipp.ClipProps? {
        val (c0, t, audio) = find(id) ?: return null
        // the values at the playhead (keyframes evaluated) – reads the playhead, so the panel follows along
        val c = c0.at(state.playhead.toDouble())
        val q = snapshot.sequence ?: return null
        val col = c.text?.color
        return se.gendut.klipp.ClipProps(
            id = c.id, name = c.name, isAudio = audio,
            x = c.x?.toFloat(), y = c.y?.toFloat(), scale = c.scale.toFloat(), rotation = c.rotation.toFloat(),
            opacity = c.opacity.toFloat(), levelDb = c.levelDb.toFloat(),
            title = c.text?.text, textSize = c.text?.size?.toFloat() ?: 100f, titleLook = c.text?.name ?: "",
            textColor = if (col == null) 0xFFFFFF else ((col[0] * 255).toInt().coerceIn(0, 255) shl 16) or ((col[1] * 255).toInt().coerceIn(0, 255) shl 8) or (col[2] * 255).toInt().coerceIn(0, 255),
            fadeIn = t.transitions.firstOrNull { it.to == id }?.duration?.toFloat(),
            fadeOut = t.transitions.firstOrNull { it.from == id }?.duration?.toFloat(),
            seqW = q.width, seqH = q.height,
            kf = c0.kf.mapValues { (_, l) -> l.map { se.gendut.klipp.UiKf(it.t.toFloat(), it.mt, it.interp) } },
            clipStart = c0.start.toFloat(), clipEnd = c0.end.toFloat(),
            afx = c0.afx,
        )
    }

    override fun addAudioFx(id: Long, effect: String) = run("effects.apply", JSONObject().put("clips", JSONArray().put(id)).put("effect", effect)).let { }
    override fun removeAudioFx(id: Long, index: Int) = run("effects.remove", JSONObject().put("clip", id).put("index", index)).let { }
    override fun toggleAudioFx(id: Long, index: Int) = run("effects.toggleEnabled", JSONObject().put("clip", id).put("index", index)).let { }
    override fun setAudioFxParam(id: Long, index: Int, param: String, value: Double) = setAudioFxParams(id, index, mapOf(param to value))

    override fun setAudioFxParams(id: Long, index: Int, values: Map<String, Double>) {
        if (values.isEmpty()) return
        scope.launch {
            val fx = find(id)?.first?.afx?.firstOrNull { it.index == index }?.id ?: ""
            editLock.withLock { group(AudioFxCatalog.label(fx), on = values.size > 1) {
                for ((param, value) in values) {
                    // on/off parameters are bool in the engine, choices (slope) integers
                    val v: Any = when {
                        AudioFxCatalog.isToggle(fx, param) -> value > 0.5
                        AudioFxCatalog.isChoice(param) -> Math.round(value).toInt()
                        else -> value
                    }
                    engine.tryExec("effects.setParam", JSONObject().put("clip", id).put("effect", index).put("param", param).put("value", v))
                        .onFailure { onMessage(friendly(it.message)) }
                }
            } }
            refresh()
        }
    }

    /** The playhead within the clip (at most the last frame) – where keyframes land. */
    private fun clampIn(c: ClipSnap, at: Double) = at.coerceIn(c.start, (c.end - 1.0 / snapshot.fps.coerceAtLeast(1.0)).coerceAtLeast(c.start))

    override fun toggleAnimation(id: Long, key: String, at: Float) {
        scope.launch { editLock.withLock {
            val (c, _, _) = find(id) ?: return@launch
            val (eff, param) = KfKeys.engine(key)
            val tt = clampIn(c, at.toDouble())
            if (c.kf.containsKey(key)) {
                // off: keep the value at the playhead as a fixed value
                val v = c.at(tt)
                engine.tryExec("effects.toggleAnimation", JSONObject().put("clip", id).put("effect", eff).put("param", param)).onFailure { onMessage(friendly(it.message)) }
                setParam(id, eff, param, valueOf(key, v))
            } else {
                engine.tryExec("playhead.set", JSONObject().put("time", frameTicks(tt)))
                onHold(c, key, tt) {
                    engine.tryExec("effects.setParam", JSONObject().put("clip", id).put("effect", eff).put("param", param)
                        .put("value", valueOf(key, c)).put("keyframe", true).put("time", frameTicks(tt))).onFailure { onMessage(friendly(it.message)) }
                }
            }
            refresh()
        } }
    }

    private fun valueOf(key: String, c: ClipSnap): Any = when (key) {
        KfKeys.POSITION -> JSONArray().put(c.x ?: ((snapshot.sequence?.width ?: 1920) / 2.0)).put(c.y ?: ((snapshot.sequence?.height ?: 1080) / 2.0))
        KfKeys.SCALE -> c.scale
        KfKeys.ROTATION -> c.rotation
        KfKeys.OPACITY -> c.opacity
        else -> c.levelDb
    }

    override fun toggleKeyframe(id: Long, key: String, at: Float) {
        scope.launch { editLock.withLock {
            val (c, _, _) = find(id) ?: return@launch
            val (eff, param) = KfKeys.engine(key)
            val tt = clampIn(c, at.toDouble())
            val frame = 1.0 / snapshot.fps.coerceAtLeast(1.0)
            val existing = c.kf[key]?.firstOrNull { kotlin.math.abs(it.t - tt) < frame / 2 }
            if (existing != null) {
                engine.tryExec("effects.deleteKeyframe", JSONObject().put("clip", id).put("effect", eff).put("param", param).put("mediaTime", existing.mt))
                    .onFailure { onMessage(friendly(it.message)) }
            } else onHold(c, key, tt) {
                engine.tryExec("effects.setParam", JSONObject().put("clip", id).put("effect", eff).put("param", param)
                    .put("value", valueOf(key, c.at(tt))).put("keyframe", true).put("time", frameTicks(tt))).onFailure { onMessage(friendly(it.message)) }
            }
            refresh()
        } }
    }

    override fun moveKeyframe(id: Long, key: String, mt: Long, to: Float) {
        scope.launch { editLock.withLock {
            val (c, _, _) = find(id) ?: return@launch
            val (eff, param) = KfKeys.engine(key)
            engine.tryExec("effects.moveKeyframe", JSONObject().put("clip", id).put("effect", eff).put("param", param)
                .put("mediaTime", mt).put("to", c.mediaTicksAt(clampIn(c, to.toDouble())))).onFailure { onMessage(friendly(it.message)) }
            refresh()
        } }
    }

    override fun setInterpolation(id: Long, key: String, mt: Long, interp: String) {
        scope.launch { editLock.withLock {
            val (eff, param) = KfKeys.engine(key)
            engine.tryExec("effects.setInterpolation", JSONObject().put("clip", id).put("effect", eff).put("param", param)
                .put("mediaTime", mt).put("interpolation", interp)).onFailure { onMessage(friendly(it.message)) }
            refresh()
        } }
    }

    private suspend fun setParam(clip: Long, effect: String, param: String, value: Any): Boolean {
        // animated parameter: the value goes into a keyframe at the playhead (within the clip)
        val p = JSONObject().put("clip", clip).put("effect", effect).put("param", param).put("value", value)
        val c = find(clip)?.first
        val tt = c?.let { clampIn(it, state.playhead.toDouble()) }
        if (c != null && tt != null) p.put("time", frameTicks(tt))
        var ok = false
        suspend fun write() { val r = engine.tryExec("effects.setParam", p); r.onFailure { onMessage(friendly(it.message)) }; ok = r.isSuccess }
        val key = KEYS.firstOrNull { KfKeys.engine(it) == effect to param }
        if (c != null && tt != null && key != null && c.kf.containsKey(key)) onHold(c, key, tt) { write() } else write()
        return ok
    }

    /**
     * A keyframe write ([write]) at timeline [tt]. The engine puts a keyframe at the clip's source time there – on a
     * freeze frame that is the frozen frame for the whole clip, so every keyframe would land on one spot (at the start)
     * and replace the previous one. There the new keyframe is moved to the moving source time, where the engine
     * evaluates the effect; a keyframe already on the frozen spot is moved aside meanwhile.
     */
    private suspend fun onHold(c: ClipSnap, key: String, tt: Double, write: suspend () -> Unit) {
        val h = c.hold
        val to = c.mediaTicksAt(tt)
        val frozen = h?.let { exactTicks(it) }
        if (frozen == null || frozen == to) { write(); return }
        val (eff, param) = KfKeys.engine(key)
        fun move(from: Long, dest: Long) = JSONObject().put("clip", c.id).put("effect", eff).put("param", param).put("mediaTime", from).put("to", dest)
        val parked = c.kf[key]?.any { it.mt == frozen } == true
        val aside = frozen - 1
        group(Str.t(R.string.keyframeui_add_keyframe)) {
            if (parked) engine.tryExec("effects.moveKeyframe", move(frozen, aside))
            write()
            engine.tryExec("effects.moveKeyframe", move(frozen, to))
            if (parked) engine.tryExec("effects.moveKeyframe", move(aside, frozen))
        }
    }

    override fun setProps(id: Long, x: Float?, y: Float?, scale: Float?, rotation: Float?, opacity: Float?, levelDb: Float?) {
        scope.launch { editLock.withLock {
            val c = find(id)?.first?.at(state.playhead.toDouble()) ?: return@launch
            val q = snapshot.sequence ?: return@launch
            if (x != null || y != null) {
                val px = (x ?: c.x?.toFloat() ?: (q.width / 2f)).toDouble()
                val py = (y ?: c.y?.toFloat() ?: (q.height / 2f)).toDouble()
                setParam(id, "motion", "position", JSONArray().put(px).put(py))
            }
            scale?.let { setParam(id, "motion", "scale", it.toDouble()) }
            rotation?.let { setParam(id, "motion", "rotation", it.toDouble()) }
            opacity?.let { setParam(id, "opacity", "opacity", it.toDouble().coerceIn(0.0, 100.0)) }
            levelDb?.let { setParam(id, "volume", "level", it.toDouble().coerceIn(-60.0, 15.0)) }
            refresh()
        } }
    }

    override fun resetMotion(id: Long) {
        val q = snapshot.sequence ?: return
        setProps(id, q.width / 2f, q.height / 2f, 100f, 0f, 100f)
    }

    override fun setTransition(id: Long, edgeIn: Boolean, seconds: Float?) {
        scope.launch { editLock.withLock {
            val (_, t, audio) = find(id) ?: return@launch
            val old = t.transitions.filter { if (edgeIn) it.to == id else it.from == id }
            if (old.isNotEmpty()) {
                engine.tryExec("sequence.removeTransition", JSONObject().put("transitions", JSONArray(old.map { it.id })))
                    .onFailure { onMessage(friendly(it.message)) }
            }
            if (seconds != null) {
                val frames = Math.round(seconds * snapshot.fps).toInt().coerceAtLeast(1)
                // video: Cross Dissolve; audio: Constant Power (Premiere's default for audio)
                engine.tryExec(
                    if (audio) "sequence.applyAudioTransition" else "sequence.applyVideoTransition",
                    JSONObject().put("clip", id).put("effect", if (audio) "constant_power" else "cross_dissolve").put("frames", frames).put("edge", if (edgeIn) "in" else "out"),
                ).onFailure { onMessage(Str.t(R.string.projectcontroller_fade_failed, friendly(it.message))) }
            }
            refresh()
        } }
    }

    override fun resizeTransition(transition: Long, seconds: Float) {
        scope.launch { editLock.withLock {
            val frames = Math.round(seconds * snapshot.fps).toInt().coerceAtLeast(1)
            engine.tryExec("sequence.setTransition", JSONObject().put("transition", transition).put("frames", frames))
                .onFailure { onMessage(Str.t(R.string.projectcontroller_fade_too_long)) }
            refresh()
        } }
    }

    override fun setTransitionType(transition: Long, effect: String, reverse: Boolean) {
        scope.launch { editLock.withLock {
            val q = snapshot.sequence ?: return@launch
            val audio = q.audio.any { t -> t.transitions.any { it.id == transition } }
            if (audio) return@launch
            val x = q.video.flatMap { it.transitions }.firstOrNull { it.id == transition } ?: return@launch
            if (x.effect == effect && x.reverse == reverse) return@launch
            val clip = x.to ?: x.from ?: return@launch
            val frames = Math.round(x.duration * snapshot.fps).toInt().coerceAtLeast(1)
            // the engine doesn't change the effect of a transition: remove and re-add, then the same position and length again
            // place [eff] where the old one was, with the same position and length
            suspend fun place(eff: String, rev: Boolean): Boolean {
                val r = engine.tryExec(
                    "sequence.applyVideoTransition",
                    JSONObject().put("clip", clip).put("effect", eff).put("frames", frames).put("edge", if (x.to != null) "in" else "out").put("reverse", rev),
                )
                if (r.isFailure) return false
                val after = engine.snapshot().sequence?.video?.flatMap { it.transitions }?.firstOrNull { it.from == x.from && it.to == x.to }
                if (after != null && (kotlin.math.abs(after.start - x.start) > 1e-4 || kotlin.math.abs(after.duration - x.duration) > 1e-4)) {
                    engine.tryExec("sequence.setTransition", JSONObject().put("transition", after.id).put("start", secToTicks(x.start)).put("duration", secToTicks(x.duration)))
                }
                return true
            }
            group(Str.t(R.string.projectcontroller_undo_transition)) {
                engine.tryExec("sequence.removeTransition", JSONObject().put("transitions", JSONArray().put(transition))).onFailure { onMessage(friendly(it.message)); return@group }
                // if the new one failed: the old one back (never a vanished transition)
                if (!place(effect, reverse)) {
                    place(x.effect, x.reverse)
                    onMessage(Str.t(R.string.projectcontroller_transition_change_failed))
                }
            }
            refresh()
        } }
    }

    override fun addCutTransition(right: Long, effect: String, seconds: Float, onAdded: (Long) -> Unit) {
        scope.launch { editLock.withLock {
            val (_, t, audio) = find(right) ?: return@launch
            // already a transition at the edit point: select it instead
            t.transitions.firstOrNull { it.to == right }?.let { refresh(); onAdded(it.id); return@launch }
            val frames = Math.round(seconds * snapshot.fps).toInt().coerceAtLeast(1)
            val r = engine.tryExec(
                if (audio) "sequence.applyAudioTransition" else "sequence.applyVideoTransition",
                JSONObject().put("clip", right).put("effect", if (audio) "constant_power" else effect).put("frames", frames).put("edge", "in"),
            )
            refresh()
            r.onFailure { onMessage(Str.t(R.string.projectcontroller_transition_failed, friendly(it.message))); return@launch }
            val q = snapshot.sequence ?: return@launch
            (if (audio) q.audio else q.video).flatMap { it.transitions }.firstOrNull { it.to == right }?.let { onAdded(it.id) }
        } }
    }

    override fun removeTransition(transition: Long) {
        scope.launch { editLock.withLock {
            engine.tryExec("sequence.removeTransition", JSONObject().put("transitions", JSONArray().put(transition)))
                .onFailure { onMessage(friendly(it.message)) }
            refresh()
        } }
    }

    override fun addTitle(text: String, at: Float, look: String) {
        scope.launch { editLock.withLock {
            val q = snapshot.sequence ?: run { onMessage(Str.t(R.string.projectcontroller_add_clip_first)); return@launch }
            val l = TitleLook.parse(look)
            val size = q.height * when (l.template) {
                TitleTemplate.LOWER_THIRD -> 0.062; TitleTemplate.CHAPTER -> 0.085; TitleTemplate.ROLL -> 0.05; TitleTemplate.FREE -> 0.09
            }
            val r = engine.tryExec(
                "graphics.newText",
                JSONObject().put("text", text.ifBlank { Str.t(R.string.projectcontroller_default_title) }).put("time", frameTicks(at.toDouble()))
                    // the default length from settings (credits rolls twice as long)
                    .put("seconds", se.gendut.klipp.AppPrefs.titleSeconds(context).toDouble() * if (l.template == TitleTemplate.ROLL) 2 else 1)
                    .put("size", size.coerceAtLeast(12.0))
                    .put("position", JSONArray().put(q.width / 2.0).put(q.height * 0.82)),
            )
            r.onFailure { onMessage(Str.t(R.string.projectcontroller_title_failed, friendly(it.message))) }
            val clip = r.getOrNull()?.optLong("clip")?.takeIf { it > 0 }
            if (clip != null && look.isNotEmpty()) setParam(clip, "graphic_text", "name", look)
            refresh()
            clip?.let { state.selected = it; state.inspecting = true }
        } }
    }

    override fun setTitle(id: Long, text: String?, size: Float?, color: Int?, look: String?) {
        scope.launch { editLock.withLock {
            text?.let {
                engine.tryExec("graphics.setText", JSONObject().put("clip", id).put("text", it)).onFailure { e -> onMessage(friendly(e.message)) }
                engine.tryExec("clip.rename", JSONObject().put("clip", id).put("name", it.lines().firstOrNull()?.take(40) ?: Str.t(R.string.projectcontroller_default_title)))
            }
            size?.let { setParam(id, "graphic_text", "size", it.toDouble()) }
            color?.let { setParam(id, "graphic_text", "fill_color", "#%06x".format(it and 0xFFFFFF)) }
            look?.let { setParam(id, "graphic_text", "name", it) }
            refresh()
        } }
    }

    override fun setCaptionText(id: Long, text: String) {
        scope.launch { editLock.withLock {
            engine.tryExec("captions.setText", JSONObject().put("caption", id).put("text", text)).onFailure { onMessage(friendly(it.message)) }
            refresh()
        } }
    }

    override fun deleteCaption(id: Long) {
        scope.launch { editLock.withLock {
            engine.tryExec("captions.delete", JSONObject().put("captions", JSONArray().put(id))).onFailure { onMessage(friendly(it.message)) }
            refresh()
        } }
    }

    private fun frames(dt: Float) = Math.round(dt * snapshot.fps)

    override fun moveCaption(id: Long, dt: Float) {
        val f = frames(dt).takeIf { it != 0L } ?: return
        scope.launch { editLock.withLock {
            engine.tryExec("captions.move", JSONObject().put("captions", JSONArray().put(id)).put("deltaFrames", f)).onFailure { onMessage(friendly(it.message)) }
            refresh()
        } }
    }

    override fun trimCaption(id: Long, inEdge: Boolean, dt: Float) {
        val f = frames(dt).takeIf { it != 0L } ?: return
        scope.launch { editLock.withLock {
            engine.tryExec("captions.trim", JSONObject().put("caption", id).put("edge", if (inEdge) "in" else "out").put("deltaFrames", f)).onFailure { onMessage(friendly(it.message)) }
            refresh()
        } }
    }

    override fun addCaption(at: Float, onAdded: (Long) -> Unit) {
        scope.launch { editLock.withLock {
            if (snapshot.sequence == null) engine.exec("file.newSequence", newSeqParams())
            // on its own, visible track (the engine creates one if there is none)
            val track = snapshot.sequence?.captionTracks?.firstOrNull { it.enabled }?.id
            val p = JSONObject().put("seconds", at.toDouble()).put("text", Str.t(R.string.projectcontroller_new_caption)).put("durationSeconds", se.gendut.klipp.AppPrefs.captionSeconds(context).toDouble())
            track?.let { p.put("track", it) }
            engine.tryExec("captions.add", p)
                .onSuccess { r -> refresh(); onAdded(r.optLong("caption")) }
                .onFailure { onMessage(if (it.message?.contains("covers") == true) Str.t(R.string.projectcontroller_caption_exists) else friendly(it.message)); refresh() }
        } }
    }

    override fun splitCaption(id: Long, at: Float) {
        scope.launch { editLock.withLock {
            engine.tryExec("captions.split", JSONObject().put("caption", id).put("frame", Math.round(at * snapshot.fps))).onFailure { onMessage(friendly(it.message)) }
            refresh()
        } }
    }

    override fun setCaptionStyle(track: Long, values: Map<String, Any>) {
        scope.launch { editLock.withLock {
            val p = JSONObject().put("track", track)
            values.forEach { (k, v) -> p.put(k, v) }
            engine.tryExec("captions.setStyle", p).onFailure { onMessage(friendly(it.message)) }
            refresh()
        } }
    }

    override fun captionStyle(track: Long) = snapshot.sequence?.captionTracks?.firstOrNull { it.id == track }?.style

    override fun importCaptions() {
        captionPicker?.invoke() ?: onMessage(Str.t(R.string.projectcontroller_picker_not_ready))
    }

    /** Reads SRT/VTT: copied to the app's cache (the engine reads the file by path). */
    fun importCaptionsFrom(uri: Uri) = scope.launch { editLock.withLock {
        try {
            val name = displayName(uri).ifBlank { Str.t(R.string.projectcontroller_captions_file_fallback) }
            val ext = name.substringAfterLast('.', "srt").lowercase().takeIf { it in setOf("srt", "vtt", "scc", "xml", "stl", "mcc", "txt") } ?: "srt"
            val dir = java.io.File(context.cacheDir, "captions").apply { mkdirs() }
            val f = java.io.File(dir, "${System.currentTimeMillis()}.$ext")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            }
            if (snapshot.sequence == null) engine.exec("file.newSequence", newSeqParams())
            val r = engine.exec("captions.import", JSONObject().put("path", f.absolutePath).put("name", name.substringBeforeLast('.')))
            refresh()
            onMessage(r.optInt("captions").let { n -> Str.p(R.plurals.projectcontroller_captions_imported, n, n) })
        } catch (e: Exception) {
            onMessage(Str.t(R.string.projectcontroller_captions_read_failed, e.message.orEmpty()))
        }
    } }

    /**
     * Captions from a file the app wrote itself (auto captions). A previous run with the same name
     * is replaced (its lines removed) instead of remaining as duplicates. One undo step per command.
     */
    suspend fun importCaptionFile(f: java.io.File, name: String) = editLock.withLock {
        group(Str.t(R.string.projectcontroller_undo_auto_captions)) {
            if (snapshot.sequence == null) engine.exec("file.newSequence", newSeqParams())
            val old = snapshot.sequence?.captionTracks.orEmpty().filter { it.name == name }.flatMap { it.captionIds }
            if (old.isNotEmpty()) engine.exec("captions.delete", JSONObject().put("captions", JSONArray().apply { old.forEach { put(it) } }))
            engine.exec("captions.import", JSONObject().put("path", f.absolutePath).put("name", name))
        }
        refresh()
    }

    // ------------------------------------------------------------------ text-based editing (the engine's transcript.*)

    /** A word of the sequence transcript: its index (what transcript.extract takes), text and timeline seconds. */
    data class TxWord(val i: Int, val text: String, val start: Double, val end: Double)

    /** Increases when a transcript changed (the transcript view re-reads the words). */
    var transcriptRevision by mutableIntStateOf(0)
        private set

    /** The media items the audio tracks' (enabled) clips use – what a transcript is made for. */
    fun speechItems(): List<Long> = snapshot.sequence?.audio.orEmpty().flatMap { t -> t.clips.filter { it.enabled && it.item > 0 } }.map { it.item }.distinct()

    /** The sequence transcript: words in timeline order and paragraphs (word index ranges split at pauses). */
    data class TxText(val words: List<TxWord>, val paragraphs: List<IntRange>)

    /** The words of the sequence transcript (empty without transcripts). */
    suspend fun transcriptWords(): TxText = editLock.withLock {
        val r = engine.tryExec("transcript.inspect").getOrNull() ?: return@withLock TxText(emptyList(), emptyList())
        val a = r.optJSONArray("words") ?: JSONArray()
        val words = (0 until a.length()).map { k ->
            val o = a.getJSONObject(k)
            TxWord(o.optInt("i", k), o.optString("text"), o.optLong("start") / TICKS_PER_SECOND.toDouble(), o.optLong("end") / TICKS_PER_SECOND.toDouble())
        }
        val p = r.optJSONArray("paragraphs") ?: JSONArray()
        val paras = (0 until p.length()).map { k -> p.getJSONObject(k).let { it.optInt("from")..it.optInt("to") } }.filter { !it.isEmpty() && it.last < words.size }
        TxText(words, paras.ifEmpty { if (words.isEmpty()) emptyList() else listOf(words.indices) })
    }

    /** Store a media item's transcript: words as (text, media start s, media end s). Replaces an earlier one. */
    suspend fun setTranscript(item: Long, language: String, words: List<SpeechLine>) = editLock.withLock {
        val arr = JSONArray()
        for (w in words) arr.put(JSONObject().put("text", w.text).put("start", secToTicks(w.start)).put("end", secToTicks(maxOf(w.end, w.start))))
        val tr = JSONObject().put("language", language).put("source", "whisper").put("speakers", JSONArray()).put("words", arr)
        se.gendut.klipp.CrashReport.note("text edit: transcript for item $item, ${words.size} words")
        engine.tryExec("transcript.set", JSONObject().put("item", item).put("transcript", tr)).onFailure { onMessage(friendly(it.message)) }
        refresh()
        transcriptRevision++
    }

    /** Remove words [from]..[to] (indices) from the timeline and close the gap on all synced tracks – one undo step. */
    fun extractWords(from: Int, to: Int) = scope.launch { editLock.withLock {
        se.gendut.klipp.CrashReport.note("text edit: extract words $from..$to")
        group(Str.t(R.string.projectcontroller_undo_delete_text)) {
            engine.tryExec("transcript.extract", JSONObject().put("from", from).put("to", to)).onFailure { onMessage(friendly(it.message)) }
            sweepSlivers()
        }
        refresh()
        transcriptRevision++
        state.playhead = snapshot.playhead.toFloat()
    } }

    /** Remove the pauses between words (longer than [minSec], keeping [keepSec] on each side). Returns the seconds removed. */
    fun removePauses(minSec: Double, keepSec: Double) = transcriptCut("transcript.removePauses", JSONObject().put("minSeconds", minSec).put("keepSeconds", keepSec), R.string.projectcontroller_undo_remove_pauses)

    /** Remove filler words ("eh", "öh", "um" …). */
    fun removeFillers() = transcriptCut("transcript.removeFillers", JSONObject().put("fillers", JSONArray(FILLERS)), R.string.projectcontroller_undo_remove_fillers)

    private fun transcriptCut(cmd: String, p: JSONObject, label: Int) = scope.launch { editLock.withLock {
        var removed = 0
        var seconds = 0.0
        se.gendut.klipp.CrashReport.note("text edit: $cmd")
        group(Str.t(label)) {
            engine.tryExec(cmd, p).onSuccess { removed = it.optInt("removed"); seconds = it.optDouble("seconds", 0.0) }.onFailure { onMessage(friendly(it.message)) }
            sweepSlivers()
        }
        refresh()
        transcriptRevision++
        onMessage(if (removed == 0) Str.t(R.string.projectcontroller_nothing_removed) else Str.p(R.plurals.projectcontroller_parts_removed, removed, removed, seconds))
    } }

    override val captionsVisible: Boolean get() = snapshot.sequence?.captionTracks?.any { it.enabled } == true
    override val hasCaptionTrack: Boolean get() = snapshot.sequence?.captionTracks?.isNotEmpty() == true

    override fun setCaptionsVisible(visible: Boolean) {
        scope.launch { editLock.withLock {
            for (t in snapshot.sequence?.captionTracks.orEmpty()) {
                engine.tryExec("captions.setTrack", JSONObject().put("track", t.id).put("enabled", visible))
            }
            refresh()
        } }
    }

    private fun toUi(s: Snapshot): List<Clip> {
        val q = s.sequence ?: return emptyList()
        val out = ArrayList<Clip>()
        val media = s.media.associateBy { it.id }
        q.video.take(3).forEachIndexed { k, t ->
            for (c in t.clips) out += c.toUi(2 - k, if (c.isTitle) ClipKind.TITLE else ClipKind.VIDEO, media[c.item])
        }
        q.audio.take(3).forEachIndexed { k, t ->
            for (c in t.clips) out += c.toUi(3 + k, ClipKind.AUDIO, media[c.item])
        }
        return out
    }

    private fun ClipSnap.toUi(track: Int, kind: ClipKind, m: MediaInfo?): Clip {
        // titles can be extended as far as you like
        // the file's length according to Media3 (same as the player) – the one that limits how far the clip can be extended
        val total = if (isTitle) (sourceIn + duration + 3600).toFloat() else (MediaProbe.length(uris[item], m?.duration) ?: (sourceIn + duration)).toFloat()
        return Clip(
            id = id,
            name = name,
            kind = kind,
            track = track.coerceIn(0, TRACKS.lastIndex),
            start = start.toFloat(),
            length = duration.toFloat(),
            srcIn = sourceIn.toFloat(),
            srcLen = total,
            scene = (item % 5).toInt(),
            item = item,
            speed = speed.toFloat(),
            link = link,
            enabled = enabled,
            hold = hold != null,
            reverse = reverse,
            group = group,
        )
    }

    fun close() {
        // a pending save happens now (a couple of seconds at most), before the engine is released
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(2000) { save() } } }
        }
        engine.close()
        fds.forEach { runCatching { it.close() } }
        fds.clear()
    }
}

/** Preset speed ramps: the speed (times the clip's) along the clip, u = 0..1. */
enum class SpeedRamp(@androidx.annotation.StringRes private val labelRes: Int, private val f: (Double) -> Double) {
    SLOW_MID(R.string.projectcontroller_ramp_slow_mid, { u -> 1.0 - 0.7 * bump(u) }),
    INTO_SLOW(R.string.projectcontroller_ramp_into_slow, { u -> 1.0 - 0.7 * smooth(0.3, 0.7, u) }),
    FROM_SLOW(R.string.projectcontroller_ramp_from_slow, { u -> 0.3 + 0.7 * smooth(0.3, 0.7, u) }),
    FAST_MID(R.string.projectcontroller_ramp_fast_mid, { u -> 1.0 + 2.0 * bump(u) });

    val label: String get() = Str.t(labelRes)

    fun factor(u: Double) = f(u)

    /** Where the clip is split (timeline seconds) and the speed (%) in each piece. */
    class Plan(val cuts: List<Double>, val speeds: List<Int>)

    companion object {
        private fun smooth(e0: Double, e1: Double, x: Double): Double {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }
        /** 0 at the ends, 1 in the middle (smooth transitions). */
        private fun bump(u: Double) = smooth(0.15, 0.4, u) * (1 - smooth(0.6, 0.85, u))

        /**
         * The clip ([start], [duration] on the timeline, speed [speed]) in equally long pieces of the source (~¼ s, 6–20
         * pieces); adjacent pieces with the same speed are merged.
         */
        fun plan(r: SpeedRamp, start: Double, duration: Double, speed: Double): Plan {
            val n = ((duration * speed) / 0.25).toInt().coerceIn(6, 20)
            val speeds = (0 until n).map { i -> (speed * 100 * r.factor((i + 0.5) / n)).toInt().coerceIn(5, 1000) }
            val cuts = ArrayList<Double>(); val out = ArrayList<Int>()
            for (i in 0 until n) {
                if (i > 0 && speeds[i] == out.last()) continue
                if (i > 0) cuts += start + duration * i / n
                out += speeds[i]
            }
            return Plan(cuts, out)
        }
    }
}

/** Filler words removed by "Remove filler words" (Swedish and English hesitations; matched whole, case and punctuation ignored). */
private val FILLERS = listOf("eh", "eeh", "öh", "öhm", "äh", "ähm", "ehm", "hmm", "mm", "mhm", "um", "umm", "uh", "uhm", "erm", "er", "ah")

/** The animatable properties (see KfKeys). */
private val KEYS = listOf(KfKeys.POSITION, KfKeys.SCALE, KfKeys.ROTATION, KfKeys.OPACITY, KfKeys.LEVEL)
