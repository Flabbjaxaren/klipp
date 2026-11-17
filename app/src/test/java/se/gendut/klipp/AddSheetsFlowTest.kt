package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.TitleTemplate

/**
 * Add › Title and Add › Captions in the order things are made: a title's text first, then its look (which keeps the
 * text); captions in parts – from speech, written by hand, those in the project.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class AddSheetsFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aTitleIsWrittenFirstAndTheLookKeepsTheText() {
        val fake = FakeProps()
        val st = EditorState().apply { props = fake; playhead = 2f }
        var open by mutableStateOf(true)
        rule.setContent { KlippTheme { Column(Modifier.fillMaxSize().background(K.Bg).verticalScroll(rememberScrollState())) { if (open) TitlesTab(st, fake) {} } } }
        rule.waitForIdle()
        // the text comes before the look
        val field = rule.onNodeWithTag("titleField:0").getUnclippedBoundsInRoot()
        val look = rule.onNodeWithTag("mall:namnskylt").getUnclippedBoundsInRoot()
        assertTrue("text above the look", field.bottom <= look.top)
        rule.onRoot().captureRoboImage("build/shots/92_titel_text_forst.png")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("titleField:0"))).performTextInput("Maja Lind")
        rule.waitForIdle()
        // another look: the name stays
        rule.onNodeWithTag("mall:kapitel").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(TitleTemplate.CHAPTER, st.titleDraft.template)
        assertEquals("Maja Lind", st.titleDraft.lines.first())
        // the sheet closed by mistake and opened again: the text is still there
        rule.runOnUiThread { open = false }
        rule.waitForIdle()
        rule.runOnUiThread { open = true }
        rule.waitForIdle()
        assertEquals("Maja Lind", st.titleDraft.lines.first())
        rule.onNodeWithTag("title:add").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(fake.log.last(), fake.log.last().startsWith("title Maja Lind @2.0 klipp:mall=kapitel"))
        // added: the next title starts empty
        assertTrue(st.titleDraft.lines.isEmpty())
    }

    @Test fun theCaptionPartsAsTheyLook() {
        val fake = FakeProps().apply { tracks = listOf(CaptionTrackUi(10, "Intervju.srt", 2, true), CaptionTrackUi(11, "Automatiska undertexter", 2, true)) }
        val st = EditorState().apply { props = fake; captions = listOf(UiCaption(1, 0.5f, 2f, "Hej och välkommen", track = 10), UiCaption(2, 2.5f, 4f, "I dag ska vi", track = 11)) }
        rule.setContent { KlippTheme { Column(Modifier.fillMaxSize().background(K.Bg).verticalScroll(rememberScrollState())) { CaptionsTab(st, fake) {} } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/94_undertexter_befintliga.png")
        rule.onNodeWithTag("capsPart:speech").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/95_undertexter_fran_tal.png")
    }

    /** A written line fits the room before the next caption on its track, or the button says why it can't go in. */
    @Test fun aWrittenLineFitsTheRoomOrSaysWhy() {
        val fake = FakeProps()
        val st = EditorState().apply { props = fake; playhead = 1.5f; captions = listOf(UiCaption(1, 0f, 1f, "a"), UiCaption(2, 2f, 3f, "b")) }
        rule.setContent { KlippTheme { Column(Modifier.fillMaxSize().background(K.Bg).verticalScroll(rememberScrollState())) { CaptionsTab(st, fake) {} } } }
        rule.waitForIdle()
        rule.onNodeWithTag("capsPart:write").performClick()
        rule.onNodeWithTag("captionWriteText").performTextInput("Mitt emellan")
        rule.waitForIdle()
        // 1.5 s up to the next caption at 2 s: half a second long
        rule.onNodeWithTag("captionWriteAdd").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(fake.log.last(), fake.log.last().endsWith(" 0.50s"))
        // in a caption: not offered, and said why
        rule.runOnUiThread { st.playhead = 2.5f; st.captionDraft = "Till" }
        rule.waitForIdle()
        rule.onNodeWithTag("captionWriteAdd").assertIsNotEnabled()
        rule.onNodeWithText("Spelhuvudet står i en undertext på spåret – flytta det till en tom plats.").assertExists()
    }

    @Test fun captionsOpenOnWhatThereIs() {
        val fake = FakeProps()
        val none = EditorState().apply { props = fake }
        assertEquals(CaptionsPart.SPEECH, firstCaptionsPart(none))
        val some = EditorState().apply { props = fake; captions = listOf(UiCaption(1, 0f, 1f, "Hej")) }
        assertEquals(CaptionsPart.EXISTING, firstCaptionsPart(some))
    }

    @Test fun writingALineAddsItAndTheNextFollowsOn() {
        val fake = FakeProps()
        val st = EditorState().apply { props = fake; playhead = 3f }
        rule.setContent { KlippTheme { Column(Modifier.fillMaxSize().background(K.Bg).verticalScroll(rememberScrollState())) { CaptionsTab(st, fake) {} } } }
        rule.waitForIdle()
        rule.onNodeWithTag("capsPart:write").performClick()
        rule.waitForIdle()
        // nothing typed: nothing to add
        rule.onNodeWithTag("captionWriteAdd").assertIsNotEnabled()
        rule.onRoot().captureRoboImage("build/shots/93_undertexter_skriv.png")
        rule.onNodeWithTag("captionWriteText").performTextInput("Välkommen hit")
        // (the engine's refresh: the new caption, 3–5 s)
        fake.beforeAdded = { st.captions = listOf(UiCaption(99, 3f, 5f, "Välkommen hit")) }
        rule.onNodeWithTag("captionWriteAdd").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(fake.log.last(), fake.log.last().startsWith("addcaption @3.0 Välkommen hit 0 "))
        // the playhead at its end, the field empty for the next line
        assertEquals(5f, st.playhead, 0.001f)
        assertEquals("", st.captionDraft)
        // the file import is here too
        assertEquals(1, rule.onAllNodesWithTag("capsImport").fetchSemanticsNodes().size)
    }
}
