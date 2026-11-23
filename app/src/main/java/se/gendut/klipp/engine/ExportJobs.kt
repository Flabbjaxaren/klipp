package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.gendut.klipp.CrashReport

/**
 * The video export as a job of its own, not of the screen that started it: the editor asks for it ([start]), the
 * foreground service runs it in its scope once Android has let it into the foreground ([run]) and stops when it ends.
 * HOME, Back, the lock or the app swiped from Recents only take the screen away; the job goes on while the process and
 * the service live. A screen – the one that started it or a new one – shows [status] and can [cancel] it.
 *
 * One job at a time: [start] refuses a second while one runs (a double tap, a screen come back).
 *
 * The end: saved (a whole file published – also when a cancel came after publishing), failed, cancelled (the user,
 * Android's time limit, the service stopped) or denied (the service couldn't be held in the foreground: the job never
 * ran, nothing was made). Watched by a resumed screen, the end is said there ([status], then [ack]); otherwise it waits
 * as [ExportGuard.notice] for the next screen.
 */
/** The export itself: the context (the application's), the progress (0–100); its file's URI. */
typealias ExportWork = suspend (Context, (Int) -> Unit) -> Result<Uri>

object ExportJobs {
    enum class Phase { STARTING, RUNNING, SAVED, FAILED, CANCELLED, DENIED }

    enum class Reason { USER, TIMEOUT, SERVICE_STOPPED }

    data class Status(
        val id: Long, val name: String, val phase: Phase, val percent: Int = 0,
        /** SAVED: what the file became; FAILED, DENIED: the error. */
        val message: String? = null,
        /** CANCELLED: by whom. */
        val reason: Reason? = null,
        /** SAVED: the file made (for sharing it), when known. */
        val uri: Uri? = null,
    ) {
        val active get() = phase == Phase.STARTING || phase == Phase.RUNNING
    }

    /** The job that runs, or the last one's end until the screen has said it (Compose state; written on the main thread). */
    var status by mutableStateOf<Status?>(null)
        private set

    /** The last job's end (diagnostics). */
    @Volatile var last: Status? = null
        private set

    /** A screen is resumed (set by the activity): an end then is said there, otherwise kept as a notice. */
    @Volatile var watching = false

    private class Pending(val id: Long, val name: String, val work: ExportWork)

    private var pending: Pending? = null
    private var job: Job? = null
    private var reason: Reason? = null
    private var nextId = 1L

    /** Asks for the foreground service; throws if Android refuses (tests put in their own). */
    internal var startService: (Context, Long, String) -> Unit = { c, id, name -> ExportService.start(c, id, name) }

    /**
     * A new export named [name]: the foreground service is asked for and runs [work] once it is in the foreground.
     * False if one is already running or Android refused the service (then [status] is DENIED: nothing was made).
     */
    @MainThread
    fun start(c: Context, name: String, work: ExportWork): Boolean {
        if (status?.active == true || job != null) {
            CrashReport.note("export: \"$name\" not started – one is running")
            return false
        }
        val id = nextId++
        pending = Pending(id, name, work)
        status = Status(id, name, Phase.STARTING)
        CrashReport.note("export job $id asked for: $name")
        try {
            startService(c.applicationContext, id, name)
        } catch (e: Exception) {
            denied(id, e)
            return false
        }
        return true
    }

    /** The service couldn't be held in the foreground: job [id] is not run (no file, no marks). */
    @MainThread
    fun denied(id: Long, e: Throwable) {
        val p = pending?.takeIf { it.id == id } ?: return
        pending = null
        CrashReport.note("export job $id not started: the foreground refused (${e.javaClass.simpleName}: ${e.message})")
        val s = Status(id, p.name, Phase.DENIED, message = e.message)
        last = s
        status = s
    }

    /**
     * From the service, in the foreground: job [id] runs in [scope] (the service's – it lives as long as the service).
     * [onEnd] once it has ended and cleaned up. False if there is no such job waiting.
     */
    @MainThread
    fun run(c: Context, id: Long, scope: CoroutineScope, onEnd: () -> Unit): Boolean {
        val p = pending?.takeIf { it.id == id } ?: return false
        pending = null
        reason = null
        status = Status(id, p.name, Phase.RUNNING)
        val j = scope.launch {
            CrashReport.note("export job $id start: ${p.name}")
            ExportGuard.begin(c, p.name)
            BackgroundWork.export(true)
            var result: Result<Uri>? = null
            try {
                result = p.work(c) { progress(c, id, it) }
            } catch (_: CancellationException) {
                // (the end below: saved if the file was published before the cancel, otherwise cancelled)
            } finally {
                finish(c, p, result, onEnd)
            }
        }
        // (a job that ended at once has already cleared itself)
        job = if (j.isCompleted) null else j
        return true
    }

    /** Stops the running job (or the one waiting for its service). */
    @MainThread
    fun cancel(why: Reason) {
        job?.let { j ->
            if (reason == null) reason = why
            CrashReport.note("export: cancel (${why.name.lowercase()})")
            j.cancel()
            return
        }
        val p = pending ?: return
        pending = null
        val s = Status(p.id, p.name, Phase.CANCELLED, reason = why)
        last = s
        status = if (watching) s else null
    }

    /** The screen has said the end of job [id]. */
    @MainThread
    fun ack(id: Long) {
        if (status?.id == id && status?.active == false) status = null
    }

    @MainThread
    private fun progress(c: Context, id: Long, percent: Int) {
        val s = status ?: return
        // (a late report of an ended job – or of one being stopped – changes nothing and posts no notification)
        if (s.id != id || s.phase != Phase.RUNNING || reason != null || percent == s.percent) return
        status = s.copy(percent = percent)
        ExportService.progress(c, s.name, percent)
    }

    private fun finish(c: Context, p: Pending, result: Result<Uri>?, onEnd: () -> Unit) {
        val published = ExportGuard.isPublished(c)
        val error = result?.exceptionOrNull()
        val end = when {
            result?.isSuccess == true || published -> Status(p.id, p.name, Phase.SAVED, 100, message = Exporter.lastResult, uri = result?.getOrNull())
            result != null && error !is CancellationException -> Status(p.id, p.name, Phase.FAILED, message = error?.message ?: "")
            else -> Status(p.id, p.name, Phase.CANCELLED, reason = reason ?: Reason.SERVICE_STOPPED)
        }
        if (end.phase == Phase.FAILED) {
            val x = error as? androidx.media3.transformer.ExportException
            CrashReport.note("export failed: ${x?.errorCodeName ?: error?.javaClass?.simpleName} ${error?.message} ${error?.cause?.let { "(${it.javaClass.simpleName}: ${it.message})" } ?: ""}")
            // the whole chain of causes with where each was thrown (the first cause alone often says nothing)
            generateSequence(error?.cause) { it.cause }.take(5).forEach { c ->
                CrashReport.note("  cause ${c.javaClass.name}: ${c.message} @ " + c.stackTrace.take(5).joinToString(" < ") { "${it.className}.${it.methodName}:${it.lineNumber}" })
            }
        }
        CrashReport.note("export job ${p.id} ${end.phase.name.lowercase()}${end.reason?.let { " (${it.name.lowercase()})" } ?: ""}${if (watching) "" else " – unseen"}")
        job = null
        reason = null
        last = end
        ExportGuard.end(c)
        BackgroundWork.export(false)
        if (watching) {
            status = end
        } else {
            status = null
            ExportGuard.notice = ExportGuard.Notice(p.name, saved = end.phase == Phase.SAVED, failure = if (end.phase == Phase.FAILED) end.message ?: "" else null)
        }
        onEnd()
    }
}
