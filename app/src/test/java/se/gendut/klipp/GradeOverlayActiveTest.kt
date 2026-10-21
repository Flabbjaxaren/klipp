package se.gendut.klipp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.Window

/** The color page stays composed next to the edit page – its window/key must still not show there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GradeOverlayActiveTest {
    @get:Rule val rule = createComposeRule()

    @Test fun windowAndKeyOnlyWhileColorPageShown() {
        val gs = GradeState()
        gs.set(7L, ClipGrade(listOf(GradeNode(1, "A", win = Window(shape = 1)))))
        gs.windowEdit = 7L to 1
        gs.showKey = 7L to 1
        rule.setContent { KlippTheme { Box(Modifier.size(200.dp)) { GradeOverlay(gs) } } }
        rule.onNodeWithTag("windowOverlay").assertExists()
        assertEquals(7L to 1, gs.shownKey)

        gs.active = false
        rule.waitForIdle()
        rule.onNodeWithTag("windowOverlay").assertDoesNotExist()
        assertNull(gs.shownKey)

        // back in color mode: the same tool as when you left
        gs.active = true
        rule.waitForIdle()
        rule.onNodeWithTag("windowOverlay").assertExists()
    }
}
