package se.gendut.klipp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/*
 * Simple mode's clip strip (first stage of the beginner editor, docs/simple-mode.md): the film as one row of clips –
 * the storyline (V1) – in place of the track timeline. It is a view, not a model: the clips are the editor's, a trim is
 * the magnetic timeline's own trim ([magTrim], one engine group = one undo step), and nothing here changes what simple
 * mode can't show (connected clips, other tracks, effects follow their clips as in advanced mode).
 */

/** What the strip shows and lets change – pure, so it is tested without a screen. */
object SimpleStripModel {
    /** The shortest a trim leaves a clip (s). */
    const val MIN_LEN = 0.1f

    /** The storyline: the clips on V1, in time order. */
    fun storyline(state: EditorState): List<Clip> {
        val v1 = state.tracks.videoRow(0)
        return state.clips.filter { it.track == v1 && it.kind.track == TrackKind.VIDEO }.sortedBy { it.start }
    }

    /** What a row under the clips holds: video over the film, titles, sound and music, or the captions. */
    enum class LaneKind { OVERLAY, TEXT, SOUND, CAPTIONS }

    /**
     * A row under the clips: one track's clips ([track] its row) – or the captions ([track] −1). Every track with
     * something on it is a row of its own, in the advanced timeline's order (the highest video track first, then A1, A2
     * …), so what simple mode shows is the project's own tracks.
     */
    data class Lane(val track: Int, val kind: LaneKind, val clips: List<Clip>, val captions: List<UiCaption> = emptyList(), val newLevel: Int = 1)

    /** [Lane.track] of the row offered while a video clip is dragged: a new layer (over all the others, or over the storyline). */
    const val NEW_LAYER = -2

    /**
     * The rows under the clips, as the simpler editors show their layers: a row per track with clips on it (its video,
     * titles, or the sound that isn't a video clip's own – that is part of its clip), then the captions. Empty tracks
     * are left out (they stay in the project).
     */
    fun lanes(state: EditorState): List<Lane> {
        val v1 = state.tracks.v1Row
        val links = state.clips.filter { it.kind.track == TrackKind.VIDEO }.mapNotNull { it.link }.toSet()
        val out = ArrayList<Lane>()
        for (row in state.tracks.indices) {
            if (row == v1) continue
            val cs = state.clips.filter { it.track == row && (it.kind.track == TrackKind.VIDEO || it.link == null || it.link !in links) }
                .sortedBy { it.start }
            if (cs.isEmpty()) continue
            val kind = when {
                state.tracks[row].kind == TrackKind.AUDIO -> LaneKind.SOUND
                cs.all { it.kind == ClipKind.TITLE } -> LaneKind.TEXT
                else -> LaneKind.OVERLAY
            }
            out += Lane(row, kind, cs)
        }
        val caps = state.captions.filter { !it.hidden }.sortedBy { it.start }
        if (caps.isNotEmpty()) out += Lane(-1, LaneKind.CAPTIONS, emptyList(), caps)
        return out
    }

    /** Clips the strip doesn't show at all: not in the storyline or a row, and not a shown video clip's own sound. */
    fun others(state: EditorState): Int {
        val shown = storyline(state) + lanes(state).flatMap { it.clips }
        val ids = shown.map { it.id }.toSet()
        val links = shown.filter { it.kind.track == TrackKind.VIDEO }.mapNotNull { it.link }.toSet()
        return state.clips.count { it.id !in ids && (it.link == null || it.link !in links) }
    }

    /** Clip [c] can go on row [lane] (its own kind of track; video also into the storyline, [main]). */
    fun fits(state: EditorState, c: Clip, lane: Lane?, main: Boolean): Boolean = when {
        main -> c.kind == ClipKind.VIDEO
        lane == null || lane.kind == LaneKind.CAPTIONS -> false
        lane.track == NEW_LAYER -> c.kind.track == TrackKind.VIDEO
        else -> state.tracks.getOrNull(lane.track)?.kind == c.kind.track
    }

    /** The magnetic model's lane for a drop on [lane] ([main]: the storyline, 0). */
    fun levelOf(state: EditorState, lane: Lane?, main: Boolean): Int = when {
        main -> 0
        lane == null -> 0
        lane.track == NEW_LAYER -> lane.newLevel
        else -> state.tracks.engineIndex(lane.track)
    }

    /**
     * Music and sound moved, trimmed or added in simple mode stay where they are put in the film – they don't hang on the
     * clip under them (a tune that jumps when a clip earlier in the film is shortened is what a beginner doesn't
     * expect). Kept as the magnetic timeline's "disconnected", part of the edit (not an undo step of its own).
     */
    fun pinInTime(state: EditorState, ids: Collection<Long>) {
        val s = state.magSide
        if (ids.isEmpty() || s.disconnected.containsAll(ids)) return
        state.magSide = s.copy(disconnected = s.disconnected + ids)
        state.props?.requestSave()
    }

    /**
     * Moves [c] to start near [toStart] on the magnetic model's lane [level] of its kind, as one undo step: into the
     * storyline (0, at the nearest edit point – what follows makes room), out of it (what follows closes up), or to
     * another place or track as a connected clip (stacked onto a free track when that one is taken).
     */
    fun moveTo(state: EditorState, c: Clip, toStart: Float, level: Int): Boolean {
        if (moveBlock(state, c) != null) return false
        if (c.kind.track == TrackKind.AUDIO) pinInTime(state, listOf(c.id))
        return state.magEdit(Str.t(R.string.projectcontroller_undo_move)) { it.move(c.id, toStart.coerceAtLeast(0f), level, state.keepConnected) }
    }

    /** The storyline's edit point nearest to [t] (where a clip dropped there goes in). */
    fun insertPoint(line: List<Clip>, t: Float): Float =
        (listOf(0f) + line.flatMap { listOf(it.start, it.end) }).minByOrNull { abs(it - t) } ?: 0f

    /** How long a title or an adjustment clip can be made at most (s): it has no file to run out of. */
    const val FREE_TAIL = 600f

    /** A clip whose trimmed-off material can be brought back (a plain video or sound clip: not reversed, not a freeze frame). */
    fun canExtend(c: Clip): Boolean =
        (c.kind == ClipKind.VIDEO || c.kind == ClipKind.AUDIO || c.kind == ClipKind.MUSIC) && !c.reverse && !c.hold && c.srcLen > 0f

    private fun speed(c: Clip) = abs(c.speed).coerceAtLeast(1e-4f)

    /** Timeline seconds trimmed off the clip's start that can be brought back. */
    fun hiddenHead(c: Clip): Float = if (canExtend(c)) (c.srcIn / speed(c)).coerceAtLeast(0f) else 0f

    /** Timeline seconds after the clip's end, up to the file's end, that can be brought back. */
    fun hiddenTail(c: Clip): Float = when {
        canExtend(c) -> ((c.srcLen - c.srcIn) / speed(c) - c.length).coerceAtLeast(0f)
        // a title or an adjustment clip: as long as wanted
        c.kind == ClipKind.TITLE || c.kind == ClipKind.ADJUST -> (FREE_TAIL - c.length).coerceAtLeast(0f)
        else -> 0f
    }

    /**
     * A trim of [c]'s edge held to what the file has and to [MIN_LEN]: the start edge's [delta] > 0 cuts off, < 0
     * brings back; the end edge's > 0 lengthens, < 0 cuts off (the magnetic timeline's convention, [MagTimeline.trim]).
     */
    fun clamp(c: Clip, startEdge: Boolean, delta: Float): Float =
        if (startEdge) delta.coerceInSafe(-hiddenHead(c), c.length - MIN_LEN)
        else delta.coerceInSafe(-(c.length - MIN_LEN), hiddenTail(c))

    /** The clip's length after the trim. */
    fun lengthAfter(c: Clip, startEdge: Boolean, delta: Float): Float = if (startEdge) c.length - delta else c.length + delta

    /** Why simple mode leaves an edge or a clip alone (null = it can be trimmed / moved). */
    enum class Block { TRANSITION, LOCKED, MOVE_TRANSITION }

    fun block(state: EditorState, c: Clip, startEdge: Boolean): Block? {
        if (state.locked(c.track)) return Block.LOCKED
        val edge = if (startEdge) c.start else c.end
        // a transition at the edge: the engine's from/to, or (without them) one on the track over the edge. A sound's
        // own fade in or out (nothing on its other side, e.g. *Fade out at the end*) goes with its edge: not a block
        val sound = state.tracks.getOrNull(c.track)?.kind == TrackKind.AUDIO
        val at = state.transitions.any { t ->
            (if (startEdge) t.to == c.id && !(sound && t.from == null) else t.from == c.id && !(sound && t.to == null)) ||
                (t.from == null && t.to == null && t.track == c.track && edge >= t.start - 1e-3f && edge <= t.end + 1e-3f)
        }
        return if (at) Block.TRANSITION else null
    }

