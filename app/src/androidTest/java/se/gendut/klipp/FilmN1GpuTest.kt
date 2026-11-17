package se.gendut.klipp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.FilmFx
import se.gendut.klipp.engine.FilmGl
import se.gendut.klipp.engine.Spektra
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * N1 on the phone's GPU (FilmGl, as playback runs it), read back as floats – before any 8-bit rounding or video coding:
 *
 *  - the scaled sizes (refShort) are what the GPU uses: GPU against the CPU at the same scale, and against no scale;
 *  - the grain at half size against the full size averaged 2×2, before (no scale) and after: mean, RMS, the noise
 *    field's lag-1 autocorrelation and its power spectrum in bands, for five tones and six frames each.
 *
 * Results go to the log (tag FilmN1GpuTest). The grain gate is the one set before measuring: RMS ratio 0.95–1.05.
 */
@RunWith(AndroidJUnit4::class)
class FilmN1GpuTest {
    @Before fun init() {
        Spektra.init(InstrumentationRegistry.getInstrumentation().targetContext)
        GlHarness(16, 16).use { assertTrue("the phone renders to float textures", FilmGl.floatTargets()) }
    }

    private fun log(s: String) = android.util.Log.i("FilmN1GpuTest", s)

    /** The test card (code values) at w×h: background, a bright square, a dark bar, a grating (period w/32). */
    private fun card(w: Int, h: Int) = FloatArray(w * h * 3) { i ->
        val p = i / 3; val x = p % w; val y = p / w
        val fx = x.toDouble() / w; val fy = y.toDouble() / h
        when {
            fx in 0.10..0.35 && fy in 0.20..0.60 -> 0.80f
            fx in 0.45..0.50 && fy in 0.10..0.90 -> 0.12f
            fx in 0.60..0.90 && fy in 0.20..0.80 -> (0.45 + 0.2 * sin(2 * PI * x * 32.0 / w)).toFloat()
            else -> 0.38f
        }
    }

    private fun gpu(f: Film, img: FloatArray, w: Int, h: Int, ref: Int, timeUs: Long = 0): FloatArray = GlHarness(w, h).use { gl ->
        val p = FilmGl.Program(f, null, false, false, ref).also { it.configure(w, h) }
        try { gl.drawFloat(p, gl.upload(img), timeUs) } finally { p.release() }
    }

    private fun undershoot(img: FloatArray, w: Int, h: Int): Double {
        var bg = 0.0; var mn = Double.MAX_VALUE
        val y0 = (0.25 * h).toInt(); val y1 = (0.55 * h).toInt()
        fun col(x: Int): Double { var s = 0.0; for (y in y0 until y1) s += img[(y * w + x) * 3 + 1]; return s / (y1 - y0) }
        bg = col((0.40 * w).toInt())
        for (x in (0.35 * w).toInt() until (0.35 * w).toInt() + w / 20) mn = minOf(mn, col(x))
        return (bg - mn) * 255
    }

    @Test fun theGpuUsesTheScaledSizes() {
        val w = 320; val h = 180; val ref = 360
        val img = card(w, h)
        for ((label, f) in listOf("mask" to Film(grain = false, glare = false), "mask + scan blur 1.5" to Film(grain = false, glare = false, scanBlur = 1.5f))) {
            val g = gpu(f, img, w, h, ref); val c = FilmFx.applyEncoded(FilmFx.setup(f), img, w, h, 0, ref)
            val g0 = gpu(f, img, w, h, 0)
            val d = FloatArray(g.size) { abs(g[it] - c[it]) }
            val mean = d.average(); val p99 = d.sorted()[(d.size * 0.99).toInt()]
            log("$label: GPU vs CPU at scale 0.5: mean %.4f p99 %.4f; undershoot GPU %.2f CPU %.2f, GPU unscaled %.2f".format(
                mean, p99, undershoot(g, w, h), undershoot(c, w, h), undershoot(g0, w, h)))
            assertTrue("$label: GPU and CPU differ at scale 0.5 (mean $mean)", mean < 0.02 && p99 < 0.08)
            val moved = FloatArray(g.size) { abs(g[it] - g0[it]) }.average()
            assertTrue("$label: the GPU's halo is the CPU's at this scale (${undershoot(g, w, h)} vs ${undershoot(c, w, h)})",
                abs(undershoot(g, w, h) - undershoot(c, w, h)) < 1.0)
            assertTrue("$label: the scale changes the GPU's picture (mean change $moved)", moved > 0.001)
        }
    }

    // ------------------------------------------------------------------------------------------------ grain

    private class Stats(val mean: Double, val rms: Double, val acX: Double, val acY: Double, val bands: DoubleArray)

