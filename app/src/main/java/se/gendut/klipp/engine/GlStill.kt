package se.gendut.klipp.engine

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * A still graded by playback's own GPU programs (the grade's node effects, the whole film with its couplers, halation,
 * grain and sharpening, the look) in a GL context of its own – for the color page's preview of a grade with a film node.
 * The film's spatial effects can't be baked into a LUT: the LUT-only film was a few code values off the played picture
 * on textured images, and the picture jumped when playback's frame replaced the still.
 *
 * The node and film settings are handed over through [LiveGrades] under a clip id of their own (as playback reads its
 * settings while a slider moves), so a drag doesn't rebuild the programs; they are rebuilt only when the chain's
 * structure (the nodes, which have a film) or the look changes. Freed after a while unused.
 */
@UnstableApi
object GlStill {
    /** The clip id the still's node settings go under in [LiveGrades] (never a project clip's id). */
    private const val CLIP = Long.MIN_VALUE + 0x5717L

    /** Unused this long: the programs and images are freed (the film's take tens of MB of GPU memory). */
    private const val IDLE_MS = 30_000L

    @Volatile var broken: String? = null
        private set

    private val thread by lazy { HandlerThread("klipp-still-gl").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    @Volatile private var app: android.content.Context? = null
    fun init(context: android.content.Context) { app = context.applicationContext }

    private var display: EGLDisplay? = null
    private var context: EGLContext? = null

    private class Stage(val key: Any, val program: BaseGlShaderProgram)
    private var stages: List<Stage> = emptyList()
    private var flip: Int = 0
    private var w = 0
    private var h = 0
    /** Ping-pong images (RGBA16F) and the 8-bit output. */
    private var texA = 0; private var fboA = 0
    private var texB = 0; private var fboB = 0
    private var texOut = 0; private var fboOut = 0
    private var input = 0
    private var inW = 0; private var inH = 0; private var inF16 = false
    private val release = Runnable { freeAll() }

    /** Whether [grade] needs this path: a film node that is computed. */
    fun wants(grade: ClipGrade?): Boolean = grade?.chain?.any { !it.isNeutral && it.film != null } == true

    /**
     * [src] graded with [grade] and [look] (code values, as playback's chain sees them), null when this path doesn't
     * work (then the caller uses its own). Blocks until the image is done.
     */
    fun apply(src: Bitmap, grade: ClipGrade, look: Look): Bitmap? {
        if (broken != null) return null
        val ctx = app ?: return null
        val task = FutureTask { runCatching { render(ctx, src, grade, look) }.getOrElse { e ->
            broken = e.message ?: e.javaClass.simpleName
            se.gendut.klipp.CrashReport.note("still film off: $broken")
            freeAll()
            null
        } }
        handler.removeCallbacks(release)
        handler.post(task)
        return try { task.get(10, TimeUnit.SECONDS) } catch (e: Exception) { null }
            .also { handler.postDelayed(release, IDLE_MS) }
    }

    private fun ensureContext() {
        if (context != null) return
        val d = GlUtil.getDefaultEglDisplay()
        val c = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, d, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        GlUtil.createFocusedPlaceholderEglSurface(c, d)
        display = d; context = c
        if (!FilmGl.floatTargets()) error("no float render targets")
    }

    private fun render(ctx: android.content.Context, src0: Bitmap, grade: ClipGrade, look: Look): Bitmap? {
        val src = if (src0.config == Bitmap.Config.HARDWARE) src0.copy(Bitmap.Config.RGBA_F16, false) ?: return null else src0
        ensureContext()
        val sw = src.width; val sh = src.height
        if (sw != w || sh != h) {
            freeImages(); w = sw; h = sh; makeImages()
            // the programs stay (the film's tables and shaders); they make their images for the new size
            stages.forEach { it.program.configure(w, h) }
        }
        // the nodes' settings as playback reads them while a slider moves
        val nodes = grade.chain.filter { !it.isNeutral }
        for (n in nodes) LiveGrades.put(CLIP, n)
        val nodeFx = ClipGrade(nodes).effects(clip = CLIP)
        val lookFx = CompositionBuilder.videoEffects(look)
        val effects = nodeFx + lookFx
        // the nodes by their place (their settings come live); the look's effects by the look (new objects every call)
        val keys: List<Any> = nodeFx.map { e ->
            when (e) {
                is GradeNodeEffect -> "node" to e.live
                is FilmEffect -> "film" to e.live
                else -> e
            }
        } + lookFx.indices.map { i -> Triple("look", i, look) }
        if (keys != stages.map { it.key }) {
            stages.forEach { runCatching { it.program.release() } }
            stages = effects.mapIndexed { i, e ->
                val ge = e as? GlEffect ?: error("not a GL effect: ${e.javaClass.simpleName}")
                val p = ge.toGlShaderProgram(ctx, false) as? BaseGlShaderProgram
                    ?: error("not a frame program: ${e.javaClass.simpleName}")
                p.configure(w, h)
                Stage(keys[i], p)
            }
        }
        upload(src)
        // the bitmap's top row is at the texture's bottom: flipped into playback's orientation (OpenGL's origin at the
        // bottom, as Media3 hands frames to effects – windows and masks mean the same place)
        drawFlip(input, fboA)
        var cur = texA; var curFbo = fboA
        var other = texB; var otherFbo = fboB
        for (s in stages) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, otherFbo)
            GLES20.glViewport(0, 0, w, h)
            s.program.drawFrame(cur, 0)
            val t = cur; cur = other; other = t
            val f = curFbo; curFbo = otherFbo; otherFbo = f
        }
        // back upright into the 8-bit image that is read out
        drawFlip(cur, fboOut)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboOut)
        val px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
        GlUtil.checkGlError()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        px.rewind()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(px) }
    }

    /** [b] into the input texture (RGBA16F for a half-float still: its values below 0 and above 1 stay). */
    private fun upload(b: Bitmap) {
        val f16 = b.config == Bitmap.Config.RGBA_F16
        if (input == 0 || b.width != inW || b.height != inH || f16 != inF16) {
            if (input != 0) GLES20.glDeleteTextures(1, intArrayOf(input), 0)
            input = GlUtil.createTexture(b.width, b.height, f16)
            inW = b.width; inH = b.height; inF16 = f16
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, b)
        GlUtil.checkGlError()
    }

    private fun makeImages() {
        texA = GlUtil.createTexture(w, h, true); fboA = GlUtil.createFboForTexture(texA)
        texB = GlUtil.createTexture(w, h, true); fboB = GlUtil.createFboForTexture(texB)
        texOut = GlUtil.createTexture(w, h, false); fboOut = GlUtil.createFboForTexture(texOut)
    }

    private fun freeImages() {
        val t = intArrayOf(texA, texB, texOut).filter { it != 0 }.toIntArray()
        if (t.isNotEmpty()) GLES20.glDeleteTextures(t.size, t, 0)
        val f = intArrayOf(fboA, fboB, fboOut).filter { it != 0 }.toIntArray()
        if (f.isNotEmpty()) GLES20.glDeleteFramebuffers(f.size, f, 0)
        texA = 0; texB = 0; texOut = 0; fboA = 0; fboB = 0; fboOut = 0
    }

    private fun freeAll() {
        val c = context ?: return
        runCatching {
            stages.forEach { runCatching { it.program.release() } }
            stages = emptyList()
            freeImages()
            if (input != 0) GLES20.glDeleteTextures(1, intArrayOf(input), 0)
            if (flip != 0) GLES20.glDeleteProgram(flip)
        }
        input = 0; flip = 0; w = 0; h = 0
        runCatching { GlUtil.destroyEglContext(display, c) }
        context = null; display = null
    }

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); rewind() }

    private const val FLIP_VS = """#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() { vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5); gl_Position = vec4(aPos, 0.0, 1.0); }
"""
    private const val FLIP_FS = """#version 300 es
precision highp float;
uniform sampler2D uIn;
in vec2 vUv;
out vec4 o;
void main() { o = vec4(texture(uIn, vUv).rgb, 1.0); }
"""

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1); GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("shader: " + GLES20.glGetShaderInfoLog(sh))
            return sh
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("program: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    /** [tex] upside down into [fbo] (unclamped when the target is a float image). */
    private fun drawFlip(tex: Int, fbo: Int) {
        if (flip == 0) flip = program(FLIP_VS, FLIP_FS)
        GLES20.glUseProgram(flip)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(flip, "uIn"), 0)
        val loc = GLES20.glGetAttribLocation(flip, "aPos")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }
}
