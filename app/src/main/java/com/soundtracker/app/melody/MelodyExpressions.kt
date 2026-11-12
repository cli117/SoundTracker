package com.soundtracker.app.melody

import com.soundtracker.app.dsp.NativeSynthNote
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Bitmask flags passed per [NativeSynthNote] to the C++ SoundFont2 engine (`melody_synth.cpp`)
 * so articulations and sudden dynamic envelopes sound authentic in both real-time audition and WAV export.
 */
object NoteSynthFlags {
    const val NONE = 0
    const val CRISP_RELEASE = 1 shl 0     // Staccato / Staccatissimo / Marcato crisp acoustic damper
    const val PORTATO_RELEASE = 1 shl 1   // Portato / Slur-end gentle singing release
    const val LEGATO_OVERLAP = 1 shl 2    // Interior Slur note smooth legato connection
    const val FORTEPIANO_DUCK = 1 shl 3   // `fp` strong attack followed by immediate soft sustain
}

/**
 * Standard musical Articulations (`奏法记号`) attached to individual notes/chords.
 * Sheet music keeps standard written rhythmic values while audio playback & WAV export
 * shape gate duration, damper release, and touch weight accurately.
 */
enum class NoteArticulation(
    val symbolBadge: String,
    val label: String,
    val subtitle: String,
    val gateRatio: Double,
    val maxCapSec: Double?,
    val velocityScale: Float
) {
    STACCATO(
        symbolBadge = ".",
        label = "跳音 (Staccato)",
        subtitle = "短促轻巧断奏 · 发声约 42% 时值",
        gateRatio = 0.42,
        maxCapSec = 0.22,
        velocityScale = 1.04f
    ),
    STACCATISSIMO(
        symbolBadge = "▼",
        label = "顿音 (Staccatissimo)",
        subtitle = "极短促清脆断音 · 发声约 24% 时值",
        gateRatio = 0.24,
        maxCapSec = 0.13,
        velocityScale = 1.12f
    ),
    TENUTO(
        symbolBadge = "−",
        label = "保持音 (Tenuto)",
        subtitle = "饱满充分保持满拍并略加重触键",
        gateRatio = 1.00,
        maxCapSec = null,
        velocityScale = 1.07f
    ),
    PORTATO(
        symbolBadge = "−.",
        label = "半连音 (Portato)",
        subtitle = "连中带断、歌唱性柔和分音 · 约 72% 时值",
        gateRatio = 0.72,
        maxCapSec = null,
        velocityScale = 1.02f
    )
}

/**
 * Single-note / chord attack accents (`重音` & `强重音`).
 */
enum class NoteAccent(
    val symbolBadge: String,
    val label: String,
    val subtitle: String,
    val velocityBoost: Float,
    val minVelocity: Float,
    val gateCapRatio: Double?
) {
    ACCENT(
        symbolBadge = ">",
        label = "重音 (Accent)",
        subtitle = "单音/单拍突出强调触键 (>)",
        velocityBoost = 0.18f,
        minVelocity = 0.86f,
        gateCapRatio = null
    ),
    MARCATO(
        symbolBadge = "^",
        label = "强重音 (Marcato)",
        subtitle = "强有力且干脆利落的突强 (^)",
        velocityBoost = 0.26f,
        minVelocity = 0.96f,
        gateCapRatio = 0.78
    )
}

/**
 * Sectional & sudden beat Dynamics (`力度记号`):
 * - `PP`, `P`, `MP`, `MF`, `F`, `FF` set the prevailing dynamic from `tick` onward.
 * - `SFZ` delivers a sudden powerful strike on that single beat/chord, then resumes prior dynamic.
 * - `FP` strikes *forte* (`f`) with post-attack ducking on sustaining voices, and sets subsequent beats to *piano* (`p`).
 */
enum class DynamicLevel(
    val marking: String,
    val cnName: String,
    val subtitle: String,
    val targetVelocity: Float,
    val isBeatAccentOnly: Boolean = false
) {
    PP("pp", "很弱 (Pianissimo)", "轻柔耳语般的弱奏", 0.20f),
    P("p", "弱 (Piano)", "柔和安静的弱奏", 0.33f),
    MP("mp", "中弱 (Mezzo-piano)", "温润自然的偏弱力度", 0.48f),
    MF("mf", "中强 (Mezzo-forte)", "标准自然演奏力度", 0.66f),
    F("f", "强 (Forte)", "饱满洪亮的强奏", 0.86f),
    FF("ff", "很强 (Fortissimo)", "辉煌澎湃的极强奏", 1.08f),
    SFZ("sfz", "突强 (Sforzando)", "当前单拍猛烈突强，随后恢复原力度", 1.14f, isBeatAccentOnly = true),
    FP("fp", "强后即弱 (Fortepiano)", "起音强奏后迅速转为弱奏 (p)", 0.96f, isBeatAccentOnly = true)
}

data class ScoreDynamicMark(
    val id: String = UUID.randomUUID().toString(),
    val tick: Int,
    val level: DynamicLevel
)

/**
 * Gradual dynamic hairpins (`<` Crescendo / `>` Diminuendo).
 */
enum class HairpinType(
    val wedgeSymbol: String,
    val abbrev: String,
    val label: String,
    val subtitle: String
) {
    CRESCENDO("<", "cresc.", "渐强 < (cresc.)", "区间内音量由弱平滑渐强"),
    DIMINUENDO(">", "dim.", "渐弱 > (dim.)", "区间内音量由强平滑渐弱")
}

