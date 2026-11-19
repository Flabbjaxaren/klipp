package se.gendut.klipp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The edit page's picture can be zoomed like the color page's: the monitor's touch (scrub, frame steps) around the
 * picture's own zoom, as App.kt puts them together (monitorTouch with pictureZoom outside, zoomGestures inside).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class EditPictureZoomTest {
    @get:Rule val rule = createComposeRule()

    private var fullscreen = 0

    private fun monitor(st: EditorState, z: MonitorZoom) {
        rule.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val haptics = rememberHaptics()
            Box(
                Modifier.size(400.dp, 225.dp).testTag("monitor")
                    .monitorTouch(st, enabled = true, haptics = haptics, onTier = {}, onDoubleTap = { fullscreen++ }, pictureZoom = true)
            ) {
                Box(Modifier.fillMaxSize().zoomGestures(z, onDoubleTapOut = { scope.launch { z.animateOut() } }, onFullscreen = { fullscreen++ }))
            }
        }
        rule.onNodeWithTag("monitor").performTouchInput { z.image = Size(width.toFloat(), height.toFloat()); z.monitor = z.image }
    }

    private fun pinchOut() = rule.onNodeWithTag("monitor").performTouchInput {
        pinch(center - Offset(40f, 0f), center - Offset(200f, 0f), center + Offset(40f, 0f), center + Offset(200f, 0f), durationMillis = 400)
    }

    @Test
    fun pinchZoomsWithoutSteppingOrScrubbing() {
        val st = EditorState()
        val z = MonitorZoom()
        monitor(st, z)
        val start = st.playhead
        pinchOut()
        rule.waitForIdle()
        assertTrue("zoomed: ${z.scale}", z.scale > 2f)
        assertEquals("no frame step, no scrub", start, st.playhead, 1e-6f)
        // one finger moves the zoomed picture instead of scrubbing
        val before = z.pan
        rule.onNodeWithTag("monitor").performTouchInput { swipe(center, center + Offset(150f, 0f), 300) }
        rule.waitForIdle()
        assertTrue("moved: $before → ${z.pan}", z.pan.x > before.x + 50f)
        assertEquals("no scrub while zoomed", start, st.playhead, 1e-6f)
        // double tap: back to 100 %, not full screen
        rule.onNodeWithTag("monitor").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertFalse(z.zoomed)
        assertEquals(0, fullscreen)
        // at 100 %: double tap = full screen, once
        rule.onNodeWithTag("monitor").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(1, fullscreen)
    }

    @Test
    fun holdAndTapStillStepsFramesAndDragStillScrubs() {
        val st = EditorState()
        val z = MonitorZoom()
        monitor(st, z)
        val start = st.playhead
        rule.onNodeWithTag("monitor").performTouchInput {
            down(0, center)
            advanceEventTime(500)
            down(1, Offset(center.x + 120f * density, center.y))
            advanceEventTime(80)
            up(1)
            advanceEventTime(200)
            down(1, Offset(center.x + 120f * density, center.y))
            advanceEventTime(80)
            up(1)
            up(0)
        }
        rule.waitForIdle()
        assertEquals("two frames forward", start + 2 * FRAME, st.playhead, 1e-3f)
        assertFalse(z.zoomed)
        val s2 = st.playhead
        rule.onNodeWithTag("monitor").performTouchInput {
            val from = Offset(width * 0.2f, height * 0.3f)
            down(from)
            for (i in 1..10) moveTo(Offset(from.x + width * 0.5f * i / 10, from.y))
            up()
        }
        rule.waitForIdle()
        assertTrue("scrubbed forward: ${st.playhead}", st.playhead > s2 + 1f)
    }
}
