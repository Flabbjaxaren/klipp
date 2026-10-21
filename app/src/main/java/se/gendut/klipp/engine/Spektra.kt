package se.gendut.klipp.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * spektrafilm (Andrea Volpato et al., https://github.com/andreavolpato/spektrafilm, GPL-3.0; profiles CC BY-SA 4.0)
 * – spectral simulation of analog film: light exposes the negative, it is developed (with DIR couplers), printed
 * with the enlarger's filtered light onto paper (or scanned directly, slide film) and scanned.
 *
 * This holds the per-pixel chain, ported line by line from spektrafilm's Python code (runtime/stages, model/develop,
 * model/couplers, utils/spectral_upsampling, utils/fast_interp). What depends only on the film or the paper
 * (spectral upsampling × the film's sensitivity, the scan of the paper with the output's gamut compression) is computed
 * with spektrafilm's own code in tools/spektrafilm/export_tables.py and lives in assets/spektrafilm.
 *
 * Spatial and random effects (halation, grain, diffusion, sharpness, flares) are not included – the chain is baked into
 * a 3D LUT in a color node. The tests (SpektraTest) compare every step with spektrafilm.
 */

/**
 * The settings in a color node – spektrafilm's controls (the parameter's path in spektrafilm in square brackets) with
 * spektrafilm's defaults, and some of Klipp's own (marked "Klipp"). [print] null = the slide film is scanned directly.
 */
