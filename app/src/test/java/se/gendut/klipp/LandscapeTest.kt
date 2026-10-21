package se.gendut.klipp

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w891dp-h411dp-land-xxhdpi")
class LandscapeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun landscapeEditsAndDoubleTapGoesFullScreen() {
        val state = EditorState()
        rule.setContent { KlippTheme { KlippApp(state, AppUi()) } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/20_liggande.png")
        // landscape: the picture on the left, the timeline on the right
        assertTrue("timeline in landscape", rule.onAllNodesWithTag("timeline").fetchSemanticsNodes().isNotEmpty())
        // double tap on the picture = full screen (only the video)
        rule.onNodeWithTag("monitorArea").performTouchInput { click(center); advanceEventTime(80); click(center) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/21_helskarm.png")
        assertTrue("no timeline in full screen", rule.onAllNodesWithTag("timeline").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("fullViewer").performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        // the test environment advances time until the app is idle, so playback has time to finish – check that it played
        assertTrue("tap plays (the playhead has moved forward)", state.playing || state.playhead > 14.5f)
    }
}
