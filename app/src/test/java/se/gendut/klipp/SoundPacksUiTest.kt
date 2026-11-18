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

/** Packs without a file on Klipp's release: no download button, and the sheet says why (in Swedish here). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class SoundPacksUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun unavailablePacksSayWhyAndOfferNoDownload() {
        rule.setContent { KlippTheme { SoundFxSheet(onDismiss = {}, onAdd = { _, _, _ -> }) } }
        rule.waitForIdle()
        rule.onNodeWithTag("sfx:moving").assertExists()
        rule.onNodeWithText("Vissa paket går inte att hämta just nu", substring = true).assertExists()
        val first = SoundFx.PACKS.first { it.hosted == null }
        rule.onNodeWithTag("sfxUnavailable:${first.id}", useUnmergedTree = true).assertExists()
        assertEquals(0, rule.onAllNodesWithTag("sfxDownload:${first.id}", useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