data class ScoreHairpin(
    val id: String = UUID.randomUUID().toString(),
    val startTick: Int,
    val endTick: Int,
    val type: HairpinType
) {
    val safeStartTick: Int get() = min(startTick, endTick).coerceAtLeast(0)
    val safeEndTick: Int get() = max(endTick, safeStartTick + TICKS_PER_STEP)
}

/**
 * Classical Italian Tempo Terms (`常用速度术语`) with standard default BPMs.
 */
enum class TempoTerm(
    val italianName: String,
    val cnName: String,
    val defaultBpm: Int,
    val typicalRange: String
) {
    LARGO("Largo", "广板", 50, "40–60 BPM"),
    ADAGIO("Adagio", "柔板", 68, "60–76 BPM"),
    ANDANTE("Andante", "行板", 88, "76–108 BPM"),
    MODERATO("Moderato", "中板", 112, "108–120 BPM"),
    ALLEGRETTO("Allegretto", "小快板", 122, "116–128 BPM"),
    ALLEGRO("Allegro", "快板", 138, "126–156 BPM"),
    VIVACE("Vivace", "急快板", 162, "156–176 BPM"),
    PRESTO("Presto", "急板", 184, "168–208 BPM")
}

enum class TempoEventKind(val shortLabel: String) {
    STEADY_BPM("速度变更"),
    RITARDANDO("渐慢 rit."),
    ACCELERANDO("渐快 accel."),
    A_TEMPO("回原速 a tempo")
}

/**
 * Musical Tempo Map event (`速度记号`):
 * - [TempoEventKind.STEADY_BPM]: Instantaneous tempo mark at [startTick], e.g. `Allegro ♩ = 138` or `♩ = 96`.
 * - [TempoEventKind.RITARDANDO]: Gradual deceleration (`rit.`) across `[startTick, endTick]` down to [targetBpm] (or auto 68%).
 * - [TempoEventKind.ACCELERANDO]: Gradual acceleration (`accel.`) across `[startTick, endTick]` up to [targetBpm] (or auto 135%).
 * - [TempoEventKind.A_TEMPO]: Restores the previous steady tempo (`a tempo`) at [startTick].
 */
data class ScoreTempoEvent(
    val id: String = UUID.randomUUID().toString(),
    val startTick: Int,
    val endTick: Int = startTick,
    val kind: TempoEventKind,
    val bpm: Int = 120,
    val term: TempoTerm? = null,
    val targetBpm: Int? = null
) {
    val safeStartTick: Int get() = startTick.coerceAtLeast(0)
    val safeEndTick: Int
        get() = when (kind) {
            TempoEventKind.RITARDANDO, TempoEventKind.ACCELERANDO ->
                max(endTick, safeStartTick + TICKS_PER_BEAT)
            else -> safeStartTick
        }

    fun scoreLabel(): String = when (kind) {
        TempoEventKind.STEADY_BPM -> {
            val cleanBpm = bpm.coerceIn(36, 240)
            if (term != null) "${term.italianName} ($cleanBpm)" else "$cleanBpm"
        }
        TempoEventKind.RITARDANDO -> "rit."
        TempoEventKind.ACCELERANDO -> "accel."
        TempoEventKind.A_TEMPO -> "a tempo"
    }

    fun summaryText(): String = when (kind) {
        TempoEventKind.STEADY_BPM -> {
            val cleanBpm = bpm.coerceIn(36, 240)
            if (term != null) "${term.italianName} ${term.cnName} (♩=$cleanBpm)" else "♩ = $cleanBpm BPM"
        }
        TempoEventKind.RITARDANDO -> targetBpm?.let { "渐慢 rit. (至 ♩=${it.coerceIn(32, 240)})" } ?: "渐慢 rit."
        TempoEventKind.ACCELERANDO -> targetBpm?.let { "渐快 accel. (至 ♩=${it.coerceIn(36, 240)})" } ?: "渐快 accel."
        TempoEventKind.A_TEMPO -> "回原速 a tempo"
    }
}

/**
 * Interactive selection reference for non-note score markings tapped directly on the 5-line staff.
 */
sealed class SelectedScoreAnnotation {
    abstract val id: String
    data class Dynamic(override val id: String) : SelectedScoreAnnotation()
    data class Hairpin(override val id: String) : SelectedScoreAnnotation()
    data class Tempo(override val id: String) : SelectedScoreAnnotation()
    data class KeyChange(override val id: String) : SelectedScoreAnnotation()
    data class ChordSymbol(override val id: String) : SelectedScoreAnnotation()
}

/**
 * Piecewise-monotonic sample-accurate bidirectional mapping (`tick <-> audio frame`)
 * powering Playhead sync, Real-Time Woodblock Metronome, Live Recording, and WAV Export.
 */
