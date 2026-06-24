package com.example.arabicyoutubedubber.transcription

/**
 * Low-level JNI wrapper for the native whisper.cpp library.
 * All methods map directly to C functions in whisper-jni.c.
 */
object WhisperLib {

    init {
        System.loadLibrary("whisper_jni")
    }

    // ── Context lifecycle ──
    external fun initContext(modelPath: String): Long
    external fun freeContext(contextPtr: Long)
    external fun resetTimings(contextPtr: Long)

    // ── Transcription ──
    external fun fullTranscribe(
        contextPtr: Long,
        numThreads: Int,
        audioData: FloatArray,
        language: String,
        beamSize: Int,
        temperature: Float,
        noContext: Boolean
    ): Int

    // ── Segment access ──
    external fun getTextSegmentCount(contextPtr: Long): Int
    external fun getTextSegment(contextPtr: Long, index: Int): String
    external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
    external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
}
