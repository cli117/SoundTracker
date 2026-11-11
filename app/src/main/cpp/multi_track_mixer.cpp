#include "multi_track_mixer.h"
#include "loudness_maximizer.h"
#include "wav_io.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace soundtracker {

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kCeilingLinear = 0.97723722f; // -0.2 dBFS
constexpr int64_t kMicroDeclickFrames = 64;   // ~1.45ms anti-click ramp on zero-fade cuts

uint16_t readLE16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

uint32_t readLE32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

ssize_t preadFull(int fd, uint8_t* dst, size_t count, off_t offset) {
    size_t total = 0;
    while (total < count) {
        ssize_t n = ::pread(fd, dst + total, count - total, offset + static_cast<off_t>(total));
        if (n <= 0) break;
        total += static_cast<size_t>(n);
    }
    return static_cast<ssize_t>(total);
}

inline float computeFadeMultiplier(
    int64_t localFrame,
    int64_t durationFrames,
    int64_t fadeInFrames,
    int64_t fadeOutFrames
) {
    if (durationFrames <= 2) return 1.0f;
    const int64_t halfDur = durationFrames / 2;
    const int64_t effIn = std::min(halfDur, std::max(fadeInFrames, kMicroDeclickFrames));
    const int64_t effOut = std::min(halfDur, std::max(fadeOutFrames, kMicroDeclickFrames));

    float gain = 1.0f;
    if (effIn > 0 && localFrame < effIn) {
        float t = static_cast<float>(localFrame) / static_cast<float>(effIn);
        gain *= 0.5f - 0.5f * std::cos(kPi * t);
    }
    const int64_t rem = durationFrames - 1 - localFrame;
    if (effOut > 0 && rem < effOut) {
        float t = static_cast<float>(std::max<int64_t>(0, rem)) / static_cast<float>(effOut);
        gain *= 0.5f - 0.5f * std::cos(kPi * t);
    }
    return gain;
}

} // namespace

WavRandomAccessReader::~WavRandomAccessReader() {
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
}

bool WavRandomAccessReader::openFile(const std::string& path) {
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }

    int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) return false;

    struct stat st {};
    if (::fstat(fd, &st) != 0 || st.st_size < 44) {
        ::close(fd);
        return false;
    }
    const int64_t fileSize = static_cast<int64_t>(st.st_size);

    uint8_t riff[12];
    if (preadFull(fd, riff, 12, 0) != 12 ||
        std::memcmp(riff, "RIFF", 4) != 0 ||
        std::memcmp(riff + 8, "WAVE", 4) != 0) {
        ::close(fd);
        return false;
    }

    bool foundFmt = false;
    bool foundData = false;
    int64_t pos = 12;
    uint32_t dataSize = 0;

    while (pos + 8 <= fileSize) {
        uint8_t chunkHdr[8];
        if (preadFull(fd, chunkHdr, 8, static_cast<off_t>(pos)) != 8) break;
        uint32_t chunkSize = readLE32(chunkHdr + 4);
        int64_t payloadOffset = pos + 8;

        if (std::memcmp(chunkHdr, "fmt ", 4) == 0 && chunkSize >= 16) {
            uint8_t fmtBuf[40] = {0};
            size_t toRead = std::min<size_t>(chunkSize, sizeof(fmtBuf));
            if (preadFull(fd, fmtBuf, toRead, static_cast<off_t>(payloadOffset)) != static_cast<ssize_t>(toRead)) {
                break;
            }
            audioFormat_ = readLE16(fmtBuf);
            channels_ = static_cast<int>(readLE16(fmtBuf + 2));
            sampleRate_ = static_cast<int>(readLE32(fmtBuf + 4));
            bitsPerSample_ = readLE16(fmtBuf + 14);
            if (audioFormat_ == 0xFFFE && toRead >= 40) {
                audioFormat_ = readLE16(fmtBuf + 24);
            }
            foundFmt = true;
        } else if (std::memcmp(chunkHdr, "data", 4) == 0) {
            dataOffset_ = payloadOffset;
            dataSize = chunkSize;
            foundData = true;
            break;
        }

        pos = payloadOffset + static_cast<int64_t>(chunkSize) + (chunkSize & 1);
    }

    int bytesPerSample = bitsPerSample_ / 8;
    if (!foundFmt || !foundData || channels_ <= 0 || sampleRate_ <= 0 ||
        (audioFormat_ != 1 && audioFormat_ != 3) ||
        bytesPerSample <= 0 || bytesPerSample > 4) {
        ::close(fd);
        return false;
    }

    bytesPerFrame_ = channels_ * bytesPerSample;
    int64_t availBytes = std::max<int64_t>(0, fileSize - dataOffset_);
    int64_t actualBytes = (dataSize == 0 || static_cast<int64_t>(dataSize) > availBytes)
        ? availBytes
        : static_cast<int64_t>(dataSize);
    totalFrames_ = actualBytes / bytesPerFrame_;
    if (totalFrames_ <= 0) {
        ::close(fd);
        return false;
    }

    fd_ = fd;
    return true;
}

