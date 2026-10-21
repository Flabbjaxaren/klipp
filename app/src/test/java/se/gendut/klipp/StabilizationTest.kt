package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.Stabilization
import se.gendut.klipp.engine.WindowTrack
import kotlin.math.abs
import kotlin.math.sin

/** Stabilization: the shake is removed, the slow motion is kept and the zoom covers the edges. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StabilizationTest {
    private val n = 150
    // slow pan + shake (±1 % of the frame, a period of a few frames) + a little rotational shake
    private fun slow(i: Int) = i * 0.001f
    private val path = WindowTrack(0.0, 0.04,
        FloatArray(n) { slow(it) + 0.01f * sin(it * 1.7f) }, FloatArray(n) { 0.008f * sin(it * 2.3f) },
        FloatArray(n) { 1f }, FloatArray(n) { 1f }, smooth = 0f, r = FloatArray(n) { 0.004f * sin(it * 1.3f) })

    @Test fun removesShakeKeepsPan() {
        val st = Stabilization(path, 0.8f)
        val w = 1920f; val h = 1080f
        var jitter = 0f; var bias = 0f; var before = 0f
        var prev: FloatArray? = null
        for (i in 0 until n) {
            val c = st.correction(i * 0.04, w, h)
            // the content's center where it is now → after the correction
            val p = c.map(path.dx[i] * w, path.dy[i] * h)
            // shake = jerk between two frames beyond the pan; the slow path may drift a little
            prev?.let { q ->
                jitter = maxOf(jitter, abs((p[0] - q[0]) - 0.001f * w), abs(p[1] - q[1]))
                before = maxOf(before, abs((path.dx[i] - path.dx[i - 1]) * w - 0.001f * w))
            }
            prev = p
            bias = maxOf(bias, abs(p[0] - slow(i) * w), abs(p[1]))
        }
        assertTrue("before: $before px jerk", before > 20f)
        assertTrue("jerk left: $jitter px", jitter < 1f)
        assertTrue("slow drift: $bias px", bias < 20f)
        val z = st.zoom(w / h)
        assertTrue("zoom $z", z > 1.005f && z < 1.1f)
    }

    /** The corners of the zoomed, corrected frame lie inside the source frame (no edges show). */
    private fun edgesHidden(st: Stabilization, p: WindowTrack, w: Float, h: Float): Boolean {
        for (i in 0 until p.size) {
            val inv = st.transform(i * p.dt, w, h).inverse()
            for ((x, y) in listOf(-w / 2 to -h / 2, w / 2 to -h / 2, -w / 2 to h / 2, w / 2 to h / 2)) {
                val q = inv.map(x, y)
                if (abs(q[0]) > w / 2 + 1f || abs(q[1]) > h / 2 + 1f) return false
            }
        }
        return true
    }

    @Test fun violentShakeStaysInsideCropBudget() {
        // heavy shake (±6 % and ±3°) – the smooth path would require more than 10 % zoom
        val m = 200
        val p = WindowTrack(0.0, 1.0 / 30, FloatArray(m) { 0.06f * sin(it * 0.9f) + 0.0015f * it }, FloatArray(m) { 0.05f * sin(it * 1.4f + 1f) },
            FloatArray(m) { 1f + 0.01f * sin(it * 0.5f) }, FloatArray(m) { 1f }, smooth = 0f, r = FloatArray(m) { 0.05f * sin(it * 0.7f) })
        val w = 1920f; val h = 1080f
        val st = Stabilization(p, 1f, 0, 1.1f)
        assertEquals(1.1f, st.zoom(w / h), 1e-4f)
        assertTrue("edges show", edgesHidden(st, p, w, h))
        // and the shake has still dropped sharply (jerk between frames)
        var before = 0f; var after = 0f
        var prev: FloatArray? = null
        for (i in 0 until m) {
            val q = st.correction(i / 30.0, w, h).map(p.dx[i] * w, p.dy[i] * h)
            prev?.let { o ->
                after += abs(q[0] - o[0]) + abs(q[1] - o[1])
                before += abs((p.dx[i] - p.dx[i - 1]) * w) + abs((p.dy[i] - p.dy[i - 1]) * h)
            }
            prev = q
        }
        assertTrue("jerk before $before after $after", after < before * 0.4f)
    }

    @Test fun tripodHoldsStill() {
        val st = Stabilization(path, 0.5f, 1, 1.3f)
        val w = 1920f; val h = 1080f
        assertTrue(edgesHidden(st, path, w, h))
        // the content's center stays still in frame (the 15 % pan fits within 30 % zoom)
        var spread = 0f
        val first = st.correction(0.0, w, h).map(path.dx[0] * w, path.dy[0] * h)
        for (i in 0 until n) {
            val q = st.correction(i * 0.04, w, h).map(path.dx[i] * w, path.dy[i] * h)
            spread = maxOf(spread, abs(q[0] - first[0]), abs(q[1] - first[1]))
        }
        assertTrue("tripod moves $spread px", spread < 1f)
        val back = Stabilization.fromJson(JSONObject(st.toJson().toString()))!!
        assertEquals(1, back.mode)
        assertEquals(1.3f, back.maxZoom, 1e-6f)
    }

    @Test fun longClipSolveTime() {
        val m = 30 * 90
        val p = WindowTrack(0.0, 1.0 / 30, FloatArray(m) { 0.05f * sin(it * 0.9f) + 0.0004f * it }, FloatArray(m) { 0.04f * sin(it * 1.4f + 1f) },
            FloatArray(m) { 1f }, FloatArray(m) { 1f }, smooth = 0f, r = FloatArray(m) { 0.03f * sin(it * 0.7f) })
        val t0 = System.nanoTime()
        Stabilization(p, 0.6f, 0, 1.2f).zoom(16f / 9f)
        println("solve 90 s: ${(System.nanoTime() - t0) / 1e6} ms")
    }

    @Test fun savedWithGrade() {
        val g = ClipGrade(stab = Stabilization(path, 0.4f))
        assertTrue(!g.isNeutral)
        assertTrue(g.needsClock)
        val back = ClipGrade.fromJson(JSONObject(g.toJson().toString()))
        assertEquals(0.4f, back.stab!!.strength, 1e-6f)
        assertEquals(n, back.stab!!.path.size)
        // the effect exists only with a clock (the frame's time)
        assertTrue(g.effects().isEmpty())
        assertEquals(1, g.effects(1L) { it / 1e6 }.size)
    }
}
