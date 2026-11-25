package se.gendut.klipp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.gendut.klipp.engine.Placement
import se.gendut.klipp.engine.TextSnap
import se.gendut.klipp.engine.TitleArt
import se.gendut.klipp.engine.inArt

/**
 * A title whose text goes past the frame is drawn with all of its text: scaled down, it shows whole – before, the
 * picture was frame-size, so the text past the edge was gone before the clip's scale and the scaled title had cut edges.
 * A title that fits is drawn exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TitleRegionTest {
    private val w = 640
    private val h = 360

    /** [img] placed in a [w]×[h] frame the way Media3 and the preview place an overlay (see drawPlaced). */
    private fun place(img: Bitmap, p: Placement): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        Canvas(out).apply {
            translate((p.bgX + 1f) / 2f * w, (1f - p.bgY) / 2f * h)
            rotate(-p.rotation)
            scale(p.scale, p.scale)
            translate(-(p.ovX + 1f) / 2f * img.width, -(1f - p.ovY) / 2f * img.height)
            drawBitmap(img, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    private fun columns(b: Bitmap): IntRange? {
        var lo = -1; var hi = -1
        for (x in 0 until b.width) for (y in 0 until b.height step 2) if (Color.alpha(b.getPixel(x, y)) > 128) { if (lo < 0) lo = x; hi = x; break }
        return if (lo < 0) null else lo..hi
    }

    @Test fun aTitleThatFitsIsDrawnAsBefore() {
        val t = TextSnap("Hej", 60.0, null, null)
        assertTrue(TitleArt.region(t, w, h).isFrame)
        val b = TitleArt.drawRegion(t, w, h)
        assertEquals(w, b.width); assertEquals(h, b.height)
        assertTrue(TitleArt.regionOf(b).isFrame)
    }

    @Test fun aBigTitleScaledDownShowsAllOfItsText() {
        val t = TextSnap("KLIPP KLIPP KLIPP", 200.0, null, null)
        val r = TitleArt.region(t, w, h)
        assertTrue("the text goes past both sides: $r", r.left < 0f && r.right > 1f)
        val p = Placement(0f, 0f, 0f, 0f, 0.3f, 0f, 1f)
        // before: the frame-size picture, scaled to 30 %: the text ends where the frame's edge was (0.35 w … 0.65 w)
        val before = columns(place(TitleArt.draw(t, w, h), p))!!
        assertTrue("cut at the old frame edge: $before", before.first >= (0.35f * w).toInt() - 2 && before.last <= (0.65f * w).toInt() + 2)
        // now: the region's picture, placed by it – the text reaches further out on both sides
        val art = TitleArt.drawRegion(t, w, h)
        assertEquals(r, TitleArt.regionOf(art))
        val now = columns(place(art, p.inArt(r)))!!
        assertTrue("whole text: $now (before $before)", now.first < before.first - 10 && now.last > before.last + 10)
        // and it stays centred where it was
        assertEquals((before.first + before.last) / 2f, (now.first + now.last) / 2f, 6f)
    }

    @Test fun theSameTextPointLandsOnTheSameSpot() {
        val r = TitleArt.Region(-0.5f, -0.25f, 1.5f, 1.25f, 0.8f)
        val p = Placement(0.2f, -0.4f, 0.1f, 0.3f, 0.5f, 15f, 1f)
        val q = p.inArt(r)
        // the anchor, in frame fractions, is the same point of the region's picture
        val ax = r.left + (q.ovX + 1f) / 2f * (r.right - r.left)
        val ay = r.top + (1f - q.ovY) / 2f * (r.bottom - r.top)
        assertEquals((p.ovX + 1f) / 2f, ax, 1e-5f)
        assertEquals((1f - p.ovY) / 2f, ay, 1e-5f)
        // drawn at the region's resolution: the scale makes up for it
        assertEquals(p.scale / r.k, q.scale, 1e-6f)
        assertEquals(p.bgX, q.bgX); assertEquals(p.rotation, q.rotation)
    }
}
