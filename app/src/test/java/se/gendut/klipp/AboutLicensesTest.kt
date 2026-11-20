package se.gendut.klipp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings → About → Licenses and source code: reachable without developer settings, offline (everything from the
 * APK's assets/licenses/), with the GPL's full text, this build's own source link and every notice the inventory names.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "sv-w411dp-h891dp-xxhdpi")
class AboutLicensesTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun everyNoticeTheInventoryNamesIsInTheApp() {
        val items = LicenseInventory.load(ctx)
        assertTrue("inventory loaded: ${items.size}", items.size > 300)
        val missing = items.flatMap { i -> i.notices.filter { LicenseInventory.text(ctx, it).isBlank() }.map { "${i.id}: $it" } }
        assertEquals(emptyList<String>(), missing)
        // the parts a reader must find: Klipp's GPL, the engine's fonts, spektrafilm's license and changelog, Symphonia's MPL
        assertTrue(LicenseInventory.text(ctx, "LICENSES/texts/GPL-3.0.txt").contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(LicenseInventory.text(ctx, "LICENSES/texts/MPL-2.0.txt").contains("Mozilla Public License"))
        assertTrue(LicenseInventory.text(ctx, "app/src/main/assets/spektrafilm/SPEKTRAFILM_LICENSE.txt").contains("Andrea Volpato"))
        assertTrue(LicenseInventory.text(ctx, "app/src/main/assets/spektrafilm/CHANGELOG.txt").contains("Modified by the Klipp contributors"))
        assertTrue(items.any { it.id == "filmcraft-fonts" && it.notices.any { n -> n.endsWith("OFL-Inter.txt") } })
        assertTrue(items.first { it.name == "symphonia-core" }.license == "MPL-2.0")
    }

    @Test fun theSourceLinkIsThisCommitNotMain() {
        assertEquals("https://github.com/Flabbjaxaren/klipp/tree/0123456789abcdef", LicenseInventory.sourceUrl("0123456789abcdef"))
        assertEquals("https://github.com/Flabbjaxaren/klipp/tree/0123456789abcdef", LicenseInventory.sourceUrl("0123456789abcdef-dirty"))
        assertEquals("https://github.com/Flabbjaxaren/klipp", LicenseInventory.sourceUrl("unknown"))
    }

    @Test fun aboutLeadsToTheLicensesAndTheGplText() {
        rule.setContent { KlippTheme { SettingsScreen(onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("set:cat:about").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("about:gpl").assertIsDisplayed()
        rule.onNodeWithText("Licenser och källkod").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("about:source").assertIsDisplayed()
        rule.onNodeWithTag("licenses:spektrafilm").performScrollTo()
        rule.onNodeWithTag("licenses:rust").performScrollTo()
        rule.onNodeWithTag("about:gplText").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("notice:text").assertExists()
    }
}

/** The same at a large text size (1.3×): the pages scroll and nothing is cut off. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w360dp-h640dp-xxhdpi", fontScale = 1.3f)
class AboutLicensesLargeTextTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aComponentAndItsNoticeOpenAtLargeText() {
        rule.setContent { KlippTheme { SettingsScreen(onClose = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("set:cat:about").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("about:licenses").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("lic:spektrafilm-tables").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("CC-BY-SA-4.0").assertExists()
        rule.onNodeWithText("CHANGELOG.txt").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("notice:text").assertExists()
    }
}

/** Notices are shown reflowed for a narrow screen: only whitespace changes, and lists, indented blocks and headings stay. */
class LicenseReflowTest {
    @Test fun paragraphsJoinListsAndIndentedBlocksStay() {
        val text = "  Everyone is permitted to copy and distribute verbatim copies of this\n" +
            "license document, but changing it is not allowed.\n\n" +
            "1. The origin of this software must not be misrepresented; you must not\n" +
            "   claim that you wrote the original software.\n" +
            "    This program is free software: you can redistribute it and/or modify\n" +
            "    it under the terms of the GNU General Public License.\n" +
            "END OF TERMS AND CONDITIONS\n" +
            "short line\nnext"
        val r = LicenseInventory.reflow(text)
        assertEquals(text.filterNot { it.isWhitespace() }, r.filterNot { it.isWhitespace() })
        assertTrue(r.startsWith("  Everyone is permitted to copy and distribute verbatim copies of this license document"))
        assertTrue(r.contains("misrepresented; you must not\n   claim"))
        assertTrue(r.contains("and/or modify\n    it under"))
        assertTrue(r.contains("short line\nnext"))
    }

    @Test fun aHeadingCentredWithSpacesLosesThem() {
        val text = "                    GNU GENERAL PUBLIC LICENSE\n                       Version 3, 29 June 2007\n\n" +
            "    <one line to give the program's name and a brief idea of what it does.>\n"
        val r = LicenseInventory.reflow(text)
        assertTrue(r, r.startsWith("GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007\n\n"))
        assertTrue("a sample notice keeps its indent", r.contains("\n    <one line"))
    }
}
