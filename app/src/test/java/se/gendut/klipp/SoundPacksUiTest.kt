package se.gendut.klipp

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.SoundFx

/** The packs are in the app: each offers Install, and there is no note about packs that can't be had. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class SoundPacksUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun everyPackOffersInstall() {
        rule.setContent { KlippTheme { SoundFxSheet(onDismiss = {}, onAdd = { _, _, _ -> }) } }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("sfx:moving").fetchSemanticsNodes().size)
        rule.onNodeWithTag("sfxDownload:${SoundFx.PACKS.first().id}", useUnmergedTree = true).assertExists()
        assertEquals(0, rule.onAllNodesWithTag("sfxUnavailable:${SoundFx.PACKS.first().id}", useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
