package se.gendut.klipp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.gendut.klipp.engine.Overlay
import se.gendut.klipp.engine.OverlayItem
import se.gendut.klipp.engine.Placement
import se.gendut.klipp.engine.TextSnap
import se.gendut.klipp.engine.TitleAnim
import se.gendut.klipp.engine.TitleArt
import se.gendut.klipp.engine.TitleLook
import se.gendut.klipp.engine.TitlePlate
import se.gendut.klipp.engine.TitleShadow
import se.gendut.klipp.engine.TitleTemplate
import se.gendut.klipp.engine.placementOf
import kotlin.math.roundToInt

/**
 * Draws an image placed the way Media3 places an overlay: the point (ovX, ovY) in the image lands on
 * (bgX, bgY) in the frame, scaled and rotated around it. [frameW]×[frameH] = the program frame's size in pixels;
 * the image is drawn at the same scale.
 */
fun DrawScope.drawPlaced(img: ImageBitmap, p: Placement, frameW: Int, frameH: Int) {
    if (p.alpha < 0.003f) return
    val k = size.width / frameW
    val ow = img.width * k
    val oh = img.height * k
    val bx = (p.bgX + 1f) / 2f * size.width
    val by = (1f - p.bgY) / 2f * size.height
    val ox = (p.ovX + 1f) / 2f * ow
    val oy = (1f - p.ovY) / 2f * oh
    translate(bx, by) {
        // Media3 rotates counterclockwise, Compose clockwise
        rotate(-p.rotation, Offset.Zero) {
            scale(p.scale, p.scale, Offset.Zero) {
                translate(-ox, -oy) {
                    drawImage(img, dstSize = IntSize(ow.roundToInt().coerceAtLeast(1), oh.roundToInt().coerceAtLeast(1)), dstOffset = IntOffset.Zero, alpha = p.alpha.coerceIn(0f, 1f))
                }
            }
        }
    }
}

/** Draws a pinned title's image [bmp] through [m] (its pixels → frame pixels, see pinArtOf) into a [frameW]-wide frame. */
fun DrawScope.drawPinned(bmp: android.graphics.Bitmap, m: android.graphics.Matrix, frameW: Int, alpha: Float) {
    if (alpha < 0.003f) return
    val total = android.graphics.Matrix(m).apply { postScale(size.width / frameW, size.width / frameW) }
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt() }
    drawContext.canvas.nativeCanvas.drawBitmap(bmp, total, paint)
}

/** A small mock picture behind the title preview (sky, mountains, sun – as in the design mockup). */
private fun DrawScope.scene() {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2B3A55), Color(0xFF8C6A5C))))
    drawCircle(Color(0xFFFFC98A), h * 0.12f, Offset(w * 0.76f, h * 0.3f))
    val m = Path().apply {
        moveTo(0f, h * 0.72f); lineTo(w * 0.22f, h * 0.5f); lineTo(w * 0.38f, h * 0.63f); lineTo(w * 0.6f, h * 0.38f)
        lineTo(w * 0.82f, h * 0.6f); lineTo(w, h * 0.47f); lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(m, Color(0xFF233046))
}

fun sampleText(t: TitleTemplate): String = when (t) {
    TitleTemplate.LOWER_THIRD -> Str.t(R.string.titleui_sample_lower_third)
    TitleTemplate.CHAPTER -> Str.t(R.string.titleui_sample_chapter)
    TitleTemplate.ROLL -> Str.t(R.string.titleui_sample_roll)
    TitleTemplate.FREE -> Str.t(R.string.titleui_title)
}

fun sizeFor(t: TitleTemplate, h: Int): Double = h * when (t) {
    TitleTemplate.LOWER_THIRD -> 0.062; TitleTemplate.CHAPTER -> 0.085; TitleTemplate.ROLL -> 0.05; TitleTemplate.FREE -> 0.09
}

/** The title as it enters the frame – plays in a loop. */
@Composable
fun TitlePreview(look: TitleLook, text: String, modifier: Modifier = Modifier, color: Int = 0xFFFFFF, playing: Boolean = true, replayKey: Any? = null) {
    val fw = 640
    val fh = 360
    val snap = remember(look, text, color) {
        TextSnap(text.ifBlank { sampleText(look.template) }, sizeFor(look.template, fh), null, null,
            listOf(((color shr 16) and 255) / 255f, ((color shr 8) and 255) / 255f, (color and 255) / 255f, 1f), look.encode())
    }
    val img = remember(snap) { runCatching { TitleArt.draw(snap, fw, fh).asImageBitmap() }.getOrNull() }
    val dur = if (look.template == TitleTemplate.ROLL) 6.0 else 3.0
    var t by remember { mutableFloatStateOf(if (playing) 0f else 1.2f) }
    // the loop only plays while the box is visible on screen (not when the panel is scrolled away or on the
    // neighboring page) and no speech recognition is running – otherwise it redraws every frame for nothing
    var onScreen by remember { mutableStateOf(true) }
    val busy = se.gendut.klipp.engine.AutoCaptions.running
    val animate = playing && onScreen && !busy
    LaunchedEffect(animate, look, replayKey) {
        if (!animate) { t = 1.2f; return@LaunchedEffect }
        t = 0f
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last > 0) t += (now - last) / 1e9f
                last = now
            }
            if (t > dur + 0.5f) { t = 0f; delay(200) }
        }
    }
    val view = androidx.compose.ui.platform.LocalView.current
    Canvas(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).onGloballyPositioned { c ->
        val r = c.boundsInWindow()
        val vis = r.width > 0f && r.height > 0f && r.bottom > 0f && r.top < view.height && r.right > 0f && r.left < view.width
        if (vis != onScreen) onScreen = vis
    }) {
        scene()
        // the time is read in the draw phase: the box is redrawn every frame without recomposing anything
        val now = t
        val tt = now.toDouble().coerceAtMost(dur - 0.001)
        if (img != null && now <= dur) drawPlaced(img, placementOf(OverlayItem(0.0, dur, Overlay.Title(snap)), tt, fw, fh), fw, fh)
    }
}

