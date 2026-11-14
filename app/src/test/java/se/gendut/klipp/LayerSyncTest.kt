package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun thePrerollTargetGrowsBeforeTheSegment() {
        val seg = se.gendut.klipp.engine.Seg(50.0, 1.0, se.gendut.klipp.engine.SegSrc.Media(1, 60.0, 1.0), null)
        // 250 ms of pre-roll: the item begins at 49.75 s on the timeline
        assertEquals(0L, LayerSync.targetMs(49.75, seg, 250))
        assertEquals(11L, LayerSync.targetMs(49.761, seg, 250))
        assertEquals(250L, LayerSync.targetMs(50.0, seg, 250))
        // inside the segment: the shared mapping (Seg.sourceAt)
        assertEquals(Math.round((seg.sourceAt(50.4)!! - 60.0) * 1000) + 250, LayerSync.targetMs(50.4, seg, 250))
        // at the pre-roll's start a player waiting on its item's first frame is in step: no seek
        assertNull(LayerSync.decide(0, 0, 0, LayerSync.targetMs(49.761, seg, 250), running = true, ready = true, msSinceSeek = 5000).seekMs)
    }

    /** Shown times as a screen gives them: whole refreshes ([refreshMs]) – a lag of [lateMs] seen as the refresh it falls on. */
    private fun seen(lateMs: Double, refreshMs: Double) = Math.round(lateMs / refreshMs) * refreshMs

    /** The layer behind by [startMs]: one sample per refresh, the lag shrinking by what the factor gains; the lag after [ms]. */
    private fun follow(startMs: Double, ms: Double, refreshMs: Double = 1000.0 / 90, mainAhead: (Double) -> Double = { 0.0 }): Pair<Double, LayerSync.Rate> {
        val r = LayerSync.Rate()
        var late = startMs; var at = 0.0
        while (at < ms) {
            r.sample(seen(late + mainAhead(at), refreshMs), refreshMs)
            late -= (r.factor - 1.0) * refreshMs
            at += refreshMs
        }
        return late to r
    }

    @Test fun aLayerBehindTheMainPlayerCatchesUpAndStaysThere() {
        // measured on the OnePlus: 22–33 ms behind, all along – taken back within a second, then played at speed
        for (start in listOf(22.0, 33.0, 44.0)) {
            val (late, r) = follow(start, 1500.0)
            assertTrue("$start → $late", Math.abs(late) <= 1000.0 / 90 / 2 + 1e-9)
            assertEquals(1.0, r.factor, 0.0)
        }
        // … and ahead the other way
        assertTrue(Math.abs(follow(-30.0, 1500.0).first) <= 1000.0 / 90 / 2 + 1e-9)
    }

    @Test fun withinHalfARefreshItIsLeftAlone() {
        // shown times can't tell closer: 5 ms behind on a 90 Hz screen reads 0
        val (late, r) = follow(5.0, 1000.0)
        assertEquals(5.0, late, 1e-9)
        assertEquals(1.0, r.factor, 0.0)
    }

    @Test fun aStallOfTheMainPlayerAtACutDoesNotSteerIt() {
        // the main player's newest frame stays ~80 ms at a cut: samples late by up to that much for a few refreshes
        val r = LayerSync.Rate()
        repeat(LayerSync.Rate.WINDOW) { r.sample(0.0, 11.0) }
        repeat(LayerSync.Rate.WINDOW / 2) { r.sample(80.0, 11.0) }
        assertEquals(1.0, r.factor, 0.0)
    }

    @Test fun itNeverPlaysMoreThanTheLimitFasterOrSlower() {
        val r = LayerSync.Rate()
        repeat(LayerSync.Rate.WINDOW) { r.sample(150.0, 11.0) }
        assertEquals(1.0 + LayerSync.Rate.MAX, r.factor, 1e-12)
        repeat(LayerSync.Rate.WINDOW) { r.sample(-150.0, 11.0) }
        assertEquals(1.0 - LayerSync.Rate.MAX, r.factor, 1e-12)
        // a start known to be behind: faster from the first frame on
        r.start(19.0)
        assertTrue(r.factor > 1.0)
    }

    @Test fun aSeekToTheShownFrameShowsThatFrameFirst() {
        // 30 fps at 1.36 s on 25p (SV2's V2) and 120 fps at 25 %: the frame (timeline µs with the read-ahead, as the
        // layer's clock gives it) and the one before it, in the item (ms, 250 ms of pre-roll)
        for ((speed, srcFps) in listOf(1.0 to 30.0, 0.25 to 120.0)) {
            val seg = se.gendut.klipp.engine.Seg(1.36, 2.0, se.gendut.klipp.engine.SegSrc.Media(1, 10.0, speed), null)
            val lead = se.gendut.klipp.engine.FrameGrid.LEAD_US / 1e6 / speed
            for (j in 1..20) {
                val item = j / srcFps                       // the frame's item time past the segment's start (s, source)
                val frameUs = Math.round((1.36 + item / speed + lead) * 1e6)
                val ms = LayerSync.frameMs(frameUs, seg, 250)
                val at = item * 1000 + 250; val before = (j - 1) / srcFps * 1000 + 250
                assertTrue("$speed $j: $ms < $at", ms < at)
                assertTrue("$speed $j: $ms > $before", ms > before)
            }
        }
    }

    @Test fun playbackStartsFromTheShownFrameUnlessItWasHeldLong() {
        assertTrue(LayerSync.startsFromShown(1_381_333, 1_400_000))     // the slot's own frame
        assertTrue(LayerSync.startsFromShown(1_441_000, 1_400_000))     // a segment's first frame, pinned to its first slot
        assertTrue(LayerSync.startsFromShown(1_330_000, 1_400_000))     // held through a slot (25 fps on 60p)
        assertFalse(LayerSync.startsFromShown(1_250_000, 1_400_000))    // held long (a gap in a variable frame rate)
    }
}
