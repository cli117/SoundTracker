#pragma once

#include "audio_buffer.h"

namespace soundtracker {

// Studio Offline Polyphonic Pitch Shifter & Time Stretcher powered by Rubber Band Library v4.0 R3 (OptionEngineFiner):
// - Pass 1 (study): Global lookahead multi-resolution transient & harmonic profile analysis
// - Pass 2 (process): Multi-resolution phase-coherent time-scale modification (timeRatio = sourceBpm / targetBpm)
//   + high-order BQ sinc resampling (pitchScale = 2^(semitones / 12)) in a single unified pass
// - Supports:
//   (a) Pure Pitch Shifting without tempo change (semitones != 0, timeRatio == 1.0)
//   (b) Pure Time Stretching without pitch change (semitones == 0, timeRatio != 1.0, down to 1 BPM granularity)
//   (c) Combined Pitch Shifting + Time Stretching in one single pass (semitones != 0 && timeRatio != 1.0)
bool pitchShiftPolyphonic(
    const PlanarAudioBuffer& input,
    PlanarAudioBuffer& output,
    float semitones,
    double timeRatio,
    bool preserveTimbre,
    const ProgressCallback& onProgress = nullptr
);

} // namespace soundtracker
