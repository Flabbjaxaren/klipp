package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.LiveGrades
import se.gendut.klipp.engine.Wheel
import se.gendut.klipp.engine.Window
import se.gendut.klipp.engine.TrackCloud
import se.gendut.klipp.engine.WindowTrack
import se.gendut.klipp.engine.WindowTracker

/** Tracked windows: the data, time → position, smoothing, saving and how the grade uses them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WindowTrackTest {
    private fun line(n: Int, t0: Double = 2.0, dt: Double = 0.04) =
        WindowTrack(t0, dt, FloatArray(n) { it * 0.01f }, FloatArray(n) { -it * 0.005f }, FloatArray(n) { 1f + it * 0.002f }, FloatArray(n) { 1f }, smooth = 0f)

    @Test fun interpolatesAndHoldsEnds() {
        val t = line(11)
        val mid = t.at(2.0 + 0.04 * 2.5)
        assertEquals(0.025f, mid[0], 1e-5f)
        assertEquals(-0.0125f, mid[1], 1e-5f)
        assertEquals(1.005f, mid[2], 1e-5f)
        // outside: the end values
        assertEquals(0f, t.at(0.0)[0], 1e-6f)
        assertEquals(0.1f, t.at(99.0)[0], 1e-6f)
        assertEquals(2.4, t.end, 1e-9)
    }

    @Test fun windowFollowsTrack() {
        val w = Window(shape = 1, cx = 0.4f, cy = 0.5f, rx = 0.2f, ry = 0.1f, track = line(11))
        val at = w.at(2.0 + 0.04 * 10)
        assertEquals(0.5f, at.cx, 1e-5f)
        assertEquals(0.45f, at.cy, 1e-5f)
        assertEquals(0.2f * 1.02f, at.rx, 1e-5f)
        assertNull(at.track)
        // without a time: the base position
        assertEquals(0.4f, w.at(null).cx, 1e-6f)
    }

    @Test fun jsonRoundTrip() {
        val w = Window(shape = 2, cx = 0.3f, track = line(5).withSmooth(0.6f))
        val back = Window.fromJson(JSONObject(w.toJson().toString()))
        assertEquals(w, back)
        assertNotNull(back.track)
        assertEquals(0.6f, back.track!!.smooth, 1e-6f)
        // without tracking: as before (old projects)
        assertNull(Window.fromJson(JSONObject("""{"shape":1,"cx":0.5}""")).track)
        // a broken track is read as none
        assertNull(WindowTrack.fromJson(JSONObject("""{"t0":0,"dt":0,"x":[0],"y":[0],"s":[1]}""")))
    }

    @Test fun smoothingRemovesJitterWithoutLag() {
        val n = 101
        val noisy = FloatArray(n) { it * 0.01f + (if (it % 2 == 0) 0.004f else -0.004f) }
        val t = WindowTrack(0.0, 0.04, noisy, FloatArray(n), FloatArray(n) { 1f }, FloatArray(n) { 1f }, smooth = 0.7f)
        // in the middle: close to the smooth line and no phase shift
        for (i in 20..80) assertEquals(i * 0.01f, t.at(i * 0.04)[0], 0.0015f)
        val raw = t.withSmooth(0f)
        assertEquals(noisy[50], raw.at(50 * 0.04)[0], 1e-6f)
    }

    @Test fun mergeResamplesBothDirections() {
        val back = (1..10).map { WindowTracker.Sample(5.0 - it * 0.04, -it * 0.01f, 0f, 1f, 1f) }
        val fwd = (0..10).map { WindowTracker.Sample(5.0 + it * 0.04, it * 0.01f, 0f, 1f, 1f) }
        val t = WindowTracker.merge(back.asReversed() + fwd)
        assertEquals(21, t.size)
        assertEquals(4.6, t.t0, 1e-9)
        assertEquals(0.04, t.dt, 1e-9)
        assertEquals(0f, t.withSmooth(0f).at(5.0)[0], 1e-5f)
        assertEquals(0.1f, t.withSmooth(0f).at(5.4)[0], 1e-5f)
    }

    @Test fun gradeAtMovesOnlyTrackedWindows() {
        val tracked = GradeNode(1, "A", gain = Wheel(master = 0.2f), win = Window(shape = 1, cx = 0.5f, track = line(11)))
        val plain = GradeNode(2, "B", gain = Wheel(master = 0.1f), win = Window(shape = 1, cx = 0.2f))
        val g = ClipGrade(listOf(tracked, plain))
        assertTrue(g.hasTrack)
        val at = g.at(2.4)
        assertEquals(0.6f, at.nodes[0].win.cx, 1e-5f)
        assertEquals(0.2f, at.nodes[1].win.cx, 1e-6f)
        assertTrue(!at.hasTrack)
        // tracking does not change the structure (no player rebuild when it is added)
        val untracked = ClipGrade(listOf(tracked.copy(win = tracked.win.copy(track = null)), plain))
        assertEquals(LiveGrades.structure(mapOf(1L to untracked)), LiveGrades.structure(mapOf(1L to g)))
    }

    @Test fun cloudFindsNearestFrameOnly() {
        val smp = (0..10).map { i -> WindowTracker.Sample(2.0 + i * 0.04, 0f, 0f, 1f, 1f, floatArrayOf(0.1f * i, 0.5f)) }
        val c = TrackCloud(smp)
        assertEquals(0.3f, c.at(2.0 + 0.04 * 3 + 0.01)!!.pts!![0], 1e-6f)
        assertEquals(1.0f, c.at(2.4)!!.pts!![0], 1e-6f)
        // outside the track: no cloud
        assertNull(c.at(1.0))
        assertNull(c.at(3.0))
        assertTrue(TrackCloud(emptyList()).isEmpty)
    }

    /** The frame rate: Media3's extractors report it as a decimal, the platform as an integer. */
    @Test fun frameRateFromEitherType() {
        val f = android.media.MediaFormat()
        assertEquals(30.0, WindowTracker.frameRate(f), 1e-9)
        f.setFloat(android.media.MediaFormat.KEY_FRAME_RATE, 29.97f)
        assertEquals(29.97, WindowTracker.frameRate(f), 1e-3)
        f.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 25)
        assertEquals(25.0, WindowTracker.frameRate(f), 1e-9)
    }
}
