package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Pre-render (like Premiere's "Render In to Out"): the timeline is split into chunks at every edge (clips, transitions,
 * layers) and a chunk is rendered – with all layers, grades, masks and transitions – to a file at preview size with dense
 * keyframes. Playback then plays the file instead of computing the chunk's image ([substitute]): one decoder instead of
 * several, no real-time effects, and every chunk starts on a keyframe (no hitch at the clip switch).
 *
 * Every chunk has a key ([Chunk.hash]) from everything that affects its image; if something in the chunk changes (or
 * before it, so that it moves) the file no longer applies and the chunk plays normally again. The look is not in the
 * files – it is applied to the whole image during playback (and can change freely). Titles and captions are drawn by the
 * app on top.
 *
 * The files live in filesDir/render (same key = same file, even after a restart and across projects); the oldest are
 * deleted when together they exceed [MAX_BYTES].
 */
@OptIn(UnstableApi::class)
object RenderCache {
    /** Increments when a chunk is ready or the files were deleted (playback is then rebuilt). */
    var revision by mutableIntStateOf(0)
        private set

    /** The rendering in progress: fraction done (0..1), otherwise null. */
    var progress by mutableStateOf<Float?>(null)
        internal set

    /** Stopped by the user: no rendering until requested again ("Render", a transition to pre-render). */
    var halted by mutableStateOf(false)

    /** Render heavy parts automatically in the background (when nothing is playing or being dragged); saved in the app's settings. */
    var auto by mutableStateOf(false)
        private set

    fun setAuto(context: Context, on: Boolean) {
        auto = on
        if (on) halted = false
        context.getSharedPreferences("klipp", Context.MODE_PRIVATE).edit().putBoolean("renderAuto", on).apply()
    }

    /** "Render timeline": all chunks that are not ready get rendered (once; reset when done). */
    var renderAll by mutableStateOf(false)

    /** The transitions to keep pre-rendered, as "from>to" (clip ids) – saved with the project. */
    val pinned = mutableStateListOf<String>()

    /** The chunks' state for the timeline's render bar: (from, to, ready, needs rendering). */
    var states by mutableStateOf<List<ChunkState>>(emptyList())
        internal set

    data class ChunkState(val a: Double, val b: Double, val ready: Boolean, val heavy: Boolean)

    /** A chunk of the timeline [a]–[b]; [heavy] = anything other than a plain clip without effects (worth rendering). */
    data class Chunk(val a: Double, val b: Double, val hash: String, val heavy: Boolean)

    private const val VERSION = 1
    /** Chunks shorter than this merge with the neighbor (a frame on its own would just mean more clip switches). */
    private const val MIN_CHUNK = 0.25
    /** The disk space for the files. */
    private const val MAX_BYTES = 3L * 1024 * 1024 * 1024
    /** The render ids (like the reversed clips': negative, but in a range of their own). */
    private const val ID_BASE = -(1L shl 52)

    @Volatile private var dir: File? = null
    private val files = ConcurrentHashMap<Long, File>()

    fun init(context: Context) {
        if (dir != null) return
        auto = context.getSharedPreferences("klipp", Context.MODE_PRIVATE).getBoolean("renderAuto", false)
        val d = File(context.applicationContext.filesDir, "render").apply { mkdirs() }
        d.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        d.listFiles()?.filter { it.name.endsWith(".mp4") && it.length() > 0 }?.forEach { f -> files[idOf(f.nameWithoutExtension)] = f }
        dir = d
    }

    fun pairKey(from: Long, to: Long) = "$from>$to"

    /** The transition between [from] and [to] is pre-rendered (or not). */
    fun setPinned(from: Long, to: Long, on: Boolean) {
        val k = pairKey(from, to)
        if (on) halted = false
        if (on && k !in pinned) pinned += k else if (!on) pinned.remove(k)
    }

    fun isPinned(from: Long, to: Long) = pairKey(from, to) in pinned

    /** The file for a render id (playback's uriOf). */
    fun uriOf(id: Long): Uri? = if (id > ID_BASE) null else files[id]?.takeIf { it.exists() }?.let { Uri.fromFile(it) }

    private fun idOf(hash: String): Long = ID_BASE - (hash.take(10).toLong(16) and 0xFF_FFFF_FFFFL)

    private fun fileOf(hash: String): File? = dir?.let { File(it, "$hash.mp4") }

    fun isReady(c: Chunk): Boolean = fileOf(c.hash)?.let { it.exists() && it.length() > 0 } == true

