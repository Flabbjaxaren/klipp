package se.gendut.klipp

import kotlin.math.abs

/*
 * The magnetic timeline in the editor: the timeline's clips as the magnetic model ([MagTimeline]), its edits as one
 * undo step each (the engine's, or the local timeline's in tests and the demo), and how a plan becomes engine edits.
 */

/** Edges closer than this (s) are the same edge. */
private const val EPS = 1e-3f

/** [this] clip in the magnetic model (rows as [tracks]). */
fun Clip.toMClip(tracks: List<TrackDef>): MClip = MClip(
    id = id,
    video = tracks.getOrNull(track)?.kind?.let { it == TrackKind.VIDEO } ?: (kind.track == TrackKind.VIDEO),
    level = tracks.engineIndex(track).coerceAtLeast(0),
    start = start,
    len = length,
    link = link,
    // a freeze frame, a title or an adjustment shows the same thing whatever is cut off its start
    follows = !hold && (kind == ClipKind.VIDEO || kind == ClipKind.AUDIO || kind == ClipKind.MUSIC),
)

/**
 * The timeline's clips (rows as [tracks]) as the magnetic model; transitions between clips join connected clips, and
 * the captions hang on the storyline as connected clips do.
 */
fun magTimelineOf(clips: List<Clip>, tracks: List<TrackDef>, transitions: List<UiTransition>, side: MagSide, captions: List<UiCaption> = emptyList()): MagTimeline =
    MagTimeline(
        clips.map { it.toMClip(tracks) },
        transitions.mapNotNull { t -> if (t.from != null && t.to != null) t.from to t.to else null },
        side, tracks.videoCount, tracks.audioCount,
        captions = captions.map { MCaption(it.id, it.start, it.end) },
    )

/** The row of lane ([video], [level]) among [rows]. */
fun List<TrackDef>.rowOf(video: Boolean, level: Int): Int = if (video) videoRow(level) else audioRow(level)

/**
 * The clips after [plan] on the local timeline (no engine: tests, the demo), on [rows] (with as many tracks as the plan
 * needs). Pieces get new ids from [newId]; a cut off start moves the source in point.
 */
fun applyMagPlan(clips: List<Clip>, plan: MPlan, rows: List<TrackDef>, newId: () -> Long): List<Clip> {
    val byId = clips.associateBy { it.id }
    val out = ArrayList<Clip>()
    for (f in plan.clips) {
        val piece = plan.pieces.firstOrNull { it.id == f.id }
        val src = byId[piece?.from ?: f.id] ?: continue
        val inAt = if (piece != null) src.srcIn + (piece.at - src.start) * src.speed else src.srcIn
        out += src.copy(
            id = if (piece != null) newId() else src.id,
            track = rows.rowOf(f.video, f.level),
            start = f.start,
            length = f.len,
            srcIn = if (src.hold) inAt else inAt + f.head * src.speed,
        )
    }
    return out
}

/** An edge trim: the in edge moves [delta] s later (positive = shorter), the out edge [delta] s later (positive = longer). */
data class MagTrim(val id: Long, val inEdge: Boolean, val delta: Float)

/** A clip to its new lane and start. */
data class MagMove(val id: Long, val video: Boolean, val level: Int, val start: Float)

/** A plan as engine edits, in this order: the edges that get shorter, one move of everything, the edges that get longer. */
data class MagSteps(val shrink: List<MagTrim>, val moves: List<MagMove>, val extend: List<MagTrim>)

/**
 * How the engine gets from [now] (the clips once the plan's cuts and deletions are made) to [plan]: shorter edges first
 * (in place), then every clip that changes place in one move (they are lifted off together, so they never land on each
 * other), then longer edges – each clip moved to where its longer edge has room. [ids]: the engine's ids of the pieces.
 */
