package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.AutoCaptions
import se.gendut.klipp.engine.ProjectController
import se.gendut.klipp.engine.SpeechModel
import se.gendut.klipp.engine.SpeechModels

/**
 * Text-based editing: the speech on the audio tracks as text, word by word. Tap a word = the playhead goes there; tap
 * another = the words between them are selected; delete = the video, sound and captions of those words are cut out
 * of the timeline (all synced tracks follow). Pauses and filler words can be removed in one go.
 */
@Composable
fun TranscriptCard(controller: ProjectController, state: EditorState) {
    val haptics = rememberHaptics()
    var open by remember { mutableStateOf(false) }
    PanelCard(Modifier.padding(vertical = 6.dp), title = stringResource(R.string.transcript_title)) {
        Text(stringResource(R.string.transcript_info), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
        PillButton(stringResource(R.string.transcript_open), { haptics.confirm(); open = true },
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("transcript:open"), icon = Ic.Transcript)
    }
    if (open) TranscriptSheet(controller, state) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptSheet(controller: ProjectController, state: EditorState, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    val ctx = LocalContext.current
    val ac = AutoCaptions
    var text by remember { mutableStateOf<ProjectController.TxText?>(null) }
    // re-read after every change of the timeline or the transcripts (deleted words, undo …)
    LaunchedEffect(controller.revision, controller.transcriptRevision) { text = controller.transcriptWords() }
    /** The selection: the word tapped first and the one tapped last (indices into the words). */
    var anchor by remember { mutableStateOf<Int?>(null) }
    var end by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(text) { anchor = null; end = null }
    val sel = anchor?.let { a -> val b = end ?: a; minOf(a, b)..maxOf(a, b) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = K.Surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).testTag("transcriptSheet")) {
            Text(stringResource(R.string.transcript_title), color = K.OnSurface, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 16.dp))
            val t = text
            val busy = ac.progress
            if (busy != null) {
                Text(ac.phase, color = K.OnSurface, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                androidx.compose.material3.LinearProgressIndicator(progress = { busy }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = K.Primary)
                PillButton(stringResource(R.string.titleui_cancel), { ac.cancel() }, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), icon = Ic.Close, filled = false)
            } else if (t == null || t.words.isEmpty()) {
                // no transcript yet: pick a model and listen
                var model by remember { mutableStateOf(SpeechModel.SV_BEST) }
                Text(stringResource(R.string.transcript_empty), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                val models = SpeechModel.entries
                ConnectedToggleGroup(models.map { it.label }, models.indexOf(model), { model = models[it] },
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), tag = { "tx:model$it" })
                val ready = remember(model, ac.modelRevision, ac.running) { SpeechModels.ready(ctx, model) }
                if (!ready) PillButton(stringResource(R.string.titleui_download_model, model.bytes / 1_000_000), { haptics.confirm(); ac.download(ctx, model) },
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("tx:download"), icon = Ic.Download, filled = false)
                else PillButton(stringResource(R.string.transcript_transcribe), { haptics.confirm(); ac.transcript(ctx, controller, model) },
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("tx:start"), icon = Ic.Transcript)
            } else {
                // actions: the selection's delete, pauses, filler words
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (sel != null) {
                        PillButton(pluralStringResource(R.plurals.transcript_delete_words, sel.count(), sel.count()), {
                            haptics.heavy()
                            state.playing = false
                            controller.extractWords(t.words[sel.first].i, t.words[sel.last].i)
                            anchor = null; end = null
                        }, Modifier.testTag("tx:delete"), icon = Ic.Delete)
                        ToggleChip(stringResource(R.string.app_deselect), false, { anchor = null; end = null })
                    } else {
                        PillButton(stringResource(R.string.transcript_remove_pauses), { haptics.confirm(); state.playing = false; controller.removePauses(PAUSE_MIN, PAUSE_KEEP) },
                            Modifier.testTag("tx:pauses"), filled = false)
                        PillButton(stringResource(R.string.transcript_remove_fillers), { haptics.confirm(); state.playing = false; controller.removeFillers() },
                            Modifier.testTag("tx:fillers"), filled = false)
                    }
                }
                Text(stringResource(if (sel == null) R.string.transcript_hint else R.string.transcript_hint_selected), color = K.Outline, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
                val list = rememberLazyListState()
                // the word being spoken at the playhead
                val now = state.playhead.toDouble()
                LazyColumn(Modifier.fillMaxWidth().padding(top = 6.dp), state = list) {
                    itemsIndexed(t.paragraphs, key = { _, r -> r.first }) { _, r ->
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            for (k in r) {
                                val w = t.words[k]
                                val selected = sel != null && k in sel
                                val current = now >= w.start && now < w.end
                                Text(
                                    w.text, fontSize = 17.sp, lineHeight = 26.sp,
                                    color = when { selected -> K.OnPrimary; current -> K.Primary; else -> K.OnSurface },
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(end = 3.dp, bottom = 2.dp).clip(RoundedCornerShape(6.dp))
                                        .background(if (selected) K.Primary else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                            haptics.tick()
                                            state.playing = false
                                            state.playhead = w.start.toFloat()
                                            // tap = select this word; a second tap elsewhere extends; a tap in the selection clears it
                                            when {
                                                sel != null && k in sel && sel.count() > 1 -> { anchor = k; end = null }
                                                sel != null && k in sel -> { anchor = null; end = null }
                                                anchor == null -> anchor = k
                                                else -> end = k
                                            }
                                        }
                                        .padding(horizontal = 3.dp, vertical = 1.dp)
                                        .testTag("tx:w$k"),
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.width(1.dp).padding(bottom = 40.dp)) }
                }
            }
            ac.message?.let { Text(it, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("tx:msg")) }
        }
    }
}

/** Pauses longer than this (s) are removed … */
private const val PAUSE_MIN = 0.8
/** … keeping this much (s) on each side, so speech isn't clipped. */
private const val PAUSE_KEEP = 0.15
