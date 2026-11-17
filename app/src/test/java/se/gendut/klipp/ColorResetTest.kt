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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.BlendMode
import se.gendut.klipp.engine.ChromaKey
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.LayerFx
import se.gendut.klipp.engine.LayerMask
import se.gendut.klipp.engine.MaskKey
import se.gendut.klipp.engine.MotionBlur
import se.gendut.klipp.engine.Stabilization
import se.gendut.klipp.engine.WindowTrack

/**
 * Color › Clip grade › Reset resets what the color page owns (the nodes) and nothing that Edit › Picture owns: keying,
 * mask, blend, crop (with their keyframes), stabilization and motion blur. It used to set an empty ClipGrade, which
 * silently removed all of those.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ColorResetTest {
    @get:Rule val rule = createComposeRule()

    private val path = WindowTrack(0.0, 0.04, FloatArray(50), FloatArray(50), FloatArray(50) { 1f }, FloatArray(50) { 1f }, smooth = 0f)
    private val picture = LayerFx(
        key = ChromaKey(tolerance = 0.4f),
        mask = LayerMask(cx = 0.3f, keys = listOf(MaskKey(0.0, 0.3f, 0.5f, 0.4f, 0.4f), MaskKey(1.0, 0.6f, 0.5f, 0.4f, 0.4f))),
        blend = BlendMode.entries.last(),
        crop = LayerMask(w = 0.8f, keys = listOf(MaskKey(0.5, 0.5f, 0.5f, 0.8f, 0.7f))),
    )
    private val stab = Stabilization(path, 0.8f, mode = 1, maxZoom = 1.3f)
    private val blur = MotionBlur(path, 120f)
    private val graded = ClipGrade(
        nodes = listOf(GradeNode(1, "Primär", sat = 1.4f, temp = 0.2f), GradeNode(2, "Look", contrast = 1.3f)),
        stab = stab, layer = picture, mblur = blur,
    )

    @Test fun resetKeepsWhatThePicturePageOwns() {
        val r = graded.colorReset()
        // the color page's part: one fresh primary node
        assertEquals(ClipGrade().nodes, r.nodes)
        // Edit › Picture's part: the same objects, keyframes included
        assertSame(picture, r.layer)
        assertEquals(2, r.layer!!.mask!!.keys.size)
        assertEquals(1, r.layer!!.crop!!.keys.size)
        assertSame(stab, r.stab)
        assertSame(blur, r.mblur)
        // nothing else in a clip grade
        assertEquals(ClipGrade(stab = stab, layer = picture, mblur = blur), r)
    }

    @Test fun theResetButtonIsOneUndoStepThatKeepsThePicture() {
        val st = EditorState().apply { playhead = 2f; selected = 1L }
        val gs = GradeState()
        val steps = ArrayList<Pair<() -> Unit, () -> Unit>>()
        gs.history = { _, undo, redo -> steps += undo to redo }
        gs.set(1L, graded, record = false)
        val clips = listOf(ColorClip(1, "A003_intervju", 0f, 8f, null, 0.0, 2))
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { ColorPage(st, gs, clips) { Text("tidslinje") } } } }
        rule.waitForIdle()
        rule.onNodeWithTag("colorMore").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("lower:grade").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("grade:reset").performClick()
        rule.waitForIdle()

        val after = gs.of(1L)
        assertEquals(ClipGrade().nodes, after.nodes)
        assertSame(picture, after.layer)
        assertSame(stab, after.stab)
        assertSame(blur, after.mblur)
        // one step, and undoing it brings the color back
        assertEquals(1, steps.size)
        rule.runOnUiThread { steps.single().first() }
        assertEquals(graded, gs.of(1L))
        rule.runOnUiThread { steps.single().second() }
        assertNotNull(gs.of(1L).layer)
        assertEquals(ClipGrade().nodes, gs.of(1L).nodes)
    }
}
