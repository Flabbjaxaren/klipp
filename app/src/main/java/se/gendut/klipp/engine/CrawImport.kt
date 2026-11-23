package se.gendut.klipp.engine

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageWriter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodecList
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToLong

/** Develops Cinematic RAW Studio video to project-owned 10-bit log editing media. */
internal object CrawImport {
    init { System.loadLibrary("klipp_craw") }

    private external fun open(fd: Int): Long
    private external fun close(handle: Long)
    private external fun metadata(handle: Long): LongArray
    private external fun configure(handle: Long, xyzToGamut: FloatArray, curve: FloatArray, ei: Int)
    private external fun decode(
        handle: Long, index: Int, width: Int, height: Int,
        y: ByteBuffer, yRow: Int, yPixel: Int,
        u: ByteBuffer, uRow: Int, uPixel: Int,
        v: ByteBuffer, vRow: Int, vPixel: Int,
    ): Long
    private external fun pcm16(handle: Long, start: Long, frames: Int, output: ByteBuffer)

    private class Source(val handle: Long, fields: LongArray) {
        val width = fields[0].toInt() and -2
        val height = fields[1].toInt() and -2
        val frames = fields[2].toInt()
        val rotation = Math.floorMod(fields[3].toInt(), 360).takeIf { it % 90 == 0 } ?: 0
        val fps = fields[4] / 1000.0
        val channels = fields[5].toInt()
        val sampleRate = fields[6].toInt()
        val audioFrames = fields[7]
        val firstTimestampNs = fields[8]
    }

