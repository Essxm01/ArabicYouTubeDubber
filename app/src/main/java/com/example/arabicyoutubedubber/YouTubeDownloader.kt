package com.example.arabicyoutubedubber

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

class YouTubeDownloader {

    suspend fun downloadVideo(
        context: Context,
        url: String,
        quality: String = "best",
        onProgress: (Int) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(context)

        // Set up input data for the background worker
        val inputData = Data.Builder()
            .putString("url", url)
            .putString("quality", quality)
            .build()

        val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .build()

        // Enqueue background work
        workManager.enqueue(downloadWorkRequest)

        // Wait for the worker to reach a terminal state, reporting progress along the way
        val finalWorkInfo = workManager.getWorkInfoByIdFlow(downloadWorkRequest.id)
            .filter { workInfo ->
                // Report progress on each emission
                val progress = workInfo.progress.getInt("progress", -1)
                if (progress != -1) {
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
                // Only pass through terminal states
                workInfo.state.isFinished
            }
            .first() // Suspends until the first terminal emission, then cancels the flow

        return@withContext when (finalWorkInfo.state) {
            WorkInfo.State.SUCCEEDED -> {
                finalWorkInfo.outputData.getString("file_path")
                    ?: throw IOException("Download succeeded but no file path returned")
            }
            WorkInfo.State.FAILED -> {
                val errorMsg = finalWorkInfo.outputData.getString("error_message")
                    ?: "Unknown download failure"
                throw IOException(errorMsg)
            }
            else -> {
                throw IOException("Download cancelled or interrupted")
            }
        }
    }
}
