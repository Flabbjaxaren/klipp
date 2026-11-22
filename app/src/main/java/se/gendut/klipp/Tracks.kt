package se.gendut.klipp

/**
 * The sequence's tracks as the timeline shows them. Rows run from the top: the video tracks highest first (V_n … V1),
 * then the audio tracks (A1 … A_m). A clip's [Clip.track] is its row; the engine numbers each kind from V1 / A1 (its
 * "engine index", 0 = V1). The rows follow the sequence's actual tracks (any number; new projects have 3 + 3), and the
 * user can add tracks up to [MAX_VIDEO_TRACKS] / [MAX_AUDIO_TRACKS] and delete empty ones.
 */
const val DEFAULT_VIDEO_TRACKS = 3
const val DEFAULT_AUDIO_TRACKS = 3
/** At most this many video tracks can be added (V1–V6). A sequence made elsewhere may have more: they are shown. */
const val MAX_VIDEO_TRACKS = 6
/** At most this many audio tracks can be added (A1–A10). */
const val MAX_AUDIO_TRACKS = 10

enum class TrackKind { VIDEO, AUDIO }

/**
 * A row in the timeline: its name (V1, A2 … by place), kind, normal height and the engine's track [id] (stand-ins below 0
 * without an engine: the prototype and tests).
 */
data class TrackDef(val name: String, val kind: TrackKind, val heightDp: Int, val id: Long = 0)

/**
 * The rows for [video] video and [audio] audio tracks. [videoIds] / [audioIds]: the engine's track ids in its order
 * (V1 first); missing ones get stand-ins (−100 − n for V(n+1), −200 − n for A(n+1)) so rows can be told apart.
 */
fun trackRows(video: Int, audio: Int, videoIds: List<Long> = emptyList(), audioIds: List<Long> = emptyList()): List<TrackDef> {
    val v = video.coerceAtLeast(1)
    val a = audio.coerceAtLeast(1)
    return List(v) { r ->
        val k = v - 1 - r
        TrackDef("V${k + 1}", TrackKind.VIDEO, if (k == 0) 52 else 40, videoIds.getOrNull(k) ?: (-100L - k))
    } + List(a) { k -> TrackDef("A${k + 1}", TrackKind.AUDIO, 44, audioIds.getOrNull(k) ?: (-200L - k)) }
}

/** A new project's rows (and the prototype's): V3 V2 V1 A1 A2 A3. */
val DEFAULT_TRACKS: List<TrackDef> = trackRows(DEFAULT_VIDEO_TRACKS, DEFAULT_AUDIO_TRACKS)

val List<TrackDef>.videoCount: Int get() = count { it.kind == TrackKind.VIDEO }
val List<TrackDef>.audioCount: Int get() = count { it.kind == TrackKind.AUDIO }
/** V1's row (the picture's base). */
val List<TrackDef>.v1Row: Int get() = videoCount - 1
/** A1's row (the dialogue). */
val List<TrackDef>.a1Row: Int get() = videoCount
/** The topmost video track's row (titles go there). */
val List<TrackDef>.topVideoRow: Int get() = 0
val List<TrackDef>.videoRows: IntRange get() = 0 until videoCount
val List<TrackDef>.audioRows: IntRange get() = videoCount until size
/** The row of the engine's video track [k] (0 = V1). */
fun List<TrackDef>.videoRow(k: Int): Int = videoCount - 1 - k
/** The row of the engine's audio track [k] (0 = A1). */
fun List<TrackDef>.audioRow(k: Int): Int = videoCount + k
/** Row [row]'s place among its kind as the engine counts it (0 = V1 / A1). */
fun List<TrackDef>.engineIndex(row: Int): Int = if (getOrNull(row)?.kind == TrackKind.AUDIO) row - videoCount else videoCount - 1 - row
/** Another video track can be added. */
val List<TrackDef>.canAddVideo: Boolean get() = videoCount < MAX_VIDEO_TRACKS
/** Another audio track can be added. */
val List<TrackDef>.canAddAudio: Boolean get() = audioCount < MAX_AUDIO_TRACKS

/**
 * Track [row] can be deleted: no clips on it (nor a transition), and not the last of its kind (the engine keeps one).
 * [clips] are the timeline's clips (rows as [this]).
 */
fun List<TrackDef>.canDelete(row: Int, clips: List<Clip>, transitions: List<UiTransition> = emptyList()): Boolean {
    val t = getOrNull(row) ?: return false
    val ofKind = if (t.kind == TrackKind.VIDEO) videoCount else audioCount
    return ofKind > 1 && clips.none { it.track == row } && transitions.none { it.track == row }
}

/**
 * A row index from the rows [old] to the same track in [new] (by track id; null = the track is gone). The rows of tracks
 * below shift when one is added on top or deleted: what is kept per row (target tracks, opened tracks) follows its track.
 */
fun remapRow(row: Int, old: List<TrackDef>, new: List<TrackDef>): Int? {
    val id = old.getOrNull(row)?.id ?: return null
    return new.indexOfFirst { it.id == id }.takeIf { it >= 0 }
}
