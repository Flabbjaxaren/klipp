package se.gendut.klipp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    /** Clips the strip doesn't show: everything but the storyline and the sound linked to its clips. */
    fun others(state: EditorState): Int {
        val line = storyline(state)
        val ids = line.map { it.id }.toSet()
        val links = line.mapNotNull { it.link }.toSet()
        return state.clips.count { it.id !in ids && (it.link == null || it.link !in links) }
    }

    /** A clip whose trimmed-off material can be brought back (a plain video clip: not reversed, not a freeze frame). */
    fun canExtend(c: Clip): Boolean = c.kind == ClipKind.VIDEO && !c.reverse && !c.hold && c.srcLen > 0f

    private fun speed(c: Clip) = abs(c.speed).coerceAtLeast(1e-4f)

    /** Timeline seconds trimmed off the clip's start that can be brought back. */
    fun hiddenHead(c: Clip): Float = if (canExtend(c)) (c.srcIn / speed(c)).coerceAtLeast(0f) else 0f

    /** Timeline seconds after the clip's end, up to the file's end, that can be brought back. */
    fun hiddenTail(c: Clip): Float = if (canExtend(c)) ((c.srcLen - c.srcIn) / speed(c) - c.length).coerceAtLeast(0f) else 0f

    /**
     * A trim of [c]'s edge held to what the file has and to [MIN_LEN]: the start edge's [delta] > 0 cuts off, < 0
     * brings back; the end edge's > 0 lengthens, < 0 cuts off (the magnetic timeline's convention, [MagTimeline.trim]).
     */
    fun clamp(c: Clip, startEdge: Boolean, delta: Float): Float =
        if (startEdge) delta.coerceInSafe(-hiddenHead(c), c.length - MIN_LEN)
        else delta.coerceInSafe(-(c.length - MIN_LEN), hiddenTail(c))

    /** The clip's length after the trim. */
    fun lengthAfter(c: Clip, startEdge: Boolean, delta: Float): Float = if (startEdge) c.length - delta else c.length + delta

    /** Why simple mode leaves an edge alone (null = it can be trimmed). */
    enum class Block { TRANSITION, LOCKED }

    fun block(state: EditorState, c: Clip, startEdge: Boolean): Block? {
        if (state.locked(c.track)) return Block.LOCKED
        val edge = if (startEdge) c.start else c.end
        // a transition at the edge: the engine's from/to, or (without them) one on the track over the edge
        val at = state.transitions.any { t ->
            (if (startEdge) t.to == c.id else t.from == c.id) ||
                (t.from == null && t.to == null && t.track == c.track && edge >= t.start - 1e-3f && edge <= t.end + 1e-3f)
        }
        return if (at) Block.TRANSITION else null
    }

    /** Trims [c]'s edge by [delta] (clamped) as one undo step; false = nothing changed. */
    fun trim(state: EditorState, c: Clip, startEdge: Boolean, delta: Float): Boolean {
        if (block(state, c, startEdge) != null) return false
        val d = clamp(c, startEdge, delta)
        if (abs(d) < 1e-4f) return false
        return state.magTrim(c.id, startEdge, d)
    }

    /** Brings back everything trimmed off [c]'s start (or end), as one undo step. */
    fun restore(state: EditorState, c: Clip, startEdge: Boolean): Boolean =
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
    val selected = line.firstOrNull { it.id == state.selected }
    var blocked by remember { mutableStateOf<SimpleStripModel.Block?>(null) }
    // the edge being dragged and how far (clamped), for the live feedback; null = no drag
    var dragEdge by remember { mutableStateOf<Boolean?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    // where the playhead goes once a trim has landed: the monitor seeks when the playhead changes, so it moves only
    // after the edit (set before, the seek ran on the old timeline and the picture stayed from it)
    var landing by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.clips) { landing?.let { state.playhead = it; landing = null } }
    Column(modifier.fillMaxSize().testTag("simpleStrip")) {
        // what is going on: the drag's result, the selected clip's choices, or how to start
        StripInfo(state, selected, dragEdge, dragDelta, onOpenAdvanced = { state.switchMode(false) })
        if (line.isEmpty() && others > 0) {
            // the film is only on other tracks: the strip can't show it – left as it is
            AdvancedOnly(stringResource(R.string.simple_only_other_tracks)) { state.switchMode(false) }
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(132.dp)) {
            val side = 24.dp
            val widthPx = with(density) { (maxWidth - side * 2).toPx() }
            // the whole film in view when it fits; at least 32 dp a second, then the strip scrolls
            val pps = maxOf(widthPx / total, with(density) { 32.dp.toPx() })
            val scroll = rememberScrollState()
            // playing: the playhead stays in view
            LaunchedEffect(state.playhead, state.playing) {
                if (!state.playing) return@LaunchedEffect
                val x = (state.playhead * pps).roundToInt()
                val view = with(density) { maxWidth.toPx() }.roundToInt()
                if (x < scroll.value || x > scroll.value + view - with(density) { side.toPx() }.roundToInt() * 2) scroll.scrollTo((x - view / 3).coerceAtLeast(0))
            }
            // a clip selected out of view (the dock, a search, a test) comes into view
            LaunchedEffect(state.selected, pps) {
                val c = line.firstOrNull { it.id == state.selected } ?: return@LaunchedEffect
                val view = with(density) { maxWidth.toPx() }
                val a = with(density) { side.toPx() } + c.start * pps
                val b = with(density) { side.toPx() } + c.end * pps
                if (a < scroll.value || b > scroll.value + view) scroll.animateScrollTo(((a + b) / 2 - view / 2).roundToInt().coerceAtLeast(0))
            }
            val contentW = with(density) { (total * pps).toDp() } + side * 2
            Box(Modifier.fillMaxSize().horizontalScroll(scroll)) {
                Box(
                    Modifier.width(contentW).fillMaxSize()
                        // a tap beside the clips: the playhead goes there, nothing stays selected
                        .pointerInput(pps) {
                            detectTapGestures { o ->
                                state.playing = false
                                state.clearSelection()
                                state.playhead = ((o.x - side.toPx()) / pps).coerceIn(0f, total)
                            }
                        }
                        .testTag("simpleStrip:track")
                ) {
                    val count = line.size
                    line.forEachIndexed { i, c ->
                        StripClip(state, c, i, count, pps, side, c.id == state.selected,
                            dragEdge.takeIf { c.id == state.selected }, if (c.id == state.selected) dragDelta else 0f)
                    }
                    // gaps in the storyline: dashed (the magnetic timeline's gap clips; trimmed in advanced mode for now)
                    var t = 0f
                    for (c in line) {
                        if (c.start - t > 0.05f) StripGap(t, c.start - t, pps, side)
                        t = maxOf(t, c.end)
                    }
                    if (selected != null) {
                        for (startEdge in listOf(true, false)) TrimHandle(
                            state, selected, startEdge, pps, side,
                            onBlocked = { b -> haptics.heavy(); blocked = b },
                            onDrag = { d -> dragEdge = startEdge; dragDelta = d },
                            onEnd = { dragEdge = null; dragDelta = 0f },
                            onLanded = { t -> landing = t },
                        )
                    }
                    Playhead(state, pps, side, total, with(density) { contentW.toPx() })
                }
            }
        }
        if (others > 0 && line.isNotEmpty()) Text(
            pluralStringResource(R.plurals.simple_others_follow, others, others),
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("simpleStrip:others"),
        )
    }
    blocked?.let { b ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text(stringResource(if (b == SimpleStripModel.Block.TRANSITION) R.string.simple_blocked_transition_title else R.string.simple_blocked_locked_title)) },
            text = { Text(stringResource(if (b == SimpleStripModel.Block.TRANSITION) R.string.simple_blocked_transition else R.string.simple_blocked_locked)) },
            confirmButton = { PillButton(stringResource(R.string.simple_open_advanced), { blocked = null; state.switchMode(false) }, Modifier.testTag("simpleStrip:openAdvanced")) },
            dismissButton = { PillButton(stringResource(android.R.string.cancel), { blocked = null }, filled = false) },
        )
    }
}

