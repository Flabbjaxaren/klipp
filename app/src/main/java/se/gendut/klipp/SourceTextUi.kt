package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.AutoCaptions
import se.gendut.klipp.engine.SpeechLine
import se.gendut.klipp.engine.SpeechModels

/**
 * The source clip's transcript, for picking sentences by text in the source monitor. The transcript belongs to the
 * media item, so it is the same one the timeline's text-based editing uses (one transcript, two views).
 */
interface SourceText {
    /** Changes when a transcript changed (the view re-reads the words). */
    val revision: Int
    /** The clip's words in media seconds (empty = not transcribed). */
    suspend fun words(): List<SpeechLine>
    /** Listen to the clip with the chosen model (the model must be downloaded). */
    fun transcribe(context: android.content.Context, pick: SpeechPick)
}

/**
 * Sentences as word index ranges: a sentence ends at a word ending in . ? ! … (closing quotes allowed) or before a
 * pause of at least [PAUSE_SPLIT] seconds (speech without punctuation still gets pickable pieces).
 */
fun sentences(words: List<SpeechLine>): List<IntRange> {
    val out = ArrayList<IntRange>()
    var from = 0
    for (k in words.indices) {
        val t = words[k].text.trimEnd('"', '\'', '”', '’', ')', '»')
        val ends = t.endsWith('.') || t.endsWith('?') || t.endsWith('!') || t.endsWith('…')
        val pause = k + 1 < words.size && words[k + 1].start - words[k].end >= PAUSE_SPLIT
        if (ends || pause || k == words.lastIndex) { out += from..k; from = k + 1 }
    }
    return out
}

/**
 * In and out marks (source seconds; out = the last frame's start, as the scrub bar's marks) for the words [first]..[last]:
 * a little air around the speech, never into the neighboring words, within the clip.
 */
fun sentenceMarks(words: List<SpeechLine>, first: Int, last: Int, duration: Double, frame: Double): Pair<Double, Double> {
    val a = words[first]
    val b = words[last]
    val prevEnd = words.getOrNull(first - 1)?.end ?: 0.0
    val nextStart = words.getOrNull(last + 1)?.start ?: duration
    val inT = maxOf(a.start - LEAD_IN, prevEnd, 0.0).coerceAtMost(a.start)
    val outEnd = minOf(b.end + TAIL_OUT, nextStart, duration).coerceAtLeast(b.end.coerceAtMost(duration))
    return inT to (outEnd - frame).coerceAtLeast(inT)
}

private const val PAUSE_SPLIT = 1.2
private const val LEAD_IN = 0.12
private const val TAIL_OUT = 0.2

/**
 * The text view in the source monitor: the clip's sentences with their times. Tap a sentence = it is marked (in/out)
 * and the source jumps there; tap another = the marks stretch to it; tap inside the marked part = clear. Without a
 * transcript: language and quality, then listen.
 */
@Composable
fun SourceTextView(
    item: Long,
    duration: Double,
    frame: Double,
    text: SourceText,
    pos: Double,
    modifier: Modifier,
    /** The marked sentences (indices into the sentence list), kept by the panel so it can clear them after an edit. */
    selection: IntRange?,
    onSelect: (IntRange?) -> Unit,
    onMarks: (Double, Double) -> Unit,
    onSeek: (Double) -> Unit,
) {
    val haptics = rememberHaptics()
    val ctx = LocalContext.current
    val ac = AutoCaptions
    var words by remember(item) { mutableStateOf<List<SpeechLine>?>(null) }
    LaunchedEffect(item, text.revision) { words = text.words() }
    val ws = words
    val parts = remember(ws) { ws?.let { sentences(it) }.orEmpty() }
    var redo by remember(item) { mutableStateOf(false) }
    Column(modifier.background(K.Deep).testTag("sourceText")) {
        val busy = ac.progress
        when {
            busy != null && ac.item == item -> Column(Modifier.padding(16.dp)) {
                Text(ac.phase, color = K.OnSurface, fontSize = 13.sp)
                androidx.compose.material3.LinearProgressIndicator(progress = { busy }, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = K.Primary)
                PillButton(stringResource(R.string.titleui_cancel), { ac.cancel() }, icon = Ic.Close, filled = false)
            }
            ws == null -> {}
            ws.isEmpty() || redo -> Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                Text(stringResource(if (redo) R.string.transcript_redo_hint else R.string.source_text_empty), color = K.OnSurfaceVar, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                // the last run's result ("No speech found" …) right under the hint, where it is seen
                ac.message?.let { Text(it, color = K.Primary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("src:message")) }
                val pick = rememberSpeechPick()
                SpeechPicker(pick, "src")
                val model = pick.model
                val ready = remember(model, ac.modelRevision, ac.running) { SpeechModels.ready(ctx, model) }
                when {
                    // the media panel's job and the timeline's share the one recognizer
                    ac.running -> Text(if (busy != null) stringResource(R.string.source_transcribing_other) else ac.phase, color = K.OnSurfaceVar, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    !ready -> PillButton(stringResource(R.string.titleui_download_model, model.bytes / 1_000_000), { haptics.confirm(); ac.download(ctx, model) },
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("src:download"), icon = Ic.Download, filled = false)
                    else -> PillButton(stringResource(R.string.transcript_transcribe), { haptics.confirm(); redo = false; onSelect(null); text.transcribe(ctx, pick) },
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag("src:transcribe"), icon = Ic.Transcript)
                }
                if (redo) ToggleChip(stringResource(R.string.titleui_cancel), false, { redo = false }, Modifier.padding(horizontal = 14.dp))
            }
            else -> {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val sel = selection
                    Text(
                        if (sel == null) stringResource(R.string.source_text_hint)
                        else stringResource(R.string.source_text_selected, durText(ws[parts[sel.last].last].end - ws[parts[sel.first].first].start)),
                        color = if (sel == null) K.Outline else K.Primary, fontSize = 11.sp, modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton({ haptics.tick(); redo = true }, Modifier.testTag("src:redo")) {
                        Text(stringResource(R.string.transcript_redo), color = K.OnSurfaceVar, fontSize = 12.sp)
                    }
                }
                val list = rememberLazyListState()
                LazyColumn(Modifier.fillMaxWidth(), state = list) {
                    itemsIndexed(parts, key = { _, r -> r.first }) { si, r ->
                        val a = ws[r.first]; val b = ws[r.last]
                        val marked = selection != null && si in selection
                        val playing = pos >= a.start && pos < b.end
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (marked) K.Primary.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    haptics.tick()
                                    val sel = selection
                                    // tap = mark this sentence; another = stretch the marks to it; inside the marks = clear
                                    val next = when {
                                        sel != null && si in sel -> null
                                        sel == null -> si..si
                                        else -> minOf(sel.first, si)..maxOf(sel.last, si)
                                    }
                                    onSelect(next)
                                    if (next != null) {
                                        val (i, o) = sentenceMarks(ws, parts[next.first].first, parts[next.last].last, duration, frame)
                                        onMarks(i, o)
                                        onSeek(i)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("src:s$si"),
                        ) {
                            Text(formatTc(a.start.toFloat()).substring(3, 8), color = if (marked) K.Primary else K.Outline, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp).padding(top = 3.dp))
                            Text(
                                (r.first..r.last).joinToString(" ") { ws[it].text }, fontSize = 15.sp, lineHeight = 21.sp,
                                color = if (marked || playing) K.OnSurface else K.OnSurfaceVar,
                                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
