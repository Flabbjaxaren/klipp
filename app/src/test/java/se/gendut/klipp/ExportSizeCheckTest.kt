package se.gendut.klipp

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.Exporter

/** The message afterwards when the encoder made the file at another size than asked for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "sv")
class ExportSizeCheckTest {
    @Test fun reportsOnlyARealChange() {
        // encoder alignment (1080 → 1088) and a turned frame are the same size
        assertNull(Exporter.sizeMismatch(1920 to 1080, 1920, 1088))
        assertNull(Exporter.sizeMismatch(1080 to 1920, 1920, 1080))
        assertNull(Exporter.sizeMismatch(null, 640, 360))
        // 4K asked for, 720p came out
        val m = Exporter.sizeMismatch(3840 to 2160, 1280, 720)
        assertNotNull(m)
        assertTrue(m!!, m.contains("3840×2160"))
    }
}
