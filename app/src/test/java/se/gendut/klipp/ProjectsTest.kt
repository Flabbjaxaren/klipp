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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
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
        rule.onNodeWithTag("project:p2").performClick()
        assertEquals("open p2", fake.log.last())
        rule.onNodeWithTag("project:p2").performTouchInput { longClick() }
        rule.onNodeWithText("Duplicera").performClick()
        assertEquals("dup p2", fake.log.last())
        rule.onRoot().captureRoboImage("build/shots/35_projekt.png")
        assertEquals(2, closed)
    }
}
