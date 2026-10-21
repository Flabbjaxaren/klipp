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
import se.gendut.klipp.engine.LayerPlanner
import se.gendut.klipp.engine.Mask
import se.gendut.klipp.engine.SegFx
import se.gendut.klipp.engine.SegSrc
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.TransitionType

/** The transitions: the geometry per piece and how they end up in the video layers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransitionsTest {
    private fun fx(type: TransitionType, incoming: Boolean, over: Boolean, reverse: Boolean = false) = SegFx(type, incoming, over, 2.0, 4.0, 3.0, reverse)

    @Test fun wipeRevealsTheIncomingFromTheLeftAndTheOutgoingIsItsComplement() {
        val inc = fx(TransitionType.WIPE, true, true).at(2.5) // 25 %
        val out = fx(TransitionType.WIPE, false, true).at(2.5)
        for (x in listOf(0.1f, 0.2f, 0.3f, 0.9f)) {
            // exactly one of the two is visible at every point
            assertTrue(SegFx.visibleAt(inc.mask, x, 0.5f, 1.78f) != SegFx.visibleAt(out.mask, x, 0.5f, 1.78f))
        }
        assertTrue(SegFx.visibleAt(inc.mask, 0.2f, 0.5f, 1.78f)); assertFalse(SegFx.visibleAt(inc.mask, 0.3f, 0.5f, 1.78f))
        // reversed: from the right
        val rev = fx(TransitionType.WIPE, true, true, reverse = true).at(2.5)
        assertTrue(SegFx.visibleAt(rev.mask, 0.8f, 0.5f, 1.78f)); assertFalse(SegFx.visibleAt(rev.mask, 0.7f, 0.5f, 1.78f))
    }

    @Test fun pushMovesBothClipsTogetherAndEndsInPlace() {
        assertEquals(1f, fx(TransitionType.PUSH, true, true).at(2.0).dx, 1e-6f)
        assertEquals(0.5f, fx(TransitionType.PUSH, true, true).at(3.0).dx, 1e-6f)
        assertEquals(-0.5f, fx(TransitionType.PUSH, false, false).at(3.0).dx, 1e-6f)
        assertEquals(0f, fx(TransitionType.PUSH, true, false).at(4.0).dx, 1e-6f)
        assertEquals(-0.5f, fx(TransitionType.PUSH, true, true, reverse = true).at(3.0).dx, 1e-6f)
    }

    @Test fun irisGrowsAndDipsReachTheColourAtTheCut() {
        val g = fx(TransitionType.IRIS, true, true).at(3.0)
        assertTrue(SegFx.visibleAt(g.mask, 0.5f, 0.5f, 1.78f)); assertFalse(SegFx.visibleAt(g.mask, 0.02f, 0.02f, 1.78f))
        assertTrue(SegFx.visibleAt(fx(TransitionType.IRIS, true, true).at(4.0).mask, 0.01f, 0.01f, 1.78f))
        val dip = fx(TransitionType.DIP_WHITE, false, false)
        assertEquals(0f, dip.at(2.0).tint, 1e-6f); assertEquals(1f, dip.at(3.0).tint, 1e-3f)
        assertEquals(0.5f, fx(TransitionType.DIP_WHITE, true, false).at(3.5).tint, 1e-3f)
        assertTrue(dip.at(3.0).tintWhite)
        assertFalse(fx(TransitionType.DIP_BLACK, true, true).visible)
    }

    @Test fun zoomGoesInToTheCutAndOutAgain() {
        val out = fx(TransitionType.ZOOM, false, false); val inc = fx(TransitionType.ZOOM, true, false)
        assertEquals(1f, out.at(2.0).scale, 1e-6f)
        assertEquals(1f + SegFx.ZOOM_MAX, out.at(2.999).scale, 0.02f)
        assertEquals(1f + SegFx.ZOOM_MAX, inc.at(3.0).scale, 1e-4f)
        assertEquals(1f, inc.at(4.0).scale, 1e-6f)
        assertTrue(out.at(2.5).scale < out.at(2.8).scale)
        assertFalse(fx(TransitionType.ZOOM, true, true).visible)
        assertEquals(TransitionType.ZOOM, TransitionType.of("cross_zoom"))
    }

    private fun fxSnap(effect: String, reverse: Boolean = false): Snapshot {
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot_fx.json")!!.readText())
        val tr = o.getJSONObject("sequence").getJSONArray("video").getJSONObject(0).getJSONArray("transitions").getJSONObject(0)
        tr.put("effect", effect).put("reverse", reverse)
        return Snapshot.parse(o)
    }

    @Test fun crossDissolveIsUnchanged() {
        val plan = LayerPlanner.plan(fxSnap("cross_dissolve")) { 30.0 }!!
        assertTrue(plan.layers.first().segs.all { it.fx == null && it.ramp != null })
        assertTrue(plan.layers.last().segs.all { it.fx == null })
    }

    @Test fun pushGivesBothClipsTheirRoleWithoutSplittingTheTrack() {
        val s = fxSnap("push", reverse = true)
        val x = s.sequence!!.video[0].transitions.single()
        val plan = LayerPlanner.plan(s) { 30.0 }!!
        val over = plan.layers.first().segs
        assertEquals(2, over.size)
        assertTrue(over.all { it.ramp == null && it.fx?.type == TransitionType.PUSH && it.fx!!.reverse && it.fx!!.over })
        // the track: the same pieces as without a transition (one per clip – every new piece made the player restart the file),
        // each with the transition's window and its role
        val base = plan.layers.last().segs
        val plain = LayerPlanner.plan(fxSnap("cross_dissolve")) { 30.0 }!!.layers.last().segs
        assertEquals(plain.map { it.start to it.end }, base.map { it.start to it.end })
        assertEquals(listOf(x.from, x.to), base.map { it.clip?.id })
        assertEquals(listOf(false, true), base.map { it.windows.single().incoming })
        assertTrue(base.all { it.windows.single().let { w -> w.t0 == x.start && w.t1 == x.end && !w.over && w.type == TransitionType.PUSH } })
        // outside the window: unchanged image; inside: shifted
        val outgoing = base.first()
        assertNull(outgoing.geomAt(x.start - 0.5))
        assertEquals(0.25f, outgoing.geomAt(x.start + x.duration / 4)!!.dx, 1e-4f)
        assertNull(base.last().geomAt(x.end + 0.5))
        assertTrue(base.last().geomAt(x.end)!!.identity)
    }

    /** One of the transition's clips as a freeze frame (source time 1.0 s). */
    private fun holdSnap(effect: String, holdFrom: Boolean): Snapshot {
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot_fx.json")!!.readText())
        val v = o.getJSONObject("sequence").getJSONArray("video").getJSONObject(0)
        val tr = v.getJSONArray("transitions").getJSONObject(0).put("effect", effect)
        val id = if (holdFrom) tr.getLong("from") else tr.getLong("to")
        val clips = v.getJSONArray("clips")
        for (i in 0 until clips.length()) if (clips.getJSONObject(i).getLong("id") == id) clips.getJSONObject(i).put("hold", 1.0)
        return Snapshot.parse(o)
    }

    @Test fun aFrozenClipStaysInTheTrackForTheWholeTransition() {
        for (effect in listOf("push", "wipe", "slide", "iris_round", "cross_dissolve")) for (holdFrom in listOf(true, false)) {
            val s = holdSnap(effect, holdFrom)
            val x = s.sequence!!.video[0].transitions.single()
            val still = if (holdFrom) x.from else x.to
            val moving = if (holdFrom) x.to else x.from
            val plan = LayerPlanner.plan(s) { 30.0 }!!
            val what = "$effect, freeze frame ${if (holdFrom) "outgoing" else "incoming"}"
            // the upper layer: only the moving clip, the whole transition, never a still
            val over = plan.layers.first().segs
            assertEquals(what, 1, over.size)
            assertEquals(what, moving, over.single().clip?.id)
            assertTrue(what, over.single().src is SegSrc.Media)
            assertEquals(what, x.start, over.single().start, 1e-6); assertEquals(what, x.end, over.single().end, 1e-6)
            // the track: the freeze frame for the whole transition
            val inside = plan.layers.last().segs.filter { it.start >= x.start - 1e-6 && it.end <= x.end + 1e-6 }
            assertEquals(what, x.duration, inside.sumOf { it.dur }, 1e-6)
            assertTrue(what, inside.all { it.clip?.id == still && it.src is SegSrc.Still && it.ramp == null })
            if (effect == "cross_dissolve") {
                assertTrue(what, inside.all { it.fx == null })
                // the upper clip fades in (incoming) or out (outgoing) over the whole transition
                val r = over.single().ramp!!
                assertEquals(what, if (holdFrom) 0f else 1f, r.a0); assertEquals(what, if (holdFrom) 1f else 0f, r.a1)
            } else {
                assertEquals(what, holdFrom, over.single().fx!!.incoming)
                assertTrue(what, inside.all { it.fx!!.incoming == !holdFrom && !it.fx!!.over })
                // outside the transition: the moving clip's pieces in the track only have the window (not split)
                assertTrue(what, plan.layers.last().segs.filter { it.clip?.id == moving }.all { it.fx == null && it.windows.size == 1 })
            }
        }
    }

    @Test fun dipThroughAColourHasNoOverlayLayer() {
        val plan = LayerPlanner.plan(fxSnap("dip_to_white")) { 30.0 }!!
        assertEquals(1, plan.layers.size)
        assertTrue(plan.layers.single().segs.any { s -> s.windows.any { it.type == TransitionType.DIP_WHITE } })
    }

    @Test fun whiteFadeInUsesATintNotOpacity() {
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot_fx.json")!!.readText())
        val v = o.getJSONObject("sequence").getJSONArray("video").getJSONObject(0)
        val first = v.getJSONArray("clips").getJSONObject(0)
        v.put("transitions", org.json.JSONArray().put(JSONObject().put("id", 99).put("effect", "dip_to_white").put("start", first.getDouble("start"))
            .put("duration", 1.0).put("from", JSONObject.NULL).put("to", first.getLong("id"))))
        val plan = LayerPlanner.plan(Snapshot.parse(o)) { 30.0 }!!
        val seg = plan.layers.last().segs.first()
        assertNull(seg.ramp)
        assertEquals(TransitionType.DIP_WHITE, seg.fx!!.type)
        assertEquals(1f, seg.fx!!.at(first.getDouble("start")).tint, 0.01f)
        assertTrue(plan.blackFades.isEmpty())
    }

    /** The clips as in snapshot_fx but the incoming one starts [inSrc] s into the file. */
    private fun edgeSnap(effect: String, inSrc: Double): Snapshot {
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot_fx.json")!!.readText())
        val v = o.getJSONObject("sequence").getJSONArray("video").getJSONObject(0)
        v.getJSONArray("transitions").getJSONObject(0).put("effect", effect)
        val clips = v.getJSONArray("clips")
        for (i in 0 until clips.length()) if (clips.getJSONObject(i).getLong("id") == 13L) clips.getJSONObject(i).put("sourceIn", inSrc)
        return Snapshot.parse(o)
    }

    @Test fun handlesUseRealFramesAndFreezeOnlyWhereTheFileEnds() {
        for (effect in listOf("slide", "push", "cross_dissolve")) {
            // plenty of material on both sides: only real frames, exactly the whole transition (2.6–3.4)
            val full = LayerPlanner.plan(edgeSnap(effect, 5.0)) { 30.0 }!!.layers.first().segs
            assertTrue(effect, full.all { it.src is SegSrc.Media })
            assertEquals(effect, 2.6, full.first().start, 1e-6); assertEquals(effect, 3.4, full.last().end, 1e-6)
            assertEquals(effect, 4.6, (full.first().src as SegSrc.Media).srcIn, 1e-6)
            assertEquals(effect, 4.0, (full.last().src as SegSrc.Media).srcIn, 1e-6)

            // the outgoing clip (source 1–4) in a 4.2 s file and the incoming one 0.2 s into the file:
            // 0.2 s of real frames on each side, the rest a freeze of the first/last frame
            val plan = LayerPlanner.plan(edgeSnap(effect, 0.2)) { 4.2 }!!
            val over = plan.layers.first().segs
            val what = "$effect: " + over.joinToString { "${"%.2f".format(it.start)}–${"%.2f".format(it.end)} ${it.clip?.id} ${it.src}" }
            // the upper layer: only moving images (a still there ends up two frames off in Media3's compositor)
            assertTrue(what, over.all { it.src is SegSrc.Media })
            assertEquals(what, listOf(10L, 13L, 10L, 13L), over.map { it.clip?.id })
            assertEquals(what, listOf(2.6, 2.8, 3.0, 3.2), over.map { it.start }.map { Math.round(it * 100) / 100.0 })
            assertEquals(what, 3.4, over.last().end, 1e-6)
            // real frames outside the clips: the incoming one's 0–0.2 s and the outgoing one's 4.0–4.2 s
            assertEquals(what, 0.0, (over[1].src as SegSrc.Media).srcIn, 1e-6); assertEquals(what, 4.0, (over[2].src as SegSrc.Media).srcIn, 1e-6)
            // above the freeze frame: the track's own clip at the same time (the outgoing 3.6 s, the incoming 0.4 s)
            assertEquals(what, 3.6, (over[0].src as SegSrc.Media).srcIn, 1e-6); assertEquals(what, 0.4, (over[3].src as SegSrc.Media).srcIn, 1e-6)
            // the roles follow the clip, not the layer
            if (effect != "cross_dissolve") assertEquals(what, listOf(false, true, false, true), over.map { it.fx!!.incoming })
            else assertEquals(what, listOf(1f, 0f, 1f, 0f), over.map { it.ramp!!.a0 })
            // the track: the freeze frames (first frame before, last after) with their clip's role
            val base = plan.layers.last().segs.filter { it.start >= 2.6 - 1e-6 && it.end <= 3.4 + 1e-6 }
            val bw = "$effect track: " + base.joinToString { "${"%.2f".format(it.start)}–${"%.2f".format(it.end)} ${it.clip?.id} ${it.src}" }
            assertEquals(bw, 0.8, base.sumOf { it.dur }, 1e-6)
            val stills = base.filter { it.src is SegSrc.Still }
            assertEquals(bw, listOf(SegSrc.Still(1, 0.0)), stills.filter { it.end <= 3.0 + 1e-6 }.map { it.src })
            assertEquals(bw, 2.6, stills.first().start, 1e-6); assertEquals(bw, 2.8, stills.first().end, 1e-6)
            assertEquals(bw, 13L, stills.first().clip?.id); assertEquals(bw, 10L, stills.last().clip?.id)
            assertEquals(bw, 4.2, (stills.last().src as SegSrc.Still).sec, 0.01); assertEquals(bw, 3.2, stills.last().start, 1e-6)
            assertTrue(bw, stills.all { it.ramp == null })
            if (effect != "cross_dissolve") assertEquals(bw, listOf(true, false), stills.map { it.fx!!.incoming })
            // the pieces in the track are in order without overlap
            val all = plan.layers.last().segs
            for (i in 1 until all.size) assertTrue(bw, all[i].start >= all[i - 1].end - 1e-6)
            // the freeze frames are produced ahead of time (playback and export)
            assertEquals(what, setOf(1L to 0.0, 1L to 4.199), LayerPlanner.stillsOf(plan).toSet())
        }
        // the clip ends exactly where the file ends: the whole second half is the last frame
        val endPlan = LayerPlanner.plan(edgeSnap("slide", 5.0)) { 4.0 }!!
        val end = endPlan.layers.last().segs.filter { it.clip?.id == 10L && it.src is SegSrc.Still }
        assertEquals(1, end.size)
        assertEquals(3.0, end.single().start, 1e-6); assertEquals(3.4, end.single().end, 1e-6)
        // above: the incoming one sliding in
        val top = endPlan.layers.first().segs.single { it.start >= 3.0 - 1e-6 }
        assertEquals(13L, top.clip?.id); assertTrue(top.fx!!.incoming && top.fx!!.over)
    }
}
