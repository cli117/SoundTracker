#pragma once

#include "audio_buffer.h"
#include <string>

namespace soundtracker {

bool encodeMp3File(
    const PlanarAudioBuffer& buffer,
    const std::string& outMp3Path,
    int bitrateKbps = 320,
    const std::string& title = "",
    const ProgressCallback& onProgress = nullptr,
    std::string* errMsg = nullptr
);

} // namespace soundtracker
