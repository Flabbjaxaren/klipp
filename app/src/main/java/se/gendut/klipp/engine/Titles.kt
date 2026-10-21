package se.gendut.klipp.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.annotation.StringRes
import se.gendut.klipp.R
import se.gendut.klipp.Str
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Title templates (from the design draft). [key] is persisted; [label] is the display name. */
enum class TitleTemplate(val key: String, @StringRes private val labelRes: Int) {
    LOWER_THIRD("namnskylt", R.string.titles_template_lower_third),
    CHAPTER("kapitel", R.string.titles_template_chapter),
    ROLL("rulltext", R.string.titles_template_roll),
    FREE("fri", R.string.titles_template_free);

    val label: String get() = Str.t(labelRes)
}

/** How the title enters the frame. [key] is persisted; [label] is the display name. */
enum class TitleAnim(val key: String, @StringRes private val labelRes: Int) {
    SLIDE("glid", R.string.titles_anim_slide),
    BOUNCE("studs", R.string.titles_anim_bounce),
    SCALE("skala", R.string.titles_anim_scale),
    FADE("tona", R.string.titles_anim_fade),
    NONE("ingen", R.string.titles_anim_none);

    val label: String get() = Str.t(labelRes)
}

/** Template + animation; stored as `klipp:mall=…;in=…` in the text layer's name in the engine. */
data class TitleLook(val template: TitleTemplate = TitleTemplate.FREE, val anim: TitleAnim = TitleAnim.NONE, val font: String = "Inter") {
    fun encode() = "klipp:mall=${template.key};in=${anim.key}" + if (font != "Inter") ";font=${font.replace(Regex("[;=]"), "_")}" else ""

    companion object {
        fun parse(name: String): TitleLook {
            if (!name.startsWith("klipp:")) return TitleLook()
            val kv = name.removePrefix("klipp:").split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return TitleLook(
                TitleTemplate.entries.firstOrNull { it.key == kv["mall"] } ?: TitleTemplate.FREE,
                TitleAnim.entries.firstOrNull { it.key == kv["in"] } ?: TitleAnim.NONE,
                kv["font"]?.takeIf { it.isNotBlank() } ?: "Inter",
            )
        }
    }
}

/**
 * Where the title sits and how it moves at time t (seconds into the clip), in NDC (−1..1, y up).
 * The same values are used by the Media3 overlay and by the in-app preview.
 *
 * @param anchor the point in the image that is scaled/rotated around (NDC in the overlay's own image)
 * @param dx offset in NDC
 */
data class TitlePose(val anchorX: Float, val anchorY: Float, val dx: Float, val dy: Float, val scale: Float, val alpha: Float)

object TitleMotion {
    const val IN = 0.8
    const val OUT = 0.35

    /** Damped spring 0 → 1 (like the Compose springs in the UI). */
    private fun spring(p: Double, damping: Double, freq: Double): Double =
        1 - exp(-damping * p) * cos(freq * p)

    fun pose(look: TitleLook, t: Double, dur: Double, w: Int, h: Int, rollHeight: Int = h): TitlePose {
        val anchor = anchorOf(look.template, w, h)
        val outA = if (dur > IN + OUT && t > dur - OUT) ((dur - t) / OUT).coerceIn(0.0, 1.0).toFloat() else 1f
        if (look.template == TitleTemplate.ROLL) {
            // the credits roll glides from below the frame to above it over the whole clip
            val p = (t / max(dur, 0.01)).coerceIn(0.0, 1.0).toFloat()
            val travel = 2f + 2f * rollHeight / h
            return TitlePose(0f, 1f, 0f, -1f + p * travel - 1f, 1f, 1f)
        }
        val p = (t / IN).coerceIn(0.0, 1.0)
        return when (look.anim) {
            TitleAnim.NONE -> TitlePose(anchor.first, anchor.second, 0f, 0f, 1f, outA)
            TitleAnim.FADE -> TitlePose(anchor.first, anchor.second, 0f, 0f, 1f, min(1f, (p * 1.6).toFloat()) * outA)
            TitleAnim.SLIDE -> {
                val e = 1 - (1 - p) * (1 - p) * (1 - p)
                TitlePose(anchor.first, anchor.second, (-(1 - e) * 0.5).toFloat(), 0f, 1f, min(1f, (p * 2).toFloat()) * outA)
            }
            TitleAnim.BOUNCE -> {
                val e = spring(p * 1.0, 5.0, 11.0)
                TitlePose(anchor.first, anchor.second, 0f, (-(1 - e) * 0.3).toFloat(), 1f, min(1f, (p * 3).toFloat()) * outA)
            }
            TitleAnim.SCALE -> {
                val e = spring(p, 6.0, 9.0)
                TitlePose(anchor.first, anchor.second, 0f, 0f, (0.4 + 0.6 * e).toFloat(), min(1f, (p * 3).toFloat()) * outA)
            }
        }
    }

    /** The template's "corner" in the frame (where it grows from), NDC. */
    fun anchorOf(t: TitleTemplate, w: Int, h: Int): Pair<Float, Float> = when (t) {
        TitleTemplate.LOWER_THIRD -> (0.06f * 2 - 1) to (1 - 0.86f * 2)
        else -> 0f to 0f
    }
}

