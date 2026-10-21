package se.gendut.klipp.engine

import android.opengl.GLES20
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostics: how long Klipp's own stages in the video chain take on the GPU (nodes, film, LUT, look) and how large
 * the image they compute on is. Only measured while diagnostics are open ([enabled]): glFinish before and after the
 * stage waits for the GPU, which itself makes the chain a bit slower.
 */
object PassTimes {
    @Volatile var enabled = false

    private class Stat(@Volatile var ms: Double = 0.0, @Volatile var calls: Long = 0)
    private val stats = ConcurrentHashMap<String, Stat>()
    /** The latest image size into Klipp's stages (e.g. "1920×1080"). */
    @Volatile var size: String? = null

    inline fun measure(kind: String, block: () -> Unit) {
        if (!enabled) { block(); return }
        GLES20.glFinish()
        val t0 = System.nanoTime()
        try {
            block()
        } finally {
            GLES20.glFinish()
            record(kind, (System.nanoTime() - t0) / 1e6)
        }
    }

    fun record(kind: String, ms: Double) {
        val s = stats.getOrPut(kind) { Stat(ms) }
        s.ms = s.ms * 0.8 + ms * 0.2
        s.calls++
    }

    fun noteSize(w: Int, h: Int) { if (w > 0 && h > 0) size = "${w}×$h" }

    private var lastAt = 0L
    private var lastShown = 0L
    private var lastLate = 0L
    private val lastCalls = HashMap<String, Long>()
    private var lastText = ""

    /**
     * Lines for diagnostics: frames per second out of the chain, dropped as late, and each stage's time and count per
     * frame. Recomputed at most every other second (same text in between).
     */
    @Synchronized fun describe(): String {
        val now = System.nanoTime()
        val shown = androidx.media3.effect.KlippPrecision.framesShown
        val late = androidx.media3.effect.KlippPrecision.framesLate
        if (lastAt != 0L && now - lastAt < 1_000_000_000L) return lastText
        val sec = (now - lastAt) / 1e9
        val sb = StringBuilder()
        if (lastAt != 0L) {
            val ds = shown - lastShown; val dl = late - lastLate
            sb.append("frames out: %.1f/s".format(ds / sec)).append(if (dl > 0) ", %.1f/s too late (dropped)".format(dl / sec) else "").append('\n')
            val parts = stats.entries.sortedBy { it.key }.mapNotNull { (k, s) ->
                val dc = s.calls - (lastCalls[k] ?: 0L)
                if (dc <= 0) null else "$k %.1f ms".format(s.ms) + (if (ds > 0 && dc > ds * 1.2) " ×%.0f".format(dc.toDouble() / ds) else "")
            }
            if (!enabled) sb.append("stage times: measured while diagnostics are open\n")
            else if (parts.isNotEmpty()) sb.append("GPU stages: ").append(parts.joinToString(", ")).append(size?.let { " (image $it)" } ?: "").append('\n')
        }
        lastAt = now; lastShown = shown; lastLate = late
        for ((k, s) in stats) lastCalls[k] = s.calls
        lastText = sb.toString()
        return lastText
    }
}
