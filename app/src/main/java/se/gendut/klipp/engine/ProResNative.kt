package se.gendut.klipp.engine

import java.nio.ByteBuffer

/**
 * libklipp_prores.so (tools/ffmpeg: FFmpeg's ProRes decoder, PCM decoders and MOV demuxer/muxer, LGPL-2.1-or-later,
 * plus Klipp's wrapper). Every call that can fail throws java.io.IOException with the reason; a handle is used from
 * one thread at a time and must be [close]d.
 */
object ProResNative {
    /** Set if the library couldn't be loaded (a build without tools/ffmpeg/build.sh): the import says so instead of crashing. */
    val loadError: Throwable? by lazy { runCatching { System.loadLibrary("klipp_prores") }.exceptionOrNull() }

    val available: Boolean get() = loadError == null

    @JvmStatic external fun version(): String
    /** Opens the file behind [fd] (duplicated; the caller keeps and closes its own). */
    @JvmStatic external fun open(fd: Int): Long
    /** The file as JSON: picture codec, fourcc, size, rate, colour, audio tracks (see tools/ffmpeg/prores_core.h kp_info). */
    @JvmStatic external fun info(h: Long): String
    /** Decoders on [threads] threads, the audio plan (see [ProResPlan.AudioPlan]) and the output file. */
    @JvmStatic external fun prepare(h: Long, out: String, threads: Int, streams: IntArray?, mix: FloatArray?, outChannels: Int, copyStream: Int)
    /**
     * The next picture into the encoder's planes (direct buffers, strides in bytes) at [w]×[ht], [bits] 10 (P010 samples)
     * or 8. Returns its time in µs from the file's start, −1 after the last picture.
     */
    @JvmStatic external fun readFrame(h: Long, y: ByteBuffer, yStride: Int, u: ByteBuffer, uStride: Int, uPixel: Int, v: ByteBuffer, vStride: Int, vPixel: Int, w: Int, ht: Int, bits: Int): Long
    /** Starts the output once the encoder's parameter sets ([csd], Annex B) are known; colours as FFmpeg's numbers. */
    @JvmStatic external fun startMux(h: Long, csd: ByteArray, w: Int, ht: Int, primaries: Int, transfer: Int, matrix: Int, range: Int)
    @JvmStatic external fun writeVideo(h: Long, buf: ByteBuffer, offset: Int, size: Int, ptsUs: Long, key: Boolean)
    /** The rest of the audio and the file's index. */
    @JvmStatic external fun finish(h: Long)
    @JvmStatic external fun close(h: Long)
}
