#include "pitch_shifter.h"
#include "loudness_maximizer.h"

#include "rubberband/RubberBandStretcher.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace soundtracker {

bool pitchShiftPolyphonic(
    const PlanarAudioBuffer& input,
    PlanarAudioBuffer& output,
    float semitones,
    double timeRatio,
    bool preserveTimbre,
    const ProgressCallback& onProgress
) {
    const int nCh = input.numChannels();
    const size_t inputLength = input.numFrames();
    const int sr = input.sampleRate;
    if (nCh <= 0 || inputLength == 0 || sr <= 0) {
        return false;
    }

    const double clampedTimeRatio = std::clamp(timeRatio, 0.25, 4.0);
    const double clampedSemitones = std::clamp(static_cast<double>(semitones), -24.0, 24.0);

    // Zero shift + 1.0x speed shortcut: exact bit-identical copy
    if (std::abs(clampedSemitones) < 0.005 && std::abs(clampedTimeRatio - 1.0) < 0.0005) {
        output = input;
        if (onProgress) onProgress(1.0f);
        return true;
    }

    const double pitchScale = std::pow(2.0, clampedSemitones / 12.0);
    const size_t targetOutputLength = std::max<size_t>(
        1,
        static_cast<size_t>(std::llround(static_cast<double>(inputLength) * clampedTimeRatio))
    );

    using RB = RubberBand::RubberBandStretcher;
    RB::Options options = RB::OptionProcessOffline
                        | RB::OptionEngineFiner
                        | RB::OptionWindowStandard
                        | RB::OptionThreadingNever;

    // Lock stereo pair centre clarity & mono speaker compatibility on 2-channel mixes
    if (nCh == 2) {
        options |= RB::OptionChannelsTogether;
    } else {
        options |= RB::OptionChannelsApart;
    }

    // Accompaniment default (preserveTimbre == false): OptionFormantShifted preserves exact integer
    // harmonic ratios across 0..22.05 kHz with zero vocal-tract envelope modulation ("zero autotune").
    // Full-mix vocal mode (preserveTimbre == true): R3 OptionFormantPreserved protects lead vocal timbre.
    if (preserveTimbre) {
        options |= RB::OptionFormantPreserved;
    } else {
        options |= RB::OptionFormantShifted;
    }

    RB stretcher(
        static_cast<size_t>(sr),
        static_cast<size_t>(nCh),
        options,
        clampedTimeRatio, // Exact duration ratio = sourceBpm / targetBpm
        pitchScale        // Exact musical frequency ratio = 2^(semitones / 12)
    );

    if (stretcher.getEngineVersion() != 3) {
        return false;
    }

    constexpr size_t kBlockFrames = 8192;
    stretcher.setExpectedInputDuration(inputLength);
    stretcher.setMaxProcessSize(kBlockFrames);

    if (onProgress && !onProgress(0.02f)) {
        return false;
    }

    std::vector<const float*> inPtrs(static_cast<size_t>(nCh));

    // Pass 1: Global offline multi-resolution transient & harmonic profile analysis (study)
    size_t inCursor = 0;
    size_t stepCounter = 0;
    while (inCursor < inputLength) {
        const size_t count = std::min(kBlockFrames, inputLength - inCursor);
        const bool isFinal = (inCursor + count >= inputLength);
        for (int c = 0; c < nCh; ++c) {
            inPtrs[c] = input.channels[c].data() + inCursor;
        }
        stretcher.study(inPtrs.data(), count, isFinal);
        inCursor += count;

        if (onProgress && ((++stepCounter & 3u) == 0u || isFinal)) {
            const float p = 0.02f + 0.22f * (static_cast<float>(inCursor) / static_cast<float>(inputLength));
            if (!onProgress(p)) {
                return false;
            }
        }
    }

    // Pass 2: Multi-resolution R3 synthesis + high-order polyphase sinc resampling
    output.sampleRate = sr;
    output.resize(nCh, targetOutputLength);

    std::vector<float*> outPtrs(static_cast<size_t>(nCh));
    std::vector<std::vector<float>> overflowScratch;

    auto drainAvailable = [&](size_t& outCursor) {
        int avail = 0;
        while ((avail = stretcher.available()) > 0) {
            const size_t availFrames = static_cast<size_t>(avail);
            if (outCursor < targetOutputLength) {
                const size_t toRead = std::min(availFrames, targetOutputLength - outCursor);
                for (int c = 0; c < nCh; ++c) {
                    outPtrs[c] = output.channels[c].data() + outCursor;
                }
                const size_t got = stretcher.retrieve(outPtrs.data(), toRead);
                if (got == 0) break;
                outCursor += got;
            } else {
                if (overflowScratch.empty()) {
                    overflowScratch.assign(static_cast<size_t>(nCh), std::vector<float>(kBlockFrames));
                }
                const size_t toDiscard = std::min(availFrames, kBlockFrames);
                for (int c = 0; c < nCh; ++c) {
                    outPtrs[c] = overflowScratch[c].data();
                }
                const size_t got = stretcher.retrieve(outPtrs.data(), toDiscard);
                if (got == 0) break;
            }
        }
    };

    inCursor = 0;
    size_t outCursor = 0;
    stepCounter = 0;
    while (inCursor < inputLength) {
        const size_t count = std::min(kBlockFrames, inputLength - inCursor);
        const bool isFinal = (inCursor + count >= inputLength);
        for (int c = 0; c < nCh; ++c) {
            inPtrs[c] = input.channels[c].data() + inCursor;
        }
        stretcher.process(inPtrs.data(), count, isFinal);
        inCursor += count;

        drainAvailable(outCursor);

        if (onProgress && ((++stepCounter & 3u) == 0u || isFinal)) {
            const float p = 0.24f + 0.72f * (static_cast<float>(inCursor) / static_cast<float>(inputLength));
            if (!onProgress(p)) {
                return false;
            }
        }
    }

    drainAvailable(outCursor);

    // Transparent -0.15 dBTP 4x-oversampled True-Peak safety limiter prevents phase-rotation clipping
    applyTruePeakSafetyLimiter(output, -0.15f);

    if (onProgress) {
        onProgress(1.0f);
    }
    return true;
}

