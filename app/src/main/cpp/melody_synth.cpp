#include "melody_synth.h"

#include "loudness_maximizer.h"
#include "wav_io.h"

#define TSF_IMPLEMENTATION
#include "third_party/tsf.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace soundtracker {

namespace {

constexpr float kTwoPi = 6.28318530717958647692f;

std::mutex gSfMutex;
tsf* gBaseSoundFont = nullptr;

// Helper to initialize 4 MIDI channels on a tsf instance so Dual Keyboards + Score Playback
// never reallocate channel arrays on the audio thread:
//   ch 0 = Live Keyboard Slot 0,  ch 1 = Sequencer Slot 0
//   ch 2 = Live Keyboard Slot 1,  ch 3 = Sequencer Slot 1
void applySlotToChannels(tsf* instance, int slot, const InstrumentFxProfile& prof) {
    if (!instance) return;
    const int baseCh = (slot == 1) ? 2 : 0;
    int presetIdx = tsf_get_presetindex(instance, prof.bank, prof.presetNumber);
    if (presetIdx < 0) {
        presetIdx = tsf_get_presetindex(instance, 0, prof.presetNumber);
    }
    if (presetIdx < 0 && tsf_get_presetcount(instance) > 0) {
        presetIdx = 0;
    }
    const float linGain = std::pow(10.0f, std::clamp(prof.gainDb, -24.0f, 18.0f) / 20.0f);
    for (int ch = baseCh; ch <= baseCh + 1; ++ch) {
        if (presetIdx >= 0) {
            tsf_channel_set_presetindex(instance, ch, presetIdx);
        }
        tsf_channel_set_pan(instance, ch, 0.5f);
        tsf_channel_set_volume(instance, ch, linGain);
        // Never use SF2 raw MIDI CC64 sustain (`tsf_channel_set_sustain`), because looped
        // wind/string/EP presets have SF2 `sustain > 0` and would hold indefinitely ("forever").
        // Instead we perform natural exponential damper-pedal release over `sustainDurationSec`.
        tsf_channel_set_sustain(instance, ch, 0);
    }
}

// Releases matching active voice(s) on `(channel, key)` either normally (`!sustainOn`)
// or with a smooth exponential damper-pedal decay lasting `sustainDurationSec` seconds (`sustainOn`),
// ensuring ALL instruments (both plucked/struck and looped wind/string/EP) decay naturally to silence.
void releaseChannelKeyWithCustomSustain(
    tsf* f,
    int channel,
    int key,
    bool sustainOn,
    float sustainDurationSec
) {
    if (!f || !f->voices) return;
    struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
    struct tsf_voice *vMatchFirst = nullptr, *vMatchLast = nullptr;
    for (; v != vEnd; ++v) {
        if (v->playingPreset == -1 || v->playingChannel != channel || v->playingKey != key ||
            v->ampenv.segment >= TSF_SEGMENT_RELEASE) {
            continue;
        }
        if (!vMatchFirst || v->playIndex < vMatchFirst->playIndex) {
            vMatchFirst = vMatchLast = v;
        } else if (v->playIndex == vMatchFirst->playIndex) {
            vMatchLast = v;
        }
    }
    if (!vMatchFirst) return;

    const float clampedSustainSec = std::clamp(sustainDurationSec, 0.020f, 15.0f);
    for (v = vMatchFirst; v <= vMatchLast; ++v) {
        if (v != vMatchFirst && v != vMatchLast &&
            (v->playIndex != vMatchFirst->playIndex || v->playingPreset == -1 ||
             v->playingChannel != channel || v->playingKey != key ||
             v->ampenv.segment >= TSF_SEGMENT_RELEASE)) {
            continue;
        }
        if (!sustainOn) {
            tsf_voice_end(f, v);
        } else {
            const int repeats = (f->maxVoiceNum ? 2 : 1);
            for (int r = 0; r < repeats; ++r) {
                if (v->ampenv.segment < TSF_SEGMENT_HOLD) {
                    // Key was tapped very briefly during slow attack (e.g., Strings / Flute):
                    // let attack finish smoothly to peak, then decay exponentially to 0 over `clampedSustainSec`.
                    v->ampenv.parameters.hold = 0.0f;
                    v->ampenv.parameters.sustain = 0.0f;
                    v->ampenv.parameters.decay = clampedSustainSec;
                    v->ampenv.parameters.release = 0.025f;
                } else {
                    v->ampenv.parameters.release = clampedSustainSec;
                    v->modenv.parameters.release = std::max(v->modenv.parameters.release, clampedSustainSec);
                    tsf_voice_envelope_nextsegment(&v->ampenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
                    tsf_voice_envelope_nextsegment(&v->modenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
                }
            }
        }
    }
}

// Reclaims any voices whose sustain-to-zero decay has reached silence (< -80 dB),
// and optionally dampens long sustain tails when the user switches sustain pedal OFF.
void sweepSilentVoices(tsf* f) {
    if (!f || !f->voices) return;
    struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
    for (; v != vEnd; ++v) {
        if (v->playingPreset != -1 &&
            v->ampenv.segment == TSF_SEGMENT_SUSTAIN &&
            v->ampenv.level <= 0.00012f &&
            v->ampenv.parameters.sustain <= 0.00012f) {
            tsf_voice_kill(v);
        }
    }
}

void dampSustainingVoicesOnChannel(tsf* f, int channel) {
    if (!f || !f->voices) return;
    const int fastSamples = std::max(64, static_cast<int>(0.16f * f->outSampleRate));
    struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
    for (; v != vEnd; ++v) {
        if (v->playingPreset == -1 || v->playingChannel != channel) continue;
        if (v->ampenv.segment == TSF_SEGMENT_RELEASE && v->ampenv.samplesUntilNextSegment > fastSamples) {
            v->ampenv.parameters.release = 0.16f;
            tsf_voice_envelope_nextsegment(&v->ampenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
            if (v->region && v->region->loop_mode == TSF_LOOPMODE_SUSTAIN) {
                v->loopEnd = v->loopStart;
            }
        }
    }
}

void configureTsfInstance(
    tsf* instance,
    int sampleRate,
    const InstrumentFxProfile& slot0Prof,
    const InstrumentFxProfile& slot1Prof
) {
    if (!instance) return;
    tsf_set_max_voices(instance, 128);
    tsf_set_output(instance, TSF_STEREO_INTERLEAVED, sampleRate, 0.0f);
    applySlotToChannels(instance, 0, slot0Prof);
    applySlotToChannels(instance, 1, slot1Prof);
}

// ============================================================================
// Studio Stereo Concert Reverb + Quadrature Chorus + Safety Limiter
// ============================================================================

class CombFilter {
public:
    void init(int delaySamples) {
        buf_.assign(std::max(16, delaySamples), 0.0f);
        idx_ = 0;
        filterStore_ = 0.0f;
    }

    void clear() {
        std::fill(buf_.begin(), buf_.end(), 0.0f);
        filterStore_ = 0.0f;
        idx_ = 0;
    }

    inline float process(float input, float feedback, float damp) {
        float output = buf_[idx_];
        filterStore_ = output * (1.0f - damp) + filterStore_ * damp;
        // Flush denormals
        if (std::fabs(filterStore_) < 1e-18f) filterStore_ = 0.0f;
        buf_[idx_] = input + filterStore_ * feedback;
        if (++idx_ >= static_cast<int>(buf_.size())) idx_ = 0;
        return output;
    }

private:
    std::vector<float> buf_;
    int idx_ = 0;
    float filterStore_ = 0.0f;
};

class AllpassFilter {
public:
    void init(int delaySamples) {
        buf_.assign(std::max(8, delaySamples), 0.0f);
        idx_ = 0;
    }

    void clear() {
        std::fill(buf_.begin(), buf_.end(), 0.0f);
        idx_ = 0;
    }

    inline float process(float input) {
        constexpr float feedback = 0.5f;
        float bufOut = buf_[idx_];
        if (std::fabs(bufOut) < 1e-18f) bufOut = 0.0f;
        float output = -input + bufOut;
        buf_[idx_] = input + bufOut * feedback;
        if (++idx_ >= static_cast<int>(buf_.size())) idx_ = 0;
        return output;
    }

private:
    std::vector<float> buf_;
    int idx_ = 0;
};

class StudioStereoFxRack {
public:
    explicit StudioStereoFxRack(int sampleRate) : sampleRate_(sampleRate) {
        const double scale = static_cast<double>(sampleRate) / 44100.0;
        constexpr int kCombTunings[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        constexpr int kAllpassTunings[4] = {556, 441, 341, 225};
        constexpr int kStereoSpread = 23;

        for (int i = 0; i < 8; ++i) {
            combL_[i].init(static_cast<int>(std::round(kCombTunings[i] * scale)));
            combR_[i].init(static_cast<int>(std::round((kCombTunings[i] + kStereoSpread) * scale)));
        }
        for (int i = 0; i < 4; ++i) {
            allpassL_[i].init(static_cast<int>(std::round(kAllpassTunings[i] * scale)));
            allpassR_[i].init(static_cast<int>(std::round((kAllpassTunings[i] + kStereoSpread) * scale)));
        }

        // 18 ms studio pre-delay keeps note attacks crisp and upfront
        int preDelayLen = std::max(16, static_cast<int>(std::round(0.018 * sampleRate)));
        preDelayBuf_.assign(preDelayLen, 0.0f);
        preDelayIdx_ = 0;

        // 130 Hz high-pass on reverb send prevents low piano/bass mud
        float rc = 1.0f / (kTwoPi * 130.0f);
        float dt = 1.0f / static_cast<float>(sampleRate);
        hpAlpha_ = rc / (rc + dt);

        // Stereo chorus delay line (~25 ms max)
        chorusMaxLen_ = std::max(64, static_cast<int>(std::ceil(0.025 * sampleRate)));
        chorusBufL_.assign(chorusMaxLen_, 0.0f);
        chorusBufR_.assign(chorusMaxLen_, 0.0f);
        chorusWriteIdx_ = 0;
        chorusPhase_ = 0.0f;

        // Smooth limiter release (~80 ms)
        limiterReleaseCoeff_ = std::exp(-1.0f / (0.080f * static_cast<float>(sampleRate)));
    }

    void reset() {
        for (int i = 0; i < 8; ++i) {
            combL_[i].clear();
            combR_[i].clear();
        }
        for (int i = 0; i < 4; ++i) {
            allpassL_[i].clear();
            allpassR_[i].clear();
        }
        std::fill(preDelayBuf_.begin(), preDelayBuf_.end(), 0.0f);
        std::fill(chorusBufL_.begin(), chorusBufL_.end(), 0.0f);
        std::fill(chorusBufR_.begin(), chorusBufR_.end(), 0.0f);
        hpPrevIn_ = 0.0f;
        hpPrevOut_ = 0.0f;
        limiterGain_ = 1.0f;
    }

    void processInPlace(
        float* interleavedLR,
        int numFrames,
        float reverbWet,
        float chorusDepth,
        bool applySafetyLimiter
    ) {
        const float wet = std::clamp(reverbWet, 0.0f, 0.75f);
        const float chMix = std::clamp(chorusDepth, 0.0f, 0.50f);
        const float lfoInc = kTwoPi * 0.82f / static_cast<float>(sampleRate_);
        const float baseDelaySamples = 0.0075f * static_cast<float>(sampleRate_);
        const float modDepthSamples = 0.0018f * static_cast<float>(sampleRate_);

        constexpr float kRoomFeedback = 0.83f;
        constexpr float kDamp = 0.34f;
        constexpr float kInputGain = 0.018f;
        constexpr float kCeiling = 0.977f; // -0.2 dBFS

        for (int i = 0; i < numFrames; ++i) {
            float L = interleavedLR[i * 2];
            float R = interleavedLR[i * 2 + 1];

            // 1. Optional Quadrature Stereo Chorus (velvety width for EP / Strings / Synth)
            if (chMix > 0.001f) {
                chorusBufL_[chorusWriteIdx_] = L;
                chorusBufR_[chorusWriteIdx_] = R;

                float dL = baseDelaySamples + std::sin(chorusPhase_) * modDepthSamples;
                float dR = baseDelaySamples + std::cos(chorusPhase_) * modDepthSamples;

                float cL = readFractional(chorusBufL_, chorusWriteIdx_, dL);
                float cR = readFractional(chorusBufR_, chorusWriteIdx_, dR);

                L = L * (1.0f - chMix * 0.35f) + cL * chMix;
                R = R * (1.0f - chMix * 0.35f) + cR * chMix;

                if (++chorusWriteIdx_ >= chorusMaxLen_) chorusWriteIdx_ = 0;
                chorusPhase_ += lfoInc;
                if (chorusPhase_ >= kTwoPi) chorusPhase_ -= kTwoPi;
            }

            // 2. Studio Concert Hall / Warm Room Reverb with 18ms Pre-Delay & Low-Cut
            if (wet > 0.001f) {
                float monoIn = (L + R) * 0.5f;
                float hpOut = hpAlpha_ * (hpPrevOut_ + monoIn - hpPrevIn_);
                hpPrevIn_ = monoIn;
                hpPrevOut_ = hpOut;

                float delayedIn = preDelayBuf_[preDelayIdx_];
                preDelayBuf_[preDelayIdx_] = hpOut * kInputGain;
                if (++preDelayIdx_ >= static_cast<int>(preDelayBuf_.size())) preDelayIdx_ = 0;

                float revL = 0.0f;
                float revR = 0.0f;
                for (int c = 0; c < 8; ++c) {
                    revL += combL_[c].process(delayedIn, kRoomFeedback, kDamp);
                    revR += combR_[c].process(delayedIn, kRoomFeedback, kDamp);
                }
                for (int a = 0; a < 4; ++a) {
                    revL = allpassL_[a].process(revL);
                    revR = allpassR_[a].process(revR);
                }

                // Cross-blend stereo width (wet1 / wet2)
                const float wet1 = wet * 1.15f;
                const float wet2 = wet * 0.22f;
                L += revL * wet1 + revR * wet2;
                R += revR * wet1 + revL * wet2;
            }

            // 3. Zero-distortion transparent ceiling protection for realtime polyphonic chords
            if (applySafetyLimiter) {
                float peak = std::max(std::fabs(L), std::fabs(R));
                float targetG = (peak > kCeiling) ? (kCeiling / peak) : 1.0f;
                if (targetG < limiterGain_) {
                    limiterGain_ = targetG; // instant attack on chord peaks
                } else {
                    limiterGain_ = limiterReleaseCoeff_ * limiterGain_ + (1.0f - limiterReleaseCoeff_) * targetG;
                }
                L *= limiterGain_;
                R *= limiterGain_;
            }

            interleavedLR[i * 2] = L;
            interleavedLR[i * 2 + 1] = R;
        }
    }

private:
    static inline float readFractional(const std::vector<float>& buf, int writeIdx, float delaySamples) {
        const int sz = static_cast<int>(buf.size());
        float readPos = static_cast<float>(writeIdx) - delaySamples;
        while (readPos < 0.0f) readPos += static_cast<float>(sz);
        int i0 = static_cast<int>(readPos) % sz;
        int i1 = (i0 + 1) % sz;
        float frac = readPos - std::floor(readPos);
        return buf[i0] + frac * (buf[i1] - buf[i0]);
    }

    int sampleRate_;
    CombFilter combL_[8];
    CombFilter combR_[8];
    AllpassFilter allpassL_[4];
    AllpassFilter allpassR_[4];
    std::vector<float> preDelayBuf_;
    int preDelayIdx_ = 0;
    float hpAlpha_ = 0.98f;
    float hpPrevIn_ = 0.0f;
    float hpPrevOut_ = 0.0f;

    int chorusMaxLen_ = 1024;
    std::vector<float> chorusBufL_;
    std::vector<float> chorusBufR_;
    int chorusWriteIdx_ = 0;
    float chorusPhase_ = 0.0f;

    float limiterGain_ = 1.0f;
    float limiterReleaseCoeff_ = 0.999f;
};

} // namespace

// ============================================================================
// Shared SoundFont Asset Loader
// ============================================================================

bool ensureSoundFontLoadedFromAssets(
    AAssetManager* assetManager,
    const std::string& assetRelPath,
    std::string* errMsg
) {
    std::lock_guard<std::mutex> lock(gSfMutex);
    if (gBaseSoundFont != nullptr) return true;
    if (!assetManager) {
        if (errMsg) *errMsg = "AssetManager is null";
        return false;
    }

    AAsset* asset = AAssetManager_open(assetManager, assetRelPath.c_str(), AASSET_MODE_BUFFER);
    if (!asset) {
        if (errMsg) *errMsg = "Missing soundfont asset: " + assetRelPath;
        return false;
    }

    const off_t len = AAsset_getLength(asset);
    const void* buf = AAsset_getBuffer(asset);
    tsf* loaded = nullptr;
    if (buf != nullptr && len > 0) {
        loaded = tsf_load_memory(buf, static_cast<int>(len));
    } else if (len > 0) {
        std::vector<uint8_t> copyBuf(static_cast<size_t>(len));
        int readBytes = AAsset_read(asset, copyBuf.data(), copyBuf.size());
        if (readBytes == static_cast<int>(len)) {
            loaded = tsf_load_memory(copyBuf.data(), readBytes);
        }
    }
    AAsset_close(asset);

    if (!loaded) {
        if (errMsg) *errMsg = "Failed to parse SoundFont2 bank";
        return false;
    }

    gBaseSoundFont = loaded;
    return true;
}

bool ensureSoundFontLoadedFromFile(
    const std::string& filePath,
    std::string* errMsg
) {
    std::lock_guard<std::mutex> lock(gSfMutex);
    if (gBaseSoundFont != nullptr) return true;
    tsf* loaded = tsf_load_filename(filePath.c_str());
    if (!loaded) {
        if (errMsg) *errMsg = "Failed to load SoundFont file: " + filePath;
        return false;
    }
    gBaseSoundFont = loaded;
    return true;
}

bool isSoundFontReady() {
    std::lock_guard<std::mutex> lock(gSfMutex);
    return gBaseSoundFont != nullptr;
}

static tsf* cloneSharedSoundFontInstance() {
    std::lock_guard<std::mutex> lock(gSfMutex);
    if (!gBaseSoundFont) return nullptr;
    return tsf_copy(gBaseSoundFont);
}

// ============================================================================
// Expressive Note Flags & Voice Articulation Helpers
// ============================================================================

constexpr int kSynthFlagCrispRelease   = 1 << 0; // Staccato / Staccatissimo / Marcato
constexpr int kSynthFlagPortatoRelease = 1 << 1; // Portato / Phrase-end singing release
constexpr int kSynthFlagLegatoOverlap  = 1 << 2; // Slur interior smooth overlap
constexpr int kSynthFlagFortepianoDuck = 1 << 3; // `fp` strong attack -> soft sustain

struct ActiveSeqNote {
    int channel = 1;
    int midiNote = 60;
    int64_t offFrame = 0;
    int flags = 0;
    int64_t fpDuckStartFrame = 0;
    float fpDuckRemainingDb = 0.0f;
};

static void triggerExpressiveChannelNoteOn(
    tsf* f,
    int channel,
    int key,
    float rawVelocity
) {
    if (!f) return;
    const float midiVel = std::clamp(rawVelocity, 0.05f, 1.0f);
    const unsigned int beforePlayIdx = f->voicePlayIndex;
    tsf_channel_note_on(f, channel, key, midiVel);
    if (rawVelocity > 1.002f && f->voices) {
        const float extraDb = 20.0f * std::log10(std::min(rawVelocity, 1.26f));
        struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
        for (; v != vEnd; ++v) {
            if (v->playingPreset != -1 &&
                v->playIndex >= beforePlayIdx &&
                v->playingChannel == channel &&
                v->playingKey == key) {
                v->noteGainDB += extraDb;
            }
        }
    }
}

static void applyFortepianoVoiceAttenuation(
    tsf* f,
    int channel,
    int key,
    float deltaDb
) {
    if (!f || !f->voices || deltaDb <= 0.0f) return;
    struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
    for (; v != vEnd; ++v) {
        if (v->playingPreset != -1 &&
            v->playingChannel == channel &&
            v->playingKey == key &&
            v->ampenv.segment < TSF_SEGMENT_RELEASE) {
            v->noteGainDB -= deltaDb;
        }
    }
}

static void releaseExpressiveSeqNote(
    tsf* f,
    int channel,
    int key,
    int flags,
    const InstrumentFxProfile& prof
) {
    if ((flags & kSynthFlagCrispRelease) != 0) {
        // Staccato / Staccatissimo / Marcato always damp crisply (~45ms) even when sustain pedal is ON
        releaseChannelKeyWithCustomSustain(f, channel, key, /*sustainOn=*/true, /*sustainDurationSec=*/0.045f);
    } else if ((flags & kSynthFlagPortatoRelease) != 0) {
        const float portatoSec = prof.sustainPedal
            ? std::min(prof.sustainDurationSec, 0.220f)
            : 0.120f;
        releaseChannelKeyWithCustomSustain(f, channel, key, /*sustainOn=*/true, portatoSec);
    } else {
        releaseChannelKeyWithCustomSustain(f, channel, key, prof.sustainPedal, prof.sustainDurationSec);
    }
}

// ============================================================================
// RealtimeMelodySynthEngine Implementation
// ============================================================================

struct RealtimeMelodySynthEngine::Impl {
    std::mutex mutex;
    tsf* synth = nullptr;
    InstrumentFxProfile slotProfiles[2];
    StudioStereoFxRack fxRack;

    std::vector<SynthNoteSpec> seqNotes;
    std::vector<ActiveSeqNote> activeSeqNotes;
    size_t nextSeqIndex = 0;
    int64_t loopLengthFrames = 0;
    bool loopEnabled = false;

    // Sample-accurate synthesized woodblock metronome (supports arbitrary N/D meters & Tempo Maps!)
    bool metronomeEnabled = false;
    float bpm = 120.0f;
    int timeSigNumerator = 4;
    int timeSigDenominator = 4;
    std::vector<int64_t> metroPulseFrames;
    std::vector<int> metroPulseAccents;
    size_t nextMetroIndex = 0;
    int clickPosSamples = -1;
    int clickTotalSamples = 0;
    float clickFreqHz = 880.0f;
    float clickGain = 0.0f;

    explicit Impl(int sr) : fxRack(sr) {
        synth = cloneSharedSoundFontInstance();
        if (synth) {
            configureTsfInstance(synth, sr, slotProfiles[0], slotProfiles[1]);
        }
    }

    ~Impl() {
        if (synth) {
            tsf_close(synth);
            synth = nullptr;
        }
    }

    void ensureSynthInitialized(int sr) {
        if (!synth) {
            synth = cloneSharedSoundFontInstance();
            if (synth) {
                configureTsfInstance(synth, sr, slotProfiles[0], slotProfiles[1]);
            }
        }
    }

    void stopAllSequenceNotes() {
        if (synth) {
            tsf_channel_note_off_all(synth, 1);
            tsf_channel_note_off_all(synth, 3);
        }
        activeSeqNotes.clear();
    }

    void reindexMetronomeForFrame(int64_t targetFrame) {
        nextMetroIndex = 0;
        while (nextMetroIndex < metroPulseFrames.size() &&
               metroPulseFrames[nextMetroIndex] < targetFrame) {
            ++nextMetroIndex;
        }
    }

    void reindexForFrame(int64_t targetFrame) {
        stopAllSequenceNotes();
        nextSeqIndex = 0;
        while (nextSeqIndex < seqNotes.size() && seqNotes[nextSeqIndex].startFrame < targetFrame) {
            ++nextSeqIndex;
        }
        reindexMetronomeForFrame(targetFrame);
    }
};

RealtimeMelodySynthEngine::RealtimeMelodySynthEngine(int sampleRate)
    : sampleRate_(std::max(8000, sampleRate)),
      impl_(std::make_unique<Impl>(std::max(8000, sampleRate))) {}

RealtimeMelodySynthEngine::~RealtimeMelodySynthEngine() = default;

void RealtimeMelodySynthEngine::setInstrument(int slot, const InstrumentFxProfile& profile) {
    const int idx = (slot == 1) ? 1 : 0;
    const int baseCh = (idx == 1) ? 2 : 0;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->slotProfiles[idx] = profile;
    impl_->ensureSynthInitialized(sampleRate_);
    if (impl_->synth) {
        tsf_channel_note_off_all(impl_->synth, baseCh);
        tsf_channel_note_off_all(impl_->synth, baseCh + 1);
        applySlotToChannels(impl_->synth, idx, profile);
    }
}

void RealtimeMelodySynthEngine::setReverbLevel(float reverbWet) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->slotProfiles[0].reverbSend = std::clamp(reverbWet, 0.0f, 0.70f);
}

void RealtimeMelodySynthEngine::setSustainPedal(int slot, bool sustainOn, float sustainDurationSec) {
    const int idx = (slot == 1) ? 1 : 0;
    const int baseCh = (idx == 1) ? 2 : 0;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->slotProfiles[idx].sustainPedal = sustainOn;
    impl_->slotProfiles[idx].sustainDurationSec = std::clamp(sustainDurationSec, 0.020f, 15.0f);
    impl_->ensureSynthInitialized(sampleRate_);
    if (impl_->synth) {
        tsf_channel_set_sustain(impl_->synth, baseCh, 0);
        tsf_channel_set_sustain(impl_->synth, baseCh + 1, 0);
        if (!sustainOn) {
            dampSustainingVoicesOnChannel(impl_->synth, baseCh);
            dampSustainingVoicesOnChannel(impl_->synth, baseCh + 1);
        }
    }
}

void RealtimeMelodySynthEngine::liveNoteOn(int slot, int midiNote, float velocity) {
    const int idx = (slot == 1) ? 1 : 0;
    const int liveCh = (idx == 1) ? 2 : 0;
    const int key = std::clamp(midiNote, 0, 127);
    const float vel = std::clamp(velocity, 0.05f, 1.0f);
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->ensureSynthInitialized(sampleRate_);
    if (impl_->synth) {
        releaseChannelKeyWithCustomSustain(
            impl_->synth,
            liveCh,
            key,
            impl_->slotProfiles[idx].sustainPedal,
            impl_->slotProfiles[idx].sustainDurationSec
        );
        tsf_channel_note_on(impl_->synth, liveCh, key, vel);
    }
}

void RealtimeMelodySynthEngine::liveNoteOff(int slot, int midiNote) {
    const int idx = (slot == 1) ? 1 : 0;
    const int liveCh = (idx == 1) ? 2 : 0;
    const int key = std::clamp(midiNote, 0, 127);
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (impl_->synth) {
        releaseChannelKeyWithCustomSustain(
            impl_->synth,
            liveCh,
            key,
            impl_->slotProfiles[idx].sustainPedal,
            impl_->slotProfiles[idx].sustainDurationSec
        );
    }
}

void RealtimeMelodySynthEngine::allNotesOff() {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (impl_->synth) {
        tsf_note_off_all(impl_->synth);
    }
    impl_->activeSeqNotes.clear();
}

void RealtimeMelodySynthEngine::setSequence(
    const std::vector<SynthNoteSpec>& notes,
    int64_t totalLengthFrames,
    bool loopEnabled
) {
    std::vector<SynthNoteSpec> sorted = notes;
    std::sort(sorted.begin(), sorted.end(), [](const SynthNoteSpec& a, const SynthNoteSpec& b) {
        return a.startFrame < b.startFrame;
    });

    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->seqNotes = std::move(sorted);
    impl_->loopLengthFrames = std::max<int64_t>(0, totalLengthFrames);
    impl_->loopEnabled = loopEnabled;

    const int64_t curFrame = transportFrame_.load(std::memory_order_relaxed);
    impl_->nextSeqIndex = 0;
    while (impl_->nextSeqIndex < impl_->seqNotes.size() &&
           impl_->seqNotes[impl_->nextSeqIndex].startFrame < curFrame) {
        ++impl_->nextSeqIndex;
    }
}

void RealtimeMelodySynthEngine::setMetronome(
    bool enabled,
    float bpm,
    int numerator,
    int denominator,
    const std::vector<int64_t>& pulseFrames,
    const std::vector<int>& pulseAccents
) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->metronomeEnabled = enabled;
    impl_->bpm = std::clamp(bpm, 30.0f, 260.0f);
    impl_->timeSigNumerator = std::clamp(numerator, 1, 16);
    impl_->timeSigDenominator = (denominator == 2 || denominator == 8 || denominator == 16) ? denominator : 4;
    impl_->metroPulseFrames = pulseFrames;
    impl_->metroPulseAccents = pulseAccents;
    const int64_t curFrame = transportFrame_.load(std::memory_order_relaxed);
    impl_->reindexMetronomeForFrame(curFrame);
    if (!enabled) {
        impl_->clickPosSamples = -1;
    }
}

void RealtimeMelodySynthEngine::setTransportPlaying(bool playing, int64_t startFrame) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->ensureSynthInitialized(sampleRate_);
    const int64_t clampedStart = std::max<int64_t>(0, startFrame);
    transportFrame_.store(clampedStart, std::memory_order_relaxed);
    transportPlaying_.store(playing, std::memory_order_relaxed);
    impl_->clickPosSamples = -1;
    impl_->reindexForFrame(clampedStart);
}

void RealtimeMelodySynthEngine::seekTransport(int64_t frame) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    const int64_t clamped = std::max<int64_t>(0, frame);
    transportFrame_.store(clamped, std::memory_order_relaxed);
    impl_->clickPosSamples = -1;
    impl_->reindexForFrame(clamped);
}

