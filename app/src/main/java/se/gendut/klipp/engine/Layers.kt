package se.gendut.klipp.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings

/*
 * How the program image is built (0.8):
 *
 *  - Image layers = video tracks with real video clips (V3 → V2 → V1, top first), plus a separate layer for
 *    cross dissolves above the track they belong to. Media3's multi-layer compositor is used only when there
 *    is more than one layer; otherwise everything goes through the ordinary simple path.
 *  - Titles and captions are placed on top of the whole image as an overlay effect on the composition, so they
 *    also work on the simple path.
 *  - Everything time-dependent is "fail-open": if the timestamps cannot be interpreted the image still shows
 *    instead of going black.
 */

/** The image layers, top first (Media3's compositor draws index 0 last, i.e. on top, and lets it drive the time). */
data class LayerPlan(
    val width: Int,
    val height: Int,
    val total: Double,
    val layers: List<Layer>,
    /** Titles and captions as overlays (drawn on top of everything). */
    val overlays: List<OverlayItem> = emptyList(),
    /** Fades to/from black for V1 when the simple path is used. */
    val blackFades: List<Ramp> = emptyList(),
    /**
     * Where the plan starts on the timeline (a part of it, see [LayerPlanner.slice]: composition time 0 = [origin], the
     * times in the segments are the timeline's). [total] = where it ends.
     */
    val origin: Double = 0.0,
    /** Pre-rendered parts ([RenderCache]): there the image already has its fades (BlackFade skips them). */
    val rendered: List<ClosedFloatingPointRange<Double>> = emptyList(),
) {
    /** The composition's length (s). */
    val length get() = total - origin
    /** The multi-layer compositor is only needed when images actually stack on each other. */
    val needsCompositor: Boolean get() = layers.size > 1
}

/** One layer = one Media3 sequence. */
data class Layer(val name: String, val segs: List<Seg>) {
    /** Binary search for the segment visible at [t] seconds. */
    fun at(t: Double): Seg? {
        var lo = 0; var hi = segs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = segs[mid]
            when {
                t < s.start -> hi = mid - 1
                t >= s.end -> lo = mid + 1
                else -> return s
            }
        }
        return null
    }
}

sealed interface SegSrc {
    val item: Long
    /** A piece of a media file: source time and speed. */
    data class Media(override val item: Long, val srcIn: Double, val speed: Double) : SegSrc
    /** Freeze frame: the frame at source time [sec] for the whole segment. */
    data class Still(override val item: Long, val sec: Double) : SegSrc
}

/** The piece of clip [c] starting [offset] s after the clip's start (negative: before, for a transition). */
fun srcOf(c: ClipSnap, offset: Double = 0.0): SegSrc =
    c.hold?.let { SegSrc.Still(c.item, it) } ?: SegSrc.Media(c.item, (c.sourceIn + offset * c.speed).coerceAtLeast(0.0), c.speed)

sealed interface Overlay {
    data class Title(val t: TextSnap) : Overlay
    data class Caption(val text: String, val style: CaptionStyle = CaptionStyle()) : Overlay
}

/** Linear opacity ramp (transition): from [a0] at [t0] to [a1] at [t1]. */
data class Ramp(val t0: Double, val t1: Double, val a0: Float, val a1: Float) {
    fun at(t: Double): Float {
        if (t1 <= t0) return a1
        val k = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0).toFloat()
        return a0 + (a1 - a0) * k
    }
    fun covers(t: Double) = t >= t0 && t < t1
}

/**
 * A segment in a layer. [fx] = the transition's role for the whole segment (the overlap layer's segments, the freeze
 * frames); [windows] = transitions that only apply within their own times (t0–t1) – the clips in the track are not split
 * at the transition's edges, since every new segment makes the player restart in the file (decode from the nearest
 * keyframe), which hitched at every transition.
 */
