package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.CutSeek

/** Playback never starts a few frames before the end of one of the composition's pieces (the player stood still there). */
class StartEdgeTest {
    private val edges = doubleArrayOf(4.3, 4.6, 8.96)

    @Test fun aStartJustBeforeAnEdgeBeginsAWhileBeforeIt() {
        // measured on the vivo: 1–4 frames before the edge stood still
        for (s in listOf(4.16, 4.2, 4.24, 4.28)) assertEquals(4.0, CutSeek.startSec(s, edges), 1e-9)
        // further away, on the edge or after it: as asked
        assertEquals(4.0, CutSeek.startSec(4.0, edges), 1e-9)
        assertEquals(4.3, CutSeek.startSec(4.3, edges), 1e-9)
        assertEquals(4.32, CutSeek.startSec(4.32, edges), 1e-9)
        // before the second edge: back past the first one is fine, as long as that start is not itself just before an edge
        assertEquals(4.3, CutSeek.startSec(4.5, edges), 1e-9)
        assertEquals(0.0, CutSeek.startSec(0.1, doubleArrayOf(0.2)), 1e-9)
    }
}
