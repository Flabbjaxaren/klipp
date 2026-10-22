package se.gendut.klipp

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.magnifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Height of an empty track's slim lane (dp). */
private const val SLIM_TRACK_DP = 16
/** A swipe down that leaves the touch slop within this time (ms) is a cut, not a move. */
private const val KNIFE_MS = 160L
/** Upward speed (dp/s) at release that flings a dragged clip out (delete). */
private const val FLING_OUT_DP = 2400f

/** The platform magnifier (Android 9+; not in the JVM test environment, which has no surfaces for it). */
private val LOUPE = Build.VERSION.SDK_INT >= 28 && Build.FINGERPRINT != "robolectric"

/** SELECT move, RIPPLE ripple, RAZOR razor, TRIM trim (slip on the clip, roll on the edit point). */
enum class Tool { SELECT, RIPPLE, RAZOR, TRIM }

class Haptics(private val view: View) {
    companion object {
        /** Haptic feedback on touch (setting). */
        @Volatile var enabled = true
    }
    fun tick() {
        if (!enabled) return
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        )
    }
    fun confirm() {
        if (!enabled) return
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }
    fun heavy() { if (enabled) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

private class ClipAnim(start: Float, len: Float, track: Float, appear: Float) {
    val start = Animatable(start)
    val len = Animatable(len)
    val track = Animatable(track)
    val lift = Animatable(0f)
    val appear = Animatable(appear)
    val pulse = Animatable(1f)
    /** 1 = the clip is dragged out of the timeline and will be deleted on release. */
    val doom = Animatable(0f)
}

private sealed interface Hit {
    data class Body(val id: Long) : Hit
    data class TrimL(val id: Long) : Hit
    data class TrimR(val id: Long) : Hit
    /** Edge of a transition (drag = change duration). */
    data class TransEdge(val id: Long, val left: Boolean) : Hit
    /** Middle of a transition (tap = select). */
    data class TransBody(val id: Long) : Hit
    /** Fade handle in the selected clip's top corner (drag inward = fade in/out or cross dissolve). */
    data class Fade(val id: Long, val inEdge: Boolean) : Hit
    /** The edit point between two adjacent clips (trim tool: roll). */
    data class Roll(val left: Long, val right: Long) : Hit
    /** A caption in the caption lane: edge (−1 = in, 1 = out) or the whole (0). */
    data class Cap(val id: Long, val edge: Int) : Hit
    /** Empty area in the caption lane. */
    data object CapLane : Hit
    data object Ruler : Hit
    data object Empty : Hit
}

@Composable
fun Timeline(state: EditorState, tool: Tool, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    // room for the track name and a button (eye/speaker) right in the header
    val headerW = 68.dp
    // the caption lane is fixed under the ruler (as in Premiere/FilmCraft) when the sequence has a caption track
    val rulerBaseH = 28.dp
    val capH = if (state.hasCaptionLane) 30.dp else 0.dp
    val rulerH = rulerBaseH + capH
    val rulerPx = with(density) { rulerH.toPx() }
    val rulerBasePx = with(density) { rulerBaseH.toPx() }
    // reused drawing objects (not new ones every frame during playback and scrolling)
    val scratchPath = remember { Path() }
    val scratchPath2 = remember { Path() }
    val lassoDash = remember(density) { with(density) { androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())) } }
    /** Text in the caption lane (one object, not a new one on every draw). */
    val capPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(0xD8, 0xF5, 0xEC) } }
    /** Caption being dragged right now: (id, start, end) – shown until the engine's reply arrives. */
    var capDrag by remember { mutableStateOf<Triple<Long, Float, Float>?>(null) }
    /** Group move: the selected captions shifted by this much (s) – shown until the engine's reply arrives. */
    var capGroupDt by remember { mutableStateOf<Pair<Set<Long>, Float>?>(null) }
    LaunchedEffect(state.captions) { capDrag = null; capGroupDt = null }
    val fullPx = remember(density) { TRACKS.map { with(density) { it.heightDp.dp.toPx() } } }
    val slimPx = with(density) { SLIM_TRACK_DP.dp.toPx() }
    // track heights, animated: an empty track (other than V1/A1 and the target tracks) shrinks to a slim lane and opens
    // while a clip is dragged over it – more room for the tracks in use
    val trackH = remember(density) { fullPx.map { Animatable(it) } }
    /** The track a dragged clip is over right now (a slim lane opens under it). */
    var hoverTrack by remember { mutableIntStateOf(-1) }
    for (i in TRACKS.indices) {
        val full = i == 2 || i == 3 || i == state.targetVideo || i == state.targetAudio || i == hoverTrack ||
            state.clips.any { it.track == i && it.id !in state.removing }
        LaunchedEffect(i, full, fullPx, slimPx) { trackH[i].animateTo(if (full) fullPx[i] else slimPx, Springs.settle()) }
    }
    // read at use (in gestures and drawing), so they always follow the animated heights
    val heightsPx: List<Float> = remember(trackH) { object : AbstractList<Float>() { override val size get() = TRACKS.size; override fun get(index: Int) = trackH[index].value } }
    val topsPx: List<Float> = remember(trackH) {
        object : AbstractList<Float>() {
            override val size get() = TRACKS.size
            override fun get(index: Int): Float { var a = 0f; for (k in 0 until index) a += trackH[k].value; return a }
        }
    }
    val snapPx = with(density) { 14.dp.toPx() }
    val handlePx = with(density) { 18.dp.toPx() }
    val fadeZonePx = with(density) { 26.dp.toPx() }
    val fadeTopPx = with(density) { 24.dp.toPx() }
    val transEdgePx = with(density) { 14.dp.toPx() }
    /** How far outside a transition its edge can be grabbed. */
    val transEdgeOutPx = with(density) { 6.dp.toPx() }
    /** A tap this close to the edit point selects it (instead of the clip). */
    val cutTapPx = with(density) { 14.dp.toPx() }
    /** Any clip's edge can be dragged (trim) within this distance inside the clip. */
    val edgePx = with(density) { 12.dp.toPx() }
    /** A clip released this far above the tracks (or flung up) is deleted. */
    val outPx = with(density) { 8.dp.toPx() }
    val minSpan = with(density) { 40.dp.toPx() } // pinch zoom: minimum finger distance
    /** Preview while a transition is dragged: (id, start, end, anchor, edit point); id −1 = new. */
    var transPreview by remember { mutableStateOf<UiTransition?>(null) }

    var pps by remember { mutableFloatStateOf(with(density) { 16.dp.toPx() }) } // pixels per second
    var freeScroll by remember { mutableFloatStateOf(0f) }
    var laneWidth by remember { mutableFloatStateOf(1f) }
    var laneHeight by remember { mutableFloatStateOf(1f) }
    // vertical scrolling (to reach the audio tracks when the source panel takes up space)
    var scrollY by remember { mutableFloatStateOf(0f) }
    val bottomPadPx = with(density) { 96.dp.toPx() }
    fun maxScrollY() = (topsPx[TRACKS.lastIndex] + heightsPx[TRACKS.lastIndex] + bottomPadPx - (laneHeight - rulerPx)).coerceAtLeast(0f)
    fun clampY(v: Float) = v.coerceIn(0f, maxScrollY())
    // Locked mode: the playhead stays in the center and the timeline slides under it.
    fun sx(): Float = if (state.centerPlayhead) state.playhead * pps - laneWidth / 2f else freeScroll
    fun setScroll(v: Float) {
        if (state.centerPlayhead) state.playhead = ((v + laneWidth / 2f) / pps).coerceAtLeast(0f)
        else freeScroll = v.coerceAtLeast(0f)
    }
    var dragId by remember { mutableStateOf<Long?>(null) }
    /** Selection box (hold a finger on an empty area, drag over clips): the corners in content coordinates. */
    var lassoPts by remember { mutableStateOf<List<Offset>?>(null) }
    var lassoLive by remember { mutableStateOf<Set<Long>>(emptySet()) }
    /** The captions inside the selection box right now. */
    var lassoCapLive by remember { mutableStateOf<Set<Long>>(emptySet()) }
    /** The razor dragged up/down: (time, first track, track under the finger). */
    var razorLine by remember { mutableStateOf<Triple<Float, Int, Int>?>(null) }
    var snapLine by remember { mutableStateOf<Float?>(null) }
    /** Where the finger is during a trim (the loupe magnifies there); null = no loupe. */
    var loupe by remember { mutableStateOf<Offset?>(null) }
    /** A dragged clip is out of the timeline: released now it is deleted. */
    var deleteArmed by remember { mutableStateOf(false) }
    /** Scrub speed tier while the playhead is dragged (finger moved down = slower, see ScrubTiers). */
    var scrubTier by remember { mutableIntStateOf(0) }
    val snapGlow = remember { Animatable(0f) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var followJob by remember { mutableStateOf<Job?>(null) }

    val anims = remember { mutableStateMapOf<Long, ClipAnim>() }
    var initialized by remember { mutableStateOf(false) }

    fun yOf(tf: Float): Float {
        val last = TRACKS.lastIndex
        if (tf <= 0f) return topsPx[0] + tf * heightsPx[0]
        if (tf >= last) return topsPx[last] + (tf - last) * heightsPx[last]
        val i = floor(tf).toInt()
        val f = tf - i
        return topsPx[i] + (topsPx[i + 1] - topsPx[i]) * f
    }
    fun hOf(tf: Float): Float {
        val i = floor(tf.coerceIn(0f, TRACKS.lastIndex.toFloat())).toInt()
        val j = min(i + 1, TRACKS.lastIndex)
        val f = tf.coerceIn(0f, TRACKS.lastIndex.toFloat()) - i
        return heightsPx[i] + (heightsPx[j] - heightsPx[i]) * f
    }
    fun trackAt(y: Float, kind: TrackKind): Int {
        val range = if (kind == TrackKind.VIDEO) 0..2 else 3..5
        var best = range.first
        var bestD = Float.MAX_VALUE
        for (i in range) {
            val mid = topsPx[i] + heightsPx[i] / 2f
            val d = abs(mid - y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** The track (any kind) at height [y] in content coordinates. */
    fun anyTrackAt(y: Float): Int {
        for (i in TRACKS.indices) if (y < topsPx[i] + heightsPx[i]) return i
        return TRACKS.lastIndex
    }
    fun clipRects(): Map<Long, androidx.compose.ui.geometry.Rect> = state.clips.filter { it.id !in state.removing }.mapNotNull { c ->
        val a = anims[c.id] ?: return@mapNotNull null
        val ty = yOf(a.track.value)
        c.id to androidx.compose.ui.geometry.Rect(a.start.value * pps, ty, (a.start.value + a.len.value) * pps, ty + hOf(a.track.value))
    }.toMap()

    /** Clear everything temporary from a gesture and return the clips to the model's state (cancelled gesture). */
    fun abandonGesture() {
        dragId = null; snapLine = null; scrubTier = 0; loupe = null; deleteArmed = false; hoverTrack = -1; lassoPts = null; lassoLive = emptySet(); lassoCapLive = emptySet(); razorLine = null; transPreview = null; capDrag = null; capGroupDt = null
        state.trimView = null
        for (c in state.clips) anims[c.id]?.let { a ->
            scope.launch { a.start.animateTo(c.start, Springs.settle()) }
            scope.launch { a.len.animateTo(c.length, Springs.settle()) }
            scope.launch { a.track.animateTo(c.track.toFloat(), Springs.track()) }
            scope.launch { a.lift.animateTo(0f, Springs.lift()) }
            scope.launch { a.doom.animateTo(0f, Springs.lift()) }
        }
    }

    // Glow on the snap line
    LaunchedEffect(snapLine) {
        if (snapLine != null) {
            snapGlow.snapTo(1.6f)
            snapGlow.animateTo(1f, Springs.pop())
        } else snapGlow.animateTo(0f, tween(160))
    }

    // Follow the playhead during playback. The glide is its own job: a touch on the timeline stops it (otherwise
    // it overwrote the finger's scrolling), and a fling is stopped when playback starts.
    LaunchedEffect(Unit) {
        snapshotFlow { state.playhead }.collect { ph ->
            if (!state.playing || state.centerPlayhead || followJob?.isActive == true) return@collect
            val x = ph * pps - freeScroll
            if (x > laneWidth * 0.82f) {
                val target = ph * pps - laneWidth * 0.25f
                followJob = scope.launch { animate(freeScroll, target, animationSpec = Springs.settle()) { v, _ -> freeScroll = v } }
            }
        }
    }
    LaunchedEffect(state.playing) { if (state.playing) flingJob?.cancel() else followJob?.cancel() }

    // Removal animations. Each removal gets its own job that always ends with finishRemove – even if
    // the animation is cancelled (another animation on the clip, the timeline leaves the screen). Otherwise a
    // clip could stay behind invisible without the engine knowing (undo did nothing, the picture still played).
    val removeJobs = remember { HashMap<Long, Job>() }
    LaunchedEffect(Unit) {
        snapshotFlow { state.removing.toList() }.collect { list ->
            for (id in list) {
                if (removeJobs.containsKey(id)) continue
                val a = anims[id]
                if (a == null) { state.finishRemove(id); continue }
                removeJobs[id] = scope.launch {
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(700) {
                            a.lift.animateTo(0.6f, Springs.lift())
                            a.appear.animateTo(0f, tween(200))
                        }
                    } finally {
                        removeJobs.remove(id)
                        state.finishRemove(id)
                    }
                }
            }
        }
    }

    LaunchedEffect(state.clips) {
        val ids = state.clips.map { it.id }.toSet()
        anims.keys.retainAll(ids)
        initialized = true
    }

    Row(modifier.background(K.Lane)) {
        // Track headers
        Column(Modifier.width(headerW).fillMaxHeight().background(K.Surface)) {
            // the whole timeline in the corner: the visible part as a frame; tap or drag = go there
            Canvas(
                Modifier.height(rulerBaseH).fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 5.dp, bottom = 5.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            flingJob?.cancel(); followJob?.cancel()
                            state.playing = false
                            haptics.tick()
                            fun jump(x: Float) {
                                val total = max(state.projectEnd, (sx() + laneWidth) / pps).coerceAtLeast(1f)
                                setScroll((x / size.width).coerceIn(0f, 1f) * total * pps - laneWidth / 2f)
                            }
                            jump(down.position.x)
                            down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                jump(c.position.x)
                                c.consume()
                            }
                        }
                    }
                    .testTag("minimap")
            ) {
                val total = max(state.projectEnd, (sx() + laneWidth) / pps).coerceAtLeast(1f)
                fun x(t: Float) = t / total * size.width
                drawRoundRect(K.Deep, cornerRadius = CornerRadius(4.dp.toPx()))
                val rowH = size.height / TRACKS.size
                for (c in state.clips) {
                    if (c.id in state.removing) continue
                    drawRect(c.kind.fg.copy(alpha = 0.7f), Offset(x(c.start), c.track * rowH + rowH * 0.2f), Size((x(c.end) - x(c.start)).coerceAtLeast(1f), rowH * 0.6f))
                }
                val v0 = x(sx() / pps); val v1 = x((sx() + laneWidth) / pps)
                drawRoundRect(K.PrimarySoft, Offset(v0, 0f), Size((v1 - v0).coerceAtLeast(3f), size.height), CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                drawRect(K.Primary, Offset(x(state.playhead) - 0.75.dp.toPx(), 0f), Size(1.5.dp.toPx(), size.height))
            }
            if (state.hasCaptionLane) Row(Modifier.height(capH).fillMaxWidth().padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TXT", color = if (state.captionsOn) K.Teal else K.Outline, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // show/hide the captions (like the eye on a video track)
                HeaderToggle(if (state.captionsOn) Ic.Eye else Ic.EyeOff, if (state.captionsOn) stringResource(R.string.timeline_hide_captions) else stringResource(R.string.timeline_show_captions), !state.captionsOn, "capToggle") {
                    haptics.tick(); state.onCaptionsVisible?.invoke(!state.captionsOn)
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dy ->
                            scrollY = clampY(scrollY - dy)
                            change.consume()
                        }
                    }
            ) {
            Column(Modifier.layout { m, c ->
                val pl = m.measure(c.copy(maxHeight = Constraints.Infinity))
                layout(pl.width, c.maxHeight) { pl.place(0, -scrollY.roundToInt()) }
            }) {
            TRACKS.forEachIndexed { i, t ->
                val ts = state.trackStates.getOrNull(i) ?: TrackState()
                var menu by remember { mutableStateOf(false) }
                val targeted = i == state.targetVideo || i == state.targetAudio
                val lamp by androidx.compose.animation.animateColorAsState(if (targeted) K.Primary else K.OutlineVar, label = "lamp$i")
                val lampH by androidx.compose.animation.core.animateDpAsState(if (targeted) 24.dp else 16.dp, androidx.compose.animation.core.spring(0.35f, 500f), label = "lh$i")
                val slim = trackH[i].value < fullPx[i] * 0.7f
                Row(
                    Modifier.height(with(density) { trackH[i].value.toDp() }).fillMaxWidth().clipToBounds()
                        .testTag("trackHeader:${t.name}")
                        // tap = target track, hold = track states (lock, hide/mute, solo, sync lock)
                        .combinedClickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                            onLongClick = { haptics.heavy(); menu = true },
                        ) {
                            haptics.tick()
                            val cb = state.onTargetTrack
                            if (cb != null) cb(i) else if (t.kind == TrackKind.VIDEO) state.targetVideo = i else state.targetAudio = i
                        }
                        .padding(start = 8.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackMenu(menu, { menu = false }, t, ts) { flag, on -> haptics.tick(); state.setTrackFlag(i, flag, on) }
                    // an empty track as a slim lane: just its name (tap = target it, which opens it)
                    if (slim) { Text(t.name, color = K.Outline, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 14.dp)); return@Row }
                    Box(
                        Modifier.size(6.dp, lampH).clip(RoundedCornerShape(3.dp))
                            .background(lamp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(t.name, color = if (ts.enabled && !ts.muted) K.OnSurfaceVar else K.Outline, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        // the states as small badges under the name
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (ts.locked) androidx.compose.material3.Icon(Ic.Lock, stringResource(R.string.timeline_locked), tint = K.Primary, modifier = Modifier.size(11.dp))
                            if (ts.solo) Text("S", color = K.Teal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        // the audio track's level while it plays
                        if (t.kind == TrackKind.AUDIO) TrackMeter(t.name.drop(1).toInt() - 1, state.playing, Modifier.padding(top = 2.dp).size(22.dp, 3.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    // hide the video track / mute the audio track directly (hold the header for lock, solo and sync lock)
                    if (t.kind == TrackKind.VIDEO) HeaderToggle(if (ts.enabled) Ic.Eye else Ic.EyeOff, if (ts.enabled) stringResource(R.string.timeline_hide_track) else stringResource(R.string.timeline_show_track), !ts.enabled, "trackToggle:${t.name}") {
                        haptics.tick(); state.setTrackFlag(i, "enabled", !ts.enabled)
                    } else HeaderToggle(if (ts.muted) Ic.SpeakerOff else Ic.Speaker, if (ts.muted) stringResource(R.string.timeline_unmute) else stringResource(R.string.timeline_mute_track), ts.muted, "trackToggle:${t.name}") {
                        haptics.tick(); state.setTrackFlag(i, "muted", !ts.muted)
                    }
                }
            }
            }
            }
        }

        // Lanes + ruler
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("timeline")
                .clipToBounds()
                .systemGestureExclusion()
                // a loupe above the finger while an edge is trimmed (the finger covers what it moves)
                .then(if (!LOUPE) Modifier else Modifier.magnifier(
                    sourceCenter = { loupe ?: Offset.Unspecified },
                    magnifierCenter = { loupe?.let { it - Offset(0f, 72.dp.toPx()) } ?: Offset.Unspecified },
                    zoom = 2f,
                    size = androidx.compose.ui.unit.DpSize(132.dp, 60.dp),
                    cornerRadius = 14.dp,
                ))
                .layout { m, c ->
                    val p = m.measure(c)
                    laneWidth = p.width.toFloat()
                    laneHeight = p.height.toFloat()
                    layout(p.width, p.height) { p.place(0, 0) }
                }
                // keys: everything that changes the geometry the gesture relies on (the caption lane shifts the tracks 30 dp)
                .pointerInput(tool, state.hasCaptionLane) {
                    awaitEachGesture {
                      try {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        flingJob?.cancel()
                        followJob?.cancel()
                        val start = down.position
                        val hit: Hit = run {
                            val y = start.y - rulerPx + scrollY
                            val cx = start.x + sx()
                            if (start.y >= rulerBasePx && start.y < rulerPx) {
                                val sc = state.selectedCaption
                                state.captions.firstOrNull { it.id == sc }?.let { c ->
                                    val x0 = c.start * pps
                                    val x1 = c.end * pps
                                    // short blocks: the handles take at most a quarter each, the middle moves
                                    val hz = min(handlePx, (x1 - x0) / 4f)
                                    if (cx >= x0 - hz && cx < x0 + hz) return@run Hit.Cap(c.id, -1)
                                    if (cx > x1 - hz && cx <= x1 + hz) return@run Hit.Cap(c.id, 1)
                                }
                                val c = state.captions.firstOrNull { cx >= it.start * pps && cx <= it.end * pps } ?: return@run Hit.CapLane
                                return@run Hit.Cap(c.id, 0)
                            }
                            if (y < 0) return@run Hit.Ruler
                            val sel = state.selected
                            val list = state.clips.filter { it.id !in state.removing }
                            val canFade = state.props != null && tool != Tool.RAZOR
                            // the fade handles in the selected clip's top corners
                            if (sel != null && canFade) {
                                val c = list.firstOrNull { it.id == sel }
                                val a = c?.let { anims[it.id] }
                                if (a != null) {
                                    val ty = yOf(a.track.value)
                                    val x0 = a.start.value * pps
                                    val x1 = (a.start.value + a.len.value) * pps
                                    if (y >= ty - 6f && y <= ty + fadeTopPx && x1 - x0 > fadeZonePx * 2) {
                                        if (cx >= x0 - 4f && cx <= x0 + fadeZonePx) return@run Hit.Fade(sel, true)
                                        if (cx <= x1 + 4f && cx >= x1 - fadeZonePx) return@run Hit.Fade(sel, false)
                                    }
                                }
                            }
                            // transitions sit on top of the clips
                            if (canFade) for (tr in state.transitions) {
                                val ty = yOf(tr.track.toFloat())
                                if (y < ty || y > ty + hOf(tr.track.toFloat())) continue
                                val x0 = tr.start * pps
                                val x1 = tr.end * pps
                                // the edges: only a little bit outside the transition (otherwise it took touches far into the
                                // neighboring clips when the timeline is zoomed out), inside at most a third of the transition
                                val outside = transEdgeOutPx
                                val inside = minOf(transEdgePx, (x1 - x0) / 3f)
                                if (cx > x0 - outside && cx < x0 + inside && tr.anchor != -1) return@run Hit.TransEdge(tr.id, true)
                                if (cx > x1 - inside && cx < x1 + outside && tr.anchor != 1) return@run Hit.TransEdge(tr.id, false)
                                if (cx > x0 && cx < x1) return@run Hit.TransBody(tr.id)
                            }
                            // trim tool: the edit point between two adjacent clips = roll
                            if (tool == Tool.TRIM) {
                                for ((l, r) in state.cutPairs()) {
                                    val ty = yOf(l.track.toFloat())
                                    if (y < ty || y > ty + hOf(l.track.toFloat())) continue
                                    if (abs(cx - r.start * pps) < handlePx) return@run Hit.Roll(l.id, r.id)
                                }
                            }
                            if (sel != null && tool != Tool.RAZOR) {
                                val a = anims[sel]
                                if (a != null) {
                                    val ty = yOf(a.track.value)
                                    if (y >= ty && y <= ty + hOf(a.track.value)) {
                                        val x0 = a.start.value * pps
                                        val x1 = (a.start.value + a.len.value) * pps
                                        if (abs(cx - x0) < handlePx && cx < x0 + (x1 - x0) / 2) return@run Hit.TrimL(sel)
                                        if (abs(cx - x1) < handlePx) return@run Hit.TrimR(sel)
                                    }
                                }
                            }
                            // any clip's edge (not only the selected one's): drag = trim. Only the inner part, so the edge
                            // belongs to the clip under the finger (adjacent clips: left of the cut = the left clip's end)
                            if (tool == Tool.SELECT || tool == Tool.RIPPLE) for (c in list.asReversed()) {
                                val a = anims[c.id] ?: continue
                                val ty = yOf(a.track.value)
                                if (y < ty || y > ty + hOf(a.track.value)) continue
                                val x0 = a.start.value * pps
                                val x1 = (a.start.value + a.len.value) * pps
                                val zone = min(edgePx, (x1 - x0) / 4f)
                                if (cx >= x0 && cx < x0 + zone) return@run Hit.TrimL(c.id)
                                if (cx > x1 - zone && cx <= x1) return@run Hit.TrimR(c.id)
                            }
                            for (c in list.asReversed()) {
                                val a = anims[c.id] ?: continue
                                val ty = yOf(a.track.value)
                                val x0 = a.start.value * pps
                                val x1 = (a.start.value + a.len.value) * pps
                                if (cx in x0..x1 && y >= ty && y <= ty + hOf(a.track.value)) return@run Hit.Body(c.id)
                            }
                            Hit.Empty
                        }

                        if (DEBUG_GESTURES) println("TL hit=$hit y=${start.y} x=${start.x}")
                        // empty part of the caption lane: hold the finger still = selection box (as on an empty area among the tracks)
                        var capLaneLasso = false
                        var capPending: androidx.compose.ui.input.pointer.PointerEvent? = null
                        if (hit is Hit.CapLane) {
                            val r = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var out: androidx.compose.ui.input.pointer.PointerEvent
                                while (true) {
                                    out = awaitPointerEvent()
                                    val p = out.changes.filter { it.pressed }
                                    if (p.size != 1 || out.changes.any { it.isConsumed } || (p[0].position - start).getDistance() > viewConfiguration.touchSlop) break
                                }
                                out
                            }
                            if (r == null) capLaneLasso = true else capPending = r
                        }
                        // caption lane: tap = select, drag = move, drag an edge (selected) = trim
                        if ((hit is Hit.Cap || hit is Hit.CapLane) && !capLaneLasso) {
                            val cap = (hit as? Hit.Cap)?.let { h -> state.captions.firstOrNull { it.id == h.id } }
                            val edge = (hit as? Hit.Cap)?.edge ?: 0
                            val scroll0 = sx()
                            val minLen = FRAME
                            // the neighbors on the same track set the limits (the engine doesn't let them overlap)
                            val same = state.captions.filter { cap != null && it.track == cap.track && it.id != cap.id }
                            val lo = cap?.let { c -> same.filter { it.end <= c.start + 1e-3f }.maxOfOrNull { it.end } } ?: 0f
                            val hi = cap?.let { c -> same.filter { it.start >= c.end - 1e-3f }.minOfOrNull { it.start } } ?: Float.MAX_VALUE
                            val cands = listOf(0f, state.playhead) + state.markers + state.clips.filter { it.id !in state.removing }.flatMap { listOf(it.start, it.end) } +
                                state.captions.filter { it.id != cap?.id }.flatMap { listOf(it.start, it.end) }
                            fun snapOf(v: Float): Float? = cands.minByOrNull { abs(it - v) }?.takeIf { abs(it - v) * pps < snapPx }
                            // a selected group: the caption moves the whole group (captions and clips)

                            val group = cap != null && edge == 0 && cap.id in state.multiCaptions
                            val groupCaps = if (group) state.multiCaptions else emptySet()
                            val groupRange = if (group) state.captionGroupRange(groupCaps) else 0f..0f
                            val groupClips = if (group) state.clips.filter { it.id in state.multi && !state.locked(it.track) } else emptyList()
                            val groupFloor = groupClips.minOfOrNull { -it.start } ?: -Float.MAX_VALUE
                            val groupCands = if (group) listOf(0f, state.playhead) + state.markers +
                                state.clips.filter { it.id !in state.multi && it.id !in state.removing }.flatMap { listOf(it.start, it.end) } +
                                state.captions.filter { it.id !in groupCaps }.flatMap { listOf(it.start, it.end) } else emptyList()
                            var groupD = 0f
                            var moved = false
                            var lastSnapC: Float? = null
                            while (true) {
                                val ev = capPending?.also { capPending = null } ?: awaitPointerEvent()
                                val p = ev.changes.filter { it.pressed }
                                if (p.isEmpty()) break
                                if (p.size >= 2 || ev.changes.any { it.isConsumed }) {
                                    capDrag = null; snapLine = null
                                    while (awaitPointerEvent().changes.any { it.pressed }) { }
                                    return@awaitEachGesture
                                }
                                val ch = p[0]
                                val dx = ch.position.x - start.x
                                if (!moved && abs(dx) > viewConfiguration.touchSlop) {
                                    moved = true
                                    if (cap != null && !group) { haptics.tick(); state.playing = false; state.selectedCaption = cap.id; state.selected = null; state.multi = emptySet(); state.multiCaptions = emptySet(); state.selectedTransition = null; state.selectedCut = null }
                                    if (group) { haptics.tick(); state.playing = false; for (gc in groupClips) anims[gc.id]?.let { ga -> scope.launch { ga.lift.animateTo(1f, Springs.lift()) } } }
                                }
                                if (!moved) continue
                                ch.consume()
                                if (cap == null) { setScroll(scroll0 - dx); continue }
                                if (group) {
                                    // the whole group: snap the start/end of the dragged caption, within the group's limits
                                    var d = dx / pps
                                    val len = cap.end - cap.start
                                    var snapped: Float? = null
                                    if (state.magnet) {
                                        val ns = cap.start + d
                                        val sa = groupCands.minByOrNull { abs(it - ns) }?.takeIf { abs(it - ns) * pps < snapPx }
                                        val sb = groupCands.minByOrNull { abs(it - ns - len) }?.takeIf { abs(it - ns - len) * pps < snapPx }
                                        if (sa != null && (sb == null || abs(sa - ns) <= abs(sb - ns - len))) { d = sa - cap.start; snapped = sa }
                                        else if (sb != null) { d = sb - len - cap.start; snapped = sb }
                                    }
                                    d = d.coerceIn(groupRange.start, groupRange.endInclusive).coerceAtLeast(groupFloor)
                                    if (snapped != lastSnapC) { if (snapped != null) haptics.tick(); lastSnapC = snapped; snapLine = snapped }
                                    groupD = d
                                    capGroupDt = groupCaps to d
                                    for (gc in groupClips) anims[gc.id]?.let { ga -> scope.launch { ga.start.animateTo(gc.start + d, Springs.follow()) } }
                                    if (!state.centerPlayhead) state.playhead = cap.start + d
                                    continue
                                }
                                val dt = dx / pps
                                var ns = cap.start
                                var ne = cap.end
                                var snapped: Float? = null
                                when (edge) {
                                    -1 -> { ns = cap.start + dt; snapOf(ns)?.let { ns = it; snapped = it }; ns = ns.coerceIn(lo, ne - minLen) }
                                    1 -> { ne = cap.end + dt; snapOf(ne)?.let { ne = it; snapped = it }; ne = ne.coerceIn(ns + minLen, hi) }
                                    else -> {
                                        val len = cap.end - cap.start
                                        ns = cap.start + dt
                                        val sa = snapOf(ns)
                                        val sb = snapOf(ns + len)
                                        if (sa != null && (sb == null || abs(sa - ns) <= abs(sb - ns - len))) { ns = sa; snapped = sa }
                                        else if (sb != null) { ns = sb - len; snapped = sb }
                                        ns = ns.coerceIn(lo, (hi - len).coerceAtLeast(lo))
                                        ne = ns + len
                                    }
                                }
                                if (snapped != lastSnapC) { if (snapped != null) haptics.tick(); lastSnapC = snapped; snapLine = snapped }
                                capDrag = Triple(cap.id, ns, ne)
                                // the picture follows the edge/start being dragged
                                if (!state.centerPlayhead) state.playhead = if (edge == 1) ne else ns
                            }
                            snapLine = null
                            if (group && moved) {
                                for (gc in groupClips) anims[gc.id]?.let { ga -> scope.launch { ga.lift.animateTo(0f, Springs.lift()) } }
                                if (abs(groupD) * pps < 0.5f) {
                                    capGroupDt = null
                                    for (gc in groupClips) anims[gc.id]?.let { ga -> scope.launch { ga.start.animateTo(gc.start, Springs.settle()) } }
                                    return@awaitEachGesture
                                }
                                haptics.confirm()
                                val pending = capGroupDt
                                state.moveGroup(groupD, insert = tool == Tool.RIPPLE)
                                // drop the preview state once the engine has replied; clips that didn't move (rejected) slide back
                                scope.launch {
                                    delay(1500)
                                    if (capGroupDt === pending) capGroupDt = null
                                    for (gc in groupClips) {
                                        val now = state.clips.firstOrNull { it.id == gc.id } ?: continue
                                        anims[gc.id]?.let { ga -> if (abs(ga.start.value - now.start) > 1e-3f && dragId == null) ga.start.animateTo(now.start, Springs.settle()) }
                                    }
                                }
                                return@awaitEachGesture
                            }
                            if (!moved) {
                                haptics.tick()
                                if (cap != null && state.groupSelected) {
                                    // a group is selected: tap adds or removes the caption
                                    state.toggleMultiCaption(cap.id)
                                    return@awaitEachGesture
                                }
                                if (cap != null) {
                                    state.selectedCaption = if (state.selectedCaption == cap.id) null else cap.id
                                    state.selected = null; state.multi = emptySet(); state.multiCaptions = emptySet(); state.selectedTransition = null; state.selectedCut = null
                                    if (state.selectedCaption != null && !state.centerPlayhead) state.playhead = cap.start
                                } else state.selectedCaption = null
                                return@awaitEachGesture
                            }
                            val d = capDrag
                            if (cap != null && d != null) {
                                val b = state.props
                                when (edge) {
                                    -1 -> b?.trimCaption(cap.id, true, d.second - cap.start)
                                    1 -> b?.trimCaption(cap.id, false, d.third - cap.end)
                                    else -> b?.moveCaption(cap.id, d.second - cap.start)
                                }
                                // drop the preview state once the engine has replied – or anyway after a while (less than a
                                // frame, or a move the engine didn't accept: the list then stays the same and doesn't change)
                                if (b == null) capDrag = null
                                else scope.launch { delay(1500); if (capDrag === d) capDrag = null }
                            }
                            return@awaitEachGesture
                        }
                        val orig: Clip? = when (hit) {
                            is Hit.Body -> state.clips.firstOrNull { it.id == hit.id }
                            is Hit.TrimL -> state.clips.firstOrNull { it.id == hit.id }
                            is Hit.TrimR -> state.clips.firstOrNull { it.id == hit.id }
                            else -> null
                        }
                        val a = orig?.let { anims[it.id] }
                        // linked clips (video ↔ audio) follow along while dragging
                        // several selected (lasso): the whole group follows along, tracks are kept
                        val groupDrag = orig != null && hit is Hit.Body && tool != Tool.RAZOR && orig.id in state.multi
                        val partners: List<Pair<Clip, ClipAnim>> = if (groupDrag) {
                            state.clips.filter { it.id in state.multi && it.id != orig.id }.mapNotNull { pc -> anims[pc.id]?.let { pc to it } }
                        } else orig?.let { o -> state.partnersOf(o).mapNotNull { pc -> anims[pc.id]?.let { pc to it } } }.orEmpty()
                        val groupMinStart = if (groupDrag) (partners.map { it.first.start } + orig.start).min() else 0f
                        // selected captions move with the group (within their limits)
                        val groupCaps = if (groupDrag) state.multiCaptions else emptySet()
                        val capRange = if (groupCaps.isNotEmpty()) state.captionGroupRange(groupCaps) else null
                        val candidates = if (groupDrag) {
                            val inGroup = state.multi
                            listOf(0f, state.playhead) + state.markers + state.clips.filter { it.id !in inGroup && it.id !in state.removing }.flatMap { listOf(it.start, it.end) }
                        } else state.snapCandidates(orig?.id)
                        val scroll0 = sx()
                        val scrollY0 = scrollY
                        var vertical = false
                        val centerCands = state.snapCandidates(null).filter { abs(it - state.playhead) > 0.001f }
                        val vt = VelocityTracker()
                        vt.addPosition(down.uptimeMillis, down.position)
                        var moved = false
                        var multi = false
                        var cancelled = false
                        var lastSnap: Float? = null
                        var lastTarget = orig?.start ?: 0f
                        var lastLen = orig?.length ?: 0f
                        var curTrack = orig?.track ?: 0
                        var lastSpan = 0f
                        var lastPos = start
                        var pinchA: androidx.compose.ui.input.pointer.PointerId? = null
                        // scrubbing the playhead (ruler, or the timeline under a locked playhead): slower the further down the finger goes
                        val scrubT = ScrubTiers(28.dp.toPx(), 44.dp.toPx())
                        scrubT.start(start.x, start.y, if (state.centerPlayhead) state.playhead.toDouble() else ((start.x + sx()) / pps).toDouble())
                        // clip edges and markers: a tick when the playhead passes one while scrubbing
                        val scrubEdges = state.snapCandidates(null).filter { abs(it - state.playhead) > 0.001f }.sorted()
                        var lastScrubPh = state.playhead
                        fun scrubTo(t: Float) {
                            val lo = min(lastScrubPh, t); val hi = max(lastScrubPh, t)
                            if (lastSnap == null && scrubEdges.any { it > lo && it <= hi }) haptics.tick()
                            lastScrubPh = t
                            state.playhead = t
                        }
                        var pinchB: androidx.compose.ui.input.pointer.PointerId? = null
                        var lastCentroid = Offset.Zero
                        val wasPlaying = state.playing

                        fun revert() {
                            if (orig != null && a != null && dragId == orig.id) {
                                for ((pc, pa) in partners) {
                                    scope.launch { pa.start.animateTo(pc.start, Springs.settle()) }
                                    scope.launch { pa.len.animateTo(pc.length, Springs.settle()) }
                                }
                                dragId = null
                                snapLine = null
                                scope.launch { a.start.animateTo(orig.start, Springs.settle()) }
                                scope.launch { a.len.animateTo(orig.length, Springs.settle()) }
                                scope.launch { a.track.animateTo(orig.track.toFloat(), Springs.track()) }
                                scope.launch { a.lift.animateTo(0f, Springs.lift()) }
                            }
                        }

                        fun applySnap(snap: Pair<Float, Float>?) {
                            val point = snap?.second
                            if (point != lastSnap) {
                                if (point != null) haptics.tick()
                                lastSnap = point
                                snapLine = point
                            }
                        }

                        // trim tool: slip (the clip's content) and roll (the edit point)
                        val slipping = tool == Tool.TRIM && hit is Hit.Body && orig != null
                        var slipD = 0f
                        val rollPair = (hit as? Hit.Roll)?.let { h -> state.clips.firstOrNull { it.id == h.left }?.let { l -> state.clips.firstOrNull { it.id == h.right }?.let { r -> l to r } } }
                        var rollD = 0f
                        val lockedHit = (orig != null && state.locked(orig.track)) || (rollPair != null && state.locked(rollPair.first.track))

                        fun beginDrag() {
                            if (orig == null || a == null) return
                            if (lockedHit) return
                            if (slipping) return
                            dragId = orig.id
                            if (!groupDrag) { state.selected = orig.id; state.multi = emptySet(); state.multiCaptions = emptySet() }
                            haptics.tick()
                            if (hit is Hit.Body) scope.launch { a.lift.animateTo(1f, Springs.lift()) }
                        }

                        // hold the finger still on an empty area → selection box
                        var pendingEv: androidx.compose.ui.input.pointer.PointerEvent? = null
                        var lasso = false
                        var razorDrag = false
                        var razorT = 0f
                        var razorFrom = 0
                        // a quick swipe down through a clip (select/ripple tool) cuts it where the finger went down
                        var knife = false
                        if (capLaneLasso) lasso = true
                        else if (hit is Hit.Body && (tool == Tool.SELECT || tool == Tool.RIPPLE) && !lockedHit) {
                            val r = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var out: androidx.compose.ui.input.pointer.PointerEvent
                                while (true) {
                                    out = awaitPointerEvent()
                                    val p = out.changes.filter { it.pressed }
                                    if (p.size != 1 || out.changes.any { it.isConsumed } || (p[0].position - start).getDistance() > viewConfiguration.touchSlop) break
                                }
                                out
                            }
                            if (r == null) {
                                // held still on the clip → it lifts (free move, also between tracks)
                                moved = true
                                state.playing = false
                                beginDrag()
                                haptics.heavy()
                            } else pendingEv = r
                        } else if (hit is Hit.Empty) {
                            val r = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var out: androidx.compose.ui.input.pointer.PointerEvent
                                while (true) {
                                    out = awaitPointerEvent()
                                    val p = out.changes.filter { it.pressed }
                                    if (p.size != 1 || out.changes.any { it.isConsumed } || (p[0].position - start).getDistance() > viewConfiguration.touchSlop) break
                                }
                                out
                            }
                            if (r == null) lasso = true else pendingEv = r
                        }
                        if (lasso) {
                            haptics.heavy()
                            state.playing = false
                            fun content(o: Offset) = Offset(o.x + sx(), o.y - rulerPx + scrollY)
                            val pts = arrayListOf(content(start))
                            lassoPts = pts.toList()
                            val rects = clipRects()
                            val add = state.groupSelected || state.selected != null || state.selectedCaption != null
                            // the caption lane in screen coordinates (it doesn't scroll vertically)
                            val capTop = rulerBasePx
                            val capBottom = rulerPx
                            var aborted = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val p = ev.changes.filter { it.pressed }
                                if (p.isEmpty()) break
                                if (p.size >= 2 || ev.changes.any { it.isConsumed }) { aborted = true; break }
                                val ch = p[0]
                                ch.consume()
                                // selection box: from where the finger was held to where it is now
                                val q = content(ch.position)
                                val o = pts[0]
                                val box = listOf(o, Offset(q.x, o.y), q, Offset(o.x, q.y))
                                lassoPts = box
                                val drawnBox = abs(q.x - o.x) > 2f && abs(q.y - o.y) > 2f
                                val hits = if (drawnBox) TimelineTools.lassoHits(box, rects) else emptySet()
                                // captions: the box (in screen height) reaches into the lane and covers part of the block
                                val sy0 = min(o.y, q.y) + rulerPx - scrollY
                                val sy1 = max(o.y, q.y) + rulerPx - scrollY
                                val cx0 = min(o.x, q.x)
                                val cx1 = max(o.x, q.x)
                                val capHits = if (drawnBox && capBottom > capTop && sy1 >= capTop && sy0 <= capBottom)
                                    state.captions.filter { it.end * pps >= cx0 && it.start * pps <= cx1 }.map { it.id }.toSet() else emptySet()
                                if (hits != lassoLive || capHits != lassoCapLive) haptics.tick()
                                lassoLive = hits
                                lassoCapLive = capHits
                            }
                            val hits = lassoLive
                            val capHits = lassoCapLive
                            lassoCapLive = emptySet()
                            val drawn = lassoPts?.let { b -> b.size >= 4 && (abs(b[2].x - b[0].x) > 2f || abs(b[2].y - b[0].y) > 2f) } == true
                            lassoPts = null
                            lassoLive = emptySet()
                            if (aborted) {
                                while (awaitPointerEvent().changes.any { it.pressed }) { }
                                return@awaitEachGesture
                            }
                            if (drawn) {
                                val any = hits.isNotEmpty() || capHits.isNotEmpty()
                                if (any) haptics.confirm()
                                // a lasso from an empty area adds to what was already selected only if new items were enclosed

                                if (any) state.lassoSelectAll(hits, capHits, add = add) else state.clearSelection()
                            }
                            return@awaitEachGesture
                        }

                        while (true) {
                            val ev = pendingEv?.also { pendingEv = null } ?: awaitPointerEvent()
                            if (ev.changes.any { it.isConsumed }) {
                                cancelled = true
                                transPreview = null
                                revert()
                                // wait until all fingers are lifted
                                while (awaitPointerEvent().changes.any { it.pressed }) { }
                                break
                            }
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {
                                // the same two fingers for the whole pinch (by id, not position in the list – a third finger
                                // being added or lifted otherwise caused a jump in the zoom)
                                var f0 = pressed.firstOrNull { it.id == pinchA }
                                var f1 = pressed.firstOrNull { it.id == pinchB }
                                val relock = f0 == null || f1 == null
                                if (relock) { f0 = pressed[0]; f1 = pressed[1]; pinchA = f0.id; pinchB = f1.id }
                                if (!multi || relock) {
                                    if (!multi) {
                                        multi = true
                                        transPreview = null
                                        revert()
                                    }
                                    lastSpan = max(minSpan, abs(f0.position.x - f1.position.x))
                                    lastCentroid = (f0.position + f1.position) / 2f
                                }
                                // horizontal distance with a floor: fingers on top of each other must not zoom ×30
                                val span = max(minSpan, abs(f0.position.x - f1.position.x))
                                val centroid = (f0.position + f1.position) / 2f
                                val zoom = span / lastSpan
                                // the time under the fingers follows the fingers (zoom + pan in one)
                                if (state.centerPlayhead) {
                                    // zoom around the playhead, panning moves the playhead
                                    pps = (pps * zoom).coerceIn(with(density) { 2.dp.toPx() }, with(density) { 400.dp.toPx() })
                                    state.playhead = (state.playhead - (centroid.x - lastCentroid.x) / pps).coerceAtLeast(0f)
                                } else {
                                    val anchorT = (lastCentroid.x + freeScroll) / pps
                                    pps = (pps * zoom).coerceIn(with(density) { 2.dp.toPx() }, with(density) { 400.dp.toPx() })
                                    freeScroll = (anchorT * pps - centroid.x).coerceAtLeast(0f)
                                }
                                lastSpan = span
                                lastCentroid = centroid
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            if (multi) continue

                            val ch = pressed.first()
                            vt.addPosition(ch.uptimeMillis, ch.position)
                            lastPos = ch.position
                            val d = ch.position - start
                            if (!moved && (abs(d.x) > viewConfiguration.touchSlop || abs(d.y) > viewConfiguration.touchSlop)) {
                                moved = true
                                vertical = hit is Hit.Empty && abs(d.y) > abs(d.x) * 1.2f
                                if (tool == Tool.RAZOR && (hit is Hit.Body || hit is Hit.Empty) && abs(d.y) > abs(d.x) * 1.2f) {
                                    // the razor is dragged up/down: the line shows across all tracks it passes
                                    razorDrag = true
                                    vertical = false
                                    razorT = ((start.x + sx()) / pps).coerceAtLeast(0f)
                                    if (abs(razorT - state.playhead) < snapPx / pps) razorT = state.playhead
                                    razorFrom = anyTrackAt(start.y - rulerPx + scrollY)
                                    razorLine = Triple(razorT, razorFrom, razorFrom)
                                    state.playing = false
                                    haptics.tick()
                                }
                                val quick = ch.uptimeMillis - down.uptimeMillis < KNIFE_MS
                                if (hit is Hit.Body && (tool == Tool.SELECT || tool == Tool.RIPPLE) && !lockedHit && quick && d.y > 0f && d.y > abs(d.x) * 1.5f) {
                                    knife = true
                                    razorDrag = true
                                    vertical = false
                                    razorT = snapFrame(((start.x + sx()) / pps).coerceAtLeast(0f))
                                    if (abs(razorT - state.playhead) < snapPx / pps) razorT = state.playhead
                                    razorFrom = anyTrackAt(start.y - rulerPx + scrollY)
                                    razorLine = Triple(razorT, razorFrom, razorFrom)
                                    state.playing = false
                                    haptics.tick()
                                }
                                if (knife) {
                                    // cutting, not moving
                                } else if (lockedHit && (hit is Hit.Body || hit is Hit.TrimL || hit is Hit.TrimR || hit is Hit.Roll)) {
                                    // locked track: nothing changes – the finger pans instead
                                    state.onNotice?.invoke(Str.t(R.string.timeline_track_locked))
                                    haptics.tick()
                                } else if (hit is Hit.Body || hit is Hit.TrimL || hit is Hit.TrimR) {
                                    if (tool == Tool.RAZOR && hit is Hit.Body) {
                                        // the razor doesn't drag clips – let it pan instead
                                    } else beginDrag()
                                }
                                if (hit is Hit.Ruler || (state.centerPlayhead && (hit is Hit.Empty || (hit is Hit.Body && tool == Tool.RAZOR)))) state.playing = false
                            }
                            if (!moved) continue
                            ch.consume()

                            val dt = d.x / pps
                            val thr = snapPx / pps
                            when {
                                slipping && !lockedHit -> {
                                    // drag right = earlier frames into the clip (like Premiere's slip)
                                    val want = -dt * orig.speed
                                    slipD = state.slipLimit(orig, want)
                                    if (DEBUG_GESTURES) println("slip move want=$want d=$slipD")
                                    if (orig.kind == ClipKind.VIDEO) state.trimView = TrimView(orig.item, (orig.srcIn + slipD).toDouble())
                                }
                                rollPair != null && !lockedHit -> {
                                    val (l, r) = rollPair
                                    var d = state.rollLimit(l, r, dt)
                                    val snap = if (state.magnet) state.nearestSnap(listOf(r.start + d), candidates.filter { abs(it - r.start) > 0.001f }, thr) else null
                                    if (snap != null) d = state.rollLimit(l, r, d + snap.first)
                                    applySnap(snap)
                                    rollD = d
                                    val la = anims[l.id]; val ra = anims[r.id]
                                    val spec = if (snap != null) Springs.snap() else Springs.follow()
                                    la?.let { x -> scope.launch { x.len.animateTo(l.length + d, spec) } }
                                    ra?.let { x -> scope.launch { x.start.animateTo(r.start + d, spec) }; scope.launch { x.len.animateTo(r.length - d, spec) } }
                                    loupe = ch.position
                                    if (r.kind == ClipKind.VIDEO) state.trimView = TrimView(r.item, (r.srcIn + d * r.speed).toDouble(),
                                        pair = l.takeIf { it.kind == ClipKind.VIDEO }?.let { TrimView(it.item, (it.srcIn + (it.length + d) * it.speed - FRAME).coerceAtLeast(it.srcIn).toDouble()) },
                                        pairLeft = true)
                                }
                                razorDrag -> {
                                    val cur = anyTrackAt(ch.position.y - rulerPx + scrollY)
                                    if (cur != razorLine?.third) {
                                        haptics.tick()
                                        razorLine = Triple(razorT, razorFrom, cur)
                                    }
                                }
                                hit is Hit.Body && dragId == orig?.id && orig != null && a != null -> {
                                    val floorT = if (groupDrag) orig.start - groupMinStart else 0f
                                    val raw = (orig.start + dt).coerceAtLeast(floorT)
                                    val snap = if (state.magnet) state.nearestSnap(listOf(raw, raw + orig.length), candidates, thr) else null
                                    var target = (raw + (snap?.first ?: 0f)).coerceAtLeast(floorT)
                                    if (capRange != null) {
                                        target = orig.start + (target - orig.start).coerceIn(capRange.start, capRange.endInclusive)
                                        capGroupDt = groupCaps to (target - orig.start)
                                    }
                                    applySnap(snap)
                                    lastTarget = target
                                    val spec = if (snap != null) Springs.snap() else Springs.follow()
                                    scope.launch { a.start.animateTo(target, spec) }
                                    for ((pc, pa) in partners) scope.launch { pa.start.animateTo((pc.start + (target - orig.start)).coerceAtLeast(0f), spec) }
                                    val nt = if (groupDrag) curTrack else trackAt(ch.position.y - rulerPx + scrollY, orig.kind.track)
                                    if (nt != curTrack) {
                                        curTrack = nt
                                        haptics.tick()
                                        scope.launch { a.track.animateTo(nt.toFloat(), Springs.track()) }
                                    }
                                    hoverTrack = if (groupDrag) -1 else curTrack
                                    // dragged up out of the timeline: released there, the clip is deleted (ripple)
                                    val out = ch.position.y < rulerPx - outPx
                                    if (out != deleteArmed) {
                                        deleteArmed = out
                                        if (out) haptics.heavy() else haptics.tick()
                                        scope.launch { a.doom.animateTo(if (out) 1f else 0f, Springs.lift()) }
                                        for ((_, pa) in partners) scope.launch { pa.doom.animateTo(if (out) 1f else 0f, Springs.lift()) }
                                    }
                                }
                                hit is Hit.TrimL && dragId == orig?.id && orig != null && a != null -> {
                                    var lo = max(0f, orig.start - orig.srcIn / orig.speed)
                                    if (tool != Tool.RIPPLE) {
                                        val prevEnd = state.clips.filter { it.track == orig.track && it.id != orig.id && it.end <= orig.start + 0.001f }.maxOfOrNull { it.end }
                                        if (prevEnd != null) lo = max(lo, prevEnd)
                                    }
                                    var s = (orig.start + dt).coerceIn(lo, orig.end - 0.5f)
                                    val snap = if (state.magnet) state.nearestSnap(listOf(s), candidates, thr) else null
                                    if (snap != null) s = (s + snap.first).coerceIn(lo, orig.end - 0.5f)
                                    applySnap(snap)
                                    lastTarget = s
                                    lastLen = orig.end - s
                                    loupe = ch.position
                                    // the incoming frame, beside the outgoing clip's last frame when a clip ends right here
                                    if (orig.kind == ClipKind.VIDEO) state.trimView = TrimView(orig.item, (orig.srcIn + (s - orig.start) * orig.speed).coerceAtLeast(0f).toDouble(),
                                        pair = state.clips.firstOrNull { it.track == orig.track && it.id != orig.id && it.kind == ClipKind.VIDEO && abs(it.end - orig.start) < 0.02f }
                                            ?.let { l -> TrimView(l.item, (l.srcIn + l.length * l.speed - FRAME).coerceAtLeast(l.srcIn).toDouble()) },
                                        pairLeft = true)
                                    val spec = if (snap != null) Springs.snap() else Springs.follow()
                                    // ripple: the clip stays put and only the start is shortened (the rest moves in) – as when released
                                    if (tool == Tool.RIPPLE) scope.launch { a.start.animateTo(orig.start, spec) }
                                    else scope.launch { a.start.animateTo(s, spec) }
                                    scope.launch { a.len.animateTo(orig.end - s, spec) }
                                    for ((pc, pa) in partners) if (abs(pc.start - orig.start) < 0.02f) {
                                        if (tool == Tool.RIPPLE) scope.launch { pa.len.animateTo(pc.length - (s - orig.start), spec) }
                                        else {
                                            scope.launch { pa.start.animateTo(s, spec) }
                                            scope.launch { pa.len.animateTo(pc.end - s, spec) }
                                        }
                                    }
                                }
                                hit is Hit.TrimR && dragId == orig?.id && orig != null && a != null -> {
                                    var hi = orig.start + (orig.srcLen - orig.srcIn) / orig.speed
                                    if (tool != Tool.RIPPLE) {
                                        val nextStart = state.clips.filter { it.track == orig.track && it.id != orig.id && it.start >= orig.end - 0.001f }.minOfOrNull { it.start }
                                        if (nextStart != null) hi = min(hi, nextStart)
                                    }
                                    var e = (orig.end + dt).coerceIn(orig.start + 0.5f, hi)
                                    val snap = if (state.magnet) state.nearestSnap(listOf(e), candidates, thr) else null
                                    if (snap != null) e = (e + snap.first).coerceIn(orig.start + 0.5f, hi)
                                    applySnap(snap)
                                    lastLen = e - orig.start
                                    loupe = ch.position
                                    // the outgoing frame, beside the next clip's first frame when one starts right here
                                    if (orig.kind == ClipKind.VIDEO) state.trimView = TrimView(orig.item, (orig.srcIn + (e - orig.start) * orig.speed - 0.04f).coerceAtLeast(orig.srcIn).toDouble(),
                                        pair = state.clips.firstOrNull { it.track == orig.track && it.id != orig.id && it.kind == ClipKind.VIDEO && abs(it.start - orig.end) < 0.02f }
                                            ?.let { r -> TrimView(r.item, r.srcIn.toDouble()) },
                                        pairLeft = false)
                                    val spec = if (snap != null) Springs.snap() else Springs.follow()
                                    scope.launch { a.len.animateTo(e - orig.start, spec) }
                                    for ((pc, pa) in partners) if (abs(pc.end - orig.end) < 0.02f) scope.launch { pa.len.animateTo(e - pc.start, spec) }
                                }
                                hit is Hit.TransEdge -> {
                                    val tr = state.transitions.firstOrNull { it.id == hit.id }
                                    if (tr != null) {
                                        val t = ((ch.position.x + sx()) / pps)
                                        loupe = ch.position
                                        val (s0, e0) = tr.resized(hit.left, snapFrame(t), FRAME)
                                        val prev = transPreview
                                        val len = e0 - s0
                                        // tick for every half second
                                        if (prev != null && (len * 2).toInt() != ((prev.end - prev.start) * 2).toInt()) haptics.tick()
                                        transPreview = tr.copy(start = s0, end = e0)
                                    }
                                }
                                hit is Hit.Fade -> {
                                    val c = state.clips.firstOrNull { it.id == hit.id }
                                    if (c != null) {
                                        val t = ((ch.position.x + sx()) / pps)
                                        var len = (if (hit.inEdge) t - c.start else c.end - t).coerceIn(0f, c.length * 0.9f)
                                        len = snapFrame(len)
                                        val neighbor = state.clips.firstOrNull { o ->
                                            o.id != c.id && o.track == c.track && o.kind.track == c.kind.track &&
                                                abs(if (hit.inEdge) o.end - c.start else o.start - c.end) < 0.02f
                                        }
                                        val prev = transPreview
                                        val next = when {
                                            neighbor != null -> {
                                                val cut = if (hit.inEdge) c.start else c.end
                                                UiTransition(-1, c.track, cut - len, cut + len, 0, cut)
                                            }
                                            hit.inEdge -> UiTransition(-1, c.track, c.start, c.start + len, -1)
                                            else -> UiTransition(-1, c.track, c.end - len, c.end, 1)
                                        }
                                        if (prev != null && (len * 2).toInt() != (((prev.end - prev.start) / (if (prev.anchor == 0) 2 else 1)) * 2).toInt()) haptics.tick()
                                        transPreview = next
                                    }
                                }
                                hit is Hit.Ruler && !state.centerPlayhead -> {
                                    val raw = scrubT.at(ch.position.x, ch.position.y, 1.0 / pps).toFloat().coerceAtLeast(0f)
                                    if (scrubT.changed) { scrubTier = scrubT.tier; haptics.tick() }
                                    // snapping only at full speed (slowed down = precise positioning)
                                    val snap = if (state.magnet && scrubT.tier == 0) state.nearestSnap(listOf(raw), candidates.filter { it != state.playhead }, thr) else null
                                    applySnap(snap)
                                    scrubTo(raw + (snap?.first ?: 0f))
                                }
                                hit is Hit.Empty && vertical -> {
                                    scrollY = clampY(scrollY0 - d.y)
                                }
                                else -> {
                                    if (state.centerPlayhead) {
                                        // drag the timeline under the playhead (slower further down); snap to clip edges at full speed
                                        val raw = scrubT.at(ch.position.x, ch.position.y, -1.0 / pps).toFloat().coerceAtLeast(0f)
                                        if (scrubT.changed) { scrubTier = scrubT.tier; haptics.tick() }
                                        val snap = if (state.magnet && scrubT.tier == 0) state.nearestSnap(listOf(raw), centerCands, thr) else null
                                        applySnap(snap)
                                        scrubTo((raw + (snap?.first ?: 0f)).coerceAtLeast(0f))
                                    } else setScroll(scroll0 - d.x)
                                }
                            }
                        }

                        if (DEBUG_GESTURES) println("TL end cancelled=$cancelled multi=$multi moved=$moved razor=$razorDrag knife=$knife line=$razorLine")
                        if (cancelled || multi) {
                            // cancelled or two fingers (zoom): clear trim frame, snap line and previews
                            razorLine = null
                            scrubTier = 0
                            loupe = null
                            hoverTrack = -1
                            if (deleteArmed) { deleteArmed = false; a?.let { x -> scope.launch { x.doom.animateTo(0f, Springs.lift()) } } }
                            snapLine = null
                            state.trimView = null
                            rollPair?.let { (l, r) ->
                                anims[l.id]?.let { x -> scope.launch { x.len.animateTo(l.length, Springs.settle()) } }
                                anims[r.id]?.let { x -> scope.launch { x.start.animateTo(r.start, Springs.settle()) }; scope.launch { x.len.animateTo(r.length, Springs.settle()) } }
                            }
                            return@awaitEachGesture
                        }
                        if (slipping && moved) {
                            loupe = null
                            state.trimView = null
                            if (DEBUG_GESTURES) println("slip release d=$slipD"); if (abs(slipD) > 1e-4f) { haptics.confirm(); state.slip(orig.id, slipD) }
                            return@awaitEachGesture
                        }
                        if (rollPair != null && moved) {
                            loupe = null
                            state.trimView = null
                            snapLine = null
                            val (l, r) = rollPair
                            if (abs(rollD) > 1e-4f && state.roll(l.id, r.id, rollD) != 0f) haptics.confirm()
                            else {
                                // no change: back
                                anims[l.id]?.let { x -> scope.launch { x.len.animateTo(l.length, Springs.settle()) } }
                                anims[r.id]?.let { x -> scope.launch { x.start.animateTo(r.start, Springs.settle()) }; scope.launch { x.len.animateTo(r.length, Springs.settle()) } }
                            }
                            return@awaitEachGesture
                        }
                        if (razorDrag) {
                            val line = razorLine
                            razorLine = null
                            if (line != null) {
                                if (knife && line.second == line.third && orig != null) {
                                    // one track: the same as the razor's tap (linked sound follows the engine's rules)
                                    if (line.first > orig.start + 0.04f && line.first < orig.end - 0.04f) { haptics.confirm(); state.split(orig.id, line.first) }
                                } else {
                                    val n = state.razorAcross(line.first, min(line.second, line.third)..max(line.second, line.third))
                                    if (n > 0) haptics.confirm()
                                }
                            }
                            return@awaitEachGesture
                        }

                        if (!moved) {
                            // tap at the boundary between two clips: the edit point is selected (where a transition can be added)
                            if (tool != Tool.RAZOR && state.props != null && !state.groupSelected &&
                                (hit is Hit.Body || hit is Hit.TrimL || hit is Hit.TrimR || hit is Hit.Roll || hit is Hit.Fade)) {
                                val y = start.y - rulerPx + scrollY
                                val cx = start.x + sx()
                                val near = state.cutPairs().mapNotNull { (l, r) ->
                                    val ty = yOf(l.track.toFloat())
                                    if (y < ty || y > ty + hOf(l.track.toFloat())) return@mapNotNull null
                                    // short clips: the zone takes at most a quarter of each (the rest selects the clip)
                                    val zone = minOf(cutTapPx, l.length * pps / 4f, r.length * pps / 4f)
                                    val d = abs(cx - r.start * pps)
                                    if (d < zone) Triple(l, r, d) else null
                                }.minByOrNull { it.third }
                                if (near != null) {
                                    val (l, r) = near
                                    haptics.tick()
                                    state.playing = false
                                    // if there's already a transition there: it gets selected
                                    val existing = state.transitions.firstOrNull { it.track == l.track && it.anchor == 0 && abs(it.cut - r.start) < 0.02f }
                                    if (existing != null) { state.clearSelection(); state.selectedTransition = existing.id }
                                    else state.selectCut(UiCut(l.track, r.start, l.id, r.id))
                                    return@awaitEachGesture
                                }
                            }
                            // Tap

                            when (hit) {
                                is Hit.Body -> {
                                    val c = orig ?: return@awaitEachGesture
                                    if (tool == Tool.RAZOR) {
                                        var t = (start.x + sx()) / pps
                                        if (abs(t - state.playhead) < snapPx / pps) t = state.playhead
                                        haptics.confirm()
                                        state.split(c.id, t)
                                    } else if (state.groupSelected) {
                                        // several selected: tap adds or removes the clip
                                        state.toggleMulti(c.id)
                                        haptics.tick()
                                    } else {
                                        // compound clip: the whole group is selected
                                        state.tapSelect(c.id)
                                        haptics.tick()
                                        a?.let { an -> scope.launch { an.pulse.snapTo(0.93f); an.pulse.animateTo(1f, Springs.pop()) } }
                                    }
                                }
                                is Hit.TrimL, is Hit.TrimR -> {
                                    // an edge of a clip that isn't selected: the tap selects the clip
                                    val id = (hit as? Hit.TrimL)?.id ?: (hit as Hit.TrimR).id
                                    if (state.groupSelected) { state.toggleMulti(id); haptics.tick() }
                                    else if (state.selected != id) { state.tapSelect(id); haptics.tick() }
                                }
                                is Hit.Fade, is Hit.Roll, is Hit.Cap, Hit.CapLane -> Unit
                                is Hit.TransBody, is Hit.TransEdge -> {
                                    val id = if (hit is Hit.TransBody) hit.id else (hit as Hit.TransEdge).id
                                    state.selected = null
                                    state.selectedTransition = if (state.selectedTransition == id) null else id
                                    haptics.tick()
                                }
                                Hit.Ruler -> {
                                    state.playing = false
                                    val target = ((start.x + sx()) / pps).coerceAtLeast(0f)
                                    scope.launch { animate(state.playhead, target, animationSpec = Springs.settle()) { v, _ -> state.playhead = v } }
                                }
                                Hit.Empty -> state.clearSelection()
                            }
                            return@awaitEachGesture
                        }

                        snapLine = null
                        loupe = null
                        hoverTrack = -1
                        state.trimView = null
                        // released above the tracks, or flung up hard: delete
                        val fling = vt.calculateVelocity()
                        val flungOut = hit is Hit.Body && dragId == orig?.id && fling.y < -FLING_OUT_DP * density.density && (lastPos.y + fling.y * 0.12f) < rulerPx - outPx
                        val dropDelete = (deleteArmed || flungOut) && hit is Hit.Body && orig != null && dragId == orig.id
                        deleteArmed = false
                        if (dropDelete && orig != null && a != null) {
                            dragId = null
                            haptics.heavy()
                            capGroupDt = null
                            scope.launch { a.doom.animateTo(0f, Springs.lift()) }
                            for ((_, pa) in partners) scope.launch { pa.doom.animateTo(0f, Springs.lift()) }
                            if (groupDrag) {
                                for ((pc, pa) in partners) { scope.launch { pa.start.animateTo(pc.start, Springs.settle()) }; scope.launch { pa.lift.animateTo(0f, Springs.lift()) } }
                                scope.launch { a.start.animateTo(orig.start, Springs.settle()) }
                                scope.launch { a.lift.animateTo(0f, Springs.lift()) }
                                state.removeGroup(true)
                            } else {
                                // (the clip fades out where it was dropped; linked partners go back until the engine removes them)
                                for ((pc, pa) in partners) scope.launch { pa.start.animateTo(pc.start, Springs.settle()) }
                                state.requestRemove(orig.id, true)
                            }
                            return@awaitEachGesture
                        }
                        when (hit) {
                            is Hit.Body -> {
                                if (orig != null && a != null && dragId == orig.id) {
                                    dragId = null
                                    scope.launch { a.lift.animateTo(0f, Springs.lift()) }
                                    if (groupDrag) {
                                        for ((_, pa) in partners) scope.launch { pa.lift.animateTo(0f, Springs.lift()) }
                                        if (lastTarget != orig.start) {
                                            haptics.confirm()
                                            state.moveGroup(lastTarget - orig.start, insert = tool == Tool.RIPPLE)
                                            val pending = capGroupDt
                                            if (pending != null) scope.launch { delay(1500); if (capGroupDt === pending) capGroupDt = null }
                                        } else capGroupDt = null
                                    } else if (lastTarget != orig.start || curTrack != orig.track) {
                                        haptics.confirm()
                                        state.moveClip(orig, lastTarget, curTrack, insert = tool == Tool.RIPPLE)
                                    }
                                }
                            }
                            // back where it started: no trim (and no undo step)
                            is Hit.TrimL, is Hit.TrimR -> if (orig != null && dragId == orig.id && abs(lastLen - orig.length) < 1e-4f && abs(lastTarget - orig.start) < 1e-4f) {
                                revert()
                            } else if (hit is Hit.TrimL && orig != null && dragId == orig.id) {
                                dragId = null
                                haptics.confirm()
                                if (tool == Tool.RIPPLE) {
                                    val updated = orig.copy(length = lastLen, srcIn = orig.srcIn + (orig.length - lastLen))
                                    state.trimCommit(updated, lastLen - orig.length)
                                } else {
                                    val updated = orig.copy(start = lastTarget, length = lastLen, srcIn = orig.srcIn + (lastTarget - orig.start))
                                    state.trimCommit(updated, 0f)
                                }
                            }
                            else if (orig != null && dragId == orig.id) {
                                dragId = null
                                haptics.confirm()
                                state.trimCommit(orig.copy(length = lastLen), if (tool == Tool.RIPPLE) lastLen - orig.length else 0f)
                            }
                            is Hit.TransEdge -> {
                                val p = transPreview
                                transPreview = null
                                if (p != null) {
                                    val len = p.end - p.start
                                    haptics.confirm()
                                    if (len < 0.15f) state.props?.removeTransition(hit.id) else state.props?.resizeTransition(hit.id, len)
                                }
                            }
                            is Hit.Fade -> {
                                val p = transPreview
                                transPreview = null
                                if (p != null && p.end - p.start >= FRAME) {
                                    haptics.confirm()
                                    state.props?.setTransition(hit.id, hit.inEdge, p.end - p.start)
                                }
                            }
                            Hit.Ruler -> if (wasPlaying && !state.centerPlayhead) state.playing = true
                            else -> Unit
                        }
                        val snappedInCenter = state.centerPlayhead && lastSnap != null
                        val slowScrub = scrubTier > 0
                        scrubTier = 0
                        if (!vertical && !snappedInCenter && !slowScrub && (hit is Hit.Empty || (hit is Hit.Body && tool == Tool.RAZOR) || (hit is Hit.Ruler && state.centerPlayhead))) {
                            val v = -vt.calculateVelocity().x
                            flingJob = scope.launch {
                                animateDecay(sx(), v, FloatExponentialDecaySpec(frictionMultiplier = 1.4f)) { value, _ ->
                                    setScroll(value)
                                }
                            }
                        }
                      } catch (e: kotlinx.coroutines.CancellationException) {
                        // the gesture was cancelled (tool changed, view disappeared): no temporary state may remain
                        abandonGesture()
                        throw e
                      }
                    }
                }
        ) {
            // Background, ruler, markers
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                // empty tracks as slim lanes (a darker strip)
                TRACKS.indices.forEach { i ->
                    if (heightsPx[i] < fullPx[i] - 0.5f) drawRect(K.Deep.copy(alpha = 0.7f), Offset(0f, rulerPx + topsPx[i] - scrollY), Size(w, heightsPx[i]))
                }
                // tracks
                TRACKS.forEachIndexed { i, _ ->
                    val y = rulerPx + topsPx[i] + heightsPx[i] - 1f - scrollY
                    drawLine(K.Bg, Offset(0f, y), Offset(w, y), strokeWidth = 2f)
                }
                drawRect(K.Surface, size = Size(w, rulerBasePx))
                if (rulerPx > rulerBasePx) drawRect(K.Deep, Offset(0f, rulerBasePx), Size(w, rulerPx - rulerBasePx))
                // before the start of the timeline (visible in locked mode)
                val zeroX = -sx()
                if (zeroX > 0f) drawRect(K.Deep, Offset(0f, rulerPx), Size(zeroX, size.height - rulerPx))
                // ticks
                val steps = TICK_STEPS
                val minor = steps.firstOrNull { it * pps >= 12.dp.toPx() } ?: 60f
                val major = steps.firstOrNull { it * pps >= 80.dp.toPx() } ?: 60f
                val t0 = floor(sx() / pps / minor) * minor
                var t = t0
                while (t * pps - sx() < w) {
                    if (t < -0.0001f) { t += minor; continue }
                    val x = t * pps - sx()
                    val isMajor = abs((t / major) - Math.round(t / major)) < 0.001f
                    drawLine(
                        if (isMajor) K.Outline else K.OutlineVar,
                        Offset(x, rulerBasePx - if (isMajor) 12f.dp.toPx() else 6.dp.toPx()),
                        Offset(x, rulerBasePx), strokeWidth = 1.5f
                    )
                    t += minor
                }
                // pre-render state (like Premiere's render bar): green = rendered (plays from a finished file), red = heavy
                // parts (layers, effects, transitions) computed in real time
                for (c in se.gendut.klipp.engine.RenderCache.states) {
                    if (!c.ready && !c.heavy) continue
                    val x0 = (c.a * pps).toFloat() - sx(); val x1 = (c.b * pps).toFloat() - sx()
                    if (x1 < 0f || x0 > w) continue
                    drawRect(if (c.ready) Color(0xFF4CAF7A) else Color(0xFFD9534F), Offset(x0, rulerBasePx - 3.dp.toPx()), Size(x1 - x0, 3.dp.toPx()))
                }
                // markers
                for ((i, m) in state.markers.withIndex()) {
                    val x = m * pps - sx()
                    val p = scratchPath.apply {
                        reset()
                        moveTo(x - 6.dp.toPx(), 2.dp.toPx()); lineTo(x + 6.dp.toPx(), 2.dp.toPx())
                        lineTo(x + 6.dp.toPx(), 9.dp.toPx()); lineTo(x, 14.dp.toPx()); lineTo(x - 6.dp.toPx(), 9.dp.toPx()); close()
                    }
                    drawPath(p, if (i == 0) K.Teal else K.Pink)
                }
            }
            // time labels
            RulerLabels(pps = { pps }, scroll = { sx() }, width = { laneWidth })

            // Clips
            Box(
                Modifier.fillMaxSize().padding(top = rulerH).clipToBounds()
            ) {
                // the selected clip's link group once (not a search through all clips for every clip)
                val selLink = if (state.linkedSelection) state.clips.firstOrNull { it.id == state.selected }?.link else null
                for (c in state.clips) key(c.id) {
                    // only read during composition; registering and consuming popFrom happen once the composition has gone through
                    val a = remember(c.id) {
                        anims[c.id] ?: run {
                            val pop = state.popFrom[c.id]
                            if (pop != null) ClipAnim(pop.first, c.length, pop.second, 0f)
                            else ClipAnim(c.start, c.length, c.track.toFloat(), if (initialized) 0f else 1f)
                        }
                    }
                    DisposableEffect(a) {
                        if (anims[c.id] !== a) anims[c.id] = a
                        state.popFrom.remove(c.id)
                        onDispose { }
                    }
                    // in when the clip appears – and back if a removal didn't go through (the clip is still there)
                    val beingRemoved = c.id in state.removing
                    val wasRemoved = remember { booleanArrayOf(false) }
                    LaunchedEffect(beingRemoved) {
                        if (beingRemoved) { wasRemoved[0] = true; return@LaunchedEffect }
                        if (wasRemoved[0]) {
                            // the engine has received the removal; if the clip is still there a while later, it didn't go through
                            delay(1500)
                            wasRemoved[0] = false
                            launch { a.lift.animateTo(0f, Springs.lift()) }
                        }
                        if (a.appear.value < 1f) a.appear.animateTo(1f, Springs.pop())
                    }
                    LaunchedEffect(c) {
                        if (dragId == c.id) return@LaunchedEffect
                        val d = state.rippleDelay.remove(c.id) ?: 0
                        if (d > 0) delay(d.toLong())
                        launch { a.start.animateTo(c.start, Springs.settle()) }
                        launch { a.len.animateTo(c.length, Springs.settle()) }
                        launch { a.track.animateTo(c.track.toFloat(), Springs.track()) }
                    }
                    ClipView(
                        clip = c,
                        anim = a,
                        uri = if ((c.kind == ClipKind.VIDEO || c.kind == ClipKind.AUDIO || c.kind == ClipKind.MUSIC) && c.item > 0) state.uriOfItem?.invoke(c.item) else null,
                        selected = state.selected == c.id || c.id in state.multi || c.id in lassoLive || (selLink != null && c.link == selLink),
                        pps = { pps },
                        scroll = { sx() },
                        yOf = { yOf(it) - scrollY },
                        hOf = ::hOf,
                    )
                }
            }

            // Transitions (bow tie over the clip edge) and captions (bars under the ruler)
            Canvas(Modifier.fillMaxSize()) {
                // hidden/muted tracks are dimmed, locked ones get diagonal stripes
                state.trackStates.forEachIndexed { i, ts ->
                    if (i > TRACKS.lastIndex) return@forEachIndexed
                    val top = rulerPx + topsPx[i] - scrollY
                    val h = heightsPx[i]
                    if (top + h < rulerPx || top > size.height) return@forEachIndexed
                    if (!ts.enabled || ts.muted) drawRect(K.Lane.copy(alpha = 0.6f), Offset(0f, maxOf(top, rulerPx)), Size(size.width, h - maxOf(0f, rulerPx - top)))
                    if (ts.locked) {
                        val step = 10.dp.toPx()
                        clipRect(0f, maxOf(top, rulerPx), size.width, top + h) {
                            var x = -h
                            while (x < size.width) {
                                drawLine(K.OnSurface.copy(alpha = 0.12f), Offset(x, top + h), Offset(x + h, top), 1.5f)
                                x += step
                            }
                        }
                    }
                }
                val preview = transPreview
                val shown = state.transitions.filter { it.id != preview?.id } + listOfNotNull(preview)
                for (tr in shown) {
                    val x0 = tr.start * pps - sx()
                    val x1 = tr.end * pps - sx()
                    if (x1 < 0f || x0 > size.width) continue
                    val top = rulerPx + yOf(tr.track.toFloat()) - scrollY + 3.dp.toPx()
                    val h = hOf(tr.track.toFloat()) - 6.dp.toPx()
                    if (top + h < rulerPx) continue
                    val sel = tr.id == state.selectedTransition || tr === preview
                    // cross dissolve = bow tie; fade in/out = wedge showing which way the picture comes
                    val shape = scratchPath2.apply {
                        reset()
                        when (tr.anchor) {
                            -1 -> { moveTo(x0, top + h); lineTo(x1, top); lineTo(x1, top + h); close() }
                            1 -> { moveTo(x0, top); lineTo(x1, top + h); lineTo(x0, top + h); close() }
                            else -> { moveTo(x0, top); lineTo(x1, top + h); lineTo(x1, top); lineTo(x0, top + h); close() }
                        }
                    }
                    drawRoundRect(Color(0xCC1D2026), Offset(x0, top), Size(x1 - x0, h), CornerRadius(6.dp.toPx()))
                    drawPath(shape, (if (sel) K.Primary else K.PrimarySoft).copy(alpha = if (sel) 0.75f else 0.55f))
                    drawRoundRect(if (sel) K.Primary else K.PrimarySoft, Offset(x0, top), Size(x1 - x0, h), CornerRadius(6.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke((if (sel) 2.5f else 1.5f).dp.toPx()))
                    // draggable edges
                    val gw = 3.dp.toPx()
                    if (tr.anchor != -1) drawRoundRect(K.OnSurface.copy(alpha = 0.8f), Offset(x0 + 2.dp.toPx(), top + h * 0.3f), Size(gw, h * 0.4f), CornerRadius(gw / 2))
                    if (tr.anchor != 1) drawRoundRect(K.OnSurface.copy(alpha = 0.8f), Offset(x1 - 2.dp.toPx() - gw, top + h * 0.3f), Size(gw, h * 0.4f), CornerRadius(gw / 2))
                }
                // selected edit point: a bright marker over the boundary
                state.selectedCut?.let { cut ->
                    val x = cut.t * pps - sx()
                    val top = rulerPx + yOf(cut.track.toFloat()) - scrollY
                    val h = hOf(cut.track.toFloat())
                    if (x > -20f && x < size.width + 20f && top + h > rulerPx) {
                        val w = 10.dp.toPx()
                        drawRoundRect(Color(0xCC1D2026), Offset(x - w / 2 - 2.dp.toPx(), top + 1.dp.toPx()), Size(w + 4.dp.toPx(), h - 2.dp.toPx()), CornerRadius(6.dp.toPx()))
                        drawRoundRect(K.Primary, Offset(x - w / 2, top + 3.dp.toPx()), Size(w, h - 6.dp.toPx()), CornerRadius(5.dp.toPx()))
                        drawLine(K.OnPrimary, Offset(x, top + h * 0.3f), Offset(x, top + h * 0.7f), 2.dp.toPx())
                    }
                }
                // fade handles on the selected video clip
                val selClip = state.selected?.let { id -> state.clips.firstOrNull { it.id == id } }
                val sa = selClip?.let { anims[it.id] }
                if (state.props != null && sa != null && tool != Tool.RAZOR) {
                    val x0 = sa.start.value * pps - sx()
                    val x1 = (sa.start.value + sa.len.value) * pps - sx()
                    val top = rulerPx + yOf(sa.track.value) - scrollY
                    if (x1 - x0 > fadeZonePx * 2 && top + 8.dp.toPx() > rulerPx) {
                        val r = 7.dp.toPx()
                        for (cxp in listOf(x0 + r + 3.dp.toPx(), x1 - r - 3.dp.toPx())) {
                            drawCircle(Color(0xFF1D2026), r + 2.dp.toPx(), Offset(cxp, top + r + 3.dp.toPx()))
                            drawCircle(K.PrimarySoft, r, Offset(cxp, top + r + 3.dp.toPx()))
                        }
                    }
                }
                // track volume automation (yellow line with keyframes, as in Premiere): +6 dB at the top, −60 dB at the bottom
                state.trackVolKf.forEachIndexed { k, kfs ->
                    if (kfs.isEmpty()) return@forEachIndexed
                    val ti = 3 + k
                    val top = rulerPx + yOf(ti.toFloat()) - scrollY
                    val h = hOf(ti.toFloat())
                    if (top + h < rulerPx || top > size.height) return@forEachIndexed
                    fun yDb(db: Float): Float {
                        // fader-like scale: 0 dB at 30 % from the top

                        val f = if (db >= 0f) 0.3f - 0.3f * (db / 6f).coerceAtMost(1f) else 0.3f + 0.7f * (1f - Math.pow(10.0, db / 40.0).toFloat())
                        return top + h * 0.12f + (h * 0.76f) * f.coerceIn(0f, 1f)
                    }
                    val col = Color(0xFFE2C15B)
                    var prev: Offset? = Offset(0f, yDb(kfs.first().second))
                    for (kf in kfs) {
                        val p = Offset(kf.first * pps - sx(), yDb(kf.second))
                        prev?.let { drawLine(col, Offset(maxOf(it.x, 0f), it.y), p, 1.5.dp.toPx()) }
                        prev = p
                    }
                    prev?.let { drawLine(col, it, Offset(size.width, it.y), 1.5.dp.toPx()) }
                    for (kf in kfs) {
                        val c = Offset(kf.first * pps - sx(), yDb(kf.second))
                        if (c.x < -10f || c.x > size.width + 10f) continue
                        val r = 4.dp.toPx()
                        val d = scratchPath.apply { reset(); moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }
                        drawPath(d, col)
                    }
                }
                // caption lane: blocks with the text, selected = bright border and handles at the edges
                if (rulerPx > rulerBasePx) {
                    val top = rulerBasePx + 3.dp.toPx()
                    val h = rulerPx - rulerBasePx - 6.dp.toPx()
                    val paint = capPaint.apply { textSize = 11.sp.toPx() }
                    for (c0 in state.captions) {
                        val gd = capGroupDt?.takeIf { c0.id in it.first }?.second
                        val dragged = capDrag?.takeIf { it.first == c0.id } ?: gd?.let { Triple(c0.id, c0.start + it, c0.end + it) }
                        val cs = dragged?.second ?: c0.start
                        val ce = dragged?.third ?: c0.end
                        val x0 = cs * pps - sx()
                        val x1 = ce * pps - sx()
                        if (x1 < 0f || x0 > size.width) continue
                        val sel = state.selectedCaption == c0.id || c0.id in state.multiCaptions || c0.id in lassoCapLive
                        val wpx = (x1 - x0 - 1.dp.toPx()).coerceAtLeast(2f)
                        // hidden caption track: gray
                        val capCol = if (c0.hidden) K.Outline else K.Teal
                        drawRoundRect(if (sel) capCol.copy(alpha = 0.55f) else capCol.copy(alpha = if (c0.hidden) 0.18f else 0.28f), Offset(x0, top), Size(wpx, h), CornerRadius(6.dp.toPx()))
                        if (sel) {
                            drawRoundRect(capCol, Offset(x0, top), Size(wpx, h), CornerRadius(6.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                            // the (trim) handles only on a single selected caption
                            if (state.selectedCaption == c0.id) for (hx in listOf(x0 + 3.dp.toPx(), x0 + wpx - 3.dp.toPx())) drawRoundRect(capCol, Offset(hx - 2.dp.toPx(), top + h * 0.2f), Size(4.dp.toPx(), h * 0.6f), CornerRadius(2.dp.toPx()))
                        }
                        if (wpx > 18.dp.toPx()) drawContext.canvas.nativeCanvas.let { cv ->
                            cv.save()
                            cv.clipRect(x0 + 6.dp.toPx(), top, x0 + wpx - 6.dp.toPx(), top + h)
                            paint.alpha = if (c0.hidden) 110 else 255
                            cv.drawText(c0.text.replace('\n', ' '), x0 + 8.dp.toPx(), top + h / 2 + paint.textSize * 0.36f, paint)
                            cv.restore()
                        }
                    }
                }
            }

            // Snap line + playhead (+ lasso and razor line)
            Canvas(Modifier.fillMaxSize()) {
                lassoPts?.let { pts ->
                    if (pts.size > 1) {
                        val path = Path().apply {
                            moveTo(pts[0].x - sx(), pts[0].y + rulerPx - scrollY)
                            for (i in 1 until pts.size) lineTo(pts[i].x - sx(), pts[i].y + rulerPx - scrollY)
                            close()
                        }
                        drawPath(path, K.Primary.copy(alpha = 0.14f))
                        drawPath(path, K.PrimarySoft, style = androidx.compose.ui.graphics.drawscope.Stroke(
                            2.dp.toPx(), pathEffect = lassoDash,
                        ))
                    }
                }
                razorLine?.let { (t, a0, b0) ->
                    val lo = min(a0, b0)
                    val hi = max(a0, b0)
                    val x = t * pps - sx()
                    val top = rulerPx + yOf(lo.toFloat()) - scrollY
                    val bottom = rulerPx + yOf(hi.toFloat()) + hOf(hi.toFloat()) - scrollY
                    val red = Color(0xFFFF6B6B)
                    drawRect(red.copy(alpha = 0.18f), Offset(x - 5.dp.toPx(), top), Size(10.dp.toPx(), bottom - top))
                    drawRect(red, Offset(x - 1.dp.toPx(), top), Size(2.dp.toPx(), bottom - top))
                    // mark where the cuts will land
                    for (c in TimelineTools.razorCuts(state.clips.filter { it.id !in state.removing }, t, lo..hi)) {
                        val cy = rulerPx + yOf(c.track.toFloat()) + hOf(c.track.toFloat()) / 2f - scrollY
                        drawCircle(Color(0xFF1D2026), 7.dp.toPx(), Offset(x, cy))
                        drawCircle(red, 5.dp.toPx(), Offset(x, cy))
                    }
                }
                snapLine?.let { s ->
                    val x = s * pps - sx()
                    val g = snapGlow.value
                    drawRect(K.Primary.copy(alpha = 0.18f * g), Offset(x - 6.dp.toPx() * g, 0f), Size(12.dp.toPx() * g, size.height))
                    drawRect(K.PrimarySoft.copy(alpha = min(1f, g)), Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                }
                val px = state.playhead * pps - sx()
                drawRect(K.Primary, Offset(px - 1.dp.toPx(), rulerBasePx - 4.dp.toPx()), Size(2.dp.toPx(), size.height))
                drawRoundRect(
                    K.Primary, Offset(px - 7.dp.toPx(), 4.dp.toPx()), Size(14.dp.toPx(), 18.dp.toPx()),
                    cornerRadius = CornerRadius(7.dp.toPx())
                )
                val my = maxScrollY()
                if (my > 1f) {
                    val viewH = size.height - rulerPx
                    val barH = (viewH * viewH / (viewH + my)).coerceAtLeast(24.dp.toPx())
                    val barY = rulerPx + (viewH - barH) * (scrollY / my)
                    drawRoundRect(K.Outline.copy(alpha = 0.7f), Offset(size.width - 5.dp.toPx(), barY), Size(3.dp.toPx(), barH), CornerRadius(1.5.dp.toPx()))
                }
            }
            ScrubSpeedBadge(scrubTier, Modifier.align(Alignment.TopCenter).padding(top = rulerH + 6.dp))
            androidx.compose.animation.AnimatedVisibility(
                deleteArmed, Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                enter = androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(0.5f, 600f), initialScale = 0.7f) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.scaleOut(targetScale = 0.7f) + androidx.compose.animation.fadeOut(),
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(K.Error).padding(horizontal = 12.dp, vertical = 5.dp).testTag("releaseToDelete"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(Ic.Delete, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.timeline_release_to_delete), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RulerLabels(pps: () -> Float, scroll: () -> Float, width: () -> Float) {
    val density = LocalDensity.current
    val majorPx = with(density) { 80.dp.toPx() }
    val padX = with(density) { 4.dp.toPx() }
    val padY = with(density) { 3.dp.roundToPx() }
    // which labels exist changes only when a new one comes into view (or the zoom changes step); where they are
    // is read only when they're placed – during playback and scrolling they move without being recreated
    val major by remember { derivedStateOf { RULER_STEPS.firstOrNull { it * pps() >= majorPx } ?: 60f } }
    val range by remember {
        derivedStateOf {
            val p = pps(); val s = scroll(); val m = major
            val first = floor(s / p / m).toInt().coerceAtLeast(0)
            val last = ((s + width()) / p / m).toInt() + 1
            first..maxOf(first, last)
        }
    }
    Box(Modifier.fillMaxWidth().height(28.dp)) {
        for (k in range) key(k, major) {
            val t = k * major
            Text(
                formatTc(t).substring(3, 8),
                color = K.Outline,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.layout { m, c ->
                    val pl = m.measure(c)
                    layout(pl.width, pl.height) { pl.place((t * pps() - scroll() + padX).roundToInt(), padY) }
                }
            )
        }
    }
}

private val RULER_STEPS = floatArrayOf(1f, 2f, 5f, 10f, 15f, 30f, 60f)
private val TICK_STEPS = floatArrayOf(0.04f, 0.2f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f)

@Composable
private fun ClipView(
    clip: Clip,
    anim: ClipAnim,
    uri: android.net.Uri?,
    selected: Boolean,
    pps: () -> Float,
    scroll: () -> Float,
    yOf: (Float) -> Float,
    hOf: (Float) -> Float,
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(10.dp)
    val inset = with(density) { 3.dp.toPx() }
    Box(
        Modifier
            .zIndex(if (anim.lift.value > 0.01f) 10f else if (selected) 5f else 0f)
            .layout { m, _ ->
                val x = anim.start.value * pps() - scroll()
                val w = max(1f, anim.len.value * pps())
                val y = yOf(anim.track.value) + inset
                val h = max(1f, hOf(anim.track.value) - inset * 2)
                val p = m.measure(Constraints.fixed(w.roundToInt(), h.roundToInt()))
                layout(0, 0) { p.place(x.roundToInt(), y.roundToInt()) }
            }
            .graphicsLayer {
                val l = anim.lift.value
                val ap = anim.appear.value
                val s = (1f + 0.06f * l) * anim.pulse.value
                scaleX = s * (0.85f + 0.15f * ap)
                scaleY = s * (0.4f + 0.6f * ap)
                alpha = ap.coerceIn(0f, 1f)
                shadowElevation = 18.dp.toPx() * l.coerceAtLeast(0f)
                this.shape = shape
                this.clip = true
                // disabled clip: the grayscale is drawn in its own layer (saturation is removed only from the clip)
                compositingStrategy = if (clip.enabled) androidx.compose.ui.graphics.CompositingStrategy.Auto else androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }
            .then(if (selected) Modifier.border(2.dp, K.Primary, shape) else Modifier)
            .then(if (clip.enabled) Modifier else Modifier.testTag("clipOff:${clip.id}").drawWithContent {
                drawContent()
                drawRect(Color(0xFF808080), blendMode = androidx.compose.ui.graphics.BlendMode.Saturation)
                drawRect(Color.Black.copy(alpha = 0.45f))
            })
            .background(clip.kind.bg)
            // dragged out of the timeline: red (released now, it is deleted)
            .drawWithContent { drawContent(); val d = anim.doom.value; if (d > 0.01f) drawRect(Color(0xFFE5484D).copy(alpha = 0.6f * d)) }
    ) {
        if (clip.kind == ClipKind.AUDIO || clip.kind == ClipKind.MUSIC) {
            Waveform(clip, uri, Modifier.fillMaxSize())
        } else if (uri != null) Filmstrip(clip, uri, pps, Modifier.fillMaxSize())
        Text(
            (if (clip.enabled) "" else stringResource(R.string.timeline_clip_off_prefix)) + (if (clip.hold) "❄ " else "") + (if (clip.reverse) "◀◀ " else "") + (if (clip.group != null) "⧉ " else "") + (if (clip.kind == ClipKind.TITLE) "T  ${clip.name}" else clip.name) +
                if (!clip.hold && kotlin.math.abs(clip.speed - 1f) > 0.001f) "  ${(clip.speed * 100).roundToInt()}%" else "",
            color = clip.kind.fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(start = if (selected) 14.dp else 8.dp, top = 4.dp, end = 8.dp)
        )
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterStart).width(10.dp).fillMaxHeight()
                    .background(K.Primary, RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp)),
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(2.dp, 14.dp).background(K.OnPrimary)) }
            Box(
                Modifier.align(Alignment.CenterEnd).width(10.dp).fillMaxHeight()
                    .background(K.Primary, RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp)),
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(2.dp, 14.dp).background(K.OnPrimary)) }
        }
    }
}