    /** Why [c] can't be moved in simple mode: its track is locked, or a transition joins it to a neighbour. */
    fun moveBlock(state: EditorState, c: Clip): Block? = when {
        state.locked(c.track) -> Block.LOCKED
        block(state, c, true) == Block.TRANSITION || block(state, c, false) == Block.TRANSITION -> Block.MOVE_TRANSITION
        else -> null
    }

    /**
     * Where [c] goes back in when dropped with its start at [toStart]: the magnetic timeline takes it out, closes up and
     * puts it back at the storyline's edit point nearest to [toStart] ([MagTimeline.move]). That edit point, in the
     * strip as it is now (with [c] still in it), for the marker shown while dragging.
     */
    fun dropPoint(line: List<Clip>, c: Clip, toStart: Float): Float {
        val rest = line.filter { it.id != c.id }
        // the storyline closed up without the clip: what came after it moves back by its length
        val shut = rest.map { if (it.start >= c.end - 1e-3f) it.start - c.length to it.end - c.length else it.start to it.end }
        val points = (listOf(0f) + shut.flatMap { listOf(it.first, it.second) }).distinct()
        val b = points.minByOrNull { abs(it - toStart) } ?: 0f
        // back to the strip as shown (with the clip): points after its old place lie its length later
        return if (b > c.start + 1e-3f) b + c.length else b
    }

    /** Moves [c] so that it starts near [toStart] (the nearest edit point), as one undo step; false = nothing changed. */
    fun move(state: EditorState, c: Clip, toStart: Float): Boolean {
        if (moveBlock(state, c) != null) return false
        return state.magMove(c, toStart, c.track, position = false)
    }

    /** One place earlier ([steps] -1) or later (+1) in the storyline (the screen reader's move). */
    fun moveBy(state: EditorState, c: Clip, steps: Int): Boolean {
        val line = storyline(state)
        val i = line.indexOfFirst { it.id == c.id }
        val other = line.getOrNull(i + steps) ?: return false
        // earlier: where the clip before starts; later: after the next one, once the clip is out (the next starts where it did)
        return move(state, c, if (steps < 0) other.start else c.start + other.length)
    }

    /** Trims [c]'s edge by [delta] (clamped) as one undo step; false = nothing changed. */
    fun trim(state: EditorState, c: Clip, startEdge: Boolean, delta: Float): Boolean {
        if (block(state, c, startEdge) != null) return false
        val d = clamp(c, startEdge, delta)
        if (abs(d) < 1e-4f) return false
        if (c.kind.track == TrackKind.AUDIO) pinInTime(state, listOf(c.id))
        return state.magTrim(c.id, startEdge, d)
    }

    /** Brings back everything trimmed off [c]'s start (or end), as one undo step (a clip from a file only). */
    fun restore(state: EditorState, c: Clip, startEdge: Boolean): Boolean = canExtend(c) &&
        if (startEdge) trim(state, c, true, -hiddenHead(c)) else trim(state, c, false, hiddenTail(c))

    /** m:ss (a clip under 10 s also with tenths, "0:06.4" – the decimal mark of the language). */
    fun clock(sec: Float): String {
        val s = sec.coerceAtLeast(0f)
        val whole = s.toInt()
        val base = "%d:%02d".format(whole / 60, whole % 60)
        return if (s < 10f) base + java.text.DecimalFormatSymbols.getInstance().decimalSeparator + ((s - whole) * 10).toInt().coerceIn(0, 9) else base
    }
}

