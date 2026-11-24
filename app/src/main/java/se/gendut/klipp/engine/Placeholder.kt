package se.gendut.klipp.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
/**
 * A stand-in for a MotionCam RAW clip still being developed – a small video of its middle frame, as long as the clip,
 * with silent sound when the clip has sound (so the timeline makes its audio clip too). It goes on the timeline at once,
 * and the develop takes its place when ready ([McrawStore.relink]), as a RAW redevelop does. Made in a fraction of a
 * second: the develop's own size and frame rate (the project keeps both through the relink), one encoded picture
 * written on every frame time.
 */
internal object Placeholder {
    /** In the file name (after the clip's own name, which the timeline shows until the develop replaces it). */
    const val MARK = "_placeholder_"

    fun isPlaceholder(u: Uri?): Boolean = u?.scheme == "file" && File(u.path ?: "").name.contains(MARK)

    /** A placeholder at a quick develop's (half) size. */
    fun isDraft(u: Uri?): Boolean = isPlaceholder(u) && File(u?.path ?: "").name.substringAfter(MARK).startsWith("q")

    /** The RAW clip's name (without its extension) a placeholder was made for. */
    fun clipName(u: Uri): String? = File(u.path ?: return null).name.substringBefore(MARK).takeIf { it.isNotEmpty() }

    /** The stand-in for the .mcraw [src], or null when it can't be made (the import then waits for the develops). */
    fun make(ctx: Context, src: Uri, name: String, draft: Boolean): Uri? = runCatching { make0(ctx, src, name, draft) }
        .onFailure { android.util.Log.e("KlippPlaceholder", "make $name", it) }.getOrNull()

    private fun make0(ctx: Context, src: Uri, name: String, draft: Boolean): Uri? {
        val thumb = Mcraw.thumb(ctx, src) ?: return null
        if (thumb.seconds <= 0 || thumb.width <= 0 || thumb.height <= 0) return null
        val s = Mcraw.openSource(ctx, src) ?: return null
        val (rate, channels) = try { JSONObject(Mcraw.info(s.h)).let { it.optInt("audioRate") to it.optInt("audioChannels") } } finally { s.close() }
        // the develop's own size (as McrawConvert picks it): the project keeps the media's size through the relink, and a
        // 256 px placeholder left its develop drawn at 256 px
        val fps = if (thumb.fps > 0) thumb.fps else 30.0
        val ch = (thumb.height / 16 * 16).coerceAtLeast(16)
        val (w, h) = McrawConvert.pickSize(if (draft) thumb.width / 2 else thumb.width, if (draft) ch / 2 else ch, fps)
            ?.let { it.first to it.second } ?: return null
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)
        val out = File(McrawStore.dir(ctx), "$base$MARK${if (draft) "q" else "f"}${System.nanoTime()}.mp4")
        val durUs = (thumb.seconds * 1e6).toLong()
        val frameUs = if (thumb.fps > 0) (1e6 / thumb.fps).toLong() else 33_333L
        val video = encodeVideo(thumb.bitmap, w, h)
        val audio = if (rate > 0 && channels > 0) encodeSilence(rate, channels.coerceIn(1, 2), durUs) else null
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vt = mux.addTrack(video.first)
            val at = audio?.let { mux.addTrack(it.first) }
            mux.start()
            // the one picture (a key frame) on every frame time of the clip: the file has the clip's frame count and rate
            // (the project keeps the media's frame rate too: a placeholder at 2 frames a second made its develop play at 2)
            // at the cost of a single encoded frame
            val key = video.second.first { it.info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
            val count = ((durUs + frameUs / 2) / frameUs).coerceAtLeast(1)
            val bi = MediaCodec.BufferInfo()
            for (k in 0 until count) {
                bi.set(0, key.info.size, k * frameUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                mux.writeSampleData(vt, key.data.duplicate().apply { position(0); limit(key.info.size) }, bi)
            }
            if (audio != null && at != null) for ((b, i) in audio.second) mux.writeSampleData(at, b, i)
            mux.stop()
        } finally { runCatching { mux.release() } }
        return Uri.fromFile(out)
    }

    private class Sample(val data: ByteBuffer, val info: MediaCodec.BufferInfo) {
        operator fun component1() = data
        operator fun component2() = info
    }

