package se.gendut.klipp

import se.gendut.klipp.engine.at
import se.gendut.klipp.engine.fromFrame
import se.gendut.klipp.engine.toFrame
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import se.gendut.klipp.engine.ClipGrade
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Slider for a real value: drag anywhere on the track (no snapping – a tick when passing the default position),
 * double tap = reset. The engine only gets the value when the finger lifts (one undo step per drag).
 */
/** The room at the sides of a [PropSlider] (the color page's narrow column has its own). */
val LocalSliderInset = androidx.compose.runtime.compositionLocalOf { 16.dp }

@Composable
fun PropSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    default: Float,
    format: (Float) -> String,
    tag: String = label,
    onLive: (Float) -> Unit = {},
    anim: AnimUi? = null,
    /** Show the keyframe lane under the slider (position X and Y share a lane – shown under Y). */
    lane: Boolean = true,
    onCommit: (Float) -> Unit,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val v = remember { Animatable(value) }
    var dragging by remember { mutableStateOf(false) }
    val lastTapRef = remember { longArrayOf(0L) }
    // the value from the engine (undo, reset …) springs in when not dragging
    LaunchedEffect(value) { if (!dragging) v.animateTo(value, Springs.settle()) }
    val commit by rememberUpdatedState(onCommit)
    // the gesture isn't restarted when the value changes – read the current value from here
    val current by rememberUpdatedState(value)
    val live by rememberUpdatedState(onLive)
    val grow by animateFloatAsState(if (dragging) 1f else 0f, spring(0.4f, 600f), label = "grow")
    Column {
    Column(Modifier.fillMaxWidth().padding(horizontal = LocalSliderInset.current)) {
        // the name on the left and the value on the right, above the track (the track gets the whole width)
        val nav = anim != null && lane && anim.animated
        Row(Modifier.fillMaxWidth().height(if (nav) 34.dp else if (anim != null) 28.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (anim != null) Stopwatch(anim)
            Text(
                label, color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = if (anim != null) 6.dp else 0.dp, end = 8.dp),
            )
            // the keyframe jumps beside the name: the lane under the slider gets the whole width
            if (nav) { KeyframeNav(anim); Spacer(Modifier.width(8.dp)) }
            Text(format(v.value), color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, style = TextStyle(fontFeatureSettings = "tnum"))
        }
        Box(
            Modifier.fillMaxWidth().height(44.dp).testTag("prop:$tag")
                .pointerInput(min, max, default) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // the value when the gesture started: live values can be fed back into [value] during the drag
                        // (color page), so the comparison on release is against the start value – otherwise nothing is saved
                        val start = current
                        var side = kotlin.math.sign(v.value - default)
                        var moved = false
                        var cancelled = false
                        fun valueAt(x: Float): Float {
                            val nv = min + (x / size.width).coerceIn(0f, 1f) * (max - min)
                            val s = kotlin.math.sign(nv - default)
                            if (s != side) { if (side != 0f || s == 0f) haptics.tick(); side = s }
                            return nv
                        }
                        var last = v.value
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            if (!moved) {
                                if (c.isConsumed) { cancelled = true; break } // the panel is scrolling
                                val dx = abs(c.position.x - down.position.x)
                                val dy = abs(c.position.y - down.position.y)
                                if (dx > viewConfiguration.touchSlop / 2 && dx > dy) { moved = true; dragging = true }
                                else if (dy > viewConfiguration.touchSlop) { cancelled = true; break }
                            }
                            if (moved) {
                                last = valueAt(c.position.x)
                                live(last)
                                scope.launch { v.animateTo(last, Springs.follow()) }
                                c.consume()
                            }
                        }
                        dragging = false
                        if (cancelled) return@awaitEachGesture
                        if (!moved) {
                            // double tap = back to the default value
                            val now = System.currentTimeMillis()
                            if (now - lastTapRef[0] < 350) {
                                last = default
                                live(last)
                                haptics.confirm()
                                scope.launch { v.animateTo(last, Springs.pop()) }
                                lastTapRef[0] = 0L
                            } else {
                                lastTapRef[0] = now
                                return@awaitEachGesture
                            }
                        }
                        if (abs(last - start) > 1e-4f || abs(last - current) > 1e-4f) commit(last)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                val h = 16.dp.toPx()
                val top = (size.height - h) / 2
                fun xOf(f: Float) = ((f - min) / (max - min)).coerceIn(0f, 1f) * size.width
                val x = xOf(v.value)
                val x0 = xOf(default.coerceIn(min, max))
                val hw = (4f + 2f * grow).dp.toPx()
                val hh = (36f + 6f * grow).dp.toPx()
                // the track in two parts with a gap around the handle: round outer ends, tighter corners towards the handle
                val gap = 6.dp.toPx() + hw / 2
                val outer = CornerRadius(8.dp.toPx())
                val inner = CornerRadius(3.dp.toPx())
                val track = Path().apply {
                    if (x - gap > 1f) addRoundRect(RoundRect(Rect(0f, top, x - gap, top + h), topLeft = outer, bottomLeft = outer, topRight = inner, bottomRight = inner))
                    if (x + gap < size.width - 1f) addRoundRect(RoundRect(Rect(x + gap, top, size.width, top + h), topLeft = inner, bottomLeft = inner, topRight = outer, bottomRight = outer))
                }
                drawPath(track, K.SurfaceHighest)
                // filled from the default position to the value
                val a = kotlin.math.min(x, x0)
                val b = kotlin.math.max(x, x0)
                clipPath(track) { drawRect(K.Primary, Offset(a, top), Size((b - a).coerceAtLeast(0f), h)) }
                // the default position (hidden under the handle)
                if (abs(x - x0) > gap) drawCircle(if (b - a > 0f) K.OnPrimary.copy(alpha = 0.7f) else K.OnSurfaceVar, 2.dp.toPx(), Offset(x0.coerceIn(4.dp.toPx(), size.width - 4.dp.toPx()), size.height / 2))
                drawRoundRect(K.Primary, Offset(x - hw / 2, (size.height - hh) / 2), Size(hw, hh), CornerRadius(hw / 2))
            }
        }
    }
    if (anim != null && lane) KeyframeLane(anim)
    }
}


private fun fmtNum(v: Float, unit: String) = (if (v < -0.05f) "−" else "") + "%.0f".format(abs(v)) + unit

/** Live values while a slider or gesture is dragged (shown directly in the image, the engine gets them on release). */
fun EditorState.setLive(p: ClipProps) { live = p; liveToken++ }

/** Keep the live image a moment after release, until playback has had time to reload. */
suspend fun EditorState.endLive() {
    val tok = liveToken
    delay(900)
    if (liveToken == tok) live = null
}

