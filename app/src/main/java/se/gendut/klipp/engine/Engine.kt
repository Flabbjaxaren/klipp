package se.gendut.klipp.engine

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Ticks per second in FilmCraft (`filmcraft_time::TICKS_PER_SECOND`). */
const val TICKS_PER_SECOND = 254_016_000_000L

fun secToTicks(s: Double): Long = Math.round(s * TICKS_PER_SECOND)

/** Times in whole frames (for everything that moves edges in the timeline). */
object FrameTime {
    /** The frame rate as a fraction (n/d): the common NTSC rates exactly. */
    fun rate(fps: Double): Pair<Long, Long> {
        val f = fps.takeIf { it.isFinite() && it >= 1.0 } ?: 25.0
        val known = listOf(24000L to 1001L, 30000L to 1001L, 60000L to 1001L, 48000L to 1001L, 120000L to 1001L)
        known.firstOrNull { (n, d) -> kotlin.math.abs(f - n.toDouble() / d) < 1e-3 }?.let { return it }
        return if (kotlin.math.abs(f - Math.round(f)) < 1e-3) Math.round(f) to 1L else Math.round(f * 1000) to 1000L
    }

    /** Nearest whole frame to [sec] at [fps], in ticks (exactly the engine's frame boundary). */
    fun ticks(sec: Double, fps: Double): Long {
        val (n, d) = rate(fps)
        val f = Math.round(sec * n / d)
        val per = TICKS_PER_SECOND * d
        return if (per % n == 0L) f * (per / n) else Math.round(f.toDouble() * per / n)
    }
}

class EngineException(message: String) : Exception(message)

/**
 * Thin Kotlin side of the Rust bridge. The engine isn't thread-safe, so all calls go through a dedicated thread.
 */
