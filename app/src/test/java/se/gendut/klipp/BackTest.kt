package se.gendut.klipp

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
        // the clip is still selected: back lets go of it first
        assertEquals(2L, state.selected)
        back()
        assertEquals(null, state.selected)
        assertFalse(rule.activity.isFinishing)
        // nothing open: back leaves (the activity finishes)
        back()
        assertEquals(true, rule.activity.isFinishing)
    }

    /** A row of steps in the dock (here Speed) closes on back – the clip stays selected and the app stays. */
    @Test fun backClosesTheDockRowThenTheSelection() {
        val state = EditorState()
        rule.setContent { KlippTheme { KlippApp(state, AppUi()) } }
        rule.waitForIdle()
        rule.runOnUiThread { state.selected = 2L }
        rule.waitForIdle()
        rule.onNodeWithTag("ctx:speed").performClick()
        rule.waitForIdle()
        assertEquals(DockRow.SPEED, state.dockRow)
        back()
        assertEquals(DockRow.NONE, state.dockRow)
        assertEquals(2L, state.selected)
        assertFalse(rule.activity.isFinishing)
        // the dock shows the clip's actions again
        rule.onNodeWithTag("ctx:speed").assertExists()
        back()
        assertEquals(null, state.selected)
        assertFalse(rule.activity.isFinishing)
        back()
        assertEquals(true, rule.activity.isFinishing)
    }

    /** The caption editor closes on back and leaves the caption selected; the next back lets go of it. */
    @Test fun backClosesTheCaptionEditorFirst() {
        val state = EditorState()
        rule.setContent { KlippTheme { KlippApp(state, AppUi()) } }
        rule.waitForIdle()
        rule.runOnUiThread { state.selectedCaption = 7L; state.editingCaption = true }
        rule.waitForIdle()
        back()
        assertFalse(state.editingCaption)
        assertEquals(7L, state.selectedCaption)
        assertFalse(rule.activity.isFinishing)
        back()
        assertEquals(null, state.selectedCaption)
        assertFalse(rule.activity.isFinishing)
        back()
        assertEquals(true, rule.activity.isFinishing)
    }

    /** A selected transition and a selected edit point each take one press. */
    @Test fun backLetsGoOfATransitionThenTheClip() {
        val state = EditorState()
        rule.setContent { KlippTheme { KlippApp(state, AppUi()) } }
        rule.waitForIdle()
        rule.runOnUiThread { state.selected = 2L; state.selectedTransition = 5L }
        rule.waitForIdle()
        back()
        assertEquals(null, state.selectedTransition)
        assertEquals(2L, state.selected)
        back()
        assertEquals(null, state.selected)
        assertFalse(rule.activity.isFinishing)
    }
}
