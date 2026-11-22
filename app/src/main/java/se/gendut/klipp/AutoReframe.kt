package se.gendut.klipp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import se.gendut.klipp.engine.ProjectController
import se.gendut.klipp.engine.Reframe
import se.gendut.klipp.engine.Window
import se.gendut.klipp.engine.WindowTracker
import kotlinx.coroutines.launch

/**
 * Auto reframe of one clip (see [Reframe]): the face nearest the middle is followed through the clip (face following),
 * then the clip is zoomed to fill the frame and panned so the face stays in the middle.
 */
object AutoReframe {
    var target by mutableStateOf<Long?>(null)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    private val scope = kotlinx.coroutines.MainScope()
    private var job: kotlinx.coroutines.Job? = null

    fun start(ctx: Context, controller: ProjectController, clipId: Long) {
        val (c, _, _) = controller.find(clipId) ?: return
        val uri = controller.uriOf(c.item) ?: run { message = Str.t(R.string.masktrack_file_missing); return }
        cancel()
        target = clipId
        progress = 0f
        message = null
        job = scope.launch {
            try {
                val speed = kotlin.math.abs(c.speed).coerceAtLeast(0.01)
                val lo = c.sourceIn; val hi = c.sourceIn + c.duration * speed
                val aspect = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { StabService.aspectOf(ctx, uri) }
                    ?: error(Str.t(R.string.reframe_no_picture))
                val centers = java.util.concurrent.ConcurrentLinkedQueue<Triple<Double, Float, Float>>()
                // the window covers the whole picture: the face nearest its middle is the one followed
                WindowTracker.track(ctx, uri, lo, lo, hi, Window(shape = 2, cx = 0.5f, cy = 0.5f, rx = 0.5f, ry = 0.5f),
                    { p -> scope.launch { progress = p } }, onSample = { s ->
                        val pts = s.pts
                        if (s.q > 0f && pts != null && pts.size >= 10) {
                            var x = 0f; var y = 0f
                            for (i in 0 until 5) { x += pts[2 * i]; y += pts[2 * i + 1] }
                            centers += Triple(s.t, x / 5, y / 5)
                        }
                    }, forwardOnly = true, face = true)
                val q = controller.snapshot.sequence ?: return@launch
                val plan = Reframe.plan(centers.toList(), aspect, q.width, q.height, { t -> c.start + (t - c.sourceIn) / speed })
                    ?: error(Str.t(R.string.reframe_no_face))
                controller.applyReframe(clipId, plan)
                message = Str.t(R.string.reframe_done, plan.keys.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = Str.t(R.string.masktrack_canceled)
                throw e
            } catch (e: Throwable) {
                message = Str.t(R.string.reframe_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                progress = null
                target = null
            }
        }
    }

    fun cancel() { job?.cancel(); job = null }
}
