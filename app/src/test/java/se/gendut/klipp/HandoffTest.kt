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

    @Test fun aClipWithATransitionIsLeftToTheMainPlayer() {
        val fx = se.gendut.klipp.engine.SegFx(se.gendut.klipp.engine.TransitionType.WHIP, true, false, 4.5, 5.5, 5.0, false)
        val base = Layer("V1", listOf(seg(0.0, 5.0, 1), seg(5.0, 3.0, 2).copy(windows = listOf(fx))))
        assertNull(Handoff.layer(base) { true })
    }

    @Test fun aTransitionShowingOnePictureAtATimeIsCoveredWithItsGeometry() {
        // zoom, rotate, vertical whip, dips, light leak: the main player switches clips at the edit point (it froze 0.1–0.2 s
        // there) – the layer covers it, drawing the transition itself
        for (type in se.gendut.klipp.engine.TransitionType.entries.filter { it.sequential }) {
            val fx = se.gendut.klipp.engine.SegFx(type, true, false, 4.5, 5.5, 5.0, false)
            val base = Layer("V1", listOf(seg(0.0, 5.0, 1), seg(5.0, 3.0, 2).copy(windows = listOf(fx))))
            val h = Handoff.layer(base) { true }
            assertEquals(type.name, listOf(5.0), h?.segs?.map { it.start })
            assertEquals(listOf(fx), h!!.segs[0].windows)
            assertEquals(base.segs[1].geomAt(5.2), h.segs[0].geomAt(5.2))
        }
        // with an overlap layer (whip, push): left to the main player and the overlap (a third video otherwise)
        for (type in listOf(se.gendut.klipp.engine.TransitionType.PUSH, se.gendut.klipp.engine.TransitionType.WHIP)) {
            val fx = se.gendut.klipp.engine.SegFx(type, true, false, 4.5, 5.5, 5.0, false)
            assertNull(Handoff.layer(Layer("V1", listOf(seg(0.0, 5.0, 1), seg(5.0, 3.0, 2).copy(windows = listOf(fx))))) { true })
        }
    }
}
