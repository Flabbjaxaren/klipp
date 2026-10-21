package se.gendut.klipp.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

/**
 * Waveforms: audio is decoded once per file (MediaCodec) into peak values, [RATE] per second, and
 * stored in the app's cache. The timeline draws from them.
 */
object Peaks {
    const val RATE = 100

    private val mem = object : LinkedHashMap<String, FloatArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 24
    }
    private val running = HashSet<String>()

    fun cached(uri: Uri): FloatArray? = synchronized(mem) { mem[uri.toString()] }

    private fun file(context: Context, uri: Uri) = File(File(context.cacheDir, "peaks").apply { mkdirs() }, uri.toString().hashCode().toUInt().toString(16) + "_" + uri.toString().length + ".pk")

    /** Peak values for a file (0..1), or null if it has no audio. Blocks – run on a background thread. */
    fun load(context: Context, uri: Uri): FloatArray? {
        val key = uri.toString()
        synchronized(mem) { mem[key]?.let { return it } }
        // another thread is already decoding the same file: wait for it
        while (true) {
            val mine = synchronized(running) { running.add(key) }
            if (mine) break
            Thread.sleep(60)
            synchronized(mem) { mem[key]?.let { return it } }
        }
        try {
            val f = file(context, uri)
            val p = (if (f.exists()) runCatching { read(f) }.getOrNull() else null) ?: decode(context, uri)?.also { runCatching { write(f, it) } }
            if (p != null) synchronized(mem) { mem[key] = p }
            return p
        } finally {
            synchronized(running) { running.remove(key) }
        }
    }

    private fun write(f: File, p: FloatArray) {
        val b = ByteArray(p.size)
        for (i in p.indices) b[i] = (p[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        // via a temp file: an interrupted write never leaves a shorter waveform that looks valid
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(b)
        if (!tmp.renameTo(f)) { tmp.delete(); throw java.io.IOException("could not save the waveform") }
    }

    private fun read(f: File): FloatArray {
        val b = f.readBytes()
        return FloatArray(b.size) { (b[it].toInt() and 255) / 255f }
    }

    private val decodeLock = Any()

    /** Decodes the audio track into peak values per 1/RATE second – one file at a time (saves memory and decoders). */
    private fun decode(context: Context, uri: Uri): FloatArray? = synchronized(decodeLock) { decodeNow(context, uri) }

    private fun decodeNow(context: Context, uri: Uri): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val acc = PeakAccumulator(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT), durUs)
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

/** Collects peak values per 1/RATE s from PCM blocks (pure logic, testable). */
class PeakAccumulator(private var rate: Int, private var channels: Int, durUs: Long) {
    private var out = FloatArray(((durUs / 1e6) * Peaks.RATE).toInt().coerceAtLeast(16) + 2)
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
            for (c in 0 until ch) { val v = kotlin.math.abs(s[f * ch + c].toInt()); if (v > m) m = v }
            put(binOf(ptsUs, f), m / 32768f)
        }
    }

    fun addFloat(ptsUs: Long, s: FloatArray) {
        val ch = channels.coerceAtLeast(1)
        val frames = s.size / ch
        for (f in 0 until frames) {
            var m = 0f
            for (c in 0 until ch) { val v = kotlin.math.abs(s[f * ch + c]); if (v > m) m = v }
            put(binOf(ptsUs, f), m.coerceAtMost(1f))
        }
    }

    private fun binOf(ptsUs: Long, frame: Int): Int = ((ptsUs / 1e6 + frame.toDouble() / rate) * Peaks.RATE).toInt()

    fun result(): FloatArray = out.copyOf(n)
}
