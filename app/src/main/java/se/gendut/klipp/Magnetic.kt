package se.gendut.klipp

import kotlin.math.abs

/*
 * The magnetic timeline (Settings → Experimental; off by default): the timeline edits like one storyline instead of tracks.
 *
 * Klipp stays track-based underneath. V1 and the sound linked to its clips are the primary storyline: inserting, moving,
 * deleting and trimming there close gaps and push later clips on (clips never collide), and the empty stretches of V1
 * are gap clips that can be selected, trimmed and deleted like clips. Everything on the other tracks is a connected
 * clip: it hangs on the storyline clip under its connection point (its first frame, unless moved) and follows that
 * clip when it moves, ripples or is deleted. Connected clips never overwrite each other: a clip that would land on
 * another goes up (video) or down (audio) to the next free track. Position mode suspends the magnetism: a moved
 * storyline clip overwrites what is where it lands and leaves a gap clip where it was.
 *
 * The anchors are not stored: they are read from where the clips are before each edit (the storyline clip under each
 * connected clip's connection point), so a project edited with tracks opens as a magnetic one and turning the setting
 * off leaves every clip where it is. Only what the timeline cannot show is kept with the project: clips disconnected
 * on purpose and moved connection points (see [MagSide]).
 *
 * This file is the pure model: an edit takes the timeline ([MagTimeline]) and returns the whole timeline after it
 * ([MPlan]); the engine (ProjectController) or the local timeline (EditorState) carries the plan out as one undo step.
 */

/** Edges closer than this (s) are the same edge. */
private const val EPS = 1e-3f

/**
 * A clip as the magnetic model sees it. Lanes count out from the middle: [level] 0 = V1 / A1, 1 = V2 / A2 …
 * [head]: timeline seconds the edit has cut off the clip's start (negative = extended) – the engine trims the clip's in
 * point by it. [follows]: the picture runs with the timeline (not a freeze frame, a title or an adjustment), so a cut
 * off head moves what is shown (and the clips connected to it).
 */
data class MClip(
    val id: Long,
    val video: Boolean,
    val level: Int,
    val start: Float,
    val len: Float,
    val link: Long? = null,
    val follows: Boolean = true,
    val head: Float = 0f,
) {
    val end: Float get() = start + len
}

/** A clip cut in two by the edit: [id] (negative, until the engine names it) is the part of clip [from] from time [at] (as it was before the edit) on. */
data class MPiece(val id: Long, val from: Long, val at: Float)

/**
 * The timeline after a magnetic edit: every clip that remains (pieces cut by the edit included, under their temporary
 * ids), the clips that are gone, and how many tracks of each kind it needs. [stuck]: clips that found no free track
 * (more connected clips over each other than there are tracks) and stay on theirs.
 */
data class MPlan(
    val clips: List<MClip>,
    val removed: Set<Long>,
    val pieces: List<MPiece> = emptyList(),
    val videoLanes: Int,
    val audioLanes: Int,
    val stuck: Int = 0,
    /** Captions that move with what they hang on: their id → how far (s). */
    val captionShift: Map<Long, Float> = emptyMap(),
    /** Captions that go with the clip they hang on. */
    val captionsRemoved: Set<Long> = emptySet(),
) {
    fun clip(id: Long): MClip? = clips.firstOrNull { it.id == id }
}

/** A caption: it hangs on the storyline like a connected clip (by its start), so it stays on the words it belongs to. */
data class MCaption(val id: Long, val start: Float, val end: Float)

/** A gap clip: an empty stretch of the primary storyline (also after its end, under connected clips that hang there). */
data class MGap(val start: Float, val end: Float) {
    val len: Float get() = end - start
}

/** Where a connected clip hangs: its connection point [at] on the storyline clip [parent] (null = on a gap clip). */
data class MConnection(val clip: Long, val at: Float, val parent: Long?)

/** What is stored with the project for the magnetic timeline: disconnected clips and moved connection points (s into the clip). */
data class MagSide(val disconnected: Set<Long> = emptySet(), val points: Map<Long, Float> = emptyMap()) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("disconnected", org.json.JSONArray().apply { disconnected.sorted().forEach { put(it) } })
        .put("points", org.json.JSONObject().apply { points.toSortedMap().forEach { (k, v) -> put(k.toString(), v.toDouble()) } })

    /** Only what still exists among [ids] (deleted clips' entries are dropped). */
    fun keepOnly(ids: Set<Long>) = MagSide(disconnected.filterTo(HashSet()) { it in ids }, points.filterKeys { it in ids })

    companion object {
        fun fromJson(o: org.json.JSONObject?): MagSide {
            if (o == null) return MagSide()
            val d = o.optJSONArray("disconnected")
            val dis = HashSet<Long>()
            if (d != null) for (i in 0 until d.length()) d.optLong(i, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { dis += it }
            val p = o.optJSONObject("points")
            val pts = HashMap<Long, Float>()
            if (p != null) for (k in p.keys()) k.toLongOrNull()?.let { id -> pts[id] = p.optDouble(k, 0.0).toFloat() }
            return MagSide(dis, pts)
        }
    }
}

