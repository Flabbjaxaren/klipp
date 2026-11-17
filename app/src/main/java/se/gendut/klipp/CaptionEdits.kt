package se.gendut.klipp

import kotlin.math.abs

/** A caption track as the captions overview and the caption editor name it. */
data class CaptionTrackUi(val id: Long, val name: String, val count: Int, val enabled: Boolean)

/** Why an edge of a caption can't go to the playhead (the same rules as dragging it in the timeline). */
enum class CaptionEdgeProblem {
    /** The playhead is where the edge already is. */
    ALREADY_THERE,
    /** Set start: the playhead is at or after the caption's end. */
    AFTER_END,
    /** Set end: the playhead is at or before the caption's start. */
    BEFORE_START,
    /** The caption would be shorter than a frame. */
    TOO_SHORT,
    /** The start would go into the caption before it on the same track. */
    INTO_PREVIOUS,
    /** The end would go into the caption after it on the same track. */
    INTO_NEXT,
}

/** The move of an edge ([dt] seconds), or why it can't be made. */
data class CaptionEdgeMove(val dt: Float, val problem: CaptionEdgeProblem?) {
    val ok get() = problem == null
}

/**
 * "Set start/end at the playhead": the edge of [c] moved to [at] – checked against the rules the timeline's drag has
 * (at least [minLen] long, never into a neighbor on the same track, not before 0). The move is made with the same
 * engine command as the drag (captions.trim), so it is one undo step.
 */
fun captionEdgeTo(c: UiCaption, all: List<UiCaption>, at: Float, inEdge: Boolean, minLen: Float): CaptionEdgeMove {
    val same = all.filter { it.track == c.track && it.id != c.id }
    val tol = minLen / 2
    return if (inEdge) {
        val dt = at - c.start
        val lo = same.filter { it.end <= c.start + 1e-3f }.maxOfOrNull { it.end } ?: 0f
        when {
            abs(dt) < tol -> CaptionEdgeMove(0f, CaptionEdgeProblem.ALREADY_THERE)
            at >= c.end - 1e-4f -> CaptionEdgeMove(dt, CaptionEdgeProblem.AFTER_END)
            c.end - at < minLen - 1e-4f -> CaptionEdgeMove(dt, CaptionEdgeProblem.TOO_SHORT)
            at < lo - 1e-4f -> CaptionEdgeMove(dt, CaptionEdgeProblem.INTO_PREVIOUS)
            else -> CaptionEdgeMove(dt, null)
        }
    } else {
        val dt = at - c.end
        val hi = same.filter { it.start >= c.end - 1e-3f }.minOfOrNull { it.start } ?: Float.MAX_VALUE
        when {
            abs(dt) < tol -> CaptionEdgeMove(0f, CaptionEdgeProblem.ALREADY_THERE)
            at <= c.start + 1e-4f -> CaptionEdgeMove(dt, CaptionEdgeProblem.BEFORE_START)
            at - c.start < minLen - 1e-4f -> CaptionEdgeMove(dt, CaptionEdgeProblem.TOO_SHORT)
            at > hi + 1e-4f -> CaptionEdgeMove(dt, CaptionEdgeProblem.INTO_NEXT)
            else -> CaptionEdgeMove(dt, null)
        }
    }
}

/**
 * The caption track a style change applies to: the only one, or the one the user chose ([chosen]). With several
 * tracks and none chosen: null – the user picks first (the style is the track's, never silently the first one's).
 */
fun captionStyleTrack(tracks: List<CaptionTrackUi>, chosen: Long?): Long? = when {
    tracks.size == 1 -> tracks.first().id
    chosen != null && tracks.any { it.id == chosen } -> chosen
    else -> null
}

/** Where a written line goes, or why it can't go in at the playhead. */
data class CaptionSlot(val start: Float, val length: Float, val problem: CaptionSlotProblem?) {
    val ok get() = problem == null
    val end get() = start + length
}

enum class CaptionSlotProblem {
    /** The playhead stands in a caption on that track. */
    INSIDE_CAPTION,
    /** The next caption on that track starts right away: no room for a line. */
    NO_ROOM,
}

/**
 * A new line at [at] on [track]: [length] long, shortened so it ends where the next caption on the track starts (the
 * engine won't let captions on a track overlap); at least [minLen] of room, else not – and why. [track] null = the
 * track the engine picks (the first visible).
 */
fun captionWriteSlot(captions: List<UiCaption>, track: Long?, at: Float, length: Float, minLen: Float): CaptionSlot {
    val same = captions.filter { !it.hidden && (track == null || it.track == track) }
    if (same.any { at >= it.start - 1e-4f && at < it.end - 1e-4f }) return CaptionSlot(at, 0f, CaptionSlotProblem.INSIDE_CAPTION)
    val next = same.filter { it.start >= at - 1e-4f }.minOfOrNull { it.start } ?: Float.MAX_VALUE
    val room = next - at
    if (room < minLen - 1e-4f) return CaptionSlot(at, 0f, CaptionSlotProblem.NO_ROOM)
    return CaptionSlot(at, minOf(length, room), null)
}
