package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.LayerFx
import se.gendut.klipp.engine.LayerMask
import se.gendut.klipp.engine.MaskKey
import se.gendut.klipp.engine.WindowTrack
import kotlin.math.abs
import kotlin.math.sin

/** The mask's keyframes: interpolation, editing at the playhead, saving, and tracking → keyframes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MaskKeysTest {
    private val m = LayerMask(cx = 0.5f, cy = 0.5f, w = 0.4f, h = 0.3f, keys = listOf(
        MaskKey(1.0, 0.2f, 0.3f, 0.4f, 0.3f),
        MaskKey(2.0, 0.6f, 0.5f, 0.6f, 0.5f, MaskKey.SMOOTH),
        MaskKey(3.0, 0.8f, 0.5f, 0.6f, 0.5f, MaskKey.HOLD),
        MaskKey(4.0, 0.1f, 0.1f, 0.2f, 0.2f),
    ))

    @Test fun interpolatesBetweenKeysAndHoldsTheEnds() {
        // before the first and after the last: their values
        assertEquals(0.2f, m.at(0.0).cx, 1e-6f); assertEquals(0.1f, m.at(9.0).cx, 1e-6f)
        // linear
        val a = m.at(1.5)
        assertEquals(0.4f, a.cx, 1e-6f); assertEquals(0.4f, a.cy, 1e-6f); assertEquals(0.5f, a.w, 1e-6f); assertEquals(0.4f, a.h, 1e-6f)
        assertFalse(a.animated)
        // smooth: the middle is the middle, a quarter in has gone less far than linear
        assertEquals(0.7f, m.at(2.5).cx, 1e-6f)
        assertTrue(m.at(2.25).cx < 0.65f)
        // hold: stays still until the next keyframe
        assertEquals(0.8f, m.at(3.99).cx, 1e-6f)
        assertEquals(0.1f, m.at(4.0).cx, 1e-6f)
        // without a time: the fixed values do not change, the keyframes are removed
        assertEquals(0.5f, m.at(null).cx, 1e-6f); assertFalse(m.at(null).animated)
        // without keyframes: as is
        val still = LayerMask(cx = 0.3f)
        assertTrue(still.at(2.0) === still)
    }

    @Test fun coverageFollowsTheKeys() {
        val fx = LayerFx(mask = LayerMask(w = 0.2f, h = 0.2f, feather = 0f, keys = listOf(MaskKey(0.0, 0.2f, 0.5f, 0.2f, 0.2f), MaskKey(1.0, 0.8f, 0.5f, 0.2f, 0.2f))))
        val px = floatArrayOf(1f, 1f, 1f)
        assertEquals(1f, fx.apply(px, 0.2f, 0.5f, 1f, 0.0), 1e-4f)
        assertEquals(0f, fx.apply(px, 0.2f, 0.5f, 1f, 1.0), 1e-4f)
        assertEquals(1f, fx.apply(px, 0.8f, 0.5f, 1f, 1.0), 1e-4f)
        assertEquals(1f, fx.apply(px, 0.5f, 0.5f, 1f, 0.5), 1e-4f)
    }

    @Test fun editingAtThePlayheadMakesOrChangesAKey() {
        val tol = 1 / 60.0
        // static mask: the values change, no keyframes
        val still = LayerMask()
        val s2 = still.withValues(still.copy(cx = 0.3f), 1.0, tol)
        assertEquals(0.3f, s2.cx, 0f); assertFalse(s2.animated)
        // animated: a new keyframe between two
        val n = m.withValues(m.at(1.5).copy(cx = 0.9f), 1.5, tol)
        assertEquals(5, n.keys.size)
        assertEquals(0.9f, n.at(1.5).cx, 1e-6f)
        assertEquals(listOf(1.0, 1.5, 2.0, 3.0, 4.0), n.keys.map { it.t })
        // near an existing one: that one changes (keeps its time and interpolation)
        val c = m.withValues(m.at(2.0).copy(w = 0.9f), 2.0 + tol / 2, tol)
        assertEquals(4, c.keys.size)
        assertEquals(2.0, c.keys[1].t, 0.0); assertEquals(0.9f, c.keys[1].w, 0f); assertEquals(MaskKey.SMOOTH, c.keys[1].interp)
        // stopwatch on: a keyframe with the fixed values; off: the values at the playhead become fixed
        val on = still.toggleAnimation(2.0)
        assertEquals(listOf(MaskKey(2.0, 0.5f, 0.5f, 0.6f, 0.6f)), on.keys)
        val off = m.toggleAnimation(1.5)
        assertFalse(off.animated); assertEquals(0.4f, off.cx, 1e-6f)
        // ◆: add with the values there, remove again; last one removed → fixed there
        val k = m.toggleKey(1.5, tol)
        assertEquals(5, k.keys.size); assertEquals(0.4f, k.keys[1].cx, 1e-6f)
        assertEquals(m.keys, k.toggleKey(1.5, tol).keys)
        val one = LayerMask(keys = listOf(MaskKey(1.0, 0.2f, 0.2f, 0.3f, 0.3f)))
        val gone = one.toggleKey(1.0, tol)
        assertFalse(gone.animated); assertEquals(0.2f, gone.cx, 0f)
        // move: another one already there is replaced
        val mv = m.moveKey(m.keys[0], 2.0, tol)
        assertEquals(listOf(2.0, 3.0, 4.0), mv.keys.map { it.t }); assertEquals(0.2f, mv.keys[0].cx, 0f)
        assertEquals(MaskKey.HOLD, m.setInterp(m.keys[0], MaskKey.HOLD).keys[0].interp)
        // a track only replaces the keyframes within its range
        val r = m.replaceKeys(1.5, 3.5, listOf(MaskKey(2.5, 0f, 0f, 0.1f, 0.1f)))
        assertEquals(listOf(1.0, 2.5, 4.0), r.keys.map { it.t })
    }

    @Test fun savedWithTheGradeAndKeptOnSplit() {
        val g = ClipGrade(layer = LayerFx(mask = m))
        val back = ClipGrade.fromJson(JSONObject(g.toJson().toString()))
        assertEquals(m, back.layer?.mask)
        assertTrue(g.needsClock)
        assertFalse(ClipGrade(layer = LayerFx(mask = LayerMask())).needsClock)
        // older projects without keyframes read as before
        assertFalse(LayerMask.fromJson(JSONObject("""{"shape":1,"cx":0.3}"""))!!.animated)
        // broken keyframes are skipped
        val bad = LayerMask.fromJson(JSONObject("""{"keys":[[1.0,0.1,0.2,0.3,0.4,2],[0.5],null,[0.2,0.5,0.5,0.6,0.6]]}"""))!!
        assertEquals(listOf(0.2, 1.0), bad.keys.map { it.t }); assertEquals(MaskKey.HOLD, bad.keys[1].interp)
        // another clip's grade does not get the mask (it belongs to the clip's own image)
        assertNull(g.forOtherClip(null).layer)
    }

    @Test fun trackBecomesFewKeysThatStayOnTheTrack() {
        // the object moves straight for 1 s, then in an arc, and grows a little: 4 s at 30 fps
        val n = 121; val dt = 1 / 30.0
        val dx = FloatArray(n) { i -> val t = i * dt; if (t < 1) (0.2 * t).toFloat() else (0.2 + 0.1 * sin((t - 1) * 2)).toFloat() }
        val dy = FloatArray(n) { i -> (0.05 * i * dt).toFloat() }
        val sc = FloatArray(n) { i -> (1 + 0.1 * i * dt).toFloat() }
        val track = WindowTrack(10.0, dt, dx, dy, sc, FloatArray(n) { 1f }, smooth = 0f)
        val base = LayerMask(cx = 0.3f, cy = 0.4f, w = 0.2f, h = 0.25f)
        val keys = MaskKey.fromTrack(track, base, 10.0, 14.0)
        // the first and last frame, and far fewer than the frames
        assertEquals(10.0, keys.first().t, 1e-9); assertEquals(14.0, keys.last().t, 1e-9)
        assertTrue("${keys.size} keyframes", keys.size in 3..40)
        // the mask between the keyframes follows the track in every frame
        val mm = base.copy(keys = keys)
        var worst = 0f
        for (i in 0 until n) {
            val t = 10.0 + i * dt
            val a = mm.at(t)
            worst = maxOf(worst, abs(a.cx - (0.3f + dx[i])), abs(a.cy - (0.4f + dy[i])), abs(a.w - 0.2f * sc[i]), abs(a.h - 0.25f * sc[i]))
        }
        assertTrue("deviation $worst", worst <= 0.0016f)
        // a straight motion becomes two keyframes
        val lin = WindowTrack(0.0, dt, FloatArray(31) { (it * 0.01).toFloat() }, FloatArray(31), FloatArray(31) { 1f }, FloatArray(31) { 1f }, smooth = 0f)
        assertEquals(2, MaskKey.fromTrack(lin, base, 0.0, 1.0).size)
        // only within the clip's range
        val part = MaskKey.fromTrack(track, base, 11.0, 12.0)
        assertEquals(11.0, part.first().t, 1e-9); assertEquals(12.0, part.last().t, 1e-9)
    }

    @Test fun timelineAndSourceTimeAgree() {
        val fwd = ColorClip(1, "a", 5f, 9f, null, 2.0, 0, speed = 2.0)
        val rev = fwd.copy(reverse = true)
        for (t in listOf(5f, 6.5f, 9f)) {
            assertEquals(t, fwd.timelineAt(fwd.srcAt(t)), 1e-4f)
            assertEquals(t, rev.timelineAt(rev.srcAt(t)), 1e-4f)
        }
        assertEquals(10.0, rev.srcAt(5f), 1e-9)
    }
}
