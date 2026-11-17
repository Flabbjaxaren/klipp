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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * A sheet's content keeps what is left of an upward scroll or fling to itself: at the end of its list the rest went to
 * the sheet, which tried to go on past its top and never came to rest (a quick throw upwards left the sheet bouncing
 * up and down for good). Downward stays the sheet's: from the top of the content a pull down still closes it.
 */
internal fun Modifier.upwardStaysInside(): Modifier = nestedScroll(object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) =
        if (available.y < 0f) androidx.compose.ui.geometry.Offset(0f, available.y) else androidx.compose.ui.geometry.Offset.Zero
    override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity) =
        if (available.y < 0f) androidx.compose.ui.unit.Velocity(0f, available.y) else androidx.compose.ui.unit.Velocity.Zero
})

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
    /** Above the content, not scrolled with it (the sheet's tabs). */
    top: (@Composable ColumnScope.() -> Unit)? = null,
    /** Below the content, always in view (the sheet's main action). */
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || canClose() })
    ModalBottomSheet(
        onDismissRequest = { if (canClose()) onDismiss() }, sheetState = sheet, containerColor = K.Surface,
        scrimColor = if (tall) K.Bg.copy(alpha = 0.5f) else Color.Transparent,
    ) {
        Column((if (tall) Modifier.fillMaxHeight(0.9f) else Modifier).fillMaxWidth().upwardStaysInside().testTag(tag)) {
            SheetHeader(title) { if (canClose()) onDismiss() }
            top?.invoke(this)
            Column(if (tall) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier) {
                content()
                Spacer(Modifier.height(24.dp))
            }
            bottom?.invoke(this)
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

/**
 * A title: its text first, then its look and how it comes in, added at the playhead (the button always in view);
 * the titles already on the timeline in a tab of their own.
 */
@Composable
internal fun TitleSheet(state: EditorState, onDismiss: () -> Unit) {
    val backend = state.props ?: return
    var tab by remember { mutableStateOf(0) }
    val list = tab == 1 && state.clips.any { it.kind == ClipKind.TITLE }
    AddSheet(
        stringResource(R.string.design_add_title), "titleSheet", onDismiss,
        top = { TitleTabs(state, tab) { tab = it } },
        bottom = if (list) null else ({ TitleAddButton(state, backend, onDismiss) }),
    ) {
        if (list) TitlesOnTimeline(state, onDismiss) else TitleNew(state)
    }
}

/**
 * Captions, in three parts: made from the speech, written by hand (or a file imported), and those in the project
 * (their track, style and list). Writing has its add button below, always in view.
 */
@Composable
internal fun CaptionsSheet(state: EditorState, onDismiss: () -> Unit) {
    val backend = state.props ?: return
    var part by remember { mutableStateOf(firstCaptionsPart(state)) }
    AddSheet(
        stringResource(R.string.design_add_captions), "captionsSheet", onDismiss,
        top = { CaptionTabs(state, part) { part = it } },
        bottom = if (part == CaptionsPart.WRITE) ({ CaptionWriteButton(state, backend) }) else null,
    ) {
        CaptionsPartContent(state, backend, part, { part = it }, onDismiss)
    }
}
