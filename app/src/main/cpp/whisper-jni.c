#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── Helper: convert jstring -> C string (caller must free) ───
static const char *jstring_to_cstr(JNIEnv *env, jstring jstr) {
    if (jstr == NULL) return NULL;
    return (*env)->GetStringUTFChars(env, jstr, NULL);
}

static void release_cstr(JNIEnv *env, jstring jstr, const char *cstr) {
    if (cstr) (*env)->ReleaseStringUTFChars(env, jstr, cstr);
}

// ─── initContext: Load model from file path, return pointer as long ───
JNIEXPORT jlong JNICALL
Java_com_example_arabicyoutubedubber_transcription_WhisperLib_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    const char *model_path = jstring_to_cstr(env, model_path_str);
    if (!model_path) {
        LOGE("initContext: model_path is null");
        return 0;
    }
    LOGI("initContext: loading model from %s", model_path);

    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);

    release_cstr(env, model_path_str, model_path);

    if (!ctx) {
        LOGE("initContext: failed to load model");
        return 0;
    }
    LOGI("initContext: model loaded successfully");
    return (jlong)(intptr_t)ctx;
}

// ─── freeContext: Release a previously loaded model ───
JNIEXPORT void JNICALL
Java_com_example_arabicyoutubedubber_transcription_WhisperLib_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)context_ptr;
    if (ctx) {
        whisper_free(ctx);
        LOGI("freeContext: context freed");
    }
}

// ─── fullTranscribe: Run full transcription on float PCM data ───
JNIEXPORT jint JNICALL
Java_com_example_arabicyoutubedubber_transcription_WhisperLib_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr,
        jint num_threads, jfloatArray audio_data, jstring language_str,
        jint beam_size, jfloat temperature, jboolean no_context) {
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)context_ptr;
    if (!ctx) {
        LOGE("fullTranscribe: context is null");
        return -1;
    }

    // Get audio samples
    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jint audio_len = (*env)->GetArrayLength(env, audio_data);

    // Get language
    const char *language = jstring_to_cstr(env, language_str);

    LOGI("fullTranscribe: %d samples, threads=%d, lang=%s, beam_size=%d, temp=%.2f, no_context=%d",
         audio_len, num_threads, language ? language : "auto", beam_size, temperature, no_context);

    // Configure parameters
    enum whisper_sampling_strategy strategy = (beam_size > 1) ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY;
    struct whisper_full_params params = whisper_full_default_params(strategy);
    params.print_realtime   = JNI_FALSE;
    params.print_progress   = JNI_FALSE;
    params.print_timestamps = JNI_TRUE;
    params.print_special    = JNI_FALSE;
    params.translate        = JNI_FALSE;
    params.language         = language;
    params.n_threads        = num_threads;
    params.offset_ms        = 0;
    params.no_context       = no_context ? JNI_TRUE : JNI_FALSE;
    params.single_segment   = JNI_FALSE;

    if (strategy == WHISPER_SAMPLING_BEAM_SEARCH) {
        params.beam_search.beam_size = beam_size;
    }

    params.temperature = temperature;
    if (temperature == 0.0f) {
        params.temperature_inc = 0.0f; // Disable automatic temperature fallback to keep it 0.0
    }

    // Execute transcription
    int ret = whisper_full(ctx, params, audio, audio_len);

    // Release resources
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, 0);
    release_cstr(env, language_str, language);

    if (ret != 0) {
        LOGE("fullTranscribe: whisper_full returned %d", ret);
    } else {
        LOGI("fullTranscribe: success");
    }
    return ret;
}

// ─── getTextSegmentCount: Number of segments after transcription ───
JNIEXPORT jint JNICALL
Java_com_example_arabicyoutubedubber_transcription_WhisperLib_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)context_ptr;
    if (!ctx) return 0;
    return whisper_full_n_segments(ctx);
}

// ─── getTextSegment: Get text of a specific segment ───
JNIEXPORT jstring JNICALL
Java_com_example_arabicyoutubedubber_transcription_WhisperLib_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)context_ptr;
    if (!ctx) return (*env)->NewStringUTF(env, "");
    const char *text = whisper_full_get_segment_text(ctx, index);
    return (*env)->NewStringUTF(env, text);
}

// ─── getTextSegmentT0: Get start timestamp of a segment (in centiseconds) ───
JNIEXPORT jlong JNICALL
Java_com_example_arabicyoutubedubber_transcription_WhisperLib_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)context_ptr;
    if (!ctx) return 0;
    return whisper_full_get_segment_t0(ctx, index);
}

// ─── getTextSegmentT1: Get end timestamp of a segment (in centiseconds) ───
JNIEXPORT jlong JNICALL
Java_com_example_arabicyoutubedubber_transcription_WhisperLib_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)context_ptr;
    if (!ctx) return 0;
    return whisper_full_get_segment_t1(ctx, index);
}

// ─── resetTimings: Reset context execution timings ───
JNIEXPORT void JNICALL
Java_com_example_arabicyoutubedubber_transcription_WhisperLib_resetTimings(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)context_ptr;
    if (ctx) {
        whisper_reset_timings(ctx);
        LOGI("resetTimings: context timings reset");
    }
}
