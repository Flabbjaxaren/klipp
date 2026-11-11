package se.gendut.klipp

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The accent color (buttons, pills, switches, sliders – not the timeline). Only the seed is chosen: every tone the
 * interface uses is worked out from it by the Material 3 roles ([AccentRoles]).
 */
enum class Accent(@StringRes private val labelId: Int, private val seedArgb: Long) {
    LIME(R.string.theme_accent_lime, 0xFFD5F15E),
    ORANGE(R.string.theme_accent_amber, 0xFFFFA24C),
    YELLOW(R.string.theme_accent_yellow, 0xFFF2C230),
    GREEN(R.string.theme_accent_green, 0xFF7ADB6B),
    CYAN(R.string.theme_accent_cyan, 0xFF45D6EE),
    BLUE(R.string.theme_accent_blue, 0xFF74B2FF),
    PURPLE(R.string.theme_accent_purple, 0xFFB79CFF),
    PINK(R.string.theme_accent_pink, 0xFFFF8DBA),
    RED(R.string.theme_accent_red, 0xFFFF8A76),
    /** Any hue: the seed is [K.customSeed]. */
    CUSTOM(R.string.theme_accent_custom, 0);

    /** Display name (resolved on use, so it follows the app language). */
    val label: String get() = Str.t(labelId)
    val seed: Int get() = if (this == CUSTOM) K.customSeed else seedArgb.toInt()
    val roles: AccentRoles get() = K.rolesOf(seed)
    val primary: Long get() = roles.primary.toLong() and 0xFFFFFFFFL
    val onPrimary: Long get() = roles.onPrimary.toLong() and 0xFFFFFFFFL
}

/** The background tone: surfaces from darkest to lightest. */
enum class Base(@StringRes private val labelId: Int, @StringRes private val hintId: Int, val bg: Long, val deep: Long, val lane: Long, val surface: Long, val high: Long, val highest: Long, val outlineVar: Long) {
    DARK(R.string.theme_base_dark, R.string.theme_base_dark_hint, 0xFF0F1012, 0xFF0A0B0D, 0xFF18191C, 0xFF18191C, 0xFF222327, 0xFF2D2E33, 0xFF47484E),
    BLACK(R.string.theme_base_black, R.string.theme_base_black_hint, 0xFF000000, 0xFF000000, 0xFF0C0D0F, 0xFF101114, 0xFF1A1B1F, 0xFF25262B, 0xFF3C3D43),
    GRAPHITE(R.string.theme_base_graphite, R.string.theme_base_graphite_hint, 0xFF191A1D, 0xFF131416, 0xFF222327, 0xFF222327, 0xFF2C2D32, 0xFF37383E, 0xFF505158);

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
    var accent by mutableStateOf(Accent.LIME)
    /** The seed of [Accent.CUSTOM]. */
    var customSeed by mutableStateOf(0xFFD5F15E.toInt())
    var base by mutableStateOf(Base.DARK)

    private val rolesCache = HashMap<Int, AccentRoles>()
    fun rolesOf(seed: Int): AccentRoles = synchronized(rolesCache) {
        if (rolesCache.size > 48) rolesCache.clear()
        rolesCache.getOrPut(seed) { AccentRoles(seed) }
    }
    private val roles get() = rolesOf(accent.seed)

    val Bg get() = Color(base.bg)
    val Deep get() = Color(base.deep)
    val Lane get() = Color(base.lane)
    val Surface get() = Color(base.surface)
    val SurfaceHigh get() = Color(base.high)
    val SurfaceHighest get() = Color(base.highest)
    val OnSurface = Color(0xFFEDEDF0)
    val OnSurfaceVar = Color(0xFFB1B3BB)
    val Outline = Color(0xFF8A8C94)
    val OutlineVar get() = Color(base.outlineVar)
    // the accent's roles (Material 3, dark): tones of the seed's palettes
    val Primary get() = Color(roles.primary)
    val OnPrimary get() = Color(roles.onPrimary)
    val PrimaryContainer get() = Color(roles.container)
    val PrimarySoft get() = Color(roles.onContainer)
    // the timeline's own marks (playhead, selection, trim handles): the same whatever the accent
    val Mark = Color(0xFFEDEDF0)
    val OnMark = Color(0xFF0F1012)
    val Teal = Color(0xFF7DD5C6)
    val Pink = Color(0xFFF2B8D8)
    val Error = Color(0xFFFF9A86)
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

/**
 * A button shrinks to [to] as the finger lands and springs back when it lifts – the press shows at once, not only
 * after release (feedback later than ~80 ms no longer reads as caused by the touch). A tap too quick for the shrink
 * to show (or a press reported late, inside a scrolling row) still gets the whole pop; a press turned into a scroll
 * just settles back.
 */
@Composable
fun Modifier.pressPop(src: MutableInteractionSource, to: Float): Modifier {
    val s = rememberPressScale(src, to)
    return graphicsLayer { scaleX = s.value; scaleY = s.value }
}

/** The scale behind [pressPop]. */
@Composable
fun rememberPressScale(src: MutableInteractionSource, to: Float): Animatable<Float, *> {
    val s = remember { Animatable(1f) }
    LaunchedEffect(src) {
        src.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> launch { s.animateTo(to, spring(1f, 1400f)) }
                is PressInteraction.Release -> launch {
                    if (s.value > 1f - (1f - to) / 2) s.snapTo(to)
                    s.animateTo(1f, Springs.pop())
                }
                is PressInteraction.Cancel -> launch { s.animateTo(1f, Springs.follow()) }
            }
        }
    }
    return s
}

