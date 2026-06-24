package com.example.arabicyoutubedubber.transcription

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Extracts audio from video files using FFmpegKit.
 *
 * Whisper.cpp requires 16kHz, 16-bit, mono PCM WAV input.
 * This class handles the conversion automatically.
 */
object AudioExtractor {

    private const val TAG = "AudioExtractor"

    /**
     * Extract audio from a video file and convert to WAV format
     * suitable for Whisper transcription (16kHz, 16-bit, mono PCM).
     *
     * @param videoPath Absolute path to the input video file.
     * @param outputDir Directory to write the output WAV file.
     * @param outputFileName Name for the output file (default: "extracted_audio.wav").
     * @return Absolute path to the generated WAV file.
     * @throws IOException if extraction fails.
     */
    suspend fun extractAudioForWhisper(
        videoPath: String,
        outputDir: File,
        outputFileName: String = "extracted_audio.wav"
    ): String = withContext(Dispatchers.IO) {
        val inputFile = File(videoPath)
        if (!inputFile.exists()) {
            throw IOException("Input video file not found: $videoPath")
        }

        outputDir.mkdirs()
        val outputFile = File(outputDir, outputFileName)

        // Delete previous output if exists
        if (outputFile.exists()) {
            outputFile.delete()
        }

        // FFmpeg command:
        // -i input         : input file
        // -vn               : no video
        // -acodec pcm_s16le : 16-bit PCM
        // -ar 16000         : 16kHz sample rate
        // -ac 1             : mono channel
        // -y                : overwrite output
        val command = "-i \"$videoPath\" -vn -acodec pcm_s16le -ar 16000 -ac 1 -y \"${outputFile.absolutePath}\""

        Log.i(TAG, "Extracting audio: $command")

        val session = FFmpegKit.execute(command)

        if (ReturnCode.isSuccess(session.returnCode)) {
            if (outputFile.exists() && outputFile.length() > 44) {  // 44 = WAV header
                Log.i(TAG, "Audio extracted successfully: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                return@withContext outputFile.absolutePath
            } else {
                throw IOException("FFmpeg succeeded but output file is missing or empty")
            }
        } else {
            val errorLog = session.allLogsAsString ?: "No logs available"
            Log.e(TAG, "FFmpeg failed: $errorLog")
            throw IOException("Audio extraction failed (code: ${session.returnCode}). Logs: $errorLog")
        }
    }
}
