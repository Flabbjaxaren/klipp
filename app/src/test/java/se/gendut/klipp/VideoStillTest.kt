package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.VideoStill

/** The video-chain still's read: 16 bits over −0.5..1.5 as a high and a low byte (as the encode shader writes them). */
class VideoStillTest {
    /** What the shader writes for a value: (high byte, low byte). */
    private fun encode(v: Float): Pair<Byte, Byte> {
        val u = Math.floor(((v + 0.5) / 2.0).coerceIn(0.0, 1.0) * 65535.0 + 0.5).toInt()
        return (u / 256).toByte() to (u % 256).toByte()
    }

    @Test fun valuesBeyondBlackAndWhiteComeBackWithin16BitSteps() {
        val step = 2.0 / 65535
        for (v in listOf(-0.5f, -0.07f, 0f, 0.18f, 0.5f, 1f, 1.068f, 1.09f, 1.5f)) {
            val (hi, lo) = encode(v)
            assertEquals("$v", v.toDouble(), VideoStill.value(hi, lo).toDouble(), step / 2 + 1e-6)
        }
        // the ends of the range
        assertEquals(-0.5f, VideoStill.value(0, 0), 0f)
        assertEquals(1.5f, VideoStill.value(-1, -1), 0f)
    }
}
