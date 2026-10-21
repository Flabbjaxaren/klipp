package se.gendut.klipp

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.LayerPlanner
import se.gendut.klipp.engine.RenderCache
import se.gendut.klipp.engine.SegSrc
import se.gendut.klipp.engine.Snapshot
import java.io.File

/** Pre-rendering: the chunks, their keys and how finished chunks are swapped into the playback plan. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RenderCacheTest {
    private fun snap(effect: String): Snapshot {
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot_fx.json")!!.readText())
        o.getJSONObject("sequence").getJSONArray("video").getJSONObject(0).getJSONArray("transitions").getJSONObject(0).put("effect", effect)
        return Snapshot.parse(o)
    }
    private val uriOf: (Long) -> Uri? = { id -> Uri.parse("file:///media/$id.mp4") }

    @Test fun chunksSplitAtEveryEdgeAndKnowWhatIsHeavy() {
        // A (0–3 s) | B (3–6 s) with a transition 2.6–3.4
        val plan = LayerPlanner.plan(snap("wipe")) { 30.0 }!!
        val c = RenderCache.chunks(plan, { null }, uriOf, 30.0, 1080)
        assertEquals(listOf(0.0, 2.6, 3.0, 3.4), c.map { Math.round(it.a * 10) / 10.0 })
        assertEquals(6.0, c.last().b, 1e-9)
        // A is moved, scaled, rotated and semi-transparent: heavy; B is an ordinary clip – heavy only in the transition
        assertEquals(listOf(true, true, true, false), c.map { it.heavy })
        // a grade on B makes B's part heavy and only changes the keys where B is visible
        val g = ClipGrade(listOf(GradeNode(1, "Primär", contrast = 1.2f)))
        val c2 = RenderCache.chunks(plan, { id -> if (id == 13L) g else null }, uriOf, 30.0, 1080)
        assertTrue(c2[3].heavy)
        assertEquals(c[0].hash, c2[0].hash)
        assertNotEquals(c[1].hash, c2[1].hash); assertNotEquals(c[2].hash, c2[2].hash); assertNotEquals(c[3].hash, c2[3].hash)
        // same timeline: same keys (the files stay valid even after a restart)
        assertEquals(c.map { it.hash }, RenderCache.chunks(LayerPlanner.plan(snap("wipe")) { 30.0 }!!, { null }, uriOf, 30.0, 1080).map { it.hash })
        // another preview size or another transition: other files
        assertNotEquals(c[1].hash, RenderCache.chunks(plan, { null }, uriOf, 30.0, 720)[1].hash)
        assertNotEquals(c[1].hash, RenderCache.chunks(LayerPlanner.plan(snap("push")) { 30.0 }!!, { null }, uriOf, 30.0, 1080)[1].hash)
    }

    @Test fun readyChunksReplaceAllLayersThere() {
        RenderCache.init(ApplicationProvider.getApplicationContext())
        RenderCache.clear()
        val plan = LayerPlanner.plan(snap("wipe")) { 30.0 }!!
        val chunks = RenderCache.chunks(plan, { null }, uriOf, 30.0, 1080)
        assertTrue(RenderCache.substitute(plan, chunks) === plan)
        // the transition's two chunks (2.6–3.4) are rendered
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "render")
        for (c in chunks.subList(1, 3)) File(dir, c.hash + ".mp4").writeBytes(ByteArray(10))
        val sub = RenderCache.substitute(plan, chunks)
        // no upper layer left (the transition's layer was only there) – the compositor is not needed
        assertEquals(1, sub.layers.size)
        val segs = sub.layers.single().segs
        assertEquals(listOf(0.0 to 2.6, 2.6 to 3.0, 3.0 to 3.4, 3.4 to 6.0), segs.map { Math.round(it.start * 10) / 10.0 to Math.round(it.end * 10) / 10.0 })
        // the rendered chunks play from their files, without clipping (no effects on top)
        for (r in segs.subList(1, 3)) {
            assertTrue(r.clip == null && r.windows.isEmpty() && r.fx == null)
            assertEquals(0.0, (r.src as SegSrc.Media).srcIn, 0.0)
            assertTrue(RenderCache.uriOf(r.src.item)!!.path!!.endsWith(".mp4"))
        }
        // B continues after the transition at the right place in the source (5.0 + 0.4)
        assertEquals(5.4, (segs[3].src as SegSrc.Media).srcIn, 1e-9)
        assertEquals(1.0, (segs[0].src as SegSrc.Media).srcIn, 1e-9)
        assertEquals(listOf(2.6..3.0, 3.0..3.4).map { Math.round(it.start * 10) to Math.round(it.endInclusive * 10) },
            sub.rendered.map { Math.round(it.start * 10) to Math.round(it.endInclusive * 10) })
        // a change in the chunk: the file is no longer valid
        val g = ClipGrade(listOf(GradeNode(1, "Primär", contrast = 1.2f)))
        val changed = RenderCache.chunks(plan, { id -> if (id == 13L) g else null }, uriOf, 30.0, 1080)
        assertFalse(RenderCache.isReady(changed[2]))
        RenderCache.clear()
        assertTrue(RenderCache.substitute(plan, chunks) === plan)
    }

    @Test fun aSliceStartsLaterButKeepsTimelineTimes() {
        val plan = LayerPlanner.plan(snap("slide")) { 30.0 }!!
        val sl = LayerPlanner.slice(plan, 2.6, 3.4)
        assertEquals(2.6, sl.origin, 0.0); assertEquals(3.4, sl.total, 0.0); assertEquals(0.8, sl.length, 1e-9)
        assertTrue(sl.overlays.isEmpty())
        val base = sl.layers.last().segs
        assertEquals(2.6, base.first().start, 1e-9); assertEquals(3.4, base.last().end, 1e-9)
        // A trimmed at the start: the source shifted by the same amount (1.0 + 2.6)
        assertEquals(3.6, (base.first().src as SegSrc.Media).srcIn, 1e-9)
        // the transition's window remains in timeline time
        assertEquals(2.6, base.first().windows.single().t0, 1e-9)
    }
}
