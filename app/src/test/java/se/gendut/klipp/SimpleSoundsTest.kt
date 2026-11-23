package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Simple mode's Sounds switches (SimpleSounds.kt): which tracks are ducked, which sounds fade out at the film's end,
 * and a sound's own fade not blocking its edge in the strip.
 *
 * The prototype timeline: the film's picture is 0–30 s; A1 (row 3) holds 7 and 8, A2 (row 4) 9 and 10 (22–30 s),
 * A3 (row 5) the music, 11 (1–29 s).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SimpleSoundsTest {
    private fun simple() = EditorState().apply { switchMode(true) }
    private fun EditorState.clip(id: Long) = clips.first { it.id == id }

    @Test fun onlyTracksOfAddedSoundsAreDucked() {
        val st = simple()
        assertEquals(listOf(0, 1, 2), SimpleSounds.soundTracks(st))
        // 7 is clip 1's own sound: A1 holds speech, so it isn't lowered
        st.commit(st.clips.map { if (it.id == 1L || it.id == 7L) it.copy(link = 100L) else it })
        assertEquals(listOf(1, 2), SimpleSounds.soundTracks(st))
        assertFalse(SimpleSounds.ducked(st))
        st.clipLevels = mapOf(11L to ClipLevel(0f, listOf(UiKf(5f, 0L, "Linear", listOf(-12.0)))))
        assertTrue(SimpleSounds.ducked(st))
    }

    @Test fun theSoundsPlayingAtTheEndFadeOut() {
        val st = simple()
        assertEquals(30f, SimpleSounds.filmEnd(st))
        // the ambience ends with the film; the music stops a second before it
        assertEquals(listOf(10L), SimpleSounds.atEnd(st).map { it.id })
        st.commit(st.clips.map { if (it.id == 11L) it.copy(length = 32f) else it })
        assertEquals(listOf(10L, 11L), SimpleSounds.atEnd(st).map { it.id })
        assertFalse(SimpleSounds.fadedAtEnd(st))
        st.transitions = listOf(UiTransition(1, 4, 28f, 30f, anchor = 1, from = 10L, to = null))
        assertFalse("the music doesn't fade yet", SimpleSounds.fadedAtEnd(st))
        st.transitions = st.transitions + UiTransition(2, 5, 31f, 33f, anchor = 1, from = 11L, to = null)
        assertTrue(SimpleSounds.fadedAtEnd(st))
    }

    @Test fun aSoundsOwnFadeDoesNotBlockItsEdge() {
        val st = simple()
        st.transitions = listOf(UiTransition(1, 4, 28f, 30f, anchor = 1, from = 10L, to = null), UiTransition(2, 4, 22f, 23f, anchor = -1, from = null, to = 10L))
        assertNull(SimpleStripModel.block(st, st.clip(10), false))
        assertNull(SimpleStripModel.block(st, st.clip(10), true))
        assertNull(SimpleStripModel.moveBlock(st, st.clip(10)))
        // a picture's transition still does
        st.transitions = listOf(UiTransition(3, 2, 7.5f, 8.5f, from = 1L, to = 2L))
        assertEquals(SimpleStripModel.Block.TRANSITION, SimpleStripModel.block(st, st.clip(1), false))
    }
}
