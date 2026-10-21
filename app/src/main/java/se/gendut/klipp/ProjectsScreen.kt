package se.gendut.klipp

import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A project shown on the projects page (decoupled from the engine, so the page can be tested). */
data class ProjectCard(
    val id: String, val name: String, val modified: Long, val duration: Double,
    val width: Int, val height: Int, val fps: Double, val clips: Int, val thumb: File?, val current: Boolean,
)

/** What the projects page needs from the app. */
interface ProjectsBackend {
    val revision: Int
    fun list(): List<ProjectCard>
    fun open(id: String)
    fun create(spec: NewProjectSpec)
    fun rename(id: String, name: String)
    fun duplicate(id: String)
    fun delete(id: String)
}

/**
 * A new project: name, format (null = like the first clip), frame rate (null = like the first clip),
 * audio sample rate and channels, and the project's default export options.
 */
data class NewProjectSpec(
    val name: String,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val sampleRate: Int = 48_000,
    val mono: Boolean = false,
    val export: se.gendut.klipp.engine.ExportSettings = se.gendut.klipp.engine.ExportSettings(),
)

/**
 * Resolution preset (the null preset = like the first clip). [key] is stable (Swedish, used in test tags and
 * for selection); [label] and [hint] are localized when read.
 */
data class Preset(val key: String, @StringRes val labelRes: Int, val w: Int, val h: Int, private val hintText: String, @StringRes val hintRes: Int = 0) {
    val label: String get() = Str.t(labelRes)
    val hint: String get() = if (hintRes != 0) Str.t(hintRes) else hintText
}

val PRESETS = listOf(
    Preset("Liggande HD", R.string.projectsscreen_preset_landscape_hd, 1920, 1080, "16:9 · 1920×1080"),
    Preset("Liggande 4K", R.string.projectsscreen_preset_landscape_4k, 3840, 2160, "16:9 · 3840×2160"),
    Preset("Liggande 720p", R.string.projectsscreen_preset_landscape_720p, 1280, 720, "16:9 · 1280×720"),
    Preset("Stående", R.string.projectsscreen_preset_vertical, 1080, 1920, "9:16 · Reels, TikTok, Shorts"),
    Preset("Porträtt 4:5", R.string.projectsscreen_preset_portrait, 1080, 1350, "", R.string.projectsscreen_preset_portrait_hint),
    Preset("Kvadrat", R.string.projectsscreen_preset_square, 1080, 1080, "1:1"),
    Preset("Bio 2.39", R.string.projectsscreen_preset_cinema_239, 1920, 804, "2.39:1 · cinemascope"),
    Preset("Liggande 1440p", R.string.projectsscreen_preset_landscape_1440p, 2560, 1440, "16:9 · 2560×1440"),
    Preset("Stående 4K", R.string.projectsscreen_preset_vertical_4k, 2160, 3840, "9:16 · 2160×3840"),
    Preset("DCI 4K", R.string.projectsscreen_preset_dci_4k, 4096, 2160, "", R.string.projectsscreen_preset_dci_4k_hint),
    Preset("Bio 2.39 4K", R.string.projectsscreen_preset_cinema_239_4k, 3840, 1608, "2.39:1 · 3840×1608"),
    Preset("Flat 1.85", R.string.projectsscreen_preset_flat_185, 1998, 1080, "", R.string.projectsscreen_preset_flat_185_hint),
    Preset("Klassisk 4:3", R.string.projectsscreen_preset_classic, 1440, 1080, "4:3 · 1440×1080"),
)
val FRAME_RATES = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 48.0, 50.0, 59.94, 60.0, 100.0, 120.0)
val SEQ_SAMPLE_RATES = listOf(44_100, 48_000, 96_000)

/** 29.97 → "29.97" ("29,97" in Swedish), 25.0 → "25" (decimal separator by locale). */
fun fpsLabel(f: Double): String = "%.3f".format(f).trimEnd('0').trimEnd(',', '.')