data class Seg(
    val start: Double, val dur: Double, val src: SegSrc, val clip: ClipSnap?, val ramp: Ramp? = null, val fx: SegFx? = null,
    val windows: List<SegFx> = emptyList(),
) {
    val end get() = start + dur
    /** The transition's geometry at timeline time [t] (null = no transition). */
    fun geomAt(t: Double): Geom? = windows.firstOrNull { t >= it.t0 - 1e-6 && t <= it.t1 + 1e-6 }?.at(t) ?: fx?.at(t)
    val hasFx get() = fx != null || windows.isNotEmpty()
    fun alpha(t: Double): Float = ((clip?.at(t)?.opacity ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f) * (ramp?.at(t) ?: 1f)
}

/** A title or caption on the timeline. */
data class OverlayItem(val start: Double, val end: Double, val overlay: Overlay, val clip: ClipSnap? = null, val fadeIn: Ramp? = null, val fadeOut: Ramp? = null) {
    fun alpha(t: Double): Float {
        var a = ((clip?.at(t)?.opacity ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f)
        fadeIn?.let { if (t < it.t1) a *= it.at(t) }
        fadeOut?.let { if (t >= it.t0) a *= it.at(t) }
        return a
    }
}

object LayerPlanner {
    private const val EPS = 1e-6

    fun plan(s: Snapshot, mediaDur: (Long) -> Double?): LayerPlan? {
        val q = s.playable().sequence ?: return null
        val layers = ArrayList<Layer>()
        val overlays = ArrayList<OverlayItem>()
        // tracks top to bottom: V3, V2, V1
        for (t in q.video.take(3).reversed()) {
            if (!t.enabled) continue // hidden track (eye off)
            val clips = t.clips.sortedBy { it.start }
            val byId = clips.associateBy { it.id }
            val base = ArrayList<Seg>()
            val over = ArrayList<Seg>()
            for (c in clips) {
                val fadeIn = t.transitions.firstOrNull { it.to == c.id && it.from == null }
                val fadeOut = t.transitions.firstOrNull { it.from == c.id && it.to == null }
                if (c.text != null) {
                    overlays += OverlayItem(
                        c.start, c.end, Overlay.Title(c.text), c,
                        fadeIn?.let { Ramp(it.start, it.end, 0f, 1f) }, fadeOut?.let { Ramp(it.start, it.end, 1f, 0f) },
                    )
                    continue
                }
                val src = srcOf(c)
                if (fadeIn == null && fadeOut == null) {
                    base += Seg(c.start, c.duration, src, c)
                    continue
                }
                // dip to white: a color tint (fx) instead of opacity toward black
                val whiteIn = fadeIn?.takeIf { TransitionType.of(it.effect) == TransitionType.DIP_WHITE }
                val whiteOut = fadeOut?.takeIf { TransitionType.of(it.effect) == TransitionType.DIP_WHITE }
                // split the clip into segments so that each has at most one ramp
                var cur = c.start
                val cuts = sortedSetOf(c.start, c.end)
                fadeIn?.let { cuts += it.end.coerceIn(c.start, c.end) }
                fadeOut?.let { cuts += it.start.coerceIn(c.start, c.end) }
                for (edge in cuts.drop(1)) {
                    if (edge - cur > EPS) {
                        val inFade = fadeIn != null && cur < fadeIn.end - EPS
                        val outFade = !inFade && fadeOut != null && cur >= fadeOut.start - EPS
                        val ramp = when {
                            inFade && whiteIn == null -> Ramp(fadeIn.start, fadeIn.end, 0f, 1f)
                            outFade && whiteOut == null -> Ramp(fadeOut.start, fadeOut.end, 1f, 0f)
                            else -> null
                        }
                        val fx = when {
                            inFade && whiteIn != null -> SegFx(TransitionType.DIP_WHITE, true, false, whiteIn.start, whiteIn.end, whiteIn.start, false)
                            outFade && whiteOut != null -> SegFx(TransitionType.DIP_WHITE, false, false, whiteOut.start, whiteOut.end, whiteOut.end, false)
                            else -> null
                        }
                        base += Seg(cur, edge - cur, shift(src, cur - c.start), c, ramp, fx)
                    }
                    cur = edge
                }
            }
            // cross dissolve: the incoming clip's start before the clip and the outgoing clip's end after,
            // in a separate layer above the track with opacity following the transition
            for (x in t.transitions.sortedBy { it.start }) {
                val from = x.from?.let { byId[it] }?.takeIf { it.text == null } ?: continue
                val to = x.to?.let { byId[it] }?.takeIf { it.text == null } ?: continue
                val cut = to.start
                if (x.duration <= EPS) continue
                val type = TransitionType.of(x.effect)
                fun fx(incoming: Boolean, isOver: Boolean) = if (type == TransitionType.CROSS) null else SegFx(type, incoming, isOver, x.start, x.end, cut, x.reverse)
                val showOver = fx(true, true)?.visible ?: true
                fun ramp(incoming: Boolean) = if (type != TransitionType.CROSS) null else if (incoming) Ramp(x.start, x.end, 0f, 1f) else Ramp(x.start, x.end, 1f, 0f)
                // a freeze frame lies in the track for the whole transition and the moving clip above: Media3's compositor
                // gets an image in an upper layer with the content of a later frame (two ahead) – in the track it is correct
                val stillBelow = when {
                    !showOver -> null
                    from.hold != null && to.hold == null -> from
                    to.hold != null && from.hold == null -> to
                    else -> null
                }
                if (stillBelow != null) {
                    val incomingTop = stillBelow === from
                    val moving = if (incomingTop) to else from
                    val offset = if (incomingTop) -(cut - x.start) else from.duration - (cut - x.start)
                    over += handleSegs(moving, x.start, x.end - x.start, offset, mediaDur, ramp(incomingTop), fx(incomingTop, true))
                }
                // the padding where a file runs out (freeze frames): in the track, and the track's own clip above right there –
                // a still in an upper layer makes Media3's compositor compute the image position two frames off (see above)
                val swaps = ArrayList<Seg>()
                if (stillBelow == null) {
                    // the incoming clip's start before the edit point and the outgoing clip's end after: the material
                    // outside the clip in the file; where the file runs out (the clip starts at the file's start or ends at
                    // its end) a freeze frame of the first or last frame respectively for the rest, as in Premiere
                    val handles = ArrayList<Seg>()
                    if (showOver && cut - x.start > EPS) {
                        handles += handleSegs(to, x.start, cut - x.start, -(cut - x.start), mediaDur, ramp(true), fx(true, true))
                    }
                    if (showOver && x.end - cut > EPS) {
                        handles += handleSegs(from, cut, x.end - cut, from.duration, mediaDur, ramp(false), fx(false, true))
                    }
                    for (h in handles) {
                        if (h.src !is SegSrc.Still || h.clip?.hold != null) { over += h; continue }
                        swaps += h
                        // above: the track's own clip (before the edit point the outgoing one, after it the incoming one) with its role
                        val own = if (h.start < cut - EPS) from else to
                        val ownIn = own === to
                        over += Seg(h.start, h.dur, srcOf(own, h.start - own.start), own, ramp(ownIn), fx(ownIn, true))
                    }
                }
                // the track's own segments during the transition (push, slide, zoom, dip through a color): a time window with
                // its role on the segment, which is not split. Only where another image takes the clip's place (the freeze
                // frame for the whole transition, the padding where a file runs out) is the segment split – there it is a
                // different file anyway
                if (type != TransitionType.CROSS || stillBelow != null || swaps.isNotEmpty()) {
                    // (from, to, image that replaces the clip there)
                    val repl = ArrayList<Triple<Double, Double, Seg>>()
                    if (stillBelow != null) repl += Triple(x.start, x.end, Seg(x.start, x.duration, srcOf(stillBelow), stillBelow))
                    for (w in swaps) repl += Triple(w.start, w.end, w)
                    val pieces = ArrayList<Seg>()
                    for (sg in base) {
                        val incoming = when (sg.clip?.id) { from.id -> false; to.id -> true; else -> null }
                        val inWin = incoming != null && sg.end > x.start + EPS && sg.start < x.end - EPS
                        if (!inWin) { pieces += sg; continue }
                        val win = fx(incoming, false)
                        val e = sortedSetOf(sg.start, sg.end)
                        for ((r0, r1, _) in repl) { e += r0.coerceIn(sg.start, sg.end); e += r1.coerceIn(sg.start, sg.end) }
                        val edges = e.toList()
                        for (k in 0 until edges.size - 1) {
                            val a = edges[k]; val b = edges[k + 1]
                            if (b - a <= EPS) continue
                            val r = repl.firstOrNull { a >= it.first - EPS && b <= it.second + EPS }?.third
                            pieces += if (r != null) Seg(a, b - a, r.src, r.clip, null, fx(r.clip?.id == to.id, false))
                            else Seg(a, b - a, shift(sg.src, a - sg.start), sg.clip, sg.ramp, sg.fx, sg.windows + listOfNotNull(win))
                        }
                    }
                    base.clear(); base += pieces
                }
            }
            if (over.isNotEmpty()) layers += Layer("${t.name} transitions", nonOverlapping(over.sortedBy { it.start }))
            if (base.isNotEmpty()) layers += Layer(t.name, base)
        }
        for (c in q.captions.sortedBy { it.start }) {
            val style = q.captionTracks.firstOrNull { it.id == c.track }?.style ?: CaptionStyle()
            if (c.duration > EPS && c.text.isNotBlank()) overlays += OverlayItem(c.start, c.end, Overlay.Caption(c.text, style))
        }
        if (layers.isEmpty() && overlays.isEmpty()) return null
        // simple path: fades to black are done as an effect on the whole image
        val fades = if (layers.size == 1) layers[0].segs.mapNotNull { it.ramp }.distinct() else emptyList()
        return LayerPlan(q.width.coerceAtLeast(2), q.height.coerceAtLeast(2), q.duration, layers, overlays.sortedBy { it.start }, fades)
    }

    /**
     * The plan's part [a]–[b] (timeline seconds): the segments cut at the edges, without titles and captions (the app draws
     * them during playback). The times are still the timeline's; the composition starts at [a] ([LayerPlan.origin]).
     */
    fun slice(plan: LayerPlan, a: Double, b: Double): LayerPlan {
        val layers = plan.layers.mapNotNull { l ->
            val segs = l.segs.mapNotNull { sg ->
                val s0 = maxOf(sg.start, a); val s1 = minOf(sg.end, b)
                if (s1 - s0 <= EPS) null else sg.copy(start = s0, dur = s1 - s0, src = shift(sg.src, s0 - sg.start))
            }
            if (segs.isEmpty()) null else l.copy(segs = segs)
        }
        val fades = if (layers.size == 1) layers[0].segs.mapNotNull { it.ramp }.distinct() else emptyList()
        return plan.copy(layers = layers, overlays = emptyList(), blackFades = fades, origin = a, total = b, rendered = emptyList())
    }

    /**
     * Clip [c] during a transition outside its own edges: the segment [start]–[start]+[len] on the timeline, starting
     * [offset] s after the clip's start (negative = before). Real frames as far as the file reaches ([mediaDur]); before the
     * file's start the first frame holds still and after its end the last one (a freeze frame). A freeze frame in the
     * timeline: the whole segment.
     */
    internal fun handleSegs(c: ClipSnap, start: Double, len: Double, offset: Double, mediaDur: (Long) -> Double?, ramp: Ramp?, fx: SegFx?): List<Seg> {
        if (len <= EPS) return emptyList()
        if (c.hold != null) return listOf(Seg(start, len, SegSrc.Still(c.item, c.hold), c, ramp, fx))
        val sp = kotlin.math.abs(c.speed).takeIf { it > 1e-9 } ?: 1.0
        val s0 = c.sourceIn + offset * sp
        val s1 = s0 + len * sp
        val md = mediaDur(c.item)?.takeIf { it > 0 }
        val a = s0.coerceAtLeast(0.0)
        val b = if (md != null) s1.coerceAtMost(md) else s1
        val out = ArrayList<Seg>()
        // before the file's start: the first frame
        val tA = start + (a - s0) / sp
        if (tA - start > EPS) out += Seg(start, (tA - start).coerceAtMost(len), SegSrc.Still(c.item, 0.0), c, ramp, fx)
        if (b - a > EPS) out += Seg(tA, (b - a) / sp, SegSrc.Media(c.item, a, c.speed), c, ramp, fx)
        // after the file's end: the last frame (just before the end – there is no frame there)
        if (md != null && s1 > md + EPS) {
            val tB = maxOf(start + (md - s0) / sp, start)
            if (start + len - tB > EPS) out += Seg(tB, start + len - tB, SegSrc.Still(c.item, (md - LAST_FRAME).coerceAtLeast(0.0)), c, ramp, fx)
        }
        return out
    }

    /** How far before the file's end the last frame is taken (nearest frame, see FrameHolds). */
    private const val LAST_FRAME = 0.001

    /** The freeze frames the plan needs (freeze frames and the transitions' padding where the file runs out): (media item, source time). */
    fun stillsOf(plan: LayerPlan?): List<Pair<Long, Double>> =
        plan?.layers.orEmpty().flatMap { l -> l.segs.mapNotNull { (it.src as? SegSrc.Still)?.let { st -> st.item to st.sec } } }.distinct()

    private fun shift(src: SegSrc, by: Double): SegSrc = when (src) {
        is SegSrc.Media -> src.copy(srcIn = src.srcIn + by * src.speed)
        is SegSrc.Still -> src
    }

    /** A sequence cannot overlap: shorten a segment that runs into the next. */
    private fun nonOverlapping(segs: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>()
        for (s in segs) {
            val prevEnd = out.lastOrNull()?.end ?: 0.0
            if (s.start < prevEnd - EPS) {
                val cut = prevEnd - s.start
                if (s.dur - cut > EPS) out += s.copy(start = prevEnd, dur = s.dur - cut, src = shift(s.src, cut))
            } else out += s
        }
        return out
    }
}

/**
 * Interprets frame timestamps as composition time in seconds. Media3 sends composition time, but an offset
 * can occur (e.g. 10^12 µs). During playback and on every seek the time base gets the player's position
 * ([hintUs]); of the possible interpretations the one closest to it is chosen – an offset once chosen is never
 * used blindly (an earlier miscalibration could otherwise give effects that always landed at the wrong time,
 * e.g. a tracked window beside the object). If no interpretation fits, the offset is calibrated against the
 * player's position. Without a position (the export) the timestamp applies directly. If the time cannot be
 * interpreted the answer is null and the caller shows the image without time-dependent effects.
 */
class TimeBase(private val total: Double, private val shift: Double = 0.0) {
    @Volatile var hintUs: Long = -1
    @Volatile private var offsetUs: Long? = null
    /** The latest interpretation (for the diagnostics view): timestamp → seconds, and the player's position then. */
    @Volatile var last: Triple<Long, Double, Long>? = null
        private set

    /** Timeline time for a frame: composition time + [shift] (a part of the timeline does not start at 0). */
    fun seconds(presentationTimeUs: Long): Double? = interpret(presentationTimeUs)?.also { last = Triple(presentationTimeUs, it, hintUs) }?.plus(shift)

    private fun interpret(pts: Long): Double? {
        fun ok(us: Long) = us / 1e6 in -0.5..(total + 1.0)
        val h = hintUs
        val off = offsetUs
        if (h >= 0) {
            // the interpretations: the previous offset, none, 10^12 – the one closest to the player's position
            var best = Long.MIN_VALUE
            var bestD = Long.MAX_VALUE
            for (c in longArrayOf(if (off != null) pts - off else pts, pts, pts - BIG)) {
                if (!ok(c)) continue
                val d = kotlin.math.abs(c - h)
                if (d < bestD) { bestD = d; best = c }
            }
            if (best != Long.MIN_VALUE && bestD <= TOLERANCE_US) {
                offsetUs = pts - best
                return best / 1e6
            }
            // no interpretation matches the player's position: calibrate against it
            offsetUs = pts - h
            return h / 1e6
        }
        if (off != null && ok(pts - off)) return (pts - off) / 1e6
        if (ok(pts)) return pts / 1e6
        if (ok(pts - BIG)) { offsetUs = BIG; return (pts - BIG) / 1e6 }
        return null
    }

    companion object {
        private const val BIG = 1_000_000_000_000L
        /** Frames are processed a bit ahead of display; more deviation than this is a different time base. */
        const val TOLERANCE_US = 1_500_000L
    }
}

/**
 * Where an overlay lands: the point (ovX, ovY) in the overlay's own image (NDC) is placed at (bgX, bgY) in
 * the program image, scaled and rotated around that point. Used by both Media3 and the preview.
 */
data class Placement(val ovX: Float, val ovY: Float, val bgX: Float, val bgY: Float, val scale: Float, val rotation: Float, val alpha: Float)

/** Title/caption at time t → placement (the title template's animation + the engine's motion + fades). */
fun placementOf(item: OverlayItem, t: Double, w: Int, h: Int): Placement {
    val c = item.clip?.at(t)
    val a = item.alpha(t)
    val mx = if (c?.x != null) (c.x / w * 2 - 1).toFloat() else 0f
    val my = if (c?.y != null) (1 - c.y / h * 2).toFloat() else 0f
    val k = ((c?.scale ?: 100.0) / 100.0).toFloat()
    val rot = (c?.rotation ?: 0.0).toFloat()
    val title = item.overlay as? Overlay.Title ?: return Placement(0f, 0f, 0f, 0f, 1f, 0f, a)
    val pose = TitleMotion.pose(title.t.look, t - item.start, item.end - item.start, w, h, TitleArt.rollHeight(title.t, w, h))
    // follows a tracked object: the displacement since the title's start (fraction of the image, y down → NDC)
    val f = item.clip?.let { TitleFollow.offset(it.id, item.start, t) }
    val fx = (f?.get(0) ?: 0f) * 2f
    val fy = -(f?.get(1) ?: 0f) * 2f
    return Placement(pose.anchorX, pose.anchorY, pose.anchorX + pose.dx + mx + fx, pose.anchorY + pose.dy + my + fy, pose.scale * k, -rot, a * pose.alpha)
}

/** A title following a tracked window: the clip below and the node with the window. */
data class Follow(val clip: Long, val node: Int) {
    fun encode() = "$clip:$node"
    companion object {
        fun parse(s: String): Follow? = s.split(':').takeIf { it.size == 2 }?.let { (a, b) -> a.toLongOrNull()?.let { c -> b.toIntOrNull()?.let { Follow(c, it) } } }
    }
}

/**
 * Titles that follow tracked windows (playback, the preview and the export read from here – like
 * LiveGrades). Published by the app when grades, the timeline or the links change.
 */
object TitleFollow {
    @Volatile private var follows: Map<Long, Follow> = emptyMap()
    @Volatile private var grades: Map<Long, ClipGrade> = emptyMap()
    @Volatile private var clips: Map<Long, ClipSnap> = emptyMap()
    /** Increments on every publish (the title layer is redrawn). */
    var revision by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun publish(follows: Map<Long, Follow>, grades: Map<Long, ClipGrade>, snapshot: Snapshot?) {
        this.follows = HashMap(follows)
        this.grades = HashMap(grades)
        this.clips = snapshot?.playable()?.sequence?.video.orEmpty().flatMap { it.clips }.associateBy { it.id }
        revision++
    }

    /** The displacement (dx, dy, fraction of the image) for title [title] at timeline [t], since the title's start [start]. */
    fun offset(title: Long, start: Double, t: Double): FloatArray? {
        val f = follows[title] ?: return null
        return offsetOf(f, clips[f.clip] ?: return null, grades[f.clip] ?: return null, start, t)
    }

    /** Pure computation (testable): the window's displacement between [start] and [t] on the timeline. */
    fun offsetOf(f: Follow, c: ClipSnap, g: ClipGrade, start: Double, t: Double): FloatArray? {
        val track = g.nodes.firstOrNull { it.id == f.node }?.win?.track ?: return null
        fun src(x: Double) = c.sourceAt(x)
        val a = track.at(src(start)); val b = track.at(src(t))
        return floatArrayOf(b[0] - a[0], b[1] - a[1])
    }
}

@OptIn(UnstableApi::class)
fun Placement.toSettings(): OverlaySettings = StaticOverlaySettings.Builder()
    .setAlphaScale(alpha.coerceIn(0f, 1f))
    .setOverlayFrameAnchor(ovX, ovY)
    .setBackgroundFrameAnchor(bgX, bgY)
    .setScale(scale.coerceAtLeast(0.001f), scale.coerceAtLeast(0.001f))
    .setRotationDegrees(rotation)
    .build()

/** The engine's motion (sequence pixels, %, degrees clockwise) → Media3 overlay (NDC, factor, degrees counterclockwise). */
@OptIn(UnstableApi::class)
fun motionSettings(c: ClipSnap?, alpha: Float, w: Int, h: Int): OverlaySettings {
    val b = StaticOverlaySettings.Builder().setAlphaScale(alpha.coerceIn(0f, 1f))
    if (c != null && c.hasMotion(w, h)) {
        val px = c.x ?: (w / 2.0)
        val py = c.y ?: (h / 2.0)
        b.setBackgroundFrameAnchor((px / w * 2 - 1).toFloat(), (1 - py / h * 2).toFloat())
        val k = (c.scale / 100.0).toFloat().coerceAtLeast(0.001f)
        b.setScale(k, k)
        b.setRotationDegrees((-c.rotation).toFloat())
    }
    return b.build()
}

/**
 * Compositor settings per layer and frame: opacity (incl. transitions), position, scale, rotation.
 * The bottom layer is never fully hidden, so the image cannot go fully black from a time error.
 */
@OptIn(UnstableApi::class)
class LayerCompositor(
    private val plan: LayerPlan,
    /** The clip's blend mode (Klipp's BlendMode, see LayerFx); NORMAL = ordinary transparency. */
    private val blendOf: (Long) -> BlendMode = { BlendMode.NORMAL },
    /** Output image size (pre-render: the preview's); 0 = the timeline's. Motion is computed in timeline pixels. */
    private val outW: Int = 0, private val outH: Int = 0,
) : VideoCompositorSettings {
    val time = TimeBase(plan.length, plan.origin)
    private val hidden = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    /** For the diagnostics view: latest call per layer (raw time, interpreted time, opacity, count). */
    val stats = Array(plan.layers.size) { LongArray(4) }
    @Volatile var outputSizeCalls = 0

    override fun getOutputSize(inputSizes: MutableList<Size>): Size {
        outputSizeCalls++
        return if (outW > 0 && outH > 0) Size(outW, outH) else Size(plan.width, plan.height)
    }

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val layer = plan.layers.getOrNull(inputId) ?: return hidden
        val bottom = inputId == plan.layers.lastIndex
        val t = time.seconds(presentationTimeUs)
        val seg = t?.let { layer.at(it) }
        val out = when {
            t == null || seg == null -> if (bottom) StaticOverlaySettings.Builder().build() else hidden
            else -> motionSettings(seg.clip?.at(t), seg.alpha(t), plan.width, plan.height).let { o ->
                val mode = seg.clip?.let { blendOf(it.id) } ?: BlendMode.NORMAL
                if (mode == BlendMode.NORMAL || bottom) o else BlendOverlay(o, mode.ordinal)
            }
        }
        stats.getOrNull(inputId)?.let { st ->
            st[0] = presentationTimeUs
            st[1] = t?.let { (it * 1000).toLong() } ?: -1
            st[2] = (out.alphaScale * 100).toLong()
            st[3]++
        }
        return out
    }

    fun describe(): String = buildString {
        append("compositor: output size ${outputSizeCalls}×\n")
        plan.layers.forEachIndexed { i, l ->
            val st = stats[i]
            append("  $i ${l.name}: ${st[3]} frames, raw ${st[0] / 1000} ms → ${if (st[1] < 0) "?" else "${st[1]} ms"}, opacity ${st[2]} %\n")
        }
    }
}

/** A layer's settings with blend mode (the compositor then blends in a shader, see KlippBlend). */
@OptIn(UnstableApi::class)
private class BlendOverlay(base: OverlaySettings, private val mode: Int) : OverlaySettings by base, androidx.media3.effect.KlippBlend.Settings {
    override fun getBlendMode(): Int = mode
}

/** Titles and captions as an overlay on the whole image; the images are drawn when needed and cached. */
@OptIn(UnstableApi::class)
class TimedOverlay(
    allItems: List<OverlayItem>,
    captions: Boolean,
    private val w: Int,
    private val h: Int,
    val time: TimeBase,
) : BitmapOverlay() {
    private val empty: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) } }
    private val cache = object : LinkedHashMap<Overlay, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Overlay, Bitmap>?) = size > 6
    }
    private val hidden = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    private val items = allItems.filter { (it.overlay is Overlay.Caption) == captions }

    fun at(t: Double): OverlayItem? = items.lastOrNull { t >= it.start && t < it.end }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val t = time.seconds(presentationTimeUs) ?: return empty
        val item = at(t) ?: return empty
        return cache.getOrPut(item.overlay) { OverlayRenderer.draw(item.overlay, w, h) }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val t = time.seconds(presentationTimeUs) ?: return hidden
        val item = at(t) ?: return hidden
        return placementOf(item, t, w, h).toSettings()
    }
}

