package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ColorPageTest {
    @get:Rule val rule = createComposeRule()
    private val dir = "build/shots"
    private val clips = listOf(
        ColorClip(1, "A003_intervju", 0f, 8f, null, 0.0, 2),
        ColorClip(2, "B-roll_hamn", 8f, 13f, null, 0.0, 2),
    )

    private fun setUp(): Pair<EditorState, GradeState> {
        val st = EditorState().apply { playhead = 2f; selected = null }
        val gs = GradeState()
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { ColorPage(st, gs, clips) { Text("tidslinje") } } } }
        rule.waitForIdle()
        return st to gs
    }

    /** The Timeline tab (the look with LUT) inside color mode – crashed with nested scrolling in 0.13. */
    @Test fun timelineTabShowsLookAndLutImport() {
        val st = EditorState().apply { playhead = 2f; selected = null }
        val gs = GradeState()
        var imported = 0
        rule.setContent {
            KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { ColorPage(st, gs, clips) { ColorPanel(ColorGrade(), { imported++ }, scroll = false) } } }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Tidslinje").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("+ Importera .cube").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, imported)
        rule.onRoot().captureRoboImage("$dir/36_farg_tidslinje.png")
    }

    /** A LUT in a node on just one clip, qualifier and window via the tabs – and that they are cleared when the page is left. */
    @Test fun nodeLutQualifierWindowTabs() {
        val (_, gs) = setUp()
        rule.onNodeWithTag("editor:LUT").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("nodeLut:${se.gendut.klipp.engine.LutStore.TEAL_ORANGE}").performScrollTo().performClick()
        // the LUT is read on a background thread before it is put into the node (never on the main thread)
        rule.waitUntil(5000) { gs.of(1).nodes[0].lut == se.gendut.klipp.engine.LutStore.TEAL_ORANGE }
        rule.waitForIdle()
        assertEquals(se.gendut.klipp.engine.LutStore.TEAL_ORANGE, gs.of(1).nodes[0].lut)
        assertEquals("only clip 1 got the LUT", null, gs.grades[2L])
        rule.onRoot().captureRoboImage("$dir/37_farg_nodlut.png")
        rule.onNodeWithTag("editor:Kvalificerare").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("qual:on").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(gs.of(1).nodes[0].qual.on)
        assertEquals("the LUT is still there", se.gendut.klipp.engine.LutStore.TEAL_ORANGE, gs.of(1).nodes[0].lut)
        rule.onNodeWithTag("qual:key").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1L to 1, gs.showKey)
        rule.onRoot().captureRoboImage("$dir/38_farg_kvalificerare.png")
        rule.onNodeWithTag("editor:Fönster").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("win:1").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, gs.of(1).nodes[0].win.shape)
        assertEquals("the window can be dragged in the frame", 1L to 1, gs.windowEdit)
        assertTrue("the qualifier is still there", gs.of(1).nodes[0].qual.on)
        rule.onRoot().captureRoboImage("$dir/39_farg_fonster.png")
        rule.onNodeWithTag("editor:Hjul").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("window editing off outside the tab", null, gs.windowEdit)
        rule.onNodeWithText("Tidslinje").performClick()
        rule.waitForIdle()
        assertEquals(null, gs.windowEdit)
    }

    @Test fun wheelDragIsLiveThenCommitted() {
        val (_, gs) = setUp()
        rule.onRoot().captureRoboImage("$dir/32_farg.png")
        rule.onNodeWithTag("wheel:Gain").performScrollTo().performTouchInput {
            down(center)
            for (i in 1..10) moveTo(Offset(center.x + width * 0.04f * i, center.y))
        }
        rule.waitForIdle()
        assertNotNull("live while dragging", gs.live)
        rule.onNodeWithTag("wheel:Gain").performTouchInput { up() }
        rule.waitForIdle()
        val g = gs.grades[1L]!!
        assertTrue("puck to the right = red gain", g.nodes[0].gain.rgb()[0] > 0.2f)
        rule.onRoot().captureRoboImage("$dir/33_farg_gain.png")
    }

    @Test fun nodesCurvesCopyPaste() {
        val (st, gs) = setUp()
        rule.onNodeWithTag("addNode").performClick()
        rule.waitForIdle()
        assertEquals(2, gs.of(1).nodes.size)
        rule.onNodeWithTag("editor:Kurvor").performClick()
        rule.waitForIdle()
        // press in the middle of the curve box, drag up = brighter midtones
        rule.onNodeWithTag("curve").performScrollTo().performTouchInput {
            down(center)
            for (i in 1..10) moveTo(Offset(center.x, center.y - height * 0.015f * i))
            up()
        }
        rule.waitForIdle()
        val node = gs.of(1).nodes.last()
        assertTrue("the curve got a point", node.curveY.points.size == 3)
        assertTrue(node.curveY.table()[128] > 0.6f)
        rule.onRoot().captureRoboImage("$dir/34_kurvor.png")
        rule.onNodeWithTag("node:1").performScrollTo().performClick()
        // copy to the next clip (the page scrolled up to the clip row)
        rule.onNodeWithTag("colorScroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -10_000f) }
        rule.waitForIdle()
        rule.onNodeWithTag("colorClip:1").performClick()
        rule.onNodeWithTag("colorClip:2").performClick()
        assertEquals(2L, st.selected)
    }
}
