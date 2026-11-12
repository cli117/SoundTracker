package com.soundtracker.app.melody

import com.soundtracker.app.dsp.NativeSynthNote
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

const val MELODY_SAMPLE_RATE = 44100
const val STEPS_PER_BEAT = 4          // 1 quarter-note = 4 sixteenth-note steps

// Exact integer PPQN rhythmic grid (`240 = 16 * 3 * 5` per quarter note) so standard notes, dotted notes,
// Duplets (2:3), Triplets (3:2), Quintuplets (5:4), Sextuplets (6:4), and any N/2, N/4, N/8, N/16
// time signature all resolve to exact integers with zero floating-point rounding drift!
const val TICKS_PER_BEAT = 240                             // 240 ticks = 1 quarter note (♩)
const val TICKS_PER_WHOLE_NOTE = TICKS_PER_BEAT * 4        // 960 ticks = 1 whole note (𝅝)
const val TICKS_PER_STEP = TICKS_PER_BEAT / STEPS_PER_BEAT // 60 ticks  = 1 sixteenth note ( sixteenth )
const val MAX_MELODY_BARS = 64

/**
 * Configurable musical Time Signature (`拍号`), e.g. `4/4`, `3/4`, `2/4`, `6/8`, `12/8`, `5/4`, `7/8`, or custom `N/D`.
 */
data class TimeSignature(
    val numerator: Int = 4,
    val denominator: Int = 4
) {
    val safeNumerator: Int get() = numerator.coerceIn(1, 16)
    val safeDenominator: Int
        get() = when (denominator) {
            2, 4, 8, 16 -> denominator
            else -> 4
        }

    val label: String get() = "$safeNumerator/$safeDenominator"

    /** Ticks of a single denominator beat unit (`2`->480, `4`->240, `8`->120, `16`->60). */
    val unitTicks: Int get() = TICKS_PER_WHOLE_NOTE / safeDenominator

    /** Sixteenth-note steps per denominator beat unit (`2`->8, `4`->4, `8`->2, `16`->1). */
    val unitSteps: Int get() = (unitTicks / TICKS_PER_STEP).coerceAtLeast(1)

    /** Exact total ticks in one full measure of this time signature. */
    val ticksPerBar: Int get() = safeNumerator * unitTicks

    /** Exact total 1/16th steps in one full measure of this time signature. */
    val stepsPerBar: Int get() = (ticksPerBar / TICKS_PER_STEP).coerceAtLeast(1)

    /** Measure length expressed in quarter-note beats (e.g. `4/4`->4.0, `3/4`->3.0, `6/8`->3.0, `3/8`->1.5). */
    val quarterBeatsPerBar: Float get() = ticksPerBar.toFloat() / TICKS_PER_BEAT.toFloat()

    fun sanitized(): TimeSignature = TimeSignature(safeNumerator, safeDenominator)

    /**
     * Classical beaming bucket index inside a measure so simple meters beam by quarter note
     * while compound meters (`6/8`, `9/8`, `12/8`, `3/8`) beam in dotted-quarter groups of 3 eighth notes
     * and asymmetric meters (`5/8`, `7/8`) beam cleanly as `3+2` / `3+2+2`.
     */
    fun beamBucketForTick(tickInBar: Int): Int {
        val t = tickInBar.coerceAtLeast(0)
        return when (safeDenominator) {
            8 -> when {
                safeNumerator % 3 == 0 -> t / 360 // 3 eighth notes = 360 ticks
                safeNumerator == 5 -> if (t < 360) 0 else 1
                safeNumerator == 7 -> when {
                    t < 360 -> 0
                    t < 600 -> 1
                    else -> 2
                }
                else -> t / 240
            }
            16 -> if (safeNumerator >= 6 && safeNumerator % 3 == 0) t / 180 else t / 240
            else -> t / TICKS_PER_BEAT
        }
    }

    /**
     * Step boundary for grouping automatic gap rests so `4/4` splits at the half-bar (`8` steps),
     * compound `6/8` / `9/8` / `12/8` split at dotted-quarter beats (`6` steps),
     * and `3/4` / `2/4` / `5/4` split per beat (`4` steps) without confusing cross-beat half rests.
     */
    val restGroupSteps: Int
        get() = when {
            safeDenominator == 4 && safeNumerator == 4 -> 8
            safeDenominator == 2 && safeNumerator == 2 -> 8
            safeDenominator == 8 && safeNumerator % 3 == 0 -> 6
            else -> unitSteps
        }

    data class PresetOption(
        val timeSignature: TimeSignature,
        val title: String,
        val subtitle: String
    )

    companion object {
        val FOUR_FOUR = TimeSignature(4, 4)
        val VALID_DENOMINATORS = listOf(2, 4, 8, 16)

        val PRESETS: List<PresetOption> = listOf(
            PresetOption(TimeSignature(4, 4), "4/4 四四拍", "强·弱·次强·弱 · 流行/摇滚/通用"),
            PresetOption(TimeSignature(3, 4), "3/4 三四拍", "强·弱·弱 · 圆舞曲/华尔兹/抒情"),
            PresetOption(TimeSignature(2, 4), "2/4 二四拍", "强·弱 · 进行曲/轻快民谣"),
            PresetOption(TimeSignature(6, 8), "6/8 六八拍", "复二拍子(3+3) · 摇篮曲/慢摇滚/R&B"),
            PresetOption(TimeSignature(2, 2), "2/2 二二拍", "二二分拍 · 开阔律动进行"),
            PresetOption(TimeSignature(3, 8), "3/8 三八拍", "轻快单三拍子 · 舞曲小品"),
            PresetOption(TimeSignature(9, 8), "9/8 九八拍", "复三拍子(3+3+3) · 抒情爵士/古典"),
            PresetOption(TimeSignature(12, 8), "12/8 十二八拍", "复四拍子 · 慢蓝调 Blues / 灵魂乐"),
            PresetOption(TimeSignature(5, 4), "5/4 五四拍", "混合五拍子 · 现代爵士 (如 Take Five)"),
            PresetOption(TimeSignature(7, 8), "7/8 七八拍", "不对称复合拍(3+2+2) · 前卫律动")
        )
    }
}

