package se.gendut.klipp

import android.media.MediaFormat
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.CameraLog
import se.gendut.klipp.engine.OpenDrt
import se.gendut.klipp.engine.ProRes
import se.gendut.klipp.engine.ProResPlan
import se.gendut.klipp.engine.ProResPlan.AudioPlan
import se.gendut.klipp.engine.ProResPlan.AudioTrack
import se.gendut.klipp.engine.QtBoxes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * ProRes import: finding ProRes (and the camera's log curve) in a file's boxes, the intermediate's size, bitrate and
 * colour, how the camera's audio tracks become one, the cache key, and that only ProRes sources are swapped for their
 * intermediates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProResTest {
    @After fun clear() { ProRes.clearForTest(); CameraLog.clearForTest() }

    // ------------------------------------------------------------------ detection

    @Test fun everyProResFlavourIsProResAndNothingElseIs() {
        for (t in listOf("apco", "apcs", "apcn", "apch", "ap4h", "ap4x", "APCN")) assertTrue(t, ProResPlan.isProRes(t))
        for (t in listOf("avc1", "hvc1", "hev1", "apv1", "aprn", "aprh", "AVdh", "mp4v", "", null)) assertFalse("$t", ProResPlan.isProRes(t))
        assertTrue(ProResPlan.isProResMime("video/prores"))
        assertFalse(ProResPlan.isProResMime("video/hevc"))
    }

    /** A box: 32-bit size, type, body. */
    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = body.fold(ByteArray(0)) { a, b -> a + b }
        return int(8 + content.size) + type.toByteArray(Charsets.ISO_8859_1) + content
    }
    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun full(vararg body: ByteArray) = byteArrayOf(0, 0, 0, 0) + body.fold(ByteArray(0)) { a, b -> a + b }

    private fun track(handler: String, entry: String) = box("trak",
        box("tkhd", ByteArray(84)),
        box("mdia",
            box("mdhd", ByteArray(24)),
            box("hdlr", full(handler.let { "mhlr".toByteArray() }, handler.toByteArray(), ByteArray(12))),
            box("minf", box("stbl",
                box("stsd", full(int(1), box(entry, ByteArray(70)))),
                box("stsz", ByteArray(12)),
            )),
        ),
    )

    private val panasonicXml = """<?xml version="1.0" encoding="UTF-8"?><NonRealTimeMeta><Device manufacturer="Panasonic" modelName="DC-S5M2X"/>""" +
        """<CaptureGamma>V-Log</CaptureGamma> <CaptureGamut>V-Gamut</CaptureGamut><AudioFormat numOfChannel="4"/></NonRealTimeMeta>"""

    /** QuickTime metadata in moov: keys (mdta) and ilst items numbered by key. */
    private fun qtMeta(vararg kv: Pair<String, String>): ByteArray {
        val keys = box("keys", full(int(kv.size), *kv.map { (k, _) -> int(8 + k.length) + "mdta".toByteArray() + k.toByteArray() }.toTypedArray()))
        val items = kv.mapIndexed { i, (_, v) ->
            val value = v.toByteArray(Charsets.UTF_8)
            int(8 + 8 + 8 + value.size) + int(i + 1) + box("data", int(1), int(0), value)
        }
        return box("meta", box("hdlr", full(ByteArray(4), "mdta".toByteArray(), ByteArray(12))), keys, box("ilst", *items.toTypedArray()))
    }

    private fun panasonicFile(): ByteArray =
        box("ftyp", "qt  ".toByteArray(), int(0x200), "qt  ".toByteArray()) +
            box("wide") +
            box("mdat", ByteArray(4096)) +
            box("moov",
                box("mvhd", ByteArray(100)),
                track("vide", "apcn"),
                track("soun", "lpcm"), track("soun", "lpcm"), track("soun", "lpcm"), track("soun", "lpcm"),
                track("tmcd", "tmcd"),
                qtMeta("com.panasonic.SemiPro.metadata.xml" to panasonicXml, "com.apple.quicktime.make" to "Panasonic"),
            )

    @Test fun aPanasonicProResMovIsFoundWithItsTracksAndItsXml() {
        val info = QtBoxes.of(panasonicFile())!!
        assertEquals(listOf("apcn"), info.video)
        assertEquals(List(4) { "lpcm" }, info.audio)
        assertEquals(panasonicXml, info.meta["com.panasonic.SemiPro.metadata.xml"])
        assertEquals("Panasonic", info.meta["com.apple.quicktime.make"])
        assertEquals(OpenDrt.Input.VLOG, CameraLog.detect(info.texts))
    }

    @Test fun anIsoMetaBoxWithAnXmlChildIsRead() {
        val sony = """<NonRealTimeMeta><AcquisitionRecord><Group name="CameraUnitMetadataSet">""" +
            """<Item name="CaptureGammaEquation" value="s-log3-cine"/><Item name="CaptureColorPrimaries" value="S-Gamut3Cine"/></Group></AcquisitionRecord></NonRealTimeMeta>"""
        val file = box("ftyp", "XAVC".toByteArray(), int(0)) + box("moov", track("vide", "avc1"),
            box("meta", full(box("hdlr", full(ByteArray(4), "nrtm".toByteArray(), ByteArray(12))), box("xml ", full(sony.toByteArray())))))
        val info = QtBoxes.of(file)!!
        assertEquals(listOf("avc1"), info.video)
        assertEquals(listOf(sony), info.xml)
        assertEquals(OpenDrt.Input.SLOG3, CameraLog.detect(info.texts))
    }

    @Test fun notAQuickTimeFileIsNull() {
        assertNull(QtBoxes.of("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray()))
        assertNull(QtBoxes.of(ByteArray(3)))
        // a broken box size in the middle ends the walk without an error
        val broken = box("ftyp", "qt  ".toByteArray()) + int(0x7fffffff) + "moov".toByteArray() + ByteArray(20)
        assertEquals(emptyList<String>(), QtBoxes.of(broken)!!.video)
    }

    @Test fun cameraLogIsReadFromPanasonicAndSonyMetadata() {
        assertEquals(OpenDrt.Input.VLOG, CameraLog.detect(listOf("<x><CaptureGamma>V-Log</CaptureGamma></x>")))
        assertEquals(OpenDrt.Input.VLOG, CameraLog.detect(listOf("<x><CaptureGamma> v-log </CaptureGamma><CaptureGamut>V-GAMUT</CaptureGamut></x>")))
        // V-Log with another gamut: not a combination OpenDRT has
        assertNull(CameraLog.detect(listOf("<x><CaptureGamma>V-Log</CaptureGamma><CaptureGamut>BT.709</CaptureGamut></x>")))
        assertNull(CameraLog.detect(listOf("<x><CaptureGamma>HLG</CaptureGamma></x>")))
        assertNull(CameraLog.detect(listOf("<x><CaptureGamma>Standard</CaptureGamma></x>")))
        assertEquals(OpenDrt.Input.SLOG3SG, CameraLog.detect(listOf("""<Item name="CaptureGammaEquation" value="s-log3"/><Item name="CaptureColorPrimaries" value="S-Gamut3"/>""")))
        assertEquals(OpenDrt.Input.SLOG3, CameraLog.detect(listOf("""<Item name="CaptureGammaEquation" value="s-log3-cine"/>""")))
        assertNull(CameraLog.detect(listOf("Panasonic", "no xml here")))
        assertNull(CameraLog.detect(emptyList()))
    }

    @Test fun theDetectedLogFollowsTheIntermediate() {
        val orig = Uri.parse("content://media/external/video/media/42")
        val inter = Uri.fromFile(File("/data/prores/x.mov"))
        CameraLog.remember(orig, OpenDrt.Input.VLOG)
        CameraLog.alias(orig, inter)
        assertEquals(OpenDrt.Input.VLOG, CameraLog.of(inter))
        assertNull(CameraLog.of(Uri.parse("content://other")))
    }

    /** The generated sample (tools/ffmpeg/make_test_media.sh) when its folder is given: KLIPP_PRORES_MEDIA. */
    @Test fun theGeneratedPanasonicStyleSampleIsFound() {
        val dir = System.getenv("KLIPP_PRORES_MEDIA")
        assumeTrue("KLIPP_PRORES_MEDIA not set", dir != null)
        val f = File(dir!!, "p422_dci4k25_4mono_vlog.mov")
        assumeTrue(f.isFile)
        val info = RandomAccessFile(f, "r").use { QtBoxes.of(it.channel) }!!
        assertEquals(listOf("apcn"), info.video)
        assertEquals(4, info.audio.size)
        assertEquals(OpenDrt.Input.VLOG, CameraLog.detect(info.texts))
        val hq = RandomAccessFile(File(dir, "p422hq_uhd2398_2mono.mov"), "r").use { QtBoxes.of(it.channel) }!!
        assertEquals(listOf("apch"), hq.video)
        assertNull(CameraLog.detect(hq.texts))
    }

    // ------------------------------------------------------------------ picture

    @Test fun theSourceSizeIsKeptWhenTheEncoderTakesIt() {
        assertEquals(ProResPlan.Size(4096, 2160), ProResPlan.outputSize(4096, 2160) { _, _ -> true })
        // odd sizes are cropped to even
        assertEquals(ProResPlan.Size(1920, 1080), ProResPlan.outputSize(1921, 1081) { _, _ -> true })
    }

    @Test fun dci4kOnAnEncoderThatStopsAtUhdIsScaledKeepingTheAspect() {
        val s = ProResPlan.outputSize(4096, 2160) { w, h -> w <= 3840 && h <= 2160 }!!
        assertEquals(3840, s.width)
        assertEquals(2024, s.height) // 3840 × 2160 / 4096 = 2025 → even
        assertTrue(s.scaledFrom(4096, 2160))
        assertFalse(ProResPlan.Size(4096, 2160).scaledFrom(4096, 2160))
        assertNull(ProResPlan.outputSize(4096, 2160) { _, _ -> false })
    }

    @Test fun bitrateScalesWithPixelsAndGentlyWithFrameRate() {
        val uhd25 = ProResPlan.bitrate(3840, 2160, 25.0)
        val dci25 = ProResPlan.bitrate(4096, 2160, 25.0)
        assertEquals(180.0, uhd25 / 1e6, 2.0)
        assertEquals(192.0, dci25 / 1e6, 2.0)
        assertEquals(45.0, ProResPlan.bitrate(1920, 1080, 25.0) / 1e6, 1.0)
        // 60 fps: √2 × the 30 fps rate, not 2×
        val r30 = ProResPlan.bitrate(1920, 1080, 30.0).toDouble()
        assertEquals(Math.sqrt(2.0), ProResPlan.bitrate(1920, 1080, 60.0) / r30, 0.01)
        // the encoder's limit and the floor
        assertEquals(100_000_000, ProResPlan.bitrate(4096, 2160, 25.0, max = 100_000_000))
        assertEquals(10_000_000, ProResPlan.bitrate(320, 240, 25.0))
        assertEquals(ProResPlan.bitrate(1920, 1080, 30.0), ProResPlan.bitrate(1920, 1080, 0.0))
        assertEquals(25.0, ProResPlan.fps(25, 1), 0.0)
        assertEquals(23.976, ProResPlan.fps(24000, 1001), 0.001)
        assertEquals(0.0, ProResPlan.fps(0, 0), 0.0)
    }

    @Test fun coloursFollowTheSourceTagsAndUnspecifiedIsBt709Limited() {
        val unspec = ProResPlan.colors(2, 2, 2, 0)
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, unspec.standard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, unspec.transfer)
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, unspec.range)
        assertEquals(listOf(1, 1, 1, 1), listOf(unspec.avPrimaries, unspec.avTransfer, unspec.avMatrix, unspec.avRange))
        // the S5II's V-Log ProRes: tagged BT.709 – kept as BT.709 (the log curve is the grade's, see CameraLog)
        assertEquals(unspec, ProResPlan.colors(1, 1, 1, 1))
        val hlg = ProResPlan.colors(9, 18, 9, 1)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, hlg.standard)
        assertEquals(MediaFormat.COLOR_TRANSFER_HLG, hlg.transfer)
        assertTrue(hlg.hdr)
        assertEquals(listOf(9, 18, 9, 1), listOf(hlg.avPrimaries, hlg.avTransfer, hlg.avMatrix, hlg.avRange))
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, ProResPlan.colors(9, 16, 9, 1).transfer)
        // only the matrix says BT.2020 (primaries unspecified)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, ProResPlan.colors(2, 2, 9, 1).standard)
        val sdr2020 = ProResPlan.colors(9, 14, 9, 2)
        assertFalse(sdr2020.hdr)
        assertEquals(MediaFormat.COLOR_RANGE_FULL, sdr2020.range)
        assertEquals(14, sdr2020.avTransfer)
        assertEquals(MediaFormat.COLOR_STANDARD_BT601_PAL, ProResPlan.colors(5, 6, 5, 1).standard)
    }

    // ------------------------------------------------------------------ audio

    private fun mono(i: Int, codec: String = "pcm_s24be", rate: Int = 48000) = AudioTrack(i, codec, 1, rate, true)

    private fun slot(first: Int, count: Int) = ProResPlan.Slot(first, count)

    @Test fun fourMonoTracksBecomeOneFourChannelTrackInOrder() {
        // the S5II: four mono 24-bit tracks (FFmpeg streams 1–4 after the picture)
        val p = ProResPlan.audioPlan(listOf(mono(1), mono(2), mono(3), mono(4))) as AudioPlan.Keep
        assertEquals(listOf(1, 2, 3, 4), p.streams)
        assertEquals(4, p.channels)
        assertEquals(48000, p.rate)
        // identity: nothing mixed, nothing louder
        for (o in 0..3) for (c in 0..3) assertEquals(if (o == c) 1f else 0f, p.matrix[o * 4 + c], 0f)
        // the engine's stream k is channel k
        assertEquals(listOf(slot(0, 1), slot(1, 1), slot(2, 1), slot(3, 1)), p.slots)
    }

    @Test fun twoMonoTracksAndAStereoTrackKeepEveryChannel() {
        val two = ProResPlan.audioPlan(listOf(mono(1), mono(2))) as AudioPlan.Keep
        assertEquals(2, two.channels)
        assertEquals(listOf(slot(0, 1), slot(1, 1)), two.slots)
        val st = ProResPlan.audioPlan(listOf(AudioTrack(1, "pcm_s16le", 2, 48000, true), mono(2))) as AudioPlan.Keep
        assertEquals(3, st.channels)
        assertEquals(listOf(slot(0, 2), slot(2, 1)), st.slots)
        val m = ProResPlan.audioPlan(listOf(mono(1))) as AudioPlan.Keep
        assertEquals(1, m.channels)
        assertEquals(listOf(1f), m.matrix.toList())
    }

    @Test fun onlyPcmAtTheFirstRateIsKeptAndAacIsCopiedWhenThereIsNoPcm() {
        val p = ProResPlan.audioPlan(listOf(mono(1), mono(2, rate = 44100), AudioTrack(3, "aac", 2, 48000, false), mono(4))) as AudioPlan.Keep
        assertEquals(listOf(1, 4), p.streams)
        // the streams not kept have no slot (their clips play nothing rather than another stream)
        assertEquals(listOf(slot(0, 1), null, null, slot(1, 1)), p.slots)
        assertEquals(AudioPlan.Copy(5, listOf(slot(0, 2))), ProResPlan.audioPlan(listOf(AudioTrack(5, "aac", 2, 48000, false))))
        assertEquals(AudioPlan.None, ProResPlan.audioPlan(listOf(AudioTrack(5, "opus", 2, 48000, false))))
        assertEquals(AudioPlan.None, ProResPlan.audioPlan(emptyList()))
        // a PCM decoder this build lacks counts as none
        assertEquals(AudioPlan.None, ProResPlan.audioPlan(listOf(AudioTrack(1, "pcm_s64le", 1, 48000, false))))
    }

    @Test fun atMostSixteenTracksAndSixtyFourChannelsAreKept() {
        val p = ProResPlan.audioPlan((1..20).map { mono(it) }) as AudioPlan.Keep
        assertEquals(16, p.streams.size)
        assertEquals(null, p.slots[16])
        val wide = ProResPlan.audioPlan((1..5).map { AudioTrack(it, "pcm_s24le", 16, 48000, true) }) as AudioPlan.Keep
        assertEquals(64, wide.channels)
        assertEquals(slot(48, 16), wide.slots[3])
        assertEquals(null, wide.slots[4])
    }

    // ------------------------------------------------------------------ cache and swapping

    @Test fun theCacheKeyChangesWithTheFileItsSizeAndTheVersion() {
        val a = ProResPlan.key("content://x/1", 100, 1)
        assertEquals(a, ProResPlan.key("content://x/1", 100, 1))
        assertNotEquals(a, ProResPlan.key("content://x/2", 100, 1))
        assertNotEquals(a, ProResPlan.key("content://x/1", 101, 1))
        assertNotEquals(a, ProResPlan.key("content://x/1", 100, 2))
        assertEquals(24, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    @Test fun onlyProResSourcesAreSwappedAndNoneBeforeItsIntermediateIsReady() {
        val h264 = Uri.parse("content://media/external/video/media/1")
        val waiting = Uri.parse("content://media/external/video/media/2")
        val done = Uri.parse("content://media/external/video/media/3")
        val file = File("/data/files/prores/abc.mov")
        ProRes.putForTest(h264, null, isProRes = false)
        ProRes.putForTest(waiting, null)
        ProRes.putForTest(done, file)
        assertEquals(h264, ProRes.playable(h264))
        // never probed: untouched
        val unknown = Uri.parse("content://elsewhere/4")
        assertEquals(unknown, ProRes.playable(unknown))
        assertNull(ProRes.playable(waiting))
        assertEquals(Uri.fromFile(file), ProRes.playable(done))
        assertNull(ProRes.statusOf(h264))
        assertEquals(ProRes.Status.Queued, ProRes.statusOf(waiting))
        assertEquals(ProRes.Status.Ready, ProRes.statusOf(done))
        ProRes.progress[waiting.toString()] = 42
        assertEquals(ProRes.Status.Working(42), ProRes.statusOf(waiting))
        ProRes.failures[waiting.toString()] = "the ProRes pictures are damaged"
        assertEquals(ProRes.Status.Failed("the ProRes pictures are damaged"), ProRes.statusOf(waiting))
    }

    @Test fun aRealFileIsProbedOnceAndRemembered() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = File(ctx.cacheDir, "s5.mov").apply { writeBytes(panasonicFile()) }
        val other = File(ctx.cacheDir, "x.mp4").apply {
            val o = ByteArrayOutputStream()
            o.write(box("ftyp", "isom".toByteArray(), int(0)))
            o.write(box("moov", track("vide", "hvc1"), track("soun", "mp4a")))
            writeBytes(o.toByteArray())
        }
        val u = Uri.fromFile(f)
        assertTrue(ProRes.probe(ctx, u))
        assertEquals(OpenDrt.Input.VLOG, CameraLog.of(u))
        assertEquals(true, ProRes.isProRes(u))
        assertFalse(ProRes.probe(ctx, Uri.fromFile(other)))
        assertEquals(Uri.fromFile(other), ProRes.playable(Uri.fromFile(other)))
        assertNull(ProRes.playable(u))
        f.delete(); other.delete()
    }
}
