package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.gendut.klipp.engine.Layer
import se.gendut.klipp.engine.Ramp
import se.gendut.klipp.engine.Seg
import se.gendut.klipp.engine.SegSrc

/** The handoff layer: prewarmed pieces at every cut in the main layer – not at the start, not in fades. */
class HandoffTest {
    private fun seg(start: Double, dur: Double, item: Long, ramp: Ramp? = null) = Seg(start, dur, SegSrc.Media(item, 2.0, 1.0), null, ramp)

    @Test fun coversEachCutButNotStartOrFades() {
        val base = Layer("V1", listOf(
            seg(0.0, 5.0, 1),
            seg(5.0, 3.0, 2),
            seg(8.0, 0.4, 3),
            seg(8.4, 2.0, 4, Ramp(8.4, 10.4, 1f, 0f)),
        ))
        val h = Handoff.layer(base) { true }!!
        assertEquals(listOf(5.0, 8.0), h.segs.map { it.start })
        // at most SEC, never longer than the piece
        assertEquals(listOf(Handoff.SEC, 0.4), h.segs.map { it.dur })
        // the same source as in the main layer (the same picture)
        assertEquals(base.segs[1].src, h.segs[0].src)
    }

    @Test fun skipsUnusableAndEmpty() {
        val base = Layer("V1", listOf(seg(0.0, 5.0, 1), seg(5.0, 3.0, 2)))
        assertNull(Handoff.layer(base) { false })
        assertNull(Handoff.layer(Layer("V1", listOf(seg(0.0, 5.0, 1)))) { true })
    }

    @Test fun aPreRenderedPieceIsLeftToTheMainPlayer() {
        // (its id is below the render cache's base; the layer waited on a clip's frames there and covered the whole piece)
        val chunk = -(1L shl 52) - 12345L
        val base = Layer("V1", listOf(seg(0.0, 5.0, 1), seg(5.0, 0.6, chunk), seg(5.6, 3.0, 2)))
        assertEquals(listOf(5.6), Handoff.layer(base) { true }!!.segs.map { it.start })
    }

    @Test fun aClipWithATransitionIsLeftToTheMainPlayer() {
        val fx = se.gendut.klipp.engine.SegFx(se.gendut.klipp.engine.TransitionType.WHIP, true, false, 4.5, 5.5, 5.0, false)
        val base = Layer("V1", listOf(seg(0.0, 5.0, 1), seg(5.0, 3.0, 2).copy(windows = listOf(fx))))
        assertNull(Handoff.layer(base) { true })
    }

    @Test fun withOneRendererOnlyACutAfterAGapOrAFreezeFrameIsCovered() {
        // (one video renderer across the items, see LiveCuts: a clip straight after a clip or a pre-rendered piece is on
        // time in the main player; after an empty stretch or a freeze frame the video renderer starts again)
        val chunk = -(1L shl 52) - 12345L
        val base = Layer("V1", listOf(
            seg(0.0, 2.0, 1),
            seg(2.0, 2.0, 2),                                   // after a clip
            seg(4.0, 1.0, chunk),                               // a pre-rendered piece (never covered)
            seg(5.0, 2.0, 3),                                   // after the piece
            seg(7.6, 1.0, 4),                                   // after a gap
            Seg(8.6, 0.4, SegSrc.Still(4, 3.0), null),          // a freeze frame (never covered)
            seg(9.0, 2.0, 5),                                   // after the freeze frame
        ))
        assertEquals(listOf(7.6, 9.0), Handoff.layer(base, oneRenderer = true) { true }!!.segs.map { it.start })
        // a gap played as video (LiveCuts.gapAsVideo): the clip after it is on time too; the freeze frame's still isn't
        assertEquals(listOf(9.0), Handoff.layer(base, oneRenderer = true, gapPlayed = { _, _ -> true }) { true }!!.segs.map { it.start })
        // a freeze frame played as video (HoldVideos) too: nothing left to cover
        assertNull(Handoff.layer(base, oneRenderer = true, gapPlayed = { _, _ -> true }, stillPlayed = { true }) { true })
        // without: every cut that isn't a piece
        assertEquals(listOf(2.0, 5.0, 7.6, 9.0), Handoff.layer(base) { true }!!.segs.map { it.start })
        // a timeline that begins with a gap: its first clip is covered
        assertEquals(listOf(0.5), Handoff.layer(Layer("V1", listOf(seg(0.5, 2.0, 1), seg(2.5, 2.0, 2))), oneRenderer = true) { true }!!.segs.map { it.start })
        assertNull(Handoff.layer(Layer("V1", listOf(seg(0.0, 2.0, 1), seg(2.0, 2.0, 2))), oneRenderer = true) { true })
    }

    @Test fun aSegmentWithATransitionWindowIsNeverCovered() {
        // transitions between two clips reach playback only pre-rendered (RenderCache.playPlan) – the layer never draws one
        for (type in se.gendut.klipp.engine.TransitionType.entries) {
            val fx = se.gendut.klipp.engine.SegFx(type, true, false, 4.5, 5.5, 5.0, false)
            assertNull(type.name, Handoff.layer(Layer("V1", listOf(seg(0.0, 5.0, 1), seg(5.0, 3.0, 2).copy(windows = listOf(fx))))) { true })
        }
    }
}
