package se.gendut.klipp.engine

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import androidx.media3.inspector.MediaExtractorCompat
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Window tracking (for Power Windows): the window follows an object through the clip.
 *
 * Frames are decoded in the phone's hardware decoder (Snapdragon's, from the proxy when there is one – light 1080p with
 * dense keyframes) and only the luma plane goes on, without color conversion and (forward) without copying,
 * straight to the tracker in Rust (rust/src/track.rs: pyramidal Lucas-Kanade with RANSAC, NEON-vectorized).
 * Forward and backward from the reference frame are tracked concurrently, each on its own core with a performance hint
 * (ADPF), so the scheduler puts them on the big cores.
 */
object WindowTracker {
    /** Tracking working resolution (long side). */
    const val WORK = 480
    /** Backward is decoded in chunks that are stored downscaled and tracked in reverse. */
    private const val CHUNK_SEC = 2.0

    /**
     * A tracked position: source time, offset (fraction of the frame), scale, confidence – and the points the tracker
     * followed in the frame ([x0, y0, x1, y1, …], fraction of the frame in display orientation), for the point cloud.
     */
    class Sample(val t: Double, val dx: Float, val dy: Float, val s: Float, val q: Float, val pts: FloatArray? = null, val rot: Float = 0f)

    /**
     * Track [win] (placed in the frame at source time [ref]) over the source range [lo]–[hi] (seconds in the file).
     * [onProgress] 0..1 (from a background thread). Cancelled with the coroutine.
     */
    suspend fun track(
        context: Context, uri: Uri, ref: Double, lo: Double, hi: Double, win: Window, onProgress: (Float) -> Unit,
        /** Every tracked frame (from the tracking threads), to show progress. */
        onSample: (Sample) -> Unit = {},
        /** Only forward from [ref] (continue tracking after a correction). */
        forwardOnly: Boolean = false,
    ): WindowTrack = coroutineScope {
        val face = faces
        val file = Proxies.videoUri(uri)
        val total = (if (forwardOnly) hi - ref else hi - lo).coerceAtLeast(1e-3)
        val done = DoubleArray(2)
        fun report() = onProgress(((done[0] + done[1]) / total).toFloat().coerceIn(0f, 1f))
        val fwdPool = Executors.newSingleThreadExecutor { r -> Thread(r, "track-forward").apply { isDaemon = true } }.asCoroutineDispatcher()
        val bwdPool = Executors.newSingleThreadExecutor { r -> Thread(r, "track-backward").apply { isDaemon = true } }.asCoroutineDispatcher()
        try {
            val fwd = async(fwdPool) { forward(context, file, ref, hi, win, onSample) { t -> done[0] = t - ref; report() } }
            val bwd = async(bwdPool) { if (!forwardOnly && ref - lo > 0.02) backward(context, file, ref, lo, win, onSample) { t -> done[1] = ref - t; report() } else emptyList() }
            val f = fwd.await()
            val b = bwd.await()
            onProgress(1f)
            withContext(Dispatchers.Default) { merge(if (face) FaceSmooth.apply(b.asReversed() + f) else b.asReversed() + f) }
        } finally {
            fwdPool.close(); bwdPool.close()
        }
    }

    /** The probe (rotation and size) for the file's video track. */
    internal class Video(val ex: MediaExtractorCompat, val track: Int, val format: MediaFormat, val rotation: Int)

