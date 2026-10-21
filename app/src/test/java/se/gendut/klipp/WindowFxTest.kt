package se.gendut.klipp

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.Wheel
import se.gendut.klipp.engine.Window
import se.gendut.klipp.engine.WindowTrack
import kotlin.math.abs

/** Rotated windows, blur/pixelation inside the window, and correcting a track. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WindowFxTest {
    @Test fun rotatedWindowKey() {
        // narrow rectangle (horizontal), rotated 90° → stands upright
        val w = Window(shape = 2, cx = 0.5f, cy = 0.5f, rx = 0.3f, ry = 0.02f, soft = 0f)
        val p0 = GradeNode(1, gain = Wheel(master = 0.5f), win = w).params()
        val p90 = GradeNode(1, gain = Wheel(master = 0.5f), win = w.copy(angle = 90f)).params()
        val aspect = 16f / 9f
        // a point right of center: inside without rotation, outside with it
        assertEquals(1f, GradeNode.key(p0, .5f, .5f, .5f, 0.7f, 0.5f, aspect), 1e-4f)
        assertEquals(0f, GradeNode.key(p90, .5f, .5f, .5f, 0.7f, 0.5f, aspect), 1e-4f)
        // a point straight above: 0.3·width in pixels = 0.3·aspect vertically
        assertEquals(1f, GradeNode.key(p90, .5f, .5f, .5f, 0.5f, 0.5f - 0.3f * aspect * 0.9f, aspect), 1e-4f)
        // is saved
        assertEquals(90f, Window.fromJson(JSONObject(w.copy(angle = 90f).toJson().toString())).angle, 1e-6f)
    }

    @Test fun blurOnlyInsideWindow() {
        // checkerboard: blur inside the window makes it gray, outside unchanged
        val b = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        for (y in 0 until 36) for (x in 0 until 64) b.setPixel(x, y, if ((x / 2 + y / 2) % 2 == 0) -1 else -0x1000000)
        val g = ClipGrade(listOf(GradeNode(1, blur = 1f, win = Window(shape = 1, cx = 0.25f, cy = 0.5f, rx = 0.15f, ry = 0.3f, soft = 0f))))
        assertTrue(!g.isNeutral)
        val out = g.applyTo(b)
        val inside = out.getPixel(16, 18) and 255
        assertTrue("blurred inside: $inside", inside in 60..200)
        val outside = out.getPixel(48, 18)
        assertEquals(b.getPixel(48, 18), outside)
        // pixelation: all points in a cell are equal
        val px = ClipGrade(listOf(GradeNode(1, pixelate = 1f))).applyTo(b)
        assertEquals(px.getPixel(0, 0), px.getPixel(2, 2))
    }

    @Test fun trackCorrectionShiftsFromFrameAndContinues() {
        val n = 50
        val t = WindowTrack(0.0, 0.04, FloatArray(n) { it * 0.01f }, FloatArray(n), FloatArray(n) { 1f }, FloatArray(n) { 1f }, smooth = 0f)
        // correction at frame 30: +0.05 in x from there on, smooth transition before
        val c = t.shiftFrom(30 * 0.04, 0.05f, 0f)
        assertEquals(t.dx[10], c.dx[10], 1e-6f)
        assertEquals(t.dx[30] + 0.05f, c.dx[30], 1e-6f)
        assertEquals(t.dx[49] + 0.05f, c.dx[49], 1e-6f)
        assertTrue(c.dx[28] > t.dx[28] && c.dx[28] < t.dx[28] + 0.05f)
        // track on from frame 30: new values relative to there, in line with the old ones
        val fresh = WindowTrack(30 * 0.04, 0.04, FloatArray(20) { it * 0.02f }, FloatArray(20), FloatArray(20) { 1f }, FloatArray(20) { 1f }, smooth = 0f)
        val m = c.replaceFrom(fresh)
        assertEquals(50, m.size)
        assertEquals(c.dx[29], m.dx[29], 1e-6f)
        assertEquals(c.dx[30], m.dx[30], 1e-5f)
        assertEquals(c.dx[30] + 19 * 0.02f, m.dx[49], 1e-5f)
        // the rotation is carried along in the window
        val rot = WindowTrack(0.0, 0.04, FloatArray(3), FloatArray(3), FloatArray(3) { 1f }, FloatArray(3) { 1f }, smooth = 0f, r = floatArrayOf(0f, 0.1f, 0.2f))
        val w = Window(shape = 1, angle = 10f, track = rot).at(0.08)
        assertTrue(abs(w.angle - (10f + Math.toDegrees(0.2).toFloat())) < 1e-3f)
    }
}
