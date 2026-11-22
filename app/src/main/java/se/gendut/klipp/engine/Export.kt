package se.gendut.klipp.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderUtil
import org.json.JSONObject
import se.gendut.klipp.R
import se.gendut.klipp.Str
import kotlin.math.roundToInt

/**
 * Export settings. 0 means "same as the project" where noted. Saved per project (and as the default for new ones).
 *
 * @param height short side of the frame in pixels (720, 1080, 2160 …); the width follows the project's aspect ratio
 * @param fps frame rate; only lower than the project's (Media3 drops frames, it doesn't invent new ones)
 * @param codec video MIME type (H.264, HEVC, AV1 – whatever the phone can encode)
 * @param bitrateMbps video bitrate; 0 = Auto (Standard for the format, see [videoMbps])
 * @param cbr constant bitrate (otherwise variable: better quality per MB)
 * @param keyframeSec seconds between keyframes; 0 = the encoder's choice (1 s)
 * @param highProfile H.264 High / HEVC Main – more efficient than Baseline/Main when the phone supports it
 * @param hdr keep HDR (HLG/PQ, 10-bit HEVC) – only when the sources are HDR and the phone can encode it
 * @param bitDepth bit depth of an SDR file: 0 = same as the sources (10 if any clip is 10-bit), 8 or 10 (HEVC/AV1 Main10)
 * @param hlg HDR made from the grade: every graded clip ends in OpenDRT, rendered as Rec.2100 HLG (10-bit HEVC) –
 *   see [ClipGrade.hlgReady]
 */
data class ExportSettings(
    val height: Int = 0,
    val fps: Double = 0.0,
    val codec: String = MimeTypes.VIDEO_H264,
    val bitrateMbps: Float = 0f,
    val cbr: Boolean = false,
    val keyframeSec: Float = 0f,
    val highProfile: Boolean = true,
    val hdr: Boolean = false,
    val audio: Boolean = true,
    val audioKbps: Int = 192,
    val sampleRate: Int = 0,
    val mono: Boolean = false,
    val bitDepth: Int = 0,
    val hlg: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("h", height).put("fps", fps).put("codec", codec).put("mbps", bitrateMbps.toDouble())
        .put("cbr", cbr).put("key", keyframeSec.toDouble()).put("high", highProfile).put("hdr", hdr)
        .put("audio", audio).put("akbps", audioKbps).put("sr", sampleRate).put("mono", mono).put("bits", bitDepth).put("hlg", hlg)

    /** Output frame size for a [w]×[h] project (even dimensions, as encoders require). */
    fun outputSize(w: Int, h: Int): Pair<Int, Int> {
        if (height <= 0 || w <= 0 || h <= 0) return even(w) to even(h)
        val k = height.toDouble() / minOf(w, h)
        return even((w * k).roundToInt()) to even((h * k).roundToInt())
    }

    /**
     * Bitrate given to the encoder (Mbit/s): the chosen one, or for Auto Klipp's Standard for the file's size,
     * frame rate and codec. Media3's own guess is based on the source file's frame rate – wrong for footage whose file
     * declares a different (or no) frame rate, and in 4K the picture then turned blurry and blocky.
     */
    fun videoMbps(w: Int, h: Int, projectFps: Double): Double {
        if (bitrateMbps > 0f) return bitrateMbps.toDouble()
        val (ow, oh) = outputSize(w, h)
        val f = outputFps(projectFps).takeIf { it > 0.0 } ?: 30.0
        return ExportPresets.mbps(ExportPresets.STANDARD, ow, oh, f, codec)
    }

    /** Output frame rate (never higher than the project's). */
    fun outputFps(projectFps: Double): Double = if (fps <= 0.0) projectFps else minOf(fps, projectFps)

    /** Estimated file size in MB for [seconds] seconds. */
    fun estimateMb(w: Int, h: Int, projectFps: Double, seconds: Double): Double {
        val v = videoMbps(w, h, projectFps)
        val a = if (audio) audioKbps / 1000.0 else 0.0
        return (v + a) * seconds / 8.0
    }

    companion object {
        private fun even(v: Int) = (v.coerceAtLeast(2) / 2) * 2

        fun fromJson(o: JSONObject?): ExportSettings {
            val d = ExportSettings()
            o ?: return d
            return ExportSettings(
                o.optInt("h", 0).coerceIn(0, 8640), o.optDouble("fps", 0.0).coerceIn(0.0, 240.0),
                o.optString("codec", d.codec).ifBlank { d.codec }, o.optDouble("mbps", 0.0).toFloat().coerceIn(0f, 400f),
                o.optBoolean("cbr", false), o.optDouble("key", 0.0).toFloat().coerceIn(0f, 10f), o.optBoolean("high", true),
                o.optBoolean("hdr", false), o.optBoolean("audio", true), o.optInt("akbps", 192).coerceIn(32, 512),
                o.optInt("sr", 0).coerceIn(0, 192_000), o.optBoolean("mono", false),
                o.optInt("bits", 0).let { if (it == 8 || it == 10) it else 0 },
                o.optBoolean("hlg", false),
            )
        }

        private fun prefs(c: Context) = c.getSharedPreferences("export", Context.MODE_PRIVATE)

        /** Settings for project [projectId] (otherwise the last used, otherwise the defaults). */
        fun load(c: Context, projectId: String?): ExportSettings {
            val p = prefs(c)
            val s = projectId?.let { p.getString("p:$it", null) } ?: p.getString("last", null)
            return runCatching { fromJson(s?.let { JSONObject(it) }) }.getOrDefault(ExportSettings())
        }

        fun save(c: Context, projectId: String?, s: ExportSettings) {
            val j = s.toJson().toString()
            prefs(c).edit().apply { projectId?.let { putString("p:$it", j) }; putString("last", j) }.apply()
        }
    }
}