private val TitleTemplate.desc: Int get() = when (this) {
    TitleTemplate.LOWER_THIRD -> R.string.titleui_tpl_desc_lower_third
    TitleTemplate.CHAPTER -> R.string.titleui_tpl_desc_chapter
    TitleTemplate.ROLL -> R.string.titleui_tpl_desc_roll
    TitleTemplate.FREE -> R.string.titleui_tpl_desc_free
}

/** A template as a tile: a still of it and its name; selected = the accent's container. */
@Composable
private fun TemplateRow(t: TitleTemplate, anim: TitleAnim, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) K.PrimaryContainer else K.Surface, label = "tb")
    Column(
        modifier.pressPop(src, 0.97f)
            .clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(6.dp)
            .testTag("mall:${t.key}"),
    ) {
        // a still – only the big preview above plays
        TitlePreview(TitleLook(t, anim), "", Modifier.fillMaxWidth(), playing = false)
        Text(t.label, color = if (selected) K.PrimarySoft else K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        Text(stringResource(t.desc), color = K.OnSurfaceVar, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
    }
}

/** Text fields for the title's lines, by template. */
@Composable
private fun TitleFields(template: TitleTemplate, lines: List<String>, placeholders: List<String> = emptyList(), onChange: (List<String>) -> Unit) {
    fun at(i: Int) = lines.getOrElse(i) { "" }
    fun ph(i: Int) = placeholders.getOrNull(i)
    when (template) {
        TitleTemplate.LOWER_THIRD -> {
            TextInput(stringResource(R.string.titleui_field_name), at(0), Modifier.testTag("titleField:0"), placeholder = ph(0)) { onChange(listOf(it, at(1))) }
            TextInput(stringResource(R.string.titleui_field_role), at(1), Modifier.testTag("titleField:1"), placeholder = ph(1)) { onChange(listOf(at(0), it)) }
        }
        TitleTemplate.CHAPTER -> {
            TextInput(stringResource(R.string.titleui_field_heading), at(0), Modifier.testTag("titleField:0"), placeholder = ph(0)) { onChange(listOf(it, at(1))) }
            TextInput(stringResource(R.string.titleui_field_sub), at(1), Modifier.testTag("titleField:1"), placeholder = ph(1)) { onChange(listOf(at(0), it)) }
        }
        TitleTemplate.ROLL, TitleTemplate.FREE -> {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
                Text(stringResource(if (template == TitleTemplate.ROLL) R.string.titleui_field_lines else R.string.titleui_field_text), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.width(64.dp).padding(top = 12.dp))
                BasicTextField(
                    value = lines.joinToString("\n"),
                    onValueChange = { onChange(it.split('\n')) },
                    textStyle = TextStyle(color = K.OnSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(K.Primary),
                    modifier = Modifier.weight(1f).height(if (template == TitleTemplate.ROLL) 140.dp else 72.dp)
                        .clip(RoundedCornerShape(14.dp)).background(K.SurfaceHigh).padding(horizontal = 14.dp, vertical = 12.dp).testTag("titleField:0"),
                    decorationBox = { inner ->
                        Box {
                            if (lines.joinToString("").isEmpty() && placeholders.isNotEmpty()) Text(placeholders.joinToString("\n"), color = K.OnSurfaceVar.copy(alpha = 0.6f), fontSize = 15.sp)
                            inner()
                        }
                    },
                )
            }
            if (template == TitleTemplate.ROLL) Text(
                stringResource(R.string.titleui_roll_hint),
                color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun AnimChips(template: TitleTemplate, anim: TitleAnim, onPick: (TitleAnim) -> Unit) {
    if (template == TitleTemplate.ROLL) return
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (a in TitleAnim.entries) {
            SpringChip(a.label, anim == a, { onPick(a) }, Modifier.testTag("anim:${a.key}"))
        }
    }
}

/** What a title being made shows: what was typed, else the template's example. */
internal fun TitleDraft.shownText(): String = lines.joinToString("\n").trim('\n').ifBlank { sampleText(template) }

/**
 * A new title (Add › Title), in the order it is made: the text first, then how it looks, then how it comes in – with a
 * preview on top that shows what is typed. Changing the look keeps the text. The add button is below, always in view
 * ([TitleAddButton]).
 */
@Composable
internal fun TitleNew(state: EditorState) {
    val haptics = rememberHaptics()
    val d = state.titleDraft
    var replay by remember { mutableStateOf(0) }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        TitlePreview(TitleLook(d.template, d.anim), d.shownText(), Modifier.fillMaxWidth(), replayKey = replay)
    }
    SectionLabel(stringResource(R.string.titleui_step_text))
    TitleFields(d.template, d.lines, placeholders = sampleText(d.template).split('\n')) { d.lines = it }
    SectionLabel(stringResource(R.string.titleui_step_look))
    for (pair in TitleTemplate.entries.chunked(2)) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in pair) TemplateRow(t, d.anim, d.template == t, Modifier.weight(1f)) {
            // (the text stays: only the look changes)
            haptics.confirm(); d.template = t; replay++
        }
    }
    if (d.template != TitleTemplate.ROLL) {
        SectionLabel(stringResource(R.string.titleui_step_entrance))
        AnimChips(d.template, d.anim) { d.anim = it; replay++; haptics.tick() }
    }
}

/** Add the title being made at the playhead – below the sheet's content, always in view. */
@Composable
internal fun TitleAddButton(state: EditorState, backend: PropsBackend, onEdit: () -> Unit) {
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth().background(K.Surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
        AddAtPlayhead(state, Modifier.fillMaxWidth().testTag("title:add")) {
            haptics.confirm()
            val d = state.titleDraft
            backend.addTitle(d.shownText(), state.playhead, TitleLook(d.template, d.anim).encode())
            d.lines = emptyList()
            onEdit()
        }
    }
}

/** The titles already on the timeline: a row opens the title's properties. */
@Composable
internal fun TitlesOnTimeline(state: EditorState, onEdit: () -> Unit) {
    val haptics = rememberHaptics()
    val titles = state.clips.filter { it.kind == ClipKind.TITLE }.sortedBy { it.start }
    for (t in titles) ListRow(formatTc(t.start), t.name, "title:row:${t.id}") {
        haptics.tick()
        state.playing = false
        state.playhead = t.start
        state.selected = t.id
        state.inspectGroup = 0
        state.inspecting = true
        onEdit()
    }
}

/** The title sheet's two tabs: a new title, and (when there are any) the titles on the timeline. */
@Composable
internal fun TitleTabs(state: EditorState, tab: Int, onTab: (Int) -> Unit) {
    val n = state.clips.count { it.kind == ClipKind.TITLE }
    if (n == 0) return
    ConnectedToggleGroup(
        listOf(stringResource(R.string.titleui_tab_new), stringResource(R.string.titleui_tab_on_timeline, n)), tab, onTab,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), tag = { listOf("titleTab:new", "titleTab:list")[it] },
    )
}

