package se.gendut.klipp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The grouped toolbars: every action the clip bar used to have can still be reached, and the empty-selection row adds. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class DesignRowsTest {
    @get:Rule val rule = createComposeRule()

    private fun t(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun videoSelected(): Pair<EditorState, AppUi> {
        val st = EditorState().apply { props = FakeProps(); playhead = 30f; onBeatMarkers = {}; onCopyToSource = {} }
        assertEquals(ClipKind.VIDEO, st.clips.first { it.id == 1L }.kind)
        st.selected = 1L
        val ui = AppUi()
        rule.setContent { KlippTheme { KlippApp(st, ui) } }
        rule.waitForIdle()
        return st to ui
    }

    @Test fun aVideoClipReachesEveryActionThroughItsGroups() {
        val (st, _) = videoSelected()
        // the row: split, copy, speed, picture, delete, more – no color, transition or composite shortcuts
        for (tag in listOf("ctx:copy", "ctx:picture", "ctx:more")) rule.onNodeWithTag(tag).assertExists()
        for (tag in listOf("ctx:grade", "ctx:transition", "ctx:composite")) rule.onNodeWithTag(tag).assertDoesNotExist()
        for (s in listOf(R.string.app_split, R.string.app_speed, R.string.app_delete)) rule.onNodeWithText(t(s)).assertExists()
        // More: the rest, without copy (in the row) and ripple delete
        rule.onNodeWithTag("ctx:more").performClick()
        rule.waitForIdle()
        for (s in listOf(R.string.app_freeze, R.string.app_reverse, R.string.beats_btn, R.string.app_label, R.string.app_disable)) rule.onNodeWithText(t(s)).assertExists()
        for (s in listOf(R.string.app_copy, R.string.app_ripple_delete)) rule.onNodeWithText(t(s)).assertDoesNotExist()
        st.selected = null; rule.waitForIdle(); st.selected = 1L; rule.waitForIdle()
        // Picture: position, crop, blend, key, mask, stabilization and motion blur
        rule.onNodeWithTag("ctx:picture").performClick()
        rule.waitForIdle()
        for (tag in listOf("propTab:motion", "propTab:crop", "propTab:blend", "propTab:key", "propTab:mask", "propTab:stab", "propTab:mblur")) rule.onNodeWithTag(tag).assertExists()
    }

    @Test fun nothingSelectedOffersTheThingsToAdd() {
        val st = EditorState()
        val ui = AppUi()
        rule.setContent { KlippTheme { KlippApp(st, ui) } }
        rule.waitForIdle()
        for (tag in listOf("add:media", "add:sound", "add:title", "add:captions", "add:voice")) rule.onNodeWithTag(tag).assertExists()
        val before = st.clips.size
        rule.onNodeWithTag("add:media").performClick()
        rule.waitForIdle()
        assertEquals(before + 1, st.clips.size)
        // (the new clip is selected and its own row shows; deselecting brings the add row back)
        st.selected = null
        rule.waitForIdle()
        rule.onNodeWithTag("add:captions").performClick()
        rule.waitForIdle()
        assertEquals(3f, ui.page.value, 0.01f)
    }

    /** Title and captions open their sheet over the edit page (no jump to another page); nothing in the row is lit. */
    @Test fun addingOpensASheetOverTheEditPage() {
        val st = EditorState().apply { props = FakeProps() }
        val ui = AppUi()
        rule.setContent { KlippTheme { KlippApp(st, ui) } }
        rule.waitForIdle()
        for (tag in listOf("add:media", "add:sound", "add:title", "add:captions", "add:voice")) rule.onNodeWithTag(tag).assertIsNotSelected()
        rule.onNodeWithTag("add:title").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("titleSheet").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/shots/71_lagg_till_titel.png")
        assertEquals(0f, ui.page.value, 0.01f)
        rule.onNodeWithTag("sheet:done").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("titleSheet").assertDoesNotExist()
        rule.onNodeWithTag("add:captions").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("captionsSheet").assertIsDisplayed()
        assertEquals(0f, ui.page.value, 0.01f)
    }

    /** A sound clip: delete, split, volume, effects, more – no fade; the volume is a slider, not steps. */
    @Test fun aSoundClipsVolumeIsASlider() {
        val st = EditorState()
        val aid = st.clips.first { it.kind == ClipKind.AUDIO }.id
        val props = FakeProps().apply { p = p.copy(id = aid) }
        st.props = props
        st.selected = aid
        rule.setContent { KlippTheme { KlippApp(st, AppUi()) } }
        rule.waitForIdle()
        rule.onNodeWithTag("ctx:fade").assertDoesNotExist()
        for (tag in listOf("ctx:delete", "ctx:split", "ctx:volume", "ctx:afx", "ctx:more")) rule.onNodeWithTag(tag).assertIsDisplayed()
        rule.onNodeWithTag("ctx:split").assertIsNotSelected()
        rule.onNodeWithTag("ctx:volume").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/72_ljud_volym.png")
        rule.onNodeWithTag("prop:level").assertIsDisplayed()
        // dragged to the right end: the most there is, saved on release
        rule.onNodeWithTag("prop:level").performTouchInput { down(centerLeft + Offset(width * 0.5f, 0f)); moveTo(centerRight + Offset(40f, 0f), 0); up() }
        rule.waitForIdle()
        assertEquals(15f, props.p.levelDb, 0.01f)
    }

    @Test fun theToolsAndTimelineModesAreInTheTimelineRow() {
        val st = EditorState()
        rule.setContent { KlippTheme { KlippApp(st, AppUi()) } }
        rule.waitForIdle()
        rule.onNodeWithTag("tool:menu").performClick()
        rule.waitForIdle()
        for (tool in listOf("SELECT", "RIPPLE", "TRIM", "RAZOR")) rule.onNodeWithTag("tool:$tool").assertExists()
        rule.onNodeWithTag("tool:RIPPLE").performClick()
        rule.waitForIdle()
        // snapping and linked selection are toggles in the timeline's row, the rest of the modes in the menu
        val linked = st.linkedSelection
        rule.onNodeWithTag("mode:link").performClick()
        rule.waitForIdle()
        assertEquals(!linked, st.linkedSelection)
        val magnet = st.magnet
        rule.onNodeWithTag("mode:magnet").performClick()
        rule.waitForIdle()
        assertEquals(!magnet, st.magnet)
        rule.onNodeWithTag("modes:menu").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("mode:center").assertExists()
        rule.onNodeWithTag("mode:vol").assertExists()
    }

    /** With the playhead on a marker the marker button takes it away; the menu takes them all away. */
    @Test fun theMarkerButtonRemovesTheMarkerUnderThePlayhead() {
        val st = EditorState().apply { markers = listOf(9f, 31f); playhead = 14f }
        rule.setContent { KlippTheme { KlippApp(st, AppUi()) } }
        rule.waitForIdle()
        rule.onNodeWithTag("marker:add").performClick()
        rule.waitForIdle()
        assertEquals(listOf(9f, 14f, 31f), st.markers)
        // (it stands on the one it just set: the same button takes it away again)
        rule.onNodeWithContentDescription(t(R.string.app_remove_marker)).assertExists()
        rule.onNodeWithTag("marker:add").performClick()
        rule.waitForIdle()
        assertEquals(listOf(9f, 31f), st.markers)
        rule.onNodeWithContentDescription(t(R.string.app_add_marker)).assertExists()
        rule.onNodeWithTag("modes:menu").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("markers:clear").performClick()
        rule.waitForIdle()
        assertTrue(st.markers.isEmpty())
        // (nothing to remove: the choice is gone from the menu)
        rule.onNodeWithTag("modes:menu").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("markers:clear").assertDoesNotExist()
    }

    /** The settings have their own gear in the timeline's row, also where the row is at its narrowest. */
    @Config(sdk = [35], qualifiers = "sv-w360dp-h792dp-xxhdpi")
    @Test fun theGearInTheTimelineRowOpensTheSettings() {
        val st = EditorState()
        rule.setContent { KlippTheme { KlippApp(st, AppUi()) } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/70_skal_kugghjul_smal.png")
        for (tag in listOf("tool:menu", "mode:magnet", "mode:link", "marker:add", "modes:menu", "appSettings")) rule.onNodeWithTag(tag).assertIsDisplayed()
        val row = rule.onRoot().getUnclippedBoundsInRoot()
        val gear = rule.onNodeWithTag("appSettings").getUnclippedBoundsInRoot()
        assertTrue(gear.right <= row.right && gear.right - gear.left >= 43.dp)
        rule.onNodeWithTag("appSettings").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings").assertIsDisplayed()
        rule.onNodeWithTag("set:cat:appearance").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/83_installningar_smal.png")
        // all the accents show there (five to a row), none outside the screen
        for (a in Accent.entries) {
            val b = rule.onNodeWithTag("accent:${a.name}").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("${a.name} is inside the screen", b.left >= row.left && b.right <= row.right)
        }
    }

    /** The sound sheet has a fourth tab for the phone's own audio files (narrow phone: all four tabs fit). */
    @Config(qualifiers = "sv-w360dp-h792dp-xxhdpi")
    @Test fun theSoundSheetTakesThePhonesOwnAudioFiles() {
        rule.setContent { KlippTheme { SoundFxSheet(onDismiss = {}, speech = true) { _, _, _ -> } } }
        rule.waitForIdle()
        rule.onNodeWithText(t(R.string.sfx_tab_speech)).assertIsDisplayed()
        rule.onNodeWithText(t(R.string.sfx_tab_files)).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("sfx:file").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/shots/66_ljud_filer.png")
    }
}
