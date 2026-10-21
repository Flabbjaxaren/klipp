package se.gendut.klipp

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.ClipSnap
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.GpuGrade
import se.gendut.klipp.engine.MediaInfo
import se.gendut.klipp.engine.Proxies
import se.gendut.klipp.engine.SequenceSnap
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.TrackSnap

/** 0.15.3: proxy files, speed on the item, the GPU check and the crash report. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PerfTest {
    private fun media(w: Int, h: Int, fps: Double = 30.0, video: Boolean = true) = MediaInfo(1, "a", null, 10.0, video, true, w, h, fps, null, null)

    @Test fun whichFilesGetProxies() {
        assertTrue("4K", Proxies.needs(media(3840, 2160)))
        assertTrue("portrait 4K", Proxies.needs(media(2160, 3840)))
        assertFalse("1080p30", Proxies.needs(media(1920, 1080)))
        assertTrue("1080p60", Proxies.needs(media(1920, 1080, 60.0)))
        assertFalse("720p60", Proxies.needs(media(1280, 720, 60.0)))
        assertTrue("HDR 1080p", Proxies.needs(media(1920, 1080), hdr = true))
        assertFalse("audio only", Proxies.needs(media(0, 0, video = false)))
        // heavy codecs get a proxy at every resolution
        val P = se.gendut.klipp.engine.Perf
        assertTrue("APV 1080p", Proxies.needs(media(1920, 1080), heavy = P.heavy(se.gendut.klipp.engine.Perf.Codec("video/apv", 200_000_000, true))))
        assertTrue("APV without a known bitrate", P.heavy(se.gendut.klipp.engine.Perf.Codec("video/apv", null, true)))
        assertTrue("software decoding", P.heavy(se.gendut.klipp.engine.Perf.Codec("video/av01", 20_000_000, false)))
        assertTrue("high bitrate", P.heavy(se.gendut.klipp.engine.Perf.Codec("video/hevc", 150_000_000, true)))
        assertFalse("ordinary phone video", P.heavy(se.gendut.klipp.engine.Perf.Codec("video/hevc", 40_000_000, true)))
        assertFalse("unknown", P.heavy(null))
        assertFalse("heavy codec but audio only", Proxies.needs(media(0, 0, video = false), heavy = true))
    }

    @Test fun proxyIsUsedForPictureButNotSound() {
        val c = ClipSnap(5, 1, "a", 0.0, 4.0, 1.0, 2.0, null)
        val a = ClipSnap(6, 1, "a", 0.0, 4.0, 1.0, 2.0, 5)
        val s = Snapshot(0.0, 25.0, false, false, listOf(media(3840, 2160)),
            SequenceSnap(1920, 1080, 4.0, listOf(TrackSnap(1, "V1", false, false, listOf(c))), listOf(TrackSnap(2, "A1", false, false, listOf(a)))))
        val orig = android.net.Uri.parse("content://x/original.mp4")
        val proxy = android.net.Uri.parse("file:///proxies/p.mp4")
        val comp = CompositionBuilder.buildFull(s, { orig }, videoUriOf = { proxy })!!.composition
        val items = comp.sequences.flatMap { it.editedMediaItems }.filter { it.mediaItem.localConfiguration != null }
        val v = items.first { it.removeAudio }
        val au = items.first { it.removeVideo }
        assertEquals(proxy, v.mediaItem.localConfiguration!!.uri)
        assertEquals(orig, au.mediaItem.localConfiguration!!.uri)
        // the same times and the same speed (the proxy has the original's timeline)
        assertEquals(v.mediaItem.clippingConfiguration.startPositionUs, au.mediaItem.clippingConfiguration.startPositionUs)
        assertEquals(2f, v.speedProvider.getSpeed(0), 0f)
        assertEquals(2f, au.speedProvider.getSpeed(0), 0f)
    }

    @Test fun gpuBlackCheck() {
        val black = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        val grey = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(90, 90, 90)) }
        assertTrue(GpuGrade.looksBlack(black))
        assertFalse(GpuGrade.looksBlack(grey))
        // a single bright point is enough to not be black
        val dot = black.copy(Bitmap.Config.ARGB_8888, true).apply { setPixel(10, 5, android.graphics.Color.WHITE) }
        assertFalse(GpuGrade.looksBlack(dot))
    }

    @Test fun crashReportIsSavedAndShownOnce() {
        val ctx = RuntimeEnvironment.getApplication()
        java.io.File(ctx.filesDir, "senaste_krasch.txt").writeText("java.lang.IllegalStateException: test\n\tat se.gendut.klipp.X")
        val r = CrashReport.takePending(ctx)
        assertTrue(r!!.contains("IllegalStateException"))
        assertEquals("shown only once", null, CrashReport.takePending(ctx)?.takeIf { it.contains("IllegalStateException") })
    }
}
