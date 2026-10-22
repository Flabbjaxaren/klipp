package se.gendut.klipp

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
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

/** The color page's monitor touch: pinch, move, double tap – and the image's own tools come first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class MonitorZoomGestureTest {
    @get:Rule val rule = createComposeRule()

    private var fullscreen = 0

    private fun monitor(z: MonitorZoom, toolTakesTouch: Boolean = false) {
        rule.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            Box(
                Modifier.size(400.dp, 225.dp).testTag("monitor")
                    .zoomGestures(z, onDoubleTapOut = { scope.launch { z.animateOut() } }, onFullscreen = { fullscreen++ })
            ) {
                // a tool in the image (window, eyedropper) that consumes what it touches
                if (toolTakesTouch) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                            if (ev.changes.none { it.pressed }) break
                        }
                    }
                })
            }
        }
        rule.onNodeWithTag("monitor").performTouchInput { z.image = Size(width.toFloat(), height.toFloat()); z.monitor = z.image }
    }

    private fun pinchOut() = rule.onNodeWithTag("monitor").performTouchInput {
        pinch(center - Offset(40f, 0f), center - Offset(200f, 0f), center + Offset(40f, 0f), center + Offset(200f, 0f), durationMillis = 400)
    }

    @Test
    fun pinchZoomsMoveAndDoubleTapGoesBack() {
        val z = MonitorZoom()
        monitor(z)
        pinchOut()
        rule.waitForIdle()
        assertTrue("zoomed: ${z.scale}", z.scale > 2f)
        // one finger moves the zoomed image
        val before = z.pan
        rule.onNodeWithTag("monitor").performTouchInput { swipe(center, center + Offset(150f, 0f), 300) }
        rule.waitForIdle()
        assertTrue("moved: $before → ${z.pan}", z.pan.x > before.x + 50f)
        // double tap: back to 100 %, not full screen
        rule.onNodeWithTag("monitor").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertFalse(z.zoomed)
        assertEquals(Offset.Zero, z.pan)
        assertEquals(0, fullscreen)
        // at 100 %: double tap = full screen
        rule.onNodeWithTag("monitor").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(1, fullscreen)
    }

    @Test
    fun oneFingerAtHundredPercentDoesNothing() {
        val z = MonitorZoom()
        monitor(z)
        rule.onNodeWithTag("monitor").performTouchInput { swipe(center, center + Offset(150f, 0f), 300) }
        rule.waitForIdle()
        assertEquals(1f, z.scale)
        assertEquals(Offset.Zero, z.pan)
    }

    @Test
    fun toolsInTheImageComeFirst() {
        val z = MonitorZoom()
        monitor(z, toolTakesTouch = true)
        pinchOut()
        rule.waitForIdle()
        assertEquals(1f, z.scale)
    }
}
