package se.gendut.klipp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.gendut.klipp.engine.CaptionStyle
import se.gendut.klipp.engine.TitleLook
import se.gendut.klipp.engine.TitleAnim
import se.gendut.klipp.engine.TitleTemplate

/** The caption track's style from the engine snapshot, and the titles' fonts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptionStyleTest {
    @Test fun parsesEngineStyle() {
        val s = CaptionStyle.fromJson(JSONObject("""{"font":"Serif","size":72,"color":"#ffe07aff","background":true,"backgroundColor":"#000000bf",
            "align":"left","anchor":"top","margin":0.1,"lineSpacing":1.4,"outline":3,"outlineColor":"#112233ff"}"""))
        assertEquals("Serif", s.font)
        assertEquals(72f, s.size, 0f)
        assertEquals(0xFFFFE07A.toInt(), s.color)
        assertEquals(0xBF000000.toInt(), s.backgroundColor)
        assertEquals("left", s.align)
        assertEquals("top", s.anchor)
        assertEquals(0xFF112233.toInt(), s.outlineColor)
        // back to the engine's format
        assertEquals("#ffe07aff", CaptionStyle.hex(s.color))
        assertEquals("#000000bf", CaptionStyle.hex(s.backgroundColor))
        // no style: the engine's default
        assertEquals(CaptionStyle(), CaptionStyle.fromJson(null))
        assertEquals(54f, CaptionStyle().size, 0f)
    }

    @Test fun titleFontIsSavedInTheLook() {
        val l = TitleLook(TitleTemplate.CHAPTER, TitleAnim.FADE, "Serif")
        assertEquals(l, TitleLook.parse(l.encode()))
        // older titles: the default font, and the string looks as before
        assertEquals("Inter", TitleLook.parse("klipp:mall=kapitel;in=tona").font)
        assertTrue(!TitleLook(TitleTemplate.CHAPTER, TitleAnim.FADE).encode().contains("font"))
    }

    @Test fun drawsWithStyle() {
        val bmp = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888)
        se.gendut.klipp.engine.OverlayRenderer.drawCaption(android.graphics.Canvas(bmp), "Hej\nrad två", CaptionStyle(anchor = "top", outline = 2f), 320, 180)
        se.gendut.klipp.engine.OverlayRenderer.drawCaption(android.graphics.Canvas(bmp), "", CaptionStyle(), 320, 180)
    }
}
