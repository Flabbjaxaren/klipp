package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Transitions directly in the timeline: drag the fade handles and the edges, tap to select. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class TimelineTransitionTest {
    @get:Rule
    val rule = createComposeRule()
    private val dir = "build/shots"

    /** A point in the timeline: second and height in dp below the ruler (V1 starts 80 dp down). pps = 16 dp/s. */
    private fun pt(sec: Float, yDp: Float): Offset {
        val d = rule.density.density
        return Offset(sec * 16f * d, (28f + yDp) * d)
    }

    private fun setUp(fake: FakeProps, init: EditorState.() -> Unit = {}): EditorState {
        val st = EditorState().apply { props = fake; playhead = 30f; init() }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { Timeline(st, Tool.SELECT, Modifier.fillMaxSize()) } } }
        rule.waitForIdle()
        return st
    }

    @Test
    fun draggingTheFadeHandleOfAClipCreatesACrossfadeWithTheNeighbour() {
        val fake = FakeProps()
        val st = setUp(fake) { selected = 2L } // B-roll 8–13 s on V1, A003 ends at 8 s
        rule.onRoot().captureRoboImage("$dir/25_toningshandtag.png")
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(8.3f, 88f)
            down(from)
            for (i in 1..12) moveTo(Offset(from.x + 16f * density * 2f * i / 12, from.y))
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/26_korstoning_dras.png")
        rule.onNodeWithTag("timeline").performTouchInput { up() }
        rule.waitForIdle()
        // the handle is dragged 2.3 s into the clip = a 4.6 s cross dissolve centered on the edit point
        val last = fake.log.last()
        assertTrue(last, last.startsWith("fade in "))
        assertEquals(4.6f, last.removePrefix("fade in ").toFloat(), 0.1f)
        assertEquals("the clip did not move", 8f, st.clips.first { it.id == 2L }.start, 0.001f)
    }

    @Test
    fun fadeInWithoutNeighbourIsAnchoredAtTheStart() {
        val fake = FakeProps()
        setUp(fake) { selected = 1L } // A003 0–8 s, nothing before
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(0.3f, 88f)
            down(from)
            for (i in 1..12) moveTo(Offset(from.x + 16f * density * 2f * i / 12, from.y))
            up()
        }
        rule.waitForIdle()
        val last = fake.log.last()
        assertTrue(last, last.startsWith("fade in "))
        assertEquals(2.3f, last.removePrefix("fade in ").toFloat(), 0.15f)
    }

    @Test
    fun draggingATransitionEdgeResizesItAndTapSelectsIt() {
        val fake = FakeProps()
        val st = setUp(fake) { transitions = listOf(UiTransition(7, 2, 12f, 14f, 0, 13f)) }
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(14f, 106f)
            down(from)
            for (i in 1..10) moveTo(Offset(from.x + 16f * density * 1.5f * i / 10, from.y))
            up()
        }
        rule.waitForIdle()
        assertTrue(fake.log.last(), fake.log.last().startsWith("resize 7 "))
        assertEquals(5.0f, fake.log.last().removePrefix("resize 7 ").toFloat(), 0.05f)
        // tap the middle of the transition = select it (and not the clip below)
        rule.onNodeWithTag("timeline").performTouchInput { down(pt(13.3f, 106f)); up() }
        rule.waitForIdle()
        assertEquals(7L, st.selectedTransition)
        assertEquals(null, st.selected)
        rule.onRoot().captureRoboImage("$dir/27_overtoning_markerad.png")
        // drag the edge all the way in → removed
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(14f, 106f)
            down(from)
            moveTo(Offset(from.x + 16f * density * 1.5f, from.y))
            for (i in 1..10) moveTo(Offset(from.x + 16f * density * (1.5f - 2.45f * i / 10), from.y))
            up()
        }
        rule.waitForIdle()
        assertEquals("remove 7", fake.log.last())
    }

    @Test
    fun linkedAudioFollowsTrimWhileDragging() {
        val clips = listOf(
            Clip(1, "A003", ClipKind.VIDEO, 2, 0f, 8f, 0f, 20f, 0, item = 1, link = 7),
            Clip(2, "A003", ClipKind.AUDIO, 3, 0f, 8f, 0f, 20f, 0, item = 1, link = 7),
        )
        val st = EditorState(clips).apply { playhead = 20f; selected = 1L }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { Timeline(st, Tool.SELECT, Modifier.fillMaxSize()) } } }
        rule.waitForIdle()
        // drag the right edge (the lower part of the clip – the top corners are fade handles) from 8 s to 5 s
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(8f, 120f)
            down(from)
            for (i in 1..12) moveTo(Offset(from.x - 16f * density * 3f * i / 12, from.y))
        }
        rule.mainClock.advanceTimeBy(600)
        rule.onRoot().captureRoboImage("$dir/30_lankad_trim.png")
        rule.onNodeWithTag("timeline").performTouchInput { up() }
        rule.waitForIdle()
        // linked selection off: only the video
        st.linkedSelection = false
        assertTrue(st.partnersOf(st.clips.first { it.id == 1L }).isEmpty())
        st.linkedSelection = true
        assertEquals(listOf(2L), st.partnersOf(st.clips.first { it.id == 1L }).map { it.id })
    }

    @Test
    fun audioClipsGetFadeHandlesToo() {
        val fake = FakeProps()
        setUp(fake) { selected = 7L } // the first audio clip on A1 (0–8 s)
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = pt(0.3f, 136f) // A1 starts 132 dp down
            down(from)
            for (i in 1..12) moveTo(Offset(from.x + 16f * density * 2f * i / 12, from.y))
            up()
        }
        rule.waitForIdle()
        assertTrue(fake.log.lastOrNull() ?: "", fake.log.lastOrNull()?.startsWith("fade in ") == true)
    }

    @Test
    fun tappingTheCutBetweenTwoClipsSelectsItAndAddsAnyTransition() {
        val fake = FakeProps()
        val st = EditorState().apply { props = fake; playhead = 30f }
        rule.setContent {
            KlippTheme {
                Box(Modifier.fillMaxSize().background(K.Bg)) {
                    Timeline(st, Tool.SELECT, Modifier.fillMaxSize())
                    CutBar(st, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
                }
            }
        }
        rule.waitForIdle()
        // in the middle of the clip: the clip is selected as before
        rule.onNodeWithTag("timeline").performTouchInput { down(pt(4f, 106f)); up() }
        rule.waitForIdle()
        assertEquals(1L, st.selected); assertEquals(null, st.selectedCut)
        // right next to the edit point at 8 s (A003 | B-roll on V1): the edit point, not the clip – even when the clip is selected
        rule.onNodeWithTag("timeline").performTouchInput { down(pt(7.6f, 106f)); up() }
        rule.waitForIdle()
        assertEquals(UiCut(2, 8f, 1L, 2L), st.selectedCut)
        assertEquals(null, st.selected)
        rule.onNodeWithTag("cutbar").assertIsDisplayed()
        rule.onRoot().captureRoboImage("$dir/28_klippning_markerad.png")
        // choose length and type: the transition is added and becomes selected
        rule.onNodeWithTag("cutlen:2.0").performClick()
        rule.onNodeWithTag("cut:iris_round").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("addcut 2 iris_round 2.0", fake.log.last())
        assertEquals(42L, st.selectedTransition)
        assertEquals(null, st.selectedCut)
        // an edit point that already has a transition: the transition is selected
        st.transitions = listOf(UiTransition(7, 2, 7.5f, 8.5f, 0, 8f))
        st.clearSelection()
        rule.waitForIdle()
        rule.onNodeWithTag("timeline").performTouchInput { down(pt(8.05f, 106f)); up() }
        rule.waitForIdle()
        assertEquals(7L, st.selectedTransition); assertEquals(null, st.selectedCut)
        // tapping a clip deselects the edit point
        st.selectCut(UiCut(2, 8f, 1L, 2L))
        rule.onNodeWithTag("timeline").performTouchInput { down(pt(3f, 106f)); up() }
        rule.waitForIdle()
        assertEquals(null, st.selectedCut); assertEquals(1L, st.selected)
    }

    @Test
    fun tappingAClipNextToATransitionSelectsTheClip() {
        val fake = FakeProps()
        // transition 12–14 s (edit point 13 s) on V1; B-roll 8–13 s
        val st = setUp(fake) { transitions = listOf(UiTransition(7, 2, 12f, 14f, 0, 13f, from = 2, to = 3)) }
        for (sec in listOf(9f, 10f, 11f, 11.5f)) {
            st.clearSelection()
            rule.onNodeWithTag("timeline").performTouchInput { down(pt(sec, 106f)); up() }
            rule.waitForIdle()
            assertEquals("tap at $sec s", null, st.selectedTransition)
            assertEquals("tap at $sec s", 2L, st.selected)
        }
    }
}
