#pragma once

#include "audio_buffer.h"

#include <android/asset_manager.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

struct tsf;

namespace soundtracker {

struct SynthNoteSpec {
    int64_t startFrame = 0;
    int64_t durationFrames = 0;
    int midiNote = 60;
    float velocity = 0.85f;
    int slot = 0;  // 0 = Primary Keyboard, 1 = Secondary Keyboard
    int flags = 0; // Bitmask: 1=CRISP_RELEASE, 2=PORTATO_RELEASE, 4=LEGATO_OVERLAP, 8=FORTEPIANO_DUCK
};

struct InstrumentFxProfile {
    int bank = 0;
    int presetNumber = 0;
    float gainDb = 0.0f;
    float reverbSend = 0.28f;
    float chorusDepth = 0.0f;
    bool sustainPedal = false;
    float sustainDurationSec = 0.850f;
};

// Loads and caches the embedded studio `.sf2` SoundFont once in memory so both
// the low-latency live keyboard engine and offline WAV renderer share sample data.
bool ensureSoundFontLoadedFromAssets(
    AAssetManager* assetManager,
    const std::string& assetRelPath,
    std::string* errMsg = nullptr
);

bool ensureSoundFontLoadedFromFile(
    const std::string& filePath,
    std::string* errMsg = nullptr
);

bool isSoundFontReady();

// Sample-accurate real-time SoundFont2 synthesizer + Stereo Concert Reverb + Chorus + Metronome
class RealtimeMelodySynthEngine {
public:
    explicit RealtimeMelodySynthEngine(int sampleRate = 44100);
    ~RealtimeMelodySynthEngine();

    int sampleRate() const { return sampleRate_; }

    void setInstrument(int slot, const InstrumentFxProfile& profile);
    void setReverbLevel(float reverbWet);
    void setSustainPedal(int slot, bool sustainOn, float sustainDurationSec = 0.850f);

    // Live multi-touch keyboard voices (slot 0 -> MIDI ch 0, slot 1 -> MIDI ch 2)
    void liveNoteOn(int slot, int midiNote, float velocity);
    void liveNoteOff(int slot, int midiNote);
    void allNotesOff();

    // Score sequencer playback (MIDI channel 1) + sample-accurate woodblock metronome
    void setSequence(
        const std::vector<SynthNoteSpec>& notes,
        int64_t totalLengthFrames,
        bool loopEnabled
    );
    void setMetronome(
        bool enabled,
        float bpm,
        int numerator,
        int denominator = 4,
        const std::vector<int64_t>& pulseFrames = {},
        const std::vector<int>& pulseAccents = {}
    );
    void setTransportPlaying(bool playing, int64_t startFrame);
    void seekTransport(int64_t frame);

    int64_t currentTransportFrame() const {
        return transportFrame_.load(std::memory_order_relaxed);
    }
    bool isTransportPlaying() const {
        return transportPlaying_.load(std::memory_order_relaxed);
    }

    // Renders `numFrames` interleaved stereo float samples [-1.0, 1.0]
    void renderInterleavedStereo(float* outInterleavedLR, int numFrames);

private:
    struct Impl;
    int sampleRate_ = 44100;
    std::atomic<int64_t> transportFrame_{0};
    std::atomic<bool> transportPlaying_{false};
    std::unique_ptr<Impl> impl_;
};

// Offline studio WAV render of a melody sequence (with natural reverb tail decay & -0.2 dBTP protection)
bool renderMelodySequenceToWav(
    const InstrumentFxProfile& primaryProfile,
    const InstrumentFxProfile& secondaryProfile,
    const std::vector<SynthNoteSpec>& notes,
    int64_t sequenceDurationFrames,
    int outputSampleRate,
    const std::string& outWavPath,
    const ProgressCallback& onProgress = nullptr,
    std::string* errMsg = nullptr
);

} // namespace soundtracker
