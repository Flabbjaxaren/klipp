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
import androidx.compose.foundation.horizontalScroll
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

/** Template card: a live preview with a name; selected = rounder shape and a bright border. */
@Composable
private fun TemplateTile(t: TitleTemplate, anim: TitleAnim, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    val r by animateDpAsState(if (selected) 22.dp else 14.dp, spring(0.5f, 400f), label = "tr")
    val border by animateColorAsState(if (selected) K.Primary else K.OutlineVar, label = "tb")
    Column(
        modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }
            .clip(RoundedCornerShape(r)).background(K.Surface)
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(r))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch { press.snapTo(0.93f); press.animateTo(1f, Springs.pop()) }
                onClick()
            }
            .padding(6.dp)
            .testTag("mall:${t.key}"),
    ) {
        TitlePreview(TitleLook(t, anim), "", Modifier.fillMaxWidth(), playing = selected)
        Text(t.label, color = if (selected) K.Primary else K.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
}

/** Text fields for the title's lines, by template. */
@Composable
private fun TitleFields(template: TitleTemplate, lines: List<String>, onChange: (List<String>) -> Unit) {
    fun at(i: Int) = lines.getOrElse(i) { "" }
    when (template) {
        TitleTemplate.LOWER_THIRD -> {
            TextInput(stringResource(R.string.titleui_field_name), at(0)) { onChange(listOf(it, at(1))) }
            TextInput(stringResource(R.string.titleui_field_role), at(1)) { onChange(listOf(at(0), it)) }
        }
        TitleTemplate.CHAPTER -> {
            TextInput(stringResource(R.string.titleui_field_heading), at(0)) { onChange(listOf(it, at(1))) }
            TextInput(stringResource(R.string.titleui_field_sub), at(1)) { onChange(listOf(at(0), it)) }
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
                        .clip(RoundedCornerShape(14.dp)).background(K.SurfaceHigh).padding(horizontal = 14.dp, vertical = 12.dp),
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
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (a in TitleAnim.entries) {
            SpringChip(a.label, anim == a, { onPick(a) }, Modifier.testTag("anim:${a.key}"), selectedColor = K.Primary, selectedText = K.OnPrimary)
        }
    }
}

/** Text mode: pick a template and how the title enters, watch it play, insert at the playhead. Captions below. */
@Composable
fun TitlesPanel(state: EditorState, onEdit: () -> Unit) {
    val backend = state.props ?: return
    val haptics = rememberHaptics()
    var template by remember { mutableStateOf(TitleTemplate.LOWER_THIRD) }
    var anim by remember { mutableStateOf(TitleAnim.BOUNCE) }
    var lines by remember { mutableStateOf(sampleText(TitleTemplate.LOWER_THIRD).split('\n')) }
    var replay by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("titles")) {
        PanelTitle(stringResource(R.string.titleui_panel_title))
        SectionLabel(stringResource(R.string.titleui_template))
        for (row in TitleTemplate.entries.chunked(2)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in row) TemplateTile(t, anim, template == t, Modifier.weight(1f)) {
                    haptics.confirm()
                    if (template != t) { template = t; lines = sampleText(t).split('\n') }
                    replay++
                }
            }
        }
        SectionLabel(stringResource(R.string.titleui_entrance))
        AnimChips(template, anim) { anim = it; replay++; haptics.tick() }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            TitlePreview(TitleLook(template, anim), lines.joinToString("\n"), Modifier.fillMaxWidth(), replayKey = replay)
        }
        SectionLabel(stringResource(R.string.titleui_content))
        TitleFields(template, lines) { lines = it }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            AddAtPlayhead(state) {
                haptics.confirm()
                val text = lines.joinToString("\n").trim('\n').ifBlank { Str.t(R.string.titleui_title) }
                backend.addTitle(text, state.playhead, TitleLook(template, anim).encode())
                onEdit()
            }
        }
        val titles = state.clips.filter { it.kind == ClipKind.TITLE }.sortedBy { it.start }
        if (titles.isNotEmpty()) {
            SectionLabel(stringResource(R.string.titleui_titles_on_timeline))
            for (t in titles) ListRow(formatTc(t.start), t.name) {
                haptics.tick()
                state.playing = false
                state.playhead = t.start
                state.selected = t.id
                state.inspecting = true
                onEdit()
            }
        }
        // text-based editing: delete words to cut the video
        (backend as? se.gendut.klipp.engine.ProjectController)?.let { TranscriptCard(it, state) }
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.titleui_captions)) }
            if (backend.hasCaptionTrack) {
                val vis = backend.captionsVisible
                SpringChip(stringResource(if (vis) R.string.titleui_captions_shown else R.string.titleui_captions_hidden), vis, { backend.setCaptionsVisible(!vis) }, selectedColor = K.Primary, selectedText = K.OnPrimary)
            }
        }
        (backend as? se.gendut.klipp.engine.ProjectController)?.let { AutoCaptionsCard(it) }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(stringResource(R.string.titleui_new_caption), {
                haptics.confirm()
                backend.addCaption(state.playhead) { id -> state.selectedCaption = id; state.editingCaption = true; onEdit() }
            }, modifier = Modifier.weight(1f))
            BigButton(stringResource(R.string.titleui_import_captions), { haptics.tick(); backend.importCaptions() }, color = K.SurfaceHigh, textColor = K.OnSurface, modifier = Modifier.weight(1f))
        }
        state.captions.firstOrNull()?.let { first ->
            var styleOpen by remember { mutableStateOf(false) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SpringChip(stringResource(if (styleOpen) R.string.titleui_hide_style else R.string.titleui_font_and_style), styleOpen, { haptics.tick(); styleOpen = !styleOpen }, selectedColor = K.Primary, selectedText = K.OnPrimary)
            }
            androidx.compose.animation.AnimatedVisibility(styleOpen) { Column { CaptionStyleEditor(backend, first.track) } }
        }
        if (state.captions.isEmpty()) {
            Text(
                stringResource(R.string.titleui_captions_hint),
                color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        var editing by remember { mutableStateOf<Long?>(null) }
        for (c in state.captions.take(300)) key(c.id) {
            ListRow(formatTc(c.start), c.text.replace('\n', ' ')) {
                haptics.tick(); state.playing = false; state.playhead = c.start
                editing = if (editing == c.id) null else c.id
            }
            androidx.compose.animation.AnimatedVisibility(editing == c.id) {
                CaptionEditor(c, backend) { editing = null }
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}

/** Its own recomposition scope: only the button follows the playhead, not the whole panel. */
@Composable
private fun AddAtPlayhead(state: EditorState, onClick: () -> Unit) {
    BigButton(stringResource(R.string.titleui_add_at, formatTc(state.playhead)), onClick)
}

/** The title's part of Properties: template, animation, text, size and color – changes show in the picture immediately. */
@Composable
fun TitleSection(state: EditorState, p: ClipProps, backend: PropsBackend) {
    val scope = rememberCoroutineScope()
    val look = TitleLook.parse(p.titleLook)
    LocalGradeState.current?.let { gs -> FollowSection(state, p, gs) }
    SectionLabel(stringResource(R.string.titleui_title))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in TitleTemplate.entries) {
            SpringChip(t.label, look.template == t, { backend.setTitle(p.id, look = look.copy(template = t).encode()) }, selectedColor = K.Primary, selectedText = K.OnPrimary)
        }
    }
    AnimChips(look.template, look.anim) { backend.setTitle(p.id, look = look.copy(anim = it).encode()) }
    TitleFontRow(look.font) { backend.setTitle(p.id, look = look.copy(font = it).encode()) }
    // bold, italic, all capitals
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SpringChip(stringResource(R.string.titleui_bold), look.bold, { backend.setTitle(p.id, look = look.copy(bold = !look.bold).encode()) }, Modifier.testTag("title:bold"), selectedColor = K.Primary, selectedText = K.OnPrimary)
        SpringChip(stringResource(R.string.titleui_italic), look.italic, { backend.setTitle(p.id, look = look.copy(italic = !look.italic).encode()) }, Modifier.testTag("title:italic"), selectedColor = K.Primary, selectedText = K.OnPrimary)
        SpringChip(stringResource(R.string.titleui_caps), look.caps, { backend.setTitle(p.id, look = look.copy(caps = !look.caps).encode()) }, Modifier.testTag("title:caps"), selectedColor = K.Primary, selectedText = K.OnPrimary)
    }
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
    TitleFields(look.template, lines) {
        lines = it
        val text = it.joinToString("\n")
        pending.value = if (text == p.title) null else text
        state.setLive((state.live?.takeIf { l -> l.id == p.id } ?: p).copy(title = text))
        if (pending.value == null) scope.launch { state.endLive() }
    }
    PropSlider(
        stringResource(R.string.titleui_size), p.textSize, 8f, 400f, sizeFor(look.template, p.seqH).roundToInt().toFloat(), { "%.0f px".format(it) }, "size",
        onLive = { v -> state.setLive((state.live?.takeIf { it.id == p.id } ?: p).copy(textSize = v)) },
    ) { backend.setTitle(p.id, size = it); scope.launch { state.endLive() } }
    SectionLabel(stringResource(R.string.titleui_text_color))
    ColorPicker(p.textColor and 0xFFFFFF, "titleColor",
        onLive = { c -> state.setLive((state.live?.takeIf { it.id == p.id } ?: p).copy(textColor = c)) },
    ) { c -> backend.setTitle(p.id, color = c); scope.launch { state.endLive() } }
    TitleShadowSection(state, p, look, backend)
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
            Modifier.testTag("title:shadow"), selectedColor = K.Primary, selectedText = K.OnPrimary)
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
fun ListRow(time: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, color = K.Primary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(96.dp))
        Text(text, color = K.OnSurface, fontSize = 14.sp, maxLines = 2)
    }
}

