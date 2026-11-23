package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.GradeNode

/**
 * The color page in simple mode: the clips in a strip and, for the clip, one calm column – a look (the LUT browser and
 * its strength), four adjustments (exposure, contrast, warmth, saturation) and log footage (OpenDRT). Each writes the
 * clip's grade exactly as the advanced page's control does (see [SimpleGrade]): the look and the adjustments in the
 * clip's first node, OpenDRT in a node of its own at the end. A grade made with more than that is not touched: the
 * page says so and offers the badge's choices (on/off, remove, open in advanced mode) instead of the controls.
 * [timelineLook]: the project has a timeline look (advanced mode's) – said under the tools.
 */
@Composable
fun SimpleColorPage(
    state: EditorState,
    gs: GradeState,
    clips: List<ColorClip>,
    timelineLook: Boolean = false,
    onImportLut: (((String) -> Unit) -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    val current = remember(clips) { derivedStateOf { currentColorClip(clips, state) } }.value
    Column(Modifier.fillMaxSize().testTag("simpleColor")) {
        ClipStrip(clips, current?.id, gs) { c ->
            haptics.tick()
            state.selected = c.id
            state.playing = false
            if (state.playhead < c.start || state.playhead >= c.end) state.playhead = (c.start + c.end) / 2
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(K.Surface).padding(top = 10.dp)) {
            if (current == null) { NoClip(); return@Box }
            Column(Modifier.fillMaxSize().verticalScroll(remember(current.id) { androidx.compose.foundation.ScrollState(0) }).padding(bottom = LocalBarSpace.current + 20.dp).testTag("colorScroll")) {
                // (decided on the stored grade: a drag's live values never switch the page to the advanced card)
                if (!SimpleGrade.fits(gs.grades[current.id])) AdvancedColorCard(state, gs, current)
                else SimpleColorTools(state, gs, current, onImportLut)
                if (timelineLook) Text(stringResource(R.string.simple_color_timeline_look), color = K.OnSurfaceVar, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp).testTag("simpleColor:timelineLook"))
            }
        }
    }
}

