package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/**
 * A freeze frame as a video, for live playback with one renderer (see [LiveCuts]): the frame's image (FrameHolds)
 * encoded at the preview's size as H.264, 30 frames a second with a keyframe every second, a few fixed lengths long and
 * clipped to the freeze frame. Played as an image the freeze frame went to the image renderer: the video renderer was
 * disabled before it and enabled again after it, and the cuts into and out of it came 100–150 ms late (the edit point
 * layer covered the cut after it, not the one before). As video the one renderer reads on through it.
 *
 * Only playback uses these; the export and the pre-rendering take the image as before. Made in the background, one at a
 * time; [revision] rises when one is ready (playback then rebuilds).
 */
object HoldVideos {
    private const val FPS = 30
    /** The file lengths (s): a freeze frame uses the shortest that covers it and the read-ahead. */
    private val LENGTHS = doubleArrayOf(2.0, 5.0, 10.0, 30.0, 60.0, 180.0, 600.0)

    var revision by mutableIntStateOf(0)
        private set

    /** Freeze frame videos waiting or being made (the render chip counts them as preparation). */
    var busy by mutableIntStateOf(0)
        private set

    @Volatile private var app: Context? = null
    fun init(context: Context) { app = context.applicationContext }

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Job>()
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private var running: Transformer? = null

    private data class Job(val still: File, val length: Double, val short: Int, val out: File)

    fun lengthFor(sec: Double): Double? = LENGTHS.firstOrNull { it >= sec + 0.5 }

    private fun outFor(still: File, length: Double, short: Int): File =
        File(still.parentFile, "${still.nameWithoutExtension}_v${length.toInt()}s_$short.mp4")

    /**
     * The size the video is made at: the preview's short side, never above the image's. The preview at full resolution
     * (short side 0): the image's own size (it crashed the app – a short side of 0 isn't a size).
     */
    fun shortFor(still: File): Int = shortFor(VideoPipeline.previewShort, FrameHolds.shortSide(still))

    internal fun shortFor(preview: Int, image: Int?): Int = when {
        preview <= 0 -> image ?: 1080
        image == null -> preview
        else -> minOf(preview, image)
    }.coerceAtLeast(2)

    /** The video for a freeze frame of [sec] from [still], if it is ready. */
    fun file(still: File, sec: Double): File? {
        val len = lengthFor(sec) ?: return null
        return outFor(still, len, shortFor(still)).takeIf { it.exists() && it.length() > 0 }
    }

    /** Makes the video for a freeze frame of [sec] from [still] in the background if it isn't there. */
    fun ensure(still: File, sec: Double) {
        val len = lengthFor(sec) ?: return
        val short = shortFor(still)
        val out = outFor(still, len, short)
        if (out.exists() && out.length() > 0) return
        val app = app ?: return
        main.post {
            if (out.path in pending || out.path in failed) return@post
            pending += out.path
            busy = pending.size
            queue.addLast(Job(still, len, short, out))
            if (running == null) next(app)
        }
    }

    private fun next(c: Context) {
        val job = queue.removeFirstOrNull() ?: return
        // whatever goes wrong making one, the app goes on (the freeze frame then plays as an image)
        runCatching { start(c, job) }.onFailure {
            se.gendut.klipp.CrashReport.note("freeze frame video: ${it.message}")
            running = null; pending -= job.out.path; busy = pending.size; failed += job.out.path
            next(c)
        }
    }

    private fun start(c: Context, job: Job) {
        val tmp = File(job.out.path + ".tmp")
        tmp.delete()
        // a still: the bitrate only buys the keyframes' quality, the frames between cost nothing
        val bitrate = (16_000_000.0 * (job.short / 1080.0) * (job.short / 1080.0)).toInt().coerceAtLeast(2_000_000)
        val enc = DefaultEncoderFactory.Builder(c)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).setiFrameIntervalSeconds(1f).build())
            .setEnableFallback(true)
            .build()
        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(Uri.fromFile(job.still)).setImageDurationMs(Math.round(job.length * 1000)).build(),
        )
            .setFrameRate(FPS)
            .setEffects(androidx.media3.transformer.Effects(emptyList(), listOf(androidx.media3.effect.Presentation.createForShortSide(job.short))))
            .build()
        val comp = Composition.Builder(EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO)).addItem(item).build()).build()
        fun done(ok: Boolean) {
            running = null
            pending -= job.out.path
            busy = pending.size
            if (ok && tmp.renameTo(job.out)) revision++ else { tmp.delete(); failed += job.out.path }
            next(c)
        }
        val t = Transformer.Builder(c)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(enc)
            .setVideoFrameProcessorFactory(VideoPipeline.processorFactory(false))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = done(true)
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    se.gendut.klipp.CrashReport.note("freeze frame video: ${exportException.errorCodeName} ${exportException.message}")
                    done(false)
                }
            })
            .build()
        running = t
        runCatching { t.start(comp, tmp.absolutePath) }.onFailure { done(false) }
    }

    /** Whether a freeze frame from [uri] (original) at [sec] in source time, [dur] long, plays as video now. */
    fun playable(uri: Uri?, sec: Double, dur: Double): Boolean {
        if (!LiveCuts.oneRenderer || uri == null || Perf.cachedHdr(uri) == true) return false
        val still = FrameHolds.file(uri, sec) ?: return false
        return file(still, dur) != null
    }
}
