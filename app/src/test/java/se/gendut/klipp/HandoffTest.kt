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
}