/** The strip: the storyline's clips with their frames, the playhead, selection and the selected clip's trim handles. */
@Composable
internal fun SimpleStrip(state: EditorState, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val line = SimpleStripModel.storyline(state)
    val others = SimpleStripModel.others(state)
    val total = maxOf(state.clips.maxOfOrNull { it.end } ?: 0f, 1f)
    val lanes = SimpleStripModel.lanes(state)
    val laneClips = lanes.flatMap { it.clips }
    // (a clip in the storyline or on a row: its handles and the line over the strip)
    val selected = line.firstOrNull { it.id == state.selected } ?: laneClips.firstOrNull { it.id == state.selected }
    var blocked by remember { mutableStateOf<SimpleStripModel.Block?>(null) }
    // the edge being dragged and how far (clamped), for the live feedback; null = no drag
    var dragEdge by remember { mutableStateOf<Boolean?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    // where the playhead goes once a trim has landed: the monitor seeks when the playhead changes, so it moves only
    // after the edit (set before, the seek ran on the old timeline and the picture stayed from it)
    var landing by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.clips) { landing?.let { state.playhead = it; landing = null } }
    // a delete (the bar's Delete): done at once – the timeline's removal animation, which finishes a delete, isn't here
    LaunchedEffect(Unit) { snapshotFlow { state.removing.toList() }.collect { ids -> ids.forEach { state.finishRemove(it) } } }
    // a clip held and dragged to a new place (the long press starts it), and how far
    var movingId by remember { mutableStateOf<Long?>(null) }
    // a swipe down cutting a clip: where (the red line)
    var knife by remember { mutableStateOf<KnifeMark?>(null) }
    var movingDx by remember { mutableFloatStateOf(0f) }
    var movingDy by remember { mutableFloatStateOf(0f) }
    // where on the clip it was taken hold of (px from its start): for the scroll at the strip's edge
    var movingGrab by remember { mutableFloatStateOf(0f) }
    // where the finger was in the strip when the clip was taken (px from the strip's top): for the row it is over
    var movingFingerY by remember { mutableFloatStateOf(0f) }
    // the zoom (1 = the whole film in view; negative = not set yet: at least 32 dp a second) and where a pinch is
    // centred (null = keep the playhead in place)
    var zoom by remember { mutableFloatStateOf(-1f) }
    // the zoom in use and the most it can be (worked out with the strip's width; read by the pinch)
    var zoomNow by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(24f) }
    var pinchAt by remember { mutableStateOf<Float?>(null) }
    Column(modifier.fillMaxSize().testTag("simpleStrip")) {
        // what is going on: the drag's result, the selected clip's choices, or how to start
        StripInfo(state, selected, dragEdge, dragDelta, moving = movingId != null, onOpenAdvanced = { state.switchMode(false) },
            // (only when zoomed in past the whole film – a short film fits from the start)
            onOverview = if (zoomNow <= 1.01f) null else ({ zoom = 1f }))
        SimpleTip()
        if (line.isEmpty() && state.clips.isNotEmpty()) {
            // the film is only on other tracks: the strip can't show it – left as it is
            AdvancedOnly(stringResource(R.string.simple_only_other_tracks)) { state.switchMode(false) }
            return@Column
        }
        // the strip takes all the room down to the bar (the rows under the clips are in it): a drag anywhere scrolls it
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).heightIn(min = RULER_H + MAIN_H + LANE_STEP + 8.dp)
                // two fingers: zoom (the time under them – the playhead when it is centred – stays put)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } >= 2) {
                                val z = ev.calculateZoom()
                                if (z != 1f) {
                                    pinchAt = if (state.centerPlayhead) null else ev.calculateCentroid(useCurrent = true).x
                                    // (from the zoom just set: several moves can come before the strip is laid out again)
                                    zoom = ((if (zoom > 0f) zoom else zoomNow) * z).coerceIn(1f, maxZoom)
                                    ev.changes.forEach { it.consume() }
                                }
                            }
                        } while (ev.changes.any { it.pressed })
                        pinchAt = null
                    }
                }
                .testTag("simpleStrip:zoomable")
        ) {
            val viewPx = with(density) { maxWidth.toPx() }
            // the centred playhead: the strip moves under a still line in the middle (half a view of room at each end)
            val centered = state.centerPlayhead
            val side = if (centered) maxWidth / 2 else 24.dp
            val sidePx = with(density) { side.toPx() }
            // zoom 1: the whole film in view, however long (the overview); at first at least 32 dp a second, so clips
            // are big enough to take hold of; zoomed in up to 400 dp a second
            val fit = with(density) { (maxWidth - 48.dp).toPx() } / total
            zoomNow = if (zoom > 0f) zoom else maxOf(1f, with(density) { 32.dp.toPx() } / fit)
            maxZoom = maxOf(1f, with(density) { 400.dp.toPx() } / fit)
            val pps = fit * zoomNow
            val scroll = rememberScrollState()
            // while an edge is dragged the strip holds still: its own scrolling is off, and anything else that moves it
            // meanwhile is undone (on a phone the strip jumped once per trim by about the trimmed length, which moved the
            // centred playhead; seen on the vivo, not on the OnePlus) – and moves no playhead
            val trimming = dragEdge != null
            LaunchedEffect(trimming) {
                if (!trimming) return@LaunchedEffect
                val held = scroll.value
                snapshotFlow { scroll.value }.collect { v -> if (v != held) scroll.scrollTo(held) }
            }
            // a pinch keeps the time under the fingers (or the playhead) where it was on the screen
            var lastPps by remember { mutableFloatStateOf(pps) }
            LaunchedEffect(pps) {
                if (lastPps != pps) {
                    val at = pinchAt
                    val target = if (at == null || centered) state.playhead * pps + (if (centered) 0f else sidePx - viewPx / 2)
                        else ((scroll.value + at - sidePx) / lastPps) * pps + sidePx - at
                    scroll.scrollTo(target.roundToInt().coerceAtLeast(0))
                    lastPps = pps
                }
            }
            if (centered) {
                // scrolling the strip is scrubbing: the time under the line is the playhead
                LaunchedEffect(pps) {
                    snapshotFlow { scroll.value }.collect { v ->
                        if (scroll.isScrollInProgress && !state.playing && !trimming) state.playhead = (v / pps).coerceIn(0f, total)
                    }
                }
                // and the playhead set elsewhere (playing, a tap, Start / End) brings the strip along
                LaunchedEffect(pps) {
                    snapshotFlow { state.playhead }.collect { p -> if (!scroll.isScrollInProgress) scroll.scrollTo((p * pps).roundToInt()) }
                }
            } else {
                // playing: the playhead stays in view
                LaunchedEffect(state.playhead, state.playing) {
                    if (!state.playing) return@LaunchedEffect
                    val x = (sidePx + state.playhead * pps).roundToInt()
                    if (x < scroll.value || x > scroll.value + viewPx - sidePx * 2) scroll.scrollTo((x - viewPx / 3).roundToInt().coerceAtLeast(0))
                }
                // a clip selected out of view (the dock, a search, a test) comes into view
                LaunchedEffect(state.selected, pps) {
                    val c = line.firstOrNull { it.id == state.selected } ?: return@LaunchedEffect
                    val a = sidePx + c.start * pps
                    val b = sidePx + c.end * pps
                    if (a < scroll.value || b > scroll.value + viewPx) scroll.animateScrollTo(((a + b) / 2 - viewPx / 2).roundToInt().coerceAtLeast(0))
                }
            }
            // the rows, in the timeline's order: the video tracks over the storyline (the highest on top – what lies
            // higher covers what is under it, as in the picture), the sound tracks and the captions under it. While a
            // video clip is dragged and nothing lies over the film yet, a row to drop it on opens just over the storyline.
            val moving = (line + laneClips).firstOrNull { it.id == movingId }
            val videoLanes = lanes.filter { l -> l.track >= 0 && state.tracks.getOrNull(l.track)?.kind == TrackKind.VIDEO }
            // a new layer while a video clip (or a title) is dragged: a place to drop it at the strip's top, over the
            // ruler – over all the layers (so a layer can be laid over another), or just over the storyline when there
            // are none. (Nothing moves to make room: a row opening under the finger put the clip over another row.)
            val hasLayers = lanes.any { it.kind == SimpleStripModel.LaneKind.OVERLAY }
            val topLevel = (videoLanes.maxOfOrNull { state.tracks.engineIndex(it.track) } ?: 0) + 1
            fun offersNew(m: Clip?) = m != null && m.kind.track == TrackKind.VIDEO && topLevel < MAX_VIDEO_TRACKS
            val offerNew = offersNew(moving)
            val newRow = SimpleStripModel.Lane(SimpleStripModel.NEW_LAYER, SimpleStripModel.LaneKind.OVERLAY, emptyList(), newLevel = if (hasLayers) topLevel else 1)
            val above = videoLanes
            val under = lanes.filter { it !in above }
            val mainTop = RULER_H + LANE_STEP * above.size
            val underTop = mainTop + MAIN_H + 8.dp
            fun laneY(lane: SimpleStripModel.Lane): androidx.compose.ui.unit.Dp {
                val a = above.indexOf(lane)
                return if (a >= 0) RULER_H + LANE_STEP * a else underTop + LANE_STEP * under.indexOf(lane)
            }
            // all the rows, however many: the strip scrolls up and down when they don't fit
            val contentH = maxOf(maxHeight, underTop + LANE_STEP * under.size)
            // (the playhead's line runs down through the rows)
            val lineH = contentH - 22.dp
            val rulerPx = with(density) { RULER_H.toPx() }
            val mainTopPx = with(density) { mainTop.toPx() }
            val underTopPx = with(density) { underTop.toPx() }
            val laneStepPx = with(density) { LANE_STEP.toPx() }
            val edgePx = with(density) { 6.dp.toPx() }
            val mainHPx = with(density) { MAIN_H.toPx() }
            // where a dragged clip would go with the finger at [fy]: into the storyline, onto a new layer (the strip's
            // top), or onto the row under the finger (over or under the storyline). Worked out again when it is dropped:
            // the finger can move on before the strip is drawn again
            fun overMainAt(fy: Float, m: Clip?) = m != null && fy >= mainTopPx - edgePx && fy < mainTopPx + mainHPx + edgePx
            fun overNewAt(fy: Float, m: Clip?) = offersNew(m) && fy < (if (above.isEmpty()) mainTopPx - edgePx else rulerPx)
            fun overLaneAt(fy: Float, m: Clip?): SimpleStripModel.Lane? = when {
                m == null || overMainAt(fy, m) -> null
                overNewAt(fy, m) -> newRow
                fy < mainTopPx -> above.getOrNull(((fy - rulerPx) / laneStepPx).toInt().coerceIn(0, (above.size - 1).coerceAtLeast(0)))
                else -> under.getOrNull(((fy - underTopPx) / laneStepPx).toInt().coerceAtLeast(0))
            }
            // the magnet: a clip put on a row snaps its start or end to an edge of any row's clips, or to the playhead
            // (its start and the edge it caught; nothing near: the start as dragged and no edge)
            val snapSec = with(density) { 12.dp.toPx() } / pps
            fun edgesBut(c: Clip, playhead: Boolean) = (listOf(0f) + (if (playhead) listOf(state.playhead) else emptyList()) +
                (line + laneClips).filter { it.id != c.id && (c.link == null || it.link != c.link) }.flatMap { listOf(it.start, it.end) })
            fun snapOf(m: Clip, ts: Float): Pair<Float, Float?> {
                var best: Float = ts
                var bestD = snapSec
                var edge: Float? = null
                for (e in edgesBut(m, true)) {
                    if (abs(ts - e) < bestD) { bestD = abs(ts - e); best = e; edge = e }
                    if (abs(ts + m.length - e) < bestD) { bestD = abs(ts + m.length - e); best = e - m.length; edge = e }
                }
                return best.coerceAtLeast(0f) to edge
            }
            val fingerY = movingFingerY + movingDy
            val overMain = overMainAt(fingerY, moving)
            val overNew = overNewAt(fingerY, moving)
            val overLane = overLaneAt(fingerY, moving)
            val targetOk = moving != null && SimpleStripModel.fits(state, moving, overLane, overMain)
            val toStart = moving?.let { it.start + movingDx / pps }
            val snap = if (moving == null || toStart == null || overMain || !targetOk) null else snapOf(moving, toStart)
            val snapStart = snap?.first
            val snapEdge = snap?.second
            // a clip dropped: to another place on its row (the storyline's own reorder), or onto another row
            fun drop(id: Long, cancelled: Boolean) {
                val dx = if (cancelled) 0f else movingDx
                val fy = movingFingerY + movingDy
                movingId = null; movingDx = 0f; movingDy = 0f
                // (the clip as it is now – not as its tile was drawn)
                val now = state.clips.firstOrNull { it.id == id } ?: return
                if (cancelled) return
                val main = overMainAt(fy, now)
                val lane = overLaneAt(fy, now)
                val ok = SimpleStripModel.fits(state, now, lane, main)
                val inLine = line.any { it.id == id }
                val sameRow = !ok || (main && inLine) || (!main && lane?.track == now.track)
                if (sameRow && abs(dx) <= 1f) return
                val b = SimpleStripModel.moveBlock(state, now)
                if (b != null) { haptics.heavy(); blocked = b; return }
                val raw = now.start + dx / pps
                val at = if (!main && ok) snapOf(now, raw).first else raw
                val done = when {
                    sameRow && inLine -> SimpleStripModel.move(state, now, at)
                    sameRow -> SimpleStripModel.moveTo(state, now, at, state.tracks.engineIndex(now.track))
                    else -> SimpleStripModel.moveTo(state, now, at, SimpleStripModel.levelOf(state, lane, main))
                }
                if (done) haptics.confirm()
            }
            // a clip held near the strip's edge: the strip scrolls that way (the clip stays under the finger)
            LaunchedEffect(movingId, pps) {
                val c = (line + laneClips).firstOrNull { it.id == movingId } ?: return@LaunchedEffect
                val edge = with(density) { 56.dp.toPx() }
                val most = with(density) { 14.dp.toPx() }
                while (movingId == c.id) {
                    val finger = sidePx + c.start * pps + movingDx + movingGrab - scroll.value
                    val step = when {
                        finger > viewPx - edge -> most * ((finger - (viewPx - edge)) / edge).coerceIn(0.2f, 1f)
                        finger < edge -> -most * ((edge - finger) / edge).coerceIn(0.2f, 1f)
                        else -> 0f
                    }
                    val moved = if (step != 0f) scroll.scrollBy(step) else 0f
                    if (moved != 0f) { movingDx += moved; withFrameNanos { } }
                    // nothing to scroll (away from the edges, or at the strip's end): wait for the finger to move
                    else { val at = movingDx; snapshotFlow { movingDx to movingId }.first { it.first != at || it.second != c.id } }
                }
            }
            LaunchedEffect(snapEdge) { if (snapEdge != null) haptics.tick() }
            // (the clip shown where it will land: at the snapped place)
            fun shownAt(c: Clip) = Offset(if (snapStart != null) (snapStart - c.start) * pps else movingDx, movingDy)
            // a trimmed edge snaps the same way (not to the playhead: it follows the edge while trimming)
            fun snapTrim(c: Clip): (Float) -> Float? = { t -> edgesBut(c, false).minByOrNull { abs(it - t) }?.takeIf { abs(it - t) < snapSec } }
            // more rows over the storyline than fit: it starts in view (the rows over it a swipe down away)
            val vscroll = rememberScrollState()
            val viewH = with(density) { maxHeight.toPx() }
            LaunchedEffect(above.size, viewH) {
                val needed = mainTopPx + with(density) { (MAIN_H + 16.dp).toPx() } - viewH
                if (needed > vscroll.value) vscroll.scrollTo(needed.roundToInt())
            }
            // where each layer hangs on the storyline: a pin and a thin line to the clip (music added here has none –
            // it stays where it is in the film)
            val conns = remember(state.clips, state.magSide, state.tracks) {
                runCatching { state.mag().connections() }.getOrDefault(emptyList()).filter { it.parent != null }.associateBy { it.clip }
            }
            // (the add tile after the last clip)
            val contentW = with(density) { (total * pps).toDp() } + side * 2 + (if (centered) 0.dp else 88.dp)
            Box(Modifier.fillMaxSize().verticalScroll(vscroll).testTag("simpleStrip:rows")) {
            Box(Modifier.fillMaxWidth().height(contentH).horizontalScroll(scroll, enabled = !trimming)) {
                Box(
                    Modifier.width(contentW).fillMaxHeight()
                        // a tap beside the clips: nothing stays selected; in the ruler and the storyline's row the
                        // playhead goes there too (elsewhere, in the black, the tap only deselects)
                        .pointerInput(pps, sidePx, mainTopPx) {
                            detectTapGestures { o ->
                                state.playing = false
                                state.clearSelection()
                                if (o.y < rulerPx || (o.y >= mainTopPx && o.y < mainTopPx + MAIN_H.toPx()))
                                    state.playhead = ((o.x - sidePx) / pps).coerceIn(0f, total)
                            }
                        }
                        .testTag("simpleStrip:track")
                ) {
                    // the time ruler over the clips: drag along it to move the playhead through the film
                    Ruler(state, pps, sidePx, total, scrub = !centered)
                    // the pins (under the clips): on the row's edge towards the storyline, a line to it
                    Canvas(Modifier.fillMaxSize()) {
                        for (lane in above + under) for (c in lane.clips) {
                            if (c.id == movingId) continue
                            val cn = conns[c.id] ?: continue
                            val x = sidePx + cn.at * pps
                            val over = lane in above
                            val y = if (over) (laneY(lane) + LANE_H).toPx() else laneY(lane).toPx()
                            val to = if (over) mainTopPx else mainTopPx + MAIN_H.toPx()
                            drawLine(K.OnSurfaceVar.copy(alpha = 0.5f), Offset(x, y), Offset(x, to), 1.dp.toPx())
                            drawCircle(K.OnSurface, 3.5.dp.toPx(), Offset(x, y))
                        }
                    }
                    // the row a dragged clip will land on
                    if (moving != null && overLane != null && overLane !== newRow && targetOk) {
                        val y = laneY(overLane)
                        Box(
                            Modifier.offset { IntOffset(0, y.roundToPx()) }.width(contentW).height(LANE_H)
                                .background(K.Primary.copy(alpha = 0.10f)).testTag("simpleLane:target")
                        )
                    }
                    val count = line.size
                    line.forEachIndexed { i, c -> key(c.id) {
                        StripClip(state, c, i, count, pps, side, mainTop, c.id == state.selected,
                            dragEdge.takeIf { c.id == state.selected }, if (c.id == state.selected) dragDelta else 0f,
                            moving = if (movingId == c.id) shownAt(c) else null,
                            onMoveStart = { grab ->
                                movingId = c.id; movingDx = 0f; movingDy = 0f; movingGrab = grab.x
                                movingFingerY = mainTopPx + grab.y
                            },
                            onMove = { d -> movingDx += d.x; movingDy += d.y },
                            onDrop = { cancelled -> drop(c.id, cancelled) },
                            onKnife = { t -> knife = if (t != null) KnifeMark(t, mainTop, MAIN_H, true) else knife?.copy(held = false) },
                        )
                    } }
                    // the rows: each track's clips (a tap selects, hold and drag moves – along the row or to another)
                    // (keyed: a row opening over the others must not make the dragged clip's tile anew – its drag would end)
                    for (lane in above + under) key(lane.track) {
                        val y = laneY(lane)
                        when {
                            lane.kind == SimpleStripModel.LaneKind.CAPTIONS -> for (cap in lane.captions) CaptionTile(state, cap, pps, sidePx, y)
                            else -> {
                                for (c in lane.clips) key(c.id) { LaneClip(
                                    state, c, pps, sidePx, y, c.id == state.selected,
                                    dragEdge.takeIf { c.id == state.selected }, if (c.id == state.selected) dragDelta else 0f,
                                    moving = if (movingId == c.id) shownAt(c) else null,
                                    onMoveStart = { grab ->
                                        movingId = c.id; movingDx = 0f; movingDy = 0f; movingGrab = grab.x
                                        movingFingerY = with(density) { y.toPx() } + grab.y
                                    },
                                    onMove = { d -> movingDx += d.x; movingDy += d.y },
                                    onDrop = { cancelled -> drop(c.id, cancelled) },
                                    onKnife = { t -> knife = if (t != null) KnifeMark(t, y, LANE_H, true) else knife?.copy(held = false) },
                                ) }
                                LaneMarks(state, lane, y, with(density) { scroll.value.toDp() }) { b -> haptics.heavy(); blocked = b }
                            }
                        }
                    }
                    // a swipe down's cut: the red line on the row (as the timeline's knife)
                    KnifeLine(knife, pps, sidePx)
                    // the new layer's place, over the ruler (lit when the finger is over it)
                    if (offerNew) NewLayerRow(with(density) { scroll.value.toDp() }, with(density) { viewPx.toDp() }, overNew)
                    // after the last clip: add more (the media import, as the bar's Add)
                    if (!centered) state.onAddAction?.let { add ->
                        val addDesc = stringResource(R.string.simple_add_clips)
                        Box(
                            Modifier.offset { IntOffset((sidePx + total * pps + 8.dp.toPx()).roundToInt(), mainTop.roundToPx()) }
                                .size(MAIN_H).clip(RoundedCornerShape(12.dp)).background(K.SurfaceHigh)
                                .clickable(onClickLabel = addDesc, role = Role.Button) { haptics.press(); add(AddAction.MEDIA) }
                                .semantics { contentDescription = addDesc }.testTag("simpleStrip:add"),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Ic.Plus, null, tint = K.OnSurface, modifier = Modifier.size(28.dp)) }
                    }
                    // where a clip dropped into the storyline goes in: a lime line at the edit point nearest its start
                    if (moving != null && overMain && targetOk && toStart != null) {
                        val at = if (line.any { it.id == moving.id }) SimpleStripModel.dropPoint(line, moving, toStart)
                            else SimpleStripModel.insertPoint(line, toStart)
                        Box(
                            Modifier.offset { IntOffset((sidePx + at * pps - 2.dp.toPx()).roundToInt(), (mainTop - 4.dp).roundToPx()) }
                                .zIndex(3f).width(4.dp).height(MAIN_H + 8.dp).clip(RoundedCornerShape(2.dp)).background(K.Primary).testTag("simpleStrip:dropPoint")
                        )
                    }
                    // a layer dragged along: the storyline clip it will hang on lights up
                    if (moving != null && !overMain && targetOk && toStart != null && moving.kind.track == TrackKind.VIDEO) {
                        line.firstOrNull { it.id != moving.id && toStart >= it.start - 1e-3f && toStart < it.end }?.let { a ->
                            Box(
                                Modifier.offset { IntOffset((sidePx + a.start * pps).roundToInt(), mainTop.roundToPx()) }.zIndex(1f)
                                    .width(with(density) { (a.length * pps).toDp() }).height(MAIN_H)
                                    .border(2.dp, K.Primary.copy(alpha = 0.8f), RoundedCornerShape(12.dp)).testTag("simpleStrip:anchor")
                            )
                        }
                    }
                    // the magnet's guide: a lime line through every row at the edge the clip snapped to
                    snapEdge?.let { e ->
                        Box(
                            Modifier.offset { IntOffset((sidePx + e * pps - 1.dp.toPx()).roundToInt(), rulerPx.roundToInt()) }.zIndex(5f)
                                .width(2.dp).height(contentH - RULER_H).background(K.Primary).testTag("simpleStrip:snap")
                        )
                    }
                    // gaps in the storyline: dashed (the magnetic timeline's gap clips; trimmed in advanced mode for now)
                    var t = 0f
                    for (c in line) {
                        if (c.start - t > 0.05f) StripGap(t, c.start - t, pps, side, mainTop)
                        t = maxOf(t, c.end)
                    }
                    if (selected != null && movingId == null) {
                        // the handles on the clip's edges: in the storyline, or on its row
                        val inLine = line.any { it.id == selected.id }
                        val lane = (above + under).firstOrNull { l -> l.clips.any { it.id == selected.id } }
                        if (inLine || lane != null) for (startEdge in listOf(true, false)) TrimHandle(
                            state, selected, startEdge, pps, side,
                            top = if (inLine || lane == null) mainTop - 6.dp else laneY(lane) - 4.dp,
                            height = if (inLine || lane == null) MAIN_H + 12.dp else LANE_H + 8.dp,
                            snap = snapTrim(selected),
                            onBlocked = { b -> haptics.heavy(); blocked = b },
                            onDrag = { d -> dragEdge = startEdge; dragDelta = d },
                            onEnd = { dragEdge = null; dragDelta = 0f },
                            onLanded = { t -> landing = t },
                        )
                    }
                    if (!centered) Playhead(state, pps, side, total, with(density) { contentW.toPx() }, lineH)
                }
            }
            }
            // the centred playhead: a still line in the middle with the time over it
            if (centered) CenterPlayhead(state, total, maxHeight - 22.dp, Modifier.align(Alignment.TopCenter))
        }
        if (others > 0 && line.isNotEmpty()) Text(
            pluralStringResource(R.plurals.simple_others_follow, others, others),
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("simpleStrip:others"),
        )
    }
    blocked?.let { b ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text(stringResource(if (b == SimpleStripModel.Block.LOCKED) R.string.simple_blocked_locked_title else R.string.simple_blocked_transition_title)) },
            text = { Text(stringResource(when (b) {
                SimpleStripModel.Block.TRANSITION -> R.string.simple_blocked_transition
                SimpleStripModel.Block.MOVE_TRANSITION -> R.string.simple_blocked_move_transition
                SimpleStripModel.Block.LOCKED -> R.string.simple_blocked_locked
            })) },
            confirmButton = { PillButton(stringResource(R.string.simple_open_advanced), { blocked = null; state.switchMode(false) }, Modifier.testTag("simpleStrip:openAdvanced")) },
            dismissButton = { PillButton(stringResource(android.R.string.cancel), { blocked = null }, filled = false) },
        )
    }
}

