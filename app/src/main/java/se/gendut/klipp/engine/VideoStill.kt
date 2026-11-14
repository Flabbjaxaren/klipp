package se.gendut.klipp.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
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
import androidx.media3.effect.KlippPrecision
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A still for the color page's preview taken through the video chain playback uses – the file's own YUV matrix without
 * clamping ([androidx.media3.effect.KlippYuv]), floating-point textures, a proxy's headroom brought back – instead of
 * MediaMetadataRetriever's 8-bit picture, which cuts a camera's super-whites and sub-blacks. The grade preview then
 * shows what the nodes do to them while a control is dragged, as playback and the export do.
 *
 * The result is an RGBA_F16 bitmap in extended sRGB: the chain's code values as they are, below 0 and above 1 included.
 * Only for SDR files on a phone whose chain carries such values (floating point on, YUV sampling passed its check);
 * otherwise null and the caller takes MediaMetadataRetriever's still.
 */
@OptIn(UnstableApi::class)
object VideoStill {
    /** The code values the read covers (16 bits over this range). */
    private const val LO = -0.5f
    private const val HI = 1.5f
    private const val WAIT_MS = 5000L

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Can this file's still come through the video chain (and carry values beyond black and white)? Not on the main thread. */
    fun usable(context: Context, uri: Uri): Boolean =
        android.os.Build.VERSION.SDK_INT >= 29 && KlippPrecision.highPrecision && KlippPrecision.sdrYuv && Perf.isHdr(context, uri) == false

