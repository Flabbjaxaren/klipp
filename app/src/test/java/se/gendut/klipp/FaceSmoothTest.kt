package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.FaceSmooth
import se.gendut.klipp.engine.WindowTracker.Sample
import kotlin.math.abs

class FaceSmoothTest {
    @Test fun framesWithoutTheFaceAreLeftOut() {
        val s = (0 until 10).map { Sample(it / 30.0, it * 0.01f, 0f, 1f, if (it == 4) 0f else 0.9f) }
        val out = FaceSmooth.apply(s)
        assertEquals(9, out.size)
        assertTrue(out.none { abs(it.t - 4 / 30.0) < 1e-9 })
    }

    @Test fun sizeJitterIsSmoothed() {
        // a face box whose size wobbles ±5 % every other frame
        val s = (0 until 60).map { Sample(it / 30.0, 0f, 0f, if (it % 2 == 0) 1.05f else 0.95f, 0.9f) }
        val out = FaceSmooth.apply(s)
        val mid = out.subList(10, 50)
        assertTrue(mid.all { abs(it.s - 1f) < 0.01f })
    }

    @Test fun steadyMotionIsKept() {
        val s = (0 until 60).map { Sample(it / 30.0, it * 0.005f, 0f, 1f, 0.9f) }
        val out = FaceSmooth.apply(s)
        for (i in 10 until 50) assertEquals(i * 0.005f, out[i].dx, 1e-4f)
    }
}