class Engine {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "filmcraft").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    @Volatile private var handle: Long = 0
    @Volatile private var closed = false

    init {
        // the library is loaded in the companion; if that failed the constructor must throw (the app then shows prototype mode)
        loadError?.let { throw it }
        handle = nativeNew()
        if (handle == 0L) throw EngineException("engine could not be started")
    }

    /** The handle, or an error if the engine is closed (called only on the engine thread). */
    private fun live(): Long {
        val h = handle
        if (closed || h == 0L) throw EngineException("engine is closed")
        return h
    }

    suspend fun exec(id: String, params: JSONObject = JSONObject()): JSONObject = withContext(dispatcher) {
        val out = JSONObject(nativeExecute(live(), id, params.toString()))
        if (!out.optBoolean("ok")) throw EngineException(out.optString("error", "unknown error in $id"))
        out.optJSONObject("result") ?: JSONObject().put("value", out.opt("result"))
    }

    /** Like [exec] but returns an error instead of throwing (cancellation is rethrown). */
    suspend fun tryExec(id: String, params: JSONObject = JSONObject()): Result<JSONObject> =
        try {
            Result.success(exec(id, params))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /** The whole project state. An engine error is thrown (otherwise it became an empty timeline that looked valid). */
    suspend fun snapshot(): Snapshot = withContext(dispatcher) {
        val o = JSONObject(nativeSnapshot(live()))
        if (o.has("error") && !o.has("sequence") && !o.has("media")) throw EngineException(o.optString("error", "engine state could not be read"))
        Snapshot.parse(o)
    }

    /** Frees the engine on its own thread (after calls already running), then the thread. */
    fun close() {
        if (closed) return
        closed = true
        val free = executor.submit {
            val h = handle
            handle = 0
            if (h != 0L) nativeFree(h)
        }
        executor.shutdown()
        // wait briefly; a long call in progress frees the engine itself when it finishes
        runCatching { free.get(2, TimeUnit.SECONDS) }
    }

    companion object {
        /** Set if the library couldn't be loaded. Loaded here so nativeFx* works without an engine. */
        private val loadError: Throwable? = runCatching { System.loadLibrary("klipp_bridge") }.exceptionOrNull()

        // audio effect chains (no session needed – see rust/src/audio.rs); requires the library to be loaded
        @JvmStatic external fun nativeFxNew(json: String): Long
        @JvmStatic external fun nativeFxFree(fx: Long)
        @JvmStatic external fun nativeFxCount(): Int
        @JvmStatic external fun nativeFxSet(fx: Long, index: Int, param: String, value: Double): Boolean
        @JvmStatic external fun nativeFxLatency(fx: Long, rate: Int): Int
        @JvmStatic external fun nativeFxReset(fx: Long)
        @JvmStatic external fun nativeFxProcess(fx: Long, data: FloatArray, len: Int, channels: Int, rate: Int)
        @JvmStatic external fun nativeFxProcessDirect(fx: Long, buf: java.nio.ByteBuffer, offset: Int, frames: Int, channels: Int, float: Boolean, rate: Int): Boolean
        @JvmStatic external fun nativeMeasureWav(path: String): String
        /** Curve for a graphical effect editor: "response" (dB at Hz) or "transfer" (dB out for dB in). */
        @JvmStatic external fun nativeFxCurve(json: String, kind: String, xs: DoubleArray): DoubleArray?
        // window tracking (rust/src/track.rs): one tracker per direction, the luma plane straight from the decoder
        @JvmStatic external fun nativeTrackNew(w: Int, h: Int): Long
        @JvmStatic external fun nativeTrackFree(h: Long)
        @JvmStatic external fun nativeTrackFrame(h: Long, buf: java.nio.ByteBuffer, offset: Int, rowStride: Int, pixelStride: Int, srcW: Int, srcH: Int, reset: Boolean, cx: Float, cy: Float, rx: Float, ry: Float, shape: Int): FloatArray?
        @JvmStatic external fun nativeMotionFrame(h: Long, buf: java.nio.ByteBuffer, offset: Int, rowStride: Int, pixelStride: Int, srcW: Int, srcH: Int): FloatArray?
    }

    private external fun nativeNew(): Long
    private external fun nativeFree(h: Long)
    private external fun nativeExecute(h: Long, id: String, params: String): String
    private external fun nativeSnapshot(h: Long): String
}

data class MediaInfo(
    val id: Long,
    val name: String,
    val path: String?,
    val duration: Double,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val width: Int,
    val height: Int,
    val fps: Double,
    val markIn: Double?,
    val markOut: Double?,
    /** The bin in the media panel; null = top level. */
    val bin: Long? = null,
    val offline: Boolean = false,
)

/** A bin in the media panel. */
data class BinSnap(val id: Long, val name: String, val parent: Long?)

