package se.gendut.klipp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10

/** Color correction – values −1..1, Animatable so presets can spring in. */
class ColorGrade {
    val exposure = Animatable(0f)
    val contrast = Animatable(0f)
    val temp = Animatable(0f)
    val tint = Animatable(0f)
    val sat = Animatable(0f)
    val all get() = listOf(exposure, contrast, temp, tint, sat)
    var lutName by mutableStateOf<String?>(null)
    val lutMix = Animatable(1f)
    /** Tetrahedral (high quality) or trilinear interpolation of the LUT. */
    var tetra by mutableStateOf(true)
}

class AudioMix {
    val gains = List(4) { Animatable(0.75f) } // 0.75 = 0 dB
    val levels = List(4) { Animatable(0f) }
    /** The fader is held by a finger right now – automation must not yank it then. */
    val dragging = BooleanArray(4)
    val mute = mutableStateListOf(false, false, false, false)
    val solo = mutableStateListOf(false, false, false, false)
    var role by mutableIntStateOf(0)
    /** Called when a track has finished changing (fader released, mute/solo) – index 0..2 = A1..A3, 3 = mix. */
    var onCommit: (Int) -> Unit = {}
    /** Mix normalization (dB), set by the loudness measurement; applied on top of the master fader. */
    var normDb by mutableFloatStateOf(0f)
    /**
     * Fader 0..1 where 0.75 = 0 dB. The master fader (3) has a square law (reaches +5 dB). (Where the fader is going:
     * it springs after the finger, and a release must save where it was let go, not where the spring had got to.)
     */
    fun db(i: Int): Double {
        val g = gains[i].targetValue
        if (g < 0.001f) return -60.0
        return (if (i == 3) 40.0 else 20.0) * log10((g / 0.75f).toDouble())
    }
}

fun dbToFader(db: Double): Float = if (db <= -59.9) 0f else (0.75 * Math.pow(10.0, db / 20.0)).toFloat().coerceIn(0f, 1f)

enum class TitleStyle(@StringRes val labelRes: Int) {
    SLIDE(R.string.panels_style_slide), BOUNCE(R.string.panels_style_bounce), SCALE(R.string.panels_style_scale);

    val label: String get() = Str.t(labelRes)
}

class TitleState {
    var name by mutableStateOf("Anna Berg")
    var role by mutableStateOf(Str.t(R.string.panels_title_role_default))
    var style by mutableStateOf(TitleStyle.BOUNCE)
    var previewKey by mutableIntStateOf(0)
    var template by mutableIntStateOf(0)
}

fun gainToDb(g: Float, squared: Boolean = false): String {
    if (g <= 0.001f) return "−∞"
    val db = (if (squared) 40f else 20f) * log10(g / 0.75f)
    return (if (db >= 0) "+" else "−") + "%.1f".format(abs(db))
}

@Composable
fun PanelTitle(text: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = K.OnSurface, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

/**
 * Small "chip" button with a springy press and shape change when selected. What is chosen or switched on has the
 * accent container, like every choice in the app; an action chip is never selected and stays neutral.
 * [tight] = the chips share a row's width between them: less air beside the text.
 */
@Composable
fun SpringChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedColor: Color = K.PrimaryContainer,
    selectedText: Color = K.PrimarySoft,
    tight: Boolean = false,
) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val radius by animateDpAsState(if (selected) 22.dp else 10.dp, androidx.compose.animation.core.spring(0.5f, 500f), label = "r")
    val bg by animateColorAsState(if (selected) selectedColor else Color.Transparent, label = "bg")
    Box(
        modifier
            .pressPop(src, 0.9f)
            .height(44.dp)
            .clip(RoundedCornerShape(radius))
            .background(bg)
            .border(1.dp, if (selected) bg else K.OutlineVar, RoundedCornerShape(radius))
            .clickable(interactionSource = src, indication = null) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = if (tight) 4.dp else 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) selectedText else K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/**
 * A slider of the timeline look: the same slider as every other property ([PropSlider]); the value is the look's own
 * (an [Animatable], so a preset can spring in), and the app saves it and makes the undo step once it has rested.
 */
