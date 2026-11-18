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

/** Sound packs: a hosted pack is a pinned file on Klipp's release, kept only if it matches (tools/sounds). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SoundPacksTest {
    @Test fun aDownloadIsKeptOnlyIfItIsThePinnedFile() {
        val f = File.createTempFile("pack", ".zip")
        try {
            f.writeText("abc")
            val pin = SoundFx.Hosted("x-sounds-1.zip", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3)
            assertTrue(SoundFx.verified(f, pin))
            assertFalse(SoundFx.verified(f, pin.copy(sha256 = "0".repeat(64))))
            assertFalse(SoundFx.verified(f, pin.copy(bytes = 4)))
            assertEquals("https://github.com/Flabbjaxaren/klipp/releases/download/sounds-1/x-sounds-1.zip", pin.url)
        } finally { f.delete() }
    }

    @Test fun pinsMatchTheManifestAndEveryPackHasASource() {
        val ids = SoundFx.PACKS.map { it.id }.toSet()
        assertTrue(SoundFx.HOSTED.keys.all { it in ids })
        val manifest = listOf(File("../tools/sounds/${SoundFx.SOUNDS_RELEASE}.json"), File("tools/sounds/${SoundFx.SOUNDS_RELEASE}.json")).firstOrNull { it.isFile }
        if (manifest == null) { assertTrue("pins without a manifest", SoundFx.HOSTED.isEmpty()); return }
        val assets = JSONObject(manifest.readText()).getJSONArray("assets")
        val byPack = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("pack") }
        for ((pack, h) in SoundFx.HOSTED) {
            val a = byPack[pack] ?: error("$pack is pinned but not in ${manifest.name}")
            assertEquals(pack, a.getString("file"), h.file); assertEquals(pack, a.getString("sha256"), h.sha256); assertEquals(pack, a.getLong("bytes"), h.bytes)
        }
    }

    @Test fun aPackWithoutAFileOnKlippsReleaseIsNotDownloadedAndNothingIsTouched() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val pack = SoundFx.PACKS.first { it.hosted == null }
        // a pack already on the phone (from before) stays as it is
        val dir = File(SoundFx.dir(ctx), pack.id).apply { mkdirs() }
        File(dir, "Hit.wav").writeText("x"); File(dir, ".done").writeText("old")
        val r = kotlinx.coroutines.runBlocking { SoundFx.download(ctx, pack) }
        assertTrue(r.exceptionOrNull() is SoundFx.PackUnavailable)
        assertTrue(File(dir, "Hit.wav").exists() && SoundFx.installed(ctx, pack))
    }
}
