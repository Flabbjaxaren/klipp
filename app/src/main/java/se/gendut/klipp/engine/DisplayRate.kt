package se.gendut.klipp.engine

import android.app.Activity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Matches the display refresh rate to the video frame rate during playback: 24p on a 60 Hz display otherwise shows
 * unevenly (3:2 – every other frame stays longer); with a rate that is an exact multiple (24p → 120 Hz, 30p → 60/120 Hz)
 * every frame shows equally long. Only modes with the same resolution (the switch happens without the screen blinking
 * on the Snapdragon phones). If there is no exact multiple nothing changes. When paused the system chooses again.
 */
object DisplayRate {
    /** The highest of [rates] (Hz) that is an exact multiple of [fps], or null. */
    fun pick(rates: List<Float>, fps: Double): Float? =
        if (fps < 1.0) null else rates.filter { r -> val k = r / fps; k >= 0.99 && abs(k - k.roundToInt()) < 0.01 }.maxOrNull()

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
