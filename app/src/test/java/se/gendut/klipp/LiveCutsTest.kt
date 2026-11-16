package se.gendut.klipp

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.ExportSettings
import se.gendut.klipp.engine.PrerollDrop
import se.gendut.klipp.engine.Snapshot

/**
 * Live playback's cuts with one video renderer (LiveCuts): the player's clips start "at a keyframe" (no initial
 * discontinuity) and drop the frames before their start first in their effects; the export and the pre-render keep
 * Media3's own clipping (the frames before the in point skipped by the decoder, never seen by an effect).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveCutsTest {
    private fun snap() = Snapshot.parse(JSONObject(javaClass.classLoader!!.getResource("snapshot.json")!!.readText()))
    private val uriOf: (Long) -> Uri? = { Uri.parse("file:///m/$it.mp4") }

    @Test fun playbackClipsStartAtAKeyFrameAndDropTheirPrerollFirst() {
        val s = snap()
        val clips = s.sequence!!.video[0].clips.sortedBy { it.start }
        val items = CompositionBuilder.buildFull(s, uriOf, forceSingle = true)!!.composition.sequences[0].editedMediaItems
            .filter { it.mediaItem.localConfiguration != null }
        assertEquals(clips.size, items.size)
        for ((c, item) in clips.zip(items)) {
            assertTrue(item.mediaItem.clippingConfiguration.startsAtKeyFrame)
            // the clip's start on the composition's clock (the timeline begins at 0 here)
            assertEquals(Math.round(c.start * 1e6), (item.effects.videoEffects.first() as PrerollDrop).startUs)
        }
    }

    @Test fun theExportKeepsMedia3sClipping() {
        val items = CompositionBuilder.build(snap(), uriOf, output = ExportSettings())!!.sequences[0].editedMediaItems
            .filter { it.mediaItem.localConfiguration != null }
        assertTrue(items.isNotEmpty())
        for (item in items) {
            assertFalse(item.mediaItem.clippingConfiguration.startsAtKeyFrame)
            assertTrue(item.effects.videoEffects.none { it is PrerollDrop })
        }
    }
}
