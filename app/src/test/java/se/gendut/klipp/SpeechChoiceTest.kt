package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.SpeechChoice
import se.gendut.klipp.engine.SpeechModel

/** Language + quality → model, and the language whisper is told. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechChoiceTest {
    @Test fun modelsFollowLanguageAndQuality() {
        assertEquals(SpeechModel.SV_FAST, SpeechChoice.model("sv", 0))
        assertEquals(SpeechModel.SV_BEST, SpeechChoice.model("sv", 1))
        assertEquals(SpeechModel.SV_LARGE, SpeechChoice.model("sv", 2))
        assertEquals(SpeechModel.EN_BASE, SpeechChoice.model("en", 0))
        assertEquals(SpeechModel.EN_SMALL, SpeechChoice.model("en", 1))
        assertEquals("English at best quality: the multilingual turbo model", SpeechModel.MULTI_TURBO, SpeechChoice.model("en", 2))
        assertEquals(SpeechModel.MULTI_BASE, SpeechChoice.model("de", 0))
        assertEquals(SpeechModel.MULTI, SpeechChoice.model("auto", 1))
        assertEquals(SpeechModel.MULTI_TURBO, SpeechChoice.model("ja", 9))
    }

    @Test fun whisperIsToldTheModelsOwnLanguageOrTheChosenOne() {
        assertEquals("sv", SpeechChoice.whisperLanguage(SpeechModel.SV_BEST, "sv"))
        assertEquals("en", SpeechChoice.whisperLanguage(SpeechModel.EN_SMALL, "en"))
        assertEquals("en", SpeechChoice.whisperLanguage(SpeechModel.MULTI_TURBO, "en"))
        assertEquals("auto", SpeechChoice.whisperLanguage(SpeechModel.MULTI, "auto"))
        assertEquals("de", SpeechChoice.whisperLanguage(SpeechModel.MULTI, "de"))
    }

    @Test fun languageNamesAndDefaults() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("nb-NO"))
            assertEquals("Norwegian Bokmål maps to Whisper's Norwegian", "no", SpeechChoice.defaultLanguage())
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("is-IS"))
            assertEquals("a language not offered: automatic", "auto", SpeechChoice.defaultLanguage())
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("sv-SE"))
            assertEquals("sv", SpeechChoice.defaultLanguage())
            assertEquals("Engelska", SpeechChoice.label("en"))
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            assertEquals("Swedish", SpeechChoice.label("sv"))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test fun everyModelHasItsOwnFile() {
        assertEquals(SpeechModel.entries.size, SpeechModel.entries.map { it.fileName }.toSet().size)
        // the files downloaded by earlier versions keep their names
        assertEquals("sv_best.bin", SpeechModel.SV_BEST.fileName)
        assertEquals("multi.bin", SpeechModel.MULTI.fileName)
    }
}