data class Film(
    /** Film [selection.film_stock]. */
    val film: String = "kodak_portra_400",
    /** Print paper [selection.print_paper]; null = the slide film is scanned directly. */
    val print: String? = "kodak_portra_endura",
    /** Exposure compensation in stops [camera.exposure_compensation_ev]. (No auto exposure: the video is already exposed.) */
    val ev: Float = 0f,
    /** Print exposure, factor [enlarger.print_exposure] (1 = neutral). */
    val printExposure: Float = 1f,
    /** The enlarger's filters, Kodak CC from neutral [enlarger.m_filter_shift], [enlarger.y_filter_shift]. */
    val mShift: Float = 0f,
    val yShift: Float = 0f,
    /** The negative's gamma [film_render.density_curve_gamma]. */
    val gamma: Float = 1f,
    /** The DIR couplers' amount [film_render.dir_couplers.amount]. */
    val couplers: Float = 1f,
    /** The input color space and encoding [io.input_color_space], [io.input_cctf_decoding] – in Klipp also the cameras' log curves. */
    val input: String = OpenDrt.Input.REC709.key,
    /** Klipp: the display encoding, 0 = sRGB (phone, web), 1 = Rec.1886 2.4 (TV). (spektrafilm: output color space sRGB.) */
    val display: Int = 0,
    /**
     * The print's auto compensation [enlarger.print_exposure_compensation]: the print follows the camera exposure, so
     * the exposure changes the negative (density, grain, contrast) but barely the image's brightness. Off: the exposure
     * makes the image brighter/darker.
     */
    val labComp: Boolean = true,
    /** The film format's long side in mm [camera.film_format_mm]: how large a pixel is on the film. */
    val format: Float = 35f,
    /** Grain [film_render.grain.active]. */
    val grain: Boolean = true,
    /** The grain particle area (× spektrafilm's 0.2 µm²) [film_render.grain.particle_area_um2]. */
    val grainSize: Float = 1f,
    /** Halation amount [film_render.halation.halation_amount]. */
    val halation: Float = 1f,
    /** Scatter amount [film_render.halation.scatter_amount]. */
    val scatter: Float = 1f,
    /** Diffusion filter in front of the lens [camera.diffusion_filter.filter_family] ([FilmFx.DIFFUSION] key), null = off. */
    val diffusion: String? = null,
    /** The filter's strength in grades as on the filters [camera.diffusion_filter.strength] (1/8 … 2). */
    val diffusionStrength: Float = 0.5f,
    /** Lens blur, σ in µm on the film [camera.lens_blur_um]. */
    val lensBlur: Float = 0f,
    /** The scanner's sharpening, amount [scanner.unsharp_mask][1] (σ 0.7 pixels). */
    val sharpen: Float = 0.7f,
    /** Glare in the paper [print_render.glare.active]. */
    val glare: Boolean = true,
    /** Klipp: the grain's dye clouds (× the film's size, ≈ 10–25 µm). 0 = off (spektrafilm). */
    val grainClouds: Float = 0f,
    /** Klipp: grain color, 1 = color layers fully independent (spektrafilm), 0 = luminance only. */
    val grainColor: Float = 1f,
    /** Klipp: grain amount, amplifies the grain's deviation in density (1 = spektrafilm). */
    val grainAmount: Float = 1f,
    /** Halation size [film_render.halation.halation_spatial_scale] (× the first bounce's 65/50 µm). */
    val halationSize: Float = 1f,
    /** Scatter size [film_render.halation.scatter_spatial_scale]. */
    val scatterSize: Float = 1f,
    /** Highlight boost in stops [film_render.halation.boost_ev] (0 = off). */
    val boostEv: Float = 0f,
    /** Protected range above mid gray in stops [film_render.halation.protect_ev]. */
    val protectEv: Float = 4f,
    /** How quickly the boost ramps up, 0–1 [film_render.halation.boost_range]. */
    val boostRange: Float = 0.3f,
    /** Grain blur, σ in pixels [film_render.grain.blur]. */
    val grainBlur: Float = 0.65f,
    /** Grain sublayers [film_render.grain.sublayers_active]. */
    val grainLayers: Boolean = true,
    /** Glare amount in percent [print_render.glare.percent]. */
    val glarePercent: Float = 0.03f,
    /** Glare roughness, 0–1 [print_render.glare.roughness]. */
    val glareRoughness: Float = 0.7f,
    /** The paper's gamma (the chemistry) [print_render.density_curves_morph.gamma_factor]. */
    val printGamma: Float = 1f,
    /** Diffusion filter in the enlarger [enlarger.diffusion_filter.filter_family], null = off. */
    val enlargerDiffusion: String? = null,
    /** The enlarger filter's strength [enlarger.diffusion_filter.strength]. */
    val enlargerDiffusionStrength: Float = 0.5f,
    /** The scanner's blur, σ in pixels [scanner.lens_blur]. */
    val scanBlur: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject().put("film", film).put("print", print ?: JSONObject.NULL)
        .put("ev", ev.toDouble()).put("pe", printExposure.toDouble()).put("m", mShift.toDouble()).put("y", yShift.toDouble())
        .put("gamma", gamma.toDouble()).put("dir", couplers.toDouble()).put("in", input).put("disp", display)
        .put("lab", labComp).put("fmt", format.toDouble()).put("grain", grain).put("gsz", grainSize.toDouble())
        .put("hal", halation.toDouble()).put("sct", scatter.toDouble()).put("dif", diffusion ?: JSONObject.NULL)
        .put("difS", diffusionStrength.toDouble()).put("lens", lensBlur.toDouble()).put("shp", sharpen.toDouble()).put("glare", glare)
        .put("gcl", grainClouds.toDouble()).put("gcol", grainColor.toDouble())
        .put("gamt", grainAmount.toDouble()).put("hsz", halationSize.toDouble())
        .put("ssz", scatterSize.toDouble()).put("bev", boostEv.toDouble()).put("pev", protectEv.toDouble()).put("brg", boostRange.toDouble())
        .put("gbl", grainBlur.toDouble()).put("glay", grainLayers).put("glp", glarePercent.toDouble()).put("glr", glareRoughness.toDouble())
        .put("pgam", printGamma.toDouble()).put("edif", enlargerDiffusion ?: JSONObject.NULL).put("edifS", enlargerDiffusionStrength.toDouble())
        .put("sbl", scanBlur.toDouble()).put("v", 3)

    companion object {
        const val HALATION_MAX = 30f
        const val HALATION_SIZE_MAX = 6f

        fun fromJson(o: JSONObject?): Film? {
            o ?: return null
            val d = Film()
            fun num(k: String, def: Float, lo: Float, hi: Float) = o.optDouble(k, def.toDouble()).toFloat().let { if (it.isNaN()) def else it.coerceIn(lo, hi) }
            return Film(
                o.optString("film", d.film).ifBlank { d.film },
                if (o.isNull("print")) null else o.optString("print", d.print!!).ifBlank { d.print },
                num("ev", 0f, -6f, 6f),
                num("pe", 1f, 0.1f, 8f),
                num("m", 0f, -200f, 200f),
                num("y", 0f, -200f, 200f),
                num("gamma", 1f, 0.3f, 3f),
                // above 1 the pre-coupler curves become invalid for some films (see SpektraTest)
                num("dir", 1f, 0f, 1f),
                o.optString("in", d.input).takeIf { k -> OpenDrt.Input.entries.any { it.key == k } } ?: d.input,
                o.optInt("disp", 0).coerceIn(0, 1),
                // (saved projects always have the key; without it: as before, off)
                o.optBoolean("lab", false),
                num("fmt", 35f, 2f, 120f),
                o.optBoolean("grain", true),
                num("gsz", 1f, 0.1f, 10f),
                num("hal", 1f, 0f, HALATION_MAX),
                num("sct", 1f, 0f, 3f),
                if (o.isNull("dif")) null else o.optString("dif").takeIf { it in FilmFx.DIFFUSION },
                num("difS", 0.5f, 0.125f, 2f),
                num("lens", 0f, 0f, 200f),
                // (version 1 saved spektrafilm's 0.7 as the default)
                if (o.optInt("v", 1) < 2) 0.7f else num("shp", d.sharpen, 0f, 3f),
                o.optBoolean("glare", true),
                // (without the key: from before dye clouds – none)
                num("gcl", 0f, 0f, 3f),
                // (without the key: from before grain color – spektrafilm's)
                num("gcol", 1f, 0f, 1f),
                num("gamt", 1f, 0f, 10f),
                num("hsz", 1f, 0.25f, HALATION_SIZE_MAX),
                num("ssz", 1f, 0.1f, 6f),
                num("bev", 0f, 0f, 20f),
                num("pev", 4f, 0f, 10f),
                num("brg", 0.3f, 0f, 1f),
                num("gbl", 0.65f, 0f, 3f),
                o.optBoolean("glay", true),
                num("glp", 0.03f, 0f, 1f),
                num("glr", 0.7f, 0f, 1f),
                num("pgam", 1f, 0.5f, 2f),
                if (o.isNull("edif")) null else o.optString("edif").takeIf { it in FilmFx.DIFFUSION },
                num("edifS", 0.5f, 0.125f, 2f),
                num("sbl", 0f, 0f, 5f),
            )
        }
    }
}

object Spektra {
    /** 380–780 nm in steps of 5 nm (spektrafilm's SPECTRAL_SHAPE). */
    const val WL = 81
    private const val K = 3

    class StockInfo(val id: String, val name: String, val type: String, val use: String?, val targetPrint: String?, val support: String?)

    class Index(
        val films: List<StockInfo>,
        val prints: List<StockInfo>,
        val scanN: Int,
        /** The enlarger lamp (TH-KG3) and the dichroic filters C, M, Y ([l*3+k]). */
        val enlarger: DoubleArray,
        val dichroic: DoubleArray,
        /** paper → film → neutral filters (C, M, Y in CC) for the enlarger lamp TH-KG3. */
        val neutral: Map<String, Map<String, DoubleArray>>,
    )

    class FilmData(
        val id: String, val type: String,
        val tcSize: Int, val tc: FloatArray,
        val logExposure: DoubleArray, val curves: DoubleArray,
        val channelDensity: DoubleArray, val baseDensity: DoubleArray,
        val refXy: DoubleArray, val m709: DoubleArray, val mSrgb: DoubleArray,
        val couplerSelf: DoubleArray, val couplerR: DoubleArray, val couplerG: DoubleArray, val couplerB: DoubleArray,
        val inhSelf: Double, val inhInter: Double,
        val scan: FloatArray?, val scanMin: DoubleArray?, val scanMax: DoubleArray?,
        /** The grain sublayers: the density curves per layer, index [le·9 + layer·3 + channel] (empty = missing). */
        val layers: DoubleArray = DoubleArray(0),
        /** spektrafilm's spatial and random parameters for the film (after digest_params). */
        val spatial: Spatial = Spatial(),
    ) { val positive get() = type == "positive" }

