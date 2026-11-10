#include "mp3_encoder.h"
#include "lame.h"

#include <algorithm>
#include <cstdio>
#include <vector>

namespace soundtracker {

bool encodeMp3File(
    const PlanarAudioBuffer& buffer,
    const std::string& outMp3Path,
    int bitrateKbps,
    const std::string& title,
    const ProgressCallback& onProgress,
    std::string* errMsg
) {
    if (buffer.numChannels() == 0 || buffer.numFrames() == 0) {
        if (errMsg) *errMsg = "Cannot encode empty buffer to MP3";
        return false;
    }

    FILE* fp = std::fopen(outMp3Path.c_str(), "wb");
    if (!fp) {
        if (errMsg) *errMsg = "Cannot open output MP3 file: " + outMp3Path;
        return false;
    }

    lame_global_flags* gfp = lame_init();
    if (!gfp) {
        std::fclose(fp);
        if (errMsg) *errMsg = "Failed to init LAME encoder";
        return false;
    }

    const int nCh = std::min(2, buffer.numChannels());
    lame_set_in_samplerate(gfp, buffer.sampleRate);
    lame_set_out_samplerate(gfp, (buffer.sampleRate == 48000 || buffer.sampleRate == 44100) ? buffer.sampleRate : 44100);
    lame_set_num_channels(gfp, nCh);
    lame_set_mode(gfp, (nCh == 1) ? MONO : JOINT_STEREO);
    lame_set_brate(gfp, std::clamp(bitrateKbps, 128, 320));
    lame_set_quality(gfp, 2); // High studio psychoacoustic quality
    lame_set_bWriteVbrTag(gfp, 0);

    if (!title.empty()) {
        id3tag_init(gfp);
        id3tag_v2_only(gfp);
        id3tag_set_title(gfp, title.c_str());
        id3tag_set_comment(gfp, "Processed with SoundTracker");
    }

    if (lame_init_params(gfp) < 0) {
        lame_close(gfp);
        std::fclose(fp);
        if (errMsg) *errMsg = "Invalid LAME parameters";
        return false;
    }

    constexpr size_t kChunkFrames = 32768;
    std::vector<unsigned char> mp3Buf(static_cast<size_t>(1.25 * kChunkFrames) + 7200);
    const size_t totalFrames = buffer.numFrames();
    const float* leftBase = buffer.channels[0].data();
    const float* rightBase = (nCh > 1) ? buffer.channels[1].data() : buffer.channels[0].data();

    size_t pos = 0;
    while (pos < totalFrames) {
        size_t cnt = std::min(kChunkFrames, totalFrames - pos);
        int written = lame_encode_buffer_ieee_float(
            gfp,
            leftBase + pos,
            rightBase + pos,
            static_cast<int>(cnt),
            mp3Buf.data(),
            static_cast<int>(mp3Buf.size())
        );
        if (written < 0) {
            lame_close(gfp);
            std::fclose(fp);
            if (errMsg) *errMsg = "LAME encode error";
            return false;
        }
        if (written > 0) {
            if (std::fwrite(mp3Buf.data(), 1, static_cast<size_t>(written), fp) != static_cast<size_t>(written)) {
                lame_close(gfp);
                std::fclose(fp);
                if (errMsg) *errMsg = "MP3 disk write failed";
                return false;
            }
        }
        pos += cnt;
        if (onProgress) {
            if (!onProgress(static_cast<float>(pos) / static_cast<float>(totalFrames))) {
                lame_close(gfp);
                std::fclose(fp);
                return false;
            }
        }
    }

    int flushed = lame_encode_flush(gfp, mp3Buf.data(), static_cast<int>(mp3Buf.size()));
    if (flushed > 0) {
        std::fwrite(mp3Buf.data(), 1, static_cast<size_t>(flushed), fp);
    }

    lame_close(gfp);
    std::fclose(fp);
    if (onProgress) onProgress(1.0f);
    return true;
}

} // namespace soundtracker
