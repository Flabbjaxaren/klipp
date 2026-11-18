package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main buttons and the messages within reach on a small screen: landscape (a phone's 360 dp tall), 360 dp wide, and
 * text at 1.3× – the editor's rows, Add › Title and Add › Captions › Write with their buttons, a refusal shown in the
 * sheet. (The export dialog isn't here: any text field in a dialog at 360 dp never lets Robolectric's Compose go idle –
 * a bare BasicTextField in an AlertDialog does the same – so it is checked on the phones.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutReachTest {
    @get:Rule val rule = createComposeRule()

    @Before fun largeText() = RuntimeEnvironment.setFontScale(1.3f)

    private val toaster = Toaster()

    /** Shown, tappable and inside its window (sheets and dialogs are windows of their own). */
    private fun reach(n: SemanticsNodeInteraction, what: String, tap: Boolean = true) {
        runCatching { n.performScrollTo() }
        n.assertIsDisplayed()
        if (tap) n.assertHasClickAction()
        val node = n.fetchSemanticsNode()
        var root = node
        while (root.parent != null) root = root.parent!!
        val b = node.boundsInWindow
        val w = root.boundsInWindow
        assertTrue("$what inside its window: $b in $w", b.left >= w.left - 1f && b.top >= w.top - 1f && b.right <= w.right + 1f && b.bottom <= w.bottom + 1f)
    }

    private fun msg(text: String) = rule.onNode(hasTestTag("message") and hasText(text), useUnmergedTree = true)

    private fun sheets(name: String) {
        val fake = FakeProps().apply { titleRefusal = "Inget ledigt videospår ovanför bilden under titelns 5 s."; onTitleMessage = { toaster.show(it) } }
        val st = EditorState().apply { props = fake; playhead = 2f }
        var sheet = 0
        val open = androidx.compose.runtime.mutableIntStateOf(1)
        rule.setContent {
            KlippTheme {
                CompositionLocalProvider(LocalToaster provides toaster) {
                    Box(Modifier.fillMaxSize().background(K.Bg)) {
                        when (open.intValue) {
                            1 -> TitleSheet(st) { open.intValue = 0 }
                            2 -> CaptionsSheet(st) { open.intValue = 0 }
                        }
                        MessageHost(toaster = toaster, layer = 0)
                    }
                }
            }
        }
        rule.waitForIdle()
        reach(rule.onNodeWithTag("title:add"), "$name: Add title")
        rule.onNodeWithTag("title:add").performClick()
        rule.waitForIdle()
        reach(msg("Inget ledigt videospår ovanför bilden under titelns 5 s."), "$name: the refusal in the title sheet", tap = false)
        reach(rule.onNodeWithTag("title:add"), "$name: Add title, after the refusal")
        rule.onAllNodes(isRoot()).fetchSemanticsNodes().size.let { sheet = it }
        rule.onNode(isRoot().and(androidx.compose.ui.test.hasAnyDescendant(hasTestTag("titleSheet")))).captureRoboImage("build/shots/96_${name}_titel.png")
        rule.runOnUiThread { open.intValue = 2 }
        rule.waitForIdle()
        rule.onNodeWithTag("capsPart:write").performClick()
        rule.waitForIdle()
        reach(rule.onNodeWithTag("captionWriteText"), "$name: the line's text field")
        reach(rule.onNodeWithTag("captionWriteSlot"), "$name: where the line goes", tap = false)
        reach(rule.onNodeWithTag("captionWriteAdd"), "$name: Add the written line")
        rule.runOnUiThread { toaster.show("Det finns redan en undertext vid spelhuvudet") }
        rule.waitForIdle()
        reach(msg("Det finns redan en undertext vid spelhuvudet"), "$name: a message in the captions sheet", tap = false)
        rule.onNode(isRoot().and(androidx.compose.ui.test.hasAnyDescendant(hasTestTag("captionsSheet")))).captureRoboImage("build/shots/97_${name}_undertexter.png")
        assertTrue(sheet >= 1)
    }

    @Test @Config(sdk = [35], qualifiers = "sv-w800dp-h360dp-land-xxhdpi")
    fun sheetsInLandscape() = sheets("liggande")

    @Test @Config(sdk = [35], qualifiers = "sv-w360dp-h740dp-xxhdpi")
    fun sheetsAt360() = sheets("360")

    @Test @Config(sdk = [35], qualifiers = "sv-w800dp-h360dp-land-xxhdpi")
    fun theEditorInLandscape() {
        val st = EditorState().apply { props = FakeProps() }
        rule.setContent { KlippTheme { KlippApp(st, AppUi(), toaster = toaster) } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/99_liggande_redigera.png")
        for (t in listOf("add:media", "add:sound", "add:title", "add:captions", "add:voice", "modeTab:0", "modeTab:1", "modeTab:2", "tool:menu", "undo", "appSettings"))
            reach(rule.onNodeWithTag(t), "landscape: $t")
        rule.runOnUiThread { toaster.show("Spåret är låst") }
        rule.waitForIdle()
        reach(msg("Spåret är låst"), "landscape: a message in the editor", tap = false)
    }

    @Test @Config(sdk = [35], qualifiers = "sv-w800dp-h360dp-land-xxhdpi")
    fun theEditRowInLandscapeWithTheTranscriptSwitch() {
        // the edit page has the right 54 % in landscape (the switch is there when a project is open)
        val st = EditorState()
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { Box(Modifier.align(androidx.compose.ui.Alignment.TopEnd).fillMaxWidth(0.54f).fillMaxSize()) {
            EditPage(st, Tool.SELECT, {}, Toaster(), transcript = { m -> Box(m.testTag("fakeTranscript")) })
        } } } }
        rule.waitForIdle()
        for (t in listOf("tool:menu", "view:transcript", "mode:magnet", "mode:link", "marker:add", "modes:menu", "appSettings")) reach(rule.onNodeWithTag(t), "landscape edit row: $t")
    }
}
