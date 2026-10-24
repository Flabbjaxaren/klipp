package se.gendut.klipp

import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.gendut.klipp.engine.Fonts

/** The fonts that come with the app: every family loads from the assets, bold is bold, Swedish letters are there. */
@RunWith(AndroidJUnit4::class)
class BundledFontsTest {
    @Test fun allFamiliesLoadWithTheirStyles() {
        Fonts.init(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(33, Fonts.bundled.size)
        assertTrue("the phone's Inter keeps its name", "Inter Display" in Fonts.bundled && "Inter" in Fonts.builtIn)
        val fallback = Paint().apply { textSize = 100f; typeface = android.graphics.Typeface.SANS_SERIF }.measureText("Åsa går hem")
        // ink: pixels the text covers – bold covers more, also where it is synthesized or the font is monospaced
        fun ink(tf: android.graphics.Typeface): Int {
            val bmp = android.graphics.Bitmap.createBitmap(900, 160, android.graphics.Bitmap.Config.ALPHA_8)
            android.graphics.Canvas(bmp).drawText("Åsa går hem", 10f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 100f; typeface = tf })
            val px = ByteArray(bmp.byteCount).also { bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(it)) }
            return px.sumOf { it.toInt() and 0xFF }
        }
        for (name in Fonts.bundled.keys) {
            val regular = Paint().apply { textSize = 100f; typeface = Fonts.typeface(name) }.measureText("Åsa går hem")
            val r = ink(Fonts.typeface(name, false, false)); val b = ink(Fonts.typeface(name, true, false))
            val i = ink(Fonts.typeface(name, false, true))
            android.util.Log.i("BundledFontsTest", "$name: width %.0f (sans %.0f), ink %d / bold %d (%.0f %%)".format(regular, fallback, r, b, (b - r) * 100f / r))
            assertTrue("$name loads (not the phone's sans)", kotlin.math.abs(regular - fallback) > 0.5f)
            assertTrue("$name has å, ä, ö", Paint().apply { typeface = Fonts.typeface(name) }.hasGlyph("ö"))
            assertTrue("$name bold is heavier", b > r * 1.04f)
            assertTrue("$name italic draws", i > r / 2)
        }
    }
}