class ScoreTempoMap(
    val sampleRate: Int,
    private val segTicks: DoubleArray,
    private val segFrames: DoubleArray,
    private val segBpms: DoubleArray,
    val tailBpm: Float
) {
    private val tailFramesPerTick: Double =
        ((60.0 / tailBpm.coerceIn(30f, 260f).toDouble()) * sampleRate.toDouble()) / TICKS_PER_BEAT.toDouble()

    fun tickToFrame(tick: Double): Long {
        if (tick <= 0.0 || segTicks.isEmpty()) return 0L
        val lastIdx = segTicks.lastIndex
        if (tick >= segTicks[lastIdx]) {
            val extraTicks = tick - segTicks[lastIdx]
            return (segFrames[lastIdx] + extraTicks * tailFramesPerTick).roundToLong().coerceAtLeast(0L)
        }
        var lo = 0
        var hi = lastIdx - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segTicks[mid + 1] <= tick) {
                lo = mid + 1
            } else if (segTicks[mid] > tick) {
                hi = mid - 1
            } else {
                val t0 = segTicks[mid]
                val t1 = segTicks[mid + 1]
                val f0 = segFrames[mid]
                val f1 = segFrames[mid + 1]
                val u = if (t1 > t0) (tick - t0) / (t1 - t0) else 0.0
                return (f0 + (f1 - f0) * u).roundToLong().coerceAtLeast(0L)
            }
        }
        return 0L
    }

    fun frameToTick(frame: Long): Double {
        if (frame <= 0L || segFrames.isEmpty()) return 0.0
        val f = frame.toDouble()
        val lastIdx = segFrames.lastIndex
        if (f >= segFrames[lastIdx]) {
            val extraFrames = f - segFrames[lastIdx]
            return segTicks[lastIdx] + (extraFrames / tailFramesPerTick.coerceAtLeast(1e-6))
        }
        var lo = 0
        var hi = lastIdx - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segFrames[mid + 1] <= f) {
                lo = mid + 1
            } else if (segFrames[mid] > f) {
                hi = mid - 1
            } else {
                val f0 = segFrames[mid]
                val f1 = segFrames[mid + 1]
                val t0 = segTicks[mid]
                val t1 = segTicks[mid + 1]
                val u = if (f1 > f0) (f - f0) / (f1 - f0) else 0.0
                return (t0 + (t1 - t0) * u).coerceAtLeast(0.0)
            }
        }
        return 0.0
    }

    fun bpmAtTick(tick: Int): Float {
        if (segTicks.isEmpty()) return tailBpm
        val t = tick.toDouble().coerceAtLeast(0.0)
        val lastIdx = segTicks.lastIndex
        if (t >= segTicks[lastIdx]) return tailBpm
        var lo = 0
        var hi = (lastIdx - 1).coerceAtLeast(0)
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segTicks[mid + 1] <= t) {
                lo = mid + 1
            } else if (segTicks[mid] > t) {
                hi = mid - 1
            } else {
                return segBpms[mid].toFloat()
            }
        }
        return tailBpm
    }

    /**
     * Generates exact audio frame positions & accent levels (`2`=downbeat, `1`=compound sub-accent, `0`=weak beat)
     * for the C++ real-time woodblock metronome so clicks track `♩=xx`, `rit.`, `accel.`, `a tempo`, and `𝄐`!
     */
    fun buildMetronomePulses(
        maxTicks: Int,
        timeSignature: TimeSignature
    ): Pair<LongArray, IntArray> {
        val unitTicks = timeSignature.unitTicks.coerceAtLeast(1)
        val num = timeSignature.safeNumerator
        val den = timeSignature.safeDenominator
        val totalPulses = (maxTicks.coerceAtLeast(timeSignature.ticksPerBar) / unitTicks) + num * 2
        val frames = LongArray(totalPulses)
        val accents = IntArray(totalPulses)
        for (p in 0 until totalPulses) {
            val tick = p.toDouble() * unitTicks.toDouble()
            frames[p] = tickToFrame(tick)
            val pulseInBar = p % num
            val isDownbeat = (pulseInBar == 0)
            val isCompoundSub = !isDownbeat && den == 8 && num >= 6 && (num % 3 == 0) && (pulseInBar % 3 == 0)
            accents[p] = when {
                isDownbeat -> 2
                isCompoundSub -> 1
                else -> 0
            }
        }
        return frames to accents
    }
}

data class CompiledMelodySchedule(
    val notes: List<NativeSynthNote>,
    val totalFrames: Long,
    val tempoMap: ScoreTempoMap,
    val metronomePulseFrames: LongArray,
    val metronomePulseAccents: IntArray
)

/**
 * Compiles musical score markings (Slurs, Articulations, Fermatas, Dynamics, Hairpins, Tempo Map, and Swing)
 * into sample-accurate synthesis events shared identically by `试听` and `生成音轨 (.wav)`.
 */
object ScoreExpressionCompiler {

    private const val FERMATA_STRETCH_RATIO = 1.80
    private const val TEMPO_SUBDIV_TICKS = 20 // 1/12th of a quarter note for ultra-smooth rit./accel.

