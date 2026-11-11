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
import java.io.File

/** An export lost with the process is reported at the next start, and its leftovers are removed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportGuardTest {
    private val c = RuntimeEnvironment.getApplication()

    @Test fun anExportCutOffIsReportedOnceAndItsFilesGo() {
        Str.init(c)
        ExportGuard.begin(c, "Sommarfilm")
        // the service was asked to hold the app in the foreground
        assertEquals(ExportService::class.java.name, Shadows.shadowOf(c).nextStartedService?.component?.className)
        val tmp = File(c.cacheDir, "export_123.mp4").apply { writeText("x") }
        val pkg = File(c.cacheDir, "package_9.klipp").apply { writeText("x") }
        val other = File(c.cacheDir, "thumbs.bin").apply { writeText("x") }
        // (the process died here) – the next start:
        assertEquals("Sommarfilm", ExportGuard.interrupted(c))
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
}