void WavRandomAccessReader::readStereoFloat(int64_t startFrame, int frameCount, float* dstL, float* dstR) const {
    if (frameCount <= 0) return;
    std::memset(dstL, 0, static_cast<size_t>(frameCount) * sizeof(float));
    std::memset(dstR, 0, static_cast<size_t>(frameCount) * sizeof(float));
    if (fd_ < 0 || totalFrames_ <= 0) return;

    int dstOffset = 0;
    int64_t srcStart = startFrame;
    int remaining = frameCount;

    if (srcStart < 0) {
        int64_t skip = std::min<int64_t>(-srcStart, remaining);
        dstOffset += static_cast<int>(skip);
        remaining -= static_cast<int>(skip);
        srcStart = 0;
    }
    if (remaining <= 0 || srcStart >= totalFrames_) return;

    int toReadFrames = static_cast<int>(std::min<int64_t>(remaining, totalFrames_ - srcStart));
    if (toReadFrames <= 0) return;

    static thread_local std::vector<uint8_t> tlRaw;
    size_t byteCount = static_cast<size_t>(toReadFrames) * static_cast<size_t>(bytesPerFrame_);
    if (tlRaw.size() < byteCount) {
        tlRaw.resize(byteCount);
    }

    off_t fileOffset = static_cast<off_t>(dataOffset_ + srcStart * bytesPerFrame_);
    ssize_t gotBytes = preadFull(fd_, tlRaw.data(), byteCount, fileOffset);
    if (gotBytes <= 0) return;

    int gotFrames = static_cast<int>(gotBytes / bytesPerFrame_);
    const uint8_t* p = tlRaw.data();
    const int ch = channels_;

    if (audioFormat_ == 1 && bitsPerSample_ == 16) {
        constexpr float kScale16 = 1.0f / 32768.0f;
        if (ch == 1) {
            for (int i = 0; i < gotFrames; ++i) {
                float v = static_cast<float>(static_cast<int16_t>(readLE16(p))) * kScale16;
                dstL[dstOffset + i] = v;
                dstR[dstOffset + i] = v;
                p += 2;
            }
        } else {
            const int stride = ch * 2;
            for (int i = 0; i < gotFrames; ++i) {
                dstL[dstOffset + i] = static_cast<float>(static_cast<int16_t>(readLE16(p))) * kScale16;
                dstR[dstOffset + i] = static_cast<float>(static_cast<int16_t>(readLE16(p + 2))) * kScale16;
                p += stride;
            }
        }
    } else if (audioFormat_ == 3 && bitsPerSample_ == 32) {
        const int stride = ch * 4;
        for (int i = 0; i < gotFrames; ++i) {
            float l = 0.0f;
            float r = 0.0f;
            std::memcpy(&l, p, 4);
            if (ch >= 2) std::memcpy(&r, p + 4, 4);
            else r = l;
            dstL[dstOffset + i] = std::isfinite(l) ? l : 0.0f;
            dstR[dstOffset + i] = std::isfinite(r) ? r : 0.0f;
            p += stride;
        }
    } else if (audioFormat_ == 1 && bitsPerSample_ == 24) {
        constexpr float kScale24 = 1.0f / 8388608.0f;
        const int stride = ch * 3;
        for (int i = 0; i < gotFrames; ++i) {
            int32_t s0 = static_cast<int32_t>(p[0]) |
                         (static_cast<int32_t>(p[1]) << 8) |
                         (static_cast<int32_t>(static_cast<int8_t>(p[2])) << 16);
            float l = static_cast<float>(s0) * kScale24;
            float r = l;
            if (ch >= 2) {
                int32_t s1 = static_cast<int32_t>(p[3]) |
                             (static_cast<int32_t>(p[4]) << 8) |
                             (static_cast<int32_t>(static_cast<int8_t>(p[5])) << 16);
                r = static_cast<float>(s1) * kScale24;
            }
            dstL[dstOffset + i] = l;
            dstR[dstOffset + i] = r;
            p += stride;
        }
    } else if (audioFormat_ == 1 && bitsPerSample_ == 32) {
        constexpr float kScale32 = 1.0f / 2147483648.0f;
        const int stride = ch * 4;
        for (int i = 0; i < gotFrames; ++i) {
            float l = static_cast<float>(static_cast<int32_t>(readLE32(p))) * kScale32;
            float r = (ch >= 2)
                ? static_cast<float>(static_cast<int32_t>(readLE32(p + 4))) * kScale32
                : l;
            dstL[dstOffset + i] = l;
            dstR[dstOffset + i] = r;
            p += stride;
        }
    } else if (audioFormat_ == 1 && bitsPerSample_ == 8) {
        const int stride = ch;
        for (int i = 0; i < gotFrames; ++i) {
            float l = (static_cast<int>(p[0]) - 128) / 128.0f;
            float r = (ch >= 2) ? ((static_cast<int>(p[1]) - 128) / 128.0f) : l;
            dstL[dstOffset + i] = l;
            dstR[dstOffset + i] = r;
            p += stride;
        }
    }
}

