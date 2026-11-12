package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A layer's frames after a start: never handed out together (a TextureView would show only the second). */
class EarlyRampTest {
    @Test fun aStartRampsUpOneFrameAtATime() {
        // 25 fps, threshold 65 ms; playback starts at 0, the first frame is due at 40 ms. Each frame goes out as soon as
        // it is within the threshold (checked every 10 ms, as the renderer does).
        val max = 65_000L
        var threshold = max
        var lastRelease = 0L
        val out = mutableListOf<Long>()
        var now = 0L
        for (k in 1..12) {
            val due = k * 40_000L
            while (due - now > threshold) now += 10_000
            out += now
            val gap = if (lastRelease > 0) due - lastRelease else 0L
            lastRelease = due
            threshold = EarlyRamp.next(due - now, gap, max)
        }
        // the first two frames were within 65 ms of the start together – now ≥ 60 % of a frame apart
        for ((a, b) in out.zipWithNext()) assertTrue("handed out at $out", b - a >= 24_000)
        // and back at the full threshold within a few frames
        assertEquals(max, threshold)
    }

    @Test fun slackFollowsTheFrameTime() {
        assertEquals(16_000L, EarlyRamp.slackUs(40_000))
        assertEquals(6_666L, EarlyRamp.slackUs(16_666))
        assertEquals(EarlyRamp.SLACK_US, EarlyRamp.slackUs(0))
        // never above the layer's own threshold, never below the slack
        assertEquals(35_000L, EarlyRamp.next(60_000, 40_000, 35_000))
        assertEquals(16_000L, EarlyRamp.next(-5_000, 40_000, 65_000))
    }
}
