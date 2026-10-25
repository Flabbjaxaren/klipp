package se.gendut.klipp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mode row at the bottom: drag along it and the mode steps (not following the finger smoothly); taps still pick. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ModeSwipeTest {
    @get:Rule val rule = createComposeRule()

    private fun setUp(): AppUi {
        val ui = AppUi()
        rule.setContent { KlippTheme { KlippApp(EditorState(), ui) } }
        rule.waitForIdle()
        return ui
    }

    /** Slowly from Edit to over Audio: lands on Audio, and back again to Edit. */
    @Test fun dragStepsToTheModeUnderTheFinger() {
        val ui = setUp()
        rule.onNodeWithTag("modes").performTouchInput {
            val item = width / 4f
            val from = Offset(item * 0.5f, height / 2f)
            down(from)
            for (i in 1..20) { advanceEventTime(30); moveTo(Offset(from.x + item * 2f * i / 20, from.y)) }
            advanceEventTime(300)
            up()
        }
        rule.waitForIdle()
        assertEquals(2f, ui.page.value, 0.01f)
        rule.onNodeWithTag("modes").performTouchInput {
            val item = width / 4f
            val from = Offset(item * 2.5f, height / 2f)
            down(from)
            for (i in 1..20) { advanceEventTime(30); moveTo(Offset(from.x - item * 2.2f * i / 20, from.y)) }
            advanceEventTime(300)
            up()
        }
        rule.waitForIdle()
        assertEquals(0f, ui.page.value, 0.01f)
    }

    /** Held a bit past the slop, short of the next mode's place: the indicator stays where it is, it does not creep. */
    @Test fun aSlowDragShortOfTheNextModeStays() {
        val ui = setUp()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("modes").performTouchInput {
            val item = width / 4f
            val from = Offset(item * 0.5f, height / 2f)
            down(from)
            for (i in 1..20) { advanceEventTime(30); moveTo(Offset(from.x + viewConfiguration.touchSlop + item * 0.4f * i / 20, from.y)) }
        }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(0f, ui.page.value, 0f)
        rule.onNodeWithTag("modes").performTouchInput { advanceEventTime(300); up() }
        rule.mainClock.advanceTimeBy(1500)
        assertEquals(0f, ui.page.value, 0f)
    }

    /** A quick flick a third of a mode on is enough to go to the next one. */
    @Test fun flickGoesOneOn() {
        val ui = setUp()
        rule.onNodeWithTag("modes").performTouchInput {
            val item = width / 4f
            val from = Offset(item * 0.5f, height / 2f)
            down(from)
            for (i in 1..4) { advanceEventTime(10); moveTo(Offset(from.x + item * 0.35f * i / 4, from.y)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(1f, ui.page.value, 0.01f)
    }

    @Test fun tapStillPicks() {
        val ui = setUp()
        rule.onNodeWithText("Ljud").performClick()
        rule.waitForIdle()
        assertEquals(2f, ui.page.value, 0.01f)
    }
}
