package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterBallisticsTest {
    @Test fun risesAtOnceFallsSlowlyAndHoldsThePeak() {
        val m = MeterBallistics()
        assertFalse(m.moving)
        m.update(0.5f, 0.0)
        assertEquals(-6.02, m.db, 0.01)
        assertEquals(-6.02, m.holdDb, 0.01)
        // silence for 0.5 s: the bar falls 12 dB, the held peak stays
        repeat(30) { m.update(0f, 0.5 / 30) }
        assertEquals(-18.02, m.db, 0.05)
        assertEquals(-6.02, m.holdDb, 0.01)
        // after the hold the peak line falls too; in the end everything is at rest
        repeat(300) { m.update(0f, 1.0 / 60) }
        assertTrue(m.holdDb < -6.02)
        repeat(600) { m.update(0f, 1.0 / 60) }
        assertEquals(MeterBallistics.FLOOR_DB, m.db, 0.0)
        assertFalse(m.moving)
    }

    @Test fun fullScaleLightsTheClipLamp() {
        val m = MeterBallistics()
        m.update(0.9f, 0.0)
        assertFalse(m.clipped)
        m.update(1.3f, 0.0)
        assertTrue(m.clipped)
        m.update(0.1f, 1.0)
        assertTrue("held", m.clipped)
        m.update(0.1f, 1.1)
        assertFalse(m.clipped)
    }

    @Test fun scale() {
        assertEquals(0f, MeterBallistics.frac(MeterBallistics.toDb(0f)), 0f)
        assertEquals(1f, MeterBallistics.frac(MeterBallistics.toDb(1f)), 1e-6f)
        assertEquals(0.5f, MeterBallistics.frac(-30.0), 1e-6f)
        assertEquals(1f, MeterBallistics.frac(MeterBallistics.toDb(2f)), 0f)
    }
}
