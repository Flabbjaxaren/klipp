package se.gendut.klipp.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MotionCam develop's colour: the camera → output gamut matrix and the placeholder forward matrix it ignores. */
class McrawColorTest {
    // what the OnePlus 12 writes as forwardMatrix1/2: sRGB → XYZ D50, quantized to 1/128 (not a calibration)
    private val onePlusFm = floatArrayOf(0.4375f, 0.3828125f, 0.140625f, 0.21875f, 0.71875f, 0.0625f, 0.015625f, 0.09375f, 0.7109375f)
    // its colour matrices are calibrated (XYZ → camera, D65)
    private val onePlusCm = floatArrayOf(0.9848f, -0.3684f, -0.1011f, -0.2682f, 1.1295f, 0.1152f, 0.0588f, 0.0403f, 0.5348f)
    private val neutral = floatArrayOf(0.52f, 1f, 0.61f)
    // a forward matrix as a calibrated camera writes it (rows sum to D50)
    private val realFm = floatArrayOf(0.6f, 0.25f, 0.1142f, 0.25f, 0.85f, -0.1f, 0.03f, -0.15f, 0.9449f)

    @Test fun theSrgbPlaceholderIsRecognized() {
        assertTrue(McrawConvert.isPlaceholderForwardMatrix(onePlusFm))
        // the exact sRGB → XYZ D50 matrix, and the same at another scale (rows are compared normalized to D50)
        val exact = floatArrayOf(0.4360747f, 0.3850649f, 0.1430804f, 0.2225045f, 0.7168786f, 0.0606169f, 0.0139322f, 0.0971045f, 0.7141733f)
        assertTrue(McrawConvert.isPlaceholderForwardMatrix(exact))
        assertTrue(McrawConvert.isPlaceholderForwardMatrix(FloatArray(9) { exact[it] * 1.7f }))
    }

    @Test fun aRealForwardMatrixIsNotAPlaceholder() {
        assertFalse(McrawConvert.isPlaceholderForwardMatrix(realFm))
        assertFalse(McrawConvert.isPlaceholderForwardMatrix(FloatArray(9)))
        assertFalse(McrawConvert.isPlaceholderForwardMatrix(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertFalse(McrawConvert.isPlaceholderForwardMatrix(onePlusFm.copyOf(8)))
    }

    @Test fun aPlaceholderForwardMatrixFallsBackToTheColorMatrix() {
        val out = OpenDrt.M.SGAMUT3CINE_TO_XYZ
        val withPlaceholder = McrawConvert.cameraToOut(onePlusFm, onePlusCm, neutral, out)
        val withoutFm = McrawConvert.cameraToOut(null, onePlusCm, neutral, out)
        assertArrayEquals(withoutFm, withPlaceholder, 0f)
        // a real forward matrix is used (another result than the colour matrix's)
        val withReal = McrawConvert.cameraToOut(realFm, onePlusCm, neutral, out)
        assertTrue((0 until 9).any { kotlin.math.abs(withReal[it] - withoutFm[it]) > 0.01f })
    }

    @Test fun theNeutralStaysNeutralAtUnitLuminance() {
        for (input in listOf(OpenDrt.Input.SLOG3, OpenDrt.Input.DI, OpenDrt.Input.ACESCCT, OpenDrt.Input.LOGC4)) {
            for (fm in listOf(null, realFm, onePlusFm)) {
                val m = McrawConvert.cameraToOut(fm, onePlusCm, neutral, input.gamut)
                // white-balanced grey (1, 1, 1) → equal channels …
                val o = FloatArray(3) { r -> m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2] }
                assertEquals("${input.key} $fm", o[0], o[1], 1e-4f)
                assertEquals("${input.key} $fm", o[2], o[1], 1e-4f)
                // … at Y = 1 in a D65 gamut (the colour matrix path)
                if (fm !== realFm && input != OpenDrt.Input.ACESCCT) {
                    val g = input.gamut
                    assertEquals("${input.key}", 1f, g[3] * o[0] + g[4] * o[1] + g[5] * o[2], 2e-3f)
                }
            }
        }
    }
}
