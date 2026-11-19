package se.gendut.klipp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import kotlin.math.roundToInt
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.CaptionStyle
import se.gendut.klipp.engine.Fonts

/** Selected caption in the timeline (the dock's row): delete, edit, split at the playhead, new. Drag in the lane to move/trim. */
@Composable
internal fun CaptionDock(state: EditorState) {
    val c = state.selectedCaption?.let { id -> state.captions.firstOrNull { it.id == id } }
    // the tags ("cap:…") stay the same whatever the UI language
    DockItems(
        listOf(
            // (delete first, as in a clip's row)
            DockItem(Ic.Delete, stringResource(R.string.captionui_delete), "cap:Ta bort", tint = K.Error, strong = true) {
                val id = c?.id ?: return@DockItem
                state.selectedCaption = null; state.props?.deleteCaption(id)
            },
            DockItem(Ic.Edit, stringResource(R.string.captionui_edit), "cap:Redigera") { state.editingCaption = true },
            DockItem(Ic.Split, stringResource(R.string.captionui_split), "cap:Dela") {
                val cap = c ?: return@DockItem
                if (state.playhead <= cap.start + 0.04f || state.playhead >= cap.end - 0.04f) { state.onNotice?.invoke(Str.t(R.string.captionui_playhead_inside)); return@DockItem }
                state.props?.splitCaption(cap.id, state.playhead)
            },
            DockItem(Ic.Plus, stringResource(R.string.captionui_new), "cap:Ny") {
                state.props?.addCaption(state.playhead) { id -> state.selectedCaption = id; state.editingCaption = true }
            },
        ),
        Modifier.testTag("captionBar"),
    )
}

/** Edit the selected caption: its text and times (the track's look is in Add › Captions › Style). */
@Composable
fun CaptionInspector(state: EditorState) {
    val backend = state.props ?: return
    val haptics = rememberHaptics()
    val c = state.selectedCaption?.let { id -> state.captions.firstOrNull { it.id == id } }
    // the caption's track: named in the header and in the style's scope (the style is the track's)
    val trackName = c?.let { cc -> backend.captionTracks.firstOrNull { it.id == cc.track }?.name } ?: stringResource(R.string.captionui_track_unnamed)
    Column(Modifier.fillMaxSize().background(K.Bg).testTag("captionInspector")) {
        // the header (with Done) stays in place; what is under it scrolls
        // (the track in the header only when there are several – which one the caption is on matters then)
        SheetHeader(if (c != null && backend.captionTracks.size > 1) stringResource(R.string.captionui_caption_on_track, trackName) else stringResource(R.string.captionui_caption)) { haptics.tick(); state.editingCaption = false }
        if (c == null) {
            Text(stringResource(R.string.captionui_select_caption), color = K.OnSurfaceVar, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            CaptionTextField(c, backend)
            CaptionTimes(state, c, backend)
            // (the look is the track's: it is set in Add › Captions › Style, not here)
            // (the page ends clear of undo and redo, which lie over its corner)
            Spacer(Modifier.height(LocalBarSpace.current + 36.dp))
        }
    }
}

/**
 * The caption's start and end, and buttons that set either at the playhead – the timeline's drag without the precision
 * (same engine command, one undo step each; the same limits: at least a frame, never into a neighbor). In a scope of its
 * own: it follows the playhead without recomposing the editor.
 */
@Composable
private fun CaptionTimes(state: EditorState, c: UiCaption, backend: PropsBackend) {
    val haptics = rememberHaptics()
    Text(
        stringResource(R.string.captionui_time, formatTc(c.start), formatTc(c.end)),
        color = K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("captionTimes"),
    )
    val start = captionEdgeTo(c, state.captions, state.playhead, inEdge = true, minLen = FRAME)
    val end = captionEdgeTo(c, state.captions, state.playhead, inEdge = false, minLen = FRAME)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillButton(stringResource(R.string.captionui_set_start), { haptics.confirm(); backend.trimCaption(c.id, true, start.dt) },
            Modifier.testTag("captionSetStart"), filled = false, enabled = start.ok)
        PillButton(stringResource(R.string.captionui_set_end), { haptics.confirm(); backend.trimCaption(c.id, false, end.dt) },
            Modifier.testTag("captionSetEnd"), filled = false, enabled = end.ok)
    }
    // why a button can't be used (not when the playhead is simply where the edge is)
    val why = listOfNotNull(start.problem, end.problem).filter { it != CaptionEdgeProblem.ALREADY_THERE }.distinct().map { p ->
        stringResource(when (p) {
            CaptionEdgeProblem.AFTER_END -> R.string.captionui_edge_after_end
            CaptionEdgeProblem.BEFORE_START -> R.string.captionui_edge_before_start
            CaptionEdgeProblem.TOO_SHORT -> R.string.captionui_edge_too_short
            CaptionEdgeProblem.INTO_PREVIOUS -> R.string.captionui_edge_into_previous
            CaptionEdgeProblem.INTO_NEXT -> R.string.captionui_edge_into_next
            CaptionEdgeProblem.ALREADY_THERE -> R.string.captionui_edge_already
        })
    }
    for (w in why) Text(w, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp).testTag("captionEdgeWhy"))
    Text(stringResource(R.string.captionui_drag_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

/**
 * A caption's text – the one editor for it (the caption editor over the timeline and the captions list both use it).
 * Saved by itself once typing pauses for a moment and when the editor closes; the whole editing session is one undo
 * step. An empty text is not saved (a caption is deleted with Delete): it stays in the field with a word why.
 */
@Composable
internal fun CaptionTextField(c: UiCaption, backend: PropsBackend, modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), tag: String = "captionText") {
    var text by remember(c.id) { mutableStateOf(c.text) }
    val pending = remember(c.id) { mutableStateOf<String?>(null) }
    // one editing session: the saves it makes become one undo step (see PropsBackend.setCaptionText)
    val session = remember(c.id) { Any() }
    LaunchedEffect(c.id, c.text) { if (pending.value == null && text != c.text) text = c.text }
    LaunchedEffect(pending.value) {
        val t = pending.value ?: return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        backend.setCaptionText(c.id, t, session)
        pending.value = null
    }
    DisposableEffect(c.id) {
        val id = c.id
        onDispose { pending.value?.let { backend.setCaptionText(id, it, session) } }
    }
    val blank = text.isBlank()
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = text,
            // what is saved: a text with something in it, different from what the caption has
            onValueChange = { text = it; pending.value = if (it == c.text || it.isBlank()) null else it },
            textStyle = TextStyle(color = K.OnSurface, fontSize = 16.sp), cursorBrush = SolidColor(K.Primary),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(K.SurfaceHigh).padding(14.dp).testTag(tag),
        )
        if (blank) Text(
            stringResource(R.string.captionui_blank_not_saved), color = K.OnSurfaceVar, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp).testTag("captionBlank"),
        )
    }
}

