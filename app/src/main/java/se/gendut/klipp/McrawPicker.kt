package se.gendut.klipp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.Mcraw
import se.gendut.klipp.engine.OpenDrt

/**
 * MotionCam RAW in the video picker. The .mcraw files of one folder (picked once, Documents/MotionCam by default – the
 * files aren't media, so only the folder's own grant reads them), each with a preview of its middle frame, its length
 * and size; picked like the videos (numbered, in order) and developed into a log clip on import. The EI chips set where
 * the sensor's clip lands in the log (as on a log camera), Auto measures each file.
 */
object McrawFolder {
    private fun p(c: Context) = c.getSharedPreferences("klipp_mcraw", Context.MODE_PRIVATE)
    fun tree(c: Context): Uri? = p(c).getString("tree", null)?.let(Uri::parse)
    fun setTree(c: Context, u: Uri) { p(c).edit().putString("tree", u.toString()).apply() }
    /** The EI the files are developed at (the sensor's clip log2(EI/25) stops over 18 % grey); 0 = Auto (measured per file, see McrawConvert.autoEi). */
    fun ei(c: Context): Int = p(c).getInt("ei", 0)
    fun setEi(c: Context, ei: Int) { p(c).edit().putInt("ei", ei).apply() }
    /** Import a quick half-size develop first (the full one follows in the background). */
    fun quick(c: Context): Boolean = p(c).getBoolean("quick", true)
    fun setQuick(c: Context, on: Boolean) { p(c).edit().putBoolean("quick", on).apply() }
    /** The chips: 0 = Auto. */
    val EIS = listOf(0, 400, 800, 1600, 3200)
    fun eiLabel(v: Int) = if (v <= 0) Str.t(R.string.mcraw_auto) else "$v"
    /** The output the files are developed into (an OpenDrt.Input key; S-Log3 / S-Gamut3.Cine by default). */
    fun out(c: Context): String = p(c).getString("out", null)?.let { k -> OUTS.firstOrNull { it.key == k }?.key } ?: OpenDrt.Input.SLOG3.key
    fun setOut(c: Context, k: String) { p(c).edit().putString("out", k).apply() }
    /** The logs a develop can go into (every input OpenDRT reads but Rec.709 video). */
    val OUTS get() = OpenDrt.Input.entries.filter { it != OpenDrt.Input.REC709 }

    class File(val uri: Uri, val name: String, val bytes: Long, val time: Long)

    /** The picked folder's .mcraw files, newest first. Blocking: off the main thread. */
    fun list(c: Context): List<File> {
        val tree = tree(c) ?: return emptyList()
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<File>()
        runCatching {
            c.contentResolver.query(kids, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ), null, null, null)?.use { k ->
                while (k.moveToNext()) {
                    val name = k.getString(1) ?: continue
                    if (!name.endsWith(".mcraw", true)) continue
                    out += File(DocumentsContract.buildDocumentUriUsingTree(tree, k.getString(0)), name, k.getLong(2), k.getLong(3))
                }
            }
        }
        return out.sortedByDescending { it.time }
    }
}

