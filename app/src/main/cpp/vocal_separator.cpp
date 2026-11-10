#include "vocal_separator.h"
#include "loudness_maximizer.h"
#include "onnxruntime/onnxruntime_c_api.h"
#include "signalsmith-linear/fft.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace soundtracker {

namespace {

constexpr int kTargetSampleRate = 44100;

struct OrtGlobalEnv {
    std::mutex mutex;
    const OrtApi* ort = nullptr;
    OrtEnv* env = nullptr;

    // Intentionally do NOT call ort->ReleaseEnv(env) inside static C++ atexit destructor:
    // libonnxruntime.so's internal static logger mutexes may already be destroyed during
    // __cxa_finalize, causing std::system_error(EINVAL). Process-wide OrtEnv lives until OS exit.

    bool ensureInit(std::string* errMsg) {
        std::lock_guard<std::mutex> lock(mutex);
        if (ort && env) return true;

        const OrtApiBase* base = OrtGetApiBase();
        if (!base) {
            if (errMsg) *errMsg = "OrtGetApiBase returned null";
            return false;
        }
        ort = base->GetApi(ORT_API_VERSION);
        if (!ort) {
            if (errMsg) *errMsg = "Unsupported ONNX Runtime C API version";
            return false;
        }
        OrtStatus* st = ort->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "SoundTrackerDSP", &env);
        if (st) {
            if (errMsg) *errMsg = std::string("CreateEnv failed: ") + ort->GetErrorMessage(st);
            ort->ReleaseStatus(st);
            env = nullptr;
            return false;
        }
        return true;
    }
};

OrtGlobalEnv& getGlobalOrtEnv() {
    static OrtGlobalEnv instance;
    return instance;
}

bool checkOrt(const OrtApi* ort, OrtStatus* st, const char* ctx, std::string* errMsg) {
    if (!st) return true;
    if (errMsg) {
        *errMsg = std::string(ctx) + ": " + ort->GetErrorMessage(st);
    }
    ort->ReleaseStatus(st);
    return false;
}

// Scoped ONNX session that mmaps directly from Android APK AAsset (zero disk copy!)
// and releases 100% of neural network memory as soon as the separation pass finishes.
struct ScopedOrtSession {
    const OrtApi* ort = nullptr;
    OrtSessionOptions* opts = nullptr;
    OrtSession* session = nullptr;
    OrtMemoryInfo* memInfo = nullptr;
#ifdef __ANDROID__
    AAsset* mappedAsset = nullptr;
#endif
    std::vector<uint8_t> fallbackModelBytes;

    ~ScopedOrtSession() {
        if (ort) {
            if (memInfo) ort->ReleaseMemoryInfo(memInfo);
            if (session) ort->ReleaseSession(session);
            if (opts) ort->ReleaseSessionOptions(opts);
        }
        // Always close mapped AAsset AFTER OrtSession is destroyed so zero-copy initializers stay valid!
#ifdef __ANDROID__
        if (mappedAsset) {
            AAsset_close(mappedAsset);
            mappedAsset = nullptr;
        }
#endif
    }

