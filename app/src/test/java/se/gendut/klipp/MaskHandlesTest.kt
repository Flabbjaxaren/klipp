package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.LayerMask
import se.gendut.klipp.engine.MaskKey
import se.gendut.klipp.engine.fromFrame
import se.gendut.klipp.engine.toFrame

/** The mask's handles on the picture: a handle moves its edges (the opposite stays), inside moves the shape; keyframes and tracking are kept. */
class MaskHandlesTest {
    private val m = LayerMask(cx = 0.5f, cy = 0.5f, w = 0.4f, h = 0.2f)

    @Test fun aCornerMovesItsTwoEdges() {
        // the top left corner to (0.2, 0.3): right and bottom stay at 0.7 and 0.6
        val r = maskDragged(m, -1, -1, 0.2f, 0.3f, 0f, 0f)
        assertEquals(0.2f, r.cx - r.w / 2f, 1e-5f); assertEquals(0.7f, r.cx + r.w / 2f, 1e-5f)
        assertEquals(0.3f, r.cy - r.h / 2f, 1e-5f); assertEquals(0.6f, r.cy + r.h / 2f, 1e-5f)
    }

    @Test fun anEdgeMovesOnlyItselfAndNeverPastTheOther() {
        val r = maskDragged(m, 1, 0, 0.9f, 0.99f, 0f, 0f)
        assertEquals(0.3f, r.cx - r.w / 2f, 1e-5f); assertEquals(0.9f, r.cx + r.w / 2f, 1e-5f)
        assertEquals(m.h, r.h, 1e-6f)
        // dragged across the left edge: stays 2 % wide
        assertEquals(0.02f, maskDragged(m, 1, 0, 0.1f, 0.5f, 0f, 0f).w, 1e-5f)
    }

    @Test fun insideMovesTheShape() {
        val r = maskDragged(m, 0, 0, 0f, 0f, 0.1f, -0.05f)
        assertEquals(0.6f, r.cx, 1e-6f); assertEquals(0.45f, r.cy, 1e-6f); assertEquals(m.w, r.w, 0f)
    }

    @Test fun aTrackedMaskGetsAKeyAtThePlayheadAndKeepsTheOthers() {
        val tracked = m.copy(keys = listOf(MaskKey(1.0, 0.4f, 0.5f, 0.4f, 0.2f, tracked = true), MaskKey(2.0, 0.6f, 0.5f, 0.4f, 0.2f, tracked = true)))
        val r = tracked.withValues(maskDragged(tracked.at(2.0), 0, 0, 0f, 0f, 0.1f, 0f), 2.0, 0.02)
        assertEquals(2, r.keys.size)
        assertEquals(0.7f, r.keys[1].cx, 1e-6f)
        assertEquals("the other keyframe untouched", tracked.keys[0], r.keys[0])
    }

    @Test fun theOutlineFollowsTheShape() {
        assertEquals(4, maskOutline(m).size)
        val e = maskOutline(m.copy(shape = LayerMask.ELLIPSE), 4)
        assertEquals(0.7f, e[0].first, 1e-5f); assertEquals(0.6f, e[1].second, 1e-5f)
    }

    /** The dashed feather lines lie where the mask actually starts to fade and where it has faded out – not further out. */
    @Test fun theFeatherLinesMatchTheSoftEdge() {
        val aspect = 16f / 9f
        for (shape in listOf(LayerMask.RECT, LayerMask.ELLIPSE)) {
            val s = m.copy(shape = shape, feather = 0.1f)
            val (inner, outer) = s.featherBounds(aspect)
            for ((u, v) in maskOutline(outer, 36)) assertEquals("outer, shape $shape at ($u, $v)", 0f, s.coverage(u, v, aspect), 0.02f)
            for ((u, v) in maskOutline(inner, 36)) assertEquals("inner, shape $shape at ($u, $v)", 1f, s.coverage(u, v, aspect), 0.02f)
            // and halfway between them it is half
            val mid = s.copy(w = (inner.w + outer.w) / 2f, h = (inner.h + outer.h) / 2f)
            for ((u, v) in maskOutline(mid, 36)) assertEquals("middle, shape $shape", 0.5f, s.coverage(u, v, aspect), 0.08f)
        }
    }

    /** A 4:3 clip in a portrait 9:16 sequence lies in a band across the middle: the outline is drawn there, not over the whole frame. */
    @Test fun aClipOfAnotherShapeIsFittedInsideTheFrame() {
        val c = se.gendut.klipp.engine.ClipSnap(1, 1, "c", 0.0, 1.0, 0.0, 1.0, null)
        val (fw, fh) = se.gendut.klipp.engine.fitIn(4f / 3f, 1080, 1920)
        assertEquals(1080f, fw, 0.01f); assertEquals(810f, fh, 0.01f)
        // the clip's top edge: at the top of the band, (1920 − 810) / 2 px down
        val top = c.toFrame(0.5f, 0f, 1080, 1920, 4f / 3f)
        assertEquals(555f / 1920f, top[1], 1e-5f)
        // and back
        val back = c.fromFrame(top[0], top[1], 1080, 1920, 4f / 3f)
        assertEquals(0.5f, back[0], 1e-5f); assertEquals(0f, back[1], 1e-5f)
        // without a shape: the whole frame, as before
        assertEquals(0f, c.toFrame(0.5f, 0f, 1080, 1920)[1], 1e-6f)
    }
}