private val CAPTION_COLORS = listOf(0xFFFFFFFF, 0xFFFFE07A, 0xFFFFB77C, 0xFF9FE7D2, 0xFFFFD7F0, 0xFF111318)
private const val WORDS_ALL = 11f
private val WORD_COLORS = listOf(0xFFFFD400, 0xFF3DF58A, 0xFF4FD8FF, 0xFFFF5FA8, 0xFFFF8A1F)

/** The style view's tabs: the presets, the look, the placement, the word marking (one row, no tabs inside tabs). */
enum class CaptionStyleTab { STYLES, LOOK, PLACE, WORDS }

/** The tab row of the caption style (above what it shows; in the style view it stays in place while the rest scrolls). */
@Composable
fun CaptionStyleTabs(st: CaptionStyle, tab: CaptionStyleTab, onTab: (CaptionStyleTab) -> Unit, modifier: Modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
    ToolTabs(listOf(
        ToolTab(stringResource(R.string.captionstyle_tab_styles), Ic.Grid, "capTab:styles"),
        ToolTab(stringResource(R.string.captionstyle_tab_look), Ic.Text, "capTab:look"),
        ToolTab(stringResource(R.string.captionstyle_tab_place), Ic.Center, "capTab:place"),
        ToolTab(stringResource(R.string.captionstyle_tab_words), Ic.Subs, "capTab:words", st.wordColor != null),
    ), tab.ordinal, { onTab(CaptionStyleTab.entries[it]) }, modifier)
}

/** The style applies to the whole caption track (as in other editing programs): every line looks the same. */
@Composable
fun CaptionStyleEditor(backend: PropsBackend, track: Long) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(CaptionStyleTab.STYLES) }
    val st = backend.captionStyle(track) ?: CaptionStyle()
    CaptionStyleTabs(st, tab, { tab = it })
    CaptionStyleContent(backend, track, tab)
}

