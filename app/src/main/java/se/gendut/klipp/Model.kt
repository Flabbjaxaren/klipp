package se.gendut.klipp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.random.Random
import se.gendut.klipp.engine.valueAt

/** The sequence frame rate (set from the engine; 25 until a project exists). */
var projectFps by androidx.compose.runtime.mutableDoubleStateOf(25.0)
/** Frames per second in the timecode (integer: 29.97 shows as 30, as in Premiere/Resolve without drop frame). */
val FPS: Int get() = kotlin.math.round(projectFps).toInt().coerceAtLeast(1)
/** Length of one frame (s). */
val FRAME: Float get() = (1.0 / projectFps).toFloat()
/** [t] rounded to the nearest whole frame. */
fun snapFrame(t: Float): Float = (Math.round(t * projectFps) / projectFps).toFloat()

enum class TrackKind { VIDEO, AUDIO }

data class TrackDef(val name: String, val kind: TrackKind, val heightDp: Int)

val TRACKS = listOf(
    TrackDef("V3", TrackKind.VIDEO, 40),
    TrackDef("V2", TrackKind.VIDEO, 40),
    TrackDef("V1", TrackKind.VIDEO, 52),
    TrackDef("A1", TrackKind.AUDIO, 44),
    TrackDef("A2", TrackKind.AUDIO, 44),
    TrackDef("A3", TrackKind.AUDIO, 44),
)

enum class ClipKind(val bg: Color, val fg: Color, val track: TrackKind) {
    VIDEO(Color(0xFF3A4688), Color(0xFFDDE1FF), TrackKind.VIDEO),
    TITLE(Color(0xFF5E2C55), Color(0xFFFFD7F0), TrackKind.VIDEO),
    ADJUST(Color(0xFF4A4E5A), Color(0xFFE2E2E9), TrackKind.VIDEO),
    AUDIO(Color(0xFF1D5236), Color(0xFFB5F5CC), TrackKind.AUDIO),
    MUSIC(Color(0xFF2E4A5E), Color(0xFFC6E7FF), TrackKind.AUDIO),
}

/** Times in seconds. srcIn/srcLen describe the source media so trims have handles. */
data class Clip(
    val id: Long,
    val name: String,
    val kind: ClipKind,
    val track: Int,
    val start: Float,
    val length: Float,
    val srcIn: Float,
    val srcLen: Float,
    val scene: Int,
    /** The media item in the engine (0 in the prototype). */
    val item: Long = 0,
    /** Playback speed (1 = normal). */
    val speed: Float = 1f,
    /** Link group (video ↔ audio from the same source). */
    val link: Long? = null,
    /** Disabled clip: gray in the timeline, not seen/heard. */
    val enabled: Boolean = true,
    /** Freeze frame (the same frame for the whole clip). */
    val hold: Boolean = false,
    /** Plays in reverse. */
    val reverse: Boolean = false,
    /** Compound clip: clips with the same group are selected, moved and graded together. */
    val group: Long? = null,
) {
    val end: Float get() = start + length
}

/** Mock scenes for the monitor: sky, ground, sun. */
val SCENES = listOf(
    Triple(Color(0xFF2F2A26), Color(0xFF4A3D33), Color(0xFF5A4A3E)),
    Triple(Color(0xFF1A2230), Color(0xFF26334A), Color(0xFF3B4C6B)),
    Triple(Color(0xFF233025), Color(0xFF2E4A33), Color(0xFF6B8C5A)),
    Triple(Color(0xFF34242C), Color(0xFF4F2F3A), Color(0xFFC77D6B)),
    Triple(Color(0xFF1C2B33), Color(0xFF214452), Color(0xFF7DD5C6)),
)

fun formatTc(sec: Float): String {
    // frame number at the exact rate, split up using the nominal rate (timecode without drop frame)
    val totalFrames = (sec.coerceAtLeast(0f) * projectFps + 1e-4).toInt()
    val f = totalFrames % FPS
    val s = (totalFrames / FPS) % 60
    val m = (totalFrames / FPS / 60) % 60
    val h = totalFrames / FPS / 3600
    return "%02d:%02d:%02d:%02d".format(h, m, s, f)
}

/**
 * A typed timecode → seconds (null = not a time). Digits only are read as in Premiere, filled from the right
 * (hhmmssff: "1512" = 15 s 12 frames); with separators (: . , ;) the groups are read from the right (ss:ff, mm:ss:ff,
 * hh:mm:ss:ff). A leading + or - moves relative to [from].
 */
fun parseTimecode(text: String, from: Float): Float? {
    var t = text.trim()
    if (t.isEmpty()) return null
    val sign = when (t[0]) { '+' -> 1; '-' -> -1; else -> 0 }
    if (sign != 0) t = t.substring(1).trim()
    val groups: List<Int> = if (t.all { it.isDigit() }) {
        if (t.isEmpty() || t.length > 8) return null
        t.padStart(8, '0').chunked(2).map { it.toInt() }
    } else {
        val parts = t.split(':', '.', ',', ';')
        if (parts.size > 4 || parts.any { p -> p.isEmpty() || !p.all { it.isDigit() } || p.length > 4 }) return null
        List(4 - parts.size) { 0 } + parts.map { it.toInt() }
    }
    val (h, m, sec, f) = groups
    val frames = ((h * 60L + m) * 60L + sec) * FPS + f
    val v = (frames / projectFps).toFloat()
    return if (sign == 0) v else (from + sign * v).coerceAtLeast(0f)
}

