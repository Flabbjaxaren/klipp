package se.gendut.klipp

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Crash reports: if Klipp crashes, the cause is saved (Java/Kotlin exceptions directly, native crashes and
 * "app not responding" via Android's exit history) and shown on the next launch – so it can be copied and
 * sent, instead of the user only seeing that the app closed.
 */
object CrashReport {
    private fun file(c: Context) = File(c.filesDir, "senaste_krasch.txt")
    private fun trailFile(c: Context) = File(c.filesDir, "spar.txt")
    private fun prevTrailFile(c: Context) = File(c.filesDir, "spar_forra.txt")
    @Volatile private var app: Context? = null
    private val trail = ArrayDeque<String>()
    /** install() and takePending() run once per process (the activity can be created several times). */
    private var installed = false
    private var pendingTaken = false

    /**
     * A trail of what happened (last 40 events), written straight to file – so a report about a crash
     * without a stack trace (Android saves nothing for ordinary crashes) still shows what the app was doing.
     */
    fun note(msg: String) {
        val c = app ?: return
        val line = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date()) + "  " + msg
        synchronized(trail) {
            trail.addLast(line)
            while (trail.size > 40) trail.removeFirst()
        }
        // to file on its own thread (never the main thread); several events in a row become one write
        if (writePending.compareAndSet(false, true)) writer.execute {
            writePending.set(false)
            val text = synchronized(trail) { trail.joinToString("\n") }
            runCatching { trailFile(c).writeText(text) }
            // a copy in the app's external folder, readable over USB (adb) without root – the system log is rate
            // limited for apps on some phones
            runCatching { c.getExternalFilesDir(null)?.let { File(it, "spar.txt").writeText(text) } }
        }
    }
    private val writePending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "trail").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private fun seen(c: Context) = c.getSharedPreferences("klipp", Context.MODE_PRIVATE)

    @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        this.app = app
        // the previous run's trail is set aside (for the report), a new one starts
        runCatching { trailFile(app).takeIf { it.exists() }?.let { f -> f.copyTo(prevTrailFile(app), overwrite = true); f.delete() } }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            app.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(("Klipp " + BuildConfigShim.version).toByteArray())
        }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val spar = synchronized(trail) { trail.joinToString("\n") }
                file(app).writeText(header() + "Thread: ${t.name}\n\n$sw\n\nRecent events:\n$spar")
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun header(version: String = BuildConfigShim.version, at: Long = System.currentTimeMillis()) = "Klipp $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
        "${Build.MANUFACTURER} ${Build.MODEL}" + (if (Build.VERSION.SDK_INT >= 31) " · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "") + "\n" +
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(at)) + "\n"

    /** The report from the previous run (once), or null. */
    @Synchronized fun takePending(context: Context): String? {
        if (pendingTaken) return null
        pendingTaken = true
        file(context).takeIf { it.exists() }?.let { f ->
            val t = runCatching { f.readText() }.getOrNull()
            f.delete()
            if (!t.isNullOrBlank()) return t
        }
        // native crash or freeze: Android's history (API 30+)
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            val isCrash = last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || last.reason == ApplicationExitInfo.REASON_ANR || last.reason == ApplicationExitInfo.REASON_CRASH
            if (!isCrash) return null
            val prefs = seen(context)
            if (prefs.getLong("exitSeen", 0L) >= last.timestamp) return null
            prefs.edit().putLong("exitSeen", last.timestamp).apply()
            val kind = when (last.reason) { ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"; ApplicationExitInfo.REASON_ANR -> "app not responding"; else -> "crash" }
            // main thread first (the one that froze or crashed), then the rest – otherwise the report gets cut off before it
            val raw = runCatching { last.traceInputStream?.use { it.readBytes() } }.getOrNull() ?: ByteArray(0)
            // a native crash comes as a protobuf tombstone: decoded (the crashing thread first), otherwise text (ANR traces)
            val decoded = if (last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) Tombstone.format(raw) else null
            val full = decoded ?: String(raw, Charsets.UTF_8)
            val mainStart = full.indexOf("\n\"main\"")
            val mainBlock = if (mainStart >= 0) full.substring(mainStart, full.indexOf("\n\n", mainStart + 2).let { if (it < 0) full.length else it }) else ""
            val oom = Regex("exception_class: \"([^\"]+)\"").findAll(full).map { it.groupValues[1] }.distinct().joinToString(", ")
            val trace = (if (oom.isNotBlank()) "Earlier crashes in the log: $oom\n" else "") +
                (if (mainBlock.isNotBlank()) "Main thread:$mainBlock\n\n" else "") + full.take(6_000)
            // the version that crashed (saved in the process state summary) and when it happened
            val ver = runCatching { last.processStateSummary?.let { String(it) } }.getOrNull() ?: "unknown version (before 0.15.3)"
            val spar = runCatching { prevTrailFile(context).readText() }.getOrNull().orEmpty()
            header(ver.removePrefix("Klipp "), last.timestamp) + "Type: $kind\n${last.description.orEmpty()}\n\n$trace" +
                (if (spar.isNotBlank()) "\n\nRecent events before the crash:\n$spar" else "\n\n(no trail – the crash happened in a version without a trail)")
        }.getOrNull()
    }
}

/** The version name without BuildConfig (set from MainActivity). */
object BuildConfigShim { var version = "" }

@Composable
fun CrashDialog(text: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crashreport_title)) },
        text = {
            SelectionContainer {
                Text(
                    stringResource(R.string.crashreport_intro) + "\n\n" + text.take(4000),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text(stringResource(R.string.crashreport_copy)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.crashreport_close)) } },
        containerColor = K.SurfaceHigh,
    )
}
