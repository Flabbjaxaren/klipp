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

    @Test fun aStretchCutOffInDecodingOrder() {
        // B-frames: a frame is decoded before the one shown ahead of it (0, 80, 40, 160, 120, … ms at 25 fps) – a stretch
        // read until 2 s ended on a frame ahead of one not yet read (x264's file measured 24)
        val decoding = listOf(0L) + (0 until 60).flatMap { k -> listOf(2L * k + 2, 2L * k + 1) }.map { it * 40_000L }
        val cut = ArrayList<Long>()
        for (t in decoding) { cut += t; if (t - cut.first() > 2_000_000L) break }
        org.junit.Assert.assertNotEquals("cut where the reading stopped: wrong", 25.0, MediaProbe.rateOf(cut)!!, 1e-6)
        val more = ArrayList<Long>(); var extra = -1
        for (t in decoding) { more += t; if (extra < 0 && t - more.first() > 2_000_000L) extra = 0; if (extra >= 0 && ++extra > 16) break }
        assertEquals(25.0, MediaProbe.rateInDecodingOrder(more)!!, 1e-9)
        assertEquals("in order: as before", 25.0, MediaProbe.rateInDecodingOrder(times(80) { 40_000L })!!, 1e-9)
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
