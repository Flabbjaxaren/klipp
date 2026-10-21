package se.gendut.klipp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.AfxSnap
import se.gendut.klipp.engine.AudioFxCatalog
import se.gendut.klipp.engine.AudioPiece
import se.gendut.klipp.engine.CompositionBuilder
import se.gendut.klipp.engine.FxNative
import se.gendut.klipp.engine.FxProcessor
import se.gendut.klipp.engine.FxRegistry
import se.gendut.klipp.engine.GainRampProcessor
import se.gendut.klipp.engine.Meters
import se.gendut.klipp.engine.PeakAccumulator
import se.gendut.klipp.engine.Snapshot
import se.gendut.klipp.engine.TrackSnap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A fake DSP: delays by [lat] samples per channel and multiplies by [gain]. */
class FakeFx(private val lat: Int = 0) : FxNative {
    val log = mutableListOf<String>()
    var next = 1L
    val gain = HashMap<Long, Double>()
    private val delay = HashMap<Long, ArrayDeque<Float>>()
    override fun create(json: String): Long { val id = next++; log += "new $id $json"; gain[id] = 1.0; return id }
    override fun free(fx: Long) { log += "free $fx" }
    override fun set(fx: Long, index: Int, param: String, value: Double): Boolean { log += "set $fx $index $param $value"; if (param == "gain") gain[fx] = value; return true }
    override fun latency(fx: Long, rate: Int) = lat
    override fun reset(fx: Long) { delay.remove(fx); log += "reset $fx" }
    override fun process(fx: Long, data: FloatArray, len: Int, channels: Int, rate: Int) {
        val d = delay.getOrPut(fx) { ArrayDeque<Float>().apply { repeat(lat * channels) { add(0f) } } }
        val g = (gain[fx] ?: 1.0).toFloat()
        for (i in 0 until len) { d.addLast(data[i]); data[i] = d.removeFirst() * g }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class AudioRoundTest {
    @get:Rule val rule = createComposeRule()
    private val dir = "build/shots"

    private fun pcm(vararg v: Short): ByteBuffer = ByteBuffer.allocateDirect(v.size * 2).order(ByteOrder.nativeOrder()).apply { v.forEach { putShort(it) }; flip() }
    private fun shorts(b: ByteBuffer): List<Short> { val o = b.order(ByteOrder.nativeOrder()); val l = ArrayList<Short>(); while (o.remaining() >= 2) l += o.short; return l }

    @Test fun trackAutomationInterpolatesInDb() {
        val t = TrackSnap(1, "A1", false, false, emptyList(), volumeDb = -3.0, volKf = listOf(2.0 to -6.0, 4.0 to 0.0))
        assertTrue(t.hasAutomation)
        assertEquals(-6.0, t.volumeAt(0.0), 1e-9)
        assertEquals(-3.0, t.volumeAt(3.0), 1e-9)
        assertEquals(0.0, t.volumeAt(10.0), 1e-9)
        val off = t.copy(autoMode = "Off")
        assertEquals("off: the fader applies", -3.0, off.volumeAt(3.0), 1e-9)
        assertEquals(-3.0, t.copy(volKf = emptyList()).volumeAt(3.0), 1e-9)
    }

    @Test fun gainProcessorFollowsAutomationAndMeters() {
        Meters.clear()
        val piece = AudioPiece(2.0, 2.0, 1, 0.0, 1.0, 0.0)
        val t = TrackSnap(1, "A1", false, false, emptyList(), volKf = listOf(2.0 to -6.0, 4.0 to 0.0))
        val p = GainRampProcessor(piece, 1f, clip = true, track = t, master = 1f, meter = 0)
        assertEquals(CompositionBuilder.dbToGain(-6.0), p.gainAt(0.0), 1e-4f)
        assertEquals(CompositionBuilder.dbToGain(-3.0), p.gainAt(1.0), 1e-4f)
        p.configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        p.queueInput(pcm(*ShortArray(100) { 16384 }))
        val out = shorts(p.output)
        assertEquals(16384 * CompositionBuilder.dbToGain(-6.0), out[0].toFloat(), 40f)
        val (pk, rms) = Meters.read(0)
        assertTrue("the meter got a level", pk > 0.2f && rms > 0.2f)
        assertEquals(0f, Meters.read(1).first, 0f)
    }

    @Test fun fxProcessorCompensatesLatencyAndKeepsLength() {
        val fx = FakeFx(lat = 3)
        val p = FxProcessor(7, fx)
        p.configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ShortArray(10) { ((it + 1) * 1000).toShort() }
        p.queueInput(pcm(*input.copyOfRange(0, 6)))
        val a = shorts(p.output)
        p.queueInput(pcm(*input.copyOfRange(6, 10)))
        val b = shorts(p.output)
        p.queueEndOfStream()
        val c = shorts(p.output)
        val all = a + b + c
        assertEquals("same length as the input", 10, all.size)
        // in sync: first output sample = first input sample (the delay is gone)
        for (i in 0 until 10) assertEquals("sample $i", input[i].toDouble(), all[i].toDouble(), 1.0)
        // after a seek the compensation starts over
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertTrue(fx.log.any { it.startsWith("reset 7") })
        // stereo with floats
        val fx2 = FakeFx(lat = 2)
        val q = FxProcessor(8, fx2)
        q.configure(AudioProcessor.AudioFormat(1000, 2, C.ENCODING_PCM_FLOAT))
        q.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val fb = ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.nativeOrder()).apply { for (i in 0 until 8) putFloat(i.toFloat()); flip() }
        q.queueInput(fb)
        val o = ArrayList<Float>()
        fun drain() { val ob = q.output.order(ByteOrder.nativeOrder()); while (ob.remaining() >= 4) o += ob.float }
        drain()
        q.queueEndOfStream()
        drain()
        assertEquals(8, o.size)
        for (i in 0 until 8) assertEquals(i.toFloat(), o[i], 1e-6f)
        // chain 0 = no effects: the processor is off
        assertEquals(false, FxProcessor(0, fx).apply { configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT)) }.isActive)
    }

    @Test fun registryLiveSyncAndRelease() {
        val fx = FakeFx()
        val r = FxRegistry(fx)
        val afx = listOf(AfxSnap(3, "denoise", true, mapOf("amount" to 40.0)))
        val a = r.create(10, afx)
        val b = r.create(10, afx) // cross dissolve piece of the same clip
        assertEquals(0L, r.create(11, emptyList()))
        assertEquals("a disabled effect gives no chain", 0L, r.create(12, listOf(afx[0].copy(on = false))))
        r.live(10, 3, "amount", 80.0)
        assertEquals(2, fx.log.count { it.startsWith("set") && it.endsWith("amount 80.0") })
        val clip = se.gendut.klipp.engine.ClipSnap(10, 1, "x", 0.0, 1.0, 0.0, 1.0, null, afx = listOf(afx[0].copy(params = mapOf("amount" to 55.0))))
        r.sync(listOf(clip))
        assertEquals(2, fx.log.count { it.endsWith("amount 55.0") })
        val all = r.takeAll()
        assertEquals(setOf(a, b), all.toSet())
        r.free(all)
        assertEquals(2, fx.log.count { it.startsWith("free") })
        r.live(10, 3, "amount", 1.0) // no chains left: nothing happens
        assertEquals(2, fx.log.count { it.startsWith("set") && it.endsWith("amount 80.0") })
    }

    @Test fun snapshotParsesEffectsAndAutomation_andCompositionUsesThem() {
        val json = JSONObject("""{"playhead":0,"fps":25,"canUndo":true,"canRedo":false,"media":[{"id":1,"name":"a","duration":20,"hasVideo":false,"hasAudio":true}],
          "sequence":{"width":1920,"height":1080,"duration":6,"video":[],"audio":[
            {"id":5,"name":"A1","locked":false,"muted":false,"volumeDb":0,"syncLock":true,"autoMode":"Read","volKf":[[2,-6],[4,0]],
             "clips":[{"id":12,"item":1,"name":"a","start":0,"duration":6,"sourceIn":0,"speed":1,"link":null,"levelDb":0,
               "afx":[{"i":3,"id":"denoise","on":true,"p":{"amount":40}},{"i":4,"id":"parametric_eq","on":false,"p":{"low_gain":6,"hp_on":0}}]}]}]}}""")
        val s = Snapshot.parse(json)
        val t = s.sequence!!.audio[0]
        assertEquals(listOf(2.0 to -6.0, 4.0 to 0.0), t.volKf)
        val c = t.clips[0]
        assertEquals(2, c.afx.size)
        assertEquals(40.0, c.afx[0].params["amount"]!!, 0.0)
        assertEquals(false, c.afx[1].on)
        val fx = FakeFx()
        val reg = FxRegistry(fx)
        val comp = CompositionBuilder.buildFull(s, { android.net.Uri.parse("file:///m/$it.mp4") }, fx = reg, meters = true)!!.composition
        val procs = comp.sequences.flatMap { it.editedMediaItems }.flatMap { it.effects.audioProcessors }
        assertTrue("the effect chain is in the audio", procs.any { it is FxProcessor })
        assertTrue("the track automation and the meter", procs.count { it is GainRampProcessor } >= 2)
        assertTrue("the chain got only the enabled effect", fx.log.first().contains("denoise"))
        // without a registry (export in tests without the engine) it still builds – without effects
        val plain = CompositionBuilder.buildFull(s, { android.net.Uri.parse("file:///m/$it.mp4") })!!.composition
        assertTrue(plain.sequences.flatMap { it.editedMediaItems }.flatMap { it.effects.audioProcessors }.none { it is FxProcessor })
    }

    @Test fun normalizationRespectsTruePeak() {
        assertEquals("straight to the target", 5.9, normalizeDb(-19.9, -11.9, 0.0, -14.0), 1e-9)
        assertEquals("with the master fader", 3.9, normalizeDb(-19.9, -11.9, 2.0, -14.0), 1e-9)
        assertEquals("the peak limits", 1.0, normalizeDb(-20.0, -2.0, 0.0, -14.0), 1e-9)
        assertEquals("lowers a mix that is too loud", -6.0, normalizeDb(-8.0, -0.5, 0.0, -14.0), 1e-9)
        // the master's dB matches the gain it applies
        val m = AudioMix()
        kotlinx.coroutines.runBlocking { m.gains[3].snapTo(0.9f) }
        assertEquals(20.0 * kotlin.math.log10(mixMaster(m).toDouble()), m.db(3), 1e-4)
        m.normDb = 4f
        assertEquals(m.db(3) + 4.0, 20.0 * kotlin.math.log10(mixMaster(m).toDouble()), 1e-4)
        // saved and read back (does not restart playback)
        val o = gradeToJson(ColorGrade(), m)
        assertEquals(mixMaster(m), masterFromJson(o), 1e-6f)
    }

    @Test fun peaksFromPcm() {
        val acc = PeakAccumulator(1000, 2, 1_000_000)
        // 1 s stereo: first half silent, second half at half level on the right channel
        val s = ShortArray(2000) { i -> if (i % 2 == 1 && i >= 1000) 16384 else 0 }
        acc.addShort(0, s)
        val p = acc.result()
        assertEquals(se.gendut.klipp.engine.Peaks.RATE, p.size)
        assertEquals(0f, p[10], 1e-6f)
        assertEquals(0.5f, p[80], 1e-3f)
    }

    @Test fun catalogMatchesEngineParams() {
        // the parameters Klipp shows exist in the engine's effects (ids from filmcraft-project)
        val known = mapOf(
            "denoise" to setOf("amount"), "speech_enhance" to setOf("mix", "tone"),
            "parametric_eq" to (setOf("master_gain", "hp_on", "hp_freq", "hp_slope", "lp_on", "lp_freq", "lp_slope") +
                listOf("low", "b1", "b2", "mid", "b4", "b5", "high").flatMap { b -> listOf("${b}_on", "${b}_freq", "${b}_gain", "${b}_q") }),
            "dynamics_rack" to setOf("comp_on", "comp_threshold", "comp_ratio", "comp_attack", "comp_release", "comp_makeup", "lim_on", "lim_threshold",
                "gate_on", "gate_threshold", "exp_on", "exp_threshold", "exp_ratio", "output"),
            "hard_limiter" to setOf("max", "boost"),
        )
        for (fx in AudioFxCatalog.all) {
            val k = known[fx.id] ?: error("unknown effect ${fx.id}")
            for (p in fx.params) assertTrue("${fx.id}.${p.id}", p.id in k)
        }
        assertTrue(AudioFxCatalog.isToggle("parametric_eq", "hp_on"))
        // the EQ graph's bands and the compressor graph's parameters exist in the engine
        for (b in se.gendut.klipp.engine.FxCurves.BANDS) for (id in listOf(b.on, b.f, b.g, b.qId)) assertTrue(id, id in known["parametric_eq"]!!)
        assertTrue(AudioFxCatalog.isToggle("parametric_eq", "b4_on"))
        assertTrue(AudioFxCatalog.isChoice("hp_slope"))
    }

    @Test fun propertiesAudioEffectsLiveAndCommit() {
        val be = FakeProps().apply { p = p.copy(isAudio = true, name = "A003.wav") }
        val st = EditorState().apply { props = be; selected = 1L; inspecting = true }
        val live = mutableListOf<String>()
        st.onFxLive = { c, i, prm, v -> live += "$c $i $prm" }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { PropertiesPanel(st) {} } } }
        rule.waitForIdle()
        rule.onNodeWithTag("addFx:denoise").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(be.log.contains("addfx denoise"))
        rule.onNodeWithTag("fx:denoise").performScrollTo()
        rule.onNodeWithTag("prop:fx_denoise_amount").performScrollTo().performTouchInput {
            down(center)
            for (i in 1..8) moveTo(Offset(center.x + width * 0.04f * i, center.y))
        }
        rule.waitForIdle()
        assertTrue("audible while dragging", live.any { it.endsWith("amount") })
        rule.onNodeWithTag("prop:fx_denoise_amount").performTouchInput { up() }
        rule.waitForIdle()
        assertTrue("written to the engine when the finger lifts", be.log.any { it.startsWith("fxparam 3 amount") })
        rule.onNodeWithTag("fxOn:denoise").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(be.log.contains("togglefx 3"))
        rule.onRoot().captureRoboImage("$dir/40_ljudeffekter.png")
        rule.onNodeWithTag("fxDel:denoise").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(be.p.afx.isEmpty())
    }

    @Test fun mixerAutomationNormalizeVoiceOver() {
        val st = EditorState().apply { playhead = 3f }
        val mix = AudioMix()
        var tracks = listOf(TrackSnap(1, "A1", false, false, emptyList()), TrackSnap(2, "A2", false, false, emptyList()), TrackSnap(3, "A3", false, false, emptyList()))
        val log = mutableListOf<String>()
        val vo = VoiceOverUi().apply { onStart = { log += "vo start" } }
        val tools = AudioTools(
            tracks = { tracks }, toggleKf = { i, at, db -> log += "kf $i $at ${"%.1f".format(java.util.Locale.ROOT, db)}" }, clearKf = { log += "clear $it" },
            measure = { Result.success(Triple(-19.9, -11.9, 4.0)) }, voiceOver = vo,
        )
        var commits = 0
        mix.onCommit = { if (it == 3) commits++ }
        rule.setContent { KlippTheme { Box(Modifier.fillMaxSize().background(K.Bg)) { AudioPanel(mix, st, tools) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("kf:A2").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(log.toString(), log.contains("kf 1 3.0 0.0"))
        rule.onNodeWithTag("normalize").performScrollTo().performClick()
        rule.waitUntil(3000) { commits > 0 }
        assertEquals(5.9f, mix.normDb, 0.01f)
        assertEquals(1, commits)
        rule.onNodeWithTag("voStart").performScrollTo().performClick()
        assertTrue(log.contains("vo start"))
        rule.onRoot().captureRoboImage("$dir/41_mixer.png")
        assertNotEquals(0, log.size)
    }
}
