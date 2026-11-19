package se.gendut.klipp.engine

import kotlinx.coroutines.sync.withLock

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Pre-render (as in other editing programs): the timeline is split into chunks at every edge (clips, transitions,
 * layers) and a chunk is rendered – with all layers, grades, masks and transitions – to a file at preview size with dense
 * keyframes. Playback then plays the file instead of computing the chunk's image ([substitute]): one decoder instead of
 * several, no real-time effects, and every chunk starts on a keyframe (no hitch at the clip switch).
 *
 * Every chunk has a key ([Chunk.hash]) from everything that affects its image; if something in the chunk changes (or
 * before it, so that it moves) the file no longer applies and the chunk plays normally again. The look is not in the
 * files – it is applied to the whole image during playback (and can change freely). Titles and captions are drawn by the
 * app on top.
 *
 * The files live in filesDir/render (same key = same file, even after a restart and across projects); the oldest are
 * deleted when together they exceed [MAX_BYTES].
 */
@OptIn(UnstableApi::class)
object RenderCache {
    /** Increments when a chunk is ready or the files were deleted (playback is then rebuilt). */
    var revision by mutableIntStateOf(0)
        private set

    /** The chunk being rendered now (start, end in s) and how much of it is done (0..1) – the timeline's render bar shows it. */
    var current by mutableStateOf<Pair<Double, Double>?>(null)
        private set
    var currentDone by mutableStateOf(0f)
        private set

    /** A render waits for a clip's proxy (see [proxyPending]): the render chip says so instead of 0 %. */
    var waitingForProxy by mutableStateOf(false)
        private set

    /** Diagnostics (test runs): playback builds its composition again, as when a chunk becomes ready – nothing is deleted. */
    fun touch() { revision++ }

    /** The rendering in progress: fraction done (0..1), otherwise null. */
    var progress by mutableStateOf<Float?>(null)
        internal set

    /** Diagnostics (the agent bridge): nothing is rendered – the timeline plays live until it is turned off again. */
    var hold by mutableStateOf(false)

    /** Stopped by the user: no optional rendering ("Render", automatic) until requested again – transitions still render (playback shows them only rendered). */
    var halted by mutableStateOf(false)

    /** Render heavy parts automatically in the background (when nothing is playing or being dragged); saved in the app's settings. */
    var auto by mutableStateOf(false)
        private set

    fun setAuto(context: Context, on: Boolean) {
        auto = on
        if (on) halted = false
        context.getSharedPreferences("klipp", Context.MODE_PRIVATE).edit().putBoolean("renderAuto", on).apply()
    }

    /** "Render timeline": all chunks that are not ready get rendered (once; reset when done). */
    var renderAll by mutableStateOf(false)

    /**
     * Chunks the live preview did not keep up with (PlaybackMargin: late or dropped frames while playing), by key: rendered
     * ahead like transitions, red on the render bar until then. A change in the chunk gives it a new key – measured anew.
     */
    private val slow = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    var slowRevision by mutableIntStateOf(0)
        private set
    fun markSlow(keys: Set<String>) { if (slow.addAll(keys)) slowRevision++ }
    fun isSlow(c: Chunk): Boolean = c.hash in slow

