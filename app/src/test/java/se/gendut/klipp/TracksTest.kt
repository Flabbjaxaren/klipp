package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The timeline's rows follow the sequence's tracks: 3 + 3 for a new project (exactly the rows before tracks could be
 * added), up to 6 video and 10 audio tracks, empty ones deleted; what is kept per row follows its track when rows shift.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class TracksTest {
    @get:Rule val rule = createComposeRule()

    private fun show(st: EditorState) {
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { Timeline(st, Tool.SELECT, Modifier.fillMaxSize()) } } }
        rule.waitForIdle()
    }

    @Test fun theHeadersAddTracksAndADoubleTapOpensAVideoTrack() {
        val st = EditorState(listOf(clip(1, 2, 1f))).apply { playhead = 30f }
        show(st)
        // no captions: no TXT lane
        rule.onAllNodesWithTag("capToggle").assertCountEquals(0)
        rule.onNodeWithTag("addTrack:video").performClick()
        rule.onNodeWithTag("addTrack:audio").performClick()
        rule.waitForIdle()
        assertEquals(listOf("V4", "V3", "V2", "V1", "A1", "A2", "A3", "A4"), st.tracks.map { it.name })
        rule.onNodeWithTag("trackHeader:V4").assertExists()
        rule.onNodeWithTag("trackHeader:A4").assertExists()
        // V1 (with its clip) opened by a double tap on its header: as tall as an opened audio track
        val before = rule.onNodeWithTag("trackHeader:V1").getBoundsInRoot()
        rule.onNodeWithTag("trackHeader:V1").performTouchInput { down(center); up(); advanceEventTime(100); down(center); up() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        assertTrue(st.isOpen(st.tracks.v1Row))
        val after = rule.onNodeWithTag("trackHeader:V1").getBoundsInRoot()
        assertEquals(OPEN_TRACK_DP, (after.bottom - after.top).value, 1f)
        assertTrue(after.bottom - after.top > before.bottom - before.top)
        // captions arrive: the lane is there
        st.hasCaptionLane = true
        st.captions = listOf(UiCaption(1, 0f, 1f, "hej", 7))
        rule.waitForIdle()
        rule.onNodeWithTag("capToggle").assertExists()
    }

    private fun clip(id: Long, track: Int, start: Float = 0f) = Clip(id, "c$id", if (track < 3) ClipKind.VIDEO else ClipKind.AUDIO, track, start, 2f, 0f, 10f, 0)

    @Test fun aNewProjectHasTheRowsItAlwaysHad() {
        assertEquals(listOf("V3", "V2", "V1", "A1", "A2", "A3"), DEFAULT_TRACKS.map { it.name })
        assertEquals(listOf(40, 40, 52, 44, 44, 44), DEFAULT_TRACKS.map { it.heightDp })
        assertEquals(2, DEFAULT_TRACKS.v1Row)
        assertEquals(3, DEFAULT_TRACKS.a1Row)
        // a new state: the target tracks are V1 and A1, the track states one per row
        val st = EditorState(emptyList())
        assertEquals(DEFAULT_TRACKS, st.tracks)
        assertEquals(2, st.targetVideo)
        assertEquals(3, st.targetAudio)
        assertEquals(6, st.trackStates.size)
    }

    @Test fun rowsFromTheSequencesTracks() {
        // engine order (V1 first) with ids: the rows run V4 V3 V2 V1 A1 … A5
        val rows = trackRows(4, 5, listOf(11, 12, 13, 14), listOf(21, 22, 23, 24, 25))
        assertEquals(listOf("V4", "V3", "V2", "V1", "A1", "A2", "A3", "A4", "A5"), rows.map { it.name })
        assertEquals(listOf(14L, 13, 12, 11, 21, 22, 23, 24, 25), rows.map { it.id })
        assertEquals(3, rows.v1Row)
        assertEquals(4, rows.a1Row)
        assertEquals(0, rows.videoRow(3))
        assertEquals(3, rows.videoRow(0))
        assertEquals(8, rows.audioRow(4))
        for (r in rows.indices) {
            val k = rows.engineIndex(r)
            assertEquals(r, if (rows[r].kind == TrackKind.VIDEO) rows.videoRow(k) else rows.audioRow(k))
        }
        assertEquals(0..3, rows.videoRows)
        assertEquals(4..8, rows.audioRows)
    }

    @Test fun addingStopsAtTheLimits() {
        val st = EditorState(listOf(clip(1, 2), clip(2, 3)))
        repeat(10) { st.addTrack(TrackKind.VIDEO) }
        assertEquals(MAX_VIDEO_TRACKS, st.tracks.videoCount)
        assertFalse(st.tracks.canAddVideo)
        repeat(20) { st.addTrack(TrackKind.AUDIO) }
        assertEquals(MAX_AUDIO_TRACKS, st.tracks.audioCount)
        assertFalse(st.tracks.canAddAudio)
        assertEquals(listOf("V6", "V5", "V4", "V3", "V2", "V1", "A1"), st.tracks.take(7).map { it.name })
        // a video track goes on top: the clips stay on their tracks (V1, A1), on rows further down
        assertEquals(st.tracks.v1Row, st.clips.first { it.id == 1L }.track)
        assertEquals(st.tracks.a1Row, st.clips.first { it.id == 2L }.track)
        // the target tracks followed
        assertEquals(st.tracks.v1Row, st.targetVideo)
        assertEquals(st.tracks.a1Row, st.targetAudio)
    }

    @Test fun onlyAnEmptyTrackCanBeDeletedAndNeverTheLastOfItsKind() {
        val st = EditorState(listOf(clip(1, 2), clip(2, 3), clip(3, 0)))
        // V3 has a clip, V2 is empty, A2 empty
        assertFalse(st.tracks.canDelete(0, st.clips))
        assertTrue(st.tracks.canDelete(1, st.clips))
        assertTrue(st.tracks.canDelete(4, st.clips))
        // a transition counts as something on the track
        assertFalse(st.tracks.canDelete(1, st.clips, listOf(UiTransition(9, 1, 0f, 1f, -1, 0f))))
        st.deleteTrack(1)
        assertEquals(listOf("V2", "V1", "A1", "A2", "A3"), st.tracks.map { it.name })
        // the clip on the old V3 is on the row above V1 now; V1's clip on V1
        assertEquals(0, st.clips.first { it.id == 3L }.track)
        assertEquals(st.tracks.v1Row, st.clips.first { it.id == 1L }.track)
        assertEquals(st.tracks.a1Row, st.clips.first { it.id == 2L }.track)
        // a track with a clip stays
        st.deleteTrack(0)
        assertEquals(5, st.tracks.size)
        // the last of a kind stays
        val one = trackRows(1, 1)
        assertFalse(one.canDelete(0, emptyList()))
        assertFalse(one.canDelete(1, emptyList()))
    }

    @Test fun whatIsKeptPerRowFollowsItsTrack() {
        // an old project's rows (3 + 3, with the engine's ids); its track states, targets and an opened track
        val old = trackRows(3, 3, listOf(11, 12, 13), listOf(21, 22, 23))
        val st = EditorState(emptyList())
        st.setTrackRows(old)
        st.trackStates = List(6) { i -> TrackState(locked = i == 1, muted = i == 4) } // V2 locked, A2 muted
        st.targetVideo = 1 // V2
        st.targetAudio = 4 // A2
        st.toggleOpen(4) // A2 opened for its volume lines
        st.toggleOpen(0) // V3 opened (taller)
        assertTrue(st.volOn(4) && !st.volOn(3) && !st.volOn(0) && st.isOpen(0))
        // a track added on top (V4) and one deleted (A1): every row of a kept track follows it
        val next = trackRows(4, 2, listOf(11, 12, 13, 14), listOf(22, 23))
        st.setTrackRows(next)
        assertEquals(listOf("V4", "V3", "V2", "V1", "A1", "A2"), st.tracks.map { it.name })
        assertTrue("V2 still locked", st.trackStates[2].locked)
        assertTrue("the old A2 (now A1) still muted", st.trackStates[4].muted)
        assertFalse(st.trackStates[1].locked)
        assertEquals(2, st.targetVideo)
        assertEquals(4, st.targetAudio)
        assertTrue(st.volOn(4) && st.isOpen(1) && !st.isOpen(0))
        // a target track that is gone: back to V1 / A1
        st.setTrackRows(trackRows(4, 1, listOf(11, 12, 13, 14), listOf(23)))
        assertEquals(st.tracks.a1Row, st.targetAudio)
        assertNull(remapRow(4, next, st.tracks))
    }

    @Test fun theCaptionLaneShowsOnlyWithCaptions() {
        val st = EditorState(emptyList())
        assertFalse(st.showCaptionLane)
        st.hasCaptionLane = true
        assertFalse("a caption track without captions: no lane", st.showCaptionLane)
        st.captions = listOf(UiCaption(1, 0f, 1f, "hej", 7, hidden = true))
        assertTrue("hidden captions keep the lane (to show them again)", st.showCaptionLane)
        st.captions = emptyList()
        assertFalse(st.showCaptionLane)
    }
}
