package se.gendut.klipp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.offset
import kotlin.math.abs

/**
 * The part of the clip the keyframe lanes show (fractions 0..1 of the clip), shared by all lanes in a panel: zoomed with
 * the bar under the lanes or by pinching a lane, so close keyframes can be told apart and grabbed.
 */
class KfView {
    var a by mutableFloatStateOf(0f)
        private set
    var b by mutableFloatStateOf(1f)
        private set
    val zoomed: Boolean get() = b - a < 0.999f

    fun set(na: Float, nb: Float) {
        val w = (nb - na).coerceIn(MIN_SPAN, 1f)
        val s = na.coerceIn(0f, 1f - w)
        a = s; b = s + w
    }
    /** Zoom by [factor] (> 1 = in) keeping clip fraction [f] where it is on screen. */
    fun zoomAround(f: Float, factor: Float) {
        val w = ((b - a) / factor).coerceIn(MIN_SPAN, 1f)
        val rel = if (b > a) (f - a) / (b - a) else 0.5f
        set(f - rel * w, f - rel * w + w)
    }
    fun pan(df: Float) = set(a + df, b + df)
    /** Bring clip fraction [f] into view (centered) if it is outside. */
    fun show(f: Float) { if (f < a || f > b) { val w = b - a; set(f - w / 2, f + w / 2) } }
    fun reset() = set(0f, 1f)

    /** The lanes whose value graph is open. */
    var graphs by mutableStateOf(emptySet<String>())
    fun toggleGraph(key: String) { graphs = if (key in graphs) graphs - key else graphs + key }

    companion object {
        /** At most 50× zoom. */
        const val MIN_SPAN = 0.02f
    }
}

/** Everything a slider row needs for keyframes. Times in timeline seconds. */
class AnimUi(
    val key: String,
    val keys: List<UiKf>,
    val clipStart: Float,
    val clipEnd: Float,
    /** Read only when needed (in drawing/lambdas), so the panel isn't recomposed every frame. */
    private val playheadOf: () -> Float,
    val onToggle: () -> Unit,
    val onKey: () -> Unit,
    val onJump: (Float) -> Unit,
    val onMove: (UiKf, Float) -> Unit,
    val onInterp: (UiKf) -> Unit,
    /** The lanes' zoom (shared by the panel's lanes). */
    val view: KfView = KfView(),
    /** This lane carries the zoom bar (the lowest animated lane in the panel). */
    val zoomBar: Boolean = true,
    /** Long-press menu: linear / easy ease / ease in / ease out / hold (KfEase). Null = long-press cycles via [onInterp]. */
    val onEase: ((UiKf, String) -> Unit)? = null,
    /**
     * The value graph: the ease handles' influence (in, out; null = unchanged) and, when the dragged side didn't ease
     * yet, the keyframe's new interpolation (see handleEase) …
     */
    val onInfluence: ((UiKf, Double?, Double?, String?) -> Unit)? = null,
    /** … and a keyframe's new value(s). Null = no graph. */
    val onValue: ((UiKf, List<Double>) -> Unit)? = null,
    /** The value as shown next to the slider (while dragging a point in the graph). */
    val format: (Float) -> String = { "%.1f".format(it) },
) {
    val animated get() = keys.isNotEmpty()
    /** The clip fraction of timeline time [t]. */
    fun frac(t: Float): Float = ((t - clipStart) / (clipEnd - clipStart).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
    val playhead: Float get() = playheadOf()
    private val frame get() = FRAME
    /** Where the playhead stands for keyframe [k]: its time, at most the clip's last frame ([lastFrame]). */
    private fun at(k: UiKf) = k.t.coerceIn(clipStart, lastFrame(clipStart, clipEnd))
    /** The keyframe at the playhead (within half a frame). */
    val atPlayhead: UiKf? get() = keys.firstOrNull { abs(at(it) - playhead.coerceIn(clipStart, clipEnd)) < frame / 2 }
    val prev: UiKf? get() = keys.lastOrNull { at(it) < playhead - frame / 2 }
    val next: UiKf? get() = keys.firstOrNull { at(it) > playhead + frame / 2 }
}

/** The keyframe menu (long-press a keyframe): the interpolation after it, and the graph. */
@Composable
private fun EaseMenu(k: UiKf, anim: AnimUi, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val cur = se.gendut.klipp.engine.KfEase.of(k.interp)
    androidx.compose.material3.DropdownMenu(true, onDismiss, shape = RoundedCornerShape(20.dp), containerColor = K.SurfaceHighest,
        modifier = Modifier.testTag("kfmenu:${anim.key}")) {
        Text(stringResource(R.string.keyframeui_interp_title), color = K.OnSurfaceVar, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        for ((ease, label) in listOf(
            se.gendut.klipp.engine.KfEase.LINEAR to R.string.keyframeui_linear,
            se.gendut.klipp.engine.KfEase.EASY to R.string.keyframeui_easy_ease,
            se.gendut.klipp.engine.KfEase.IN to R.string.keyframeui_ease_in,
            se.gendut.klipp.engine.KfEase.OUT to R.string.keyframeui_ease_out,
            se.gendut.klipp.engine.KfEase.HOLD to R.string.keyframeui_hold,
        )) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(label), color = K.OnSurface, fontSize = 14.sp) },
                leadingIcon = { EaseIcon(ease, ease == cur) },
                onClick = { onPick(ease) },
                modifier = Modifier.testTag("kfease:$ease"),
            )
        }
        if (anim.onValue != null) {
            androidx.compose.material3.HorizontalDivider(color = K.OutlineVar)
            val open = anim.key in anim.view.graphs
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(if (open) R.string.keyframeui_hide_graph else R.string.keyframeui_show_graph), color = K.OnSurface, fontSize = 14.sp) },
                leadingIcon = { Icon(Ic.Graph, null, tint = K.OnSurfaceVar, modifier = Modifier.size(20.dp)) },
                onClick = { onDismiss(); anim.view.toggleGraph(anim.key) },
                modifier = Modifier.testTag("kfmenugraph"),
            )
        }
    }
}

