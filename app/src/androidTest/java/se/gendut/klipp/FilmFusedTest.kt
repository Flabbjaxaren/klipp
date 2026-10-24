package se.gendut.klipp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.FilmFx
import se.gendut.klipp.engine.FilmGl
import se.gendut.klipp.engine.Spektra
import kotlin.math.abs

/**
 * The fused full-resolution blurs (FilmGl.fusedBlur) give the same picture as one pass per element, at the preview
 * size where the default film uses four full-resolution elements in the camera and two in the couplers. The only
 * difference is rounding: the fused sum stays in 32 bits instead of being stored at 16 bits after every element.
 */
@RunWith(AndroidJUnit4::class)
class FilmFusedTest {
    private val w = 1920
    private val h = 1080
    private lateinit var gl: GlHarness

    @Before fun gl() { Spektra.init(InstrumentationRegistry.getInstrumentation().targetContext); gl = GlHarness(w, h) }
    @After fun release() { FilmGl.fusedBlur = true; gl.close() }

    /** Smooth gradients, a hard edge and a small very bright spot (halation and the couplers' spread show there). */
    private val image = FloatArray(w * h * 3).also { a ->
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            a[i] = x / (w - 1f); a[i + 1] = y / (h - 1f); a[i + 2] = if (x > w / 2) 0.8f else 0.2f
            if ((x - 1400) * (x - 1400) + (y - 500) * (y - 500) < 400) { a[i] = 1f; a[i + 1] = 1f; a[i + 2] = 1f }
        }
    }

    private fun render(f: Film, fused: Boolean): FloatArray {
        FilmGl.fusedBlur = fused
        val p = FilmGl.Program(f, null, false).also { it.configure(w, h) }
        val out = gl.draw(p, gl.upload(image))
        p.release()
        return out
    }

    /** Mean and largest difference from the CPU path (32-bit floats throughout), and how many values differ by over 1.5/255. */
    private fun vsCpu(gpu: FloatArray, cpu: FloatArray): Triple<Double, Float, Int> {
        var sum = 0.0; var max = 0f; var over = 0
        for (i in gpu.indices) { val d = abs(gpu[i] - cpu[i].coerceIn(0f, 1f)); sum += d; if (d > max) max = d; if (d > 1.5f / 255f) over++ }
        return Triple(sum / gpu.size, max, over)
    }

    /**
     * Fused and split against each other – and both against the CPU: the fused one must be as close (it is the
     * rounding between elements that differs). Grain off: it is random per pixel, and a rounding difference can
     * flip a grain decision, which says nothing about the blurs.
     */
    private fun same(name: String, f: Film) {
        val a = render(f, false); val b = render(f, true)
        var sum = 0.0; var max = 0f; var over1 = 0
        for (i in a.indices) { val d = abs(a[i] - b[i]); sum += d; if (d > max) max = d; if (d > 1.5f / 255f) over1++ }
        val mean = sum / a.size
        val cpu = FilmFx.applyEncoded(FilmFx.setup(f), image, w, h, 0)
        val sa = vsCpu(a, cpu); val sb = vsCpu(b, cpu)
        android.util.Log.i("FilmFusedTest", "$name: fused vs split mean %.6f max %.4f (%d over 1/255); vs CPU: split mean %.6f max %.4f over %d, fused mean %.6f max %.4f over %d"
            .format(mean, max, over1, sa.first, sa.second, sa.third, sb.first, sb.second, sb.third))
        assertTrue("$name: mean difference $mean", mean < 0.0005)
        // as close to the CPU as the split passes (within 1 %: both differ from it by the GPU's own rounding, ~6× more
        // than they differ from each other)
        assertTrue("$name: the fused blurs are further from the CPU (${sb.first} vs ${sa.first})", sb.first <= sa.first * 1.01)
        assertTrue("$name: more values off the CPU fused (${sb.third}) than split (${sa.third})", sb.third <= sa.third * 1.01)
    }

    @Test fun defaultFilm() = same("default", Film(grain = false))
    @Test fun effectsOnly() = same("effects only", Film(grain = false, effectsOnly = true))
    @Test fun strongHalationAndScatter() = same("strong", Film(grain = false, halation = 3f, scatter = 2f, halationSize = 2f))
}
