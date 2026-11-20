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

/** A node's film turned off keeps its settings, and they come back with it (found on the phone: on gave the default film). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class FilmOffTest {
    @org.junit.Test fun offKeepsTheFilmAndItSurvivesSaving() {
        val f = se.gendut.klipp.engine.Film(film = "kodak_vision3_500t", print = "kodak_2383")
        val off = se.gendut.klipp.engine.GradeNode(id = 1).copy(film = null, filmOff = f)
        val back = se.gendut.klipp.engine.GradeNode.fromJson(off.toJson())
        org.junit.Assert.assertNull(back.film)
        org.junit.Assert.assertEquals("kodak_vision3_500t", back.filmOff?.film)
        org.junit.Assert.assertTrue("off is neutral", back.isNeutral)
    }
}
