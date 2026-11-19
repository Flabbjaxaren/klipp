package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The caption track the overview, its list and the style follow: the one chosen ([chosen], kept in the editor's
 * state) while it exists, else the first with lines, else the first. Choosing a track is an editing choice only – it
 * never shows, hides or exports anything.
 */
fun captionShownTrack(tracks: List<CaptionTrackUi>, chosen: Long?): Long? =
    tracks.firstOrNull { it.id == chosen }?.id ?: tracks.firstOrNull { it.count > 0 }?.id ?: tracks.firstOrNull()?.id

/** "name · 12 lines", or "name · Empty". */
@Composable
fun captionTrackLabel(t: CaptionTrackUi): String =
    t.name + " · " + if (t.count == 0) stringResource(R.string.captiontracks_empty) else pluralStringResource(R.plurals.captiontracks_lines, t.count, t.count)

/** A name for a new track that no track has: "Captions 2", "Captions 3" … */
fun newCaptionTrackName(tracks: List<CaptionTrackUi>, pattern: (Int) -> String): String =
    (tracks.size + 1..tracks.size + 100).map(pattern).first { n -> tracks.none { it.name == n } }

/**
 * The track picker: "Edit caption track → name · lines" as a menu of the tracks and "New track", and Rename beside it.
 * [label] heads it (the write part says where new lines go instead).
 */
@Composable
fun CaptionTrackPicker(state: EditorState, backend: PropsBackend, tracks: List<CaptionTrackUi>, current: Long?, label: String = stringResource(R.string.captiontracks_edit)) {
    val haptics = rememberHaptics()
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CaptionTrackUi?>(null) }
    val cur = tracks.firstOrNull { it.id == current }
    val newName = newCaptionTrackName(tracks) { Str.t(R.string.captiontracks_new_name, it) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(K.SurfaceHighest).border(1.dp, if (open) K.Primary else K.OutlineVar, shape)
                        .clickable(onClickLabel = label) { haptics.tick(); open = true }.padding(horizontal = 14.dp, vertical = 10.dp).testTag("capTrackPicker"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(cur?.let { captionTrackLabel(it) } ?: stringResource(R.string.captiontracks_target_new, newName), color = K.OnSurface, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Ic.ChevronDown, null, tint = K.OnSurfaceVar, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(open, { open = false }, containerColor = K.SurfaceHighest, modifier = Modifier.widthIn(min = 240.dp).testTag("capTrackMenu")) {
                    for (t in tracks) {
                        val sel = t.id == current
                        Text(captionTrackLabel(t), color = if (sel) K.PrimarySoft else K.OnSurface, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { haptics.tick(); state.captionTrack = t.id; open = false }
                                .padding(horizontal = 16.dp, vertical = 12.dp).testTag("capTrack:${t.id}"))
                    }
                    Text("+ " + stringResource(R.string.captiontracks_new), color = K.PrimarySoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clickable { haptics.tick(); open = false; backend.newCaptionTrack(newName) { id -> state.captionTrack = id } }
                            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("capTrackNew"))
                }
            }
            if (cur != null) PillButton(stringResource(R.string.captiontracks_rename), { haptics.tick(); renaming = cur }, Modifier.testTag("capTrackRename"), filled = false)
        }
    }
    renaming?.let { t -> RenameTrackDialog(t.name, { renaming = null }) { backend.renameCaptionTrack(t.id, it); renaming = null } }
}

@Composable
private fun RenameTrackDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.captiontracks_rename_title)) },
        text = {
            BasicTextField(text, { text = it }, singleLine = true, textStyle = TextStyle(color = K.OnSurface, fontSize = 16.sp), cursorBrush = SolidColor(K.Primary),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.SurfaceHighest).padding(14.dp).testTag("capTrackName"))
        },
        confirmButton = { FlatTextButton({ if (text.isNotBlank()) onSave(text.trim()) }, Modifier.testTag("capTrackNameSave")) { Text(stringResource(R.string.captiontracks_save)) } },
        dismissButton = { FlatTextButton(onDismiss) { Text(stringResource(R.string.titleui_cancel)) } },
        containerColor = K.SurfaceHigh,
    )
}

