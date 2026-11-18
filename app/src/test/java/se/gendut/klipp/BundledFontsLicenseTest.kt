package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gendut.klipp.engine.Fonts
import java.io.File
import java.security.MessageDigest

/**
 * The fonts whose OFL license reserves their name ship as the publishers' original files, byte for byte
 * (google/fonts e7db6ca) – a changed file may not use a Reserved Font Name (docs/licensing-fonts.md).
 */
class BundledFontsLicenseTest {
    private val unmodified = mapOf(
        "Raleway/Italic.ttf" to "96629caf2202183fab46c70237055a7d67e6a5400b85413d45a77ed6f2a0770c",
        "Raleway/Regular.ttf" to "8bbcc3eb8275c388f4bcd998832f8a4b943eadbaf6a595205312774b5951aefb",
        "AbrilFatface/Regular.ttf" to "5971d4a3758a922a9fedc7f6fb825a96341a2e718c45a4b2c9a6b417c8c4dbe9",
        "Righteous/Regular.ttf" to "2ffb3fe5c27d7e6571210b800448c4e234e651b46c6b4426c1bb567e5341348a",
        "PlayfairDisplay/Italic.ttf" to "a5e26dc5e2e77fb2803a0bf02fd4f81ee136ec8dea863ccdb0c59a263b21378b",
        "PlayfairDisplay/Regular.ttf" to "c40f2293766a503bc70cce9e512ef844a4ccb7cbcde792fe2ea31d191917d8d6",
        "Lora/Italic.ttf" to "22d8d8854b53807aa664ca34f2031a9ed57a1d0dea296b8b96cdd3aad937a2b3",
        "Lora/Regular.ttf" to "822a6621ccbe8d97d20ac88c1c41f5615c9c2c202eaa75f272cd452aac6475a7",
        "Merriweather/Italic.ttf" to "f68a8f4989258679e4fbaf50aa42400132b5373c2d9d2514ba82ef6e85947a0b",
        "Merriweather/Regular.ttf" to "d0ed0e359e396af7ad05e73dffd11a3a4c326ea0d0283c56bd9361cb2cc86a96",
        "LibreBaskerville/Italic.ttf" to "223959683dc73ec4437bd61fabaa4b3f22209e22855ffd3aee36ba61a5116e97",
        "LibreBaskerville/Regular.ttf" to "05a95421961341c5b2556285e8415df9db27dab4f4abe22b446b3c6a8b916c5d",
        "DancingScript/Regular.ttf" to "21808625578fe8d8cd10cb684be546dca077b27cd03a53a2f1ec11dc743c924c",
    )

    @Test fun reservedNameFontsAreTheOriginalFiles() {
        val base = File("src/main/assets/fonts").takeIf { it.isDirectory } ?: File("app/src/main/assets/fonts")
        for ((path, sha) in unmodified) {
            val d = MessageDigest.getInstance("SHA-256").digest(File(base, path).readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(path, sha, d)
        }
    }

    @Test fun merriweatherKeepsTheLookOfItsEarlierFile() {
        // the earlier (instanced) file was the 72 pt optical size at normal width; the original file defaults to 18 pt
        val b = Fonts.Bundled("Merriweather", "Merriweather", true, setOf("regular", "italic"), "'opsz' 72, 'wdth' 100")
        assertEquals("'wght' 700, 'opsz' 72, 'wdth' 100", Fonts.variation(b, bold = true))
        assertEquals("'wght' 400", Fonts.variation(Fonts.Bundled("Lora", "Lora", true, setOf("regular")), bold = false))
    }
}
