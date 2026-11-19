package se.gendut.klipp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxSize
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
/** Below this screen height (dp) a tall sheet scrolls as one, its tabs and main button included (landscape). */
const val COMPACT_SHEET_HEIGHT = 480

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
    /** How much of the screen a tall sheet takes – lower to keep the picture in view above it (the caption style). */
    heightFraction: Float = 0.9f,
    /** The content's scroll (a sheet that shows different views keeps one per view). */
    scroll: androidx.compose.foundation.ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || canClose() })
    ModalBottomSheet(
        onDismissRequest = { if (canClose()) onDismiss() }, sheetState = sheet, containerColor = K.Surface,
        scrimColor = if (tall && heightFraction > 0.75f) K.Bg.copy(alpha = 0.5f) else Color.Transparent,
    ) {
        // a low screen (landscape): everything scrolls as one – the tabs and the button kept in view would leave the
        // content a sliver, and with the keyboard up the field being typed in no room at all
        val compact = tall && LocalConfiguration.current.screenHeightDp < COMPACT_SHEET_HEIGHT
        val ownScroll = rememberScrollState()
        val sc = scroll ?: ownScroll
        val h by androidx.compose.animation.core.animateFloatAsState(heightFraction, androidx.compose.animation.core.spring(1f, 400f), label = "sheetHeight")
        Box((if (tall) Modifier.fillMaxHeight(h) else Modifier).fillMaxWidth().upwardStaysInside().fingerTaps().testTag(tag)) {
            if (compact) Column(Modifier.fillMaxSize().verticalScroll(sc)) {
                SheetHeader(title) { if (canClose()) onDismiss() }
                top?.invoke(this)
                content()
                bottom?.invoke(this)
                Spacer(Modifier.height(16.dp))
            } else Column((if (tall) Modifier.fillMaxHeight() else Modifier).fillMaxWidth()) {
                SheetHeader(title) { if (canClose()) onDismiss() }
                top?.invoke(this)
                Column(if (tall) Modifier.weight(1f).verticalScroll(sc) else Modifier) {
                    content()
                    Spacer(Modifier.height(24.dp))
                }
                bottom?.invoke(this)
            }
            // a message (an action refused, an error) shows here, over the sheet – not behind it
            MessageHost(Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
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
    // the style view: in the same sheet, lowered so the picture shows above it; back returns to the overview as it was
    var styleTrack by remember { mutableStateOf<Long?>(null) }
    var styleTab by remember { mutableStateOf(CaptionStyleTab.STYLES) }
    val overviewScroll = rememberScrollState()
    val styleScroll = rememberScrollState()
    val st = styleTrack?.let { id -> backend.captionTracks.firstOrNull { it.id == id } }
    if (styleTrack != null && st == null) styleTrack = null
    androidx.activity.compose.BackHandler(enabled = st != null) { styleTrack = null }
    // one sheet for both views (it stays open while switching): its title, top, height and scroll follow the view
    AddSheet(
        stringResource(if (st != null) R.string.captionstyle_title else R.string.design_add_captions), "captionsSheet", onDismiss,
        top = {
            if (st != null) {
                CaptionStyleHeader(st.name) { styleTrack = null }
                CaptionStyleTabs(backend.captionStyle(st.id) ?: se.gendut.klipp.engine.CaptionStyle(), styleTab, { styleTab = it })
            } else CaptionTabs(state, part) { part = it }
        },
        bottom = if (st == null && part == CaptionsPart.WRITE) ({ CaptionWriteButton(state, backend) }) else null,
        heightFraction = if (st != null) 0.58f else 0.9f,
        scroll = if (st != null) styleScroll else overviewScroll,
    ) {
        if (st != null) androidx.compose.foundation.layout.Column(Modifier.testTag("captionStyleView")) { CaptionStyleContent(backend, st.id, styleTab) }
        else CaptionsPartContent(state, backend, part, { part = it }, onDismiss) { id -> styleTrack = id; styleTab = CaptionStyleTab.STYLES }
    }
}
