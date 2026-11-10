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

} // namespace soundtracker
