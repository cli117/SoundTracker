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
constexpr float kPi = 3.14159265358979323846f;
constexpr int kStringsEnsemblePreset = 48;

constexpr int kSynthFlagCrispRelease     = 1 << 0; // Staccato / Staccatissimo / Marcato
constexpr int kSynthFlagPortatoRelease   = 1 << 1; // Portato / Phrase-end singing release
constexpr int kSynthFlagLegatoOverlap    = 1 << 2; // Slur interior smooth overlap
constexpr int kSynthFlagFortepianoDuck   = 1 << 3; // `fp` strong attack -> soft sustain
constexpr int kSynthFlagContiguousNext   = 1 << 4; // Immediately followed by next note on same slot with no rest
constexpr int kSynthFlagStringsCrossfade = 1 << 5; // Internal: strings contiguous bow-change crossfade
constexpr int kSynthFlagNoVibrato        = 1 << 6; // Internal: short note (<290ms) suppresses delayed vibrato
constexpr int kSynthFlagShortBow         = 1 << 7; // Internal: <250ms short note agile bowing
constexpr int kSynthFlagMediumBow        = 1 << 8; // Internal: 250ms..440ms singing bowing
constexpr int kSynthFlagCrispBowStart    = 1 << 9; // Internal: staccato/marcato/fp/accent keeps crisp bow transient
constexpr int kSynthFlagLegatoEntry      = 1 << 10; // Internal: seamless entry from prior note via equal-power sine attack
constexpr int kSynthFlagTightOnset       = 1 << 11; // Internal: live keyboard / beat-0 / seek-catchup brisk soft bow

inline bool isStringsProfile(const InstrumentFxProfile& prof) {
    return prof.presetNumber == kStringsEnsemblePreset;
}

inline float resolveStringsReleaseSec(bool sustainOn, float sustainDurationSec) {
    if (!sustainOn) {
        // tsf.h decays to -80.1 dB over `release` seconds (-20 dB audible tail ~= 0.25 * release -> ~365ms).
        return 1.45f;
    }
    const float clamped = std::clamp(sustainDurationSec, 0.020f, 15.0f);
    if (clamped < 0.60f) {
        return clamped;
    }
    return std::min(clamped + 0.55f, clamped * 1.65f);
}

inline bool isVoiceReleasable(const struct tsf_voice* v) {
    return v &&
           v->playingPreset != -1 &&
           v->ampenv.segment < TSF_SEGMENT_RELEASE &&
           !(v->ampenv.segment == TSF_SEGMENT_DECAY && v->ampenv.parameters.sustain <= 0.0001f);
}

std::mutex gSfMutex;
tsf* gBaseSoundFont = nullptr;

// Neutralizes SF2 `Fast Strings` (Preset 48) hardcoded bright modEnv filter sweep and LFO vibrato,
// establishing a clean 1.0 sustain plateau controlled smoothly in dB by StringsVoiceModTracker.
void prepareLoadedSoundFontPresets(tsf* sf) {
    if (!sf || !sf->presets) return;
    for (int p = 0; p < sf->presetNum; ++p) {
        if (sf->presets[p].preset == kStringsEnsemblePreset && sf->presets[p].regions) {
            for (int r = 0; r < sf->presets[p].regionNum; ++r) {
                struct tsf_region& reg = sf->presets[p].regions[r];
                reg.modEnvToFilterFc = 0;
                reg.modLfoToFilterFc = 0;
                reg.modLfoToPitch = 0;
                reg.vibLfoToPitch = 0;
                reg.modLfoToVolume = 0;
                reg.initialFilterFc = 9950;
                reg.initialFilterQ = 0;
                reg.ampenv.hold = 0.0f;
                reg.ampenv.decay = 0.0f;
                reg.ampenv.sustain = 1.0f;
            }
        }
    }
}

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
        tsf_channel_set_pitchrange(instance, ch, 2.0f);
        tsf_channel_set_pitchwheel(instance, ch, 8192);
        tsf_channel_set_tuning(instance, ch, 0.0f);
        // Never use SF2 raw MIDI CC64 sustain (`tsf_channel_set_sustain`), because looped
        // wind/string/EP presets have SF2 `sustain > 0` and would hold indefinitely ("forever").
        // Instead we perform natural exponential damper-pedal release over `sustainDurationSec`.
        tsf_channel_set_sustain(instance, ch, 0);
    }
}

class StringsVoiceModTracker {
public:
    void resetAll() {
        slots_.clear();
    }

    void resetChannel(int channel) {
        for (auto& s : slots_) {
            if (s.active && s.channel == channel) {
                s.active = false;
            }
        }
    }

    void onStringsNoteVoicesSpawned(
        tsf* f,
        unsigned int playIndex,
        int channel,
        int key,
        float rawVelocity,
        int flags,
        float startCutoffHz,
        float peakCutoffHz,
        float attackSec,
        float initialCurveDb,
        float glideFromSemitones = 0.0f
    ) {
        if (!f || !f->voices || f->voiceNum <= 0) return;
        if (static_cast<int>(slots_.size()) < f->voiceNum) {
            slots_.resize(static_cast<size_t>(f->voiceNum));
        }

        const float reg = std::clamp(static_cast<float>(key - 40) / 36.0f, 0.0f, 1.0f);
        const float velT = std::clamp((rawVelocity - 0.15f) / 0.85f, 0.0f, 1.0f);
        const bool isCrispBow = (flags & kSynthFlagCrispBowStart) != 0;
        const bool isLegatoBow = !isCrispBow && ((flags & (kSynthFlagLegatoEntry | kSynthFlagLegatoOverlap)) != 0);
        const bool isTightOnset = !isCrispBow && ((flags & kSynthFlagTightOnset) != 0);
        const bool allowVibrato =
            (flags & kSynthFlagNoVibrato) == 0 &&
            (flags & kSynthFlagCrispRelease) == 0;
        float channelBaseShift = 0.0f;
        if (f->channels && channel >= 0 && channel < f->channels->channelNum) {
            const int pw = tsf_channel_get_pitchwheel(f, channel);
            const float pr = tsf_channel_get_pitchrange(f, channel);
            const float tuning = tsf_channel_get_tuning(f, channel);
            channelBaseShift = (pw == 8192) ? tuning : (((pw / 16383.0f) * pr * 2.0f) - pr + tuning);
        }

        // Per-note hash (shared across stereo ensemble layers) so both layers oscillate coherently
        // at the exact same vibrato rate and never drift 180 degrees out of phase.
        const unsigned int noteH =
            (static_cast<unsigned int>(key) * 2654435761u) ^
            (static_cast<unsigned int>(playIndex) * 2246822519u);
        const float noteHash01 = static_cast<float>(noteH & 0xFFu) / 255.0f;
        const float notePhase0 = (static_cast<float>((noteH >> 8) & 0xFFu) / 255.0f) * 1.20f;
        const float sharedVibRateHz = std::clamp(5.08f + 0.30f * reg + 0.20f * noteHash01, 5.05f, 5.58f);
        const float sharedMaxDepth = std::clamp((0.215f + 0.050f * reg) * (0.92f + 0.14f * velT), 0.205f, 0.270f);

        int layerIdx = 0;
        for (int i = 0; i < f->voiceNum; ++i) {
            struct tsf_voice* v = &f->voices[i];
            if (v->playingPreset == -1 ||
                v->playIndex != playIndex ||
                v->playingChannel != channel ||
                v->playingKey != key) {
                continue;
            }

            const unsigned int h = noteH ^ (static_cast<unsigned int>(layerIdx) * 1597334677u);
            const float hashDetune01 = static_cast<float>((h >> 8) & 0xFFu) / 255.0f;
            const float baseLayerDetune = (layerIdx % 2 == 1) ? 0.019f : -0.018f;

            VoiceModSlot& s = slots_[static_cast<size_t>(i)];
            s.active = true;
            s.playIndex = playIndex;
            s.channel = channel;
            s.key = key;
            s.allowVibrato = allowVibrato;
            s.isCrispBow = isCrispBow;
            s.isLegatoBow = isLegatoBow;
            s.isTightOnset = isTightOnset;
            s.velT = velT;
            s.elapsedSec = 0.0f;
            s.vibAgeSec = 0.0f;
            s.attackSec = std::max(0.015f, attackSec);
            s.initialCurveDb = initialCurveDb;
            s.appliedCurveDb = initialCurveDb;
            s.appliedVibDb = 0.0f;
            s.releaseFrozen = false;
            s.startCutoffHz = startCutoffHz;
            s.peakCutoffHz = peakCutoffHz;
            s.currentCutoffHz = startCutoffHz;
            s.releaseCutoffCaptured = false;
            s.releaseStartCutoffHz = startCutoffHz;
            s.glideStartSemitones = isCrispBow ? 0.0f : glideFromSemitones;
            s.detuneSemitones = baseLayerDetune + (hashDetune01 - 0.5f) * 0.008f;
            s.vibPhase = notePhase0 + ((layerIdx % 2 == 1) ? 0.16f : 0.0f);
            s.vibRateHz = sharedVibRateHz;
            s.maxDepthSemitones = sharedMaxDepth;

            tsf_voice_calcpitchratio(
                v,
                channelBaseShift + s.detuneSemitones + s.glideStartSemitones,
                f->outSampleRate
            );
            ++layerIdx;
        }
    }