/** The line over the strip: the trim's result while dragging, the selected clip's length and choices, or a hint. */
@Composable
private fun StripInfo(
    state: EditorState, selected: Clip?, dragEdge: Boolean?, dragDelta: Float, moving: Boolean, onOpenAdvanced: () -> Unit,
    /** Zoomed in: back to the whole film (null = it is in view). */
    onOverview: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            when {
                moving -> Text(stringResource(R.string.simple_move_hint), color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("simpleStrip:moveInfo"))
                selected != null && dragEdge != null -> Column {
                    val after = SimpleStripModel.lengthAfter(selected, dragEdge, dragDelta)
                    Text(
                        stringResource(R.string.simple_trim_left, SimpleStripModel.clock(after), SimpleStripModel.clock(selected.length)),
                        color = K.OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("simpleStrip:trimInfo"),
                    )
                    // what letting go does (the storyline closes up; what is cut off can be brought back)
                    Text(stringResource(if (selected.track == state.tracks.v1Row) R.string.simple_trim_release else R.string.simple_trim_release_row),
                        color = K.OnSurfaceVar, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.testTag("simpleStrip:releaseHint"))
                }
                selected != null -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val head = SimpleStripModel.hiddenHead(selected)
                    val tail = SimpleStripModel.hiddenTail(selected)
                    val file = SimpleStripModel.canExtend(selected)
                    val canStart = file && head > 0.05f && SimpleStripModel.block(state, selected, true) == null
                    val canEnd = file && tail > 0.05f && SimpleStripModel.block(state, selected, false) == null
                    if (!canStart && !canEnd) Text(stringResource(R.string.simple_trim_hint), color = K.OnSurfaceVar, fontSize = 13.sp)
                    if (canStart) PillButton(stringResource(R.string.simple_trim_restore_start, SimpleStripModel.clock(head)),
                        { SimpleStripModel.restore(state, selected, true) }, Modifier.testTag("simpleStrip:restoreStart"), filled = false)
                    if (canEnd) PillButton(stringResource(R.string.simple_trim_restore_end, SimpleStripModel.clock(tail)),
                        { SimpleStripModel.restore(state, selected, false) }, Modifier.testTag("simpleStrip:restoreEnd"), filled = false)
                }
                else -> Text(stringResource(R.string.simple_strip_hint), color = K.OnSurfaceVar, fontSize = 13.sp)
            }
        }
        // zoomed in: the whole film again in one tap (pinching out does the same)
        if (onOverview != null) {
            val d = stringResource(R.string.simple_overview)
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(K.SurfaceHigh)
                    .clickable(onClickLabel = d, role = Role.Button) { onOverview() }.semantics { contentDescription = d }.testTag("simpleStrip:overview"),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Fullscreen, null, tint = K.OnSurface, modifier = Modifier.size(20.dp)) }
        }
        // the way to advanced mode, always in sight (the same switch as the ⋯ menu and Settings)
        PillButton(stringResource(R.string.simple_advanced_button), onOpenAdvanced, Modifier.testTag("simpleStrip:advanced"), filled = false)
    }
}

