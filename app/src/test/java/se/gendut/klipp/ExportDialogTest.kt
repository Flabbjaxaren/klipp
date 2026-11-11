package se.gendut.klipp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.EncoderInfo
import se.gendut.klipp.engine.ExportSettings
import se.gendut.klipp.engine.Support

/** The export dialog: Export can't be pressed for what can't be made. (Default size: text fields in dialogs hang on larger Robolectric screens.) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExportDialogTest {
    @get:Rule val rule = createComposeRule()

    private class Phone(val maxShort: Map<String, Int>) : EncoderInfo {
        override val codecs get() = maxShort.keys.toList()
        override fun size(mime: String, w: Int, h: Int) = if (minOf(w, h) <= (maxShort[mime] ?: 0)) Support.YES else Support.NO
        override fun rate(mime: String, w: Int, h: Int, fps: Double) = Support.YES
        override fun tenBit(mime: String) = Support.NO
        override val hdr = Support.NO
    }

    @Test fun exportCantBeStartedForWhatCantBeMade() {
        var caps by mutableStateOf<EncoderInfo>(Phone(mapOf(MimeTypes.VIDEO_H264 to 1080, MimeTypes.VIDEO_H265 to 1080)))
        var started: ExportSettings? = null
        rule.setContent {
            KlippTheme { ExportDialog(ExportSettings(), "film", 3840, 2160, 30.0, 10.0, false, null, anyTenBit = false, caps = caps, onDismiss = {}) { s, _ -> started = s } }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("exp:go").assertIsNotEnabled().performClick()
        assertNull(started)
        // HEVC can: Export works, and the summary says HEVC (what is made), not the chosen H.264
        caps = Phone(mapOf(MimeTypes.VIDEO_H264 to 1080, MimeTypes.VIDEO_H265 to 2160))
        rule.waitForIdle()
        rule.onNodeWithTag("exp:summary").assertTextContains("HEVC", substring = true)
        rule.onNodeWithTag("exp:go").assertIsEnabled().performClick()
        assertEquals(MimeTypes.VIDEO_H264, started?.codec)
    }
}