RealtimeMultiTrackEngine::RealtimeMultiTrackEngine(int outputSampleRate)
    : outputSampleRate_(std::max(8000, outputSampleRate)) {
    // ~65ms smooth release envelope for real-time bus anti-clipping
    releaseCoeff_ = std::exp(-1.0f / (0.065f * static_cast<float>(outputSampleRate_)));
    activeSchedule_ = std::make_shared<ResolvedSchedule>();
}

void RealtimeMultiTrackEngine::clearCache() {
    std::lock_guard<std::mutex> lock(mutex_);
    readerCache_.clear();
    activeSchedule_ = std::make_shared<ResolvedSchedule>();
}

void RealtimeMultiTrackEngine::resetEnvelope() {
    limiterGain_ = 1.0f;
}

void RealtimeMultiTrackEngine::setSchedule(
    const std::vector<MixClipSpec>& clips,
    int64_t totalDurationFrames
) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto next = std::make_shared<ResolvedSchedule>();
    next->totalDurationFrames = std::max<int64_t>(0, totalDurationFrames);
    next->clips.reserve(clips.size());

    // Keep only readers still referenced or recently used (bounded cache)
    if (readerCache_.size() > 32) {
        readerCache_.clear();
    }

    for (const auto& spec : clips) {
        if (spec.durationFrames <= 0 || spec.linearGain <= 0.0001f || spec.wavPath.empty()) {
            continue;
        }
        std::shared_ptr<WavRandomAccessReader> reader;
        auto it = readerCache_.find(spec.wavPath);
        if (it != readerCache_.end()) {
            reader = it->second;
        } else {
            auto opened = std::make_shared<WavRandomAccessReader>();
            if (opened->openFile(spec.wavPath)) {
                readerCache_[spec.wavPath] = opened;
                reader = opened;
            }
        }
        if (!reader) continue;

        ResolvedClip rc;
        rc.reader = reader;
        rc.timelineStartFrame = spec.timelineStartFrame;
        rc.durationFrames = spec.durationFrames;
        rc.sourceStartFrame = spec.sourceStartSec * static_cast<double>(reader->sampleRate());
        rc.resampleStep = static_cast<double>(reader->sampleRate()) / static_cast<double>(outputSampleRate_);
        rc.linearGain = spec.linearGain;
        rc.fadeInFrames = std::max<int64_t>(0, spec.fadeInFrames);
        rc.fadeOutFrames = std::max<int64_t>(0, spec.fadeOutFrames);
        next->clips.push_back(std::move(rc));
    }

    activeSchedule_ = std::move(next);
}

