package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.Drt
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.OpenDrt
import kotlin.math.abs

/**
 * The OpenDRT port against the original: opendrt_ref.txt is output from OpenDRT.dctl v1.1.0 compiled as C++
 * (unchanged code, same presets) for 24 settings × 2 mid grays × 2 displays × 12 colors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenDrtTest {
    private val inputs = mapOf(
        (8 to 10) to "slog3", (8 to 9) to "slog3sg", (4 to 6) to "logc3", (7 to 11) to "vlog", (1 to 14) to "di", (5 to 7) to "logc4",
        (6 to 8) to "red", (9 to 4) to "flog2", (3 to 2) to "acescct", (2 to 12) to "tlog",
    )

    @Test fun matchesTheOriginalDctl() {
        val lines = javaClass.classLoader!!.getResourceAsStream("opendrt_ref.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(1152, lines.size)
        var worst = 0f
        val transforms = HashMap<Drt, OpenDrt.Transform>()
        for (l in lines) {
            val v = l.trim().split(Regex("\\s+"))
            val key = inputs.getValue(v[0].toInt() to v[1].toInt())
            val cwp = v[4].toInt()
            // DCTL: encoding 1 = sRGB display (Klipp's 0), 0 = Rec.1886 (Klipp's 1)
            val drt = Drt(key, v[2].toInt(), v[3].toInt(), cwp, if (cwp != 0) 0.4f else 0.25f, v[5].toFloat(), if (v[6].toInt() == 1) 0 else 1)
            val t = transforms.getOrPut(drt) { OpenDrt.Transform(drt) }
            val px = floatArrayOf(v[7].toFloat(), v[8].toFloat(), v[9].toFloat())
            t.apply(px)
            for (k in 0..2) {
                val d = abs(px[k] - v[10 + k].toFloat())
                if (d > worst) worst = d
                assertTrue("$l → ${px.toList()}", d < 2e-3f)
            }
        }
        println("OpenDRT largest deviation from the original: $worst")
    }

    /**
     * vivo Log has no published spec: the decode and the gamut were measured from DaVinci Resolve 21.1.1 (input
     * "Vivo Wide Gamut/Vivo Log" → DaVinci WG/Intermediate, 16-bit TIFF render). Rows: vivo Log code values, then
     * Resolve's DaVinci Intermediate output.
     */
    @Test fun vivoLogMatchesResolve() {
        val ref = listOf(
            doubleArrayOf(0.097752, 0.097752, 0.097752, 0.008927, 0.008927, 0.008927),
            doubleArrayOf(0.127077, 0.127077, 0.127077, 0.057298, 0.057298, 0.057298),
            doubleArrayOf(0.136852, 0.136852, 0.136852, 0.069993, 0.069993, 0.069993),
            doubleArrayOf(0.195503, 0.195503, 0.195503, 0.139864, 0.139864, 0.139864),
            doubleArrayOf(0.293255, 0.293255, 0.293255, 0.241596, 0.241596, 0.241596),
            doubleArrayOf(0.391007, 0.391007, 0.391007, 0.335973, 0.335973, 0.335973),
            doubleArrayOf(0.500489, 0.500489, 0.500489, 0.438392, 0.438392, 0.438392),
            doubleArrayOf(0.586510, 0.586510, 0.586510, 0.517891, 0.517891, 0.517891),
            doubleArrayOf(0.684262, 0.684262, 0.684262, 0.607782, 0.607782, 0.607782),
            doubleArrayOf(0.782014, 0.782014, 0.782014, 0.697459, 0.697459, 0.697459),
            doubleArrayOf(0.879765, 0.879765, 0.879765, 0.787060, 0.787060, 0.787060),
            doubleArrayOf(1.000000, 1.000000, 1.000000, 0.897230, 0.897230, 0.897230),
            doubleArrayOf(0.416145, 0.289692, 0.407802, 0.356695, 0.258106, 0.352361),
            doubleArrayOf(0.235017, 0.716324, 0.936695, 0.551080, 0.668208, 0.831510),
            doubleArrayOf(0.833423, 0.531836, 0.460416, 0.734813, 0.506935, 0.507652),
            doubleArrayOf(0.289422, 0.344338, 0.285775, 0.239963, 0.286839, 0.234455),
            doubleArrayOf(0.913132, 0.139296, 0.539034, 0.807584, 0.480552, 0.580652),
            doubleArrayOf(0.421525, 0.612608, 0.743997, 0.441749, 0.552865, 0.655344),
            doubleArrayOf(0.932745, 0.934417, 0.898238, 0.834043, 0.835203, 0.806577),
            doubleArrayOf(0.103058, 0.352591, 0.863404, 0.474128, 0.499519, 0.764340),
            doubleArrayOf(0.856274, 0.632563, 0.685380, 0.757321, 0.585092, 0.631449),
            doubleArrayOf(0.555184, 0.739508, 0.843301, 0.550820, 0.665202, 0.746578),
            doubleArrayOf(0.976657, 0.450161, 0.761033, 0.866606, 0.573388, 0.712642),
            doubleArrayOf(0.289862, 0.732584, 0.858128, 0.495186, 0.661463, 0.759564),
        )
        val vivo = OpenDrt.Input.of("vivo")
        assertEquals(OpenDrt.Input.VIVO, vivo)
        val toDwg = inv3(OpenDrt.M.DWG_TO_XYZ.map { it.toDouble() })
        val g = vivo.gamut.map { it.toDouble() }
        // white stays white (D65 from x 0.3127, y 0.3290)
        for (r in 0..2) assertEquals(listOf(0.3127 / 0.329, 1.0, (1 - 0.3127 - 0.329) / 0.329)[r], g[r * 3] + g[r * 3 + 1] + g[r * 3 + 2], 1e-4)
        fun di(x: Double) = if (x <= 0.00262409) x * 10.44426855 else (kotlin.math.log2(x + 0.0075) + 7.0) * 0.07329248
        var worst = 0.0
        for (row in ref) {
            val lin = DoubleArray(3) { OpenDrt.oetf(row[it].toFloat(), vivo.oetf).toDouble() }
            val xyz = DoubleArray(3) { r -> (0..2).sumOf { g[r * 3 + it] * lin[it] } }
            for (r in 0..2) {
                val d = abs(di((0..2).sumOf { toDwg[r * 3 + it] * xyz[it] }) - row[3 + r])
                worst = maxOf(worst, d)
                assertTrue("${row.toList()} channel $r off by $d", d < 5e-4)
            }
        }
        println("vivo Log largest deviation from Resolve (DaVinci Intermediate code values): $worst")
    }

    private fun inv3(m: List<Double>): DoubleArray {
        val (a, b, c) = Triple(m[0], m[1], m[2]); val (d, e, f) = Triple(m[3], m[4], m[5]); val (g, h, i) = Triple(m[6], m[7], m[8])
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return doubleArrayOf(e * i - f * h, c * h - b * i, b * f - c * e, f * g - d * i, a * i - c * g, c * d - a * f, d * h - e * g, b * g - a * h, a * e - b * d)
            .map { it / det }.toDoubleArray()
    }

    @Test fun nodeBakesLutAfterItsOwnAndSaves() {
        val n = GradeNode(1, "Ut", drt = Drt(look = 2))
        assertTrue(!n.isNeutral)
        val p = n.params()
        assertNotNull(p.lut)
        assertEquals(1f, p.lutMix, 0f)
        // same settings → the same LUT object (the GPU uploads it once)
        assertSame(p.lut, n.params().lut)
        // S-Log3 mid gray → a neutral gray on the display
        val g = floatArrayOf(420f / 1023f, 420f / 1023f, 420f / 1023f)
        GradeNode.apply(p, g)
        assertTrue(g.toList().toString(), abs(g[0] - g[1]) < 0.01f && abs(g[2] - g[1]) < 0.01f && g[1] in 0.3f..0.5f)
        val back = GradeNode.fromJson(JSONObject(n.toJson().toString()))
        assertEquals(n.drt, back.drt)
        assertNull(GradeNode.fromJson(JSONObject(GradeNode(2, "B").toJson().toString())).drt)
        // with the node's own LUT: the LUT first, then the transform
        val withLut = n.copy(lut = se.gendut.klipp.engine.LutStore.BLEACH, lutMix = 0.5f).params()
        assertTrue(withLut.lut !== p.lut)
    }

    @Test fun oldProjectsWithLogInputStillLoad() {
        val g = ClipGrade.fromJson(JSONObject("""{"input":"Sony S-Log3","nodes":[{"id":1,"label":"A"}]}"""))
        assertEquals(1, g.chain.size)
        assertTrue(g.isNeutral)
    }
}
