package se.gendut.klipp.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import se.gendut.klipp.R
import se.gendut.klipp.Str
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * ProRes import. No phone has a ProRes decoder (seen: OnePlus 12 and vivo X300 Ultra have APV only), so a ProRes file
 * is converted once, in the background, into an intermediate the phone decodes in hardware: FFmpeg's ProRes decoder
 * (libklipp_prores.so, tools/ffmpeg) → the phone's HEVC encoder, Main10 at the source's size and frame rate, ~0.87 bits
 * per pixel ([ProResPlan.bitrate]) → a MOV with the camera's audio tracks mixed into one 16-bit PCM track
 * ([ProResPlan.audioPlan]; Media3 plays only one audio track).
 *
 * The original stays the project's file (saved, relinked, shown by name); everything that decodes – playback, preview,
 * proxies, scrubbing, export – reads the intermediate instead ([playable], via ProjectController.uriOf). Until the
 * intermediate is ready the clip has no file to play (like a missing one: a gap, no thumbnail); the export waits for
 * it ([await]). Intermediates live in files/prores, keyed by the source's address and size and [VERSION], reused across
 * projects, and are deleted when no project uses their source any more ([sweep]).
 */
object ProRes {
    /** Bumped when intermediates come out differently: the old ones are then made anew. */
    const val VERSION = 1

    /** A source's state for the media panel. */
    sealed interface Status {
        data object Queued : Status
        data class Working(val percent: Int) : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    /** Increments when an intermediate is ready or failed (playback is then rebuilt). */
    var revision by mutableIntStateOf(0)
        private set
    /** Sources being converted: address → percent (−1 = queued). */
    val progress = mutableStateMapOf<String, Int>()
    /** Sources that couldn't be converted (not retried in this session): address → reason. */
    val failures = mutableStateMapOf<String, String>()
    /** Messages to the user (start, done, errors) – set by the project controller. */
    @Volatile var onMessage: ((String) -> Unit)? = null
    /** A conversion finished: the project's files changed (the controller refreshes). */
    @Volatile var onChange: (() -> Unit)? = null

    /** A finished intermediate. */
    class Entry(val file: File, val key: String, val note: String)

    private val ready = ConcurrentHashMap<String, Entry>()
    /** Probed sources: address → ProRes (true) or not. */
    private val known = ConcurrentHashMap<String, Boolean>()
    private val sizes = ConcurrentHashMap<String, Long>()
    private val names = ConcurrentHashMap<String, String>()
    /** Sources whose conversion was called off ([cancel]) – the running one stops at its next picture. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val probing = ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "prores").apply { isDaemon = true } }
    /** Probing never waits behind a conversion (minutes). */
    private val prober = Executors.newSingleThreadExecutor { r -> Thread(r, "prores-probe").apply { isDaemon = true } }
    @Volatile private var app: Context? = null

    private fun dir(c: Context) = File(c.filesDir, "prores").apply { mkdirs() }
    private fun index(c: Context) = File(dir(c), "index.json")

    fun init(context: Context) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        runCatching {
            val o = JSONObject(index(c).readText())
            if (o.optInt("version") != VERSION) {
                dir(c).listFiles()?.forEach { it.delete() }
                return@runCatching
            }
            val items = o.getJSONObject("items")
            for (k in items.keys()) {
                val e = items.getJSONObject(k)
                val f = File(dir(c), e.getString("f"))
                if (f.isFile && f.length() > 0) {
                    ready[k] = Entry(f, e.optString("key"), e.optString("note"))
                    known[k] = true
                }
            }
        }
        // leftovers of conversions that were under way when the app ended
        dir(c).listFiles { f -> f.name.endsWith(".tmp.mov") }?.forEach { it.delete() }
    }

