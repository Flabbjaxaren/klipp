package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimClampTest {
    @Test fun anEmptyRangeKeepsTheLowerBoundInsteadOfThrowing() {
        // the crash from 0.17.2: a 0.28 s clip right after its neighbour (lo 2.08, end − 0.5 = 1.86)
        assertEquals(2.08f, 1.5f.coerceInSafe(2.08f, 1.86f), 0f)
        assertEquals(2.08f, 3.0f.coerceInSafe(2.08f, 1.86f), 0f)
    }

    @Test fun aNormalRangeClampsAsUsual() {
        assertEquals(1f, 0.5f.coerceInSafe(1f, 2f), 0f)
        assertEquals(1.5f, 1.5f.coerceInSafe(1f, 2f), 0f)
        assertEquals(2f, 2.5f.coerceInSafe(1f, 2f), 0f)
    }
}
