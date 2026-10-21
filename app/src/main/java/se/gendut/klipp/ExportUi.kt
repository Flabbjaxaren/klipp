package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.EncoderCaps
import se.gendut.klipp.engine.ExportPresets
import se.gendut.klipp.engine.ExportSettings
import androidx.media3.common.MimeTypes

/** Short sides to choose from (0 = the project's). */
private val HEIGHTS = listOf(0, 480, 720, 1080, 1440, 2160)
private val EXPORT_FPS = listOf(0.0, 23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0)
private val AUDIO_KBPS = listOf(96, 128, 192, 256, 320)
private val SAMPLE_RATES = listOf(0, 44_100, 48_000)
private val KEYFRAMES = listOf(0f, 0.5f, 1f, 2f, 5f)

/**
 * All export options (also used as the default when a project is created): resolution, frame rate, codec,
 * bitrate (levels or custom), CBR/VBR, keyframes, profile, SDR/HDR and audio.
 *
 * @param hdrPossible the sources are HDR and the phone can encode HDR; [hdrBlocked] = why it still isn't possible (grade)
 */
@Composable
fun ExportOptions(
    s: ExportSettings, seqW: Int, seqH: Int, projectFps: Double,
    hdrPossible: Boolean, hdrBlocked: String?,
    onChange: (ExportSettings) -> Unit,
) {
    val (ow, oh) = s.outputSize(seqW, seqH)
    val outFps = s.outputFps(projectFps)
    val auto = stringResource(R.string.exportui_auto)
    val projects = stringResource(R.string.exportui_project_setting)

    DropField(stringResource(R.string.exportui_resolution), HEIGHTS.map { h ->
        val (w2, h2) = s.copy(height = h).outputSize(seqW, seqH)
        val ok = EncoderCaps.sizeSupported(s.codec, w2, h2)
        Opt(h, if (h == 0) projects else if (h == 2160) "4K" else "${h}p",
            if (!ok) stringResource(R.string.exportui_res_hint_unsupported, w2, h2)
            else if (h > 0 && minOf(w2, h2) > minOf(seqW, seqH)) stringResource(R.string.exportui_res_hint_upscaled, w2, h2)
            else "${w2}×$h2",
            enabled = ok || h == s.height, key = "h$h")
    }, s.height, "exp:res") { onChange(s.copy(height = it)) }
    if (minOf(ow, oh) > minOf(seqW, seqH)) Note(stringResource(R.string.exportui_note_upscaled))
    if (!EncoderCaps.sizeSupported(s.codec, ow, oh)) Note(stringResource(R.string.exportui_note_size_unsupported, ow, oh))

    // higher than the project isn't possible (frames aren't invented); same as the project = "Project"
    val fpsOpts = listOf(Opt(0.0, projects, stringResource(R.string.exportui_fps_value, fpsLabel(projectFps)), key = "fps0")) +
        EXPORT_FPS.filter { it > 0.0 && it <= projectFps + 0.01 && kotlin.math.abs(it - projectFps) >= 0.001 }
            .map { Opt(it, stringResource(R.string.exportui_fps_value, fpsLabel(it)), key = "fps$it") }
    val fpsSel = if (s.fps <= 0.0 || kotlin.math.abs(s.fps - projectFps) < 0.001) 0.0 else fpsOpts.firstOrNull { kotlin.math.abs(it.value - s.fps) < 0.001 }?.value ?: 0.0
    DropField(stringResource(R.string.exportui_frame_rate), fpsOpts, fpsSel, "exp:fps") { onChange(s.copy(fps = it)) }

    DropField(stringResource(R.string.exportui_codec), EncoderCaps.codecs.map { c ->
        Opt(c.mime, c.label.substringBefore(" · "), c.label.substringAfter(" · ", ""), enabled = !s.hdr || c.mime == MimeTypes.VIDEO_H265, key = c.mime.substringAfter('/'))
    }, s.codec, "exp:codec") { onChange(s.copy(codec = it)) }

    // bit depth (SDR): Auto follows the sources – 10-bit is encoded as HEVC/AV1 Main10 (H.264 is then swapped for HEVC)
    if (!s.hdr) {
        val tenCodec = remember { se.gendut.klipp.engine.VideoPipeline.tenBitCodec(s.codec) }
        val tenHint = when {
            tenCodec == null -> stringResource(R.string.exportui_bits_no_ten)
            s.codec == MimeTypes.VIDEO_H264 -> stringResource(R.string.exportui_bits_h264_hevc)
            else -> stringResource(R.string.exportui_bits_main10)
        }
        DropField(stringResource(R.string.exportui_bit_depth), listOf(
            Opt(0, auto, stringResource(R.string.exportui_bits_auto_hint), key = "b0"),
            Opt(8, stringResource(R.string.exportui_bits_8), stringResource(R.string.exportui_bits_8_hint), key = "b8"),
            Opt(10, stringResource(R.string.exportui_bits_10), tenHint, enabled = tenCodec != null, key = "b10"),
        ), s.bitDepth, "exp:bits") { onChange(s.copy(bitDepth = it)) }
    }

    // quality: the encoder's choice, four levels by format and codec, or a custom bitrate
    val levels = ExportPresets.LABELS.indices.map { ExportPresets.mbps(it, ow, oh, outFps, s.codec) }
    val matched = levels.indexOfFirst { s.bitrateMbps > 0f && kotlin.math.abs(s.bitrateMbps - it) < 0.01 }
    var custom by remember { mutableStateOf(s.bitrateMbps > 0f && matched < 0) }
    val qSel = when { custom -> CUSTOM_Q; s.bitrateMbps <= 0f -> -1; matched >= 0 -> matched; else -> CUSTOM_Q }
    DropField(stringResource(R.string.exportui_quality),
        listOf(Opt(-1, auto, stringResource(R.string.exportui_quality_auto_hint, mbpsLabel(levels[ExportPresets.STANDARD])), key = "auto")) +
            levels.mapIndexed { i, m -> Opt(i, ExportPresets.LABELS[i], mbpsLabel(m), key = "q$i") } +
            Opt(CUSTOM_Q, stringResource(R.string.exportui_quality_custom), stringResource(R.string.exportui_quality_custom_hint), key = "custom"),
        qSel, "exp:q",
    ) { v ->
        when (v) {
            -1 -> { custom = false; onChange(s.copy(bitrateMbps = 0f)) }
            CUSTOM_Q -> { custom = true; if (s.bitrateMbps <= 0f) onChange(s.copy(bitrateMbps = levels[ExportPresets.STANDARD].toFloat())) }
            else -> { custom = false; onChange(s.copy(bitrateMbps = levels[v].toFloat())) }
        }
    }
    if (qSel == CUSTOM_Q) PropSlider(stringResource(R.string.exportui_mbps_unit), if (s.bitrateMbps > 0f) s.bitrateMbps else levels[ExportPresets.STANDARD].toFloat(), 1f, 200f, levels[ExportPresets.STANDARD].toFloat(),
        { "%.0f".format(it) }, "expBitrate", lane = false) { onChange(s.copy(bitrateMbps = Math.round(it).toFloat())) }
    Chips {
        Choice(stringResource(R.string.exportui_vbr), !s.cbr, true, "exp:vbr") { onChange(s.copy(cbr = false)) }
        Choice(stringResource(R.string.exportui_cbr), s.cbr, true, "exp:cbr") { onChange(s.copy(cbr = true)) }
    }

    val keyAutoHint = stringResource(R.string.exportui_keyframe_auto_hint)
    val keyDenseHint = stringResource(R.string.exportui_keyframe_dense_hint)
    DropField(stringResource(R.string.exportui_keyframes), KEYFRAMES.map { k ->
        Opt(k, if (k == 0f) auto else stringResource(R.string.exportui_keyframe_every, if (k % 1f == 0f) k.toInt().toString() else "%.1f".format(k)),
            if (k == 0f) keyAutoHint else if (k <= 0.5f) keyDenseHint else null, key = "k$k")
    }, s.keyframeSec, "exp:key") { onChange(s.copy(keyframeSec = it)) }
    if (s.codec == MimeTypes.VIDEO_H264 && !s.hdr) {
        SectionLabel(stringResource(R.string.exportui_profile))
        Chips {
            Choice(stringResource(R.string.exportui_profile_high), s.highProfile, true, "exp:high") { onChange(s.copy(highProfile = true)) }
            Choice(stringResource(R.string.exportui_profile_encoder), !s.highProfile, true, "exp:baseline") { onChange(s.copy(highProfile = false)) }
        }
    }

    SectionLabel(stringResource(R.string.exportui_color))
    Chips {
        Choice("SDR · Rec.709", !s.hdr, true, "exp:sdr") { onChange(s.copy(hdr = false)) }
        Choice(stringResource(R.string.exportui_hdr_keep), s.hdr, hdrPossible && hdrBlocked == null, "exp:hdr") { onChange(s.copy(hdr = true, codec = MimeTypes.VIDEO_H265)) }
    }
    Note(when {
        s.hdr -> stringResource(R.string.exportui_note_hdr)
        hdrBlocked != null -> hdrBlocked
        !hdrPossible -> stringResource(R.string.exportui_note_hdr_impossible)
        else -> stringResource(R.string.exportui_note_sdr)
    })

    SectionLabel(stringResource(R.string.exportui_audio))
    Chips {
        Choice(stringResource(R.string.exportui_with_audio), s.audio, true, "exp:audio") { onChange(s.copy(audio = true)) }
        Choice(stringResource(R.string.exportui_without_audio), !s.audio, true, "exp:noaudio") { onChange(s.copy(audio = false)) }
    }
    if (s.audio) {
        val aqSpeech = stringResource(R.string.exportui_aq_speech)
        val aqGood = stringResource(R.string.exportui_aq_good)
        val aqStandard = stringResource(R.string.exportui_aq_standard)
        val aqHigh = stringResource(R.string.exportui_aq_high)
        DropField(stringResource(R.string.exportui_audio_quality), AUDIO_KBPS.map { k ->
            Opt(k, stringResource(R.string.exportui_aac, k), when { k <= 96 -> aqSpeech; k <= 128 -> aqGood; k <= 192 -> aqStandard; else -> aqHigh }, key = "a$k")
        }, s.audioKbps, "exp:akbps") { onChange(s.copy(audioKbps = it)) }
        DropField(stringResource(R.string.exportui_sample_rate), SAMPLE_RATES.map { r ->
            Opt(r, if (r == 0) projects else srLabel(r), key = "sr$r")
        }, s.sampleRate, "exp:sr") { onChange(s.copy(sampleRate = it)) }
        Chips {
            Choice(stringResource(R.string.exportui_stereo), !s.mono, true, "exp:stereo") { onChange(s.copy(mono = false)) }
            Choice(stringResource(R.string.exportui_mono), s.mono, true, "exp:mono") { onChange(s.copy(mono = true)) }
        }
    }
}