/** Fade to/from black for the simple path (multiplies RGB). Outside the ramps: no change. */
@OptIn(UnstableApi::class)
class BlackFade(private val ramps: List<Ramp>, val time: TimeBase, private val skip: List<ClosedFloatingPointRange<Double>> = emptyList()) : androidx.media3.effect.RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val t = time.seconds(presentationTimeUs)
        // pre-rendered: the fade is already in the image
        val k = if (t == null || skip.any { t in it }) 1f else ramps.firstOrNull { it.covers(t) }?.at(t) ?: 1f
        return floatArrayOf(k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 1f)
    }
}

/** Motion for a clip on the simple path: the same conversion as the compositor, as a matrix in NDC. */
@OptIn(UnstableApi::class)
class MotionMatrix(private val c: ClipSnap, private val w: Int, private val h: Int, private val time: TimeBase? = null) : androidx.media3.effect.MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        // with keyframes: the value at the frame's time (unknown time → the clip's base value)
        val t = if (c.animatedMotion) time?.seconds(presentationTimeUs) else null
        return matrixOf(if (t != null) c.at(t) else c, w, h)
    }

    companion object {
        fun matrixOf(c: ClipSnap, w: Int, h: Int): android.graphics.Matrix {
            val hw = w / 2f
            val hh = h / 2f
            val k = (c.scale / 100.0).toFloat()
            val dx = ((c.x ?: (w / 2.0)) - w / 2.0).toFloat()
            val dy = -((c.y ?: (h / 2.0)) - h / 2.0).toFloat() // NDC has y up
            return android.graphics.Matrix().apply {
                setScale(hw, hh) // NDC → pixels (around the center)
                postScale(k, k)
                postRotate((-c.rotation).toFloat()) // clockwise on screen
                postTranslate(dx, dy)
                postScale(1f / hw, 1f / hh) // back to NDC
            }
        }
    }
}

