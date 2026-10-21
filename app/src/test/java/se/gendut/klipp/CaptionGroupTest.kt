package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The selection box over captions: select, move as a group (with clips), delete. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class CaptionGroupTest {
    @get:Rule val rule = createComposeRule()

    private fun state() = EditorState(listOf(Clip(3, "B", ClipKind.VIDEO, 2, 5f, 5f, 0f, 20f, 0))).apply {
        playhead = 30f
        magnet = false
        markers = emptyList()
        hasCaptionLane = true
        captions = listOf(
            UiCaption(101, 2f, 4f, "ett", 1),
            UiCaption(102, 6f, 8f, "två", 1),
            UiCaption(103, 12f, 14f, "tre", 1),
        )
    }

    @Test fun groupRangeAndMoveFollowTheEngineRules() {
        val st = state()
        st.lassoSelectAll(emptySet(), setOf(101L, 102L), add = false)
        assertEquals(setOf(101L, 102L), st.multiCaptions)
        // left: not before 0 (2 s); right: up to "tre" (12 − 8 = 4 s)
        val r = st.captionGroupRange()
        assertEquals(-2f, r.start, 1e-4f)
        assertEquals(4f, r.endInclusive, 1e-4f)
        // a move further than possible: stops at the neighbor
        st.moveGroup(10f, insert = false)
        assertEquals(6f, st.captions.first { it.id == 101L }.start, 1e-4f)
        assertEquals(10f, st.captions.first { it.id == 102L }.start, 1e-4f)
        assertEquals("the neighbor untouched", 12f, st.captions.first { it.id == 103L }.start, 1e-4f)
    }

    @Test fun singleCaptionInTheBoxIsAPlainSelection() {
        val st = state()
        st.lassoSelectAll(emptySet(), setOf(103L), add = false)
        assertEquals(103L, st.selectedCaption)
        assertTrue(st.multiCaptions.isEmpty())
    }

    @Test fun captionsAndAClipMoveTogether() {
        val st = state()
        st.lassoSelectAll(setOf(3L), setOf(101L), add = false)
        assertEquals(setOf(3L), st.multi)
        assertEquals(setOf(101L), st.multiCaptions)
        st.moveGroup(1f, insert = false)
        assertEquals(6f, st.clips.first { it.id == 3L }.start, 1e-4f)
        assertEquals(3f, st.captions.first { it.id == 101L }.start, 1e-4f)
        st.removeGroup(false)
        assertTrue(st.captions.none { it.id == 101L })
        assertTrue(st.multiCaptions.isEmpty())
    }

    @Test fun boxFromATrackUpIntoTheCaptionLaneThenDragTheGroup() {
        val st = state()
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { Timeline(st, Tool.SELECT, Modifier.fillMaxSize()) } } }
        rule.waitForIdle()
        val d = rule.density.density
        // ruler 28 dp, caption lane 28–58 dp, V3 from 58 dp; 16 dp per second
        fun at(sec: Float, yDp: Float) = Offset(sec * 16f * d, yDp * d)
        rule.onNodeWithTag("timeline").performTouchInput {
            down(at(1f, 72f))
            advanceEventTime(700)
            val to = at(9f, 40f)
            val from = at(1f, 72f)
            for (i in 1..12) moveTo(from + (to - from) * (i / 12f))
            up()
        }
        rule.waitForIdle()
        assertEquals(setOf(101L, 102L), st.multiCaptions)
        assertTrue("the clip on V1 is outside the box", st.multi.isEmpty())
        assertNull(st.selectedCaption)
        // drag "ett" 2 s to the right: both follow
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = at(3f, 43f)
            down(from)
            for (i in 1..10) moveTo(from + Offset(2f * 16f * d * i / 10, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(4f, st.captions.first { it.id == 101L }.start, 0.05f)
        assertEquals(8f, st.captions.first { it.id == 102L }.start, 0.05f)
        assertEquals(12f, st.captions.first { it.id == 103L }.start, 1e-4f)
    }
}
