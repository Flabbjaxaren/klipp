package se.gendut.klipp

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An OpenGL ES 3 context on the phone for running Klipp's shader programs on one frame. Images are RGB floats, row 0
 * at the top. [upright]: stored as Media3 hands frames to effects (OpenGL's origin at the bottom), so positions
 * (windows, masks) mean the same as on the CPU.
 */
class GlHarness(val w: Int, val h: Int, private val upright: Boolean = true) : AutoCloseable {
    private val display: EGLDisplay = GlUtil.getDefaultEglDisplay()
    private val context: EGLContext = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)

    init { GlUtil.createFocusedPlaceholderEglSurface(context, display) }

    private fun row(y: Int) = if (upright) h - 1 - y else y

    /** [img] as an RGBA16F texture. */
    fun upload(img: FloatArray): Int {
        val tex = GlUtil.createTexture(w, h, true)
        val b = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder())
        for (gy in 0 until h) for (x in 0 until w) {
            val i = (row(gy) * w + x) * 3
            for (c in 0..2) b.putShort(android.util.Half.toHalf(img[i + c]))
            b.putShort(android.util.Half.toHalf(1f))
        }
        b.flip()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, b)
        GlUtil.checkGlError()
        return tex
    }

    /** One frame through [p] (configured for w × h) into an 8-bit target: the result (0..1), row 0 at the top. */
    fun draw(p: BaseGlShaderProgram, input: Int): FloatArray {
        val out = GlUtil.createTexture(w, h, false)
        val fbo = GlUtil.createFboForTexture(out)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        p.drawFrame(input, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        val px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
        GlUtil.checkGlError()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(out), 0)
        val res = FloatArray(w * h * 3)
        for (gy in 0 until h) for (x in 0 until w) for (c in 0..2) res[(row(gy) * w + x) * 3 + c] = (px.get((gy * w + x) * 4 + c).toInt() and 255) / 255f
        return res
    }

    override fun close() = GlUtil.destroyEglContext(display, context)
}
