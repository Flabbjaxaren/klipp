package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.FrameSpan
import se.gendut.klipp.engine.TitlePlace
import se.gendut.klipp.engine.TitlePlaceProblem
import se.gendut.klipp.engine.titlePlace

/**
 * A new title always goes on the topmost video track (nothing covers it): there when it is free for the whole title, else on
 * a new track added on top – up to MAX_VIDEO_TRACKS; at the limit, nothing is made, and why.
 */
class TitlePlaceTest {
    private fun s(a: Long, b: Long) = FrameSpan(a, b)
    private val none = emptyList<Boolean>()
    private val max = MAX_VIDEO_TRACKS

    @Test fun onTheTopTrackWhenItIsFree() {
        // a new project's three tracks, a clip on V1 only: V3 (index 2), even though V2 is free
        assertEquals(TitlePlace(2, false, null), titlePlace(listOf(listOf(s(0, 250)), emptyList(), emptyList()), none, 50, 125, max))
        // only V1, busy: a new V2 on top
        assertEquals(TitlePlace(1, true, null), titlePlace(listOf(listOf(s(0, 250))), none, 50, 125, max))
        // only V1, free for the title: V1 is the top track
        assertEquals(TitlePlace(0, false, null), titlePlace(listOf(listOf(s(0, 50))), none, 50, 125, max))
    }

    @Test fun aBusyTopTrackGetsANewTrackAboveIt() {
        // V3 free at the playhead but a clip starts there 2 s later: a new V4
        val t = listOf(listOf(s(0, 500)), emptyList(), listOf(s(100, 200)))
        assertEquals(TitlePlace(3, true, null), titlePlace(t, none, 50, 125, max))
        // a title short enough fits before the clip on V3
        assertEquals(TitlePlace(2, false, null), titlePlace(t, none, 50, 50, max))
        // an existing title on the top track at the playhead: the new one goes above it
        assertEquals(TitlePlace(4, true, null), titlePlace(List(3) { emptyList<FrameSpan>() } + listOf(listOf(s(0, 100))), none, 50, 125, max))
    }

    @Test fun atTheLimitItSaysWhy() {
        val six = List(5) { emptyList<FrameSpan>() } + listOf(listOf(s(0, 100)))
        assertEquals(TitlePlace(null, false, TitlePlaceProblem.NO_ROOM), titlePlace(six, none, 50, 125, max))
        // free but locked at the limit
        val free = List(6) { emptyList<FrameSpan>() }
        assertEquals(TitlePlace(null, false, TitlePlaceProblem.LOCKED), titlePlace(free, List(6) { it == 5 }, 50, 125, max))
        // locked below the limit: a new track above it
        assertEquals(TitlePlace(3, true, null), titlePlace(List(3) { emptyList<FrameSpan>() }, listOf(false, false, true), 50, 125, max))
    }

    @Test fun alwaysOnTopAndNeverOverAClip() {
        val r = java.util.Random(7)
        repeat(4000) {
            val n = 1 + r.nextInt(6)
            val tracks = List(n) { List(r.nextInt(3)) { val a = r.nextInt(300).toLong(); s(a, a + 1 + r.nextInt(120)) }.sortedBy { it.start } }
            val locked = List(n) { r.nextInt(5) == 0 }
            val at = r.nextInt(300).toLong(); val len = 1L + r.nextInt(150)
            val p = titlePlace(tracks, locked, at, len, max)
            if (p.ok) {
                val i = p.track!!
                if (p.create) { assertEquals(tracks.size, i); assertTrue(i < max) }
                else {
                    assertEquals("the top track: $p", tracks.lastIndex, i)
                    assertFalse("over a clip: $tracks $at $len $p", tracks[i].any { it.start < at + len && at < it.end })
                    assertFalse("locked: $p", locked[i])
                }
            } else assertEquals(max, tracks.size)
        }
    }
}
