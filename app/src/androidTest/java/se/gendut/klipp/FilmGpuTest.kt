package se.gendut.klipp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.FilmFx
import se.gendut.klipp.engine.FilmGl
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.LiveGrades
import se.gendut.klipp.engine.Spektra
import kotlin.math.abs

/**
 * The film on the phone's GPU ([FilmGl.Program], as playback runs it) against the CPU ([FilmFx.applyEncoded], as paused
 * frames and the tests compute it): whole film, effects only, a change while it plays (the next frame must not be
 * black), halation around a bright spot. Grain is off (it is random, differently on each side).
 */
@RunWith(AndroidJUnit4::class)
class FilmGpuTest {
    private val w = 160
    private val h = 90
    private lateinit var gl: GlHarness

    @Before fun gl() {
        Spektra.init(InstrumentationRegistry.getInstrumentation().targetContext)
        gl = GlHarness(w, h)
        assertTrue("the phone renders to float textures", FilmGl.floatTargets())
    }

    @After fun release() = gl.close()

    /** A test picture in code values: gradients, a gray patch and a small bright spot. */
    private val image = FloatArray(w * h * 3).also { a ->
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            a[i] = x / (w - 1f); a[i + 1] = y / (h - 1f); a[i + 2] = 0.35f
            if (x in 20..40 && y in 20..40) { a[i] = 0.45f; a[i + 1] = 0.45f; a[i + 2] = 0.45f }
            if (x in 110..115 && y in 40..45) { a[i] = 1f; a[i + 1] = 1f; a[i + 2] = 1f }
        }
    }

    private fun upload(img: FloatArray) = gl.upload(img)
    private fun draw(p: FilmGl.Program, input: Int) = gl.draw(p, input)

    private fun program(f: Film, live: Pair<Long, Int>? = null) = FilmGl.Program(f, live, false).also { it.configure(w, h) }

    private fun cpu(f: Film) = FilmFx.applyEncoded(FilmFx.setup(f), image, w, h, 0)

    /** Mean and 99th percentile of the absolute difference. */
    private fun diff(a: FloatArray, b: FloatArray): Pair<Float, Float> {
        val d = FloatArray(a.size) { abs(a[it] - b[it]) }
        return d.average().toFloat() to d.sorted()[(d.size * 0.99).toInt()]
    }

    private fun mean(a: FloatArray) = a.average().toFloat()

    private fun assertClose(what: String, gpu: FloatArray, cpu: FloatArray) {
        val (m, p99) = diff(gpu, cpu)
        android.util.Log.i("FilmGpuTest", "$what: mean %.4f p99 %.4f (gpu mean %.3f, cpu mean %.3f)".format(m, p99, mean(gpu), mean(cpu)))
        assertTrue("$what: GPU and CPU differ, mean $m", m < 0.02f)
        assertTrue("$what: GPU and CPU differ, 99th percentile $p99", p99 < 0.08f)
    }

    @Test fun wholeFilmMatchesTheCpu() {
        val f = Film(grain = false)
        val input = upload(image)
        val p = program(f)
        assertClose("whole film", draw(p, input), cpu(f))
        p.release()
    }

    @Test fun effectsOnlyMatchesTheCpuAndKeepsTheColors() {
        val f = Film(grain = false, effectsOnly = true)
        val input = upload(image)
        val p = program(f)
        val g = draw(p, input)
        assertClose("effects only", g, cpu(f))
        // away from the bright spot the picture is (nearly) the input: the colors are not the film's
        val i = (30 * w + 30) * 3
        assertTrue("gray patch stays gray: ${g[i]} ${g[i + 1]} ${g[i + 2]}", abs(g[i] - 0.45f) < 0.04f && abs(g[i + 1] - 0.45f) < 0.04f && abs(g[i + 2] - 0.45f) < 0.04f)
        p.release()
    }

    @Test fun aChangeWhilePlayingIsNotBlack() {
        val key = 9001L to 7
        val a = Film(grain = false, effectsOnly = true)
        val b = a.copy(halation = 2f, ev = 1f)
        val input = upload(image)
        val p = program(a, key)
        draw(p, input)
        // the panel's slider moves: the next frame takes the new film (new tables) – 0.18.8 drew it black
        LiveGrades.put(key.first, GradeNode(key.second, film = b))
        val g = draw(p, input)
        assertTrue("the frame after the change isn't black: mean ${mean(g)}", mean(g) > 0.2f)
        assertClose("after a change", g, cpu(b))
        // and back to whole film
        val c = Film(grain = false)
        LiveGrades.put(key.first, GradeNode(key.second, film = c))
        assertClose("after switching to whole film", draw(p, input), cpu(c))
        p.release()
    }

    @Test fun halationGlowsAroundABrightSpot() {
        // the test picture is small (a pixel is ~225 µm of 35 mm film): the halation is made larger to reach a few pixels
        val f = Film(grain = false, effectsOnly = true, halation = 3f, halationSize = 8f)
        val input = upload(image)
        val p = program(f)
        val with = draw(p, input)
        val p0 = program(f.copy(halation = 0f, scatter = 0f))
        val without = draw(p0, input)
        // the glow around the spot: without it (the effects not running, as in 0.18.7) the two pictures were identical
        var spread = 0f
        for (y in 30 until 56) for (x in 100 until 126) {
            if (x in 110..115 && y in 40..45) continue
            val i = (y * w + x) * 3
            spread += with[i] - without[i]
        }
        android.util.Log.i("FilmGpuTest", "halation: red spread %.3f".format(spread))
        assertTrue("halation reaches outside the spot: red spread $spread", spread > 0.1f)
        assertClose("halation", with, cpu(f))
        p.release(); p0.release()
    }
}
