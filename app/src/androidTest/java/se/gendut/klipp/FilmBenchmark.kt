package se.gendut.klipp

import android.opengl.GLES20
import androidx.media3.common.util.GlUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.FilmGl
import se.gendut.klipp.engine.Spektra

/**
 * The film's GPU cost at the preview size (1920×1080) on the phone, as playback runs it: frames back to back, one
 * glFinish at the end (stage-by-stage glFinish lets the GPU clock down and inflates everything). Variants switch one
 * part off at a time to show what each costs. Logged, nothing asserted.
 */
@RunWith(AndroidJUnit4::class)
class FilmBenchmark {
    private val w = 1920
    private val h = 1080
    private lateinit var gl: GlHarness

    @Before fun gl() { Spektra.init(InstrumentationRegistry.getInstrumentation().targetContext); gl = GlHarness(w, h) }
    @After fun release() { FilmGl.profile = null; gl.close() }

    private val image = FloatArray(w * h * 3) { i -> val p = i / 3; val x = p % w; val y = p / w; ((x * 7 + y * 13 + (i % 3) * 50) % 256) / 255f }

    private fun msPerFrame(f: Film, frames: Int = 60): Double {
        val p = FilmGl.Program(f, null, false).also { it.configure(w, h) }
        val input = gl.upload(image)
        val out = GlUtil.createTexture(w, h, true)
        val fbo = GlUtil.createFboForTexture(out)
        fun frame(i: Int) { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo); GLES20.glViewport(0, 0, w, h); p.drawFrame(input, i * 40_000L) }
        repeat(30) { frame(it) }
        GLES20.glFinish()
        val t0 = System.nanoTime()
        repeat(frames) { frame(30 + it) }
        GLES20.glFinish()
        val ms = (System.nanoTime() - t0) / 1e6 / frames
        p.release()
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0); GLES20.glDeleteTextures(2, intArrayOf(out, input), 0)
        return ms
    }

    @Test fun variants() {
        val base = Film()
        val cases = listOf(
            "default" to base,
            "default again" to base,
            "no halation" to base.copy(halation = 0f),
            "no scatter" to base.copy(scatter = 0f),
            "no halation, no scatter" to base.copy(halation = 0f, scatter = 0f),
            "no couplers" to base.copy(couplers = 0f),
            "no grain" to base.copy(grain = false),
            "no sharpen" to base.copy(sharpen = 0f),
            "effects only" to base.copy(effectsOnly = true),
        )
        for ((name, f) in cases) android.util.Log.i("FilmBenchmark", "$name: %.2f ms/frame".format(msPerFrame(f)))
        for ((name, f) in listOf("default" to base, "effects only" to base.copy(effectsOnly = true))) stages(name, f)
    }

    /** Each stage's GPU time (timer queries), averaged over 30 frames after a warm-up. */
    private fun stages(name: String, f: Film) {
        val p = FilmGl.Program(f, null, false).also { it.configure(w, h) }
        val input = gl.upload(image)
        val out = GlUtil.createTexture(w, h, true)
        val fbo = GlUtil.createFboForTexture(out)
        fun frame(i: Int) { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo); GLES20.glViewport(0, 0, w, h); p.drawFrame(input, i * 40_000L) }
        repeat(30) { frame(it) }
        val sums = LinkedHashMap<String, Double>()
        FilmGl.profile = { stage, ms -> sums[stage] = (sums[stage] ?: 0.0) + ms }
        repeat(30) { frame(30 + it) }
        FilmGl.profile = null
        android.util.Log.i("FilmBenchmark", "$name stages (GPU ms): " + sums.entries.joinToString(", ") { "${it.key} %.2f".format(it.value / 30) } + " – sum %.2f".format(sums.values.sum() / 30))
        p.release()
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0); GLES20.glDeleteTextures(2, intArrayOf(out, input), 0)
    }
}
