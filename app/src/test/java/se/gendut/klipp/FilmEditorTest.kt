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
import androidx.compose.ui.test.performScrollTo
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.Spektra
import java.io.File

/** The film editor: the basics always shown, everything else in folded sections – all of the grain in one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilmEditorTest {
    companion object {
        @BeforeClass @JvmStatic fun data() {
            Spektra.open = { File("src/main/assets/spektrafilm/$it").inputStream() }
        }
    }

    @get:Rule val rule = createComposeRule()

    private fun show(film: Film): () -> GradeNode {
        var node by mutableStateOf(GradeNode(1, "A", film = film))
        rule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) { FilmEditor(node, onCommit = { node = it }) }
        }
        return { node }
    }

    @Test fun grainLivesInOneSection() {
        show(Film())
        // the basics are always there
        for (t in listOf("film:stock", "film:print", "film:input", "prop:filmEv")) rule.onNodeWithTag(t).assertExists()
        // folded: no grain or halation controls until their section is opened
        for (t in listOf("prop:filmGrain", "prop:filmGrainAmount", "prop:filmClouds", "prop:filmHal")) rule.onNodeWithTag(t).assertDoesNotExist()
        rule.onNodeWithTag("filmsec:grain:changed", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("filmsec:grain").performScrollTo().performClick()
        // spektrafilm's grain controls and Klipp's additions together
        for (t in listOf("film:grain", "film:grainLayers", "prop:filmGrain", "prop:filmGrainBlur", "prop:filmGrainAmount", "prop:filmClouds", "prop:filmGrainColor"))
            rule.onNodeWithTag(t).assertExists()
        rule.onNodeWithTag("prop:filmHal").assertDoesNotExist()
        // closing it again
        rule.onNodeWithTag("filmsec:grain").performScrollTo().performClick()
        rule.onNodeWithTag("prop:filmGrainAmount").assertDoesNotExist()
    }

    @Test fun changedSectionsAreMarked() {
        show(Film(grainAmount = 2f, halation = 0.5f))
        rule.onNodeWithTag("filmsec:grain:changed", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("filmsec:halation:changed", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("filmsec:scanner:changed", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("filmsec:print:changed", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun slideFilmHasNoPrintSection() {
        show(Film(film = "fujifilm_velvia_100", print = null))
        rule.onNodeWithTag("filmsec:print").assertDoesNotExist()
        rule.onNodeWithTag("filmsec:negative").assertExists()
    }
}
