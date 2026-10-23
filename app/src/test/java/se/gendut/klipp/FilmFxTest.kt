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
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The film's spatial and random effects against spektrafilm (the reference computed with tools/spektrafilm/export_tables.py):
 * halation, scatter, DIR coupler diffusion, diffusion filters, lens blur and the scanner's sharpening on
 * test images; grain spread on flat areas; the exposure control; the blur kernels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilmFxTest {
    companion object {
        @BeforeClass @JvmStatic fun data() {
            Spektra.open = { File("src/main/assets/spektrafilm/$it").inputStream() }
        }
        private val REF: JSONObject by lazy {
            val gz = FilmFxTest::class.java.classLoader!!.getResourceAsStream("spektrafilm_spatial_ref.json.gz")!!
            JSONObject(GZIPInputStream(gz).readBytes().toString(Charsets.UTF_8))
        }
    }

    private fun d(a: JSONArray) = FloatArray(a.length()) { if (a.isNull(it)) Float.NaN else a.getDouble(it).toFloat() }

    private fun filmOf(c: JSONObject): Film {
        val w = c.getInt("w")
        return Film(
            film = c.getString("film"),
            print = if (c.isNull("print")) null else c.getString("print"),
            ev = c.optDouble("ev", 0.0).toFloat(),
            couplers = c.optDouble("couplers", 1.0).toFloat(),
            labComp = false,
            format = (w * c.getDouble("um") / 1000.0).toFloat(),
            grain = false,
            halation = c.optDouble("halation", 1.0).toFloat(),
            diffusion = c.optString("diffusion").ifBlank { null },
            diffusionStrength = c.optDouble("strength", 0.5).toFloat(),
            lensBlur = c.optDouble("lensBlurUm", 0.0).toFloat(),
            glare = false,
            sharpen = 0.7f, // spektrafilm's scanner
        )
    }

    private class Err { var max = 0.0; var sum = 0.0; var n = 0
        fun add(e: Double) { if (e > max) max = e; sum += e; n++ }
        val mean get() = sum / max(n, 1)
        override fun toString() = "max %.4f mean %.5f".format(max, mean)
    }

    @Test fun spatialEffectsMatchSpektrafilm() {
        val cases = REF.getJSONArray("spatial")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val w = c.getInt("w"); val h = c.getInt("h")
            val f = filmOf(c)
            val s = FilmFx.setup(f)
            val fr = s.forImage(w, h)
            assertEquals("pixel size on the film", c.getDouble("pixelUm"), fr.pixelUm, 1e-4)
            val input = d(c.getJSONArray("input"))
            val t = FilmFx.process(s, input, w, h, 0)
            val taps = c.getJSONObject("taps")
            val le = d(taps.getJSONArray("log_e_film")); val cmy = d(taps.getJSONArray("cmy_film")); val out = d(taps.getJSONArray("rgb_out"))
            // the exposure in light (relative), the density and the output; only where the light matters (log E > −4)
            val eLe = Err(); val eCmy = Err(); val eOut = Err()
            var worst = -1
            for (k in le.indices) {
                if (le[k] < -4f) continue
                eLe.add(abs(t.logEFilm[k] - le[k]).toDouble())
                eCmy.add(abs(t.cmyFilm[k] - cmy[k]).toDouble())
                // (spektrafilm does not clip: sharpening can dip slightly below 0 at hard edges – a display shows 0)
                val eo = abs(t.out[k] - out[k].coerceIn(0f, 1f)).toDouble()
                if (eo > eOut.max) worst = k
                eOut.add(eo)
            }
            if (worst >= 0) {
                val px = worst / 3; val ch = worst % 3
                println("  worst out: x ${px % w} y ${px / w} channel $ch – in ${input[worst]} logE ${t.logEFilm[worst]}/${le[worst]} cmy ${t.cmyFilm[worst]}/${cmy[worst]} out ${t.out[worst]}/${out[worst]}")
            }
            val name = c.toString().take(140)
            println("$name\n  log E $eLe · density $eCmy · out $eOut · camera kernel ${fr.camera.count} elements (error ${"%.4f".format(fr.camera.maxError)})")
            // log E: 0.02 ≈ 5 % in light where the kernel is steepest (the edges of the bright spot), much less on average
            assertTrue("$name log E $eLe", eLe.max < 0.05 && eLe.mean < 0.004)
            assertTrue("$name density $eCmy", eCmy.max < 0.04 && eCmy.mean < 0.004)
            // 8 bits = 0.0039
            assertTrue("$name out $eOut", eOut.max < 0.03 && eOut.mean < 0.004)
        }
    }

    @Test fun grainMatchesSpektrafilmStatistically() {
        val g = REF.getJSONArray("grain")
        for (i in 0 until g.length()) {
            val c = g.getJSONObject(i)
            val n = 192
            val um = c.getDouble("pixelUm")
            val film = c.getString("film")
            val positive = Spektra.film(film).positive
            val f = Film(film = film, print = if (positive) null else "kodak_portra_endura", labComp = false, format = (n * um / 1000).toFloat(),
                halation = 0f, scatter = 0f, glare = false, grainClouds = 0f, grainColor = 1f)
            val s = FilmFx.setup(f)
            val stats = c.getJSONArray("stats")
            for (k in 0 until stats.length()) {
                val st = stats.getJSONObject(k)
                val v = st.getDouble("level").toFloat()
                val flat = FloatArray(n * n * 3) { v }
                val t = FilmFx.process(s, flat, n, n, 11L + k)
                val mean = d(st.getJSONArray("mean")); val std = d(st.getJSONArray("std"))
                for (ch in 0..2) {
                    var sum = 0.0; var sq = 0.0; var cnt = 0
                    for (y in 8 until n - 8) for (x in 8 until n - 8) { val vv = t.cmyGrain[(y * n + x) * 3 + ch].toDouble(); sum += vv; sq += vv * vv; cnt++ }
                    val m = sum / cnt; val sd = sqrt(max(sq / cnt - m * m, 0.0))
                    println("$film ${um}µm level $v channel $ch: mean %.4f (spektrafilm %.4f) σ %.4f (spektrafilm %.4f)".format(m, mean[ch], sd, std[ch]))
                    assertEquals("$film $v mean $ch", mean[ch].toDouble(), m, 0.01)
                    assertEquals("$film $v σ $ch", std[ch].toDouble(), sd, 0.15 * std[ch] + 0.0006)
                }
            }
        }
    }

    @Test fun grainCloudsAreSizedOnTheFilmNotInPixels() {
        // the same film at two resolutions: the color clouds are the same size on the film (σ in pixels twice as large at 4K)
        val s = FilmFx.setup(Film(film = "kodak_vision3_500t", print = "kodak_2383", format = 12.5f, grainClouds = 1f))
        val hd = s.forImage(1920, 1080); val uhd = s.forImage(3840, 2160)
        val cloudHd = sqrt(hd.grainSigmaPx * hd.grainSigmaPx - 0.65 * 0.65); val cloudUhd = sqrt(uhd.grainSigmaPx * uhd.grainSigmaPx - 0.65 * 0.65)
        println("Super 16, 500T: color cloud σ ${"%.2f".format(cloudHd)} px (1080p), ${"%.2f".format(cloudUhd)} px (2160p), µm ${"%.1f".format(s.cloudSigmaUm)}")
        assertEquals(2.0, cloudUhd / cloudHd, 0.05)
        // faster film = larger clouds
        assertTrue(FilmFx.setup(Film(film = "kodak_vision3_500t", print = "kodak_2383")).cloudSigmaUm > FilmFx.setup(Film(film = "kodak_ektar_100")).cloudSigmaUm)
        // 0 (the default) = spektrafilm's 0.65 pixels
        assertEquals(0.65, FilmFx.setup(Film()).forImage(1920, 1080).grainSigmaPx, 1e-6)
    }

    @Test fun grainColorTurnsChromaIntoLuminance() {
        val n = 96
        val flat = FloatArray(n * n * 3) { 0.18f }
        fun noise(chroma: Float): Pair<Double, Double> {
            val t = FilmFx.process(FilmFx.setup(Film(grainColor = chroma, glare = false, halation = 0f, scatter = 0f, format = 4f)), flat, n, n, 3)
            val mean = DoubleArray(3) { c -> (0 until n * n).sumOf { t.cmyGrain[it * 3 + c].toDouble() } / (n * n) }
            var lum = 0.0; var chr = 0.0
            for (i in 0 until n * n) {
                val nz = DoubleArray(3) { c -> t.cmyGrain[i * 3 + c] - mean[c] }
                val l = nz.average()
                lum += l * l; chr += (nz[0] - l) * (nz[0] - l)
            }
            return sqrt(lum / (n * n)) to sqrt(chr / (n * n))
        }
        val (l1, c1) = noise(1f); val (l0, c0) = noise(0f)
        println("grain: luminance ${"%.4f".format(l1)} / ${"%.4f".format(l0)}, color ${"%.4f".format(c1)} / ${"%.4f".format(c0)}")
        assertEquals("luminance grain remains", l1, l0, 0.15 * l1)
        assertTrue("the color disappears", c0 < 0.05 * c1)
    }

    @Test fun grainAmountScalesTheGrain() {
        val n = 96
        val flat = FloatArray(n * n * 3) { 0.18f }
        fun sd(amount: Float): Double {
            val t = FilmFx.process(FilmFx.setup(Film(grainAmount = amount, glare = false, halation = 0f, scatter = 0f, format = 4f)), flat, n, n, 3)
            val v = DoubleArray(n * n) { t.cmyGrain[it * 3 + 1].toDouble() }
            val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        }
        val one = sd(1f); val three = sd(3f)
        println("grain σ: 0× ${"%.5f".format(sd(0f))}, 1× ${"%.4f".format(one)}, 3× ${"%.4f".format(three)}")
        assertTrue("0× = no grain", sd(0f) < 1e-6)
        assertEquals(3.0, three / one, 0.05)
        // saved and read
        assertEquals(2.5f, Film.fromJson(Film(grainAmount = 2.5f).toJson())!!.grainAmount)
        assertEquals(1f, Film.fromJson(Film().toJson().apply { remove("gamt") })!!.grainAmount)
    }

    @Test fun olderProjectsGetTheNewScannerDefault() {
        val o = Film(sharpen = 0.7f).toJson().apply { remove("v"); put("shp", 0.7) }
        assertEquals(Film().sharpen, Film.fromJson(o)!!.sharpen)
        assertEquals(1.2f, Film.fromJson(Film(sharpen = 1.2f).toJson())!!.sharpen)
    }

    @Test fun grainChangesEveryFrameButIsRepeatable() {
        val s = FilmFx.setup(Film(format = 4f))
        val img = FloatArray(32 * 18 * 3) { 0.4f }
        val a = FilmFx.applyEncoded(s, img, 32, 18, 1)
        val a2 = FilmFx.applyEncoded(s, img, 32, 18, 1)
        val b = FilmFx.applyEncoded(s, img, 32, 18, 2)
        assertTrue("same frame = same grain", a.contentEquals(a2))
        assertTrue("next frame = new grain", !a.contentEquals(b))
    }

    @Test fun effectsOnlyKeepsTheImagesColors() {
        // without any effect the image comes back as it was: the film's colors are divided out again
        val w = 24; val h = 16
        val img = FloatArray(w * h * 3) { i -> val p = i / 3; val c = i % 3; ((p % w) / (w - 1f) * 0.8f + 0.1f) * (if (c == p / w % 3) 1f else 0.6f) }
        for (input in listOf("rec709", "vivo")) {
            val calm = Film(input = input, grain = false, halation = 0f, scatter = 0f, glare = false, sharpen = 0f, effectsOnly = true)
            val out = FilmFx.applyEncoded(FilmFx.setup(calm), img, w, h, 0)
            var worst = 0f
            for (i in img.indices) worst = max(worst, abs(out[i] - img[i]))
            println("$input: largest change without effects $worst")
            assertTrue("$input: $worst", worst < 0.01f)
        }
        // grain: noise around the image's own value
        val flat = FloatArray(64 * 64 * 3) { 0.45f }
        val grainy = FilmFx.applyEncoded(FilmFx.setup(Film(halation = 0f, scatter = 0f, glare = false, sharpen = 0f, format = 4f, effectsOnly = true)), flat, 64, 64, 3)
        val mean = grainy.average(); val sd = sqrt(grainy.map { (it - mean) * (it - mean) }.average())
        assertEquals(0.45, mean, 0.01)
        assertTrue("grain $sd", sd > 0.003)
    }

    @Test fun effectsOnlyIgnoresTheHiddenColorControls() {
        // values left from the whole film (exposure, filters, gamma, paper) don't change the effects
        val w = 24; val h = 16
        val img = FloatArray(w * h * 3) { i -> 0.2f + 0.6f * ((i / 3) % w) / w + 0.05f * (i % 3) }
        val plain = Film(effectsOnly = true, format = 4f)
        val tweaked = plain.copy(ev = 2f, printExposure = 1.6f, mShift = 40f, yShift = -30f, gamma = 1.3f, couplers = 0.4f, printGamma = 1.5f, print = "fujifilm_crystal_archive_typeii", display = 1)
        val a = FilmFx.applyEncoded(FilmFx.setup(plain), img, w, h, 7)
        val b = FilmFx.applyEncoded(FilmFx.setup(tweaked), img, w, h, 7)
        for (i in a.indices) assertEquals("px $i", a[i], b[i], 1e-5f)
    }

    @Test fun effectsDoNotRebakeTheColors() {
        // halation, grain, diffusion … leave the colors' LUT as it is (the same object); a color control rebakes it
        val a = Spektra.composite(null, 0f, Film())
        assertTrue(a === Spektra.composite(null, 0f, Film(halation = 6f, halationSize = 2f, grainSize = 3f, diffusion = FilmFx.DIFFUSION.first(), effectsOnly = true)))
        assertTrue(a !== Spektra.composite(null, 0f, Film(ev = 1f)))
    }

    @Test fun encodeInvertsTheInputCurve() {
        for (input in listOf("rec709", "vivo", "slog3", "logc4")) {
            val s = FilmFx.setup(Film(input = input))
            for (k in 1..19) {
                val code = k / 20f
                val lin = FloatArray(1) { code }.let { FilmFx.applyDecode(s, it[0]) }
                if (lin <= 0f) continue
                assertEquals("$input $code", code, FilmFx.encodeLinear(s, lin), 2e-3f)
            }
        }
    }

    @Test fun exposureSliderBrightensUnlessTheLabCompensates() {
        fun mid(f: Film) = Spektra.Model(f).linearToSrgb(doubleArrayOf(0.18, 0.18, 0.18))[1]
        // without the print's auto compensation (spektrafilm's default has it on)
        val base = mid(Film(labComp = false))
        val up = mid(Film(ev = 1f, labComp = false))
        val down = mid(Film(ev = -1f, labComp = false))
        assertTrue("+1 EV brighter: $base → $up", up > base + 0.05)
        assertTrue("−1 EV darker: $base → $down", down < base - 0.05)
        // the lab compensates: mid gray stays (almost) still (spektrafilm's default)
        val comp = mid(Film(ev = 1f, labComp = true))
        assertTrue("compensated: $base → $comp", abs(comp - base) < 0.03)
        // the same in the baked LUT (the color node)
        val lutUp = Spektra.composite(null, 0f, Film(ev = 1f, labComp = false)).applyTetrahedral(0.5f, 0.5f, 0.5f)[1]
        val lutBase = Spektra.composite(null, 0f, Film(labComp = false)).applyTetrahedral(0.5f, 0.5f, 0.5f)[1]
        assertTrue("LUT: $lutBase → $lutUp", lutUp > lutBase + 0.05)
    }

    @Test fun halationAmountAndSizeWidenTheRedGlow() {
        // a white spot on a dark background at 1080p scale (35 mm across 1920 pixels): a red glow outside the edge
        val n = 160
        val img = FloatArray(n * n * 3) { 0.03f }
        for (y in 0 until n) for (x in 0 until n) { val dx = x - n / 2; val dy = y - n / 2; if (dx * dx + dy * dy <= 15 * 15) for (c in 0..2) img[(y * n + x) * 3 + c] = 1f }
        fun red(hal: Float, size: Float, d: Int): Float {
            val t = FilmFx.process(FilmFx.setup(Film(halation = hal, halationSize = size, grain = false, glare = false, format = 35f * n / 1920f)), img, n, n, 0)
            val i = ((n / 2) * n + n / 2 + d) * 3
            return t.out[i] - t.out[i + 2]
        }
        val none = red(0f, 1f, 20); val one = red(1f, 1f, 20); val strong = red(10f, 1f, 20); val wide = red(10f, 3f, 30)
        println("halation, red minus blue: off ${"%.3f".format(none)}, 1× ${"%.3f".format(one)}, 10× ${"%.3f".format(strong)}, 10× wide (30 px) ${"%.3f".format(wide)}")
        assertTrue("1× is weak but present", one > none + 0.002)
        assertTrue("10× is clearly visible", strong > none + 0.04)
        assertTrue("the width reaches further out", wide > red(10f, 1f, 30) + 0.02)
        // saved and read; old projects get 1×
        assertEquals(2.5f, Film.fromJson(Film(halationSize = 2.5f, halation = 20f).toJson())!!.halationSize)
        assertEquals(20f, Film.fromJson(Film(halation = 20f).toJson())!!.halation)
        assertEquals(1f, Film.fromJson(Film().toJson().apply { remove("hsz") })!!.halationSize)
    }

    @Test fun kernelsFitTheirMtf() {
        val sp = Spektra.film("kodak_portra_400").spatial
        for (um in listOf(5.0, 18.75, 75.0)) {
            for (f in listOf(Film(), Film(halation = 3f), Film(halation = 10f, halationSize = 3f), Film(halation = Film.HALATION_MAX, halationSize = Film.HALATION_SIZE_MAX), Film(diffusion = "black_pro_mist", diffusionStrength = 1f), Film(diffusion = "cinebloom", diffusionStrength = 2f), Film(lensBlur = 40f))) {
                val k = FilmFx.cameraKernel(f, sp, um)
                // (kernels wider than the image don't fit – spektrafilm truncates them too)
                if (k.scalePx > 1080 / 4.0) continue
                val fit = FilmFx.fit(k, 1920, 1080)
                println("camera ${um}µm ${f.diffusion ?: ""} hal ${f.halation} lens ${f.lensBlur}: ${fit.count} elements, error %.4f, weights ${fit.w[0].joinToString { "%.3f".format(it) }}".format(fit.maxError))
                assertTrue("MTF error ${fit.maxError}", fit.maxError < 0.03)
                for (c in 0..2) assertEquals("energy is preserved", 1.0, fit.w[c].sum().toDouble(), 1e-4)
            }
            val cf = FilmFx.fit(FilmFx.couplerKernel(sp, um)!!, 1920, 1080)
            println("couplers ${um}µm: ${cf.count} elements, error %.4f".format(cf.maxError))
            assertTrue(cf.maxError < 0.03)
        }
    }

    @Test fun fittingAFrameSizeIsQuick() {
        for (f in listOf(Film(), Film(diffusion = "cinebloom", diffusionStrength = 2f), Film(diffusion = "black_pro_mist", lensBlur = 20f))) {
            val s = FilmFx.setup(f)
            val t0 = System.nanoTime()
            val fr = s.forImage(1920, 1080)
            val ms = (System.nanoTime() - t0) / 1e6
            println("fit 1920×1080 ${f.diffusion}: ${"%.0f".format(ms)} ms (camera ${fr.camera.count} elements, couplers ${fr.couplers?.count})")
            assertTrue("too slow: $ms ms", ms < 1500)
        }
    }

    @Test fun pyramidElementsHaveTheirSigma() {
        // a point in the middle: element e must have variance σ_e² (at full resolution) and sum 1
        val w = 257; val h = 257
        val img = FloatArray(w * h * 3); img[((h / 2) * w + w / 2) * 3] = 1f
        val n = FilmFx.Pyramid.usable(w, h)
        for (e in 0 until n) {
            val wt = Array(3) { FloatArray(e + 2) }.also { it[0][e + 1] = 1f }
            val fit = FilmFx.Fit(e + 1, wt, 0.0)
            val up = FilmFx.Pyramid.apply(img, w, h, fit)
            var sum = 0.0; var vx = 0.0
            for (y in 0 until h) for (x in 0 until w) { val v = up[(y * w + x) * 3].toDouble(); sum += v; vx += v * (x - w / 2) * (x - w / 2) }
            val sigma = sqrt(vx / sum)
            val want = FilmFx.Pyramid.ELS[e].sigma
            println("element $e (level ${FilmFx.Pyramid.ELS[e].k}): σ %.3f (should be %.3f), sum %.4f".format(sigma, want, sum))
            // (a kernel that is a large part of the image reaches the edge: the measurement truncates it)
            if (want <= w / 8.0) {
                assertEquals(1.0, sum, 0.01)
                assertEquals(want, sigma, 0.06 * want + 0.12)
            }
        }
    }

    @Test fun scatterAndHalationImpulseMatchesSpektrafilm() {
        // spektrafilm apply_halation_um on a point (Portra 400, 21 µm): row 14..26 through the point (20, 20)
        val ref = arrayOf(
            doubleArrayOf(0.00004, 0.00006, 0.00016, 0.00088, 0.00633, 0.06927, 0.53202, 0.06927, 0.00633, 0.00088, 0.00016, 0.00006, 0.00004),
            doubleArrayOf(0.00001, 0.00002, 0.00012, 0.00079, 0.00590, 0.05796, 0.60061, 0.05796, 0.00590, 0.00079, 0.00012, 0.00002, 0.00001),
            doubleArrayOf(0.00000, 0.00000, 0.00005, 0.00061, 0.00507, 0.06023, 0.61204, 0.06023, 0.00507, 0.00061, 0.00005, 0.00000, 0.00000),
        )
        val sp = Spektra.film("kodak_portra_400").spatial
        val w = 41; val h = 41
        val fit = FilmFx.fit(FilmFx.cameraKernel(Film(), sp, 21.0), w, h)
        val img = FloatArray(w * h * 3); for (c in 0..2) img[(20 * w + 20) * 3 + c] = 1f
        val out = FilmFx.Pyramid.apply(img, w, h, fit)
        for (c in 0..2) {
            val row = (14..26).map { x -> out[(20 * w + x) * 3 + c].toDouble() }
            println("channel $c: " + row.joinToString(" ") { "%.5f".format(it) })
            for (i in row.indices) assertEquals("channel $c x ${14 + i}", ref[c][i], row[i], 0.002 + 0.01 * ref[c][i])
        }
    }

    @Test fun boostReferenceIgnoresNoisyPixels() {
        // a bright sky with sensor noise, two frames: the brightest single pixel jumps, the boost's reference stays
        val w = 1920; val h = 1080
        fun frame(seed: Long): FloatArray {
            val r = java.util.Random(seed)
            return FloatArray(w * h * 3) { i -> (if ((i / 3) / w < h / 3) 2f else 0.3f) * (1f + 0.15f * r.nextGaussian().toFloat()) }
        }
        val a = frame(1); val b = frame(2)
        b[1000] = 9f // one hot pixel
        val ra = FilmFx.boostRef(a, w, h); val rb = FilmFx.boostRef(b, w, h)
        assertEquals(2.0, ra, 0.1)
        assertEquals(ra, rb, 0.02)
        assertTrue(abs(a.max() - b.max()) > 1f)
        // small images (the reference renders) keep spektrafilm's brightest pixel
        val small = FloatArray(96 * 64 * 3) { 0.2f }.also { it[77] = 3f }
        assertEquals(3.0, FilmFx.boostRef(small, 96, 64), 1e-6)
    }

    @Test fun aFlatImageStaysFlat() {
        // energy is preserved for every element, even the coarsest (mirrored edges)
        val w = 200; val h = 120
        val img = FloatArray(w * h * 3) { 0.37f }
        for (e in 0 until FilmFx.Pyramid.usable(w, h)) {
            val wt = Array(3) { FloatArray(e + 2) }.also { a -> for (c in 0..2) a[c][e + 1] = 1f }
            val out = FilmFx.Pyramid.apply(img, w, h, FilmFx.Fit(e + 1, wt, 0.0))
            for (v in out) assertEquals("element $e", 0.37, v.toDouble(), 1e-4)
        }
    }

    @Test fun logEMatchesInLinearLight() {
        // without spatial effects and couplers: the same exposure as Spektra.Model (the LUT over normalized RGB)
        val f = Film(film = "kodak_ektar_100", halation = 0f, scatter = 0f, couplers = 0f, grain = false, glare = false, sharpen = 0f)
        val s = FilmFx.setup(f)
        val m = Spektra.Model(f)
        val cols = listOf(floatArrayOf(0.18f, 0.18f, 0.18f), floatArrayOf(0.9f, 0.1f, 0.05f), floatArrayOf(0.02f, 0.4f, 0.8f), floatArrayOf(3f, 2f, 1f), floatArrayOf(0.001f, 0.002f, 0.0005f))
        val img = FloatArray(cols.size * 3).also { a -> cols.forEachIndexed { i, c -> c.copyInto(a, i * 3) } }
        val t = FilmFx.process(s, img, cols.size, 1, 0)
        for ((i, c) in cols.withIndex()) {
            val le = m.logEFilm(doubleArrayOf(c[0].toDouble(), c[1].toDouble(), c[2].toDouble()))
            for (k in 0..2) assertEquals("color $i channel $k", le[k], t.logEFilm[i * 3 + k].toDouble(), 0.004)
            // all the way (without blurs) like Spektra.Model
            val ref = m.linearToSrgb(doubleArrayOf(c[0].toDouble(), c[1].toDouble(), c[2].toDouble()))
            for (k in 0..2) assertEquals("color $i out $k", ref[k].coerceIn(0.0, 1.0), t.out[i * 3 + k].toDouble(), 0.01)
        }
    }

    @Test fun labCompensationKeepsMidGreyAndShowsUnderexposureInTheBlacks() {
        // grayscale (linear): black, mid gray, highlight
        val vals = listOf(0.005f, 0.18f, 0.8f)
        val img = FloatArray(vals.size * 3) { vals[it / 3] }
        fun out(lab: Boolean, ev: Float): FloatArray {
            val t = FilmFx.process(FilmFx.setup(Film(labComp = lab, ev = ev, grain = false, glare = false, halation = 0f, scatter = 0f, sharpen = 0f)), img, vals.size, 1, 0)
            return FloatArray(vals.size) { t.out[it * 3 + 1] }
        }
        val base = out(false, 0f)
        // without: the exposure makes the image darker/brighter
        assertTrue(out(false, -1.6f)[1] < base[1] - 0.15f)
        assertTrue(out(false, 1.6f)[1] > base[1] + 0.15f)
        // with: mid gray stays, underexposure gives grayer blacks, overexposure barely anything (the film's latitude)
        for (ev in listOf(-3f, -1.6f, 1.6f, 3f)) assertEquals("mid gray at $ev", base[1], out(true, ev)[1], 0.02f)
        assertTrue(out(true, -1.6f)[0] > base[0] + 0.02f)
        assertTrue(out(true, -3f)[0] > base[0] + 0.08f)
        assertEquals(base[0], out(true, 1.6f)[0], 0.01f)
    }

    @Test fun exposureAndPrintExposureDoNotRebuildTheTablesButMatchTheModel() {
        // the same tables (no recomputation while dragging the exposure) …
        val a = FilmFx.setup(Film(labComp = true, ev = -1.6f))
        val b = FilmFx.setup(Film(labComp = true, ev = 0.7f, printExposure = 1.4f))
        assertTrue(a.lutP === b.lutP && a.lutE === b.lutE)
        // … and the same image as spektrafilm's model computed with the settings (without spatial effects)
        val cols = listOf(floatArrayOf(0.18f, 0.18f, 0.18f), floatArrayOf(0.9f, 0.1f, 0.05f), floatArrayOf(0.02f, 0.4f, 0.8f), floatArrayOf(0.005f, 0.005f, 0.005f), floatArrayOf(0.8f, 0.8f, 0.8f))
        val img = FloatArray(cols.size * 3).also { x -> cols.forEachIndexed { i, c -> c.copyInto(x, i * 3) } }
        for (f0 in listOf(Film(labComp = true, ev = -1.6f), Film(labComp = true, ev = 2f), Film(labComp = false, ev = -1f, printExposure = 1.5f), Film(labComp = true, ev = -2.5f, printExposure = 0.6f))) {
            val f = f0.copy(scatter = 0f, couplers = 0f, grain = false, glare = false, sharpen = 0f, halation = 0f)
            val t = FilmFx.process(FilmFx.setup(f), img, cols.size, 1, 0)
            val m = Spektra.Model(f)
            for ((i, c) in cols.withIndex()) {
                val ref = m.linearToSrgb(doubleArrayOf(c[0].toDouble(), c[1].toDouble(), c[2].toDouble()))
                for (k in 0..2) assertEquals("lab ${f.labComp} ev ${f.ev} print ${f.printExposure} color $i channel $k", ref[k].coerceIn(0.0, 1.0), t.out[i * 3 + k].toDouble(), 0.01)
            }
        }
    }
}