/** The name of the preset a track's style is, or "Own". */
@Composable
fun captionStyleName(st: CaptionStyle): String = when (se.gendut.klipp.engine.CaptionPresets.of(st)) {
    se.gendut.klipp.engine.CaptionPresets.Kind.STANDARD -> stringResource(R.string.captionstyle_preset_standard)
    se.gendut.klipp.engine.CaptionPresets.Kind.SOCIAL -> stringResource(R.string.captionstyle_preset_social)
    se.gendut.klipp.engine.CaptionPresets.Kind.PLATE -> stringResource(R.string.captionstyle_preset_plate)
    null -> stringResource(R.string.captionstyle_preset_own)
}

/** What one tab of the caption style shows. */
@Composable
fun CaptionStyleContent(backend: PropsBackend, track: Long, tab: CaptionStyleTab) {
    val haptics = rememberHaptics()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = backend.captionStyle(track) ?: CaptionStyle()
    fun set(vararg kv: Pair<String, Any>) { haptics.tick(); backend.setCaptionStyle(track, mapOf(*kv)) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { Fonts.import(ctx, uri) } }
                .onSuccess { set("font" to it) }
                .onFailure { android.widget.Toast.makeText(ctx, Str.t(R.string.captionui_font_error, it.message.toString()), android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    when (tab) {
        CaptionStyleTab.STYLES -> CaptionPresetCards(st) { kind ->
            haptics.tick()
            if (kind == null) backend.setCaptionStyle(track, mapOf("restoreOwn" to true)) else backend.setCaptionStyle(track, mapOf("preset" to kind.name))
        }
        CaptionStyleTab.LOOK -> {
    FontGrid(st.font, { set("font" to it) }) { haptics.tick(); fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) }
    PropSlider(stringResource(R.string.captionui_size), st.size, 16f, 160f, 54f, { "%.0f px".format(it) }, "capSize", lane = false) { set("size" to it.toDouble()) }
    SectionLabel(stringResource(R.string.captionui_color))
    Swatches(st.color, CAPTION_COLORS) { set("color" to CaptionStyle.hex(it)) }
    val off = stringResource(R.string.captionui_off)
    PropSlider(stringResource(R.string.captionui_outline), st.outline, 0f, 12f, 0f, { if (it < 0.05f) off else "%.1f".format(it) }, "capOutline", lane = false) { set("outline" to it.toDouble()) }
    SectionLabel(stringResource(R.string.captionui_background))
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SpringChip(stringResource(R.string.captionui_bg_none), !st.background, { set("background" to false) })
        SpringChip(stringResource(R.string.captionui_bg_semi), st.background && (st.backgroundColor ushr 24) < 250, { set("background" to true, "backgroundColor" to "#000000bf") })
        SpringChip(stringResource(R.string.captionui_bg_solid), st.background && (st.backgroundColor ushr 24) >= 250, { set("background" to true, "backgroundColor" to "#000000ff") })
    }
        }
        CaptionStyleTab.PLACE -> CaptionPlacement(backend, track, st) { kv -> haptics.tick(); backend.setCaptionStyle(track, kv) }
        CaptionStyleTab.WORDS -> {
    val words = (backend as? se.gendut.klipp.engine.ProjectController)?.snapshot?.sequence?.words
    Row(Modifier.fillMaxWidth().clickable { set("wordColor" to if (st.wordColor == null) "#ffd400ff" else "") }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("cap:markWord"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.captionstyle_mark_word), color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.captionstyle_mark_word_hint), color = K.OnSurfaceVar, fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(st.wordColor != null, { set("wordColor" to if (it) "#ffd400ff" else "") },
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = K.PrimarySoft, checkedTrackColor = K.PrimaryContainer))
    }
    if (st.wordColor != null) {
        Swatches(st.wordColor, WORD_COLORS) { set("wordColor" to CaptionStyle.hex(it)) }
        if (words != null && words.isEmpty()) Text(stringResource(R.string.captionstyle_no_word_times), color = K.OnSurfaceVar, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("cap:noWordTimes"))
    }
    val all = stringResource(R.string.captionui_words_all)
    // the far end of the slider = the whole caption
    PropSlider(stringResource(R.string.captionui_words_on_screen), if (st.wordCount == 0) WORDS_ALL else st.wordCount.toFloat(), 1f, WORDS_ALL, WORDS_ALL,
        { if (it.roundToInt() >= WORDS_ALL.toInt()) all else "${it.roundToInt()}" }, "capWords", lane = false) { v ->
        set("wordCount" to v.roundToInt().let { if (it >= WORDS_ALL.toInt()) 0 else it })
    }
    Text(stringResource(R.string.captionstyle_words_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
    if (st.wordColor != null) PropSlider(stringResource(R.string.captionui_word_zoom), st.wordZoom * 100f, 100f, 200f, 100f, { "%.0f %%".format(it) }, "capZoom", lane = false) { set("wordZoom" to (it / 100f)) }
        }
    }
}

