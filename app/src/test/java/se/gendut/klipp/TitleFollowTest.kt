package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.ClipSnap
import se.gendut.klipp.engine.Follow
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.TitleFollow
import se.gendut.klipp.engine.around
import se.gendut.klipp.engine.fromFrame
import se.gendut.klipp.engine.toFrame
import se.gendut.klipp.engine.Window
import se.gendut.klipp.engine.WindowTrack

/** A title that follows a tracked window: the displacement since the title's start, and that the link is saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TitleFollowTest {
    // the object moves 0.01 of the frame width to the right per frame (25 fps), from source time 2 s
    private val track = WindowTrack(2.0, 0.04, FloatArray(100) { it * 0.01f }, FloatArray(100) { -it * 0.002f }, FloatArray(100) { 1f }, FloatArray(100) { 1f }, smooth = 0f)
    private val grade = ClipGrade(listOf(GradeNode(1, win = Window(shape = 1, track = track))))
    // the clip sits on the timeline at 10 s with source start 2 s
    private val clip = ClipSnap(7, 1, "A", 10.0, 4.0, 2.0, 1.0, null)

    @Test fun offsetSinceTitleStart() {
        val f = Follow(7, 1)
        // the title starts at 10.4 s (frame 10); at 11.2 s (frame 30) the object has moved 20 frames
        val o = TitleFollow.offsetOf(f, clip, grade, 10.4, 11.2, 1920, 1080)!!
        assertEquals(0.2f, o[0], 1e-5f)
        assertEquals(-0.04f, o[1], 1e-5f)
        // at the title's start: no displacement
        assertEquals(0f, TitleFollow.offsetOf(f, clip, grade, 10.4, 10.4, 1920, 1080)!![0], 1e-6f)
        // the node is missing or has no tracking: none
        assertNull(TitleFollow.offsetOf(Follow(7, 9), clip, grade, 10.4, 11.0, 1920, 1080))
    }

    @Test fun scaleAndTurnSinceTitleStart() {
        // the object grows 1 % and turns 0.002 rad (clockwise) per frame
        val tr = WindowTrack(2.0, 0.04, FloatArray(100), FloatArray(100), FloatArray(100) { 1f + it * 0.01f }, FloatArray(100) { 1f }, smooth = 0f, r = FloatArray(100) { it * 0.002f })
        val g = ClipGrade(listOf(GradeNode(1, win = Window(shape = 1, track = tr))))
        // from frame 10 to frame 30: 1.3 / 1.1 and 20 · 0.002
        val o = TitleFollow.offsetOf(Follow(7, 1, scale = true, rotate = true), clip, g, 10.4, 11.2, 1920, 1080)!!
        assertEquals(1.3f / 1.1f, o[2], 1e-4f); assertEquals(0.04f, o[3], 1e-5f)
        // not asked for: unchanged
        val n = TitleFollow.offsetOf(Follow(7, 1), clip, g, 10.4, 11.2, 1920, 1080)!!
        assertEquals(1f, n[2], 0f); assertEquals(0f, n[3], 0f)
    }

    /**
     * A zoom made in the app (scale keyframes 100 → 200 %) on a still object: the title goes where the object shows in
     * the finished picture, and with "scale" it grows as much as the zoom.
     */
    @Test fun followsTheAppsOwnZoom() {
        val still = WindowTrack(2.0, 0.04, FloatArray(100), FloatArray(100), FloatArray(100) { 1f }, FloatArray(100) { 1f }, smooth = 0f)
        // the object right of center in the clip's picture
        val g = ClipGrade(listOf(GradeNode(1, win = Window(shape = 1, cx = 0.75f, cy = 0.5f, track = still))))
        val zoom = clip.copy(kf = mapOf(se.gendut.klipp.engine.KfKeys.SCALE to listOf(se.gendut.klipp.engine.Kf(0, 10.0, listOf(100.0)), se.gendut.klipp.engine.Kf(1, 12.0, listOf(200.0)))))
        val o = TitleFollow.offsetOf(Follow(7, 1, scale = true), zoom, g, 10.0, 12.0, 1920, 1080)!!
        // at 200 % the point 0.25 of the width right of center lands 0.5 right of it: from 0.75 to 1.0
        assertEquals(0.25f, o[0], 1e-4f); assertEquals(0f, o[1], 1e-4f)
        assertEquals(2f, o[2], 1e-4f)
        // halfway (150 %)
        assertEquals(0.125f, TitleFollow.offsetOf(Follow(7, 1), zoom, g, 10.0, 11.0, 1920, 1080)!![0], 1e-4f)
    }

    /** A box drawn on the finished picture goes back through the clip's move/zoom/turn exactly. */
    @Test fun frameAndClipPointsRoundTrip() {
        val c = clip.copy(x = 1200.0, y = 400.0, scale = 160.0, rotation = 25.0)
        for ((u, v) in listOf(0.3f to 0.7f, 0.5f to 0.5f, 0.9f to 0.1f)) {
            val f = c.toFrame(u, v, 1920, 1080)
            val back = c.fromFrame(f[0], f[1], 1920, 1080)
            assertEquals(u, back[0], 1e-4f); assertEquals(v, back[1], 1e-4f)
        }
        // the clip's center lands at its position
        val m = c.toFrame(0.5f, 0.5f, 1920, 1080)
        assertEquals(1200f / 1920f, m[0], 1e-5f); assertEquals(400f / 1080f, m[1], 1e-5f)
    }

    @Test fun followChoicesAreSavedAndOldLinksRead() {
        assertEquals(Follow(7, 1), Follow.parse("7:1"))
        for (f in listOf(Follow(7, 1, true, false), Follow(7, 1, false, true), Follow(7, 1, true, true))) assertEquals(f, Follow.parse(f.encode()))
        assertEquals("7:1", Follow(7, 1).encode())
    }

    /** Growing and turning happen around the text's own point: that point stays where it was, the rest moves around it. */
    @Test fun scaleAndTurnAroundTheText() {
        val w = 1920; val h = 1080
        val base = se.gendut.klipp.engine.Placement(0f, 0f, 0.1f, -0.2f, 1.3f, 15f, 1f)
        val p = -0.5f to 0.25f
        // where an overlay point lands (as drawPlaced does: pixels, scale and counterclockwise turn around the anchor)
        fun land(pl: se.gendut.klipp.engine.Placement, x: Float, y: Float): Pair<Float, Float> {
            fun px(v: Float) = (v + 1f) / 2f * w
            fun py(v: Float) = (1f - v) / 2f * h
            val a = Math.toRadians(-pl.rotation.toDouble())
            val vx = (px(x) - px(pl.ovX)) * pl.scale; val vy = (py(y) - py(pl.ovY)) * pl.scale
            return (px(pl.bgX) + (vx * kotlin.math.cos(a) - vy * kotlin.math.sin(a)).toFloat()) to (py(pl.bgY) + (vx * kotlin.math.sin(a) + vy * kotlin.math.cos(a)).toFloat())
        }
        val moved = base.around(p, 1.8f, 0.3f, w, h)
        val before = land(base, p.first, p.second); val after = land(moved, p.first, p.second)
        assertEquals(before.first, after.first, 0.01f); assertEquals(before.second, after.second, 0.01f)
        assertEquals(1.3f * 1.8f, moved.scale, 1e-5f)
        assertEquals(15f - Math.toDegrees(0.3).toFloat(), moved.rotation, 1e-3f)
        // another point: 1.8 times as far from the text point, turned 0.3 rad clockwise
        val q0 = land(base, 0.2f, -0.1f); val q1 = land(moved, 0.2f, -0.1f)
        val d0 = kotlin.math.hypot(q0.first - before.first, q0.second - before.second)
        val d1 = kotlin.math.hypot(q1.first - before.first, q1.second - before.second)
        assertEquals(1.8f, d1 / d0, 1e-3f)
        val turn = kotlin.math.atan2(q1.second - before.second, q1.first - before.first) - kotlin.math.atan2(q0.second - before.second, q0.first - before.first)
        assertEquals(0.3f, turn, 1e-3f)
    }

    @Test fun followIsSavedWithGrades() {
        val gs = GradeState()
        gs.set(7, grade)
        gs.setFollow(42, Follow(7, 1))
        val back = GradeState().apply { load(JSONObject(gs.toJson().toString())) }
        assertEquals(Follow(7, 1), back.follows[42])
        assertEquals(1, back.grades.size)
    }
}
