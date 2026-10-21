package se.gendut.klipp

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The system back closes what is open (properties, another page) before it leaves the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    @Test fun backClosesPropertiesThenPageThenLeaves() {
        val state = EditorState()
        val ui = AppUi()
        rule.setContent { KlippTheme { KlippApp(state, ui) } }
        rule.waitForIdle()
        // another page: back = to the Edit page
        rule.runOnUiThread { kotlinx.coroutines.runBlocking { ui.page.snapTo(2f) } }
        rule.waitForIdle()
        back()
        rule.mainClock.advanceTimeBy(2000)
        rule.waitForIdle()
        assertEquals(0f, ui.page.targetValue, 0.01f)
        // properties open: back closes them, the app stays
        rule.runOnUiThread { state.selected = 2L; state.inspecting = true }
        rule.waitForIdle()
        back()
        assertFalse(state.inspecting)
        assertFalse(rule.activity.isFinishing)
        // nothing open: back leaves (the activity finishes)
        back()
        assertEquals(true, rule.activity.isFinishing)
    }
}
