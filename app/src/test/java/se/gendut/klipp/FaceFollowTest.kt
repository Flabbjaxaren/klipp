package se.gendut.klipp

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
import se.gendut.klipp.engine.FaceFollow
import se.gendut.klipp.engine.WindowTracker
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Face following ([FaceFollow]) is the same algorithm as the prototype tools/face-eval/follow.py: the fixtures
 * (src/test/resources/face/, written by tools/face-eval/evaluate.py --fixtures) hold the faces MediaPipe found in every
 * frame of a clip and what the prototype made of them – where to look, which face, and the smoothed track.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FaceFollowTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("face/$name.json")!!.readText())
    private fun obs(a: JSONArray) = FaceFollow.Obs(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), FloatArray(10) { a.getDouble(4 + it).toFloat() })

    /** Replays a fixture: every request and pick as the prototype's, and the smoothed track. Returns the picks. */
    private fun replay(name: String): List<FaceFollow.Obs?> {
        val fx = fixture(name)
        val want = fx.getJSONArray("want")
        val f = FaceFollow.Follower(fx.getInt("w"), fx.getInt("h"), want.getDouble(0), want.getDouble(1), want.getDouble(2))
        val steps = fx.getJSONArray("steps")
        val picks = ArrayList<FaceFollow.Obs?>()
        val found = ArrayList<FaceFollow.State>()
        val fps = fx.getDouble("fps")
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            val req = f.request()
            val r = s.getJSONArray("req")
            assertEquals("$name frame $i crop x", r.getDouble(0), req.cx, 1e-6)
            assertEquals("$name frame $i crop y", r.getDouble(1), req.cy, 1e-6)
            assertEquals("$name frame $i crop side", r.getDouble(2), req.side, 1e-6)
            assertEquals("$name frame $i wide search", r.length() > 3, req.search != null)
            req.search?.let { for (k in 0 until 3) assertEquals(r.getDouble(3 + k), it[k], 1e-6) }
            val faces = s.getJSONArray("faces").let { a -> (0 until a.length()).map { obs(a.getJSONArray(it)) } }
            val pick = f.step(faces)
            if (s.isNull("pick")) assertNull("$name frame $i", pick)
            else {
                val e = s.getJSONArray("pick")
                assertNotNull("$name frame $i", pick)
                assertEquals("$name frame $i pick", e.getDouble(0), pick!!.cx, 1e-9)
                assertEquals(e.getDouble(1), pick.cy, 1e-9)
                found += FaceFollow.State(i / fps, pick.cx, pick.cy, pick.size, pick.angle)
            }
            picks += pick
        }
        val sm = FaceFollow.smooth(found)
        val exp = fx.getJSONArray("smoothed")
        assertEquals(exp.length(), sm.size)
        for (i in sm.indices) {
            val e = exp.getJSONArray(i)
            assertEquals("$name smoothed $i x", e.getDouble(1), sm[i].cx, 1e-6)
            assertEquals("$name smoothed $i y", e.getDouble(2), sm[i].cy, 1e-6)
            assertEquals("$name smoothed $i size", e.getDouble(3), sm[i].size, 1e-6)
            assertEquals("$name smoothed $i angle", e.getDouble(4), sm[i].angle, 1e-9)
        }
        return picks
    }

    @Test fun stillFaceAsThePrototype() {
        val picks = replay("face_akiyo")
        assertTrue(picks.all { it != null })
    }

    /** Two people; the chosen one is covered for 12 frames (150–161) while the other stays: no frame on the other. */
    @Test fun twoFacesNeverSwitch() {
        val picks = replay("face_two_faces")
        val fx = fixture("face_two_faces")
        val w = fx.getInt("w")
        // the chosen face (foreman) is in the right part of the canvas, the other (akiyo) left of x = 400 px
        for ((i, p) in picks.withIndex()) if (p != null) assertTrue("frame $i at x ${p.cx}", p.cx > 0.42 * w)
        assertTrue("covered frames are not found", (150 until 162).all { picks[it] == null })
        // found again in the first frame after it, until he leaves the picture (frame 186)
        assertTrue("found again after the cover", (162 until 186).all { picks[it] != null })
    }

    @Test fun farFaceAsThePrototype() {
        val picks = replay("face_far_30")
        assertTrue(picks.count { it == null } <= 3)
    }

    /** Auto reframe: the window is the whole picture – the face is found by the wide search. */
    @Test fun reframeFindsTheFaceInTheWholePicture() {
        val picks = replay("face_mid_55_reframe")
        assertTrue(picks.count { it != null } >= 55)
    }

    @Test fun observeAsThePrototype() {
        val fx = fixture("face_akiyo")
        val lm = fx.getJSONArray("landmarks")
        val n = lm.length()
        val x = FloatArray(n) { lm.getJSONArray(it).getDouble(0).toFloat() }
        val y = FloatArray(n) { lm.getJSONArray(it).getDouble(1).toFloat() }
        val z = FloatArray(n) { lm.getJSONArray(it).getDouble(2).toFloat() }
        val o = FaceFollow.observe(x, y, z, fx.getInt("w"), fx.getInt("h"))
        val e = fx.getJSONArray("observed")
        assertEquals(e.getDouble(0), o.cx, 1e-3)
        assertEquals(e.getDouble(1), o.cy, 1e-3)
        assertEquals(e.getDouble(2), o.size, 1e-3)
        assertEquals(e.getDouble(3), o.angle, 1e-5)
        for (k in 0 until 10) assertEquals(e.getDouble(4 + k), o.pts[k].toDouble(), 1e-6)
    }

    /** One Euro, forwards and backwards: no lag on a moving face, and the noise of a still one is damped. */
    @Test fun smoothingHasNoLagAndDampsNoise() {
        val fps = 30.0
        // a head moving across at 0.5 face sizes per frame: the smoothed path is not behind
        val moving = (0 until 90).map { FaceFollow.State(it / fps, 100.0 + it * 20.0, 300.0, 40.0, 0.0) }
        val sm = FaceFollow.smooth(moving)
        for (i in 10 until 80) assertEquals(moving[i].cx, sm[i].cx, 1.0)
        // a still face with ±1 px of alternating noise: much calmer
        val still = (0 until 90).map { FaceFollow.State(it / fps, 300.0 + if (it % 2 == 0) 1.0 else -1.0, 300.0, 40.0, 0.02 * sin(it * PI / 2)) }
        val ss = FaceFollow.smooth(still)
        for (i in 10 until 80) assertTrue("frame $i: ${ss[i].cx}", abs(ss[i].cx - 300.0) < 0.3)
    }

    /** The track is relative to the face at the reference time: the window starts exactly where it was placed. */
    @Test fun samplesAreRelativeToTheReference() {
        val steps = (0 until 60).map { i -> i / 30.0 to FaceFollow.Obs(200.0 + i * 2, 100.0, 50.0 + i * 0.1, 0.0, FloatArray(10)) }
        val s = WindowTracker.faceSamples(steps, 1.0, 1000, 500)
        val at = s.minBy { abs(it.t - 1.0) }
        assertEquals(0f, at.dx, 1e-6f); assertEquals(0f, at.dy, 1e-6f); assertEquals(1f, at.s, 1e-6f)
        assertTrue(s.first().dx < -0.05f && s.last().dx > 0.05f)
        // frames without the face are left out
        val gaps = steps.mapIndexed { i, p -> if (i in 20..25) p.first to null else p }
        assertEquals(54, WindowTracker.faceSamples(gaps, 1.0, 1000, 500).size)
    }

    /** A crop is read upright from a frame stored sideways: display pixel (X, Y) of a 90° frame is buffer (Y, bh-1-X). */
    @Test fun cropsAreUpright() {
        val bw = 6; val bh = 4
        val buf = java.nio.ByteBuffer.allocateDirect(bw * bh).also { b -> for (v in 0 until bh) for (u in 0 until bw) b.put(v * bw + u, (v * 10 + u).toByte()) }
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val dw = if (rot % 180 == 0) bw else bh; val dh = if (rot % 180 == 0) bh else bw
            val pic = se.gendut.klipp.engine.FacePicture(buf, 0, bw, 1, bw, bh, rot, dw, dh)
            val out = IntArray(1)
            for (y in 0 until dh) for (x in 0 until dw) {
                pic.crop(x + 0.5, y + 0.5, 1.0, 1, out)
                val (u, v) = when (rot) { 90 -> y to bh - 1 - x; 180 -> bw - 1 - x to bh - 1 - y; 270 -> bw - 1 - y to x; else -> x to y }
                assertEquals("rot $rot ($x, $y)", v * 10 + u, out[0] and 255)
            }
        }
        // outside the frame: mid grey
        val out = IntArray(4)
        se.gendut.klipp.engine.FacePicture(buf, 0, bw, 1, bw, bh, 0, bw, bh).crop(-10.0, -10.0, 2.0, 2, out)
        assertTrue(out.all { it and 255 == 128 })
    }

    /** The bundled model is the pinned file (LICENSES/third-party/mediapipe/SOURCE.txt). */
    @Test fun bundledModelIsPinned() {
        val f = File("src/main/assets/face_landmarker.task").takeIf { it.isFile } ?: File("app/src/main/assets/face_landmarker.task")
        val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff", sha)
    }
}
