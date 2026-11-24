package se.gendut.klipp

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.MediaProbe

/** Speed → Slow-mo: a high-frame-rate clip at the project's rate, every source frame used once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlowMoTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun resetRate() { projectFps = 25.0 }

    private fun open(clipFps: Double, project: Double): Pair<EditorState, MutableList<Int>> {
        projectFps = project
        val state = EditorState(EditorState.initialClips().map { if (it.id == 2L) it.copy(item = 7L) else it })
        val uri = Uri.parse("content://slowmo/$clipFps")
        MediaProbe.put(uri, MediaProbe.Info(10.0, 0.0, clipFps))
        val asked = mutableListOf<Int>()
        state.uriOfItem = { if (it == 7L) uri else null }
        state.onSpeed = { _, p -> asked += p }
        rule.setContent { KlippTheme { KlippApp(state, AppUi()) } }
        rule.waitForIdle()
        rule.runOnUiThread { state.selected = 2L }
        rule.waitForIdle()
        rule.onNodeWithTag("ctx:speed").performClick()
        rule.waitForIdle()
        return state to asked
    }

    @Test fun aVariable120ClipInA24ProjectGoesToTwentyPercent() {
        val (_, asked) = open(119.6, 24.0)
        rule.onNodeWithTag("speed:conform").assertTextEquals("Slow-mo 120→24")
        rule.onNodeWithTag("speed:conform").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(20), asked)
    }

    @Test fun ratesThatDontDivideAreMarkedApproximate() {
        val (_, asked) = open(120.0, 25.0)
        rule.onNodeWithText("Slow-mo 120→25 ≈").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(21), asked)
    }

    @Test fun noSlowMoForAClipAtTheProjectsRate() {
        open(30.0, 30.0)
        rule.onNodeWithTag("speed:conform").assertDoesNotExist()
    }
}
