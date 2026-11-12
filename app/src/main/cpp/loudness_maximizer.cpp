#include "loudness_maximizer.h"
#include "signalsmith-linear/fft.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <deque>
#include <vector>

namespace soundtracker {

namespace {

struct Biquad {
    double b0 = 1.0, b1 = 0.0, b2 = 0.0;
    double a1 = 0.0, a2 = 0.0;
    double z1 = 0.0, z2 = 0.0;

    void reset() { z1 = z2 = 0.0; }

    inline float process(float x) {
        double in = static_cast<double>(x);
        double out = b0 * in + z1;
        z1 = b1 * in - a1 * out + z2;
        z2 = b2 * in - a2 * out;
        return static_cast<float>(out);
    }
};

// ITU-R BS.1770-4 Stage 1 High-Shelf Pre-Filter (generalized via bilinear transform)
Biquad makeKWeightShelf(int sampleRate) {
    const double fs = static_cast<double>(sampleRate);
    const double f0 = 1681.974450955533;
    const double dbGain = 3.999843853973347;
    const double Q = 0.7071752369554196;

    const double K = std::tan(M_PI * f0 / fs);
    const double Vh = std::pow(10.0, dbGain / 20.0);
    const double Vb = std::pow(Vh, 0.4996667741545416);
    const double a0 = 1.0 + K / Q + K * K;

    Biquad bq;
    bq.b0 = (Vh + Vb * K / Q + K * K) / a0;
    bq.b1 = 2.0 * (K * K - Vh) / a0;
    bq.b2 = (Vh - Vb * K / Q + K * K) / a0;
    bq.a1 = 2.0 * (K * K - 1.0) / a0;
    bq.a2 = (1.0 - K / Q + K * K) / a0;
    return bq;
}

// ITU-R BS.1770-4 Stage 2 RLB High-Pass Filter
Biquad makeKWeightHighPass(int sampleRate) {
    const double fs = static_cast<double>(sampleRate);
    const double f0 = 38.13547087602444;
    const double Q = 0.5003270373238773;

    const double K = std::tan(M_PI * f0 / fs);
    const double a0 = 1.0 + K / Q + K * K;

    Biquad bq;
    bq.b0 = 1.0 / a0;
    bq.b1 = -2.0 / a0;
    bq.b2 = 1.0 / a0;
    bq.a1 = 2.0 * (K * K - 1.0) / a0;
    bq.a2 = (1.0 - K / Q + K * K) / a0;
    return bq;
}

// 4-point Catmull-Rom / 8-tap polyphase inter-sample true-peak estimator
// Evaluates sample peak + 3 inter-sample phases (0.25, 0.50, 0.75)
inline float estimateFrameTruePeak4x(const float* chPtr, size_t idx, size_t nFrames) {
    float s1 = chPtr[idx];
    float maxAbs = std::abs(s1);
    if (idx == 0 || idx + 2 >= nFrames) {
        return maxAbs;
    }
    float s0 = chPtr[idx - 1];
    float s2 = chPtr[idx + 1];
    float s3 = chPtr[idx + 2];

    // Skip polynomial evaluation if surrounding samples are quiet
    if (maxAbs < 0.15f && std::abs(s2) < 0.15f) {
        return std::max(maxAbs, std::abs(s2));
    }

    // 4-tap cubic Hermite / polyphase FIR approximation for phases 0.25, 0.50, 0.75
    float a = -0.5f * s0 + 1.5f * s1 - 1.5f * s2 + 0.5f * s3;
    float b = s0 - 2.5f * s1 + 2.0f * s2 - 0.5f * s3;
    float c = -0.5f * s0 + 0.5f * s2;
    float d = s1;

    float p25 = ((a * 0.25f + b) * 0.25f + c) * 0.25f + d;
    float p50 = ((a * 0.50f + b) * 0.50f + c) * 0.50f + d;
    float p75 = ((a * 0.75f + b) * 0.75f + c) * 0.75f + d;

    maxAbs = std::max(maxAbs, std::abs(p25));
    maxAbs = std::max(maxAbs, std::abs(p50));
    maxAbs = std::max(maxAbs, std::abs(p75));
    return maxAbs;
}

inline float linToDb(float lin, float floorDb = -96.0f) {
    if (lin <= 1e-5f) return floorDb;
    return std::max(floorDb, 20.0f * std::log10(lin));
}

// Studio 20-Band Log-Spectral Flux + Harmonic Comb Autocorrelation Tempo Detector (1 BPM resolution)
float estimateTempoBpm(const PlanarAudioBuffer& buf) {
    const int sr = buf.sampleRate;
    const int nCh = buf.numChannels();
    const size_t totalFrames = buf.numFrames();
    if (sr <= 0 || nCh <= 0 || totalFrames < static_cast<size_t>(sr * 2)) {
        return 120.0f;
    }

    // Analyze up to 45 seconds around the energetic center of the track
    const size_t maxWindow = static_cast<size_t>(sr) * 45;
    size_t startFrame = 0;
    size_t analysisFrames = totalFrames;
    if (totalFrames > maxWindow) {
        startFrame = (totalFrames - maxWindow) / 2;
        analysisFrames = maxWindow;
    }

    constexpr size_t kNfft = 1024;
    constexpr size_t kHop = 256; // 172.265625 Hz @ 44.1 kHz
    constexpr size_t kHalf = kNfft / 2;
    if (analysisFrames < kNfft + kHop * 64) {
        return 120.0f;
    }

    const size_t nEnv = (analysisFrames - kNfft) / kHop + 1;
    const double actualFps = static_cast<double>(sr) / static_cast<double>(kHop);

    constexpr int kNumBands = 20;
    std::vector<int> bandStart(kNumBands), bandEnd(kNumBands);
    const double fMin = 45.0, fMax = std::min(11000.0, sr * 0.45);
    for (int b = 0; b < kNumBands; ++b) {
        double f0 = fMin * std::pow(fMax / fMin, static_cast<double>(b) / kNumBands);
        double f1 = fMin * std::pow(fMax / fMin, static_cast<double>(b + 1) / kNumBands);
        int k0 = std::max(1, static_cast<int>(std::floor(f0 * kNfft / sr)));
        int k1 = std::min(static_cast<int>(kHalf) - 1, std::max(k0, static_cast<int>(std::ceil(f1 * kNfft / sr))));
        bandStart[b] = k0;
        bandEnd[b] = k1;
    }

    std::vector<float> hannWin(kNfft);
    for (size_t i = 0; i < kNfft; ++i) {
        hannWin[i] = 0.5f - 0.5f * std::cos(static_cast<float>(2.0 * M_PI * i / kNfft));
    }

    signalsmith::linear::RealFFT<float> fft(kNfft);
    std::vector<float> timeFrame(kNfft);
    std::vector<std::complex<float>> spec(kHalf);
    std::vector<float> prevBandLog(kNumBands, 0.0f);
    std::vector<float> rawFlux(nEnv, 0.0f);

    const float invCh = 1.0f / static_cast<float>(nCh);
    for (size_t t = 0; t < nEnv; ++t) {
        const size_t base = startFrame + t * kHop;
        for (size_t i = 0; i < kNfft; ++i) {
            float mono = 0.0f;
            for (int c = 0; c < nCh; ++c) {
                mono += buf.channels[c][base + i];
            }
            timeFrame[i] = (mono * invCh) * hannWin[i];
        }
        fft.fft(timeFrame.data(), spec.data());

        float flux = 0.0f;
        for (int b = 0; b < kNumBands; ++b) {
            float bandMagSum = 0.0f;
            for (int k = bandStart[b]; k <= bandEnd[b]; ++k) {
                bandMagSum += std::abs(spec[k]);
            }
            bandMagSum /= static_cast<float>(bandEnd[b] - bandStart[b] + 1);
            float logMag = std::log1p(25.0f * bandMagSum);
            if (t > 0 && logMag > prevBandLog[b]) {
                float bandWeight = (b < 14) ? 1.2f : 0.6f;
                flux += bandWeight * (logMag - prevBandLog[b]);
            }
            prevBandLog[b] = logMag;
        }
        rawFlux[t] = flux;
    }

    std::vector<float> onset(nEnv, 0.0f);
    constexpr int kLocalRadius = 16;
    double maxOnset = 1e-6;
    for (size_t t = 0; t < nEnv; ++t) {
        size_t i0 = (t > kLocalRadius) ? (t - kLocalRadius) : 0;
        size_t i1 = std::min(nEnv - 1, t + kLocalRadius);
        float sum = 0.0f;
        for (size_t k = i0; k <= i1; ++k) sum += rawFlux[k];
        float localMean = sum / static_cast<float>(i1 - i0 + 1);
        float v = std::max(0.0f, rawFlux[t] - localMean);
        onset[t] = v;
        if (v > maxOnset) maxOnset = v;
    }
    if (maxOnset <= 1e-5) {
        return 120.0f;
    }

    std::vector<float> smoothOnset(nEnv, 0.0f);
    for (size_t t = 1; t + 1 < nEnv; ++t) {
        smoothOnset[t] = static_cast<float>((0.25 * onset[t - 1] + 0.50 * onset[t] + 0.25 * onset[t + 1]) / maxOnset);
    }

    const size_t minLag = std::max<size_t>(8, static_cast<size_t>(std::floor(actualFps * 60.0 / 240.0)));
    const size_t maxLag = std::min<size_t>(nEnv / 2, static_cast<size_t>(std::ceil(actualFps * 60.0 / 28.0)));
    if (maxLag <= minLag + 4) {
        return 120.0f;
    }

    std::vector<double> acf(maxLag + 1, 0.0);
    for (size_t lag = minLag; lag <= maxLag; ++lag) {
        double s = 0.0;
        const size_t limit = nEnv - lag;
        const float* a = smoothOnset.data();
        const float* b = smoothOnset.data() + lag;
        for (size_t i = 0; i < limit; ++i) {
            s += static_cast<double>(a[i]) * static_cast<double>(b[i]);
        }
        acf[lag] = s / static_cast<double>(limit);
    }

    auto interpAcf = [&](double lagF) -> double {
        if (lagF < static_cast<double>(minLag) || lagF + 1.0 > static_cast<double>(maxLag)) {
            return 0.0;
        }
        const size_t idx = static_cast<size_t>(lagF);
        const double frac = lagF - static_cast<double>(idx);
        return acf[idx] * (1.0 - frac) + acf[idx + 1] * frac;
    };

    double bestBpm = 120.0;
    double bestScore = -1e18;

    for (int step = 600; step <= 1850; ++step) {
        const double bpm = step * 0.10; // 60.0 .. 185.0 BPM
        const double tau = (60.0 * actualFps) / bpm;

        const double r1 = interpAcf(tau);
        const double r2 = interpAcf(2.0 * tau);
        const double r3 = interpAcf(3.0 * tau);
        const double r4 = interpAcf(4.0 * tau);

        const double comb = 1.00 * r1 + 0.55 * r2 + 0.35 * r3 + 0.25 * r4;
        const double logOct = std::log2(bpm / 116.0);
        const double prior = std::exp(-0.5 * (logOct / 0.52) * (logOct / 0.52));

        const double score = comb * (0.68 + 0.32 * prior);
        if (score > bestScore) {
            bestScore = score;
            bestBpm = bpm;
        }
    }

    if (bestBpm >= 136.0) {
        const double tauFast = (60.0 * actualFps) / bestBpm;
        const double rSub = interpAcf(tauFast);
        const double rBeat = interpAcf(2.0 * tauFast);
        if (rBeat > rSub * 1.45) {
            bestBpm *= 0.5;
        }
    }

    return static_cast<float>(std::clamp(std::round(bestBpm), 45.0, 220.0));
}

void estimateMusicalKey(
    const PlanarAudioBuffer& buf,
    int& outFifths,
    bool& outMinor,
    float& outConfidence
) {
    outFifths = 0;
    outMinor = false;
    outConfidence = 0.0f;

    const int sr = buf.sampleRate;
    const int nCh = buf.numChannels();
    const size_t totalFrames = buf.numFrames();
    if (sr <= 0 || nCh <= 0 || totalFrames < static_cast<size_t>(sr)) {
        return;
    }

    constexpr size_t kNfft = 4096;
    constexpr size_t kHop = 2048;
    constexpr size_t kHalf = kNfft / 2;
    if (totalFrames < kNfft) {
        return;
    }

    const size_t maxWindow = static_cast<size_t>(sr) * 60;
    size_t startFrame = 0;
    size_t analysisFrames = totalFrames;
    if (totalFrames > maxWindow) {
        startFrame = (totalFrames - maxWindow) / 2;
        analysisFrames = maxWindow;
    }

    const size_t nFramesStft = (analysisFrames - kNfft) / kHop + 1;
    if (nFramesStft < 4) {
        return;
    }

    std::vector<float> hannWin(kNfft);
    for (size_t i = 0; i < kNfft; ++i) {
        hannWin[i] = 0.5f - 0.5f * std::cos(static_cast<float>(2.0 * M_PI * i / kNfft));
    }

    signalsmith::linear::RealFFT<float> fft(kNfft);
    std::vector<float> timeFrame(kNfft);
    std::vector<std::complex<float>> spec(kHalf);
    std::vector<float> mag(kHalf, 0.0f);

    const int kMin = std::max(2, static_cast<int>(std::ceil(65.0 * kNfft / sr)));
    const int kMax = std::min(static_cast<int>(kHalf) - 2, static_cast<int>(std::floor(2000.0 * kNfft / sr)));
    if (kMax <= kMin + 4) {
        return;
    }

    double chroma[12] = {0.0};
    const float invCh = 1.0f / static_cast<float>(nCh);
    const size_t frameStride = std::max<size_t>(1, nFramesStft / 600);

    for (size_t t = 0; t < nFramesStft; t += frameStride) {
        const size_t base = startFrame + t * kHop;
        double frameEnergy = 0.0;
        for (size_t i = 0; i < kNfft; ++i) {
            float mono = 0.0f;
            for (int c = 0; c < nCh; ++c) {
                mono += buf.channels[c][base + i];
            }
            const float v = mono * invCh;
            frameEnergy += static_cast<double>(v) * static_cast<double>(v);
            timeFrame[i] = v * hannWin[i];
        }
        if (frameEnergy < 1e-7 * static_cast<double>(kNfft)) {
            continue;
        }

        fft.fft(timeFrame.data(), spec.data());
        for (int k = kMin - 1; k <= kMax + 1; ++k) {
            mag[k] = std::abs(spec[k]);
        }

        // Accumulate local harmonic spectral peaks into 12-bin HPCP chroma
        for (int k = kMin; k <= kMax; ++k) {
            const float mCur = mag[k];
            const float mPrev = mag[k - 1];
            const float mNext = mag[k + 1];
            if (mCur <= 1e-5f || mCur <= mPrev || mCur < mNext) {
                continue;
            }
            const float denom = mPrev - 2.0f * mCur + mNext;
            float delta = 0.0f;
            if (std::abs(denom) > 1e-9f) {
                delta = std::clamp(0.5f * (mPrev - mNext) / denom, -0.5f, 0.5f);
            }
            const double freq = (static_cast<double>(k) + static_cast<double>(delta)) *
                static_cast<double>(sr) / static_cast<double>(kNfft);
            if (freq < 60.0 || freq > 2100.0) continue;

            const double midiF = 69.0 + 12.0 * std::log2(freq / 440.0);
            const int nearestMidi = static_cast<int>(std::round(midiF));
            const double centsDev = std::abs(midiF - static_cast<double>(nearestMidi));
            const double tuneWeight = std::exp(-4.5 * centsDev * centsDev);
            const double octWeight = (freq < 600.0) ? 1.0 : (600.0 / freq);
            const int pc = ((nearestMidi % 12) + 12) % 12;
            chroma[pc] += std::log1p(24.0 * static_cast<double>(mCur)) * tuneWeight * octWeight;
        }
    }

    double chromaMean = 0.0;
    for (int i = 0; i < 12; ++i) chromaMean += chroma[i];
    chromaMean /= 12.0;

    double chromaVar = 0.0;
    for (int i = 0; i < 12; ++i) {
        const double d = chroma[i] - chromaMean;
        chromaVar += d * d;
    }
    if (chromaVar <= 1e-12) {
        return;
    }

    // Krumhansl-Kessler Major & Minor key profiles
    static constexpr double kMajorProfile[12] = {
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    };
    static constexpr double kMinorProfile[12] = {
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    };
    static constexpr int kMajorTonicToFifths[12] = {
        0, -5, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5
    };
    static constexpr int kMinorTonicToFifths[12] = {
        -3, 4, -1, -6, 1, -4, 3, -2, 5, 0, -5, 2
    };

    auto corrWithProfile = [&](const double prof[12], int tonicPc) -> double {
        double pMean = 0.0;
        for (int i = 0; i < 12; ++i) pMean += prof[i];
        pMean /= 12.0;
        double num = 0.0;
        double pVar = 0.0;
        for (int i = 0; i < 12; ++i) {
            const double dc = chroma[(tonicPc + i) % 12] - chromaMean;
            const double dp = prof[i] - pMean;
            num += dc * dp;
            pVar += dp * dp;
        }
        return num / std::sqrt(chromaVar * pVar);
    };

    double bestCorr = -2.0;
    int bestTonic = 0;
    bool bestIsMinor = false;

    for (int pc = 0; pc < 12; ++pc) {
        const double rMaj = corrWithProfile(kMajorProfile, pc);
        if (rMaj > bestCorr) {
            bestCorr = rMaj;
            bestTonic = pc;
            bestIsMinor = false;
        }
        const double rMin = corrWithProfile(kMinorProfile, pc);
        if (rMin > bestCorr) {
            bestCorr = rMin;
            bestTonic = pc;
            bestIsMinor = true;
        }
    }

    outFifths = bestIsMinor ? kMinorTonicToFifths[bestTonic] : kMajorTonicToFifths[bestTonic];
    outMinor = bestIsMinor;
    outConfidence = static_cast<float>(std::clamp(bestCorr, 0.0, 1.0));
}

} // namespace

AudioAnalysis analyzeAudio(const PlanarAudioBuffer& buf, int numWaveformBins) {
    AudioAnalysis res;
    res.sampleRate = buf.sampleRate;
    res.channels = buf.numChannels();
    const size_t nFrames = buf.numFrames();
    if (nFrames == 0 || res.channels == 0 || res.sampleRate <= 0) {
        return res;
    }
    res.durationSec = static_cast<float>(nFrames) / static_cast<float>(res.sampleRate);

    // Sample peak, True Peak (4x), and RMS
    float samplePeak = 0.0f;
    float truePeak = 0.0f;
    double sumSq = 0.0;

    for (int c = 0; c < res.channels; ++c) {
        const float* p = buf.channels[c].data();
        for (size_t i = 0; i < nFrames; ++i) {
            float v = p[i];
            float av = std::abs(v);
            if (av > samplePeak) samplePeak = av;
            sumSq += static_cast<double>(v) * static_cast<double>(v);
            float tp = estimateFrameTruePeak4x(p, i, nFrames);
            if (tp > truePeak) truePeak = tp;
        }
    }

    double rms = std::sqrt(sumSq / static_cast<double>(nFrames * res.channels));
    res.samplePeakDbfs = linToDb(samplePeak);
    res.truePeakDbtp = linToDb(truePeak);
    res.rmsDbfs = linToDb(static_cast<float>(rms));
    res.pureLosslessHeadroomDb = std::max(0.0f, -0.2f - res.truePeakDbtp);

    // ITU-R BS.1770-4 Gated Integrated Loudness (400ms blocks, 75% overlap = 100ms hop)
    const size_t hop = std::max<size_t>(1, static_cast<size_t>(res.sampleRate / 10)); // 100 ms
    std::vector<double> hopEnergy;
    hopEnergy.reserve(nFrames / hop + 2);

    std::vector<Biquad> shelf(res.channels, makeKWeightShelf(res.sampleRate));
    std::vector<Biquad> rlb(res.channels, makeKWeightHighPass(res.sampleRate));

    size_t pos = 0;
    while (pos < nFrames) {
        size_t end = std::min(nFrames, pos + hop);
        double chEnergySum = 0.0;
        for (int c = 0; c < res.channels; ++c) {
            const float* p = buf.channels[c].data();
            double e = 0.0;
            for (size_t i = pos; i < end; ++i) {
                float y = rlb[c].process(shelf[c].process(p[i]));
                e += static_cast<double>(y) * static_cast<double>(y);
            }
            chEnergySum += e / static_cast<double>(end - pos);
        }
        hopEnergy.push_back(chEnergySum);
        pos = end;
    }

    // Form 400ms (4-hop) overlapping gating blocks
    std::vector<double> blockMeanSq;
    if (hopEnergy.size() < 4) {
        double avg = 0.0;
        for (double v : hopEnergy) avg += v;
        if (!hopEnergy.empty()) blockMeanSq.push_back(avg / hopEnergy.size());
    } else {
        blockMeanSq.reserve(hopEnergy.size() - 3);
        for (size_t j = 0; j + 3 < hopEnergy.size(); ++j) {
            double z = 0.25 * (hopEnergy[j] + hopEnergy[j + 1] + hopEnergy[j + 2] + hopEnergy[j + 3]);
            blockMeanSq.push_back(z);
        }
    }

    // Absolute gate at -70 LKFS: -0.691 + 10*log10(z) > -70
    const double absGateZ = std::pow(10.0, (-70.0 + 0.691) / 10.0);
    double sumAbsPass = 0.0;
    size_t countAbsPass = 0;
    for (double z : blockMeanSq) {
        if (z > absGateZ) {
            sumAbsPass += z;
            ++countAbsPass;
        }
    }

    if (countAbsPass > 0) {
        double gammaA = -0.691 + 10.0 * std::log10(sumAbsPass / static_cast<double>(countAbsPass));
        double relGateZ = std::pow(10.0, (gammaA - 10.0 + 0.691) / 10.0);
        double sumRelPass = 0.0;
        size_t countRelPass = 0;
        for (double z : blockMeanSq) {
            if (z > relGateZ && z > absGateZ) {
                sumRelPass += z;
                ++countRelPass;
            }
        }
        if (countRelPass > 0) {
            res.integratedLufs = static_cast<float>(
                -0.691 + 10.0 * std::log10(sumRelPass / static_cast<double>(countRelPass))
            );
        } else {
            res.integratedLufs = static_cast<float>(gammaA);
        }
    }

    // Waveform visual envelope bins & automatic BPM detection (only when full track analysis is requested)
    if (numWaveformBins > 0) {
        res.detectedBpm = estimateTempoBpm(buf);
        estimateMusicalKey(buf, res.detectedKeyFifths, res.detectedKeyMinor, res.detectedKeyConfidence);
        res.waveformPeaks.assign(numWaveformBins, 0.0f);
        for (int b = 0; b < numWaveformBins; ++b) {
            size_t s = (static_cast<size_t>(b) * nFrames) / numWaveformBins;
            size_t e = std::max(s + 1, (static_cast<size_t>(b + 1) * nFrames) / numWaveformBins);
            e = std::min(e, nFrames);
            // Sub-sample large bins for speed while preserving transients
            size_t stride = std::max<size_t>(1, (e - s) / 256);
            float binPeak = 0.0f;
            for (int c = 0; c < res.channels; ++c) {
                const float* p = buf.channels[c].data();
                for (size_t i = s; i < e; i += stride) {
                    binPeak = std::max(binPeak, std::abs(p[i]));
                }
            }
            res.waveformPeaks[b] = std::min(1.0f, binPeak);
        }
    }

    return res;
}

BoostResult boostVolumeTransparent(
    PlanarAudioBuffer& buf,
    float gainDb,
    float ceilingDbtp,
    const ProgressCallback& onProgress
) {
    BoostResult out;
    const size_t nFrames = buf.numFrames();
    const int nCh = buf.numChannels();
    if (nFrames == 0 || nCh == 0) return out;

    if (onProgress && !onProgress(0.05f)) return out;

    AudioAnalysis pre = analyzeAudio(buf, 0);
    const double linearGain = std::pow(10.0, static_cast<double>(gainDb) / 20.0);
    const float ceilingLin = std::pow(10.0f, ceilingDbtp / 20.0f);

    // Stage 1: Check if pure 100% lossless linear amplification fits inside ceiling headroom
    if (pre.truePeakDbtp + gainDb <= ceilingDbtp + 0.02f) {
        for (int c = 0; c < nCh; ++c) {
            float* p = buf.channels[c].data();
            for (size_t i = 0; i < nFrames; ++i) {
                p[i] = static_cast<float>(static_cast<double>(p[i]) * linearGain);
            }
        }
        if (onProgress && !onProgress(0.90f)) return out;
        AudioAnalysis post = analyzeAudio(buf, 0);
        out.ok = true;
        out.wasPureLossless = true;
        out.postLufs = post.integratedLufs;
        out.postTruePeakDbtp = post.truePeakDbtp;
        out.maxGainReductionDb = 0.0f;
        if (onProgress) onProgress(1.0f);
        return out;
    }

    // Stage 2: Mastering 5ms Lookahead Stereo-Linked True-Peak Limiter
    // Pre-scale in float32 (unlimited dynamic range before ceiling)
    for (int c = 0; c < nCh; ++c) {
        float* p = buf.channels[c].data();
        for (size_t i = 0; i < nFrames; ++i) {
            p[i] = static_cast<float>(static_cast<double>(p[i]) * linearGain);
        }
    }
    if (onProgress && !onProgress(0.25f)) return out;

    // Compute stereo-linked instantaneous 4x true-peak ceiling gain target
    std::vector<float> targetGain(nFrames, 1.0f);
    for (size_t i = 0; i < nFrames; ++i) {
        float framePeak = 0.0f;
        for (int c = 0; c < nCh; ++c) {
            framePeak = std::max(framePeak, estimateFrameTruePeak4x(buf.channels[c].data(), i, nFrames));
        }
        if (framePeak > ceilingLin) {
            targetGain[i] = ceilingLin / framePeak;
        }
    }
    if (onProgress && !onProgress(0.50f)) return out;

    // 5ms lookahead sliding minimum via O(N) monotonic deque:
    // minGain[i] = min_{k in [i .. min(N-1, i + L)]} targetGain[k]
    const size_t lookahead = std::max<size_t>(16, static_cast<size_t>(buf.sampleRate * 0.005)); // 5 ms
    std::vector<float> minGain(nFrames, 1.0f);
    std::deque<size_t> dq;

    size_t r = 0;
    for (size_t i = 0; i < nFrames; ++i) {
        size_t windowEnd = std::min(nFrames - 1, i + lookahead);
        while (r <= windowEnd) {
            while (!dq.empty() && targetGain[dq.back()] >= targetGain[r]) {
                dq.pop_back();
            }
            dq.push_back(r);
            ++r;
        }
        while (!dq.empty() && dq.front() < i) {
            dq.pop_front();
        }
        minGain[i] = targetGain[dq.front()];
    }
    if (onProgress && !onProgress(0.70f)) return out;

    // Program-dependent dual-release + causal attack smoothing over `lookahead` samples:
    // Because minGain[i - L .. i] <= targetGain[i], averaging over causal window [i - L + 1 .. i]
    // smoothly ramps down to <= targetGain[i] right as the peak arrives at sample i!
    const float fastRelCoeff = std::exp(-1.0f / (0.040f * buf.sampleRate)); // 40 ms transient recovery
    const float slowRelCoeff = std::exp(-1.0f / (0.240f * buf.sampleRate)); // 240 ms bass-clean floor

    // First apply adaptive release envelope on minGain
    float fastEnv = 1.0f;
    float slowEnv = 1.0f;
    for (size_t i = 0; i < nFrames; ++i) {
        float mg = minGain[i];
        if (mg < fastEnv) {
            fastEnv = mg;
        } else {
            fastEnv = fastRelCoeff * fastEnv + (1.0f - fastRelCoeff) * mg;
        }
        if (mg < slowEnv) {
            slowEnv = 0.85f * slowEnv + 0.15f * mg;
        } else {
            slowEnv = slowRelCoeff * slowEnv + (1.0f - slowRelCoeff) * mg;
        }
        // Blend fast transient recovery with slow anti-pumping floor when sustained GR occurs
        float grDepth = 1.0f - slowEnv;
        float blend = std::clamp(grDepth * 1.6f, 0.0f, 0.65f);
        float env = std::min(mg, (1.0f - blend) * fastEnv + blend * slowEnv);
        minGain[i] = env;
    }

    // Causal two-pass boxcar (triangular kernel of total length = lookahead) for click-free smooth attack
    const size_t halfWin = std::max<size_t>(4, lookahead / 2);
    std::vector<float> smooth1(nFrames, 1.0f);
    double winSum = 0.0;
    for (size_t i = 0; i < nFrames; ++i) {
        winSum += minGain[i];
        if (i >= halfWin) {
            winSum -= minGain[i - halfWin];
            smooth1[i] = static_cast<float>(winSum / halfWin);
        } else {
            smooth1[i] = static_cast<float>(winSum / (i + 1));
        }
    }

    float minAppliedGain = 1.0f;
    winSum = 0.0;
    const float hardGuard = std::min(0.996f, ceilingLin * 1.008f);
    for (size_t i = 0; i < nFrames; ++i) {
        winSum += smooth1[i];
        float g;
        if (i >= halfWin) {
            winSum -= smooth1[i - halfWin];
            g = static_cast<float>(winSum / halfWin);
        } else {
            g = static_cast<float>(winSum / (i + 1));
        }
        // Guarantee strict peak ceiling compliance
        g = std::min(g, targetGain[i]);
        minAppliedGain = std::min(minAppliedGain, g);
        for (int c = 0; c < nCh; ++c) {
            float y = buf.channels[c][i] * g;
            buf.channels[c][i] = std::clamp(y, -hardGuard, hardGuard);
        }
    }

    if (onProgress && !onProgress(0.92f)) return out;
    AudioAnalysis post = analyzeAudio(buf, 0);
    out.ok = true;
    out.wasPureLossless = false;
    out.postLufs = post.integratedLufs;
    out.postTruePeakDbtp = post.truePeakDbtp;
    out.maxGainReductionDb = (minAppliedGain < 0.9995f) ? -linToDb(minAppliedGain) : 0.0f;
    if (onProgress) onProgress(1.0f);
    return out;
}

float applyTruePeakSafetyLimiter(PlanarAudioBuffer& buf, float ceilingDbtp) {
    float maxPeak = 0.0f;
    const size_t nFrames = buf.numFrames();
    for (int c = 0; c < buf.numChannels(); ++c) {
        const float* p = buf.channels[c].data();
        for (size_t i = 0; i < nFrames; ++i) {
            maxPeak = std::max(maxPeak, estimateFrameTruePeak4x(p, i, nFrames));
        }
    }
    const float ceilingLin = std::pow(10.0f, ceilingDbtp / 20.0f);
    if (maxPeak <= ceilingLin) {
        return 0.0f;
    }
    BoostResult r = boostVolumeTransparent(buf, 0.0f, ceilingDbtp, nullptr);
    return r.maxGainReductionDb;
}

namespace {

inline float estimateHistoryTruePeak4x(const float h[4]) {
    const float s0 = h[0], s1 = h[1], s2 = h[2], s3 = h[3];
    float maxAbs = std::abs(s1);
    if (maxAbs < 0.15f && std::abs(s2) < 0.15f) {
        return std::max(maxAbs, std::abs(s2));
    }
    const float a = -0.5f * s0 + 1.5f * s1 - 1.5f * s2 + 0.5f * s3;
    const float b = s0 - 2.5f * s1 + 2.0f * s2 - 0.5f * s3;
    const float c = -0.5f * s0 + 0.5f * s2;
    const float d = s1;

    const float p25 = ((a * 0.25f + b) * 0.25f + c) * 0.25f + d;
    const float p50 = ((a * 0.50f + b) * 0.50f + c) * 0.50f + d;
    const float p75 = ((a * 0.75f + b) * 0.75f + c) * 0.75f + d;

    maxAbs = std::max(maxAbs, std::abs(p25));
    maxAbs = std::max(maxAbs, std::abs(p50));
    maxAbs = std::max(maxAbs, std::abs(p75));
    return maxAbs;
}

inline int16_t floatToPcm16ExactScale(float x) {
    const int32_t v = static_cast<int32_t>(std::lrintf(x * 32768.0f));
    return static_cast<int16_t>(std::clamp(v, -32768, 32767));
}

size_t nextPowerOfTwo(size_t n) {
    size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}

} // namespace

RealtimeVolumeMaximizer::RealtimeVolumeMaximizer(int sampleRate, int channels, float ceilingDbtp)
    : sampleRate_(std::clamp(sampleRate, 8000, 192000)),
      channels_(std::clamp(channels, 1, 2)),
      ceilingLin_(std::pow(10.0f, ceilingDbtp / 20.0f)),
      hardGuard_(std::min(0.996f, ceilingLin_ * 1.008f)),
      fastRelCoeff_(std::exp(-1.0f / (0.040f * static_cast<float>(sampleRate_)))),
      slowRelCoeff_(std::exp(-1.0f / (0.240f * static_cast<float>(sampleRate_)))),
      paramSmoothAlpha_(1.0f - std::exp(-1.0f / (0.008f * static_cast<float>(sampleRate_)))),
      lookahead_(std::max<size_t>(16, static_cast<size_t>(sampleRate_ * 0.005))),
      halfWin_(std::max<size_t>(4, lookahead_ / 2)),
      invHalfWin_(1.0 / static_cast<double>(halfWin_)),
      ringCap_(nextPowerOfTwo(lookahead_ + 16)),
      ringMask_(ringCap_ - 1) {
    audioRing0_.assign(ringCap_, 0.0f);
    audioRing1_.assign(ringCap_, 0.0f);
    targetGainRing_.assign(ringCap_, 1.0f);
    dequeIdx_.assign(ringCap_, 0);
    dequeVal_.assign(ringCap_, 1.0f);
    boxcar1Ring_.assign(halfWin_, 1.0f);
    boxcar2Ring_.assign(halfWin_, 1.0f);
    reset();
}

void RealtimeVolumeMaximizer::setGainDb(float gainDb) {
    const float clamped = std::clamp(gainDb, -12.0f, 24.0f);
    targetGainDb_.store(clamped, std::memory_order_relaxed);
}

void RealtimeVolumeMaximizer::reset() {
    const float gainDb = targetGainDb_.load(std::memory_order_relaxed);
    appliedGainLin_ = std::pow(10.0f, gainDb / 20.0f);

    for (int c = 0; c < 2; ++c) {
        for (int k = 0; k < 4; ++k) {
            tpHist_[c][k] = 0.0f;
        }
    }
    std::fill(audioRing0_.begin(), audioRing0_.end(), 0.0f);
    std::fill(audioRing1_.begin(), audioRing1_.end(), 0.0f);
    std::fill(targetGainRing_.begin(), targetGainRing_.end(), 1.0f);

    frameCount_ = lookahead_;
    dequeHead_ = 0;
    dequeSize_ = 1;
    dequeIdx_[0] = lookahead_ - 1;
    dequeVal_[0] = 1.0f;

    fastEnv_ = 1.0f;
    slowEnv_ = 1.0f;
    std::fill(boxcar1Ring_.begin(), boxcar1Ring_.end(), 1.0f);
    std::fill(boxcar2Ring_.begin(), boxcar2Ring_.end(), 1.0f);
    boxcarPos_ = 0;
    winSum1_ = static_cast<double>(halfWin_);
    winSum2_ = static_cast<double>(halfWin_);
}

void RealtimeVolumeMaximizer::processInterleavedInt16(
    const int16_t* inPcm16,
    int16_t* outPcm16,
    int frames
) {
    if (!inPcm16 || !outPcm16 || frames <= 0) return;

    const float targetGainDb = targetGainDb_.load(std::memory_order_relaxed);
    const float targetLin = std::pow(10.0f, targetGainDb / 20.0f);
    constexpr float kInv32768 = 1.0f / 32768.0f;

    for (int i = 0; i < frames; ++i) {
        // 1. Zipper-free 8ms exponential fader smoothing
        appliedGainLin_ += paramSmoothAlpha_ * (targetLin - appliedGainLin_);

        float in0 = 0.0f;
        float in1 = 0.0f;
        if (channels_ == 2) {
            in0 = static_cast<float>(inPcm16[i * 2]) * kInv32768 * appliedGainLin_;
            in1 = static_cast<float>(inPcm16[i * 2 + 1]) * kInv32768 * appliedGainLin_;
        } else {
            in0 = static_cast<float>(inPcm16[i]) * kInv32768 * appliedGainLin_;
            in1 = in0;
        }

        // 2. Shift 4-tap Catmull-Rom 4x inter-sample true-peak window
        tpHist_[0][0] = tpHist_[0][1];
        tpHist_[0][1] = tpHist_[0][2];
        tpHist_[0][2] = tpHist_[0][3];
        tpHist_[0][3] = in0;

        float framePeak = estimateHistoryTruePeak4x(tpHist_[0]);
        if (channels_ == 2) {
            tpHist_[1][0] = tpHist_[1][1];
            tpHist_[1][1] = tpHist_[1][2];
            tpHist_[1][2] = tpHist_[1][3];
            tpHist_[1][3] = in1;
            framePeak = std::max(framePeak, estimateHistoryTruePeak4x(tpHist_[1]));
        }

        const float instTargetGain = (framePeak > ceilingLin_) ? (ceilingLin_ / framePeak) : 1.0f;

        // 3. Store center sample tpHist_[c][1] & targetGain into lookahead ring buffer
        const uint64_t curIdx = frameCount_;
        const size_t writeSlot = static_cast<size_t>(curIdx) & ringMask_;
        audioRing0_[writeSlot] = tpHist_[0][1];
        if (channels_ == 2) {
            audioRing1_[writeSlot] = tpHist_[1][1];
        }
        targetGainRing_[writeSlot] = instTargetGain;

        // 4. Push into O(1) monotonic sliding-minimum deque
        while (dequeSize_ > 0) {
            const size_t backSlot = (dequeHead_ + dequeSize_ - 1) & ringMask_;
            if (dequeVal_[backSlot] >= instTargetGain) {
                --dequeSize_;
            } else {
                break;
            }
        }
        const size_t pushSlot = (dequeHead_ + dequeSize_) & ringMask_;
        dequeIdx_[pushSlot] = curIdx;
        dequeVal_[pushSlot] = instTargetGain;
        ++dequeSize_;

        // 5. Pop expired lookahead window entries for delayed output frame `outIdx`
        const uint64_t outIdx = curIdx - lookahead_;
        while (dequeSize_ > 1 && dequeIdx_[dequeHead_] < outIdx) {
            dequeHead_ = (dequeHead_ + 1) & ringMask_;
            --dequeSize_;
        }
        const float mg = dequeVal_[dequeHead_];

        // 6. Program-dependent dual-release (40ms transient + 240ms anti-pumping floor)
        if (mg < fastEnv_) {
            fastEnv_ = mg;
        } else {
            fastEnv_ = fastRelCoeff_ * fastEnv_ + (1.0f - fastRelCoeff_) * mg;
        }
        if (mg < slowEnv_) {
            slowEnv_ = 0.85f * slowEnv_ + 0.15f * mg;
        } else {
            slowEnv_ = slowRelCoeff_ * slowEnv_ + (1.0f - slowRelCoeff_) * mg;
        }
        const float grDepth = 1.0f - slowEnv_;
        const float blend = std::clamp(grDepth * 1.6f, 0.0f, 0.65f);
        const float env = std::min(mg, (1.0f - blend) * fastEnv_ + blend * slowEnv_);

        // 7. Two-pass causal boxcar (triangular kernel of width = lookahead) smooth attack
        winSum1_ += static_cast<double>(env) - static_cast<double>(boxcar1Ring_[boxcarPos_]);
        boxcar1Ring_[boxcarPos_] = env;
        const float s1 = static_cast<float>(winSum1_ * invHalfWin_);

        winSum2_ += static_cast<double>(s1) - static_cast<double>(boxcar2Ring_[boxcarPos_]);
        boxcar2Ring_[boxcarPos_] = s1;
        float g = static_cast<float>(winSum2_ * invHalfWin_);
        if (++boxcarPos_ >= halfWin_) {
            boxcarPos_ = 0;
        }

        // 8. Strict brickwall ceiling guard & output conversion
        const size_t outSlot = static_cast<size_t>(outIdx) & ringMask_;
        g = std::clamp(std::min(g, targetGainRing_[outSlot]), 0.0f, 1.0f);

        const float y0 = std::clamp(audioRing0_[outSlot] * g, -hardGuard_, hardGuard_);
        if (channels_ == 2) {
            const float y1 = std::clamp(audioRing1_[outSlot] * g, -hardGuard_, hardGuard_);
            outPcm16[i * 2] = floatToPcm16ExactScale(y0);
            outPcm16[i * 2 + 1] = floatToPcm16ExactScale(y1);
        } else {
            outPcm16[i] = floatToPcm16ExactScale(y0);
        }

        ++frameCount_;
    }
}

} // namespace soundtracker