/** Where edits are performed when an engine is attached (FilmCraft via JNI). */
interface EditBackend {
    fun move(clip: Clip, start: Float, track: Int, insert: Boolean)
    fun trim(old: Clip, updated: Clip, ripple: Boolean)
    fun split(id: Long, at: Float)
    /** Freeze frame: the frame at [at] is held for [seconds] s, inserted into the clip (the rest is pushed later). */
    fun freezeFrame(id: Long, at: Float, seconds: Float) {}
    /** Play the clips in reverse (or forward again). */
    fun setReverse(ids: List<Long>, reverse: Boolean) {}
    /** Compound clip: group the clips (or break up their group). */
    fun setGrouped(ids: List<Long>, grouped: Boolean) {}
    fun remove(id: Long, ripple: Boolean)
    fun undo()
    fun redo()
    /** Clear the engine's redo steps (a change outside the engine, e.g. color, after an undo). */
    fun clearRedo() {}
    /** Move several clips in one step (one undo). The list is complete – linked clips don't follow on their own. */
    fun moveMany(moves: List<Triple<Clip, Float, Int>>, insert: Boolean) { for ((c, s, t) in moves) move(c, s, t, insert) }
    /** Delete several clips in one step. */
    fun removeMany(ids: List<Long>, ripple: Boolean) { for (id in ids) remove(id, ripple) }
    /** Razor through several tracks at [at] (one undo); only the tracks that have a clip there. */
    fun razorTracks(at: Float, tracks: List<Int>) {}
    /** Slip: the clip's content shifts [dSrc] seconds in the source, position and length unchanged. */
    fun slip(clip: Clip, dSrc: Float) {}
    /** Roll: the edit point between [left] and [right] moves [dt] seconds (sum of the lengths unchanged). */
    fun roll(left: Clip, right: Clip, dt: Float) {}
    /** Track setting: "locked", "enabled", "muted", "solo" or "syncLock". */
    fun setTrackFlag(track: Int, flag: String, on: Boolean) {}
    /** Disable/enable the clips (each one toggled separately, linked audio follows), one undo. */
    fun toggleEnabled(ids: List<Long>) {}
    /** Move clips and captions together (the selection marquee), one undo step. */
    fun moveGroup(moves: List<Triple<Clip, Float, Int>>, insert: Boolean, captions: List<Long>, dt: Float) { moveMany(moves, insert) }
    /** Delete captions (one undo step). */
    fun deleteCaptions(ids: List<Long>) {}
}

/** Track states (like the track header in Premiere): lock, sync lock, visible (video) / mute and solo (audio). */
data class TrackState(
    val locked: Boolean = false,
    val enabled: Boolean = true,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val syncLock: Boolean = true,
)

/** A clip's properties (Effect Controls) shown by the UI. Position in sequence pixels, null = center. */
data class ClipProps(
    val id: Long,
    val name: String,
    val isAudio: Boolean,
    val x: Float?,
    val y: Float?,
    val scale: Float = 100f,
    val rotation: Float = 0f,
    val opacity: Float = 100f,
    val levelDb: Float = 0f,
    val title: String? = null,
    val textSize: Float = 100f,
    /** 0xRRGGBB */
    val textColor: Int = 0xFFFFFF,
    /** The title's template and animation (`klipp:mall=…;in=…`). */
    val titleLook: String = "",
    val fadeIn: Float? = null,
    val fadeOut: Float? = null,
    val seqW: Int = 1920,
    val seqH: Int = 1080,
    /** Keyframes per property ("position", "scale", "rotation", "opacity", "level"). */
    val kf: Map<String, List<UiKf>> = emptyMap(),
    val clipStart: Float = 0f,
    val clipEnd: Float = 0f,
    /** Audio effects (the engine's rack). */
    val afx: List<se.gendut.klipp.engine.AfxSnap> = emptyList(),
) {
    val isTitle get() = title != null
}

/**
 * A keyframe in the UI: timeline time, media time (the engine's id for the keyframe) and interpolation; for the value
 * graph also its value(s) [v] and the ease handles' influence ([inInf], [outInf], 0..1).
 */
/** An audio clip's level (dB) and its keyframes ("level"), for the volume line in the timeline. */
data class ClipLevel(val db: Float, val kf: List<UiKf> = emptyList()) {
    private val keys = kf.sortedBy { it.t }.map { se.gendut.klipp.engine.Kf(it.mt, it.t.toDouble(), it.v, it.interp, it.inInf, it.outInf) }
    val animated get() = keys.isNotEmpty()
    /** The level at timeline time [t], evaluated as the engine does. */
    fun at(t: Float): Float = keys.valueAt(t.toDouble())?.toFloat() ?: db

    companion object {
        /** The level range the line edits (as the clip's volume slider). */
        const val MIN_DB = -60f
        const val MAX_DB = 15f
        // the mixer's fader taper (the engine's): dB → position 0 (bottom) … 1 (top)
        private val TAPER = arrayOf(-96f to 0f, -60f to 0.05f, -40f to 0.14f, -30f to 0.24f, -20f to 0.38f, -12f to 0.52f, -6f to 0.64f, 0f to 0.76f, 6f to 0.88f, 15f to 1f)
        fun pos(db: Float): Float {
            if (db <= TAPER[0].first) return 0f
            for (i in 0 until TAPER.size - 1) {
                val (d0, p0) = TAPER[i]; val (d1, p1) = TAPER[i + 1]
                if (db <= d1) return p0 + (p1 - p0) * (db - d0) / (d1 - d0)
            }
            return 1f
        }
        fun dbAt(pos: Float): Float {
            val p = pos.coerceIn(0f, 1f)
            for (i in 0 until TAPER.size - 1) {
                val (d0, p0) = TAPER[i]; val (d1, p1) = TAPER[i + 1]
                if (p <= p1) return d0 + (d1 - d0) * (p - p0) / (p1 - p0)
            }
            return TAPER.last().first
        }
    }
}

data class UiKf(
    val t: Float, val mt: Long, val interp: String,
    val v: List<Double> = emptyList(),
    val inInf: Double = se.gendut.klipp.engine.DEFAULT_INFLUENCE,
    val outInf: Double = se.gendut.klipp.engine.DEFAULT_INFLUENCE,
)

/**
 * A transition drawn by the timeline. anchor: −1 = fade in (locked to the clip's start), +1 = fade out (locked to the
 * end), 0 = cross dissolve (centered on the edit point, [cut]).
 */
data class UiTransition(
    val id: Long, val track: Int, val start: Float, val end: Float, val anchor: Int = 0, val cut: Float = (start + end) / 2f,
    /** The engine's effect (see TransitionType) and direction. */
    val effect: String = "cross_dissolve", val reverse: Boolean = false,
    /** The clips before and after (null = fade in/out); for pre-rendering. */
    val from: Long? = null, val to: Long? = null,
) {
    /** New start/end when an edge is dragged to time [t]. */
    fun resized(leftEdge: Boolean, t: Float, minLen: Float): Pair<Float, Float> = when (anchor) {
        -1 -> start to maxOf(t, start + minLen)
        1 -> minOf(t, end - minLen) to end
        else -> {
            val half = maxOf(kotlin.math.abs(t - cut), minLen / 2f)
            (cut - half) to (cut + half)
        }
    }
}

/** An edit point: clip [left] ends where [right] starts (time [t]) on track [track]. */
data class UiCut(val track: Int, val t: Float, val left: Long, val right: Long)

/** A caption in the timeline. [hidden] = the track is turned off (not shown in the image, drawn gray). */
data class UiCaption(val id: Long, val start: Float, val end: Float, val text: String, val track: Long = 0, val hidden: Boolean = false)