void RealtimeMultiTrackEngine::renderPreviewBlock(
    int64_t timelineStartFrame,
    int16_t* outStereoPcm,
    int numFrames
) {
    if (numFrames <= 0 || !outStereoPcm) return;

    std::shared_ptr<const ResolvedSchedule> schedule;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        schedule = activeSchedule_;
    }

    if (!schedule || schedule->clips.empty()) {
        std::memset(outStereoPcm, 0, static_cast<size_t>(numFrames) * 2 * sizeof(int16_t));
        return;
    }

    if (static_cast<int>(mixL_.size()) < numFrames) {
        mixL_.resize(static_cast<size_t>(numFrames));
        mixR_.resize(static_cast<size_t>(numFrames));
    }
    std::memset(mixL_.data(), 0, static_cast<size_t>(numFrames) * sizeof(float));
    std::memset(mixR_.data(), 0, static_cast<size_t>(numFrames) * sizeof(float));

    const int64_t blockStart = timelineStartFrame;
    const int64_t blockEnd = timelineStartFrame + numFrames;

    for (const auto& clip : schedule->clips) {
        const int64_t clipStart = clip.timelineStartFrame;
        const int64_t clipEnd = clip.timelineStartFrame + clip.durationFrames;
        const int64_t overlapStart = std::max(blockStart, clipStart);
        const int64_t overlapEnd = std::min(blockEnd, clipEnd);
        if (overlapStart >= overlapEnd) continue;

        const int outOffset = static_cast<int>(overlapStart - blockStart);
        const int overlapFrames = static_cast<int>(overlapEnd - overlapStart);
        const int64_t localStart = overlapStart - clipStart;

        if (std::abs(clip.resampleStep - 1.0) < 1e-6) {
            const int64_t srcStart = static_cast<int64_t>(std::llround(clip.sourceStartFrame)) + localStart;
            if (static_cast<int>(srcScratchL_.size()) < overlapFrames) {
                srcScratchL_.resize(static_cast<size_t>(overlapFrames));
                srcScratchR_.resize(static_cast<size_t>(overlapFrames));
            }
            clip.reader->readStereoFloat(srcStart, overlapFrames, srcScratchL_.data(), srcScratchR_.data());
            for (int i = 0; i < overlapFrames; ++i) {
                const float g = clip.linearGain * computeFadeMultiplier(
                    localStart + i,
                    clip.durationFrames,
                    clip.fadeInFrames,
                    clip.fadeOutFrames
                );
                mixL_[outOffset + i] += srcScratchL_[i] * g;
                mixR_[outOffset + i] += srcScratchR_[i] * g;
            }
        } else {
            const double firstPos = clip.sourceStartFrame + static_cast<double>(localStart) * clip.resampleStep;
            const double lastPos = clip.sourceStartFrame + static_cast<double>(localStart + overlapFrames - 1) * clip.resampleStep;
            const int64_t baseSrcFrame = static_cast<int64_t>(std::floor(firstPos));
            const int neededSrcFrames = static_cast<int>(std::ceil(lastPos) - baseSrcFrame) + 2;
            if (neededSrcFrames <= 0) continue;

            if (static_cast<int>(srcScratchL_.size()) < neededSrcFrames) {
                srcScratchL_.resize(static_cast<size_t>(neededSrcFrames));
                srcScratchR_.resize(static_cast<size_t>(neededSrcFrames));
            }
            clip.reader->readStereoFloat(baseSrcFrame, neededSrcFrames, srcScratchL_.data(), srcScratchR_.data());

            for (int i = 0; i < overlapFrames; ++i) {
                const double exactSrc = clip.sourceStartFrame + static_cast<double>(localStart + i) * clip.resampleStep;
                const double rel = exactSrc - static_cast<double>(baseSrcFrame);
                int idx0 = static_cast<int>(rel);
                idx0 = std::clamp(idx0, 0, neededSrcFrames - 2);
                const float frac = static_cast<float>(rel - static_cast<double>(idx0));
                const float sL = srcScratchL_[idx0] + frac * (srcScratchL_[idx0 + 1] - srcScratchL_[idx0]);
                const float sR = srcScratchR_[idx0] + frac * (srcScratchR_[idx0 + 1] - srcScratchR_[idx0]);
                const float g = clip.linearGain * computeFadeMultiplier(
                    localStart + i,
                    clip.durationFrames,
                    clip.fadeInFrames,
                    clip.fadeOutFrames
                );
                mixL_[outOffset + i] += sL * g;
                mixR_[outOffset + i] += sR * g;
            }
        }
    }

    // Smooth 32-bit float master ceiling limiter (-0.2 dBFS) -> int16 PCM
    float envGain = limiterGain_;
    const float rel = releaseCoeff_;
    for (int i = 0; i < numFrames; ++i) {
        float l = mixL_[i];
        float r = mixR_[i];
        float peak = std::max(std::abs(l), std::abs(r));
        float needed = (peak * envGain > kCeilingLinear && peak > 1e-6f)
            ? (kCeilingLinear / peak)
            : 1.0f;
        if (needed < envGain) {
            envGain = needed; // Instant zero-overshoot attack
        } else {
            envGain = rel * envGain + (1.0f - rel) * 1.0f;
            if (peak * envGain > kCeilingLinear && peak > 1e-6f) {
                envGain = kCeilingLinear / peak;
            }
        }

        int32_t qL = static_cast<int32_t>(std::lrintf(l * envGain * 32768.0f));
        int32_t qR = static_cast<int32_t>(std::lrintf(r * envGain * 32768.0f));
        outStereoPcm[i * 2] = static_cast<int16_t>(std::clamp(qL, -32768, 32767));
        outStereoPcm[i * 2 + 1] = static_cast<int16_t>(std::clamp(qR, -32768, 32767));
    }
    limiterGain_ = envGain;
}