    fun buildTempoMap(
        melody: MelodyStudioUiState,
        sampleRate: Int = MELODY_SAMPLE_RATE
    ): ScoreTempoMap {
        val sr = sampleRate.coerceAtLeast(22050)
        val ticksPerBar = melody.ticksPerBar.coerceAtLeast(60)
        val maxNoteEndTick = maxOf(
            melody.notes.maxOfOrNull { it.resolvedEndTick } ?: 0,
            melody.maxPlayableEndTick
        )
        val maxTempoEndTick = melody.tempoEvents.maxOfOrNull { it.safeEndTick } ?: 0
        val horizonTicks = maxOf(
            melody.totalTicks,
            maxNoteEndTick + ticksPerBar,
            maxTempoEndTick + ticksPerBar,
            ticksPerBar * 2
        )

        val sortedEvents = melody.tempoEvents.sortedWith(
            compareBy<ScoreTempoEvent> { it.safeStartTick }
                .thenBy {
                    // At identical startTick, apply instantaneous STEADY_BPM / A_TEMPO before starting a ramp
                    when (it.kind) {
                        TempoEventKind.STEADY_BPM -> 0
                        TempoEventKind.A_TEMPO -> 1
                        TempoEventKind.RITARDANDO -> 2
                        TempoEventKind.ACCELERANDO -> 3
                    }
                }
        )

        // Collect unique Fermata spans `[startTick, endTick]` across chords/rests
        val fermataSpans = melody.notes
            .asSequence()
            .filter { it.hasFermata }
            .map { it.resolvedStartTick.coerceAtLeast(0) to it.resolvedEndTick.coerceAtLeast(it.resolvedStartTick + TICKS_PER_STEP) }
            .sortedBy { it.first }
            .fold(mutableListOf<Pair<Int, Int>>()) { acc, span ->
                val last = acc.lastOrNull()
                if (last != null && span.first < last.second) {
                    acc[acc.lastIndex] = last.first to max(last.second, span.second)
                } else {
                    acc += span
                }
                acc
            }

        // Pre-resolve active tempo trajectory across [0 .. horizonTicks]
        data class ResolvedRamp(
            val startTick: Int,
            val endTick: Int,
            val startBpm: Double,
            val endBpm: Double
        )

        var steadyBpm = melody.bpm.coerceIn(36, 240).toDouble()
        var currentBpm = steadyBpm

        // Check if an explicit STEADY_BPM sits right at tick 0
        sortedEvents.firstOrNull { it.safeStartTick == 0 && it.kind == TempoEventKind.STEADY_BPM }?.let { ev0 ->
            steadyBpm = ev0.bpm.coerceIn(36, 240).toDouble()
            currentBpm = steadyBpm
        }

        // Build ordered tempo segments
        val breakpoints = java.util.TreeSet<Int>()
        breakpoints.add(0)
        breakpoints.add(horizonTicks)
        for ((f0, f1) in fermataSpans) {
            if (f0 in 0..horizonTicks) breakpoints.add(f0)
            if (f1 in 0..horizonTicks) breakpoints.add(f1)
        }
        for (ev in sortedEvents) {
            if (ev.safeStartTick in 0..horizonTicks) breakpoints.add(ev.safeStartTick)
            if (ev.safeEndTick in 0..horizonTicks) {
                breakpoints.add(ev.safeEndTick)
                if (ev.kind == TempoEventKind.RITARDANDO || ev.kind == TempoEventKind.ACCELERANDO) {
                    var sub = ev.safeStartTick + TEMPO_SUBDIV_TICKS
                    while (sub < ev.safeEndTick && sub < horizonTicks) {
                        breakpoints.add(sub)
                        sub += TEMPO_SUBDIV_TICKS
                    }
                }
            }
        }

        val resolvedRamps = mutableListOf<ResolvedRamp>()
        val instantChanges = HashMap<Int, Pair<Double, Double>>() // tick -> (newCurrentBpm, newSteadyBpm)

        for ((idx, ev) in sortedEvents.withIndex()) {
            val t0 = ev.safeStartTick
            // Advance currentBpm up to t0 from any completed prior ramp
            resolvedRamps.lastOrNull { it.endTick <= t0 }?.let { finished ->
                // Only adopt if no instant change occurred after finished.endTick
                val laterInstant = instantChanges.keys.filter { it in finished.endTick..t0 }.maxOrNull()
                if (laterInstant == null) {
                    currentBpm = finished.endBpm
                }
            }
            // If t0 falls mid-way inside a previous ramp, truncate/sample that previous ramp at t0
            val overlappingIdx = resolvedRamps.indexOfLast { t0 > it.startTick && t0 < it.endTick }
            if (overlappingIdx >= 0) {
                val prev = resolvedRamps[overlappingIdx]
                val frac = (t0 - prev.startTick).toDouble() / (prev.endTick - prev.startTick).coerceAtLeast(1).toDouble()
                val midBpm = prev.startBpm + (prev.endBpm - prev.startBpm) * frac
                resolvedRamps[overlappingIdx] = prev.copy(endTick = t0, endBpm = midBpm)
                currentBpm = midBpm
            }

            when (ev.kind) {
                TempoEventKind.STEADY_BPM -> {
                    val b = ev.bpm.coerceIn(36, 240).toDouble()
                    currentBpm = b
                    steadyBpm = b
                    instantChanges[t0] = currentBpm to steadyBpm
                }
                TempoEventKind.A_TEMPO -> {
                    currentBpm = steadyBpm
                    instantChanges[t0] = currentBpm to steadyBpm
                }
                TempoEventKind.RITARDANDO -> {
                    val t1 = ev.safeEndTick
                    val nextSteady = sortedEvents
                        .drop(idx + 1)
                        .firstOrNull {
                            it.kind == TempoEventKind.STEADY_BPM &&
                                it.safeStartTick in t0..(t1 + TICKS_PER_STEP * 2) &&
                                it.bpm < currentBpm
                        }
                    val target = when {
                        ev.targetBpm != null -> ev.targetBpm.coerceIn(32, 240).toDouble()
                        nextSteady != null -> nextSteady.bpm.coerceIn(32, 240).toDouble()
                        else -> (currentBpm * 0.68).coerceIn(36.0, max(36.0, currentBpm - 8.0))
                    }
                    resolvedRamps += ResolvedRamp(t0, t1, currentBpm, target)
                    currentBpm = target
                }
                TempoEventKind.ACCELERANDO -> {
                    val t1 = ev.safeEndTick
                    val nextSteady = sortedEvents
                        .drop(idx + 1)
                        .firstOrNull {
                            it.kind == TempoEventKind.STEADY_BPM &&
                                it.safeStartTick in t0..(t1 + TICKS_PER_STEP * 2) &&
                                it.bpm > currentBpm
                        }
                    val target = when {
                        ev.targetBpm != null -> ev.targetBpm.coerceIn(36, 240).toDouble()
                        nextSteady != null -> nextSteady.bpm.coerceIn(36, 240).toDouble()
                        else -> (currentBpm * 1.35).coerceIn(min(240.0, currentBpm + 10.0), 240.0)
                    }
                    resolvedRamps += ResolvedRamp(t0, t1, currentBpm, target)
                    currentBpm = target
                }
            }
        }

        val bpList = breakpoints.toIntArray()
        val nPts = bpList.size
        val segTicks = DoubleArray(nPts)
        val segFrames = DoubleArray(nPts)
        val segBpms = DoubleArray(nPts)

        var runningBpm = melody.bpm.coerceIn(36, 240).toDouble()
        instantChanges[0]?.let { runningBpm = it.first }

        var accumFrames = 0.0
        for (i in 0 until nPts) {
            val tick = bpList[i]
            segTicks[i] = tick.toDouble()
            segFrames[i] = accumFrames

            if (i < nPts - 1) {
                val nextTick = bpList[i + 1]
                instantChanges[tick]?.let { runningBpm = it.first }

                val midTick = (tick + nextTick) * 0.5
                val activeRamp = resolvedRamps.lastOrNull { midTick >= it.startTick && midTick <= it.endTick }
                val baseSegmentBpm = if (activeRamp != null) {
                    val u = ((midTick - activeRamp.startTick) /
                        (activeRamp.endTick - activeRamp.startTick).coerceAtLeast(1).toDouble()).coerceIn(0.0, 1.0)
                    val b = activeRamp.startBpm + (activeRamp.endBpm - activeRamp.startBpm) * u
                    // Also carry endBpm forward once ramp completes
                    if (nextTick >= activeRamp.endTick && !instantChanges.containsKey(nextTick)) {
                        runningBpm = activeRamp.endBpm
                    }
                    b
                } else {
                    runningBpm
                }

                val inFermata = fermataSpans.any { (f0, f1) -> midTick >= f0 && midTick <= f1 }
                val effectiveBpm = if (inFermata) {
                    (baseSegmentBpm / FERMATA_STRETCH_RATIO).coerceAtLeast(20.0)
                } else {
                    baseSegmentBpm.coerceIn(30.0, 260.0)
                }
                segBpms[i] = effectiveBpm

                val framesPerTick = ((60.0 / effectiveBpm) * sr.toDouble()) / TICKS_PER_BEAT.toDouble()
                accumFrames += (nextTick - tick).toDouble() * framesPerTick
            } else {
                instantChanges[tick]?.let { runningBpm = it.first }
                segBpms[i] = runningBpm.coerceIn(30.0, 260.0)
            }
        }

        return ScoreTempoMap(
            sampleRate = sr,
            segTicks = segTicks,
            segFrames = segFrames,
            segBpms = segBpms,
            tailBpm = runningBpm.toFloat().coerceIn(36f, 240f)
        )
    }