object OverlayRenderer {
    fun draw(o: Overlay, w: Int, h: Int): Bitmap = when (o) {
        // the title draws its own image – no needless full-size image (4K: 33 MB)
        is Overlay.Title -> TitleArt.draw(o.t, w, h)
        is Overlay.Caption -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            bmp.eraseColor(Color.TRANSPARENT)
            drawCaption(Canvas(bmp), o.text, o.style, w, h)
        }
    }

    /** A caption with the track's style (font, size, color, background box, outline, alignment, position). */
    fun drawCaption(cv: Canvas, text: String, st: CaptionStyle, w: Int, h: Int) {
        val scale = h / 1080f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = st.size.coerceIn(8f, 300f) * scale
            typeface = Fonts.typeface(st.font)
            textAlign = when (st.align) { "left" -> Paint.Align.LEFT; "right" -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            color = st.color
        }
        val lines = text.split('\n').filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        val lh = p.textSize * st.lineSpacing.coerceIn(0.8f, 3f)
        val margin = st.margin.coerceIn(0f, 0.45f) * h
        val block = lh * (lines.size - 1) + p.textSize
        // the first line's baseline according to the position
        var y = when (st.anchor) {
            "top" -> margin + p.textSize
            "middle" -> (h - block) / 2f + p.textSize
            else -> h - margin - lh * (lines.size - 1)
        }
        val side = w * 0.05f
        val x = when (st.align) { "left" -> side; "right" -> w - side; else -> w / 2f }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = st.backgroundColor }
        val stroke = if (st.outline > 0f) Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = st.outline * scale * 2f; color = st.outlineColor; strokeJoin = Paint.Join.ROUND } else null
        for (line in lines) {
            val tw = p.measureText(line)
            val pad = p.textSize * 0.3f
            if (st.background) {
                val l = when (st.align) { "left" -> x; "right" -> x - tw; else -> x - tw / 2 }
                cv.drawRoundRect(RectF(l - pad, y - p.textSize, l + tw + pad, y + p.textSize * 0.3f), pad, pad, bg)
            }
            stroke?.let { cv.drawText(line, x, y, it) }
            cv.drawText(line, x, y, p)
            y += lh
        }
    }
}

