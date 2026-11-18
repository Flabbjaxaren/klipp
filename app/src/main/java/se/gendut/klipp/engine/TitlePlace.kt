package se.gendut.klipp.engine

/** How many video tracks the timeline shows (V1–V3). A clip on a track above them can't be seen or selected. */
const val SHOWN_VIDEO_TRACKS = 3

/** A clip's frames on its track: [start] until [end] (not included). */
data class FrameSpan(val start: Long, val end: Long)

/** Why a new title has no place at the playhead. */
enum class TitlePlaceProblem {
    /** V3 (or a track above it) has a clip at the playhead: a title over it would go on a track the timeline doesn't show. */
    COVERED,
    /** The shown tracks above the picture are busy for the title's length. */
    NO_ROOM,
    /** The only free track above the picture is locked. */
    LOCKED,
}

/**
 * Where a new title goes, or why it can't. [track] is the engine's video track index (0 = V1); [create] means the
 * track doesn't exist yet and the engine makes it (the next one up, still shown).
 */
data class TitlePlace(val track: Int?, val create: Boolean, val problem: TitlePlaceProblem?) {
    val ok get() = problem == null
}

/**
 * A new title at frame [at], [len] frames long, on [tracks] (per engine video track V1, V2 …: its clips as frame ranges
 * start until end). As the engine places it – above the topmost clip at the playhead, on the lowest track free for its
 * whole length (never over a clip) – but only on the tracks the timeline shows ([shown]); without a place there, the
 * reason, and nothing is made. (Left to itself the engine made a new track, V4 or higher, that the timeline can't show.)
 */
fun titlePlace(tracks: List<List<FrameSpan>>, locked: List<Boolean>, at: Long, len: Long, shown: Int = SHOWN_VIDEO_TRACKS): TitlePlace {
    val n = len.coerceAtLeast(1)
    val top = tracks.indices.lastOrNull { i -> tracks[i].any { at >= it.start && at < it.end } }?.plus(1) ?: 0
    if (top >= shown) return TitlePlace(null, false, TitlePlaceProblem.COVERED)
    var lockedFree = false
    for (i in top until shown) {
        if (i >= tracks.size) return TitlePlace(i, true, null)
        if (tracks[i].any { it.start < at + n && at < it.end }) continue
        if (locked.getOrElse(i) { false }) { lockedFree = true; continue }
        return TitlePlace(i, false, null)
    }
    return TitlePlace(null, false, if (lockedFree) TitlePlaceProblem.LOCKED else TitlePlaceProblem.NO_ROOM)
}