    /**
     * The file is opened with Media3's extractors (same as playback and export), not Android's MediaExtractor:
     * they interpret camera files' edit lists and B-frame time offsets differently, and then every tracked position
     * would get a timestamp a few frames off – the effect in the window lags behind the object.
     */
    internal fun open(context: Context, uri: Uri): Video {
        // same extractors and data source as the player (DefaultMediaSourceFactory). Not setDataSource(context, uri, …):
        // it closes the file descriptor for content:// URIs right after opening, and then nothing can be read
        val ex = MediaExtractorCompat(androidx.media3.extractor.DefaultExtractorsFactory(), androidx.media3.datasource.DefaultDataSource.Factory(context))
        // if the file can't be read, the reader is released (otherwise it leaked – and the file was held open)
        val t: Int
        val f: MediaFormat
        try {
            ex.setDataSource(uri, 0)
            t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("no video in the file")
            ex.selectTrack(t)
            f = ex.getTrackFormat(t)
        } catch (e: Throwable) {
            ex.release()
            throw e
        }
        val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) runCatching { f.getInteger(MediaFormat.KEY_ROTATION) }.getOrElse { runCatching { f.getFloat(MediaFormat.KEY_ROTATION).toInt() }.getOrDefault(0) } else 0
        return Video(ex, t, f, ((rot % 360) + 360) % 360)
    }

    /**
     * Decode [from]–[to] (source seconds) and hand each frame (luma) to [onFrame]. Hardware decoder,
     * non-realtime priority (as fast as possible), flexible YUV so the luma plane can be read directly.
     */
    internal suspend fun decode(v: Video, from: Double, to: Double, onFrame: suspend (Double, Image) -> Unit) {
        val mime = v.format.getString(MediaFormat.KEY_MIME) ?: error("unknown codec")
        val fmt = MediaFormat(v.format).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_PRIORITY, 1)
        }
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            runCatching { codec.configure(MediaFormat(fmt).apply { setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) }, null, null, 0) }
                .onFailure { codec.reset(); codec.configure(fmt, null, null, 0) }
            codec.start()
            v.ex.seekTo((from * 1e6).toLong().coerceAtLeast(0), MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val half = 0.5 / frameRate(v.format)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = v.ex.readSampleData(buf, 0)
                        val st = v.ex.sampleTime
                        if (n < 0 || st > ((to + 0.25) * 1e6).toLong()) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, st, 0)
                            v.ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val pts = info.presentationTimeUs / 1e6
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0 && pts >= from - half && pts <= to + half) {
                        val img = codec.getOutputImage(o)
                        try { if (img != null) onFrame(pts, img) } finally { img?.close() }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (eos || pts > to + half) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /** Frame rate from the format (Media3 gives it as a float, the platform sometimes as an int); 30 if unknown. */
    internal fun frameRate(f: MediaFormat): Double {
        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) return 30.0
        val r = runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
            ?: runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
        return r?.takeIf { it.isFinite() && it >= 1.0 } ?: 30.0
    }

    /** Working size (width, height) for a frame in buffer orientation. */
    private fun workSize(w: Int, h: Int): Pair<Int, Int> =
        if (w >= h) WORK to (WORK.toDouble() * h / w).roundToInt().coerceAtLeast(16)
        else (WORK.toDouble() * w / h).roundToInt().coerceAtLeast(16) to WORK

    /** The window in display orientation → the buffer (phone videos are often stored sideways). */
    private fun toBuffer(win: Window, rot: Int): FloatArray = when (rot) {
        90 -> floatArrayOf(win.cy, 1f - win.cx, win.ry, win.rx)
        180 -> floatArrayOf(1f - win.cx, 1f - win.cy, win.rx, win.ry)
        270 -> floatArrayOf(1f - win.cy, win.cx, win.ry, win.rx)
        else -> floatArrayOf(win.cx, win.cy, win.rx, win.ry)
    }

    /** Offset in the buffer → display orientation. */
    private fun toDisplay(du: Float, dv: Float, rot: Int): Pair<Float, Float> = when (rot) {
        90 -> -dv to du
        180 -> -du to -dv
        270 -> dv to -du
        else -> du to dv
    }

    /**
     * How dense the point cloud is: 1 = normal (150 points), 2 = dense, 4 = very dense (600) – more points follow small,
     * low-contrast or partly covered objects better; tracking takes about as many times longer. Saved in the settings.
     */
    @Volatile var density = 1f
    /** false: the window keeps its size (only position and turn follow) – for a head that turns, whose points close
     *  up as it looks away and made the window shrink though the head stayed the same size. */
    @Volatile var followSize = true
    val DENSITIES = floatArrayOf(1f, 2f, 4f)

    /**
     * What the window follows: points on the object (any object; the default), or a face found anew in every frame
     * (rust/src/face.rs) – which keeps up with a head that turns, nods or looks away, where points slide off it.
     * Saved in the settings.
     */
    var faces by androidx.compose.runtime.mutableStateOf(false)
    /** Face work size (long side): faces are large enough at this size, and detection costs follow the pixel count. */
    private const val FACE_WORK = 320

    /** A tracker in Rust (always closed): points, or faces ([face], for frames stored turned [rotation]). */
    private class Native(w: Int, h: Int, val face: Boolean = false, rotation: Int = 0) : AutoCloseable {
        val h = (if (face) Engine.nativeFaceNew(w, h, rotation) else Engine.nativeTrackNew(w, h).also { if (it != 0L) Engine.nativeTrackDensity(it, density) })
            .also { if (it == 0L) error("the tracker could not be started") }
        override fun close() = if (face) Engine.nativeFaceFree(h) else Engine.nativeTrackFree(h)
        fun frame(buf: ByteBuffer, off: Int, rs: Int, ps: Int, w: Int, h: Int, reset: Boolean, roi: FloatArray, shape: Int): FloatArray? =
            if (face) Engine.nativeFaceFrame(this.h, buf, off, rs, ps, w, h, reset, roi[0], roi[1], roi[2], roi[3])
            else Engine.nativeTrackFrame(this.h, buf, off, rs, ps, w, h, reset, roi[0], roi[1], roi[2], roi[3], shape)
    }

    /** Work size for this tracker kind. */
    private fun sizeFor(face: Boolean, w: Int, h: Int): Pair<Int, Int> =
        if (!face) workSize(w, h)
        else if (w >= h) FACE_WORK to (FACE_WORK.toDouble() * h / w).roundToInt().coerceAtLeast(16)
        else (FACE_WORK.toDouble() * w / h).roundToInt().coerceAtLeast(16) to FACE_WORK

    /** A face result (already in display orientation, the landmarks as points). */
    private fun faceSample(t: Double, r: FloatArray?): Sample {
        if (r == null || r.size < 5) return Sample(t, 0f, 0f, 1f, 0f)
        val pts = if (r.size >= 16) r.copyOfRange(6, 16) else null
        return Sample(t, r[0], r[1], r[2], r[4], pts, r[3])
    }

    private fun sample(t: Double, r: FloatArray?, rot: Int): Sample {
        if (r == null || r.size < 5) return Sample(t, 0f, 0f, 1f, 0f)
        val (dx, dy) = toDisplay(r[0], r[1], rot)
        val n = (r.size - 6) / 2
        val pts = if (n > 0) FloatArray(n * 2).also { o ->
            for (i in 0 until n) {
                val u = r[6 + 2 * i]; val v = r[7 + 2 * i]
                // point in the buffer → display orientation (inverse of toBuffer)
                val (x, y) = when (rot) { 90 -> (1f - v) to u; 180 -> (1f - u) to (1f - v); 270 -> v to (1f - u); else -> u to v }
                o[2 * i] = x; o[2 * i + 1] = y
            }
        } else null
        // the rotation (clockwise) is the same in the buffer and on screen: rotating the whole frame doesn't change it
        return Sample(t, dx, dy, r[2], r[4], pts, r[3])
    }

    private suspend fun forward(context: Context, uri: Uri, ref: Double, hi: Double, win: Window, onSample: (Sample) -> Unit, progress: (Double) -> Unit): List<Sample> {
        val v = open(context, uri)
        try {
            val out = ArrayList<Sample>()
            val hint = Perf.Hint(context, 6)
            val roi = toBuffer(win, v.rotation)
            val face = faces
            var tracker: Native? = null
            try {
                decode(v, ref, hi) { t, img ->
                    val p = img.planes[0]
                    val tr = tracker ?: sizeFor(face, img.width, img.height).let { (w, h) -> Native(w, h, face, v.rotation) }.also { tracker = it }
                    val first = out.isEmpty()
                    val r = hint.run { tr.frame(p.buffer, p.buffer.position(), p.rowStride, p.pixelStride, img.width, img.height, first, roi, win.shape) }
                    out += (if (face) faceSample(t, r) else sample(t, r, v.rotation)).also(onSample)
                    progress(t)
                }
            } finally {
                tracker?.close()
            }
            return out
        } finally {
            v.ex.release()
        }
    }

    /**
     * Backward: chunks of [CHUNK_SEC] are decoded forward, stored downscaled (luma only, ~130 kB per frame) and
     * tracked in reverse – the tracker sees the frames in reverse order, from the reference frame towards [lo].
     */
    private suspend fun backward(context: Context, uri: Uri, ref: Double, lo: Double, win: Window, onSample: (Sample) -> Unit, progress: (Double) -> Unit): List<Sample> {
        val v = open(context, uri)
        try {
            val out = ArrayList<Sample>()
            val hint = Perf.Hint(context, 6)
            val roi = toBuffer(win, v.rotation)
            val face = faces
            var tracker: Native? = null
            var direct: ByteBuffer? = null
            var size: Pair<Int, Int>? = null
            try {
                var end = ref
                var firstChunk = true
                while (end > lo + 1e-3) {
                    currentCoroutineContext().ensureActive()
                    val start = (end - CHUNK_SEC).coerceAtLeast(lo)
                    val frames = ArrayList<Pair<Double, ByteArray>>()
                    decode(v, start, end) { t, img ->
                        // the boundary frame already exists (previous chunk) – except the reference frame in the first chunk
                        if (t > end + 1e-3 || (!firstChunk && t >= end - 1e-3)) return@decode
                        val (w, h) = size ?: sizeFor(face, img.width, img.height).also { size = it }
                        frames += t to downscale(img, w, h)
                    }
                    if (frames.isEmpty()) break
                    val (w, h) = size!!
                    val tr = tracker ?: Native(w, h, face, v.rotation).also { tracker = it }
                    val buf = direct ?: ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder()).also { direct = it }
                    for ((t, px) in frames.asReversed()) {
                        currentCoroutineContext().ensureActive()
                        buf.clear(); buf.put(px); buf.flip()
                        val first = out.isEmpty()
                        val r = hint.run { tr.frame(buf, 0, w, 1, w, h, first, roi, win.shape) }
                        out += (if (face) faceSample(t, r) else sample(t, r, v.rotation)).also(onSample)
                        progress(t)
                    }
                    firstChunk = false
                    end = frames.first().first
                    if (start <= lo + 1e-3) break
                }
            } finally {
                tracker?.close()
            }
            return out
        } finally {
            v.ex.release()
        }
    }

    /** The luma plane downscaled to [w]×[h] (average over each cell). */
    private fun downscale(img: Image, w: Int, h: Int): ByteArray {
        val p = img.planes[0]
        val b = p.buffer
        val base = b.position()
        val rs = p.rowStride
        val ps = p.pixelStride
        val sw = img.width; val sh = img.height
        val out = ByteArray(w * h)
        val fx = sw.toFloat() / w; val fy = sh.toFloat() / h
        // at most 4×4 samples per cell (fast enough, and without aliasing)
        val nx = fx.toInt().coerceIn(1, 4); val ny = fy.toInt().coerceIn(1, 4)
        for (y in 0 until h) {
            val y0 = (y * fy).toInt()
            for (x in 0 until w) {
                val x0 = (x * fx).toInt()
                var acc = 0
                for (j in 0 until ny) {
                    val row = base + (y0 + (j * fy / ny).toInt()).coerceAtMost(sh - 1) * rs
                    for (i in 0 until nx) acc += b.get(row + (x0 + (i * fx / nx).toInt()).coerceAtMost(sw - 1) * ps).toInt() and 255
                }
                out[y * w + x] = (acc / (nx * ny)).toByte()
            }
        }
        return out
    }

    /** Sorted positions → an even time grid (the frames' own spacing). */
    /**
     * Camera path for stabilization: whole-frame motion from frame to frame (Rust: fresh corner points in a grid in
     * every frame, LK, RANSAC similarity – see track.rs `global_step`), chained from [lo]. The result is a track of the
     * frame center: offset (fraction of the frame), scale and rotation relative to the first frame.
     * Frames without an estimate (too little structure) reuse the previous motion for at most two frames, then no motion.
     */
    suspend fun cameraPath(context: Context, uri: Uri, lo: Double, hi: Double, onProgress: (Float) -> Unit): WindowTrack {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "stabilization").apply { isDaemon = true } }.asCoroutineDispatcher()
        try {
            return withContext(pool) {
                val v = open(context, Proxies.videoUri(uri))
                try {
                    val out = ArrayList<Sample>()
                    val hint = Perf.Hint(context, 6)
                    var tracker: Native? = null
                    var size: Pair<Int, Int>? = null
                    // C = chained similarity first frame → current (working pixels in the buffer)
                    var ca = 1.0; var cb = 0.0; var ctx = 0.0; var cty = 0.0
                    var lastM: FloatArray? = null
                    var misses = 0
                    try {
                        decode(v, lo, hi) { t, img ->
                            val p = img.planes[0]
                            val (w, h) = size ?: workSize(img.width, img.height).also { size = it }
                            val tr = tracker ?: Native(w, h).also { tracker = it }
                            val r = hint.run { Engine.nativeMotionFrame(tr.h, p.buffer, p.buffer.position(), p.rowStride, p.pixelStride, img.width, img.height) }
                            var q = 1f
                            if (out.isNotEmpty()) {
                                val m = r?.takeIf { it.size >= 6 }?.also { lastM = it; misses = 0 } ?: run { q = 0f; if (++misses <= 2) lastM else null }
                                if (m != null) {
                                    val ma = m[0].toDouble(); val mb = m[1].toDouble()
                                    val na = ma * ca - mb * cb; val nb = ma * cb + mb * ca
                                    val ntx = ma * ctx - mb * cty + m[2]; val nty = mb * ctx + ma * cty + m[3]
                                    ca = na; cb = nb; ctx = ntx; cty = nty
                                }
                            }
                            // frame center: where the content there has moved
                            val cx = w / 2.0; val cy = h / 2.0
                            val mx = ca * cx - cb * cy + ctx - cx
                            val my = cb * cx + ca * cy + cty - cy
                            val (dx, dy) = toDisplay((mx / w).toFloat(), (my / h).toFloat(), v.rotation)
                            out += Sample(t, dx, dy, kotlin.math.hypot(ca, cb).toFloat(), q, null, kotlin.math.atan2(cb, ca).toFloat())
                            onProgress(((t - lo) / (hi - lo).coerceAtLeast(1e-3)).toFloat().coerceIn(0f, 1f))
                        }
                    } finally {
                        tracker?.close()
                    }
                    merge(out)
                } finally {
                    v.ex.release()
                }
            }
        } finally {
            pool.close()
        }
    }

    fun merge(samples: List<Sample>): WindowTrack {
        val s = samples.sortedBy { it.t }.fold(ArrayList<Sample>()) { acc, x -> if (acc.isEmpty() || x.t - acc.last().t > 1e-4) acc += x; acc }
        require(s.isNotEmpty()) { "no frames to track" }
        if (s.size == 1) return WindowTrack(s[0].t, 1.0 / 30, floatArrayOf(s[0].dx), floatArrayOf(s[0].dy), floatArrayOf(if (followSize) s[0].s else 1f), floatArrayOf(s[0].q), r = floatArrayOf(s[0].rot))
        val gaps = s.zipWithNext { a, b -> b.t - a.t }.sorted()
        val dt = gaps[gaps.size / 2].coerceAtLeast(1e-3)
        val n = ((s.last().t - s.first().t) / dt).roundToInt() + 1
        val dx = FloatArray(n); val dy = FloatArray(n); val sc = FloatArray(n); val q = FloatArray(n); val rot = FloatArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = s.first().t + i * dt
            while (j < s.size - 2 && s[j + 1].t < t) j++
            val a = s[j]; val b = s[(j + 1).coerceAtMost(s.size - 1)]
            val k = if (b.t - a.t > 1e-9) ((t - a.t) / (b.t - a.t)).toFloat().coerceIn(0f, 1f) else 0f
            dx[i] = a.dx + (b.dx - a.dx) * k; dy[i] = a.dy + (b.dy - a.dy) * k
            sc[i] = if (followSize) a.s + (b.s - a.s) * k else 1f; q[i] = if (abs(k) < 0.5f) a.q else b.q
            rot[i] = a.rot + (b.rot - a.rot) * k
        }
        return WindowTrack(s.first().t, dt, dx, dy, sc, q, r = rot)
    }
}

