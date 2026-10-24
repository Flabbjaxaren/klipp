package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A linked clip moved to another track takes its partner as many tracks the other way (V1 → V3 = A1 → A3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkedMoveTest {
    private class Rec : EditBackend {
        val log = mutableListOf<String>()
        override fun move(clip: Clip, start: Float, track: Int, insert: Boolean) { log += "move ${clip.id} $start ${TRACKS[track].name}" }
        override fun moveMany(moves: List<Triple<Clip, Float, Int>>, insert: Boolean) { log += moves.joinToString("; ") { "${it.first.id} ${it.second} ${TRACKS[it.third].name}" } }
        override fun trim(old: Clip, updated: Clip, ripple: Boolean) {}
        override fun split(id: Long, at: Float) {}
        override fun remove(id: Long, ripple: Boolean) {}
        override fun undo() {}
        override fun redo() {}
    }

    private fun state(): Pair<EditorState, Rec> {
        val v = Clip(1, "cam", ClipKind.VIDEO, 2, 0f, 10f, 0f, 10f, 0, link = 7)
        val a = Clip(2, "cam", ClipKind.AUDIO, 3, 0f, 10f, 0f, 10f, 0, link = 7)
        val st = EditorState(listOf(v, a))
        val r = Rec()
        st.backend = r
        return st to r
    }

    @Test fun videoUpTakesTheAudioDown() {
        val (st, r) = state()
        st.moveClip(st.clips[0], 3f, 0, insert = false)
        assertEquals(listOf("1 3.0 V3; 2 3.0 A3"), r.log)
    }

    @Test fun audioDownTakesTheVideoUp() {
        val (st, r) = state()
        st.moveClip(st.clips[1], 1f, 4, insert = false)
        assertEquals(listOf("2 1.0 A2; 1 1.0 V2"), r.log)
    }

    @Test fun sameTrackIsAPlainMove() {
        val (st, r) = state()
        st.moveClip(st.clips[0], 5f, 2, insert = false)
        assertEquals(listOf("move 1 5.0 V1"), r.log)
    }

    @Test fun withoutLinkedSelectionOnlyTheClipMoves() {
        val (st, r) = state()
        st.linkedSelection = false
        st.moveClip(st.clips[0], 3f, 1, insert = false)
        assertEquals(listOf("move 1 3.0 V2"), r.log)
    }
}
