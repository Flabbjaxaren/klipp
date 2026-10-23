package se.gendut.klipp.engine

import android.content.Context
import java.io.File
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Performance for the phone's (Snapdragon) hardware: how the players buffer, when late frames are dropped
 * before they are graded, HDR footage tone-mapped in the hardware decoder, and thermals/load.
 */
@OptIn(UnstableApi::class)
object Perf {
    /**
     * Editing: short buffer. The player starts showing after 250 ms instead of 2.5 s (fast start after
     * seeking and rebuilding), and doesn't decode far ahead (less memory and heat with several layers).
     */
    fun editingLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(/* min */ 500, /* max */ 8_000, /* for playback */ 250, /* after rebuffer */ 500)
        .setTargetBufferBytes(MAX_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    /**
     * Buffer cap in bytes – but only beyond the minimum time (0.5 s, Media3's own lower bound), which is always filled.
     * A cap without a minimum time locked up playback: the camera's MP4 has audio in chunks after ≈ 1 s of video, and for
     * APV at 600 Mbit/s that is 80 MB – the player stopped reading before the audio arrived and stood still (position 0,
     * "playing"). Memory is instead protected by Media3's own guard (stops reading when Java memory is nearly full) and
     * the short minimum time: previously 2 s for the player and 50 s / 128 MB for Transformer, which with APV caused
     * OutOfMemoryError.
     */
    const val MAX_BUFFER_BYTES = 32 * 1024 * 1024

    /** Buffer for the source monitor and Transformer (export, proxy, node cache): at least 0.5 s, then at most [MAX_BUFFER_BYTES]. */
    fun boundedLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(/* min */ 500, /* max */ 4_000, /* for start */ 250, /* after rebuffer */ 500)
        .setTargetBufferBytes(MAX_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    /**
     * Transformer's asset loading with [boundedLoadControl] (Media3's own has 128 MB for video). Still images work as
     * before; each loader gets its own LoadControl (they can run on different threads).
     */
    fun assetLoaderFactory(context: Context): androidx.media3.transformer.AssetLoader.Factory {
        val app = context.applicationContext
        val decoders = androidx.media3.transformer.DefaultDecoderFactory.Builder(app).build()
        val images = androidx.media3.transformer.DefaultAssetLoaderFactory(app, decoders, androidx.media3.common.util.Clock.DEFAULT, null)
        return androidx.media3.transformer.AssetLoader.Factory { item, looper, listener, settings ->
            if (item.mediaItem.localConfiguration?.imageDurationMs?.let { it != androidx.media3.common.C.TIME_UNSET } == true) {
                images.createAssetLoader(item, looper, listener, settings)
            } else {
                androidx.media3.transformer.ExoPlayerAssetLoader.Factory(app, decoders, androidx.media3.common.util.Clock.DEFAULT, null, null, null, boundedLoadControl())
                    .createAssetLoader(item, looper, listener, settings)
            }
        }
    }


    @Volatile private var app: Context? = null
    fun init(context: Context) { app = context.applicationContext }

    /** ADPF hint for the preview's grading thread (an image should take less than a frame) and the still-image thread. */
    val gradeHint by lazy { app?.let { Hint(it, 12) } }

    /** Preview image width (lower when the phone is hot). */
    fun previewWidth(): Int = app?.let { previewWidth(it) } ?: 1920

    // ------------------------------------------------------------------ HDR

    private val hdrCache = HashMap<String, Boolean>()
    /** Incremented when an HDR probe has finished (playback may need rebuilding). */
    var hdrRevision by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** Only what's already probed (null = not yet) – never blocks. */
    fun cachedHdr(uri: Uri): Boolean? = synchronized(hdrCache) { hdrCache[uri.toString()] }

    /** Probe the files on a background thread (never on the main thread – that could freeze the app). */
    suspend fun prefetchHdr(context: Context, uris: Collection<Uri>) {
        val todo = uris.filter { cachedHdr(it) == null }
        if (todo.isEmpty()) return
        var any = false
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { for (u in todo) if (isHdr(context, u) == true) any = true }
        if (any) hdrRevision++
    }

    /**
     * Is the file HDR (PQ/HDR10/HDR10+ or HLG)? Phones often record like that. null = unknown. The result
     * is cached per file (probing takes a few milliseconds the first time).
     */
    fun isHdr(context: Context, uri: Uri): Boolean? {
        val key = uri.toString()
        synchronized(hdrCache) { hdrCache[key]?.let { return it } }
        val r = runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                val f = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?: return@runCatching false
                hdrFormat(f)
            } finally {
                ex.release()
            }
        }.getOrNull() ?: return null
        synchronized(hdrCache) { hdrCache[key] = r }
        return r
    }

