package se.gendut.klipp.engine

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram

/**
 * Live playback's cuts with one video renderer.
 *
 * With Media3's video prewarming (its default) the next item is decoded by a second renderer whose frames wait outside
 * the effects until the playback position reaches the item: the shared sink is stopped when the first renderer ends,
 * the second attaches only when it is started – then the chain is rebuilt and the first frame graded, so the picture
 * stood 85–160 ms at every unrendered cut on the vivo.
 *
 * Without prewarming a clipped item starts with a discontinuity (its frames from the keyframe before the in point must
 * be decoded but not shown): ExoPlayer then waits for every renderer to end before it re-enables them – and the main
 * sequence's audio renderer never ends inside the timeline, so the video stopped at the first cut.
 *
 * Here the items say they start at a keyframe (no discontinuity): the one video renderer reads on into the next item and
 * the chain gets its frames ahead of time, like any frame inside an item. The frames before the item's start then come
 * through the decoder as well (they aren't skipped as after a seek): [PrerollDrop], first in the item's effects, drops
 * them before any work is done on them.
 *
 * Measured on the vivo (4K HEVC clips with a film grade, the chain at about 24 frames a second): every slot drawn on time
 * at cuts between clips, into and out of pre-rendered pieces, between files of different codecs (the new decoder starts
 * ahead of the cut) and into speed-changed clips; with prewarming 74–240 ms at each, with or without the edit point layer.
 * The paused frame is the same pixel for pixel. Not covered: a clip after an empty stretch or a freeze frame – those are
 * played by the image renderer, the video renderer starts again after them; the edit point layer ([se.gendut.klipp.Handoff])
 * covers those cuts and only those.
 */
object LiveCuts {
    /**
     * On for the player being made: the default (diagnostics: the file diag/prewarm in the app's external files turns it
     * off – Media3's prewarming, for comparison). Compose state: the edit point layer follows it.
     */
    var oneRenderer by mutableStateOf(true)

    /**
     * An empty stretch before a clip, played as video: a black file of its own (256×144 H.264, 30 frames a second, a
     * keyframe every second, [GAP_LENGTH_S] long) clipped to the stretch, so the one video renderer reads on through it.
     * (Frames all through, as Media3's gap has: with a single frame at the start the paused pick inside a stretch found
     * no frame and waited for its time limit.) Media3's gap is played by the image renderer:
     * the video renderer was disabled at the clip's end and enabled again only just before the next clip – the black came
     * 130 ms late and the clip after it 140 ms (vivo). An HDR clip on either side keeps Media3's gap (the black file is
     * SDR), and so does a stretch at the timeline's end (nothing to read on into).
     */
    val GAP_URI: android.net.Uri = android.net.Uri.parse("asset:///gap/black.mp4")
    const val GAP_LENGTH_S = 600.0

    /** Whether the stretch of [sec] between a clip from [before] (null: the timeline's start) and one from [after] is played as video (original files). */
    fun gapAsVideo(sec: Double, before: android.net.Uri?, after: android.net.Uri?): Boolean =
        oneRenderer && after != null && sec > 0.0 && sec < GAP_LENGTH_S - 1.0 &&
            before?.let { Perf.cachedHdr(it) } != true && Perf.cachedHdr(after) != true
}

/** An item's effect: frames before the item's start ([startUs], composition time) are dropped (see [LiveCuts]). */
data class PrerollDrop(val startUs: Long) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(startUs)

    private class Program(private val startUs: Long) : PassthroughShaderProgram() {
        override fun queueInputFrame(gl: GlObjectsProvider, tex: GlTextureInfo, presentationTimeUs: Long) {
            if (presentationTimeUs < startUs) {
                if (CutTrace.on) CutTrace.log("preroll", "drop $presentationTimeUs start=$startUs")
                inputListener.onInputFrameProcessed(tex)
                inputListener.onReadyToAcceptInputFrame()
                return
            }
            super.queueInputFrame(gl, tex, presentationTimeUs)
        }
    }
}