/**
 * The point cloud from a track: the points followed in each frame (shown as plus signs). Kept in memory
 * only – not saved with the project (not needed for the grade, only to see how tracking went).
 */
class TrackCloud(samples: List<WindowTracker.Sample>) {
    private val s = samples.filter { it.pts != null }.sortedBy { it.t }
    private val times = DoubleArray(s.size) { s[it].t }
    val isEmpty get() = s.isEmpty()

    /** The frame nearest source time [t] (within one frame), or null. */
    fun at(t: Double): WindowTracker.Sample? {
        if (s.isEmpty()) return null
        var i = java.util.Arrays.binarySearch(times, t)
        if (i < 0) {
            val ins = -i - 1
            i = when {
                ins <= 0 -> 0
                ins >= times.size -> times.size - 1
                t - times[ins - 1] <= times[ins] - t -> ins - 1
                else -> ins
            }
        }
        val gap = if (times.size > 1) (times.last() - times.first()) / (times.size - 1) else 0.05
        return s[i].takeIf { abs(it.t - t) <= gap.coerceAtLeast(0.02) }
    }
}

/**
 * Face samples before they become a track (pure, testable): frames where the face wasn't found are left out (the
 * track interpolates over them), and the detector's frame-to-frame jitter is smoothed – position and turn lightly,
 * size more (a face box's size wobbles a few per cent from frame to frame).
 */
