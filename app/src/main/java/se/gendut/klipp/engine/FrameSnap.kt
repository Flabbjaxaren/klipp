package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * A video clip's in point on its file's own frames: the in point the timeline has (a split at the playhead, a trim) lies
 * anywhere between two source frames, and a camera file's frames seldom sit on the timeline's slots. With the first
 * frame a few milliseconds after the in point every frame's time fell just after a slot, so by the rule (SourceSampler:
 * a frame shows from the first slot at or after it) each showed one slot late – and since a clip's first frame is shown
 * from its first slot, the second slot had none of its own: after every cut the first picture stood for two slots and
 * the clip's last source frame was never shown, in playback, the pre-render and the export alike.
 *
 * Here the clip's in point is moved to the time of the first frame the clip reads (from the file's sample table, no
 * decoding): its frames then lie on the slots, one per slot from the first to the last. The clip itself changes (a
 * copy of the timeline for playing and rendering), so everything that maps timeline time to source time – the plan and
 * its pieces, the clocks of stabilization and tracked windows, the stills – follows the same line.
 *
 * The times are read in the background ([ensure]; [revision] rises when new ones arrive, the player then rebuilds) or
 * awaited ([await]: renders and the export never run without them).
 */
object FrameSnap {
    @Volatile private var app: Context? = null
    fun init(context: Context) { app = context.applicationContext }

    /** Rises when frame times were read that a timeline asked for ([ensure]). */
    var revision by mutableIntStateOf(0)
        private set

    /** A clip is read this much before its in point (Playback's VIDEO_LEAD_US): the first frame it gets is at or after that. */
    private const val LEAD_US = 1_000L
    private const val NONE = Long.MIN_VALUE

    /** "uri|in point µs" → the first frame's time (µs), [NONE] when it couldn't be read. */
    private val cache = ConcurrentHashMap<String, Long>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    private fun key(uri: Uri, inUs: Long) = "$uri|$inUs"
    private fun us(sec: Double) = Math.round(sec * 1e6)

    /** The clips whose in point is moved: moving pictures read forwards from a file. */
    private fun applies(c: ClipSnap) = c.text == null && c.hold == null && !c.reverse && c.speed > 0.0

    /** [c] with its in point on its first frame in [uri] (the file its picture is read from) – unchanged while that isn't known. */
    fun clip(c: ClipSnap, uri: Uri?): ClipSnap {
        if (uri == null || !applies(c)) return c
        val t = cache[key(uri, us(c.sourceIn))] ?: return c
        if (t == NONE) return c
        val sec = t / 1e6
        return if (sec == c.sourceIn) c else c.copy(sourceIn = sec)
    }

    /** The timeline with every video clip's in point on its first frame ([videoUriOf]: the file a clip's picture is read from). */
    fun snapshot(s: Snapshot, videoUriOf: (Long) -> Uri?): Snapshot {
        val q = s.sequence ?: return s
        var changed = false
        val video = q.video.map { t ->
            var any = false
            val clips = t.clips.map { c -> clip(c, videoUriOf(c.item)).also { if (it !== c) any = true } }
            if (any) { changed = true; t.copy(clips = clips) } else t
        }
        return if (changed) s.copy(sequence = q.copy(video = video)) else s
    }

    private fun wanted(s: Snapshot, videoUriOf: (Long) -> Uri?): List<Pair<Uri, Long>> =
        s.sequence?.video.orEmpty().flatMap { it.clips }.filter(::applies).mapNotNull { c -> videoUriOf(c.item)?.let { it to us(c.sourceIn) } }
            .filter { (u, t) -> !cache.containsKey(key(u, t)) }.distinct()

    /** Read the frame times [s] needs in the background; [revision] rises when they are in. */
    fun ensure(context: Context, s: Snapshot, videoUriOf: (Long) -> Uri?) {
        val todo = wanted(s, videoUriOf).filter { (u, t) -> pending.add(key(u, t)) }
        if (todo.isEmpty()) return
        val c = context.applicationContext
        Thread({
            for ((u, t) in todo) { read(c, u, t); pending.remove(key(u, t)) }
            android.os.Handler(android.os.Looper.getMainLooper()).post { revision++ }
        }, "frame-snap").apply { isDaemon = true }.start()
    }

    /** Read the frame times [s] needs now (renders, the export). */
    suspend fun await(context: Context, s: Snapshot, videoUriOf: (Long) -> Uri?) {
        val todo = wanted(s, videoUriOf)
        if (todo.isEmpty()) return
        withContext(Dispatchers.IO) { for ((u, t) in todo) read(context.applicationContext, u, t) }
    }

    private fun read(context: Context, uri: Uri, inUs: Long) {
        val v = runCatching { firstFrameUs(context, uri, inUs) }.getOrNull()
        cache[key(uri, inUs)] = v ?: NONE
    }

    /**
     * The time (µs) of the first video frame a clip with in point [inUs] reads from [uri]: the earliest frame at or after
     * the in point less the read-ahead. Null when the file has none there or can't be read.
     */
    fun firstFrameUs(context: Context, uri: Uri, inUs: Long): Long? {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            ex.selectTrack(track)
            val from = (inUs - LEAD_US).coerceAtLeast(0L)
            ex.seekTo(from, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            return firstAtOrAfter(generateSequence { ex.sampleTime.takeIf { it >= 0 }?.also { ex.advance() } }, from)
        } finally {
            ex.release()
        }
    }

    /**
     * The earliest of [times] (a file's frame times in decoding order, from a keyframe) at or after [fromUs]. With
     * B-frames a later sample can carry an earlier time: read on a little past the first hit ([REORDER] samples).
     */
    fun firstAtOrAfter(times: Sequence<Long>, fromUs: Long): Long? {
        var best = NONE
        var after = 0
        for (t in times) {
            if (t >= fromUs && (best == NONE || t < best)) best = t
            if (best != NONE && ++after > REORDER) break
        }
        return best.takeIf { it != NONE }
    }

    private const val REORDER = 16
}