/** Edit a caption: the text (multiple lines) and delete. Tap the row again to close. */
@Composable
private fun CaptionEditor(c: UiCaption, backend: PropsBackend, onDone: () -> Unit) {
    val haptics = rememberHaptics()
    var text by remember(c.id) { mutableStateOf(c.text) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.Surface).padding(10.dp)) {
        BasicTextField(
            value = text, onValueChange = { text = it },
            textStyle = TextStyle(color = K.OnSurface, fontSize = 15.sp), cursorBrush = SolidColor(K.Primary),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.SurfaceHigh).padding(12.dp).testTag("caption:${c.id}"),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpringChip(stringResource(R.string.titleui_save), false, { haptics.confirm(); backend.setCaptionText(c.id, text); onDone() }, selectedColor = K.Primary, selectedText = K.OnPrimary)
            SpringChip(stringResource(R.string.titleui_delete), false, { haptics.heavy(); backend.deleteCaption(c.id); onDone() })
            Text("${formatTc(c.start)} – ${formatTc(c.end)}", color = K.OnSurfaceVar, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterVertically))
        }
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
        val ready = remember(model, ac.modelRevision, ac.running) { se.gendut.klipp.engine.SpeechModels.ready(ctx, model) }
        if (tracks.size > 1) {
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
            PillButton(stringResource(R.string.titleui_generate_captions), { haptics.confirm(); ac.start(ctx, controller, model, pick.language, track) },
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("asr:start"), icon = Ic.Subs)
        }
        ac.message?.let { Text(it, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp).testTag("asr:msg")) }
    }
}

