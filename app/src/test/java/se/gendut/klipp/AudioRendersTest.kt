package se.gendut.klipp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.AfxSnap
import se.gendut.klipp.engine.AudioRenders
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.playable
import java.io.File

/** Rendered sound effects: which part of a rack goes into the file, and that the clip then plays it with the rest live. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioRendersTest {
    @After fun clear() = AudioRenders.clearForTest()

    private fun fx(i: Int, id: String, on: Boolean = true) = AfxSnap(i, id, on, mapOf("amount" to 40.0))

    @Test fun theRackIsRenderedUpToTheLastRenderedEffect() {
        assertNull(AudioRenders.split(listOf(fx(0, "parametric_eq"), fx(1, "hard_limiter"))))
        assertNull(AudioRenders.split(listOf(fx(0, "denoise", on = false))))
        val (pre, rest) = AudioRenders.split(listOf(fx(0, "parametric_eq"), fx(1, "studio_reverb", on = false), fx(2, "denoise"), fx(3, "hard_limiter")))!!
        // the effects in front that are on go into the file with it; the ones after play live
        assertEquals(listOf(0, 2), pre.map { it.index })
        assertEquals(listOf(3), rest.map { it.index })
    }

    private fun snap(): Snapshot {
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot_fx.json")!!.readText())
        val c = o.getJSONObject("sequence").getJSONArray("audio").getJSONObject(0).getJSONArray("clips").getJSONObject(1)
        c.put("afx", JSONArray().put(JSONObject().put("i", 0).put("id", "denoise").put("on", true).put("p", JSONObject().put("amount", 40.0)))
            .put(JSONObject().put("i", 1).put("id", "hard_limiter").put("on", true).put("p", JSONObject())))
        return Snapshot.parse(o)
    }

    @Test fun aClipPlaysItsRenderedFileWithTheRestOfItsEffectsLive() {
        val s = snap()
        val orig = s.sequence!!.audio[0].clips[1]
        // without a file: unchanged (the effects play live until it is ready)
        assertSame(s, s.playable())
        val (pre, _) = AudioRenders.split(orig.afx)!!
        val wav = File.createTempFile("afxr", ".wav").apply { deleteOnExit() }
        val id = AudioRenders.putForTest(orig.item, "content://a", orig.sourceIn - 1.0, orig.sourceIn + orig.duration + 1.0, AfxSnap.chainJson(pre), wav)
        val c = s.playable().sequence!!.audio[0].clips[1]
        assertEquals(id, c.item)
        assertEquals(1.0, c.sourceIn, 1e-9)
        assertEquals(listOf("hard_limiter"), c.afx.map { it.id })
        assertTrue(id < -(1L shl 40) + 1)
        // other settings than the file was made with: not used
        AudioRenders.clearForTest()
        AudioRenders.putForTest(orig.item, "content://a", orig.sourceIn - 1.0, orig.sourceIn + orig.duration + 1.0, AfxSnap.chainJson(pre.map { it.copy(params = mapOf("amount" to 90.0)) }), wav)
        assertEquals(orig.item, s.playable().sequence!!.audio[0].clips[1].item)
    }
}