/** Add › Title's content in one column (the sheet itself puts the tabs on top and the button at the bottom). */
@Composable
internal fun TitlesTab(state: EditorState, backend: PropsBackend, onEdit: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    TitleTabs(state, tab) { tab = it }
    if (tab == 0 || state.clips.none { it.kind == ClipKind.TITLE }) {
        TitleNew(state)
        TitleAddButton(state, backend, onEdit)
    } else TitlesOnTimeline(state, onEdit)
}

/** The captions sheet's parts: made from the speech, written by hand (or imported), and those in the project. */
enum class CaptionsPart { SPEECH, WRITE, EXISTING }

@Composable
internal fun CaptionTabs(state: EditorState, part: CaptionsPart, onPart: (CaptionsPart) -> Unit) {
    ConnectedToggleGroup(
        listOf(stringResource(R.string.titleui_caps_from_speech), stringResource(R.string.titleui_caps_write), stringResource(R.string.titleui_caps_existing, state.captions.size)),
        part.ordinal, { onPart(CaptionsPart.entries[it]) },
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), tag = { "capsPart:${CaptionsPart.entries[it].name.lowercase()}" },
    )
}

/** The first part shown: the captions in the project when there are any, else making them from the speech. */
internal fun firstCaptionsPart(state: EditorState) = if (state.captions.isNotEmpty()) CaptionsPart.EXISTING else CaptionsPart.SPEECH

/** One part of the captions sheet. */
@Composable
internal fun CaptionsPartContent(state: EditorState, backend: PropsBackend, part: CaptionsPart, onPart: (CaptionsPart) -> Unit, onEdit: () -> Unit) {
    when (part) {
        CaptionsPart.SPEECH -> {
            (backend as? se.gendut.klipp.engine.ProjectController)?.let { AutoCaptionsCard(it) }
                ?: Text(stringResource(R.string.titleui_caps_speech_needs_project), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            // made: the captions are shown (the generation ends with them on the timeline)
            val n = state.captions.size
            var before by remember { mutableStateOf(n) }
            LaunchedEffect(n, se.gendut.klipp.engine.AutoCaptions.running) {
                if (n > before && !se.gendut.klipp.engine.AutoCaptions.running) onPart(CaptionsPart.EXISTING)
                before = n
            }
        }
        CaptionsPart.WRITE -> CaptionsWrite(state, backend)
        CaptionsPart.EXISTING -> CaptionsExisting(state, backend, onPart, onEdit)
    }
}

/** The captions sheet's content in one column (the sheet itself puts the tabs on top and the add button at the bottom). */
@Composable
internal fun CaptionsTab(state: EditorState, backend: PropsBackend, onEdit: () -> Unit) {
    var part by remember { mutableStateOf(firstCaptionsPart(state)) }
    CaptionTabs(state, part) { part = it }
    CaptionsPartContent(state, backend, part, { part = it }, onEdit)
    if (part == CaptionsPart.WRITE) CaptionWriteButton(state, backend)
}

/** The caption track new lines go to and the style applies to: the chosen one, else the first visible one. */
@Composable
private fun CaptionTrackChips(state: EditorState, backend: PropsBackend, tracks: List<CaptionTrackUi>, target: Long?) {
    if (tracks.size <= 1) return
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val haptics = rememberHaptics()
        for (t in tracks) SpringChip(stringResource(R.string.titleui_caption_track_count, t.name, t.count), target == t.id,
            { haptics.tick(); state.captionTrack = t.id }, Modifier.testTag("capTrack:${t.id}"))
    }
}

/**
 * Write captions by hand: type a line, add it at the playhead – the playhead then goes to its end, so the next line
 * follows on. (A file of captions is imported here too: it becomes a track of its own.)
 */