bool extractFastWavPeaks(const std::string& wavPath, int numBins, std::vector<float>& outPeaks) {
    if (numBins <= 0) return false;
    WavRandomAccessReader reader;
    if (!reader.openFile(wavPath)) return false;

    const int64_t total = reader.totalFrames();
    if (total <= 0) return false;

    outPeaks.assign(static_cast<size_t>(numBins), 0.05f);
    constexpr int kProbeFrames = 512;
    std::vector<float> bufL(kProbeFrames);
    std::vector<float> bufR(kProbeFrames);

    float globalMax = 0.01f;
    for (int b = 0; b < numBins; ++b) {
        int64_t binStart = (total * b) / numBins;
        int64_t binEnd = std::max(binStart + 1, (total * (b + 1)) / numBins);
        int64_t binLen = binEnd - binStart;
        int readLen = static_cast<int>(std::min<int64_t>(kProbeFrames, binLen));
        // Sample around the center of each bin for representative transient energy
        int64_t probeStart = binStart + std::max<int64_t>(0, (binLen - readLen) / 2);
        reader.readStereoFloat(probeStart, readLen, bufL.data(), bufR.data());

        float localPeak = 0.0f;
        for (int i = 0; i < readLen; ++i) {
            localPeak = std::max(localPeak, std::max(std::abs(bufL[i]), std::abs(bufR[i])));
        }
        outPeaks[static_cast<size_t>(b)] = localPeak;
        globalMax = std::max(globalMax, localPeak);
    }

    if (globalMax > 0.01f) {
        const float norm = 1.0f / std::min(1.0f, globalMax * 1.05f);
        for (float& v : outPeaks) {
            v = std::clamp(v * norm, 0.04f, 1.0f);
        }
    }
    return true;
}

