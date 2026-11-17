package se.gendut.klipp

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.FilmGl
import se.gendut.klipp.engine.GlStill
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.Look
import se.gendut.klipp.engine.Spektra
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The colour page's still while a control is dragged (GlStill: playback's own GPU programs on a still): with the
 * clip's original size it must be playback's film at that scale (N1), and differ from the unscaled film.
 */
@RunWith(AndroidJUnit4::class)
class FilmDragStillTest {
    @Before fun init() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        Spektra.init(c)
        GlStill.init(c)
    }

    private val w = 480
    private val h = 270

    private fun card(): FloatArray = FloatArray(w * h * 3) { i ->
        val p = i / 3; val x = p % w; val y = p / w
        val fx = x.toDouble() / w; val fy = y.toDouble() / h
        when {
            fx in 0.10..0.35 && fy in 0.20..0.60 -> 0.80f
            fx in 0.45..0.50 && fy in 0.10..0.90 -> 0.12f
            fx in 0.60..0.90 && fy in 0.20..0.80 -> (0.45 + 0.2 * sin(2 * PI * x * 32.0 / w)).toFloat()
            else -> 0.38f
        }
    }

    private fun bitmap(img: FloatArray): Bitmap {
        val px = IntArray(w * h) { p ->
            val r = (img[p * 3] * 255 + 0.5f).toInt(); val g = (img[p * 3 + 1] * 255 + 0.5f).toInt(); val b = (img[p * 3 + 2] * 255 + 0.5f).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun floats(b: Bitmap): FloatArray {
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        return FloatArray(w * h * 3) { i -> val c = px[i / 3]; ((c shr (16 - 8 * (i % 3))) and 255) / 255f }
    }

    @Test fun theDragStillUsesTheScale() {
        val f = Film(grain = false, glare = false)
        val img = card()
        // (the 8-bit still as input, as the colour page hands it over)
        val in8 = floats(bitmap(img))
        val grade = ClipGrade(listOf(GradeNode(1, film = f)))
        val ref = 2 * h
        val still = GlStill.apply(bitmap(img), grade, Look.NONE, ref); assertNotNull("the GPU still works on this phone (${GlStill.broken})", still)
        val still0 = GlStill.apply(bitmap(img), grade, Look.NONE, 0)!!
        val play = GlHarness(w, h).use { gl -> val p = FilmGl.Program(f, null, false, false, ref).also { it.configure(w, h) }
            try { gl.drawFloat(p, gl.upload(in8)) } finally { p.release() } }
        val s = floats(still!!); val s0 = floats(still0)
        val d = FloatArray(s.size) { abs(s[it] - play[it]) }
        val moved = FloatArray(s.size) { abs(s[it] - s0[it]) }.average()
        android.util.Log.i("FilmDragStillTest", "still vs playback's film at the same scale: mean %.4f p99 %.4f; still with vs without the scale: mean %.4f".format(
            d.average(), d.sorted()[(d.size * 0.99).toInt()], moved))
        assertTrue("the drag still is playback's film at this scale (mean ${d.average()})", d.average() < 0.004 && d.sorted()[(d.size * 0.99).toInt()] < 0.012)
        assertTrue("the scale reaches the drag still (mean change $moved)", moved > 0.001)
    }
}
