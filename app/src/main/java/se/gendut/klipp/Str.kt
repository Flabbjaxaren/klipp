package se.gendut.klipp

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * String resources outside composition (engine messages, toasts, labels built in plain functions). Inside a composable,
 * use stringResource / pluralStringResource instead. English is the default (res/values), Swedish in res/values-sv.
 */
object Str {
    @SuppressLint("StaticFieldLeak")
    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    /** The application (also in tests, where no activity has called [init]). */
    private val ctx: Context
        get() = app ?: (Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context).also { app = it }

    fun t(@StringRes id: Int, vararg args: Any): String = ctx.getString(id, *args)

    fun p(@PluralsRes id: Int, count: Int, vararg args: Any): String = ctx.resources.getQuantityString(id, count, *args)

    /**
     * The display name for a value that is saved in projects under its original (Swedish) name – the first node's
     * label, the built-in LUT and font names. What is saved never changes; only what is shown follows the language.
     */
    fun name(stored: String): String = when (stored) {
        "Primär" -> t(R.string.names_node_primary)
        "Blekt" -> t(R.string.names_lut_bleach)
        "Sans fet" -> t(R.string.names_font_sans_bold)
        "Smal" -> t(R.string.names_font_condensed)
        "Lätt" -> t(R.string.names_font_light)
        "Handskrift" -> t(R.string.names_font_handwriting)
        "Avslappnad" -> t(R.string.names_font_casual)
        else -> stored
    }
}
