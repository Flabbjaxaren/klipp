package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.DecodeCalibration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

class DecodeCalibrationTest {
    private val truth = DecodeCalibration.PATCHES.toList()

    /** RGB → YUV with one matrix, back with another (limited range, rounded to 8 bits like a decoder). */
    private fun mismatch(c: FloatArray, enc: Pair<Double, Double>, dec: Pair<Double, Double>): FloatArray {
        val (kr, kb) = enc
        val y = kr * c[0] + (1 - kr - kb) * c[1] + kb * c[2]
        val u = (c[2] - y) / (2 * (1 - kb)); val v = (c[0] - y) / (2 * (1 - kr))
        // 8-bit codes (16–235 / 16–240)
        val yq = Math.round(16 + 219 * y) / 255.0; val uq = Math.round(128 + 224 * u) / 255.0; val vq = Math.round(128 + 224 * v) / 255.0
        val y2 = (yq * 255 - 16) / 219; val u2 = (uq * 255 - 128) / 224; val v2 = (vq * 255 - 128) / 224
        val (dr, db) = dec
        val r = y2 + 2 * (1 - dr) * v2; val b = y2 + 2 * (1 - db) * u2; val g = (y2 - dr * r - db * b) / (1 - dr - db)
        return floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
    }

    private fun apply(m: FloatArray, d: FloatArray) = FloatArray(3) { c -> m[c * 4] * d[0] + m[c * 4 + 1] * d[1] + m[c * 4 + 2] * d[2] + m[c * 4 + 3] }

    @Test fun aMatrixMixUpIsMeasuredAndCorrected() {
        val bt709 = 0.2126 to 0.0722; val bt601 = 0.299 to 0.114; val bt2020 = 0.2627 to 0.0593
        for ((name, dec) in listOf("601" to bt601, "2020" to bt2020)) {
            val decoded = truth.map { mismatch(it, bt709, dec) }
            val f = DecodeCalibration.fit(decoded, truth)
            println("709 decoded as $name: deviation ${"%.1f".format(f.before * 255)} → ${"%.2f".format(f.after * 255)} code values")
            assertNotNull("correction for $name", f.matrix)
            assertTrue(f.before > 5.0 / 255)
            // the correction brings every patch back to the original (within rounding)
            var err = 0.0
            for ((i, d) in decoded.withIndex()) { val r = apply(f.matrix!!, d); for (c in 0..2) err = max(err, abs(r[c] - truth[i][c]).toDouble()) }
            assertTrue("remaining ${err * 255}", err < 1.5 / 255)
        }
    }

    @Test fun aMatchingDecodeIsLeftAlone() {
        val bt709 = 0.2126 to 0.0722
        val f = DecodeCalibration.fit(truth.map { mismatch(it, bt709, bt709) }, truth)
        assertNull(f.matrix)
        assertTrue(f.before < 1.5 / 255)
    }

    @Test fun anErrorAMatrixCannotExplainIsNotCorrected() {
        // a tone curve (not a matrix): no correction – it could make the image worse
        val decoded = truth.map { c -> FloatArray(3) { c[it].toDouble().pow(1.3).toFloat() } }
        val f = DecodeCalibration.fit(decoded, truth)
        assertNull(f.matrix)
        assertTrue(f.before > 5.0 / 255)
    }

    @Test fun thePatchesAreDistinctAndAwayFromClipping() {
        assertEquals(16, truth.size)
        for (c in truth) for (v in c) assertTrue(v in 0.1f..0.9f)
        assertEquals(16, truth.map { it.toList() }.distinct().size)
    }
}
