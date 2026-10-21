package se.gendut.klipp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class KlippUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir = "build/shots"

    private fun setUp(): Pair<EditorState, AppUi> {
        val state = EditorState()
        val ui = AppUi()
        rule.setContent { KlippTheme { KlippApp(state, ui) } }
        rule.waitForIdle()
        return state to ui
    }

    /** A point in timeline coordinates (dp → px). pps = 16 dp/s, ruler 28 dp. */
    private fun pt(sec: Float, trackCenterDp: Float): Offset {
        val d = rule.density.density
        return Offset(sec * 16f * d, (28f + trackCenterDp) * d)
    }

    private val v1Center = 80f + 26f

    @Test
    fun editorRendersAndClipSnapsAndRipples() {
        val (state, _) = setUp()
        rule.onRoot().captureRoboImage("$dir/01_redigera.png")

        // Drag B-roll (8–13 s on V1) right ~5.75 s: snaps to the playhead (14 s), lands in A003 → jumps to its end and pushes C012
        val tl = rule.onNodeWithTag("timeline")
        tl.performTouchInput {
            val from = pt(10.5f, v1Center)
            down(from)
            val steps = 20
            val dx = 5.75f * 16f * density
            for (i in 1..steps) moveTo(Offset(from.x + dx * i / steps, from.y))
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/02_dra_fastning.png")
        tl.performTouchInput { up() }
        rule.waitForIdle()
        val broll = state.clips.first { it.id == 2L }
        val c012 = state.clips.first { it.id == 4L }
        assertEquals(22f, broll.start, 0.01f)
        assertEquals(27f, c012.start, 0.01f)
        assertEquals(1, state.undoCount)
        rule.onRoot().captureRoboImage("$dir/03_efter_ripple.png")

        // Two-finger tap = undo
        tl.performTouchInput {
            down(0, pt(5f, v1Center + 40f))
            down(1, pt(9f, v1Center + 40f))
            up(0); up(1)
        }
        rule.waitForIdle()
        assertEquals(8f, state.clips.first { it.id == 2L }.start, 0.01f)
        assertEquals(22f, state.clips.first { it.id == 4L }.start, 0.01f)
    }

    @Test
    fun selectTrimAndRazor() {
        val (state, _) = setUp()
        val tl = rule.onNodeWithTag("timeline")
        // Tap the clip = select
        tl.performTouchInput { click(pt(10.5f, v1Center)) }
        rule.waitForIdle()
        assertEquals(2L, state.selected)
        rule.onRoot().captureRoboImage("$dir/04_markerat.png")

        // Trim the right edge 13 s → 11.1 s; the playhead is at 11 s so the edge should snap there
        state.playhead = 11f
        rule.waitForIdle()
        tl.performTouchInput {
            val from = pt(12.95f, v1Center)
            down(from)
            for (i in 1..12) moveTo(Offset(from.x - 1.85f * 16f * density * i / 12, from.y))
            up()
        }
        rule.waitForIdle()
        assertEquals(3f, state.clips.first { it.id == 2L }.length, 0.01f)

        // Razor: split A003 (0–8 s) at 4 s
        toolClick("Rakblad")
        tl.performTouchInput { click(pt(4f, v1Center)) }
        rule.waitForIdle()
        val parts = state.clips.filter { it.track == 2 && it.name == "A003_intervju" && it.start < 8f }
        assertEquals(2, parts.size)
        assertEquals(4f, parts.maxOf { it.start }, 0.05f)
        rule.onRoot().captureRoboImage("$dir/05_rakblad.png")
    }

    @Test
    fun threeFingerSwipeChangesMode() {
        val (_, ui) = setUp()
        rule.onRoot().performTouchInput {
            val y = height * 0.75f
            val xs = listOf(width * 0.6f, width * 0.7f, width * 0.8f)
            xs.forEachIndexed { i, x -> down(i, Offset(x, y)) }
            for (s in 1..15) {
                xs.forEachIndexed { i, x -> updatePointerTo(i, Offset(x - width * 0.45f * s / 15, y)) }
                move()
            }
            xs.indices.forEach { up(it) }
        }
        rule.waitForIdle()
        assertEquals(1f, ui.page.value, 0.01f)
        rule.onRoot().captureRoboImage("$dir/06_farg.png")

        rule.onNodeWithText("Varm").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/07_farg_varm.png")
    }

    @Test
    fun audioAndTextPanels() {
        val (_, ui) = setUp()
        rule.onNodeWithText("Ljud").performClick()
        rule.waitForIdle()
        assertEquals(2f, ui.page.value, 0.01f)
        rule.onRoot().captureRoboImage("$dir/08_ljud.png")
        rule.onNodeWithText("Text").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Namnskylt").performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.onRoot().captureRoboImage("$dir/09_text.png")
    }

    @Test
    fun addClipFromFab() {
        val (state, _) = setUp()
        val before = state.clips.size
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Lägg till klipp")).performClick()
        rule.mainClock.advanceTimeBy(120)
        rule.onRoot().captureRoboImage("$dir/10_nytt_klipp_flyger.png")
        rule.waitForIdle()
        assertEquals(before + 1, state.clips.size)
        val added = state.clips.maxBy { it.id }
        assertTrue(added.start >= 13f)
    }

    private fun toolClick(desc: String) {
        rule.onNode(androidx.compose.ui.test.hasContentDescription(desc)).performClick()
        rule.waitForIdle()
    }

    @Test
    fun centeredPlayheadScrubsTimelineAndSnaps() {
        val state = EditorState()
        state.centerPlayhead = true
        rule.setContent { KlippTheme { KlippApp(state, AppUi()) } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/11_last_spelhuvud.png")
        // The playhead is at 14 s in the middle. Drag the empty V2 lane right 1.1 s → 12.9 s → snaps to the clip edge at 13 s.
        val tl = rule.onNodeWithTag("timeline")
        tl.performTouchInput {
            val from = Offset(width * 0.75f, (28f + 40f + 20f) * density)
            down(from)
            for (i in 1..12) moveTo(Offset(from.x + 1.1f * 16f * density * i / 12, from.y))
            up()
        }
        rule.waitForIdle()
        assertEquals(13f, state.playhead, 0.02f)
        rule.onRoot().captureRoboImage("$dir/12_last_spelhuvud_fast.png")
    }

    @Test
    fun sourcePanelSitsAboveTimeline() {
        val state = EditorState()
        val media = se.gendut.klipp.engine.MediaInfo(
            id = 1, name = "IMG_2041.mp4", path = null, duration = 42.0, hasVideo = true, hasAudio = true,
            width = 1920, height = 1080, fps = 29.97, markIn = 4.0, markOut = 11.5,
        )
        rule.setContent {
            KlippTheme {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val src = androidx.compose.runtime.remember { se.gendut.klipp.engine.SourcePlayback(ctx) }
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                    SourcePanel(media, android.net.Uri.parse("file:///none.mp4"), src, state, setMarks = { _, _ -> }, edit = { _, done -> done() }, onClose = {})
                    Timeline(state, Tool.SELECT, androidx.compose.ui.Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/13_kalla_over_tidslinje.png")
        rule.onNodeWithText("Infoga").assertExists()
    }

    @Test
    fun copyActionSendsSelectedClip() {
        val state = EditorState()
        var copied: Long? = null
        state.onCopyToSource = { copied = it }
        rule.setContent { KlippTheme { KlippApp(state, AppUi()) } }
        rule.waitForIdle()
        rule.onNodeWithTag("timeline").performTouchInput { click(pt(10.5f, v1Center)) }
        rule.waitForIdle()
        rule.onNodeWithText("Kopiera").performClick()
        rule.waitForIdle()
        assertEquals(2L, copied)
        rule.onRoot().captureRoboImage("$dir/14_kopiera.png")
    }

    @Test
    fun targetTrackMarkerAndNextEdit() {
        val state = EditorState()
        rule.setContent { KlippTheme { KlippApp(state, AppUi()) } }
        rule.waitForIdle()
        // tap the V2 header → V2 becomes the target track
        rule.onNodeWithText("V2").performClick()
        rule.waitForIdle()
        assertEquals(1, state.targetVideo)
        // marker at the playhead (14 s)
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Lägg till markör")).performClick()
        rule.waitForIdle()
        assertTrue(14f in state.markers)
        // the next clip edge after 14 s is the title's start (15 s)
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Nästa klippkant")).performClick()
        rule.waitForIdle()
        assertEquals(15f, state.playhead, 0.001f)
        rule.onRoot().captureRoboImage("$dir/16_malspar_markor.png")
    }

    @Test
    fun speedFromContextBar() {
        val state = EditorState()
        rule.setContent { KlippTheme { KlippApp(state, AppUi()) } }
        rule.waitForIdle()
        rule.onNodeWithTag("timeline").performTouchInput { click(pt(10.5f, v1Center)) }
        rule.waitForIdle()
        rule.onNodeWithText("Hastighet").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/17_hastighet.png")
        rule.onNodeWithText("200%").performClick()
        rule.waitForIdle()
        val c = state.clips.first { it.id == 2L }
        assertEquals(2f, c.speed, 0.001f)
        assertEquals(2.5f, c.length, 0.01f)
        // following clips move (ripple)
        assertEquals(10.5f, state.clips.first { it.id == 3L }.start, 0.01f)
    }

    @Test
    fun verticalScrollRevealsLowerTracks() {
        val state = EditorState()
        rule.setContent {
            KlippTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp(200f))) {
                    Timeline(state, Tool.SELECT, androidx.compose.ui.Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/18_rulla_fore.png")
        // drag up on empty space (the V2 lane after 20 s) → scrolls down
        rule.onNodeWithTag("timeline").performTouchInput {
            val from = Offset(width * 0.9f, height * 0.5f)
            down(from)
            for (i in 1..15) moveTo(Offset(from.x, from.y - 140f * density * i / 15))
            up()
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/19_rulla_efter.png")
        // after scrolling: a tap lower down hits the A3 music (which was off screen before)
        rule.onNodeWithTag("timeline").performTouchInput { click(Offset(width * 0.5f, height * 0.65f)) }
        rule.waitForIdle()
        assertEquals("the A3 clip can be selected after scrolling", 11L, state.selected)
    }

    @Test
    fun colorPanelShowsLuts() {
        val (_, ui) = setUp()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Färg")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(se.gendut.klipp.engine.LutStore.TEAL_ORANGE).performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/21_lut.png")
        rule.onNodeWithText("Tetraedrisk").assertExists()
        rule.onNodeWithText("Styrka").assertExists()
        assertEquals(1f, ui.page.value, 0.01f)
    }
}
