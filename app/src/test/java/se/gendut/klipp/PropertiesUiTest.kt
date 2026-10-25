package se.gendut.klipp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fake engine: keeps the properties in memory and logs the calls. */
class FakeProps : PropsBackend {
    var p by mutableStateOf(ClipProps(1L, "A003.mp4", false, null, null, seqW = 1920, seqH = 1080))
    val log = mutableListOf<String>()
    override fun props(id: Long) = if (id == p.id) p else null
    override fun setProps(id: Long, x: Float?, y: Float?, scale: Float?, rotation: Float?, opacity: Float?, levelDb: Float?) {
        log += "set x=$x y=$y scale=$scale rot=$rotation op=$opacity db=$levelDb"
        p = p.copy(x = x ?: p.x, y = y ?: p.y, scale = scale ?: p.scale, rotation = rotation ?: p.rotation, opacity = opacity ?: p.opacity, levelDb = levelDb ?: p.levelDb)
    }
    override fun resetMotion(id: Long) { log += "reset"; p = p.copy(x = null, y = null, scale = 100f, rotation = 0f, opacity = 100f) }
    override fun setTransition(id: Long, edgeIn: Boolean, seconds: Float?) {
        log += "fade ${if (edgeIn) "in" else "ut"} $seconds"
        p = if (edgeIn) p.copy(fadeIn = seconds) else p.copy(fadeOut = seconds)
    }
    override fun resizeTransition(transition: Long, seconds: Float) { log += "resize $transition $seconds" }
    override fun removeTransition(transition: Long) { log += "remove $transition" }
    override fun addCutTransition(right: Long, effect: String, seconds: Float, onAdded: (Long) -> Unit) { log += "addcut $right $effect $seconds"; onAdded(42L) }
    override fun addTitle(text: String, at: Float, look: String) { log += "title $text @$at $look".trimEnd() }
    override fun setTitle(id: Long, text: String?, size: Float?, color: Int?, look: String?) { log += "settitle $text $size $color $look" }
    override fun importCaptions() { log += "captions" }
    override fun setCaptionText(id: Long, text: String) { log += "captiontext $id $text" }
    override fun deleteCaption(id: Long) { log += "deletecaption $id" }
    override fun toggleAnimation(id: Long, key: String, at: Float) { log += "anim $key @$at" }
    override fun toggleKeyframe(id: Long, key: String, at: Float) { log += "kf $key @$at" }
    override fun moveKeyframe(id: Long, key: String, mt: Long, to: Float) { log += "move $key $mt -> $to" }
    override fun setInterpolation(id: Long, key: String, mt: Long, interp: String) { log += "interp $key $mt $interp" }
    override fun setEase(id: Long, key: String, mt: Long, ease: String) { log += "ease $key $mt $ease" }
    override fun setInfluence(id: Long, key: String, mt: Long, inInf: Double?, outInf: Double?, interp: String?) {
        log += "infl $key $mt $inInf $outInf" + (interp?.let { " $it" } ?: "")
    }
    override fun setKeyframeValue(id: Long, key: String, mt: Long, v: List<Double>) { log += "value $key $mt $v" }
    override fun setCaptionsVisible(visible: Boolean) { log += "visible $visible" }
    override fun addAudioFx(id: Long, effect: String) {
        log += "addfx $effect"
        p = p.copy(afx = p.afx + se.gendut.klipp.engine.AfxSnap(3 + p.afx.size, effect, true, emptyMap()))
    }
    override fun removeAudioFx(id: Long, index: Int) { log += "delfx $index"; p = p.copy(afx = p.afx.filter { it.index != index }) }
    override fun toggleAudioFx(id: Long, index: Int) { log += "togglefx $index"; p = p.copy(afx = p.afx.map { if (it.index == index) it.copy(on = !it.on) else it }) }
    override fun setAudioFxParam(id: Long, index: Int, param: String, value: Double) {
        log += "fxparam $index $param ${"%.1f".format(java.util.Locale.ROOT, value)}"
        p = p.copy(afx = p.afx.map { if (it.index == index) it.copy(params = it.params + (param to value)) else it })
    }
    override val captionsVisible = true
    override val hasCaptionTrack = true
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class PropertiesUiTest {
    @get:Rule
    val rule = createComposeRule()
    private val dir = "build/shots"

    private fun state(fake: FakeProps) = EditorState().apply { props = fake; selected = 1L; inspecting = true }

    @Test
    fun sliderDragCommitsOnceOnReleaseAndSnapsToDefault() {
        val fake = FakeProps()
        val st = state(fake)
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { PropertiesPanel(st) { st.inspecting = false } } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/20_egenskaper.png")
        // scale: drag to ~3/4 of the track (0–400 % → ≈300 %)
        rule.onNodeWithTag("prop:scale").performTouchInput {
            down(Offset(width * 0.25f, height / 2f))
            for (i in 1..10) moveTo(Offset(width * (0.25f + 0.05f * i), height / 2f))
            up()
        }
        rule.waitForIdle()
        assertEquals(1, fake.log.size)
        assertTrue(fake.log[0], fake.p.scale in 290f..310f)
        // drag back near 100 % → stays where the finger is (no snapping: small changes around the default are possible)
        rule.onNodeWithTag("prop:scale").performTouchInput {
            down(Offset(width * 0.75f, height / 2f))
            for (i in 1..10) moveTo(Offset(width * (0.75f - 0.0505f * i), height / 2f))
            up()
        }
        rule.waitForIdle()
        assertTrue("${fake.p.scale}", fake.p.scale in 95f..99.9f)
        // a single tap changes nothing (you can brush it by accident while scrolling)
        rule.onNodeWithTag("prop:opacity").performTouchInput { click(Offset(width * 0.1f, height / 2f)) }
        rule.waitForIdle()
        assertEquals(2, fake.log.size)
        // double tap = exactly back to 100 %
        rule.onNodeWithTag("prop:scale").performTouchInput { doubleClick(Offset(width * 0.6f, height / 2f)) }
        rule.waitForIdle()
        assertEquals(100f, fake.p.scale, 0.001f)
    }

    @Test
    fun fadeChipsSetAndRemoveTransitions() {
        val fake = FakeProps()
        val st = state(fake)
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { PropertiesPanel(st) { } } } }
        rule.onNodeWithTag("fadeIn1.0").performClick()
        rule.waitForIdle()
        assertEquals(1f, fake.p.fadeIn)
        rule.onRoot().captureRoboImage("$dir/21_overtoning.png")
        assertEquals("fade in 1.0", fake.log.last())
    }

    @Test
    fun audioClipShowsVolumeOnly() {
        val fake = FakeProps().apply { p = p.copy(isAudio = true, levelDb = -6f, name = "A1 ljud") }
        val st = state(fake)
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { PropertiesPanel(st) { } } } }
        rule.onNodeWithTag("prop:volume").assertExists()
        assertTrue(runCatching { rule.onNodeWithTag("prop:scale").assertExists() }.isFailure)
    }

    @Test
    fun titleClipShowsTextSizeAndColors() {
        val fake = FakeProps().apply { p = p.copy(title = "Hej", name = "Hej", textSize = 97f) }
        val st = state(fake)
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { PropertiesPanel(st) { } } } }
        rule.waitForIdle()
        rule.onNodeWithTag("prop:size").assertExists()
        rule.onRoot().captureRoboImage("$dir/22_titel.png")
    }

    @Test
    fun monitorGesturesMoveScaleAndRotateWithSnapping() {
        val fake = FakeProps()
        val st = state(fake)
        rule.setContent {
            KlippTheme {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.DarkGray)) { MotionOverlay(st) }
            }
        }
        rule.waitForIdle()
        // one finger: move a quarter of the width to the right
        rule.onNodeWithTag("motion").performTouchInput {
            val c = center
            down(c)
            for (i in 1..10) moveTo(Offset(c.x + width * 0.025f * i, c.y))
            up()
        }
        rule.waitForIdle()
        assertEquals(1, fake.log.size)
        assertEquals(1920f * 0.75f, fake.p.x!!, 8f)
        assertEquals("y snapped to the center", 540f, fake.p.y!!, 0.001f)
        // two fingers: pinch out (×2) and rotate ~30°
        rule.onNodeWithTag("motion").performTouchInput {
            val c = center
            val r0 = width * 0.1f
            down(0, Offset(c.x - r0, c.y)); down(1, Offset(c.x + r0, c.y))
            for (i in 1..20) {
                val f = i / 20f
                val r = r0 * (1f + f)
                val a = Math.toRadians(30.0 * f)
                moveTo(0, Offset(c.x - r * kotlin.math.cos(a).toFloat(), c.y - r * kotlin.math.sin(a).toFloat()))
                moveTo(1, Offset(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat()))
            }
            up(0); up(1)
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/23_rorelse.png")
        assertEquals(2, fake.log.size)
        assertEquals(200f, fake.p.scale, 6f)
        assertEquals(30f, fake.p.rotation, 2f)
    }

    @Test
    fun titlesPanelAddsTitleAtPlayheadAndImportsCaptions() {
        val fake = FakeProps()
        val st = EditorState().apply { props = fake; playhead = 3f; captions = listOf(UiCaption(1, 0.5f, 2f, "Första raden")) }
        var edited = false
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { TitlesPanel(st) { edited = true } } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/24_text.png")
        rule.onNodeWithText("Lägg till vid", substring = true).performScrollTo().performClick()
        // captions have their own tab
        rule.onNodeWithTag("textTab:1").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/24b_undertexter.png")
        rule.onNodeWithText("Importera SRT / VTT").performScrollTo().performClick()
        rule.onNodeWithText("Första raden").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(edited)
        assertEquals(listOf("title Anna Berg\nFiskare, Smögen @3.0 klipp:mall=namnskylt;in=studs", "captions"), fake.log)
        assertEquals(0.5f, st.playhead, 0.001f)
        // the line opens for editing
        rule.onNodeWithTag("caption:1").performTextReplacement("Ny text")
        rule.onNodeWithText("Spara").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("captiontext 1 Ny text", fake.log.last())
        assertNotNull(st)
    }

    @Test
    fun titleTemplatesPreviewAndAddWithChosenLook() {
        val fake = FakeProps()
        val st = EditorState().apply { props = fake; playhead = 1f }
        rule.mainClock.autoAdvance = false
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { TitlesPanel(st) { } } } }
        rule.mainClock.advanceTimeBy(700)
        rule.onRoot().captureRoboImage("$dir/28_mallar.png")
        rule.onNodeWithTag("mall:kapitel").performClick()
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("anim:skala").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onRoot().captureRoboImage("$dir/29_kapitel_skala.png")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Lägg till vid", substring = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("title Kapitel 2\nHavet @1.0 klipp:mall=kapitel;in=skala", fake.log.last())
    }

    @Test
    fun keyframesStopwatchLaneAndNavigation() {
        val fake = FakeProps().apply {
            p = p.copy(clipStart = 0f, clipEnd = 8f, kf = mapOf("scale" to listOf(UiKf(1f, 100L, "Linear"), UiKf(4f, 400L, "AutoBezier"), UiKf(6f, 600L, "Hold"))))
        }
        val st = state(fake).apply { playhead = 2f }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { PropertiesPanel(st) { } } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/31_nyckelrutor.png")
        // stopwatch on opacity = turn on animation at the playhead
        rule.onNodeWithTag("stopwatch:opacity").performClick()
        assertEquals("anim opacity @2.0", fake.log.last())
        // ◀ ▶ jump between keyframes
        rule.onNodeWithTag("kfnext:scale").performClick()
        assertEquals(4f, st.playhead, 0.001f)
        rule.onNodeWithTag("kfprev:scale").performClick()
        assertEquals(1f, st.playhead, 0.001f)
        // ◆ on a keyframe = remove/add at the playhead
        rule.onNodeWithTag("kf:scale").performClick()
        assertEquals("kf scale @1.0", fake.log.last())
        // drag the keyframe at 4 s to ~5 s
        rule.onNodeWithTag("lane:scale").performTouchInput {
            val x4 = width * 4f / 8f
            down(Offset(x4, height / 2f))
            for (i in 1..10) moveTo(Offset(x4 + width / 8f * i / 10, height / 2f))
            up()
        }
        rule.waitForIdle()
        val mv = fake.log.last()
        assertTrue(mv, mv.startsWith("move scale 400 -> "))
        assertEquals(5f, mv.substringAfter("-> ").toFloat(), 0.1f)
        // long press = the interpolation menu; easy ease
        rule.onNodeWithTag("lane:scale").performTouchInput { longClick(Offset(width * 4f / 8f, height / 2f)) }
        rule.waitForIdle()
        rule.onNodeWithTag("kfmenu:scale").assertExists()
        rule.onNodeWithTag("kfease:easy").performClick()
        rule.waitForIdle()
        assertEquals("ease scale 400 easy", fake.log.last())
        rule.onNodeWithTag("kfmenu:scale").assertDoesNotExist()
        // tap the lane = move the playhead there
        rule.onNodeWithTag("lane:scale").performTouchInput { click(Offset(width * 7f / 8f, height / 2f)) }
        assertEquals(7f, st.playhead, 0.1f)
    }

    @Test
    fun valueGraphHandlesAndPoints() {
        val fake = FakeProps().apply {
            p = p.copy(clipStart = 0f, clipEnd = 8f, kf = mapOf("scale" to listOf(
                UiKf(1f, 100L, "Bezier", listOf(50.0)), UiKf(4f, 400L, "AutoBezier", listOf(100.0)), UiKf(6f, 600L, "Hold", listOf(100.0)),
            )))
        }
        val st = state(fake).apply { playhead = 2f }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { PropertiesPanel(st) { } } } }
        rule.onNodeWithTag("kfgraph:scale").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("kfgraphview:scale").performScrollTo()
        rule.onRoot().captureRoboImage("$dir/31b_vardegraf.png")
        // the geometry the graph uses: values 50..100 with 15 % air, 10 px padding
        val (lo, hi) = graphRange(fake.p.kf.getValue("scale"))
        fun yOf(v: Double, h: Float) = (10f + (1 - (v - lo) / (hi - lo)) * (h - 20f)).toFloat()
        // the out handle of the keyframe at 1 s sits a third of the way to 4 s (x = 2/8); drag it to 3/8 = influence 2/3
        rule.onNodeWithTag("kfgraphview:scale").performTouchInput {
            val y = yOf(50.0, height.toFloat())
            down(Offset(width * 2f / 8f, y))
            for (i in 1..10) moveTo(Offset(width * (2f + i / 10f) / 8f, y))
            up()
        }
        rule.waitForIdle()
        val infl = fake.log.last()
        assertTrue(infl, infl.startsWith("infl scale 100 null "))
        assertEquals(2.0 / 3.0, infl.substringAfterLast(' ').toDouble(), 0.02)
        // the point at 4 s dragged to the top = the top of the range
        rule.onNodeWithTag("kfgraphview:scale").performTouchInput {
            val x = width * 4f / 8f
            val y0 = yOf(100.0, height.toFloat())
            down(Offset(x, y0))
            for (i in 1..10) moveTo(Offset(x, y0 + (y0 - 10f) * -i / 10f))
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/31c_graf_drag.png")
        rule.onNodeWithTag("kfgraphview:scale").performTouchInput { up() }
        rule.waitForIdle()
        val v = fake.log.last()
        assertTrue(v, v.startsWith("value scale 400 ["))
        assertEquals(hi, v.substringAfter('[').substringBefore(']').toDouble(), 0.5)
        // the curve between the keyframes is the engine's
        val ks = fake.p.kf.getValue("scale")
        val mid = ks.graphValueAt(2.5f, 0)!!
        assertEquals(50.0 + 50.0 * se.gendut.klipp.engine.ease("Bezier", 1.0 / 3, "AutoBezier", 1.0 / 3, 0.5), mid, 1e-9)
    }

    @Test
    fun linearKeyframesHaveHandlesThatEase() {
        val fake = FakeProps().apply {
            p = p.copy(clipStart = 0f, clipEnd = 8f, kf = mapOf("scale" to listOf(
                UiKf(1f, 100L, "Linear", listOf(50.0)), UiKf(4f, 400L, "Linear", listOf(100.0)), UiKf(6f, 600L, "Hold", listOf(100.0)),
            )))
        }
        val st = state(fake).apply { playhead = 2f }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { PropertiesPanel(st) { } } } }
        rule.onNodeWithTag("kfgraph:scale").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("kfgraphview:scale").performScrollTo()
        rule.onRoot().captureRoboImage("$dir/31d_linjara_handtag.png")
        val (lo, hi) = graphRange(fake.p.kf.getValue("scale"))
        fun yOf(v: Double, h: Float) = (10f + (1 - (v - lo) / (hi - lo)) * (h - 20f)).toFloat()
        // the linear keyframe at 1 s: its (hollow) out handle a third of the way to 4 s; dragging it = ease out
        rule.onNodeWithTag("kfgraphview:scale").performTouchInput {
            val y = yOf(50.0, height.toFloat())
            down(Offset(width * 2f / 8f, y))
            for (i in 1..10) moveTo(Offset(width * (2f + i / 10f) / 8f, y))
            up()
        }
        rule.waitForIdle()
        val out = fake.log.last()
        assertTrue(out, out.startsWith("infl scale 100 null ") && out.endsWith(" EaseOut"))
        // the keyframe at 4 s: its in handle a third back toward 1 s (x = 3/8) → ease in
        rule.onNodeWithTag("kfgraphview:scale").performTouchInput {
            val y = yOf(100.0, height.toFloat())
            down(Offset(width * 3f / 8f, y))
            for (i in 1..10) moveTo(Offset(width * (3f - 0.5f * i / 10f) / 8f, y))
            up()
        }
        rule.waitForIdle()
        val inn = fake.log.last()
        assertTrue(inn, inn.startsWith("infl scale 400 ") && inn.endsWith(" null EaseIn"))
        // the hold keyframe at 6 s has no outgoing handle (a step has no curve) – nothing beyond it to drag
        assertEquals(2, fake.log.count { it.startsWith("infl") })
    }
}
