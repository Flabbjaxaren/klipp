package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.Beats

class BeatsTest {
    private fun pulses(bpm: Double, sec: Int, offset: Double = 0.0): FloatArray {
        val step = 60.0 / bpm
        return FloatArray(sec * 100) { i ->
            val t = i / 100.0 - offset
            if (t < 0) 0.02f else { val since = t % step; 0.05f + 0.9f * kotlin.math.exp(-since * 25).toFloat() }
        }
    }

    @Test fun findsTheTempoAndTheBeats() {
        for (bpm in listOf(90.0, 120.0, 150.0)) {
            val b = Beats.detect(pulses(bpm, 20, 0.13))
            val gap = b.zipWithNext { x, y -> y - x }.sorted()[b.size / 2]
            assertEquals("$bpm", 60.0 / bpm, gap, 0.02)
            assertTrue("$bpm: first beat ${b.first()}", b.any { kotlin.math.abs(it - 0.13) < 0.05 } || b.first() > 0.13)
        }
    }

    @Test fun noBeatInSilenceOrSteadySound() {
        assertTrue(Beats.detect(FloatArray(2000)).isEmpty())
        assertTrue(Beats.detect(FloatArray(2000) { 0.4f }).isEmpty())
    }

    @Test fun noBeatInNoise() {
        val r = java.util.Random(7)
        assertTrue(Beats.detect(FloatArray(3000) { 0.05f + r.nextFloat() * 0.3f }).isEmpty())
    }

    @Test fun tooShortGivesNothing() = assertTrue(Beats.detect(pulses(120.0, 1)).isEmpty())
}
