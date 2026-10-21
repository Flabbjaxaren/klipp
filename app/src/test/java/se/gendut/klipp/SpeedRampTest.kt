package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.SpeedRamp

/** Speed ramps: where the clip is split and the speed of each piece. */
class SpeedRampTest {
    @Test fun slowMidGoesDownAndBack() {
        val p = SpeedRamp.plan(SpeedRamp.SLOW_MID, 10.0, 4.0, 1.0)
        assertEquals(p.cuts.size + 1, p.speeds.size)
        assertEquals(100, p.speeds.first())
        assertEquals(100, p.speeds.last())
        assertTrue(p.speeds.min() in 30..40)
        // the splits lie inside the clip, in order
        assertTrue(p.cuts.all { it > 10.0 && it < 14.0 })
        assertEquals(p.cuts.sorted(), p.cuts)
        // adjacent pieces have different speeds (equal ones were merged)
        assertTrue(p.speeds.zipWithNext().all { (a, b) -> a != b })
    }

    @Test fun followsClipSpeedAndLimits() {
        val p = SpeedRamp.plan(SpeedRamp.FAST_MID, 0.0, 2.0, 2.0)
        assertEquals(200, p.speeds.first())
        assertTrue(p.speeds.max() in 550..600)
        val into = SpeedRamp.plan(SpeedRamp.INTO_SLOW, 0.0, 1.0, 1.0)
        assertEquals(100, into.speeds.first())
        assertEquals(30, into.speeds.last())
        // a short clip still gets at least six pieces to ramp over (before merging)
        assertTrue(into.speeds.size >= 3)
    }
}