/** Keyframed opacity on the simple path (against black): RGB × opacity at the frame's time. */
@OptIn(UnstableApi::class)
class KeyframedOpacity(private val c: ClipSnap, private val time: TimeBase) : androidx.media3.effect.RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val t = time.seconds(presentationTimeUs)
        val k = ((if (t != null) c.at(t) else c).opacity / 100.0).toFloat().coerceIn(0f, 1f)
        return floatArrayOf(k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 1f)
    }
}

/**
 * Fonts for titles and captions: the phone's built-in families and custom ones (.ttf/.otf) imported into
 * the app. The names are saved in the project; unknown names become Inter/sans.
 */
object Fonts {
    /**
     * Display name → Android family. "Inter" is the engine default (FilmCraft) – here the phone's sans. The names are
     * saved in projects (CaptionStyle.font / title fonts), so they stay unchanged (Swedish).
     */
    val builtIn = linkedMapOf(
        "Inter" to "sans-serif", "Sans fet" to "sans-serif-black", "Smal" to "sans-serif-condensed", "Lätt" to "sans-serif-light",
        "Serif" to "serif", "Mono" to "monospace", "Handskrift" to "cursive", "Avslappnad" to "casual",
    )

    /**
     * A font family that comes with the app (assets/fonts, see fonts.json): [variable] = one file with a weight axis
     * (bold = weight 700), otherwise separate files per style; [styles] = the files there are (regular, bold, italic,
     * boldItalic). A style without a file is synthesized by Android (slanted / thickened).
     */
    class Bundled(val name: String, val dir: String, val variable: Boolean, val styles: Set<String>)

