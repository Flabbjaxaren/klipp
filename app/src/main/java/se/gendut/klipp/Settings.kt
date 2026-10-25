package se.gendut.klipp

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.gendut.klipp.engine.ExportSettings

/** App-wide settings (apply to all projects). Stored in "klipp". */
object AppPrefs {
    private fun p(c: Context) = c.getSharedPreferences("klipp", Context.MODE_PRIVATE)

    /** Tracking point cloud density (see WindowTracker.density). */
    fun trackDensity(c: Context): Float = p(c).getFloat("trackDensity", 1f)
    fun setTrackDensity(c: Context, d: Float) { p(c).edit().putFloat("trackDensity", d).apply(); se.gendut.klipp.engine.WindowTracker.density = d }

    /** Playhead locked in the center when the app starts. */
    fun centerDefault(c: Context): Boolean = p(c).getBoolean("centerPlayhead", false)
    fun setCenterDefault(c: Context, on: Boolean) { p(c).edit().putBoolean("centerPlayhead", on).apply() }

    /** Magnetic snapping on when the app starts. */
    fun magnetDefault(c: Context): Boolean = p(c).getBoolean("magnet", true)
    fun setMagnetDefault(c: Context, on: Boolean) { p(c).edit().putBoolean("magnet", on).apply() }

    /** Vibration on taps. */
    /** The agent bridge (McpBridge): off unless turned on. */
    fun agentBridge(c: Context): Boolean = p(c).getBoolean("agentBridge", false)
    fun setAgentBridge(c: Context, on: Boolean) { p(c).edit().putBoolean("agentBridge", on).apply(); if (on) McpBridge.start() else McpBridge.stop() }

    fun haptics(c: Context): Boolean = p(c).getBoolean("haptics", true)
    fun setHaptics(c: Context, on: Boolean) { p(c).edit().putBoolean("haptics", on).apply(); Haptics.enabled = on }

    /** Length of new titles in seconds (credits get double). */
    fun titleSeconds(c: Context): Float = p(c).getFloat("titleSec", 5f).coerceIn(1f, 60f)
    fun setTitleSeconds(c: Context, s: Float) { p(c).edit().putFloat("titleSec", s).apply() }

    /** Length of new captions in seconds. */
    fun captionSeconds(c: Context): Float = p(c).getFloat("captionSec", 2f).coerceIn(0.5f, 20f)
    fun setCaptionSeconds(c: Context, s: Float) { p(c).edit().putFloat("captionSec", s).apply() }

    /** The program monitor's height in portrait (dp); 0 = the default (16:9 of the width). */
    fun monitorHeight(c: Context): Float = p(c).getFloat("monitorHeightDp", 0f)
    fun setMonitorHeight(c: Context, dp: Float) { p(c).edit().putFloat("monitorHeightDp", dp).apply() }

    /** Tips already shown (first-run hints), by key. */
    fun tipSeen(c: Context, key: String): Boolean = p(c).getBoolean("tip_$key", false)
    fun setTipSeen(c: Context, key: String) { p(c).edit().putBoolean("tip_$key", true).apply() }

    fun accent(c: Context): Accent = runCatching { Accent.valueOf(p(c).getString("accent", null) ?: "") }.getOrDefault(Accent.ORANGE)
    fun setAccent(c: Context, a: Accent) { p(c).edit().putString("accent", a.name).apply(); K.accent = a }

    fun base(c: Context): Base = runCatching { Base.valueOf(p(c).getString("base", null) ?: "") }.getOrDefault(Base.DARK)
    fun setBase(c: Context, b: Base) { p(c).edit().putString("base", b.name).apply(); K.base = b }

    /** Loads what applies to the whole app (theme, haptics) – when the app starts. */
    fun apply(c: Context) {
        K.accent = accent(c)
        K.base = base(c)
        Haptics.enabled = haptics(c)
    }
}

private val TITLE_SECONDS = listOf(2f, 3f, 4f, 5f, 8f, 10f)
private val CAPTION_SECONDS = listOf(1f, 1.5f, 2f, 3f, 4f, 5f)