    /**
     * The chunks in [plan] (playback's plan, before substitution). [gradeOf] = the clips' grade, [uriOf] = the files,
     * [fps] = the project frame rate, [short] = the preview's short side (the files' size).
     */
    fun chunks(plan: LayerPlan, gradeOf: (Long) -> ClipGrade?, uriOf: (Long) -> Uri?, fps: Double, short: Int): List<Chunk> {
        val edges = sortedSetOf(plan.origin, plan.total)
        for (l in plan.layers) for (sg in l.segs) { edges += sg.start; edges += sg.end }
        val e = edges.filter { it >= plan.origin - 1e-9 && it <= plan.total + 1e-9 }.toMutableList()
        // too-short chunks: the edge is removed (the chunk merges with the one before)
        var i = 1
        while (i < e.size) {
            if (e[i] - e[i - 1] < MIN_CHUNK && e.size > 2) { if (i == e.size - 1) e.removeAt(i - 1) else e.removeAt(i) } else i++
        }
        val out = ArrayList<Chunk>()
        for (k in 0 until e.size - 1) {
            val a = e[k]; val b = e[k + 1]
            if (b - a <= 1e-6) continue
            val sb = StringBuilder()
            sb.append("v").append(VERSION).append('|').append(plan.width).append('x').append(plan.height).append('@').append(fps).append('/').append(short)
                .append('|').append(r(a)).append('-').append(r(b))
            var heavy = plan.layers.count { l -> l.segs.any { it.end > a + 1e-6 && it.start < b - 1e-6 } } > 1
            for ((li, l) in plan.layers.withIndex()) {
                val segs = l.segs.filter { it.end > a + 1e-6 && it.start < b - 1e-6 }
                if (segs.isEmpty()) continue
                sb.append("|L").append(li)
                if (segs.size > 1) heavy = true
                for (sg in segs) {
                    sb.append('[').append(r(sg.start)).append(',').append(r(sg.end)).append(',')
                    when (val s = sg.src) {
                        is SegSrc.Media -> sb.append("M").append(uriOf(s.item)).append(',').append(r(s.srcIn)).append(',').append(s.speed)
                        is SegSrc.Still -> { sb.append("S").append(uriOf(s.item)).append(',').append(r(s.sec)); heavy = true }
                    }
                    // ramps and transition windows only where they apply in the chunk (a transition further on does not change this one)
                    sg.ramp?.takeIf { it.t1 > a + 1e-6 && it.t0 < b - 1e-6 }?.let { sb.append(",R").append(it); heavy = true }
                    sg.fx?.let { sb.append(",F").append(it); heavy = true }
                    sg.windows.filter { it.t1 > a + 1e-6 && it.t0 < b - 1e-6 }.takeIf { it.isNotEmpty() }?.let { sb.append(",W").append(it); heavy = true }
                    sg.clip?.let { c ->
                        sb.append(",C").append(videoKey(c))
                        if (c.hasMotion(plan.width, plan.height) || c.hasOpacity || kotlin.math.abs(c.speed - 1.0) > 1e-3 || c.reverse) heavy = true
                        gradeOf(c.id)?.let { g ->
                            sb.append(",G").append(g.toJson())
                            if (!g.isNeutral || g.layer != null) heavy = true
                        }
                    }
                    sb.append(']')
                }
            }
            out += Chunk(a, b, sha1(sb.toString()), heavy)
        }
        return out
    }

    /** What in the clip affects the image (not the audio: volume, audio effects, name). */
    private fun videoKey(c: ClipSnap): String = listOf(
        c.item, r(c.start), r(c.duration), r(c.sourceIn), c.speed, c.x, c.y, c.scale, c.rotation, c.opacity,
        c.kf.filterKeys { it != KfKeys.LEVEL }, c.reverse, c.hold, c.enabled,
    ).joinToString(";")

