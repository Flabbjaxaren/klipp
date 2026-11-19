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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.verticalScroll
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

    @Test fun aListRowOpensTheCaptionEditor() {
        val fake = FakeProps()
        var edited = 0
        val st = EditorState().apply { props = fake; selected = 4L; captions = listOf(UiCaption(1, 0.5f, 2f, "Första raden"), UiCaption(2, 3f, 4f, "Andra")) }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { CaptionsTab(st, fake) { edited++ } } } }
        rule.waitForIdle()
        rule.onNodeWithTag("caption:row:2").performClick()
        rule.waitForIdle()
        // the same editor as tapping the caption in the timeline: selected, editor open, the sheet closes (onEdit)
        assertEquals(2L, st.selectedCaption)
        assertTrue(st.editingCaption)
        assertEquals(null, st.selected)
        assertEquals(3f, st.playhead, 0.001f)
        assertEquals(1, edited)
    }

    /** The caption editor shows the times and sets either edge at the playhead – within the drag's limits. */
    @Test fun theEdgeButtonsFollowThePlayheadAndTheLimits() {
        val fake = FakeProps()
        val st = inspector(fake)
        rule.onNodeWithTag("captionTimes").assertExists()
        // the playhead inside the caption (0.5–2.0): both can be set
        rule.runOnUiThread { st.playhead = 1.0f }
        rule.waitForIdle()
        rule.onNodeWithTag("captionSetStart").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("captionSetEnd").performClick()
        rule.waitForIdle()
        assertEquals(listOf("trimcaption 1 in 0.50", "trimcaption 1 out -1.00"), fake.log.filter { it.startsWith("trimcaption") })
        // after the next caption's start (2.5): the end would go into it – not offered, and said why
        rule.runOnUiThread { st.playhead = 3.0f }
        rule.waitForIdle()
        rule.onNodeWithTag("captionSetEnd").performClick()
        rule.onNodeWithTag("captionSetStart").performClick()
        rule.waitForIdle()
        assertEquals(2, fake.log.count { it.startsWith("trimcaption") })
        assertTrue(rule.onAllNodesWithTag("captionEdgeWhy").fetchSemanticsNodes().size == 2)
    }

    /**
     * The overview follows one track: the first with lines until another is chosen in the picker ("name · lines",
     * "Empty"); its lines only, its own visibility, an empty track says so. Choosing a track shows or hides nothing.
     */
    @Test fun theOverviewFollowsTheChosenTrack() {
        val fake = FakeProps().apply { tracks = listOf(se.gendut.klipp.CaptionTrackUi(10, "Intervju.srt", 1, true), se.gendut.klipp.CaptionTrackUi(11, "Auto captions", 0, true)) }
        val st = EditorState().apply { props = fake; captions = listOf(UiCaption(1, 0.5f, 2f, "Rad", track = 10)) }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { androidx.compose.foundation.layout.Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { CaptionsTab(st, fake) {} } } } }
        rule.waitForIdle()
        rule.onNodeWithText("Intervju.srt · 1 rad").assertExists()
        rule.onNodeWithTag("caption:row:1").assertExists()
        rule.onNodeWithTag("capStyle").assertExists()
        // the other track: chosen in the picker – the list follows it, and it is empty
        rule.onNodeWithTag("capTrackPicker").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Auto captions · Tomt").assertExists()
        rule.onNodeWithTag("capTrack:11").performClick()
        rule.waitForIdle()
        assertEquals(11L, st.captionTrack)
        assertEquals(0, rule.onAllNodesWithTag("caption:row:1").fetchSemanticsNodes().size)
        rule.onNodeWithTag("capTrackEmpty").assertExists()
        assertTrue("choosing shows or hides nothing", fake.log.none { it.startsWith("trackvisible") || it.startsWith("visible") })
        // this track's visibility, by itself
        rule.onNodeWithTag("capTrackVisible").performClick()
        rule.waitForIdle()
        assertEquals(listOf("trackvisible 11 false"), fake.log.filter { it.startsWith("trackvisible") })
        // empty tracks are removed on request only
        rule.onNodeWithTag("capRemoveEmpty").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("deletetracks 11"), fake.log.filter { it.startsWith("deletetracks") })
        // renaming: offered for the track (its dialog has a text field – checked on the phones, see LayoutReachTest)
        rule.onNodeWithTag("capTrackRename").assertExists()
    }

    /** A line written with no caption track yet goes to a new track with a name of its own. */
    @Test fun aWrittenLineWithoutATrackMakesANamedTrack() {
        val fake = FakeProps().apply { tracks = emptyList() }
        val st = EditorState().apply { props = fake; playhead = 1f; captionDraft = "Hej" }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { androidx.compose.foundation.layout.Column { CaptionsWrite(st, fake); CaptionWriteButton(st, fake) } } } }
        rule.waitForIdle()
        rule.onNodeWithText("Ett nytt spår skapas: Undertexter 1").assertExists()
        rule.onNodeWithTag("captionWriteAdd").performClick()
        rule.waitForIdle()
        assertEquals("newtrack Undertexter 1", fake.log.first { it.startsWith("newtrack") })
        assertTrue(fake.log.any { it.startsWith("addcaption") && it.contains(" Hej 50") })
        assertEquals(50L, st.captionTrack)
    }
}