/** The video picker's MotionCam RAW list: the develop settings, the folder and its files. [onToggle]: a file tapped. */
@Composable
fun ColumnScope.McrawPanel(picked: List<Uri>, onToggle: (Uri) -> Unit) {
    val ctx = LocalContext.current
    var tree by remember { mutableStateOf(McrawFolder.tree(ctx)) }
    var ei by remember { mutableIntStateOf(McrawFolder.ei(ctx)) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            McrawFolder.setTree(ctx, u); tree = u
        }
    }
    val startAt = remember { Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2FMotionCam") }
    if (!Mcraw.available) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp)) { Text(stringResource(R.string.mcraw_unavailable), color = K.OnSurfaceVar, fontSize = 15.sp) }
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.mcraw_develop_at_ei), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(McrawFolder.EIS) { v -> ToggleChip(McrawFolder.eiLabel(v), ei == v, { ei = v; McrawFolder.setEi(ctx, v) }, Modifier.testTag("mcraw:ei$v")) }
        }
    }
    var quick by remember { mutableStateOf(McrawFolder.quick(ctx)) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ToggleChip(stringResource(R.string.mcraw_quick), quick, { quick = !quick; McrawFolder.setQuick(ctx, quick) }, Modifier.testTag("mcraw:quick"))
    }
    var outKey by remember { mutableStateOf(McrawFolder.out(ctx)) }
    Box(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
        DropField(stringResource(R.string.mcraw_develop_into), McrawFolder.OUTS.map { Opt(it.key, it.label, key = it.key) }, outKey, "mcraw:out") {
            outKey = it; McrawFolder.setOut(ctx, it)
        }
    }
    if (tree == null) {
        Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.mcraw_folder_hint), color = K.OnSurfaceVar, fontSize = 15.sp)
            PillButton(stringResource(R.string.mcraw_choose_folder), { pickFolder.launch(startAt) }, filled = true, modifier = Modifier.testTag("mcraw:folder"))
        }
        return
    }
    val files by produceState<List<McrawFolder.File>?>(null, tree) { value = withContext(Dispatchers.IO) { McrawFolder.list(ctx) } }
    val list = files
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tree?.lastPathSegment?.substringAfterLast(':')?.ifEmpty { null } ?: "MotionCam", color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.weight(1f))
        FlatTextButton(onClick = { pickFolder.launch(startAt) }, modifier = Modifier.testTag("mcraw:otherFolder")) { Text(stringResource(R.string.mcraw_other_folder), fontSize = 12.sp) }
    }
    when {
        list == null -> Box(Modifier.fillMaxWidth().weight(1f))
        list.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp)) { Text(stringResource(R.string.mcraw_none), color = K.OnSurfaceVar, fontSize = 15.sp) }
        else -> LazyVerticalGrid(
            GridCells.Adaptive(150.dp), Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.uri.toString() }) { f -> McrawCard(f, picked.indexOf(f.uri) + 1) { onToggle(f.uri) } }
        }
    }
}

/** Previews and lengths, made once per file (decoding one frame) and kept while the app runs. */
private object McrawThumbs {
    val cache = mutableStateMapOf<String, Mcraw.Thumb>()
}

@Composable
private fun McrawCard(f: McrawFolder.File, order: Int, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val key = f.uri.toString()
    val thumb = McrawThumbs.cache[key]
    LaunchedEffect(key) {
        if (McrawThumbs.cache[key] == null) withContext(Dispatchers.IO) { Mcraw.thumb(ctx, f.uri)?.let { McrawThumbs.cache[key] = it } }
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.clip(shape).background(K.SurfaceHigh)
            .then(if (order > 0) Modifier.border(3.dp, K.Primary, shape) else Modifier)
            .clickable(onClick = onClick).testTag("mcraw:${f.name}")
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(K.Bg)) {
            // turned as the clip was shot (a clip shot with the phone turned round showed upside down)
            val shown = androidx.compose.runtime.remember(thumb) {
                thumb?.bitmap?.let { b ->
                    if (thumb.rotation == 0) b
                    else android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(thumb.rotation.toFloat()) }, true)
                }
            }
            shown?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop) }
            thumb?.let {
                Text(
                    durText(it.seconds), color = K.OnSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(K.Bg.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text("RAW", color = K.OnPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(K.Primary).padding(horizontal = 6.dp, vertical = 2.dp))
            if (order > 0) Box(
                Modifier.align(Alignment.Center).size(30.dp).clip(RoundedCornerShape(15.dp)).background(K.Primary),
                contentAlignment = Alignment.Center,
            ) { Text("$order", color = K.OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }
        Text(f.name, color = K.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp))
        val meta = listOfNotNull(
            thumb?.let { if (it.rotation % 180 != 0) "${it.height}×${it.width} · ${"%.0f".format(it.fps)} fps" else "${it.width}×${it.height} · ${"%.0f".format(it.fps)} fps" },
            "%.1f GB".format(f.bytes / 1e9),
            android.text.format.DateFormat.format("d MMM HH:mm", f.time).toString(),
        ).joinToString(" · ")
        Text(meta, color = K.OnSurfaceVar, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp))
    }
}
