package se.gendut.klipp

import androidx.annotation.StringRes
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The accent color (buttons, selection, playhead). The tones are Material 3's dark roles for the color. */
enum class Accent(@StringRes private val labelId: Int, val primary: Long, val onPrimary: Long, val container: Long, val soft: Long, val tonal: Long) {
    ORANGE(R.string.theme_accent_amber, 0xFFFFB77C, 0xFF4D2600, 0xFF6E3900, 0xFFFFDCC3, 0xFF4A3A2C),
    BLUE(R.string.theme_accent_blue, 0xFF9ECAFF, 0xFF003258, 0xFF00497D, 0xFFD1E4FF, 0xFF2E3A4A),
    GREEN(R.string.theme_accent_green, 0xFFA6D396, 0xFF133809, 0xFF2A4F1E, 0xFFC1F0B0, 0xFF33402E),
    PURPLE(R.string.theme_accent_purple, 0xFFD0BCFF, 0xFF381E72, 0xFF4F378B, 0xFFEADDFF, 0xFF3E3550),
    PINK(R.string.theme_accent_pink, 0xFFFFB0CC, 0xFF5E1133, 0xFF7B2949, 0xFFFFD9E3, 0xFF4C3039),
    RED(R.string.theme_accent_red, 0xFFFFB4A9, 0xFF690003, 0xFF930006, 0xFFFFDAD4, 0xFF4E3330),
    YELLOW(R.string.theme_accent_yellow, 0xFFF0BB5C, 0xFF3F2A00, 0xFF5C3F00, 0xFFFFDC8A, 0xFF483E2A),
    CYAN(R.string.theme_accent_cyan, 0xFF80D4EA, 0xFF00363F, 0xFF004E5B, 0xFFA9EDFF, 0xFF2C3E44);

    /** Display name (resolved on use, so it follows the app language). */
    val label: String get() = Str.t(labelId)
}

/** The background tone: surfaces from darkest to lightest. */
enum class Base(@StringRes private val labelId: Int, @StringRes private val hintId: Int, val bg: Long, val deep: Long, val lane: Long, val surface: Long, val high: Long, val highest: Long, val outlineVar: Long) {
    DARK(R.string.theme_base_dark, R.string.theme_base_dark_hint, 0xFF111318, 0xFF0C0E12, 0xFF15181D, 0xFF1D2026, 0xFF272A30, 0xFF32353B, 0xFF43474E),
    BLACK(R.string.theme_base_black, R.string.theme_base_black_hint, 0xFF000000, 0xFF000000, 0xFF0A0B0D, 0xFF121316, 0xFF1C1D21, 0xFF27292D, 0xFF3A3D43),
    GRAPHITE(R.string.theme_base_graphite, R.string.theme_base_graphite_hint, 0xFF1A1C20, 0xFF14161A, 0xFF202328, 0xFF272A30, 0xFF31343A, 0xFF3C3F45, 0xFF4B4F56);

    /** Display name and hint (resolved on use, so they follow the app language). */
    val label: String get() = Str.t(labelId)
    val hint: String get() = Str.t(hintId)
}

/**
 * The colors (dark Material 3 theme). Accent and background are chosen in settings; they are read as state, so
 * everything drawn with them changes immediately when the choice changes.
 */
/**
 * Labels under an icon: the font's extra space above and below the line is trimmed, so icon + label sit in the middle
 * of their button (with it the label's box was taller than its letters and pushed the icon toward the top edge).
 */
val IconLabel = androidx.compose.ui.text.TextStyle(
    lineHeight = androidx.compose.ui.unit.TextUnit(1.15f, androidx.compose.ui.unit.TextUnitType.Em),
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
    ),
)

object K {
    var accent by mutableStateOf(Accent.ORANGE)
    var base by mutableStateOf(Base.DARK)

    val Bg get() = Color(base.bg)
    val Deep get() = Color(base.deep)
    val Lane get() = Color(base.lane)
    val Surface get() = Color(base.surface)
    val SurfaceHigh get() = Color(base.high)
    val SurfaceHighest get() = Color(base.highest)
    val OnSurface = Color(0xFFE2E2E9)
    val OnSurfaceVar = Color(0xFFC3C6D0)
    val Outline = Color(0xFF8D9199)
    val OutlineVar get() = Color(base.outlineVar)
    val Primary get() = Color(accent.primary)
    val OnPrimary get() = Color(accent.onPrimary)
    val PrimaryContainer get() = Color(accent.container)
    val PrimarySoft get() = Color(accent.soft)
    val Tonal get() = Color(accent.tonal)
    val Teal = Color(0xFF7DD5C6)
    val Pink = Color(0xFFF2B8D8)
    val Error = Color(0xFFFFB4AB)
    val Rec = Color(0xFF93000A)
}

/** Springs. Low damping = more bounce. Stiffness = how fast. */
object Springs {
    fun snap() = spring<Float>(dampingRatio = 0.42f, stiffness = 650f)
    fun follow() = spring<Float>(dampingRatio = 1f, stiffness = 3200f)
    fun settle() = spring<Float>(dampingRatio = 0.55f, stiffness = 420f)
    fun lift() = spring<Float>(dampingRatio = 0.32f, stiffness = 520f)
    fun track() = spring<Float>(dampingRatio = 0.5f, stiffness = 500f)
    fun page() = spring<Float>(dampingRatio = 0.72f, stiffness = 300f)
    fun pop() = spring<Float>(dampingRatio = 0.35f, stiffness = 380f)
}

@Composable
fun KlippTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = K.Primary,
            onPrimary = K.OnPrimary,
            primaryContainer = K.PrimaryContainer,
            onPrimaryContainer = K.PrimarySoft,
            secondaryContainer = K.Tonal,
            onSecondaryContainer = K.PrimarySoft,
            tertiary = K.Teal,
            background = K.Bg,
            surface = K.Bg,
            surfaceContainer = K.Surface,
            surfaceContainerHigh = K.SurfaceHigh,
            surfaceContainerHighest = K.SurfaceHighest,
            onSurface = K.OnSurface,
            onSurfaceVariant = K.OnSurfaceVar,
            outline = K.Outline,
            outlineVariant = K.OutlineVar,
            error = K.Error,
        ),
        content = content,
    )
}
