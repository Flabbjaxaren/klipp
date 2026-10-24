package se.gendut.klipp

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.GpuGrade
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.Look
import se.gendut.klipp.engine.Wheel
import kotlin.math.abs

/**
 * The drag preview's GPU grading of a still (GpuGrade, AGSL through HardwareRenderer) against the CPU's grade of the
 * same image: the result is read back right (channel order, padded rows) at the preview size, an odd size, and when
 * the size switches back and forth as it does while scrubbing.
 */
@RunWith(AndroidJUnit4::class)
class GpuGradeStillTest {
    private fun picture(w: Int, h: Int): Bitmap {
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or (((x + y) * 3) and 255) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private val grade = ClipGrade(listOf(GradeNode(1, gain = Wheel(0.2f, -0.1f, 0.1f), sat = 1.3f, contrast = 1.15f, temp = 0.2f, density = 0.6f)))

    private fun check(w: Int, h: Int) {
        val src = picture(w, h)
        val gpu = GpuGrade.apply(src, grade, Look.NONE)
        assertTrue("GPU grading available", gpu != null)
        val cpu = grade.applyTo(src.copy(Bitmap.Config.ARGB_8888, true))
        var sum = 0.0; var max = 0
        for (y in 0 until h step 3) for (x in 0 until w step 3) {
            val a = gpu!!.getPixel(x, y); val b = cpu.getPixel(x, y)
            for (sh in intArrayOf(16, 8, 0)) { val d = abs(((a shr sh) and 255) - ((b shr sh) and 255)); sum += d; if (d > max) max = d }
        }
        val mean = sum / ((w / 3 + 1) * (h / 3 + 1) * 3)
        android.util.Log.i("GpuGradeStillTest", "${w}x$h: mean %.3f max %d".format(mean, max))
        assertTrue("${w}x$h: mean difference $mean code values", mean < 1.0)
        assertTrue("${w}x$h: largest difference $max code values", max <= 6)
    }

    @Test fun previewSize() = check(1920, 1080)
    @Test fun oddSize() = check(333, 187)
    @Test fun switchingSizes() { check(1920, 1080); check(480, 270); check(1920, 1080); check(333, 187); check(480, 270) }
}