    bool open(
#ifdef __ANDROID__
        AAssetManager* assetManager,
#endif
        const std::string& modelPath,
        int numThreads,
        GraphOptimizationLevel optLevel,
        std::string* errMsg
    ) {
        auto& g = getGlobalOrtEnv();
        if (!g.ensureInit(errMsg)) return false;
        ort = g.ort;

        if (!checkOrt(ort, ort->CreateSessionOptions(&opts), "CreateSessionOptions", errMsg)) return false;

        const int threads = std::clamp(numThreads, 1, 8);
        if (!checkOrt(ort, ort->SetIntraOpNumThreads(opts, threads), "SetIntraOpNumThreads", errMsg)) return false;
        if (!checkOrt(ort, ort->SetInterOpNumThreads(opts, 1), "SetInterOpNumThreads", errMsg)) return false;
        if (!checkOrt(ort, ort->SetSessionGraphOptimizationLevel(opts, optLevel), "SetGraphOpt", errMsg)) return false;

        // Prevent BFCArena multi-GB over-reservation on mobile RAM
        if (!checkOrt(ort, ort->DisableCpuMemArena(opts), "DisableCpuMemArena", errMsg)) return false;
        if (!checkOrt(ort, ort->DisableMemPattern(opts), "DisableMemPattern", errMsg)) return false;
        // Allow ORT to reference initializers directly inside mmap'd APK buffer without copying
        OrtStatus* cfgSt = ort->AddSessionConfigEntry(opts, "session.use_ort_model_bytes_for_initializers", "1");
        if (cfgSt) ort->ReleaseStatus(cfgSt); // Non-fatal if unsupported on older host builds

        if (!checkOrt(
                ort,
                ort->CreateCpuMemoryInfo(OrtDeviceAllocator, OrtMemTypeDefault, &memInfo),
                "CreateCpuMemoryInfo",
                errMsg
            )) {
            return false;
        }

#ifdef __ANDROID__
        if (assetManager) {
            AAsset* asset = AAssetManager_open(assetManager, modelPath.c_str(), AASSET_MODE_BUFFER);
            if (!asset) {
                if (errMsg) *errMsg = "APK 模型资源不存在: " + modelPath;
                return false;
            }
            const off64_t assetLen = AAsset_getLength64(asset);
            const void* directBuf = AAsset_getBuffer(asset);
            if (directBuf && assetLen > 0) {
                mappedAsset = asset;
                return checkOrt(
                    ort,
                    ort->CreateSessionFromArray(g.env, directBuf, static_cast<size_t>(assetLen), opts, &session),
                    "CreateSessionFromArray(mmap)",
                    errMsg
                );
            }
            // Fallback streaming read if asset was compressed on a custom package build
            if (assetLen <= 0) {
                AAsset_close(asset);
                if (errMsg) *errMsg = "APK 模型长度为 0: " + modelPath;
                return false;
            }
            fallbackModelBytes.resize(static_cast<size_t>(assetLen));
            size_t totalRead = 0;
            while (totalRead < fallbackModelBytes.size()) {
                int r = AAsset_read(
                    asset,
                    fallbackModelBytes.data() + totalRead,
                    fallbackModelBytes.size() - totalRead
                );
                if (r <= 0) break;
                totalRead += static_cast<size_t>(r);
            }
            AAsset_close(asset);
            if (totalRead != fallbackModelBytes.size()) {
                if (errMsg) *errMsg = "读取模型流不完整: " + modelPath;
                return false;
            }
            return checkOrt(
                ort,
                ort->CreateSessionFromArray(g.env, fallbackModelBytes.data(), fallbackModelBytes.size(), opts, &session),
                "CreateSessionFromArray(stream)",
                errMsg
            );
        }
#endif
        return checkOrt(ort, ort->CreateSession(g.env, modelPath.c_str(), opts, &session), "CreateSession", errMsg);
    }
};

// Safe periodic mirror reflection for arbitrary negative/overflow indices and any len >= 1
inline size_t reflectIndex(int64_t idx, int64_t len) {
    if (len <= 1) return 0;
    const int64_t period = 2 * (len - 1);
    int64_t m = idx % period;
    if (m < 0) m += period;
    if (m >= len) m = period - m;
    return static_cast<size_t>(m);
}

