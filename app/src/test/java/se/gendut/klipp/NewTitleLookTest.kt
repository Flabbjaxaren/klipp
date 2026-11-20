package se.gendut.klipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gendut.klipp.engine.TitleAnim
import se.gendut.klipp.engine.TitleLook
import se.gendut.klipp.engine.TitleTemplate

/** A new title is free text without effects; titles already made keep their look. */
class NewTitleLookTest {
    @Test fun aNewTitleIsPlainFreeText() {
        val d = TitleDraft()
        assertEquals(TitleTemplate.FREE, d.template)
        assertEquals(TitleAnim.NONE, d.anim)
        val l = TitleLook.parse(newTitleLook(d.template, d.anim).encode())
        assertEquals(TitleTemplate.FREE, l.template)
        assertEquals(TitleAnim.NONE, l.anim)
        assertFalse("no shadow", l.shadowOrDefault.on)
        assertFalse("no plate", l.plateOrDefault.on)
        assertEquals("no outline", 0f, l.stroke, 0f)
    }

    @Test fun aFreeTitleSavedBeforeKeepsItsShadow() {
        // saved without a shadow of its own: the template's (as before)
        assertTrue(TitleLook.parse("klipp:mall=fri;in=ingen").shadowOrDefault.on)
        // another template chosen for a new title: its own look
        assertTrue(TitleLook.parse(newTitleLook(TitleTemplate.LOWER_THIRD, TitleAnim.NONE).encode()).plateOrDefault.on)
    }
}