/**
 * The title follows a tracked object: pick a tracked window on a clip under the title (Color mode → Window →
 * Track Object). The title stays where you put it when it starts and then moves with the object.
 */
@Composable
private fun FollowSection(state: EditorState, p: ClipProps, gs: GradeState) {
    val haptics = rememberHaptics()
    val title = state.clips.firstOrNull { it.id == p.id } ?: return
    val t0 = title.start; val t1 = title.start + title.length
    // tracked windows on video clips that lie under the title at some point
    val options = state.clips.filter { it.kind == ClipKind.VIDEO && it.start < t1 && it.start + it.length > t0 }.flatMap { c ->
        gs.grades[c.id]?.nodes.orEmpty().filter { it.win.on && it.win.track != null }.map { n -> se.gendut.klipp.engine.Follow(c.id, n.id) to Str.t(R.string.titleui_follow_node, c.name, n.id) }
    }
    val cur = gs.follows[p.id]
    SectionLabel(stringResource(R.string.titleui_follow_object))
    if (options.isEmpty() && cur == null) {
        Text(stringResource(R.string.titleui_follow_hint),
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        return
    }
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleChip(stringResource(R.string.titleui_follow_none), cur == null, { haptics.tick(); gs.setFollow(p.id, null) }, Modifier.testTag("follow:none"))
        for ((f, label) in options) ToggleChip(label, cur == f, { haptics.tick(); gs.setFollow(p.id, f) }, Modifier.testTag("follow:${f.encode()}"))
    }
}