/** One storyline clip: its frames, name, selection frame and – while its edge is dragged – what goes or comes back. */
@Composable
private fun StripClip(
    state: EditorState, c: Clip, index: Int, count: Int, pps: Float, side: androidx.compose.ui.unit.Dp,
    top: androidx.compose.ui.unit.Dp, selected: Boolean, dragEdge: Boolean?, dragDelta: Float,
    moving: Offset?, onMoveStart: (Offset) -> Unit, onMove: (Offset) -> Unit, onDrop: (cancelled: Boolean) -> Unit,
    onKnife: (Float?) -> Unit = {},
) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val knife by rememberUpdatedState(onKnife)
    // (the gesture outlives a composition: it calls the newest callbacks – the drop reads the row under the finger)
    val start by rememberUpdatedState(onMoveStart)
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val x = with(density) { side.toPx() } + c.start * pps + (moving?.x ?: 0f)
    val earlier = stringResource(R.string.simple_move_earlier)
    val later = stringResource(R.string.simple_move_later)
    val w = with(density) { (c.length * pps).toDp() }
    val desc = stringResource(R.string.simple_clip_desc, index + 1, count, c.name, SimpleStripModel.clock(c.length))
    val uri = state.uriOfItem?.invoke(c.item)
    Box(
        Modifier.offset { IntOffset(x.roundToInt(), with(density) { (if (moving != null) (top - 8.dp).toPx() + moving.y else top.toPx()).roundToInt() }) }
            .zIndex(if (moving != null) 4f else 0f)
            .width(w).height(MAIN_H)
            .then(if (moving != null) Modifier.shadow(12.dp, RoundedCornerShape(12.dp)) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(c.kind.bg)
            .then(if (selected) Modifier.border(3.dp, K.Primary, RoundedCornerShape(12.dp)) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = desc; this.selected = selected; role = Role.Button
                customActions = listOf(
                    CustomAccessibilityAction(earlier) { SimpleStripModel.moveBy(state, c, -1) },
                    CustomAccessibilityAction(later) { SimpleStripModel.moveBy(state, c, 1) },
                )
            }
            // hold and drag: the clip to another place in the film (dropped, it goes in at the nearest edit point)
            .pointerInput(c.id, pps) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o -> haptics.heavy(); state.playing = false; state.tapSelect(c.id); start(o) },
                    onDrag = { ch, d -> ch.consume(); move(d) },
                    onDragEnd = { drop(false) },
                    // (cancelled: the clip goes back – nothing moves)
                    onDragCancel = { drop(true) },
                )
            }
            .clickable {
                haptics.press(); state.playing = false; state.tapSelect(c.id)
                // the picture shows the clip chosen: the playhead into it when it stands outside (at the film's end the
                // monitor was black)
                if (state.playhead < c.start || state.playhead >= c.end) state.playhead = c.start
            }
            .swipeCut(state, c, pps, haptics) { knife(it) }
            .testTag("simpleClip:${c.id}")
    ) {
        if (uri != null) Filmstrip(c, uri, { liveSourceSpan(c.srcIn, c.length, c.speed, c.reverse, c.hold, c.length, 0f) }, { pps }, Modifier.fillMaxSize())
        Text(c.name, color = c.kind.fg, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(6.dp))
        // what was made for the clip in advanced mode (kept and still in the film): the mark the timeline shows too
        BadgeMark(state.badgesOf(c, LocalGradeState.current), Modifier.align(Alignment.TopEnd).padding(4.dp).testTag("badgeMark:${c.id}"))
        // the part a drag cuts off: striped
        if (dragEdge != null) {
            val cut = when {
                dragEdge && dragDelta > 0f -> 0f to dragDelta
                !dragEdge && dragDelta < 0f -> c.length + dragDelta to c.length
                else -> null
            }
            if (cut != null) Canvas(Modifier.fillMaxSize().testTag("simpleClip:cut")) {
                val a = cut.first * pps
                val b = cut.second * pps
                drawRect(Color.Black.copy(alpha = 0.55f), Offset(a, 0f), androidx.compose.ui.geometry.Size(b - a, size.height))
                val step = 10.dp.toPx()
                var s = a - size.height
                while (s < b) {
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(maxOf(a, s), (s - maxOf(a, s)) + size.height), Offset(minOf(b, s + size.height), size.height - (minOf(b, s + size.height) - s)), 2.dp.toPx())
                    s += step
                }
            }
            // what goes, said on it when there is room: "Cut away 0:05"
            if (cut != null && (cut.second - cut.first) * pps >= with(density) { 56.dp.toPx() }) Text(
                stringResource(R.string.simple_trim_cut_away, SimpleStripModel.clock(cut.second - cut.first)),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.offset { IntOffset((cut.first * pps).roundToInt(), 0) }.width(with(density) { ((cut.second - cut.first) * pps).toDp() })
                    .align(Alignment.CenterStart).testTag("simpleClip:cutLabel"),
            )
        }
    }
    // brought back while dragging: the material outside the clip, as a pale strip beside it
    if (dragEdge != null && ((dragEdge && dragDelta < 0f) || (!dragEdge && dragDelta > 0f))) {
        val extra = abs(dragDelta)
        val gx = if (dragEdge) x - extra * pps else x + c.length * pps
        Box(
            // (over the neighbour: lengthened to the right it lay under the next clip and wasn't seen)
            Modifier.offset { IntOffset(gx.roundToInt(), with(density) { top.roundToPx() }) }.zIndex(2f)
                .width(with(density) { (extra * pps).toDp() }).height(MAIN_H)
                .clip(RoundedCornerShape(12.dp)).background(K.Primary.copy(alpha = 0.25f))
                .border(2.dp, K.Primary.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .testTag("simpleClip:back")
        ) {
            if (uri != null) {
                val from = if (dragEdge) c.srcIn - extra * c.speed else c.srcIn + c.length * c.speed
                Filmstrip(c, uri, { from.toDouble() to (from + extra * c.speed).toDouble() }, { pps }, Modifier.fillMaxSize())
            }
        }
    }
}

