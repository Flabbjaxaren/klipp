package se.gendut.klipp

import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import se.gendut.klipp.engine.playable
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clipToBounds
import se.gendut.klipp.engine.applyTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.ClipSnap
import se.gendut.klipp.engine.Overlay
import se.gendut.klipp.engine.OverlayItem
import se.gendut.klipp.engine.ProjectController
import se.gendut.klipp.engine.TextSnap
import se.gendut.klipp.engine.TitleArt
import se.gendut.klipp.engine.placementOf
import se.gendut.klipp.engine.at
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Still frames from the video for preview and scrubbing. The decoders (MediaMetadataRetriever) are reused
 * per file and everything runs in order on a dedicated thread – a request that has gone stale (the playhead
 * has moved on) is cancelled before it starts, so scrubbing keeps up.
 */
/** What [LivePreview] grades for one clip: the still, its grade and look, and whether a control is being dragged. */
private data class GradeReq(
    val tag: Any, val src: ImageBitmap, val g: se.gendut.klipp.engine.ClipGrade?, val look: se.gendut.klipp.engine.Look,
    val keyNode: Int?, val interacting: Boolean,
)

object Frames {
    // kept by size, not count: a 1920 px frame is 8 MB, a scrub frame a fraction of that
    private val cache = LinkedHashMap<String, ImageBitmap>(32, 0.75f, true)
    private const val CACHE_BYTES = 96L shl 20
    private fun put(k: String, img: ImageBitmap) = synchronized(cache) {
        cache[k] = img
        var bytes = cache.values.sumOf { it.width.toLong() * it.height * 4 }
        val it = cache.entries.iterator()
        while (bytes > CACHE_BYTES && cache.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == k) continue
            bytes -= e.value.width.toLong() * e.value.height * 4
            it.remove()
        }
    }
    private val retrievers = object : LinkedHashMap<Uri, MediaMetadataRetriever>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, MediaMetadataRetriever>?): Boolean {
            if (size > 3) { runCatching { eldest?.value?.release() }; return true }
            return false
        }
    }
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "frames").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    private val extractors = object : LinkedHashMap<Uri, android.media.MediaExtractor>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, android.media.MediaExtractor>?): Boolean {
            if (size > 3) { runCatching { eldest?.value?.release() }; return true }
            return false
        }
    }

    fun key(uri: Uri, sec: Double, exact: Boolean) = "$uri@${(sec * 25).roundToInt()}${if (exact) "e" else "s"}"
    fun cached(uri: Uri, sec: Double): ImageBitmap? = synchronized(cache) { cache[key(uri, sec, true)] }

    /**
     * Nearest keyframe to [sec] within the clip's part of the source ([lo], [hi]) – so a fast scrub frame never
     * shows material that was trimmed away. null = no keyframe nearby (the exact frame is used instead).
     */
    private fun syncNear(context: Context, uri: Uri, sec: Double, lo: Double, hi: Double): Double? {
        // if opening fails the object is released (otherwise a native object leaked on every scrub step on a broken file)
        val x = extractors[uri] ?: android.media.MediaExtractor().also { e ->
            try {
                e.setDataSource(context, uri, null)
                val v = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?: error("no video track")
                e.selectTrack(v)
            } catch (t: Throwable) {
                e.release()
                throw t
            }
            extractors[uri] = e
        }
        val us = (sec * 1_000_000).toLong()
        x.seekTo(us, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val prev = x.sampleTime.takeIf { it >= 0 }?.div(1_000_000.0)
        x.seekTo(us, android.media.MediaExtractor.SEEK_TO_NEXT_SYNC)
        val next = x.sampleTime.takeIf { it >= 0 }?.div(1_000_000.0)
        return pickSync(prev, next, sec, lo, hi)
    }

    /** Of the keyframes before/after [sec]: the nearest one inside the clip ([lo], [hi]) and at most 1 s away. */
    fun pickSync(prev: Double?, next: Double?, sec: Double, lo: Double, hi: Double): Double? =
        listOfNotNull(prev, next).filter { it >= lo - 0.001 && it < hi && kotlin.math.abs(it - sec) < 1.0 }
            .minByOrNull { kotlin.math.abs(it - sec) }

    /**
     * @param exact exact frame (slower) or the nearest keyframe within [lo]–[hi] (fast, for scrubbing)
     */
    suspend fun load(context: Context, uri: Uri, sec: Double, exact: Boolean = true, lo: Double = 0.0, hi: Double = Double.MAX_VALUE): ImageBitmap? =
        loadTimed(context, uri, sec, exact, lo, hi)?.second

    /** Like [load], but with the frame's own source time (a fast scrub frame is the keyframe – not [sec]). */
    suspend fun loadTimed(context: Context, uri: Uri, sec: Double, exact: Boolean = true, lo: Double = 0.0, hi: Double = Double.MAX_VALUE): Pair<Double, ImageBitmap>? {
        synchronized(cache) { cache[key(uri, sec, true)] }?.let { return sec to it }
        return withContext(worker) {
            runCatching {
                val at = if (exact) sec else (runCatching { syncNear(context, uri, sec, lo, hi) }.getOrNull() ?: sec)
                val fast = !exact && at != sec
                val k = if (fast) "$uri@k${(at * 1000).roundToInt()}" else key(uri, sec, true)
                synchronized(cache) { cache[k] }?.let { return@runCatching at to it }
                val r = retrievers[uri] ?: MediaMetadataRetriever().also { m ->
                    try { m.setDataSource(context, uri) } catch (t: Throwable) { m.release(); throw t }
                    retrievers[uri] = m
                }
                val us = (at * 1_000_000).toLong()
                val opt = if (fast) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
                val px = se.gendut.klipp.engine.Perf.previewWidth()
                // (decoding happens in mediaserver, not on this thread – a performance hint here does nothing)
                val f = if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, opt, px, px) else r.getFrameAtTime(us, opt)
                val img = f?.asImageBitmap() ?: return@runCatching null
                put(k, img)
                at to img
            }.getOrNull()
        }
    }
}

