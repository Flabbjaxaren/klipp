package se.gendut.klipp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The media sheet's name card (new folder, rename) lay outside the sheet: back closed the sheet and left the card on
 * screen with the typed text (F4). Now back closes the card first, and closing the sheet closes the card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class MediaSheetBackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }
    private fun cards() = rule.onAllNodesWithTag("nameField").fetchSemanticsNodes().size

    @Test fun backClosesTheNameCardThenTheSheet() {
        var open by mutableStateOf(true)
        var closed = 0
        val state = EditorState()
        rule.setContent {
            KlippTheme {
                Box(Modifier.fillMaxSize().background(K.Bg)) {
                    // the app's handler below the sheet's own, as in App (the sheet is composed later)
                    EditorBack(state, of = { state.backState(open, false, false, 0, false) }) { if (it == BackLayer.MEDIA) { open = false; closed++ } }
                    MediaSheet(open, FakeLibrary(), {}, {}, { open = false; closed++ })
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("newBin").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("nameField").performTextReplacement("Intervjuer")
        rule.waitForIdle()
        assertEquals(1, cards())
        // back: the card closes, the sheet stays
        back()
        assertEquals(0, cards())
        assertEquals(true, open)
        assertEquals(0, closed)
        // back again: the sheet closes; the app stays
        back()
        assertEquals(false, open)
        assertEquals(1, closed)
        assertFalse(rule.activity.isFinishing)
    }

    @Test fun closingTheSheetClosesTheNameCard() {
        var open by mutableStateOf(true)
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { MediaSheet(open, FakeLibrary(), {}, {}, { open = false }) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("newBin").performClick()
        rule.waitForIdle()
        assertEquals(1, cards())
        rule.runOnUiThread { open = false }
        rule.waitForIdle()
        assertEquals(0, cards())
        // opened again: no card left over
        rule.runOnUiThread { open = true }
        rule.waitForIdle()
        assertEquals(0, cards())
    }
}