@Composable
internal fun CaptionsWrite(state: EditorState, backend: PropsBackend) {
    val haptics = rememberHaptics()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text(stringResource(R.string.titleui_caps_write_hint, "%.0f".format(AppPrefs.captionSeconds(ctx).toDouble())), color = K.OnSurfaceVar, fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    BasicTextField(
        value = state.captionDraft, onValueChange = { state.captionDraft = it },
        textStyle = TextStyle(color = K.OnSurface, fontSize = 16.sp), cursorBrush = SolidColor(K.Primary),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(96.dp).clip(RoundedCornerShape(14.dp))
            .background(K.SurfaceHigh).padding(14.dp).testTag("captionWriteText"),
        decorationBox = { inner ->
            Box {
                if (state.captionDraft.isEmpty()) Text(stringResource(R.string.titleui_caps_write_placeholder), color = K.OnSurfaceVar.copy(alpha = 0.6f), fontSize = 16.sp)
                inner()
            }
        },
    )
    // which track the line goes to (when there are several)
    val tracks = backend.captionTracks
    val target = captionStyleTrack(tracks, state.captionTrack) ?: tracks.firstOrNull { it.enabled }?.id
    if (tracks.size > 1) {
        SectionLabel(stringResource(R.string.titleui_caps_write_track))
        CaptionTrackChips(state, backend, tracks, target)
    }
    SectionLabel(stringResource(R.string.titleui_caps_import_title))
    Text(stringResource(R.string.titleui_caps_import_hint), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    PillButton(stringResource(R.string.titleui_import_captions), { haptics.tick(); backend.importCaptions() },
        Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("capsImport"), icon = Ic.Download, filled = false)
}

/** Add the line being written at the playhead – below the sheet's content, always in view. */
@Composable
internal fun CaptionWriteButton(state: EditorState, backend: PropsBackend) {
    val haptics = rememberHaptics()
    val tracks = backend.captionTracks
    val target = captionStyleTrack(tracks, state.captionTrack) ?: tracks.firstOrNull { it.enabled }?.id
    Row(Modifier.fillMaxWidth().background(K.Surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
        AddAtPlayhead(state, Modifier.fillMaxWidth().testTag("captionWriteAdd"), enabled = state.captionDraft.isNotBlank()) {
            haptics.confirm()
            val text = state.captionDraft.trim()
            backend.addCaption(state.playhead, text, target) { id ->
                // the next line follows on: the playhead to this one's end, the field empty again
                state.captions.firstOrNull { it.id == id }?.let { state.playhead = it.end }
                state.captionDraft = ""
            }
        }
    }
}

/** The captions in the project: the track (chosen when there are several), the style named by its track, the list. */
@Composable
internal fun CaptionsExisting(state: EditorState, backend: PropsBackend, onPart: (CaptionsPart) -> Unit, onEdit: () -> Unit) {
    val haptics = rememberHaptics()
    if (state.captions.isEmpty()) {
        Text(stringResource(R.string.titleui_caps_none), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.titleui_caps_from_speech), { onPart(CaptionsPart.SPEECH) }, icon = Ic.Subs, filled = false)
            PillButton(stringResource(R.string.titleui_caps_write), { onPart(CaptionsPart.WRITE) }, icon = Ic.Text, filled = false)
        }
        return
    }
    Text(stringResource(R.string.titleui_captions_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    // the tracks: a style is the track's – with several, the one it applies to is chosen (never silently the first)
    val tracks = backend.captionTracks
    val target = captionStyleTrack(tracks, state.captionTrack)
    if (tracks.size > 1) {
        SectionLabel(stringResource(R.string.titleui_caption_tracks))
        CaptionTrackChips(state, backend, tracks, target)
        if (target == null) Text(stringResource(R.string.titleui_caption_track_choose), color = K.OnSurfaceVar, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("capTrackChoose"))
    }
    // the style (the chosen track's, said on the button) and shown / hidden (all tracks)
    var styleOpen by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val name = tracks.firstOrNull { it.id == target }?.name
        if (name != null) SpringChip(stringResource(R.string.captionui_style_scope, name), styleOpen, { haptics.tick(); styleOpen = !styleOpen }, Modifier.testTag("capStyle"))
        if (backend.hasCaptionTrack) {
            val vis = backend.captionsVisible
            SpringChip(stringResource(if (vis) R.string.titleui_captions_shown_all else R.string.titleui_captions_hidden_all), vis, { backend.setCaptionsVisible(!vis) })
        }
    }
    if (target != null) androidx.compose.animation.AnimatedVisibility(styleOpen) { Column { CaptionStyleEditor(backend, target) } }
    // the captions: a row opens the caption editor (text, times, style) – the same one as tapping it in the timeline
    SectionLabel(stringResource(R.string.titleui_caps_list))
    for (c in state.captions.take(300)) key(c.id) {
        ListRow(formatTc(c.start), c.text.replace('\n', ' '), "caption:row:${c.id}") {
            haptics.tick(); state.playing = false; state.playhead = c.start
            state.clearSelection(); state.selectedCaption = c.id; state.editingCaption = true
            onEdit()
        }
    }
}

/** Its own recomposition scope: only the button follows the playhead, not the whole panel. */
@Composable
private fun AddAtPlayhead(state: EditorState, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    BigButton(stringResource(R.string.titleui_add_at, formatTc(state.playhead)), onClick, color = K.PrimaryContainer, textColor = K.PrimarySoft, modifier = modifier, enabled = enabled)
}

/** The title's part of Properties: template, animation, text, size and color – changes show in the picture immediately. */
@Composable
fun TitleSection(state: EditorState, p: ClipProps, backend: PropsBackend, tab: Int) {
    val scope = rememberCoroutineScope()
    val look = TitleLook.parse(p.titleLook)
    fun live(l: TitleLook) = state.setLive((state.live?.takeIf { it.id == p.id } ?: p).copy(titleLook = l.encode()))
    fun commit(l: TitleLook) { backend.setTitle(p.id, look = l.encode()); scope.launch { state.endLive() } }
    var lines by remember(p.id) { mutableStateOf((p.title ?: "").split('\n')) }
    // text that has been typed but not yet sent to the engine
    val pending = remember(p.id) { mutableStateOf<String?>(null) }
    // undo etc. from outside: follow the engine's text when nothing of our own is pending
    LaunchedEffect(p.id, p.title) {
        if (pending.value == null && lines.joinToString("\n") != (p.title ?: "")) lines = (p.title ?: "").split('\n')
    }
    // live in the picture while typing; the engine gets the text once typing pauses for a moment
    LaunchedEffect(pending.value) {
        val text = pending.value ?: return@LaunchedEffect
        delay(600)
        backend.setTitle(p.id, text = text)
        pending.value = null // restarts this effect – endLive gets its own coroutine
        scope.launch { state.endLive() }
    }
    // switching clips or closing the panel before then: send what is pending instead of losing it
    DisposableEffect(p.id) {
        val id = p.id
        onDispose {
            pending.value?.let { text -> pending.value = null; backend.setTitle(id, text = text) }
            // endLive has no time to run when the panel closes – drop the live picture here
            if (state.live?.id == id) state.live = null
        }
    }
    when (tab) {
        0 -> {
            PanelCard(Modifier.padding(top = 10.dp)) {
            TitleFields(look.template, lines) {
                lines = it
                val text = it.joinToString("\n")
                pending.value = if (text == p.title) null else text
                state.setLive((state.live?.takeIf { l -> l.id == p.id } ?: p).copy(title = text))
                if (pending.value == null) scope.launch { state.endLive() }
            }
            }
        }
        1 -> {
            PanelCard(Modifier.padding(top = 10.dp)) {
            SectionLabel(stringResource(R.string.titleui_template))
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in TitleTemplate.entries) SpringChip(t.label, look.template == t, { backend.setTitle(p.id, look = look.copy(template = t).encode()) })
            }
            }
            // the text's look: size, color, its own opacity, font, weight, spacing
            PanelCard(Modifier.padding(top = 10.dp)) {
            PropSlider(
                stringResource(R.string.titleui_size), p.textSize, 8f, 400f, sizeFor(look.template, p.seqH).roundToInt().toFloat(), { "%.0f px".format(it) }, "size",
                onLive = { v -> state.setLive((state.live?.takeIf { it.id == p.id } ?: p).copy(textSize = v)) },
            ) { backend.setTitle(p.id, size = it); scope.launch { state.endLive() } }
            SectionLabel(stringResource(R.string.titleui_text_color))
            ColorPicker(p.textColor and 0xFFFFFF, "titleColor",
                onLive = { c -> state.setLive((state.live?.takeIf { it.id == p.id } ?: p).copy(textColor = c)) },
            ) { c -> backend.setTitle(p.id, color = c); scope.launch { state.endLive() } }
            PropSlider(stringResource(R.string.titleui_text_opacity), look.opacity * 100f, 0f, 100f, 100f, { "%.0f %%".format(it) }, "titleOpacity", lane = false,
                onLive = { live(look.copy(opacity = it / 100f)) }) { commit(look.copy(opacity = it / 100f)) }
            TitleFontRow(look.font) { commit(look.copy(font = it)) }
            // bold, italic, all capitals
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SpringChip(stringResource(R.string.titleui_bold), look.bold, { commit(look.copy(bold = !look.bold)) }, Modifier.testTag("title:bold"))
                SpringChip(stringResource(R.string.titleui_italic), look.italic, { commit(look.copy(italic = !look.italic)) }, Modifier.testTag("title:italic"))
                SpringChip(stringResource(R.string.titleui_caps), look.caps, { commit(look.copy(caps = !look.caps)) }, Modifier.testTag("title:caps"))
            }
            PropSlider(stringResource(R.string.titleui_tracking), look.tracking * 100f, -10f, 60f, 0f, { "%.0f".format(it) }, "titleTracking", lane = false,
                onLive = { live(look.copy(tracking = it / 100f)) }) { commit(look.copy(tracking = it / 100f)) }
            PropSlider(stringResource(R.string.titleui_leading), look.leading * 100f, 50f, 300f, 100f, { "%.0f %%".format(it) }, "titleLeading", lane = false,
                onLive = { live(look.copy(leading = it / 100f)) }) { commit(look.copy(leading = it / 100f)) }
            if (look.template == TitleTemplate.FREE) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, l) in listOf("l" to stringResource(R.string.captionui_left), "c" to stringResource(R.string.captionui_center), "r" to stringResource(R.string.captionui_right)))
                    SpringChip(l, look.align == k, { commit(look.copy(align = k)) }, Modifier.testTag("title:align:$k"))
            }
            }
            if (look.template != TitleTemplate.ROLL) PanelCard(Modifier.padding(top = 10.dp)) { TitlePlateSection(look, ::live, ::commit) }
            PanelCard(Modifier.padding(top = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.captionui_outline)) }
                SpringChip(stringResource(if (look.stroke > 0f) R.string.titleui_on else R.string.titleui_off), look.stroke > 0f,
                    { commit(look.copy(stroke = if (look.stroke > 0f) 0f else 0.04f)) }, Modifier.testTag("title:stroke"))
            }
            if (look.stroke > 0f) {
                PropSlider(stringResource(R.string.titleui_width), look.stroke * 100f, 0.5f, 20f, 4f, { "%.1f".format(it) }, "titleStroke", lane = false,
                    onLive = { live(look.copy(stroke = it / 100f)) }) { commit(look.copy(stroke = it / 100f)) }
                ColorPicker(look.strokeColor, "titleStrokeColor", onLive = { live(look.copy(strokeColor = it)) }) { commit(look.copy(strokeColor = it)) }
            }
            }
            PanelCard(Modifier.padding(top = 10.dp)) { TitleShadowSection(state, p, look, backend) }
        }
        3 -> PanelCard(Modifier.padding(top = 10.dp)) {
            SectionLabel(stringResource(R.string.titleui_entrance))
            AnimChips(look.template, look.anim) { backend.setTitle(p.id, look = look.copy(anim = it).encode()) }
            if (look.template == TitleTemplate.ROLL) Text(stringResource(R.string.titleui_roll_no_anim), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        2 -> LocalGradeState.current?.let { gs -> PanelCard(Modifier.padding(top = 10.dp)) { FollowSection(state, p, gs) }; PanelCard(Modifier.padding(top = 10.dp)) { PinSection(state, p, gs) } }
        else -> {}
    }
}