private fun durLabel(sec: Double): String {
    val s = sec.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** "today 12:07", "yesterday 12:07" or "21 Oct" – in the app's locale. */
private fun dateLabel(ms: Long): String {
    val now = System.currentTimeMillis()
    val days = (now - ms) / 86_400_000
    val locale = java.util.Locale.getDefault()
    val date = java.util.Date(ms)
    fun fmt(skeleton: String) = java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
    return when {
        days < 1 -> Str.t(R.string.projectsscreen_today_at, fmt("jmm"))
        days < 2 -> Str.t(R.string.projectsscreen_yesterday_at, fmt("jmm"))
        else -> fmt("dMMM")
    }
}

/** The projects page: all projects as cards with a thumbnail, new project at the top. Tap = open, long-press = more. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectsScreen(backend: ProjectsBackend, onClose: () -> Unit) {
    val haptics = rememberHaptics()
    // the project list is read from disk – on the IO thread, not in composition (the previous list shows until the new one is ready)
    val projects by produceState(emptyList<ProjectCard>(), backend, backend.revision) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { backend.list() }
    }
    var creating by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ProjectCard?>(null) }
    var renaming by remember { mutableStateOf<ProjectCard?>(null) }
    var deleting by remember { mutableStateOf<ProjectCard?>(null) }
    Column(Modifier.fillMaxSize().background(K.Bg).windowInsetsPadding(WindowInsets.safeDrawing).testTag("projects")) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Ic.Back, stringResource(R.string.projectsscreen_back)) { onClose() }
            Text(stringResource(R.string.projectsscreen_title), color = K.OnSurface, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
        LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.weight(1f).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                NewProjectCard { haptics.confirm(); creating = true }
            }
            items(projects, key = { it.id }) { p ->
                ProjectTile(p,
                    onOpen = { haptics.confirm(); backend.open(p.id); onClose() },
                    onMenu = { haptics.heavy(); menuFor = p })
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(40.dp)) }
        }
    }
    if (creating) NewProjectDialog(onDismiss = { creating = false }) { spec ->
        creating = false; backend.create(spec); onClose()
    }
    menuFor?.let { p ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(p.name) },
            text = {
                Column {
                    MenuRow(stringResource(R.string.projectsscreen_open)) { menuFor = null; backend.open(p.id); onClose() }
                    MenuRow(stringResource(R.string.projectsscreen_rename)) { menuFor = null; renaming = p }
                    MenuRow(stringResource(R.string.projectsscreen_duplicate)) { menuFor = null; backend.duplicate(p.id) }
                    if (!p.current) MenuRow(stringResource(R.string.projectsscreen_delete), danger = true) { menuFor = null; deleting = p }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text(stringResource(R.string.projectsscreen_close)) } },
            containerColor = K.SurfaceHigh,
        )
    }
    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.projectsscreen_rename)) },
            text = { TextInput(stringResource(R.string.projectsscreen_name), name) { name = it } },
            confirmButton = { TextButton(onClick = { renaming = null; backend.rename(p.id, name.ifBlank { p.name }) }) { Text(stringResource(R.string.projectsscreen_save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.projectsscreen_cancel)) } },
            containerColor = K.SurfaceHigh,
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.projectsscreen_delete_title, p.name)) },
            text = { Text(stringResource(R.string.projectsscreen_delete_text)) },
            confirmButton = { TextButton(onClick = { deleting = null; backend.delete(p.id) }) { Text(stringResource(R.string.projectsscreen_delete), color = Color(0xFFFFB4AB)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.projectsscreen_cancel)) } },
            containerColor = K.SurfaceHigh,
        )
    }
}

@Composable
private fun MenuRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = if (danger) Color(0xFFFFB4AB) else K.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
    )
}

@Composable
private fun NewProjectCard(onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).graphicsLayer { scaleX = press.value; scaleY = press.value }
            .clip(RoundedCornerShape(28.dp)).background(K.Primary)
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                scope.launch { press.snapTo(0.95f); press.animateTo(1f, Springs.pop()) }
                onClick()
            }
            .padding(20.dp)
            .testTag("newProject"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Plus, null, tint = K.OnPrimary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.projectsscreen_new_project), color = K.OnPrimary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(stringResource(R.string.projectsscreen_new_project_hint), color = K.OnPrimary.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectTile(p: ProjectCard, onOpen: () -> Unit, onMenu: () -> Unit) {
    val img by produceState<ImageBitmap?>(null, p.thumb, p.modified) {
        value = p.thumb?.let { f -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() } }
    }
    val border by animateColorAsState(if (p.current) K.Primary else K.OutlineVar, label = "pb")
    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).background(K.Surface)
            .border(if (p.current) 2.dp else 1.dp, border, RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .testTag("project:${p.id}"),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(K.SurfaceHighest)) {
            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (p.current) Text(
                stringResource(R.string.projectsscreen_open_badge), color = K.OnPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(K.Primary).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            if (p.duration > 0) Text(
                durLabel(p.duration), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(Modifier.padding(10.dp)) {
            Text(p.name, color = K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(dateLabel(p.modified), if (p.width > 0) "${p.width}×${p.height}" else null, if (p.fps > 0) "${fpsLabel(p.fps)}p" else null).joinToString(" · "),
                color = K.OnSurfaceVar, fontSize = 11.sp, maxLines = 1,
            )
        }
    }
}

/** New project: name, format (or "like the first clip") and frame rate. */
@Composable
fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (NewProjectSpec) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf<Preset?>(null) }
    var custom by remember { mutableStateOf(false) }
    var cw by remember { mutableStateOf("1920") }
    var ch by remember { mutableStateOf("1080") }
    var fps by remember { mutableStateOf<Double?>(null) }
    var sr by remember { mutableStateOf(48_000) }
    var mono by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(se.gendut.klipp.engine.ExportSettings.load(ctx, null)) }
    var showExport by remember { mutableStateOf(false) }
    // custom format: even dimensions between 16 and 8192 (the encoders require even sizes)
    fun dim(t: String) = t.toIntOrNull()?.takeIf { it in 16..8192 }?.let { it / 2 * 2 }
    // "Like the first clip": the frame rate can follow the clip too; otherwise 25 until one is picked
    val autoFps = !custom && preset == null
    val shownFps = if (autoFps) fps else fps ?: 25.0
    val size: Pair<Int, Int>? = if (custom) dim(cw)?.let { w -> dim(ch)?.let { h -> w to h } } else preset?.let { it.w to it.h }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.projectsscreen_new_project)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                TextInput(stringResource(R.string.projectsscreen_name), name) { name = it }
                FormatPicker(preset, custom, auto = true) { p, c -> preset = p; custom = c }
                if (custom) {
                    TextInput(stringResource(R.string.projectsscreen_width), cw) { cw = it.filter(Char::isDigit).take(4) }
                    TextInput(stringResource(R.string.projectsscreen_height), ch) { ch = it.filter(Char::isDigit).take(4) }
                    if (size == null) Text(stringResource(R.string.projectsscreen_size_error), color = K.Error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
                }
                FpsPicker(shownFps, auto = autoFps) { fps = it }
                SampleRatePicker(sr) { sr = it }
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpringChip(stringResource(R.string.projectsscreen_stereo), !mono, { mono = false }, selectedColor = K.Primary, selectedText = K.OnPrimary)
                    SpringChip(stringResource(R.string.projectsscreen_mono), mono, { mono = true }, selectedColor = K.Primary, selectedText = K.OnPrimary)
                }
                SectionLabel(stringResource(R.string.projectsscreen_export))
                PresetRow(stringResource(if (showExport) R.string.projectsscreen_hide_export_settings else R.string.projectsscreen_export_settings),
                    stringResource(R.string.projectsscreen_export_settings_hint), showExport) { showExport = !showExport }
                if (showExport) {
                    val (w, h) = size ?: (1920 to 1080)
                    ExportOptions(export.copy(hdr = false), w, h, shownFps ?: 60.0, hdrPossible = false,
                        hdrBlocked = stringResource(R.string.settings_hdr_at_export)) { export = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val f = if (size != null) shownFps ?: 25.0 else fps
                    onCreate(NewProjectSpec(name, size?.first, size?.second, f, sr, mono, export))
                },
                enabled = !custom || size != null,
                modifier = Modifier.testTag("create"),
            ) { Text(stringResource(R.string.projectsscreen_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.projectsscreen_cancel)) } },
        containerColor = K.SurfaceHigh,
    )
}

