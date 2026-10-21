package se.gendut.klipp

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.LayerPlanner
import se.gendut.klipp.engine.Reversals
import se.gendut.klipp.engine.SegSrc
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.playable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reverse: the audio reversed, the time mapping to the reversed file, and that the rest of the app sees a forward clip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReversalsTest {
    @After fun clear() = Reversals.clearForTest()

    @Test fun wavIsTheSamplesBackwardsWithAllChannelsKeptTogether() {
        val dir = createTempDirectory()
        val raw = File(dir, "a.raw"); val wav = File(dir, "a.wav")
        // stereo: frame k = (k, -k), more frames than one chunk (the chunks are reversed too)
        val n = 40_000
        val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until n) { bb.putShort(k.toShort()); bb.putShort((-k).toShort()) }
        raw.writeBytes(bb.array())
        Reversals.writeReversedWav(raw, wav, 48_000, 2)
        val out = ByteBuffer.wrap(wav.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav.readBytes().copyOfRange(0, 4)))
        assertEquals(48_000, out.getInt(24)); assertEquals(2.toShort(), out.getShort(22)); assertEquals(n * 4, out.getInt(40))
        out.position(44)
        for (k in n - 1 downTo 0) {
            assertEquals(k.toShort(), out.getShort()); assertEquals((-k).toShort(), out.getShort())
        }
        assertFalse(out.hasRemaining())
    }

    private fun snap(edit: (JSONObject) -> Unit = {}): Snapshot {
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot_fx.json")!!.readText())
        edit(o)
        return Snapshot.parse(o)
    }
    private fun clip(o: JSONObject, kind: String, i: Int) = o.getJSONObject("sequence").getJSONArray(kind).getJSONObject(0).getJSONArray("clips").getJSONObject(i)

    @Test fun aReversedClipPlaysForwardsFromTheReversedFile() {
        // clip 13 (source 5–8 s) reversed; the file covers source 4–9 s → file time r = 9 − source time
        val s = snap { o -> clip(o, "video", 1).put("reverse", true); clip(o, "audio", 1).put("reverse", true) }
        // without a file: unchanged (forward until it is ready)
        assertSame(s, s.playable())
        Reversals.putForTest(1, "content://a", 4.0, 9.0, File("v.mp4"), File("a.wav"))
        val p = s.playable()
        val v = p.sequence!!.video[0].clips.first { it.id == 13L }
        val a = p.sequence!!.audio[0].clips.first { it.id == 14L }
        assertTrue(v.item < 0 && a.item < 0 && v.item != a.item)
        assertFalse(v.reverse || a.reverse)
        // the clip's start = the source's end 8 s → the file's 1 s, then forward at the same rate
        assertEquals(1.0, v.sourceIn, 1e-9); assertEquals(1.0, a.sourceIn, 1e-9)
        assertEquals(1.0, v.speed, 1e-9)
        // the same source time at every point as the reversed clip (source b − r)
        val orig = s.sequence!!.video[0].clips.first { it.id == 13L }
        for (t in listOf(3.0, 4.2, 5.9)) assertEquals(orig.sourceAt(t), 9.0 - v.sourceAt(t), 1e-9)
        // the files are found via their own ids
        assertEquals("v.mp4", Reversals.uriOf(v.item)!!.lastPathSegment)
        assertEquals("a.wav", Reversals.uriOf(a.item)!!.lastPathSegment)
        // the other clip is untouched
        assertEquals(10L, p.sequence!!.video[0].clips.first().id); assertEquals(1L, p.sequence!!.video[0].clips.first().item)
        // the video layers: the piece from the reversed file, forward
        val plan = LayerPlanner.plan(s) { 30.0 }!!
        val seg = plan.layers.last().segs.last()
        assertEquals(v.item, seg.src.item)
        assertEquals(1.0 + (seg.start - 3.0), (seg.src as SegSrc.Media).srcIn, 1e-9)
    }

    @Test fun aFileThatDoesNotCoverTheClipIsNotUsed() {
        val s = snap { o -> clip(o, "video", 1).put("reverse", true).put("speed", 2.0) } // source 5–11 s
        Reversals.putForTest(1, "content://a", 4.0, 9.0, File("v.mp4"), null)
        assertTrue(s.playable().sequence!!.video[0].clips.first { it.id == 13L }.reverse)
        Reversals.putForTest(1, "content://a", 4.0, 12.0, File("v2.mp4"), null)
        val v = s.playable().sequence!!.video[0].clips.first { it.id == 13L }
        assertFalse(v.reverse)
        assertEquals(2.0, v.speed, 1e-9)
        assertEquals(1.0, v.sourceIn, 1e-9) // 12 − 11
        assertEquals("v2.mp4", Reversals.uriOf(v.item)!!.lastPathSegment)
        // the file has no audio: the audio clip is left as is
        val s2 = snap { o -> clip(o, "audio", 1).put("reverse", true) }
        assertTrue(s2.playable().sequence!!.audio[0].clips.first { it.id == 14L }.reverse)
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("rev").toFile()
}