fun magSteps(plan: MPlan, now: Map<Long, MClip>, ids: Map<Long, Long> = emptyMap()): MagSteps {
    val shrink = ArrayList<MagTrim>()
    val moves = ArrayList<MagMove>()
    val extend = ArrayList<MagTrim>()
    for (f in plan.clips) {
        val id = ids[f.id] ?: f.id
        val cur = now[id] ?: continue
        val headCut = f.head
        val tailCut = cur.len - f.head - f.len
        if (headCut > EPS) shrink += MagTrim(id, true, headCut)
        if (tailCut > EPS) shrink += MagTrim(id, false, -tailCut)
        val pos = cur.start + headCut.coerceAtLeast(0f)
        val target = f.start + (-headCut).coerceAtLeast(0f)
        if (abs(target - pos) > EPS || cur.video != f.video || cur.level != f.level) moves += MagMove(id, f.video, f.level, target)
        if (headCut < -EPS) extend += MagTrim(id, true, headCut)
        if (tailCut < -EPS) extend += MagTrim(id, false, -tailCut)
    }
    return MagSteps(shrink, moves, extend)
}

// ------------------------------------------------------------------ the editor's magnetic edits

/** The timeline as the magnetic model (clips being deleted left out). */
fun EditorState.mag(): MagTimeline = magTimelineOf(clips.filter { it.id !in removing }, tracks, transitions, magSide, captions)

/**
 * A magnetic edit as one undo step. With the engine, [plan] is made again from the engine's timeline (the same clips
 * the UI shows); locally the plan is applied at once. False if nothing changes or a locked track is in the way.
 */
fun EditorState.magEdit(label: String, plan: (MagTimeline) -> MPlan?): Boolean {
    val mt = mag()
    val p = plan(mt) ?: return false
    // a locked track: nothing on it may move, change or go
    val before = mt.clips.associateBy { it.id }
    val touched = p.removed.mapNotNull { before[it] } + p.clips.mapNotNull { f ->
        val b = before[f.id]
        when {
            b == null -> null
            abs(b.start - f.start) > EPS || abs(b.len - f.len) > EPS || b.level != f.level || b.video != f.video || abs(f.head) > EPS -> b
            else -> null
        }
    } + p.pieces.mapNotNull { before[it.from] }
    val lockedLanes = touched.map { tracks.rowOf(it.video, it.level) } + p.clips.filter { f -> before[f.id]?.let { it.level != f.level } == true }
        .mapNotNull { f -> if (f.level < (if (f.video) tracks.videoCount else tracks.audioCount)) tracks.rowOf(f.video, f.level) else null }
    if (lockedLanes.any { locked(it) }) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return false }
    if (p.stuck > 0) onNotice?.invoke(Str.t(R.string.magnetic_no_free_track))
    backend?.let { it.magnetic(label) { m -> plan(m) }; return true }
    val rows = if (p.videoLanes > tracks.videoCount || p.audioLanes > tracks.audioCount)
        trackRows(maxOf(p.videoLanes, tracks.videoCount), maxOf(p.audioLanes, tracks.audioCount)) else tracks
    val next = applyMagPlan(clips.filter { it.id !in removing }, p, rows, ::newId)
    if (rows != tracks) setTrackRows(rows)
    commit(next)
    // (the local timeline's captions: moved with what they hang on – not part of its undo, as there)
    if (p.captionShift.isNotEmpty() || p.captionsRemoved.isNotEmpty()) captions = captions.filter { it.id !in p.captionsRemoved }
        .map { c -> p.captionShift[c.id]?.let { d -> c.copy(start = c.start + d, end = c.end + d) } ?: c }
    return true
}

/** Drag a clip to [start] on row [track]: magnetic, or – [position] mode – overwriting and leaving a gap clip. */
fun EditorState.magMove(orig: Clip, start: Float, track: Int, position: Boolean): Boolean {
    if (locked(orig.track) || locked(track)) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return false }
    val level = tracks.engineIndex(track)
    val keep = keepConnected
    return magEdit(Str.t(R.string.projectcontroller_undo_move)) { m ->
        if (position && m.isPrimary(orig.id) && level == orig.toMClip(tracks).level) m.positionMove(orig.id, start, keep)
        else m.move(orig.id, start, level, keep, alone = !linkedSelection)
    }
}

