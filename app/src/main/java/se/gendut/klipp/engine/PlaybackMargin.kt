package se.gendut.klipp.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * The live preview's margin, measured while it plays: every frame the main player's chain shows is checked against the
 * time it was to be shown (the effect chain's frame hook, after the frame went to the screen). A frame drawn after that
 * time, shown "at once" or dropped as late, is a frame the phone did not keep up with.
 *
 * When playback stops, the pre-render chunks of the stretch played that had such frames are marked as too slow to play
 * live ([RenderCache.markSlow]): they are rendered ahead like transitions and shown red on the render bar until then.
 * The preview's quality never changes – the parts that don't keep up play from finished files instead.
 *
 * Pure apart from the hook; [lateIn] is tested.
 */
object PlaybackMargin {
    /** After play the first frames are left out (the start's own latency, not the material's). */
    private const val SETTLE_NS = 300_000_000L

    /** A chunk is too slow with this many late frames in one playback, or one dropped. */
    const val LATE_FRAMES = 2

    data class Frame(val ptsUs: Long, val dropped: Boolean)

    @Volatile private var active = false
    @Volatile private var since = 0L
    private val frames = ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
    private val late = ConcurrentHashMap<Int, java.util.concurrent.ConcurrentLinkedQueue<Frame>>()

    /** Called by the frame hook for every frame of every chain (see [FrameHook]). */
    fun onFrame(chain: Int, ptsUs: Long, presentNs: Long, dropped: Boolean) {
        if (!active) return
        val now = System.nanoTime()
        if (now < since) return
        frames.getOrPut(chain) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        if (dropped || presentNs < 0 || now > presentNs) late.getOrPut(chain) { java.util.concurrent.ConcurrentLinkedQueue() }.add(Frame(ptsUs, dropped))
    }

    fun begin() {
        frames.clear(); late.clear()
        since = System.nanoTime() + SETTLE_NS
        active = true
    }

    /** Playback stopped: the main chain's late frames (the chain that showed the most frames – the upper layers play less). */
    fun end(): List<Frame> {
        if (!active) return emptyList()
        active = false
        val main = frames.maxByOrNull { it.value.get() }?.key ?: return emptyList()
        return late[main]?.toList().orEmpty()
    }

    /**
     * The chunks ([a, b) in timeline seconds, with their keys) that had too many late frames: [late] in the composition's
     * time, which starts at [originUs] on the timeline.
     */
    fun lateIn(chunks: List<Triple<Double, Double, String>>, late: List<Frame>, originUs: Long): Set<String> =
        chunks.filter { (a, b, _) ->
            val inside = late.filter { f -> (f.ptsUs + originUs) / 1e6 in a..<b }
            inside.any { it.dropped } || inside.size >= LATE_FRAMES
        }.map { it.third }.toSet()
}

/**
 * The effect chain's one frame hook: the cut trace (when it runs) and the playback margin (always, cheap).
 */
object FrameHook {
    fun install() {
        androidx.media3.effect.KlippPrecision.frameTrace = androidx.media3.effect.KlippPrecision.FrameTrace { chain, ptsUs, presentNs, dropped ->
            if (CutTrace.on) CutTrace.log("gl", "chain=$chain pts=$ptsUs ${if (dropped) "DROPPED" else "present=$presentNs"} thread=${Thread.currentThread().name}")
            PlaybackMargin.onFrame(chain, ptsUs, presentNs, dropped)
        }
    }
}
