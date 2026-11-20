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

/** A docked sheet opens expanded, its handle lowers it to a smaller state (what lies above it is usable), and a release settles on one of the two. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class DockSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun itOpensExpandedAndTheHandleLowersIt() {
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
        val root = rule.onNodeWithTag("editor").fetchSemanticsNode().boundsInRoot.height
        val high = rule.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.height
        assertEquals("opens expanded", DOCK_HIGH, high / root, 0.02f)
        // the top bar above it still takes taps; a tap on the sheet doesn't reach the editor
        rule.onNodeWithTag("editor").performTouchInput { click(Offset(width / 2f, height * 0.05f)) }
        rule.onNodeWithTag("inside").performClick()
        rule.waitForIdle()
        assertEquals(1, behind)
        // the handle lowers it to the smaller state: then the picture's height is the editor's again
        rule.onNodeWithTag("dockHandle").performClick()
        rule.waitForIdle()
        val low = rule.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.height
        assertEquals("the smaller state", DOCK_LOW, low / root, 0.02f)
        rule.onNodeWithTag("editor").performTouchInput { click(Offset(width / 2f, height * 0.3f)) }
        rule.waitForIdle()
        assertEquals(2, behind)
        // and raises it again
        rule.onNodeWithTag("dockHandle").performClick()
        rule.waitForIdle()
        assertEquals(DOCK_HIGH, rule.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.height / root, 0.02f)
        assertEquals(0, dismissed)
    }

    @Test fun aReleaseSettlesOnOneOfTheTwoStates() {
        assertEquals(DOCK_HIGH, dockRestingPlace(0.8f, 0f), 0f)
        assertEquals(DOCK_LOW, dockRestingPlace(0.6f, 0f), 0f)
        // a quick pull down from high up goes all the way, a push up from low down too
        assertEquals(DOCK_LOW, dockRestingPlace(0.85f, -2f), 0f)
        assertEquals(DOCK_HIGH, dockRestingPlace(0.5f, 2f), 0f)
        // past a state (the resisted overshoot): back to it
        assertEquals(DOCK_LOW, dockRestingPlace(0.41f, 0f), 0f)
        assertEquals(DOCK_HIGH, dockRestingPlace(0.92f, 0f), 0f)
    }
}