/** A small picture of the curve for each interpolation in the menu. */
@Composable
private fun EaseIcon(ease: String, selected: Boolean) {
    val col = if (selected) K.Primary else K.OnSurfaceVar
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val path = Path()
        val (aI, bI) = when (ease) {
            se.gendut.klipp.engine.KfEase.EASY -> "Bezier" to "Bezier"
            se.gendut.klipp.engine.KfEase.IN -> "Linear" to "EaseIn"
            se.gendut.klipp.engine.KfEase.OUT -> "EaseOut" to "Linear"
            se.gendut.klipp.engine.KfEase.HOLD -> "Hold" to "Linear"
            else -> "Linear" to "Linear"
        }
        for (i in 0..20) {
            val u = i / 20.0
            val y = if (aI == "Hold") (if (i == 20) 1.0 else 0.0) else se.gendut.klipp.engine.ease(aI, se.gendut.klipp.engine.DEFAULT_INFLUENCE, bI, se.gendut.klipp.engine.DEFAULT_INFLUENCE, u)
            val px = (u * w).toFloat(); val py = (h - y * h).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, col, style = Stroke((if (selected) 2.5f else 1.8f) * density))
    }
}

/**
 * The clip's last frame: where a jump within the clip stops. The clip's end is the next clip's first frame, so a
 * keyframe at the end (e.g. after the clip was shortened) showed the next clip.
 */
fun lastFrame(start: Float, end: Float): Float = (end - FRAME).coerceAtLeast(start)

/** Next interpolation when long-pressing a keyframe: linear → smooth → hold → linear. */
fun nextInterp(i: String): String = when (i) {
    "Linear" -> "autoBezier"
    "Hold" -> "linear"
    "EaseIn", "EaseOut", "Bezier", "AutoBezier", "ContinuousBezier" -> "hold"
    else -> "linear"
}

/** The stopwatch before the label: lit = the property is animated. */
@Composable
fun Stopwatch(anim: AnimUi) {
    val haptics = rememberHaptics()
    val bg by animateColorAsState(if (anim.animated) K.Primary else Color.Transparent, label = "sw")
    val fg by animateColorAsState(if (anim.animated) K.OnPrimary else K.OnSurfaceVar, label = "swf")
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { haptics.confirm(); anim.onToggle() }
            .testTag("stopwatch:${anim.key}"),
        contentAlignment = Alignment.Center,
    ) { Icon(Ic.Stopwatch, stringResource(R.string.keyframeui_animate), tint = fg, modifier = Modifier.size(18.dp)) }
}

