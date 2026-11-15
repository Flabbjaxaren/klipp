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
        // 25p with a 100 Hz mode: that one
        assertEquals(100f, DisplayRate.pick(phone + 100f, 25.0))
        assertEquals(null, DisplayRate.pick(phone, 0.0))
    }

    @Test fun withoutAnEvenMultipleTheMostEvenFramesWin() {
        // 25p: 60 Hz shows 33 and 50 ms in turn, 120 Hz four frames of 41.7 ms and one of 33.3, 144 Hz 41.7 and 34.7
        assertEquals(120f, DisplayRate.pick(phone, 25.0))
        assertEquals(144f, DisplayRate.pick(phone + 144f, 25.0))
        assertEquals(144f, DisplayRate.pick(phone + 144f, 50.0))
        // the errors themselves (ms): 25p at 60 Hz about 8.2, at 120 Hz 3.3, at 144 Hz 3.0
        assertEquals(8.16, DisplayRate.unevenness(60f, 25.0) * 1000, 0.05)
        assertEquals(3.33, DisplayRate.unevenness(120f, 25.0) * 1000, 0.05)
        assertEquals(2.98, DisplayRate.unevenness(144f, 25.0) * 1000, 0.05)
        // more frames a second than the display has refreshes: the fastest display
        assertEquals(90f, DisplayRate.pick(listOf(60f, 90f), 120.0))
    }
}
