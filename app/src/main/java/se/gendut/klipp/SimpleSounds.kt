package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Simple mode's two sound switches (the mockup's Sounds sheet), each an advanced-mode function on the film's own
 * sounds – the sound and music added to the film, not a video clip's own sound (see [SimpleStripModel.lanes]):
 *
 * - *Lower the sounds when someone speaks*: the audio panel's automatic ducking, on each track that holds only such
 *   sounds, against the speech on the others. Off takes the ducking away again.
 * - *Fade out at the end*: the sounds still playing when the film's picture ends end with it and fade out
 *   ([se.gendut.klipp.engine.ProjectController.fadeSoundsAtEnd]).
 */
object SimpleSounds {
    /** The video clips' own sound (linked to a video clip): not one of the film's added sounds. */
    private fun ownSound(state: EditorState): Set<Long> = state.clips.filter { it.kind.track == TrackKind.VIDEO }.mapNotNull { it.link }.toSet()

    private fun isAdded(c: Clip, own: Set<Long>) = c.kind.track == TrackKind.AUDIO && (c.link == null || c.link !in own)

    /** The audio tracks (the engine's index, 0 = A1) that hold added sounds only. */
    fun soundTracks(state: EditorState): List<Int> {
        val own = ownSound(state)
        return state.tracks.audioRows.mapNotNull { row ->
            val cs = state.clips.filter { it.track == row }
            if (cs.isNotEmpty() && cs.all { isAdded(it, own) }) state.tracks.engineIndex(row) else null
        }
    }

    /** Whether the sounds are lowered under speech now: a clip on [soundTracks] has a volume curve. */
    fun ducked(state: EditorState): Boolean {
        val rows = soundTracks(state).map { state.tracks.audioRow(it) }.toSet()
        return state.clips.any { it.track in rows && state.clipLevels[it.id]?.animated == true }
    }

    /** Where the film's picture ends (the last video clip's end; without video, the last clip's). */
    fun filmEnd(state: EditorState): Float =
        state.clips.filter { it.kind.track == TrackKind.VIDEO }.maxOfOrNull { it.end } ?: state.clips.maxOfOrNull { it.end } ?: 0f

    /** The added sounds still playing in the film's last second (or past its end): those that fade out at the end. */
    fun atEnd(state: EditorState): List<Clip> {
        val own = ownSound(state)
        val end = filmEnd(state)
        return state.clips.filter { isAdded(it, own) && it.start < end - 0.1f && it.end > end - 1f }
    }

    /** Whether every sound at the end fades out (an audio fade on its out edge, nothing after it). */
    fun fadedAtEnd(state: EditorState): Boolean {
        val cs = atEnd(state)
        return cs.isNotEmpty() && cs.all { c -> state.transitions.any { it.from == c.id && it.to == null } }
    }
}

/** The switches under the Sounds sheet's list ([SimpleSounds]); each change is one undo step and says how it went. */
@Composable
internal fun SimpleSoundSwitches(
    state: EditorState,
    duck: suspend (Int, Double) -> Result<Int>,
    unduck: suspend (Int) -> Result<Int>,
    fadeAtEnd: suspend (List<Long>, Double, Boolean) -> Result<Int>,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val haptics = rememberHaptics()
    var busy by remember { mutableStateOf(false) }
    val ducked = SimpleSounds.ducked(state)
    val faded = SimpleSounds.fadedAtEnd(state)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(K.SurfaceHigh).testTag("simple:soundSwitches")) {
        SwitchRow(stringResource(R.string.simple_duck), stringResource(R.string.simple_duck_hint), ducked, "simple:duck") { on ->
            if (busy) return@SwitchRow
            val tracks = SimpleSounds.soundTracks(state)
            if (tracks.isEmpty()) { toaster?.show(Str.t(R.string.simple_duck_no_sound)); return@SwitchRow }
            busy = true
            haptics.confirm()
            scope.launch {
                var n = 0
                var err: String? = null
                for (t in tracks) (if (on) duck(t, -12.0) else unduck(t)).onSuccess { n += it }.onFailure { err = it.message }
                busy = false
                toaster?.show(err ?: Str.t(if (on) R.string.simple_duck_done else R.string.simple_duck_off))
            }
        }
        SwitchRow(stringResource(R.string.simple_fade_end), stringResource(R.string.simple_fade_end_hint), faded, "simple:fadeEnd") { on ->
            if (busy) return@SwitchRow
            val cs = SimpleSounds.atEnd(state)
            if (cs.isEmpty()) { toaster?.show(Str.t(R.string.simple_fade_end_none)); return@SwitchRow }
            busy = true
            haptics.confirm()
            scope.launch {
                fadeAtEnd(cs.map { it.id }, SimpleSounds.filmEnd(state).toDouble(), on).onFailure { toaster?.show(it.message ?: "") }
                busy = false
            }
        }
    }
}
