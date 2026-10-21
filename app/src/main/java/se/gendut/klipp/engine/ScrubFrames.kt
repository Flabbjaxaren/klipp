package se.gendut.klipp.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Scrub frames: small images (long side [SIZE] px, [PER_SEC] per second) from each clip, decoded once in the
 * background (hardware decoder, sequentially – no seeks) and stored as JPEG in the app's cache.
 *
 * Most mobile editors do this (LumaFusion, CapCut, VN): while the finger drags, a low-res image is shown straight
 * from the cache – never black, no waiting for the decoder – and the exact full-resolution frame when the finger
 * rests or lifts. Without a cache (yet) the nearest keyframe is used as before.
 */
object ScrubFrames {
    const val SIZE = 320
    const val PER_SEC = 6
    /** Files longer than this aren't built (it takes too long; keyframes will have to do). */
    private const val MAX_SEC = 30 * 60
    private const val MAX_DISK = 400L * 1024 * 1024

    private var root: File? = null
    /** Per file: time (ms) → exists on disk. */
    private val index = ConcurrentHashMap<String, ConcurrentSkipListMap<Long, Boolean>>()
    private val done = ConcurrentHashMap.newKeySet<String>()
    /**
     * Files that could not be built (HDR without a proxy, too long, broken): the key is the source + the decoded file,
     * so they are retried only once a proxy exists – not on every scrub.
     */
    private val skipped = ConcurrentHashMap.newKeySet<String>()
    /** Playback is running: building waits (the decoder is needed for the picture being played). */
    @Volatile var paused = false
    private val building = ConcurrentHashMap<String, Job>()
    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 72
    }
    private val builder = Executors.newSingleThreadExecutor { r -> Thread(r, "scrub-build").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }.asCoroutineDispatcher()
    private val reader = Executors.newSingleThreadExecutor { r -> Thread(r, "scrub-read").apply { isDaemon = true } }.asCoroutineDispatcher()
    /** Prefetching on its own thread: the frame under the finger (reader) never waits behind it. */
    private val prefetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "scrub-prefetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())

    private fun dirOf(key: String) = File(root, Integer.toHexString(key.hashCode()) + "_" + key.length)

    fun init(context: Context) {
        if (root != null) return
        root = File(context.cacheDir, "scrub").apply { mkdirs() }
        scope.launch(builder) { trim() }
    }

    /** Delete old files' frames when the cache gets too big (least recently used first). */
    private fun trim() {
        val dirs = root?.listFiles()?.filter { it.isDirectory } ?: return
        var total = dirs.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }
        for (d in dirs.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK) break
            total -= d.listFiles()?.sumOf { it.length() } ?: 0L
            d.deleteRecursively()
        }
    }

    private fun indexOf(key: String): ConcurrentSkipListMap<Long, Boolean> = index.getOrPut(key) {
        ConcurrentSkipListMap<Long, Boolean>().also { m ->
            val d = dirOf(key)
            d.listFiles()?.forEach { f -> f.nameWithoutExtension.toLongOrNull()?.let { m[it] = true } }
            if (File(d, "klar").exists()) done += key
        }
    }

    /**
     * Build the scrub frames for [media] in the background if they don't already exist (one file at a time, lowest
     * priority). Decodes from the proxy when there is one (light 1080p), otherwise the original.
     */
    fun ensure(context: Context, media: Uri) {
        init(context)
        val key = media.toString()
        if (key in done || building.containsKey(key)) return
        val attempt = key + "|" + Proxies.videoUri(media)
        if (attempt in skipped) return
        building[key] = scope.launch(builder) {
            try {
                // the directory is read here (background thread), not on the caller's thread
                indexOf(key)
                if (key !in done && !build(context, media, key)) skipped += attempt
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                skipped += attempt
            } finally {
                building.remove(key)
            }
        }
    }

    /** Builds the scrub frames; false = the file can't be built (then keyframes will have to do). */
    private suspend fun build(context: Context, media: Uri, key: String): Boolean {
        if (key in done) return true
        val v = WindowTracker.open(context, Proxies.videoUri(media))
        try {
            // HDR (PQ/HLG) would look washed out without tone mapping – keyframes will have to do there
            if (Perf.hdrFormat(v.format)) return false
            val durUs = if (v.format.containsKey(android.media.MediaFormat.KEY_DURATION)) v.format.getLong(android.media.MediaFormat.KEY_DURATION) else -1L
            val dur = if (durUs > 0) durUs / 1e6 else return false
            if (dur > MAX_SEC) return false
            val d = dirOf(key).apply { mkdirs(); setLastModified(System.currentTimeMillis()) }
            val m = indexOf(key)
            val step = 1.0 / PER_SEC
            val yuv = Yuv.of(v.format)
            var next = 0.0
            WindowTracker.decode(v, 0.0, dur) { pts, img ->
                // during playback building waits (the decoder and CPU are needed for the picture being played)
                while (paused) kotlinx.coroutines.delay(200)
                if (pts + 1e-4 < next) return@decode
                next = (Math.floor(pts / step + 1e-6) + 1) * step
                val ms = (pts * 1000).roundToInt().toLong()
                if (m.containsKey(ms)) return@decode
                val bmp = small(img, v.rotation, yuv) ?: return@decode
                val f = File(d, "$ms.jpg")
                val tmp = File(d, "$ms.tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 72, it) }
                bmp.recycle()
                if (tmp.renameTo(f)) m[ms] = true
            }
            File(d, "klar").createNewFile()
            done += key
            return true
        } finally {
            v.ex.release()
        }
    }

    /** YUV → RGB conversion per the file's color standard and range (BT.709/BT.601, limited/full). */
    internal class Yuv(val full: Boolean, val rv: Float, val gu: Float, val gv: Float, val bu: Float) {
        companion object {
            fun of(f: android.media.MediaFormat): Yuv {
                val full = f.containsKey(android.media.MediaFormat.KEY_COLOR_RANGE) && f.getInteger(android.media.MediaFormat.KEY_COLOR_RANGE) == android.media.MediaFormat.COLOR_RANGE_FULL
                val std = if (f.containsKey(android.media.MediaFormat.KEY_COLOR_STANDARD)) f.getInteger(android.media.MediaFormat.KEY_COLOR_STANDARD) else -1
                val bt601 = std == android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC || std == android.media.MediaFormat.COLOR_STANDARD_BT601_PAL
                // the Kr/Kb coefficients, scaled for the range (limited: 224/255 for chroma)
                val (kr, kb) = if (bt601) 0.299f to 0.114f else 0.2126f to 0.0722f
                val kg = 1f - kr - kb
                val c = if (full) 1f else 255f / 224f
                return Yuv(full, 2f * (1f - kr) * c, 2f * (1f - kb) * kb / kg * c, 2f * (1f - kr) * kr / kg * c, 2f * (1f - kb) * c)
            }
        }
    }

    /**
     * The YUV image from the decoder → small ARGB image: luma as the mean of 2×2 samples, color from the nearest
     * chroma sample (the file's color standard and range), rotated the way the file should be displayed.
     */
    internal fun small(img: Image, rotation: Int, yuv: Yuv = Yuv(false, 1.793f, 0.213f, 0.533f, 2.112f)): Bitmap? {
        if (img.format != ImageFormat.YUV_420_888) return null
        val crop = img.cropRect
        val w = crop.width(); val h = crop.height()
        if (w < 2 || h < 2) return null
        val sc = SIZE.toFloat() / maxOf(w, h)
        val ow = (w * sc).roundToInt().coerceAtLeast(1)
        val oh = (h * sc).roundToInt().coerceAtLeast(1)
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRs = yP.rowStride; val yPs = yP.pixelStride
        val uRs = uP.rowStride; val uPs = uP.pixelStride
        val vRs = vP.rowStride; val vPs = vP.pixelStride
        val px = IntArray(ow * oh)
        val fx = w.toFloat() / ow; val fy = h.toFloat() / oh
        for (oy in 0 until oh) {
            val sy = crop.top + minOf(h - 2, (oy * fy).toInt())
            for (ox in 0 until ow) {
                val sx = crop.left + minOf(w - 2, (ox * fx).toInt())
                val y0 = sy * yRs + sx * yPs
                val yy = ((yB.get(y0).toInt() and 255) + (yB.get(y0 + yPs).toInt() and 255) +
                    (yB.get(y0 + yRs).toInt() and 255) + (yB.get(y0 + yRs + yPs).toInt() and 255)) / 4
                val cu = (uB.get((sy / 2) * uRs + (sx / 2) * uPs).toInt() and 255) - 128
                val cv = (vB.get((sy / 2) * vRs + (sx / 2) * vPs).toInt() and 255) - 128
                val l = if (yuv.full) yy.toFloat() else 1.164f * (yy - 16)
                val r = (l + yuv.rv * cv).roundToInt().coerceIn(0, 255)
                val g = (l - yuv.gu * cu - yuv.gv * cv).roundToInt().coerceIn(0, 255)
                val b = (l + yuv.bu * cu).roundToInt().coerceIn(0, 255)
                px[oy * ow + ox] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
        if (rotation % 360 == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, ow, oh, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    /** Time (s) of the nearest scrub frame within [lo]–[hi] and at most [maxGap] s from [sec]. */
    fun nearestTime(media: Uri, sec: Double, lo: Double = 0.0, hi: Double = Double.MAX_VALUE, maxGap: Double = 0.5): Double? {
        val m = index[media.toString()] ?: return null
        val ms = (sec * 1000).toLong()
        val cands = listOfNotNull(m.floorKey(ms), m.ceilingKey(ms)).map { it / 1000.0 }
            .filter { it >= lo - 0.001 && it < hi && abs(it - sec) <= maxGap }
        return cands.minByOrNull { abs(it - sec) }
    }

    /** The scrub frame if it's already in memory (immediate, for the composition). */
    fun peek(media: Uri, sec: Double, lo: Double = 0.0, hi: Double = Double.MAX_VALUE): Pair<Double, ImageBitmap>? {
        val t = nearestTime(media, sec, lo, hi) ?: return null
        return synchronized(memory) { memory["$media@${(t * 1000).roundToInt()}"] }?.let { t to it }
    }

    /** The scrub frame nearest [sec] (from memory or disk, a few milliseconds). */
    suspend fun load(media: Uri, sec: Double, lo: Double = 0.0, hi: Double = Double.MAX_VALUE): Pair<Double, ImageBitmap>? {
        val t = nearestTime(media, sec, lo, hi) ?: return null
        val k = "$media@${(t * 1000).roundToInt()}"
        synchronized(memory) { memory[k] }?.let { return t to it }
        return withContext(reader) {
            val f = File(dirOf(media.toString()), "${(t * 1000).roundToInt()}.jpg")
            val b = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()?.asImageBitmap() ?: return@withContext null
            synchronized(memory) { memory[k] = b }
            t to b
        }
    }

    /** Preload the frames around [sec] into memory (scrubbing usually continues in the same direction). */
    private var prefetchJob: Job? = null

    fun prefetch(media: Uri, sec: Double, lo: Double, hi: Double) {
        // only the latest: the previous finger position's prefetch must not be queued ahead of the frame needed now
        prefetchJob?.cancel()
        prefetchJob = scope.launch(prefetcher) {
            for (i in -3..6) {
                if (!isActive) return@launch
                val s = sec + i / PER_SEC.toDouble()
                if (s < lo || s >= hi) continue
                val t = nearestTime(media, s, lo, hi, 0.2) ?: continue
                val k = "$media@${(t * 1000).roundToInt()}"
                if (synchronized(memory) { memory.containsKey(k) }) continue
                val f = File(dirOf(media.toString()), "${(t * 1000).roundToInt()}.jpg")
                runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()?.let { b -> synchronized(memory) { memory[k] = b.asImageBitmap() } }
            }
        }
    }
}
