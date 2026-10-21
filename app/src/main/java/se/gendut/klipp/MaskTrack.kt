package se.gendut.klipp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import se.gendut.klipp.engine.LayerMask
import se.gendut.klipp.engine.MaskKey
import se.gendut.klipp.engine.WindowTracker

/**
 * Tracking for the layer mask: the mask follows an object through the clip and the track becomes keyframes (position
 * and size, as few as needed – see [MaskKey.fromTrack]) that can then be edited by hand. Same tracker as the windows
 * ([WindowTracker]); while it runs, the mask follows along in the image and the playhead follows the frame being tracked.
 */
object MaskTrackService {
    var progress by mutableStateOf<Float?>(null)
        private set
    /** The clip being tracked right now. */
    var target by mutableStateOf<Long?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    /** The clip that [message] refers to. */
    var lastClip by mutableStateOf<Long?>(null)
        private set
    private val scope = kotlinx.coroutines.MainScope()
    private var job: kotlinx.coroutines.Job? = null

    /**
     * Track the mask in clip [c] from the frame at [playhead] (timeline seconds) forward and backward through the whole
     * clip. The mask as it appears at the playhead is the starting point. [follow] moves the playhead to the frame being tracked.
     */
    fun start(ctx: Context, gs: GradeState, c: ColorClip, playhead: Float, follow: ((Float) -> Unit)? = null) {
        lastClip = c.id
        val uri = c.uri ?: run { message = Str.t(R.string.masktrack_file_missing); return }
        if (c.hold != null) { message = Str.t(R.string.masktrack_freeze_frame); return }
        val mask0 = gs.shown(c.id)?.layer?.mask ?: return
        cancel()
        val lo = c.srcIn
        val hi = c.srcIn + (c.end - c.start) * kotlin.math.abs(c.speed)
        val ref = c.srcAt(playhead).coerceIn(lo, hi)
        val base = mask0.at(ref)
        val win = se.gendut.klipp.engine.Window(
            shape = if (base.shape == LayerMask.RECT) 2 else 1, cx = base.cx, cy = base.cy,
            rx = (base.w / 2).coerceIn(0.01f, 0.5f), ry = (base.h / 2).coerceIn(0.01f, 0.5f),
        )
        target = c.id
        lastClip = c.id
        progress = 0f
        message = null
        val samples = java.util.concurrent.ConcurrentLinkedQueue<WindowTracker.Sample>()
        val fwd = java.util.concurrent.atomic.AtomicReference<Double?>(null)
        val bwd = java.util.concurrent.atomic.AtomicReference<Double?>(null)
        // keyframes from a (partial) track, applied to the mask as it is now (shape and feather may have changed)
        fun withTrack(track: se.gendut.klipp.engine.WindowTrack): LayerMask? {
            val cur = gs.shown(c.id)?.layer?.mask ?: return null
            return cur.replaceKeys(lo, hi, MaskKey.fromTrack(track.withSmooth(SMOOTH), base, lo, hi))
        }
        job = scope.launch {
            // progress in the image: the mask follows the track so far, a few times per second
            val ticker = launch {
                var lastF: Double? = null
                var stall = 0
                while (true) {
                    kotlinx.coroutines.delay(200)
                    val sorted = samples.toList().sortedBy { it.t }
                    if (sorted.isEmpty()) continue
                    val part = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { WindowTracker.merge(sorted) }.getOrNull() }
                    part?.let { withTrack(it) }?.let { m -> gs.of(c.id).let { g -> gs.setLive(c.id, g.copy(layer = (g.layer ?: return@let).copy(mask = m))) } }
                    // forward first, then backward (forward counts as done once it has stalled a couple of times)
                    val f = fwd.get(); val b = bwd.get()
                    stall = if (f == lastF) stall + 1 else 0
                    lastF = f
                    (if (f != null && stall < 2) f else b ?: f)?.let { t -> follow?.invoke(c.timelineAt(t)) }
                }
            }
            try {
                val t0 = System.nanoTime()
                val track = WindowTracker.track(ctx, uri, ref, lo, hi, win, { p -> scope.launch { progress = p } },
                    onSample = { smp ->
                        samples += smp
                        if (smp.t >= ref) fwd.set(smp.t) else bwd.set(smp.t)
                    })
                ticker.cancel()
                val m = withTrack(track) ?: return@launch
                // one undo step for the whole track
                gs.of(c.id).let { g -> g.layer?.let { l -> gs.set(c.id, g.copy(layer = l.copy(mask = m))) } }
                scope.launch { gs.endLive() }
                follow?.invoke(playhead)
                val secs = (System.nanoTime() - t0) / 1e9
                val n = m.keys.count { it.t >= lo - 1e-6 && it.t <= hi + 1e-6 }
                message = Str.p(R.plurals.masktrack_done, n, track.end - track.t0, secs, n) +
                    (if (track.weak > 0) " · " + Str.p(R.plurals.masktrack_weak_frames, track.weak, track.weak) else "")
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = Str.t(R.string.masktrack_canceled)
                scope.launch { gs.endLive() }
                throw e
            } catch (e: Throwable) {
                message = Str.t(R.string.masktrack_failed, e.message ?: e.javaClass.simpleName)
                scope.launch { gs.endLive() }
            } finally {
                ticker.cancel()
                progress = null
                target = null
            }
        }
    }

    fun cancel() { job?.cancel(); job = null }

    /** Tracking smoothing (same as the windows' default). */
    private const val SMOOTH = 0.3f
}

/** Timeline time (s) for source time [src] in the clip (inverse of srcAt; clamped to the clip). */
fun ColorClip.timelineAt(src: Double): Float {
    if (hold != null) return start
    val rel = (src - srcIn) / kotlin.math.abs(speed).coerceAtLeast(1e-6)
    return (if (reverse) end - rel else start + rel).toFloat().coerceIn(start, end)
}
