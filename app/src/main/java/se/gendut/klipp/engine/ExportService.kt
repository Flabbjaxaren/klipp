package se.gendut.klipp.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import kotlinx.coroutines.cancel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import se.gendut.klipp.R
import se.gendut.klipp.Str

/**
 * The export's owner while it runs: in the foreground (a notification with its progress) it runs the job [ExportJobs]
 * hands it, in its own scope, and stops when the job has ended. The screen that asked for the export may go away (HOME,
 * Back, the lock, swiped from Recents) – the job goes on as long as the process and this service live.
 *
 * Not allowed into the foreground: the job is not run (the screen says so, nothing is made). Android's time limit
 * ([onTimeout]) or the service stopped from outside ([onDestroy]): the job is cancelled, not left to run unprotected.
 * If the process dies anyway, [ExportGuard] notices it at the next start: the export is reported as saved only if its
 * file was published whole, otherwise as interrupted with its half-written entry and temporary files removed.
 */
class ExportService : Service() {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        // (one job per service: a second start while one runs is not a new job)
        if (running) return START_NOT_STICKY
        val n = notification(this, intent?.getStringExtra(EXTRA_NAME).orEmpty(), -1)
        // (the type must be named on Android 14+; media processing exists from Android 15, data sync before it)
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            startForeground(ID, n, type)
        } catch (e: Exception) {
            // not allowed into the foreground now (Android 12+ from the background, the time limit used up …): the job
            // is not started – an export without the protection would be lost the moment the app is left
            se.gendut.klipp.CrashReport.note("export service: ${e.javaClass.simpleName} ${e.message}")
            ExportJobs.denied(id, e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        current = this
        running = ExportJobs.run(applicationContext, id, scope) { done() }
        if (!running) done()
        return START_NOT_STICKY
    }

    private fun done() {
        running = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /**
     * Android 15+: the media processing type's time is up (six hours in a day). The job is stopped, and the service at
     * once (Android ends the app if it doesn't stop within seconds); the job's end follows as cancelled.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        se.gendut.klipp.CrashReport.note("export service: time limit reached (type $fgsType) – the export is stopped")
        ExportJobs.cancel(ExportJobs.Reason.TIMEOUT)
        done()
    }

    override fun onDestroy() {
        if (current === this) current = null
        // stopped from outside while the job runs: the job goes with it (a no-op after the job's own end)
        ExportJobs.cancel(ExportJobs.Reason.SERVICE_STOPPED)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 4711
        private const val CHANNEL = "export"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ID = "id"

        private fun notification(c: Context, name: String, percent: Int): Notification {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, Str.t(R.string.exportservice_channel), NotificationManager.IMPORTANCE_LOW))
            }
            val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                PendingIntent.getActivity(c, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            return NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify_export)
                .setContentTitle(Str.t(R.string.exportservice_title))
                .setContentText(name)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                .setContentIntent(open)
                .build()
        }

        /** The running service (the test build's time limit test point calls its [onTimeout]). */
        @Volatile var current: ExportService? = null
            private set

        /** Asks for the service for job [id]; throws if Android refuses (ForegroundServiceStartNotAllowedException). */
        fun start(c: Context, id: Long, name: String) {
            if (ExportTestPoints.takeRefusal()) {
                val msg = "test point: the foreground start refused"
                throw if (Build.VERSION.SDK_INT >= 31) android.app.ForegroundServiceStartNotAllowedException(msg) else IllegalStateException(msg)
            }
            c.startForegroundService(Intent(c, ExportService::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name))
        }

        /** The notification's progress (only while a job runs – [ExportJobs] decides). */
        fun progress(c: Context, name: String, percent: Int) {
            runCatching { c.getSystemService(NotificationManager::class.java).notify(ID, notification(c, name, percent)) }
        }
    }
}

/**
 * Remembers that a video export is running (in preferences, written before it starts), so that an export lost with the
 * process is reported at the next start instead of passing unnoticed – and what became of its file in Movies, so that
 * what the app says always agrees with what is there: "saved" only for a whole, published file, "no file was saved"
 * only when none is left (a half-written entry is removed).
 */
object ExportGuard {
    /**
     * What the editor says about an export that ended while no screen watched: its name, and whether its file was saved
     * ([failure]: it failed, with this error, rather than being stopped).
     */
    data class Notice(val name: String, val saved: Boolean, val failure: String? = null)

    /** Set when the last export ended unseen (cut off with the process, ended with no screen); shown once by the editor. */
    var notice by androidx.compose.runtime.mutableStateOf<Notice?>(null)

