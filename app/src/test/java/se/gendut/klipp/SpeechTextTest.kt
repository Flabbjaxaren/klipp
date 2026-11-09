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

    private fun w(s: Double, e: Double, t: String) = SpeechLine(s, e, t)

    /** Captions from the word transcript: a clip's words land where the clip plays them, cut to what it plays. */
    @Test fun transcriptWordsOnTheTimeline() {
        val words = listOf(w(8.0, 8.4, "före"), w(9.1, 9.5, "Hej"), w(9.6, 10.0, "där."), w(12.9, 13.3, "efter"))
        // the clip plays 9..13 s of the file at half speed from 4 s on the timeline (8 s long)
        val l = SpeechText.onTimeline(words, start = 4.0, sourceIn = 9.0, duration = 8.0, speed = 0.5)
        assertEquals(listOf("Hej", "där."), l.map { it.text })
        assertEquals(4.2, l[0].start, 1e-9)
        assertEquals(6.0, l[1].end, 1e-9)
        assertTrue(SpeechText.onTimeline(words, 4.0, 9.0, 8.0, 0.5, reverse = true).isEmpty())
    }

    @Test fun wordsGroupIntoLinesAtSentencesPausesAndLength() {
        val words = listOf(
            w(0.0, 0.3, "Hej"), w(0.35, 0.6, "och"), w(0.65, 1.0, "välkommen."),
            w(1.1, 1.4, "Idag"), w(1.45, 1.7, "ska"), w(1.75, 2.0, "vi"),
            w(3.0, 3.3, "fiska"), // after a pause of a second
        )
        assertEquals(listOf("Hej och välkommen.", "Idag ska vi", "fiska"), SpeechText.group(words).map { it.text })
        assertEquals(1.1, SpeechText.group(words)[1].start, 1e-9)
        assertEquals(2.0, SpeechText.group(words)[1].end, 1e-9)
        // a long run without pauses is split before 42 characters
        val long = (0 until 20).map { i -> w(i * 0.3, i * 0.3 + 0.25, "ordet$i") }
        val lines = SpeechText.group(long)
        assertTrue(lines.size > 1)
        assertTrue(lines.joinToString { it.text }, lines.all { it.text.length <= 42 })
        assertEquals(long.joinToString(" ") { it.text }, lines.joinToString(" ") { it.text })
    }

    private fun say(text: String, t0: Double = 0.0): List<SpeechLine> {
        var t = t0
        return text.split(" ").map { x -> val d = 0.08 * x.length + 0.1; SpeechLine(t, t + d, x).also { t += d + 0.03 } }
    }

    @Test fun aSentenceThatFitsStaysWhole() {
        assertEquals(listOf("Vi åkte till Sicilien i somras."), SpeechText.group(say("Vi åkte till Sicilien i somras.")).map { it.text })
    }

    @Test fun sentencesNeverShareALine() {
        assertEquals(listOf("Det var kul.", "Ja."), SpeechText.group(say("Det var kul. Ja.")).map { it.text })
    }

    @Test fun aLongSentenceBreaksAtTheCommaAndKeepsWordsTogether() {
        val lines = SpeechText.group(say("När vi kom fram till byn vid havet, så gick vi direkt ner till stranden och badade hela dagen.")).map { it.text }
        assertTrue(lines.toString(), lines.all { it.length <= 42 })
        assertTrue(lines.toString(), lines[0].endsWith("havet,"))
        val tied = setOf("en", "den", "till", "vid", "och", "att", "på", "jag", "har")
        assertTrue(lines.toString(), lines.dropLast(1).none { it.substringAfterLast(' ').trimEnd(',') in tied })
    }

    @Test fun noOrphanWordAtTheEndOfASentence() {
        val lines = SpeechText.group(say("Jag tycker verkligen att det här är det bästa vi någonsin har gjort tillsammans.")).map { it.text }
        assertTrue(lines.toString(), lines.none { ' ' !in it })
        assertTrue(lines.toString(), lines.none { it.endsWith(" har") || it.endsWith(" att") || it.endsWith(" jag") })
    }

    @Test fun fillersAndRepeatsLeaveTheCaptionsOnly() {
        val w = say("Jag jag gick eh, hem öh.")
        assertEquals("Jag gick hem.", SpeechText.clean(w).joinToString(" ") { it.text })
        // digits may really be repeated, and a sentence end separates
        assertEquals(2, SpeechText.clean(say("10 10")).size)
        assertEquals(2, SpeechText.clean(say("Nej. Nej")).size)
    }

    @Test fun aFirstWordStretchedOverSilenceStartsWhenItIsSaid() {
        val l = SpeechText.group(listOf(w(1.0, 5.0, "Hej"), w(5.05, 5.4, "alla.")))
        assertEquals(5.0 - 0.65, l[0].start, 1e-9)
        val e = SpeechText.group(listOf(w(1.0, 1.3, "Hej"), w(1.35, 6.0, "alla.")))
        assertEquals(1.35 + 0.75, e[0].end, 1e-9)
    }

    @Test fun shortGapsClose() {
        val t = SpeechText.tidy(listOf(SpeechLine(0.0, 1.0, "a"), SpeechLine(1.2, 2.2, "b"), SpeechLine(4.0, 5.0, "c")))
        assertEquals(1.2, t[0].end, 1e-9)
        assertEquals(2.2, t[1].end, 1e-9)
    }
}