    private fun save(c: Context) = synchronized(ready) {
        runCatching {
            val items = JSONObject()
            ready.forEach { (k, e) -> items.put(k, JSONObject().put("f", e.file.name).put("key", e.key).put("note", e.note)) }
            val tmp = File(dir(c), "index.json.tmp")
            tmp.writeText(JSONObject().put("version", VERSION).put("items", items).toString())
            tmp.renameTo(index(c))
        }
    }

    // ------------------------------------------------------------------ what plays

    /**
     * The file to decode for [uri]: its intermediate when ready, null while a ProRes source has none (it can't be
     * decoded), otherwise [uri] itself – any other file is untouched.
     */
    fun playable(uri: Uri): Uri? {
        val k = uri.toString()
        ready[k]?.let { return Uri.fromFile(it.file) }
        return if (known[k] == true) null else uri
    }

    /** Is [uri] a ProRes source (null = not probed yet)? */
    fun isProRes(uri: Uri): Boolean? = known[uri.toString()]

    fun statusOf(uri: Uri): Status? {
        val k = uri.toString()
        if (known[k] != true) return null
        failures[k]?.let { return Status.Failed(it) }
        progress[k]?.let { return if (it < 0) Status.Queued else Status.Working(it) }
        return if (ready.containsKey(k)) Status.Ready else Status.Queued
    }