/** "Show this track in the picture and the export" – this track only (the others keep theirs). */
@Composable
fun CaptionTrackVisible(backend: PropsBackend, t: CaptionTrackUi) {
    Row(
        Modifier.fillMaxWidth().clickable { backend.setCaptionTrackVisible(t.id, !t.enabled) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("capTrackVisible"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.captiontracks_visible), color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.captiontracks_visible_hint), color = K.OnSurfaceVar, fontSize = 12.sp)
        }
        Switch(t.enabled, { backend.setCaptionTrackVisible(t.id, it) }, colors = SwitchDefaults.colors(checkedThumbColor = K.PrimarySoft, checkedTrackColor = K.PrimaryContainer))
    }
}

/**
 * The captions in the project: the track being edited (picker, rename), whether it is shown, its style (opens the
 * style view, [onStyle]) and its lines – an empty track says so and offers ways to fill it. Empty tracks can be
 * removed (one undo step), never automatically. The switch for all tracks stays apart, at the end.
 */
@Composable
internal fun CaptionsExisting(state: EditorState, backend: PropsBackend, onPart: (CaptionsPart) -> Unit, onEdit: () -> Unit, onStyle: (Long) -> Unit) {
    val haptics = rememberHaptics()
    val tracks = backend.captionTracks
    if (tracks.isEmpty()) {
        Text(stringResource(R.string.titleui_caps_none), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        FillButtons(onPart)
        return
    }
    val current = captionShownTrack(tracks, state.captionTrack)
    val t = tracks.firstOrNull { it.id == current } ?: return
    CaptionTrackPicker(state, backend, tracks, current)
    CaptionTrackVisible(backend, t)
    // the style: its name, opens the style view
    val st = backend.captionStyle(t.id)
    if (st != null) Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(K.SurfaceHigh)
            .clickable { haptics.tick(); onStyle(t.id) }.padding(horizontal = 14.dp, vertical = 12.dp).testTag("capStyle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.captiontracks_style_row, captionStyleName(st)), color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(Ic.Back, null, tint = K.OnSurfaceVar, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 180f })
    }
    // the lines on this track (a row opens the caption editor – the same one as tapping it in the timeline)
    val lines = state.captions.filter { it.track == t.id }
    SectionLabel(stringResource(R.string.captiontracks_list, t.name))
    if (lines.isEmpty()) {
        Text(stringResource(R.string.captiontracks_empty_track), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("capTrackEmpty"))
        FillButtons(onPart)
    } else {
        Text(stringResource(R.string.titleui_captions_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        for (c in lines.take(300)) key(c.id) {
            ListRow(formatTc(c.start), c.text.replace('\n', ' '), "caption:row:${c.id}") {
                haptics.tick(); state.playing = false; state.playhead = c.start
                state.clearSelection(); state.selectedCaption = c.id; state.editingCaption = true
                onEdit()
            }
        }
    }
    // empty tracks: removed on request (undo brings them back)
    val empty = tracks.filter { it.count == 0 }
    val toaster = LocalToaster.current
    if (empty.isNotEmpty()) PillButton(pluralStringResource(R.plurals.captiontracks_remove_empty, empty.size, empty.size), {
        haptics.tick()
        backend.deleteCaptionTracks(empty.map { it.id })
        toaster?.show(Str.p(R.plurals.captiontracks_removed, empty.size, empty.size))
    }, Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("capRemoveEmpty"), icon = Ic.Delete, filled = false)
    // all tracks at once (apart from the track above)
    if (tracks.size > 1) {
        val vis = backend.captionsVisible
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SpringChip(stringResource(if (vis) R.string.titleui_captions_shown_all else R.string.titleui_captions_hidden_all), vis, { backend.setCaptionsVisible(!vis) }, Modifier.testTag("capAllVisible"))
        }
    }
}

@Composable
private fun FillButtons(onPart: (CaptionsPart) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(stringResource(R.string.titleui_caps_from_speech), { onPart(CaptionsPart.SPEECH) }, icon = Ic.Subs, filled = false)
        PillButton(stringResource(R.string.titleui_caps_write), { onPart(CaptionsPart.WRITE) }, icon = Ic.Text, filled = false)
    }
}

/** The style view's header: back to the overview, the track and that the style is the whole track's. */
@Composable
fun CaptionStyleHeader(trackName: String, onBack: () -> Unit) {
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = stringResource(R.string.captionstyle_back)) { haptics.tick(); onBack() }
                .padding(horizontal = 10.dp, vertical = 8.dp).testTag("capStyleBack"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Back, null, tint = K.OnSurface, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.captionstyle_back), color = K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
        }
        Text(stringResource(R.string.captionstyle_scope, trackName), color = K.OnSurfaceVar, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}
