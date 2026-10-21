/*
 * OpenDRT v1.1.0 – Kotlin port for Klipp.
 *
 * Original: Jed Smith, https://github.com/jedypod/open-display-transform
 * License: GNU General Public License v3.0 (GPLv3). This file is a derivative of OpenDRT.dctl and
 * is covered by the same license.
 *
 * The port is limited to SDR (100 nits peak, Rec.709 primaries) because Klipp's image pipeline is SDR. The algorithm,
 * the matrices and all presets (looks, tonescales, creative white point) are unchanged from the original.
 * Additions in Klipp: input "Rec.709 video" (2.4 power to linear) and "Apple Log" (Apple's published curve).
 */
package se.gendut.klipp.engine

import androidx.annotation.StringRes
import org.json.JSONObject
import se.gendut.klipp.R
import se.gendut.klipp.Str
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * OpenDRT as an effect in a node: [input] = how the footage is encoded (log curve + color space), [look] and [tonescale]
 * = OpenDRT's presets, [cwp] = creative white point (0 = the look's), [grey] = mid gray luminance in nits (10 =
 * default; higher = brighter image), [display] = the display encoding.
 */
data class Drt(
    val input: String = OpenDrt.Input.SLOG3.key,
    val look: Int = 0,
    val tonescale: Int = 0,
    val cwp: Int = 0,
    val cwpLimit: Float = 0.25f,
    val grey: Float = 10f,
    val display: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("in", input).put("look", look).put("ts", tonescale).put("cwp", cwp)
        .put("cwpLm", cwpLimit.toDouble()).put("grey", grey.toDouble()).put("disp", display)

    companion object {
        fun fromJson(o: JSONObject?): Drt? {
            o ?: return null
            val d = Drt()
            return Drt(
                o.optString("in", d.input).takeIf { k -> OpenDrt.Input.entries.any { it.key == k } } ?: d.input,
                o.optInt("look", 0).coerceIn(0, OpenDrt.LOOKS.lastIndex), o.optInt("ts", 0).coerceIn(0, OpenDrt.TONESCALE_LABELS.lastIndex),
                o.optInt("cwp", 0).coerceIn(0, OpenDrt.WHITE_LABELS.lastIndex), o.optDouble("cwpLm", 0.25).toFloat().coerceIn(0f, 1f),
                o.optDouble("grey", 10.0).toFloat().coerceIn(3f, 25f), o.optInt("disp", 0).coerceIn(0, OpenDrt.DISPLAY_LABELS.lastIndex),
            )
        }
    }
}

object OpenDrt {
    /** Input: log curve + color space (like OpenDRT's "Input Transfer Function" and "Input Gamut"). */
    enum class Input(val key: String, private val name0: String, val oetf: Int, val gamut: FloatArray, @StringRes private val labelRes: Int = 0) {
        REC709("rec709", "Rec.709 video", -1, M.REC709_TO_XYZ, R.string.opendrt_input_rec709),
        APPLE("apple", "Apple Log", -2, M.REC2020_TO_XYZ),
        SLOG3("slog3", "Sony S-Log3 / S-Gamut3.Cine", 8, M.SGAMUT3CINE_TO_XYZ),
        SLOG3SG("slog3sg", "Sony S-Log3 / S-Gamut3", 8, M.SGAMUT3_TO_XYZ),
        VLOG("vlog", "Panasonic V-Log", 7, M.VGAMUT_TO_XYZ),
        LOGC3("logc3", "ARRI LogC3", 4, M.AWG3_TO_XYZ),
        LOGC4("logc4", "ARRI LogC4", 5, M.AWG4_TO_XYZ),
        FLOG2("flog2", "Fujifilm F-Log2", 9, M.REC2020_TO_XYZ),
        RED("red", "RED Log3G10", 6, M.REDWG_TO_XYZ),
        DI("di", "DaVinci Intermediate", 1, M.DWG_TO_XYZ),
        ACESCCT("acescct", "ACEScct", 3, M.AP1_TO_XYZ),
        TLOG("tlog", "FilmLight T-Log", 2, M.EGAMUT_TO_XYZ);

