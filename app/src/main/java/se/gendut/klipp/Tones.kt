package se.gendut.klipp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Material 3 tonal colors: a color as hue, chroma and tone (HCT – CAM16 hue and chroma, L* as tone), and tonal
 * palettes made from a seed color. Every interface color that follows the accent is a tone of such a palette, so one
 * seed gives the whole family (button, text on it, container, text on the container …) with the same contrast steps
 * whatever the hue.
 */
object Tones {
    // CAM16 under the default viewing conditions: D65 white, a mid-gray background (L* 50), average surround
    private val WHITE = doubleArrayOf(95.047, 100.0, 108.883)
    private const val C = 0.69
    private const val NC = 1.0
    private val LA = 200.0 / PI * yOf(50.0) / 100.0
    private val N = yOf(50.0) / WHITE[1]
    private val Z = 1.48 + sqrt(N)
    private val NBB = 0.725 / N.pow(0.2)
    private val FL: Double
    private val RGB_D: DoubleArray
    private val AW: Double

    init {
        val w = toCone(WHITE[0], WHITE[1], WHITE[2])
        val d = (1.0 - 1.0 / 3.6 * exp((-LA - 42.0) / 92.0)).coerceIn(0.0, 1.0)
        RGB_D = DoubleArray(3) { d * (100.0 / w[it]) + 1.0 - d }
        val k = 1.0 / (5.0 * LA + 1.0)
        val k4 = k * k * k * k
        FL = 0.2 * k4 * (5.0 * LA) + 0.1 * (1.0 - k4) * (1.0 - k4) * cbrt(5.0 * LA)
        val a = DoubleArray(3) { val f = (FL * RGB_D[it] * w[it] / 100.0).pow(0.42); 400.0 * f / (f + 27.13) }
        AW = (2.0 * a[0] + a[1] + 0.05 * a[2]) * NBB
    }

    private fun toCone(x: Double, y: Double, z: Double) = doubleArrayOf(
        0.401288 * x + 0.650173 * y - 0.051461 * z,
        -0.250268 * x + 1.204414 * y + 0.045854 * z,
        -0.002079 * x + 0.048952 * y + 0.953127 * z,
    )

    /** Relative luminance (0..100) of a tone (L*). */
    fun yOf(tone: Double): Double {
        val f = (tone + 16.0) / 116.0
        return 100.0 * if (f * f * f > 216.0 / 24389.0) f * f * f else (116.0 * f - 16.0) / (24389.0 / 27.0)
    }

    private fun toneOfY(y: Double): Double {
        val t = y / 100.0
        return if (t > 216.0 / 24389.0) 116.0 * cbrt(t) - 16.0 else 24389.0 / 27.0 * t
    }

