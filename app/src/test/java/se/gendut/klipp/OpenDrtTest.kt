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
