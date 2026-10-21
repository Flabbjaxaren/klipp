package se.gendut.klipp.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import se.gendut.klipp.R
import se.gendut.klipp.Str

/**
 * Reverse (the engine's reverse). Media3 cannot play backwards, so the clip's source range is turned into a file that
 * runs backwards – once, in the background – and the clip then plays forwards from it ([rewrite], via [playable]):
 *  1. Transformer encodes the range with keyframes only (every frame standalone; 10 bits and HDR are kept)
 *  2. the frames are written in reverse order to a new file (no re-encoding – they are standalone)
 *  3. the audio is decoded and written backwards as WAV
 * File time r corresponds to source time b − r (the range [a, b]). Until the file is ready the clip plays forwards;
 * the export waits for it ([await]). The clips get negative ids for the files (the engine's ids are positive).
 */
@OptIn(UnstableApi::class)
object Reversals {
    /** A reversed range [a, b] of [uri]: the video ([video], id [vid]) and the audio ([audio], id [aid]). */
    data class Rendition(val uri: String, val a: Double, val b: Double, val video: File?, val audio: File?, val vid: Long, val aid: Long)

    /** Increments when a file is ready (playback is then rebuilt). */
    var revision by mutableIntStateOf(0)
        private set

    /** Margin around the clip's range (for the transitions' pre-roll and small trims without a new file). */
    private const val MARGIN = 1.0