@Composable
private fun LookSlider(
    label: String, tag: String, value: Animatable<Float, *>, scope: CoroutineScope, bipolar: Boolean = true, default: Float = 0f,
    format: (Float) -> String = { v -> (if (v > 0.005f) "+" else if (v < -0.005f) "−" else "") + "%.0f".format(abs(v) * 100) },
) {
    PropSlider(label, value.value, if (bipolar) -1f else 0f, 1f, default, format, tag, onLive = { v -> scope.launch { value.snapTo(v) } }) { v ->
        scope.launch { value.snapTo(v) }
    }
}

@Composable
fun ColorPanel(grade: ColorGrade, onImportLut: (() -> Unit)? = null, scroll: Boolean = true) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var lut by remember { mutableIntStateOf(0) }
    fun preset(vals: List<Float>) {
        haptics.confirm()
        grade.all.forEachIndexed { i, a ->
            scope.launch {
                delay(i * 45L)
                a.animateTo(vals[i], Springs.pop())
            }
        }
    }
    // inside the color page (which already scrolls) the panel must not scroll itself – nested scrolling crashes
    Column(if (scroll) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth()) {
        val reset = @Composable { SpringChip(stringResource(R.string.panels_reset), false, { lut = 0; preset(listOf(0f, 0f, 0f, 0f, 0f)) }) }
        if (scroll) PanelTitle(stringResource(R.string.panels_color)) { reset() }
        // inside the color page the top bar names the page: here only what this part is – the timeline's look
        else Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.colortools_look), color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            reset()
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val luts = listOf(R.string.panels_preset_none, R.string.panels_preset_warm, R.string.panels_preset_cool, R.string.panels_preset_film, R.string.panels_preset_bw)
            val vals = listOf(
                listOf(0f, 0f, 0f, 0f, 0f),
                listOf(0.1f, 0.15f, 0.55f, 0.1f, 0.15f),
                listOf(0f, 0.2f, -0.6f, -0.05f, -0.1f),
                listOf(-0.15f, 0.45f, 0.2f, -0.15f, -0.35f),
                listOf(0f, 0.35f, 0f, 0f, -1f),
            )
            luts.forEachIndexed { i, l -> SpringChip(stringResource(l), lut == i, { lut = i; preset(vals[i]) }) }
        }
        Spacer(Modifier.height(8.dp))
        SectionLabel("LUT")
        Row(
            Modifier.sideScroll().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SpringChip(stringResource(R.string.panels_lut_none), grade.lutName == null, { grade.lutName = null })
            for (n in se.gendut.klipp.engine.LutStore.names) {
                SpringChip(Str.name(n), grade.lutName == n, { grade.lutName = n; scope.launch { grade.lutMix.snapTo(0f); grade.lutMix.animateTo(1f, Springs.settle()) } })
            }
            if (onImportLut != null) SpringChip(stringResource(R.string.panels_lut_import), false, onImportLut)
        }
        if (grade.lutName != null) {
            LookSlider(stringResource(R.string.panels_lut_strength), "lookLut", grade.lutMix, scope, bipolar = false, default = 1f, format = { "%.0f %%".format(it * 100) })
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SpringChip(stringResource(R.string.panels_lut_tetra), grade.tetra, { grade.tetra = true })
                SpringChip(stringResource(R.string.panels_lut_trilinear), !grade.tetra, { grade.tetra = false })
                Text(stringResource(if (grade.tetra) R.string.panels_lut_tetra_desc else R.string.panels_lut_trilinear_desc), color = K.OnSurfaceVar, fontSize = 12.sp)
            }
        }
        SectionLabel(stringResource(R.string.panels_tone))
        LookSlider(stringResource(R.string.panels_exposure), "lookExposure", grade.exposure, scope)
        LookSlider(stringResource(R.string.panels_contrast), "lookContrast", grade.contrast, scope)
        SectionLabel(stringResource(R.string.panels_white_balance))
        LookSlider(stringResource(R.string.panels_temperature), "lookTemp", grade.temp, scope)
        LookSlider(stringResource(R.string.panels_tint), "lookTint", grade.tint, scope)
        SectionLabel(stringResource(R.string.panels_creative))
        LookSlider(stringResource(R.string.panels_saturation), "lookSat", grade.sat, scope)
        // (on its own the page ends clear of the bar; inside the color page that page makes the room)
        if (scroll) Spacer(Modifier.height(LocalBarSpace.current + 36.dp))
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = LocalSliderInset.current, top = 12.dp, bottom = 2.dp)
    )
}

