package se.gendut.klipp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shell: undo and redo stay at the bottom beside the dock, the dock follows what is selected and the page, and the
 * accent colors it all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ShellTest {
    @get:Rule val rule = createComposeRule()
    private val dir = "build/shots"

    @After fun restore() { K.accent = Accent.LIME }

    private fun app(st: EditorState, ui: AppUi = AppUi()): AppUi {
        rule.setContent { KlippTheme { KlippApp(st, ui) } }
        rule.waitForIdle()
        return ui
    }

    private fun goTo(page: Int) {
        rule.onNodeWithTag("modeTab:$page").performClick()
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
    }

    @Test fun undoAndRedoAreAtTheBottomBesideTheDock() {
        val st = EditorState()
        app(st)
        rule.onRoot().captureRoboImage("$dir/60_skal_inget_valt.png")
        val undo = rule.onNodeWithTag("undoRedo").assertIsDisplayed().getUnclippedBoundsInRoot()
        val dock = rule.onNodeWithTag("dock").assertIsDisplayed().getUnclippedBoundsInRoot()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue("undo is at the bottom of the screen", root.bottom - undo.bottom < 40.dp)
        assertTrue("undo is to the left of the dock", undo.right <= dock.left)
        assertEquals("in the same row", undo.top.value, dock.top.value, 0.5f)
        // an edit can be undone from there
        val before = st.clips.size
        rule.onNodeWithTag("add:media").performClick()
        rule.waitForIdle()
        assertEquals(before + 1, st.clips.size)
        rule.onNodeWithTag("undo").performClick()
        rule.waitForIdle()
        assertEquals(before, st.clips.size)
    }

    @Test fun theDockFollowsWhatIsSelected() {
        val st = EditorState().apply { props = FakeProps(); playhead = 3f; onCopyToSource = {} }
        app(st)
        rule.onNodeWithTag("addBar").assertExists()
        rule.onNodeWithTag("selectionChip").assertDoesNotExist()
        st.selected = 1L
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/61_skal_klipp_valt.png")
        rule.onNodeWithTag("addBar").assertDoesNotExist()
        // in the order they are used: delete first, then picture, then copy, then the rest
        val order = listOf("ctx:delete", "ctx:picture", "ctx:copy", "ctx:split", "ctx:speed", "ctx:more")
        for (tag in order) rule.onNodeWithTag(tag).assertIsDisplayed()
        val lefts = order.map { rule.onNodeWithTag(it).getUnclippedBoundsInRoot().left }
        assertEquals(lefts.sorted(), lefts)
        // what is selected is named above the dock, and let go of there
        rule.onNodeWithTag("selectionChip").assertIsDisplayed()
        rule.onNodeWithTag("selection:clear").performClick()
        rule.waitForIdle()
        assertNull(st.selected)
        rule.onNodeWithTag("addBar").assertExists()
        // several clips
        st.multi = setOf(1L, 2L)
        rule.waitForIdle()
        rule.onNodeWithTag("multiBar").assertExists()
        rule.onRoot().captureRoboImage("$dir/62_skal_flera_valda.png")
    }

    /** The [tags] are all showing, whole inside the dock and wide enough to press, in that order from the left. */
    private fun assertTheDockHolds(tags: List<String>) {
        val dock = rule.onNodeWithTag("dock").assertIsDisplayed().getUnclippedBoundsInRoot()
        val bounds = tags.map { rule.onNodeWithTag(it).assertIsDisplayed().getUnclippedBoundsInRoot() }
        for ((tag, b) in tags.zip(bounds)) {
            assertTrue("$tag is inside the dock", b.left >= dock.left && b.right <= dock.right)
            assertTrue("$tag is wide enough to press (${b.right - b.left})", b.right - b.left >= 40.dp)
        }
        assertEquals(bounds.map { it.left }.sorted(), bounds.map { it.left })
    }

    /** On the narrowest phone the six actions of a clip still share the dock: it never scrolls, and delete comes first. */
    @Config(sdk = [35], qualifiers = "sv-w360dp-h792dp-xxhdpi")
    @Test fun theClipDockOnANarrowPhoneHoldsAllSixActions() {
        val st = EditorState().apply { props = FakeProps(); playhead = 3f; onCopyToSource = {}; selected = 1L }
        app(st)
        rule.onRoot().captureRoboImage("$dir/69_skal_klipp_valt_smal.png")
        assertTheDockHolds(listOf("ctx:delete", "ctx:picture", "ctx:copy", "ctx:split", "ctx:speed", "ctx:more"))
        // undo and redo keep their place beside it, and what is selected is named above
        val dock = rule.onNodeWithTag("dock").getUnclippedBoundsInRoot()
        val undo = rule.onNodeWithTag("undoRedo").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(undo.right <= dock.left)
        rule.onNodeWithTag("selectionChip").assertIsDisplayed()
        // the cross that lets go of the selection is pressed over 44 dp
        val cross = rule.onNodeWithTag("selection:clear").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(cross.right - cross.left >= 44.dp && cross.bottom - cross.top >= 44.dp)
    }

    /** The other docks there: a sound clip's six, a title's five and several clips' six are all inside the dock. */
    @Config(sdk = [35], qualifiers = "sv-w360dp-h792dp-xxhdpi")
    @Test fun everyDockOnANarrowPhoneKeepsItsActionsInside() {
        val st = EditorState().apply { props = FakeProps(); playhead = 3f; onCopyToSource = {}; onBeatMarkers = {} }
        app(st)
        st.selected = st.clips.first { it.kind == ClipKind.AUDIO }.id
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/73_docka_ljud_smal.png")
        assertTheDockHolds(listOf("ctx:delete", "ctx:split", "ctx:volume", "ctx:afx", "ctx:speed", "ctx:more"))
        st.selected = st.clips.first { it.kind == ClipKind.TITLE }.id
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/74_docka_titel_smal.png")
        assertTheDockHolds(listOf("ctx:delete", "ctx:editText", "ctx:split", "ctx:label", "ctx:power"))
        rule.onNodeWithTag("ctx:more").assertDoesNotExist()
        st.selected = null
        st.multi = setOf(1L, 2L)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/75_docka_flera_smal.png")
        assertTheDockHolds(listOf("multi:delete", "multi:ripple", "multi:label", "multi:power", "syncAudio"))
    }

    /** The rows of steps there: Back stays in place while the steps scroll beside it, the current one in view. */
    @Config(sdk = [35], qualifiers = "sv-w360dp-h792dp-xxhdpi")
    @Test fun theStepsOfTheDockOnANarrowPhone() {
        val st = EditorState().apply { props = FakeProps(); playhead = 3f; onCopyToSource = {}; onSpeedRamp = { _, _ -> }; selected = 1L }
        app(st)
        rule.onNodeWithTag("ctx:speed").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/76_docka_hastighet_smal.png")
        val dock = rule.onNodeWithTag("dock").getUnclippedBoundsInRoot()
        val back = rule.onNodeWithContentDescription("Tillbaka").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(back.left >= dock.left && back.right - back.left >= 44.dp)
        // the clip's own speed shows without scrolling
        rule.onNodeWithText("100%").assertIsDisplayed()
        // ramp is the last one: reached by scrolling
        rule.onNodeWithTag("speed:ramp").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Tillbaka").assertIsDisplayed().performClick()
        rule.waitForIdle()
        // the labels: each is pressed over 44 dp
        rule.onNodeWithTag("ctx:more").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/77_docka_mer_smal.png")
        rule.onNodeWithTag("ctx:label").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/78_docka_etikett_smal.png")
        val none = rule.onNodeWithTag("label:none").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(none.right - none.left >= 44.dp && none.bottom - none.top >= 44.dp)
        rule.onNodeWithTag("label:${ClipLabel.entries.last().name}").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(ClipLabel.entries.last(), st.clips.first { it.id == 1L }.label)
    }

    /** The picture's sheet there: its tools end above undo and redo, and its choices stay inside the screen. */
    @Config(sdk = [35], qualifiers = "sv-w360dp-h792dp-xxhdpi")
    @Test fun thePictureSheetOnANarrowPhone() {
        val st = EditorState().apply { props = FakeProps(); playhead = 3f; selected = 1L }
        app(st)
        rule.onNodeWithTag("ctx:picture").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/79_bild_smal.png")
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val undo = rule.onNodeWithTag("undoRedo").assertIsDisplayed().getUnclippedBoundsInRoot()
        for (tab in listOf("motion", "crop", "blend", "key", "mask", "stab", "mblur")) {
            val b = rule.onNodeWithTag("propTab:$tab").getBoundsInRoot()
            assertTrue("the tool $tab ends above undo and redo", b.bottom <= undo.top)
        }
        for (tab in listOf("crop", "blend", "key", "mask")) {
            rule.onNodeWithTag("propTab:$tab").performScrollTo().performClick()
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(600)
            rule.onRoot().captureRoboImage("$dir/79_bild_${tab}_smal.png")
        }
        rule.onNodeWithTag("sheet:done").assertIsDisplayed()
        assertTrue(rule.onNodeWithTag("properties").getUnclippedBoundsInRoot().right <= root.right)
    }

    /** The sound and text pages there. */
    @Config(sdk = [35], qualifiers = "sv-w360dp-h792dp-xxhdpi")
    @Test fun theSoundAndTextPagesOnANarrowPhone() {
        val st = EditorState().apply { props = FakeProps() }
        app(st)
        goTo(2)
        rule.onRoot().captureRoboImage("$dir/80_ljud_smal.png")
        assertTheDockHolds(listOf("audioTab:mixer", "audioTab:loudness"))
        goTo(3)
        rule.onRoot().captureRoboImage("$dir/81_text_smal.png")
        assertTheDockHolds(listOf("textTab:0", "textTab:1", "textTab:2"))
        rule.onNodeWithTag("textTab:1").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/82_undertexter_smal.png")
    }

    @Test fun thePictureSheetHasItsToolsInARailAndUndoStays() {
        val st = EditorState().apply { props = FakeProps(); playhead = 3f; selected = 1L }
        app(st)
        rule.onNodeWithTag("ctx:picture").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/63_skal_bild.png")
        rule.onNodeWithTag("properties").assertIsDisplayed()
        // the tools are under each other on the left
        val a = rule.onNodeWithTag("propTab:motion").getUnclippedBoundsInRoot()
        val b = rule.onNodeWithTag("propTab:crop").getUnclippedBoundsInRoot()
        assertEquals(a.left.value, b.left.value, 0.5f)
        assertTrue(b.top >= a.bottom)
        // the dock gives way to the sheet, undo stays
        rule.onNodeWithTag("dock").assertDoesNotExist()
        rule.onNodeWithTag("undoRedo").assertIsDisplayed()
        rule.onNodeWithTag("propTab:blend").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/64_skal_bild_blandning.png")
        rule.onNodeWithTag("sheet:done").performClick()
        rule.waitForIdle()
        assertEquals(false, st.inspecting)
        rule.onNodeWithTag("dock").assertExists()
    }

    @Test fun aSelectedTransitionHasACardAndItsOwnDock() {
        val fake = FakeProps()
        val st = EditorState().apply {
            props = fake; playhead = 8f
            transitions = listOf(UiTransition(7L, 0, 7.5f, 8.5f, cut = 8f, effect = "whip", from = 1L, to = 2L))
            selectedTransition = 7L
        }
        app(st)
        rule.onRoot().captureRoboImage("$dir/65_skal_overgang.png")
        rule.onNodeWithTag("transitionCard").assertIsDisplayed()
        for (tag in listOf("transition:whip", "prop:transition:length", "transition:remove", "transition:done")) rule.onNodeWithTag(tag).assertExists()
        rule.onNodeWithTag("transition:done").performClick()
        rule.waitForIdle()
        assertNull(st.selectedTransition)
        rule.onNodeWithTag("addBar").assertExists()
    }

    /** Between two sounds: a crossfade with a length – no picture types, no direction, nothing to pre-render. */
    @Test fun aCrossfadeBetweenSoundsHasOnlyItsLength() {
        val st = EditorState().apply {
            props = FakeProps(); playhead = 8f
            transitions = listOf(UiTransition(8L, 3, 7.5f, 8.5f, cut = 8f, effect = "constant_power", from = 1L, to = 2L))
            selectedTransition = 8L
        }
        app(st)
        rule.onRoot().captureRoboImage("$dir/65_skal_overtoning_ljud.png")
        rule.onNodeWithTag("transitionCard").assertIsDisplayed()
        rule.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.app_crossfade)).assertIsDisplayed()
        for (tag in listOf("prop:transition:length", "transition:remove", "transition:done")) rule.onNodeWithTag(tag).assertExists()
        for (tag in listOf("transition:cross_dissolve", "transition:whip", "transition:reverse", "prop:transition:blur", "transition:render")) rule.onNodeWithTag(tag).assertDoesNotExist()
    }

    @Test fun theSoundAndTextPagesHaveTheirTabsInTheDock() {
        val st = EditorState().apply { props = FakeProps() }
        app(st)
        goTo(2)
        rule.onRoot().captureRoboImage("$dir/66_skal_ljud.png")
        // the tabs are in the dock only (not also in the page)
        assertEquals(1, rule.onAllNodesWithTag("audioTab:loudness").fetchSemanticsNodes().size)
        rule.onNodeWithTag("audioTab:loudness").performClick()
        rule.waitForIdle()
        assertEquals(1, st.audioTab)
        rule.onNodeWithTag("undoRedo").assertIsDisplayed()
        goTo(3)
        rule.onRoot().captureRoboImage("$dir/67_skal_text.png")
        assertEquals(1, rule.onAllNodesWithTag("textTab:1").fetchSemanticsNodes().size)
        rule.onNodeWithTag("textTab:1").performClick()
        rule.waitForIdle()
        assertEquals(1, st.textTab)
        // the color page has its tools in the page: no dock, undo stays
        goTo(1)
        rule.onRoot().captureRoboImage("$dir/68_skal_farg.png")
        rule.onNodeWithTag("dock").assertDoesNotExist()
        rule.onNodeWithTag("undoRedo").assertIsDisplayed()
    }

    @Test fun anotherAccentColorsTheShell() {
        K.accent = Accent.BLUE
        val st = EditorState().apply { props = FakeProps(); playhead = 3f; selected = 1L }
        app(st)
        rule.onRoot().captureRoboImage("$dir/69_skal_bla.png")
        rule.onNodeWithTag("ctx:picture").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/70_skal_bla_bild.png")
    }
}
