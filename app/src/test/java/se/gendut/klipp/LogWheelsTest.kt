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
 * The log wheels against reference renders (a neutral ramp through each wheel at several values, a combination and
 * other zone limits, rendered in float): log_wheels_ref.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogWheelsTest {
    private val ref by lazy {
        JSONObject(LogWheelsTest::class.java.classLoader!!.getResourceAsStream("log_wheels_ref.json")!!.readBytes().toString(Charsets.UTF_8)).getJSONArray("cases")
    }

    @Test fun theWheelsMatchTheReferenceRenders() {
        for (k in 0 until ref.length()) {
            val c = ref.getJSONObject(k)
            val s = c.getDouble("shadow").toFloat(); val m = c.getDouble("mid").toFloat(); val h = c.getDouble("high").toFloat()
            val lo = c.getDouble("low").toFloat(); val top = c.getDouble("top").toFloat()
            val curve = GradeNode.logCurve(s, m, h, lo, top)
            val xs = c.getJSONArray("x"); val ys = c.getJSONArray("y")
            var worst = 0.0
            for (i in 0 until xs.length()) {
                val want = ys.getDouble(i)
                // the table holds code values 0..1
                if (want < 0.0 || want > 1.0) continue
                worst = max(worst, abs(GradeNode.sample(curve, xs.getDouble(i).toFloat()) - want))
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

    @Test fun eachWheelLeavesTheOtherZonesAlone() {
        assertEquals(0.9f, GradeNode.logTone(0.9f, 0.2f, 0f, 0f, 0.333f, 0.55f), 1e-4f)
        assertEquals(0.1f, GradeNode.logTone(0.1f, 0f, 0f, 0.3f, 0.333f, 0.55f), 1e-4f)
        assertEquals(0.05f, GradeNode.logTone(0.05f, 0f, 0.3f, 0f, 0.333f, 0.55f), 1e-4f)
        assertEquals(0.95f, GradeNode.logTone(0.95f, 0f, 0.3f, 0f, 0.333f, 0.55f), 1e-4f)
    }
}
