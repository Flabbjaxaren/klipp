package se.gendut.klipp.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Audio fade over the timeline's [t0, t1]: fade in (sin) or out (cos) – "constant power", so a
 * crossfade (one out, the other in) keeps the same perceived loudness all the way.
 */
data class AudioRamp(val t0: Double, val t1: Double, val fadeIn: Boolean) {
    fun gain(t: Double): Float {
        if (t1 <= t0) return if (fadeIn) 1f else 0f
        val p = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
        return (if (fadeIn) sin(p * PI / 2) else cos(p * PI / 2)).toFloat()
    }
}

/** A piece of audio on the timeline: source, time, clip volume and optional ramps (in and/or out). */
data class AudioPiece(
    val start: Double, val dur: Double, val item: Long, val srcIn: Double, val speed: Double, val levelDb: Double,
    val ramps: List<AudioRamp> = emptyList(),
    /** Clip volume keyframes (dB), empty = fixed [levelDb]. */
    val levelKf: List<Kf> = emptyList(),
    /** The clip (for audio effects and live sliders). */
    val clip: Long = 0,
    val afx: List<AfxSnap> = emptyList(),
) {
    val end get() = start + dur
    fun gain(t: Double): Float = ramps.fold(1f) { g, r -> g * r.gain(t) }
    /** Does the volume need to follow time (fade or keyframes)? */
    val varying get() = ramps.isNotEmpty() || levelKf.isNotEmpty()
    fun levelAt(t: Double): Double = levelKf.valueAt(t) ?: levelDb
}

/** An audio track becomes a base sequence plus (for crossfades) a sequence of overlapping pieces. */
data class AudioTrackPlan(val base: List<AudioPiece>, val over: List<AudioPiece>)

object AudioPlanner {
    private const val EPS = 1e-6

    fun plan(t: TrackSnap, mediaDur: (Long) -> Double?): AudioTrackPlan {
        val clips = t.clips.sortedBy { it.start }
        val byId = clips.associateBy { it.id }
        val base = ArrayList<AudioPiece>()
        val over = ArrayList<AudioPiece>()
        for (c in clips) {
            // all transitions touching the clip: fade in at the start, out at the end
            val ins = t.transitions.filter { it.to == c.id }.map { AudioRamp(it.start, it.end, true) }
            val outs = t.transitions.filter { it.from == c.id }.map { AudioRamp(it.start, it.end, false) }
            val ramps = ins + outs
            val lk = c.kf[KfKeys.LEVEL].orEmpty()
            if (ramps.isEmpty()) { base += AudioPiece(c.start, c.duration, c.item, c.sourceIn, c.speed, c.levelDb, levelKf = lk, clip = c.id, afx = c.afx); continue }
            // split at the ramp edges so each piece only has the ramps that apply there
            val cuts = sortedSetOf(c.start, c.end)
            for (r in ramps) { cuts += r.t0.coerceIn(c.start, c.end); cuts += r.t1.coerceIn(c.start, c.end) }
            val edges = cuts.toList()
            for (k in 0 until edges.size - 1) {
                val a = edges[k]; val b = edges[k + 1]
                if (b - a <= EPS) continue
                val mid = (a + b) / 2
                base += AudioPiece(a, b - a, c.item, c.sourceIn + (a - c.start) * c.speed, c.speed, c.levelDb, ramps.filter { mid >= it.t0 && mid <= it.t1 }, lk, c.id, c.afx)
            }
        }
        // crossfades: the incoming clip's start before the cut and the outgoing clip's end after it
        for (x in t.transitions) {
            val from = x.from?.let { byId[it] } ?: continue
            val to = x.to?.let { byId[it] } ?: continue
            val cut = to.start
            // only as much as exists before the clip's in point; the piece is shortened at the start so it ends exactly at the cut
            val pre = (cut - x.start).coerceAtMost(to.sourceIn / to.speed.coerceAtLeast(1e-9))
            if (pre > EPS) {
                over += AudioPiece(cut - pre, pre, to.item, (to.sourceIn - pre * to.speed).coerceAtLeast(0.0), to.speed, to.levelDb, listOf(AudioRamp(x.start, x.end, true)), to.kf[KfKeys.LEVEL].orEmpty(), to.id, to.afx)
            }
            if (x.end - cut > EPS) {
                val s0 = from.sourceIn + from.duration * from.speed
                var len = x.end - cut
                mediaDur(from.item)?.let { md -> len = len.coerceAtMost((md - s0) / from.speed) }
                if (len > EPS) over += AudioPiece(cut, len, from.item, s0, from.speed, from.levelDb, listOf(AudioRamp(x.start, x.end, false)), from.kf[KfKeys.LEVEL].orEmpty(), from.id, from.afx)
            }
        }
        return AudioTrackPlan(base, over.sortedBy { it.start })
    }
}

