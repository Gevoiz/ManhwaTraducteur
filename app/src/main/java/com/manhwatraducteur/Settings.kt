package com.manhwatraducteur

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage

data class Lang(val code: String, val label: String)

val SOURCE_LANGS = listOf(
    Lang(TranslateLanguage.ENGLISH, "Anglais"),
    Lang(TranslateLanguage.KOREAN, "Coréen"),
    Lang(TranslateLanguage.JAPANESE, "Japonais"),
    Lang(TranslateLanguage.CHINESE, "Chinois"),
)

val TARGET_LANGS = listOf(
    Lang(TranslateLanguage.FRENCH, "Français"),
    Lang(TranslateLanguage.ENGLISH, "Anglais"),
    Lang(TranslateLanguage.SPANISH, "Espagnol"),
    Lang(TranslateLanguage.GERMAN, "Allemand"),
    Lang(TranslateLanguage.ITALIAN, "Italien"),
    Lang(TranslateLanguage.PORTUGUESE, "Portugais"),
)

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sourceLang: String
        get() = sp.getString("source", TranslateLanguage.ENGLISH)!!
        set(v) = sp.edit().putString("source", v).apply()

    var targetLang: String
        get() = sp.getString("target", TranslateLanguage.FRENCH)!!
        set(v) = sp.edit().putString("target", v).apply()

    /** Efface la traduction dès que le contenu de l'écran change (défilement). */
    var autoClear: Boolean
        get() = sp.getBoolean("autoClear", true)
        set(v) = sp.edit().putBoolean("autoClear", v).apply()

    /** Retraduit automatiquement quand l'écran redevient immobile. */
    var autoMode: Boolean
        get() = sp.getBoolean("autoMode", false)
        set(v) = sp.edit().putBoolean("autoMode", v).apply()
}
