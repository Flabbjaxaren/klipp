package se.gendut.klipp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * How much of the screen a docked sheet takes: it opens [DOCK_HIGH] (expanded – the whole editor below the top bar) and
 * can be pulled down to [DOCK_LOW], a stable smaller state where the picture and the transport are in view above it.
 * Only these two: a release (on the handle or from the content's top) settles on the nearer one, or the one the finger
 * was moving towards.
 */
internal const val DOCK_LOW = 0.45f
internal const val DOCK_HIGH = 0.9f
/** In landscape: the editor's left column (App's landscape row). */
private const val SIDE_WIDTH = 0.46f
/** A release this fast (fraction of the screen per second) goes on in its direction, whatever the position. */
private const val FLING = 0.6f

/** Where a sheet released at [frac] moving at [velocity] (fraction per second, + = up) comes to rest. */
internal fun dockRestingPlace(frac: Float, velocity: Float): Float = when {
    velocity > FLING -> DOCK_HIGH
    velocity < -FLING -> DOCK_LOW
    else -> if (frac >= (DOCK_LOW + DOCK_HIGH) / 2f) DOCK_HIGH else DOCK_LOW
}

/**
 * A sheet docked at the bottom of the editor (Add › Title, Captions, Sound, Voice-over): it lies over the lower part
 * only and doesn't block the rest – what is above it can still be touched while it is open. It opens expanded; its
 * handle or a pull down from the top of its content lowers it to the smaller state, a push up raises it again (before
 * the content scrolls). One animation owns the height: it follows the finger exactly and goes on from the finger's
 * speed when released – no jump to a lagging spring, no bounce. With the keyboard up it takes the room above it, on a
 * low screen (landscape) all of it. It is a part of the editor's own window, so the editor's finger taps (undo, redo)
 * work on it as everywhere else. [onDismiss] after it has slid away; [canClose] can keep it open (a recording
 * voice-over).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DockSheet(
    tag: String,
    onDismiss: () -> Unit,
    canClose: () -> Boolean = { true },
    /** A tall sheet (most): expanded or smaller; a short one keeps its own height (the voice-over). */
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
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_SHEET_HEIGHT
    // landscape: over the left column (the picture and the tools, as the editor's bottom bar) – the timeline on the
    // right stays usable
    val side = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val ime = WindowInsets.isImeVisible
    // the height as a fraction of the screen: the finger's while dragged, then animated from there (see dockRestingPlace)
    // (its "arrived" threshold well under a pixel: the default 0.01 of the screen is ~28 px – the last bit jumped)
    val pos = remember { androidx.compose.animation.core.Animatable(DOCK_HIGH, visibilityThreshold = 0.0002f) }
    var rest by remember { mutableStateOf(DOCK_HIGH) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val locked = compact || ime
    // keyboard up or a low screen: the whole room (the field being typed in stays in view); back to where it rested after
    LaunchedEffect(locked) { pos.animateTo(if (locked) DOCK_HIGH else rest, spring(1f, 500f)) }
    // the finger's own height, kept here at once (several moves can come in one frame – read back from the animation,
    // all but the last were lost); NaN = no drag going on
    val finger = remember { floatArrayOf(Float.NaN) }
    // a drag or its settling under way: the sheet keeps its tallest layout and only moves (see the Column below)
    var dragging by remember { mutableStateOf(false) }
    // the height last shown for the finger (the animation may not have taken it yet when the finger lifts)
    val shown = remember { floatArrayOf(DOCK_HIGH) }
    fun dragBy(dyPx: Float) {
        if (locked) return
        if (finger[0].isNaN()) { finger[0] = pos.value; dragging = true }
        finger[0] = (finger[0] - dyPx / screenPx).coerceIn(DOCK_LOW - 0.15f, DOCK_HIGH + 0.08f)
        val f = finger[0]
        // past the two states the finger is resisted (a third of its way)
        val v = when {
            f > DOCK_HIGH -> DOCK_HIGH + (f - DOCK_HIGH) / 3f
            f < DOCK_LOW -> DOCK_LOW - (DOCK_LOW - f) / 3f
            else -> f
        }
        shown[0] = v
        scope.launch { pos.snapTo(v) }
    }
    suspend fun settle(velocityPxPerS: Float) {
        val dragged = !finger[0].isNaN()
        finger[0] = Float.NaN
        try {
            if (locked) return
            val v = -velocityPxPerS / screenPx
            val from = if (dragged) shown[0] else pos.value
            rest = dockRestingPlace(from, v)
            pos.snapTo(from)
            // (critically damped: it arrives without overshooting)
            pos.animateTo(rest, spring(1f, 500f), initialVelocity = v)
        } finally { dragging = false }
    }
    // the content's scrolls: up raises the sheet before the content moves, down from the content's top lowers it; a
    // fling that ends a drag of the sheet settles it
    val nested = remember(screenPx, locked) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (locked || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                return if (available.y < 0f && pos.value < DOCK_HIGH - 1e-3f) { dragBy(available.y); androidx.compose.ui.geometry.Offset(0f, available.y) }
                else androidx.compose.ui.geometry.Offset.Zero
            }
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (locked || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                return if (available.y > 0f) { dragBy(available.y); androidx.compose.ui.geometry.Offset(0f, available.y) } else androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                // the sheet moved in this gesture: it takes the whole fling
                if (locked || finger[0].isNaN()) return androidx.compose.ui.unit.Velocity.Zero
                settle(available.y)
                return available
            }
            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (!locked && !finger[0].isNaN()) settle(available.y)
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    val haptics = rememberHaptics()
    // (the box itself takes no touches: only the sheet does – what lies above it stays usable)
    Box(Modifier.fillMaxSize(), contentAlignment = if (side) Alignment.BottomStart else Alignment.BottomCenter) {
        AnimatedVisibility(vis, enter = slideInVertically(spring(0.8f, 400f)) { it }, exit = slideOutVertically(spring(1f, 500f)) { it }) {
            // while it moves the sheet is laid out at its tallest and only shifted down (a layer offset: no measuring of
            // its content in every frame – the drag stuttered on the OnePlus, 8 % of the frames late with a slow UI
            // thread); at rest it gets its own height, once
            val moving = tall && !locked && (dragging || pos.isRunning)
            val layoutFrac = if (moving) DOCK_HIGH + 0.02f else pos.value.coerceIn(0.05f, 1f)
            Column(
                (if (tall) Modifier.fillMaxHeight(layoutFrac).graphicsLayer { translationY = if (moving) (layoutFrac - pos.value) * screenPx else 0f } else Modifier)
                    .fillMaxWidth(if (side) SIDE_WIDTH else 1f)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(K.Surface)
                    // the sheet's empty parts take the touch too (the editor lies under them); and when the last finger
                    // lifts from a drag that moved the sheet without a fling after it (content that didn't scroll), it
                    // settles here – it was left between its states, laid out for moving
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.none { it.pressed } && !finger[0].isNaN()) {
                                    // (after the content's own fling has had its turn: it settles with the finger's speed)
                                    scope.launch { kotlinx.coroutines.delay(32); if (!finger[0].isNaN()) settle(0f) }
                                }
                            }
                        }
                    }
                    .then(if (tall) Modifier.nestedScroll(nested)
                        // the whole sheet drags where nothing in it scrolls (the handle, the header, the tabs, the button
                        // row – a scrolling list takes its own drags first and hands them on through [nested])
                        .draggable(rememberDraggableState { dy -> dragBy(dy) }, Orientation.Vertical, onDragStopped = { v -> settle(v) })
                        else Modifier)
                    .imePadding().navigationBarsPadding()
                    .testTag(tag),
            ) {
                // the handle: tap = the other state (dragged with the whole sheet); a short sheet has none
                if (tall && !compact) Box(
                    Modifier.fillMaxWidth().height(28.dp)
                        .clickable(onClickLabel = stringResource(if (rest > (DOCK_LOW + DOCK_HIGH) / 2f) R.string.dock_lower else R.string.dock_raise)) {
                            if (locked) return@clickable
                            haptics.tick()
                            rest = if (rest > (DOCK_LOW + DOCK_HIGH) / 2f) DOCK_LOW else DOCK_HIGH
                            scope.launch { pos.animateTo(rest, spring(1f, 500f)) }
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
