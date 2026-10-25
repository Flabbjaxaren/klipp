package se.gendut.klipp

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.magnifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.CornerPin
import se.gendut.klipp.engine.CornerPins
import se.gendut.klipp.engine.PinTrack
import se.gendut.klipp.engine.TitleFollow
import se.gendut.klipp.engine.WindowTracker
import kotlinx.coroutines.launch

/**
 * Tracks a pinned title's four corners on the clip under it (each corner with its own window, see
 * [CornerPins.windows]) over the part of the clip the title covers, from the frame at the playhead.
 */
object PinTrackService {
    var progress by mutableStateOf<Float?>(null)
        private set
    /** The title being tracked right now. */
    var target by mutableStateOf<Long?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    private val scope = kotlinx.coroutines.MainScope()
    private var job: kotlinx.coroutines.Job? = null

    fun start(ctx: Context, gs: GradeState, title: Clip, c: ColorClip, playhead: Float) {
        val uri = c.uri ?: run { message = Str.t(R.string.colortools_clip_file_missing); return }
        val pin = gs.pins[title.id] ?: return
        cancel()
        val at = playhead.coerceIn(maxOf(title.start, c.start), minOf(title.end, c.end) - 0.001f)
        // the corners where they show now → the clip's own picture (before its motion and stabilization)
        val shown = TitleFollow.pinQuad(title.id, at.toDouble()) ?: pin.quad
        val corners = FloatArray(8)
        for (i in 0 until 4) {
            val p = TitleFollow.pointToClip(c.id, at.toDouble(), shown[2 * i], shown[2 * i + 1]) ?: floatArrayOf(shown[2 * i], shown[2 * i + 1])
            corners[2 * i] = p[0]; corners[2 * i + 1] = p[1]
        }
        // only the part of the clip the title covers
        val a = c.srcAt(maxOf(title.start, c.start)); val b = c.srcAt(minOf(title.end, c.end) - 0.001f)
        val lo = minOf(a, b); val hi = maxOf(a, b)
        val ref = c.srcAt(at).coerceIn(lo, hi)
        val wins = CornerPins.windows(corners, TitleFollow.width, TitleFollow.height)
        target = title.id
        progress = 0f
        message = null
        job = scope.launch {
            try {
                val t0 = System.nanoTime()
                val tracked = wins.mapIndexed { i, w ->
                    val tr = WindowTracker.track(ctx, uri, ref, lo, hi, w, { p -> scope.launch { progress = (i + p) / 4f } })
                    w.copy(track = tr)
                }
                val weak = tracked.sumOf { it.track?.weak ?: 0 }
                gs.setPin(title.id, CornerPin(shown, PinTrack(c.id, at.toDouble(), corners, tracked)))
                val secs = (System.nanoTime() - t0) / 1e9
                message = Str.t(R.string.titlepin_tracked, hi - lo, secs) + (if (weak > 0) " · " + Str.p(R.plurals.colortools_uncertain_frames, weak, weak) else "")
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = Str.t(R.string.colortools_tracking_cancelled)
                throw e
            } catch (e: Throwable) {
                message = Str.t(R.string.colortools_tracking_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                progress = null
                target = null
            }
        }
    }

    fun cancel() { job?.cancel(); job = null }
}

/**
 * A title's corner pin in its Properties: pin the text's box by its corners (drag them onto a surface in the picture),
 * track the corners, or let go of the pin.
 */
@Composable
fun PinSection(state: EditorState, p: ClipProps, gs: GradeState) {
    val haptics = rememberHaptics()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val title = state.clips.firstOrNull { it.id == p.id } ?: return
    val controller = state.props as? se.gendut.klipp.engine.ProjectController
    val pin = gs.pins[p.id]
    SectionLabel(stringResource(R.string.titlepin_title))
    val busy = PinTrackService.target == p.id
    val at = state.playhead.coerceIn(title.start, title.end - 0.001f)
    val under = state.clips.filter { it.kind == ClipKind.VIDEO && it.enabled && state.trackStates.getOrNull(it.track)?.enabled != false && at >= it.start && at < it.start + it.length }.minByOrNull { it.track }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (pin == null) {
            PillButton(stringResource(R.string.titlepin_pin), {
                haptics.confirm()
                // the text's box as it shows now becomes the pin; then its corners can be dragged
                val snap = controller?.snapshot?.sequence?.video?.firstNotNullOfOrNull { tr -> tr.clips.firstOrNull { it.id == p.id && it.text != null } }
                val q = snap?.let { se.gendut.klipp.engine.titleBoxQuad(it, at.toDouble(), p.seqW, p.seqH) } ?: return@PillButton
                state.playing = false
                state.playhead = at
                gs.setPin(p.id, CornerPin(q))
                state.pinEdit = p.id
            }, Modifier.testTag("pin:add"), icon = Ic.Target)
        } else if (busy) {
            PillButton(stringResource(R.string.titleui_cancel), { PinTrackService.cancel() }, Modifier.testTag("pin:cancel"), icon = Ic.Close, filled = false)
        } else {
            ToggleChip(stringResource(R.string.titlepin_edit), state.pinEdit == p.id, { haptics.tick(); state.pinEdit = if (state.pinEdit == p.id) null else p.id }, Modifier.testTag("pin:edit"))
            PillButton(stringResource(if (pin.track != null) R.string.titlepin_retrack else R.string.titlepin_track), {
                haptics.confirm()
                val c = under?.let { gs.clipInfo?.invoke(it.id) }
                if (c == null || c.uri == null) { PinTrackService.message = Str.t(R.string.titleui_follow_no_clip); return@PillButton }
                state.playing = false
                state.pinEdit = null
                PinTrackService.start(ctx, gs, title, c, state.playhead)
            }, Modifier.testTag("pin:track"), icon = Ic.Target)
            PillButton(stringResource(R.string.titlepin_remove), { haptics.tick(); state.pinEdit = null; gs.setPin(p.id, null) }, Modifier.testTag("pin:remove"), icon = Ic.Delete, filled = false)
        }
    }
    if (pin != null && !busy) TrackDensityRow()
    PinTrackService.progress?.takeIf { busy }?.let { pr ->
        androidx.compose.material3.LinearProgressIndicator(progress = { pr }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = K.Primary)
    }
    val msg = PinTrackService.message?.takeIf { !busy }
    Text(msg ?: stringResource(if (pin == null) R.string.titlepin_hint else R.string.titlepin_hint_edit),
        color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("pin:msg"))
}

