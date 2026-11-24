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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.flow.first
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

/**
 * SELECT move, RIPPLE ripple, RAZOR razor, TRIM trim (slip on the clip, roll on the edit point); POSITION (the magnetic
 * timeline's, in place of Ripple there: its trims ripple anyway) moves storyline clips over what is there, leaving gap clips.
 */
enum class Tool { SELECT, RIPPLE, RAZOR, TRIM, POSITION }

/** The tool moves and trims clips (as opposed to cutting, slipping or rolling). */
val Tool.moves: Boolean get() = this == Tool.SELECT || this == Tool.RIPPLE || this == Tool.POSITION

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
    /** A button pressed: the key click (a tick is too faint on many phones to be felt under a finger lifting off). */
    fun press() { if (enabled) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
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
    /**
     * The clip's start edge is being trimmed (or was, until the engine's reply): what is cut off is at the start, so the
     * waveform and filmstrip keep their material where it is while [len] changes (see liveSourceSpan).
     */
    var leftEdge by mutableStateOf(false)
    /** The track (id) the clip was last shown on: the same track on a new row (rows shifted) snaps there, no slide. */
    var rowId: Long? = null
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
    /** A keyframe on an audio clip's volume line (volume lines on): drag = time and level, double tap = delete. */
    data class VolKey(val id: Long, val mt: Long) : Hit
    /** An audio clip's volume line: drag = the level (between the keyframes around the finger), tap = a keyframe. */
    data class VolLine(val id: Long) : Hit
    data object Ruler : Hit
    data object Empty : Hit
    /** An edge of the selected gap clip (magnetic timeline): drag = its length, what follows ripples. */
    data class GapEdge(val gap: MGap, val left: Boolean) : Hit
}