/** Effect Controls for the selected clip: motion, opacity, fades, title or audio volume. */
@Composable
fun PropertiesPanel(state: EditorState, onClose: () -> Unit) {
    val backend = state.props ?: return
    val id = state.selected
    // the values at the playhead: the panel only redraws when they actually change (not 60 times a second during
    // playback – props reads the playhead)
    val pState = remember(id, backend) { androidx.compose.runtime.derivedStateOf { id?.let { backend.props(it) } } }
    val p = pState.value
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(K.Bg).testTag("properties")) {
        // a tool group of the clip opened from its toolbar: the group's name above the clip's
        val groupTitle = if (state.inspectGroup == GROUP_PICTURE && p?.isTitle == false && !p.isAudio) stringResource(R.string.design_picture) else null
        SheetHeader(
            groupTitle ?: p?.name ?: stringResource(R.string.properties_title),
            if (p != null && groupTitle != null) stringResource(R.string.design_selection, p.name, p.clipEnd - p.clipStart) else null,
        ) { haptics.tick(); onClose() }
        if (p == null) {
            Text(stringResource(R.string.properties_select_clip), color = K.OnSurfaceVar, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            return@Column
        }
        // the current value during a drag (otherwise the engine's)
        fun cur(): ClipProps = state.live?.takeIf { it.id == p.id } ?: p
        fun live(f: (ClipProps) -> ClipProps) = state.setLive(f(cur()))
        fun done() { scope.launch { state.endLive() } }
        // keyframes: stopwatch, lane and ◀ ◆ ▶ per property; one zoom for all the clip's lanes, its bar under the lowest
        val kfView = remember(p.id) { KfView() }
        val lowest = (if (p.isAudio) listOf("level") else listOf("position", "scale", "rotation")).lastOrNull { p.kf[it].orEmpty().isNotEmpty() }
        fun anim(key: String) = AnimUi(
            key, p.kf[key].orEmpty(), p.clipStart, p.clipEnd, { state.playhead },
            onToggle = { backend.toggleAnimation(p.id, key, state.playhead) },
            onKey = { backend.toggleKeyframe(p.id, key, state.playhead) },
            onJump = { t -> state.playing = false; state.playhead = t.coerceIn(p.clipStart, lastFrame(p.clipStart, p.clipEnd)) },
            onMove = { k, t -> backend.moveKeyframe(p.id, key, k.mt, t) },
            onInterp = { k -> backend.setInterpolation(p.id, key, k.mt, nextInterp(k.interp)) },
            view = kfView, zoomBar = key == lowest || key == "opacity",
            onEase = { k, ease -> backend.setEase(p.id, key, k.mt, ease) },
            onInfluence = { k, i, o, interp -> backend.setInfluence(p.id, key, k.mt, i, o, interp) },
            onValue = { k, v -> backend.setKeyframeValue(p.id, key, k.mt, v) },
            format = when (key) {
                "level" -> { v -> "%+.1f dB".format(v).replace('-', '−') }
                "scale", "opacity" -> { v -> fmtNum(v, " %") }
                "rotation" -> { v -> fmtNum(v, "°") }
                else -> { v -> "%.0f".format(v) }
            },
        )
        // one bar for everything about the clip; a dot marks the tools that are in use
        val gsNow = LocalGradeState.current
        val layerFx = gsNow?.shown(p.id)?.layer
        val moved = (p.x ?: (p.seqW / 2f)) != p.seqW / 2f || (p.y ?: (p.seqH / 2f)) != p.seqH / 2f || p.scale != 100f || p.rotation != 0f
        val fades = p.fadeIn != null || p.fadeOut != null
        val motionTab = ToolTab(stringResource(R.string.properties_motion), Ic.Center, "propTab:motion", moved)
        val fadeTab = ToolTab(stringResource(R.string.properties_fade), Ic.Fade, "propTab:fade", fades)
        val tabs = when {
            p.isAudio -> listOf(
                ToolTab(stringResource(R.string.properties_volume), Ic.Speaker, "propTab:volume", p.levelDb != 0f),
                ToolTab(stringResource(R.string.audioui_effects), Ic.Audio, "propTab:afx", p.afx.isNotEmpty()),
            )
            p.isTitle -> listOf(
                ToolTab(stringResource(R.string.titleui_tab_text), Ic.Text, "titleTab:0"),
                ToolTab(stringResource(R.string.titleui_tab_style), Ic.Sliders, "titleTab:1"),
                ToolTab(stringResource(R.string.titleui_tab_animate), Ic.Bolt, "titleTab:2"),
                ToolTab(stringResource(R.string.titleui_tab_track), Ic.Target, "titleTab:3", gsNow?.let { p.id in it.follows || p.id in it.pins } == true),
                motionTab, fadeTab,
            )
            else -> {
                val crop = ToolTab(stringResource(R.string.properties_crop), Ic.Crop, "propTab:crop", layerFx?.crop != null)
                val blend = ToolTab(stringResource(R.string.properties_blend_tab), Ic.Layers, "propTab:blend", p.opacity != 100f || (layerFx?.blend ?: se.gendut.klipp.engine.BlendMode.NORMAL) != se.gendut.klipp.engine.BlendMode.NORMAL)
                val key = ToolTab(stringResource(R.string.properties_tab_key), Ic.Dropper, "propTab:key", layerFx?.key != null)
                val mask = ToolTab(stringResource(R.string.properties_mask), Ic.Window, "propTab:mask", layerFx?.mask != null)
                val g = gsNow?.of(p.id)
                // stabilization and motion blur need moving video from a file (not an adjustment layer or a freeze frame)
                val camera = state.clips.firstOrNull { it.id == p.id }?.let { it.kind == ClipKind.VIDEO && !it.hold } == true
                when (state.inspectGroup) {
                    // the picture: where it sits, what of it shows, how it lies over what is below, and the camera's
                    // movement (stabilized or smeared)
                    GROUP_PICTURE -> listOf(
                        ToolTab(stringResource(R.string.design_position), Ic.Center, "propTab:motion", moved), crop, blend, key, mask,
                    ) + if (!camera) emptyList() else listOf(
                        ToolTab(stringResource(R.string.design_stabilize), Ic.Target, "propTab:stab", g?.stab != null),
                        ToolTab(stringResource(R.string.design_motion_blur), Ic.Film, "propTab:mblur", g?.mblur != null),
                    )
                    else -> listOf(motionTab, crop, blend, key, mask, fadeTab)
                }
            }
        }
        // (the clip's toolbar can ask for one of the tools: the sheet opens on it)
        var tab by androidx.compose.runtime.saveable.rememberSaveable(p.isTitle, p.isAudio, state.inspectGroup) {
            mutableStateOf(tabs.indexOfFirst { it.tag == state.openTab }.coerceAtLeast(0))
        }
        LaunchedEffect(state.openTab) { tabs.indexOfFirst { it.tag == state.openTab }.takeIf { it >= 0 }?.let { tab = it; state.openTab = null } }
        val current = tab.coerceIn(0, tabs.lastIndex)
        @Composable fun opacitySlider() = PropSlider(stringResource(R.string.properties_opacity), p.opacity, 0f, 100f, 100f, { fmtNum(it, " %") }, "opacity", anim = anim("opacity"),
            onLive = { v -> live { it.copy(opacity = v) } }) { backend.setProps(p.id, opacity = it); done() }
        @Composable fun motion() = PanelCard(Modifier.padding(top = 10.dp), title = stringResource(R.string.properties_motion), trailing = { SpringChip(stringResource(R.string.properties_reset), false, { haptics.confirm(); backend.resetMotion(p.id) }) }) {
            val w = p.seqW.toFloat()
            val h = p.seqH.toFloat()
            PropSlider(stringResource(R.string.properties_position_x), p.x ?: w / 2, -w / 2, w * 1.5f, w / 2, { "%.0f".format(it) }, "x", anim = anim("position"), lane = false,
                onLive = { v -> live { it.copy(x = v) } }) { backend.setProps(p.id, x = it); done() }
            PropSlider(stringResource(R.string.properties_position_y), p.y ?: h / 2, -h / 2, h * 1.5f, h / 2, { "%.0f".format(it) }, "y", anim = anim("position"),
                onLive = { v -> live { it.copy(y = v) } }) { backend.setProps(p.id, y = it); done() }
            PropSlider(stringResource(R.string.properties_scale), p.scale, 0f, 400f, 100f, { fmtNum(it, " %") }, "scale", anim = anim("scale"),
                onLive = { v -> live { it.copy(scale = v) } }) { backend.setProps(p.id, scale = it); done() }
            PropSlider(stringResource(R.string.properties_rotation), p.rotation, -180f, 180f, 0f, { fmtNum(it, "°") }, "rotation", anim = anim("rotation"),
                onLive = { v -> live { it.copy(rotation = v) } }) { backend.setProps(p.id, rotation = it); done() }
            if (p.isTitle) opacitySlider()
            Text(stringResource(R.string.properties_motion_hint), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        @Composable fun fade() = PanelCard(Modifier.padding(top = 10.dp), title = stringResource(R.string.properties_fade)) {
            FadeRow(stringResource(R.string.properties_fade_in), "In", p.fadeIn) { haptics.confirm(); backend.setTransition(p.id, true, it) }
            FadeRow(stringResource(R.string.properties_fade_out), "Ut", p.fadeOut) { haptics.confirm(); backend.setTransition(p.id, false, it) }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
        ToolRail(tabs, current, { tab = it })
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
        when {
            p.isAudio -> if (current == 0) PanelCard(Modifier.padding(top = 10.dp), title = stringResource(R.string.properties_volume)) {
                PropSlider(stringResource(R.string.properties_volume), p.levelDb, -40f, 15f, 0f, { "%+.1f dB".format(it).replace('-', '−') }, "volume", anim = anim("level"),
                    onLive = { v -> live { it.copy(levelDb = v) } }) { backend.setProps(p.id, levelDb = it); done() }
            } else PanelCard(Modifier.padding(top = 10.dp)) { AudioFxSection(state, p, backend) }
            p.isTitle -> {
                TitleSection(state, p, backend, current)
                if (current == 4) motion()
                if (current == 5) fade()
            }
            else -> when (val t = tabs[current].tag) {
                "propTab:motion" -> motion()
                "propTab:crop" -> gsNow?.let { CropSection(it, p, state, backend) }
                "propTab:fade" -> fade()
                // stabilization and motion blur used to be on the color page; one card here holds both
                "propTab:stab", "propTab:mblur" -> gsNow?.let { g -> g.clipInfo?.invoke(p.id)?.let { StabilizeCard(g, it, blurOnly = t == "propTab:mblur") } }
                else -> PanelCard(Modifier.padding(top = 10.dp)) {
                    if (t == "propTab:blend") opacitySlider()
                    gsNow?.let { LayerSection(it, p.id, state, when (t) { "propTab:blend" -> 1; "propTab:key" -> 2; else -> 3 }) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        }
        }
    }
}

/** The top of a sheet over the timeline: what it is about (and of which clip), and Done. */
@Composable
internal fun SheetHeader(title: String, subtitle: String? = null, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = K.OnSurface, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = K.OnSurfaceVar, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val src = remember { MutableInteractionSource() }
        Box(
            Modifier.pressPop(src, 0.9f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(K.SurfaceHighest)
                .clickable(interactionSource = src, indication = null, onClick = onDone).padding(horizontal = 20.dp).testTag("sheet:done"),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.properties_done), color = K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}

/**
 * The tools of a sheet as a rail on the left: an icon in a pill with its name under; a dot marks the tools in use.
 * The rail ends above undo and redo, which lie over the page's corner. When the tools don't all fit they scroll, sized
 * so that half a tool shows at the edge (and fades): there is more. The selected one is kept in view.
 * [dense] = the color page's rail: the tools close together, the selected one's name in the text color.
 */
@Composable
internal fun ToolRail(tabs: List<ToolTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, dense: Boolean = false) {
    val haptics = rememberHaptics()
    val bar = LocalBarSpace.current
    BoxWithConstraints(modifier.width(76.dp).fillMaxHeight().padding(top = if (dense) 0.dp else 6.dp, bottom = if (bar > 0.dp) bar + 4.dp else 0.dp)) {
        val full = if (dense) 50.dp else 56.dp
        val least = 44.dp
        val each = when {
            full * tabs.size <= maxHeight -> full
            least * tabs.size <= maxHeight -> maxHeight / tabs.size
            // they scroll: whole tools and half a one fill the height
            else -> (maxHeight / ((maxHeight / least - 0.5f).toInt().coerceAtLeast(1) + 0.5f)).coerceAtLeast(least)
        }
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().fadingEdges(scroll).verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
            for ((i, t) in tabs.withIndex()) {
                val on = i == selected
                val bg by animateColorAsState(if (on) K.PrimaryContainer else K.PrimaryContainer.copy(alpha = 0f), label = "rb")
                val fg by animateColorAsState(if (on) K.PrimarySoft else K.OnSurfaceVar, label = "rf")
                val name by animateColorAsState(if (on && dense) K.OnSurface else if (on) K.PrimarySoft else K.OnSurfaceVar, label = "rn")
                Column(
                    Modifier.width(76.dp).height(each).inViewWhen(on, each / 2, horizontal = false)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { if (!on) haptics.tick(); onSelect(i) }
                        .semantics { this.selected = on }
                        .testTag(t.tag),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(52.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
                        Icon(t.icon, t.label, tint = fg, modifier = Modifier.size(20.dp))
                        if (t.marked) Box(Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 9.dp).size(7.dp).clip(CircleShape).background(K.Primary))
                    }
                    Spacer(Modifier.height(if (each < 52.dp) 2.dp else 3.dp))
                    // a long name shrinks to the rail's width instead of being cut
                    BasicText(
                        t.label, color = { name }, maxLines = 1,
                        style = TextStyle(fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
                        modifier = Modifier.padding(horizontal = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * The clip as a layer: green screen, mask and blend mode (saved with the clip's grade – undoable, carried over on
 * split). Sliders preview live; releasing one makes an undo step.
 */
@Composable
private fun LayerSection(gs: GradeState, clip: Long, state: EditorState, part: Int) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val shown = gs.shown(clip) ?: ClipGrade()
    val fx = shown.layer ?: se.gendut.klipp.engine.LayerFx()
    fun set(f: se.gendut.klipp.engine.LayerFx) = gs.set(clip, gs.of(clip).copy(layer = f.takeUnless { it.isNone }))
    fun live(f: se.gendut.klipp.engine.LayerFx) = gs.setLive(clip, gs.of(clip).copy(layer = f))
    fun done() { scope.launch { gs.endLive() } }

    if (part == 2) {
    SectionLabel(stringResource(R.string.properties_green_screen))
    val key = fx.key
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SpringChip(stringResource(R.string.properties_off), key == null, { haptics.tick(); set(fx.copy(key = null)) }, Modifier.testTag("key:off"))
        SpringChip(stringResource(R.string.properties_key_green), key?.color == se.gendut.klipp.engine.ChromaKey.GREEN, {
            haptics.confirm(); set(fx.copy(key = (key ?: se.gendut.klipp.engine.ChromaKey()).copy(color = se.gendut.klipp.engine.ChromaKey.GREEN)))
        }, Modifier.testTag("key:green"))
        SpringChip(stringResource(R.string.properties_key_blue), key?.color == se.gendut.klipp.engine.ChromaKey.BLUE, {
            haptics.confirm(); set(fx.copy(key = (key ?: se.gendut.klipp.engine.ChromaKey()).copy(color = se.gendut.klipp.engine.ChromaKey.BLUE)))
        }, Modifier.testTag("key:blue"))
    }
    if (key != null) {
        fun pct(v: Float) = "%.0f %%".format(v * 100)
        PropSlider(stringResource(R.string.properties_key_tolerance), key.tolerance, 0f, 1f, 0.35f, ::pct, "keyTol",
            onLive = { v -> live(fx.copy(key = key.copy(tolerance = v))) }) { set(fx.copy(key = key.copy(tolerance = it))); done() }
        PropSlider(stringResource(R.string.properties_key_softness), key.softness, 0f, 0.6f, 0.15f, ::pct, "keySoft",
            onLive = { v -> live(fx.copy(key = key.copy(softness = v))) }) { set(fx.copy(key = key.copy(softness = it))); done() }
        PropSlider(stringResource(R.string.properties_key_spill), key.spill, 0f, 1f, 0.6f, ::pct, "keySpill",
            onLive = { v -> live(fx.copy(key = key.copy(spill = v))) }) { set(fx.copy(key = key.copy(spill = it))); done() }
        Text(
            stringResource(R.string.properties_key_hint),
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }

    }
    if (part == 1) {
    SectionLabel(stringResource(R.string.properties_blend))
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in se.gendut.klipp.engine.BlendMode.entries) {
            SpringChip(m.label, fx.blend == m, { haptics.tick(); set(fx.copy(blend = m)) }, Modifier.testTag("blend:${m.name}"))
        }
    }
    if (fx.blend != se.gendut.klipp.engine.BlendMode.NORMAL) Text(
        stringResource(R.string.properties_blend_hint),
        color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )

    }
    if (part == 3) {
    SectionLabel(stringResource(R.string.properties_mask))
    val mask = fx.mask
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SpringChip(stringResource(R.string.properties_off), mask == null, { haptics.tick(); set(fx.copy(mask = null)) }, Modifier.testTag("mask:off"))
        SpringChip(stringResource(R.string.properties_mask_rect), mask?.shape == se.gendut.klipp.engine.LayerMask.RECT, {
            haptics.confirm(); set(fx.copy(mask = (mask ?: se.gendut.klipp.engine.LayerMask()).copy(shape = se.gendut.klipp.engine.LayerMask.RECT)))
        }, Modifier.testTag("mask:rect"))
        SpringChip(stringResource(R.string.properties_mask_ellipse), mask?.shape == se.gendut.klipp.engine.LayerMask.ELLIPSE, {
            haptics.confirm(); set(fx.copy(mask = (mask ?: se.gendut.klipp.engine.LayerMask()).copy(shape = se.gendut.klipp.engine.LayerMask.ELLIPSE)))
        }, Modifier.testTag("mask:ellipse"))
        if (mask != null) SpringChip(stringResource(R.string.properties_mask_invert), mask.invert, { haptics.tick(); set(fx.copy(mask = mask.copy(invert = !mask.invert))) }, Modifier.testTag("mask:invert"))
    }
    if (mask != null) MaskControls(gs, clip, state, fx, mask, ::set, ::live, ::done)
    }
}

/** The keyframe lane for a mask or crop rectangle [box] (keys in source time, shown in timeline time). */
private fun boxAnim(
    info: ColorClip, box: se.gendut.klipp.engine.LayerMask, tol: Double, state: EditorState, view: KfView,
    srcOf: (Float) -> Double?, keySrc: () -> Double?, put: (se.gendut.klipp.engine.LayerMask) -> Unit,
): AnimUi {
    val lo = info.srcIn
    val hi = info.srcIn + (info.end - info.start) * abs(info.speed)
    return AnimUi(
        "mask",
        box.keys.filter { it.t >= lo - tol && it.t <= hi + tol }.map { k ->
            UiKf(info.timelineAt(k.t), Math.round(k.t * 1e6), when (k.interp) {
                se.gendut.klipp.engine.MaskKey.HOLD -> "Hold"; se.gendut.klipp.engine.MaskKey.SMOOTH -> "AutoBezier"; else -> "Linear"
            })
        },
        info.start, info.end, { state.playhead },
        onToggle = { srcOf(state.playhead)?.let { put(box.toggleAnimation(it)) } },
        onKey = { keySrc()?.let { put(box.toggleKey(it, tol)) } },
        onJump = { t -> state.playing = false; state.playhead = t.coerceIn(info.start, lastFrame(info.start, info.end)) },
        onMove = { k, t ->
            val mk = box.keys.firstOrNull { Math.round(it.t * 1e6) == k.mt }
            val to = srcOf(t)
            if (mk != null && to != null) put(box.moveKey(mk, to, tol))
        },
        onInterp = { k ->
            box.keys.firstOrNull { Math.round(it.t * 1e6) == k.mt }?.let { mk ->
                val next = when (mk.interp) {
                    se.gendut.klipp.engine.MaskKey.LINEAR -> se.gendut.klipp.engine.MaskKey.SMOOTH
                    se.gendut.klipp.engine.MaskKey.SMOOTH -> se.gendut.klipp.engine.MaskKey.HOLD
                    else -> se.gendut.klipp.engine.MaskKey.LINEAR
                }
                put(box.setInterp(mk, next))
            }
        },
        view = view,
    )
}

private val FULL_FRAME = se.gendut.klipp.engine.LayerMask(w = 1f, h = 1f, feather = 0f)

/** Keeps the crop only while it does something: the whole frame, unfeathered and without keyframes, is no crop. */
private fun cropOrNull(c: se.gendut.klipp.engine.LayerMask) = c.takeUnless { !it.animated && it.feather <= 0f && it.w >= 0.999f && it.h >= 0.999f }

/** The crop as edges, each a fraction of the picture in from its side. */
private fun se.gendut.klipp.engine.LayerMask.withEdges(l: Float, t: Float, r: Float, b: Float): se.gendut.klipp.engine.LayerMask {
    val ll = l.coerceIn(0f, 0.98f); val tt = t.coerceIn(0f, 0.98f)
    val rr = r.coerceIn(0f, 0.98f - ll); val bb = b.coerceIn(0f, 0.98f - tt)
    return copy(cx = (ll + 1f - rr) / 2f, cy = (tt + 1f - bb) / 2f, w = 1f - rr - ll, h = 1f - bb - tt)
}

/** Crop: formats, the four edges, feather, keyframes, and scaling the crop up to fill the frame. Handles on the picture while open. */
@Composable
private fun CropSection(gs: GradeState, p: ClipProps, state: EditorState, backend: PropsBackend) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val clip = p.id
    val fx = (gs.shown(clip) ?: ClipGrade()).layer ?: se.gendut.klipp.engine.LayerFx()
    val crop = fx.crop ?: FULL_FRAME
    androidx.compose.runtime.DisposableEffect(clip) { state.cropEdit = clip; onDispose { state.cropEdit = null } }
    fun set(c: se.gendut.klipp.engine.LayerMask?) = gs.set(clip, gs.of(clip).copy(layer = fx.copy(crop = c?.let(::cropOrNull)).takeUnless { it.isNone }))
    fun live(c: se.gendut.klipp.engine.LayerMask) = gs.setLive(clip, gs.of(clip).copy(layer = fx.copy(crop = c)))
    fun done() { scope.launch { gs.endLive() } }
    val info = remember(clip, gs.clipInfo, state.clips.firstOrNull { it.id == clip }) { gs.clipInfo?.invoke(clip) }
    fun srcOf(t: Float): Double? = info?.srcAt(t)
    val tol = (FRAME / 2.0) * abs(info?.speed ?: 1.0).coerceAtLeast(1e-3)
    val cur by remember(crop, info) { androidx.compose.runtime.derivedStateOf { if (crop.animated) crop.at(srcOf(state.playhead)) else crop } }
    fun keySrc(): Double? = srcOf(state.playhead)
    fun edit(l: Float? = null, t: Float? = null, r: Float? = null, b: Float? = null): se.gendut.klipp.engine.LayerMask {
        val c = cur
        return crop.withValues(c.withEdges(l ?: (c.cx - c.w / 2f), t ?: (c.cy - c.h / 2f), r ?: (1f - c.cx - c.w / 2f), b ?: (1f - c.cy - c.h / 2f)), keySrc(), tol)
    }
    val view = remember(clip) { KfView() }
    val anim = info?.let { boxAnim(it, crop, tol, state, view, ::srcOf, ::keySrc) { m -> set(m) } }
    fun pct(v: Float) = "%.0f %%".format(v * 100)
    val frame = p.seqW.toFloat() / p.seqH.toFloat().coerceAtLeast(1f)
    PanelCard(Modifier.padding(top = 10.dp), title = stringResource(R.string.properties_crop_format), trailing = {
        SpringChip(stringResource(R.string.properties_reset), false, { haptics.confirm(); set(null) }, Modifier.testTag("crop:reset"))
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, a) in listOf("16:9" to 16f / 9f, "9:16" to 9f / 16f, "1:1" to 1f, "4:5" to 4f / 5f)) {
                val ratio = a / frame
                val w = if (ratio >= 1f) 1f else ratio
                val h = if (ratio >= 1f) 1f / ratio else 1f
                SpringChip(label, abs(cur.w - w) < 0.002f && abs(cur.h - h) < 0.002f && fx.crop != null, {
                    haptics.confirm(); set(crop.withValues(cur.copy(cx = 0.5f, cy = 0.5f, w = w, h = h), keySrc(), tol))
                }, Modifier.weight(1f).testTag("crop:$label"), tight = true)
            }
        }
    }
    PanelCard(Modifier.padding(top = 10.dp), title = stringResource(R.string.properties_crop_edges)) {
        PropSlider(stringResource(R.string.properties_crop_left), cur.cx - cur.w / 2f, 0f, 0.98f, 0f, ::pct, "cropL", anim = anim, lane = false,
            onLive = { live(edit(l = it)) }) { set(edit(l = it)); done() }
        PropSlider(stringResource(R.string.properties_crop_right), 1f - cur.cx - cur.w / 2f, 0f, 0.98f, 0f, ::pct, "cropR", anim = anim, lane = false,
            onLive = { live(edit(r = it)) }) { set(edit(r = it)); done() }
        PropSlider(stringResource(R.string.properties_crop_top), cur.cy - cur.h / 2f, 0f, 0.98f, 0f, ::pct, "cropT", anim = anim, lane = false,
            onLive = { live(edit(t = it)) }) { set(edit(t = it)); done() }
        PropSlider(stringResource(R.string.properties_crop_bottom), 1f - cur.cy - cur.h / 2f, 0f, 0.98f, 0f, ::pct, "cropB", anim = anim,
            onLive = { live(edit(b = it)) }) { set(edit(b = it)); done() }
        PropSlider(stringResource(R.string.properties_mask_feather), crop.feather, 0f, 0.5f, 0f, ::pct, "cropFeather", lane = false,
            onLive = { live(crop.copy(feather = it)) }) { set(crop.copy(feather = it)); done() }
    }
    if (fx.crop != null) PillButton(stringResource(R.string.properties_crop_fill), {
        haptics.confirm()
        val k = maxOf(1f / cur.w.coerceAtLeast(0.02f), 1f / cur.h.coerceAtLeast(0.02f))
        backend.setProps(p.id, x = p.seqW * (0.5f + (0.5f - cur.cx) * k), y = p.seqH * (0.5f + (0.5f - cur.cy) * k), scale = 100f * k)
    }, Modifier.padding(horizontal = 16.dp, vertical = 10.dp).testTag("crop:fill"), icon = Ic.Center)
}

/** The crop's corners and edges on the picture: drag a handle, or inside the crop to move it. */
@Composable
fun CropOverlay(state: EditorState, gs: GradeState, modifier: Modifier = Modifier) {
    val id = state.cropEdit ?: return
    val controller = state.props as? se.gendut.klipp.engine.ProjectController ?: return
    controller.revision
    val snap = controller.snapshot.sequence?.video?.firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id == id } } ?: return
    val seqW = controller.snapshot.sequence?.width ?: return
    val seqH = controller.snapshot.sequence?.height ?: return
    val haptics = rememberHaptics()
    val info = remember(id, gs.clipInfo, state.clips.firstOrNull { it.id == id }) { gs.clipInfo?.invoke(id) }
    fun box(): se.gendut.klipp.engine.LayerMask {
        val c = gs.shown(id)?.layer?.crop ?: FULL_FRAME
        return if (c.animated) c.at(info?.srcAt(state.playhead)) else c
    }
    val placed = { snap.at(state.playhead.toDouble()) }
    Box(modifier.fillMaxSize().testTag("cropOverlay").pointerInput(id) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val w = size.width.toFloat(); val h = size.height.toFloat()
            val start = box()
            val c0 = placed()
            val l = start.cx - start.w / 2f; val r = start.cx + start.w / 2f; val t = start.cy - start.h / 2f; val b = start.cy + start.h / 2f
            // handles: which edges each one moves (-1 left/top, 1 right/bottom, 0 none)
            val handles = listOf(Triple(l, t, -1 to -1), Triple(r, t, 1 to -1), Triple(r, b, 1 to 1), Triple(l, b, -1 to 1),
                Triple(l, start.cy, -1 to 0), Triple(r, start.cy, 1 to 0), Triple(start.cx, t, 0 to -1), Triple(start.cx, b, 0 to 1))
            fun screen(u: Float, v: Float) = c0.toFrame(u, v, seqW, seqH).let { Offset(it[0] * w, it[1] * h) }
            val hit = handles.minByOrNull { (screen(it.first, it.second) - down.position).getDistance() }?.takeIf { (screen(it.first, it.second) - down.position).getDistance() < 44.dp.toPx() }
            val p0 = c0.fromFrame(down.position.x / w, down.position.y / h, seqW, seqH)
            val inside = p0[0] in l..r && p0[1] in t..b
            if (hit == null && !inside) return@awaitEachGesture
            down.consume()
            haptics.tick()
            val g0 = gs.of(id)
            val fx0 = g0.layer ?: se.gendut.klipp.engine.LayerFx()
            val all = fx0.crop ?: FULL_FRAME
            val tol = (FRAME / 2.0) * abs(info?.speed ?: 1.0).coerceAtLeast(1e-3)
            var result = all
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                val q = c0.fromFrame(c.position.x / w, c.position.y / h, seqW, seqH)
                var nl = l; var nt = t; var nr = 1f - r; var nb = 1f - b
                if (hit != null) {
                    val (ex, ey) = hit.third
                    if (ex < 0) nl = q[0]; if (ex > 0) nr = 1f - q[0]
                    if (ey < 0) nt = q[1]; if (ey > 0) nb = 1f - q[1]
                } else {
                    val dx = (q[0] - p0[0]).coerceIn(-l, 1f - r); val dy = (q[1] - p0[1]).coerceIn(-t, 1f - b)
                    nl = l + dx; nr = 1f - r - dx; nt = t + dy; nb = 1f - b - dy
                }
                result = all.withValues(start.withEdges(nl, nt, nr, nb), info?.srcAt(state.playhead), tol)
                gs.setLive(id, g0.copy(layer = fx0.copy(crop = result)))
                c.consume()
            }
            haptics.confirm()
            gs.set(id, g0.copy(layer = fx0.copy(crop = cropOrNull(result)).takeUnless { it.isNone }))
            scope0(gs)
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val c = box(); val pl = placed()
            fun pt(u: Float, v: Float) = pl.toFrame(u, v, seqW, seqH).let { Offset(it[0] * size.width, it[1] * size.height) }
            val l = c.cx - c.w / 2f; val r = c.cx + c.w / 2f; val t = c.cy - c.h / 2f; val b = c.cy + c.h / 2f
            val path = androidx.compose.ui.graphics.Path().apply { pt(l, t).let { moveTo(it.x, it.y) }; pt(r, t).let { lineTo(it.x, it.y) }; pt(r, b).let { lineTo(it.x, it.y) }; pt(l, b).let { lineTo(it.x, it.y) }; close() }
            drawPath(path, Color.Black.copy(alpha = 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(3.5.dp.toPx()))
            drawPath(path, K.Primary, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            for ((u, v) in listOf(l to t, r to t, r to b, l to b, l to c.cy, r to c.cy, c.cx to t, c.cx to b)) {
                drawCircle(Color.Black.copy(alpha = 0.5f), 8.dp.toPx(), pt(u, v))
                drawCircle(K.PrimarySoft, 6.5.dp.toPx(), pt(u, v))
            }
        }
    }
}

private fun scope0(gs: GradeState) { kotlinx.coroutines.MainScope().launch { gs.endLive() } }

/**
 * The mask's position and size, with keyframes (stopwatch, lane, ◀ ◆ ▶ – in source time, so they follow the clip) and
 * tracking: the mask follows an object and the track becomes keyframes. Animated: the sliders show and change the
 * values at the playhead (a keyframe there).
 */
@Composable
private fun MaskControls(
    gs: GradeState, clip: Long, state: EditorState, fx: se.gendut.klipp.engine.LayerFx, mask: se.gendut.klipp.engine.LayerMask,
    set: (se.gendut.klipp.engine.LayerFx) -> Unit, live: (se.gendut.klipp.engine.LayerFx) -> Unit, done: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val haptics = rememberHaptics()
    // the clip's placement (timeline ↔ source time); re-read when the timeline changes (trim, move)
    val info = remember(clip, gs.clipInfo, state.clips.firstOrNull { it.id == clip }) { gs.clipInfo?.invoke(clip) }
    fun srcOf(t: Float): Double? = info?.srcAt(t)
    // half a frame in source time: this close counts as the same keyframe
    val tol = (FRAME / 2.0) * abs(info?.speed ?: 1.0).coerceAtLeast(1e-3)
    // the values at the playhead (only when they actually change – not every frame while the mask stands still)
    val cur by remember(mask, info) { androidx.compose.runtime.derivedStateOf { if (mask.animated) mask.at(srcOf(state.playhead)) else mask } }
    /**
     * The source time of the keyframe "at the playhead": at the clip's last frame a keyframe at its end (after it was
     * shortened) counts as there – the jump to it stops on the last frame ([lastFrame]).
     */
    fun keySrc(): Double? {
        val s = srcOf(state.playhead) ?: return null
        val i = info ?: return s
        if (state.playhead < lastFrame(i.start, i.end) - FRAME / 2) return s
        val endSrc = i.srcAt(i.end)
        return mask.keys.firstOrNull { k -> abs(k.t - s) > tol && (k.t - s) * (endSrc - s) > 0 && abs(k.t - s) <= abs(endSrc - s) + tol }?.t ?: s
    }
    fun edit(f: (se.gendut.klipp.engine.LayerMask) -> se.gendut.klipp.engine.LayerMask): se.gendut.klipp.engine.LayerFx =
        fx.copy(mask = mask.withValues(f(cur), keySrc(), tol))
    val maskView = remember(clip) { KfView() }
    val anim = info?.let { boxAnim(it, mask, tol, state, maskView, ::srcOf, ::keySrc) { m -> set(fx.copy(mask = m)) } }
    fun pct(v: Float) = "%.0f %%".format(v * 100)
    PropSlider(stringResource(R.string.properties_mask_center_x), cur.cx, 0f, 1f, 0.5f, ::pct, "maskX", anim = anim, lane = false,
        onLive = { v -> live(edit { it.copy(cx = v) }) }) { v -> set(edit { it.copy(cx = v) }); done() }
    PropSlider(stringResource(R.string.properties_mask_center_y), cur.cy, 0f, 1f, 0.5f, ::pct, "maskY", anim = anim, lane = false,
        onLive = { v -> live(edit { it.copy(cy = v) }) }) { v -> set(edit { it.copy(cy = v) }); done() }
    PropSlider(stringResource(R.string.properties_mask_width), cur.w, 0.02f, 1.5f, 0.6f, ::pct, "maskW", anim = anim, lane = false,
        onLive = { v -> live(edit { it.copy(w = v) }) }) { v -> set(edit { it.copy(w = v) }); done() }
    PropSlider(stringResource(R.string.properties_mask_height), cur.h, 0.02f, 1.5f, 0.6f, ::pct, "maskH", anim = anim,
        onLive = { v -> live(edit { it.copy(h = v) }) }) { v -> set(edit { it.copy(h = v) }); done() }
    PropSlider(stringResource(R.string.properties_mask_feather), mask.feather, 0f, 0.5f, 0.03f, ::pct, "maskFeather",
        onLive = { v -> live(fx.copy(mask = mask.copy(feather = v))) }) { set(fx.copy(mask = mask.copy(feather = it))); done() }
    // tracking: the mask follows an object through the clip (from the frame at the playhead, in both directions) → keyframes
    if (info != null && info.hold == null && info.uri != null) {
        val busy = MaskTrackService.target == clip
        val progress = MaskTrackService.progress
        if (!busy) TrackDensityRow()
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                PillButton(stringResource(R.string.properties_cancel), { MaskTrackService.cancel() }, Modifier.testTag("mask:trackCancel"), icon = Ic.Close, filled = false)
            } else {
                PillButton(stringResource(if (mask.animated) R.string.properties_track_again else R.string.properties_track_object), {
                    MaskTrackService.start(ctx, gs, info, state.playhead) { t -> state.playing = false; state.playhead = t }
                }, Modifier.testTag("mask:track"), icon = Ic.Target)
                if (mask.animated) PillButton(stringResource(R.string.properties_clear_keyframes), {
                    haptics.confirm(); srcOf(state.playhead)?.let { set(fx.copy(mask = mask.at(it))) }
                }, Modifier.testTag("mask:keysClear"), icon = Ic.Delete, filled = false)
            }
        }
        if (busy && progress != null) {
            androidx.compose.material3.LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = K.Primary)
            Text(stringResource(R.string.properties_tracking_progress, progress * 100), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        val msg = MaskTrackService.message?.takeIf { !busy && MaskTrackService.lastClip == clip }
        Text(
            msg ?: stringResource(R.string.properties_track_hint),
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("mask:trackMsg"),
        )
    }
}

@Composable
private fun FadeRow(label: String, tag: String, current: Float?, set: (Float?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.width(32.dp))
        SpringChip(stringResource(R.string.properties_off), current == null, { set(null) }, Modifier.weight(1f), selectedColor = K.SurfaceHighest, selectedText = K.OnSurface, tight = true)
        for (s in listOf(0.5f, 1f, 2f)) {
            val on = current != null && abs(current - s) < 0.03f
            SpringChip(if (s < 1f) stringResource(R.string.properties_fade_half) else "${s.roundToInt()} s", on, { set(s) }, Modifier.weight(1f).testTag("fade$tag$s"), tight = true)
        }
    }
}

/**
 * Direct manipulation in the program monitor: one finger moves, two fingers pinch (scale) and rotate.
 * Snapping to center, 100 % and right angles with haptics; the engine gets the values when the fingers lift.
 */
@Composable
fun MotionOverlay(state: EditorState, modifier: Modifier = Modifier) {
    val backend = state.props ?: return
    val id = state.selected ?: return
    val pState = remember(id, backend) { androidx.compose.runtime.derivedStateOf { backend.props(id) } }
    val p = pState.value ?: return
    if (p.isAudio) return
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val w = p.seqW.toFloat()
    val h = p.seqH.toFloat()
    var x by remember(id) { mutableFloatStateOf(p.x ?: w / 2) }
    var y by remember(id) { mutableFloatStateOf(p.y ?: h / 2) }
    var k by remember(id) { mutableFloatStateOf(p.scale) }
    var r by remember(id) { mutableFloatStateOf(p.rotation) }
    var active by remember { mutableStateOf(false) }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(p) {
        if (!active) { x = p.x ?: w / 2; y = p.y ?: h / 2; k = p.scale; r = p.rotation }
    }
    val pNow by rememberUpdatedState(p)
    // a title's own snapshot (its text and look) for the box around the text; follows the engine's revision
    val controller = backend as? se.gendut.klipp.engine.ProjectController
    val titleSnap = if (!p.isTitle) null else controller?.let { c ->
        c.revision
        se.gendut.klipp.engine.TitleFollow.revision
        c.snapshot.sequence?.video?.firstNotNullOfOrNull { tr -> tr.clips.firstOrNull { it.id == id && it.text != null } }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("motion")) {
        val s = constraints.maxWidth / w // screen pixels per sequence pixel
        Box(
            Modifier.fillMaxSize().pointerInput(id, s, w, h) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    active = true
                    scope.launch { glow.animateTo(1f, Springs.pop()) }
                    var rawX = x; var rawY = y; var rawK = k; var rawR = r
                    var snapX = rawX > w / 2; var snapY = rawY > h / 2; var snapK = rawK > 100f; var snapR = kotlin.math.floor(rawR / 90f).roundToInt()
                    // if the gesture is canceled (clip changes, view goes away) the overlay must not get stuck as active
                    try { while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val moving = ev.changes.filter { it.pressed && it.previousPressed }
                        if (moving.isNotEmpty()) {
                            // movement of the centroid = pan
                            val pan = moving.fold(Offset.Zero) { acc, c -> acc + c.positionChange() } / moving.size.toFloat()
                            rawX += pan.x / s; rawY += pan.y / s
                            if (moving.size >= 2) {
                                val a = moving[0]; val b = moving[1]
                                val d1 = hypot(a.position.x - b.position.x, a.position.y - b.position.y)
                                val d0 = hypot(a.previousPosition.x - b.previousPosition.x, a.previousPosition.y - b.previousPosition.y)
                                if (d0 > 1f) rawK *= d1 / d0
                                val ang1 = Math.toDegrees(atan2((b.position.y - a.position.y).toDouble(), (b.position.x - a.position.x).toDouble()))
                                val ang0 = Math.toDegrees(atan2((b.previousPosition.y - a.previousPosition.y).toDouble(), (b.previousPosition.x - a.previousPosition.x).toDouble()))
                                var da = (ang1 - ang0).toFloat()
                                if (da > 180f) da -= 360f
                                if (da < -180f) da += 360f
                                rawR += da
                            }
                            rawK = rawK.coerceIn(1f, 1000f)
                            // no snapping: a tick when passing the center, 100 % or a multiple of 90°
                            val sx = rawX > w / 2; val sy = rawY > h / 2; val sk = rawK > 100f
                            val sr = kotlin.math.floor(rawR / 90f).roundToInt()
                            if (sx != snapX || sy != snapY || sk != snapK || sr != snapR) haptics.tick()
                            snapX = sx; snapY = sy; snapK = sk; snapR = sr
                            x = rawX; y = rawY; k = rawK; r = rawR
                            state.setLive(pNow.copy(x = x, y = y, scale = k, rotation = r))
                        }
                        ev.changes.forEach { it.consume() }
                    } } catch (e: kotlinx.coroutines.CancellationException) {
                        active = false
                        scope.launch { glow.animateTo(0f, Springs.settle()) }
                        scope.launch { state.endLive() }
                        throw e
                    }
                    active = false
                    scope.launch { glow.animateTo(0f, Springs.settle()) }
                    val q = pNow
                    val changed = abs(x - (q.x ?: w / 2)) > 0.5f || abs(y - (q.y ?: h / 2)) > 0.5f || abs(k - q.scale) > 0.05f || abs(r - q.rotation) > 0.05f
                    if (changed) {
                        haptics.confirm()
                        backend.setProps(q.id, x = x, y = y, scale = k, rotation = r)
                    }
                    scope.launch { state.endLive() }
                }
            }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = x * s
                val cy = y * s
                val fw = w * s * k / 100f
                val fh = h * s * k / 100f
                val g = glow.value
                // guide lines through the center while dragging
                if (g > 0.01f) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
                    drawLine(K.OnSurface.copy(alpha = 0.35f * g), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx(), pathEffect = dash)
                    drawLine(K.OnSurface.copy(alpha = 0.35f * g), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = dash)
                }
                // a title: the box right around its text (as it is drawn now, with the live values while dragging)
                val quad = titleSnap?.let { c -> se.gendut.klipp.engine.titleBoxQuad(c.copy(x = x.toDouble(), y = y.toDouble(), scale = k.toDouble(), rotation = r.toDouble(), kf = if (active) emptyMap() else c.kf),
                    state.playhead.toDouble().coerceIn(c.start, c.end - 1e-3), w.toInt(), h.toInt()) }
                val hs = (8f + 4f * g).dp.toPx()
                if (quad != null) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(quad[0] * size.width, quad[1] * size.height)
                        for (i in 1 until 4) lineTo(quad[2 * i] * size.width, quad[2 * i + 1] * size.height)
                        close()
                    }
                    drawPath(path, K.Primary.copy(alpha = 0.85f), style = Stroke((1.5f + 1.5f * g).dp.toPx()))
                    for (i in 0 until 4) drawRoundRect(K.PrimarySoft, Offset(quad[2 * i] * size.width - hs / 2, quad[2 * i + 1] * size.height - hs / 2), Size(hs, hs), CornerRadius(hs / 3))
                } else rotate(r, Offset(cx, cy)) {
                    drawRect(
                        K.Primary.copy(alpha = 0.85f), Offset(cx - fw / 2, cy - fh / 2), Size(fw, fh),
                        style = Stroke((1.5f + 1.5f * g).dp.toPx()),
                    )
                    for (dx in listOf(-1f, 1f)) for (dy in listOf(-1f, 1f)) {
                        drawRoundRect(K.PrimarySoft, Offset(cx + dx * fw / 2 - hs / 2, cy + dy * fh / 2 - hs / 2), Size(hs, hs), CornerRadius(hs / 3))
                    }
                }
                drawCircle(K.PrimarySoft, (4f + 2f * g).dp.toPx(), Offset(cx, cy))
            }
            if (active) {
                Text(
                    "X ${x.roundToInt()}  Y ${y.roundToInt()}  ·  ${k.roundToInt()} %  ·  ${r.roundToInt()}°",
                    color = K.OnSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).graphicsLayer { alpha = glow.value }
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}


