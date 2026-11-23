package se.gendut.klipp.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A file's audio streams (Android's extractor: each audio track's channel count, in file order – the engine's
 * audio_stream numbering) and, for an audio clip of stream k ≥ 1, a stand-in that holds just that stream: Media3 plays
 * only a file's first audio track. The stand-in is the stream decoded once to 16-bit WAV (lossless for PCM, a decode –
 * not a re-encode – for AAC and others), silence before it where the stream starts after the file, so its times are
 * the file's. Kept in the cache folder (made again when needed). See [AudioChannels].
 *
 * Limits: a reversed clip or one with rendered effects (denoise …) of stream k ≥ 1 of such a file plays from a file
 * made of the first stream (Reversals/AudioRenders decode the first audio track); ProRes intermediates hold every
 * stream and don't have that limit.
 */
object AudioStreams {
    /** Bumped when the stand-ins change: increments when one is ready (playback is then rebuilt). */
    const val VERSION = 1

    var revision by mutableIntStateOf(0)
        private set

    private val counts = ConcurrentHashMap<String, List<Int>>()
    private val sizes = ConcurrentHashMap<String, Long>()
    private val ready = ConcurrentHashMap<String, File>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-streams").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** The channel counts of [uri]'s audio streams if probed (null = not yet). */
    fun cached(uri: Uri): List<Int>? = counts[uri.toString()]

    /** Probes [uri]'s audio tracks (blocking, a few ms; cached). Files Klipp makes itself (intermediates) need none. */
    fun probe(context: Context, uri: Uri): List<Int>? {
        val k = uri.toString()
        counts[k]?.let { return it }
        val r = runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                (0 until ex.trackCount).map { ex.getTrackFormat(it) }.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    .map { f -> if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2 }
            } finally { ex.release() }
        }.getOrNull() ?: return null
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { sizes[k] = it.statSize } }
        counts[k] = r
        return r
    }

    private fun key(uri: Uri, stream: Int) = "$uri#$stream"

    /** Stream [stream]'s stand-in when it is ready, else null ([ensure] makes it). */
    fun standIn(uri: Uri, stream: Int): Uri? = ready[key(uri, stream)]?.let { Uri.fromFile(it) }

    private fun dir(c: Context) = File(c.cacheDir, "streams").apply { mkdirs() }

    private fun fileFor(c: Context, uri: Uri, stream: Int): File {
        val h = MessageDigest.getInstance("SHA-1").digest("$uri|${sizes[uri.toString()] ?: -1}|$stream|v$VERSION".toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        return File(dir(c), "$h.wav")
    }

    /** The streams ≥ 1 the timeline's audio clips use, by file (uriOf: the file each item decodes). */
    private fun wanted(s: Snapshot, uriOf: (Long) -> Uri?): List<Pair<Uri, Int>> {
        val q = s.sequence ?: return emptyList()
        return q.audio.flatMap { it.clips }.filter { it.audioStream > 0 && it.item >= 0 }
            .mapNotNull { c -> uriOf(c.item)?.takeIf { ProRes.audioSlots(it) == null }?.let { it to c.audioStream } }.distinct()
    }

    /** Makes the missing stand-ins in the background (playback). */
    fun ensure(context: Context, s: Snapshot, uriOf: (Long) -> Uri?) {
        val c = context.applicationContext
        for ((u, k) in wanted(s, uriOf)) {
            val key = key(u, k)
            if (ready.containsKey(key) || failed.containsKey(key) || !pending.add(key)) continue
            worker.execute { make(c, u, k) }
        }
    }

    /** The export: every stand-in it needs, made now (one that can't be made plays silent – said in the trace). */
    suspend fun await(context: Context, s: Snapshot, uriOf: (Long) -> Uri?) = withContext(Dispatchers.IO) {
        val want = wanted(s, uriOf)
        if (want.isEmpty()) return@withContext
        ensure(context, s, uriOf)
        while (want.any { (u, k) -> key(u, k).let { !ready.containsKey(it) && !failed.containsKey(it) } }) delay(200)
    }

    private fun make(c: Context, uri: Uri, stream: Int) {
        val key = key(uri, stream)
        try {
            if (probe(c, uri) == null) error("the file's audio can't be read")
            val out = fileFor(c, uri, stream)
            if (!out.isFile || out.length() <= 44) {
                val tmp = File(out.path + ".tmp")
                try { decodeToWav(c, uri, stream, tmp) } catch (e: Throwable) { tmp.delete(); throw e }
                if (!tmp.renameTo(out)) error("the stand-in couldn't be saved")
            }
            ready[key] = out
            MediaProbe.probe(c, Uri.fromFile(out))
            se.gendut.klipp.CrashReport.note("audio stream $stream of ${uri.lastPathSegment}: stand-in ready (${out.length() / 1000} kB)")
            main.post { revision++ }
        } catch (e: Throwable) {
            failed[key] = e.message ?: e.javaClass.simpleName
            se.gendut.klipp.CrashReport.note("audio stream $stream of ${uri.lastPathSegment}: ${failed[key]}")
        } finally {
            pending.remove(key)
        }
    }

    /** Audio track [stream] (counted among the audio tracks) decoded into a 16-bit WAV that starts at the file's time 0. */
    private fun decodeToWav(c: Context, uri: Uri, stream: Int, out: File) {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(c, uri, null)
            val track = (0 until ex.trackCount).filter { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                .getOrNull(stream) ?: error("the file has no audio stream $stream")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also { dec = it }
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var written = 0L // sample frames
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var idle = 0
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                val os = java.io.BufferedOutputStream(java.io.FileOutputStream(raf.fd), 1 shl 16)
                while (idle < 1000) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { idle++; continue }
                    idle = 0
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        continue
                    }
                    if (o < 0) continue
                    val ob = codec.getOutputBuffer(o)!!
                    if (info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val pcm: ByteArray = if (float) {
                            val fb = ob.order(ByteOrder.nativeOrder()).asFloatBuffer()
                            val s = ByteArray(fb.remaining() * 2)
                            var k = 0
                            while (fb.hasRemaining()) { val v = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt(); s[k++] = v.toByte(); s[k++] = (v shr 8).toByte() }
                            s
                        } else ByteArray(info.size).also { ob.get(it) }
                        val frameBytes = 2 * channels
                        // the block's place in the file's time: silence before it where the stream starts later
                        val at = info.presentationTimeUs * rate / 1_000_000
                        if (at > written + 1 && at - written < rate.toLong() * 3600) {
                            val frames = (at - written).coerceAtMost(((1 shl 24) / frameBytes).toLong()).toInt()
                            os.write(ByteArray(frames * frameBytes))
                            written += frames
                        }
                        os.write(pcm, 0, pcm.size / frameBytes * frameBytes)
                        written += pcm.size / frameBytes
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                os.flush()
                if (idle >= 1000) error("the decoder stopped")
                val data = written * 2 * channels
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * 2 * channels).putShort((2 * channels).toShort()).putShort(16)
                h.put("data".toByteArray()).putInt(data.toInt())
                raf.seek(0)
                raf.write(h.array())
            }
        } finally {
            runCatching { dec?.stop() }; runCatching { dec?.release() }
            ex.release()
        }
    }

    internal fun putForTest(uri: Uri, channelCounts: List<Int>, standIns: Map<Int, File> = emptyMap()) {
        counts[uri.toString()] = channelCounts
        for ((k, f) in standIns) ready[key(uri, k)] = f
    }

    internal fun clearForTest() { counts.clear(); ready.clear(); failed.clear(); pending.clear() }
}