data class ClipSnap(
    val id: Long,
    val item: Long,
    val name: String,
    val start: Double,
    val duration: Double,
    val sourceIn: Double,
    val speed: Double,
    val link: Long?,
    /** Effect controls: position in sequence pixels (null = center), scale %, rotation °, opacity %, clip volume dB. */
    val x: Double? = null,
    val y: Double? = null,
    val scale: Double = 100.0,
    val rotation: Double = 0.0,
    val opacity: Double = 100.0,
    val levelDb: Double = 0.0,
    /** Set for titles (graphics clips with a text layer). */
    val text: TextSnap? = null,
    /** Keyframes per property (see KfKeys). */
    val kf: Map<String, List<Kf>> = emptyMap(),
    /** Audio effects (the engine's rack, only those with DSP). */
    val afx: List<AfxSnap> = emptyList(),
    /** Disabled (Premiere's "Enable", Shift+E): stays in the timeline but isn't seen/heard and isn't exported. */
    val enabled: Boolean = true,
    /** Plays in reverse (the source's end first). */
    val reverse: Boolean = false,
    /** Freeze frame: the source time (s) shown for the whole clip; null = moving picture. */
    val hold: Double? = null,
    /** Compound clip: clips with the same group are selected, moved and graded together. */
    val group: Long? = null,
) {
    /**
     * The source time (s in the file) shown at timeline time [t] – freeze frame, reverse and speed included
     * (the engine's source_time_at, without frame rounding).
     */
    fun sourceAt(t: Double): Double {
        hold?.let { return it }
        val rel = (t.coerceIn(start, start + duration) - start) * kotlin.math.abs(speed)
        return if (reverse) sourceIn + duration * kotlin.math.abs(speed) - rel else sourceIn + rel
    }

    val animatedMotion get() = kf.containsKey(KfKeys.POSITION) || kf.containsKey(KfKeys.SCALE) || kf.containsKey(KfKeys.ROTATION)
    val end get() = start + duration
    /** Moved, scaled or rotated? (The engine states the center explicitly, so compare against the center.) */
    fun hasMotion(w: Int, h: Int): Boolean = animatedMotion ||
        (x != null && kotlin.math.abs(x - w / 2.0) > 0.5) || (y != null && kotlin.math.abs(y - h / 2.0) > 0.5) ||
            kotlin.math.abs(scale - 100.0) > 0.01 || kotlin.math.abs(rotation) > 0.01
    val hasOpacity get() = opacity < 99.99 || kf.containsKey(KfKeys.OPACITY)
    val isTitle get() = text != null
}

/** An audio effect on a clip: slot in the rack ([index]), effect id, on/off and the parameters. */
data class AfxSnap(val index: Int, val id: String, val on: Boolean, val params: Map<String, Double>) {
    fun toJson(): JSONObject = JSONObject().put("i", index).put("id", id).put("on", on)
        .put("p", JSONObject().also { o -> params.forEach { (k, v) -> o.put(k, v) } })