/**
 * The keyframe lane under an animated slider: the clip's length, keyframes as diamonds (smooth = round,
 * hold = square), the playhead. Tap = jump there, drag a keyframe = move it, long-press = change interpolation.
 * ◀ ▶ jump between keyframes, ◆ adds or removes the keyframe at the playhead.
 */
@Composable
fun KeyframeLane(anim: AnimUi) {
    AnimatedVisibility(anim.animated, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val haptics = rememberHaptics()
        val a by rememberUpdatedState(anim)
        var drag by remember { mutableStateOf<Pair<UiKf, Float>?>(null) }
        // the keyframe whose menu is open, and where (px in the lane)
        var menu by remember { mutableStateOf<Pair<UiKf, Float>?>(null) }
        // the zoom follows the playhead when it leaves the visible part (playback, jumps)
        androidx.compose.runtime.LaunchedEffect(anim.view) {
            androidx.compose.runtime.snapshotFlow { a.frac(a.playhead) }.collect { f -> if (a.view.zoomed) a.view.show(f) }
        }
        androidx.compose.foundation.layout.Column {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // the graph button keeps clear of the lane (a keyframe at the clip's start sits right at its edge)
            Box(Modifier.width(108.dp).padding(end = 16.dp), contentAlignment = Alignment.CenterEnd) {
                if (anim.onValue != null) {
                    val open = anim.key in anim.view.graphs
                    SmallIcon(Ic.Graph, stringResource(if (open) R.string.keyframeui_hide_graph else R.string.keyframeui_show_graph), true, "kfgraph:${anim.key}", filled = open) {
                        haptics.tick(); anim.view.toggleGraph(anim.key)
                    }
                }
            }
            Box(
                Modifier.weight(1f).height(32.dp).testTag("lane:${anim.key}")
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val span = (a.clipEnd - a.clipStart).coerceAtLeast(0.01f)
                            val v = a.view
                            // through the zoom: the lane shows the clip fractions v.a..v.b
                            fun tOf(x: Float) = a.clipStart + (v.a + (x / size.width).coerceIn(0f, 1f) * (v.b - v.a)) * span
                            // clamped like the drawing: a keyframe outside the clip (after a trim) sits at the lane's edge and is grabbed there
                            fun xOf(t: Float) = (((t - a.clipStart) / span).coerceIn(0f, 1f) - v.a) / (v.b - v.a) * size.width
                            val hit = a.keys.minByOrNull { abs(xOf(it.t) - down.position.x) }?.takeIf { abs(xOf(it.t) - down.position.x) < 16.dp.toPx() }
                            var moved = false
                            var cancelled = false
                            val longPress = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                            var now = down.uptimeMillis
                            while (true) {
                                // long-press on a keyframe: no movement before the timeout (counted from the press)
                                val wait = longPress - now
                                val ev = if (!moved && hit != null) {
                                    withTimeoutOrNull(wait.coerceAtLeast(1)) { awaitPointerEvent() }
                                } else awaitPointerEvent()
                                if (ev == null) {
                                    haptics.confirm(); cancelled = true
                                    if (a.onEase != null) menu = hit!! to xOf(hit.t) else a.onInterp(hit!!)
                                    while (awaitPointerEvent().changes.any { it.pressed }) { }
                                    break
                                }
                                // two fingers: pinch = zoom the lanes (and pan with the fingers' midpoint)
                                if (ev.changes.count { it.pressed } >= 2) {
                                    drag = null; cancelled = true
                                    var last: Pair<Float, Float>? = null
                                    var e2: androidx.compose.ui.input.pointer.PointerEvent = ev
                                    while (true) {
                                        val ps = e2.changes.filter { it.pressed }
                                        if (ps.size < 2) break
                                        val x0 = ps[0].position.x; val x1 = ps[1].position.x
                                        val spanPx = abs(x1 - x0).coerceAtLeast(24.dp.toPx())
                                        val mid = (x0 + x1) / 2
                                        last?.let { (lSpan, lMid) ->
                                            val f = v.a + (lMid / size.width) * (v.b - v.a)
                                            v.zoomAround(f, spanPx / lSpan)
                                            v.pan(-(mid - lMid) / size.width * (v.b - v.a))
                                        }
                                        last = spanPx to mid
                                        e2.changes.forEach { it.consume() }
                                        e2 = awaitPointerEvent()
                                    }
                                    while (awaitPointerEvent().changes.any { it.pressed }) { }
                                    break
                                }
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                now = c.uptimeMillis
                                if (!c.pressed) break
                                if (!moved && abs(c.position.x - down.position.x) > viewConfiguration.touchSlop / 2) {
                                    if (abs(c.position.y - down.position.y) > abs(c.position.x - down.position.x)) { cancelled = true; break }
                                    moved = true
                                }
                                if (moved && hit != null) {
                                    val nt = snapFrame(tOf(c.position.x))
                                    if (drag?.second != nt) haptics.tick()
                                    drag = hit to nt
                                    c.consume()
                                } else if (moved) {
                                    // scrub the playhead along the lane
                                    a.onJump(tOf(c.position.x)); c.consume()
                                }
                            }
                            val d = drag
                            drag = null
                            if (cancelled) return@awaitEachGesture
                            when {
                                d != null -> { haptics.confirm(); a.onMove(d.first, d.second); a.onJump(d.second) }
                                hit != null && !moved -> { haptics.tick(); a.onJump(hit.t) }
                                !moved -> a.onJump(tOf(down.position.x))
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                val keyPath = remember { Path() }
                val sortedKeys = remember(anim.keys) { anim.keys.sortedBy { it.t } }
                Canvas(Modifier.fillMaxWidth().height(32.dp).clipToBounds()) {
                    val span = (anim.clipEnd - anim.clipStart).coerceAtLeast(0.01f)
                    val v = anim.view
                    fun xOf(t: Float) = (((t - anim.clipStart) / span).coerceIn(0f, 1f) - v.a) / (v.b - v.a) * size.width
                    val cy = size.height / 2
                    drawRoundRect(K.SurfaceHigh, Offset(0f, cy - 3.dp.toPx()), Size(size.width, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                    // lines between keyframes (dashed = hold)
                    // without a drag the keyframes are already in time order (nothing new per frame while the playhead moves)
                    val d = drag
                    val ks = if (d == null) sortedKeys else anim.keys.map { k -> if (d.first == k) k.copy(t = d.second) else k }.sortedBy { it.t }
                    for (i in 0 until ks.size - 1) {
                        drawLine(K.Primary.copy(alpha = if (ks[i].interp == "Hold") 0.3f else 0.7f), Offset(xOf(ks[i].t), cy), Offset(xOf(ks[i + 1].t), cy), 2.dp.toPx())
                    }
                    val ph = xOf(anim.playhead)
                    drawRect(K.Primary, Offset(ph - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                    val on = anim.atPlayhead
                    for (k in ks) keyShape(keyPath, Offset(xOf(k.t), cy), k.interp, k.mt == on?.mt || d?.first?.mt == k.mt)
                }
                menu?.let { (k, x) ->
                    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.toInt(), 0) }) {
                        EaseMenu(k, anim, onDismiss = { menu = null }) { ease -> menu = null; haptics.confirm(); anim.onEase?.invoke(k, ease) }
                    }
                }
            }
            // only when the keyframe at/before/after the playhead changes – not every frame
            val prev by remember { derivedStateOf { a.prev } }
            val next by remember { derivedStateOf { a.next } }
            val on by remember { derivedStateOf { a.atPlayhead } }
            SmallIcon(Ic.KfPrev, stringResource(R.string.keyframeui_prev_keyframe), prev != null, "kfprev:${anim.key}") { prev?.let { haptics.tick(); anim.onJump(it.t); anim.view.show(anim.frac(it.t)) } }
            SmallIcon(Ic.Diamond, if (on != null) stringResource(R.string.keyframeui_remove_keyframe) else stringResource(R.string.keyframeui_add_keyframe), true, "kf:${anim.key}", filled = on != null) { haptics.confirm(); anim.onKey() }
            SmallIcon(Ic.KfNext, stringResource(R.string.keyframeui_next_keyframe), next != null, "kfnext:${anim.key}") { next?.let { haptics.tick(); anim.onJump(it.t); anim.view.show(anim.frac(it.t)) } }
        }
        AnimatedVisibility(anim.onValue != null && anim.key in anim.view.graphs, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            KfGraph(anim)
        }
        if (anim.zoomBar) KfZoomBar(anim)
        }
    }
}

/**
 * The zoom bar under the lanes (as in FilmCraft's Effect Controls): the whole clip, with the visible part as a thumb.
 * Drag the thumb = pan, drag one of its ends = zoom, tap beside it = go there, double tap = the whole clip.
 */
@Composable
private fun KfZoomBar(anim: AnimUi) {
    val haptics = rememberHaptics()
    val a by rememberUpdatedState(anim)
    val v = anim.view
    Row(Modifier.fillMaxWidth().height(26.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(108.dp))
        Canvas(
            Modifier.weight(1f).height(22.dp).testTag("kfzoom:${anim.key}")
                .pointerInput(Unit) {
                    var lastTap = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (down.uptimeMillis - lastTap < viewConfiguration.doubleTapTimeoutMillis) { lastTap = 0L; haptics.tick(); v.reset(); return@awaitEachGesture }
                        val w = size.width.toFloat()
                        val grab = 16.dp.toPx()
                        val x0 = v.a * w; val x1 = v.b * w
                        val x = down.position.x
                        // 1 = left end, 2 = right end, 3 = pan, 0 = beside the thumb
                        val mode = when {
                            abs(x - x0) < grab && abs(x - x0) <= abs(x - x1) -> 1
                            abs(x - x1) < grab -> 2
                            x in x0..x1 -> 3
                            else -> 0
                        }
                        if (mode == 0) { val ww = v.b - v.a; v.set(x / w - ww / 2, x / w + ww / 2) }
                        val a0 = v.a; val b0 = v.b
                        var moved = false
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val df = (c.position.x - down.position.x) / w
                            if (abs(c.position.x - down.position.x) > viewConfiguration.touchSlop / 2) moved = true
                            when (mode) {
                                1 -> v.set((a0 + df).coerceAtMost(b0 - KfView.MIN_SPAN), b0)
                                2 -> v.set(a0, (b0 + df).coerceAtLeast(a0 + KfView.MIN_SPAN))
                                else -> v.set(a0 + df, b0 + df)
                            }
                            c.consume()
                        }
                        if (!moved && mode != 0) lastTap = down.uptimeMillis
                    }
                }
        ) {
            val h = size.height
            val r = CornerRadius(h / 2)
            drawRoundRect(K.SurfaceHigh, Offset(0f, h * 0.25f), Size(size.width, h * 0.5f), CornerRadius(h / 4))
            // the keyframes over the whole clip, as small ticks
            for (k in a.keys) {
                val kx = a.frac(k.t) * size.width
                drawRect(K.PrimarySoft.copy(alpha = 0.6f), Offset(kx - 1f, h * 0.3f), Size(2f, h * 0.4f))
            }
            val x0 = v.a * size.width; val x1 = v.b * size.width
            drawRoundRect(K.Tonal.copy(alpha = 0.85f), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(6.dp.toPx()), h), r)
            drawRoundRect(K.PrimarySoft, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(6.dp.toPx()), h), r, style = Stroke(1.5.dp.toPx()))
            // grips at the ends
            for (gx in listOf(x0 + 5.dp.toPx(), x1 - 5.dp.toPx())) drawLine(K.PrimarySoft, Offset(gx, h * 0.3f), Offset(gx, h * 0.7f), 2.dp.toPx())
            val ph = a.frac(a.playhead) * size.width
            drawRect(K.Primary, Offset(ph - 1.dp.toPx(), 0f), Size(2.dp.toPx(), h))
        }
        Box(Modifier.width(102.dp))
    }
}