/** A clip graded with advanced tools: what it is and the badge's choices, no controls (they would change advanced work). */
@Composable
private fun AdvancedColorCard(state: EditorState, gs: GradeState, clip: ColorClip) {
    val haptics = rememberHaptics()
    val act = remember(state, gs) { BadgeActions(state, gs) }
    val on = SimpleGrade.allOn(gs.of(clip.id))
    val badge = Badge(BadgeKind.COLOR, on = on, canToggle = on != null)
    PanelCard(Modifier.padding(top = 4.dp).testTag("simpleColor:advanced"), title = stringResource(R.string.simple_badge_color)) {
        Text(stringResource(R.string.simple_reason_color), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
        if (on == null) Text(stringResource(R.string.simple_badge_mixed), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (on != null) PillButton(stringResource(if (on) R.string.simple_badge_turn_off else R.string.simple_badge_turn_on), { haptics.tick(); act.toggle(clip.id, badge) },
                Modifier.testTag("simpleColor:toggle"), icon = Ic.Power, filled = false, on = !on)
            PillButton(stringResource(R.string.simple_badge_open), { haptics.press(); act.open(clip.id, badge) }, Modifier.testTag("simpleColor:open"), icon = Ic.Sliders)
            PillButton(stringResource(R.string.simple_badge_remove), { haptics.heavy(); act.remove(clip.id, badge) }, Modifier.testTag("simpleColor:remove"), icon = Ic.Reset, filled = false)
        }
    }
}

/** The look, the adjustments and log footage for a clip whose grade simple mode can show whole. */
@Composable
private fun SimpleColorTools(state: EditorState, gs: GradeState, clip: ColorClip, onImportLut: (((String) -> Unit) -> Unit)?) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val g = gs.shown(clip.id) ?: ClipGrade()
    val a = SimpleGrade.adjustNode(g) ?: return
    // the look: in the adjust node, or with log footage in its own node after OpenDRT
    val lk = SimpleGrade.lookNode(g) ?: a
    val log = SimpleGrade.drtNode(g)
    // the advanced page's own writers: a node changed (live while dragging, committed on release)
    fun live(n: GradeNode) = gs.liveNode(clip.id, n)
    fun commit(n: GradeNode) { gs.commitNode(clip.id, n); scope.launch { gs.endLive() } }
    // a LUT or OpenDRT is read (and baked, 33³ points) on a background thread before it is committed – the latest only
    var pending by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun baked(g2: ClipGrade, then: () -> Unit) {
        pending?.cancel()
        pending = scope.launch { withContext(Dispatchers.Default) { g2.nodes.forEach { it.params() } }; then() }
    }
    fun setBaked(g2: ClipGrade) = baked(g2) { gs.set(clip.id, g2) }

    PanelCard(Modifier.padding(top = 4.dp), title = stringResource(R.string.simple_color_look)) {
        LutBrowser(
            selected = lk.lut,
            onPick = { name -> haptics.tick(); setBaked(SimpleGrade.withLook(gs.of(clip.id), name)) },
            preview = rememberLutPreview(state, gs, clip, lk.id, true),
            onImport = onImportLut?.let { imp -> { imp { name -> setBaked(SimpleGrade.withLook(gs.of(clip.id), name, 1f)) } } },
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (lk.lut != null) PropSlider(stringResource(R.string.colortools_lut_strength), lk.lutMix, 0f, 1f, 1f, { "%.0f %%".format(it * 100) }, "simple:lutMix", lane = false,
            // (with OpenDRT in the node the LUT is baked together with it – rebuilt when the finger lifts)
            onLive = { if (lk.drt == null) live(lk.copy(lutMix = it)) }) { setBaked(SimpleGrade.withLook(gs.of(clip.id), lk.lut, it)) }
        if (log != null && lk.lut != null) Text(stringResource(R.string.simple_color_log_look_note), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
    }
    PanelCard(Modifier.padding(top = 10.dp), title = stringResource(R.string.simple_color_adjust)) {
        // exposure = the gain wheel's master (the advanced Wheels tab's "Gain" luminance), the rest as on its Adjust tab
        PropSlider(stringResource(R.string.simple_color_exposure), a.gain.master, -1f, 1f, 0f, { "%+.2f".format(it) }, "simple:exposure", lane = false,
            onLive = { live(a.copy(gain = a.gain.copy(master = it))) }) { commit(a.copy(gain = a.gain.copy(master = it))) }
        PropSlider(stringResource(R.string.colorpage_contrast), a.contrast, 0f, 2f, 1f, { "%.2f".format(it) }, "simple:contrast", lane = false,
            onLive = { live(a.copy(contrast = it)) }) { commit(a.copy(contrast = it)) }
        PropSlider(stringResource(R.string.simple_color_warmth), a.temp, -1f, 1f, 0f, { "%+.0f".format(it * 100) }, "simple:warmth", lane = false,
            onLive = { live(a.copy(temp = it)) }) { commit(a.copy(temp = it)) }
        PropSlider(stringResource(R.string.colorpage_saturation), a.sat, 0f, 2f, 1f, { "%.0f".format(it * 50) }, "simple:sat", lane = false,
            onLive = { live(a.copy(sat = it)) }) { commit(a.copy(sat = it)) }
    }
    // log footage: OpenDRT from what the camera says it shot in (CameraLog), or what is picked here
    val hint = se.gendut.klipp.engine.CameraLog.of(clip.uri)
    PanelCard(Modifier.padding(top = 10.dp)) {
        SwitchLine(stringResource(R.string.simple_color_log), hint?.let { stringResource(R.string.simple_color_log_detected, it.label) } ?: stringResource(R.string.simple_color_log_hint),
            log != null, "simple:log") { on -> haptics.tick(); setBaked(SimpleGrade.withLog(gs.of(clip.id), on, hint?.key)) }
        val d = log?.drt
        if (d != null) {
            Text(stringResource(R.string.simple_color_log_shot_in), color = K.OnSurfaceVar, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, top = 4.dp))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (inp in se.gendut.klipp.engine.OpenDrt.Input.entries) ToggleChip(inp.label, d.input == inp.key, {
                    if (d.input != inp.key) setBaked(SimpleGrade.withInput(gs.of(clip.id), inp.key))
                }, Modifier.testTag("simple:logIn:${inp.key}"))
            }
        }
    }
}
