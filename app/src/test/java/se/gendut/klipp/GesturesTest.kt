package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
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

/** The touch gestures: knife swipe, drag-out delete, edge trims, long-press lift, slim lanes, scrub speeds, timecodes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class GesturesTest {
    @get:Rule
    val rule = createComposeRule()
    private val dir = "build/shots"

    /** Second and height in dp below the ruler (V3 0, V2 40, V1 80, A1 132 …). pps = 16 dp/s. */
    private fun pt(sec: Float, yDp: Float): Offset {
        val d = rule.density.density
        return Offset(sec * 16f * d, (28f + yDp) * d)
    }
    private val v1 = 80f + 26f

    private fun timeline(st: EditorState, tool: Tool = Tool.SELECT) {
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { Timeline(st, tool, Modifier.fillMaxSize()) } } }
        rule.waitForIdle()
    }

    @Test fun quickSwipeDownCutsTheClip() {
        val st = EditorState()
        timeline(st)
        // the second A003 (13–22 s on V1): a quick swipe down at 18 s
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(18f, v1 - 10f)
            down(from)
            for (i in 1..4) moveTo(Offset(from.x, from.y + 12f * density * i))
            up()
        }
        rule.waitForIdle()
        val parts = st.clips.filter { it.track == 2 && it.name == "A003_intervju" && it.start >= 13f }.sortedBy { it.start }
        assertEquals(2, parts.size)
        assertEquals(18f, parts[1].start, 0.05f)
        assertEquals("only that clip is cut", 5, st.clips.count { it.track == 2 })
    }

    @Test fun slowVerticalDragStillMovesBetweenTracks() {
        val st = EditorState()
        timeline(st)
        // B-roll (8–13 s on V1): rest a moment (shorter than a long press), then drag down slowly onto A? no – up to V2
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(10.5f, v1)
            down(from)
            advanceEventTime(250)
            for (i in 1..12) moveTo(Offset(from.x, from.y - 40f * density * i / 12))
            up()
        }
        rule.waitForIdle()
        assertEquals("moved to V2, not cut", 1, st.clips.first { it.id == 2L }.track)
        assertEquals(1, st.clips.count { it.name == "B-roll_hamn" })
    }

    @Test fun draggingAClipOutOfTheTimelineDeletesIt() {
        val st = EditorState()
        timeline(st)
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(10.5f, v1)
            down(from)
            advanceEventTime(250)
            // up past the ruler (above the tracks)
            for (i in 1..14) moveTo(Offset(from.x, from.y - (v1 + 40f) * density * i / 14))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("releaseToDelete").assertExists()
        rule.onRoot().captureRoboImage("$dir/40_slapp_for_att_radera.png")
        rule.onNodeWithTag("timeline").performTouchInput { up() }
        rule.waitForIdle()
        assertNull("B-roll deleted", st.clips.firstOrNull { it.id == 2L })
        // ripple: the clip after it moved in
        assertEquals(8f, st.clips.first { it.id == 3L }.start, 0.01f)
    }

    @Test fun anyEdgeTrimsWithoutSelectingFirst() {
        val st = EditorState()
        timeline(st)
        assertNull(st.selected)
        // B-roll's right edge (13 s), grabbed just inside, dragged 2 s left
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(12.75f, v1)
            down(from)
            for (i in 1..12) moveTo(Offset(from.x - 2f * 16f * density * i / 12, from.y))
            up()
        }
        rule.waitForIdle()
        assertEquals(3f, st.clips.first { it.id == 2L }.length, 0.05f)
    }

    @Test fun tapOnAnEdgeSelectsTheClip() {
        val st = EditorState()
        timeline(st)
        rule.onNodeWithTag("timeline").performTouchInput { down(pt(8.3f, v1)); up() }
        rule.waitForIdle()
        assertEquals(2L, st.selected)
    }

    @Test fun holdingAClipLiftsItForAFreeMove() {
        val st = EditorState()
        timeline(st)
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(10.5f, v1)
            down(from)
            advanceEventTime(700) // long press: the clip lifts
            // straight down fast – would be a cut without the hold
            for (i in 1..4) moveTo(Offset(from.x, from.y + 13f * density * i))
            up()
        }
        rule.waitForIdle()
        assertEquals("not cut", 1, st.clips.count { it.name == "B-roll_hamn" })
    }

    @Test fun emptyTracksBecomeSlimLanes() {
        val clips = listOf(
            Clip(1, "A", ClipKind.VIDEO, 2, 0f, 5f, 0f, 10f, 0),
            Clip(2, "A", ClipKind.AUDIO, 3, 0f, 5f, 0f, 10f, 0),
        )
        val st = EditorState(clips)
        timeline(st)
        val v2 = rule.onNodeWithTag("trackHeader:V2").getBoundsInRoot()
        val v1h = rule.onNodeWithTag("trackHeader:V1").getBoundsInRoot()
        assertEquals("slim V2", 16f, (v2.bottom - v2.top).value, 0.6f)
        assertEquals("V1 full", 52f, (v1h.bottom - v1h.top).value, 0.6f)
        rule.onRoot().captureRoboImage("$dir/41_smala_spar.png")
    }

    @Test fun scrubTiersHalveTheSpeedFurtherDown() {
        val t = ScrubTiers(deadPx = 30f, stepPx = 50f)
        t.start(0f, 0f, 10.0)
        assertEquals(11.0, t.at(100f, 0f, 0.01), 1e-9)
        // into tier 1 (½): re-anchored, nothing jumps
        assertEquals(11.0, t.at(100f, 31f, 0.01), 1e-9)
        assertEquals(1, t.tier)
        assertTrue(t.changed)
        assertEquals(11.5, t.at(200f, 31f, 0.01), 1e-9)
        // further down: ¼, then ⅛
        t.at(200f, 81f, 0.01); assertEquals(2, t.tier)
        assertEquals(11.75, t.at(300f, 81f, 0.01), 1e-9)
        t.at(300f, 131f, 0.01); assertEquals(3, t.tier)
        // back up a little past the boundary: hysteresis keeps the tier, further up it returns
        t.at(300f, 125f, 0.01); assertEquals(3, t.tier)
        t.at(300f, 100f, 0.01); assertEquals(2, t.tier)
        assertFalse(t.changed.also { t.at(300f, 100f, 0.01) } && t.changed)
        assertEquals("¼×", ScrubTiers.label(2))
    }

    @Test fun typedTimecodes() {
        val before = projectFps
        projectFps = 25.0
        try {
            assertEquals(15.48f, parseTimecode("1512", 0f)!!, 1e-4f)
            assertEquals(1.48f, parseTimecode("1:12", 0f)!!, 1e-4f)
            assertEquals(60f, parseTimecode("1.00.00", 0f)!!, 1e-4f)
            assertEquals(3661f, parseTimecode("01:01:01:00", 0f)!!, 1e-3f)
            assertEquals(12f, parseTimecode("+200", 10f)!!, 1e-4f)
            assertEquals(9f, parseTimecode("-1:00", 10f)!!, 1e-4f)
            assertEquals(0f, parseTimecode("-100", 0.5f)!!, 1e-4f)
            assertNull(parseTimecode("", 0f))
            assertNull(parseTimecode("abc", 0f))
            assertNull(parseTimecode("1:2:3:4:5", 0f))
        } finally {
            projectFps = before
        }
    }

    @Test fun whisperWordsBecomeWordsWithMediaTimes() {
        val json = """[{"t0":0,"t1":400,"text":" Hej"},{"t0":400,"t1":450,"text":","},{"t0":500,"t1":900,"text":" där"},{"t0":900,"t1":950,"text":" "}]"""
        val w = se.gendut.klipp.engine.SpeechText.words(json, 10.0)
        assertEquals(listOf("Hej,", "där"), w.map { it.text })
        assertEquals(10.0, w[0].start, 1e-9)
        assertEquals(10.45, w[0].end, 1e-9)
        assertEquals(10.5, w[1].start, 1e-9)
    }

    @Test fun holdOneFingerOnThePictureAndTapBesideItStepsFrames() {
        val st = EditorState()
        rule.setContent { KlippTheme { KlippApp(st, AppUi()) } }
        rule.waitForIdle()
        val start = st.playhead
        rule.onNodeWithTag("monitorArea").performTouchInput {
            down(0, center)
            advanceEventTime(500)
            down(1, Offset(center.x + 120f * density, center.y))
            up(1)
            advanceEventTime(200)
            down(1, Offset(center.x + 120f * density, center.y))
            up(1)
            up(0)
        }
        rule.waitForIdle()
        assertEquals("two frames forward", start + 2 * FRAME, st.playhead, 1e-3f)
        assertEquals("no undo", 0, st.undoCount)
    }

    @Test fun draggingOnThePictureScrubs() {
        val st = EditorState()
        rule.setContent { KlippTheme { KlippApp(st, AppUi()) } }
        rule.waitForIdle()
        val start = st.playhead
        rule.onNodeWithTag("monitorArea").performTouchInput {
            val from = Offset(width * 0.2f, height * 0.3f)
            down(from)
            for (i in 1..10) moveTo(Offset(from.x + width * 0.5f * i / 10, from.y))
            up()
        }
        rule.waitForIdle()
        // the whole width = 6 s at full speed: half the width ≈ 3 s (minus the touch slop)
        assertTrue("scrubbed forward: ${st.playhead}", st.playhead - start in 2.6f..3.05f)
    }

    @Test fun keyframeZoomKeepsThePointUnderTheFingers() {
        val v = KfView()
        assertFalse(v.zoomed)
        v.zoomAround(0.5f, 4f)
        assertEquals(0.375f, v.a, 1e-5f); assertEquals(0.625f, v.b, 1e-5f)
        // zoom around a point near the start: it stays at the same place on screen, the view stays inside the clip
        v.reset(); v.zoomAround(0.1f, 10f)
        assertEquals(0.09f, v.a, 1e-5f); assertEquals(0.19f, v.b, 1e-5f)
        v.pan(-1f)
        assertEquals(0f, v.a, 1e-6f); assertEquals(0.1f, v.b, 1e-5f)
        // never more than 50×, never outside the clip
        v.zoomAround(0.05f, 1000f); assertEquals(KfView.MIN_SPAN, v.b - v.a, 1e-5f)
        v.show(0.9f); assertTrue(0.9f in v.a..v.b)
        v.reset(); assertFalse(v.zoomed)
    }
}
