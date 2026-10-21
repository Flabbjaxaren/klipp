package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.DisplayRate

/** The display's refresh rate follows the video's: an even multiple, the highest one, otherwise no change. */
class DisplayRateTest {
    private val phone = listOf(60f, 90f, 120f)

    @Test fun picksTheHighestEvenMultiple() {
        assertEquals(120f, DisplayRate.pick(phone, 24.0))
        assertEquals(120f, DisplayRate.pick(phone, 30.0))
        assertEquals(120f, DisplayRate.pick(phone, 60.0))
        assertEquals(120f, DisplayRate.pick(phone, 23.976))
        assertEquals(90f, DisplayRate.pick(listOf(60f, 90f), 30.0))
        // 25p: no even multiple among 60/90/120 – no change; with a 100 Hz mode: that one
        assertEquals(null, DisplayRate.pick(phone, 25.0))
        assertEquals(100f, DisplayRate.pick(phone + 100f, 25.0))
        assertEquals(null, DisplayRate.pick(phone, 0.0))
    }
}
