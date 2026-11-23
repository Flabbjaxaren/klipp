package se.gendut.klipp.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

/**
 * Waveforms: audio is decoded once per file (MediaCodec) into peak values, [FINE_RATE] per second, and stored in the
 * app's cache (one byte per value). The timeline draws from a pyramid of them ([PeakLevels]: the fine values and
 * coarser ones, so a long clip zoomed out reads a few values per bar); [load] gives [RATE] per second to the others
 * (beat markers).
 */
object Peaks {
    /** The values [load] and [cached] give (per second). */
    const val RATE = 100
    /** The values stored and drawn when zoomed in (per second): one per millisecond – a bar per pixel at the deepest zoom. */
    const val FINE_RATE = 1000
    /** The stored format: a new one makes every file be decoded again (the older files are removed when seen). */
    private const val VERSION = 2
    /** The waveforms kept in memory (bytes, all levels; ~4.8 MB per hour of audio); the newest one is always kept. */
    private const val MEM_BYTES = 48L shl 20

    private val mem = LinkedHashMap<String, PeakLevels>(8, 0.75f, true)
    private val running = HashSet<String>()

    private fun keep(key: String, p: PeakLevels) = synchronized(mem) {
        mem[key] = p
        var total = mem.values.sumOf { it.bytes }
        val it = mem.entries.iterator()
        while (total > MEM_BYTES && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            total -= e.value.bytes
            it.remove()
        }
    }

    /**
     * The memory and file key: the file, and the channels of its audio track the waveform shows ([channels]: a clip of
     * one of a camera's microphone tracks, AudioChannels; empty = all).
     */
    private fun key(uri: Uri, channels: List<Int>) = uri.toString() + if (channels.isEmpty()) "" else "#ch" + channels.joinToString(",")

    /** The waveform at all its levels if it is in memory (the timeline draws from it right away). */
    fun cachedLevels(uri: Uri, channels: List<Int> = emptyList()): PeakLevels? = synchronized(mem) { mem[key(uri, channels)] }

    /** [RATE] values per second if the waveform is in memory. */
    fun cached(uri: Uri): FloatArray? = cachedLevels(uri)?.coarse()

    private fun dir(context: Context) = File(context.cacheDir, "peaks").apply { mkdirs() }
    private fun name(key: String) = key.hashCode().toUInt().toString(16) + "_" + key.length
    private fun file(context: Context, key: String) = File(dir(context), name(key) + "_v$VERSION.pk")

    /** Peak values for a file (0..1, [RATE] per second), or null if it has no audio. Blocks – run on a background thread. */
    fun load(context: Context, uri: Uri): FloatArray? = loadLevels(context, uri)?.coarse()

    /** The waveform at all its levels, or null if the file has no audio. Blocks – run on a background thread. */
    fun loadLevels(context: Context, uri: Uri, channels: List<Int> = emptyList()): PeakLevels? {
        val key = key(uri, channels)
        synchronized(mem) { mem[key]?.let { return it } }
        // another thread is already decoding the same file: wait for it
        while (true) {
            val mine = synchronized(running) { running.add(key) }
            if (mine) break
            Thread.sleep(60)
            synchronized(mem) { mem[key]?.let { return it } }
        }
        try {
            val f = file(context, key)
            // the older format (100 values per second) is not read: the file is decoded again
            runCatching { File(dir(context), name(key) + ".pk").delete() }
            val b = (if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null)
                ?: decode(context, uri, channels)?.let { quantize(it) }?.also { runCatching { write(f, it) } }
            val p = b?.let { PeakLevels(it) }
            if (p != null) keep(key, p)
            return p
        } finally {
            synchronized(running) { running.remove(key) }
        }
    }

