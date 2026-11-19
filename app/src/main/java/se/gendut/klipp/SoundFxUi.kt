package se.gendut.klipp

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
 * Sound effects (CC0): packs to download (BigSoundBank, Kenney), and the sounds found on Freesound before its search was
 * removed (2026-11, its API terms don't cover a paid app) – those stay usable. A tap on ▶ plays a sound; "Add" places it
 * at the playhead on the first free audio track ([onAdd]).
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
    DockSheet("sfxDock", onDismiss) { _ ->
        var tab by remember { mutableStateOf(0) }
        Box(Modifier.fillMaxWidth().weight(1f).upwardStaysInside().testTag("sfxSheet")) {
        Column(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.sfx_title), color = K.OnSurface, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 16.dp))
            if (tab < 1) Text(stringResource(R.string.sfx_license_note), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            val tabs = listOf(ToolTab(stringResource(R.string.sfx_tab_packs), Ic.Note, "sfxTab:0")) +
                (if (speech) listOf(ToolTab(stringResource(R.string.sfx_tab_speech), Ic.Mic, "sfxTab:1")) else emptyList()) +
                ToolTab(stringResource(R.string.sfx_tab_files), Ic.Folder, "sfxTab:${if (speech) 2 else 1}")
            ToolTabs(tabs, tab, { tab = it; preview.stop() }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            when (tab) {
                0 -> PacksTab(preview, onAdd)
                tabs.lastIndex -> FilesTab(onAdd)
                else -> TextToSpeechCard(onAdd)
            }
        }
        MessageHost(Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
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
    // packs that can't be downloaded now (no file on Klipp's own release yet): say why, once, above the list
    if (SoundFx.PACKS.any { it.bundled == null && it.id !in installedPacks })
        Text(stringResource(R.string.sfx_packs_moving), color = K.OnSurfaceVar, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("sfx:moving"))
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
                        pack.bundled == null -> Text(stringResource(R.string.sfx_pack_unavailable), color = K.OnSurfaceVar, fontSize = 12.sp,
                            modifier = Modifier.testTag("sfxUnavailable:${pack.id}"))
                        else -> PillButton(stringResource(R.string.sfx_download), {
                            error = null
                            scope.launch {
                                SoundFx.download(ctx, pack).onSuccess { open = pack.id }
                                    .onFailure { error = Str.t(R.string.sfx_download_failed, it.message.orEmpty()) }
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
        // sounds downloaded from Freesound before its search was removed: still listed, still usable
        val earlier = SoundFx.sounds(ctx, SoundFx.FREESOUND)
        if (earlier.isNotEmpty()) {
            item(key = "freesound") {
                Row(Modifier.fillMaxWidth().clickable { haptics.tick(); open = if (open == SoundFx.FREESOUND) null else SoundFx.FREESOUND }
                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("sfxPack:freesound"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Freesound", color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.sfx_freesound_earlier, earlier.size), color = K.OnSurfaceVar, fontSize = 12.sp)
                    }
                    Text(if (open == SoundFx.FREESOUND) "▲" else "▼", color = K.OnSurfaceVar, fontSize = 14.sp)
                }
            }
            if (open == SoundFx.FREESOUND) items(earlier, key = { it.file.path }) { s ->
                SoundRow(s.name, s.seconds, preview.playing == s.file.path, { preview.toggle(s.file.path, s.file.toURI().toString()) }, { onAdd(s.file, s.name, s.seconds) }, subtitle = s.author)
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
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