/** Bitrate quality levels (Mbit/s), by resolution, frame rate and codec. */
object ExportPresets {
    const val LOW = 0
    const val STANDARD = 1
    const val HIGH = 2
    const val MAX = 3
    private val LABEL_IDS = intArrayOf(R.string.export_quality_low, R.string.export_quality_standard, R.string.export_quality_high, R.string.export_quality_max)
    val LABELS: List<String> get() = LABEL_IDS.map { Str.t(it) }
    private val FACTOR = floatArrayOf(0.45f, 0.75f, 1.2f, 2.4f)

    /**
     * H.264 1080p30 "Standard" ≈ 12 Mbit/s (in line with YouTube's upload recommendations), scaled by
     * pixels per second. HEVC and AV1 reach the same quality at roughly 60 and 50 % respectively.
     */
    fun mbps(level: Int, w: Int, h: Int, fps: Double, codec: String): Double {
        val px = w.toDouble() * h * fps / (1920.0 * 1080 * 30)
        val codecK = when (codec) { MimeTypes.VIDEO_H265 -> 0.6; MimeTypes.VIDEO_AV1 -> 0.5; else -> 1.0 }
        val v = 16.0 * Math.pow(px, 0.85) * FACTOR[level.coerceIn(0, 3)] * codecK
        return (Math.round(v * 2) / 2.0).coerceIn(0.5, 400.0)
    }
}

/** What the phone's encoders support (read once). */
@OptIn(UnstableApi::class)
object EncoderCaps {
    /** [label] is "name · hint" (callers split on " · "); resolved at use so it follows the app language. */
    data class Codec(val mime: String, @androidx.annotation.StringRes private val labelRes: Int) {
        val label: String get() = Str.t(labelRes)
    }
    private val KNOWN = listOf(
        Codec(MimeTypes.VIDEO_H264, R.string.export_codec_h264),
        Codec(MimeTypes.VIDEO_H265, R.string.export_codec_hevc),
        Codec(MimeTypes.VIDEO_AV1, R.string.export_codec_av1),
    )