// Virtual full-range piano span: C1 (MIDI 24) .. C8 (MIDI 108) = 7 octaves + top C8 = 50 white keys
const val PIANO_MIN_MIDI = 24
const val PIANO_MAX_MIDI = 108
const val TOTAL_VIRTUAL_WHITE_KEYS = 50
const val DEFAULT_REST_MIDI = 71      // B4 (middle line of treble staff, silent)
const val DEFAULT_SUSTAIN_DURATION_SEC = 0.850f

enum class StudioInstrument(
    val displayName: String,
    val shortName: String,
    val subtitle: String,
    val bank: Int,
    val presetNumber: Int,
    val defaultOctave: Int,
    val gainDb: Float,
    val defaultReverb: Float,
    val chorusDepth: Float
) {
    GRAND_PIANO(
        displayName = "三角钢琴",
        shortName = "钢琴",
        subtitle = "立体声音乐厅三角大钢琴 · 适合主旋律与和弦",
        bank = 0,
        presetNumber = 0,
        defaultOctave = 4,
        gainDb = 1.2f,
        defaultReverb = 0.30f,
        chorusDepth = 0.00f
    ),
    ELECTRIC_PIANO(
        displayName = "温暖电钢",
        shortName = "电钢",
        subtitle = "经典 Rhodes 电钢琴 · 自带微合唱空间感，适合流行/R&B",
        bank = 0,
        presetNumber = 4,
        defaultOctave = 4,
        gainDb = 1.0f,
        defaultReverb = 0.28f,
        chorusDepth = 0.22f
    ),
    STEEL_GUITAR(
        displayName = "民谣吉他",
        shortName = "民谣吉他",
        subtitle = "清亮钢弦木吉他 · 颗粒感清晰，适合节奏与拨弦",
        bank = 0,
        presetNumber = 25,
        defaultOctave = 4,
        gainDb = 4.8f,
        defaultReverb = 0.24f,
        chorusDepth = 0.08f
    ),
    ORCHESTRAL_HARP(
        displayName = "管弦竖琴",
        shortName = "竖琴",
        subtitle = "晶莹饱满交响竖琴 · 滑奏琶音与梦幻伴奏极佳",
        bank = 0,
        presetNumber = 46,
        defaultOctave = 4,
        gainDb = -1.2f,
        defaultReverb = 0.38f,
        chorusDepth = 0.08f
    ),
    MUSIC_BOX(
        displayName = "八音盒",
        shortName = "八音盒",
        subtitle = "清脆空灵金属八音盒 · 适合前奏与间奏点缀",
        bank = 0,
        presetNumber = 10,
        defaultOctave = 5,
        gainDb = 1.0f,
        defaultReverb = 0.38f,
        chorusDepth = 0.10f
    ),
    MARIMBA(
        displayName = "马林巴琴",
        shortName = "马林巴",
        subtitle = "温润木质打击乐器 · 律动清脆温暖",
        bank = 0,
        presetNumber = 12,
        defaultOctave = 4,
        gainDb = -3.8f,
        defaultReverb = 0.26f,
        chorusDepth = 0.00f
    ),
    STRINGS_ENSEMBLE(
        displayName = "温暖弦乐",
        shortName = "弦乐群",
        subtitle = "饱满管弦乐合奏弦乐 · 适合长音铺底与副歌烘托",
        bank = 0,
        presetNumber = 48,
        defaultOctave = 4,
        gainDb = -2.2f,
        defaultReverb = 0.42f,
        chorusDepth = 0.18f
    ),
    SOLO_FLUTE(
        displayName = "长笛",
        shortName = "长笛",
        subtitle = "通透悠扬木管长笛 · 适合清晰柔和的主旋律",
        bank = 0,
        presetNumber = 73,
        defaultOctave = 5,
        gainDb = -1.8f,
        defaultReverb = 0.36f,
        chorusDepth = 0.08f
    ),
    ALTO_SAX(
        displayName = "萨克斯",
        shortName = "萨克斯",
        subtitle = "醇厚中音萨克斯 · 适合爵士与流行独奏段落",
        bank = 0,
        presetNumber = 65,
        defaultOctave = 4,
        gainDb = 0.5f,
        defaultReverb = 0.32f,
        chorusDepth = 0.06f
    )
}

enum class MelodyInputMode(val label: String) {
    STEP_INPUT("步进打谱"),
    LIVE_RECORD("实时弹录")
}

/**
 * Glissando-aware keyboard anti-mistouch sensitivity level (`防误触等级`).
 */
