package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.ClockSmoother
import kotlin.math.abs

/** The playback clock takes a correction of the audio position up gradually; starts, stalls and seeks pass as they are. */
class ClockSmootherTest {
    /** Calls every 10 ms: the audio position [raw] (µs) at each call's time (ms). */
    private fun run(c: ClockSmoother, fromMs: Int, toMs: Int, raw: (Int) -> Long) = (fromMs until toMs step 10).map { ms -> ms to c.next(raw(ms), ms * 1_000_000L) }

    @Test fun aStepIsTakenUpGradually() {
        val c = ClockSmoother()
        var steps = 0
        c.onStep = { steps++ }
        // the position runs with the clock, and at 10 s it is corrected by −25 ms
        val out = run(c, 0, 16_000) { ms -> ms * 1000L - if (ms >= 10_000) 25_000L else 0L }
        // rising all the way, never more than 1 % (and a rounding) off the clock's own pace
        out.zipWithNext().forEach { (a, b) -> val d = b.second - a.second; assertTrue("at ${b.first} ms: $d", d in 9_800L..10_200L) }
        // just after the step the reported time is still (nearly) the old line; three seconds later it has reached the new one
        assertEquals(10_100_000.0, out.first { it.first == 10_100 }.second.toDouble(), 2_000.0)
        assertEquals(13_500_000.0 - 25_000, out.first { it.first == 13_500 }.second.toDouble(), 500.0)
        assertEquals(1, steps)
    }

    @Test fun thePictureWaitsForTheSoundAtTheStart() {
        val c = ClockSmoother()
        // after play the position stands for 170 ms (the track starts), then runs
        val out = run(c, 0, 1000) { ms -> if (ms < 170) 0L else (ms - 170) * 1000L }
        out.filter { it.first < 170 }.forEach { assertEquals(0L, it.second) }
        out.forEach { (ms, p) -> assertTrue("at $ms ms: $p", abs(p - maxOf(0, ms - 170) * 1000L) <= 200) }
    }

    @Test fun aStallAndASeekPassAsTheyAre() {
        val c = ClockSmoother()
        // runs, stands from 2 s to 2.5 s (buffering), runs on
        val out = run(c, 0, 4000) { ms -> when { ms < 2000 -> ms * 1000L; ms < 2500 -> 2_000_000L; else -> (ms - 500) * 1000L } }
        assertEquals(2_000_000L, out.first { it.first == 2400 }.second)
        assertEquals(3_000_000.0, out.first { it.first == 3500 }.second.toDouble(), 300.0)
        // a jump of a second forwards while running: taken at once
        val d = ClockSmoother()
        val o2 = run(d, 0, 3000) { ms -> ms * 1000L + if (ms >= 1500) 1_000_000L else 0L }
        assertEquals(2_600_000L, o2.first { it.first == 1600 }.second)
    }
}