    /** The app's font families by name (empty until [init]). */
    var bundled: Map<String, Bundled> = emptyMap()
        private set
    private var assets: android.content.res.AssetManager? = null
    private var dir: java.io.File? = null
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Typeface>()
    /** Imported fonts (names). */
    val custom = androidx.compose.runtime.mutableStateListOf<String>()

    /** All font names in picker order: the phone's, the app's, the imported ones. */
    val all: List<String> get() = builtIn.keys.toList() + bundled.keys + custom

    fun init(context: android.content.Context) {
        if (dir != null) return
        assets = context.assets
        bundled = runCatching {
            val a = org.json.JSONArray(context.assets.open("fonts/fonts.json").bufferedReader().use { it.readText() })
            (0 until a.length()).map { i -> a.getJSONObject(i) }.associate { o ->
                val st = o.getJSONArray("styles").let { s -> (0 until s.length()).map { s.getString(it) }.toSet() }
                // a bundled name the phone's list already has (Inter) gets its own name, so saved projects keep their look
                val n = o.getString("name").let { if (it in builtIn) "$it Display" else it }
                n to Bundled(n, o.getString("dir"), o.getBoolean("variable"), st)
            }
        }.getOrDefault(emptyMap())
        dir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
        custom.clear()
        dir?.listFiles { f -> f.extension.lowercase() in setOf("ttf", "otf") }?.sortedBy { it.name.lowercase() }?.forEach { custom += it.nameWithoutExtension }
    }

