package se.gendut.klipp

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.launch
import se.gendut.klipp.engine.SoundFx
import java.io.File

/**
 * Sound effects (CC0): Kenney's packs to download, and a search on Freesound with the user's own API key. A tap on ▶
 * plays a sound; "Add" places it at the playhead on the first free audio track ([onAdd]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundFxSheet(
    onDismiss: () -> Unit,
    /** As the edit page's Sound: the sheet is titled "Sound" and has text read aloud as a third tab. */
    speech: Boolean = false,
    onAdd: (File, String, Double) -> Unit,
) {
    val ctx = LocalContext.current
    val preview = remember { Preview(ctx) }
    DisposableEffect(Unit) { onDispose { preview.release() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = K.Surface) {
        var tab by remember { mutableStateOf(0) }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("sfxSheet")) {
            Text(stringResource(if (speech) R.string.design_add_sound else R.string.sfx_title), color = K.OnSurface, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 16.dp))
            if (tab < 2) Text(stringResource(R.string.sfx_license_note), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            val tabs = listOf(stringResource(R.string.sfx_tab_packs), "Freesound") +
                (if (speech) listOf(stringResource(R.string.sfx_tab_speech)) else emptyList()) + stringResource(R.string.sfx_tab_files)
            ConnectedToggleGroup(tabs, tab, { tab = it; preview.stop() },
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), tag = { "sfxTab:$it" })
            when (tab) {
                0 -> PacksTab(preview, onAdd)
                1 -> FreesoundTab(preview, onAdd)
                tabs.lastIndex -> FilesTab(onAdd)
                else -> TextToSpeechCard(onAdd)
            }
        }
    }
}

/** The phone's own music and sounds: a file is copied into the app (the project keeps working if it is moved) and added. */
@Composable
private fun FilesTab(onAdd: (File, String, Double) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { SoundFx.copyAudio(ctx, uri) } }
            busy = false
            r.onSuccess { (file, name, secs) -> onAdd(file, name, secs) }
                .onFailure { error = Str.t(R.string.sfx_file_failed) }
        }
    }
    Text(stringResource(R.string.sfx_files_hint), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    error?.let { Text(it, color = K.Error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("sfx:fileError")) }
    PillButton(stringResource(if (busy) R.string.sfx_file_busy else R.string.sfx_file_choose), { if (!busy) picker.launch(arrayOf("audio/*")) },
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("sfx:file"))
}

/** Plays one sound at a time (a file or a URL). */
private class Preview(ctx: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(ctx).build()
    var playing by mutableStateOf<String?>(null)
        private set

    fun toggle(key: String, uri: String) {
        if (playing == key) { stop(); return }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
        playing = key
    }

    fun stop() { player.stop(); playing = null }
    fun release() = player.release()
}