enum class TouchGuardMode(
    val shortLabel: String,
    val title: String,
    val subtitle: String
) {
    OFF(
        shortLabel = "关",
        title = "关闭防误触",
        subtitle = "原始零死区灵敏触控，手指跨过键缝 1px 立即切音"
    ),
    STANDARD(
        shortLabel = "标准",
        title = "标准防误触（推荐）",
        subtitle = "抑制快弹落指/抬指指腹滚动与黑键滑落，横滑约半键即可顺滑刮奏"
    ),
    STRICT(
        shortLabel = "强",
        title = "强力防误触（高速弹录）",
        subtitle = "更大键缝防抖死区 + 严格黑白防串层 + 过滤实时弹录极短擦音，横向扫键仍可刮奏"
    )
}

/**
 * Persisted keyboard touch-guard configuration (`防误触配置`) shared across single/dual decks
 * and Live Record (`实时弹录`) ghost-graze filtering.
 */
data class PianoTouchGuardConfig(
    val mode: TouchGuardMode = TouchGuardMode.STANDARD,
    val glissandoSamePlaneLock: Boolean = true,
    val lockChromeWhilePlaying: Boolean = true
) {
    val isEnabled: Boolean get() = mode != TouchGuardMode.OFF
}

/**
 * Standard musical note durations for step-input scoring, ordered from shortest (16th) to longest (Whole)
 * with exact denominator & augmentation-dot metadata for custom vector note/rest glyph rendering.
 */
enum class NoteDurationPreset(
    val label: String,
    val restLabel: String,
    val steps: Int,
    val baseDenom: Int,
    val isDotted: Boolean
) {
    SIXTEENTH(
        label = "十六分音符",
        restLabel = "十六分休止符",
        steps = 1,
        baseDenom = 16,
        isDotted = false
    ),
    EIGHTH(
        label = "八分音符",
        restLabel = "八分休止符",
        steps = 2,
        baseDenom = 8,
        isDotted = false
    ),
    DOTTED_EIGHTH(
        label = "附点八分音符",
        restLabel = "附点八分休止符",
        steps = 3,
        baseDenom = 8,
        isDotted = true
    ),
    QUARTER(
        label = "四分音符",
        restLabel = "四分休止符",
        steps = 4,
        baseDenom = 4,
        isDotted = false
    ),
    DOTTED_QUARTER(
        label = "附点四分音符",
        restLabel = "附点四分休止符",
        steps = 6,
        baseDenom = 4,
        isDotted = true
    ),
    HALF(
        label = "二分音符",
        restLabel = "二分休止符",
        steps = 8,
        baseDenom = 2,
        isDotted = false
    ),
    DOTTED_HALF(
        label = "附点二分音符",
        restLabel = "附点二分休止符",
        steps = 12,
        baseDenom = 2,
        isDotted = true
    ),
    WHOLE(
        label = "全音符",
        restLabel = "全休止符",
        steps = 16,
        baseDenom = 1,
        isDotted = false
    );

    val ticks: Int get() = steps * TICKS_PER_STEP
    val beats: Float get() = steps.toFloat() / STEPS_PER_BEAT.toFloat()
}

/**
 * Musical Tuplet (`连音`) presets supporting Duplets (二连音 2:3), Triplets (三连音 3:2),
 * Quintuplets (五连音 5:4), and Sextuplets (六连音 6:4).
 */
enum class TupletPreset(
    val label: String,
    val shortLabel: String,
    val menuSubtitle: String,
    val count: Int,
    val normalCount: Int,
    val spanTicks: Int,
    val writtenBaseDenom: Int
) {
    TRIPLET_EIGHTH(
        label = "八分三连音",
        shortLabel = "三连音",
        menuSubtitle = "1拍均分3音 · 最常用三连音",
        count = 3,
        normalCount = 2,
        spanTicks = TICKS_PER_BEAT,             // 240 ticks (1 beat), 80 ticks each
        writtenBaseDenom = 8
    ),
    TRIPLET_QUARTER(
        label = "四分三连音",
        shortLabel = "四分三连音",
        menuSubtitle = "2拍均分3音 · 宽广大三连音",
        count = 3,
        normalCount = 2,
        spanTicks = TICKS_PER_BEAT * 2,         // 480 ticks (2 beats), 160 ticks each
        writtenBaseDenom = 4
    ),
    TRIPLET_SIXTEENTH(
        label = "十六分三连音",
        shortLabel = "十六分三连音",
        menuSubtitle = "半拍均分3音 · 快速装饰连音",
        count = 3,
        normalCount = 2,
        spanTicks = TICKS_PER_BEAT / 2,         // 120 ticks (0.5 beat), 40 ticks each
        writtenBaseDenom = 16
    ),
    SEXTUPLET_SIXTEENTH(
        label = "十六分六连音",
        shortLabel = "六连音",
        menuSubtitle = "1拍均分6音 · 华彩跑动六连音",
        count = 6,
        normalCount = 4,
        spanTicks = TICKS_PER_BEAT,             // 240 ticks (1 beat), 40 ticks each
        writtenBaseDenom = 16
    ),
    SEXTUPLET_EIGHTH(
        label = "八分六连音",
        shortLabel = "八分六连音",
        menuSubtitle = "2拍均分6音 · 流动分解六连音",
        count = 6,
        normalCount = 4,
        spanTicks = TICKS_PER_BEAT * 2,         // 480 ticks (2 beats), 80 ticks each
        writtenBaseDenom = 8
    ),
    DUPLET_EIGHTH(
        label = "八分二连音 (2:3)",
        shortLabel = "二连音(1.5拍)",
        menuSubtitle = "附点四分(6/8单大拍)均分2音",
        count = 2,
        normalCount = 3,
        spanTicks = (TICKS_PER_BEAT * 3) / 2,   // 360 ticks (1.5 beats), 180 ticks each
        writtenBaseDenom = 8
    ),
    DUPLET_QUARTER(
        label = "四分二连音 (2:3)",
        shortLabel = "二连音(3拍)",
        menuSubtitle = "3拍(附点二分)均分2音",
        count = 2,
        normalCount = 3,
        spanTicks = TICKS_PER_BEAT * 3,         // 720 ticks (3 beats), 360 ticks each
        writtenBaseDenom = 4
    ),
    QUINTUPLET_SIXTEENTH(
        label = "十六分五连音",
        shortLabel = "五连音",
        menuSubtitle = "1拍均分5音 · 现代律动五连音",
        count = 5,
        normalCount = 4,
        spanTicks = TICKS_PER_BEAT,             // 240 ticks (1 beat), 48 ticks each
        writtenBaseDenom = 16
    );

    val noteTicks: Int get() = spanTicks / count
    val restLabel: String get() = "${shortLabel}休止符"
}

