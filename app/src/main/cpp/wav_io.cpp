#include "wav_io.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <random>

namespace soundtracker {

namespace {

uint16_t readLE16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

uint32_t readLE32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

void writeLE16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v & 0xFF);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
}

void writeLE32(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v & 0xFF);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
    p[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}

} // namespace

bool readWavFile(const std::string& path, PlanarAudioBuffer& outBuffer, std::string* errMsg) {
    FILE* fp = std::fopen(path.c_str(), "rb");
    if (!fp) {
        if (errMsg) *errMsg = "Cannot open WAV file: " + path;
        return false;
    }

    uint8_t riffHeader[12];
    if (std::fread(riffHeader, 1, 12, fp) != 12 ||
        std::memcmp(riffHeader, "RIFF", 4) != 0 ||
        std::memcmp(riffHeader + 8, "WAVE", 4) != 0) {
        std::fclose(fp);
        if (errMsg) *errMsg = "Not a valid RIFF/WAVE file";
        return false;
    }

    uint16_t audioFormat = 0;
    uint16_t numChannels = 0;
    uint32_t sampleRate = 0;
    uint16_t bitsPerSample = 0;
    bool foundFmt = false;
    bool foundData = false;
    long dataOffset = 0;
    uint32_t dataSize = 0;

    while (!std::feof(fp)) {
        uint8_t chunkHdr[8];
        if (std::fread(chunkHdr, 1, 8, fp) != 8) break;
        uint32_t chunkSize = readLE32(chunkHdr + 4);

        if (std::memcmp(chunkHdr, "fmt ", 4) == 0 && chunkSize >= 16) {
            std::vector<uint8_t> fmtBuf(chunkSize);
            if (std::fread(fmtBuf.data(), 1, chunkSize, fp) != chunkSize) break;
            audioFormat = readLE16(fmtBuf.data());
            numChannels = readLE16(fmtBuf.data() + 2);
            sampleRate = readLE32(fmtBuf.data() + 4);
            bitsPerSample = readLE16(fmtBuf.data() + 14);
            // Handle WAVE_FORMAT_EXTENSIBLE (0xFFFE)
            if (audioFormat == 0xFFFE && chunkSize >= 40) {
                audioFormat = readLE16(fmtBuf.data() + 24);
            }
            foundFmt = true;
            if (chunkSize & 1) std::fseek(fp, 1, SEEK_CUR);
        } else if (std::memcmp(chunkHdr, "data", 4) == 0) {
            dataOffset = std::ftell(fp);
            dataSize = chunkSize;
            foundData = true;
            break;
        } else {
            long skip = static_cast<long>(chunkSize) + (chunkSize & 1);
            if (std::fseek(fp, skip, SEEK_CUR) != 0) break;
        }
    }

    if (!foundFmt || !foundData || numChannels == 0 || sampleRate == 0 ||
        (audioFormat != 1 && audioFormat != 3)) {
        std::fclose(fp);
        if (errMsg) *errMsg = "Unsupported WAV format";
        return false;
    }

    int bytesPerSample = bitsPerSample / 8;
    if (bytesPerSample <= 0 || bytesPerSample > 4) {
        std::fclose(fp);
        if (errMsg) *errMsg = "Unsupported WAV bit depth";
        return false;
    }

    std::fseek(fp, 0, SEEK_END);
    long fileSize = std::ftell(fp);
    std::fseek(fp, dataOffset, SEEK_SET);
    long availableBytes = std::max<long>(0, fileSize - dataOffset);
    size_t actualBytes = (dataSize == 0 || dataSize > static_cast<uint32_t>(availableBytes))
        ? static_cast<size_t>(availableBytes)
        : static_cast<size_t>(dataSize);

    size_t frameSize = static_cast<size_t>(numChannels) * bytesPerSample;
    size_t totalFrames = actualBytes / frameSize;
    if (totalFrames == 0) {
        std::fclose(fp);
        if (errMsg) *errMsg = "Empty WAV audio stream";
        return false;
    }

    outBuffer.sampleRate = static_cast<int>(sampleRate);
    outBuffer.resize(numChannels, totalFrames);

    constexpr size_t kChunkFrames = 32768;
    std::vector<uint8_t> rawBuf(kChunkFrames * frameSize);

    size_t framesReadTotal = 0;
    while (framesReadTotal < totalFrames) {
        size_t toRead = std::min(kChunkFrames, totalFrames - framesReadTotal);
        size_t gotBytes = std::fread(rawBuf.data(), 1, toRead * frameSize, fp);
        size_t gotFrames = gotBytes / frameSize;
        if (gotFrames == 0) break;

        const uint8_t* p = rawBuf.data();
        if (audioFormat == 3 && bitsPerSample == 32) {
            for (size_t i = 0; i < gotFrames; ++i) {
                for (int c = 0; c < numChannels; ++c) {
                    float val;
                    std::memcpy(&val, p, sizeof(float));
                    if (!std::isfinite(val)) val = 0.0f;
                    outBuffer.channels[c][framesReadTotal + i] = val;
                    p += 4;
                }
            }
        } else if (audioFormat == 1 && bitsPerSample == 16) {
            constexpr float kScale16 = 1.0f / 32768.0f;
            for (size_t i = 0; i < gotFrames; ++i) {
                for (int c = 0; c < numChannels; ++c) {
                    int16_t s = static_cast<int16_t>(readLE16(p));
                    outBuffer.channels[c][framesReadTotal + i] = static_cast<float>(s) * kScale16;
                    p += 2;
                }
            }
        } else if (audioFormat == 1 && bitsPerSample == 24) {
            constexpr float kScale24 = 1.0f / 8388608.0f;
            for (size_t i = 0; i < gotFrames; ++i) {
                for (int c = 0; c < numChannels; ++c) {
                    int32_t s = (static_cast<int32_t>(p[0])) |
                                (static_cast<int32_t>(p[1]) << 8) |
                                (static_cast<int32_t>(static_cast<int8_t>(p[2])) << 16);
                    outBuffer.channels[c][framesReadTotal + i] = static_cast<float>(s) * kScale24;
                    p += 3;
                }
            }
        } else if (audioFormat == 1 && bitsPerSample == 32) {
            constexpr float kScale32 = 1.0f / 2147483648.0f;
            for (size_t i = 0; i < gotFrames; ++i) {
                for (int c = 0; c < numChannels; ++c) {
                    int32_t s = static_cast<int32_t>(readLE32(p));
                    outBuffer.channels[c][framesReadTotal + i] = static_cast<float>(s) * kScale32;
                    p += 4;
                }
            }
        } else if (audioFormat == 1 && bitsPerSample == 8) {
            for (size_t i = 0; i < gotFrames; ++i) {
                for (int c = 0; c < numChannels; ++c) {
                    outBuffer.channels[c][framesReadTotal + i] = (static_cast<int>(*p) - 128) / 128.0f;
                    p += 1;
                }
            }
        }

        framesReadTotal += gotFrames;
    }

    std::fclose(fp);
    if (framesReadTotal < totalFrames) {
        for (int c = 0; c < numChannels; ++c) {
            outBuffer.channels[c].resize(framesReadTotal);
        }
    }
    return framesReadTotal > 0;
}

