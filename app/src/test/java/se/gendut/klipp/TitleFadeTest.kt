package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.TitleAnim
import se.gendut.klipp.engine.TitleLook
import se.gendut.klipp.engine.TitleMotion
import se.gendut.klipp.engine.TitleTemplate

/** A title with no entrance is fully there from its first to its last frame; with one it fades out at the end. */
class TitleFadeTest {
    @Test fun noAnimationNoFades() {
        val look = TitleLook(TitleTemplate.FREE, TitleAnim.NONE)
        for (t in listOf(0.0, 0.04, 2.0, 3.9, 3.96)) assertEquals("t=$t", 1f, TitleMotion.pose(look, t, 4.0, 1920, 1080).alpha, 0f)
    }

    @Test fun withAnimationFadesOutAtTheEnd() {
        val look = TitleLook(TitleTemplate.FREE, TitleAnim.SLIDE)
        assertTrue(TitleMotion.pose(look, 3.9, 4.0, 1920, 1080).alpha < 0.5f)
        assertEquals(1f, TitleMotion.pose(look, 2.0, 4.0, 1920, 1080).alpha, 0f)
    }

    /** The playhead is a Float: 4.04 s comes out a hair before a title starting at 4.04 – it must count as inside. */
    @Test fun playheadFloatAtTheTitlesStart() {
        val start = 4.04
        val t = start.toFloat().toDouble()
        assertTrue("the Float is below the start ($t)", t < start)
        assertTrue(t >= start - 5e-4)
    }
}