/**
 * Global Swing (`摇摆律动`) preset.
 * Following standard musical engraving practice, sheet music stays in clean straight notation
 * with a Swing tempo marking at the top, while real-time playback (`试听`) and offline `.wav` export
 * (`生成音轨`) apply exact swing timing to straight/syncopated subdivisions (leaving explicit tuplets untouched).
 */
enum class SwingPreset(
    val label: String,
    val menuSubtitle: String,
    val shortBadge: String,
    val scoreMarking: String?,
    val ratio: Float,
    val cellBeats: Double
) {
    OFF(
        label = "平直节拍 (无 Swing)",
        menuSubtitle = "标准 1:1 均分八分/十六分音符",
        shortBadge = "Swing:关",
        scoreMarking = null,
        ratio = 0.50f,
        cellBeats = 1.0
    ),
    LIGHT_8TH(
        label = "轻柔摇摆 (58%)",
        menuSubtitle = "流行 / 抒情爵士 · 微摇摆律动",
        shortBadge = "Swing 58%",
        scoreMarking = "Light Swing (58%)",
        ratio = 0.58f,
        cellBeats = 1.0
    ),
    CLASSIC_8TH(
        label = "经典三连音摇摆 (67%)",
        menuSubtitle = "标准爵士 / 蓝调 · 2:1 三连音摇摆",
        shortBadge = "Swing 67%",
        scoreMarking = "Swing (♪ = ♩³♪ · 67%)",
        ratio = 2f / 3f,
        cellBeats = 1.0
    ),
    HEAVY_8TH(
        label = "重度夏佛摇摆 (72%)",
        menuSubtitle = "强律动 Shuffle / 摇滚蓝调",
        shortBadge = "Swing 72%",
        scoreMarking = "Heavy Shuffle (72%)",
        ratio = 0.72f,
        cellBeats = 1.0
    ),
    SIXTEENTH_62(
        label = "十六分 R&B 律动 (62%)",
        menuSubtitle = "Funk / R&B / City Pop 十六分摇摆",
        shortBadge = "16th Swing",
        scoreMarking = "16th Swing (62%)",
        ratio = 0.62f,
        cellBeats = 0.5
    );

    val isEnabled: Boolean get() = this != OFF

    fun warpBeat(rawBeat: Double, barQuarterBeats: Double = 4.0): Double {
        if (!isEnabled || rawBeat <= 0.0) return rawBeat.coerceAtLeast(0.0)
        val safeBarBeats = barQuarterBeats.coerceAtLeast(0.25)
        val barIdx = floor(rawBeat / safeBarBeats)
        val inBarBeat = (rawBeat - barIdx * safeBarBeats).coerceAtLeast(0.0)
        val r = ratio.toDouble().coerceIn(0.50, 0.80)
        val cellIndex = floor(inBarBeat / cellBeats)
        val frac = ((inBarBeat - cellIndex * cellBeats) / cellBeats).coerceIn(0.0, 1.0)
        val warpedFrac = if (frac <= 0.5) {
            frac * (r / 0.5)
        } else {
            r + (frac - 0.5) * ((1.0 - r) / 0.5)
        }
        return barIdx * safeBarBeats + (cellIndex + warpedFrac) * cellBeats
    }
}

enum class ReverbPreset(val label: String, val multiplier: Float) {
    DRY("干爽近场", 0.15f),
    ROOM("温暖房间", 1.0f),
    HALL("音乐厅", 1.55f)
}

/**
 * Exact Tuplet grouping metadata attached to a [MelodyScoreNote] so both playback and
 * 5-line staff engraving render crisp beams and classical `┌ 2 ┐`, `┌ 3 ┐`, `┌ 5 ┐`, `┌ 6 ┐` brackets.
 */
data class NoteTupletTag(
    val count: Int,
    val normalCount: Int,
    val writtenBaseDenom: Int,
    val groupStartTick: Int,
    val spanTicks: Int
) {
    val noteTicks: Int get() = (spanTicks / count.coerceAtLeast(1)).coerceAtLeast(1)
    val groupKey: String get() = "${groupStartTick}_${spanTicks}_${count}_${writtenBaseDenom}"
}

