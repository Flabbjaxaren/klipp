package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.ClipSnap
import se.gendut.klipp.engine.Drt
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.SequenceSnap
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.TrackSnap

/**
 * What the export can be (HLG, keeping HDR) is decided by the grades of the clips it renders only. The project keeps the
 * grades of deleted clips (for undo) and of its other sequences: one of them without OpenDRT greyed out HLG for good.
 */
class GradesInUseTest {
    private fun snap(vararg ids: Long) = Snapshot.EMPTY.copy(
        sequence = SequenceSnap(1920, 1080, 10.0, listOf(TrackSnap(1, "V1", false, false,
            ids.map { ClipSnap(it, 1, "c$it", 0.0, 1.0, 0.0, 1.0, null) })), emptyList()),
    )
    private val drt = ClipGrade(listOf(GradeNode(1, "Ut", drt = Drt())))
    private val lut = ClipGrade(listOf(GradeNode(1, "A", sat = 1.2f)))

    @Test fun aDeletedClipsGradeDoesNotCount() {
        // clip 2 (graded without OpenDRT) was deleted; its grade is still stored
        val inUse = gradesInUse(snap(1, 3), mapOf(1L to drt, 2L to lut))
        assertEquals(listOf(drt), inUse)
        assertTrue(inUse.all { it.hlgReady })
    }

    @Test fun ungradedClipsAddNothing() = assertEquals(listOf(drt), gradesInUse(snap(1, 3, 4), mapOf(1L to drt)))

    @Test fun aDisabledClipsGradeDoesNotCount() {
        val s = snap(1, 2).let { x -> x.copy(sequence = x.sequence!!.copy(video = x.sequence!!.video.map { t ->
            t.copy(clips = t.clips.map { if (it.id == 2L) it.copy(enabled = false) else it }) })) }
        assertEquals(listOf(drt), gradesInUse(s, mapOf(1L to drt, 2L to lut)))
    }

    @Test fun noSequenceNoGrades() = assertEquals(emptyList<ClipGrade>(), gradesInUse(Snapshot.EMPTY, mapOf(1L to drt)))
}
