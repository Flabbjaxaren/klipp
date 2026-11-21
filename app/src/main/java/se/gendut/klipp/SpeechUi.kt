package se.gendut.klipp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.AutoCaptions
import se.gendut.klipp.engine.SpeechChoice
import se.gendut.klipp.engine.SpeechModel
import se.gendut.klipp.engine.SpeechModels
import se.gendut.klipp.engine.WordAligner
import androidx.compose.ui.platform.testTag

/** The chosen language and quality (saved), and the model they give. */
class SpeechPick(language: String, tier: Int) {
    var language by mutableStateOf(language)
    var tier by mutableIntStateOf(tier)
    val model: SpeechModel get() = SpeechChoice.model(language, tier)
}

@Composable
fun rememberSpeechPick(): SpeechPick {
    val ctx = LocalContext.current
    return remember { SpeechPick(SpeechChoice.language(ctx), SpeechChoice.tier(ctx)) }
}

/**
 * Language + quality for speech recognition (auto captions and text-based editing share the choice): the language
 * (the phone's by default, or automatic) and Fast / Balanced / Best. Under it, the model they give and whether it is
 * downloaded.
 */
@Composable
fun SpeechPicker(pick: SpeechPick, tag: String, words: Boolean = false) {
    val ctx = LocalContext.current
    val ac = AutoCaptions
    Column(Modifier.fillMaxWidth()) {
        DropField(
            stringResource(R.string.transcribe_language), SpeechChoice.LANGUAGES.map { Opt(it, SpeechChoice.label(it), key = it) },
            pick.language, "$tag:lang",
        ) { pick.language = it; SpeechChoice.save(ctx, pick.language, pick.tier) }
        ConnectedToggleGroup(
            listOf(stringResource(R.string.transcribe_tier_fast), stringResource(R.string.transcribe_tier_balanced), stringResource(R.string.transcribe_tier_best)),
            pick.tier, { pick.tier = it; SpeechChoice.save(ctx, pick.language, pick.tier) },
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), tag = { "$tag:tier$it" },
        )
        val model = pick.model
        val ready = remember(model, ac.modelRevision, ac.running) { SpeechModels.ready(ctx, model) }
        Text(
            stringResource(if (ready) R.string.titleui_model_ready else R.string.titleui_model_needs_download, model.detail),
            color = K.Outline, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp),
        )
        if (pick.tier == 2) Text(stringResource(R.string.transcribe_best_hint), color = K.Outline, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp))
        // exact word edges for cutting (Swedish): a switch, and the model's download when it is on
        if (words && SpeechChoice.whisperLanguage(model, pick.language) == WordAligner.LANGUAGE) WordEdgesRow(tag, ready)
    }
}

/** "Exact word edges": on/off, and the model (1.3 GB) downloaded when it is on and missing. */
@Composable
private fun WordEdgesRow(tag: String, modelReady: Boolean) {
    val ctx = LocalContext.current
    val ac = AutoCaptions
    var on by remember { mutableStateOf(WordAligner.enabled(ctx)) }
    val ready = remember(ac.modelRevision, ac.running) { WordAligner.ready(ctx) }
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        ToggleChip(stringResource(R.string.speech_word_edges), on, { on = !on; WordAligner.setEnabled(ctx, on) },
            Modifier.testTag("$tag:edges"), icon = Ic.Waveform)
        if (on && !ready && modelReady) PillButton(stringResource(R.string.titleui_download_model, WordAligner.BYTES / 1_000_000), { ac.downloadAligner(ctx) },
            Modifier.testTag("$tag:edgesDownload"), icon = Ic.Download, filled = false, enabled = !ac.running)
    }
    Text(stringResource(if (!on) R.string.speech_word_edges_off else if (ready) R.string.speech_word_edges_ready else R.string.speech_word_edges_needs),
        color = K.Outline, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp))
}
