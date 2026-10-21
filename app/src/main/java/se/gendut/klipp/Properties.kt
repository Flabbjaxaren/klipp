package se.gendut.klipp

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
import androidx.compose.foundation.horizontalScroll
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Slider for a real value: drag anywhere on the track, magnetic default position with haptics,
 * double tap = reset. The engine only gets the value when the finger lifts (one undo step per drag).
 */
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
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (anim != null) Row(Modifier.width(108.dp), verticalAlignment = Alignment.CenterVertically) {
            Stopwatch(anim)
            Text(label, color = K.OnSurfaceVar, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
        } else Text(label, color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.width(88.dp))
        Box(
            Modifier.weight(1f).height(44.dp).testTag("prop:$tag")
                .pointerInput(min, max, default) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // the value when the gesture started: live values can be fed back into [value] during the drag
                        // (color page), so the comparison on release is against the start value – otherwise nothing is saved
                        val start = current
                        var atDefault = abs(v.value - default) < 1e-3f
                        var moved = false
                        var cancelled = false
                        fun valueAt(x: Float): Float {
                            var nv = min + (x / size.width).coerceIn(0f, 1f) * (max - min)
                            if (abs(nv - default) < (max - min) * 0.025f) {
                                if (!atDefault) haptics.tick()
                                atDefault = true
                                nv = default
                            } else atDefault = false
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
                drawRoundRect(K.SurfaceHighest, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                fun xOf(f: Float) = ((f - min) / (max - min)).coerceIn(0f, 1f) * size.width
                val x = xOf(v.value)
                val x0 = xOf(default.coerceIn(min, max))
                val a = kotlin.math.min(x, x0)
                val b = kotlin.math.max(x, x0)
                drawRoundRect(K.Primary.copy(alpha = 0.8f), Offset(a, top), Size((b - a).coerceAtLeast(0f), h), CornerRadius(h / 2))
                // the default position
                drawCircle(K.OnSurfaceVar.copy(alpha = 0.6f), 2.dp.toPx(), Offset(x0, size.height / 2))
                val hw = (4f + 2f * grow).dp.toPx()
                val hh = (28f + 8f * grow).dp.toPx()
                drawRoundRect(K.PrimarySoft, Offset(x - hw / 2, (size.height - hh) / 2), Size(hw, hh), CornerRadius(hw / 2))
            }
        }
        Text(
            format(v.value), color = K.OnSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp).padding(start = 8.dp),
        )
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
    Column(Modifier.fillMaxSize().background(K.Bg).verticalScroll(rememberScrollState()).testTag("properties")) {
        PanelTitle(stringResource(R.string.properties_title)) { RoundIcon(Ic.Check, stringResource(R.string.properties_done)) { haptics.tick(); onClose() } }
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
        val lowest = (if (p.isAudio) listOf("level") else listOf("position", "scale", "rotation", "opacity")).lastOrNull { p.kf[it].orEmpty().isNotEmpty() }
        fun anim(key: String) = AnimUi(
            key, p.kf[key].orEmpty(), p.clipStart, p.clipEnd, { state.playhead },
            onToggle = { backend.toggleAnimation(p.id, key, state.playhead) },
            onKey = { backend.toggleKeyframe(p.id, key, state.playhead) },
            onJump = { t -> state.playing = false; state.playhead = t.coerceIn(p.clipStart, p.clipEnd) },
            onMove = { k, t -> backend.moveKeyframe(p.id, key, k.mt, t) },
            onInterp = { k -> backend.setInterpolation(p.id, key, k.mt, nextInterp(k.interp)) },
            view = kfView, zoomBar = key == lowest,
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
        Text(
            p.name, color = K.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(if (p.isTitle) ClipKind.TITLE.bg else if (p.isAudio) ClipKind.AUDIO.bg else ClipKind.VIDEO.bg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (p.isAudio) {
            SectionLabel(stringResource(R.string.properties_audio))
            PropSlider(stringResource(R.string.properties_volume), p.levelDb, -40f, 15f, 0f, { "%+.1f dB".format(it).replace('-', '−') }, "volume", anim = anim("level"),
                onLive = { v -> live { it.copy(levelDb = v) } }) { backend.setProps(p.id, levelDb = it); done() }
            AudioFxSection(state, p, backend)
        } else {
            if (p.isTitle) TitleSection(state, p, backend)
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.properties_motion)) }
                SpringChip(stringResource(R.string.properties_reset), false, { haptics.confirm(); backend.resetMotion(p.id) })
            }
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
            PropSlider(stringResource(R.string.properties_opacity), p.opacity, 0f, 100f, 100f, { fmtNum(it, " %") }, "opacity", anim = anim("opacity"),
                onLive = { v -> live { it.copy(opacity = v) } }) { backend.setProps(p.id, opacity = it); done() }
            Text(
                stringResource(R.string.properties_motion_hint),
                color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            SectionLabel(stringResource(R.string.properties_fade))
            FadeRow(stringResource(R.string.properties_fade_in), "In", p.fadeIn) { haptics.confirm(); backend.setTransition(p.id, true, it) }
            FadeRow(stringResource(R.string.properties_fade_out), "Ut", p.fadeOut) { haptics.confirm(); backend.setTransition(p.id, false, it) }
            if (!p.isTitle) LocalGradeState.current?.let { gs -> LayerSection(gs, p.id, state) }
        }
        Spacer(Modifier.height(120.dp))
    }
}

/**
 * The clip as a layer: green screen, mask and blend mode (saved with the clip's grade – undoable, carried over on
 * split). Sliders preview live; releasing one makes an undo step.
 */