    suspend fun convert(context: Context, uri: Uri, destination: File, settings: CrawSettings) {
        require(Build.VERSION.SDK_INT >= 31) { "CRAW development requires Android 12 or later" }
        val job = currentCoroutineContext()
        val resolver = context.contentResolver
        val scratch = mutableListOf<File>()
        fun temp(suffix: String) = File.createTempFile("craw-", suffix, destination.parentFile).also { scratch += it }

        try {
            val output = temp(".mp4")
            val input = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open CRAW source")
            input.use { fd ->
                // Cloud document providers can return pipes. The decoder needs a seekable descriptor.
                val staged = if (OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode)) null else {
                    temp(".input").also { file ->
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use { stream ->
                            file.outputStream().use { sink ->
                                val bytes = ByteArray(128 * 1024)
                                while (true) {
                                    job.ensureActive()
                                    val count = stream.read(bytes)
                                    if (count < 0) break
                                    sink.write(bytes, 0, count)
                                }
                            }
                        }
                    }
                }
                fun transcode(sourceFd: Int) {
                    val handle = open(sourceFd)
                    try {
                        val source = Source(handle, metadata(handle))
                        require(source.width >= 2 && source.height >= 2 && source.frames > 0) { "Invalid CRAW dimensions or frame count" }
                        configure(handle, settings.xyzToGamut(), settings.logLut(), settings.ei)
                        val hasAudio = source.audioFrames > 0
                        val video = if (hasAudio) temp(".video.mp4") else output
                        writeVideo(source, video, job::ensureActive)
                        if (hasAudio) writeAudioAndCopyVideo(source, video, output, job::ensureActive)
                    } finally {
                        close(handle)
                    }
                }
                if (staged == null) transcode(fd.fd)
                else ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY).use { transcode(it.fd) }
            }
            job.ensureActive()
            if (!output.renameTo(destination)) throw IOException("Could not save developed CRAW")
        } finally {
            scratch.forEach { it.delete() }
        }
    }

    private data class VideoInput(val name: String, val surface: Boolean, val width: Int, val height: Int)
    private class Main10Exception(message: String, cause: Throwable? = null) : IOException(message, cause)

    private fun writeVideo(source: Source, out: File, checkActive: () -> Unit) {
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val direct = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
        val surface = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        val candidates = buildList {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder || info.isAlias || mime !in info.supportedTypes) continue
                val caps = info.getCapabilitiesForType(mime)
                val video = caps.videoCapabilities ?: continue
                if (!info.isHardwareAccelerated && caps.profileLevels.none {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    }) continue

                val xAlignment = maxOf(2, video.widthAlignment)
                val yAlignment = maxOf(2, video.heightAlignment)
                val preferred = minOf(1.0, video.supportedWidths.upper.toDouble() / source.width,
                    video.supportedHeights.upper.toDouble() / source.height)
                val landscape = source.width >= source.height
                val scale4k = minOf((if (landscape) 3840.0 else 2160.0) / source.width,
                    (if (landscape) 2160.0 else 3840.0) / source.height)
                val sizes = listOf(preferred, minOf(preferred, scale4k), preferred * .90, preferred * .75, preferred * .5)
                    .map { scale ->
                        (source.width * scale).toInt() / xAlignment * xAlignment to
                            (source.height * scale).toInt() / yAlignment * yAlignment
                    }.distinct().filter { (w, h) -> w > 0 && h > 0 && video.areSizeAndRateSupported(w, h, source.fps) }
                for ((w, h) in sizes) {
                    if (direct in caps.colorFormats) add(VideoInput(info.name, false, w, h))
                    if (Build.VERSION.SDK_INT >= 33 && surface in caps.colorFormats) add(VideoInput(info.name, true, w, h))
                    // Some hardware encoders accept P010 without advertising it.
                    if (direct !in caps.colorFormats) add(VideoInput(info.name, false, w, h))
                }
            }
        }.sortedByDescending { it.width.toLong() * it.height }
        val failure = Main10Exception("No HEVC Main10 input worked at ${source.width}×${source.height}")
        for (candidate in candidates) {
            checkActive()
            try {
                writeVideoAttempt(source, out, candidate, checkActive)
                return
            } catch (e: Main10Exception) {
                failure.addSuppressed(e)
            } catch (e: MediaCodec.CodecException) {
                failure.addSuppressed(e)
            }
        }
        throw failure
    }

    private fun writeVideoAttempt(source: Source, out: File, input: VideoInput, checkActive: () -> Unit) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, input.width, input.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, if (input.surface) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            // The matrix coefficients describe YUV packing. OpenDRT interprets the RGB log gamut.
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setFloat(MediaFormat.KEY_FRAME_RATE, source.fps.toFloat())
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BIT_RATE, (input.width.toDouble() * input.height * source.fps * .35).coerceIn(1_000_000.0, 100_000_000.0).toInt())
        }
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            muxer.setOrientationHint(source.rotation)
            var track = -1
            var frame = 0
            var lastPtsUs = -1L
            encode(format, checkActive, { outputFormat ->
                val profile = outputFormat.getInteger(MediaFormat.KEY_PROFILE, 0)
                if (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) {
                    throw Main10Exception("The encoder returned 8-bit HEVC")
                }
                track = muxer.addTrack(outputFormat)
                muxer.start()
            }, { data, info ->
                muxer.writeSampleData(track, data, info)
            }, input) { codec, index, writer ->
                if (frame == source.frames) {
                    val end = lastPtsUs + (1_000_000 / source.fps).roundToLong()
                    if (writer == null) codec.queueInputBuffer(index, 0, 0, end, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else codec.signalEndOfInputStream()
                    true
                } else {
                    // C2 invalidates the input Image if getInputBuffer is called after getInputImage.
                    val capacity = if (writer == null) codec.getInputBuffer(index)?.capacity()
                        ?: throw Main10Exception("No direct encoder input buffer") else 0
                    val image = if (writer == null) codec.getInputImage(index)
                        ?: throw Main10Exception("No direct P010 input image") else writer.dequeueInputImage()
                    var submittedToWriter = false
                    try {
                        if (image.format != ImageFormat.YCBCR_P010 || image.planes.size != 3) {
                            throw Main10Exception("The video encoder does not expose P010 planes")
                        }
                        val (y, u, v) = image.planes
                        val ns = decode(
                            source.handle, frame, input.width, input.height,
                            y.buffer.slice(), y.rowStride, y.pixelStride,
                            u.buffer.slice(), u.rowStride, u.pixelStride,
                            v.buffer.slice(), v.rowStride, v.pixelStride,
                        )
                        val recordedUs = (ns - source.firstTimestampNs) / 1000
                        val pts = when {
                            frame == 0 -> 0
                            recordedUs > lastPtsUs -> recordedUs
                            else -> lastPtsUs + (1_000_000 / source.fps).roundToLong()
                        }
                        if (writer == null) codec.queueInputBuffer(index, 0, capacity, pts, 0)
                        else {
                            image.timestamp = pts * 1000
                            writer.queueInputImage(image)
                            submittedToWriter = true
                        }
                        lastPtsUs = pts
                        frame++
                        false
                    } finally {
                        if (!submittedToWriter) image.close()
                    }
                }
            }
            // An explicit end timestamp also gives a one-frame clip its intended duration.
            val end = lastPtsUs + (1_000_000 / source.fps).roundToLong()
            muxer.writeSampleData(track, ByteBuffer.allocateDirect(0), MediaCodec.BufferInfo().apply {
                set(0, 0, end, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            })
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    private fun writeAudioAndCopyVideo(source: Source, video: File, out: File, checkActive: () -> Unit) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, source.sampleRate, source.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, minOf(source.channels * 96_000, 320_000))
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.path)
            extractor.selectTrack(0)
            val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                muxer.setOrientationHint(source.rotation)
                val videoFormat = extractor.getTrackFormat(0)
                val videoTrack = muxer.addTrack(videoFormat)
                var audioTrack = -1
                var frame = 0L
                val audioEnd = source.audioFrames * 1_000_000 / source.sampleRate
                encode(format, checkActive, { audioFormat ->
                    audioTrack = muxer.addTrack(audioFormat)
                    muxer.start()
                }, { data, info ->
                    if (info.presentationTimeUs < audioEnd) muxer.writeSampleData(audioTrack, data, info)
                }) { codec, index, _ ->
                    val pts = frame * 1_000_000 / source.sampleRate
                    val pcm = codec.getInputBuffer(index) ?: error("No audio encoder input buffer")
                    val stride = source.channels * 2
                    val frames = minOf((pcm.capacity() / stride).toLong(), source.audioFrames - frame).toInt()
                    check(frames > 0 || frame == source.audioFrames) { "Audio encoder buffer is too small" }
                    if (frames > 0) pcm16(source.handle, frame, frames, pcm)
                    val end = frames == 0
                    codec.queueInputBuffer(index, 0, frames * stride, pts, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    frame += frames
                    end
                }
                var buffer = ByteBuffer.allocateDirect(0)
                val sample = MediaCodec.BufferInfo().apply { set(0, 0, audioEnd, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                muxer.writeSampleData(audioTrack, buffer, sample)
                while (extractor.sampleSize >= 0) {
                    checkActive()
                    val size = extractor.sampleSize.toInt()
                    if (buffer.capacity() < size) buffer = ByteBuffer.allocateDirect(size)
                    buffer.clear()
                    check(extractor.readSampleData(buffer, 0) == size) { "Short developed video sample" }
                    sample.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(videoTrack, buffer, sample)
                    extractor.advance()
                }
                if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    sample.set(0, 0, videoFormat.getLong(MediaFormat.KEY_DURATION), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    muxer.writeSampleData(videoTrack, buffer, sample)
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun encode(
        format: MediaFormat,
        checkActive: () -> Unit,
        onFormat: (MediaFormat) -> Unit,
        onSample: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
        videoInput: VideoInput? = null,
        feed: (MediaCodec, Int, ImageWriter?) -> Boolean,
    ) {
        val name = videoInput?.name ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error("No compatible encoder for ${format.getString(MediaFormat.KEY_MIME)}")
        val codec = try {
            MediaCodec.createByCodecName(name)
        } catch (e: IOException) {
            if (videoInput == null) throw e
            throw Main10Exception("Cannot open $name", e)
        }
        var surface: Surface? = null
        var writer: ImageWriter? = null
        try {
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                if (videoInput?.surface == true && Build.VERSION.SDK_INT >= 33) {
                    val inputSurface = codec.createInputSurface()
                    surface = inputSurface
                    writer = ImageWriter.Builder(inputSurface).setMaxImages(8).setImageFormat(ImageFormat.YCBCR_P010)
                        .setUsage(HardwareBuffer.USAGE_VIDEO_ENCODE or HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                            or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                        .build()
                }
                codec.start()
            } catch (e: RuntimeException) {
                if (videoInput == null) throw e
                throw Main10Exception("Cannot configure $name for P010", e)
            }
            val info = MediaCodec.BufferInfo()
            var doneFeeding = false
            var lastProgress = SystemClock.elapsedRealtime()
            while (true) {
                checkActive()
                if (!doneFeeding) {
                    val input = if (writer == null) codec.dequeueInputBuffer(10_000) else -1
                    if (writer != null || input >= 0) {
                        doneFeeding = feed(codec, input, writer)
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                when (val output = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        onFormat(codec.outputFormat)
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                    in 0..Int.MAX_VALUE -> {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val data = codec.getOutputBuffer(output) ?: error("No encoder output buffer")
                            onSample(data, info)
                        }
                        codec.releaseOutputBuffer(output, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                check(SystemClock.elapsedRealtime() - lastProgress < 30_000) { "CRAW encoder stopped responding" }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { writer?.close() }
            surface?.release()
            codec.release()
        }
    }
}