    /** The export's entries in Movies (MediaStore; tests put in their own). */
    interface Entries {
        /** The entry: true pending (being written), false published, null gone. */
        fun pending(c: Context, uri: Uri): Boolean?
        /** This app's own pending entries in Movies/Klipp. */
        fun ownPending(c: Context): List<Uri>
        fun delete(c: Context, uri: Uri)
    }

    internal var entries: Entries = MediaStoreEntries

    private fun prefs(c: Context) = c.getSharedPreferences("export_guard", Context.MODE_PRIVATE)

    private var checked = false

    /** Once per process: [interrupted] into [notice], and this app's half-written entries in Movies removed. */
    fun checkOnce(c: Context) {
        if (checked) return
        checked = true
        interrupted(c)?.let { notice = it }
        Thread({ runCatching { sweep(c) } }, "export-sweep").start()
    }

    /** The job has begun (in the foreground): marked before any work. */
    fun begin(c: Context, name: String) {
        prefs(c).edit().putString("running", name).putLong("since", System.currentTimeMillis()).remove("file").remove("published").commit()
    }

    /** The export's entry in Movies was made (pending, nothing written yet): recorded first, so a death leaves no trace. */
    fun writing(c: Context, uri: Uri) {
        prefs(c).edit().putString("file", uri.toString()).commit()
    }

    /** The entry was published: the file is whole and in Movies. */
    fun published(c: Context) {
        prefs(c).edit().putBoolean("published", true).commit()
    }

    /** Whether this export's file has been published (whole, in Movies). */
    fun isPublished(c: Context) = prefs(c).getBoolean("published", false)

    /** The job has ended (its end decided): the marks go. */
    fun end(c: Context) {
        prefs(c).edit().remove("running").remove("since").remove("file").remove("published").commit()
    }

    /**
     * At start (a new process: nothing can be running): the export that ended with the old process, or null. Saved if
     * its file was published (also when the process died right after publishing, before it was noted); otherwise
     * interrupted, and its pending entry removed. Clears the marks and removes the temporary files an export or a
     * package leaves in the cache.
     */
    fun interrupted(c: Context): Notice? {
        val p = prefs(c)
        val name = p.getString("running", null)
        val file = p.getString("file", null)?.let { Uri.parse(it) }
        var saved = p.getBoolean("published", false)
        if (name != null && !saved && file != null) {
            when (runCatching { entries.pending(c, file) }.getOrNull()) {
                false -> saved = true
                true -> runCatching { entries.delete(c, file) }.onFailure { se.gendut.klipp.CrashReport.note("export: pending entry not removed: ${it.message}") }
                null -> Unit
            }
        }
        p.edit().remove("running").remove("since").remove("file").remove("published").apply()
        c.cacheDir.listFiles { f -> (f.name.startsWith("export_") && f.name.endsWith(".mp4")) || (f.name.startsWith("package_") && f.name.endsWith(".klipp")) }
            ?.forEach { runCatching { it.delete() } }
        return name?.let { Notice(it, saved) }
    }

    /**
     * This app's pending entries in Movies/Klipp, removed: at the start of a process nothing of ours is being written, so
     * any is a half file left by an export cut off (Android would otherwise keep it hidden for a week). Skipped if an
     * export has begun meanwhile.
     */
    fun sweep(c: Context): Int {
        if (prefs(c).getString("running", null) != null) return 0
        val left = entries.ownPending(c)
        left.forEach { runCatching { entries.delete(c, it) } }
        if (left.isNotEmpty()) se.gendut.klipp.CrashReport.note("export: ${left.size} half-written file(s) removed from Movies/Klipp")
        return left.size
    }
}

/** The export's entries through MediaStore (pending ones included: only their owner, this app, sees them). */
internal object MediaStoreEntries : ExportGuard.Entries {
    private const val DIR = "Movies/Klipp/"

    private fun query(c: Context, uri: Uri, where: String?, args: Array<String>?): android.database.Cursor? {
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                if (where != null) {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, where)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                }
            }
            c.contentResolver.query(uri, proj, b, null)
        } else {
            @Suppress("DEPRECATION")
            c.contentResolver.query(MediaStore.setIncludePending(uri), proj, where, args, null)
        }
    }

    override fun pending(c: Context, uri: Uri): Boolean? =
        query(c, uri, null, null)?.use { if (it.moveToFirst()) it.getInt(1) == 1 else null }

    override fun ownPending(c: Context): List<Uri> {
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val where = "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        return query(c, base, where, arrayOf(c.packageName, DIR))?.use { cur ->
            buildList { while (cur.moveToNext()) add(android.content.ContentUris.withAppendedId(base, cur.getLong(0))) }
        }.orEmpty()
    }

    override fun delete(c: Context, uri: Uri) {
        c.contentResolver.delete(uri, null, null)
    }
}
