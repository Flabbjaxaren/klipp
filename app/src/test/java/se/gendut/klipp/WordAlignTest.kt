package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import se.gendut.klipp.engine.Ctc
import se.gendut.klipp.engine.SpeechLine
import se.gendut.klipp.engine.WordAligner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class WordAlignTest {
    /** Log-probabilities that say letter [tok] in the given frames, blank elsewhere. */
    private fun lp(frames: Int, marks: Map<Int, Int>): Array<FloatArray> = Array(frames) { f ->
        FloatArray(WordAligner.VOCAB.size) { v ->
            val want = marks[f] ?: WordAligner.BLANK
            if (v == want) 0f else -10f
        }
    }

    @Test fun lettersMapToTheModelsTokens() {
        val t = Ctc.tokens("Hej, åÄ!")
        assertEquals(listOf("H", "E", "J", "Å", "Ä"), t.map { WordAligner.VOCAB[it] })
    }

    @Test fun twoWordsAreFoundWhereTheirLettersSound() {
        val v = WordAligner.VOCAB
        val marks = mapOf(10 to v.indexOf("J"), 11 to v.indexOf("A"), 30 to v.indexOf("N"), 31 to v.indexOf("E"), 32 to v.indexOf("J"))
        val r = Ctc.align(lp(50, marks), 0, 50, listOf("ja", "nej"))!!
        assertEquals(10 to 12, r[0])
        assertEquals(30 to 33, r[1])
    }

    @Test fun aWordWithoutKnownLettersKeepsNoTimes() {
        val v = WordAligner.VOCAB
        val r = Ctc.align(lp(20, mapOf(5 to v.indexOf("A"))), 0, 20, listOf("—", "a"))!!
        assertEquals(null, r[0])
        assertNotNull(r[1])
    }

    @Test fun blocksSplitAtPauses() {
        val w = listOf(SpeechLine(0.0, 0.4, "a"), SpeechLine(0.5, 0.9, "b"), SpeechLine(2.0, 2.4, "c"))
        assertEquals(listOf(listOf(0, 1), listOf(2)), Ctc.blocks(w))
    }

    /**
     * The model's real output for Swedish speech with known word edges (noise at 20 dB SNR), dumped by the measuring
     * script: the edges land within a few ms of the truth in the typical word. Needs KLIPP_ALIGN_DIR (skipped otherwise).
     */
    @Test fun realSpeechEdgesAreExact() {
        val dir = System.getenv("KLIPP_ALIGN_DIR")
        assumeTrue(dir != null && File(dir, "speech_snr20.lp.f32").isFile)
        fun floats(f: File) = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        val lpRaw = floats(File(dir, "speech_snr20.lp.f32"))
        val v = WordAligner.VOCAB.size
        val lp = Array(lpRaw.size / v) { f -> lpRaw.copyOfRange(f * v, f * v + v) }
        val pcm = floats(File(dir, "speech_snr20.pcm.f32"))
        val truth = File(dir, "truth.tsv").readLines().map { it.split("\t") }.map { SpeechLine(it[1].toDouble(), it[2].toDouble(), it[0]) }
        // Whisper's times are off by up to ~150 ms (early starts): the blocks are built from those
        val heard = truth.mapIndexed { i, w -> w.copy(start = w.start - 0.15 + (i % 3) * 0.05, end = w.end + 0.1 - (i % 2) * 0.12) }
        val level = WordAligner.Level(pcm)
        val out = heard.toMutableList()
        for (b in Ctc.blocks(heard)) {
            val ws = b.map { heard[it] }
            val f0 = ((ws.first().start - 0.6) / WordAligner.FRAME_S).toInt().coerceIn(0, lp.size)
            val f1 = ((ws.last().end + 0.6) / WordAligner.FRAME_S).toInt().coerceIn(f0, lp.size)
            val t = Ctc.align(lp, f0, f1, ws.map { it.text }) ?: continue
            val e = level.refine(t.map { p -> p?.let { it.first * WordAligner.FRAME_S to it.second * WordAligner.FRAME_S } })
            for ((k, i) in b.withIndex()) e[k]?.let { out[i] = heard[i].copy(start = it.first, end = it.second) }
        }
        val ds = out.zip(truth).map { (a, b) -> abs(a.start - b.start) }.sorted()
        val de = out.zip(truth).map { (a, b) -> abs(a.end - b.end) }.sorted()
        println("align: start |err| median %.0f ms, p90 %.0f; end median %.0f, p90 %.0f".format(ds[ds.size / 2] * 1e3, ds[ds.size * 9 / 10] * 1e3, de[de.size / 2] * 1e3, de[de.size * 9 / 10] * 1e3))
        assertTrue(ds[ds.size / 2] < 0.015)
        assertTrue(de[de.size / 2] < 0.015)
    }
}
