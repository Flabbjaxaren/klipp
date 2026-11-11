package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The color page's look: the lower part (gallery, actions) and the tool tabs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ColorPageLookTest {
    @get:Rule val rule = createComposeRule()

    private val two = listOf(ColorClip(1, "A003_intervju", 0f, 8f, null, 0.0, 2), ColorClip(2, "B-roll_hamn", 8f, 13f, null, 0.0, 2))

    private fun screen(st: EditorState, gs: GradeState, clips: List<ColorClip> = two) {
        rule.setContent { KlippTheme { ColorScreen(st, gs, clips) } }
        rule.waitForIdle()
    }

    /** The page as in the design: clips, nodes, the wheel with its ranges, and the picture's tools over the picture. */
    @Test fun thePageWithItsWheel() {
        val st = EditorState().apply { playhead = 2f; selected = 1L }
        val gs = GradeState()
        gs.set(1, se.gendut.klipp.engine.ClipGrade(listOf(
            se.gendut.klipp.engine.GradeNode(1, "Bas"),
            se.gendut.klipp.engine.GradeNode(2, "Hud", lift = se.gendut.klipp.engine.Wheel(-0.2f, -0.15f, 0f), gamma = se.gendut.klipp.engine.Wheel(0.35f, 0.3f, 0.08f), gain = se.gendut.klipp.engine.Wheel(0.1f, 0.25f, 0f)),
            se.gendut.klipp.engine.GradeNode(3, "Himmel"),
        )), record = false)
        screen(st, gs, two + ColorClip(3, "C", 13f, 20f, null, 0.0, 2) + ColorClip(4, "D", 20f, 26f, null, 0.0, 2) + ColorClip(5, "E", 26f, 30f, null, 0.0, 2))
        rule.onNodeWithTag("node:2").performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.onRoot().captureRoboImage("build/shots/43_farg_hjul.png")
        // the picture's tools are over the picture, the page's rows under the transport
        for (tag in listOf("colorCompare", "scopes", "colorFullscreen", "colorClip:1", "addNode", "colorMore", "wheels:0", "wheels:reset", "range:Lift", "range:Offset", "wheels:wb", "wheels:copy")) rule.onNodeWithTag(tag).assertExists()
        // Log: the log wheels and their limits
        rule.onNodeWithTag("wheels:1").performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("wheel:LogMid").assertExists()
        rule.onNodeWithTag("prop:logLow").assertExists()
        rule.onRoot().captureRoboImage("build/shots/44_farg_log.png")
        // the reset button takes all of the mode's wheels in one step
        rule.onNodeWithTag("wheels:0").performClick()
        rule.onNodeWithTag("wheels:reset").performClick()
        rule.waitForIdle()
        val n = gs.of(1).nodes[1]
        assertTrue(n.lift.isNeutral && n.gamma.isNeutral && n.gain.isNeutral)
    }

    /** On the narrowest phone the wheel gives way to its ranges' names, and the tools end above undo and redo. */
    @Config(sdk = [35], qualifiers = "sv-w360dp-h792dp-xxhdpi")
    @Test fun thePageOnANarrowPhone() {
        val st = EditorState().apply { playhead = 2f; selected = 1L }
        val gs = GradeState()
        screen(st, gs)
        rule.mainClock.advanceTimeBy(1000)
        rule.onRoot().captureRoboImage("build/shots/47_farg_hjul_smal.png")
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val undo = rule.onNodeWithTag("undoRedo").assertIsDisplayed().getUnclippedBoundsInRoot()
        for (r in listOf("Lift", "Gamma", "Gain", "Offset")) {
            val b = rule.onNodeWithTag("range:$r").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("the range $r is whole on the screen", b.right <= root.right - 8.dp && b.right - b.left >= 90.dp)
        }
        // primaries are named Lift / Gamma / Gain; the log wheels by their tonal ranges
        rule.onNodeWithTag("range:Lift").assertTextContains("Lift")
        rule.onNodeWithTag("wheels:1").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("range:LogShadow").assertTextContains("Skuggor")
        rule.onNodeWithTag("wheels:0").performClick()
        rule.mainClock.advanceTimeBy(500)
        for (tool in listOf("Hjul", "Kurvor", "Justera", "LUT", "Kvalificerare", "Fönster", "Film")) {
            val b = rule.onNodeWithTag("editor:$tool").getBoundsInRoot()
            assertTrue("the tool $tool ends above undo and redo", b.bottom <= undo.top)
        }
        // every tool there, each from its top
        for ((i, tool) in listOf("Kurvor", "Justera", "LUT", "Kvalificerare", "Fönster", "Film").withIndex()) {
            rule.onNodeWithTag("editor:$tool").performScrollTo().performClick()
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(1000)
            rule.onRoot().captureRoboImage("build/shots/47_farg_smal_${i + 1}.png")
        }
        rule.onNodeWithTag("colorMore").performClick(); rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/47_farg_smal_galleri.png")
    }

    @Test fun lowerPartAndScopes() {
        val st = EditorState().apply { playhead = 2f; selected = null }
        val gs = GradeState()
        screen(st, gs)
        rule.onNodeWithTag("scopes").performClick()
        rule.waitForIdle()
        captureScreenRoboImage("build/shots/42_farg_scopesmeny.png")
        rule.onNodeWithTag("scope:1").performClick()
        rule.waitForIdle()
        assertEquals(1, gs.scopeKind)
        rule.onNodeWithTag("scope").assertExists()
        rule.onNodeWithTag("editor:Justera").performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.onRoot().captureRoboImage("build/shots/40_farg_justera.png")
        // the gallery and the clip's grade are behind the grid button
        rule.onNodeWithTag("colorMore").performClick(); rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/45_farg_galleri.png")
        rule.onNodeWithTag("lower:grade").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("grade:reset").assertExists()
        rule.onRoot().captureRoboImage("build/shots/41_farg_nederst.png")
        rule.onNodeWithTag("sheet:done").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("prop:contrast").assertExists()
    }

    /** Compare splits the picture against the clip without its grade; the picture's scrub gives way to the line. */
    @Test fun compareIsTurnedOnAndOffOverThePicture() {
        val st = EditorState().apply { playhead = 2f; selected = 1L }
        val gs = GradeState()
        screen(st, gs)
        rule.onNodeWithTag("colorCompare").performClick(); rule.waitForIdle()
        assertEquals(Still.ORIGINAL, gs.compare?.id)
        rule.onRoot().captureRoboImage("build/shots/46_farg_jamfor.png")
        rule.onNodeWithTag("colorCompare").performClick(); rule.waitForIdle()
        assertEquals(null, gs.compare)
    }

    /** Dragging the picture scrubs inside the clip being graded: whole frames, never into the next clip. */
    @Test fun thePictureScrubsInsideTheClip() {
        val st = EditorState().apply { playhead = 9f; selected = 2L; playing = true }
        val gs = GradeState()
        screen(st, gs)
        rule.onNodeWithTag("monitorArea").performTouchInput {
            down(Offset(width * 0.3f, height * 0.4f))
            for (i in 1..10) moveTo(Offset(width * (0.3f + 0.025f * i), height * 0.4f))
            up()
        }
        rule.waitForIdle()
        assertFalse(st.playing)
        assertTrue("forward inside the clip (${st.playhead})", st.playhead > 9.5f && st.playhead < 13f)
        assertEquals(0f, (st.playhead * FPS) - Math.round(st.playhead * FPS), 1e-3f)
        repeat(3) {
            rule.onNodeWithTag("monitorArea").performTouchInput {
                down(Offset(width * 0.1f, height * 0.4f))
                for (i in 1..10) moveTo(Offset(width * (0.1f + 0.08f * i), height * 0.4f))
                up()
            }
        }
        rule.waitForIdle()
        assertEquals("the last frame of the clip, not the next clip", 13f - 1f / FPS, st.playhead, 1e-3f)
        repeat(3) {
            rule.onNodeWithTag("monitorArea").performTouchInput {
                down(Offset(width * 0.9f, height * 0.4f))
                for (i in 1..10) moveTo(Offset(width * (0.9f - 0.08f * i), height * 0.4f))
                up()
            }
        }
        rule.waitForIdle()
        assertEquals(8f, st.playhead, 1e-3f)
    }

    /** The playhead under the Window tool: drag through the clip, whole frames, never outside the clip. */
    @Test fun scrubberMovesPlayheadWithinClip() {
        val st = EditorState().apply { playhead = 2f; selected = 2L; playing = true }
        val gs = GradeState().apply { editor = 5 }
        val clips = listOf(ColorClip(1, "A", 0f, 8f, null, 0.0, 2), ColorClip(2, "B", 8f, 13f, null, 0.0, 2))
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { ColorPage(st, gs, clips) { Text("tidslinje") } } } }
        rule.waitForIdle()
        rule.onNodeWithTag("scrub").performTouchInput {
            down(Offset(width * 0.1f, height / 2f))
            moveTo(Offset(width * 0.5f, height / 2f))
        }
        rule.waitForIdle()
        assertFalse(st.playing)
        assertEquals(10.5f, st.playhead, 0.05f)
        // whole frames
        assertEquals(0f, (st.playhead * FPS) - Math.round(st.playhead * FPS), 1e-3f)
        rule.onNodeWithTag("scrub").performTouchInput {
            moveTo(Offset(width * 1.5f, height / 2f))
            up()
        }
        rule.waitForIdle()
        // the last frame of the clip, not the next clip
        assertEquals(13f - 1f / FPS, st.playhead, 1e-3f)
    }
}
