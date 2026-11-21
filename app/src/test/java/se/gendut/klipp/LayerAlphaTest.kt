package se.gendut.klipp

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.LayerFx
import se.gendut.klipp.engine.LayerMask
import se.gendut.klipp.engine.MaskKey

/** The still shown while scrubbing or dragging gets the layer's mask too (it showed the whole clip until the finger was lifted). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayerAlphaTest {
    private val still = android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GRAY) }.asImageBitmap()

    private fun alphaAt(img: androidx.compose.ui.graphics.ImageBitmap, u: Float, v: Float): Int {
        val b = img.asAndroidBitmap()
        return android.graphics.Color.alpha(b.getPixel((u * b.width).toInt().coerceAtMost(b.width - 1), (v * b.height).toInt().coerceAtMost(b.height - 1)))
    }

    @Test fun theMaskIsTransparentOutsideAndWholeInside() {
        val fx = LayerFx(mask = LayerMask(LayerMask.ELLIPSE, 0.5f, 0.5f, 0.4f, 0.5f, feather = 0.02f))
        val a = LayerAlpha.of(fx, still, 1.0)!!
        assertEquals(255, alphaAt(a, 0.5f, 0.5f))
        assertEquals(0, alphaAt(a, 0.05f, 0.05f))
        // inverted: the other way round
        val inv = LayerAlpha.of(fx.copy(mask = fx.mask!!.copy(invert = true)), still, 1.0)!!
        assertEquals(0, alphaAt(inv, 0.5f, 0.5f))
        assertEquals(255, alphaAt(inv, 0.05f, 0.05f))
    }

    @Test fun aTrackedMaskIsTakenAtTheStillsOwnTime() {
        val keys = listOf(MaskKey(0.0, 0.25f, 0.5f, 0.3f, 0.3f, tracked = true), MaskKey(2.0, 0.75f, 0.5f, 0.3f, 0.3f, tracked = true))
        val fx = LayerFx(mask = LayerMask(LayerMask.RECT, feather = 0f, keys = keys))
        val early = LayerAlpha.of(fx, still, 0.0)!!
        val late = LayerAlpha.of(fx, still, 2.0)!!
        assertTrue(alphaAt(early, 0.25f, 0.5f) == 255 && alphaAt(early, 0.75f, 0.5f) == 0)
        assertTrue(alphaAt(late, 0.75f, 0.5f) == 255 && alphaAt(late, 0.25f, 0.5f) == 0)
    }
}