/** The presets as cards that show their look, and "Own" when the track has (or kept) a look of its own. */
@Composable
private fun CaptionPresetCards(st: CaptionStyle, onPick: (se.gendut.klipp.engine.CaptionPresets.Kind?) -> Unit) {
    val now = se.gendut.klipp.engine.CaptionPresets.of(st)
    Text(stringResource(R.string.captionstyle_presets_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PresetCard(stringResource(R.string.captionstyle_preset_standard), now == se.gendut.klipp.engine.CaptionPresets.Kind.STANDARD, "capPreset:standard",
            { onPick(se.gendut.klipp.engine.CaptionPresets.Kind.STANDARD) }) {
            Text("Aa", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        PresetCard(stringResource(R.string.captionstyle_preset_social), now == se.gendut.klipp.engine.CaptionPresets.Kind.SOCIAL, "capPreset:social",
            { onPick(se.gendut.klipp.engine.CaptionPresets.Kind.SOCIAL) }) {
            Row {
                Text("AA ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("AA", color = Color(0xFFFFD400), fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
        }
        PresetCard(stringResource(R.string.captionstyle_preset_plate), now == se.gendut.klipp.engine.CaptionPresets.Kind.PLATE, "capPreset:plate",
            { onPick(se.gendut.klipp.engine.CaptionPresets.Kind.PLATE) }) {
            Text("Aa", color = Color(0xFF111318), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        // the track's own look: the current one, or the one kept when a preset was chosen
        if (now == null || st.savedLook != null) PresetCard(stringResource(R.string.captionstyle_preset_own), now == null, "capPreset:own",
            { if (now != null) onPick(null) }) {
            Text("Aa", color = Color(st.color), fontSize = 18.sp)
        }
    }
}

@Composable
private fun PresetCard(label: String, on: Boolean, tag: String, onClick: () -> Unit, sample: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(96.dp).clip(shape).background(if (on) K.PrimaryContainer else K.SurfaceHigh)
            .border(if (on) 2.dp else 1.dp, if (on) K.Primary else K.OutlineVar, shape)
            .clickable(onClickLabel = label, onClick = onClick).padding(vertical = 10.dp).testTag(tag)
            .semantics { this.selected = on },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(40.dp).fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF5A6470)), contentAlignment = Alignment.Center) { sample() }
        Text(label, color = if (on) K.PrimarySoft else K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp))
    }
}

/**
 * Where the track's captions sit: top, middle, below the middle (with its height) or bottom; the alignment, the distance
 * from the edge, the line width, and "fit to a safe area" for a platform.
 */
@Composable
private fun CaptionPlacement(backend: PropsBackend, track: Long, st: CaptionStyle, set: (Map<String, Any>) -> Unit) {
    val haptics = rememberHaptics()
    val trackName = backend.captionTracks.firstOrNull { it.id == track }?.name ?: ""
    Text(stringResource(R.string.captionstyle_place_scope, trackName), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    val place = when { st.centerY != null -> "under"; else -> st.anchor }
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, l) in listOf("top" to R.string.captionstyle_place_top, "middle" to R.string.captionstyle_place_middle,
            "under" to R.string.captionstyle_place_under, "bottom" to R.string.captionstyle_place_bottom)) {
            SpringChip(stringResource(l), place == k, { set(mapOf("placement" to k)) }, Modifier.testTag("capPlace:$k"))
        }
    }
    if (st.centerY != null) PropSlider(stringResource(R.string.captionstyle_height), st.centerY * 100f, 30f, 90f, 60f, { "%.0f %%".format(it) }, "capHeight", lane = false) {
        set(mapOf("centerY" to it / 100f))
    } else if (st.anchor != "middle") PropSlider(stringResource(R.string.captionui_margin), st.margin * 100f, 0f, 45f, 8f, { "%.0f %%".format(it) }, "capMargin", lane = false) {
        set(mapOf("margin" to (it / 100.0)))
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, l) in listOf("left" to stringResource(R.string.captionui_left), "center" to stringResource(R.string.captionui_center), "right" to stringResource(R.string.captionui_right))) SpringChip(l, st.align == k, { set(mapOf("align" to k)) })
    }
    PropSlider(stringResource(R.string.captionstyle_line_width), (st.wrapWidth ?: 0.9f) * 100f, 20f, 90f, 90f, { "%.0f %%".format(it) }, "capWrap", lane = false) {
        set(mapOf("wrapWidth" to if (it >= 89.5f) -1f else it / 100f))
    }
    // fit to a platform's safe area (a portrait sequence only): the platform shown over the picture, or chosen here
    if (backend.portrait) {
    SectionLabel(stringResource(R.string.captionstyle_fit))
    Text(stringResource(R.string.captionstyle_fit_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    val shown = LocalSafeZone.current
    var platform by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(shown?.key ?: se.gendut.klipp.engine.SafeZones.Platform.TIKTOK.key) }
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in se.gendut.klipp.engine.SafeZones.Platform.entries) SpringChip(stringResource(p.label), platform == p.key, { haptics.tick(); platform = p.key }, Modifier.testTag("capFitFor:${p.key}"))
    }
    val toaster = LocalToaster.current
    PillButton(stringResource(R.string.captionstyle_fit), {
        haptics.confirm()
        val msg = backend.fitCaptionsToSafeArea(track, platform)
        toaster?.show(msg)
    }, Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("capFit"), icon = Ic.Crop, filled = false)
    }
}

