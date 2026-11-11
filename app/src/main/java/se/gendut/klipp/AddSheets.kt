package se.gendut.klipp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * What the edit page's dock adds, each in a sheet of its own over the edit page: the page stays where it is, and what
 * is made lands on the timeline at the playhead. (Sound is the sound effect sheet with speech as a third tab.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(
    title: String, tag: String, onDismiss: () -> Unit,
    /** Whether the sheet may close now (not while a voice-over records). */
    canClose: () -> Boolean = { true },
    /** Tall sheets scroll; a short one (the voice-over) keeps the picture in view above it. */
    tall: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || canClose() })
    ModalBottomSheet(
        onDismissRequest = { if (canClose()) onDismiss() }, sheetState = sheet, containerColor = K.Surface,
        scrimColor = if (tall) K.Bg.copy(alpha = 0.5f) else Color.Transparent,
    ) {
        Column((if (tall) Modifier.fillMaxHeight(0.9f) else Modifier).fillMaxWidth().testTag(tag)) {
            SheetHeader(title) { if (canClose()) onDismiss() }
            Column(if (tall) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier) {
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** A voice-over: the timeline plays from the playhead while it records; the sheet stays low so the picture shows. */
@Composable
internal fun VoiceSheet(vo: VoiceOverUi, onDismiss: () -> Unit) {
    AddSheet(stringResource(R.string.panels_voice_over), "voiceSheet", onDismiss, canClose = { vo.phase == 0 }, tall = false) {
        VoiceOverCard(vo)
    }
}

/** A title: the template, how it enters and its text, added at the playhead. */
@Composable
internal fun TitleSheet(state: EditorState, onDismiss: () -> Unit) {
    val backend = state.props ?: return
    AddSheet(stringResource(R.string.design_add_title), "titleSheet", onDismiss) {
        TitlesTab(state, backend, onDismiss)
    }
}

/** Captions: a new one at the playhead, imported, or made from the speech. */
@Composable
internal fun CaptionsSheet(state: EditorState, onDismiss: () -> Unit) {
    val backend = state.props ?: return
    AddSheet(stringResource(R.string.design_add_captions), "captionsSheet", onDismiss) {
        CaptionsTab(state, backend, onDismiss)
    }
}
