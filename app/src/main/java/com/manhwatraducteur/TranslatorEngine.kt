package com.manhwatraducteur

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/** Traduction 100 % sur l'appareil (ML Kit). Le modèle de langue est téléchargé au premier usage. */
class TranslatorEngine(val source: String, val target: String) {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
    )
    var ready = source == target
        private set

    suspend fun ensureModel() {
        if (ready) return
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        ready = true
    }

    suspend fun translate(text: String): String =
        if (source == target) text else translator.translate(text).await()

    fun close() = translator.close()
}