    /** Halation, scatter, grain, DIR coupler diffusion, glare and the scanner's sharpening (spektrafilm's defaults). */
    class Spatial(
        val scatterCoreUm: DoubleArray = doubleArrayOf(2.2, 2.0, 1.6),
        val scatterTailUm: DoubleArray = doubleArrayOf(9.3, 9.7, 9.1),
        val scatterTailWeight: DoubleArray = doubleArrayOf(0.78, 0.65, 0.67),
        val halationStrength: DoubleArray = doubleArrayOf(0.015, 0.005, 0.0),
        val halationSigmaUm: DoubleArray = doubleArrayOf(65.0, 65.0, 65.0),
        val bounces: Int = 3,
        val bounceDecay: Double = 0.5,
        val halationRenormalize: Boolean = true,
        val particleAreaUm2: Double = 0.2,
        val particleScale: DoubleArray = doubleArrayOf(1.6, 1.6, 3.2),
        val particleScaleLayers: DoubleArray = doubleArrayOf(2.0, 1.0, 0.5),
        val grainDensityMin: DoubleArray = doubleArrayOf(0.03, 0.03, 0.03),
        val uniformity: DoubleArray = doubleArrayOf(0.97, 0.99, 0.97),
        val grainBlurPx: Double = 0.65,
        val couplerDiffusionUm: Double = 20.0,
        val couplerTailUm: Double = 200.0,
        val couplerTailWeight: Double = 0.06,
        val glarePercent: Double = 0.03,
        val glareRoughness: Double = 0.7,
        val unsharpSigma: Double = 0.7,
        val unsharpAmount: Double = 0.7,
    ) {
        companion object {
            fun fromJson(o: JSONObject?): Spatial {
                o ?: return Spatial()
                val d = Spatial()
                fun JSONObject?.arr(k: String, def: DoubleArray) = this?.optJSONArray(k)?.let { a -> DoubleArray(a.length()) { a.optDouble(it) } }?.takeIf { it.size == def.size && it.none(Double::isNaN) } ?: def
                fun JSONObject?.num(k: String, def: Double) = this?.optDouble(k, def)?.takeUnless { it.isNaN() } ?: def
                val h = o.optJSONObject("halation"); val g = o.optJSONObject("grain"); val c = o.optJSONObject("couplers"); val gl = o.optJSONObject("glare")
                val us = o.optJSONArray("unsharp")
                return Spatial(
                    h.arr("scatterCoreUm", d.scatterCoreUm), h.arr("scatterTailUm", d.scatterTailUm), h.arr("scatterTailWeight", d.scatterTailWeight),
                    h.arr("strength", d.halationStrength), h.arr("firstSigmaUm", d.halationSigmaUm),
                    h?.optInt("bounces", d.bounces) ?: d.bounces, h.num("bounceDecay", d.bounceDecay), h?.optBoolean("renormalize", true) ?: true,
                    g.num("particleAreaUm2", d.particleAreaUm2), g.arr("particleScale", d.particleScale), g.arr("particleScaleLayers", d.particleScaleLayers),
                    g.arr("densityMin", d.grainDensityMin), g.arr("uniformity", d.uniformity), g.num("blur", d.grainBlurPx),
                    c.num("diffusionUm", d.couplerDiffusionUm), c.num("tailUm", d.couplerTailUm), c.num("tailWeight", d.couplerTailWeight),
                    gl.num("percent", d.glarePercent), gl.num("roughness", d.glareRoughness),
                    us?.optDouble(0, d.unsharpSigma) ?: d.unsharpSigma, us?.optDouble(1, d.unsharpAmount) ?: d.unsharpAmount,
                )
            }
        }
    }

    class PrintData(
        val id: String,
        val logExposure: DoubleArray, val curves: DoubleArray,
        val sensitivity: DoubleArray, val channelDensity: DoubleArray, val baseDensity: DoubleArray,
        val scan: FloatArray, val scanMin: DoubleArray, val scanMax: DoubleArray,
        /** The curve model (the chemistry controls), null = missing in the data. */
        val curveModel: CurveModel? = null,
    )

    /**
     * The paper's density curves as a model (spektrafilm's DensityCurvesModel): per channel a sum of layers, each one
     * a normal CDF curve with center, amplitude and width. Index [channel·layers + i].
     */
    class CurveModel(val positive: Boolean, val layers: Int, val centers: DoubleArray, val amplitudes: DoubleArray, val sigmas: DoubleArray)

    /** The chemistry controls (spektrafilm's PrintCurvesMorphParams; 1/0 = unchanged). */
    data class Morph(
        val gamma: Double = 1.0, val fast: Double = 1.0, val slow: Double = 1.0,
        val red: Double = 1.0, val green: Double = 1.0, val blue: Double = 1.0, val exhaustion: Double = 0.0,
    ) {
        val identity get() = gamma == 1.0 && fast == 1.0 && slow == 1.0 && red == 1.0 && green == 1.0 && blue == 1.0 && exhaustion == 0.0
    }

