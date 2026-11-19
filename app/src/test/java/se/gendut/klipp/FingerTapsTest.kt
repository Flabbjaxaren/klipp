package se.gendut.klipp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two- and three-finger taps undo and redo inside a sheet (a window of its own), as on the edit page. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class FingerTapsTest {
    @get:Rule val rule = createComposeRule()

    private var undos = 0
    private var redos = 0

    private fun sheet() {
        rule.setContent {
            CompositionLocalProvider(LocalUndoRedo provides UndoRedo({ undos++ }, { redos++ })) {
                ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Box(Modifier.fillMaxWidth().height(400.dp).fingerTaps().testTag("sheet"))
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tap(fingers: Int, move: Float = 0f) = rule.onNodeWithTag("sheet").performTouchInput {
        for (i in 0 until fingers) down(i, center + Offset(i * 120f - 120f, 0f))
        if (move != 0f) for (s in 1..5) for (i in 0 until fingers) moveTo(i, center + Offset(i * 120f - 120f, move * s / 5))
        advanceEventTime(60)
        for (i in 0 until fingers) up(i)
    }

    @Test
    fun twoFingersUndoThreeRedo() {
        sheet()
        tap(2)
        rule.waitForIdle()
        assertEquals(1, undos)
        tap(3)
        rule.waitForIdle()
        assertEquals(1, redos)
        assertEquals(1, undos)
    }

    @Test
    fun aTwoFingerScrollIsNoTap() {
        sheet()
        tap(2, move = 200f)
        rule.waitForIdle()
        assertEquals(0, undos)
        tap(1)
        rule.waitForIdle()
        assertEquals("one finger is no tap", 0, undos)
    }
}
