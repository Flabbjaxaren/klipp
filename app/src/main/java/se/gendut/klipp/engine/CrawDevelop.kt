package se.gendut.klipp.engine

import org.json.JSONObject
import android.net.Uri

data class CrawSettings(val log: OpenDrt.Input = OpenDrt.Input.DI, val ei: Int = 0) {
    companion object {
        val LOGS = listOf(OpenDrt.Input.DI, OpenDrt.Input.SLOG3, OpenDrt.Input.FLOG2, OpenDrt.Input.LOGC4)
        val EXPOSURE_INDICES = listOf(0, 100, 200, 400, 800, 1600, 3200, 6400)
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
    fun logLut(): FloatArray = FloatArray(33_280 + 120 * 64 + 1) { i ->
        // Dense samples around black and midtones, wider spacing in the highlights.
        val linear = if (i <= 33_280) i / 4096f - .0625f else 8f + (i - 33_280) / 64f
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
    fun toJson() = JSONObject().put("uri", original.toString()).put("log", settings.log.key).put("ei", settings.ei)

    companion object {
        fun fromJson(value: JSONObject): CrawSource? {
            val uri = value.optString("uri").takeIf { it.isNotEmpty() } ?: return null
            val log = OpenDrt.Input.of(value.optString("log"))
            val ei = value.optInt("ei", 0)
            return CrawSource(Uri.parse(uri), CrawSettings(log.takeIf { it in CrawSettings.LOGS } ?: OpenDrt.Input.DI, ei))
        }
    }
}
