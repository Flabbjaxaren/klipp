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
import androidx.core.app.NotificationCompat
import se.gendut.klipp.R
import se.gendut.klipp.Str

/**
 * Keeps the app in the foreground while a video export runs (with a notification and its progress), so Android does
 * not stop it when the app is left. The export itself runs in the app as before; this only holds the process.
 *
 * If the process dies anyway, [ExportGuard] notices it at the next start: the export is reported as saved only if its
 * file was published whole, otherwise as interrupted with its half-written entry and temporary files removed.
 */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(this, intent?.getStringExtra(EXTRA_NAME).orEmpty(), -1)
        // (the type must be named on Android 14+; media processing exists from Android 15, data sync before it)
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        runCatching { startForeground(ID, n, type) }.onFailure {
            // (not allowed to start in the foreground now: the export still runs, only without the protection)
            se.gendut.klipp.CrashReport.note("export service: ${it.javaClass.simpleName} ${it.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+: the media processing type's time is up (six hours in a day). The service must stop at once or the app
     * is ended with an error; the export goes on without the protection (if the process is lost after all, ExportGuard
     * reports it at the next start).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        se.gendut.klipp.CrashReport.note("export service: time limit reached (type $fgsType) – the export goes on unprotected")
        stopSelf()
    }

    companion object {
        private const val ID = 4711
        private const val CHANNEL = "export"
        private const val EXTRA_NAME = "name"

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

        /** Between [start] and [stop]: a progress that comes after the export ended posts no notification. */
        @Volatile private var active = false

        fun start(c: Context, name: String) {
            active = true
            runCatching { c.startForegroundService(Intent(c, ExportService::class.java).putExtra(EXTRA_NAME, name)) }
                .onFailure { se.gendut.klipp.CrashReport.note("export service not started: ${it.message}") }
        }

        private var lastPercent = -1

        /** The notification's progress (only when it changes by a whole percent). */
        fun progress(c: Context, name: String, percent: Int) {
            // (a late progress after the end would put up an ongoing notification nothing takes down)
            if (!active || percent == lastPercent) return
            lastPercent = percent
            runCatching { c.getSystemService(NotificationManager::class.java).notify(ID, notification(c, name, percent)) }
        }

        fun stop(c: Context) {
            active = false
            lastPercent = -1
            runCatching { c.stopService(Intent(c, ExportService::class.java)) }
            runCatching { c.getSystemService(NotificationManager::class.java).cancel(ID) }
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
    /** What the editor says about an export that ended while it wasn't watching: its name, and whether its file was saved. */
    data class Notice(val name: String, val saved: Boolean)

    /** Set when the last export ended unseen (cut off with the process, or its screen closed); shown once by the editor. */
    var notice: Notice? = null

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

    fun begin(c: Context, name: String) {
        prefs(c).edit().putString("running", name).putLong("since", System.currentTimeMillis()).remove("file").remove("published").commit()
        ExportService.start(c, name)
    }

    /** The export's entry in Movies was made (pending, nothing written yet): recorded first, so a death leaves no trace. */
    fun writing(c: Context, uri: Uri) {
        prefs(c).edit().putString("file", uri.toString()).commit()
    }

    /** The entry was published: the file is whole and in Movies. */
    fun published(c: Context) {
        prefs(c).edit().putBoolean("published", true).commit()
    }

    fun progress(c: Context, name: String, percent: Int) = ExportService.progress(c, name, percent)

    fun end(c: Context) {
        prefs(c).edit().remove("running").remove("since").remove("file").remove("published").commit()
        ExportService.stop(c)
    }

    /**
     * The export was stopped from outside while the process lives (its coroutine cancelled: the screen that ran it went
     * away). Said the next time the editor shows – as saved if its file was published (a copy once begun is finished),
     * otherwise as interrupted. Call before [end].
     */
    fun stopped(c: Context, name: String) {
        val saved = prefs(c).getBoolean("published", false)
        notice = Notice(name, saved)
        se.gendut.klipp.CrashReport.note("export ended unseen while running: $name (${if (saved) "saved" else "no file"})")
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
