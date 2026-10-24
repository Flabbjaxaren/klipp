package se.gendut.klipp

import android.graphics.Bitmap
import android.graphics.RectF
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.ClipGrade
import se.gendut.klipp.engine.ClipSnap
import se.gendut.klipp.engine.CornerPin
import se.gendut.klipp.engine.CornerPins
import se.gendut.klipp.engine.PinTrack
import se.gendut.klipp.engine.TitleBounds
import se.gendut.klipp.engine.Window
import se.gendut.klipp.engine.WindowTrack

/** A title pinned by its corners: where the corners go over time, the perspective, the text's own box, saving. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CornerPinTest {
    private val w = 1920; private val h = 1080
    // the clip at 10 s on the timeline, source from 2 s, no motion of its own
    private val clip = ClipSnap(7, 1, "A", 10.0, 4.0, 2.0, 1.0, null)
    private val quad = floatArrayOf(0.2f, 0.3f, 0.6f, 0.25f, 0.65f, 0.6f, 0.15f, 0.55f)

    private fun track(dx: (Int) -> Float, s: (Int) -> Float = { 1f }, r: (Int) -> Float = { 0f }) =
        WindowTrack(2.0, 0.04, FloatArray(100) { dx(it) }, FloatArray(100), FloatArray(100) { s(it) }, FloatArray(100) { 1f }, smooth = 0f, r = FloatArray(100) { r(it) })

    private fun quadAt(pin: CornerPin, t: Double) = CornerPins.quadAt(pin, t, w, h, { if (it == 7L) clip else null }, { ClipGrade() })!!

    @Test fun untrackedPinStays() {
        val q = quadAt(CornerPin(quad), 11.0)
        for (i in 0 until 8) assertEquals(quad[i], q[i], 0f)
    }

    /** Each corner moves with its own window: two corners slide right, two stay – the quad is skewed as the surface is. */
    @Test fun eachCornerFollowsItsWindow() {
        val wins = CornerPins.windows(quad, w, h).mapIndexed { i, win -> win.copy(track = if (i < 2) track({ it * 0.001f }) else track({ 0f })) }
        val pin = CornerPin(quad, PinTrack(7, 10.0, quad, wins))
        // 25 frames later: the top corners 0.025 to the right, the bottom ones where they were
        val q = quadAt(pin, 11.0)
        assertEquals(quad[0] + 0.025f, q[0], 1e-4f); assertEquals(quad[2] + 0.025f, q[2], 1e-4f)
        assertEquals(quad[4], q[4], 1e-4f); assertEquals(quad[6], q[6], 1e-4f)
        for (i in listOf(1, 3, 5, 7)) assertEquals(quad[i], q[i], 1e-4f)
    }

    /** A corner keeps its place relative to its window as the window grows: it moves away from the window's center. */
    @Test fun cornerScalesWithItsWindow() {
        val wins = CornerPins.windows(quad, w, h).map { it.copy(track = track({ 0f }, s = { 1f + it * 0.01f })) }
        val pin = CornerPin(quad, PinTrack(7, 10.0, quad, wins))
        val q = quadAt(pin, 10.0 + 50 * 0.04)
        // since the pin's reference (t = 10 s, frame 0) the window has grown 1.5 times (frame 50)
        val c = wins[0]
        assertEquals(c.cx + (quad[0] - c.cx) * 1.5f, q[0], 1e-4f)
        assertEquals(c.cy + (quad[1] - c.cy) * 1.5f, q[1], 1e-4f)
    }

    /** The perspective: the text box's corners land exactly on the quad's corners. */
    @Test fun perspectiveMapsTheBoxOntoTheQuad() {
        val box = RectF(400f, 300f, 1100f, 500f)
        val m = CornerPins.matrix(box, quad, w, h)
        val pts = floatArrayOf(box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom)
        m.mapPoints(pts)
        for (i in 0 until 8) assertEquals(quad[i] * if (i % 2 == 0) w else h, pts[i], 0.05f)
    }

    /** Pinned large, the text is drawn as many times larger as the quad needs (sharp, not an enlarged small image). */
    @Test fun pinnedTextIsDrawnAtTheQuadsSize() {
        val box = RectF(800f, 500f, 1100f, 560f)
        // a quad three times as wide and high as the box
        val q = floatArrayOf(0.1f, 0.1f, 0.1f + 900f / w, 0.1f, 0.1f + 900f / w, 0.1f + 180f / h, 0.1f, 0.1f + 180f / h)
        assertEquals(3f, se.gendut.klipp.engine.PinArt.magnification(box, q, w, h), 1e-3f)
        val text = se.gendut.klipp.engine.TextSnap("Skylt", 80.0, 960.0, 540.0, listOf(1f, 1f, 1f, 1f), se.gendut.klipp.engine.TitleLook().encode())
        val art = se.gendut.klipp.engine.PinArt.of(text, w, h, q)
        val own = TitleBounds.of(text, w, h)
        val k = CornerPinTest.kOf(own, q, w, h)
        assertEquals(own.width() * k, art.box.width(), 1f)
        assertEquals(own.height() * k, art.box.height(), 1f)
    }

    companion object {
        fun kOf(box: RectF, q: FloatArray, w: Int, h: Int) = (kotlin.math.ceil(se.gendut.klipp.engine.PinArt.magnification(box, q, w, h) * 2f) / 2f).coerceIn(1f, se.gendut.klipp.engine.PinArt.MAX_K)
    }

    @Test fun textBoxIsMeasuredFromTheDrawing() {
        val b = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        b.eraseColor(0)
        for (x in 30 until 120) for (y in 40 until 61) b.setPixel(x, y, 0xFFFFFFFF.toInt())
        val r = TitleBounds.measure(b)!!
        assertEquals(RectF(30f, 40f, 120f, 61f), r)
    }

    @Test fun savedAndReadBack() {
        val wins = CornerPins.windows(quad, w, h).map { it.copy(track = track({ it * 0.001f })) }
        val pin = CornerPin(quad, PinTrack(7, 10.4, quad, wins))
        val back = CornerPin.fromJson(JSONObject(pin.toJson().toString()))
        assertNotNull(back)
        for (i in 0 until 8) assertEquals(quad[i], back!!.quad[i], 1e-6f)
        val t = back!!.track!!
        assertEquals(7L, t.clip); assertEquals(10.4, t.refT, 1e-9)
        assertEquals(0.03f, t.wins[0].track!!.at(2.0 + 30 * 0.04)[0], 1e-6f)
        // with the grades: the pin survives saving the project
        val gs = GradeState()
        gs.setPin(42, pin)
        val g2 = GradeState().apply { load(JSONObject(gs.toJson().toString())) }
        assertEquals(quad[4], g2.pins[42]!!.quad[4], 1e-6f)
    }
}