    /** Drains [codec] into samples (copied); true once the end of stream came out. */
    private fun drain(codec: MediaCodec, out: MutableList<Sample>, fmt: Array<MediaFormat?>, timeoutUs: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> fmt[0] = codec.outputFormat
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)!!
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val copy = ByteBuffer.allocateDirect(info.size)
                        buf.position(info.offset); buf.limit(info.offset + info.size); copy.put(buf); copy.flip()
                        val ci = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        out += Sample(copy, ci)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    /** One key frame of [bmp] (stretched over [w]×[h]), HEVC, and its format. */
    private fun encodeVideo(bmp: Bitmap?, w: Int, h: Int): Pair<MediaFormat, List<Sample>> {
        // the picture as I420, sampled from the small preview (no full-size bitmap)
        val sw = bmp?.width ?: 1; val sh = bmp?.height ?: 1
        val px = IntArray(sw * sh).also { a -> if (bmp != null) bmp.getPixels(a, 0, sw, 0, 0, sw, sh) else a.fill(0xFF181818.toInt()) }
        val y = ByteArray(w * h); val u = ByteArray((w / 2) * (h / 2)); val v = ByteArray((w / 2) * (h / 2))
        val colOf = IntArray(w) { (it.toLong() * sw / w).toInt().coerceIn(0, sw - 1) }
        for (j in 0 until h) {
            val row = (j.toLong() * sh / h).toInt().coerceIn(0, sh - 1) * sw
            for (i in 0 until w) {
                val c = px[row + colOf[i]]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                y[j * w + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if (j and 1 == 0 && i and 1 == 0) {
                    val k = (j / 2) * (w / 2) + i / 2
                    u[k] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                    v[k] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                }
            }
        }
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        // as the develop: 10-bit HEVC, BT.2020, SDR video, limited range (the project keeps what the media was imported
        // as; an untagged file came out tagged PQ from the Qualcomm encoder, see McrawConvert)
        val ten = android.os.Build.VERSION.SDK_INT >= 33
        val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, if (ten) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            if (ten) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            // one frame a second as far as the encoder knows: the bit rate is the key frame's size (~25 KB)
            setInteger(MediaFormat.KEY_BIT_RATE, 200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 1)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        val samples = ArrayList<Sample>(); val outFmt = arrayOfNulls<MediaFormat>(1)
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var eos = false; var done = false; var fed = false
            while (!done) {
                if (!eos) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        if (!fed) {
                            val img = codec.getInputImage(ii)!!
                            fun plane(p: android.media.Image.Plane, src: ByteArray, pw: Int, ph: Int) {
                                val bb = p.buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN); val rs = p.rowStride; val ps = p.pixelStride
                                when {
                                    // P010: 16 bits a sample, the 10 bits at the top
                                    ten -> for (row in 0 until ph) for (col in 0 until pw) bb.putShort(row * rs + col * ps, ((src[row * pw + col].toInt() and 255) shl 8).toShort())
                                    ps == 1 -> for (row in 0 until ph) { bb.position(row * rs); bb.put(src, row * pw, pw) }
                                    else -> for (row in 0 until ph) for (col in 0 until pw) bb.put(row * rs + col * ps, src[row * pw + col])
                                }
                            }
                            plane(img.planes[0], y, w, h); plane(img.planes[1], u, w / 2, h / 2); plane(img.planes[2], v, w / 2, h / 2)
                            codec.queueInputBuffer(ii, 0, w * h * 3 / 2 * (if (ten) 2 else 1), 0, 0); fed = true
                        } else { codec.queueInputBuffer(ii, 0, 0, 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    }
                }
                done = drain(codec, samples, outFmt, if (eos) 10_000 else 0)
            }
        } finally { runCatching { codec.stop() }; runCatching { codec.release() } }
        return (outFmt[0] ?: error("no video format")) to samples
    }

    private fun encodeSilence(rate: Int, channels: Int, durUs: Long): Pair<MediaFormat, List<Sample>> {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * channels * 2 * 4)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = ArrayList<Sample>(); val outFmt = arrayOfNulls<MediaFormat>(1)
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val total = durUs * rate / 1_000_000
            var fed = 0L; var eos = false; var done = false
            while (!done) {
                if (!eos) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val frames = minOf(1024L, total - fed, (buf.capacity() / (2 * channels)).toLong()).toInt()
                        if (frames > 0) {
                            buf.clear(); repeat(frames * channels) { buf.putShort(0) }
                            codec.queueInputBuffer(ii, 0, frames * channels * 2, fed * 1_000_000 / rate, 0); fed += frames
                        } else { codec.queueInputBuffer(ii, 0, 0, fed * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    }
                }
                // (no waiting while there is input to give: a 5 ms wait per buffer made a 9 s clip's silence take seconds)
                done = drain(codec, samples, outFmt, if (eos) 10_000 else 0)
            }
        } finally { runCatching { codec.stop() }; runCatching { codec.release() } }
        return (outFmt[0] ?: error("no audio format")) to samples
    }
}
