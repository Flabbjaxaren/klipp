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
import se.gendut.klipp.engine.FilmFx
import se.gendut.klipp.engine.Spektra
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Klipp's film controls against spektrafilm's (the reference computed with tools/spektrafilm/reference_params.py): each case is
 * spektrafilm with a few controls changed – the same controls in Klipp must give the same image. Also the paper's curves with the chemistry
 * controls, grain with and without sublayers, and the glare statistics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilmFxParamsTest {
    companion object {
        @BeforeClass @JvmStatic fun data() {
            Spektra.open = { File("src/main/assets/spektrafilm/$it").inputStream() }
        }
        private val REF: JSONObject by lazy {
            val gz = FilmFxParamsTest::class.java.classLoader!!.getResourceAsStream("spektrafilm_params_ref.json.gz")!!
            JSONObject(GZIPInputStream(gz).readBytes().toString(Charsets.UTF_8))
        }
    }

    private fun d(a: JSONArray) = FloatArray(a.length()) { if (a.isNull(it)) Float.NaN else a.getDouble(it).toFloat() }

    /** spektrafilm's controls (path → value) as Klipp's [Film]. Null = controls Klipp doesn't have. */
    private fun filmOf(c: JSONObject): Film? {
        val w = c.getInt("w")
        var f = Film(
            film = c.optString("film", "kodak_portra_400"),
            print = if (c.has("print") && c.isNull("print")) null else c.optString("print", "kodak_portra_endura"),
            format = (w * c.optDouble("um", 21.0) / 1000.0).toFloat(),
            grain = false, glare = false,
        )
        val set = c.optJSONObject("set") ?: JSONObject()
        for (k in set.keys()) {
            val v = set.get(k)
            fun n() = (v as Number).toFloat()
            f = when (k) {
                "camera.exposure_compensation_ev" -> f.copy(ev = n())
                "enlarger.print_exposure" -> f.copy(printExposure = n())
                "enlarger.print_exposure_compensation" -> f.copy(labComp = v as Boolean)
                "enlarger.m_filter_shift" -> f.copy(mShift = n())
                "enlarger.y_filter_shift" -> f.copy(yShift = n())
                "film_render.halation.boost_ev" -> f.copy(boostEv = n())
                "film_render.halation.protect_ev" -> f.copy(protectEv = n())
                "film_render.halation.boost_range" -> f.copy(boostRange = n())
                "film_render.halation.halation_amount" -> f.copy(halation = n())
                "film_render.halation.halation_spatial_scale" -> f.copy(halationSize = n())
                "film_render.halation.scatter_amount" -> f.copy(scatter = n())
                "film_render.halation.scatter_spatial_scale" -> f.copy(scatterSize = n())
                "print_render.density_curves_morph.active" -> f
                "print_render.density_curves_morph.gamma_factor" -> f.copy(printGamma = n())
                "enlarger.diffusion_filter.active" -> f
                "enlarger.diffusion_filter.filter_family" -> f.copy(enlargerDiffusion = v as String)
                "enlarger.diffusion_filter.strength" -> f.copy(enlargerDiffusionStrength = n())
                "scanner.lens_blur" -> f.copy(scanBlur = n())
                "scanner.unsharp_mask" -> f.copy(sharpen = (v as JSONArray).getDouble(1).toFloat())
                else -> return null
            }
        }
        return f
    }

    private class Err { var max = 0.0; var sum = 0.0; var n = 0
        fun add(e: Double) { if (e > max) max = e; sum += e; n++ }
        val mean get() = sum / max(n, 1)
        override fun toString() = "max %.4f mean %.5f".format(max, mean)
    }

    @Test fun everyControlMatchesSpektrafilm() {
        val cases = REF.getJSONArray("cases")
        var compared = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val f = filmOf(c)
            if (f == null) { println("$name: controls Klipp doesn't show – only the paper's curves are compared"); continue }
            val w = c.getInt("w"); val h = c.getInt("h")
            val s = FilmFx.setup(f)
            assertEquals("$name pixel size", c.getDouble("pixelUm"), s.forImage(w, h).pixelUm, 1e-4)
            val t = FilmFx.process(s, d(c.getJSONArray("input")), w, h, 0)
            val taps = c.getJSONObject("taps")
            val le = d(taps.getJSONArray("log_e_film")); val out = d(taps.getJSONArray("rgb_out"))
            val eLe = Err(); val eOut = Err()
            for (k in le.indices) {
                if (le[k] < -4f) continue
                eLe.add(abs(t.logEFilm[k] - le[k]).toDouble())
                eOut.add(abs(t.out[k] - out[k].coerceIn(0f, 1f)).toDouble())
            }
            println("$name: log E $eLe · out $eOut")
            // the same limits as the spatial effects (FilmFxTest): 8 bits = 0.0039. Known limitation: next to
            // highlights boosted many stops (64–256×) the fast blur's approximation (the pyramid,
            // error ≈ 0.1 % of the energy) is not enough for the smallest kernels where the image is thousands of times darker – a few
            // pixels nearest the highlight then differ (6 stops: at most ≈ 8 code values; 8 stops and 3× halation: more)
            val (maxLe, maxOut, meanOut) = when (name) {
                "boost_6" -> Triple(0.05, 0.04, 0.003)
                "boost_hog" -> Triple(0.15, 0.30, 0.006)
                else -> Triple(0.05, 0.03, 0.004)
            }
            assertTrue("$name log E $eLe", eLe.max < maxLe && eLe.mean < 0.006)
            assertTrue("$name out $eOut", eOut.max < maxOut && eOut.mean < meanOut)
            compared++
        }
        assertTrue(compared >= 12)
    }

    @Test fun printChemistryCurvesMatchSpektrafilm() {
        val cases = REF.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val ref = c.optJSONArray("printCurves") ?: continue
            val m = c.getJSONObject("morph")
            val morph = Spektra.Morph(
                gamma = if (m.getBoolean("active")) m.getDouble("gamma_factor") else 1.0,
                fast = if (m.getBoolean("active")) m.getDouble("gamma_factor_fast") else 1.0,
                slow = if (m.getBoolean("active")) m.getDouble("gamma_factor_slow") else 1.0,
                red = if (m.getBoolean("active")) m.getDouble("gamma_factor_red") else 1.0,
                green = if (m.getBoolean("active")) m.getDouble("gamma_factor_green") else 1.0,
                blue = if (m.getBoolean("active")) m.getDouble("gamma_factor_blue") else 1.0,
                exhaustion = if (m.getBoolean("active")) m.getDouble("developer_exhaustion") else 0.0,
            )
            val pr = Spektra.print(c.optString("print", "kodak_portra_endura"))
            val got = Spektra.printCurves(pr, morph)
            val r = d(ref)
            var err = 0.0
            for (k in r.indices) err = max(err, abs(got[k] - r[k]))
            println("${c.getString("name")}: paper curves, largest difference %.2e".format(err))
            assertTrue("${c.getString("name")} curves $err", err < 2e-5)
        }
    }

    @Test fun grainVariantsMatchSpektrafilmStatistically() {
        val g = REF.getJSONArray("grain")
        for (i in 0 until g.length()) {
            val c = g.getJSONObject(i)
            val n = 192
            val um = c.getDouble("pixelUm")
            val set = c.getJSONObject("set")
            var f = Film(film = c.getString("film"), print = "kodak_portra_endura", labComp = false, format = (n * um / 1000).toFloat(),
                halation = 0f, scatter = 0f, glare = false)
            if (set.has("film_render.grain.sublayers_active")) f = f.copy(grainLayers = set.getBoolean("film_render.grain.sublayers_active"))
            if (set.has("film_render.grain.blur")) f = f.copy(grainBlur = set.getDouble("film_render.grain.blur").toFloat())
            if (set.has("film_render.grain.particle_area_um2")) f = f.copy(grainSize = (set.getDouble("film_render.grain.particle_area_um2") / 0.2).toFloat())
            val s = FilmFx.setup(f)
            val stats = c.getJSONArray("stats")
            for (k in 0 until stats.length()) {
                val st = stats.getJSONObject(k)
                val v = st.getDouble("level").toFloat()
                val t = FilmFx.process(s, FloatArray(n * n * 3) { v }, n, n, 21L + k)
                val mean = d(st.getJSONArray("mean")); val std = d(st.getJSONArray("std"))
                for (ch in 0..2) {
                    var sum = 0.0; var sq = 0.0; var cnt = 0
                    for (y in 8 until n - 8) for (x in 8 until n - 8) { val vv = t.cmyGrain[(y * n + x) * 3 + ch].toDouble(); sum += vv; sq += vv * vv; cnt++ }
                    val mm = sum / cnt; val sd = sqrt(max(sq / cnt - mm * mm, 0.0))
                    println("${c.getString("name")} level $v channel $ch: mean %.4f (spektrafilm %.4f) σ %.4f (spektrafilm %.4f)".format(mm, mean[ch], sd, std[ch]))
                    assertEquals("${c.getString("name")} $v mean $ch", mean[ch].toDouble(), mm, 0.01)
                    assertEquals("${c.getString("name")} $v σ $ch", std[ch].toDouble(), sd, 0.15 * std[ch] + 0.0006)
                }
            }
        }
    }

    @Test fun glareMatchesSpektrafilmStatistically() {
        val gl = REF.getJSONArray("glare")
        for (i in 0 until gl.length()) {
            val c = gl.getJSONObject(i)
            val n = 128
            val f = Film(format = (n * 21.0 / 1000).toFloat(), grain = false, halation = 0f, scatter = 0f, sharpen = 0f,
                glarePercent = c.getDouble("percent").toFloat(), glareRoughness = c.getDouble("roughness").toFloat())
            val t = FilmFx.process(FilmFx.setup(f), FloatArray(n * n * 3) { 0.05f }, n, n, 5L + i)
            val t0 = FilmFx.process(FilmFx.setup(f.copy(glare = false)), FloatArray(n * n * 3) { 0.05f }, n, n, 5L)
            fun lin(v: Float) = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            val mean = d(c.getJSONArray("mean")); val std = d(c.getJSONArray("std")); val without = d(c.getJSONArray("without"))
            for (ch in 0..2) {
                var sum = 0.0; var sq = 0.0; var cnt = 0
                for (y in 4 until n - 4) for (x in 4 until n - 4) { val vv = lin(t.out[(y * n + x) * 3 + ch]); sum += vv; sq += vv * vv; cnt++ }
                val mm = sum / cnt; val sd = sqrt(max(sq / cnt - mm * mm, 0.0))
                val base = lin(t0.out[(n / 2 * n + n / 2) * 3 + ch])
                println("glare ${c.getString("name")} channel $ch: added %.5f (spektrafilm %.5f) σ %.5f (spektrafilm %.5f)".format(mm - base, mean[ch] - without[ch], sd, std[ch]))
                // the added light and its spread (spektrafilm adds it in XYZ before the output's gamut compression)
                assertEquals("${c.getString("name")} added $ch", (mean[ch] - without[ch]).toDouble(), mm - base, 0.15 * (mean[ch] - without[ch]) + 2e-5)
                assertEquals("${c.getString("name")} σ $ch", std[ch].toDouble(), sd, 0.2 * std[ch] + 1e-5)
            }
        }
    }
}
