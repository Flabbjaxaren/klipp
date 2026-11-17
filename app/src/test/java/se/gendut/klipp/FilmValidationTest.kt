package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.FilmFx
import se.gendut.klipp.engine.Spektra
import java.io.File

/**
 * A film node that can't be rendered is corrected when it is read, never silently dropped: a negative without a paper
 * (it has no scan table) used to make the film's setup fail and the film was off without a word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilmValidationTest {
    companion object {
        @BeforeClass @JvmStatic fun data() { Spektra.open = { File("src/main/assets/spektrafilm/$it").inputStream() } }
    }

    private fun read(json: String) = Film.fromJson(JSONObject(json))!!

    @Test fun noPrintKeyMeansTheDefaultPaper() {
        val f = read("""{"film":"kodak_portra_400","fmt":8}""")
        assertEquals(Film().print, f.print)
        FilmFx.setup(f)   // (threw before: a negative with no paper)
    }

    @Test fun aNegativeWithoutPaperGetsItsTargetPaper() {
        assertEquals("kodak_2383", read("""{"film":"kodak_vision3_500t","print":null}""").print)
        assertEquals("fujifilm_crystal_archive_typeii", read("""{"film":"fujifilm_c200","print":null}""").print)
        FilmFx.setup(read("""{"film":"kodak_vision3_500t","print":null}"""))
    }

    @Test fun anUnknownPaperOrStockIsReplaced() {
        assertEquals("kodak_portra_endura", read("""{"film":"kodak_portra_400","print":"no_such_paper"}""").print)
        val f = read("""{"film":"no_such_film","print":"kodak_2383"}""")
        assertEquals(Film().film, f.film)
        assertEquals("kodak_2383", f.print)
    }

    @Test fun aSlideFilmIsScannedWithoutPaper() {
        assertNull(read("""{"film":"fujifilm_velvia_100","print":null}""").print)
        FilmFx.setup(read("""{"film":"fujifilm_velvia_100","print":null}"""))
    }

    @Test fun aValidNodeIsUnchangedAndRoundTrips() {
        val f = Film(film = "kodak_vision3_250d", print = "kodak_2393", format = 16f, grainSize = 2f)
        assertEquals(f, f.valid())
        assertEquals(f, Film.fromJson(f.toJson()))
    }
}
