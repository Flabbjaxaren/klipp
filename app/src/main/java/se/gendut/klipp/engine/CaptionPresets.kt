package se.gendut.klipp.engine

import org.json.JSONObject
import kotlin.math.abs

/**
 * The caption styles to choose from (Standard, Big social, Text plate) – each a *look* only: font, size, colors,
 * outline, background plate and whether the spoken word is marked. A preset never touches the captions' text, times or
 * placement, nor how many words are shown at a time or how much the marked word grows (those stay the user's).
 *
 * Choosing a preset over a look of the track's own keeps that look in the track ([CaptionStyle.savedLook]), so it
 * can be chosen again ("Own"): turning Big social off brings back what was there before. A change by hand while a
 * preset is in use makes the look the track's own (it no longer matches a preset).
 */
object CaptionPresets {
    enum class Kind { STANDARD, SOCIAL, PLATE }

    /** The engine's style keys (and the word color) a look is made of. */
    val LOOK_KEYS = listOf("font", "size", "color", "background", "backgroundColor", "outline", "outlineColor", "lineSpacing", "wordColor")

    /** What [k] sets ([tall]: a vertical sequence – the big sizes are for the frame's height). */
    fun look(k: Kind, tall: Boolean): Map<String, Any> = when (k) {
        Kind.STANDARD -> mapOf(
            "font" to "Inter", "size" to 54.0, "color" to "#ffffffff", "background" to true, "backgroundColor" to "#000000bf",
            "outline" to 0.0, "outlineColor" to "#000000ff", "lineSpacing" to 1.25, "wordColor" to "",
        )
        Kind.SOCIAL -> mapOf(
            "font" to "Sans fet", "size" to (if (tall) 64.0 else 104.0), "color" to "#ffffffff", "background" to false, "backgroundColor" to "#000000bf",
            "outline" to 6.0, "outlineColor" to "#000000ff", "lineSpacing" to 1.25, "wordColor" to "#ffd400ff",
        )
        Kind.PLATE -> mapOf(
            "font" to "Sans fet", "size" to (if (tall) 50.0 else 58.0), "color" to "#111318ff", "background" to true, "backgroundColor" to "#ffffffff",
            "outline" to 0.0, "outlineColor" to "#000000ff", "lineSpacing" to 1.25, "wordColor" to "",
        )
    }

    /** The preset [st] is (in either orientation's size), or null: a look of the track's own. */
    fun of(st: CaptionStyle): Kind? = Kind.entries.firstOrNull { k -> matches(st, look(k, true)) || matches(st, look(k, false)) }

    /** The look of [st] as values for the style (what [look] gives for a preset) – kept while a preset is in use. */
    fun lookOf(st: CaptionStyle): Map<String, Any> = mapOf(
        "font" to st.font, "size" to st.size.toDouble(), "color" to CaptionStyle.hex(st.color), "background" to st.background,
        "backgroundColor" to CaptionStyle.hex(st.backgroundColor), "outline" to st.outline.toDouble(), "outlineColor" to CaptionStyle.hex(st.outlineColor),
        "lineSpacing" to st.lineSpacing.toDouble(), "wordColor" to (st.wordColor?.let { CaptionStyle.hex(it) } ?: ""),
    )

    /** A look as it is kept in the track's name: JSON, base64url (no ';' or '=' – the tag's separators). */
    fun encode(look: Map<String, Any>): String {
        val o = JSONObject()
        for (k in LOOK_KEYS) look[k]?.let { o.put(k, it) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray())
    }

    /** A kept look back as style values; null if it can't be read. */
    fun decode(s: String): Map<String, Any>? = runCatching {
        val o = JSONObject(String(java.util.Base64.getUrlDecoder().decode(s)))
        LOOK_KEYS.filter { o.has(it) }.associateWith { o.get(it) }
    }.getOrNull()

    private fun matches(st: CaptionStyle, l: Map<String, Any>): Boolean {
        fun num(k: String) = (l[k] as Number).toFloat()
        fun col(k: String) = CaptionStyle.parseColor(l[k] as String, 0)
        val word = (l["wordColor"] as String).takeIf { it.isNotEmpty() }?.let { CaptionStyle.parseColor(it, 0) }
        return st.font == l["font"] && abs(st.size - num("size")) < 0.5f && st.color == col("color") && st.background == l["background"] &&
            (!st.background || st.backgroundColor == col("backgroundColor")) && abs(st.outline - num("outline")) < 0.05f &&
            (st.outline < 0.05f || st.outlineColor == col("outlineColor")) && abs(st.lineSpacing - num("lineSpacing")) < 0.01f && st.wordColor == word
    }
}
