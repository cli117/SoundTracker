#pragma once

#include "audio_buffer.h"
#include <string>

#ifdef __ANDROID__
#include <android/asset_manager.h>
#endif

namespace soundtracker {

enum class VocalSeparationMode : int {
    INST_HQ5_DIRECT = 0,   // Full-band 22.05kHz Direct Instrumental Synthesizer (Inst-HQ5)
    HTDEMUCS_FT = 1,       // Meta AI Hybrid Time+Frequency Transformer Specialist (HTDemucs-FT v4)
    HYBRID_ENSEMBLE = 2    // Dual-Engine Studio Fusion (Inst-HQ5 + Meta HTDemucs-FT v4)
};

struct SeparationModelPaths {
#ifdef __ANDROID__
    AAssetManager* assetManager = nullptr; // Zero-copy APK asset mmap when non-null
#endif
    std::string instHq5Path;
    std::string htdemucsFtPath;
    bool enableInstDenoise = false;
};

bool separateVocalsStudio(
    const SeparationModelPaths& models,
    VocalSeparationMode mode,
    const PlanarAudioBuffer& input,
    PlanarAudioBuffer& outAccompaniment,
    PlanarAudioBuffer* outVocals,
    int numThreads,
    const ProgressCallback& onProgress = nullptr,
    std::string* errMsg = nullptr
);

} // namespace soundtracker
