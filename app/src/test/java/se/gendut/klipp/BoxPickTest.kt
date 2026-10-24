package se.gendut.klipp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Drawing the box a title follows: a drag gives the box, a tap a square box around the point; the mode then ends. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w400dp-h800dp-xxhdpi")
class BoxPickTest {
    @get:Rule val rule = createComposeRule()

    @Test fun dragAndTap() {
        val st = EditorState()
        var got: FloatArray? = null
        st.boxPick = { cx, cy, rx, ry -> got = floatArrayOf(cx, cy, rx, ry) }
        rule.setContent { KlippTheme { Box(Modifier.size(320.dp, 180.dp)) { BoxPickOverlay(st) } } }
        rule.onNodeWithTag("boxPick").performTouchInput {
            down(Offset(width * 0.2f, height * 0.3f))
            for (i in 1..10) moveTo(Offset(width * (0.2f + 0.02f * i), height * (0.3f + 0.03f * i)))
            up()
        }
        rule.waitForIdle()
        val g = got!!
        assertEquals(0.3f, g[0], 0.01f); assertEquals(0.45f, g[1], 0.01f)
        assertEquals(0.1f, g[2], 0.01f); assertEquals(0.15f, g[3], 0.01f)
        assertNull("the mode ends", st.boxPick)
        // a tap: a box around the point, square on screen (16:9 frame → rx = ry · 9/16)
        st.boxPick = { cx, cy, rx, ry -> got = floatArrayOf(cx, cy, rx, ry) }
        rule.waitForIdle()
        rule.onNodeWithTag("boxPick").performTouchInput { click(Offset(width * 0.7f, height * 0.5f)) }
        rule.waitForIdle()
        val t = got!!
        assertEquals(0.7f, t[0], 0.01f); assertEquals(0.5f, t[1], 0.01f)
        assertEquals(0.08f, t[3], 1e-4f); assertEquals(0.08f * 9f / 16f, t[2], 0.002f)
    }
}
