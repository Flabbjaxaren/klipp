package se.gendut.klipp.engine

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * SDR pictures in an HLG export, after ITU-R BT.2408 (display-light mapping). In the HLG export the graded
 * clips' last OpenDRT node already renders Rec.2100 HLG; ungraded clips, titles and captions were written as their SDR
 * code values, so they came out too dark and desaturated in the HDR file. Here an SDR code value (BT.709, a 2.4
 * display gamma) becomes display light with SDR white at the 203 cd/m² reference of a 1000 cd/m² HLG display
 * (BT.709 → BT.2020 primaries, in linear light), then the HLG inverse OOTF (system gamma 1.2) and the OETF: SDR white
 * lands at 75 % HLG, as BT.2408 recommends.
 */
object SdrHlg {
    private const val REF_WHITE = 203f / 1000f
    private const val GAMMA = 1.2f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    // BT.709 → BT.2020 (linear light), ITU-R BT.2087
    private val M = floatArrayOf(
        0.6274f, 0.3293f, 0.0433f,
        0.0691f, 0.9195f, 0.0114f,
        0.0164f, 0.0880f, 0.8956f,
    )

    private fun oetf(e: Float): Float {
        val x = e.coerceAtLeast(0f)
        return if (x <= 1f / 12f) sqrt(3f * x) else A * ln(12f * x - B) + C
    }

    /** One SDR code value triple (0..1) → HLG code values (0..1). [rgb] is overwritten. */
    fun apply(rgb: FloatArray) {
        val r0 = rgb[0].coerceIn(0f, 1f).pow(2.4f)
        val g0 = rgb[1].coerceIn(0f, 1f).pow(2.4f)
        val b0 = rgb[2].coerceIn(0f, 1f).pow(2.4f)
        // display light on the 1000 cd/m² display, normalised to its peak
        val r = (M[0] * r0 + M[1] * g0 + M[2] * b0) * REF_WHITE
        val g = (M[3] * r0 + M[4] * g0 + M[5] * b0) * REF_WHITE
        val b = (M[6] * r0 + M[7] * g0 + M[8] * b0) * REF_WHITE
        // inverse OOTF: scene light = display light · Yd^(1/γ − 1)
        val yd = 0.2627f * r + 0.6780f * g + 0.0593f * b
        val k = if (yd > 0f) yd.pow(1f / GAMMA - 1f) else 0f
        rgb[0] = oetf(max(0f, r * k)); rgb[1] = oetf(max(0f, g * k)); rgb[2] = oetf(max(0f, b * k))
    }

    /** As a 33³ LUT for a clip's chain ([GradeNode.sdrToHlg]). */
    val lut: CubeLut by lazy {
        val n = 33
        val d = FloatArray(n * n * n * 3)
        val px = FloatArray(3)
        var i = 0
        for (bb in 0 until n) for (gg in 0 until n) for (rr in 0 until n) {
            px[0] = rr / (n - 1f); px[1] = gg / (n - 1f); px[2] = bb / (n - 1f)
            apply(px)
            for (k in 0..2) d[i++] = px[k].coerceIn(0f, 1f)
        }
        CubeLut("SDR→HLG (BT.2408)", n, d, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    }

    /** A title's or caption's bitmap (ARGB_8888, not premultiplied in getPixels) to HLG code values, in place. */
    fun bitmap(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
        val out = if (bmp.isMutable) bmp else bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val w = out.width; val h = out.height
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        val memo = HashMap<Int, Int>()
        val c = FloatArray(3)
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            if (a == 0) continue
            val rgbIn = p and 0xFFFFFF
            val rgbOut = memo.getOrPut(rgbIn) {
                c[0] = ((rgbIn shr 16) and 255) / 255f; c[1] = ((rgbIn shr 8) and 255) / 255f; c[2] = (rgbIn and 255) / 255f
                apply(c)
                (Math.round(c[0] * 255f) shl 16) or (Math.round(c[1] * 255f) shl 8) or Math.round(c[2] * 255f)
            }
            px[i] = (a shl 24) or rgbOut
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}
