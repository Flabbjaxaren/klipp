package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.SpeechLine
import se.gendut.klipp.engine.SpeechText

/** From the speech recognizer's response to captions: timing, junk in silence, overlaps and SRT. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechTextTest {
    @Test fun parseMapsSourceTimeToTimeline() {
        val json = """[{"t0":0,"t1":2000,"text":" Hej och välkommen."},{"t0":2000,"t1":5200,"text":"  Det här  är ett test. "}]"""
        // the piece starts 10 s into the file; the clip sits at 4 s with source start 9 s and half speed
        val l = SpeechText.parse(json, 10.0) { src -> 4.0 + (src - 9.0) / 0.5 }
        assertEquals(2, l.size)
        assertEquals(6.0, l[0].start, 1e-9)
        assertEquals(10.0, l[0].end, 1e-9)
        assertEquals("Det här är ett test.", l[1].text)
    }

    @Test fun dropsSilenceHallucinationsAndSoundTags() {
        val json = """[{"t0":0,"t1":1000,"text":" Svensktextning.nu"},{"t0":1000,"t1":2000,"text":" [MUSIK]"},
            {"t0":2000,"t1":3000,"text":" ..."},{"t0":3000,"t1":4000,"text":" Riktig mening."}]"""
        val l = SpeechText.parse(json, 0.0) { it }
        assertEquals(listOf("Riktig mening."), l.map { it.text })
    }

    @Test fun tidyRemovesOverlapAndKeepsShortLinesReadable() {
        val l = SpeechText.tidy(listOf(SpeechLine(5.0, 5.2, "Ja."), SpeechLine(1.0, 3.5, "Först."), SpeechLine(3.0, 4.0, "Sedan.")))
        assertEquals(listOf("Först.", "Sedan.", "Ja."), l.map { it.text })
        // ends where the next one starts
        assertEquals(3.0, l[0].end, 1e-9)
        // a short line gets at least 0.7 s
        assertEquals(5.7, l[2].end, 1e-9)
    }

    @Test fun srtFormat() {
        val s = SpeechText.srt(listOf(SpeechLine(1.5, 3.25, "Hej"), SpeechLine(3661.0, 3662.0, "Sent")))
        assertTrue(s, s.startsWith("1\n00:00:01,500 --> 00:00:03,250\nHej\n\n2\n01:01:01,000 --> 01:01:02,000\nSent\n"))
    }
}