// ============================================================================
// Engine 1: Inst-HQ5 Full-Band (0 - 22.05 kHz) Direct Instrumental Synthesizer
// n_fft = 5120, hop = 1024, dim_f = 2560, dim_t = 256 (chunk = 261,120 samples)
// ============================================================================
bool runInstHq5Pass(
    const SeparationModelPaths& models,
    const PlanarAudioBuffer& work,
    PlanarAudioBuffer& outAcc,
    int numThreads,
    float progressStart,
    float progressSpan,
    const ProgressCallback& onProgress,
    std::string* errMsg
) {
    constexpr size_t kNfft = 5120;
    constexpr size_t kHop = 1024;
    constexpr size_t kDimF = 2560;
    constexpr size_t kDimT = 256;
    constexpr size_t kPad = kNfft / 2;                      // 2560
    constexpr size_t kChunkSamples = kHop * (kDimT - 1);    // 261,120 (~5.921s)
    constexpr size_t kPaddedChunkLen = kChunkSamples + kNfft; // 266,240
    constexpr size_t kStride = kChunkSamples / 2;           // 50% Hann/triangular overlap
    constexpr float kCompensate = 1.015f;

    ScopedOrtSession sess;
    if (!sess.open(
#ifdef __ANDROID__
            models.assetManager,
#endif
            models.instHq5Path,
            numThreads,
            ORT_ENABLE_ALL,
            errMsg
        )) {
        return false;
    }

    const size_t N = work.numFrames();
    const int64_t N64 = static_cast<int64_t>(N);
    const size_t margin = kChunkSamples / 2;
    const size_t totalSpan = margin + N + margin + kChunkSamples;

    // Periodic Hann window matching torch.hann_window(5120, periodic=True)
    std::vector<float> hannWin(kNfft);
    for (size_t n = 0; n < kNfft; ++n) {
        hannWin[n] = 0.5f - 0.5f * std::cos(static_cast<float>(2.0 * M_PI * static_cast<double>(n) / static_cast<double>(kNfft)));
    }

    // Precompute inverse COLA window-squared envelope + 1/NFFT scaling for exact iSTFT unity reconstruction
    std::vector<float> invOlaNorm(kPaddedChunkLen, 0.0f);
    for (size_t t = 0; t < kDimT; ++t) {
        const size_t base = t * kHop;
        for (size_t n = 0; n < kNfft; ++n) {
            invOlaNorm[base + n] += hannWin[n] * hannWin[n];
        }
    }
    const float invNfft = 1.0f / static_cast<float>(kNfft);
    for (size_t i = 0; i < kPaddedChunkLen; ++i) {
        invOlaNorm[i] = (invNfft * kCompensate) / std::max(invOlaNorm[i], 1e-8f);
    }

    // Chunk crossfade weight (50% raised linear triangle)
    std::vector<float> chunkWeight(kChunkSamples, 1.0f);
    const size_t ramp = kChunkSamples / 2;
    for (size_t i = 0; i < ramp; ++i) {
        float w = static_cast<float>(i + 1) / static_cast<float>(ramp);
        chunkWeight[i] = w;
        chunkWeight[kChunkSamples - 1 - i] = w;
    }

    signalsmith::linear::RealFFT<float> fft(kNfft);
    std::vector<float> timeFrame(kNfft);
    std::vector<std::complex<float>> freqFrame(kNfft / 2); // 2560 complex bins
    std::vector<float> chunkWaveL(kChunkSamples);
    std::vector<float> chunkWaveR(kChunkSamples);
    std::vector<float> paddedWave(kPaddedChunkLen);
    std::vector<float> olaSignal(kPaddedChunkLen);

    constexpr size_t kSpecElems = 4 * kDimF * kDimT;
    std::vector<float> specIn(kSpecElems);
    std::vector<float> specNeg;
    if (models.enableInstDenoise) {
        specNeg.resize(kSpecElems);
    }

    std::vector<float> accSumL(totalSpan, 0.0f);
    std::vector<float> accSumR(totalSpan, 0.0f);
    std::vector<float> wSum(totalSpan, 0.0f);

    const int64_t inputShape[4] = {1, 4, static_cast<int64_t>(kDimF), static_cast<int64_t>(kDimT)};
    const char* inNames[1] = {"input"};
    const char* outNames[1] = {"output"};

    const size_t targetEnd = margin + N;
    const size_t totalChunks = (targetEnd + kStride - 1) / kStride;
    size_t chunkIdx = 0;

    for (size_t pos = 0; pos < targetEnd; pos += kStride, ++chunkIdx) {
        if (onProgress) {
            float p = progressStart + progressSpan * (static_cast<float>(chunkIdx) / static_cast<float>(std::max<size_t>(1, totalChunks)));
            if (!onProgress(p)) {
                if (errMsg) *errMsg = "Cancelled";
                return false;
            }
        }

        const float* srcL = work.channels[0].data();
        const float* srcR = work.channels[1].data();
        for (size_t i = 0; i < kChunkSamples; ++i) {
            const int64_t songIdx = static_cast<int64_t>(pos + i) - static_cast<int64_t>(margin);
            const size_t rIdx = reflectIndex(songIdx, N64);
            chunkWaveL[i] = srcL[rIdx];
            chunkWaveR[i] = srcR[rIdx];
        }

        // Forward STFT for Left (c=0) & Right (c=1) -> specIn [1, 4, 2560, 256]
        for (int c = 0; c < 2; ++c) {
            const std::vector<float>& chWave = (c == 0) ? chunkWaveL : chunkWaveR;
            for (size_t i = 0; i < kPaddedChunkLen; ++i) {
                const int64_t cIdx = static_cast<int64_t>(i) - static_cast<int64_t>(kPad);
                paddedWave[i] = chWave[reflectIndex(cIdx, static_cast<int64_t>(kChunkSamples))];
            }

            float* dstRealPlane = specIn.data() + (c * 2 + 0) * (kDimF * kDimT);
            float* dstImagPlane = specIn.data() + (c * 2 + 1) * (kDimF * kDimT);

            for (size_t t = 0; t < kDimT; ++t) {
                const float* framePtr = paddedWave.data() + t * kHop;
                for (size_t n = 0; n < kNfft; ++n) {
                    timeFrame[n] = framePtr[n] * hannWin[n];
                }
                fft.fft(timeFrame.data(), freqFrame.data());

                // signalsmith RealFFT packs {DC, Nyquist} into freqFrame[0]
                dstRealPlane[0 * kDimT + t] = freqFrame[0].real();
                dstImagPlane[0 * kDimT + t] = 0.0f;
                for (size_t f = 1; f < kDimF; ++f) {
                    dstRealPlane[f * kDimT + t] = freqFrame[f].real();
                    dstImagPlane[f * kDimT + t] = freqFrame[f].imag();
                }
            }
        }

        // Execute ONNX forward pass
        OrtValue* inTensor = nullptr;
        OrtValue* outTensor = nullptr;
        if (!checkOrt(
                sess.ort,
                sess.ort->CreateTensorWithDataAsOrtValue(
                    sess.memInfo,
                    specIn.data(),
                    specIn.size() * sizeof(float),
                    inputShape,
                    4,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
                    &inTensor
                ),
                "CreateTensor(InstHQ5)",
                errMsg
            )) {
            return false;
        }

        OrtStatus* runSt = sess.ort->Run(sess.session, nullptr, inNames, &inTensor, 1, outNames, 1, &outTensor);
        sess.ort->ReleaseValue(inTensor);
        if (!checkOrt(sess.ort, runSt, "Run(InstHQ5)", errMsg)) {
            if (outTensor) sess.ort->ReleaseValue(outTensor);
            return false;
        }

        float* predSpec = nullptr;
        if (!checkOrt(sess.ort, sess.ort->GetTensorMutableData(outTensor, reinterpret_cast<void**>(&predSpec)), "GetData(InstHQ5)", errMsg)) {
            sess.ort->ReleaseValue(outTensor);
            return false;
        }

        // Optional dual-polarity denoise pass: 0.5 * (f(x) - f(-x)) cancels DC/BN bias floor
        if (models.enableInstDenoise) {
            for (size_t k = 0; k < kSpecElems; ++k) specNeg[k] = -specIn[k];
            OrtValue* negInTensor = nullptr;
            OrtValue* negOutTensor = nullptr;
            if (!checkOrt(
                    sess.ort,
                    sess.ort->CreateTensorWithDataAsOrtValue(
                        sess.memInfo,
                        specNeg.data(),
                        specNeg.size() * sizeof(float),
                        inputShape,
                        4,
                        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
                        &negInTensor
                    ),
                    "CreateNegTensor(InstHQ5)",
                    errMsg
                )) {
                sess.ort->ReleaseValue(outTensor);
                return false;
            }
            OrtStatus* negSt = sess.ort->Run(sess.session, nullptr, inNames, &negInTensor, 1, outNames, 1, &negOutTensor);
            sess.ort->ReleaseValue(negInTensor);
            if (!checkOrt(sess.ort, negSt, "RunNeg(InstHQ5)", errMsg)) {
                if (negOutTensor) sess.ort->ReleaseValue(negOutTensor);
                sess.ort->ReleaseValue(outTensor);
                return false;
            }
            float* predNeg = nullptr;
            OrtStatus* getNegSt = sess.ort->GetTensorMutableData(negOutTensor, reinterpret_cast<void**>(&predNeg));
            if (getNegSt) {
                sess.ort->ReleaseStatus(getNegSt);
                predNeg = nullptr;
            }
            if (predNeg) {
                for (size_t k = 0; k < kSpecElems; ++k) {
                    predSpec[k] = 0.5f * (predSpec[k] - predNeg[k]);
                }
            }
            sess.ort->ReleaseValue(negOutTensor);
        }

        // Inverse STFT for Left & Right and overlap-add into output accumulator
        for (int c = 0; c < 2; ++c) {
            std::fill(olaSignal.begin(), olaSignal.end(), 0.0f);
            const float* srcRealPlane = predSpec + (c * 2 + 0) * (kDimF * kDimT);
            const float* srcImagPlane = predSpec + (c * 2 + 1) * (kDimF * kDimT);

            for (size_t t = 0; t < kDimT; ++t) {
                // DC real + 0.0f Nyquist bin (bin 2560 is zero-padded in torch.istft)
                freqFrame[0] = {srcRealPlane[0 * kDimT + t], 0.0f};
                for (size_t f = 1; f < kDimF; ++f) {
                    freqFrame[f] = {srcRealPlane[f * kDimT + t], srcImagPlane[f * kDimT + t]};
                }
                fft.ifft(freqFrame.data(), timeFrame.data());

                float* dstOla = olaSignal.data() + t * kHop;
                for (size_t n = 0; n < kNfft; ++n) {
                    dstOla[n] += timeFrame[n] * hannWin[n];
                }
            }

            float* dstAccSum = (c == 0) ? accSumL.data() : accSumR.data();
            for (size_t i = 0; i < kChunkSamples; ++i) {
                const float sample = olaSignal[kPad + i] * invOlaNorm[kPad + i];
                dstAccSum[pos + i] += sample * chunkWeight[i];
            }
        }

        for (size_t i = 0; i < kChunkSamples; ++i) {
            wSum[pos + i] += chunkWeight[i];
        }

        sess.ort->ReleaseValue(outTensor);
    }

    outAcc.sampleRate = kTargetSampleRate;
    outAcc.resize(2, N);
    for (size_t i = 0; i < N; ++i) {
        const float invW = 1.0f / std::max(wSum[margin + i], 1e-8f);
        outAcc.channels[0][i] = accSumL[margin + i] * invW;
        outAcc.channels[1][i] = accSumR[margin + i] * invW;
    }

    return true;
}

