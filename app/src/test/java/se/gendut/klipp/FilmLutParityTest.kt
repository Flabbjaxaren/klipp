package se.gendut.klipp

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.FilmFx
import se.gendut.klipp.engine.Spektra
import java.io.File
import kotlin.math.abs

/**
 * While scrubbing, the preview shows a node's film as its colors only (spektrafilm's chain baked into a LUT). On an
 * even image (where grain is off and halation/diffusion have no edges to spread) that must match the full film on the CPU.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilmLutParityTest {
    companion object {
        @BeforeClass @JvmStatic fun data() { Spektra.open = { File("src/main/assets/spektrafilm/$it").inputStream() } }
    }

    @Test fun lutMatchesTheFullFilmOnEvenColors() {
        val colors = listOf(
            floatArrayOf(0.2f, 0.2f, 0.2f), floatArrayOf(0.45f, 0.45f, 0.45f), floatArrayOf(0.7f, 0.7f, 0.7f),
            floatArrayOf(0.6f, 0.35f, 0.25f), floatArrayOf(0.25f, 0.45f, 0.6f), floatArrayOf(0.35f, 0.55f, 0.3f),
        )
        for (film in listOf(Film(grain = false), Film(grain = false, ev = 1f, labComp = false))) {
            val lut = Spektra.composite(null, 0f, film)
            var worst = 0f
            for (c in colors) {
                val n = 24
                val img = FloatArray(n * n * 3) { c[it % 3] }
                val full = FilmFx.applyEncoded(FilmFx.setup(film), img, n, n, 0)
                val i = ((n / 2) * n + n / 2) * 3
                val l = lut.applyTetrahedral(c[0], c[1], c[2])
                for (k in 0..2) worst = maxOf(worst, abs(full[i + k] - l[k]))
                println("film ${film.ev} ${c.toList()}: full ${full.slice(i..i + 2)} lut ${l.toList()}")
            }
            assertTrue("largest difference $worst", worst < 0.03f)
        }
    }
}
