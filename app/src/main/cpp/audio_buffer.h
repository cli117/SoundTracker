#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <vector>

namespace soundtracker {

using ProgressCallback = std::function<bool(float)>; // returns false if cancelled

struct PlanarAudioBuffer {
    int sampleRate = 44100;
    std::vector<std::vector<float>> channels; // channels[ch][frame]

    int numChannels() const { return static_cast<int>(channels.size()); }
    size_t numFrames() const { return channels.empty() ? 0 : channels[0].size(); }

    void resize(int nCh, size_t nFrames) {
        channels.resize(nCh);
        for (int c = 0; c < nCh; ++c) {
            channels[c].assign(nFrames, 0.0f);
        }
    }

    void ensureStereo() {
        if (channels.size() == 1) {
            channels.push_back(channels[0]);
        } else if (channels.size() > 2) {
            channels.resize(2);
        }
    }
};

// Modified zero-order Bessel function I0(x) for Kaiser window
inline double besselI0(double x) {
    double sum = 1.0;
    double term = 1.0;
    double halfX = x * 0.5;
    for (int k = 1; k < 28; ++k) {
        term *= (halfX / k) * (halfX / k);
        sum += term;
        if (term < 1e-12 * sum) break;
    }
    return sum;
}

// High-precision Kaiser-windowed polyphase sinc resampler (> 90 dB stopband rejection)
inline PlanarAudioBuffer resampleSinc(const PlanarAudioBuffer& src, int targetRate) {
    if (src.sampleRate == targetRate || src.numFrames() == 0 || src.sampleRate <= 0 || targetRate <= 0) {
        return src;
    }

    const double ratio = static_cast<double>(targetRate) / static_cast<double>(src.sampleRate);
    const size_t inLen = src.numFrames();
    const size_t outLen = static_cast<size_t>(std::llround(static_cast<double>(inLen) * ratio));

    PlanarAudioBuffer dst;
    dst.sampleRate = targetRate;
    dst.resize(src.numChannels(), outLen);

    constexpr int kHalfTaps = 16; // 32-tap kernel (scaled when downsampling)
    constexpr int kPhases = 512;
    constexpr double kBeta = 8.6;
    const double cutoff = std::min(1.0, ratio) * 0.94; // anti-aliasing cutoff
    const int halfSupport = static_cast<int>(std::ceil(kHalfTaps / cutoff));
    const int taps = halfSupport * 2;
    const double invI0Beta = 1.0 / besselI0(kBeta);

    // Precompute polyphase filter table
    std::vector<float> table((kPhases + 1) * taps, 0.0f);
    for (int p = 0; p <= kPhases; ++p) {
        double frac = static_cast<double>(p) / kPhases;
        double weightSum = 0.0;
        std::vector<double> row(taps);
        for (int k = 0; k < taps; ++k) {
            int tapOffset = k - halfSupport + 1;
            double x = tapOffset - frac;
            double winArg = x / halfSupport;
            double win = 0.0;
            if (std::abs(winArg) < 1.0) {
                win = besselI0(kBeta * std::sqrt(1.0 - winArg * winArg)) * invI0Beta;
            }
            double sincX = x * cutoff;
            double sincVal = (std::abs(sincX) < 1e-9)
                ? 1.0
                : std::sin(M_PI * sincX) / (M_PI * sincX);
            double w = sincVal * cutoff * win;
            row[k] = w;
            weightSum += w;
        }
        double norm = (std::abs(weightSum) > 1e-12) ? (1.0 / weightSum) : 1.0;
        for (int k = 0; k < taps; ++k) {
            table[p * taps + k] = static_cast<float>(row[k] * norm);
        }
    }

    const double step = 1.0 / ratio;
    for (int ch = 0; ch < src.numChannels(); ++ch) {
        const float* inPtr = src.channels[ch].data();
        float* outPtr = dst.channels[ch].data();
        for (size_t i = 0; i < outLen; ++i) {
            double srcPos = static_cast<double>(i) * step;
            int baseIdx = static_cast<int>(std::floor(srcPos));
            double frac = srcPos - baseIdx;
            double phasePos = frac * kPhases;
            int p0 = std::min(kPhases - 1, static_cast<int>(phasePos));
            float alpha = static_cast<float>(phasePos - p0);
            const float* w0 = &table[p0 * taps];
            const float* w1 = &table[(p0 + 1) * taps];

            float acc = 0.0f;
            int startIn = baseIdx - halfSupport + 1;
            for (int k = 0; k < taps; ++k) {
                int idx = startIn + k;
                if (idx < 0) idx = 0;
                else if (idx >= static_cast<int>(inLen)) idx = static_cast<int>(inLen) - 1;
                float coeff = w0[k] + alpha * (w1[k] - w0[k]);
                acc += inPtr[idx] * coeff;
            }
            outPtr[i] = acc;
        }
    }

    return dst;
}

} // namespace soundtracker
