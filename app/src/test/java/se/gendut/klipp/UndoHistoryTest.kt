package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.GradeNode

/**
 * Undo/redo takes color changes and timeline changes in the order they were made. Previously color sat outside
 * the history: undo after a color change instead undid the latest timeline change (e.g. that the clip was added).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UndoHistoryTest {

    /** An engine like the FilmCraft bridge: the timeline as a list, step ids as in `undoIds`/`redoIds`. */
    private class FakeEngine(val st: EditorState) : EditBackend {
        private val undo = ArrayList<Pair<Long, List<Clip>>>()
        private val redo = ArrayList<Pair<Long, List<Clip>>>()
        private var next = 1L
        var serial = 0L
        var clearRedoCalls = 0
        var undoCalls = 0
        /** The snapshot arrives only when [flush] is called (like the engine thread): fast taps see the old one. */
        var deferred = false
        private var pending = false
        var cur: List<Clip> = st.clips

        private fun publish() { if (deferred) pending = true else st.applyEngine(cur, undo.map { it.first }, redo.map { it.first }, serial) }
        fun flush() { if (pending) { pending = false; st.applyEngine(cur, undo.map { it.first }, redo.map { it.first }, serial) } }

        fun edit(newClips: List<Clip>) {
            undo += next++ to cur
            redo.clear()
            serial++
            cur = newClips
            publish()
        }

        override fun move(clip: Clip, start: Float, track: Int, insert: Boolean) = edit(cur.map { if (it.id == clip.id) it.copy(start = start) else it })
        override fun trim(old: Clip, updated: Clip, ripple: Boolean) = edit(cur.map { if (it.id == old.id) updated else it })
        override fun split(id: Long, at: Float) {}
        override fun remove(id: Long, ripple: Boolean) = edit(cur.filter { it.id != id })
        override fun undo() {
            undoCalls++
            val (id, prev) = undo.removeLastOrNull() ?: return
            redo += id to cur
            cur = prev
            publish()
        }
        override fun redo() {
            val (id, nxt) = redo.removeLastOrNull() ?: return
            undo += id to cur
            cur = nxt
            publish()
        }
        override fun clearRedo() { clearRedoCalls++; redo.clear(); publish() }
    }

    private fun gradeWith(ev: Float) = ClipGrade(listOf(GradeNode(1, "Primär", film = Film(ev = ev))))

    private fun wire(st: EditorState): GradeState = GradeState().also { gs -> gs.history = { l, u, r -> st.recordSide(l, u, r) } }

    @Test fun gradeChangesAreUndoneBeforeTheTimelineAndTheClipStays() {
        val st = EditorState()
        val eng = FakeEngine(st)
        st.backend = eng
        val gs = wire(st)
        val clip = st.clips.first { it.kind == ClipKind.VIDEO }
        // the clip is added (one timeline step), then color in two steps
        eng.remove(st.clips.last().id, false)
        val count = st.clips.size
        gs.set(clip.id, gradeWith(0f))
        gs.set(clip.id, gradeWith(1f))
        assertEquals(3, st.undoCount)
        // undo: color steps back one at a time, the timeline is not touched
        assertTrue(st.undo())
        assertEquals(0f, gs.of(clip.id).nodes[0].film!!.ev)
        assertEquals(count, st.clips.size)
        assertTrue(st.undo())
        assertNull(gs.grades[clip.id])
        assertEquals("no engine undo yet", 0, eng.undoCalls)
        assertEquals(count, st.clips.size)
        // only now the timeline
        assertTrue(st.undo())
        assertEquals(1, eng.undoCalls)
        assertEquals(count + 1, st.clips.size)
        // redo in the same order: the timeline, then color in two steps
        assertTrue(st.redo())
        assertEquals(count, st.clips.size)
        assertNull(gs.grades[clip.id])
        assertTrue(st.redo())
        assertEquals(0f, gs.of(clip.id).nodes[0].film!!.ev)
        assertTrue(st.redo())
        assertEquals(1f, gs.of(clip.id).nodes[0].film!!.ev)
        assertFalse(st.redo())
    }

    @Test fun interleavedStepsUndoInOrderEvenWithQuickTaps() {
        val st = EditorState()
        val eng = FakeEngine(st)
        st.backend = eng
        val gs = wire(st)
        val a = st.clips[0]
        eng.move(a, a.start + 1f, a.track, false)   // E1
        gs.set(a.id, gradeWith(0.5f))                // G1
        eng.move(a, a.start + 2f, a.track, false)   // E2
        gs.set(a.id, gradeWith(1.5f))                // G2
        eng.deferred = true
        // four fast taps before the engine snapshot has had time to arrive
        assertTrue(st.undo()) // G2
        assertEquals(0.5f, gs.of(a.id).nodes[0].film!!.ev)
        assertTrue(st.undo()) // E2
        assertEquals(1, eng.undoCalls)
        assertTrue(st.undo()) // G1 – not the engine again
        assertNull(gs.grades[a.id])
        assertEquals(1, eng.undoCalls)
        assertTrue(st.undo()) // E1
        assertEquals(2, eng.undoCalls)
        eng.flush()
        assertEquals(a.start, st.clips.first { it.id == a.id }.start)
        assertEquals(0, st.undoCount)
        assertEquals(4, st.redoCount)
    }

    @Test fun aNewEditDropsRedoOnBothSides() {
        val st = EditorState()
        val eng = FakeEngine(st)
        st.backend = eng
        val gs = wire(st)
        val a = st.clips[0]
        eng.move(a, a.start + 1f, a.track, false)
        gs.set(a.id, gradeWith(1f))
        // color is undone, then a new timeline change: the color redo no longer applies
        st.undo()
        assertEquals(1, st.redoCount)
        eng.move(a, a.start + 3f, a.track, false)
        assertEquals(0, st.redoCount)
        assertFalse(st.redo())
        assertNull(gs.grades[a.id])
        // the timeline is undone, then a color change: the engine's redo is cleared
        st.undo()
        assertEquals(1, st.redoCount)
        gs.set(a.id, gradeWith(2f))
        assertEquals(1, eng.clearRedoCalls)
        assertEquals(0, st.redoCount)
    }

    @Test fun splitsAndLoadsAreNotUndoSteps() {
        val st = EditorState()
        st.backend = FakeEngine(st)
        val gs = wire(st)
        gs.setAll(mapOf(5L to gradeWith(1f), 6L to gradeWith(1f)), record = false)
        assertEquals(0, st.undoCount)
        // "apply to all" = one step
        gs.setAll(mapOf(5L to gradeWith(2f), 6L to gradeWith(2f)))
        assertEquals(1, st.undoCount)
        st.undo()
        assertEquals(1f, gs.of(5).nodes[0].film!!.ev)
        assertEquals(1f, gs.of(6).nodes[0].film!!.ev)
        // the same grade again = no change, no step
        gs.set(5L, gradeWith(1f))
        assertEquals(0, st.undoCount)
    }

    @Test fun worksWithoutAnEngineToo() {
        val st = EditorState()
        val gs = wire(st)
        val a = st.clips[0]
        st.moveClip(a, a.start + 1f, a.track, false)
        gs.set(a.id, gradeWith(1f))
        st.moveClip(st.clips.first { it.id == a.id }, a.start + 2f, a.track, false)
        assertTrue(st.undo())
        assertEquals(a.start + 1f, st.clips.first { it.id == a.id }.start)
        assertEquals(1f, gs.of(a.id).nodes[0].film!!.ev)
        assertTrue(st.undo())
        assertNull(gs.grades[a.id])
        assertTrue(st.undo())
        assertEquals(a.start, st.clips.first { it.id == a.id }.start)
        assertTrue(st.redo())
        assertTrue(st.redo())
        assertEquals(1f, gs.of(a.id).nodes[0].film!!.ev)
        assertTrue(st.redo())
        assertEquals(a.start + 2f, st.clips.first { it.id == a.id }.start)
    }
}