@Composable
private fun LayerSection(gs: GradeState, clip: Long, state: EditorState) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val shown = gs.shown(clip) ?: ClipGrade()
    val fx = shown.layer ?: se.gendut.klipp.engine.LayerFx()
    fun set(f: se.gendut.klipp.engine.LayerFx) = gs.set(clip, gs.of(clip).copy(layer = f.takeUnless { it.isNone }))
    fun live(f: se.gendut.klipp.engine.LayerFx) = gs.setLive(clip, gs.of(clip).copy(layer = f))
    fun done() { scope.launch { gs.endLive() } }

    SectionLabel(stringResource(R.string.properties_green_screen))
    val key = fx.key
    Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

    SectionLabel(stringResource(R.string.properties_blend))
    Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in se.gendut.klipp.engine.BlendMode.entries) {
            SpringChip(m.label, fx.blend == m, { haptics.tick(); set(fx.copy(blend = m)) }, Modifier.testTag("blend:${m.name}"))
        }
    }
    if (fx.blend != se.gendut.klipp.engine.BlendMode.NORMAL) Text(
        stringResource(R.string.properties_blend_hint),
        color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )

    SectionLabel(stringResource(R.string.properties_mask))
    val mask = fx.mask
    Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    fun edit(f: (se.gendut.klipp.engine.LayerMask) -> se.gendut.klipp.engine.LayerMask): se.gendut.klipp.engine.LayerFx =
        fx.copy(mask = mask.withValues(f(cur), srcOf(state.playhead), tol))
    val maskView = remember(clip) { KfView() }
    val anim = if (info == null) null else {
        val lo = info.srcIn
        val hi = info.srcIn + (info.end - info.start) * abs(info.speed)
        AnimUi(
            "mask",
            mask.keys.filter { it.t >= lo - tol && it.t <= hi + tol }.map { k ->
                UiKf(info.timelineAt(k.t), Math.round(k.t * 1e6), when (k.interp) {
                    se.gendut.klipp.engine.MaskKey.HOLD -> "Hold"; se.gendut.klipp.engine.MaskKey.SMOOTH -> "AutoBezier"; else -> "Linear"
                })
            },
            info.start, info.end, { state.playhead },
            onToggle = { srcOf(state.playhead)?.let { set(fx.copy(mask = mask.toggleAnimation(it))) } },
            onKey = { srcOf(state.playhead)?.let { set(fx.copy(mask = mask.toggleKey(it, tol))) } },
            onJump = { t -> state.playing = false; state.playhead = t.coerceIn(info.start, info.end) },
            onMove = { k, t ->
                val mk = mask.keys.firstOrNull { Math.round(it.t * 1e6) == k.mt }
                val to = srcOf(t)
                if (mk != null && to != null) set(fx.copy(mask = mask.moveKey(mk, to, tol)))
            },
            onInterp = { k ->
                mask.keys.firstOrNull { Math.round(it.t * 1e6) == k.mt }?.let { mk ->
                    val next = when (mk.interp) {
                        se.gendut.klipp.engine.MaskKey.LINEAR -> se.gendut.klipp.engine.MaskKey.SMOOTH
                        se.gendut.klipp.engine.MaskKey.SMOOTH -> se.gendut.klipp.engine.MaskKey.HOLD
                        else -> se.gendut.klipp.engine.MaskKey.LINEAR
                    }
                    set(fx.copy(mask = mask.setInterp(mk, next)))
                }
            },
            view = maskView,
        )
    }
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
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        SpringChip(stringResource(R.string.properties_off), current == null, { set(null) }, selectedColor = K.SurfaceHighest, selectedText = K.OnSurface)
        for (s in listOf(0.5f, 1f, 2f)) {
            val on = current != null && abs(current - s) < 0.03f
            SpringChip(if (s < 1f) stringResource(R.string.properties_fade_half) else "${s.roundToInt()} s", on, { set(s) }, Modifier.testTag("fade$tag$s"), selectedColor = K.Primary, selectedText = K.OnPrimary)
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
    BoxWithConstraints(modifier.fillMaxSize().testTag("motion")) {
        val s = constraints.maxWidth / w // screen pixels per sequence pixel
        Box(
            Modifier.fillMaxSize().pointerInput(id, s, w, h) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    active = true
                    scope.launch { glow.animateTo(1f, Springs.pop()) }
                    var snapX = false; var snapY = false; var snapK = false; var snapR = false
                    var rawX = x; var rawY = y; var rawK = k; var rawR = r
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
                            // magnetic snapping
                            val tolPos = 14.dp.toPx() / s
                            val nx = if (abs(rawX - w / 2) < tolPos) w / 2 else rawX
                            val ny = if (abs(rawY - h / 2) < tolPos) h / 2 else rawY
                            val nk = if (abs(rawK - 100f) < 4f) 100f else rawK
                            val near = (rawR / 90f).roundToInt() * 90f
                            val nr = if (abs(rawR - near) < 4f) near else rawR
                            if ((nx == w / 2) != snapX) { snapX = nx == w / 2; if (snapX) haptics.tick() }
                            if ((ny == h / 2) != snapY) { snapY = ny == h / 2; if (snapY) haptics.tick() }
                            if ((nk == 100f) != snapK) { snapK = nk == 100f; if (snapK) haptics.tick() }
                            if ((nr == near) != snapR) { snapR = nr == near; if (snapR) haptics.tick() }
                            x = nx; y = ny; k = nk; r = nr
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
                rotate(r, Offset(cx, cy)) {
                    drawRect(
                        K.Primary.copy(alpha = 0.85f), Offset(cx - fw / 2, cy - fh / 2), Size(fw, fh),
                        style = Stroke((1.5f + 1.5f * g).dp.toPx()),
                    )
                    val hs = (8f + 4f * g).dp.toPx()
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

