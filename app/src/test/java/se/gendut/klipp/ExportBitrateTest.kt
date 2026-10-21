package se.gendut.klipp

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.ExportPresets
import se.gendut.klipp.engine.ExportSettings

class ExportBitrateTest {
    @Test fun autoGetsAFixedBitrateForTheOutputFormat() {
        // Auto: Klipp's Standard for the file's size and the project's frame rate (not the encoder's guess from the source)
        val auto = ExportSettings().videoMbps(3840, 2160, 25.0)
        assertEquals(ExportPresets.mbps(ExportPresets.STANDARD, 3840, 2160, 25.0, MimeTypes.VIDEO_H264), auto, 1e-9)
        println("Auto 4K25 H.264: $auto Mbit/s")
        assertTrue("4K gets a reasonable bitrate", auto > 30.0)
        // lower resolution and HEVC: less
        assertTrue(ExportSettings(height = 1080).videoMbps(3840, 2160, 25.0) < auto)
        assertTrue(ExportSettings(codec = MimeTypes.VIDEO_H265).videoMbps(3840, 2160, 25.0) < auto)
        // a custom choice applies as is
        assertEquals(17.0, ExportSettings(bitrateMbps = 17f).videoMbps(3840, 2160, 25.0), 1e-9)
    }
}
