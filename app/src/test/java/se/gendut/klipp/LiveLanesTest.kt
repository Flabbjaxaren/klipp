package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.Layer
import se.gendut.klipp.engine.LayerPlan
import se.gendut.klipp.engine.RenderCache
import se.gendut.klipp.engine.Seg
import se.gendut.klipp.engine.SegSrc

/**
 * More video tracks in playback: each upper layer is a player and a hardware decoder, so what live playback carries is
 * limited by the layers overlapping at a moment (RenderCache.LIVE_UPPER_LAYERS), not by the tracks. Up to that many upper
 * layers the plan stays as it was; beyond, tracks share players where they don't overlap, and where too many overlap the
 * span is pre-rendered (until then the top ones are left out).
 */
class LiveLanesTest {
    private var item = 1L
    private fun seg(a: Double, b: Double) = Seg(a, b - a, SegSrc.Media(item++, 10.0, 1.0), null)
    /** Layers top first (as LayerPlanner gives them), the bottom one last. */
    private fun plan(vararg layers: List<Seg>) = LayerPlan(1920, 1080, 60.0, layers.mapIndexed { i, s -> Layer("V${layers.size - i}", s) })
    private val live = RenderCache.LIVE_UPPER_LAYERS

    @Test fun theDefaultIsThreeLiveUpperLayers() {
        assertEquals(3, RenderCache.LIVE_UPPER_LAYERS)
    }

    @Test fun uptoTheLimitNothingChanges() {
        val p = plan(listOf(seg(0.0, 5.0)), listOf(seg(1.0, 4.0)), listOf(seg(2.0, 3.0)), listOf(seg(0.0, 60.0)))
        assertSame(p, RenderCache.liveLanes(p, live))
        assertTrue(RenderCache.overloadWindows(p, live).isEmpty())
    }

    @Test fun sixTracksUsedAtDifferentTimesShareFewPlayers() {
        // V2 … V6 each with a clip, one after the other with room between: one lane is enough
        val uppers = (0 until 5).map { k -> listOf(seg(k * 10.0, k * 10.0 + 5.0)) }.reversed()
        val base = listOf(seg(0.0, 60.0))
        val p = plan(*(uppers + listOf(base)).toTypedArray())
        assertTrue(RenderCache.overloadWindows(p, live).isEmpty())
        val l = RenderCache.liveLanes(p, live)
        assertEquals(2, l.layers.size)
        assertEquals(5, l.layers[0].segs.size)
        assertSame(base, l.layers.last().segs)
    }

    @Test fun higherTracksStayOnTop() {
        // V2..V5 overlap in pairs: V2 with V3 at 0–5, V4 with V5 at 10–15 (V5 over V4, V3 over V2)
        val v2 = seg(0.0, 5.0); val v3 = seg(1.0, 4.0); val v4 = seg(10.0, 15.0); val v5 = seg(11.0, 14.0)
        val p = plan(listOf(v5), listOf(v4), listOf(v3), listOf(v2), listOf(seg(0.0, 60.0)))
        val l = RenderCache.liveLanes(p, live)
        val uppers = l.layers.dropLast(1)
        assertTrue(uppers.size <= live)
        // lane of a segment (0 = top of the list = drawn last); higher track ⇒ drawn above
        fun laneOf(s: Seg) = uppers.indexOfFirst { l2 -> l2.segs.any { it.src == s.src } }
        assertTrue(laneOf(v3) < laneOf(v2))
        assertTrue(laneOf(v5) < laneOf(v4))
    }

    @Test fun tooManyAtOnceArePreRenderedAndTheTopOnesWaitForIt() {
        // five upper tracks: more than three at once from 2 to 3.5 s
        val ups = listOf(seg(0.0, 10.0), seg(1.0, 9.0), seg(2.0, 3.5), seg(2.0, 3.0), seg(2.5, 7.0)) // V2 … V6
        val p = plan(*(ups.reversed().map { listOf(it) } + listOf(listOf(seg(0.0, 60.0)))).toTypedArray())
        val w = RenderCache.overloadWindows(p, live)
        assertEquals(listOf(2.0..3.5), w.map { it.start..it.endInclusive })
        val l = RenderCache.liveLanes(p, live)
        val uppers = l.layers.dropLast(1)
        assertTrue(uppers.size <= live)
        // at no moment more than three upper segments live
        for (t in (0 until 120).map { it * 0.1 + 0.05 }) assertTrue("at $t", uppers.count { u -> u.segs.any { it.start <= t && it.end > t } } <= live)
        // outside the overloaded span the top track plays: V6 from 3.5 s on, its source following the cut
        val v6 = uppers.flatMap { it.segs }.filter { (it.src as SegSrc.Media).item == (ups[4].src as SegSrc.Media).item }
        assertEquals(1, v6.size)
        assertEquals(3.5, v6[0].start, 1e-9)
        assertEquals(11.0, (v6[0].src as SegSrc.Media).srcIn, 1e-9)
        // V5 lies wholly in the span: left out until it is rendered
        assertTrue(uppers.flatMap { it.segs }.none { (it.src as SegSrc.Media).item == (ups[3].src as SegSrc.Media).item })
    }
}
