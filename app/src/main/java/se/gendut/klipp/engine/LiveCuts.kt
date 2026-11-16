package se.gendut.klipp.engine

import android.content.Context
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
 */
object LiveCuts {
    /** On for the player being made (diagnostics switch: the file diag/noprewarm in the app's external files). */
    @Volatile var oneRenderer = false
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
