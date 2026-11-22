package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.Reframe

class ReframeTest {
    private val wide = 16f / 9

    @Test fun landscapeInPortraitFillsTheFrame() {
        val p = Reframe.plan(listOf(Triple(0.0, 0.5f, 0.5f), Triple(2.0, 0.5f, 0.5f)), wide, 1080, 1920, { it })!!
        // 1080 wide at 100 % → 607.5 high; filling 1920 takes 316 %
        assertEquals(1920 / 607.5 * 100, p.scale, 0.01)
        // a face in the middle: the clip stays centred, and a still face needs only two keyframes
        assertEquals(2, p.keys.size)
        assertTrue(p.keys.all { kotlin.math.abs(it.second - 540) < 0.5 && kotlin.math.abs(it.third - 960) < 0.5 })
    }

    @Test fun theFaceIsPannedToTheMiddleButNoEdgeShows() {
        val dw = 1080 * (1920 / 607.5)
        val p = Reframe.plan(listOf(Triple(0.0, 0.8f, 0.5f), Triple(3.0, 0.8f, 0.5f)), wide, 1080, 1920, { it }, sigma = 0.0)!!
        assertEquals(540 + dw * (0.5 - 0.8), p.keys.first().second, 0.5)
        // a face at the far left: the clip's left edge stays at the frame's edge
        val q = Reframe.plan(listOf(Triple(0.0, 0.02f, 0.5f), Triple(3.0, 0.02f, 0.5f)), wide, 1080, 1920, { it }, sigma = 0.0)!!
        assertEquals(dw / 2, q.keys.first().second, 0.5)
    }

    @Test fun aSteadyPanNeedsFewKeyframesAndFollowsTheTimeline() {
        val c = (0..90).map { Triple(10.0 + it / 30.0, 0.3f + 0.4f * it / 90, 0.5f) }
        val p = Reframe.plan(c, wide, 1080, 1920, { 100 + (it - 10.0) }, sigma = 0.0)!!
        assertTrue("${p.keys.size} keyframes", p.keys.size <= 3)
        assertEquals(100.0, p.keys.first().first, 1e-6)
        assertEquals(103.0, p.keys.last().first, 0.05)
    }

    @Test fun aFaceOffCentreVerticallyKeepsTheFilledAxisStill() {
        // the clip fills the height exactly: y can't move (the limits meet – rounding must not throw)
        val p = Reframe.plan(listOf(Triple(0.0, 0.5f, 0.2f), Triple(1.0, 0.5f, 0.3f)), wide, 1080, 1920, { it })!!
        assertTrue(p.keys.all { kotlin.math.abs(it.third - 960) < 1e-6 })
    }

    @Test fun nothingToFollow() = assertNull(Reframe.plan(emptyList(), wide, 1080, 1920, { it }))
}