/**
 * The pinned title's four corners on the picture: drag one onto the corner of a surface. A drag lets go of the
 * tracking (the corners are where you put them; track again from there).
 */
@Composable
fun PinOverlay(state: EditorState, gs: GradeState, modifier: Modifier = Modifier) {
    val id = state.pinEdit ?: return
    val pin = gs.pins[id] ?: return
    val title = state.clips.firstOrNull { it.id == id } ?: return
    val haptics = rememberHaptics()
    val t = state.playhead.toDouble().coerceIn(title.start.toDouble(), title.end.toDouble() - 1e-3)
    TitleFollow.revision
    val quad = TitleFollow.pinQuad(id, t) ?: pin.quad
    androidx.activity.compose.BackHandler { state.pinEdit = null }
    // the corners are placed on a frame of the title: the playhead goes inside it (and stands still)
    androidx.compose.runtime.LaunchedEffect(id) {
        state.playing = false
        if (state.playhead < title.start || state.playhead >= title.end) state.playhead = title.start
    }
    // the corner being dragged (local pixels): a magnifier shows what is under it
    var dragAt by remember { mutableStateOf(Offset.Unspecified) }
    var dragIdx by remember { mutableStateOf(-1) }
    // how many screen pixels one of the overlay's pixels takes (the monitor's pinch zoom scales the overlay) – measured
    // through the layout's transform (its bounds in the root are clipped to the screen, so they don't tell when zoomed)
    var screenPerLocal by remember { mutableStateOf(1f) }
    var coords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    Box(
        modifier.fillMaxSize().testTag("pinOverlay")
            .onGloballyPositioned { c ->
                coords = c
                if (c.isAttached) screenPerLocal = (c.localToRoot(Offset(1f, 0f)) - c.localToRoot(Offset.Zero)).getDistance().takeIf { it > 0f } ?: 1f
            }
            // the magnifier shows exactly what is inside a corner's ring (radius HANDLE_R on screen): its 120 dp circle
            // is the ring, enlarged – with the fine cross in the middle on the corner itself. Its source is taken as
            // the layout's position plus an unscaled offset, so the corner goes in as its offset on screen (zoomed: scaled
            // and moved with the monitor) – it used to magnify somewhere else as soon as the monitor was zoomed.
            .magnifier(sourceCenter = { magnifierSource(coords, dragAt) }, zoom = (LOUPE / 2f / HANDLE_R).toFloat().coerceAtLeast(1f),
                size = androidx.compose.ui.unit.DpSize(LOUPE.dp, LOUPE.dp), cornerRadius = (LOUPE / 2).dp)
            .pointerInput(id) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = gs.pins[id] ?: return@awaitEachGesture
                val q0 = TitleFollow.pinQuad(id, state.playhead.toDouble().coerceIn(title.start.toDouble(), title.end.toDouble() - 1e-3)) ?: start.quad
                // the nearest corner within reach
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val i = (0 until 4).minByOrNull { k -> kotlin.math.hypot(q0[2 * k] * w - down.position.x, q0[2 * k + 1] * h - down.position.y) } ?: return@awaitEachGesture
                if (kotlin.math.hypot(q0[2 * i] * w - down.position.x, q0[2 * i + 1] * h - down.position.y) > 48.dp.toPx()) return@awaitEachGesture
                down.consume()
                haptics.tick()
                val grab = Offset(q0[2 * i] * w - down.position.x, q0[2 * i + 1] * h - down.position.y)
                var q = q0.copyOf()
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    q = q.copyOf().also { it[2 * i] = ((c.position.x + grab.x) / w).coerceIn(-0.5f, 1.5f); it[2 * i + 1] = ((c.position.y + grab.y) / h).coerceIn(-0.5f, 1.5f) }
                    gs.livePin(id, CornerPin(q))
                    dragAt = Offset(q[2 * i] * w, q[2 * i + 1] * h)
                    dragIdx = i
                    c.consume()
                }
                dragAt = Offset.Unspecified
                dragIdx = -1
                haptics.confirm()
                gs.commitPin(id, CornerPin(q), start)
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                moveTo(quad[0] * size.width, quad[1] * size.height)
                for (k in 1 until 4) lineTo(quad[2 * k] * size.width, quad[2 * k + 1] * size.height)
                close()
            }
            // sizes in screen dp whatever the monitor's zoom
            val u = 1f / screenPerLocal
            val drag = dragIdx >= 0
            // while a corner is dragged the edges are hairlines (they are magnified too)
            drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke((if (drag) 1f else 3.5f).dp.toPx() * u))
            drawPath(path, K.Primary, style = Stroke((if (drag) 0.5f else 2f).dp.toPx() * u))
            for (k in 0 until 4) {
                val o = Offset(quad[2 * k] * size.width, quad[2 * k + 1] * size.height)
                if (k == dragIdx) {
                    // the corner being dragged: only a fine cross on the exact point – the magnifier shows it large
                    val l = (HANDLE_R * 0.5f).dp.toPx() * u
                    for (pass in 0..1) {
                        val c = if (pass == 0) Color.Black.copy(alpha = 0.7f) else Color.White
                        val sw = (if (pass == 0) 0.9f else 0.4f).dp.toPx() * u
                        drawLine(c, Offset(o.x - l, o.y), Offset(o.x + l, o.y), sw)
                        drawLine(c, Offset(o.x, o.y - l), Offset(o.x, o.y + l), sw)
                    }
                    continue
                }
                // a ring with an open middle and a fine cross: the exact point stays visible
                drawCircle(Color.Black.copy(alpha = 0.5f), HANDLE_R.dp.toPx() * u, o, style = Stroke(3.5.dp.toPx() * u))
                drawCircle(Color.White, HANDLE_R.dp.toPx() * u, o, style = Stroke(2.dp.toPx() * u))
                val l = 4.dp.toPx() * u; val sw = 1.dp.toPx() * u
                drawLine(K.Primary, Offset(o.x - l, o.y), Offset(o.x + l, o.y), sw)
                drawLine(K.Primary, Offset(o.x, o.y - l), Offset(o.x, o.y + l), sw)
            }
        }
        // at the top, beside the monitor's "Program" badge (titles' corners are often low in the frame)
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 110.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // the hint gives way (wraps) – the done button keeps its size
            Text(stringResource(R.string.titlepin_overlay_hint), color = Color.White, fontSize = 12.sp,
                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000)).padding(horizontal = 8.dp, vertical = 4.dp))
            Text(stringResource(R.string.titlepin_done), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000))
                    .clickable { state.pinEdit = null }.padding(horizontal = 8.dp, vertical = 4.dp).testTag("pin:done"))
        }
    }
}

/**
 * The magnifier's source for the local point [p]: Compose places it at the layout's position in the root plus the given
 * offset as is, which is only right unscaled – so the offset is [p]'s distance from the layout's origin on screen.
 */
internal fun magnifierSource(c: androidx.compose.ui.layout.LayoutCoordinates?, p: Offset): Offset {
    if (c == null || !c.isAttached || !p.isSpecified) return p
    return c.localToRoot(p) - c.positionInRoot()
}

/** A corner handle's ring radius and the magnifier's size (dp on screen): the magnifier is the ring, enlarged. */
private const val HANDLE_R = 12f
private const val LOUPE = 120f