/** The mixer's hooks into the project (null in prototype mode). */
class AudioTools(
    /** Tracks A1–A3 (for automation). */
    val tracks: () -> List<se.gendut.klipp.engine.TrackSnap>,
    /** Keyframe on/off at the playhead for track i (value = the fader's). */
    val toggleKf: (Int, Double, Double) -> Unit,
    val clearKf: (Int) -> Unit,
    /** Measure the mix loudness (LUFS, true peak, LRA). */
    val measure: suspend () -> Result<Triple<Double, Double, Double>>,
    val voiceOver: VoiceOverUi,
    /** Automatic ducking: the music on track i is lowered x dB under the speech on the other tracks (number of ducked clips). */
    val duck: suspend (Int, Double) -> Result<Int> = { _, _ -> Result.success(0) },
    /** Remove the ducking on track i. */
    val unduck: suspend (Int) -> Result<Int> = { Result.success(0) },
    /** Place a sound effect (file, name, seconds) at the playhead (SoundFxSheet). */
    val addSound: ((java.io.File, String, Double) -> Unit)? = null,
)

/**
 * Automatic ducking: pick the music track and how much it is lowered; it is lowered wherever something is heard on the
 * other tracks (speech, voice-over), with smooth transitions. The volume keyframes can be adjusted in the clip's properties.
 */
@Composable
private fun DuckingCard(tools: AudioTools, /** The result has been written last in the card: bring it into view. */ reveal: () -> Unit) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var track by remember { mutableIntStateOf(1) }
    var reduce by remember { mutableStateOf(-12.0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 18.dp).fillMaxWidth()) {
        Text(stringResource(R.string.panels_duck_desc), color = K.OnSurfaceVar, fontSize = 12.sp)
        // the choices share the row (a row that scrolled sideways hid its last choice on a narrow phone)
        Text(stringResource(R.string.panels_duck_music_on), color = K.OnSurfaceVar, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0..2) SpringChip("A${i + 1}", track == i, { track = i }, Modifier.weight(1f).testTag("duck:track:$i"))
        }
        Text(stringResource(R.string.panels_duck_lower), color = K.OnSurfaceVar, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (db in listOf(-6.0, -12.0, -18.0)) SpringChip("%.0f dB".format(db).replace('-', '−'), reduce == db, { reduce = db }, Modifier.weight(1f).testTag("duck:db:${db.toInt()}"))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SpringChip(stringResource(if (busy) R.string.panels_duck_listening else R.string.panels_duck_go), false, {
                if (busy) return@SpringChip
                busy = true; message = null
                scope.launch {
                    tools.duck(track, reduce).onSuccess { n ->
                        haptics.confirm()
                        message = if (n == 0) Str.t(R.string.panels_duck_no_speech, track + 1) else Str.p(R.plurals.panels_duck_done, n, track + 1, n)
                    }.onFailure { message = it.message }
                    busy = false
                    reveal()
                }
            }, Modifier.weight(1f).testTag("duck:go"))
            SpringChip(stringResource(R.string.panels_duck_remove), false, {
                if (busy) return@SpringChip
                busy = true; message = null
                scope.launch {
                    tools.unduck(track).onSuccess { n -> haptics.tick(); message = if (n == 0) Str.t(R.string.panels_duck_none, track + 1) else Str.p(R.plurals.panels_duck_removed, n, n) }
                        .onFailure { message = it.message }
                    busy = false
                    reveal()
                }
            }, Modifier.weight(1f).testTag("duck:remove"))
        }
        message?.let { Text(it, color = K.OnSurface, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).testTag("duck:msg")) }
    }
}