    /**
     * The frame at [sec] of [uri], fitted within [box]×[box] pixels (never enlarged). Blocks the calling thread (never the
     * main thread) until it is read; null if the chain failed or took too long.
     */
    fun grab(context: Context, uri: Uri, sec: Double, box: Int): Bitmap? {
        check(Looper.myLooper() != Looper.getMainLooper())
        val app = context.applicationContext
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        var transformer: Transformer? = null
        val out = File(app.cacheDir, "videostill").apply { mkdirs() }.let { File(it, "still.mp4") }
        val capture = Capture(box) { b -> result = b; latch.countDown() }
        // the clip starts 1 ms early (as playback's items): the frame at exactly [sec] is the first one
        val startUs = (Math.round(sec * 1e6) - 1000).coerceAtLeast(0)
        main.post {
            runCatching {
                val item = EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(uri).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionUs(startUs).setEndPositionUs(startUs + 300_000).build(),
                    ).build(),
                ).setRemoveAudio(true)
                    // the proxy's headroom back first (as playback), the read, then a small picture for the encoder
                    // (whose output is thrown away)
                    .setEffects(androidx.media3.transformer.Effects(emptyList(), listOfNotNull(
                        Headroom.decodeFor(uri), capture, androidx.media3.effect.Presentation.createForHeight(144),
                    )))
                    .build()
                val t = Transformer.Builder(app)
                    .setAssetLoaderFactory(Perf.assetLoaderFactory(app))
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setVideoFrameProcessorFactory(VideoPipeline.processorFactory(false))
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { latch.countDown() }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            se.gendut.klipp.CrashReport.note("video still: ${exportException.errorCodeName}")
                            latch.countDown()
                        }
                    })
                    .build()
                transformer = t
                t.start(Composition.Builder(EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO)).addItem(item).build()).build(), out.absolutePath)
            }.onFailure { se.gendut.klipp.CrashReport.note("video still: ${it.message}"); latch.countDown() }
        }
        val done = latch.await(WAIT_MS, TimeUnit.MILLISECONDS)
        // read (or failed, or too slow): the rest of the clip is not needed
        main.post { runCatching { transformer?.cancel() }; out.delete() }
        if (!done) se.gendut.klipp.CrashReport.note("video still: took longer than $WAIT_MS ms")
        return result
    }

    /** Reads the first frame it gets (fitted within [box]) into a floating-point bitmap; passes every frame on unchanged. */
    private class Capture(private val box: Int, private val onRead: (Bitmap?) -> Unit) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(box, onRead, useHdr)

        private class Program(private val box: Int, private val onRead: (Bitmap?) -> Unit, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
            private val copy = program(COPY)
            // built before any use() (see GlProgram build is current)
            private val encode = program(ENCODE)
            private var w = 0
            private var h = 0
            private var done = false

            private fun program(frag: String) = GlProgram(TetraShaders.VERTEX, frag).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }

            override fun configure(inputWidth: Int, inputHeight: Int): Size { w = inputWidth; h = inputHeight; return Size(inputWidth, inputHeight) }

            override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
                try {
                    if (!done && w > 0 && h > 0) {
                        done = true
                        onRead(runCatching { read(inputTexId) }.onFailure { se.gendut.klipp.CrashReport.note("video still read: ${it.message}") }.getOrNull())
                    }
                    copy.use()
                    copy.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                    copy.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GlUtil.checkGlError()
                } catch (e: Exception) {
                    throw VideoFrameProcessingException(e, presentationTimeUs)
                }
            }

            /**
             * The frame at the output size into an 8-bit buffer twice – the high and the low byte of each value's 16 bits
             * over [LO]..[HI] (readable on every GPU, unlike floating-point buffers) – then put together as half floats.
             */
            private fun read(tex: Int): Bitmap {
                val k = minOf(1.0, box.toDouble() / maxOf(w, h))
                val ow = maxOf(1, Math.round(w * k).toInt()); val oh = maxOf(1, Math.round(h * k).toInt())
                val prevFbo = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
                val prevVp = IntArray(4); GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevVp, 0)
                val t = IntArray(1); val f = IntArray(1)
                GLES20.glGenTextures(1, t, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, ow, oh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES20.glGenFramebuffers(1, f, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
                GLES20.glViewport(0, 0, ow, oh)
                // the source is sampled linearly (Media3's textures): a fair average when the frame is halved
                val bytes = Array(2) { part ->
                    encode.use()
                    encode.setSamplerTexIdUniform("uTexSampler", tex, 0)
                    encode.setFloatUniform("uPart", part.toFloat())
                    encode.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    ByteBuffer.allocateDirect(ow * oh * 4).order(ByteOrder.nativeOrder()).also { b ->
                        GLES20.glReadPixels(0, 0, ow, oh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
                    }
                }
                GlUtil.checkGlError()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
                GLES20.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3])
                GLES20.glDeleteFramebuffers(1, f, 0)
                GLES20.glDeleteTextures(1, t, 0)
                return toBitmap(bytes[0], bytes[1], ow, oh)
            }

            override fun release() {
                super.release()
                try { copy.delete(); encode.delete() } catch (e: Exception) { throw VideoFrameProcessingException(e) }
            }
        }
    }

    /** The two byte planes (GL's rows bottom up) as an RGBA_F16 bitmap in extended sRGB, top row first. */
    internal fun toBitmap(hi: ByteBuffer, lo: ByteBuffer, w: Int, h: Int): Bitmap {
        val px = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder())
        val one = android.util.Half.toHalf(1f)
        for (y in 0 until h) {
            val row = (h - 1 - y) * w * 4
            for (x in 0 until w) {
                val i = row + x * 4
                for (c in 0..2) px.putShort(android.util.Half.toHalf(value(hi.get(i + c), lo.get(i + c))))
                px.putShort(one)
            }
        }
        px.rewind()
        return Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16, true, ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)).apply { copyPixelsFromBuffer(px) }
    }

    /** A value from its high and low byte. */
    internal fun value(hi: Byte, lo: Byte): Float =
        LO + ((hi.toInt() and 255) * 256 + (lo.toInt() and 255)) / 65535f * (HI - LO)

    private const val COPY = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
void main() {
  gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
}
"""

    /** Each value's 16 bits over LO..HI: the high byte (uPart 0) or the low one (uPart 1). */
    private val ENCODE = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform float uPart;
varying vec2 vTexSamplingCoord;
void main() {
  vec3 c = texture2D(uTexSampler, vTexSamplingCoord).rgb;
  vec3 u = floor(clamp((c - ($LO)) / (${HI - LO}), 0.0, 1.0) * 65535.0 + 0.5);
  vec3 hi = floor(u / 256.0);
  vec3 v = uPart < 0.5 ? hi : u - hi * 256.0;
  gl_FragColor = vec4(v / 255.0, 1.0);
}
"""
}
