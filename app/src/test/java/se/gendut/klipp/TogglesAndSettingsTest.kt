package se.gendut.klipp

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.ExportSettings
import se.gendut.klipp.engine.LayerPlanner
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.playable

/** Disabled clips, the track buttons (eye/speaker, captions), dropdown menus and the settings. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class TogglesAndSettingsTest {
    @get:Rule val rule = createComposeRule()

    private fun snapJson() = JSONObject(javaClass.classLoader!!.getResource("snapshot.json")!!.readText())

    // ------------------------------------------------------------------ disabled clips

    @Test fun disabledClipsAreLeftOutOfPlaybackAndExport() {
        val plain = Snapshot.parse(snapJson())
        // no disabled clips: the same object (no copy on every build)
        assertSame(plain, plain.playable())
        val v1 = plain.sequence!!.video[0]
        val off = v1.clips.first()
        val j = snapJson()
        j.getJSONObject("sequence").getJSONArray("video").getJSONObject(0).getJSONArray("clips").getJSONObject(0).put("enabled", false)
        val s = Snapshot.parse(j)
        assertFalse(s.sequence!!.video[0].clips.first().enabled)
        val p = s.playable()
        val pv1 = p.sequence!!.video[0]
        assertEquals(v1.clips.size - 1, pv1.clips.size)
        assertTrue(pv1.clips.none { it.id == off.id })
        assertTrue("transitions to/from the clip are removed", pv1.transitions.none { it.from == off.id || it.to == off.id })
        // the timeline is just as long (the clip becomes a gap)
        assertEquals(s.sequence!!.duration, p.sequence!!.duration, 1e-9)
        // the composition and the layer plan are built without it
        val uriOf: (Long) -> Uri? = { Uri.parse("file:///media/$it.mp4") }
        assertNotNull(CompositionBuilder.build(s, uriOf, output = ExportSettings()))
        val plan = LayerPlanner.plan(s) { 60.0 }
        assertTrue(plan == null || plan.layers.flatMap { it.segs }.none { seg -> seg.clip?.id == off.id })
    }

    @Test fun contextBarTurnsAClipOffAndOnAndTheTimelineGraysIt() {
        val st = EditorState().apply { playhead = 30f }
        val id = st.clips.first { it.kind == ClipKind.VIDEO }.id
        st.selected = id
        rule.setContent { KlippTheme { KlippApp(st) } }
        rule.waitForIdle()
        rule.onNodeWithText("Stäng av").performClick()
        rule.waitForIdle()
        assertFalse(st.clips.first { it.id == id }.enabled)
        rule.onNodeWithTag("clipOff:$id", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Slå på").performClick()
        rule.waitForIdle()
        assertTrue(st.clips.first { it.id == id }.enabled)
    }

    @Test fun lockedClipsStayOn() {
        val st = EditorState()
        val c = st.clips.first()
        st.setTrackFlag(c.track, "locked", true)
        st.toggleEnabled(listOf(c.id))
        assertTrue(st.clips.first { it.id == c.id }.enabled)
    }

    // ------------------------------------------------------------------ track buttons

    @Test fun headerButtonsHideVideoMuteAudioAndHideCaptions() {
        val st = EditorState().apply { playhead = 30f; hasCaptionLane = true }
        st.onCaptionsVisible = { st.captionsOn = it }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { Timeline(st, Tool.SELECT, Modifier.fillMaxSize()) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("trackToggle:V1").performClick()
        rule.onNodeWithTag("trackToggle:A2").performClick()
        rule.waitForIdle()
        assertFalse("V1 hidden", st.trackStates[2].enabled)
        assertTrue("A2 muted", st.trackStates[4].muted)
        rule.onNodeWithTag("capToggle").performClick()
        rule.waitForIdle()
        assertFalse(st.captionsOn)
        // back
        rule.onNodeWithTag("trackToggle:V1").performClick()
        rule.onNodeWithTag("capToggle").performClick()
        rule.waitForIdle()
        assertTrue(st.trackStates[2].enabled)
        assertTrue(st.captionsOn)
    }

    // ------------------------------------------------------------------ dropdown menus

    @Test fun exportOptionsAreDropdowns() {
        var s by mutableStateOf(ExportSettings())
        rule.setContent {
            KlippTheme {
                Column(Modifier.fillMaxSize().background(K.Bg).verticalScroll(rememberScrollState())) {
                    ExportOptions(s, 1920, 1080, 30.0, hdrPossible = false, hdrBlocked = null) { s = it }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("exp:fps").performScrollTo().performClick()
        rule.onNodeWithTag("exp:fps:fps24.0").performClick()
        rule.waitForIdle()
        assertEquals(24.0, s.fps, 1e-9)
        rule.onNodeWithTag("exp:q").performScrollTo().performClick()
        rule.onNodeWithTag("exp:q:custom").performClick()
        rule.waitForIdle()
        assertTrue("custom bitrate", s.bitrateMbps > 0f)
        rule.onNodeWithTag("prop:expBitrate").assertExists()
        rule.onNodeWithTag("exp:key").performScrollTo().performClick()
        rule.onNodeWithTag("exp:key:k2.0").performClick()
        rule.waitForIdle()
        assertEquals(2f, s.keyframeSec)
        rule.onNodeWithTag("exp:sr").performScrollTo().performClick()
        rule.onNodeWithTag("exp:sr:sr44100").performClick()
        rule.waitForIdle()
        assertEquals(44_100, s.sampleRate)
    }

    // ------------------------------------------------------------------ settings

    @Test fun settingsChangeThemeAndDefaults() {
        val ctx = RuntimeEnvironment.getApplication()
        var center: Boolean? = null
        rule.setContent { KlippTheme { SettingsScreen(onClose = {}, onCenterDefault = { center = it }) } }
        rule.waitForIdle()
        try {
            rule.onNodeWithTag("accent:BLUE").performScrollTo().performClick()
            rule.waitForIdle()
            assertEquals(Accent.BLUE, K.accent)
            assertEquals(Accent.BLUE, AppPrefs.accent(ctx))
            assertEquals(androidx.compose.ui.graphics.Color(Accent.BLUE.primary), K.Primary)
            rule.onNodeWithTag("base").performScrollTo().performClick()
            rule.onNodeWithTag("base:BLACK").performClick()
            rule.waitForIdle()
            assertEquals(Base.BLACK, K.base)
            rule.onNodeWithTag("set:center").performScrollTo().performClick()
            rule.waitForIdle()
            assertEquals(true, center)
            assertTrue(AppPrefs.centerDefault(ctx))
            rule.onNodeWithTag("set:haptics").performScrollTo().performClick()
            rule.waitForIdle()
            assertFalse(Haptics.enabled)
            rule.onNodeWithTag("set:titleSec").performScrollTo().performClick()
            rule.onNodeWithTag("set:titleSec:t8.0").performClick()
            rule.waitForIdle()
            assertEquals(8f, AppPrefs.titleSeconds(ctx))
        } finally {
            AppPrefs.setAccent(ctx, Accent.ORANGE)
            AppPrefs.setBase(ctx, Base.DARK)
            AppPrefs.setCenterDefault(ctx, false)
            AppPrefs.setHaptics(ctx, true)
            AppPrefs.setTitleSeconds(ctx, 5f)
        }
    }
}
