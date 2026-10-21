package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Layer players: no time stretch, no needless seeks (that was what stuttered in 0.9). */
class LayerSyncTest {
    private fun d(cur: Long, want: Long, running: Boolean = true, ready: Boolean = true, since: Long = 5000, idx: Int = 0) =
        LayerSync.decide(idx, cur, 0, want, running, ready, since)

    @Test fun normalDriftIsLeftAlone() {
        assertNull(d(1030, 1000).seekMs)
        assertNull("150 ms is no stutter – no seek, no stretch", d(850, 1000).seekMs)
    }

    @Test fun realHiccupSeeksAheadButNotOverAndOver() {
        assertEquals(1000L + LayerSync.SEEK_LEAD_MS, d(500, 1000).seekMs)
        assertNull("just seeked: wait", d(500, 1000, since = 300).seekMs)
        assertNull("buffering: wait", d(500, 1000, ready = false).seekMs)
    }

    @Test fun stillOrStartingIsFrameExact() {
        assertEquals(1000L, d(960, 1000, running = false).seekMs)
        assertNull(d(1005, 1000, running = false).seekMs)
    }

    @Test fun wrongPieceSeeks() { assertEquals(1000L + LayerSync.SEEK_LEAD_MS, d(0, 1000, idx = 3).seekMs) }
}
