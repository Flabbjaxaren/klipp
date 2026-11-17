package se.gendut.klipp.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The test build only: holds the next export's copy into Movies halfway, so that a test can stop the export exactly
 * there (the process killed, the screen closed) – a moment otherwise a few hundred milliseconds long.
 *
 * Armed through the agent bridge (tool export_latch); in any other build [enabled] is false and nothing is ever held.
 */
object ExportLatch {
    /** Set at start in the test build (its package). */
    @Volatile var enabled = false

    /** idle, armed, held <bytes>, released or timed out. */
    @Volatile var state = "idle"
        private set

    @Volatile private var gate: CountDownLatch? = null
    @Volatile private var holdSeconds = 60L

    /** The next copy is held halfway, until [release] or [seconds] have passed. */
    @Synchronized fun arm(seconds: Long = 60) {
        check(enabled) { "only in the test build" }
        gate = CountDownLatch(1)
        holdSeconds = seconds.coerceIn(1, 600)
        state = "armed"
    }

    @Synchronized fun release() {
        gate?.countDown()
    }

    /** In the copy, with [written] bytes in the file: waits there if armed (once). */
    fun atCopy(written: Long) {
        val g = synchronized(this) { gate?.takeIf { state == "armed" } ?: return }
        state = "held $written"
        se.gendut.klipp.CrashReport.note("export latch: copy held at $written bytes")
        val ok = g.await(holdSeconds, TimeUnit.SECONDS)
        state = if (ok) "released" else "timed out"
        synchronized(this) { if (gate === g) gate = null }
    }
}
