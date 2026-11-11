package se.gendut.klipp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    /** Some clip is 10-bit (null = not known). */
    anyTenBit: Boolean? = null,
    caps: se.gendut.klipp.engine.EncoderInfo = se.gendut.klipp.engine.PhoneEncoders,
    /** The project's length (s): the presets show their estimated size (0 = not shown). */
    seconds: Double = 0.0,
    onChange: (ExportSettings) -> Unit,
) {
    // the same plan the export itself follows: what is made, what is refused, what to know
    fun planOf(x: ExportSettings) = se.gendut.klipp.engine.ExportPlan.resolve(x, seqW, seqH, projectFps, hdrPossible, anyTenBit, hdrBlocked != null, caps)
    val plan = planOf(s)
    val (ow, oh) = s.outputSize(seqW, seqH)
    val outFps = s.outputFps(projectFps)
    val auto = stringResource(R.string.exportui_auto)
    val projects = stringResource(R.string.exportui_project_setting)

    // presets in plain words on top of the tabs: each sets codec, bit depth, bitrate and audio; any other choice = custom
    val preset = se.gendut.klipp.engine.ExportPreset.of(s, seqW, seqH, projectFps, caps)
    DropField(stringResource(R.string.exportui_preset), se.gendut.klipp.engine.ExportPreset.entries.map { p ->
        val x = p.apply(s, seqW, seqH, projectFps, caps)
        val px = planOf(x)
        val what = when (p) {
            se.gendut.klipp.engine.ExportPreset.COMPATIBLE -> stringResource(R.string.exportpreset_compatible_hint)
            se.gendut.klipp.engine.ExportPreset.SMALLER -> stringResource(R.string.exportpreset_smaller_hint, codecName(px.effective.codec))
            se.gendut.klipp.engine.ExportPreset.QUALITY -> stringResource(R.string.exportpreset_quality_hint, codecName(px.effective.codec))
            se.gendut.klipp.engine.ExportPreset.CUSTOM -> stringResource(R.string.exportpreset_custom_hint)
        }
        // the estimate is what the preset makes (a "smaller" file only says so when the numbers do)
        val size = if (seconds > 0 && p != se.gendut.klipp.engine.ExportPreset.CUSTOM) " · ≈ " + sizeLabel(px.effective.estimateMb(seqW, seqH, projectFps, seconds)) else ""
        Opt(p, stringResource(when (p) {
            se.gendut.klipp.engine.ExportPreset.COMPATIBLE -> R.string.exportpreset_compatible
            se.gendut.klipp.engine.ExportPreset.SMALLER -> R.string.exportpreset_smaller
            se.gendut.klipp.engine.ExportPreset.QUALITY -> R.string.exportpreset_quality
            se.gendut.klipp.engine.ExportPreset.CUSTOM -> R.string.exportpreset_custom
        }), what + size, enabled = p != se.gendut.klipp.engine.ExportPreset.CUSTOM || preset == p, key = p.name)
    }, preset, "exp:preset") { p -> onChange(p.apply(s, seqW, seqH, projectFps, caps)) }

    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    ConnectedToggleGroup(
        listOf(stringResource(R.string.exportui_tab_video), stringResource(R.string.exportui_tab_quality), stringResource(R.string.exportui_color), stringResource(R.string.exportui_audio)),
        tab, { tab = it }, Modifier.padding(vertical = 6.dp).fillMaxWidth(), tag = { "expTab:$it" },
    )
    ExportIssues(plan, onChange)
    when (tab) {
        0 -> {
    DropField(stringResource(R.string.exportui_resolution), HEIGHTS.map { h ->
        val (w2, h2) = s.copy(height = h).outputSize(seqW, seqH)
        // only a size no encoder can make is closed (one the chosen codec can't make is made in another)
        val p2 = planOf(s.copy(height = h))
        Opt(h, if (h == 0) projects else if (h == 2160) "4K" else "${h}p",
            if (!p2.ok) stringResource(R.string.exportui_res_hint_unsupported, w2, h2)
            else if (h > 0 && minOf(w2, h2) > minOf(seqW, seqH)) stringResource(R.string.exportui_res_hint_upscaled, w2, h2)
            else if (p2.effective.codec != s.codec && !p2.tenBit) "${w2}×$h2 · ${codecName(p2.effective.codec)}"
            else "${w2}×$h2",
            enabled = p2.ok || h == s.height, key = "h$h")
    }, s.height, "exp:res") { onChange(s.copy(height = it)) }

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
        // (follows the codec: not remembered)
        val tenCodec = se.gendut.klipp.engine.ExportPlan.tenBitCodec(s.codec, caps)
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

        }
        1 -> {
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

        }
        2 -> {
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

        }
        else -> {
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
    }
}

private const val CUSTOM_Q = 99

/** The dialog shown when tapping Export: file name, all options and an estimate of the file. */
@Composable
fun ExportDialog(
    initial: ExportSettings, defaultName: String, seqW: Int, seqH: Int, projectFps: Double, seconds: Double,
    hdrPossible: Boolean, hdrBlocked: String?,
    anyTenBit: Boolean? = null,
    caps: se.gendut.klipp.engine.EncoderInfo = se.gendut.klipp.engine.PhoneEncoders,
    onDismiss: () -> Unit, onExport: (ExportSettings, String) -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(defaultName) }
    val plan = se.gendut.klipp.engine.ExportPlan.resolve(s, seqW, seqH, projectFps, hdrPossible, anyTenBit, hdrBlocked != null, caps)
    val ow = plan.width
    val oh = plan.height
    // the estimate and the summary are for what is actually made (the codec may have been swapped)
    val mb = plan.effective.estimateMb(seqW, seqH, projectFps, seconds)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exportui_title)) },
        text = {
            Column(Modifier.testTag("exportDialog")) {
                // the same height on every tab, so the dialog stays where it is when the tab changes; the summary is always in view
                Column(Modifier.weight(1f, fill = false).height(420.dp).verticalScroll(rememberScrollState())) {
                    TextInput(stringResource(R.string.exportui_file_name), name) { name = it.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) }
                    ExportOptions(s, seqW, seqH, projectFps, hdrPossible, hdrBlocked, anyTenBit, caps, seconds) { s = it }
                }
                Text(
                    "${ow}×$oh · ${stringResource(R.string.exportui_fps_value, fpsLabel(plan.fps))} · ${codecName(plan.effective.codec)}" +
                        (if (plan.hdr) " HDR" else if (plan.tenBit) " 10-bit" else "") + " · " +
                        (if (s.bitrateMbps > 0f) mbpsLabel(s.bitrateMbps.toDouble()) else stringResource(R.string.exportui_auto_lower)) + " · ≈ ${sizeLabel(mb)}",
                    color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("exp:summary"),
                )
            }
        },
        // a plan that can't be made can't be started (the reason is shown above the options)
        confirmButton = {
            FlatTextButton(onClick = { if (plan.ok) onExport(s, name.ifBlank { defaultName }) }, enabled = plan.ok, modifier = Modifier.testTag("exp:go")) {
                Text(stringResource(R.string.exportui_go))
            }
        },
        dismissButton = { FlatTextButton(onClick = onDismiss) { Text(stringResource(R.string.exportui_cancel)) } },
        containerColor = K.SurfaceHigh,
    )
}

