package se.gendut.klipp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sequence's transcript is the edit page's other view: a switch in the timeline's row (also on a narrow phone),
 * the same playhead and selection, a header that says which transcript it is, and back to the timeline.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w360dp-h792dp-xxhdpi")
class TranscriptViewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    @Test fun theSwitchKeepsThePlayheadAndSelectionAndBackReturns() {
        val st = EditorState().apply { playhead = 7.5f; selected = 2L }
        rule.setContent {
            KlippTheme {
                Box(Modifier.fillMaxSize().background(K.Bg)) {
                    EditorBack(st, of = { st.backState(false, false, false, 0, false) }) {}
                    EditPage(st, Tool.SELECT, {}, Toaster(), transcript = { m -> Box(m.testTag("fakeTranscript")) })
                }
            }
        }
        rule.waitForIdle()
        // on a narrow phone every button of the row is there, inside the screen
        val row = rule.onRoot().getUnclippedBoundsInRoot()
        for (tag in listOf("tool:menu", "view:transcript", "mode:magnet", "mode:link", "marker:add", "modes:menu", "appSettings")) {
            val b = rule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tag inside", b.left >= row.left && b.right <= row.right)
        }
        rule.onNodeWithTag("view:transcript").performClick()
        rule.waitForIdle()
        assertEquals(EditView.TRANSCRIPT, st.editView)
        rule.onNodeWithTag("fakeTranscript").assertExists()
        rule.onNodeWithText("Transkription · sekvens").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("timeline").fetchSemanticsNodes().size)
        // the playhead and the selection stay
        assertEquals(7.5f, st.playhead, 0f)
        assertEquals(2L, st.selected)
        // back: to the timeline first (the selection still there), then the selection
        back()
        assertEquals(EditView.TIMELINE, st.editView)
        assertEquals(2L, st.selected)
        assertFalse(rule.activity.isFinishing)
        // and the button in the header goes back too
        rule.onNodeWithTag("view:transcript").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("view:timeline").performClick()
        rule.waitForIdle()
        assertEquals(EditView.TIMELINE, st.editView)
    }

    /** The transcript ends above the dock (the floating bar): none of its buttons lie under it. */
    @Test fun theTranscriptEndsAboveTheDock() {
        val st = EditorState().apply { editView = EditView.TRANSCRIPT }
        rule.setContent {
            KlippTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalBarSpace provides 84.dp) {
                    Box(Modifier.fillMaxSize().background(K.Bg)) { EditPage(st, Tool.SELECT, {}, Toaster(), transcript = { m -> Box(m.testTag("fakeTranscript")) }) }
                }
            }
        }
        rule.waitForIdle()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val t = rule.onNodeWithTag("fakeTranscript").getUnclippedBoundsInRoot()
        assertTrue("ends ${t.bottom} above the bar (${root.bottom} - 84)", t.bottom <= root.bottom - 84.dp + 0.5.dp)
    }

    @Test fun theCutIsSaidBeforeItIsMade() {
        val cut = TranscriptCut(3.0f, 4.52f, listOf("V1", "A1"), 2, true, listOf("V2"), 1)
        rule.setContent { KlippTheme { Column(Modifier.background(K.Bg)) { TranscriptCutInfo(cut) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("tx:cut:range").assertIsDisplayed()
        rule.onNodeWithText("Klipps ur V1, A1: 2 klipp").assertIsDisplayed()
        rule.onNodeWithText("Det som kommer efter flyttas 1,5 s tidigare på de spåren.", substring = false).assertExists()
        rule.onNodeWithText("Ändras inte: V2", substring = true).assertIsDisplayed()
        rule.onNodeWithText("1 undertext ligger i området – dess text ändras inte.").assertIsDisplayed()
    }
}
