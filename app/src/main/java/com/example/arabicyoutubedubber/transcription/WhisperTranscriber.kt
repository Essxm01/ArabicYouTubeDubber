package com.example.arabicyoutubedubber.transcription

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Callback interface for transcription progress updates.
 */
interface OnTranscriptionProgressListener {
    /** Called with progress percentage (0–100). */
    fun onProgress(progress: Int)
}

/**
 * Represents a single transcription segment with timing info.
 */
data class TranscriptionSegment(
    val text: String,
    val startMs: Long,  // start time in milliseconds
    val endMs: Long     // end time in milliseconds
)

/**
 * Helper class representing a chunk of audio.
 */
data class AudioChunk(
    val samples: FloatArray,
    val startIndex: Int // offset in samples from start of original audio
)

/**
 * High-level Kotlin wrapper around whisper.cpp via JNI.
 */
class WhisperTranscriber {

    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val MODELS_DIR = "models"
    }

    private var contextPtr: Long = 0L
    private var isInitialized = false
    private var currentModelPath: String? = null

    // Benchmarking metrics
    var lastRtf: Float = 0.0f
        private set

    var lastProcessingTimeMs: Long = 0L
        private set

    /**
     * Initialize whisper by loading a model.
     * Reuses context if already initialized with the same model.
     */
    suspend fun initialize(
        context: Context,
        modelName: String = "ggml-base.en-q5_0.bin",
        onProgress: OnTranscriptionProgressListener? = null
    ) = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, "$MODELS_DIR/$modelName")

        if (isInitialized && currentModelPath == modelFile.absolutePath) {
            Log.i(TAG, "Whisper context already initialized with model $modelName, reusing context.")
            onProgress?.onProgress(100)
            return@withContext
        }

        if (isInitialized) {
            Log.i(TAG, "Releasing existing context for model change")
            release()
        }

        // Copy model from assets to internal storage (if not cached)
        if (!modelFile.exists()) {
            val assetPath = "$MODELS_DIR/$modelName"
            var assetCopied = false
            try {
                // Check if file exists in assets
                context.assets.open(assetPath).close()
                Log.i(TAG, "Copying model from assets: $modelName")
                copyModelFromAssets(context, modelName, modelFile, onProgress)
                assetCopied = true
            } catch (e: IOException) {
                Log.w(TAG, "Model '$assetPath' not found in assets. Falling back to Hugging Face download.")
            }

            if (!assetCopied) {
                val hfUrl = "https://huggingface.co/ggml-org/whisper.cpp/resolve/main/$modelName"
                downloadModelFromUrl(hfUrl, modelFile, onProgress)
            }
        } else {
            Log.i(TAG, "Model already cached at: ${modelFile.absolutePath}")
            onProgress?.onProgress(100)
        }

        // Load model into whisper context
        contextPtr = WhisperLib.initContext(modelFile.absolutePath)
        if (contextPtr == 0L) {
            throw IOException("Failed to initialize whisper context from: ${modelFile.absolutePath}")
        }
        currentModelPath = modelFile.absolutePath
        isInitialized = true
        Log.i(TAG, "Whisper initialized successfully with model: $modelName")
    }

    /**
     * Transcribe a WAV audio file to English text.
     */
    suspend fun transcribeAudioFile(
        audioFilePath: String,
        language: String = "en",
        numThreads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6),
        onProgress: OnTranscriptionProgressListener? = null
    ): String = withContext(Dispatchers.IO) {
        val segments = transcribeWithTimestamps(audioFilePath, language, numThreads, onProgress)
        return@withContext segments.joinToString(" ") { it.text }
    }

    /**
     * Transcribe and return individual segments with timestamps.
     */
    suspend fun transcribeWithTimestamps(
        audioFilePath: String,
        language: String = "en",
        numThreads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6),
        onProgress: OnTranscriptionProgressListener? = null
    ): List<TranscriptionSegment> = withContext(Dispatchers.IO) {
        check(isInitialized) { "WhisperTranscriber not initialized. Call initialize() first." }

        onProgress?.onProgress(0)

        // Read WAV file into float array
        val audioData = readWavToFloatArray(audioFilePath)
        val sampleCount = audioData.size
        val durationSeconds = sampleCount / 16000f

        // Limit audio duration to 30 minutes (28,800,000 samples at 16kHz)
        val maxSamplesLimit = 30 * 60 * 16000
        if (sampleCount > maxSamplesLimit) {
            throw IllegalArgumentException(
                "Audio file duration (%.2f min) exceeds maximum limit of 30 minutes."
                    .format(durationSeconds / 60f)
            )
        }

        Log.i(TAG, "Transcribing WAV audio file (Duration: %.2fs)".format(durationSeconds))
        val startTime = System.currentTimeMillis()

        // Split audio into chunks using Kotlin VAD
        val chunks = splitAudioIntoChunks(audioData)
        Log.i(TAG, "VAD: Split audio into ${chunks.size} chunks (10-30 seconds each)")

        val allSegments = mutableListOf<TranscriptionSegment>()

        // Inference options
        val beamSize = 1 // 1 = greedy (faster)
        val temperature = 0.0f
        val noContext = true

        for (index in chunks.indices) {
            if (!isActive) break // support coroutine cancellation

            val chunk = chunks[index]
            val chunkStartMs = (chunk.startIndex.toLong() * 1000) / 16000

            Log.d(TAG, "Processing chunk ${index + 1}/${chunks.size} (samples: ${chunk.samples.size}, startMs: $chunkStartMs)")

            // Transcribe the chunk
            val result = WhisperLib.fullTranscribe(
                contextPtr = contextPtr,
                numThreads = numThreads,
                audioData = chunk.samples,
                language = "en",
                beamSize = beamSize,
                temperature = temperature,
                noContext = noContext
            )

            if (result != 0) {
                throw IOException("Whisper JNI transcription failed on chunk $index with code: $result")
            }

            // Reset timings to prevent accumulating stats
            WhisperLib.resetTimings(contextPtr)

            // Extract segment text and adjust timestamps relative to original audio
            val segmentCount = WhisperLib.getTextSegmentCount(contextPtr)
            for (s in 0 until segmentCount) {
                val text = WhisperLib.getTextSegment(contextPtr, s)
                val t0 = WhisperLib.getTextSegmentT0(contextPtr, s) * 10 // centiseconds to ms
                val t1 = WhisperLib.getTextSegmentT1(contextPtr, s) * 10

                val globalStart = chunkStartMs + t0
                val globalEnd = chunkStartMs + t1

                allSegments.add(TranscriptionSegment(text.trim(), globalStart, globalEnd))
            }

            // Report progress (10% to 100%)
            val progressPercent = 10 + (((index + 1).toFloat() / chunks.size) * 90).toInt()
            onProgress?.onProgress(progressPercent.coerceAtMost(100))
        }

        val endTime = System.currentTimeMillis()
        val processingTimeMs = endTime - startTime
        val processingTimeSeconds = processingTimeMs / 1000f

        lastProcessingTimeMs = processingTimeMs
        lastRtf = if (durationSeconds > 0) processingTimeSeconds / durationSeconds else 0f

        Log.i(
            TAG,
            "Benchmark: Real-Time Factor (RTF) = %.3f (Processed %.2fs of audio in %.2fs)"
                .format(lastRtf, durationSeconds, processingTimeSeconds)
        )

        return@withContext allSegments
    }

    /**
     * Release native resources. Must be called when done.
     */
    fun release() {
        if (contextPtr != 0L) {
            WhisperLib.freeContext(contextPtr)
            contextPtr = 0L
            isInitialized = false
            currentModelPath = null
            Log.i(TAG, "Whisper context released")
        }
    }

    // ─── Private helpers ───

    /**
     * Splits mono float PCM audio samples into chunks of 10 to 30 seconds.
     * Chooses the split point at the quietest 100ms window in the search region.
     */
    private fun splitAudioIntoChunks(audioData: FloatArray): List<AudioChunk> {
        val chunks = mutableListOf<AudioChunk>()
        val sampleRate = 16000
        val minChunkSamples = 10 * sampleRate // 10s
        val maxChunkSamples = 30 * sampleRate // 30s
        val windowSize = 1600 // 100ms
        
        var currentStart = 0
        val totalSamples = audioData.size

        while (currentStart < totalSamples) {
            val remaining = totalSamples - currentStart
            if (remaining <= maxChunkSamples) {
                val chunkSamples = audioData.copyOfRange(currentStart, totalSamples)
                chunks.add(AudioChunk(chunkSamples, currentStart))
                break
            }

            // Find best split point in [currentStart + minChunkSamples, currentStart + maxChunkSamples]
            val searchStart = currentStart + minChunkSamples
            val searchEnd = (currentStart + maxChunkSamples).coerceAtMost(totalSamples - windowSize)

            var minEnergy = Float.MAX_VALUE
            var bestSplitIndex = currentStart + maxChunkSamples // fallback split at max boundary

            var i = searchStart
            while (i <= searchEnd) {
                var sumAbs = 0f
                for (j in 0 until windowSize) {
                    sumAbs += Math.abs(audioData[i + j])
                }
                val energy = sumAbs / windowSize
                if (energy < minEnergy) {
                    minEnergy = energy
                    bestSplitIndex = i + windowSize / 2
                }
                i += windowSize // step window by 100ms
            }

            val chunkSamples = audioData.copyOfRange(currentStart, bestSplitIndex)
            chunks.add(AudioChunk(chunkSamples, currentStart))
            currentStart = bestSplitIndex
        }

        return chunks
    }

    /**
     * Copy a model file from assets to internal storage.
     */
    private fun copyModelFromAssets(
        context: Context,
        modelName: String,
        destFile: File,
        onProgress: OnTranscriptionProgressListener?
    ) {
        destFile.parentFile?.mkdirs()
        val assetPath = "$MODELS_DIR/$modelName"
        val inputStream = context.assets.open(assetPath)

        val totalBytes = inputStream.available().toLong()
        var copiedBytes = 0L
        val buffer = ByteArray(8192)

        FileOutputStream(destFile).use { output ->
            inputStream.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    copiedBytes += bytesRead
                    if (totalBytes > 0) {
                        val progress = ((copiedBytes.toFloat() / totalBytes) * 100).toInt()
                        onProgress?.onProgress(progress.coerceAtMost(100))
                    }
                }
            }
        }
        Log.i(TAG, "Model copied: ${destFile.absolutePath} ($copiedBytes bytes)")
    }

    /**
     * Downloads a model file from a remote URL directly to internal storage with progress reporting.
     */
    private fun downloadModelFromUrl(
        urlString: String,
        destFile: File,
        onProgress: OnTranscriptionProgressListener?
    ) {
        destFile.parentFile?.mkdirs()
        val tempFile = File(destFile.absolutePath + ".tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        Log.i(TAG, "Downloading model from: $urlString")
        val url = java.net.URL(urlString)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.connect()

        if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
            throw IOException("Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
        }

        val totalBytes = connection.contentLengthLong
        var downloadedBytes = 0L
        val buffer = ByteArray(8192)

        FileOutputStream(tempFile).use { output ->
            connection.inputStream.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        val progress = ((downloadedBytes.toFloat() / totalBytes) * 100).toInt()
                        onProgress?.onProgress(progress.coerceAtMost(100))
                    }
                }
            }
        }

        if (tempFile.renameTo(destFile)) {
            Log.i(TAG, "Model downloaded successfully: ${destFile.absolutePath} ($downloadedBytes bytes)")
        } else {
            throw IOException("Failed to rename temporary download file to destination model file.")
        }
    }

    /**
     * Read a 16kHz 16-bit mono WAV file into a normalized FloatArray.
     */
    private fun readWavToFloatArray(filePath: String): FloatArray {
        val file = File(filePath)
        if (!file.exists()) {
            throw IOException("Audio file not found: $filePath")
        }

        val bytes = file.readBytes()

        if (bytes.size < 44) {
            throw IOException("File too small to be a valid WAV: ${bytes.size} bytes")
        }

        val riff = String(bytes, 0, 4)
        val wave = String(bytes, 8, 4)
        if (riff != "RIFF" || wave != "WAVE") {
            throw IOException("Not a valid WAV file (header: $riff/$wave)")
        }

        // Find "data" chunk
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

        if (dataSize == 0) {
            throw IOException("Could not find 'data' chunk in WAV file")
        }

        val sampleCount = dataSize / 2
        val floatArray = FloatArray(sampleCount)
        val buffer = ByteBuffer.wrap(bytes, dataOffset, dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until sampleCount) {
            floatArray[i] = buffer.short.toFloat() / 32768.0f
        }

        return floatArray
    }
}