/** The plate behind the text: on/off, color, opacity, corners, padding and the accent. */
@Composable
private fun TitlePlateSection(look: TitleLook, live: (TitleLook) -> Unit, commit: (TitleLook) -> Unit) {
    val pl = look.plateOrDefault
    fun l(n: TitlePlate) = live(look.copy(plate = n))
    fun c(n: TitlePlate) = commit(look.copy(plate = n))
    Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.titleui_plate)) }
        SpringChip(stringResource(if (pl.on) R.string.titleui_on else R.string.titleui_off), pl.on, { c(pl.copy(on = !pl.on)) }, Modifier.testTag("title:plate"))
    }
    if (pl.on) {
        PropSlider(stringResource(R.string.titleui_opacity), pl.opacity * 100f, 0f, 100f, TitlePlate.default(look.template).opacity * 100f, { "%.0f %%".format(it) }, "plateOpacity", lane = false,
            onLive = { l(pl.copy(opacity = it / 100f)) }) { c(pl.copy(opacity = it / 100f)) }
        if (look.template != TitleTemplate.CHAPTER) {
            PropSlider(stringResource(R.string.titleui_corners), pl.radius * 100f, 0f, 100f, 16f, { "%.0f".format(it) }, "plateRadius", lane = false,
                onLive = { l(pl.copy(radius = it / 100f)) }) { c(pl.copy(radius = it / 100f)) }
            PropSlider(stringResource(R.string.titleui_padding), pl.padding * 100f, 0f, 150f, 38f, { "%.0f".format(it) }, "platePadding", lane = false,
                onLive = { l(pl.copy(padding = it / 100f)) }) { c(pl.copy(padding = it / 100f)) }
        }
        SectionLabel(stringResource(R.string.titleui_plate_color))
        ColorPicker(pl.color, "plateColor", onLive = { l(pl.copy(color = it)) }) { c(pl.copy(color = it)) }
    }
    if (look.template == TitleTemplate.LOWER_THIRD || look.template == TitleTemplate.CHAPTER) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.titleui_accent)) }
            SpringChip(stringResource(if (pl.accentOn) R.string.titleui_on else R.string.titleui_off), pl.accentOn, { c(pl.copy(accentOn = !pl.accentOn)) }, Modifier.testTag("title:accent"))
        }
        if (pl.accentOn) ColorPicker(pl.accent, "accentColor", onLive = { l(pl.copy(accent = it)) }) { c(pl.copy(accent = it)) }
    }
}

