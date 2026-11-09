package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.CaptionStyle
import se.gendut.klipp.engine.CaptionWords
import se.gendut.klipp.engine.SpeechLine

class CaptionWordsTest {
    private val heard = listOf(SpeechLine(1.2, 1.5, "Hej"), SpeechLine(1.6, 2.0, "på"), SpeechLine(2.5, 3.0, "dig."))

    private fun assertSpans(expected: List<Pair<Double, Double>>, got: List<Pair<Double, Double>>) {
        assertEquals(expected.size, got.size)
        expected.zip(got).forEach { (e, g) -> assertEquals(e.first, g.first, 1e-6); assertEquals(e.second, g.second, 1e-6) }
    }

    @Test fun wordsFollowTheTranscriptWithoutGaps() =
        assertSpans(listOf(1.0 to 1.6, 1.6 to 2.5, 2.5 to 3.5), CaptionWords.spans("Hej på dig.", 1.0, 3.5, heard))

    @Test fun withoutTranscriptTheTimeFollowsWordLength() =
        assertSpans(listOf(0.0 to 1.0, 1.0 to 4.0), CaptionWords.spans("a bbb", 0.0, 4.0, emptyList()))

    @Test fun anEditedWordKeepsItsNeighborsTimes() {
        val s = CaptionWords.spans("Hej alla på dig.", 1.0, 3.5, heard)
        assertEquals(4, s.size)
        assertEquals(1.6, s[2].first, 1e-6)
        assertEquals(2.5, s[3].first, 1e-6)
        assertEquals(s[0].second, s[1].first, 1e-6)
    }

    @Test fun theStepFollowsTime() {
        assertEquals(1, CaptionWords.stepAt("Hej på dig.", 1.0, 3.5, heard, 0, 2.0)?.word)
        assertEquals(null, CaptionWords.stepAt("Hej på dig.", 1.0, 3.5, heard, 0, 3.6))
    }

    @Test fun pagesShowAFewWordsAtATime() {
        val s = CaptionWords.steps("Hej på dig.", 1.0, 3.5, heard, 2)
        assertEquals(listOf("Hej på", "Hej på", "dig."), s.map { it.text })
        assertEquals(listOf(0, 1, 0), s.map { it.word })
        assertEquals(1.0, s[0].start, 1e-6)
        assertEquals(3.5, s[2].end, 1e-6)
    }

    @Test fun wordSettingsAreReadFromTheTrackName() {
        val st = CaptionStyle().withWordsOf("Auto" + CaptionStyle.wordTag("#ffd400ff", 3, 1.3f))
        assertEquals(0xFFFFD400.toInt(), st.wordColor)
        assertEquals(3, st.wordCount)
        assertEquals(1.3f, st.wordZoom, 1e-3f)
        assertEquals(0, CaptionStyle().withWordsOf("Auto;klipp:word=#ffd400ff").wordCount)
    }

    @Test fun wordColorIsReadFromTheTrackName() {
        assertEquals(0xFFFFD400.toInt(), CaptionStyle.wordColorOf("Auto;klipp:word=#ffd400ff"))
        assertEquals(null, CaptionStyle.wordColorOf("Auto"))
    }
}

class TitleStyleTest {
    @org.junit.Test fun theNewSettingsSurviveEncoding() {
        val look = se.gendut.klipp.engine.TitleLook(plate = se.gendut.klipp.engine.TitlePlate(on = true, color = 0x123456, opacity = 0.5f), opacity = 0.75f, stroke = 0.04f, strokeColor = 0xff0000, tracking = 0.1f, leading = 1.5f, align = "l")
        org.junit.Assert.assertEquals(look, se.gendut.klipp.engine.TitleLook.parse(look.encode()))
    }

    @org.junit.Test fun theSameAmountOnAllLogWheelsIsAnOffset() {
        for (x in listOf(0.1f, 0.33f, 0.5f, 0.9f)) assertEquals(x + 0.1f, se.gendut.klipp.engine.GradeNode.logTone(x, 0.1f, 0.1f, 0.1f, 0.333f, 0.55f), 1e-4f)
    }

    @org.junit.Test fun theShadowWheelLeavesHighlightsAlone() =
        assertEquals(0.9f, se.gendut.klipp.engine.GradeNode.logTone(0.9f, 0.2f, 0f, 0f, 0.333f, 0.55f), 1e-4f)
}

class PathSmoothingTest {
    private val gauss = se.gendut.klipp.engine.WindowTrack.Companion::gauss

    @org.junit.Test fun aSteadyPanIsKeptAllTheWay() {
        val pan = FloatArray(200) { it * 2f }
        val out = gauss(pan, 20f)
        for (i in pan.indices) assertEquals(pan[i], out[i], 0.05f)
    }

    @org.junit.Test fun theFirstFrameIsSmoothedToo() {
        val shake = FloatArray(200) { if (it % 2 == 0) 5f else -5f }
        assertEquals(0f, gauss(shake, 20f)[0], 1f)
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class CropTest {
    private val aspect = 16f / 9f

    @org.junit.Test fun afeatheredCropFadesInsideItsEdgeOnly() {
        val c = se.gendut.klipp.engine.LayerMask(cx = 0.6f, cy = 0.5f, w = 0.8f, h = 1f, feather = 0.2f).cropDrawn(aspect)
        assertEquals(0f, c.coverage(0.2f, 0.5f, aspect), 0.01f)
        assertEquals(1f, c.coverage(0.6f, 0.5f, aspect), 0.01f)
    }

    @org.junit.Test fun anUncroppedSideStaysHardAtTheFrameEdge() {
        val c = se.gendut.klipp.engine.LayerMask(cx = 0.6f, cy = 0.5f, w = 0.8f, h = 1f, feather = 0.2f).cropDrawn(aspect)
        assertEquals(1f, c.coverage(0.999f, 0.5f, aspect), 0.01f)
        assertEquals(1f, c.coverage(0.6f, 0.001f, aspect), 0.01f)
    }

    @org.junit.Test fun theCropSurvivesSaving() {
        val fx = se.gendut.klipp.engine.LayerFx(crop = se.gendut.klipp.engine.LayerMask(cx = 0.4f, w = 0.5f, h = 0.5f, feather = 0.1f))
        assertEquals(fx.crop!!.feather, se.gendut.klipp.engine.LayerFx.fromJson(fx.toJson())!!.crop!!.feather, 1e-4f)
    }
}