namespace {

constexpr size_t kRealtimeMaxBlockFrames = 1024;
constexpr int kRealtimeSubBlockFrames = 512;

inline int16_t floatToSoftLimitedInt16(float x) {
    if (!std::isfinite(x)) return 0;
    const float ax = std::abs(x);
    if (ax > 0.95f) {
        const float excess = (ax - 0.95f) * 20.0f;
        const float soft = 0.95f + 0.05f * std::tanh(excess);
        x = std::copysign(soft, x);
    }
    const int32_t s = static_cast<int32_t>(std::lrintf(x * 32767.0f));
    return static_cast<int16_t>(std::clamp(s, -32768, 32767));
}

} // namespace

RealtimePitchShifter::RealtimePitchShifter(int sampleRate, int channels)
    : sampleRate_(std::clamp(sampleRate, 8000, 192000)),
      channels_(std::clamp(channels, 1, 2)) {
    using RB = RubberBand::RubberBandStretcher;
    RB::Options options = RB::OptionProcessRealTime
                        | RB::OptionEngineFiner
                        | RB::OptionWindowShort
                        | RB::OptionPitchHighConsistency
                        | RB::OptionThreadingNever
                        | RB::OptionFormantShifted;

    if (channels_ == 2) {
        options |= RB::OptionChannelsTogether;
    } else {
        options |= RB::OptionChannelsApart;
    }

    stretcher_ = std::make_unique<RB>(
        static_cast<size_t>(sampleRate_),
        static_cast<size_t>(channels_),
        options,
        1.0,
        1.0
    );
    stretcher_->setMaxProcessSize(kRealtimeMaxBlockFrames);

    inPlanar_.assign(static_cast<size_t>(channels_), std::vector<float>(kRealtimeMaxBlockFrames, 0.0f));
    outPlanar_.assign(static_cast<size_t>(channels_), std::vector<float>(kRealtimeMaxBlockFrames * 4, 0.0f));
    zeroPlanar_.assign(static_cast<size_t>(channels_), std::vector<float>(kRealtimeMaxBlockFrames, 0.0f));
    pendingOutInterleaved_.reserve(16384 * static_cast<size_t>(channels_));
}

RealtimePitchShifter::~RealtimePitchShifter() = default;

