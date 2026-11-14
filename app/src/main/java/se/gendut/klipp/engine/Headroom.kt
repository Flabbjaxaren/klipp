package se.gendut.klipp.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.KlippPrecision

/**
 * Files Klipp makes for playback (proxies, the node cache, reversed clips) keep a camera's super-whites and sub-blacks:
 * once SDR video is sampled unclamped ([SdrYuvCheck]) the original has values from [LO] to [HI], and a proxy encoded
 * as is would cut them off – the preview would then show clipped highlights that a grade pulls down in the export.
 * Such a file stores (v − LO) / (HI − LO) and is named *[SUFFIX]; whatever reads it brings the values back
 * ([decodeEffect], [DynamicDecode], [fix] for still frames).
 */
@OptIn(UnstableApi::class)
object Headroom {
    /** The range kept: limited-range video's whole code range (8-bit codes 1…254 ≈ −0.07…1.09). */
    const val LO = -0.07f
    const val HI = 1.09f
    private const val S = HI - LO

    /** The ending of a file stored with headroom. */
    const val SUFFIX = ".h.mp4"

    /** New files are made with headroom: the decoding keeps values outside 0..1 (and the chain is floating point). */
    val active: Boolean get() = KlippPrecision.sdrYuv && KlippPrecision.highPrecision

    fun isHeadroom(uri: Uri?): Boolean = uri?.path?.endsWith(SUFFIX) == true

    /** The last effect when making such a file. */
    fun encodeEffect(): Effect = DecodeCalibration.CorrectionEffect(floatArrayOf(1 / S, 0f, 0f, -LO / S, 0f, 1 / S, 0f, -LO / S, 0f, 0f, 1 / S, -LO / S))

    /** The first effect when playing such a file. */
    fun decodeEffect(): Effect = DecodeCalibration.CorrectionEffect(floatArrayOf(S, 0f, 0f, LO, 0f, S, 0f, LO, 0f, 0f, S, LO))

    /** [decodeEffect] if [uri] is such a file, otherwise null. */
    fun decodeFor(uri: Uri?): Effect? = if (isHeadroom(uri)) decodeEffect() else null

    /** A still frame read from [uri] (MediaMetadataRetriever, own decoding) brought back; other files unchanged. */
    fun fix(uri: Uri?, bmp: Bitmap?): Bitmap? {
        if (bmp == null || !isHeadroom(uri)) return bmp
        val src = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val t = LO * 255f
        val m = ColorMatrix(floatArrayOf(S, 0f, 0f, 0f, t, 0f, S, 0f, 0f, t, 0f, 0f, S, 0f, t, 0f, 0f, 0f, 1f, 0f))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        if (src !== bmp) src.recycle()
        return out
    }

    private const val SHADER = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform float uOn;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  gl_FragColor = vec4(uOn > 0.5 ? c.rgb * $S + vec3($LO) : c.rgb, c.a);
}
"""

    /**
     * [decodeEffect] for a player whose items differ (a layer's player: one effect list for all its segments) – on for
     * the frames where [on] says the picture comes from such a file.
     */
    class DynamicDecode(private val on: (Long) -> Boolean) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(on, useHdr)

        private class Program(private val on: (Long) -> Boolean, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
            private val program = GlProgram(TetraShaders.VERTEX, SHADER).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }

            override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

            override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
                try {
                    program.use()
                    program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                    program.setFloatUniform("uOn", if (on(presentationTimeUs)) 1f else 0f)
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
