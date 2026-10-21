package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.SpeechLine

/** Sentences and their marks for picking by text in the source monitor. */
class SourceTextTest {
    private fun w(a: Double, b: Double, t: String) = SpeechLine(a, b, t)

    @Test fun sentencesSplitAtPunctuationAndLongPauses() {
        val ws = listOf(w(0.0, 0.5, "Hej."), w(0.6, 1.0, "Vad"), w(1.0, 1.4, "gör"), w(1.4, 1.8, "du?”"), w(2.0, 2.4, "inget"), w(5.0, 5.5, "sen"), w(5.5, 6.0, "då"))
        assertEquals(listOf(0..0, 1..3, 4..4, 5..6), sentences(ws))
        assertEquals(emptyList<IntRange>(), sentences(emptyList()))
    }

    @Test fun marksKeepClearOfNeighboursAndTheClip() {
        val ws = listOf(w(0.05, 0.5, "A."), w(0.55, 1.0, "B."), w(1.1, 2.0, "C."))
        val frame = 0.04
        // the first word near the clip start: in = 0, not negative
        assertEquals(0.0, sentenceMarks(ws, 0, 0, 2.05, frame).first, 1e-9)
        // the middle one: not into A (ends 0.5) nor C (starts 1.1)
        val (i, o) = sentenceMarks(ws, 1, 1, 2.05, frame)
        assertEquals(0.5, i, 1e-9)
        assertEquals(1.1 - frame, o, 1e-9)
        // the last: at most the clip's end
        assertEquals(2.05 - frame, sentenceMarks(ws, 2, 2, 2.05, frame).second, 1e-9)
    }
}
