package se.gendut.klipp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A button answers the finger as it lands – not only once it lifts – and is back to full size after. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PressPopTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var scale: Animatable<Float, *>

    private fun button() = rule.setContent {
        val src = remember { MutableInteractionSource() }
        scale = rememberPressScale(src, 0.85f)
        Box(Modifier.size(48.dp).testTag("b").clickable(interactionSource = src, indication = null) {})
    }

    @Test fun shrinksWhileHeld() {
        button()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("b").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(80)
        assertTrue("held: ${scale.value}", scale.value < 0.9f)
        rule.onNodeWithTag("b").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1500)
        assertEquals(1f, scale.value, 1e-3f)
    }

    /** Down and up in the same instant: the shrink never got going, the pop still shows. */
    @Test fun quickTapStillPops() {
        button()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("b").performTouchInput { down(center); up() }
        rule.mainClock.advanceTimeBy(32)
        assertTrue("tapped: ${scale.value}", scale.value < 0.95f)
        rule.mainClock.advanceTimeBy(1500)
        assertEquals(1f, scale.value, 1e-3f)
    }
}
