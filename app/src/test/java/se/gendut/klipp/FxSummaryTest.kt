package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.AfxSnap

/** The effect card's one-line summary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FxSummaryTest {
    private fun eq(vararg p: Pair<String, Double>) = AfxSnap(0, "parametric_eq", true,
        mapOf("master_gain" to 0.0, "low_freq" to 100.0, "low_gain" to 0.0, "low_on" to 1.0,
            "mid_freq" to 1000.0, "mid_gain" to 0.0, "mid_on" to 1.0, "high_freq" to 8000.0, "high_gain" to 0.0, "high_on" to 1.0) + p)

    @Test fun eqNamesTheBandsThatAreRaisedOrLowered() {
        val s = fxSummary(eq("mid_gain" to 12.9, "low_gain" to -3.0))
        // lowest frequency first; the flat bands and the output are left out
        assertEquals(listOf(hz(100.0) + " " + db(-3.0), hz(1000.0) + " " + db(12.9)).joinToString(" · "), s)
    }

    @Test fun eqBandThatIsOffIsNotNamed() {
        val s = fxSummary(eq("high_gain" to 6.0, "high_on" to 0.0))
        assertTrue(s, !s.contains(hz(8000.0)))
    }

    @Test fun flatEqFallsBackToTheOutput() {
        assertTrue(fxSummary(eq()).isNotEmpty())
    }
}