/** The drop shadow: on/off, color, opacity, angle, distance, softness and size – live in the picture while dragging. */
@Composable
private fun TitleShadowSection(state: EditorState, p: ClipProps, look: TitleLook, backend: PropsBackend) {
    val scope = rememberCoroutineScope()
    val sh = look.shadowOrDefault
    fun live(n: TitleShadow) = state.setLive((state.live?.takeIf { it.id == p.id } ?: p).copy(titleLook = look.copy(shadow = n).encode()))
    fun commit(n: TitleShadow) { backend.setTitle(p.id, look = look.copy(shadow = n).encode()); scope.launch { state.endLive() } }
    Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.titleui_shadow)) }
        SpringChip(stringResource(if (sh.on) R.string.titleui_on else R.string.titleui_off), sh.on,
            { commit(if (sh.on) TitleShadow.OFF else TitleShadow()) },
            Modifier.testTag("title:shadow"))
    }
    if (!sh.on) return
    val d = TitleShadow()
    PropSlider(stringResource(R.string.titleui_opacity), sh.opacity * 100f, 0f, 100f, d.opacity * 100f, { "%.0f %%".format(it) }, "shOpacity", lane = false,
        onLive = { live(sh.copy(opacity = it / 100f)) }) { commit(sh.copy(opacity = it / 100f)) }
    PropSlider(stringResource(R.string.titleui_angle), sh.angle, -180f, 180f, d.angle, { "%.0f°".format(it) }, "shAngle", lane = false,
        onLive = { live(sh.copy(angle = it)) }) { commit(sh.copy(angle = it)) }
    PropSlider(stringResource(R.string.titleui_distance), sh.distance * 100f, 0f, 50f, d.distance * 100f, { "%.1f".format(it) }, "shDistance", lane = false,
        onLive = { live(sh.copy(distance = it / 100f)) }) { commit(sh.copy(distance = it / 100f)) }
    PropSlider(stringResource(R.string.titleui_softness), sh.blur * 100f, 0f, 50f, d.blur * 100f, { "%.1f".format(it) }, "shBlur", lane = false,
        onLive = { live(sh.copy(blur = it / 100f)) }) { commit(sh.copy(blur = it / 100f)) }
    PropSlider(stringResource(R.string.titleui_spread), sh.spread * 100f, 0f, 20f, 0f, { "%.1f".format(it) }, "shSpread", lane = false,
        onLive = { live(sh.copy(spread = it / 100f)) }) { commit(sh.copy(spread = it / 100f)) }
    SectionLabel(stringResource(R.string.titleui_shadow_color))
    ColorPicker(sh.color, "shadowColor", onLive = { live(sh.copy(color = it)) }) { commit(sh.copy(color = it)) }
}

