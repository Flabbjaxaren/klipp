package se.gendut.klipp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ScrubBarTest {
    @get:Rule
    val rule = createComposeRule()

    private var markIn by mutableStateOf<Double?>(4.0)
    private var markOut by mutableStateOf<Double?>(12.0)
    private var pos by mutableStateOf(20.0)
    private var doneCount = 0

    private fun setUp() {
        rule.setContent {
            KlippTheme {
                Column(Modifier.fillMaxWidth()) {
                    ScrubBar(
                        duration = 40.0, pos = pos, markIn = markIn, markOut = markOut,
                        onScrub = { pos = it }, onMoveIn = { markIn = it }, onMoveOut = { markOut = it },
                        onMarksDone = { doneCount++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** The tagged node is the track itself (the margin lies outside). */
    private fun androidx.compose.ui.test.TouchInjectionScope.xOf(t: Double): Float {
        val pad = 0f // the testTag sits inside the margin
        return pad + ((width - 2 * pad) * (t / 40.0)).toFloat()
    }

    @Test
    fun dragInHandleAllTheWay() {
        setUp()
        rule.onNodeWithTag("scrub").performTouchInput {
            val y = 50f * density // on the tab below the track
            val from = Offset(xOf(4.0) - 8f * density, y)
            down(from)
            val to = xOf(10.0) - 8f * density
            for (i in 1..30) moveTo(Offset(from.x + (to - from.x) * i / 30, y))
            up()
        }
        rule.waitForIdle()
        assertEquals("the in point follows all the way", 10.0, markIn!!, 0.15)
        assertEquals(12.0, markOut!!, 0.001)
        assertEquals(1, doneCount)
        assertEquals("the playhead does not move", 20.0, pos, 0.001)
        rule.onRoot().captureRoboImage("build/shots/15_skrubb_in_dragen.png")
    }

    @Test
    fun outHandleCannotPassIn() {
        setUp()
        rule.onNodeWithTag("scrub").performTouchInput {
            val y = 50f * density
            val from = Offset(xOf(12.0) + 8f * density, y)
            down(from)
            for (i in 1..20) moveTo(Offset(from.x - (xOf(12.0) - xOf(1.0)) * i / 20, y))
            up()
        }
        rule.waitForIdle()
        assertEquals(4.0, markOut!!, 0.001)
    }

    @Test
    fun fineModeIsFourTimesSlower() {
        setUp()
        rule.onNodeWithTag("scrub").performTouchInput {
            val y = 50f * density
            val from = Offset(xOf(4.0) - 8f * density, y)
            down(from)
            // go down 120 dp (fine adjustment), then 8 s along x → only 2 s of movement
            moveTo(Offset(from.x, y + 120f * density))
            val dx = xOf(12.0) - xOf(4.0)
            for (i in 1..20) moveTo(Offset(from.x + dx * i / 20, y + 120f * density))
            up()
        }
        rule.waitForIdle()
        assertTrue("fine movement: ${markIn}", markIn!! in 5.7..6.3)
    }

    @Test
    fun tapElsewhereScrubs() {
        setUp()
        rule.onNodeWithTag("scrub").performTouchInput {
            down(Offset(xOf(30.0), 22f * density)); up()
        }
        rule.waitForIdle()
        assertEquals(30.0, pos, 0.2)
        assertEquals(4.0, markIn!!, 0.001)
    }
}
