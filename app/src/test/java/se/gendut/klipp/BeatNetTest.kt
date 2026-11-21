package se.gendut.klipp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import se.gendut.klipp.engine.BeatNet
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class BeatNetTest {
    private fun floats(f: File) = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }

    @Test fun fftMatchesTheDefinition() {
        val n = 16
        val x = DoubleArray(n) { kotlin.math.sin(it * 0.7) + 0.3 * it }
        val re = x.copyOf(); val im = DoubleArray(n)
        BeatNet.fft(re, im)
        for (k in 0 until n) {
            var r = 0.0; var i = 0.0
            for (t in 0 until n) { r += x[t] * kotlin.math.cos(2 * Math.PI * k * t / n); i -= x[t] * kotlin.math.sin(2 * Math.PI * k * t / n) }
            assertEquals(r, re[k], 1e-9); assertEquals(i, im[k], 1e-9)
        }
    }

    @Test fun peaksAreLocalMaximaAboveZeroAndNeighboursMerge() {
        val l = FloatArray(100) { -5f }
        l[10] = 2f; l[11] = 2f   // two equal neighbours → one beat between them
        l[30] = 1f; l[32] = 3f   // within ±3: only the larger
        l[60] = -0.5f            // below zero: no beat
        val p = BeatNet.peaks(l)
        assertEquals(listOf(10.5 / 50, 32.0 / 50), p)
    }

    @Test fun windowsCoverEveryFrameOnce() = runBlocking {
        val n = 4000
        val f = Array(n) { FloatArray(128) { _ -> it.toFloat() } }
        val (b, _) = BeatNet.logits(f) { w -> val len = w.size / 128; FloatArray(len) { i -> w[i * 128] } to FloatArray(len) }
        for (i in 0 until n) assertEquals(i.toFloat(), b[i], 0f)
    }

    /** The front end as the reference script computes it (needs KLIPP_BEAT_DIR with pcm.f32, feats.f32, mel.bin). */
    @Test fun featuresMatchTheReference() {
        val dir = System.getenv("KLIPP_BEAT_DIR")
        assumeTrue(dir != null && File(dir, "feats.f32").isFile)
        val fb = BeatNet.filterbank()
        val ref = floats(File(dir, "mel.bin"))
        var worst = 0f
        for (m in 0 until 128) for (b in 0 until 513) {
            val (first, w) = fb[m]
            val v = if (b >= first && b < first + w.size) w[b - first] else 0f
            worst = maxOf(worst, abs(v - ref[b * 128 + m]))
        }
        assertTrue("filterbank off by $worst", worst < 1e-4f)
        val f = BeatNet.features(floats(File(dir, "pcm.f32")))
        val want = floats(File(dir, "feats.f32"))
        assertEquals(want.size / 128, f.size)
        var dmax = 0f
        for (t in f.indices) for (m in 0 until 128) dmax = maxOf(dmax, abs(f[t][m] - want[t * 128 + m]))
        println("beat features: largest difference $dmax")
        assertTrue(dmax < 1e-3f)
    }
}