    /**
     * Evaluates the expressive dynamic curve (`pp..ff`, `<` cresc., `>` dim., `sfz`, `fp`) across the score.
     */
    private class DynamicTimelineEvaluator(
        dynamicMarks: List<ScoreDynamicMark>,
        hairpins: List<ScoreHairpin>
    ) {
        val hasScoreDynamics: Boolean = dynamicMarks.isNotEmpty() || hairpins.isNotEmpty()
        private val sortedMarks = dynamicMarks.sortedBy { it.tick }
        private val sortedHairpins = hairpins.sortedBy { it.safeStartTick }

        private data class ResolvedHairpin(
            val startTick: Int,
            val endTick: Int,
            val vStart: Float,
            val vEnd: Float
        )

        private val resolvedHairpins: List<ResolvedHairpin>

        init {
            val out = mutableListOf<ResolvedHairpin>()
            for (h in sortedHairpins) {
                val t0 = h.safeStartTick
                val t1 = h.safeEndTick
                val v0 = baseDynamicBeforeHairpins(t0, out)
                val explicitEndMark = sortedMarks.firstOrNull { m ->
                    !m.level.isBeatAccentOnly &&
                        m.tick in (t0 + 1)..(t1 + TICKS_PER_STEP * 2) &&
                        ((h.type == HairpinType.CRESCENDO && m.level.targetVelocity > v0) ||
                            (h.type == HairpinType.DIMINUENDO && m.level.targetVelocity < v0))
                }
                val v1 = when {
                    explicitEndMark != null -> explicitEndMark.level.targetVelocity
                    h.type == HairpinType.CRESCENDO -> {
                        val delta = if (v0 >= 0.84f) 0.20f else 0.30f
                        (v0 + delta).coerceAtMost(1.08f)
                    }
                    else -> {
                        val delta = if (v0 <= 0.34f) 0.14f else 0.28f
                        (v0 - delta).coerceAtLeast(0.18f)
                    }
                }
                out += ResolvedHairpin(t0, t1, v0, v1)
            }
            resolvedHairpins = out
        }

        private fun baseDynamicBeforeHairpins(tick: Int, completedHairpins: List<ResolvedHairpin>): Float {
            var curVel = DynamicLevel.MF.targetVelocity
            var lastEventTick = -1
            for (m in sortedMarks) {
                if (m.tick > tick) break
                if (!m.level.isBeatAccentOnly) {
                    curVel = m.level.targetVelocity
                    lastEventTick = m.tick
                } else if (m.level == DynamicLevel.FP && tick > m.tick + 12) {
                    curVel = DynamicLevel.P.targetVelocity
                    lastEventTick = m.tick
                }
            }
            for (rh in completedHairpins) {
                if (rh.endTick <= tick && rh.endTick > lastEventTick) {
                    curVel = rh.vEnd
                    lastEventTick = rh.endTick
                }
            }
            return curVel
        }

        data class BeatDynamicResult(
            val velocity: Float,
            val isFortepiano: Boolean
        )

        fun evaluateAtTick(tick: Int, rawNoteVelocity: Float): BeatDynamicResult {
            if (!hasScoreDynamics) {
                return BeatDynamicResult(rawNoteVelocity.coerceIn(0.12f, 1.0f), isFortepiano = false)
            }

            // Check instant beat-level `sfz` or `fp` right at `tick`
            val beatSpecial = sortedMarks.lastOrNull { m ->
                m.level.isBeatAccentOnly && abs(m.tick - tick) <= 15
            }
            if (beatSpecial?.level == DynamicLevel.FP) {
                return BeatDynamicResult(
                    velocity = DynamicLevel.FP.targetVelocity,
                    isFortepiano = true
                )
            }

            // Prevailing dynamic + active hairpin interpolation
            var baseVel = baseDynamicBeforeHairpins(tick, resolvedHairpins)
            val activeHairpin = resolvedHairpins.lastOrNull { tick in it.startTick..it.endTick }
            if (activeHairpin != null) {
                val span = (activeHairpin.endTick - activeHairpin.startTick).coerceAtLeast(1)
                val u = ((tick - activeHairpin.startTick).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                baseVel = activeHairpin.vStart + (activeHairpin.vEnd - activeHairpin.vStart) * u
            }

            if (beatSpecial?.level == DynamicLevel.SFZ) {
                baseVel = max(baseVel + 0.28f, DynamicLevel.SFZ.targetVelocity)
            }

            // Preserve subtle inner-voice / accompaniment balance from rawNoteVelocity (~0.85f nominal)
            val voiceBalance = (rawNoteVelocity / 0.85f).coerceIn(0.82f, 1.08f)
            return BeatDynamicResult(
                velocity = (baseVel * voiceBalance).coerceIn(0.14f, 1.18f),
                isFortepiano = false
            )
        }
    }

    fun compilePlaybackSchedule(
        melody: MelodyStudioUiState,
        sampleRate: Int = MELODY_SAMPLE_RATE
    ): CompiledMelodySchedule {
        val sr = sampleRate.coerceAtLeast(22050)
        val tempoMap = buildTempoMap(melody, sr)
        val dynEval = DynamicTimelineEvaluator(melody.dynamicMarks, melody.hairpins)
        val barBeatsD = melody.quarterBeatsPerBar.toDouble().coerceAtLeast(0.25)

        val pitchedNotes = melody.notes.filterNot { it.isRest }

        // Precompute multi-note Slur groups scoped per staff (`(slurGroupId, StaffPart) -> sorted distinct onset ticks`)
        data class SlurPhraseMeta(
            val firstTick: Int,
            val lastOnsetTick: Int,
            val onsetTicks: List<Int>
        )
        val slurMetaMap = HashMap<Pair<String, StaffPart>, SlurPhraseMeta>()
        pitchedNotes
            .filter { it.slurGroupId != null }
            .groupBy { it.slurGroupId!! to it.resolvedStaffPart(melody.grandStaffEnabled) }
            .forEach { (key, list) ->
                val distinctOnsets = list.map { it.resolvedStartTick }.distinct().sorted()
                if (distinctOnsets.size >= 2) {
                    slurMetaMap[key] = SlurPhraseMeta(
                        firstTick = distinctOnsets.first(),
                        lastOnsetTick = distinctOnsets.last(),
                        onsetTicks = distinctOnsets
                    )
                }
            }

        val nativeNotes = ArrayList<NativeSynthNote>(pitchedNotes.size + melody.chordSymbols.size * 4)
        for (n in pitchedNotes) {
            val rawStartTick = n.resolvedStartTick
            val rawDurTicks = n.resolvedDurationTicks
            val rawStartBeat = rawStartTick.toDouble() / TICKS_PER_BEAT.toDouble()
            val rawEndBeat = (rawStartTick + rawDurTicks).toDouble() / TICKS_PER_BEAT.toDouble()

            val warpedStartTick: Double
            val warpedEndTick: Double
            if (n.tuplet != null || !melody.swingPreset.isEnabled) {
                warpedStartTick = rawStartTick.toDouble()
                warpedEndTick = (rawStartTick + rawDurTicks).toDouble()
            } else {
                warpedStartTick = melody.swingPreset.warpBeat(rawStartBeat, barBeatsD) * TICKS_PER_BEAT.toDouble()
                warpedEndTick = melody.swingPreset.warpBeat(rawEndBeat, barBeatsD) * TICKS_PER_BEAT.toDouble()
            }

            val startFrame = tempoMap.tickToFrame(warpedStartTick)
            val nominalEndFrame = tempoMap.tickToFrame(warpedEndTick)
            val nominalDurFrames = (nominalEndFrame - startFrame).coerceAtLeast(512L)

            // 1. Dynamic + Accent Velocity Calculation
            val dynRes = dynEval.evaluateAtTick(rawStartTick, n.velocity)
            var finalVel = dynRes.velocity
            var flags = NoteSynthFlags.NONE
            if (dynRes.isFortepiano) {
                flags = flags or NoteSynthFlags.FORTEPIANO_DUCK
            }

            // 2. Slur (`圆滑线`) Legato Phrasing vs Articulations (`奏法`)
            val staffPart = n.resolvedStaffPart(melody.grandStaffEnabled)
            val slurMeta = n.slurGroupId?.let { slurMetaMap[it to staffPart] }
            val isInteriorSlurNote = slurMeta != null && rawStartTick < slurMeta.lastOnsetTick
            val isPhraseEndSlurNote = slurMeta != null && rawStartTick == slurMeta.lastOnsetTick
            val isAfterSlurAttack = slurMeta != null && rawStartTick > slurMeta.firstTick

            // Interior slurred notes (without explicit accent) receive a gentle legato re-attack
            if (isAfterSlurAttack && n.accent == null && !dynRes.isFortepiano) {
                finalVel *= 0.95f
            }

            n.articulation?.let { art ->
                finalVel *= art.velocityScale
            }
            n.accent?.let { acc ->
                finalVel = max(finalVel + acc.velocityBoost, acc.minVelocity)
            }
            finalVel = finalVel.coerceIn(0.12f, 1.22f)

            // 3. Acoustic Gate Duration & Damper Release Flags
            var actualDurFrames: Long = when (val art = n.articulation) {
                NoteArticulation.STACCATO, NoteArticulation.STACCATISSIMO -> {
                    flags = flags or NoteSynthFlags.CRISP_RELEASE
                    val scaled = (nominalDurFrames.toDouble() * art.gateRatio).roundToLong()
                    val capFrames = art.maxCapSec?.let { (it * sr.toDouble()).roundToLong() } ?: Long.MAX_VALUE
                    min(scaled, capFrames).coerceAtLeast(512L)
                }
                NoteArticulation.PORTATO -> {
                    flags = flags or NoteSynthFlags.PORTATO_RELEASE
                    (nominalDurFrames.toDouble() * art.gateRatio).roundToLong().coerceAtLeast(640L)
                }
                NoteArticulation.TENUTO -> {
                    nominalDurFrames
                }
                null -> {
                    when {
                        n.hasFermata -> {
                            // Hold richly through 94% of the fermata-stretched beat with a natural breath at the end
                            (nominalDurFrames.toDouble() * 0.94).roundToLong().coerceAtLeast(1024L)
                        }
                        isInteriorSlurNote -> {
                            flags = flags or NoteSynthFlags.LEGATO_OVERLAP
                            // Check if next note on the same slot has the EXACT same MIDI pitch:
                            // if same pitch, leave a tiny 10ms re-articulation gap; if different pitch, overlap by +22ms for true Legato!
                            val samePitchImmediateNext = pitchedNotes.any { other ->
                                other.id != n.id &&
                                    other.keyboardSlot == n.keyboardSlot &&
                                    other.midiNote == n.midiNote &&
                                    abs(other.resolvedStartTick - (rawStartTick + rawDurTicks)) <= 10
                            }
                            if (samePitchImmediateNext) {
                                (nominalDurFrames - (0.010 * sr).roundToLong()).coerceAtLeast(512L)
                            } else {
                                nominalDurFrames + (0.022 * sr).roundToLong()
                            }
                        }
                        isPhraseEndSlurNote -> {
                            flags = flags or NoteSynthFlags.PORTATO_RELEASE
                            (nominalDurFrames.toDouble() * 0.88).roundToLong().coerceAtLeast(640L)
                        }
                        else -> nominalDurFrames
                    }
                }
            }

            // Apply Marcato (`^`) punchy detached gate cap unless overridden by explicit shorter staccato or fermata
            if (n.accent == NoteAccent.MARCATO && n.articulation == null && !n.hasFermata) {
                flags = flags or NoteSynthFlags.CRISP_RELEASE
                val marcatoCap = (nominalDurFrames.toDouble() * 0.78).roundToLong().coerceAtLeast(640L)
                actualDurFrames = min(actualDurFrames, marcatoCap)
            }

            nativeNotes += NativeSynthNote(
                startFrame = startFrame,
                durationFrames = actualDurFrames.coerceAtLeast(512L),
                midiNote = n.midiNote.coerceIn(12, 108),
                velocity = finalVel,
                slot = n.keyboardSlot.coerceIn(0, 1),
                flags = flags
            )
        }

        val ticksPerBar = melody.ticksPerBar.coerceAtLeast(60)

        // 4. Optional Above-Staff Chord Symbol Accompaniment (`和弦真实发声`)
        if (melody.chordPlaybackEnabled && melody.chordSymbols.isNotEmpty()) {
            val chordSlot = when {
                melody.dualKeyboardEnabled -> 1
                melody.instrument == StudioInstrument.SOLO_FLUTE ||
                    melody.instrument == StudioInstrument.ALTO_SAX ||
                    melody.instrument == StudioInstrument.MUSIC_BOX -> 1
                else -> 0
            }
            val hasScoreDyn = melody.dynamicMarks.isNotEmpty() || melody.hairpins.isNotEmpty()
            val sortedChords = melody.chordSymbols
                .sortedBy { it.safeTick }
                .fold(mutableListOf<ScoreChordSymbol>()) { acc, cs ->
                    val prev = acc.lastOrNull()
                    if (prev != null && abs(prev.safeTick - cs.safeTick) <= 8) {
                        acc[acc.lastIndex] = cs
                    } else {
                        acc += cs
                    }
                    acc
                }

            for (idx in sortedChords.indices) {
                val chord = sortedChords[idx]
                val parsed = MelodyChordHelper.parseChordSymbol(chord.symbol) ?: continue
                val cStartTick = chord.safeTick
                val barEndTick = ((cStartTick / ticksPerBar) + 1) * ticksPerBar
                val nextChordTick = sortedChords.getOrNull(idx + 1)?.safeTick ?: Int.MAX_VALUE
                val baseEndTick = min(nextChordTick, barEndTick).coerceAtLeast(cStartTick + TICKS_PER_STEP)

                val dynScale = if (hasScoreDyn) {
                    (dynEval.evaluateAtTick(cStartTick, 0.85f).velocity / DynamicLevel.MF.targetVelocity)
                        .coerceIn(0.55f, 1.25f)
                } else {
                    1.0f
                }

                val voiced = MelodyChordHelper.voiceChordForAccompaniment(parsed)
                for ((midi, baseWeight) in voiced) {
                    // Avoid double-striking if an explicit score note on the same slot attacks this exact pitch
                    val duplicateAtStart = pitchedNotes.any { other ->
                        other.keyboardSlot.coerceIn(0, 1) == chordSlot &&
                            other.midiNote == midi &&
                            abs(other.resolvedStartTick - cStartTick) <= 15
                    }
                    if (duplicateAtStart) continue

                    val nextCollisionTick = pitchedNotes
                        .asSequence()
                        .filter { other ->
                            other.keyboardSlot.coerceIn(0, 1) == chordSlot &&
                                other.midiNote == midi &&
                                other.resolvedStartTick in (cStartTick + 16) until baseEndTick
                        }
                        .minOfOrNull { it.resolvedStartTick - 6 }
                    val voiceEndTick = (nextCollisionTick ?: baseEndTick)
                        .coerceAtLeast(cStartTick + TICKS_PER_STEP / 2)

                    val startBeat = cStartTick.toDouble() / TICKS_PER_BEAT.toDouble()
                    val endBeat = voiceEndTick.toDouble() / TICKS_PER_BEAT.toDouble()
                    val wStartTick = if (melody.swingPreset.isEnabled) {
                        melody.swingPreset.warpBeat(startBeat, barBeatsD) * TICKS_PER_BEAT.toDouble()
                    } else {
                        cStartTick.toDouble()
                    }
                    val wEndTick = if (melody.swingPreset.isEnabled) {
                        melody.swingPreset.warpBeat(endBeat, barBeatsD) * TICKS_PER_BEAT.toDouble()
                    } else {
                        voiceEndTick.toDouble()
                    }

                    val startFrame = tempoMap.tickToFrame(wStartTick)
                    val endFrame = tempoMap.tickToFrame(wEndTick)
                    val durFrames = ((endFrame - startFrame).toDouble() * 0.94)
                        .roundToLong()
                        .coerceAtLeast(640L)
                    val vel = (baseWeight * dynScale).coerceIn(0.18f, 0.66f)

                    nativeNotes += NativeSynthNote(
                        startFrame = startFrame,
                        durationFrames = durFrames,
                        midiNote = midi.coerceIn(12, 108),
                        velocity = vel,
                        slot = chordSlot,
                        flags = NoteSynthFlags.PORTATO_RELEASE
                    )
                }
            }
        }

        if (nativeNotes.size > 1) {
            nativeNotes.sortBy { it.startFrame }
        }

        val activeTicks = if (melody.isRecording) {
            melody.totalTicks
        } else {
            val maxContentTick = maxOf(
                melody.notes.maxOfOrNull { it.resolvedEndTick } ?: 0,
                melody.maxPlayableEndTick
            )
            val refTick = if (maxContentTick > 0) maxContentTick else melody.totalTicks
            val barsNeeded = ceil(refTick.toDouble() / ticksPerBar.toDouble()).toInt().coerceAtLeast(1)
            val effectiveBars = maxOf(barsNeeded, if (maxContentTick == 0) melody.totalBars else barsNeeded)
            effectiveBars * ticksPerBar
        }
        val totalFrames = tempoMap.tickToFrame(activeTicks.toDouble()).coerceAtLeast(sr.toLong())
        val (metroFrames, metroAccents) = tempoMap.buildMetronomePulses(
            maxTicks = max(activeTicks, melody.totalTicks),
            timeSignature = melody.timeSignature
        )

        return CompiledMelodySchedule(
            notes = nativeNotes,
            totalFrames = totalFrames,
            tempoMap = tempoMap,
            metronomePulseFrames = metroFrames,
            metronomePulseAccents = metroAccents
        )
    }
}