@Composable
private fun PacksTab(preview: Preview, onAdd: (File, String, Double) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val rev = SoundFx.revision
    var open by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val installedPacks = remember(rev) { SoundFx.PACKS.filter { SoundFx.installed(ctx, it) }.map { it.id }.toSet() }
    // (above the list, not in it: as the list's first row it appeared scrolled out of view)
    error?.let { e -> Text(e, color = K.Error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("sfx:error")) }
    LazyColumn(Modifier.fillMaxWidth()) {
        for (pack in SoundFx.PACKS) {
            val installed = pack.id in installedPacks
            val progress = SoundFx.progress[pack.id]
            item(key = pack.id) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = installed) { haptics.tick(); open = if (open == pack.id) null else pack.id }
                        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("sfxPack:${pack.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(pack.name, color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(pack.descRes) + " · " + stringResource(R.string.sfx_size_mb, pack.mb) + " · " + pack.source, color = K.OnSurfaceVar, fontSize = 12.sp)
                    }
                    when {
                        progress != null -> Text("${(progress * 100).toInt()} %", color = K.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        installed -> Text(if (open == pack.id) "▲" else "▼", color = K.OnSurfaceVar, fontSize = 14.sp)
                        else -> PillButton(stringResource(R.string.sfx_download), {
                            error = null
                            scope.launch {
                                SoundFx.download(ctx, pack).onSuccess { open = pack.id }
                                    .onFailure { error = Str.t(R.string.sfx_download_failed, Str.netError(it)) }
                            }
                        }, Modifier.testTag("sfxDownload:${pack.id}"))
                    }
                }
                if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = K.Primary)
            }
            if (installed && open == pack.id) {
                val sounds = SoundFx.sounds(ctx, pack.id)
                items(sounds, key = { it.file.path }) { s -> SoundRow(s.name, s.seconds, preview.playing == s.file.path, { preview.toggle(s.file.path, s.file.toURI().toString()) }, { onAdd(s.file, s.name, s.seconds) }) }
                item(key = pack.id + ":remove") {
                    Text(stringResource(R.string.sfx_remove_pack), color = K.Error, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable { haptics.heavy(); preview.stop(); SoundFx.remove(ctx, pack); open = null })
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun FreesoundTab(preview: Preview, onAdd: (File, String, Double) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("klipp", Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(prefs.getString("freesoundKey", "").orEmpty()) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SoundFx.Hit>>(emptyList()) }
    var busy by remember { mutableStateOf<Long?>(null) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    if (key.isBlank()) {
        var input by remember { mutableStateOf("") }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.sfx_freesound_key_intro), color = K.OnSurfaceVar, fontSize = 13.sp)
            val links = androidx.compose.ui.platform.LocalUriHandler.current
            Text("freesound.org/apiv2/apply", color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                modifier = Modifier.padding(vertical = 6.dp).clickable { runCatching { links.openUri("https://freesound.org/apiv2/apply") } })
            Field(input, { input = it }, stringResource(R.string.sfx_freesound_key_hint), "sfxKey")
            PillButton(stringResource(R.string.sfx_save), {
                prefs.edit().putString("freesoundKey", input.trim()).apply(); key = input.trim()
            }, Modifier.padding(vertical = 8.dp).testTag("sfxKeySave"))
        }
        return
    }
    fun search() {
        if (query.isBlank()) return
        searching = true; message = null
        scope.launch {
            SoundFx.search(key, query).onSuccess { hits = it; if (it.isEmpty()) message = Str.t(R.string.sfx_no_results) }
                .onFailure { message = Str.t(R.string.sfx_search_failed, Str.netError(it)) }
            searching = false
        }
    }
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Field(query, { query = it }, stringResource(R.string.sfx_search_hint), "sfxQuery", onDone = ::search) }
        Spacer(Modifier.width(8.dp))
        PillButton(stringResource(R.string.sfx_search), ::search, Modifier.testTag("sfxSearch"))
    }
    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = K.Primary)
    message?.let { Text(it, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
    val rev = SoundFx.revision
    val mine = remember(rev) { SoundFx.sounds(ctx, SoundFx.FREESOUND) }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(hits, key = { it.id }) { h ->
            SoundRow(h.name, h.seconds, preview.playing == "fs${h.id}", { preview.toggle("fs${h.id}", h.preview) }, {
                if (busy != null) return@SoundRow
                busy = h.id
                scope.launch {
                    SoundFx.fetch(ctx, h).onSuccess { onAdd(it, h.name, SoundFx.wavSeconds(it)) }
                        .onFailure { message = Str.t(R.string.sfx_download_failed, Str.netError(it)) }
                    busy = null
                }
            }, subtitle = h.user, busy = busy == h.id)
        }
        if (mine.isNotEmpty()) {
            item { Text(stringResource(R.string.sfx_downloaded), color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
            items(mine, key = { it.file.path }) { s -> SoundRow(s.name, s.seconds, preview.playing == s.file.path, { preview.toggle(s.file.path, s.file.toURI().toString()) }, { onAdd(s.file, s.name, s.seconds) }) }
        }
        item {
            Text(stringResource(R.string.sfx_forget_key), color = K.OnSurfaceVar, fontSize = 12.sp,
                modifier = Modifier.padding(16.dp).clickable { prefs.edit().remove("freesoundKey").apply(); key = "" })
        }
    }
}

@Composable
private fun SoundRow(name: String, seconds: Double, playing: Boolean, onPlay: () -> Unit, onAdd: () -> Unit, subtitle: String? = null, busy: Boolean = false) {
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(if (playing) K.PrimaryContainer else K.SurfaceHigh).clickable { haptics.tick(); onPlay() },
            contentAlignment = Alignment.Center,
        ) { Text(if (playing) "■" else "▶", color = if (playing) K.PrimarySoft else K.OnSurface, fontSize = 13.sp) }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(name, color = K.OnSurface, fontSize = 14.sp, maxLines = 1)
            Text(listOfNotNull(stringResource(R.string.sfx_seconds, seconds), subtitle?.takeIf { it.isNotBlank() }).joinToString(" · "), color = K.OnSurfaceVar, fontSize = 11.sp, maxLines = 1)
        }
        if (busy) Text("…", color = K.Primary, fontSize = 16.sp)
        else PillButton(stringResource(R.string.sfx_add), { haptics.confirm(); onAdd() }, Modifier.testTag("sfxAdd:$name"), filled = false)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, tag: String, onDone: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.SurfaceHigh).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, color = K.OnSurfaceVar, fontSize = 15.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = K.OnSurface, fontSize = 15.sp), cursorBrush = SolidColor(K.Primary),
            keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Search else ImeAction.Done),
            keyboardActions = KeyboardActions(onSearch = { onDone?.invoke() }, onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}