/** Voice-over: state and actions (recording is handled by the app). */
class VoiceOverUi {
    /** 0 = off, 3..1 = countdown, -1 = recording */
    var phase by mutableIntStateOf(0)
    var track by mutableIntStateOf(1)
    var startedAt by mutableStateOf(0L)
    var onStart: () -> Unit = {}
    var onStop: () -> Unit = {}
}

/**
 * The normalization (dB) that takes the mix from [measured] LUFS to [target], with the master fader [faderDb] kept,
 * but never so that the true peak ([truePeak], before master) goes above −1 dBTP.
 */
fun normalizeDb(measured: Double, truePeak: Double, faderDb: Double, target: Double): Double {
    val want = target - measured - faderDb
    val room = -1.0 - truePeak - faderDb
    return minOf(want, room).coerceIn(-40.0, 40.0)
}

/** Targets for loudness normalization. */
private val LOUDNESS_TARGETS = listOf(-14.0 to R.string.panels_target_youtube, -16.0 to R.string.panels_target_podcast, -23.0 to R.string.panels_target_tv)

/**
 * The sound page's tabs (the page shows them itself, or the app's dock does): what is done to the sound that is
 * already there. New sound (effects, speech, a voice-over) is added from the edit page's dock.
 */
@Composable
internal fun audioTabs(mix: AudioMix, tools: Boolean): List<ToolTab> = listOf(
    ToolTab(stringResource(R.string.panels_tab_mixer), Ic.Sliders, "audioTab:mixer"),
    ToolTab(stringResource(R.string.panels_tab_loudness), Ic.Waveform, "audioTab:loudness", mix.normDb != 0f),
) + if (!tools) emptyList() else listOf(
    ToolTab(stringResource(R.string.panels_tab_ducking), Ic.VolLine, "audioTab:ducking"),
)

