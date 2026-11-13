package se.gendut.klipp.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * A layer player's frames on the timeline's grid ([LayerGrid]) – the same frames the export and the main player show.
 * Two stages, because which frame is kept and when it is shown are two things:
 * - [select] goes first in the layer's chain: it drops the frames the grid doesn't keep and leaves the timestamps alone,
 *   so the effects after it (grade, stabilization, masks, keyframes) read each frame's own source time from the layer's
 *   clock as before;
 * - [present] goes last: it moves each kept frame's timestamp to its slot, which is when the player shows it (the video
 *   sink releases frames by the chain's output time; the layer's frame listener sees that time as well).
 *
 * Timestamps at the chain's input (ExoPlayer, MediaCodecVideoRenderer → PlaybackVideoGraphWrapper): the buffer time less
 * the renderer stream's start position – the file's time less [LayerClock]'s base, rising across items. [timelineUs]
 * maps one onto the timeline (null: not known – the frame passes as it is), [segmentUs] gives the playing segment's start
 * (µs; null: none) and [speed] its speed (file time per timeline time).
 *
 * Starts over at a seek (flush) and with a new chain (new programs); a new segment begins a new stream in [LayerGrid].
 */
@OptIn(UnstableApi::class)
class LayerGridEffect(
    val fps: Double,
    private val timelineUs: (Long) -> Long?,
    private val segmentUs: () -> Long?,
    private val speed: () -> Double,
    /** Diagnostics: a line per frame while [traceOn]. */
    private val trace: ((String) -> Unit)? = null,
    private val traceOn: () -> Boolean = { false },
) {
    // input timestamp → shown timestamp, for the frames select kept (both stages run on the chain's GL thread)
    private val moves = object : LinkedHashMap<Long, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?) = size > 64
    }

    val select: androidx.media3.effect.GlEffect = object : androidx.media3.effect.GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): androidx.media3.effect.GlShaderProgram = Select()
    }

    val present: androidx.media3.effect.GlEffect = object : androidx.media3.effect.GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): androidx.media3.effect.GlShaderProgram = Present()
    }

    /**
     * Drops what the grid doesn't keep. A dropped frame is handed back at once, as Media3's frame dropping does: its
     * texture goes back to the stage before and the next frame is asked for (it never reaches the output, so nothing
     * waits on it).
     */
    private inner class Select : androidx.media3.effect.PassthroughShaderProgram() {
        private val rule = LayerGrid(fps)

        override fun queueInputFrame(gl: androidx.media3.common.GlObjectsProvider, tex: androidx.media3.common.GlTextureInfo, presentationTimeUs: Long) {
            val tl = timelineUs(presentationTimeUs)
            val seg = segmentUs()
            if (tl == null || seg == null) { super.queueInputFrame(gl, tex, presentationTimeUs); return }
            val at = rule.next(tl, seg)
            if (trace != null && traceOn()) trace.invoke("grid in=$presentationTimeUs tl=$tl seg=$seg out=${at ?: "drop"}")
            if (at == null) {
                inputListener.onInputFrameProcessed(tex)
                inputListener.onReadyToAcceptInputFrame()
                return
            }
            if (at != tl) synchronized(moves) { moves[presentationTimeUs] = presentationTimeUs + Math.round((at - tl) * speed()) }
            super.queueInputFrame(gl, tex, presentationTimeUs)
        }

        override fun flush() {
            rule.reset()
            synchronized(moves) { moves.clear() }
            super.flush()
        }
    }

    /** Shows each kept frame at its slot. */
    private inner class Present : androidx.media3.effect.PassthroughShaderProgram() {
        override fun queueInputFrame(gl: androidx.media3.common.GlObjectsProvider, tex: androidx.media3.common.GlTextureInfo, presentationTimeUs: Long) {
            val out = synchronized(moves) { moves.remove(presentationTimeUs) } ?: presentationTimeUs
            super.queueInputFrame(gl, tex, out)
        }
    }
}
