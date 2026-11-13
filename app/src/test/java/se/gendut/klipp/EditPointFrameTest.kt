package se.gendut.klipp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The edit point layer is seen from the frame that covers the edit point, also off the main player's frame grid. */
class EditPointFrameTest {
    @Test fun onTheGridTheFrameBeforeTheCutEndsAtItAndDoesNotCount() {
        // 25 fps, cut at 62.5 ms item time... on the grid: frames at 22 500 and 62 500
        assertFalse(EditPointFrame.covers(22_500, 40_000, 62_500))
        assertTrue(EditPointFrame.covers(62_500, 40_000, 62_500))
    }

    @Test fun offTheGridTheFrameReachingOverTheCutCounts() {
        // 120 fps at 25 % (a frame every 8 333 µs of item time): 57 000 runs to 65 333, over the cut at 62 500
        assertTrue(EditPointFrame.covers(57_000, 8_333, 62_500))
        // the one before ends before the cut
        assertFalse(EditPointFrame.covers(48_666, 8_333, 62_500))
    }

    @Test fun withoutAStepOnlyFromTheCutOn() {
        assertFalse(EditPointFrame.covers(57_000, null, 62_500))
        assertTrue(EditPointFrame.covers(61_600, null, 62_500))
    }
}