    void markReleaseFrozen(tsf* f, const struct tsf_voice* v) {
        if (!f || !f->voices || !v) return;
        const ptrdiff_t idx = v - f->voices;
        if (idx < 0 || idx >= static_cast<ptrdiff_t>(slots_.size())) return;
        VoiceModSlot& s = slots_[static_cast<size_t>(idx)];
        if (s.active && s.playIndex == v->playIndex && s.channel == v->playingChannel && s.key == v->playingKey) {
            s.releaseFrozen = true;
            if (!s.releaseCutoffCaptured) {
                s.releaseCutoffCaptured = true;
                s.releaseStartCutoffHz = s.currentCutoffHz;
            }
        }
    }

    void processSubChunk(tsf* f, int stepSamples) {
        if (!f || !f->voices || f->voiceNum <= 0 || stepSamples <= 0) return;
        if (static_cast<int>(slots_.size()) < f->voiceNum) {
            slots_.resize(static_cast<size_t>(f->voiceNum));
        }

        const float sr = std::max(8000.0f, f->outSampleRate);
        const float dt = static_cast<float>(stepSamples) / sr;
        constexpr float kVibDelaySec = 0.155f;
        constexpr float kVibRampSec = 0.205f;
        constexpr float kFreshMinLin = 0.0199526f;  // 10^(-34 / 20)
        constexpr float kLegatoMinLin = 0.0398107f; // 10^(-28 / 20)
        constexpr float kCrispMinLin = 0.3981072f;  // 10^(-8 / 20)

        for (int i = 0; i < f->voiceNum; ++i) {
            VoiceModSlot& s = slots_[static_cast<size_t>(i)];
            if (!s.active) continue;

            struct tsf_voice* v = &f->voices[i];
            if (v->playingPreset == -1 ||
                v->playIndex != s.playIndex ||
                v->playingChannel != s.channel ||
                v->playingKey != s.key) {
                s.active = false;
                continue;
            }

            if (v->ampenv.segment == TSF_SEGMENT_DELAY) {
                // Waiting on chord-humanize delay: keep initial gain & filter warm and detune steady.
                continue;
            }

            const bool isReleasing =
                (v->ampenv.segment >= TSF_SEGMENT_RELEASE) ||
                (v->ampenv.segment == TSF_SEGMENT_DECAY && v->ampenv.parameters.sustain <= 0.0001f);

            // 1. Decoupled two-stage bow envelope (on-beat velvet cushion + leisurely swell) / equal-power legato
            if (isReleasing) {
                s.releaseFrozen = true;
                if (!s.releaseCutoffCaptured) {
                    s.releaseCutoffCaptured = true;
                    s.releaseStartCutoffHz = s.currentCutoffHz;
                }
            } else if (!s.releaseFrozen) {
                s.elapsedSec += dt;
                const float u = std::clamp(s.elapsedSec / s.attackSec, 0.0f, 1.0f);
                float targetDb = 0.0f;
                if (s.isCrispBow) {
                    const float linG = kCrispMinLin + (1.0f - kCrispMinLin) * std::sin(0.5f * kPi * u);
                    targetDb = 20.0f * std::log10(linG);
                } else if (s.isLegatoBow) {
                    const float uFoot = std::clamp(s.elapsedSec / 0.026f, 0.0f, 1.0f);
                    const float foot = uFoot * uFoot * (3.0f - 2.0f * uFoot);
                    const float linG = std::max(kLegatoMinLin, foot * std::sin(0.5f * kPi * u));
                    targetDb = 20.0f * std::log10(linG);
                } else {
                    // Stage 1: Smoothstep catch (~36..42ms) onto dark -8 dB velvet cushion -> locks beat P-center cleanly.
                    // Stage 2: Leisurely S-curve swell (~225..265ms) of remaining +8 dB -> lush non-harsh bloom after beat.
                    const float catchSec = s.isTightOnset ? 0.036f : 0.042f;
                    const float cushionAmp = 0.36f + 0.04f * s.velT;
                    const float uCatch = std::clamp(s.elapsedSec / catchSec, 0.0f, 1.0f);
                    const float sCatch = uCatch * uCatch * (3.0f - 2.0f * uCatch);
                    const float sSwell = u * u * (3.0f - 2.0f * u);
                    const float linG = kFreshMinLin +
                                       cushionAmp * sCatch +
                                       (1.0f - kFreshMinLin - cushionAmp) * sSwell;
                    targetDb = 20.0f * std::log10(linG);
                }
                v->noteGainDB += (targetDb - s.appliedCurveDb);
                s.appliedCurveDb = targetDb;
            }

            // 2. Ensemble detune + continuous sustain & tail vibrato (Pitch FM + Bow AM) + finger micro-portamento
            s.vibAgeSec += dt;

            float vibEnv = 0.0f;
            float vibLfo = 0.0f;
            float vibShift = 0.0f;
            float targetVibDb = 0.0f;
            if (s.allowVibrato && s.vibAgeSec > kVibDelaySec) {
                const float rampU = std::clamp((s.vibAgeSec - kVibDelaySec) / kVibRampSec, 0.0f, 1.0f);
                vibEnv = 0.5f * (1.0f - std::cos(kPi * rampU));
                if (isReleasing) {
                    // Keep 78%..100% singing vibrato through the entire soft release / sustain tail!
                    const float envLvl = std::clamp(v->ampenv.level, 0.0f, 1.0f);
                    vibEnv *= (0.78f + 0.22f * std::sqrt(envLvl));
                }
                s.vibPhase += kTwoPi * s.vibRateHz * dt;
                if (s.vibPhase >= kTwoPi) s.vibPhase -= kTwoPi;
                vibLfo = std::sin(s.vibPhase);
                vibShift = vibLfo * (s.maxDepthSemitones * vibEnv);
                targetVibDb = 0.85f * vibEnv * vibLfo;
            }
            if (std::fabs(targetVibDb - s.appliedVibDb) > 1e-5f) {
                v->noteGainDB += (targetVibDb - s.appliedVibDb);
                s.appliedVibDb = targetVibDb;
            }

            float glideShift = 0.0f;
            if (std::fabs(s.glideStartSemitones) > 0.001f) {
                const float glideDurSec = s.isTightOnset ? 0.075f : 0.092f;
                if (s.vibAgeSec < glideDurSec) {
                    const float ug = std::clamp(s.vibAgeSec / glideDurSec, 0.0f, 1.0f);
                    glideShift = s.glideStartSemitones * (0.5f * (1.0f + std::cos(kPi * ug)));
                }
            }

            float channelBaseShift = 0.0f;
            if (f->channels && v->playingChannel >= 0 && v->playingChannel < f->channels->channelNum) {
                const int pw = tsf_channel_get_pitchwheel(f, v->playingChannel);
                const float pr = tsf_channel_get_pitchrange(f, v->playingChannel);
                const float tuning = tsf_channel_get_tuning(f, v->playingChannel);
                channelBaseShift = (pw == 8192) ? tuning : (((pw / 16383.0f) * pr * 2.0f) - pr + tuning);
            }
            tsf_voice_calcpitchratio(v, channelBaseShift + s.detuneSemitones + glideShift + vibShift, sr);

            // 3. Dark lagged S-curve warmth lowpass filter + vibrato resonance shimmer (sustain & release)
            float fc = s.currentCutoffHz;
            if (isReleasing) {
                const float relScale = 0.45f + 0.55f * std::sqrt(std::clamp(v->ampenv.level, 0.0f, 1.0f));
                fc = std::max(1180.0f, s.releaseStartCutoffHz * relScale);
            } else {
                const float filterRiseSec = s.isCrispBow
                    ? 0.048f
                    : std::max(0.300f, s.attackSec * 1.28f);
                const float uFilt = std::clamp(s.elapsedSec / filterRiseSec, 0.0f, 1.0f);
                const float smFilt = uFilt * uFilt * (3.0f - 2.0f * uFilt);
                fc = s.startCutoffHz + (s.peakCutoffHz - s.startCutoffHz) * smFilt;
            }
            s.currentCutoffHz = fc;
            const float modulatedFc = (vibEnv > 0.001f)
                ? (fc * (1.0f + 0.085f * vibLfo * vibEnv))
                : fc;
            tsf_voice_lowpass_setup(&v->lowpass, std::clamp(modulatedFc / sr, 0.02f, 0.485f));
        }
    }

private:
    struct VoiceModSlot {
        bool active = false;
        unsigned int playIndex = 0;
        int channel = -1;
        int key = -1;
        bool allowVibrato = true;
        bool isCrispBow = false;
        bool isLegatoBow = false;
        bool isTightOnset = false;
        float velT = 0.5f;
        float elapsedSec = 0.0f;
        float vibAgeSec = 0.0f;
        float attackSec = 0.24f;
        float initialCurveDb = -34.0f;
        float appliedCurveDb = -34.0f;
        float appliedVibDb = 0.0f;
        bool releaseFrozen = false;
        float startCutoffHz = 1400.0f;
        float peakCutoffHz = 3200.0f;
        float currentCutoffHz = 1400.0f;
        bool releaseCutoffCaptured = false;
        float releaseStartCutoffHz = 2600.0f;
        float glideStartSemitones = 0.0f;
        float detuneSemitones = 0.0f;
        float vibPhase = 0.0f;
        float vibRateHz = 5.2f;
        float maxDepthSemitones = 0.23f;
    };

