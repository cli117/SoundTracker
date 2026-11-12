#pragma once

#include "audio_buffer.h"

#include <atomic>
#include <cstdint>
#include <vector>

namespace soundtracker {

struct AudioAnalysis {
    float integratedLufs = -70.0f;
    float truePeakDbtp = -96.0f;
    float samplePeakDbfs = -96.0f;
    float rmsDbfs = -96.0f;
    float pureLosslessHeadroomDb = 0.0f; // headroom up to -0.2 dBTP
    float durationSec = 0.0f;
    int sampleRate = 44100;
    int channels = 2;
    float detectedBpm = 120.0f;
    int detectedKeyFifths = 0;
    bool detectedKeyMinor = false;
    float detectedKeyConfidence = 0.0f;
    std::vector<float> waveformPeaks; // normalized [0..1] envelope bins for UI
};

struct BoostResult {
    bool ok = false;
    bool wasPureLossless = true;
    float postLufs = -70.0f;
    float postTruePeakDbtp = -96.0f;
    float maxGainReductionDb = 0.0f;
};

AudioAnalysis analyzeAudio(const PlanarAudioBuffer& buf, int numWaveformBins = 220);

BoostResult boostVolumeTransparent(
    PlanarAudioBuffer& buf,
    float gainDb,
    float ceilingDbtp = -0.2f,
    const ProgressCallback& onProgress = nullptr
);

// Fast transparent safety true-peak limiter (0 dB pre-gain, ceiling e.g. -0.15 dBTP)
// Used after AI separation or pitch shifting to prevent > 0 dBFS inter-sample overshoots.
float applyTruePeakSafetyLimiter(PlanarAudioBuffer& buf, float ceilingDbtp = -0.15f);

// Low-latency Real-Time Streaming Volume Maximizer + 5ms Lookahead 4x True-Peak Limiter ("边听边调"):
// Matches the exact transfer curve of boostVolumeTransparent() with smooth 8ms zipper-free fader
// ramps, O(1)-per-sample monotonic deque lookahead, dual-release anti-pumping envelope, and zero
// dynamic heap allocations on the audio thread.
class RealtimeVolumeMaximizer {
public:
    RealtimeVolumeMaximizer(int sampleRate, int channels, float ceilingDbtp = -0.2f);

    int sampleRate() const { return sampleRate_; }
    int channels() const { return channels_; }

    // Lock-free gain update safe to call from the UI thread on every slider drag tick
    void setGainDb(float gainDb);

    // Resets delay ring buffer and envelope state (audio thread)
    void reset();

    // Processes `frames` interleaved 16-bit PCM frames in-place / to `outPcm16` (1:1 frame count).
    void processInterleavedInt16(
        const int16_t* inPcm16,
        int16_t* outPcm16,
        int frames
    );

private:
    int sampleRate_;
    int channels_;
    float ceilingLin_;
    float hardGuard_;
    float fastRelCoeff_;
    float slowRelCoeff_;
    float paramSmoothAlpha_;

    size_t lookahead_;
    size_t halfWin_;
    double invHalfWin_;
    size_t ringCap_;
    size_t ringMask_;

    std::atomic<float> targetGainDb_{0.0f};
    float appliedGainLin_ = 1.0f;

    float tpHist_[2][4] = {{0.0f}};
    std::vector<float> audioRing0_;
    std::vector<float> audioRing1_;
    std::vector<float> targetGainRing_;

    std::vector<uint64_t> dequeIdx_;
    std::vector<float> dequeVal_;
    size_t dequeHead_ = 0;
    size_t dequeSize_ = 0;
    uint64_t frameCount_ = 0;

    float fastEnv_ = 1.0f;
    float slowEnv_ = 1.0f;
    std::vector<float> boxcar1Ring_;
    std::vector<float> boxcar2Ring_;
    size_t boxcarPos_ = 0;
    double winSum1_ = 0.0;
    double winSum2_ = 0.0;
};

} // namespace soundtracker
