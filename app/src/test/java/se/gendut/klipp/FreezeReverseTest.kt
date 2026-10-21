package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipSnap
import se.gendut.klipp.engine.LayerPlanner
import se.gendut.klipp.engine.SegSrc
import se.gendut.klipp.engine.Snapshot

/** Freeze frame and reverse: the engine's fields, the source time and the video layers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FreezeReverseTest {
    private fun fx(edit: (JSONObject) -> Unit = {}): Snapshot {
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot_fx.json")!!.readText())
        edit(o)
        return Snapshot.parse(o)
    }
    private fun clipJson(o: JSONObject, i: Int) = o.getJSONObject("sequence").getJSONArray("video").getJSONObject(0).getJSONArray("clips").getJSONObject(i)

    @Test fun snapshotCarriesHoldAndReverse() {
        val s = fx { o -> clipJson(o, 1).put("hold", 4.5); clipJson(o, 0).put("reverse", true) }
        val q = s.sequence!!.video[0]
        assertEquals(4.5, q.clips[1].hold!!, 1e-9)
        assertTrue(q.clips[0].reverse)
        assertEquals(null, fx().sequence!!.video[0].clips[1].hold)
    }

    @Test fun sourceTimeFollowsHoldReverseAndSpeed() {
        val c = ClipSnap(1, 1, "a", start = 10.0, duration = 4.0, sourceIn = 2.0, speed = 2.0, link = null)
        assertEquals(2.0, c.sourceAt(10.0), 1e-9)
        assertEquals(4.0, c.sourceAt(11.0), 1e-9)
        assertEquals(10.0, c.sourceAt(20.0), 1e-9) // after the clip: the last one
        // reverse: the source's end (2 + 4·2 = 10) first
        val r = c.copy(reverse = true)
        assertEquals(10.0, r.sourceAt(10.0), 1e-9)
        assertEquals(8.0, r.sourceAt(11.0), 1e-9)
        assertEquals(2.0, r.sourceAt(14.0), 1e-9)
        // freeze frame: the same time for the whole clip
        val h = c.copy(hold = 3.3)
        assertEquals(3.3, h.sourceAt(10.0), 1e-9); assertEquals(3.3, h.sourceAt(13.9), 1e-9)
    }

    @Test fun holdClipsBecomeStillSegmentsAlsoInsideATransition() {
        val s = fx { o -> clipJson(o, 1).put("hold", 7.25) }
        val plan = LayerPlanner.plan(s) { 30.0 }!!
        val base = plan.layers.last().segs
        val held = s.sequence!!.video[0].clips[1]
        val segs = base.filter { it.clip?.id == held.id }
        assertTrue(segs.isNotEmpty())
        for (seg in segs) assertEquals(SegSrc.Still(held.item, 7.25), seg.src)
        // the cross dissolve into the freeze frame: the freeze frame sits in the track for the whole transition, the outgoing (moving) clip
        // as video above, fading out (a still in an upper layer gets the wrong frame in Media3's compositor)
        val x = s.sequence!!.video[0].transitions.first { it.to == held.id }
        assertTrue(base.filter { it.start >= x.start - 1e-6 && it.end <= x.end + 1e-6 }.all { it.clip?.id == held.id })
        val over = plan.layers.first().segs
        assertTrue(over.none { it.clip?.id == held.id })
        assertTrue(over.single().src is SegSrc.Media)
        assertEquals(0f, over.single().ramp!!.a1)
    }
}
