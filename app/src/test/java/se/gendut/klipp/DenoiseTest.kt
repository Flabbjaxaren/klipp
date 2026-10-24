package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.Denoise
import se.gendut.klipp.engine.GradeNode
import kotlin.math.abs
import kotlin.math.sqrt

/** Noise reduction: flat areas smooth out, an edge stays sharp; saved with the node. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DenoiseTest {
    private val w = 64
    private val h = 32
    /** Left half dark, right half bright, with noise (σ ≈ 0.02 per channel). */
    private val img = FloatArray(w * h * 3).also { a ->
        val rnd = java.util.Random(4)
        for (y in 0 until h) for (x in 0 until w) for (c in 0..2) a[(y * w + x) * 3 + c] = (if (x < w / 2) 0.25f else 0.75f) + rnd.nextGaussian().toFloat() * 0.02f
    }

    private fun sd(a: FloatArray, x0: Int, x1: Int): Float {
        val v = ArrayList<Float>()
        for (y in 4 until h - 4) for (x in x0 until x1) v += a[(y * w + x) * 3 + 1]
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size).toFloat()
    }

    @Test fun smoothsFlatAreasAndKeepsTheEdge() {
        val out = Denoise.apply(img, w, h, 0.5f)
        assertTrue("noise down: ${sd(out, 4, 28)} vs ${sd(img, 4, 28)}", sd(out, 4, 28) < sd(img, 4, 28) * 0.5f)
        // across the edge (0.25 → 0.75) the two sides don't blend
        val y = h / 2
        assertTrue(abs(out[(y * w + w / 2 - 1) * 3 + 1] - 0.25f) < 0.05f)
        assertTrue(abs(out[(y * w + w / 2) * 3 + 1] - 0.75f) < 0.05f)
    }

    @Test fun strongerSmoothsMore() {
        assertTrue(sd(Denoise.apply(img, w, h, 1f), 4, 28) < sd(Denoise.apply(img, w, h, 0.1f), 4, 28))
    }

    @Test fun savedWithTheNode() {
        val n = GradeNode(3, denoise = 0.4f)
        assertEquals(0.4f, GradeNode.fromJson(n.toJson()).denoise, 1e-6f)
        assertTrue("a node with only noise reduction does something", !n.isNeutral)
        assertTrue(n.params().neighbors)
        assertEquals(0f, GradeNode.fromJson(GradeNode(3).toJson()).denoise, 0f)
    }
}
