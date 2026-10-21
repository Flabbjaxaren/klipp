package se.gendut.klipp.engine

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.inspector.MediaExtractorCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * File durations and start times according to Media3 – the same file reader that playback and export use.
 *
 * The engine (FilmCraft) reads files with its own demuxers, and the two don't always agree: video and audio
 * tracks of different lengths, edit lists in camera files (e.g. Samsung's APV), frame rate as 29.97 or 30.
 * When the app computed player positions from the engine's values, it produced errors that Media3 rejected (clip
 * end past the file's length, start before zero). So Media3 decides: everything that goes to the player, and the
 * limits on how far a clip can be trimmed, is computed from these values.
 */
object MediaProbe {
    /** [duration] = longest track (s), [videoStart] = time of the first frame (s), [fps] if the file states it. */
    data class Info(val duration: Double, val videoStart: Double?, val fps: Double?)

    private val cache = ConcurrentHashMap<String, Info>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** Already probed (immediate, for building playback on the main thread). */
    fun cached(uri: Uri?): Info? = uri?.let { cache[it.toString()] }

    /** Probe the file (a few milliseconds, not on the main thread). null if it can't be read. */
    fun probe(context: Context, uri: Uri): Info? {
        val key = uri.toString()
        cache[key]?.let { return it }
        if (key in failed) return null
        val info = runCatching {
            val ex = MediaExtractorCompat(androidx.media3.extractor.DefaultExtractorsFactory(), androidx.media3.datasource.DefaultDataSource.Factory(context))
            try {
                ex.setDataSource(uri, 0)
                var dur = 0L
                var video = -1
                var fps: Double? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.containsKey(MediaFormat.KEY_DURATION)) dur = maxOf(dur, f.getLong(MediaFormat.KEY_DURATION))
                    if (video < 0 && f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        video = i
                        fps = WindowTracker.frameRate(f).takeIf { f.containsKey(MediaFormat.KEY_FRAME_RATE) }
                    }
                }
                var start: Double? = null
                if (video >= 0) {
                    ex.selectTrack(video)
                    ex.seekTo(0, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
                    start = ex.sampleTime.takeIf { it >= 0 }?.div(1e6)
                }
                if (dur <= 0) null else Info(dur / 1e6, start, fps)
            } finally {
                ex.release()
            }
        }.getOrNull()
        if (info != null) cache[key] = info else failed += key
        return info
    }

    /**
     * The length that applies to a file: Media3's when available. If the engine's is shorter, that one is used (the
     * engine doesn't allow clips past its own length anyway). [engine] = the engine's length.
     */
    fun length(uri: Uri?, engine: Double?): Double? {
        val m = cached(uri)?.duration
        return when {
            m == null -> engine
            engine == null || engine <= 0.0 -> m
            else -> minOf(m, engine)
        }
    }

    /** For tests. */
    internal fun put(uri: Uri, info: Info) { cache[uri.toString()] = info }
}