    /** With [renderAll]: only the chunks within this stretch (s) – the selected clips' (the render chip's "Render selection"). */
    var renderRange by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)

    /** The chunks' state for the timeline's render bar: (from, to, ready, needs rendering). */
    var states by mutableStateOf<List<ChunkState>>(emptyList())
        internal set

    data class ChunkState(val a: Double, val b: Double, val ready: Boolean, val heavy: Boolean)

    /** A chunk of the timeline [a]–[b]; [heavy] = anything other than a plain clip without effects (worth rendering). */
    data class Chunk(
        val a: Double, val b: Double, val hash: String, val heavy: Boolean,
        /** A plain part of a clip can play from a longer file of the same clip, if one was rendered: their keys and where in them this part starts (s). */
        val longer: List<Pair<String, Double>> = emptyList(),
    )

    private const val VERSION = 20
    /** Chunks shorter than this merge with the neighbor (a frame on its own would just mean more clip switches). */
    private const val MIN_CHUNK = 0.25
    /** The longest piece a stretch of the timeline is rendered in (s), see [chunks]. */
    const val MAX_CHUNK = 4.0
    /** The disk space for the files. */
    private const val MAX_BYTES = 3L * 1024 * 1024 * 1024
    /** The render ids (like the reversed clips': negative, but in a range of their own). */
    private const val ID_BASE = -(1L shl 52)

    @Volatile private var dir: File? = null
    private val files = ConcurrentHashMap<Long, File>()

    fun init(context: Context) {
        if (dir != null) return
        auto = context.getSharedPreferences("klipp", Context.MODE_PRIVATE).getBoolean("renderAuto", false)
        val d = File(context.applicationContext.filesDir, "render").apply { mkdirs() }
        d.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        d.listFiles()?.filter { it.name.endsWith(".mp4") && it.length() > 0 }?.forEach { f -> files[idOf(f.nameWithoutExtension)] = f }
        dir = d
    }

    /** A transition's key, by the clips before and after it (its look, see TransitionLooks). */
    fun pairKey(from: Long, to: Long) = "$from>$to"

    /** The file for a render id (playback's uriOf). */
    fun uriOf(id: Long): Uri? = if (id > ID_BASE) null else files[id]?.takeIf { it.exists() }?.let { Uri.fromFile(it) }

    private fun idOf(hash: String): Long = ID_BASE - (hash.take(10).toLong(16) and 0xFF_FFFF_FFFFL)

    /** Whether [item] is a pre-rendered chunk's file (their ids lie below [ID_BASE]'s range, never a project item's). */
    fun isChunk(item: Long): Boolean = item <= ID_BASE

    private fun fileOf(hash: String): File? = dir?.let { File(it, "$hash.mp4") }

    private fun exists(hash: String) = fileOf(hash)?.let { it.exists() && it.length() > 0 } == true

    fun isReady(c: Chunk): Boolean = exists(c.hash) || c.longer.any { exists(it.first) }

    /** The longer file this chunk plays from, having none of its own. */
    private fun borrowed(c: Chunk) = if (exists(c.hash)) null else c.longer.firstOrNull { exists(it.first) }

    /**
     * The chunks in [plan] (playback's plan, before substitution). [gradeOf] = the clips' grade, [uriOf] = the files,
     * [fps] = the project frame rate, [short] = the preview's short side (the files' size).
     */
    fun chunks(plan: LayerPlan, gradeOf: (Long) -> ClipGrade?, uriOf: (Long) -> Uri?, fps: Double, short: Int): List<Chunk> {
        val edges = sortedSetOf(plan.origin, plan.total)
        for (l in plan.layers) for (sg in l.segs) { edges += sg.start; edges += sg.end }
        // a transition window is a chunk of its own, also where it lies inside one piece (the transitions that show one
        // picture at a time: zoom, whip, rotate, dips): only it has to be rendered before the transition shows – the rest
        // of the clip keeps its key and its file (a whip into a 98 s clip waited for all 98 s to render)
        for (l in plan.layers) for (sg in l.segs) for (w in sg.windows) {
            if (w.t0 > sg.start + 1e-6 && w.t0 < sg.end - 1e-6) edges += w.t0
            if (w.t1 > sg.start + 1e-6 && w.t1 < sg.end - 1e-6) edges += w.t1
        }
        // on the timeline's grid: an edge between two slots moves up to the next slot, so a chunk holds exactly the slots
        // of [a, b) and begins on one. A chunk beginning between slots (a cut at 6.56 s in a 60 fps timeline made at 25)
        // was rendered on its own grid – the muxer puts a file's first frame at 0 – a frame more than its slots, the
        // last one where the next chunk's first goes (measured on the vivo: 17 frames for 16 slots)
        // a transition window is one chunk: no edge inside it (its pieces are only ever played together, and a join inside
        // it put a keyframe at the cut – the picture there stood one display refresh too long on the vivo, every time,
        // though the frames went out evenly)
        val inside = plan.layers.flatMap { l -> l.segs.flatMap { sg -> sg.windows + listOfNotNull(sg.fx) } }.map { it.t0 to it.t1 }.distinct()
        val e = edges.filter { it >= plan.origin - 1e-9 && it <= plan.total + 1e-9 }
            .filter { x -> inside.none { (t0, t1) -> x > t0 + 1e-6 && x < t1 - 1e-6 } }
            .map { x -> if (fps <= 0.0 || x <= plan.origin + 1e-9) x else minOf(plan.total, Math.ceil(x * fps - 1e-6) / fps) }
            .distinct().sorted().toMutableList()
        // too-short chunks: the edge is removed (the chunk merges with the one before)
        var i = 1
        while (i < e.size) {
            if (e[i] - e[i - 1] < MIN_CHUNK && e.size > 2) { if (i == e.size - 1) e.removeAt(i - 1) else e.removeAt(i) } else i++
        }
        // a long stretch is rendered in pieces of at most MAX_CHUNK: playback stops the chunk being rendered and it starts
        // over from its beginning afterwards, so a long clip (27 s on the vivo: half a minute undisturbed) was never ready
        // while one kept playing and adjusting – a piece is done in a few seconds and stays. The pieces begin on fixed
        // places of the timeline (multiples of MAX_CHUNK, on a slot), so an edit elsewhere in the clip keeps the others'
        // keys; ready neighbours are joined into one file as always. Never inside a transition window (one chunk, see above)
        val pieces = ArrayList<Double>()
        for (k in 0 until e.size - 1) {
            val a = e[k]; val b = e[k + 1]
            if (b - a <= MAX_CHUNK * 1.5 || inside.any { (t0, t1) -> t0 < b - 1e-6 && t1 > a + 1e-6 }) continue
            var x = (Math.floor(a / MAX_CHUNK + 1e-9) + 1) * MAX_CHUNK
            while (x < b - 1e-6) {
                val at = if (fps > 0.0) Math.ceil(x * fps - 1e-6) / fps else x
                if (at - a >= MAX_CHUNK / 2 && b - at >= MAX_CHUNK / 2) pieces += at
                x += MAX_CHUNK
            }
        }
        if (pieces.isNotEmpty()) { e += pieces; e.sort() }
        val out = ArrayList<Chunk>()
        for (k in 0 until e.size - 1) {
            val a = e[k]; val b = e[k + 1]
            if (b - a <= 1e-6) continue
            val sb = StringBuilder()
            sb.append("v").append(VERSION).append(if (androidx.media3.effect.KlippPrecision.sdrYuv) "y" else "").append('|').append(plan.width).append('x').append(plan.height).append('@').append(fps).append('/').append(short)
                .append('|').append(r(a)).append('-').append(r(b))
            var heavy = plan.layers.count { l -> l.segs.any { it.end > a + 1e-6 && it.start < b - 1e-6 } } > 1
            for ((li, l) in plan.layers.withIndex()) {
                val segs = l.segs.filter { it.end > a + 1e-6 && it.start < b - 1e-6 }
                if (segs.isEmpty()) continue
                // (counted from the bottom: a transition elsewhere adds a layer on top, and must not change this chunk's key)
                sb.append("|L").append(plan.layers.size - 1 - li)
                if (segs.size > 1) heavy = true
                for (sg in segs) {
                    sb.append('[').append(r(sg.start)).append(',').append(r(sg.end)).append(',')
                    when (val s = sg.src) {
                        is SegSrc.Media -> sb.append("M").append(uriOf(s.item)).append(proxyKey(uriOf(s.item))).append(refKey(uriOf(s.item))).append(',').append(r(s.srcIn)).append(',').append(s.speed)
                        is SegSrc.Still -> { sb.append("S").append(uriOf(s.item)).append(refKey(uriOf(s.item))).append(',').append(r(s.sec)); heavy = true }
                    }
                    // ramps and transition windows only where they apply in the chunk (a transition further on does not change this one)
                    sg.ramp?.takeIf { it.t1 > a + 1e-6 && it.t0 < b - 1e-6 }?.let { sb.append(",R").append(it); heavy = true }
                    // (with the version of the transitions' moves: files rendered before they changed are made again)
                    sg.fx?.let { sb.append(",F").append(SegFx.LOOK).append(it); heavy = true }
                    sg.windows.filter { it.t1 > a + 1e-6 && it.t0 < b - 1e-6 }.takeIf { it.isNotEmpty() }?.let { sb.append(",W").append(SegFx.LOOK).append(it); heavy = true }
                    sg.clip?.let { c ->
                        sb.append(",C").append(videoKey(c))
                        if (c.hasMotion(plan.width, plan.height) || c.hasOpacity || kotlin.math.abs(c.speed - 1.0) > 1e-3 || c.reverse) heavy = true
                        gradeOf(c.id)?.let { g ->
                            sb.append(gradeKey(g))
                            if (!g.isNeutral || g.layer != null) heavy = true
                        }
                    }
                    sb.append(']')
                }
            }
            val longer = plan.layers.flatMap { l -> l.segs.filter { it.end > a + 1e-6 && it.start < b - 1e-6 } }.singleOrNull()
                ?.let { longerFiles(it, a, b, plan, gradeOf, uriOf, fps, short) }.orEmpty()
            out += Chunk(a, b, sha1(sb.toString()), heavy, longer)
        }
        return out
    }

    /**
     * Longer files that hold the chunk [a]–[b], if it is a plain part of a clip: a transition added later moves the chunk's
     * edges, but the part outside it looks the same as before. The keys [chunks] gave the clip without that transition (at
     * the start, at the end or both), each with where the part starts in that file.
     */
    private fun longerFiles(sg: Seg, a: Double, b: Double, plan: LayerPlan, gradeOf: (Long) -> ClipGrade?, uriOf: (Long) -> Uri?, fps: Double, short: Int): List<Pair<String, Double>> {
        val c = sg.clip ?: return emptyList()
        val s = sg.src as? SegSrc.Media ?: return emptyList()
        if (kotlin.math.abs(s.speed - 1.0) > 1e-3 || c.reverse || c.hold != null || sg.fx != null) return emptyList()
        if (sg.ramp?.let { it.t1 > a + 1e-6 && it.t0 < b - 1e-6 } == true || sg.windows.any { it.t1 > a + 1e-6 && it.t0 < b - 1e-6 }) return emptyList()
        if (a < c.start - 1e-6 || b > c.end + 1e-6) return emptyList()
        fun key(from: Double, to: Double): String {
            val sb = StringBuilder()
            sb.append("v").append(VERSION).append(if (androidx.media3.effect.KlippPrecision.sdrYuv) "y" else "").append('|').append(plan.width).append('x').append(plan.height).append('@').append(fps).append('/').append(short)
                .append('|').append(r(from)).append('-').append(r(to)).append("|L0[").append(r(c.start)).append(',').append(r(c.end)).append(',')
                .append("M").append(uriOf(s.item)).append(proxyKey(uriOf(s.item))).append(refKey(uriOf(s.item))).append(',').append(r(c.sourceIn)).append(',').append(s.speed).append(",C").append(videoKey(c))
            gradeOf(c.id)?.let { g -> sb.append(gradeKey(g)) }
            sb.append(']')
            return sha1(sb.toString())
        }
        return listOf(c.start to c.end, a to c.end, c.start to b)
            .filter { (from, to) -> from < a - 1e-6 || to > b + 1e-6 }
            .map { (from, to) -> key(from, to) to a - from }
    }

    /**
     * A clip's grade in the key: without the nodes' names (renaming a node doesn't change the image), with each LUT's
     * contents (a new LUT under the same name must make the chunk again, as in the node cache).
     */
    private fun gradeKey(g: ClipGrade): String {
        val sb = StringBuilder(",G").append(g.copy(nodes = g.nodes.map { it.copy(label = "") }).toJson())
        for (n in g.nodes) n.lut?.let { name -> runCatching { LutStore.get(name) }.getOrNull()?.data?.contentHashCode()?.let { sb.append(",lut").append(it) } }
        return sb.toString()
    }

    /** Chunks are made from the proxy when there is one: a chunk made from the original is made again once the proxy exists (and back). */
    // (a proxy with headroom keeps the super-whites a grade can pull down: another picture)
    private fun proxyKey(u: Uri?): String = if (u != null && Proxies.videoUri(u) != u) "~p" + (if (Headroom.isHeadroom(Proxies.videoUri(u))) "h" else "") else ""

    /** What in the clip affects the image (not the audio: volume, audio effects, name). */
    private fun videoKey(c: ClipSnap): String = listOf(
        c.item, r(c.start), r(c.duration), r(c.sourceIn), c.speed, c.x, c.y, c.scale, c.rotation, c.opacity,
        c.kf.filterKeys { it != KfKeys.LEVEL }, c.reverse, c.hold, c.enabled,
    ).joinToString(";")

    private fun r(v: Double) = Math.round(v * 1e5) / 1e5

    /** The source's size (the film's reference, see SourceSizes): a piece rendered before it was known is made again. */
    private fun refKey(u: Uri?): String = ",r" + (SourceSizes.short(u) ?: 0)

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * The plan with the ready chunks substituted: in the chunks' time everything is removed from all layers and the bottom
     * layer plays the file. (Same in the main player and in the app's upper layer, so they agree.)
     */
    fun substitute(plan: LayerPlan, chunks: List<Chunk>): LayerPlan = substituteReady(plan, chunks.filter { isReady(it) })

    /**
     * The transitions between two clips (timeline time) in the tracks the preview draws: they are only ever seen pre-rendered
     * (see [playPlan]).
     */
    fun transitionWindows(s: Snapshot): List<ClosedFloatingPointRange<Double>> =
        s.playable().sequence?.video.orEmpty().take(3).filter { it.enabled }.flatMap { t ->
            val byId = t.clips.associateBy { it.id }
            t.transitions.filter { x ->
                x.duration > 1e-6 && x.from?.let { byId[it] }?.text == null && x.to?.let { byId[it] }?.text == null &&
                    x.from != null && x.to != null
            }.map { it.start..it.end }
        }

    /**
     * Playback's plan: [cuts] (the timeline with its transitions as plain cuts, LayerPlanner.plan(transitions = false)) with
     * the ready chunks of [chunks] (made from the plan with the transitions) in their place. A transition comes in only
     * whole – all chunks over its window ready – otherwise it stays a cut (half of one rendered and the other half a cut
     * would jump). The main player and the upper layers both play this, so they always agree.
     */
    fun playPlan(cuts: LayerPlan, chunks: List<Chunk>, windows: List<ClosedFloatingPointRange<Double>>, fps: Double = 0.0): LayerPlan =
        substituteReady(cuts, playable(chunks, windows), fps)

    /** The ready chunks playback may use: none of a transition that isn't ready all over. */
    fun playable(chunks: List<Chunk>, windows: List<ClosedFloatingPointRange<Double>>, ready: (Chunk) -> Boolean = ::isReady): List<Chunk> {
        fun over(c: Chunk, w: ClosedFloatingPointRange<Double>) = c.a < w.endInclusive - 1e-6 && c.b > w.start + 1e-6
        val incomplete = windows.filter { w -> chunks.any { c -> over(c, w) && !ready(c) } }
        return chunks.filter { c -> ready(c) && incomplete.none { w -> over(c, w) } }
    }

    private fun substituteReady(plan: LayerPlan, ready: List<Chunk>, fps: Double = 0.0): LayerPlan {
        if (ready.isEmpty() || plan.layers.isEmpty()) return plan
        // adjacent ready chunks merge into one interval (but play as separate files)
        val ranges = ready.map { it.a..it.b }
        fun cut(segs: List<Seg>): List<Seg> {
            val out = ArrayList<Seg>()
            for (sg in segs) {
                var pieces = listOf(sg)
                for (rg in ranges) {
                    pieces = pieces.flatMap { p ->
                        if (p.end <= rg.start + 1e-9 || p.start >= rg.endInclusive - 1e-9) listOf(p)
                        else listOfNotNull(
                            if (rg.start - p.start > 1e-6) p.copy(dur = rg.start - p.start) else null,
                            if (p.end - rg.endInclusive > 1e-6) p.copy(start = rg.endInclusive, dur = p.end - rg.endInclusive, src = shiftSrc(p.src, rg.endInclusive - p.start)) else null,
                        )
                    }
                }
                out += pieces
            }
            return out
        }
        val layers = plan.layers.map { it.copy(segs = cut(it.segs)) }.toMutableList()
        val rendered = renderedSegs(ready)
        val bottom = layers.removeAt(layers.lastIndex)
        val base = bottom.copy(segs = liveAfterChunks((bottom.segs + rendered).sortedBy { it.start }, rendered, fps))
        val kept = layers.filter { it.segs.isNotEmpty() } + base
        val fades = if (kept.size == 1) base.segs.mapNotNull { it.ramp }.distinct() else emptyList()
        return plan.copy(layers = kept, blackFades = fades, rendered = ranges)
    }

    /**
     * A live clip right after a pre-rendered piece begins a little earlier, inside the piece's last slot: just after
     * the point from which a source frame belongs to the slot at the piece's end (SourceSampler: frames after the slot
     * before, + LEAD) – its in point moves along by the same, so every frame keeps its time, and the piece plays that
     * much less (its last frame lies on the slot before, it stays). Cut at the piece's end, the clip lacked the source
     * frame lying between those two points – the export's frame for that slot: the first slot after a piece showed the
     * next source frame (on the vivo: 4.28 s on the 25p test timeline, 6.317 and 6.333 s on the 60p one, in playback
     * and paused). The rule pins the clip's first frame on the piece's end slot, as the export shows it.
     */
    internal fun liveAfterChunks(segs: List<Seg>, rendered: List<Seg>, fps: Double): List<Seg> {
        if (fps <= 0.0 || rendered.isEmpty()) return segs
        val g = FrameGrid(fps)
        val out = segs.toMutableList()
        for (i in 0 until out.size - 1) {
            val piece = out[i]; val next = out[i + 1]
            if (rendered.none { it === piece }) continue
            val src = next.src as? SegSrc.Media ?: continue
            if (rendered.any { it === next } || kotlin.math.abs(next.start - piece.end) > 1e-6 || src.speed <= 1e-6) continue
            val endUs = Math.round(piece.end * 1e6)
            val k = g.slotAtOrAfter(endUs - FrameGrid.LEAD_US)
            // (after everything that belongs to the slot before: a frame up to half a slot after it does, see FrameGrid.slotOf)
            val from = (g.timeOf(k - 1) + FrameGrid.LEAD_US + g.tolUs + 2) / 1e6
            val shift = piece.end - from
            if (shift <= 1e-6 || from <= piece.start + 1e-6 || shift >= next.dur) continue
            out[i] = piece.copy(dur = piece.dur - shift)
            out[i + 1] = next.copy(start = from, dur = next.dur + shift, src = src.copy(srcIn = src.srcIn - shift * src.speed), cut = true)
        }
        return out
    }

    /** Runs of chunks that sit edge to edge (in time order). */
    /**
     * Ready chunks edge to edge, as runs that can be joined into one file: a run also ends where the next chunk's file
     * is encoded differently ([formatOf]) – a joined file has one format (the first piece's), and a piece encoded
     * otherwise was decoded with it (the vivo's encoder made the timeline's last chunk limited range and the others
     * full range: in the joined file that chunk's blacks were lifted and its whites lowered, only once it was rendered).
     */
    private fun runs(chunks: List<Chunk>): List<List<Chunk>> {
        val out = ArrayList<MutableList<Chunk>>()
        for (c in chunks.sortedBy { it.a }) {
            val last = out.lastOrNull()
            if (last != null && kotlin.math.abs(last.last().b - c.a) < 1e-6 && formatOf(last.last().hash) == formatOf(c.hash)) last += c
            else out += mutableListOf(c)
        }
        return out
    }

    /** A chunk file's encoding (by its hash – a file never changes): what must be equal for its frames to join another's. */
    private val formats = ConcurrentHashMap<String, String>()

    private fun formatOf(hash: String): String {
        formats[hash]?.let { return it }
        val f = fileOf(hash)?.takeIf { it.exists() && it.length() > 0 } ?: return ""
        val k = runCatching { formatKey(f) }.getOrDefault("?" + hash)
        formats[hash] = k
        return k
    }

    /** The video track's codec, size, color description and parameter sets (SPS/PPS: profile, level, range, matrix). */
    private fun formatKey(f: File): String {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(f.path)
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val m = ex.getTrackFormat(t)
            fun int(k: String) = if (m.containsKey(k)) m.getInteger(k) else -1
            fun csd(k: String) = m.getByteBuffer(k)?.let { b -> val a = ByteArray(b.remaining()); b.duplicate().get(a); a.joinToString("") { "%02x".format(it) } }.orEmpty()
            return listOf(m.getString(android.media.MediaFormat.KEY_MIME), int(android.media.MediaFormat.KEY_WIDTH), int(android.media.MediaFormat.KEY_HEIGHT),
                int(android.media.MediaFormat.KEY_COLOR_RANGE), int(android.media.MediaFormat.KEY_COLOR_STANDARD), int(android.media.MediaFormat.KEY_COLOR_TRANSFER),
                csd("csd-0"), csd("csd-1")).joinToString("|")
        } finally {
            ex.release()
        }
    }

    private fun joinHash(run: List<Chunk>) = sha1("join|" + run.joinToString(",") { it.hash })

    /**
     * Join ready chunks that sit edge to edge into one file (the frames are copied – no re-encoding; every chunk starts on
     * a keyframe with the same format). The chunks' files remain (a change in one chunk only redoes the join).
     */
    fun joinRuns(chunks: List<Chunk>) = synchronized(joinLock) { joinRunsLocked(chunks) }

    private fun joinRunsLocked(chunks: List<Chunk>) {
        val d = dir ?: return
        for (run in runs(chunks.filter { exists(it.hash) })) {
            if (run.size < 2) continue
            val h = joinHash(run)
            val out = File(d, "$h.mp4")
            if (out.exists() && out.length() > 0) continue
            val tmp = File(d, "$h.tmp")
            val ok = runCatching { concat(run.map { File(d, it.hash + ".mp4") to (it.a - run.first().a) }, tmp) }
                .onFailure { se.gendut.klipp.CrashReport.note("pre-render: join: ${it.message}") }.isSuccess
            if (ok && tmp.renameTo(out)) files[idOf(h)] = out else tmp.delete()
        }
    }

    /**
     * Where a chunk is rendered to: one frame past its end [Chunk.b] when the timeline goes on (cut away after, so the
     * file holds exactly [a, b)). Rendered only to b, the chunk's last item ended the whole render, and the frame the
     * effects sent last – a transition's frame repeated into the slot before the edit point – didn't reach the file:
     * Media3's encoder path loses a final frame sent just before the end of input (seen on the vivo: a 60 fps whip's
     * outgoing chunk without its last slot, a 17 ms hold at the cut in every playback).
     */
    /**
     * How far before a part starting at [a] it is read (s): the frame rate's rule chooses a frame by its time and the
     * frame before it (SourceSampler), and a slot no frame belongs to holds the frame before, so a part starting inside
     * a clip needs the source frames before its start – read there, its first slots get the frames the whole timeline
     * has there (before, the stream began with the part: another source frame on its first slots than in the export).
     * Two and a half source frames of the slowest clip playing at [a] and a slot more; nothing at the timeline's start.
     * The frames before [a] are kept unseen (StartPinPlanner).
     */
    internal fun preRoll(s: Snapshot, a: Double): Double {
        if (a <= 1e-9) return 0.0
        val q = s.sequence ?: return 0.0
        val slot = 1.0 / (s.fps.takeIf { it > 0.0 } ?: 25.0)
        var p = 0.0
        for (t in q.video) for (c in t.clips) {
            if (!c.enabled || c.start > a + 1e-9 || c.start + c.duration <= a + 1e-9) continue
            val f = s.media.firstOrNull { it.id == c.item }?.fps?.takeIf { it > 0.0 }
            val sp = kotlin.math.abs(c.speed).takeIf { it > 1e-6 } ?: 1.0
            p = maxOf(p, if (f == null) PRE_ROLL_UNKNOWN else 2.5 / (f * sp) + slot)
        }
        return minOf(p, PRE_ROLL_MAX, a)
    }

    /**
     * The pre-roll from the clips' real frame times (PreRollRule): reading begins at the frame before the one the whole
     * timeline shows on the part's first slot – after a long gap in a variable-frame-rate file, or with a slow source,
     * well before the part (a fixed pre-roll from the nominal rate missed it: the part's first slots showed the frame
     * after the gap early). The times come from the played file's sample table (no decoding; a gap has no frames, so a
     * long one costs nothing to read through). Bounded: [PRE_ROLL_SYNC_STEPS] steps back from key frame to key frame
     * and [PRE_ROLL_LIMIT] seconds; a clip that can't be read, a reversed one or a freeze frame: [preRoll]'s estimate.
     */
    internal fun preRollFor(context: Context, s0: Snapshot, uriOf: (Long) -> Uri?, a: Double, export: Boolean): Double {
        if (a <= 1e-9) return 0.0
        // (the clips as the render reads them: their in points on their own frames, see FrameSnap)
        val s = FrameSnap.snapshot(s0) { id -> uriOf(id)?.let { if (export) it else Proxies.videoUri(it) } }
        val q = s.sequence ?: return 0.0
        val fps = s.fps.takeIf { it > 0.0 } ?: return preRoll(s, a)
        val g = FrameGrid(fps)
        val visibleUs = Math.round(a * 1e6)
        val slotEnd = g.timeOf(g.slotAtOrAfter(visibleUs - FrameGrid.LEAD_US)) + FrameGrid.LEAD_US + g.tolUs
        var p = 0.0
        for (t in q.video) for (c in t.clips) {
            if (!c.enabled || c.text != null || c.start > a + 1e-9 || c.start + c.duration <= a + 1e-9) continue
            val sp = c.speed
            val uri = uriOf(c.item)?.let { if (export) it else Proxies.videoUri(it) }
            if (uri == null || c.reverse || c.hold != null || sp <= 1e-6) { p = maxOf(p, preRoll(s, a)); continue }
            val need = runCatching { readStartTau(context, uri, c, sp, slotEnd, visibleUs, fps) }.getOrNull()
            p = maxOf(p, if (need == null) preRoll(s, a) else a - (need - g.stepUs) / 1e6)
        }
        // (the reading starts on a slot: an upper layer's empty stretch from there gives the compositor's primary frames,
        // one per 1/fps from its start – off the grid, the compositor paired the bottom layer's frames with other times
        // than the whole timeline does: a part from 1.52 s showed the frame before on every slot to the next cut)
        val from = a - p.coerceIn(0.0, minOf(a, PRE_ROLL_LIMIT))
        return (a - Math.floor(from * fps + 1e-6) / fps).coerceIn(0.0, a)
    }

    /** The timeline time (µs) a part showing from [visibleUs] must read clip [c] from, or null when it can't be told. */
    private fun readStartTau(context: Context, uri: Uri, c: ClipSnap, speed: Double, slotEndUs: Long, visibleUs: Long, fps: Double): Long? {
        fun tau(fileUs: Long) = Math.round((c.start + (fileUs / 1e6 - c.sourceIn) / speed) * 1e6)
        val clipStartUs = Math.round(c.start * 1e6)
        val untilFileUs = Math.round((c.sourceIn + ((slotEndUs - clipStartUs) / 1e6) * speed) * 1e6)
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            ex.selectTrack(track)
            val times = java.util.TreeSet<Long>()
            var from = untilFileUs
            repeat(PRE_ROLL_SYNC_STEPS) {
                ex.seekTo(from, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val sync = ex.sampleTime
                if (sync < 0) return null
                // (in decode order: with B-frames a later sample can carry an earlier time – read a little past the end)
                while (ex.sampleTime >= 0 && ex.sampleTime <= untilFileUs + 500_000) { if (ex.sampleTime <= untilFileUs) times += ex.sampleTime; if (!ex.advance()) break }
                // the clip's frames on the timeline (the export reads none before its in point, read 1 ms early)
                val taus = times.map(::tau).filter { it >= clipStartUs - FrameGrid.LEAD_US }
                PreRollRule.startTau(taus, visibleUs, fps)?.let { return it }
                // reached the clip's first frame: the part reads from the clip's start (its first frame is pinned there)
                if (taus.size < times.size || sync <= 0L) return clipStartUs
                if (tau(sync) < visibleUs - Math.round(PRE_ROLL_LIMIT * 1e6)) return null
                from = sync - 1
            }
            return null
        } finally {
            ex.release()
        }
    }

    private const val PRE_ROLL_SYNC_STEPS = 8
    private const val PRE_ROLL_LIMIT = 30.0
    private const val PRE_ROLL_UNKNOWN = 0.25
    private const val PRE_ROLL_MAX = 1.0

    private fun renderEnd(s: Snapshot, c: Chunk): Double {
        val total = s.sequence?.duration ?: return c.b
        val f = s.fps.takeIf { it > 0.0 } ?: return c.b
        return if (c.b + 1.0 / f <= total + 1e-9) c.b + 1.0 / f else c.b
    }

    /** The files (each with its start time, s) one after another in one file – same codec and format (see [runs]). */
    private fun concat(parts: List<Pair<File, Double>>, out: File, limitUs: Long = Long.MAX_VALUE) {
        var muxer: android.media.MediaMuxer? = null
        var track = -1
        val buf = java.nio.ByteBuffer.allocate(8 * 1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()
        try {
            for ((f, offset) in parts) {
                val ex = android.media.MediaExtractor()
                try {
                    ex.setDataSource(f.path)
                    val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ex.selectTrack(t)
                    if (muxer == null) {
                        muxer = android.media.MediaMuxer(out.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        track = muxer.addTrack(ex.getTrackFormat(t))
                        muxer.start()
                    }
                    val off = Math.round(offset * 1e6)
                    var count = 0; var firstPts = Long.MAX_VALUE; var lastPts = Long.MIN_VALUE; var maxPts = Long.MIN_VALUE
                    while (true) {
                        buf.clear()
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        val pts = ex.sampleTime
                        // (a single part cut at its end: frames at or past the limit are left out)
                        if (pts + Math.round(offset * 1e6) >= limitUs) { ex.advance(); continue }
                        if (count == 0) firstPts = pts
                        lastPts = pts; maxPts = maxOf(maxPts, pts); count++
                        info.set(0, n, pts + off, if (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, buf, info)
                        ex.advance()
                    }
                    // (what each piece holds against its place: a frame at or past the next piece's start shows twice)
                    se.gendut.klipp.CrashReport.note("pre-render: join piece at ${"%.3f".format(java.util.Locale.ROOT, offset)} s: $count frames, first ${firstPts} µs, last ${lastPts} µs, max ${maxPts} µs")
                } finally {
                    ex.release()
                }
            }
        } finally {
            muxer?.let { runCatching { it.stop() }; it.release() }
        }
    }

    /**
     * Diagnostics (the agent bridge): the files that exist for [chunks] – each chunk's own ("chunk") and each run's joined
     * one ("join") – with where they begin on the timeline.
     */
    fun diagFiles(chunks: List<Chunk>): List<Triple<String, Double, File>> {
        val out = ArrayList<Triple<String, Double, File>>()
        for (c in chunks.sortedBy { it.a }) fileOf(c.hash)?.takeIf { it.exists() && it.length() > 0 }?.let { out += Triple("chunk", c.a, it) }
        for (run in runs(chunks.filter { exists(it.hash) })) {
            if (run.size < 2) continue
            fileOf(joinHash(run))?.takeIf { it.exists() && it.length() > 0 }?.let { out += Triple("join", run.first().a, it) }
        }
        return out
    }

    /**
     * Diagnostics: [a, b] of [s] rendered into [out] as one file, without the cache – with a chunk's settings (preview
     * size, from the proxies) or, with [export], those settings from the originals.
     */
    suspend fun renderRange(
        context: Context, s: Snapshot, uriOf: (Long) -> Uri?, grades: Map<Long, ClipGrade>, a: Double, b: Double, out: File,
        export: se.gendut.klipp.engine.ExportSettings? = null,
    ): Result<Unit> {
        val short = VideoPipeline.previewShort
        val settings = export ?: renderSettings(s, short)
        // the same reading as a chunk's: from before [a] and one frame past [b] when the timeline goes on
        val end = renderEnd(s, Chunk(a, b, "", false))
        FrameHolds.await(context, FrameHolds.holdsOf(s, uriOf))
        Reversals.await(context, s, uriOf)
        FrameSnap.await(context, s) { id -> uriOf(id)?.let { if (export == null) Proxies.videoUri(it) else it } }
        val anyHdr = withContext(Dispatchers.IO) { CompositionBuilder.anyHdr(context, s, probe = true, uriOf = uriOf) }
        val built = CompositionBuilder.buildFull(
            s, uriOf, Look.NONE, overlays = false, grades = grades, hdrMode = VideoPipeline.toneMapMode(anyHdr, false),
            videoUriOf = { id -> uriOf(id)?.let { if (export == null) Proxies.videoUri(it) else it } },
            previewShort = if (export == null) short else 0, range = (a - preRollFor(context, s, uriOf, a, export = export != null))..end, visibleFrom = a, output = settings,
        ) ?: return Result.failure(IllegalStateException("nothing to render in $a–$b"))
        out.delete()
        if (end <= b) return Exporter.transform(context, built.composition, out, settings, tenBit = false, note = false, oneColor = export == null) { }
        // as a chunk: one frame past the end, cut away again (see renderEnd)
        val tmp = File(out.path + ".tmp")
        tmp.delete()
        val r = Exporter.transform(context, built.composition, tmp, settings, tenBit = false, note = false, oneColor = export == null) { }
        if (r.isFailure) { tmp.delete(); return r }
        return runCatching { concat(listOf(tmp to 0.0), out, limitUs = Math.round((b - a) * 1e6) - 500) }.also { tmp.delete() }
    }

    private fun shiftSrc(src: SegSrc, by: Double): SegSrc = when (src) {
        is SegSrc.Media -> src.copy(srcIn = src.srcIn + by * src.speed)
        is SegSrc.Still -> src
    }

    /**
     * The ready chunks as playback segments: adjacent ones as a joined file when it exists (one item in the player – no
     * switch between the chunks), otherwise one by one.
     */
    private fun renderedSegs(ready: List<Chunk>): List<Seg> {
        val out = ArrayList<Seg>()
        fun add(hash: String, a: Double, b: Double, at: Double = 0.0) {
            val id = idOf(hash)
            fileOf(hash)?.let { files[id] = it; it.setLastModified(System.currentTimeMillis()) }
            out += Seg(a, b - a, SegSrc.Media(id, at, 1.0), null)
        }
        for (run in runs(ready)) {
            val joined = run.size > 1 && run.none { borrowed(it) != null } && exists(joinHash(run))
            if (joined) add(joinHash(run), run.first().a, run.last().b)
            else for (c in run) borrowed(c)?.let { (h, at) -> add(h, c.a, c.b, at) } ?: add(c.hash, c.a, c.b)
        }
        return out
    }

    /** The files the substitution currently uses (playback's key: if they change the player is rebuilt). */
    fun usedFiles(chunks: List<Chunk>): String = renderedSegs(chunks.filter { isReady(it) }).joinToString(",") { it.src.item.toString() }

    /**
     * Render the chunks [todo] (those not already ready), one at a time. [canWork] = whether rendering may run now (not
     * during playback – it would take power from it); otherwise it waits. [onChunk] after every finished chunk.
     */
    /**
     * Runs [block] for as long as [canWork] holds: when it no longer does, the block is cancelled and the answer is null
     * (cancelled from outside it is cancelled as usual). Checked on the caller's thread.
     */
    internal suspend fun <T> whileAllowed(canWork: () -> Boolean, block: suspend () -> T): T? = kotlinx.coroutines.coroutineScope {
        val job = async { block() }
        val watch = launch { while (canWork()) kotlinx.coroutines.delay(100); job.cancel() }
        try {
            job.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            ensureActive()
            null
        } finally {
            watch.cancel()
        }
    }

    /**
     * The chunks the timeline wants rendered now (their keys), and a count that goes up each time that is worked out
     * again. A render under way finishes the chunk it is on if that is still wanted – an edit elsewhere, a zoom or a
     * scroll must not throw away its work – and then leaves the rest to a render with the new timeline.
     */
    @Volatile var wanted: Set<String> = emptySet()
    @Volatile var generation = 0

    suspend fun render(
        context: Context, s: Snapshot, uriOf: (Long) -> Uri?, grades: Map<Long, ClipGrade>, todo: List<Chunk>,
        canWork: () -> Boolean, onChunk: () -> Unit,
    ): Result<Int> = renderLock.withLock {
        // automatic and in the background: whatever goes wrong fails this render, never the app
        try {
            renderLocked(context, s, uriOf, grades, todo, canWork, onChunk)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Whether a video clip within [a, b] (s) has its proxy queued or being made. */
    private fun proxyPending(s: Snapshot, uriOf: (Long) -> Uri?, a: Double, b: Double): Boolean {
        if (Proxies.progress.isEmpty()) return false
        return s.sequence?.video.orEmpty().any { t ->
            t.clips.any { c -> c.start < b && c.start + c.duration > a && uriOf(c.item)?.let { Proxies.isPending(it) } == true }
        }
    }

    /**
     * One render at a time, and never beside a join: two at once wrote the same temporary file – a chunk then failed
     * with "could not save", and two joins of the same run made one file from two muxers (in playback: the frames at
     * the join out of order, the last one before it held).
     */
    private val renderLock = kotlinx.coroutines.sync.Mutex()
    private val joinLock = Any()

    private suspend fun renderLocked(
        context: Context, s: Snapshot, uriOf: (Long) -> Uri?, grades: Map<Long, ClipGrade>, todo: List<Chunk>,
        canWork: () -> Boolean, onChunk: () -> Unit,
    ): Result<Int> {
        val d = dir ?: return Result.failure(IllegalStateException("no directory"))
        val list = todo.filter { !isReady(it) }
        if (list.isEmpty()) return Result.success(0)
        val gen = generation
        val total = list.sumOf { it.b - it.a }.coerceAtLeast(1e-6)
        var done = 0.0
        progress = 0f
        try {
            // the freeze frames and the reversed clips' files must exist (as in the export)
            FrameHolds.await(context, FrameHolds.holdsOf(s, uriOf))
            Reversals.await(context, s, uriOf)
            // the clips' first frame times (their in points on their own frames, see FrameSnap)
            FrameSnap.await(context, s) { id -> uriOf(id)?.let { Proxies.videoUri(it) } }
            val anyHdr = withContext(Dispatchers.IO) { CompositionBuilder.anyHdr(context, s, probe = true, uriOf = uriOf) }
            val short = VideoPipeline.previewShort
            var n = 0
            for (c in list) {
                while (!canWork()) kotlinx.coroutines.delay(300)
                // the timeline changed: what is left is rendered with the new one
                if (generation != gen) return Result.success(n)
                if (isReady(c)) { done += c.b - c.a; continue }
                // a clip's proxy still being made: wait for it. Rendered from the original meanwhile the chunk decoded the
                // camera file beside the proxy's transcode and the node caches (APV 4K on the vivo: "Codec exception", the
                // decoder's instances taken) – and the chunk's key holds whether a proxy is used, so it was rendered again
                // once the proxy was there.
                while (proxyPending(s, uriOf, c.a - preRollFor(context, s, uriOf, c.a, export = false), renderEnd(s, c))) {
                    waitingForProxy = true
                    if (generation != gen || c.hash !in wanted) { waitingForProxy = false; return Result.success(n) }
                    kotlinx.coroutines.delay(500)
                }
                waitingForProxy = false
                current = c.a to c.b
                currentDone = 0f
                val built = CompositionBuilder.buildFull(
                    s, uriOf, Look.NONE, overlays = false, grades = grades, hdrMode = VideoPipeline.toneMapMode(anyHdr, false),
                    videoUriOf = { id -> uriOf(id)?.let { Proxies.videoUri(it) } }, previewShort = short,
                    range = (c.a - preRollFor(context, s, uriOf, c.a, export = false))..renderEnd(s, c), visibleFrom = c.a, output = renderSettings(s, short),
                ) ?: run { done += c.b - c.a; continue }
                val tmp = File(d, "${c.hash}.tmp")
                // one more attempt if it failed (the encoder can be busy for a while, e.g. by an export)
                var r: Result<Unit> = Result.failure(IllegalStateException())
                var attempt = 0
                while (attempt <= 1) {
                    tmp.delete()
                    // playback that begins in the middle of the chunk stops it (the render takes decoders and GPU from the
                    // picture), and so does a change that makes the chunk unwanted; otherwise it is rendered again from its
                    // start once playback is over
                    val out = whileAllowed({ canWork() && c.hash in wanted }) {
                        Exporter.transform(context, built.composition, tmp, renderSettings(s, short), tenBit = false, note = false, oneColor = true) { p ->
                            progress = ((done + (c.b - c.a) * p / 100.0) / total).toFloat().coerceIn(0f, 1f)
                            currentDone = (p / 100f).coerceIn(0f, 1f)
                        }
                    }
                    if (out == null) {
                        tmp.delete()
                        progress = (done / total).toFloat()
                        se.gendut.klipp.CrashReport.note("pre-render: chunk ${"%.2f".format(java.util.Locale.ROOT, c.a)}–${"%.2f".format(java.util.Locale.ROOT, c.b)} s stopped (playing or no longer wanted)")
                        if (c.hash !in wanted || generation != gen) return Result.success(n)
                        while (!canWork()) kotlinx.coroutines.delay(300)
                        continue
                    }
                    r = out
                    if (r.isSuccess || r.exceptionOrNull() is kotlinx.coroutines.CancellationException) break
                    kotlinx.coroutines.delay(1000)
                    attempt++
                }
                if (r.isFailure) { tmp.delete(); return Result.failure(r.exceptionOrNull()!!) }
                // the frame rendered past the chunk's end (see renderEnd) is cut away again: the chunk holds [a, b)
                if (renderEnd(s, c) > c.b) {
                    val cut = File(d, "${c.hash}.cut")
                    val ok = runCatching { concat(listOf(tmp to 0.0), cut, limitUs = Math.round((c.b - c.a) * 1e6) - 500) }.isSuccess
                    tmp.delete()
                    if (!ok || !cut.renameTo(tmp)) { cut.delete(); return Result.failure(IllegalStateException("could not cut the chunk")) }
                }
                val f = File(d, "${c.hash}.mp4")
                if (!tmp.renameTo(f)) { tmp.delete(); return Result.failure(IllegalStateException("could not save")) }
                files[idOf(c.hash)] = f
                done += c.b - c.a
                n++
                progress = (done / total).toFloat()
                onChunk()
            }
            trim()
            return Result.success(n)
        } finally {
            progress = null
            current = null
            waitingForProxy = false
        }
    }

    /** The files' format: preview size (0 = full), the project frame rate, H.264 with dense keyframes. */
    private fun renderSettings(s: Snapshot, short: Int): ExportSettings {
        val q = s.sequence
        val h = if (short > 0 && q != null) minOf(short, minOf(q.width, q.height)) else 0
        val st = ExportSettings(height = h, codec = MimeTypes.VIDEO_H264, keyframeSec = 0.5f, audio = false, hdr = false, bitDepth = 8)
        return if (q == null) st else st.copy(bitrateMbps = (st.videoMbps(q.width, q.height, s.fps) * 1.5).toFloat())
    }

    /** Increments when the files were deleted (the chunks are then recomputed). */
    var cleared by mutableIntStateOf(0)
        private set

    /** Delete all rendered files. */
    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
        files.clear()
        formats.clear()
        states = states.map { it.copy(ready = false) }
        renderAll = false
        cleared++
        revision++
    }

    /** The files' size (bytes). */
    fun bytes(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0L

    /** Delete the oldest files when they take too much space. */
    private fun trim() {
        val all = dir?.listFiles()?.filter { it.name.endsWith(".mp4") }?.sortedBy { it.lastModified() } ?: return
        var sum = all.sumOf { it.length() }
        for (f in all) {
            if (sum <= MAX_BYTES) break
            sum -= f.length()
            files.entries.removeIf { it.value == f }
            f.delete()
        }
    }

    internal fun bump() { revision++ }
}
