package com.example.arabicyoutubedubber.transcription

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class VideoDubber {

    companion object {
        private const val TAG = "VideoDubber"
    }

    enum class DubbingMode {
        REPLACE,      // Replace original audio completely
        VOICEOVER,    // Keep original audio as quiet background (-20dB / 0.15 volume)
        DUAL_TRACK    // Add dubbed audio as a secondary audio track
    }

    /**
     * Integrates the newly generated Arabic audio track into the original video.
     *
     * @param videoPath Absolute path to the original MP4 video.
     * @param dubbedAudioPath Absolute path to the dubbed Arabic WAV audio track.
     * @param outputPath Absolute path to save the final dubbed video.
     * @param mode The dubbing mode (REPLACE, VOICEOVER, DUAL_TRACK).
     * @return Absolute path of the dubbed video file.
     */
    suspend fun dubVideo(
        videoPath: String,
        dubbedAudioPath: String,
        outputPath: String,
        mode: DubbingMode
    ): String = withContext(Dispatchers.IO) {
        val videoFile = File(videoPath)
        val audioFile = File(dubbedAudioPath)

        if (!videoFile.exists()) throw IOException("Video file not found: $videoPath")
        if (!audioFile.exists()) throw IOException("Audio track file not found: $dubbedAudioPath")

        val outputFile = File(outputPath)
        if (outputFile.exists()) {
            outputFile.delete()
        }

        // Construct FFmpeg command based on selected mode
        val command = when (mode) {
            DubbingMode.REPLACE -> {
                // Mux original video and new audio track, replacing original audio
                "-y -i \"$videoPath\" -i \"$dubbedAudioPath\" -c:v copy -c:a aac -map 0:v:0 -map 1:a:0 -shortest \"$outputPath\""
            }
            DubbingMode.VOICEOVER -> {
                // Mix original audio (ducked to 15%) and new dubbed audio (100%)
                "-y -i \"$videoPath\" -i \"$dubbedAudioPath\" -filter_complex \"[0:a]volume=0.15[bg];[1:a]volume=1.0[fg];[bg][fg]amix=inputs=2:duration=first[a]\" -c:v copy -c:a aac -map 0:v:0 -map \"[a]\" -shortest \"$outputPath\""
            }
            DubbingMode.DUAL_TRACK -> {
                // Keep original video and original audio, and add new audio as a second track
                "-y -i \"$videoPath\" -i \"$dubbedAudioPath\" -c:v copy -c:a aac -map 0:v:0 -map 0:a:0 -map 1:a:0 -shortest \"$outputPath\""
            }
        }

        Log.i(TAG, "Muxing dubbed video (mode: $mode): $command")

        val session = FFmpegKit.execute(command)

        if (ReturnCode.isSuccess(session.returnCode)) {
            if (outputFile.exists() && outputFile.length() > 0) {
                Log.i(TAG, "Video dubbing completed successfully: $outputPath")
                return@withContext outputPath
            } else {
                throw IOException("FFmpeg finished but output video is missing or empty")
            }
        } else {
            val errorLog = session.allLogsAsString ?: "No logs available"
            Log.e(TAG, "FFmpeg video dubbing failed: $errorLog")
            throw IOException("FFmpeg video dubbing failed with code: ${session.returnCode}. Logs: $errorLog")
        }
    }
}