/**
 * Time-varying volume: the clip's level and fade ramps ([clip]) and/or the track's volume with
 * automation × master ([track]). Time is counted in output samples from the start of the piece; after a
 * seek the stream's position offset is used if it lies within the piece. With [meter] ≥ 0 the level
 * is measured after the volume (the mixer's meters).
 */
@OptIn(UnstableApi::class)
class GainRampProcessor(
    private val piece: AudioPiece,
    private val trackGain: Float,
    private val clip: Boolean = true,
    private val track: TrackSnap? = null,
    private val master: Float = 1f,
    private val meter: Int = -1,
) : BaseAudioProcessor() {
    private var framesOut = 0L
    private var offsetUs = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    /** The volume at a time (seconds from the start of the piece). */
    fun gainAt(local: Double): Float {
        val t = piece.start + local
        val c = if (clip) CompositionBuilder.dbToGain(piece.levelAt(t)) * piece.gain(t) else 1f
        val tr = if (track != null) CompositionBuilder.dbToGain(track.volumeAt(t)) * master else trackGain
        return c * tr
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val ch = fmt.channelCount
        val bytesPerSample = if (fmt.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frames = inputBuffer.remaining() / (ch * bytesPerSample)
        val out = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        val inBuf = inputBuffer.order(ByteOrder.nativeOrder())
        val rate = fmt.sampleRate.toDouble()
        var peak = 0f
        var sq = 0.0
        // the volume is computed every 64 samples and interpolated linearly in between (exact for ramps, no stair-stepping)
        var g0 = 0f
        var g1 = 0f
        for (f in 0 until frames) {
            if (f % 64 == 0) {
                g0 = gainAt(offsetUs / 1e6 + (framesOut + f) / rate)
                g1 = gainAt(offsetUs / 1e6 + (framesOut + minOf(f + 64, frames)) / rate)
            }
            val blockLen = minOf(64, frames - (f - f % 64))
            val g = g0 + (g1 - g0) * ((f % 64).toFloat() / blockLen)
            for (c in 0 until ch) {
                if (bytesPerSample == 2) {
                    val v = (inBuf.short * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(v.toShort())
                    if (meter >= 0) { val a = kotlin.math.abs(v) / 32768f; if (a > peak) peak = a; sq += (a * a).toDouble() }
                } else {
                    val v = inBuf.float * g
                    out.putFloat(v)
                    if (meter >= 0) { val a = kotlin.math.abs(v); if (a > peak) peak = a; sq += (a * a).toDouble() }
                }
            }
        }
        if (meter >= 0 && frames > 0) Meters.report(meter, peak, kotlin.math.sqrt(sq / (frames * ch)).toFloat())
        framesOut += frames
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        framesOut = 0
        val off = streamMetadata.positionOffsetUs
        offsetUs = if (off > 0 && off < (piece.dur * 1e6).toLong()) off else 0L
    }

    override fun onReset() {
        framesOut = 0
        offsetUs = 0
    }
}

/** The mixer's level meters: peak and RMS per track (A1–A3), updated by the audio thread. */
object Meters {
    const val TRACKS = 3
    private val peak = FloatArray(TRACKS)
    private val rms = FloatArray(TRACKS)
    private val at = LongArray(TRACKS)

    fun report(track: Int, p: Float, r: Float) {
        if (track !in 0 until TRACKS) return
        synchronized(this) {
            val now = System.nanoTime()
            // several pieces on the same track (crossfades): keep the highest within the same instant
            if (now - at[track] > 30_000_000L || p > peak[track]) { peak[track] = p; rms[track] = r } else rms[track] = kotlin.math.sqrt(rms[track] * rms[track] + r * r)
            at[track] = now
        }
    }

    /** (peak, rms) for a track; zeros if nothing arrived for 250 ms (paused). */
    fun read(track: Int): Pair<Float, Float> = synchronized(this) {
        if (System.nanoTime() - at[track] > 250_000_000L) 0f to 0f else peak[track] to rms[track]
    }

    /** The mix level: the tracks' RMS added as power, the peak as a sum (capped). */
    fun master(): Pair<Float, Float> {
        var p = 0f; var e = 0f
        for (i in 0 until TRACKS) { val (a, b) = read(i); p += a; e += b * b }
        return p.coerceAtMost(2f) to kotlin.math.sqrt(e)
    }

    fun clear() = synchronized(this) { peak.fill(0f); rms.fill(0f); at.fill(0L) }
}

/** The bridge to the audio effect chains (replaceable in tests). */
interface FxNative {
    fun create(json: String): Long
    fun free(fx: Long)
    fun set(fx: Long, index: Int, param: String, value: Double): Boolean
    fun latency(fx: Long, rate: Int): Int
    fun reset(fx: Long)
    fun process(fx: Long, data: FloatArray, len: Int, channels: Int, rate: Int)
    /** In a direct ByteBuffer without copies (false = not possible, use [process]). */
    fun processDirect(fx: Long, buf: ByteBuffer, offset: Int, frames: Int, channels: Int, float: Boolean, rate: Int): Boolean = false
}

/** The engine's DSP (filmcraft-audio-dsp) via JNI. If the library is missing everything is a no-op. */
object EngineFx : FxNative {
    override fun create(json: String): Long = runCatching { Engine.nativeFxNew(json) }.getOrDefault(0L)
    override fun free(fx: Long) { runCatching { Engine.nativeFxFree(fx) } }
    override fun set(fx: Long, index: Int, param: String, value: Double): Boolean = runCatching { Engine.nativeFxSet(fx, index, param, value) }.getOrDefault(false)
    override fun latency(fx: Long, rate: Int): Int = runCatching { Engine.nativeFxLatency(fx, rate) }.getOrDefault(0)
    override fun reset(fx: Long) { runCatching { Engine.nativeFxReset(fx) } }
    override fun process(fx: Long, data: FloatArray, len: Int, channels: Int, rate: Int) { runCatching { Engine.nativeFxProcess(fx, data, len, channels, rate) } }
    override fun processDirect(fx: Long, buf: ByteBuffer, offset: Int, frames: Int, channels: Int, float: Boolean, rate: Int): Boolean =
        buf.isDirect && runCatching { Engine.nativeFxProcessDirect(fx, buf, offset, frames, channels, float, rate) }.getOrDefault(false)
}

/**
 * The chains used by the current composition, per clip: so a slider can change the effect while dragging
 * (without rebuilding playback), and so they are released when the composition is replaced.
 */
class FxRegistry(private val native: FxNative = EngineFx) {
    private val byClip = HashMap<Long, MutableList<Long>>()

    @Synchronized fun create(clip: Long, afx: List<AfxSnap>): Long {
        if (afx.none { it.on }) return 0L
        val id = native.create(AfxSnap.chainJson(afx))
        if (id != 0L) byClip.getOrPut(clip) { ArrayList() } += id
        return id
    }

    /** A slider is dragged: all chains for the clip get the value immediately. */
    @Synchronized fun live(clip: Long, index: Int, param: String, value: Double) {
        byClip[clip]?.forEach { native.set(it, index, param, value) }
    }

    /** After a change in the engine (undo, another device …): the chains get the engine's values. */
    @Synchronized fun sync(clips: List<ClipSnap>) {
        for (c in clips) {
            val ids = byClip[c.id] ?: continue
            for (e in c.afx) for ((k, v) in e.params) ids.forEach { native.set(it, e.index, k, v) }
        }
    }

    /** All chains so far – released when no longer in use. */
    @Synchronized fun takeAll(): List<Long> { val all = byClip.values.flatten(); byClip.clear(); return all }

    /** Like [takeAll] but per clip, so they can be put back with [swapBack]. */
    @Synchronized fun takeAllByClip(): Map<Long, List<Long>> { val all = byClip.mapValues { it.value.toList() }; byClip.clear(); return all }

    /** Puts back [old] (a rebuild failed); returns the chains created since then. */
    @Synchronized fun swapBack(old: Map<Long, List<Long>>): List<Long> {
        val fresh = byClip.values.flatten()
        byClip.clear()
        old.forEach { (k, v) -> byClip[k] = v.toMutableList() }
        return fresh
    }

    fun free(ids: List<Long>) = ids.forEach { native.free(it) }
}

/**
 * The clip's audio effects (the engine's DSP) as an audio processor. The chain's latency (e.g. the noise
 * reduction's analysis window) is compensated: the first samples after a seek are skipped and the tail is
 * flushed at the end, so the audio stays in sync with the picture.
 */
@OptIn(UnstableApi::class)
class FxProcessor(private val fx: Long, private val native: FxNative = EngineFx) : BaseAudioProcessor() {
    private var latency = 0
    private var skip = 0
    private var buf = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (fx == 0L) return AudioProcessor.AudioFormat.NOT_SET
        if ((inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount !in 1..2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        latency = native.latency(fx, inputAudioFormat.sampleRate).coerceIn(0, inputAudioFormat.sampleRate)
        skip = latency
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // the pipeline polls with AudioProcessor.EMPTY_BUFFER, which is also our initial output buffer:
        // out.put(inputBuffer) below would then put a buffer into itself (IllegalArgumentException → player error)
        if (!inputBuffer.hasRemaining()) return
        val fmt = inputAudioFormat
        val ch = fmt.channelCount
        val f32 = fmt.encoding == C.ENCODING_PCM_FLOAT
        val bps = if (f32) 4 else 2
        val frames = inputBuffer.remaining() / (ch * bps)
        val drop = minOf(skip, frames)
        // fast path: copy straight into the output buffer and let the DSP work there (no Java arrays)
        val out = replaceOutputBuffer(frames * ch * bps).order(ByteOrder.nativeOrder())
        val start = inputBuffer.position()
        out.put(inputBuffer)
        out.flip()
        if (native.processDirect(fx, out, 0, frames, ch, f32, fmt.sampleRate)) {
            skip -= drop
            out.position(drop * ch * bps)
            return
        }
        // fallback: via a float array
        inputBuffer.position(start)
        val n = frames * ch
        if (buf.size < n) buf = FloatArray(n)
        val inBuf = inputBuffer.order(ByteOrder.nativeOrder())
        for (i in 0 until n) buf[i] = if (f32) inBuf.float else inBuf.short / 32768f
        native.process(fx, buf, n, ch, fmt.sampleRate)
        skip -= drop
        write((frames - drop) * ch, drop * ch, f32)
    }

    private fun write(count: Int, from: Int, f32: Boolean) {
        val out = replaceOutputBuffer(count * (if (f32) 4 else 2)).order(ByteOrder.nativeOrder())
        for (i in from until from + count) {
            val v = buf[i]
            if (f32) out.putFloat(v) else out.putShort((v * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    override fun onQueueEndOfStream() {
        // the tail: as many samples as were skipped at the start (so the length matches)
        val tail = latency - skip
        if (tail <= 0) return
        val fmt = inputAudioFormat
        val ch = fmt.channelCount
        val n = tail * ch
        if (buf.size < n) buf = FloatArray(n)
        java.util.Arrays.fill(buf, 0, n, 0f)
        native.process(fx, buf, n, ch, fmt.sampleRate)
        write(n, 0, fmt.encoding == C.ENCODING_PCM_FLOAT)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        native.reset(fx)
        skip = latency
    }
}
