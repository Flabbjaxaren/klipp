package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A docked sheet leaves what lies above it usable (the timeline's tracks), and its handle raises it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class DockSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theEditorAboveTheSheetStillTakesTaps() {
        var behind = 0
        var dismissed = 0
        rule.setContent {
            KlippTheme {
                // (as in the app: the sheet lies over the editor, beside it in the tree)
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(K.Bg).clickable { behind++ }.testTag("editor"))
                    DockSheet("sheet", { dismissed++ }) { _ -> Box(Modifier.fillMaxWidth().height(100.dp).testTag("inside")) }
                }
            }
        }
        rule.waitForIdle()
        // a tap high on the screen (the picture, the timeline's top) reaches the editor
        rule.onNodeWithTag("editor").performTouchInput { click(Offset(width / 2f, height * 0.2f)) }
        rule.waitForIdle()
        assertEquals(1, behind)
        // a tap on the sheet doesn't
        rule.onNodeWithTag("inside").performClick()
        rule.waitForIdle()
        assertEquals(1, behind)
        // the handle raises it: then the same high tap lands on the editor only if above 90 %
        val low = rule.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.height
        rule.onNodeWithTag("dockHandle").performClick()
        rule.waitForIdle()
        val high = rule.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.height
        assertTrue("raised: $low → $high", high > low * 1.4f)
        assertEquals(0, dismissed)
    }
}