// ============================================================================
// Engine 2: Meta AI HTDemucs-FT v4 Vocals Specialist (Hybrid Time+Freq Transformer)
// Input "mix": [1, 2, 343980] -> Output "stems": [1, 4, 2, 343980] (row 3 = vocals)
// NOTE: Uses ORT_DISABLE_ALL to prevent ConstantFolding from unfolding STFT constants
// into 8 GB RAM (keeps mobile RSS <= 2.0 GB).
// ============================================================================
bool runHtDemucsFtPass(
    const SeparationModelPaths& models,
    const PlanarAudioBuffer& work,
    PlanarAudioBuffer& outAcc,
    int numThreads,
    float progressStart,
    float progressSpan,
    const ProgressCallback& onProgress,
    std::string* errMsg
) {
    constexpr size_t kSegSamples = 343980;            // 7.8s @ 44.1kHz
    constexpr size_t kOverlap = kSegSamples / 4;      // 85,995 (25% crossfade)
    constexpr size_t kStride = kSegSamples - kOverlap;// 257,985

    ScopedOrtSession sess;
    if (!sess.open(
#ifdef __ANDROID__
            models.assetManager,
#endif
            models.htdemucsFtPath,
            numThreads,
            ORT_DISABLE_ALL,
            errMsg
        )) {
        return false;
    }

    const size_t N = work.numFrames();
    const int64_t N64 = static_cast<int64_t>(N);
    const size_t margin = kOverlap;
    const size_t totalSpan = margin + N + margin + kSegSamples;

    std::vector<float> window(kSegSamples, 1.0f);
    for (size_t i = 0; i < kOverlap; ++i) {
        float w = static_cast<float>(i) / static_cast<float>(kOverlap - 1);
        window[i] = w;
        window[kSegSamples - 1 - i] = w;
    }

    std::vector<float> mixChunk(2 * kSegSamples);
    std::vector<float> accSumL(totalSpan, 0.0f);
    std::vector<float> accSumR(totalSpan, 0.0f);
    std::vector<float> wSum(totalSpan, 0.0f);

    const int64_t inputShape[3] = {1, 2, static_cast<int64_t>(kSegSamples)};
    const char* inNames[1] = {"mix"};
    const char* outNames[1] = {"stems"};

    const size_t targetEnd = margin + N;
    const size_t totalChunks = (targetEnd + kStride - 1) / kStride;
    size_t chunkIdx = 0;

    for (size_t pos = 0; pos < targetEnd; pos += kStride, ++chunkIdx) {
        if (onProgress) {
            float p = progressStart + progressSpan * (static_cast<float>(chunkIdx) / static_cast<float>(std::max<size_t>(1, totalChunks)));
            if (!onProgress(p)) {
                if (errMsg) *errMsg = "Cancelled";
                return false;
            }
        }

        float* mixL = mixChunk.data();
        float* mixR = mixChunk.data() + kSegSamples;
        const float* srcL = work.channels[0].data();
        const float* srcR = work.channels[1].data();

        for (size_t i = 0; i < kSegSamples; ++i) {
            const int64_t songIdx = static_cast<int64_t>(pos + i) - static_cast<int64_t>(margin);
            const size_t rIdx = reflectIndex(songIdx, N64);
            mixL[i] = srcL[rIdx];
            mixR[i] = srcR[rIdx];
        }

        OrtValue* inTensor = nullptr;
        OrtValue* outTensor = nullptr;
        if (!checkOrt(
                sess.ort,
                sess.ort->CreateTensorWithDataAsOrtValue(
                    sess.memInfo,
                    mixChunk.data(),
                    mixChunk.size() * sizeof(float),
                    inputShape,
                    3,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
                    &inTensor
                ),
                "CreateTensor(HTDemucsFT)",
                errMsg
            )) {
            return false;
        }

        OrtStatus* runSt = sess.ort->Run(sess.session, nullptr, inNames, &inTensor, 1, outNames, 1, &outTensor);
        sess.ort->ReleaseValue(inTensor);
        if (!checkOrt(sess.ort, runSt, "Run(HTDemucsFT)", errMsg)) {
            if (outTensor) sess.ort->ReleaseValue(outTensor);
            return false;
        }

        float* stemsData = nullptr;
        if (!checkOrt(sess.ort, sess.ort->GetTensorMutableData(outTensor, reinterpret_cast<void**>(&stemsData)), "GetData(HTDemucsFT)", errMsg)) {
            sess.ort->ReleaseValue(outTensor);
            return false;
        }

        // Output layout: [1, 4, 2, kSegSamples], where index 3 = specialist vocals stem
        const float* vocL = stemsData + (3 * 2 + 0) * kSegSamples;
        const float* vocR = stemsData + (3 * 2 + 1) * kSegSamples;

        for (size_t i = 0; i < kSegSamples; ++i) {
            const float w = window[i];
            accSumL[pos + i] += (mixL[i] - vocL[i]) * w;
            accSumR[pos + i] += (mixR[i] - vocR[i]) * w;
            wSum[pos + i] += w;
        }

        sess.ort->ReleaseValue(outTensor);
    }

    outAcc.sampleRate = kTargetSampleRate;
    outAcc.resize(2, N);
    for (size_t i = 0; i < N; ++i) {
        const float invW = 1.0f / std::max(wSum[margin + i], 1e-8f);
        outAcc.channels[0][i] = accSumL[margin + i] * invW;
        outAcc.channels[1][i] = accSumR[margin + i] * invW;
    }

    return true;
}

