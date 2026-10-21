package se.gendut.klipp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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

/** Selected caption in the timeline: edit, split at the playhead, new, delete. Drag in the lane to move/trim. */
@Composable
fun CaptionBar(state: EditorState, modifier: Modifier) {
    val haptics = rememberHaptics()
    val c = state.selectedCaption?.let { id -> state.captions.firstOrNull { it.id == id } }
    AnimatedVisibility(
        visible = c != null && state.props != null && !state.editingCaption,
        enter = slideInVertically(spring(0.6f, 500f)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.testTag("captionBar"),
    ) {
        Row(
            Modifier.shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(K.SurfaceHighest)
                .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Subs, null, tint = K.Teal, modifier = Modifier.padding(start = 8.dp, end = 4.dp).size(20.dp))
            Text(
                c?.text?.replace('\n', ' ').orEmpty(), color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp).padding(end = 4.dp),
            )
            CaptionAction(Ic.Edit, stringResource(R.string.captionui_edit), "Redigera") { haptics.tick(); state.editingCaption = true }
            CaptionAction(Ic.Split, stringResource(R.string.captionui_split), "Dela") {
                val cap = c ?: return@CaptionAction
                if (state.playhead <= cap.start + 0.04f || state.playhead >= cap.end - 0.04f) { state.onNotice?.invoke(Str.t(R.string.captionui_playhead_inside)); return@CaptionAction }
                haptics.confirm(); state.props?.splitCaption(cap.id, state.playhead)
            }
            CaptionAction(Ic.Plus, stringResource(R.string.captionui_new), "Ny") {
                haptics.confirm()
                state.props?.addCaption(state.playhead) { id -> state.selectedCaption = id; state.editingCaption = true }
            }
            CaptionAction(Ic.Delete, stringResource(R.string.captionui_delete), "Ta bort") {
                val id = c?.id ?: return@CaptionAction
                haptics.heavy(); state.selectedCaption = null; state.props?.deleteCaption(id)
            }
            CaptionAction(Ic.Close, stringResource(R.string.captionui_deselect), "Avmarkera") { haptics.tick(); state.selectedCaption = null }
        }
    }
}

/** [tag] = the stable test tag ("cap:$tag"); it stays the same whatever the UI language. */
@Composable
private fun CaptionAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp).testTag("cap:$tag"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = K.OnSurface, modifier = Modifier.size(22.dp))
        Text(label, color = K.OnSurfaceVar, fontSize = 11.sp, style = IconLabel)
    }
}

