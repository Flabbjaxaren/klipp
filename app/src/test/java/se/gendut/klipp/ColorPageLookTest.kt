package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The color page's look: the lower part (gallery, actions) and the tool tabs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ColorPageLookTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lowerPartAndScopes() {
        val st = EditorState().apply { playhead = 2f; selected = null }
        val gs = GradeState()
        val clips = listOf(ColorClip(1, "A003_intervju", 0f, 8f, null, 0.0, 2), ColorClip(2, "B-roll_hamn", 8f, 13f, null, 0.0, 2))
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { ColorPage(st, gs, clips) { Text("tidslinje") } } } }
        rule.waitForIdle()
        rule.onNodeWithTag("scopes").performClick()
        rule.waitForIdle()
        captureScreenRoboImage("build/shots/42_farg_scopesmeny.png")
        rule.onNodeWithTag("scope:1").performClick()
        rule.onNodeWithTag("editor:Justera").performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.onRoot().captureRoboImage("build/shots/40_farg_justera.png")
        rule.onNodeWithTag("grade:reset").performScrollTo()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/41_farg_nederst.png")
    }

    /** The playhead in color mode: drag through the clip, whole frames, never outside the clip. */
    @Test fun scrubberMovesPlayheadWithinClip() {
        val st = EditorState().apply { playhead = 2f; selected = 2L; playing = true }
        val gs = GradeState()
        val clips = listOf(ColorClip(1, "A", 0f, 8f, null, 0.0, 2), ColorClip(2, "B", 8f, 13f, null, 0.0, 2))
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { ColorPage(st, gs, clips) { Text("tidslinje") } } } }
        rule.waitForIdle()
        rule.onNodeWithTag("scrub").performTouchInput {
            down(Offset(width * 0.1f, height / 2f))
            moveTo(Offset(width * 0.5f, height / 2f))
        }
        rule.waitForIdle()
        assertFalse(st.playing)
        assertEquals(10.5f, st.playhead, 0.05f)
        // whole frames
        assertEquals(0f, (st.playhead * FPS) - Math.round(st.playhead * FPS), 1e-3f)
        rule.onNodeWithTag("scrub").performTouchInput {
            moveTo(Offset(width * 1.5f, height / 2f))
            up()
        }
        rule.waitForIdle()
        // the last frame of the clip, not the next clip
        assertEquals(13f - 1f / FPS, st.playhead, 1e-3f)
    }
}