bool writeWavFile(
    const std::string& path,
    const PlanarAudioBuffer& buffer,
    bool float32Format,
    std::string* errMsg
) {
    if (buffer.numChannels() == 0 || buffer.numFrames() == 0) {
        if (errMsg) *errMsg = "Cannot write empty audio buffer";
        return false;
    }

    FILE* fp = std::fopen(path.c_str(), "wb");
    if (!fp) {
        if (errMsg) *errMsg = "Cannot open output WAV file: " + path;
        return false;
    }

    const uint16_t nCh = static_cast<uint16_t>(buffer.numChannels());
    const uint32_t sr = static_cast<uint32_t>(buffer.sampleRate);
    const uint16_t bits = float32Format ? 32 : 16;
    const uint16_t formatTag = float32Format ? 3 : 1;
    const uint16_t blockAlign = nCh * (bits / 8);
    const uint32_t byteRate = sr * blockAlign;
    const size_t nFrames = buffer.numFrames();
    const uint32_t dataBytes = static_cast<uint32_t>(nFrames * blockAlign);
    const uint32_t riffSize = 36 + dataBytes;

    uint8_t header[44];
    std::memcpy(header, "RIFF", 4);
    writeLE32(header + 4, riffSize);
    std::memcpy(header + 8, "WAVE", 4);
    std::memcpy(header + 12, "fmt ", 4);
    writeLE32(header + 16, 16);
    writeLE16(header + 20, formatTag);
    writeLE16(header + 22, nCh);
    writeLE32(header + 24, sr);
    writeLE32(header + 28, byteRate);
    writeLE16(header + 32, blockAlign);
    writeLE16(header + 34, bits);
    std::memcpy(header + 36, "data", 4);
    writeLE32(header + 40, dataBytes);

    if (std::fwrite(header, 1, 44, fp) != 44) {
        std::fclose(fp);
        if (errMsg) *errMsg = "Failed writing WAV header";
        return false;
    }

    constexpr size_t kChunkFrames = 32768;
    if (float32Format) {
        std::vector<float> interleaved(kChunkFrames * nCh);
        size_t pos = 0;
        while (pos < nFrames) {
            size_t cnt = std::min(kChunkFrames, nFrames - pos);
            for (size_t i = 0; i < cnt; ++i) {
                for (int c = 0; c < nCh; ++c) {
                    float v = buffer.channels[c][pos + i];
                    if (!std::isfinite(v)) v = 0.0f;
                    interleaved[i * nCh + c] = v;
                }
            }
            if (std::fwrite(interleaved.data(), sizeof(float), cnt * nCh, fp) != cnt * nCh) {
                std::fclose(fp);
                return false;
            }
            pos += cnt;
        }
    } else {
        // 16-bit PCM with TPDF dithering for ultra-clean studio noise floor
        std::minstd_rand rng(1337u);
        constexpr float kInvRng = 1.0f / static_cast<float>(std::minstd_rand::max());
        std::vector<uint8_t> outBytes(kChunkFrames * blockAlign);
        size_t pos = 0;
        while (pos < nFrames) {
            size_t cnt = std::min(kChunkFrames, nFrames - pos);
            uint8_t* dst = outBytes.data();
            for (size_t i = 0; i < cnt; ++i) {
                for (int c = 0; c < nCh; ++c) {
                    float v = buffer.channels[c][pos + i];
                    if (!std::isfinite(v)) v = 0.0f;
                    v = std::clamp(v, -1.0f, 1.0f);
                    float dither = (rng() * kInvRng - rng() * kInvRng) * 0.5f; // [-0.5, +0.5] LSB
                    int32_t q = static_cast<int32_t>(std::lrintf(v * 32767.0f + dither));
                    q = std::clamp(q, -32768, 32767);
                    writeLE16(dst, static_cast<uint16_t>(static_cast<int16_t>(q)));
                    dst += 2;
                }
            }
            if (std::fwrite(outBytes.data(), 1, cnt * blockAlign, fp) != cnt * blockAlign) {
                std::fclose(fp);
                return false;
            }
            pos += cnt;
        }
    }

    std::fclose(fp);
    return true;
}

} // namespace soundtracker
