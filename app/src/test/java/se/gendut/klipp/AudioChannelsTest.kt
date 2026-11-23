package se.gendut.klipp

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.AudioChannels
import se.gendut.klipp.engine.AudioChannels.Pick
import se.gendut.klipp.engine.AudioPlanner
import se.gendut.klipp.engine.AudioStreams
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.PeakAccumulator
import se.gendut.klipp.engine.ProRes
import se.gendut.klipp.engine.ProResPlan
import se.gendut.klipp.engine.Snapshot
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A clip of one of a file's audio streams or channels plays that one (a camera's four microphone tracks are four clips
 * on A1–A4): the engine's stream and channels reach the player, which picks them – from a ProRes intermediate's
 * channels, a stream's stand-in, or a multichannel stream – and a plain mono or stereo file plays as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioChannelsTest {
    @After fun clear() { ProRes.clearForTest(); AudioStreams.clearForTest() }

    private fun snapJson() = JSONObject(javaClass.classLoader!!.getResource("snapshot_audio.json")!!.readText())

    // ------------------------------------------------------------------ the engine's fields reach the planner

    @Test fun theClipsStreamAndChannelsReachEveryPieceIncludingTheCrossfade() {
        val o = snapJson()
        val clips = o.getJSONObject("sequence").getJSONArray("audio").getJSONObject(0).getJSONArray("clips")
        for (i in 0 until clips.length()) clips.getJSONObject(i).put("audioStream", 2).put("sourceChannels", JSONArray().put(1))
        val s = Snapshot.parse(o)
        val a1 = s.sequence!!.audio[0]
        assertTrue(a1.clips.all { it.audioStream == 2 && it.sourceChannels == listOf(1) })
        val plan = AudioPlanner.plan(a1) { 30.0 }
        assertTrue(plan.over.isNotEmpty())
        for (p in plan.base + plan.over) {
            assertEquals(2, p.stream)
            assertEquals(listOf(1), p.channels)
            assertEquals(p.item, p.layoutItem)
        }
    }

    @Test fun withoutTheFieldsAClipIsStreamZeroWithAllItsChannels() {
        val s = Snapshot.parse(snapJson())
        assertTrue(s.sequence!!.audio[0].clips.all { it.audioStream == 0 && it.sourceChannels.isEmpty() && it.layoutItem == null })
        val plan = AudioPlanner.plan(s.sequence!!.audio[0]) { 30.0 }
        assertTrue((plan.base + plan.over).all { it.stream == 0 && it.channels.isEmpty() })
    }

    // ------------------------------------------------------------------ which channels

    @Test fun channelChoicePerCase() {
        // a mono stream: its one channel
        assertEquals(listOf(0), AudioChannels.picks(0, 1, emptyList()))
        // a stereo stream: both, and that is today's path
        assertEquals(listOf(0, 1), AudioChannels.picks(0, 2, emptyList()))
        assertTrue(AudioChannels.isDefault(0, emptyList(), 2))
        assertTrue(AudioChannels.isDefault(0, listOf(0, 1), 2))
        assertTrue(AudioChannels.isDefault(0, emptyList(), 1))
        assertTrue(AudioChannels.isDefault(0, emptyList(), null))
        // one side of a stereo stream: not today's path
        assertFalse(AudioChannels.isDefault(0, listOf(1), 2))
        assertEquals(listOf(1), AudioChannels.picks(0, 2, listOf(1)))
        // a four-channel stream split into four clips: each its own channel; the whole of it its first two
        for (k in 0..3) assertEquals(listOf(k), AudioChannels.picks(0, 4, listOf(k)))
        assertFalse(AudioChannels.isDefault(0, emptyList(), 4))
        assertEquals(listOf(0, 1), AudioChannels.picks(0, 4, emptyList()))
        // stream k of four mono streams side by side (a ProRes intermediate): channel k
        for (k in 0..3) assertEquals(listOf(k), AudioChannels.picks(k, 1, emptyList()))
        // a stereo stream after two mono ones; a channel past the stream's end reads its last
        assertEquals(listOf(2, 3), AudioChannels.picks(2, 2, emptyList()))
        assertEquals(listOf(3), AudioChannels.picks(2, 2, listOf(5)))
        // the engine's left/right pair in reverse order is kept as asked
        assertEquals(listOf(4, 3), AudioChannels.picks(3, 2, listOf(1, 0)))
    }

    @Test fun theMatrixTakesThePickedChannels() {
        val mono = AudioChannels.matrix(4, listOf(2))
        assertEquals(4, mono.inputChannelCount)
        assertEquals(1, mono.outputChannelCount)
        for (c in 0..3) assertEquals(if (c == 2) 1f else 0f, mono.getMixingCoefficient(c, 0), 0f)
        val st = AudioChannels.matrix(6, listOf(4, 5))
        assertEquals(2, st.outputChannelCount)
        assertEquals(1f, st.getMixingCoefficient(4, 0), 0f)
        assertEquals(1f, st.getMixingCoefficient(5, 1), 0f)
        assertEquals(0f, st.getMixingCoefficient(0, 0), 0f)
        // fewer channels than expected: the last one instead of a failure
        assertEquals(1f, AudioChannels.matrix(1, listOf(2)).getMixingCoefficient(0, 0), 0f)
    }

    @Test fun theProcessorPlaysOnlyThePickedChannelOfFour() {
        val p = AudioChannels.processor(listOf(2))
        val out = p.configure(AudioProcessor.AudioFormat(48000, 4, C.ENCODING_PCM_16BIT))
        assertEquals(1, out.channelCount)
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(8 * 10).order(ByteOrder.nativeOrder())
        repeat(10) { input.putShort(100).putShort(200).putShort(300).putShort(400) }
        input.flip()
        p.queueInput(input)
        val o = p.output.order(ByteOrder.nativeOrder())
        assertEquals(10 * 2, o.remaining())
        repeat(10) { assertEquals(300.toShort(), o.short) }
    }

    // ------------------------------------------------------------------ which file

    private val orig = Uri.parse("content://media/external/video/media/7")
    private val inter = Uri.fromFile(File("/data/files/prores/abc.mov"))

    @Test fun aProResIntermediateGivesEachStreamItsOwnChannel() {
        ProRes.putForTest(orig, File(inter.path!!), audio = listOf(ProResPlan.Slot(0, 1), ProResPlan.Slot(1, 1), ProResPlan.Slot(2, 1), ProResPlan.Slot(3, 1)))
        for (k in 0..3) assertEquals(Pick(inter, listOf(k)), AudioChannels.resolve(inter, inter, k, emptyList()))
        // a stream the intermediate didn't keep: nothing (rather than another stream's sound)
        assertNull(AudioChannels.resolve(inter, inter, 4, emptyList()))
        // a reversed or rendered file made from the intermediate keeps its channels: picked the same way
        val rendered = Uri.fromFile(File("/data/files/afx/x.wav"))
        assertEquals(Pick(rendered, listOf(1)), AudioChannels.resolve(rendered, inter, 1, emptyList()))
    }

    @Test fun anOrdinaryFilesSecondStreamPlaysFromItsStandIn() {
        val f = Uri.parse("content://media/external/video/media/8")
        // not probed yet: stream 0 as today, stream 1 waits
        assertEquals(Pick(f, emptyList()), AudioChannels.resolve(f, f, 0, emptyList()))
        assertNull(AudioChannels.resolve(f, f, 1, emptyList()))
        val wav = File("/cache/streams/s1.wav")
        AudioStreams.putForTest(f, listOf(2, 1, 4), mapOf(1 to wav, 2 to File("/cache/streams/s2.wav")))
        // stream 0 (stereo, all of it): today's path, nothing picked
        assertEquals(Pick(f, emptyList()), AudioChannels.resolve(f, f, 0, emptyList()))
        // stream 1 (mono): its stand-in as it is
        assertEquals(Pick(Uri.fromFile(wav), emptyList()), AudioChannels.resolve(f, f, 1, emptyList()))
        // stream 2 (four channels) split per channel: its stand-in, channel k
        assertEquals(Pick(Uri.fromFile(File("/cache/streams/s2.wav")), listOf(3)), AudioChannels.resolve(f, f, 2, listOf(3)))
        // a multichannel first stream: the channel matrix on the file itself
        val g = Uri.parse("content://media/external/video/media/9")
        AudioStreams.putForTest(g, listOf(6))
        assertEquals(Pick(g, listOf(0, 1)), AudioChannels.resolve(g, g, 0, emptyList()))
        assertEquals(Pick(g, listOf(4)), AudioChannels.resolve(g, g, 0, listOf(4)))
    }

    @Test fun aPlainStereoFileIsBuiltExactlyAsBefore() {
        val s = Snapshot.parse(snapJson())
        val comp = CompositionBuilder.build(s, { Uri.parse("file:///m/$it.mp4") })!!
        val audio = comp.sequences.flatMap { it.editedMediaItems }.filter { it.removeVideo }
        assertTrue(audio.isNotEmpty())
        // only the stereo upmix (last) – no channel picker in front
        assertTrue(audio.all { e -> e.effects.audioProcessors.count { it is ChannelMixingAudioProcessor } == 1 })
        assertTrue(audio.all { e -> e.effects.audioProcessors.last() is ChannelMixingAudioProcessor })
    }

    @Test fun aMicrophoneTrackClipGetsItsChannelPickedFirst() {
        val o = snapJson()
        val clips = o.getJSONObject("sequence").getJSONArray("audio").getJSONObject(0).getJSONArray("clips")
        for (i in 0 until clips.length()) clips.getJSONObject(i).put("audioStream", 1)
        val s = Snapshot.parse(o)
        val file = Uri.fromFile(File("/data/files/prores/m1.mov"))
        ProRes.putForTest(Uri.parse("content://cam/1"), File(file.path!!), audio = listOf(ProResPlan.Slot(0, 1), ProResPlan.Slot(1, 1)))
        val comp = CompositionBuilder.build(s, { if (it == 1L) file else Uri.parse("file:///m/$it.mp4") })!!
        val audio = comp.sequences.flatMap { it.editedMediaItems }.filter { it.removeVideo && it.mediaItem.localConfiguration?.uri == file }
        assertTrue(audio.isNotEmpty())
        for (e in audio) {
            val procs = e.effects.audioProcessors
            // the picker first (channel 1 of 2 → mono), the upmix last
            assertTrue(procs.first() is ChannelMixingAudioProcessor && procs.last() is ChannelMixingAudioProcessor && procs.size >= 2)
            val out = (procs.first() as ChannelMixingAudioProcessor).configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
            assertEquals(1, out.channelCount)
        }
    }

    @Test fun renderedEffectsGetOnlyTheClipsChannels() {
        // (denoise on one of four microphone tracks: the renderer takes mono or stereo – the clip's channel is cut out)
        val raw = File.createTempFile("afx", ".raw")
        val b = ByteBuffer.allocate(4 * 2 * 3).order(ByteOrder.LITTLE_ENDIAN)
        repeat(3) { f -> for (c in 0..3) b.putShort((100 * c + f).toShort()) }
        raw.writeBytes(b.array())
        se.gendut.klipp.engine.AudioRenders.pickChannels(raw, 4, listOf(2))
        val o = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3 * 2, o.remaining())
        repeat(3) { f -> assertEquals((200 + f).toShort(), o.short) }
        raw.delete()
    }

    // ------------------------------------------------------------------ waveforms

    @Test fun theWaveformShowsOnlyTheClipsChannel() {
        val acc = PeakAccumulator(1000, 4, 1_000_000, 100, only = listOf(2))
        acc.addShort(0, ShortArray(40) { i -> if (i % 4 == 2) 16384 else 32767 })
        assertEquals(0.5f, acc.result().first(), 0.01f)
        val all = PeakAccumulator(1000, 4, 1_000_000, 100)
        all.addShort(0, ShortArray(40) { i -> if (i % 4 == 2) 16384 else 32767 })
        assertEquals(1f, all.result().first(), 0.01f)
    }

    // ------------------------------------------------------------------ Media3 reads the intermediate's audio

    /**
     * The intermediate's four-channel 16-bit PCM (FFmpeg writes it as 'lpcm', sound description v2) read by Media3's
     * own MP4 extractor – the reader playback and export use. The file: tools/ffmpeg/bench --mux (KLIPP_PRORES_MEDIA).
     */
    @Test fun media3ReadsTheIntermediatesFourChannelPcm() {
        val dir = System.getenv("KLIPP_PRORES_MEDIA")
        assumeTrue("KLIPP_PRORES_MEDIA not set", dir != null)
        val f = File(dir!!, "inter_4ch_check.mov")
        assumeTrue(f.isFile)
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val ex = androidx.media3.inspector.MediaExtractorCompat(androidx.media3.extractor.DefaultExtractorsFactory(), androidx.media3.datasource.DefaultDataSource.Factory(ctx))
        try {
            ex.setDataSource(Uri.fromFile(f), 0)
            val audio = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.single { it.getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            assertEquals("audio/raw", audio.getString(android.media.MediaFormat.KEY_MIME))
            assertEquals(4, audio.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT))
            assertEquals(48000, audio.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(C.ENCODING_PCM_16BIT, audio.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING))
            // and its samples: 6 s × 48000 × 4 channels × 2 bytes
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) == "audio/raw" }
            ex.selectTrack(track)
            val buf = ByteBuffer.allocate(1 shl 20)
            var bytes = 0L
            while (true) { buf.clear(); val n = ex.readSampleData(buf, 0); if (n < 0) break; bytes += n; ex.advance() }
            assertEquals(6L * 48000 * 4 * 2, bytes)
        } finally { ex.release() }
    }
}
