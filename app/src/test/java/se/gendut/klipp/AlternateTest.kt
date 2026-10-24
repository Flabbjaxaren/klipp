package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.Layer
import se.gendut.klipp.engine.Seg
import se.gendut.klipp.engine.SegSrc

/** Back-to-back segments in an upper layer are split between two players; spaced ones stay with one. */
class AlternateTest {
    private fun seg(a: Double, b: Double) = Seg(a, b - a, SegSrc.Media(1, a, 1.0), null)

    @Test fun dissolveHalvesGoToTwoPlayers() {
        val l = Layer("Video 1 transitions", listOf(seg(11.1, 11.6), seg(11.6, 12.1), seg(20.0, 21.0), seg(21.0, 22.0)))
        val parts = Alternate.split(l)
        assertEquals(listOf("Video 1 transitions", "Video 1 transitions B"), parts.map { it.name })
        assertEquals(listOf(11.1, 20.0), parts[0].segs.map { it.start })
        assertEquals(listOf(11.6, 21.0), parts[1].segs.map { it.start })
    }

    @Test fun spacedSegmentsKeepOnePlayer() {
        val l = Layer("Video 2", listOf(seg(0.0, 2.0), seg(3.5, 5.0)))
        assertEquals(listOf(l), Alternate.split(l))
    }
}
