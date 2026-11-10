package com.manhwatraducteur

import android.graphics.Bitmap
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

interface TranslationEngine {
    val source: String
    val target: String

    /** Traduit tous les textes d'une page d'un coup. [screenshot] sert de contexte aux moteurs IA. */
    suspend fun translateAll(texts: List<String>, screenshot: Bitmap?): List<String>

    fun close()
}

/** Traduction 100 % sur l'appareil (ML Kit). Le modèle de langue est téléchargé au premier usage. */
class MlKitEngine(override val source: String, override val target: String) : TranslationEngine {

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

    override suspend fun translateAll(texts: List<String>, screenshot: Bitmap?): List<String> {
        if (source == target) return texts
        ensureModel()
        return coroutineScope { texts.map { async { translator.translate(it).await() } }.awaitAll() }
    }

    override fun close() = translator.close()
}
