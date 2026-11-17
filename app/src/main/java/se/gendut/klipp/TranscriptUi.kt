package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import se.gendut.klipp.engine.SpeechModels

/**
 * The sequence's transcript as a view of the edit page (the timeline's other view): the speech on the audio tracks as
 * text, word by word, with the same picture and playhead. Tap a word = the playhead goes there; tap another = the words
 * between are selected; before they are deleted the view says what goes (the range, the tracks and clips, what moves
 * after it, the tracks left as they are, the captions whose text stays). Pauses and filler words can be removed in one go.
 */
@Composable
fun TranscriptView(controller: ProjectController, state: EditorState, modifier: Modifier = Modifier) {
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
    /** Listen again (another language or quality): the picker instead of the words. */
    var redo by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag("transcriptView")) {
        val t = text
        val busy = ac.progress
        if (busy != null) {
            Text(ac.phase, color = K.OnSurface, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            androidx.compose.material3.LinearProgressIndicator(progress = { busy }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = K.Primary)
            PillButton(stringResource(R.string.titleui_cancel), { ac.cancel() }, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), icon = Ic.Close, filled = false)
        } else if (t == null || t.words.isEmpty() || redo) {
            // no transcript yet (or listen again, e.g. in another language): pick a language and quality and listen
            val pick = rememberSpeechPick()
            Text(stringResource(if (redo) R.string.transcript_redo_hint else R.string.transcript_empty), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            SpeechPicker(pick, "tx")
            val model = pick.model
            val ready = remember(model, ac.modelRevision, ac.running) { SpeechModels.ready(ctx, model) }
            if (!ready) PillButton(stringResource(R.string.titleui_download_model, model.bytes / 1_000_000), { haptics.confirm(); ac.download(ctx, model) },
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("tx:download"), icon = Ic.Download, filled = false)
            else PillButton(stringResource(R.string.transcript_transcribe), { haptics.confirm(); redo = false; ac.transcript(ctx, controller, model, pick.language) },
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("tx:start"), icon = Ic.Transcript)
            if (redo) ToggleChip(stringResource(R.string.titleui_cancel), false, { redo = false }, Modifier.padding(horizontal = 14.dp))
        } else {
            if (sel != null) {
                // what the delete does, before it is done
                val cut = transcriptCut(t.words[sel.first].start.toFloat(), t.words[sel.last].end.toFloat(), FRAME,
                    state.clips, state.trackStates, state.targetVideo, state.targetAudio, state.captions)
                TranscriptCutInfo(cut)
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(pluralStringResource(R.plurals.transcript_delete_words, sel.count(), sel.count()), {
                        haptics.heavy()
                        state.playing = false
                        controller.extractWords(t.words[sel.first].i, t.words[sel.last].i)
                        anchor = null; end = null
                    }, Modifier.testTag("tx:delete"), icon = Ic.Delete, enabled = cut.tracks.isNotEmpty())
                    ToggleChip(stringResource(R.string.app_deselect), false, { anchor = null; end = null }, Modifier.testTag("tx:deselect"))
                }
            } else {
                // wraps onto a second line on narrow screens (a sideways-scrolling row hid "Transcribe again")
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.transcript_remove_pauses), { haptics.confirm(); state.playing = false; controller.removePauses(PAUSE_MIN, PAUSE_KEEP) },
                        Modifier.testTag("tx:pauses"), filled = false)
                    PillButton(stringResource(R.string.transcript_remove_fillers), { haptics.confirm(); state.playing = false; controller.removeFillers() },
                        Modifier.testTag("tx:fillers"), filled = false)
                    // the captions are made in the captions' own flow (it reuses this transcript)
                    state.onAddAction?.let { add ->
                        PillButton(stringResource(R.string.transcript_make_captions), { haptics.tick(); add(AddAction.CAPTIONS) },
                            Modifier.testTag("tx:captions"), icon = Ic.Subs, filled = false)
                    }
                    PillButton(stringResource(R.string.transcript_redo), { haptics.tick(); redo = true },
                        Modifier.testTag("tx:redo"), icon = Ic.Transcript, filled = false)
                }
                Text(stringResource(R.string.transcript_hint), color = K.Outline, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
                Text(stringResource(R.string.transcript_bulk_scope), color = K.Outline, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            val list = rememberLazyListState()
            // the word being spoken at the playhead
            val now = state.playhead.toDouble()
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp), state = list) {
                itemsIndexed(t.paragraphs, key = { _, r -> r.first }) { _, r ->
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        for (k in r) {
                            val w = t.words[k]
                            val selected = sel != null && k in sel
                            val current = now >= w.start && now < w.end
                            Text(
                                w.text, fontSize = 17.sp, lineHeight = 26.sp,
                                color = when { selected -> K.PrimarySoft; current -> K.Primary; else -> K.OnSurface },
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(end = 3.dp, bottom = 2.dp).clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) K.PrimaryContainer else androidx.compose.ui.graphics.Color.Transparent)
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
                item { Spacer(Modifier.width(1.dp).padding(bottom = (LocalBarSpace.current + 40.dp))) }
            }
        }
        ac.message?.let { Text(it, color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("tx:msg")) }
    }
}

/** A way to the transcript view (the edit page's other view) from where the text features are listed. */
@Composable
fun TranscriptCard(state: EditorState, onOpen: () -> Unit) {
    val haptics = rememberHaptics()
    PanelCard(Modifier.padding(vertical = 6.dp), title = stringResource(R.string.transcript_title)) {
        Text(stringResource(R.string.transcript_info), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
        PillButton(stringResource(R.string.transcript_open), { haptics.confirm(); state.editView = EditView.TRANSCRIPT; onOpen() },
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("transcript:open"), icon = Ic.Transcript)
    }
}

/** What a delete of the selected words does: the range, what is cut and what moves, what stays as it is. */
@Composable
internal fun TranscriptCutInfo(cut: TranscriptCut) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.SurfaceHigh).padding(12.dp).testTag("tx:cut")) {
        Text(stringResource(R.string.transcript_cut_range, formatTc(cut.start), formatTc(cut.end), "%.1f".format(cut.length)),
            color = K.OnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("tx:cut:range"))
        if (cut.tracks.isEmpty()) Text(stringResource(R.string.transcript_cut_nothing), color = K.Error, fontSize = 12.sp, modifier = Modifier.testTag("tx:cut:none"))
        else Text(pluralStringResource(R.plurals.transcript_cut_tracks, cut.clips, cut.tracks.joinToString(", "), cut.clips), color = K.OnSurface, fontSize = 12.sp, modifier = Modifier.testTag("tx:cut:tracks"))
        if (cut.tracks.isNotEmpty()) Text(if (cut.laterMoves) stringResource(R.string.transcript_cut_later_moves, "%.1f".format(cut.length)) else stringResource(R.string.transcript_cut_nothing_after),
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.testTag("tx:cut:later"))
        if (cut.untouched.isNotEmpty()) Text(stringResource(R.string.transcript_cut_untouched, cut.untouched.joinToString(", ")), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.testTag("tx:cut:untouched"))
        if (cut.captions > 0) Text(pluralStringResource(R.plurals.transcript_cut_captions, cut.captions, cut.captions), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.testTag("tx:cut:captions"))
    }
}

/** Pauses longer than this (s) are removed … */
private const val PAUSE_MIN = 0.8
/** … keeping this much (s) on each side, so speech isn't clipped. */
private const val PAUSE_KEEP = 0.15