void RealtimePitchShifter::setParameters(float semitones, double timeRatio, bool preserveTimbre) {
    const float clampedSemi = std::clamp(semitones, -24.0f, 24.0f);
    const double clampedRatio = std::clamp(timeRatio, 0.25, 4.0);
    targetSemitones_.store(clampedSemi, std::memory_order_relaxed);
    targetTimeRatio_.store(clampedRatio, std::memory_order_relaxed);
    targetPreserveTimbre_.store(preserveTimbre, std::memory_order_relaxed);
}

void RealtimePitchShifter::applyPendingParamsOnAudioThread() {
    using RB = RubberBand::RubberBandStretcher;
    const float semi = targetSemitones_.load(std::memory_order_relaxed);
    const double ratio = targetTimeRatio_.load(std::memory_order_relaxed);
    const bool preserve = targetPreserveTimbre_.load(std::memory_order_relaxed);

    if (std::abs(semi - appliedSemitones_) > 1e-4f) {
        appliedSemitones_ = semi;
        const double pitchScale = std::pow(2.0, static_cast<double>(semi) / 12.0);
        stretcher_->setPitchScale(pitchScale);
    }
    if (std::abs(ratio - appliedTimeRatio_) > 1e-5) {
        appliedTimeRatio_ = ratio;
        stretcher_->setTimeRatio(ratio);
    }
    if (preserve != appliedPreserveTimbre_) {
        appliedPreserveTimbre_ = preserve;
        stretcher_->setFormantOption(
            preserve ? RB::OptionFormantPreserved : RB::OptionFormantShifted
        );
    }
}

void RealtimePitchShifter::reset() {
    stretcher_->reset();
    primed_ = false;
    remainingStartDelayFrames_ = 0;
    pendingOutInterleaved_.clear();
    pendingReadFrameOffset_ = 0;
    // Force re-application of current target params on next prime/process
    appliedSemitones_ = -999.0f;
    appliedTimeRatio_ = -1.0;
    appliedPreserveTimbre_ = !targetPreserveTimbre_.load(std::memory_order_relaxed);
    applyPendingParamsOnAudioThread();
}

void RealtimePitchShifter::primeStartPadIfNeeded() {
    if (primed_) return;
    applyPendingParamsOnAudioThread();

    size_t padRemaining = stretcher_->getPreferredStartPad();
    remainingStartDelayFrames_ = stretcher_->getStartDelay();

    const float* zeroPtrs[2] = {
        zeroPlanar_[0].data(),
        channels_ > 1 ? zeroPlanar_[1].data() : nullptr
    };

    while (padRemaining > 0) {
        const size_t chunk = std::min(padRemaining, kRealtimeMaxBlockFrames);
        stretcher_->process(zeroPtrs, chunk, false);
        padRemaining -= chunk;
        drainStretcherToPending();
    }
    primed_ = true;
}

void RealtimePitchShifter::drainStretcherToPending() {
    const size_t outChunkCapacity = outPlanar_[0].size();
    float* outPtrs[2] = {
        outPlanar_[0].data(),
        channels_ > 1 ? outPlanar_[1].data() : nullptr
    };

    int avail = 0;
    while ((avail = stretcher_->available()) > 0) {
        const size_t toRead = std::min(static_cast<size_t>(avail), outChunkCapacity);
        const size_t got = stretcher_->retrieve(outPtrs, toRead);
        if (got == 0) break;

        size_t startIdx = 0;
        if (remainingStartDelayFrames_ > 0) {
            const size_t skip = std::min(got, remainingStartDelayFrames_);
            remainingStartDelayFrames_ -= skip;
            startIdx = skip;
        }

        if (startIdx < got) {
            const size_t keepFrames = got - startIdx;
            const size_t oldSize = pendingOutInterleaved_.size();
            pendingOutInterleaved_.resize(oldSize + keepFrames * static_cast<size_t>(channels_));
            int16_t* dst = pendingOutInterleaved_.data() + oldSize;
            if (channels_ == 2) {
                const float* ch0 = outPlanar_[0].data() + startIdx;
                const float* ch1 = outPlanar_[1].data() + startIdx;
                for (size_t i = 0; i < keepFrames; ++i) {
                    dst[i * 2] = floatToSoftLimitedInt16(ch0[i]);
                    dst[i * 2 + 1] = floatToSoftLimitedInt16(ch1[i]);
                }
            } else {
                const float* ch0 = outPlanar_[0].data() + startIdx;
                for (size_t i = 0; i < keepFrames; ++i) {
                    dst[i] = floatToSoftLimitedInt16(ch0[i]);
                }
            }
        }
    }
}