/**
 * The waveform from the file's real audio (peak values, see Peaks), for the clip's part of the source. Without a file
 * (prototype mode) or while it decodes: a calm placeholder.
 */
@Composable
private fun Waveform(clip: Clip, uri: android.net.Uri?, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val peaks by androidx.compose.runtime.produceState(uri?.let { se.gendut.klipp.engine.Peaks.cached(it) }, uri) {
        if (uri != null && value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { se.gendut.klipp.engine.Peaks.load(ctx, uri) }
    }
    Canvas(modifier.testTag(if (peaks != null) "wave:${clip.id}" else "wavePending:${clip.id}")) {
        val mid = size.height * 0.6f
        val maxH = size.height * 0.36f
        val col = clip.kind.fg.copy(alpha = 0.55f)
        val p = peaks
        if (p == null) {
            drawLine(clip.kind.fg.copy(alpha = 0.25f), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
            return@Canvas
        }
        // one bar per 2 dp; each bar = the highest peak in its time interval in the source
        val step = 2.dp.toPx()
        val bars = (size.width / step).toInt().coerceAtLeast(1)
        val secPerBar = clip.length / bars * clip.speed
        for (i in 0 until bars) {
            val t0 = clip.srcIn + i * secPerBar
            val a = (t0 * se.gendut.klipp.engine.Peaks.RATE).toInt()
            val b = ((t0 + secPerBar) * se.gendut.klipp.engine.Peaks.RATE).toInt().coerceAtLeast(a + 1)
            var m = 0f
            for (k in a until b) if (k in p.indices && p[k] > m) m = p[k]
            if (m <= 0.002f) continue
            // dB scale (−48 … 0 dBFS) so speech shows even when it's quiet
            val v = ((20f * kotlin.math.log10(m) + 48f) / 48f).coerceIn(0.03f, 1f)
            val x = i * step + step / 2
            drawLine(col, Offset(x, mid - maxH * v), Offset(x, mid + maxH * v * 0.55f), strokeWidth = step * 0.7f)
        }
    }
}

/** Filmstrip: frames from the clip's source, one per ~64 dp, at most 8 decodes per clip. */
@Composable
private fun Filmstrip(clip: Clip, uri: android.net.Uri, pps: () -> Float, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val tileW = with(density) { 64.dp.toPx() }
    // the tile count is read in derivedStateOf: the clip recomposes only when the count changes, not per pinch frame
    val n by remember(clip.length, tileW) { androidx.compose.runtime.derivedStateOf { (clip.length * pps() / tileW).toInt().coerceIn(1, 8) } }
    // quantize to whole seconds so the cache is reused when zooming; source time = timeline time × speed
    val times = remember(n, clip.srcIn, clip.length, clip.speed, clip.hold, clip.reverse) {
        // freeze frame: the same frame in all tiles; reverse: the end of the source first
        (0 until n).map { i ->
            val f = if (clip.hold) 0f else (if (clip.reverse) (n - i - 0.5f) / n else (i + 0.5f) / n)
            kotlin.math.floor(clip.srcIn + clip.length * clip.speed * f).toDouble()
        }
    }
    // loaded frames per source time: a changed tile count loads only the new ones, old ones show meanwhile
    val frames = remember(uri) { androidx.compose.runtime.mutableStateMapOf<Double, androidx.compose.ui.graphics.ImageBitmap>() }
    LaunchedEffect(uri, times) {
        for (t in times) if (t !in frames) Thumbs.load(ctx, uri, t)?.let { frames[t] = it }
        if (frames.size > 24) frames.keys.retainAll(times.toSet())
    }
    Canvas(modifier.graphicsLayer { alpha = 0.55f }) {
        val w = size.width / times.size
        times.forEachIndexed { i, t ->
            val img = frames[t] ?: frames.keys.minByOrNull { abs(it - t) }?.let { frames[it] }
            if (img != null) {
                val scale = maxOf(w / img.width, size.height / img.height)
                val sw = (w / scale).toInt().coerceAtMost(img.width)
                val sh = (size.height / scale).toInt().coerceAtMost(img.height)
                drawImage(
                    img,
                    srcOffset = androidx.compose.ui.unit.IntOffset((img.width - sw) / 2, (img.height - sh) / 2),
                    srcSize = androidx.compose.ui.unit.IntSize(sw, sh),
                    dstOffset = androidx.compose.ui.unit.IntOffset((i * w).roundToInt(), 0),
                    dstSize = androidx.compose.ui.unit.IntSize(w.roundToInt() + 1, size.height.roundToInt()),
                )
            }
        }
    }
}

/** Small button in the track header: [off] = off state (red). */
@Composable
private fun HeaderToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, off: Boolean, tag: String, onClick: () -> Unit) {
    // the touch area is the button itself: Compose otherwise enlarges small buttons to 48 dp, and then the button
    // takes the touches on the track name (target track, hold for the menu)
    val vc = androidx.compose.ui.platform.LocalViewConfiguration.current
    val tight = remember(vc) {
        object : androidx.compose.ui.platform.ViewConfiguration by vc {
            override val minimumTouchTargetSize = androidx.compose.ui.unit.DpSize(28.dp, 28.dp)
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalViewConfiguration provides tight) {
        HeaderToggleBox(icon, label, off, tag, onClick)
    }
}

@Composable
private fun HeaderToggleBox(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, off: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
            .background(if (off) K.Error.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, label, tint = if (off) K.Error else K.Outline, modifier = Modifier.size(16.dp))
    }
}

/** Track states (hold the track header). Video tracks: lock, show/hide, sync lock. Audio tracks: lock, mute, solo, sync lock. */
@Composable
private fun TrackMenu(open: Boolean, onDismiss: () -> Unit, t: TrackDef, ts: TrackState, set: (String, Boolean) -> Unit) {
    androidx.compose.material3.DropdownMenu(open, onDismiss, containerColor = K.SurfaceHighest, modifier = Modifier.testTag("trackMenu:${t.name}")) {
        @Composable
        fun item(label: String, on: Boolean, tag: String, click: () -> Unit) = androidx.compose.material3.DropdownMenuItem(
            text = { Text(label, color = K.OnSurface, fontSize = 14.sp) },
            trailingIcon = { androidx.compose.material3.Switch(on, null) },
            onClick = click,
            modifier = Modifier.testTag("trackFlag:${t.name}:$tag"),
        )
        Text(t.name, color = K.OnSurfaceVar, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        item(stringResource(R.string.timeline_lock_track), ts.locked, "locked") { set("locked", !ts.locked) }
        if (t.kind == TrackKind.VIDEO) item(stringResource(R.string.timeline_show_track), ts.enabled, "enabled") { set("enabled", !ts.enabled) }
        else {
            item(stringResource(R.string.timeline_mute), ts.muted, "muted") { set("muted", !ts.muted) }
            item(stringResource(R.string.timeline_solo), ts.solo, "solo") { set("solo", !ts.solo) }
        }
        item(stringResource(R.string.timeline_sync_lock), ts.syncLock
, "syncLock") { set("syncLock", !ts.syncLock) }
    }
}
