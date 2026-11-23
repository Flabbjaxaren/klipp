package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * Rendered sound effects: noise reduction and speech enhance are worked out ahead, over the clip's whole range, and the
 * clip then plays the result (rust/src/offline.rs). Played live they only know the sound that has passed – the noise
 * reduction spent its first seconds learning the noise at every start and could only shut pauses; rendered they hear
 * the whole range first and clean under the speech too.
 *
 * The rack's effects up to and including the last rendered one ([split]) go into the file (other effects in front of
 * them run there in the engine's DSP, in order); the effects after it play live on the file. A clip's range is
 * rendered with a margin (transitions' pre-roll, small trims) once its effects are in place – started when the
 * timeline changes ([ensure]), shown in the effect's card ([statusOf]). Until a file is ready the clip plays the effects
 * live; the export waits for the files ([await]). The files have their own ids ([ID_BASE] and down).
 */
object AudioRenders {
    /** Effects that are rendered. */
    val RENDERED = setOf("denoise", "speech_enhance")

    data class Rendition(val uri: String, val a: Double, val b: Double, val chain: String, val file: File, val id: Long)

    /**
     * [picks]: the channels of [uri]'s audio the clip plays (AudioChannels: one of a camera's microphone tracks); empty =
     * all of it. The rendered file then holds just those – the clip's own sound.
     */
    private data class Job(val uri: Uri, val a: Double, val b: Double, val chain: String, val picks: List<Int> = emptyList()) {
        /** The source as the renditions name it: the file, and the channels when picked. */
        val src: String get() = sourceOf(uri, picks)
        val key: String by lazy {
            val k = "$src@${"%.1f".format(java.util.Locale.ROOT, a)}-${"%.1f".format(java.util.Locale.ROOT, b)}#$chain"
            MessageDigest.getInstance("SHA-1").digest(k.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    /** Below the reversed files' ids, above the pre-rendered chunks' ([RenderCache]). */
    private const val ID_BASE = -(1L shl 40)
    private const val MARGIN = 1.0
    /** Files kept (the oldest that no clip plays go first). */
    private const val KEEP = 60

    /** Increments when a file is ready (playback is then rebuilt). */
    var revision by mutableIntStateOf(0)
        private set

    /** Per job key: 0…1 while it runs (queued = 0), [FAILED] when it couldn't be made. */
    private val progress = mutableStateMapOf<String, Float>()
    /** Per clip id: the key of the file it needs ([statusOf]). */
    private val clipJobs = mutableStateMapOf<Long, String>()
    const val FAILED = -1f

    private val ready = CopyOnWriteArrayList<Rendition>()
    private val queued = ConcurrentHashMap<String, Unit>()
    private val failed = ConcurrentHashMap<String, String>()
    /** Item → file (set by [ensure]; [rewrite] only has the clips). */
    private val itemUris = ConcurrentHashMap<Long, String>()
    /** Clip → source, for the clips that play some channels or another stream of their file (see [Job.picks]). */
    private val clipSources = ConcurrentHashMap<Long, String>()

    private fun sourceOf(uri: Uri, picks: List<Int>) = uri.toString() + if (picks.isEmpty()) "" else "#ch" + picks.joinToString(",")
    /** The job keys the timeline wants now (older queued jobs are dropped when their turn comes). */
    @Volatile private var wanted: Set<String> = emptySet()
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "afx-render").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } }
    @Volatile private var dir: File? = null
    private var nextJob = 1L

    private fun dir(c: Context): File = dir ?: run {
        val v = runCatching { Engine.nativeAfxVersion() }.getOrDefault(0)
        val name = "afx-v$v"
        // files from an earlier version come out differently: away with them
        c.filesDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("afx-v") && it.name != name }?.forEach { it.deleteRecursively() }
        File(c.filesDir, name).apply { mkdirs() }.also { dir = it; load(it) }
    }

    fun init(context: Context) { worker.execute { runCatching { dir(context.applicationContext) } } }

    fun uriOf(id: Long): Uri? {
        if (id > ID_BASE) return null
        return ready.firstOrNull { it.id == id }?.file?.takeIf { it.exists() }?.let { Uri.fromFile(it) }
    }

    val any: Boolean get() = ready.isNotEmpty()

    /** The rendered part of a rack (the effects that are on, up to and including the last rendered one) and the rest. */
    fun split(afx: List<AfxSnap>): Pair<List<AfxSnap>, List<AfxSnap>>? {
        val last = afx.indexOfLast { it.on && it.id in RENDERED }
        if (last < 0) return null
        return afx.subList(0, last + 1).filter { it.on } to afx.subList(last + 1, afx.size)
    }

    fun hasRendered(c: ClipSnap): Boolean = c.afx.any { it.on && it.id in RENDERED }

    private fun rangeOf(c: ClipSnap): Pair<Double, Double> = c.sourceIn to c.sourceIn + c.duration * kotlin.math.abs(c.speed)

    private fun covering(uri: String, chain: String, s0: Double, s1: Double): Rendition? =
        ready.firstOrNull { it.uri == uri && it.chain == chain && it.a <= s0 + 1e-3 && it.b >= s1 - 1e-3 && it.file.exists() }

    /** An audio clip as it plays: from its rendered file (when ready) with the rest of its effects, otherwise unchanged. */
    fun rewrite(c: ClipSnap): ClipSnap {
        if (c.reverse || c.hold != null || c.text != null) return c
        val (pre, rest) = split(c.afx) ?: return c
        val picked = clipSources[c.id]
        val uri = picked ?: itemUris[c.item] ?: return c
        val (s0, s1) = rangeOf(c)
        val r = covering(uri, AfxSnap.chainJson(pre), s0, s1) ?: return c
        // a file of picked channels is the clip's own sound: nothing more to pick from it
        return if (picked != null) c.copy(item = r.id, sourceIn = (c.sourceIn - r.a).coerceAtLeast(0.0), afx = rest, audioStream = 0, sourceChannels = emptyList(), layoutItem = null)
        else c.copy(item = r.id, sourceIn = (c.sourceIn - r.a).coerceAtLeast(0.0), afx = rest, layoutItem = c.layoutItem ?: c.item)
    }

    /**
     * The effect card's line for [clip]: null = nothing rendered for it, 0…1 = being worked out, 1 = ready, [FAILED].
     */
    fun statusOf(clip: Long): Float? {
        val key = clipJobs[clip] ?: return null
        return progress[key] ?: 1f
    }

    /** The files the timeline's clips need and don't have (the clip's id with each). */
    private fun jobsOf(context: Context, s: Snapshot, uriOf: (Long) -> Uri?): List<Pair<Long, Job?>> {
        val q = s.sequence ?: return emptyList()
        val out = ArrayList<Pair<Long, Job?>>()
        for (t in q.audio) for (c0 in t.clips) {
            if (!c0.enabled || !hasRendered(c0)) continue
            // a reversed clip renders its reversed file (once that exists)
            val c = if (c0.reverse) Reversals.rewrite(c0, true) else c0
            if (c.reverse || c.hold != null || c.text != null) continue
            val (pre, _) = split(c.afx) ?: continue
            val file = uriOf(c.item) ?: continue
            // the clip's own stream and channels (a camera's microphone track): rendered from just those
            val pick = AudioChannels.resolve(file, uriOf(c.layoutItem ?: c.item) ?: file, c.audioStream, c.sourceChannels) ?: continue
            val uri = pick.uri
            if (pick.picks.isEmpty() && uri == file) { itemUris[c.item] = uri.toString(); clipSources.remove(c0.id) }
            else clipSources[c0.id] = sourceOf(uri, pick.picks)
            val chain = AfxSnap.chainJson(pre)
            val (s0, s1) = rangeOf(c)
            if (covering(sourceOf(uri, pick.picks), chain, s0, s1) != null) { out += c0.id to null; continue }
            val len = MediaProbe.probe(context, uri)?.duration?.takeIf { it > 0 }
            val a = (Math.floor((s0 - MARGIN) * 10) / 10).coerceAtLeast(0.0)
            val b = (Math.ceil((s1 + MARGIN) * 10) / 10).let { if (len != null) it.coerceAtMost(len) else it }
            out += c0.id to Job(uri, a, maxOf(b, s1), chain, pick.picks)
        }
        return out
    }

    /** Works out the missing files in the background (playback; the cards show the progress). */
    fun ensure(context: Context, s: Snapshot, uriOf: (Long) -> Uri?) {
        val app = context.applicationContext
        if (s.sequence?.audio?.any { t -> t.clips.any { it.enabled && hasRendered(it) } } != true) {
            if (clipJobs.isNotEmpty()) main.post { clipJobs.clear() }
            wanted = emptySet()
            return
        }
        worker.execute {
            dir(app)
            val jobs = runCatching { jobsOf(app, s, uriOf) }.getOrDefault(emptyList())
            wanted = jobs.mapNotNull { it.second?.key }.toSet()
            val keys = jobs.associate { (clip, j) -> clip to (j?.key ?: READY) }
            val fresh = jobs.mapNotNull { it.second }.distinctBy { it.key }.filter { !failed.containsKey(it.key) && queued.putIfAbsent(it.key, Unit) == null }
            main.post {
                clipJobs.keys.retainAll(keys.keys)
                clipJobs.putAll(keys)
                for (j in fresh) if (progress[j.key] == null) progress[j.key] = 0f
                for (k in keys.values) failed[k]?.let { progress[k] = FAILED }
            }
            for (j in fresh) worker.execute { run(app, j) }
        }
    }

    /** A clip whose file is there (any key that no job has). */
    private const val READY = "ready"

    /** The export: missing files are made now. One that can't be made plays its effects live (as before). */
    suspend fun await(context: Context, s: Snapshot, uriOf: (Long) -> Uri?) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (s.sequence?.audio?.any { t -> t.clips.any { it.enabled && hasRendered(it) } } != true) return@withContext
        val done = CountDownLatch(1)
        worker.execute {
            try {
                dir(app)
                for (j in jobsOf(app, s, uriOf).mapNotNull { it.second }.distinctBy { it.key }) {
                    if (failed.containsKey(j.key)) continue
                    queued[j.key] = Unit
                    run(app, j, force = true)
                }
            } catch (_: Throwable) {
            } finally { done.countDown() }
        }
        done.await()
    }

    /** One job (on the worker). */
    private fun run(c: Context, j: Job, force: Boolean = false) {
        val d = dir(c)
        val out = File(d, j.key + ".wav")
        val raw = File(d, j.key + ".raw")
        val job = synchronized(this) { nextJob++ }
        var poll: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            if (!force && j.key !in wanted) { main.post { progress.remove(j.key) }; return }
            if (covering(j.src, j.chain, j.a, j.b) != null) return
            se.gendut.klipp.CrashReport.note("afx render: ${j.uri.lastPathSegment} ${"%.1f".format(j.a)}–${"%.1f".format(j.b)} s")
            main.post { progress[j.key] = 0.02f }
            if (!out.exists()) {
                val (rate, all) = Reversals.decodePcmRange(c, j.uri, j.a, j.b, raw)
                // just the clip's channels (the renderer takes mono or stereo)
                val channels = if (j.picks.isEmpty()) all else { pickChannels(raw, all, j.picks); j.picks.size }
                // decoding is the first fifth
                main.post { progress[j.key] = 0.2f }
                poll = ticker.scheduleWithFixedDelay({
                    val p = runCatching { Engine.nativeAfxProgress(job) }.getOrDefault(0f)
                    main.post { if (progress[j.key] != null) progress[j.key] = 0.2f + 0.8f * p }
                }, 150, 150, java.util.concurrent.TimeUnit.MILLISECONDS)
                val err = Engine.nativeAfxRender(j.chain, raw.path, rate, channels, out.path, job)
                if (err != null) error(err)
            }
            add(Rendition(j.src, j.a, j.b, j.chain, out, idOf(j.key)))
            MediaProbe.probe(c, Uri.fromFile(out))
            trim(d)
            save(d)
            main.post { progress.remove(j.key); revision++ }
        } catch (e: Throwable) {
            val m = e.message ?: e.javaClass.simpleName
            failed[j.key] = m
            out.delete()
            se.gendut.klipp.CrashReport.note("afx render error: $m")
            main.post { progress[j.key] = FAILED }
        } finally {
            poll?.cancel(false)
            raw.delete()
            queued.remove(j.key)
        }
    }

    /** Keeps only [picks] of raw 16-bit PCM with [channels] channels, in place (the file is rewritten). */
    internal fun pickChannels(raw: File, channels: Int, picks: List<Int>) {
        val tmp = File(raw.path + ".pick")
        val inFrame = 2 * channels
        val outFrame = 2 * picks.size
        val at = picks.map { it.coerceIn(0, channels - 1) * 2 }
        raw.inputStream().buffered(1 shl 16).use { ins ->
            tmp.outputStream().buffered(1 shl 16).use { os ->
                val f = ByteArray(inFrame)
                val o = ByteArray(outFrame)
                while (true) {
                    var n = 0
                    while (n < inFrame) { val r = ins.read(f, n, inFrame - n); if (r < 0) break; n += r }
                    if (n < inFrame) break
                    for ((k, b) in at.withIndex()) { o[2 * k] = f[b]; o[2 * k + 1] = f[b + 1] }
                    os.write(o)
                }
            }
        }
        if (!tmp.renameTo(raw)) { tmp.delete(); error("could not keep the clip's channels") }
    }

    private val ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "afx-progress").apply { isDaemon = true } }

    private fun idOf(key: String): Long = ID_BASE - key.take(9).toLong(16)

    private fun add(r: Rendition) = synchronized(ready) {
        ready.removeAll { it.id == r.id }
        ready += r
    }

    /** Beyond [KEEP] files the oldest that no clip on the timeline plays are deleted. */
    private fun trim(d: File) = synchronized(ready) {
        if (ready.size <= KEEP) return@synchronized
        val used = HashSet<Long>()
        for (uri in itemUris.values + clipSources.values) ready.filter { it.uri == uri }.forEach { used += it.id }
        for (r in ready.toList()) {
            if (ready.size <= KEEP) break
            if (r.id in used) continue
            ready.remove(r)
            r.file.delete()
        }
    }

    // ------------------------------------------------------------------ index on disk

    private fun index(d: File) = File(d, "index.json")

    private fun save(d: File) = synchronized(ready) {
        runCatching {
            val a = JSONArray()
            for (r in ready) a.put(JSONObject().put("uri", r.uri).put("a", r.a).put("b", r.b).put("chain", r.chain).put("file", r.file.name))
            index(d).writeText(a.toString())
        }
    }

    private fun load(d: File) = synchronized(ready) {
        if (ready.isNotEmpty()) return@synchronized
        runCatching {
            val a = JSONArray(index(d).readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val f = File(d, o.getString("file")).takeIf { it.exists() } ?: continue
                add(Rendition(o.getString("uri"), o.getDouble("a"), o.getDouble("b"), o.getString("chain"), f, idOf(f.nameWithoutExtension)))
            }
        }
        // half-written files from a run that was stopped
        d.listFiles()?.filter { it.name.endsWith(".raw") || it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    /** For tests: a finished file for [item]'s source. */
    internal fun putForTest(item: Long, uri: String, a: Double, b: Double, chain: String, file: File): Long {
        itemUris[item] = uri
        val id = idOf(MessageDigest.getInstance("SHA-1").digest("$uri$a$b$chain".toByteArray()).joinToString("") { "%02x".format(it) })
        add(Rendition(uri, a, b, chain, file, id))
        return id
    }

    internal fun clearForTest() { synchronized(ready) { ready.clear() }; itemUris.clear(); clipSources.clear() }
}
