package se.gendut.klipp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * What the system back closes on the editor, innermost first – one layer per press. The overlays composed above the
 * editor (full screen, settings, projects, dialogs, sheets, the corner pin, the box pick, the media sheet's name card)
 * have their own handlers and win while shown; only when nothing here is open does back leave the app (or, while an
 * export runs, send it to the background – see App).
 */
enum class BackLayer {
    /** The media sheet. */
    MEDIA,
    /** The source monitor. */
    SOURCE,
    /** Properties (a clip's sheet). */
    PROPERTIES,
    /** The caption editor over the timeline. */
    CAPTION_EDITOR,
    /** A row of steps that replaced the dock (speed, volume, label, freeze, more). */
    DOCK_ROW,
    /** The color page's grid (gallery, clip grade, timeline look). */
    COLOR_GRID,
    /** The edit page's transcript view: back to the timeline (the selection and the playhead stay). */
    TRANSCRIPT,
    /** Another page: back to Edit. */
    PAGE,
    /** A selected transition (its card). */
    TRANSITION,
    /** A selected edit point (the transition chooser). */
    CUT,
    /** A selection on the timeline (clips, captions). */
    SELECTION,
    /** Nothing: the activity's own back (leave the app, or the export's handler). */
    NONE,
}

/** The editor's state as back sees it. */
data class BackState(
    val mediaOpen: Boolean = false,
    val sourceOpen: Boolean = false,
    val inspecting: Boolean = false,
    val editingCaption: Boolean = false,
    val dockRow: Boolean = false,
    val colorGrid: Boolean = false,
    val transcript: Boolean = false,
    val page: Int = 0,
    val transition: Boolean = false,
    val cut: Boolean = false,
    val selection: Boolean = false,
    /** An export runs: the layers back used to leave alone stay that way (back sends the app to the background). */
    val exporting: Boolean = false,
)

/** The layer the next back closes. */
fun backLayer(s: BackState): BackLayer = when {
    s.mediaOpen -> BackLayer.MEDIA
    s.sourceOpen -> BackLayer.SOURCE
    s.inspecting -> BackLayer.PROPERTIES
    !s.exporting && s.page == 0 && s.editingCaption -> BackLayer.CAPTION_EDITOR
    !s.exporting && s.page == 0 && s.dockRow -> BackLayer.DOCK_ROW
    !s.exporting && s.page == 1 && s.colorGrid -> BackLayer.COLOR_GRID
    !s.exporting && s.page == 0 && s.transcript -> BackLayer.TRANSCRIPT
    s.page != 0 -> BackLayer.PAGE
    s.exporting -> BackLayer.NONE
    s.transition -> BackLayer.TRANSITION
    s.cut -> BackLayer.CUT
    s.selection -> BackLayer.SELECTION
    else -> BackLayer.NONE
}

fun EditorState.backState(mediaOpen: Boolean, sourceOpen: Boolean, colorGrid: Boolean, page: Int, exporting: Boolean) = BackState(
    mediaOpen = mediaOpen, sourceOpen = sourceOpen, inspecting = inspecting,
    editingCaption = editingCaption && selectedCaption != null, dockRow = dockRow != DockRow.NONE, colorGrid = colorGrid, page = page,
    transcript = editView == EditView.TRANSCRIPT,
    transition = selectedTransition != null, cut = selectedCut != null,
    selection = selected != null || multi.isNotEmpty() || multiCaptions.isNotEmpty() || selectedCaption != null,
    exporting = exporting,
)

/**
 * The editor's back handler. In its own composable: what it reads (the selection, the dock's row) recomposes only this,
 * not the whole app.
 */
@Composable
internal fun EditorBack(state: EditorState, of: () -> BackState, close: (BackLayer) -> Unit) {
    val now by rememberUpdatedState(of)
    val layer by remember { derivedStateOf { backLayer(now()) } }
    androidx.activity.compose.BackHandler(enabled = layer != BackLayer.NONE) {
        when (val l = backLayer(now())) {
            BackLayer.CAPTION_EDITOR -> state.editingCaption = false
            BackLayer.DOCK_ROW -> state.dockRow = DockRow.NONE
            BackLayer.TRANSCRIPT -> state.editView = EditView.TIMELINE
            BackLayer.TRANSITION -> state.selectedTransition = null
            BackLayer.CUT -> state.selectedCut = null
            BackLayer.SELECTION -> state.clearSelection()
            BackLayer.NONE -> {}
            else -> close(l)
        }
    }
}
