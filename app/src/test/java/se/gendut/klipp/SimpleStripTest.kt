package se.gendut.klipp

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
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

    @Test fun theStripIsTheStorylineWithARowPerTrack() {
        val st = simple()
        assertEquals(listOf(1L, 2L, 3L, 4L), SimpleStripModel.storyline(st).map { it.id })
        // a row for each track with something on it, in the timeline's order: V3 (the title), V2 (the adjustment clip),
        // then A1, A2, A3 (the sound that isn't a video clip's own, and the music)
        val lanes = SimpleStripModel.lanes(st)
        assertEquals(listOf(0, 1, 3, 4, 5), lanes.map { it.track })
        assertEquals(listOf(SimpleStripModel.LaneKind.TEXT, SimpleStripModel.LaneKind.OVERLAY, SimpleStripModel.LaneKind.SOUND,
            SimpleStripModel.LaneKind.SOUND, SimpleStripModel.LaneKind.SOUND), lanes.map { it.kind })
        assertEquals(listOf(listOf(6L), listOf(5L), listOf(7L, 8L), listOf(9L, 10L), listOf(11L)), lanes.map { l -> l.clips.map { it.id } })
        assertEquals("nothing left unshown", 0, SimpleStripModel.others(st))
        // a video clip's own sound goes with it: not a row's
        st.commit(st.clips.map { if (it.id == 5L) it.copy(kind = ClipKind.VIDEO, link = 77L) else if (it.id == 9L) it.copy(link = 77L) else it })
        assertEquals(listOf(10L), SimpleStripModel.lanes(st).first { it.track == 4 }.clips.map { it.id })
        assertEquals(0, SimpleStripModel.others(st))
        // an emptied track is no row (it stays in the project)
        st.commit(st.clips.filter { it.id != 11L })
        assertFalse(SimpleStripModel.lanes(st).any { it.track == 5 })
        assertEquals(6, st.tracks.size)
    }

    @Test fun aLayerMovesAlongItsRowAsOneUndoStep() {
        val st = simple()
        val undo0 = st.undoCount
        assertTrue(SimpleStripModel.moveTo(st, st.clip(6), 17f, st.tracks.engineIndex(0)))
        assertEquals(undo0 + 1, st.undoCount)
        assertEquals(17f, st.clip(6).start, 1e-4f)
        assertEquals(0, st.clip(6).track)
        assertTrue(st.undo())
        assertEquals(15f, st.clip(6).start, 1e-4f)
    }

    @Test fun aLayerGoesIntoTheStorylineAndAClipOutOfIt() {
        val st = simple()
        // the adjustment clip made a video clip (B-roll over the film)
        st.commit(st.clips.map { if (it.id == 5L) it.copy(kind = ClipKind.VIDEO) else it })
        val c5 = st.clip(5)
        assertTrue(SimpleStripModel.fits(st, c5, null, main = true))
        assertFalse("a title can't go into the film's row", SimpleStripModel.fits(st, st.clip(6), null, main = true))
        val sound = SimpleStripModel.lanes(st).first { it.track == 3 }
        assertFalse("video doesn't go on a sound row", SimpleStripModel.fits(st, c5, sound, main = false))
        // dropped at 7.5 s: in at the nearest edit point (8 s), what follows makes room
        assertTrue(SimpleStripModel.moveTo(st, c5, 7.5f, 0))
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), SimpleStripModel.storyline(st).map { it.id })
        assertEquals(8f, st.clip(5).start, 1e-4f)
        assertEquals(13f, st.clip(2).start, 1e-4f)
        // and back out onto a row over the film: the storyline closes up again, one undo step
        val undo0 = st.undoCount
        assertTrue(SimpleStripModel.moveTo(st, st.clip(5), 8f, 1))
        assertEquals(undo0 + 1, st.undoCount)
        assertEquals(listOf(1L, 2L, 3L, 4L), SimpleStripModel.storyline(st).map { it.id })
        assertEquals(8f, st.clip(2).start, 1e-4f)
        assertTrue(SimpleStripModel.lanes(st).any { l -> l.kind == SimpleStripModel.LaneKind.OVERLAY && l.clips.any { it.id == 5L } })
    }

    @Test fun musicMovedInSimpleModeStaysWhereItIsInTheFilm() {
        val st = simple()
        // the music to 9 s (over clip 2) – then clip 1 made 2 s shorter: clip 2 moves up, the music stays
        assertTrue(SimpleStripModel.moveTo(st, st.clip(11), 9f, st.tracks.engineIndex(5)))
        assertTrue(11L in st.magSide.disconnected)
        assertTrue(SimpleStripModel.trim(st, st.clip(1), false, -2f))
        assertEquals(6f, st.clip(2).start, 1e-4f)
        assertEquals(9f, st.clip(11).start, 1e-4f)
        // a title still hangs on its clip and goes with it
        assertEquals(13f, st.clip(6).start, 1e-4f)
    }

    @Test fun aTitleCanBeMadeLongerButNotBroughtBack() {
        val st = simple()
        val t = st.clip(6)
        assertEquals(0f, SimpleStripModel.hiddenHead(t), 0f)
        assertTrue(SimpleStripModel.hiddenTail(t) > 100f)
        assertFalse("nothing to bring back", SimpleStripModel.restore(st, t, false))
        assertTrue(SimpleStripModel.trim(st, t, false, 3f))
        assertEquals(8f, st.clip(6).length, 1e-4f)
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
        assertTrue(SimpleStripModel.storyline(st).isEmpty())
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
    // ------------------------------------------------------------------ the mockup's layout

    @Test fun aLongFilmCanBeZoomedOutToTheWholeFilm() {
        val st = simple()
        // a long film: each storyline clip ten minutes
        var t = 0f
        st.commit(st.clips.map { c -> if (c.track == st.tracks.videoRow(0)) c.copy(start = t, length = 600f, srcLen = 606f).also { t += 600f } else c })
        val undo0 = st.undoCount
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        // at first readable (the strip scrolls); the overview button shows the whole film
        val w0 = rule.onNodeWithTag("simpleClip:1").fetchSemanticsNode().size.width
        rule.onNodeWithTag("simpleStrip:overview").performClick()
        rule.waitForIdle()
        val w1 = rule.onNodeWithTag("simpleClip:1").fetchSemanticsNode().size.width
        assertTrue("zoomed out: $w0 → $w1", w1 < w0 / 10)
        val strip = rule.onNodeWithTag("simpleStrip:zoomable").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("simpleClip:4").fetchSemanticsNode().boundsInRoot
        assertTrue("the last clip in view: $last in $strip", last.right <= strip.right + 1f)
        rule.onNodeWithTag("simpleStrip:overview").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/shots/s25_enkel_oversikt.png")
        assertEquals("zooming is no edit", undo0, st.undoCount)
    }

    @Test fun theRowsShowEachTracksClipsAndThereIsAnAddTile() {
        val st = simple()
        var asked: AddAction? = null
        st.onAddAction = { asked = it }
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        for (id in listOf(5L, 6L, 7L, 8L, 9L, 10L, 11L)) rule.onNodeWithTag("simpleLaneClip:$id", useUnmergedTree = true).assertExists()
        // (the title lies past the screen's edge at the first zoom: its click action, as a screen reader would)
        rule.runOnIdle { rule.onNodeWithTag("simpleLaneClip:6").fetchSemanticsNode().config[SemanticsActions.OnClick].action?.invoke() }
        rule.waitForIdle()
        assertEquals(6L, st.selected)
        // a selected layer has its handles on its row
        rule.onNodeWithTag("simpleHandle:end").assertExists()
        rule.onNodeWithTag("simpleStrip:add").assertExists()
        rule.runOnIdle { rule.onNodeWithTag("simpleStrip:add").fetchSemanticsNode().config[SemanticsActions.OnClick].action?.invoke() }
        assertEquals(AddAction.MEDIA, asked)
    }

    @Test fun theTipShowsOnceUntilClosed() {
        val st = simple()
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleTip").assertIsDisplayed()
        rule.onNodeWithTag("simpleTip:ok").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("simpleTip").assertDoesNotExist()
        assertTrue(AppPrefs.simpleTipSeen(ctx))
    }

    @Test fun theMockupsBarsAreTheAdvancedActions() {
        val st = EditorState().apply { props = FakeProps(); playhead = 3f }
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        st.switchMode(true)
        rule.waitForIdle()
        // the top: the project and Save video, no workspaces
        rule.onNodeWithTag("simpleTop").assertIsDisplayed()
        rule.onNodeWithTag("simple:save").assertIsDisplayed()
        rule.onNodeWithTag("modes").assertDoesNotExist()
        // the bottom bar with nothing selected: Add, Music, Text, Filter, Format
        for (t in listOf("add:media", "add:sound", "add:title", "add:filter", "add:format")) rule.onNodeWithTag(t).assertIsDisplayed()
        rule.onNodeWithTag("add:captions").assertDoesNotExist()
        // a clip selected: split here, speed, sound, filter, zoom, delete – delete last
        st.selected = 2L
        rule.waitForIdle()
        val tags = listOf("ctx:split", "ctx:speed", "ctx:volume", "ctx:filter", "ctx:picture", "ctx:delete")
        val xs = tags.map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals("in the mockup's order", xs.sorted(), xs)
        rule.onRoot().captureRoboImage("build/shots/s26_enkel_klipp.png")
        // Text: captions from the speech are one tap away
        st.selected = null
        rule.waitForIdle()
        rule.onNodeWithTag("add:title").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("simple:captions").assertExists()
        rule.onRoot().captureRoboImage("build/shots/s27_enkel_text.png")
    }

    // ------------------------------------------------------------------ the strip's touch

    @Test fun aTapUnderTheClipsDeselectsAndLeavesThePlayhead() {
        val st = simple().apply { playhead = 4f; selected = 2L }
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleStrip:track").performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, bottom - 20f)) }
        rule.waitForIdle()
        assertNull(st.selected)
        assertEquals(4f, st.playhead, 0f)
    }

    @Test fun aDragUnderTheClipsScrollsTheStrip() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        val x0 = rule.onNodeWithTag("simpleClip:3").fetchSemanticsNode().positionInRoot.x
        rule.onNodeWithTag("simpleStrip:track").performTouchInput { swipeLeft(startX = right - 40f, endX = left + 40f, durationMillis = 300) }
        rule.waitForIdle()
        // (the swipe started low in the strip, under the clips)
        val x1 = rule.onNodeWithTag("simpleClip:3").fetchSemanticsNode().positionInRoot.x
        assertTrue("scrolled: $x0 → $x1", x1 < x0 - 100f)
        assertEquals(0, st.undoCount)
    }

    @Test fun withTheCentredPlayheadATrimMovesItOnlyWhenTheFingerLifts() {
        val st = simple().apply { centerPlayhead = true; playhead = 10f; selected = 2L }
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleHandle:end").performTouchInput { down(center); repeat(8) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(-12f, 0f)) } }
        rule.waitForIdle()
        assertEquals("still while the finger is down", 10f, st.playhead, 0f)
        // the line says what letting go does, and the part cut off says how much
        rule.onNodeWithTag("simpleStrip:releaseHint", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("simpleClip:cut", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("simpleHandle:end").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, st.undoCount)
        assertTrue("at the edit afterwards: ${st.playhead}", abs(st.playhead - st.clip(2).end) < 0.1f)
    }

    @Test fun aClipHeldAtTheEdgeScrollsTheStrip() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        // clip 1 taken to the right edge and held there: the strip scrolls on, the clip goes far along
        rule.onNodeWithTag("simpleClip:1").performTouchInput {
            down(center); advanceEventTime(800)
            repeat(9) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(70f, 0f)) }
        }
        rule.mainClock.advanceTimeBy(2500)
        rule.waitForIdle()
        rule.onNodeWithTag("simpleClip:1").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, st.undoCount)
        assertEquals("moved past what was in view", 1L, SimpleStripModel.storyline(st).last().id)
    }

    // ------------------------------------------------------------------ the rows under the clips

    @Test fun theVideoRowsAreOverTheStorylineAndTheSoundUnderIt() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        fun top(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        // as in the timeline and the picture: V3 (the title) over V2 (the adjustment clip) over the storyline, then A1–A3
        assertTrue(top("simpleLaneClip:6") < top("simpleLaneClip:5"))
        assertTrue(top("simpleLaneClip:5") < top("simpleClip:2"))
        assertTrue(top("simpleClip:2") < top("simpleLaneClip:7"))
        assertTrue(top("simpleLaneClip:7") < top("simpleLaneClip:9"))
        assertTrue(top("simpleLaneClip:9") < top("simpleLaneClip:11"))
    }

    @Test fun aClipDraggedUpLeavesTheStorylineForARowOverIt() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        val undo0 = st.undoCount
        // clip 2 held and dragged straight up onto the row over it (V2)
        rule.onNodeWithTag("simpleClip:2").performTouchInput {
            down(center); advanceEventTime(800)
            repeat(5) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(0f, -30f)) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleLane:target").assertExists()
        rule.onNodeWithTag("simpleClip:2").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(undo0 + 1, st.undoCount)
        assertEquals(listOf(1L, 3L, 4L), SimpleStripModel.storyline(st).map { it.id })
        assertTrue(SimpleStripModel.lanes(st).any { l -> l.kind != SimpleStripModel.LaneKind.SOUND && l.clips.any { it.id == 2L } })
    }

    @Test fun aClipDraggedOntoASoundRowStaysWhereItWas() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleClip:2").performTouchInput {
            down(center); advanceEventTime(800)
            repeat(6) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(0f, 30f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals("video doesn't go on a sound track", 0, st.undoCount)
        assertEquals(listOf(1L, 2L, 3L, 4L), SimpleStripModel.storyline(st).map { it.id })
    }

    @Test fun aLayerDraggedAlongItsRowMoves() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        val s0 = st.clip(5).start
        rule.onNodeWithTag("simpleLaneClip:5").performTouchInput {
            down(center); advanceEventTime(800)
            repeat(6) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(20f, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(1, st.undoCount)
        assertTrue("later: ${st.clip(5).start}", st.clip(5).start > s0 + 0.3f)
        assertEquals(1, st.clip(5).track)
    }

    @Test fun aLockedTrackShowsItsMarkAndExplains() {
        val st = simple()
        st.trackStates = st.trackStates.mapIndexed { i, t -> if (i == 1) t.copy(locked = true) else t }
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleLane:marks:1").assertExists().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("simpleStrip:openAdvanced").assertExists()
        assertEquals(SimpleStripModel.Block.LOCKED, SimpleStripModel.moveBlock(st, st.clip(5)))
    }

    @Test fun moreRowsThanRoomScrollUpAndDown() {
        val st = simple()
        rule.setContent { KlippTheme { androidx.compose.foundation.layout.Box(Modifier.height(400.dp)) { SimpleStrip(st) } } }
        rule.waitForIdle()
        // the storyline in view from the start; the rows past the strip's room a scroll away
        rule.onNodeWithTag("simpleClip:1").assertIsDisplayed()
        fun shown(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height > 1f
        assertFalse("the last sound row is out of view at first", shown("simpleLaneClip:11"))
        // a swipe up beside the clips (on a clip a quick swipe cuts or deletes it, as in the timeline): the rows under
        // the storyline
        rule.onNodeWithTag("simpleStrip:rows").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(left + 10f, bottom - 10f), androidx.compose.ui.geometry.Offset(left + 10f, top + 10f), 300) }
        rule.waitForIdle()
        assertTrue(shown("simpleLaneClip:11"))
        // and down again: the rows over it
        rule.onNodeWithTag("simpleStrip:rows").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(left + 10f, top + 10f), androidx.compose.ui.geometry.Offset(left + 10f, bottom - 10f), 300) }
        rule.waitForIdle()
        assertTrue(shown("simpleLaneClip:5"))
        rule.onRoot().captureRoboImage("build/shots/s24_enkel_rader.png")
    }

    // ------------------------------------------------------------------ delete, layers over layers, the magnet

    // ------------------------------------------------------------------ the timeline's quick swipes (the gesture tips)

    private fun quick(tag: String, dy: Float, holdMs: Long = 16) = rule.onNodeWithTag(tag).performTouchInput {
        down(center); advanceEventTime(holdMs)
        moveBy(androidx.compose.ui.geometry.Offset(2f, dy / 2)); advanceEventTime(16)
        moveBy(androidx.compose.ui.geometry.Offset(0f, dy / 2)); advanceEventTime(16)
        up()
    }

    @Test fun aQuickSwipeDownCutsTheClipWhereTheFingerWent() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        // clip 2 (8–13 s), swiped down through its middle
        quick("simpleClip:2", 90f)
        rule.waitForIdle()
        val line = SimpleStripModel.storyline(st)
        assertEquals(5, line.size)
        val cut = line[2].start
        assertTrue("cut inside clip 2: $cut", cut > 9f && cut < 12f)
        assertEquals(8f, line[1].start, 1e-4f)
        assertEquals(13f, line[3].start, 1e-4f)
        assertEquals(1, st.undoCount)
    }

    @Test fun theCutShowsAsARedLineWhileTheFingerIsDown() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleClip:2").performTouchInput {
            down(center); advanceEventTime(16)
            moveBy(androidx.compose.ui.geometry.Offset(2f, 45f)); advanceEventTime(16)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 45f)); advanceEventTime(16)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleStrip:knife").assertExists()
        rule.onRoot().captureRoboImage("build/shots/s25_enkel_kniv.png")
        assertEquals("nothing cut yet", 4, SimpleStripModel.storyline(st).size)
        rule.onNodeWithTag("simpleClip:2").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(5, SimpleStripModel.storyline(st).size)
        rule.onNodeWithTag("simpleStrip:knife").assertDoesNotExist()
    }

    @Test fun aQuickFlickUpDeletesTheClipAndTheRestClosesUp() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        quick("simpleClip:2", -90f)
        rule.waitForIdle()
        assertEquals(listOf(1L, 3L, 4L), SimpleStripModel.storyline(st).map { it.id })
        assertEquals(8f, st.clip(3).start, 1e-4f)
        assertEquals(1, st.undoCount)
        // a layer flicked up just goes
        quick("simpleLaneClip:5", -60f)
        rule.waitForIdle()
        assertFalse(st.clips.any { it.id == 5L })
    }

    @Test fun aSlowOrSidewaysDragIsNoSwipe() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        // slow: the finger rested before it went down
        quick("simpleClip:2", 90f, holdMs = 250)
        // sideways: the strip scrolls
        rule.onNodeWithTag("simpleClip:2").performTouchInput {
            down(center); advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(-90f, 30f)); advanceEventTime(16); up()
        }
        rule.waitForIdle()
        assertEquals(listOf(1L, 2L, 3L, 4L), SimpleStripModel.storyline(st).map { it.id })
        assertEquals(0, st.undoCount)
    }

    @Test fun simpleModesTipsNameItsOwnGestures() {
        val keys = SIMPLE_TIPS.map { it.first }
        assertFalse("no transcript in simple mode", "text" in keys)
        assertTrue("knife" in keys && "out" in keys)
        assertEquals(R.string.tip_out_simple, SIMPLE_TIPS.first { it.first == "out" }.second)
    }

    @Test fun theBarsDeleteTakesTheClipOut() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        // (the bar's Delete: a storyline clip closes up, a layer just goes)
        st.requestRemove(2L, ripple = true)
        rule.waitForIdle()
        assertEquals(listOf(1L, 3L, 4L), SimpleStripModel.storyline(st).map { it.id })
        assertEquals(8f, st.clip(3).start, 1e-4f)
        assertTrue(st.removing.isEmpty())
        st.requestRemove(5L, ripple = true)
        rule.waitForIdle()
        assertFalse(st.clips.any { it.id == 5L })
        assertEquals(2, st.undoCount)
    }

    @Test fun aLayerDraggedUpGoesOverTheOthersOnANewTrack() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        // the adjustment clip (V2) held and dragged up past the title's row: a new row opens over them all
        rule.onNodeWithTag("simpleLaneClip:5").performTouchInput {
            down(center); advanceEventTime(800)
            repeat(12) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(0f, -40f)) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleLane:new").assertExists()
        rule.onNodeWithTag("simpleLaneClip:5").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, st.undoCount)
        assertEquals("a new track over V3", 4, st.tracks.videoCount)
        assertEquals(3, st.tracks.engineIndex(st.clip(5).track))
        assertEquals(4f, st.clip(5).start, 1e-3f)
    }

    @Test fun aMovedLayerSnapsToTheEdgesOfTheOtherRows() {
        val st = simple()
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        // the adjustment clip (4–9 s) dragged about 3.8 s later: its start (and end) catch clip 2's start (8 s)
        val pps = rule.onNodeWithTag("simpleClip:1").fetchSemanticsNode().size.width / 8f
        rule.onNodeWithTag("simpleLaneClip:5").performTouchInput {
            down(center); advanceEventTime(800)
            repeat(10) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(pps * 0.38f, 0f)) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleStrip:snap").assertExists()
        rule.onNodeWithTag("simpleLaneClip:5").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("snapped", 8f, st.clip(5).start, 1e-4f)
    }

    @Test fun aTrimmedLayerSnapsToTheEdgesOfTheOtherRows() {
        val st = simple().apply { selected = 5L }
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        // its end (9 s) dragged about 3.8 s later: it catches 13 s (clip 3's start)
        val pps = rule.onNodeWithTag("simpleClip:1").fetchSemanticsNode().size.width / 8f
        rule.onNodeWithTag("simpleHandle:end").performTouchInput {
            down(center)
            repeat(10) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(pps * 0.38f + if (it == 0) viewConfiguration.touchSlop else 0f, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals("snapped", 13f, st.clip(5).end, 1e-4f)
    }

    @Test fun aTrimDragSurvivesTheClipBeingUpdatedUnderIt() {
        // (as the app does while a drag goes on: a proxy made, a file's length read – the same clip, read anew)
        val st = simple().apply { centerPlayhead = true; playhead = 10f; selected = 2L }
        rule.setContent { KlippTheme { SimpleStrip(st) } }
        rule.waitForIdle()
        val pps = rule.onNodeWithTag("simpleClip:1").fetchSemanticsNode().size.width / 8f
        rule.onNodeWithTag("simpleHandle:start").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(viewConfiguration.touchSlop + 1f, 0f))
            repeat(3) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(pps * 0.2f, 0f)) }
        }
        rule.waitForIdle()
        st.commit(st.clips.map { if (it.id == 2L) it.copy(label = ClipLabel.GREEN) else it })
        rule.waitForIdle()
        rule.onNodeWithTag("simpleHandle:start").performTouchInput {
            repeat(3) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(pps * 0.2f, 0f)) }
        }
        rule.waitForIdle()
        assertEquals("the strip didn't scroll: the centred playhead stayed", 10f, st.playhead, 0f)
        rule.onNodeWithTag("simpleHandle:start").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("one trim (after the update's own step)", 2, st.undoCount)
        assertEquals("the whole drag (1.2 s, caught by the adjustment clip's end at 9 s)", 9f, st.clip(2).srcIn - 2f + 8f, 1e-3f)
    }
}