/** A gap in the storyline: dashed, not selectable here. */
@Composable
private fun StripGap(start: Float, length: Float, pps: Float, side: androidx.compose.ui.unit.Dp, top: androidx.compose.ui.unit.Dp) {
    val density = LocalDensity.current
    val desc = stringResource(R.string.simple_gap_desc, SimpleStripModel.clock(length))
    Box(
        Modifier.offset { IntOffset((side.toPx() + start * pps).roundToInt(), top.roundToPx()) }
            .width(with(density) { (length * pps).toDp() }).height(MAIN_H)
            .border(1.5.dp, K.Outline.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .semantics { contentDescription = desc }
            .testTag("simpleGap")
    )
}

/**
 * One of the selected clip's trim handles: a lime bar on its edge, 48 dp to touch. A drag shows its result as it goes
 * (the picture at the new edge in the monitor) and becomes one trim when the finger lifts – one undo step. An edge
 * simple mode can't trim safely (a transition, a locked track) says why and offers advanced mode instead.
 */
@Composable
private fun TrimHandle(
    state: EditorState, c: Clip, startEdge: Boolean, pps: Float, side: androidx.compose.ui.unit.Dp,
    top: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp,
    /** The edge's new time snapped to an edge nearby (null = none near). */
    snap: (Float) -> Float? = { null },
    onBlocked: (SimpleStripModel.Block) -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit, onLanded: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val block = SimpleStripModel.block(state, c, startEdge)
    val snapNow by rememberUpdatedState(snap)
    val clipNow by rememberUpdatedState(c)
    val ppsNow by rememberUpdatedState(pps)
    val touch = 48.dp
    val edgeX = with(density) { side.toPx() } + (if (startEdge) c.start else c.end) * pps
    val label = stringResource(if (startEdge) R.string.simple_handle_start else R.string.simple_handle_end, c.name)
    val shorten = stringResource(R.string.simple_a11y_shorten)
    val lengthen = stringResource(R.string.simple_a11y_lengthen)
    // half a second each way, for a screen reader (each one trim, one undo step)
    val step = 0.5f
    Box(
        Modifier.offset { IntOffset((edgeX - touch.toPx() / 2).roundToInt(), top.roundToPx()) }
            .zIndex(3f).size(touch, height)
            .semantics {
                contentDescription = label
                customActions = if (block != null) emptyList() else listOf(
                    CustomAccessibilityAction(shorten) { SimpleStripModel.trim(state, c, startEdge, if (startEdge) step else -step) },
                    CustomAccessibilityAction(lengthen) { SimpleStripModel.trim(state, c, startEdge, if (startEdge) -step else step) },
                )
            }
            // (keyed by the clip's id, not the clip: the clip is read anew while the app updates it – a new file
            // length, a proxy made – and a gesture keyed by the clip itself was cut off mid-drag; the rest of the drag
            // then scrolled the strip, which moved the centred playhead)
            .pointerInput(c.id, startEdge, block) {
                if (block != null) {
                    detectTapGestures { onBlocked(block) }
                    return@pointerInput
                }
                var raw = 0f
                var playheadBefore = 0f
                var dragging = false
                // the clip as it was when the drag began: the trim is measured from its edge
                var c0 = clipNow
                // the drag's trim, snapped to an edge of the other clips when one is near (a tick when it catches)
                var caught: Float? = null
                fun trimOf(r: Float): Float {
                    val edge = if (startEdge) c0.start else c0.end
                    val d = SimpleStripModel.clamp(c0, startEdge, r)
                    val e = snapNow(edge + d)
                    if (e != caught) { caught = e; if (e != null) haptics.tick() }
                    return if (e != null) SimpleStripModel.clamp(c0, startEdge, e - edge) else d
                }
                try {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            c0 = clipNow; raw = 0f; caught = null; dragging = true
                            playheadBefore = state.playhead; state.playing = false; haptics.press()
                        },
                        onHorizontalDrag = { ch, dx ->
                            ch.consume()
                            raw += dx / ppsNow
                            val d = trimOf(raw)
                            onDrag(d)
                            // the monitor shows the frame at the new edge (shortening: the material is there to show) –
                            // not with the centred playhead: the strip would move under the finger; it goes to the edit
                            // when the finger lifts
                            if (!state.centerPlayhead) {
                                val frame = 1f / projectFps.toFloat()
                                state.playhead = if (startEdge) c0.start + d.coerceAtLeast(0f)
                                    else c0.start + SimpleStripModel.lengthAfter(c0, false, d.coerceAtMost(0f)) - frame
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            val d = trimOf(raw)
                            onEnd()
                            // back where it was for now; once the trim has landed, to the new edge (the start stays where
                            // the clip starts – what follows ripples)
                            state.playhead = playheadBefore
                            if (abs(d) >= 1e-4f && SimpleStripModel.trim(state, c0, startEdge, d)) {
                                haptics.confirm()
                                val frame = 1f / projectFps.toFloat()
                                onLanded(if (startEdge) c0.start else c0.start + SimpleStripModel.lengthAfter(c0, false, d) - frame)
                            }
                        },
                        onDragCancel = { dragging = false; onEnd(); state.playhead = playheadBefore },
                    )
                } finally {
                    // the gesture ended without a lift (the handle went away): nothing is trimmed, nothing is left shown
                    if (dragging) { onEnd(); state.playhead = playheadBefore }
                }
            }
            .testTag("simpleHandle:${if (startEdge) "start" else "end"}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(if (height < 60.dp) 10.dp else 14.dp).height(height - 8.dp).clip(RoundedCornerShape(7.dp))
                .background(if (block != null) K.OnSurfaceVar else K.Primary),
            contentAlignment = Alignment.Center,
        ) {
            if (block != null) Icon(Ic.Lock, null, tint = K.Surface, modifier = Modifier.size(12.dp))
            else Box(Modifier.width(3.dp).height(if (height < 60.dp) 12.dp else 22.dp).clip(RoundedCornerShape(2.dp)).background(K.OnPrimary))
        }
    }
}

