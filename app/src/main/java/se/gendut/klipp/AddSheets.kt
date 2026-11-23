package se.gendut.klipp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
    /** The content's scroll (a sheet that shows different views keeps one per view). */
    scroll: androidx.compose.foundation.ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DockSheet(tag, onDismiss, canClose, tall) { close ->
        // a low screen (landscape): everything scrolls as one – the tabs and the button kept in view would leave the
        // content a sliver, and with the keyboard up the field being typed in no room at all
        val compact = tall && LocalConfiguration.current.screenHeightDp < COMPACT_SHEET_HEIGHT
        val ownScroll = rememberScrollState()
        val sc = scroll ?: ownScroll
        Box(Modifier.fillMaxWidth().then(if (tall) Modifier.weight(1f) else Modifier).upwardStaysInside()) {
            if (compact) Column(Modifier.fillMaxSize().verticalScroll(sc)) {
                SheetHeader(title) { close() }
                top?.invoke(this)
                content()
                bottom?.invoke(this)
                Spacer(Modifier.height(16.dp))
            } else Column((if (tall) Modifier.fillMaxHeight() else Modifier).fillMaxWidth()) {
                SheetHeader(title) { close() }
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
        top = {
            // simple mode: captions from the speech are text too (the mockup's Text sheet) – the captions sheet
            if (state.simple) SimpleCaptionsEntry { onDismiss(); state.onAddAction?.invoke(AddAction.CAPTIONS) }
            TitleTabs(state, tab) { tab = it }
        },
        bottom = if (list) null else ({ TitleAddButton(state, backend, onDismiss) }),
    ) {
        if (list) TitlesOnTimeline(state, onDismiss) else TitleNew(state)
    }
}

/** Simple mode's way from Text to captions made from what is said (the captions sheet, as advanced mode's Captions). */
@Composable
private fun SimpleCaptionsEntry(onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .border(1.dp, K.Outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { haptics.press(); onClick() }.padding(12.dp).testTag("simple:captions"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Subs, null, tint = K.PrimarySoft, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.simple_captions_from_speech), color = K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.simple_captions_hint), color = K.OnSurfaceVar, fontSize = 12.sp)
        }
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
