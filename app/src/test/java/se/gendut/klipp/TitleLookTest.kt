package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.TitleAnim
import se.gendut.klipp.engine.TitleLook
import se.gendut.klipp.engine.TitleShadow
import se.gendut.klipp.engine.TitleTemplate

/** The title's look in the text layer's name: style and shadow saved and read back; older titles unchanged. */
class TitleLookTest {
    @Test fun olderTitlesKeepTheirLook() {
        val l = TitleLook.parse("klipp:mall=kapitel;in=studs;font=Serif")
        assertEquals(TitleTemplate.CHAPTER, l.template); assertEquals(TitleAnim.BOUNCE, l.anim); assertEquals("Serif", l.font)
        assertTrue(l.bold); assertTrue(!l.italic); assertTrue(!l.caps); assertNull(l.shadow)
        // the template's own shadow as before: soft under chapter text, none on the lower third's plate
        assertTrue(l.shadowOrDefault.on)
        assertTrue(!TitleLook(TitleTemplate.LOWER_THIRD).shadowOrDefault.on)
        // an unchanged look writes the same string as before
        assertEquals("klipp:mall=kapitel;in=studs;font=Serif", l.encode())
    }

    @Test fun styleAndShadowRoundTrip() {
        val sh = TitleShadow(true, 0x123456, 0.8f, 45f, 0.1f, 0.2f, 0.05f)
        val l = TitleLook(TitleTemplate.FREE, TitleAnim.FADE, "Playfair Display", bold = false, italic = true, caps = true, shadow = sh)
        val r = TitleLook.parse(l.encode())
        assertEquals(l.copy(shadow = null), r.copy(shadow = null))
        val s = r.shadow!!
        assertEquals(0x123456, s.color); assertEquals(0.8f, s.opacity, 1e-3f); assertEquals(45f, s.angle, 0.05f)
        assertEquals(0.1f, s.distance, 1e-4f); assertEquals(0.2f, s.blur, 1e-4f); assertEquals(0.05f, s.spread, 1e-4f)
        assertEquals(TitleShadow.OFF, TitleLook.parse(l.copy(shadow = TitleShadow.OFF).encode()).shadow)
    }

    @Test fun brokenShadowFallsBackToTheTemplate() {
        assertNull(TitleLook.parse("klipp:mall=fri;in=ingen;sh=1,zz").shadow)
    }
}