@Composable
fun AudioPanel(mix: AudioMix, state: EditorState, tools: AudioTools? = null, dockTabs: Boolean = false) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    // what the app's bar covers of the page's bottom (nothing in landscape, where it sits under the picture)
    val bar = LocalBarSpace.current
    // level meters: real levels from playback (peak level in dBFS, −60 … 0 → 0 … 1)
    LaunchedEffect(state.playing) {
        if (!state.playing) {
            mix.levels.forEach { a -> launch { a.animateTo(0f, androidx.compose.animation.core.spring(0.8f, 200f)) } }
            return@LaunchedEffect
        }
        while (isActive && state.playing) {
            for (i in 0..3) {
                val pk = if (i == 3) se.gendut.klipp.engine.Meters.master().let { maxOf(it.first, it.second) } else se.gendut.klipp.engine.Meters.read(i)
                val db = if (pk <= 1e-6f) -60f else (20f * log10(pk)).coerceIn(-60f, 6f)
                val target = ((db + 60f) / 60f).coerceIn(0f, 1.1f)
                val a = mix.levels[i]
                // fast rise, slower fall (like a real peak meter)
                if (target >= a.value) a.snapTo(target) else a.snapTo((a.value - 0.025f).coerceAtLeast(target))
            }
            delay(33)
        }
    }
    val tracks = tools?.tracks?.invoke().orEmpty()
    // automation: the fader shows the track's value at the playhead (read in snapshotFlow, not in composition)
    LaunchedEffect(tracks) {
        snapshotFlow { state.playhead }.collect { ph ->
            tracks.take(3).forEachIndexed { i, t ->
                if (t.hasAutomation && !mix.dragging[i]) mix.gains[i].snapTo(dbToFader(t.volumeAt(ph.toDouble())))
            }
        }
    }
    var measured by remember { mutableStateOf<Triple<Double, Double, Double>?>(null) }
    var measuring by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(-14.0) }
    var message by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    // a result is written last in its card: the page goes to its end, where the card stands clear of the dock
    val reveal: () -> Unit = { scope.launch { androidx.compose.runtime.withFrameNanos { }; scroll.animateScrollTo(scroll.maxValue) } }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val pageHeight = maxHeight
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        // the tab lives in the editor state: in the app the dock at the bottom shows the tabs (dockTabs) and the top
        // bar names the page; on its own the page does both itself
        var tab by state::audioTab
        val tabs = audioTabs(mix, tools != null)
        LaunchedEffect(state.openTab) { tabs.indexOfFirst { it.tag == state.openTab }.takeIf { it >= 0 }?.let { tab = it; state.openTab = null } }
        if (!dockTabs) {
            PanelTitle(stringResource(R.string.panels_audio))
            ToolTabs(tabs, tab.coerceIn(0, tabs.lastIndex), { tab = it }, Modifier.padding(horizontal = 12.dp))
        }
        Spacer(Modifier.height(10.dp))
        when (tab.coerceIn(0, tabs.lastIndex)) {
            0 -> {
                // the strips take the room above the dock, so the values and M/S under the faders show without scrolling
                val above = if (dockTabs) 10.dp else 122.dp
                val stripHeight = (pageHeight - above - (if (bar > 0.dp) bar + 8.dp else 12.dp)).coerceIn(216.dp, 280.dp)
                Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(stripHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("A1", "A2", "A3", "Mix").forEachIndexed { i, n ->
                        ChannelStrip(n, i, mix, scope, Modifier.weight(1f).fillMaxHeight(), auto = tracks.getOrNull(i)?.takeIf { i < 3 }?.volKf?.size ?: 0)
                    }
                }
                if (tools != null) PanelCard(Modifier.padding(top = 10.dp), title = stringResource(R.string.panels_volume_automation)) {
                    Text(stringResource(R.string.panels_automation_hint),
                        color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
                    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in 0..2) {
                            val n = tracks.getOrNull(i)?.volKf?.size ?: 0
                            SpringChip("◆ A${i + 1}" + if (n > 0) " ($n)" else "", n > 0, {
                                haptics.tick(); tools.toggleKf(i, state.playhead.toDouble(), mix.db(i))
                            }, Modifier.testTag("kf:A${i + 1}"))
                            if (n > 0) SpringChip(stringResource(R.string.panels_clear_track, i + 1), false, { haptics.heavy(); tools.clearKf(i) }, Modifier.testTag("kfClear:A${i + 1}"))
                        }
                    }
                }
            }
            1 -> PanelCard {
                // loudness: measure the whole mix and normalize master toward a target
                Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.panels_integrated_loudness), color = K.OnSurfaceVar, fontSize = 12.sp)
                            val m = measured
                            val withMaster = m?.let { it.first + mix.db(3) + mix.normDb }
                            Text(
                                when { measuring -> stringResource(R.string.panels_measuring_value); withMaster == null -> "– LUFS"; else -> "%.1f LUFS".format(withMaster).replace('-', '−') },
                                color = if (withMaster != null && abs(withMaster - target) <= 1.0) Color(0xFF5BC27A) else K.OnSurface,
                                fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.testTag("lufs"),
                            )
                            if (m != null) Text(stringResource(R.string.panels_peak_lra, m.second + mix.db(3) + mix.normDb, m.third).replace('-', '−'), color = K.OnSurfaceVar, fontSize = 11.sp)
                        }
                        if (tools != null) SpringChip(stringResource(if (measuring) R.string.panels_measuring else R.string.panels_measure), false, {
                            if (measuring) return@SpringChip
                            measuring = true; message = null
                            scope.launch {
                                tools.measure().onSuccess { measured = it; haptics.confirm() }.onFailure { message = it.message; reveal() }
                                measuring = false
                            }
                        }, Modifier.testTag("measure"))
                    }
                    Text(stringResource(R.string.panels_target), color = K.OnSurfaceVar, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    // the targets wrap (a row that scrolled sideways cut the last one on a narrow phone)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((t, label) in LOUDNESS_TARGETS) SpringChip("%.0f · %s".format(t, stringResource(label)).replace('-', '−'), target == t, { target = t }, Modifier.testTag("target:${t.toInt()}"))
                    }
                    if (tools != null) androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SpringChip(stringResource(R.string.panels_normalize), false, {
                            if (measuring) return@SpringChip
                            measuring = true; message = null
                            scope.launch {
                                tools.measure().onSuccess { m ->
                                    measured = m
                                    val n = normalizeDb(m.first, m.second, mix.db(3), target)
                                    mix.normDb = n.toFloat()
                                    mix.onCommit(3)
                                    haptics.confirm()
                                    val final = m.first + mix.db(3) + n
                                    message = if (final < target - 0.3) Str.t(R.string.panels_normalize_limited, final).replace('-', '−')
                                    else Str.t(R.string.panels_normalize_done, n, final).replace('-', '−')
                                }.onFailure { message = it.message }
                                measuring = false
                                reveal()
                            }
                        }, Modifier.testTag("normalize"))
                        // the normalization stays on the mix until it is taken away (it is not the fader)
                        if (abs(mix.normDb) > 0.05f) SpringChip(stringResource(R.string.panels_normalize_remove), false, {
                            mix.normDb = 0f
                            mix.onCommit(3)
                            message = null
                        }, Modifier.testTag("normalize:remove"))
                    }
                    message?.let { Text(it, color = K.OnSurface, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).testTag("loudness:msg")) }
                }
            }
            else -> if (tools != null) PanelCard(title = stringResource(R.string.panels_duck_title)) { DuckingCard(tools, reveal) }
        }
        Spacer(Modifier.height(bar + 36.dp))
    }
    }
}