    private fun r(v: Double) = Math.round(v * 1e5) / 1e5

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * The plan with the ready chunks substituted: in the chunks' time everything is removed from all layers and the bottom
     * layer plays the file. (Same in the main player and in the app's upper layer, so they agree.)
     */
    fun substitute(plan: LayerPlan, chunks: List<Chunk>): LayerPlan {
        val ready = chunks.filter { isReady(it) }
        if (ready.isEmpty() || plan.layers.isEmpty()) return plan
        // adjacent ready chunks merge into one interval (but play as separate files)
        val ranges = ready.map { it.a..it.b }
        fun cut(segs: List<Seg>): List<Seg> {
            val out = ArrayList<Seg>()
            for (sg in segs) {
                var pieces = listOf(sg)
                for (rg in ranges) {
                    pieces = pieces.flatMap { p ->
                        if (p.end <= rg.start + 1e-9 || p.start >= rg.endInclusive - 1e-9) listOf(p)
                        else listOfNotNull(
                            if (rg.start - p.start > 1e-6) p.copy(dur = rg.start - p.start) else null,
                            if (p.end - rg.endInclusive > 1e-6) p.copy(start = rg.endInclusive, dur = p.end - rg.endInclusive, src = shiftSrc(p.src, rg.endInclusive - p.start)) else null,
                        )
                    }
                }
                out += pieces
            }
            return out
        }
        val layers = plan.layers.map { it.copy(segs = cut(it.segs)) }.toMutableList()
        val rendered = renderedSegs(ready)
        val bottom = layers.removeAt(layers.lastIndex)
        val base = bottom.copy(segs = (bottom.segs + rendered).sortedBy { it.start })
        val kept = layers.filter { it.segs.isNotEmpty() } + base
        val fades = if (kept.size == 1) base.segs.mapNotNull { it.ramp }.distinct() else emptyList()
        return plan.copy(layers = kept, blackFades = fades, rendered = ranges)
    }

    /** Runs of chunks that sit edge to edge (in time order). */
    private fun runs(chunks: List<Chunk>): List<List<Chunk>> {
        val out = ArrayList<MutableList<Chunk>>()
        for (c in chunks.sortedBy { it.a }) {
            val last = out.lastOrNull()
            if (last != null && kotlin.math.abs(last.last().b - c.a) < 1e-6) last += c else out += mutableListOf(c)
        }
        return out
    }

    private fun joinHash(run: List<Chunk>) = sha1("join|" + run.joinToString(",") { it.hash })

    /**
     * Join ready chunks that sit edge to edge into one file (the frames are copied – no re-encoding; every chunk starts on
     * a keyframe with the same format). The chunks' files remain (a change in one chunk only redoes the join).
     */
    fun joinRuns(chunks: List<Chunk>) {
        val d = dir ?: return
        for (run in runs(chunks.filter { isReady(it) })) {
            if (run.size < 2) continue
            val h = joinHash(run)
            val out = File(d, "$h.mp4")
            if (out.exists() && out.length() > 0) continue
            val tmp = File(d, "$h.tmp")
            val ok = runCatching { concat(run.map { File(d, it.hash + ".mp4") to (it.a - run.first().a) }, tmp) }
                .onFailure { se.gendut.klipp.CrashReport.note("pre-render: join: ${it.message}") }.isSuccess
            if (ok && tmp.renameTo(out)) files[idOf(h)] = out else tmp.delete()
        }
    }

    /** The files (each with its start time, s) one after another in one file – same codec and format. */
    private fun concat(parts: List<Pair<File, Double>>, out: File) {
        var muxer: android.media.MediaMuxer? = null
        var track = -1
        val buf = java.nio.ByteBuffer.allocate(8 * 1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()
        try {
            for ((f, offset) in parts) {
                val ex = android.media.MediaExtractor()
                try {
                    ex.setDataSource(f.path)
                    val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ex.selectTrack(t)
                    if (muxer == null) {
                        muxer = android.media.MediaMuxer(out.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        track = muxer.addTrack(ex.getTrackFormat(t))
                        muxer.start()
                    }
                    val off = Math.round(offset * 1e6)
                    while (true) {
                        buf.clear()
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        info.set(0, n, ex.sampleTime + off, if (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, buf, info)
                        ex.advance()
                    }
                } finally {
                    ex.release()
                }
            }
        } finally {
            muxer?.let { runCatching { it.stop() }; it.release() }
        }
    }

    private fun shiftSrc(src: SegSrc, by: Double): SegSrc = when (src) {
        is SegSrc.Media -> src.copy(srcIn = src.srcIn + by * src.speed)
        is SegSrc.Still -> src
    }

    /**
     * The ready chunks as playback segments: adjacent ones as a joined file when it exists (one item in the player – no
     * switch between the chunks), otherwise one by one.
     */
    private fun renderedSegs(ready: List<Chunk>): List<Seg> {
        val out = ArrayList<Seg>()
        fun add(hash: String, a: Double, b: Double) {
            val id = idOf(hash)
            fileOf(hash)?.let { files[id] = it; it.setLastModified(System.currentTimeMillis()) }
            out += Seg(a, b - a, SegSrc.Media(id, 0.0, 1.0), null)
        }
        for (run in runs(ready)) {
            val joined = run.size > 1 && fileOf(joinHash(run))?.let { it.exists() && it.length() > 0 } == true
            if (joined) add(joinHash(run), run.first().a, run.last().b) else for (c in run) add(c.hash, c.a, c.b)
        }
        return out
    }

    /** The files the substitution currently uses (playback's key: if they change the player is rebuilt). */
    fun usedFiles(chunks: List<Chunk>): String = renderedSegs(chunks.filter { isReady(it) }).joinToString(",") { it.src.item.toString() }

    /**
     * Render the chunks [todo] (those not already ready), one at a time. [canWork] = whether rendering may run now (not
     * during playback – it would take power from it); otherwise it waits. [onChunk] after every finished chunk.
     */
    suspend fun render(
        context: Context, s: Snapshot, uriOf: (Long) -> Uri?, grades: Map<Long, ClipGrade>, todo: List<Chunk>,
        canWork: () -> Boolean, onChunk: () -> Unit,
    ): Result<Int> {
        val d = dir ?: return Result.failure(IllegalStateException("no directory"))
        val list = todo.filter { !isReady(it) }
        if (list.isEmpty()) return Result.success(0)
        val total = list.sumOf { it.b - it.a }.coerceAtLeast(1e-6)
        var done = 0.0
        progress = 0f
        try {
            // the freeze frames and the reversed clips' files must exist (as in the export)
            FrameHolds.await(context, FrameHolds.holdsOf(s, uriOf))
            Reversals.await(context, s, uriOf)
            val anyHdr = withContext(Dispatchers.IO) { CompositionBuilder.anyHdr(context, s, probe = true, uriOf = uriOf) }
            val short = VideoPipeline.previewShort
            var n = 0
            for (c in list) {
                while (!canWork()) kotlinx.coroutines.delay(300)
                if (isReady(c)) { done += c.b - c.a; continue }
                val built = CompositionBuilder.buildFull(
                    s, uriOf, Look.NONE, overlays = false, grades = grades, hdrMode = VideoPipeline.toneMapMode(anyHdr, false),
                    videoUriOf = { id -> uriOf(id)?.let { Proxies.videoUri(it) } }, previewShort = short, range = c.a..c.b,
                    output = renderSettings(s, short),
                ) ?: run { done += c.b - c.a; continue }
                val tmp = File(d, "${c.hash}.tmp")
                // one more attempt if it failed (the encoder can be busy for a while, e.g. by an export)
                var r: Result<Unit> = Result.failure(IllegalStateException())
                for (attempt in 0..1) {
                    tmp.delete()
                    r = Exporter.transform(context, built.composition, tmp, renderSettings(s, short), tenBit = false, note = false) { p ->
                        progress = ((done + (c.b - c.a) * p / 100.0) / total).toFloat().coerceIn(0f, 1f)
                    }
                    if (r.isSuccess || r.exceptionOrNull() is kotlinx.coroutines.CancellationException) break
                    kotlinx.coroutines.delay(1000)
                }
                if (r.isFailure) { tmp.delete(); return Result.failure(r.exceptionOrNull()!!) }
                val f = File(d, "${c.hash}.mp4")
                if (!tmp.renameTo(f)) { tmp.delete(); return Result.failure(IllegalStateException("could not save")) }
                files[idOf(c.hash)] = f
                done += c.b - c.a
                n++
                progress = (done / total).toFloat()
                onChunk()
            }
            trim()
            return Result.success(n)
        } finally {
            progress = null
        }
    }

    /** The files' format: preview size (0 = full), the project frame rate, H.264 with dense keyframes. */
    private fun renderSettings(s: Snapshot, short: Int): ExportSettings {
        val q = s.sequence
        val h = if (short > 0 && q != null) minOf(short, minOf(q.width, q.height)) else 0
        val st = ExportSettings(height = h, codec = MimeTypes.VIDEO_H264, keyframeSec = 0.5f, audio = false, hdr = false, bitDepth = 8)
        return if (q == null) st else st.copy(bitrateMbps = (st.videoMbps(q.width, q.height, s.fps) * 1.5).toFloat())
    }

    /** Increments when the files were deleted (the chunks are then recomputed). */
    var cleared by mutableIntStateOf(0)
        private set

    /** Delete all rendered files. */
    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
        files.clear()
        states = states.map { it.copy(ready = false) }
        renderAll = false
        cleared++
        revision++
    }

    /** The files' size (bytes). */
    fun bytes(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0L

    /** Delete the oldest files when they take too much space. */
    private fun trim() {
        val all = dir?.listFiles()?.filter { it.name.endsWith(".mp4") }?.sortedBy { it.lastModified() } ?: return
        var sum = all.sumOf { it.length() }
        for (f in all) {
            if (sum <= MAX_BYTES) break
            sum -= f.length()
            files.entries.removeIf { it.value == f }
            f.delete()
        }
    }

    internal fun bump() { revision++ }
}