/** The engine side of properties, titles, transitions and captions. */
interface PropsBackend {
    fun props(id: Long): ClipProps?
    /** Change motion/opacity/volume; null = unchanged. One undo step. */
    fun setProps(id: Long, x: Float? = null, y: Float? = null, scale: Float? = null, rotation: Float? = null, opacity: Float? = null, levelDb: Float? = null)
    fun resetMotion(id: Long)
    /** Cross dissolve at the clip's in or out edge; seconds = null removes it. */
    fun setTransition(id: Long, edgeIn: Boolean, seconds: Float?)
    /** Change the length of an existing transition (keeps its alignment). */
    fun resizeTransition(transition: Long, seconds: Float)
    fun removeTransition(transition: Long)
    /** Change the transition's type (the engine's effect, see TransitionType) and direction; position and length are kept. */
    fun setTransitionType(transition: Long, effect: String, reverse: Boolean) {}
    /**
     * New transition of type [effect] (the engine's effect; audio: always a crossfade) and length [seconds], centered on
     * the edit point where clip [right] starts. [onAdded] receives the new transition's id.
     */
    fun addCutTransition(right: Long, effect: String, seconds: Float, onAdded: (Long) -> Unit = {}) {}
    /** Save the project soon (something saved with it changed outside the engine, e.g. what gets pre-rendered). */
    fun requestSave() {}
    fun addTitle(text: String, at: Float, look: String = "")
    fun setTitle(id: Long, text: String? = null, size: Float? = null, color: Int? = null, look: String? = null)
    fun importCaptions()
    fun setCaptionText(id: Long, text: String) {}
    /** The stopwatch: turn animation on (keyframe at the playhead) or off (all keyframes removed, the value at the playhead stays). */
    fun toggleAnimation(id: Long, key: String, at: Float) {}
    /** Add a keyframe at [at] with the current value, or remove the keyframe already there. */
    fun toggleKeyframe(id: Long, key: String, at: Float) {}
    fun moveKeyframe(id: Long, key: String, mt: Long, to: Float) {}
    fun setInterpolation(id: Long, key: String, mt: Long, interp: String) {}
    /**
     * The keyframe's ease (see KfEase): linear, easy ease (both sides, 33 % influence), ease in, ease out or hold.
     * One undo step.
     */
    fun setEase(id: Long, key: String, mt: Long, ease: String) {}
    /**
     * The ease handles' influence (0..1); null = unchanged. [interp] (engine name, e.g. "EaseOut") is set first when the
     * dragged side didn't ease yet – one undo step.
     */
    fun setInfluence(id: Long, key: String, mt: Long, inInf: Double?, outInf: Double?, interp: String? = null) {}
    /** The keyframe's value (position: x, y). */
    fun setKeyframeValue(id: Long, key: String, mt: Long, v: List<Double>) {}
    /** The volume line in the timeline: a level keyframe at [at] with the value the line has there. */
    fun addLevelKey(id: Long, at: Float) {}
    /** A level keyframe dragged on the line: to timeline time [to] and [db]. One undo step. */
    fun moveLevelKey(id: Long, mt: Long, to: Float, db: Double) {}
    /** The line dragged between keyframes: those keyframes get new values (none: the clip's level is [db]). One undo step. */
    fun setLevelLine(id: Long, keys: List<Pair<Long, Double>>, db: Double) {}
    fun deleteLevelKey(id: Long, mt: Long) {}
    fun deleteCaption(id: Long) {}
    /** Move a caption [dt] seconds (the engine keeps it clear of its neighbors). One undo step. */
    fun moveCaption(id: Long, dt: Float) {}
    /** Drag an edge [dt] seconds (in = start). */
    fun trimCaption(id: Long, inEdge: Boolean, dt: Float) {}
    /** New caption at [at]; [onAdded] receives its id. */
    fun addCaption(at: Float, onAdded: (Long) -> Unit = {}) {}
    fun splitCaption(id: Long, at: Float) {}
    /** The track's style (applies to all lines on the track), e.g. "font" to "Serif", "size" to 60. */
    fun setCaptionStyle(track: Long, values: Map<String, Any>) {}
    fun captionStyle(track: Long): se.gendut.klipp.engine.CaptionStyle? = null
    /** Audio effects on an audio clip (the engine's effect id, e.g. "denoise"). */
    fun addAudioFx(id: Long, effect: String) {}
    fun removeAudioFx(id: Long, index: Int) {}
    fun toggleAudioFx(id: Long, index: Int) {}
    fun setAudioFxParam(id: Long, index: Int, param: String, value: Double) {}
    /** Several parameters at once (one undo step) – e.g. an EQ handle that changes frequency and level. */
    fun setAudioFxParams(id: Long, index: Int, values: Map<String, Double>) { values.forEach { (k, v) -> setAudioFxParam(id, index, k, v) } }
    fun setCaptionsVisible(visible: Boolean)
    val captionsVisible: Boolean
    val hasCaptionTrack: Boolean
}

/**
 * The frame at a clip edge being trimmed: media item and source time (s). [pair] = the frame on the other side of the
 * edit point (the neighboring clip's), shown beside it – on the left when [pairLeft].
 */
data class TrimView(val item: Long, val sec: Double, val pair: TrimView? = null, val pairLeft: Boolean = false)

class EditorState(initial: List<Clip> = initialClips()) {
    var clips by mutableStateOf(initial)
        private set
    var backend: EditBackend? = null
    var subtitle by mutableStateOf(Str.t(R.string.model_subtitle_prototype))
    /** Target tracks for the source's video/audio (UI index: V1 = 2, A1 = 3). */
    var targetVideo by mutableIntStateOf(2)
    var targetAudio by mutableIntStateOf(3)
    var onTargetTrack: ((Int) -> Unit)? by mutableStateOf(null)
    var onAddMarker: ((Float) -> Unit)? by mutableStateOf(null)
    var onSpeed: ((Long, Int) -> Unit)? by mutableStateOf(null)
    /** Speed ramp on a clip (set when the engine is attached). */
    var onSpeedRamp: ((Long, se.gendut.klipp.engine.SpeedRamp) -> Unit)? by mutableStateOf(null)
    /** Linked selection (Premiere: Linked Selection): video and audio follow each other. */
    var linkedSelection by mutableStateOf(true)
    var onLinkedSelection: ((Boolean) -> Unit)? by mutableStateOf(null)

