package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Messages show in the window the user is in – a sheet or a dialog over the editor – once, and never late: an action
 * refused at once, an error that comes later, the same message twice, a message from before the sheet opened.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class MessagesTest {
    @get:Rule val rule = createComposeRule()

    private val toaster = Toaster()
    private var sheetOpen by mutableStateOf(false)
    private var dialogOpen by mutableStateOf(false)
    private val fake = FakeProps()
    private val st = EditorState().apply { props = fake; playhead = 2f }

    private fun content() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
        KlippTheme {
            CompositionLocalProvider(LocalToaster provides toaster) {
                Box(Modifier.fillMaxSize().background(K.Bg)) {
                    Box(Modifier.fillMaxSize().testTag("editor"))
                    if (sheetOpen) TitleSheet(st) { sheetOpen = false }
                    if (dialogOpen) AlertDialog(
                        onDismissRequest = { dialogOpen = false },
                        title = { Text("Dialog") },
                        text = { androidx.compose.foundation.layout.Column(Modifier.testTag("dialogBody")) { Text("Body"); MessageHost(inline = true) } },
                        confirmButton = {},
                    )
                    MessageHost(toaster = toaster, layer = 0)
                }
            }
        }
        }
        settle(500)
    }

    /** The message's nodes: in which window (sheet, dialog, editor) and how many. */
    private fun shown(text: String) = rule.onAllNodes(hasTestTag("message") and hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun inSheet(text: String) = rule.onAllNodes(hasTestTag("message") and hasText(text) and hasAnyAncestor(hasTestTag("titleSheet")), useUnmergedTree = true)
        .fetchSemanticsNodes().size

    /** A few frames (the clock moves only when told: the messages' times are measured, not skipped). */
    private fun settle(ms: Long = 200) { rule.waitForIdle(); rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    @Test fun aRefusalInTheSheetShowsThereAndKeepsWhatWasTyped() {
        val why = "Inget ledigt videospår ovanför bilden under titelns 5 s."
        fake.titleRefusal = why
        fake.onTitleMessage = { toaster.show(it) }   // refused at once, inside the tap
        sheetOpen = true
        content()
        settle()
        rule.onNode(hasSetTextAction() and (hasTestTag("titleField:0") or hasAnyAncestor(hasTestTag("titleField:0")))).performTextInput("Maja Lind")
        rule.onNodeWithTag("title:add").performClick()
        settle()
        assertEquals("shown once", 1, shown(why))
        assertEquals("in the sheet", 1, inSheet(why))
        // the sheet is still open, the text still there, nothing added
        rule.onNodeWithTag("titleSheet").assertIsDisplayed()
        assertEquals("Maja Lind", st.titleDraft.lines.first())
        assertTrue(fake.log.last().startsWith("title Maja Lind"))
        // it goes after its time, and doesn't come back in the editor when the sheet closes
        settle(Toaster.duration(why) + 1000)
        assertEquals(0, shown(why))
        sheetOpen = false
        settle(3000)
        assertEquals("not again in the editor", 0, shown(why))
    }

    @Test fun anErrorThatComesLaterShowsInTheSheetOpenThen() {
        sheetOpen = true
        content()
        settle()
        rule.onNode(hasSetTextAction() and (hasTestTag("titleField:0") or hasAnyAncestor(hasTestTag("titleField:0")))).performTextInput("Rubrik")
        settle(800)
        // as the engine's answer: from outside the tap, a while later
        rule.runOnUiThread { toaster.show("Kunde inte skapa titeln: disk full") }
        settle()
        assertEquals(1, shown("Kunde inte skapa titeln: disk full"))
        assertEquals(1, inSheet("Kunde inte skapa titeln: disk full"))
        assertEquals("Rubrik", st.titleDraft.lines.first())
        // closed while it shows: cut short, and the editor doesn't show it afterwards
        rule.runOnUiThread { sheetOpen = false }
        settle(500)
        assertEquals(0, shown("Kunde inte skapa titeln: disk full"))
        settle(5000)
        assertEquals(0, shown("Kunde inte skapa titeln: disk full"))
    }

    @Test fun theSameMessageTwiceShowsOnceAndAnOldOneNeverInANewWindow() {
        content()
        settle()
        rule.runOnUiThread { toaster.show("Spåret är låst"); toaster.show("Spåret är låst") }
        settle()
        assertEquals(1, shown("Spåret är låst"))
        // a sheet opens over it: the sheet doesn't take an old message (and the editor's is hidden behind the sheet)
        rule.runOnUiThread { sheetOpen = true }
        settle(1000)
        assertEquals(0, inSheet("Spåret är låst"))
        rule.runOnUiThread { sheetOpen = false }
        settle(6000)
        assertEquals("not again when the sheet closes", 0, shown("Spåret är låst"))
        // its turn is over: the same text, said again, shows again
        rule.runOnUiThread { toaster.show("Spåret är låst") }
        settle()
        assertEquals(1, shown("Spåret är låst"))
    }

    @Test fun aDialogShowsTheMessageInItsOwnText() {
        dialogOpen = true
        content()
        settle()
        rule.runOnUiThread { toaster.show("Kunde inte återställa versionen") }
        settle()
        assertEquals("once", 1, shown("Kunde inte återställa versionen"))
        assertEquals("in the dialog", 1, rule.onAllNodes(hasTestTag("message") and hasText("Kunde inte återställa versionen") and hasAnyAncestor(hasTestTag("dialogBody")), useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNode(hasTestTag("message") and hasText("Kunde inte återställa versionen"), useUnmergedTree = true).assertIsDisplayed()
        rule.runOnUiThread { dialogOpen = false }
        settle(6000)
        assertEquals(0, shown("Kunde inte återställa versionen"))
    }
}
