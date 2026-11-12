package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.gendut.klipp.engine.BackgroundWork

/** The export goes first: background jobs hear when it starts and ends, once each (plan PR 8, risk 9). */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class BackgroundWorkTest {
    @Test fun jobsHoldWhileAnExportRunsAndResumeAfter() {
        val heard = ArrayList<Boolean>()
        BackgroundWork.listen { heard += it }
        BackgroundWork.export(true)
        assertTrue(BackgroundWork.exporting)
        // a second start (or end) is not told again
        BackgroundWork.export(true)
        BackgroundWork.export(false)
        BackgroundWork.export(false)
        assertFalse(BackgroundWork.exporting)
        assertEquals(listOf(true, false), heard)
    }
}
