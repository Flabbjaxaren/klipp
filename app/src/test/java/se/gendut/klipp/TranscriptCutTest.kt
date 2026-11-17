package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What deleting transcript words cuts, said before (TranscriptCut.kt) – the engine's rule: targeted or sync-locked, unlocked tracks. */
class TranscriptCutTest {
    private val f = 0.04f
    // tracks by index: 0 V3, 1 V2, 2 V1, 3 A1, 4 A2, 5 A3
    private fun clip(id: Long, track: Int, start: Float, len: Float) = Clip(id, "c$id", if (track <= 2) ClipKind.VIDEO else ClipKind.AUDIO, track, start, len, 0f, len, 0)
    private val clips = listOf(clip(1, 2, 0f, 10f), clip(2, 3, 0f, 10f), clip(3, 1, 2f, 3f), clip(4, 4, 12f, 2f))

    @Test fun targetedAndSyncLockedTracksAreCutAndTheRestMovesEarlier() {
        // V1 and A1 targeted, everything sync-locked (the default)
        val c = transcriptCut(3.01f, 4.49f, f, clips, List(6) { TrackState() }, 2, 3, emptyList())
        assertEquals(3.0f, c.start, 1e-4f)
        assertEquals(4.52f, c.end, 1e-4f)
        assertEquals(listOf("V2", "V1", "A1", "A2"), c.tracks)
        // V2's clip (2–5 s), V1's and A1's: three clips touched; A2's clip after it moves earlier
        assertEquals(3, c.clips)
        assertTrue(c.laterMoves)
        assertTrue(c.untouched.isEmpty())
    }

    @Test fun tracksNeitherTargetedNorSyncLockedOrLockedStay() {
        val states = List(6) { i -> when (i) { 1 -> TrackState(syncLock = false); 4 -> TrackState(locked = true); else -> TrackState() } }
        val c = transcriptCut(3f, 4f, f, clips, states, 2, 3, emptyList())
        assertEquals(listOf("V1", "A1"), c.tracks)
        assertEquals(listOf("V2", "A2"), c.untouched)
        assertEquals(2, c.clips)
    }

    @Test fun nothingTargetedNorSyncLockedCutsNothing() {
        val c = transcriptCut(3f, 4f, f, clips, List(6) { TrackState(syncLock = false) }, -1, -1, emptyList())
        assertTrue(c.tracks.isEmpty())
        assertEquals(0, c.clips)
        assertFalse(c.laterMoves)
    }

    @Test fun captionsInTheRangeAreCountedNotPromised() {
        val caps = listOf(UiCaption(1, 2.5f, 3.5f, "a"), UiCaption(2, 5f, 6f, "b"), UiCaption(3, 3.2f, 3.4f, "hidden", hidden = true))
        assertEquals(1, transcriptCut(3f, 4f, f, clips, List(6) { TrackState() }, 2, 3, caps).captions)
    }
}