private fun DrawScope.keyShape(path: Path, c: Offset, interp: String, selected: Boolean) {
    val r = (if (selected) 7f else 6f) * density
    val fill = if (selected) K.Primary else K.Bg
    path.reset()
    when (interp) {
        "Hold" -> { path.addRect(androidx.compose.ui.geometry.Rect(c.x - r * 0.8f, c.y - r * 0.8f, c.x + r * 0.8f, c.y + r * 0.8f)) }
        "Linear" -> { path.moveTo(c.x, c.y - r); path.lineTo(c.x + r, c.y); path.lineTo(c.x, c.y + r); path.lineTo(c.x - r, c.y); path.close() }
        else -> { path.addOval(androidx.compose.ui.geometry.Rect(c, r * 0.85f)) }
    }
    drawPath(path, fill)
    drawPath(path, K.Primary, style = Stroke(2f * density))
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, tag: String, filled: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (filled) K.Primary else Color.Transparent, label = "kfb")
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(bg)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (filled) K.OnPrimary else if (enabled) K.OnSurface else K.OutlineVar, modifier = Modifier.size(18.dp)) }
}

/** The value of component [c] at timeline time [t] over keyframes sorted by time – the engine's curve. */
internal fun List<UiKf>.graphValueAt(t: Float, c: Int): Double? {
    if (isEmpty()) return null
    if (t <= first().t) return first().v.getOrNull(c)
    if (t >= last().t) return last().v.getOrNull(c)
    val i = indexOfLast { it.t <= t }.coerceIn(0, size - 2)
    val a = this[i]; val b = this[i + 1]
    val va = a.v.getOrNull(c) ?: return null
    val vb = b.v.getOrNull(c) ?: return va
    val u = if (b.t > a.t) ((t - a.t) / (b.t - a.t)).toDouble() else 1.0
    return va + (vb - va) * se.gendut.klipp.engine.ease(a.interp, a.outInf, b.interp, b.inInf, u)
}

