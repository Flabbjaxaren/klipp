package se.gendut.klipp

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.CaptionSnap
import se.gendut.klipp.engine.CaptionStack
import se.gendut.klipp.engine.CaptionStyle
import se.gendut.klipp.engine.CaptionTrackSnap
import se.gendut.klipp.engine.OverlayRenderer

/** Captions of two tracks at the same time and place are stacked, not drawn on each other. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CaptionStackTest {
    private val w = 1080
    private val h = 1920

    private fun box(text: String, st: CaptionStyle, shift: Float) = OverlayRenderer.captionBounds(text, st, w, h)!!.let { RectF(it.left, it.top + shift * h, it.right, it.bottom + shift * h) }

    @Test fun aSecondTrackAtTheSameTimeGoesAboveTheFirst() {
        val st = CaptionStyle()
        val tracks = listOf(CaptionTrackSnap(1, "Svenska", true, 1, style = st), CaptionTrackSnap(2, "English", true, 1, style = st))
        val caps = listOf(CaptionSnap(10, 0.0, 3.0, "Hej och välkommen", 1), CaptionSnap(20, 1.0, 3.0, "Hello and welcome", 2), CaptionSnap(30, 5.0, 2.0, "Later", 2))
        val s = CaptionStack.shifts(caps, tracks, w, h)
        assertEquals("the first track stays", null, s[10])
        assertEquals("no other caption then", null, s[30])
        val up = s[20]!!
        assertTrue("moved up: $up", up < 0f)
        assertTrue("clear of each other", !RectF.intersects(box("Hej och välkommen", st, 0f), box("Hello and welcome", st, up)))
    }

    @Test fun aTopTrackMovesDownAndOneTrackNeverMoves() {
        val top = CaptionStyle(anchor = "top")
        val tracks = listOf(CaptionTrackSnap(1, "A", true, 1, style = top), CaptionTrackSnap(2, "B", true, 1, style = top))
        val caps = listOf(CaptionSnap(10, 0.0, 3.0, "Uppe", 1), CaptionSnap(20, 0.0, 3.0, "Också uppe", 2))
        assertTrue(CaptionStack.shifts(caps, tracks, w, h)[20]!! > 0f)
        assertEquals(emptyMap<Long, Float>(), CaptionStack.shifts(caps.take(1), tracks.take(1), w, h))
        // one of them hidden: nothing to stack against
        assertEquals(emptyMap<Long, Float>(), CaptionStack.shifts(caps.take(1), listOf(tracks[0], tracks[1].copy(enabled = false)), w, h))
    }

    /** Two captions at the same time are played in separate overlays (one overlay draws one item at a time); a caption's word steps share one. */
    @Test fun simultaneousCaptionsGetALaneEach() {
        val a = se.gendut.klipp.engine.Overlay.Caption("Hej")
        val items = listOf(
            se.gendut.klipp.engine.OverlayItem(0.0, 1.0, a.copy(word = 0)), se.gendut.klipp.engine.OverlayItem(1.0, 2.0, a.copy(word = 1)),
            se.gendut.klipp.engine.OverlayItem(0.5, 1.5, se.gendut.klipp.engine.Overlay.Caption("Hello")),
        )
        val lanes = se.gendut.klipp.engine.overlayLanes(items)
        assertEquals(2, lanes.size)
        assertEquals(listOf(2, 1), lanes.map { it.size })
    }
}
