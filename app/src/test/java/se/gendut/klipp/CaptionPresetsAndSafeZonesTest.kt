package se.gendut.klipp

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.CaptionFit
import se.gendut.klipp.engine.CaptionPresets
import se.gendut.klipp.engine.CaptionStyle
import se.gendut.klipp.engine.OverlayRenderer
import se.gendut.klipp.engine.SafeZones

/** The caption styles: presets that can be left again, below the middle, safe zones and fitting to them, the style view. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class CaptionPresetsAndSafeZonesTest {
    @get:Rule val rule = createComposeRule()

    /** A style with a preset's look applied (as the engine's style and the track's tag would give it). */
    private fun styled(look: Map<String, Any>, base: CaptionStyle = CaptionStyle()): CaptionStyle {
        fun c(k: String) = CaptionStyle.parseColor(look[k] as String, 0)
        val word = (look["wordColor"] as String).takeIf { it.isNotEmpty() }?.let { CaptionStyle.parseColor(it, 0) }
        return base.copy(font = look["font"] as String, size = (look["size"] as Number).toFloat(), color = c("color"), background = look["background"] as Boolean,
            backgroundColor = c("backgroundColor"), outline = (look["outline"] as Number).toFloat(), outlineColor = c("outlineColor"),
            lineSpacing = (look["lineSpacing"] as Number).toFloat(), wordColor = word)
    }

    @Test fun presetsAreRecognisedAndAnOwnLookSurvivesTheTrip() {
        assertEquals("the engine's default is Standard", CaptionPresets.Kind.STANDARD, CaptionPresets.of(CaptionStyle()))
        for (k in CaptionPresets.Kind.entries) for (tall in listOf(true, false)) assertEquals(k, CaptionPresets.of(styled(CaptionPresets.look(k, tall))))
        // an own look: no preset; kept as text and read back the same
        val own = CaptionStyle(font = "Lora", size = 70f, color = 0xFFFFE07A.toInt(), background = false, outline = 2f, wordColor = 0xFF3DF58A.toInt())
        assertNull(CaptionPresets.of(own))
        val kept = CaptionPresets.encode(CaptionPresets.lookOf(own))
        assertTrue("no tag separators", ';' !in kept && '=' !in kept)
        assertEquals(own.copy(savedLook = null), styled(CaptionPresets.decode(kept)!!).copy(align = own.align, anchor = own.anchor, margin = own.margin))
        // a preset never changes the placement
        CaptionPresets.Kind.entries.forEach { k -> assertTrue(CaptionPresets.look(k, true).keys.none { it in setOf("anchor", "align", "margin") }) }
    }

    @Test fun klippsSettingsLiveInTheTrackName() {
        val tag = CaptionStyle.klippTag("", 0, 1f, centerY = 0.6f, savedLook = "eyJmb250IjoiTG9yYSJ9", wrapWidth = 0.7f)
        val st = CaptionStyle().withWordsOf("Auto captions$tag")
        assertNull("no marking", st.wordColor)
        assertEquals(0.6f, st.centerY!!, 1e-6f)
        assertEquals(0.7f, st.wrapWidth!!, 1e-6f)
        assertEquals("eyJmb250IjoiTG9yYSJ9", st.savedLook)
        assertEquals("Auto captions", CaptionStyle.shownName("Auto captions$tag"))
        assertEquals("", CaptionStyle.klippTag("", 0, 1f))
    }

    @Test fun belowTheMiddleCentersTheBlockAt60Percent() {
        val st = CaptionStyle(centerY = 0.6f, background = false)
        val r = OverlayRenderer.captionBounds("En rad\noch en till", st, 1080, 1920)!!
        assertEquals(0.6f * 1920, r.centerY(), 0.04f * 1920)
        // bottom: the block's bottom near the margin
        val b = OverlayRenderer.captionBounds("En rad", CaptionStyle(background = false), 1080, 1920)!!
        assertTrue("bottom: ${b.bottom}", b.bottom > 1920 * 0.88f && b.bottom < 1920 * 0.95f)
    }

    @Test fun safeZonesCoverTheButtonsAndFitMovesTheCaptionsClear() {
        val tt = SafeZones.of(SafeZones.Platform.TIKTOK)
        // the clear area plus the covered bands make up the frame; the column is inside the clear area
        assertEquals(5, tt.covered.size)
        assertTrue(tt.clear.contains(tt.covered[4]))
        // Big social at the bottom of a vertical frame: inside TikTok's bottom band – fit moves it up and narrows it
        val st = styled(CaptionPresets.look(CaptionPresets.Kind.SOCIAL, true), CaptionStyle(anchor = "bottom", margin = 0.08f))
        val texts = listOf("Det här är en ganska lång rad som bryts", "Kort")
        val before = CaptionFit.blocks(texts, st, 1080, 1920)
        assertNotNull("runs into a zone", before.firstNotNullOfOrNull { SafeZones.hit(tt, it) })
        val r = CaptionFit.fit(texts, st, 1080, 1920, tt)
        assertNull(r.problem)
        val v = r.values!!
        var fitted = st
        (v["margin"] as? Double)?.let { fitted = fitted.copy(margin = it.toFloat()) }
        (v["centerY"] as? Float)?.let { fitted = fitted.copy(centerY = it) }
        (v["wrapWidth"] as? Float)?.let { fitted = fitted.copy(wrapWidth = it.takeIf { w -> w > 0f }) }
        val after = CaptionFit.blocks(texts, fitted, 1080, 1920)
        assertTrue("clear of every zone: $after", after.all { SafeZones.hit(tt, it) == null })
        // nothing to fit
        assertEquals(CaptionFit.Problem.EMPTY, CaptionFit.fit(emptyList(), st, 1080, 1920, tt).problem)
    }

    @Test fun aBlockTallerThanTheFreeAreaCannotBeFitted() {
        val huge = CaptionStyle(size = 160f, background = false, wrapWidth = 0.35f)
        val r = CaptionFit.fit(listOf((1..40).joinToString(" ") { "ord" }), huge, 1080, 1920, SafeZones.of(SafeZones.Platform.SHORTS))
        assertEquals(CaptionFit.Problem.TOO_TALL, r.problem)
    }

    /**
     * The captions sheet: In the project first, then Write, From speech and Style. The style row in the overview opens
     * the Style tab for the same track; a preset is a look only; Look, Placement and Word marking open one at a time.
     */
    @Test fun theStyleTabFollowsTheTrackAndItsSectionsOpenOneAtATime() {
        val fake = FakeProps().apply { tracks = listOf(CaptionTrackUi(10, "Auto captions", 1, true)) }
        val st = EditorState().apply { props = fake; captions = listOf(UiCaption(1, 0.5f, 2f, "Rad", track = 10)) }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { CaptionsSheet(st) {} } } }
        rule.waitForIdle()
        // the tabs in that order, the overview first
        val order = listOf("existing", "write", "speech", "style").map { rule.onNodeWithTag("capsPart:$it").fetchSemanticsNode().boundsInRoot.left }
        assertEquals(order.sorted(), order)
        rule.onNodeWithTag("caption:row:1").assertExists()
        rule.onNodeWithTag("capStyle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("capPreset:social").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("captionstyle 10 preset=SOCIAL"), fake.log.filter { it.startsWith("captionstyle") })
        // Placement opens; Word marking then closes it
        rule.onNodeWithTag("capSection:place").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("capPlace:under").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(fake.log.contains("captionstyle 10 placement=under"))
        rule.onNodeWithTag("capFitFor:reels").performScrollTo().performClick()
        rule.onNodeWithTag("capFit").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(fake.log.toString(), fake.log.contains("fit 10 reels"))
        rule.onNodeWithTag("capSection:words").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("cap:markWord").assertExists()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag("capPlace:under")).fetchSemanticsNodes().size)
    }

    /** A landscape sequence: no safe zones offered (they are for portrait video). */
    @Test fun noSafeAreaFitInLandscape() {
        val fake = FakeProps().apply { tracks = listOf(CaptionTrackUi(10, "Auto captions", 1, true)); isPortrait = false }
        val st = EditorState().apply { props = fake; captions = listOf(UiCaption(1, 0.5f, 2f, "Rad", track = 10)) }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { CaptionsSheet(st) {} } } }
        rule.waitForIdle()
        rule.onNodeWithTag("capsPart:style").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("capSection:place").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("capPlace:under").assertExists()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag("capFit")).fetchSemanticsNodes().size)
    }

    @Suppress("unused") private fun RectF.str() = "[$left,$top,$right,$bottom]"
}
