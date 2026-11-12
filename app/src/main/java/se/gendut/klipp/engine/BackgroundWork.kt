package se.gendut.klipp.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The export goes first (plan PR 8, risk 9: decoders and encoders were shared without a policy). While an export runs,
 * the jobs that only make playback lighter – proxies, node cache, pre-rendering – start nothing new; one already running
 * stops and is done again afterwards, and the preview lets go of its decoders (it is reloaded when the export ends).
 * Main thread only.
 */
object BackgroundWork {
    /** An export is running (Compose state: the preview and the pre-rendering follow it). */
    var exporting by mutableStateOf(false)
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** [l] hears when an export starts (true) and ends (false). */
    fun listen(l: (Boolean) -> Unit) { listeners += l }

    fun export(on: Boolean) {
        if (exporting == on) return
        exporting = on
        se.gendut.klipp.CrashReport.note(if (on) "export: background work held, preview released" else "export: background work resumed")
        for (l in listeners) runCatching { l(on) }
    }
}