data class MelodyScoreNote(
    val id: String = UUID.randomUUID().toString(),
    val midiNote: Int,
    val startBeat: Float,     // In quarter-note beats (0.0 = Bar 1 Beat 1; 0.25 = one 1/16 step)
    val durationBeats: Float, // e.g. 1.0 = quarter note, 1/3f = eighth triplet
    val velocity: Float = 0.85f,
    val keyboardSlot: Int = 0, // 0 = Primary Keyboard, 1 = Secondary Keyboard
    val isRest: Boolean = false, // Explicit silent musical rest on the staff
    val startTickExact: Int? = null,
    val durationTicksExact: Int? = null,
    val tuplet: NoteTupletTag? = null,
    val slurGroupId: String? = null,
    val articulation: NoteArticulation? = null,
    val accent: NoteAccent? = null,
    val hasFermata: Boolean = false
) {
    val endBeat: Float get() = startBeat + durationBeats
    val resolvedStartTick: Int
        get() = startTickExact ?: (startBeat * TICKS_PER_BEAT).roundToInt().coerceAtLeast(0)
    val resolvedDurationTicks: Int
        get() = durationTicksExact ?: (durationBeats * TICKS_PER_BEAT).roundToInt().coerceAtLeast(1)
    val resolvedEndTick: Int
        get() = resolvedStartTick + resolvedDurationTicks
}

data class MelodyProjectSummary(
    val id: String,
    val title: String,
    val keySignatureLabel: String,
    val timeSignatureLabel: String,
    val bpm: Int,
    val totalBars: Int,
    val pitchedNoteCount: Int,
    val updatedAtMs: Long
)

data class MelodySnapshot(
    val notes: List<MelodyScoreNote>,
    val cursorTick: Int,
    val totalBars: Int,
    val scoreTitle: String = "未命名乐谱",
    val timeSignature: TimeSignature = TimeSignature.FOUR_FOUR,
    val initialKeySignature: KeySignature = KeySignature.C_MAJOR,
    val keySignatureEvents: List<ScoreKeySignatureEvent> = emptyList(),
    val bpm: Int = 120,
    val swingPreset: SwingPreset = SwingPreset.OFF,
    val instrument: StudioInstrument = StudioInstrument.GRAND_PIANO,
    val secondaryInstrument: StudioInstrument = StudioInstrument.GRAND_PIANO,
    val dualKeyboardEnabled: Boolean = false,
    val reverbPreset: ReverbPreset = ReverbPreset.ROOM,
    val sustainPedal: Boolean = false,
    val secondarySustainPedal: Boolean = false,
    val sustainDurationSec: Float = DEFAULT_SUSTAIN_DURATION_SEC,
    val secondarySustainDurationSec: Float = DEFAULT_SUSTAIN_DURATION_SEC,
    val dynamicMarks: List<ScoreDynamicMark> = emptyList(),
    val hairpins: List<ScoreHairpin> = emptyList(),
    val tempoEvents: List<ScoreTempoEvent> = emptyList()
)

