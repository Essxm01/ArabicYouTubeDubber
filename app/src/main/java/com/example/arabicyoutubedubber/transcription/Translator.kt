package com.example.arabicyoutubedubber.transcription

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException

class Translator {

    private var translatorClient: com.google.mlkit.nl.translate.Translator? = null
    private var isInitialized = false

    /**
     * Prepares the translator. Downloads the English-Arabic translation model
     * if not already downloaded.
     */
    suspend fun initialize(onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (isInitialized) {
            onProgress(100)
            return@withContext
        }

        onProgress(0)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()

        val client = Translation.getClient(options)
        val model = TranslateRemoteModel.Builder(TranslateLanguage.ARABIC).build()
        val modelManager = RemoteModelManager.getInstance()

        // Check if model is downloaded
        val isDownloaded = modelManager.isModelDownloaded(model).await()

        if (!isDownloaded) {
            onProgress(10)
            val conditions = DownloadConditions.Builder().build()
            try {
                modelManager.download(model, conditions).await()
            } catch (e: Exception) {
                throw IOException("Failed to download translation model: ${e.message}", e)
            }
        }

        translatorClient = client
        isInitialized = true
        onProgress(100)
    }

    /**
     * Translates a single string of text from English to Arabic.
     */
    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        check(isInitialized) { "Translator not initialized. Call initialize() first." }
        val client = translatorClient ?: throw IOException("Translator client is null")
        try {
            return@withContext client.translate(text).await()
        } catch (e: Exception) {
            throw IOException("Translation failed: ${e.message}", e)
        }
    }

    /**
     * Translates multiple segments while preserving their original timestamps.
     */
    suspend fun translateSegments(
        segments: List<TranscriptionSegment>
    ): List<TranscriptionSegment> = withContext(Dispatchers.IO) {
        check(isInitialized) { "Translator not initialized. Call initialize() first." }
        val client = translatorClient ?: throw IOException("Translator client is null")

        val translatedSegments = mutableListOf<TranscriptionSegment>()
        for (segment in segments) {
            val translatedText = if (segment.text.isBlank()) {
                ""
            } else {
                try {
                    client.translate(segment.text).await()
                } catch (e: Exception) {
                    // Fallback to original text if translation fails for a single segment
                    segment.text
                }
            }
            translatedSegments.add(
                TranscriptionSegment(
                    text = translatedText,
                    startMs = segment.startMs,
                    endMs = segment.endMs
                )
            )
        }
        return@withContext translatedSegments
    }

    /**
     * Closes the translator client and releases resources.
     */
    fun close() {
        translatorClient?.close()
        translatorClient = null
        isInitialized = false
    }
}
