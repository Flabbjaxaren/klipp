package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.LayerPlanner
import se.gendut.klipp.engine.RenderCache
import se.gendut.klipp.engine.Snapshot

/**
 * Playback shows a transition between two clips only pre-rendered (the export's own pixels, one file): a plain cut until
 * then. It never composes one live from several players – with heavy files those drifted apart (the transition showed
 * twice, and a freeze frame's mirrored continuation was seen).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayPlanTest {
    private fun snap(effect: String): Snapshot {
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot_fx.json")!!.readText())
        o.getJSONObject("sequence").getJSONArray("video").getJSONObject(0).getJSONArray("transitions").getJSONObject(0).put("effect", effect)
        return Snapshot.parse(o)
    }

    @Test fun withoutTransitionsTheTimelineIsTheClipsCutTogether() {
        for (effect in listOf("whip", "push", "cross_dissolve", "cross_zoom", "dip_to_black", "light_leak")) {
            val s = snap(effect)
            val full = LayerPlanner.plan(s) { 30.0 }!!
            val cuts = LayerPlanner.plan(s, transitions = false) { 30.0 }!!
            assertTrue(effect, full.layers.size > 1 || full.layers.single().segs.any { it.hasFx || it.ramp != null })
            // one layer, the clips as they lie: no overlap layer, no transition window, no ramp, no freeze frame
            assertEquals(effect, 1, cuts.layers.size)
            val segs = cuts.layers.single().segs
            assertTrue(effect, segs.none { it.hasFx || it.ramp != null })
            val clips = s.sequence!!.video[0].clips.filter { it.text == null }.sortedBy { it.start }
            assertEquals(effect, clips.map { it.start to it.end }, segs.map { it.start to it.end })
        }
    }

    @Test fun theWindowsAreTheTransitionsBetweenTwoClips() {
        val s = snap("whip")
        val x = s.sequence!!.video[0].transitions.single()
        assertEquals(listOf(x.start..x.end), RenderCache.transitionWindows(s))
    }

    private fun chunk(a: Double, b: Double) = RenderCache.Chunk(a, b, "$a-$b", heavy = true)

    @Test fun aTransitionComesInOnlyWhenAllOfItIsReady() {
        // a transition 4.5–5.5 split at its edit point, and plain chunks on either side
        val before = chunk(0.0, 4.5); val first = chunk(4.5, 5.0); val second = chunk(5.0, 5.5); val after = chunk(5.5, 9.0)
        val all = listOf(before, first, second, after)
        val w = listOf(4.5..5.5)
        // half of it ready: none of it (half rendered and half a cut would jump); the plain chunks still play rendered
        assertEquals(listOf(before, after), RenderCache.playable(all, w) { it != second })
        // all of it ready: in
        assertEquals(all, RenderCache.playable(all, w) { true })
        // nothing ready: nothing
        assertEquals(emptyList<RenderCache.Chunk>(), RenderCache.playable(all, w) { false })
    }

    @Test fun anEmptyPlanStaysAsItIs() {
        assertNull(LayerPlanner.plan(Snapshot.parse(JSONObject("{\"fps\":25,\"sequence\":null,\"media\":[]}")), transitions = false) { 30.0 })
    }
}
