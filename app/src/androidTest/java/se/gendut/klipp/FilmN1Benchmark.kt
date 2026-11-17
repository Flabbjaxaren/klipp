package se.gendut.klipp

import android.content.Context
import android.opengl.GLES20
import android.os.PowerManager
import androidx.media3.common.util.GlUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.FilmGl
import se.gendut.klipp.engine.Spektra
import kotlin.math.sqrt

/**
 * N1's cost: the film at the preview size (1920×1080) with the reference size (N1, the 4K original's 2160: scale 0.5)
 * against without (ref 0: scale 1, the film as before N1) – same program, image and parameters. Blocks of frames back to
 * back (one glFinish per block), the two alternating after a warm-up; then each stage's GPU time (timer queries). Logged
 * with the thermal status; nothing asserted. Run with -e n1bench true.
 */
@RunWith(AndroidJUnit4::class)
class FilmN1Benchmark {
    private val w = 1920
    private val h = 1080
    private lateinit var gl: GlHarness
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        assumeTrue("run with -e n1bench true", InstrumentationRegistry.getArguments().getString("n1bench") == "true")
        Spektra.init(ctx); gl = GlHarness(w, h)
    }
    @After fun release() { FilmGl.profile = null; if (this::gl.isInitialized) gl.close() }

    /** Natural-ish detail: gradients, edges and a fine texture (the grain and the mask see real structure). */
    private val image = FloatArray(w * h * 3) { i ->
        val p = i / 3; val x = p % w; val y = p / w; val c = i % 3
        val base = 0.15f + 0.6f * x / w + 0.1f * c
        val tex = if ((x / 3 + y / 3) % 2 == 0) 0.04f else -0.04f
        (if (x in 600..900 && y in 300..700) 0.85f else base + tex).coerceIn(0f, 1f)
    }

    private fun thermal(): String {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val head = runCatching { pm.getThermalHeadroom(10) }.getOrNull()
        return "thermal status ${pm.currentThermalStatus}, headroom %.2f".format(head ?: Float.NaN)
    }

    private class Run(val p: FilmGl.Program, val input: Int, val out: Int, val fbo: Int)

    private fun open(f: Film, ref: Int): Run {
        val p = FilmGl.Program(f, null, false, false, ref).also { it.configure(w, h) }
        val input = gl.upload(image)
        val out = GlUtil.createTexture(w, h, true)
        return Run(p, input, out, GlUtil.createFboForTexture(out))
    }

    private fun close(r: Run) {
        r.p.release(); GLES20.glDeleteFramebuffers(1, intArrayOf(r.fbo), 0); GLES20.glDeleteTextures(2, intArrayOf(r.out, r.input), 0)
    }

    private var t = 0
    private fun block(r: Run, frames: Int): Double {
        fun frame() { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, r.fbo); GLES20.glViewport(0, 0, w, h); r.p.drawFrame(r.input, (t++) * 40_000L) }
        GLES20.glFinish()
        val t0 = System.nanoTime()
        repeat(frames) { frame() }
        GLES20.glFinish()
        return (System.nanoTime() - t0) / 1e6 / frames
    }

    private fun stats(v: List<Double>): String {
        val m = v.average(); val sd = sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
        val s = v.sorted()
        return "mean %.2f ± %.2f ms (median %.2f, min %.2f, max %.2f, n=%d blocks)".format(m, sd, s[s.size / 2], s.first(), s.last(), v.size)
    }

    /** The film's GPU time per frame from timer queries (10 frames): not stretched by a frozen process. */
    private fun gpuMs(r: Run): Double {
        var sum = 0.0
        FilmGl.profile = { _, ms -> sum += ms }
        repeat(10) { block(r, 1) }
        FilmGl.profile = null
        return sum / 10
    }

    private fun stages(r: Run): Map<String, Double> {
        repeat(10) { block(r, 1) }
        val sums = LinkedHashMap<String, Double>()
        FilmGl.profile = { stage, ms -> sums[stage] = (sums[stage] ?: 0.0) + ms }
        repeat(30) { block(r, 1) }
        FilmGl.profile = null
        return sums.mapValues { it.value / 30 }
    }

    @Test fun scaledAgainstUnscaled() {
        val cases = listOf(
            "default (grain, glare)" to Film(),
            "strong grain" to Film(grainAmount = 2f, grainSize = 1.5f),
            "grain and glare off (mask only)" to Film(grain = false, glare = false),
        )
        for ((name, f) in cases) {
            android.util.Log.i("FilmN1Benchmark", "== $name – ${thermal()}")
            val before = open(f, 0); val after = open(f, 2 * h)
            // warm-up: both, until the clocks settle
            repeat(3) { block(before, 30); block(after, 30) }
            val a = ArrayList<Double>(); val b = ArrayList<Double>()
            val ga = ArrayList<Double>(); val gb = ArrayList<Double>()
            fun pair(first: Run, fl: ArrayList<Double>, fg: ArrayList<Double>, second: Run, sl: ArrayList<Double>, sg: ArrayList<Double>) {
                fl += block(first, 40); fg += gpuMs(first); sl += block(second, 40); sg += gpuMs(second)
            }
            repeat(10) { k -> if (k % 2 == 0) pair(before, a, ga, after, b, gb) else pair(after, b, gb, before, a, ga) }
            // (the vivo's freezer can stop the process mid-block: wall-clock blocks over twice the median are counted apart)
            fun clean(v: List<Double>): Pair<List<Double>, Int> { val m = v.sorted()[v.size / 2]; val k = v.filter { it <= 2 * m }; return k to (v.size - k.size) }
            val (ca, xa) = clean(a); val (cb, xb) = clean(b)
            android.util.Log.i("FilmN1Benchmark", "$name: GPU time per frame (timer queries, 10 frames × 10 blocks) before N1 (ref 0) ${stats(ga)}")
            android.util.Log.i("FilmN1Benchmark", "$name: GPU time per frame                                     N1 (ref 2160)    ${stats(gb)}")
            android.util.Log.i("FilmN1Benchmark", "$name: GPU N1 − before per pair ${stats(ga.indices.map { gb[it] - ga[it] })}")
            android.util.Log.i("FilmN1Benchmark", "$name: wall per frame (40 back to back) before ${stats(ca)} [$xa frozen blocks left out]")
            android.util.Log.i("FilmN1Benchmark", "$name: wall per frame                     N1     ${stats(cb)} [$xb frozen blocks left out]")
            val sa = stages(before); val sb = stages(after)
            android.util.Log.i("FilmN1Benchmark", "$name stages GPU ms (before → N1): " + (sa.keys + sb.keys).distinct().joinToString(", ") { k ->
                "$k %.2f→%.2f".format(sa[k] ?: 0.0, sb[k] ?: 0.0) } + " | sum %.2f → %.2f".format(sa.values.sum(), sb.values.sum()))
            close(before); close(after)
            android.util.Log.i("FilmN1Benchmark", "after $name – ${thermal()}")
        }
    }
}