/** A dedicated thread for the preview's color grading (GPU or CPU). */
object GradeWorker {
    val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "grade").apply { isDaemon = true } }.asCoroutineDispatcher()
    /** At most ~15 graded preview frames per second while dragging – always the latest request. */
    const val MIN_GAP_MS = 66L
    @Volatile var lastRun = 0L
    fun <T> hinted(block: () -> T): T = se.gendut.klipp.engine.Perf.gradeHint?.run(block) ?: block()
}

/** Title bitmaps for the preview, at half resolution. Rendered outside the draw phase (see LivePreview). */
private object TitleBitmaps {
    private val cache = object : LinkedHashMap<Triple<TextSnap, Int, Int>, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<TextSnap, Int, Int>, ImageBitmap>?) = size > 6
    }
    fun cached(t: TextSnap, w: Int, h: Int): ImageBitmap? = synchronized(cache) { cache[Triple(t, w, h)] }
    fun get(t: TextSnap, w: Int, h: Int): ImageBitmap {
        cached(t, w, h)?.let { return it }
        val img = TitleArt.draw(t, w, h).asImageBitmap()
        synchronized(cache) { cache[Triple(t, w, h)] = img }
        return img
    }
}

/**
 * Preview while dragging a slider or in the image: the program image at the playhead is drawn by the app
 * itself (a still from each video layer + titles), with the clip's live values. Playback gets the real
 * values when the finger is lifted.
 */
