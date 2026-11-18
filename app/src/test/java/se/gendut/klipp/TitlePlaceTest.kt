package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.FrameSpan
import se.gendut.klipp.engine.TitlePlace
import se.gendut.klipp.engine.TitlePlaceProblem
import se.gendut.klipp.engine.titlePlace

/** A new title goes where the timeline shows it (V1–V3), above the picture, never over a clip – or not at all, and why. */
class TitlePlaceTest {
    private fun s(a: Long, b: Long) = FrameSpan(a, b)
    private val none = emptyList<Boolean>()

    @Test fun aboveThePictureOnTheFirstFreeTrack() {
        // only V1, a clip under the playhead: the engine makes V2
        assertEquals(TitlePlace(1, true, null), titlePlace(listOf(listOf(s(0, 250))), none, 50, 125))
        // V1 and V2 busy at the playhead, V3 free: V3
        assertEquals(TitlePlace(2, false, null), titlePlace(listOf(listOf(s(0, 250)), listOf(s(25, 100)), emptyList()), none, 50, 125))
        // nothing under the playhead: V1 (as the engine does), right after the clip that ends there
        assertEquals(TitlePlace(0, false, null), titlePlace(listOf(listOf(s(0, 50)), emptyList(), emptyList()), none, 50, 125))
    }

    @Test fun freeForTheWholeTitleNotJustAtThePlayhead() {
        // V2 free at the playhead but a clip starts there 2 s later: V3
        val t = listOf(listOf(s(0, 500)), listOf(s(100, 200)), emptyList())
        assertEquals(TitlePlace(2, false, null), titlePlace(t, none, 50, 125))
        // V3 busy later too: no room – and nothing is made
        val full = listOf(listOf(s(0, 500)), listOf(s(100, 200)), listOf(s(150, 160)))
        assertEquals(TitlePlace(null, false, TitlePlaceProblem.NO_ROOM), titlePlace(full, none, 50, 125))
        // a title short enough fits before the clip on V2
        assertEquals(TitlePlace(1, false, null), titlePlace(t, none, 50, 50))
    }

    @Test fun v3InUseOrAHiddenTrackAbove() {
        // a clip on V3 at the playhead: a title over it would need V4, which the timeline doesn't show
        assertEquals(TitlePlaceProblem.COVERED, titlePlace(listOf(listOf(s(0, 250)), emptyList(), listOf(s(0, 100))), none, 50, 125).problem)
        // a V4 left over from before (made by the engine) with a clip at the playhead
        assertEquals(TitlePlaceProblem.COVERED, titlePlace(listOf(emptyList(), emptyList(), emptyList(), listOf(s(40, 60))), none, 50, 125).problem)
        // … but elsewhere V4's clip doesn't matter
        assertEquals(TitlePlace(0, false, null), titlePlace(listOf(emptyList(), emptyList(), emptyList(), listOf(s(400, 600))), none, 50, 125))
    }

    @Test fun aLockedTrackIsNotUsed() {
        val t = listOf(listOf(s(0, 250)), listOf(s(0, 250)), emptyList())
        assertEquals(TitlePlace(null, false, TitlePlaceProblem.LOCKED), titlePlace(t, listOf(false, false, true), 50, 125))
        // V2 free but locked, V3 free: V3
        assertEquals(TitlePlace(2, false, null), titlePlace(listOf(listOf(s(0, 250)), emptyList(), emptyList()), listOf(false, true, false), 50, 125))
    }

    @Test fun neverOverAClipAndAlwaysShown() {
        val r = java.util.Random(7)
        repeat(4000) {
            val n = 1 + r.nextInt(4)
            val tracks = List(n) { List(r.nextInt(3)) { val a = r.nextInt(300).toLong(); s(a, a + 1 + r.nextInt(120)) }.sortedBy { it.start } }
            val locked = List(n) { r.nextInt(5) == 0 }
            val at = r.nextInt(300).toLong(); val len = 1L + r.nextInt(150)
            val p = titlePlace(tracks, locked, at, len)
            if (p.ok) {
                val i = p.track!!
                assertTrue("shown: $p", i in 0..2)
                if (p.create) assertEquals(tracks.size, i)
                else {
                    assertFalse("over a clip: $tracks $at $len $p", tracks[i].any { it.start < at + len && at < it.end })
                    assertFalse("locked: $p", locked[i])
                }
                assertFalse("under the picture at the playhead: $p", tracks.drop(i).any { t -> t.any { at >= it.start && at < it.end } })
            }
        }
    }
}
