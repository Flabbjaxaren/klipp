package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.GradeNode

/**
 * A grade whose one node is turned off (or at 0 % mix) is kept: it still holds its settings. It used to be dropped as
 * neutral – turning the node off deleted the grade, film and all, and turning it on again had nothing to turn on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GradeOffKeptTest {
    private val film = ClipGrade(listOf(GradeNode(1, "Primär", film = Film(), contrast = 1.2f)))

    @Test fun aNodeTurnedOffKeepsItsSettings() {
        val gs = GradeState()
        gs.set(7, film)
        gs.set(7, film.copy(nodes = film.nodes.map { it.copy(enabled = false) }))
        assertNotNull(gs.grades[7]?.nodes?.single()?.film)
        gs.set(7, gs.of(7).copy(nodes = gs.of(7).nodes.map { it.copy(enabled = true) }))
        assertEquals(film, gs.of(7))
    }

    @Test fun zeroMixKeepsItsSettingsToo() {
        val gs = GradeState()
        gs.set(7, film.copy(nodes = film.nodes.map { it.copy(mix = 0f) }))
        assertEquals(1.2f, gs.grades[7]!!.nodes.single().contrast, 1e-6f)
    }

    @Test fun anEmptyNodeStillGoes() {
        val gs = GradeState()
        gs.set(7, film)
        gs.set(7, ClipGrade(listOf(GradeNode(1, "Primär"))))
        assertNull(gs.grades[7])
        gs.set(7, ClipGrade(listOf(GradeNode(1, "Primär", enabled = false))))
        assertNull(gs.grades[7])
    }
}