    /** Statistics of the G channel's noise on a flat field (border 8 px dropped): spectrum in 6 bands up to [nyq] cycles/px. */
    private fun stats(img: FloatArray, w: Int, h: Int, nyq: Double): Stats {
        val b = 8; val ww = w - 2 * b; val hh = h - 2 * b
        val v = DoubleArray(ww * hh) { i -> img[((i / ww + b) * w + i % ww + b) * 3 + 1] * 255.0 }
        val m = v.average(); for (i in v.indices) v[i] -= m
        val rms = sqrt(v.sumOf { it * it } / v.size)
        var ax = 0.0; var ay = 0.0
        for (y in 0 until hh) for (x in 0 until ww) { val a = v[y * ww + x]; if (x + 1 < ww) ax += a * v[y * ww + x + 1]; if (y + 1 < hh) ay += a * v[(y + 1) * ww + x] }
        ax /= (hh * (ww - 1)) * rms * rms; ay /= ((hh - 1) * ww) * rms * rms
        // the power spectrum by a direct DFT on a 64×64 block grid (averaged over blocks), radial bands of nyq/6
        val n = 64; val bands = DoubleArray(6); val counts = IntArray(6)
        val cs = Array(n) { k -> DoubleArray(n) { x -> cos(2 * PI * k * x / n) } }; val sn = Array(n) { k -> DoubleArray(n) { x -> sin(2 * PI * k * x / n) } }
        for (by in 0 until hh / n) for (bx in 0 until ww / n) {
            val blk = Array(n) { y -> DoubleArray(n) { x -> v[(by * n + y) * ww + bx * n + x] } }
            // rows first
            val re = Array(n) { DoubleArray(n) }; val im = Array(n) { DoubleArray(n) }
            for (y in 0 until n) for (k in 0 until n) { var r = 0.0; var i2 = 0.0; for (x in 0 until n) { r += blk[y][x] * cs[k][x]; i2 -= blk[y][x] * sn[k][x] }; re[y][k] = r; im[y][k] = i2 }
            for (kx in 0 until n) for (ky in 0 until n) {
                var r = 0.0; var i2 = 0.0
                for (y in 0 until n) { r += re[y][kx] * cs[ky][y] + im[y][kx] * sn[ky][y]; i2 += im[y][kx] * cs[ky][y] - re[y][kx] * sn[ky][y] }
                val fx = (if (kx > n / 2) kx - n else kx) / n.toDouble(); val fy = (if (ky > n / 2) ky - n else ky) / n.toDouble()
                val f = hypot(fx, fy); if (f <= 0.0 || f >= nyq) continue
                val bi = (f / nyq * 6).toInt().coerceIn(0, 5); bands[bi] += r * r + i2 * i2; counts[bi]++
            }
        }
        for (i in 0..5) bands[i] = if (counts[i] > 0) bands[i] / counts[i] else 0.0
        return Stats(m, rms, ax, ay, bands)
    }

    private fun half(img: FloatArray, w: Int, h: Int): FloatArray {
        val hw = w / 2; val hh = h / 2
        return FloatArray(hw * hh * 3) { i ->
            val c = i % 3; val p = i / 3; val x = p % hw; val y = p / hw
            var s = 0f; for (dy in 0..1) for (dx in 0..1) s += img[((2 * y + dy) * w + 2 * x + dx) * 3 + c]; s / 4f
        }
    }

    @Test fun grainBeforeTheCodec() {
        val w = 640; val h = 384; val hw = w / 2; val hh = h / 2
        val f = Film(glare = false)
        val frames = (0 until 6).map { it * 40_000L }
        var worstAfter = 0.0
        log("tone | before: mean Δ, RMS ratio, acX/acY half vs full↓, band ratios | after: the same (full↓ = the export brought down)")
        for (tone in listOf(0.15f, 0.30f, 0.45f, 0.60f, 0.75f)) {
            val full = FloatArray(w * h * 3) { tone }; val halfIn = FloatArray(hw * hh * 3) { tone }
            fun avg(list: List<Stats>) = Stats(list.map { it.mean }.average(), list.map { it.rms }.average(), list.map { it.acX }.average(),
                list.map { it.acY }.average(), DoubleArray(6) { b -> list.map { it.bands[b] }.average() })
            val ref = avg(frames.map { t -> stats(half(gpu(f, full, w, h, 0, t), w, h), hw, hh, 0.5) })
            fun row(r: Int): String {
                val s = avg(frames.map { t -> stats(gpu(f, halfIn, hw, hh, r, t), hw, hh, 0.5) })
                val ratio = s.rms / ref.rms
                if (r > 0) worstAfter = maxOf(worstAfter, abs(ratio - 1))
                return "mean Δ %+.3f RMS %.3f/%.3f (%.3f) ac %.3f/%.3f vs %.3f/%.3f bands %s".format(s.mean - ref.mean, s.rms, ref.rms, ratio,
                    s.acX, s.acY, ref.acX, ref.acY, (0..5).joinToString(" ") { "%.2f".format(s.bands[it] / ref.bands[it]) })
            }
            log("%.2f | before %s".format(tone, row(0)))
            log("%.2f | after  %s".format(tone, row(h)))
        }
        // and the GPU's grain against the CPU's (different generators, same statistics) at the export's size
        val cpu = FilmFx.applyEncoded(FilmFx.setup(f), FloatArray(w * h * 3) { 0.45f }, w, h, 3)
        val g = gpu(f, FloatArray(w * h * 3) { 0.45f }, w, h, 0, 120_000)
        val sc = stats(cpu, w, h, 0.5); val sg = stats(g, w, h, 0.5)
        log("GPU vs CPU at full size, tone 0.45: mean %.3f/%.3f RMS %.3f/%.3f ac %.3f/%.3f".format(sg.mean, sc.mean, sg.rms, sc.rms, sg.acX, sc.acX))
        assertTrue("grain at half size vs the export brought down, worst RMS ratio off by $worstAfter", worstAfter <= 0.05)
    }

