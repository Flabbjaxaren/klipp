package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.Placement
import se.gendut.klipp.engine.inRange
import se.gendut.klipp.engine.toSettings

/**
 * An overlay placed beyond the frame's edge (a title bouncing in, a clip sliding in) must not fail the export: Media3's
 * overlay settings throw for an anchor outside −1..1 (found on the phone: "Video frame processing error").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlacementRangeTest {
    /** Where the overlay's point [x] (overlay NDC) lands in the frame, without rotation: bg + scale · (x − ov). */
    private fun lands(p: Placement, x: Float) = p.bgX + p.scale * (x - p.ovX)

    @Test fun anAnchorOutsideTheFrameIsMovedToTheOverlaysOwn() {
        val p = Placement(-0.8f, -0.7f, -1.35f, -0.7f, 1f, 0f, 1f)
        val q = p.inRange()
        assertTrue(q.bgX in -1f..1f && q.ovX in -1f..1f)
        // every point of the overlay lands where it did
        for (x in listOf(-1f, 0f, 0.5f)) assertEquals(lands(p, x), lands(q, x), 1e-5f)
        q.toSettings()          // doesn't throw
        p.toSettings()
        // inside the range: unchanged
        val ok = Placement(0f, 0f, 0.3f, -0.2f, 1.2f, 10f, 1f)
        assertEquals(ok, ok.inRange())
        // scaled: the same points too
        val s = Placement(0f, 0f, 1.3f, 0f, 0.5f, 0f, 1f)
        for (x in listOf(-1f, 0f, 1f)) assertEquals(lands(s, x), lands(s.inRange(), x), 1e-5f)
    }
}
