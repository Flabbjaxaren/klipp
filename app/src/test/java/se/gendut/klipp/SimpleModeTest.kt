package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.AfxSnap
import se.gendut.klipp.engine.BlendMode
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.Curve
import se.gendut.klipp.engine.Drt
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.LayerFx
import se.gendut.klipp.engine.LayerMask
import se.gendut.klipp.engine.LutStore
import se.gendut.klipp.engine.Wheel

/**
 * Simple and advanced mode: existing installs stay advanced, a fresh install is asked once; simple mode's controls
 * write exactly what advanced mode's write; what simple mode has no control for shows as badges; switching changes
 * nothing in the project; simple mode edits on the magnetic timeline without touching the advanced setting.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class SimpleModeTest {
    @get:Rule val rule = createComposeRule()
    private val dir = "build/shots"
    private val ctx get() = RuntimeEnvironment.getApplication()

    /** (Simple mode and the magnetic timeline are experimental features: on for these tests, off in their own below.) */
    @Before fun experimentalOn() {
        ctx.getSharedPreferences("klipp", 0).edit().putBoolean("experimental", true).commit()
    }

    // ------------------------------------------------------------------ experimental

    private fun experimentalOff() { ctx.getSharedPreferences("klipp", 0).edit().putBoolean("experimental", false).commit() }

    @Test fun withoutTheExperimentalFeaturesNothingIsAskedOrOffered() {
        experimentalOff()
        AppPrefs.initMode(ctx, updated = false, hasProjects = false)
        assertFalse("a fresh install is not asked", AppPrefs.askMode(ctx))
        // choices made earlier (with the features on) wait: advanced mode on tracks
        AppPrefs.setSimpleMode(ctx, true)
        AppPrefs.setMagneticTimeline(ctx, true)
        assertFalse(AppPrefs.simpleMode(ctx))
        assertFalse(AppPrefs.magneticTimeline(ctx))
        val st = EditorState()
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("modeAsk").assertDoesNotExist()
        assertFalse(st.simple)
        assertFalse(st.magnetic)
        assertFalse(st.experimental)
        rule.onNodeWithTag("modes:menu").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("mode:simple").assertDoesNotExist()
        rule.onNodeWithTag("mode:magnetic").assertDoesNotExist()
        rule.onNodeWithTag("mode:center").assertExists()
    }

    @Test fun theExperimentalPageHoldsTheModeAndTheMagneticTimeline() {
        experimentalOff()
        var on: Boolean? = null
        rule.setContent { KlippTheme { SettingsScreen(onClose = {}, onMode = {}, onExperimental = { on = it }) } }
        rule.waitForIdle()
        // the first page: no mode row
        rule.onNodeWithTag("set:mode").assertDoesNotExist()
        rule.onNodeWithTag("set:cat:experimental").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("set:mode").assertDoesNotExist()
        rule.onNodeWithTag("set:experimental").performClick()
        rule.waitForIdle()
        assertEquals(true, on)
        assertTrue(AppPrefs.experimental(ctx))
        rule.onNodeWithTag("set:mode").assertExists()
        rule.onNodeWithTag("set:magnetic").assertExists()
        rule.onRoot().captureRoboImage("$dir/s00_experimentellt.png")
    }

    @Test fun theTimelinePageHasNoMagneticSwitch() {
        rule.setContent { KlippTheme { SettingsScreen(onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("set:cat:timeline").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("set:magnetic").assertDoesNotExist()
        rule.onNodeWithTag("set:magnet").assertExists()
    }

    @Test fun turningTheExperimentalFeaturesOffLeavesSimpleModeAndKeepsTheChoice() {
        AppPrefs.setSimpleMode(ctx, true)
        val st = EditorState().apply { props = FakeProps(); playhead = 3f }
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        assertTrue(st.simple)
        val clips = st.clips
        // Settings → Experimental → off: advanced mode on tracks, the project as it was
        rule.onNodeWithTag("appSettings").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("set:cat:experimental").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("set:experimental").performClick()
        rule.waitForIdle()
        assertFalse(st.simple)
        assertFalse(st.magnetic)
        assertFalse(st.experimental)
        assertEquals(clips, st.clips)
        // on again: simple mode is back
        rule.onNodeWithTag("set:experimental").performClick()
        rule.waitForIdle()
        assertTrue(st.simple)
    }

    // ------------------------------------------------------------------ the mode setting

    @Test fun existingInstallsStayAdvanced() {
        // an update (installed before): advanced, nothing asked
        AppPrefs.initMode(ctx, updated = true, hasProjects = false)
        assertFalse(AppPrefs.simpleMode(ctx))
        assertFalse(AppPrefs.askMode(ctx))
        assertEquals("advanced", ctx.getSharedPreferences("klipp", 0).getString("appMode", null))
    }

    @Test fun projectsOrSettingsMeanAnExistingInstall() {
        AppPrefs.initMode(ctx, updated = false, hasProjects = true)
        assertFalse(AppPrefs.askMode(ctx))
        assertFalse(AppPrefs.simpleMode(ctx))
        ctx.getSharedPreferences("klipp", 0).edit().clear().commit()
        // a setting of its own (the app was used before the modes)
        AppPrefs.setHaptics(ctx, false)
        AppPrefs.initMode(ctx, updated = false, hasProjects = false)
        assertFalse(AppPrefs.askMode(ctx))
        assertFalse(AppPrefs.simpleMode(ctx))
    }

    @Test fun aFreshInstallIsAskedOnce() {
        AppPrefs.initMode(ctx, updated = false, hasProjects = false)
        assertTrue(AppPrefs.askMode(ctx))
        assertFalse("advanced until answered", AppPrefs.simpleMode(ctx))
        // a later start before the answer: still asked, nothing else changes
        AppPrefs.initMode(ctx, updated = true, hasProjects = true)
        assertTrue(AppPrefs.askMode(ctx))
        AppPrefs.setSimpleMode(ctx, true)
        assertFalse(AppPrefs.askMode(ctx))
        assertTrue(AppPrefs.simpleMode(ctx))
        AppPrefs.initMode(ctx, updated = false, hasProjects = false)
        assertFalse("answered: never asked again", AppPrefs.askMode(ctx))
        assertTrue(AppPrefs.simpleMode(ctx))
    }

    @Test fun theFirstStartQuestionPicksSimple() {
        AppPrefs.initMode(ctx, updated = false, hasProjects = false)
        val st = EditorState()
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("modeAsk").assertIsDisplayed()
        rule.onRoot().captureRoboImage("$dir/s01_enkelt_fraga.png")
        rule.onNodeWithTag("modeAsk:simple").performClick()
        rule.waitForIdle()
        assertTrue(st.simple)
        assertTrue(AppPrefs.simpleMode(ctx))
        assertFalse(AppPrefs.askMode(ctx))
        rule.onNodeWithTag("modeAsk").assertDoesNotExist()
    }

    @Test fun advancedAsksHowTheTimelineWorks() {
        AppPrefs.initMode(ctx, updated = false, hasProjects = false)
        val st = EditorState()
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("modeAsk:advanced").performClick()
        rule.waitForIdle()
        // not answered yet: the second question, and Back returns to the first
        assertTrue(AppPrefs.askMode(ctx))
        rule.onNodeWithTag("modeAsk:tracks").assertIsDisplayed()
        rule.onRoot().captureRoboImage("$dir/s01b_tidslinje_fraga.png")
        rule.onNodeWithTag("modeAsk:back").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("modeAsk:simple").assertIsDisplayed()
        rule.onNodeWithTag("modeAsk:advanced").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("modeAsk:magnetic").performClick()
        rule.waitForIdle()
        assertFalse(st.simple)
        assertTrue(st.magnetic)
        assertTrue(AppPrefs.magneticTimeline(ctx))
        assertFalse(AppPrefs.simpleMode(ctx))
        assertFalse(AppPrefs.askMode(ctx))
        rule.onNodeWithTag("modeAsk").assertDoesNotExist()
        // V1 says it is the storyline
        rule.onNodeWithTag("storylineBadge", useUnmergedTree = true).assertExists()
    }

    @Test fun advancedOnTracksKeepsTheTimelineAsBefore() {
        AppPrefs.initMode(ctx, updated = false, hasProjects = false)
        val st = EditorState()
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("modeAsk:advanced").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("modeAsk:tracks").performClick()
        rule.waitForIdle()
        assertFalse(st.simple)
        assertFalse(st.magnetic)
        assertFalse(AppPrefs.magneticTimeline(ctx))
        assertFalse(AppPrefs.askMode(ctx))
        rule.onNodeWithTag("storylineBadge", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun skippingTheQuestionKeepsAdvanced() {
        AppPrefs.initMode(ctx, updated = false, hasProjects = false)
        val st = EditorState()
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("modeAsk:skip").performClick()
        rule.waitForIdle()
        assertFalse(st.simple)
        assertFalse(AppPrefs.simpleMode(ctx))
        assertFalse(AppPrefs.askMode(ctx))
        rule.onNodeWithTag("modeAsk").assertDoesNotExist()
    }

    @Test fun noQuestionWithoutAFreshInstall() {
        val st = EditorState()
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        rule.onNodeWithTag("modeAsk").assertDoesNotExist()
        assertFalse(st.simple)
    }

    // ------------------------------------------------------------------ the magnetic timeline

    @Test fun simpleModeUsesTheMagneticTimelineWithoutChangingTheAdvancedSetting() {
        AppPrefs.setMagneticTimeline(ctx, false)
        val st = EditorState()
        st.magnetic = false
        st.magnet = false
        st.switchMode(true)
        assertTrue(st.magnetic)
        assertTrue("simple mode snaps", st.magnet)
        assertFalse("the stored advanced choice is untouched", AppPrefs.magneticTimeline(ctx))
        st.switchMode(false)
        assertFalse("back in advanced: its own choice", st.magnetic)
        assertFalse(st.magnet)
        // an advanced user with the magnetic timeline on keeps it in both modes
        st.magnetic = true
        st.switchMode(true); st.switchMode(false)
        assertTrue(st.magnetic)
    }

    @Test fun theMenuSwitchKeepsTheProjectAsItIs() {
        val st = EditorState().apply { props = FakeProps(); playhead = 3f }
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        val clips = st.clips
        val tracks = st.trackStates
        val markers = st.markers
        val side = st.magSide
        val linked = st.linkedSelection
        rule.onNodeWithTag("modes:menu").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("mode:simple").performClick()
        rule.waitForIdle()
        assertTrue(st.simple)
        assertTrue("kept in the settings", AppPrefs.simpleMode(ctx))
        rule.onRoot().captureRoboImage("$dir/s02_enkelt_redigera.png")
        // simple mode's header: no tool chip, snapping or (with it on) linked selection toggle
        rule.onNodeWithTag("tool:menu").assertDoesNotExist()
        rule.onNodeWithTag("mode:magnet").assertDoesNotExist()
        rule.onNodeWithTag("mode:link").assertDoesNotExist()
        rule.onNodeWithTag("marker:add").assertIsDisplayed()
        rule.onNodeWithTag("addTrack:video").assertDoesNotExist()
        rule.onNodeWithTag("modes:menu").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("mode:magnetic").assertDoesNotExist()
        rule.onNodeWithTag("mode:simple").performClick()
        rule.waitForIdle()
        assertFalse(st.simple)
        assertFalse(AppPrefs.simpleMode(ctx))
        assertEquals(clips, st.clips)
        assertEquals(tracks, st.trackStates)
        assertEquals(markers, st.markers)
        assertEquals(side, st.magSide)
        assertEquals(linked, st.linkedSelection)
        rule.onNodeWithTag("tool:menu").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ the dock

    @Test fun simpleDockRowsAreTheEverydayActions() {
        val st = EditorState().apply { props = FakeProps(); playhead = 3f; onCopyToSource = {}; onSpeedRamp = { _, _ -> } }
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        st.switchMode(true)
        st.selected = 1L
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/s03_enkelt_klipp.png")
        rule.onNodeWithTag("ctx:delete").assertExists()
        rule.onNodeWithTag("ctx:picture").assertExists()
        rule.onNodeWithTag("ctx:split").assertExists()
        rule.onNodeWithTag("ctx:speed").assertExists()
        rule.onNodeWithTag("ctx:copy").assertDoesNotExist()
        rule.onNodeWithTag("ctx:speed").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("speed:ramp").assertDoesNotExist()
        st.dockRow = DockRow.MORE
        rule.waitForIdle()
        rule.onNodeWithTag("ctx:label").assertDoesNotExist()
        rule.onNodeWithTag("ctx:power").assertExists()
        // several: delete, ripple delete and on/off only
        st.selected = null
        st.multi = setOf(1L, 2L)
        rule.waitForIdle()
        rule.onNodeWithTag("multi:delete").assertExists()
        rule.onNodeWithTag("multi:ripple").assertExists()
        rule.onNodeWithTag("multi:power").assertExists()
        rule.onNodeWithTag("multi:label").assertDoesNotExist()
        rule.onNodeWithTag("syncAudio").assertDoesNotExist()
    }

    @Test fun theVolumeSliderIsOffWithAVolumeCurve() {
        val props = FakeProps().apply {
            p = ClipProps(7L, "intervju L", true, null, null, levelDb = -3f, kf = mapOf("level" to listOf(UiKf(1f, 1_000_000L, "Linear", listOf(-3.0)), UiKf(4f, 4_000_000L, "Linear", listOf(-12.0)))))
        }
        val st = EditorState().apply { this.props = props; playhead = 2f }
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        st.switchMode(true)
        st.selected = 7L
        rule.waitForIdle()
        st.dockRow = DockRow.VOLUME
        rule.waitForIdle()
        rule.onNodeWithText(Str.t(R.string.simple_reason_volume_curve)).assertExists()
        rule.onNodeWithTag("prop:level").performTouchInput {
            down(Offset(width * 0.5f, height / 2f)); for (i in 1..8) moveTo(Offset(width * (0.5f - 0.05f * i), height / 2f)); up()
        }
        rule.waitForIdle()
        assertTrue("nothing written: ${props.log}", props.log.none { it.startsWith("set ") })
        // advanced mode: the slider sets the keyframe at the playhead, as always
        st.switchMode(false)
        rule.waitForIdle()
        st.dockRow = DockRow.VOLUME
        rule.waitForIdle()
        rule.onNodeWithText(Str.t(R.string.simple_reason_volume_curve)).assertDoesNotExist()
    }

    @Test fun keyedMotionCantBeSetInSimpleMode() {
        val props = FakeProps().apply {
            p = p.copy(x = 300f, kf = mapOf("position" to listOf(UiKf(0.5f, 500_000L, "Linear", listOf(300.0, 540.0)), UiKf(3f, 3_000_000L, "Linear", listOf(900.0, 540.0)))))
        }
        val st = EditorState().apply { this.props = props; playhead = 2f; selected = 1L; inspecting = true; inspectGroup = GROUP_PICTURE }
        rule.setContent { KlippTheme { PropertiesPanel(st) {} } }
        rule.waitForIdle()
        st.switchMode(true)
        rule.waitForIdle()
        rule.onNodeWithTag("reason:motion").assertExists()
        rule.onNodeWithTag("prop:x").assertIsNotEnabled()
        // simple mode's picture tools: position, crop, stabilize (no camera here) and fades – no blend, key or mask
        rule.onNodeWithTag("propTab:blend").assertDoesNotExist()
        rule.onNodeWithTag("propTab:mask").assertDoesNotExist()
        rule.onNodeWithTag("propTab:key").assertDoesNotExist()
        rule.onNodeWithTag("propTab:crop").assertExists()
    }

    // ------------------------------------------------------------------ badges

    @Test fun badgesForWhatSimpleModeCantShow() {
        val video = Clip(1, "A", ClipKind.VIDEO, 2, 0f, 4f, 0f, 10f, 0)
        val audio = Clip(7, "B", ClipKind.AUDIO, 3, 0f, 4f, 0f, 10f, 0)
        val title = Clip(6, "T", ClipKind.TITLE, 0, 0f, 4f, 0f, 4f, 0)
        fun kinds(b: List<Badge>) = b.map { it.kind }
        assertEquals(emptyList<Badge>(), clipBadges(video, null, null))
        // what simple mode itself makes: a look, the adjustments and log footage – no badge
        val simpleGrade = SimpleGrade.withLog(ClipGrade(listOf(GradeNode(1, "Primär", contrast = 1.2f, temp = 0.3f, sat = 1.4f, gain = Wheel(master = 0.2f), lut = LutStore.TEAL_ORANGE, lutMix = 0.7f))), true, "vivo")
        assertTrue(SimpleGrade.fits(simpleGrade))
        assertEquals(emptyList<BadgeKind>(), kinds(clipBadges(video, null, simpleGrade)))
        // curves, a second coloring node, a window: advanced color (on/off offered when all nodes agree)
        val curves = ClipGrade(listOf(GradeNode(1, curveY = Curve(listOf(0f to 0f, 0.5f to 0.6f, 1f to 1f)))))
        assertEquals(listOf(Badge(BadgeKind.COLOR, on = true, canToggle = true)), clipBadges(video, null, curves))
        val mixed = ClipGrade(listOf(GradeNode(1, sat = 1.5f), GradeNode(2, hue = 10f, enabled = false)))
        assertEquals(Badge(BadgeKind.COLOR, on = null, canToggle = false), clipBadges(video, null, mixed).single())
        // the picture's advanced tools
        val layered = ClipGrade(layer = LayerFx(mask = LayerMask(), blend = BlendMode.entries.last()))
        assertEquals(listOf(BadgeKind.MASK, BadgeKind.BLEND), kinds(clipBadges(video, null, layered)))
        assertEquals(listOf(BadgeKind.BLEND), kinds(clipBadges(video, ClipExtras(opacity = 60f), null)))
        // keyframes (counted per property) and sound effects other than the two simple switches
        assertEquals(Badge(BadgeKind.KEYFRAMES, 2), clipBadges(video, ClipExtras(keyed = setOf("position", "scale")), null).single())
        val afx = listOf(AfxSnap(0, "speech_enhance", true, emptyMap()), AfxSnap(1, "parametric_eq", true, emptyMap()), AfxSnap(2, "dynamics_rack", false, emptyMap()))
        assertEquals(Badge(BadgeKind.SOUND_FX, 2, null, false), clipBadges(audio, ClipExtras(afx = afx), null).single())
        assertEquals(Badge(BadgeKind.SOUND_FX, 1, true, true), clipBadges(audio, ClipExtras(afx = afx.take(2)), null).single())
        // a title that follows an object or is pinned
        assertEquals(listOf(BadgeKind.FOLLOW, BadgeKind.PIN), kinds(clipBadges(title, null, null, follows = true, pinned = true)))
        // a track's own effects
        assertNull(trackBadge(TrackState()))
        assertEquals(Badge(BadgeKind.TRACK_FX, 1, false, true), trackBadge(TrackState(afx = listOf(AfxSnap(0, "chorus", false, emptyMap())))))
    }

    @Test fun aBadgeShowsOnTheClipAndOpensItsToolInAdvancedMode() {
        val props = FakeProps()
        val st = EditorState().apply { this.props = props; playhead = 3f }
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        st.clipExtras = mapOf(1L to ClipExtras(keyed = setOf("position")))
        rule.waitForIdle()
        rule.onNodeWithTag("badgeMark:1").assertDoesNotExist()
        st.switchMode(true)
        rule.waitForIdle()
        // (in simple mode's clip strip the mark is part of the clip's description: the unmerged tree)
        rule.onNodeWithTag("badgeMark:1", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("badgeMark:2", useUnmergedTree = true).assertDoesNotExist()
        st.selected = 1L
        rule.waitForIdle()
        rule.onNodeWithTag("ctx:badges").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("badgeDialog").assertExists()
        rule.onRoot().captureRoboImage("$dir/s04_enkelt_markering.png")
        // keyframes have no on/off in advanced mode: remove and open only
        rule.onNodeWithTag("badge:toggle").assertDoesNotExist()
        rule.onNodeWithTag("badge:open").performClick()
        rule.waitForIdle()
        assertFalse("advanced mode", st.simple)
        assertEquals(1L, st.selected)
        assertTrue(st.inspecting)
        assertEquals(GROUP_PICTURE, st.inspectGroup)
        rule.onNodeWithTag("propTab:motion").assertExists()
        rule.onNodeWithTag("propTab:mask").assertExists()
    }

    @Test fun removingKeyframesIsTheStopwatchOff() {
        val props = FakeProps()
        val st = EditorState().apply { this.props = props; playhead = 3f }
        st.clipExtras = mapOf(1L to ClipExtras(keyed = setOf("position", "level", "unknown")))
        BadgeActions(st, GradeState()).remove(1L, Badge(BadgeKind.KEYFRAMES, 2))
        assertEquals(listOf("anim position @3.0", "anim level @3.0"), props.log)
    }

    @Test fun advancedColorOnOffAndRemoveAreTheAdvancedOnes() {
        val gs = GradeState()
        val st = EditorState()
        val g = ClipGrade(listOf(GradeNode(1, "Primär", sat = 1.3f, hue = 12f), GradeNode(2, "")), layer = LayerFx(blend = BlendMode.entries.last()))
        gs.set(1L, g)
        val act = BadgeActions(st, gs)
        act.toggle(1L, Badge(BadgeKind.COLOR, on = true, canToggle = true))
        assertTrue(gs.of(1L).nodes.none { it.enabled })
        act.toggle(1L, Badge(BadgeKind.COLOR, on = false, canToggle = true))
        assertEquals(g, gs.of(1L))
        // remove = Color › Reset: the picture's own tools (the blend) stay
        act.remove(1L, Badge(BadgeKind.COLOR))
        assertEquals(g.colorReset(), gs.of(1L))
        assertNotNull(gs.of(1L).layer)
    }

    // ------------------------------------------------------------------ the color page

    private val colorClips = listOf(ColorClip(1, "A003_intervju", 0f, 8f, null, 0.0, 2))

    private fun toEnd(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performTouchInput {
            down(Offset(width * 0.5f, height / 2f))
            for (i in 1..10) moveTo(Offset(width * (0.5f + 0.08f * i), height / 2f))
            up()
        }
        rule.waitForIdle()
    }

    @Test fun simpleColorWritesWhatTheAdvancedPageWrites() {
        val st = EditorState().apply { playhead = 2f; selected = null }
        val advanced = GradeState()
        val simple = GradeState()
        var which by mutableIntStateOf(0)
        rule.setContent {
            KlippTheme {
                Box(Modifier.fillMaxSize().background(K.Bg)) {
                    if (which == 0) ColorPage(st, advanced, colorClips) { Text("tidslinje") }
                    else SimpleColorPage(st, simple, colorClips)
                }
            }
        }
        rule.waitForIdle()
        // the advanced page: Adjust's contrast, temperature and saturation, the Gain wheel's luminance, a LUT
        rule.onNodeWithTag("editor:Justera").performScrollTo().performClick()
        rule.waitForIdle()
        toEnd("prop:contrast"); toEnd("prop:temp"); toEnd("prop:sat")
        rule.onNodeWithTag("editor:Hjul").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("range:Gain").performClick()
        rule.waitForIdle()
        toEnd("prop:master")
        rule.onNodeWithTag("editor:LUT").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("nodeLut:${LutStore.TEAL_ORANGE}").performScrollTo().performClick()
        rule.waitUntil(5000) { advanced.of(1).nodes[0].lut == LutStore.TEAL_ORANGE }
        // then the simple page: exposure, contrast, warmth, saturation and the same look
        st.switchMode(true)
        which = 1
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/s05_enkelt_farg.png")
        toEnd("prop:simple:exposure"); toEnd("prop:simple:contrast"); toEnd("prop:simple:warmth"); toEnd("prop:simple:sat")
        rule.onNodeWithTag("nodeLut:${LutStore.TEAL_ORANGE}").performScrollTo().performClick()
        rule.waitUntil(5000) { simple.of(1).nodes[0].lut == LutStore.TEAL_ORANGE }
        rule.waitForIdle()
        assertEquals(1f, simple.of(1).nodes[0].gain.master, 0f)
        assertEquals(advanced.of(1).toJson().toString(), simple.of(1).toJson().toString())
        assertTrue(SimpleGrade.fits(simple.of(1)))
    }

    @Test fun theFilterGoesOnAllClipsInOneStepAndLeavesAdvancedColorAlone() {
        val st = EditorState().apply { playhead = 2f; selected = null; switchMode(true) }
        val gs = GradeState()
        val steps = mutableListOf<String>()
        gs.history = { label, _, _ -> steps += label }
        val three = colorClips + ColorClip(2, "B-roll_hamn", 8f, 13f, null, 0.0, 2) + ColorClip(3, "C012_marknad", 13f, 20f, null, 0.0, 2)
        // clip 3 was graded with advanced tools (a second node with a curve)
        val advanced = ClipGrade(listOf(GradeNode(1, "Primär"), GradeNode(2, "Kurva", sat = 1.4f)))
        gs.set(3, advanced, record = false)
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { SimpleColorPage(st, gs, three) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleColor:all").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("simpleColor:skipped").assertExists()
        toEnd("prop:simple:warmth")
        rule.waitForIdle()
        assertEquals(1f, gs.of(1).nodes[0].temp, 0f)
        assertEquals("the other clip simple mode can grade gets it too", 1f, gs.of(2).nodes[0].temp, 0f)
        assertEquals("advanced color is left alone", advanced, gs.of(3))
        assertEquals("one undo step", 1, steps.size)
        // carried over: the look too, onto each clip's own grade
        val carried = SimpleGrade.carry(SimpleGrade.withLook(gs.of(1), LutStore.TEAL_ORANGE, 0.7f), ClipGrade())
        assertEquals(LutStore.TEAL_ORANGE, carried.nodes[0].lut)
        assertEquals(0.7f, carried.nodes[0].lutMix, 0f)
        assertEquals(1f, carried.nodes[0].temp, 0f)
        // a clip whose one node holds OpenDRT (a developed raw clip): its look goes over too
        val raw = ClipGrade(listOf(GradeNode(1, "", drt = Drt())))
        val rawLook = SimpleGrade.withLook(raw, LutStore.TEAL_ORANGE)
        assertEquals(LutStore.TEAL_ORANGE, SimpleGrade.carry(rawLook, raw).nodes.firstNotNullOfOrNull { it.lut })
        // this clip only again: the next change is this clip's alone
        rule.onNodeWithTag("simpleColor:this").performClick()
        rule.waitForIdle()
        toEnd("prop:simple:sat")
        rule.waitForIdle()
        assertEquals(2f, gs.of(1).nodes[0].sat, 0f)
        assertEquals(1f, gs.of(2).nodes[0].sat, 0f)
    }

    @Test fun logFootageAddsTheAdvancedOpenDrtNode() {
        val st = EditorState().apply { playhead = 2f; selected = null }
        val advanced = GradeState()
        val simple = GradeState()
        var which by mutableIntStateOf(0)
        rule.setContent {
            KlippTheme {
                Box(Modifier.fillMaxSize().background(K.Bg)) {
                    if (which == 0) ColorPage(st, advanced, colorClips) { Text("tidslinje") }
                    else SimpleColorPage(st, simple, colorClips)
                }
            }
        }
        rule.waitForIdle()
        // advanced: a new node, OpenDRT on in its LUT tab
        rule.onNodeWithTag("addNode").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("editor:LUT").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("drt:on").performScrollTo().performClick()
        rule.waitUntil(8000) { advanced.of(1).nodes.getOrNull(1)?.drt != null }
        st.switchMode(true)
        which = 1
        rule.waitForIdle()
        rule.onNodeWithTag("simple:log").performScrollTo().performClick()
        rule.waitUntil(8000) { simple.of(1).nodes.getOrNull(1)?.drt != null }
        rule.waitForIdle()
        val a = advanced.of(1)
        val s = simple.of(1)
        // the same nodes (simple mode names its node, as the advanced node menu's Rename does)
        assertEquals("OpenDRT", s.nodes[1].label)
        assertEquals(a.nodes.map { it.copy(label = "") }, s.nodes.take(2).map { it.copy(label = "") })
        // then an empty node for the look (the advanced page's new node), which changes nothing
        assertEquals(3, s.nodes.size)
        assertTrue(SimpleGrade.empty(s.nodes[2]))
        assertTrue("OpenDRT is the last node that colours: still HLG-ready", s.hlgReady)
        // what it was shot in: the advanced "Shot in" chips' value
        rule.onNodeWithTag("simple:logIn:${se.gendut.klipp.engine.OpenDrt.Input.VIVO.key}").performScrollTo().performClick()
        rule.waitUntil(8000) { simple.of(1).nodes[1].drt?.input == se.gendut.klipp.engine.OpenDrt.Input.VIVO.key }
        // off again: the node goes
        rule.onNodeWithTag("simple:log").performScrollTo().performClick()
        rule.waitUntil(8000) { simple.of(1).nodes.size == 1 }
    }

    @Test fun withLogFootageTheLookComesAfterTheConversion() {
        val lut = LutStore.TEAL_ORANGE
        val plain = ClipGrade(listOf(GradeNode(1, "Primär", contrast = 1.2f, lut = lut, lutMix = 0.6f)))
        // log on: OpenDRT, then the look moved into its own node after it, strength kept
        val log = SimpleGrade.withLog(plain, true, "vlog")
        assertEquals(listOf(null, null, lut), log.nodes.map { it.lut })
        assertEquals(0.6f, log.nodes[2].lutMix, 1e-6f)
        assertEquals(1.2f, log.nodes[0].contrast, 1e-6f)
        assertTrue(SimpleGrade.fits(log))
        assertEquals(log.nodes[2].id, SimpleGrade.lookNode(log)!!.id)
        // a look after the conversion: the clip goes into an HLG export as SDR
        assertFalse(log.hlgReady)
        // another look picked: same node, same strength; none: the node stays, empty – HLG-ready again
        val other = SimpleGrade.withLook(log, LutStore.names.first { it != lut })
        assertEquals(3, other.nodes.size)
        assertEquals(0.6f, other.nodes[2].lutMix, 1e-6f)
        val none = SimpleGrade.withLook(other, null)
        assertTrue(SimpleGrade.empty(none.nodes[2]))
        assertTrue(none.hlgReady)
        // log off: the look goes back into the first node and OpenDRT and the look node go
        val back = SimpleGrade.withLog(log, false, null)
        assertEquals(1, back.nodes.size)
        assertEquals(lut, back.nodes[0].lut)
        assertEquals(0.6f, back.nodes[0].lutMix, 1e-6f)
        // without log footage the look stays in the first node
        val noLog = SimpleGrade.withLook(ClipGrade(listOf(GradeNode(1, "Primär"))), lut)
        assertEquals(1, noLog.nodes.size)
        assertEquals(lut, noLog.nodes[0].lut)
    }

    @Test fun anAdvancedGradeIsNotTouchedBySimpleMode() {
        val st = EditorState().apply { playhead = 2f; selected = null; simple = true }
        val gs = GradeState()
        val g = ClipGrade(listOf(GradeNode(1, "Primär", curveY = Curve(listOf(0f to 0.05f, 1f to 0.95f)), drt = Drt(look = 2))))
        gs.set(1L, g)
        val before = gs.toJson().toString()
        val rev = gs.revision
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { SimpleColorPage(st, gs, colorClips) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("simpleColor:advanced").assertExists()
        rule.onNodeWithTag("prop:simple:exposure").assertDoesNotExist()
        rule.onRoot().captureRoboImage("$dir/s06_enkelt_farg_avancerad.png")
        assertEquals(before, gs.toJson().toString())
        assertEquals(rev, gs.revision)
    }

    // ------------------------------------------------------------------ sound

    @Test fun theSoundSwitchesAreTheRackEffects() {
        val props = FakeProps().apply { p = ClipProps(7L, "intervju L", true, null, null) }
        // on: added to the rack – what the advanced rack's add tile does
        setSimpleFx(props, 7L, props.p.afx, "speech_enhance", true)
        assertEquals(listOf("addfx speech_enhance"), props.log)
        assertTrue(simpleFxOn(props.p.afx, "speech_enhance"))
        // off: removed (its settings were the defaults)
        setSimpleFx(props, 7L, props.p.afx, "speech_enhance", false)
        assertEquals("delfx 3", props.log.last())
        assertTrue(props.p.afx.isEmpty())
        // tuned in advanced mode: only turned off, so the settings stay; on again = turned on
        props.p = props.p.copy(afx = listOf(AfxSnap(0, "denoise", true, mapOf("amount" to 80.0))))
        setSimpleFx(props, 7L, props.p.afx, "denoise", false)
        assertEquals("togglefx 0", props.log.last())
        assertEquals(80.0, props.p.afx.single().params["amount"]!!, 0.0)
        setSimpleFx(props, 7L, props.p.afx, "denoise", true)
        assertEquals("togglefx 0", props.log.last())
        assertTrue(props.p.afx.single().on)
    }

    @Test fun theSoundTabHasTwoSwitchesInSimpleMode() {
        val props = FakeProps().apply { p = ClipProps(7L, "intervju L", true, null, null) }
        val st = EditorState().apply { this.props = props; playhead = 2f; selected = 7L; inspecting = true; openTab = "propTab:afx"; simple = true }
        rule.setContent { KlippTheme { PropertiesPanel(st) {} } }
        rule.waitForIdle()
        rule.onNodeWithTag("sfx:speech_enhance").assertExists()
        rule.onNodeWithTag("sfx:denoise").performClick()
        rule.waitForIdle()
        assertEquals("addfx denoise", props.log.last())
        rule.onNodeWithTag("fxAdd").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ settings and export

    @Test fun simpleSettingsAreReduced() {
        var mode: Boolean? = null
        rule.setContent { KlippTheme { SettingsScreen(onClose = {}, simple = true, onMode = { mode = it }) } }
        rule.waitForIdle()
        rule.onNodeWithTag("set:cat:experimental").assertExists()
        rule.onNodeWithTag("set:cat:appearance").assertExists()
        rule.onNodeWithTag("set:cat:timeline").assertExists()
        rule.onNodeWithTag("set:cat:help").assertExists()
        rule.onNodeWithTag("set:cat:about").assertExists()
        rule.onNodeWithTag("set:cat:pipeline").assertDoesNotExist()
        rule.onNodeWithTag("set:cat:developer").assertDoesNotExist()
        rule.onRoot().captureRoboImage("$dir/s07_enkelt_installningar.png")
        rule.onNodeWithTag("set:cat:experimental").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("mode:advanced").performClick()
        rule.waitForIdle()
        assertEquals(false, mode)
        // (simple mode: no magnetic switch – it is always on)
        rule.onNodeWithTag("set:magnetic").assertDoesNotExist()
    }

    @Test fun simpleSettingsTimelinePage() {
        rule.setContent { KlippTheme { SettingsScreen(onClose = {}, simple = true, onMode = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("set:cat:timeline").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("set:magnetic").assertDoesNotExist()
        rule.onNodeWithTag("set:titleSec").assertExists()
    }

    @Test fun simpleExportIsGoodOrBest() {
        var s by androidx.compose.runtime.mutableStateOf(se.gendut.klipp.engine.ExportSettings())
        rule.setContent {
            KlippTheme {
                CompositionLocalProvider(LocalSimple provides true) {
                    androidx.compose.foundation.layout.Column {
                        ExportOptions(s, 3840, 2160, 30.0, hdrPossible = false, hdrBlocked = null, seconds = 45.0) { s = it }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("expTab:0").assertDoesNotExist()
        rule.onNodeWithTag("exp:codec").assertDoesNotExist()
        rule.onNodeWithTag("exp:preset").assertDoesNotExist()
        // Good: the compatible preset at Full HD; Best: the quality preset at 4K – the same settings the advanced tabs write
        rule.onNodeWithTag("exp:quality:GOOD").performClick()
        rule.waitForIdle()
        assertEquals(1080, s.height)
        assertEquals(se.gendut.klipp.engine.ExportPreset.COMPATIBLE, se.gendut.klipp.engine.ExportPreset.of(s, 3840, 2160, 30.0, se.gendut.klipp.engine.PhoneEncoders))
        assertEquals(SimpleQuality.GOOD, SimpleQuality.of(s, se.gendut.klipp.engine.ExportPreset.COMPATIBLE))
        rule.onNodeWithTag("exp:quality:BEST").performClick()
        rule.waitForIdle()
        assertEquals(2160, s.height)
        assertEquals(se.gendut.klipp.engine.ExportPreset.QUALITY, se.gendut.klipp.engine.ExportPreset.of(s, 3840, 2160, 30.0, se.gendut.klipp.engine.PhoneEncoders))
        // a Full HD project's Best is its own size (nothing is blown up)
        assertEquals(0, SimpleQuality.BEST.apply(s, 1920, 1080, 30.0, se.gendut.klipp.engine.PhoneEncoders).height)
    }
}
