package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import se.gendut.klipp.engine.FrameTime
import se.gendut.klipp.engine.TICKS_PER_SECOND
import se.gendut.klipp.engine.secToTicks

/** Timeline edges land on exactly the same tick as the neighbor's edge, despite Float seconds in the app. */
class FrameTimeTest {
    @Test fun floatEdgeLandsExactlyOnNeighbour() {
        for (fps in listOf(25.0, 30.0, 24.0, 50.0, 60.0, 30000.0 / 1001, 24000.0 / 1001, 60000.0 / 1001)) {
            val (n, d) = FrameTime.rate(fps)
            for (frame in listOf(1L, 1263L, 2126L, 10_000L, 54_321L)) {
                // the neighbor's edge in the engine: exact frame boundary
                val exact = frame * (TICKS_PER_SECOND * d / n)
                // the app's Float value for the same edge (as the timeline holds it)
                val f = (exact.toDouble() / TICKS_PER_SECOND).toFloat()
                assertEquals("fps $fps frame $frame", exact, FrameTime.ticks(f.toDouble(), fps))
            }
        }
        // this is what the bug looked like before: Float rounding put an edge ~1 µs off (an overlap that became a tiny piece)
        val exact = 2126L * TICKS_PER_SECOND / 25
        assertNotEquals(exact, secToTicks((exact.toDouble() / TICKS_PER_SECOND).toFloat().toDouble()))
    }

    @Test fun roundsToNearestFrame() {
        assertEquals(TICKS_PER_SECOND / 25 * 3, FrameTime.ticks(0.13, 25.0))
        assertEquals(0L, FrameTime.ticks(0.01, 25.0))
    }
}