    /** Diagnostics: the unsharp mask alone, pixel by pixel, on a noise picture (everything random or spatial else off). */
    @Test fun maskPixelByPixel() {
        val w = 320; val h = 192
        val rnd = java.util.Random(5)
        val img = FloatArray(w * h * 3) { 0.3f + 0.4f * rnd.nextFloat() }.also { a -> for (p in 0 until w * h) { a[p * 3 + 1] = a[p * 3]; a[p * 3 + 2] = a[p * 3] } }
        val f = Film(grain = false, glare = false, halation = 0f, scatter = 0f, couplers = 0f)
        for ((label, ref) in listOf("σ 0.7 (5 taps)" to 0, "σ 0.35 (3 taps)" to 2 * h)) {
            val g = gpu(f, img, w, h, ref); val c = FilmFx.applyEncoded(FilmFx.setup(f), img, w, h, 0, ref)
            val d = FloatArray(g.size) { abs(g[it] - c[it]) }
            var gx = 0.0; var gy = 0.0; var cx = 0.0; var cy = 0.0
            for (y in 4 until h - 5) for (x in 4 until w - 5) {
                val i0 = (y * w + x) * 3 + 1
                gx += (g[i0 + 3] - g[i0]).let { it * it }.toDouble(); gy += (g[i0 + 3 * w] - g[i0]).let { it * it }.toDouble()
                cx += (c[i0 + 3] - c[i0]).let { it * it }.toDouble(); cy += (c[i0 + 3 * w] - c[i0]).let { it * it }.toDouble()
            }
            log("mask %s: GPU vs CPU mean %.5f p99 %.5f max %.5f; gradient energy GPU x/y %.3f, CPU x/y %.3f, GPU/CPU x %.3f y %.3f".format(label,
                d.average(), d.sorted()[(d.size * 0.99).toInt()], d.maxOrNull(), gx / gy, cx / cy, gx / cx, gy / cy))
            assertTrue("$label: the GPU's mask is the CPU's (mean ${d.average()}, x ${gx / cx}, y ${gy / cy})",
                d.average() < 0.001 && abs(gx / cx - 1) < 0.01 && abs(gy / cy - 1) < 0.01)
        }
    }

    private fun dec(v: Float) = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    private fun enc(v: Float) = if (v <= 0.0031308f) v * 12.92f else (1.055 * Math.pow(v.toDouble(), 1 / 2.4) - 0.055).toFloat()

    /**
     * The GPU's mask on the GPU's own grain against Klipp's mask applied by hand to the same grain, at full scale (σ 0.7)
     * and at half (σ 0.35): at half scale it must be no further off than at full. (It was: the horizontal pass stored as
     * a half-float blur rounded away the faint high-pass of a 3-tap mask – grain up to 4 % stronger, more so across.)
     */
    @Test fun maskOnGrainByScale() {
        val hw = 320; val hh = 192
        val ratio = HashMap<String, Double>()
        for ((label, ref, sigma) in listOf(Triple("full σ0.70", 0, 0.7), Triple("half σ0.35", 384, 0.35))) for (tone in listOf(0.45f, 0.75f)) {
            val img = FloatArray(hw * hh * 3) { tone }
            val g0 = gpu(Film(glare = false, sharpen = 0f, grainBlur = 0f), img, hw, hh, ref, 80_000)
            val g1 = gpu(Film(glare = false, grainBlur = 0f), img, hw, hh, ref, 80_000)
            val lin = FloatArray(g0.size) { dec(g0[it]) }
            val bl = FilmFx.Pyramid.blur(lin, hw, hh, FilmFx.smallTaps(sigma))
            val hand = FloatArray(lin.size) { enc((lin[it] + 0.7f * (lin[it] - bl[it])).coerceIn(0f, 1f)) }
            val s1 = stats(g1, hw, hh, 0.5); val sh = stats(hand, hw, hh, 0.5)
            log("scale %s %.2f: GPU mask RMS %.3f ac %.3f/%.3f | hand RMS %.3f ac %.3f/%.3f | RMS ratio %.3f".format(label, tone, s1.rms, s1.acX, s1.acY, sh.rms, sh.acX, sh.acY, s1.rms / sh.rms))
            ratio["$tone ${label.take(4)}"] = s1.rms / sh.rms
        }
        for (tone in listOf(0.45f, 0.75f)) {
            val off = abs(ratio["$tone half"]!! - ratio["$tone full"]!!)
            assertTrue("tone $tone: the mask on grain at half scale is off by $off more than at full", off <= 0.01)
        }
    }
}
