package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.GradeNode

/** Film density: subtractive saturation – richer colors with less light, gray and exposure untouched; saved with the node. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilmDensityTest {
    private fun dense(r: Float, g: Float, b: Float, d: Float) = floatArrayOf(r, g, b).also { GradeNode.density(it, d) }
    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
    /** HSV saturation. */
    private fun sat(c: FloatArray): Float { val mx = c.max(); return if (mx <= 0f) 0f else (mx - c.min()) / mx }

    @Test fun grayStays() {
        for (v in floatArrayOf(0f, 0.05f, 0.4f, 1f)) {
            val c = dense(v, v, v, 1f)
            for (i in 0..2) assertEquals(v, c[i], 1e-6f)
        }
    }

    @Test fun richerAndDarkerNeverBrighter() {
        val colors = listOf(floatArrayOf(0.8f, 0.6f, 0.5f), floatArrayOf(0.2f, 0.5f, 0.9f), floatArrayOf(0.3f, 0.7f, 0.2f), floatArrayOf(1f, 0.1f, 0.1f))
        for (c0 in colors) {
            val c = dense(c0[0], c0[1], c0[2], 0.6f)
            for (i in 0..2) assertTrue("no channel gains light", c[i] <= c0[i] + 1e-6f)
            assertEquals("the strongest channel stays", c0.max(), c.max(), 1e-6f)
            assertTrue("darker", luma(c) < luma(c0))
            assertTrue("more saturated", sat(c) > sat(c0))
        }
    }

    @Test fun moreDensityMoreEffect() {
        val a = dense(0.8f, 0.6f, 0.5f, 0.3f); val b = dense(0.8f, 0.6f, 0.5f, 0.9f)
        assertTrue(luma(b) < luma(a) && sat(b) > sat(a))
    }

    @Test fun sameOnEveryExposure() {
        // only the channels' ratios count: half the light gives half the result (above the dark fade)
        val hi = dense(0.8f, 0.6f, 0.5f, 0.7f); val lo = dense(0.4f, 0.3f, 0.25f, 0.7f)
        for (i in 0..2) assertEquals(hi[i] / 2f, lo[i], 1e-5f)
    }

    @Test fun fadesOutInTheBlacks() {
        val c = dense(0.015f, 0.01f, 0.012f, 1f)
        assertEquals(0.01f, c[1], 1e-7f)
    }

    @Test fun neutralAtZeroAndSaved() {
        val c = dense(0.8f, 0.6f, 0.5f, 0f)
        assertEquals(0.6f, c[1], 0f)
        assertTrue(GradeNode(1).isNeutral)
        assertTrue(!GradeNode(1, density = 0.5f).isNeutral)
        assertEquals(0.5f, GradeNode.fromJson(GradeNode(1, density = 0.5f).toJson()).density, 1e-6f)
        assertEquals(0f, GradeNode.fromJson(GradeNode(1).toJson()).density, 0f)
    }

    @Test fun inTheNode() {
        val px = floatArrayOf(0.8f, 0.6f, 0.5f)
        GradeNode.apply(GradeNode(1, density = 0.6f).params(), px)
        assertEquals(dense(0.8f, 0.6f, 0.5f, 0.6f).toList(), px.toList())
    }
}