/** The line over the strip: the trim's result while dragging, the selected clip's length and choices, or a hint. */
@Composable
private fun StripInfo(state: EditorState, selected: Clip?, dragEdge: Boolean?, dragDelta: Float, onOpenAdvanced: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            when {
                selected != null && dragEdge != null -> {
                    val after = SimpleStripModel.lengthAfter(selected, dragEdge, dragDelta)
                    Text(
                        stringResource(R.string.simple_trim_left, SimpleStripModel.clock(after), SimpleStripModel.clock(selected.length)),
                        color = K.OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("simpleStrip:trimInfo"),
                    )
                }
                selected != null -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val head = SimpleStripModel.hiddenHead(selected)
                    val tail = SimpleStripModel.hiddenTail(selected)
                    val canStart = head > 0.05f && SimpleStripModel.block(state, selected, true) == null
                    val canEnd = tail > 0.05f && SimpleStripModel.block(state, selected, false) == null
                    if (!canStart && !canEnd) Text(stringResource(R.string.simple_trim_hint), color = K.OnSurfaceVar, fontSize = 13.sp)
                    if (canStart) PillButton(stringResource(R.string.simple_trim_restore_start, SimpleStripModel.clock(head)),
                        { SimpleStripModel.restore(state, selected, true) }, Modifier.testTag("simpleStrip:restoreStart"), filled = false)
                    if (canEnd) PillButton(stringResource(R.string.simple_trim_restore_end, SimpleStripModel.clock(tail)),
                        { SimpleStripModel.restore(state, selected, false) }, Modifier.testTag("simpleStrip:restoreEnd"), filled = false)
                }
                else -> Text(stringResource(R.string.simple_strip_hint), color = K.OnSurfaceVar, fontSize = 13.sp)
            }
        }
        // the way to advanced mode, always in sight (the same switch as the ⋯ menu and Settings)
        PillButton(stringResource(R.string.simple_advanced_button), onOpenAdvanced, Modifier.testTag("simpleStrip:advanced"), filled = false)
    }
}

