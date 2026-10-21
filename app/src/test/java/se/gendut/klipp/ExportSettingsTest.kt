package se.gendut.klipp

import android.net.Uri
import androidx.media3.common.MimeTypes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.ExportPresets
import se.gendut.klipp.engine.ExportSettings
import se.gendut.klipp.engine.FrameRateEffect
import se.gendut.klipp.engine.Snapshot

/** Export choices: output format, frame rate, estimate, saving – and that the composition gets them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportSettingsTest {
    @Test fun outputSizeKeepsAspectAndEvenSides() {
        assertEquals(1920 to 1080, ExportSettings().outputSize(1920, 1080))
        assertEquals(1280 to 720, ExportSettings(height = 720).outputSize(1920, 1080))
        assertEquals(3840 to 2160, ExportSettings(height = 2160).outputSize(1920, 1080))
        // portrait: the short side is the width
        assertEquals(720 to 1280, ExportSettings(height = 720).outputSize(1080, 1920))
        // cinemascope 1920×804 at 720: even dimensions
        val (w, h) = ExportSettings(height = 720).outputSize(1920, 804)
        assertEquals(0, w % 2); assertEquals(0, h % 2); assertEquals(720, h)
    }

    @Test fun fpsNeverAboveProject() {
        assertEquals(25.0, ExportSettings().outputFps(25.0), 0.0)
        assertEquals(24.0, ExportSettings(fps = 24.0).outputFps(30.0), 0.0)
        assertEquals(30.0, ExportSettings(fps = 60.0).outputFps(30.0), 0.0)
    }

    @Test fun estimateAndPresets() {
        // 10 Mbit/s video + 192 kbit/s audio for 60 s ≈ 76 MB
        assertEquals(76.4, ExportSettings(bitrateMbps = 10f).estimateMb(1920, 1080, 30.0, 60.0), 0.2)
        assertEquals(75.0, ExportSettings(bitrateMbps = 10f, audio = false).estimateMb(1920, 1080, 30.0, 60.0), 0.01)
        val std = ExportPresets.mbps(ExportPresets.STANDARD, 1920, 1080, 30.0, MimeTypes.VIDEO_H264)
        assertTrue("$std", std in 8.0..16.0)
        assertTrue(ExportPresets.mbps(ExportPresets.STANDARD, 3840, 2160, 30.0, MimeTypes.VIDEO_H264) > std * 2.5)
        assertTrue(ExportPresets.mbps(ExportPresets.STANDARD, 1920, 1080, 30.0, MimeTypes.VIDEO_H265) < std)
        for (l in 0..2) assertTrue(ExportPresets.mbps(l, 1920, 1080, 30.0, MimeTypes.VIDEO_H264) < ExportPresets.mbps(l + 1, 1920, 1080, 30.0, MimeTypes.VIDEO_H264))
    }

    @Test fun jsonRoundTrip() {
        val s = ExportSettings(1440, 24.0, MimeTypes.VIDEO_H265, 35f, true, 2f, false, true, true, 320, 48_000, true)
        assertEquals(s, ExportSettings.fromJson(JSONObject(s.toJson().toString())))
        assertEquals(ExportSettings(), ExportSettings.fromJson(null))
    }

    @Test fun compositionGetsOutputEffects() {
        val snap = Snapshot.parse(JSONObject(javaClass.classLoader!!.getResource("snapshot.json")!!.readText()))
        val uriOf: (Long) -> Uri? = { Uri.parse("file:///media/$it.mp4") }
        val plain = CompositionBuilder.build(snap, uriOf, output = ExportSettings())!!
        // the project's frame rate always applies in the export (clips may have another)
        assertTrue(plain.effects.videoEffects.any { it is FrameRateEffect && kotlin.math.abs(it.fps - snap.fps) < 1e-9 })
        val c = CompositionBuilder.build(snap, uriOf, output = ExportSettings(height = 360, fps = 12.0, mono = true, sampleRate = 44_100))!!
        assertTrue("frame rate", c.effects.videoEffects.any { it is FrameRateEffect && it.fps == 12.0 })
        assertTrue("size", c.effects.videoEffects.last() is androidx.media3.effect.Presentation)
        assertEquals(2, c.effects.audioProcessors.size)
        // without audio: no audio sequences
        val silent = CompositionBuilder.build(snap, uriOf, output = ExportSettings(audio = false))!!
        assertTrue(silent.sequences.size < CompositionBuilder.build(snap, uriOf)!!.sequences.size)
    }

    @Test fun frameRateGridGivesEvenRate() {
        fun frames(fps: Double, sec: Double) = (0 until (fps * sec).toInt()).map { Math.round(it * 1_000_000.0 / fps) }
        // 25 → 24 and 30 → 24: exactly 24 frames per second, evenly spaced
        for (src in listOf(25.0, 30.0, 29.97, 60.0)) {
            val out = FrameRateEffect.keep(frames(src, 10.0), 24.0)
            assertTrue("$src → ${out.size}", out.size in 239..241)
            val steps = out.zipWithNext { a, b -> b - a }.toSet()
            assertTrue("$src: $steps", steps.all { it in 41_666L..41_667L })
        }
        // equal: nothing is dropped
        assertEquals(250, FrameRateEffect.keep(frames(25.0, 10.0), 25.0).size)
        // variable (25 and 30 mixed, like the test clip) → increasing, unique, no gaps under one frame
        val vfr = frames(25.0, 3.0) + frames(30.0, 3.0).map { it + 3_000_000 }
        val out = FrameRateEffect.keep(vfr, 25.0)
        assertTrue(out.zipWithNext().all { (a, b) -> b - a == 40_000L })
        assertEquals(150, out.size)
    }
}
