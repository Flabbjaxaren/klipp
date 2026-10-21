package se.gendut.klipp

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.GradeNode
import kotlin.math.abs

/** White balance with the eyedropper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ColorInputTest {
    @Test fun whiteBalanceNeutralizesPickedColor() {
        val n = GradeNode(1, "A", contrast = 1.1f)
        // a white wall that has turned bluish
        val src = floatArrayOf(0.62f, 0.66f, 0.80f)
        val (t, u) = GradeNode.whiteBalance(n, src)
        val out = src.copyOf()
        GradeNode.apply(n.copy(temp = t, tint = u).params(), out)
        val l = out.average().toFloat()
        assertTrue("${out.toList()}", out.all { abs(it - l) / l < 0.03f })
        assertTrue("warmer: $t", t > 0f)
    }
}
