package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.Drt
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.GradeNode

/**
 * HLG no longer needs every graded clip to end in OpenDRT: the others (a film or a LUT last) go in as SDR, converted
 * like ungraded clips. The note under the export's choices says what HLG will make of the grades.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HlgSdrNoteTest {
    private val drt = ClipGrade(listOf(GradeNode(1, "Ut", drt = Drt())))
    private val film = ClipGrade(listOf(GradeNode(1, "Film", film = Film())))
    private val sat = ClipGrade(listOf(GradeNode(1, "A", sat = 1.2f)))

    @Test fun everyGradeEndsInOpenDrtNoNote() = assertNull(hlgSdrNote(listOf(drt, drt)))

    @Test fun noOpenDrtAtAllSaysItIsSdrInHlg() =
        assertEquals(Str.t(R.string.app_hlg_none_graded), hlgSdrNote(listOf(film)))

    @Test fun nothingGradedIsSdrInHlgToo() = assertEquals(Str.t(R.string.app_hlg_none_graded), hlgSdrNote(emptyList()))

    @Test fun aFilmClipBesideOpenDrtIsCountedWithItsReason() {
        val n = hlgSdrNote(listOf(drt, film, drt))!!
        assertTrue(n, n.startsWith("1 "))
        assertTrue(n, n.contains("75 %"))
        assertTrue(n, n.contains(Str.t(R.string.app_hlg_no_drt, "Film")))
    }

    @Test fun anOpenDrtNodeAtHalfMixSaysSo() {
        val half = ClipGrade(listOf(GradeNode(1, "Ut", drt = Drt(), mix = 0.5f)))
        val n = hlgSdrNote(listOf(drt, half))!!
        assertTrue(n, n.contains(Str.t(R.string.app_hlg_mix)))
        assertTrue(n, n.contains("100 %"))
    }

    @Test fun twoClipsWithoutOpenDrt() {
        val n = hlgSdrNote(listOf(drt, film, sat))!!
        assertTrue(n, n.startsWith("2 "))
    }
}
