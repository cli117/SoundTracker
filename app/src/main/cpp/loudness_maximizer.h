#pragma once

#include "audio_buffer.h"
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

} // namespace soundtracker
