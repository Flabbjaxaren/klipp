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
     * The HLG export's encoding against the original: opendrt_hlg_ref.txt is OpenDRT.dctl v1.1.0 compiled as C++ the
     * same way, with "Rec.2100 - HLG / Rec.2020 (P3 Limited)" and Display Peak Luminance 1000 (24 settings × 2 mid
     * grays × 12 colors, the inputs of opendrt_ref.txt).
     */
    @Test fun hlgMatchesTheOriginalDctl() {
        val lines = javaClass.classLoader!!.getResourceAsStream("opendrt_hlg_ref.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(576, lines.size)
        var worst = 0f
        val transforms = HashMap<Drt, OpenDrt.Transform>()
        for (l in lines) {
            val v = l.trim().split(Regex("\\s+"))
            val key = inputs.getValue(v[0].toInt() to v[1].toInt())
            val cwp = v[4].toInt()
            val drt = Drt(key, v[2].toInt(), v[3].toInt(), cwp, if (cwp != 0) 0.4f else 0.25f, v[5].toFloat())
            val t = transforms.getOrPut(drt) { OpenDrt.Transform(drt, hdr = true) }
            val px = floatArrayOf(v[7].toFloat(), v[8].toFloat(), v[9].toFloat())
            t.apply(px)
            for (k in 0..2) {
                val d = abs(px[k] - v[10 + k].toFloat())
                if (d > worst) worst = d
                assertTrue("$l → ${px.toList()}", d < 2e-3f)
            }
        }
        println("OpenDRT HLG largest deviation from the original: $worst")
    }

    @Test fun hlgOnlyWhenOpenDrtEndsTheGrade() {
        val drt = GradeNode(1, "Ut", drt = Drt())
        assertTrue(ClipGrade(listOf(drt)).hlgReady)
        // a colour node after OpenDRT, a film on it or a window: no
        assertTrue(!ClipGrade(listOf(drt, GradeNode(2, "B", sat = 1.2f))).hlgReady)
        assertTrue(!ClipGrade(listOf(GradeNode(2, "B", sat = 1.2f))).hlgReady)
        // the HLG LUT differs from the SDR one and is kept apart
        assertTrue(drt.params(hlg = true).lut !== drt.params().lut)
        assertSame(drt.params(hlg = true).lut, drt.params(hlg = true).lut)
    }

    /**
     * vivo Log has no published spec: the decode was measured.
     */
    @Test fun vivoLogMatchesTheMeasurement() {
        // vivo Log code values, then the expected linear XYZ (D65) after the decode and the earlier measured gamut
        val ref = listOf(
            doubleArrayOf(0.097752, 0.097752, 0.097752, 0.000812380, 0.000854727, 0.000930847),
            doubleArrayOf(0.127077, 0.127077, 0.127077, 0.005637697, 0.005931571, 0.006459824),
            doubleArrayOf(0.136852, 0.136852, 0.136852, 0.007266202, 0.007644965, 0.008325808),
            doubleArrayOf(0.195503, 0.195503, 0.195503, 0.020744175, 0.021825499, 0.023769229),
            doubleArrayOf(0.293255, 0.293255, 0.293255, 0.065819917, 0.069250889, 0.075418217),
            doubleArrayOf(0.391007, 0.391007, 0.391007, 0.170963670, 0.179875432, 0.195894733),
            doubleArrayOf(0.500489, 0.500489, 0.500489, 0.462013574, 0.486096789, 0.529387475),
            doubleArrayOf(0.586510, 0.586510, 0.586510, 0.987878241, 1.039373013, 1.131937236),
            doubleArrayOf(0.684262, 0.684262, 0.684262, 2.321124369, 2.442116781, 2.659606209),
            doubleArrayOf(0.782014, 0.782014, 0.782014, 5.429821415, 5.712859756, 6.221634196),
            doubleArrayOf(0.879765, 0.879765, 0.879765, 12.680147052, 13.341120499, 14.529250683),
            doubleArrayOf(1.000000, 1.000000, 1.000000, 35.956482136, 37.830772699, 41.199896225),
            doubleArrayOf(0.416145, 0.289692, 0.407802, 0.188030913, 0.101040108, 0.246995832),
            doubleArrayOf(0.235017, 0.716324, 0.936695, 3.696042328, 1.172192437, 26.199402735),
            doubleArrayOf(0.833423, 0.531836, 0.460416, 5.935289162, 2.909121720, 0.315671698),
            doubleArrayOf(0.289422, 0.344338, 0.285775, 0.070586788, 0.105474471, 0.063237313),
            doubleArrayOf(0.913132, 0.139296, 0.539034, 11.649608057, 4.797912214, 0.799370541),
            doubleArrayOf(0.421525, 0.612608, 0.743997, 0.954826170, 0.837775532, 4.833330340),
            doubleArrayOf(0.932745, 0.934417, 0.898238, 19.330929966, 21.713148801, 16.316567455),
            doubleArrayOf(0.103058, 0.352591, 0.863404, 1.696819764, -0.639999014, 14.078971499),
            doubleArrayOf(0.856274, 0.632563, 0.685380, 7.656104395, 4.028578643, 2.784871447),
            doubleArrayOf(0.555184, 0.739508, 0.843301, 2.541498799, 2.722928676, 11.338289888),
            doubleArrayOf(0.976657, 0.450161, 0.761033, 20.767743715, 8.326710915, 5.700708955),
            doubleArrayOf(0.289862, 0.732584, 0.858128, 2.230198552, 2.258909336, 12.993485038),
        )
        val vivo = OpenDrt.Input.of("vivo")
        assertEquals(OpenDrt.Input.VIVO, vivo)
        // the log curve against the measured values, with the earlier measured gamut (Klipp's own gamut follows vivo's LUT instead)
        val measuredGamut = listOf(0.6481493972540809, 0.1575487681456304, 0.14475776165196025, 0.27135463226395545, 0.7925205414874028, -0.06387517375135825, -0.0018353373842675902, -0.126500213210501, 1.217393301354647)
        val g = measuredGamut
        // Klipp's vivo colors keep white white
        val kg = vivo.gamut.map { it.toDouble() }
        for (r in 0..2) assertEquals(listOf(0.3127 / 0.329, 1.0, (1 - 0.3127 - 0.329) / 0.329)[r], kg[r * 3] + kg[r * 3 + 1] + kg[r * 3 + 2], 1e-4)
        // white stays white (D65 from x 0.3127, y 0.3290)
        for (r in 0..2) assertEquals(listOf(0.3127 / 0.329, 1.0, (1 - 0.3127 - 0.329) / 0.329)[r], g[r * 3] + g[r * 3 + 1] + g[r * 3 + 2], 1e-4)
        // compared in stops (with a small offset at black): 1/146 stop is what a 16-bit measurement resolves here
        fun stops(x: Double) = kotlin.math.log2(maxOf(x, -0.0074) + 0.0075)
        var worst = 0.0
        for (row in ref) {
            val lin = DoubleArray(3) { OpenDrt.oetf(row[it].toFloat(), vivo.oetf).toDouble() }
            val xyz = DoubleArray(3) { r -> (0..2).sumOf { g[r * 3 + it] * lin[it] } }
            for (r in 0..2) {
                val d = abs(stops(xyz[r]) - stops(row[3 + r]))
                worst = maxOf(worst, d)
                assertTrue("${row.toList()} channel $r off by $d stops", d < 5e-4 / 0.07329248)
            }
        }
        println("vivo Log largest deviation from the expected values: $worst stops")
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
