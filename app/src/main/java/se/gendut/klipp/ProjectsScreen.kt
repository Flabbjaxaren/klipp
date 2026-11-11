package se.gendut.klipp

import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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
    /** Write project [id] as a package (.klipp) to [to]; [withMedia] = with the media files. */
    fun export(id: String, withMedia: Boolean, to: android.net.Uri) {}
    /** Read a package as a new project. */
    fun import(from: android.net.Uri) {}
    /** Progress (0..1) of an export/import in progress; null = none. */
    val progress: Float? get() = null
    /** The project's earlier versions (time, clips), newest first. */
    fun versions(id: String): List<Pair<Long, Int>> = emptyList()
    /** Earlier version [stamp] of project [id] as a new project. */
    fun restoreVersion(id: String, stamp: Long) {}
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

/** "today 12:07", "yesterday 12:07" or "21 Oct" ([withTime]: "21 Oct 12:07") – in the app's locale. */
private fun dateLabel(ms: Long, withTime: Boolean = false): String {
    val now = System.currentTimeMillis()
    val days = (now - ms) / 86_400_000
    val locale = java.util.Locale.getDefault()
    val date = java.util.Date(ms)
    fun fmt(skeleton: String) = java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
    return when {
        days < 1 -> Str.t(R.string.projectsscreen_today_at, fmt("jmm"))
        days < 2 -> Str.t(R.string.projectsscreen_yesterday_at, fmt("jmm"))
        else -> fmt(if (withTime) "dMMMjmm" else "dMMM")
    }
}

