package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.PeakLevels
import se.gendut.klipp.engine.Peaks

/**
 * The waveform and filmstrip while a clip's edge is trimmed: they show the part of the source the box covers now and
 * keep the material where it is (the old range was spread over the new width until release); the peak pyramid gives
 * the same peaks as the fine values at every zoom.
 */
class WaveformSpanTest {
    private fun span(srcIn: Float, len: Float, speed: Float = 1f, reverse: Boolean = false, hold: Boolean = false, liveLen: Float = len, leftCut: Float = 0f) =
        liveSourceSpan(srcIn, len, speed, reverse, hold, liveLen, leftCut)

    @Test fun spanFollowsTheTrim() {
        // untouched: the clip's own source
        assertEquals(10.0 to 14.0, span(10f, 4f))
        // right edge dragged out 1 s: the same start, one more second
        assertEquals(10.0 to 15.0, span(10f, 4f, liveLen = 5f))
        // left edge dragged in 1 s: the first second is cut, the end stays
        assertEquals(11.0 to 14.0, span(10f, 4f, liveLen = 3f, leftCut = 1f))
        // left edge dragged out 2 s (into the handle)
        assertEquals(8.0 to 14.0, span(10f, 4f, liveLen = 6f, leftCut = -2f))
        // at 200 %: a timeline second is two source seconds
        assertEquals(12.0 to 18.0, span(10f, 4f, speed = 2f, liveLen = 3f, leftCut = 1f))
        // reversed: the box's start shows the source's end; trimming the start cuts from the end
        assertEquals(18.0 to 10.0, span(10f, 4f, speed = 2f, reverse = true))
        assertEquals(16.0 to 10.0, span(10f, 4f, speed = 2f, reverse = true, liveLen = 3f, leftCut = 1f))
        // a freeze frame: its frame throughout
        assertEquals(10.0 to 10.0, span(10f, 4f, hold = true, liveLen = 2f, leftCut = 2f))
    }

    @Test fun barsStandStillWhileTheLeftEdgeIsDragged() {
        val pps = 100f
        val dSrc = waveBarSource(4f, pps, 1f)
        assertEquals(0.04, dSrc, 1e-9)
        // each bar's place on the timeline (the box's left edge + the bar in the box) for a few trims
        fun bars(leftCut: Float): Map<Long, Float> {
            val liveLen = 4f - leftCut
            val (a, b) = span(10f, 4f, liveLen = liveLen, leftCut = leftCut)
            val out = HashMap<Long, Float>()
            forEachWaveBar(a, b, liveLen * pps, dSrc) { x, t0 -> out[Math.round(t0 / dSrc)] = (5f + leftCut) * pps + x }
            return out
        }
        val before = bars(0f)
        for (cut in listOf(0.37f, 1f, 2.5f, -0.8f)) {
            val now = bars(cut)
            assertTrue("bars for $cut", now.isNotEmpty())
            for ((k, x) in now) before[k]?.let { assertEquals("bar $k at cut $cut", it, x, 0.01f) }
        }
        // the bars cover the box and nothing outside it
        val (a, b) = span(10f, 4f)
        var n = 0
        forEachWaveBar(a, b, 400f, dSrc) { x, _ -> assertTrue(x in 0f..400f); n++ }
        assertEquals(100, n)
    }

    @Test fun reversedBarsRunRightToLeft() {
        val xs = ArrayList<Pair<Float, Double>>()
        forEachWaveBar(14.0, 10.0, 400f, 0.5) { x, t0 -> xs += x to t0 }
        assertEquals(8, xs.size)
        // the latest source is at the left
        assertTrue(xs.minBy { it.first }.second > xs.maxBy { it.first }.second)
    }

    @Test fun pyramidPeaksMatchTheFineValues() {
        val r = java.util.Random(3)
        val fine = ByteArray(12_345) { (r.nextInt(200) + if (r.nextInt(50) == 0) 55 else 0).toByte() }
        val p = PeakLevels(fine)
        assertTrue(p.levelCount > 4)
        fun brute(t0: Double, t1: Double): Float {
            val a = kotlin.math.floor(t0 * Peaks.FINE_RATE).toInt().coerceAtLeast(0)
            val b = kotlin.math.ceil(t1 * Peaks.FINE_RATE).toInt().coerceAtMost(fine.size).coerceAtLeast(a + 1)
            var m = 0
            for (k in a until minOf(b, fine.size)) m = maxOf(m, fine[k].toInt() and 255)
            return m / 255f
        }
        repeat(2000) {
            val t0 = r.nextDouble() * 12.0
            val len = Math.pow(10.0, -3.0 + r.nextDouble() * 4)
            val got = p.peak(t0, t0 + len)
            val want = brute(t0, t0 + len)
            // a coarse level may reach a little past the span (never miss a peak inside it)
            assertTrue("peak $t0 +$len: $got < $want", got >= want - 1e-6f)
            if (len < 0.008) assertEquals("fine level for short spans", want, got, 1e-6f)
        }
        // the 100-per-second values (beats) are the largest of each ten
        val c = p.coarse()
        assertEquals(1235, c.size)
        for (i in c.indices) assertEquals((i * 10 until minOf(fine.size, i * 10 + 10)).maxOf { fine[it].toInt() and 255 } / 255f, c[i], 1e-6f)
    }

    @Test fun filmTilesArePowersOfTwoAndAtMostAboutEight() {
        for (pps in listOf(5f, 37f, 120f, 900f)) for (len in listOf(0.5f, 3f, 40f)) {
            val d = filmTileSource(170f, pps, 1f, len)
            val l = kotlin.math.log2(d)
            assertEquals(Math.round(l).toDouble(), l, 1e-9)
            assertTrue("$pps $len: $d", len / d <= 8.0 + 1e-9)
        }
    }
}