@Composable
fun ListRow(time: String, text: String, tag: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, color = K.OnSurfaceVar, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(96.dp))
        Text(text, color = K.OnSurface, fontSize = 14.sp, maxLines = 2)
    }
}

/**
 * Auto captions: speech recognition on the phone (whisper.cpp, KB-Whisper for Swedish). Pick a language and an
 * audio track; the model is downloaded the first time. The result is a regular caption track that can be corrected.
 */
@Composable
private fun AutoCaptionsCard(controller: se.gendut.klipp.engine.ProjectController) {
    val haptics = rememberHaptics()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ac = se.gendut.klipp.engine.AutoCaptions
    val pick = rememberSpeechPick()
    val tracks = controller.snapshot.sequence?.audio.orEmpty()
    // the first audio track with clips (usually the camera audio on A1)
    var track by remember(tracks.size) { mutableStateOf(tracks.indexOfFirst { it.clips.isNotEmpty() }.coerceAtLeast(0)) }
    PanelCard(Modifier.padding(vertical = 6.dp), title = stringResource(R.string.titleui_auto_captions_title)) {
        Text(
            stringResource(R.string.titleui_auto_captions_info),
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
        )
        SpeechPicker(pick, "asr")
        val model = pick.model
        val downloaded = remember(model, ac.modelRevision, ac.running) { se.gendut.klipp.engine.SpeechModels.ready(ctx, model) }
        // the track's files already transcribed (text-based editing): the captions are made from that, no model needed
        val transcribed by androidx.compose.runtime.produceState(false, track, controller.revision, controller.transcriptRevision) {
            val items = tracks.getOrNull(track)?.clips.orEmpty().map { it.item }.filter { it > 0 }.distinct()
            value = items.isNotEmpty() && items.all { controller.mediaWords(it).isNotEmpty() }
        }
        val ready = downloaded || transcribed
        if (tracks.size > 1) {
            // which audio track the speech is on (the camera's sound, usually A1 – music or effects elsewhere don't become text)
            SectionLabel(stringResource(R.string.titleui_speech_from_track))
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, t) in tracks.withIndex()) ToggleChip(if (t.clips.isEmpty()) stringResource(R.string.titleui_track_empty, i + 1) else "A${i + 1}", track == i, { track = i }, Modifier.testTag("asr:track$i"))
            }
        }
        val p = ac.progress
        if (p != null) {
            Text(ac.phase, color = K.OnSurface, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            androidx.compose.material3.LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), color = K.Primary)
            PillButton(stringResource(R.string.titleui_cancel), { haptics.tick(); ac.cancel() }, Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("asr:cancel"), icon = Ic.Close, filled = false)
        } else if (!ready) {
            PillButton(stringResource(R.string.titleui_download_model, model.bytes / 1_000_000), { haptics.confirm(); ac.download(ctx, model) },
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("asr:download"), icon = Ic.Download, filled = false)
        } else {
            // a run replaces the previous automatic captions (their lines, edits included): said before, not after
            val autoName = stringResource(R.string.transcribe_track_name)
            val old = controller.captionTracks.filter { it.name == autoName }.sumOf { it.count }
            var confirm by remember { mutableStateOf(false) }
            PillButton(stringResource(R.string.titleui_generate_captions), { haptics.confirm(); if (old > 0) confirm = true else ac.start(ctx, controller, model, pick.language, track) },
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("asr:start"), icon = Ic.Subs)
            if (confirm) androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text(stringResource(R.string.titleui_replace_auto_title)) },
                text = { Text(stringResource(R.string.titleui_replace_auto_body, old, autoName)) },
                confirmButton = { FlatTextButton({ confirm = false; ac.start(ctx, controller, model, pick.language, track) }, Modifier.testTag("asr:replace")) { Text(stringResource(R.string.titleui_replace)) } },
                dismissButton = { FlatTextButton({ confirm = false }) { Text(stringResource(R.string.titleui_cancel)) } },
                containerColor = K.SurfaceHigh,
            )
        }
        ac.message?.let { Text(it, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp).testTag("asr:msg")) }
    }
}

/**
 * The title follows a tracked object: draw a box around it in the picture and it is tracked right here (a tracked
 * window on the clip under the title, which grades nothing), or pick a window already tracked in Color. The title stays
 * where you put it when it starts and then moves with the object.
 */
