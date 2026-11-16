package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.GradeNode
import kotlin.math.abs
import kotlin.math.max

/**
 * The log wheels against measured values (a neutral ramp through each wheel at several values, a combination and
 * other zone limits, rendered in float): log_wheels_ref.json over 0..1, log_wheels_wide_ref.json over −0.2..1.3 (values
 * below black and above white go through, each zone continuing straight).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogWheelsTest {
    private fun cases(file: String) =
        JSONObject(LogWheelsTest::class.java.classLoader!!.getResourceAsStream(file)!!.readBytes().toString(Charsets.UTF_8)).getJSONArray("cases")

    @Test fun theWheelsMatchTheMeasuredValues() = matches(cases("log_wheels_ref.json"))

    @Test fun belowBlackAndAboveWhiteMatchTheMeasuredValues() = matches(cases("log_wheels_wide_ref.json"))

    private fun matches(ref: org.json.JSONArray) {
        for (k in 0 until ref.length()) {
            val c = ref.getJSONObject(k)
            val s = c.getDouble("shadow").toFloat(); val m = c.getDouble("mid").toFloat(); val h = c.getDouble("high").toFloat()
            val lo = c.getDouble("low").toFloat(); val top = c.getDouble("top").toFloat()
            val curve = GradeNode.logCurve(s, m, h, lo, top)
            val xs = c.getJSONArray("x"); val ys = c.getJSONArray("y")
            var worst = 0.0
            for (i in 0 until xs.length()) {
                worst = max(worst, abs(GradeNode.curveSample(curve, xs.getDouble(i).toFloat()) - ys.getDouble(i)))
            }
            // the model's measured error: within 0.01 up to ±0.3, larger at the extremes (the transitions' exact shape)
            // and for a midtone zone much wider than the default (its gamma shape)
            val strong = maxOf(abs(s), abs(m), abs(h))
            val limit = when { top - lo > 0.4f -> 0.032; strong > 0.65f -> 0.032; strong > 0.35f -> 0.017; else -> 0.01 }
            println("${c.getString("name")}: max %.4f (limit %.3f)".format(worst, limit))
            assertTrue("${c.getString("name")} max $worst", worst <= limit)
        }
    }

    @Test fun tonesKeepTheirOrderAtAnySetting() {
        for (v in listOf(-1f, -0.5f, 0.5f, 1f)) for (zone in 0..2) {
            val t = GradeNode.logCurve(if (zone == 0) v else 0f, if (zone == 1) v else 0f, if (zone == 2) v else 0f, 0.333f, 0.55f)
            for (i in 1 until t.size) assertTrue("zone $zone at $v: ${t[i - 1]} > ${t[i]} at $i", t[i] >= t[i - 1] - 1e-6f)
        }
    }

    /**
     * The shaders' decoding (written without large intermediate values, for GPUs that work it out in half precision)
     * gives the stored value back for every high byte, and in half-precision steps no intermediate exceeds a half's range.
     */
    @Test fun theShadersDecodingMatchesTheStorageWithoutLargeNumbers() {
        fun eval(expr: String, r: Double, g: Double, b: Double): Double {
            val n = Regex("[0-9]+\\.[0-9]+(?:e-?[0-9]+)?").findAll(expr.substringAfter("128.0")).map { it.value.toDouble() }.toList()
            return if (n.size == 4) (r - 128) * n[0] + g * n[1] + b * n[2] + n[3]
            else { val m = Regex("[0-9]+\\.[0-9]+(?:e-?[0-9]+)?").findAll(expr).map { it.value.toDouble() }.toList(); r * m[0] + g * m[1] }
        }
        for (r in 0..255) for (g in intArrayOf(0, 1, 127, 255)) for (b in intArrayOf(0, 200, 255)) {
            val want = (r * 65536.0 + g * 256.0 + b) / 16777215.0 * 2 * GradeNode.CURVE_RANGE - GradeNode.CURVE_RANGE
            assertEquals("r $r g $g b $b", want, eval(GradeNode.CURVE_DECODE, r.toDouble(), g.toDouble(), b.toDouble()), 1e-9)
            val hsl = (r * 256.0 + g) / 65535.0
            assertEquals("hsl r $r g $g", hsl, eval(GradeNode.HSL_DECODE, r.toDouble(), g.toDouble(), 0.0), 1e-9)
            // every product and partial sum stays far inside a half's range (65504)
            assertTrue(abs((r - 128) * 0.0625) <= 8.0 && abs(r * 0.0039064) <= 1.0)
        }
    }

    @Test fun theGpuTableHoldsTheCurveAndContinuesItStraight() {
        val t = arrayOf(GradeNode.logCurve(0f, 0f, 0.6f, 0.333f, 0.55f), GradeNode.logCurve(-0.6f, 0f, 0f, 0.333f, 0.55f), FloatArray(GradeNode.CURVE_N) { GradeNode.curveX(it) })
        // what the shaders do: decode 24 bits, interpolate, continue straight beyond the ends
        fun texel(ch: Int, i: Int) = GradeNode.curve24(t, ch, i) / 16777215.0 * 2 * GradeNode.CURVE_RANGE - GradeNode.CURVE_RANGE
        fun gpu(ch: Int, x: Float): Double {
            val p = (x - GradeNode.CURVE_LO) * (GradeNode.CURVE_N - 1) / (GradeNode.CURVE_HI - GradeNode.CURVE_LO)
            val i = kotlin.math.floor(p).toInt().coerceIn(0, GradeNode.CURVE_N - 2)
            return x + texel(ch, i) + (texel(ch, i + 1) - texel(ch, i)) * (p - i)
        }
        // the rounding to 24 bits: half a step per value inside the table; beyond it the end slope's rounding grows
        // with the distance (two values one table step apart)
        val q = GradeNode.CURVE_RANGE / 16777215.0
        val step = (GradeNode.CURVE_HI - GradeNode.CURVE_LO) / (GradeNode.CURVE_N - 1).toDouble()
        for (ch in 0..2) for (x in listOf(-1f, -0.3f, 0f, 0.25f, 0.5f, 0.9f, 1f, 1.07f, 1.5f, 2.5f)) {
            val beyond = maxOf(GradeNode.CURVE_LO - x, x - GradeNode.CURVE_HI, 0f)
            assertEquals("channel $ch at $x", GradeNode.curveSample(t[ch], x).toDouble(), gpu(ch, x), q * (1 + 2 * beyond / step) + 1e-6)
        }
        // the identity stays the identity, beyond the table too
        for (x in listOf(-2f, 0f, 0.5f, 1f, 3f)) assertEquals(x.toDouble(), gpu(2, x), 2e-6)
        // highlight +0.6 continues straight above the table: the slope at its end
        val s = (GradeNode.curveSample(t[0], 1.5f) - GradeNode.curveSample(t[0], 1.4f)) / 0.1f
        assertEquals(GradeNode.curveSample(t[0], 1.5f) + s, GradeNode.curveSample(t[0], 2.5f), 1e-4f)
    }

    @Test fun aNodeKeepsSuperWhitesAndSubBlacksForItsCurves() {
        fun node(json: String) = GradeNode.fromJson(JSONObject(json)).params()
        // saturation alone: nothing is cut
        val sat = node("{\"id\":1,\"label\":\"N\",\"sat\":1.2}")
        val px = floatArrayOf(1.06f, 1.06f, 1.06f)
        GradeNode.apply(sat, px)
        assertEquals(1.06f, px[1], 1e-5f)
        val dark = floatArrayOf(-0.05f, -0.05f, -0.05f)
        GradeNode.apply(sat, dark)
        assertEquals(-0.05f, dark[1], 1e-5f)
        // log highlight master −0.6 = an amount of −0.3 (amount = master·0.5): 1.1 → 0.934, 1.2 → 1.004 measured
        val hi = node("{\"id\":1,\"label\":\"N\",\"logH\":[0,0,-0.6]}")
        for ((x, want) in listOf(1.1f to 0.934f, 1.2f to 1.004f)) {
            val p = floatArrayOf(x, x, x)
            GradeNode.apply(hi, p)
            assertEquals("log highlight at $x", want, p[1], 0.003f)
        }
    }

    @Test fun eachWheelLeavesTheOtherZonesAlone() {
        assertEquals(0.9f, GradeNode.logTone(0.9f, 0.2f, 0f, 0f, 0.333f, 0.55f), 1e-4f)
        assertEquals(0.1f, GradeNode.logTone(0.1f, 0f, 0f, 0.3f, 0.333f, 0.55f), 1e-4f)
        assertEquals(0.05f, GradeNode.logTone(0.05f, 0f, 0.3f, 0f, 0.333f, 0.55f), 1e-4f)
        assertEquals(0.95f, GradeNode.logTone(0.95f, 0f, 0.3f, 0f, 0.333f, 0.55f), 1e-4f)
    }
}
