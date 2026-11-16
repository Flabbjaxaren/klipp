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
    /**
     * [duration] = longest track (s), [videoStart] = time of the first frame (s), [fps] = the video's frame rate as its
     * frames are timed (see [rateOf]), else as the file states it.
     */
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
                // the frames' own times over the first two seconds: the rate they are actually at
                if (video >= 0) measuredRate(context, uri)?.let { fps = it }
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

    private const val MEASURE_FRAMES = 600

    /**
     * The video's frame times from the platform's extractor – it steps through the sample table without reading the
     * samples (Media3's reads each one: two seconds of 4K APV is 250 MB).
     */
    private fun measuredRate(context: Context, uri: Uri): Double? = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return@runCatching null
            ex.selectTrack(track)
            val times = ArrayList<Long>()
            while (times.size < MEASURE_FRAMES) {
                val t = ex.sampleTime.takeIf { it >= 0 } ?: break
                times += t
                if (t - times.first() > 2_000_000L || !ex.advance()) break
            }
            rateOf(times)
        } finally {
            ex.release()
        }
    }.getOrNull()
    private val STANDARD = doubleArrayOf(23.976, 24.0, 25.0, 29.97, 30.0, 48.0, 50.0, 59.94, 60.0, 90.0, 100.0, 119.88, 120.0, 240.0)

    /**
     * A video's frame rate from its frames' times (in decoding order, any order): frames over the time they span, read as
     * the nearest standard rate within half a percent. The file's own statement can be wrong – a phone's 100 fps file
     * whose frames alternate 8.3 and 11.7 ms apart said (and was read as) 120. Null with too few frames.
     */
    fun rateOf(times: List<Long>): Double? {
        if (times.size < 8) return null
        val s = times.sorted()
        val span = s.last() - s.first()
        if (span <= 0) return null
        val r = (s.size - 1) * 1e6 / span
        return STANDARD.minBy { kotlin.math.abs(it - r) }.takeIf { kotlin.math.abs(it - r) <= it * 0.005 }
            ?: (Math.round(r * 1000) / 1000.0)
    }

    /** A clip's frame rate: measured from its file when probed, else [engine]'s. */
    fun frameRate(uri: Uri?, engine: Double): Double = cached(uri)?.fps?.takeIf { it > 0.0 } ?: engine

    /** For tests. */
    internal fun put(uri: Uri, info: Info) { cache[uri.toString()] = info }
}
