package se.gendut.klipp.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue

/**
 * Develops a MotionCam .mcraw into a log clip (10-bit HEVC, BT.2020, limited range) that Klipp then edits like any log
 * clip – S-Log3 / S-Gamut3.Cine by default (OpenDRT's default input: no setting to change), or another log Klipp reads.
 *
 * Per frame, on the GPU: the frame's own black level, the lens shading map (MotionCam doesn't bake it in), the as-shot
 * white balance, a Malvar–He–Cutler demosaic, the camera's ForwardMatrix1 (ColorMatrix1 when there is none, or when it
 * is only a placeholder – see [isPlaceholderForwardMatrix]) to XYZ, Bradford to D65, the output gamut and its log
 * curve. The sensor's clip lands log2(EI/25) stops over 18 % grey (EI from the picker or Auto, as on a log camera);
 * clipped highlights stay neutral. The frames are decoded on other threads while the GPU and the encoder work, and the
 * file's sound is encoded to AAC beside them and muxed in. The file's own size when the encoder takes it.
 */
object McrawConvert {
    /** Stops of the sensor's clip over 18 % grey at [ei] (Klipp's EI terms: EI 800 = 5 stops). */
    fun headroom(ei: Int): Float = kotlin.math.log2(ei / 25f)

    /** EIs Auto picks from (one-third stops) – up to 3200, which every output curve holds. */
    private val AUTO_EIS = listOf(100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200)

    /**
     * "Auto" EI – the EI that brings the middle frame's average (the log average of its green sites, the picture only)
     * to 18 % grey, as an exposure meter would: linear = raw × 0.18 × EI / 25, so EI = 25 / average. Rounded to
     * one-third stops, 100–3200. null = the file could not be read. Blocking.
     */
    fun autoEi(ctx: Context, src: Uri): Int? {
        val source = Mcraw.openSource(ctx, src) ?: return null
        val h = source.h
        try {
            val info = JSONObject(Mcraw.info(h))
            val c = info.getJSONObject("container")
            val n = info.getJSONArray("frames").length()
            val ff = info.getJSONObject("firstFrame")
            val w = ff.getInt("width"); val hh = ff.getInt("height")
            val buf = ByteBuffer.allocateDirect(w * hh * 2).order(ByteOrder.LITTLE_ENDIAN)
            val meta = Mcraw.decodeTo(h, n / 2, buf)?.let { JSONObject(it) } ?: return null
            val blackMax = (c.optJSONArray("blackLevel")?.let { a -> (0 until a.length()).maxOfOrNull { a.getDouble(it) } } ?: 0.0).toInt() + 1
            val empty = Mcraw.emptyBottomRows(h, blackMax)
            val ch = if (empty >= hh / 20) hh - empty else hh
            val black = meta.optJSONArray("dynamicBlackLevel") ?: c.optJSONArray("blackLevel") ?: JSONArray(listOf(0, 0, 0, 0))
            val white = meta.optDouble("dynamicWhiteLevel", c.optDouble("whiteLevel", 1023.0))
            val pattern = when (c.optString("sensorArrangment", "rggb").lowercase()) { "bggr" -> "BGGR"; "grbg" -> "GRBG"; "gbrg" -> "GBRG"; else -> "RGGB" }
            val sb = buf.asShortBuffer()
            var sum = 0.0; var count = 0
            // every 8th row and column (both even, so the CFA position is the same), plus one: a green site
            for (y0 in 0 until ch - 1 step 8) for (x0 in 0 until w - 1 step 8) {
                val p0 = 0
                val (x, y) = when {
                    pattern[p0] == 'G' -> x0 to y0
                    pattern[1] == 'G' -> x0 + 1 to y0
                    else -> x0 to y0 + 1
                }
                val pos = (y and 1) * 2 + (x and 1)
                val bl = black.optDouble(pos, 0.0)
                val v = ((sb.get(y * w + x).toInt() and 0xFFFF) - bl) / (white - bl)
                sum += kotlin.math.ln(v.coerceIn(1.0 / 4096, 1.0)); count++
            }
            if (count == 0) return null
            val avg = kotlin.math.exp(sum / count)
            val ei = 25.0 / avg
            return AUTO_EIS.minByOrNull { kotlin.math.abs(kotlin.math.ln(it / ei)) }
        } catch (e: Throwable) {
            return null
        } finally {
            source.close()
        }
    }

    class Result(val uri: Uri?, val summary: String, val log: String)

    /**
     * Develops [src] into [outFile] (written as .part, renamed when done) at [ei] (≤ 0: Auto, see [autoEi]) into [out]
     * (an [OpenDrt.Input] key). [draft]: half size, each 2×2 CFA block one pixel (no demosaic) – a quick develop for
     * editing; the full one follows (McrawStore). Blocking: call off the main thread.
     */
    fun convert(ctx: Context, src: Uri, outFile: java.io.File, ei: Int, out: String = OpenDrt.Input.SLOG3.key,
                draft: Boolean = false, progress: (Float) -> Unit): Result {
        val outIn = OpenDrt.Input.of(out)
        // the develop runs below the app's own threads (decoding, sound and drawing alike), so the app stays responsive
        // while clips develop – the screen was hard to use during an import
        val myTid = android.os.Process.myTid()
        val oldPriority = runCatching { android.os.Process.getThreadPriority(myTid) }.getOrDefault(0)
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try { return convert0(ctx, src, draft, outFile, ei, outIn, progress) }
        finally { runCatching { android.os.Process.setThreadPriority(myTid, oldPriority) } }
    }