    fun typeface(name: String): Typeface = typeface(name, bold = false, italic = false)

    /** The font [name] in the style asked for: real bold/italic where the family has it, synthesized otherwise. */
    fun typeface(name: String, bold: Boolean, italic: Boolean): Typeface = cache.getOrPut("$name|$bold|$italic") {
        val style = styleOf(bold, italic)
        builtIn[name]?.let { fam ->
            return@getOrPut if (fam == "sans-serif-black") Typeface.create("sans-serif", styleOf(true, italic))
                else Typeface.create(fam, style)
        }
        bundled[name]?.let { b -> bundledFace(b, bold, italic)?.let { return@getOrPut it } }
        val f = dir?.let { d -> listOf("ttf", "otf").map { java.io.File(d, "$name.$it") }.firstOrNull { it.isFile } }
        val base = f?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        if (style == Typeface.NORMAL) base else Typeface.create(base, style)
    }

    private fun styleOf(bold: Boolean, italic: Boolean) = when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }

    private fun bundledFace(b: Bundled, bold: Boolean, italic: Boolean): Typeface? = runCatching {
        val am = assets ?: return null
        fun file(k: String) = "fonts/${b.dir}/${k.replaceFirstChar { it.uppercase() }}.ttf"
        if (b.variable) {
            val it = italic && "italic" in b.styles
            val tf = Typeface.Builder(am, file(if (it) "italic" else "regular"))
                .setFontVariationSettings("'wght' ${if (bold) 700 else 400}").build()
            // no italic file: slanted by Android
            if (italic && !it) Typeface.create(tf, Typeface.ITALIC) else tf
        } else {
            val want = when { bold && italic -> "boldItalic"; bold -> "bold"; italic -> "italic"; else -> "regular" }
            val have = want.takeIf { it in b.styles } ?: when {
                bold && italic && "bold" in b.styles -> "bold"
                bold && italic && "italic" in b.styles -> "italic"
                else -> "regular"
            }
            val tf = Typeface.Builder(am, file(have)).build()
            // the styles the file lacks are synthesized
            val missing = styleOf(bold && "bold" !in have.lowercase(), italic && "italic" !in have.lowercase())
            if (missing == Typeface.NORMAL) tf else Typeface.create(tf, missing)
        }
    }.getOrNull()

    /** Import a font; returns the name (throws if the file cannot be read as a font). */
    fun import(context: android.content.Context, uri: android.net.Uri): String {
        init(context)
        val raw = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Typsnitt.ttf"
        val ext = raw.substringAfterLast('.', "ttf").lowercase().takeIf { it == "ttf" || it == "otf" } ?: "ttf"
        val name = raw.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").take(40)
        val f = java.io.File(dir, "$name.$ext")
        context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        runCatching { Typeface.createFromFile(f) }.getOrElse { f.delete(); error("the file is not a font") }
        cache.keys.removeAll { it.startsWith("$name|") }
        if (name !in custom) custom += name
        return name
    }
}

