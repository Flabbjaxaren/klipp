package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captions are edited in one component (F3): the caption editor over the timeline and the captions list both use it.
 * It saves valid text by itself (after a pause and when it closes), groups an editing session into one undo step (the
 * session travels with each save), and never saves an empty text – nor loses what was typed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class CaptionEditingTest {
    @get:Rule val rule = createComposeRule()

    private fun saves(fake: FakeProps) = fake.log.filter { it.startsWith("captiontext") }

    private fun inspector(fake: FakeProps): EditorState {
        val st = EditorState().apply {
            props = fake; captions = listOf(UiCaption(1, 0.5f, 2f, "Första raden"), UiCaption(2, 2.5f, 4f, "Andra raden"))
            selectedCaption = 1L; editingCaption = true
        }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { if (st.editingCaption) CaptionInspector(st) } } }
        rule.waitForIdle()
        return st
    }

    @Test fun theEditorSavesAfterAPauseAsOneSession() {
        val fake = FakeProps()
        val st = inspector(fake)
        rule.onNodeWithTag("captionText").performTextReplacement("Hej")
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.onNodeWithTag("captionText").performTextReplacement("Hej och välkommen")
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals(listOf("captiontext 1 Hej", "captiontext 1 Hej och välkommen"), saves(fake))
        // the two saves are one editing session: the controller makes them one undo step
        assertNotNull(fake.textSessions[0])
        assertSame(fake.textSessions[0], fake.textSessions[1])
        // closed and opened again: a new session
        rule.runOnUiThread { st.editingCaption = false }
        rule.waitForIdle()
        rule.runOnUiThread { st.editingCaption = true }
        rule.waitForIdle()
        rule.onNodeWithTag("captionText").performTextReplacement("Hej igen")
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertNotSame(fake.textSessions[0], fake.textSessions.last())
    }

    @Test fun closingSavesWhatWasTyped() {
        val fake = FakeProps()
        val st = inspector(fake)
        rule.onNodeWithTag("captionText").performTextReplacement("Skrivet precis innan")
        // closed before the pause is over: the text is saved, not lost
        rule.runOnUiThread { st.editingCaption = false }
        rule.waitForIdle()
        assertEquals(listOf("captiontext 1 Skrivet precis innan"), saves(fake))
    }

    @Test fun anEmptyTextIsNotSavedAndSaysWhy() {
        val fake = FakeProps()
        val st = inspector(fake)
        rule.onNodeWithTag("captionText").performTextReplacement("   ")
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodesWithTag("captionBlank").fetchSemanticsNodes().size)
        rule.runOnUiThread { st.editingCaption = false }
        rule.waitForIdle()
        assertTrue(saves(fake).isEmpty())
    }

    @Test fun theListUsesTheSameEditor() {
        val fake = FakeProps()
        var open by mutableStateOf(true)
        val st = EditorState().apply { props = fake; captions = listOf(UiCaption(1, 0.5f, 2f, "Första raden")) }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { if (open) CaptionsTab(st, fake) {} } } }
        rule.waitForIdle()
        rule.onNodeWithTag("caption:row:1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("caption:1").performTextReplacement("Rättad")
        // it saves by itself after a pause (no Save button)
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals(listOf("captiontext 1 Rättad"), saves(fake))
        // typing more and the sheet closing: saved, same session
        rule.onNodeWithTag("caption:1").performTextReplacement("Rättad igen")
        rule.runOnUiThread { open = false }
        rule.waitForIdle()
        assertEquals(listOf("captiontext 1 Rättad", "captiontext 1 Rättad igen"), saves(fake))
        assertSame(fake.textSessions[0], fake.textSessions[1])
    }
}
