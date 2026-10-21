package se.gendut.klipp

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.MediaProbe
import se.gendut.klipp.engine.Snapshot

/** Media3 decides the files' durations: the player gets the same values Media3 itself reads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaProbeTest {
    @Test fun lengthRules() {
        val u = Uri.parse("file:///probe/a.mp4")
        assertEquals(8.0, MediaProbe.length(Uri.parse("file:///probe/okand.mp4"), 8.0)!!, 0.0)
        MediaProbe.put(u, MediaProbe.Info(7.96, 0.0, 25.0))
        // the shortest wins (the engine allows no more than its own duration, the player no more than the file's)
        assertEquals(7.96, MediaProbe.length(u, 8.0)!!, 0.0)
        assertEquals(6.0, MediaProbe.length(u, 6.0)!!, 0.0)
        assertEquals(7.96, MediaProbe.length(u, null)!!, 0.0)
        assertNull(MediaProbe.length(null, null))
    }

    @Test fun playerGetsMedia3Lengths() {
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot.json")!!.readText())
        val s = Snapshot.parse(o)
        // Media3 reads file 1 as 6.02 s (engine: 10 s) – the clip 4.00–6.04 in it is still built
        val uriOf: (Long) -> Uri? = { Uri.parse("file:///probe/m$it.mp4") }
        MediaProbe.put(uriOf(1)!!, MediaProbe.Info(6.02, 0.0, 25.0))
        val comp = CompositionBuilder.build(s, uriOf = uriOf)!!
        val v = comp.sequences[0].editedMediaItems.filter { it.mediaItem.localConfiguration?.uri == uriOf(1) }
        assertTrue(v.isNotEmpty())
        for (it in v) {
            val c = it.mediaItem.clippingConfiguration
            // the duration given to Media3 is the file's according to Media3 – or the clip's end if that is later
            assertTrue(it.durationUs >= c.endPositionUs)
            assertTrue(it.durationUs == 6_020_000L || it.durationUs == c.endPositionUs)
        }
    }
}
