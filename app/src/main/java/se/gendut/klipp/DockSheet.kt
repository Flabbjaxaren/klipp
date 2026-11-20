package se.gendut.klipp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * How much of the screen a docked sheet takes: low (the picture and the timeline's tracks in view above it – on a
 * phone the picture, the transport and the tool row take the upper 45 %, the tracks the next fifth) or high. The handle
 * is dragged to any height between [DOCK_MIN] and [DOCK_HIGH]; the height is kept for the next sheet.
 */
private const val DOCK_LOW = 0.36f
private const val DOCK_HIGH = 0.9f
private const val DOCK_MIN = 0.24f
private var dockHeight = DOCK_LOW
/** In landscape: the editor's left column (App's landscape row). */
private const val SIDE_WIDTH = 0.46f

/**
 * A sheet docked at the bottom of the editor (Add › Title, Captions, Sound, Voice-over): it lies over the lower part
 * only and doesn't block the rest – the picture and the timeline above it can still be touched (a track, a clip, a
 * caption) while it is open. Its handle raises and lowers it; with the keyboard up it takes the room above it. It is a
 * part of the editor's own window, so the editor's finger taps (undo, redo) work on it as everywhere else.
 * [onDismiss] after it has slid away; [canClose] can keep it open (a recording voice-over).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DockSheet(
    tag: String,
    onDismiss: () -> Unit,
    canClose: () -> Boolean = { true },
    /** A tall sheet (most): low or high by the handle; a short one keeps its own height (the voice-over). */
    tall: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val vis = remember { MutableTransitionState(false) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vis.targetState = true; started = true }
    val close = { if (canClose()) vis.targetState = false }
    // gone once it has slid away
    LaunchedEffect(vis.currentState, vis.isIdle) { if (started && vis.isIdle && !vis.currentState && !vis.targetState) onDismiss() }
    androidx.activity.compose.BackHandler(enabled = vis.targetState) { close() }
    var frac by remember { mutableStateOf(dockHeight) }
    var dragging by remember { mutableStateOf(false) }
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_SHEET_HEIGHT
    // landscape: over the left column (the picture and the tools, as the editor's bottom bar) – the timeline on the
    // right stays usable
    val side = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val ime = WindowInsets.isImeVisible
    val target = if (compact || ime) DOCK_HIGH else frac
    val anim by animateFloatAsState(target, spring(1f, 400f), label = "dockHeight")
    // (while dragged: the finger's height, not the spring's)
    val h = if (dragging) target else anim
    val haptics = rememberHaptics()
    // (the box itself takes no touches: only the sheet does – what lies above it stays usable)
    Box(Modifier.fillMaxSize(), contentAlignment = if (side) Alignment.BottomStart else Alignment.BottomCenter) {
        AnimatedVisibility(vis, enter = slideInVertically(spring(0.8f, 400f)) { it }, exit = slideOutVertically(spring(1f, 500f)) { it }) {
            Column(
                (if (tall) Modifier.fillMaxHeight(h) else Modifier).fillMaxWidth(if (side) SIDE_WIDTH else 1f)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(K.Surface)
                    // the sheet's empty parts take the touch too (the editor lies under them)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    .imePadding().navigationBarsPadding()
                    .testTag(tag),
            ) {
                // the handle: drag to any height, tap = raise or lower (a short sheet has none)
                if (tall && !compact) Box(
                    Modifier.fillMaxWidth().height(28.dp)
                        .pointerInput(screenPx) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true }, onDragEnd = { dragging = false; dockHeight = frac }, onDragCancel = { dragging = false },
                            ) { ch, dy -> ch.consume(); frac = (frac - dy / screenPx).coerceIn(DOCK_MIN, DOCK_HIGH) }
                        }
                        .clickable(onClickLabel = stringResource(if (frac > 0.6f) R.string.dock_lower else R.string.dock_raise)) {
                            haptics.tick(); frac = if (frac > 0.6f) DOCK_LOW else DOCK_HIGH; dockHeight = frac
                        }
                        .semantics { contentDescription = "dockHandle" }.testTag("dockHandle"),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(K.OutlineVar)) }
                else Box(Modifier.height(10.dp))
                content(close)
            }
        }
    }
}