object FaceSmooth {
    const val SIGMA_POS = 1.5f
    const val SIGMA_SIZE = 4f

    fun apply(samples: List<WindowTracker.Sample>): List<WindowTracker.Sample> {
        val s = samples.sortedBy { it.t }.filter { it.q > 0f }
        if (s.size < 3) return if (s.isEmpty()) samples else s
        val dx = gauss(FloatArray(s.size) { s[it].dx }, SIGMA_POS)
        val dy = gauss(FloatArray(s.size) { s[it].dy }, SIGMA_POS)
        val sc = gauss(FloatArray(s.size) { s[it].s }, SIGMA_SIZE)
        val r = gauss(FloatArray(s.size) { s[it].rot }, SIGMA_POS)
        return s.mapIndexed { i, x -> WindowTracker.Sample(x.t, dx[i], dy[i], sc[i], x.q, x.pts, r[i]) }
    }

    /** Gaussian over the sample index, renormalized at the ends. */
    fun gauss(v: FloatArray, sigma: Float): FloatArray {
        val k = (sigma * 3).toInt().coerceAtLeast(1)
        val w = FloatArray(2 * k + 1) { val d = (it - k).toFloat(); kotlin.math.exp(-d * d / (2 * sigma * sigma)) }
        return FloatArray(v.size) { i ->
            var a = 0f; var n = 0f
            for (j in -k..k) { val x = i + j; if (x in v.indices) { a += v[x] * w[j + k]; n += w[j + k] } }
            a / n
        }
    }
}