/**
 * Draw a box around something in the picture (a title follows it, see FollowSection): drag from corner to corner; a
 * tap gives a box around the point. Back or the cancel chip leaves without one.
 */
@Composable
fun BoxPickOverlay(state: EditorState, modifier: Modifier = Modifier) {
    val pick = state.boxPick ?: return
    var a by remember { mutableStateOf<Offset?>(null) }
    var b by remember { mutableStateOf<Offset?>(null) }
    androidx.activity.compose.BackHandler { state.boxPick = null }
    Box(
        modifier.fillMaxSize().border(2.dp, K.Primary).testTag("boxPick").pointerInput(pick) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                a = down.position; b = down.position
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    b = c.position; c.consume()
                }
                val p = a ?: return@awaitEachGesture; val q = b ?: p
                val w = size.width.toFloat(); val h = size.height.toFloat()
                var rx = kotlin.math.abs(q.x - p.x) / 2f / w; var ry = kotlin.math.abs(q.y - p.y) / 2f / h
                var cx = (p.x + q.x) / 2f / w; var cy = (p.y + q.y) / 2f / h
                // a tap (or a tiny box): a box around the point, square on screen
                if (rx * w < 12.dp.toPx() || ry * h < 12.dp.toPx()) { ry = 0.08f; rx = ry * h / w; cx = p.x / w; cy = p.y / h }
                a = null; b = null
                state.boxPick = null
                pick(cx.coerceIn(0f, 1f), cy.coerceIn(0f, 1f), rx.coerceIn(0.01f, 0.5f), ry.coerceIn(0.01f, 0.5f))
            }
        }
    ) {
        val p = a; val q = b
        if (p != null && q != null) Canvas(Modifier.fillMaxSize()) {
            val tl = Offset(minOf(p.x, q.x), minOf(p.y, q.y))
            val sz = androidx.compose.ui.geometry.Size(kotlin.math.abs(q.x - p.x), kotlin.math.abs(q.y - p.y))
            drawRect(Color.Black.copy(alpha = 0.25f), tl, sz)
            drawRect(Color.White, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        Row(Modifier.align(Alignment.TopCenter).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.properties_box_pick_hint), color = Color.White, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000)).padding(horizontal = 8.dp, vertical = 4.dp))
            Text(stringResource(R.string.properties_cancel), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000))
                    .clickable { state.boxPick = null }.padding(horizontal = 8.dp, vertical = 4.dp).testTag("boxPick:cancel"))
        }
    }
}