    val codecs: List<Codec> by lazy {
        val supported = runCatching { EncoderUtil.getSupportedMimeTypes() }.getOrDefault(emptySet())
        KNOWN.filter { it.mime in supported }.ifEmpty { KNOWN.take(1) }
    }

    /** Can any encoder for [mime] handle [w]×[h]? */
    fun sizeSupported(mime: String, w: Int, h: Int): Boolean = runCatching {
        EncoderUtil.getSupportedEncoders(mime).any { EncoderUtil.isSizeSupported(it, mime, w, h) }
    }.getOrDefault(false)

    /** Can the phone encode HDR (HLG or PQ, 10-bit) with HEVC? */
    val hdrSupported: Boolean by lazy {
        runCatching {
            listOf(C.COLOR_TRANSFER_HLG, C.COLOR_TRANSFER_ST2084).any { tr ->
                val ci = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorRange(C.COLOR_RANGE_LIMITED).setColorTransfer(tr).build()
                EncoderUtil.getSupportedEncodersForHdrEditing(MimeTypes.VIDEO_H265, ci).isNotEmpty()
            }
        }.getOrDefault(false)
    }
}

/**
 * The timeline's frame rate: at most one frame per slot of [fps] frames a second, its timestamp moved to the slot (at most
 * half a frame from the source time) – the rule is [FrameGrid]. Gives the export a constant frame rate even from
 * variable-frame-rate clips; playback's main player gets it too, so it shows the frames the export has.
 *
 * Each item is a stream of its own: the export rebuilds its effect chain for every item (the effects differ) and so starts
 * over; in playback the chain may run on across items, so [startsUs] (the items' starts on the timeline, µs) start it
 * over there. A seek (flush) starts it over as well.
 *
 * Media3's own FrameDropEffect.createDefaultFrameDropEffect keeps the frame closest to the target spacing from the
 * previous one, so 30 → 24 or 25 → 24 drops almost nothing at all.
 */