    private fun linear(c: Int): Double {
        val v = c / 255.0
        return 100.0 * if (v <= 0.040449936) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun gamma(l: Double): Int {
        val v = l / 100.0
        val d = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055
        return (d * 255.0).roundToInt().coerceIn(0, 255)
    }

    private fun xyzOf(argb: Int): DoubleArray {
        val r = linear(argb shr 16 and 0xFF)
        val g = linear(argb shr 8 and 0xFF)
        val b = linear(argb and 0xFF)
        return doubleArrayOf(
            0.41233895 * r + 0.35762064 * g + 0.18051042 * b,
            0.2126 * r + 0.7152 * g + 0.0722 * b,
            0.01932141 * r + 0.11916382 * g + 0.95034478 * b,
        )
    }

    /** The tone (L*, 0..100) of a color. */
    fun toneOf(argb: Int): Double = toneOfY(xyzOf(argb)[1])

    /** Hue (0..360), chroma and tone of a color. */
    fun hct(argb: Int): DoubleArray {
        val xyz = xyzOf(argb)
        val cone = toCone(xyz[0], xyz[1], xyz[2])
        val a3 = DoubleArray(3) {
            val d = RGB_D[it] * cone[it]
            val f = (FL * abs(d) / 100.0).pow(0.42)
            sign(d) * 400.0 * f / (f + 27.13)
        }
        val a = (11.0 * a3[0] - 12.0 * a3[1] + a3[2]) / 11.0
        val b = (a3[0] + a3[1] - 2.0 * a3[2]) / 9.0
        val u = (20.0 * a3[0] + 20.0 * a3[1] + 21.0 * a3[2]) / 20.0
        val p2 = (40.0 * a3[0] + 20.0 * a3[1] + a3[2]) / 20.0
        val hue = (Math.toDegrees(atan2(b, a)) + 360.0) % 360.0
        val j = 100.0 * (p2 * NBB / AW).coerceAtLeast(0.0).pow(C * Z)
        val e = 0.25 * (cos(Math.toRadians(hue) + 2.0) + 3.8)
        val t = 50000.0 / 13.0 * e * NC * NBB * hypot(a, b) / (u + 0.305)
        val chroma = t.pow(0.9) * (1.64 - 0.29.pow(N)).pow(0.73) * sqrt(j / 100.0)
        return doubleArrayOf(hue, chroma, toneOfY(xyz[1]))
    }

    /** Linear sRGB (0..100 per channel, may be out of range) of a CAM16 lightness, chroma and hue. */
    private fun linearOf(j: Double, chroma: Double, hue: Double): DoubleArray {
        val alpha = if (chroma == 0.0 || j == 0.0) 0.0 else chroma / sqrt(j / 100.0)
        val t = (alpha / (1.64 - 0.29.pow(N)).pow(0.73)).pow(1.0 / 0.9)
        val h = Math.toRadians(hue)
        val e = 0.25 * (cos(h + 2.0) + 3.8)
        val ac = AW * (j / 100.0).pow(1.0 / (C * Z))
        val p1 = e * (50000.0 / 13.0) * NC * NBB
        val p2 = ac / NBB
        val g = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * cos(h) + 108.0 * t * sin(h))
        val a = g * cos(h)
        val b = g * sin(h)
        val a3 = doubleArrayOf(
            (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0,
            (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0,
            (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0,
        )
        val f = DoubleArray(3) {
            val base = (27.13 * abs(a3[it]) / (400.0 - abs(a3[it]))).coerceAtLeast(0.0)
            sign(a3[it]) * (100.0 / FL) * base.pow(1.0 / 0.42) / RGB_D[it]
        }
        val x = 1.86206786 * f[0] - 1.01125463 * f[1] + 0.14918677 * f[2]
        val y = 0.38752654 * f[0] + 0.62144744 * f[1] - 0.00897398 * f[2]
        val z = -0.01584150 * f[0] - 0.03412294 * f[1] + 1.04996444 * f[2]
        return doubleArrayOf(
            3.2413774792388685 * x - 1.5376652402851851 * y - 0.49885366846268053 * z,
            -0.9691452513005321 * x + 1.8758853451067872 * y + 0.04156585616912061 * z,
            0.05562093689691305 * x - 0.20395524564742123 * y + 1.0571799111220335 * z,
        )
    }

    /** The color with this hue, chroma and tone when sRGB can show it, otherwise null. */
    private fun exact(hue: Double, chroma: Double, tone: Double): Int? {
        val y = yOf(tone)
        var lo = 0.0
        var hi = 100.0
        var rgb = linearOf(50.0, chroma, hue)
        repeat(40) {
            val j = (lo + hi) / 2.0
            rgb = linearOf(j, chroma, hue)
            val yj = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
            if (yj.isNaN() || yj > y) hi = j else lo = j
        }
        if (rgb.any { it.isNaN() || it < -0.05 || it > 100.05 }) return null
        return (0xFF shl 24) or (gamma(rgb[0]) shl 16) or (gamma(rgb[1]) shl 8) or gamma(rgb[2])
    }

    private fun gray(tone: Double): Int = gamma(yOf(tone)).let { (0xFF shl 24) or (it shl 16) or (it shl 8) or it }

    /**
     * The color with this hue and tone and as much of the chroma as sRGB can show: the tone always holds (contrast
     * depends on it), chroma gives way.
     */
    fun color(hue: Double, chroma: Double, tone: Double): Int {
        if (tone <= 0.01) return 0xFF000000.toInt()
        if (tone >= 99.99) return 0xFFFFFFFF.toInt()
        if (chroma < 0.5) return gray(tone)
        exact(hue, chroma, tone)?.let { return it }
        var lo = 0.0
        var hi = chroma
        var best = gray(tone)
        while (hi - lo > 0.2) {
            val mid = (lo + hi) / 2.0
            val c = exact(hue, mid, tone)
            if (c != null) { best = c; lo = mid } else hi = mid
        }
        return best
    }

    /** The chroma a color of this hue and tone really gets (the most sRGB can show, up to [chroma]). */
    fun reach(hue: Double, chroma: Double, tone: Double): Double = hct(color(hue, chroma, tone))[1]
}

/** The tones of one hue and chroma: [tone] 0 is black, 100 white, and equal tones of any palette are equally light. */
class TonalPalette(val hue: Double, val chroma: Double) {
    private val cache = HashMap<Int, Int>()

    fun tone(t: Int): Int = synchronized(cache) { cache.getOrPut(t) { Tones.color(hue, chroma, t.toDouble()) } }

    /** The tone in [from]..[to] where the hue is the most colorful (yellows and limes peak light, blues and reds darker). */
    fun vividTone(from: Int, to: Int): Int {
        var best = from
        var bestC = -1.0
        for (t in from..to) {
            val c = Tones.reach(hue, chroma, t.toDouble())
            if (c > bestC + 0.5) { bestC = c; best = t }
        }
        return best
    }
}

/**
 * The interface colors an accent gives a dark theme, by the Material 3 roles: the palette is the seed's hue at its own
 * chroma (at least 48); the roles are fixed tones of it.
 */
class AccentRoles(seed: Int) {
    private val hc = Tones.hct(seed)
    val primaryPalette = TonalPalette(hc[0], maxOf(48.0, hc[1]))

    /**
     * Filled buttons: the chosen color's own tone when it is light enough for a dark theme (80–90), otherwise the nearest
     * tone of that band – a light lime stays the lime that was picked, a deep blue is lifted to tone 80.
     */
    val primaryTone = hc[2].roundToInt().coerceIn(80, 90)
    val primary = primaryPalette.tone(primaryTone)
    val onPrimary = primaryPalette.tone(20)
    /** Selected and "on" states. */
    val container = primaryPalette.tone(30)
    val onContainer = primaryPalette.tone(90)
}
