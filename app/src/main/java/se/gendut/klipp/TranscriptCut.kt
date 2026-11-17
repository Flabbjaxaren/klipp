package se.gendut.klipp

import kotlin.math.ceil
import kotlin.math.floor

/**
 * What deleting a range of transcript words does, said before it is done – the same rule as the engine's
 * transcript.extract (FilmCraft edit::extract): the words' range, widened to whole frames, is cut out of every track that
 * is targeted or sync-locked and not locked, and what comes after on those tracks moves earlier by its length. Other
 * tracks are left as they are. Captions in the range keep their text (the engine shortens a sync-locked caption track's
 * captions, it never edits their words).
 */
data class TranscriptCut(
    /** The range cut out (timeline seconds, whole frames). */
    val start: Float,
    val end: Float,
    /** The tracks cut (V1, A1 …) and the clips on them that the range touches. */
    val tracks: List<String>,
    val clips: Int,
    /** Something after the range on the cut tracks: it moves earlier by [length]. */
    val laterMoves: Boolean,
    /** Tracks with clips that are left as they are (not targeted, not sync-locked, or locked): they may fall out of step. */
    val untouched: List<String>,
    /** Captions in the range: their text is not changed. */
    val captions: Int,
) {
    val length get() = end - start
}

fun transcriptCut(
    wordStart: Float, wordEnd: Float, frame: Float,
    clips: List<Clip>, trackStates: List<TrackState>, targetVideo: Int, targetAudio: Int, captions: List<UiCaption>,
): TranscriptCut {
    val start = floor(wordStart / frame + 1e-4f) * frame
    val end = ceil(wordEnd / frame - 1e-4f) * frame
    fun cut(i: Int): Boolean {
        val t = trackStates.getOrNull(i) ?: TrackState()
        return !t.locked && (i == targetVideo || i == targetAudio || t.syncLock)
    }
    val withClips = clips.map { it.track }.distinct().sorted()
    val cutTracks = TRACKS.indices.filter { cut(it) && it in withClips }
    val hit = clips.filter { it.track in cutTracks && it.start < end - 1e-4f && it.end > start + 1e-4f }
    val later = clips.any { it.track in cutTracks && it.end > end + 1e-4f }
    val untouched = withClips.filter { !cut(it) }.mapNotNull { TRACKS.getOrNull(it)?.name }
    val caps = captions.count { !it.hidden && it.start < end - 1e-4f && it.end > start + 1e-4f }
    return TranscriptCut(start, end, cutTracks.map { TRACKS[it].name }, hit.size, later, untouched, caps)
}