@Composable
fun LivePreview(
    controller: ProjectController, state: EditorState, modifier: Modifier = Modifier, gs: GradeState? = null,
    look: se.gendut.klipp.engine.Look = se.gendut.klipp.engine.Look.NONE,
    /** Show even without dragging – e.g. until the player has shown its first frame, or while a new look is being built in. */
    force: Boolean = false,
) {
    // shown while dragging a slider/in the image/a color wheel, scrubbing, or when the player has no frame yet
    state.trimView?.let { TrimFrame(controller, it, modifier); return }
    val live = state.live?.takeUnless { it.isAudio }
    if (live == null && !state.scrubbing && gs?.live == null && gs?.shownKey == null && !force) return
    val q = controller.snapshot.playable().sequence ?: return
    val ctx = LocalContext.current
    val t = state.playhead.toDouble()
    val w = q.width.coerceAtLeast(2)
    val h = q.height.coerceAtLeast(2)
    // V1 at the bottom; clips covering the playhead (the selected clip is shown even if the playhead is outside it)
    val layers = q.video.take(3).filter { it.enabled }.mapNotNull { tr ->
        tr.clips.firstOrNull { t >= it.start - 5e-4 && t < it.end - 5e-4 } ?: tr.clips.firstOrNull { t >= it.start - 5e-4 && t < it.end + 5e-4 } ?: live?.let { l -> tr.clips.firstOrNull { it.id == l.id } }
    }.map { c -> c.at(t).copy(kf = emptyMap()).let { if (live != null && it.id == live.id) it.withLive(live) else it } }
    // latest frame per clip, with its own source time – shown until the next one is decoded (never black in between)
    val frames = remember { mutableStateMapOf<Long, Pair<Double, ImageBitmap>>() }
    // stills already in the cache (written into frames after composition)
    val hits = HashMap<Long, Pair<Double, ImageBitmap>>()
    // the source time is kept within the clip's in/out (never material that was trimmed away)
    fun srcSec(c: ClipSnap) = c.sourceAt(c.start + (t - c.start).coerceIn(0.0, (c.duration - 0.04).coerceAtLeast(0.0)))
    for (c in layers) if (c.text == null) {
        val media = controller.uriOf(c.item) ?: continue
        // the proxy when there is one: 1080p with dense keyframes = much faster stills while scrubbing
        val uri = se.gendut.klipp.engine.Proxies.videoUri(media)
        val sec = srcSec(c)
        val lo = c.sourceIn
        val hi = c.sourceIn + c.duration * c.speed
        LaunchedEffect(media) { se.gendut.klipp.engine.ScrubFrames.ensure(ctx, media) }
        // exact frame from the cache, otherwise (while the finger drags) the low-res scrub frame already in memory
        (Frames.cached(uri, sec)?.let { sec to it } ?: if (state.scrubbing) se.gendut.klipp.engine.ScrubFrames.peek(media, sec, lo, hi) else null)
            ?.let { hits[c.id] = it }
        // while the finger drags: the scrub frame (low-res, immediate) or the nearest keyframe; the exact frame at full
        // resolution once the playhead has rested for a moment or the finger is lifted
        LaunchedEffect(c.id, (sec * 25).roundToInt()) {
            if (state.scrubbing) {
                val quick = se.gendut.klipp.engine.ScrubFrames.load(media, sec, lo, hi)
                if (quick != null) { frames[c.id] = quick; se.gendut.klipp.engine.ScrubFrames.prefetch(media, sec, lo, hi) }
                else Frames.loadTimed(ctx, uri, sec, exact = false, lo = lo, hi = hi)?.let { frames[c.id] = it }
                kotlinx.coroutines.delay(120)
            }
            Frames.loadTimed(ctx, uri, sec, exact = true)?.let { frames[c.id] = it }
        }
    }
    // the clip's grade (color page) and the timeline look: on the GPU (AGSL, milliseconds), otherwise on the
    // CPU at half resolution. A dedicated thread: only the latest request waits, nothing queues up.
    val graded = remember { mutableStateMapOf<Long, Pair<Any, ImageBitmap>>() }
    val ungraded = HashSet<Long>()
    val shownTimes = HashMap<Long, Double>()
    // the preview goes away: the window is drawn for the playhead again
    if (gs != null) androidx.compose.runtime.DisposableEffect(gs) { onDispose { gs.stillTimes = emptyMap() } }
    for (c in layers) if (c.text == null) {
        val (srcTime, src) = hits[c.id] ?: frames[c.id] ?: continue
        shownTimes[c.id] = srcTime
        // "show key" on the color page: the node's key in grayscale (without the look)
        val keyNode = gs?.shownKey?.takeIf { it.first == c.id }?.second
        // tracked windows: where they are at the shown still's own source time – not the playhead's (during a
        // scrub the image is a keyframe or the previous frame, and the window then landed beside the object)
        val g = gs?.shown(c.id)?.takeUnless { it.isNeutral && keyNode == null }?.at(srcTime)
        if (g == null && look.isNeutral) { ungraded += c.id; continue }
        val lk = if (keyNode != null) se.gendut.klipp.engine.Look.NONE else look
        val tag = Triple(src, g, if (keyNode != null) keyNode else look)
        // while scrubbing, film is shown with its colors only (the full film on the CPU takes longer than a frame, and
        // every new frame cancelled it – the ungraded log image was shown until the finger was lifted)
        // the same while a slider, wheel or curve is dragged: the CPU's full film runs at 480 px, far below the
        // screen, and every change waited for it
        val interacting = state.scrubbing || live != null || gs?.live != null
        key(c.id) {
            // one grading at a time per clip, always of the latest request; a grading in progress is never cancelled –
            // with a slow chain (a heavy node chain at full size) and a finger that keeps moving, cancelling meant that
            // nothing was ever shown until the finger stopped
            val req by androidx.compose.runtime.rememberUpdatedState(GradeReq(tag, src, g, lk, keyNode, interacting))
            LaunchedEffect(Unit) {
                snapshotFlow { req }.distinctUntilChanged().conflate().collect { r ->
                    // the time limit counts from the previous frame: during a drag the latest position is graded every
                    // 66 ms at most instead of on every pointer event (less garbage, no queue)
                    val wait = GradeWorker.lastRun + GradeWorker.MIN_GAP_MS - android.os.SystemClock.uptimeMillis()
                    if (wait > 0) kotlinx.coroutines.delay(wait)
                    GradeWorker.lastRun = android.os.SystemClock.uptimeMillis()
                    val out = kotlinx.coroutines.withContext(GradeWorker.dispatcher) { GradeWorker.hinted {
                        val b = r.src.asAndroidBitmap()
                        se.gendut.klipp.engine.GpuGrade.apply(b, r.g, r.look, r.keyNode, filmColorOnly = r.interacting)?.asImageBitmap() ?: run {
                            var small = android.graphics.Bitmap.createScaledBitmap(b, 480, (480f * b.height / b.width).roundToInt().coerceAtLeast(1), true)
                            if (r.g != null) small = r.g.applyTo(small, r.keyNode)
                            r.look.applyTo(small).asImageBitmap()
                        }
                    } }
                    graded[c.id] = r.tag to out
                }
            }
        }
    }
    // titles are rendered at half resolution on a background thread; the previous image is shown until the new one is ready
    val titles = remember { mutableStateMapOf<Long, ImageBitmap>() }
    val halves = HashMap<Long, TextSnap>()
    for (c in layers) {
        val text = c.text ?: continue
        val half = TextSnap(text.text, text.size / 2, text.x?.div(2), text.y?.div(2), text.color, text.name)
        halves[c.id] = half
        LaunchedEffect(c.id, half, w, h) {
            val img = TitleBitmaps.cached(half, w / 2, h / 2)
                ?: withContext(Dispatchers.Default) { runCatching { TitleBitmaps.get(half, w / 2, h / 2) }.getOrNull() } ?: return@LaunchedEffect
            titles[c.id] = img
        }
    }
    // the last image shown at the bottom: shown instead of black until a new clip's first frame is decoded
    val lastBottom = remember { arrayOfNulls<ImageBitmap>(1) }
    val bottom = layers.firstOrNull { it.text == null }
    val bottomImg = bottom?.let { b -> graded[b.id]?.second ?: (hits[b.id] ?: frames[b.id])?.second }
    // write after composition, and drop images for clips no longer shown
    SideEffect {
        if (bottomImg != null) lastBottom[0] = bottomImg
        val ids = layers.mapTo(HashSet()) { it.id }
        // the same image and time are not written again (Pair compares by content – otherwise a new write every composition)
        hits.forEach { (id, p) -> if (frames[id] != p) frames[id] = p }
        if (gs != null && gs.stillTimes != shownTimes) gs.stillTimes = HashMap(shownTimes)
        ungraded.forEach { graded.remove(it) }
        for (m in listOf(frames, graded, titles)) (m.keys - ids).forEach { m.remove(it) }
    }
    // until every clip's first graded still is ready, nothing is drawn: the player's frame underneath (already graded)
    // shows through. Otherwise every drag began with a flash of the ungraded (log) still, or black. Not when forced –
    // then the player has no frame to show.
    val ready = force || layers.all { c -> c.text != null || (if (c.id in ungraded) (hits[c.id] ?: frames[c.id]) != null else graded[c.id] != null) }
    if (!ready) return
    Canvas(modifier.fillMaxSize().clipToBounds().testTag("livepreview")) {
        drawRect(Color.Black)
        val s = size.width / w
        if (bottom != null && bottomImg == null) lastBottom[0]?.let { img ->
            val f = min(w.toFloat() / img.width, h.toFloat() / img.height) * s
            val iw = img.width * f; val ih = img.height * f
            drawImage(img, dstOffset = IntOffset(((size.width - iw) / 2).roundToInt(), ((size.height - ih) / 2).roundToInt()), dstSize = IntSize(iw.roundToInt().coerceAtLeast(1), ih.roundToInt().coerceAtLeast(1)))
        }
        for (c in layers) {
            val text = c.text
            if (text != null) {
                val half = halves[c.id]
                val img = half?.let { TitleBitmaps.cached(it, w / 2, h / 2) } ?: titles[c.id] ?: continue
                // outside the clip: show the title fully in
                val tt = if (t < c.start - 5e-4 || t >= c.end - 5e-4) c.start + se.gendut.klipp.engine.TitleMotion.IN else t.coerceAtLeast(c.start)
                val item = OverlayItem(c.start, c.end, Overlay.Title(text), c)
                // pinned: at half size like the other titles here
                val pin = half?.let { hs -> se.gendut.klipp.engine.pinArtOf(OverlayItem(c.start, c.end, Overlay.Title(hs), c), tt, w / 2, h / 2) }?.let { it to (w / 2) }
                    ?: se.gendut.klipp.engine.pinArtOf(item, tt, w, h)?.let { it to w }
                if (pin != null) drawPinned(pin.first.first, pin.first.second, pin.second, placementOf(item, tt, w, h).alpha)
                else drawPlaced(img, placementOf(item, tt, w, h), w / 2, h / 2)
                continue
            }
            val shown = hits[c.id] ?: frames[c.id] ?: continue
            val raw = shown.second
            // stabilized clip: the same correction as playback, for the still's source time
            val stab = gs?.grades?.get(c.id)?.stab?.transform(shown.first, raw.width.toFloat(), raw.height.toFloat())
            // the graded image; if it belongs to an older still (the next one is being graded right now) it is shown
            // anyway – better one frame late than an ungraded flash
            val img = graded[c.id]?.second ?: raw
            val f = min(w.toFloat() / img.width, h.toFloat() / img.height)
            val iw = img.width * f * s
            val ih = img.height * f * s
            val k = (c.scale / 100.0).toFloat()
            translate(((c.x ?: (w / 2.0)) * s).toFloat(), ((c.y ?: (h / 2.0)) * s).toFloat()) {
                rotate(c.rotation.toFloat(), Offset.Zero) {
                    scale(k, k, Offset.Zero) {
                        if (stab != null) drawContext.canvas.nativeCanvas.concat(android.graphics.Matrix().apply {
                            // the translation in the still's pixels → drawn size
                            setValues(floatArrayOf(stab.a, stab.b, stab.tx / raw.width * iw, stab.c, stab.d, stab.ty / raw.height * ih, 0f, 0f, 1f))
                        })
                        drawImage(
                            img, dstOffset = IntOffset((-iw / 2).roundToInt(), (-ih / 2).roundToInt()),
                            dstSize = IntSize(iw.roundToInt().coerceAtLeast(1), ih.roundToInt().coerceAtLeast(1)),
                            alpha = (c.opacity / 100.0).toFloat().coerceIn(0f, 1f),
                        )
                    }
                }
            }
        }
        // captions on top, with the track's style (same drawing as playback and export)
        val caps = q.captions.filter { it.text.isNotBlank() && t >= it.start - 5e-4 && t < it.start + it.duration - 5e-4 }
        if (caps.isNotEmpty()) drawContext.canvas.nativeCanvas.let { cv ->
            cv.save(); cv.scale(s, s)
            for (cap in caps) se.gendut.klipp.engine.OverlayRenderer.drawCaption(cv, cap.text,
                q.captionTracks.firstOrNull { it.id == cap.track }?.style ?: se.gendut.klipp.engine.CaptionStyle(), w, h)
            cv.restore()
        }
    }
}

