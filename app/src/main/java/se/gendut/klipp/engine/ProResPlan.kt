package se.gendut.klipp.engine

import android.media.MediaFormat
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * The decisions of the ProRes import ([ProRes]) as plain functions: is a file ProRes, which size and bitrate the HEVC
 * intermediate gets, how the camera's audio tracks become one, which colour tags it carries, and the file's cache key.
 */
object ProResPlan {
    /** ProRes 422 Proxy, LT, 422, HQ and 4444, 4444 XQ (ProRes RAW – aprn/aprh – is another codec, not decodable). */
    val TAGS = setOf("apco", "apcs", "apcn", "apch", "ap4h", "ap4x")

    fun isProRes(fourcc: String?): Boolean = fourcc != null && fourcc.lowercase() in TAGS

    /** From a mime type (some extractors name it, e.g. "video/prores"). */
    fun isProResMime(mime: String?): Boolean = mime?.lowercase()?.contains("prores") == true

    // ------------------------------------------------------------------ picture

    /**
     * The intermediate's size: the source's own (made even) when the encoder takes it; otherwise the largest size it
     * takes at the source's aspect, stepping down in 16-pixel widths (a DCI 4096 source on an encoder that stops at
     * 3840). null = nothing down to 640 wide is supported.
     */
    data class Size(val width: Int, val height: Int) {
        fun scaledFrom(w: Int, h: Int) = width != (w and 1.inv()) || height != (h and 1.inv())
    }

    fun outputSize(w: Int, h: Int, supported: (Int, Int) -> Boolean): Size? {
        if (w <= 0 || h <= 0) return null
        val ew = w and 1.inv()
        val eh = h and 1.inv()
        if (supported(ew, eh)) return Size(ew, eh)
        var cw = (ew / 16) * 16
        if (cw == ew) cw -= 16
        while (cw >= 640) {
            val ch = (Math.round(cw.toDouble() * h / w).toInt() and 1.inv()).coerceAtLeast(2)
            if (supported(cw, ch)) return Size(cw, ch)
            cw -= 16
        }
        return null
    }

    /**
     * The intermediate's bitrate (bit/s): ~0.87 bits per pixel per frame – 180 Mbit/s for UHD 25p, 192 for DCI 4K 25p,
     * 45 for 1080p25 – so the intermediate is close to the ProRes it replaces after one generation. Frame rates above 30
     * add only √(fps/30) (the encoder gets more frames, each needs less). Within [floor, max].
     */
    fun bitrate(w: Int, h: Int, fps: Double, max: Int? = null): Int {
        val f = if (fps.isFinite() && fps > 0) fps else 30.0
        val bits = BITS_PER_PIXEL * w.toDouble() * h * minOf(f, 30.0) * sqrt(maxOf(f, 30.0) / 30.0)
        val top = (max ?: MAX_BITRATE).coerceAtMost(MAX_BITRATE).toDouble()
        return bits.coerceIn(MIN_BITRATE.toDouble(), maxOf(top, MIN_BITRATE.toDouble())).toInt()
    }

    private const val BITS_PER_PIXEL = 0.87
    private const val MIN_BITRATE = 10_000_000
    private const val MAX_BITRATE = 400_000_000

    /** A frame rate as frames per second from FFmpeg's fraction (25/1, 24000/1001); 0 = unknown. */
    fun fps(num: Int, den: Int): Double = if (num > 0 && den > 0) num.toDouble() / den else 0.0

    /**
     * The colour of the intermediate: what the encoder writes in its VUI (MediaFormat's KEY_COLOR_*) and what the MOV's
     * colr box says (FFmpeg's numbers, ISO/IEC 23091-2) – the same, from the source's tags. Unspecified tags are BT.709
     * limited range (ProRes' default; the S5II's V-Log ProRes says BT.709 too – the log curve is a grading choice,
     * see [CameraLog]). HLG and PQ stay HDR.
     */
    data class Colors(val standard: Int, val transfer: Int, val range: Int, val avPrimaries: Int, val avTransfer: Int, val avMatrix: Int, val avRange: Int) {
        val hdr get() = transfer == MediaFormat.COLOR_TRANSFER_HLG || transfer == MediaFormat.COLOR_TRANSFER_ST2084
    }

