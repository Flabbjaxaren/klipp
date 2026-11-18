package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.SpeechModel
import se.gendut.klipp.engine.SpeechModels

/** Every speech model is pinned to one file: a repository revision and its SHA-256 (docs/licensing.md, downloads). */
class SpeechModelPinTest {
    @Test fun everyModelIsPinnedToARevisionAndAHash() {
        for (m in SpeechModel.entries) {
            assertTrue(m.name + ": " + m.url, Regex("^https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[^/]+\\.bin$").matches(m.url))
            assertTrue(m.name, Regex("^[0-9a-f]{64}$").matches(m.sha256))
        }
        assertEquals("the same files as before pinning", 8, SpeechModel.entries.map { it.sha256 }.toSet().size)
    }

    @Test fun theHashIsTheFilesSha256() {
        val f = java.io.File.createTempFile("klipp", ".bin")
        try {
            f.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SpeechModels.sha256(f))
        } finally { f.delete() }
    }
}
