package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.SoundFx
import java.io.File

/** Sound packs: every pack comes with the app as its pinned file, used only if it matches (tools/sounds). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SoundPacksTest {
    @Test fun aPackFileIsUsedOnlyIfItIsThePinnedFile() {
        val f = File.createTempFile("pack", ".zip")
        try {
            f.writeText("abc")
            val pin = SoundFx.Bundled("x-sounds-1.zip", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3)
            assertTrue(SoundFx.verified(f, pin))
            assertFalse(SoundFx.verified(f, pin.copy(sha256 = "0".repeat(64))))
            assertFalse(SoundFx.verified(f, pin.copy(bytes = 4)))
            assertEquals("sounds/x-sounds-1.zip", pin.asset)
        } finally { f.delete() }
    }

    /** Every pack is in the app, its file is there and is the one in the manifest – nothing is downloaded. */
    @Test fun everyPackIsInTheAppAndMatchesTheManifest() {
        assertEquals(SoundFx.PACKS.map { it.id }.toSet(), SoundFx.BUNDLED.keys)
        val manifest = listOf(File("../tools/sounds/${SoundFx.SOUNDS_RELEASE}.json"), File("tools/sounds/${SoundFx.SOUNDS_RELEASE}.json")).first { it.isFile }
        val assets = JSONObject(manifest.readText()).getJSONArray("assets")
        val byPack = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("pack") }
        val dir = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        for ((pack, h) in SoundFx.BUNDLED) {
            val a = byPack[pack] ?: error("$pack is in the app but not in ${manifest.name}")
            assertEquals(pack, a.getString("file"), h.file); assertEquals(pack, a.getString("sha256"), h.sha256); assertEquals(pack, a.getLong("bytes"), h.bytes)
            assertTrue("$pack: ${h.asset} matches its pin", SoundFx.verified(File(dir, h.asset), h))
        }
    }
}