int RealtimePitchShifter::copyPendingToOut(int16_t* outPcm16, int maxOutFrames) {
    if (!outPcm16 || maxOutFrames <= 0) return 0;
    const size_t totalPendingFrames = pendingOutInterleaved_.size() / static_cast<size_t>(channels_);
    if (pendingReadFrameOffset_ >= totalPendingFrames) {
        pendingOutInterleaved_.clear();
        pendingReadFrameOffset_ = 0;
        return 0;
    }

    const size_t availFrames = totalPendingFrames - pendingReadFrameOffset_;
    const size_t framesToCopy = std::min(availFrames, static_cast<size_t>(maxOutFrames));
    const size_t samplesToCopy = framesToCopy * static_cast<size_t>(channels_);
    const int16_t* src = pendingOutInterleaved_.data() + pendingReadFrameOffset_ * static_cast<size_t>(channels_);
    std::copy(src, src + samplesToCopy, outPcm16);

    pendingReadFrameOffset_ += framesToCopy;
    if (pendingReadFrameOffset_ >= totalPendingFrames) {
        pendingOutInterleaved_.clear();
        pendingReadFrameOffset_ = 0;
    } else if (pendingReadFrameOffset_ > 8192) {
        const size_t remainSamples = (totalPendingFrames - pendingReadFrameOffset_) * static_cast<size_t>(channels_);
        std::move(src + samplesToCopy, src + samplesToCopy + remainSamples, pendingOutInterleaved_.begin());
        pendingOutInterleaved_.resize(remainSamples);
        pendingReadFrameOffset_ = 0;
    }

    return static_cast<int>(framesToCopy);
}

int RealtimePitchShifter::processInterleavedInt16(
    const int16_t* inPcm16,
    int inFrames,
    int16_t* outPcm16,
    int maxOutFrames
) {
    if (!inPcm16 || inFrames <= 0) {
        return copyPendingToOut(outPcm16, maxOutFrames);
    }

    primeStartPadIfNeeded();

    const float* inPtrs[2] = {
        inPlanar_[0].data(),
        channels_ > 1 ? inPlanar_[1].data() : nullptr
    };

    int frameOffset = 0;
    while (frameOffset < inFrames) {
        applyPendingParamsOnAudioThread();
        const int chunk = std::min(kRealtimeSubBlockFrames, inFrames - frameOffset);

        if (channels_ == 2) {
            float* dst0 = inPlanar_[0].data();
            float* dst1 = inPlanar_[1].data();
            const int16_t* src = inPcm16 + frameOffset * 2;
            for (int i = 0; i < chunk; ++i) {
                dst0[i] = static_cast<float>(src[i * 2]) * (1.0f / 32768.0f);
                dst1[i] = static_cast<float>(src[i * 2 + 1]) * (1.0f / 32768.0f);
            }
        } else {
            float* dst0 = inPlanar_[0].data();
            const int16_t* src = inPcm16 + frameOffset;
            for (int i = 0; i < chunk; ++i) {
                dst0[i] = static_cast<float>(src[i]) * (1.0f / 32768.0f);
            }
        }

        stretcher_->process(inPtrs, static_cast<size_t>(chunk), false);
        frameOffset += chunk;
        drainStretcherToPending();
    }

    return copyPendingToOut(outPcm16, maxOutFrames);
}

int RealtimePitchShifter::finishInterleavedInt16(
    int16_t* outPcm16,
    int maxOutFrames
) {
    if (primed_) {
        applyPendingParamsOnAudioThread();
        const float* zeroPtrs[2] = {
            zeroPlanar_[0].data(),
            channels_ > 1 ? zeroPlanar_[1].data() : nullptr
        };
        stretcher_->process(zeroPtrs, 0, true);
        drainStretcherToPending();
        primed_ = false;
    }
    return copyPendingToOut(outPcm16, maxOutFrames);
}

int RealtimePitchShifter::drainPendingInt16(
    int16_t* outPcm16,
    int maxOutFrames
) {
    drainStretcherToPending();
    return copyPendingToOut(outPcm16, maxOutFrames);
}

} // namespace soundtracker