    /** Clips that follow [c] when it is moved/trimmed (same link group, linked selection on). */
    fun partnersOf(c: Clip): List<Clip> =
        if (!linkedSelection || c.link == null) emptyList() else clips.filter { it.id != c.id && it.link == c.link && it.id !in removing }
    /** Uri for a clip's media (filmstrip in the timeline). */
    var uriOfItem: ((Long) -> android.net.Uri?)? by mutableStateOf(null)
    /** Copy a clip to the source panel (set when the engine is attached). */
    var onCopyToSource: ((Long) -> Unit)? by mutableStateOf(null)
    /** Properties, titles etc. (set when the engine is attached). */
    var props: PropsBackend? by mutableStateOf(null)
    /** The properties panel is open for the selected clip. */
    var inspecting by mutableStateOf(false)
    /** Live values for the selected clip while a slider/gesture is dragged (previewed in the image). */
    var live by mutableStateOf<ClipProps?>(null)
    var liveToken = 0
    /** The playhead is moved by hand (scrubbing) – the program monitor shows stills meanwhile. */
    var scrubbing by mutableStateOf(false)
    /** While a clip edge is trimmed: the frame at the edit point (source time), as in Premiere. */
    var trimView by mutableStateOf<TrimView?>(null)
    /** Each audio clip's level and its keyframes (the volume line on the clips). */
    var clipLevels by mutableStateOf<Map<Long, ClipLevel>>(emptyMap())
    /** The volume lines on the audio clips can be edited (taller audio tracks, the line takes touches). */
    var volLines by mutableStateOf(false)
    /** Track volume automation per audio track (A1–A3): (time, dB). Drawn as a line in the timeline. */
    var trackVolKf by mutableStateOf<List<List<Pair<Float, Float>>>>(emptyList())
    /** An audio effect slider is being dragged: (clip, effect index, parameter, value) to the playback chains. */
    var onFxLive: ((Long, Int, String, Double) -> Unit)? = null
    var transitions by mutableStateOf(listOf<UiTransition>())
    /** Selected transition (tap it in the timeline). */
    var selectedTransition by mutableStateOf<Long?>(null)
    /** Selected edit point (tap the boundary between two clips): where a transition can be added. */
    var selectedCut by mutableStateOf<UiCut?>(null)

    /** Select edit point [cut] (everything else is deselected). */
    fun selectCut(cut: UiCut) {
        clearSelection()
        selectedCut = cut
    }
    var captions by mutableStateOf(listOf<UiCaption>())
    /** A caption track exists (even hidden) – the timeline then shows its caption lane. */
    var hasCaptionLane by mutableStateOf(false)
    /** Captions are visible (some caption track is on). */
    var captionsOn by mutableStateOf(true)
    /** Show/hide the captions (the eye on the TXT lane). */
    var onCaptionsVisible: ((Boolean) -> Unit)? = null
    /** Selected caption in the timeline, and whether its editor is open. */
    var selectedCaption by mutableStateOf<Long?>(null)
    var editingCaption by mutableStateOf(false)
    var playhead by mutableFloatStateOf(14f)
    var playing by mutableStateOf(false)
    var selected by mutableStateOf<Long?>(null)
    /** Track states, V3 V2 V1 A1 A2 A3 (indexed like TRACKS). */
    var trackStates by mutableStateOf(List(TRACKS.size) { TrackState() })
    /** Short message to the user (set by the app to a toast). */
    var onNotice: ((String) -> Unit)? = null
    fun locked(track: Int) = trackStates.getOrNull(track)?.locked == true

    fun setTrackFlag(track: Int, flag: String, on: Boolean) {
        backend?.let { it.setTrackFlag(track, flag, on); return }
        trackStates = trackStates.mapIndexed { i, t ->
            if (i != track) t else when (flag) {
                "locked" -> t.copy(locked = on); "enabled" -> t.copy(enabled = on); "muted" -> t.copy(muted = on)
                "solo" -> t.copy(solo = on); "syncLock" -> t.copy(syncLock = on); else -> t
            }
        }
    }