@Composable
fun Timeline(state: EditorState, tool: Tool, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    // (simple mode: the clips' and tracks' badges – what was made in advanced mode)
    val gsNow = LocalGradeState.current
    var trackBadgeOpen by remember { mutableStateOf<Pair<Int, Badge>?>(null) }
    trackBadgeOpen?.let { (i, b) ->
        val act = remember(state, gsNow) { BadgeActions(state, gsNow) }
        val ts = state.trackStates.getOrNull(i) ?: TrackState()
        val audio = state.tracks.getOrNull(i)?.name?.drop(1)?.toIntOrNull()?.minus(1) ?: 0
        BadgeDialog(b,
            onToggle = if (b.canToggle) ({ act.toggleTrack(audio, ts); trackBadgeOpen = null }) else null,
            onRemove = { act.removeTrack(audio, ts); trackBadgeOpen = null },
            onOpen = { trackBadgeOpen = null; act.openTrack(audio) },
            onDismiss = { trackBadgeOpen = null })
    }

    // room for the track name and a button (eye/speaker) right in the header
    val headerW = 68.dp
    // the caption lane is fixed under the ruler (as in other editing programs) when the sequence has a caption track
    val rulerBaseH = 28.dp
    val capH = if (state.showCaptionLane) 30.dp else 0.dp
    val rulerH = rulerBaseH + capH
    val rulerPx = with(density) { rulerH.toPx() }
    val rulerBasePx = with(density) { rulerBaseH.toPx() }
    // reused drawing objects (not new ones every frame during playback and scrolling)
    val scratchPath = remember { Path() }
    val scratchPath2 = remember { Path() }
    val lassoDash = remember(density) { with(density) { androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())) } }
    val gapDash = remember(density) { with(density) { androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) } }
    /** Text in the caption lane (one object, not a new one on every draw). */
    val capPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(0xD8, 0xF5, 0xEC) } }
    /** Caption being dragged right now: (id, start, end) – shown until the engine's reply arrives. */
    var capDrag by remember { mutableStateOf<Triple<Long, Float, Float>?>(null) }
    /** Group move: the selected captions shifted by this much (s) – shown until the engine's reply arrives. */
    var capGroupDt by remember { mutableStateOf<Pair<Set<Long>, Float>?>(null) }
    LaunchedEffect(state.captions) { capDrag = null; capGroupDt = null }
    // the rows follow the sequence's tracks (read live: gestures and drawing always see the current rows)
    fun rows(): List<TrackDef> = state.tracks
    fun fullPxOf(i: Int): Float = with(density) { (rows().getOrNull(i)?.heightDp ?: 44).dp.toPx() }
    val slimPx = with(density) { SLIM_TRACK_DP.dp.toPx() }
    // track heights, animated, per track (by id: a track added on top moves the rows, each track keeps its height): an
    // empty track (other than V1/A1 and the target tracks) shrinks to a slim lane and opens while a clip is dragged over it
    val trackH = remember(density) { HashMap<Long, Animatable<Float, androidx.compose.animation.core.AnimationVector1D>>() }
    fun hAnim(i: Int): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
        val id = rows().getOrNull(i)?.id ?: Long.MIN_VALUE
        return trackH.getOrPut(id) { Animatable(fullPxOf(i)) }
    }
    /** The track a dragged clip is over right now (a slim lane opens under it). */
    var hoverTrack by remember { mutableIntStateOf(-1) }
    /** The track (id) whose deletion is being confirmed. */
    var deleting by remember { mutableStateOf<Long?>(null) }
    deleting?.let { id ->
        val row = rows().indexOfFirst { it.id == id }
        if (row < 0) deleting = null
        else DeleteTrackDialog(rows()[row].name, { deleting = null }) { deleting = null; haptics.confirm(); state.deleteTrack(row) }
    }
    for (i in rows().indices) key(rows()[i].id) {
        val full = i == rows().v1Row || i == rows().a1Row || i == state.targetVideo || i == state.targetAudio || i == hoverTrack ||
            state.clips.any { it.track == i && it.id !in state.removing }
        // opened with a double tap: taller (an audio track has room for its volume line to be dragged; a video track the same)
        val tall = state.volOn(i) || state.isOpen(i)
        val target = if (!full) slimPx else if (tall) with(density) { OPEN_TRACK_DP.dp.toPx() } else fullPxOf(i)
        val anim = hAnim(i)
        LaunchedEffect(anim, target) { anim.animateTo(target, Springs.settle()) }
    }
    // read at use (in gestures and drawing), so they always follow the animated heights
    val heightsPx: List<Float> = remember(trackH) { object : AbstractList<Float>() { override val size get() = rows().size; override fun get(index: Int) = hAnim(index).value } }
    val topsPx: List<Float> = remember(trackH) {
        object : AbstractList<Float>() {
            override val size get() = rows().size
            override fun get(index: Int): Float { var a = 0f; for (k in 0 until index) a += hAnim(k).value; return a }
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

    // zoom and scroll are kept in the editor's state (the timeline leaves the screen, they stay)
    val zoom = remember(state) { state.timelineZoom.also { if (it.floatValue <= 0f) it.floatValue = with(density) { 16.dp.toPx() } } }
    var pps by zoom // pixels per second
    var freeScroll by state.timelineScroll
    var laneWidth by remember { mutableFloatStateOf(1f) }
    var laneHeight by remember { mutableFloatStateOf(1f) }
    // vertical scrolling (to reach the audio tracks when the source panel takes up space)
    var scrollY by remember { mutableFloatStateOf(0f) }
    // a video track is added on top: everything below moves down a row – the view follows, so the add buttons (under the
    // last track) stay where the finger is instead of sliding under the dock
    var addedOnTop by remember { mutableStateOf(false) }
    // room under the last row: the add-track buttons (36 dp) and the floating dock / undo pill (~84 dp) – without it the
    // +V / +A buttons stayed under the dock and a tap there hit Undo
    val bottomPadPx = with(density) { 150.dp.toPx() }
    fun maxScrollY() = (topsPx[rows().lastIndex] + heightsPx[rows().lastIndex] + bottomPadPx - (laneHeight - rulerPx)).coerceAtLeast(0f)
    fun clampY(v: Float) = v.coerceIn(0f, maxScrollY())
    LaunchedEffect(rows().size) {
        if (addedOnTop) { addedOnTop = false; scrollY = clampY(scrollY + (heightsPx.getOrNull(0) ?: 0f)) }
    }
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
    /** A finger is on the lanes (the timeline doesn't go after the playhead under it). */
    val touching = remember { booleanArrayOf(false) }

    val anims = remember { mutableStateMapOf<Long, ClipAnim>() }
    // the magnetic timeline: the model of the timeline as it is (null when off), its gap clips and connections
    val magNow by remember(state) { derivedStateOf { if (state.magnetic) state.mag() else null } }
    val magGaps by remember(state) { derivedStateOf { magNow?.gaps().orEmpty() } }
    val magLinks by remember(state) { derivedStateOf { magNow?.connections().orEmpty() } }
    /** A storyline clip is dragged or trimmed (magnetic): the pill that keeps its connected clips in place shows. */
    var magDragging by remember { mutableStateOf(false) }
    /** The running drag's preview, made again when the pill is pressed. */
    val magRepreview = remember { arrayOfNulls<() -> Unit>(1) }
    LaunchedEffect(state.keepConnected) { magRepreview[0]?.invoke() }
    // a selected gap clip that is gone (an edit moved it) is let go
    LaunchedEffect(magGaps) { state.selectedGap?.let { g -> if (magGaps.none { abs(it.start - g.start) < 1e-3f && abs(it.end - g.end) < 1e-3f }) state.selectedGap = null } }
    var initialized by remember { mutableStateOf(false) }
    /** A volume line being dragged (or just released): the clip and its level as shown until the engine's reply arrives. */
    var volEdit by remember { mutableStateOf<Pair<Long, ClipLevel>?>(null) }
    LaunchedEffect(state.clipLevels) { volEdit = null }
    /** The level next to the finger while a volume line is dragged: (screen position, dB). */
    var volTip by remember { mutableStateOf<Pair<Offset, Float>?>(null) }
    /** The last tap on a volume keyframe (clip, media time, time) – a second one soon after deletes it. */
    val volTap = remember { longArrayOf(0L, 0L, 0L) }
    /** The last tap on an audio track's lane (track, time) – a second one soon after opens or closes its volume lines. */
    val trackTap = remember { longArrayOf(-1L, 0L) }
    val clipInsetPx = with(density) { 3.dp.toPx() }
    val volGrabPx = with(density) { 22.dp.toPx() }
    val volLinePx = with(density) { 18.dp.toPx() }
    /** A drag this long covers the whole level range (the line is short; a finger needs room for small steps). */
    val volRangePx = with(density) { 150.dp.toPx() }

    fun yOf(tf: Float): Float {
        val last = rows().lastIndex
        if (tf <= 0f) return topsPx[0] + tf * heightsPx[0]
        if (tf >= last) return topsPx[last] + (tf - last) * heightsPx[last]
        val i = floor(tf).toInt()
        val f = tf - i
        return topsPx[i] + (topsPx[i + 1] - topsPx[i]) * f
    }
    fun hOf(tf: Float): Float {
        val i = floor(tf.coerceIn(0f, rows().lastIndex.toFloat())).toInt()
        val j = min(i + 1, rows().lastIndex)
        val f = tf.coerceIn(0f, rows().lastIndex.toFloat()) - i
        return heightsPx[i] + (heightsPx[j] - heightsPx[i]) * f
    }
    fun trackAt(y: Float, kind: TrackKind): Int {
        val range = if (kind == TrackKind.VIDEO) rows().videoRows else rows().audioRows
        var best = range.first
        var bestD = Float.MAX_VALUE
        for (i in range) {
            val mid = topsPx[i] + heightsPx[i] / 2f
            val d = abs(mid - y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** An audio clip's volume line band in content coordinates (top, bottom), as ClipView draws it. */
    fun volBand(a: ClipAnim): Pair<Float, Float> {
        val top = yOf(a.track.value) + clipInsetPx
        val h = hOf(a.track.value) - clipInsetPx * 2
        val b = volBandOf(h, density.density)
        return top + b.first to top + b.second
    }
    /** The volume line or one of its keyframes under (cx, y) (content coordinates), or null. */
    fun volHit(cx: Float, y: Float, list: List<Clip>): Hit? {
        for (c in list.asReversed()) {
            if (c.kind.track != TrackKind.AUDIO || state.locked(c.track) || !state.volOn(c.track)) continue
            val a = anims[c.id] ?: continue
            val x0 = a.start.value * pps
            val x1 = (a.start.value + a.len.value) * pps
            val (bt, bb) = volBand(a)
            if (cx < x0 || cx > x1 || y < bt - volLinePx || y > bb + volLinePx) continue
            val lv = state.clipLevels[c.id] ?: ClipLevel(0f)
            fun xOfT(t: Float) = (x0 + (t - c.start) / c.length.coerceAtLeast(0.001f) * (x1 - x0)).coerceIn(x0, x1)
            fun yOfDb(db: Float) = bb - ClipLevel.pos(db) * (bb - bt)
            val k = lv.kf.minByOrNull { kotlin.math.hypot(xOfT(it.t) - cx, yOfDb(it.v.firstOrNull()?.toFloat() ?: 0f) - y) }
            if (k != null && kotlin.math.hypot(xOfT(k.t) - cx, yOfDb(k.v.firstOrNull()?.toFloat() ?: 0f) - y) < volGrabPx) return Hit.VolKey(c.id, k.mt)
            val t = c.start + (cx - x0) / (x1 - x0).coerceAtLeast(1f) * c.length
            if (abs(yOfDb(lv.at(t)) - y) < volLinePx) return Hit.VolLine(c.id)
        }
        return null
    }

    /** The track (any kind) at height [y] in content coordinates. */
    fun anyTrackAt(y: Float): Int {
        for (i in rows().indices) if (y < topsPx[i] + heightsPx[i]) return i
        return rows().lastIndex
    }
    fun clipRects(): Map<Long, androidx.compose.ui.geometry.Rect> = state.clips.filter { it.id !in state.removing }.mapNotNull { c ->
        val a = anims[c.id] ?: return@mapNotNull null
        val ty = yOf(a.track.value)
        c.id to androidx.compose.ui.geometry.Rect(a.start.value * pps, ty, (a.start.value + a.len.value) * pps, ty + hOf(a.track.value))
    }.toMap()

    /**
     * The magnetic preview: every clip but [exclude] (those follow the finger) slides to where [plan] puts it – the
     * neighbors make room, connected clips go with their storyline clips, what the edit removes fades; no plan = back
     * to where the clips are.
     */
    fun magPreview(plan: MPlan?, exclude: Set<Long>) {
        val rws = rows()
        for (c in state.clips) {
            if (c.id in exclude || c.id in state.removing) continue
            val an = anims[c.id] ?: continue
            val f = plan?.clip(c.id)
            val st = f?.start ?: c.start
            val ln = f?.len ?: c.length
            // (a track the edit adds lies past the rows: above the top one, below the last)
            val tr = f?.let { rws.rowOf(it.video, it.level).toFloat() } ?: c.track.toFloat()
            val dm = if (plan != null && c.id in plan.removed) 0.6f else 0f
            if (abs(an.start.targetValue - st) > 1e-4f) scope.launch { an.start.animateTo(st, Springs.follow()) }
            if (abs(an.len.targetValue - ln) > 1e-4f) scope.launch { an.len.animateTo(ln, Springs.follow()) }
            if (abs(an.track.targetValue - tr) > 1e-4f) scope.launch { an.track.animateTo(tr, Springs.track()) }
            if (abs(an.doom.targetValue - dm) > 1e-4f) scope.launch { an.doom.animateTo(dm, Springs.lift()) }
        }
    }
    /** After a magnetic edit: what the engine leaves where it was slides back (moved clips follow their new place on their own). */
    fun magSettleLater() {
        scope.launch {
            delay(1500)
            if (dragId == null) magPreview(null, emptySet())
        }
    }

    /** Clear everything temporary from a gesture and return the clips to the model's state (cancelled gesture). */
    fun abandonGesture() {
        dragId = null; snapLine = null; scrubTier = 0; loupe = null; deleteArmed = false; hoverTrack = -1; lassoPts = null; lassoLive = emptySet(); lassoCapLive = emptySet(); razorLine = null; transPreview = null; capDrag = null; capGroupDt = null
        state.trimView = null
        magDragging = false; magRepreview[0] = null; state.keepConnected = false
        for (c in state.clips) anims[c.id]?.let { a ->
            scope.launch { a.start.animateTo(c.start, Springs.settle()) }
            scope.launch { a.len.animateTo(c.length, Springs.settle()) }
            scope.launch { a.track.animateTo(c.track.toFloat(), Springs.track()) }
            scope.launch { a.lift.animateTo(0f, Springs.lift()) }
            scope.launch { a.doom.animateTo(0f, Springs.lift()) }
        }
    }

    /** The gap clip under a touch at [pos] (screen coordinates in the lanes), on the storyline's row; null = none or not magnetic. */
    fun gapAtTouch(pos: Offset): MGap? {
        if (magNow == null) return null
        val v1 = rows().v1Row
        val y = pos.y - rulerPx + scrollY
        val ty = yOf(v1.toFloat())
        if (y < ty || y > ty + hOf(v1.toFloat())) return null
        val t = (pos.x + sx()) / pps
        return magGaps.firstOrNull { t >= it.start && t < it.end }
    }

    // Glow on the snap line
    LaunchedEffect(snapLine) {
        if (snapLine != null) {
            snapGlow.snapTo(1.6f)
            snapGlow.animateTo(1f, Springs.pop())
        } else snapGlow.animateTo(0f, tween(160))
    }

    // Follow the playhead. During playback the timeline glides on when the playhead nears the right edge. Paused, a
    // jump that takes the playhead out of view (an edit point, a frame step, a typed time, the bar under the picture)
    // brings the timeline to it. The glide is its own job: a touch on the timeline stops it (otherwise it overwrote
    // the finger's scrolling), and a fling is stopped when playback starts.
    LaunchedEffect(Unit) {
        // (the lanes have a width once they are laid out)
        snapshotFlow { laneWidth }.first { it > 1f }
        var arrived = true
        var glideTo = freeScroll
        snapshotFlow { state.playhead }.collect { ph ->
            val first = arrived
            arrived = false
            // back on screen (from another page, the full-screen viewer): only a playhead that has moved meanwhile
            // (the very first time the timeline is shown, it starts where it is)
            val moved = !state.timelineSeen.isNaN() && ph != state.timelineSeen
            state.timelineSeen = ph
            if (!moved || state.centerPlayhead) return@collect
            // (while a glide runs, where it is going is what counts)
            val x = ph * pps - if (followJob?.isActive == true) glideTo else freeScroll
            val out = x < 0f || x > laneWidth - edgePx
            when {
                first -> if (out) freeScroll = (ph * pps - laneWidth * 0.25f).coerceAtLeast(0f)
                state.playing -> if (followJob?.isActive != true && x > laneWidth * 0.82f) {
                    glideTo = ph * pps - laneWidth * 0.25f
                    followJob = scope.launch { animate(freeScroll, glideTo, animationSpec = Springs.settle()) { v, _ -> freeScroll = v } }
                }
                out && !touching[0] -> {
                    // more of the timeline in the direction the playhead went
                    glideTo = (ph * pps - laneWidth * if (x < 0f) 0.75f else 0.25f).coerceAtLeast(0f)
                    followJob?.cancel()
                    followJob = scope.launch { animate(freeScroll, glideTo, animationSpec = Springs.settle()) { v, _ -> freeScroll = v } }
                }
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

    Box(modifier) {
    Row(Modifier.fillMaxSize().background(K.Lane)) {
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
                val rowH = size.height / rows().size
                for (c in state.clips) {
                    if (c.id in state.removing) continue
                    drawRect(c.kind.fg.copy(alpha = 0.7f), Offset(x(c.start), c.track * rowH + rowH * 0.2f), Size((x(c.end) - x(c.start)).coerceAtLeast(1f), rowH * 0.6f))
                }
                val v0 = x(sx() / pps); val v1 = x((sx() + laneWidth) / pps)
                drawRoundRect(K.Mark, Offset(v0, 0f), Size((v1 - v0).coerceAtLeast(3f), size.height), CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                drawRect(K.Mark, Offset(x(state.playhead) - 0.75.dp.toPx(), 0f), Size(1.5.dp.toPx(), size.height))
            }
            if (state.showCaptionLane) Row(Modifier.height(capH).fillMaxWidth().padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TXT", color = if (state.captionsOn) K.Teal else K.Outline, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // show/hide the captions (like the eye on a video track)
                HeaderToggle(if (state.captionsOn) Ic.Eye else Ic.EyeOff, if (state.captionsOn) stringResource(R.string.timeline_hide_captions) else stringResource(R.string.timeline_show_captions), !state.captionsOn, "capToggle") {
                    haptics.press(); state.onCaptionsVisible?.invoke(!state.captionsOn)
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
            rows().forEachIndexed { i, t -> key(t.id) {
                val ts = state.trackStates.getOrNull(i) ?: TrackState()
                var menu by remember { mutableStateOf(false) }
                val targeted = i == state.targetVideo || i == state.targetAudio
                val lamp by androidx.compose.animation.animateColorAsState(if (targeted) K.Mark else K.OutlineVar, label = "lamp$i")
                val lampH by androidx.compose.animation.core.animateDpAsState(if (targeted) 24.dp else 16.dp, androidx.compose.animation.core.spring(0.35f, 500f), label = "lh$i")
                val slim = heightsPx[i] < fullPxOf(i) * 0.7f
                Row(
                    Modifier.height(with(density) { heightsPx[i].toDp() }).fillMaxWidth().clipToBounds()
                        .testTag("trackHeader:${t.name}")
                        // tap = target track, hold = track states (lock, hide/mute, solo, sync lock, add, delete); a double tap
                        // opens the track (audio: for the clips' volume lines; video: as tall) or closes it
                        // (simple mode: no track menu – a track's lock, solo and sync lock still apply and show)
                        .then(if (t.kind == TrackKind.AUDIO) Modifier.combinedClickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                            onLongClick = if (state.simple) null else ({ haptics.heavy(); menu = true }),
                            onDoubleClick = { haptics.confirm(); state.toggleOpen(i) },
                        ) {
                            haptics.tick()
                            val cb = state.onTargetTrack
                            if (cb != null) cb(i) else state.targetAudio = i
                        } else Modifier.headerTaps(
                            // a video track is targeted at once (as before); the second tap of a double tap opens it
                            onTap = {
                                haptics.tick()
                                val cb = state.onTargetTrack
                                if (cb != null) cb(i) else state.targetVideo = i
                            },
                            onDoubleTap = { haptics.confirm(); state.toggleOpen(i) },
                            onLongPress = { if (!state.simple) { haptics.heavy(); menu = true } },
                        ))
                        .padding(start = 8.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackMenu(menu, { menu = false }, t, ts, state.tracks.canDelete(i, state.clips.filter { it.id !in state.removing }, state.transitions),
                        canAdd = if (t.kind == TrackKind.VIDEO) state.tracks.canAddVideo else state.tracks.canAddAudio,
                        onAdd = { menu = false; haptics.confirm(); state.addTrack(t.kind) }, onDelete = { menu = false; deleting = t.id },
                    ) { flag, on -> haptics.tick(); state.setTrackFlag(i, flag, on) }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ts.locked) androidx.compose.material3.Icon(Ic.Lock, stringResource(R.string.timeline_locked), tint = K.Mark, modifier = Modifier.size(11.dp))
                            if (ts.solo) Text("S", color = K.Teal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            // the magnetic timeline: V1 is the storyline (what closes up and makes room)
                            if (state.magnetic && t.kind == TrackKind.VIDEO && t.name == "V1") androidx.compose.material3.Icon(Ic.Storyline,
                                stringResource(R.string.magnetic_storyline_badge), tint = K.PrimarySoft, modifier = Modifier.size(11.dp).testTag("storylineBadge"))
                            // simple mode: the track's own effects (made in advanced mode) – a tap offers their choices
                            if (state.simple) trackBadge(ts)?.let { b ->
                                BadgeMark(listOf(b), Modifier.clickable(onClickLabel = stringResource(b.kind.label)) { haptics.tick(); trackBadgeOpen = i to b }.testTag("trackBadge:${t.name}"))
                            }
                        }
                        // the audio track's level while it plays
                        if (t.kind == TrackKind.AUDIO) TrackMeter(t.name.drop(1).toInt() - 1, state.playing, Modifier.padding(top = 2.dp).size(22.dp, 3.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    // hide the video track / mute the audio track directly (hold the header for lock, solo and sync lock)
                    if (t.kind == TrackKind.VIDEO) HeaderToggle(if (ts.enabled) Ic.Eye else Ic.EyeOff, if (ts.enabled) stringResource(R.string.timeline_hide_track) else stringResource(R.string.timeline_show_track), !ts.enabled, "trackToggle:${t.name}") {
                        haptics.press(); state.setTrackFlag(i, "enabled", !ts.enabled)
                    } else HeaderToggle(if (ts.muted) Ic.SpeakerOff else Ic.Speaker, if (ts.muted) stringResource(R.string.timeline_unmute) else stringResource(R.string.timeline_mute_track), ts.muted, "trackToggle:${t.name}") {
                        haptics.press(); state.setTrackFlag(i, "muted", !ts.muted)
                    }
                }
            } }
            // under the last track: add a video track (on top) or an audio track (at the bottom), up to the limits
            // (simple mode: the storyline and its connected clips find their tracks themselves)
            if (!state.simple) AddTrackRow(state.tracks.canAddVideo, state.tracks.canAddAudio) { kind -> haptics.confirm(); if (kind == TrackKind.VIDEO) addedOnTop = true; state.addTrack(kind) }
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
                .pointerInput(tool, state.showCaptionLane) {
                    awaitEachGesture {
                      try {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        touching[0] = true
                        flingJob?.cancel()
                        followJob?.cancel()
                        val start = down.position
                        val hit: Hit = run {
                            val y = start.y - rulerPx + scrollY
                            val cx = start.x + sx()
                            if (start.y >= rulerBasePx && start.y < rulerPx) {
                                // several caption tracks: a row each – only the row under the finger counts
                                val capRows = captionRows(state)
                                val row = if (capRows.size < 2) -1 else (((start.y - rulerBasePx) / (rulerPx - rulerBasePx)) * capRows.size).toInt().coerceIn(0, capRows.size - 1)
                                val inRow = { c: UiCaption -> row < 0 || capRows.indexOf(c.track) == row }
                                val sc = state.selectedCaption
                                state.captions.firstOrNull { it.id == sc && inRow(it) }?.let { c ->
                                    val x0 = c.start * pps
                                    val x1 = c.end * pps
                                    // short blocks: the handles take at most a quarter each, the middle moves
                                    val hz = min(handlePx, (x1 - x0) / 4f)
                                    if (cx >= x0 - hz && cx < x0 + hz) return@run Hit.Cap(c.id, -1)
                                    if (cx > x1 - hz && cx <= x1 + hz) return@run Hit.Cap(c.id, 1)
                                }
                                val c = state.captions.firstOrNull { inRow(it) && cx >= it.start * pps && cx <= it.end * pps } ?: return@run Hit.CapLane
                                return@run Hit.Cap(c.id, 0)
                            }
                            if (y < 0) return@run Hit.Ruler
                            val sel = state.selected
                            val list = state.clips.filter { it.id !in state.removing }
                            if (state.anyVolOn && tool != Tool.RAZOR && state.props != null) volHit(cx, y, list)?.let { return@run it }
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
                            // trim tool: the edit point between two adjacent clips = roll. The nearest one on the track under
                            // the finger: with short clips several are within reach, and the first found was not always it
                            if (tool == Tool.TRIM) {
                                val near = state.cutPairs()
                                    .filter { (l, _) -> val ty = yOf(l.track.toFloat()); y >= ty && y <= ty + hOf(l.track.toFloat()) }
                                    .minByOrNull { (_, r) -> abs(cx - r.start * pps) }
                                if (near != null && abs(cx - near.second.start * pps) < handlePx) return@run Hit.Roll(near.first.id, near.second.id)
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
                            if (tool.moves) for (c in list.asReversed()) {
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
                            // the selected gap clip's edges (inside it: the clips around it keep theirs)
                            state.selectedGap?.takeIf { magNow != null && tool != Tool.RAZOR }?.let { g ->
                                val v1 = rows().v1Row
                                val ty = yOf(v1.toFloat())
                                if (y >= ty && y <= ty + hOf(v1.toFloat())) {
                                    val x0 = g.start * pps
                                    val x1 = g.end * pps
                                    val zone = maxOf(handlePx, edgePx)
                                    if (cx > x1 - zone && cx <= x1 + 4f) return@run Hit.GapEdge(g, false)
                                    if (cx >= x0 - 4f && cx < x0 + zone && g.start > 0f) return@run Hit.GapEdge(g, true)
                                }
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
                                    -1 -> { ns = cap.start + dt; snapOf(ns)?.let { ns = it; snapped = it }; ns = ns.coerceInSafe(lo, ne - minLen) }
                                    1 -> { ne = cap.end + dt; snapOf(ne)?.let { ne = it; snapped = it }; ne = ne.coerceInSafe(ns + minLen, hi) }
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
                        // the volume line: drag a keyframe (time and level) or the line (the level between the keyframes around
                        // the finger), tap the line = a keyframe there, double tap a keyframe = delete it
                        if (hit is Hit.VolKey || hit is Hit.VolLine) {
                            val id = (hit as? Hit.VolKey)?.id ?: (hit as Hit.VolLine).id
                            val c = state.clips.firstOrNull { it.id == id } ?: return@awaitEachGesture
                            val lv = state.clipLevels[id] ?: ClipLevel(0f)
                            val keys = lv.kf.sortedBy { it.t }
                            val key = (hit as? Hit.VolKey)?.let { h -> keys.firstOrNull { it.mt == h.mt } }
                            val va = anims[id]
                            val t0 = if (va == null) c.start else (c.start + ((start.x + sx()) / pps - va.start.value) / va.len.value.coerceAtLeast(0.001f) * c.length).coerceIn(c.start, c.end)
                            // the keyframes the line drag moves: the ones on either side of the finger (none: the clip's level)
                            val around = if (key != null) emptyList() else listOfNotNull(keys.lastOrNull { it.t <= t0 }, keys.firstOrNull { it.t > t0 })
                            val db0 = key?.v?.firstOrNull()?.toFloat() ?: lv.at(t0)
                            val pos0 = ClipLevel.pos(db0)
                            val i = keys.indexOf(key)
                            val lo = maxOf(c.start, keys.getOrNull(i - 1)?.t?.plus(FRAME) ?: c.start)
                            val hi = minOf(lastFrame(c.start, c.end), keys.getOrNull(i + 1)?.t?.minus(FRAME) ?: Float.MAX_VALUE)
                            var moved = false
                            var origin = start
                            var lastDb = db0
                            var lastT = key?.t ?: t0
                            var lastStep = Math.round(db0)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val p = ev.changes.filter { it.pressed }
                                if (p.isEmpty()) break
                                if (p.size >= 2 || ev.changes.any { it.isConsumed }) {
                                    volEdit = null; volTip = null; state.liveLevelDelta = null
                                    while (awaitPointerEvent().changes.any { it.pressed }) { }
                                    return@awaitEachGesture
                                }
                                val ch = p[0]
                                // measured from where the drag began (past the touch slop), so nothing jumps when it starts
                                if (!moved && (ch.position - start).getDistance() > viewConfiguration.touchSlop) { moved = true; origin = ch.position; haptics.tick() }
                                if (!moved) continue
                                ch.consume()
                                val d = ch.position - origin
                                var db = ClipLevel.dbAt(pos0 - d.y / volRangePx).coerceIn(ClipLevel.MIN_DB, ClipLevel.MAX_DB)
                                // 0 dB holds a little (the clip's own level)
                                if (abs(db) < 0.4f) db = 0f
                                val step = Math.round(db)
                                if (step != lastStep) { haptics.tick(); lastStep = step }
                                lastDb = db
                                val shown = if (key != null) {
                                    lastT = if (lo <= hi) snapFrame(key.t + d.x / pps).coerceIn(lo, hi) else key.t
                                    lv.copy(kf = lv.kf.map { if (it.mt == key.mt) it.copy(t = lastT, v = listOf(db.toDouble())) else it })
                                } else if (around.isEmpty()) ClipLevel(db)
                                else {
                                    val delta = db - db0
                                    lv.copy(kf = lv.kf.map { k -> if (around.any { it.mt == k.mt }) k.copy(v = listOf(((k.v.firstOrNull() ?: 0.0) + delta).coerceIn(ClipLevel.MIN_DB.toDouble(), ClipLevel.MAX_DB.toDouble()))) else k })
                                }
                                volEdit = id to shown
                                // heard while dragging: the change of the level where the playhead is (if it is in the clip)
                                val ph = state.playhead
                                state.liveLevelDelta = if (ph >= c.start && ph < c.end) shown.at(ph) - lv.at(ph) else null
                                // the level over the point being dragged (the line moves slower than the finger)
                                val tipAt = va?.let { an ->
                                    val (bt, bb) = volBand(an)
                                    val x = if (key == null) ch.position.x else (an.start.value + (lastT - c.start) / c.length.coerceAtLeast(0.001f) * an.len.value) * pps - sx()
                                    Offset(x, bb - ClipLevel.pos(db) * (bb - bt) + rulerPx - scrollY)
                                } ?: ch.position
                                volTip = tipAt to db
                            }
                            volTip = null
                            state.liveLevelDelta = null
                            val b = state.props
                            if (!moved) {
                                if (key != null) {
                                    val now = down.uptimeMillis
                                    if (volTap[0] == id && volTap[1] == key.mt && now - volTap[2] < viewConfiguration.doubleTapTimeoutMillis * 2) {
                                        volTap[2] = 0L
                                        haptics.heavy()
                                        volEdit = id to lv.copy(kf = lv.kf.filter { it.mt != key.mt })
                                        b?.deleteLevelKey(id, key.mt)
                                    } else { volTap[0] = id; volTap[1] = key.mt; volTap[2] = now; haptics.tick() }
                                } else {
                                    haptics.confirm()
                                    b?.addLevelKey(id, snapFrame(t0).coerceIn(c.start, lastFrame(c.start, c.end)))
                                }
                                return@awaitEachGesture
                            }
                            haptics.confirm()
                            when {
                                key != null -> b?.moveLevelKey(id, key.mt, lastT, lastDb.toDouble())
                                else -> b?.setLevelLine(id, around.map { k -> k.mt to ((k.v.firstOrNull() ?: 0.0) + (lastDb - db0)) }, lastDb.toDouble())
                            }
                            // the preview stays until the engine's levels arrive (or a while, if nothing changed)
                            val pending = volEdit
                            scope.launch { delay(1500); if (volEdit === pending) volEdit = null }
                            return@awaitEachGesture
                        }
                        // the selected gap clip's edge: its length (what follows ripples, as a storyline clip's trim)
                        if (hit is Hit.GapEdge) {
                            val g = hit.gap
                            var moved = false
                            var lastD = 0f
                            magRepreview[0] = { magPreview(magNow?.trimGap(g.start, lastD, state.keepConnected), emptySet()) }
                            while (true) {
                                val ev = awaitPointerEvent()
                                val p = ev.changes.filter { it.pressed }
                                if (p.isEmpty()) break
                                if (p.size >= 2 || ev.changes.any { it.isConsumed }) {
                                    magPreview(null, emptySet()); snapLine = null; magDragging = false
                                    while (awaitPointerEvent().changes.any { it.pressed }) { }
                                    return@awaitEachGesture
                                }
                                val ch = p[0]
                                val dx = ch.position.x - start.x
                                if (!moved && abs(dx) > viewConfiguration.touchSlop) { moved = true; state.playing = false; haptics.tick(); magDragging = true }
                                if (!moved) continue
                                ch.consume()
                                // the right edge longer to the right, the left one longer to the left; as short as nothing
                                val d = ((if (hit.left) -dx else dx) / pps).coerceAtLeast(-g.len)
                                if ((d <= -g.len) != (lastD <= -g.len)) haptics.tick()
                                lastD = d
                                magRepreview[0]?.invoke()
                            }
                            magDragging = false
                            magRepreview[0] = null
                            if (moved && abs(lastD) * pps > 0.5f) {
                                haptics.confirm()
                                if (!state.magTrimGap(g.start, snapFrame(lastD))) magPreview(null, emptySet()) else magSettleLater()
                            } else magPreview(null, emptySet())
                            state.keepConnected = false
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
                        // the magnetic timeline: the model as the gesture starts, and whether the clip is a storyline clip (its trims ripple)
                        val mag0 = magNow
                        val magLead = mag0 != null && orig != null && mag0.leadOf(orig.id) == orig.id
                        val magStory = mag0 != null && orig != null && mag0.isPrimary(orig.id)
                        val rippleTrim = if (mag0 != null) magLead else tool == Tool.RIPPLE
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
                        } else state.snapCandidates(setOfNotNull(orig?.id) + partners.map { it.first.id })
                        val scroll0 = sx()
                        val scrollY0 = scrollY
                        var vertical = false
                        val centerCands = state.snapCandidates().filter { abs(it - state.playhead) > 0.001f }
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
                        val scrubEdges = state.snapCandidates().filter { abs(it - state.playhead) > 0.001f }.sorted()
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
                            // (the magnetic preview's neighbors back to where they are)
                            if (mag0 != null) magPreview(null, setOfNotNull(orig?.id) + partners.map { it.first.id })
                            if (orig != null && a != null && dragId == orig.id) {
                                for ((pc, pa) in partners) {
                                    scope.launch { pa.start.animateTo(pc.start, Springs.settle()) }
                                    scope.launch { pa.len.animateTo(pc.length, Springs.settle()) }
                                    scope.launch { pa.track.animateTo(pc.track.toFloat(), Springs.track()) }
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
                            // a storyline clip (or a group with one): the pill to keep its connected clips in place
                            if (mag0 != null && (magStory || (groupDrag && state.multi.any { mag0.isPrimary(it) })) && (hit is Hit.Body || magLead)) magDragging = true
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
                        else if (hit is Hit.Body && tool.moves && !lockedHit) {
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
                            // with the finger near the lane's left or right edge the timeline scrolls on its own
                            // (faster the closer it is), so the box can reach clips off screen; checked every frame,
                            // also while the finger rests there
                            val edgeZone = 44.dp.toPx()
                            val edgeStep = 22.dp.toPx()
                            var last: Offset? = null
                            while (true) {
                                val ev = withTimeoutOrNull(16L) { awaitPointerEvent() }
                                if (ev != null) {
                                    val p = ev.changes.filter { it.pressed }
                                    if (p.isEmpty()) break
                                    if (p.size >= 2 || ev.changes.any { it.isConsumed }) { aborted = true; break }
                                    val ch = p[0]
                                    ch.consume()
                                    last = ch.position
                                }
                                val pos = last ?: continue
                                val push = when {
                                    pos.x < edgeZone -> -(edgeZone - pos.x) / edgeZone
                                    pos.x > laneWidth - edgeZone -> (pos.x - (laneWidth - edgeZone)) / edgeZone
                                    else -> 0f
                                }.coerceIn(-1f, 1f)
                                // (never past the project's end: the end can come into view, beyond it there is nothing to take in)
                                val endScroll = if (state.centerPlayhead) state.projectEnd * pps - laneWidth / 2f else state.projectEnd * pps - laneWidth + edgeZone
                                if (push != 0f) setScroll(min(sx() + push * edgeStep, max(sx(), endScroll)))
                                if (ev == null && push == 0f) continue
                                // selection box: from where the finger was held to where it is now
                                val q = content(pos)
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
                                if (hit is Hit.Body && tool.moves && !lockedHit && quick && d.y > 0f && d.y > abs(d.x) * 1.5f) {
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
                                    // drag right = earlier frames into the clip (a slip, as in other editing programs)
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
                                        // linked partners go as many tracks the other way (as when released)
                                        if (!groupDrag) for ((pc, pa) in partners) scope.launch { pa.track.animateTo(state.partnerTrack(orig, nt, pc).toFloat(), Springs.track()) }
                                    }
                                    hoverTrack = if (groupDrag) -1 else curTrack
                                    // magnetic: the neighbors make room (or close up) while the clip is dragged
                                    if (mag0 != null) {
                                        val tgt = target
                                        val trk = curTrack
                                        val follow = setOf(orig.id) + partners.map { it.first.id }
                                        magRepreview[0] = {
                                            val m = magNow
                                            magPreview(when {
                                                m == null -> null
                                                groupDrag -> m.moveBlock(state.multi, tgt - orig.start, state.keepConnected)
                                                tool == Tool.POSITION && m.isPrimary(orig.id) && trk == orig.track -> m.positionMove(orig.id, tgt, state.keepConnected)
                                                else -> m.move(orig.id, tgt, rows().engineIndex(trk), state.keepConnected, alone = !state.linkedSelection)
                                            }, follow)
                                        }
                                        magRepreview[0]?.invoke()
                                    }
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
                                    if (!rippleTrim) {
                                        val prevEnd = state.clips.filter { it.track == orig.track && it.id != orig.id && it.end <= orig.start + 0.001f }.maxOfOrNull { it.end }
                                        if (prevEnd != null) lo = max(lo, prevEnd)
                                    }
                                    var s = (orig.start + dt).coerceInSafe(lo, orig.end - minOf(MIN_TRIM, orig.length))
                                    val snap = if (state.magnet) state.nearestSnap(listOf(s), candidates, thr) else null
                                    if (snap != null) s = (s + snap.first).coerceInSafe(lo, orig.end - minOf(MIN_TRIM, orig.length))
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
                                    a.leftEdge = true
                                    // ripple: the clip stays put and only the start is shortened (the rest moves in) – as when released
                                    if (rippleTrim) scope.launch { a.start.animateTo(orig.start, spec) }
                                    else scope.launch { a.start.animateTo(s, spec) }
                                    scope.launch { a.len.animateTo(orig.end - s, spec) }
                                    for ((pc, pa) in partners) if (abs(pc.start - orig.start) < 0.02f) {
                                        pa.leftEdge = true
                                        if (rippleTrim) scope.launch { pa.len.animateTo(pc.length - (s - orig.start), spec) }
                                        else {
                                            scope.launch { pa.start.animateTo(s, spec) }
                                            scope.launch { pa.len.animateTo(pc.end - s, spec) }
                                        }
                                    }
                                    if (mag0 != null) {
                                        val cut = s - orig.start
                                        val follow = setOf(orig.id) + partners.map { it.first.id }
                                        magRepreview[0] = { magPreview(magNow?.trim(orig.id, true, cut, state.keepConnected), follow) }
                                        magRepreview[0]?.invoke()
                                    }
                                }
                                hit is Hit.TrimR && dragId == orig?.id && orig != null && a != null -> {
                                    var hi = orig.start + (orig.srcLen - orig.srcIn) / orig.speed
                                    if (!rippleTrim) {
                                        val nextStart = state.clips.filter { it.track == orig.track && it.id != orig.id && it.start >= orig.end - 0.001f }.minOfOrNull { it.start }
                                        if (nextStart != null) hi = min(hi, nextStart)
                                    }
                                    var e = (orig.end + dt).coerceInSafe(orig.start + minOf(MIN_TRIM, orig.length), hi)
                                    val snap = if (state.magnet) state.nearestSnap(listOf(e), candidates, thr) else null
                                    if (snap != null) e = (e + snap.first).coerceInSafe(orig.start + minOf(MIN_TRIM, orig.length), hi)
                                    applySnap(snap)
                                    lastLen = e - orig.start
                                    loupe = ch.position
                                    // the outgoing frame, beside the next clip's first frame when one starts right here
                                    if (orig.kind == ClipKind.VIDEO) state.trimView = TrimView(orig.item, (orig.srcIn + (e - orig.start) * orig.speed - 0.04f).coerceAtLeast(orig.srcIn).toDouble(),
                                        pair = state.clips.firstOrNull { it.track == orig.track && it.id != orig.id && it.kind == ClipKind.VIDEO && abs(it.start - orig.end) < 0.02f }
                                            ?.let { r -> TrimView(r.item, r.srcIn.toDouble()) },
                                        pairLeft = false)
                                    val spec = if (snap != null) Springs.snap() else Springs.follow()
                                    a.leftEdge = false
                                    scope.launch { a.len.animateTo(e - orig.start, spec) }
                                    for ((pc, pa) in partners) if (abs(pc.end - orig.end) < 0.02f) { pa.leftEdge = false; scope.launch { pa.len.animateTo(e - pc.start, spec) } }
                                    if (mag0 != null) {
                                        val dl = e - orig.end
                                        val follow = setOf(orig.id) + partners.map { it.first.id }
                                        magRepreview[0] = { magPreview(magNow?.trim(orig.id, false, dl, state.keepConnected), follow) }
                                        magRepreview[0]?.invoke()
                                    }
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
                            // a double tap on a track (on a clip or between them) opens it – an audio track for the clips'
                            // volume lines, taller, with the line to draw keyframes on; a video track as tall (between its clips:
                            // two taps on a video clip still select and deselect it) – or closes it again
                            if (tool != Tool.RAZOR && state.props != null && (hit is Hit.Body || hit is Hit.TrimL || hit is Hit.TrimR || hit == Hit.Empty)) {
                                val ti = anyTrackAt(start.y - rulerPx + scrollY)
                                if (rows().getOrNull(ti)?.kind == TrackKind.VIDEO && hit != Hit.Empty) trackTap[0] = -1L
                                else {
                                    if (trackTap[0] == ti.toLong() && down.uptimeMillis - trackTap[1] < viewConfiguration.doubleTapTimeoutMillis) {
                                        trackTap[0] = -1L
                                        haptics.confirm()
                                        state.toggleOpen(ti)
                                        return@awaitEachGesture
                                    }
                                    trackTap[0] = ti.toLong(); trackTap[1] = down.uptimeMillis
                                }
                            }

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
                                is Hit.Fade, is Hit.Roll, is Hit.Cap, Hit.CapLane, is Hit.VolKey, is Hit.VolLine -> Unit
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
                                Hit.Empty -> {
                                    // the magnetic timeline: a gap clip on the storyline is selected (as a clip)
                                    val g = gapAtTouch(start)
                                    state.clearSelection()
                                    if (g != null && tool != Tool.RAZOR) { haptics.tick(); state.selectedGap = g }
                                }
                                is Hit.GapEdge -> Unit
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
                        if (dropDelete && a != null) {
                            dragId = null
                            haptics.heavy()
                            if (mag0 != null) { magPreview(null, setOf(orig.id)); magSettleLater() }
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
                                            if (mag0 != null && state.multiCaptions.isEmpty()) { if (!state.magMoveBlock(state.multi, lastTarget - orig.start)) magPreview(null, emptySet()); magSettleLater() }
                                            else state.moveGroup(lastTarget - orig.start, insert = tool == Tool.RIPPLE)
                                            val pending = capGroupDt
                                            if (pending != null) scope.launch { delay(1500); if (capGroupDt === pending) capGroupDt = null }
                                        } else capGroupDt = null
                                    } else if (mag0 != null) {
                                        // magnetic: the plan the preview showed, made again from the timeline as it is
                                        val changed = (lastTarget != orig.start || curTrack != orig.track) && state.magMove(orig, lastTarget, curTrack, position = tool == Tool.POSITION)
                                        if (changed) haptics.confirm()
                                        else {
                                            magPreview(null, emptySet())
                                            for ((cl, an) in listOf(orig to a) + partners) {
                                                scope.launch { an.start.animateTo(cl.start, Springs.settle()) }
                                                scope.launch { an.track.animateTo(cl.track.toFloat(), Springs.track()) }
                                            }
                                        }
                                        magSettleLater()
                                    } else if (lastTarget != orig.start || curTrack != orig.track) {
                                        haptics.confirm()
                                        state.moveClip(orig, lastTarget, curTrack, insert = tool == Tool.RIPPLE)
                                        // a move the engine didn't take (the timeline stays the same): the clips slide back
                                        scope.launch {
                                            delay(1500)
                                            for ((cl, an) in listOf(orig to a) + partners) {
                                                val now = state.clips.firstOrNull { it.id == cl.id } ?: continue
                                                if (dragId != null) break
                                                if (abs(an.track.value - now.track) > 0.01f) launch { an.track.animateTo(now.track.toFloat(), Springs.track()) }
                                                if (abs(an.start.value - now.start) > 1e-3f) launch { an.start.animateTo(now.start, Springs.settle()) }
                                            }
                                        }
                                    }
                                }
                            }
                            // back where it started: no trim (and no undo step)
                            is Hit.TrimL, is Hit.TrimR -> if (orig != null && dragId == orig.id && abs(lastLen - orig.length) < 1e-4f && abs(lastTarget - orig.start) < 1e-4f) {
                                revert()
                            } else if (mag0 != null && orig != null && dragId == orig.id) {
                                // magnetic: a storyline clip ripples (its connected clips keep to their frames), others trim alone
                                dragId = null
                                haptics.confirm()
                                val inEdge = hit is Hit.TrimL
                                val delta = if (inEdge) (if (rippleTrim) orig.length - lastLen else lastTarget - orig.start) else lastLen - orig.length
                                if (!state.magTrim(orig.id, inEdge, delta)) { a?.leftEdge = false; magPreview(null, emptySet()) }
                                magSettleLater()
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
                      } finally {
                        touching[0] = false
                        magDragging = false
                        magRepreview[0] = null
                        state.keepConnected = false
                      }
                    }
                }
        ) {
            // Background, ruler, markers
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                // empty tracks as slim lanes (a darker strip)
                rows().indices.forEach { i ->
                    if (heightsPx[i] < fullPxOf(i) - 0.5f) drawRect(K.Deep.copy(alpha = 0.7f), Offset(0f, rulerPx + topsPx[i] - scrollY), Size(w, heightsPx[i]))
                }
                // tracks
                rows().forEachIndexed { i, _ ->
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
                // pre-render state (a render bar, as in other editing programs): green = rendered (plays from a finished file), red = heavy
                // parts (layers, effects, transitions) computed in real time
                for (c in se.gendut.klipp.engine.RenderCache.states) {
                    if (!c.ready && !c.heavy) continue
                    val x0 = (c.a * pps).toFloat() - sx(); val x1 = (c.b * pps).toFloat() - sx()
                    if (x1 < 0f || x0 > w) continue
                    drawRect(if (c.ready) Color(0xFF4CAF7A) else Color(0xFFD9534F), Offset(x0, rulerBasePx - 3.dp.toPx()), Size(x1 - x0, 3.dp.toPx()))
                }
                // the chunk being rendered now: amber, filled as far as it has come
                se.gendut.klipp.engine.RenderCache.current?.let { (a, b) ->
                    val x0 = (a * pps).toFloat() - sx(); val x1 = (b * pps).toFloat() - sx()
                    if (x1 >= 0f && x0 <= w) {
                        val h = 3.dp.toPx(); val y = rulerBasePx - h
                        drawRect(Color(0x66E0A93B), Offset(x0, y), Size(x1 - x0, h))
                        drawRect(Color(0xFFE0A93B), Offset(x0, y), Size((x1 - x0) * se.gendut.klipp.engine.RenderCache.currentDone, h))
                    }
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
                        }.also { if (it.rowId == null) it.rowId = state.tracks.getOrNull(c.track)?.id }
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
                            // (dragged out of the timeline it was drawn where it was dropped: back to its place)
                            if (dragId != c.id) {
                                launch { a.start.animateTo(c.start, Springs.settle()) }
                                launch { a.len.animateTo(c.length, Springs.settle()) }
                                launch { a.track.animateTo(c.track.toFloat(), Springs.track()) }
                            }
                        }
                        if (a.appear.value < 1f) a.appear.animateTo(1f, Springs.pop())
                    }
                    LaunchedEffect(c) {
                        if (dragId == c.id) return@LaunchedEffect
                        // the engine's reply to a trim: the clip's own source start now holds what was cut
                        a.leftEdge = false
                        // the same track on another row (a track was added on top or deleted): it moves with its row at once
                        val rowId = state.tracks.getOrNull(c.track)?.id
                        val shifted = rowId != null && rowId == a.rowId && abs(a.track.value - c.track) > 0.01f && abs(a.track.value - a.track.targetValue) < 0.01f
                        a.rowId = rowId
                        if (shifted) { a.track.snapTo(c.track.toFloat()); state.rippleDelay.remove(c.id) }
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
                        level = if (c.kind.track == TrackKind.AUDIO) volEdit?.takeIf { it.first == c.id }?.second ?: state.clipLevels[c.id] else null,
                        editLine = state.volOn(c.track),
                        // simple mode: a mark on a clip that holds something made in advanced mode (its choices: the dock's Advanced)
                        badges = if (state.simple) state.badgesOf(c, gsNow) else emptyList(),
                    )
                }
            }

            // Transitions (bow tie over the clip edge) and captions (bars under the ruler)
            Canvas(Modifier.fillMaxSize()) {
                // hidden/muted tracks are dimmed, locked ones get diagonal stripes
                state.trackStates.forEachIndexed { i, ts ->
                    if (i > rows().lastIndex) return@forEachIndexed
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
                // the magnetic timeline: gap clips on the storyline's row and a thin line from each connected clip to its
                // connection point on the storyline
                if (magNow != null) {
                    val v1 = rows().v1Row.toFloat()
                    val a1 = rows().a1Row.toFloat()
                    val inset = 3.dp.toPx()
                    val gTop = rulerPx + yOf(v1) - scrollY + inset
                    val gH = hOf(v1) - inset * 2
                    if (dragId == null && gTop + gH > rulerPx) for (g in magGaps) {
                        val x0 = g.start * pps - sx()
                        val x1 = g.end * pps - sx()
                        if (x1 < 0f || x0 > size.width || x1 - x0 < 2f) continue
                        val sel = state.selectedGap?.let { abs(it.start - g.start) < 1e-3f } == true
                        drawRoundRect(K.Deep.copy(alpha = if (sel) 0.8f else 0.55f), Offset(x0 + 1f, gTop), Size(x1 - x0 - 2f, gH), CornerRadius(6.dp.toPx()))
                        drawRoundRect(if (sel) K.Mark else K.OutlineVar, Offset(x0 + 1f, gTop), Size(x1 - x0 - 2f, gH), CornerRadius(6.dp.toPx()),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(if (sel) 2.dp.toPx() else 1.dp.toPx(), pathEffect = if (sel) null else gapDash))
                        if (sel) for (hx in listOf(x0 + 4.dp.toPx(), x1 - 4.dp.toPx())) drawRoundRect(K.Mark, Offset(hx - 2.dp.toPx(), gTop + gH * 0.25f), Size(4.dp.toPx(), gH * 0.5f), CornerRadius(2.dp.toPx()))
                    }
                    val lineCol = K.OnSurface.copy(alpha = 0.35f)
                    for (cn in magLinks) {
                        val c = state.clips.firstOrNull { it.id == cn.clip } ?: continue
                        if (c.id in state.removing) continue
                        val an = anims[c.id] ?: continue
                        val x = (cn.at + an.start.value - c.start) * pps - sx()
                        if (x < -4f || x > size.width + 4f) continue
                        val row = an.track.value
                        val video = rows().getOrNull(c.track)?.kind != TrackKind.AUDIO
                        val (y0, y1) = if (video) (rulerPx + yOf(row) + hOf(row) - scrollY - inset) to (rulerPx + yOf(v1) - scrollY + inset)
                            else (rulerPx + yOf(a1) + hOf(a1) - scrollY - inset) to (rulerPx + yOf(row) - scrollY + inset)
                        if (y1 - y0 < 4f) continue
                        drawLine(lineCol, Offset(x, maxOf(y0, rulerPx)), Offset(x, maxOf(y1, rulerPx)), 1.dp.toPx())
                        val dot = if (video) y1 else y0
                        if (dot > rulerPx) drawCircle(lineCol, 2.5.dp.toPx(), Offset(x, dot))
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
                    drawRoundRect(K.Surface.copy(alpha = 0.8f), Offset(x0, top), Size(x1 - x0, h), CornerRadius(6.dp.toPx()))
                    drawPath(shape, (if (sel) K.Mark else K.Mark).copy(alpha = if (sel) 0.75f else 0.55f))
                    drawRoundRect(if (sel) K.Mark else K.Mark, Offset(x0, top), Size(x1 - x0, h), CornerRadius(6.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke((if (sel) 2.5f else 1.5f).dp.toPx()))
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
                        drawRoundRect(K.Surface.copy(alpha = 0.8f), Offset(x - w / 2 - 2.dp.toPx(), top + 1.dp.toPx()), Size(w + 4.dp.toPx(), h - 2.dp.toPx()), CornerRadius(6.dp.toPx()))
                        drawRoundRect(K.Mark, Offset(x - w / 2, top + 3.dp.toPx()), Size(w, h - 6.dp.toPx()), CornerRadius(5.dp.toPx()))
                        drawLine(K.OnMark, Offset(x, top + h * 0.3f), Offset(x, top + h * 0.7f), 2.dp.toPx())
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
                            drawCircle(K.Surface, r + 2.dp.toPx(), Offset(cxp, top + r + 3.dp.toPx()))
                            drawCircle(K.Mark, r, Offset(cxp, top + r + 3.dp.toPx()))
                        }
                    }
                }
                // track volume automation (yellow line with keyframes, as in other editing programs): +6 dB at the top, −60 dB at the bottom
                state.trackVolKf.forEachIndexed { k, kfs ->
                    if (kfs.isEmpty()) return@forEachIndexed
                    val ti = rows().audioRow(k)
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
                    // several caption tracks with captions: a row each (they were drawn on each other)
                    val capRows = captionRows(state)
                    val laneTop = rulerBasePx + 3.dp.toPx()
                    val laneH = rulerPx - rulerBasePx - 6.dp.toPx()
                    val rowH = if (capRows.size < 2) laneH else (laneH - (capRows.size - 1) * 2.dp.toPx()) / capRows.size
                    val paint = capPaint.apply { textSize = if (capRows.size < 2) 11.sp.toPx() else (rowH * 0.62f).coerceAtMost(11.sp.toPx()) }
                    for (c0 in state.captions) {
                        val ri = if (capRows.size < 2) 0 else capRows.indexOf(c0.track).coerceAtLeast(0)
                        val top = laneTop + ri * (rowH + 2.dp.toPx())
                        val h = rowH
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
                        drawPath(path, K.Mark.copy(alpha = 0.14f))
                        drawPath(path, K.Mark, style = androidx.compose.ui.graphics.drawscope.Stroke(
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
                        drawCircle(K.Surface, 7.dp.toPx(), Offset(x, cy))
                        drawCircle(red, 5.dp.toPx(), Offset(x, cy))
                    }
                }
                volTip?.let { (at, db) ->
                    val paint = capPaint.apply { textSize = 13.sp.toPx() }
                    val text = (if (db > 0.05f) "+" else "") + "%.1f dB".format(db).replace('-', '−')
                    val tw = paint.measureText(text)
                    val pad = 8.dp.toPx()
                    val bx = (at.x - tw / 2 - pad).coerceIn(4.dp.toPx(), size.width - tw - pad * 2 - 4.dp.toPx())
                    val by = (at.y - 40.dp.toPx()).coerceAtLeast(rulerBasePx + 2.dp.toPx())
                    drawRoundRect(K.Surface.copy(alpha = 0.93f), Offset(bx, by), Size(tw + pad * 2, 26.dp.toPx()), CornerRadius(13.dp.toPx()))
                    drawContext.canvas.nativeCanvas.drawText(text, bx + pad, by + 13.dp.toPx() + paint.textSize * 0.36f, paint)
                }
                snapLine?.let { s ->
                    val x = s * pps - sx()
                    val g = snapGlow.value
                    drawRect(K.Mark.copy(alpha = 0.18f * g), Offset(x - 6.dp.toPx() * g, 0f), Size(12.dp.toPx() * g, size.height))
                    drawRect(K.Mark.copy(alpha = min(1f, g)), Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                }
                val px = state.playhead * pps - sx()
                drawRect(K.Mark, Offset(px - 1.dp.toPx(), rulerBasePx - 4.dp.toPx()), Size(2.dp.toPx(), size.height))
                drawRoundRect(
                    K.Mark, Offset(px - 7.dp.toPx(), 4.dp.toPx()), Size(14.dp.toPx(), 18.dp.toPx()),
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
    // magnetic: while a storyline clip is dragged or trimmed, another finger on this pill keeps its connected clips
    // where they are (the touch equivalent of holding a key while dragging)
    androidx.compose.animation.AnimatedVisibility(
        magDragging, Modifier.align(Alignment.TopCenter).padding(top = rulerH + 4.dp),
        enter = androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(0.5f, 600f), initialScale = 0.8f) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.scaleOut(targetScale = 0.8f) + androidx.compose.animation.fadeOut(),
    ) {
        val keep = state.keepConnected
        Row(
            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (keep) K.PrimaryContainer else K.SurfaceHighest)
                .clickable { haptics.tick(); state.keepConnected = !state.keepConnected }
                .padding(horizontal = 14.dp).testTag("magnetic:keep"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(if (keep) Ic.Unlink else Ic.Link, null, tint = if (keep) K.PrimarySoft else K.OnSurfaceVar, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (keep) R.string.magnetic_keep_stay else R.string.magnetic_keep_follow), color = if (keep) K.PrimarySoft else K.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
    /** An audio clip's level: its volume line (and the waveform follows it). */
    level: ClipLevel? = null,
    /** The volume lines can be edited: drawn bolder, with large keyframes. */
    editLine: Boolean = false,
    /** Simple mode: what the clip holds from advanced mode (a small mark in its corner). */
    badges: List<Badge> = emptyList(),
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
                // lifted, it grows in height only: its edges are where it will land (the snap line)
                scaleX = anim.pulse.value * (0.85f + 0.15f * ap)
                scaleY = (1f + 0.06f * l) * anim.pulse.value * (0.4f + 0.6f * ap)
                alpha = ap.coerceIn(0f, 1f)
                shadowElevation = 18.dp.toPx() * l.coerceAtLeast(0f)
                this.shape = shape
                this.clip = true
                // disabled clip: the grayscale is drawn in its own layer (saturation is removed only from the clip)
                compositingStrategy = if (clip.enabled) androidx.compose.ui.graphics.CompositingStrategy.Auto else androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }
            .then(if (selected) Modifier.border(2.dp, K.Mark, shape) else Modifier)
            .then(if (clip.enabled) Modifier else Modifier.testTag("clipOff:${clip.id}").drawWithContent {
                drawContent()
                drawRect(Color(0xFF808080), blendMode = androidx.compose.ui.graphics.BlendMode.Saturation)
                drawRect(Color.Black.copy(alpha = 0.45f))
            })
            .background(clip.kind.bg)
            // color label: a frame inside the clip's edge (inside the selection border when selected)
            .then(clip.label?.let { l -> Modifier.testTag("clipLabel:${clip.id}:${l.name}").drawWithContent {
                drawContent()
                val w = 2.5.dp.toPx(); val inset = (if (selected) 2.dp.toPx() else 0f) + w / 2
                drawRoundRect(l.color, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2),
                    CornerRadius((10.dp.toPx() - inset).coerceAtLeast(0f)), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            } } ?: Modifier)
            // dragged out of the timeline: red (released now, it is deleted)
            .drawWithContent { drawContent(); val d = anim.doom.value; if (d > 0.01f) drawRect(Color(0xFFE5484D).copy(alpha = 0.6f * d)) }
    ) {
        // the source the box shows now: while an edge is dragged the material stays where it is (nothing stretches)
        val leftCut = { if (anim.leftEdge) clip.length - anim.len.value else 0f }
        val span = { liveSourceSpan(clip.srcIn, clip.length, clip.speed, clip.reverse, clip.hold, anim.len.value, leftCut()) }
        if (clip.kind == ClipKind.AUDIO || clip.kind == ClipKind.MUSIC) {
            Waveform(clip, uri, span, { f -> clip.start + leftCut() + f * anim.len.value }, pps, Modifier.fillMaxSize(), level)
        } else if (uri != null) Filmstrip(clip, uri, span, pps, Modifier.fillMaxSize())
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
        if (level != null && (editLine || level.animated || abs(level.db) > 0.05f)) VolumeLine(clip, level, editLine, Modifier.fillMaxSize())
        BadgeMark(badges, Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = if (selected) 13.dp else 4.dp).testTag("badgeMark:${clip.id}"))
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterStart).width(10.dp).fillMaxHeight()
                    .background(K.Mark, RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp)),
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(2.dp, 14.dp).background(K.OnMark)) }
            Box(
                Modifier.align(Alignment.CenterEnd).width(10.dp).fillMaxHeight()
                    .background(K.Mark, RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp)),
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(2.dp, 14.dp).background(K.OnMark)) }
        }
    }
}

/** Audio tracks are this much taller while the volume lines are edited. */
const val VOL_TRACK_SCALE = 2.2f
/** A track opened with a double tap (audio: for its volume lines; video: the same height). */
const val OPEN_TRACK_DP = 44f * VOL_TRACK_SCALE

/** Where an audio clip [h] px tall draws its volume line: (top, bottom) within the clip – under the name, to the bottom edge. */
fun volBandOf(h: Float, density: Float): Pair<Float, Float> = 17f * density to (h - 3f * density).coerceAtLeast(17f * density + 1f)

/** An audio clip's volume line: its level over time on the fader scale, with the keyframes. */
@Composable
private fun VolumeLine(clip: Clip, level: ClipLevel, edit: Boolean, modifier: Modifier) {
    val path = remember { Path() }
    val diamond = remember { Path() }
    Canvas(modifier.testTag("volline:${clip.id}")) {
        val (bt, bb) = volBandOf(size.height, density)
        if (bb - bt < 6f) return@Canvas
        fun y(db: Float) = bb - ClipLevel.pos(db) * (bb - bt)
        fun x(t: Float) = ((t - clip.start) / clip.length.coerceAtLeast(0.001f) * size.width).coerceIn(0f, size.width)
        path.reset()
        if (level.animated) {
            // a point every 2 px (at most 600 per clip, also when zoomed far in): eased keyframes bend the line
            val n = (size.width / 2f).toInt().coerceIn(1, 600)
            for (i in 0..n) {
                val px = size.width * i / n
                val py = y(level.at(clip.start + px / size.width * clip.length))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
        } else { path.moveTo(0f, y(level.db)); path.lineTo(size.width, y(level.db)) }
        val w = (if (edit) 2f else 1.2f).dp.toPx()
        val a = if (edit) 1f else 0.6f
        drawPath(path, Color.Black.copy(alpha = 0.5f * a), style = androidx.compose.ui.graphics.drawscope.Stroke(w + 2.dp.toPx()))
        drawPath(path, Color(0xFFFFE08A).copy(alpha = a), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        val r = (if (edit) 5f else 3f).dp.toPx()
        for (k in level.kf) {
            val cx = x(k.t); val cy = y(k.v.firstOrNull()?.toFloat() ?: 0f)
            val d = diamond.apply { reset(); moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }
            drawPath(d, Color.Black.copy(alpha = 0.6f * a), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawPath(d, Color(0xFFFFE08A).copy(alpha = a))
        }
    }
}

/**
 * The waveform from the file's real audio (peak values, see Peaks), for the part of the source the box shows now
 * ([span], source seconds at its left and right edge – it follows a trim live). The bars sit on a fixed grid of source
 * time (a bar per [WaveDetail] step at the timeline's zoom), so the peaks under the finger stand still while an edge is
 * dragged. Without a file (prototype mode) or while it decodes: a calm placeholder.
 */
@Composable
private fun Waveform(clip: Clip, uri: android.net.Uri?, span: () -> Pair<Double, Double>, timeAt: (Float) -> Float, pps: () -> Float, modifier: Modifier, level: ClipLevel? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // the clip's own stream and channels (one of a camera's microphone tracks): that file and those channels
    val pick = remember(uri, clip.audioStream, clip.sourceChannels, se.gendut.klipp.engine.AudioStreams.revision) {
        uri?.let { se.gendut.klipp.engine.AudioChannels.resolve(it, it, clip.audioStream, clip.sourceChannels) }
    }
    val peaks by androidx.compose.runtime.produceState(pick?.let { se.gendut.klipp.engine.Peaks.cachedLevels(it.uri, it.picks) }, pick) {
        if (pick != null && value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { se.gendut.klipp.engine.Peaks.loadLevels(ctx, pick.uri, pick.picks) }
    }
    val detail = WaveDetail.current
    val outline = remember { Path() }
    Canvas(modifier.testTag(if (peaks != null) "wave:${clip.id}" else "wavePending:${clip.id}")) {
        val mid = size.height * 0.6f
        val maxH = size.height * 0.36f
        val col = clip.kind.fg.copy(alpha = 0.55f)
        val p = peaks
        if (p == null) {
            drawLine(clip.kind.fg.copy(alpha = 0.25f), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
            return@Canvas
        }
        val step = if (detail == WaveDetail.MAX) 1f else detail.stepDp.dp.toPx()
        val (from, to) = span()
        // dB scale (−48 … 0 dBFS) so speech shows even when it's quiet; with the clip's level (as it plays)
        fun height(m: Float, x: Float): Float {
            if (m <= 0.002f) return 0f
            val gain = level?.at(timeAt(x / size.width)) ?: 0f
            return ((20f * kotlin.math.log10(m) + gain + 48f) / 48f).coerceIn(0.03f, 1f)
        }
        val dSrc = waveBarSource(step, pps(), clip.speed)
        if (detail == WaveDetail.MAX) {
            // one value per pixel: a filled outline (separate lines would be a pixel wide each)
            outline.reset()
            val ys = ArrayList<Pair<Float, Float>>()
            forEachWaveBar(from, to, size.width, dSrc) { x, t0 -> ys += x to height(p.peak(t0, t0 + dSrc), x) }
            if (ys.isEmpty()) return@Canvas
            ys.sortBy { it.first }
            outline.moveTo(ys.first().first, mid)
            for ((x, v) in ys) outline.lineTo(x, mid - maxH * v)
            outline.lineTo(ys.last().first, mid)
            for (i in ys.indices.reversed()) outline.lineTo(ys[i].first, mid + maxH * ys[i].second * 0.55f)
            outline.close()
            drawPath(outline, col)
            return@Canvas
        }
        forEachWaveBar(from, to, size.width, dSrc) { x, t0 ->
            val v = height(p.peak(t0, t0 + dSrc), x)
            if (v > 0f) drawLine(col, Offset(x, mid - maxH * v), Offset(x, mid + maxH * v * 0.55f), strokeWidth = step * 0.7f)
        }
    }
}

/** How fine the timeline draws waveforms (Settings → Timeline). [stepDp]: a bar per this many dp (MAX: one per pixel). */
enum class WaveDetail(val stepDp: Float) {
    NORMAL(2f),
    DETAILED(1f),
    MAX(0f);

    companion object {
        /** The detail in use (set from the settings when the app starts and when it is changed). */
        var current by mutableStateOf(NORMAL)
    }
}

/**
 * Which part of its source a clip box shows while its edges may be dragged: the source time (s) at the box's left and
 * at its right edge (reversed: the left is the later one; a freeze frame: its first frame throughout). [liveLen] is the
 * box's length now (s on the timeline) and [leftCut] how much of the clip's committed start is trimmed away right now
 * (< 0: extended). The material stays anchored where it was: a trim shows more or less of it, never stretches it.
 */
internal fun liveSourceSpan(srcIn: Float, length: Float, speed: Float, reverse: Boolean, hold: Boolean, liveLen: Float, leftCut: Float): Pair<Double, Double> {
    if (hold) return srcIn.toDouble() to srcIn.toDouble()
    val s = abs(speed).toDouble()
    val a = leftCut.toDouble()
    val b = (leftCut + liveLen).toDouble()
    if (!reverse) return (srcIn + a * s) to (srcIn + b * s)
    // reversed: the clip's start shows the end of its source (as the engine's source time)
    val end = srcIn + length * s
    return (end - a * s) to (end - b * s)
}

/** Source seconds per waveform bar: a bar per [stepPx] at [pps] (pixels per timeline second), at the clip's speed. */
internal fun waveBarSource(stepPx: Float, pps: Float, speed: Float): Double = stepPx.toDouble() / pps.coerceAtLeast(1e-3f) * abs(speed).coerceAtLeast(1e-3f)

/**
 * The bars of a box [width] px wide showing source [from]..[to] (s; reversed: from > to): bars on a fixed grid of
 * source time, [dSrc] s each – (the bar's middle in the box, the bar's source start). The grid doesn't move with the
 * box's edges, so the bars stand still while one is dragged.
 */
internal inline fun forEachWaveBar(from: Double, to: Double, width: Float, dSrc: Double, f: (x: Float, t0: Double) -> Unit) {
    if (dSrc <= 0.0 || width <= 0f || from == to) return
    val lo = minOf(from, to)
    val hi = maxOf(from, to)
    val scale = width / (to - from)
    var k = kotlin.math.floor(lo / dSrc).toLong()
    while (k * dSrc < hi) {
        val t0 = k * dSrc
        val x = ((t0 + dSrc / 2 - from) * scale).toFloat()
        if (x >= 0f && x <= width) f(x, t0)
        k++
    }
}

/**
 * Filmstrip: frames from the clip's source, on a fixed grid of source time (one tile per ~64 dp, at most ~8 per clip,
 * as a power of two of seconds so zooming reuses the frames). The tiles stay where their frames are while an edge is
 * trimmed ([span], as the waveform): extending shows the new frames, nothing stretches.
 */
@Composable
internal fun Filmstrip(clip: Clip, uri: android.net.Uri, span: () -> Pair<Double, Double>, pps: () -> Float, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val tileW = with(density) { 64.dp.toPx() }
    // source seconds per tile and the tiles in view, read in derivedStateOf: the clip recomposes only when they change,
    // not per pinch or drag frame
    val dSrc by remember(clip.length, clip.speed, tileW) {
        androidx.compose.runtime.derivedStateOf { filmTileSource(tileW, pps(), clip.speed, clip.length) }
    }
    val tiles by remember(clip.hold) {
        androidx.compose.runtime.derivedStateOf {
            val (a, b) = span()
            if (clip.hold) listOf(Long.MIN_VALUE) else {
                val d = dSrc
                (kotlin.math.floor(minOf(a, b) / d).toLong() until kotlin.math.ceil(maxOf(a, b) / d).toLong()).toList()
            }
        }
    }
    // the frame each tile shows: its middle, in whole seconds (the cache is reused while zooming); freeze frame: the first
    fun frameOf(k: Long): Double = if (k == Long.MIN_VALUE) kotlin.math.floor(clip.srcIn.toDouble()) else kotlin.math.floor((k + 0.5) * dSrc)
    val times = remember(tiles, dSrc) { tiles.map { frameOf(it) }.distinct() }
    // loaded frames per source time: a changed tile set loads only the new ones, old ones show meanwhile
    val frames = remember(uri) { androidx.compose.runtime.mutableStateMapOf<Double, androidx.compose.ui.graphics.ImageBitmap>() }
    LaunchedEffect(uri, times) {
        for (t in times) if (t !in frames) Thumbs.load(ctx, uri, t)?.let { frames[t] = it }
        if (frames.size > 24) frames.keys.retainAll(times.toSet())
    }
    Canvas(modifier.graphicsLayer { alpha = 0.55f }) {
        val (from, to) = span()
        val d = dSrc
        fun xOf(t: Double): Float = if (from == to) 0f else ((t - from) / (to - from) * size.width).toFloat()
        // the tiles' edges in the box (a freeze frame: the same frame every tileW px)
        val rects: List<Pair<Long, Pair<Float, Float>>> = if (clip.hold) List(kotlin.math.ceil(size.width / tileW).toInt().coerceIn(1, 64)) { i -> Long.MIN_VALUE to (i * tileW to (i + 1) * tileW) }
            else tiles.map { k -> k to xOf(k * d).let { a -> xOf((k + 1) * d).let { b -> minOf(a, b) to maxOf(a, b) } } }
        for ((k, edges) in rects) {
            val t = frameOf(k)
            val img = frames[t] ?: frames.keys.minByOrNull { abs(it - t) }?.let { frames[it] } ?: continue
            val (x0, x1) = edges
            val w = x1 - x0
            if (w < 1f || x1 < 0f || x0 > size.width) continue
            val scale = maxOf(w / img.width, size.height / img.height)
            val sw = (w / scale).toInt().coerceIn(1, img.width)
            val sh = (size.height / scale).toInt().coerceIn(1, img.height)
            drawImage(
                img,
                srcOffset = androidx.compose.ui.unit.IntOffset((img.width - sw) / 2, (img.height - sh) / 2),
                srcSize = androidx.compose.ui.unit.IntSize(sw, sh),
                dstOffset = androidx.compose.ui.unit.IntOffset(x0.roundToInt(), 0),
                dstSize = androidx.compose.ui.unit.IntSize(w.roundToInt() + 1, size.height.roundToInt()),
            )
        }
    }
}

/**
 * Source seconds per filmstrip tile: one per [tileW] px at [pps], at least an eighth of the clip (at most ~8 decodes per
 * clip), rounded up to a power of two of seconds so a pinch doesn't change it every frame.
 */
internal fun filmTileSource(tileW: Float, pps: Float, speed: Float, length: Float): Double {
    val s = abs(speed).coerceAtLeast(1e-3f).toDouble()
    val raw = maxOf(tileW / pps.coerceAtLeast(1e-3f) * s, length * s / 8.0, 1.0 / 64)
    return Math.pow(2.0, kotlin.math.ceil(kotlin.math.log2(raw)))
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

/**
 * A track header's taps without waiting for a second one: [onTap] at once on every tap, [onDoubleTap] on the second of
 * two quick taps (instead of a second [onTap]), [onLongPress] when held. (A clickable with a double click delays every
 * single tap by the double-tap timeout – targeting a video track by tapping its header must stay immediate.)
 */
@Composable
private fun Modifier.headerTaps(onTap: () -> Unit, onDoubleTap: () -> Unit, onLongPress: () -> Unit): Modifier {
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    val double by androidx.compose.runtime.rememberUpdatedState(onDoubleTap)
    val long by androidx.compose.runtime.rememberUpdatedState(onLongPress)
    val last = remember { longArrayOf(Long.MIN_VALUE / 2) }
    return this
        .semantics {
            onClick { tap(); true }
            onLongClick { long(); true }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    up = waitForUpOrCancellation()
                    true
                }
                if (ended == null) {
                    long()
                    // the rest of the press is the menu's
                    while (awaitPointerEvent().changes.any { it.pressed }) { }
                    return@awaitEachGesture
                }
                val u = up ?: return@awaitEachGesture
                u.consume()
                if (u.uptimeMillis - last[0] < viewConfiguration.doubleTapTimeoutMillis) { last[0] = Long.MIN_VALUE / 2; double() }
                else { last[0] = u.uptimeMillis; tap() }
            }
        }
}

/**
 * Under the last track header: add a video track (it goes on top) or an audio track (at the bottom); greyed out at the
 * limits (MAX_VIDEO_TRACKS, MAX_AUDIO_TRACKS).
 */
@Composable
private fun AddTrackRow(canVideo: Boolean, canAudio: Boolean, onAdd: (TrackKind) -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 6.dp, end = 4.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((kind, on) in listOf(TrackKind.VIDEO to canVideo, TrackKind.AUDIO to canAudio)) {
            val label = stringResource(if (kind == TrackKind.VIDEO) R.string.timeline_add_video_track else R.string.timeline_add_audio_track)
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(K.SurfaceHigh)
                    .clickable(enabled = on, onClickLabel = label) { onAdd(kind) }
                    .testTag("addTrack:${kind.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                Text("+" + if (kind == TrackKind.VIDEO) "V" else "A", color = if (on) K.OnSurfaceVar else K.OutlineVar, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** "Delete track V4?" – only an empty track is offered; the deletion is one undo step. */
@Composable
private fun DeleteTrackDialog(name: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timeline_delete_track_title, name)) },
        text = { Text(stringResource(R.string.timeline_delete_track_text)) },
        confirmButton = { FlatTextButton(onDelete, Modifier.testTag("deleteTrackConfirm")) { Text(stringResource(R.string.timeline_delete_track)) } },
        dismissButton = { FlatTextButton(onDismiss) { Text(stringResource(R.string.titleui_cancel)) } },
        containerColor = K.SurfaceHigh,
    )
}

/**
 * Track states (hold the track header). Video tracks: lock, show/hide, sync lock. Audio tracks: lock, mute, solo, sync
 * lock. Then: add a track of the same kind ([canAdd]: below the limit), delete this track ([canDelete]: it is empty).
 */
@Composable
private fun TrackMenu(
    open: Boolean, onDismiss: () -> Unit, t: TrackDef, ts: TrackState, canDelete: Boolean,
    canAdd: Boolean, onAdd: () -> Unit, onDelete: () -> Unit, set: (String, Boolean) -> Unit,
) {
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
        androidx.compose.material3.HorizontalDivider(color = K.OutlineVar)
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(if (t.kind == TrackKind.VIDEO) R.string.timeline_add_video_track else R.string.timeline_add_audio_track), color = if (canAdd) K.OnSurface else K.Outline, fontSize = 14.sp) },
            onClick = onAdd, enabled = canAdd,
            modifier = Modifier.testTag("trackAdd:${t.name}"),
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.timeline_delete_track_menu), color = if (canDelete) K.OnSurface else K.Outline, fontSize = 14.sp) },
            onClick = onDelete, enabled = canDelete,
            modifier = Modifier.testTag("trackDelete:${t.name}"),
        )
    }
}

/** The shortest a trim makes a clip (a clip that is already shorter keeps its length as the limit). */
private const val MIN_TRIM = 0.5f

/**
 * [coerceIn] that never throws: when the range is empty (a clip or caption shorter than the minimum, next to its
 * neighbour) the lower bound wins – the edge stays where it may be.
 */
internal fun Float.coerceInSafe(lo: Float, hi: Float): Float = coerceIn(lo, maxOf(lo, hi))

/**
 * The caption lane's rows: the caption tracks that have captions, in the tracks' order (one row – the whole lane – when
 * only one has). Captions of several tracks at the same time were drawn on each other.
 */
internal fun captionRows(state: EditorState): List<Long> {
    val used = state.captions.map { it.track }.distinct()
    if (used.size < 2) return used
    val order = state.props?.captionTracks?.map { it.id }.orEmpty()
    return used.sortedBy { t -> order.indexOf(t).let { if (it < 0) Int.MAX_VALUE else it } }
}