/**
 * The frame at the clip edge being trimmed (whole frame, fitted), fast keyframe first and then exact. With a neighbor
 * at the edit point: two frames side by side – the outgoing clip's last frame and the incoming clip's first.
 */
@Composable
private fun TrimFrame(controller: ProjectController, v: TrimView, modifier: Modifier) {
    val main = trimImage(controller, v)
    val pair = v.pair?.let { trimImage(controller, it) }
    Canvas(modifier.fillMaxSize().clipToBounds().testTag(if (v.pair != null) "trimframe2" else "trimframe")) {
        drawRect(Color.Black)
        fun fit(i: ImageBitmap?, x0: Float, w: Float) {
            i ?: return
            val f = min(w / i.width, size.height / i.height)
            val iw = (i.width * f).roundToInt().coerceAtLeast(1)
            val ih = (i.height * f).roundToInt().coerceAtLeast(1)
            drawImage(i, dstOffset = IntOffset((x0 + (w - iw) / 2).roundToInt(), ((size.height - ih) / 2).roundToInt()), dstSize = IntSize(iw, ih))
        }
        if (v.pair == null) { fit(main, 0f, size.width); return@Canvas }
        val half = size.width / 2f
        val gap = 2.dp.toPx()
        val (left, right) = if (v.pairLeft) pair to main else main to pair
        fit(left, 0f, half - gap)
        fit(right, half + gap, half - gap)
        // the edit point between the two frames
        drawRect(K.Primary, Offset(half - gap / 2, 0f), androidx.compose.ui.geometry.Size(gap, size.height))
    }
}

