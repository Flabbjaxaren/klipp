package se.gendut.klipp.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The test build only: holds the next export's copy into Movies halfway, so that a test can stop the export exactly
 * there (the process killed, the screen closed) – a moment otherwise a few hundred milliseconds long.
 *
 * Armed through the agent bridge (tool export_latch, listed only in the test build); in any other build [enabled] is
 * false (the build's TEST_HOOKS) and nothing is ever held.
 */
object ExportLatch {
    /** The test build (its build configuration); unit tests turn it on for themselves. */
    @Volatile var enabled = se.gendut.klipp.BuildConfig.TEST_HOOKS

    /** idle, armed, held <bytes>, released or timed out. */
    @Volatile var state = "idle"
        private set

    @Volatile private var gate: CountDownLatch? = null
    @Volatile private var holdSeconds = 60L
    /** Where it holds: "copy" (halfway through the copy) or "published" (the file just published, before that is noted). */
    @Volatile var at = "copy"
        private set

    /** The next copy is held halfway, until [release] or [seconds] have passed. */
    @Synchronized fun arm(seconds: Long = 60, where: String = "copy") {
        check(enabled) { "only in the test build" }
        require(where == "copy" || where == "published") { "at is copy or published" }
        at = where
        gate = CountDownLatch(1)
        holdSeconds = seconds.coerceIn(1, 600)
        state = "armed"
    }

    @Synchronized fun release() {
        gate?.countDown()
    }

    /** In the copy, with [written] bytes in the file: waits there if armed (once), until released, timed out or [cancelled]. */
    fun atCopy(written: Long, cancelled: () -> Boolean = { false }) {
        if (at == "copy") hold("held $written", cancelled)
    }

    /** The file just published (whole, visible), before the export has noted it: waits there if armed for it. */
    fun atPublished() {
        if (at == "published") hold("held published") { false }
    }

    private fun hold(what: String, cancelled: () -> Boolean) {
        val g = synchronized(this) { gate?.takeIf { state == "armed" } ?: return }
        state = what
        se.gendut.klipp.CrashReport.note("export latch: $what")
        val until = System.nanoTime() + holdSeconds * 1_000_000_000L
        var end = "timed out"
        while (System.nanoTime() < until) {
            if (g.await(100, TimeUnit.MILLISECONDS)) { end = "released"; break }
            if (cancelled()) { end = "cancelled"; break }
        }
        state = end
        synchronized(this) { if (gate === g) gate = null }
    }
}

/**
 * The test build's other test points for the export (the agent bridge's export_latch tool, listed only there): the
 * next foreground start refused, the next encoder failing, Android's time limit now. Each refuses to act outside the test build.
 */
object ExportTestPoints {
    @Volatile private var refuseNext = false

    /** The next export's foreground start is refused, as Android refuses one (ForegroundServiceStartNotAllowedException). */
    fun refuseNextStart() {
        check(ExportLatch.enabled) { "only in the test build" }
        refuseNext = true
    }

    internal fun takeRefusal(): Boolean {
        if (!refuseNext || !ExportLatch.enabled) return false
        refuseNext = false
        return true
    }

    @Volatile private var failNext = false

    /** The next export's encoder fails once it is under way (as Transformer's onError: the job released, a failure). */
    fun failNextEncode() {
        check(ExportLatch.enabled) { "only in the test build" }
        failNext = true
    }

    /** From the export's progress poll: true once (at [percent] ≥ 5) if a failure was asked for. */
    internal fun takeFailure(percent: Int): Boolean {
        if (!failNext || !ExportLatch.enabled || percent < 5) return false
        failNext = false
        return true
    }

    /** Android's time limit reached now: the running export service's onTimeout, as Android calls it (main thread). */
    fun timeout() {
        check(ExportLatch.enabled) { "only in the test build" }
        val s = ExportService.current ?: error("no export service running")
        s.onTimeout(0, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
    }
}
