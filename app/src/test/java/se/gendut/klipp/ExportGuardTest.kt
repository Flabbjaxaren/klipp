package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ExportGuard
import se.gendut.klipp.engine.ExportService
import android.net.Uri
import java.io.File

/** An export lost with the process is reported at the next start, and its leftovers are removed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportGuardTest {
    private val c = RuntimeEnvironment.getApplication()

    @Test fun anExportCutOffIsReportedOnceAndItsFilesGo() {
        Str.init(c)
        ExportGuard.begin(c, "Sommarfilm")
        val tmp = File(c.cacheDir, "export_123.mp4").apply { writeText("x") }
        val pkg = File(c.cacheDir, "package_9.klipp").apply { writeText("x") }
        val other = File(c.cacheDir, "thumbs.bin").apply { writeText("x") }
        // (the process died here) – the next start:
        assertEquals(ExportGuard.Notice("Sommarfilm", saved = false), ExportGuard.interrupted(c))
        assertFalse(tmp.exists())
        assertFalse(pkg.exists())
        assertTrue("other cache files stay", other.exists())
        assertNull("said once", ExportGuard.interrupted(c))
    }

    @Test fun aFinishedExportIsNotReported() {
        Str.init(c)
        ExportGuard.begin(c, "Klar")
        ExportGuard.end(c)
        assertNull(ExportGuard.interrupted(c))
    }

    /** MediaStore as the tests set it: entry → pending (true), published (false); gone when absent. */
    private class FakeEntries(val rows: MutableMap<Uri, Boolean>) : ExportGuard.Entries {
        val deleted = mutableListOf<Uri>()
        override fun pending(c: android.content.Context, uri: Uri) = rows[uri]
        override fun ownPending(c: android.content.Context) = rows.filterValues { it }.keys.toList()
        override fun delete(c: android.content.Context, uri: Uri) { deleted += uri; rows.remove(uri) }
    }

    private fun withEntries(f: FakeEntries, block: () -> Unit) {
        val real = ExportGuard.entries
        ExportGuard.entries = f
        try { block() } finally { ExportGuard.entries = real }
    }

    @Test fun theProcessDyingInTheCopyLeavesNoHalfFile() {
        Str.init(c)
        val half = Uri.parse("content://media/external/video/media/11")
        val f = FakeEntries(mutableMapOf(half to true))
        withEntries(f) {
            ExportGuard.begin(c, "Halv")
            ExportGuard.writing(c, half)
            // (the process died in the copy) – the next start:
            assertEquals(ExportGuard.Notice("Halv", saved = false), ExportGuard.interrupted(c))
            assertEquals(listOf(half), f.deleted)
        }
    }

    @Test fun theProcessDyingRightAfterPublishingIsReportedAsSaved() {
        Str.init(c)
        val whole = Uri.parse("content://media/external/video/media/12")
        // published in MediaStore, the process gone before it could note it
        val f = FakeEntries(mutableMapOf(whole to false))
        withEntries(f) {
            ExportGuard.begin(c, "Sparad")
            ExportGuard.writing(c, whole)
            assertEquals(ExportGuard.Notice("Sparad", saved = true), ExportGuard.interrupted(c))
            assertTrue("a published file is never removed", f.deleted.isEmpty())
        }
    }

    @Test fun thePublishedMarkMeansSavedWithoutAsking() {
        Str.init(c)
        val f = FakeEntries(mutableMapOf())
        withEntries(f) {
            ExportGuard.begin(c, "Klar men avbruten")
            ExportGuard.writing(c, Uri.parse("content://media/external/video/media/13"))
            ExportGuard.published(c)
            assertEquals(ExportGuard.Notice("Klar men avbruten", saved = true), ExportGuard.interrupted(c))
        }
    }

    @Test fun theStartRemovesHalfFilesLeftWithoutAMark() {
        Str.init(c)
        val a = Uri.parse("content://media/external/video/media/21")
        val b = Uri.parse("content://media/external/video/media/22")
        val whole = Uri.parse("content://media/external/video/media/23")
        val f = FakeEntries(mutableMapOf(a to true, b to true, whole to false))
        withEntries(f) {
            ExportGuard.end(c)
            assertEquals(2, ExportGuard.sweep(c))
            assertEquals(setOf(a, b), f.deleted.toSet())
            // an export begun meanwhile: its entry is left alone
            f.rows[a] = true
            ExportGuard.begin(c, "Pågår")
            assertEquals(0, ExportGuard.sweep(c))
            ExportGuard.end(c)
        }
    }

    @Test fun theCopyIsWholeAndTheTestLatchHoldsItHalfway() {
        val data = ByteArray(1_000_000) { (it * 31).toByte() }
        // not armed (Klipp itself): straight through
        val out1 = java.io.ByteArrayOutputStream()
        se.gendut.klipp.engine.Exporter.copyFile(data.inputStream(), out1, data.size.toLong())
        assertTrue(data.contentEquals(out1.toByteArray()))
        // armed: held with half in the file, until released
        val latch = se.gendut.klipp.engine.ExportLatch
        latch.enabled = true
        try {
            latch.arm(30)
            val out2 = java.io.ByteArrayOutputStream()
            val t = Thread { se.gendut.klipp.engine.Exporter.copyFile(data.inputStream(), out2, data.size.toLong()) }.apply { start() }
            var waited = 0
            while (!latch.state.startsWith("held") && waited < 5000) { Thread.sleep(10); waited += 10 }
            val held = latch.state.removePrefix("held ").toLong()
            assertTrue(held >= data.size / 2 && held < data.size)
            Thread.sleep(100)
            assertEquals("nothing more written while held", held, out2.size().toLong())
            latch.release()
            t.join(5000)
            assertEquals("released", latch.state)
            assertTrue(data.contentEquals(out2.toByteArray()))
        } finally {
            latch.enabled = false
        }
    }

}
