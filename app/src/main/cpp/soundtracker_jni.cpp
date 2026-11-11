#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include "audio_buffer.h"
#include "loudness_maximizer.h"
#include "mp3_encoder.h"
#include "multi_track_mixer.h"
#include "pitch_shifter.h"
#include "vocal_separator.h"
#include "wav_io.h"

#include <atomic>
#include <string>
#include <vector>

#define LOG_TAG "SoundTrackerDSP"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

std::atomic<bool> gCancelRequested{false};

std::string jstringToStd(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* utf = env->GetStringUTFChars(jstr, nullptr);
    if (!utf) return "";
    std::string res(utf);
    env->ReleaseStringUTFChars(jstr, utf);
    return res;
}

soundtracker::ProgressCallback makeJniProgress(JNIEnv* env, jobject listener) {
    if (!listener) {
        return [](float) { return !gCancelRequested.load(std::memory_order_relaxed); };
    }
    jclass cls = env->GetObjectClass(listener);
    jmethodID mid = env->GetMethodID(cls, "onProgress", "(F)V");
    env->DeleteLocalRef(cls);
    if (!mid) {
        return [](float) { return !gCancelRequested.load(std::memory_order_relaxed); };
    }
    return [env, listener, mid](float progress) -> bool {
        if (gCancelRequested.load(std::memory_order_relaxed)) {
            return false;
        }
        env->CallVoidMethod(listener, mid, progress);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return !gCancelRequested.load(std::memory_order_relaxed);
    };
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeResetCancel(JNIEnv*, jobject) {
    gCancelRequested.store(false, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeRequestCancel(JNIEnv*, jobject) {
    gCancelRequested.store(true, std::memory_order_relaxed);
}

// Returns float array:
// [0] = integratedLufs
// [1] = truePeakDbtp
// [2] = samplePeakDbfs
// [3] = rmsDbfs
// [4] = pureLosslessHeadroomDb
// [5] = durationSec
// [6] = sampleRate
// [7] = channels
// [8 .. 8+bins-1] = waveformPeaks
JNIEXPORT jfloatArray JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeAnalyzeWav(
    JNIEnv* env,
    jobject,
    jstring jWavPath,
    jint waveformBins
) {
    std::string wavPath = jstringToStd(env, jWavPath);
    soundtracker::PlanarAudioBuffer buf;
    std::string err;
    if (!soundtracker::readWavFile(wavPath, buf, &err)) {
        LOGE("nativeAnalyzeWav read failed: %s", err.c_str());
        return nullptr;
    }

    int bins = std::max(0, static_cast<int>(waveformBins));
    soundtracker::AudioAnalysis a = soundtracker::analyzeAudio(buf, bins);

    std::vector<float> packed;
    packed.reserve(9 + a.waveformPeaks.size());
    packed.push_back(a.integratedLufs);
    packed.push_back(a.truePeakDbtp);
    packed.push_back(a.samplePeakDbfs);
    packed.push_back(a.rmsDbfs);
    packed.push_back(a.pureLosslessHeadroomDb);
    packed.push_back(a.durationSec);
    packed.push_back(static_cast<float>(a.sampleRate));
    packed.push_back(static_cast<float>(a.channels));
    packed.push_back(a.detectedBpm);
    packed.insert(packed.end(), a.waveformPeaks.begin(), a.waveformPeaks.end());

    jfloatArray out = env->NewFloatArray(static_cast<jsize>(packed.size()));
    if (out) {
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(packed.size()), packed.data());
    }
    return out;
}

// Returns nullptr on success, or an error/cancellation jstring on failure
JNIEXPORT jstring JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeSeparateVocals(
    JNIEnv* env,
    jobject,
    jobject jAssetManager,
    jstring jInstAssetPath,
    jstring jDemucsAssetPath,
    jint modeOrdinal,
    jboolean enableDenoise,
    jstring jInWavPath,
    jstring jOutAccWavPath,
    jstring jOutVocWavPath,
    jint numThreads,
    jobject progressListener
) {
    gCancelRequested.store(false, std::memory_order_relaxed);
    AAssetManager* assetMgr = jAssetManager ? AAssetManager_fromJava(env, jAssetManager) : nullptr;
    if (!assetMgr) {
        return env->NewStringUTF("AAssetManager 初始化失败");
    }

    soundtracker::SeparationModelPaths modelPaths;
    modelPaths.assetManager = assetMgr;
    modelPaths.instHq5Path = jstringToStd(env, jInstAssetPath);
    modelPaths.htdemucsFtPath = jstringToStd(env, jDemucsAssetPath);
    modelPaths.enableInstDenoise = (enableDenoise == JNI_TRUE);

    std::string inWavPath = jstringToStd(env, jInWavPath);
    std::string outAccPath = jstringToStd(env, jOutAccWavPath);
    std::string outVocPath = jstringToStd(env, jOutVocWavPath);

    soundtracker::PlanarAudioBuffer inBuf;
    std::string err;
    if (!soundtracker::readWavFile(inWavPath, inBuf, &err)) {
        LOGE("nativeSeparateVocals read failed: %s", err.c_str());
        return env->NewStringUTF(("读取输入音频失败: " + err).c_str());
    }

    soundtracker::VocalSeparationMode sepMode = soundtracker::VocalSeparationMode::INST_HQ5_DIRECT;
    if (modeOrdinal == 1) {
        sepMode = soundtracker::VocalSeparationMode::HTDEMUCS_FT;
    } else if (modeOrdinal == 2) {
        sepMode = soundtracker::VocalSeparationMode::HYBRID_ENSEMBLE;
    }

    soundtracker::PlanarAudioBuffer accBuf;
    soundtracker::PlanarAudioBuffer vocBuf;
    soundtracker::PlanarAudioBuffer* vocPtr = outVocPath.empty() ? nullptr : &vocBuf;

    auto cb = makeJniProgress(env, progressListener);
    if (!soundtracker::separateVocalsStudio(modelPaths, sepMode, inBuf, accBuf, vocPtr, numThreads, cb, &err)) {
        LOGE("nativeSeparateVocals failed: %s", err.c_str());
        return env->NewStringUTF(err.empty() ? "伴奏提取失败" : err.c_str());
    }

    if (!soundtracker::writeWavFile(outAccPath, accBuf, false, &err)) {
        LOGE("nativeSeparateVocals write acc failed: %s", err.c_str());
        return env->NewStringUTF(("写入伴奏失败: " + err).c_str());
    }

    if (vocPtr && !soundtracker::writeWavFile(outVocPath, vocBuf, false, &err)) {
        LOGE("nativeSeparateVocals write voc failed: %s", err.c_str());
        return env->NewStringUTF(("写入人声失败: " + err).c_str());
    }

    return nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativePitchShift(
    JNIEnv* env,
    jobject,
    jstring jInWavPath,
    jstring jOutWavPath,
    jfloat semitones,
    jdouble timeRatio,
    jboolean preserveTimbre,
    jobject progressListener
) {
    gCancelRequested.store(false, std::memory_order_relaxed);
    std::string inPath = jstringToStd(env, jInWavPath);
    std::string outPath = jstringToStd(env, jOutWavPath);

    soundtracker::PlanarAudioBuffer inBuf;
    std::string err;
    if (!soundtracker::readWavFile(inPath, inBuf, &err)) {
        LOGE("nativePitchShift read failed: %s", err.c_str());
        return JNI_FALSE;
    }

    soundtracker::PlanarAudioBuffer outBuf;
    auto cb = makeJniProgress(env, progressListener);
    if (!soundtracker::pitchShiftPolyphonic(
            inBuf,
            outBuf,
            semitones,
            static_cast<double>(timeRatio),
            preserveTimbre == JNI_TRUE,
            cb
        )) {
        LOGE("nativePitchShift cancelled or failed");
        return JNI_FALSE;
    }

    if (!soundtracker::writeWavFile(outPath, outBuf, false, &err)) {
        LOGE("nativePitchShift write failed: %s", err.c_str());
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

// Returns [0]=wasPureLossless(1/0), [1]=postLufs, [2]=postTruePeakDbtp, [3]=maxGainReductionDb
JNIEXPORT jfloatArray JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeBoostVolume(
    JNIEnv* env,
    jobject,
    jstring jInWavPath,
    jstring jOutWavPath,
    jfloat gainDb,
    jfloat ceilingDbtp,
    jobject progressListener
) {
    gCancelRequested.store(false, std::memory_order_relaxed);
    std::string inPath = jstringToStd(env, jInWavPath);
    std::string outPath = jstringToStd(env, jOutWavPath);

    soundtracker::PlanarAudioBuffer buf;
    std::string err;
    if (!soundtracker::readWavFile(inPath, buf, &err)) {
        LOGE("nativeBoostVolume read failed: %s", err.c_str());
        return nullptr;
    }

    auto cb = makeJniProgress(env, progressListener);
    soundtracker::BoostResult r = soundtracker::boostVolumeTransparent(buf, gainDb, ceilingDbtp, cb);
    if (!r.ok) {
        LOGE("nativeBoostVolume cancelled or failed");
        return nullptr;
    }

    if (!soundtracker::writeWavFile(outPath, buf, false, &err)) {
        LOGE("nativeBoostVolume write failed: %s", err.c_str());
        return nullptr;
    }

    float resArr[4] = {
        r.wasPureLossless ? 1.0f : 0.0f,
        r.postLufs,
        r.postTruePeakDbtp,
        r.maxGainReductionDb
    };
    jfloatArray out = env->NewFloatArray(4);
    if (out) {
        env->SetFloatArrayRegion(out, 0, 4, resArr);
    }
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeEncodeMp3(
    JNIEnv* env,
    jobject,
    jstring jInWavPath,
    jstring jOutMp3Path,
    jint bitrateKbps,
    jstring jTitle,
    jobject progressListener
) {
    gCancelRequested.store(false, std::memory_order_relaxed);
    std::string inPath = jstringToStd(env, jInWavPath);
    std::string outPath = jstringToStd(env, jOutMp3Path);
    std::string title = jstringToStd(env, jTitle);

    soundtracker::PlanarAudioBuffer buf;
    std::string err;
    if (!soundtracker::readWavFile(inPath, buf, &err)) {
        LOGE("nativeEncodeMp3 read failed: %s", err.c_str());
        return JNI_FALSE;
    }

    auto cb = makeJniProgress(env, progressListener);
    if (!soundtracker::encodeMp3File(buf, outPath, bitrateKbps, title, cb, &err)) {
        LOGE("nativeEncodeMp3 failed: %s", err.c_str());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeConvertToStandardWav(
    JNIEnv* env,
    jobject,
    jstring jInWavPath,
    jstring jOutWavPath
) {
    std::string inPath = jstringToStd(env, jInWavPath);
    std::string outPath = jstringToStd(env, jOutWavPath);
    soundtracker::PlanarAudioBuffer buf;
    std::string err;
    if (!soundtracker::readWavFile(inPath, buf, &err)) {
        return JNI_FALSE;
    }
    if (buf.sampleRate != 44100 && buf.sampleRate != 48000) {
        buf = soundtracker::resampleSinc(buf, 44100);
    }
    buf.ensureStereo();
    return soundtracker::writeWavFile(outPath, buf, false, &err) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// Real-Time Streaming Pitch & Tempo Preview ("边听边调") JNI Bridge
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeCreateRealtimeShifter(
    JNIEnv*,
    jobject,
    jint sampleRate,
    jint channels
) {
    auto* shifter = new soundtracker::RealtimePitchShifter(
        static_cast<int>(sampleRate),
        static_cast<int>(channels)
    );
    return reinterpret_cast<jlong>(shifter);
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeDestroyRealtimeShifter(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle == 0) return;
    auto* shifter = reinterpret_cast<soundtracker::RealtimePitchShifter*>(handle);
    delete shifter;
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeResetRealtimeShifter(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle == 0) return;
    auto* shifter = reinterpret_cast<soundtracker::RealtimePitchShifter*>(handle);
    shifter->reset();
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeSetRealtimeShifterParams(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat semitones,
    jdouble timeRatio,
    jboolean preserveTimbre
) {
    if (handle == 0) return;
    auto* shifter = reinterpret_cast<soundtracker::RealtimePitchShifter*>(handle);
    shifter->setParameters(
        static_cast<float>(semitones),
        static_cast<double>(timeRatio),
        preserveTimbre == JNI_TRUE
    );
}

JNIEXPORT jint JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeProcessRealtimeShifter(
    JNIEnv* env,
    jobject,
    jlong handle,
    jshortArray jInPcm,
    jint inFrames,
    jshortArray jOutPcm,
    jint maxOutFrames
) {
    if (handle == 0 || !jOutPcm || maxOutFrames <= 0) return 0;
    auto* shifter = reinterpret_cast<soundtracker::RealtimePitchShifter*>(handle);
    const int ch = shifter->channels();

    static thread_local std::vector<int16_t> tlInBuf;
    static thread_local std::vector<int16_t> tlOutBuf;

    const int safeInFrames = (jInPcm && inFrames > 0) ? static_cast<int>(inFrames) : 0;
    const size_t inSamples = static_cast<size_t>(safeInFrames) * static_cast<size_t>(ch);
    if (inSamples > 0) {
        if (tlInBuf.size() < inSamples) {
            tlInBuf.resize(inSamples);
        }
        env->GetShortArrayRegion(jInPcm, 0, static_cast<jsize>(inSamples), tlInBuf.data());
    }

    const size_t maxOutSamples = static_cast<size_t>(maxOutFrames) * static_cast<size_t>(ch);
    if (tlOutBuf.size() < maxOutSamples) {
        tlOutBuf.resize(maxOutSamples);
    }

    const int outFrames = shifter->processInterleavedInt16(
        inSamples > 0 ? tlInBuf.data() : nullptr,
        safeInFrames,
        tlOutBuf.data(),
        static_cast<int>(maxOutFrames)
    );
    if (outFrames > 0) {
        const jsize outSamples = static_cast<jsize>(outFrames * ch);
        env->SetShortArrayRegion(jOutPcm, 0, outSamples, tlOutBuf.data());
    }
    return static_cast<jint>(outFrames);
}

JNIEXPORT jint JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeDrainRealtimeShifter(
    JNIEnv* env,
    jobject,
    jlong handle,
    jshortArray jOutPcm,
    jint maxOutFrames
) {
    if (handle == 0 || !jOutPcm || maxOutFrames <= 0) return 0;
    auto* shifter = reinterpret_cast<soundtracker::RealtimePitchShifter*>(handle);
    const int ch = shifter->channels();

    static thread_local std::vector<int16_t> tlOutBuf;
    const size_t maxOutSamples = static_cast<size_t>(maxOutFrames) * static_cast<size_t>(ch);
    if (tlOutBuf.size() < maxOutSamples) {
        tlOutBuf.resize(maxOutSamples);
    }

    const int outFrames = shifter->drainPendingInt16(
        tlOutBuf.data(),
        static_cast<int>(maxOutFrames)
    );
    if (outFrames > 0) {
        env->SetShortArrayRegion(
            jOutPcm,
            0,
            static_cast<jsize>(outFrames * ch),
            tlOutBuf.data()
        );
    }
    return static_cast<jint>(outFrames);
}

JNIEXPORT jint JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeFinishRealtimeShifter(
    JNIEnv* env,
    jobject,
    jlong handle,
    jshortArray jOutPcm,
    jint maxOutFrames
) {
    if (handle == 0 || !jOutPcm || maxOutFrames <= 0) return 0;
    auto* shifter = reinterpret_cast<soundtracker::RealtimePitchShifter*>(handle);
    const int ch = shifter->channels();

    static thread_local std::vector<int16_t> tlOutBuf;
    const size_t maxOutSamples = static_cast<size_t>(maxOutFrames) * static_cast<size_t>(ch);
    if (tlOutBuf.size() < maxOutSamples) {
        tlOutBuf.resize(maxOutSamples);
    }

    const int outFrames = shifter->finishInterleavedInt16(
        tlOutBuf.data(),
        static_cast<int>(maxOutFrames)
    );
    if (outFrames > 0) {
        env->SetShortArrayRegion(
            jOutPcm,
            0,
            static_cast<jsize>(outFrames * ch),
            tlOutBuf.data()
        );
    }
    return static_cast<jint>(outFrames);
}

// ============================================================================
// Real-Time Streaming Volume Maximizer ("音量放大边听边调") JNI Bridge
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeCreateRealtimeMaximizer(
    JNIEnv*,
    jobject,
    jint sampleRate,
    jint channels
) {
    auto* maximizer = new soundtracker::RealtimeVolumeMaximizer(
        static_cast<int>(sampleRate),
        static_cast<int>(channels),
        -0.2f
    );
    return reinterpret_cast<jlong>(maximizer);
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeDestroyRealtimeMaximizer(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle == 0) return;
    auto* maximizer = reinterpret_cast<soundtracker::RealtimeVolumeMaximizer*>(handle);
    delete maximizer;
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeResetRealtimeMaximizer(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle == 0) return;
    auto* maximizer = reinterpret_cast<soundtracker::RealtimeVolumeMaximizer*>(handle);
    maximizer->reset();
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeSetRealtimeMaximizerGain(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat gainDb
) {
    if (handle == 0) return;
    auto* maximizer = reinterpret_cast<soundtracker::RealtimeVolumeMaximizer*>(handle);
    maximizer->setGainDb(static_cast<float>(gainDb));
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeProcessRealtimeMaximizer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jshortArray jPcm,
    jint frames
) {
    if (handle == 0 || !jPcm || frames <= 0) return;
    auto* maximizer = reinterpret_cast<soundtracker::RealtimeVolumeMaximizer*>(handle);
    const int ch = maximizer->channels();
    const size_t totalSamples = static_cast<size_t>(frames) * static_cast<size_t>(ch);

    static thread_local std::vector<int16_t> tlBuf;
    if (tlBuf.size() < totalSamples) {
        tlBuf.resize(totalSamples);
    }

    env->GetShortArrayRegion(jPcm, 0, static_cast<jsize>(totalSamples), tlBuf.data());
    maximizer->processInterleavedInt16(tlBuf.data(), tlBuf.data(), static_cast<int>(frames));
    env->SetShortArrayRegion(jPcm, 0, static_cast<jsize>(totalSamples), tlBuf.data());
}

// ============================================================================
// Multi-Track Arranger / Mashup Engine (1..10 Tracks Real-Time + Offline Mixdown)
// ============================================================================

static std::vector<soundtracker::MixClipSpec> unpackMixClipSpecs(
    JNIEnv* env,
    jobjectArray jWavPaths,
    jlongArray jTimelineStarts,
    jlongArray jDurations,
    jdoubleArray jSourceStartSecs,
    jfloatArray jLinearGains,
    jlongArray jFadeIns,
    jlongArray jFadeOuts
) {
    std::vector<soundtracker::MixClipSpec> specs;
    if (!jWavPaths || !jTimelineStarts || !jDurations || !jSourceStartSecs ||
        !jLinearGains || !jFadeIns || !jFadeOuts) {
        return specs;
    }

    const jsize count = env->GetArrayLength(jWavPaths);
    if (count <= 0) return specs;

    std::vector<jlong> starts(static_cast<size_t>(count));
    std::vector<jlong> durs(static_cast<size_t>(count));
    std::vector<jdouble> srcSecs(static_cast<size_t>(count));
    std::vector<jfloat> gains(static_cast<size_t>(count));
    std::vector<jlong> fadeIns(static_cast<size_t>(count));
    std::vector<jlong> fadeOuts(static_cast<size_t>(count));

    env->GetLongArrayRegion(jTimelineStarts, 0, count, starts.data());
    env->GetLongArrayRegion(jDurations, 0, count, durs.data());
    env->GetDoubleArrayRegion(jSourceStartSecs, 0, count, srcSecs.data());
    env->GetFloatArrayRegion(jLinearGains, 0, count, gains.data());
    env->GetLongArrayRegion(jFadeIns, 0, count, fadeIns.data());
    env->GetLongArrayRegion(jFadeOuts, 0, count, fadeOuts.data());

    specs.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto jPath = static_cast<jstring>(env->GetObjectArrayElement(jWavPaths, i));
        soundtracker::MixClipSpec s;
        s.wavPath = jstringToStd(env, jPath);
        if (jPath) env->DeleteLocalRef(jPath);
        s.timelineStartFrame = static_cast<int64_t>(starts[static_cast<size_t>(i)]);
        s.durationFrames = static_cast<int64_t>(durs[static_cast<size_t>(i)]);
        s.sourceStartSec = static_cast<double>(srcSecs[static_cast<size_t>(i)]);
        s.linearGain = static_cast<float>(gains[static_cast<size_t>(i)]);
        s.fadeInFrames = static_cast<int64_t>(fadeIns[static_cast<size_t>(i)]);
        s.fadeOutFrames = static_cast<int64_t>(fadeOuts[static_cast<size_t>(i)]);
        specs.push_back(std::move(s));
    }
    return specs;
}

JNIEXPORT jlong JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeCreateMultiTrackEngine(
    JNIEnv*,
    jobject,
    jint outputSampleRate
) {
    auto* eng = new soundtracker::RealtimeMultiTrackEngine(static_cast<int>(outputSampleRate));
    return reinterpret_cast<jlong>(eng);
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeDestroyMultiTrackEngine(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle == 0) return;
    auto* eng = reinterpret_cast<soundtracker::RealtimeMultiTrackEngine*>(handle);
    delete eng;
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeClearMultiTrackCache(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle == 0) return;
    auto* eng = reinterpret_cast<soundtracker::RealtimeMultiTrackEngine*>(handle);
    eng->clearCache();
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeResetMultiTrackEnvelope(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle == 0) return;
    auto* eng = reinterpret_cast<soundtracker::RealtimeMultiTrackEngine*>(handle);
    eng->resetEnvelope();
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeSetMultiTrackSchedule(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobjectArray jWavPaths,
    jlongArray jTimelineStarts,
    jlongArray jDurations,
    jdoubleArray jSourceStartSecs,
    jfloatArray jLinearGains,
    jlongArray jFadeIns,
    jlongArray jFadeOuts,
    jlong totalDurationFrames
) {
    if (handle == 0) return;
    auto* eng = reinterpret_cast<soundtracker::RealtimeMultiTrackEngine*>(handle);
    auto specs = unpackMixClipSpecs(
        env,
        jWavPaths,
        jTimelineStarts,
        jDurations,
        jSourceStartSecs,
        jLinearGains,
        jFadeIns,
        jFadeOuts
    );
    eng->setSchedule(specs, static_cast<int64_t>(totalDurationFrames));
}

JNIEXPORT void JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeRenderMultiTrackPreviewBlock(
    JNIEnv* env,
    jobject,
    jlong handle,
    jlong timelineStartFrame,
    jshortArray jOutStereoPcm,
    jint numFrames
) {
    if (handle == 0 || !jOutStereoPcm || numFrames <= 0) return;
    auto* eng = reinterpret_cast<soundtracker::RealtimeMultiTrackEngine*>(handle);
    const size_t totalSamples = static_cast<size_t>(numFrames) * 2;
    static thread_local std::vector<int16_t> tlOut;
    if (tlOut.size() < totalSamples) {
        tlOut.resize(totalSamples);
    }
    eng->renderPreviewBlock(static_cast<int64_t>(timelineStartFrame), tlOut.data(), static_cast<int>(numFrames));
    env->SetShortArrayRegion(jOutStereoPcm, 0, static_cast<jsize>(totalSamples), tlOut.data());
}

JNIEXPORT jfloatArray JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeExtractFastWavPeaks(
    JNIEnv* env,
    jobject,
    jstring jWavPath,
    jint numBins
) {
    std::string path = jstringToStd(env, jWavPath);
    std::vector<float> peaks;
    if (!soundtracker::extractFastWavPeaks(path, static_cast<int>(numBins), peaks) || peaks.empty()) {
        return nullptr;
    }
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(peaks.size()));
    if (out) {
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(peaks.size()), peaks.data());
    }
    return out;
}

// Returns nullptr on success, or error/cancellation message string on failure
JNIEXPORT jstring JNICALL
Java_com_soundtracker_app_dsp_NativeAudioEngine_nativeRenderMultiTrackMixdown(
    JNIEnv* env,
    jobject,
    jobjectArray jWavPaths,
    jlongArray jTimelineStarts,
    jlongArray jDurations,
    jdoubleArray jSourceStartSecs,
    jfloatArray jLinearGains,
    jlongArray jFadeIns,
    jlongArray jFadeOuts,
    jlong totalDurationFrames,
    jint outputSampleRate,
    jstring jOutWavPath,
    jobject progressListener
) {
    auto specs = unpackMixClipSpecs(
        env,
        jWavPaths,
        jTimelineStarts,
        jDurations,
        jSourceStartSecs,
        jLinearGains,
        jFadeIns,
        jFadeOuts
    );
    std::string outPath = jstringToStd(env, jOutWavPath);
    auto cb = makeJniProgress(env, progressListener);
    std::string errMsg;
    bool ok = soundtracker::renderMultiTrackMixdownToWav(
        specs,
        static_cast<int64_t>(totalDurationFrames),
        static_cast<int>(outputSampleRate),
        outPath,
        cb,
        &errMsg
    );
    if (ok) return nullptr;
    if (errMsg.empty()) errMsg = "多轨合成失败";
    return env->NewStringUTF(errMsg.c_str());
}

} // extern "C"