/** Edit the selected caption: the text and the track's style (font, size, color, box, outline, position). */
@Composable
fun CaptionInspector(state: EditorState) {
    val backend = state.props ?: return
    val haptics = rememberHaptics()
    val c = state.selectedCaption?.let { id -> state.captions.firstOrNull { it.id == id } }
    Column(Modifier.fillMaxSize().background(K.Bg).verticalScroll(rememberScrollState()).testTag("captionInspector")) {
        PanelTitle(stringResource(R.string.captionui_caption)) { RoundIcon(Ic.Check, stringResource(R.string.captionui_done)) { haptics.tick(); state.editingCaption = false } }
        if (c == null) {
            Text(stringResource(R.string.captionui_select_caption), color = K.OnSurfaceVar, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            return@Column
        }
        CaptionTextField(c, backend)
        Text(
            stringResource(R.string.captionui_time_hint, formatTc(c.start), formatTc(c.end)),
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        CaptionStyleEditor(backend, c.track)
        Spacer(Modifier.height(120.dp))
    }
}

/** The text is saved once typing pauses for a moment, and when the panel closes. */
@Composable
private fun CaptionTextField(c: UiCaption, backend: PropsBackend) {
    var text by remember(c.id) { mutableStateOf(c.text) }
    val pending = remember(c.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(c.id, c.text) { if (pending.value == null && text != c.text) text = c.text }
    LaunchedEffect(pending.value) {
        val t = pending.value ?: return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        backend.setCaptionText(c.id, t)
        pending.value = null
    }
    DisposableEffect(c.id) {
        val id = c.id
        onDispose { pending.value?.let { backend.setCaptionText(id, it) } }
    }
    BasicTextField(
        value = text, onValueChange = { text = it; pending.value = if (it == c.text) null else it },
        textStyle = TextStyle(color = K.OnSurface, fontSize = 16.sp), cursorBrush = SolidColor(K.Primary),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(K.SurfaceHigh).padding(14.dp).testTag("captionText"),
    )
}

private val CAPTION_COLORS = listOf(0xFFFFFFFF, 0xFFFFE07A, 0xFFFFB77C, 0xFF9FE7D2, 0xFFFFD7F0, 0xFF111318)

/** The style applies to the whole caption track (as in FilmCraft/Premiere): every line looks the same. */
@Composable
fun CaptionStyleEditor(backend: PropsBackend, track: Long) {
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
    SectionLabel(stringResource(R.string.captionui_font))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in Fonts.builtIn.keys + Fonts.custom) FontTile(name, st.font == name) { set("font" to name) }
        Box(
            Modifier.height(56.dp).clip(RoundedCornerShape(16.dp)).background(K.SurfaceHigh)
                .clickable { haptics.tick(); fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) }
                .padding(horizontal = 14.dp).testTag("font:import"),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.captionui_import), color = K.PrimarySoft, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
    PropSlider(stringResource(R.string.captionui_size), st.size, 16f, 160f, 54f, { "%.0f px".format(it) }, "capSize", lane = false) { set("size" to it.toDouble()) }
    SectionLabel(stringResource(R.string.captionui_color))
    Swatches(st.color) { set("color" to CaptionStyle.hex(it)) }
    SectionLabel(stringResource(R.string.captionui_background))
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SpringChip(stringResource(R.string.captionui_bg_none), !st.background, { set("background" to false) }, selectedColor = K.Primary, selectedText = K.OnPrimary)
        SpringChip(stringResource(R.string.captionui_bg_semi), st.background && (st.backgroundColor ushr 24) < 250, { set("background" to true, "backgroundColor" to "#000000bf") }, selectedColor = K.Primary, selectedText = K.OnPrimary)
        SpringChip(stringResource(R.string.captionui_bg_solid), st.background && (st.backgroundColor ushr 24) >= 250, { set("background" to true, "backgroundColor" to "#000000ff") }, selectedColor = K.Primary, selectedText = K.OnPrimary)
    }
    val off = stringResource(R.string.captionui_off)
    PropSlider(stringResource(R.string.captionui_outline), st.outline, 0f, 12f, 0f, { if (it < 0.05f) off else "%.1f".format(it) }, "capOutline", lane = false) { set("outline" to it.toDouble()) }
    SectionLabel(stringResource(R.string.captionui_position))
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, l) in listOf("top" to stringResource(R.string.captionui_top), "middle" to stringResource(R.string.captionui_middle), "bottom" to stringResource(R.string.captionui_bottom))) SpringChip(l, st.anchor == k, { set("anchor" to k) }, selectedColor = K.Primary, selectedText = K.OnPrimary)
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, l) in listOf("left" to stringResource(R.string.captionui_left), "center" to stringResource(R.string.captionui_center), "right" to stringResource(R.string.captionui_right))) SpringChip(l, st.align == k, { set("align" to k) }, selectedColor = K.Primary, selectedText = K.OnPrimary)
    }
    PropSlider(stringResource(R.string.captionui_margin), st.margin * 100f, 0f, 45f, 8f, { "%.0f %%".format(it) }, "capMargin", lane = false) { set("margin" to (it / 100.0)) }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SpringChip(stringResource(R.string.captionui_reset_style), false, { set("reset" to true) })
    }
}

@Composable
private fun FontTile(name: String, selected: Boolean, onClick: () -> Unit) {
    val tf = remember(name) { androidx.compose.ui.text.font.FontFamily(Fonts.typeface(name)) }
    Column(
        Modifier.height(56.dp).clip(RoundedCornerShape(if (selected) 20.dp else 14.dp))
            .background(if (selected) K.Primary else K.SurfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = 14.dp).testTag("font:$name"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Aa", fontFamily = tf, fontSize = 20.sp, color = if (selected) K.OnPrimary else K.OnSurface)
        Text(Str.name(name), fontSize = 10.sp, color = if (selected) K.OnPrimary else K.OnSurfaceVar, maxLines = 1)
    }
}

@Composable
private fun Swatches(current: Int, onPick: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (c in CAPTION_COLORS) {
            val on = (current and 0xFFFFFF) == (c.toInt() and 0xFFFFFF)
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(if (on) 12.dp else 20.dp)).background(Color(c))
                    .border(if (on) 3.dp else 1.dp, if (on) K.Primary else K.OutlineVar, RoundedCornerShape(if (on) 12.dp else 20.dp))
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
    SectionLabel(stringResource(R.string.captionui_font))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in Fonts.builtIn.keys + Fonts.custom) FontTile(name, current == name) { haptics.tick(); onPick(name) }
        Box(
            Modifier.height(56.dp).clip(RoundedCornerShape(16.dp)).background(K.SurfaceHigh)
                .clickable { haptics.tick(); fontPicker.launch(arrayOf("*/*")) }.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.captionui_import), color = K.PrimarySoft, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}
