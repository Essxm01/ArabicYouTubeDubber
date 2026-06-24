# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve youtubedl-android classes
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }

# Preserve FFmpegKit classes
-keep class com.arthenica.ffmpegkit.** { *; }

# Preserve ML Kit Translation classes
-keep class com.google.mlkit.** { *; }

# Preserve JNI / WhisperLib classes and native methods
-keep class com.example.arabicyoutubedubber.transcription.WhisperLib { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
