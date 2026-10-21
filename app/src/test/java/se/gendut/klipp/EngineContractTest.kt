package se.gendut.klipp

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.at

/** The Rust → Kotlin contract: a real snapshot from the engine (saved by the Rust test). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineContractTest {
    private fun snap(): Snapshot {
        val json = javaClass.classLoader!!.getResource("snapshot.json")!!.readText()
        return Snapshot.parse(JSONObject(json))
    }

    @Test
    fun parsesEngineSnapshot() {
        val s = snap()
        assertEquals(2, s.media.size)
        assertEquals(1280, s.media[0].width)
        assertEquals(25.0, s.fps, 0.001)
        val q = assertNotNull(s.sequence).let { s.sequence!! }
        assertEquals(3, q.video[0].clips.size)
        assertTrue(q.audio[0].clips.isNotEmpty())
        assertEquals(6.08, q.duration, 0.05)
    }

    /** An aspect ratio different from the sequence: every frame is fitted (black bars), never stretched. */
    @Test
    fun everyVideoItemIsFittedToTheSequence() {
        val s = snap()
        val comp = CompositionBuilder.build(s, uriOf = { Uri.parse("file:///media/$it.mp4") })!!
        val items = comp.sequences[0].editedMediaItems.filter { it.mediaItem.localConfiguration != null }
        assertTrue(items.isNotEmpty())
        for (it in items) {
            val p = it.effects.videoEffects.filterIsInstance<androidx.media3.effect.Presentation>().single()
            // 1080×1920 (portrait) into 1280×720: fitted to the height, not stretched
            val out = p.configure(1080, 1920)
            assertEquals(1280, out.width)
            assertEquals(720, out.height)
        }
    }

    /** Camera files (e.g. APV) can give a source start time just below zero – Media3 then threw without a message. */
    @Test
    fun negativeSourceInStillBuilds() {
        val json = javaClass.classLoader!!.getResource("snapshot.json")!!.readText().replace(Regex("\"sourceIn\"\\s*:\\s*2\\.0"), "\"sourceIn\": -0.021")
        val s = Snapshot.parse(JSONObject(json))
        assertTrue(s.sequence!!.video[0].clips.any { it.sourceIn < 0 })
        val comp = CompositionBuilder.build(s, uriOf = { Uri.parse("file:///media/$it.mp4") })
        assertNotNull(comp)
        for (seq in comp!!.sequences) for (it in seq.editedMediaItems) {
            val c = it.mediaItem.clippingConfiguration
            assertTrue(c.startPositionUs >= 0 && (c.endPositionUs == androidx.media3.common.C.TIME_END_OF_SOURCE || c.endPositionUs > c.startPositionUs))
        }
    }

    /** The engine's read file duration a fraction shorter than the clip's end: Media3 then threw without a message (getClippedDuration). */
    @Test
    fun mediaShorterThanClipEndStillBuilds() {
        val o = JSONObject(javaClass.classLoader!!.getResource("snapshot.json")!!.readText())
        // file 1 "ends" at 6.00 s; the third clip runs to 6.04 s in it
        o.getJSONArray("media").getJSONObject(0).put("duration", 6.0)
        val s = Snapshot.parse(o)
        val comp = CompositionBuilder.build(s, uriOf = { Uri.parse("file:///media/$it.mp4") })!!
        for (seq in comp.sequences) for (it in seq.editedMediaItems) assertTrue(it.presentationDurationUs > 0)
    }

    /** Odd but possible values from the engine must never make playback impossible to build. */
    @Test
    fun oddValuesNeverBreakTheBuild() {
        val base = javaClass.classLoader!!.getResource("snapshot.json")!!.readText()
        val rnd = java.util.Random(7)
        repeat(300) { n ->
            val o = JSONObject(base)
            val media = o.getJSONArray("media")
            for (i in 0 until media.length()) media.getJSONObject(i).put("duration", listOf(0.0, 0.5, 3.0, 6.0, 10.0)[rnd.nextInt(5)])
            val q = o.getJSONObject("sequence")
            for (kind in listOf("video", "audio")) {
                val tracks = q.getJSONArray(kind)
                for (t in 0 until tracks.length()) {
                    val clips = tracks.getJSONObject(t).getJSONArray("clips")
                    for (i in 0 until clips.length()) {
                        val c = clips.getJSONObject(i)
                        if (rnd.nextBoolean()) c.put("sourceIn", c.getDouble("sourceIn") * rnd.nextDouble() - rnd.nextDouble() * 0.05)
                        if (rnd.nextInt(4) == 0) c.put("speed", listOf(0.1, 0.25, 0.5, 2.0, 4.0, 10.0)[rnd.nextInt(6)])
                        if (rnd.nextInt(5) == 0) c.put("duration", listOf(0.001, 0.02, 0.04, 9.0)[rnd.nextInt(4)])
                    }
                }
            }
            val s = Snapshot.parse(o)
            val comp = try {
                CompositionBuilder.build(s, uriOf = { Uri.parse("file:///media/$it.mp4") })
            } catch (e: Exception) {
                throw AssertionError("variant $n: ${e.javaClass.simpleName} ${e.stackTrace.firstOrNull()}", e)
            }
            if (comp != null) for (seq in comp.sequences) for (it in seq.editedMediaItems) assertTrue("variant $n", it.presentationDurationUs > 0)
        }
    }

    @Test
    fun buildsCompositionFromSnapshot() {
        val s = snap()
        val comp = CompositionBuilder.build(s, uriOf = { Uri.parse("file:///media/$it.mp4") })
        assertNotNull(comp)
        // V1 (video) + A1 (audio)
        assertEquals(2, comp!!.sequences.size)
        assertEquals(3, comp.sequences[0].editedMediaItems.size)
        // CompositionPlayer throws if any item lacks a duration – that was the bug in 0.2
        val all = comp.sequences.flatMap { it.editedMediaItems }
        assertTrue(all.all { it.durationUs != androidx.media3.common.C.TIME_UNSET && it.durationUs > 0 })
    }

    @Test
    fun uiMappingPutsV1AndA1OnTheRightLanes() {
        val state = EditorState(emptyList())
        val s = snap()
        val q = s.sequence!!
        val clips = q.video[0].clips.map {
            Clip(it.id, it.name, ClipKind.VIDEO, 2, it.start.toFloat(), it.duration.toFloat(), it.sourceIn.toFloat(), 10f, 0)
        }
        state.applyEngine(clips, s.canUndo, s.canRedo)
        assertEquals(3, state.clips.size)
        assertEquals(1, state.undoCount)
        // new clips should drop in from above
        assertTrue(state.popFrom.values.all { it.second < 0f })
    }

    private fun snap2(): Snapshot =
        Snapshot.parse(JSONObject(javaClass.classLoader!!.getResource("snapshot_marker.json")!!.readText()))

    @Test
    fun parsesMarkersTargetsAndTrackVolumes() {
        val q = snap2().sequence!!
        assertEquals(1, q.markers.size)
        assertEquals("Bra tagning", q.markers[0].name)
        assertEquals(1.0, q.markers[0].time, 0.001)
        assertEquals(q.video[0].id, q.videoTarget)
        assertEquals(q.audio[0].id, q.audioTarget)
        assertEquals(-6.0, q.audio[0].volumeDb, 0.001)
    }

    @Test
    fun colorLookBecomesVideoEffectsAndVolumeBecomesAudioProcessor() {
        val s = snap2()
        val look = se.gendut.klipp.engine.Look(exposure = 0.3f, contrast = 0.2f, temp = 0.5f, sat = -0.4f)
        val comp = CompositionBuilder.build(s, { Uri.parse("file:///m/$it.mp4") }, look)!!
        val v = comp.sequences[0].editedMediaItems.first { it.durationUs > 0 && it.mediaItem.localConfiguration != null }
        // the look sits on the whole composition (once), not on the clips
        assertTrue(v.effects.videoEffects.filterNot { it is androidx.media3.effect.Presentation }.isEmpty())
        // RGB + contrast + saturation in a single pass (LookEffect); without it: Media3's three effects
        assertEquals(1, comp.effects.videoEffects.size)
        assertTrue(comp.effects.videoEffects[0] is se.gendut.klipp.engine.LookEffect)
        val std = CompositionBuilder.build(s, { Uri.parse("file:///m/$it.mp4") }, look.copy(tetra = false))!!
        assertEquals("fallback: RGB + contrast + saturation", 3, std.effects.videoEffects.size)
        val a = comp.sequences[1].editedMediaItems.first { it.mediaItem.localConfiguration != null }
        assertEquals("A1 at −6 dB gets a volume processor", 1, a.effects.audioProcessors.size)
        assertTrue(a.effects.videoEffects.filterNot { it is androidx.media3.effect.Presentation }.isEmpty())
    }

    @Test
    fun neutralLookAddsNoEffects() {
        val comp = CompositionBuilder.build(snap(), { Uri.parse("file:///m/$it.mp4") })!!
        assertTrue(comp.sequences.flatMap { it.editedMediaItems }.all { it.effects.videoEffects.filterNot { it is androidx.media3.effect.Presentation }.isEmpty() && it.effects.audioProcessors.isEmpty() })
    }

    @Test
    fun mutedAndSoloTracksAreRespected() {
        val s = snap()
        val q = s.sequence!!
        val muted = s.copy(sequence = q.copy(audio = q.audio.mapIndexed { i, t -> if (i == 0) t.copy(muted = true) else t }))
        assertEquals("only the video left when A1 is muted", 1, CompositionBuilder.build(muted, { Uri.parse("file:///m/$it.mp4") })!!.sequences.size)
    }

    @Test
    fun speedEffectComesFirstOnVideoAndAudio() {
        val s = snap()
        val q = s.sequence!!
        val fast = s.copy(sequence = q.copy(
            video = q.video.mapIndexed { i, t -> if (i == 0) t.copy(clips = t.clips.mapIndexed { j, c -> if (j == 0) c.copy(speed = 2.0, duration = c.duration / 2) else c }) else t },
            audio = q.audio.mapIndexed { i, t -> if (i == 0) t.copy(volumeDb = -6.0, clips = t.clips.mapIndexed { j, c -> if (j == 0) c.copy(speed = 2.0, duration = c.duration / 2) else c }) else t },
        ))
        val look = se.gendut.klipp.engine.Look(contrast = 0.3f)
        val comp = CompositionBuilder.build(fast, { Uri.parse("file:///m/$it.mp4") }, look)!!
        val v = comp.sequences[0].editedMediaItems.first { it.mediaItem.localConfiguration != null }
        // speed on the item (Media3's supported path), not as an audio-driven effect on the video
        assertTrue("no speed effect among the video effects", v.effects.videoEffects.filterNot { it is androidx.media3.effect.Presentation }.isEmpty())
        assertEquals(2f, v.speedProvider.getSpeed(0), 1e-6f)
        assertTrue("the contrast on the composition", comp.effects.videoEffects.first() is se.gendut.klipp.engine.LookEffect)
        val a = comp.sequences[1].editedMediaItems.first { it.mediaItem.localConfiguration != null }
        assertEquals(2f, a.speedProvider.getSpeed(0), 1e-6f)
        assertEquals("only the volume", 1, a.effects.audioProcessors.size)
        assertTrue(a.effects.audioProcessors[0] is androidx.media3.common.audio.ChannelMixingAudioProcessor)
        // clipping takes source material = duration × speed
        val clip = v.mediaItem.clippingConfiguration
        assertTrue(kotlin.math.abs((q.video[0].clips[0].duration * 1_000_000).toLong() - (clip.endPositionUs - clip.startPositionUs)) < 50)
    }

    // ---------------------------------------------------------------- 0.7: properties, titles, transitions, captions

    private fun snapFx(): Snapshot =
        Snapshot.parse(JSONObject(javaClass.classLoader!!.getResource("snapshot_fx.json")!!.readText()))

    @Test
    fun parsesPropertiesTitlesTransitionsCaptions() {
        val q = snapFx().sequence!!
        val c = q.video[0].clips[0]
        assertEquals(320.0, c.x!!, 1e-6)
        assertEquals(50.0, c.scale, 1e-6)
        assertEquals(15.0, c.rotation, 1e-6)
        assertEquals(60.0, c.opacity, 1e-6)
        assertTrue(c.hasMotion(1280, 720))
        // the engine states the center explicitly – that is not motion (0.7 thought so, and everything went through the compositor)
        assertTrue(!q.video[0].clips[1].hasMotion(1280, 720))
        assertEquals(-9.0, q.audio[0].clips[0].levelDb, 1e-6)
        val t = q.video[1].clips.single()
        assertEquals("Hej igen", t.text!!.text)
        assertEquals(80.0, t.text!!.size, 1e-6)
        assertEquals(124f / 255f, t.text!!.color[2], 0.01f)
        val tr = q.video[0].transitions.single()
        assertEquals("cross_dissolve", tr.effect)
        assertEquals(q.video[0].clips[0].id, tr.from)
        assertEquals(q.video[0].clips[1].id, tr.to)
        assertEquals(2, q.captions.size)
        assertEquals("Andra\nraden", q.captions[1].text)
        assertEquals(1, q.captionTracks.size)
    }

    @Test
    fun layerPlanHasVideoLayersAndTitlesCaptionsAsOverlays() {
        val s = snapFx()
        val q = s.sequence!!
        val plan = se.gendut.klipp.engine.LayerPlanner.plan(s) { 30.0 }!!
        assertEquals(listOf("Video 1 transitions", "Video 1"), plan.layers.map { it.name })
        assertTrue(plan.needsCompositor)
        assertEquals(3, plan.overlays.size)
        val over = plan.layers[0].segs
        assertEquals(2, over.size)
        val from = q.video[0].clips[0]
        val to = q.video[0].clips[1]
        assertEquals(2.6, over[0].start, 1e-6); assertEquals(3.0, over[0].end, 1e-6)
        assertEquals(to.sourceIn - 0.4, (over[0].src as se.gendut.klipp.engine.SegSrc.Media).srcIn, 1e-6)
        assertEquals(from.sourceIn + from.duration, (over[1].src as se.gendut.klipp.engine.SegSrc.Media).srcIn, 1e-6)
        assertEquals(0.25f, over[0].alpha(2.8), 1e-4f)
        assertEquals(0.15f, over[1].alpha(3.2), 1e-4f)
    }

    @Test
    fun compositorAppliesOpacityMotionAndNeverHidesTheBottomLayer() {
        val s = snapFx()
        val built = CompositionBuilder.buildFull(s, { Uri.parse("file:///m/$it.mp4") })!!
        assertTrue(built.multi)
        val comp = built.composition
        assertEquals("transition layer + V1 + A1", 3, comp.sequences.size)
        val cs = comp.videoCompositorSettings as se.gendut.klipp.engine.LayerCompositor
        val v1 = cs.getOverlaySettings(1, 1_000_000)
        assertEquals(0.6f, v1.alphaScale, 1e-4f)
        assertEquals(-0.5f, v1.backgroundFrameAnchor.first, 1e-4f)
        assertEquals(0.5f, v1.backgroundFrameAnchor.second, 1e-4f)
        assertEquals(0.5f, v1.scale.first, 1e-4f)
        assertEquals(-15f, v1.rotationDegrees, 1e-4f)
        assertEquals(0.5f, cs.getOverlaySettings(0, 2_999_999).alphaScale, 1e-3f)
        // outside the transition the transition layer is hidden
        assertEquals(0f, cs.getOverlaySettings(0, 1_000_000).alphaScale, 0f)
        // the 10^12 µs offset is removed
        assertEquals(0.5f, cs.getOverlaySettings(0, 1_000_000_000_000L + 2_999_999).alphaScale, 1e-3f)
        // nonsensical time: the bottom layer still shows (never fully black), upper ones are hidden
        assertEquals(1f, cs.getOverlaySettings(1, 777_000_000_000L).alphaScale, 0f)
        assertEquals(0f, cs.getOverlaySettings(0, 777_000_000_000L).alphaScale, 0f)
        // titles and captions as overlays on the whole composition
        assertTrue(comp.effects.videoEffects.any { it is androidx.media3.effect.OverlayEffect })
        val a = comp.sequences[2].editedMediaItems.first { it.mediaItem.localConfiguration != null }
        assertEquals(1, a.effects.audioProcessors.size)
        val total = (s.sequence!!.duration * 1_000_000).toLong()
        for (i in 0..1) {
            val sum = comp.sequences[i].editedMediaItems.sumOf { e ->
                val cc = e.mediaItem.clippingConfiguration
                if (e.mediaItem.localConfiguration == null) e.durationUs else cc.endPositionUs - cc.startPositionUs
            }
            assertTrue("layer $i: $sum vs $total", kotlin.math.abs(sum - total) < 2000)
        }
    }

    @Test
    fun timeBaseCalibratesAgainstPlayerPosition() {
        val tb = se.gendut.klipp.engine.TimeBase(10.0)
        assertEquals(2.0, tb.seconds(2_000_000)!!, 1e-9)
        assertEquals(null, tb.seconds(555_000_000_000L))
        tb.hintUs = 3_000_000
        assertEquals(3.0, tb.seconds(555_000_000_000L)!!, 1e-9)
        assertEquals(3.5, tb.seconds(555_000_500_000L)!!, 1e-9)
    }

    @Test
    fun singleLayerUsesSimplePathWithMotionOnTheClipAndFadeToBlack() {
        val s = snapFx()
        val q = s.sequence!!
        // only V1, no cross dissolve but a fade-out on the last clip
        val v1 = q.video[0].copy(transitions = listOf(se.gendut.klipp.engine.TransitionSnap(99, "cross_dissolve", 5.2, 0.8, q.video[0].clips[1].id, null)))
        val one = s.copy(sequence = q.copy(video = listOf(v1, q.video[1].copy(clips = emptyList()), q.video[2]), captions = emptyList()))
        val built = CompositionBuilder.buildFull(one, { Uri.parse("file:///m/$it.mp4") })!!
        assertTrue("one video sequence is enough", !built.multi)
        val first = built.composition.sequences[0].editedMediaItems[0]
        assertTrue(first.effects.videoEffects.any { it is se.gendut.klipp.engine.MotionMatrix })
        assertTrue(first.effects.videoEffects.any { it is androidx.media3.effect.RgbAdjustment })
        val fade = built.composition.effects.videoEffects.filterIsInstance<se.gendut.klipp.engine.BlackFade>().single()
        assertEquals(0.5f, fade.getMatrix(5_600_000, false)[0], 1e-3f)
        assertEquals("outside the fade: untouched", 1f, fade.getMatrix(1_000_000, false)[0], 0f)
        assertEquals("nonsensical time: untouched", 1f, fade.getMatrix(444_000_000_000L, false)[0], 0f)
    }

    @Test
    fun motionMatrixMovesTheCentreWhereTheEngineSays() {
        val c = snapFx().sequence!!.video[0].clips[0] // 50 %, (320, 180), 15°
        val m = se.gendut.klipp.engine.MotionMatrix.matrixOf(c, 1280, 720)
        val p = floatArrayOf(0f, 0f, 1f, 0f)
        m.mapPoints(p)
        assertEquals(-0.5f, p[0], 1e-4f)
        assertEquals(0.5f, p[1], 1e-4f)
        // the edge 1 NDC to the right lands half a frame width away in pixels × 50 % → 0.5 NDC from the center (before rotation)
        val q = floatArrayOf(1f, 0f)
        m.mapPoints(q)
        val dxPx = (q[0] - p[0]) * 640f
        val dyPx = (q[1] - p[1]) * 360f
        assertEquals(320.0, kotlin.math.hypot(dxPx.toDouble(), dyPx.toDouble()), 0.5)
        assertTrue("clockwise on screen = downward in NDC", dyPx < 0)
    }

    @Test
    fun singleLayerWithoutPropertiesStaysOnSimplePath() {
        val built = CompositionBuilder.buildFull(snap(), { Uri.parse("file:///m/$it.mp4") })!!
        assertTrue(!built.multi)
        assertTrue(built.composition.sequences[0].editedMediaItems.all { it.effects.videoEffects.filterNot { it is androidx.media3.effect.Presentation }.isEmpty() })
    }

    @Test
    fun forcedSingleKeepsOnlyV1() {
        val built = CompositionBuilder.buildFull(snapFx(), { Uri.parse("file:///m/$it.mp4") }, forceSingle = true)!!
        assertTrue(!built.multi)
        assertEquals("V1 + A1", 2, built.composition.sequences.size)
    }

    @Test
    fun previewUsesSupportedSinglePathAndExportStacksLayers() {
        val s = snapFx()
        val preview = CompositionBuilder.buildFull(s, { Uri.parse("file:///m/$it.mp4") }, overlays = false, forceSingle = true)!!
        assertTrue(!preview.multi)
        assertEquals("V1 + A1", 2, preview.composition.sequences.size)
        assertTrue(preview.composition.effects.videoEffects.none { it is androidx.media3.effect.OverlayEffect })
        val export = CompositionBuilder.buildFull(s, { Uri.parse("file:///m/$it.mp4") })!!
        assertTrue(export.multi)
        assertEquals("klipp:mall=namnskylt;in=studs", s.sequence!!.video[1].clips.single().text!!.name)
    }

    private fun snapKf(): Snapshot =
        Snapshot.parse(JSONObject(javaClass.classLoader!!.getResource("snapshot_kf.json")!!.readText()))

    @Test
    fun keyframesAreParsedAndEvaluatedEverywhere() {
        val s = snapKf()
        val c = s.sequence!!.video[0].clips[0]
        val sc = c.kf["scale"]!!
        assertEquals(2, sc.size)
        assertEquals(1.0, sc[0].t, 1e-6)
        assertEquals("AutoBezier", sc[0].interp)
        // smooth curve: halfway = half the value, a quarter in = less than linear
        assertEquals(75.0, c.at(2.0).scale, 1e-6)
        assertTrue(c.at(1.5).scale > 87.5)
        assertEquals(100.0, c.at(0.5).scale, 1e-6) // before the first keyframe
        assertEquals(50.0, c.at(4.5).scale, 1e-6)  // after the last
        assertEquals(50.0, c.at(3.0).opacity, 2.0)  // linear 100 → 0 over 1–4.96 s
        assertTrue(c.hasMotion(1280, 720))
        // simple path: motion and opacity follow the time on the clip
        val built = CompositionBuilder.buildFull(s, { Uri.parse("file:///m/$it.mp4") }, overlays = false, forceSingle = true)!!
        val fx = built.composition.sequences[0].editedMediaItems.first { it.mediaItem.localConfiguration != null }.effects.videoEffects.filterNot { it is androidx.media3.effect.Presentation }
        val mm = fx.filterIsInstance<se.gendut.klipp.engine.MotionMatrix>().single()
        val p = floatArrayOf(0f, 0f, 1f, 0f)
        mm.getMatrix(2_000_000).mapPoints(p)
        assertEquals("75 % at 2 s", 0.75f, p[2] - p[0], 1e-3f)
        val op = fx.filterIsInstance<se.gendut.klipp.engine.KeyframedOpacity>().single()
        assertEquals(0.5f, op.getMatrix(3_000_000, false)[0], 0.03f)
        // unknown time: the clip's base value (the image never disappears)
        assertTrue(op.getMatrix(999_000_000_000L, false)[0] > 0.99f)
    }
}
