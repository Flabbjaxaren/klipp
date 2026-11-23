package se.gendut.klipp.engine

import org.json.JSONObject
import android.net.Uri

/** [high]: the encoder's top bit rate (on the OnePlus 160 instead of 100 Mbit/s for 4K) – cleaner gradients, bigger files. */
data class CrawSettings(val log: OpenDrt.Input = OpenDrt.Input.DI, val ei: Int = 0, val high: Boolean = false) {
    companion object {
        val LOGS = listOf(OpenDrt.Input.DI, OpenDrt.Input.SLOG3, OpenDrt.Input.FLOG2, OpenDrt.Input.LOGC4)
        val EXPOSURE_INDICES = listOf(0, 100, 200, 400, 800, 1600, 3200, 6400)

        /** Standard: 0.35 bit a pixel, at most 100 Mbit/s. High: 1.6 times that, up to the encoder's own top (240 at most). */
        fun bitRate(width: Int, height: Int, fps: Double, high: Boolean, encoderTop: Int): Int {
            val base = width.toDouble() * height * fps * .35
            val top = if (high) minOf(encoderTop.toDouble(), 240_000_000.0) else 100_000_000.0
            return (if (high) base * 1.6 else base).coerceIn(1_000_000.0, maxOf(1_000_000.0, top)).toInt()
        }
    }

    fun xyzToGamut(): FloatArray {
        val (a, b, c) = log.gamut
        val d = log.gamut[3]
        val e = log.gamut[4]
        val f = log.gamut[5]
        val g = log.gamut[6]
        val h = log.gamut[7]
        val i = log.gamut[8]
        val inverse = floatArrayOf(
            e * i - f * h, c * h - b * i, b * f - c * e,
            f * g - d * i, a * i - c * g, c * d - a * f,
            d * h - e * g, b * g - a * h, a * e - b * d,
        )
        val determinant = a * inverse[0] + b * inverse[3] + c * inverse[6]
        for (index in inverse.indices) inverse[index] /= determinant
        return inverse
    }

    // Invert the input transfer used by OpenDRT so both ends of the workflow share the same curves.
    fun logLut(): FloatArray = FloatArray(33_024 + 120 * 64 + 1) { i ->
        // Dense samples around black and midtones, wider spacing in the highlights. The split is linear 8.0 (index 33 024),
        // the same as craw_jni.cpp – it used to be 8.0625 here and 8.0 there
        val linear = if (i <= 33_024) i / 4096f - .0625f else 8f + (i - 33_024) / 64f
        var low = 0f
        var high = 1f
        repeat(18) {
            val middle = (low + high) / 2f
            if (OpenDrt.oetf(middle, log.oetf) < linear) low = middle else high = middle
        }
        (low + high) / 2f
    }
}

internal data class CrawSource(val original: Uri, val settings: CrawSettings) {
    fun toJson() = JSONObject().put("uri", original.toString()).put("log", settings.log.key).put("ei", settings.ei).put("high", settings.high)

    companion object {
        fun fromJson(value: JSONObject): CrawSource? {
            val uri = value.optString("uri").takeIf { it.isNotEmpty() } ?: return null
            val log = OpenDrt.Input.of(value.optString("log"))
            val ei = value.optInt("ei", 0)
            return CrawSource(Uri.parse(uri), CrawSettings(log.takeIf { it in CrawSettings.LOGS } ?: OpenDrt.Input.DI, ei, value.optBoolean("high")))
        }
    }
}