/** The playhead: a line over the strip with the time on top ("0:12 / 0:45"). */
@Composable
private fun Playhead(state: EditorState, pps: Float, side: androidx.compose.ui.unit.Dp, total: Float, width: Float, lineH: androidx.compose.ui.unit.Dp) {
    val density = LocalDensity.current
    val x = with(density) { side.toPx() } + state.playhead.coerceIn(0f, total) * pps
    val text = stringResource(R.string.simple_playhead, SimpleStripModel.clock(state.playhead.coerceIn(0f, total)), SimpleStripModel.clock(total))
    Box(
        Modifier.offset { IntOffset((x - 1.dp.toPx()).roundToInt(), 22.dp.roundToPx()) }.zIndex(5f).width(2.dp).height(lineH).background(Color.White)
            .testTag("simplePlayhead")
    )
    // the time sits over the line, kept inside the strip at its ends
    var labelW by remember { mutableFloatStateOf(0f) }
    Text(
        text, color = K.OnSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.offset { IntOffset((x - labelW / 2).coerceIn(0f, (width - labelW).coerceAtLeast(0f)).roundToInt(), 0) }
            .onSizeChanged { labelW = it.width.toFloat() }
            .widthIn(min = 56.dp).clip(RoundedCornerShape(6.dp)).background(K.SurfaceHighest).padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = text }
            .testTag("simplePlayhead:time"),
    )
}

/** The time ruler over the clips: a tick each second (or more apart when zoomed out); [scrub]: a drag along it moves the playhead. */
@Composable
private fun Ruler(state: EditorState, pps: Float, sidePx: Float, total: Float, scrub: Boolean) {
    val desc = stringResource(R.string.simple_ruler)
    Canvas(
        Modifier.fillMaxWidth().height(26.dp)
            .then(if (!scrub) Modifier else Modifier.pointerInput(pps, sidePx) {
                // (the monitor shows stills while the playhead moves and seeks when it rests – as the timeline's ruler)
                detectHorizontalDragGestures(
                    onDragStart = { o -> state.playing = false; state.playhead = ((o.x - sidePx) / pps).coerceIn(0f, total) },
                    onHorizontalDrag = { ch, _ -> ch.consume(); state.playhead = ((ch.position.x - sidePx) / pps).coerceIn(0f, total) },
                )
            })
            .semantics { contentDescription = desc }
            .testTag("simpleStrip:ruler")
    ) {
        // a tick every 1, 2, 5, 10 … s: at least 24 dp apart
        val minGap = 24.dp.toPx()
        val step = listOf(0.5f, 1f, 2f, 5f, 10f, 30f, 60f).firstOrNull { it * pps >= minGap } ?: 120f
        var t = 0f
        while (t <= total + 1e-3f) {
            val x = sidePx + t * pps
            val major = (t / step).roundToInt() % 5 == 0
            drawLine(K.OnSurfaceVar.copy(alpha = if (major) 0.7f else 0.35f), Offset(x, size.height - (if (major) 10.dp else 6.dp).toPx()), Offset(x, size.height), 1.dp.toPx())
            t += step
        }
    }
}

/** The centred playhead: a line in the middle of the strip (which moves under it) with the time over it. */
@Composable
private fun CenterPlayhead(state: EditorState, total: Float, lineH: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val text = stringResource(R.string.simple_playhead, SimpleStripModel.clock(state.playhead.coerceIn(0f, total)), SimpleStripModel.clock(total))
    Column(modifier.testTag("simplePlayhead"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text, color = K.OnSurface, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(K.SurfaceHighest).padding(horizontal = 6.dp, vertical = 2.dp)
                .semantics { contentDescription = text }.testTag("simplePlayhead:time"),
        )
        Box(Modifier.width(2.dp).height(lineH).background(Color.White))
    }
}

/** Under the ruler (where the rows start, from the strip's top); the storyline's height; a row's height and step. */
private val RULER_H = 30.dp
private val MAIN_H = 72.dp
private val LANE_H = 36.dp
private val LANE_STEP = 42.dp

/**
 * A clip on a row under the storyline: its colour, name (and frames, for video), a pin where it hangs on the storyline
 * drawn by the strip. A tap selects it (the bar below then acts on it, its handles trim it); hold and drag moves it –
 * along its row, onto another row of its kind, or (video) up into the storyline.
 */
@Composable
private fun LaneClip(
    state: EditorState, c: Clip, pps: Float, sidePx: Float, y: androidx.compose.ui.unit.Dp, selected: Boolean,
    dragEdge: Boolean?, dragDelta: Float, moving: Offset?,
    onMoveStart: (Offset) -> Unit, onMove: (Offset) -> Unit, onDrop: (cancelled: Boolean) -> Unit,
    onKnife: (Float?) -> Unit = {},
) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val knife by rememberUpdatedState(onKnife)
    val start by rememberUpdatedState(onMoveStart)
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    // while an edge is dragged: the clip as it will be
    val d = dragDelta
    val a = c.start + (if (dragEdge == true) d else 0f)
    val len = SimpleStripModel.lengthAfter(c, dragEdge ?: false, if (dragEdge != null) d else 0f)
    val x = sidePx + a * pps + (moving?.x ?: 0f)
    val desc = stringResource(when (c.kind) {
        ClipKind.TITLE -> R.string.simple_text_band
        ClipKind.AUDIO, ClipKind.MUSIC -> R.string.simple_sound_band
        else -> R.string.simple_lane_overlay
    }, c.name)
    val uri = if (c.kind == ClipKind.VIDEO) state.uriOfItem?.invoke(c.item) else null
    Box(
        Modifier.offset { IntOffset(x.roundToInt(), (y.toPx() + (moving?.y ?: 0f)).roundToInt()) }
            .zIndex(if (moving != null) 4f else 1f)
            .width(with(density) { (len * pps).toDp() }.coerceAtLeast(2.dp)).height(LANE_H)
            .then(if (moving != null) Modifier.shadow(10.dp, RoundedCornerShape(8.dp)) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(c.kind.bg)
            .then(if (selected) Modifier.border(2.5.dp, K.Primary, RoundedCornerShape(8.dp)) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = desc; this.selected = selected; role = Role.Button }
            .pointerInput(c.id, pps) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o -> haptics.heavy(); state.playing = false; state.tapSelect(c.id); start(o) },
                    onDrag = { ch, dd -> ch.consume(); move(dd) },
                    onDragEnd = { drop(false) },
                    onDragCancel = { drop(true) },
                )
            }
            .clickable { haptics.press(); state.playing = false; state.tapSelect(c.id) }
            .swipeCut(state, c, pps, haptics) { knife(it) }
            .testTag("simpleLaneClip:${c.id}"),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (uri != null) Filmstrip(c, uri, { liveSourceSpan(c.srcIn, c.length, c.speed, c.reverse, c.hold, c.length, 0f) }, { pps }, Modifier.fillMaxSize())
        Text(c.name, color = c.kind.fg, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

/** A caption on the captions' row: its words; a tap takes the playhead there (captions are edited in advanced mode). */
@Composable
private fun CaptionTile(state: EditorState, cap: UiCaption, pps: Float, sidePx: Float, y: androidx.compose.ui.unit.Dp) {
    val density = LocalDensity.current
    val desc = stringResource(R.string.simple_caption_lane, cap.text)
    Box(
        Modifier.offset { IntOffset((sidePx + cap.start * pps).roundToInt(), y.roundToPx()) }
            .width(with(density) { ((cap.end - cap.start) * pps).toDp() }.coerceAtLeast(2.dp)).height(LANE_H)
            .clip(RoundedCornerShape(8.dp)).background(K.SurfaceHigh)
            .semantics(mergeDescendants = true) { contentDescription = desc; role = Role.Button }
            .clickable { state.playing = false; state.playhead = cap.start }
            .testTag("simpleCaption:${cap.id}"),
        contentAlignment = Alignment.CenterStart,
    ) { Text(cap.text, color = K.OnSurfaceVar, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp)) }
}

