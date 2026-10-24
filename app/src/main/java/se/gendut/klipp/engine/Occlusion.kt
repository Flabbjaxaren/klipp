package se.gendut.klipp.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Preview playback only – work that never reaches the screen is skipped (export is not affected; its effects have no
 * clip key).
 *
 * - [hide]: a clip completely covered by an opaque upper layer right now. Its film (the heavy pass) passes the image
 *   through instead. The covering layer refreshes the mark every frame and stops [LEAD_S] before it uncovers the clip,
 *   so the clip is graded again before it is seen; a mark that isn't refreshed lapses after [HOLD_MS].
 * - [mainFrames]: frames of each clip that went through the main player's chain – the edit point layer (see Handoff)
 *   stands down once the main player has the new clip going instead of computing the same picture for a whole second.
 */
object Occlusion {
    /** The covering layer stops marking this long before the clip below shows again. */
    const val LEAD_S = 0.5
    /** A mark lasts this long unless refreshed. */
    const val HOLD_MS = 150L

    private val hiddenUntil = ConcurrentHashMap<Long, Long>()
    private val frames = ConcurrentHashMap<Long, Long>()

    fun hide(clip: Long, nowMs: Long = android.os.SystemClock.uptimeMillis()) { hiddenUntil[clip] = nowMs + HOLD_MS }
    fun hidden(clip: Long, nowMs: Long = android.os.SystemClock.uptimeMillis()): Boolean = (hiddenUntil[clip] ?: 0L) > nowMs

    /** Film frames passed through because their clip was hidden (diagnostics). */
    @Volatile var skipped = 0L
    fun clear() { hiddenUntil.clear(); mainHiddenUntil.clear() }

    private val mainHiddenUntil = ConcurrentHashMap<Long, Long>()
    private val filmedFrames = ConcurrentHashMap<Long, Long>()

    /**
     * The edit point layer covers the main player's picture of [clip] completely (same clip, opaque): only the main
     * player's chain passes it through – the layer's own chain has the same clip. Lapses like [hide].
     */
    fun hideMain(clip: Long, nowMs: Long = android.os.SystemClock.uptimeMillis()) { mainHiddenUntil[clip] = nowMs + HOLD_MS }
    fun showMain(clip: Long) { mainHiddenUntil.remove(clip) }
    fun hiddenMain(clip: Long, nowMs: Long = android.os.SystemClock.uptimeMillis()): Boolean = (mainHiddenUntil[clip] ?: 0L) > nowMs

    /** The main player's chain ran the film on a frame of [clip] (it is graded again, see [hideMain]). */
    fun filmed(clip: Long) { filmedFrames.merge(clip, 1L, Long::plus) }
    fun filmedFrames(clip: Long): Long = filmedFrames[clip] ?: 0L

    /** The main player's chain processed a frame of [clip]. */
    fun sawMain(clip: Long) { frames.merge(clip, 1L, Long::plus) }
    fun mainFrames(clip: Long): Long = frames[clip] ?: 0L

    /** A box in sequence pixels: left, top, right, bottom. */
    data class Box(val l: Double, val t: Double, val r: Double, val b: Double) {
        fun contains(o: Box, tol: Double = 0.5) = l <= o.l + tol && t <= o.t + tol && r >= o.r - tol && b >= o.b - tol
    }

    /**
     * Where a video ([videoW]×[videoH], square pixels) lands in the [frameW]×[frameH] frame: fitted, then scaled by
     * [scale] (1 = 100 %), moved by ([dx], [dy]) from the center and turned [rotation]° – the bounding box.
     */
    fun box(frameW: Double, frameH: Double, videoW: Double, videoH: Double, scale: Double, dx: Double, dy: Double, rotation: Double): Box {
        val f = minOf(frameW / videoW, frameH / videoH)
        val w = videoW * f * scale; val h = videoH * f * scale
        val a = Math.toRadians(rotation); val c = abs(kotlin.math.cos(a)); val sn = abs(kotlin.math.sin(a))
        val bw = w * c + h * sn; val bh = w * sn + h * c
        val cx = frameW / 2 + dx; val cy = frameH / 2 + dy
        return Box(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
    }

    /**
     * Is a layer an opaque rectangle (no transparency, blend mode, key/mask, transition geometry or rotation)? Only
     * then does it hide what is under its box.
     */
    fun opaque(rotation: Double, alpha: Float, plainBlend: Boolean, noAlphaFx: Boolean, geomIdentity: Boolean) =
        alpha >= 0.999f && plainBlend && noAlphaFx && geomIdentity && abs(rotation) <= 0.01

    /** Does a layer cover the whole frame, opaque (see [box], [opaque])? */
    fun covers(
        frameW: Double, frameH: Double, videoW: Double, videoH: Double,
        scale: Double, dx: Double, dy: Double, rotation: Double, alpha: Float,
        plainBlend: Boolean, noAlphaFx: Boolean, geomIdentity: Boolean,
    ): Boolean {
        if (!opaque(rotation, alpha, plainBlend, noAlphaFx, geomIdentity)) return false
        if (videoW <= 0 || videoH <= 0 || frameW <= 0 || frameH <= 0) return false
        return box(frameW, frameH, videoW, videoH, scale, dx, dy, rotation).contains(Box(0.0, 0.0, frameW, frameH))
    }

    /** The display size (rotation applied) of each video file, read once in the background; null until known. */
    object Sizes {
        private val known = ConcurrentHashMap<String, Pair<Int, Int>>()
        private val asked = ConcurrentHashMap<String, Boolean>()
        fun of(context: android.content.Context, uri: android.net.Uri): Pair<Int, Int>? {
            val k = uri.toString()
            known[k]?.let { return it }
            if (asked.putIfAbsent(k, true) == null) Thread({
                runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(context, uri)
                        val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                        val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                        val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (w != null && h != null && w > 0 && h > 0) known[k] = if (rot % 180 != 0) h to w else w to h
                    } finally { r.release() }
                }
            }, "videosize").apply { isDaemon = true }.start()
            return null
        }
    }
}
