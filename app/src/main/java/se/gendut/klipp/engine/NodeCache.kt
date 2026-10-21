package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import org.json.JSONObject
import se.gendut.klipp.R
import se.gendut.klipp.Str
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Node cache (like Resolve's "node cache"): the image up to and including a node is rendered ahead of time and playback
 * reads the file instead of computing the nodes – heavy nodes (film with grain and halation, blur, tracked windows)
 * then don't lag. Nodes after the cache point are computed as usual (live).
 *
 * Full quality: from the original file (never the proxy), at the original's resolution, through the video chain in
 * floating point, and encoded in 10-bit (HEVC Main10) at a very high bitrate (≈ 0.8 bits per pixel per frame) when the
 * phone can – otherwise H.264 High. Export never uses the cache (it always computes from the originals).
 *
 * The cache is valid for exactly those nodes (all values, the LUTs' contents) and the clip's part of the file (+ 2 s on
 * either side); if anything changes it is redone in the background a while after the change, and meanwhile everything
 * is computed live.
 */
@OptIn(UnstableApi::class)
object NodeCache {
    /** A finished cache file: source time [start, end] (s) in the file. */
    class Entry(val file: File, val start: Double, val end: Double, val tenBit: Boolean)

    /** What a cache file should contain. */
    class Req(val clip: Long, val uri: Uri, val start: Double, val end: Double, val point: Int, val nodes: List<GradeNode>, val key: String)

    private const val VERSION = 1
    private const val HANDLE = 2.0

    private val ready = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    /** Clip → key of the clip's latest cache file (the previous one is deleted when a new one is done). */
    private val byClip = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val failed = HashSet<String>()
    /** Ongoing render per clip: percent (−1 = queued). */
    val progress = mutableStateMapOf<Long, Int>()
    /** Incremented when a cache file is finished or removed (playback is rebuilt). */
    var revision by mutableIntStateOf(0)
        private set

    private val queue = ArrayDeque<Req>()
    private var running: Pair<Req, Transformer>? = null
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())

    private fun dir(c: Context) = File(c.filesDir, "nodecache").apply { mkdirs() }
    private fun index(c: Context) = File(dir(c), "index.json")

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        DecodeCalibration.init(context)
        runCatching {
            val o = JSONObject(index(app!!).readText())
            o.optJSONObject("files")?.let { f ->
                for (k in f.keys()) {
                    val e = f.getJSONObject(k)
                    val file = File(e.getString("f"))
                    if (file.exists() && file.length() > 0) ready[k] = Entry(file, e.getDouble("a"), e.getDouble("b"), e.optBoolean("ten"))
                }
            }
            o.optJSONObject("clips")?.let { c -> for (k in c.keys()) k.toLongOrNull()?.let { byClip[it] = c.getString(k) } }
        }
    }

    private fun save(c: Context) {
        runCatching {
            val f = JSONObject()
            ready.forEach { (k, e) -> f.put(k, JSONObject().put("f", e.file.absolutePath).put("a", e.start).put("b", e.end).put("ten", e.tenBit)) }
            val cl = JSONObject()
            byClip.forEach { (id, k) -> cl.put(id.toString(), k) }
            index(c).writeText(JSONObject().put("files", f).put("clips", cl).toString())
        }
    }

    /**
     * What the clip's cache file should contain (null = no node is cached, or the nodes up to the point do nothing).
     * [mediaDur] = the original file's duration (s).
     */
    fun request(clip: ClipSnap, uri: Uri, mediaDur: Double?, g: ClipGrade): Req? {
        // same grade object and same part of the file: same answer (the key hashes all node values)
        val memoKey = "${clip.id}|$uri|${clip.sourceIn}|${clip.duration}|${clip.speed}|$mediaDur"
        synchronized(memo) { memo[g]?.let { (k, r) -> if (k == memoKey) return r } }
        val r = compute(clip, uri, mediaDur, g)
        synchronized(memo) { if (memo.size > 128) memo.clear(); memo[g] = memoKey to r }
        return r
    }

    private val memo = java.util.IdentityHashMap<ClipGrade, Pair<String, Req?>>()

    private fun compute(clip: ClipSnap, uri: Uri, mediaDur: Double?, g: ClipGrade): Req? {
        val point = g.cachePoint
        if (point < 0) return null
        val nodes = g.nodes.take(point + 1)
        if (nodes.all { it.isNeutral }) return null
        val a = (floor(clip.sourceIn) - HANDLE).coerceAtLeast(0.0)
        var b = ceil(clip.sourceIn + clip.duration * kotlin.math.abs(clip.speed)) + HANDLE
        if (mediaDur != null && mediaDur > 0) b = b.coerceAtMost(mediaDur)
        if (b <= a + 0.05) return null
        val sb = StringBuilder()
        sb.append(VERSION).append('|').append(uri).append('|').append(a).append('|').append(b).append('|')
        sb.append(VideoPipeline.tenBitProfile(MimeTypes.VIDEO_H265) != null).append('|')
        for (n in nodes) {
            sb.append(n.copy(cache = false).toJson().toString()).append('|')
            // the LUT's contents (a new LUT with the same name must produce a new cache)
            n.lut?.let { name -> runCatching { LutStore.get(name) }.getOrNull()?.data?.contentHashCode()?.let { sb.append("lut").append(it).append('|') } }
        }
        val key = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        return Req(clip.id, uri, a, b, point, nodes, key)
    }

    /** Finished cache file for [req] covering source [from, to] (s), otherwise null. */
    fun lookup(req: Req, from: Double, to: Double): Entry? =
        ready[req.key]?.takeIf { it.file.exists() && from >= it.start - 1e-3 && to <= it.end + 1e-3 }

    /** Which clips currently read a cache file (key for rebuilding playback). */
    fun usage(s: Snapshot, grades: Map<Long, ClipGrade>, uriOf: (Long) -> Uri?): String {
        if (ready.isEmpty()) return ""
        val q = s.playable().sequence ?: return ""
        val durs = s.media.associate { it.id to it.duration.takeIf { d -> d > 0 } }
        val sb = StringBuilder()
        for (c in q.video.flatMap { it.clips }) {
            val g = grades[c.id] ?: continue
            if (g.cachePoint < 0) continue
            val uri = uriOf(c.item) ?: continue
            val r = request(c, uri, MediaProbe.length(uri, durs[c.item]), g) ?: continue
            if (ready.containsKey(r.key)) sb.append(c.id).append('=').append(r.key.take(12)).append(';')
        }
        return sb.toString()
    }

    /** The latest [sync]'s request per clip. */
    @Volatile private var lastWanted: Map<Long, Req> = emptyMap()

    /** Node cache status for the clip (Color page, reads Compose state): null = no cache, otherwise a short text. */
    fun status(clip: Long): String? {
        revision // (state: the status changes when a file is done)
        progress[clip]?.let { return if (it < 0) Str.t(R.string.nodecache_status_queued) else Str.t(R.string.nodecache_status_caching, it) }
        val r = lastWanted[clip] ?: return null
        if (ready.containsKey(r.key)) return Str.t(R.string.nodecache_status_cached)
        if (r.key in failed) return Str.t(R.string.nodecache_status_failed)
        return Str.t(R.string.nodecache_status_waiting)
    }

    /**
     * Make sure every clip with a cached node has its cache file: missing ones are queued, stale renders are
     * cancelled. Called a while after changes (not on the main thread's hot path).
     */
    fun sync(context: Context, s: Snapshot, grades: Map<Long, ClipGrade>, uriOf: (Long) -> Uri?) {
        init(context)
        val q = s.playable().sequence ?: return
        val durs = s.media.associate { it.id to it.duration.takeIf { d -> d > 0 } }
        val wanted = HashMap<Long, Req>()
        for (c in q.video.flatMap { it.clips }) {
            if (c.text != null) continue
            val g = grades[c.id] ?: continue
            val uri = uriOf(c.item) ?: continue
            val r = request(c, uri, MediaProbe.length(uri, durs[c.item]), g) ?: continue
            wanted[c.id] = r
        }
        lastWanted = wanted
        // stale: a render or a queued job that is no longer requested
        running?.let { (r, t) -> if (wanted[r.clip]?.key != r.key) { t.cancel(); running = null; progress.remove(r.clip) } }
        queue.removeAll { r -> (wanted[r.clip]?.key != r.key).also { if (it) progress.remove(r.clip) } }
        for ((id, r) in wanted) {
            if (ready.containsKey(r.key) || r.key in failed) continue
            if (running?.first?.key == r.key || queue.any { it.key == r.key }) continue
            progress[id] = -1
            queue.addLast(r)
        }
        // clips that no longer cache: delete their files
        for ((id, k) in byClip.entries.toList()) if (wanted[id] == null) { byClip.remove(id); drop(context, k) }
        // cache files from before the color check (or a new format): check the format first
        if (running == null && !calibrating) {
            val formats = ready.values.map { it.tenBit }.distinct().map { DecodeCalibration.formatFor(it) }
            val unchecked = formats.firstOrNull { (mime, ten) -> !DecodeCalibration.isChecked(context, mime, ten != null) }
            if (unchecked != null) {
                val (mime, profile) = unchecked
                calibrating = true
                DecodeCalibration.check(context, mime, profile != null, encoderFactory(context, profile, 20_000_000)) {
                    calibrating = false
                    // playback is rebuilt (with the correction)
                    revision++
                    next()
                }
                return
            }
        }
        next()
    }

    private fun drop(c: Context, key: String) {
        if (byClip.values.contains(key)) return
        ready.remove(key)?.file?.delete()
        save(c)
        revision++
    }

    /** The color check is running (before the first cache in a format); the queue waits. */
    private var calibrating = false

    private fun next() {
        val c = app ?: return
        if (running != null || calibrating) return
        val r = queue.removeFirstOrNull() ?: return
        val out = File(dir(c), r.key + ".mp4")
        val tmp = File(out.path + ".tmp")
        tmp.delete()
        Thread({
            // the file's size, frame rate and HDR (not on the main thread)
            val codec = Perf.codec(c, r.uri)
            val hdr = Perf.isHdr(c, r.uri) == true
            val fps = MediaProbe.probe(c, r.uri)?.fps ?: 30.0
            main.post { start(c, r, out, tmp, codec, hdr, fps) }
        }, "nodecache-probe").apply { isDaemon = true }.start()
    }

    /** The cache format: 10-bit HEVC when the phone can, otherwise 8-bit H.264. */
    private fun cacheMime(): Pair<String, Pair<Int, Int>?> = DecodeCalibration.formatFor(true)

    /** The encoder for the cache (and its color check, see [DecodeCalibration]). */
    private fun encoderFactory(c: Context, ten: Pair<Int, Int>?, bits: Int): DefaultEncoderFactory {
        val vs = VideoEncoderSettings.Builder()
            .setBitrate(bits)
            .setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            .setiFrameIntervalSeconds(0.5f)
        if (ten != null) vs.setEncodingProfileLevel(ten.first, ten.second)
        return DefaultEncoderFactory.Builder(c).setRequestedVideoEncoderSettings(vs.build()).setEnableFallback(true).build()
    }

    /** The correction of the phone's decoding for a cache file (null = none needed), see [DecodeCalibration]. */
    fun correction(e: Entry): FloatArray? = DecodeCalibration.correction(if (e.tenBit) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264, e.tenBit)

    private fun start(c: Context, r: Req, out: File, tmp: File, codec: Perf.Codec?, hdr: Boolean, fps: Double) {
        if (running != null || calibrating) { queue.addFirst(r); return }
        val (mime, ten) = cacheMime()
        // first (once per phone and format): check that encode → decode gives the same colors
        if (!DecodeCalibration.isChecked(c, mime, ten != null)) {
            progress[r.clip] = 0
            calibrating = true
            DecodeCalibration.check(c, mime, ten != null, encoderFactory(c, ten, 20_000_000)) {
                calibrating = false
                // still wanted: render now (otherwise the next in the queue)
                if (running == null && lastWanted[r.clip]?.key == r.key && !ready.containsKey(r.key)) start(c, r, out, tmp, codec, hdr, fps)
                else { if (lastWanted[r.clip]?.key != r.key) progress.remove(r.clip); next() }
            }
            return
        }
        val w = codec?.width?.takeIf { it > 0 } ?: 1920
        val h = codec?.height?.takeIf { it > 0 } ?: 1080
        // "full quality": ≈ 0.8 bits per pixel per frame (HEVC), 1.2 (H.264); the encoder picks its nearest
        val bits = (w.toDouble() * h * fps.coerceIn(1.0, 120.0) * (if (ten != null) 0.8 else 1.2)).coerceIn(10e6, 800e6).toInt()
        val enc = encoderFactory(c, ten, bits)
        // the nodes with fixed values; tracked windows and grain get the source time
        val tb = TimeBase(r.end - r.start)
        val clock: (Long) -> Double? = { pts -> tb.seconds(pts)?.let { r.start + it } }
        val effects = ClipGrade(r.nodes).effects(null, clock)
        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(r.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionUs((r.start * 1e6).toLong()).setEndPositionUs((r.end * 1e6).toLong()).build(),
            ).build(),
        ).setRemoveAudio(true).setEffects(androidx.media3.transformer.Effects(emptyList(), effects)).build()
        val comp = Composition.Builder(EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO)).addItem(item).build())
            .setHdrMode(VideoPipeline.toneMapMode(hdr, false))
            .build()
        progress[r.clip] = 0
        se.gendut.klipp.CrashReport.note("node cache starting: clip ${r.clip}, ${"%.1f".format(java.util.Locale.US, r.end - r.start)} s, ${w}×$h ${if (ten != null) "HEVC 10-bit" else "H.264 8-bit"} ${bits / 1_000_000} Mbit/s")
        val t = Transformer.Builder(c)
            .setAssetLoaderFactory(Perf.assetLoaderFactory(c))
            .setVideoMimeType(mime)
            .setEncoderFactory(enc)
            .setVideoFrameProcessorFactory(VideoPipeline.processorFactory(ten != null))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    running = null
                    progress.remove(r.clip)
                    if (tmp.renameTo(out)) {
                        ready[r.key] = Entry(out, r.start, r.end, ten != null)
                        val old = byClip.put(r.clip, r.key)
                        save(c)
                        // playback needs the file's duration (MediaProbe) before it is rebuilt
                        Thread({
                            MediaProbe.probe(c, Uri.fromFile(out))
                            main.post {
                                if (old != null && old != r.key) drop(c, old)
                                revision++
                            }
                        }, "nodecache-done").apply { isDaemon = true }.start()
                    } else failed += r.key
                    next()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    running = null
                    progress.remove(r.clip)
                    se.gendut.klipp.CrashReport.note("node cache error: ${exportException.errorCodeName} ${exportException.message}")
                    tmp.delete()
                    failed += r.key
                    next()
                }
            })
            .build()
        running = r to t
        runCatching { t.start(comp, tmp.absolutePath) }.onFailure {
            running = null; progress.remove(r.clip); failed += r.key
            se.gendut.klipp.CrashReport.note("node cache could not start: ${it.message}")
            next(); return
        }
        val holder = ProgressHolder()
        main.post(object : Runnable {
            override fun run() {
                if (running?.second !== t) return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress[r.clip] = holder.progress
                main.postDelayed(this, 500)
            }
        })
    }

    /** Total size of the cache files (bytes). */
    fun size(context: Context): Long = dir(context).listFiles()?.filter { it.name.endsWith(".mp4") }?.sumOf { it.length() } ?: 0L

    /** Delete all cache files (they are re-rendered when needed). */
    fun clear(context: Context) {
        running?.second?.cancel(); running = null
        queue.clear(); progress.clear(); failed.clear()
        ready.clear(); byClip.clear()
        dir(context).listFiles()?.forEach { it.delete() }
        revision++
    }

    fun describe(): String = "node cache: ${ready.size} files" + (if (progress.isNotEmpty()) ", ${progress.size} rendering" else "") + "\n"
}