    /**
     * Looks into the file (a few kilobytes of its boxes, see [QtBoxes]): ProRes? and the camera's log curve
     * ([CameraLog]). Blocking – on a background thread; cached.
     */
    fun probe(context: Context, uri: Uri): Boolean {
        val k = uri.toString()
        known[k]?.let { return it }
        // (a file that can't be opened now – offline – is not remembered: looked at again when it is back)
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return false
        sizes[k] = pfd.statSize
        // (the stream owns the descriptor and closes it once – a FileInputStream on pfd.fileDescriptor would close it twice)
        val r = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ins -> runCatching { QtBoxes.of(ins.channel) }.getOrNull() }
        CameraLog.remember(uri, r?.let { CameraLog.detect(it.texts) })
        val pro = r != null && r.video.any { ProResPlan.isProRes(it) }
        known[k] = pro
        // a file replaced at the same address (another size): its old intermediate goes, it is converted anew
        val size = sizes[k]
        ready[k]?.let { e -> if (pro && size != null && size > 0 && e.key != ProResPlan.key(k, size, VERSION)) { ready.remove(k); e.file.delete(); save(context.applicationContext) } }
        if (pro) {
            se.gendut.klipp.CrashReport.note("prores: ${uri.lastPathSegment} ${r?.video} audio ${r?.audio} log ${CameraLog.of(uri)?.key}")
            ready[k]?.let { CameraLog.alias(uri, Uri.fromFile(it.file)) }
        }
        return pro
    }

    /**
     * The project's media: ProRes sources without an intermediate are queued (probing what hasn't been – in the
     * background). Called on every refresh; cheap when nothing is new.
     */
    fun ensure(context: Context, media: List<Pair<Uri, MediaInfo>>) {
        init(context)
        val c = context.applicationContext
        for ((u, m) in media) names[u.toString()] = m.name
        fun waiting(k: String) = !ready.containsKey(k) && !progress.containsKey(k) && !failures.containsKey(k)
        // known ProRes sources: queued at once
        for ((u, _) in media) {
            val k = u.toString()
            if (known[k] != true || !waiting(k)) continue
            progress[k] = -1
            cancelled.remove(k)
            worker.execute { run(c, u) }
        }
        // not probed yet (normally the import and the project's opening probe them): looked at in the background
        val unknown = media.map { it.first }.filter { u -> u.toString().let { k -> !known.containsKey(k) && probing.add(k) } }
        if (unknown.isEmpty()) return
        prober.execute {
            val found = unknown.filter { u -> probe(c, u).also { probing.remove(u.toString()) } }
            if (found.isEmpty()) return@execute
            main.post {
                for (u in found) {
                    val k = u.toString()
                    if (!waiting(k)) continue
                    progress[k] = -1
                    cancelled.remove(k)
                    worker.execute { run(c, u) }
                }
                // a source found to be ProRes only now: its clips stop trying to play the original
                revision++
                onChange?.invoke()
            }
        }
    }

    /** Stops [uri]'s conversion (queued or running): its media item was removed. Done again if it is used again. */
    fun cancel(uri: Uri) {
        val k = uri.toString()
        if (!progress.containsKey(k)) return
        cancelled += k
        progress.remove(k)
    }

    /**
     * The export: waits until every source in [uris] has its intermediate (queuing what isn't, e.g. after a failure in
     * an earlier session). Throws with the reason when one can't be made.
     */
    suspend fun await(context: Context, uris: Collection<Uri>) = withContext(Dispatchers.IO) {
        val c = context.applicationContext
        init(c)
        val todo = uris.filter { probe(c, it) }
        while (true) {
            var waiting = false
            for (u in todo) {
                val k = u.toString()
                if (ready.containsKey(k)) continue
                failures[k]?.let { error(Str.t(R.string.prores_export_failed, names[k] ?: u.lastPathSegment.orEmpty(), it)) }
                waiting = true
                if (!progress.containsKey(k)) {
                    withContext(Dispatchers.Main) { progress[k] = -1 }
                    cancelled.remove(k)
                    worker.execute { run(c, u) }
                }
            }
            if (!waiting) return@withContext
            delay(500)
        }
    }

    /**
     * Deletes the intermediates whose source no project uses any more ([inUse]: every address the projects refer to).
     */
    fun sweep(context: Context, inUse: Set<String>) {
        val c = context.applicationContext
        init(c)
        var changed = false
        for ((k, e) in ready.entries.toList()) {
            if (k in inUse || progress.containsKey(k)) continue
            ready.remove(k)
            e.file.delete()
            changed = true
            se.gendut.klipp.CrashReport.note("prores: intermediate of ${Uri.parse(k).lastPathSegment} deleted (no project uses it)")
        }
        if (changed) save(c)
    }

    fun size(context: Context): Long = dir(context).listFiles()?.filter { it.name.endsWith(".mov") }?.sumOf { it.length() } ?: 0L

    fun describe(): String = if (known.values.none { it }) "" else
        "prores: ${ready.size} ready" + (if (progress.isNotEmpty()) ", ${progress.size} converting" else "") +
            (if (failures.isNotEmpty()) ", ${failures.size} failed" else "") +
            ready.values.mapNotNull { it.note.takeIf { n -> n.isNotEmpty() } }.joinToString("") { " ($it)" } + "\n"

    // ------------------------------------------------------------------ the conversion (worker thread)

    private fun run(c: Context, uri: Uri) {
        val k = uri.toString()
        if (k in cancelled || ready.containsKey(k)) { main.post { progress.remove(k) }; return }
        val name = names[k] ?: uri.lastPathSegment.orEmpty()
        val key = ProResPlan.key(k, sizes[k] ?: -1L, VERSION)
        val out = File(dir(c), "$key.mov")
        val tmp = File(dir(c), "$key.tmp.mov")
        main.post { if (progress.containsKey(k)) progress[k] = 0; onMessage?.invoke(Str.t(R.string.prores_converting, name)) }
        se.gendut.klipp.CrashReport.note("prores: converting ${uri.lastPathSegment}")
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            val note = Transcode(c, uri, tmp, { p -> main.post { if (progress.containsKey(k)) progress[k] = p } }, { k in cancelled }).run()
            if (!tmp.renameTo(out)) error("the intermediate file couldn't be saved")
            ready[k] = Entry(out, key, note)
            save(c)
            CameraLog.alias(uri, Uri.fromFile(out))
            MediaProbe.probe(c, Uri.fromFile(out))
            se.gendut.klipp.CrashReport.note("prores: ${uri.lastPathSegment} ready in ${(android.os.SystemClock.elapsedRealtime() - t0) / 1000} s ($note), ${out.length() / 1_000_000} MB")
            main.post {
                progress.remove(k)
                revision++
                onMessage?.invoke(Str.t(R.string.prores_ready, name))
                onChange?.invoke()
            }
        } catch (e: Throwable) {
            tmp.delete()
            if (e is Cancelled) {
                se.gendut.klipp.CrashReport.note("prores: ${uri.lastPathSegment} called off")
                main.post { progress.remove(k) }
                return
            }
            val m = e.message ?: e.javaClass.simpleName
            se.gendut.klipp.CrashReport.note("prores error: ${uri.lastPathSegment}: $m")
            main.post {
                progress.remove(k)
                failures[k] = m
                revision++
                onMessage?.invoke(Str.t(R.string.prores_failed, name, m))
                onChange?.invoke()
            }
        }
    }

    private class Cancelled : Exception("called off")

    /** The HEVC encoder chosen for a source: its name, the size, and 10-bit (P010) or 8-bit input. */
    private data class EncoderPick(val name: String, val size: ProResPlan.Size, val tenBit: Boolean, val maxBitrate: Int?, val vbr: Boolean)

    private const val COLOR_FORMAT_P010 = 54 // MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 (API 33 name)

    /**
     * The phone's HEVC encoders, hardware first: Main10 with P010 input wanted (10 bits kept); 8-bit 4:2:0 only when no
     * encoder takes 10 bits. The size: the source's, or the largest the encoder takes ([ProResPlan.outputSize]).
     */
    private fun pickEncoders(w: Int, h: Int): List<EncoderPick> {
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && mime in it.supportedTypes.map { t -> t.lowercase() } }
            .sortedBy { if (android.os.Build.VERSION.SDK_INT >= 29 && it.isHardwareAccelerated) 0 else 1 }
        val out = ArrayList<EncoderPick>()
        for (tenBit in listOf(true, false)) for (info in all) {
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val vc = caps.videoCapabilities ?: continue
            if (tenBit) {
                if (caps.profileLevels.none { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }) continue
                if (COLOR_FORMAT_P010 !in caps.colorFormats) continue
            } else if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible !in caps.colorFormats) continue
            val size = ProResPlan.outputSize(w, h) { a, b -> runCatching { vc.isSizeSupported(a, b) }.getOrDefault(false) } ?: continue
            val vbr = runCatching { caps.encoderCapabilities!!.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) }.getOrDefault(false)
            out += EncoderPick(info.name, size, tenBit, runCatching { vc.bitrateRange.upper }.getOrNull(), vbr)
        }
        return out
    }

    /** One conversion: FFmpeg decodes, the encoder encodes, FFmpeg muxes. Returns a note for the trace (encoder, size). */
    private class Transcode(val c: Context, val uri: Uri, val out: File, val onProgress: (Int) -> Unit, val cancelled: () -> Boolean) {
        fun run(): String {
            ProResNative.loadError?.let { throw IllegalStateException(Str.t(R.string.prores_no_decoder), it) }
            val pfd = c.contentResolver.openFileDescriptor(uri, "r") ?: error("the file can't be opened")
            var h = 0L
            var codec: MediaCodec? = null
            val boost = Perf.Boost(c)
            try {
                h = ProResNative.open(pfd.fd)
                val info = JSONObject(ProResNative.info(h))
                val v = info.optJSONObject("video") ?: error("the file has no picture")
                if (v.optString("codec") != "prores") error("the picture is ${v.optString("codec")}, not ProRes")
                val w = v.getInt("width")
                val ht = v.getInt("height")
                val fps = ProResPlan.fps(v.optInt("fpsNum"), v.optInt("fpsDen"))
                val dur = info.optLong("durationUs", -1L)
                val colors = ProResPlan.colors(v.optInt("primaries", 2), v.optInt("transfer", 2), v.optInt("matrix", 2), v.optInt("range", 0))
                val tracks = info.optJSONArray("audio")?.let { a ->
                    (0 until a.length()).map { i -> a.getJSONObject(i).let { t -> ProResPlan.AudioTrack(t.getInt("index"), t.optString("codec"), t.optInt("channels"), t.optInt("rate"), t.optBoolean("decodable")) } }
                }.orEmpty()
                val plan = ProResPlan.audioPlan(tracks)
                // the encoder: the first that configures (a 10-bit one first)
                val picks = pickEncoders(w, ht)
                if (picks.isEmpty()) error(Str.t(R.string.prores_no_encoder, w, ht))
                var pick: EncoderPick? = null
                var lastErr: Throwable? = null
                for (p in picks) {
                    val mc = runCatching { MediaCodec.createByCodecName(p.name) }.getOrNull() ?: continue
                    val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, p.size.width, p.size.height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, if (p.tenBit) COLOR_FORMAT_P010 else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                        setInteger(MediaFormat.KEY_BIT_RATE, ProResPlan.bitrate(p.size.width, p.size.height, fps, p.maxBitrate))
                        if (p.vbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        setFloat(MediaFormat.KEY_FRAME_RATE, (if (fps > 0) fps else 30.0).toFloat())
                        // a keyframe every second: seeking in the intermediate decodes at most a second
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_PROFILE, if (p.tenBit) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, colors.standard)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, colors.transfer)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, colors.range)
                        // frames in order (the muxer gets no decoding times from MediaCodec); not real time
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        setInteger(MediaFormat.KEY_PRIORITY, 1)
                    }
                    try {
                        mc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        codec = mc; pick = p
                        break
                    } catch (e: Throwable) {
                        lastErr = e
                        runCatching { mc.release() }
                    }
                }
                val enc = codec ?: error("the encoder refused the format (${lastErr?.message ?: "no encoder"})")
                val p = pick!!
                val outW = p.size.width
                val outH = p.size.height
                when (plan) {
                    is ProResPlan.AudioPlan.Mix -> ProResNative.prepare(h, out.absolutePath, Runtime.getRuntime().availableProcessors(), plan.streams.toIntArray(), plan.matrix, plan.outChannels, -1)
                    is ProResPlan.AudioPlan.Copy -> ProResNative.prepare(h, out.absolutePath, Runtime.getRuntime().availableProcessors(), null, null, 0, plan.stream)
                    ProResPlan.AudioPlan.None -> ProResNative.prepare(h, out.absolutePath, Runtime.getRuntime().availableProcessors(), null, null, 0, -1)
                }
                val note = "${p.name} ${outW}x$outH ${if (p.tenBit) "10" else "8"}-bit" + (if (p.size.scaledFrom(w, ht)) " scaled from ${w}x$ht" else "") +
                    " audio " + when (plan) { is ProResPlan.AudioPlan.Mix -> "${plan.streams.size} tracks → ${plan.outChannels} ch"; is ProResPlan.AudioPlan.Copy -> "copied"; else -> "none" }
                se.gendut.klipp.CrashReport.note("prores: $note, ${ProResPlan.bitrate(outW, outH, fps, p.maxBitrate) / 1_000_000} Mbit/s")
                enc.start()
                encode(enc, h, outW, outH, if (p.tenBit) 10 else 8, colors, dur)
                ProResNative.finish(h)
                return note
            } finally {
                boost.close()
                codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
                if (h != 0L) ProResNative.close(h)
                runCatching { pfd.close() }
            }
        }

        private fun encode(enc: MediaCodec, h: Long, w: Int, ht: Int, bits: Int, colors: ProResPlan.Colors, dur: Long) {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var muxing = false
            var lastOut = android.os.SystemClock.elapsedRealtime()
            var lastPct = -1
            val bytes = if (bits == 10) 2 else 1
            while (true) {
                if (cancelled()) throw Cancelled()
                if (!inputDone) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val cap = enc.getInputBuffer(i)?.capacity() ?: 0
                        val img = runCatching { enc.getInputImage(i) }.getOrNull()
                        val pts = if (img != null && img.planes.size >= 3) {
                            val pl = img.planes
                            ProResNative.readFrame(h, pl[0].buffer, pl[0].rowStride, pl[1].buffer, pl[1].rowStride, pl[1].pixelStride,
                                pl[2].buffer, pl[2].rowStride, pl[2].pixelStride, w, ht, bits)
                        } else {
                            // no Image: the buffer as semi-planar 4:2:0 (P010 / NV12) with the format's stride and slice height
                            val buf = enc.getInputBuffer(i) ?: error("the encoder gave no input buffer")
                            val fmt = enc.inputFormat
                            var stride = if (fmt.containsKey(MediaFormat.KEY_STRIDE)) fmt.getInteger(MediaFormat.KEY_STRIDE) else w * bytes
                            // (some encoders state the stride in pixels)
                            if (stride < w * bytes) stride *= bytes
                            val slice = if (fmt.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) fmt.getInteger(MediaFormat.KEY_SLICE_HEIGHT).coerceAtLeast(ht) else ht
                            val y = buf.duplicate().apply { clear() }.slice()
                            val u = buf.duplicate().apply { clear(); position(stride * slice) }.slice()
                            val v = buf.duplicate().apply { clear(); position(stride * slice + bytes) }.slice()
                            ProResNative.readFrame(h, y, stride, u, stride, 2 * bytes, v, stride, 2 * bytes, w, ht, bits)
                        }
                        if (pts < 0) {
                            enc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            enc.queueInputBuffer(i, 0, if (cap > 0) cap else w * ht * 3 / 2 * bytes, pts, 0)
                            if (dur > 0) {
                                val pct = (pts * 100 / dur).toInt().coerceIn(0, 99)
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
                // everything the encoder has ready
                while (true) {
                    val o = enc.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (android.os.SystemClock.elapsedRealtime() - lastOut > 30_000) error("the encoder stopped giving pictures")
                        break
                    }
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxing) {
                            val f = enc.outputFormat
                            val csd = listOfNotNull(f.getByteBuffer("csd-0"), f.getByteBuffer("csd-1"), f.getByteBuffer("csd-2"))
                            if (csd.isNotEmpty()) {
                                ProResNative.startMux(h, csd.fold(ByteArray(0)) { acc, b -> acc + b.duplicate().let { d -> ByteArray(d.remaining()).also { d.get(it) } } },
                                    w, ht, colors.avPrimaries, colors.avTransfer, colors.avMatrix, colors.avRange)
                                muxing = true
                            }
                        }
                        continue
                    }
                    if (o < 0) continue
                    lastOut = android.os.SystemClock.elapsedRealtime()
                    val buf = enc.getOutputBuffer(o)
                    if (buf != null && info.size > 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (!muxing) {
                                val d = buf.duplicate().apply { position(info.offset); limit(info.offset + info.size) }
                                ProResNative.startMux(h, ByteArray(d.remaining()).also { d.get(it) }, w, ht, colors.avPrimaries, colors.avTransfer, colors.avMatrix, colors.avRange)
                                muxing = true
                            }
                        } else {
                            if (!muxing) error("the encoder gave no parameter sets")
                            ProResNative.writeVideo(h, buf, info.offset, info.size, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { onProgress(100); return }
                }
            }
        }
    }

    internal fun putForTest(uri: Uri, file: File?, isProRes: Boolean = true) {
        known[uri.toString()] = isProRes
        if (file != null) ready[uri.toString()] = Entry(file, "test", "")
    }

    internal fun clearForTest() { ready.clear(); known.clear(); progress.clear(); failures.clear() }
}
