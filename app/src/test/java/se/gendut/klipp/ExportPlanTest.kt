package se.gendut.klipp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.EncoderInfo
import se.gendut.klipp.engine.ExportPlan
import se.gendut.klipp.engine.ExportPlan.Kind
import se.gendut.klipp.engine.ExportSettings
import se.gendut.klipp.engine.Support

/** The export plan: what is made from the chosen settings, what is refused and what is only a warning. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class ExportPlanTest {
    @get:Rule val rule = createComposeRule()

    private val H264 = MimeTypes.VIDEO_H264
    private val HEVC = MimeTypes.VIDEO_H265
    private val AV1 = MimeTypes.VIDEO_AV1

    /** A phone: the largest short side per codec, which codecs make 10-bit, HDR, and what it can't tell. */
    private class Fake(
        val maxShort: Map<String, Int>,
        val ten: Set<String> = emptySet(),
        override val hdr: Support = Support.NO,
        val unknown: Set<String> = emptySet(),
        val maxFps: Double = 240.0,
    ) : EncoderInfo {
        override val codecs: List<String> get() = maxShort.keys.toList()
        override fun size(mime: String, w: Int, h: Int): Support = when {
            mime in unknown -> Support.UNKNOWN
            minOf(w, h) <= (maxShort[mime] ?: 0) -> Support.YES
            else -> Support.NO
        }
        override fun rate(mime: String, w: Int, h: Int, fps: Double) = if (fps <= maxFps) Support.YES else Support.NO
        override fun tenBit(mime: String) = if (mime in ten) Support.YES else Support.NO
    }

    private fun plan(s: ExportSettings, caps: EncoderInfo, anyHdr: Boolean = false, anyTen: Boolean? = false, graded: Boolean = false) =
        ExportPlan.resolve(s, 3840, 2160, 30.0, anyHdr, anyTen, graded, caps)

    @Test fun anOrdinaryExportHasNothingToSay() {
        val p = plan(ExportSettings(height = 1080), Fake(mapOf(H264 to 2160, HEVC to 2160)))
        assertTrue(p.ok)
        assertTrue(p.warnings.isEmpty())
        assertEquals(H264, p.effective.codec)
        assertEquals(1920 to 1080, p.width to p.height)
    }

    @Test fun aSizeOnlyAnotherCodecCanMakeIsAWarningThatSwapsTheCodec() {
        val p = plan(ExportSettings(), Fake(mapOf(H264 to 1080, HEVC to 2160)))
        assertTrue(p.ok)
        assertEquals(HEVC, p.effective.codec)
        val w = p.warnings.single { it.kind == Kind.SIZE_OTHER_CODEC }
        assertEquals(HEVC, w.fix?.codec)
    }

    @Test fun aSizeNoCodecCanMakeIsRefused() {
        val p = plan(ExportSettings(), Fake(mapOf(H264 to 1080, HEVC to 1080)))
        assertFalse(p.ok)
        assertEquals(Kind.SIZE_NONE, p.errors.single().kind)
    }

    @Test fun cantTellIsNeverANo() {
        val p = plan(ExportSettings(), Fake(mapOf(H264 to 0), unknown = setOf(H264)))
        assertTrue(p.ok)
        assertEquals(Kind.SIZE_UNKNOWN, p.warnings.single().kind)
        assertEquals(H264, p.effective.codec)
        // a no for the chosen codec, can't tell for another: that one is tried
        val q = plan(ExportSettings(), Fake(mapOf(H264 to 1080, AV1 to 0), unknown = setOf(AV1)))
        assertTrue(q.ok)
        assertEquals(AV1, q.effective.codec)
    }

    @Test fun upscalingIsOnlyAWarning() {
        val p = ExportPlan.resolve(ExportSettings(height = 2160), 1920, 1080, 30.0, false, false, false, Fake(mapOf(H264 to 2160)))
        assertTrue(p.ok)
        assertEquals(Kind.UPSCALED, p.warnings.single().kind)
    }

    @Test fun tenBitIsMadeInHevcOrAv1OrNotAtAll() {
        val caps = Fake(mapOf(H264 to 2160, HEVC to 2160, AV1 to 2160), ten = setOf(AV1))
        val p = plan(ExportSettings(bitDepth = 10, height = 1080), caps)
        assertTrue(p.tenBit)
        assertEquals(AV1, p.effective.codec)
        // Auto follows the sources (not known yet: 8-bit in the plan)
        assertFalse(plan(ExportSettings(height = 1080), caps, anyTen = null).tenBit)
        assertTrue(plan(ExportSettings(height = 1080), caps, anyTen = true).tenBit)
        // no Main10 anywhere: 8-bit, said so
        val none = plan(ExportSettings(bitDepth = 10, height = 1080), Fake(mapOf(H264 to 2160, HEVC to 2160)))
        assertFalse(none.tenBit)
        assertEquals(H264, none.effective.codec)
        assertEquals(Kind.TEN_BIT_NONE, none.warnings.single().kind)
    }

    @Test fun hdrIsKeptOnlyWhenItCanBe() {
        val s = ExportSettings(hdr = true, height = 1080)
        val caps = Fake(mapOf(H264 to 2160, HEVC to 2160), hdr = Support.YES)
        val p = plan(s, caps, anyHdr = true)
        assertTrue(p.hdr)
        assertEquals(HEVC, p.effective.codec)
        assertFalse("SDR sources", plan(s, caps, anyHdr = false).hdr)
        assertEquals(Kind.HDR_GRADED, plan(s, caps, anyHdr = true, graded = true).warnings.single().kind)
        assertEquals(Kind.HDR_TO_SDR, plan(s, Fake(mapOf(H264 to 2160)), anyHdr = true).warnings.single().kind)
    }

    @Test fun aRateTheEncoderDoesNotListIsAWarning() {
        val p = ExportPlan.resolve(ExportSettings(height = 1080), 1920, 1080, 120.0, false, false, false, Fake(mapOf(H264 to 2160), maxFps = 60.0))
        assertTrue(p.ok)
        assertEquals(Kind.RATE_UNLISTED, p.warnings.single().kind)
    }

    @Test fun theBitDepthHintFollowsTheCodec() {
        assertNull(ExportPlan.tenBitCodec(H264, Fake(mapOf(H264 to 2160))))
        assertEquals(HEVC, ExportPlan.tenBitCodec(H264, Fake(mapOf(H264 to 2160, HEVC to 2160), ten = setOf(HEVC))))
        assertEquals(AV1, ExportPlan.tenBitCodec(AV1, Fake(mapOf(AV1 to 2160, HEVC to 2160), ten = setOf(HEVC, AV1))))
    }

    private fun t(id: Int, vararg a: Any) = RuntimeEnvironment.getApplication().getString(id, *a)

    /**
     * The options show the plan's errors and warnings and offer the way out. (The dialog itself, which only adds the
     * name field and Export enabled = plan.ok, never goes idle under Robolectric – a text field in a dialog window.)
     */
    @Test fun theOptionsShowWhatCantBeMadeAndOfferAWayOut() {
        var caps by androidx.compose.runtime.mutableStateOf<EncoderInfo>(Fake(mapOf(H264 to 1080, HEVC to 1080)))
        var s by androidx.compose.runtime.mutableStateOf(ExportSettings())
        rule.setContent { KlippTheme { ExportOptions(s, 3840, 2160, 30.0, false, null, anyTenBit = false, caps = caps) { s = it } } }
        rule.waitForIdle()
        // 4K: no encoder can – said in red
        rule.onNodeWithTag("exp:issue:SIZE_NONE").assertExists()
        rule.onNodeWithText(t(R.string.exportplan_size_none, 3840, 2160)).assertExists()
        // a phone whose HEVC can: a warning with "Use HEVC"
        caps = Fake(mapOf(H264 to 1080, HEVC to 2160))
        rule.waitForIdle()
        rule.onNodeWithTag("exp:issue:SIZE_NONE").assertDoesNotExist()
        rule.onNodeWithTag("exp:issue:SIZE_OTHER_CODEC").assertExists()
        rule.onNodeWithText(t(R.string.exportplan_fix, "HEVC")).performClick()
        rule.waitForIdle()
        assertEquals(HEVC, s.codec)
        rule.onNodeWithTag("exp:issue:SIZE_OTHER_CODEC").assertDoesNotExist()
    }

    @Test fun changingTheCodecUpdatesTheBitDepthAtOnce() {
        val caps = Fake(mapOf(H264 to 2160, HEVC to 2160), ten = setOf(HEVC))
        var s by androidx.compose.runtime.mutableStateOf(ExportSettings(codec = HEVC, height = 1080))
        rule.setContent { KlippTheme { ExportOptions(s, 1920, 1080, 30.0, false, null, caps = caps) { s = it } } }
        rule.waitForIdle()
        rule.onNodeWithTag("exp:bits").performClick()
        rule.onNodeWithText(t(R.string.exportui_bits_main10)).assertExists()
        rule.onNodeWithTag("exp:bits:b0").performClick()
        // H.264: the hint says HEVC is used (it was remembered for good before)
        s = s.copy(codec = H264)
        rule.waitForIdle()
        rule.onNodeWithTag("exp:bits").performClick()
        rule.onNodeWithText(t(R.string.exportui_bits_h264_hevc)).assertExists()
    }
}