/**
 * A scrolling row or column fades out at the edge it continues past – what is cut there reads as "there is more", not
 * as a broken layout. Goes before the scroll modifier in the chain (it fades the edges of what the scroll shows).
 */
fun Modifier.fadingEdges(scroll: ScrollState, horizontal: Boolean = false, length: Dp = 20.dp): Modifier = drawWithContent {
    val len = length.toPx()
    // how much of the fade shows at each end: none at the end of the scroll, growing over the first pixels scrolled
    val start = (scroll.value / len).coerceIn(0f, 1f)
    val end = if (scroll.maxValue == Int.MAX_VALUE) 0f else ((scroll.maxValue - scroll.value) / len).coerceIn(0f, 1f)
    if (start == 0f && end == 0f) {
        drawContent()
        return@drawWithContent
    }
    // the content is drawn into a layer of its own and the fade is cut out of its alpha; the layer reaches past the
    // other axis (as far as a scrolling row lets its content draw), so a pressed chip's bounce or a shadow isn't cut
    val over = 30.dp.toPx()
    val w = size.width
    val h = size.height
    drawIntoCanvas { canvas ->
        canvas.saveLayer(if (horizontal) Rect(0f, -over, w, h + over) else Rect(-over, 0f, w + over, h), Paint())
        drawContent()
        fun strip(from: Float, to: Float, a0: Float, a1: Float) {
            val colors = listOf(Color.Black.copy(alpha = a0), Color.Black.copy(alpha = a1))
            if (horizontal) drawRect(Brush.horizontalGradient(colors, from, to), Offset(from, -over), Size(to - from, h + over * 2), blendMode = BlendMode.DstIn)
            else drawRect(Brush.verticalGradient(colors, from, to), Offset(-over, from), Size(w + over * 2, to - from), blendMode = BlendMode.DstIn)
        }
        if (start > 0f) strip(0f, len, 1f - start, 1f)
        if (end > 0f) {
            val full = if (horizontal) w else h
            strip(full - len, full, 1f, 1f - end)
        }
        canvas.restore()
    }
}

/** A row that scrolls sideways (chips, steps of a value): it fades at the edge it continues past. */
@Composable
fun Modifier.sideScroll(scroll: ScrollState = rememberScrollState()): Modifier = fadingEdges(scroll, horizontal = true).horizontalScroll(scroll)

/**
 * Keeps the item of a scrolling row (or column) that is [active] in view, with some [air] around it: what is chosen is
 * never out of sight. (Every scrolling container around it follows – for a row inside a scrolling page, think twice.)
 */
@Composable
fun Modifier.inViewWhen(active: Boolean, air: Dp = 28.dp, horizontal: Boolean = true): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val airPx = with(LocalDensity.current) { air.toPx() }
    LaunchedEffect(active, size) {
        if (active && size != IntSize.Zero) requester.bringIntoView(
            if (horizontal) Rect(-airPx, 0f, size.width + airPx, size.height.toFloat())
            else Rect(0f, -airPx, size.width.toFloat(), size.height + airPx)
        )
    }
    return bringIntoViewRequester(requester).onSizeChanged { size = it }
}

@Composable
fun KlippTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = K.Primary,
            onPrimary = K.OnPrimary,
            primaryContainer = K.PrimaryContainer,
            onPrimaryContainer = K.PrimarySoft,
            secondaryContainer = K.PrimaryContainer,
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