/**
 * The timeline for magnetic edits. [transitions]: (from, to) clip pairs of the transitions between clips – connected
 * clips joined by one move together (a small connected storyline). [videoLanes] / [audioLanes]: the tracks there are;
 * an edit may need more, up to [maxVideo] / [maxAudio].
 */
class MagTimeline(
    clips: List<MClip>,
    val transitions: List<Pair<Long, Long>> = emptyList(),
    val side: MagSide = MagSide(),
    val videoLanes: Int = DEFAULT_VIDEO_TRACKS,
    val audioLanes: Int = DEFAULT_AUDIO_TRACKS,
    val maxVideo: Int = MAX_VIDEO_TRACKS,
    val maxAudio: Int = MAX_AUDIO_TRACKS,
    val captions: List<MCaption> = emptyList(),
) {
    val clips: List<MClip> = clips.sortedWith(compareBy({ it.start }, { it.id }))
    private val byId = this.clips.associateBy { it.id }

    /** The clips of each link group (picture and sound from one source). */
    private val byLink: Map<Long, List<MClip>> = this.clips.filter { it.link != null }.groupBy { it.link!! }

    fun clip(id: Long): MClip? = byId[id]

    /** The clips linked to V1 clip [v] (its sound, on any track). */
    private fun partnersOf(v: MClip): List<MClip> = v.link?.let { byLink[it] }.orEmpty().filter { it.id != v.id && !(it.video && it.level == 0) }

    /** The clips of the primary storyline: V1's and the sound (any track) linked to them. */
    val primary: Set<Long> by lazy {
        val out = HashSet<Long>()
        for (v in this.clips) if (v.video && v.level == 0) {
            out += v.id
            partnersOf(v).forEach { out += it.id }
        }
        out
    }

    fun isPrimary(id: Long) = id in primary

    /** The storyline clip [id] belongs to (its V1 clip), or null for a connected clip. */
    fun leadOf(id: Long): Long? {
        val c = byId[id] ?: return null
        if (c.video && c.level == 0) return c.id
        if (id !in primary) return null
        return clips.firstOrNull { it.video && it.level == 0 && it.link == c.link }?.id
    }

    /** The gap clips (for drawing and selecting). */
    fun gaps(): List<MGap> = Work().els.filter { it.isGap && it.len > EPS }.map { MGap(it.start, it.start + it.len) }

    /** The gap clip at time [t] (in the storyline's lane), or null. */
    fun gapAt(t: Float): MGap? = gaps().firstOrNull { t >= it.start - EPS && t < it.end - EPS }

    /** Where each connected clip (each connected group's first clip) hangs on the storyline; disconnected ones are left out. */
    fun connections(): List<MConnection> = Work().units.filter { !it.fixed && !it.caption && it.anchor != null }.map { u ->
        MConnection(u.lead.id, u.cp, u.anchor!!.lead?.id)
    }

    /** The end of the storyline (its last clip or the gap clip under connected clips past it). */
    val storyEnd: Float get() = Work().let { w -> w.els.lastOrNull()?.let { it.start + it.len } ?: 0f }

    // ------------------------------------------------------------------ edits

    /**
     * Delete: storyline clips (their ids, or a sound linked to them deletes just that sound) and gap clips (their starts)
     * close up – later clips move back – and take their connected clips with them; deleted connected clips just go.
     * [keep]: the connected clips of the deleted ones stay where they are.
     */
    fun delete(ids: Set<Long>, gaps: Collection<Float> = emptyList(), keep: Boolean = false): MPlan? {
        val w = Work()
        var any = false
        for (e in w.els.toList()) {
            val lead = e.lead
            if ((lead != null && lead.id in ids) || (lead == null && gaps.any { abs(it - e.start) < EPS })) {
                w.removeEl(e, keepUnits = keep)
                any = true
            } else if (lead != null) {
                for (m in e.members.toList()) if (m.clip.id != lead.id && m.clip.id in ids) { e.members.remove(m); w.removed += m.clip.id; any = true }
            }
        }
        for (u in w.units) if (!u.caption) for (c in u.clips.toList()) if (c.id in ids) { u.clips.remove(c); w.removed += c.id; any = true }
        return if (any) w.finish() else null
    }

    /**
     * Replace with gap: storyline clips become gap clips of their length – nothing moves, connected clips stay. Connected
     * clips in [ids] are deleted, and so are sounds in [ids] linked to a storyline clip that stays (a file's second audio
     * stream, say): the picture keeps its place.
     */
    fun replaceWithGap(ids: Set<Long>): MPlan? {
        val w = Work()
        var any = false
        for (e in w.els) {
            val lead = e.lead ?: continue
            if (lead.id !in ids) {
                for (m in e.members.toList()) if (m.clip.id in ids) { e.members.remove(m); w.removed += m.clip.id; any = true }
                continue
            }
            for (m in e.members) w.removed += m.clip.id
            e.members.clear(); e.lead = null; e.shift = 0f
            any = true
        }
        for (u in w.units) if (!u.caption) for (c in u.clips.toList()) if (c.id in ids) { u.clips.remove(c); w.removed += c.id; any = true }
        return if (any) w.finish() else null
    }

    /**
     * Drag clip [id] so that it starts at [toStart] on lane [toLevel] (of its own kind). Magnetic: a storyline clip
     * moves among the others (the gap it leaves closes, the clips where it lands make room at the nearest edit point);
     * moved off V1 it becomes a connected clip; a connected clip moved onto V1 is inserted into the storyline; a
     * connected clip moved elsewhere stacks onto a free track if its own is taken. A storyline clip's connected clips
     * go with it, unless [keep]. [alone]: linked selection is off – a sound linked to a storyline clip is moved on its
     * own (it becomes a connected clip).
     */
    fun move(id: Long, toStart: Float, toLevel: Int, keep: Boolean = false, alone: Boolean = false): MPlan? {
        val c = byId[id] ?: return null
        val w = Work()
        val e = w.elOf(id)
        if (e != null && !(alone && e.lead!!.id != id)) {
            val dl = toLevel - c.level
            if (dl == 0) {
                // reorder within the storyline: out, then back in at the edit point nearest to where its start was dropped
                val idx0 = w.els.indexOf(e)
                w.els.remove(e)
                w.layout()
                val rel = e.members.first { it.clip.id == id }.rel
                val idx = w.nearestBoundary(toStart - rel)
                if (idx == idx0) return null
                w.els.add(idx, e)
                if (keep) w.keepUnitsOf(e)
                return w.finish()
            }
            // out of the storyline: a connected clip (with its linked sound), where it was dropped
            val delta = toStart - c.start
            val idx = w.els.indexOf(e)
            w.els.removeAt(idx)
            val moved = e.members.map { m ->
                val mc = m.clip
                val lvl = if (mc.video) (mc.level + dl).coerceAtLeast(1) else (mc.level + dl).coerceAtLeast(0)
                mc.copy(level = lvl)
            }
            w.addUnit(moved, delta)
            // its connected clips go with it (or stay)
            for (u in w.units) if (u.anchor === e) { if (keep) u.keepInPlace = true else u.explicit = delta }
            return w.finish()
        }
        if (e != null) {
            // a linked sound moved on its own: out of its storyline clip, a connected clip
            val m = e.members.first { it.clip.id == id }
            e.members.remove(m)
            w.addUnit(listOf(c.copy(level = toLevel.coerceAtLeast(0))), toStart - c.start)
            return w.finish()
        }
        val u = w.unitOf(id) ?: return null
        if (c.video && toLevel == 0) {
            // into the storyline: inserted at the edit point nearest to where it was dropped
            val sub = u.clips.filter { it.id == id || (c.link != null && it.link == c.link) }
            u.clips.removeAll(sub)
            w.layout()
            val idx = w.nearestBoundary(toStart)
            val el = El(c.copy(level = 0), c.len)
            for (s in sub) {
                val lvl = if (s.video) 0 else (s.level - c.level).coerceAtLeast(0)
                el.members += Member(s.copy(level = lvl), s.start - c.start)
            }
            w.els.add(idx, el)
            return w.finish()
        }
        // a connected clip to another place: its whole group goes, the tracks as far out from the middle
        val dl = toLevel - c.level
        u.explicit = toStart - c.start
        if (dl != 0) u.clips.replaceAll { x -> x.copy(level = if (x.video) (x.level + dl).coerceAtLeast(1) else (x.level + dl).coerceAtLeast(0)) }
        u.wanted = true
        return w.finish()
    }

    /**
     * Several selected clips dragged together by [dt]: the storyline clips among them go as a block (in their order) to
     * the edit point nearest their new start; the connected ones move by [dt] – but one whose own storyline clip is in
     * the block goes with that clip, to where it lands (moved by [dt] it was left where the finger let go, beside the
     * clip it hangs on).
     */
    fun moveBlock(ids: Set<Long>, dt: Float, keep: Boolean = false): MPlan? {
        val w = Work()
        val block = w.els.filter { it.lead != null && it.lead!!.id in ids }
        val units = w.units.filter { u -> !u.caption && u.clips.any { it.id in ids } }
        if (block.isEmpty() && units.isEmpty()) return null
        for (u in units) if (keep || u.fixed || u.anchor == null || u.anchor !in block) { u.explicit = dt; u.wanted = true }
        if (block.isNotEmpty()) {
            val first = block.first().start
            w.els.removeAll(block)
            w.layout()
            val idx = w.nearestBoundary(first + dt)
            w.els.addAll(idx, block)
            if (keep) block.forEach { w.keepUnitsOf(it) }
        }
        return w.finish()
    }

    /**
     * Position mode: storyline clip [id] moved to [toStart] overwrites what is there (cut or removed) and leaves a gap clip
     * where it was; nothing else moves. Its connected clips go with it (unless [keep]); those of what it overwrites stay.
     * A connected clip moves as in [move].
     */
    fun positionMove(id: Long, toStart: Float, keep: Boolean = false): MPlan? {
        val w = Work()
        val e = w.elOf(id) ?: return move(id, toStart, byId[id]?.level ?: return null, keep)
        val lead = e.lead!!
        val rel = e.members.first { it.clip.id == id }.rel
        val at = (toStart - rel).coerceAtLeast(0f)
        if (abs(at - e.start) < EPS) return null
        val idx = w.els.indexOf(e)
        // a gap clip where it was (its connected clips stay with it, not with the gap)
        w.els[idx] = El(null, e.len)
        w.overwrite(e, at, preserveSound = false)
        if (keep) w.keepUnitsOf(e)
        return w.finish().takeIf { lead.id in it.clips.map { c -> c.id } }
    }

    /**
     * Trim clip [id]'s edge by [delta] (s; in edge: positive = cut off, out edge: positive = longer). A storyline clip
     * ripples: what follows moves on (its connected clips follow; those on the trimmed clip stay on their frame).
     * A sound linked to a storyline clip, or a connected clip, is trimmed alone (up to its neighbors on the track).
     */
    fun trim(id: Long, inEdge: Boolean, delta: Float, keep: Boolean = false): MPlan? {
        if (abs(delta) < EPS) return null
        val c = byId[id] ?: return null
        val w = Work()
        val e = w.elOf(id)
        if (e != null && e.lead!!.id == id) {
            val d = if (inEdge) delta.coerceAtMost(e.len - EPS) else delta.coerceAtLeast(-(e.len - EPS))
            if (inEdge) {
                e.len -= d
                for (m in e.members) m.clip = m.clip.copy(head = m.clip.head + d, len = m.clip.len - d)
                if (e.lead!!.follows) e.shift += d
            } else {
                e.len += d
                for (m in e.members) m.clip = m.clip.copy(len = m.clip.len + d)
            }
            e.members.removeAll { it.clip.len <= EPS }
            if (keep) w.keepUnitsAfter(e)
            return w.finish()
        }
        // alone: within its neighbors on the track
        val lane = clips.filter { it.video == c.video && it.level == c.level && it.id != id }
        if (e != null) {
            val m = e.members.first { it.clip.id == id }
            val d = clampAlone(c, lane, inEdge, delta)
            if (abs(d) < EPS) return null
            m.clip = if (inEdge) m.clip.copy(head = m.clip.head + d, len = m.clip.len - d) else m.clip.copy(len = m.clip.len + d)
            if (inEdge) m.rel += d
            return w.finish()
        }
        val u = w.unitOf(id) ?: return null
        val d = clampAlone(c, lane, inEdge, delta)
        if (abs(d) < EPS) return null
        u.clips.replaceAll { x -> if (x.id != id) x else if (inEdge) x.copy(start = x.start + d, head = x.head + d, len = x.len - d) else x.copy(len = x.len + d) }
        u.explicit = 0f
        return w.finish()
    }

    private fun clampAlone(c: MClip, lane: List<MClip>, inEdge: Boolean, delta: Float): Float =
        if (inEdge) {
            val lo = lane.filter { it.end <= c.start + EPS }.maxOfOrNull { it.end } ?: 0f
            delta.coerceIn(lo - c.start, c.len - EPS)
        } else {
            val hi = lane.filter { it.start >= c.end - EPS }.minOfOrNull { it.start } ?: Float.MAX_VALUE
            delta.coerceIn(-(c.len - EPS), hi - c.end)
        }

    /** The gap clip starting at [gapStart] made [delta] s longer (negative = shorter): what follows ripples. */
    fun trimGap(gapStart: Float, delta: Float, keep: Boolean = false): MPlan? {
        val w = Work()
        val g = w.els.firstOrNull { it.isGap && abs(it.start - gapStart) < EPS } ?: return null
        val d = delta.coerceAtLeast(-g.len)
        if (abs(d) < EPS) return null
        g.len += d
        if (keep) w.keepUnitsAfter(g)
        return w.finish()
    }

    /**
     * Insert: the storyline clips [ids] (new clips placed after everything, or clips already in the storyline) go into
     * the storyline at [at], in their order – a clip there is cut in two – and everything after moves on.
     */
    fun insert(ids: List<Long>, at: Float): MPlan? {
        val w = Work()
        val els = ids.mapNotNull { id -> w.els.firstOrNull { it.lead?.id == id } }
        if (els.isEmpty()) return null
        w.els.removeAll(els)
        w.layout()
        val idx = w.splitAt(at.coerceAtLeast(0f))
        w.els.addAll(idx, els)
        return w.finish()
    }

    /** Append: the storyline clips [ids] go to the end of the storyline (after the gap clip under connected clips past its end). */
    fun append(ids: List<Long>): MPlan? {
        val w = Work()
        val els = ids.mapNotNull { id -> w.els.firstOrNull { it.lead?.id == id } }
        if (els.isEmpty()) return null
        w.els.removeAll(els)
        w.layout()
        w.fitTrailingGap()
        w.els.addAll(els)
        return w.finish()
    }

    /**
     * Lift from storyline: storyline clip [id] becomes a connected clip at the same time (on the first free track
     * above, its sound below) and leaves a gap clip of its length; nothing moves.
     */
    fun liftFromStoryline(id: Long): MPlan? {
        val w = Work()
        val e = w.elOf(id) ?: return null
        val idx = w.els.indexOf(e)
        val gap = El(null, e.len)
        gap.start = e.start
        w.els[idx] = gap
        for (u in w.units) if (u.anchor === e) u.anchor = gap
        w.addUnit(e.members.map { m -> m.clip.copy(level = m.clip.level + 1) }, 0f)
        return w.finish()
    }

    /**
     * Overwrite to primary: connected video clip [id] (with its linked sound) goes down into the storyline where it is,
     * over what is there. The storyline clips it covers lose their picture; their sound stays as connected clips (moved
     * to a free track when the clip brings its own sound).
     */
    fun overwriteToPrimary(id: Long): MPlan? {
        val c = byId[id] ?: return null
        if (!c.video || isPrimary(id)) return null
        val w = Work()
        val u = w.unitOf(id) ?: return null
        val sub = u.clips.filter { it.id == id || (c.link != null && it.link == c.link) }
        u.clips.removeAll(sub)
        val el = El(c.copy(level = 0), c.len)
        for (s in sub) el.members += Member(s.copy(level = if (s.video) 0 else (s.level - c.level).coerceAtLeast(0)), s.start - c.start)
        w.overwrite(el, c.start, preserveSound = true)
        return w.finish()
    }

    /**
     * The track a new connected clip of [len] s at [start] goes on: the first free one above V1 (video) or below A1
     * (sound); a number past the tracks there are means a new track; null = no room up to the limit.
     */
    fun connectLevel(video: Boolean, start: Float, len: Float): Int? {
        val max = if (video) maxVideo else maxAudio
        for (lvl in 1 until max) {
            if (clips.none { it.video == video && it.level == lvl && it.start < start + len - EPS && it.end > start + EPS }) return lvl
        }
        return null
    }

    // ------------------------------------------------------------------ the working copy of one edit

    private class Member(var clip: MClip, var rel: Float)

    /** A storyline element: a clip (its V1 clip and the clips linked to it) or a gap clip ([lead] null). */
    private class El(var lead: MClip?, var len: Float) {
        val members = ArrayList<Member>()
        var start = 0f
        /** How far the picture moved back by trims at the clip's start (s): connected clips keep to their frame. */
        var shift = 0f
        /** Where the element started before the edit (a cut is placed by it). */
        var origStart = 0f
        val isGap get() = lead == null
    }

    /** Connected clips that move together: a clip, the clips linked to it and the clips joined to them by transitions. */
    private class CUnit(val clips: MutableList<MClip>, val lead: MClip, val cp: Float, val fixed: Boolean, val caption: Boolean = false) {
        var anchor: El? = null
        var off = 0f
        /** Moved by the edit by this much (s), whatever its anchor does. */
        var explicit: Float? = null
        var keepInPlace = false
        var deleted = false
        /** Placed where the user put it (a free track is searched only if that one is taken). */
        var wanted = false
    }

    private inner class Work {
        val els = ArrayList<El>()
        val units = ArrayList<CUnit>()
        val removed = HashSet<Long>()
        val pieces = ArrayList<MPiece>()
        private var nextTemp = -1L

        init {
            // the storyline: V1's clips in order, the gaps between them as gap clips
            var cursor = 0f
            for (v in clips) {
                if (!v.video || v.level != 0) continue
                if (v.start > cursor + EPS) els += El(null, v.start - cursor).also { it.start = cursor; it.origStart = cursor }
                val e = El(v, v.len)
                e.start = v.start; e.origStart = v.start
                e.members += Member(v, 0f)
                for (p in partnersOf(v)) e.members += Member(p, p.start - v.start)
                els += e
                cursor = maxOf(cursor, v.end)
            }
            // connected groups (linked clips and clips joined by a transition)
            val rest = clips.filter { it.id !in primary }
            val parent = HashMap<Long, Long>()
            fun find(x: Long): Long { var r = x; while (parent[r] != null && parent[r] != r) r = parent[r]!!; return r }
            fun union(a: Long, b: Long) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
            for (c in rest) parent[c.id] = c.id
            for ((_, linked) in rest.filter { it.link != null }.groupBy { it.link }) for (i in 1 until linked.size) union(linked[0].id, linked[i].id)
            val restIds = rest.map { it.id }.toSet()
            for ((f, t) in transitions) if (f in restIds && t in restIds) union(f, t)
            for ((_, group) in rest.groupBy { find(it.id) }) {
                val lead = group.filter { it.video }.minByOrNull { it.start } ?: group.minBy { it.start }
                val cp = lead.start + (side.points[lead.id] ?: 0f).coerceIn(0f, (lead.len - EPS).coerceAtLeast(0f))
                units += CUnit(group.toMutableList(), lead, cp, group.any { it.id in side.disconnected })
            }
            // captions hang on the storyline as connected clips do (no tracks: they never stack)
            for (cap in captions) {
                val c = MClip(cap.id, false, -1, cap.start, (cap.end - cap.start).coerceAtLeast(0f))
                units += CUnit(mutableListOf(c), c, cap.start, false, caption = true)
            }
            // connected clips hanging past the storyline's end: a gap clip under them (as when connecting into an empty timeline)
            val end = els.lastOrNull()?.let { it.start + it.len } ?: 0f
            val tail = units.filter { !it.fixed && !it.caption && it.cp >= end - EPS }
            if (tail.isNotEmpty()) {
                val to = tail.maxOf { u -> u.clips.maxOf { it.end } }
                if (to > end + EPS) els += El(null, to - end).also { it.start = end; it.origStart = end }
            }
            for (u in units) {
                if (u.fixed) continue
                val e = els.firstOrNull { u.cp >= it.start - EPS && u.cp < it.start + it.len - EPS } ?: els.lastOrNull()
                u.anchor = e
                u.off = if (e == null) u.cp else u.cp - e.start
            }
        }

        fun layout() { var t = 0f; for (e in els) { e.start = t; t += e.len } }

        fun elOf(id: Long): El? = els.firstOrNull { e -> e.members.any { it.clip.id == id } }
        fun unitOf(id: Long): CUnit? = units.firstOrNull { u -> !u.caption && u.clips.any { it.id == id } }

        fun addUnit(cs: List<MClip>, delta: Float) {
            val lead = cs.filter { it.video }.minByOrNull { it.start } ?: cs.minBy { it.start }
            units += CUnit(cs.toMutableList(), lead, lead.start, false).also { it.explicit = delta; it.wanted = true }
        }

        /** Remove storyline element [e]: its clips go, and its connected clips with them (or they stay, [keepUnits]). */
        fun removeEl(e: El, keepUnits: Boolean) {
            els.remove(e)
            for (m in e.members) removed += m.clip.id
            for (u in units) if (u.anchor === e) { if (keepUnits) u.keepInPlace = true else u.deleted = true }
        }

        fun keepUnitsOf(e: El) { for (u in units) if (u.anchor === e) u.keepInPlace = true }

        /** The connected clips of [e] and of everything after it stay where they are. */
        fun keepUnitsAfter(e: El) {
            val i = els.indexOf(e)
            for (u in units) { val a = u.anchor ?: continue; if (els.indexOf(a) >= i) u.keepInPlace = true }
        }

        /** The index (in [els], laid out) of the edit point nearest to [t]. */
        fun nearestBoundary(t: Float): Int {
            var best = 0
            var bestD = abs(t)
            var acc = 0f
            for ((i, e) in els.withIndex()) {
                acc += e.len
                val d = abs(t - acc)
                if (d < bestD) { bestD = d; best = i + 1 }
            }
            return best
        }

        /** The gap clip at the end is as long as the connected clips hanging on it need (gone if none). */
        fun fitTrailingGap() {
            val g = els.lastOrNull()?.takeIf { it.isGap } ?: return
            val mine = units.filter { it.anchor === g && !it.caption && !it.deleted && it.explicit == null && !it.keepInPlace && it.clips.isNotEmpty() }
            g.len = (mine.maxOfOrNull { u -> u.off - g.shift + (u.clips.maxOf { it.end } - u.cp) } ?: 0f).coerceAtLeast(0f)
            if (mine.isEmpty()) els.remove(g)
        }

        /**
         * Cut the storyline at [t] (laid out, nothing moved before [t] yet): an element there is cut in two (a clip into
         * itself and a piece). Returns the index where something inserted at [t] goes.
         */
        fun splitAt(t: Float): Int {
            var acc = 0f
            for ((i, e) in els.withIndex()) {
                if (t <= acc + EPS) return i
                if (t < acc + e.len - EPS) {
                    val right = cut(e, t - acc)
                    els.add(i + 1, right)
                    return i + 1
                }
                acc += e.len
            }
            return els.size
        }

        /** Cut element [e] [s] s into it: [e] keeps the part before, the returned element is the part after. */
        fun cut(e: El, s: Float): El {
            val lead = e.lead
            val right = El(null, e.len - s)
            right.start = e.start + s
            right.origStart = e.origStart + s
            e.len = s
            if (lead != null) {
                for (m in e.members.toList()) {
                    val a = m.rel
                    val b = m.rel + m.clip.len
                    when {
                        b <= s + EPS -> Unit
                        a >= s - EPS -> { e.members.remove(m); right.members += Member(m.clip, a - s) }
                        else -> {
                            val id = nextTemp--
                            pieces += MPiece(id, m.clip.id, e.origStart + s)
                            val piece = m.clip.copy(id = id, len = b - s, head = 0f)
                            right.members += Member(piece, 0f)
                            m.clip = m.clip.copy(len = s - a)
                        }
                    }
                }
                right.lead = right.members.firstOrNull { it.clip.video && it.clip.level == 0 }?.clip
                right.shift = 0f
            }
            for (u in units) if (u.anchor === e && u.off >= s - EPS) { u.anchor = right; u.off -= s }
            return right
        }

        /**
         * [el] laid over the storyline from [at] (nothing has moved: the storyline's times are as before the edit). What
         * it covers is cut off or removed (the connected clips of what is removed stay where they are); with
         * [preserveSound], the covered clips' linked sound stays as connected clips.
         */
        fun overwrite(el: El, at: Float, preserveSound: Boolean) {
            layout()
            val end = els.lastOrNull()?.let { it.start + it.len } ?: 0f
            if (at >= end - EPS) {
                if (at > end + EPS) els += El(null, at - end)
                els += el
                return
            }
            val to = at + el.len
            if (preserveSound) {
                // cut at both edges, so what is under the clip can lose its picture and keep its sound
                splitAt(to); layout(); splitAt(at); layout()
            } else {
                // the element both edges fall in: cut in two where the clip ends (its middle is covered)
                els.firstOrNull { it.start < at - EPS && it.start + it.len > to + EPS }?.let { e ->
                    val r = cut(e, to - e.start)
                    els.add(els.indexOf(e) + 1, r)
                    layout()
                }
            }
            // (the times as laid out now: trimming below must not shift the elements that follow)
            val spans = els.associateWith { it.start to it.start + it.len }
            var idx = els.size
            for (e in els.toList()) {
                val (s, en) = spans.getValue(e)
                when {
                    en <= at + EPS -> Unit
                    s >= to - EPS -> idx = minOf(idx, els.indexOf(e))
                    s >= at - EPS && en <= to + EPS -> {
                        // covered: removed (its connected clips stay where they are)
                        idx = minOf(idx, els.indexOf(e))
                        els.remove(e)
                        for (u in units) if (u.anchor === e) u.keepInPlace = true
                        if (e.lead == null) continue
                        for (m in e.members) {
                            if (preserveSound && !m.clip.video) {
                                units += CUnit(mutableListOf(m.clip.copy(start = s + m.rel)), m.clip, s + m.rel, false).also { it.explicit = 0f }
                            } else removed += m.clip.id
                        }
                    }
                    // the start falls in it: it keeps its head
                    s < at -> trimTail(e, at - s)
                    // the end falls in it: it keeps its tail, where it is (its start moves on)
                    else -> { trimHead(e, to - s); idx = minOf(idx, els.indexOf(e)) }
                }
            }
            if (idx > els.size) idx = els.size
            els.add(idx, el)
        }

        /** Keep the first [len] s of element [e]. */
        fun trimTail(e: El, len: Float) {
            for (u in units) if (u.anchor === e && u.off >= len - EPS) u.keepInPlace = true
            e.len = len
            for (m in e.members.toList()) {
                val keep = len - m.rel
                if (keep <= EPS) { e.members.remove(m); removed += m.clip.id } else if (m.clip.len > keep) m.clip = m.clip.copy(len = keep)
            }
        }

        /** Cut [cut] s off element [e]'s start; what remains stays where it is (the picture and its connected clips too). */
        fun trimHead(e: El, cut: Float) {
            for (u in units) if (u.anchor === e && u.off < cut - EPS) u.keepInPlace = true
            e.len -= cut
            // (nothing moves in an overwrite: the rest of the element and what hangs on it stay where they are)
            e.shift += cut
            for (m in e.members.toList()) {
                val gone = cut - m.rel
                if (gone >= m.clip.len - EPS) { e.members.remove(m); removed += m.clip.id; continue }
                if (gone > 0f) { m.clip = m.clip.copy(head = m.clip.head + gone, len = m.clip.len - gone); m.rel = 0f } else m.rel -= cut
            }
            if (e.lead != null) e.lead = e.members.firstOrNull { it.clip.video && it.clip.level == 0 }?.clip
        }

        /** The timeline after the edit. */
        fun finish(): MPlan {
            // neighboring gap clips are one
            var i = 0
            while (i < els.size - 1) {
                val a = els[i]; val b = els[i + 1]
                if (a.isGap && b.isGap) {
                    for (u in units) if (u.anchor === b) { u.anchor = a; u.off += a.len + a.shift - b.shift }
                    a.len += b.len
                    els.removeAt(i + 1)
                } else i++
            }
            fitTrailingGap()
            layout()
            val primaryOut = ArrayList<MClip>()
            for (e in els) if (!e.isGap) for (m in e.members) primaryOut += m.clip.copy(start = e.start + m.rel)
            // where each connected group goes (and each caption)
            val placed = ArrayList<Pair<CUnit, Float>>()
            val capsGone = HashSet<Long>()
            val capShift = HashMap<Long, Float>()
            for (u in units) {
                if (u.clips.isEmpty()) continue
                if (u.caption && (u.deleted || (u.explicit == null && !u.keepInPlace && u.anchor != null && u.anchor !in els))) { capsGone += u.lead.id; continue }
                if (u.deleted) { u.clips.forEach { removed += it.id }; continue }
                val a = u.anchor
                var d = when {
                    u.explicit != null -> u.explicit!!
                    u.keepInPlace || u.fixed || a == null -> 0f
                    a !in els -> { u.clips.forEach { removed += it.id }; continue }
                    else -> {
                        var cp = a.start + u.off - a.shift
                        // a connection point cut off with the clip's start: it stays on the clip's first frame
                        if (a.lead != null && cp < a.start) cp = a.start
                        cp - u.cp
                    }
                }
                val lo = u.clips.minOf { it.start }
                if (lo + d < 0f) d = -lo
                if (u.caption) { if (abs(d) > EPS) capShift[u.lead.id] = d; continue }
                placed += u to d
            }
            // tracks: the storyline first, then the connected clips that stay, then those that moved (in time order)
            val occupied = HashMap<Pair<Boolean, Int>, ArrayList<Pair<Float, Float>>>()
            fun free(video: Boolean, level: Int, ranges: List<Pair<Float, Float>>) = occupied[video to level].orEmpty().none { (a, b) ->
                ranges.any { (s, e) -> s < b - EPS && e > a + EPS }
            }
            fun take(video: Boolean, level: Int, ranges: List<Pair<Float, Float>>) { occupied.getOrPut(video to level) { ArrayList() }.addAll(ranges) }
            for (c in primaryOut) take(c.video, c.level, listOf(c.start to c.end))
            val out = ArrayList<MClip>(primaryOut)
            var stuck = 0
            val order = placed.sortedWith(compareBy<Pair<CUnit, Float>>({ if (it.second == 0f && !it.first.wanted) 0 else 1 }, { p -> p.first.clips.minOf { it.start } + p.second }))
            for ((u, d) in order) {
                val moved = u.clips.map { it.copy(start = it.start + d) }
                for ((lane, block) in moved.groupBy { it.video to it.level }) {
                    val (video, level) = lane
                    val ranges = block.map { it.start to it.end }
                    val max = if (video) maxVideo else maxAudio
                    val min = if (video) 1 else 0
                    var lvl = level.coerceAtLeast(min)
                    while (lvl < max && !free(video, lvl, ranges)) lvl++
                    if (lvl >= max) { lvl = level.coerceAtLeast(min); stuck += block.size }
                    take(video, lvl, ranges)
                    for (b in block) out += b.copy(level = lvl)
                }
            }
            val ids = out.map { it.id }.toSet()
            for (c in clips) if (c.id !in ids) removed += c.id
            removed.removeAll(ids)
            val vl = maxOf(videoLanes, (out.filter { it.video }.maxOfOrNull { it.level } ?: 0) + 1)
            val al = maxOf(audioLanes, (out.filter { !it.video }.maxOfOrNull { it.level } ?: 0) + 1)
            return MPlan(out.sortedWith(compareBy({ it.start }, { it.id })), removed.toSet(), pieces.filter { p -> p.id in ids }, vl, al, stuck, capShift, capsGone)
        }
    }
}
