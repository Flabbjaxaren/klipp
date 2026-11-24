package se.gendut.klipp.engine

import kotlin.math.abs

/**
 * A new project's frame rate from a clip's measured rate: the standard rate it is meant to be. Phones record a variable
 * frame rate whose average can lie well off the rate set in the camera (a 60 fps clip measured 58.6, and a 58.598 fps
 * project put every frame on a slightly slow grid and dropped some on export). The NTSC rates only when the clip is
 * really on one; otherwise the nearest whole standard rate within 4 %, high frame rates included. Anything else stays
 * as measured.
 */
internal fun standardFps(f: Double): Double {
    NTSC_RATES.firstOrNull { abs(it - f) < 0.03 }?.let { return it }
    return STANDARD_RATES.minBy { abs(it - f) }.takeIf { abs(it - f) <= it * 0.04 } ?: f
}

private val NTSC_RATES = listOf(23.976, 29.97, 59.94, 119.88)
private val STANDARD_RATES = listOf(24.0, 25.0, 30.0, 48.0, 50.0, 60.0, 90.0, 100.0, 120.0, 240.0)