    fun colors(primaries: Int, transfer: Int, matrix: Int, range: Int): Colors {
        // FFmpeg (ISO/IEC 23091-2): primaries 1 BT.709, 5 BT.470BG, 6 SMPTE 170M, 9 BT.2020; transfer 16 PQ, 18 HLG;
        // matrix 1 BT.709, 5/6 BT.601, 9/10 BT.2020; range 1 limited, 2 full
        val bt2020 = primaries == 9 || (primaries == 2 && (matrix == 9 || matrix == 10))
        val standard = when {
            bt2020 -> MediaFormat.COLOR_STANDARD_BT2020
            primaries == 5 || (primaries == 2 && matrix == 5) -> MediaFormat.COLOR_STANDARD_BT601_PAL
            primaries == 6 || (primaries == 2 && matrix == 6) -> MediaFormat.COLOR_STANDARD_BT601_NTSC
            else -> MediaFormat.COLOR_STANDARD_BT709
        }
        val tf = when (transfer) {
            18 -> MediaFormat.COLOR_TRANSFER_HLG
            16 -> MediaFormat.COLOR_TRANSFER_ST2084
            else -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        }
        val full = range == 2
        val (avP, avM) = when (standard) {
            MediaFormat.COLOR_STANDARD_BT2020 -> 9 to 9
            MediaFormat.COLOR_STANDARD_BT601_PAL -> 5 to 5
            MediaFormat.COLOR_STANDARD_BT601_NTSC -> 6 to 6
            else -> 1 to 1
        }
        val avT = when (tf) {
            MediaFormat.COLOR_TRANSFER_HLG -> 18
            MediaFormat.COLOR_TRANSFER_ST2084 -> 16
            // SDR: the BT.709 curve (BT.601 and BT.2020's SDR curves are the same function)
            else -> if (transfer == 6 || transfer == 14 || transfer == 15) transfer else 1
        }
        return Colors(standard, tf, if (full) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED, avP, avT, avM, if (full) 2 else 1)
    }

    // ------------------------------------------------------------------ audio

    /** An audio track as FFmpeg describes it. */
    data class AudioTrack(val index: Int, val codec: String, val channels: Int, val rate: Int, val decodable: Boolean)

    /** How the camera's audio becomes the intermediate's one track (Media3 plays only one). */
    sealed interface AudioPlan {
        /** No usable audio. */
        data object None : AudioPlan
        /** One compressed track copied as it is (AAC) – nothing to mix, nothing lost. */
        data class Copy(val stream: Int) : AudioPlan
        /**
         * PCM tracks mixed into [outChannels] channels of 16-bit PCM: [matrix] has a row per output channel and a column
         * per input channel (all of [streams]' channels in order).
         */
        data class Mix(val streams: List<Int>, val outChannels: Int, val matrix: FloatArray, val rate: Int) : AudioPlan {
            fun gain(out: Int, col: Int) = matrix[out * (matrix.size / outChannels) + col]
            override fun equals(other: Any?) = other is Mix && streams == other.streams && outChannels == other.outChannels && matrix.contentEquals(other.matrix) && rate == other.rate
            override fun hashCode() = (streams.hashCode() * 31 + outChannels) * 31 + matrix.contentHashCode()
        }
    }

    /** Compressed audio copied when there is no PCM to mix (what Media3 plays from a MOV). */
    private val COPYABLE = setOf("aac")

    /** At most this many tracks are mixed (FFmpeg side: KP_MAX_AUDIO). */
    private const val MAX_TRACKS = 16

    /**
     * The audio plan for a camera file's tracks:
     *  - one mono channel in all → mono;
     *  - two channels in all (two mono tracks, or one stereo track) → stereo, the first left, the second right;
     *  - more → stereo: a stereo track keeps its sides, every mono track (and each channel of a track with more than two)
     *    is centered; each source at 1/√n so n tracks that carry the same sound don't clip.
     * Only PCM tracks at the first one's sample rate are mixed; without PCM, an AAC track is copied as it is.
     */
    fun audioPlan(tracks: List<AudioTrack>): AudioPlan {
        val pcm = tracks.filter { it.decodable && it.codec.startsWith("pcm_") && it.channels > 0 && it.rate > 0 }
        if (pcm.isEmpty()) return tracks.firstOrNull { it.codec in COPYABLE && it.channels > 0 }?.let { AudioPlan.Copy(it.index) } ?: AudioPlan.None
        val rate = pcm.first().rate
        val use = pcm.filter { it.rate == rate }.take(MAX_TRACKS)
        val cols = use.sumOf { it.channels }
        if (cols == 1) return AudioPlan.Mix(use.map { it.index }, 1, floatArrayOf(1f), rate)
        val m = FloatArray(2 * cols)
        if (cols == 2) {
            m[0 * cols + 0] = 1f
            m[1 * cols + 1] = 1f
            return AudioPlan.Mix(use.map { it.index }, 2, m, rate)
        }
        // sources: a stereo track is one (its two sides), every other channel one
        val sources = use.sumOf { if (it.channels == 2) 1 else it.channels }
        val g = (1.0 / sqrt(sources.toDouble())).toFloat()
        var col = 0
        for (t in use) {
            if (t.channels == 2) {
                m[0 * cols + col] = g
                m[1 * cols + col + 1] = g
            } else for (c in 0 until t.channels) {
                m[0 * cols + col + c] = g
                m[1 * cols + col + c] = g
            }
            col += t.channels
        }
        return AudioPlan.Mix(use.map { it.index }, 2, m, rate)
    }

    // ------------------------------------------------------------------ cache

    /**
     * The intermediate's cache key: the source's address and size (a file replaced at the same address is converted
     * anew) and [version] (a new version of the conversion makes the old files go).
     */
    fun key(uri: String, size: Long, version: Int): String =
        MessageDigest.getInstance("SHA-1").digest("$uri|$size|v$version".toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
