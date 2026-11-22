package se.gendut.klipp.engine

/** A clip's frames on its track: [start] until [end] (not included). */
data class FrameSpan(val start: Long, val end: Long)

/** Why a new title has no place at the playhead. */
enum class TitlePlaceProblem {
    /** The top video track is busy for the title's length, and no more video tracks can be added. */
    NO_ROOM,
    /** The top video track is locked, and no more video tracks can be added. */
    LOCKED,
}

/**
 * Where a new title goes, or why it can't. [track] is the engine's video track index (0 = V1); [create] means the
 * track doesn't exist yet and is added on top first.
 */
data class TitlePlace(val track: Int?, val create: Boolean, val problem: TitlePlaceProblem?) {
    val ok get() = problem == null
}

/**
 * A new title at frame [at], [len] frames long, on [tracks] (per engine video track V1, V2 …: its clips as frame ranges
 * start until end). Titles always go on the topmost video track, so nothing covers them: there when it is free for the
 * title's whole length (never over a clip) and not locked – else on a new track added on top, while fewer than [max]
 * video tracks exist; at the limit, the reason, and nothing is made.
 */
fun titlePlace(tracks: List<List<FrameSpan>>, locked: List<Boolean>, at: Long, len: Long, max: Int): TitlePlace {
    val n = len.coerceAtLeast(1)
    val top = tracks.lastIndex
    if (top >= 0) {
        val free = tracks[top].none { it.start < at + n && at < it.end }
        val open = !locked.getOrElse(top) { false }
        if (free && open) return TitlePlace(top, false, null)
        if (tracks.size >= max) return TitlePlace(null, false, if (free) TitlePlaceProblem.LOCKED else TitlePlaceProblem.NO_ROOM)
    }
    return TitlePlace(tracks.size, true, null)
}