// UVR-style Min-Spec Crossover Fusion for HYBRID_ENSEMBLE:
// - Below 150 Hz: 50% Inst-HQ5 + 50% Meta HTDemucs-FT v4 (punchy time-domain kick & sub-bass)
// - Above 270 Hz (smooth cosine ramp 150..270 Hz):
//   Magnitude = 0.70 * min(|Z_inst|, |Z_demucs|) + 0.30 * |Z_inst|, rejecting any residual vocal/harmony
//   leakage from either architecture while preserving complex phase coherence.
void minSpecCrossoverFuse(
    const PlanarAudioBuffer& accInst,
    const PlanarAudioBuffer& accDemucs,
    PlanarAudioBuffer& outAcc
) {
    constexpr size_t kNfft = 4096;
    constexpr size_t kHop = 1024;
    constexpr size_t kHalf = kNfft / 2;
    constexpr size_t kPad = kNfft / 2;

    const size_t N = std::min(accInst.numFrames(), accDemucs.numFrames());
    outAcc.sampleRate = kTargetSampleRate;
    outAcc.resize(2, N);
    if (N == 0) return;

    const int64_t N64 = static_cast<int64_t>(N);
    const size_t minTotal = N + 2 * kPad;
    const size_t numFrames = (minTotal <= kNfft)
        ? 1
        : ((minTotal - kNfft + kHop - 1) / kHop + 1);
    const size_t paddedLen = (numFrames - 1) * kHop + kNfft;

    std::vector<float> hannWin(kNfft);
    for (size_t n = 0; n < kNfft; ++n) {
        hannWin[n] = 0.5f - 0.5f * std::cos(static_cast<float>(2.0 * M_PI * static_cast<double>(n) / static_cast<double>(kNfft)));
    }

    std::vector<float> invNorm(paddedLen, 0.0f);
    for (size_t t = 0; t < numFrames; ++t) {
        const size_t base = t * kHop;
        for (size_t n = 0; n < kNfft; ++n) {
            invNorm[base + n] += hannWin[n] * hannWin[n];
        }
    }
    const float invNfft = 1.0f / static_cast<float>(kNfft);
    for (size_t i = 0; i < paddedLen; ++i) {
        invNorm[i] = invNfft / std::max(invNorm[i], 1e-8f);
    }

    // Precompute frequency crossover weights alpha[f] in [0, 1] for bins 1..kHalf-1
    std::vector<float> alpha(kHalf, 0.0f);
    const float binHz = static_cast<float>(kTargetSampleRate) / static_cast<float>(kNfft);
    for (size_t f = 1; f < kHalf; ++f) {
        const float hz = static_cast<float>(f) * binHz;
        if (hz <= 150.0f) {
            alpha[f] = 0.0f;
        } else if (hz >= 270.0f) {
            alpha[f] = 1.0f;
        } else {
            const float u = (hz - 150.0f) / (270.0f - 150.0f);
            alpha[f] = 0.5f - 0.5f * std::cos(static_cast<float>(M_PI) * u);
        }
    }

    signalsmith::linear::RealFFT<float> fft(kNfft);
    std::vector<float> padA(paddedLen), padB(paddedLen), olaOut(paddedLen);
    std::vector<float> timeA(kNfft), timeB(kNfft), timeOut(kNfft);
    std::vector<std::complex<float>> specA(kHalf), specB(kHalf), specOut(kHalf);

    for (int c = 0; c < 2; ++c) {
        const float* srcA = accInst.channels[c].data();
        const float* srcB = accDemucs.channels[c].data();
        for (size_t i = 0; i < paddedLen; ++i) {
            const size_t rIdx = reflectIndex(static_cast<int64_t>(i) - static_cast<int64_t>(kPad), N64);
            padA[i] = srcA[rIdx];
            padB[i] = srcB[rIdx];
        }

        std::fill(olaOut.begin(), olaOut.end(), 0.0f);
        for (size_t t = 0; t < numFrames; ++t) {
            const size_t base = t * kHop;
            for (size_t n = 0; n < kNfft; ++n) {
                timeA[n] = padA[base + n] * hannWin[n];
                timeB[n] = padB[base + n] * hannWin[n];
            }
            fft.fft(timeA.data(), specA.data());
            fft.fft(timeB.data(), specB.data());

            // Bin 0 packs DC in real() and Nyquist (22.05 kHz) in imag()
            const float dcOut = 0.5f * (specA[0].real() + specB[0].real());
            const float nyqA = specA[0].imag();
            const float nyqB = specB[0].imag();
            const float nyqMag = 0.70f * std::min(std::abs(nyqA), std::abs(nyqB)) + 0.30f * std::abs(nyqA);
            const float nyqRef = 0.65f * nyqA + 0.35f * nyqB;
            const float nyqOut = (nyqRef >= 0.0f) ? nyqMag : -nyqMag;
            specOut[0] = {dcOut, nyqOut};

            for (size_t f = 1; f < kHalf; ++f) {
                const std::complex<float> za = specA[f];
                const std::complex<float> zb = specB[f];
                const std::complex<float> zLow = 0.5f * (za + zb);
                const float w = alpha[f];
                if (w <= 0.0f) {
                    specOut[f] = zLow;
                } else {
                    const float magA = std::abs(za);
                    const float magB = std::abs(zb);
                    const float targetMag = 0.70f * std::min(magA, magB) + 0.30f * magA;
                    const std::complex<float> zRef = 0.65f * za + 0.35f * zb;
                    const float refMag = std::abs(zRef);
                    std::complex<float> zHigh;
                    if (refMag > 1e-12f) {
                        zHigh = zRef * (targetMag / refMag);
                    } else if (magA > 1e-12f) {
                        zHigh = za * (targetMag / magA);
                    } else {
                        zHigh = {0.0f, 0.0f};
                    }
                    specOut[f] = (1.0f - w) * zLow + w * zHigh;
                }
            }

            fft.ifft(specOut.data(), timeOut.data());
            for (size_t n = 0; n < kNfft; ++n) {
                olaOut[base + n] += timeOut[n] * hannWin[n];
            }
        }

        float* dst = outAcc.channels[c].data();
        for (size_t i = 0; i < N; ++i) {
            dst[i] = olaOut[kPad + i] * invNorm[kPad + i];
        }
    }
}