    /** 0..1 → one byte (0..255). */
    fun quantize(p: FloatArray): ByteArray = ByteArray(p.size) { (p[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }

    private fun write(f: File, b: ByteArray) {
        // via a temp file: an interrupted write never leaves a shorter waveform that looks valid
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(b)
        if (!tmp.renameTo(f)) { tmp.delete(); throw java.io.IOException("could not save the waveform") }
    }

    private val decodeLock = Any()

    /** Decodes the audio track into peak values per 1/FINE_RATE second – one file at a time (saves memory and decoders). */
    private fun decode(context: Context, uri: Uri, channels: List<Int>): FloatArray? = synchronized(decodeLock) { decodeNow(context, uri, channels) }

    private fun decodeNow(context: Context, uri: Uri, channels: List<Int>): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val acc = PeakAccumulator(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT), durUs, FINE_RATE, channels)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var pcmFloat = false
            // a decoder that never reaches the end (broken file) must not hold the lock forever: ~10 s without output
            var idle = 0
            while (!outDone && idle < 1000) {
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) idle++ else idle = 0
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        acc.reformat(of.getInteger(MediaFormat.KEY_SAMPLE_RATE), of.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    }
                    o >= 0 -> {
                        val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        if (pcmFloat) { val fb = buf.asFloatBuffer(); val a = FloatArray(fb.remaining()); fb.get(a); acc.addFloat(info.presentationTimeUs, a) }
                        else { val sb = buf.asShortBuffer(); val a = ShortArray(sb.remaining()); sb.get(a); acc.addShort(info.presentationTimeUs, a) }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
            // gave up (no output for a long time): no partial waveform in the cache
            if (!outDone) return null
            return acc.result()
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}

/** Collects peak values per 1/[perSecond] s from PCM blocks – of the channels [only], or all (pure logic, testable). */
class PeakAccumulator(private var rate: Int, private var channels: Int, durUs: Long, private val perSecond: Int = Peaks.RATE, private val only: List<Int> = emptyList()) {
    private var out = FloatArray(((durUs / 1e6) * perSecond).toInt().coerceAtLeast(16) + 2)
    private var n = 0

    fun reformat(r: Int, ch: Int) { rate = r; channels = ch.coerceAtLeast(1) }

    private fun put(bin: Int, v: Float) {
        if (bin < 0) return
        if (bin >= out.size) out = out.copyOf(maxOf(bin + 1, out.size * 2))
        if (v > out[bin]) out[bin] = v
        if (bin + 1 > n) n = bin + 1
    }

    fun addShort(ptsUs: Long, s: ShortArray) {
        val ch = channels.coerceAtLeast(1)
        val frames = s.size / ch
        for (f in 0 until frames) {
            var m = 0
            for (c in 0 until ch) { if (only.isNotEmpty() && c !in only) continue; val v = kotlin.math.abs(s[f * ch + c].toInt()); if (v > m) m = v }
            put(binOf(ptsUs, f), m / 32768f)
        }
    }

    fun addFloat(ptsUs: Long, s: FloatArray) {
        val ch = channels.coerceAtLeast(1)
        val frames = s.size / ch
        for (f in 0 until frames) {
            var m = 0f
            for (c in 0 until ch) { if (only.isNotEmpty() && c !in only) continue; val v = kotlin.math.abs(s[f * ch + c]); if (v > m) m = v }
            put(binOf(ptsUs, f), m.coerceAtMost(1f))
        }
    }

    private fun binOf(ptsUs: Long, frame: Int): Int = ((ptsUs / 1e6 + frame.toDouble() / rate) * perSecond).toInt()

    fun result(): FloatArray = out.copyOf(n)
}

/**
 * A file's waveform at several resolutions: level 0 is [Peaks.FINE_RATE] values per second (one byte each, 0..255),
 * each next level [FACTOR] times coarser (the largest of the values it covers). A bar reads the coarsest level that
 * still has several values for it: drawing costs the same at any zoom, and a long file zoomed out reads little.
 * Pure logic (testable).
 */
class PeakLevels(fine: ByteArray) {
    companion object {
        const val FACTOR = 4
        /** A bar reads a level with at least this many values for it (fewer: the values' edges blur into the neighbours). */
        private const val PER_BAR = 8
    }

    private val levels: List<ByteArray> = buildList {
        add(fine)
        var cur = fine
        while (cur.size > 64) {
            val next = ByteArray((cur.size + FACTOR - 1) / FACTOR)
            for (i in next.indices) {
                var m = 0
                for (k in i * FACTOR until minOf(cur.size, i * FACTOR + FACTOR)) { val v = cur[k].toInt() and 255; if (v > m) m = v }
                next[i] = m.toByte()
            }
            add(next)
            cur = next
        }
    }

    /** Memory taken (all levels). */
    val bytes: Long = levels.sumOf { it.size.toLong() }

    /** How many resolutions there are (the fine one first). */
    val levelCount: Int get() = levels.size

    /** The source's length the values cover (s). */
    val seconds: Double get() = levels[0].size.toDouble() / Peaks.FINE_RATE

    /** The highest peak (0..1) between source times [t0] and [t1] (s); 0 outside the file. */
    fun peak(t0: Double, t1: Double): Float {
        val span = (t1 - t0).coerceAtLeast(0.0)
        // the coarsest level that still has PER_BAR values in the span
        var lv = 0
        var rate = Peaks.FINE_RATE.toDouble()
        while (lv + 1 < levels.size && span * rate / FACTOR >= PER_BAR) { lv++; rate /= FACTOR }
        val p = levels[lv]
        val a = kotlin.math.floor(t0 * rate).toInt().coerceAtLeast(0)
        val b = kotlin.math.ceil(t1 * rate).toInt().coerceAtLeast(a + 1).coerceAtMost(p.size)
        var m = 0
        for (k in a until b) { val v = p[k].toInt() and 255; if (v > m) m = v }
        return m / 255f
    }

    /** [Peaks.RATE] values per second (the largest of the fine ones each covers). */
    fun coarse(): FloatArray {
        val f = levels[0]
        val per = Peaks.FINE_RATE / Peaks.RATE
        return FloatArray((f.size + per - 1) / per) { i ->
            var m = 0
            for (k in i * per until minOf(f.size, i * per + per)) { val v = f[k].toInt() and 255; if (v > m) m = v }
            m / 255f
        }
    }
}