data class MelodyStudioUiState(
    val scoreTitle: String = "未命名乐谱",
    val instrument: StudioInstrument = StudioInstrument.GRAND_PIANO,
    val secondaryInstrument: StudioInstrument = StudioInstrument.GRAND_PIANO,
    val dualKeyboardEnabled: Boolean = false,
    val collapseScoreInDualMode: Boolean = false,
    val primaryScrollWhiteKey: Float = 21f,   // C4 (white key index 21 out of 0..49)
    val secondaryScrollWhiteKey: Float = 14f, // C3 (1 octave below primary by default)
    val touchGuardConfig: PianoTouchGuardConfig = PianoTouchGuardConfig(),
    val inputMode: MelodyInputMode = MelodyInputMode.STEP_INPUT,
    val timeSignature: TimeSignature = TimeSignature.FOUR_FOUR,
    val initialKeySignature: KeySignature = KeySignature.C_MAJOR,
    val keySignatureEvents: List<ScoreKeySignatureEvent> = emptyList(),
    val bpm: Int = 120,
    val swingPreset: SwingPreset = SwingPreset.OFF,
    val totalBars: Int = 4,
    val noteDuration: NoteDurationPreset = NoteDurationPreset.QUARTER,
    val activeTuplet: TupletPreset? = null,
    val tupletAnchorTick: Int = 0,
    val activeSlurGroupId: String? = null,
    val pendingArticulation: NoteArticulation? = null,
    val pendingAccent: NoteAccent? = null,
    val pendingFermata: Boolean = false,
    val chordStepMode: Boolean = false,
    val reverbPreset: ReverbPreset = ReverbPreset.ROOM,
    val sustainPedal: Boolean = false,
    val secondarySustainPedal: Boolean = false,
    val sustainDurationSec: Float = DEFAULT_SUSTAIN_DURATION_SEC,
    val secondarySustainDurationSec: Float = DEFAULT_SUSTAIN_DURATION_SEC,
    val metronomeEnabled: Boolean = true,
    val loopPlayback: Boolean = false,
    val cursorTick: Int = 0,
    val notes: List<MelodyScoreNote> = emptyList(),
    val dynamicMarks: List<ScoreDynamicMark> = emptyList(),
    val hairpins: List<ScoreHairpin> = emptyList(),
    val tempoEvents: List<ScoreTempoEvent> = emptyList(),
    val selectedNoteId: String? = null,
    val selectedAnnotation: SelectedScoreAnnotation? = null,
    val currentProjectId: String? = null,
    val isProjectDirty: Boolean = false,
    val savedProjects: List<MelodyProjectSummary> = emptyList(),
    val autoSaveDraftSummary: MelodyProjectSummary? = null,
    val isPlaying: Boolean = false,
    val isRecording: Boolean = false,
    val playheadBeat: Float = 0f,
    val soundFontLoaded: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
) {
    val ticksPerBar: Int get() = timeSignature.ticksPerBar
    val stepsPerBar: Int get() = timeSignature.stepsPerBar
    val quarterBeatsPerBar: Float get() = timeSignature.quarterBeatsPerBar
    val totalQuarterBeats: Float get() = totalBars * quarterBeatsPerBar
    val totalSteps: Int get() = totalBars * stepsPerBar
    val totalTicks: Int get() = totalBars * ticksPerBar
    val cursorStep: Int get() = cursorTick / TICKS_PER_STEP
    val hasPlayableNotes: Boolean get() = notes.any { !it.isRest }
    val hasMidKeyChanges: Boolean get() = keySignatureEvents.isNotEmpty()

    val selectedNote: MelodyScoreNote?
        get() = selectedNoteId?.let { id -> notes.firstOrNull { it.id == id } }

    fun barNumberAtTick(tick: Int = cursorTick): Int =
        (tick.coerceAtLeast(0) / ticksPerBar) + 1

    fun keySignatureEventAtBar(barIndex: Int): ScoreKeySignatureEvent? {
        if (barIndex < 1) return null
        return keySignatureEvents.lastOrNull { it.safeBarIndex == barIndex }
    }

    fun keySignatureAtBar(barIndex: Int): KeySignature {
        if (barIndex <= 0 || keySignatureEvents.isEmpty()) return initialKeySignature
        var active = initialKeySignature
        for (ev in keySignatureEvents) {
            if (ev.safeBarIndex <= barIndex) {
                active = ev.keySignature
            } else {
                break
            }
        }
        return active
    }

    fun keySignatureAtTick(tick: Int = cursorTick): KeySignature {
        val barIdx = (tick.coerceAtLeast(0) / ticksPerBar).coerceAtLeast(0)
        return keySignatureAtBar(barIdx)
    }

    fun beatNumberAtTick(tick: Int = cursorTick): Int =
        (((tick.coerceAtLeast(0) % ticksPerBar) / timeSignature.unitTicks) + 1)
            .coerceIn(1, timeSignature.safeNumerator)

    /**
     * 1-based slot index (`1..count`) of the next tuplet note to enter when [activeTuplet] is enabled.
     */
    val currentTupletSlotNumber: Int
        get() {
            val tup = activeTuplet ?: return 1
            val rel = (cursorTick - tupletAnchorTick).mod(tup.spanTicks)
            return ((rel / tup.noteTicks) + 1).coerceIn(1, tup.count)
        }

    val effectiveReverbSend: Float
        get() {
            val baseRev = if (dualKeyboardEnabled) {
                maxOf(instrument.defaultReverb, secondaryInstrument.defaultReverb)
            } else {
                instrument.defaultReverb
            }
            return (baseRev * reverbPreset.multiplier).coerceIn(0.02f, 0.62f)
        }

    val sequenceDurationSec: Float
        get() {
            val compiled = compileSchedule(MELODY_SAMPLE_RATE)
            return (compiled.totalFrames.toFloat() / MELODY_SAMPLE_RATE.toFloat()).coerceAtLeast(0.5f)
        }

    fun compileSchedule(sampleRate: Int = MELODY_SAMPLE_RATE): CompiledMelodySchedule =
        ScoreExpressionCompiler.compilePlaybackSchedule(this, sampleRate)

    /**
     * Builds sample-accurate [NativeSynthNote] events for BOTH real-time audition (`试听`)
     * and offline `.wav` export (`生成音轨`), applying Slurs, Articulations, Fermatas, Dynamics,
     * Hairpins, Tempo Map (`rit.` / `accel.` / `a tempo`), and global [swingPreset].
     */
    fun buildNativeSchedule(sampleRate: Int = MELODY_SAMPLE_RATE): Pair<List<NativeSynthNote>, Long> {
        val compiled = compileSchedule(sampleRate)
        return compiled.notes to compiled.totalFrames
    }
}

object MusicalNotationHelper {
    private val PITCH_NAMES = arrayOf("C", "#C", "D", "#D", "E", "F", "#F", "G", "#G", "A", "#A", "B")
    private val FLAT_PITCH_NAMES = arrayOf("C", "bD", "D", "bE", "E", "F", "bG", "G", "bA", "A", "bB", "B")
    private val WHITE_SEMITONE_OFFSETS = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    fun formatSustainSeconds(sec: Float): String =
        String.format(Locale.US, "%.3f", sec.coerceIn(0.030f, 10.000f))

    /**
     * Default ergonomic white key physical width (dp) for a screen container width.
     */
    fun defaultKeyWidthDpForScreen(containerWidthDp: Float): Float = when {
        containerWidthDp >= 1100f -> 43f
        containerWidthDp >= 840f  -> 42f
        containerWidthDp >= 640f  -> 40f
        containerWidthDp >= 480f  -> 38f
        else                      -> 38f
    }

    fun octaveToWhiteKeyIndex(octave: Int): Float =
        ((octave.coerceIn(1, 7) - 1) * 7).toFloat()

    fun whiteKeyIndexToMidi(whiteIndex: Int): Int {
        val clamped = whiteIndex.coerceIn(0, TOTAL_VIRTUAL_WHITE_KEYS - 1)
        val oct = (clamped / 7) + 1
        val degree = clamped % 7
        return ((oct + 1) * 12 + WHITE_SEMITONE_OFFSETS[degree]).coerceIn(PIANO_MIN_MIDI, PIANO_MAX_MIDI)
    }

