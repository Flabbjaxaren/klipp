package se.gendut.klipp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's videos with previews, for the import. The system's own pickers show no thumbnail for 10-bit
 * log video (they can't make one) and, in the files app, list leftover ".pending" files first; this one draws the
 * previews with Klipp's own thumbnail decoder (as the media panel does), newest first, by folder, with the file names. Picked
 * clips come back in the order they were picked. "Files app" opens the system picker for anything else.
 */
object VideoLibrary {
    data class Video(
        val uri: Uri, val name: String, val durationMs: Long, val width: Int, val height: Int,
        /** Newest first: when it was taken (ms), else when the file changed. */
        val time: Long, val folder: String,
    )

    /** The read permission(s) to ask for: videos (Android 13+), or storage before that. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Full access, or (Android 14+) access to the videos the user chose. */
    fun hasAccess(c: Context): Boolean = permissions().any { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

    fun query(c: Context): List<Video> {
        val out = ArrayList<Video>()
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        runCatching {
            c.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { k ->
                while (k.moveToNext()) {
                    val name = k.getString(1) ?: continue
                    // leftovers of unfinished recordings and hidden files
                    if (name.startsWith(".")) continue
                    val taken = k.getLong(5)
                    out += Video(
                        android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, k.getLong(0)),
                        name, k.getLong(2), k.getInt(3), k.getInt(4),
                        if (taken > 0) taken else k.getLong(6) * 1000, k.getString(7) ?: "",
                    )
                }
            }
        }
        return out.sortedByDescending { it.time }
    }
}

@Composable
fun VideoPickerScreen(onImport: (List<Uri>) -> Unit, onFilesApp: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    var access by remember { mutableStateOf(VideoLibrary.hasAccess(ctx)) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true; access = VideoLibrary.hasAccess(ctx) }
    // asked once on opening; after a refusal the page offers it again (or the files app)
    LaunchedEffect(Unit) { if (!access) ask.launch(VideoLibrary.permissions()) }
    var reload by remember { mutableIntStateOf(0) }
    val videos by produceState<List<VideoLibrary.Video>?>(null, access, reload) {
        value = if (access) withContext(Dispatchers.IO) { VideoLibrary.query(ctx) } else emptyList()
    }
    val all = stringResource(R.string.videopicker_all)
    var folder by remember { mutableStateOf<String?>(null) }
    // picked, in the order they were picked
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // (the empty pointer input keeps touches on the page's bare parts from reaching the editor under it)
    Column(Modifier.fillMaxSize().background(K.Bg).pointerInput(Unit) {}.windowInsetsPadding(WindowInsets.safeDrawing).testTag("videoPicker")) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Ic.Close, stringResource(R.string.videopicker_close)) { onClose() }
            Text(stringResource(R.string.videopicker_title), color = K.OnSurface, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            PillButton(stringResource(R.string.videopicker_files_app), { onFilesApp() }, icon = Ic.Folder, filled = false, modifier = Modifier.padding(end = 8.dp).testTag("videoPicker:files"))
        }
        val list = videos
        when {
            !access -> Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.videopicker_access), color = K.OnSurfaceVar, fontSize = 15.sp)
                PillButton(stringResource(R.string.videopicker_allow), {
                    // after "don't ask again" the system answers at once without a dialog: the app's settings then
                    if (asked && permissionsBlocked(ctx)) openAppSettings(ctx) else ask.launch(VideoLibrary.permissions())
                }, filled = true)
            }
            list == null -> Box(Modifier.fillMaxWidth().weight(1f))
            list.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp)) {
                Text(stringResource(R.string.videopicker_none), color = K.OnSurfaceVar, fontSize = 15.sp)
            }
            else -> {
                // folders, the most used first (Camera usually); "All" = every video, newest first
                val folders = list.groupingBy { it.folder }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.filter { it.isNotEmpty() }
                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { ToggleChip(all, folder == null, { folder = null }) }
                    items(folders) { f -> ToggleChip(f, folder == f, { folder = f }) }
                }
                Spacer(Modifier.height(8.dp))
                val shown = if (folder == null) list else list.filter { it.folder == folder }
                LazyVerticalGrid(
                    GridCells.Adaptive(150.dp), Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.uri.toString() }) { v ->
                        val n = picked.indexOf(v.uri) + 1
                        VideoCard(v, n) {
                            haptics.tick()
                            picked = if (n > 0) picked - v.uri else picked + v.uri
                        }
                    }
                }
            }
        }
        if (picked.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(stringResource(R.string.videopicker_clear), { picked = emptyList() }, filled = false)
            Spacer(Modifier.size(10.dp))
            PillButton(stringResource(R.string.videopicker_import, picked.size), { haptics.confirm(); onImport(picked) }, filled = true, on = true,
                modifier = Modifier.weight(1f).testTag("videoPicker:import"))
        }
    }
}

@Composable
private fun VideoCard(v: VideoLibrary.Video, order: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.clip(shape).background(K.SurfaceHigh)
            .then(if (order > 0) Modifier.border(3.dp, K.Primary, shape) else Modifier)
            .clickable(onClick = onClick).testTag("video:${v.name}")
    ) {
        Box {
            Thumb(v.uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Text(
                durText(v.durationMs / 1000.0), color = K.OnSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(K.Bg.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (order > 0) Box(
                Modifier.align(Alignment.Center).size(30.dp).clip(RoundedCornerShape(15.dp)).background(K.Primary),
                contentAlignment = Alignment.Center,
            ) { Text("$order", color = K.OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }
        Text(v.name, color = K.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp))
        val meta = listOfNotNull(
            if (v.width > 0 && v.height > 0) "${v.width}×${v.height}" else null,
            android.text.format.DateFormat.format("d MMM HH:mm", v.time).toString(),
        ).joinToString(" · ")
        Text(meta, color = K.OnSurfaceVar, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp))
    }
}

/** True when every permission is refused for good (the system no longer shows its dialog). */
private fun permissionsBlocked(c: Context): Boolean {
    val a = c as? android.app.Activity ?: return false
    return VideoLibrary.permissions().none { a.shouldShowRequestPermissionRationale(it) } && !VideoLibrary.hasAccess(c)
}

private fun openAppSettings(c: Context) {
    runCatching {
        c.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.packageName, null))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
