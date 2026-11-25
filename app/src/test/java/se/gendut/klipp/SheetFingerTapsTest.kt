package se.gendut.klipp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Two-finger tap = undo and three-finger tap = redo inside the Add sheets too, as on the edit page. The sheets are part of
 * the editor (docked sheets), so the editor's own finger taps reach them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class SheetFingerTapsTest {
    @get:Rule val rule = createComposeRule()

    private fun tap(tag: String, fingers: Int) = rule.onNodeWithTag(tag).performTouchInput {
        for (i in 0 until fingers) down(i, center + Offset(i * 120f - 120f, 0f))
        advanceEventTime(60)
        for (i in 0 until fingers) up(i)
    }

    @Test fun twoFingersUndoAndThreeRedoInsideTheTitleSheet() = inSheet("add:title", "titleSheet")

    @Test fun twoFingersUndoAndThreeRedoInsideTheCaptionsSheet() = inSheet("add:captions", "captionsSheet")

    private fun inSheet(button: String, sheet: String) {
        val state = EditorState().apply { props = FakeProps() }  // the Title sheet needs a backend for its properties
        rule.setContent { KlippTheme { KlippApp(state, AppUi()) } }
        rule.waitForIdle()
        // an edit to undo: B-roll (8–13 s) split in two
        val before = state.clips.size
        state.split(2L, 10f)
        rule.waitForIdle()
        val after = state.clips.size
        assertEquals("the split made a clip", before + 1, after)
        state.selected = null
        rule.waitForIdle()
        rule.onNodeWithTag(button).performClick()
        rule.waitForIdle()
        tap(sheet, 2)
        rule.waitForIdle()
        assertEquals("undone inside the sheet", before, state.clips.size)
        tap(sheet, 3)
        rule.waitForIdle()
        assertEquals("redone inside the sheet", after, state.clips.size)
        rule.onNodeWithTag(sheet).assertExists()
    }
}
