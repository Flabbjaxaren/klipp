package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.ClipSnap
import se.gendut.klipp.engine.SequenceSnap
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.TrackSnap
import se.gendut.klipp.engine.newClips

/**
 * Which clips count as made by a new edit (their leftover grades are reset): only an edit in the same project. Opening
 * another project compared its clips with the previous project's and reset the grades of every clip whose id that
 * project didn't have – the whole grade of the opened timeline's clips was lost.
 */
class NewClipsTest {
    private fun snap(serial: Long, vararg ids: Long) = Snapshot.EMPTY.copy(
        editSerial = serial,
        sequence = SequenceSnap(1920, 1080, 10.0, listOf(TrackSnap(1, "V1", false, false,
            ids.map { ClipSnap(it, 1, "c$it", 0.0, 1.0, 0.0, 1.0, null) })), emptyList()),
    )

    @Test fun aNewEditsClipsAreNew() =
        assertEquals(listOf(7L), newClips(snap(3, 1, 2), snap(4, 1, 2, 7), edited = true, restoring = false))

    @Test fun undoAndRedoMakeNoNewClips() =
        assertEquals(emptyList<Long>(), newClips(snap(3, 1, 2), snap(3, 1, 2, 7), edited = true, restoring = false))

    @Test fun openingAnotherProjectKeepsItsClipsGrades() =
        assertEquals(emptyList<Long>(), newClips(snap(12, 1, 2), snap(40, 167, 173), edited = true, restoring = true))

    @Test fun theFirstReadMakesNoNewClips() =
        assertEquals(emptyList<Long>(), newClips(Snapshot.EMPTY, snap(5, 1, 2), edited = false, restoring = false))
}
