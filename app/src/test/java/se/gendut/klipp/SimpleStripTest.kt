package se.gendut.klipp

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.GradeNode

/**
 * Simple mode's clip strip (SimpleStrip.kt, docs/simple-mode.md): it shows the storyline, a trim is the magnetic
 * timeline's own (one undo step, what follows ripples, other tracks' clips follow), trimmed-off material comes back,
 * an edge simple mode can't trim safely is left alone, and switching modes changes nothing in the project.
 *
 * The prototype timeline: V1 (row 2) holds four clips (0–8, 8–13, 13–22, 22–30 s), each with 2 s trimmed off its start
 * and 4 s after its end in the file; a title, an adjustment clip, sound and music lie on the other tracks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class SimpleStripTest {
    @get:Rule val rule = createComposeRule()

    private fun simple() = EditorState().apply { switchMode(true); playhead = 0f }
    private fun EditorState.clip(id: Long) = clips.first { it.id == id }

    // ------------------------------------------------------------------ the model

    @Test fun theStripIsTheStorylineAndCountsTheRest() {
        val st = simple()
        assertEquals(listOf(1L, 2L, 3L, 4L), SimpleStripModel.storyline(st).map { it.id })
        assertEquals("title, adjustment, sound and music", 7, SimpleStripModel.others(st))
    }

    @Test fun whatATrimCanBringBackIsWhatTheFileHas() {
        val c = simple().clip(2)
        assertEquals(2f, SimpleStripModel.hiddenHead(c), 1e-4f)
        assertEquals(4f, SimpleStripModel.hiddenTail(c), 1e-4f)
        // held to the file and to the shortest clip
        assertEquals(-2f, SimpleStripModel.clamp(c, true, -5f), 1e-4f)
        assertEquals(4f, SimpleStripModel.clamp(c, false, 9f), 1e-4f)
        assertEquals(c.length - SimpleStripModel.MIN_LEN, SimpleStripModel.clamp(c, true, 99f), 1e-4f)
        // a freeze frame or a reversed clip only shortens (its hidden material isn't the file's next frames)
        assertEquals(0f, SimpleStripModel.hiddenTail(c.copy(hold = true)), 0f)
        assertEquals(0f, SimpleStripModel.hiddenHead(c.copy(reverse = true)), 0f)
    }

    @Test fun shorteningTheEndRipplesAndIsOneUndoStep() {
        val st = simple()
        val before = st.clips
        val undo0 = st.undoCount
        assertTrue(SimpleStripModel.trim(st, st.clip(2), false, -2f))
        assertEquals("one trim, one undo step", undo0 + 1, st.undoCount)
        assertEquals(3f, st.clip(2).length, 1e-4f)
        // what follows on the storyline moves up – no gap
        assertEquals(11f, st.clip(3).start, 1e-4f)
        assertEquals(20f, st.clip(4).start, 1e-4f)
        // a connected clip on another track follows the clip it hangs on (the title on clip 3)
        assertEquals(13f, st.clip(6).start, 1e-4f)
        // undo: the timeline as it was; redo: the trim again
        assertTrue(st.undo())
        assertEquals(before, st.clips)
        assertTrue(st.redo())
        assertEquals(3f, st.clip(2).length, 1e-4f)
        assertEquals(11f, st.clip(3).start, 1e-4f)
    }

    @Test fun cuttingTheStartKeepsTheClipInPlaceAndMovesItsInPoint() {
        val st = simple()
        assertTrue(SimpleStripModel.trim(st, st.clip(2), true, 1.5f))
        val c = st.clip(2)
        assertEquals(8f, c.start, 1e-4f)
        assertEquals(3.5f, c.length, 1e-4f)
        assertEquals(3.5f, c.srcIn, 1e-4f)
        assertEquals(11.5f, st.clip(3).start, 1e-4f)
    }

    @Test fun trimmedOffMaterialComesBack() {
        val st = simple()
        SimpleStripModel.trim(st, st.clip(2), true, 1.5f)
        SimpleStripModel.trim(st, st.clip(2), false, -1f)
        val trimmed = st.clip(2)
        assertEquals(3.5f, SimpleStripModel.hiddenHead(trimmed), 1e-4f)
        assertEquals(5f, SimpleStripModel.hiddenTail(trimmed), 1e-4f)
        // the start back to the file's first frame, the end to its last – each one undo step
        val undo0 = st.undoCount
        assertTrue(SimpleStripModel.restore(st, trimmed, true))
        assertTrue(SimpleStripModel.restore(st, st.clip(2), false))
        assertEquals(undo0 + 2, st.undoCount)
        val c = st.clip(2)
        assertEquals(0f, c.srcIn, 1e-4f)
        assertEquals(c.srcLen, c.length, 1e-4f)
        assertEquals(0f, SimpleStripModel.hiddenHead(c), 1e-4f)
        assertEquals(0f, SimpleStripModel.hiddenTail(c), 1e-4f)
    }

    @Test fun anEdgeWithATransitionIsLeftAlone() {
        val st = simple()
        st.transitions = listOf(UiTransition(90, 2, 12.5f, 13.5f, from = 2L, to = 3L))
        val before = st.clips
        assertEquals(SimpleStripModel.Block.TRANSITION, SimpleStripModel.block(st, st.clip(2), false))
        assertEquals(SimpleStripModel.Block.TRANSITION, SimpleStripModel.block(st, st.clip(3), true))
        assertNull("the other edge is free", SimpleStripModel.block(st, st.clip(2), true))
        assertFalse(SimpleStripModel.trim(st, st.clip(2), false, -1f))
        assertEquals(before, st.clips)
        assertEquals(before, st.clips.also { SimpleStripModel.restore(st, st.clip(3), true) })
    }

    @Test fun aLockedTrackIsLeftAlone() {
        val st = simple()
        st.setTrackFlag(st.clip(2).track, "locked", true)
        val before = st.clips
        assertEquals(SimpleStripModel.Block.LOCKED, SimpleStripModel.block(st, st.clip(2), false))
        assertFalse(SimpleStripModel.trim(st, st.clip(2), false, -1f))
        assertEquals(before, st.clips)
    }

    @Test fun switchingModesChangesNothingAndKeepsAdvancedSettings() {
        val st = EditorState()
        st.transitions = listOf(UiTransition(90, 2, 12.5f, 13.5f, from = 2L, to = 3L))
        st.clipExtras = mapOf(3L to ClipExtras(keyed = setOf("position")))
        val gs = GradeState().apply { setAll(mapOf(3L to ClipGrade(nodes = listOf(GradeNode(1, contrast = 1.3f)))), record = false) }
        val clips = st.clips; val tracks = st.trackStates; val side = st.magSide; val trans = st.transitions
        val extras = st.clipExtras; val grades = gs.grades.toMap(); val undo = st.undoCount
        repeat(3) { st.switchMode(true); st.switchMode(false) }
        assertEquals(clips, st.clips)
        assertEquals(tracks, st.trackStates)
        assertEquals(side, st.magSide)
        assertEquals(trans, st.transitions)
        assertEquals(extras, st.clipExtras)
        assertEquals(grades, gs.grades.toMap())
        assertEquals("switching is no edit", undo, st.undoCount)
        // a trim in simple mode keeps the clip's own settings (same clip, only its edges change)
        st.switchMode(true)
        // (its start edge has the transition: the end is trimmed)
        assertTrue(SimpleStripModel.trim(st, st.clip(3), false, -1f))
        assertEquals(extras, st.clipExtras)
        assertEquals(grades, gs.grades.toMap())
        assertEquals(3L, st.clip(3).id)
    }

    // ------------------------------------------------------------------ the strip on screen

    @Test fun theEditPageShowsTheStripInSimpleModeAndTheTimelineInAdvanced() {
        val st = EditorState().apply { props = FakeProps(); playhead = 3f }
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleStrip").assertDoesNotExist()
        st.switchMode(true)
        rule.waitForIdle()
        rule.onNodeWithTag("simpleStrip").assertIsDisplayed()
        rule.onNodeWithTag("simpleClip:1").assertIsDisplayed()
        rule.onNodeWithTag("simplePlayhead:time").assertIsDisplayed()
        rule.onNodeWithTag("undo").assertIsDisplayed()
        rule.onNodeWithTag("redo").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/shots/s20_enkel_remsa.png")
        // the visible way back to advanced mode
        rule.onNodeWithTag("simpleStrip:advanced").performClick()
        rule.waitForIdle()
        assertFalse(st.simple)
        rule.onNodeWithTag("simpleStrip").assertDoesNotExist()
    }

    @Test fun aDragOnTheHandleIsOneTrimAndOneUndoStep() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleClip:1").performClick()
        rule.waitForIdle()
        assertEquals(1L, st.selected)
        rule.onRoot().captureRoboImage("build/shots/s21_enkel_vald.png")
        val undo0 = st.undoCount
        val len0 = st.clip(1).length
        // a drag of many small moves: still one trim
        rule.onNodeWithTag("simpleHandle:end").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 120f, durationMillis = 400) }
        rule.waitForIdle()
        assertEquals(undo0 + 1, st.undoCount)
        assertTrue(st.clip(1).length < len0)
        assertEquals("what follows moved up", st.clip(1).end, st.clip(2).start, 1e-3f)
        // the screen reader's half second is a trim too
        rule.runOnIdle { rule.onNodeWithTag("simpleHandle:end").fetchSemanticsNode().config[SemanticsActions.CustomActions][0].action() }
        rule.waitForIdle()
        assertEquals(undo0 + 2, st.undoCount)
    }

    @Test fun aBlockedHandleExplainsAndOffersAdvancedMode() {
        val st = simple()
        st.transitions = listOf(UiTransition(90, 2, 12.5f, 13.5f, from = 2L, to = 3L))
        st.selected = 2L
        val before = st.clips
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleHandle:end").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("simpleStrip:openAdvanced").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/shots/s22_enkel_overgang.png")
        rule.onNodeWithTag("simpleStrip:openAdvanced").performClick()
        rule.waitForIdle()
        assertFalse(st.simple)
        assertEquals(before, st.clips)
    }

    @Test fun aFilmOnlyOnOtherTracksIsLeftAsItIs() {
        val st = simple()
        val v2 = st.tracks.videoRow(1)
        st.commit(st.clips.map { if (it.track == st.tracks.videoRow(0)) it.copy(track = v2) else it })
        val before = st.clips
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleStrip:advancedOnly").assertIsDisplayed()
        assertEquals(before, st.clips)
        assertNotEquals(0, SimpleStripModel.others(st))
    }
    // ------------------------------------------------------------------ moving, scrubbing, zoom, the transport

    private fun order(st: EditorState) = SimpleStripModel.storyline(st).map { it.id }

    @Test fun aClipMovesAmongTheOthersAsOneUndoStep() {
        val st = simple()
        val undo0 = st.undoCount
        assertTrue(SimpleStripModel.moveBy(st, st.clip(1), 1))
        assertEquals(listOf(2L, 1L, 3L, 4L), order(st))
        assertEquals(undo0 + 1, st.undoCount)
        // no gap: the film is as long as before
        assertEquals(30f, SimpleStripModel.storyline(st).last().end, 1e-3f)
        assertTrue(SimpleStripModel.moveBy(st, st.clip(4), -1))
        assertEquals(listOf(2L, 1L, 4L, 3L), order(st))
        assertTrue(st.undo()); assertTrue(st.undo())
        assertEquals(listOf(1L, 2L, 3L, 4L), order(st))
        // the first can't go earlier, the last can't go later
        assertFalse(SimpleStripModel.moveBy(st, st.clip(1), -1))
        assertFalse(SimpleStripModel.moveBy(st, st.clip(4), 1))
    }

    @Test fun aDroppedClipGoesInAtTheNearestEditPoint() {
        val st = simple()
        val line = SimpleStripModel.storyline(st)
        // clip 1 (0–8) dropped with its start at 15 s: without it the film is 2 (0–5), 3 (5–14), 4 (14–22) – nearest is 14,
        // shown where 4 starts now (22)
        assertEquals(22f, SimpleStripModel.dropPoint(line, st.clip(1), 15f), 1e-3f)
        assertTrue(SimpleStripModel.move(st, st.clip(1), 15f))
        assertEquals(listOf(2L, 3L, 1L, 4L), order(st))
    }

    @Test fun aClipJoinedByATransitionIsNotMoved() {
        val st = simple()
        st.transitions = listOf(UiTransition(90, 2, 12.5f, 13.5f, from = 2L, to = 3L))
        val before = st.clips
        assertEquals(SimpleStripModel.Block.MOVE_TRANSITION, SimpleStripModel.moveBlock(st, st.clip(2)))
        assertFalse(SimpleStripModel.moveBy(st, st.clip(2), 1))
        assertEquals(before, st.clips)
        assertNull(SimpleStripModel.moveBlock(st, st.clip(1)))
    }

    @Test fun holdingAndDraggingAClipMovesIt() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        val undo0 = st.undoCount
        // clip 2 (8–13 s, in view) held and dragged to the film's start
        rule.onNodeWithTag("simpleClip:2").performTouchInput {
            down(center); advanceEventTime(800); moveBy(androidx.compose.ui.geometry.Offset(-40f, 0f))
            repeat(10) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(-80f, 0f)) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleStrip:moveInfo").assertIsDisplayed()
        rule.onNodeWithTag("simpleStrip:dropPoint").assertExists()
        rule.onRoot().captureRoboImage("build/shots/s23_enkel_flytta.png")
        rule.onNodeWithTag("simpleClip:2").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(undo0 + 1, st.undoCount)
        assertEquals(listOf(2L, 1L, 3L, 4L), order(st))
    }

    @Test fun draggingAlongTheRulerMovesThePlayhead() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleStrip:ruler").performTouchInput { swipeRight(startX = left + 80f, endX = left + 600f, durationMillis = 300) }
        rule.waitForIdle()
        assertTrue("moved through the film: ${st.playhead}", st.playhead > 3f)
        assertEquals("nothing edited", 0, st.undoCount)
    }

    @Test fun twoFingersZoomTheStrip() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        val w0 = rule.onNodeWithTag("simpleClip:2").fetchSemanticsNode().size.width
        rule.onNodeWithTag("simpleStrip:zoomable").performTouchInput {
            pinch(center - androidx.compose.ui.geometry.Offset(30f, 0f), center - androidx.compose.ui.geometry.Offset(300f, 0f),
                center + androidx.compose.ui.geometry.Offset(30f, 0f), center + androidx.compose.ui.geometry.Offset(300f, 0f), durationMillis = 300)
        }
        rule.waitForIdle()
        val w1 = rule.onNodeWithTag("simpleClip:2").fetchSemanticsNode().size.width
        assertTrue("wider after the pinch: $w0 → $w1", w1 > w0 * 2)
        assertEquals("a zoom is no edit", 0, st.undoCount)
    }

    @Test fun theCentredPlayheadStaysInTheMiddleAndTheStripScrubs() {
        val st = simple().apply { centerPlayhead = true }
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simplePlayhead").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/shots/s24_enkel_centrerad.png")
        // the strip dragged left: later in the film
        rule.onNodeWithTag("simpleStrip:track").performTouchInput { swipeLeft(startX = right - 40f, endX = left + 40f, durationMillis = 400) }
        rule.waitForIdle()
        assertTrue("scrubbed: ${st.playhead}", st.playhead > 2f)
        assertEquals(0, st.undoCount)
    }

    @Test fun simpleModeHasStartPlayAndEndOnlyAndNoTranscript() {
        val st = EditorState().apply { props = FakeProps(); playhead = 3f }
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("timecode").assertExists()
        st.switchMode(true)
        rule.waitForIdle()
        rule.onNodeWithTag("simple:start").assertIsDisplayed()
        rule.onNodeWithTag("simple:play").assertIsDisplayed()
        rule.onNodeWithTag("simple:end").assertIsDisplayed()
        rule.onNodeWithTag("timecode").assertDoesNotExist()
        rule.onNodeWithTag("view:transcript").assertDoesNotExist()
        rule.onNodeWithTag("simple:end").performClick()
        rule.waitForIdle()
        assertEquals(st.projectEnd, st.playhead, 1e-4f)
        rule.onNodeWithTag("simple:start").performClick()
        rule.waitForIdle()
        assertEquals(0f, st.playhead, 0f)
        // the centred playhead is offered in simple mode too
        rule.onNodeWithTag("modes:menu").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("mode:center").assertIsDisplayed()
    }
}
