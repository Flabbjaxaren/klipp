package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.FdPath

/** A media file reaches the engine with its extension, so the engine streams it instead of reading it whole. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FdPathTest {
    @Test fun theExtensionComesFromTheNameOrTheType() {
        assertEquals("/proc/self/fd/57.mp4", FdPath.of(57, "VID_20261111.MP4", null))
        assertEquals("/proc/self/fd/9.mov", FdPath.of(9, "clip.mov", "video/quicktime"))
        Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("mp4", "video/mp4")
        // the photo picker's names often have none ("29180"): the type gives it
        assertEquals("/proc/self/fd/12.mp4", FdPath.of(12, "29180", "video/mp4"))
        // nothing to go by: as before
        assertEquals("/proc/self/fd/3", FdPath.of(3, "29180", null))
        // never anything odd in the path
        assertEquals("/proc/self/fd/4", FdPath.of(4, "a.mp4/../x", null))
    }
}