    /**
     * The paper's curves ([PrintData.curves] form, [i·3 + channel] over [PrintData.logExposure]) with the chemistry
     * controls – spektrafilm's s023 morph (utils/morph_curves.py): each layer's center and width are divided by the
     * layer's gamma (fastest layer: gamma·fast, the others: gamma·slow, times the channel's), width never below 0.05;
     * developer exhaustion blends each layer toward a Gumbel curve and shifts them so the density at log E = 0 stays put.
     * Unchanged: the exported curves (the same as the model at defaults).
     */
    fun printCurves(pr: PrintData, m: Morph): DoubleArray {
        val cm = pr.curveModel
        if (m.identity || cm == null) return pr.curves
        val n = pr.logExposure.size
        val out = DoubleArray(n * K)
        for (c in 0 until K) {
            val l = cm.layers
            val centers = DoubleArray(l) { cm.centers[c * l + it] }
            val amps = DoubleArray(l) { cm.amplitudes[c * l + it] }
            val sigmas = DoubleArray(l) { cm.sigmas[c * l + it] }
            val order = (0 until l).sortedBy { centers[it] }
            val iFast = order[0]; val iMid = order[l / 2]; val iSlow = order[l - 1]
            val ch = when (c) { 0 -> m.red; 1 -> m.green; else -> m.blue }
            val gFast = m.gamma * ch * m.fast; val gOther = m.gamma * ch * m.slow
            require(gFast > 0 && gOther > 0) { "chemistry gamma must be greater than 0" }
            for ((i, g) in listOf(iFast to gFast, iMid to gOther, iSlow to gOther).distinctBy { it.first }) {
                sigmas[i] = max(sigmas[i] / g, 0.05); centers[i] = centers[i] / g
            }
            val mix = m.exhaustion.coerceIn(0.0, 1.0)
            val off = exhaustionOffset(centers, amps, sigmas, cm.positive, mix)
            for (i in 0 until l) centers[i] += off
            for (j in 0 until n) out[j * K + c] = channelDensity(pr.logExposure[j], centers, amps, sigmas, cm.positive, mix)
        }
        return out
    }

    private val GUMBEL_LOCATION = -ln(ln(2.0))
    private val GUMBEL_WIDTH = 0.5 * ln(2.0) * sqrt(2 * PI)

    private fun channelDensity(x: Double, centers: DoubleArray, amps: DoubleArray, sigmas: DoubleArray, positive: Boolean, mix: Double): Double {
        var total = 0.0
        for (i in centers.indices) {
            val z0 = (x - centers[i]) / sigmas[i]
            val z = if (positive) -z0 else z0
            var cdf = normCdf(z)
            if (mix > 0) cdf = (1 - mix) * cdf + mix * exp(-exp(-(z / GUMBEL_WIDTH + GUMBEL_LOCATION)))
            total += amps[i] * cdf
        }
        return total
    }

