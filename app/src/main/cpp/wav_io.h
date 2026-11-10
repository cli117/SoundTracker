#pragma once

#include "audio_buffer.h"
#include <string>

namespace soundtracker {

bool readWavFile(const std::string& path, PlanarAudioBuffer& outBuffer, std::string* errMsg = nullptr);

// Writes 16-bit TPDF-dithered WAV (or 32-bit IEEE float WAV when float32Format == true)
bool writeWavFile(
    const std::string& path,
    const PlanarAudioBuffer& buffer,
    bool float32Format = false,
    std::string* errMsg = nullptr
);

} // namespace soundtracker
