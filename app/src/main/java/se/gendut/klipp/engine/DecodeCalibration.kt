package se.gendut.klipp.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Color check of the phone's own encode → decode round trip (the node cache is encoded and played back). The encoder
 * converts the image to YUV with a matrix (BT.709 per the file's tagging), but GPU decoding sometimes uses a different
 * one (the emulator always BT.601, some phones BT.2020 for 10-bit) – then the cached image came out more saturated/
 * different than the same image computed live. The check encodes an image with known colors the same way as the cache,
 * reads it back through the same decoding and fits a color matrix (affine, in the video chain's coded values) that takes
 * the decoded result back to the original. The result is saved per phone and format.
 */
@OptIn(UnstableApi::class)
object DecodeCalibration {
    const val W = 640
    const val H = 352
    private const val COLS = 4
    private const val ROWS = 4

    /** Patch colors (coded RGB 0–1): grays, primaries, secondaries and mixes – not at 0/1 (clipping). */
    val PATCHES: Array<FloatArray> = arrayOf(
        floatArrayOf(0.15f, 0.15f, 0.15f), floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.85f, 0.85f, 0.85f), floatArrayOf(0.7f, 0.15f, 0.15f),
        floatArrayOf(0.15f, 0.7f, 0.15f), floatArrayOf(0.15f, 0.15f, 0.7f), floatArrayOf(0.15f, 0.7f, 0.7f), floatArrayOf(0.7f, 0.15f, 0.7f),
        floatArrayOf(0.7f, 0.7f, 0.15f), floatArrayOf(0.8f, 0.55f, 0.4f), floatArrayOf(0.3f, 0.45f, 0.75f), floatArrayOf(0.45f, 0.75f, 0.3f),
        floatArrayOf(0.85f, 0.3f, 0.5f), floatArrayOf(0.35f, 0.25f, 0.2f), floatArrayOf(0.6f, 0.65f, 0.25f), floatArrayOf(0.25f, 0.6f, 0.55f),
    )

    /** The image with the patches (row by row from the top). */
    fun patchBitmap(): Bitmap {
        val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val c = PATCHES[(y * ROWS / H) * COLS + x * COLS / W]
            px[y * W + x] = (0xFF shl 24) or (Math.round(c[0] * 255) shl 16) or (Math.round(c[1] * 255) shl 8) or Math.round(c[2] * 255)
        }
        return Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888)
    }

    /** The fit: the correction (row-major 3×4, null = none needed) and the errors before/after (0–1). */
    class Fit(val matrix: FloatArray?, val before: Double, val after: Double)

    /**
     * Affine matrix taking the decoded colors [decoded] to [truth] (least squares per channel). No correction
     * when the deviation is already below 1.5 code values – or when the matrix doesn't explain it (then the error is not
     * a matrix mix-up and a correction could make it worse).
     */
    fun fit(decoded: List<FloatArray>, truth: List<FloatArray>): Fit {
        val n = minOf(decoded.size, truth.size)
        var before = 0.0
        for (i in 0 until n) for (c in 0..2) before = max(before, abs(decoded[i][c] - truth[i][c]).toDouble())
        if (n < 8) return Fit(null, before, before)
        // the normal equations (4×4) for [r g b 1]
        val ata = Array(4) { DoubleArray(4) }
        val atb = Array(3) { DoubleArray(4) }
        for (i in 0 until n) {
            val v = doubleArrayOf(decoded[i][0].toDouble(), decoded[i][1].toDouble(), decoded[i][2].toDouble(), 1.0)
            for (a in 0..3) {
                for (b in 0..3) ata[a][b] += v[a] * v[b]
                for (c in 0..2) atb[c][a] += v[a] * truth[i][c]
            }
        }
        val m = FloatArray(12)
        for (c in 0..2) {
            val x = solve4(ata.map { it.clone() }.toTypedArray(), atb[c].clone()) ?: return Fit(null, before, before)
            for (k in 0..3) m[c * 4 + k] = x[k].toFloat()
        }
        var after = 0.0
        for (i in 0 until n) for (c in 0..2) {
            val d = decoded[i]
            val v = m[c * 4] * d[0] + m[c * 4 + 1] * d[1] + m[c * 4 + 2] * d[2] + m[c * 4 + 3]
            after = max(after, abs(v - truth[i][c]).toDouble())
        }
        if (before < 1.5 / 255) return Fit(null, before, before)
        if (after > 2.5 / 255 || after > before * 0.5) return Fit(null, before, after)
        return Fit(m, before, after)
    }

    private fun solve4(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 4
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-12) return null
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            val tb = b[col]; b[col] = b[piv]; b[piv] = tb
            for (r in 0 until n) if (r != col) {
                val f = a[r][col] / a[col][col]
                for (k in col until n) a[r][k] -= f * a[col][k]
                b[r] -= f * b[col]
            }
        }
        return DoubleArray(n) { b[it] / a[it][it] }
    }

    // ------------------------------------------------------------------------------------------- results

    private const val VERSION = 1
    /** Format → correction ("id" = none needed). Missing = not checked yet (or failed: no correction). */
    private val results = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
    private val checked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val lines = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val IDENTITY = FloatArray(0)

    /** Incremented when a check has finished (playback is then rebuilt with the correction). */
    @Volatile var stamp = 0
        private set

    private fun key(mime: String, tenBit: Boolean) = "$mime|$tenBit"
    private fun prefs(c: Context) = c.getSharedPreferences("decodecal", Context.MODE_PRIVATE)
    private fun prefKey(k: String) = "v$VERSION|${android.os.Build.FINGERPRINT}|$k"

    @Volatile private var app: Context? = null
    fun init(context: Context) { app = context.applicationContext }

    /** The format Klipp encodes a file with [tenBit] in (cache, proxy): 10-bit HEVC when the phone can, otherwise H.264. */
    fun formatFor(tenBit: Boolean): Pair<String, Pair<Int, Int>?> {
        val ten = if (tenBit) VideoPipeline.tenBitProfile(MimeTypes.VIDEO_H265) else null
        return (if (ten != null) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264) to ten
    }

    /** The correction for files encoded as [mime]/[tenBit] (row-major 3×4), null = none (loads a saved check). */
    fun correction(mime: String, tenBit: Boolean): FloatArray? {
        app?.let { isChecked(it, mime, tenBit) }
        return results[key(mime, tenBit)]?.takeIf { it.size == 12 }
    }

    fun isChecked(context: Context, mime: String, tenBit: Boolean): Boolean {
        val k = key(mime, tenBit)
        if (k in checked) return true
        val s = prefs(context).getString(prefKey(k), null) ?: return false
        if (s != "id") s.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 12 }?.let { results[k] = it.toFloatArray() }
        checked += k
        lines[k] = if (s == "id") "no correction needed" else "corrected (saved)"
        return true
    }

    fun describe(): String = if (lines.isEmpty()) "" else "color check: " + lines.entries.joinToString("; ") { (k, v) ->
        "${k.substringBefore('|').substringAfter('/')}${if (k.endsWith("true")) " 10-bit" else ""} $v"
    } + "\n"

    /**
     * Check [mime]/[tenBit] with the encoder from [encoder] (same as the cache) and run [done] on the main thread
     * afterwards – also when the check fails (then no correction). Called on the main thread.
     */
    fun check(
        context: Context, mime: String, tenBit: Boolean,
        encoder: androidx.media3.transformer.Codec.EncoderFactory, done: () -> Unit,
    ) {
        val k = key(mime, tenBit)
        if (isChecked(context, mime, tenBit)) { done(); return }
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val dir = File(app.cacheDir, "decodecal").apply { mkdirs() }
        val png = File(dir, "patches.png")
        val coded = File(dir, "coded.mp4")
        val back = File(dir, "back.mp4")
        var finished = false
        fun finish(note: String, store: String?) {
            if (finished) return
            finished = true
            store?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 12 }?.let { results[k] = it.toFloatArray() }
            checked += k
            if (store != null) prefs(app).edit().putString(prefKey(k), store).apply()
            lines[k] = note
            stamp++
            se.gendut.klipp.CrashReport.note("color check ${mime.substringAfter('/')}${if (tenBit) " 10-bit" else ""}: $note")
            png.delete(); coded.delete(); back.delete()
            done()
        }
        // if something hangs: carry on without a correction after a while (the cache shouldn't wait forever)
        main.postDelayed({ finish("took too long – no correction", null) }, 30_000)
        try {
            png.outputStream().use { patchBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) { finish("could not create the test image – no correction", null); return }
        var truth: List<FloatArray>? = null
        val encodeProbe = ProbeEffect { v -> main.post { if (truth == null) truth = v } }
        val img = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(Uri.fromFile(png)).setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(400).build(),
        ).setFrameRate(30).setEffects(androidx.media3.transformer.Effects(emptyList(), listOf(encodeProbe))).build()
        val first = Transformer.Builder(app)
            .setAssetLoaderFactory(Perf.assetLoaderFactory(app))
            .setVideoMimeType(mime)
            .setEncoderFactory(encoder)
            .setVideoFrameProcessorFactory(VideoPipeline.processorFactory(tenBit))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val t = truth ?: return finish("the test image was not read – no correction", null)
                    decodeBack(app, coded, back, main, t, ::finish)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finish("encoding failed (${exportException.errorCodeName}) – no correction", null)
                }
            })
            .build()
        runCatching {
            first.start(Composition.Builder(EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO)).addItem(img).build()).build(), coded.absolutePath)
        }.onFailure { finish("could not start (${it.message}) – no correction", null) }
    }

    /** Read the encoded test file back through the same decoding as playback and fit the correction. */
    private fun decodeBack(app: Context, coded: File, back: File, main: Handler, truth: List<FloatArray>, finish: (String, String?) -> Unit) {
        var decoded: List<FloatArray>? = null
        val probe = ProbeEffect(skip = 3) { v -> main.post { if (decoded == null) decoded = v } }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(coded)))
            .setRemoveAudio(true).setEffects(androidx.media3.transformer.Effects(emptyList(), listOf(probe))).build()
        val t = Transformer.Builder(app)
            .setAssetLoaderFactory(Perf.assetLoaderFactory(app))
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setVideoFrameProcessorFactory(VideoPipeline.processorFactory(false))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val d = decoded ?: return finish("reading back produced no image – no correction", null)
                    val f = fit(d, truth)
                    val b = "%.1f".format(java.util.Locale.US, f.before * 255)
                    val a = "%.1f".format(java.util.Locale.US, f.after * 255)
                    when {
                        f.matrix != null -> finish("corrected (deviation $b → $a code values)", f.matrix.joinToString(","))
                        f.before < 1.5 / 255 -> finish("no correction needed (deviation $b code values)", "id")
                        else -> finish("deviation of $b code values that a matrix doesn't explain ($a) – no correction", "id")
                    }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finish("decoding failed (${exportException.errorCodeName}) – no correction", null)
                }
            })
            .build()
        runCatching {
            t.start(Composition.Builder(EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO)).addItem(item).build()).build(), back.absolutePath)
        }.onFailure { finish("could not read back (${it.message}) – no correction", null) }
    }

    // ------------------------------------------------------------------------------------------- GL

    private const val COPY = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
