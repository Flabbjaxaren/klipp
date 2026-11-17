package se.gendut.klipp

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.FilmFx
import se.gendut.klipp.engine.Spektra
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The film's sizes in pixels (the scanner's unsharp mask, the scan blur, the grain blur's 0.65) are the export's: a
 * frame rendered smaller than the export (the 1080 preview of a 4K clip) must look like the export brought down to its
 * size, and the export itself must not change. Half size against the full render averaged 2×2; code values ×255.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilmResolutionTest {
    companion object {
        @BeforeClass @JvmStatic fun data() { Spektra.open = { File("src/main/assets/spektrafilm/$it").inputStream() } }
        const val W = 768
        const val H = 432
    }

    /** A test card in input code values: background, a bright square, a dark bar and a sine grating (period 24 px). */
    private fun card(w: Int, h: Int): FloatArray = FloatArray(w * h * 3) { i ->
        val p = i / 3; val x = p % w; val y = p / w
        val fx = x.toDouble() / w; val fy = y.toDouble() / h
        when {
            fx in 0.10..0.35 && fy in 0.20..0.60 -> 0.80f
            fx in 0.45..0.50 && fy in 0.10..0.90 -> 0.12f
            fx in 0.60..0.90 && fy in 0.20..0.80 -> (0.45 + 0.2 * sin(2 * PI * x * w / W / 24.0)).toFloat()
            else -> 0.38f
        }
    }

    private fun half(img: FloatArray, w: Int, h: Int): FloatArray {
        val hw = w / 2; val hh = h / 2
        return FloatArray(hw * hh * 3) { i ->
            val c = i % 3; val p = i / 3; val x = p % hw; val y = p / hw
            var s = 0f
            for (dy in 0..1) for (dx in 0..1) s += img[((2 * y + dy) * w + 2 * x + dx) * 3 + c]
            s / 4f
        }
    }

    private fun mae(a: FloatArray, b: FloatArray, w: Int, h: Int, border: Int = 6): Pair<Double, Double> {
        val d = ArrayList<Double>()
        for (y in border until h - border) for (x in border until w - border) for (c in 0..2) d += abs(a[(y * w + x) * 3 + c] - b[(y * w + x) * 3 + c]) * 255.0
        d.sort()
        return d.average() to d[(d.size * 0.99).toInt()]
    }

    /** The dark undershoot on the background beside the bright square's right edge (G, middle row). */
    private fun undershoot(img: FloatArray, w: Int, h: Int): Double {
        val y = (0.40 * h).toInt(); val x0 = (0.35 * w).toInt()
        val bg = img[(y * w + (0.40 * w).toInt()) * 3 + 1]
        var mn = Float.MAX_VALUE
        for (x in x0 until x0 + w / 20) mn = minOf(mn, img[(y * w + x) * 3 + 1])
        return (bg - mn) * 255.0
    }

    private fun rms(img: FloatArray, w: Int, h: Int): Double {
        var s = 0.0; var s2 = 0.0; var n = 0
        for (y in 8 until h - 8) for (x in 8 until w - 8) { val v = img[(y * w + x) * 3 + 1] * 255.0; s += v; s2 += v * v; n++ }
        val m = s / n; return sqrt(max(0.0, s2 / n - m * m))
    }

    private fun render(f: Film, img: FloatArray, w: Int, h: Int, ref: Int, seed: Long = 1) = FilmFx.applyEncoded(FilmFx.setup(f), img, w, h, seed, ref)

    @Test fun theExportIsUnchanged() {
        val img = card(W, H)
        for (f in listOf(Film(), Film(film = "kodak_vision3_500t", print = "kodak_2383", scanBlur = 1f, format = 16f), Film(film = "fujifilm_velvia_100", print = null))) {
            val before = render(f, img, W, H, 0, seed = 7)
            val after = render(f, img, W, H, minOf(W, H), seed = 7)
            assertTrue("${f.film}: the export's own size must give the same image bit for bit", before.contentEquals(after))
        }
    }

    @Test fun aSmallerRenderLooksLikeTheExportBroughtDown() {
        val full = card(W, H); val halfIn = half(full, W, H); val hw = W / 2; val hh = H / 2
        println("== before (sizes in the render's pixels) | after (in the export's) – half size vs full↓, code values ×255 ==")
        var after: Triple<Double, Double, Double>? = null
        for ((label, f) in listOf("defaults, grain/glare off" to Film(grain = false, glare = false),
                                  "scan blur 1.5" to Film(grain = false, glare = false, scanBlur = 1.5f))) {
            val ref = half(render(f, full, W, H, 0), W, H)
            val rows = listOf(0, H).map { r ->
                val a = render(f, halfIn, hw, hh, r)
                val (m, p) = mae(a, ref, hw, hh)
                Triple(m, p, abs(undershoot(a, hw, hh) - undershoot(ref, hw, hh)))
            }
            println("%-28s MAE %.3f → %.3f   p99 %.2f → %.2f   |undershoot − full↓| %.2f → %.2f  (full↓ %.2f)".format(label,
                rows[0].first, rows[1].first, rows[0].second, rows[1].second, rows[0].third, rows[1].third, undershoot(ref, hw, hh)))
            if (after == null) after = rows[1]
        }
        assertTrue("half-size image vs the export brought down: MAE ${after!!.first}", after.first <= 0.3)
        assertTrue("the sharpening halo beside an edge: ${after.third} code values off", after.third <= 1.0)

        val grey = FloatArray(W * H * 3) { 0.45f }; val greyHalf = half(grey, W, H)
        var ratioAfter = 0.0
        for ((label, f) in listOf("grain (glare off)" to Film(glare = false), "grain, sharpen off" to Film(glare = false, sharpen = 0f),
                                  "glare only (grain off)" to Film(grain = false))) {
            var rf = 0.0
            for (seed in 1L..4L) rf += rms(half(render(f, grey, W, H, 0, seed), W, H), hw, hh)
            val r = listOf(0, H).map { ref ->
                var rh = 0.0
                for (seed in 1L..4L) rh += rms(render(f, greyHalf, hw, hh, ref, seed), hw, hh)
                rh / rf
            }
            println("%-28s noise RMS half / full↓: %.3f → %.3f   (full↓ RMS %.3f code values)".format(label, r[0], r[1], rf / 4))
            if (label.startsWith("grain (")) ratioAfter = r[1]
        }
        assertTrue("grain at half size vs the export brought down: RMS ratio $ratioAfter", ratioAfter in 0.95..1.05)
    }

    /**
     * The grain blur blurs the whole grainy density – the image too, not only the noise (spektrafilm). Averaged over
     * seeds the noise goes and the image's edge stays. The variance-matched grain blur (N1) is chosen for the noise; on
     * the image it blurs about half as much as the export brought down (grain's share of the edge −3.5/+7.1 against
     * −7.5/+18.8 code values), but the whole edge is far closer than before N1 (1.6 against 7.4 code values off).
     * Guards that: no worse than half of the unscaled film's error. (Found on the vivo 2026-11-17: the node cache vs
     * live; see docs/n1-pixel-units.md.)
     */
    @Test fun theGrainBlursTheImageAsTheExportDoes() {
        // (a size where the edge at 0.35 falls on a pixel boundary at both sizes, as on the 4K card: no mixed pixel)
        val W = 800; val H = 448
        val ex = 280  // the square's right edge: full pixels 0…279 bright, half 0…139
        val full = FloatArray(W * H * 3) { i -> val p = i / 3; val x = p % W; val y = p / W
            if (x in 80 until ex && y in (0.20 * H).toInt() until (0.60 * H).toInt()) 0.80f else 0.38f }
        val halfIn = half(full, W, H); val hw = W / 2; val hh = H / 2
        val f = Film(glare = false)
        val seeds = 1L..12L
        /** The edge profile (G, rows .25–.55 averaged, columns e−3 … e+4 of the bright square's right edge), averaged over seeds. */
        fun edge(img: (Long) -> FloatArray, w: Int, h: Int): DoubleArray {
            val e = ex * w / W; val out = DoubleArray(8)
            for (sd in seeds) { val a = img(sd); for (y in (0.25 * h).toInt() until (0.55 * h).toInt()) for (k in 0..7) out[k] += a[(y * w + e - 3 + k) * 3 + 1] * 255.0 }
            val n = seeds.count() * ((0.55 * h).toInt() - (0.25 * h).toInt()); for (k in 0..7) out[k] /= n
            return out
        }
        val ref = edge({ sd -> half(render(f, full, W, H, 0, sd), W, H) }, hw, hh)
        val noGrain = edge({ sd -> half(render(Film(glare = false, grain = false), full, W, H, 0, sd), W, H) }, hw, hh)
        val got = edge({ sd -> render(f, halfIn, hw, hh, H, sd) }, hw, hh)
        val gotNoGrain = edge({ sd -> render(Film(glare = false, grain = false), halfIn, hw, hh, H, sd) }, hw, hh)
        val pre = edge({ sd -> render(f, halfIn, hw, hh, 0, sd) }, hw, hh)
        val preNoGrain = edge({ sd -> render(Film(glare = false, grain = false), halfIn, hw, hh, 0, sd) }, hw, hh)
        println("before N1 (half, unscaled): ${pre.joinToString(" ") { "%.2f".format(it) }}  | grain's share ${DoubleArray(8) { pre[it] - preNoGrain[it] }.joinToString(" ") { "%.2f".format(it) }}  | largest vs full↓ %.2f".format((0..7).maxOf { abs(pre[it] - ref[it]) }))
        val su = FilmFx.setup(f)
        println("grain blur σ: export ${"%.3f".format(su.forImage(W, H, 0).grainSigmaPx)}, half ${"%.3f".format(su.forImage(hw, hh, H).grainSigmaPx)} (in its pixels)")
        println("edge e−3…e+4, code values: full↓ ${ref.joinToString(" ") { "%.2f".format(it) }}")
        println("                           half  ${got.joinToString(" ") { "%.2f".format(it) }}")
        val dRef = DoubleArray(8) { ref[it] - noGrain[it] }; val dGot = DoubleArray(8) { got[it] - gotNoGrain[it] }
        println("grain's share of the edge:  full↓ ${dRef.joinToString(" ") { "%.2f".format(it) }}")
        println("                            half  ${dGot.joinToString(" ") { "%.2f".format(it) }}")
        val off = (0..7).maxOf { abs(got[it] - ref[it]) }
        val offBefore = (0..7).maxOf { abs(pre[it] - ref[it]) }
        println("largest edge difference half vs full↓: %.2f code values (before N1 %.2f)".format(off, offBefore))
        assertTrue("with grain the half-size edge vs the export brought down: $off code values off, before N1 $offBefore", off <= offBefore / 2)
    }
}
