cmake_minimum_required(VERSION 3.22.1)
project(soundtracker_dsp LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 16 KB page alignment for Android 15/16+ compatibility
add_link_options("-Wl,-z,max-page-size=16384")

# Import prebuilt ONNX Runtime for current ABI
set(JNILIBS_ABI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${JNILIBS_ABI_DIR}/libonnxruntime.so"
)

# Build static libmp3lame (excluding mpglib decoder interface)
set(LAME_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/lame")
set(LAME_SOURCES
    "${LAME_DIR}/libmp3lame/VbrTag.c"
    "${LAME_DIR}/libmp3lame/bitstream.c"
    "${LAME_DIR}/libmp3lame/encoder.c"
    "${LAME_DIR}/libmp3lame/fft.c"
    "${LAME_DIR}/libmp3lame/gain_analysis.c"
    "${LAME_DIR}/libmp3lame/id3tag.c"
    "${LAME_DIR}/libmp3lame/lame.c"
    "${LAME_DIR}/libmp3lame/newmdct.c"
    "${LAME_DIR}/libmp3lame/presets.c"
    "${LAME_DIR}/libmp3lame/psymodel.c"
    "${LAME_DIR}/libmp3lame/quantize.c"
    "${LAME_DIR}/libmp3lame/quantize_pvt.c"
    "${LAME_DIR}/libmp3lame/reservoir.c"
    "${LAME_DIR}/libmp3lame/set_get.c"
    "${LAME_DIR}/libmp3lame/tables.c"
    "${LAME_DIR}/libmp3lame/takehiro.c"
    "${LAME_DIR}/libmp3lame/util.c"
    "${LAME_DIR}/libmp3lame/vbrquantize.c"
    "${LAME_DIR}/libmp3lame/version.c"
)

add_library(mp3lame STATIC ${LAME_SOURCES})
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H=1)
target_compile_options(mp3lame PRIVATE -O3 -ffast-math -Wno-unused-value -Wno-deprecated-declarations -Wno-shift-negative-value -Wno-absolute-value -Wno-tautological-pointer-compare)
target_include_directories(mp3lame PUBLIC
    "${LAME_DIR}"
    "${LAME_DIR}/include"
    "${LAME_DIR}/libmp3lame"
)

# Build static Rubber Band Library v4.0.0 (R3 Finer Multi-Resolution Engine)
# Compiled with -fno-fast-math so IEEE phase-vocoder & Kaiser/BQ sinc precision remain 100% exact
set(RUBBERBAND_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/rubberband")
add_library(rubberband STATIC
    "${RUBBERBAND_DIR}/single/RubberBandSingle.cpp"
)
target_compile_definitions(rubberband PRIVATE
    ALREADY_CONFIGURED=1
    USE_BQRESAMPLER=1
    USE_BUILTIN_FFT=1
    LACK_SINCOS=1
    NO_TIMING=1
    NO_THREADING=1
    NO_THREAD_CHECKS=1
)
target_compile_options(rubberband PRIVATE -O3 -fno-fast-math -Wno-unused-parameter -Wno-sign-compare)
target_include_directories(rubberband PUBLIC
    "${RUBBERBAND_DIR}"
)

# Main SoundTracker Native Studio Engine
add_library(soundtracker_dsp SHARED
    wav_io.cpp
    loudness_maximizer.cpp
    pitch_shifter.cpp
    vocal_separator.cpp
    mp3_encoder.cpp
    soundtracker_jni.cpp
)

target_include_directories(soundtracker_dsp PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party"
    "${RUBBERBAND_DIR}"
    "${LAME_DIR}/include"
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(soundtracker_dsp
    PRIVATE
    rubberband
    mp3lame
    onnxruntime
    ${log-lib}
    ${android-lib}
    m
)