    private val ready = CopyOnWriteArrayList<Rendition>()
    private val pending = ConcurrentHashMap<String, Unit>()
    private val failed = ConcurrentHashMap<String, String>()
    /** Engine media item → file (set by [ensure]; [rewrite] only has the clips). */
    private val itemUris = ConcurrentHashMap<Long, String>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "reversals").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var dir: File? = null
    /** Messages to the user (start, errors). */
    @Volatile var onMessage: ((String) -> Unit)? = null

    private fun dir(c: Context): File = dir ?: File(c.filesDir, "reversed").apply { mkdirs() }.also { dir = it; load(it) }

    fun init(context: Context) { dir(context.applicationContext) }

    /** A reversed clip's files: the video for [vid], the audio for [aid]. */
    fun uriOf(id: Long): Uri? {
        if (id >= 0) return null
        val r = ready.firstOrNull { it.vid == id || it.aid == id } ?: return null
        return (if (r.vid == id) r.video else r.audio)?.let { Uri.fromFile(it) }
    }

    /**
     * The source time in the original file for time [t] in the file [item] plays: from a reversed file (negative id) b − t,
     * otherwise [t] (tracked windows, stabilization and mask keyframes are in the original's time).
     */
    fun originalTime(item: Long, t: Double): Double {
        if (item >= 0) return t
        val r = ready.firstOrNull { it.vid == item || it.aid == item } ?: return t
        return r.b - t
    }

    /** The source range a clip plays (counted forwards): [s0, s1]. */
    private fun rangeOf(c: ClipSnap): Pair<Double, Double> {
        val s0 = c.sourceIn
        return s0 to s0 + c.duration * kotlin.math.abs(c.speed)
    }

    private fun covering(uri: String, s0: Double, s1: Double, audio: Boolean): Rendition? =
        ready.firstOrNull { it.uri == uri && it.a <= s0 + 1e-3 && it.b >= s1 - 1e-3 && (if (audio) it.audio != null else it.video != null) }

    /**
     * A reversed clip as a forward clip from the reversed file (when ready), otherwise unchanged. [audio] = the clip is
     * on an audio track (then the audio file).
     */
    fun rewrite(c: ClipSnap, audio: Boolean): ClipSnap {
        if (!c.reverse || c.hold != null || c.text != null) return c
        val uri = itemUris[c.item] ?: return c
        val (s0, s1) = rangeOf(c)
        val r = covering(uri, s0, s1, audio) ?: return c
        return c.copy(item = if (audio) r.aid else r.vid, sourceIn = (r.b - s1).coerceAtLeast(0.0), speed = kotlin.math.abs(c.speed), reverse = false)
    }

    /** Whether any reversed files exist (otherwise [playable] need not look). */
    val any: Boolean get() = ready.isNotEmpty()

    private data class Job(val uri: Uri, val a: Double, val b: Double) {
        val key get() = "$uri@${"%.1f".format(java.util.Locale.ROOT, a)}-${"%.1f".format(java.util.Locale.ROOT, b)}"
    }

    /** The ranges missing for the timeline's reversed clips. */
    private fun jobsOf(context: Context, s: Snapshot, uriOf: (Long) -> Uri?, skipPending: Boolean = true): List<Job> {
        val q = s.sequence ?: return emptyList()
        val out = LinkedHashMap<String, Job>()
        for ((audio, tracks) in listOf(false to q.video, true to q.audio)) for (t in tracks) for (c in t.clips) {
            if (!c.reverse || c.hold != null || c.text != null) continue
            val uri = uriOf(c.item) ?: continue
            itemUris[c.item] = uri.toString()
            val (s0, s1) = rangeOf(c)
            if (covering(uri.toString(), s0, s1, audio) != null) continue
            // rounded to tenths (same file for the same clip) with margin, within the file
            val len = MediaProbe.probe(context, uri)?.duration?.takeIf { it > 0 }
            val a = (Math.floor((s0 - MARGIN) * 10) / 10).coerceAtLeast(0.0)
            val b = (Math.ceil((s1 + MARGIN) * 10) / 10).let { if (len != null) it.coerceAtMost(len) else it }
            val j = Job(uri, a, maxOf(b, s1))
            // a range already in progress that covers the clip is enough
            if (out.values.any { it.uri == uri && it.a <= s0 && it.b >= s1 } || (skipPending && pendingCovers(uri.toString(), s0, s1))) continue
            out[j.key] = j
        }
        return out.values.toList()
    }

    private val pendingRanges = ConcurrentHashMap<String, Triple<String, Double, Double>>()
    private fun pendingCovers(uri: String, s0: Double, s1: Double) = pendingRanges.values.any { (u, a, b) -> u == uri && a <= s0 && b >= s1 }

    /** Create the missing reversed files, in the background (playback). */
    fun ensure(context: Context, s: Snapshot, uriOf: (Long) -> Uri?) {
        val app = context.applicationContext
        dir(app)
        if (s.sequence?.let { q -> (q.video + q.audio).any { t -> t.clips.any { it.reverse && it.hold == null } } } != true) return
        worker.execute {
            for (j in jobsOf(app, s, uriOf)) {
                if (failed.containsKey(j.key) || pending.putIfAbsent(j.key, Unit) != null) continue
                pendingRanges[j.key] = Triple(j.uri.toString(), j.a, j.b)
                worker.execute { run(app, j) }
            }
        }
    }

    /** All reversed files (the export): missing ones are created now. Error = the export aborts with the error. */
    suspend fun await(context: Context, s: Snapshot, uriOf: (Long) -> Uri?) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        dir(app)
        val done = CountDownLatch(1)
        var err: Throwable? = null
        // on the same thread as the background jobs: a job already running finishes first
        worker.execute {
            try {
                // including those waiting in the queue (they run here and now; the queue then finds them done)
                for (j in jobsOf(app, s, uriOf, skipPending = false)) {
                    pending[j.key] = Unit
                    pendingRanges[j.key] = Triple(j.uri.toString(), j.a, j.b)
                    if (!run(app, j)) error(failed[j.key] ?: "reverse failed")
                }
            } catch (e: Throwable) { err = e } finally { done.countDown() }
        }
        done.await()
        err?.let { throw it }
    }

    private fun name(j: Job): String = MessageDigest.getInstance("SHA-1").digest(j.key.toByteArray()).joinToString("") { "%02x".format(it) }

    /** One job (on the worker thread). true = done. */
    private fun run(c: Context, j: Job): Boolean {
        val d = dir(c)
        try {
            if (covering(j.uri.toString(), j.a, j.b, false) != null || covering(j.uri.toString(), j.a, j.b, true) != null) return true
            se.gendut.klipp.CrashReport.note("reverse starting: ${j.uri.lastPathSegment} ${"%.1f".format(j.a)}–${"%.1f".format(j.b)} s")
            main.post { onMessage?.invoke(Str.t(R.string.reversals_preparing)) }
            val base = name(j)
            val hasVideo = trackOf(c, j.uri, "video/") != null
            val hasAudio = trackOf(c, j.uri, "audio/") != null
            val video = if (hasVideo) File(d, "$base.mp4").also { if (!it.exists()) reverseVideo(c, j, it) } else null
            val audio = if (hasAudio) File(d, "$base.wav").also { if (!it.exists()) reverseAudio(c, j, it) } else null
            if (video == null && audio == null) error("no video or audio in the file")
            add(Rendition(j.uri.toString(), j.a, j.b, video, audio, 0, 0))
            video?.let { MediaProbe.probe(c, Uri.fromFile(it)) }
            save(d)
            se.gendut.klipp.CrashReport.note("reverse done: ${j.uri.lastPathSegment}")
            main.post { revision++ }
            return true
        } catch (e: Throwable) {
            val m = e.message ?: e.javaClass.simpleName
            failed[j.key] = m
            se.gendut.klipp.CrashReport.note("reverse error: $m")
            main.post { onMessage?.invoke(Str.t(R.string.reversals_failed, m)) }
            return false
        } finally {
            pending.remove(j.key)
            pendingRanges.remove(j.key)
        }
    }

    /** Add with own ids (negative, stable during the run: the list only grows). */
    private fun add(r: Rendition) = synchronized(ready) {
        val n = ready.size
        ready += r.copy(vid = -(2L * n + 2), aid = -(2L * n + 3))
    }

    private fun trackOf(c: Context, uri: Uri, prefix: String): Int? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(c, uri, null)
            for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
            return null
        } finally { ex.release() }
    }

    // ------------------------------------------------------------------ video

    private fun reverseVideo(c: Context, j: Job, out: File) {
        val intra = File(out.path + ".intra.mp4")
        try {
            // first with keyframes only; for an encoder that still makes inter frames: every frame as a keyframe
            var ok = false
            for (interval in floatArrayOf(0f, 0.001f)) {
                intra.delete()
                transcodeIntra(c, j, intra, interval)
                if (remuxReversed(intra, File(out.path + ".tmp"), j.b - j.a)) { ok = true; break }
            }
            if (!ok) error("the encoder does not produce keyframes only")
            if (!File(out.path + ".tmp").renameTo(out)) error("could not save the file")
        } finally {
            intra.delete()
            File(out.path + ".tmp").delete()
        }
    }

    /** The range with keyframes only, full resolution (10 bits and HDR kept when the phone can). */
    private fun transcodeIntra(c: Context, j: Job, out: File, interval: Float) {
        val hdr = Perf.isHdr(c, j.uri) == true
        val keepHdr = hdr && EncoderCaps.hdrSupported
        val ten = if (keepHdr || VideoPipeline.bitDepth(c, j.uri) >= 10) VideoPipeline.tenBitProfile(MimeTypes.VIDEO_H265) else null
        val mime = if (ten != null || keepHdr) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val codec = Perf.codec(c, j.uri)
        // keyframes only need more data for the same quality: ~2.5 × the source's, within reasonable limits
        val px = (codec?.width ?: 1920).toLong() * (codec?.height ?: 1080)
        val floor = (40_000_000.0 * px / (1920.0 * 1080.0)).toLong()
        val bitrate = maxOf(floor, (codec?.bitrate ?: 0L) * 5 / 2).coerceIn(20_000_000L, 200_000_000L).toInt()
        val vs = VideoEncoderSettings.Builder().setBitrate(bitrate).setiFrameIntervalSeconds(interval)
        if (ten != null && !keepHdr) vs.setEncodingProfileLevel(ten.first, ten.second)
        val enc = DefaultEncoderFactory.Builder(c).setRequestedVideoEncoderSettings(vs.build()).setEnableFallback(true).build()
        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(j.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(Math.round(j.a * 1000)).setEndPositionMs(Math.round(j.b * 1000)).build(),
            ).build(),
        ).setRemoveAudio(true).build()
        val comp = Composition.Builder(EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO)).addItem(item).build())
            .setHdrMode(if (keepHdr) Composition.HDR_MODE_KEEP_HDR else VideoPipeline.toneMapMode(hdr, false))
            .build()
        val done = CountDownLatch(1)
        var err: ExportException? = null
        main.post {
            // Snapdragon: the encoding threads on the big cores (see Perf.Boost)
            val boost = Perf.Boost(c)
            val t = Transformer.Builder(c)
                .setAssetLoaderFactory(Perf.assetLoaderFactory(c))
                .setVideoMimeType(mime)
                .setEncoderFactory(enc)
                .setVideoFrameProcessorFactory(VideoPipeline.processorFactory(ten != null))
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { boost.close(); done.countDown() }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { boost.close(); err = exportException; done.countDown() }
                })
                .build()
            runCatching { t.start(comp, out.absolutePath) }.onFailure { e -> boost.close(); err = ExportException.createForUnexpected(Exception(e)); done.countDown() }
        }
        if (!done.await(30, TimeUnit.MINUTES)) error("took too long")
        err?.let { throw IllegalStateException("${it.errorCodeName} ${it.message}", it) }
    }

    /**
     * The frames in reverse order (file time r ↔ source time b − r). false = the file has inter frames (cannot be
     * reversed without decoding).
     */
    internal fun remuxReversed(input: File, out: File, span: Double): Boolean {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(input.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: error("no video in the intermediate file")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val times = ArrayList<Long>()
            var maxSize = 0
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) return false
                times += t
                maxSize = maxOf(maxSize, ex.sampleSize.toInt())
                ex.advance()
            }
            if (times.isEmpty()) error("the intermediate file is empty")
            times.sort()
            // one frame's duration (median) – the last frame ends at the range's end
            val frame = if (times.size > 1) times.zipWithNext { a, b -> b - a }.sorted()[(times.size - 1) / 2] else 33_333L
            val end = maxOf(times.last() + frame, Math.round(span * 1_000_000))
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            if (format.containsKey(MediaFormat.KEY_ROTATION)) m.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
            val outTrack = m.addTrack(format)
            m.start()
            val buf = ByteBuffer.allocateDirect(maxSize.coerceAtLeast(1))
            val info = MediaCodec.BufferInfo()
            var last = -1L
            for (t in times.asReversed()) {
                ex.seekTo(t, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                if (ex.sampleTime != t) error("could not read the frame at ${t / 1000} ms")
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                // the frame was shown [t, t + frame) → reversed: [end − t − frame, end − t)
                val pts = (end - t - frame).coerceAtLeast(last + 1)
                info.set(0, size, pts, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                m.writeSampleData(outTrack, buf, info)
                last = pts
            }
            m.stop()
            return true
        } finally {
            runCatching { muxer?.release() }
            ex.release()
        }
    }

    // ------------------------------------------------------------------ audio

    /** The audio in [a, b] backwards as WAV (16 bits). */
    private fun reverseAudio(c: Context, j: Job, out: File) {
        val raw = File(out.path + ".raw")
        val tmp = File(out.path + ".tmp")
        try {
            val (rate, channels) = decodePcm(c, j, raw)
            writeReversedWav(raw, tmp, rate, channels)
            if (!tmp.renameTo(out)) error("could not save the audio")
        } finally {
            raw.delete(); tmp.delete()
        }
    }

    /** Decode [a, b) to raw 16-bit PCM in [raw]; returns (sample rate, channels). */
    private fun decodePcm(c: Context, j: Job, raw: File): Pair<Int, Int> {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(c, j.uri, null)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also { dec = it }
            codec.configure(fmt, null, null, 0)
            codec.start()
            val a = Math.round(j.a * 1_000_000); val b = Math.round(j.b * 1_000_000)
            ex.seekTo(a, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val info = MediaCodec.BufferInfo()
            var inDone = false
            raw.outputStream().buffered(1 shl 16).use { os ->
                while (true) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val ib = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(ib, 0)
                            if (n < 0 || ex.sampleTime > b) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
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
                        // 16 bits out (float is rescaled)
                        val pcm: ByteArray = if (float) {
                            val fb = ob.order(ByteOrder.nativeOrder()).asFloatBuffer()
                            val s = ByteArray(fb.remaining() * 2)
                            var k = 0
                            while (fb.hasRemaining()) { val v = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt(); s[k++] = v.toByte(); s[k++] = (v shr 8).toByte() }
                            s
                        } else ByteArray(info.size).also { ob.get(it) }
                        // only [a, b): frames before a and after b are cut away
                        val frameBytes = 2 * channels
                        val frames = pcm.size / frameBytes
                        val t0 = info.presentationTimeUs
                        val skip = if (t0 < a) ((a - t0) * rate / 1_000_000).toInt().coerceIn(0, frames) else 0
                        val keep = (((b - t0) * rate / 1_000_000).toInt()).coerceIn(0, frames)
                        if (keep > skip) os.write(pcm, skip * frameBytes, (keep - skip) * frameBytes)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return rate to channels
        } finally {
            runCatching { dec?.stop() }; runCatching { dec?.release() }
            ex.release()
        }
    }

    /** [raw] (16-bit PCM) backwards as WAV, in chunks (memory suffices for long clips). */
    internal fun writeReversedWav(raw: File, out: File, rate: Int, channels: Int) {
        val frameBytes = 2 * channels
        val total = raw.length() / frameBytes * frameBytes
        RandomAccessFile(raw, "r").use { inp ->
            out.outputStream().buffered(1 shl 16).use { os ->
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((36 + total).toInt()).put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * frameBytes).putShort(frameBytes.toShort()).putShort(16)
                h.put("data".toByteArray()).putInt(total.toInt())
                os.write(h.array())
                val chunk = (1 shl 16) / frameBytes * frameBytes
                val buf = ByteArray(chunk)
                val rev = ByteArray(chunk)
                var end = total
                while (end > 0) {
                    val n = minOf(chunk.toLong(), end).toInt()
                    inp.seek(end - n)
                    inp.readFully(buf, 0, n)
                    // the sample frames (all channels together) in reverse order
                    val frames = n / frameBytes
                    for (f in 0 until frames) System.arraycopy(buf, f * frameBytes, rev, (frames - 1 - f) * frameBytes, frameBytes)
                    os.write(rev, 0, n)
                    end -= n
                }
            }
        }
    }

    // ------------------------------------------------------------------ index on disk

    private fun index(d: File) = File(d, "index.json")

    private fun save(d: File) = synchronized(ready) {
        runCatching {
            val a = JSONArray()
            for (r in ready) a.put(JSONObject().put("uri", r.uri).put("a", r.a).put("b", r.b).put("video", r.video?.name ?: JSONObject.NULL).put("audio", r.audio?.name ?: JSONObject.NULL))
            index(d).writeText(a.toString())
        }
    }

    private fun load(d: File) = synchronized(ready) {
        if (ready.isNotEmpty()) return@synchronized
        runCatching {
            val a = JSONArray(index(d).readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val v = o.optString("video").takeIf { !o.isNull("video") && it.isNotEmpty() }?.let { File(d, it) }?.takeIf { it.exists() }
                val w = o.optString("audio").takeIf { !o.isNull("audio") && it.isNotEmpty() }?.let { File(d, it) }?.takeIf { it.exists() }
                if (v == null && w == null) continue
                add(Rendition(o.getString("uri"), o.getDouble("a"), o.getDouble("b"), v, w, 0, 0))
            }
        }
    }

    /** For tests: a finished range and the item's file. */
    internal fun putForTest(item: Long, uri: String, a: Double, b: Double, video: File?, audio: File?) {
        itemUris[item] = uri
        add(Rendition(uri, a, b, video, audio, 0, 0))
    }

    internal fun clearForTest() { synchronized(ready) { ready.clear() }; itemUris.clear() }
}
