package se.gendut.klipp.engine

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderUtil

/** What the phone says about something it may encode: yes, no, or it can't tell (never taken as a no). */
enum class Support { YES, NO, UNKNOWN }

/** The phone's encoders as the export plan sees them (a fake one in tests). */
interface EncoderInfo {
    /** The video codecs to offer (MIME types), in order of preference. */
    val codecs: List<String>
    fun size(mime: String, w: Int, h: Int): Support
    fun rate(mime: String, w: Int, h: Int, fps: Double): Support
    /** Main10 (10-bit SDR) with [mime]. */
    fun tenBit(mime: String): Support
    /** HDR (HLG/PQ, 10-bit HEVC). */
    val hdr: Support
}

/** The real encoders. A phone that reports no encoder at all for a codec can't tell – every phone has some. */
@OptIn(UnstableApi::class)
object PhoneEncoders : EncoderInfo {
    override val codecs: List<String> get() = EncoderCaps.codecs.map { it.mime }

    private fun encoders(mime: String) = runCatching { EncoderUtil.getSupportedEncoders(mime) }.getOrNull()

    override fun size(mime: String, w: Int, h: Int): Support {
        val e = encoders(mime)
        if (e.isNullOrEmpty()) return Support.UNKNOWN
        return runCatching { if (e.any { EncoderUtil.isSizeSupported(it, mime, w, h) }) Support.YES else Support.NO }.getOrDefault(Support.UNKNOWN)
    }

    override fun rate(mime: String, w: Int, h: Int, fps: Double): Support {
        val e = encoders(mime)
        if (e.isNullOrEmpty()) return Support.UNKNOWN
        return runCatching {
            if (e.any { it.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(w, h, fps) == true }) Support.YES else Support.NO
        }.getOrDefault(Support.UNKNOWN)
    }

    override fun tenBit(mime: String): Support {
        if (mime == MimeTypes.VIDEO_H264) return Support.NO
        val e = encoders(mime)
        if (e.isNullOrEmpty()) return Support.UNKNOWN
        return if (VideoPipeline.tenBitProfile(mime) != null) Support.YES else Support.NO
    }

    override val hdr: Support get() = if (EncoderCaps.hdrSupported) Support.YES else Support.NO
}

/**
 * What an export will actually make from the chosen [ExportSettings] – the one place the rules live, used by the
 * export dialog (what it shows, whether Export can be pressed) and by the export itself, so the two never judge the
 * same settings differently.
 *
 * - HDR is kept only with HDR sources, an HDR encoder and nothing graded (grading is done in SDR); then HEVC.
 * - 10-bit SDR (chosen, or Auto with 10-bit sources) is encoded with the chosen codec if it has Main10, otherwise HEVC,
 *   otherwise AV1; with none of them the file is 8-bit.
 * - A size the chosen codec can't make: another codec that can (8-bit SDR only). If no codec can, the export is refused.
 *   "Can't tell" never refuses.
 */