void RealtimeMelodySynthEngine::renderInterleavedStereo(float* outInterleavedLR, int numFrames) {
    if (!outInterleavedLR || numFrames <= 0) return;

    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->ensureSynthInitialized(sampleRate_);
    if (!impl_->synth) {
        std::memset(outInterleavedLR, 0, sizeof(float) * static_cast<size_t>(numFrames) * 2);
        return;
    }

    constexpr int kSubChunk = 64;
    int offset = 0;
    const int clickDuration = std::max(64, static_cast<int>(0.022f * sampleRate_)); // 22ms woodblock

    while (offset < numFrames) {
        const int step = std::min(kSubChunk, numFrames - offset);
        float* chunkPtr = outInterleavedLR + offset * 2;

        if (transportPlaying_.load(std::memory_order_relaxed)) {
            int64_t curFrame = transportFrame_.load(std::memory_order_relaxed);
            const int64_t endFrame = curFrame + step;

            // 1. Trigger note-offs & `fp` (fortepiano) post-attack ducking inside [curFrame, endFrame)
            for (auto it = impl_->activeSeqNotes.begin(); it != impl_->activeSeqNotes.end();) {
                if (it->offFrame <= endFrame) {
                    const int slotIdx = (it->channel == 3) ? 1 : 0;
                    releaseExpressiveSeqNote(
                        impl_->synth,
                        it->channel,
                        it->midiNote,
                        it->flags,
                        impl_->slotProfiles[slotIdx]
                    );
                    it = impl_->activeSeqNotes.erase(it);
                } else {
                    if (it->fpDuckRemainingDb > 0.01f && endFrame > it->fpDuckStartFrame) {
                        const float stepDb = std::min(
                            it->fpDuckRemainingDb,
                            160.0f * (static_cast<float>(step) / static_cast<float>(sampleRate_))
                        );
                        applyFortepianoVoiceAttenuation(impl_->synth, it->channel, it->midiNote, stepDb);
                        it->fpDuckRemainingDb -= stepDb;
                    }
                    ++it;
                }
            }

            // 2. Trigger note-ons starting inside [curFrame, endFrame)
            while (impl_->nextSeqIndex < impl_->seqNotes.size()) {
                const SynthNoteSpec& ev = impl_->seqNotes[impl_->nextSeqIndex];
                if (ev.startFrame >= endFrame) break;
                if (ev.startFrame >= curFrame) {
                    const int slotIdx = (ev.slot == 1) ? 1 : 0;
                    const int seqCh = (slotIdx == 1) ? 3 : 1;
                    const int key = std::clamp(ev.midiNote, 0, 127);
                    const float vel = std::clamp(ev.velocity, 0.05f, 1.25f);
                    releaseChannelKeyWithCustomSustain(
                        impl_->synth,
                        seqCh,
                        key,
                        impl_->slotProfiles[slotIdx].sustainPedal,
                        impl_->slotProfiles[slotIdx].sustainDurationSec
                    );
                    triggerExpressiveChannelNoteOn(impl_->synth, seqCh, key, vel);
                    const bool isFp = (ev.flags & kSynthFlagFortepianoDuck) != 0;
                    impl_->activeSeqNotes.push_back(ActiveSeqNote{
                        seqCh,
                        key,
                        ev.startFrame + std::max<int64_t>(256, ev.durationFrames),
                        ev.flags,
                        ev.startFrame + static_cast<int64_t>(0.065 * sampleRate_),
                        isFp ? 9.5f : 0.0f
                    });
                }
                ++impl_->nextSeqIndex;
            }

            // 3. Check sample-accurate metronome beat boundary (follows full Tempo Map: rit., accel., Fermata, N/D!)
            if (impl_->metronomeEnabled) {
                if (!impl_->metroPulseFrames.empty()) {
                    bool triggeredFromSchedule = false;
                    while (impl_->nextMetroIndex < impl_->metroPulseFrames.size()) {
                        const int64_t pf = impl_->metroPulseFrames[impl_->nextMetroIndex];
                        if (pf >= endFrame) break;
                        if (pf >= curFrame) {
                            const int accent = (impl_->nextMetroIndex < impl_->metroPulseAccents.size())
                                ? impl_->metroPulseAccents[impl_->nextMetroIndex]
                                : 0;
                            impl_->clickPosSamples = 0;
                            impl_->clickTotalSamples = clickDuration;
                            impl_->clickFreqHz = (accent >= 2) ? 1240.0f : ((accent == 1) ? 1020.0f : 860.0f);
                            impl_->clickGain = (accent >= 2) ? 0.34f : ((accent == 1) ? 0.26f : 0.19f);
                            triggeredFromSchedule = true;
                        }
                        ++impl_->nextMetroIndex;
                    }
                    // Fallback beyond pre-allocated bars when live-recording continuously
                    const int64_t lastScheduledFrame = impl_->metroPulseFrames.back();
                    if (!triggeredFromSchedule && curFrame > lastScheduledFrame) {
                        const double quarterFrames = (60.0 / static_cast<double>(impl_->bpm)) * sampleRate_;
                        const double framesPerPulse =
                            quarterFrames * (4.0 / static_cast<double>(impl_->timeSigDenominator));
                        if (framesPerPulse > 80.0) {
                            const double rel0 = static_cast<double>(curFrame - lastScheduledFrame);
                            const double rel1 = static_cast<double>(endFrame - 1 - lastScheduledFrame);
                            const int64_t pBefore = static_cast<int64_t>(std::floor(rel0 / framesPerPulse));
                            const int64_t pAfter = static_cast<int64_t>(std::floor(rel1 / framesPerPulse));
                            if (pAfter > pBefore) {
                                const size_t basePulses = impl_->metroPulseFrames.size() - 1;
                                const int pulseInBar =
                                    static_cast<int>((basePulses + static_cast<size_t>(pAfter)) % impl_->timeSigNumerator);
                                const bool isDownbeat = (pulseInBar == 0);
                                const bool isCompoundSubAccent = !isDownbeat &&
                                    impl_->timeSigDenominator == 8 &&
                                    impl_->timeSigNumerator >= 6 &&
                                    (impl_->timeSigNumerator % 3 == 0) &&
                                    (pulseInBar % 3 == 0);
                                impl_->clickPosSamples = 0;
                                impl_->clickTotalSamples = clickDuration;
                                impl_->clickFreqHz = isDownbeat ? 1240.0f : (isCompoundSubAccent ? 1020.0f : 860.0f);
                                impl_->clickGain = isDownbeat ? 0.34f : (isCompoundSubAccent ? 0.26f : 0.19f);
                            }
                        }
                    }
                } else {
                    const double quarterFrames = (60.0 / static_cast<double>(impl_->bpm)) * sampleRate_;
                    const double framesPerPulse =
                        quarterFrames * (4.0 / static_cast<double>(impl_->timeSigDenominator));
                    if (framesPerPulse > 80.0) {
                        const int64_t pulseBefore = static_cast<int64_t>(std::floor(curFrame / framesPerPulse));
                        const int64_t pulseAfter = static_cast<int64_t>(std::floor((endFrame - 1) / framesPerPulse));
                        if (curFrame == 0 || pulseAfter > pulseBefore) {
                            const int64_t pulseNum = (curFrame == 0) ? 0 : pulseAfter;
                            const int pulseInBar = static_cast<int>(pulseNum % impl_->timeSigNumerator);
                            const bool isDownbeat = (pulseInBar == 0);
                            const bool isCompoundSubAccent = !isDownbeat &&
                                impl_->timeSigDenominator == 8 &&
                                impl_->timeSigNumerator >= 6 &&
                                (impl_->timeSigNumerator % 3 == 0) &&
                                (pulseInBar % 3 == 0);
                            impl_->clickPosSamples = 0;
                            impl_->clickTotalSamples = clickDuration;
                            impl_->clickFreqHz = isDownbeat ? 1240.0f : (isCompoundSubAccent ? 1020.0f : 860.0f);
                            impl_->clickGain = isDownbeat ? 0.34f : (isCompoundSubAccent ? 0.26f : 0.19f);
                        }
                    }
                }
            }

            // Advance transport & loop/end handling
            int64_t nextTransport = endFrame;
            if (impl_->loopLengthFrames > 0 && nextTransport >= impl_->loopLengthFrames) {
                if (impl_->loopEnabled) {
                    nextTransport = 0;
                    impl_->reindexForFrame(0);
                } else if (!impl_->metronomeEnabled) {
                    // Stop cleanly at end of score when not live-recording with metronome
                    transportPlaying_.store(false, std::memory_order_relaxed);
                    nextTransport = 0;
                    impl_->reindexForFrame(0);
                }
            }
            transportFrame_.store(nextTransport, std::memory_order_relaxed);
        }

        // Render polyphonic SoundFont voices for this sub-chunk
        tsf_render_float(impl_->synth, chunkPtr, step, 0);
        sweepSilentVoices(impl_->synth);

        // Mix crisp woodblock metronome click if active (post-dry, pre-limiter)
        if (impl_->clickPosSamples >= 0 && impl_->clickPosSamples < impl_->clickTotalSamples) {
            for (int i = 0; i < step && impl_->clickPosSamples < impl_->clickTotalSamples; ++i) {
                const float t = static_cast<float>(impl_->clickPosSamples) / static_cast<float>(sampleRate_);
                const float env = std::exp(-t * 175.0f);
                const float clickSample = std::sin(kTwoPi * impl_->clickFreqHz * t) * env * impl_->clickGain;
                chunkPtr[i * 2] += clickSample;
                chunkPtr[i * 2 + 1] += clickSample;
                ++impl_->clickPosSamples;
            }
            if (impl_->clickPosSamples >= impl_->clickTotalSamples) {
                impl_->clickPosSamples = -1;
            }
        }

        offset += step;
    }

    // Apply Studio Stereo Chorus + Concert Reverb + Polyphonic Ceiling Protection
    const float maxChorus = std::max(impl_->slotProfiles[0].chorusDepth, impl_->slotProfiles[1].chorusDepth);
    impl_->fxRack.processInPlace(
        outInterleavedLR,
        numFrames,
        impl_->slotProfiles[0].reverbSend,
        maxChorus,
        /*applySafetyLimiter=*/true
    );
}