/**
 * The projects page: the project last opened at the top with Continue, the others as thumbnails under it, New project
 * floating in the corner. Tap a picture = open; the dots (or a long press) = more. The magnifier searches by name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectsScreen(backend: ProjectsBackend, onSettings: (() -> Unit)? = null, onClose: () -> Unit) {
    val haptics = rememberHaptics()
    // the project list is read from disk – on the IO thread, not in composition (the previous list shows until the new one is ready)
    val projects by produceState(emptyList<ProjectCard>(), backend, backend.revision) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { backend.list() }
    }
    var creating by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ProjectCard?>(null) }
    var renaming by remember { mutableStateOf<ProjectCard?>(null) }
    var deleting by remember { mutableStateOf<ProjectCard?>(null) }
    var exporting by remember { mutableStateOf<ProjectCard?>(null) }
    var versionsOf by remember { mutableStateOf<ProjectCard?>(null) }
    // the project and choice waiting for the file picker (kept if the activity is recreated meanwhile)
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    val createDoc = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = pendingExport; pendingExport = null
        if (uri != null && p != null) backend.export(p.substringAfter(':'), p.startsWith("m:"), uri)
    }
    val openDoc = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { backend.import(it) }
    }
    // what is typed in the search field; null = not searching
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    val hero = projects.firstOrNull { it.current } ?: projects.firstOrNull()
    val shown = query?.trim().let { q ->
        when {
            q == null -> projects.filter { it.id != hero?.id }
            q.isEmpty() -> projects
            else -> projects.filter { it.name.contains(q, ignoreCase = true) }
        }
    }
    fun open(p: ProjectCard) { haptics.confirm(); backend.open(p.id); onClose() }
    // the system back leaves the search before it leaves the page
    androidx.activity.compose.BackHandler(query != null) { query = null }
    // (the empty pointer input keeps touches on the page's bare parts from reaching the editor under it)
    Box(Modifier.fillMaxSize().background(K.Bg).pointerInput(Unit) {}.testTag("projects")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val q = query
                if (q != null) {
                    SearchField(q, { query = it }, Modifier.weight(1f))
                    HeaderButton(Ic.Close, stringResource(R.string.projectsscreen_search_close), Color.Transparent, Modifier.testTag("searchClose")) { query = null }
                } else {
                    Text("Klipp", color = K.OnSurface, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.weight(1f))
                    HeaderButton(Ic.Search, stringResource(R.string.projectsscreen_search), Color.Transparent, Modifier.testTag("searchProjects")) { query = "" }
                    if (onSettings != null) HeaderButton(Ic.Gear, stringResource(R.string.app_app_settings), K.SurfaceHigh, Modifier.testTag("appSettings")) { onSettings() }
                }
            }
            backend.progress?.let { f ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("packProgress")) {
                    Text(stringResource(R.string.projectsscreen_copying, (f * 100).toInt()), color = K.OnSurfaceVar, fontSize = 13.sp)
                    androidx.compose.material3.LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().padding(top = 6.dp), color = K.Primary, trackColor = K.SurfaceHigh)
                }
            }
            LazyVerticalGrid(
                GridCells.Adaptive(160.dp), Modifier.weight(1f).testTag("projectGrid"),
                // (room for New project under the last row)
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (query == null) {
                    hero?.let { h ->
                        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                            HeroCard(h, onOpen = { open(h) }, onMenu = { haptics.heavy(); menuFor = h })
                        }
                    }
                    item(key = "all", span = { GridItemSpan(maxLineSpan) }) {
                        AllProjectsRow(projects.size) { haptics.tick(); openDoc.launch(arrayOf("*/*")) }
                    }
                }
                items(shown, key = { it.id }) { p ->
                    ProjectTile(p, onOpen = { open(p) }, onMenu = { haptics.heavy(); menuFor = p })
                }
                if (query != null && shown.isEmpty()) item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.projectsscreen_search_none), color = K.OnSurfaceVar, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp).testTag("searchNone"))
                }
            }
        }
        NewProjectButton(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 16.dp, bottom = 28.dp)) {
            haptics.confirm(); creating = true
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
                    MenuRow(stringResource(R.string.projectsscreen_export_project)) { menuFor = null; exporting = p }
                    MenuRow(stringResource(R.string.projectsscreen_versions)) { menuFor = null; versionsOf = p }
                    if (!p.current) MenuRow(stringResource(R.string.projectsscreen_delete), danger = true) { menuFor = null; deleting = p }
                }
            },
            confirmButton = { FlatTextButton(onClick = { menuFor = null }) { Text(stringResource(R.string.projectsscreen_close)) } },
            containerColor = K.SurfaceHigh,
        )
    }
    versionsOf?.let { p ->
        val list by produceState<List<Pair<Long, Int>>?>(null, p.id) { value = withContext(Dispatchers.IO) { backend.versions(p.id) } }
        AlertDialog(
            onDismissRequest = { versionsOf = null },
            title = { Text(stringResource(R.string.projectsscreen_versions_title, p.name)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    val l = list
                    when {
                        l == null -> Unit
                        l.isEmpty() -> Text(stringResource(R.string.projectsscreen_versions_none), color = K.OnSurfaceVar, fontSize = 14.sp)
                        else -> {
                            Text(stringResource(R.string.projectsscreen_versions_hint), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                            for ((stamp, clips) in l) {
                                ExportChoice(dateLabel(stamp, withTime = true), pluralStringResource(R.plurals.app_n_clips, clips, clips), "version:$stamp") {
                                    versionsOf = null; haptics.confirm(); backend.restoreVersion(p.id, stamp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { FlatTextButton(onClick = { versionsOf = null }) { Text(stringResource(R.string.projectsscreen_close)) } },
            containerColor = K.SurfaceHigh,
        )
    }
    exporting?.let { p ->
        fun go(withMedia: Boolean) {
            exporting = null
            pendingExport = (if (withMedia) "m:" else "p:") + p.id
            createDoc.launch(ProjectPackageName.of(p.name))
        }
        AlertDialog(
            onDismissRequest = { exporting = null },
            title = { Text(stringResource(R.string.projectsscreen_export_title, p.name)) },
            text = {
                Column {
                    ExportChoice(stringResource(R.string.projectsscreen_export_with_media), stringResource(R.string.projectsscreen_export_with_media_hint), "exportWithMedia") { go(true) }
                    ExportChoice(stringResource(R.string.projectsscreen_export_only), stringResource(R.string.projectsscreen_export_only_hint), "exportProjectOnly") { go(false) }
                }
            },
            confirmButton = { FlatTextButton(onClick = { exporting = null }) { Text(stringResource(R.string.projectsscreen_cancel)) } },
            containerColor = K.SurfaceHigh,
        )
    }
    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.projectsscreen_rename)) },
            text = { TextInput(stringResource(R.string.projectsscreen_name), name, focus = true) { name = it } },
            confirmButton = { FlatTextButton(onClick = { renaming = null; backend.rename(p.id, name.ifBlank { p.name }) }) { Text(stringResource(R.string.projectsscreen_save)) } },
            dismissButton = { FlatTextButton(onClick = { renaming = null }) { Text(stringResource(R.string.projectsscreen_cancel)) } },
            containerColor = K.SurfaceHigh,
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.projectsscreen_delete_title, p.name)) },
            text = { Text(stringResource(R.string.projectsscreen_delete_text)) },
            confirmButton = { FlatTextButton(onClick = { deleting = null; backend.delete(p.id) }) { Text(stringResource(R.string.projectsscreen_delete), color = K.Error) } },
            dismissButton = { FlatTextButton(onClick = { deleting = null }) { Text(stringResource(R.string.projectsscreen_cancel)) } },
            containerColor = K.SurfaceHigh,
        )
    }
}

