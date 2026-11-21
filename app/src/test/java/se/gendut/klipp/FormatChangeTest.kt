package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.ClipSnap
import se.gendut.klipp.engine.FormatChange
import se.gendut.klipp.engine.Kf
import se.gendut.klipp.engine.KfKeys
import se.gendut.klipp.engine.SequenceSnap
import se.gendut.klipp.engine.TrackSnap

class FormatChangeTest {
    private fun clip(id: Long, x: Double?, y: Double?, kf: Map<String, List<Kf>> = emptyMap()) =
        ClipSnap(id, 1, "c", 0.0, 4.0, 0.0, 1.0, null, x = x, y = y, kf = kf)

    private fun seq(vararg c: ClipSnap) = SequenceSnap(1920, 1080, 4.0, listOf(TrackSnap(1, "V1", false, false, c.toList())), emptyList())

    @Test fun centeredClipStaysCenteredWhenLandscapeTurnsPortrait() {
        // the engine writes the center when a clip is placed: (960, 540) in 1920×1080
        val m = FormatChange.moves(seq(clip(9, 960.0, 540.0)), 1080, 1920).single()
        assertEquals(540.0, m.x, 1e-9)
        assertEquals(960.0, m.y, 1e-9)
    }

    @Test fun movedClipKeepsItsPlaceRelativeToTheFrame() {
        val m = FormatChange.moves(seq(clip(9, 1440.0, 270.0)), 1080, 1920).single()
        assertEquals(810.0, m.x, 1e-9)   // 3/4 across
        assertEquals(480.0, m.y, 1e-9)   // 1/4 down
    }

    @Test fun positionKeyframesFollowToo() {
        val kf = mapOf(KfKeys.POSITION to listOf(Kf(0, 0.0, listOf(0.0, 540.0)), Kf(100, 1.0, listOf(1920.0, 540.0))))
        val m = FormatChange.moves(seq(clip(9, 960.0, 540.0, kf)), 1080, 1920).single()
        assertEquals(listOf(0L to listOf(0.0, 960.0), 100L to listOf(1080.0, 960.0)), m.keys)
    }

    @Test fun nothingMovesWithoutAPositionOrASizeChange() {
        assertTrue(FormatChange.moves(seq(clip(9, null, null)), 1080, 1920).isEmpty())
        assertTrue(FormatChange.moves(seq(clip(9, 960.0, 540.0)), 1920, 1080).isEmpty())
    }
}