/** One storyline clip: its frames, name, selection frame and – while its edge is dragged – what goes or comes back. */
@Composable
private fun StripClip(
    state: EditorState, c: Clip, index: Int, count: Int, pps: Float, side: androidx.compose.ui.unit.Dp,
    selected: Boolean, dragEdge: Boolean?, dragDelta: Float,
) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val x = with(density) { side.toPx() } + c.start * pps
    val w = with(density) { (c.length * pps).toDp() }
    val desc = stringResource(R.string.simple_clip_desc, index + 1, count, c.name, SimpleStripModel.clock(c.length))
    val uri = state.uriOfItem?.invoke(c.item)
    Box(
        Modifier.offset { IntOffset(x.roundToInt(), with(density) { 30.dp.roundToPx() }) }
            .width(w).height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.kind.bg)
            .then(if (selected) Modifier.border(3.dp, K.Primary, RoundedCornerShape(12.dp)) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = desc; this.selected = selected; role = Role.Button }
            .clickable { haptics.press(); state.playing = false; state.tapSelect(c.id) }
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
        }
    }
    // brought back while dragging: the material outside the clip, as a pale strip beside it
    if (dragEdge != null && ((dragEdge && dragDelta < 0f) || (!dragEdge && dragDelta > 0f))) {
        val extra = abs(dragDelta)
        val gx = if (dragEdge) x - extra * pps else x + c.length * pps
        Box(
            Modifier.offset { IntOffset(gx.roundToInt(), with(density) { 30.dp.roundToPx() }) }
                .width(with(density) { (extra * pps).toDp() }).height(72.dp)
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
private fun StripGap(start: Float, length: Float, pps: Float, side: androidx.compose.ui.unit.Dp) {
    val density = LocalDensity.current
    val desc = stringResource(R.string.simple_gap_desc, SimpleStripModel.clock(length))
    Box(
        Modifier.offset { IntOffset((side.toPx() + start * pps).roundToInt(), 30.dp.roundToPx()) }
            .width(with(density) { (length * pps).toDp() }).height(72.dp)
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
    onBlocked: (SimpleStripModel.Block) -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit, onLanded: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val block = SimpleStripModel.block(state, c, startEdge)
    val touch = 48.dp
    val edgeX = with(density) { side.toPx() } + (if (startEdge) c.start else c.end) * pps
    val label = stringResource(if (startEdge) R.string.simple_handle_start else R.string.simple_handle_end, c.name)
    val shorten = stringResource(R.string.simple_a11y_shorten)
    val lengthen = stringResource(R.string.simple_a11y_lengthen)
    // half a second each way, for a screen reader (each one trim, one undo step)
    val step = 0.5f
    Box(
        Modifier.offset { IntOffset((edgeX - touch.toPx() / 2).roundToInt(), 24.dp.roundToPx()) }
            .size(touch, 84.dp)
            .semantics {
                contentDescription = label
                customActions = if (block != null) emptyList() else listOf(
                    CustomAccessibilityAction(shorten) { SimpleStripModel.trim(state, c, startEdge, if (startEdge) step else -step) },
                    CustomAccessibilityAction(lengthen) { SimpleStripModel.trim(state, c, startEdge, if (startEdge) -step else step) },
                )
            }
            .pointerInput(c, startEdge, pps, block) {
                if (block != null) {
                    detectTapGestures { onBlocked(block) }
                    return@pointerInput
                }
                var raw = 0f
                var playheadBefore = 0f
                detectHorizontalDragGestures(
                    onDragStart = { raw = 0f; playheadBefore = state.playhead; state.playing = false; haptics.press() },
                    onHorizontalDrag = { ch, dx ->
                        ch.consume()
                        raw += dx / pps
                        val d = SimpleStripModel.clamp(c, startEdge, raw)
                        onDrag(d)
                        // the monitor shows the frame at the new edge (shortening: the material is there to show)
                        val frame = 1f / projectFps.toFloat()
                        state.playhead = if (startEdge) c.start + d.coerceAtLeast(0f)
                            else c.start + SimpleStripModel.lengthAfter(c, false, d.coerceAtMost(0f)) - frame
                    },
                    onDragEnd = {
                        val d = SimpleStripModel.clamp(c, startEdge, raw)
                        onEnd()
                        // back where it was for now; once the trim has landed, to the new edge (the start stays where the
                        // clip starts – what follows ripples)
                        state.playhead = playheadBefore
                        if (abs(d) >= 1e-4f && SimpleStripModel.trim(state, c, startEdge, d)) {
                            haptics.confirm()
                            val frame = 1f / projectFps.toFloat()
                            onLanded(if (startEdge) c.start else c.start + SimpleStripModel.lengthAfter(c, false, d) - frame)
                        }
                    },
                    onDragCancel = { onEnd(); state.playhead = playheadBefore },
                )
            }
            .testTag("simpleHandle:${if (startEdge) "start" else "end"}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(14.dp).height(76.dp).clip(RoundedCornerShape(7.dp))
                .background(if (block != null) K.OnSurfaceVar else K.Primary),
            contentAlignment = Alignment.Center,
        ) {
            if (block != null) Icon(Ic.Lock, null, tint = K.Surface, modifier = Modifier.size(12.dp))
            else Box(Modifier.width(3.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(K.OnPrimary))
        }
    }
}

/** The playhead: a line over the strip with the time on top ("0:12 / 0:45"). */
@Composable
private fun Playhead(state: EditorState, pps: Float, side: androidx.compose.ui.unit.Dp, total: Float, width: Float) {
    val density = LocalDensity.current
    val x = with(density) { side.toPx() } + state.playhead.coerceIn(0f, total) * pps
    val text = stringResource(R.string.simple_playhead, SimpleStripModel.clock(state.playhead), SimpleStripModel.clock(total))
    Box(
        Modifier.offset { IntOffset((x - 1.dp.toPx()).roundToInt(), 22.dp.roundToPx()) }.width(2.dp).height(92.dp).background(Color.White)
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

/** What the strip can't show, said plainly, with the way to advanced mode (the project is left as it is). */
@Composable
private fun AdvancedOnly(text: String, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp).testTag("simpleStrip:advancedOnly"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, color = K.OnSurface, fontSize = 14.sp, lineHeight = 20.sp)
        PillButton(stringResource(R.string.simple_open_advanced), onOpen, Modifier.testTag("simpleStrip:openAdvanced"))
        Spacer(Modifier.height(4.dp))
    }
}
