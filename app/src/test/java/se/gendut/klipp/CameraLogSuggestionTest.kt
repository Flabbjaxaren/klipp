package se.gendut.klipp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.OpenDrt
import se.gendut.klipp.engine.Spektra
import java.io.File

/**
 * The camera's log curve (CameraLog, e.g. V-Log from a Panasonic's metadata) as a suggestion in the film node: a node
 * turned on starts from it, and when another input is picked it is offered as one tap – nothing changes by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraLogSuggestionTest {
    companion object {
        @BeforeClass @JvmStatic fun data() {
            Spektra.open = { File("src/main/assets/spektrafilm/$it").inputStream() }
        }
    }

    @get:Rule val rule = createComposeRule()

    /** The editor on [start]; what it hands on is seen at once in [onLive] (the commit itself waits for the bake). */
    private fun show(start: GradeNode, log: OpenDrt.Input?): () -> GradeNode? {
        var node by mutableStateOf(start)
        var live: GradeNode? = null
        rule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) { FilmEditor(node, onCommit = { node = it }, onLive = { live = it }, logHint = log) }
        }
        return { live }
    }

    @Test fun aNewFilmNodeStartsFromTheCamerasLog() {
        val live = show(GradeNode(1, "A"), OpenDrt.Input.VLOG)
        // off: said, nothing to tap, nothing graded
        rule.onNodeWithTag("logHint:use").assertDoesNotExist()
        rule.onNodeWithTag("film:on").performClick()
        rule.waitForIdle()
        assertEquals(OpenDrt.Input.VLOG.key, live()!!.film!!.input)
    }

    @Test fun anotherInputGetsTheCamerasLogOfferedAsOneTap() {
        val live = show(GradeNode(1, "A", film = Film(input = OpenDrt.Input.REC709.key)), OpenDrt.Input.VLOG)
        rule.onNodeWithTag("logHint:use").performClick()
        rule.waitForIdle()
        assertEquals(OpenDrt.Input.VLOG.key, live()!!.film!!.input)
    }

    @Test fun theCamerasLogInUseIsNotOfferedAgain() {
        show(GradeNode(1, "A", film = Film(input = OpenDrt.Input.VLOG.key)), OpenDrt.Input.VLOG)
        rule.onNodeWithTag("logHint:use").assertDoesNotExist()
    }

    @Test fun withoutCameraMetadataTheFilmNodeKeepsItsDefaultInput() {
        val live = show(GradeNode(1, "A"), null)
        rule.onNodeWithTag("film:on").performClick()
        rule.waitForIdle()
        assertEquals(OpenDrt.Input.REC709.key, live()!!.film!!.input)
        rule.onNodeWithTag("logHint:use").assertDoesNotExist()
    }
}
