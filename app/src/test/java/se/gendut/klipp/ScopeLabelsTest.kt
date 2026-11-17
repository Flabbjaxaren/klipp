package se.gendut.klipp

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a control applies to is said where it is: the renders' app-wide settings, the mixer's faders. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ScopeLabelsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theRenderDefaultsAreTheAppsAndSaySo() {
        rule.setContent { KlippTheme { SettingsScreen(onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("set:cat:playback").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("set:renderAuto").performScrollTo()
        rule.onNodeWithText("Rendera automatiskt (alla projekt)").assertExists()
        rule.onNodeWithText("Ta bort renderingar (alla projekt)").performScrollTo()
    }

    @Test fun theMixerSaysWhatItsFadersAre() {
        rule.setContent { KlippTheme { AudioPanel(AudioMix(), EditorState()) } }
        rule.waitForIdle()
        rule.onNodeWithTag("mixer:scope").performScrollTo()
        rule.onNodeWithText("A1–A3: spårvolym (hela spåret). Mix: hela ljudet. Ett klipps egen volym ställs på klippet i Redigera.").assertExists()
    }
}
