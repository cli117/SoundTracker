#pragma once

#include "audio_buffer.h"
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace soundtracker {

struct MixClipSpec {
    std::string wavPath;
    int64_t timelineStartFrame = 0;
    int64_t durationFrames = 0;
    double sourceStartSec = 0.0;
    float linearGain = 1.0f;
    int64_t fadeInFrames = 0;
    int64_t fadeOutFrames = 0;
};

class WavRandomAccessReader {
public:
    WavRandomAccessReader() = default;
    ~WavRandomAccessReader();

    WavRandomAccessReader(const WavRandomAccessReader&) = delete;
    WavRandomAccessReader& operator=(const WavRandomAccessReader&) = delete;

    bool openFile(const std::string& path);

    int sampleRate() const { return sampleRate_; }
    int channels() const { return channels_; }
    int64_t totalFrames() const { return totalFrames_; }

    // Thread-safe lock-free random read via POSIX pread()
    void readStereoFloat(int64_t startFrame, int frameCount, float* dstL, float* dstR) const;

private:
    int fd_ = -1;
    int64_t dataOffset_ = 0;
    int64_t totalFrames_ = 0;
    int sampleRate_ = 44100;
    int channels_ = 2;
    uint16_t audioFormat_ = 1;
    uint16_t bitsPerSample_ = 16;
    int bytesPerFrame_ = 4;
};

class RealtimeMultiTrackEngine {
public:
    explicit RealtimeMultiTrackEngine(int outputSampleRate = 44100);
    ~RealtimeMultiTrackEngine() = default;

    int outputSampleRate() const { return outputSampleRate_; }

    void setSchedule(const std::vector<MixClipSpec>& clips, int64_t totalDurationFrames);
    void clearCache();
    void resetEnvelope();

    // Renders `numFrames` of stereo interleaved int16 PCM starting at `timelineStartFrame`.
    void renderPreviewBlock(int64_t timelineStartFrame, int16_t* outStereoPcm, int numFrames);

private:
    struct ResolvedClip {
        std::shared_ptr<WavRandomAccessReader> reader;
        int64_t timelineStartFrame = 0;
        int64_t durationFrames = 0;
        double sourceStartFrame = 0.0;
        double resampleStep = 1.0;
        float linearGain = 1.0f;
        int64_t fadeInFrames = 0;
        int64_t fadeOutFrames = 0;
    };

    struct ResolvedSchedule {
        std::vector<ResolvedClip> clips;
        int64_t totalDurationFrames = 0;
    };

    int outputSampleRate_ = 44100;
    float limiterGain_ = 1.0f;
    float releaseCoeff_ = 0.9995f;

    std::mutex mutex_;
    std::shared_ptr<const ResolvedSchedule> activeSchedule_;
    std::unordered_map<std::string, std::shared_ptr<WavRandomAccessReader>> readerCache_;

    std::vector<float> mixL_;
    std::vector<float> mixR_;
    std::vector<float> srcScratchL_;
    std::vector<float> srcScratchR_;
};

// Fast high-resolution waveform peak extraction directly via random-access chunk sampling
bool extractFastWavPeaks(const std::string& wavPath, int numBins, std::vector<float>& outPeaks);

// Offline mastering-grade mixdown of all multi-track clips into a 16-bit WAV file
bool renderMultiTrackMixdownToWav(
    const std::vector<MixClipSpec>& clips,
    int64_t totalDurationFrames,
    int outputSampleRate,
    const std::string& outWavPath,
    const ProgressCallback& progressCb,
    std::string* errMsg = nullptr
);

} // namespace soundtracker
