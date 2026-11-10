#pragma once

#include "audio_buffer.h"

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

namespace RubberBand {
class RubberBandStretcher;
}

namespace soundtracker {

// Studio Offline Polyphonic Pitch Shifter & Time Stretcher powered by Rubber Band Library v4.0 R3 (OptionEngineFiner):
// - Pass 1 (study): Global lookahead multi-resolution transient & harmonic profile analysis
// - Pass 2 (process): Multi-resolution phase-coherent time-scale modification (timeRatio = sourceBpm / targetBpm)
//   + high-order BQ sinc resampling (pitchScale = 2^(semitones / 12)) in a single unified pass
bool pitchShiftPolyphonic(
    const PlanarAudioBuffer& input,
    PlanarAudioBuffer& output,
    float semitones,
    double timeRatio,
    bool preserveTimbre,
    const ProgressCallback& onProgress = nullptr
);

// Low-latency Real-Time Streaming Pitch Shifter & Time Stretcher for live audition preview ("边听边调"):
// Powered by Rubber Band v4.0 R3 Short-Window RealTime Engine with OptionPitchHighConsistency for
// click-free continuous pitch & BPM sweeps during playback.
class RealtimePitchShifter {
public:
    RealtimePitchShifter(int sampleRate, int channels);
    ~RealtimePitchShifter();

    int sampleRate() const { return sampleRate_; }
    int channels() const { return channels_; }

    // Lock-free parameter update safe to call from the UI thread at any frequency
    void setParameters(float semitones, double timeRatio, bool preserveTimbre);

    // Must be called on the audio processing thread (e.g. on seek / flush)
    void reset();

    // Processes interleaved 16-bit PCM frames and appends output frames into internal buffer.
    // Returns number of output frames written to outPcm16 (up to maxOutFrames).
    int processInterleavedInt16(
        const int16_t* inPcm16,
        int inFrames,
        int16_t* outPcm16,
        int maxOutFrames
    );

    // Signals end-of-stream and drains remaining overlap-add tail frames.
    int finishInterleavedInt16(
        int16_t* outPcm16,
        int maxOutFrames
    );

    // Drains any overflow frames left in pendingOutInterleaved_ from a previous call.
    int drainPendingInt16(
        int16_t* outPcm16,
        int maxOutFrames
    );

private:
    void applyPendingParamsOnAudioThread();
    void primeStartPadIfNeeded();
    void drainStretcherToPending();
    int copyPendingToOut(int16_t* outPcm16, int maxOutFrames);

    int sampleRate_;
    int channels_;
    std::unique_ptr<RubberBand::RubberBandStretcher> stretcher_;

    std::atomic<float> targetSemitones_{0.0f};
    std::atomic<double> targetTimeRatio_{1.0};
    std::atomic<bool> targetPreserveTimbre_{false};

    float appliedSemitones_ = 0.0f;
    double appliedTimeRatio_ = 1.0;
    bool appliedPreserveTimbre_ = false;

    bool primed_ = false;
    size_t remainingStartDelayFrames_ = 0;

    std::vector<std::vector<float>> inPlanar_;
    std::vector<std::vector<float>> outPlanar_;
    std::vector<std::vector<float>> zeroPlanar_;
    std::vector<int16_t> pendingOutInterleaved_;
    size_t pendingReadFrameOffset_ = 0;
};

} // namespace soundtracker
