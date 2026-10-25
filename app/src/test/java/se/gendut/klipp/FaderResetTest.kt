package se.gendut.klipp

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mixer's faders: a double tap goes back to 0 dB (and is saved there); a single tap changes nothing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class FaderResetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun doubleTapGoesBackToZeroDb() {
        val mix = AudioMix()
        val commits = mutableListOf<Double>()
        mix.onCommit = { i -> if (i == 0) commits += mix.db(0) }
        rule.setContent { KlippTheme { AudioPanel(mix, EditorState()) } }
        rule.waitForIdle()
        rule.runOnIdle { runBlocking { mix.gains[0].snapTo(0.3f) } }
        val fader = rule.onNodeWithTag("fader:A1").performScrollTo()
        fader.performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("one tap: unchanged", 0.3f, mix.gains[0].value, 1e-4f)
        assertEquals(0, commits.size)
        rule.mainClock.advanceTimeBy(1000)
        fader.performTouchInput { click(center); advanceEventTime(120); click(center) }
        rule.waitForIdle()
        assertEquals(0.75f, mix.gains[0].value, 1e-3f)
        assertEquals(1, commits.size)
        assertEquals(0.0, commits[0], 0.01)
    }
}