    private fun convert0(ctx: Context, src: Uri, draft: Boolean, outFile: java.io.File, ei0: Int, outIn: OpenDrt.Input,
                         progress: (Float) -> Unit): Result {
        val tCall = System.nanoTime()
        val log = StringBuilder()
        // (the sound thread writes here too)
        fun line(s: String) { synchronized(log) { log.append(s).append('\n') } }
        if (!Mcraw.available) return Result(null, "MCRAW library not loaded", "")
        val name = runCatching {
            ctx.contentResolver.query(src, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "mcraw"
        val source = Mcraw.openSource(ctx, src) ?: return Result(null, "Could not open it as MotionCam RAW (.mcraw)", "")
        val h = source.h
        val part = java.io.File(outFile.path + ".part")
        try {
            val info = JSONObject(Mcraw.info(h))
            val c = info.getJSONObject("container")
            val frames = info.getJSONArray("frames")
            val n = frames.length()
            val ff = info.getJSONObject("firstFrame")
            val w = ff.getInt("width"); val hh = ff.getInt("height")
            val t0ns = frames.getLong(0)
            val fps = if (n > 1) (n - 1) / ((frames.getLong(n - 1) - t0ns) / 1e9) else 30.0
            line("convert: $name  ${w}x$hh  $n frames  ${"%.3f".format(fps)} fps")

            // MotionCam pads the frame's buffer below the picture (4096×3072 holding a 4096×2560 picture): those rows,
            // all at the black level with no noise, are left out – the clip is the picture's own height
            val blackMax = (c.optJSONArray("blackLevel")?.let { a -> (0 until a.length()).maxOfOrNull { a.getDouble(it) } } ?: 0.0).toInt() + 1
            val probeBuf = ByteBuffer.allocateDirect(w * hh * 2)
            val empty = if (Mcraw.decodeTo(h, n / 2, probeBuf) != null) Mcraw.emptyBottomRows(h, blackMax) else 0
            val ch = if (empty >= hh / 20) ((hh - empty) / 16 * 16).coerceAtLeast(16) else hh
            if (ch != hh) line("picture height: $ch of $hh (the rest is padding)")

            // the encoder's size: the picture's own, else the largest of the same shape it takes
            val (ow, oh, codecName) = (if (draft) pickSize(w / 2, ch / 2, fps) else pickSize(w, ch, fps)) ?: return Result(null, "No HEVC 10-bit encoder for this size", log.toString())
            line("encoder: $codecName  ${ow}x$oh")
            val bitrate = (ow.toLong() * oh * fps * 0.25).toLong().coerceIn(20_000_000L, 400_000_000L).toInt()
            line("bitrate: ${bitrate / 1_000_000} Mb/s")

            val tAuto = System.nanoTime()
            val ei = ei0.takeIf { it > 0 } ?: autoEi(ctx, src) ?: 800
            if (ei0 <= 0) line("auto EI: $ei in ${ms(System.nanoTime() - tAuto)} ms")
            line("developed at EI $ei (sensor clip ${"%.1f".format(headroom(ei))} stops over 18 % grey)")
            val cfa = c.optString("sensorArrangment", "rggb")
            if (arr(c, "forwardMatrix1")?.let { isPlaceholderForwardMatrix(it) } == true) line("forwardMatrix1 is the sRGB placeholder: colorMatrix1 used")
            val camToOut = colorMatrix(c, ff, outIn.gamut)
            line("cfa $cfa  camera → ${outIn.label}: ${camToOut.joinToString { "%.4f".format(it) }}")

            part.parentFile?.mkdirs()
            val outPfd = android.os.ParcelFileDescriptor.open(part, android.os.ParcelFileDescriptor.MODE_CREATE or
                android.os.ParcelFileDescriptor.MODE_TRUNCATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE)

            val tStart = System.nanoTime()
            line("before the frames: ${ms(tStart - tCall)} ms (open, picture height, EI, colour, output file)")
            // the sound: AAC, encoded on its own thread while the frames are developed (it took 2-4 s before them).
            // The muxer needs its format to start, which the encoder gives at once; the samples are written at the end.
            val soundFormat = java.util.concurrent.CompletableFuture<MediaFormat?>()
            var sound: Sound? = null
            var soundMs = 0L
            val soundThread = Thread {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                val ts = System.nanoTime()
                sound = runCatching { encodeAudio(h, info.optInt("audioRate"), info.optInt("audioChannels"), t0ns) { soundFormat.complete(it) } }
                    .onFailure { line("sound: failed – $it") }.getOrNull()
                soundFormat.complete(null)
                soundMs = (System.nanoTime() - ts) / 1_000_000
            }.apply { this.name = "mcraw-sound"; start() }
            // where the time goes, per stage (for optimizing): decode on the reader thread; on this one the
            // wait for a decoded frame, the upload and draw (texture upload is a CPU copy), the swap (blocks when the
            // encoder has no free input buffer or the GPU is behind) and the drain to the muxer
            val tDecode = java.util.concurrent.atomic.AtomicLong(0)
            var tWait = 0L; var tDraw = 0L; var tSwap = 0L; var tDrain = 0L; var uploadNs = 0L
            val bytes = w * hh * 2
            // decoded frames, in order, from READERS threads, each with its own handle on the file: thread t
            // decodes frames t, t + READERS, … into its own queue, and the frames are taken from the queues in turn.
            // One decode thread was the limit (13 ms a frame, 21-27 ms with other work running). Buffers: each thread
            // holds at most one queued and one being decoded, plus the one being drawn.
            class Frame(val index: Int, val buf: ByteBuffer, val meta: JSONObject?)
            val READERS = readerCount(draft)
            val nBuf = READERS * 2 + 1
            val free = ArrayBlockingQueue<ByteBuffer>(nBuf).apply { repeat(nBuf) { add(ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)) } }
            val readyQ = List(READERS) { ArrayBlockingQueue<Frame>(1) }
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val readers = List(READERS) { t ->
                Thread {
                    runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                    val rs = Mcraw.openSource(ctx, src)
                    val rh = rs?.h ?: 0L
                    try {
                        var i = t
                        while (i < n) {
                            if (stop.get()) break
                            val b = free.take()
                            val td = System.nanoTime()
                            val m = if (rh != 0L) Mcraw.decodeTo(rh, i, b) else null
                            tDecode.addAndGet(System.nanoTime() - td)
                            readyQ[t].put(Frame(i, b, m?.let { JSONObject(it) }))
                            i += READERS
                        }
                    } catch (_: InterruptedException) {
                    } catch (e: Throwable) {
                        android.util.Log.e("KlippMcraw", "reader", e)
                    } finally { rs?.close() }
                }.apply { this.name = "mcraw-read-$t"; start() }
            }

            var encoded = 0
            var failed = 0
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, ow, oh).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat())
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    // log picture in BT.2020 primaries. The transfer must be given: left out, the Qualcomm encoder tagged
                    // the file PQ (ST 2084) and Klipp took it for HDR (no EI, tone-mapped preview). S-Log3 has no tag of
                    // its own, so SDR video, as the clips Klipp edits as log. Limited range: the encoder writes limited
                    // codes from the surface whatever the range asked for (it tagged them full).
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                }
                val codec = MediaCodec.createByCodecName(codecName)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = codec.createInputSurface()
                codec.start()
                val muxer = MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var track = -1
                var audioTrack = -1
                val info2 = MediaCodec.BufferInfo()
                fun drain(end: Boolean) {
                    // (at the end: the encoder's end of stream – or, when none comes, what it has given within 5 s: an
                    // .mcraw whose frame times stepped back left the encoder without one, and the import waited forever)
                    var quiet = 0
                    while (true) {
                        val i = codec.dequeueOutputBuffer(info2, if (end) 10_000 else 0)
                        if (i >= 0 || i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) quiet = 0
                        when {
                            i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return else if (++quiet >= 500) {
                                line("end of stream: none from the encoder within 5 s – the file ends with what it gave")
                                return
                            }
                            i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                track = muxer.addTrack(codec.outputFormat)
                                audioTrack = runCatching { soundFormat.get(10, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()?.let { muxer.addTrack(it) } ?: -1
                                muxer.start()
                            }
                            i >= 0 -> {
                                val ob = codec.getOutputBuffer(i)!!
                                if (info2.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info2.size = 0
                                if (info2.size > 0 && track >= 0) { ob.position(info2.offset); ob.limit(info2.offset + info2.size); muxer.writeSampleData(track, ob, info2) }
                                codec.releaseOutputBuffer(i, false)
                                if (info2.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                            }
                        }
                    }
                }

                var lastPts = Long.MIN_VALUE
                var stepped = 0
                val frameNs = (1e9 / fps).toLong().coerceAtLeast(1L)
                val gl = Gl(inputSurface, w, hh, ch, ow, oh)
                try {
                    gl.setup(cfa, c, camToOut, headroom(ei), draft, outIn.oetf)
                    for (k in 0 until n) {
                        var t = System.nanoTime()
                        val f = readyQ[k % READERS].poll(20, java.util.concurrent.TimeUnit.SECONDS) ?: error("reading the frames stopped at $k")
                        var t2 = System.nanoTime(); tWait += t2 - t; t = t2
                        if (f.meta == null) { failed++; free.put(f.buf); continue }
                        gl.drawFrame(f.buf, f.meta, c)
                        free.put(f.buf)
                        t2 = System.nanoTime(); tDraw += t2 - t; t = t2
                        // the frame's time, always after the one before: the encoder drops a frame whose time doesn't
                        // move on (a file's clock stepping back or repeating)
                        var pts = frames.getLong(f.index) - t0ns
                        if (lastPts != Long.MIN_VALUE && pts <= lastPts) { pts = lastPts + frameNs; stepped++ }
                        lastPts = pts
                        gl.present(pts)
                        t2 = System.nanoTime(); tSwap += t2 - t; t = t2
                        encoded++
                        drain(false)
                        tDrain += System.nanoTime() - t
                        if (k % 4 == 0) progress(k / n.toFloat())
                    }
                } finally { uploadNs = gl.tUpload; gl.release() }
                if (stepped > 0) line("frame times: $stepped did not move on – each put one frame after the one before")
                val tEnd = System.nanoTime()
                codec.signalEndOfInputStream()
                drain(true)
                line("end of stream: ${ms(System.nanoTime() - tEnd)} ms")
                codec.stop(); codec.release()
                val tJoin = System.nanoTime()
                soundThread.join()
                line("sound: encoded in $soundMs ms beside the frames (waited ${ms(System.nanoTime() - tJoin)} ms at the end)")
                val snd = sound
                if (snd != null) line("sound: ${snd.samples.size} AAC frames, starts at ${"%.3f".format(snd.offsetUs / 1e6)} s")
                if (snd != null && audioTrack >= 0 && track >= 0) for ((data, bi) in snd.samples) muxer.writeSampleData(audioTrack, ByteBuffer.wrap(data), bi)
                if (track >= 0) muxer.stop()
                muxer.release()
            } finally {
                stop.set(true)
                readers.forEach { it.interrupt() }; readers.forEach { it.join(2000) }
                soundThread.join(30_000) // it reads the file's handle, closed after this
                outPfd.close()
            }
            val secs = (System.nanoTime() - tStart) / 1e9
            val per = encoded.coerceAtLeast(1) * 1e6
            line("per frame (ms): decode ${"%.1f".format(tDecode.get() / per)} (each, on $READERS threads) · wait ${"%.1f".format(tWait / per)} · " +
                "upload+draw ${"%.1f".format(tDraw / per)} (upload ${"%.1f".format(uploadNs / per)}) · swap ${"%.1f".format(tSwap / per)} · drain ${"%.1f".format(tDrain / per)}")
            line("total with the steps before: ${"%.1f".format((System.nanoTime() - tCall) / 1e9)} s")
            if (!part.renameTo(outFile)) error("could not write ${outFile.name}")
            progress(1f)
            line("encoded $encoded frames (failed $failed) in ${"%.1f".format(secs)} s → ${"%.1f".format(encoded / secs)} fps (${"%.2f".format(encoded / secs / fps)}× real time)")
            val summary = "Converted $encoded frames in ${"%.1f".format(secs)} s (${"%.1f".format(encoded / secs)} fps, ${"%.2f".format(encoded / secs / fps)}× real time)\n" +
                outFile.name + " – " + "${ow}×$oh 10-bit HEVC${if (sound != null) " with sound" else ", no sound"}. In Klipp: input ${outIn.label}, developed at EI $ei."
            appendLog(ctx, summary, log)
            return Result(Uri.fromFile(outFile), summary, log.toString())
        } catch (e: Throwable) {
            line("error: $e")
            appendLog(ctx, "Conversion failed: ${e.message}", log)
            runCatching { part.delete() }
            return Result(null, "Conversion failed: ${e.message}", log.toString())
        } finally {
            source.close()
        }
    }