/** A voice-over: the track it goes on and the record button (with its countdown), then stop. */
@Composable
internal fun VoiceOverCard(vo: VoiceOverUi) {
    val haptics = rememberHaptics()
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) SpringChip("A${i + 1}", vo.track == i, { if (vo.phase == 0) vo.track = i })
        when {
            vo.phase == 0 -> SpringChip(stringResource(R.string.panels_record), false, { haptics.confirm(); vo.onStart() }, Modifier.testTag("voStart"), selectedColor = K.Error)
            vo.phase > 0 -> Text("${vo.phase} …", color = K.Error, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp))
            else -> SpringChip(stringResource(R.string.panels_stop), true, { haptics.confirm(); vo.onStop() }, Modifier.testTag("voStop"), selectedColor = K.Error, selectedText = Color.White)
        }
    }
    Text(
        stringResource(if (vo.phase < 0) R.string.panels_recording_hint else R.string.panels_record_hint),
        color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** Text read aloud by the phone's own voice, placed at the playhead. */
@Composable
internal fun TextToSpeechCard(add: (java.io.File, String, Double) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = rememberHaptics()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    androidx.compose.material3.OutlinedTextField(text, { text = it; failed = false }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("ttsText"),
        placeholder = { Text(stringResource(R.string.tts_hint)) }, maxLines = 4)
    if (failed) Text(stringResource(R.string.tts_failed), color = K.Error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    PillButton(stringResource(if (busy) R.string.tts_busy else R.string.tts_add), {
        if (!busy && text.isNotBlank()) {
            haptics.confirm(); busy = true; failed = false
            scope.launch {
                val r = runCatching { se.gendut.klipp.engine.SpeechFile.make(context, text.trim()) }.getOrNull()
                if (r != null) { add(r.file, text.trim().take(40), r.seconds); text = "" } else failed = true
                busy = false
            }
        }
    }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("ttsAdd"))
}

@Composable
private fun ChannelStrip(name: String, i: Int, mix: AudioMix, scope: CoroutineScope, modifier: Modifier, auto: Int = 0) {
    val haptics = rememberHaptics()
    val g = mix.gains[i]
    var atUnity by remember { mutableStateOf(true) }
    val lastTap = remember { longArrayOf(0L) }
    val isMix = i == 3
    Column(
        // the mix is set apart by its tone only (the accent is for what is switched on)
        modifier.clip(RoundedCornerShape(20.dp)).background(if (isMix) K.SurfaceHigh else K.Surface).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(name, color = K.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(if (auto > 0) "AUTO" else " ", color = K.Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.weight(1f).width(56.dp).testTag("fader:$name").pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startY = down.position.y
                    val startV = g.value
                    down.consume()
                    mix.dragging[i] = true
                    var moved = false
                    try { while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        if (!moved && abs(c.position.y - startY) < viewConfiguration.touchSlop / 2) { c.consume(); continue }
                        moved = true
                        var v = (startV - (c.position.y - startY) / size.height).coerceIn(0f, 1f)
                        if (abs(v - 0.75f) < 0.035f) {
                            if (!atUnity) haptics.tick()
                            atUnity = true
                            v = 0.75f
                            scope.launch { g.animateTo(v, Springs.snap()) }
                        } else {
                            atUnity = false
                            scope.launch { g.animateTo(v, Springs.follow()) }
                        }
                        c.consume()
                    } } finally { mix.dragging[i] = false }
                    if (!moved) {
                        // a tap changes nothing; a double tap = back to 0 dB
                        if (lastTap[0] == 0L || down.uptimeMillis - lastTap[0] >= viewConfiguration.doubleTapTimeoutMillis) { lastTap[0] = down.uptimeMillis; return@awaitEachGesture }
                        lastTap[0] = 0L
                        haptics.confirm()
                        atUnity = true
                        // saved once it is there (the commit reads the fader)
                        scope.launch { g.animateTo(0.75f, Springs.pop()); mix.onCommit(i) }
                        return@awaitEachGesture
                    }
                    mix.onCommit(i)
                }
            }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val railX = size.width / 2 + 6.dp.toPx()
                val meterX = size.width / 2 - 12.dp.toPx()
                val w6 = 6.dp.toPx()
                drawRoundRect(K.SurfaceHighest, Offset(meterX, 0f), Size(w6, size.height), CornerRadius(w6 / 2))
                val lv = mix.levels[i].value.coerceIn(0f, 1f)
                val mh = size.height * lv
                // green up to −12 dBFS, yellow to −3, red above
                drawRoundRect(
                    when { lv > 0.95f -> K.Error; lv > 0.8f -> Color(0xFFE2C15B); else -> Color(0xFF5BC27A) },
                    Offset(meterX, size.height - mh), Size(w6, mh), CornerRadius(w6 / 2)
                )
                drawRoundRect(K.SurfaceHighest, Offset(railX - w6 / 2, 0f), Size(w6, size.height), CornerRadius(w6 / 2))
                val unityY = size.height * (1f - 0.75f)
                drawLine(K.Outline, Offset(railX - 12.dp.toPx(), unityY), Offset(railX + 12.dp.toPx(), unityY), 1.dp.toPx())
                val capY = size.height * (1f - g.value)
                val capW = 30.dp.toPx()
                val capH = 20.dp.toPx()
                drawRoundRect(K.OnSurface, Offset(railX - capW / 2, capY - capH / 2), Size(capW, capH), CornerRadius(8.dp.toPx()))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(if (isMix) gainToDb(g.value, squared = true) else gainToDb(g.value), color = K.OnSurfaceVar, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        if (!isMix) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleSquare("M", mix.mute[i]) { mix.mute[i] = !mix.mute[i]; mix.onCommit(i) }
                ToggleSquare("S", mix.solo[i]) { mix.solo[i] = !mix.solo[i]; mix.onCommit(i) }
            }
        } else Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
            // the normalization on top of the fader (set and removed under Loudness)
            if (abs(mix.normDb) > 0.05f) Text("norm %+.1f".format(mix.normDb).replace('-', '−'), color = K.Primary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.testTag("mix:norm"))
        }
    }
}