/** Format: the presets, "like the first clip" ([auto]) and a custom format. onPick(preset, custom). */
@Composable
fun FormatPicker(selected: Preset?, custom: Boolean, auto: Boolean, onPick: (Preset?, Boolean) -> Unit) {
    val opts = buildList {
        if (auto) add(Opt("auto", Str.t(R.string.projectsscreen_like_first_clip), Str.t(R.string.projectsscreen_like_first_clip_hint), key = "auto"))
        PRESETS.forEach { add(Opt(it.key, it.label, it.hint, key = it.key)) }
        add(Opt("custom", Str.t(R.string.projectsscreen_custom_format), Str.t(R.string.projectsscreen_custom_format_hint), key = "custom"))
    }
    val sel = if (custom || (!auto && selected == null)) "custom" else selected?.key ?: "auto"
    DropField(stringResource(R.string.projectsscreen_format), opts, sel, "fmt") { v ->
        when (v) {
            "auto" -> onPick(null, false)
            "custom" -> onPick(null, true)
            else -> onPick(PRESETS.first { it.key == v }, false)
        }
    }
}

/** Frame rate; with [auto] also "like the first clip" (null). */
@Composable
fun FpsPicker(selected: Double?, auto: Boolean, onPick: (Double?) -> Unit) {
    val opts = buildList {
        if (auto) add(Opt<Double?>(null, Str.t(R.string.projectsscreen_like_first_clip), key = "auto"))
        FRAME_RATES.forEach { add(Opt<Double?>(it, Str.t(R.string.exportui_fps_value, fpsLabel(it)), fpsHint(it), key = fpsLabel(it))) }
    }
    val sel = selected?.let { f -> FRAME_RATES.firstOrNull { kotlin.math.abs(it - f) < 0.001 } } ?: selected
    DropField(stringResource(R.string.projectsscreen_frame_rate), opts, sel, "fps", onPick = onPick)
}

