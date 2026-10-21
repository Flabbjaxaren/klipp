package se.gendut.klipp.engine

import androidx.annotation.StringRes
import se.gendut.klipp.R
import se.gendut.klipp.Str

/**
 * The audio effects Klipp offers (the engine's effect ids and parameters, with display names). The values are
 * written to the engine and played through the engine's DSP (filmcraft-audio-dsp), so playback and export sound the same.
 */
object AudioFxCatalog {
    data class Param(
        val id: String, @StringRes val labelRes: Int, val min: Float, val max: Float, val default: Float,
        val format: (Float) -> String, val toggle: Boolean = false,
        /** Shown only while this on/off parameter is on. */
        val onlyIf: String? = null,
    ) {
        val label: String get() = Str.t(labelRes)
    }

    /** [graph]: "eq" or "comp" = graphical editor above the sliders; [addable] = listed among the add buttons. */
    data class Fx(val id: String, @StringRes val labelRes: Int, @StringRes val hintRes: Int, val params: List<Param>, val graph: String? = null, val addable: Boolean = true) {
        val label: String get() = Str.t(labelRes)
        val hint: String get() = Str.t(hintRes)
    }

    private fun db(v: Float) = "%+.1f dB".format(v).replace('-', '−')
    private fun hz(v: Float) = if (v >= 1000f) "%.1f kHz".format(v / 1000f) else "%.0f Hz".format(v)
    private fun pct(v: Float) = "%.0f %%".format(v)

    val all = listOf(
        Fx("denoise", R.string.audiofxcatalog_denoise, R.string.audiofxcatalog_denoise_hint, listOf(
            Param("amount", R.string.audiofxcatalog_amount, 0f, 100f, 40f, ::pct),
        )),
        Fx("speech_enhance", R.string.audiofxcatalog_speech_enhance, R.string.audiofxcatalog_speech_enhance_hint, listOf(
            Param("mix", R.string.audiofxcatalog_mix, 0f, 100f, 100f, ::pct),
        )),
        Fx("parametric_eq", R.string.audiofxcatalog_eq, R.string.audiofxcatalog_eq_hint, listOf(
            Param("master_gain", R.string.audiofxcatalog_output_level, -15f, 15f, 0f, ::db),
        ), graph = "eq"),
        Fx("dynamics_rack", R.string.audiofxcatalog_compressor, R.string.audiofxcatalog_compressor_hint, listOf(
            Param("comp_threshold", R.string.audiofxcatalog_threshold, -60f, 0f, -20f, ::db),
            Param("comp_ratio", R.string.audiofxcatalog_ratio, 1f, 30f, 2f, { "%.1f:1".format(it) }),
            Param("comp_attack", R.string.audiofxcatalog_attack, 0.1f, 300f, 10f, { "%.1f ms".format(it) }),
            Param("comp_release", R.string.audiofxcatalog_release, 1f, 3000f, 100f, { "%.0f ms".format(it) }),
            Param("comp_makeup", R.string.audiofxcatalog_makeup, 0f, 30f, 0f, ::db),
            Param("lim_on", R.string.audiofxcatalog_limiter_after, 0f, 1f, 0f, { Str.t(if (it > 0.5f) R.string.audiofxcatalog_on else R.string.audiofxcatalog_off) }, toggle = true),
            Param("lim_threshold", R.string.audiofxcatalog_limiter_ceiling, -30f, 0f, -1f, ::db, onlyIf = "lim_on"),
        ), graph = "comp"),
        Fx("hard_limiter", R.string.audiofxcatalog_limiter, R.string.audiofxcatalog_limiter_hint, listOf(
            Param("max", R.string.audiofxcatalog_ceiling, -12f, 0f, -0.1f, ::db),
            Param("boost", R.string.audiofxcatalog_boost, 0f, 20f, 0f, ::db),
        )),
    )

    fun of(id: String): Fx? = all.firstOrNull { it.id == id }
    fun isToggle(fx: String, param: String) =
        of(fx)?.params?.firstOrNull { it.id == param }?.toggle == true || param.endsWith("_on") || param == "comp_auto" || param == "soft_clip"
    /** Choice parameters (integers in the engine). */
    fun isChoice(param: String) = param.endsWith("_slope") || param == "detector"
    fun label(id: String) = of(id)?.label ?: id
}