/** The package's file name: the project's name (without characters file systems refuse) + .klipp. */
object ProjectPackageName {
    fun of(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Klipp" } + "." + se.gendut.klipp.engine.ProjectPackage.EXT
}

@Composable
private fun ExportChoice(title: String, hint: String, tag: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(K.SurfaceHighest)
            .combinedClickable(onClick = onClick).padding(14.dp).testTag(tag)
    ) {
        Text(title, color = K.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(hint, color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun MenuRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = if (danger) K.Error else K.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
    )
}

/** Behind a badge on a picture: dark enough for white text on any frame. */
private val Scrim = Color(0xBF0F1012)

/** A round button in the page's header. */
@Composable
private fun HeaderButton(icon: ImageVector, desc: String, bg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    Box(
        modifier.size(44.dp).pressPop(src, 0.85f).clip(CircleShape).background(bg)
            .clickable(interactionSource = src, indication = null, role = Role.Button) { haptics.press(); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = K.OnSurface, modifier = Modifier.size(22.dp)) }
}

/** The header's search field: it takes the keyboard when it appears. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = TextStyle(color = K.OnSurface, fontSize = 16.sp),
        cursorBrush = SolidColor(K.Primary),
        modifier = modifier.focusRequester(requester).testTag("searchField"),
        decorationBox = { inner ->
            Row(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(K.SurfaceHigh).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Ic.Search, null, tint = K.OnSurfaceVar, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(stringResource(R.string.projectsscreen_search), color = K.Outline, fontSize = 16.sp, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

@Composable
private fun rememberThumb(p: ProjectCard): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, p.thumb, p.modified) {
        value = p.thumb?.let { f -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() } }
    }
    return img
}

/** A project's picture: its thumbnail, or a film strip until it has one. Tap = open, long press = more. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectPicture(p: ProjectCard, corner: Int, modifier: Modifier, onOpen: () -> Unit, onMenu: () -> Unit, badges: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val img = rememberThumb(p)
    val label = stringResource(R.string.projectsscreen_open_named, p.name)
    Box(
        modifier.clip(RoundedCornerShape(corner.dp)).background(K.SurfaceHigh)
            .combinedClickable(role = Role.Button, onClick = onOpen, onLongClick = onMenu)
            .semantics { contentDescription = label }
            .testTag("project:${p.id}"),
    ) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (p.thumb == null) Icon(Ic.Film, null, tint = K.Outline, modifier = Modifier.align(Alignment.Center).size(28.dp))
        badges()
    }
}

/** The project last opened: a large picture, its name and facts, and Continue. */
@Composable
private fun HeroCard(p: ProjectCard, onOpen: () -> Unit, onMenu: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(K.Surface).padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProjectPicture(p, 20, Modifier.fillMaxWidth().height(180.dp), onOpen, onMenu) {
            Text(
                stringResource(R.string.projectsscreen_last_opened), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(12.dp)).background(Scrim).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = K.OnSurface, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // the facts shrink a little on a narrow phone before they are cut
                BasicText(
                    factsLabel(p), color = { K.OnSurfaceVar }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 13.sp, stepSize = 0.5.sp),
                )
            }
            // the page's play: the one filled accent
            val src = remember { MutableInteractionSource() }
            Row(
                Modifier.height(52.dp).pressPop(src, 0.94f).clip(RoundedCornerShape(20.dp)).background(K.Primary)
                    .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onOpen)
                    .padding(start = 16.dp, end = 22.dp).testTag("continue"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Ic.Play, null, tint = K.OnPrimary, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.projectsscreen_continue), color = K.OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** "1:12 · 1080p 25 · today 09:14": the length, the format and when it was last changed. */
private fun factsLabel(p: ProjectCard): String {
    val format = if (p.width > 0 && p.height > 0) {
        val long = maxOf(p.width, p.height); val short = minOf(p.width, p.height)
        // 16:9 either way up goes by its short side, anything else by its size
        (if (long * 9 == short * 16) "${short}p" else "${p.width}×${p.height}") + (if (p.fps > 0) " ${fpsLabel(p.fps)}" else "")
    } else null
    return listOfNotNull(if (p.duration > 0) durLabel(p.duration) else null, format, dateLabel(p.modified)).joinToString(" · ")
}

/** "All projects · 5" over the grid, with Import at its end. */
@Composable
private fun AllProjectsRow(count: Int, onImport: () -> Unit) {
    val all = stringResource(R.string.projectsscreen_all_projects)
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                append(all)
                withStyle(SpanStyle(color = K.OnSurfaceVar, fontWeight = FontWeight.Medium)) { append(" · $count") }
            },
            color = K.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onImport)
                .padding(horizontal = 12.dp).testTag("importProject"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Ic.Download, null, tint = K.OnSurface, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.projectsscreen_import), color = K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun ProjectTile(p: ProjectCard, onOpen: () -> Unit, onMenu: () -> Unit) {
    Column(Modifier.padding(bottom = 10.dp)) {
        ProjectPicture(p, 16, Modifier.fillMaxWidth().aspectRatio(1.83f), onOpen, onMenu) {
            if (p.duration > 0) Text(
                durLabel(p.duration), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Scrim).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Row(Modifier.height(44.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = K.OnSurface, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dateLabel(p.modified), color = K.OnSurfaceVar, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onMenu).testTag("projectMenu:${p.id}"),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.MoreVert, stringResource(R.string.projectsscreen_more_for, p.name), tint = K.OnSurface, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** New project, floating over the grid's corner. */
@Composable
private fun NewProjectButton(modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        modifier.pressPop(src, 0.94f).shadow(12.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(K.SurfaceHighest)
            .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
            .height(56.dp).padding(start = 16.dp, end = 22.dp).testTag("newProject"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Ic.Plus, null, tint = K.OnSurface, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.projectsscreen_new_project), color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
                    Row {
                        TextInput(stringResource(R.string.projectsscreen_width), cw, Modifier.weight(1f), stacked = true, number = true) { cw = it.filter(Char::isDigit).take(4) }
                        TextInput(stringResource(R.string.projectsscreen_height), ch, Modifier.weight(1f), stacked = true, number = true) { ch = it.filter(Char::isDigit).take(4) }
                    }
                    if (size == null) Text(stringResource(R.string.projectsscreen_size_error), color = K.Error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
                }
                FpsPicker(shownFps, auto = autoFps) { fps = it }
                SampleRatePicker(sr) { sr = it }
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpringChip(stringResource(R.string.projectsscreen_stereo), !mono, { mono = false })
                    SpringChip(stringResource(R.string.projectsscreen_mono), mono, { mono = true })
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
            FlatTextButton(
                onClick = {
                    val f = if (size != null) shownFps ?: 25.0 else fps
                    onCreate(NewProjectSpec(name, size?.first, size?.second, f, sr, mono, export))
                },
                enabled = !custom || size != null,
                modifier = Modifier.testTag("create"),
            ) { Text(stringResource(R.string.projectsscreen_create)) }
        },
        dismissButton = { FlatTextButton(onClick = onDismiss) { Text(stringResource(R.string.projectsscreen_cancel)) } },
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
    val bg by animateColorAsState(if (on) K.PrimaryContainer else Color.Transparent, label = "pr")
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
                Row {
                    TextInput(stringResource(R.string.projectsscreen_width), w, Modifier.weight(1f), stacked = true, number = true) { w = it.filter(Char::isDigit).take(5); preset = null }
                    TextInput(stringResource(R.string.projectsscreen_height), h, Modifier.weight(1f), stacked = true, number = true) { h = it.filter(Char::isDigit).take(5); preset = null }
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
            FlatTextButton(onClick = {
                val ww = (w.toIntOrNull() ?: width).coerceIn(16, 8192) / 2 * 2
                val hh = (h.toIntOrNull() ?: height).coerceIn(16, 8192) / 2 * 2
                onApply(n.ifBlank { name }, ww, hh, f ?: fps, sr)
            }, modifier = Modifier.testTag("applySettings")) { Text(stringResource(R.string.projectsscreen_save)) }
        },
        dismissButton = { FlatTextButton(onClick = onDismiss) { Text(stringResource(R.string.projectsscreen_cancel)) } },
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
        SpringChip(stringResource(if (p.enabled) R.string.projectsscreen_proxy_on else R.string.projectsscreen_proxy_off), p.enabled, { p.switch(!p.enabled) }, Modifier.testTag("proxyToggle"))
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
