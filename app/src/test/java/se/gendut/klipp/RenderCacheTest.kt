package se.gendut.klipp

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
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
        assertNotEquals(c[1].hash, RenderCache.chunks(LayerPlanner.plan(snap("whip")) { 30.0 }!!, { null }, uriOf, 30.0, 1080)[1].hash)
    }

    @Test fun aNewLutUnderTheSameNameMakesTheChunksAgain() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        se.gendut.klipp.engine.LutStore.init(ctx)
        fun cube(v: Float) = "LUT_3D_SIZE 2\n" + (0 until 8).joinToString("\n") { "$v $v $v" }
        fun import(v: Float): String {
            val f = File(ctx.cacheDir, "LUT.cube").apply { writeText(cube(v)) }
            return se.gendut.klipp.engine.LutStore.import(ctx, Uri.fromFile(f))
        }
        val name = import(0.2f)
        val plan = LayerPlanner.plan(snap("wipe")) { 30.0 }!!
        val g = ClipGrade(listOf(GradeNode(1, "Primär", lut = name)))
        val before = RenderCache.chunks(plan, { id -> if (id == 13L) g else null }, uriOf, 30.0, 1080)
        import(0.8f)
        val after = RenderCache.chunks(plan, { id -> if (id == 13L) g else null }, uriOf, 30.0, 1080)
        // where B (with the LUT) is seen, the keys change; the part with only A stays
        assertEquals(before[0].hash, after[0].hash)
        assertNotEquals(before[3].hash, after[3].hash)
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

    @Test fun aTransitionAddedLaterKeepsTheRestOfTheRenderedClip() {
        RenderCache.init(ApplicationProvider.getApplicationContext())
        RenderCache.clear()
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot_fx.json")!!.readText())
        o.getJSONObject("sequence").getJSONArray("video").getJSONObject(0).put("transitions", org.json.JSONArray())
        val bare = LayerPlanner.plan(Snapshot.parse(o)) { 30.0 }!!
        val g = ClipGrade(listOf(GradeNode(1, "Primär", contrast = 1.2f)))
        val grades = { id: Long -> if (id == 13L) g else null }
        val rendered = RenderCache.chunks(bare, grades, uriOf, 30.0, 1080)
        assertEquals(3.0, rendered.last().a, 1e-9)
        // (the cache's folder is set once per process, the test's data folder differs per test)
        val dir = RenderCache::class.java.getDeclaredField("dir").apply { isAccessible = true }.get(RenderCache) as File
        File(dir, rendered.last().hash + ".mp4").writeBytes(ByteArray(10))
        // B (3–6 s) was rendered as a whole; the transition 2.6–3.4 leaves 3.4–6 unchanged
        val plan = LayerPlanner.plan(snap("whip")) { 30.0 }!!
        val chunks = RenderCache.chunks(plan, grades, uriOf, 30.0, 1080)
        assertTrue(RenderCache.isReady(chunks.last()))
        assertFalse(chunks.dropLast(1).any { RenderCache.isReady(it) })
        val tail = RenderCache.substitute(plan, chunks).layers.last().segs.last()
        assertTrue(tail.clip == null)
        assertEquals(0.4, (tail.src as SegSrc.Media).srcIn, 1e-6)
        // another grade: another picture, not the old file
        val other = ClipGrade(listOf(GradeNode(1, "Primär", contrast = 1.5f)))
        assertFalse(RenderCache.isReady(RenderCache.chunks(plan, { id -> if (id == 13L) other else null }, uriOf, 30.0, 1080).last()))
        RenderCache.clear()
    }

    @Test fun aRenderInProgressStopsWhenPlaybackStarts() {
        kotlinx.coroutines.runBlocking {
            // allowed all the way: the block's own answer
            assertEquals(7, RenderCache.whileAllowed({ true }) { kotlinx.coroutines.delay(150); 7 })
            // no longer allowed in the middle (playback started): the block is cancelled, and the answer says so
            var allowed = true
            var cancelled = false
            val t = System.nanoTime()
            val out = RenderCache.whileAllowed({ allowed }) {
                try { kotlinx.coroutines.delay(250); allowed = false; kotlinx.coroutines.delay(60_000); 7 }
                catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
            }
            assertEquals(null, out)
            assertTrue(cancelled)
            assertTrue((System.nanoTime() - t) / 1_000_000 < 5_000)
            // cancelled from outside (the timeline changed): cancelled as usual, not taken for a pause
            val outer = launch { RenderCache.whileAllowed({ true }) { kotlinx.coroutines.delay(60_000) }; org.junit.Assert.fail("went on") }
            kotlinx.coroutines.delay(100)
            outer.cancel()
            outer.join()
            assertTrue(outer.isCancelled)
        }
    }
}