/** The value range the graph shows: the keyframes' values with some air (a flat curve gets a band around it). */
internal fun graphRange(keys: List<UiKf>): Pair<Double, Double> {
    val vs = keys.flatMap { it.v }.filter { it.isFinite() }
    if (vs.isEmpty()) return 0.0 to 1.0
    val lo = vs.min(); val hi = vs.max()
    val pad = if (hi - lo < 1e-6) kotlin.math.max(1.0, kotlin.math.abs(hi) * 0.1) else (hi - lo) * 0.15
    return (lo - pad) to (hi + pad)
}

/**
 * The value graph under a lane (as FilmCraft's Effect Controls graph): the property's curve over the visible part of the
 * clip (the lanes' zoom), keyframes as points, eased sides with flat handles whose length is the influence. Drag a point
 * up/down = its value, drag a handle sideways = its influence (longer = softer), drag elsewhere = move the playhead.
 * Position draws x and y; the handles sit on x (both share the influence).
 */
@Composable
private fun KfGraph(anim: AnimUi) {
    val haptics = rememberHaptics()
    val a by rememberUpdatedState(anim)
    // what a drag changes, kept after release until the engine's keyframes arrive (no jump back)
    var value by remember { mutableStateOf<Triple<Long, Int, Double>?>(null) }
    var infl by remember { mutableStateOf<Triple<Long, Boolean, Double>?>(null) }
    androidx.compose.runtime.LaunchedEffect(anim.keys) { value = null; infl = null }
    val sortedState = rememberUpdatedState(remember(anim.keys) { anim.keys.sortedBy { it.t } })
    // the value scale stays put while the graph is open (a dragged point moves, not the axis); it only grows when a
    // value leaves it, so the lowest point doesn't snap back to the bottom after every change
    var range by remember { mutableStateOf(graphRange(anim.keys)) }
    androidx.compose.runtime.LaunchedEffect(anim.keys) {
        val vs = anim.keys.flatMap { it.v }.filter { it.isFinite() }
        if (vs.isNotEmpty() && (vs.min() < range.first || vs.max() > range.second)) {
            val r = graphRange(anim.keys)
            range = minOf(r.first, range.first) to maxOf(r.second, range.second)
        }
    }
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.argb(150, 195, 198, 208) } }
    val comps = sortedState.value.maxOfOrNull { it.v.size } ?: 1
    // read through state: the gesture handler lives across recompositions and must see the current keyframes
    fun shown(): List<UiKf> {
        val sorted = sortedState.value
        val v = value; val h = infl
        if (v == null && h == null) return sorted
        return sorted.map { k ->
            var n = k
            if (v != null && k.mt == v.first) n = n.copy(v = n.v.toMutableList().also { l -> if (v.second < l.size) l[v.second] = v.third })
            if (h != null && k.mt == h.first) {
                n = if (h.second) n.copy(outInf = h.third) else n.copy(inInf = h.third)
                // a side without ease eases while its handle is dragged (as it will after release)
                val i = sorted.indexOf(k)
                se.gendut.klipp.engine.handleEase(sorted.getOrNull(i - 1)?.interp, k.interp, h.second)?.let { n = n.copy(interp = it) }
            }
            n
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(108.dp))
            Canvas(
                Modifier.weight(1f).height(150.dp).clip(RoundedCornerShape(12.dp)).background(K.Deep).testTag("kfgraphview:${anim.key}")
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val g = Geo(a, size.width.toFloat(), size.height.toFloat(), range)
                            val ks = shown()
                            val grab = 22.dp.toPx()
                            // a handle, a point (any component) or else the playhead
                            var handle: Triple<UiKf, Boolean, Float>? = null // key, out side, segment px
                            var best = grab
                            for ((i, k) in ks.withIndex()) {
                                val y = g.y(k.v.getOrNull(0) ?: continue)
                                val kx = g.x(k.t)
                                if (hasOut(ks, i)) {
                                    val seg = g.x(ks[i + 1].t) - kx
                                    val d = kotlin.math.hypot(kx + seg * k.outInf.toFloat() - down.position.x, y - down.position.y)
                                    if (d < best) { best = d; handle = Triple(k, true, seg) }
                                }
                                if (hasIn(ks, i)) {
                                    val seg = kx - g.x(ks[i - 1].t)
                                    val d = kotlin.math.hypot(kx - seg * k.inInf.toFloat() - down.position.x, y - down.position.y)
                                    if (d < best) { best = d; handle = Triple(k, false, seg) }
                                }
                            }
                            // the nearest of points and handles wins (a point beats a handle at the same distance)
                            var point: Pair<UiKf, Int>? = null
                            var bestPoint = grab
                            for (k in ks) for (c in k.v.indices) {
                                val d = kotlin.math.hypot(g.x(k.t) - down.position.x, g.y(k.v[c]) - down.position.y)
                                if (d < bestPoint) { bestPoint = d; point = k to c }
                            }
                            if (point != null && bestPoint <= best) handle = null else point = null
                            if (handle != null || point != null) down.consume()
                            var moved = false
                            var lastStep = Int.MIN_VALUE
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop / 2) {
                                    // beside the points a vertical drag scrolls the panel
                                    val d = ch.position - down.position
                                    if (handle == null && point == null && abs(d.y) > abs(d.x)) return@awaitEachGesture
                                    moved = true
                                }
                                if (!moved) { if (handle != null || point != null) ch.consume(); continue }
                                val h = handle; val pt = point
                                when {
                                    h != null -> {
                                        val dx = if (h.second) ch.position.x - g.x(h.first.t) else g.x(h.first.t) - ch.position.x
                                        val ni = (dx / h.third.coerceAtLeast(1f)).toDouble().coerceIn(0.01, 1.0)
                                        val step = (ni * 20).toInt()
                                        if (step != lastStep) { if (lastStep != Int.MIN_VALUE) haptics.tick(); lastStep = step }
                                        infl = Triple(h.first.mt, h.second, ni)
                                    }
                                    pt != null -> value = Triple(pt.first.mt, pt.second, g.v(ch.position.y))
                                    else -> a.onJump(g.t(ch.position.x))
                                }
                                ch.consume()
                            }
                            val h = handle; val pt = point
                            when {
                                h != null && moved -> infl?.let { (_, out, ni) ->
                                    haptics.confirm()
                                    val ease = se.gendut.klipp.engine.handleEase(ks.getOrNull(ks.indexOf(h.first) - 1)?.interp, h.first.interp, out)
                                    a.onInfluence?.invoke(h.first, if (out) null else ni, if (out) ni else null, ease)
                                }
                                pt != null && moved -> value?.let { (_, c, nv) ->
                                    haptics.confirm()
                                    a.onValue?.invoke(pt.first, pt.first.v.toMutableList().also { it[c] = nv })
                                }
                                pt != null -> { haptics.tick(); a.onJump(pt.first.t) }
                                h == null && !moved -> a.onJump(g.t(down.position.x))
                            }
                        }
                    }
            ) {
                val g = Geo(anim, size.width, size.height, range)
                val ks = shown()
                // grid: a line at each quarter of the range
                for (q in 1..3) drawLine(K.OutlineVar.copy(alpha = 0.35f), Offset(0f, size.height * q / 4f), Offset(size.width, size.height * q / 4f), 1f)
                // the scale: the top and bottom values
                drawContext.canvas.nativeCanvas.run {
                    paint.textSize = 10.sp.toPx()
                    drawText(anim.format(range.second.toFloat()), 6.dp.toPx(), 12.dp.toPx(), paint)
                    drawText(anim.format(range.first.toFloat()), 6.dp.toPx(), size.height - 5.dp.toPx(), paint)
                }
                val colors = listOf(K.Primary, K.Teal)
                val step = 3.dp.toPx()
                for (c in 0 until comps) {
                    val path = Path()
                    var x = 0f
                    var first = true
                    while (x <= size.width + step) {
                        val t = g.t(x.coerceAtMost(size.width))
                        val v = ks.graphValueAt(t, c)
                        if (v != null) { val y = g.y(v); if (first) path.moveTo(x, y) else path.lineTo(x, y); first = false }
                        x += step
                    }
                    drawPath(path, colors[c % colors.size], style = Stroke(2.dp.toPx()))
                }
                // handles (on the first component's curve): light = the side eases, small gray = linear (drag it to ease)
                fun handle(kx: Float, hx: Float, y: Float, eased: Boolean) {
                    drawLine(K.OnSurfaceVar.copy(alpha = if (eased) 0.6f else 0.3f), Offset(kx, y), Offset(hx, y), 1.5f * density)
                    if (eased) drawCircle(K.PrimarySoft, 5.dp.toPx(), Offset(hx, y))
                    else drawCircle(K.Outline, 3.5f.dp.toPx(), Offset(hx, y))
                }
                for ((i, k) in ks.withIndex()) {
                    val y = g.y(k.v.getOrNull(0) ?: continue)
                    val kx = g.x(k.t)
                    if (hasOut(ks, i)) handle(kx, kx + (g.x(ks[i + 1].t) - kx) * k.outInf.toFloat(), y, se.gendut.klipp.engine.easesOut(k.interp))
                    if (hasIn(ks, i)) handle(kx, kx - (kx - g.x(ks[i - 1].t)) * k.inInf.toFloat(), y, se.gendut.klipp.engine.easesIn(ks[i - 1].interp, k.interp))
                }
                for (k in ks) for (c in k.v.indices) {
                    val center = Offset(g.x(k.t), g.y(k.v[c]))
                    drawCircle(K.Bg, 6.dp.toPx(), center)
                    drawCircle(colors[c % colors.size], 6.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                }
                val ph = g.x(anim.playhead)
                drawRect(K.Primary.copy(alpha = 0.8f), Offset(ph - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
            }
            Box(Modifier.width(102.dp).padding(start = 8.dp)) {
                val v = value; val h = infl
                val txt = when {
                    v != null -> anim.format(v.third.toFloat())
                    h != null -> stringResource(R.string.keyframeui_influence, Math.round(h.third * 100).toInt())
                    else -> ""
                }
                Text(txt, color = K.OnSurface, fontSize = 12.sp, modifier = Modifier.testTag("kfgraphvalue"))
            }
        }
        Row {
            Box(Modifier.width(108.dp))
            Text(stringResource(R.string.keyframeui_graph_hint), color = K.OnSurfaceVar, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(top = 4.dp))
            Box(Modifier.width(102.dp))
        }
    }
}

