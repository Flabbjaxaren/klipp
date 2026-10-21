package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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

/**
 * Proxy files ("optimized media" / cache): heavy files (4K, high frame rate, HDR) are re-encoded in the background
 * into a lighter file with dense keyframes – with the phone's hardware encoder, several times faster than realtime.
 * Playback, scrubbing and preview use the proxy; export always uses the original. Stored in the app's files and
 * reused across projects.
 *
 * Resolution, quality and bit depth are chosen in Settings. 10-bit proxies are HEVC Main10 and go through the
 * floating-point video chain (VideoPipeline) – Auto: 10-bit when the original is 10-bit (or HDR), otherwise 8 (H.264).
 * Each proxy carries its "signature" (resolution-quality-bitdepth): if the settings change it is redone in the
 * background, and the old one is used until the new one is done.
 */
@OptIn(UnstableApi::class)
object Proxies {
    /** Default proxy short side. */
    const val SHORT_SIDE = 1080
    val SIZES = listOf(720, 1080, 1440, 2160)
    /** Quality levels: bitrate factor relative to "Normal" (1080p H.264 12 Mbit/s). */
    private val QUALITY_LABEL_IDS = intArrayOf(R.string.proxies_quality_low, R.string.proxies_quality_normal, R.string.proxies_quality_high, R.string.proxies_quality_very_high)
    val QUALITY_LABELS: List<String> get() = QUALITY_LABEL_IDS.map { Str.t(it) }
    /** English quality names for diagnostics. */
    private val QUALITY_NAMES = listOf("Low", "Normal", "High", "Very high")
    private val QUALITY_FACTOR = floatArrayOf(0.5f, 1f, 2f, 4f)

    /** Use the proxy for playback (saved). */
    var enabled by mutableStateOf(true)
        private set
    /** Proxy short side (pixels). */
    var shortSide by mutableIntStateOf(SHORT_SIDE)
        private set
    /** 0 Low, 1 Normal, 2 High, 3 Very high. */
    var quality by mutableIntStateOf(1)
        private set
    /** 0 = Auto (same as the original), 8 or 10. */
    var bitDepth by mutableIntStateOf(0)
        private set
    /** Incremented when a proxy is done (playback then switches to it). */
    var revision by mutableIntStateOf(0)
        private set
    /** Running/queued: source → percent (−1 = queued). */
    val progress = mutableStateMapOf<String, Int>()
    /** Sources whose transcode failed (not retried in this session). */
    private val failed = HashSet<String>()
    /** HDR sources where the first tone mapping failed – the next attempt uses the other one (VideoPipeline.toneMapMode). */
    private val glTried = HashSet<String>()

    /** A finished proxy and its signature (resolution-quality-bitdepth). */
    class Entry(val file: File, val sig: String)

    /** Finished proxy files. Also read from background threads (scrub frames, tracking) – hence thread-safe. */
    private val ready = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    /** Sources already probed (main thread): need no proxy, or probing is in progress – not again. */
    private val checked = HashSet<String>()

    private class Job(val uri: Uri, val hdr: Boolean, val depth: Int, val short: Int, val sig: String)

    private val queue = ArrayDeque<Job>()
    private var running: Transformer? = null
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    /** The latest list passed to [ensure] (to redo the proxies when the settings change). */
    private var lastMedia: List<Pair<Uri, MediaInfo>> = emptyList()

    private fun dir(c: Context) = File(c.filesDir, "proxies").apply { mkdirs() }
    private fun index(c: Context) = File(dir(c), "index.json")
    private fun key(u: Uri) = u.toString()
    private fun fileFor(c: Context, u: Uri, sig: String) =
        File(dir(c), "p_" + key(u).hashCode().toUInt().toString(16) + "_" + key(u).length + (if (sig == LEGACY_SIG) "" else "_$sig") + ".mp4")

    /** The signature of proxy files from older versions (1080p, 12 Mbit/s H.264, 8-bit). */
    private const val LEGACY_SIG = "1080-1-8"

    fun sig(short: Int, quality: Int, depth: Int) = "$short-$quality-$depth"

    private fun prefs(c: Context) = c.getSharedPreferences("klipp", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val c = app!!
        val p = prefs(c)
        enabled = p.getBoolean("proxies", true)
        shortSide = p.getInt("proxyShort", SHORT_SIDE).takeIf { it in SIZES } ?: SHORT_SIDE
        quality = p.getInt("proxyQuality", 1).coerceIn(0, 3)
        bitDepth = p.getInt("proxyBits", 0).let { if (it == 8 || it == 10) it else 0 }
        runCatching {
            val o = JSONObject(index(c).readText())
            for (k in o.keys()) {
                // older index: just the path
                val e = o.optJSONObject(k)
                val f = File(e?.optString("f") ?: o.getString(k))
                val sg = e?.optString("s")?.ifBlank { null } ?: LEGACY_SIG
                if (f.exists() && f.length() > 0) ready[k] = Entry(f, sg)
            }
        }
    }

    fun switch(on: Boolean) {
        enabled = on
        app?.let { prefs(it).edit().putBoolean("proxies", on).apply() }
        revision++
    }

