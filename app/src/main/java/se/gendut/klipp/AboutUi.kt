package se.gendut.klipp

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * Settings → About: the version and build, Klipp's copyright and license, and "Licenses and source code" – the GPL's
 * full text, a link to this build's exact source, and every third-party component with its license, copyright and
 * notices. All of it is read from assets/licenses/ (LICENSES/ in the repository, see docs/licensing.md), so it works
 * offline and is the same inventory the repository has. Links open only when tapped.
 */

/** One component of LICENSES/inventory.json, as the screen shows it. */
internal data class LicenseItem(
    val id: String, val name: String, val version: String, val license: String, val copyright: String,
    val origin: String, val changes: String, val notes: String, val includedIn: String, val category: String,
    val notices: List<String>,
)

internal object LicenseInventory {
    /** The repository path of a notice → its asset path in the APK (LICENSES/x → licenses/x, app/src/main/assets/x → x). */
    fun assetPath(repoPath: String): String = when {
        repoPath.startsWith("LICENSES/") -> "licenses/" + repoPath.removePrefix("LICENSES/")
        repoPath.startsWith("app/src/main/assets/") -> repoPath.removePrefix("app/src/main/assets/")
        else -> repoPath
    }

    fun load(c: Context): List<LicenseItem> = runCatching {
        val json = c.assets.open("licenses/inventory.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("components")
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optBoolean("distributed", true) }
            .map { o ->
                val kind = o.optString("kind")
                LicenseItem(
                    id = o.optString("id"), name = o.optString("component"), version = o.optString("version"),
                    license = o.optString("license"), copyright = o.optString("copyright"), origin = o.optString("origin"),
                    changes = o.optString("changes"), notes = o.optString("notes"), includedIn = o.optString("included_in"),
                    category = when (kind) { "maven" -> "maven"; "rust-crate" -> "rust"; else -> o.optString("category") },
                    notices = o.optJSONArray("notices")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                )
            }
    }.getOrDefault(emptyList())

    fun text(c: Context, repoPath: String): String =
        runCatching { c.assets.open(assetPath(repoPath)).bufferedReader().use { it.readText() } }.getOrDefault("")

    /** The source of exactly this build: its commit on GitHub (a "-dirty" build has changes the commit lacks). */
    fun sourceUrl(commit: String = BuildConfig.GIT_COMMIT): String =
        commit.removeSuffix("-dirty").takeIf { it.length >= 7 && it != "unknown" }
            ?.let { "https://github.com/Flabbjaxaren/klipp/tree/$it" } ?: "https://github.com/Flabbjaxaren/klipp"

    const val RELEASES = "https://github.com/Flabbjaxaren/klipp/releases"

    /**
     * A notice for a narrow screen: the lines of a paragraph that a plain-text file wrapped at ~80 columns are joined, so
     * the phone wraps them once. Only whitespace changes – the words, blank lines, indented lines (lists, code, the GPL's
     * sample notices) and short lines (headings, signatures) stay as they are. A heading centred with spaces (ten or more
     * before a short line, the GPL's title) loses them: on a phone they pushed it past the edge and it broke in two.
     */
    fun reflow(text: String): String {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = StringBuilder()
        for ((i, line) in lines.withIndex()) {
            val lead = line.length - line.trimStart().length
            out.append(if (lead >= 10 && line.trim().length < 50) line.trim() else line)
            if (i == lines.lastIndex) break
            val next = lines[i + 1]
            val join = line.isNotBlank() && next.isNotBlank() && line.length >= 50 && !line.startsWith("   ") &&
                !next.first().isWhitespace() && !Regex("^([-*•]|\\d+[.)]|[a-z][.)]|\\([a-z0-9]+\\))\\s").containsMatchIn(next)
            out.append(if (join) ' ' else '\n')
        }
        return out.toString()
    }
}

/** Where the About pages are: the top page, the license list, one component, or one text. */
internal sealed interface AboutStep {
    data object Licenses : AboutStep
    data class Item(val item: LicenseItem) : AboutStep
    data class Notice(val title: String, val path: String) : AboutStep
}

/** The About pages' own back stack (the settings' back arrow and Back pop it before leaving the page). */
internal class AboutNav {
    val stack = mutableStateListOf<AboutStep>()
    fun pop(): Boolean = stack.removeLastOrNull() != null
}

@Composable
internal fun aboutTitle(nav: AboutNav): String? = when (val s = nav.stack.lastOrNull()) {
    null -> null
    AboutStep.Licenses -> stringResource(R.string.about_licenses)
    is AboutStep.Item -> s.item.name
    is AboutStep.Notice -> s.title
}

@Composable
internal fun AboutPage(nav: AboutNav) {
    val ctx = LocalContext.current
    if (nav.stack.isNotEmpty()) androidx.activity.compose.BackHandler { nav.pop() }
    val items = remember { LicenseInventory.load(ctx) }
    when (val s = nav.stack.lastOrNull()) {
        null -> PanelCard(Modifier.padding(top = 8.dp).testTag("about")) { AboutTop(nav) }
        AboutStep.Licenses -> LicensesList(items, nav)
        is AboutStep.Item -> PanelCard(Modifier.padding(top = 8.dp)) { ItemDetail(s.item, nav) }
        is AboutStep.Notice -> PanelCard(Modifier.padding(top = 8.dp)) { NoticeText(s.path) }
    }
}