/** Draws titles per template. Lines: first = main text, the rest = subtitle/lines. */
object TitleArt {
    private const val ACCENT = 0xFFFFB77C.toInt()

    fun rollHeight(t: TextSnap, w: Int, h: Int): Int {
        val lines = t.text.split('\n').size
        val size = t.size.toFloat().coerceIn(8f, h * 0.2f)
        return max(h, (lines * size * 1.5f + size).toInt())
    }

    fun draw(t: TextSnap, w: Int, h: Int): Bitmap {
        val look = t.look
        val bh = if (look.template == TitleTemplate.ROLL) rollHeight(t, w, h) else h
        val bmp = Bitmap.createBitmap(w, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val cv = Canvas(bmp)
        val c = t.color
        val fill = Color.argb((c[3] * 255).toInt(), (c[0] * 255).toInt(), (c[1] * 255).toInt(), (c[2] * 255).toInt())
        val size = t.size.toFloat().coerceIn(4f, h * 2f)
        val lines = t.text.split('\n')
        val main = lines.firstOrNull().orEmpty()
        val rest = lines.drop(1)
        fun paint(sz: Float, bold: Boolean, color: Int, align: Paint.Align) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sz
            typeface = Typeface.create(Fonts.typeface(look.font), if (bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = align
            this.color = color
        }
        when (look.template) {
            TitleTemplate.LOWER_THIRD -> {
                // lower third: dark plate at bottom left with an accent edge, name + role
                val pMain = paint(size, true, fill, Paint.Align.LEFT)
                val pSub = paint(size * 0.58f, false, Color.argb(235, 226, 226, 233), Paint.Align.LEFT)
                val pad = size * 0.38f
                val tw = max(pMain.measureText(main), rest.maxOfOrNull { pSub.measureText(it) } ?: 0f)
                val th = size * 1.05f + rest.size * pSub.textSize * 1.3f
                val left = w * 0.06f
                val bottom = h * 0.86f
                val box = RectF(left, bottom - th - pad * 2, left + tw + pad * 2 + size * 0.18f, bottom)
                cv.drawRoundRect(box, size * 0.16f, size * 0.16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) })
                cv.drawRoundRect(RectF(box.left, box.top, box.left + size * 0.12f, box.bottom), size * 0.06f, size * 0.06f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
                val x = box.left + size * 0.18f + pad
                var y = box.top + pad + size * 0.85f
                cv.drawText(main, x, y, pMain)
                for (r in rest) { y += pSub.textSize * 1.3f; cv.drawText(r, x, y, pSub) }
            }
            TitleTemplate.CHAPTER -> {
                // chapter card: centered, soft dark shade behind, thin accent line between heading and subheading
                val shade = Paint().apply {
                    shader = LinearGradient(0f, h * 0.25f, 0f, h * 0.75f, intArrayOf(0, Color.argb(130, 0, 0, 0), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                }
                cv.drawRect(0f, h * 0.25f, w.toFloat(), h * 0.75f, shade)
                val pMain = paint(size * 1.15f, true, fill, Paint.Align.CENTER).apply { letterSpacing = 0.04f; setShadowLayer(size * 0.08f, 0f, size * 0.03f, Color.argb(140, 0, 0, 0)) }
                val pSub = paint(size * 0.55f, false, Color.argb(235, 226, 226, 233), Paint.Align.CENTER).apply { letterSpacing = 0.2f }
                val cy = h / 2f
                cv.drawText(main, w / 2f, cy - size * 0.1f, pMain)
                val lw = min(pMain.measureText(main) * 0.5f, w * 0.3f)
                cv.drawRect(w / 2f - lw / 2, cy + size * 0.22f, w / 2f + lw / 2, cy + size * 0.27f, Paint().apply { color = ACCENT })
                var y = cy + size * 0.95f
                for (r in rest) { cv.drawText(r.uppercase(), w / 2f, y, pSub); y += pSub.textSize * 1.4f }
            }
            TitleTemplate.ROLL -> {
                // credits roll: centered lines; lines ending in ":" become small headings
                var y = size
                for (r in lines) {
                    val head = r.trimEnd().endsWith(":")
                    val p = if (head) paint(size * 0.6f, false, Color.argb(220, 255, 183, 124), Paint.Align.CENTER) else paint(size, true, fill, Paint.Align.CENTER)
                    p.setShadowLayer(size * 0.06f, 0f, size * 0.03f, Color.argb(140, 0, 0, 0))
                    cv.drawText(r, w / 2f, y, p)
                    y += size * 1.5f
                }
            }
            TitleTemplate.FREE -> {
                val p = paint(size, true, fill, Paint.Align.CENTER).apply { setShadowLayer(textSize * 0.06f, 0f, textSize * 0.03f, Color.argb(140, 0, 0, 0)) }
                val x = (t.x ?: (w / 2.0)).toFloat()
                var y = (t.y ?: (h / 2.0)).toFloat()
                for (line in lines) {
                    cv.drawText(line, x, y, p)
                    y += p.textSize * 1.2f
                }
            }
        }
        return bmp
    }
}
