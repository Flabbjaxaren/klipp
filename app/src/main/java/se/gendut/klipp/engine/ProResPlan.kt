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

    /** Where one of the file's audio streams lies in the intermediate's track: its first channel and how many. */
    data class Slot(val first: Int, val count: Int)

    /**
     * How the camera's audio becomes the intermediate's one track (Media3 plays only one track of a file): every
     * channel is kept, side by side, and [slots] (one per audio stream of the source, in file order – the engine's
     * audio_stream – null = not kept) says where each stream is, so a clip of stream k plays its own channels
     * (AudioChannels).
     */
    sealed interface AudioPlan {
        val slots: List<Slot?>

        /** No usable audio. */
        data object None : AudioPlan { override val slots: List<Slot?> = emptyList() }

        /** One compressed track copied as it is (AAC) – nothing to keep beside it, nothing lost. */
        data class Copy(val stream: Int, override val slots: List<Slot?>) : AudioPlan

        /**
         * PCM tracks [streams] (FFmpeg's stream indexes) written as one 16-bit track of [channels] channels: stream 0's
         * channels first, then stream 1's … [matrix] is the identity FFmpeg's wrapper is given (a row per output
         * channel, a column per input channel).
         */
        data class Keep(val streams: List<Int>, val channels: Int, val rate: Int, override val slots: List<Slot?>) : AudioPlan {
            val matrix: FloatArray get() = FloatArray(channels * channels) { if (it % (channels + 1) == 0) 1f else 0f }
        }
    }

    /** Compressed audio copied when there is no PCM (what Media3 plays from a MOV). */
    private val COPYABLE = setOf("aac")

    /** At most this many tracks are kept (FFmpeg side: KP_MAX_AUDIO) … */
    private const val MAX_TRACKS = 16
    /** … and this many channels in all (KP_MAX_OUT). */
    private const val MAX_CHANNELS = 64

    /**
     * The audio plan for a camera file's audio tracks ([tracks]: every audio stream in file order): the PCM tracks at
     * the first one's sample rate, all their channels in order (four mono tracks → one four-channel track). Without
     * PCM, an AAC track is copied as it is.
     */
    fun audioPlan(tracks: List<AudioTrack>): AudioPlan {
        val pcm = tracks.filter { it.decodable && it.codec.startsWith("pcm_") && it.channels > 0 && it.rate > 0 }
        if (pcm.isEmpty()) {
            val c = tracks.firstOrNull { it.codec in COPYABLE && it.channels > 0 } ?: return AudioPlan.None
            return AudioPlan.Copy(c.index, tracks.map { if (it === c) Slot(0, it.channels) else null })
        }
        val rate = pcm.first().rate
        val use = ArrayList<AudioTrack>()
        var cols = 0
        for (t in pcm) {
            if (t.rate != rate || use.size >= MAX_TRACKS || cols + t.channels > MAX_CHANNELS) continue
            use += t
            cols += t.channels
        }
        val first = HashMap<AudioTrack, Int>()
        var at = 0
        for (t in use) { first[t] = at; at += t.channels }
        return AudioPlan.Keep(use.map { it.index }, cols, rate, tracks.map { t -> first[t]?.let { Slot(it, t.channels) } })
    }

    // ------------------------------------------------------------------ cache

    /**
     * The intermediate's cache key: the source's address and size (a file replaced at the same address is converted
     * anew) and [version] (a new version of the conversion makes the old files go).
     */
    fun key(uri: String, size: Long, version: Int): String =
        MessageDigest.getInstance("SHA-1").digest("$uri|$size|v$version".toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