data class ExportPlan(
    /** The settings the export runs with (codec swapped, HDR off when it can't be kept). */
    val effective: ExportSettings,
    val width: Int,
    val height: Int,
    val fps: Double,
    val tenBit: Boolean,
    val hdr: Boolean,
    val errors: List<Issue>,
    val warnings: List<Issue>,
) {
    val ok: Boolean get() = errors.isEmpty()

    /** Something to tell about the plan; [fix] = settings that would avoid it (offered as a button). */
    data class Issue(val kind: Kind, val codec: String? = null, val other: String? = null, val fix: ExportSettings? = null)

    enum class Kind {
        /** No codec can make the size (error). */
        SIZE_NONE,
        /** The chosen codec can't make the size, [other] can – the export uses [other]. */
        SIZE_OTHER_CODEC,
        /** The phone doesn't tell whether the size works. */
        SIZE_UNKNOWN,
        /** Larger than the project: frames are scaled up. */
        UPSCALED,
        /** The encoder doesn't list the frame rate at this size (it may pick another). */
        RATE_UNLISTED,
        /** 10-bit wanted, no encoder makes it: the file is 8-bit. */
        TEN_BIT_NONE,
        /** HDR wanted, the phone can't encode it: the file is SDR. */
        HDR_TO_SDR,
        /** HDR wanted, but grading is done in SDR: the file is SDR. */
        HDR_GRADED,
    }

    companion object {
        /**
         * @param anyHdr some video clip is HDR; [anyTenBit] some clip is 10-bit (null = not known yet: Auto stays
         *   8-bit in the plan); [graded] some clip or the timeline has a grade
         */
        fun resolve(
            s: ExportSettings, projectW: Int, projectH: Int, projectFps: Double,
            anyHdr: Boolean, anyTenBit: Boolean?, graded: Boolean, caps: EncoderInfo,
        ): ExportPlan {
            val errors = ArrayList<Issue>()
            val warnings = ArrayList<Issue>()
            val (w, h) = s.outputSize(projectW, projectH)
            val fps = s.outputFps(projectFps)
            if (s.height > 0 && minOf(w, h) > minOf(projectW, projectH)) warnings += Issue(Kind.UPSCALED)

            // HDR
            var hdr = false
            if (s.hdr && anyHdr) {
                when {
                    graded -> warnings += Issue(Kind.HDR_GRADED)
                    caps.hdr == Support.NO -> warnings += Issue(Kind.HDR_TO_SDR)
                    else -> hdr = true
                }
            }
            if (hdr) {
                val st = s.copy(codec = MimeTypes.VIDEO_H265)
                if (caps.size(st.codec, w, h) == Support.NO) errors += Issue(Kind.SIZE_NONE, st.codec)
                return ExportPlan(st, w, h, fps, tenBit = false, hdr = true, errors, warnings)
            }

            // SDR, 8 or 10 bits
            val wantTen = s.bitDepth == 10 || (s.bitDepth == 0 && anyTenBit == true)
            val tenCodec = if (wantTen) tenBitCodec(s.codec, caps) else null
            if (wantTen && tenCodec == null) warnings += Issue(Kind.TEN_BIT_NONE)
            var st = s.copy(hdr = false, codec = tenCodec ?: s.codec)
            val tenBit = tenCodec != null

            // the size
            when (caps.size(st.codec, w, h)) {
                Support.YES -> Unit
                Support.UNKNOWN -> warnings += Issue(Kind.SIZE_UNKNOWN, st.codec)
                Support.NO -> {
                    val other = if (tenBit) null else sizeCodec(st.codec, w, h, caps)
                    if (other != null) {
                        warnings += Issue(Kind.SIZE_OTHER_CODEC, st.codec, other, fix = s.copy(codec = other))
                        st = st.copy(codec = other)
                    } else {
                        errors += Issue(Kind.SIZE_NONE, st.codec)
                    }
                }
            }
            if (errors.isEmpty() && caps.rate(st.codec, w, h, fps) == Support.NO) warnings += Issue(Kind.RATE_UNLISTED, st.codec)
            return ExportPlan(st, w, h, fps, tenBit, hdr = false, errors, warnings)
        }

        /** The codec for 10-bit SDR: [preferred] if it has Main10, otherwise HEVC, otherwise AV1 ("can't tell" counts). */
        fun tenBitCodec(preferred: String, caps: EncoderInfo): String? {
            fun can(m: String) = caps.tenBit(m) == Support.YES
            return when {
                preferred != MimeTypes.VIDEO_H264 && can(preferred) -> preferred
                can(MimeTypes.VIDEO_H265) -> MimeTypes.VIDEO_H265
                can(MimeTypes.VIDEO_AV1) -> MimeTypes.VIDEO_AV1
                else -> null
            }
        }

        /** Another offered codec that can make [w]×[h] (a sure yes first, then "can't tell"); null = none. */
        fun sizeCodec(not: String, w: Int, h: Int, caps: EncoderInfo): String? {
            val others = caps.codecs.filter { it != not }
            return others.firstOrNull { caps.size(it, w, h) == Support.YES } ?: others.firstOrNull { caps.size(it, w, h) == Support.UNKNOWN }
        }
    }
}

/**
 * The export's presets in plain words. Each one only sets choices the export already has (codec, bit depth, bitrate
 * level, audio quality) on top of the current settings – resolution and frame rate stay as chosen – and goes through
 * [ExportPlan] like any choice. Settings that match no preset are "custom".
 */
enum class ExportPreset {
    /** H.264, 8-bit, the standard bitrate: plays everywhere. */
    COMPATIBLE,
    /** HEVC where the phone has it (same quality in about 60 % of the bitrate), the low bitrate, 8-bit. */
    SMALLER,
    /** HEVC where the phone has it, the highest bitrate level, bit depth following the sources. */
    QUALITY,
    CUSTOM;

    /** These settings with the preset applied for a [w]×[h] project at [fps] (unchanged for [CUSTOM]). */
    fun apply(s: ExportSettings, w: Int, h: Int, fps: Double, caps: EncoderInfo): ExportSettings {
        val hevc = if (MimeTypes.VIDEO_H265 in caps.codecs) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val (ow, oh) = s.outputSize(w, h)
        val f = s.outputFps(fps).takeIf { it > 0.0 } ?: 30.0
        fun mbps(level: Int, codec: String) = ExportPresets.mbps(level, ow, oh, f, codec).toFloat()
        val base = s.copy(hdr = false, cbr = false, keyframeSec = 0f, highProfile = true, audio = true)
        return when (this) {
            COMPATIBLE -> base.copy(codec = MimeTypes.VIDEO_H264, bitDepth = 8, bitrateMbps = 0f, audioKbps = 192)
            SMALLER -> base.copy(codec = hevc, bitDepth = 8, bitrateMbps = mbps(ExportPresets.LOW, hevc), audioKbps = 128)
            QUALITY -> base.copy(codec = hevc, bitDepth = 0, bitrateMbps = mbps(ExportPresets.MAX, hevc), audioKbps = 320)
            CUSTOM -> s
        }
    }

    companion object {
        /** The preset these settings are (CUSTOM when a single choice differs from every preset). */
        fun of(s: ExportSettings, w: Int, h: Int, fps: Double, caps: EncoderInfo): ExportPreset =
            entries.firstOrNull { it != CUSTOM && it.apply(s, w, h, fps, caps) == s } ?: CUSTOM
    }
}