/** The selected clips dragged together by [dt]. */
fun EditorState.magMoveBlock(ids: Set<Long>, dt: Float): Boolean =
    magEdit(Str.t(R.string.projectcontroller_undo_move)) { it.moveBlock(ids, dt, keepConnected) }

/** Delete clips: storyline clips close up and take their connected clips; [ripple] false = they become gap clips. */
fun EditorState.magDelete(ids: Set<Long>, ripple: Boolean): Boolean =
    magEdit(Str.t(R.string.magnetic_undo_delete)) { if (ripple) it.delete(ids) else it.replaceWithGap(ids) }

/** Trim clip [id]'s edge by [delta] (see [MagTimeline.trim]): storyline clips ripple. */
fun EditorState.magTrim(id: Long, inEdge: Boolean, delta: Float): Boolean {
    val c = clips.firstOrNull { it.id == id } ?: return false
    if (locked(c.track)) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return false }
    val keep = keepConnected
    return magEdit(Str.t(R.string.projectcontroller_undo_trim)) { it.trim(id, inEdge, delta, keep) }
}

/** The gap clip starting at [start] made [delta] s longer (negative = shorter). */
fun EditorState.magTrimGap(start: Float, delta: Float): Boolean {
    val keep = keepConnected
    return magEdit(Str.t(R.string.magnetic_undo_gap)) { it.trimGap(start, delta, keep) }.also { selectedGap = null }
}

/** Delete the gap clip starting at [start] (what follows closes up; its connected clips go with it). */
fun EditorState.magDeleteGap(start: Float): Boolean =
    magEdit(Str.t(R.string.magnetic_undo_gap)) { it.delete(emptySet(), listOf(start)) }.also { selectedGap = null }

/** Lift from storyline: the clip becomes a connected clip where it is, a gap clip takes its place. */
fun EditorState.liftFromStoryline(id: Long): Boolean = magEdit(Str.t(R.string.magnetic_lift)) { it.liftFromStoryline(id) }

/** Overwrite to primary: the connected clip goes down into the storyline where it is. */
fun EditorState.overwriteToPrimary(id: Long): Boolean = magEdit(Str.t(R.string.magnetic_overwrite_primary)) { it.overwriteToPrimary(id) }

/** The side state as an undo step of the shared history (saved with the project). */
private fun EditorState.setSide(label: String, next: MagSide) {
    val before = magSide
    if (next == before) return
    magSide = next
    props?.requestSave()
    recordSide(label, { magSide = before; props?.requestSave() }, { magSide = next; props?.requestSave() })
}

/** Disconnect (or reconnect) connected clips: a disconnected clip stays where it is when the storyline changes. */
fun EditorState.setDisconnected(ids: Collection<Long>, disconnected: Boolean) {
    val all = withPartners(ids)
    val s = magSide
    setSide(Str.t(if (disconnected) R.string.magnetic_disconnect else R.string.magnetic_reconnect),
        s.copy(disconnected = if (disconnected) s.disconnected + all else s.disconnected - all))
}

/** The clip's connection point at timeline time [at] (within the clip); outside it = back to its first frame. */
fun EditorState.setConnectionPoint(id: Long, at: Float) {
    val c = clips.firstOrNull { it.id == id } ?: return
    val off = at - c.start
    val s = magSide
    val points = if (off > EPS && off < c.length - EPS) s.points + (id to off) else s.points - id
    setSide(Str.t(R.string.magnetic_connection_point), s.copy(points = points))
}

/** The clip is a connected clip (in the magnetic timeline): not on V1 and not linked to a V1 clip. */
fun EditorState.isConnected(id: Long): Boolean = magnetic && clips.any { it.id == id } && !mag().isPrimary(id)