private fun fpsHint(f: Double): String? = when (f) {
    23.976, 24.0 -> Str.t(R.string.projectsscreen_fps_hint_film)
    25.0, 50.0 -> Str.t(R.string.projectsscreen_fps_hint_pal)
    29.97, 59.94 -> Str.t(R.string.projectsscreen_fps_hint_ntsc)
    30.0, 60.0 -> Str.t(R.string.projectsscreen_fps_hint_phones)
    48.0 -> Str.t(R.string.projectsscreen_fps_hint_hfr)
    100.0, 120.0 -> Str.t(R.string.projectsscreen_fps_hint_slowmo)
    else -> null
}

@Composable
fun SampleRatePicker(selected: Int, onPick: (Int) -> Unit) {
    val rates = (SEQ_SAMPLE_RATES + selected).distinct().sorted()
    val hint44 = stringResource(R.string.projectsscreen_sr_hint_44)
    val hint48 = stringResource(R.string.projectsscreen_sr_hint_48)
    val hint96 = stringResource(R.string.projectsscreen_sr_hint_96)
    DropField(stringResource(R.string.projectsscreen_sample_rate), rates.map { r ->
        Opt(r, srLabel(r), when (r) { 44_100 -> hint44; 48_000 -> hint48; 96_000 -> hint96; else -> null }, key = "sr$r")
    }, selected, "sr", onPick = onPick)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetRow(label: String, hint: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) K.Tonal else Color.Transparent, label = "pr")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .combinedClickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("preset:$label"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (on) K.PrimarySoft else K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(hint, color = K.OnSurfaceVar, fontSize = 11.sp)
        }
        if (on) Icon(Ic.Check, null, tint = K.PrimarySoft, modifier = Modifier.size(18.dp))
    }
}