        /** The display name (resolved in the current language where it is translatable). */
        val label: String get() = if (labelRes != 0) Str.t(labelRes) else name0

        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: SLOG3 }
    }

    val LOOKS = listOf("Standard", "Arriba", "Sylvan", "Colorful", "Aery", "Dystopic", "Umbra")

    /** Labels as either a string resource id (Int) or a fixed proper name (String); resolved at use. */
    private fun resolve(labels: List<Any>): List<String> = labels.map { if (it is Int) Str.t(it) else it as String }

    internal val TONESCALE_LABELS: List<Any> = listOf(R.string.opendrt_from_look, R.string.opendrt_tonescale_low, R.string.opendrt_tonescale_medium,
        R.string.opendrt_tonescale_high, "Arriba", "Sylvan", "Colorful", "Aery", "Dystopic", "Umbra",
        "ACES 1.x", "ACES 2.0", "Marvelous Tonescape", "DaGrinchi ToneGroan")
    val TONESCALES: List<String> get() = resolve(TONESCALE_LABELS)
    internal val WHITE_LABELS: List<Any> = listOf(R.string.opendrt_from_look, "D93", "D75", "D65", "D60", "D55", "D50")
    val WHITES: List<String> get() = resolve(WHITE_LABELS)
    /** The display encoding: phone/web (sRGB display, 2.2, bright surround) or TV (Rec.1886, 2.4, dim). */
    internal val DISPLAY_LABELS: List<Any> = listOf(R.string.opendrt_display_phone_web, "TV (Rec.1886 2.4)")
    val DISPLAYS: List<String> get() = resolve(DISPLAY_LABELS)

    // ---------------------------------------------------------------- parameters (the presets)

    private class P {
        var tn_con = 0f; var tn_sh = 0f; var tn_toe = 0f; var tn_off = 0f
        var tn_hcon_enable = false; var tn_hcon = 0f; var tn_hcon_pv = 0f; var tn_hcon_st = 0f
        var tn_lcon_enable = false; var tn_lcon = 0f; var tn_lcon_w = 0f
        var cwp = 2; var cwp_lm = 0f; var rs_sa = 0f; var rs_rw = 0f; var rs_bw = 0f
        var pt_enable = true; var pt_lml = 0f; var pt_lml_r = 0f; var pt_lml_g = 0f; var pt_lml_b = 0f; var pt_lmh = 0f; var pt_lmh_r = 0f; var pt_lmh_b = 0f
        var ptl_enable = true; var ptl_c = 0f; var ptl_m = 0f; var ptl_y = 0f
        var ptm_enable = true; var ptm_low = 0f; var ptm_low_rng = 0f; var ptm_low_st = 0f; var ptm_high = 0f; var ptm_high_rng = 0f; var ptm_high_st = 0f
        var brl_enable = true; var brl = 0f; var brl_r = 0f; var brl_g = 0f; var brl_b = 0f; var brl_rng = 0f; var brl_st = 0f
        var brlp_enable = true; var brlp = 0f; var brlp_r = 0f; var brlp_g = 0f; var brlp_b = 0f
        var hc_enable = true; var hc_r = 0f; var hc_r_rng = 0f
        var hs_rgb_enable = true; var hs_r = 0f; var hs_r_rng = 0f; var hs_g = 0f; var hs_g_rng = 0f; var hs_b = 0f; var hs_b_rng = 0f
        var hs_cmy_enable = true; var hs_c = 0f; var hs_c_rng = 0f; var hs_m = 0f; var hs_m_rng = 0f; var hs_y = 0f; var hs_y_rng = 0f

        fun ts(con: Float, sh: Float, toe: Float, off: Float, hconOn: Boolean, hcon: Float, hconPv: Float, hconSt: Float, lconOn: Boolean, lcon: Float, lconW: Float) {
            tn_con = con; tn_sh = sh; tn_toe = toe; tn_off = off; tn_hcon_enable = hconOn; tn_hcon = hcon; tn_hcon_pv = hconPv; tn_hcon_st = hconSt
            tn_lcon_enable = lconOn; tn_lcon = lcon; tn_lcon_w = lconW
        }
    }

    private fun preset(look: Int, tonescale: Int, whiteSel: Int, whiteLimit: Float): P {
        val p = P()
        with(p) {
            when (look) {
                1 -> { // Arriba
                    ts(1.05f, 0.5f, 0.1f, 0.01f, false, 0f, 1f, 4f, true, 1.5f, 0.2f)
                    cwp = 2; cwp_lm = 0.25f; rs_sa = 0.35f; rs_rw = 0.25f; rs_bw = 0.55f
                    pt_enable = true; pt_lml = 0.25f; pt_lml_r = 0.45f; pt_lml_g = 0f; pt_lml_b = 0.1f; pt_lmh = 0.25f; pt_lmh_r = 0.25f; pt_lmh_b = 0f
                    ptl_enable = true; ptl_c = 0.06f; ptl_m = 0.08f; ptl_y = 0.06f
                    ptm_enable = true; ptm_low = 1f; ptm_low_rng = 0.4f; ptm_low_st = 0.5f; ptm_high = -0.8f; ptm_high_rng = 0.66f; ptm_high_st = 0.6f
                    brl_enable = true; brl = 0f; brl_r = -2.5f; brl_g = -1.5f; brl_b = -1.5f; brl_rng = 0.5f; brl_st = 0.35f
                    brlp_enable = true; brlp = 0f; brlp_r = -1.7f; brlp_g = -2f; brlp_b = -0.5f
                    hc_enable = true; hc_r = 1f; hc_r_rng = 0.3f
                    hs_rgb_enable = true; hs_r = 0.6f; hs_r_rng = 0.8f; hs_g = 0.35f; hs_g_rng = 1f; hs_b = 0.66f; hs_b_rng = 1f
                    hs_cmy_enable = true; hs_c = 0.15f; hs_c_rng = 1f; hs_m = 0f; hs_m_rng = 1f; hs_y = 0f; hs_y_rng = 1f
                }
                2 -> { // Sylvan
                    ts(1.6f, 0.5f, 0.01f, 0.01f, false, 0f, 1f, 4f, true, 0.25f, 0.75f)
                    cwp = 2; cwp_lm = 0.25f; rs_sa = 0.25f; rs_rw = 0.25f; rs_bw = 0.55f
                    pt_enable = true; pt_lml = 0.15f; pt_lml_r = 0.5f; pt_lml_g = 0.15f; pt_lml_b = 0.1f; pt_lmh = 0.25f; pt_lmh_r = 0.15f; pt_lmh_b = 0.15f
                    ptl_enable = true; ptl_c = 0.05f; ptl_m = 0.08f; ptl_y = 0.05f
                    ptm_enable = true; ptm_low = 0.5f; ptm_low_rng = 0.5f; ptm_low_st = 0.5f; ptm_high = -0.8f; ptm_high_rng = 0.5f; ptm_high_st = 0.5f
                    brl_enable = true; brl = -1f; brl_r = -2f; brl_g = -2f; brl_b = 0f; brl_rng = 0.25f; brl_st = 0.25f
                    brlp_enable = true; brlp = -1f; brlp_r = -0.5f; brlp_g = -0.25f; brlp_b = -0.25f
                    hc_enable = true; hc_r = 1f; hc_r_rng = 0.4f
                    hs_rgb_enable = true; hs_r = 0.6f; hs_r_rng = 1.15f; hs_g = 0.8f; hs_g_rng = 1.25f; hs_b = 0.6f; hs_b_rng = 1f
                    hs_cmy_enable = true; hs_c = 0.25f; hs_c_rng = 0.25f; hs_m = 0.25f; hs_m_rng = 0.5f; hs_y = 0.35f; hs_y_rng = 0.5f
                }
                3 -> { // Colorful
                    ts(1.5f, 0.5f, 0.003f, 0.003f, false, 0f, 1f, 4f, true, 0.4f, 0.5f)
                    cwp = 2; cwp_lm = 0.25f; rs_sa = 0.35f; rs_rw = 0.25f; rs_bw = 0.55f
                    pt_enable = true; pt_lml = 0.5f; pt_lml_r = 1f; pt_lml_g = 0f; pt_lml_b = 0.5f; pt_lmh = 0.15f; pt_lmh_r = 0.15f; pt_lmh_b = 0.15f
                    ptl_enable = true; ptl_c = 0.05f; ptl_m = 0.06f; ptl_y = 0.05f
                    ptm_enable = true; ptm_low = 0.8f; ptm_low_rng = 0.5f; ptm_low_st = 0.4f; ptm_high = -0.8f; ptm_high_rng = 0.4f; ptm_high_st = 0.4f
                    brl_enable = true; brl = 0f; brl_r = -1.25f; brl_g = -1.25f; brl_b = -0.25f; brl_rng = 0.3f; brl_st = 0.5f
                    brlp_enable = true; brlp = -0.5f; brlp_r = -1.25f; brlp_g = -1.25f; brlp_b = -0.5f
                    hc_enable = true; hc_r = 1f; hc_r_rng = 0.4f
                    hs_rgb_enable = true; hs_r = 0.5f; hs_r_rng = 0.8f; hs_g = 0.35f; hs_g_rng = 1f; hs_b = 0.5f; hs_b_rng = 1f
                    hs_cmy_enable = true; hs_c = 0.25f; hs_c_rng = 1f; hs_m = 0f; hs_m_rng = 1f; hs_y = 0.25f; hs_y_rng = 1f
                }
                4 -> { // Aery
                    ts(1.15f, 0.5f, 0.04f, 0.006f, false, 0f, 0f, 0.5f, true, 0.5f, 2f)
                    cwp = 1; cwp_lm = 0.25f; rs_sa = 0.25f; rs_rw = 0.2f; rs_bw = 0.5f
                    pt_enable = true; pt_lml = 0f; pt_lml_r = 0.5f; pt_lml_g = 0.15f; pt_lml_b = 0.1f; pt_lmh = 0f; pt_lmh_r = 0.1f; pt_lmh_b = 0f
                    ptl_enable = true; ptl_c = 0.05f; ptl_m = 0.08f; ptl_y = 0.05f
                    ptm_enable = true; ptm_low = 0.8f; ptm_low_rng = 0.35f; ptm_low_st = 0.5f; ptm_high = -0.9f; ptm_high_rng = 0.5f; ptm_high_st = 0.3f
                    brl_enable = true; brl = -3f; brl_r = 0f; brl_g = 0f; brl_b = 1f; brl_rng = 0.8f; brl_st = 0.15f
                    brlp_enable = true; brlp = -1f; brlp_r = -1f; brlp_g = -1f; brlp_b = 0f
                    hc_enable = true; hc_r = 0.5f; hc_r_rng = 0.25f
                    hs_rgb_enable = true; hs_r = 0.6f; hs_r_rng = 1f; hs_g = 0.35f; hs_g_rng = 2f; hs_b = 0.5f; hs_b_rng = 1.5f
                    hs_cmy_enable = true; hs_c = 0.35f; hs_c_rng = 1f; hs_m = 0.25f; hs_m_rng = 1f; hs_y = 0.35f; hs_y_rng = 0.5f
                }
                5 -> { // Dystopic
                    ts(1.6f, 0.5f, 0.01f, 0.008f, true, 0.25f, 0f, 1f, true, 1f, 0.75f)
                    cwp = 3; cwp_lm = 0.25f; rs_sa = 0.2f; rs_rw = 0.25f; rs_bw = 0.55f
                    pt_enable = true; pt_lml = 0.15f; pt_lml_r = 0f; pt_lml_g = 0f; pt_lml_b = 0f; pt_lmh = 0f; pt_lmh_r = 0f; pt_lmh_b = 0f
                    ptl_enable = true; ptl_c = 0.05f; ptl_m = 0.08f; ptl_y = 0.05f
                    ptm_enable = true; ptm_low = 0.25f; ptm_low_rng = 0.25f; ptm_low_st = 0.8f; ptm_high = -0.8f; ptm_high_rng = 0.6f; ptm_high_st = 0.25f
                    brl_enable = true; brl = -2f; brl_r = -2f; brl_g = -2f; brl_b = 0f; brl_rng = 0.35f; brl_st = 0.35f
                    brlp_enable = true; brlp = 0f; brlp_r = -1f; brlp_g = -1f; brlp_b = -1f
                    hc_enable = true; hc_r = 1f; hc_r_rng = 0.25f
                    hs_rgb_enable = true; hs_r = 0.7f; hs_r_rng = 1.33f; hs_g = 1f; hs_g_rng = 2f; hs_b = 0.75f; hs_b_rng = 2f
                    hs_cmy_enable = true; hs_c = 1f; hs_c_rng = 0.5f; hs_m = 1f; hs_m_rng = 1f; hs_y = 1f; hs_y_rng = 0.765f
                }
                6 -> { // Umbra
                    ts(1.8f, 0.5f, 0.001f, 0.015f, false, 0f, 1f, 4f, true, 1f, 1f)
                    cwp = 5; cwp_lm = 0.25f; rs_sa = 0.35f; rs_rw = 0.25f; rs_bw = 0.55f
                    pt_enable = true; pt_lml = 0f; pt_lml_r = 0.5f; pt_lml_g = 0f; pt_lml_b = 0.15f; pt_lmh = 0.25f; pt_lmh_r = 0.25f; pt_lmh_b = 0f
                    ptl_enable = true; ptl_c = 0.05f; ptl_m = 0.06f; ptl_y = 0.05f
                    ptm_enable = true; ptm_low = 0.4f; ptm_low_rng = 0.35f; ptm_low_st = 0.66f; ptm_high = -0.6f; ptm_high_rng = 0.45f; ptm_high_st = 0.45f
                    brl_enable = true; brl = -2f; brl_r = -4.5f; brl_g = -3f; brl_b = -4f; brl_rng = 0.35f; brl_st = 0.3f
                    brlp_enable = true; brlp = 0f; brlp_r = -2f; brlp_g = -1f; brlp_b = -0.5f
                    hc_enable = true; hc_r = 1f; hc_r_rng = 0.35f
                    hs_rgb_enable = true; hs_r = 0.66f; hs_r_rng = 1f; hs_g = 0.5f; hs_g_rng = 2f; hs_b = 0.85f; hs_b_rng = 2f
                    hs_cmy_enable = true; hs_c = 0f; hs_c_rng = 1f; hs_m = 0.25f; hs_m_rng = 1f; hs_y = 0.66f; hs_y_rng = 0.66f
                }
                else -> { // Standard
                    ts(1.66f, 0.5f, 0.003f, 0.005f, false, 0f, 1f, 4f, false, 0f, 0.5f)
                    cwp = 2; cwp_lm = 0.25f; rs_sa = 0.35f; rs_rw = 0.25f; rs_bw = 0.55f
                    pt_enable = true; pt_lml = 0.25f; pt_lml_r = 0.5f; pt_lml_g = 0f; pt_lml_b = 0.1f; pt_lmh = 0.25f; pt_lmh_r = 0.5f; pt_lmh_b = 0f
                    ptl_enable = true; ptl_c = 0.06f; ptl_m = 0.08f; ptl_y = 0.06f
                    ptm_enable = true; ptm_low = 0.4f; ptm_low_rng = 0.25f; ptm_low_st = 0.5f; ptm_high = -0.8f; ptm_high_rng = 0.35f; ptm_high_st = 0.4f
                    brl_enable = true; brl = 0f; brl_r = -2.5f; brl_g = -1.5f; brl_b = -1.5f; brl_rng = 0.5f; brl_st = 0.35f
                    brlp_enable = true; brlp = -0.5f; brlp_r = -1.25f; brlp_g = -1.25f; brlp_b = -0.25f
                    hc_enable = true; hc_r = 1f; hc_r_rng = 0.3f
                    hs_rgb_enable = true; hs_r = 0.6f; hs_r_rng = 0.6f; hs_g = 0.35f; hs_g_rng = 1f; hs_b = 0.66f; hs_b_rng = 1f
                    hs_cmy_enable = true; hs_c = 0.25f; hs_c_rng = 1f; hs_m = 0f; hs_m_rng = 1f; hs_y = 0f; hs_y_rng = 1f
                }
            }
            when (tonescale) {
                1 -> ts(1.4f, 0.5f, 0.003f, 0.005f, false, 0f, 1f, 4f, false, 0f, 0.5f)
                2 -> ts(1.66f, 0.5f, 0.003f, 0.005f, false, 0f, 1f, 4f, false, 0f, 0.5f)
                3 -> ts(1.4f, 0.5f, 0.003f, 0.005f, false, 0f, 1f, 4f, true, 1f, 0.5f)
                4 -> ts(1.05f, 0.5f, 0.1f, 0.01f, false, 0f, 1f, 4f, true, 1.5f, 0.2f)
                5 -> ts(1.6f, 0.5f, 0.01f, 0.01f, false, 0f, 1f, 4f, true, 0.25f, 0.75f)
                6 -> ts(1.5f, 0.5f, 0.003f, 0.003f, false, 0f, 1f, 4f, true, 0.4f, 0.5f)
                7 -> ts(1.15f, 0.5f, 0.04f, 0.006f, false, 0f, 0f, 0.5f, true, 0.5f, 2f)
                8 -> ts(1.6f, 0.5f, 0.01f, 0.008f, true, 0.25f, 0f, 1f, true, 1f, 0.75f)
                9 -> ts(1.8f, 0.5f, 0.001f, 0.015f, false, 0f, 1f, 4f, true, 1f, 1f)
                10 -> ts(1f, 0.35f, 0.02f, 0f, true, 0.55f, 0f, 2f, true, 1.13f, 1f)
                11 -> ts(1.15f, 0.5f, 0.04f, 0f, false, 1f, 1f, 1f, false, 1f, 0.6f)
                12 -> ts(1.5f, 0.5f, 0.003f, 0.01f, true, 0.25f, 0f, 4f, true, 1f, 1f)
                13 -> ts(1.2f, 0.5f, 0.02f, 0f, false, 0f, 1f, 1f, false, 0f, 0.6f)
            }
            // creative white point: 0 = the look's, 1..6 = D93..D50 (internally 0..5)
            if (whiteSel != 0) { cwp_lm = whiteLimit; cwp = whiteSel - 1 }
        }
        return p
    }

    // ---------------------------------------------------------------- math

    private const val SQRT3 = 1.7320508f
    private const val PIf = PI.toFloat()

    private fun spowf(a: Float, b: Float) = if (a <= 0f) a else a.pow(b)
    private fun sdivf(a: Float, b: Float) = if (b == 0f) 0f else a / b
    private fun exp2(x: Float) = 2f.pow(x)
    private fun exp10(x: Float) = 10f.pow(x)

    internal fun oetf(x: Float, tf: Int): Float = when (tf) {
        -1 -> spowf(x, 2.4f) // Rec.709 video (Klipp): the display curve inverted
        -2 -> { // Apple Log (Klipp)
            val r0 = -0.05641088f; val rt = 0.01f; val c = 47.28711236f; val b = 0.00964052f; val g = 0.08550479f; val beta = 0.69336945f
            val pt = c * (rt - r0) * (rt - r0)
            if (x >= pt) exp2((x - beta) / g) - b else if (x < 0f) r0 else sqrt(x / c) + r0
        }
        1 -> if (x <= 0.02740668f) x / 10.44426855f else exp2(x / 0.07329248f - 7f) - 0.0075f
        2 -> if (x < 0.075f) (x - 0.075f) / 16.184376489665897f else exp((x - 0.5520126568606655f) / 0.09232902596577353f) - 0.0057048244042473785f
        3 -> if (x <= 0.155251141552511f) (x - 0.0729055341958355f) / 10.5402377416545f else exp2(x * 17.52f - 9.72f)
        4 -> if (x < 5.367655f * 0.010591f + 0.092809f) (x - 0.092809f) / 5.367655f else (exp10((x - 0.385537f) / 0.247190f) - 0.052272f) / 5.555556f
        5 -> if (x < -0.7774983977293537f) x * 0.3033266726886969f - 0.7774983977293537f else (exp2(14f * (x - 0.09286412512218964f) / 0.9071358748778103f + 6f) - 64f) / 2231.8263090676883f
        6 -> if (x < 0f) (x / 15.1927f) - 0.01f else (exp10(x / 0.224282f) - 1f) / 155.975327f - 0.01f
        7 -> if (x < 0.181f) (x - 0.125f) / 5.6f else exp10((x - 0.598206f) / 0.241514f) - 0.00873f
        8 -> if (x < 171.2102946929f / 1023f) (x * 1023f - 95f) * 0.01125f / (171.2102946929f - 95f) else exp10((x * 1023f - 420f) / 261.5f) * (0.18f + 0.01f) - 0.01f
        9 -> if (x < 0.100686685370811f) (x - 0.092864f) / 8.799461f else exp10((x - 0.384316f) / 0.245281f) / 5.555556f - 0.064829f / 5.555556f
        else -> x
    }

    private fun compressHyperbolicPower(x: Float, s: Float, p: Float) = spowf(x / (x + s), p)
    private fun compressToeQuadratic(x: Float, toe: Float, inv: Boolean): Float {
        if (toe == 0f) return x
        return if (!inv) spowf(x, 2f) / (x + toe) else (x + sqrt(x * (4f * toe + x))) / 2f
    }
    private fun compressToeCubic(x: Float, m: Float, w: Float, inv: Boolean): Float {
        if (m == 1f) return x
        val x2 = x * x
        if (!inv) return x * (x2 + m * w) / (x2 + w)
        val p0 = x2 - 3f * m * w
        val p1 = 2f * x2 + 27f * w - 9f * m * w
        val p2 = (sqrt(x2 * p1 * p1 - 4 * p0 * p0 * p0) / 2f + x * p1 / 2f).pow(1f / 3f)
        return p0 / (3f * p2) + p2 / 3f + x / 3f
    }
    private fun contrastHigh(x: Float, p: Float, pv: Float, pvLx: Float): Float {
        val x0 = 0.18f * 2f.pow(pv)
        if (x < x0 || p == 1f) return x
        val o = x0 - x0 / p
        val s0 = x0.pow(1f - p) / p
        val x1 = x0 * 2f.pow(pvLx)
        val k1 = p * s0 * x1.pow(p) / x1
        val y1 = s0 * x1.pow(p) + o
        return if (x > x1) k1 * (x - x1) + y1 else s0 * x.pow(p) + o
    }
    private fun softplus(x: Float, s: Float): Float {
        if (x > 10f * s || s < 1e-4f) return x
        return s * ln(max(0f, 1f + exp(x / s)))
    }
    private fun gaussWindow(x: Float, w: Float) = exp(-x * x / w)
    private fun hueOffset(h: Float, o: Float) = ((h - o + PIf) % (2f * PIf)) - PIf

    private fun mul(m: FloatArray, v: FloatArray) {
        val x = v[0]; val y = v[1]; val z = v[2]
        v[0] = m[0] * x + m[1] * y + m[2] * z
        v[1] = m[3] * x + m[4] * y + m[5] * z
        v[2] = m[6] * x + m[7] * y + m[8] * z
    }

    /** The matrices from OpenDRT (row-major). */
    object M {
        val AP1_TO_XYZ = floatArrayOf(0.652418717671912951f, 0.127179925537538263f, 0.170857283842220459f, 0.268064059194271287f, 0.672464478992617742f, 0.0594714618131108388f, -0.0054699285104975676f, 0.00518279997697511721f, 1.08934487929340107f)
        val REC2020_TO_XYZ = floatArrayOf(0.636958048301290991f, 0.144616903586208406f, 0.168880975164172054f, 0.26270021201126692f, 0.677998071518871148f, 0.0593017164698619384f, 4.9999999999999999e-17f, 0.0280726930490874452f, 1.06098505771079066f)
        val AWG3_TO_XYZ = floatArrayOf(0.638007619284f, 0.214703856337f, 0.097744451431f, 0.291953779f, 0.823841041511f, -0.11579482051f, 0.002798279032f, -0.067034235689f, 1.15329370742f)
        val AWG4_TO_XYZ = floatArrayOf(0.704858320407231953f, 0.129760295170463003f, 0.115837311473976537f, 0.254524176404026969f, 0.781477732712002049f, -0.0360019091160290391f, 0f, 0f, 1.08905775075987843f)
        val REDWG_TO_XYZ = floatArrayOf(0.735275245905858799f, 0.0686094106139610721f, 0.14657127053185201f, 0.286694099499934962f, 0.842979134016975662f, -0.129673233516910319f, -0.0796808568783676785f, -0.347343216994429771f, 1.51608182463267593f)
        val SGAMUT3_TO_XYZ = floatArrayOf(0.706482713192318812f, 0.12880104979055762f, 0.115172164068795255f, 0.270979670813492168f, 0.786606411220905466f, -0.0575860820343976273f, -0.00967784538619615754f, 0.00460003749251991934f, 1.09413555865355483f)
        val SGAMUT3CINE_TO_XYZ = floatArrayOf(0.599083920758327171f, 0.248925516115423628f, 0.102446490177920776f, 0.215075820115587457f, 0.88506850174372842f, -0.100144321859315821f, -0.0320658495445057951f, -0.0276583906794915374f, 1.1487819909838759f)
        val VGAMUT_TO_XYZ = floatArrayOf(0.679644469878f, 0.15221141244f, 0.118600044733f, 0.26068555009f, 0.77489446333f, -0.03558001342f, -0.009310198218f, -0.004612467044f, 1.10298041602f)
        val EGAMUT_TO_XYZ = floatArrayOf(0.705396850087770755f, 0.164041328309919021f, 0.0810177486539819941f, 0.280130724091105898f, 0.820206641549595106f, -0.100337365640700782f, -0.103781511569163279f, -0.0729072570266306313f, 1.26574651935567273f)
        val DWG_TO_XYZ = floatArrayOf(0.700622392093671609f, 0.148774815123196763f, 0.101058719834803246f, 0.274118510906649016f, 0.873631895940436665f, -0.147750406847085763f, -0.0989629128832311411f, -0.137895325075543307f, 1.32591598871865268f)
        val P3D65_TO_XYZ = floatArrayOf(0.486570948648216151f, 0.265667693169093f, 0.198217285234362467f, 0.228974564069748754f, 0.691738521836506193f, 0.079286914093744984f, -4.00000000000000029e-17f, 0.0451133818589026167f, 1.04394436890097575f)
        val XYZ_TO_P3D65 = floatArrayOf(2.49349691194142542f, -0.93138361791912383f, -0.402710784450716841f, -0.829488969561574696f, 1.76266406031834655f, 0.0236246858419435941f, 0.0358458302437844531f, -0.0761723892680418041f, 0.956884524007687309f)
        val REC709_TO_XYZ = floatArrayOf(0.412390799265959229f, 0.357584339383878125f, 0.180480788401834347f, 0.212639005871510217f, 0.71516867876775625f, 0.0721923153607337414f, 0.0193308187155918181f, 0.119194779794626018f, 0.950532152249661033f)
        val XYZ_TO_REC709 = floatArrayOf(3.24096994190452348f, -1.53738317757009435f, -0.498610760293003552f, -0.969243636280879506f, 1.87596750150771996f, 0.0415550574071755843f, 0.0556300796969936354f, -0.20397695888897649f, 1.05697151424287816f)
        // D65 → D93, D75, (D65), D60, D55, D50 (CAT02)
        val CAT_D65 = arrayOf(
            floatArrayOf(0.95703423023223877f, -0.0247171502560377121f, 0.0624028593301773071f, -0.0179296955466270447f, 0.990019857883453369f, 0.0248119533061981201f, 0.00127589143812656403f, 0.00427919067442417058f, 1.29345715045928955f),
            floatArrayOf(0.981001079082489014f, -0.0116619253531098366f, 0.0265614092350006104f, -0.00843488052487373352f, 0.996506094932556152f, 0.0105696544051170349f, 0.000552809564396739006f, 0.00179840810596942902f, 1.12374722957611084f),
            null,
            floatArrayOf(1.01182246208190918f, 0.00778879318386316299f, -0.0157783031463623047f, 0.00561682833358645439f, 1.00150644779205322f, -0.00628517568111419678f, -0.000335735734552145004f, -0.0010509500280022619f, 0.927366673946380615f),
            floatArrayOf(1.02585089206695557f, 0.0179439820349216461f, -0.0332137793302536011f, 0.0129133854061365128f, 1.00214779376983643f, -0.0132421031594276428f, -0.000719940289855003032f, -0.00218106806278228803f, 0.84868013858795166f),
            floatArrayOf(1.04257404804229736f, 0.03089117631316185f, -0.052812620997428894f, 0.0221935361623764038f, 1.00185668468475342f, -0.0210737623274326324f, -0.00116488314233720303f, -0.00342052709311246915f, 0.761789083480834961f),
        )
        /** Normalization by the white point for Rec.709 (D93, D75, D65, D60, D55, D50). */
        val CWP_NORM_709 = floatArrayOf(0.744192699063f, 0.873470832146f, 1f, 0.955936992163f, 0.905671332781f, 0.850004385027f)
    }

    // ---------------------------------------------------------------- the transform itself

    /** A prepared OpenDRT (the presets and the tonescale constants computed once). */
    class Transform(drt: Drt) {
        private val p = preset(drt.look, drt.tonescale, drt.cwp, drt.cwpLimit)
        private val input = Input.of(drt.input)
        private val eotf = if (drt.display == 1) 2 else 1 // 2.4 Rec.1886 / 2.2 sRGB display
        private val tnSu = if (drt.display == 1) 1 else 2
        private val tnLp = 100f
        private val tnLg = drt.grey
        private val tnGb = 0.13f
        private val ptHdr = 0.5f

        private val tsX1 = 2f.pow(6f * p.tn_sh + 4f)
        private val tsY1 = tnLp / 100f
        private val tsX0 = 0.18f + p.tn_off
        private val tsY0 = tnLg / 100f * (1f + tnGb * (ln(tsY1) / ln(2f)))
        private val tsS0 = compressToeQuadratic(tsY0, p.tn_toe, true)
        private val tsP = p.tn_con / (1f + tnSu * 0.05f)
        private val tsS10 = tsX0 * (tsS0.pow(-1f / p.tn_con) - 1f)
        private val tsM1 = tsY1 / (tsX1 / (tsX1 + tsS10)).pow(p.tn_con)
        private val tsM2 = compressToeQuadratic(tsM1, p.tn_toe, true)
        private val tsS = tsX0 * ((tsS0 / tsM2).pow(-1f / p.tn_con) - 1f)
        private val tsDsc = 100f / tnLp
        private val ptCmpLf = ptHdr * min(1f, (tnLp - 100f) / 900f)
        private val sLp100 = tsX0 * ((tnLg / 100f).pow(-1f / p.tn_con) - 1f)
        private val tsS1 = tsS * ptCmpLf + sLp100 * (1f - ptCmpLf)
        private val lconM = 2f.pow(-p.tn_lcon)
        private val lconW = (p.tn_lcon_w / 4f).let { it * it }
        private val lconSc = if (p.tn_lcon_enable) compressToeCubic(tsX0, lconM, lconW, true) / tsX0 else 1f
        private val hconP = 2f.pow(p.tn_hcon)

        /** One pixel: code values in the input format (0..1) → display code values (0..1). [rgb] is overwritten. */
        fun apply(rgb: FloatArray) {
            for (i in 0..2) rgb[i] = oetf(rgb[i], input.oetf)
            mul(input.gamut, rgb)
            mul(M.XYZ_TO_P3D65, rgb)

            // rendering space
            val rsW0 = p.rs_rw; val rsW1 = 1f - p.rs_rw - p.rs_bw; val rsW2 = p.rs_bw
            var satL = rgb[0] * rsW0 + rgb[1] * rsW1 + rgb[2] * rsW2
            for (i in 0..2) rgb[i] = satL * p.rs_sa + rgb[i] * (1f - p.rs_sa) + p.tn_off

            var tsn = sqrt(max(0f, rgb[0] * rgb[0] + rgb[1] * rgb[1] + rgb[2] * rgb[2])) / SQRT3
            for (i in 0..2) rgb[i] = sdivf(rgb[i], tsn)

            val oppX = rgb[0] - rgb[2]
            val oppY = rgb[1] - (rgb[0] + rgb[2]) / 2f
            var achD = sqrt(max(0f, oppX * oppX + oppY * oppY)) / 2f
            achD = 1.25f * compressToeQuadratic(achD, 0.25f, false)
            val hue = (atan2(oppX, oppY) + PIf + 1.10714931f) % (2f * PIf)

            val haR = gaussWindow(hueOffset(hue, 0.1f), 0.66f)
            val haG = gaussWindow(hueOffset(hue, 4.3f), 0.66f)
            val haB = gaussWindow(hueOffset(hue, 2.3f), 0.66f)
            val hsR = gaussWindow(hueOffset(hue, -0.4f), 0.66f)
            val hsG = haG
            val hsB = gaussWindow(hueOffset(hue, 2.5f), 0.66f)
            val cmyC = gaussWindow(hueOffset(hue, 3.3f), 0.5f)
            val cmyM = gaussWindow(hueOffset(hue, 1.3f), 0.5f)
            val cmyY = gaussWindow(hueOffset(hue, -1.15f), 0.5f)

            if (p.brl_enable) {
                val tsf = (tsn / (tsn + 1f)).pow(1f - p.brl_rng)
                val exf = (p.brl + p.brl_r * haR + p.brl_g * haG + p.brl_b * haB) * achD.pow(1f / p.brl_st)
                tsn *= 2f.pow(exf * (if (exf < 0f) tsf else 1f - tsf))
            }
            if (p.tn_lcon_enable) {
                tsn *= lconSc
                tsn = compressToeCubic(tsn, lconM, lconW, false)
            }
            if (p.tn_hcon_enable) tsn = contrastHigh(tsn, hconP, p.tn_hcon_pv, p.tn_hcon_st)

            val tsnPt = compressHyperbolicPower(tsn, tsS1, tsP)
            val tsnConst = compressHyperbolicPower(tsn, sLp100, tsP)
            tsn = compressHyperbolicPower(tsn, tsS, tsP)

            if (p.hc_enable) {
                var hcTs = 1f - tsnConst
                var hcC = hcTs * (1f - achD) + achD * (1f - hcTs)
                hcC *= achD * haR
                hcTs = hcTs.pow(1f / p.hc_r_rng)
                val hcF = p.hc_r * (hcC - 2f * hcC * hcTs) + 1f
                rgb[1] *= hcF; rgb[2] *= hcF
            }
            if (p.hs_rgb_enable) {
                val x = hsR * achD * tsnPt.pow(1f / p.hs_r_rng) * p.hs_r
                val y = hsG * achD * tsnPt.pow(1f / p.hs_g_rng) * -p.hs_g
                val z = hsB * achD * tsnPt.pow(1f / p.hs_b_rng) * -p.hs_b
                rgb[0] += z - y; rgb[1] += x - z; rgb[2] += y - x
            }
            if (p.hs_cmy_enable) {
                val c = 1f - tsnPt
                val x = cmyC * achD * c.pow(1f / p.hs_c_rng) * -p.hs_c
                val y = cmyM * achD * c.pow(1f / p.hs_m_rng) * p.hs_m
                val z = cmyY * achD * c.pow(1f / p.hs_y_rng) * p.hs_y
                rgb[0] += z - y; rgb[1] += x - z; rgb[2] += y - x
            }

            // purity compression
            val ptLmlP = 1f + 4f * (1f - tsnPt) * (p.pt_lml + p.pt_lml_r * hsR + p.pt_lml_g * hsG + p.pt_lml_b * hsB)
            var ptf = 1f - tsnPt.pow(ptLmlP)
            val ptLmhP = (1f - achD * (p.pt_lmh_r * hsR + p.pt_lmh_b * hsB)) * (1f - p.pt_lmh * achD)
            ptf = ptf.pow(ptLmhP)
            if (p.ptm_enable) {
                val low = if (p.ptm_low_st == 0f || p.ptm_low_rng == 0f) 1f
                    else 1f + p.ptm_low * exp(-2f * achD * achD / p.ptm_low_st) * (1f - tsnConst).pow(1f / p.ptm_low_rng)
                val high = if (p.ptm_high_st == 0f || p.ptm_high_rng == 0f) 1f
                    else 1f + p.ptm_high * exp(-2f * achD * achD / p.ptm_high_st) * tsnPt.pow(1f / (4f * p.ptm_high_rng))
                ptf *= low * high
            }
            for (i in 0..2) rgb[i] = rgb[i] * ptf + 1f - ptf

            // back from the rendering space
            satL = rgb[0] * rsW0 + rgb[1] * rsW1 + rgb[2] * rsW2
            for (i in 0..2) rgb[i] = (satL * p.rs_sa - rgb[i]) / (p.rs_sa - 1f)

            displayWhite(rgb, tsnConst)

            if (p.brlp_enable) {
                val bx = rgb[0] - rgb[2]
                val by = rgb[1] - (rgb[0] + rgb[2]) / 2f
                var bAch = sqrt(max(0f, bx * bx + by * by)) / 4f
                bAch = 1.1f * (bAch * bAch / (bAch + 0.1f))
                val m = p.brlp + p.brlp_r * achD * haR + p.brlp_g * achD * haG + p.brlp_b * achD * haB
                val ex = 2f.pow(m * bAch * tsn)
                for (i in 0..2) rgb[i] *= ex
            }
            if (p.ptl_enable) { rgb[0] = softplus(rgb[0], p.ptl_c); rgb[1] = softplus(rgb[1], p.ptl_m); rgb[2] = softplus(rgb[2], p.ptl_y) }

            tsn *= tsM2
            tsn = compressToeQuadratic(tsn, p.tn_toe, false)
            tsn *= tsDsc
            val ep = 2f + eotf * 0.2f
            for (i in 0..2) rgb[i] = spowf((rgb[i] * tsn).coerceIn(0f, 1f), 1f / ep)
        }

        /** P3-D65 → the display's Rec.709 with the creative white point (only D65 displays in Klipp). */
        private fun displayWhite(rgb: FloatArray, tsn: Float) {
            mul(M.P3D65_TO_XYZ, rgb)
            val n0 = rgb[0]; val n1 = rgb[1]; val n2 = rgb[2]
            val f = tsn.pow(2f * p.cwp_lm)
            M.CAT_D65.getOrNull(p.cwp)?.let { mul(it, rgb) }
            for (i in 0..2) rgb[i] = rgb[i] * f + (if (i == 0) n0 else if (i == 1) n1 else n2) * (1f - f)
            mul(M.XYZ_TO_REC709, rgb)
            val norm = M.CWP_NORM_709.getOrElse(p.cwp) { 1f }
            val k = norm * f + 1f - f
            for (i in 0..2) rgb[i] *= k
        }
    }

    // ---------------------------------------------------------------- LUT in the node

    private const val SIZE = 33
    private val cache = object : LinkedHashMap<String, CubeLut>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CubeLut>?) = size > 8
    }

    /**
     * The node's LUT followed by OpenDRT: [base] (the node's own LUT, mixed by [baseMix]) and then the transform,
     * baked into a 33³ LUT. The same object for the same settings (the GPU uploads it once).
     */
    /** A LUT has been re-imported: everything baked with that name is baked again. */
    fun clearCache() = synchronized(cache) { cache.clear() }

    fun composite(base: CubeLut?, baseMix: Float, drt: Drt): CubeLut {
        val mixQ = (baseMix.coerceIn(0f, 1f) * 50f).toInt() / 50f
        val key = "${base?.name}|${if (base != null) mixQ else 0f}|$drt"
        synchronized(cache) { cache[key]?.let { return it } }
        val t = Transform(drt)
        val d = FloatArray(SIZE * SIZE * SIZE * 3)
        val px = FloatArray(3)
        var i = 0
        val n1 = (SIZE - 1).toFloat()
        for (b in 0 until SIZE) for (g in 0 until SIZE) for (r in 0 until SIZE) {
            px[0] = r / n1; px[1] = g / n1; px[2] = b / n1
            if (base != null && mixQ > 0f) {
                val o = base.applyTetrahedral(px[0], px[1], px[2])
                for (k in 0..2) px[k] += (o[k].coerceIn(0f, 1f) - px[k]) * mixQ
            }
            t.apply(px)
            for (k in 0..2) d[i++] = if (px[k].isNaN()) 0f else px[k].coerceIn(0f, 1f)
        }
        val lut = CubeLut("OpenDRT $key", SIZE, d, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        synchronized(cache) { cache[key] = lut }
        return lut
    }
}
