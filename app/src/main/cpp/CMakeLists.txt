cmake_minimum_required(VERSION 3.10)
project(whisper_jni)

# Fetch whisper.cpp from GitHub
include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        v1.11.0
    GIT_SHALLOW    TRUE
)

# Disable unnecessary builds
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(whisper)

# Build the JNI bridge library
add_library(whisper_jni SHARED whisper-jni.c)

# Optimize compilation for ARM targets (CPU + NEON)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(whisper_jni PRIVATE -O3 -march=armv8-a -ftree-vectorize)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(whisper_jni PRIVATE -O3 -march=armv7-a -mfpu=neon -ftree-vectorize)
else()
    target_compile_options(whisper_jni PRIVATE -O3)
endif()

target_include_directories(whisper_jni PRIVATE
    ${whisper_SOURCE_DIR}/include
    ${whisper_SOURCE_DIR}/ggml/include
)

target_link_libraries(whisper_jni
    whisper
    android
    log
)