bool renderMultiTrackMixdownToWav(
    const std::vector<MixClipSpec>& clips,
    int64_t totalDurationFrames,
    int outputSampleRate,
    const std::string& outWavPath,
    const ProgressCallback& progressCb,
    std::string* errMsg
) {
    if (totalDurationFrames <= 0 || outputSampleRate <= 0) {
        if (errMsg) *errMsg = "混剪轨道为空";
        return false;
    }

    struct OfflineClip {
        std::shared_ptr<WavRandomAccessReader> reader;
        MixClipSpec spec;
        double sourceStartFrame = 0.0;
        double resampleStep = 1.0;
    };

    std::unordered_map<std::string, std::shared_ptr<WavRandomAccessReader>> readers;
    std::vector<OfflineClip> activeClips;
    activeClips.reserve(clips.size());

    for (const auto& spec : clips) {
        if (spec.durationFrames <= 0 || spec.linearGain <= 0.0001f || spec.wavPath.empty()) continue;
        std::shared_ptr<WavRandomAccessReader> r;
        auto it = readers.find(spec.wavPath);
        if (it != readers.end()) {
            r = it->second;
        } else {
            auto opened = std::make_shared<WavRandomAccessReader>();
            if (opened->openFile(spec.wavPath)) {
                readers[spec.wavPath] = opened;
                r = opened;
            }
        }
        if (!r) continue;
        OfflineClip oc;
        oc.reader = r;
        oc.spec = spec;
        oc.sourceStartFrame = spec.sourceStartSec * static_cast<double>(r->sampleRate());
        oc.resampleStep = static_cast<double>(r->sampleRate()) / static_cast<double>(outputSampleRate);
        activeClips.push_back(std::move(oc));
    }

    if (activeClips.empty()) {
        if (errMsg) *errMsg = "没有可合成的有效音频片段（请检查是否全部静音）";
        return false;
    }

    PlanarAudioBuffer mixBuf;
    mixBuf.sampleRate = outputSampleRate;
    mixBuf.resize(2, static_cast<size_t>(totalDurationFrames));

    constexpr int kChunk = 32768;
    std::vector<float> tmpL;
    std::vector<float> tmpR;

    for (int64_t blockStart = 0; blockStart < totalDurationFrames; blockStart += kChunk) {
        if (progressCb) {
            float p = 0.80f * (static_cast<float>(blockStart) / static_cast<float>(totalDurationFrames));
            if (!progressCb(p)) {
                if (errMsg) *errMsg = "Cancelled";
                return false;
            }
        }

        const int blockFrames = static_cast<int>(std::min<int64_t>(kChunk, totalDurationFrames - blockStart));
        const int64_t blockEnd = blockStart + blockFrames;
        float* dstL = mixBuf.channels[0].data() + blockStart;
        float* dstR = mixBuf.channels[1].data() + blockStart;

        for (const auto& oc : activeClips) {
            const int64_t clipStart = oc.spec.timelineStartFrame;
            const int64_t clipEnd = clipStart + oc.spec.durationFrames;
            const int64_t ovStart = std::max(blockStart, clipStart);
            const int64_t ovEnd = std::min(blockEnd, clipEnd);
            if (ovStart >= ovEnd) continue;

            const int outOff = static_cast<int>(ovStart - blockStart);
            const int ovFrames = static_cast<int>(ovEnd - ovStart);
            const int64_t localStart = ovStart - clipStart;

            if (std::abs(oc.resampleStep - 1.0) < 1e-6) {
                const int64_t srcPos = static_cast<int64_t>(std::llround(oc.sourceStartFrame)) + localStart;
                if (static_cast<int>(tmpL.size()) < ovFrames) {
                    tmpL.resize(static_cast<size_t>(ovFrames));
                    tmpR.resize(static_cast<size_t>(ovFrames));
                }
                oc.reader->readStereoFloat(srcPos, ovFrames, tmpL.data(), tmpR.data());
                for (int i = 0; i < ovFrames; ++i) {
                    const float g = oc.spec.linearGain * computeFadeMultiplier(
                        localStart + i,
                        oc.spec.durationFrames,
                        oc.spec.fadeInFrames,
                        oc.spec.fadeOutFrames
                    );
                    dstL[outOff + i] += tmpL[i] * g;
                    dstR[outOff + i] += tmpR[i] * g;
                }
            } else {
                const double firstPos = oc.sourceStartFrame + static_cast<double>(localStart) * oc.resampleStep;
                const double lastPos = oc.sourceStartFrame + static_cast<double>(localStart + ovFrames - 1) * oc.resampleStep;
                const int64_t baseSrc = static_cast<int64_t>(std::floor(firstPos));
                const int needed = static_cast<int>(std::ceil(lastPos) - baseSrc) + 2;
                if (needed <= 0) continue;
                if (static_cast<int>(tmpL.size()) < needed) {
                    tmpL.resize(static_cast<size_t>(needed));
                    tmpR.resize(static_cast<size_t>(needed));
                }
                oc.reader->readStereoFloat(baseSrc, needed, tmpL.data(), tmpR.data());
                for (int i = 0; i < ovFrames; ++i) {
                    const double exactSrc = oc.sourceStartFrame + static_cast<double>(localStart + i) * oc.resampleStep;
                    const double rel = exactSrc - static_cast<double>(baseSrc);
                    int idx0 = std::clamp(static_cast<int>(rel), 0, needed - 2);
                    const float frac = static_cast<float>(rel - static_cast<double>(idx0));
                    const float sL = tmpL[idx0] + frac * (tmpL[idx0 + 1] - tmpL[idx0]);
                    const float sR = tmpR[idx0] + frac * (tmpR[idx0 + 1] - tmpR[idx0]);
                    const float g = oc.spec.linearGain * computeFadeMultiplier(
                        localStart + i,
                        oc.spec.durationFrames,
                        oc.spec.fadeInFrames,
                        oc.spec.fadeOutFrames
                    );
                    dstL[outOff + i] += sL * g;
                    dstR[outOff + i] += sR * g;
                }
            }
        }
    }

    // Mastering-grade lookahead true-peak limiting (-0.2 dBTP) so overlapping multi-track sum never clips
    BoostResult boostRes = boostVolumeTransparent(
        mixBuf,
        0.0f,
        -0.2f,
        [&](float p) -> bool {
            return progressCb ? progressCb(0.80f + p * 0.14f) : true;
        }
    );
    if (!boostRes.ok) {
        if (errMsg) *errMsg = "Cancelled";
        return false;
    }

    if (progressCb && !progressCb(0.95f)) {
        if (errMsg) *errMsg = "Cancelled";
        return false;
    }

    if (!writeWavFile(outWavPath, mixBuf, false, errMsg)) {
        return false;
    }

    if (progressCb) progressCb(1.0f);
    return true;
}

} // namespace soundtracker
