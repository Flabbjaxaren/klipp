package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.gendut.klipp.engine.MediaProbe

/** A video's frame rate from its frames' own times, not from what the file states. */
class MediaRateTest {
    private fun times(n: Int, step: (Int) -> Long): List<Long> { var t = 0L; return List(n) { i -> t.also { t += step(i) } } }

    @Test fun aPhonesHundredFpsFileWithUnevenFrames() {
        // frames 8.3 and 11.7 ms apart in turn (the vivo's 100 fps APV, which says 120): 100 a second
        assertEquals(100.0, MediaProbe.rateOf(times(200) { if (it % 2 == 0) 11_667L else 8_333L })!!, 1e-9)
    }

    @Test fun standardRatesAndTheirNtscCousins() {
        assertEquals(29.97, MediaProbe.rateOf(times(60) { if (it % 3 == 0) 33_367L else 33_366L })!!, 1e-9)
        assertEquals(25.0, MediaProbe.rateOf(times(50) { 40_000L })!!, 1e-9)
        assertEquals(119.88, MediaProbe.rateOf(times(240) { if (it % 3 == 0) 8_342L else 8_341L })!!, 1e-9)
    }

    @Test fun decodingOrderAndOddRates() {
        // B-frames: times out of order – sorted first
        val t = times(30) { 33_333L }.toMutableList().also { java.util.Collections.swap(it, 3, 5) }
        assertEquals(30.0, MediaProbe.rateOf(t)!!, 1e-9)
        // a rate no camera uses stays as measured
        assertEquals(37.5, MediaProbe.rateOf(times(40) { 26_667L })!!, 0.01)
        assertNull(MediaProbe.rateOf(listOf(0L, 40_000L)))
    }
}
