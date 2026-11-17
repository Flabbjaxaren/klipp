package se.gendut.klipp

import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.BackgroundWork
import se.gendut.klipp.engine.ExportGuard
import se.gendut.klipp.engine.ExportJobs
import se.gendut.klipp.engine.ExportJobs.Phase
import se.gendut.klipp.engine.ExportService

/** The export job: owned by the service, not the screen; its end follows its file; one at a time; late callbacks ignored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportJobsTest {
    private val c = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val asked = mutableListOf<Long>()
    private lateinit var realStart: (android.content.Context, Long, String) -> Unit
    private val file = Uri.parse("content://media/external/video/media/42")

    @Before fun setUp() {
        Str.init(c)
        realStart = ExportJobs.startService
        ExportJobs.startService = { _, id, _ -> asked += id }
        ExportJobs.watching = true
        ExportGuard.notice = null
    }

    @After fun tearDown() {
        ExportJobs.cancel(ExportJobs.Reason.USER)
        ExportJobs.status?.let { ExportJobs.ack(it.id) }
        ExportJobs.startService = realStart
        ExportJobs.watching = false
        ExportGuard.notice = null
        ExportGuard.end(c)
    }

    private fun startAndRun(name: String, work: se.gendut.klipp.engine.ExportWork, onEnd: () -> Unit = {}): Long {
        assertTrue(ExportJobs.start(c, name, work))
        val id = asked.last()
        assertTrue(ExportJobs.run(c, id, scope, onEnd))
        return id
    }

    @Test fun aJobRunsToSavedAndCleansUp() {
        var ended = false
        val gate = CompletableDeferred<Unit>()
        val id = startAndRun("Klar", { _, p -> p(30); gate.await(); Result.success(file) }) { ended = true }
        assertEquals(Phase.RUNNING, ExportJobs.status?.phase)
        assertEquals(30, ExportJobs.status?.percent)
        assertTrue("background work held while it runs", BackgroundWork.exporting)
        assertEquals("marked while it runs", ExportGuard.Notice("Klar", saved = false), peekMark())
        gate.complete(Unit)
        assertEquals(Phase.SAVED, ExportJobs.status?.phase)
        assertTrue(ended)
        assertFalse(BackgroundWork.exporting)
        assertNull("the mark gone", peekMark())
        ExportJobs.ack(id)
        assertNull(ExportJobs.status)
    }

    /** The prefs mark as the next start would read it – without clearing it for the job (read, then put back). */
    private fun peekMark(): ExportGuard.Notice? {
        val p = c.getSharedPreferences("export_guard", 0)
        val name = p.getString("running", null) ?: return null
        return ExportGuard.Notice(name, saved = p.getBoolean("published", false))
    }

    @Test fun theUsersCancelReachesTheJob() {
        var cancelled = false
        startAndRun("Avbryt", { _, _ -> try { awaitCancellation() } finally { cancelled = true } })
        ExportJobs.cancel(ExportJobs.Reason.USER)
        assertTrue("the work itself was cancelled", cancelled)
        assertEquals(Phase.CANCELLED, ExportJobs.status?.phase)
        assertEquals(ExportJobs.Reason.USER, ExportJobs.status?.reason)
        assertFalse(BackgroundWork.exporting)
        assertNull(peekMark())
    }

    @Test fun aCancelBeforePublishingIsCancelled() {
        startAndRun("Halv", { ctx, _ -> ExportGuard.writing(ctx, file); awaitCancellation() })
        ExportJobs.cancel(ExportJobs.Reason.USER)
        assertEquals(Phase.CANCELLED, ExportJobs.status?.phase)
    }

    @Test fun aCancelAfterPublishingKeepsItSaved() {
        startAndRun("Hel", { ctx, _ -> ExportGuard.writing(ctx, file); ExportGuard.published(ctx); awaitCancellation() })
        ExportJobs.cancel(ExportJobs.Reason.USER)
        assertEquals("a published file is saved, whatever came after", Phase.SAVED, ExportJobs.status?.phase)
    }

    @Test fun aRefusedForegroundStartsNothing() {
        ExportJobs.startService = { _, _, _ -> throw IllegalStateException("refused") }
        var ran = false
        assertFalse(ExportJobs.start(c, "Nekad") { _, _ -> ran = true; Result.success(file) })
        assertEquals(Phase.DENIED, ExportJobs.status?.phase)
        assertFalse(ran)
        assertFalse(BackgroundWork.exporting)
        assertNull("no mark", peekMark())
        // the service that couldn't get into the foreground: nothing to run either
        assertFalse(ExportJobs.run(c, ExportJobs.status!!.id, scope) {})
    }

    @Test fun aRefusalInTheServiceStartsNothing() {
        var ran = false
        assertTrue(ExportJobs.start(c, "Nekad i tjänsten") { _, _ -> ran = true; Result.success(file) })
        val id = asked.last()
        ExportJobs.denied(id, SecurityException("not allowed"))
        assertEquals(Phase.DENIED, ExportJobs.status?.phase)
        assertFalse(ExportJobs.run(c, id, scope) {})
        assertFalse(ran)
        assertNull(peekMark())
    }

    @Test fun oneJobAtATime() {
        startAndRun("Första", { _, _ -> awaitCancellation() })
        val first = ExportJobs.status!!.id
        assertFalse("a double tap or a returning screen starts no second", ExportJobs.start(c, "Andra") { _, _ -> Result.success(file) })
        assertEquals(first, ExportJobs.status?.id)
        assertEquals(1, asked.size)
    }

    @Test fun aLateProgressOfAnEndedJobChangesNothing() {
        val nm = c.getSystemService(android.app.NotificationManager::class.java)
        var oldProgress: ((Int) -> Unit)? = null
        val gate = CompletableDeferred<Unit>()
        val a = startAndRun("A", { _, p -> oldProgress = p; gate.await(); Result.success(file) })
        gate.complete(Unit)
        ExportJobs.ack(a)
        val notesAfterA = Shadows.shadowOf(nm).allNotifications.size
        oldProgress!!(77)
        assertEquals("no notification put back by the ended job", notesAfterA, Shadows.shadowOf(nm).allNotifications.size)
        startAndRun("B", { _, p -> p(10); awaitCancellation() })
        oldProgress!!(99)
        assertEquals("B's progress is B's", 10, ExportJobs.status?.percent)
        assertEquals("B", ExportJobs.status?.name)
    }

    @Test fun anEndNobodyWatchedWaitsAsANotice() {
        ExportJobs.watching = false
        val gate = CompletableDeferred<Unit>()
        startAndRun("I bakgrunden", { _, _ -> gate.await(); Result.success(file) })
        gate.complete(Unit)
        assertNull("nothing for a screen that isn't there", ExportJobs.status)
        assertEquals(ExportGuard.Notice("I bakgrunden", saved = true), ExportGuard.notice)
        assertEquals(Phase.SAVED, ExportJobs.last?.phase)
    }

    @Test fun aFailureNobodyWatchedSaysTheError() {
        ExportJobs.watching = false
        startAndRun("Fel", { _, _ -> Result.failure(IllegalStateException("kodaren sa nej")) })
        assertEquals(ExportGuard.Notice("Fel", saved = false, failure = "kodaren sa nej"), ExportGuard.notice)
    }

    @Test fun theServiceOwnsTheJobAndItsTimeLimitStopsIt() {
        ExportJobs.startService = realStart
        var cancelled = false
        assertTrue(ExportJobs.start(c, "Tidsgräns") { _, _ -> try { awaitCancellation() } finally { cancelled = true } })
        val intent = Shadows.shadowOf(c).nextStartedService
        assertEquals(ExportService::class.java.name, intent?.component?.className)
        val controller = Robolectric.buildService(ExportService::class.java, intent).create()
        val service = controller.get()
        service.onStartCommand(intent, 0, 1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("run by the service", Phase.RUNNING, ExportJobs.status?.phase)
        assertTrue(BackgroundWork.exporting)
        service.onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the job itself stopped", cancelled)
        assertEquals(Phase.CANCELLED, ExportJobs.status?.phase)
        assertEquals(ExportJobs.Reason.TIMEOUT, ExportJobs.status?.reason)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
        assertFalse(BackgroundWork.exporting)
        assertNull(peekMark())
    }

    @Test fun theServiceStoppedFromOutsideStopsTheJob() {
        ExportJobs.startService = realStart
        assertTrue(ExportJobs.start(c, "Stoppad") { _, _ -> awaitCancellation() })
        val intent = Shadows.shadowOf(c).nextStartedService
        val controller = Robolectric.buildService(ExportService::class.java, intent).create()
        controller.get().onStartCommand(intent, 0, 1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        controller.destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Phase.CANCELLED, ExportJobs.status?.phase)
        assertEquals(ExportJobs.Reason.SERVICE_STOPPED, ExportJobs.status?.reason)
    }

    @Test fun theCopyStopsAtACancel() {
        val data = ByteArray(1_000_000) { it.toByte() }
        val out = java.io.ByteArrayOutputStream()
        var reads = 0
        val src = object : java.io.InputStream() {
            val inner = data.inputStream()
            override fun read() = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return inner.read(b, off, len) }
        }
        try {
            se.gendut.klipp.engine.Exporter.copyFile(src, out, data.size.toLong()) { reads >= 3 }
            throw AssertionError("not stopped")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertTrue(out.size() < data.size)
    }

    @Test fun theAfterPublishingTestPointHoldsOnlyThere() {
        val latch = se.gendut.klipp.engine.ExportLatch
        latch.enabled = true
        try {
            latch.arm(30, "published")
            latch.atCopy(10)   // not this one
            assertEquals("armed", latch.state)
            val t = Thread { latch.atPublished() }.apply { start() }
            var waited = 0
            while (latch.state != "held published" && waited < 5000) { Thread.sleep(10); waited += 10 }
            assertEquals("held published", latch.state)
            latch.release()
            t.join(5000)
            assertEquals("released", latch.state)
        } finally {
            latch.enabled = false
        }
    }

    @Test fun theEncoderFailureTestPointFiresOnceAndOnlyInATestBuild() {
        val tp = se.gendut.klipp.engine.ExportTestPoints
        val latch = se.gendut.klipp.engine.ExportLatch
        assertFalse("off unless the build has TEST_HOOKS", latch.enabled)
        try { tp.failNextEncode(); throw AssertionError("allowed outside the test build") } catch (_: IllegalStateException) {}
        latch.enabled = true
        try {
            tp.failNextEncode()
            assertFalse("not before the encoding is under way", tp.takeFailure(2))
            assertTrue(tp.takeFailure(6))
            assertFalse("once", tp.takeFailure(7))
        } finally {
            latch.enabled = false
        }
    }
}
