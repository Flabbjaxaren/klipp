package se.gendut.klipp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.LutStore

/** A preview frame for the LUT browser: the image with [lut] in place of the node's LUT (null = no LUT). */
typealias LutPreview = suspend (lut: String?) -> ImageBitmap?

/**
 * The LUT library as a strip of cards – each shows the current frame with that LUT ([preview]; without it,
 * the name only), in one strip ordered by the folders of the zip they were imported from, each group's name above its
 * first card. Tap = use it; hold = select several, to delete them. [selected] = the LUT in use (null = none).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LutBrowser(
    selected: String?,
    onPick: (String?) -> Unit,
    preview: LutPreview?,
    onImport: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val names = LutStore.names.toList()
    val builtIn = stringResource(R.string.lutbrowser_builtin)
    val imported = stringResource(R.string.lutbrowser_imported)
    fun groupLabel(n: String) = if (LutStore.isBuiltIn(n)) builtIn else LutStore.groupOf(n).ifEmpty { imported }
    val order = names.map(::groupLabel).distinct().sortedWith(compareBy({ it != builtIn }, { it != imported }, { it.lowercase() }))
    // one strip, group after group; the group's name sits above its first card (no filter row: the cards are the list)
    val shown = order.flatMap { g -> names.filter { groupLabel(it) == g } }
    val starts = shown.indices.filter { it == 0 || groupLabel(shown[it]) != groupLabel(shown[it - 1]) }.toSet()
    // opens at the LUT in use, with the card before it in view (the "none" card is item 0, so the LUT is index + 1)
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = shown.indexOf(selected).coerceAtLeast(0))
    // selection for deleting (built-in LUTs can't be selected)
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val selecting = picked.isNotEmpty()
    var askDelete by remember { mutableStateOf(false) }

    Column(modifier) {
        if (selecting) {
            // the panel beside the tool rail is narrow: the count on its own line, the buttons in a row that scrolls
            Text(
                stringResource(R.string.lutbrowser_selected, picked.size), color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillButton(stringResource(R.string.lutbrowser_delete), { askDelete = true }, filled = true)
                PillButton(stringResource(R.string.lutbrowser_cancel), { picked = emptySet() }, filled = false)
                PillButton(stringResource(R.string.lutbrowser_select_all), { picked = shown.filterNot { LutStore.isBuiltIn(it) }.toSet() }, filled = false)
            }
            Spacer(Modifier.height(8.dp))
        }
        // the cards
        LazyRow(
            Modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!selecting) item(key = "\u0000none") {
                LutCard(stringResource(R.string.colortools_none), null, selected == null, false, preview, group = null,
                    onTap = { onPick(null) }, onHold = {})
            }
            items(shown.size, key = { shown[it] }) { i ->
                val n = shown[i]
                LutCard(Str.name(LutStore.shortName(n)), n, selected == n && !selecting, n in picked, preview,
                    group = if (i in starts) groupLabel(n) else null,
                    // while selecting, the group's name selects (or clears) the whole group
                    onGroup = {
                        val all = shown.filter { groupLabel(it) == groupLabel(n) && !LutStore.isBuiltIn(it) }.toSet()
                        if (selecting && all.isNotEmpty()) picked = if (picked.containsAll(all)) picked - all else picked + all
                    },
                    onTap = {
                        if (selecting) { if (!LutStore.isBuiltIn(n)) picked = if (n in picked) picked - n else picked + n }
                        else onPick(n)
                    },
                    onHold = { if (!LutStore.isBuiltIn(n)) { haptics.heavy(); picked = picked + n } },
                )
            }
        }
        // the panel beside the tool rail is narrow: the hint goes under the button (beside it, it wrapped a word a line),
        // and only when there is an imported LUT to select (built-in ones can't be)
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            if (onImport != null) PillButton(stringResource(R.string.colortools_import_cube), onImport, filled = false)
            if (!selecting && names.any { !LutStore.isBuiltIn(it) }) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.lutbrowser_hint), color = K.OnSurfaceVar, fontSize = 12.sp)
            }
        }
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(R.string.lutbrowser_delete_title, picked.size)) },
            text = { Text(stringResource(R.string.lutbrowser_delete_text)) },
            confirmButton = {
                FlatTextButton(onClick = {
                    val gone = picked
                    askDelete = false
                    picked = emptySet()
                    if (selected != null && selected in gone) onPick(null)
                    LutStore.delete(gone)
                    haptics.confirm()
                }) { Text(stringResource(R.string.lutbrowser_delete)) }
            },
            dismissButton = { FlatTextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.lutbrowser_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LutCard(label: String, lut: String?, on: Boolean, picked: Boolean, preview: LutPreview?, group: String?, onGroup: () -> Unit = {}, onTap: () -> Unit, onHold: () -> Unit) {
    val img by produceState<ImageBitmap?>(null, lut, preview) { value = preview?.invoke(lut) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(112.dp)) {
        // the group's name above its first card; the other cards keep the line empty, so the row stays aligned
        Text(
            group ?: "", color = K.Primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = Modifier.fillMaxWidth().then(if (group != null) Modifier.clickable(onClick = onGroup) else Modifier).padding(start = 2.dp, bottom = 3.dp),
        )
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(K.SurfaceHigh)
                .then(if (on || picked) Modifier.border(2.dp, if (picked) K.Error else K.Primary, shape) else Modifier)
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onLongClick = onHold, onClick = onTap)
                .testTag(if (lut == null) "nodeLut:none" else "nodeLut:$lut"),
            contentAlignment = Alignment.Center,
        ) {
            val i = img
            if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(label, color = K.OnSurfaceVar, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
            if (on || picked) Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (picked) K.Error else K.Primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Check, null, tint = K.Surface, modifier = Modifier.size(12.dp)) }
        }
        Text(
            label, color = if (on) K.OnSurface else K.OnSurfaceVar, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}