/** Keyframe [i] has an outgoing handle: there is a next keyframe and the segment isn't a hold (a step has no curve). */
internal fun hasOut(ks: List<UiKf>, i: Int) = i < ks.size - 1 && ks[i].interp != "Hold"

/** Keyframe [i] has an incoming handle: there is a previous keyframe that doesn't hold. */
internal fun hasIn(ks: List<UiKf>, i: Int) = i > 0 && ks[i - 1].interp != "Hold"

/** Graph geometry: timeline time ↔ x through the lanes' zoom, value ↔ y over [range]. */
private class Geo(val anim: AnimUi, val w: Float, val h: Float, val range: Pair<Double, Double>) {
    private val span = (anim.clipEnd - anim.clipStart).coerceAtLeast(0.01f)
    private val v = anim.view
    private val pad = 10f
    fun x(t: Float) = ((t - anim.clipStart) / span - v.a) / (v.b - v.a) * w
    fun t(x: Float) = anim.clipStart + (v.a + (x / w).coerceIn(0f, 1f) * (v.b - v.a)) * span
    fun y(value: Double) = (pad + (1 - (value - range.first) / (range.second - range.first)) * (h - 2 * pad)).toFloat()
    fun v(y: Float) = range.first + (1 - ((y - pad) / (h - 2 * pad)).toDouble()) * (range.second - range.first)
}
