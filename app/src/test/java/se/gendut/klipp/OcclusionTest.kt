package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.Occlusion

/** When an upper layer hides the picture below (its film is then skipped in the preview), and how long a mark lasts. */
class OcclusionTest {
    private fun covers(vw: Double = 3840.0, vh: Double = 2160.0, scale: Double = 1.0, dx: Double = 0.0, dy: Double = 0.0, rot: Double = 0.0,
                       alpha: Float = 1f, plain: Boolean = true, noAlpha: Boolean = true, geom: Boolean = true) =
        Occlusion.covers(1920.0, 1080.0, vw, vh, scale, dx, dy, rot, alpha, plain, noAlpha, geom)

    @Test fun fullFrameOpaqueCovers() {
        assertTrue(covers())
        assertTrue("zoomed in and moved a little: still covers", covers(scale = 1.2, dx = 50.0, dy = -30.0))
    }

    @Test fun anythingSeeThroughDoesNot() {
        assertFalse("smaller (picture in picture)", covers(scale = 0.8))
        assertFalse("moved off the edge", covers(dx = 10.0))
        assertFalse("rotated", covers(rot = 3.0))
        assertFalse("fading", covers(alpha = 0.98f))
        assertFalse("blend mode", covers(plain = false))
        assertFalse("key or mask", covers(noAlpha = false))
        assertFalse("transition geometry", covers(geom = false))
        assertFalse("other shape: letterboxed (2.39:1 in 16:9)", covers(vw = 2592.0, vh = 1080.0))
        assertFalse("unknown size", covers(vw = 0.0))
    }

    /** Two 2.4:1 clips in a 16:9 sequence: the upper one hides the lower one (both letterboxed the same). */
    @Test fun sameShapeLayerHidesTheClipBelow() {
        val cover = Occlusion.box(3840.0, 2160.0, 2592.0, 1080.0, 1.0, 0.0, 0.0, 0.0)
        assertTrue(cover.contains(Occlusion.box(3840.0, 2160.0, 6240.0, 2600.0, 1.0, 0.0, 0.0, 0.0)))
        assertFalse("16:9 below sticks out above and below", cover.contains(Occlusion.box(3840.0, 2160.0, 3840.0, 2160.0, 1.0, 0.0, 0.0, 0.0)))
        assertFalse("below moved up", cover.contains(Occlusion.box(3840.0, 2160.0, 2592.0, 1080.0, 1.0, 0.0, -40.0, 0.0)))
        assertFalse("below rotated", cover.contains(Occlusion.box(3840.0, 2160.0, 2592.0, 1080.0, 1.0, 0.0, 0.0, 5.0)))
        assertTrue("below smaller (picture in picture under it)", cover.contains(Occlusion.box(3840.0, 2160.0, 2592.0, 1080.0, 0.5, 300.0, 100.0, 10.0)))
    }

    @Test fun markLapsesUnlessRefreshed() {
        Occlusion.clear()
        Occlusion.hide(7L, nowMs = 1000L)
        assertTrue(Occlusion.hidden(7L, nowMs = 1000L + Occlusion.HOLD_MS - 1))
        assertFalse(Occlusion.hidden(7L, nowMs = 1000L + Occlusion.HOLD_MS))
        assertFalse(Occlusion.hidden(8L, nowMs = 1000L))
    }

    @Test fun mainFramesCount() {
        val before = Occlusion.mainFrames(42L)
        repeat(3) { Occlusion.sawMain(42L) }
        assertEquals(before + 3, Occlusion.mainFrames(42L))
    }
}
