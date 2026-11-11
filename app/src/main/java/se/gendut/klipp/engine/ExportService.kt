package se.gendut.klipp.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import se.gendut.klipp.R
import se.gendut.klipp.Str
import java.io.File

/**
 * Keeps the app in the foreground while a video export runs (with a notification and its progress), so Android does
 * not stop it when the app is left. The export itself runs in the app as before; this only holds the process.
 *
 * If the process dies anyway, [ExportGuard] notices it at the next start: the export is reported as interrupted, never
 * as saved, and its temporary files are removed.
 */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(this, intent?.getStringExtra(EXTRA_NAME).orEmpty(), -1)
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, ID, n, type) }.onFailure {
            // (not allowed to start in the foreground now: the export still runs, only without the protection)
            se.gendut.klipp.CrashReport.note("export service: ${it.javaClass.simpleName} ${it.message}")
            stopSelf()
        }
        return START_NOT_STICKY
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

        fun start(c: Context, name: String) {
            runCatching { c.startForegroundService(Intent(c, ExportService::class.java).putExtra(EXTRA_NAME, name)) }
                .onFailure { se.gendut.klipp.CrashReport.note("export service not started: ${it.message}") }
        }

        private var lastPercent = -1

        /** The notification's progress (only when it changes by a whole percent). */
        fun progress(c: Context, name: String, percent: Int) {
            if (percent == lastPercent) return
            lastPercent = percent
            runCatching { c.getSystemService(NotificationManager::class.java).notify(ID, notification(c, name, percent)) }
        }

        fun stop(c: Context) {
            lastPercent = -1
            runCatching { c.stopService(Intent(c, ExportService::class.java)) }
            runCatching { c.getSystemService(NotificationManager::class.java).cancel(ID) }
        }
    }
}

/**
 * Remembers that a video export is running (in preferences, written before it starts), so that an export lost with the
 * process is reported at the next start instead of passing unnoticed.
 */
object ExportGuard {
    /** Set at start when the last export was cut off (its name); shown once by the editor. */
    var notice: String? = null

    private fun prefs(c: Context) = c.getSharedPreferences("export_guard", Context.MODE_PRIVATE)

    fun begin(c: Context, name: String) {
        prefs(c).edit().putString("running", name).putLong("since", System.currentTimeMillis()).commit()
        ExportService.start(c, name)
    }

    fun progress(c: Context, name: String, percent: Int) = ExportService.progress(c, name, percent)

    fun end(c: Context) {
        prefs(c).edit().remove("running").remove("since").commit()
        ExportService.stop(c)
    }

    /**
     * At start (a new process: nothing can be running): the name of an export that was cut off, or null. Clears the
     * mark and removes the temporary files an export or a package leaves in the cache.
     */
    fun interrupted(c: Context): String? {
        val name = prefs(c).getString("running", null)
        prefs(c).edit().remove("running").remove("since").apply()
        c.cacheDir.listFiles { f -> (f.name.startsWith("export_") && f.name.endsWith(".mp4")) || (f.name.startsWith("package_") && f.name.endsWith(".klipp")) }
            ?.forEach { runCatching { it.delete() } }
        return name
    }
}
