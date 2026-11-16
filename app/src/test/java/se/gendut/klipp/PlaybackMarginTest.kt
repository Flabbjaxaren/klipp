package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.PlaybackMargin
import se.gendut.klipp.engine.PlaybackMargin.Frame

/** Which chunks the live preview didn't keep up with: two late frames or one dropped, in the timeline's time. */
class PlaybackMarginTest {
    private val chunks = listOf(Triple(0.0, 1.6, "a"), Triple(1.6, 3.2, "b"), Triple(3.2, 4.8, "c"))

    @Test fun twoLateFramesOrOneDroppedMakeAChunkSlow() {
        val late = listOf(Frame(1_700_000, false), Frame(1_740_000, false), Frame(3_300_000, false), Frame(4_000_000, true))
        assertEquals(setOf("b", "c"), PlaybackMargin.lateIn(chunks, late, 0L))
    }

    @Test fun aSingleLateFrameIsNotEnough() {
        assertEquals(emptySet<String>(), PlaybackMargin.lateIn(chunks, listOf(Frame(500_000, false)), 0L))
    }

    @Test fun framesAreInTheCompositionsTimeFromItsOrigin() {
        // a composition starting at 1.6 s on the timeline: its 0.1 s is the timeline's 1.7 s
        val late = listOf(Frame(100_000, false), Frame(140_000, false))
        assertEquals(setOf("b"), PlaybackMargin.lateIn(chunks, late, 1_600_000L))
    }
}
