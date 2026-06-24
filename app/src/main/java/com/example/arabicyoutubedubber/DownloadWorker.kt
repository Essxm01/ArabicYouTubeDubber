package com.example.arabicyoutubedubber

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlin.random.Random

class DownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val url = inputData.getString("url") ?: return Result.failure()
        val quality = inputData.getString("quality") ?: "best"
        
        // Define directory to store downloads in cache
        val outputDir = File(applicationContext.cacheDir, "downloads").apply { mkdirs() }
        // Ensure a unique filename format or simple placeholder filename
        val outputFilePath = File(outputDir, "original_video.mp4").absolutePath
        
        // Clean up previous files if any
        val file = File(outputFilePath)
        if (file.exists()) {
            file.delete()
        }

        var attempt = 1
        val maxAttempts = 3
        var lastException: Exception? = null

        while (attempt <= maxAttempts) {
            try {
                Log.d("DownloadWorker", "Starting download attempt $attempt for URL: $url")
                
                val request = YoutubeDLRequest(url).apply {
                    addOption("-o", "${outputDir.absolutePath}/%(title)s.%(ext)s")
                    addOption("-f", quality)
                    addOption("--merge-output-format", "mp4")
                    addOption("--user-agent", getRandomUserAgent())
                    addOption("--no-check-certificates")
                    addOption("--rm-cache-dir")
                    
                    if (attempt > 1) {
                        addOption("--force-ipv4")
                    }
                }

                // Execute the download and report progress to WorkManager
                val response = YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
                    setProgressAsync(
                        workDataOf(
                            "progress" to progress.toInt(),
                            "status" to "Downloading: ${progress.toInt()}%"
                        )
                    )
                }

                // Find the actual downloaded file (yt-dlp may name it differently)
                val downloadedFile = outputDir.listFiles()?.firstOrNull { it.extension == "mp4" }

                if (response.exitCode == 0 && downloadedFile != null && downloadedFile.exists()) {
                    // Rename to our standard name for downstream pipeline
                    val finalFile = File(outputDir, "original_video.mp4")
                    if (downloadedFile.absolutePath != finalFile.absolutePath) {
                        downloadedFile.renameTo(finalFile)
                    }
                    Log.d("DownloadWorker", "Download finished successfully. Output: ${finalFile.absolutePath}")
                    return Result.success(workDataOf("file_path" to finalFile.absolutePath))
                } else {
                    throw Exception("yt-dlp exited with non-zero code: ${response.exitCode}")
                }

            } catch (e: Exception) {
                lastException = e
                val message = e.message ?: ""
                Log.w("DownloadWorker", "Attempt $attempt failed: $message")
                
                // Retry if signature expired or general 403 / HTTP Errors occurred
                if (message.contains("Signature expired", ignoreCase = true) || 
                    message.contains("403", ignoreCase = true) ||
                    message.contains("HTTP Error", ignoreCase = true)) {
                    Log.w("DownloadWorker", "HTTP blocking or Signature issue. Changing user-agent and retrying...")
                }
                
                attempt++
                if (attempt <= maxAttempts) {
                    // Progressive delay
                    kotlinx.coroutines.delay(2000L * attempt)
                }
            }
        }

        Log.e("DownloadWorker", "All download attempts failed.", lastException)
        return Result.failure(
            workDataOf("error_message" to (lastException?.message ?: "Unknown download error"))
        )
    }

    private fun getRandomUserAgent(): String {
        val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Safari/605.1.15",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0"
        )
        return userAgents[Random.nextInt(userAgents.size)]
    }
}