    /** Slip with the trim tool: the content shifts in the source (limited to what exists). Returns the shift. */
    fun slip(id: Long, dSrc: Float): Float {
        val c = clips.firstOrNull { it.id == id } ?: return 0f
        if (locked(c.track)) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return 0f }
        val d = slipLimit(c, dSrc)
        if (abs(d) < 1e-4f) return 0f
        backend?.let { it.slip(c, d); return d }
        val ids = setOf(c.id) + partnersOf(c).map { it.id }
        commit(clips.map { if (it.id in ids) it.copy(srcIn = it.srcIn + d) else it })
        return d
    }

    /** How far the content can shift: not before the source's start or past its end. */
    fun slipLimit(c: Clip, dSrc: Float): Float = dSrc.coerceIn(-c.srcIn, (c.srcLen - c.srcIn - c.length * c.speed).coerceAtLeast(0f))

    /** Roll with the trim tool: the edit point between two clips moves. Returns the movement. */
    fun roll(leftId: Long, rightId: Long, dt: Float): Float {
        val l = clips.firstOrNull { it.id == leftId } ?: return 0f
        val r = clips.firstOrNull { it.id == rightId } ?: return 0f
        if (locked(l.track)) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return 0f }
        val d = rollLimit(l, r, dt)
        if (abs(d) < 1e-4f) return 0f
        backend?.let { it.roll(l, r, d); return d }
        commit(clips.map {
            when (it.id) {
                l.id -> it.copy(length = it.length + d)
                r.id -> it.copy(start = it.start + d, length = it.length - d, srcIn = it.srcIn + d * it.speed)
                else -> it
            }
        })
        return d
    }

    /** The edit point can move as far as there is media: the left clip's source to the right, the right clip's to the left; at least one frame remains. */
    fun rollLimit(l: Clip, r: Clip, dt: Float): Float {
        val frame = FRAME
        val maxRight = minOf((l.srcLen - l.srcIn - l.length * l.speed) / l.speed, r.length - frame)
        val maxLeft = minOf(r.srcIn / r.speed, l.length - frame)
        return dt.coerceIn(-maxLeft.coerceAtLeast(0f), maxRight.coerceAtLeast(0f))
    }

    /** Two clips on the same track that meet (left ends where right starts). */
    fun cutPairs(): List<Pair<Clip, Clip>> {
        val out = ArrayList<Pair<Clip, Clip>>()
        for ((_, list) in clips.filter { it.id !in removing }.groupBy { it.track }) {
            val sorted = list.sortedBy { it.start }
            for (i in 0 until sorted.size - 1) if (abs(sorted[i].end - sorted[i + 1].start) < 0.02f) out += sorted[i] to sorted[i + 1]
        }
        return out
    }

    /** Several selected clips (lasso). Empty = normal selection via [selected]. */
    var multi by mutableStateOf<Set<Long>>(emptySet())
    /** Captions selected with the marquee: moved and deleted together (and with selected clips). */
    var multiCaptions by mutableStateOf<Set<Long>>(emptySet())
    /** Something is selected as a group (clips and/or captions). */
    val groupSelected: Boolean get() = multi.isNotEmpty() || multiCaptions.isNotEmpty()
    var markers by mutableStateOf(listOf(9f, 31f))
    var magnet by mutableStateOf(true)
    /** Playhead locked to the center; the timeline is dragged under it. */
    var centerPlayhead by mutableStateOf(false)

    /** The clip and its linked partners (if linked selection is on). */
    fun withPartners(ids: Collection<Long>): Set<Long> {
        val out = LinkedHashSet<Long>(ids)
        // compound clips: the whole group (with linked audio)
        for (id in ids) clips.firstOrNull { it.id == id }?.group?.let { g -> clips.filter { it.group == g && it.id !in removing }.forEach { out += it.id } }
        for (id in out.toList()) clips.firstOrNull { it.id == id }?.let { c -> partnersOf(c).forEach { out += it.id } }
        return out
    }

    /** The clips in [c]'s compound clip (except [c]); empty without a group. */
    fun groupMembers(c: Clip): List<Clip> = c.group?.let { g -> clips.filter { it.group == g && it.id != c.id && it.id !in removing } }.orEmpty()

    /** Tap on a clip: it alone, or its whole compound clip. */
    fun tapSelect(id: Long) {
        val c = clips.firstOrNull { it.id == id } ?: return
        selectedTransition = null
        selectedCut = null
        if (groupMembers(c).isNotEmpty()) {
            if (id in multi) clearSelection() else lassoSelect(setOf(id))
        } else {
            selected = if (selected == id) null else id
        }
    }

    /** The selected clips as a compound clip (or, [grouped] = false, dissolved). */
    fun setGrouped(ids: Collection<Long>, grouped: Boolean) {
        val cs = ids.mapNotNull { id -> clips.firstOrNull { it.id == id } }
        if (cs.any { locked(it.track) }) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return }
        if (grouped && cs.map { it.link?.let { l -> "l$l" } ?: "c${it.id}" }.toSet().size < 2) { onNotice?.invoke(Str.t(R.string.model_select_two_clips)); return }
        backend?.setGrouped(cs.map { it.id }, grouped)
    }

    /** The lasso caught [ids]: one clip (with partners) becomes a normal selection, more become a multi-selection. */
    fun lassoSelect(ids: Set<Long>, add: Boolean = false) {
        val all = withPartners(ids) + (if (add) multi + listOfNotNull(selected).let { withPartners(it) } else emptySet())
        val groups = all.mapNotNull { id -> clips.firstOrNull { it.id == id } }.map { it.link?.let { l -> "l$l" } ?: "c${it.id}" }.toSet()
        selectedTransition = null
        selectedCut = null
        // with selected captions even a single clip is part of the group (moves with them)
        if (groups.size <= 1 && (linkedSelection || all.size <= 1) && multiCaptions.isEmpty()) {
            multi = emptySet()
            selected = all.firstOrNull { id -> clips.firstOrNull { it.id == id }?.kind?.track == TrackKind.VIDEO } ?: all.firstOrNull()
        } else {
            multi = all
            selected = null
        }
    }

    /** Tap on a clip while several are selected: add or remove it (with partners). */
    fun toggleMulti(id: Long) {
        val g = withPartners(listOf(id))
        val next = if (id in multi) multi - g else multi + g
        lassoSelect(next)
        if (next.isEmpty()) { multi = emptySet(); selected = null }
    }

    fun clearSelection() { selected = null; multi = emptySet(); multiCaptions = emptySet(); selectedTransition = null; selectedCaption = null; selectedCut = null }

    /**
     * The selection marquee: clips [clipIds] and captions [captionIds]. A lone caption (no clips) becomes a normal
     * selection; otherwise everything is selected as a group.
     */
    fun lassoSelectAll(clipIds: Set<Long>, captionIds: Set<Long>, add: Boolean) {
        val caps = if (add) multiCaptions + listOfNotNull(selectedCaption) + captionIds else captionIds
        if (caps.size == 1 && clipIds.isEmpty() && !(add && (multi.isNotEmpty() || selected != null))) {
            multiCaptions = emptySet(); multi = emptySet(); selected = null; selectedTransition = null
            selectedCaption = caps.first()
            return
        }
        multiCaptions = caps
        selectedCaption = null
        if (clipIds.isEmpty() && !add) { multi = emptySet(); selected = null; selectedTransition = null }
        else lassoSelect(clipIds, add)
        // a group with captions: a single selected clip belongs to the group
        if (caps.isNotEmpty()) selected?.let { s -> multi = withPartners(listOf(s)); selected = null }
    }

    /** Tap on a caption while a group is selected: add or remove it. */
    fun toggleMultiCaption(id: Long) {
        multiCaptions = if (id in multiCaptions) multiCaptions - id else multiCaptions + id
        selectedCaption = null
        if (multiCaptions.isEmpty() && multi.isEmpty()) selected = null
    }

    /** Lowest and highest allowed shift (s) for the selected captions – like the engine (captions.move). */
    fun captionGroupRange(ids: Set<Long> = multiCaptions): ClosedFloatingPointRange<Float> {
        var lo = -Float.MAX_VALUE
        var hi = Float.MAX_VALUE
        for (c in captions.filter { it.id in ids }) {
            lo = maxOf(lo, -c.start)
            for (o in captions) {
                if (o.id in ids || o.track != c.track) continue
                if (o.end <= c.start + 1e-4f) lo = maxOf(lo, o.end - c.start)
                else if (o.start >= c.end - 1e-4f) hi = minOf(hi, o.start - c.end)
            }
        }
        return minOf(lo, 0f)..maxOf(hi, 0f)
    }

    /** Move the whole group (selected clips and captions) [dt] seconds – one undo step. */
    fun moveGroup(dt: Float, insert: Boolean) {
        val caps = multiCaptions.filter { id -> captions.any { it.id == id } }
        if (caps.isEmpty()) { moveMarked(dt, insert); return }
        val list = clips.filter { it.id in multi && !locked(it.track) }
        if (list.size < clips.count { it.id in multi }) onNotice?.invoke(Str.t(R.string.model_locked_not_moved))
        val r = captionGroupRange(caps.toSet())
        var d = dt.coerceIn(r.start, r.endInclusive)
        if (list.isNotEmpty()) d = d.coerceAtLeast(-list.minOf { it.start })
        if (d == 0f) return
        backend?.let { b -> b.moveGroup(list.map { Triple(it, it.start + d, it.track) }, insert, caps, d); return }
        val moving = list.map { it.id }.toSet()
        commit(clips.map { if (it.id in moving) it.copy(start = it.start + d) else it })
        captions = captions.map { if (it.id in caps) it.copy(start = it.start + d, end = it.end + d) else it }
    }

    /** Move all multi-selected clips [dt] seconds (tracks are kept). */
    fun moveMarked(dt: Float, insert: Boolean) {
        val list = clips.filter { it.id in multi && !locked(it.track) }
        if (list.size < clips.count { it.id in multi }) onNotice?.invoke(Str.t(R.string.model_locked_not_moved))
        if (list.isEmpty() || dt == 0f) return
        val d = dt.coerceAtLeast(-list.minOf { it.start })
        if (d == 0f) return
        backend?.let { b -> b.moveMany(list.map { Triple(it, it.start + d, it.track) }, insert); return }
        val moving = list.map { it.id }.toSet()
        commit(clips.map { if (it.id in moving) it.copy(start = it.start + d) else it })
    }

    /** Pending group deletions (clips, ripple) – several can be in progress at once (a new one doesn't overwrite the previous). */
    private val batches = ArrayList<Pair<Set<Long>, Boolean>>()

    /** Delete the group: selected captions and clips. */
    fun removeGroup(ripple: Boolean) {
        val caps = multiCaptions.filter { id -> captions.any { it.id == id } }
        multiCaptions = emptySet()
        if (caps.isNotEmpty()) {
            backend?.deleteCaptions(caps) ?: run { captions = captions.filter { it.id !in caps } }
        }
        if (multi.isNotEmpty()) removeMarked(ripple)
    }

    /** Delete all multi-selected clips (one undo). */
    fun removeMarked(ripple: Boolean) {
        val all = multi.filter { id -> clips.any { it.id == id } }.toSet()
        val ids = all.filter { id -> clips.first { it.id == id }.let { !locked(it.track) } }.toSet()
        if (ids.size < all.size) onNotice?.invoke(Str.t(R.string.model_locked_left))
        if (ids.isEmpty()) return
        multi = emptySet()
        selected = null
        batches += ids to ripple
        for (id in ids) if (id !in removing) removing += id
    }

    /** The razor is dragged across tracks [tracks] at [at]: all clips crossing that time are split (one undo). */
    fun razorAcross(at: Float, tracks: IntRange): Int {
        val cut = TimelineTools.razorCuts(clips.filter { it.id !in removing && !locked(it.track) }, at, tracks)
        if (cut.isEmpty()) return 0
        backend?.let { it.razorTracks(at, cut.map { c -> c.track }.distinct().sorted()); return cut.size }
        var next = clips
        for (c in cut) {
            val leftLen = at - c.start
            val right = c.copy(id = newId(), start = at, length = c.length - leftLen, srcIn = c.srcIn + leftLen * c.speed)
            popFrom[right.id] = Pair(at + 0.6f, c.track.toFloat())
            next = next.map { if (it.id == c.id) c.copy(length = leftLen) else it } + right
        }
        commit(next)
        return cut.size
    }

    /** Clips being animated away before they are removed from the model. */
    val removing = mutableStateListOf<Long>()
    private val pendingRemove = mutableMapOf<Long, Boolean>()

    /** Delay (ms) per clip for the cascade animation on ripple. */
    val rippleDelay = mutableMapOf<Long, Int>()

    /** Clip ids that should "pop" in from an offset position (split, new clips). */
    val popFrom = mutableMapOf<Long, Pair<Float, Float>>()

    private val undoStack = ArrayDeque<List<Clip>>()
    private val redoStack = ArrayDeque<List<Clip>>()
    /** Step ids in [undoStack]/[redoStack] (without engine) – like the engine's: new steps get larger ids. */
    private val localIds = ArrayDeque<Long>()
    private val localRedoIds = ArrayDeque<Long>()
    private var nextLocalStep = 1L
    var undoCount by mutableIntStateOf(0)
        private set
    var redoCount by mutableIntStateOf(0)
        private set

    // ------------------------------------------------------------------ changes outside the engine (color)
    //
    // Color changes don't live in the engine, but undo/redo must take everything in the order it was done. Each such
    // step stores the id of the engine's top undo step at the time ([SideStep.anchor]). Undo takes the latest of
    // the two: the color step if no newer engine step exists (top id ≤ anchor), otherwise the engine's.

    class SideStep(var anchor: Long, val label: String, val undo: () -> Unit, val redo: () -> Unit)

    private val side = ArrayDeque<SideStep>()
    private val sideRedo = ArrayDeque<SideStep>()
    /** The engine's undo steps (ids, oldest first) and redo steps (ids, next last), from the latest snapshot. */
    private var engineIds: List<Long> = emptyList()
    private var engineRedoIds: List<Long> = emptyList()
    private var engineSerial = -1L

    /** The timeline comes from the engine (a snapshot has been loaded) – otherwise it is local (tests, demo). */
    private var engineDriven = false
    private val usesEngine get() = backend != null || engineDriven
    private fun engineTop(): Long = if (usesEngine) engineIds.lastOrNull() ?: 0L else localIds.lastOrNull() ?: 0L
    private fun engineCanUndo() = if (usesEngine) engineIds.isNotEmpty() else undoStack.isNotEmpty()
    private fun engineCanRedo() = if (usesEngine) engineRedoIds.isNotEmpty() else redoStack.isNotEmpty()

    /** A change outside the engine as an undo step. The redo steps (the engine's too) no longer apply. */
    fun recordSide(label: String, undo: () -> Unit, redo: () -> Unit) {
        side.addLast(SideStep(engineTop(), label, undo, redo))
        if (side.size > 200) side.removeFirst()
        sideRedo.clear()
        if (usesEngine) {
            if (engineRedoIds.isNotEmpty()) { engineRedoIds = emptyList(); backend?.clearRedo() }
        } else { redoStack.clear(); localRedoIds.clear() }
        syncCounts()
    }

    /** New project opened: the color steps belong to the previous one. */
    fun clearSideHistory() {
        side.clear(); sideRedo.clear()
        syncCounts()
    }

    private var nextId = 1000L
    fun newId() = nextId++

    val projectEnd: Float get() = clips.maxOfOrNull { it.end } ?: 0f

    fun commit(newClips: List<Clip>) {
        undoStack.addLast(clips)
        localIds.addLast(nextLocalStep++)
        if (undoStack.size > 200) { undoStack.removeFirst(); localIds.removeFirst() }
        redoStack.clear()
        localRedoIds.clear()
        sideRedo.clear()
        clips = newClips
        syncCounts()
    }

    /** Undo the latest – a color step or a timeline step, in the order they were done. */
    fun undo(): Boolean {
        val s = side.lastOrNull()
        if (s != null && (!engineCanUndo() || s.anchor >= engineTop())) {
            side.removeLast()
            s.undo()
            s.anchor = engineTop()
            sideRedo.addLast(s)
            syncCounts()
            return true
        }
        backend?.let {
            if (engineIds.isEmpty()) return false
            // right here (the snapshot arrives shortly): a quick second tap must see the step as undone.
            // Before the call – if the engine answers immediately, its list is what counts.
            engineRedoIds = engineRedoIds + engineIds.last()
            engineIds = engineIds.dropLast(1)
            syncCounts()
            it.undo()
            return true
        }
        if (usesEngine) return false
        val prev = undoStack.removeLastOrNull() ?: return false
        localRedoIds.addLast(localIds.removeLast())
        redoStack.addLast(clips)
        clips = prev
        if (selected != null && clips.none { it.id == selected }) selected = null
        multi = multi.filter { id -> clips.any { it.id == id } }.toSet()
        syncCounts()
        return true
    }

    fun redo(): Boolean {
        val s = sideRedo.lastOrNull()
        if (s != null && (!engineCanRedo() || s.anchor <= engineTop())) {
            sideRedo.removeLast()
            s.redo()
            s.anchor = engineTop()
            side.addLast(s)
            syncCounts()
            return true
        }
        backend?.let {
            if (engineRedoIds.isEmpty()) return false
            engineIds = engineIds + engineRedoIds.last()
            engineRedoIds = engineRedoIds.dropLast(1)
            syncCounts()
            it.redo()
            return true
        }
        if (usesEngine) return false
        val next = redoStack.removeLastOrNull() ?: return false
        localIds.addLast(localRedoIds.removeLast())
        undoStack.addLast(clips)
        clips = next
        if (selected != null && clips.none { it.id == selected }) selected = null
        multi = multi.filter { id -> clips.any { it.id == id } }.toSet()
        syncCounts()
        return true
    }

    private fun syncCounts() {
        undoCount = (if (usesEngine) engineIds.size else undoStack.size) + side.size
        redoCount = (if (usesEngine) engineRedoIds.size else redoStack.size) + sideRedo.size
    }

    /** Places a clip and pushes following clips on the same track (insert behavior). */
    fun placeWithRipple(others: List<Clip>, moved: Clip): List<Clip> {
        var m = moved.copy(start = moved.start.coerceAtLeast(0f))
        val same = others.filter { it.track == m.track }.sortedBy { it.start }
        // Lands in the middle of a clip: jump to its end.
        same.firstOrNull { it.start < m.start - 0.001f && it.end > m.start + 0.001f }?.let {
            m = m.copy(start = it.end)
        }
        val result = others.filter { it.track != m.track }.toMutableList()
        var cursor = m.end
        var stagger = 0
        for (c in same) {
            if (c.start >= m.start - 0.001f && c.start < cursor - 0.001f) {
                val shifted = c.copy(start = cursor)
                rippleDelay[c.id] = 40 * stagger++
                result += shifted
                cursor = shifted.end
            } else {
                result += c
                if (c.start >= m.start) cursor = maxOf(cursor, c.end)
            }
        }
        result += m
        return result
    }

    /** Move a clip. With engine: overwrite (Select) or insert (Ripple). */
    fun moveClip(orig: Clip, start: Float, track: Int, insert: Boolean) {
        if (locked(orig.track) || locked(track)) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return }
        backend?.let { it.move(orig, start, track, insert); return }
        commit(placeWithRipple(clips.filter { it.id != orig.id }, orig.copy(start = start, track = track)))
    }

    /** Receives a new timeline from the engine and prepares the animations for what changed. */
    /** Older form (tests): only whether there is anything to undo/redo. */
    fun applyEngine(next: List<Clip>, canUndo: Boolean, canRedo: Boolean) =
        applyEngine(next, if (canUndo) listOf(1L) else emptyList(), if (canRedo) listOf(2L) else emptyList(), engineSerial.coerceAtLeast(0))

    fun applyEngine(next: List<Clip>, undoIds: List<Long>, redoIds: List<Long>, editSerial: Long) {
        val old = clips.associateBy { it.id }
        // cascade: changed clips per track in time order
        next.filter { o -> old[o.id]?.let { it.start != o.start || it.track != o.track } == true }
            .groupBy { it.track }
            .forEach { (_, list) -> list.sortedBy { it.start }.forEachIndexed { i, c -> rippleDelay[c.id] = 40 * i } }
        // new clips: a split pops in sideways, everything else drops in from above
        for (c in next) if (c.id !in old) {
            val wasSplit = clips.any { it.track == c.track && it.start < c.start && it.end >= c.start - 0.001f && it.kind == c.kind && it.name == c.name }
            popFrom[c.id] = if (wasSplit) Pair(c.start + 0.6f, c.track.toFloat()) else Pair(c.start, if (c.track <= 2) -3f else 8f)
        }
        clips = next
        engineDriven = true
        engineIds = undoIds
        engineRedoIds = redoIds
        // a new edit in the timeline: the color redo steps no longer apply
        if (editSerial != engineSerial) { if (engineSerial >= 0) sideRedo.clear(); engineSerial = editSerial }
        syncCounts()
        if (selected != null && next.none { it.id == selected }) selected = null
        if (multi.isNotEmpty()) multi = multi.filter { id -> next.any { it.id == id } }.toSet()
    }

    /** Freeze frame at [at] in clip [id] (within the clip; at the edge: the clip's first/last frame). */
    fun freezeFrame(id: Long, at: Float, seconds: Float) {
        val c = clips.firstOrNull { it.id == id } ?: return
        if (locked(c.track)) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return }
        if (c.kind != ClipKind.VIDEO) { onNotice?.invoke(Str.t(R.string.model_freeze_video_only)); return }
        backend?.freezeFrame(id, at.coerceIn(c.start, (c.end - 0.04f).coerceAtLeast(c.start)), seconds)
    }

    /** Play the clip in reverse (or forward again); linked audio follows. */
    fun toggleReverse(id: Long) {
        val c = clips.firstOrNull { it.id == id } ?: return
        if (locked(c.track)) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return }
        if (c.hold) { onNotice?.invoke(Str.t(R.string.model_freeze_no_direction)); return }
        if (c.kind != ClipKind.VIDEO && c.kind != ClipKind.AUDIO) { onNotice?.invoke(Str.t(R.string.model_reverse_av_only)); return }
        backend?.setReverse(listOf(id), !c.reverse)
    }

    fun split(id: Long, at: Float) {
        val c = clips.firstOrNull { it.id == id } ?: return
        if (at <= c.start + 0.04f || at >= c.end - 0.04f) return
        if (locked(c.track)) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return }
        backend?.let { it.split(id, at); return }
        val leftLen = at - c.start
        val left = c.copy(length = leftLen)
        val right = c.copy(
            id = newId(),
            start = at,
            length = c.length - leftLen,
            srcIn = c.srcIn + leftLen,
        )
        popFrom[right.id] = Pair(at + 0.6f, c.track.toFloat())
        commit(clips.map { if (it.id == id) left else it } + right)
        selected = right.id
    }

    /**
     * Disable/enable clips (Premiere's "Enable", Shift+E): a disabled clip stays but is not seen/heard and is not
     * exported. Each clip is toggled separately.
     */
    fun toggleEnabled(ids: Collection<Long>) {
        val cs = clips.filter { it.id in ids }
        val ok = cs.filter { !locked(it.track) }
        if (ok.size < cs.size) onNotice?.invoke(Str.t(R.string.model_locked_left))
        if (ok.isEmpty()) return
        backend?.let { it.toggleEnabled(ok.map { c -> c.id }); return }
        val set = ok.mapTo(HashSet()) { it.id }
        commit(clips.map { if (it.id in set) it.copy(enabled = !it.enabled) else it })
    }

    /** Speed in percent. With engine: clip.speedDuration (ripple); otherwise local. */
    fun setSpeed(id: Long, percent: Int) {
        onSpeed?.let { it(id, percent); return }
        val c = clips.firstOrNull { it.id == id } ?: return
        val f = percent / 100f
        val newLen = c.length * c.speed / f
        val delta = newLen - c.length
        trimCommit(c.copy(length = newLen, speed = f), delta)
    }

    fun requestRemove(id: Long, ripple: Boolean) {
        if (id in removing) return
        clips.firstOrNull { it.id == id }?.let { c -> if (locked(c.track)) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return } }
        pendingRemove[id] = ripple
        removing += id
        if (selected == id) selected = null
    }

    /** Called when the removal animation is done. */
    fun finishRemove(id: Long) {
        batches.firstOrNull { id in it.first }?.let { b ->
            val (ids, ripple) = b
            run {
                removing.remove(id)
                if (ids.any { it in removing }) return
                batches.remove(b)
                backend?.let { it.removeMany(ids.toList(), ripple); return }
                // local: from the back so the ripple shifts don't affect each other
                var next = clips
                for (c in clips.filter { it.id in ids }.sortedByDescending { it.start }) {
                    next = next.filter { it.id != c.id }.map {
                        if (ripple && it.track == c.track && it.start >= c.end - 0.001f) it.copy(start = it.start - c.length) else it
                    }
                }
                commit(next)
                return
            }
        }
        removing.remove(id)
        val ripple = pendingRemove.remove(id) ?: false
        val c = clips.firstOrNull { it.id == id } ?: return
        backend?.let { it.remove(id, ripple); return }
        var stagger = 0
        val next = clips.filter { it.id != id }.map {
            if (ripple && it.track == c.track && it.start >= c.end - 0.001f) {
                rippleDelay[it.id] = 45 * stagger++
                it.copy(start = it.start - c.length)
            } else it
        }
        commit(next)
    }

    fun trimCommit(updated: Clip, rippleDelta: Float) {
        val old = clips.firstOrNull { it.id == updated.id } ?: return
        if (locked(old.track)) { onNotice?.invoke(Str.t(R.string.model_track_locked)); return }
        backend?.let { it.trim(old, updated, rippleDelta != 0f); return }
        var stagger = 0
        val next = clips.map {
            when {
                it.id == updated.id -> updated
                rippleDelta != 0f && it.track == updated.track && it.start >= old.end - 0.001f -> {
                    rippleDelay[it.id] = 40 * stagger++
                    it.copy(start = (it.start + rippleDelta).coerceAtLeast(0f))
                }
                else -> it
            }
        }
        commit(next)
    }

    private val names = listOf("B-roll_strand", "C014_torg", "A009_intervju", "Drönare_hamn", "B-roll_skog", "Närbild_händer")

    /** New mock clip inserted at the playhead on V1. */
    fun addClipAtPlayhead() {
        val len = 3f + Random.nextInt(0, 4)
        val v1 = 2
        val inside = clips.firstOrNull { it.track == v1 && it.start < playhead && it.end > playhead }
        val at = inside?.let { if (playhead - it.start < it.end - playhead) it.start else it.end } ?: playhead
        val c = Clip(
            id = newId(), name = names.random(), kind = ClipKind.VIDEO, track = v1,
            start = at, length = len, srcIn = 1f, srcLen = len + 4f, scene = Random.nextInt(SCENES.size),
        )
        popFrom[c.id] = Pair(at, -3f)
        commit(placeWithRipple(clips, c))
        selected = c.id
    }

    fun snapCandidates(excludeId: Long?): List<Float> {
        val out = ArrayList<Float>()
        out += 0f
        out += playhead
        out += markers
        for (c in clips) if (c.id != excludeId && c.id !in removing) { out += c.start; out += c.end }
        return out
    }

    fun nearestSnap(times: List<Float>, candidates: List<Float>, threshold: Float): Pair<Float, Float>? {
        // Returns (offset to apply, snap point)
        var best: Pair<Float, Float>? = null
        var bestD = threshold
        for (t in times) for (c in candidates) {
            val d = abs(t - c)
            if (d < bestD) { bestD = d; best = Pair(c - t, c) }
        }
        return best
    }

    companion object {
        fun initialClips(): List<Clip> {
            var id = 1L
            fun v(name: String, track: Int, s: Float, l: Float, scene: Int, kind: ClipKind = ClipKind.VIDEO) =
                Clip(id++, name, kind, track, s, l, 2f, l + 6f, scene)
            return listOf(
                v("A003_intervju", 2, 0f, 8f, 0),
                v("B-roll_hamn", 2, 8f, 5f, 1),
                v("A003_intervju", 2, 13f, 9f, 0),
                v("C012_marknad", 2, 22f, 8f, 3),
                v("Justering", 1, 4f, 5f, 0, ClipKind.ADJUST),
                v("Namnskylt", 0, 15f, 5f, 0, ClipKind.TITLE),
                v("intervju L", 3, 0f, 8f, 0, ClipKind.AUDIO),
                v("intervju L", 3, 13f, 9f, 0, ClipKind.AUDIO),
                v("hamn_amb", 4, 8f, 5f, 1, ClipKind.AUDIO),
                v("marknad_amb", 4, 22f, 8f, 3, ClipKind.AUDIO),
                v("Musik_tema", 5, 1f, 28f, 0, ClipKind.MUSIC),
            )
        }
    }
}
