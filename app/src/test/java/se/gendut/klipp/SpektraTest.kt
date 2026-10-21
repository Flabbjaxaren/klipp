package se.gendut.klipp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.OpenDrt
import se.gendut.klipp.engine.Spektra
import java.io.File
import kotlin.math.abs

/**
 * spektrafilm in Klipp against spektrafilm itself: every stage (log_e_film, cmy_film, log_e_print, cmy_print, rgb_out)
 * for six film/paper/settings combinations and 259 colors (the reference computed with tools/spektrafilm/export_tables.py).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpektraTest {
    companion object {
        @BeforeClass @JvmStatic fun data() {
            Spektra.open = { File("src/main/assets/spektrafilm/$it").inputStream() }
        }
    }

    private fun ref(): JSONArray = JSONArray(javaClass.classLoader!!.getResource("spektrafilm_ref.json")!!.readText())
    private fun d(a: JSONArray) = DoubleArray(a.length()) { if (a.isNull(it)) Double.NaN else a.getDouble(it) }

    private fun filmOf(c: JSONObject) = Film(
        film = c.getString("film"),
        print = if (c.isNull("print")) null else c.getString("print"),
        ev = c.optDouble("ev", 0.0).toFloat(),
        printExposure = c.optDouble("printExposure", 1.0).toFloat(),
        mShift = c.optDouble("m", 0.0).toFloat(),
        yShift = c.optDouble("y", 0.0).toFloat(),
        gamma = c.optDouble("gamma", 1.0).toFloat(),
        couplers = c.optDouble("couplers", 1.0).toFloat(),
        // the reference is computed with spektrafilm's default: the lab compensates the exposure when printing
        labComp = true,
    )

    @Test fun inputMatrixMatchesColour() {
        for (f in listOf("kodak_portra_400", "kodak_vision3_500t", "fujifilm_velvia_100")) {
            val fd = Spektra.film(f)
            val m = Spektra.inputMatrix(fd, OpenDrt.Input.REC709)
            for (i in 0 until 9) assertEquals("$f m709[$i]", fd.m709[i], m[i], 2e-6)
        }
    }

    @Test fun neutralFiltersFromTheDatabase() {
        val r = ref()
        for (i in 0 until r.length()) {
            val c = r.getJSONObject(i)
            if (c.isNull("print")) continue
            val m = Spektra.Model(filmOf(c))
            val n = d(c.getJSONArray("neutral"))
            for (k in 0..2) assertEquals("${c.getString("film")} neutral filter $k", n[k], m.neutral[k], 1e-9)
        }
    }

    @Test fun everyStageMatchesSpektrafilm() {
        val r = ref()
        var worstOut = 0.0
        var worstLeP = 0.0
        for (i in 0 until r.length()) {
            val c = r.getJSONObject(i)
            val m = Spektra.Model(filmOf(c))
            val input = d(c.getJSONArray("input"))
            val taps = c.getJSONObject("taps")
            val leF = d(taps.getJSONArray("log_e_film")); val cmyF = d(taps.getJSONArray("cmy_film"))
            val leP = taps.optJSONArray("log_e_print")?.let { d(it) }; val cmyP = taps.optJSONArray("cmy_print")?.let { d(it) }
            val out = d(taps.getJSONArray("rgb_out"))
            val n = input.size / 3
            val name = c.toString().take(120)
            for (k in 0 until n) {
                val rgb = doubleArrayOf(input[k * 3], input[k * 3 + 1], input[k * 3 + 2])
                // the film's exposure: the tc LUT is float16 in the app → ≈ 0.05 % in light (0.0003 in log)
                val le = m.logEFilm(rgb)
                for (ch in 0..2) {
                    val e = leF[k * 3 + ch]
                    if (e < -5) assertTrue("$name #$k log_e_film black", le[ch] < -5) else assertEquals("$name #$k log_e_film $ch", e, le[ch], 1.5e-3)
                }
                // the stages after that from spektrafilm's own values: each stage is tested on its own
                val inLe = DoubleArray(3) { leF[k * 3 + it] }
                val cmy = m.cmyFilm(inLe)
                for (ch in 0..2) assertEquals("$name #$k cmy_film $ch", cmyF[k * 3 + ch], cmy[ch], 1e-6)
                val inCmy = DoubleArray(3) { cmyF[k * 3 + it] }
                val dens = if (leP != null && cmyP != null) {
                    val lp = m.logEPrint(inCmy)
                    // the print normalization goes through the film's tc LUT (float16): a constant offset ≈ 3·10⁻⁵ in log
                    for (ch in 0..2) { worstLeP = maxOf(worstLeP, abs(leP[k * 3 + ch] - lp[ch])); assertEquals("$name #$k log_e_print $ch", leP[k * 3 + ch], lp[ch], 3e-4) }
                    val cp = m.cmyPrint(DoubleArray(3) { leP[k * 3 + it] })
                    for (ch in 0..2) assertEquals("$name #$k cmy_print $ch", cmyP[k * 3 + ch], cp[ch], 1e-6)
                    DoubleArray(3) { cmyP[k * 3 + it] }
                } else inCmy
                // the scan: 33³ LUT (float16) against spektrafilm's direct computation – under one 8-bit step
                val o = m.scan(dens)
                for (ch in 0..2) {
                    val e = abs(out[k * 3 + ch] - o[ch])
                    worstOut = maxOf(worstOut, e)
                    assertTrue("$name #$k rgb_out $ch: ${out[k * 3 + ch]} vs ${o[ch]}", e < 4e-3)
                }
                // the whole chain in one sweep (from RGB): the same image
                val all = m.linearToSrgb(rgb)
                for (ch in 0..2) assertEquals("$name #$k whole chain $ch", out[k * 3 + ch], all[ch], 6e-3)
            }
        }
        println("spektrafilm: largest output difference ${"%.5f".format(worstOut)}, in the print's log E ${"%.6f".format(worstLeP)}")
    }

    @Test fun couplerCurvesAreMonotoneForAllFilms() {
        // compute_density_curves_before_dir_couplers uses np.interp, which requires increasing x – check
        // that log E − the coupler amount increases for all films up to the slider's maximum
        for (s in Spektra.index.films) {
            val f = Spektra.film(s.id)
            val n = f.logExposure.size
            val mx = DoubleArray(3) { c -> (0 until n).maxOf { f.curves[it * 3 + c] - (0 until n).minOf { j -> f.curves[j * 3 + c] } } }
            for (amount in listOf(0.5, 1.0)) {
                val m = DoubleArray(9)
                for (k in 0..2) m[k * 3 + k] = f.couplerSelf[k] * f.inhSelf * amount
                m[1] = f.couplerR[0] * f.inhInter * amount; m[2] = f.couplerR[1] * f.inhInter * amount
                m[3] = f.couplerG[0] * f.inhInter * amount; m[5] = f.couplerG[1] * f.inhInter * amount
                m[6] = f.couplerB[0] * f.inhInter * amount; m[7] = f.couplerB[1] * f.inhInter * amount
                for (ch in 0..2) {
                    var last = Double.NEGATIVE_INFINITY
                    for (j in 0 until n) {
                        val sv = DoubleArray(3) { c -> val v = f.curves[j * 3 + c] - (0 until n).minOf { jj -> f.curves[jj * 3 + c] }; if (f.positive) mx[c] - v else v }
                        val le0 = f.logExposure[j] - (sv[0] * m[ch] + sv[1] * m[3 + ch] + sv[2] * m[6 + ch])
                        assertTrue("${s.id} couplers ×$amount channel $ch: x does not increase at $j", le0 >= last)
                        last = le0
                    }
                }
            }
        }
    }

    @Test fun bakedLutIsFiniteAndNeutralGreyStaysGrey() {
        val lut = Spektra.composite(null, 0f, Film())
        assertTrue(lut.data.all { it.isFinite() && it in 0f..1f })
        // mid gray in (Rec.709 0.49 ≈ 0.18 linear) gives nearly gray out with the paper's neutral filter
        val o = lut.applyTetrahedral(0.49f, 0.49f, 0.49f)
        assertTrue("gray: ${o.toList()}", maxOf(abs(o[0] - o[1]), abs(o[1] - o[2]), abs(o[0] - o[2])) < 0.03f)
        // brighter in gives brighter out
        val lo = lut.applyTetrahedral(0.2f, 0.2f, 0.2f)[1]
        val hi = lut.applyTetrahedral(0.8f, 0.8f, 0.8f)[1]
        assertTrue(lo < o[1] && o[1] < hi)
    }

    @Test fun nodeWithFilmSavesAndBakes() {
        val f = Film(film = "kodak_vision3_250d", print = "kodak_2383", ev = 0.5f, mShift = 4f, gamma = 1.1f, couplers = 0.8f, input = OpenDrt.Input.LOGC4.key, display = 1,
            labComp = true, format = 24.9f, grain = false, grainSize = 2f, halation = 2.5f, scatter = 0.5f, diffusion = "pro_mist", diffusionStrength = 0.25f,
            lensBlur = 12f, sharpen = 0f, glare = false)
        assertEquals(f, Film.fromJson(f.toJson()))
        // older projects (without the new fields): spektrafilm's default, but the exposure shows (the lab does not compensate)
        val old = Film.fromJson(org.json.JSONObject("{\"film\":\"kodak_portra_400\",\"print\":\"kodak_portra_endura\",\"ev\":1}"))!!
        assertEquals(Film(ev = 1f, labComp = false), old)
        val diafilm = Film(film = "fujifilm_velvia_100", print = null)
        assertEquals(diafilm, Film.fromJson(diafilm.toJson()))
        val node = se.gendut.klipp.engine.GradeNode(1, film = f)
        val back = se.gendut.klipp.engine.GradeNode.fromJson(node.toJson())
        assertEquals(node, back)
        assertTrue("a node with film is never neutral", !node.isNeutral)
        val t0 = System.nanoTime()
        val p = node.params()
        val ms = (System.nanoTime() - t0) / 1e6
        println("spektrafilm: preparation (the exposure and print LUTs) ${"%.0f".format(ms)} ms")
        // the film comes after the node (the whole image), not in the node's LUT
        assertEquals(f, p.film)
        assertTrue(p.lut == null)
        // grain/halation/diffusion do not change the heavy tables
        val t1 = System.nanoTime()
        se.gendut.klipp.engine.FilmFx.setup(f.copy(grainSize = 3f, halation = 0f, diffusion = null))
        val ms2 = (System.nanoTime() - t1) / 1e6
        println("spektrafilm: new grain/halation setting ${"%.0f".format(ms2)} ms")
        assertTrue(ms2 < ms)
        // the effects in playback: the node's pass and the film's
        val fx = se.gendut.klipp.engine.ClipGrade(listOf(node)).effects()
        assertEquals(2, fx.size)
        assertTrue(fx[1] is se.gendut.klipp.engine.FilmEffect)
    }
}
