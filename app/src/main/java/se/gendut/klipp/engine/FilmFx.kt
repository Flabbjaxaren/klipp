package se.gendut.klipp.engine

import se.gendut.klipp.R
import se.gendut.klipp.Str
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * spektrafilm (Andrea Volpato et al., GPL-3.0; profiles CC BY-SA 4.0) – the whole image, not just the color:
 *
 *   exposure → (diffusion filter, lens blur, scattering in the emulsion, halation) → development with
 *   DIR coupler diffusion → grain → printing → scanning (glare, sharpening) → display
 *
 * The per-pixel chain is the same as in [Spektra.Model] (the tests compare). The blur kernels (spektrafilm:
 * sums of Gaussian and exponential kernels in µm on the film) are converted to pixels from the film format and
 * the image size, and done with a Gaussian pyramid: each level is the image blurred with σ = 1, 2, 4 … pixels,
 * and the kernel becomes a weighted sum of the levels. The weights are fitted (non-negative least squares) so that
 * the sum's MTF follows the kernel's – the same weights for the GPU ([FilmGl]) and the CPU (here: stills,
 * scopes, the tests).
 *
 * Grain follows spektrafilm's model (three sublayers per color layer, Poisson-binomial): the number of developed
 * grains in a pixel is Poisson-distributed with mean n·p/s (n grains per pixel, p = density/max density,
 * s = saturation) – the same distribution as spektrafilm's Binom(Poisson(n/s), p). It is re-randomized every frame.
 *
 * Deviations from spektrafilm: auto exposure (depends on the whole image's mean – would pump in video) is not
 * present, and "boost highlights" is normalized by the averaged image's max, not the brightest pixel ([boostRef]);
 * glare is added after the scan's gamut compression and without its small blur (0.5 pixels); the enlarger's diffusion
 * filter and pre-flash are not present.
 */
object FilmFx {
    // ------------------------------------------------------------------------------------------- diffusion filters

    class Group(val lambdaUm: Double, val spread: Double, val n: Int, val alpha: Double = 3.0)

    /** spektrafilm model/diffusion.py: _DIFFUSION_FILTER_SHAPES and _DIFFUSION_FAMILY_TOTAL_GAIN. */
    class Family(
        val key: String, val label: String, @androidx.annotation.StringRes val hintRes: Int,
        val core: Group, val halo: Group, val bloom: Group,
        val wc: Double, val wh: Double, val wb: Double, val warmth: Double, val gain: Double,
    ) {
        /** The filter's description, resolved in the current language. */
        val hint: String get() = Str.t(hintRes)
    }

    val FAMILIES = listOf(
        Family("glimmerglass", "Glimmerglass", R.string.filmfx_hint_glimmerglass,
            Group(10.0, 1.5, 2), Group(50.0, 2.0, 3), Group(260.0, 2.5, 4, 3.2), 0.60, 0.30, 0.10, 0.0, 0.65),
        Family("black_pro_mist", "Black Pro-Mist", R.string.filmfx_hint_black_pro_mist,
            Group(16.0, 1.5, 2), Group(95.0, 2.0, 3), Group(380.0, 2.5, 4, 3.5), 0.40, 0.47, 0.13, 0.65, 0.75),
        Family("pro_mist", "Pro-Mist", R.string.filmfx_hint_pro_mist,
            Group(14.0, 1.5, 2), Group(150.0, 2.0, 3), Group(650.0, 2.5, 4, 2.9), 0.28, 0.42, 0.30, 0.40, 1.05),
        Family("cinebloom", "CineBloom", R.string.filmfx_hint_cinebloom,
            Group(20.0, 1.5, 2), Group(200.0, 2.0, 3), Group(1000.0, 2.5, 4, 2.5), 0.22, 0.30, 0.48, 0.85, 1.00),
    )
    val DIFFUSION: Set<String> = FAMILIES.map { it.key }.toSet()

    /** The filters' strengths (as they are sold). */
    val STRENGTHS = floatArrayOf(0.125f, 0.25f, 0.5f, 1f, 2f)

    private val STRENGTH_BREAKS = doubleArrayOf(0.125, 0.25, 0.5, 1.0, 2.0)
    private val STRENGTH_FRACTION = doubleArrayOf(0.10, 0.20, 0.35, 0.55, 0.75)
    private val WARMTH_AXIS = doubleArrayOf(1.30, 0.15, -1.45)

    /** The fraction of scattered light for a strength (log2-interpolated, like _strength_to_scatter). */
    fun scatterFraction(strength: Double, fam: Family): Double {
        if (strength <= 0) return 0.0
        val ls = log2(max(strength, 1e-6))
        val lb = DoubleArray(STRENGTH_BREAKS.size) { log2(STRENGTH_BREAKS[it]) }
        val base = Spektra.interp(ls, lb, 1, 0, STRENGTH_FRACTION, 1, 0, lb.size)
        return (base * fam.gain).coerceIn(0.0, 0.99)
    }

    /** (λ, weight) for a group: geometric series; core and halo equal weights, bloom λ^(2−α) (_expand_group). */
    private fun expand(g: Group, bloom: Boolean): Pair<DoubleArray, DoubleArray> {
        if (g.n <= 1 || g.spread <= 1.0) return doubleArrayOf(g.lambdaUm) to doubleArrayOf(1.0)
        val lo = ln(g.lambdaUm / g.spread); val hi = ln(g.lambdaUm * g.spread)
        val l = DoubleArray(g.n) { exp(lo + (hi - lo) * it / (g.n - 1)) }
        val w = DoubleArray(g.n) { if (bloom) l[it].pow(2.0 - g.alpha) else 1.0 }
        val s = w.sum()
        for (i in w.indices) w[i] /= s
        return l to w
    }

    /** The halo's weights per channel (_halo_channel_weights): warm outward, cool inward, energy per channel preserved. */
    private fun haloChannels(w: DoubleArray, warmth0: Double): Array<DoubleArray> {
        val n = w.size
        if (n < 2) return Array(3) { w.copyOf() }
        val warmth = warmth0.coerceIn(-1.5, 1.5)
        val g = DoubleArray(n) { -1.0 + 2.0 * it / (n - 1) }
        val avg = g.indices.sumOf { g[it] * w[it] } / w.sum()
        for (i in g.indices) g[i] -= avg
        val total = w.sum()
        return Array(3) { c ->
            val raw = DoubleArray(n) { max(w[it] * (1.0 + warmth * WARMTH_AXIS[c] * g[it]), 0.0) }
            val s = raw.sum()
            if (s > 0) DoubleArray(n) { raw[it] * total / s } else w.copyOf()
        }
    }

    // ------------------------------------------------------------------------------------------- kernels (MTF)

    /**
     * One kernel per channel as a 2D MTF (fx, fy in cycles per pixel) and its largest scale (pixels). The MTF is the
     * one spektrafilm actually gets: small Gaussians (σ < 3) are its sampled, separable FIR filters (radius int(3σ + ½)),
     * the diffusion filters' small exponentials its sampled kernel – not the analytic forms. (The whole plane is needed:
     * a sum of separable kernels is not determined by its MTF along one axis.)
     */
    class Kernel(val mtf: Array<(Double, Double) -> Double>, val scalePx: Double) {
        operator fun times(o: Kernel) = Kernel(Array(3) { c -> { fx: Double, fy: Double -> mtf[c](fx, fy) * o.mtf[c](fx, fy) } }, max(scalePx, o.scalePx))
        companion object {
            val ID = Kernel(Array(3) { { _: Double, _: Double -> 1.0 } }, 0.0)
        }
    }

    private fun gauss2(sigma: Double, fx: Double, fy: Double) = exp(-2.0 * PI * PI * sigma * sigma * (fx * fx + fy * fy))

    /** A sampled filter (odd length, centered): DTFT along one axis. */
    fun tapsMtf(k: FloatArray, f: Double): Double {
        val r = k.size / 2
        var s = 0.0
        for (t in k.indices) s += k[t] * cos(2 * PI * f * (t - r))
        return s
    }

    /** spektrafilm's fast_gaussian_filter: separable FIR (truncate 3) for σ < 3, otherwise recursive ≈ analytic Gaussian. */
    private fun spkGauss(sigma: Double): (Double, Double) -> Double {
        if (sigma >= 3.0) { val h = yvvMtf(sigma); return { fx, fy -> h(fx) * h(fy) } }
        val taps = smallTaps(max(sigma, 1e-6))
        return { fx, fy -> tapsMtf(taps, fx) * tapsMtf(taps, fy) }
    }

    /**
     * spektrafilm's large Gaussian (σ ≥ 3, fast_gaussian_filter): Young–van Vliet's recursive filter, third order, forward
     * and backward – frequency response B²/|1 − b1·e^−iω − b2·e^−2iω − b3·e^−3iω|² per axis. The tail is heavier than an
     * exact Gaussian (2× at 3σ, ≈ 10× at 4σ), which shows around very bright highlights.
     */
    internal fun yvvMtf(sigma: Double): (Double) -> Double {
        val q = if (sigma >= 2.5) 0.98711 * sigma - 0.96330 else 3.97156 - 4.14554 * sqrt(1.0 - 0.26891 * sigma)
        val q2 = q * q; val q3 = q2 * q
        val b0 = 1.57825 + 2.44413 * q + 1.4281 * q2 + 0.422205 * q3
        val b1 = (2.44413 * q + 2.85619 * q2 + 1.26661 * q3) / b0
        val b2 = -(1.4281 * q2 + 1.26661 * q3) / b0
        val b3 = 0.422205 * q3 / b0
        val bb = 1.0 - (b1 + b2 + b3)
        return { f ->
            val w = 2.0 * PI * f
            val re = 1.0 - b1 * cos(w) - b2 * cos(2 * w) - b3 * cos(3 * w)
            val im = b1 * sin(w) + b2 * sin(2 * w) + b3 * sin(3 * w)
            bb * bb / (re * re + im * im)
        }
    }

    // fast_exponential_filter: three Gaussians instead of the exponential (the scatter and coupler tails)
    private val EXP3 = arrayOf(doubleArrayOf(0.1633, 0.5360), doubleArrayOf(0.6496, 1.5236), doubleArrayOf(0.1870, 2.7684))
    private fun spkExp3(lambda: Double): (Double, Double) -> Double {
        val g = EXP3.map { it[0] to spkGauss(it[1] * lambda) }
        return { fx, fy -> g.sumOf { (a, m) -> a * m(fx, fy) } }
    }

    /**
     * A 2D exponential exp(−r/λ)/(2πλ²) as spektrafilm samples it on the pixel grid: (sum, MTF).
     * Large λ: the integral (1) and the Hankel transform (1 + (2πλ|f|)²)^(−3/2); small: the sum over the grid.
     */
    private fun sampledExp(lambda: Double): Pair<Double, (Double, Double) -> Double> {
        if (lambda >= 3.0) return 1.0 to { fx, fy -> val a = 2.0 * PI * lambda * sqrt(fx * fx + fy * fy); (1.0 + a * a).pow(-1.5) }
        val r = ceil(8 * max(lambda, 0.05)).toInt().coerceAtLeast(2)
        val n = 2 * r + 1
        val psf = Array(n) { yi -> DoubleArray(n) { xi -> val x = (xi - r).toDouble(); val y = (yi - r).toDouble(); exp(-sqrt(x * x + y * y) / lambda) / (2 * PI * lambda * lambda) } }
        val sum = psf.sumOf { it.sum() }
        return sum to { fx, fy ->
            var v = 0.0
            for (yi in 0 until n) { val cy = cos(2 * PI * fy * (yi - r)); var row = 0.0; for (xi in 0 until n) row += psf[yi][xi] * cos(2 * PI * fx * (xi - r)); v += row * cy }
            v
        }
    }

    /** The camera's steps on the exposure: diffusion filter, lens, scatter, halation (spektrafilm's order). */
    fun cameraKernel(f: Film, sp: Spektra.Spatial, pixelUm: Double): Kernel {
        var k = diffusionKernel(f.diffusion, f.diffusionStrength, pixelUm)
        if (f.lensBlur > 0f) {
            val g = spkGauss(f.lensBlur / pixelUm)
            k = k * Kernel(Array(3) { g }, f.lensBlur / pixelUm)
        }
        val s = f.scatter.toDouble()
        val ss = f.scatterSize.toDouble()
        if (s > 0 && (sp.scatterCoreUm.any { it > 0 } || sp.scatterTailUm.any { it > 0 })) {
            k = k * Kernel(Array(3) { c ->
                val core = spkGauss(max(sp.scatterCoreUm[c] * ss / pixelUm, 1e-6)); val tail = spkExp3(max(sp.scatterTailUm[c] * ss / pixelUm, 1e-6)); val w = sp.scatterTailWeight[c]
                val fn: (Double, Double) -> Double = { fx, fy -> (1 - s) + s * ((1 - w) * core(fx, fy) + w * tail(fx, fy)) }
                fn
            }, sp.scatterTailUm.max() * ss / pixelUm * 2.77)
        }
        val n = sp.bounces
        val amount = f.halation.toDouble()
        if (n >= 1 && amount > 0 && sp.halationStrength.any { it > 0 } && sp.halationSigmaUm.any { it > 0 }) {
            val decay = DoubleArray(n) { sp.bounceDecay.pow(it) }.let { d -> val t = d.sum(); DoubleArray(n) { d[it] / t } }
            k = k * Kernel(Array(3) { c ->
                val a = sp.halationStrength[c] * amount
                val g = (0 until n).map { i -> spkGauss(max(sp.halationSigmaUm[c] * f.halationSize / pixelUm * sqrt(i + 1.0), 1e-6)) }
                val fn: (Double, Double) -> Double = { fx, fy ->
                    var b = 0.0; for (i in 0 until n) b += decay[i] * g[i](fx, fy)
                    val v = 1.0 + a * b
                    if (sp.halationRenormalize) v / (1.0 + a) else v
                }
                fn
            }, sp.halationSigmaUm.max() * f.halationSize / pixelUm * sqrt(n.toDouble()))
        }
        return k
    }

    /**
     * A diffusion filter ([FAMILIES] key, null = none) with strength [strength] – in front of the camera lens or
     * in the enlarger (spektrafilm's apply_diffusion_filter_um, the same in both).
     */
    fun diffusionKernel(family: String?, strength: Float, pixelUm: Double): Kernel {
        var k = Kernel.ID
        val fam = family?.let { key -> FAMILIES.firstOrNull { it.key == key } }
        if (fam != null) {
            val p = scatterFraction(strength.toDouble(), fam)
            if (p > 0) {
                val (cl, cw) = expand(fam.core, false); val (hl, hw) = expand(fam.halo, false); val (bl, bw) = expand(fam.bloom, true)
                val hc = haloChannels(hw, fam.warmth)
                val px = 1.0 / pixelUm
                val ce = cl.map { sampledExp(it * px) }; val he = hl.map { sampledExp(it * px) }; val be = bl.map { sampledExp(it * px) }
                k = k * Kernel(Array(3) { c ->
                    // the kernel is normalized as spektrafilm does: by its sampled sum
                    var total = 0.0
                    for (i in cl.indices) total += fam.wc * cw[i] * ce[i].first
                    for (i in hl.indices) total += fam.wh * hc[c][i] * he[i].first
                    for (i in bl.indices) total += fam.wb * bw[i] * be[i].first
                    val fn: (Double, Double) -> Double = { fx, fy ->
                        var s = 0.0
                        for (i in cl.indices) s += fam.wc * cw[i] * ce[i].second(fx, fy)
                        for (i in hl.indices) s += fam.wh * hc[c][i] * he[i].second(fx, fy)
                        for (i in bl.indices) s += fam.wb * bw[i] * be[i].second(fx, fy)
                        (1 - p) + p * s / total
                    }
                    fn
                }, 3.0 * bl.max() * px)
            }
        }
        return k
    }

    /** The DIR couplers' diffusion (Gaussian + exponential tail), the same for all channels. Null = none. */
    fun couplerKernel(sp: Spektra.Spatial, pixelUm: Double): Kernel? {
        if (sp.couplerDiffusionUm <= 0) return null
        val g = spkGauss(sp.couplerDiffusionUm / pixelUm); val t = spkExp3(sp.couplerTailUm / pixelUm); val w = sp.couplerTailWeight
        val fn: (Double, Double) -> Double = { fx, fy -> (1 - w) * g(fx, fy) + w * t(fx, fy) }
        return Kernel(Array(3) { fn }, max(sp.couplerDiffusionUm, sp.couplerTailUm * 2.77) / pixelUm)
    }

    // ------------------------------------------------------------------------------------------- the pyramid

    /**
     * The image blurred with σ = 0.5, 0.71, 1, 1.41, 2, 2.83, 4, 5.66 … pixels (half octaves). Up to σ = 2.83 at
     * full resolution; after that the resolution is halved for every octave (σ = 2 and 2.83 respectively in the
     * level's own pixels). The kernel becomes a weighted sum of the image itself and the elements; the sum is built
     * bottom-up (coarsest level first, upscaled bilinearly level by level). Edges are mirrored, like spektrafilm.
     */
    object Pyramid {
        /** An element: σ (full resolution), level (resolution 2^−k), parent (−1 = the image), halve first, blur. */
        class El(val sigma: Double, val k: Int, val parent: Int, val decimate: Boolean, val inc: Double)

        val ELS: List<El> = ArrayList<El>().apply {
            val r2 = sqrt(2.0)
            // full resolution: each one directly from the image (then no intermediate images at full resolution are needed)
            add(El(0.5, 0, -1, false, 0.5))
            add(El(sqrt(0.5), 0, -1, false, sqrt(0.5)))
            add(El(1.0, 0, -1, false, 1.0))
            add(El(r2, 0, -1, false, r2))
            add(El(2.0, 0, -1, false, 2.0))
            add(El(2 * r2, 0, -1, false, 2 * r2))
            var chain = 4; var s = 2.0; var k = 0
            while (s < 4096) {
                s *= 2; k++
                // halving (2×2 mean: variance 1/16 level pixel²) and then the rest up to σ (2 level pixels)
                add(El(s, k, chain, true, sqrt(3.0 - 0.0625)))
                chain = size - 1
                add(El(s * r2, k, chain, false, 2.0))
            }
        }

        fun dims(w: Int, h: Int, k: Int): IntArray { var a = w; var b = h; repeat(k) { a = (a + 1) / 2; b = (b + 1) / 2 }; return intArrayOf(a, b) }

        /** The elements the image can hold: σ at most a quarter of the short side and the level at least 2×2. */
        fun usable(w: Int, h: Int): Int {
            var n = 0
            for (e in ELS) { val d = dims(w, h, e.k); if (e.k > 0 && (e.sigma > min(w, h) / 4.0 || d[0] < 2 || d[1] < 2)) break; n++ }
            return n
        }

        /** The element's MTF: exact at full resolution (the separable filters' DTFT through the chain), otherwise Gaussian. */
        fun mtf(e: Int, fx: Double, fy: Double): Double {
            val el = ELS[e]
            if (el.k > 0) return gauss2(el.sigma, fx, fy)
            var v = 1.0; var i = e
            while (i >= 0) { v *= tapsMtf(TAPS[i], fx) * tapsMtf(TAPS[i], fy); i = ELS[i].parent }
            return v
        }

        val TAPS: List<FloatArray> = ELS.map { taps(it.inc) }

        /** Sample weights for a Gaussian kernel (radius ⌈3σ⌉, normalized). */
        fun taps(sigma: Double): FloatArray {
            val r = ceil(3 * sigma).toInt().coerceAtLeast(1)
            val k = FloatArray(2 * r + 1) { exp(-0.5 * ((it - r) / sigma).pow(2)).toFloat() }
            val s = k.sum()
            for (i in k.indices) k[i] /= s
            return k
        }

        fun reflect(i0: Int, n: Int): Int {
            if (i0 in 0 until n) return i0
            val p = 2 * n
            var i = i0 % p
            if (i < 0) i += p
            return if (i >= n) p - 1 - i else i
        }

        /** Separable Gaussian with mirrored edges. */
        fun blur(src: FloatArray, w: Int, h: Int, k: FloatArray): FloatArray {
            val r = k.size / 2
            val tmp = FloatArray(src.size)
            val out = FloatArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0f; var b = 0f; var c = 0f
                for (t in k.indices) { val i = (y * w + reflect(x + t - r, w)) * 3; val kv = k[t]; a += src[i] * kv; b += src[i + 1] * kv; c += src[i + 2] * kv }
                val o = (y * w + x) * 3; tmp[o] = a; tmp[o + 1] = b; tmp[o + 2] = c
            }
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0f; var b = 0f; var c = 0f
                for (t in k.indices) { val i = (reflect(y + t - r, h) * w + x) * 3; val kv = k[t]; a += tmp[i] * kv; b += tmp[i + 1] * kv; c += tmp[i + 2] * kv }
                val o = (y * w + x) * 3; out[o] = a; out[o + 1] = b; out[o + 2] = c
            }
            return out
        }

        /** Halving: mean of 2×2 (like a bilinear read midway between four pixels on the GPU). */
        fun half(src: FloatArray, w: Int, h: Int): FloatArray {
            val nw = (w + 1) / 2; val nh = (h + 1) / 2
            val out = FloatArray(nw * nh * 3)
            for (y in 0 until nh) for (x in 0 until nw) {
                val x0 = 2 * x; val x1 = reflect(2 * x + 1, w); val y0 = 2 * y; val y1 = reflect(2 * y + 1, h)
                for (c in 0..2) out[(y * nw + x) * 3 + c] = 0.25f * (src[(y0 * w + x0) * 3 + c] + src[(y0 * w + x1) * 3 + c] + src[(y1 * w + x0) * 3 + c] + src[(y1 * w + x1) * 3 + c])
            }
            return out
        }

        /** Bilinear upscaling by one level (texel center to texel center, mirrored edges). */
        fun up(src: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray {
            val out = FloatArray(w * h * 3)
            for (y in 0 until h) {
                val v = (y + 0.5) * 0.5 - 0.5
                val yi = floor(v).toInt(); val ty = (v - yi).toFloat()
                val ya = reflect(yi, sh); val yb = reflect(yi + 1, sh)
                for (x in 0 until w) {
                    val u = (x + 0.5) * 0.5 - 0.5
                    val xi = floor(u).toInt(); val tx = (u - xi).toFloat()
                    val xa = reflect(xi, sw); val xb = reflect(xi + 1, sw)
                    val o = (y * w + x) * 3
                    for (c in 0..2) {
                        val a = src[(ya * sw + xa) * 3 + c]; val b = src[(ya * sw + xb) * 3 + c]
                        val cc = src[(yb * sw + xa) * 3 + c]; val d = src[(yb * sw + xb) * 3 + c]
                        val top = a + (b - a) * tx; val bot = cc + (d - cc) * tx
                        out[o + c] = top + (bot - top) * ty
                    }
                }
            }
            return out
        }

        /** The elements needed for the weights (and their parents). */
        fun needed(fit: Fit): BooleanArray {
            val need = BooleanArray(ELS.size)
            for (e in 0 until fit.count) if ((0..2).any { fit.w[it][e + 1] != 0f }) { var i = e; while (i >= 0 && !need[i]) { need[i] = true; i = ELS[i].parent } }
            return need
        }

        fun build(src: FloatArray, w: Int, h: Int, need: BooleanArray): Array<FloatArray?> {
            val out = arrayOfNulls<FloatArray>(ELS.size)
            for (e in ELS.indices) {
                if (!need[e]) continue
                val el = ELS[e]
                val parent = if (el.parent < 0) src else out[el.parent]!!
                val pd = dims(w, h, if (el.parent < 0) 0 else ELS[el.parent].k)
                val base = if (el.decimate) half(parent, pd[0], pd[1]) else parent
                val d = dims(w, h, el.k)
                out[e] = blur(base, d[0], d[1], TAPS[e])
            }
            return out
        }

        fun combine(src: FloatArray, lv: Array<FloatArray?>, fit: Fit, w: Int, h: Int): FloatArray {
            val maxK = (0 until fit.count).filter { lv[it] != null && (0..2).any { c -> fit.w[c][it + 1] != 0f } }.maxOfOrNull { ELS[it].k } ?: 0
            var acc: FloatArray? = null
            for (k in maxK downTo 0) {
                val d = dims(w, h, k)
                val cur = acc?.let { a -> val p = dims(w, h, k + 1); up(a, p[0], p[1], d[0], d[1]) } ?: FloatArray(d[0] * d[1] * 3)
                for (e in 0 until fit.count) {
                    val l = lv[e] ?: continue
                    if (ELS[e].k != k) continue
                    val w0 = fit.w[0][e + 1]; val w1 = fit.w[1][e + 1]; val w2 = fit.w[2][e + 1]
                    if (w0 == 0f && w1 == 0f && w2 == 0f) continue
                    for (i in 0 until d[0] * d[1]) { cur[i * 3] += l[i * 3] * w0; cur[i * 3 + 1] += l[i * 3 + 1] * w1; cur[i * 3 + 2] += l[i * 3 + 2] * w2 }
                }
                if (k == 0) for (i in 0 until w * h) { cur[i * 3] += src[i * 3] * fit.w[0][0]; cur[i * 3 + 1] += src[i * 3 + 1] * fit.w[1][0]; cur[i * 3 + 2] += src[i * 3 + 2] * fit.w[2][0] }
                acc = cur
            }
            return acc!!
        }

        /**
         * The kernel on the image. If coarser levels are used, the image is first mirrored out to a size divisible by 2^k
         * (then every halving is exact and no light is lost at the edges – mirroring is also spektrafilm's edge mode).
         */
        fun apply(src: FloatArray, w: Int, h: Int, fit: Fit): FloatArray {
            if (fit.isIdentity) return src
            val need = needed(fit)
            val step = 1 shl padShift(fit)
            val pw = (w + step - 1) / step * step; val ph = (h + step - 1) / step * step
            if (pw == w && ph == h) return combine(src, build(src, w, h, need), fit, w, h)
            val pad = FloatArray(pw * ph * 3)
            for (y in 0 until ph) { val sy = reflect(y, h); for (x in 0 until pw) { val sx = reflect(x, w); for (c in 0..2) pad[(y * pw + x) * 3 + c] = src[(sy * w + sx) * 3 + c] } }
            val big = combine(pad, build(pad, pw, ph, need), fit, pw, ph)
            val out = FloatArray(w * h * 3)
            for (y in 0 until h) System.arraycopy(big, y * pw * 3, out, y * w * 3, w * 3)
            return out
        }

        /** The coarsest level the weights use (the image is mirrored out to a multiple of 2^that). */
        fun padShift(fit: Fit): Int = (0 until fit.count).filter { e -> (0..2).any { fit.w[it][e + 1] != 0f } }.maxOfOrNull { ELS[it].k } ?: 0
    }

    // ------------------------------------------------------------------------------------------- the fit

    /** The weights per channel: [channel][0 = the image itself, 1 + element]; [count] elements are used. */
    class Fit(val count: Int, val w: Array<FloatArray>, val maxError: Double) {
        val isIdentity get() = (0..2).all { c -> kotlin.math.abs(w[c][0] - 1f) < 1e-6f && (1..count).all { w[c][it] == 0f } }
        companion object { val ID = Fit(0, Array(3) { floatArrayOf(1f) }, 0.0) }
    }

    /** The frequencies (fx, fy) at which the MTF is compared: a grid in the plane, denser at low ones (where the wide kernels show). */
    private val FREQ1 = DoubleArray(30) { val t = it / 29.0; 0.5 * t * t }
    private val FX = DoubleArray(FREQ1.size * FREQ1.size) { FREQ1[it % FREQ1.size] }
    private val FY = DoubleArray(FREQ1.size * FREQ1.size) { FREQ1[it / FREQ1.size] }

    /** The kernel as weights over the pyramid's elements (those needed and that the image can hold). */
    fun fit(k: Kernel, w: Int, h: Int, exclude: Set<Int> = emptySet()): Fit {
        if (k === Kernel.ID) return Fit.ID
        val usable = Pyramid.usable(w, h)
        // elements up to ~4 times the kernel's largest scale (the small full-resolution ones always)
        val count = (0 until usable).lastOrNull { e -> Pyramid.ELS[e].k == 0 || Pyramid.ELS[e].sigma <= 4 * k.scalePx + 2 }?.plus(1) ?: 0
        val cols = count + 1
        val m = FX.size
        // (excluded elements: column zero – they never get any weight)
        val a = Array(m) { r -> DoubleArray(cols) { j -> if (j == 0) 1.0 else if ((j - 1) in exclude) 0.0 else Pyramid.mtf(j - 1, FX[r], FY[r]) } }
        // f = 0 weighs heavily: the energy (the kernel's sum) must match
        val rowW = DoubleArray(m) { if (it == 0) 30.0 else 1.0 }
        val out = Array(3) { FloatArray(cols) }
        var maxErr = 0.0
        for (c in 0..2) {
            val b = DoubleArray(m) { k.mtf[c](FX[it], FY[it]) }
            val aw = Array(m) { r -> DoubleArray(cols) { a[r][it] * rowW[r] } }
            val bw = DoubleArray(m) { b[it] * rowW[it] }
            val x = nnls(aw, bw)
            val sum = x.sum()
            if (sum > 0) for (j in x.indices) x[j] *= b[0] / sum
            for (j in 0 until cols) out[c][j] = if (x[j] < 1e-5) 0f else x[j].toFloat()
            // (rounded-off small weights are added to the image itself, so the sum is exact)
            val rest = (b[0] - out[c].sum()).toFloat()
            out[c][0] += rest
            for (r in 0 until m) { var v = 0.0; for (j in 0 until cols) v += a[r][j] * out[c][j]; maxErr = max(maxErr, kotlin.math.abs(v - b[r])) }
        }
        return Fit(count, out, maxErr)
    }

    /**
     * Like [fit], but with as few expensive elements as possible: full-resolution elements (two full-frame passes each) and
     * the coarsest level (it decides how much the image is padded) are removed one at a time as long as the error stays
     * below [tol] (or below the full fit's error, if that is larger).
     */
    fun fitLean(k: Kernel, w: Int, h: Int, tol: Double = 0.002): Fit {
        var best = fit(k, w, h)
        if (best.count == 0) return best
        val limit = max(tol, best.maxError)
        val excluded = HashSet<Int>()
        fun used(f: Fit) = (0 until f.count).filter { e -> (0..2).any { f.w[it][e + 1] != 0f } }
        var changed = true
        while (changed) {
            changed = false
            val u = used(best)
            val top = u.maxOfOrNull { Pyramid.ELS[it].k } ?: 0
            // most expensive first: full resolution (smallest weight first), then the coarsest level
            val cands = u.filter { Pyramid.ELS[it].k == 0 }.sortedBy { e -> (0..2).sumOf { best.w[it][e + 1].toDouble() } } +
                u.filter { top > 0 && Pyramid.ELS[it].k == top }
            for (e in cands) {
                val f = fit(k, w, h, excluded + e)
                if (f.maxError <= limit) { excluded += e; best = f; changed = true; break }
            }
        }
        return best
    }

    /** Lawson–Hanson: min ‖Ax − b‖ with x ≥ 0. */
    fun nnls(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val m = a.size; val n = a[0].size
        val x = DoubleArray(n)
        val passive = BooleanArray(n)
        fun grad(): DoubleArray {
            val r = DoubleArray(m) { i -> b[i] - (0 until n).sumOf { a[i][it] * x[it] } }
            return DoubleArray(n) { j -> (0 until m).sumOf { a[it][j] * r[it] } }
        }
        fun solvePassive(): DoubleArray {
            val idx = (0 until n).filter { passive[it] }
            val k = idx.size
            val ata = Array(k) { p -> DoubleArray(k) { q -> (0 until m).sumOf { a[it][idx[p]] * a[it][idx[q]] } + if (p == q) 1e-12 else 0.0 } }
            val atb = DoubleArray(k) { p -> (0 until m).sumOf { a[it][idx[p]] * b[it] } }
            val z = solve(ata, atb)
            val s = DoubleArray(n)
            for (p in 0 until k) s[idx[p]] = z[p]
            return s
        }
        var iter = 0
        while (iter++ < 3 * n + 30) {
            val g = grad()
            var j = -1; var best = 1e-12
            for (t in 0 until n) if (!passive[t] && g[t] > best) { best = g[t]; j = t }
            if (j < 0) break
            passive[j] = true
            var inner = 0
            while (inner++ < 3 * n + 30) {
                val s = solvePassive()
                if ((0 until n).filter { passive[it] }.all { s[it] > 1e-14 }) { for (t in 0 until n) x[t] = s[t]; break }
                var alpha = 1.0
                for (t in 0 until n) if (passive[t] && s[t] <= 1e-14) alpha = min(alpha, x[t] / (x[t] - s[t]))
                for (t in 0 until n) x[t] += alpha * (s[t] - x[t])
                for (t in 0 until n) if (passive[t] && x[t] <= 1e-12) { passive[t] = false; x[t] = 0.0 }
            }
        }
        return x
    }

    private fun solve(m0: Array<DoubleArray>, b0: DoubleArray): DoubleArray {
        val n = b0.size
        val m = Array(n) { m0[it].copyOf() }; val b = b0.copyOf()
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (kotlin.math.abs(m[r][c]) > kotlin.math.abs(m[p][c])) p = r
            val tr = m[c]; m[c] = m[p]; m[p] = tr; val tb = b[c]; b[c] = b[p]; b[p] = tb
            val d = m[c][c]
            if (kotlin.math.abs(d) < 1e-300) continue
            for (r in c + 1 until n) { val f = m[r][c] / d; if (f != 0.0) { for (k in c until n) m[r][k] -= f * m[c][k]; b[r] -= f * b[c] } }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) { var s = b[r]; for (k in r + 1 until n) s -= m[r][k] * x[k]; x[r] = if (kotlin.math.abs(m[r][r]) < 1e-300) 0.0 else s / m[r][r] }
        return x
    }

    // ------------------------------------------------------------------------------------------- preparation

    const val LUT_N = 33
    const val CURVE_N = 1024
    const val LAYER_N = 256
    const val DECODE_N = 4096

    /**
     * Everything that depends only on the settings (not on the image): the input decoding, the exposure LUT, the curves,
     * the grain layers, the print LUT and the scan. The same tables for the GPU and the CPU.
     */
    class Setup(val f: Film) {
        private val tables = tablesFor(f)
        val model = tables.model
        val data = model.film
        val sp = data.spatial
        val positive = data.positive
        val printing = model.print != null

        /** Input code value (0..1) → linear. */
        val decode = FloatArray(DECODE_N) { OpenDrt.oetf(it / (DECODE_N - 1f), model.input.oetf).let { v -> if (v.isNaN()) 0f else max(v, 0f) } }

        /** The largest linear value an input code value gives ([decode] at 1). */
        val linMax = decode.max().coerceAtLeast(1e-6f)

        /**
         * Linear → input code value, [decode] inverted: index u = (linear / [linMax])^¼ (fine steps near black, where
         * log curves are steep). Only for [Film.effectsOnly].
         */
        val encode: FloatArray by lazy {
            FloatArray(ENCODE_N) { i ->
                val target = linMax * (i / (ENCODE_N - 1f)).let { it * it * it * it }
                var lo = 0f; var hi = 1f
                repeat(30) { val m = (lo + hi) / 2f; if (curve1(decode, m) < target) lo = m else hi = m }
                (lo + hi) / 2f
            }
        }

        /**
         * The exposure per color: the film's raw exposure for linear RGB normalized to largest channel = 1 (everything else
         * follows from the exposure being linear in light: E(k·rgb) = k·E(rgb)). Index [(b·N + g)·N + r]·3.
         */
        val lutE: FloatArray get() = tables.lutE
        val evScale = 2.0.pow(f.ev.toDouble()).toFloat()

        // development: log E → density (the curves minus their minimum), and the curves before the DIR couplers
        val leLo = model.leG.minOrNull()!!
        val leHi = model.leG.maxOrNull()!!
        private fun tab(y: DoubleArray) = FloatArray(CURVE_N * 3) { i ->
            val c = i % 3; val v = leLo + (leHi - leLo) * (i / 3) / (CURVE_N - 1.0)
            Spektra.interp(v, model.leG, 1, 0, y, 3, c, model.n).toFloat()
        }
        val curve = tab(model.curves)
        val curve0 = tab(model.curves0)
        val dmax = FloatArray(3) { model.dmaxNorm[it].toFloat() }
        val couplersOn = model.couplersOn
        val couplerM = FloatArray(9) { model.couplerM[it].toFloat() }

        // grain: density → density in each of three sublayers, per channel ([channel][layer][LAYER_N])
        /** The grain sublayers (spektrafilm's sublayers_active; without them one layer with the whole curve). */
        val hasLayers = data.layers.isNotEmpty() && f.grainLayers
        /** The layers' max density [layer][channel] (np.nanmax over the curve). Without layers: one layer with the whole curve. */
        val layerMax: Array<DoubleArray> = Array(3) { l ->
            DoubleArray(3) { c ->
                if (!hasLayers) { if (l == 0) model.dmaxNorm[c] else 0.0 }
                else (0 until model.n).maxOf { j -> data.layers[j * 9 + l * 3 + c].let { v -> if (v.isNaN()) Double.NEGATIVE_INFINITY else v } }
            }
        }
        val layerTab = FloatArray(3 * 3 * LAYER_N).also { o ->
            for (c in 0..2) for (l in 0..2) for (i in 0 until LAYER_N) {
                val d = model.dmaxNorm[c] * i / (LAYER_N - 1.0)
                o[(c * 3 + l) * LAYER_N + i] = when {
                    !hasLayers -> if (l == 0) d.toFloat() else 0f
                    positive -> Spektra.interp(-d, DoubleArray(model.n) { -model.curves[it * 3 + c] }, 1, 0, data.layers, 9, l * 3 + c, model.n).toFloat()
                    else -> Spektra.interp(d, model.curves, 3, c, data.layers, 9, l * 3 + c, model.n).toFloat()
                }
            }
        }
        val uniformity = FloatArray(3) { sp.uniformity[it].toFloat() }
        /**
         * The dye cloud size (σ in µm): color film grain is dye clouds ≈ 10–25 µm in diameter, larger the more sensitive
         * the film (ISO from the name: 100 → 12.5 µm, 400 → 20 µm, 800 → 30 µm); σ ≈ diameter / 4.
         */
        val cloudSigmaUm: Double = run {
            val iso = Regex("\\d+").findAll(data.id).map { it.value.toInt() }.filter { it in 6..6400 }.maxOrNull() ?: 200
            (10.0 + 0.025 * iso).coerceIn(10.0, 30.0) / 4.0
        }
        val grainDmin = FloatArray(3) { sp.grainDensityMin[it].toFloat() }

        // printing: the negative's density → the paper's log exposure (LUT), the paper's curves, the scan
        val pLo = DoubleArray(3) { -sp.grainDensityMin[it] }
        val pHi = DoubleArray(3) { Spektra.nanMax(data.curves, it) }
        val lutP: FloatArray? get() = tables.lutP
        /**
         * Added to the print log exposure from [lutP]: the normalization (with the lab's compensation it follows the
         * camera exposure) and the print exposure, relative to the tables' (log10 of the ratio – exactly the same as
         * computing the table with them, except for light below 1e-10).
         */
        val printOffset: Float = if (!printing) 0f else
            log10(Spektra.Model(f).printFactor / model.printFactor * f.printExposure).toFloat()
        val prLo = model.print?.logExposure?.minOrNull() ?: 0.0
        val prHi = model.print?.logExposure?.maxOrNull() ?: 1.0
        /** The paper's curves with the chemistry controls (spektrafilm's s023 morph, [Spektra.printCurves]). */
        val printCurve: FloatArray? = model.print?.let { pr ->
            val curves = Spektra.printCurves(pr, Spektra.Morph(gamma = f.printGamma.toDouble()))
            FloatArray(CURVE_N * 3) { i ->
                val c = i % 3; val v = prLo + (prHi - prLo) * (i / 3) / (CURVE_N - 1.0)
                Spektra.interp(v, pr.logExposure, 1, 0, curves, 3, c, pr.logExposure.size).toFloat()
            }
        }

        /** The scan (sRGB-encoded) as [(z·N + y)·N + x]·3 with x = the first density; [scanLo, scanHi] per channel. */
        val scanN = Spektra.index.scanN
        val scanLo: DoubleArray = model.print?.scanMin ?: data.scanMin!!
        val scanHi: DoubleArray = model.print?.scanMax ?: data.scanMax!!
        val scan: FloatArray = (model.print?.scan ?: data.scan!!).let { src ->
            val n = scanN
            FloatArray(n * n * n * 3) { i ->
                val c = i % 3; val p = i / 3; val x = p % n; val y = (p / n) % n; val z = p / (n * n)
                src[((x * n + y) * n + z) * 3 + c]
            }
        }

        /** The kernels and the grain for an image ([w]×[h] pixels): the pixel's size on the film follows the format. */
        fun forImage(w: Int, h: Int): Frame = synchronized(frames) {
            frames.getOrPut(w to h) { Frame(this, w, h) }
        }
        private val frames = object : LinkedHashMap<Pair<Int, Int>, Frame>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, Frame>?) = size > 4
        }
    }

    /**
     * The heavy tables (the exposure and print LUTs). They do not depend on grain, halation, diffusion,
     * the format, sharpness, the display, the exposure or the print exposure (the last two only shift the print
     * log exposure, see [Setup.printOffset]) – so they are not recomputed when those controls change.
     */
    class Tables(val key: Film) {
        val model = Spektra.Model(key)
        private val printing = model.print != null
        private val pLo = DoubleArray(3) { -model.film.spatial.grainDensityMin[it] }
        private val pHi = DoubleArray(3) { Spektra.nanMax(model.film.curves, it) }
        val lutE = FloatArray(LUT_N * LUT_N * LUT_N * 3).also { o ->
            val rgb = DoubleArray(3)
            for (b in 0 until LUT_N) for (g in 0 until LUT_N) for (r in 0 until LUT_N) {
                rgb[0] = r / (LUT_N - 1.0); rgb[1] = g / (LUT_N - 1.0); rgb[2] = b / (LUT_N - 1.0)
                val e = model.filmRaw(model.toXyz, rgb)
                val i = ((b * LUT_N + g) * LUT_N + r) * 3
                for (c in 0..2) o[i + c] = max(e[c], 0.0).toFloat()
            }
        }
        val lutP: FloatArray? = if (!printing) null else FloatArray(LUT_N * LUT_N * LUT_N * 3).also { o ->
            val d = DoubleArray(3)
            for (z in 0 until LUT_N) for (y in 0 until LUT_N) for (x in 0 until LUT_N) {
                d[0] = pLo[0] + (pHi[0] - pLo[0]) * x / (LUT_N - 1.0)
                d[1] = pLo[1] + (pHi[1] - pLo[1]) * y / (LUT_N - 1.0)
                d[2] = pLo[2] + (pHi[2] - pLo[2]) * z / (LUT_N - 1.0)
                val e = model.logEPrint(d)
                val i = ((z * LUT_N + y) * LUT_N + x) * 3
                for (c in 0..2) o[i + c] = e[c].toFloat()
            }
        }
    }

    /**
     * The tables' key: without exposure, the lab's compensation and the print exposure – they only shift the
     * print log exposure by a constant ([Setup.printOffset]), so dragging them does not recompute the tables.
     */
    private fun Film.tablesKey(): Film = Film(film, print, 0f, 1f, mShift, yShift, gamma, couplers, input, 0, false)

    private val tables = object : LinkedHashMap<Film, Tables>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Film, Tables>?) = size > 4
    }

    private fun tablesFor(f: Film): Tables {
        val k = f.tablesKey()
        return synchronized(tables) { tables[k] } ?: Tables(k).also { t -> synchronized(tables) { tables[k] = t } }
    }

    /** What depends on the image size: the weights for the blurs and the grain count per pixel. */
    class Frame(val s: Setup, val w: Int, val h: Int) {
        val pixelUm = s.f.format * 1000.0 / max(w, h)
        val camera: Fit = fit(cameraKernel(s.f, s.sp, pixelUm), w, h)
        val couplers: Fit? = if (s.couplersOn) couplerKernel(s.sp, pixelUm)?.let { fit(it, w, h) } else null
        val grainOn = s.f.grain
        // per [layer·3 + channel]: grains per pixel (n), the density of one grain (od), the layer's min and max
        val grainN = FloatArray(9); val grainOd = FloatArray(9); val grainLo = FloatArray(9); val grainHi = FloatArray(9)
        init {
            val area = pixelUm * pixelUm
            for (c in 0..2) {
                val total = (0..2).sumOf { s.layerMax[it][c] }.takeIf { it > 0 } ?: 1.0
                for (l in 0..2) {
                    val frac = s.layerMax[l][c] / total
                    val lo = frac * s.sp.grainDensityMin[c]
                    val hi = s.layerMax[l][c] + lo
                    val particle = s.sp.particleAreaUm2 * s.f.grainSize * s.sp.particleScale[c] * (if (s.hasLayers) s.sp.particleScaleLayers[l] else 1.0)
                    val n = area * frac / particle
                    val i = l * 3 + c
                    grainN[i] = n.toFloat(); grainLo[i] = lo.toFloat(); grainHi[i] = hi.toFloat()
                    grainOd[i] = if (n > 0) (hi / n).toFloat() else 0f
                }
            }
        }
        /**
         * The grain blur: the dye cloud size on the film (µm → pixels) together with spektrafilm's 0.65 pixels
         * (the pixel's own filter). The same at all resolutions measured on the film: the proxy and the original get the same grain.
         */
        val grainSigmaPx: Double = sqrt((s.cloudSigmaUm * s.f.grainClouds / pixelUm).pow(2) + s.f.grainBlur.toDouble().pow(2)).coerceAtMost(3.5)
        // (spektrafilm: with sublayers the grain is blurred at any σ > 0, without them only above 0.4 pixels)
        val grainBlur: FloatArray? = if (grainOn && grainSigmaPx > (if (s.hasLayers) 0.0 else 0.4)) smallTaps(grainSigmaPx) else null
        val unsharp: FloatArray? = if (s.f.sharpen > 0f && s.sp.unsharpSigma > 0) smallTaps(s.sp.unsharpSigma) else null
        /** The enlarger's diffusion filter (on the paper's exposure), null = none. */
        val enlarger: Fit? = if (!s.printing || s.f.enlargerDiffusion == null) null else
            diffusionKernel(s.f.enlargerDiffusion, s.f.enlargerDiffusionStrength, pixelUm).let { k -> fitLean(k, w, h).takeUnless { it.isIdentity } }
        /** The scanner's blur (before sharpening), null = none. */
        val scanBlur: FloatArray? = if (s.f.scanBlur > 0f) smallTaps(s.f.scanBlur.toDouble().coerceAtMost(3.5)) else null
    }

    /** spektrafilm's small Gaussian filters (truncate 3: radius int(3σ + 0.5)). */
    fun smallTaps(sigma: Double): FloatArray {
        val r = (3 * sigma + 0.5).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { exp(-0.5 * ((it - r) / sigma).pow(2)).toFloat() }
        val s = k.sum()
        for (i in k.indices) k[i] /= s
        return k
    }

    private val setups = object : LinkedHashMap<Film, Setup>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Film, Setup>?) = size > 4
    }

    /** The preparation for the settings (the same object for the same settings). */
    fun setup(f: Film): Setup = synchronized(setups) { setups[f] } ?: Setup(with(Spektra) { f.forEffects() }).also { s -> synchronized(setups) { setups[f] = s } }

    /** The image sizes playback used most recently (to prepare the kernels in advance, not on the GL thread). */
    private val recentSizes = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Int>>()

    fun noteSize(w: Int, h: Int) {
        val p = w to h
        if (recentSizes.firstOrNull() == p) return
        recentSizes.remove(p); recentSizes.add(0, p)
        while (recentSizes.size > 3) recentSizes.removeAt(recentSizes.size - 1)
    }

    /** Prepare the settings and the kernels for the most recent image sizes (called on a background thread). */
    fun warm(f: Film) {
        val s = setup(f)
        for ((w, h) in recentSizes) s.forImage(w, h)
        // effects only: the film's colors alone, which the GPU divides out (otherwise baked on the GL thread)
        if (f.effectsOnly) Spektra.composite(null, 0f, f)
    }

    fun clearCache() { synchronized(setups) { setups.clear() }; synchronized(tables) { tables.clear() } }

    // ------------------------------------------------------------------------------------------- per pixel

    /** Trilinear read in an N³ LUT ([(z·N + y)·N + x]·3), coordinates already in [0, N−1]. */
    fun tri(lut: FloatArray, n: Int, px: Float, py: Float, pz: Float, out: FloatArray, o: Int) {
        val x0 = min(px.toInt(), n - 2); val y0 = min(py.toInt(), n - 2); val z0 = min(pz.toInt(), n - 2)
        val fx = px - x0; val fy = py - y0; val fz = pz - z0
        for (c in 0..2) {
            fun at(dx: Int, dy: Int, dz: Int) = lut[(((z0 + dz) * n + (y0 + dy)) * n + (x0 + dx)) * 3 + c]
            val c00 = at(0, 0, 0) + (at(1, 0, 0) - at(0, 0, 0)) * fx
            val c10 = at(0, 1, 0) + (at(1, 1, 0) - at(0, 1, 0)) * fx
            val c01 = at(0, 0, 1) + (at(1, 0, 1) - at(0, 0, 1)) * fx
            val c11 = at(0, 1, 1) + (at(1, 1, 1) - at(0, 1, 1)) * fx
            val c0 = c00 + (c10 - c00) * fy
            val c1 = c01 + (c11 - c01) * fy
            out[o + c] = c0 + (c1 - c0) * fz
        }
    }

    /** Linear read in a table with [CURVE_N] values per channel over [lo, hi] (outside: the end value). */
    fun curveAt(tab: FloatArray, n: Int, lo: Double, hi: Double, c: Int, v: Float): Float {
        val p = ((v - lo) / (hi - lo) * (n - 1)).toFloat()
        if (!(p > 0f)) return tab[c]
        if (p >= n - 1) return tab[(n - 1) * 3 + c]
        val i = p.toInt(); val t = p - i
        return tab[i * 3 + c] + (tab[(i + 1) * 3 + c] - tab[i * 3 + c]) * t
    }

    private fun srgbDecode(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    private fun srgbEncode(v: Float): Float = if (v <= 0.0031308f) v * 12.92f else (1.055 * v.toDouble().pow(1 / 2.4) - 0.055).toFloat()

    /** Linear RGB (the input color space) → the film's raw exposure (×2^ev). */
    fun expose(s: Setup, r: Float, g: Float, b: Float, out: FloatArray, o: Int) {
        val m = max(r, max(g, b))
        if (!(m > 1e-12f)) { out[o] = 0f; out[o + 1] = 0f; out[o + 2] = 0f; return }
        val n1 = LUT_N - 1f
        tri(s.lutE, LUT_N, max(r, 0f) / m * n1, max(g, 0f) / m * n1, max(b, 0f) / m * n1, out, o)
        val k = m * s.evScale
        out[o] *= k; out[o + 1] *= k; out[o + 2] *= k
    }

    /** Simple and fast random generator (splitmix64) – deterministic for a given frame. */
    class Rng(seed: Long) {
        private var x = seed
        fun next(): Long { x += -0x61c8864680b583ebL; var z = x; z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L; z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L; return z xor (z ushr 31) }
        fun uniform(): Double = ((next() ushr 11) + 0.5) * (1.0 / (1L shl 53))
        fun normal(): Double = sqrt(-2.0 * ln(uniform())) * cos(2.0 * PI * uniform())
    }

    /** Poisson: exact (Knuth) for small means, otherwise a rounded normal distribution (the same as the GPU's). */
    fun poisson(mu: Double, rng: Rng): Double {
        if (mu <= 0) return 0.0
        if (mu < POISSON_EXACT) {
            val l = exp(-mu); var k = 0; var p = rng.uniform()
            while (p > l && k < 64) { k++; p *= rng.uniform() }
            return k.toDouble()
        }
        return max(0.0, floor(mu + sqrt(mu) * rng.normal() + 0.5))
    }
    const val POISSON_EXACT = 6.0

    /**
     * The whole film on an image in input code values (0..1, [w]·[h]·3) → display code values. [seed] = the frame
     * (grain and glare are re-randomized for each).
     */
    fun applyEncoded(s: Setup, img: FloatArray, w: Int, h: Int, seed: Long): FloatArray {
        val lin = FloatArray(img.size)
        for (i in img.indices) lin[i] = curve1(s.decode, img[i])
        val out = processLinear(s, lin, w, h, seed)
        return if (s.f.effectsOnly) keepColors(s, img, out) else out
    }

    const val ENCODE_N = 4096
    /** Keeps [keepColors]' ratio finite where the film is black. */
    const val KEEP_EPS = 0.002f
    const val KEEP_MAX = 16f

    /**
     * Only the film's effects ([Film.effectsOnly]): the film's output [out] divided by the film's colors alone (the
     * same chain without spatial and random effects, the baked LUT – [Spektra.composite]), in linear light, times the
     * input [img] in its own encoding. Halation, diffusion, grain and glare keep their strength and color as on
     * the film; the colors, the contrast and the encoding stay the input's. As [FilmGl]'s FINAL step.
     */
    fun keepColors(s: Setup, img: FloatArray, out: FloatArray): FloatArray {
        val lut = Spektra.composite(null, 0f, s.f)
        val n = lut.size; val n1 = n - 1f
        val res = FloatArray(img.size)
        val f0 = FloatArray(3)
        for (i in 0 until img.size / 3) {
            val o = i * 3
            trilinear(lut.data, n, img[o].coerceIn(0f, 1f) * n1, img[o + 1].coerceIn(0f, 1f) * n1, img[o + 2].coerceIn(0f, 1f) * n1, f0)
            for (c in 0..2) {
                val l0 = displayDecode(s, f0[c].coerceIn(0f, 1f)); val l1 = displayDecode(s, out[o + c].coerceIn(0f, 1f))
                val r = ((l1 + KEEP_EPS) / (l0 + KEEP_EPS)).coerceIn(0f, KEEP_MAX)
                // as a change in code values: below a log curve's black (linear 0) the code stays, and the table's
                // own rounding cancels out
                val lin = curve1(s.decode, img[o + c])
                res[o + c] = img[o + c] + encodeLinear(s, lin * r) - encodeLinear(s, lin)
            }
        }
        return res
    }

    private fun displayDecode(s: Setup, v: Float) = if (s.f.display == 1) v.toDouble().pow(2.4).toFloat() else srgbDecode(v)

    /** Input code value → linear ([Setup.decode]). */
    fun applyDecode(s: Setup, code: Float): Float = curve1(s.decode, code)

    /** Linear → input code value ([Setup.encode]); above [Setup.linMax] the top code value. */
    fun encodeLinear(s: Setup, lin: Float): Float =
        curve1(s.encode, sqrt(sqrt((max(lin, 0f) / s.linMax).coerceAtMost(1f).toDouble())).toFloat())

    /** A 3D table (index [(b·n + g)·n + r]·3) at fractional indices, trilinear – as the GPU's texture filtering. */
    private fun trilinear(t: FloatArray, n: Int, r: Float, g: Float, b: Float, out: FloatArray) {
        val r0 = min(r.toInt(), n - 2); val g0 = min(g.toInt(), n - 2); val b0 = min(b.toInt(), n - 2)
        val fr = r - r0; val fg = g - g0; val fb = b - b0
        for (c in 0..2) {
            fun at(dr: Int, dg: Int, db: Int) = t[(((b0 + db) * n + (g0 + dg)) * n + (r0 + dr)) * 3 + c]
            val c00 = at(0, 0, 0) + (at(1, 0, 0) - at(0, 0, 0)) * fr
            val c10 = at(0, 1, 0) + (at(1, 1, 0) - at(0, 1, 0)) * fr
            val c01 = at(0, 0, 1) + (at(1, 0, 1) - at(0, 0, 1)) * fr
            val c11 = at(0, 1, 1) + (at(1, 1, 1) - at(0, 1, 1)) * fr
            val c0 = c00 + (c10 - c00) * fg; val c1 = c01 + (c11 - c01) * fg
            out[c] = c0 + (c1 - c0) * fb
        }
    }

    private fun curve1(tab: FloatArray, v: Float): Float {
        val p = v.coerceIn(0f, 1f) * (tab.size - 1)
        val i = min(p.toInt(), tab.size - 2); val t = p - i
        return tab[i] + (tab[i + 1] - tab[i]) * t
    }

    /** The steps one by one (the tests compare with spektrafilm's taps). */
    class Taps(val logEFilm: FloatArray, val cmyFilm: FloatArray, val cmyGrain: FloatArray, val out: FloatArray)

    fun processLinear(s: Setup, lin: FloatArray, w: Int, h: Int, seed: Long): FloatArray = process(s, lin, w, h, seed).out

    fun process(s: Setup, lin: FloatArray, w: Int, h: Int, seed: Long): Taps {
        val fr = s.forImage(w, h)
        val np = w * h
        // 1. the exposure and the camera's blurs (in light, linear)
        val raw0 = FloatArray(np * 3)
        for (i in 0 until np) expose(s, lin[i * 3], lin[i * 3 + 1], lin[i * 3 + 2], raw0, i * 3)
        // highlight boost (before the blurs, normalized by the image's largest raw exposure)
        if (s.f.boostEv > 0f) boost(raw0, s.f, boostRef(raw0, w, h))
        val raw = Pyramid.apply(raw0, w, h, fr.camera)
        val logE = FloatArray(np * 3) { log10(max(raw[it], 0f) + 1e-10f) }
        // 2. development: density without couplers, the couplers' inhibition (diffused), density with the couplers
        val d = FloatArray(np * 3)
        for (i in 0 until np * 3) d[i] = curveAt(s.curve, CURVE_N, s.leLo, s.leHi, i % 3, logE[i])
        if (s.couplersOn) {
            val corr = FloatArray(np * 3)
            for (i in 0 until np) {
                val o = i * 3
                val s0 = if (s.positive) s.dmax[0] - d[o] else d[o]
                val s1 = if (s.positive) s.dmax[1] - d[o + 1] else d[o + 1]
                val s2 = if (s.positive) s.dmax[2] - d[o + 2] else d[o + 2]
                for (m in 0..2) corr[o + m] = s0 * s.couplerM[m] + s1 * s.couplerM[3 + m] + s2 * s.couplerM[6 + m]
            }
            val cb = fr.couplers?.let { Pyramid.apply(corr, w, h, it) } ?: corr
            for (i in 0 until np * 3) d[i] = curveAt(s.curve0, CURVE_N, s.leLo, s.leHi, i % 3, logE[i] - cb[i])
        }
        val cmyNoGrain = d.copyOf()
        // 3. grain
        var dg = d
        if (fr.grainOn) {
            val rng = Rng(seed * 0x9E3779B97F4A7C15uL.toLong() + 1)
            val gtmp = FloatArray(3)
            for (i in 0 until np) for (c in 0..2) {
                val o = i * 3 + c
                val di = d[o]
                var sum = 0.0
                for (l in 0..2) {
                    val k = l * 3 + c
                    val n = fr.grainN[k]
                    if (n <= 0f) continue
                    val dl = layerAt(s, c, l, di) + fr.grainLo[k]
                    val p = (dl / fr.grainHi[k]).toDouble().coerceIn(1e-6, 1 - 1e-6)
                    val sat = 1.0 - p * s.uniformity[c] * (1 - 1e-6)
                    sum += poisson(n * p / sat, rng) * fr.grainOd[k] * sat
                }
                gtmp[c] = (sum - s.grainDmin[c]).toFloat()
                if (c == 2) mixChroma(d, gtmp, i * 3, s.f.grainColor, s.f.grainAmount)
            }
            fr.grainBlur?.let { dg = Pyramid.blur(d, w, h, it) }
        }
        // 4. printing and scanning → linear sRGB
        val rgb = FloatArray(np * 3)
        val tmp = FloatArray(3)
        val n1 = LUT_N - 1f
        val sn1 = s.scanN - 1f
        // the paper's log exposure; with the enlarger's diffusion filter it is blurred (linear light) first
        var pe: FloatArray? = null
        if (s.printing) {
            val e = FloatArray(np * 3)
            for (i in 0 until np) {
                val o = i * 3
                s.lutP?.let { lp ->
                    tri(lp, LUT_N, frac(dg[o], s.pLo[0], s.pHi[0]) * n1, frac(dg[o + 1], s.pLo[1], s.pHi[1]) * n1, frac(dg[o + 2], s.pLo[2], s.pHi[2]) * n1, e, o)
                }
                for (c in 0..2) e[o + c] += s.printOffset
            }
            pe = fr.enlarger?.let { k ->
                val l = FloatArray(e.size) { 10f.pow(e[it]) }
                val b = Pyramid.apply(l, w, h, k)
                FloatArray(b.size) { log10(max(b[it], 0f) + 1e-10f) }
            } ?: e
        }
        for (i in 0 until np) {
            val o = i * 3
            var c0 = dg[o]; var c1 = dg[o + 1]; var c2 = dg[o + 2]
            if (pe != null) {
                c0 = curveAt(s.printCurve!!, CURVE_N, s.prLo, s.prHi, 0, pe[o])
                c1 = curveAt(s.printCurve, CURVE_N, s.prLo, s.prHi, 1, pe[o + 1])
                c2 = curveAt(s.printCurve, CURVE_N, s.prLo, s.prHi, 2, pe[o + 2])
            }
            tri(s.scan, s.scanN, frac(c0, s.scanLo[0], s.scanHi[0]) * sn1, frac(c1, s.scanLo[1], s.scanHi[1]) * sn1, frac(c2, s.scanLo[2], s.scanHi[2]) * sn1, rgb, o)
            for (c in 0..2) rgb[o + c] = srgbDecode(rgb[o + c])
        }
        // 5. glare in the paper (lognormal, per pixel), the scanner's blur and sharpening
        glareLn(s)?.let { (mu, sig) ->
            val rng = Rng(seed * 31 + 7)
            for (i in 0 until np) { val g = (exp(mu + sig * rng.normal()) / 100.0).toFloat(); for (c in 0..2) rgb[i * 3 + c] += g }
        }
        val scanned = fr.scanBlur?.let { Pyramid.blur(rgb, w, h, it) } ?: rgb
        val sharp = fr.unsharp?.let { k ->
            val bl = Pyramid.blur(scanned, w, h, k)
            val a = s.f.sharpen
            FloatArray(scanned.size) { scanned[it] + a * (scanned[it] - bl[it]) }
        } ?: scanned
        val out = FloatArray(np * 3)
        for (i in out.indices) {
            val v = sharp[i].coerceIn(0f, 1f)
            out[i] = if (s.f.display == 1) v.toDouble().pow(1 / 2.4).toFloat() else srgbEncode(v)
        }
        return Taps(logE, cmyNoGrain, dg, out)
    }

    /**
     * Highlight boost (spektrafilm's boost_highlights): above mid gray · 2^protect the raw exposure is raised smoothly
     * so that the largest ([maxRaw]) becomes 2^boost times larger. Modifies [raw] in place.
     */
    fun boost(raw: FloatArray, f: Film, maxRaw: Double) {
        val b = boostParams(f, maxRaw) ?: return
        for (i in raw.indices) {
            val x = raw[i].toDouble()
            if (x > b.x0) { val dx = (x - b.x0) * b.inv; raw[i] = (x + b.scale * (exp(b.a * dx) - b.a * dx - 1.0)).toFloat() }
        }
    }

    /** Above this width the image is averaged (2×2 halvings) before its largest value is taken ([boostRef]). */
    const val BOOST_REF_W = 128

    /**
     * The largest raw exposure the boost is normalized by: the image averaged (2×2 halvings) until it is at most
     * [BOOST_REF_W] wide, then its largest value – as [FilmGl]. spektrafilm takes the single brightest pixel; in video
     * that is noise, different in every frame, and the whole boost (all the highlights) pumped with it.
     */
    fun boostRef(raw: FloatArray, w: Int, h: Int): Double {
        var a = raw; var cw = w; var ch = h
        while (cw > BOOST_REF_W) {
            val d = Pyramid.dims(cw, ch, 1)
            val o = FloatArray(d[0] * d[1] * 3)
            for (y in 0 until d[1]) for (x in 0 until d[0]) for (c in 0..2) {
                var sum = 0f
                for (dy in 0..1) for (dx in 0..1) sum += a[(Pyramid.reflect(2 * y + dy, ch) * cw + Pyramid.reflect(2 * x + dx, cw)) * 3 + c]
                o[(y * d[0] + x) * 3 + c] = sum / 4f
            }
            a = o; cw = d[0]; ch = d[1]
        }
        return (a.maxOrNull() ?: 0f).toDouble()
    }

    class Boost(val x0: Double, val a: Double, val inv: Double, val scale: Double)

    /** The boost constants for the image's largest raw exposure [maxRaw] (null = no boost). */
    fun boostParams(f: Film, maxRaw: Double): Boost? {
        if (f.boostEv <= 0f || !(maxRaw > 0.0)) return null
        val x0 = (0.184 * 2.0.pow(f.protectEv.toDouble())).coerceIn(0.0, maxRaw)
        if (x0 == maxRaw) return null
        val a = 28.0.pow(1.0 - f.boostRange.toDouble().coerceIn(0.0, 1.0))
        val nx0 = x0 / maxRaw
        val denom = exp(a * (1 - nx0)) - a * (1 - nx0) - 1
        if (denom <= 0) return null
        val k = (2.0.pow(f.boostEv.toDouble()) - 1) / denom
        return Boost(x0, a, 1.0 / maxRaw, k * maxRaw)
    }

    /**
     * The glare's lognormal distribution (μ, σ for ln) per pixel, in percent: mean = the amount, spread =
     * roughness · amount. spektrafilm blurs the field with σ = 0.5 pixels – that reduces the spread by
     * [GLARE_BLUR_STD] (same mean), which is applied directly here. Null = no glare.
     */
    fun glareLn(s: Setup): Pair<Double, Double>? {
        if (!s.printing || !s.f.glare || s.f.glarePercent <= 0f) return null
        val m = s.f.glarePercent.toDouble(); val sd = s.f.glareRoughness * m * GLARE_BLUR_STD
        val sig = sqrt(ln(1 + sd * sd / (m * m))); val mu = ln(m) - sig * sig / 2
        return mu to sig
    }

    /** The reduction of the spread when the random field is blurred with σ = 0.5 pixels (√Σk² in two axes, spektrafilm's filter). */
    val GLARE_BLUR_STD: Double = smallTaps(0.5).let { k -> k.sumOf { (it * it).toDouble() } }

    /**
     * Grain color: the noise (grainy − clean density) is pulled toward its mean over the channels by [chroma] (1 = unchanged)
     * and amplified by [amount]. Writes the grainy density to [d] from [o].
     */
    fun mixChroma(d: FloatArray, g: FloatArray, o: Int, chroma: Float, amount: Float = 1f) {
        val n0 = g[0] - d[o]; val n1 = g[1] - d[o + 1]; val n2 = g[2] - d[o + 2]
        val m = (n0 + n1 + n2) / 3f
        d[o] += amount * (m + chroma * (n0 - m)); d[o + 1] += amount * (m + chroma * (n1 - m)); d[o + 2] += amount * (m + chroma * (n2 - m))
    }

    private fun frac(v: Float, lo: Double, hi: Double): Float = ((v - lo) / (hi - lo)).toFloat().let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }

    fun layerAt(s: Setup, c: Int, l: Int, d: Float): Float {
        val dm = s.dmax[c]
        val p = if (dm > 0f) (d / dm).coerceIn(0f, 1f) * (LAYER_N - 1) else 0f
        val i = min(p.toInt(), LAYER_N - 2); val t = p - i
        val base = (c * 3 + l) * LAYER_N
        return s.layerTab[base + i] + (s.layerTab[base + i + 1] - s.layerTab[base + i]) * t
    }
}
