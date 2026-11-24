package se.gendut.klipp.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRatesTest {
    @Test fun aVariableRatePhoneClipGetsItsStandardRate() {
        assertEquals(60.0, standardFps(58.598), 0.0)
        assertEquals(30.0, standardFps(29.4), 0.0)
        assertEquals(120.0, standardFps(117.2), 0.0)
        assertEquals(240.0, standardFps(236.0), 0.0)
    }

    @Test fun ntscRatesOnlyWhenTheClipIsOnOne() {
        assertEquals(29.97, standardFps(29.97), 0.0)
        assertEquals(23.976, standardFps(23.98), 0.0)
        assertEquals(59.94, standardFps(59.94), 0.0)
        assertEquals(60.0, standardFps(59.6), 0.0)
    }

    @Test fun twentyFourAndTwentyFiveStayApart() {
        assertEquals(24.0, standardFps(24.3), 0.0)
        assertEquals(25.0, standardFps(24.8), 0.0)
    }

    @Test fun aRateFarFromAnyStandardStaysAsMeasured() {
        assertEquals(15.0, standardFps(15.0), 0.0)
        assertEquals(40.0, standardFps(40.0), 0.0)
    }
}
