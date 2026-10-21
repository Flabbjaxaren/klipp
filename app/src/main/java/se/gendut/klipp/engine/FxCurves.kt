package se.gendut.klipp.engine

import org.json.JSONObject
import se.gendut.klipp.R
import se.gendut.klipp.Str
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The curves in the graphical audio editors: the EQ's frequency response and the compressor's transfer curve.
 * Computed by the engine's own DSP (the same code that is heard and exported) via JNI; if the library is missing
 * (tests) an approximation in Kotlin is used here.
 */
object FxCurves {
    /**
     * An EQ band in parametric_eq (the engine's parameter ids). [label] is the display name: [labelRes], or
     * "Band [number]" when [number] > 0.
     */
    data class Band(
        val key: String, private val labelRes: Int, val freq: Double, val q: Double, val shelf: Int = 0,
        private val number: Int = 0,
    ) {
        val label: String get() = if (number > 0) Str.t(R.string.fxcurves_band_n, number) else Str.t(labelRes)
        val on get() = "${key}_on"
        val f get() = "${key}_freq"
        val g get() = "${key}_gain"
        val qId get() = "${key}_q"
    }

    /** Low shelf, five bands, high shelf – in frequency order. */
    val BANDS = listOf(
        Band("low", R.string.fxcurves_band_low_shelf, 100.0, 0.71, shelf = -1),
        Band("b1", 0, 200.0, 1.0, number = 1),
        Band("b2", 0, 500.0, 1.0, number = 2),
        Band("mid", 0, 1000.0, 1.0, number = 3),
        Band("b4", 0, 2000.0, 1.0, number = 4),
        Band("b5", 0, 5000.0, 1.0, number = 5),
        Band("high", R.string.fxcurves_band_high_shelf, 8000.0, 0.71, shelf = 1),
    )

    /** Default values the engine uses when a parameter isn't set. */
    fun default(id: String, param: String): Double {
        if (id == "parametric_eq") {
            when (param) {
                "hp_on", "lp_on" -> return 0.0
                "hp_freq" -> return 30.0
                "lp_freq" -> return 18000.0
                "hp_slope", "lp_slope", "master_gain" -> return 0.0
            }
            BANDS.firstOrNull { param.startsWith(it.key + "_") }?.let { b ->
                return when (param) { b.on -> 1.0; b.f -> b.freq; b.g -> 0.0; b.qId -> b.q; else -> 0.0 }
            }
        }
        if (id == "dynamics_rack") return when (param) {
            "comp_on" -> 1.0; "comp_threshold" -> -20.0; "comp_ratio" -> 2.0; "comp_attack" -> 10.0; "comp_release" -> 100.0
            "gate_threshold" -> -50.0; "exp_threshold" -> -60.0; "exp_ratio" -> 2.0; "lim_threshold" -> -1.0
            else -> 0.0
        }
        return 0.0
    }

    fun value(id: String, params: Map<String, Double>, param: String) = params[param] ?: default(id, param)

    /** The engine's curve (null = the library is missing or the effect has no curve). Replaceable in tests. */
    var native: (String, String, DoubleArray) -> DoubleArray? = { j, k, xs -> runCatching { Engine.nativeFxCurve(j, k, xs) }.getOrNull() }

    private fun json(id: String, params: Map<String, Double>): String =
        JSONObject().put("id", id).put("on", true).put("p", JSONObject().also { o -> params.forEach { (k, v) -> o.put(k, v) } }).toString()

    /** Frequency response (dB) at [freqs] Hz. */
    fun response(id: String, params: Map<String, Double>, freqs: DoubleArray): DoubleArray =
        native(json(id, params), "response", freqs)?.takeIf { it.size == freqs.size && it.all { v -> v.isFinite() } }
            ?: DoubleArray(freqs.size) { approxResponse(id, params, freqs[it]) }

    /** Output level (dB) for the input levels [levels] dB. */
    fun transfer(id: String, params: Map<String, Double>, levels: DoubleArray): DoubleArray =
        native(json(id, params), "transfer", levels)?.takeIf { it.size == levels.size }?.map { if (it.isFinite()) it else -120.0 }?.toDoubleArray()
            ?: DoubleArray(levels.size) { approxTransfer(id, params, levels[it]) }

    // ---------------------------------------------------------------- approximations (without the engine)

    private const val SR = 48_000.0

    /** |H| in dB for an RBJ biquad (b0..a2) at frequency f. */
    private fun biquadDb(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double, f: Double): Double {
        val w = 2 * PI * f / SR
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = a0 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    private fun bandDb(kind: Int, f0: Double, gain: Double, q: Double, f: Double): Double {
        val a = 10.0.pow(gain / 40)
        val w0 = 2 * PI * f0.coerceIn(10.0, SR / 2 - 100) / SR
        val cw = cos(w0); val alpha = sin(w0) / (2 * q.coerceAtLeast(0.05))
        return when (kind) {
            0 -> biquadDb(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a, f)
            -1 -> { val s = 2 * sqrt(a) * alpha
                biquadDb(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s),
                    (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s, f) }
            else -> { val s = 2 * sqrt(a) * alpha
                biquadDb(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s),
                    (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s, f) }
        }
    }

    fun approxResponse(id: String, p: Map<String, Double>, f: Double): Double {
        if (id != "parametric_eq") return 0.0
        fun v(k: String) = value(id, p, k)
        var db = v("master_gain")
        for (b in BANDS) if (v(b.on) > 0.5 && v(b.g) != 0.0) db += bandDb(b.shelf, v(b.f), v(b.g), v(b.qId), f)
        if (v("hp_on") > 0.5) { val n = 2 * (v("hp_slope") + 1); db -= 10 * log10(1 + (v("hp_freq") / f).pow(2 * n)) }
        if (v("lp_on") > 0.5) { val n = 2 * (v("lp_slope") + 1); db -= 10 * log10(1 + (f / v("lp_freq")).pow(2 * n)) }
        return db
    }

    fun approxTransfer(id: String, p: Map<String, Double>, x: Double): Double {
        if (id != "dynamics_rack") return x
        fun v(k: String) = value(id, p, k)
        var y = x
        if (v("gate_on") > 0.5 && x < v("gate_threshold")) return -120.0
        if (v("exp_on") > 0.5 && x < v("exp_threshold")) y = v("exp_threshold") + (x - v("exp_threshold")) * v("exp_ratio")
        if (v("comp_on") > 0.5) {
            val t = v("comp_threshold")
            if (x > t) y = t + (x - t) / v("comp_ratio").coerceAtLeast(1.0)
            y += v("comp_makeup")
        }
        y += v("output")
        if (v("lim_on") > 0.5) y = minOf(y, v("lim_threshold"))
        return y
    }
}