    /** New proxy settings: files with a different signature are redone in the background (the old ones are used meanwhile). */
    fun setFormat(context: Context, short: Int = shortSide, quality: Int = this.quality, bits: Int = bitDepth) {
        shortSide = short.takeIf { it in SIZES } ?: SHORT_SIDE
        this.quality = quality.coerceIn(0, 3)
        bitDepth = if (bits == 8 || bits == 10) bits else 0
        prefs(context).edit().putInt("proxyShort", shortSide).putInt("proxyQuality", this.quality).putInt("proxyBits", bitDepth).apply()
        checked.clear()
        failed.clear()
        ensure(context, lastMedia)
    }

    fun hasProxy(uri: Uri): Boolean = ready.containsKey(key(uri))

    /** The file playback should use for the picture. */
    fun videoUri(uri: Uri): Uri = if (enabled) ready[key(uri)]?.let { Uri.fromFile(it.file) } ?: uri else uri

    /** The proxy's short side (pixels) for a source, if there is one (older proxies: 1080). */
    fun proxyShort(uri: Uri): Int? = ready[key(uri)]?.sig?.substringBefore('-')?.toIntOrNull()

    /**
     * Does the file need a proxy? Larger than the proxy short side, high frame rate above 720p, HDR – or a heavy codec
     * ([heavy]: APV/ProRes, no hardware decoder, very high bitrate) at any resolution.
     */
    fun needs(m: MediaInfo, hdr: Boolean = false, heavy: Boolean = false, short: Int = SHORT_SIDE): Boolean {
        if (!m.hasVideo) return false
        if (heavy) return true
        if (m.width <= 0 || m.height <= 0) return false
        val s = minOf(m.width, m.height)
        return s > short || (m.fps > 50 && m.width * m.height > 1280 * 720) || (hdr && s >= 720)
    }

    /** The proxy bit depth for a source per the setting (Auto: same as the original; 10 only if some encoder supports Main10). */
    private fun depthFor(context: Context, u: Uri, hdr: Boolean): Int {
        val want = when (bitDepth) { 8 -> 8; 10 -> 10; else -> if (hdr || VideoPipeline.bitDepth(context, u) >= 10) 10 else 8 }
        return if (want == 10 && VideoPipeline.tenBitProfile(MimeTypes.VIDEO_H265) == null) 8 else want
    }

    /**
     * Queue files that need a proxy (those that already exist with the right signature or are in progress are skipped).
     * The files are probed (HDR? bit depth?) on a background thread – never on the main thread.
     */
    fun ensure(context: Context, media: List<Pair<Uri, MediaInfo>>) {
        init(context)
        lastMedia = media
        val todo = media.filter { (u, m) -> val k = key(u); !progress.containsKey(k) && k !in failed && k !in checked && m.hasVideo }
        if (todo.isEmpty()) return
        // marked right away: every edit calls ensure – otherwise the same files were probed over and over
        todo.forEach { (u, _) -> checked += key(u) }
        val short = shortSide; val q = quality
        Thread({
            val picked = todo.mapNotNull { (u, m) ->
                val hdr = Perf.isHdr(context, u) == true
                val codec = Perf.codec(context, u)
                if (codec != null) se.gendut.klipp.CrashReport.note("media ${u.lastPathSegment}: ${codec.mime} ${codec.bitrate?.div(1_000_000)} Mbit/s hw=${codec.hardware} ${VideoPipeline.bitDepth(context, u)}-bit")
                if (!needs(m, hdr, Perf.heavy(codec), short)) return@mapNotNull null
                val depth = depthFor(context, u, hdr)
                val target = minOf(short, minOf(m.width, m.height).takeIf { it > 0 } ?: short)
                val sg = sig(short, q, depth)
                if (ready[key(u)]?.sig == sg) null else Job(u, hdr, depth, target, sg)
            }
            main.post {
                for (j in picked) {
                    val k = key(j.uri)
                    if (ready[k]?.sig == j.sig || progress.containsKey(k) || k in failed) continue
                    progress[k] = -1
                    queue.addLast(j)
                }
                next()
            }
        }, "proxy-probe").apply { isDaemon = true }.start()
    }

    /** Proxy bitrate (bit/s): Normal = 12 Mbit/s at 1080p (H.264), HEVC ≈ 70 %. */
    fun bitrate(short: Int, quality: Int, hevc: Boolean): Int {
        val px = (short.toDouble() / 1080.0).let { it * it }
        val v = 12_000_000.0 * px * QUALITY_FACTOR[quality.coerceIn(0, 3)] * (if (hevc) 0.7 else 1.0)
        return v.toInt().coerceIn(2_000_000, 400_000_000)
    }

