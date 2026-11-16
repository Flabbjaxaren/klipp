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

    @Test fun aGapBeforeAClipPlaysAsBlackVideoInPlaybackOnly() {
        val s0 = snap()
        val q = s0.sequence!!
        val last = q.video[0].clips.maxBy { it.start }
        val s = s0.copy(sequence = q.copy(duration = q.duration + 0.6, video = q.video.mapIndexed { i, t ->
            if (i == 0) t.copy(clips = t.clips.map { c -> if (c.id == last.id) c.copy(start = c.start + 0.6) else c }) else t
        }))
        val items = CompositionBuilder.buildFull(s, uriOf, forceSingle = true)!!.composition.sequences[0].editedMediaItems
        val gap = items.single { it.mediaItem.localConfiguration?.uri == se.gendut.klipp.engine.LiveCuts.GAP_URI }
        val clip = gap.mediaItem.clippingConfiguration
        assertEquals(600_000L, clip.endPositionUs - clip.startPositionUs)
        assertTrue(clip.startsAtKeyFrame)
        // right before the clip after it
        assertEquals(uriOf(last.item), items[items.indexOf(gap) + 1].mediaItem.localConfiguration?.uri)
        // the export keeps Media3's gap
        val exp = CompositionBuilder.build(s, uriOf, output = ExportSettings())!!.sequences[0].editedMediaItems
        assertTrue(exp.none { it.mediaItem.localConfiguration?.uri == se.gendut.klipp.engine.LiveCuts.GAP_URI })
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