@OptIn(UnstableApi::class)
class FrameRateEffect(
    val fps: Double, val startsUs: LongArray = LongArray(0),
    /** Per segment (see [Streams]): its source frame time on the timeline. */
    val nominalUs: LongArray = LongArray(0),
    /** Playback (see [Streams]). */
    val itemEnds: Boolean = true,
    /**
     * Where the composition's time 0 lies on the timeline (µs): a render of a part (a pre-rendered chunk) has its slots
     * on the timeline's grid, not counted from its own start – a chunk starting between two slots (a 25 fps timeline
     * changed to 60) got a grid of its own, offset, and a frame more than its slots.
     */
    val originUs: Long = 0L,
    /** See [Streams]. */
    val cutAtStart: Boolean = false,
    /** Playback: the paused player's frame for the playhead's slot (see PausedPick). */
    val pick: PausedPick? = null,
    /** See [Streams]. */
    val cutsUs: LongArray = LongArray(0),
) : androidx.media3.effect.GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): androidx.media3.effect.GlShaderProgram =
        Program(Streams(fps, startsUs, originUs, nominalUs, itemEnds && startsUs.isNotEmpty(), cutAtStart, cutsUs), pick)

    /**
     * Frames that aren't needed are handed back at once – as Media3's own frame dropping does: the input texture is
     * released to the stage before and the next frame is asked for (the frame never reaches the output, so this stage
     * holds nothing). The others pass through unchanged (only the timestamp changes).
     */
    private class Program(private val rule: Streams, private val pick: PausedPick?) : androidx.media3.effect.PassthroughShaderProgram() {
        override fun queueInputFrame(gl: androidx.media3.common.GlObjectsProvider, tex: androidx.media3.common.GlTextureInfo, presentationTimeUs: Long) {
            val ruled = rule.next(presentationTimeUs)
            // a paused seek: the rule runs over every frame, only the slot's frame is shown (at its own presented time);
            // a held frame goes back without the next one asked for (the seek's flush asks again)
            val out = if (pick == null || !pick.active) ruled else when (pick.onFrame(presentationTimeUs, ruled)) {
                PausedPick.SHOW -> ruled ?: presentationTimeUs
                PausedPick.HOLD -> { inputListener.onInputFrameProcessed(tex); return }
                else -> null
            }
            // (diagnostics: every frame's time into and out of the rule, the cut trace)
            if (CutTrace.on) CutTrace.log("rate", "in=$presentationTimeUs out=${out ?: "drop"}")
            if (out == null) {
                inputListener.onInputFrameProcessed(tex)
                inputListener.onReadyToAcceptInputFrame()
                return
            }
            super.queueInputFrame(gl, tex, out)
        }

        override fun signalEndOfCurrentInputStream() {
            if (CutTrace.on) CutTrace.log("rate", "end of the input stream")
            super.signalEndOfCurrentInputStream()
        }

        override fun flush() {
            rule.reset()
            pick?.onFlush()
            super.flush()
        }
    }

    /**
     * The rule over items (pure, for tests): a frame's new time (µs), or null when it is dropped. With item edges
     * ([startsUs], playback) an item's last frames are kept at or after their own time (FrameGrid.nearEnd); the export
     * passes none.
     */
    class Streams(
        fps: Double, private val startsUs: LongArray, private val originUs: Long = 0L,
        /** Each segment's source frame time on the timeline (µs, 0: not known): [0] before the first start, [i + 1] from startsUs[i]. */
        private val nominalUs: LongArray = LongArray(0),
        /** Playback: an item's last frames are kept for Media3's end of item (FrameGrid.nearEnd); the export doesn't need it. */
        private val itemEnds: Boolean = startsUs.isNotEmpty(),
        /** A pre-rolled part cut into a segment at the composition's start: that segment isn't pinned (SourceSampler). */
        private val cutAtStart: Boolean = false,
        /** Segment starts (composition µs) that are cuts, not a clip's own start – not pinned either (a live clip after a pre-rendered piece). */
        private val cutsUs: LongArray = LongArray(0),
    ) {
        private val grid = FrameGrid(fps)
        private val sampler = SourceSampler(fps)
        private var seg = Int.MIN_VALUE
        private var prevIn = Long.MIN_VALUE
        private var lastOut = Long.MIN_VALUE

        fun reset() { sampler.reset(); seg = Int.MIN_VALUE; prevIn = Long.MIN_VALUE; lastOut = Long.MIN_VALUE }

        fun next(tUs: Long): Long? {
            // the segment the frame belongs to (its first frame is read up to LEAD early); a new one starts a new stream
            var i = -1
            while (i + 1 < startsUs.size && tUs >= startsUs[i + 1] - FrameGrid.LEAD_US) i++
            if (i != seg) {
                val fresh = seg != Int.MIN_VALUE
                sampler.startSegment((if (i < 0) 0L else startsUs[i]) + originUs, nominalUs.getOrElse(i + 1) { 0L },
                    if (i + 1 < startsUs.size) startsUs[i + 1] + originUs else Long.MAX_VALUE, pinFirst = !(cutAtStart && i < 0) && !(i >= 0 && startsUs[i] in cutsUs))
                // (after a seek the stream picks up inside its segment: no frame before it – see SourceSampler)
                if (fresh) prevIn = Long.MIN_VALUE
                seg = i
            }
            val step = if (prevIn != Long.MIN_VALUE && tUs > prevIn) tUs - prevIn else 0L
            prevIn = tUs
            val end = if (i + 1 < startsUs.size) startsUs[i + 1] else Long.MAX_VALUE
            val out = sampler.next(tUs + originUs)?.let { it - originUs }
            val at = if (itemEnds && step > 0 && FrameGrid.nearEnd(tUs, end, step)) FrameGrid.lastFrameAt(tUs, out, end, lastOut, grid.stepUs, originUs) else out
            if (at != null) lastOut = at
            return at
        }
    }

    companion object {
        /** The times (µs) of [timesUs] that are kept – same rule as the effect, for tests. */
        fun keep(timesUs: List<Long>, fps: Double): List<Long> = FrameGrid.keep(timesUs, fps)
    }
}