@Composable
private fun ToggleSquare(label: String, on: Boolean, width: Dp = 28.dp, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (on) K.PrimaryContainer else K.SurfaceHigh, label = "t")
    val radius by animateDpAsState(if (on) 14.dp else 8.dp, androidx.compose.animation.core.spring(0.45f, 600f), label = "tr")
    Box(
        Modifier.pressPop(src, 0.8f)
            .size(width, 28.dp).clip(RoundedCornerShape(radius)).background(bg)
            .clickable(interactionSource = src, indication = null) {
                haptics.tick()
                onClick()
            },
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (on) K.PrimarySoft else K.OnSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun TextPanel(title: TitleState) {
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PanelTitle(stringResource(R.string.panels_text_title))
        SectionLabel(stringResource(R.string.panels_templates))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.panels_template_lower_third, R.string.panels_template_chapter, R.string.panels_template_credits).forEachIndexed { i, t ->
                TemplateCard(stringResource(t), title.template == i, Modifier.weight(1f)) {
                    haptics.confirm()
                    title.template = i
                    title.previewKey++
                }
            }
        }
        SectionLabel(stringResource(R.string.panels_content))
        TextInput(stringResource(R.string.panels_name), title.name) { title.name = it; }
        TextInput(stringResource(R.string.panels_role), title.role) { title.role = it }
        SectionLabel(stringResource(R.string.panels_animation_in))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TitleStyle.entries.forEach { s ->
                SpringChip(stringResource(s.labelRes), title.style == s, { title.style = s; title.previewKey++ })
            }
            SpringChip(stringResource(R.string.panels_play), false, { title.previewKey++ })
        }
        Spacer(Modifier.height(LocalBarSpace.current + 36.dp))
    }
}

