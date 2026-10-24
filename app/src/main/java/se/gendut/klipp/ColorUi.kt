package se.gendut.klipp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/*
 * Material 3 Expressive-style building blocks for the color page (M3 1.4 does not expose the expressive components
 * publicly, and the app has its own springs and colors): shape is part of the state – selected becomes round,
 * a press squeezes the corners, and everything moves on springs.
 */

private fun cornerSpring() = spring<Dp>(dampingRatio = 0.55f, stiffness = 700f)

/**
 * Connected button group (single choice): buttons sit together with 2 dp gaps, the group's outer corners are round
 * and the inner ones small; the selected one becomes fully round. Fills the width (equal-width buttons).
 */
@Composable
fun ConnectedToggleGroup(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    tag: (Int) -> String? = { null },
) {
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((i, label) in items.withIndex()) {
            ConnectedItem(label, i == selected, first = i == 0, last = i == items.lastIndex, height = height,
                modifier = Modifier.weight(1f).let { m -> tag(i)?.let { m.testTag(it) } ?: m }) { onSelect(i) }
        }
    }
}

@Composable
private fun ConnectedItem(label: String, selected: Boolean, first: Boolean, last: Boolean, height: Dp, modifier: Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val full = height / 2
    val inner = 6.dp
    fun target(outer: Boolean) = when {
        pressed -> 4.dp
        selected -> full
        outer -> full
        else -> inner
    }
    val ts by animateDpAsState(target(first), cornerSpring(), label = "ts")
    val bs by animateDpAsState(target(first), cornerSpring(), label = "bs")
    val te by animateDpAsState(target(last), cornerSpring(), label = "te")
    val be by animateDpAsState(target(last), cornerSpring(), label = "be")
    val bg by animateColorAsState(if (selected) K.Primary else K.SurfaceHigh, label = "cbg")
    val fg by animateColorAsState(if (selected) K.OnPrimary else K.OnSurface, label = "cfg")
    Box(
        modifier.height(height)
            .clip(RoundedCornerShape(topStart = ts, topEnd = te, bottomEnd = be, bottomStart = bs))
            .background(bg)
            .clickable(interactionSource = src, indication = null, role = Role.Tab) { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

/** A tab in the tool bar: icon, short name, and a dot when the tool is in use in the node. */
class ToolTab(val label: String, val icon: ImageVector, val tag: String, val marked: Boolean = false)

/**
 * The node's tool bar (like an Expressive navigation bar): a pill behind the selected icon that slides
 * on a spring to the next choice and stretches while it moves.
 */
@Composable
fun ToolTabs(tabs: List<ToolTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val pos = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) { pos.animateTo(selected.toFloat(), spring(dampingRatio = 0.62f, stiffness = 520f)) }
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(K.Surface).padding(vertical = 8.dp)) {
        val cell = maxWidth / tabs.size
        val ind = 52.dp
        // stretch: wider the faster it slides (like a droplet)
        val w = ind + (kotlin.math.abs(pos.velocity) * 5f).coerceAtMost(22f).dp
        Box(
            Modifier.offset(x = cell * pos.value + (cell - w) / 2).width(w).height(32.dp)
                .clip(CircleShape).background(K.Tonal)
        )
        Row(Modifier.fillMaxWidth()) {
            for ((i, t) in tabs.withIndex()) {
                val on = i == selected
                val fg by animateColorAsState(if (on) K.PrimarySoft else K.OnSurfaceVar, label = "tf")
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                            if (!on) haptics.tick(); onSelect(i)
                        }
                        .testTag(t.tag),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.height(32.dp).width(ind), contentAlignment = Alignment.Center) {
                        Icon(t.icon, t.label, tint = fg, modifier = Modifier.size(22.dp))
                        if (t.marked) Box(Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 10.dp).size(7.dp).clip(CircleShape).background(K.Primary))
                    }
                    Spacer(Modifier.height(4.dp))
                    // a long name (e.g. Qualifier) shrinks to its cell on a narrow phone instead of running into the next one
                    androidx.compose.foundation.text.BasicText(
                        t.label, color = { fg }, maxLines = 1, softWrap = false,
                        style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/** A card (surface with large round corners) with an optional title and an action on the right. */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(28.dp)).background(K.Surface).padding(vertical = 10.dp)) {
        if (title != null || trailing != null) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, bottom = 4.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title.orEmpty(), color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
        }
        content()
    }
}

/**
 * Action button: icon in a tonal square "squircle" with text below. A press makes it round and slightly smaller
 * (the shape responds to the finger).
 */
