package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.MotionBlur
import se.gendut.klipp.engine.Stabilization
import se.gendut.klipp.engine.WindowTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MotionBlurTest {
    /** The camera pans [perFrame] of the image width each frame. */
    private fun pan(perFrame: Float, n: Int = 60) = WindowTrack(0.0, 0.04, FloatArray(n) { it * perFrame }, FloatArray(n), FloatArray(n) { 1f }, FloatArray(n) { 1f }, 0f)

    @Test fun a180DegreeShutterSmearsHalfTheFramesMovement() {
        assertEquals(0.005f, MotionBlur(pan(0.01f)).at(1.0, null, 1.78f)[0], 1e-4f)
        assertEquals(0.01f, MotionBlur(pan(0.01f), 360f).at(1.0, null, 1.78f)[0], 1e-4f)
    }

    @Test fun aVeryFastPanIsCapped() {
        assertEquals(MotionBlur.MAX, MotionBlur(pan(0.5f), 360f).at(1.0, null, 1.78f)[0], 1e-4f)
    }

    @Test fun onATripodNoMovementIsLeft() {
        val p = pan(0.002f)
        val v = MotionBlur(p).at(1.0, Stabilization(p, 0.6f, mode = 1, maxZoom = 1.5f), 1.78f)
        assertTrue("${v[0]}", kotlin.math.abs(v[0]) < 1e-4f)
    }

    @Test fun savedWithTheGradeAndNotGivenToOtherClips() {
        val g = ClipGrade(mblur = MotionBlur(pan(0.01f), 120f))
        val back = ClipGrade.fromJson(JSONObject(g.toJson().toString()))
        assertEquals(120f, back.mblur!!.shutter, 1e-3f)
        assertEquals(g.mblur!!.at(1.0, null, 1.78f)[0], back.mblur!!.at(1.0, null, 1.78f)[0], 1e-3f)
        assertEquals(null, g.forOtherClip(null).mblur)
    }
}
