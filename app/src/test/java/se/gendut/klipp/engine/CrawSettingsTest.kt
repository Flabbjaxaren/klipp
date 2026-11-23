package se.gendut.klipp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The CRAW development's colour inputs: the gamut matrix and the log curve the native side samples. */
class CrawSettingsTest {
    @Test fun xyzToGamutInvertsEachLogsGamut() {
        for (log in CrawSettings.LOGS) {
            val m = CrawSettings(log).xyzToGamut()
            val g = log.gamut
            for (r in 0..2) for (c in 0..2) {
                val v = (0..2).sumOf { k -> m[r * 3 + k].toDouble() * g[k * 3 + c] }
                assertEquals("${log.key} [$r][$c]", if (r == c) 1.0 else 0.0, v, 1e-5)
            }
        }
    }

    /** The table is what craw_jni.cpp indexes: (v + 0.0625) * 4096 up to 8.0 (index 33 024), then 64 per unit to 128. */
    @Test fun logCurveIsTheInverseOfTheLogsDecoding() {
        for (log in CrawSettings.LOGS) {
            val lut = CrawSettings(log).logLut()
            assertEquals(33_024 + 120 * 64 + 1, lut.size)
            for (linear in listOf(0.0f, 0.01f, 0.18f, 0.45f, 1f, 4f, 7.9f)) {
                val i = ((linear + .0625f) * 4096).toInt()
                val back = OpenDrt.oetf(lut[i], log.oetf)
                assertTrue("${log.key} $linear -> ${lut[i]} -> $back", abs(back - (i / 4096f - .0625f)) < 2e-3f * maxOf(1f, linear))
            }
            // monotonic: brighter light never gives a lower code value
            for (i in 1 until lut.size) assertTrue("${log.key} at $i", lut[i] >= lut[i - 1])
        }
    }

    @Test fun highQualityRaisesTheBitRateUpToTheEncodersTop() {
        // 4K at 30: standard stops at 100 Mbit/s, high at the encoder's top (160 on the OnePlus)
        assertEquals(100_000_000, CrawSettings.bitRate(4080, 3064, 30.0, false, 160_000_000))
        assertEquals(160_000_000, CrawSettings.bitRate(4080, 3064, 30.0, true, 160_000_000))
        // 1080p at 30: 21.8 Mbit/s, high 1.6 times that
        assertEquals(21_772_800, CrawSettings.bitRate(1920, 1080, 30.0, false, 160_000_000))
        assertEquals(34_836_480, CrawSettings.bitRate(1920, 1080, 30.0, true, 160_000_000))
    }
}