void main() {
  gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
}
"""

    private const val MATRIX = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform mat4 uM;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  gl_FragColor = vec4((uM * vec4(c.rgb, 1.0)).rgb, c.a);
}
"""

    /**
     * Reads the patches' mean colors (video chain values, at the same stage the node effects see them) from frame number
     * [skip] and leaves the image unchanged.
     */
    class ProbeEffect(private val skip: Int = 0, private val onRead: (List<FloatArray>) -> Unit) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(skip, onRead, useHdr)

        private class Program(private val skip: Int, private val onRead: (List<FloatArray>) -> Unit, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
            private val program = GlProgram(TetraShaders.VERTEX, COPY).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
            private var w = 0
            private var h = 0
            private var frames = 0
            private var done = false

            override fun configure(inputWidth: Int, inputHeight: Int): Size { w = inputWidth; h = inputHeight; return Size(inputWidth, inputHeight) }

            private fun draw(tex: Int) {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", tex, 0)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
                try {
                    if (!done && frames++ >= skip && w > 0 && h > 0) {
                        done = true
                        onRead(read(inputTexId))
                    }
                    draw(inputTexId)
                    GlUtil.checkGlError()
                } catch (e: Exception) {
                    throw VideoFrameProcessingException(e, presentationTimeUs)
                }
            }

            /** Draws the image into its own 8-bit buffer (readable everywhere) and averages a block in the middle of each patch. */
            private fun read(tex: Int): List<FloatArray> {
                val prevFbo = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
                val prevVp = IntArray(4); GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevVp, 0)
                val t = IntArray(1); val f = IntArray(1)
                GLES20.glGenTextures(1, t, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES20.glGenFramebuffers(1, f, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
                GLES20.glViewport(0, 0, w, h)
                draw(tex)
                val out = ArrayList<FloatArray>()
                val bw = maxOf(2, w / COLS / 4); val bh = maxOf(2, h / ROWS / 4)
                val buf = ByteBuffer.allocateDirect(bw * bh * 4).order(ByteOrder.nativeOrder())
                for (row in 0 until ROWS) for (col in 0 until COLS) {
                    // patch centers; GL counts rows from the bottom (the image's top row = highest y)
                    val cx = ((col + 0.5) * w / COLS).toInt()
                    val cy = h - 1 - ((row + 0.5) * h / ROWS).toInt()
                    buf.clear()
                    GLES20.glReadPixels(cx - bw / 2, cy - bh / 2, bw, bh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                    val s = DoubleArray(3)
                    for (i in 0 until bw * bh) for (c in 0..2) s[c] += (buf.get(i * 4 + c).toInt() and 0xFF) / 255.0
                    out += FloatArray(3) { (s[it] / (bw * bh)).toFloat() }
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
                GLES20.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3])
                GLES20.glDeleteFramebuffers(1, f, 0)
                GLES20.glDeleteTextures(1, t, 0)
                return out
            }

            override fun release() {
                super.release()
                try { program.delete() } catch (e: Exception) { throw VideoFrameProcessingException(e) }
            }
        }
    }

    /** The correction applied to a decoded image ([m] row-major 3×4), in the video chain's coded values. */
    class CorrectionEffect(private val m: FloatArray) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(m, useHdr)

        private class Program(m: FloatArray, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
            private val program = GlProgram(TetraShaders.VERTEX, MATRIX).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
            // GLSL: column-major; column k = the coefficient for r, g, b and the offset respectively
            private val mat = FloatArray(16).also { a ->
                for (k in 0..3) for (c in 0..2) a[k * 4 + c] = m[c * 4 + k]
                a[15] = 1f
            }

            override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

            override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
                try {
                    program.use()
                    program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                    program.setFloatsUniform("uM", mat)
                    program.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GlUtil.checkGlError()
                } catch (e: Exception) {
                    throw VideoFrameProcessingException(e, presentationTimeUs)
                }
            }

            override fun release() {
                super.release()
                try { program.delete() } catch (e: Exception) { throw VideoFrameProcessingException(e) }
            }
        }
    }
}
