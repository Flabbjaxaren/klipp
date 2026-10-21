package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.gendut.klipp.engine.TimeBase

/** The frames' time base: the right interpretation is chosen from the player's position, and a miscalibration does not persist. */
class TimeBaseTest {
    @Test fun withoutHintTimestampsAreCompositionTime() {
        val tb = TimeBase(30.0)
        assertEquals(12.34, tb.seconds(12_340_000)!!, 1e-9)
        assertEquals(3.0, tb.seconds(1_000_000_000_000L + 3_000_000)!!, 1e-9)
        assertNull(TimeBase(30.0).seconds(-5_000_000_000L))
    }

    @Test fun hintPicksTheCloseInterpretation() {
        val tb = TimeBase(30.0)
        tb.hintUs = 8_480_000
        // the frame is processed a little before display – still the same time base
        assertEquals(8.52, tb.seconds(8_520_000)!!, 1e-9)
        assertEquals(8.56, tb.seconds(1_000_000_000_000L + 8_560_000)!!, 1e-9)
    }

    @Test fun wrongCalibrationDoesNotStick() {
        val tb = TimeBase(30.0)
        // an odd timestamp right after a rebuild, with a stale position: the offset is calibrated (wrongly)
        tb.hintUs = 2_000_000
        assertEquals(2.0, tb.seconds(50_000_000_000L)!!, 1e-9)
        // then ordinary timestamps: the player's position decides – the old offset is not used
        tb.hintUs = 8_480_000
        assertEquals(8.5, tb.seconds(8_500_000)!!, 1e-9)
        tb.hintUs = 9_000_000
        assertEquals(9.02, tb.seconds(9_020_000)!!, 1e-9)
    }

    @Test fun unknownBaseIsCalibratedAgainstPlayer() {
        val tb = TimeBase(30.0)
        tb.hintUs = 4_000_000
        // an entirely different time base (e.g. offset by 60 s): calibrated against the position, then follows the frames
        assertEquals(4.0, tb.seconds(64_000_000)!!, 1e-9)
        tb.hintUs = 4_100_000
        assertEquals(4.12, tb.seconds(64_120_000)!!, 1e-9)
    }
}