    std::vector<VoiceModSlot> slots_;
};

void applySmoothVoiceRelease(
    tsf* f,
    struct tsf_voice* v,
    float releaseSec,
    bool isStrings,
    StringsVoiceModTracker* modTracker = nullptr,
    bool quickDamp = false
) {
    (void)quickDamp;
    if (!f || !v) return;
    const float clampedRel = std::clamp(releaseSec, 0.020f, 15.0f);
    const int repeats = (f->maxVoiceNum ? 2 : 1);

    for (int r = 0; r < repeats; ++r) {
        if (v->ampenv.segment == TSF_SEGMENT_DELAY) {
            if (isStrings) {
                v->ampenv.parameters.delay = 0.0f;
                v->ampenv.parameters.attack = 0.015f;
                v->ampenv.parameters.hold = 0.0f;
                v->ampenv.parameters.sustain = 0.0f;
                v->ampenv.parameters.decay = clampedRel;
                v->ampenv.parameters.release = 0.045f;
                if (r == 0) {
                    tsf_voice_envelope_nextsegment(&v->ampenv, TSF_SEGMENT_NONE, f->outSampleRate);
                }
            } else {
                v->ampenv.parameters.hold = 0.0f;
                v->ampenv.parameters.sustain = 0.0f;
                v->ampenv.parameters.decay = clampedRel;
                v->ampenv.parameters.release = 0.025f;
            }
        } else if (v->ampenv.segment < TSF_SEGMENT_HOLD) {
            if (isStrings && v->ampenv.level >= 0.35f) {
                v->ampenv.parameters.release = clampedRel;
                v->modenv.parameters.release = std::max(v->modenv.parameters.release, clampedRel);
                tsf_voice_envelope_nextsegment(&v->ampenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
                tsf_voice_envelope_nextsegment(&v->modenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
                if (v->region && v->region->loop_mode == TSF_LOOPMODE_SUSTAIN) {
                    v->loopEnd = v->loopStart;
                }
            } else {
                v->ampenv.parameters.hold = 0.0f;
                v->ampenv.parameters.sustain = 0.0f;
                v->ampenv.parameters.decay = clampedRel;
                v->ampenv.parameters.release = 0.045f;
                if (isStrings && r == 0) {
                    const int maxRem = std::max(1, static_cast<int>(0.020f * f->outSampleRate));
                    if (v->ampenv.samplesUntilNextSegment > maxRem) {
                        v->ampenv.samplesUntilNextSegment = maxRem;
                        v->ampenv.slope = (1.0f - v->ampenv.level) / static_cast<float>(maxRem);
                    }
                }
            }
        } else {
            v->ampenv.parameters.release = clampedRel;
            v->modenv.parameters.release = std::max(v->modenv.parameters.release, clampedRel);
            tsf_voice_envelope_nextsegment(&v->ampenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
            tsf_voice_envelope_nextsegment(&v->modenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
            if (v->region && v->region->loop_mode == TSF_LOOPMODE_SUSTAIN) {
                v->loopEnd = v->loopStart;
            }
        }
    }

    if (isStrings && modTracker) {
        modTracker->markReleaseFrozen(f, v);
    }
}

// Releases matching active voice(s) on `(channel, key)` either normally (`!sustainOn`)
// or with a smooth exponential damper-pedal decay lasting `sustainDurationSec` seconds (`sustainOn`),
// ensuring ALL instruments (both plucked/struck and looped wind/string/EP) decay naturally to silence.
// For Warm Strings (`isStrings == true`), even `!sustainOn` uses a soft release (`stringsSoftReleaseSec`)
// instead of an abrupt hard cut.
void releaseChannelKeyWithCustomSustain(
    tsf* f,
    int channel,
    int key,
    bool sustainOn,
    float sustainDurationSec,
    bool isStrings = false,
    float stringsSoftReleaseSec = 1.45f,
    StringsVoiceModTracker* modTracker = nullptr,
    bool quickDamp = false
) {
    if (!f || !f->voices) return;
    struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
    struct tsf_voice *vMatchFirst = nullptr, *vMatchLast = nullptr;
    for (; v != vEnd; ++v) {
        if (!isVoiceReleasable(v) || v->playingChannel != channel || v->playingKey != key) {
            continue;
        }
        if (!vMatchFirst || v->playIndex < vMatchFirst->playIndex) {
            vMatchFirst = vMatchLast = v;
        } else if (v->playIndex == vMatchFirst->playIndex) {
            vMatchLast = v;
        }
    }
    if (!vMatchFirst) return;

    const float clampedSustainSec = isStrings
        ? resolveStringsReleaseSec(true, sustainDurationSec)
        : std::clamp(sustainDurationSec, 0.020f, 15.0f);
    for (v = vMatchFirst; v <= vMatchLast; ++v) {
        if (v != vMatchFirst && v != vMatchLast &&
            (v->playIndex != vMatchFirst->playIndex || !isVoiceReleasable(v) ||
             v->playingChannel != channel || v->playingKey != key)) {
            continue;
        }
        if (!sustainOn && !isStrings) {
            tsf_voice_end(f, v);
        } else {
            applySmoothVoiceRelease(
                f,
                v,
                sustainOn ? clampedSustainSec : stringsSoftReleaseSec,
                isStrings,
                modTracker,
                quickDamp
            );
        }
    }
}

// Releases exact voice(s) belonging to a specific `playIndex` so overlapping crossfades on the same
// pitch never prematurely cut a newly triggered note.
void releaseChannelPlayIndex(
    tsf* f,
    int channel,
    int key,
    unsigned int playIndex,
    bool sustainOn,
    float sustainDurationSec,
    bool isStrings,
    float stringsSoftReleaseSec = 1.45f,
    StringsVoiceModTracker* modTracker = nullptr,
    bool quickDamp = false
) {
    if (!f || !f->voices) return;
    const float clampedSustainSec = std::clamp(sustainDurationSec, 0.020f, 15.0f);
    struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
    for (; v != vEnd; ++v) {
        if (!isVoiceReleasable(v) ||
            v->playingChannel != channel ||
            v->playingKey != key ||
            v->playIndex != playIndex) {
            continue;
        }
        if (!sustainOn && !isStrings) {
            tsf_voice_end(f, v);
        } else {
            applySmoothVoiceRelease(
                f,
                v,
                sustainOn ? clampedSustainSec : stringsSoftReleaseSec,
                isStrings,
                modTracker,
                quickDamp
            );
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

void dampSustainingVoicesOnChannel(
    tsf* f,
    int channel,
    bool isStrings = false,
    StringsVoiceModTracker* modTracker = nullptr
) {
    if (!f || !f->voices) return;
    const float targetRel = isStrings ? 1.15f : 0.16f;
    const int fastSamples = std::max(64, static_cast<int>(targetRel * f->outSampleRate));
    struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
    for (; v != vEnd; ++v) {
        if (v->playingPreset == -1 || v->playingChannel != channel) continue;
        if (v->ampenv.segment == TSF_SEGMENT_RELEASE && v->ampenv.samplesUntilNextSegment > fastSamples) {
            v->ampenv.parameters.release = targetRel;
            tsf_voice_envelope_nextsegment(&v->ampenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
            if (v->region && v->region->loop_mode == TSF_LOOPMODE_SUSTAIN) {
                v->loopEnd = v->loopStart;
            }
            if (isStrings && modTracker) {
                modTracker->markReleaseFrozen(f, v);
            }
        }
    }
}

void dampChannelVoicesQuick(
    tsf* f,
    int channel,
    float releaseSec = 0.22f,
    StringsVoiceModTracker* modTracker = nullptr
) {
    if (!f || !f->voices) return;
    const float clamped = std::clamp(releaseSec, 0.020f, 1.50f);
    const int fastSamples = std::max(64, static_cast<int>(clamped * f->outSampleRate));
    struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
    for (; v != vEnd; ++v) {
        if (v->playingPreset == -1 || v->playingChannel != channel) continue;
        if (v->ampenv.segment == TSF_SEGMENT_RELEASE) {
            if (v->ampenv.samplesUntilNextSegment > fastSamples) {
                v->ampenv.parameters.release = clamped;
                tsf_voice_envelope_nextsegment(&v->ampenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
            }
            if (modTracker) modTracker->markReleaseFrozen(f, v);
        } else if (v->ampenv.segment == TSF_SEGMENT_DECAY && v->ampenv.parameters.sustain <= 0.0001f) {
            if (v->ampenv.samplesUntilNextSegment > fastSamples) {
                v->ampenv.parameters.release = clamped;
                tsf_voice_envelope_nextsegment(&v->ampenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
            }
            if (modTracker) modTracker->markReleaseFrozen(f, v);
        } else {
            applySmoothVoiceRelease(f, v, clamped, /*isStrings=*/true, modTracker, /*quickDamp=*/true);
        }
    }
}

void enforceStringsChannelPolyphonyCap(
    tsf* f,
    int channel,
    StringsVoiceModTracker* modTracker = nullptr
) {
    if (!f || !f->voices) return;
    constexpr int kMaxStringsActiveVoices = 24;    // 12 stereo notes per channel
    constexpr int kMaxStringsReleasingVoices = 12; // 6 stereo release tails per channel
    const float fastTailSec = 0.085f;
    const int fastTailSamples = std::max(64, static_cast<int>(fastTailSec * f->outSampleRate));

    for (int iter = 0; iter < 16; ++iter) {
        int activeCount = 0;
        int releasingCount = 0;
        struct tsf_voice* oldestSlowRelease = nullptr;
        struct tsf_voice* oldestReleasing = nullptr;
        struct tsf_voice* oldestActive = nullptr;

        struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
        for (; v != vEnd; ++v) {
            if (v->playingPreset == -1 || v->playingChannel != channel) continue;
            ++activeCount;
            const bool isRel = (v->ampenv.segment >= TSF_SEGMENT_RELEASE) ||
                               (v->ampenv.segment == TSF_SEGMENT_DECAY && v->ampenv.parameters.sustain <= 0.0001f);
            if (isRel) {
                ++releasingCount;
                if (!oldestReleasing || v->playIndex < oldestReleasing->playIndex) {
                    oldestReleasing = v;
                }
                if (v->ampenv.samplesUntilNextSegment > fastTailSamples &&
                    (!oldestSlowRelease || v->playIndex < oldestSlowRelease->playIndex)) {
                    oldestSlowRelease = v;
                }
            } else {
                if (!oldestActive || v->playIndex < oldestActive->playIndex) {
                    oldestActive = v;
                }
            }
        }

        if (releasingCount > kMaxStringsReleasingVoices && oldestSlowRelease) {
            if (modTracker) modTracker->markReleaseFrozen(f, oldestSlowRelease);
            oldestSlowRelease->ampenv.parameters.release = fastTailSec;
            if (oldestSlowRelease->ampenv.segment == TSF_SEGMENT_DECAY) {
                oldestSlowRelease->ampenv.parameters.decay = fastTailSec;
                oldestSlowRelease->ampenv.samplesUntilNextSegment = std::min(
                    oldestSlowRelease->ampenv.samplesUntilNextSegment, fastTailSamples);
            } else {
                tsf_voice_envelope_nextsegment(&oldestSlowRelease->ampenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
            }
            continue;
        }
        if (activeCount > kMaxStringsActiveVoices) {
            if (oldestSlowRelease) {
                if (modTracker) modTracker->markReleaseFrozen(f, oldestSlowRelease);
                oldestSlowRelease->ampenv.parameters.release = fastTailSec;
                tsf_voice_envelope_nextsegment(&oldestSlowRelease->ampenv, TSF_SEGMENT_SUSTAIN, f->outSampleRate);
            } else if (oldestActive) {
                applySmoothVoiceRelease(f, oldestActive, 0.065f, true, modTracker, true);
            } else if (oldestReleasing && activeCount > kMaxStringsActiveVoices + 4) {
                tsf_voice_kill(oldestReleasing);
            } else {
                break;
            }
            continue;
        }
        break;
    }
}

struct ScheduledSeqNote {
    int64_t startFrame = 0;
    int64_t nominalStartFrame = 0;
    int64_t durationFrames = 0;
    int midiNote = 60;
    float velocity = 0.85f;
    int slot = 0;
    int flags = 0;
    float glideFromSemitones = 0.0f;
};

std::vector<ScheduledSeqNote> buildShapedSynthSequence(
    const std::vector<SynthNoteSpec>& rawNotes,
    const InstrumentFxProfile& slot0Prof,
    const InstrumentFxProfile& slot1Prof,
    int sampleRate
) {
    if (rawNotes.empty()) return {};
    const int sr = std::max(8000, sampleRate);
    std::vector<ScheduledSeqNote> slotBuckets[2];
    for (const auto& n : rawNotes) {
        const int s = (n.slot == 1) ? 1 : 0;
        const int64_t startF = std::max<int64_t>(0, n.startFrame);
        slotBuckets[s].push_back(ScheduledSeqNote{
            startF,
            startF,
            std::max<int64_t>(256, n.durationFrames),
            n.midiNote,
            n.velocity,
            n.slot,
            n.flags,
            0.0f
        });
    }

    const int64_t minVibDurFrames = std::llround(0.210 * sr);
    const int64_t shortBowFrames = std::llround(0.250 * sr);
    const int64_t mediumBowFrames = std::llround(0.440 * sr);
    const int64_t kClusterWindowFrames = std::max<int64_t>(1, std::llround(0.0025 * sr));
    const int64_t bridgeOverlapFrames = std::llround(0.008 * sr);
    const int64_t maxContigGapFrames = std::llround(0.036 * sr);
    const int64_t minContigNegGapFrames = std::llround(0.050 * sr);
    const int64_t maxAnnotatedGapFrames = std::llround(0.180 * sr);
    const int64_t freshPreRollFrames = std::llround(0.028 * sr);
    const int64_t legatoPreRollFrames = std::llround(0.032 * sr);
    const int64_t minFullPreRollFrames = std::llround(0.014 * sr);

    std::vector<ScheduledSeqNote> shaped;
    shaped.reserve(rawNotes.size());

    for (int s = 0; s < 2; ++s) {
        auto& notes = slotBuckets[s];
        if (notes.empty()) continue;

        const InstrumentFxProfile& prof = (s == 1) ? slot1Prof : slot0Prof;
        if (!isStringsProfile(prof)) {
            std::sort(notes.begin(), notes.end(), [](const ScheduledSeqNote& a, const ScheduledSeqNote& b) {
                if (a.startFrame != b.startFrame) return a.startFrame < b.startFrame;
                return a.midiNote < b.midiNote;
            });
            shaped.insert(shaped.end(), notes.begin(), notes.end());
            continue;
        }

        std::sort(notes.begin(), notes.end(), [](const ScheduledSeqNote& a, const ScheduledSeqNote& b) {
            if (a.nominalStartFrame != b.nominalStartFrame) return a.nominalStartFrame < b.nominalStartFrame;
            return a.midiNote < b.midiNote;
        });

        // Tag bow length and crisp attack classes BEFORE duration crossfade adjustment.
        for (auto& ev : notes) {
            if (ev.durationFrames < minVibDurFrames) {
                ev.flags |= kSynthFlagNoVibrato;
            }
            if (ev.durationFrames < shortBowFrames) {
                ev.flags |= kSynthFlagShortBow;
            } else if (ev.durationFrames < mediumBowFrames) {
                ev.flags |= kSynthFlagMediumBow;
            }
            if ((ev.flags & (kSynthFlagCrispRelease | kSynthFlagFortepianoDuck)) != 0 || ev.velocity > 1.01f) {
                ev.flags |= kSynthFlagCrispBowStart;
            }
        }

        struct Cluster {
            size_t begin = 0;
            size_t end = 0;
            int64_t nominalStart = 0;
            int64_t minDur = 0;
        };
        std::vector<Cluster> clusters;
        for (size_t i = 0; i < notes.size();) {
            const int64_t cStart = notes[i].nominalStartFrame;
            int64_t minDur = std::max<int64_t>(256, notes[i].durationFrames);
            size_t j = i + 1;
            while (j < notes.size() && (notes[j].nominalStartFrame - cStart) <= kClusterWindowFrames) {
                minDur = std::min(minDur, std::max<int64_t>(256, notes[j].durationFrames));
                ++j;
            }
            clusters.push_back(Cluster{i, j, cStart, minDur});
            i = j;
        }

        // 1. Gentle +8ms crossfade bridge & finger micro-portamento on contiguous melodic transitions:
        // Outgoing note steps warmly behind the incoming pre-rolled note without an abrupt early chop.
        for (size_t c = 0; c + 1 < clusters.size(); ++c) {
            const Cluster& curC = clusters[c];
            for (size_t idx = curC.begin; idx < curC.end; ++idx) {
                ScheduledSeqNote& ev = notes[idx];
                if ((ev.flags & kSynthFlagCrispRelease) != 0) continue;

                const int64_t evDur = std::max<int64_t>(256, ev.durationFrames);
                const int64_t evEnd = ev.nominalStartFrame + evDur;
                const bool hasAnnotatedContig = (ev.flags & kSynthFlagContiguousNext) != 0;
                const int64_t searchLimit = hasAnnotatedContig ? maxAnnotatedGapFrames : maxContigGapFrames;

                int64_t bestAbsGap = searchLimit + 1;
                size_t bestM = clusters.size();
                for (size_t m = c + 1; m < clusters.size(); ++m) {
                    const int64_t gap = clusters[m].nominalStart - evEnd;
                    if (gap > searchLimit) break;

                    const bool isAnnotatedMatch = hasAnnotatedContig && std::abs(gap) <= maxAnnotatedGapFrames;
                    const bool isFrameMatch = gap >= -minContigNegGapFrames && gap <= maxContigGapFrames;
                    if (isAnnotatedMatch || isFrameMatch) {
                        const int64_t absGap = std::abs(gap);
                        if (absGap < bestAbsGap) {
                            bestAbsGap = absGap;
                            bestM = m;
                        }
                    }
                }
                if (bestM >= clusters.size()) continue;

                const Cluster& nextC = clusters[bestM];
                const int64_t nextStart = nextC.nominalStart;
                const int64_t holdBridge = std::min<int64_t>(bridgeOverlapFrames, nextC.minDur / 6);

                ev.flags |= kSynthFlagStringsCrossfade;
                ev.flags &= ~kSynthFlagPortatoRelease;
                const int64_t targetEnd = std::max(ev.nominalStartFrame + 256, nextStart + holdBridge);
                ev.durationFrames = std::max<int64_t>(256, targetEnd - ev.startFrame);

                for (size_t nIdx = nextC.begin; nIdx < nextC.end; ++nIdx) {
                    if ((notes[nIdx].flags & (kSynthFlagCrispRelease | kSynthFlagCrispBowStart)) == 0) {
                        notes[nIdx].flags |= kSynthFlagLegatoEntry;
                    }
                }
                if ((curC.end - curC.begin) == 1 && (nextC.end - nextC.begin) == 1) {
                    ScheduledSeqNote& nextEv = notes[nextC.begin];
                    const int diff = nextEv.midiNote - ev.midiNote;
                    if ((nextEv.flags & (kSynthFlagCrispRelease | kSynthFlagCrispBowStart)) == 0 &&
                        nextEv.durationFrames >= std::llround(0.180 * sr) &&
                        std::abs(diff) >= 1 && std::abs(diff) <= 9) {
                        const float mag = std::clamp(0.26f + 0.035f * static_cast<float>(std::abs(diff)), 0.28f, 0.48f);
                        nextEv.glideFromSemitones = (diff > 0) ? -mag : mag;
                    }
                }
            }
        }

        // 2. Pre-attack bow anticipation (-32ms fresh / -36ms legato) + tight chord humanize stagger (3.5..11ms)
        for (size_t ci = 0; ci < clusters.size(); ++ci) {
            const Cluster& c = clusters[ci];
            const size_t count = c.end - c.begin;
            if (count >= 2) {
                std::sort(
                    notes.begin() + static_cast<ptrdiff_t>(c.begin),
                    notes.begin() + static_cast<ptrdiff_t>(c.end),
                    [](const ScheduledSeqNote& a, const ScheduledSeqNote& b) {
                        return a.midiNote < b.midiNote;
                    }
                );
            }

            bool hasLegato = false;
            bool allCrisp = true;
            for (size_t k = 0; k < count; ++k) {
                const int fl = notes[c.begin + k].flags;
                if ((fl & kSynthFlagCrispBowStart) == 0) {
                    allCrisp = false;
                    if ((fl & kSynthFlagLegatoEntry) != 0) hasLegato = true;
                }
            }

            const int64_t desiredPreRoll = allCrisp ? 0 : (hasLegato ? legatoPreRollFrames : freshPreRollFrames);
            int64_t maxAllowedPreRoll = c.nominalStart;
            if (ci > 0) {
                maxAllowedPreRoll = std::min(
                    maxAllowedPreRoll,
                    std::max<int64_t>(0, (c.nominalStart - clusters[ci - 1].nominalStart) / 2)
                );
            }
            const int64_t clusterPreRoll = std::min(desiredPreRoll, maxAllowedPreRoll);

            const float spanMs = (count == 2) ? 5.5f : ((count == 3) ? 8.0f : 10.5f);
            for (size_t k = 0; k < count; ++k) {
                ScheduledSeqNote& ev = notes[c.begin + k];
                if (count >= 3 && k > 0 && k + 1 < count) {
                    ev.velocity = std::clamp(ev.velocity * 0.92f, 0.05f, 1.25f);
                }

                int64_t chordOffsetFrames = 0;
                if (count >= 2 && k > 0) {
                    const float baseMs = spanMs * (static_cast<float>(k) / static_cast<float>(count - 1));
                    const unsigned int h =
                        static_cast<unsigned int>(c.nominalStart & 0xFFFF) ^
                        (static_cast<unsigned int>(ev.midiNote) * 2654435761u);
                    const float jitterMs = ((static_cast<float>(h & 0xFFu) / 255.0f) - 0.5f) * 0.8f;
                    const float offsetMs = std::clamp(baseMs + jitterMs, 3.5f, 11.0f);
                    chordOffsetFrames = std::llround((static_cast<double>(offsetMs) / 1000.0) * sr);
                }

                const int64_t scheduledOff = ev.startFrame + std::max<int64_t>(256, ev.durationFrames);
                const bool isCrispNote = (ev.flags & kSynthFlagCrispBowStart) != 0;
                const int64_t notePreRoll = isCrispNote ? 0 : clusterPreRoll;
                if (!isCrispNote && notePreRoll < minFullPreRollFrames) {
                    ev.flags |= kSynthFlagTightOnset;
                }

                const int64_t newStart = std::max<int64_t>(0, ev.nominalStartFrame - notePreRoll + chordOffsetFrames);
                ev.startFrame = newStart;
                ev.durationFrames = std::max<int64_t>(256, scheduledOff - newStart);
            }
        }

        shaped.insert(shaped.end(), notes.begin(), notes.end());
    }

    std::sort(shaped.begin(), shaped.end(), [](const ScheduledSeqNote& a, const ScheduledSeqNote& b) {
        if (a.startFrame != b.startFrame) return a.startFrame < b.startFrame;
        if (a.slot != b.slot) return a.slot < b.slot;
        return a.midiNote < b.midiNote;
    });
    return shaped;
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

    prepareLoadedSoundFontPresets(loaded);
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
    prepareLoadedSoundFontPresets(loaded);
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

struct ActiveSeqNote {
    int channel = 1;
    int midiNote = 60;
    int64_t offFrame = 0;
    int flags = 0;
    int64_t fpDuckStartFrame = 0;
    float fpDuckRemainingDb = 0.0f;
    bool hasPlayIndex = false;
    unsigned int playIndex = 0;
};

static bool triggerExpressiveChannelNoteOn(
    tsf* f,
    int channel,
    int key,
    float rawVelocity,
    const InstrumentFxProfile& prof,
    int flags,
    float extraDelaySec,
    StringsVoiceModTracker* modTracker,
    unsigned int* outPlayIndex,
    float glideFromSemitones = 0.0f
) {
    if (!f) return false;
    const bool isStrings = isStringsProfile(prof);
    if (isStrings) {
        enforceStringsChannelPolyphonyCap(f, channel, modTracker);
    }

    const float midiVel = std::clamp(rawVelocity, 0.05f, 1.0f);
    const unsigned int notePlayIdx = f->voicePlayIndex;
    const int ok = tsf_channel_note_on(f, channel, key, midiVel);
    if (!ok) return false;

    if (outPlayIndex) *outPlayIndex = notePlayIdx;

    const float extraDb = (rawVelocity > 1.002f)
        ? 20.0f * std::log10(std::min(rawVelocity, 1.26f))
        : 0.0f;

    if (f->voices && !isStrings && extraDb > 0.0f) {
        struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
        for (; v != vEnd; ++v) {
            if (v->playingPreset != -1 &&
                v->playIndex == notePlayIdx &&
                v->playingChannel == channel &&
                v->playingKey == key) {
                v->noteGainDB += extraDb;
            }
        }
        return true;
    }

    if (f->voices && isStrings) {
        const bool isCrispBow =
            (flags & (kSynthFlagCrispRelease | kSynthFlagFortepianoDuck | kSynthFlagCrispBowStart)) != 0 ||
            rawVelocity > 1.01f;
        const bool isLegatoBow =
            !isCrispBow && ((flags & (kSynthFlagLegatoEntry | kSynthFlagLegatoOverlap)) != 0);
        const bool isTightOnset = !isCrispBow && ((flags & kSynthFlagTightOnset) != 0);
        const bool isShortBow = (flags & kSynthFlagShortBow) != 0;
        const bool isMediumBow = (flags & kSynthFlagMediumBow) != 0;
        const float velT = std::clamp((rawVelocity - 0.15f) / 0.85f, 0.0f, 1.0f);

        float targetAttackSec = 0.24f;
        if (isCrispBow) {
            targetAttackSec = 0.045f;
        } else if (isLegatoBow) {
            if (isTightOnset) {
                targetAttackSec = 0.165f - 0.025f * velT;
            } else {
                targetAttackSec = isShortBow
                    ? (0.130f - 0.020f * velT)
                    : (isMediumBow ? (0.170f - 0.025f * velT) : (0.205f - 0.030f * velT));
            }
        } else if (isTightOnset) {
            targetAttackSec = isShortBow
                ? (0.150f - 0.025f * velT)
                : (isMediumBow ? (0.205f - 0.030f * velT) : (0.250f - 0.038f * velT));
        } else {
            targetAttackSec = isShortBow
                ? (0.165f - 0.025f * velT)
                : (isMediumBow ? (0.220f - 0.032f * velT) : (0.275f - 0.042f * velT));
        }
        const float initialCurveDb = isCrispBow ? -8.0f : (isLegatoBow ? -28.0f : -34.0f);

        const float noteFreqHz = 440.0f * std::pow(2.0f, (static_cast<float>(key) - 69.0f) / 12.0f);
        const float rawStartHz = std::clamp(std::max(1180.0f + 340.0f * velT, noteFreqHz * 1.92f), 1180.0f, 2400.0f);
        const float peakCutoffHz = std::clamp(std::max(2250.0f + 950.0f * velT, noteFreqHz * 3.20f), 2250.0f, 3850.0f);
        const float startCutoffHz = isCrispBow
            ? peakCutoffHz
            : (isLegatoBow ? (0.72f * rawStartHz + 0.28f * peakCutoffHz) : rawStartHz);
        const float normStartFc = std::clamp(startCutoffHz / std::max(8000.0f, f->outSampleRate), 0.02f, 0.485f);

        int layerIdx = 0;
        struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
        for (; v != vEnd; ++v) {
            if (v->playingPreset != -1 &&
                v->playIndex == notePlayIdx &&
                v->playingChannel == channel &&
                v->playingKey == key) {
                // Skip pre-loop marcato bow scrape on non-crisp notes and decorrelate loop phases across layers/notes
                if (!isCrispBow && v->loopEnd > v->loopStart + 4096) {
                    const uint32_t loopLen = v->loopEnd - v->loopStart;
                    const unsigned int hPos =
                        (static_cast<unsigned int>(key) * 2654435761u) ^
                        (static_cast<unsigned int>(notePlayIdx) * 2246822519u) ^
                        (static_cast<unsigned int>(layerIdx) * 1597334677u);
                    const float unitH = static_cast<float>(hPos & 0xFFFFu) / 65535.0f;
                    const float loopFrac = (layerIdx % 2 == 1)
                        ? (0.38f + 0.24f * unitH)
                        : (0.06f + 0.22f * unitH);
                    v->sourceSamplePosition = static_cast<double>(v->loopStart) +
                                              static_cast<double>(loopFrac) * static_cast<double>(loopLen);
                }

                // TSF amp envelope acts as a clean de-click 1.0 plateau; StringsVoiceModTracker drives S-curve & release
                v->ampenv.parameters.keynumToHold = 0.0f;
                v->ampenv.parameters.keynumToDecay = 0.0f;
                v->ampenv.parameters.delay = std::max(0.0f, extraDelaySec);
                v->ampenv.parameters.attack = isCrispBow ? 0.008f : 0.014f;
                v->ampenv.parameters.hold = 0.0f;
                v->ampenv.parameters.sustain = 1.0f;
                tsf_voice_envelope_nextsegment(&v->ampenv, TSF_SEGMENT_NONE, f->outSampleRate);
                if (extraDelaySec > 0.0f) {
                    v->modenv.parameters.delay += extraDelaySec;
                    tsf_voice_envelope_nextsegment(&v->modenv, TSF_SEGMENT_NONE, f->outSampleRate);
                }

                // Gentle Q (~0.595) dynamic warmth lowpass without resonant peak
                v->lowpass.QInv = 1.68;
                v->lowpass.z1 = 0.0;
                v->lowpass.z2 = 0.0;
                v->lowpass.active = TSF_TRUE;
                tsf_voice_lowpass_setup(&v->lowpass, normStartFc);
                v->noteGainDB += (extraDb + 1.65f + initialCurveDb);
                ++layerIdx;
            }
        }

        if (modTracker) {
            modTracker->onStringsNoteVoicesSpawned(
                f,
                notePlayIdx,
                channel,
                key,
                rawVelocity,
                flags,
                startCutoffHz,
                peakCutoffHz,
                targetAttackSec,
                initialCurveDb,
                glideFromSemitones
            );
        }
    }
    return true;
}

static void applyFortepianoVoiceAttenuation(
    tsf* f,
    int channel,
    int key,
    bool hasPlayIndex,
    unsigned int playIndex,
    float deltaDb
) {
    if (!f || !f->voices || deltaDb <= 0.0f) return;
    struct tsf_voice *v = f->voices, *vEnd = v + f->voiceNum;
    for (; v != vEnd; ++v) {
        if (v->playingPreset != -1 &&
            v->playingChannel == channel &&
            v->playingKey == key &&
            (!hasPlayIndex || v->playIndex == playIndex) &&
            v->ampenv.segment < TSF_SEGMENT_RELEASE) {
            v->noteGainDB -= deltaDb;
        }
    }
}

static void releaseExpressiveSeqNote(
    tsf* f,
    const ActiveSeqNote& active,
    const InstrumentFxProfile& prof,
    StringsVoiceModTracker* modTracker = nullptr
) {
    const bool isStrings = isStringsProfile(prof);
    bool targetSustainOn = isStrings ? true : prof.sustainPedal;
    float targetReleaseSec = isStrings
        ? resolveStringsReleaseSec(prof.sustainPedal, prof.sustainDurationSec)
        : (prof.sustainPedal ? prof.sustainDurationSec : 0.0f);

    if ((active.flags & kSynthFlagCrispRelease) != 0) {
        // Staccato / Staccatissimo / Marcato always damp crisply even when sustain pedal is ON
        targetSustainOn = true;
        targetReleaseSec = isStrings ? 0.140f : 0.045f;
    } else if (isStrings && (active.flags & kSynthFlagStringsCrossfade) != 0) {
        targetSustainOn = true;
        targetReleaseSec = prof.sustainPedal
            ? resolveStringsReleaseSec(true, prof.sustainDurationSec)
            : 0.98f;
    } else if ((active.flags & kSynthFlagPortatoRelease) != 0) {
        targetSustainOn = true;
        if (isStrings) {
            targetReleaseSec = prof.sustainPedal
                ? std::min(resolveStringsReleaseSec(true, prof.sustainDurationSec), 1.45f)
                : 1.10f;
        } else {
            targetReleaseSec = prof.sustainPedal
                ? std::min(prof.sustainDurationSec, 0.220f)
                : 0.120f;
        }
    }

    const float softFallback = isStrings ? resolveStringsReleaseSec(false, 0.0f) : 0.0f;
    if (active.hasPlayIndex) {
        releaseChannelPlayIndex(
            f,
            active.channel,
            active.midiNote,
            active.playIndex,
            targetSustainOn,
            targetReleaseSec,
            isStrings,
            targetReleaseSec > 0.0f ? targetReleaseSec : softFallback,
            modTracker,
            /*quickDamp=*/false
        );
    } else {
        releaseChannelKeyWithCustomSustain(
            f,
            active.channel,
            active.midiNote,
            targetSustainOn,
            targetReleaseSec,
            isStrings,
            targetReleaseSec > 0.0f ? targetReleaseSec : softFallback,
            modTracker,
            /*quickDamp=*/false
        );
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
    StringsVoiceModTracker modTracker;
    int sampleRate = 44100;

    std::vector<SynthNoteSpec> rawSeqNotes;
    std::vector<ScheduledSeqNote> seqNotes;
    std::vector<ActiveSeqNote> activeSeqNotes;
    size_t nextSeqIndex = 0;
    bool pendingSeekCatchup = false;
    int64_t loopLengthFrames = 0;
    bool loopEnabled = false;

    int64_t renderedLiveFrames = 0;
    int64_t lastLiveChordFrame[2] = {-1000000, -1000000};
    int64_t lastLiveKeyOffFrame[2] = {-1000000, -1000000};
    int liveChordCount[2] = {0, 0};
    int lastLiveNote[2] = {-1, -1};
    std::vector<int> liveHeldKeys[2];

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

    explicit Impl(int sr) : fxRack(sr), sampleRate(sr) {
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
        sampleRate = sr;
        if (!synth) {
            synth = cloneSharedSoundFontInstance();
            if (synth) {
                configureTsfInstance(synth, sr, slotProfiles[0], slotProfiles[1]);
            }
        }
    }

    void rebuildShapedSequence(int sr) {
        seqNotes = buildShapedSynthSequence(rawSeqNotes, slotProfiles[0], slotProfiles[1], sr);
    }

    void stopAllSequenceNotes() {
        if (synth) {
            dampChannelVoicesQuick(synth, 1, 0.22f, &modTracker);
            dampChannelVoicesQuick(synth, 3, 0.22f, &modTracker);
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
        while (nextSeqIndex < seqNotes.size() &&
               seqNotes[nextSeqIndex].startFrame < targetFrame &&
               seqNotes[nextSeqIndex].nominalStartFrame < targetFrame) {
            ++nextSeqIndex;
        }
        pendingSeekCatchup = true;
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
    const int seqCh = baseCh + 1;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->slotProfiles[idx] = profile;
    impl_->ensureSynthInitialized(sampleRate_);
    if (impl_->synth) {
        tsf_channel_note_off_all(impl_->synth, baseCh);
        tsf_channel_note_off_all(impl_->synth, seqCh);
        impl_->modTracker.resetChannel(baseCh);
        impl_->modTracker.resetChannel(seqCh);
        applySlotToChannels(impl_->synth, idx, profile);
    }
    impl_->liveHeldKeys[idx].clear();
    impl_->lastLiveChordFrame[idx] = -1000000;
    impl_->lastLiveKeyOffFrame[idx] = -1000000;
    impl_->liveChordCount[idx] = 0;
    impl_->lastLiveNote[idx] = -1;
    impl_->activeSeqNotes.erase(
        std::remove_if(
            impl_->activeSeqNotes.begin(),
            impl_->activeSeqNotes.end(),
            [seqCh](const ActiveSeqNote& n) { return n.channel == seqCh; }
        ),
        impl_->activeSeqNotes.end()
    );
    impl_->rebuildShapedSequence(sampleRate_);
    const int64_t curFrame = transportFrame_.load(std::memory_order_relaxed);
    impl_->nextSeqIndex = 0;
    while (impl_->nextSeqIndex < impl_->seqNotes.size() &&
           impl_->seqNotes[impl_->nextSeqIndex].startFrame < curFrame &&
           impl_->seqNotes[impl_->nextSeqIndex].nominalStartFrame < curFrame) {
        ++impl_->nextSeqIndex;
    }
    impl_->pendingSeekCatchup = true;
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
            const bool isStrings = isStringsProfile(impl_->slotProfiles[idx]);
            dampSustainingVoicesOnChannel(impl_->synth, baseCh, isStrings, &impl_->modTracker);
            dampSustainingVoicesOnChannel(impl_->synth, baseCh + 1, isStrings, &impl_->modTracker);
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
        const InstrumentFxProfile& prof = impl_->slotProfiles[idx];
        const bool isStrings = isStringsProfile(prof);
        const float softRelSec = resolveStringsReleaseSec(false, 0.0f);
        releaseChannelKeyWithCustomSustain(
            impl_->synth,
            liveCh,
            key,
            prof.sustainPedal,
            prof.sustainDurationSec,
            isStrings,
            isStrings ? 0.95f : softRelSec,
            &impl_->modTracker,
            /*quickDamp=*/false
        );

        auto& held = impl_->liveHeldKeys[idx];
        held.erase(std::remove(held.begin(), held.end(), key), held.end());

        float extraDelaySec = 0.0f;
        float liveGlideSemitones = 0.0f;
        int liveFlags = isStrings ? kSynthFlagTightOnset : 0;
        if (isStrings) {
            const int64_t nowFrame = impl_->renderedLiveFrames;
            const int64_t chordWin = std::max<int64_t>(64, static_cast<int64_t>(0.014 * sampleRate_));
            const int64_t legatoOffWin = static_cast<int64_t>(0.095 * sampleRate_);
            const bool sameChordWindow = (nowFrame - impl_->lastLiveChordFrame[idx]) <= chordWin;

            if (sameChordWindow) {
                ++impl_->liveChordCount[idx];
                extraDelaySec = std::clamp(impl_->liveChordCount[idx] * 0.0038f, 0.0038f, 0.010f);
            } else {
                const bool prevKeyHeld = !held.empty();
                const bool recentRelease = (nowFrame - impl_->lastLiveKeyOffFrame[idx]) <= legatoOffWin;
                if (prevKeyHeld || recentRelease) {
                    liveFlags |= kSynthFlagLegatoEntry;
                    const int prevKey = prevKeyHeld ? held.back() : impl_->lastLiveNote[idx];
                    const int diff = key - prevKey;
                    if (prevKey >= 0 && std::abs(diff) >= 1 && std::abs(diff) <= 9) {
                        const float mag = std::clamp(0.24f + 0.035f * static_cast<float>(std::abs(diff)), 0.26f, 0.45f);
                        liveGlideSemitones = (diff > 0) ? -mag : mag;
                    }
                }
                impl_->lastLiveChordFrame[idx] = nowFrame;
                impl_->liveChordCount[idx] = 0;
                impl_->lastLiveNote[idx] = key;
            }
        }
        held.push_back(key);

        triggerExpressiveChannelNoteOn(
            impl_->synth,
            liveCh,
            key,
            vel,
            prof,
            liveFlags,
            extraDelaySec,
            &impl_->modTracker,
            nullptr,
            liveGlideSemitones
        );
    }
}

void RealtimeMelodySynthEngine::liveNoteOff(int slot, int midiNote) {
    const int idx = (slot == 1) ? 1 : 0;
    const int liveCh = (idx == 1) ? 2 : 0;
    const int key = std::clamp(midiNote, 0, 127);
    std::lock_guard<std::mutex> lock(impl_->mutex);
    auto& held = impl_->liveHeldKeys[idx];
    held.erase(std::remove(held.begin(), held.end(), key), held.end());
    impl_->lastLiveKeyOffFrame[idx] = impl_->renderedLiveFrames;
    if (impl_->synth) {
        const InstrumentFxProfile& prof = impl_->slotProfiles[idx];
        const bool isStrings = isStringsProfile(prof);
        releaseChannelKeyWithCustomSustain(
            impl_->synth,
            liveCh,
            key,
            prof.sustainPedal,
            prof.sustainDurationSec,
            isStrings,
            resolveStringsReleaseSec(false, 0.0f),
            &impl_->modTracker,
            /*quickDamp=*/false
        );
    }
}

void RealtimeMelodySynthEngine::allNotesOff() {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (impl_->synth) {
        for (int ch = 0; ch < 4; ++ch) {
            const int slotIdx = (ch >= 2) ? 1 : 0;
            if (isStringsProfile(impl_->slotProfiles[slotIdx])) {
                dampChannelVoicesQuick(impl_->synth, ch, 0.48f, &impl_->modTracker);
            } else {
                tsf_channel_note_off_all(impl_->synth, ch);
                impl_->modTracker.resetChannel(ch);
            }
        }
    }
    impl_->activeSeqNotes.clear();
    impl_->liveHeldKeys[0].clear();
    impl_->liveHeldKeys[1].clear();
    impl_->lastLiveChordFrame[0] = -1000000;
    impl_->lastLiveChordFrame[1] = -1000000;
    impl_->lastLiveKeyOffFrame[0] = -1000000;
    impl_->lastLiveKeyOffFrame[1] = -1000000;
    impl_->liveChordCount[0] = 0;
    impl_->liveChordCount[1] = 0;
}

void RealtimeMelodySynthEngine::setSequence(
    const std::vector<SynthNoteSpec>& notes,
    int64_t totalLengthFrames,
    bool loopEnabled
) {
    std::vector<SynthNoteSpec> sorted = notes;
    std::sort(sorted.begin(), sorted.end(), [](const SynthNoteSpec& a, const SynthNoteSpec& b) {
        if (a.startFrame != b.startFrame) return a.startFrame < b.startFrame;
        return a.midiNote < b.midiNote;
    });

    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->rawSeqNotes = std::move(sorted);
    impl_->rebuildShapedSequence(sampleRate_);
    impl_->loopLengthFrames = std::max<int64_t>(0, totalLengthFrames);
    impl_->loopEnabled = loopEnabled;

    const int64_t curFrame = transportFrame_.load(std::memory_order_relaxed);
    const bool isCurrentlyPlaying = transportPlaying_.load(std::memory_order_relaxed);
    impl_->nextSeqIndex = 0;
    while (impl_->nextSeqIndex < impl_->seqNotes.size() &&
           impl_->seqNotes[impl_->nextSeqIndex].startFrame < curFrame &&
           (isCurrentlyPlaying || impl_->seqNotes[impl_->nextSeqIndex].nominalStartFrame < curFrame)) {
        ++impl_->nextSeqIndex;
    }
    if (!isCurrentlyPlaying) {
        impl_->pendingSeekCatchup = true;
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
                        *it,
                        impl_->slotProfiles[slotIdx],
                        &impl_->modTracker
                    );
                    it = impl_->activeSeqNotes.erase(it);
                } else {
                    if (it->fpDuckRemainingDb > 0.01f && endFrame > it->fpDuckStartFrame) {
                        const float stepDb = std::min(
                            it->fpDuckRemainingDb,
                            160.0f * (static_cast<float>(step) / static_cast<float>(sampleRate_))
                        );
                        applyFortepianoVoiceAttenuation(
                            impl_->synth,
                            it->channel,
                            it->midiNote,
                            it->hasPlayIndex,
                            it->playIndex,
                            stepDb
                        );
                        it->fpDuckRemainingDb -= stepDb;
                    }
                    ++it;
                }
            }

            // 2. Trigger note-ons starting inside [curFrame, endFrame)
            while (impl_->nextSeqIndex < impl_->seqNotes.size()) {
                const ScheduledSeqNote& ev = impl_->seqNotes[impl_->nextSeqIndex];
                if (ev.startFrame >= endFrame) break;
                const bool canTriggerPreRolledCatchup =
                    impl_->pendingSeekCatchup && ev.startFrame < curFrame && ev.nominalStartFrame >= curFrame;
                if (ev.startFrame >= curFrame || canTriggerPreRolledCatchup) {
                    const int slotIdx = (ev.slot == 1) ? 1 : 0;
                    const int seqCh = (slotIdx == 1) ? 3 : 1;
                    const int key = std::clamp(ev.midiNote, 0, 127);
                    const float vel = std::clamp(ev.velocity, 0.05f, 1.25f);
                    const InstrumentFxProfile& prof = impl_->slotProfiles[slotIdx];
                    const bool isStrings = isStringsProfile(prof);
                    const int triggerFlags = canTriggerPreRolledCatchup
                        ? (ev.flags | kSynthFlagTightOnset)
                        : ev.flags;
                    if (!isStrings) {
                        releaseChannelKeyWithCustomSustain(
                            impl_->synth,
                            seqCh,
                            key,
                            prof.sustainPedal,
                            prof.sustainDurationSec,
                            /*isStrings=*/false,
                            0.0f
                        );
                    }
                    unsigned int spawnedPlayIdx = 0;
                    const bool spawnedOk = triggerExpressiveChannelNoteOn(
                        impl_->synth,
                        seqCh,
                        key,
                        vel,
                        prof,
                        triggerFlags,
                        0.0f,
                        &impl_->modTracker,
                        &spawnedPlayIdx,
                        ev.glideFromSemitones
                    );
                    const bool isFp = (triggerFlags & kSynthFlagFortepianoDuck) != 0;
                    const int64_t scheduledOff = ev.startFrame + std::max<int64_t>(256, ev.durationFrames);
                    impl_->activeSeqNotes.push_back(ActiveSeqNote{
                        seqCh,
                        key,
                        std::max<int64_t>(curFrame + 256, scheduledOff),
                        triggerFlags,
                        std::max<int64_t>(curFrame, ev.startFrame) + static_cast<int64_t>(0.065 * sampleRate_),
                        isFp ? 9.5f : 0.0f,
                        spawnedOk,
                        spawnedPlayIdx
                    });
                }
                ++impl_->nextSeqIndex;
            }
            impl_->pendingSeekCatchup = false;

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
                    // Stop cleanly at end of score when not live-recording with metronome,
                    // letting Strings & sustained voices bloom through their natural soft release.
                    transportPlaying_.store(false, std::memory_order_relaxed);
                    nextTransport = 0;
                    for (const auto& active : impl_->activeSeqNotes) {
                        const int slotIdx = (active.channel == 3) ? 1 : 0;
                        releaseExpressiveSeqNote(
                            impl_->synth,
                            active,
                            impl_->slotProfiles[slotIdx],
                            &impl_->modTracker
                        );
                    }
                    impl_->activeSeqNotes.clear();
                    impl_->nextSeqIndex = 0;
                    impl_->clickPosSamples = -1;
                    impl_->reindexMetronomeForFrame(0);
                }
            }
            transportFrame_.store(nextTransport, std::memory_order_relaxed);
        }

        // Update delayed vibrato / pitch modulation before rendering this 64-frame block
        impl_->modTracker.processSubChunk(impl_->synth, step);

        // Render polyphonic SoundFont voices for this sub-chunk
        tsf_render_float(impl_->synth, chunkPtr, step, 0);
        sweepSilentVoices(impl_->synth);
        impl_->renderedLiveFrames += step;

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
    StringsVoiceModTracker modTracker;
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

    std::vector<ScheduledSeqNote> sorted = buildShapedSynthSequence(
        notes,
        primaryProfile,
        secondaryProfile,
        sr
    );

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

    constexpr int kBlock = 64;
    std::vector<float> interleaved(kBlock * 2, 0.0f);

    int64_t cursor = 0;
    int progressTick = 0;
    while (cursor < totalFrames) {
        if (++progressTick % 256 == 0 && onProgress) {
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
                    *it,
                    prof,
                    &modTracker
                );
                it = activeNotes.erase(it);
            } else {
                if (it->fpDuckRemainingDb > 0.01f && endFrame > it->fpDuckStartFrame) {
                    const float stepDb = std::min(
                        it->fpDuckRemainingDb,
                        160.0f * (static_cast<float>(step) / static_cast<float>(sr))
                    );
                    applyFortepianoVoiceAttenuation(
                        synth,
                        it->channel,
                        it->midiNote,
                        it->hasPlayIndex,
                        it->playIndex,
                        stepDb
                    );
                    it->fpDuckRemainingDb -= stepDb;
                }
                ++it;
            }
        }

        while (nextIdx < sorted.size()) {
            const ScheduledSeqNote& ev = sorted[nextIdx];
            if (ev.startFrame >= endFrame) break;
            if (ev.startFrame >= cursor) {
                const bool isSlot1 = (ev.slot == 1);
                const int seqCh = isSlot1 ? 3 : 1;
                const InstrumentFxProfile& prof = isSlot1 ? secondaryProfile : primaryProfile;
                const int key = std::clamp(ev.midiNote, 0, 127);
                const float vel = std::clamp(ev.velocity, 0.05f, 1.25f);
                const bool isStrings = isStringsProfile(prof);
                if (!isStrings) {
                    releaseChannelKeyWithCustomSustain(
                        synth,
                        seqCh,
                        key,
                        prof.sustainPedal,
                        prof.sustainDurationSec,
                        /*isStrings=*/false,
                        0.0f
                    );
                }
                unsigned int spawnedPlayIdx = 0;
                const bool spawnedOk = triggerExpressiveChannelNoteOn(
                    synth,
                    seqCh,
                    key,
                    vel,
                    prof,
                    ev.flags,
                    0.0f,
                    &modTracker,
                    &spawnedPlayIdx,
                    ev.glideFromSemitones
                );
                const bool isFp = (ev.flags & kSynthFlagFortepianoDuck) != 0;
                activeNotes.push_back(ActiveSeqNote{
                    seqCh,
                    key,
                    ev.startFrame + std::max<int64_t>(256, ev.durationFrames),
                    ev.flags,
                    ev.startFrame + static_cast<int64_t>(0.065 * sr),
                    isFp ? 9.5f : 0.0f,
                    spawnedOk,
                    spawnedPlayIdx
                });
            }
            ++nextIdx;
        }

        modTracker.processSubChunk(synth, step);
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