std::mutex& getSeparationMutex() {
    static std::mutex mtx;
    return mtx;
}

} // namespace

bool separateVocalsStudio(
    const SeparationModelPaths& models,
    VocalSeparationMode mode,
    const PlanarAudioBuffer& input,
    PlanarAudioBuffer& outAccompaniment,
    PlanarAudioBuffer* outVocals,
    int numThreads,
    const ProgressCallback& onProgress,
    std::string* errMsg
) {
    if (input.numChannels() == 0 || input.numFrames() == 0) {
        if (errMsg) *errMsg = "输入音频为空";
        return false;
    }

    std::lock_guard<std::mutex> lock(getSeparationMutex());

    if (onProgress && !onProgress(0.02f)) {
        if (errMsg) *errMsg = "Cancelled";
        return false;
    }

    // Ensure 44.1 kHz stereo via high-precision Kaiser polyphase sinc resampler
    PlanarAudioBuffer work = (input.sampleRate == kTargetSampleRate)
        ? input
        : resampleSinc(input, kTargetSampleRate);
    work.ensureStereo();

    const size_t N = work.numFrames();
    if (onProgress && !onProgress(0.04f)) {
        if (errMsg) *errMsg = "Cancelled";
        return false;
    }

    if (mode == VocalSeparationMode::INST_HQ5_DIRECT) {
        if (!runInstHq5Pass(models, work, outAccompaniment, numThreads, 0.05f, 0.91f, onProgress, errMsg)) {
            return false;
        }
    } else if (mode == VocalSeparationMode::HTDEMUCS_FT) {
        if (!runHtDemucsFtPass(models, work, outAccompaniment, numThreads, 0.05f, 0.91f, onProgress, errMsg)) {
            return false;
        }
    } else {
        // HYBRID_ENSEMBLE: Sequential execution ensures Session 1 RAM is 100% freed before Session 2 loads!
        PlanarAudioBuffer accInst;
        if (!runInstHq5Pass(models, work, accInst, numThreads, 0.05f, 0.45f, onProgress, errMsg)) {
            return false;
        }
        PlanarAudioBuffer accDemucs;
        if (!runHtDemucsFtPass(models, work, accDemucs, numThreads, 0.50f, 0.45f, onProgress, errMsg)) {
            return false;
        }

        minSpecCrossoverFuse(accInst, accDemucs, outAccompaniment);
    }

    // Derive isolated vocal stem if requested (exact phase complement: mix - accompaniment)
    if (outVocals) {
        outVocals->sampleRate = kTargetSampleRate;
        outVocals->resize(2, N);
        for (int c = 0; c < 2; ++c) {
            const float* mixCh = work.channels[c].data();
            const float* accCh = outAccompaniment.channels[c].data();
            float* vocCh = outVocals->channels[c].data();
            for (size_t i = 0; i < N; ++i) {
                vocCh[i] = mixCh[i] - accCh[i];
            }
        }
    }

    // Transparent -0.15 dBTP safety limiter so stems never clip > 0 dBFS
    applyTruePeakSafetyLimiter(outAccompaniment, -0.15f);
    if (outVocals) {
        applyTruePeakSafetyLimiter(*outVocals, -0.15f);
    }

    if (onProgress) onProgress(1.0f);
    return true;
}

} // namespace soundtracker
