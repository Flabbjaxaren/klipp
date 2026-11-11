package se.gendut.klipp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertContentDescriptionEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class FakeProjects : ProjectsBackend {
    var rev by mutableIntStateOf(0)
    val log = mutableListOf<String>()
    val items = mutableListOf(
        ProjectCard("p1", "Smögen – dokumentär", System.currentTimeMillis() - 3_600_000, 754.0, 1920, 1080, 25.0, 41, null, true),
        ProjectCard("p2", "Musikhjälpen teaser", System.currentTimeMillis() - 90_000_000, 62.0, 1080, 1920, 30.0, 12, null, false),
        ProjectCard("aktuellt", "Mitt projekt", System.currentTimeMillis() - 900_000_000, 0.0, 0, 0, 0.0, 0, null, false),
    )
    override val revision get() = rev
    override fun list() = items.toList()
    override fun open(id: String) { log += "open $id" }
    override fun create(spec: NewProjectSpec) { log += "create ${spec.name} ${spec.width}x${spec.height} ${spec.fps}" }
    override fun rename(id: String, name: String) { log += "rename $id $name" }
    override fun duplicate(id: String) { log += "dup $id" }
    override fun delete(id: String) { log += "delete $id" }
    var folder: String? = null
    var info = BackupInfo(null, false, true)
    override fun backupFolder() = folder
    override fun backup(id: String, name: String) { log += "backup $id" }
    override fun backupInfo(id: String) = info
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// default size: in Robolectric, text fields in dialogs hang on larger screen configurations (not on real phones)
@Config(sdk = [35])
class ProjectsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun listOpenCreateAndMenu() {
        val fake = FakeProjects()
        var closed = 0
        rule.setContent { KlippTheme { ProjectsScreen(fake) { closed++ } } }
        rule.waitForIdle()
        rule.onNodeWithTag("newProject").performClick()
        rule.waitForIdle()
        // the format is chosen in a dropdown menu
        rule.onNodeWithTag("fmt").performScrollTo().performClick()
        rule.onNodeWithTag("fmt:Stående").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/36_nytt_projekt.png")
        rule.onNodeWithTag("create").performClick()
        assertEquals("create  1080x1920 25.0", fake.log.last())
        rule.onNodeWithTag("project:p2").performScrollTo().performClick()
        assertEquals("open p2", fake.log.last())
        rule.onNodeWithTag("project:p2").performScrollTo().performTouchInput { longClick() }
        rule.onNodeWithText("Duplicera").performClick()
        assertEquals("dup p2", fake.log.last())
        rule.onRoot().captureRoboImage("build/shots/35_projekt.png")
        assertEquals(2, closed)
    }

    @Test fun lastOpenedOnTopAndSearchByName() {
        val fake = FakeProjects()
        var closed = 0
        rule.setContent { KlippTheme { ProjectsScreen(fake) { closed++ } } }
        rule.waitForIdle()
        // the dots open the same menu as a long press
        rule.onNodeWithTag("projectMenu:p2").performScrollTo().performClick()
        rule.onNodeWithText("Byt namn").assertExists()
        rule.onNodeWithText("Stäng").performClick()
        rule.onNodeWithTag("searchProjects").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("searchField").performTextInput("MUSIK")
        rule.waitForIdle()
        rule.onNodeWithTag("project:p2").assertExists()
        rule.onNodeWithTag("project:p1").assertDoesNotExist()
        rule.onNodeWithTag("continue").assertDoesNotExist()
        rule.onNodeWithTag("searchField").performTextReplacement("finns inte")
        rule.waitForIdle()
        rule.onNodeWithTag("searchNone").assertExists()
        rule.onRoot().captureRoboImage("build/shots/35_projekt_sok.png")
        rule.onNodeWithTag("searchClose").performClick()
        rule.waitForIdle()
        // Continue = back into the project that is open
        rule.onNodeWithTag("continue").performClick()
        assertEquals("open p1", fake.log.last())
        assertEquals(1, closed)
    }

    @Test fun theLastOpenedProjectHasItsMenuInSight() {
        val fake = FakeProjects()
        var closed = 0
        rule.setContent { KlippTheme { ProjectsScreen(fake) { closed++ } } }
        rule.waitForIdle()
        // the hero card's dots: the menu, not the project
        // the page starts at the top: the last opened project first (the list arrives after the first frame)
        rule.onNodeWithTag("continue").assertIsDisplayed()
        rule.onNodeWithTag("projectMenu:p1").assertHasClickAction().assertContentDescriptionEquals("Mer för Smögen – dokumentär").performClick()
        rule.waitForIdle()
        assertTrue("nothing opened", fake.log.isEmpty())
        assertEquals(0, closed)
        rule.onNodeWithText("Exportera med media till en fil…").assertExists()
        rule.onNodeWithText("Exportera bara projektfilen…").assertExists()
        rule.onNodeWithTag("safetyNote").assertExists()
        rule.onRoot().captureRoboImage("build/shots/35_projekt_meny.png")
        // earlier versions: said plainly that they are no backup
        rule.onNodeWithText("Tidigare versioner…").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("versionsNotBackup").assertExists()
        rule.onNodeWithText("Stäng").performClick()
        // the long press still opens the same menu
        rule.onNodeWithTag("project:p1").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Exportera med media till en fil…").assertExists()
        assertTrue(fake.log.isEmpty())
    }

    @Test fun backupsGoToTheChosenFolderAndTheCardTellsHowItStands() {
        val fake = FakeProjects().apply { folder = "Klipp-kopior"; info = BackupInfo(java.util.GregorianCalendar(2026, 10, 11, 14, 20).timeInMillis, changed = true, complete = true) }
        rule.setContent { KlippTheme { ProjectsScreen(fake) {} } }
        rule.waitForIdle()
        // the hero card says the project changed since the backup
        rule.onNodeWithTag("heroBackup").assertTextContains("Ändrad sedan säkerhetskopian", substring = true)
        rule.onNodeWithTag("projectMenu:p1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("backupStatus").assertExists()
        rule.onNodeWithText("Till ”Klipp-kopior”", substring = true).assertExists()
        rule.onNodeWithTag("backupNow").performScrollTo().performClick()
        assertEquals("backup p1", fake.log.last())
    }

    @Test fun withoutAFolderTheMenuOffersToChooseOne() {
        val fake = FakeProjects()
        rule.setContent { KlippTheme { ProjectsScreen(fake) {} } }
        rule.waitForIdle()
        rule.onNodeWithTag("heroBackup").assertTextContains("Aldrig säkerhetskopierad")
        rule.onNodeWithTag("projectMenu:p1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("backupChoose").assertExists()
        rule.onNodeWithTag("backupNow").assertDoesNotExist()
    }

    @Test fun eachBackupGetsANameOfItsOwn() {
        val at = java.util.GregorianCalendar(2026, 10, 11, 14, 20).timeInMillis
        assertEquals("Sommar_1 2026-11-11 1420.klipp", ProjectPackageName.of("Sommar/1", at))
    }

    // the page at the size of a phone (no dialogs here, see the note on the class)
    @Config(sdk = [35], qualifiers = "sv-w360dp-h792dp-xxhdpi")
    @Test fun pageOnAPhone() {
        val fake = FakeProjects()
        fake.items += ProjectCard("p4", "Bröllop Anna & Erik – långa versionen", System.currentTimeMillis() - 200_000_000, 3725.0, 3840, 2160, 50.0, 230, null, false)
        fake.items += ProjectCard("p5", "Kvadrat", System.currentTimeMillis() - 400_000_000, 15.0, 1080, 1080, 30.0, 3, null, false)
        rule.setContent { KlippTheme { ProjectsScreen(fake, onSettings = {}) {} } }
        rule.waitForIdle()
        rule.onNodeWithTag("continue").assertExists()
        rule.onNodeWithTag("project:p5").assertExists()
        rule.onRoot().captureRoboImage("build/shots/35_projekt_sida.png")
    }
}
