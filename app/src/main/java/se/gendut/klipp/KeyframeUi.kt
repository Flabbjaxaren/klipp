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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
) {
    val animated get() = keys.isNotEmpty()
    /** The clip fraction of timeline time [t]. */
    fun frac(t: Float): Float = ((t - clipStart) / (clipEnd - clipStart).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
    val playhead: Float get() = playheadOf()
    private val frame get() = FRAME
    /** The keyframe at the playhead (within half a frame). */
    val atPlayhead: UiKf? get() = keys.firstOrNull { abs(it.t - playhead.coerceIn(clipStart, clipEnd)) < frame / 2 }
    val prev: UiKf? get() = keys.lastOrNull { it.t < playhead - frame / 2 }
    val next: UiKf? get() = keys.firstOrNull { it.t > playhead + frame / 2 }
}

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
        // the zoom follows the playhead when it leaves the visible part (playback, jumps)
        androidx.compose.runtime.LaunchedEffect(anim.view) {
            androidx.compose.runtime.snapshotFlow { a.frac(a.playhead) }.collect { f -> if (a.view.zoomed) a.view.show(f) }
        }
        androidx.compose.foundation.layout.Column {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(108.dp))
            Box(
                Modifier.weight(1f).height(32.dp).testTag("lane:${anim.key}")
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val span = (a.clipEnd - a.clipStart).coerceAtLeast(0.01f)
                            val v = a.view
                            // through the zoom: the lane shows the clip fractions v.a..v.b
                            fun tOf(x: Float) = a.clipStart + (v.a + (x / size.width).coerceIn(0f, 1f) * (v.b - v.a)) * span
                            fun xOf(t: Float) = ((t - a.clipStart) / span - v.a) / (v.b - v.a) * size.width
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
                                    haptics.confirm(); a.onInterp(hit!!); cancelled = true
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
            }
            // only when the keyframe at/before/after the playhead changes – not every frame
            val prev by remember { derivedStateOf { a.prev } }
            val next by remember { derivedStateOf { a.next } }
            val on by remember { derivedStateOf { a.atPlayhead } }
            SmallIcon(Ic.KfPrev, stringResource(R.string.keyframeui_prev_keyframe), prev != null, "kfprev:${anim.key}") { prev?.let { haptics.tick(); anim.onJump(it.t); anim.view.show(anim.frac(it.t)) } }
            SmallIcon(Ic.Diamond, if (on != null) stringResource(R.string.keyframeui_remove_keyframe) else stringResource(R.string.keyframeui_add_keyframe), true, "kf:${anim.key}", filled = on != null) { haptics.confirm(); anim.onKey() }
            SmallIcon(Ic.KfNext, stringResource(R.string.keyframeui_next_keyframe), next != null, "kfnext:${anim.key}") { next?.let { haptics.tick(); anim.onJump(it.t); anim.view.show(anim.frac(it.t)) } }
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