@Composable
private fun ColumnScope.AboutTop(nav: AboutNav) {
    val commit = BuildConfig.GIT_COMMIT
    Text("Klipp ${BuildConfigShim.version}", color = K.OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("about:version"))
    SelectionContainer {
        Text(stringResource(R.string.about_build, BuildConfig.VERSION_CODE, commit.take(12) + if (commit.endsWith("-dirty")) "-dirty" else ""),
            color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
    }
    Text(stringResource(R.string.about_copyright), color = K.OnSurface, fontSize = 14.sp, lineHeight = 20.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).testTag("about:gpl"))
    LinkRow(stringResource(R.string.about_licenses), stringResource(R.string.about_licenses_hint), "about:licenses") { nav.stack.add(AboutStep.Licenses) }
}

@Composable
private fun LicensesList(items: List<LicenseItem>, nav: AboutNav) {
    val links = LocalUriHandler.current
    val commit = BuildConfig.GIT_COMMIT
    PanelCard(Modifier.padding(top = 8.dp).testTag("licenses"), title = stringResource(R.string.about_source)) {
        Text(stringResource(if (commit.endsWith("-dirty") || commit == "unknown") R.string.about_source_hint_local else R.string.about_source_hint),
            color = K.OnSurfaceVar, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LinkRow(stringResource(R.string.about_source_commit), LicenseInventory.sourceUrl(), "about:source") { runCatching { links.openUri(LicenseInventory.sourceUrl()) } }
        LinkRow(stringResource(R.string.about_source_releases), LicenseInventory.RELEASES, "about:releases") { runCatching { links.openUri(LicenseInventory.RELEASES) } }
        LinkRow(stringResource(R.string.about_gpl_text), "GNU GPL v3", "about:gplText") {
            nav.stack.add(AboutStep.Notice("GNU General Public License v3", "LICENSES/texts/GPL-3.0.txt"))
        }
    }
    val groups = listOf(
        "klipp" to R.string.about_group_klipp, "code" to R.string.about_group_code, "assets" to R.string.about_group_assets,
        "fonts" to R.string.about_group_fonts, "downloads" to R.string.about_group_downloads,
        "maven" to R.string.about_group_maven, "rust" to R.string.about_group_rust,
    )
    for ((cat, title) in groups) {
        val list = items.filter { it.category == cat }
        if (list.isEmpty()) continue
        PanelCard(Modifier.padding(top = 12.dp).testTag("licenses:$cat"), title = stringResource(title)) {
            if (cat == "assets") Text(stringResource(R.string.about_spektrafilm), color = K.OnSurfaceVar, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("licenses:spektrafilm"))
            if (cat == "downloads") Text(stringResource(R.string.about_downloads_hint), color = K.OnSurfaceVar, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            for (it in list) LinkRow(it.name, listOf(it.version, it.license).filter { s -> s.isNotBlank() && s != "-" }.joinToString(" · "), "lic:${it.id}") {
                nav.stack.add(AboutStep.Item(it))
            }
        }
    }
}

@Composable
private fun ColumnScope.ItemDetail(item: LicenseItem, nav: AboutNav) {
    val links = LocalUriHandler.current
    @Composable fun field(label: Int, value: String) {
        if (value.isBlank()) return
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
            Text(stringResource(label), color = K.OnSurfaceVar, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            SelectionContainer { Text(value, color = K.OnSurface, fontSize = 14.sp, lineHeight = 19.sp) }
        }
    }
    field(R.string.about_field_version, item.version.takeIf { it != "-" }.orEmpty())
    field(R.string.about_field_license, item.license)
    field(R.string.about_field_copyright, item.copyright)
    field(R.string.about_field_changes, item.changes)
    field(R.string.about_field_included, item.includedIn)
    field(R.string.about_field_notes, item.notes)
    field(R.string.about_field_origin, item.origin)
    val url = Regex("https?://[^\\s·,]+").find(item.origin)?.value
    if (url != null) LinkRow(stringResource(R.string.about_open_origin), url, "lic:open") { runCatching { links.openUri(url) } }
    for (n in item.notices) LinkRow(n.substringAfterLast('/'), n, "lic:notice") { nav.stack.add(AboutStep.Notice(n.substringAfterLast('/'), n)) }
}

/** Characters of a notice shown at most (see NoticeText). */
private const val NOTICE_SHOWN = 150_000

@Composable
private fun NoticeText(path: String) {
    val ctx = LocalContext.current
    val full = remember(path) { LicenseInventory.reflow(LicenseInventory.text(ctx, path)) }
    // a very long notice (MediaPipe's NOTICE, 3 MB) is shown in part – one Text that long takes seconds to lay out;
    // the whole file is in the APK and in the source
    val cut = full.length > NOTICE_SHOWN
    val text = if (cut) full.substring(0, full.lastIndexOf('\n', NOTICE_SHOWN).coerceAtLeast(NOTICE_SHOWN / 2)) +
        "\n\n" + stringResource(R.string.about_notice_cut, full.length / 1024, LicenseInventory.assetPath(path)) else full
    SelectionContainer {
        Text(text.ifEmpty { stringResource(R.string.about_missing, path) }, color = K.OnSurface, fontSize = 13.sp, lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("notice:text"))
    }
}

@Composable
private fun LinkRow(title: String, hint: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = title, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (hint.isNotBlank()) Text(hint, color = K.OnSurfaceVar, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
