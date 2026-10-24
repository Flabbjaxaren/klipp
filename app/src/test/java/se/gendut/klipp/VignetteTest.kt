package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.GradeNode

/** The vignette preset: the middle stays, the edges and corners darken gradually. */
class VignetteTest {
    private fun at(n: GradeNode, x: Float, y: Float): Float {
        val c = floatArrayOf(0.5f, 0.5f, 0.5f)
        GradeNode.apply(n.params(), c, x, y)
        return c[1]
    }

    @Test fun darkensTowardTheCorners() {
        val n = vignette(GradeNode(1))
        val mid = at(n, 0.5f, 0.5f)
        val half = at(n, 0.85f, 0.5f)
        val edge = at(n, 1f, 0.5f)
        val corner = at(n, 1f, 1f)
        assertEquals("the middle is untouched", 0.5f, mid, 0.002f)
        assertTrue("$mid > $half > $edge > $corner", mid > half && half > edge && edge > corner)
        assertTrue("the edge darkens: $edge", edge < 0.47f)
        assertTrue("the corner is clearly darker: $corner", corner < 0.4f)
    }

    @Test fun keepsAGainAlreadySet() {
        val n = GradeNode(1).let { it.copy(gain = it.gain.copy(master = 0.2f)) }
        assertEquals(0.2f, vignette(n).gain.master, 0f)
    }
}
