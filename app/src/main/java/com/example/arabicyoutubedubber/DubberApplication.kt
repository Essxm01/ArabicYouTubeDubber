package com.example.arabicyoutubedubber

import android.app.Application
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.ffmpeg.FFmpeg

class DubberApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // Initialize Youtube-DL Android wrapper
            YoutubeDL.getInstance().init(this)
            
            // Initialize FFmpeg wrapper for Youtube-DL (handles video/audio combining, etc.)
            FFmpeg.getInstance().init(this)
            
            Log.d("DubberApplication", "YoutubeDL and FFmpeg initialized successfully.")
        } catch (e: YoutubeDLException) {
            Log.e("DubberApplication", "Failed to initialize YoutubeDL / FFmpeg", e)
        } catch (e: Exception) {
            Log.e("DubberApplication", "Unexpected initialization error", e)
        }
    }
}
