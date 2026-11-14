package se.gendut.klipp

import androidx.media3.common.C
import androidx.media3.effect.KlippYuv
import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.Headroom
import se.gendut.klipp.engine.SdrYuvCheck

/** The YUV sampling's conversion (KlippYuv), the check's expected values and the headroom files' storage. */
class SdrYuvTest {
    /** rgb = M · (s − offset) with M column-major, as the shader does. */
    private fun apply(t: Array<FloatArray>, s: FloatArray): FloatArray {
        val m = t[0]; val o = t[1]
        val d = FloatArray(3) { s[it] - o[it] }
        return FloatArray(3) { r -> m[r] * d[0] + m[3 + r] * d[1] + m[6 + r] * d[2] }
    }

    @Test fun limitedRange709At8BitsGivesTheExactValuesBeyondWhiteAndBlack() {
        val t = KlippYuv.transform(C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED, 8)
        val codes = listOf(intArrayOf(16, 128, 128), intArrayOf(235, 128, 128), intArrayOf(250, 128, 128), intArrayOf(5, 128, 128), intArrayOf(81, 90, 240), intArrayOf(145, 54, 34))
        val want = SdrYuvCheck.truth(codes, 8)
        for ((i, c) in codes.withIndex()) {
            val got = apply(t, FloatArray(3) { c[it] / 255f })
            for (k in 0..2) assertEquals("patch $i channel $k", want[i][k], got[k], 1e-4f)
        }
        // white at 1, the super-white above it, the sub-black below 0 (not clamped)
        assertEquals(1f, apply(t, floatArrayOf(235 / 255f, 128 / 255f, 128 / 255f))[1], 1e-4f)
        assertEquals((250 - 16) / 219f, apply(t, floatArrayOf(250 / 255f, 128 / 255f, 128 / 255f))[1], 1e-4f)
    }

    @Test fun tenBitsAreReadAsP010() {
        val t = KlippYuv.transform(C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED, 10)
        // a 10-bit code as the GPU returns it: code · 64 / 65535
        fun s(code: Int) = code * 64f / 65535f
        val codes = listOf(intArrayOf(64, 512, 512), intArrayOf(940, 512, 512), intArrayOf(1000, 512, 512), intArrayOf(324, 360, 960))
        val want = SdrYuvCheck.truth(codes, 10)
        for ((i, c) in codes.withIndex()) {
            val got = apply(t, FloatArray(3) { s(c[it]) })
            for (k in 0..2) assertEquals("patch $i channel $k", want[i][k], got[k], 2e-4f)
        }
    }

    @Test fun bt2020LimitedMatchesMediasOwnHdrMatrix() {
        // Media3's BT2020_LIMITED_RANGE_YUV_TO_RGB_COLOR_TRANSFORM_MATRIX (for 10-bit P010)
        val media3 = floatArrayOf(1.1689f, 1.1689f, 1.1689f, 0f, -0.1881f, 2.1502f, 1.6853f, -0.6530f, 0f)
        val m = KlippYuv.transform(C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, 10)[0]
        for (i in 0..8) assertEquals("element $i", media3[i], m[i], 2e-4f)
    }

    @Test fun fullRangeMapsTheWholeCodeRange() {
        val t = KlippYuv.transform(C.COLOR_SPACE_BT709, C.COLOR_RANGE_FULL, 8)
        assertEquals(0f, apply(t, floatArrayOf(0f, 128 / 255f, 128 / 255f))[1], 1e-5f)
        assertEquals(1f, apply(t, floatArrayOf(1f, 128 / 255f, 128 / 255f))[1], 1e-5f)
    }

    @Test fun headroomStorageKeepsLimitedVideosWholeRangeAndComesBack() {
        val s = Headroom.HI - Headroom.LO
        for (v in listOf(Headroom.LO, -0.05f, 0f, 0.5f, 1f, 1.068f, Headroom.HI)) {
            val stored = (v - Headroom.LO) / s
            assert(stored in -1e-6f..1f + 1e-6f) { "$v stored as $stored" }
            assertEquals(v, stored * s + Headroom.LO, 1e-6f)
        }
        // 8-bit codes 1 and 254 (the extremes limited-range video can hold) fit
        assert((1 - 16) / 219f >= Headroom.LO && (254 - 16) / 219f <= Headroom.HI)
        assert(Headroom.isHeadroom(android.net.Uri.EMPTY).not())
    }
}
