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

fun languageName(code: String): String =
    (SOURCE_LANGS + TARGET_LANGS).firstOrNull { it.code == code }?.label?.lowercase() ?: code

const val ENGINE_MLKIT = "mlkit"
const val ENGINE_GEMINI = "gemini"
const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sourceLang: String
        get() = sp.getString("source", TranslateLanguage.ENGLISH)!!
        set(v) = sp.edit().putString("source", v).apply()

    var targetLang: String
        get() = sp.getString("target", TranslateLanguage.FRENCH)!!
        set(v) = sp.edit().putString("target", v).apply()

    /** Les traductions suivent la page quand on fait défiler (sinon elles s'effacent). */
    var followScroll: Boolean
        get() = sp.getBoolean("followScroll", true)
        set(v) = sp.edit().putBoolean("followScroll", v).apply()

    /** Retraduit automatiquement quand l'écran redevient immobile. */
    var autoMode: Boolean
        get() = sp.getBoolean("autoMode", false)
        set(v) = sp.edit().putBoolean("autoMode", v).apply()

    var engine: String
        get() = sp.getString("engine", ENGINE_MLKIT)!!
        set(v) = sp.edit().putString("engine", v).apply()

    var geminiKey: String
        get() = sp.getString("geminiKey", "")!!
        set(v) = sp.edit().putString("geminiKey", v.trim()).apply()

    var geminiModel: String
        get() = sp.getString("geminiModel", DEFAULT_GEMINI_MODEL)!!
        set(v) = sp.edit().putString("geminiModel", v.trim()).apply()
}