    fun hasSharpAfterWhiteKey(whiteIndex: Int): Boolean {
        if (whiteIndex < 0 || whiteIndex >= TOTAL_VIRTUAL_WHITE_KEYS - 1) return false
        return when (whiteIndex % 7) {
            0, 1, 3, 4, 5 -> true // C->#C, D->#D, F->#F, G->#G, A->#A
            else -> false
        }
    }

    fun formatVisibleRangeLabel(scrollWhiteKey: Float, visibleWhiteKeysFloat: Float): String {
        val visCount = ceil(visibleWhiteKeysFloat.coerceIn(3f, 50f) - 0.15f).toInt().coerceAtLeast(3)
        val maxStart = (TOTAL_VIRTUAL_WHITE_KEYS - visCount).coerceAtLeast(0)
        val startIdx = floor(scrollWhiteKey + 0.15f).toInt().coerceIn(0, maxStart)
        val endIdx = (startIdx + visCount - 1).coerceIn(startIdx, TOTAL_VIRTUAL_WHITE_KEYS - 1)
        return "${midiToWhiteKeyLabel(whiteKeyIndexToMidi(startIdx))}–${midiToWhiteKeyLabel(whiteKeyIndexToMidi(endIdx))}"
    }

    fun isBlackKey(midiNote: Int): Boolean {
        return when (((midiNote % 12) + 12) % 12) {
            1, 3, 6, 8, 10 -> true
            else -> false
        }
    }

    fun isRootC(midiNote: Int): Boolean = (((midiNote % 12) + 12) % 12) == 0

    /**
     * White key label: Pitch name + octave group, e.g. "C4", "D4", "E4".
     */
    fun midiToWhiteKeyLabel(midiNote: Int): String {
        val pc = ((midiNote % 12) + 12) % 12
        val oct = (midiNote / 12) - 1
        return "${PITCH_NAMES[pc]}$oct"
    }

    /**
     * Black key label: Pitch name without octave or Jianpu.
     * Follows active key signature: `#C/#D/#F/#G/#A` for sharp/neutral keys, `bD/bE/bG/bA/bB` for flat keys.
     */
    fun midiToBlackKeyLabel(midiNote: Int, preferFlats: Boolean = false): String {
        val pc = ((midiNote % 12) + 12) % 12
        return if (preferFlats) FLAT_PITCH_NAMES[pc] else PITCH_NAMES[pc]
    }

    /**
     * Full pitch + octave label, e.g. "C4", "#C4" / "bD4", "D4".
     */
    fun midiToNoteName(midiNote: Int, preferFlats: Boolean = false): String {
        val pc = ((midiNote % 12) + 12) % 12
        val oct = (midiNote / 12) - 1
        val names = if (preferFlats) FLAT_PITCH_NAMES else PITCH_NAMES
        return "${names[pc]}$oct"
    }

    data class DemoScoreShowcase(
        val notes: List<MelodyScoreNote>,
        val dynamicMarks: List<ScoreDynamicMark>,
        val hairpins: List<ScoreHairpin>,
        val tempoEvents: List<ScoreTempoEvent>
    )

