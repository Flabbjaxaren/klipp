package se.gendut.klipp

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.Overlay
import se.gendut.klipp.engine.OverlayRenderer
import se.gendut.klipp.engine.TextSnap
import java.io.File

/** Titles and captions are drawn to transparent images at the sequence's size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OverlayRenderTest {
    private fun opaque(b: android.graphics.Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var n = 0
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) if (Color.alpha(b.getPixel(x, y)) > 128) n++
        return n
    }

    @Test
    fun titleIsDrawnAroundItsPositionAndRestIsTransparent() {
        val b = OverlayRenderer.draw(Overlay.Title(TextSnap("Hej Klipp", 80.0, 640.0, 600.0, listOf(1f, 0.72f, 0.49f, 1f), "")), 1280, 720)
        assertEquals(1280, b.width)
        assertEquals(0, Color.alpha(b.getPixel(10, 10)))
        assertTrue("text around the baseline", opaque(b, 440, 530, 840, 610) > 50)
        assertEquals("nothing above", 0, opaque(b, 0, 0, 1280, 400))
        save(b, "titel.png")
    }

    @Test
    fun captionSitsAtTheBottomWithABox() {
        val b = OverlayRenderer.draw(Overlay.Caption("Första raden\nAndra raden"), 1280, 720)
        assertTrue(opaque(b, 300, 560, 980, 680) > 200)
        assertEquals(0, opaque(b, 0, 0, 1280, 450))
        save(b, "undertext.png")
    }

    private fun save(b: android.graphics.Bitmap, name: String) {
        val f = File("build/shots/$name").apply { parentFile?.mkdirs() }
        f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun templatesDrawWhereTheyBelong() {
        fun look(m: String) = "klipp:mall=$m;in=studs"
        val lower = OverlayRenderer.draw(Overlay.Title(TextSnap("Anna Berg\nFiskare, Smögen", 45.0, null, null, listOf(1f, 1f, 1f, 1f), look("namnskylt"))), 1280, 720)
        assertTrue("the lower third sits bottom left", opaque(lower, 60, 480, 500, 620) > 300)
        assertEquals(0, opaque(lower, 700, 0, 1280, 400))
        save(lower, "mall_namnskylt.png")
        val chapter = OverlayRenderer.draw(Overlay.Title(TextSnap("Kapitel 2\nHavet", 61.0, null, null, listOf(1f, 1f, 1f, 1f), look("kapitel"))), 1280, 720)
        assertTrue(opaque(chapter, 400, 280, 880, 440) > 100)
        save(chapter, "mall_kapitel.png")
        val roll = OverlayRenderer.draw(Overlay.Title(TextSnap((1..30).joinToString("\n") { "Rad $it" }, 36.0, null, null, listOf(1f, 1f, 1f, 1f), look("rulltext"))), 1280, 720)
        assertTrue("the credits roll is taller than the frame", roll.height > 720)
    }

    @Test
    fun titleMotionComesInAndRollScrolls() {
        val bounce = se.gendut.klipp.engine.TitleLook.parse("klipp:mall=namnskylt;in=studs")
        assertEquals(se.gendut.klipp.engine.TitleTemplate.LOWER_THIRD, bounce.template)
        assertEquals("klipp:mall=namnskylt;in=studs", bounce.encode())
        val p0 = se.gendut.klipp.engine.TitleMotion.pose(bounce, 0.0, 5.0, 1280, 720)
        val p1 = se.gendut.klipp.engine.TitleMotion.pose(bounce, 1.0, 5.0, 1280, 720)
        val pOut = se.gendut.klipp.engine.TitleMotion.pose(bounce, 4.9, 5.0, 1280, 720)
        assertEquals(0f, p0.alpha, 1e-4f)
        assertTrue("starts below", p0.dy < -0.2f)
        assertEquals(1f, p1.alpha, 1e-4f)
        assertEquals(0f, p1.dy, 0.02f)
        assertTrue("fades out at the end", pOut.alpha < 0.5f)
        val roll = se.gendut.klipp.engine.TitleLook.parse("klipp:mall=rulltext;in=ingen")
        val r0 = se.gendut.klipp.engine.TitleMotion.pose(roll, 0.0, 10.0, 1280, 720, 1440)
        val r1 = se.gendut.klipp.engine.TitleMotion.pose(roll, 10.0, 10.0, 1280, 720, 1440)
        assertEquals("the top edge starts at the frame's bottom edge", -1f, r0.anchorY + r0.dy, 1e-4f)
        assertEquals("and ends above the frame", 1f + 2f * 1440 / 720, r1.anchorY + r1.dy, 1e-3f)
        assertEquals("old titles without a template are free text", se.gendut.klipp.engine.TitleTemplate.FREE, se.gendut.klipp.engine.TitleLook.parse("").template)
    }
}
