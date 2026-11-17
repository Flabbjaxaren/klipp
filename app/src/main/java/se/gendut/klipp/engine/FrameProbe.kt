package se.gendut.klipp.engine

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test builds only (TEST_HOOKS): the player's output frame as the chain hands it on – after every effect, before the
 * screen's scaling (Media3's last pass to the surface). The last frame drawn while armed is kept as read from the GPU
 * (floats from a half-float target, bytes from an 8-bit one), with its size and time; [save] writes it to a file.
 */
object FrameProbe {
    @Volatile var armed = false
    @Volatile private var last: Frame? = null
    @Volatile var frames = 0; private set

    class Frame(val w: Int, val h: Int, val ptsUs: Long, val float: Boolean, val data: ByteBuffer)

    fun arm() { last = null; frames = 0; armed = true }

    /** [dir]/probe_[tag].raw: "KPRB", w, h, 1 = floats (RGBA f32) / 0 = bytes (RGBA8), pts µs, then the rows as read (row 0 = GL's bottom). */
    fun save(dir: File, tag: String): Frame? {
        val f = last ?: return null
        val out = File(dir, "probe_$tag.raw")
        out.outputStream().buffered().use { o ->
            val head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            head.put("KPRB".toByteArray()).putInt(f.w).putInt(f.h).putInt(if (f.float) 1 else 0).putLong(f.ptsUs)
            o.write(head.array())
            val b = f.data.duplicate().apply { rewind() }
            val chunk = ByteArray(1 shl 16)
            while (b.hasRemaining()) { val n = minOf(chunk.size, b.remaining()); b.get(chunk, 0, n); o.write(chunk, 0, n) }
        }
        return f
    }

    class Effect : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)
    }

    private class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program = GlProgram(TetraShaders.VERTEX, COPY).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
        private var w = 0; private var h = 0

        override fun configure(inputWidth: Int, inputHeight: Int): Size { w = inputWidth; h = inputHeight; return Size(inputWidth, inputHeight) }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
                if (armed) read(presentationTimeUs)
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        /** The frame just drawn (the output framebuffer is bound): floats when the target is a float one, else bytes. */
        private fun read(ptsUs: Long) {
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) Unit
            val fb = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_FLOAT, fb)
            if (GLES20.glGetError() == GLES20.GL_NO_ERROR) { last = Frame(w, h, ptsUs, true, fb); frames++; return }
            val bb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
            GlUtil.checkGlError()
            last = Frame(w, h, ptsUs, false, bb); frames++
        }

        override fun release() {
            super.release()
            try { program.delete() } catch (e: Exception) { throw VideoFrameProcessingException(e) }
        }
    }

    private const val COPY = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
void main() { gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord); }
"""
}
