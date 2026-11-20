package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.HoldVideos
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The freeze frame video's size: the preview's, never above the image's; at full resolution (0) the image's own. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HoldVideosTest {
    @Test fun aFullResolutionPreviewUsesTheImagesSize() {
        assertEquals(1080, HoldVideos.shortFor(1080, 2160))
        assertEquals(720, HoldVideos.shortFor(1080, 720))
        assertEquals(2160, HoldVideos.shortFor(0, 2160))
        assertEquals(1080, HoldVideos.shortFor(0, null))
        assertEquals(1080, HoldVideos.shortFor(1080, null))
    }
}
