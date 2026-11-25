package se.gendut.klipp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings → About → Report a bug / Suggest a feature: the user sends it (GitHub or email); only the version and the
 * phone are filled in, and they are shown before anything is opened.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class FeedbackTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theInfoIsOnlyVersionAndPhone() {
        val info = Feedback.info("0.22.0-beta.3", 107, "0123456789abcdef", "16", 36, "vivo V2562")
        assertEquals("Klipp 0.22.0-beta.3 (build 107, 0123456789ab)\nAndroid 16 (API 36)\nvivo V2562", info)
    }

    @Test fun theGithubLinkOpensTheRightFormWithTheInfoFilledIn() {
        val url = Feedback.githubUrl(Feedback.Kind.BUG, "Klipp 1 (build 2, abc)\nAndroid 16")
        assertEquals("https://github.com/Flabbjaxaren/klipp/issues/new?template=bug_report.yml&environment=Klipp%201%20(build%202%2C%20abc)%0AAndroid%2016", url)
        assertTrue(Feedback.githubUrl(Feedback.Kind.IDEA, "x").contains("template=feature_request.yml"))
    }

    @Test fun theEmailGoesToKlippsAddress() {
        val i = Feedback.emailIntent("s", "b")
        assertEquals(android.content.Intent.ACTION_SENDTO, i.action)
        assertEquals("mailto:", i.dataString)
        assertEquals(listOf("klipp@gendutvideo.com"), i.getStringArrayExtra(android.content.Intent.EXTRA_EMAIL)!!.toList())
    }

    @Test fun aboutLeadsToTheBugPageAndEmailOpensAMailApp() {
        rule.setContent { KlippTheme { SettingsScreen(onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("set:cat:about").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("about:bug").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("feedback:info").assertIsDisplayed()
        rule.onNodeWithTag("feedback:github").assertIsDisplayed()
        rule.onNodeWithTag("feedback:email").performScrollTo().performClick()
        rule.waitForIdle()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_SENDTO, started?.action)
        assertTrue(started!!.getStringExtra(android.content.Intent.EXTRA_TEXT)!!.contains("Vad hände?"))
        assertTrue(started.getStringExtra(android.content.Intent.EXTRA_SUBJECT)!!.startsWith("Klipp "))
    }
}