/** One trim frame: the cached image right away, then a fast keyframe and the exact frame. */
@Composable
private fun trimImage(controller: ProjectController, v: TrimView): ImageBitmap? {
    val ctx = LocalContext.current
    val uri = controller.uriOf(v.item)?.let { se.gendut.klipp.engine.Proxies.videoUri(it) } ?: return null
    var img by remember(v.item) { androidx.compose.runtime.mutableStateOf<ImageBitmap?>(null) }
    Frames.cached(uri, v.sec)?.let { if (img !== it) img = it }
    LaunchedEffect(uri, (v.sec * 25).roundToInt()) {
        Frames.load(ctx, uri, v.sec, exact = false)?.let { img = it }
        kotlinx.coroutines.delay(90)
        Frames.load(ctx, uri, v.sec, exact = true)?.let { img = it }
    }
    return img
}

private fun ClipSnap.withLive(p: ClipProps): ClipSnap = copy(
    x = p.x?.toDouble() ?: x, y = p.y?.toDouble() ?: y, scale = p.scale.toDouble(), rotation = p.rotation.toDouble(), opacity = p.opacity.toDouble(),
    text = text?.copy(
        text = p.title ?: text.text, size = p.textSize.toDouble(),
        color = listOf(((p.textColor shr 16) and 255) / 255f, ((p.textColor shr 8) and 255) / 255f, (p.textColor and 255) / 255f, text.color.getOrElse(3) { 1f }),
        name = p.titleLook.ifEmpty { text.name },
    ),
)