    companion object {
        /** The chain's description for the bridge (only enabled effects matter). */
        fun chainJson(list: List<AfxSnap>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()
    }
}

/** A text layer: text, size in px, anchor point on the first baseline, fill color RGBA 0..1. */
data class TextSnap(val text: String, val size: Double, val x: Double?, val y: Double?, val color: List<Float> = listOf(1f, 1f, 1f, 1f), val name: String = "") {
    /** Klipp's template and animation (stored in the text layer's name). */
    val look: TitleLook get() = TitleLook.parse(name)
}

data class TransitionSnap(val id: Long, val effect: String, val start: Double, val duration: Double, val from: Long?, val to: Long?, val reverse: Boolean = false) {
    val end get() = start + duration
}

data class CaptionSnap(val id: Long, val start: Double, val duration: Double, val text: String, val track: Long = 0) {
    val end get() = start + duration
}

data class TrackSnap(
    val id: Long,
    val name: String,
    val locked: Boolean,
    val muted: Boolean,
    val clips: List<ClipSnap>,
    val solo: Boolean = false,
    val volumeDb: Double = 0.0,
    val transitions: List<TransitionSnap> = emptyList(),
    val syncLock: Boolean = true,
    /** The video track is shown (the eye in Premiere). A hidden track isn't played or exported. */
    val enabled: Boolean = true,
    /** Volume automation (the track's keyframes): (sequence time s, dB), linear in dB in between. */
    val volKf: List<Pair<Double, Double>> = emptyList(),
    /** Automation mode (Off = keyframes aren't read). */
    val autoMode: String = "Read",
) {
    /** The track volume at a time: the automation if present (and read), otherwise the fader. */
    fun volumeAt(t: Double): Double {
        val k = volKf
        if (k.isEmpty() || autoMode == "Off") return volumeDb
        if (t <= k.first().first) return k.first().second
        if (t >= k.last().first) return k.last().second
        val i = k.indexOfLast { it.first <= t }
        val a = k[i]; val b = k[i + 1]
        val f = (t - a.first) / (b.first - a.first).coerceAtLeast(1e-9)
        return a.second + (b.second - a.second) * f
    }
    val hasAutomation get() = volKf.isNotEmpty() && autoMode != "Off"
}

data class MarkerSnap(val id: Long, val time: Double, val name: String, val color: String)

data class SequenceSnap(
    val width: Int,
    val height: Int,
    val duration: Double,
    val video: List<TrackSnap>,
    val audio: List<TrackSnap>,
    val markers: List<MarkerSnap> = emptyList(),
    val videoTarget: Long? = null,
    val audioTarget: Long? = null,
    val captions: List<CaptionSnap> = emptyList(),
    val captionTracks: List<CaptionTrackSnap> = emptyList(),
    val sampleRate: Int = 48000,
    /** Captions on disabled tracks (not visible, shown gray in the timeline). */
    val hiddenCaptions: List<CaptionSnap> = emptyList(),
)

data class CaptionTrackSnap(val id: Long, val name: String, val enabled: Boolean, val count: Int, val captionIds: List<Long> = emptyList(), val style: CaptionStyle = CaptionStyle())

/** The caption track's style (the engine's, applies to all lines on the track). Colors ARGB. Size in px at 1080 lines. */
data class CaptionStyle(
    val font: String = "Inter",
    val size: Float = 54f,
    val color: Int = -0x1,
    val background: Boolean = true,
    val backgroundColor: Int = 0xBF000000.toInt(),
    val align: String = "center",
    val anchor: String = "bottom",
    val margin: Float = 0.08f,
    val lineSpacing: Float = 1.25f,
    val outline: Float = 0f,
    val outlineColor: Int = 0xFF000000.toInt(),
) {
    companion object {
        /** "#rrggbb" or "#rrggbbaa" → ARGB. */
        fun parseColor(s: String?, def: Int): Int {
            val h = s?.removePrefix("#") ?: return def
            val v = h.toLongOrNull(16) ?: return def
            return when (h.length) {
                6 -> (0xFF000000 or v).toInt()
                8 -> (((v and 0xFF) shl 24) or (v ushr 8)).toInt()
                else -> def
            }
        }
        fun hex(c: Int) = "#%02x%02x%02x%02x".format((c shr 16) and 255, (c shr 8) and 255, c and 255, (c ushr 24) and 255)
        fun fromJson(o: JSONObject?): CaptionStyle {
            if (o == null) return CaptionStyle()
            val d = CaptionStyle()
            return CaptionStyle(o.optString("font", d.font), o.optDouble("size", d.size.toDouble()).toFloat(), parseColor(o.optString("color"), d.color),
                o.optBoolean("background", d.background), parseColor(o.optString("backgroundColor"), d.backgroundColor), o.optString("align", d.align),
                o.optString("anchor", d.anchor), o.optDouble("margin", d.margin.toDouble()).toFloat(), o.optDouble("lineSpacing", d.lineSpacing.toDouble()).toFloat(),
                o.optDouble("outline", 0.0).toFloat(), parseColor(o.optString("outlineColor"), d.outlineColor))
        }
    }
}

/**
 * What is played and exported: disabled clips are removed (they become gaps), as are transitions to or from them.
 * Reversed clips play forward from their reversed files once those are ready ([Reversals.rewrite]).
 * Without disabled or reversed clips it's the same object.
 */
fun Snapshot.playable(): Snapshot {
    val q = sequence ?: return this
    val anyOff = !(q.video + q.audio).all { t -> t.clips.all { it.enabled } }
    val anyRev = Reversals.any && (q.video + q.audio).any { t -> t.clips.any { it.reverse && it.hold == null } }
    if (!anyOff && !anyRev) return this
    fun TrackSnap.on(audio: Boolean): TrackSnap {
        val rev = anyRev && clips.any { it.reverse }
        if (clips.all { it.enabled } && !rev) return this
        val off = clips.filter { !it.enabled }.mapTo(HashSet()) { it.id }
        return copy(
            clips = clips.filter { it.enabled }.let { cl -> if (rev) cl.map { Reversals.rewrite(it, audio) } else cl },
            transitions = transitions.filter { x -> (x.from == null || x.from !in off) && (x.to == null || x.to !in off) },
        )
    }
    return copy(sequence = q.copy(video = q.video.map { it.on(false) }, audio = q.audio.map { it.on(true) }))
}

data class Snapshot(
    val playhead: Double,
    val fps: Double,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val media: List<MediaInfo>,
    val sequence: SequenceSnap?,
    val linkedSelection: Boolean = true,
    val bins: List<BinSnap> = emptyList(),
    /** The engine's undo steps (ids, oldest first) and redo steps (ids, next last); new steps get larger ids. */
    val undoIds: List<Long> = emptyList(),
    val redoIds: List<Long> = emptyList(),
    /** Increases with every new edit in the engine (not undo/redo). */
    val editSerial: Long = 0,
) {
    companion object {
        val EMPTY = Snapshot(0.0, 25.0, false, false, emptyList(), null)

        private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeUnless { it.isNaN() }
        private inline fun <T> JSONArray?.list(f: (JSONObject) -> T): List<T> =
            if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

        fun parse(o: JSONObject): Snapshot {
            val media = o.optJSONArray("media").list {
                MediaInfo(
                    id = it.getLong("id"),
                    name = it.optString("name"),
                    path = if (it.isNull("path")) null else it.optString("path"),
                    duration = it.dbl("duration") ?: 0.0,
                    hasVideo = it.optBoolean("hasVideo"),
                    hasAudio = it.optBoolean("hasAudio"),
                    width = it.optInt("width"),
                    height = it.optInt("height"),
                    fps = it.dbl("fps") ?: 25.0,
                    markIn = it.dbl("markIn"),
                    markOut = it.dbl("markOut"),
                    bin = if (it.isNull("bin") || !it.has("bin")) null else it.optLong("bin"),
                    offline = it.optBoolean("offline"),
                )
            }
            val seq = o.optJSONObject("sequence")?.let { q ->
                fun tracks(k: String) = q.optJSONArray(k).list { t ->
                    TrackSnap(
                        t.getLong("id"), t.optString("name"), t.optBoolean("locked"), t.optBoolean("muted"),
                        t.optJSONArray("clips").list { c ->
                            ClipSnap(
                                c.getLong("id"), c.getLong("item"), c.optString("name"),
                                c.optDouble("start"), c.optDouble("duration"), c.optDouble("sourceIn"),
                                c.optDouble("speed", 1.0), if (c.isNull("link")) null else c.optLong("link"),
                                x = c.dbl("x"), y = c.dbl("y"),
                                scale = c.dbl("scale") ?: 100.0,
                                rotation = c.dbl("rotation") ?: 0.0,
                                opacity = c.dbl("opacity") ?: 100.0,
                                levelDb = c.dbl("levelDb") ?: 0.0,
                                enabled = c.optBoolean("enabled", true),
                                reverse = c.optBoolean("reverse", false),
                                hold = c.dbl("hold"),
                                group = if (c.isNull("group") || !c.has("group")) null else c.optLong("group"),
                                kf = c.optJSONObject("kf")?.let { o ->
                                    o.keys().asSequence().associateWith { k ->
                                        o.optJSONArray(k).list { j ->
                                            val v = j.optJSONArray("v")
                                            Kf(j.optLong("mt"), j.optDouble("t"), if (v == null) emptyList() else (0 until v.length()).map { v.optDouble(it) }, j.optString("interp", "Linear"))
                                        }.sortedBy { it.t }
                                    }
                                }.orEmpty(),
                                afx = c.optJSONArray("afx").list { a ->
                                    val p = a.optJSONObject("p")
                                    AfxSnap(a.optInt("i"), a.optString("id"), a.optBoolean("on", true),
                                        p?.keys()?.asSequence()?.associateWith { k -> p.optDouble(k) }?.filterValues { !it.isNaN() }.orEmpty())
                                },
                                text = c.optJSONObject("text")?.let { t ->
                                    val col = t.optJSONArray("color")
                                    TextSnap(
                                        t.optString("text"), t.dbl("size") ?: 100.0, t.dbl("x"), t.dbl("y"),
                                        if (col != null && col.length() >= 4) (0 until 4).map { col.optDouble(it, 1.0).toFloat() } else listOf(1f, 1f, 1f, 1f),
                                        t.optString("name"),
                                    )
                                },
                            )
                        },
                        transitions = t.optJSONArray("transitions").list { x ->
                            TransitionSnap(
                                x.optLong("id"), x.optString("effect"), x.optDouble("start"), x.optDouble("duration"),
                                if (x.isNull("from") || !x.has("from")) null else x.optLong("from"),
                                if (x.isNull("to") || !x.has("to")) null else x.optLong("to"),
                                x.optBoolean("reverse", false),
                            )
                        },
                        solo = t.optBoolean("solo"),
                        volumeDb = t.optDouble("volumeDb", 0.0).let { if (it.isNaN()) 0.0 else it },
                        syncLock = t.optBoolean("syncLock", true),
                        enabled = t.optBoolean("enabled", true),
                        volKf = t.optJSONArray("volKf")?.let { a ->
                            (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { p -> p.optDouble(0) to p.optDouble(1) } }
                                .filter { !it.first.isNaN() && !it.second.isNaN() }.sortedBy { it.first }
                        }.orEmpty(),
                        autoMode = t.optString("autoMode", "Read"),
                    )
                }
                SequenceSnap(
                    q.optInt("width"), q.optInt("height"), q.optDouble("duration"), tracks("video"), tracks("audio"),
                    markers = q.optJSONArray("markers").list { m -> MarkerSnap(m.optLong("id"), m.optDouble("time"), m.optString("name"), m.optString("color")) },
                    videoTarget = if (q.isNull("videoTarget") || !q.has("videoTarget")) null else q.optLong("videoTarget"),
                    audioTarget = if (q.isNull("audioTarget") || !q.has("audioTarget")) null else q.optLong("audioTarget"),
                    captions = q.optJSONArray("captions").list { c -> CaptionSnap(c.optLong("id"), c.optDouble("start"), c.optDouble("duration"), c.optString("text"), c.optLong("track")) },
                    sampleRate = q.optInt("sampleRate", 48000),
                    hiddenCaptions = q.optJSONArray("hiddenCaptions").list { c -> CaptionSnap(c.optLong("id"), c.optDouble("start"), c.optDouble("duration"), c.optString("text"), c.optLong("track")) },
                    captionTracks = q.optJSONArray("captionTracks").list { c ->
                        CaptionTrackSnap(c.optLong("id"), c.optString("name"), c.optBoolean("enabled", true), c.optInt("count"),
                            c.optJSONArray("captionIds")?.let { a -> List(a.length()) { a.optLong(it) } }.orEmpty(), CaptionStyle.fromJson(c.optJSONObject("style")))
                    },
                )
            }
            return Snapshot(
                playhead = o.optDouble("playhead"),
                fps = o.optDouble("fps", 25.0),
                canUndo = o.optBoolean("canUndo"),
                canRedo = o.optBoolean("canRedo"),
                media = media,
                sequence = seq,
                linkedSelection = o.optBoolean("linkedSelection", true),
                bins = o.optJSONArray("bins").list { b -> BinSnap(b.getLong("id"), b.optString("name"), if (b.isNull("parent") || !b.has("parent")) null else b.optLong("parent")) },
                undoIds = o.optJSONArray("undoIds")?.let { a -> List(a.length()) { a.optLong(it) } }.orEmpty(),
                redoIds = o.optJSONArray("redoIds")?.let { a -> List(a.length()) { a.optLong(it) } }.orEmpty(),
                editSerial = o.optLong("editSerial"),
            )
        }
    }
}