/** The codec's short name ("H.264", "HEVC", "AV1"). */
fun codecName(mime: String): String = EncoderCaps.codecs.firstOrNull { it.mime == mime }?.label?.substringBefore(" ·") ?: when (mime) {
    MimeTypes.VIDEO_H265 -> "HEVC"
    MimeTypes.VIDEO_AV1 -> "AV1"
    else -> "H.264"
}

/** What an issue of an export plan means, in words (also the export's error message). */
fun exportIssueText(i: se.gendut.klipp.engine.ExportPlan.Issue, plan: se.gendut.klipp.engine.ExportPlan): String {
    val w = plan.width
    val h = plan.height
    val c = i.codec?.let(::codecName).orEmpty()
    return when (i.kind) {
        se.gendut.klipp.engine.ExportPlan.Kind.SIZE_NONE -> Str.t(R.string.exportplan_size_none, w, h)
        se.gendut.klipp.engine.ExportPlan.Kind.SIZE_OTHER_CODEC -> Str.t(R.string.exportplan_size_other, c, w, h, i.other?.let(::codecName).orEmpty())
        se.gendut.klipp.engine.ExportPlan.Kind.SIZE_UNKNOWN -> Str.t(R.string.exportplan_size_unknown, c, w, h)
        se.gendut.klipp.engine.ExportPlan.Kind.UPSCALED -> Str.t(R.string.exportui_note_upscaled)
        se.gendut.klipp.engine.ExportPlan.Kind.RATE_UNLISTED -> Str.t(R.string.exportplan_rate_unlisted, c, fpsLabel(plan.fps))
        se.gendut.klipp.engine.ExportPlan.Kind.TEN_BIT_NONE -> Str.t(R.string.exportplan_ten_none)
        se.gendut.klipp.engine.ExportPlan.Kind.HDR_TO_SDR -> Str.t(R.string.exportplan_hdr_sdr)
        se.gendut.klipp.engine.ExportPlan.Kind.HDR_GRADED -> Str.t(R.string.app_hdr_blocked)
    }
}

/** The plan's errors (red, with a way out when there is one) and what to know (muted), above the options. */
@Composable
private fun ExportIssues(plan: se.gendut.klipp.engine.ExportPlan, onFix: (ExportSettings) -> Unit) {
    if (plan.errors.isEmpty() && plan.warnings.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).testTag("exp:issues"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in plan.errors + plan.warnings) {
            val error = i in plan.errors
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(exportIssueText(i, plan), color = if (error) K.Error else K.OnSurfaceVar, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("exp:issue:${i.kind.name}"))
                i.fix?.let { f ->
                    FlatTextButton(onClick = { onFix(f) }, modifier = Modifier.testTag("exp:fix")) {
                        Text(stringResource(R.string.exportplan_fix, codecName(f.codec)), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 44100 → "44.1 kHz" ("44,1 kHz" in Swedish), 48000 → "48 kHz". */
fun srLabel(r: Int): String = (if (r % 1000 == 0) "${r / 1000}" else "%.1f".format(r / 1000.0)) + " kHz"

fun mbpsLabel(m: Double): String = Str.t(R.string.exportui_mbps_value, if (m < 10) "%.1f".format(m) else "%.0f".format(m))

private fun sizeLabel(mb: Double): String = if (mb >= 1000) "%.1f GB".format(mb / 1000) else "%.0f MB".format(mb.coerceAtLeast(1.0))

@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Note(text: String) {
    Text(text, color = K.OnSurfaceVar, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

/** One choice (the chosen one has the accent's container; disabled = grayed out, can't be tapped). */
@Composable
private fun Choice(label: String, on: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Text(
        label,
        color = when { on -> K.PrimarySoft; !enabled -> K.Outline; else -> K.OnSurface },
        fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(if (on) 20.dp else 10.dp))
            .background(if (on) K.PrimaryContainer else K.SurfaceHighest)
            .clickable(enabled = enabled) { haptics.tick(); onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(tag),
    )
}
