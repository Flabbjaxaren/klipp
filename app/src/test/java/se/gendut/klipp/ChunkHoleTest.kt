package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.gendut.klipp.engine.RenderCache

/** A pre-rendered chunk with frames missing is found (and thrown away) – it froze playback for the length of the hole. */
class ChunkHoleTest {
    private fun frames(vararg us: Long) = us.toList()
    private fun steady(n: Int, from: Long = 0, step: Long = 40_000) = (0 until n).map { from + it * step }

    @Test fun wholeChunkHasNoHole() = assertNull(RenderCache.holeIn(steady(133)))

    @Test fun theVivoChunkFirstFrameThenFourSeconds() {
        // the user's chunk 2.64–7.96 s: frame 0, then 4.00–5.28 s
        assertEquals(4_000_000L, RenderCache.holeIn(frames(0) + steady(33, 4_000_000)))
    }

    @Test fun aFileStartingLateIsAHole() = assertEquals(1_000_000L, RenderCache.holeIn(steady(50, 1_000_000)))

    @Test fun slowFramesAreNoHole() {
        // a 2 fps stretch is its own rate, not a hole – and a single dropped frame neither
        assertNull(RenderCache.holeIn(steady(10, step = 500_000)))
        assertNull(RenderCache.holeIn(steady(20) + steady(20, 20 * 40_000 + 40_000)))
    }
}
