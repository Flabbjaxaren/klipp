package se.gendut.klipp

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import se.gendut.klipp.engine.BinSnap
import se.gendut.klipp.engine.MediaInfo

/** The Media panel's data and actions (the engine in the app, a fake in tests). */
interface MediaLibrary {
    val media: List<MediaInfo>
    val bins: List<BinSnap>
    val busy: Boolean
    fun uriOf(item: Long): Uri?
    /** Number of timeline clips that use the media. */
    fun usage(item: Long): Int
    fun newBin(name: String, parent: Long?)
    fun moveToBin(items: List<Long>, bin: Long?)
    fun rename(item: Long, name: String)
    fun renameBin(bin: Long, name: String)
    /** Remove media and folders (with their contents) from the project – and the timeline clips that use them. */
    fun delete(items: List<Long>, bins: List<Long>)
    /** All media in a folder and its subfolders. */
    fun mediaInBin(bin: Long): List<Long> {
        val sub = bins.filter { it.parent == bin }.map { it.id }
        return media.filter { it.bin == bin }.map { it.id } + sub.flatMap { mediaInBin(it) }
    }
}

/** A rename or a new folder: dialog with a text field. */
private data class NameAsk(val title: String, val initial: String, val done: (String) -> Unit)

/**
 * The Media panel: folders (bins) as in Premiere, tap = open in the Source monitor, hold = select (multiple).
 * Selected items can be moved to a folder, renamed or removed from the project. If the media is used in the
 * timeline the panel asks first – those clips are removed too. The files on the phone are never touched.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaSheet(visible: Boolean, lib: MediaLibrary, onImport: () -> Unit, onOpen: (MediaInfo) -> Unit, onClose: () -> Unit) {
    val haptics = rememberHaptics()
    var folder by remember { mutableStateOf<Long?>(null) }
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pickedBins by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var ask by remember { mutableStateOf<NameAsk?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    // the folder may have disappeared (undo, deleted)
    if (folder != null && lib.bins.none { it.id == folder }) folder = null
    val selecting = picked.isNotEmpty() || pickedBins.isNotEmpty()
    fun clearPick() { picked = emptySet(); pickedBins = emptySet() }
    LaunchedEffect(visible) { if (!visible) clearPick() }

    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color(0x99000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(spring(0.75f, 380f)) { it },
        exit = slideOutVertically(spring(1f, 500f)) { it },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.72f)
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(K.Surface)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(top = 10.dp)
                    .testTag("mediaSheet")
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(K.OutlineVar))
                if (selecting) {
                    // selection mode: actions in the header
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundIcon(Ic.Close, stringResource(R.string.medialibrary_deselect)) { clearPick() }
                        Text(stringResource(R.string.medialibrary_selected_count, picked.size + pickedBins.size), color = K.OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Box {
                            SpringChip(stringResource(R.string.medialibrary_move), false, { moveMenu = true }, Modifier.testTag("mediaMove"))
                            androidx.compose.material3.DropdownMenu(moveMenu, { moveMenu = false }, containerColor = K.SurfaceHighest) {
                                @Composable
                                fun dest(label: String, bin: Long?) = androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(label, color = K.OnSurface) },
                                    onClick = {
                                        moveMenu = false; haptics.confirm()
                                        // a folder can't be moved into itself
                                        lib.moveToBin(picked.toList() + pickedBins.filter { it != bin }, bin)
                                        clearPick()
                                    },
                                    modifier = Modifier.testTag("moveTo:${bin ?: "root"}"),
                                )
                                dest(stringResource(R.string.medialibrary_top_level), null)
                                for (b in lib.bins.sortedBy { it.name.lowercase() }) if (b.id !in pickedBins) dest(binPath(lib.bins, b.id), b.id)
                            }
                        }
                        val renameTitle = stringResource(R.string.medialibrary_rename)
                        val renameBinTitle = stringResource(R.string.medialibrary_rename_folder)
                        if (picked.size + pickedBins.size == 1) SpringChip(renameTitle, false, {
                            val m = picked.firstOrNull()?.let { id -> lib.media.firstOrNull { it.id == id } }
                            val b = pickedBins.firstOrNull()?.let { id -> lib.bins.firstOrNull { it.id == id } }
                            ask = when {
                                m != null -> NameAsk(renameTitle, m.name) { lib.rename(m.id, it) }
                                b != null -> NameAsk(renameBinTitle, b.name) { lib.renameBin(b.id, it) }
                                else -> null
                            }
                        }, Modifier.testTag("mediaRename"))
                        SpringChip(stringResource(R.string.medialibrary_delete), false, { confirmDelete = true }, Modifier.testTag("mediaDelete"))
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.medialibrary_title), color = K.OnSurface, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                        val newFolder = stringResource(R.string.medialibrary_new_folder)
                        val newFolderName = stringResource(R.string.medialibrary_new_folder_name)
                        RoundIcon(Ic.Plus, newFolder, modifier = Modifier.testTag("newBin")) {
                            ask = NameAsk(newFolder, newFolderName) { lib.newBin(it, folder) }
                        }
                        BigButton(stringResource(if (lib.busy) R.string.medialibrary_importing else R.string.medialibrary_import), onImport)
                    }
                }
                // where we are: Media › B-roll › Drone
                if (folder != null) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundIcon(Ic.Back, stringResource(R.string.medialibrary_up), modifier = Modifier.testTag("binUp")) { folder = lib.bins.firstOrNull { it.id == folder }?.parent }
                        val chain = generateSequence(lib.bins.firstOrNull { it.id == folder }) { b -> b.parent?.let { p -> lib.bins.firstOrNull { it.id == p } } }.toList().reversed()
                        SpringChip(stringResource(R.string.medialibrary_title), false, { folder = null })
                        for (b in chain) {
                            Text("›", color = K.Outline, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 2.dp))
                            SpringChip(b.name, b.id == folder, { folder = b.id })
                        }
                    }
                }
                val bins = lib.bins.filter { it.parent == folder }.sortedBy { it.name.lowercase() }
                val media = lib.media.filter { it.bin == folder }
                if (bins.isEmpty() && media.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (folder != null) R.string.medialibrary_folder_empty
                                else if (lib.media.isEmpty()) R.string.medialibrary_empty else R.string.medialibrary_nothing_here
                            ),
                            color = K.OnSurfaceVar, fontSize = 14.sp, modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        GridCells.Fixed(2),
                        Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("mediaGrid"),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(bins, key = { "b${it.id}" }) { b ->
                            val on = b.id in pickedBins
                            FolderCard(b, lib.bins.count { it.parent == b.id } + lib.media.count { it.bin == b.id }, on,
                                onClick = {
                                    if (selecting) { pickedBins = if (on) pickedBins - b.id else pickedBins + b.id; haptics.tick() }
                                    else folder = b.id
                                },
                                onLongClick = { haptics.heavy(); pickedBins = pickedBins + b.id },
                            )
                        }
                        items(media, key = { it.id }) { m ->
                            val on = m.id in picked
                            MediaCard(m, lib.uriOf(m.id), lib.usage(m.id), on,
                                onClick = {
                                    if (selecting) { picked = if (on) picked - m.id else picked + m.id; haptics.tick() } else onOpen(m)
                                },
                                onLongClick = { haptics.heavy(); picked = picked + m.id },
                            )
                        }
                        item(span = { GridItemSpan(2) }) {
                            Text(
                                stringResource(R.string.medialibrary_footer_hint),
                                color = K.Outline, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                        item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(60.dp)) }
                    }
                }
            }
        }
    }

    // name (new folder, rename): a card in the same window (not a dialog), the keyboard pushes it up
    ask?.let { a ->
        var text by remember(a) { mutableStateOf(a.initial) }
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        fun close() { focus.clearFocus(); ask = null }
        Box(
            Modifier.fillMaxSize().background(Color(0x99000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(K.SurfaceHigh)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(20.dp)
            ) {
                Text(a.title, color = K.OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = K.OnSurface, fontSize = 16.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(K.Primary),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Surface).padding(horizontal = 14.dp, vertical = 12.dp).testTag("nameField"),
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ close() }) { Text(stringResource(R.string.medialibrary_cancel), color = K.OnSurfaceVar) }
                    TextButton({ val t = text.trim(); if (t.isNotEmpty()) a.done(t); close(); clearPick() }, Modifier.testTag("nameOk")) { Text(stringResource(R.string.medialibrary_ok), color = K.Primary) }
                }
            }
        }
    }

    if (confirmDelete) {
        val items = picked.toList()
        val binList = pickedBins.toList()
        val all = (items + binList.flatMap { lib.mediaInBin(it) }).toSet()
        val used = all.sumOf { lib.usage(it) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = K.SurfaceHigh,
            title = { Text(stringResource(R.string.medialibrary_delete_title), color = K.OnSurface) },
            text = {
                val clipsText = pluralStringResource(R.plurals.medialibrary_clip_count, all.size, all.size)
                val foldersText = pluralStringResource(R.plurals.medialibrary_folder_count, binList.size, binList.size)
                val summary = if (binList.isNotEmpty()) stringResource(R.string.medialibrary_delete_summary_with_folders, clipsText, foldersText)
                    else stringResource(R.string.medialibrary_delete_summary, clipsText)
                val usedText = if (used > 0) pluralStringResource(
                    if (all.size == 1) R.plurals.medialibrary_delete_used_it else R.plurals.medialibrary_delete_used_they, used, used,
                ) else null
                Text(
                    listOfNotNull(summary, usedText, stringResource(R.string.medialibrary_delete_tail)).joinToString(" "),
                    color = K.OnSurfaceVar, modifier = Modifier.testTag("deleteText"),
                )
            },
            confirmButton = {
                TextButton({ haptics.heavy(); lib.delete(items, binList); confirmDelete = false; clearPick() }, Modifier.testTag("deleteOk")) { Text(stringResource(R.string.medialibrary_delete), color = K.Error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.medialibrary_cancel), color = K.OnSurfaceVar) } },
        )
    }
}

/** The folder's full path, for the move menu ("B-roll / Drone"). */
private fun binPath(bins: List<BinSnap>, id: Long): String =
    generateSequence(bins.firstOrNull { it.id == id }) { b -> b.parent?.let { p -> bins.firstOrNull { it.id == p } } }.toList().reversed().joinToString(" / ") { it.name }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(b: BinSnap, count: Int, picked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp)).background(K.SurfaceHigh)
            .then(if (picked) Modifier.border(3.dp, K.Primary, RoundedCornerShape(18.dp)) else Modifier)
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onLongClick = onLongClick, onClick = onClick)
            .testTag("bin:${b.name}")
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(K.Tonal), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(Ic.Folder, null, tint = K.PrimarySoft, modifier = Modifier.size(40.dp))
            Text(
                "$count", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(b.name, color = K.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCard(m: MediaInfo, uri: Uri?, used: Int, picked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    Column(
        Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }
            .clip(RoundedCornerShape(18.dp)).background(K.SurfaceHigh)
            .then(if (picked) Modifier.border(3.dp, K.Primary, RoundedCornerShape(18.dp)) else Modifier)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onLongClick = onLongClick,
            ) {
                scope.launch { press.snapTo(0.93f); press.animateTo(1f, Springs.pop()) }
                onClick()
            }
            .testTag("media:${m.name}")
    ) {
        Box {
            Thumb(uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Text(
                durText(m.duration), color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            // has a transcript: can be picked by text in the source monitor
            if (m.words > 0) Box(
                Modifier.align(Alignment.BottomStart).padding(6.dp).size(22.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.Icon(Ic.Transcript, stringResource(R.string.medialibrary_has_text), tint = Color.White, modifier = Modifier.size(15.dp)) }
            if (m.markIn != null || m.markOut != null) {
                Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(K.Primary))
            }
            // used in the timeline
            if (used > 0) Text(
                stringResource(R.string.medialibrary_in_timeline), color = K.OnPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(K.Primary.copy(alpha = 0.9f)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (picked) Box(
                Modifier.align(Alignment.Center).size(30.dp).clip(RoundedCornerShape(15.dp)).background(K.Primary),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.Icon(Ic.Check, null, tint = K.OnPrimary, modifier = Modifier.size(18.dp)) }
        }
        Text(m.name, color = K.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
    }
}

/** The Media panel backed by the engine. */
fun controllerLibrary(c: se.gendut.klipp.engine.ProjectController) = object : MediaLibrary {
    override val media get() = c.snapshot.media
    override val bins get() = c.snapshot.bins
    override val busy get() = c.busy
    override fun uriOf(item: Long) = c.uriOf(item)
    override fun usage(item: Long) = c.usage(item)
    override fun newBin(name: String, parent: Long?) { c.newBin(name, parent) }
    override fun moveToBin(items: List<Long>, bin: Long?) { c.moveToBin(items, bin) }
    override fun rename(item: Long, name: String) { c.renameMedia(item, name) }
    override fun renameBin(bin: Long, name: String) { c.renameBin(bin, name) }
    override fun delete(items: List<Long>, bins: List<Long>) { c.deleteMedia(items, bins) }
}
