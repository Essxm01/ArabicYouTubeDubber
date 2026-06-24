package com.example.arabicyoutubedubber.transcription

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume

class ArabicTTS {

    companion object {
        private const val TAG = "ArabicTTS"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    /**
     * Initializes the Android TextToSpeech engine with Arabic language support.
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.Main) {
        if (isInitialized) return@withContext true

        suspendCancellableCoroutine { continuation ->
            var ttsInstance: TextToSpeech? = null
            ttsInstance = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val langResult = ttsInstance?.setLanguage(Locale("ar"))
                    if (langResult == TextToSpeech.LANG_MISSING_DATA || 
                        langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "Arabic language is not supported on this TTS engine.")
                        continuation.resume(false)
                    } else {
                        tts = ttsInstance
                        isInitialized = true
                        Log.i(TAG, "TTS initialized successfully in Arabic.")
                        continuation.resume(true)
                    }
                } else {
                    Log.e(TAG, "TTS initialization failed (status: $status)")
                    continuation.resume(false)
                }
            }

            continuation.invokeOnCancellation {
                if (!isInitialized) {
                    ttsInstance.shutdown()
                }
            }
        }
    }

    /**
     * Synthesizes Arabic speech for each segment, adjusts the tempo to match timings,
     * and overlays them into a single master audio track.
     *
     * @param segments Translated Arabic segments with start/end timestamps.
     * @param totalDurationMs The duration of the original video/audio track in milliseconds.
     * @param outputWavFile The target file path to save the master dubbed WAV track.
     * @param cacheDir Cache directory for temporary audio files.
     */
    suspend fun generateDubbedAudioTrack(
        segments: List<TranscriptionSegment>,
        totalDurationMs: Long,
        outputWavFile: File,
        cacheDir: File
    ) = withContext(Dispatchers.IO) {
        check(isInitialized) { "ArabicTTS not initialized. Call initialize() first." }
        
        Log.i(TAG, "Generating master dubbed audio track for duration: ${totalDurationMs}ms")
        val sampleRate = 16000
        val totalSamples = ((totalDurationMs * sampleRate) / 1000).toInt()
        val masterBuffer = FloatArray(totalSamples)

        val tempDir = File(cacheDir, "tts_temp").apply { mkdirs() }
        tempDir.listFiles()?.forEach { it.delete() } // Clean up

        for (i in segments.indices) {
            val segment = segments[i]
            if (segment.text.isBlank()) continue

            val utteranceId = "utterance_$i"
            val tempFile = File(tempDir, "${utteranceId}_raw.wav")
            
            // 1. Synthesize segment text to raw WAV
            Log.d(TAG, "Synthesizing segment $i: '${segment.text}'")
            val success = synthesizeSegmentToFile(segment.text, tempFile, utteranceId)
            if (!success || !tempFile.exists() || tempFile.length() <= 44) {
                Log.w(TAG, "Failed to synthesize segment $i, skipping.")
                continue
            }

            // 2. Measure raw duration
            val rawDurationMs = getAudioDurationMs(tempFile)
            val targetDurationMs = (segment.endMs - segment.startMs).coerceAtLeast(100L)
            
            Log.d(TAG, "Segment $i rawDuration: ${rawDurationMs}ms, targetDuration: ${targetDurationMs}ms")

            // 3. Speed adjust using FFmpeg if needed
            val segmentWavFile = if (rawDurationMs > 0 && Math.abs(rawDurationMs - targetDurationMs) > 150) {
                val speedFactor = rawDurationMs.toFloat() / targetDurationMs.toFloat()
                val adjustedFile = File(tempDir, "${utteranceId}_adjusted.wav")
                
                val filter = getAtempoFilter(speedFactor)
                val cmd = "-i \"${tempFile.absolutePath}\" -filter:a \"$filter\" -y \"${adjustedFile.absolutePath}\""
                
                Log.d(TAG, "Adjusting speed for segment $i: speedFactor=$speedFactor")
                val session = FFmpegKit.execute(cmd)
                if (ReturnCode.isSuccess(session.returnCode) && adjustedFile.exists()) {
                    adjustedFile
                } else {
                    Log.w(TAG, "Speed adjustment failed for segment $i, using raw audio.")
                    tempFile
                }
            } else {
                tempFile
            }

            // 4. Mix segment into master buffer
            try {
                val segmentSamples = readWavToFloatArray(segmentWavFile.absolutePath)
                val startSampleIndex = ((segment.startMs * sampleRate) / 1000).toInt()
                
                for (s in segmentSamples.indices) {
                    val targetIndex = startSampleIndex + s
                    if (targetIndex >= masterBuffer.size) break
                    // Mix: just add the samples (prevent clipping by keeping them inside range later)
                    masterBuffer[targetIndex] = segmentSamples[s]
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read or mix segment $i: ${e.message}")
            }
        }

        // 5. Write master buffer to output WAV file
        Log.i(TAG, "Writing master audio track to WAV: ${outputWavFile.absolutePath}")
        writeFloatArrayToWav(masterBuffer, outputWavFile)
        
        // Clean up temp files
        tempDir.deleteRecursively()
        Log.i(TAG, "Master audio track generation completed.")
    }

    private suspend fun synthesizeSegmentToFile(
        text: String,
        outputFile: File,
        utteranceId: String
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val ttsEngine = tts ?: run {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        val listener = object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    continuation.resume(true)
                }
            }
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    continuation.resume(false)
                }
            }
        }

        ttsEngine.setOnUtteranceProgressListener(listener)

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val result = ttsEngine.synthesizeToFile(text, params, outputFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            continuation.resume(false)
        }
    }

    private fun getAudioDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract duration of ${file.name}: ${e.message}")
            0L
        } finally {
            retriever.release()
        }
    }

    private fun getAtempoFilter(factor: Float): String {
        // Clamp factor to avoid ridiculous values
        val clamped = factor.coerceIn(0.25f, 4.0f)
        return if (clamped < 0.5f) {
            "atempo=0.5,atempo=${clamped / 0.5f}"
        } else if (clamped > 2.0f) {
            "atempo=2.0,atempo=${clamped / 2.0f}"
        } else {
            "atempo=$clamped"
        }
    }

    private fun readWavToFloatArray(filePath: String): FloatArray {
        val file = File(filePath)
        if (!file.exists()) throw IOException("File not found: $filePath")
        val bytes = file.readBytes()
        if (bytes.size < 44) throw IOException("WAV too small: $filePath")
        
        // Find data chunk
        var dataOffset = 12
        var dataSize = 0
        while (dataOffset < bytes.size - 8) {
            val chunkId = String(bytes, dataOffset, 4)
            val chunkSize = ByteBuffer.wrap(bytes, dataOffset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "data") {
                dataOffset += 8
                dataSize = chunkSize
                break
            }
            dataOffset += 8 + chunkSize
        }
        
        if (dataSize == 0) throw IOException("No data chunk found in $filePath")
        
        val sampleCount = dataSize / 2
        val floatArray = FloatArray(sampleCount)
        val buffer = ByteBuffer.wrap(bytes, dataOffset, dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            
        for (i in 0 until sampleCount) {
            floatArray[i] = buffer.short.toFloat() / 32768.0f
        }
        return floatArray
    }

    private fun writeFloatArrayToWav(samples: FloatArray, outputFile: File) {
        val sampleRate = 16000
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val subChunk2Size = samples.size * 2
        val chunkSize = 36 + subChunk2Size

        val buffer = ByteBuffer.allocate(44 + subChunk2Size)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(chunkSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort()) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(subChunk2Size)

        for (sample in samples) {
            val shortSample = (sample.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            buffer.putShort(shortSample)
        }

        outputFile.writeBytes(buffer.array())
    }

    /**
     * Shuts down the TTS engine when done.
     */
    fun shutdown() {
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