// ============================================================================
// Offline Studio WAV Renderer
// ============================================================================

bool renderMelodySequenceToWav(
    const InstrumentFxProfile& primaryProfile,
    const InstrumentFxProfile& secondaryProfile,
    const std::vector<SynthNoteSpec>& notes,
    int64_t sequenceDurationFrames,
    int outputSampleRate,
    const std::string& outWavPath,
    const ProgressCallback& onProgress,
    std::string* errMsg
) {
    if (notes.empty()) {
        if (errMsg) *errMsg = "乐谱为空，请先输入音符";
        return false;
    }

    tsf* synth = cloneSharedSoundFontInstance();
    if (!synth) {
        if (errMsg) *errMsg = "乐器音源尚未加载完成";
        return false;
    }

    const int sr = std::max(22050, outputSampleRate);
    configureTsfInstance(synth, sr, primaryProfile, secondaryProfile);
    StudioStereoFxRack fxRack(sr);
    bool hasSlot1Notes = false;
    for (const auto& n : notes) {
        if (n.slot == 1) {
            hasSlot1Notes = true;
            break;
        }
    }
    const float chorusMix = hasSlot1Notes
        ? std::max(primaryProfile.chorusDepth, secondaryProfile.chorusDepth)
        : primaryProfile.chorusDepth;

    std::vector<SynthNoteSpec> sorted = notes;
    std::sort(sorted.begin(), sorted.end(), [](const SynthNoteSpec& a, const SynthNoteSpec& b) {
        return a.startFrame < b.startFrame;
    });

    int64_t maxNoteEnd = sequenceDurationFrames;
    for (const auto& n : sorted) {
        maxNoteEnd = std::max(maxNoteEnd, n.startFrame + std::max<int64_t>(256, n.durationFrames));
    }

    // Add natural studio release + custom sustain + reverb tail so final chord blooms naturally
    float maxSustainTailSec = 1.6f;
    if (primaryProfile.sustainPedal) {
        maxSustainTailSec = std::max(maxSustainTailSec, primaryProfile.sustainDurationSec + 0.65f);
    }
    if (hasSlot1Notes && secondaryProfile.sustainPedal) {
        maxSustainTailSec = std::max(maxSustainTailSec, secondaryProfile.sustainDurationSec + 0.65f);
    }
    maxSustainTailSec = std::clamp(maxSustainTailSec, 1.6f, 12.0f);
    const int64_t tailFrames = static_cast<int64_t>(sr * maxSustainTailSec);
    const int64_t totalFrames = std::max<int64_t>(sr / 2, maxNoteEnd + tailFrames);

    PlanarAudioBuffer outBuf;
    outBuf.sampleRate = sr;
    outBuf.channels.resize(2);
    outBuf.channels[0].assign(static_cast<size_t>(totalFrames), 0.0f);
    outBuf.channels[1].assign(static_cast<size_t>(totalFrames), 0.0f);

    std::vector<ActiveSeqNote> activeNotes;
    size_t nextIdx = 0;

    constexpr int kBlock = 256;
    std::vector<float> interleaved(kBlock * 2, 0.0f);

    int64_t cursor = 0;
    int progressTick = 0;
    while (cursor < totalFrames) {
        if (++progressTick % 64 == 0 && onProgress) {
            float p = 0.05f + 0.75f * (static_cast<float>(cursor) / static_cast<float>(totalFrames));
            if (!onProgress(p)) {
                tsf_close(synth);
                if (errMsg) *errMsg = "Cancelled";
                return false;
            }
        }

        const int step = static_cast<int>(std::min<int64_t>(kBlock, totalFrames - cursor));
        const int64_t endFrame = cursor + step;

        for (auto it = activeNotes.begin(); it != activeNotes.end();) {
            if (it->offFrame <= endFrame) {
                const InstrumentFxProfile& prof = (it->channel == 3) ? secondaryProfile : primaryProfile;
                releaseExpressiveSeqNote(
                    synth,
                    it->channel,
                    it->midiNote,
                    it->flags,
                    prof
                );
                it = activeNotes.erase(it);
            } else {
                if (it->fpDuckRemainingDb > 0.01f && endFrame > it->fpDuckStartFrame) {
                    const float stepDb = std::min(
                        it->fpDuckRemainingDb,
                        160.0f * (static_cast<float>(step) / static_cast<float>(sr))
                    );
                    applyFortepianoVoiceAttenuation(synth, it->channel, it->midiNote, stepDb);
                    it->fpDuckRemainingDb -= stepDb;
                }
                ++it;
            }
        }

        while (nextIdx < sorted.size()) {
            const SynthNoteSpec& ev = sorted[nextIdx];
            if (ev.startFrame >= endFrame) break;
            if (ev.startFrame >= cursor) {
                const bool isSlot1 = (ev.slot == 1);
                const int seqCh = isSlot1 ? 3 : 1;
                const InstrumentFxProfile& prof = isSlot1 ? secondaryProfile : primaryProfile;
                const int key = std::clamp(ev.midiNote, 0, 127);
                const float vel = std::clamp(ev.velocity, 0.05f, 1.25f);
                releaseChannelKeyWithCustomSustain(
                    synth,
                    seqCh,
                    key,
                    prof.sustainPedal,
                    prof.sustainDurationSec
                );
                triggerExpressiveChannelNoteOn(synth, seqCh, key, vel);
                const bool isFp = (ev.flags & kSynthFlagFortepianoDuck) != 0;
                activeNotes.push_back(ActiveSeqNote{
                    seqCh,
                    key,
                    ev.startFrame + std::max<int64_t>(256, ev.durationFrames),
                    ev.flags,
                    ev.startFrame + static_cast<int64_t>(0.065 * sr),
                    isFp ? 9.5f : 0.0f
                });
            }
            ++nextIdx;
        }

        tsf_render_float(synth, interleaved.data(), step, 0);
        sweepSilentVoices(synth);
        fxRack.processInPlace(
            interleaved.data(),
            step,
            primaryProfile.reverbSend,
            chorusMix,
            /*applySafetyLimiter=*/false
        );

        float* dstL = outBuf.channels[0].data() + cursor;
        float* dstR = outBuf.channels[1].data() + cursor;
        for (int i = 0; i < step; ++i) {
            dstL[i] = interleaved[i * 2];
            dstR[i] = interleaved[i * 2 + 1];
        }

        cursor += step;
    }

    tsf_close(synth);

    // Trim excess trailing near-silence (< -68 dBFS) at the very end while keeping natural decay
    int64_t trimEnd = totalFrames;
    constexpr float kSilenceThresh = 0.0004f; // ~ -68 dBFS
    while (trimEnd > maxNoteEnd + sr / 8) {
        float aL = std::fabs(outBuf.channels[0][static_cast<size_t>(trimEnd - 1)]);
        float aR = std::fabs(outBuf.channels[1][static_cast<size_t>(trimEnd - 1)]);
        if (std::max(aL, aR) > kSilenceThresh) break;
        --trimEnd;
    }
    if (trimEnd < totalFrames) {
        // Apply 20ms smooth micro fade-out on tail end
        const int64_t fadeLen = std::min<int64_t>(sr / 50, trimEnd);
        for (int64_t i = 0; i < fadeLen; ++i) {
            float g = static_cast<float>(fadeLen - 1 - i) / static_cast<float>(fadeLen);
            size_t idx = static_cast<size_t>(trimEnd - fadeLen + i);
            outBuf.channels[0][idx] *= g;
            outBuf.channels[1][idx] *= g;
        }
        outBuf.channels[0].resize(static_cast<size_t>(trimEnd));
        outBuf.channels[1].resize(static_cast<size_t>(trimEnd));
    }

    // Studio true-peak ceiling protection (-0.2 dBTP)
    BoostResult limRes = boostVolumeTransparent(
        outBuf,
        0.0f,
        -0.2f,
        [&](float p) -> bool {
            return onProgress ? onProgress(0.80f + p * 0.14f) : true;
        }
    );
    if (!limRes.ok) {
        if (errMsg) *errMsg = "Cancelled";
        return false;
    }

    if (!writeWavFile(outWavPath, outBuf, false, errMsg)) {
        return false;
    }
    if (onProgress) onProgress(1.0f);
    return true;
}

} // namespace soundtracker
