package se.gendut.klipp.engine

import android.app.Activity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Matches the display refresh rate to the video frame rate during playback: 24p on a 60 Hz display otherwise shows
 * unevenly (3:2 – every other frame stays longer); with a rate that is an exact multiple (24p → 120 Hz, 30p → 60/120 Hz)
 * every frame shows equally long. Without an exact multiple (25p on 60/90/120/144 Hz) the rate whose frames come out
 * most evenly is chosen ([unevenness]): left to the system, 25p played at 60 Hz, its frames 33 and 50 ms long in turn;
 * at 144 Hz they are 42 and 35 ms. Only modes with the same resolution (the switch happens without the screen blinking
 * on the Snapdragon phones). When paused the system chooses again.
 */
object DisplayRate {
    /**
     * The one of [rates] (Hz) to show [fps] at: the highest exact multiple; without one, the rate with the most even
     * frames (the higher of two alike); null when there is nothing to choose from.
     */
    fun pick(rates: List<Float>, fps: Double): Float? {
        if (fps < 1.0 || rates.isEmpty()) return null
        rates.filter { r -> val k = r / fps; k >= 0.99 && abs(k - k.roundToInt()) < 0.01 }.maxOrNull()?.let { return it }
        return rates.sortedDescending().minByOrNull { unevenness(it, fps) }
    }

    /**
     * How unevenly [fps] shows at refresh rate [rate]: a frame stays a whole number of refreshes, the one below or the one
     * above its own length – the root mean square of those two errors (s), each by how often it occurs. A rate below the
     * frame rate can't show every frame: counted as one whole frame off.
     */
    fun unevenness(rate: Float, fps: Double): Double {
        val p = 1.0 / fps; val t = 1.0 / rate
        val k = p / t
        if (k < 0.99) return p
        val lo = kotlin.math.floor(k + 1e-9); val up = lo + 1
        val f = (k - lo).coerceIn(0.0, 1.0)   // the share of frames that stay the longer time
        val dLo = p - lo * t; val dUp = up * t - p
        return kotlin.math.sqrt(f * dUp * dUp + (1 - f) * dLo * dLo)
    }

    /** The chosen mode, or 0 = the system's. */
    fun modeFor(activity: Activity, fps: Double): Int {
        @Suppress("DEPRECATION")
        val display = (if (android.os.Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay) ?: return 0
        val cur = display.mode
        val same = display.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        val r = pick(same.map { it.refreshRate }, fps) ?: return 0
        return same.first { it.refreshRate == r }.modeId
    }

    /** Playback at [fps] frames/s, or (null) none. */
    fun apply(activity: Activity, fps: Double?) {
        val want = fps?.let { modeFor(activity, it) } ?: 0
        val w = activity.window ?: return
        val attrs = w.attributes
        if (attrs.preferredDisplayModeId == want) return
        attrs.preferredDisplayModeId = want
        w.attributes = attrs
    }
}
