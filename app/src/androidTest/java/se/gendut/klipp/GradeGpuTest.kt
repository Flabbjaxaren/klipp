package se.gendut.klipp

import androidx.media3.effect.BaseGlShaderProgram
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.gendut.klipp.engine.Curve
import se.gendut.klipp.engine.Denoise
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.GradeNodeEffect
import se.gendut.klipp.engine.HslCurve
import se.gendut.klipp.engine.HslCurves
import se.gendut.klipp.engine.Qualifier
import se.gendut.klipp.engine.Wheel
import se.gendut.klipp.engine.Window
import kotlin.math.abs

/**
 * A grade node on the phone's GPU (the GLSL pass playback and export run) against the CPU ([GradeNode.apply], which
 * paused frames, the node cache and the tests use): wheels and adjustments, curves and HSL curves, a rotated window off
 * the middle (positions mean the same on both), a qualifier, the vignette preset.
 */
@RunWith(AndroidJUnit4::class)
class GradeGpuTest {
    private val w = 160
    private val h = 90
    private lateinit var gl: GlHarness

    @Before fun gl() { gl = GlHarness(w, h) }
    @After fun release() = gl.close()

    /** Hues across, saturation and brightness down: every kind of color, in code values. */
    private val image = FloatArray(w * h * 3).also { a ->
        for (y in 0 until h) for (x in 0 until w) {
            val hue = x / w.toFloat() * 6f
            val sat = 0.15f + 0.85f * y / (h - 1f)
            val v = 0.95f - 0.6f * ((x * 7 + y * 3) % 40) / 40f
            val f = hue - kotlin.math.floor(hue)
            val p = v * (1 - sat); val q = v * (1 - sat * f); val t = v * (1 - sat * (1 - f))
            val (r, g, b) = when (hue.toInt() % 6) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
            val i = (y * w + x) * 3
            a[i] = r; a[i + 1] = g; a[i + 2] = b
        }
    }

    private fun cpu(n: GradeNode): FloatArray {
        val out = image.copyOf()
        val p = n.params()
        val c = FloatArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            c[0] = out[i]; c[1] = out[i + 1]; c[2] = out[i + 2]
            GradeNode.apply(p, c, (x + 0.5f) / w, (y + 0.5f) / h, w.toFloat() / h)
            for (k in 0..2) out[i + k] = c[k].coerceIn(0f, 1f)
        }
        return out
    }

    private fun gpu(n: GradeNode): FloatArray {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val p = GradeNodeEffect(n.params()).toGlShaderProgram(ctx, false) as BaseGlShaderProgram
        p.configure(w, h)
        val input = gl.upload(image)
        val out = gl.draw(p, input)
        p.release()
        return out
    }

    private fun assertSame(what: String, n: GradeNode) {
        val g = gpu(n); val c = cpu(n)
        val d = FloatArray(g.size) { abs(g[it] - c[it]) }
        val mean = d.average().toFloat()
        val p99 = d.sorted()[(d.size * 0.99).toInt()]
        val worst = d.indices.maxBy { d[it] }
        android.util.Log.i("GradeGpuTest", "$what: mean %.4f p99 %.4f max %.4f at x=%d y=%d".format(mean, p99, d[worst], (worst / 3) % w, (worst / 3) / w))
        // the node changed the picture (a pass that silently did nothing would also "match" an unchanged CPU result)
        val moved = c.indices.count { abs(c[it] - image[it]) > 0.02f }
        assertTrue("$what changes the picture on the CPU", moved > c.size / 20)
        assertTrue("$what: GPU and CPU differ, mean $mean", mean < 0.01f)
        assertTrue("$what: GPU and CPU differ, 99th percentile $p99", p99 < 0.04f)
    }

    @Test fun wheelsAndAdjust() = assertSame("wheels", GradeNode(1,
        lift = Wheel(0.1f, -0.05f, 0.02f), gamma = Wheel(-0.05f, 0.08f, 0.05f), gain = Wheel(0.1f, 0.1f, 0.1f), offset = Wheel(0.02f, 0f, 0f),
        contrast = 1.2f, sat = 1.3f, hue = 10f, temp = 0.2f, tint = -0.1f))

    @Test fun curvesAndHsl() = assertSame("curves", GradeNode(1,
        curveY = Curve(listOf(0f to 0f, 0.5f to 0.62f, 1f to 1f)), curveR = Curve(listOf(0f to 0.04f, 1f to 0.95f)),
        hsl = HslCurves(hueSat = HslCurve(listOf(0f to 0.8f, 0.33f to 0.3f, 0.66f to 0.6f)), hueHue = HslCurve(listOf(0.5f to 0.6f)),
            lumSat = HslCurve(listOf(0f to 0.3f, 1f to 0.5f)))))

    @Test fun windowOffTheMiddle() = assertSame("window", GradeNode(1,
        gain = Wheel(0f, 0f, -0.5f), win = Window(shape = 1, cx = 0.3f, cy = 0.25f, rx = 0.2f, ry = 0.15f, soft = 0.4f, angle = 20f)))

    @Test fun qualifier() = assertSame("qualifier", GradeNode(1,
        sat = 0f, qual = Qualifier(on = true, hue = 200f, hueWidth = 80f, satLo = 0.3f, soft = 0.15f)))

    @Test fun vignette() = assertSame("vignette", vignette(GradeNode(1)))

    /** Noise reduction reads the neighbors: the GPU pass against [Denoise] on a noisy picture. */
    @Test fun noiseReduction() {
        val rnd = java.util.Random(7)
        val noisy = FloatArray(image.size) { (image[it] + rnd.nextGaussian().toFloat() * 0.03f).coerceIn(0f, 1f) }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val p = GradeNodeEffect(GradeNode(1, denoise = 0.6f).params()).toGlShaderProgram(ctx, false) as BaseGlShaderProgram
        p.configure(w, h)
        val g = gl.draw(p, gl.upload(noisy))
        p.release()
        val c = Denoise.apply(noisy, w, h, 0.6f)
        val d = FloatArray(g.size) { abs(g[it] - c[it]) }
        val mean = d.average().toFloat(); val p99 = d.sorted()[(d.size * 0.99).toInt()]
        android.util.Log.i("GradeGpuTest", "denoise: mean %.4f p99 %.4f".format(mean, p99))
        val changed = c.indices.count { abs(c[it] - noisy[it]) > 0.01f }
        assertTrue("it smooths on the CPU", changed > c.size / 4)
        assertTrue("denoise: GPU and CPU differ, mean $mean", mean < 0.01f)
        assertTrue("denoise: GPU and CPU differ, 99th percentile $p99", p99 < 0.04f)
    }
}