/**
 * Where a dragged video clip (or title) is dropped to lie over everything as a new layer: the strip's top, over the
 * ruler, while it is dragged ([lit]: the finger is over it).
 */
@Composable
private fun NewLayerRow(left: androidx.compose.ui.unit.Dp, width: androidx.compose.ui.unit.Dp, lit: Boolean) {
    Box(
        Modifier.offset(left + 16.dp, 1.dp).zIndex(3f).width((width - 32.dp).coerceAtLeast(40.dp)).height(RULER_H - 2.dp)
            .clip(RoundedCornerShape(8.dp)).background(if (lit) K.Primary.copy(alpha = 0.25f) else K.Surface)
            .border(1.5.dp, K.Primary.copy(alpha = if (lit) 1f else 0.6f), RoundedCornerShape(8.dp)).testTag("simpleLane:new"),
        contentAlignment = Alignment.Center,
    ) { Text(stringResource(R.string.simple_lane_new), color = K.Primary, fontSize = 12.sp, maxLines = 1) }
}

/**
 * What the row's track has from advanced mode: locked, hidden or muted, its own sound effects – a small mark at the
 * row's start in view. A locked one says why its clips don't move (and offers advanced mode).
 */
@Composable
private fun LaneMarks(state: EditorState, lane: SimpleStripModel.Lane, y: androidx.compose.ui.unit.Dp, left: androidx.compose.ui.unit.Dp, onLocked: (SimpleStripModel.Block) -> Unit) {
    val t = state.trackStates.getOrNull(lane.track) ?: return
    val audio = state.tracks.getOrNull(lane.track)?.kind == TrackKind.AUDIO
    val marks = buildList {
        if (t.locked) add(Ic.Lock to R.string.simple_lane_locked)
        if (audio && t.muted) add(Ic.SpeakerOff to R.string.simple_lane_muted)
        if (!audio && !t.enabled) add(Ic.EyeOff to R.string.simple_lane_hidden)
        if (t.afx.isNotEmpty()) add(Ic.Sliders to R.string.simple_lane_effects)
    }
    if (marks.isEmpty()) return
    val desc = marks.map { stringResource(it.second) }.joinToString(", ")
    Row(
        Modifier.offset(left + 4.dp, y + 6.dp).zIndex(3f).height(LANE_H - 12.dp).clip(RoundedCornerShape(12.dp))
            .background(K.SurfaceHighest.copy(alpha = 0.92f))
            .then(if (t.locked) Modifier.clickable(role = Role.Button) { onLocked(SimpleStripModel.Block.LOCKED) } else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = desc }
            .padding(horizontal = 6.dp).testTag("simpleLane:marks:${lane.track}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) { for (m in marks) Icon(m.first, null, tint = K.OnSurface, modifier = Modifier.size(14.dp)) }
}

/**
 * The timeline's quick swipe down on a clip, also in the strip (the gesture tips name it): through the clip it cuts it
 * where the finger went down (the playhead when that is close). Only a swipe down that leaves the touch slop within
 * [KNIFE_MS] and mostly downwards; anything slower, sideways or upwards is left to the tap, the hold-and-drag and the
 * scrolling. (Put after them in the chain: it sees the touch first.) There is no flick up to delete: a clip taken up
 * is a layer over the film (the magnetic timeline's lanes) – Delete in the bar takes it out.
 */
internal fun Modifier.swipeCut(state: EditorState, clip: Clip, pps: Float, haptics: Haptics, onKnife: (Float?) -> Unit): Modifier = pointerInput(clip.id, pps) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // the clip as it is now, not as it was when the gesture began to be watched: keyed by its id, the handler lives on
        // while the clip moves (a clip before it shortened or moved, the film as long) – the cut then went where the clip
        // had been, to the right of the finger, and outside the clip cut nothing
        val c = state.clips.firstOrNull { it.id == clip.id } ?: return@awaitEachGesture
        var cutting = false
        while (true) {
            val ev = awaitPointerEvent()
            if (ev.changes.count { it.pressed } > 1) { if (cutting) onKnife(null); return@awaitEachGesture }
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: run { if (cutting) onKnife(null); return@awaitEachGesture }
            if (cutting) { ch.consume(); if (!ch.pressed) break else continue }
            if (!ch.pressed) return@awaitEachGesture
            val d = ch.position - down.position
            if (abs(d.x) <= viewConfiguration.touchSlop && abs(d.y) <= viewConfiguration.touchSlop) continue
            if (ch.uptimeMillis - down.uptimeMillis >= KNIFE_MS || d.y <= 0f || d.y < abs(d.x) * 1.5f) return@awaitEachGesture
            cutting = true
            state.playing = false
            haptics.tick()
            ch.consume()
            // the red line where it will cut, while the finger is down (as the timeline's knife)
            onKnife(knifeAt(state, c, pps, down.position.x))
        }
        val t = knifeAt(state, c, pps, down.position.x)
        if (t > c.start + 0.04f && t < c.end - 0.04f) { haptics.confirm(); state.split(c.id, t) }
        onKnife(null)
    }
}

/** Where a swipe down at [x] (px into the clip) cuts: that frame, or the playhead within 12 dp of it. */
private fun androidx.compose.ui.unit.Density.knifeAt(state: EditorState, c: Clip, pps: Float, x: Float): Float {
    val t = snapFrame(c.start + x / pps)
    return if (abs(t - state.playhead) * pps < 12.dp.toPx()) state.playhead else t
}

/** The red line of a swipe-down cut, over the row it cuts (it fades when the finger lifts, on the new edit point). */
private data class KnifeMark(val t: Float, val top: androidx.compose.ui.unit.Dp, val height: androidx.compose.ui.unit.Dp, val held: Boolean)

@Composable
private fun KnifeLine(mark: KnifeMark?, pps: Float, sidePx: Float) {
    var last by remember { mutableStateOf(mark) }
    if (mark != null) last = mark
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (mark?.held == true) 1f else 0f,
        if (mark?.held == true) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(400), label = "knife")
    val m = last ?: return
    if (alpha <= 0f) return
    Canvas(
        Modifier.offset { IntOffset(0, m.top.roundToPx()) }.fillMaxWidth().height(m.height).zIndex(5f)
            .then(if (mark?.held == true) Modifier.testTag("simpleStrip:knife") else Modifier)
    ) {
        val x = sidePx + m.t * pps
        val red = Color(0xFFFF6B6B).copy(alpha = alpha)
        drawRect(red.copy(alpha = 0.18f * alpha), Offset(x - 5.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(10.dp.toPx(), size.height))
        drawRect(red, Offset(x - 1.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
        drawCircle(K.Surface.copy(alpha = alpha), 7.dp.toPx(), Offset(x, size.height / 2f))
        drawCircle(red, 5.dp.toPx(), Offset(x, size.height / 2f))
    }
}

/** Simple mode's first tip, once: how the strip is used (closed for good with OK). */
@Composable
private fun SimpleTip() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var seen by remember { mutableStateOf(AppPrefs.simpleTipSeen(ctx)) }
    if (seen) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(K.PrimaryContainer)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp).testTag("simpleTip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.simple_tip), color = K.PrimarySoft, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        PillButton(stringResource(R.string.simple_tip_ok), { AppPrefs.setSimpleTipSeen(ctx); seen = true }, Modifier.testTag("simpleTip:ok"), filled = false)
    }
}

/** What the strip can't show, said plainly, with the way to advanced mode (the project is left as it is). */
@Composable
private fun AdvancedOnly(text: String, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp).testTag("simpleStrip:advancedOnly"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, color = K.OnSurface, fontSize = 14.sp, lineHeight = 20.sp)
        PillButton(stringResource(R.string.simple_open_advanced), onOpen, Modifier.testTag("simpleStrip:openAdvanced"))
        Spacer(Modifier.height(4.dp))
    }
}