    /** The common shift that keeps the density at log E = 0 unchanged under exhaustion (spektrafilm: brentq). */
    private fun exhaustionOffset(centers: DoubleArray, amps: DoubleArray, sigmas: DoubleArray, positive: Boolean, mix: Double): Double {
        if (mix <= 0) return 0.0
        val target = channelDensity(0.0, centers, amps, sigmas, positive, 0.0)
        fun res(o: Double) = channelDensity(0.0, DoubleArray(centers.size) { centers[it] + o }, amps, sigmas, positive, mix) - target
        if (abs(res(0.0)) <= 1e-12) return 0.0
        var lo = -0.25; var hi = 0.25
        var rLo = res(lo); var rHi = res(hi)
        var k = 0
        while (rLo * rHi > 0 && k < 12) { lo *= 2; hi *= 2; rLo = res(lo); rHi = res(hi); k++ }
        if (rLo == 0.0) return lo
        if (rHi == 0.0) return hi
        if (rLo * rHi > 0) return 0.0
        repeat(200) {
            val mid = 0.5 * (lo + hi)
            val r = res(mid)
            if (r == 0.0 || hi - lo < 1e-12) return mid
            if ((r < 0) == (rLo < 0)) { lo = mid; rLo = r } else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    /** The standard normal cumulative distribution function (erfc, W. J. Cody's rational approximation, ≈ 1e-15). */
    fun normCdf(z: Double): Double = 0.5 * erfc(-z / sqrt(2.0))

    private fun erfc(x: Double): Double {
        val ax = abs(x)
        val r = if (ax < 0.5) {
            // erf with a rational function in x²
            val t = x * x
            val num = (((0.185777706184603153 * t + 3.16112374387056560) * t + 113.864154151050156) * t + 377.485237685302021) * t + 3209.37758913846947
            val den = (((t + 23.6012909523441209) * t + 244.024637934444173) * t + 1282.61652607737228) * t + 2844.23683343917062
            return 1.0 - x * num / den
        } else if (ax < 4.0) {
            val num = ((((((((2.15311535474403846e-8 * ax + 0.564188496988670089) * ax + 8.88314979438837594) * ax + 66.1191906371416295) * ax +
                298.635138197400131) * ax + 881.952221241769090) * ax + 1712.04761263407058) * ax + 2051.07837782607147) * ax + 1230.33935479799725)
            val den = ((((((((ax + 15.7449261107098347) * ax + 117.693950891312499) * ax + 537.181101862009858) * ax + 1621.38957456669019) * ax +
                3290.79923573345963) * ax + 4362.61909014324716) * ax + 3439.36767414372164) * ax + 1230.33935480374942)
            exp(-ax * ax) * num / den
        } else {
            val z = 1.0 / (ax * ax)
            val num = ((((0.0163153871373020978 * z + 0.305326634961232344) * z + 0.360344899949804439) * z + 0.125781726111229246) * z + 0.0160837851487422766) * z + 6.58749161529837803e-4
            val den = ((((z + 2.56852019228982242) * z + 1.87295284992346725) * z + 0.527905102951428412) * z + 0.0605183413124413191) * z + 2.33520497626869185e-3
            exp(-ax * ax) / ax * (1 / sqrt(PI) - z * num / den)
        }
        return if (x < 0) 2.0 - r else r
    }

    // ------------------------------------------------------------------------------------------- data (assets)

    /** Reads files in the spektrafilm directory (assets in the app, a folder in the tests). */
    @Volatile var open: ((String) -> InputStream)? = null

    fun init(context: android.content.Context) {
        if (open == null) { val am = context.applicationContext.assets; open = { am.open("spektrafilm/$it") } }
    }

    private fun read(path: String): ByteArray = (open ?: error("spektrafilm is not initialized")).invoke(path).use { it.readBytes() }
    private fun json(path: String) = JSONObject(String(read(path), Charsets.UTF_8))

    private fun doubles(a: JSONArray?): DoubleArray = DoubleArray(a?.length() ?: 0) { i -> if (a!!.isNull(i)) Double.NaN else a.getDouble(i) }

    /** float16 (little endian) → float. */
    private fun halfs(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(b.remaining()) { half(b.get(it).toInt() and 0xffff) }
    }

    internal fun half(h: Int): Float {
        val s = (h ushr 15) and 1
        val e = (h ushr 10) and 0x1f
        val m = h and 0x3ff
        val v = when (e) {
            0 -> m / 1024f * 2f.pow(-14)
            31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1f + m / 1024f) * 2f.pow(e - 15)
        }
        return if (s == 1) -v else v
    }

    val index: Index by lazy {
        val o = json("index.json")
        fun infos(a: JSONArray) = (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            StockInfo(s.getString("id"), s.optString("name", s.getString("id")), s.optString("type", "negative"),
                s.optString("use").ifBlank { null }, if (s.isNull("targetPrint")) null else s.optString("targetPrint").ifBlank { null },
                s.optString("support").ifBlank { null })
        }
        val nf = HashMap<String, Map<String, DoubleArray>>()
        o.optJSONObject("neutralFilters")?.let { db ->
            for (p in db.keys()) {
                val byFilm = db.getJSONObject(p).optJSONObject("TH-KG3") ?: continue
                nf[p] = byFilm.keys().asSequence().associateWith { f -> doubles(byFilm.getJSONArray(f)) }
            }
        }
        Index(infos(o.getJSONArray("films")), infos(o.getJSONArray("prints")), o.getInt("scanN"),
            doubles(o.getJSONArray("enlargerIlluminant")), doubles(o.getJSONArray("dichroic")), nf)
    }

    private val films = HashMap<String, FilmData>()
    private val prints = HashMap<String, PrintData>()

    fun film(id: String): FilmData = synchronized(films) {
        films.getOrPut(id) {
            val o = json("film/$id.json")
            val n = o.getInt("tcSize")
            val tc = halfs(read("film/$id.tc.bin"))
            require(tc.size == n * n * K) { "tc LUT for $id has the wrong size" }
            val c = o.getJSONObject("couplers")
            val positive = o.optString("type") == "positive"
            val scan = if (positive) halfs(read("film/$id.scan.bin")) else null
            FilmData(id, o.optString("type", "negative"), n, tc,
                doubles(o.getJSONArray("logExposure")), doubles(o.getJSONArray("densityCurves")),
                doubles(o.getJSONArray("channelDensity")), doubles(o.getJSONArray("baseDensity")),
                doubles(o.getJSONArray("refXy")), doubles(o.getJSONArray("m709")), doubles(o.getJSONArray("mSrgb")),
                doubles(c.getJSONArray("self")), doubles(c.getJSONArray("r")), doubles(c.getJSONArray("g")), doubles(c.getJSONArray("b")),
                c.optDouble("inhSelf", 1.0), c.optDouble("inhInter", 1.0),
                scan, o.optJSONArray("scanMin")?.let { doubles(it) }, o.optJSONArray("scanMax")?.let { doubles(it) },
                o.optJSONArray("densityCurvesLayers")?.let { doubles(it) }?.takeIf { it.size == o.getJSONArray("logExposure").length() * 9 } ?: DoubleArray(0),
                Spatial.fromJson(o.optJSONObject("spatial")))
        }
    }

    fun print(id: String): PrintData = synchronized(prints) {
        prints.getOrPut(id) {
            val o = json("print/$id.json")
            PrintData(id, doubles(o.getJSONArray("logExposure")), doubles(o.getJSONArray("densityCurves")),
                doubles(o.getJSONArray("sensitivity")), doubles(o.getJSONArray("channelDensity")), doubles(o.getJSONArray("baseDensity")),
                halfs(read("print/$id.scan.bin")), doubles(o.getJSONArray("scanMin")), doubles(o.getJSONArray("scanMax")),
                o.optJSONObject("curveModel")?.let { m ->
                    CurveModel(m.optString("type") == "positive", m.getInt("layers"), doubles(m.getJSONArray("centers")),
                        doubles(m.getJSONArray("amplitudes")), doubles(m.getJSONArray("sigmas")))
                })
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** np.interp / fast_interp: linear, outside the end points the end value. [x] ascending, [y] with stride [ys] from [y0]. */
    internal fun interp(v: Double, x: DoubleArray, xs: Int, x0: Int, y: DoubleArray, ys: Int, y0: Int, n: Int): Double {
        if (v.isNaN()) return Double.NaN
        if (v <= x[x0]) return y[y0]
        if (v >= x[x0 + (n - 1) * xs]) return y[y0 + (n - 1) * ys]
        // searchsorted(side='right') − 1
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (x[x0 + mid * xs] <= v) lo = mid else hi = mid }
        val xa = x[x0 + lo * xs]; val xb = x[x0 + (lo + 1) * xs]
        val dx = xb - xa
        val t = if (dx != 0.0) (v - xa) * (1.0 / dx) else 0.0
        val ya = y[y0 + lo * ys]; val yb = y[y0 + (lo + 1) * ys]
        return ya + t * (yb - ya)
    }

    private fun mitchell(t: Double): Double {
        val b = 1.0 / 3.0; val c = 1.0 / 3.0
        val x = kotlin.math.abs(t)
        return when {
            x < 1 -> (1.0 / 6.0) * ((12 - 9 * b - 6 * c) * x * x * x + (-18 + 12 * b + 6 * c) * x * x + (6 - 2 * b))
            x < 2 -> (1.0 / 6.0) * ((-b - 6 * c) * x * x * x + (6 * b + 30 * c) * x * x + (-12 * b - 48 * c) * x + (8 * b + 24 * c))
            else -> 0.0
        }
    }

    private fun reflect(i: Int, n: Int) = if (i < 0) -i else if (i >= n) 2 * (n - 1) - i else i

    /** fast_interp_lut._cubic_interp_lut_at_2d: Mitchell–Netravali (B = C = 1/3), mirrored edges. */
    internal fun cubic2d(lut: FloatArray, n: Int, xIn: Double, yIn: Double, out: DoubleArray) {
        fun base(c: Double): Pair<Int, Double> {
            val cc = if (c <= 0.0) 0.0 else if (c >= (n - 1).toDouble()) (n - 1).toDouble() else c
            if (cc >= (n - 1).toDouble()) return (n - 2) to 1.0
            val b = floor(cc).toInt()
            return b to (cc - b)
        }
        val (xb, xf) = base(xIn)
        val (yb, yf) = base(yIn)
        val wx = doubleArrayOf(mitchell(xf + 1), mitchell(xf), mitchell(xf - 1), mitchell(xf - 2))
        val wy = doubleArrayOf(mitchell(yf + 1), mitchell(yf), mitchell(yf - 1), mitchell(yf - 2))
        out[0] = 0.0; out[1] = 0.0; out[2] = 0.0
        var ws = 0.0
        for (i in 0 until 4) {
            val xi = reflect(xb - 1 + i, n)
            for (j in 0 until 4) {
                val yj = reflect(yb - 1 + j, n)
                val w = wx[i] * wy[j]
                ws += w
                val o = (xi * n + yj) * K
                out[0] += w * lut[o]; out[1] += w * lut[o + 1]; out[2] += w * lut[o + 2]
            }
        }
        if (ws != 0.0) { out[0] /= ws; out[1] /= ws; out[2] /= ws }
    }

    internal fun nanMin(a: DoubleArray, c: Int): Double { var m = Double.POSITIVE_INFINITY; var i = c; while (i < a.size) { if (!a[i].isNaN() && a[i] < m) m = a[i]; i += K }; return m }
    internal fun nanMax(a: DoubleArray, c: Int): Double { var m = Double.NEGATIVE_INFINITY; var i = c; while (i < a.size) { if (!a[i].isNaN() && a[i] > m) m = a[i]; i += K }; return m }

    // CAT16 (colour.adaptation, Li et al. 2017), like spektrafilm's input (colour.RGB_to_XYZ with CAT16)
    private val CAT16 = doubleArrayOf(0.401288, 0.650173, -0.051461, -0.250268, 1.204414, 0.045854, -0.002079, 0.048952, 0.953127)

    private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i -> val r = i / 3; val c = i % 3; a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c] }
    private fun mulv(m: DoubleArray, v: DoubleArray) = doubleArrayOf(m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2])
    private fun inv3(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        return doubleArrayOf(A / det, -(b * i - c * h) / det, (b * f - c * e) / det, B / det, (a * i - c * g) / det, -(a * f - c * d) / det, C / det, -(a * h - b * g) / det, (a * e - b * d) / det)
    }

    /** RGB (linear, color space [toXyz] with its own white point) → XYZ adapted to the white point [xy] (von Kries, CAT16). */
    fun rgbToXyzAdapted(toXyz: DoubleArray, xy: DoubleArray): DoubleArray {
        val ws = mulv(toXyz, doubleArrayOf(1.0, 1.0, 1.0))
        val wt = doubleArrayOf(xy[0] / xy[1], 1.0, (1 - xy[0] - xy[1]) / xy[1])
        val ls = mulv(CAT16, ws); val lt = mulv(CAT16, wt)
        val d = doubleArrayOf(lt[0] / ls[0], 0.0, 0.0, 0.0, lt[1] / ls[1], 0.0, 0.0, 0.0, lt[2] / ls[2])
        return mul3(mul3(inv3(CAT16), mul3(d, CAT16)), toXyz)
    }

    fun inputMatrix(film: FilmData, input: OpenDrt.Input): DoubleArray =
        rgbToXyzAdapted(DoubleArray(9) { input.gamut[it].toDouble() }, film.refXy)

    // ------------------------------------------------------------------------------------------- the model

    /**
     * The chain for one setting. Everything that is the same for all pixels is computed once here.
     * The steps match spektrafilm's taps: log_e_film → cmy_film → (log_e_print → cmy_print) → rgb_out.
     */
    class Model(val p: Film) {
        val film: FilmData = film(p.film)
        val scanFilm = p.print == null || film.positive
        val print: PrintData? = if (scanFilm) null else print(p.print!!)
        private val ix = index
        internal val le = film.logExposure
        internal val n = le.size

        // develop(): the curves minus their minimum, the axis log E / gamma
        internal val curves = DoubleArray(film.curves.size).also { o ->
            for (c in 0 until K) { val m = nanMin(film.curves, c); var i = c; while (i < o.size) { o[i] = film.curves[i] - m; i += K } }
        }
        private val gamma = p.gamma.toDouble()
        internal val leG = DoubleArray(n) { le[it] / gamma }
        internal val dmaxNorm = DoubleArray(K) { nanMax(curves, it) }

        // the DIR couplers: the matrix (donor row, receiver column) and the curves before the couplers
        internal val couplerM = DoubleArray(9).also { m ->
            for (k in 0 until K) m[k * 3 + k] = film.couplerSelf[k] * film.inhSelf
            m[1] = film.couplerR[0] * film.inhInter; m[2] = film.couplerR[1] * film.inhInter
            m[3] = film.couplerG[0] * film.inhInter; m[5] = film.couplerG[1] * film.inhInter
            m[6] = film.couplerB[0] * film.inhInter; m[7] = film.couplerB[1] * film.inhInter
            for (i in 0 until 9) m[i] *= p.couplers.toDouble()
        }
        internal val couplersOn = p.couplers > 0f // DirCouplersParams.active with amount 0 gives the same result
        internal val curves0: DoubleArray = DoubleArray(curves.size).also { out ->
            val silver = DoubleArray(curves.size) { i -> if (film.positive) dmaxNorm[i % K] - curves[i] else curves[i] }
            for (ch in 0 until K) {
                val le0 = DoubleArray(n) { j -> le[j] - (silver[j * K] * couplerM[ch] + silver[j * K + 1] * couplerM[3 + ch] + silver[j * K + 2] * couplerM[6 + ch]) }
                for (j in 0 until n) {
                    out[j * K + ch] = if (film.positive) -interp(le[j], le0, 1, 0, DoubleArray(n) { -curves[it * K + ch] }, 1, 0, n)
                    else interp(le[j], le0, 1, 0, curves, K, ch, n)
                }
            }
        }

        // input: the color space → XYZ at the film's reference illuminant
        val input = OpenDrt.Input.of(p.input)
        val toXyz = inputMatrix(film, input)

        // the enlarger: the lamp through the C/M/Y filters (neutral from the database + the shift)
        val neutral: DoubleArray = print?.let { ix.neutral[it.id]?.get(film.id) } ?: doubleArrayOf(0.0, 65.0, 55.0)
        private val printLight: DoubleArray? = print?.let {
            val cc = doubleArrayOf(neutral[0], neutral[1] + p.mShift, neutral[2] + p.yShift)
            val t = DoubleArray(K) { 10.0.pow(-(cc[it] / 100.0)) }
            DoubleArray(WL) { l ->
                var total = 1.0
                for (k in 0 until K) total *= 1 - (1 - ix.dichroic[l * K + k]) * (1 - t[k])
                ix.enlarger[l] * total
            }
        }

        // wavelengths where the film's channel density or base is missing count as no light (spektrafilm: NaN → 0)
        private val alive = BooleanArray(WL) { l -> !film.baseDensity[l].isNaN() && (0 until K).none { film.channelDensity[l * K + it].isNaN() } }

        /**
         * The print normalization: mid gray should give the same exposure on the paper. If the lab compensates for the
         * camera exposure, mid gray counts as 0.184 · 2^ev (then the print cancels the exposure almost entirely – as in
         * spektrafilm); otherwise 0.184, so the exposure shows in the image.
         */
        val printFactor: Double = print?.let { pr ->
            val g = 0.184 * (if (p.labComp) 2.0.pow(p.ev.toDouble()) else 1.0)
            val raw = filmRaw(film.mSrgb, doubleArrayOf(g, g, g))
            // develop_simple: the film's curves as they are (not normalized), without couplers
            val d = DoubleArray(K) { c -> interp(log10(raw[c] + 1e-10), leG, 1, 0, film.curves, K, c, n) }
            val r = printRawLinear(d, pr)
            var s = 0.0
            for (c in 0 until K) s += ln(max(r[c], 1e-10))
            1.0 / exp(s / K)
        } ?: 1.0

        /** hanatos2025: linear RGB (after the matrix [m]) → the film's raw exposure. */
        fun filmRaw(m: DoubleArray, rgb: DoubleArray): DoubleArray {
            val x = m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2]
            val y = m[3] * rgb[0] + m[4] * rgb[1] + m[5] * rgb[2]
            val z = m[6] * rgb[0] + m[7] * rgb[1] + m[8] * rgb[2]
            var b = x + y + z
            val d = max(b, 1e-10)
            val tx = x / d; val ty = y / d
            val qy = (ty / max(1.0 - tx, 1e-10)).coerceIn(0.0, 1.0)
            val qx = ((1.0 - tx) * (1.0 - tx)).coerceIn(0.0, 1.0)
            if (b.isNaN()) b = 0.0
            val out = DoubleArray(3)
            val s = (film.tcSize - 1).toDouble()
            cubic2d(film.tc, film.tcSize, qx * s, qy * s, out)
            for (c in 0 until K) out[c] *= b
            return out
        }

        /** log_e_film: linear RGB in the input color space → log10 of the film's exposure. */
        fun logEFilm(rgb: DoubleArray): DoubleArray {
            val raw = filmRaw(toXyz, rgb)
            val k = 2.0.pow(p.ev.toDouble())
            return DoubleArray(K) { log10(max(raw[it] * k, 0.0) + 1e-10) }
        }

        /** cmy_film: development with the DIR couplers (without diffusion – that is spatial). */
        fun cmyFilm(logE: DoubleArray): DoubleArray {
            val d = DoubleArray(K) { c -> interp(logE[c], leG, 1, 0, curves, K, c, n) }
            if (!couplersOn) return d
            val silver = DoubleArray(K) { if (film.positive) dmaxNorm[it] - d[it] else d[it] }
            return DoubleArray(K) { m ->
                val corr = silver[0] * couplerM[m] + silver[1] * couplerM[3 + m] + silver[2] * couplerM[6 + m]
                interp(logE[m] - corr, leG, 1, 0, curves0, K, m, n)
            }
        }

        /** The negative's density → the light through it (the enlarger) → the paper's exposure (linear, before normalization). */
        private fun printRawLinear(cmy: DoubleArray, pr: PrintData): DoubleArray {
            val light = printLight!!
            var r0 = 0.0; var r1 = 0.0; var r2 = 0.0
            for (l in 0 until WL) {
                if (!alive[l]) continue
                val ds = cmy[0] * film.channelDensity[l * K] + cmy[1] * film.channelDensity[l * K + 1] + cmy[2] * film.channelDensity[l * K + 2] + film.baseDensity[l]
                val t = 10.0.pow(-ds) * light[l]
                if (t.isNaN()) continue
                r0 += t * pr.sensitivity[l * K]; r1 += t * pr.sensitivity[l * K + 1]; r2 += t * pr.sensitivity[l * K + 2]
            }
            return doubleArrayOf(r0, r1, r2)
        }

        /** log_e_print */
        fun logEPrint(cmyFilm: DoubleArray): DoubleArray {
            val pr = print ?: error("no print (slide film)")
            val r = printRawLinear(cmyFilm, pr)
            return DoubleArray(K) {
                val lr = log10(max(r[it] * printFactor, 0.0) + 1e-10)
                log10(max(10.0.pow(lr) * p.printExposure, 0.0) + 1e-10)
            }
        }

        /** The paper's curves with the chemistry controls ([printCurves]). */
        val printCurves: DoubleArray? = print?.let { printCurves(it, Morph(gamma = p.printGamma.toDouble())) }

        /** cmy_print */
        fun cmyPrint(logE: DoubleArray): DoubleArray {
            val pr = print!!
            return DoubleArray(K) { c -> interp(logE[c], pr.logExposure, 1, 0, printCurves!!, K, c, pr.logExposure.size) }
        }

        /** rgb_out: the scan (precomputed, tetrahedral interpolation) → sRGB-encoded. */
        fun scan(cmy: DoubleArray): DoubleArray {
            val lut: FloatArray; val lo: DoubleArray; val hi: DoubleArray
            if (print != null) { lut = print.scan; lo = print.scanMin; hi = print.scanMax } else { lut = film.scan!!; lo = film.scanMin!!; hi = film.scanMax!! }
            val nn = ix.scanN
            val f = DoubleArray(K) { c -> (((cmy[c] - lo[c]) / (hi[c] - lo[c])).let { if (it.isNaN()) 0.0 else it }).coerceIn(0.0, 1.0) * (nn - 1) }
            val i0 = IntArray(K) { min(floor(f[it]).toInt(), nn - 2) }
            val fr = f[0] - i0[0]; val fg = f[1] - i0[1]; val fb = f[2] - i0[2]
            fun at(dr: Int, dg: Int, db: Int, c: Int) = lut[(((i0[0] + dr) * nn + (i0[1] + dg)) * nn + (i0[2] + db)) * K + c].toDouble()
            return DoubleArray(K) { c ->
                val c000 = at(0, 0, 0, c); val c111 = at(1, 1, 1, c)
                if (fr > fg) {
                    if (fg > fb) { val c1 = at(1, 0, 0, c); val c2 = at(1, 1, 0, c); c000 + fr * (c1 - c000) + fg * (c2 - c1) + fb * (c111 - c2) }
                    else if (fr > fb) { val c1 = at(1, 0, 0, c); val c2 = at(1, 0, 1, c); c000 + fr * (c1 - c000) + fb * (c2 - c1) + fg * (c111 - c2) }
                    else { val c1 = at(0, 0, 1, c); val c2 = at(1, 0, 1, c); c000 + fb * (c1 - c000) + fr * (c2 - c1) + fg * (c111 - c2) }
                } else {
                    if (fb > fg) { val c1 = at(0, 0, 1, c); val c2 = at(0, 1, 1, c); c000 + fb * (c1 - c000) + fg * (c2 - c1) + fr * (c111 - c2) }
                    else if (fb > fr) { val c1 = at(0, 1, 0, c); val c2 = at(0, 1, 1, c); c000 + fg * (c1 - c000) + fb * (c2 - c1) + fr * (c111 - c2) }
                    else { val c1 = at(0, 1, 0, c); val c2 = at(1, 1, 0, c); c000 + fg * (c1 - c000) + fr * (c2 - c1) + fb * (c111 - c2) }
                }
            }
        }

        /** The whole chain: linear RGB → sRGB-encoded (spektrafilm's output). */
        fun linearToSrgb(rgb: DoubleArray): DoubleArray {
            val cmy = cmyFilm(logEFilm(rgb))
            return scan(if (scanFilm) cmy else cmyPrint(logEPrint(cmy)))
        }

        /** One pixel as the node sees it: code values in the input format → display code values (0..1). */
        fun apply(px: FloatArray) {
            val lin = DoubleArray(K) { OpenDrt.oetf(px[it], input.oetf).toDouble() }
            val s = linearToSrgb(lin)
            for (c in 0 until K) {
                var v = s[c].coerceIn(0.0, 1.0)
                if (p.display == 1) {
                    // sRGB-encoded → linear → Rec.1886 (2.4)
                    val l = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
                    v = l.pow(1.0 / 2.4)
                }
                px[c] = if (v.isNaN()) 0f else v.toFloat()
            }
        }
    }

    // ------------------------------------------------------------------------------------------- LUT in the node

    private const val SIZE = 33
    private val cache = object : LinkedHashMap<String, CubeLut>(6, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CubeLut>?) = size > 6
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    /**
     * The node's LUT ([base], mixed by [baseMix]) and then the film, baked into a 33³ LUT. The same object for the same
     * settings. The baking is split across the CPU cores (≈ 36,000 points through the whole chain).
     */
    fun composite(base: CubeLut?, baseMix: Float, film: Film): CubeLut {
        val mixQ = (baseMix.coerceIn(0f, 1f) * 50f).toInt() / 50f
        val key = "${base?.name}|${if (base != null) mixQ else 0f}|$film"
        synchronized(cache) { cache[key]?.let { return it } }
        val m = Model(film)
        val d = FloatArray(SIZE * SIZE * SIZE * 3)
        val n1 = (SIZE - 1).toFloat()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val work = (0 until threads).map { t ->
            Thread({
                val px = FloatArray(3)
                var b = t
                while (b < SIZE) {
                    for (g in 0 until SIZE) for (r in 0 until SIZE) {
                        px[0] = r / n1; px[1] = g / n1; px[2] = b / n1
                        if (base != null && mixQ > 0f) {
                            val o = base.applyTetrahedral(px[0], px[1], px[2])
                            for (k in 0..2) px[k] += (o[k].coerceIn(0f, 1f) - px[k]) * mixQ
                        }
                        m.apply(px)
                        val i = ((b * SIZE + g) * SIZE + r) * 3
                        d[i] = px[0]; d[i + 1] = px[1]; d[i + 2] = px[2]
                    }
                    b += threads
                }
            }, "spektrafilm-$t").apply { start() }
        }
        work.forEach { it.join() }
        val lut = CubeLut("spektrafilm $key", SIZE, d, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        synchronized(cache) { cache[key] = lut }
        return lut
    }

    /** Which papers suit a film (the default first: the film's own target paper). */
    fun printsFor(filmId: String): List<StockInfo> {
        val target = index.films.firstOrNull { it.id == filmId }?.targetPrint
        return index.prints.sortedBy { if (it.id == target) 0 else 1 }
    }
}