@Composable
private fun Swatches(current: Int, colors: List<Long>, onPick: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (c in colors) {
            val on = (current ushr 24) != 0 && (current and 0xFFFFFF) == (c.toInt() and 0xFFFFFF)
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(if (on) 14.dp else 22.dp)).background(Color(c))
                    .border(if (on) 3.dp else 1.dp, if (on) K.Primary else K.OutlineVar, RoundedCornerShape(if (on) 14.dp else 22.dp))
                    .clickable { onPick(c.toInt()) }
            )
        }
    }
}

/** Font picker for titles (stored in the title's template string). */
@Composable
fun TitleFontRow(current: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { Fonts.import(ctx, uri) } }
                .onSuccess { onPick(it) }
                .onFailure { android.widget.Toast.makeText(ctx, Str.t(R.string.captionui_font_error, it.message.toString()), android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    FontGrid(current, { haptics.tick(); onPick(it) }) { haptics.tick(); fontPicker.launch(arrayOf("*/*")) }
}

/**
 * Every font as a grid of names, each written in its own font; folded it shows the chosen one and its neighbors in a
 * row. Import last.
 */
@Composable
fun FontGrid(current: String, onPick: (String) -> Unit, onImport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val all = Fonts.all
    Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.captionui_font)) }
        SpringChip(stringResource(if (open) R.string.captionui_fonts_fewer else R.string.captionui_fonts_all, all.size), open, { open = !open },
            Modifier.testTag("font:all"))
    }
    if (!open) {
        val scroll = rememberScrollState()
        // (a new choice moves to the front: the row follows it there)
        LaunchedEffect(current) { scroll.animateScrollTo(0) }
        Row(Modifier.sideScroll(scroll).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // the chosen font first, then the rest in order
            for (name in listOf(current) + all.filter { it != current }) FontName(name, current == name) { onPick(name) }
        }
    } else {
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in all) FontName(name, current == name) { onPick(name) }
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(K.SurfaceHigh)
                    .clickable { onImport() }.padding(horizontal = 14.dp).testTag("font:import"),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.captionui_import), color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun FontName(name: String, selected: Boolean, onClick: () -> Unit) {
    val tf = remember(name) { androidx.compose.ui.text.font.FontFamily(Fonts.typeface(name)) }
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(if (selected) 18.dp else 12.dp))
            .background(if (selected) K.PrimaryContainer else K.SurfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = 14.dp).testTag("font:$name"),
        contentAlignment = Alignment.Center,
    ) { Text(Str.name(name), fontFamily = tf, fontSize = 17.sp, color = if (selected) K.PrimarySoft else K.OnSurface, maxLines = 1) }
}
