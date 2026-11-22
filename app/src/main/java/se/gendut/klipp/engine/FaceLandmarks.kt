package se.gendut.klipp.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A frame's luma plane as the face follower sees it: upright (display orientation), in the coordinates of a [w]×[h]
 * analysed frame – the source pixels are read where a crop needs them, at the source's own resolution, so a small face
 * keeps all its detail. Grey is enough: MediaPipe follows grey faces as well as colour ones (tools/face-eval).
 */
class FacePicture(
    private val buf: ByteBuffer, private val off: Int, private val rowStride: Int, private val pixelStride: Int,
    /** The plane in buffer orientation. */
    private val bw: Int, private val bh: Int,
    /** Degrees clockwise from the buffer to display orientation (0, 90, 180, 270). */
    private val rot: Int,
    val w: Int, val h: Int,
) {
    /** Display size of the source and its scale from the analysed frame. */
    private val dw = if (rot == 90 || rot == 270) bh else bw
    private val dh = if (rot == 90 || rot == 270) bw else bh
    private val sx = dw.toDouble() / w
    private val sy = dh.toDouble() / h

    private fun luma(X: Int, Y: Int): Int {
        // display pixel (X, Y) of the source ← buffer pixel (u, v)
        val u: Int; val v: Int
        when (rot) {
            90 -> { u = Y; v = bh - 1 - X }
            180 -> { u = bw - 1 - X; v = bh - 1 - Y }
            270 -> { u = bw - 1 - Y; v = X }
            else -> { u = X; v = Y }
        }
        return buf.get(off + v * rowStride + u * pixelStride).toInt() and 255
    }

    /**
     * A [side]×[side] square around ([cx], [cy]) (analysed pixels) into [out] ([px]×[px], ARGB grey); mid grey outside
     * the frame. Each output pixel averages up to 3×3 source pixels when the crop is shrunk.
     */
    fun crop(cx: Double, cy: Double, side: Double, px: Int, out: IntArray) {
        val step = side / px
        val stepX = step * sx; val stepY = step * sy
        val nx = floor(stepX).toInt().coerceIn(1, 3); val ny = floor(stepY).toInt().coerceIn(1, 3)
        val x0 = (cx - side / 2) * sx; val y0 = (cy - side / 2) * sy
        for (j in 0 until px) {
            for (i in 0 until px) {
                var acc = 0; var n = 0
                for (b in 0 until ny) {
                    val Y = floor(y0 + (j + (b + 0.5) / ny) * stepY).toInt()
                    if (Y < 0 || Y >= dh) continue
                    for (a in 0 until nx) {
                        val X = floor(x0 + (i + (a + 0.5) / nx) * stepX).toInt()
                        if (X < 0 || X >= dw) continue
                        acc += luma(X, Y); n++
                    }
                }
                val g = if (n > 0) acc / n else 128
                out[j * px + i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
    }

    companion object {
        /** Analysed size (display orientation) for a source of [bw]×[bh] turned [rot]: long side at most [FaceLandmarks.WORK]. */
        fun workSize(bw: Int, bh: Int, rot: Int): Pair<Int, Int> {
            val dw = if (rot == 90 || rot == 270) bh else bw
            val dh = if (rot == 90 || rot == 270) bw else bh
            val k = min(1.0, FaceLandmarks.WORK.toDouble() / max(dw, dh))
            return max(16, (dw * k).roundToInt()) to max(16, (dh * k).roundToInt())
        }
    }
}

/**
 * Face following with MediaPipe's Face Landmarker (Apache-2.0; the model, face_landmarker.task, is bundled in the
 * assets): every frame a crop around where the chosen face is expected ([FaceFollow.Follower.request]) is given to
 * the landmarker in VIDEO mode – it finds the face once and then follows its 478 landmarks from frame to frame – and
 * the crop moves with the face, so a small, far face is as large in the crop as a near one (the landmarker's own
 * detector only finds faces that fill a good part of its picture). A wide search – a second landmarker, IMAGE mode, on
 * tiles over the region – runs at the start and every few frames while the face is missing.
 * The same as the prototype tools/face-eval/mp_source.py. One instance per tracking thread; not thread-safe.
 */
class FaceLandmarks(context: Context, private val w: Int, private val h: Int) : AutoCloseable {
    companion object {
        private const val TAG = "FaceLandmarks"
        const val MODEL = "face_landmarker.task"
        /** Long side of the analysed frame (the follower's pixel units; crops read the source at its own resolution). */
        const val WORK = 1280
        const val CROP_PX = 256
        /** In the crop: the chosen face and possibly a neighbour (matched to its own track). */
        const val NUM_FACES = 2
        const val MIN_DETECTION = 0.5f
        const val MIN_PRESENCE = 0.5f
        const val MIN_TRACKING = 0.5f
        const val TILE_OVERLAP = 0.5

        @Volatile private var model: ByteBuffer? = null
        @Volatile private var works: Boolean? = null

        private fun model(context: Context): ByteBuffer = model ?: synchronized(this) {
            model ?: context.assets.open(MODEL).use { it.readBytes() }.let { b ->
                ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder()).put(b).also { it.flip() }
            }.also { model = it }
        }

        /**
         * Whether MediaPipe runs on this phone (its native library loads and the model opens) – tried once; when it
         * doesn't, face following falls back to YuNet in the engine (rust/src/face.rs).
         */
        fun available(context: Context): Boolean = works ?: synchronized(this) {
            works ?: runCatching { FaceLandmarks(context, 64, 64).close(); true }
                .getOrElse { e -> Log.w(TAG, "MediaPipe face landmarker unavailable, using YuNet", e); false }
                .also { works = it }
        }

        fun tiles(rw: Double, rh: Double): DoubleArray {
            val big = max(rw, rh)
            val small = max(min(rw, rh) / 3.0, 64.0)
            return if (small >= big * 0.6) doubleArrayOf(big) else doubleArrayOf(big, small)
        }

        fun tileCentres(x0: Double, y0: Double, rw: Double, rh: Double, side: Double): List<DoubleArray> {
            val step = side * (1 - TILE_OVERLAP)
            val nx = if (rw > side) max(1, ceil((rw - side) / step).toInt() + 1) else 1
            val ny = if (rh > side) max(1, ceil((rh - side) / step).toInt() + 1) else 1
            val out = ArrayList<DoubleArray>()
            for (j in 0 until ny) for (i in 0 until nx) {
                val tx = if (nx == 1) x0 + rw / 2 else x0 + side / 2 + (rw - side) * i / (nx - 1)
                val ty = if (ny == 1) y0 + rh / 2 else y0 + side / 2 + (rh - side) * j / (ny - 1)
                out += doubleArrayOf(tx, ty)
            }
            return out
        }
    }

    private fun options(context: Context, mode: RunningMode, faces: Int) = FaceLandmarker.FaceLandmarkerOptions.builder()
        // one copy of the model for all; each landmarker gets its own view of it (position and limit)
        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model(context).duplicate()).setDelegate(Delegate.CPU).build())
        .setRunningMode(mode).setNumFaces(faces)
        .setMinFaceDetectionConfidence(MIN_DETECTION).setMinFacePresenceConfidence(MIN_PRESENCE).setMinTrackingConfidence(MIN_TRACKING)
        .setOutputFaceBlendshapes(false).setOutputFacialTransformationMatrixes(false)
        .build()

    private val video = FaceLandmarker.createFromOptions(context, options(context, RunningMode.VIDEO, NUM_FACES))
    private val image = try {
        FaceLandmarker.createFromOptions(context, options(context, RunningMode.IMAGE, 4))
    } catch (e: Throwable) {
        video.close(); throw e
    }
    private val pixels = IntArray(CROP_PX * CROP_PX)
    private val bitmap = Bitmap.createBitmap(CROP_PX, CROP_PX, Bitmap.Config.ARGB_8888)
    private var ts = 0L
    private val lx = FloatArray(478); private val ly = FloatArray(478); private val lz = FloatArray(478)

    override fun close() {
        runCatching { video.close() }
        runCatching { image.close() }
        bitmap.recycle()
    }

    private fun picture(pic: FacePicture, cx: Double, cy: Double, side: Double) =
        BitmapImageBuilder(bitmap.also { pic.crop(cx, cy, side, CROP_PX, pixels); it.setPixels(pixels, 0, CROP_PX, 0, 0, CROP_PX, CROP_PX) }).build()

    /** Landmarks in crop coordinates → observations in the frame. */
    private fun faces(r: FaceLandmarkerResult, cx: Double, cy: Double, side: Double): List<FaceFollow.Obs> =
        r.faceLandmarks().mapNotNull { face ->
            if (face.size < 468) return@mapNotNull null
            val n = min(face.size, lx.size)
            for (i in 0 until n) {
                val p = face[i]
                lx[i] = ((cx + (p.x() - 0.5) * side) / w).toFloat()
                ly[i] = ((cy + (p.y() - 0.5) * side) / h).toFloat()
                lz[i] = (p.z() * side / w).toFloat()
            }
            FaceFollow.observe(lx.copyOf(n), ly.copyOf(n), lz.copyOf(n), w, h)
        }

    /** The faces for [FaceFollow.Follower.step] in this frame, looking where [req] says. */
    fun look(pic: FacePicture, req: FaceFollow.Request): List<FaceFollow.Obs> {
        val r = video.detectForVideo(picture(pic, req.cx, req.cy, req.side), ts)
        ts += 33
        val found = faces(r, req.cx, req.cy, req.side).toMutableList()
        req.search?.let { s ->
            for (o in search(pic, s[0], s[1], s[2]))
                if (found.all { f -> hypot(o.cx - f.cx, o.cy - f.cy) > 0.5 * max(o.size, f.size) }) found += o
        }
        return found
    }

    /** Tiles over the square region ([cx] ± [r], [cy] ± [r]), clipped to the frame, at two tile sizes. */
    private fun search(pic: FacePicture, cx: Double, cy: Double, r: Double): List<FaceFollow.Obs> {
        val found = ArrayList<FaceFollow.Obs>()
        val x0 = max(0.0, cx - r); val x1 = min(w.toDouble(), cx + r)
        val y0 = max(0.0, cy - r); val y1 = min(h.toDouble(), cy + r)
        val rw = x1 - x0; val rh = y1 - y0
        if (rw <= 1 || rh <= 1) return found
        for (side in tiles(rw, rh)) for (c in tileCentres(x0, y0, rw, rh, side)) {
            val res = image.detect(picture(pic, c[0], c[1], side))
            for (o in faces(res, c[0], c[1], side))
                if (found.all { f -> hypot(o.cx - f.cx, o.cy - f.cy) > 0.5 * max(o.size, f.size) }) found += o
        }
        return found
    }
}

/** The face follower for one tracking direction: MediaPipe for the faces, [FaceFollow.Follower] for which one. */
class MpFaceTracker(context: Context, val w: Int, val h: Int, win: Window) : AutoCloseable {
    private val lm = FaceLandmarks(context, w, h)
    private val follower = FaceFollow.Follower(w, h, win.cx.toDouble() * w, win.cy.toDouble() * h, max(win.rx * w, win.ry * h).toDouble())

    /** The chosen face in this frame, or null. */
    fun frame(pic: FacePicture): FaceFollow.Obs? = follower.step(lm.look(pic, follower.request()))

    override fun close() = lm.close()
}
