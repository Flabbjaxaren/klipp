package se.gendut.klipp.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Freeze frames (the engine's frame hold): the held frame, from the original file at full resolution, as an image on disk.
 * Media3 cannot stop a video, so playback and export show the image instead for the whole clip.
 * The images are extracted in the background ([ensure]); the export waits for them ([await]).
 */
object FrameHolds {
    /** Increments when an image is ready (playback is then rebuilt). */
    var revision by mutableIntStateOf(0)
        private set

    /** The image's longest side (like 4K – larger would just cost memory). */
    private const val MAX_SIDE = 4096

    private val pending = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "frame-holds").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var dir: File? = null

    private fun dir(c: Context): File = dir ?: File(c.filesDir, "holds").apply { mkdirs() }.also { dir = it }

    private fun key(uri: Uri, sec: Double): String =
        MessageDigest.getInstance("SHA-1").digest("$uri@${Math.round(sec * 1000)}".toByteArray()).joinToString("") { "%02x".format(it) }

    fun init(context: Context) { dir(context.applicationContext) }

    private val sizes = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The image's short side (for the preview's downscaling). */
    fun shortSide(f: File): Int? = sizes.getOrPut(f.path) {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth > 0 && o.outHeight > 0) minOf(o.outWidth, o.outHeight) else 0
    }.takeIf { it > 0 }

    /** The image for [uri] at [sec], if ready (otherwise null). */
    fun file(uri: Uri, sec: Double): File? {
        val d = dir ?: return null
        return File(d, key(uri, sec) + ".png").takeIf { it.exists() && it.length() > 0 }
    }

    /** Extract the missing images, in the background. */
    fun ensure(context: Context, holds: Collection<Pair<Uri, Double>>) {
        val app = context.applicationContext
        val d = dir(app)
        for ((uri, sec) in holds) {
            val k = key(uri, sec)
            if (File(d, "$k.png").exists()) continue
            synchronized(pending) { if (!pending.add(k)) continue }
            worker.execute {
                runCatching { extract(app, uri, sec, File(d, "$k.png")) }
                    .onFailure { se.gendut.klipp.CrashReport.note("freeze frame: ${it.message}") }
                synchronized(pending) { pending.remove(k) }
                main.post { revision++ }
            }
        }
    }

    /** All images (the export): missing ones are extracted now. */
    suspend fun await(context: Context, holds: Collection<Pair<Uri, Double>>) = withContext(Dispatchers.IO) {
        val d = dir(context.applicationContext)
        for ((uri, sec) in holds) {
            val f = File(d, key(uri, sec) + ".png")
            if (!f.exists() || f.length() == 0L) extract(context.applicationContext, uri, sec, f)
        }
    }

    /**
     * The freeze frames in a timeline: (file, source time) – freeze-frame clips and the transitions' padding where a clip's
     * file runs out (first/last frame, see LayerPlanner.handleSegs).
     */
    fun holdsOf(s: Snapshot, uriOf: (Long) -> Uri?): List<Pair<Uri, Double>> {
        val q = s.sequence ?: return emptyList()
        val holds = q.video.flatMap { it.clips }.mapNotNull { c -> c.hold?.let { h -> uriOf(c.item)?.let { it to h } } }
        // same lengths as playback's plan (Playback.buildPlayable)
        val durs = s.media.associate { it.id to it.duration.takeIf { d -> d > 0 } }
        val plan = runCatching { LayerPlanner.plan(s) { MediaProbe.length(uriOf(it), durs[it]) } }.getOrNull()
        val handles = LayerPlanner.stillsOf(plan).mapNotNull { (item, sec) -> uriOf(item)?.let { it to sec } }
        return (holds + handles).distinct()
    }

    /** The frame at [sec] (closest, as the player shows it) → PNG. */
    private fun extract(c: Context, uri: Uri, sec: Double, out: File) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c, uri)
            var bmp = r.getFrameAtTime((sec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: error("no frame at $sec s")
            val side = maxOf(bmp.width, bmp.height)
            if (side > MAX_SIDE) {
                val k = MAX_SIDE.toFloat() / side
                bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.width * k), Math.round(bmp.height * k), true)
            }
            val tmp = File(out.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(out)) error("could not save the freeze frame")
        } finally {
            r.release()
        }
    }
}
