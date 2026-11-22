package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.SpeechDuck

class SpeechDuckTest {
    @Test fun wordsCloseTogetherAreOneStretch() {
        val r = SpeechDuck.regions(listOf(1.0 to 1.3, 1.5 to 1.9, 2.2 to 2.6, 6.0 to 6.4))
        assertEquals(2, r.size)
        assertEquals(1.0 - SpeechDuck.PRE, r[0].first, 1e-9)
        assertEquals(2.6 + SpeechDuck.POST, r[0].second, 1e-9)
    }

    @Test fun musicGoesDownUnderTheWordsAndBackAfter() {
        val k = SpeechDuck.keys(listOf(2.0 to 4.0), 0.0, 10.0, -3.0, -12.0)
        assertEquals(listOf(2.0 - SpeechDuck.FADE to -3.0, 2.0 to -15.0, 4.0 to -15.0, 4.0 + SpeechDuck.FADE to -3.0), k)
    }

    @Test fun aFadeCutByTheClipStartsAtTheRightLevel() {
        val k = SpeechDuck.keys(listOf(0.1 to 2.0), 0.0, 10.0, 0.0, -10.0)
        assertEquals(0.0, k.first().first, 1e-9)
        assertTrue(k.first().second < 0.0 && k.first().second > -10.0)
    }

    @Test fun lyricsInTheMusicAreNotSpeech() {
        // words only where another track plays count
        val w = SpeechDuck.speechOnly(listOf(1.0 to 1.5, 8.0 to 8.5), listOf(0.0 to 5.0))
        assertEquals(listOf(1.0 to 1.5), w)
    }
}