    private fun next() {
        val c = app ?: return
        if (running != null) return
        val job = queue.removeFirstOrNull() ?: return
        val u = job.uri
        val k = key(u)
        val out = fileFor(c, u, job.sig)
        val tmp = File(out.path + ".tmp")
        tmp.delete()
        val ten = if (job.depth == 10) VideoPipeline.tenBitProfile(MimeTypes.VIDEO_H265) else null
        val mime = if (ten != null) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val vs = VideoEncoderSettings.Builder()
            .setBitrate(bitrate(job.short, quality, ten != null))
            // dense keyframes: every seek (scrub) decodes at most half a second
            .setiFrameIntervalSeconds(0.5f)
        if (ten != null) vs.setEncodingProfileLevel(ten.first, ten.second)
        val enc = DefaultEncoderFactory.Builder(c)
            .setRequestedVideoEncoderSettings(vs.build())
            .setEnableFallback(true)
            .build()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(u))
            .setRemoveAudio(true) // audio plays from the original
            .setEffects(androidx.media3.transformer.Effects(emptyList(), listOf(androidx.media3.effect.Presentation.createForShortSide(job.short))))
            .build()
        val comp = Composition.Builder(EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO)).addItem(item).build())
            .setHdrMode(VideoPipeline.toneMapMode(job.hdr, k in glTried))
            .build()
        progress[k] = 0
        se.gendut.klipp.CrashReport.note("proxy starting: ${u.lastPathSegment} hdr=${job.hdr} ${job.short}p ${job.depth}-bit ${mime.substringAfter('/')}")
        // Snapdragon: the encoding threads on the big cores (see Perf.Boost)
        val boost = Perf.Boost(c)
        val t = Transformer.Builder(c)
            .setAssetLoaderFactory(Perf.assetLoaderFactory(c))
            .setVideoMimeType(mime)
            .setEncoderFactory(enc)
            // floating point in the chain; 10-bit: RGBA_1010102 into the encoder
            .setVideoFrameProcessorFactory(VideoPipeline.processorFactory(ten != null))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    boost.close()
                    running = null
                    if (tmp.renameTo(out)) {
                        val old = ready[k]
                        ready[k] = Entry(out, job.sig)
                        if (old != null && old.file != out) old.file.delete()
                        save(c)
                        revision++
                    } else failed += k
                    progress.remove(k)
                    next()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    boost.close()
                    running = null
                    se.gendut.klipp.CrashReport.note("proxy error: ${exportException.errorCodeName} ${exportException.message}")
                    tmp.delete()
                    if (job.hdr && k !in glTried) {
                        // HDR: once more with the other tone mapping
                        glTried += k
                        queue.addFirst(job)
                        progress[k] = -1
                        next()
                        return
                    }
                    if (job.depth == 10) {
                        // 10-bit didn't work on the phone: 8-bit (H.264)
                        val j8 = Job(u, job.hdr, 8, job.short, sig(job.sig.substringBefore('-').toInt(), quality, 8))
                        queue.addFirst(j8)
                        progress[k] = -1
                        next()
                        return
                    }
                    failed += k
                    progress.remove(k)
                    next()
                }
            })
            .build()
        running = t
        runCatching { t.start(comp, tmp.absolutePath) }.onFailure { boost.close(); running = null; failed += k; progress.remove(k); next(); return }
        val holder = ProgressHolder()
        main.post(object : Runnable {
            override fun run() {
                if (running !== t) return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress[k] = holder.progress
                main.postDelayed(this, 500)
            }
        })
    }

    private fun save(c: Context) {
        runCatching {
            val o = JSONObject()
            ready.forEach { (k, e) -> o.put(k, JSONObject().put("f", e.file.absolutePath).put("s", e.sig)) }
            index(c).writeText(o.toString())
        }
    }

    /** Total size of the proxy files (bytes). */
    fun size(context: Context): Long = dir(context).listFiles()?.filter { it.name.endsWith(".mp4") }?.sumOf { it.length() } ?: 0L

    /** Delete all proxy files (they are recreated when needed). */
    fun clear(context: Context) {
        running?.cancel(); running = null
        queue.clear(); progress.clear(); failed.clear(); checked.clear()
        ready.clear()
        dir(context).listFiles()?.forEach { it.delete() }
        revision++
    }

    /** Proxy status for a file, for diagnostics. */
    fun statusOf(uri: Uri): String {
        val k = key(uri)
        val e = ready[k]
        return when {
            progress[k]?.let { it >= 0 } == true -> "creating ${progress[k]} %" + (e?.let { " (previous in use: ${it.sig})" } ?: "")
            progress.containsKey(k) -> "queued" + (e?.let { " (previous in use: ${it.sig})" } ?: "")
            e != null -> (if (enabled) "in use" else "ready but disabled") + " (${e.sig.replace('-', ' ')})"
            k in failed -> "failed (see the trace report)"
            else -> "none (not needed)"
        }
    }

    fun describe(): String {
        val busy = progress.entries.firstOrNull { it.value >= 0 }
        return "proxy: ${if (enabled) "on" else "off"} ${shortSide}p ${QUALITY_NAMES[quality]} ${if (bitDepth == 0) "Auto" else "$bitDepth-bit"}, ${ready.size} ready" +
            (if (progress.isNotEmpty()) ", ${progress.size} in progress" + (busy?.let { " (${it.value} %)" } ?: "") else "") +
            (if (failed.isNotEmpty()) ", ${failed.size} failed" else "") + "\n"
    }
}