/** Project settings for the open project. */
@Composable
fun ProjectSettingsDialog(
    name: String, width: Int, height: Int, fps: Double, sampleRate: Int,
    onDismiss: () -> Unit,
    /** Extra section at the end (e.g. proxy/cache). */
    extra: @Composable () -> Unit = {},
    onApply: (name: String, w: Int, h: Int, fps: Double, sampleRate: Int) -> Unit,
) {
    var n by remember { mutableStateOf(name) }
    var preset by remember { mutableStateOf(PRESETS.firstOrNull { it.w == width && it.h == height }) }
    var w by remember { mutableStateOf(width.toString()) }
    var h by remember { mutableStateOf(height.toString()) }
    var f by remember { mutableStateOf<Double?>(FRAME_RATES.minByOrNull { kotlin.math.abs(it - fps) }) }
    var sr by remember { mutableStateOf(sampleRate) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.projectsscreen_project_settings)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                TextInput(stringResource(R.string.projectsscreen_name), n) { n = it }
                FormatPicker(preset, preset == null, auto = false) { p, _ -> preset = p; if (p != null) { w = p.w.toString(); h = p.h.toString() } }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    Box(Modifier.weight(1f)) { TextInput(stringResource(R.string.projectsscreen_width), w) { w = it.filter(Char::isDigit).take(5); preset = null } }
                    Box(Modifier.weight(1f)) { TextInput(stringResource(R.string.projectsscreen_height), h) { h = it.filter(Char::isDigit).take(5); preset = null } }
                }
                FpsPicker(f, auto = false) { f = it }
                SampleRatePicker(sr) { sr = it }
                Text(
                    stringResource(R.string.projectsscreen_settings_note),
                    color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(8.dp),
                )
                extra()
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ww = (w.toIntOrNull() ?: width).coerceIn(16, 8192) / 2 * 2
                val hh = (h.toIntOrNull() ?: height).coerceIn(16, 8192) / 2 * 2
                onApply(n.ifBlank { name }, ww, hh, f ?: fps, sr)
            }, modifier = Modifier.testTag("applySettings")) { Text(stringResource(R.string.projectsscreen_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.projectsscreen_cancel)) } },
        containerColor = K.SurfaceHigh,
    )
}


@Composable
fun ProxySection(context: android.content.Context) {
    val p = se.gendut.klipp.engine.Proxies
    var size by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(p.revision) { size = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { p.size(context) } }
    SectionLabel(stringResource(R.string.projectsscreen_proxy_cache))
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SpringChip(stringResource(if (p.enabled) R.string.projectsscreen_proxy_on else R.string.projectsscreen_proxy_off), p.enabled, { p.switch(!p.enabled) }, Modifier.testTag("proxyToggle"), selectedColor = K.Primary, selectedText = K.OnPrimary)
        SpringChip(stringResource(R.string.projectsscreen_proxy_clear, size / 1e9), false, { p.clear(context); size = 0 }, Modifier.testTag("proxyClear"))
    }
    val proxyDefault = stringResource(R.string.projectsscreen_proxy_default)
    DropField(stringResource(R.string.projectsscreen_proxy_resolution), p.SIZES.map { Opt(it, if (it == 2160) "4K (2160p)" else "${it}p", if (it == Proxies_SHORT) proxyDefault else null, key = "ps$it") },
        p.shortSide, "proxySize") { p.setFormat(context, short = it) }
    DropField(stringResource(R.string.projectsscreen_proxy_quality), p.QUALITY_LABELS.mapIndexed { i, l -> Opt(i, l, stringResource(R.string.projectsscreen_proxy_quality_hint, p.bitrate(p.shortSide, i, false) / 1e6, p.shortSide), key = "pq$i") },
        p.quality, "proxyQuality") { p.setFormat(context, quality = it) }
    val ten = remember { se.gendut.klipp.engine.VideoPipeline.tenBitProfile(androidx.media3.common.MimeTypes.VIDEO_H265) != null }
    DropField(stringResource(R.string.exportui_bit_depth), listOf(
        Opt(0, stringResource(R.string.exportui_auto), stringResource(R.string.projectsscreen_proxy_bits_auto_hint), key = "pb0"),
        Opt(8, stringResource(R.string.exportui_bits_8), stringResource(R.string.projectsscreen_proxy_bits_8_hint), key = "pb8"),
        Opt(10, stringResource(R.string.exportui_bits_10), if (ten) "HEVC Main10" else stringResource(R.string.exportui_bits_no_ten), enabled = ten, key = "pb10"),
    ), p.bitDepth, "proxyBits") { p.setFormat(context, bits = it) }
    Text(
        stringResource(R.string.projectsscreen_proxy_note) +
            (if (p.progress.isNotEmpty()) " " + pluralStringResource(R.plurals.projectsscreen_proxy_progress, p.progress.size, p.progress.size) else ""),
        color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(8.dp),
    )
}

private const val Proxies_SHORT = se.gendut.klipp.engine.Proxies.SHORT_SIDE