/** "2 s", "1.5 s" (decimal separator by locale). */
private fun secLabel(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else "%.1f".format(s)) + " s"

/**
 * Settings: appearance (accent color, background), the timeline (playhead, snapping, haptics, default lengths),
 * playback (proxy), export defaults and about the app.
 *
 * @param onCenterDefault the playhead's default mode changed (also applies right away)
 * @param onMagnetDefault the snapping default changed (also applies right away)
 */
@Composable
fun SettingsScreen(onClose: () -> Unit, onCenterDefault: (Boolean) -> Unit = {}, onMagnetDefault: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize().background(K.Bg).windowInsetsPadding(WindowInsets.safeDrawing).testTag("settings")) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Ic.Back, stringResource(R.string.settings_back)) { onClose() }
            Text(stringResource(R.string.settings_title), color = K.OnSurface, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            Group(stringResource(R.string.settings_group_appearance))
            Text(stringResource(R.string.settings_accent_color), color = K.OnSurfaceVar, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (a in Accent.entries) {
                    val on = K.accent == a
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Color(a.primary))
                                .border(if (on) 3.dp else 1.dp, if (on) K.OnSurface else K.OutlineVar, CircleShape)
                                .clickable(onClickLabel = a.label) { haptics.tick(); AppPrefs.setAccent(ctx, a) }
                                .testTag("accent:${a.name}"),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Ic.Check, null, tint = Color(a.onPrimary), modifier = Modifier.size(20.dp)) }
                        Text(a.label, color = if (on) K.OnSurface else K.OnSurfaceVar, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            DropField(stringResource(R.string.settings_background), Base.entries.map { Opt(it, it.label, it.hint, key = it.name) }, K.base, "base") { AppPrefs.setBase(ctx, it) }

            Group(stringResource(R.string.settings_group_timeline))
            var center by remember { mutableStateOf(AppPrefs.centerDefault(ctx)) }
            SwitchRow(stringResource(R.string.settings_center_playhead), stringResource(R.string.settings_center_playhead_hint), center, "set:center") {
                center = it; AppPrefs.setCenterDefault(ctx, it); onCenterDefault(it)
            }
            var magnet by remember { mutableStateOf(AppPrefs.magnetDefault(ctx)) }
            SwitchRow(stringResource(R.string.settings_magnet), stringResource(R.string.settings_magnet_hint), magnet, "set:magnet") {
                magnet = it; AppPrefs.setMagnetDefault(ctx, it); onMagnetDefault(it)
            }
            var vib by remember { mutableStateOf(AppPrefs.haptics(ctx)) }
            SwitchRow(stringResource(R.string.settings_haptics), stringResource(R.string.settings_haptics_hint), vib, "set:haptics") {
                vib = it; AppPrefs.setHaptics(ctx, it); haptics.confirm()
            }
            var titleSec by remember { mutableStateOf(AppPrefs.titleSeconds(ctx)) }
            val titleDefaultHint = stringResource(R.string.settings_title_length_default)
            val defaultHint = stringResource(R.string.settings_default)
            DropField(stringResource(R.string.settings_title_length), TITLE_SECONDS.map { Opt(it, secLabel(it), if (it == 5f) titleDefaultHint else null, key = "t$it") },
                titleSec, "set:titleSec") { titleSec = it; AppPrefs.setTitleSeconds(ctx, it) }
            var capSec by remember { mutableStateOf(AppPrefs.captionSeconds(ctx)) }
            DropField(stringResource(R.string.settings_caption_length), CAPTION_SECONDS.map { Opt(it, secLabel(it), if (it == 2f) defaultHint else null, key = "c$it") },
                capSec, "set:capSec") { capSec = it; AppPrefs.setCaptionSeconds(ctx, it) }

            Group(stringResource(R.string.settings_group_pipeline))
            val vp = se.gendut.klipp.engine.VideoPipeline
            SwitchRow(stringResource(R.string.settings_float_pipeline), stringResource(R.string.settings_float_pipeline_hint), vp.highPrecision, "set:16f") { vp.setHighPrecision(ctx, it) }
            val full = stringResource(R.string.settings_preview_full)
            val fullHint = stringResource(R.string.settings_preview_full_hint)
            val hint720 = stringResource(R.string.settings_preview_720_hint)
            val hint1080 = stringResource(R.string.settings_preview_1080_hint)
            val hintOther = stringResource(R.string.settings_preview_other_hint)
            DropField(stringResource(R.string.settings_preview_resolution), vp.PREVIEW_SIZES.map { v ->
                Opt(v, if (v == 0) full else "${v}p", when (v) {
                    0 -> fullHint
                    720 -> hint720
                    1080 -> hint1080
                    else -> hintOther
                }, key = "pv$v")
            }, vp.previewShort, "set:previewShort") { vp.setPreviewShort(ctx, it) }
            Text(stringResource(R.string.settings_preview_note),
                color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            SwitchRow(stringResource(R.string.settings_ten_bit_display), stringResource(R.string.settings_ten_bit_display_hint),
                vp.displayTenBit, "set:10display") { vp.setDisplayTenBit(ctx, it) }
            DropField(stringResource(R.string.settings_hdr_to_sdr), listOf(
                Opt(0, "OpenGL", stringResource(R.string.settings_tonemap_gl_hint), key = "tmGl"),
                Opt(1, stringResource(R.string.settings_tonemap_codec), stringResource(R.string.settings_tonemap_codec_hint), enabled = android.os.Build.VERSION.SDK_INT >= 33, key = "tmCodec"),
            ), vp.hdrToneMap, "set:toneMap") { vp.setHdrToneMap(ctx, it) }
            Text(stringResource(R.string.settings_ten_bit_note), color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            Group(stringResource(R.string.settings_group_playback))
            ProxySection(ctx)
            var cacheSize by remember { mutableStateOf(0L) }
            androidx.compose.runtime.LaunchedEffect(se.gendut.klipp.engine.NodeCache.revision) {
                cacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { se.gendut.klipp.engine.NodeCache.size(ctx) }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_node_cache), color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settings_node_cache_hint, cacheSize / 1e9),
                        color = K.OnSurfaceVar, fontSize = 12.sp)
                }
                androidx.compose.material3.TextButton(onClick = { se.gendut.klipp.engine.NodeCache.clear(ctx); cacheSize = 0 }, modifier = Modifier.testTag("set:clearNodeCache")) { Text(stringResource(R.string.settings_clear)) }
            }

            Group(stringResource(R.string.settings_group_export))
            Text(stringResource(R.string.settings_export_note), color = K.OnSurfaceVar, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            var exp by remember { mutableStateOf(ExportSettings.load(ctx, null)) }
            ExportOptions(exp.copy(hdr = false), 1920, 1080, 60.0, hdrPossible = false,
                hdrBlocked = stringResource(R.string.settings_hdr_at_export)) { exp = it; ExportSettings.save(ctx, null, it) }

            Group(stringResource(R.string.settings_group_developer))
            var bridge by remember { mutableStateOf(AppPrefs.agentBridge(ctx)) }
            SwitchRow(stringResource(R.string.settings_agent_bridge), stringResource(R.string.settings_agent_bridge_hint), bridge, "set:agentBridge") {
                bridge = it; AppPrefs.setAgentBridge(ctx, it); haptics.confirm()
            }

            Group(stringResource(R.string.settings_group_about))
            Text(
                stringResource(R.string.settings_about, BuildConfigShim.version),
                color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Group(title: String) {
    Text(title.uppercase(), color = K.Primary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, hint: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = title) { onChange(!on) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = K.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, color = K.OnSurfaceVar, fontSize = 12.sp)
        }
        Spacer(Modifier.size(12.dp))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedThumbColor = K.OnPrimary, checkedTrackColor = K.Primary))
    }
}