    // ------------------------------------------------------------------ heavy codecs (APV, ProRes …)

    /** What makes a file heavy to decode: codec, bitrate and whether the phone has a hardware decoder for it. */
    data class Codec(val mime: String, val bitrate: Long?, val hardware: Boolean, val decoder: String? = null, val width: Int = 0, val height: Int = 0)

    private val codecCache = HashMap<String, Codec>()

    /** Already probed codec (immediate, for building playback on the main thread), otherwise null. */
    fun cachedCodec(uri: Uri): Codec? = synchronized(codecCache) { codecCache[uri.toString()] }

    /** For tests: a probed codec without a file. */
    internal fun rememberCodec(uri: Uri, c: Codec) { synchronized(codecCache) { codecCache[uri.toString()] = c } }

    /** Probe the video track's codec (background thread). null = could not be read. */
    fun codec(context: Context, uri: Uri): Codec? {
        val key = uri.toString()
        synchronized(codecCache) { codecCache[key]?.let { return it } }
        val r = runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                val f = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?: return@runCatching null
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                val w = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 0
                val h = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0
                var br = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE).toLong() else null
                if (br == null || br <= 0) {
                    // whole file size / duration (video accounts for nearly everything in a high-bitrate file)
                    val us = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                    val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
                        ?: uri.path?.let { java.io.File(it).length() }
                    if (us > 0 && size != null && size > 0) br = size * 8 * 1_000_000 / us
                }
                Codec(mime, br, hardwareDecoder(mime, w, h), decoderName(mime, w, h), w, h)
            } finally {
                ex.release()
            }
        }.getOrNull() ?: return null
        synchronized(codecCache) { codecCache[key] = r }
        return r
    }

    /** Is there a hardware decoder for the codec at this resolution? (Before Android 10 there's no way to know: yes.) */
    fun hardwareDecoder(mime: String, w: Int, h: Int): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        return runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
                !ci.isEncoder && ci.isHardwareAccelerated && ci.supportedTypes.any { it.equals(mime, true) } &&
                    (w <= 0 || h <= 0 || runCatching { ci.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(w, h) != false }.getOrDefault(true))
            }
        }.getOrDefault(true)
    }

    /** The decoder Android picks for apps (hardware first), for diagnostics. */
    fun decoderName(mime: String, w: Int, h: Int): String? = runCatching {
        val all = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { ci -> !ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } }
        val hw = if (Build.VERSION.SDK_INT >= 29) all.filter { it.isHardwareAccelerated } else emptyList()
        (hw.firstOrNull() ?: all.firstOrNull())?.let { ci ->
            ci.name + if (Build.VERSION.SDK_INT >= 29) (if (ci.isHardwareAccelerated) " (hardware)" else " (software)") else ""
        } ?: "no decoder for $mime"
    }.getOrNull()

    /** Bitrate at which decoding and file reading start to become a problem when scrubbing and with several layers. */
    const val HEAVY_BITRATE = 80_000_000L

    /** Does the file need a light proxy because of its codec? APV/ProRes/DNx (heavy intra codecs), software decoding or a very high bitrate. */
    fun heavy(c: Codec?): Boolean {
        if (c == null) return false
        val m = c.mime.lowercase()
        return m.contains("apv") || m.contains("prores") || m.contains("dnx") || !c.hardware || (c.bitrate ?: 0L) >= HEAVY_BITRATE
    }

    fun hdrFormat(f: MediaFormat): Boolean {
        if (!f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) return false
        val tr = f.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        return tr == MediaFormat.COLOR_TRANSFER_ST2084 || tr == MediaFormat.COLOR_TRANSFER_HLG
    }

    /**
     * How HDR footage is converted to SDR (Klipp grades and exports in SDR): in the decoder (Qualcomm's
     * hardware can do it from Android 13 – cheap) or with OpenGL (works everywhere, costs GPU).
     */
    fun hdrMode(anyHdr: Boolean, mediaCodecFailed: Boolean): Int = when {
        !anyHdr -> androidx.media3.transformer.Composition.HDR_MODE_KEEP_HDR
        Build.VERSION.SDK_INT >= 33 && !mediaCodecFailed -> androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC
        else -> androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
    }

    // ------------------------------------------------------------------ thermals and load

    /** How close the phone is to throttling the CPU due to heat (0 = cool, 1 = throttling). null = unknown. */
    @Volatile private var headroomAt = 0L
    @Volatile private var headroom: Float? = null

    /**
     * How close the phone is to throttling (1 = at the limit). Read at most every five seconds: Android returns NaN if
     * asked more than once per second (then "hot phone" never worked during scrubbing) – and every query is a system
     * call.
     */
    fun thermalHeadroom(context: Context): Float? {
        if (Build.VERSION.SDK_INT < 30) return null
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - headroomAt < 5000 && headroomAt != 0L) return headroom
        headroomAt = now
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return headroom
        runCatching { pm.getThermalHeadroom(5) }.getOrNull()?.takeIf { !it.isNaN() }?.let { headroom = it }
        return headroom
    }

    /**
     * Preview image width: about the screen's width in full screen (grading judged on a 960 px still showed blocks and
     * steps that playback didn't have); lower when the phone is hot, so it doesn't throttle even more.
     */
    fun previewWidth(context: Context): Int = when ((thermalHeadroom(context) ?: 0f)) {
        in 0.9f..Float.MAX_VALUE -> 1280
        else -> 1920
    }

    fun describe(context: Context): String = buildString {
        append("soc: ").append(if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE).append('\n')
        thermalHeadroom(context)?.let { append("thermal: %.0f %% of the limit\n".format(java.util.Locale.US, it * 100)) }
        append("gpu grade: ").append(if (GpuGrade.supported && !GpuGrade.broken) "yes" else "no" + (GpuGrade.lastError?.let { " ($it)" } ?: "")).append('\n')
    }

    /**
     * ADPF for a throughput job (export, pre-render, proxies, reversed clips): the encoding threads (Media3's effect
     * thread, the decoder and encoder loops, loading) are put in a hint session that keeps reporting that the work takes
     * longer than the target – then the scheduler keeps them on the big cores at a high clock. Transformer starts the
     * threads gradually, so they are looked up again every 500 ms. Closed when the job is done.
     */
    class Boost(context: Context) : AutoCloseable {
        private val manager: Any? = if (Build.VERSION.SDK_INT >= 31) runCatching { context.getSystemService(android.os.PerformanceHintManager::class.java) }.getOrNull() else null
        private var session: Any? = null
        private var tids = IntArray(0)
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val tick = object : Runnable {
            override fun run() {
                if (Build.VERSION.SDK_INT < 31) return
                refresh()
                // reported above the target: "the job is not keeping up" – clock up
                runCatching { (session as? android.os.PerformanceHintManager.Session)?.reportActualWorkDuration(TARGET_NS * 2) }
                handler.postDelayed(this, 100)
            }
        }
        private var lastScan = 0L

        init { if (manager != null) handler.post(tick) }

        @androidx.annotation.RequiresApi(31)
        private fun refresh() {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastScan < 500) return
            lastScan = now
            val found = threads()
            if (found.isEmpty() || found.contentEquals(tids)) return
            tids = found
            runCatching {
                val s = session as? android.os.PerformanceHintManager.Session
                if (s != null && Build.VERSION.SDK_INT >= 34) s.setThreads(found)
                else { s?.close(); session = (manager as? android.os.PerformanceHintManager)?.createHintSession(found, TARGET_NS) }
            }
        }

        override fun close() {
            handler.removeCallbacks(tick)
            if (Build.VERSION.SDK_INT >= 31) runCatching { (session as? android.os.PerformanceHintManager.Session)?.close() }
            session = null
        }

        private companion object {
            const val TARGET_NS = 8_000_000L
            /** Thread name prefixes (Linux truncates them to 15 characters). */
            val PREFIXES = listOf("Effect:", "MediaCodec", "CodecLooper", "Transformer", "ExoPlayer:Load", "ExoPlayer:Play")

            fun threads(): IntArray = File("/proc/self/task").listFiles().orEmpty().mapNotNull { t ->
                val name = runCatching { File(t, "comm").readText().trim() }.getOrNull() ?: return@mapNotNull null
                if (PREFIXES.any { name.startsWith(it) }) t.name.toIntOrNull() else null
            }.sorted().toIntArray()
        }
    }

    /**
     * ADPF (Android 12+): tell the scheduler how long a job on its own thread may take, so the Snapdragon cores
     * clock up in time instead of after the job is already late.
     */
    class Hint(private val context: Context, private val targetMs: Long) {
        private var session: Any? = null
        private var tried = false

        fun <T> run(block: () -> T): T {
            if (Build.VERSION.SDK_INT < 31) return block()
            val t0 = System.nanoTime()
            try {
                return block()
            } finally {
                report(System.nanoTime() - t0)
            }
        }

        private fun report(ns: Long) {
            if (Build.VERSION.SDK_INT < 31) return
            if (!tried) {
                tried = true
                session = runCatching {
                    val m = context.getSystemService(android.os.PerformanceHintManager::class.java)
                    m?.createHintSession(intArrayOf(android.os.Process.myTid()), targetMs * 1_000_000L)
                }.getOrNull()
            }
            runCatching { (session as? android.os.PerformanceHintManager.Session)?.reportActualWorkDuration(ns) }
        }
    }
}
