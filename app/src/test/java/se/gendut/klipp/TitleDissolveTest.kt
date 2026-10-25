package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipSnap
import se.gendut.klipp.engine.LayerPlanner
import se.gendut.klipp.engine.TextSnap
import se.gendut.klipp.engine.TrackSnap
import se.gendut.klipp.engine.TransitionSnap
import se.gendut.klipp.engine.overlayLanes

/** A transition between two titles: they dissolve into each other (preview and export), a dip goes out then in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TitleDissolveTest {
    private fun title(id: Long, start: Double) = ClipSnap(id, 1, "T$id", start, 4.0, 0.0, 1.0, null, text = TextSnap("T$id", 80.0, null, null))
    private val a = title(1, 0.0)
    private val b = title(2, 4.0)

    private fun items(effect: String) = run {
        val t = TrackSnap(10, "V1", false, false, listOf(a, b), transitions = listOf(TransitionSnap(5, effect, 3.5, 1.0, 1, 2)))
        val byId = listOf(a, b).associateBy { it.id }
        listOf(a, b).map { LayerPlanner.titleItem(it, t, byId, null, null) }
    }

    @Test fun crossDissolveOverlapsAndFades() {
        val (x, y) = items("cross_dissolve")
        // the outgoing title lasts to the transition's end, the incoming one begins at its start
        assertTrue(x.shows(4.2)); assertTrue(y.shows(3.7))
        assertFalse(x.shows(4.5)); assertFalse(y.shows(3.4))
        // halfway: half each; at the edges all of one
        assertEquals(0.5f, x.alpha(4.0), 1e-4f); assertEquals(0.5f, y.alpha(4.0), 1e-4f)
        assertEquals(1f, x.alpha(3.5), 1e-4f); assertEquals(1f, y.alpha(4.5), 1e-4f)
        // the clip's own times (motion, entrance, following) are unchanged
        assertEquals(4.0, y.start, 0.0); assertEquals(4.0, x.end, 0.0)
        // in two lanes, so the export's overlays can draw both at once
        assertEquals(2, overlayLanes(listOf(x, y)).size)
    }

    @Test fun dipGoesOutThenIn() {
        val (x, y) = items("dip_to_black")
        assertFalse(x.shows(4.2)); assertFalse(y.shows(3.7))
        assertEquals(0f, x.alpha(3.999), 0.01f); assertEquals(0f, y.alpha(4.0), 1e-4f)
        assertEquals(1, overlayLanes(listOf(x, y)).size)
    }

    @Test fun noTransitionNoOverlap() {
        val t = TrackSnap(10, "V1", false, false, listOf(a, b))
        val byId = listOf(a, b).associateBy { it.id }
        val x = LayerPlanner.titleItem(a, t, byId, null, null)
        assertEquals(4.0, x.shownTo, 0.0)
        assertEquals(1f, x.alpha(3.9), 0f)
    }
}
