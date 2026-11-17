package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The basic flows on a narrow phone with larger text (1.3×): every control there, inside the screen, tappable. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w360dp-h792dp-xxhdpi")
class LargeTextTest {
    @get:Rule val rule = createComposeRule()

    private fun large(content: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.3f)) { KlippTheme { content() } }
    }

    private fun inside(tag: String) {
        val row = rule.onRoot().getUnclippedBoundsInRoot()
        val b = rule.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction().getUnclippedBoundsInRoot()
        assertTrue("$tag inside the screen", b.left >= row.left && b.right <= row.right)
    }

    @Test fun theEditRowAndTheAddRow() {
        val st = EditorState().apply { props = FakeProps() }
        large { KlippApp(st, AppUi()) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/90_stor_text_redigera.png")
        for (t in listOf("tool:menu", "mode:magnet", "mode:link", "marker:add", "modes:menu", "appSettings", "add:media", "add:sound", "add:title", "add:captions", "add:voice", "modeTab:0", "modeTab:1", "modeTab:2")) inside(t)
    }

    @Test fun theTranscriptSwitchWithLargeText() {
        val st = EditorState()
        large { Box(Modifier.fillMaxSize().background(K.Bg)) { EditPage(st, Tool.SELECT, {}, Toaster(), transcript = { m -> Box(m.testTag("fakeTranscript")) }) } }
        rule.waitForIdle()
        for (t in listOf("tool:menu", "view:transcript", "mode:magnet", "mode:link", "marker:add", "modes:menu", "appSettings")) inside(t)
    }

    @Test fun theCaptionEditorWithLargeText() {
        val fake = FakeProps()
        val st = EditorState().apply {
            props = fake; playhead = 1f; captions = listOf(UiCaption(1, 0.5f, 2f, "Första raden")); selectedCaption = 1L; editingCaption = true
        }
        large { Box(Modifier.fillMaxSize().background(K.Bg)) { CaptionInspector(st) } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/91_stor_text_undertext.png")
        rule.onNodeWithTag("captionText").assertIsDisplayed()
        for (t in listOf("captionSetStart", "captionSetEnd")) { rule.onNodeWithTag(t).performScrollTo(); inside(t) }
    }
}
