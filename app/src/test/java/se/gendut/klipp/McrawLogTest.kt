package se.gendut.klipp

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.McrawStore
import se.gendut.klipp.engine.OpenDrt
import java.io.File

/** A MotionCam clip (its develop or its placeholder) starts with an OpenDRT node from the log it is developed into. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class McrawLogTest {
    private val c get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun aPlaceholderKnowsItsLogBeforeTheDevelopIsDone() {
        val ph = File(McrawStore.dir(c), "take_ph_f123.mp4").apply { writeText("") }
        McrawStore.notePlaceholder(c, Uri.fromFile(ph), Uri.parse("content://x/take.mcraw"), "take.mcraw", 800, OpenDrt.Input.LOGC4.key)
        assertEquals(OpenDrt.Input.LOGC4, McrawStore.logOf(c, Uri.fromFile(ph)))
    }

    @Test fun aDevelopWithoutANoteIsTheDefaultLog() {
        val f = File(McrawStore.dir(c), "other_ei800_abc.mp4").apply { writeText("") }
        assertEquals(OpenDrt.Input.SLOG3, McrawStore.logOf(c, Uri.fromFile(f)))
    }

    @Test fun otherClipsGetNothing() {
        assertNull(McrawStore.logOf(c, Uri.fromFile(File(c.filesDir, "projects/a/media/clip.mp4"))))
        assertNull(McrawStore.logOf(c, Uri.parse("content://media/external/video/1")))
        assertNull(McrawStore.logOf(c, null))
    }
}
