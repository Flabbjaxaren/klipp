package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.CutSeek

/** Playback never starts a few frames before the end of one of the composition's pieces (the player stood still there). */
class StartEdgeTest {
    private val edges = doubleArrayOf(4.3, 5.0, 8.96)

    @Test fun aStartJustBeforeAnEdgeBeginsAWhileBeforeIt() {
        // measured: 1–4 frames before the edge stood still (vivo, light files), up to 0.22 s (vivo, 4K APV) and about
        // 0.5 s (OnePlus)
        for (s in listOf(3.8, 3.9, 4.08, 4.1, 4.16, 4.2, 4.24, 4.28)) assertEquals(3.7, CutSeek.startSec(s, edges), 1e-9)
        // further away, on the edge or after it: as asked
        assertEquals(3.7, CutSeek.startSec(3.7, edges), 1e-9)
        assertEquals(4.3, CutSeek.startSec(4.3, edges), 1e-9)
        assertEquals(4.32, CutSeek.startSec(4.32, edges), 1e-9)
        // before the second edge: back past the first one is fine, as long as that start is not itself just before an edge
        assertEquals(4.4, CutSeek.startSec(4.6, edges), 1e-9)
        assertEquals(0.0, CutSeek.startSec(0.1, doubleArrayOf(0.2)), 1e-9)
    }

    @Test fun shortPiecesInARowStepBackUntilNoEdgeIsJustAhead() {
        // a transition's pre-rendered pieces, 0.28 s each
        val t = doubleArrayOf(2.64, 2.92, 3.2, 4.48)
        val p = CutSeek.startSec(3.0, t)
        assertEquals(true, t.none { it - p > 1e-6 && it - p <= CutSeek.STALL_WINDOW })
        assertEquals(2.04, p, 1e-9)
    }
}