    /**
     * Built-in expressive 4-bar studio audition melody (warm I - V - vi - IV arpeggio + singing melody,
     * phrase slurs, dynamics, hairpins, triplet flourish & final rit. + Fermata) for instant audition & PDF test.
     */
    fun buildDemoShowcaseBundle(): DemoScoreShowcase {
        val slurBar1 = "demo_slur_1"
        val slurBar2 = "demo_slur_2"
        val slurBar3 = "demo_slur_3"
        val slurTriplet = "demo_slur_4"

        val items = mutableListOf<MelodyScoreNote>()
        // Bar 1 (C Major: C3-G3-C4 accompaniment + slurred E5-D5-C5-G4 melody)
        items += MelodyScoreNote(midiNote = 48, startBeat = 0.0f, durationBeats = 2.0f, velocity = 0.72f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 55, startBeat = 0.5f, durationBeats = 1.5f, velocity = 0.66f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 60, startBeat = 1.0f, durationBeats = 1.5f, velocity = 0.68f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 76, startBeat = 0.0f, durationBeats = 1.5f, velocity = 0.88f, keyboardSlot = 0, slurGroupId = slurBar1)
        items += MelodyScoreNote(midiNote = 74, startBeat = 1.5f, durationBeats = 0.5f, velocity = 0.80f, keyboardSlot = 0, slurGroupId = slurBar1)
        items += MelodyScoreNote(midiNote = 72, startBeat = 2.0f, durationBeats = 1.0f, velocity = 0.85f, keyboardSlot = 0, slurGroupId = slurBar1)
        items += MelodyScoreNote(midiNote = 67, startBeat = 3.0f, durationBeats = 1.0f, velocity = 0.78f, keyboardSlot = 0, slurGroupId = slurBar1)

        // Bar 2 (G Major: G2-D3-B3 + slurred D5-E5-B4)
        items += MelodyScoreNote(midiNote = 43, startBeat = 4.0f, durationBeats = 2.0f, velocity = 0.72f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 50, startBeat = 4.5f, durationBeats = 1.5f, velocity = 0.66f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 59, startBeat = 5.0f, durationBeats = 1.5f, velocity = 0.68f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 74, startBeat = 4.0f, durationBeats = 1.5f, velocity = 0.86f, keyboardSlot = 0, slurGroupId = slurBar2)
        items += MelodyScoreNote(midiNote = 76, startBeat = 5.5f, durationBeats = 0.5f, velocity = 0.82f, keyboardSlot = 0, slurGroupId = slurBar2)
        items += MelodyScoreNote(midiNote = 71, startBeat = 6.0f, durationBeats = 2.0f, velocity = 0.84f, keyboardSlot = 0, slurGroupId = slurBar2, articulation = NoteArticulation.TENUTO)

        // Bar 3 (A Minor: A2-E3-C4 + rising C5-D5-E5-G5 crescendo phrase)
        items += MelodyScoreNote(midiNote = 45, startBeat = 8.0f, durationBeats = 2.0f, velocity = 0.72f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 52, startBeat = 8.5f, durationBeats = 1.5f, velocity = 0.66f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 60, startBeat = 9.0f, durationBeats = 1.5f, velocity = 0.68f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 72, startBeat = 8.0f, durationBeats = 1.0f, velocity = 0.85f, keyboardSlot = 0, slurGroupId = slurBar3)
        items += MelodyScoreNote(midiNote = 74, startBeat = 9.0f, durationBeats = 1.0f, velocity = 0.86f, keyboardSlot = 0, slurGroupId = slurBar3)
        items += MelodyScoreNote(midiNote = 76, startBeat = 10.0f, durationBeats = 1.0f, velocity = 0.90f, keyboardSlot = 0, slurGroupId = slurBar3)
        items += MelodyScoreNote(midiNote = 79, startBeat = 11.0f, durationBeats = 1.0f, velocity = 0.88f, keyboardSlot = 0, slurGroupId = slurBar3, accent = NoteAccent.ACCENT)

        // Bar 4 (F Major -> Triplet flourish G5-E5-D5 on Beat 2 -> C resolution with rit. & Fermata)
        items += MelodyScoreNote(midiNote = 41, startBeat = 12.0f, durationBeats = 2.0f, velocity = 0.74f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 48, startBeat = 12.5f, durationBeats = 1.5f, velocity = 0.66f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 57, startBeat = 13.0f, durationBeats = 1.0f, velocity = 0.68f, keyboardSlot = 1)
        items += MelodyScoreNote(midiNote = 77, startBeat = 12.0f, durationBeats = 1.0f, velocity = 0.90f, keyboardSlot = 0)
        // Beat 13.0..14.0 (tick 3120..3360): Expressive Eighth-Note Triplet (G5 - E5 - D5)
        val demoTripletTag = NoteTupletTag(
            count = 3,
            normalCount = 2,
            writtenBaseDenom = 8,
            groupStartTick = 13 * TICKS_PER_BEAT,
            spanTicks = TICKS_PER_BEAT
        )
        items += MelodyScoreNote(
            midiNote = 79,
            startBeat = 13f,
            durationBeats = 1f / 3f,
            velocity = 0.85f,
            keyboardSlot = 0,
            startTickExact = 13 * TICKS_PER_BEAT,
            durationTicksExact = 80,
            tuplet = demoTripletTag,
            slurGroupId = slurTriplet
        )
        items += MelodyScoreNote(
            midiNote = 76,
            startBeat = 13f + 1f / 3f,
            durationBeats = 1f / 3f,
            velocity = 0.82f,
            keyboardSlot = 0,
            startTickExact = 13 * TICKS_PER_BEAT + 80,
            durationTicksExact = 80,
            tuplet = demoTripletTag,
            slurGroupId = slurTriplet
        )
        items += MelodyScoreNote(
            midiNote = 74,
            startBeat = 13f + 2f / 3f,
            durationBeats = 1f / 3f,
            velocity = 0.80f,
            keyboardSlot = 0,
            startTickExact = 13 * TICKS_PER_BEAT + 160,
            durationTicksExact = 80,
            tuplet = demoTripletTag,
            slurGroupId = slurTriplet
        )
        items += MelodyScoreNote(midiNote = 60, startBeat = 14.0f, durationBeats = 2.0f, velocity = 0.75f, keyboardSlot = 0, hasFermata = true)
        items += MelodyScoreNote(midiNote = 64, startBeat = 14.0f, durationBeats = 2.0f, velocity = 0.74f, keyboardSlot = 0, hasFermata = true)
        items += MelodyScoreNote(midiNote = 72, startBeat = 14.0f, durationBeats = 2.0f, velocity = 0.90f, keyboardSlot = 0, hasFermata = true)

        val dynamics = listOf(
            ScoreDynamicMark(tick = 0, level = DynamicLevel.MP),
            ScoreDynamicMark(tick = 12 * TICKS_PER_BEAT, level = DynamicLevel.F)
        )
        val hairpins = listOf(
            ScoreHairpin(
                startTick = 8 * TICKS_PER_BEAT,
                endTick = 12 * TICKS_PER_BEAT,
                type = HairpinType.CRESCENDO
            ),
            ScoreHairpin(
                startTick = 13 * TICKS_PER_BEAT,
                endTick = 15 * TICKS_PER_BEAT,
                type = HairpinType.DIMINUENDO
            )
        )
        val tempos = listOf(
            ScoreTempoEvent(
                startTick = 0,
                kind = TempoEventKind.STEADY_BPM,
                bpm = 112,
                term = TempoTerm.MODERATO
            ),
            ScoreTempoEvent(
                startTick = 12 * TICKS_PER_BEAT,
                endTick = 16 * TICKS_PER_BEAT,
                kind = TempoEventKind.RITARDANDO,
                targetBpm = 84
            )
        )
        return DemoScoreShowcase(
            notes = items,
            dynamicMarks = dynamics,
            hairpins = hairpins,
            tempoEvents = tempos
        )
    }

    fun buildDemoShowcaseNotes(): List<MelodyScoreNote> = buildDemoShowcaseBundle().notes
}