@Composable
private fun FollowSection(state: EditorState, p: ClipProps, gs: GradeState) {
    val haptics = rememberHaptics()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val title = state.clips.firstOrNull { it.id == p.id } ?: return
    val t0 = title.start; val t1 = title.start + title.length
    // tracked windows on video clips that lie under the title at some point
    val options = state.clips.filter { it.kind == ClipKind.VIDEO && it.start < t1 && it.start + it.length > t0 }.flatMap { c ->
        gs.grades[c.id]?.nodes.orEmpty().filter { it.win.on && it.win.track != null }.map { n ->
            se.gendut.klipp.engine.Follow(c.id, n.id) to (if (n.label.isNotBlank()) "${c.name} · ${Str.name(n.label)}" else Str.t(R.string.titleui_follow_node, c.name, n.id))
        }
    }
    val cur = gs.follows[p.id]
    // a window that only exists to be tracked (it grades nothing) can go; a graded node stays
    fun removable(f: se.gendut.klipp.engine.Follow) = gs.grades[f.clip]?.nodes?.firstOrNull { it.id == f.node }?.let { it.enabled && it.isNeutral } == true
    fun removeTrack(f: se.gendut.klipp.engine.Follow) {
        gs.follows.filter { it.value.clip == f.clip && it.value.node == f.node }.keys.toList().forEach { gs.setFollow(it, null) }
        val g = gs.of(f.clip)
        gs.set(f.clip, g.copy(nodes = g.nodes.filterNot { it.id == f.node }.ifEmpty { listOf(se.gendut.klipp.engine.GradeNode(1)) }))
    }
    SectionLabel(stringResource(R.string.titleui_follow_object))
    val busy = TrackService.target?.let { (c, n) -> cur?.clip == c && cur.node == n } == true
    // the clip the box is drawn on: the topmost visible video under the title at the playhead (inside the title)
    val at = state.playhead.coerceIn(t0, t1 - 0.001f)
    val under = state.clips.filter { it.kind == ClipKind.VIDEO && it.enabled && state.trackStates.getOrNull(it.track)?.enabled != false && at >= it.start && at < it.start + it.length }.minByOrNull { it.track }
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            PillButton(stringResource(R.string.titleui_cancel), { TrackService.cancel() }, Modifier.testTag("follow:cancel"), icon = Ic.Close, filled = false)
        } else {
            PillButton(stringResource(if (state.boxPick != null) R.string.titleui_follow_drawing else R.string.titleui_follow_draw), {
                haptics.tick()
                val c = under?.let { gs.clipInfo?.invoke(it.id) }
                if (c == null || c.uri == null) { TrackService.message = Str.t(R.string.titleui_follow_no_clip); return@PillButton }
                state.playing = false
                state.playhead = at
                state.boxPick = { fx, fy, frx, fry ->
                    haptics.confirm()
                    // drawn on the finished picture: back through the clip's own zoom/move to what the tracker sees
                    val (cx, cy, rx, ry) = se.gendut.klipp.engine.TitleFollow.toClip(c.id, at.toDouble(), fx, fy, frx, fry)?.toList()
                        ?: listOf(fx, fy, frx, fry)
                    // tracking anew replaces this title's old track
                    val old = cur?.takeIf { removable(it) }
                    if (old != null) removeTrack(old)
                    val g = gs.of(c.id)
                    val id = (g.nodes.maxOfOrNull { it.id } ?: 0) + 1 + (old?.node ?: 0)
                    val node = se.gendut.klipp.engine.GradeNode(id, Str.t(R.string.titleui_follow_node_label, title.name),
                        win = se.gendut.klipp.engine.Window(shape = 2, cx = cx, cy = cy, rx = rx, ry = ry, soft = 0f))
                    gs.set(c.id, g.copy(nodes = g.nodes + node))
                    gs.setFollow(p.id, se.gendut.klipp.engine.Follow(c.id, id))
                    TrackService.start(ctx, gs, c, id, at, follow = { t -> state.playing = false; state.playhead = t })
                }
            }, Modifier.testTag("follow:draw"), icon = Ic.Target, on = state.boxPick != null)
        }
    }
    if (!busy && state.boxPick == null) TrackDensityRow()
    TrackService.progress?.takeIf { busy }?.let { pr ->
        androidx.compose.material3.LinearProgressIndicator(progress = { pr }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = K.Primary)
    }
    TrackService.message?.takeIf { !busy }?.let { Text(it, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("follow:msg")) }
    if (options.isEmpty() && cur == null) {
        Text(stringResource(R.string.titleui_follow_hint),
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        return
    }
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleChip(stringResource(R.string.titleui_follow_none), cur == null, { haptics.tick(); gs.setFollow(p.id, null) }, Modifier.testTag("follow:none"))
        // the same window keeps the scale/turn choices when picked again
        for ((f, label) in options) {
            val on = cur?.clip == f.clip && cur.node == f.node
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToggleChip(label, on, { haptics.tick(); gs.setFollow(p.id, if (on) cur else f.copy(scale = cur?.scale == true, rotate = cur?.rotate == true)) }, Modifier.testTag("follow:${f.clip}:${f.node}"))
                if (removable(f)) Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable { haptics.confirm(); removeTrack(f) }.testTag("follow:delete:${f.clip}:${f.node}"), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(Ic.Delete, stringResource(R.string.titleui_follow_delete), tint = K.OnSurfaceVar, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    // the title also grows/shrinks and turns as the object does
    if (cur != null) Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleChip(stringResource(R.string.titleui_follow_scale), cur.scale, { haptics.tick(); gs.setFollow(p.id, cur.copy(scale = !cur.scale)) }, Modifier.testTag("follow:scale"))
        ToggleChip(stringResource(R.string.titleui_follow_rotate), cur.rotate, { haptics.tick(); gs.setFollow(p.id, cur.copy(rotate = !cur.rotate)) }, Modifier.testTag("follow:rotate"))
    }
}
