package se.gendut.klipp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A slider whose live value is fed back as its value (color mode: the node is shown live while dragging) must
 * still save on release – otherwise the change looks applied but is never saved or exported.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w400dp-h800dp-xxhdpi")
class PropSliderCommitTest {
    @get:Rule val rule = createComposeRule()

    @Test fun commitsWhenLiveValueFeedsBack() {
        var value by mutableStateOf(1f)
        val commits = mutableListOf<Float>()
        rule.setContent {
            KlippTheme {
                PropSlider("Mättnad", value, 0f, 2f, 1f, { "%.2f".format(it) }, "sat", onLive = { value = it }) { commits += it; value = it }
            }
        }
        rule.onNodeWithTag("prop:sat").performTouchInput {
            down(Offset(width * 0.5f, height / 2f))
            for (i in 1..10) moveTo(Offset(width * (0.5f - 0.045f * i), height / 2f))
            up()
        }
        rule.waitForIdle()
        assertEquals(1, commits.size)
        assertTrue("${commits[0]}", commits[0] < 0.2f)
    }
}
