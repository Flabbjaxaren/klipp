package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.ChromaKey
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.LayerFx

/**
 * Copying a grade between clips follows one rule wherever it is done (the wheels' button, the grid's Copy/Paste, a
 * still's grade): the color carries over, the receiving clip keeps its own picture settings (Edit › Picture), and
 * pasting back onto the copied clip gives it its grade back whole.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ColorCopyTest {
    @get:Rule val rule = createComposeRule()

    private val clips = listOf(ColorClip(1, "A003_intervju", 0f, 8f, null, 0.0, 2), ColorClip(2, "B-roll_hamn", 8f, 13f, null, 0.0, 2))
    private val keyA = LayerFx(key = ChromaKey(tolerance = 0.3f))
    private val keyB = LayerFx(key = ChromaKey(tolerance = 0.5f))
    private val gradeA = ClipGrade(nodes = listOf(GradeNode(1, "Primär", sat = 1.4f)), layer = keyA)

    @Test fun pasteCarriesTheColorAndKeepsThePicture() {
        val gs = GradeState()
        gs.set(1L, gradeA, record = false)
        gs.set(2L, ClipGrade(layer = keyB), record = false)
        gs.copyGrade(1L)
        gs.pasteGrade(2L)
        assertEquals(gradeA.nodes, gs.of(2L).nodes)
        assertSame(keyB, gs.of(2L).layer)
        // back onto the copied clip: its grade whole
        gs.set(1L, ClipGrade(), record = false)
        gs.pasteGrade(1L)
        assertEquals(gradeA, gs.of(1L))
        // a still's grade: the same rule
        gs.set(2L, ClipGrade(layer = keyB), record = false)
        gs.applyGrade(2L, gradeA)
        assertEquals(gradeA.nodes, gs.of(2L).nodes)
        assertSame(keyB, gs.of(2L).layer)
    }

    @Test fun theWheelsButtonPastesByTheSameRule() {
        fun run(useGrid: Boolean): ClipGrade {
            val gs = GradeState()
            gs.set(1L, gradeA, record = false)
            gs.set(2L, ClipGrade(layer = keyB), record = false)
            val st = EditorState().apply { playhead = 2f; selected = 1L }
            rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { ColorPage(st, gs, clips) { Text("tidslinje") } } } }
            rule.waitForIdle()
            if (useGrid) { rule.onNodeWithTag("colorMore").performClick(); rule.waitForIdle(); rule.onNodeWithTag("lower:grade").performClick(); rule.waitForIdle(); rule.onNodeWithTag("grade:copy").performClick() }
            else rule.onNodeWithTag("wheels:copy").performClick()
            rule.waitForIdle()
            rule.runOnUiThread { st.selected = 2L; st.playhead = 9f }
            rule.waitForIdle()
            if (useGrid) rule.onNodeWithTag("grade:paste").performClick() else rule.onNodeWithTag("wheels:copy").performClick()
            rule.waitForIdle()
            return gs.of(2L)
        }
        val wheels = run(useGrid = false)
        assertEquals(gradeA.nodes, wheels.nodes)
        assertSame(keyB, wheels.layer)
    }

    @Test fun theGridPastesByTheSameRule() {
        val gs = GradeState()
        gs.set(1L, gradeA, record = false)
        gs.set(2L, ClipGrade(layer = keyB), record = false)
        val st = EditorState().apply { playhead = 2f; selected = 1L }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { ColorPage(st, gs, clips) { Text("tidslinje") } } } }
        rule.waitForIdle()
        rule.onNodeWithTag("colorMore").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("lower:grade").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("grade:copy").performClick(); rule.waitForIdle()
        rule.runOnUiThread { st.selected = 2L; st.playhead = 9f }
        rule.waitForIdle()
        rule.onNodeWithTag("grade:paste").performClick(); rule.waitForIdle()
        assertEquals(gradeA.nodes, gs.of(2L).nodes)
        assertSame(keyB, gs.of(2L).layer)
    }
}
