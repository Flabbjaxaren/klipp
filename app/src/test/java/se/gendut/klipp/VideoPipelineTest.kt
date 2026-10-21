package se.gendut.klipp

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.ExportSettings
import se.gendut.klipp.engine.Film
import se.gendut.klipp.engine.GradeNode
import se.gendut.klipp.engine.NodeCache
import se.gendut.klipp.engine.Proxies
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.VideoPipeline
import se.gendut.klipp.engine.Wheel

/** The video pipeline: the bit depth from the file's format, the export bit depth, the proxy signature and the node cache keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoPipelineTest {
    private fun fmt(mime: String, profile: Int? = null, transfer: Int? = null) = MediaFormat().apply {
        setString(MediaFormat.KEY_MIME, mime)
        profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
        transfer?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
    }

    @Test fun recognisesTenBitMaterialAlsoInSdr() {
        val P = MediaCodecInfo.CodecProfileLevel::class.java
        fun c(n: String) = P.getField(n).getInt(null)
        assertEquals(10, VideoPipeline.depthOf(fmt("video/hevc", c("HEVCProfileMain10"))))
        assertEquals(8, VideoPipeline.depthOf(fmt("video/hevc", c("HEVCProfileMain"))))
        assertEquals(10, VideoPipeline.depthOf(fmt("video/avc", c("AVCProfileHigh10"))))
        assertEquals(10, VideoPipeline.depthOf(fmt("video/avc", c("AVCProfileHigh422"))))
        assertEquals(8, VideoPipeline.depthOf(fmt("video/avc", c("AVCProfileHigh"))))
        assertEquals(10, VideoPipeline.depthOf(fmt("video/x-vnd.on2.vp9", c("VP9Profile2"))))
        assertEquals(10, VideoPipeline.depthOf(fmt("video/av01", c("AV1ProfileMain10"))))
        assertEquals(10, VideoPipeline.depthOf(fmt("video/apv")))
        // HDR is always 10 bits, even without a profile
        assertEquals(10, VideoPipeline.depthOf(fmt("video/hevc", transfer = MediaFormat.COLOR_TRANSFER_HLG)))
        assertEquals(8, VideoPipeline.depthOf(fmt("video/avc")))
    }

    @Test fun exportBitDepthIsSaved() {
        for (b in listOf(0, 8, 10)) assertEquals(b, ExportSettings.fromJson(ExportSettings(bitDepth = b).toJson()).bitDepth)
        // unknown value → Auto; older settings without the field → Auto
        assertEquals(0, ExportSettings.fromJson(JSONObject().put("bits", 12)).bitDepth)
        assertEquals(0, ExportSettings.fromJson(JSONObject()).bitDepth)
    }

    @Test fun hdrToneMappingKeepsTheFloatPathFirst() {
        val gl = androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
        assertEquals(androidx.media3.transformer.Composition.HDR_MODE_KEEP_HDR, VideoPipeline.toneMapMode(false, false))
        assertEquals(gl, VideoPipeline.toneMapMode(true, false))
        // the fallback is the other path (the decoder on Android 13+)
        assertEquals(androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC, VideoPipeline.toneMapMode(true, true))
    }

    @Test fun proxyBitrateFollowsSizeQualityAndCodec() {
        assertEquals(12_000_000, Proxies.bitrate(1080, 1, false))
        assertTrue(Proxies.bitrate(2160, 1, false) > 3 * Proxies.bitrate(1080, 1, false))
        assertTrue(Proxies.bitrate(1080, 3, false) > Proxies.bitrate(1080, 2, false))
        assertTrue(Proxies.bitrate(1080, 1, true) < Proxies.bitrate(1080, 1, false))
        assertEquals("1440-2-10", Proxies.sig(1440, 2, 10))
    }

    private fun snap(): Snapshot = Snapshot.parse(JSONObject(javaClass.classLoader!!.getResource("snapshot.json")!!.readText()))

    @Test fun nodeCacheKeyFollowsTheNodesUpToTheCachePoint() {
        val clip = snap().sequence!!.video[0].clips.first()
        val uri = Uri.parse("file:///media/a.mp4")
        val n1 = GradeNode(1, gain = Wheel(0.2f, 0f, 0.1f), cache = true)
        val n2 = GradeNode(2, sat = 1.3f)
        val g = ClipGrade(listOf(n1, n2))
        val r = NodeCache.request(clip, uri, 60.0, g)
        assertNotNull(r)
        assertEquals(0, r!!.point)
        // the clip's part of the file + 2 s in each direction (within the file)
        assertTrue(r.start <= clip.sourceIn && r.end >= clip.sourceIn + clip.duration)
        // a node after the cache point does not change the key (it is computed live)
        assertEquals(r.key, NodeCache.request(clip, uri, 60.0, ClipGrade(listOf(n1, n2.copy(sat = 0.5f))))!!.key)
        // a node before/at the point does
        assertNotEquals(r.key, NodeCache.request(clip, uri, 60.0, ClipGrade(listOf(n1.copy(gain = Wheel(0.3f, 0f, 0.1f)), n2)))!!.key)
        assertNotEquals(r.key, NodeCache.request(clip, uri, 60.0, ClipGrade(listOf(n1.copy(film = Film(grainSize = 2f)), n2)))!!.key)
        // another file or another part of the file
        assertNotEquals(r.key, NodeCache.request(clip, Uri.parse("file:///media/b.mp4"), 60.0, g)!!.key)
        assertNotEquals(r.key, NodeCache.request(clip.copy(sourceIn = clip.sourceIn + 5), uri, 60.0, g)!!.key)
        // no cached node, or only neutral nodes up to the point: no cache
        assertNull(NodeCache.request(clip, uri, 60.0, ClipGrade(listOf(n1.copy(cache = false), n2))))
        assertNull(NodeCache.request(clip, uri, 60.0, ClipGrade(listOf(GradeNode(1, cache = true), n2))))
        // the cache flag is saved with the project
        assertEquals(n1, GradeNode.fromJson(n1.toJson()))
        // playback after the cache point: only node 2 left
        assertEquals(1, g.effectsAfter(0, null, null).size)
        assertEquals(0, ClipGrade(listOf(n1)).effectsAfter(0, null, null).size)
    }

    @Test fun playbackScalesDownBeforeTheNodesOnlyWhenTheClipIsLarger() {
        val cb = se.gendut.klipp.engine.CompositionBuilder
        // preview size: 4K timeline → 1080p, same shape, even dimensions; smaller timelines are kept
        assertEquals(1920 to 1080, cb.previewSize(3840, 2160, 1080))
        assertEquals(1080 to 1920, cb.previewSize(2160, 3840, 1080))
        assertEquals(1280 to 720, cb.previewSize(3840, 2160, 720))
        assertEquals(1920 to 1080, cb.previewSize(1920, 1080, 1440))
        assertEquals(3840 to 2160, cb.previewSize(3840, 2160, 0))
        assertEquals(1080 to 1350, cb.previewSize(2160, 2700, 1080))
        assertEquals(1080, cb.previewShortSide(2160, 1920, 1080))
        assertEquals(1080, cb.previewShortSide(2160, 1080, 1920)) // portrait timeline: the same short side
        assertNull("already 1080p: no extra pass", cb.previewShortSide(1080, 1920, 1080))
        assertNull("slightly larger: not worth a pass", cb.previewShortSide(1150, 1920, 1080))
        assertNull("unknown size", cb.previewShortSide(null, 1920, 1080))

        val s = snap()
        val first = s.sequence!!.video[0].clips[0]
        val uriOf: (Long) -> Uri? = { Uri.parse("file:///m/4k$it.mp4") }
        se.gendut.klipp.engine.Perf.rememberCodec(uriOf(first.item)!!, se.gendut.klipp.engine.Perf.Codec("video/hevc", null, true, null, 3840, 2160))
        val g = ClipGrade(listOf(GradeNode(1, contrast = 1.3f)))
        fun effectsOf(down: Boolean) = cb.buildFull(s, uriOf, grades = mapOf(first.id to g), forceSingle = true, liveGrades = true, previewShort = if (down) 1080 else 0)!!
            .composition.sequences[0].editedMediaItems.first { it.mediaItem.localConfiguration?.uri == uriOf(first.item) }.effects.videoEffects
        val on = effectsOf(true)
        assertTrue("the downscale first, before the nodes: $on", on[0] is androidx.media3.effect.Presentation && on[1] is se.gendut.klipp.engine.GradeNodeEffect)
        val off = effectsOf(false)
        assertTrue("without the setting (and in the export): the nodes first", off[0] is se.gendut.klipp.engine.GradeNodeEffect)
        assertEquals(on.size, off.size + 1)
        // the export builds without downscaling
        val exp = cb.build(s, uriOf, grades = mapOf(first.id to g))!!.sequences[0].editedMediaItems.first { it.mediaItem.localConfiguration?.uri == uriOf(first.item) }.effects.videoEffects
        assertTrue(exp[0] is se.gendut.klipp.engine.GradeNodeEffect)
    }

    @Test fun aFourKTimelinePlaysAtThePreviewSize() {
        val cb = se.gendut.klipp.engine.CompositionBuilder
        val j = JSONObject(javaClass.classLoader!!.getResource("snapshot.json")!!.readText())
        j.getJSONObject("sequence").put("width", 3840).put("height", 2160)
        val s = Snapshot.parse(j)
        val first = s.sequence!!.video[0].clips[0]
        val uriOf: (Long) -> Uri? = { Uri.parse("file:///m/uhd$it.mp4") }
        se.gendut.klipp.engine.Perf.rememberCodec(uriOf(first.item)!!, se.gendut.klipp.engine.Perf.Codec("video/hevc", null, true, null, 3840, 2160))
        val g = ClipGrade(listOf(GradeNode(1, contrast = 1.3f)))
        fun item(short: Int) = cb.buildFull(s, uriOf, grades = mapOf(first.id to g), forceSingle = true, liveGrades = true, previewShort = short)!!
            .composition.sequences[0].editedMediaItems.first { it.mediaItem.localConfiguration?.uri == uriOf(first.item) }.effects.videoEffects
        val p = item(1080)
        val down = p[0] as androidx.media3.effect.Presentation
        assertEquals("down to 1080 before the nodes", androidx.media3.common.util.Size(1920, 1080), down.configure(3840, 2160))
        assertTrue(p[1] is se.gendut.klipp.engine.GradeNodeEffect)
        val fit = p.last { it is androidx.media3.effect.Presentation } as androidx.media3.effect.Presentation
        assertEquals("and stays at the preview size (no upscale to 4K afterwards)", androidx.media3.common.util.Size(1920, 1080), fit.configure(1920, 1080))
        // full resolution: the nodes first, the image at the timeline's 4K
        val full = item(0)
        assertTrue(full[0] is se.gendut.klipp.engine.GradeNodeEffect)
        assertEquals(androidx.media3.common.util.Size(3840, 2160), (full.last { it is androidx.media3.effect.Presentation } as androidx.media3.effect.Presentation).configure(3840, 2160))
    }
}
