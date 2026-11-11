package se.gendut.klipp

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings → Help: every gesture with its button, and the tips can be shown again. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class SettingsHelpTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theGesturesAreListedAndTheTipsComeBack() {
        val c = RuntimeEnvironment.getApplication()
        for ((k, _) in TIPS) AppPrefs.setTipSeen(c, k)
        rule.setContent { KlippTheme { SettingsScreen(onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("set:cat:help").performScrollTo().performClick()
        rule.waitForIdle()
        for ((k, _) in TIPS) rule.onNodeWithTag("help:$k").performScrollTo()
        rule.onNodeWithTag("set:showTips").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("set:tipsBack").assertExists()
        assertFalse(TIPS.any { AppPrefs.tipSeen(c, it.first) })
        assertTrue(TipsState.revision > 0)
    }
}
