package se.gendut.klipp

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipSnap
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.LayerPlanner
import se.gendut.klipp.engine.SegSrc
import se.gendut.klipp.engine.Snapshot

/**
 * Timing shared by the preview's layers and the export (plan PR 4a / 8.2, on the computer): which clip each layer shows
 * at every frame of a three-layer project with trimmed, sped-up and late-starting clips, and which second of the file.
 * The engine's own mapping ([ClipSnap.sourceAt]) is the reference; the preview's upper layers and the export both go
 * through [se.gendut.klipp.engine.Seg.sourceAt] and the plan's segments.
 *
 * What these tests can't see (decoders, surfaces, the frame actually presented) is measured on the phone (PR 4b/8.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreviewExportTimingTest {
    private val fps = 25.0

    private fun clip(id: Int, item: Int, start: Double, dur: Double, srcIn: Double, speed: Double = 1.0) = JSONObject()
        .put("id", id).put("item", item).put("name", "c$id.mp4").put("start", start).put("duration", dur)
        .put("sourceIn", srcIn).put("speed", speed)

    private fun track(id: Int, name: String, vararg clips: JSONObject) = JSONObject()
        .put("id", id).put("name", name).put("enabled", true).put("locked", false).put("muted", false).put("solo", false)
        .put("pan", 0.0).put("volumeDb", 0.0).put("transitions", JSONArray()).put("clips", JSONArray(clips.toList()))

    /**
     * V1: one long clip trimmed 1 s in. V2: a clip at 2× speed from 1.52 s. V3: two short clips starting between
     * whole seconds (2.24 s, 4.04 s – frame times that are not exact in binary).
     */
    private fun snapshot(): Snapshot {
        val media = JSONArray((1..4).map { i ->
            JSONObject().put("id", i).put("name", "c$i.mp4").put("path", "/m/$i.mp4").put("duration", 30.0).put("fps", fps)
                .put("width", 1920).put("height", 1080).put("hasVideo", true).put("hasAudio", false).put("codec", "H.264")
        })
        val seq = JSONObject().put("width", 1920).put("height", 1080).put("duration", 6.0)
            .put("video", JSONArray(listOf(
                track(1, "Video 1", clip(11, 1, 0.0, 6.0, 1.0)),
                track(2, "Video 2", clip(21, 2, 1.52, 2.0, 3.0, speed = 2.0)),
                track(3, "Video 3", clip(31, 3, 2.24, 1.0, 0.5), clip(32, 4, 4.04, 0.96, 0.0)),
            )))
            .put("audio", JSONArray()).put("markers", JSONArray()).put("captions", JSONArray()).put("captionTracks", JSONArray())
        return Snapshot.parse(JSONObject().put("fps", fps).put("playhead", 0.0).put("canUndo", false).put("canRedo", false)
            .put("media", media).put("sequence", seq))
    }

    /** The clip on [track] covering frame [n] by the engine's frame grid (start frame ≤ n < end frame). */
    private fun clipAtFrame(s: Snapshot, track: Int, n: Int): ClipSnap? =
        s.sequence!!.video[track].clips.firstOrNull { c -> n >= Math.round(c.start * fps) && n < Math.round((c.start + c.duration) * fps) }

    @Test fun everyLayerShowsTheRightClipAtEveryFrame() {
        val s = snapshot()
        val plan = LayerPlanner.plan(s) { 30.0 }!!
        // the plan's layers top to bottom: V3, V2, V1
        assertEquals(listOf("Video 3", "Video 2", "Video 1"), plan.layers.map { it.name })
        for (n in 0 until (6.0 * fps).toInt()) {
            for ((li, track) in listOf(2, 1, 0).withIndex()) {
                val want = clipAtFrame(s, track, n)
                // the export's frame time, and the preview's: its Float playhead read back as a frame time (playheadSec)
                for (t in listOf(n / fps, playheadSec((n / fps).toFloat(), fps))) {
                    val seg = plan.layers[li].at(t)
                    assertEquals("layer ${plan.layers[li].name}, frame $n (t=$t)", want?.id, seg?.clip?.id)
                }
            }
        }
    }

    @Test fun theFloatPlayheadNeedsTheFrameTime() {
        // the reason for playheadSec: as a Float, 1.52 s lies before a clip starting on frame 38
        val plan = LayerPlanner.plan(snapshot()) { 30.0 }!!
        assertNull(plan.layers[1].at(1.52f.toDouble()))
        assertEquals(21L, plan.layers[1].at(playheadSec(1.52f, fps))?.clip?.id)
    }

    @Test fun theSourceSecondIsTheEnginesForTrimSpeedAndStart() {
        val s = snapshot()
        val plan = LayerPlanner.plan(s) { 30.0 }!!
        for (n in 0 until (6.0 * fps).toInt()) {
            val t = n / fps
            for (layer in plan.layers) {
                val seg = layer.at(t) ?: continue
                val c = seg.clip!!
                assertEquals("${layer.name} at $t", c.sourceAt(t), seg.sourceAt(t)!!, 1e-9)
            }
        }
        // spot checks: V2 at 2.0 s = 3.0 + 0.48 × 2; V1 at 0 = its trim
        assertEquals(3.96, plan.layers[1].at(2.0)!!.sourceAt(2.0)!!, 1e-9)
        assertEquals(1.0, plan.layers[2].at(0.0)!!.sourceAt(0.0)!!, 1e-9)
        // the end is not inside (a stale frame there would show the next second of the file)
        val v3 = plan.layers[0]
        assertNull(v3.segs[0].sourceAt(v3.segs[0].end))
    }

    @Test fun theExportClipsEachSegmentWhereThePreviewSeeksIt() {
        val s = snapshot()
        val built = CompositionBuilder.buildFull(s, { Uri.parse("file:///m/$it.mp4") })!!
        assertTrue("three layers stack: the compositor", built.multi)
        val plan = built.plan!!
        for ((li, layer) in plan.layers.withIndex()) {
            val items = built.composition.sequences[li].editedMediaItems.filter { it.mediaItem.localConfiguration != null }
            for (seg in layer.segs) {
                val m = seg.src as SegSrc.Media
                val item = items.firstOrNull { it.mediaItem.localConfiguration!!.uri.toString().endsWith("/${m.item}.mp4") }
                assertNotNull("${layer.name}: an item for ${seg.clip?.id}", item)
                // the file is entered at the segment's source time – the second the preview's layer seeks to – less the
                // export's deliberate lead of at most a few milliseconds (so the cut's first frame is never lost)
                val lead = Math.round(m.srcIn * 1_000_000) - item!!.mediaItem.clippingConfiguration.startPositionUs
                assertTrue("${layer.name}: clip start lead $lead µs", lead in 0..2_000)
            }
        }
    }

    @Test fun aClipStartingOnAnUpperTrackDoesNotMoveTheBottomPicture() {
        val s = snapshot()
        val plan = LayerPlanner.plan(s) { 30.0 }!!
        val cuts = se.gendut.klipp.engine.CutSeek.cutsOf(plan.layers.last())
        // V1 is one clip: no cut of its own – a paused seek to 1.52 s (where V2 begins) is not moved to the next frame
        assertEquals(1520L, se.gendut.klipp.engine.CutSeek.ms(1.52, cuts, fps, playing = false))
        // (all tracks' starts would have moved it)
        assertTrue(se.gendut.klipp.engine.CutSeek.ms(1.52, se.gendut.klipp.engine.CutSeek.cutsOf(s), fps, playing = false) > 1520L)
    }

    @Test fun aRepaintNeverStepsOntoTheNextFrame() {
        val r = se.gendut.klipp.engine.Repaint
        val p = Any()
        // on a frame's own time (25 fps: 1000 ms): back 1 ms (the same frame), then back up – never forward past it
        assertEquals(999L, r.next(p, 1000L))
        assertEquals(1000L, r.next(p, 999L))
        assertEquals(999L, r.next(p, 1000L))
        // at 0 there is nothing before: 1 ms on and straight back
        val seeks = ArrayList<Long>()
        assertEquals(0L, r.next(Any(), 0L) { seeks += it })
        assertEquals(listOf(1L), seeks)
    }
}