@Composable
fun ActionTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val r by animateDpAsState(if (pressed) 28.dp else 16.dp, cornerSpring(), label = "ar")
    val press = remember { Animatable(1f) }
    val tint = when { !enabled -> K.Outline; danger -> K.Error; else -> K.PrimarySoft }
    Column(
        modifier.clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button) {
            haptics.tick()
            scope.launch { press.snapTo(0.9f); press.animateTo(1f, Springs.pop()) }
            onClick()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }
                .size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(r))
                .background(if (danger) Color(0x33FFB4AB) else if (enabled) K.Tonal else K.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (enabled) K.OnSurface else K.Outline, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * On/off chip: off = filled neutral surface with soft corners, on = tonal, fully round and with a check mark (shape and
 * color show the state, no outline).
 */
@Composable
fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val r by animateDpAsState(if (pressed) 6.dp else if (on) 18.dp else 10.dp, cornerSpring(), label = "tc")
    val bg by animateColorAsState(if (on) K.Tonal else K.SurfaceHigh, label = "tcb")
    val fg by animateColorAsState(if (on) K.PrimarySoft else K.OnSurface, label = "tcf")
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(r)).background(bg)
            .clickable(interactionSource = src, indication = null, role = Role.Checkbox) { haptics.tick(); onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ic = if (on) Ic.Check else icon
        if (ic != null) {
            Icon(ic, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Primary button (filled, round) or tonal; a press makes the corners smaller. */
@Composable
fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, filled: Boolean = true) {
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val r by animateDpAsState(if (pressed) 10.dp else 22.dp, cornerSpring(), label = "pb")
    val bg = if (filled) K.Primary else K.Tonal
    val fg = if (filled) K.OnPrimary else K.PrimarySoft
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(r)).background(bg)
            .clickable(interactionSource = src, indication = null, role = Role.Button) { haptics.tick(); onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * Playhead for the clip being graded: a filmstrip over the whole clip to drag (or tap) – the image above
 * follows. Steps in whole frames. [track] = the source times covered by a track in the node (shown under the strip).
 */
@Composable
fun ClipScrubber(state: EditorState, clip: ColorClip, track: ClosedFloatingPointRange<Double>?, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val len = (clip.end - clip.start).coerceAtLeast(FRAME)
    // the playhead is read only at draw time: only the text and lines redraw during playback, not the filmstrip
    val frac = { ((state.playhead - clip.start) / len).coerceIn(0f, 1f) }
    val clipRef = androidx.compose.runtime.rememberUpdatedState(clip)
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            ScrubTc(state, clip.start, len)
            Text(" / " + formatTc(len), color = K.Outline, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            // one line at the right, shortened when narrow (it wrapped into three lines beside the timecode)
            Text(stringResource(R.string.colorui_drag_to_scrub), color = K.Outline, fontSize = 11.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(K.SurfaceHigh).testTag("scrub")
                .scrubGesture { x, w, first ->
                    val c = clipRef.value
                    val l = (c.end - c.start).coerceAtLeast(FRAME)
                    // whole frames, within the clip (the last frame starts one frame before the end)
                    val f = ((x / w).coerceIn(0f, 1f) * l * projectFps.toFloat()).roundToIntCompat().coerceIn(0, ((l * projectFps).toInt() - 1).coerceAtLeast(0))
                    val t = c.start + f / projectFps.toFloat()
                    if (first) { state.playing = false; haptics.tick() }
                    if (kotlin.math.abs(t - state.playhead) > 1e-4f) {
                        if (f == 0 || f == (l * projectFps).toInt() - 1) haptics.tick()
                        state.playhead = t
                    }
                },
        ) {
            // filmstrip
            val n = (maxWidth / 56.dp).toInt().coerceIn(3, 12)
            Row(Modifier.matchParentSize()) {
                for (i in 0 until n) {
                    val at = clip.srcIn + (i + 0.5) / n * len * kotlin.math.abs(clip.speed)
                    val img by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, clip.uri, at) {
                        value = clip.uri?.let { Thumbs.load(ctx, it, at) }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().background(K.SurfaceHighest)) {
                        img?.let { androidx.compose.foundation.Image(it, null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                    }
                }
            }
            // what has already played/passed is dimmed a bit, so the playhead position is visible at a glance
            Box(Modifier.matchParentSize().drawBehind { drawRect(Color(0x33000000), size = androidx.compose.ui.geometry.Size(size.width * frac(), size.height)) })
            // tracking range
            if (track != null) {
                val sp = kotlin.math.abs(clip.speed).coerceAtLeast(1e-6)
                val a = (((track.start - clip.srcIn) / sp) / len).toFloat().coerceIn(0f, 1f)
                val b = (((track.endInclusive - clip.srcIn) / sp) / len).toFloat().coerceIn(0f, 1f)
                if (b > a) Box(Modifier.align(Alignment.BottomStart).offset(x = maxWidth * a).width(maxWidth * (b - a)).height(4.dp).background(K.Teal).testTag("scrub:track"))
            }
            // playhead
            Box(Modifier.matchParentSize().drawBehind {
                val x = size.width * frac()
                val line = 3.dp.toPx()
                drawRect(K.Primary, androidx.compose.ui.geometry.Offset((x - line / 2).coerceIn(0f, size.width - line), 0f), androidx.compose.ui.geometry.Size(line, size.height))
                val kw = 14.dp.toPx(); val kh = 8.dp.toPx(); val r = 7.dp.toPx()
                val kx = (x - kw / 2).coerceIn(0f, size.width - kw)
                val knob = androidx.compose.ui.geometry.RoundRect(kx, 0f, kx + kw, kh,
                    bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(r), bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                drawPath(androidx.compose.ui.graphics.Path().apply { addRoundRect(knob) }, K.Primary)
            })
        }
    }
}

/** Time within the clip – its own function so only this text redraws when the playhead moves. */
@Composable
private fun ScrubTc(state: EditorState, start: Float, len: Float) {
    Text(formatTc((state.playhead - start).coerceIn(0f, len)), color = K.Primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.testTag("scrub:tc"))
}

private fun Float.roundToIntCompat(): Int = kotlin.math.round(this).toInt()

/** Press and drag horizontally: [onPos] (x, width, first) – vertical is left to the page's scrolling. */
private fun Modifier.scrubGesture(onPos: (Float, Float, Boolean) -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            onPos(down.position.x, size.width.toFloat(), true)
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                if (ch.positionChanged()) {
                    ch.consume()
                    onPos(ch.position.x, size.width.toFloat(), false)
                }
            }
        }
    }