@Composable
private fun TemplateCard(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val radius by animateDpAsState(if (selected) 28.dp else 16.dp, androidx.compose.animation.core.spring(0.5f, 400f), label = "c")
    val shape: Shape = RoundedCornerShape(radius)
    Box(
        modifier.pressPop(src, 0.92f)
            .height(76.dp).clip(shape)
            .background(if (selected) K.PrimaryContainer else K.Surface)
            .border(1.5.dp, if (selected) K.PrimaryContainer else K.OutlineVar, shape)
            .clickable(interactionSource = src, indication = null) {
                onClick()
            }
            .padding(12.dp),
        contentAlignment = Alignment.BottomStart
    ) { Text(label, color = if (selected) K.PrimarySoft else K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

/**
 * A one-line text field with its label: beside it, or above it where the row is narrow ([stacked]). The field has the
 * dropdowns' look (a lighter tone with an outline), so it shows on a panel as well as in a dialog.
 */
@Composable
fun TextInput(
    label: String, value: String, modifier: Modifier = Modifier, stacked: Boolean = false,
    /** The field takes the keyboard when it appears, with the caret after the text (a dialog that asks for a name). */
    focus: Boolean = false,
    /** Digits only: the number keyboard. */
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    // the caret is kept here (the caller owns the text, and may filter what is typed)
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = field.copy(text = value, selection = TextRange(field.selection.end.coerceAtMost(value.length)))
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    val shape = RoundedCornerShape(12.dp)
    val input: @Composable (Modifier) -> Unit = { m ->
        BasicTextField(
            value = field,
            onValueChange = { field = it; if (it.text != value) onChange(it.text) },
            singleLine = true,
            textStyle = TextStyle(color = K.OnSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(K.Primary),
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = m.focusRequester(requester).onFocusChanged { focused = it.isFocused }
                .clip(shape).background(K.SurfaceHighest).border(1.dp, if (focused) K.Primary else K.OutlineVar, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
    if (stacked) Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
        Text(label, color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        input(Modifier.fillMaxWidth())
    } else Row(modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        input(Modifier.weight(1f))
    }
}
