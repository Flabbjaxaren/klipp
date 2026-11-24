package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.Drt
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.OpenDrt

/** A MotionCam clip developed again into another log: the nodes that read the old log read the new one. */
class McrawRawTest {
    @Test fun openDrtAndFilmNodesFollowTheDevelopsLog() {
        val slog3 = OpenDrt.Input.SLOG3.key
        val di = OpenDrt.Input.DI.key
        val g = ClipGrade(nodes = listOf(
            GradeNode(1, drt = Drt(input = slog3)),
            GradeNode(2, film = Film(input = slog3)),
            // a node set to another input by hand stays as it is
            GradeNode(3, drt = Drt(input = OpenDrt.Input.VLOG.key)),
            GradeNode(4),
        ))
        val f = followDevelop(g, slog3, di)
        assertEquals(di, f.nodes[0].drt?.input)
        assertEquals(di, f.nodes[1].film?.input)
        assertEquals(OpenDrt.Input.VLOG.key, f.nodes[2].drt?.input)
        assertEquals(g.nodes[3], f.nodes[3])
    }
}