    private fun ms(ns: Long) = ns / 1_000_000

    /**
     * Threads decoding the .mcraw's frames (each with its own handle). Two for the full develop (its GPU side is the
     * limit, ~15 ms a frame); three for the quick one, which waited for the decode (16 ms a frame each).
     */
    private fun readerCount(draft: Boolean) = if (draft) 3 else 2

    /**
     * Every develop adds its log to Android/data/<app>/files/mcraw_log.txt (newest last, kept under ~1 MB), so a series
     * of imports can be timed afterwards.
     */
    private fun appendLog(ctx: Context, summary: String, log: StringBuilder) = runCatching {
        val f = java.io.File(ctx.getExternalFilesDir(null), "mcraw_log.txt")
        if (f.length() > 1_000_000) f.writeText(f.readText().takeLast(500_000))
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        f.appendText("=== $stamp\n$summary\n$log\n")
    }

    private class Sound(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>, val offsetUs: Long)

    /**
     * The file's sound as AAC (256 kb/s), timed against the first frame ([t0ns], the frames' clock). Sound before the
     * first frame is left out; when the clocks don't match (more than 2 s apart) it starts with the picture. Null when
     * the file has none.
     */
    private fun encodeAudio(h: Long, rate: Int, channels: Int, t0ns: Long, onFormat: (MediaFormat) -> Unit = {}): Sound? {
        if (rate <= 0 || channels <= 0) return null
        val (pcm, startNs) = Mcraw.audio(h) ?: return null
        var offsetUs = (startNs - t0ns) / 1000
        if (kotlin.math.abs(offsetUs) > 2_000_000) offsetUs = 0
        var first = 0 // first sample frame to use
        if (offsetUs < 0) { first = (-offsetUs * rate / 1_000_000).toInt(); offsetUs = 0 }
        val frames = pcm.size / channels
        if (first >= frames) return null
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val out = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        var pos = first
        var inputDone = false
        val bi = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val n = minOf(frames - pos, ib.capacity() / (2 * channels))
                        val pts = offsetUs + (pos - first).toLong() * 1_000_000 / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            ib.clear(); ib.asShortBuffer().put(pcm, pos * channels, n * channels)
                            codec.queueInputBuffer(ii, 0, n * channels * 2, pts, 0); pos += n
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(bi, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFormat = codec.outputFormat; onFormat(codec.outputFormat) }
                else if (oi >= 0) {
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bi.size > 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        val data = ByteArray(bi.size); ob.position(bi.offset); ob.get(data)
                        out += data to MediaCodec.BufferInfo().apply { set(0, bi.size, bi.presentationTimeUs, bi.flags) }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally { codec.stop(); codec.release() }
        return outFormat?.let { Sound(it, out, offsetUs) }
    }

    /** The encoder and size: HEVC Main10 with surface input, the file's size or the largest of the same shape. */
    internal fun pickSize(w: Int, h: Int, fps: Double): Triple<Int, Int, String>? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } && !ci.isSoftwareOnly &&
                ci.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
        }
        for (ci in infos) {
            val vc = ci.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities ?: continue
            var tw = w
            while (tw >= 1280) {
                // the exact size first (even sides): rounded down to 16 lines, a half-size 3840×2160 develop was
                // 1920×1072, and a project made from it 3840×2144
                val tw2 = tw / 2 * 2
                val th2 = ((tw.toLong() * h / w).toInt()) / 2 * 2
                if (vc.isSizeSupported(tw2, th2)) return Triple(tw2, th2, ci.name)
                val th = ((tw.toLong() * h / w) / 16 * 16).toInt()
                val tw16 = tw / 16 * 16
                if (vc.isSizeSupported(tw16, th) && (vc.areSizeAndRateSupported(tw16, th, fps) || vc.isSizeSupported(tw16, th))) return Triple(tw16, th, ci.name)
                tw -= 256
            }
        }
        return null
    }

    // ---- colour ----

    private fun arr(o: JSONObject, key: String): FloatArray? = o.optJSONArray(key)?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }

    /**
     * 3×3 row-major: white-balanced camera RGB → the linear output gamut ([outToXyz], S-Gamut3.Cine by default; D65),
     * from the file's ForwardMatrix1, ColorMatrix1 and the first frame's as-shot neutral (see [cameraToOut]).
     */
    internal fun colorMatrix(c: JSONObject, ff: JSONObject, outToXyz: FloatArray = OpenDrt.M.SGAMUT3CINE_TO_XYZ): FloatArray =
        cameraToOut(arr(c, "forwardMatrix1"), arr(c, "colorMatrix1"), arr(ff, "asShotNeutral"), outToXyz)

    /**
     * 3×3 row-major: white-balanced camera RGB → the linear output gamut ([outToXyz]; D65). With a real forward matrix
     * [fm] (white-balanced camera → XYZ D50, DNG's ForwardMatrix1): it, then Bradford D50 → D65. Without one – or with
     * the sRGB placeholder some phones write ([isPlaceholderForwardMatrix]) – the colour matrix [cm] (XYZ → camera, DNG's
     * ColorMatrix1) inverted, the [neutral] (as-shot, camera RGB) as the white, Bradford from that white to D65. The rows
     * are then scaled so the camera's neutral stays neutral in a gamut with another white (ACES AP1).
     */
    internal fun cameraToOut(fm: FloatArray?, cm: FloatArray?, neutral: FloatArray?, outToXyz: FloatArray): FloatArray {
        val m = cameraToOut0(fm, cm, neutral, outToXyz)
        val sums = FloatArray(3) { r -> m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2] }
        if (sums.any { it <= 1e-6f }) return m
        return FloatArray(9) { i -> m[i] * sums[1] / sums[i / 3] }
    }

    private fun cameraToOut0(fm: FloatArray?, cm: FloatArray?, neutral: FloatArray?, outToXyz: FloatArray): FloatArray {
        val xyzToOut = inv(outToXyz)
        if (fm != null && fm.size == 9 && fm.any { it != 0f } && !isPlaceholderForwardMatrix(fm)) return mul(xyzToOut, mul(BRADFORD_D50_D65, fm))
        // no (real) forward matrix: XYZ = inverse(ColorMatrix1) · neutral for the white, adapted to D65 and scaled so
        // the neutral maps to Y = 1
        val camToXyz = cm?.takeIf { it.size == 9 && kotlin.math.abs(det(it)) > 1e-9f }?.let { inv(it) } ?: return mul(xyzToOut, OpenDrt.M.REC2020_TO_XYZ)
        val n = neutral?.takeIf { it.size >= 3 && it.all { v -> v > 0f } } ?: floatArrayOf(1f, 1f, 1f)
        val m = mul(camToXyz, floatArrayOf(n[0], 0f, 0f, 0f, n[1], 0f, 0f, 0f, n[2]))
        val white = FloatArray(3) { r -> m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2] }
        val adapted = if (white.all { it > 1e-6f }) mul(bradford(white, D65), m) else m
        val y = adapted[3] + adapted[4] + adapted[5]
        if (y <= 1e-6f) return mul(xyzToOut, OpenDrt.M.REC2020_TO_XYZ)
        return mul(xyzToOut, FloatArray(9) { adapted[it] / y })
    }

    /**
     * The forward matrix is only sRGB → XYZ D50 (Bradford), what some phones write instead of a calibration (the
     * OnePlus 12 does): it says the white-balanced camera already is sRGB – no colour correction at all, wrong colours –
     * while their colour matrices are calibrated. Such a forward matrix is ignored and the colour matrix used, as DNG
     * does without one. Compared with its rows scaled so (1, 1, 1) maps to D50, as DNG normalizes forward matrices.
     */
    internal fun isPlaceholderForwardMatrix(fm: FloatArray): Boolean {
        if (fm.size != 9) return false
        for (r in 0 until 3) {
            val s = fm[r * 3] + fm[r * 3 + 1] + fm[r * 3 + 2]
            if (kotlin.math.abs(s) < 1e-6f) return false
            for (k in 0 until 3) if (kotlin.math.abs(fm[r * 3 + k] * D50[r] / s - SRGB_TO_XYZ_D50[r * 3 + k]) >= 0.03f) return false
        }
        return true
    }

    /** sRGB → XYZ D50 (Bradford-adapted), the placeholder forward matrix. */
    private val SRGB_TO_XYZ_D50 = floatArrayOf(
        0.4360747f, 0.3850649f, 0.1430804f,
        0.2225045f, 0.7168786f, 0.0606169f,
        0.0139322f, 0.0971045f, 0.7141733f)
    private val D50 = floatArrayOf(0.9642f, 1f, 0.8249f)
    private val D65 = floatArrayOf(0.95047f, 1f, 1.08883f)
    private val BRADFORD_D50_D65 = floatArrayOf(
        0.9555766f, -0.0230393f, 0.0631636f,
        -0.0282895f, 1.0099416f, 0.0210077f,
        0.0122982f, -0.0204830f, 1.3299098f)
    private val BRADFORD = floatArrayOf(
        0.8951f, 0.2664f, -0.1614f,
        -0.7502f, 1.7135f, 0.0367f,
        0.0389f, -0.0685f, 1.0296f)

    /** Bradford chromatic adaptation (XYZ → XYZ) from the white [src] to the white [dst] (XYZ). */
    private fun bradford(src: FloatArray, dst: FloatArray): FloatArray {
        fun lms(v: FloatArray) = FloatArray(3) { r -> BRADFORD[r * 3] * v[0] + BRADFORD[r * 3 + 1] * v[1] + BRADFORD[r * 3 + 2] * v[2] }
        val s = lms(src); val d = lms(dst)
        val scale = floatArrayOf(d[0] / s[0], 0f, 0f, 0f, d[1] / s[1], 0f, 0f, 0f, d[2] / s[2])
        return mul(inv(BRADFORD), mul(scale, BRADFORD))
    }

    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i -> val r = i / 3; val k = i % 3; a[r * 3] * b[k] + a[r * 3 + 1] * b[3 + k] + a[r * 3 + 2] * b[6 + k] }

    private fun det(m: FloatArray) = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    private fun inv(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return floatArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det)
    }

    // ---- GPU ----
    /** [h] the frame's rows (texture), [ch] the rows with picture (from the top), [ow] × [oh] the output (10-bit, the encoder's surface). */
    internal class Gl(surface: android.view.Surface, val w: Int, val h: Int, val ch: Int, val ow: Int, val oh: Int) {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val context: EGLContext
        val eglSurface: EGLSurface
        var program = 0
        // three raw and shading textures in turn, so a frame's upload doesn't wait for the GPU to finish drawing the one
        // before from the same texture
        val rawTexs = IntArray(3)
        val shadeTexs = IntArray(3)
        val shadeWs = IntArray(3); val shadeHs = IntArray(3)
        var slot = 0
        /** Time spent in the raw upload (glTexSubImage2D, a CPU copy), for the log. */
        var tUpload = 0L
        var curveTex = 0
        // the CFA: colour (0 R, 1 G, 2 B) and shading-map channel (0 R, 1 G even, 2 G odd, 3 B) at each 2×2 position
        val colorAt = IntArray(4); val shadeAt = IntArray(4)

        init {
            val v = IntArray(2); EGL14.eglInitialize(display, v, 0, v, 1)
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 10, EGL14.EGL_GREEN_SIZE, 10, EGL14.EGL_BLUE_SIZE, 10, EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, num, 0) && num[0] > 0) { "no 10-bit EGL config" }
            context = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, cfgs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
        }

        /** [tf]: the output curve ([OpenDrt.Input.oetf]); S-Log3 (8) is computed in the shader, any other from a table. */
        fun setup(cfa: String, c: JSONObject, camToOut: FloatArray, headroom: Float, draft: Boolean, tf: Int = 8) {
            val pattern = when (cfa.lowercase()) { "bggr" -> "BGGR"; "grbg" -> "GRBG"; "gbrg" -> "GBRG"; else -> "RGGB" }
            var greens = 0
            for (p in 0 until 4) when (pattern[p]) {
                'R' -> { colorAt[p] = 0; shadeAt[p] = 0 }
                'B' -> { colorAt[p] = 2; shadeAt[p] = 3 }
                else -> { colorAt[p] = 1; shadeAt[p] = 1 + greens; greens++ }
            }
            program = link(VS, FS)
            GLES30.glUseProgram(program)
            GLES30.glGenTextures(3, rawTexs, 0); GLES30.glGenTextures(3, shadeTexs, 0)
            // only the picture's rows are uploaded (the padding below it is never read: the shader clamps to [size])
            for (t in rawTexs) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, w, ch)
                nearest()
            }
            GLES30.glUniform1i(loc("raw"), 0)
            GLES30.glUniform1i(loc("shade"), 1)
            GLES30.glUniform4i(loc("colorAt"), colorAt[0], colorAt[1], colorAt[2], colorAt[3])
            GLES30.glUniform4i(loc("shadeAt"), shadeAt[0], shadeAt[1], shadeAt[2], shadeAt[3])
            GLES30.glUniform2i(loc("size"), w, ch)
            GLES30.glUniform2i(loc("full"), w, h)
            GLES30.glUniform2f(loc("scale"), w / ow.toFloat(), ch / oh.toFloat())
            GLES30.glUniform1i(loc("draft"), if (draft) 1 else 0)
            GLES30.glUniformMatrix3fv(loc("camToOut"), 1, true, camToOut, 0)
            GLES30.glUniform1f(loc("expo"), 0.18f * Math.pow(2.0, headroom.toDouble()).toFloat())
            // the output curve as a table over log2(x + 2^-14) from -14 to +8 (x up to ~256 × linear), read with texelFetch
            GLES30.glUniform1i(loc("useCurve"), if (tf == 8) 0 else 1)
            GLES30.glUniform1i(loc("curve"), 2)
            if (tf != 8) {
                val t2 = IntArray(1); GLES30.glGenTextures(1, t2, 0); curveTex = t2[0]
                val data = ByteBuffer.allocateDirect(CURVE_N * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (i in 0 until CURVE_N) {
                    val x = Math.pow(2.0, -14.0 + 22.0 * i / (CURVE_N - 1)) - Math.pow(2.0, -14.0)
                    data.put(OpenDrt.encode(x.toFloat(), tf).coerceIn(0f, 1f))
                }
                data.position(0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, CURVE_N, 1, 0, GLES30.GL_RED, GLES30.GL_FLOAT, data)
                nearest()
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            }
            GLES30.glViewport(0, 0, ow, oh)
        }

        private fun nearest() {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        private fun loc(n: String) = GLES30.glGetUniformLocation(program, n)

        fun drawFrame(buf: ByteBuffer, meta: JSONObject, c: JSONObject) {
            slot = (slot + 1) % 3
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTexs[slot])
            buf.position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
            val tu = System.nanoTime()
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, ch, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, buf)
            tUpload += System.nanoTime() - tu

            // levels: the frame's own black and white when it has them
            val black = meta.optJSONArray("dynamicBlackLevel") ?: c.optJSONArray("blackLevel") ?: JSONArray(listOf(0, 0, 0, 0))
            val bl = FloatArray(4) { black.optDouble(it.coerceAtMost(black.length() - 1), 0.0).toFloat() }
            val white = meta.optDouble("dynamicWhiteLevel", c.optDouble("whiteLevel", 1023.0)).toFloat()
            // black levels are given in the CFA's own order (as in the DNG): position p
            GLES30.glUniform4f(loc("black"), bl[0], bl[1], bl[2], bl[3])
            GLES30.glUniform1f(loc("white"), white)
            // white balance per colour from the as-shot neutral (green = 1)
            val ns = meta.optJSONArray("asShotNeutral")
            val neutral = if (ns != null && ns.length() >= 3) FloatArray(3) { ns.getDouble(it).toFloat() } else floatArrayOf(1f, 1f, 1f)
            val gains = FloatArray(3) { neutral[1] / neutral[it].coerceAtLeast(1e-4f) }
            GLES30.glUniform3f(loc("wb"), gains[0], gains[1], gains[2])
            GLES30.glUniform1f(loc("clipAt"), gains.min())

            // lens shading map: four channels R, G even, G odd, B on a coarse grid
            val lsm = meta.optJSONArray("lensShadingMap")
            if (lsm != null && lsm.length() == 4) {
                val count = lsm.getJSONArray(0).length()
                val gw = meta.optInt("lensShadingMapWidth", 0)
                val gh = meta.optInt("lensShadingMapHeight", 0)
                val (sw, sh) = if (gw > 0 && gh > 0 && gw * gh == count) gw to gh else gridOf(count, w / h.toFloat())
                val data = ByteBuffer.allocateDirect(sw * sh * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (i in 0 until sw * sh) for (ch in 0 until 4) data.put(lsm.getJSONArray(ch).optDouble(i, 1.0).toFloat())
                data.position(0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadeTexs[slot])
                if (sw != shadeWs[slot] || sh != shadeHs[slot]) {
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, sw, sh, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, null)
                    nearest(); shadeWs[slot] = sw; shadeHs[slot] = sh
                }
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, sw, sh, GLES30.GL_RGBA, GLES30.GL_FLOAT, data)
                GLES30.glUniform1i(loc("useShade"), 1)
                GLES30.glUniform2i(loc("grid"), sw, sh)
            } else GLES30.glUniform1i(loc("useShade"), 0)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }

        /** Grid size of [count] points whose shape is closest to the picture's. */
        private fun gridOf(count: Int, aspect: Float): Pair<Int, Int> {
            var best = count to 1
            var err = Float.MAX_VALUE
            for (gh in 1..count) if (count % gh == 0) {
                val gw = count / gh
                val e = kotlin.math.abs(gw / gh.toFloat() - aspect)
                if (e < err) { err = e; best = gw to gh }
            }
            return best
        }

        fun present(ptsNs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        private fun link(vs: String, fs: String): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
                val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(s) }
                return s
            }
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(p) }
            return p
        }
    }

    private const val EGL_RECORDABLE_ANDROID = 0x3142

    // one triangle over the whole frame; y flipped so the first row of the RAW is the top of the picture
    private const val VS = """#version 300 es
out vec2 pos;
void main() {
    vec2 v = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    pos = vec2(v.x, 1.0 - v.y);
    gl_Position = vec4(v * 2.0 - 1.0, 0.0, 1.0);
}"""

    private const val CURVE_N = 4096

    private const val FS = """#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
uniform usampler2D raw;
uniform highp sampler2D shade;
uniform ivec4 colorAt;
uniform ivec4 shadeAt;
uniform ivec2 size;   // the picture (rows from the top)
uniform ivec2 full;   // the whole frame: the lens shading map covers it
uniform ivec2 grid;
uniform int useShade;
uniform int draft;
uniform vec2 scale;
uniform vec4 black;
uniform float white;
uniform vec3 wb;
uniform float clipAt;
uniform mat3 camToOut;
uniform float expo;
uniform highp sampler2D curve;
uniform int useCurve;
in vec2 pos;
out vec4 color;

int at(ivec4 v, int i) { return i == 0 ? v.x : i == 1 ? v.y : i == 2 ? v.z : v.w; }
float atf(vec4 v, int i) { return i == 0 ? v.x : i == 1 ? v.y : i == 2 ? v.z : v.w; }

// the lens shading gains of the four map channels at pixel p (bilinear on the grid); 1 without a map
vec4 shading4(ivec2 p) {
    if (useShade == 0) return vec4(1.0);
    vec2 g = (vec2(p) + 0.5) / vec2(full) * vec2(grid - 1);
    ivec2 g0 = clamp(ivec2(floor(g)), ivec2(0), grid - 1);
    ivec2 g1 = min(g0 + 1, grid - 1);
    vec2 f = g - vec2(g0);
    vec4 a = texelFetch(shade, g0, 0), b = texelFetch(shade, ivec2(g1.x, g0.y), 0);
    vec4 c = texelFetch(shade, ivec2(g0.x, g1.y), 0), d = texelFetch(shade, g1, 0);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

int colorOf(ivec2 p) { return at(colorAt, (p.y & 1) * 2 + (p.x & 1)); }

// white-balanced, shading-corrected value at p (0..1 = black..clip before the white balance); the shading map is
// smooth, so the centre's gains [sh] serve the 5×5 around it
float value(ivec2 p, vec4 sh) {
    p = clamp(p, ivec2(0), size - 1);
    int k = (p.y & 1) * 2 + (p.x & 1);
    int col = at(colorAt, k);
    float bl = atf(black, k);
    float v = (float(texelFetch(raw, p, 0).r) - bl) / (white - bl);
    v = clamp(v, 0.0, 1.0) * atf(sh, at(shadeAt, k));
    return v * (col == 0 ? wb.r : col == 1 ? wb.g : wb.b);
}

float slog3(float x) {
    return x >= 0.01125 ? (420.0 + log((x + 0.01) / 0.19) / log(10.0) * 261.5) / 1023.0
                        : (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0;
}

// any other output curve: the table (see Gl.setup), linear between its entries
float fromTable(float x) {
    float t = clamp((log2(x + 0.00006103515625) + 14.0) / 22.0, 0.0, 1.0) * 4095.0;
    int i = int(floor(t));
    float a = texelFetch(curve, ivec2(i, 0), 0).r;
    float b = texelFetch(curve, ivec2(min(i + 1, 4095), 0), 0).r;
    return mix(a, b, t - float(i));
}

vec3 encodeOut(vec3 lin) {
    if (useCurve == 1) return vec3(fromTable(lin.r), fromTable(lin.g), fromTable(lin.b));
    return vec3(slog3(lin.r), slog3(lin.g), slog3(lin.b));
}

void main() {
    ivec2 p = ivec2(pos * vec2(size));
    if (scale != vec2(1.0)) p = ivec2((floor(pos * vec2(size) / scale) + 0.5) * scale);
    p = clamp(p, ivec2(0), size - 1);
    vec4 sh = shading4(p);
    vec3 rgb;
    if (draft == 1) {
        // draft (half size): each 2×2 CFA block is one pixel, no demosaic
        ivec2 q = (p / 2) * 2;
        vec3 s3 = vec3(0.0);
        vec3 n3 = vec3(0.0);
        for (int k = 0; k < 4; k++) {
            ivec2 o = q + ivec2(k & 1, k >> 1);
            int oc = colorOf(o);
            float v = value(o, sh);
            if (oc == 0) { s3.r += v; n3.r += 1.0; } else if (oc == 1) { s3.g += v; n3.g += 1.0; } else { s3.b += v; n3.b += 1.0; }
        }
        rgb = s3 / max(n3, vec3(1.0));
    } else {
        // Malvar–He–Cutler demosaic (gradient-corrected bilinear, 5×5)
        float c  = value(p, sh);
        float h1 = value(p + ivec2(-1, 0), sh) + value(p + ivec2(1, 0), sh);
        float v1 = value(p + ivec2(0, -1), sh) + value(p + ivec2(0, 1), sh);
        float h2 = value(p + ivec2(-2, 0), sh) + value(p + ivec2(2, 0), sh);
        float v2 = value(p + ivec2(0, -2), sh) + value(p + ivec2(0, 2), sh);
        float dg = value(p + ivec2(-1, -1), sh) + value(p + ivec2(1, -1), sh) + value(p + ivec2(-1, 1), sh) + value(p + ivec2(1, 1), sh);
        int cc = colorOf(p);
        if (cc == 1) {
            // green site: the colour beside it and the colour above/below it
            float sideC = (5.0 * c + 4.0 * h1 - h2 + 0.5 * v2 - dg) / 8.0;
            float vertC = (5.0 * c + 4.0 * v1 - v2 + 0.5 * h2 - dg) / 8.0;
            int side = colorOf(p + ivec2(1, 0));
            rgb = side == 0 ? vec3(sideC, c, vertC) : vec3(vertC, c, sideC);
        } else {
            float g = (4.0 * c + 2.0 * (h1 + v1) - (h2 + v2)) / 8.0;
            float other = (6.0 * c + 2.0 * dg - 1.5 * (h2 + v2)) / 8.0;
            rgb = cc == 0 ? vec3(c, g, other) : vec3(other, g, c);
        }
    }
    rgb = max(rgb, vec3(0.0));
    // clipped highlights stay neutral: no channel above the clip of the least-gained colour (with the lens shading
    // gain at this point, which lifts the clip towards the corners)
    rgb = min(rgb, vec3(clipAt * atf(sh, 1)));
    vec3 lin = max(camToOut * rgb, vec3(0.0)) * expo;
    color = vec4(encodeOut(lin), 1.0);
}"""
}
