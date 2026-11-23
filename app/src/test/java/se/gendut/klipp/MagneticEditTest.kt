package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The magnetic timeline in the editor: edits on the local timeline (one undo step each), the setting off leaving track
 * editing as it was, what goes to the engine, the side state's undo and saving, and how a plan becomes engine edits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MagneticEditTest {
    // rows: V3 V2 V1 A1 A2 A3 → V1 = 2, A1 = 3, V2 = 1, A2 = 4
    private fun clip(id: Long, kind: ClipKind, track: Int, start: Float, len: Float, link: Long? = null) =
        Clip(id, "c$id", kind, track, start, len, 0f, 100f, 0, item = 1, link = link)

    /** A (0–10), B (10–15), C (15–25) on V1 with sound on A1; a title on V2 over B (12–14). */
    private fun state(magnetic: Boolean = true) = EditorState(listOf(
        clip(1, ClipKind.VIDEO, 2, 0f, 10f, 1), clip(101, ClipKind.AUDIO, 3, 0f, 10f, 1),
        clip(2, ClipKind.VIDEO, 2, 10f, 5f, 2), clip(102, ClipKind.AUDIO, 3, 10f, 5f, 2),
        clip(3, ClipKind.VIDEO, 2, 15f, 10f, 3), clip(103, ClipKind.AUDIO, 3, 15f, 10f, 3),
        clip(10, ClipKind.TITLE, 1, 12f, 2f),
    )).also { it.magnetic = magnetic }

    private fun EditorState.c(id: Long) = clips.first { it.id == id }
    private fun EditorState.has(id: Long) = clips.any { it.id == id }

    // ------------------------------------------------------------------ local edits, one undo step each

    @Test fun rippleDeleteTakesTheTitleAndIsOneUndoStep() {
        val st = state()
        st.requestRemove(2, ripple = true)
        st.finishRemove(2)
        assertFalse(st.has(2)); assertFalse(st.has(102)); assertFalse(st.has(10))
        assertEquals(10f, st.c(3).start, 1e-4f); assertEquals(10f, st.c(103).start, 1e-4f)
        assertEquals(1, st.undoCount)
        st.undo()
        assertTrue(st.has(2) && st.has(10))
        assertEquals(15f, st.c(3).start, 1e-4f)
    }

    @Test fun deleteWithoutRippleLeavesAGapClip() {
        val st = state()
        st.requestRemove(2, ripple = false)
        st.finishRemove(2)
        assertFalse(st.has(2))
        assertTrue(st.has(10))
        assertEquals(15f, st.c(3).start, 1e-4f)
        assertEquals(listOf(MGap(10f, 15f)), st.mag().gaps())
    }

    @Test fun groupDeleteRipples() {
        val st = state()
        st.lassoSelect(setOf(1L, 2L))
        st.removeMarked(ripple = true)
        for (id in listOf(1L, 101L, 2L, 102L)) st.finishRemove(id)
        assertEquals(0f, st.c(3).start, 1e-4f)
        assertFalse(st.has(10))
        assertEquals(1, st.undoCount)
    }

    @Test fun magneticMoveIsOneUndoStep() {
        val st = state()
        assertTrue(st.magMove(st.c(3), 0f, 2, position = false))
        assertEquals(0f, st.c(3).start, 1e-4f); assertEquals(10f, st.c(1).start, 1e-4f); assertEquals(20f, st.c(2).start, 1e-4f)
        assertEquals(22f, st.c(10).start, 1e-4f)
        assertEquals(1, st.undoCount)
        st.undo()
        assertEquals(15f, st.c(3).start, 1e-4f); assertEquals(12f, st.c(10).start, 1e-4f)
    }

    @Test fun keepConnectedWhileDragging() {
        val st = state()
        st.keepConnected = true
        st.magMove(st.c(2), 100f, 2, position = false)
        assertEquals(12f, st.c(10).start, 1e-4f)
        assertEquals(20f, st.c(2).start, 1e-4f)
    }

    @Test fun positionMoveLeavesAGapAndPieces() {
        val st = state()
        st.magMove(st.c(2), 2f, 2, position = true)
        // A cut in two around B; the piece is a new clip with the source in point moved on
        val pieces = st.clips.filter { it.track == 2 && it.id !in setOf(1L, 2L, 3L) }
        assertEquals(1, pieces.size)
        assertEquals(7f, pieces[0].start, 1e-4f); assertEquals(7f, pieces[0].srcIn, 1e-4f)
        assertEquals(1, st.undoCount)
        assertEquals(listOf(MGap(10f, 15f)), st.mag().gaps())
    }

    @Test fun movingOffTheStorylineNeedingANewTrackAddsIt() {
        // V2 and V3 taken where B lands: V4 (a new track; every row moves down one)
        val st = EditorState(listOf(
            clip(1, ClipKind.VIDEO, 2, 0f, 10f), clip(2, ClipKind.VIDEO, 2, 10f, 5f),
            clip(20, ClipKind.VIDEO, 1, 0f, 30f), clip(21, ClipKind.VIDEO, 0, 0f, 30f),
        )).also { it.magnetic = true }
        assertTrue(st.magMove(st.c(2), 3f, 1, position = false))
        assertEquals(4, st.tracks.videoCount)
        assertEquals(st.tracks.videoRow(3), st.c(2).track)
        assertEquals(st.tracks.videoRow(0), st.c(1).track)
    }

    @Test fun rippleTrimMovesLaterClipsAndTheTitleKeepsItsFrame() {
        val st = state()
        assertTrue(st.magTrim(2, inEdge = true, delta = 1f))
        assertEquals(10f, st.c(2).start, 1e-4f); assertEquals(4f, st.c(2).length, 1e-4f); assertEquals(1f, st.c(2).srcIn, 1e-4f)
        assertEquals(14f, st.c(3).start, 1e-4f)
        // the title keeps to its frame of B: B's picture moved back 1 s
        assertEquals(11f, st.c(10).start, 1e-4f)
        assertEquals(1, st.undoCount)
    }

    @Test fun gapClipTrimAndDelete() {
        val st = EditorState(listOf(clip(1, ClipKind.VIDEO, 2, 0f, 10f), clip(2, ClipKind.VIDEO, 2, 14f, 5f))).also { it.magnetic = true }
        assertTrue(st.magTrimGap(10f, -1f))
        assertEquals(13f, st.c(2).start, 1e-4f)
        assertTrue(st.magDeleteGap(10f))
        assertEquals(10f, st.c(2).start, 1e-4f)
        assertEquals(2, st.undoCount)
    }

    @Test fun liftAndOverwriteToPrimary() {
        val st = state()
        assertTrue(st.liftFromStoryline(2))
        assertEquals(15f, st.c(3).start, 1e-4f)
        assertTrue(st.c(2).track < 2)
        assertEquals(listOf(MGap(10f, 15f)), st.mag().gaps())
        // and back down
        assertTrue(st.overwriteToPrimary(2))
        assertEquals(2, st.c(2).track)
        assertTrue(st.mag().gaps().isEmpty())
    }

    @Test fun aLockedTrackStopsTheEdit() {
        val st = state()
        st.setTrackFlag(1, "locked", true)
        // the title on locked V2 would have to move: nothing happens
        assertFalse(st.magMove(st.c(2), 100f, 2, position = false))
        assertEquals(10f, st.c(2).start, 1e-4f)
        assertEquals(0, st.undoCount)
    }

    // ------------------------------------------------------------------ the side state

    @Test fun disconnectIsAnUndoStepAndKeepsTheClip() {
        val st = state()
        st.setDisconnected(listOf(10L), true)
        assertEquals(setOf(10L), st.magSide.disconnected)
        assertEquals(1, st.undoCount)
        st.requestRemove(2, ripple = true); st.finishRemove(2)
        assertTrue(st.has(10))
        assertEquals(12f, st.c(10).start, 1e-4f)
        // undo the delete, then the disconnect
        st.undo(); st.undo()
        assertTrue(st.magSide.disconnected.isEmpty())
        st.redo()
        assertEquals(setOf(10L), st.magSide.disconnected)
        st.setDisconnected(listOf(10L), false)
        assertTrue(st.magSide.disconnected.isEmpty())
    }

    @Test fun connectionPointAtThePlayhead() {
        val st = state()
        st.setConnectionPoint(10, 13.5f)
        assertEquals(1.5f, st.magSide.points[10L]!!, 1e-4f)
        // outside the clip: back to its first frame
        st.setConnectionPoint(10, 40f)
        assertNull(st.magSide.points[10L])
        assertEquals(2, st.undoCount)
    }

    @Test fun sideStateSurvivesSaveAndOpen() {
        val s = MagSide(setOf(10L, 11L), mapOf(10L to 1.25f))
        val back = MagSide.fromJson(org.json.JSONObject(s.toJson().toString()))
        assertEquals(s, back)
        assertEquals(MagSide(), MagSide.fromJson(null))
        assertEquals(MagSide(), MagSide.fromJson(org.json.JSONObject()))
    }

    // ------------------------------------------------------------------ off: tracks as before

    @Test fun offTheSettingDeleteLeavesTheTitleAndRipplesOnlyTheTrack() {
        val st = state(magnetic = false)
        st.requestRemove(2, ripple = true)
        st.finishRemove(2)
        assertTrue(st.has(10))
        assertEquals(12f, st.c(10).start, 1e-4f)
        // (the local timeline ripples the clip's own track)
        assertEquals(10f, st.c(3).start, 1e-4f)
        assertEquals(15f, st.c(103).start, 1e-4f)
        assertTrue(st.has(102))
    }

    @Test fun offTheSettingTheBackendGetsTrackEdits() {
        val log = ArrayList<String>()
        val st = state(magnetic = false)
        st.backend = object : EditBackend {
            override fun move(clip: Clip, start: Float, track: Int, insert: Boolean) { log += "move ${clip.id}" }
            override fun moveMany(moves: List<Triple<Clip, Float, Int>>, insert: Boolean) { log += "moveMany" }
            override fun trim(old: Clip, updated: Clip, ripple: Boolean) { log += "trim ${old.id} $ripple" }
            override fun split(id: Long, at: Float) {}
            override fun remove(id: Long, ripple: Boolean) { log += "remove $id $ripple" }
            override fun removeMany(ids: List<Long>, ripple: Boolean) { log += "removeMany $ripple" }
            override fun undo() {}
            override fun redo() {}
            override fun magnetic(label: String, plan: (MagTimeline) -> MPlan?) { log += "magnetic" }
        }
        st.requestRemove(2, ripple = true); st.finishRemove(2)
        st.moveClip(st.c(3), 30f, 2, insert = false)
        st.trimCommit(st.c(1).copy(length = 8f), -2f)
        assertEquals(listOf("remove 2 true", "move 3", "trim 1 true"), log)
        // on: the same gestures go as magnetic edits
        st.magnetic = true
        log.clear()
        st.requestRemove(1, ripple = true); st.finishRemove(1)
        assertEquals(listOf("magnetic"), log)
    }

    @Test fun theEngineGetsAPlanMadeFromItsOwnTimeline() {
        var got: MPlan? = null
        val st = state()
        st.backend = object : EditBackend {
            override fun move(clip: Clip, start: Float, track: Int, insert: Boolean) {}
            override fun trim(old: Clip, updated: Clip, ripple: Boolean) {}
            override fun split(id: Long, at: Float) {}
            override fun remove(id: Long, ripple: Boolean) {}
            override fun undo() {}
            override fun redo() {}
            override fun magnetic(label: String, plan: (MagTimeline) -> MPlan?) {
                got = plan(magTimelineOf(st.clips, st.tracks, st.transitions, st.magSide))
            }
        }
        assertTrue(st.magMove(st.c(3), 0f, 2, position = false))
        assertEquals(0f, got!!.clip(3)!!.start, 1e-4f)
        // the local timeline is left for the engine's reply
        assertEquals(15f, st.c(3).start, 1e-4f)
    }

    // ------------------------------------------------------------------ the plan as engine edits

    @Test fun stepsShrinkMoveThenExtend() {
        val now = mapOf(
            1L to MClip(1, true, 0, 0f, 10f),
            2L to MClip(2, true, 0, 10f, 5f),
            3L to MClip(3, true, 1, 20f, 4f),
        )
        val plan = MPlan(listOf(
            // 1: 2 s off its head, stays at 0 → trim the in edge, then move back to 0
            MClip(1, true, 0, 0f, 8f, head = 2f),
            // 2: 1 s longer at its head, to 7 → move to 8, then extend the in edge
            MClip(2, true, 0, 7f, 6f, head = -1f),
            // 3: to V3, its tail 1 s longer
            MClip(3, true, 2, 20f, 5f),
        ), emptySet(), videoLanes = 3, audioLanes = 3)
        val s = magSteps(plan, now)
        assertEquals(listOf(MagTrim(1, true, 2f)), s.shrink)
        assertEquals(listOf(MagMove(1, true, 0, 0f), MagMove(2, true, 0, 8f), MagMove(3, true, 2, 20f)), s.moves)
        assertEquals(listOf(MagTrim(2, true, -1f), MagTrim(3, false, 1f)), s.extend)
    }

    @Test fun stepsForPiecesUseTheEnginesIds() {
        // the piece −1 is clip 77 in the engine, at 7–10 after the cut; it stays where it is
        val plan = MPlan(listOf(MClip(-1, true, 0, 7f, 3f)), emptySet(), listOf(MPiece(-1, 1, 7f)), 3, 3)
        val s = magSteps(plan, mapOf(77L to MClip(77, true, 0, 7f, 3f)), mapOf(-1L to 77L))
        assertTrue(s.shrink.isEmpty() && s.moves.isEmpty() && s.extend.isEmpty())
        val t = magSteps(plan, mapOf(77L to MClip(77, true, 0, 7f, 5f)), mapOf(-1L to 77L))
        assertEquals(listOf(MagTrim(77, false, -2f)), t.shrink)
    }

    @Test fun aPlanAppliedLocallyKeepsTheSourceInSync() {
        val st = state()
        val plan = st.mag().trim(1, inEdge = true, delta = 2f)
        assertNotNull(plan)
        val out = applyMagPlan(st.clips, plan!!, st.tracks) { 999L }
        val a = out.first { it.id == 1L }
        assertEquals(2f, a.srcIn, 1e-4f); assertEquals(8f, a.length, 1e-4f); assertEquals(0f, a.start, 1e-4f)
    }
}