private const val CUSTOM_Q = 99

/** The dialog shown when tapping Export: file name, all options and an estimate of the file. */
@Composable
fun ExportDialog(
    initial: ExportSettings, defaultName: String, seqW: Int, seqH: Int, projectFps: Double, seconds: Double,
    hdrPossible: Boolean, hdrBlocked: String?,
    onDismiss: () -> Unit, onExport: (ExportSettings, String) -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(defaultName) }
    val (ow, oh) = s.outputSize(seqW, seqH)
    val mb = s.estimateMb(seqW, seqH, projectFps, seconds)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exportui_title)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).testTag("exportDialog")) {
                TextInput(stringResource(R.string.exportui_file_name), name) { name = it.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) }
                ExportOptions(s, seqW, seqH, projectFps, hdrPossible, hdrBlocked) { s = it }
                Text(
                    "${ow}×$oh · ${stringResource(R.string.exportui_fps_value, fpsLabel(s.outputFps(projectFps)))} · ${EncoderCaps.codecs.firstOrNull { it.mime == s.codec }?.label?.substringBefore(" ·") ?: "H.264"} · " +
                        (if (s.bitrateMbps > 0f) mbpsLabel(s.bitrateMbps.toDouble()) else stringResource(R.string.exportui_auto_lower)) + " · ≈ ${sizeLabel(mb)}",
                    color = K.PrimarySoft, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("exp:summary"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onExport(s, name.ifBlank { defaultName }) }, modifier = Modifier.testTag("exp:go")) { Text(stringResource(R.string.exportui_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.exportui_cancel)) } },
        containerColor = K.SurfaceHigh,
    )
}

/** 44100 → "44.1 kHz" ("44,1 kHz" in Swedish), 48000 → "48 kHz". */
fun srLabel(r: Int): String = (if (r % 1000 == 0) "${r / 1000}" else "%.1f".format(r / 1000.0)) + " kHz"

fun mbpsLabel(m: Double): String = Str.t(R.string.exportui_mbps_value, if (m < 10) "%.1f".format(m) else "%.0f".format(m))

private fun sizeLabel(mb: Double): String = if (mb >= 1000) "%.1f GB".format(mb / 1000) else "%.0f MB".format(mb.coerceAtLeast(1.0))

@Composable
private fun Chips(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Note(text: String) {
    Text(text, color = K.OnSurfaceVar, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

/** One choice (disabled = grayed out, can't be tapped). */
@Composable
private fun Choice(label: String, on: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Text(
        label,
        color = when { !enabled -> K.Outline; on -> K.OnPrimary; else -> K.OnSurface },
        fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(if (on) 18.dp else 10.dp))
            .background(if (on) K.Primary else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (on) K.Primary else K.OutlineVar, RoundedCornerShape(if (on) 18.dp else 10.dp))
            .clickable(enabled = enabled) { haptics.tick(); onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
    )
}
