package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade

/** Compound clips: selected, moved and graded as one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompoundClipTest {
    // two video clips (with linked audio) in group 7, one standalone
    private fun state() = EditorState(listOf(
        Clip(1, "A", ClipKind.VIDEO, 2, 0f, 3f, 0f, 20f, 0, link = 11, group = 7),
        Clip(2, "A", ClipKind.AUDIO, 3, 0f, 3f, 0f, 20f, 0, link = 11, group = 7),
        Clip(3, "B", ClipKind.VIDEO, 2, 3f, 3f, 0f, 20f, 0, link = 12, group = 7),
        Clip(4, "B", ClipKind.AUDIO, 3, 3f, 3f, 0f, 20f, 0, link = 12, group = 7),
        Clip(5, "C", ClipKind.VIDEO, 2, 6f, 3f, 0f, 20f, 0),
    )).apply { magnet = false; markers = emptyList() }

    @Test fun tappingAGroupedClipSelectsTheWholeGroup() {
        val st = state()
        st.tapSelect(3)
        assertEquals(setOf(1L, 2L, 3L, 4L), st.multi)
        assertNull(st.selected)
        // once more: off
        st.tapSelect(3)
        assertTrue(st.multi.isEmpty())
        // a standalone clip as usual
        st.tapSelect(5)
        assertEquals(5L, st.selected); assertTrue(st.multi.isEmpty())
        // a lasso that catches part of the group takes all of it
        st.lassoSelect(setOf(1L))
        assertEquals(setOf(1L, 2L, 3L, 4L), st.multi)
    }

    private class Backend : EditBackend {
        val log = mutableListOf<String>()
        var got: Pair<List<Long>, Boolean>? = null
        override fun move(clip: Clip, start: Float, track: Int, insert: Boolean) { log += "move ${clip.id}" }
        override fun trim(old: Clip, updated: Clip, ripple: Boolean) {}
        override fun split(id: Long, at: Float) {}
        override fun remove(id: Long, ripple: Boolean) {}
        override fun undo() {}
        override fun redo() {}
        override fun moveMany(moves: List<Triple<Clip, Float, Int>>, insert: Boolean) { log += "moveMany " + moves.map { it.first.id }.sorted() + " @" + moves.map { it.second } }
        override fun setGrouped(ids: List<Long>, grouped: Boolean) { got = ids to grouped }
    }

    @Test fun theGroupMovesAsOne() {
        val st = state()
        val be = Backend(); st.backend = be
        st.tapSelect(1)
        st.moveMarked(2f, insert = false)
        assertEquals("moveMany [1, 2, 3, 4] @[2.0, 2.0, 5.0, 5.0]", be.log.last())
    }

    @Test fun groupingNeedsTwoClipsAndGoesToTheBackend() {
        val st = state()
        val be = Backend(); st.backend = be
        st.setGrouped(listOf(5L), true)
        assertNull("a single clip is not grouped", be.got)
        st.setGrouped(listOf(3L, 5L), true)
        assertEquals(listOf(3L, 5L) to true, be.got)
        st.setGrouped(listOf(1L, 2L, 3L, 4L), false)
        assertEquals(false, be.got!!.second)
    }

    @Test fun gradingOneClipGradesTheGroupInOneUndoStep() {
        val st = state()
        val gs = GradeState()
        val steps = ArrayList<Pair<() -> Unit, () -> Unit>>()
        gs.history = { _, u, r -> steps += u to r }
        gs.siblings = { id -> st.clips.firstOrNull { it.id == id }?.let { c -> st.groupMembers(c).filter { it.kind == ClipKind.VIDEO }.map { it.id } }.orEmpty() }
        val g = ClipGrade().let { it.copy(nodes = it.nodes.map { n -> n.copy(sat = 0.5f) }) }
        gs.set(1, g)
        assertEquals(g, gs.grades[1]); assertEquals(g, gs.grades[3])
        assertNull("the standalone clip untouched", gs.grades[5])
        assertNull("audio clips have no grade", gs.grades[2])
        assertEquals(1, steps.size)
        steps.single().first()
        assertNull(gs.grades[1]); assertNull(gs.grades[3])
        // a consequence of a split (record = false) is not propagated
        gs.set(5, g, record = false)
        assertNull(gs.grades[1])
    }
}
