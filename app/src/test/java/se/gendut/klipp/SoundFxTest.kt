package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.SoundFx

/** Sound effects: readable names, which track a sound goes on, Freesound's answer and the WAV header. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SoundFxTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun namesAreReadable() {
        assertEquals("Footstep carpet 000", SoundFx.humanize("footstep_carpet_000"))
        assertEquals("High up", SoundFx.humanize("highUp"))
        assertEquals("8 bit jingles jingles nes00", SoundFx.humanize("8-Bit jingles jingles_NES00"))
    }

    @Test fun soundGoesOnTheFirstFreeTrackDialogueLast() {
        val none = listOf(emptyList<Pair<Double, Double>>(), emptyList(), emptyList())
        assertEquals(1, SoundFx.freeTrack(none, 2.0, 1.0)) // A2 first
        val a2busy = listOf(emptyList(), listOf(1.0 to 5.0), emptyList())
        assertEquals(2, SoundFx.freeTrack(a2busy, 2.0, 1.0)) // A3
        assertEquals(1, SoundFx.freeTrack(a2busy, 5.0, 1.0)) // A2 free again after 5 s
        val a2a3 = listOf(emptyList(), listOf(0.0 to 10.0), listOf(0.0 to 10.0))
        assertEquals(0, SoundFx.freeTrack(a2a3, 2.0, 1.0)) // then A1
        val all = listOf(listOf(0.0 to 10.0), listOf(0.0 to 10.0), listOf(0.0 to 10.0))
        assertEquals(2, SoundFx.freeTrack(all, 2.0, 1.0)) // all busy: A3
    }

    @Test fun freesoundAnswerBecomesHits() {
        val json = """{"count":2,"results":[
            {"id":123,"name":"whoosh fast.wav","duration":1.25,"username":"anna","previews":{"preview-hq-mp3":"https://cdn.freesound.org/previews/0/123-hq.mp3","preview-lq-mp3":"x"}},
            {"id":124,"name":"no preview","duration":2.0,"username":"bo"}]}"""
        val hits = SoundFx.parseSearch(json)
        assertEquals(1, hits.size)
        assertEquals(SoundFx.Hit(123, "whoosh fast", 1.25, "https://cdn.freesound.org/previews/0/123-hq.mp3", "anna"), hits[0])
        // an error from Freesound (wrong key) is reported, not shown as "no results"
        val err = runCatching { SoundFx.parseSearch("""{"detail":"Invalid token."}""") }
        assertTrue(err.isFailure); assertEquals("Invalid token.", err.exceptionOrNull()?.message)
    }

    @Test fun wavHeaderGivesTheLength() {
        val f = tmp.newFile("x.wav")
        // 0.5 s of 48 kHz stereo 16-bit
        val bytes = 48_000L * 2 * 2 / 2
        f.writeBytes(SoundFx.wavHeader(bytes, 48_000, 2) + ByteArray(bytes.toInt()))
        assertEquals(0.5, SoundFx.wavSeconds(f), 1e-9)
        val h = f.readBytes().copyOf(12)
        assertEquals("RIFF", String(h, 0, 4)); assertEquals("WAVE", String(h, 8, 4))
    }

    @Test fun catalogIsSane() {
        assertEquals(SoundFx.PACKS.size, SoundFx.PACKS.map { it.id }.toSet().size)
        for (p in SoundFx.PACKS) {
            // a zip (Kenney) or cut clips (BigSoundBank), never both or neither
            assertTrue(p.id, (p.url != null) xor p.clips.isNotEmpty())
            assertEquals(p.id, p.clips.size, p.clips.map { it.name }.toSet().size)
            for (c in p.clips) {
                assertTrue("${p.id} ${c.name}", c.t1 > c.t0 && c.t1 - c.t0 in 0.05..10.0)
                assertTrue("${p.id} ${c.name}", c.gainDb in -6f..12f && c.fade in 0.0..2.0 && c.file.length == 4)
            }
        }
        assertTrue(SoundFx.PACKS.count { it.source == SoundFx.BIGSOUNDBANK } == 4)
    }

    @Test fun softLimitNeverClips() {
        assertEquals(0.5, SoundFx.softLimit(0.5), 0.0)
        assertEquals(-0.9, SoundFx.softLimit(-0.9), 0.0)
        for (v in listOf(0.95, 1.0, 1.5, 3.0)) {
            val y = SoundFx.softLimit(v)
            assertTrue("$v → $y", y > 0.9 && y <= 1.0)
            assertEquals(-y, SoundFx.softLimit(-v), 1e-12)
        }
        assertTrue(SoundFx.softLimit(1.2) > SoundFx.softLimit(1.0))
    }
}
