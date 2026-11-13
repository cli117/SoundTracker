package com.soundtracker.app.melody

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hit-test region for interactive on-screen tapping of engraved notes, rests,
 * dynamics (`pp..ff, sfz, fp`), hairpins (`<, >`), and tempo marks (`Allegro ♩=xx, rit., accel., a tempo`).
 */
data class StaffHitTarget(
    val noteId: String? = null,
    val annotation: SelectedScoreAnnotation? = null,
    val bounds: RectF,
    val startTick: Int
)

/**
 * Pure vector 5-line Staff (`五线谱`) engraving engine shared by:
 * 1. The interactive Compose on-screen score canvas (single-system horizontal scroll + live playhead + step cursor)
 * 2. Multi-page publication-quality A4 vector PDF export (`PdfDocument`)
 *
 * Supports Treble & Grand Staff, configurable Time Signatures (`N/D`), standard & dotted notes, rests,
 * cross-bar ties, multi-note Phrase Slurs (`圆滑线` with obstacle avoidance & PDF line-break continuation),
 * Articulations (`.`, `▼`, `-`, `-.`, `𝄐`), Dynamics (`pp..ff`, `<`, `>`, `>`, `^`, `sfz`, `fp`),
 * Tempo Map (`Largo..Presto`, `♩ = xx`, `rit.`, `accel.`, `a tempo`), Swing markings, and Tuplets (`2, 3, 5, 6`).
 */
object StaffScoreEngraver {

    // Treble staff lines: E4(30), G4(32), B4(34), D5(36), F5(38)
    private const val TREBLE_TOP_STEP = 38    // F5
    private const val TREBLE_MIDDLE_STEP = 34 // B4
    private const val TREBLE_BOTTOM_STEP = 30 // E4

    // Bass staff lines: G2(18), B2(20), D3(22), F3(24), A3(26)
    private const val BASS_TOP_STEP = 26      // A3
    private const val BASS_MIDDLE_STEP = 22   // D3
    private const val BASS_BOTTOM_STEP = 18   // G2

    // Legacy fallback pitch threshold (< G#3 / MIDI 56) for old archives without explicit StaffPart tags
    const val BASS_SPLIT_MIDI = 56

    private val STANDARD_SLICE_STEPS = intArrayOf(16, 12, 8, 6, 4, 3, 2, 1)

    private data class PitchSpelling(
        val diatonicStep: Int, // oct * 7 + (0..6 for C..B)
        val isSharp: Boolean
    )

    private data class SlicedSymbol(
        val sourceNoteId: String?,
        val barIndex: Int,
        val tickInBar: Int,
        val durTicks: Int,
        val baseDenom: Int,   // 1, 2, 4, 8, 16
        val isDotted: Boolean,
        val isRest: Boolean,
        val isAutoRest: Boolean,
        val useBassStaff: Boolean,
        val midiNote: Int,
        val keyboardSlot: Int,
        val tiedFromPrev: Boolean,
        val tiedToNext: Boolean,
        val tuplet: NoteTupletTag? = null,
        val slurGroupId: String? = null,
        val articulation: NoteArticulation? = null,
        val accent: NoteAccent? = null,
        val hasFermata: Boolean = false
    )

    private data class ChordEvent(
        val barIndex: Int,
        val tickInBar: Int,
        val durTicks: Int,
        val baseDenom: Int,
        val isDotted: Boolean,
        val useBassStaff: Boolean,
        val tuplet: NoteTupletTag?,
        val notes: List<SlicedSymbol> // sorted low -> high midiNote
    )

    private data class RenderedBeamInfo(
        val chordIndices: List<Int>,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val stemUp: Boolean,
        val color: Int
    )

    private data class MeasureSpanGeom(
        val barIndex: Int,
        val barLeftX: Float,
        val barRightX: Float,
        val noteOriginX: Float,
        val usableWidth: Float
    )

    private data class RenderedChordAnchor(
        val barIndex: Int,
        val absStartTick: Int,
        val absEndTick: Int,
        val useBassStaff: Boolean,
        val sourceNoteIds: Set<String>,
        val slurGroupIds: Set<String>,
        val xCenter: Float,
        val stemUp: Boolean,
        val topNoteY: Float,
        val bottomNoteY: Float,
        var topExtremeY: Float,
        var bottomExtremeY: Float,
        val color: Int
    )

    private data class RenderedTupletObstacle(
        val useBassStaff: Boolean,
        val x1: Float,
        val x2: Float,
        val topY: Float,
        val bottomY: Float
    )

    private data class PendingFermataRender(
        val xCenter: Float,
        val staffTopY: Float,
        val useBassStaff: Boolean,
        var minClearanceTopY: Float,
        val color: Int
    )

    fun needsGrandStaff(notes: List<MelodyScoreNote>): Boolean =
        notes.any { it.targetStaff == StaffPart.BASS || (!it.isRest && it.midiNote < BASS_SPLIT_MIDI) }

    fun isEffectiveGrandStaff(melody: MelodyStudioUiState): Boolean =
        melody.grandStaffEnabled

    /**
     * Computes sticky left header width in px so Clef + active Key Signature (`0..7` sharps/flats) + Time Signature
     * always fit comfortably without crowding.
     */
    fun computeStickyHeaderWidthPx(
        baseHeaderPx: Float,
        activeKeySignature: KeySignature,
        lineSpacingEstimate: Float
    ): Float {
        val count = abs(activeKeySignature.safeFifths)
        if (count == 0) return baseHeaderPx
        return baseHeaderPx + count * (lineSpacingEstimate * 0.68f) + lineSpacingEstimate * 0.35f
    }

    fun keySignatureGroupWidth(
        cancelledCount: Int,
        newSigCount: Int,
        s: Float
    ): Float {
        val total = cancelledCount + newSigCount
        if (total <= 0) return 0f
        val stepAdv = s * 0.68f
        val splitGap = if (cancelledCount > 0 && newSigCount > 0) s * 0.20f else 0f
        return total * stepAdv + splitGap + s * 0.26f
    }

    private fun midBarKeyChangeWidth(
        melody: MelodyStudioUiState,
        barIndex: Int,
        s: Float
    ): Float {
        if (barIndex < 1) return 0f
        val ev = melody.keySignatureEventAtBar(barIndex) ?: return 0f
        val prevKey = melody.keySignatureAtBar(barIndex - 1)
        val cancelCnt = prevKey.cancelledStaffStepsTo(ev.keySignature, false).size
        val newCnt = abs(ev.keySignature.safeFifths)
        return keySignatureGroupWidth(cancelCnt, newCnt, s)
    }

    private fun onScreenMeasureLayout(
        melody: MelodyStudioUiState,
        barIndex: Int,
        barLeft: Float,
        barWidth: Float,
        lineSpacing: Float
    ): Pair<Float, Float> {
        val basePadLeft = lineSpacing * 1.35f
        val padRight = lineSpacing * 0.85f
        val midKeyW = midBarKeyChangeWidth(melody, barIndex, lineSpacing)
        val effectivePadLeft = if (midKeyW > 0f) {
            max(basePadLeft, lineSpacing * 0.48f + midKeyW + lineSpacing * 0.72f)
        } else if (barIndex >= 1 && melody.keySignatureEventAtBar(barIndex) != null) {
            // Modulation to C/a where previous key was also 0 accidentals (mode change only)
            max(basePadLeft, lineSpacing * 1.55f)
        } else {
            basePadLeft
        }
        val usableW = (barWidth - effectivePadLeft - padRight).coerceAtLeast(lineSpacing * 3.2f)
        return (barLeft + effectivePadLeft) to usableW
    }

    private fun spellMidi(midiNote: Int): PitchSpelling {
        val clamped = midiNote.coerceIn(12, 108)
        val oct = (clamped / 12) - 1
        val pc = ((clamped % 12) + 12) % 12
        val (deg, sharp) = when (pc) {
            0 -> 0 to false // C
            1 -> 0 to true  // #C
            2 -> 1 to false // D
            3 -> 1 to true  // #D
            4 -> 2 to false // E
            5 -> 3 to false // F
            6 -> 3 to true  // #F
            7 -> 4 to false // G
            8 -> 4 to true  // #G
            9 -> 5 to false // A
            10 -> 5 to true // #A
            else -> 6 to false // B
        }
        return PitchSpelling(diatonicStep = oct * 7 + deg, isSharp = sharp)
    }

    private fun denomAndDotForSteps(steps: Int): Pair<Int, Boolean> = when (steps) {
        16 -> 1 to false
        12 -> 2 to true
        8 -> 2 to false
        6 -> 4 to true
        4 -> 4 to false
        3 -> 8 to true
        2 -> 8 to false
        else -> 16 to false
    }

    private fun decomposeSteps(totalSteps: Int): List<Int> {
        val out = mutableListOf<Int>()
        var rem = totalSteps.coerceAtLeast(1)
        while (rem > 0) {
            val pick = STANDARD_SLICE_STEPS.firstOrNull { it <= rem } ?: 1
            out += pick
            rem -= pick
        }
        return out
    }

    /**
     * Converts raw [MelodyScoreNote] items into bar-aligned, rhythmically valid notation slices
     * across `0 until totalBars` under [timeSignature], preserving exact Tuplet subdivisions (`2`, `3`, `5`, `6`),
     * Slur phrase memberships, Articulations, Accents, Fermatas, and sight-readable gap rests.
     */
    private fun buildBarSymbols(
        notes: List<MelodyScoreNote>,
        totalBars: Int,
        timeSignature: TimeSignature,
        grandStaff: Boolean,
        fillAutoRestsInActiveBars: Boolean,
        fillAllBarsWithAutoRests: Boolean
    ): List<SlicedSymbol> {
        val ticksPerBar = timeSignature.ticksPerBar
        val stepsPerBar = timeSignature.stepsPerBar
        val maxTickLimit = totalBars.coerceAtLeast(1) * ticksPerBar
        val maxStepLimit = totalBars.coerceAtLeast(1) * stepsPerBar
        val symbols = mutableListOf<SlicedSymbol>()

        for (n in notes) {
            val onBass = grandStaff && (n.resolvedStaffPart(true) == StaffPart.BASS)
            val tup = n.tuplet

            if (tup != null) {
                val rawStartTick = n.resolvedStartTick.coerceAtLeast(0)
                if (rawStartTick >= maxTickLimit) continue
                val barIdx = rawStartTick / ticksPerBar
                val tickInBar = rawStartTick % ticksPerBar
                val durTicks = min(ticksPerBar - tickInBar, n.resolvedDurationTicks.coerceAtLeast(1))
                if (durTicks <= 0) continue

                symbols += SlicedSymbol(
                    sourceNoteId = n.id,
                    barIndex = barIdx,
                    tickInBar = tickInBar,
                    durTicks = durTicks,
                    baseDenom = tup.writtenBaseDenom,
                    isDotted = false,
                    isRest = n.isRest,
                    isAutoRest = false,
                    useBassStaff = onBass,
                    midiNote = if (n.isRest) DEFAULT_REST_MIDI else n.midiNote,
                    keyboardSlot = n.keyboardSlot,
                    tiedFromPrev = false,
                    tiedToNext = false,
                    tuplet = tup,
                    slurGroupId = if (n.isRest) null else n.slurGroupId,
                    articulation = if (n.isRest) null else n.articulation,
                    accent = if (n.isRest) null else n.accent,
                    hasFermata = n.hasFermata
                )
            } else {
                val rawStartStep = (n.resolvedStartTick.toFloat() / TICKS_PER_STEP.toFloat()).roundToInt().coerceAtLeast(0)
                if (rawStartStep >= maxStepLimit) continue
                val rawDurSteps = (n.resolvedDurationTicks.toFloat() / TICKS_PER_STEP.toFloat()).roundToInt().coerceAtLeast(1)
                val endStep = min(maxStepLimit, rawStartStep + rawDurSteps)
                if (endStep <= rawStartStep) continue

                // Split across bar boundaries, then decompose each bar piece into standard rhythmic values
                val pieces = mutableListOf<Pair<Int, Int>>() // (absStartStep, pieceSteps)
                var cur = rawStartStep
                while (cur < endStep) {
                    val barEnd = ((cur / stepsPerBar) + 1) * stepsPerBar
                    val chunkEnd = min(endStep, barEnd)
                    val subSlices = decomposeSteps(chunkEnd - cur)
                    var subCur = cur
                    for (st in subSlices) {
                        pieces += subCur to st
                        subCur += st
                    }
                    cur = chunkEnd
                }

                for ((idx, pair) in pieces.withIndex()) {
                    val (absStep, durSt) = pair
                    val (denom, dotted) = denomAndDotForSteps(durSt)
                    val isFirstPiece = (idx == 0)
                    val isLastPiece = (idx == pieces.lastIndex)
                    symbols += SlicedSymbol(
                        sourceNoteId = n.id,
                        barIndex = absStep / stepsPerBar,
                        tickInBar = (absStep % stepsPerBar) * TICKS_PER_STEP,
                        durTicks = durSt * TICKS_PER_STEP,
                        baseDenom = denom,
                        isDotted = dotted,
                        isRest = n.isRest,
                        isAutoRest = false,
                        useBassStaff = onBass,
                        midiNote = if (n.isRest) DEFAULT_REST_MIDI else n.midiNote,
                        keyboardSlot = n.keyboardSlot,
                        tiedFromPrev = !n.isRest && !isFirstPiece,
                        tiedToNext = !n.isRest && !isLastPiece,
                        tuplet = null,
                        slurGroupId = if (n.isRest) null else n.slurGroupId,
                        articulation = if (!n.isRest && isLastPiece) n.articulation else null,
                        accent = if (!n.isRest && isFirstPiece) n.accent else null,
                        hasFermata = n.hasFermata && isLastPiece
                    )
                }
            }
        }

        // Fill silent gaps independently on Treble and Bass staves
        if (fillAutoRestsInActiveBars || fillAllBarsWithAutoRests) {
            val maxActiveBar = symbols.maxOfOrNull { it.barIndex } ?: -1
            for (bar in 0 until totalBars) {
                val barSyms = symbols.filter { it.barIndex == bar }
                val shouldFill = fillAllBarsWithAutoRests || (barSyms.isNotEmpty() && bar <= maxActiveBar)
                if (!shouldFill) continue

                val trebleSyms = barSyms.filter { !it.useBassStaff }
                if (fillAllBarsWithAutoRests || trebleSyms.isNotEmpty()) {
                    fillStaffGapsForMeasure(
                        out = symbols,
                        existingStaffBarSyms = trebleSyms,
                        barIndex = bar,
                        timeSignature = timeSignature,
                        useBassStaff = false,
                        onlyBetweenOrFullPdf = fillAllBarsWithAutoRests
                    )
                }

                if (grandStaff) {
                    val bassSyms = barSyms.filter { it.useBassStaff }
                    if (fillAllBarsWithAutoRests || bassSyms.isNotEmpty()) {
                        fillStaffGapsForMeasure(
                            out = symbols,
                            existingStaffBarSyms = bassSyms,
                            barIndex = bar,
                            timeSignature = timeSignature,
                            useBassStaff = true,
                            onlyBetweenOrFullPdf = fillAllBarsWithAutoRests
                        )
                    }
                }
            }
        }

        return symbols
    }

    private fun fillStaffGapsForMeasure(
        out: MutableList<SlicedSymbol>,
        existingStaffBarSyms: List<SlicedSymbol>,
        barIndex: Int,
        timeSignature: TimeSignature,
        useBassStaff: Boolean,
        onlyBetweenOrFullPdf: Boolean
    ) {
        val ticksPerBar = timeSignature.ticksPerBar
        val stepsPerBar = timeSignature.stepsPerBar

        val tupletTags = existingStaffBarSyms
            .mapNotNull { it.tuplet }
            .distinctBy { it.groupKey }

        for (tag in tupletTags) {
            val gStartInBar = (tag.groupStartTick - barIndex * ticksPerBar).coerceIn(0, (ticksPerBar - 1).coerceAtLeast(0))
            for (slotIdx in 0 until tag.count) {
                val slotStart = gStartInBar + slotIdx * tag.noteTicks
                if (slotStart >= ticksPerBar) break
                val slotEnd = min(ticksPerBar, slotStart + tag.noteTicks)
                val overlapped = existingStaffBarSyms.any { s ->
                    s.tickInBar < slotEnd && (s.tickInBar + s.durTicks) > slotStart
                }
                if (!overlapped) {
                    out += SlicedSymbol(
                        sourceNoteId = null,
                        barIndex = barIndex,
                        tickInBar = slotStart,
                        durTicks = slotEnd - slotStart,
                        baseDenom = tag.writtenBaseDenom,
                        isDotted = false,
                        isRest = true,
                        isAutoRest = true,
                        useBassStaff = useBassStaff,
                        midiNote = DEFAULT_REST_MIDI,
                        keyboardSlot = 0,
                        tiedFromPrev = false,
                        tiedToNext = false,
                        tuplet = tag
                    )
                }
            }
        }

        val stepOccupied = BooleanArray(stepsPerBar)
        for (s in existingStaffBarSyms) {
            val sStart = s.tickInBar
            val sEnd = s.tickInBar + s.durTicks
            for (st in 0 until stepsPerBar) {
                val stTick0 = st * TICKS_PER_STEP
                val stTick1 = stTick0 + TICKS_PER_STEP
                if (sStart < stTick1 && sEnd > stTick0) {
                    stepOccupied[st] = true
                }
            }
        }
        for (tag in tupletTags) {
            val gStart = (tag.groupStartTick - barIndex * ticksPerBar).coerceIn(0, (ticksPerBar - 1).coerceAtLeast(0))
            val gEnd = min(ticksPerBar, gStart + tag.spanTicks)
            for (st in 0 until stepsPerBar) {
                val stTick0 = st * TICKS_PER_STEP
                val stTick1 = stTick0 + TICKS_PER_STEP
                if (gStart < stTick1 && gEnd > stTick0) {
                    stepOccupied[st] = true
                }
            }
        }

        appendGapAutoRests(
            out = out,
            occupied = stepOccupied,
            barIndex = barIndex,
            timeSignature = timeSignature,
            useBassStaff = useBassStaff,
            onlyBetweenOrFullPdf = onlyBetweenOrFullPdf
        )
    }

    private fun appendGapAutoRests(
        out: MutableList<SlicedSymbol>,
        occupied: BooleanArray,
        barIndex: Int,
        timeSignature: TimeSignature,
        useBassStaff: Boolean,
        onlyBetweenOrFullPdf: Boolean
    ) {
        val ticksPerBar = timeSignature.ticksPerBar
        val stepsPerBar = timeSignature.stepsPerBar
        if (occupied.none { it }) {
            if (onlyBetweenOrFullPdf) {
                out += SlicedSymbol(
                    sourceNoteId = null,
                    barIndex = barIndex,
                    tickInBar = 0,
                    durTicks = ticksPerBar,
                    baseDenom = 1,
                    isDotted = false,
                    isRest = true,
                    isAutoRest = true,
                    useBassStaff = useBassStaff,
                    midiNote = DEFAULT_REST_MIDI,
                    keyboardSlot = 0,
                    tiedFromPrev = false,
                    tiedToNext = false,
                    tuplet = null
                )
            }
            return
        }

        val lastOccupiedStep = occupied.indexOfLast { it }
        val limitStep = if (onlyBetweenOrFullPdf) stepsPerBar else (lastOccupiedStep + 1)
        val unitSteps = timeSignature.unitSteps.coerceAtLeast(1)
        val groupSteps = timeSignature.restGroupSteps.coerceAtLeast(unitSteps)

        var step = 0
        while (step < limitStep) {
            if (occupied[step]) {
                step++
                continue
            }
            val unitBoundary = ((step / unitSteps) + 1) * unitSteps
            val groupBoundary = ((step / groupSteps) + 1) * groupSteps
            val cap = if (step % unitSteps == 0) {
                min(limitStep, groupBoundary)
            } else {
                min(limitStep, unitBoundary)
            }
            var runEnd = step
            while (runEnd < cap && !occupied[runEnd]) {
                runEnd++
            }
            if (runEnd <= step) runEnd = min(limitStep, step + 1)
            val slices = decomposeSteps(runEnd - step)
            var cur = step
            for (sl in slices) {
                val (denom, dotted) = denomAndDotForSteps(sl)
                out += SlicedSymbol(
                    sourceNoteId = null,
                    barIndex = barIndex,
                    tickInBar = cur * TICKS_PER_STEP,
                    durTicks = sl * TICKS_PER_STEP,
                    baseDenom = denom,
                    isDotted = dotted,
                    isRest = true,
                    isAutoRest = true,
                    useBassStaff = useBassStaff,
                    midiNote = DEFAULT_REST_MIDI,
                    keyboardSlot = 0,
                    tiedFromPrev = false,
                    tiedToNext = false,
                    tuplet = null
                )
                cur += sl
            }
            step = runEnd
        }
    }

    // ========================================================================
    // 1. On-Screen Interactive Single-Timeline 5-Line Staff Renderer
    // ========================================================================

    class OnScreenPalette(
        val paperBgColor: Int = Color.rgb(250, 247, 240),
        val inkColor: Int = Color.rgb(42, 38, 34),
        val staffLineColor: Int = Color.rgb(122, 114, 103),
        val barLineColor: Int = Color.rgb(92, 85, 76),
        val subGridColor: Int = Color.argb(70, 175, 166, 152),
        val slot0Color: Int = Color.rgb(42, 38, 34),
        val slot1Color: Int = Color.rgb(26, 122, 94),
        val selectedColor: Int = Color.rgb(124, 77, 204),
        val expressionInkColor: Int = Color.rgb(58, 46, 38),
        val autoRestColor: Int = Color.argb(115, 110, 102, 92),
        val cursorColor: Int = Color.rgb(201, 100, 66),
        val playheadColor: Int = Color.rgb(190, 58, 48)
    )

    private data class TimelineVerticalMetrics(
        val grandStaff: Boolean,
        val lineSpacing: Float,
        val staffHeight: Float,
        val grandGap: Float,
        val trebleTopY: Float,
        val bassTopY: Float,
        val bottomStaffBottomY: Float
    )

    private fun computeTimelineVerticalMetrics(
        viewportHeight: Float,
        melody: MelodyStudioUiState
    ): TimelineVerticalMetrics {
        val grandStaff = isEffectiveGrandStaff(melody)
        val hasChordMarks = melody.showChordSymbols && melody.chordSymbols.isNotEmpty()
        val hasTempoOrMod = melody.tempoEvents.isNotEmpty() ||
            melody.keySignatureEvents.isNotEmpty() ||
            melody.notes.any { it.hasFermata || it.accent == NoteAccent.MARCATO }
        val hasTopMarks = hasChordMarks || hasTempoOrMod
        val hasDualTopLayers = hasChordMarks && melody.tempoEvents.isNotEmpty()
        val hasBottomMarks = melody.dynamicMarks.isNotEmpty() || melody.hairpins.isNotEmpty()

        val baseDivisor = when {
            grandStaff && (hasTopMarks || hasBottomMarks) -> 17.6f
            grandStaff -> 15.8f
            hasTopMarks || hasBottomMarks -> 11.6f
            else -> 9.6f
        } + (if (hasDualTopLayers) 1.3f else 0f)

        val lineSpacing = if (grandStaff) {
            (viewportHeight / baseDivisor).coerceIn(5.4f, 11.6f)
        } else {
            (viewportHeight / baseDivisor).coerceIn(6.4f, 13.6f)
        }
        val staffHeight = lineSpacing * 4f
        val grandGap = lineSpacing * 3.5f

        val topBias = when {
            hasDualTopLayers -> lineSpacing * 1.15f
            hasTopMarks && !hasBottomMarks -> lineSpacing * 0.85f
            !hasTopMarks && hasBottomMarks -> -lineSpacing * 0.45f
            else -> lineSpacing * 0.25f
        }
        val minTopHeadroom = when {
            hasDualTopLayers -> lineSpacing * 4.35f
            hasChordMarks || melody.tempoEvents.isNotEmpty() -> lineSpacing * 3.25f
            else -> lineSpacing * 2.85f
        }
        val trebleTopY: Float
        val bassTopY: Float
        if (grandStaff) {
            val totalBlockH = staffHeight * 2f + grandGap
            trebleTopY = ((viewportHeight - totalBlockH) * 0.5f + topBias)
                .coerceAtLeast(minTopHeadroom)
            bassTopY = trebleTopY + staffHeight + grandGap
        } else {
            trebleTopY = ((viewportHeight - staffHeight) * 0.5f + topBias)
                .coerceAtLeast(minTopHeadroom)
            bassTopY = trebleTopY
        }
        val bottomStaffBottomY = if (grandStaff) bassTopY + staffHeight else trebleTopY + staffHeight
        return TimelineVerticalMetrics(
            grandStaff = grandStaff,
            lineSpacing = lineSpacing,
            staffHeight = staffHeight,
            grandGap = grandGap,
            trebleTopY = trebleTopY,
            bassTopY = bassTopY,
            bottomStaffBottomY = bottomStaffBottomY
        )
    }

    data class CursorScreenGeom(
        val x: Float,
        val handleCenterY: Float,
        val handleRadius: Float,
        val activeTopY: Float,
        val activeBottomY: Float,
        val fullTopY: Float,
        val fullBottomY: Float
    ) {
        fun isInHandleHotzone(px: Float, py: Float, extraX: Float, extraY: Float): Boolean {
            return abs(px - x) <= (handleRadius + extraX) &&
                py >= (handleCenterY - handleRadius - extraY) &&
                py <= (activeTopY + handleRadius * 0.9f + extraY * 0.55f)
        }

        fun isInLineHotzone(px: Float, py: Float, halfWidthPx: Float, extraY: Float): Boolean {
            return abs(px - x) <= halfWidthPx &&
                py >= (fullTopY - extraY) &&
                py <= (fullBottomY + extraY)
        }
    }

    private fun resolveCursorColor(
        melody: MelodyStudioUiState,
        palette: OnScreenPalette
    ): Int = when {
        melody.activeSlurGroupId != null -> Color.rgb(26, 122, 94)
        melody.activeTuplet != null -> palette.selectedColor
        else -> palette.cursorColor
    }

    private fun drawStepCursorWithHandle(
        canvas: Canvas,
        cx: Float,
        trebleTopY: Float,
        bassTopY: Float,
        bottomStaffBottomY: Float,
        staffHeight: Float,
        lineSpacing: Float,
        grandStaff: Boolean,
        activeStaff: StaffPart,
        cursorColor: Int,
        paperBgColor: Int,
        isDraggingCursor: Boolean,
        minClampTopY: Float? = null,
        maxClampBottomY: Float? = null
    ): CursorScreenGeom {
        if (grandStaff) {
            val ghostGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(
                    if (isDraggingCursor) 96 else 68,
                    Color.red(cursorColor),
                    Color.green(cursorColor),
                    Color.blue(cursorColor)
                )
                strokeWidth = max(1.1f, lineSpacing * 0.11f)
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(lineSpacing * 0.45f, lineSpacing * 0.55f), 0f)
            }
            val gTop = minClampTopY?.let { max(it, trebleTopY - lineSpacing * 0.6f) } ?: (trebleTopY - lineSpacing * 0.6f)
            val gBot = maxClampBottomY?.let { min(it, bottomStaffBottomY + lineSpacing * 0.6f) } ?: (bottomStaffBottomY + lineSpacing * 0.6f)
            canvas.drawLine(cx, gTop, cx, gBot, ghostGuidePaint)
        }

        val activeStaffTopY = if (grandStaff && activeStaff == StaffPart.BASS) bassTopY else trebleTopY
        val activeStaffBotY = if (grandStaff) (activeStaffTopY + staffHeight) else bottomStaffBottomY
        val pinTipY = (activeStaffTopY - lineSpacing * 0.64f).let { y ->
            minClampTopY?.let { max(it + 8f, y) } ?: y
        }
        val baseCapR = max(5.2f, lineSpacing * 0.66f)
        val capR = if (isDraggingCursor) baseCapR * 1.18f else baseCapR
        val capCenterY = (pinTipY - capR * 1.46f).let { y ->
            minClampTopY?.let { max(it + capR + 1f, y) } ?: y
        }

        val lineTopY = min(pinTipY, activeStaffTopY - lineSpacing * 0.85f)
        val lineBotY = (activeStaffBotY + lineSpacing * 1.05f).let { y ->
            maxClampBottomY?.let { min(it, y) } ?: y
        }

        val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cursorColor
            strokeWidth = max(1.9f, lineSpacing * (if (isDraggingCursor) 0.22f else 0.17f))
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(lineSpacing * 0.65f, lineSpacing * 0.45f), 0f)
        }
        canvas.drawLine(cx, lineTopY, cx, lineBotY, cursorPaint)

        // Soft halo ring when actively grabbed/scrubbing
        if (isDraggingCursor) {
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(
                    48,
                    Color.red(cursorColor),
                    Color.green(cursorColor),
                    Color.blue(cursorColor)
                )
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, capCenterY, capR * 1.85f, haloPaint)
        }

        // Teardrop / Inverted Shield-Pin Handle (`水滴/倒三角游标头`)
        val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cursorColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, capCenterY, capR, handleFillPaint)
        val conePath = Path().apply {
            moveTo(cx - capR * 0.86f, capCenterY + capR * 0.48f)
            lineTo(cx + capR * 0.86f, capCenterY + capR * 0.48f)
            lineTo(cx, max(pinTipY, capCenterY + capR * 0.95f))
            close()
        }
        canvas.drawPath(conePath, handleFillPaint)

        // Crisp warm-ivory keyhole center dot inside the handle bulb
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paperBgColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, capCenterY, capR * 0.34f, dotPaint)

        val fullTopY = if (grandStaff) min(capCenterY - capR, trebleTopY - lineSpacing * 0.6f) else (capCenterY - capR)
        val fullBotY = if (grandStaff) max(lineBotY, bottomStaffBottomY + lineSpacing * 0.6f) else lineBotY
        return CursorScreenGeom(
            x = cx,
            handleCenterY = capCenterY,
            handleRadius = baseCapR,
            activeTopY = activeStaffTopY,
            activeBottomY = activeStaffBotY,
            fullTopY = fullTopY,
            fullBottomY = fullBotY
        )
    }

    private fun drawCursorScrubBubble(
        canvas: Canvas,
        geom: CursorScreenGeom,
        viewportWidth: Float,
        viewportHeight: Float,
        minLeftX: Float,
        lineSpacing: Float,
        cursorColor: Int,
        melody: MelodyStudioUiState,
        grandStaff: Boolean
    ) {
        val label = MusicalNotationHelper.formatCursorBubbleLabel(
            tick = melody.cursorTick,
            timeSignature = melody.timeSignature,
            staffPart = if (grandStaff) melody.activeStaff else null
        )
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(250, 248, 243) // StudioSurface warm ivory
            textSize = max(13.5f, lineSpacing * 1.10f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val textW = textPaint.measureText(label)
        val padX = max(9f, lineSpacing * 0.92f)
        val pillW = textW + padX * 2f
        val pillH = max(23f, lineSpacing * 1.92f)
        val caretH = max(4.5f, lineSpacing * 0.42f)

        val preferredTop = geom.handleCenterY - geom.handleRadius - caretH - pillH - lineSpacing * 0.32f
        val placeBelow = preferredTop < 3f && (geom.activeBottomY + pillH + caretH + lineSpacing * 0.9f) <= (viewportHeight - 3f)
        val pillTop = if (placeBelow) {
            (geom.activeBottomY + caretH + lineSpacing * 0.65f).coerceAtMost(viewportHeight - pillH - 3f)
        } else {
            preferredTop.coerceAtLeast(3f)
        }
        val pillBottom = pillTop + pillH
        val safeMinLeft = minLeftX.coerceAtLeast(4f)
        val safeMaxLeft = (viewportWidth - pillW - 4f).coerceAtLeast(safeMinLeft)
        val pillLeft = (geom.x - pillW * 0.5f).coerceIn(safeMinLeft, safeMaxLeft)
        val pillRect = RectF(pillLeft, pillTop, pillLeft + pillW, pillBottom)
        val cornerR = pillH * 0.48f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(242, 31, 30, 27) // VioletAccent warm ink
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cursorColor
            strokeWidth = max(1.2f, lineSpacing * 0.12f)
            style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(pillRect, cornerR, cornerR, bgPaint)
        canvas.drawRoundRect(pillRect, cornerR, cornerR, borderPaint)

        // Small caret pointing at exact cursor X
        val caretX = geom.x.coerceIn(pillLeft + cornerR * 0.85f, pillLeft + pillW - cornerR * 0.85f)
        val caretPath = Path().apply {
            if (placeBelow) {
                moveTo(caretX - caretH * 0.95f, pillTop + 0.5f)
                lineTo(caretX + caretH * 0.95f, pillTop + 0.5f)
                lineTo(caretX, pillTop - caretH)
            } else {
                moveTo(caretX - caretH * 0.95f, pillBottom - 0.5f)
                lineTo(caretX + caretH * 0.95f, pillBottom - 0.5f)
                lineTo(caretX, pillBottom + caretH)
            }
            close()
        }
        canvas.drawPath(caretPath, bgPaint)

        val fm = textPaint.fontMetrics
        val baseline = pillRect.centerY() - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(label, pillLeft + padX, baseline, textPaint)
    }

    private fun snapInBarTickForBlankTap(
        rawFrac: Float,
        ticksPerBar: Int,
        melody: MelodyStudioUiState
    ): Int {
        val safeTpb = ticksPerBar.coerceAtLeast(TICKS_PER_STEP)
        val snapUnit = melody.smartBlankTapSnapTicks().coerceIn(1, safeTpb)
        val maxSlot = ((safeTpb - 1).coerceAtLeast(0)) / snapUnit
        val rawTickFloat = rawFrac.coerceIn(0f, 1f) * safeTpb.toFloat()
        val slot = (rawTickFloat / snapUnit.toFloat()).roundToInt().coerceIn(0, maxSlot)
        return (slot * snapUnit).coerceIn(0, (safeTpb - 1).coerceAtLeast(0))
    }

    private fun snapInBarTickForScrub(
        rawFrac: Float,
        barIdx: Int,
        ticksPerBar: Int,
        targetStaff: StaffPart,
        melody: MelodyStudioUiState
    ): Int {
        val safeTpb = ticksPerBar.coerceAtLeast(TICKS_PER_STEP)
        val barStartTick = barIdx.coerceAtLeast(0) * safeTpb
        val barEndTick = barStartTick + safeTpb
        val grand = melody.grandStaffEnabled

        val staffNotesInBar = melody.notes.filter { n ->
            val st = n.resolvedStartTick
            st in barStartTick until barEndTick &&
                (!grand || n.resolvedStaffPart(true) == targetStaff)
        }
        val tupletTags = staffNotesInBar.mapNotNull { it.tuplet }.distinctBy { it.groupKey }

        val candidates = ArrayList<Int>(32)
        var stepTick = 0
        while (stepTick < safeTpb) {
            val insideExistingTuplet = tupletTags.any { tag ->
                val gStart = (tag.groupStartTick - barStartTick).coerceAtLeast(0)
                val gEnd = (gStart + tag.spanTicks).coerceAtMost(safeTpb)
                stepTick > gStart && stepTick < gEnd
            }
            if (!insideExistingTuplet) {
                candidates.add(stepTick)
            }
            stepTick += TICKS_PER_STEP
        }
        for (tag in tupletTags) {
            val gStart = (tag.groupStartTick - barStartTick).coerceIn(0, safeTpb - 1)
            for (slotIdx in 0 until tag.count) {
                val t = gStart + slotIdx * tag.noteTicks
                if (t in 0 until safeTpb) {
                    candidates.add(t)
                }
            }
        }
        melody.activeTuplet?.let { tup ->
            val u = tup.noteTicks.coerceAtLeast(1)
            var t = 0
            while (t < safeTpb) {
                candidates.add(t)
                t += u
            }
        }
        for (n in staffNotesInBar) {
            val inBar = n.resolvedStartTick - barStartTick
            if (inBar in 0 until safeTpb) {
                candidates.add(inBar)
            }
        }
        for (ch in melody.chordSymbols) {
            if (ch.safeTick in barStartTick until barEndTick) {
                candidates.add(ch.safeTick - barStartTick)
            }
        }
        if (candidates.isEmpty()) return 0

        val targetFloat = rawFrac.coerceIn(0f, 1f) * safeTpb.toFloat()
        var bestTick = candidates[0]
        var bestDist = abs(bestTick.toFloat() - targetFloat)
        for (i in 1 until candidates.size) {
            val c = candidates[i]
            val d = abs(c.toFloat() - targetFloat)
            if (d < bestDist) {
                bestDist = d
                bestTick = c
            }
        }
        return bestTick.coerceIn(0, (safeTpb - 1).coerceAtLeast(0))
    }

    fun computeTimelineCursorScreenGeom(
        viewportWidth: Float,
        viewportHeight: Float,
        scrollX: Float,
        clefHeaderWidth: Float,
        barWidth: Float,
        melody: MelodyStudioUiState
    ): CursorScreenGeom? {
        if (melody.isRecording || viewportWidth <= clefHeaderWidth + 20f || viewportHeight <= 36f) return null
        val ticksPerBar = melody.timeSignature.ticksPerBar.coerceAtLeast(1)
        val vMetrics = computeTimelineVerticalMetrics(viewportHeight, melody)
        val lineSpacing = vMetrics.lineSpacing
        val cursorBar = melody.cursorTick.coerceAtLeast(0) / ticksPerBar
        val tickInBar = melody.cursorTick.coerceAtLeast(0) % ticksPerBar
        val barLeft = clefHeaderWidth + cursorBar * barWidth - scrollX
        val (cNoteOriginX, cUsableW) = onScreenMeasureLayout(
            melody = melody,
            barIndex = cursorBar,
            barLeft = barLeft,
            barWidth = barWidth,
            lineSpacing = lineSpacing
        )
        val cx = cNoteOriginX + (tickInBar.toFloat() / ticksPerBar.toFloat()) * cUsableW
        if (cx !in (clefHeaderWidth - lineSpacing * 0.5f)..(viewportWidth + lineSpacing * 0.5f)) return null
        val activeTopY = if (vMetrics.grandStaff && melody.activeStaff == StaffPart.BASS) {
            vMetrics.bassTopY
        } else {
            vMetrics.trebleTopY
        }
        val activeBotY = if (vMetrics.grandStaff) (activeTopY + vMetrics.staffHeight) else vMetrics.bottomStaffBottomY
        val baseCapR = max(5.2f, lineSpacing * 0.66f)
        val pinTipY = max(10f, activeTopY - lineSpacing * 0.64f)
        val capCenterY = max(baseCapR + 3f, pinTipY - baseCapR * 1.46f)
        val fullTopY = if (vMetrics.grandStaff) min(capCenterY - baseCapR, vMetrics.trebleTopY - lineSpacing * 0.6f) else (capCenterY - baseCapR)
        val fullBotY = if (vMetrics.grandStaff) (vMetrics.bottomStaffBottomY + lineSpacing * 0.6f) else (activeBotY + lineSpacing * 1.05f)
        return CursorScreenGeom(
            x = cx,
            handleCenterY = capCenterY,
            handleRadius = baseCapR,
            activeTopY = activeTopY,
            activeBottomY = activeBotY,
            fullTopY = fullTopY,
            fullBottomY = min(viewportHeight - 4f, fullBotY)
        )
    }

    fun drawInteractiveTimelineStaff(
        canvas: Canvas,
        viewportWidth: Float,
        viewportHeight: Float,
        scrollX: Float,
        clefHeaderWidth: Float,
        barWidth: Float,
        melody: MelodyStudioUiState,
        palette: OnScreenPalette = OnScreenPalette(),
        isDraggingCursor: Boolean = false
    ): List<StaffHitTarget> {
        val hitTargets = mutableListOf<StaffHitTarget>()
        if (viewportWidth <= clefHeaderWidth + 20f || viewportHeight <= 36f) return hitTargets

        val timeSig = melody.timeSignature
        val ticksPerBar = timeSig.ticksPerBar
        val quarterBeatsPerBar = timeSig.quarterBeatsPerBar.coerceAtLeast(0.25f)
        val maxNoteBeat = melody.notes.maxOfOrNull { it.endBeat } ?: quarterBeatsPerBar
        val maxChordBar = ((melody.chordSymbols.maxOfOrNull { it.safeTick } ?: 0) / ticksPerBar) + 1
        val maxKeyBar = (melody.keySignatureEvents.maxOfOrNull { it.safeBarIndex + 1 } ?: 1)
        val totalBars = maxOf(
            melody.totalBars,
            ceil(maxNoteBeat / quarterBeatsPerBar).toInt(),
            maxChordBar,
            maxKeyBar
        ).coerceAtLeast(1)

        val vMetrics = computeTimelineVerticalMetrics(viewportHeight, melody)
        val grandStaff = vMetrics.grandStaff
        val singleStaffBassClef = !grandStaff && melody.singleStaffUsesBassClef
        val lineSpacing = vMetrics.lineSpacing
        val staffHeight = vMetrics.staffHeight
        val trebleTopY = vMetrics.trebleTopY
        val bassTopY = vMetrics.bassTopY
        val bottomStaffBottomY = vMetrics.bottomStaffBottomY

        val symbols = buildBarSymbols(
            notes = melody.notes,
            totalBars = totalBars,
            timeSignature = timeSig,
            grandStaff = grandStaff,
            fillAutoRestsInActiveBars = true,
            fillAllBarsWithAutoRests = false
        )

        val visibleFirstBar = floor(scrollX / barWidth).toInt().coerceAtLeast(0)
        val visibleLastBar = (visibleFirstBar + ceil((viewportWidth - clefHeaderWidth) / barWidth).toInt() + 1)
            .coerceAtMost(totalBars - 1)
        // Expand anchor context by 2 bars on either side so cross-bar slurs/hairpins glide smoothly while scrolling
        val renderFirstBar = (visibleFirstBar - 2).coerceAtLeast(0)
        val renderLastBar = (visibleLastBar + 2).coerceAtMost(totalBars - 1)

        // Clip scrollable musical content to the right of the sticky Clef + Time Signature header
        canvas.save()
        canvas.clipRect(clefHeaderWidth, 0f, viewportWidth, viewportHeight)

        val subGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.subGridColor
            strokeWidth = max(1f, lineSpacing * 0.08f)
        }
        val barNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(195, 105, 96, 86)
            textSize = max(16f, lineSpacing * 1.08f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }

        val contentRightX = min(
            viewportWidth,
            clefHeaderWidth + totalBars * barWidth - scrollX + lineSpacing * 2f
        )

        if (grandStaff && !melody.isRecording) {
            val activeTopY = if (melody.activeStaff == StaffPart.BASS) bassTopY else trebleTopY
            val activeBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(18, 201, 100, 66)
                style = Paint.Style.FILL
            }
            canvas.drawRect(
                clefHeaderWidth,
                activeTopY - lineSpacing * 0.42f,
                contentRightX,
                activeTopY + staffHeight + lineSpacing * 0.42f,
                activeBandPaint
            )
        }

        drawFiveLines(canvas, clefHeaderWidth, contentRightX, trebleTopY, lineSpacing, palette.staffLineColor)
        if (grandStaff) {
            drawFiveLines(canvas, clefHeaderWidth, contentRightX, bassTopY, lineSpacing, palette.staffLineColor)
        }

        val barLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.barLineColor
            strokeWidth = max(1.3f, lineSpacing * 0.13f)
        }

        val measureGeoms = ArrayList<MeasureSpanGeom>(renderLastBar - renderFirstBar + 1)
        val chordAnchors = mutableListOf<RenderedChordAnchor>()
        val tupletObstacles = mutableListOf<RenderedTupletObstacle>()
        val pendingFermatas = mutableListOf<PendingFermataRender>()

        val beatCount = timeSig.safeNumerator

        for (bar in renderFirstBar..renderLastBar) {
            val barLeft = clefHeaderWidth + bar * barWidth - scrollX
            val barRight = barLeft + barWidth
            val (barNoteOriginX, barUsableW) = onScreenMeasureLayout(
                melody = melody,
                barIndex = bar,
                barLeft = barLeft,
                barWidth = barWidth,
                lineSpacing = lineSpacing
            )
            measureGeoms += MeasureSpanGeom(
                barIndex = bar,
                barLeftX = barLeft,
                barRightX = barRight,
                noteOriginX = barNoteOriginX,
                usableWidth = barUsableW
            )

            val barActiveKey = melody.keySignatureAtBar(bar)
            val midKeyEvent = if (bar >= 1) melody.keySignatureEventAtBar(bar) else null

            if (bar in visibleFirstBar..visibleLastBar) {
                if (midKeyEvent != null) {
                    val isModSelected = melody.selectedAnnotation == SelectedScoreAnnotation.KeyChange(midKeyEvent.id)
                    val modColor = if (isModSelected) palette.selectedColor else palette.inkColor
                    val modBarPaint = Paint(barLinePaint).apply {
                        if (isModSelected) color = palette.selectedColor
                    }
                    // Standard thin double barline (`细双小节线`) at mid-score key change
                    val thinPreX = barLeft - lineSpacing * 0.34f
                    canvas.drawLine(thinPreX, trebleTopY, thinPreX, bottomStaffBottomY, modBarPaint)
                    canvas.drawLine(barLeft, trebleTopY, barLeft, bottomStaffBottomY, modBarPaint)

                    val prevKey = melody.keySignatureAtBar(bar - 1)
                    val cancelTreble = prevKey.cancelledStaffStepsTo(midKeyEvent.keySignature, isBass = singleStaffBassClef)
                    val groupStartX = barLeft + lineSpacing * 0.42f
                    val groupEndX = drawKeySignatureGroup(
                        canvas = canvas,
                        leftX = groupStartX,
                        staffTopY = trebleTopY,
                        isBass = singleStaffBassClef,
                        s = lineSpacing,
                        cancelledSteps = cancelTreble,
                        newKey = midKeyEvent.keySignature,
                        color = modColor
                    )
                    if (grandStaff) {
                        val cancelBass = prevKey.cancelledStaffStepsTo(midKeyEvent.keySignature, isBass = true)
                        drawKeySignatureGroup(
                            canvas = canvas,
                            leftX = groupStartX,
                            staffTopY = bassTopY,
                            isBass = true,
                            s = lineSpacing,
                            cancelledSteps = cancelBass,
                            newKey = midKeyEvent.keySignature,
                            color = modColor
                        )
                    }

                    // Crisp modulation pill above double barline (interactive tap target)
                    val badgeText = midKeyEvent.keySignature.modulationBadgeText
                    val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (isModSelected) Color.WHITE else Color.rgb(68, 42, 128)
                        textSize = max(14f, lineSpacing * 0.98f)
                        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    }
                    val textW = badgeTextPaint.measureText(badgeText)
                    val pillH = max(18f, lineSpacing * 1.42f)
                    val pillW = textW + lineSpacing * 0.95f
                    val pillLeft = barLeft - lineSpacing * 0.2f
                    val pillTop = (trebleTopY - pillH - lineSpacing * 0.72f).coerceAtLeast(2f)
                    val pillRect = RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)

                    val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (isModSelected) palette.selectedColor else Color.argb(232, 238, 232, 252)
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(pillRect, pillH * 0.38f, pillH * 0.38f, pillBgPaint)
                    val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (isModSelected) palette.selectedColor else Color.argb(140, 124, 77, 204)
                        style = Paint.Style.STROKE
                        strokeWidth = max(1f, lineSpacing * 0.10f)
                    }
                    canvas.drawRoundRect(pillRect, pillH * 0.38f, pillH * 0.38f, pillStrokePaint)
                    val fm = badgeTextPaint.fontMetrics
                    val textBaseline = pillRect.centerY() - (fm.ascent + fm.descent) * 0.5f
                    canvas.drawText(badgeText, pillRect.left + lineSpacing * 0.46f, textBaseline, badgeTextPaint)

                    // Generous hit box covering both the modulation badge and the key signature symbols
                    val hitRight = max(pillRect.right, groupEndX) + lineSpacing * 0.35f
                    hitTargets += StaffHitTarget(
                        annotation = SelectedScoreAnnotation.KeyChange(midKeyEvent.id),
                        bounds = RectF(
                            min(thinPreX - lineSpacing * 0.35f, pillRect.left),
                            pillRect.top - lineSpacing * 0.2f,
                            hitRight,
                            bottomStaffBottomY + lineSpacing * 0.35f
                        ),
                        startTick = bar * ticksPerBar
                    )
                } else {
                    canvas.drawText(
                        "${bar + 1}",
                        barLeft + lineSpacing * 0.40f,
                        (trebleTopY - lineSpacing * 1.25f).coerceAtLeast(barNumPaint.textSize + 2f),
                        barNumPaint
                    )
                }

                for (b in 1 until beatCount) {
                    val bx = barNoteOriginX + (b.toFloat() / beatCount.toFloat()) * barUsableW
                    canvas.drawLine(bx, trebleTopY, bx, trebleTopY + staffHeight, subGridPaint)
                    if (grandStaff) {
                        canvas.drawLine(bx, bassTopY, bx, bassTopY + staffHeight, subGridPaint)
                    }
                }
            }

            val barSyms = symbols.filter { it.barIndex == bar }
            renderSingleMeasureSymbols(
                canvas = canvas,
                barSyms = barSyms,
                timeSignature = timeSig,
                activeKeySignature = barActiveKey,
                atNewSystemStart = false,
                noteOriginX = barNoteOriginX,
                usableMeasureWidth = barUsableW,
                trebleTopY = trebleTopY,
                bassTopY = bassTopY,
                lineSpacing = lineSpacing,
                grandStaff = grandStaff,
                singleStaffBassClef = singleStaffBassClef,
                selectedNoteId = melody.selectedNoteId,
                paperBgColor = palette.paperBgColor,
                slot0Color = palette.slot0Color,
                slot1Color = palette.slot1Color,
                selectedColor = palette.selectedColor,
                autoRestColor = palette.autoRestColor,
                outHitTargets = if (bar in visibleFirstBar..visibleLastBar) hitTargets else null,
                outChordAnchors = chordAnchors,
                outTupletObstacles = tupletObstacles,
                outPendingFermatas = pendingFermatas
            )

            if (bar in visibleFirstBar..visibleLastBar) {
                val nextHasKeyChange = (bar + 1 < totalBars) && (melody.keySignatureEventAtBar(bar + 1) != null)
                if (bar == totalBars - 1) {
                    val thinX = barRight - lineSpacing * 0.36f
                    canvas.drawLine(thinX, trebleTopY, thinX, bottomStaffBottomY, barLinePaint)
                    val thickPaint = Paint(barLinePaint).apply {
                        strokeWidth = max(2.6f, lineSpacing * 0.32f)
                    }
                    canvas.drawLine(barRight, trebleTopY, barRight, bottomStaffBottomY, thickPaint)
                } else if (!nextHasKeyChange) {
                    canvas.drawLine(barRight, trebleTopY, barRight, bottomStaffBottomY, barLinePaint)
                }
            }
        }

        // System-level Multi-Note Phrase Slurs + Fermatas + Chord Symbols + Dynamics/Hairpins + Tempo Marks
        renderSystemPhraseSlurs(
            canvas = canvas,
            allScoreNotes = melody.notes,
            grandStaff = grandStaff,
            chordAnchors = chordAnchors,
            tupletObstacles = tupletObstacles,
            pendingFermatas = pendingFermatas,
            measureGeoms = measureGeoms,
            isContinuousTimeline = true,
            ticksPerBar = ticksPerBar,
            trebleTopY = trebleTopY,
            bassTopY = bassTopY,
            lineSpacing = lineSpacing,
            defaultColor = palette.expressionInkColor,
            selectedColor = palette.selectedColor,
            selectedNoteId = melody.selectedNoteId
        )

        renderPendingFermatas(
            canvas = canvas,
            pendingFermatas = pendingFermatas,
            lineSpacing = lineSpacing
        )

        val tickXMapper: (Int) -> Float = { tick ->
            val clampedTick = tick.coerceAtLeast(0)
            val b = clampedTick / ticksPerBar
            val rem = clampedTick % ticksPerBar
            val bLeft = clefHeaderWidth + b * barWidth - scrollX
            val (bNoteOriginX, bUsableW) = onScreenMeasureLayout(
                melody = melody,
                barIndex = b,
                barLeft = bLeft,
                barWidth = barWidth,
                lineSpacing = lineSpacing
            )
            bNoteOriginX + (rem.toFloat() / ticksPerBar.toFloat()) * bUsableW
        }

        renderSystemDynamicsAndHairpins(
            canvas = canvas,
            dynamicMarks = melody.dynamicMarks,
            hairpins = melody.hairpins,
            sysStartTick = renderFirstBar * ticksPerBar,
            sysEndTick = (renderLastBar + 1) * ticksPerBar,
            isContinuousTimeline = true,
            sysLeftContinuationX = clefHeaderWidth + lineSpacing * 0.4f,
            sysRightContinuationX = clefHeaderWidth + (renderLastBar + 1) * barWidth - scrollX - lineSpacing * 0.3f,
            tickToX = tickXMapper,
            chordAnchors = chordAnchors,
            bottomStaffBottomY = bottomStaffBottomY,
            viewportMaxBottomY = viewportHeight - lineSpacing * 0.45f,
            lineSpacing = lineSpacing,
            inkColor = palette.expressionInkColor,
            selectedColor = palette.selectedColor,
            selectedAnnotation = melody.selectedAnnotation,
            outHitTargets = hitTargets
        )

        val hasMidModInRenderSpan = (renderFirstBar..renderLastBar).any { b ->
            b >= 1 && melody.keySignatureEventAtBar(b) != null
        }
        val chordLaneTopY = if (melody.showChordSymbols && melody.chordSymbols.isNotEmpty()) {
            renderSystemChordSymbols(
                canvas = canvas,
                chordSymbols = melody.chordSymbols,
                sysStartTick = renderFirstBar * ticksPerBar,
                sysEndTick = (renderLastBar + 1) * ticksPerBar,
                isContinuousTimeline = true,
                tickToX = tickXMapper,
                trebleTopY = trebleTopY,
                hasModulationPills = hasMidModInRenderSpan,
                chordAnchors = chordAnchors,
                pendingFermatas = pendingFermatas,
                viewportMinTopY = lineSpacing * 1.45f,
                lineSpacing = lineSpacing,
                inkColor = palette.inkColor,
                selectedColor = palette.selectedColor,
                selectedAnnotation = melody.selectedAnnotation,
                outHitTargets = hitTargets
            )
        } else {
            null
        }

        renderSystemTempoEvents(
            canvas = canvas,
            tempoEvents = melody.tempoEvents,
            sysStartTick = renderFirstBar * ticksPerBar,
            sysEndTick = (renderLastBar + 1) * ticksPerBar,
            isContinuousTimeline = true,
            sysLeftContinuationX = clefHeaderWidth + lineSpacing * 0.4f,
            sysRightContinuationX = clefHeaderWidth + (renderLastBar + 1) * barWidth - scrollX - lineSpacing * 0.3f,
            tickToX = tickXMapper,
            trebleTopY = trebleTopY,
            chordAnchors = chordAnchors,
            pendingFermatas = pendingFermatas,
            extraTopClearanceY = chordLaneTopY,
            viewportMinTopY = lineSpacing * 1.25f,
            lineSpacing = lineSpacing,
            inkColor = palette.expressionInkColor,
            selectedColor = palette.selectedColor,
            selectedAnnotation = melody.selectedAnnotation,
            outHitTargets = hitTargets
        )

        // Draw Step Input Cursor at exact `cursorTick` (emphasizing the currently active staff in Grand Staff mode!)
        var timelineCursorGeom: CursorScreenGeom? = null
        val timelineCursorColor = resolveCursorColor(melody, palette)
        if (!melody.isRecording) {
            val cursorBar = melody.cursorTick.coerceAtLeast(0) / ticksPerBar
            val tickInBar = melody.cursorTick.coerceAtLeast(0) % ticksPerBar
            val barLeft = clefHeaderWidth + cursorBar * barWidth - scrollX
            val (cNoteOriginX, cUsableW) = onScreenMeasureLayout(
                melody = melody,
                barIndex = cursorBar,
                barLeft = barLeft,
                barWidth = barWidth,
                lineSpacing = lineSpacing
            )
            val cx = cNoteOriginX + (tickInBar / ticksPerBar.toFloat()) * cUsableW
            if (cx in clefHeaderWidth..viewportWidth) {
                timelineCursorGeom = drawStepCursorWithHandle(
                    canvas = canvas,
                    cx = cx,
                    trebleTopY = trebleTopY,
                    bassTopY = bassTopY,
                    bottomStaffBottomY = bottomStaffBottomY,
                    staffHeight = staffHeight,
                    lineSpacing = lineSpacing,
                    grandStaff = grandStaff,
                    activeStaff = melody.activeStaff,
                    cursorColor = timelineCursorColor,
                    paperBgColor = palette.paperBgColor,
                    isDraggingCursor = isDraggingCursor,
                    minClampTopY = 2f,
                    maxClampBottomY = viewportHeight - 4f
                )
            }
        }

        // Draw Live Transport Playhead (tracks exact variable-tempo musical beat!)
        if (melody.isPlaying || melody.isRecording) {
            val totalTickFloat = melody.playheadBeat * TICKS_PER_BEAT.toFloat()
            val pBar = floor(totalTickFloat / ticksPerBar.toFloat()).toInt().coerceAtLeast(0)
            val pRemTick = totalTickFloat - pBar * ticksPerBar
            val barLeft = clefHeaderWidth + pBar * barWidth - scrollX
            val (pNoteOriginX, pUsableW) = onScreenMeasureLayout(
                melody = melody,
                barIndex = pBar,
                barLeft = barLeft,
                barWidth = barWidth,
                lineSpacing = lineSpacing
            )
            val px = pNoteOriginX + (pRemTick / ticksPerBar.toFloat()) * pUsableW
            if (px in clefHeaderWidth..viewportWidth) {
                val phPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.playheadColor
                    strokeWidth = max(2.2f, lineSpacing * 0.20f)
                }
                canvas.drawLine(
                    px,
                    (trebleTopY - lineSpacing * 1.2f).coerceAtLeast(2f),
                    px,
                    min(viewportHeight - 2f, bottomStaffBottomY + lineSpacing * 1.2f),
                    phPaint
                )
            }
        }

        // Empty Score Watermark
        if (melody.notes.isEmpty() &&
            melody.chordSymbols.isEmpty() &&
            melody.dynamicMarks.isEmpty() &&
            melody.hairpins.isEmpty() &&
            melody.tempoEvents.isEmpty() &&
            !melody.isRecording
        ) {
            val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(145, 112, 102, 90)
                textSize = max(18f, lineSpacing * 1.18f)
                textAlign = Paint.Align.CENTER
            }
            val hintMsg = if (grandStaff) {
                "触屏点上方高音谱写右手、点下方低音谱写左手 · 支持和弦标记/转调/连线，或点「示例曲」体验"
            } else {
                "点击下方琴键写五线谱音符（支持双行钢琴谱、和弦标记、调号/转调、连线），或点「示例曲」体验"
            }
            canvas.drawText(
                hintMsg,
                clefHeaderWidth + (viewportWidth - clefHeaderWidth) * 0.5f,
                viewportHeight - max(10f, lineSpacing * 0.70f),
                hintPaint
            )
        }

        canvas.restore()

        // Draw Sticky Left Clef + Active Key Signature + Configurable Time Signature Header
        val headerBgPaint = Paint().apply {
            color = palette.paperBgColor
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, clefHeaderWidth, viewportHeight, headerBgPaint)

        val leftMargin = if (grandStaff) lineSpacing * 1.12f else lineSpacing * 0.65f
        drawFiveLines(canvas, leftMargin, clefHeaderWidth, trebleTopY, lineSpacing, palette.staffLineColor)
        if (grandStaff) {
            drawFiveLines(canvas, leftMargin, clefHeaderWidth, bassTopY, lineSpacing, palette.staffLineColor)
            val bracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.inkColor
                strokeWidth = max(1.8f, lineSpacing * 0.20f)
            }
            canvas.drawLine(leftMargin, trebleTopY, leftMargin, bottomStaffBottomY, bracePaint)
            drawPianoGrandBrace(
                canvas = canvas,
                barLineX = leftMargin,
                topY = trebleTopY,
                bottomY = bottomStaffBottomY,
                lineSpacing = lineSpacing,
                color = palette.inkColor
            )
        } else {
            canvas.drawLine(leftMargin, trebleTopY, leftMargin, trebleTopY + staffHeight, barLinePaint)
        }

        val trebleClefColor = if (grandStaff && !melody.isRecording && melody.activeStaff == StaffPart.TREBLE) {
            palette.cursorColor
        } else {
            palette.inkColor
        }
        val bassClefColor = if (grandStaff && !melody.isRecording && melody.activeStaff == StaffPart.BASS) {
            palette.cursorColor
        } else {
            palette.inkColor
        }
        if (singleStaffBassClef) {
            drawBassClef(canvas, leftMargin + lineSpacing * 1.28f, trebleTopY, lineSpacing, trebleClefColor)
        } else {
            drawTrebleClef(canvas, leftMargin + lineSpacing * 1.32f, trebleTopY, lineSpacing, trebleClefColor)
        }
        if (grandStaff) {
            drawBassClef(canvas, leftMargin + lineSpacing * 1.28f, bassTopY, lineSpacing, bassClefColor)
        }

        val headerKey = melody.keySignatureAtBar(visibleFirstBar)
        val keyRightX = if (headerKey.safeFifths != 0) {
            val kStart = leftMargin + lineSpacing * 2.50f
            val endTreble = drawKeySignatureGroup(
                canvas = canvas,
                leftX = kStart,
                staffTopY = trebleTopY,
                isBass = singleStaffBassClef,
                s = lineSpacing,
                cancelledSteps = emptyList(),
                newKey = headerKey,
                color = palette.inkColor
            )
            if (grandStaff) {
                drawKeySignatureGroup(
                    canvas = canvas,
                    leftX = kStart,
                    staffTopY = bassTopY,
                    isBass = true,
                    s = lineSpacing,
                    cancelledSteps = emptyList(),
                    newKey = headerKey,
                    color = palette.inkColor
                )
            }
            endTreble
        } else {
            leftMargin + lineSpacing * 2.52f
        }

        val timeSigX = max(leftMargin + lineSpacing * 3.50f, keyRightX + lineSpacing * 0.82f)
        drawTimeSignature(
            canvas = canvas,
            centerX = timeSigX,
            staffTopY = trebleTopY,
            s = lineSpacing,
            numerator = timeSig.safeNumerator,
            denominator = timeSig.safeDenominator,
            color = palette.inkColor
        )
        if (grandStaff) {
            drawTimeSignature(
                canvas = canvas,
                centerX = timeSigX,
                staffTopY = bassTopY,
                s = lineSpacing,
                numerator = timeSig.safeNumerator,
                denominator = timeSig.safeDenominator,
                color = palette.inkColor
            )
        }

        val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 160, 150, 138)
            strokeWidth = max(1f, lineSpacing * 0.1f)
        }
        canvas.drawLine(clefHeaderWidth, trebleTopY, clefHeaderWidth, bottomStaffBottomY, sepPaint)

        // Floating live beat bubble above cursor handle while dragging (drawn after sticky header so it never clips!)
        if (isDraggingCursor && timelineCursorGeom != null) {
            drawCursorScrubBubble(
                canvas = canvas,
                geom = timelineCursorGeom,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                minLeftX = clefHeaderWidth + 4f,
                lineSpacing = lineSpacing,
                cursorColor = timelineCursorColor,
                melody = melody,
                grandStaff = grandStaff
            )
        }

        return hitTargets
    }

    /**
     * Maps an X/Y tap or scrub coordinate on the interactive single-strip staff back to both a snapped `cursorTick`
     * AND the tapped [StaffPart] (`TREBLE` upper staff vs `BASS` lower staff when Grand Staff is enabled).
     *
     * When [scrubFineMode] is `false` (blank-space tap), applies rounded smart beat-grid snapping based on
     * active note duration / tuplet; when `true` (cursor dragging), snaps smoothly to 1/16th notes & existing note onsets.
     */
    fun mapTouchToTickAndStaff(
        touchX: Float,
        touchY: Float,
        viewportHeight: Float,
        scrollX: Float,
        clefHeaderWidth: Float,
        barWidth: Float,
        lineSpacingEstimate: Float,
        totalBars: Int,
        melody: MelodyStudioUiState,
        scrubFineMode: Boolean = false
    ): Pair<Int, StaffPart> {
        val vMetrics = computeTimelineVerticalMetrics(viewportHeight, melody)
        val staff = if (!vMetrics.grandStaff) {
            StaffPart.TREBLE
        } else {
            val splitY = (vMetrics.trebleTopY + vMetrics.staffHeight + vMetrics.bassTopY) * 0.5f
            if (touchY >= splitY) StaffPart.BASS else StaffPart.TREBLE
        }
        val tick = mapTouchXToTick(
            touchX = touchX,
            scrollX = scrollX,
            clefHeaderWidth = clefHeaderWidth,
            barWidth = barWidth,
            lineSpacingEstimate = lineSpacingEstimate,
            totalBars = totalBars,
            melody = melody,
            scrubFineMode = scrubFineMode,
            targetStaff = staff
        )
        return tick to staff
    }

    fun mapTouchToTick(
        touchX: Float,
        scrollX: Float,
        clefHeaderWidth: Float,
        barWidth: Float,
        lineSpacingEstimate: Float,
        totalBars: Int,
        melody: MelodyStudioUiState,
        scrubFineMode: Boolean = false
    ): Int = mapTouchXToTick(
        touchX = touchX,
        scrollX = scrollX,
        clefHeaderWidth = clefHeaderWidth,
        barWidth = barWidth,
        lineSpacingEstimate = lineSpacingEstimate,
        totalBars = totalBars,
        melody = melody,
        scrubFineMode = scrubFineMode
    )

    /**
     * Maps an X coordinate on the interactive single-strip staff back to a snapped `cursorTick`
     * under [melody]'s time signature & mid-score key changes.
     */
    fun mapTouchXToTick(
        touchX: Float,
        scrollX: Float,
        clefHeaderWidth: Float,
        barWidth: Float,
        lineSpacingEstimate: Float,
        totalBars: Int,
        melody: MelodyStudioUiState,
        scrubFineMode: Boolean = false,
        targetStaff: StaffPart = melody.activeStaff
    ): Int {
        val timeSignature = melody.timeSignature
        val ticksPerBar = timeSignature.ticksPerBar.coerceAtLeast(TICKS_PER_STEP)
        val safeBars = totalBars.coerceAtLeast(1)
        val worldX = (touchX - clefHeaderWidth + scrollX).coerceAtLeast(0f)
        val barIdx = floor(worldX / barWidth.coerceAtLeast(1f)).toInt().coerceIn(0, safeBars - 1)
        val inBarX = worldX - barIdx * barWidth
        val (noteOriginOffset, usableW) = onScreenMeasureLayout(
            melody = melody,
            barIndex = barIdx,
            barLeft = 0f,
            barWidth = barWidth,
            lineSpacing = lineSpacingEstimate
        )
        val frac = ((inBarX - noteOriginOffset) / usableW.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val inBarTick = if (scrubFineMode) {
            snapInBarTickForScrub(frac, barIdx, ticksPerBar, targetStaff, melody)
        } else {
            snapInBarTickForBlankTap(frac, ticksPerBar, melody)
        }
        val rawTick = barIdx * ticksPerBar + inBarTick
        val minStepUnit = melody.activeTuplet?.noteTicks ?: TICKS_PER_STEP
        val maxTick = (safeBars * ticksPerBar - minStepUnit).coerceAtLeast(0)
        return rawTick.coerceIn(0, maxTick)
    }

    // ========================================================================
    // 1B. Multi-System Vertically-Scrollable Full-Page Sheet Music (`整页谱`)
    //     Displays multiple rows of measures at once so pitch/rhythm mistakes
    //     are immediately visible by eye, and any measure/note on any row can
    //     be tapped to seek playback or edit directly!
    // ========================================================================

    enum class PageHeaderHitKind {
        KEY_SIGNATURE,
        TIME_SIGNATURE
    }

    data class PageStaffLayout(
        val viewportWidth: Float,
        val marginLeftX: Float,
        val marginRightX: Float,
        val contentWidth: Float,
        val lineSpacing: Float,
        val grandStaff: Boolean,
        val totalBars: Int,
        val barsPerSystem: Int,
        val totalSystems: Int,
        val staffHeight: Float,
        val grandGap: Float,
        val systemBlockHeight: Float,
        val topPadding: Float,
        val systemRowStride: Float,
        val bottomPadding: Float,
        val totalContentHeight: Float
    ) {
        fun systemTrebleTopWorldY(sysIdx: Int): Float =
            topPadding + sysIdx * systemRowStride

        fun systemForBar(barIndex: Int): Int =
            (barIndex.coerceAtLeast(0) / barsPerSystem.coerceAtLeast(1))
                .coerceIn(0, (totalSystems - 1).coerceAtLeast(0))
    }

    private data class PageSystemHorizontalGeom(
        val sysIdx: Int,
        val startBar: Int,
        val barsInSys: Int,
        val endBar: Int,
        val isFirstSystem: Boolean,
        val sysStartKey: KeySignature,
        val clefZoneW: Float,
        val nextSysKeyEv: ScoreKeySignatureEvent?,
        val courtesyCancelTreble: List<Int>,
        val courtesyZoneW: Float,
        val singleBarW: Float,
        val systemActualW: Float
    )

    fun computePageStaffLayout(
        viewportWidth: Float,
        densityScale: Float,
        melody: MelodyStudioUiState
    ): PageStaffLayout {
        val safeDensity = densityScale.coerceAtLeast(1f)
        val safeWidth = viewportWidth.coerceAtLeast(160f)
        val viewportWidthDp = safeWidth / safeDensity
        val timeSig = melody.timeSignature
        val ticksPerBar = timeSig.ticksPerBar
        val quarterBeatsPerBar = timeSig.quarterBeatsPerBar.coerceAtLeast(0.25f)

        val maxNoteBeat = melody.notes.maxOfOrNull { it.endBeat } ?: quarterBeatsPerBar
        val maxMarkTick = maxOf(
            melody.dynamicMarks.maxOfOrNull { it.tick } ?: 0,
            melody.hairpins.maxOfOrNull { it.safeEndTick } ?: 0,
            melody.tempoEvents.maxOfOrNull { it.safeEndTick } ?: 0,
            melody.chordSymbols.maxOfOrNull { it.safeTick + TICKS_PER_STEP } ?: 0
        )
        val maxKeyBar = (melody.keySignatureEvents.maxOfOrNull { it.safeBarIndex + 1 } ?: 1)
        val totalBars = maxOf(
            melody.totalBars,
            ceil(maxNoteBeat / quarterBeatsPerBar).toInt(),
            ceil(maxMarkTick / ticksPerBar.toFloat()).toInt(),
            maxKeyBar
        ).coerceAtLeast(1)

        val wideMeter = ticksPerBar > 1200
        val barsPerSystem = when {
            viewportWidthDp >= 820f -> if (wideMeter) 3 else 4
            viewportWidthDp >= 540f -> if (wideMeter) 2 else 3
            else -> 2
        }
        val totalSystems = ceil(totalBars / barsPerSystem.toFloat()).toInt().coerceAtLeast(1)
        val grandStaff = isEffectiveGrandStaff(melody)

        val lineSpacingDp = when {
            viewportWidthDp >= 820f -> if (grandStaff) 7.4f else 8.4f
            viewportWidthDp >= 600f -> if (grandStaff) 7.0f else 8.0f
            else -> if (grandStaff) 6.4f else 7.2f
        }
        val lineSpacing = lineSpacingDp * safeDensity
        val staffHeight = lineSpacing * 4f
        val grandGap = lineSpacing * 3.4f
        val systemBlockHeight = if (grandStaff) (staffHeight * 2f + grandGap) else staffHeight

        val hasChordMarks = melody.showChordSymbols && melody.chordSymbols.isNotEmpty()
        val hasTopMarks = melody.tempoEvents.isNotEmpty() ||
            melody.keySignatureEvents.isNotEmpty() ||
            melody.notes.any { it.hasFermata || it.accent == NoteAccent.MARCATO }
        val hasBottomMarks = melody.dynamicMarks.isNotEmpty() || melody.hairpins.isNotEmpty()
        val topExtra = (if (hasTopMarks) 1.0f else 0f) + (if (hasChordMarks) 1.45f else 0f)

        val topPadding = lineSpacing * (3.15f + topExtra)
        val interSystemGap = lineSpacing * (
            4.8f +
                topExtra +
                (if (hasBottomMarks) 1.0f else 0f)
            )
        val systemRowStride = systemBlockHeight + interSystemGap
        val bottomPadding = lineSpacing * (if (hasBottomMarks) 4.0f else 3.0f)
        val totalContentHeight = topPadding +
            (totalSystems - 1).coerceAtLeast(0) * systemRowStride +
            systemBlockHeight +
            bottomPadding

        val marginLeftX = (if (viewportWidthDp >= 600f) 18f else 13f) * safeDensity
        val marginRightX = (if (viewportWidthDp >= 600f) 20f else 14f) * safeDensity
        val contentWidth = (safeWidth - marginLeftX - marginRightX).coerceAtLeast(lineSpacing * 18f)

        return PageStaffLayout(
            viewportWidth = safeWidth,
            marginLeftX = marginLeftX,
            marginRightX = marginRightX,
            contentWidth = contentWidth,
            lineSpacing = lineSpacing,
            grandStaff = grandStaff,
            totalBars = totalBars,
            barsPerSystem = barsPerSystem,
            totalSystems = totalSystems,
            staffHeight = staffHeight,
            grandGap = grandGap,
            systemBlockHeight = systemBlockHeight,
            topPadding = topPadding,
            systemRowStride = systemRowStride,
            bottomPadding = bottomPadding,
            totalContentHeight = totalContentHeight
        )
    }

    private fun computePageSystemHorizontalGeom(
        sysIdx: Int,
        layout: PageStaffLayout,
        melody: MelodyStudioUiState
    ): PageSystemHorizontalGeom {
        val s = layout.lineSpacing
        val startBar = sysIdx * layout.barsPerSystem
        val barsInSys = min(layout.barsPerSystem, layout.totalBars - startBar).coerceAtLeast(1)
        val endBar = startBar + barsInSys - 1
        val isFirstSystem = (sysIdx == 0)

        val sysStartKey = melody.keySignatureAtBar(startBar)
        val sysStartKeyW = keySignatureGroupWidth(0, abs(sysStartKey.safeFifths), s)
        val clefZoneW = (if (isFirstSystem) s * 5.25f else s * 3.15f) + sysStartKeyW

        val nextSysKeyEv = if (endBar + 1 < layout.totalBars) {
            melody.keySignatureEventAtBar(endBar + 1)
        } else {
            null
        }
        val endBarKey = melody.keySignatureAtBar(endBar)
        val singleStaffBassClef = !layout.grandStaff && melody.singleStaffUsesBassClef
        val courtesyCancelTreble = if (nextSysKeyEv != null) {
            endBarKey.cancelledStaffStepsTo(nextSysKeyEv.keySignature, isBass = singleStaffBassClef)
        } else {
            emptyList()
        }
        val courtesyGroupW = if (nextSysKeyEv != null) {
            keySignatureGroupWidth(
                cancelledCount = courtesyCancelTreble.size,
                newSigCount = abs(nextSysKeyEv.keySignature.safeFifths),
                s = s
            )
        } else {
            0f
        }
        val courtesyZoneW = if (nextSysKeyEv != null) {
            s * 0.55f + max(courtesyGroupW, s * 0.6f)
        } else {
            0f
        }

        val availBarsW = (layout.contentWidth - clefZoneW - courtesyZoneW).coerceAtLeast(s * 14f)
        val singleBarW = availBarsW / layout.barsPerSystem.toFloat()
        val systemActualW = clefZoneW + singleBarW * barsInSys + courtesyZoneW

        return PageSystemHorizontalGeom(
            sysIdx = sysIdx,
            startBar = startBar,
            barsInSys = barsInSys,
            endBar = endBar,
            isFirstSystem = isFirstSystem,
            sysStartKey = sysStartKey,
            clefZoneW = clefZoneW,
            nextSysKeyEv = nextSysKeyEv,
            courtesyCancelTreble = courtesyCancelTreble,
            courtesyZoneW = courtesyZoneW,
            singleBarW = singleBarW,
            systemActualW = systemActualW
        )
    }

    private fun pageMeasureNoteLayout(
        melody: MelodyStudioUiState,
        barIndex: Int,
        bOffsetInSystem: Int,
        barLeftX: Float,
        singleBarW: Float,
        lineSpacing: Float
    ): Pair<Float, Float> {
        val basePadL = lineSpacing * 1.25f
        val padR = lineSpacing * 0.80f
        val midSysKeyEv = if (bOffsetInSystem > 0 && barIndex >= 1) {
            melody.keySignatureEventAtBar(barIndex)
        } else {
            null
        }
        val midKeyW = if (midSysKeyEv != null) midBarKeyChangeWidth(melody, barIndex, lineSpacing) else 0f
        val effPadL = if (midSysKeyEv != null) {
            max(basePadL, lineSpacing * 0.45f + midKeyW + lineSpacing * 0.65f)
        } else {
            basePadL
        }
        val usableW = (singleBarW - effPadL - padR).coerceAtLeast(lineSpacing * 2.4f)
        return (barLeftX + effPadL) to usableW
    }

    fun computePageCursorScreenGeom(
        viewportHeight: Float,
        scrollY: Float,
        layout: PageStaffLayout,
        melody: MelodyStudioUiState
    ): CursorScreenGeom? {
        if (melody.isRecording || viewportHeight <= 36f) return null
        val ticksPerBar = melody.timeSignature.ticksPerBar.coerceAtLeast(1)
        val cursorBarIdx = (melody.cursorTick.coerceAtLeast(0) / ticksPerBar)
            .coerceIn(0, (layout.totalBars - 1).coerceAtLeast(0))
        val sysIdx = layout.systemForBar(cursorBarIdx)
        val hGeom = computePageSystemHorizontalGeom(sysIdx, layout, melody)
        if (cursorBarIdx !in hGeom.startBar..hGeom.endBar) return null

        val lineSpacing = layout.lineSpacing
        val staffH = layout.staffHeight
        val grandStaff = layout.grandStaff
        val trebleTopY = layout.systemTrebleTopWorldY(sysIdx) - scrollY
        val bassTopY = trebleTopY + staffH + layout.grandGap
        val sysBottomY = if (grandStaff) bassTopY + staffH else trebleTopY + staffH
        if (sysBottomY < -lineSpacing * 3f || trebleTopY > viewportHeight + lineSpacing * 3f) return null

        val bOff = (cursorBarIdx - hGeom.startBar).coerceIn(0, (hGeom.barsInSys - 1).coerceAtLeast(0))
        val bLeft = layout.marginLeftX + hGeom.clefZoneW + bOff * hGeom.singleBarW
        val (noteOriginX, usableW) = pageMeasureNoteLayout(
            melody = melody,
            barIndex = cursorBarIdx,
            bOffsetInSystem = bOff,
            barLeftX = bLeft,
            singleBarW = hGeom.singleBarW,
            lineSpacing = lineSpacing
        )
        val tickInBar = melody.cursorTick.coerceAtLeast(0) % ticksPerBar
        val cx = noteOriginX + (tickInBar.toFloat() / ticksPerBar.toFloat()) * usableW

        val activeTopY = if (grandStaff && melody.activeStaff == StaffPart.BASS) bassTopY else trebleTopY
        val activeBotY = if (grandStaff) (activeTopY + staffH) else sysBottomY
        val baseCapR = max(5.2f, lineSpacing * 0.66f)
        val pinTipY = activeTopY - lineSpacing * 0.64f
        val capCenterY = pinTipY - baseCapR * 1.46f
        val fullTopY = if (grandStaff) min(capCenterY - baseCapR, trebleTopY - lineSpacing * 0.6f) else (capCenterY - baseCapR)
        val fullBotY = if (grandStaff) (sysBottomY + lineSpacing * 0.6f) else (activeBotY + lineSpacing * 1.05f)
        return CursorScreenGeom(
            x = cx,
            handleCenterY = capCenterY,
            handleRadius = baseCapR,
            activeTopY = activeTopY,
            activeBottomY = activeBotY,
            fullTopY = fullTopY,
            fullBottomY = fullBotY
        )
    }

    fun drawInteractivePageStaff(
        canvas: Canvas,
        viewportWidth: Float,
        viewportHeight: Float,
        scrollY: Float,
        layout: PageStaffLayout,
        melody: MelodyStudioUiState,
        palette: OnScreenPalette = OnScreenPalette(),
        isDraggingCursor: Boolean = false
    ): List<StaffHitTarget> {
        val hitTargets = mutableListOf<StaffHitTarget>()
        if (viewportWidth <= 60f || viewportHeight <= 36f) return hitTargets

        val timeSig = melody.timeSignature
        val ticksPerBar = timeSig.ticksPerBar
        val beatCount = timeSig.safeNumerator
        val lineSpacing = layout.lineSpacing
        val staffH = layout.staffHeight
        val grandStaff = layout.grandStaff
        val singleStaffBassClef = !grandStaff && melody.singleStaffUsesBassClef
        val marginX = layout.marginLeftX

        val symbols = buildBarSymbols(
            notes = melody.notes,
            totalBars = layout.totalBars,
            timeSignature = timeSig,
            grandStaff = grandStaff,
            fillAutoRestsInActiveBars = true,
            fillAllBarsWithAutoRests = false
        )

        val subGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.subGridColor
            strokeWidth = max(1f, lineSpacing * 0.08f)
        }
        val barNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(205, 102, 92, 82)
            textSize = max(15f, lineSpacing * 1.05f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        val barLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.barLineColor
            strokeWidth = max(1.25f, lineSpacing * 0.13f)
        }
        val leftBracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.inkColor
            strokeWidth = if (grandStaff) max(2.0f, lineSpacing * 0.22f) else max(1.3f, lineSpacing * 0.14f)
        }
        val activeCursorBarTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(15, 201, 100, 66)
            style = Paint.Style.FILL
        }
        val activePlayBarTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(18, 190, 58, 48)
            style = Paint.Style.FILL
        }
        val rowDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, 150, 140, 126)
            strokeWidth = max(1f, lineSpacing * 0.08f)
        }

        val cursorBarIdx = (melody.cursorTick.coerceAtLeast(0) / ticksPerBar)
        val playTotalTickFloat = melody.playheadBeat * TICKS_PER_BEAT.toFloat()
        val playBarIdx = floor(playTotalTickFloat / ticksPerBar.toFloat()).toInt().coerceAtLeast(0)
        var pageCursorGeom: CursorScreenGeom? = null
        val pageCursorColor = resolveCursorColor(melody, palette)

        val cullPad = lineSpacing * 6.0f
        val firstSys = floor(((scrollY - cullPad - layout.topPadding) / layout.systemRowStride))
            .toInt()
            .coerceIn(0, layout.totalSystems - 1)
        val lastSys = ceil(((scrollY + viewportHeight + cullPad - layout.topPadding) / layout.systemRowStride))
            .toInt()
            .coerceIn(firstSys, layout.totalSystems - 1)

        for (sysIdx in firstSys..lastSys) {
            val hGeom = computePageSystemHorizontalGeom(sysIdx, layout, melody)
            val trebleTopY = layout.systemTrebleTopWorldY(sysIdx) - scrollY
            val bassTopY = trebleTopY + staffH + layout.grandGap
            val sysBottomY = if (grandStaff) bassTopY + staffH else trebleTopY + staffH

            // Subtle inter-system paper guide rule
            if (sysIdx > 0) {
                val sepY = trebleTopY - (layout.systemRowStride - layout.systemBlockHeight) * 0.48f
                if (sepY in 0f..viewportHeight) {
                    canvas.drawLine(
                        marginX,
                        sepY,
                        marginX + layout.contentWidth,
                        sepY,
                        rowDividerPaint
                    )
                }
            }

            // Highlight active cursor measure / live playing measure so the eye spots it immediately
            for (bOffset in 0 until hGeom.barsInSys) {
                val bar = hGeom.startBar + bOffset
                val bLeft = marginX + hGeom.clefZoneW + bOffset * hGeom.singleBarW
                val bRight = bLeft + hGeom.singleBarW
                if ((melody.isPlaying || melody.isRecording) && bar == playBarIdx) {
                    canvas.drawRoundRect(
                        RectF(bLeft + 1f, trebleTopY - lineSpacing * 0.7f, bRight - 1f, sysBottomY + lineSpacing * 0.7f),
                        lineSpacing * 0.45f,
                        lineSpacing * 0.45f,
                        activePlayBarTintPaint
                    )
                } else if (!melody.isRecording && bar == cursorBarIdx) {
                    if (grandStaff) {
                        val ghostBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.argb(9, 201, 100, 66)
                            style = Paint.Style.FILL
                        }
                        canvas.drawRoundRect(
                            RectF(bLeft + 1f, trebleTopY - lineSpacing * 0.55f, bRight - 1f, sysBottomY + lineSpacing * 0.55f),
                            lineSpacing * 0.45f,
                            lineSpacing * 0.45f,
                            ghostBarPaint
                        )
                        val actTopY = if (melody.activeStaff == StaffPart.BASS) bassTopY else trebleTopY
                        val actStaffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.argb(28, 201, 100, 66)
                            style = Paint.Style.FILL
                        }
                        canvas.drawRoundRect(
                            RectF(bLeft + 1f, actTopY - lineSpacing * 0.55f, bRight - 1f, actTopY + staffH + lineSpacing * 0.55f),
                            lineSpacing * 0.42f,
                            lineSpacing * 0.42f,
                            actStaffPaint
                        )
                    } else {
                        canvas.drawRoundRect(
                            RectF(bLeft + 1f, trebleTopY - lineSpacing * 0.6f, bRight - 1f, sysBottomY + lineSpacing * 0.6f),
                            lineSpacing * 0.45f,
                            lineSpacing * 0.45f,
                            activeCursorBarTintPaint
                        )
                    }
                }
            }

            // 5-line staves & system opening brace / clef / key signature / row-1 time signature
            drawFiveLines(canvas, marginX, marginX + hGeom.systemActualW, trebleTopY, lineSpacing, palette.staffLineColor)
            if (grandStaff) {
                drawFiveLines(canvas, marginX, marginX + hGeom.systemActualW, bassTopY, lineSpacing, palette.staffLineColor)
            }
            canvas.drawLine(marginX, trebleTopY, marginX, sysBottomY, leftBracePaint)
            if (grandStaff) {
                drawPianoGrandBrace(
                    canvas = canvas,
                    barLineX = marginX,
                    topY = trebleTopY,
                    bottomY = sysBottomY,
                    lineSpacing = lineSpacing,
                    color = palette.inkColor
                )
            }

            val isCursorSystem = !melody.isRecording && (cursorBarIdx in hGeom.startBar..hGeom.endBar)
            val trebleClefCol = if (grandStaff && isCursorSystem && melody.activeStaff == StaffPart.TREBLE) {
                palette.cursorColor
            } else {
                palette.inkColor
            }
            val bassClefCol = if (grandStaff && isCursorSystem && melody.activeStaff == StaffPart.BASS) {
                palette.cursorColor
            } else {
                palette.inkColor
            }
            if (singleStaffBassClef) {
                drawBassClef(canvas, marginX + lineSpacing * 1.25f, trebleTopY, lineSpacing, trebleClefCol)
            } else {
                drawTrebleClef(canvas, marginX + lineSpacing * 1.30f, trebleTopY, lineSpacing, trebleClefCol)
            }
            if (grandStaff) {
                drawBassClef(canvas, marginX + lineSpacing * 1.25f, bassTopY, lineSpacing, bassClefCol)
            }

            val sysKeyEndX = if (hGeom.sysStartKey.safeFifths != 0) {
                val kLeft = marginX + lineSpacing * 2.48f
                val endT = drawKeySignatureGroup(
                    canvas = canvas,
                    leftX = kLeft,
                    staffTopY = trebleTopY,
                    isBass = singleStaffBassClef,
                    s = lineSpacing,
                    cancelledSteps = emptyList(),
                    newKey = hGeom.sysStartKey,
                    color = palette.inkColor
                )
                if (grandStaff) {
                    drawKeySignatureGroup(
                        canvas = canvas,
                        leftX = kLeft,
                        staffTopY = bassTopY,
                        isBass = true,
                        s = lineSpacing,
                        cancelledSteps = emptyList(),
                        newKey = hGeom.sysStartKey,
                        color = palette.inkColor
                    )
                }
                endT
            } else {
                marginX + lineSpacing * 2.50f
            }

            if (hGeom.isFirstSystem) {
                val tsX = max(marginX + lineSpacing * 3.45f, sysKeyEndX + lineSpacing * 0.85f)
                drawTimeSignature(
                    canvas = canvas,
                    centerX = tsX,
                    staffTopY = trebleTopY,
                    s = lineSpacing,
                    numerator = timeSig.safeNumerator,
                    denominator = timeSig.safeDenominator,
                    color = palette.inkColor
                )
                if (grandStaff) {
                    drawTimeSignature(
                        canvas = canvas,
                        centerX = tsX,
                        staffTopY = bassTopY,
                        s = lineSpacing,
                        numerator = timeSig.safeNumerator,
                        denominator = timeSig.safeDenominator,
                        color = palette.inkColor
                    )
                }
            }

            // Clef-zone vertical separator
            val clefSepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(85, 160, 150, 138)
                strokeWidth = max(1f, lineSpacing * 0.09f)
            }
            canvas.drawLine(marginX + hGeom.clefZoneW, trebleTopY, marginX + hGeom.clefZoneW, sysBottomY, clefSepPaint)

            val sysMeasureGeoms = ArrayList<MeasureSpanGeom>(hGeom.barsInSys)
            val sysChordAnchors = mutableListOf<RenderedChordAnchor>()
            val sysTupletObstacles = mutableListOf<RenderedTupletObstacle>()
            val sysPendingFermatas = mutableListOf<PendingFermataRender>()

            for (bOffset in 0 until hGeom.barsInSys) {
                val bar = hGeom.startBar + bOffset
                val bLeft = marginX + hGeom.clefZoneW + bOffset * hGeom.singleBarW
                val bRight = bLeft + hGeom.singleBarW
                val (barNoteOriginX, usableW) = pageMeasureNoteLayout(
                    melody = melody,
                    barIndex = bar,
                    bOffsetInSystem = bOffset,
                    barLeftX = bLeft,
                    singleBarW = hGeom.singleBarW,
                    lineSpacing = lineSpacing
                )
                sysMeasureGeoms += MeasureSpanGeom(
                    barIndex = bar,
                    barLeftX = bLeft,
                    barRightX = bRight,
                    noteOriginX = barNoteOriginX,
                    usableWidth = usableW
                )

                val barKeyEv = if (bar >= 1) melody.keySignatureEventAtBar(bar) else null
                if (barKeyEv != null && bOffset > 0) {
                    val isModSelected = melody.selectedAnnotation == SelectedScoreAnnotation.KeyChange(barKeyEv.id)
                    val modColor = if (isModSelected) palette.selectedColor else palette.inkColor
                    val modBarPaint = Paint(barLinePaint).apply {
                        if (isModSelected) color = palette.selectedColor
                    }
                    val thinPreX = bLeft - lineSpacing * 0.34f
                    canvas.drawLine(thinPreX, trebleTopY, thinPreX, sysBottomY, modBarPaint)
                    canvas.drawLine(bLeft, trebleTopY, bLeft, sysBottomY, modBarPaint)

                    val prevKey = melody.keySignatureAtBar(bar - 1)
                    val cancelT = prevKey.cancelledStaffStepsTo(barKeyEv.keySignature, isBass = singleStaffBassClef)
                    val kStart = bLeft + lineSpacing * 0.38f
                    val groupEndX = drawKeySignatureGroup(
                        canvas = canvas,
                        leftX = kStart,
                        staffTopY = trebleTopY,
                        isBass = singleStaffBassClef,
                        s = lineSpacing,
                        cancelledSteps = cancelT,
                        newKey = barKeyEv.keySignature,
                        color = modColor
                    )
                    if (grandStaff) {
                        val cancelB = prevKey.cancelledStaffStepsTo(barKeyEv.keySignature, isBass = true)
                        drawKeySignatureGroup(
                            canvas = canvas,
                            leftX = kStart,
                            staffTopY = bassTopY,
                            isBass = true,
                            s = lineSpacing,
                            cancelledSteps = cancelB,
                            newKey = barKeyEv.keySignature,
                            color = modColor
                        )
                    }

                    drawInteractiveModulationPill(
                        canvas = canvas,
                        barNumber = bar + 1,
                        keyEvent = barKeyEv,
                        isSelected = isModSelected,
                        pillAnchorX = bLeft - lineSpacing * 0.15f,
                        trebleTopY = trebleTopY,
                        sysBottomY = sysBottomY,
                        minHitLeftX = thinPreX - lineSpacing * 0.35f,
                        minHitRightX = groupEndX + lineSpacing * 0.3f,
                        lineSpacing = lineSpacing,
                        selectedColor = palette.selectedColor,
                        startTick = bar * ticksPerBar,
                        outHitTargets = hitTargets
                    )
                } else if (barKeyEv != null && bOffset == 0) {
                    // Key change occurring right at the opening bar of a new system
                    val isModSelected = melody.selectedAnnotation == SelectedScoreAnnotation.KeyChange(barKeyEv.id)
                    drawInteractiveModulationPill(
                        canvas = canvas,
                        barNumber = bar + 1,
                        keyEvent = barKeyEv,
                        isSelected = isModSelected,
                        pillAnchorX = bLeft,
                        trebleTopY = trebleTopY,
                        sysBottomY = sysBottomY,
                        minHitLeftX = bLeft - lineSpacing * 0.2f,
                        minHitRightX = bLeft + lineSpacing * 2.2f,
                        lineSpacing = lineSpacing,
                        selectedColor = palette.selectedColor,
                        startTick = bar * ticksPerBar,
                        outHitTargets = hitTargets
                    )
                } else {
                    // Draw measure number on every measure so proofreading wrong bars is effortless!
                    canvas.drawText(
                        "${bar + 1}",
                        bLeft + lineSpacing * 0.36f,
                        trebleTopY - lineSpacing * 1.15f,
                        barNumPaint
                    )
                }

                for (b in 1 until beatCount) {
                    val bx = barNoteOriginX + (b.toFloat() / beatCount.toFloat()) * usableW
                    canvas.drawLine(bx, trebleTopY, bx, trebleTopY + staffH, subGridPaint)
                    if (grandStaff) {
                        canvas.drawLine(bx, bassTopY, bx, bassTopY + staffH, subGridPaint)
                    }
                }

                val barSyms = symbols.filter { it.barIndex == bar }
                renderSingleMeasureSymbols(
                    canvas = canvas,
                    barSyms = barSyms,
                    timeSignature = timeSig,
                    activeKeySignature = melody.keySignatureAtBar(bar),
                    atNewSystemStart = (bOffset == 0),
                    noteOriginX = barNoteOriginX,
                    usableMeasureWidth = usableW,
                    trebleTopY = trebleTopY,
                    bassTopY = bassTopY,
                    lineSpacing = lineSpacing,
                    grandStaff = grandStaff,
                    singleStaffBassClef = singleStaffBassClef,
                    selectedNoteId = melody.selectedNoteId,
                    paperBgColor = palette.paperBgColor,
                    slot0Color = palette.slot0Color,
                    slot1Color = palette.slot1Color,
                    selectedColor = palette.selectedColor,
                    autoRestColor = palette.autoRestColor,
                    outHitTargets = hitTargets,
                    outChordAnchors = sysChordAnchors,
                    outTupletObstacles = sysTupletObstacles,
                    outPendingFermatas = sysPendingFermatas
                )

                val nextInSysHasKeyChange = (bOffset + 1 < hGeom.barsInSys) &&
                    (melody.keySignatureEventAtBar(bar + 1) != null)
                if (bar == layout.totalBars - 1) {
                    val thinX = bRight - lineSpacing * 0.36f
                    canvas.drawLine(thinX, trebleTopY, thinX, sysBottomY, barLinePaint)
                    val thickPaint = Paint(barLinePaint).apply {
                        strokeWidth = max(2.5f, lineSpacing * 0.32f)
                    }
                    canvas.drawLine(bRight, trebleTopY, bRight, sysBottomY, thickPaint)
                } else if (bar == hGeom.endBar && hGeom.nextSysKeyEv != null) {
                    val thinPreX = bRight - lineSpacing * 0.34f
                    canvas.drawLine(thinPreX, trebleTopY, thinPreX, sysBottomY, barLinePaint)
                    canvas.drawLine(bRight, trebleTopY, bRight, sysBottomY, barLinePaint)
                    val cStartX = bRight + lineSpacing * 0.36f
                    drawKeySignatureGroup(
                        canvas = canvas,
                        leftX = cStartX,
                        staffTopY = trebleTopY,
                        isBass = singleStaffBassClef,
                        s = lineSpacing,
                        cancelledSteps = hGeom.courtesyCancelTreble,
                        newKey = hGeom.nextSysKeyEv.keySignature,
                        color = palette.inkColor
                    )
                    if (grandStaff) {
                        val courtesyCancelBass = melody.keySignatureAtBar(hGeom.endBar).cancelledStaffStepsTo(
                            hGeom.nextSysKeyEv.keySignature,
                            isBass = true
                        )
                        drawKeySignatureGroup(
                            canvas = canvas,
                            leftX = cStartX,
                            staffTopY = bassTopY,
                            isBass = true,
                            s = lineSpacing,
                            cancelledSteps = courtesyCancelBass,
                            newKey = hGeom.nextSysKeyEv.keySignature,
                            color = palette.inkColor
                        )
                    }
                } else if (!nextInSysHasKeyChange) {
                    canvas.drawLine(bRight, trebleTopY, bRight, sysBottomY, barLinePaint)
                }
            }

            val sysStartTick = hGeom.startBar * ticksPerBar
            val sysEndTick = (hGeom.endBar + 1) * ticksPerBar
            val sysLeftContX = marginX + hGeom.clefZoneW + lineSpacing * 0.30f
            val sysRightContX = marginX + hGeom.clefZoneW + hGeom.singleBarW * hGeom.barsInSys - lineSpacing * 0.28f

            renderSystemPhraseSlurs(
                canvas = canvas,
                allScoreNotes = melody.notes,
                grandStaff = grandStaff,
                chordAnchors = sysChordAnchors,
                tupletObstacles = sysTupletObstacles,
                pendingFermatas = sysPendingFermatas,
                measureGeoms = sysMeasureGeoms,
                isContinuousTimeline = false,
                ticksPerBar = ticksPerBar,
                trebleTopY = trebleTopY,
                bassTopY = bassTopY,
                lineSpacing = lineSpacing,
                defaultColor = palette.expressionInkColor,
                selectedColor = palette.selectedColor,
                selectedNoteId = melody.selectedNoteId
            )

            renderPendingFermatas(
                canvas = canvas,
                pendingFermatas = sysPendingFermatas,
                lineSpacing = lineSpacing
            )

            val sysTickToX: (Int) -> Float = { tick ->
                val clamped = tick.coerceIn(sysStartTick, sysEndTick)
                if (clamped >= sysEndTick) {
                    sysRightContX
                } else {
                    val bOff = ((clamped / ticksPerBar) - hGeom.startBar).coerceIn(0, sysMeasureGeoms.lastIndex)
                    val rem = clamped % ticksPerBar
                    val mg = sysMeasureGeoms[bOff]
                    mg.noteOriginX + (rem.toFloat() / ticksPerBar.toFloat()) * mg.usableWidth
                }
            }

            renderSystemDynamicsAndHairpins(
                canvas = canvas,
                dynamicMarks = melody.dynamicMarks,
                hairpins = melody.hairpins,
                sysStartTick = sysStartTick,
                sysEndTick = sysEndTick,
                isContinuousTimeline = false,
                sysLeftContinuationX = sysLeftContX,
                sysRightContinuationX = sysRightContX,
                tickToX = sysTickToX,
                chordAnchors = sysChordAnchors,
                bottomStaffBottomY = sysBottomY,
                viewportMaxBottomY = sysBottomY + lineSpacing * 3.4f,
                lineSpacing = lineSpacing,
                inkColor = palette.expressionInkColor,
                selectedColor = palette.selectedColor,
                selectedAnnotation = melody.selectedAnnotation,
                outHitTargets = hitTargets
            )

            val sysTopRoomY = trebleTopY - (layout.systemRowStride - layout.systemBlockHeight) * 0.85f
            val hasSysModPill = (hGeom.startBar..hGeom.endBar).any { b ->
                b >= 1 && melody.keySignatureEventAtBar(b) != null
            }
            val sysChordTopY = if (melody.showChordSymbols && melody.chordSymbols.isNotEmpty()) {
                renderSystemChordSymbols(
                    canvas = canvas,
                    chordSymbols = melody.chordSymbols,
                    sysStartTick = sysStartTick,
                    sysEndTick = sysEndTick,
                    isContinuousTimeline = false,
                    tickToX = sysTickToX,
                    trebleTopY = trebleTopY,
                    hasModulationPills = hasSysModPill,
                    chordAnchors = sysChordAnchors,
                    pendingFermatas = sysPendingFermatas,
                    viewportMinTopY = sysTopRoomY,
                    lineSpacing = lineSpacing,
                    inkColor = palette.inkColor,
                    selectedColor = palette.selectedColor,
                    selectedAnnotation = melody.selectedAnnotation,
                    outHitTargets = hitTargets
                )
            } else {
                null
            }

            renderSystemTempoEvents(
                canvas = canvas,
                tempoEvents = melody.tempoEvents,
                sysStartTick = sysStartTick,
                sysEndTick = sysEndTick,
                isContinuousTimeline = false,
                sysLeftContinuationX = sysLeftContX,
                sysRightContinuationX = sysRightContX,
                tickToX = sysTickToX,
                trebleTopY = trebleTopY,
                chordAnchors = sysChordAnchors,
                pendingFermatas = sysPendingFermatas,
                extraTopClearanceY = sysChordTopY,
                viewportMinTopY = sysTopRoomY - lineSpacing * 0.4f,
                lineSpacing = lineSpacing,
                inkColor = palette.expressionInkColor,
                selectedColor = palette.selectedColor,
                selectedAnnotation = melody.selectedAnnotation,
                outHitTargets = hitTargets
            )

            // Draw Step Input Cursor on whichever row holds `cursorTick` (highlighting active staff in Grand Staff mode!)
            if (!melody.isRecording && cursorBarIdx in hGeom.startBar..hGeom.endBar) {
                val bOff = (cursorBarIdx - hGeom.startBar).coerceIn(0, sysMeasureGeoms.lastIndex)
                val mg = sysMeasureGeoms[bOff]
                val tickInBar = melody.cursorTick.coerceAtLeast(0) % ticksPerBar
                val cx = mg.noteOriginX + (tickInBar.toFloat() / ticksPerBar.toFloat()) * mg.usableWidth
                pageCursorGeom = drawStepCursorWithHandle(
                    canvas = canvas,
                    cx = cx,
                    trebleTopY = trebleTopY,
                    bassTopY = bassTopY,
                    bottomStaffBottomY = sysBottomY,
                    staffHeight = staffH,
                    lineSpacing = lineSpacing,
                    grandStaff = grandStaff,
                    activeStaff = melody.activeStaff,
                    cursorColor = pageCursorColor,
                    paperBgColor = palette.paperBgColor,
                    isDraggingCursor = isDraggingCursor
                )
            }

            // Draw Live Transport Playhead on whichever row holds `playheadBeat`
            if ((melody.isPlaying || melody.isRecording) && playBarIdx in hGeom.startBar..hGeom.endBar) {
                val bOff = (playBarIdx - hGeom.startBar).coerceIn(0, sysMeasureGeoms.lastIndex)
                val mg = sysMeasureGeoms[bOff]
                val pRemTick = (playTotalTickFloat - playBarIdx * ticksPerBar).coerceIn(0f, ticksPerBar.toFloat())
                val px = mg.noteOriginX + (pRemTick / ticksPerBar.toFloat()) * mg.usableWidth
                val phPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.playheadColor
                    strokeWidth = max(2.3f, lineSpacing * 0.22f)
                }
                canvas.drawLine(
                    px,
                    trebleTopY - lineSpacing * 1.18f,
                    px,
                    sysBottomY + lineSpacing * 1.18f,
                    phPaint
                )
                val headDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.playheadColor
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(px, trebleTopY - lineSpacing * 1.22f, lineSpacing * 0.34f, headDotPaint)
            }
        }

        // Empty Score Watermark
        if (melody.notes.isEmpty() &&
            melody.chordSymbols.isEmpty() &&
            melody.dynamicMarks.isEmpty() &&
            melody.hairpins.isEmpty() &&
            melody.tempoEvents.isEmpty() &&
            !melody.isRecording
        ) {
            val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(148, 112, 102, 90)
                textSize = max(18f, lineSpacing * 1.20f)
                textAlign = Paint.Align.CENTER
            }
            val hintY = min(
                viewportHeight - max(10f, lineSpacing * 0.75f),
                layout.totalContentHeight - scrollY - lineSpacing * 0.4f
            ).coerceAtLeast(lineSpacing * 2.5f)
            val pageHint = if (grandStaff) {
                "点上方高音谱写右手、点下方低音谱写左手 · 支持和弦标记 · 上下滑动浏览整页乐谱"
            } else {
                "点击任意小节定位试听/打谱 · 支持双行钢琴谱与和弦标记 · 点右上角「全屏看谱」放大检阅"
            }
            canvas.drawText(
                pageHint,
                viewportWidth * 0.5f,
                hintY,
                hintPaint
            )
        }

        // Subtle right-edge scrollbar indicator when multi-system page exceeds viewport height
        if (layout.totalContentHeight > viewportHeight + 4f) {
            val maxScroll = (layout.totalContentHeight - viewportHeight).coerceAtLeast(1f)
            val trackTop = lineSpacing * 0.8f
            val trackBottom = viewportHeight - lineSpacing * 0.8f
            val trackH = (trackBottom - trackTop).coerceAtLeast(24f)
            val thumbH = ((viewportHeight / layout.totalContentHeight) * trackH).coerceIn(lineSpacing * 2.6f, trackH)
            val frac = (scrollY / maxScroll).coerceIn(0f, 1f)
            val thumbTop = trackTop + frac * (trackH - thumbH)
            val barW = max(3.5f, lineSpacing * 0.38f)
            val barLeft = viewportWidth - barW - max(3f, lineSpacing * 0.32f)

            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(36, 120, 110, 98)
                style = Paint.Style.FILL
            }
            val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(135, 118, 106, 92)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(RectF(barLeft, trackTop, barLeft + barW, trackBottom), barW * 0.5f, barW * 0.5f, trackPaint)
            canvas.drawRoundRect(RectF(barLeft, thumbTop, barLeft + barW, thumbTop + thumbH), barW * 0.5f, barW * 0.5f, thumbPaint)
        }

        // Floating live beat bubble above cursor handle while dragging
        if (isDraggingCursor && pageCursorGeom != null) {
            drawCursorScrubBubble(
                canvas = canvas,
                geom = pageCursorGeom,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                minLeftX = 4f,
                lineSpacing = lineSpacing,
                cursorColor = pageCursorColor,
                melody = melody,
                grandStaff = grandStaff
            )
        }

        return hitTargets
    }

    private fun drawInteractiveModulationPill(
        canvas: Canvas,
        barNumber: Int,
        keyEvent: ScoreKeySignatureEvent,
        isSelected: Boolean,
        pillAnchorX: Float,
        trebleTopY: Float,
        sysBottomY: Float,
        minHitLeftX: Float,
        minHitRightX: Float,
        lineSpacing: Float,
        selectedColor: Int,
        startTick: Int,
        outHitTargets: MutableList<StaffHitTarget>
    ) {
        val badgeText = "$barNumber · ${keyEvent.keySignature.modulationBadgeText}"
        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSelected) Color.WHITE else Color.rgb(68, 42, 128)
            textSize = max(13f, lineSpacing * 0.96f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val textW = badgeTextPaint.measureText(badgeText)
        val pillH = max(17f, lineSpacing * 1.38f)
        val pillW = textW + lineSpacing * 0.90f
        val pillTop = trebleTopY - pillH - lineSpacing * 0.68f
        val pillRect = RectF(pillAnchorX, pillTop, pillAnchorX + pillW, pillTop + pillH)

        val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSelected) selectedColor else Color.argb(232, 238, 232, 252)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(pillRect, pillH * 0.38f, pillH * 0.38f, pillBgPaint)
        val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSelected) selectedColor else Color.argb(140, 124, 77, 204)
            style = Paint.Style.STROKE
            strokeWidth = max(1f, lineSpacing * 0.10f)
        }
        canvas.drawRoundRect(pillRect, pillH * 0.38f, pillH * 0.38f, pillStrokePaint)
        val fm = badgeTextPaint.fontMetrics
        val textBaseline = pillRect.centerY() - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(badgeText, pillRect.left + lineSpacing * 0.44f, textBaseline, badgeTextPaint)

        outHitTargets += StaffHitTarget(
            annotation = SelectedScoreAnnotation.KeyChange(keyEvent.id),
            bounds = RectF(
                min(minHitLeftX, pillRect.left),
                pillRect.top - lineSpacing * 0.2f,
                max(minHitRightX, pillRect.right) + lineSpacing * 0.25f,
                sysBottomY + lineSpacing * 0.3f
            ),
            startTick = startTick
        )
    }

    private fun resolvePageSystemIndexAtWorldY(worldY: Float, layout: PageStaffLayout): Int {
        val rowOrigin = layout.topPadding - (layout.systemRowStride - layout.systemBlockHeight) * 0.48f
        return floor((worldY - rowOrigin) / layout.systemRowStride.coerceAtLeast(1f))
            .toInt()
            .coerceIn(0, (layout.totalSystems - 1).coerceAtLeast(0))
    }

    fun resolvePageHeaderHit(
        touchX: Float,
        touchY: Float,
        scrollY: Float,
        layout: PageStaffLayout,
        melody: MelodyStudioUiState
    ): PageHeaderHitKind? {
        val worldY = touchY + scrollY
        val sysIdx = resolvePageSystemIndexAtWorldY(worldY, layout)
        val hGeom = computePageSystemHorizontalGeom(sysIdx, layout, melody)
        val headerRight = layout.marginLeftX + hGeom.clefZoneW
        if (touchX < layout.marginLeftX - layout.lineSpacing || touchX > headerRight) return null
        val trebleTopWorldY = layout.systemTrebleTopWorldY(sysIdx)
        val sysBottomWorldY = trebleTopWorldY + layout.systemBlockHeight
        if (worldY < trebleTopWorldY - layout.lineSpacing * 1.8f ||
            worldY > sysBottomWorldY + layout.lineSpacing * 1.8f
        ) {
            return null
        }
        if (hGeom.isFirstSystem) {
            val timeSigLeft = headerRight - layout.lineSpacing * 1.95f
            if (touchX >= timeSigLeft) {
                return PageHeaderHitKind.TIME_SIGNATURE
            }
        }
        return PageHeaderHitKind.KEY_SIGNATURE
    }

    fun mapPageTouchToTick(
        touchX: Float,
        touchY: Float,
        scrollY: Float,
        layout: PageStaffLayout,
        melody: MelodyStudioUiState,
        scrubFineMode: Boolean = false,
        targetStaff: StaffPart = melody.activeStaff
    ): Int {
        val timeSig = melody.timeSignature
        val ticksPerBar = timeSig.ticksPerBar.coerceAtLeast(TICKS_PER_STEP)
        val worldY = (touchY + scrollY).coerceAtLeast(0f)
        val sysIdx = resolvePageSystemIndexAtWorldY(worldY, layout)
        val hGeom = computePageSystemHorizontalGeom(sysIdx, layout, melody)
        val measuresOriginX = layout.marginLeftX + hGeom.clefZoneW
        val relX = (touchX - measuresOriginX).coerceAtLeast(0f)
        val bOffset = floor(relX / hGeom.singleBarW.coerceAtLeast(1f))
            .toInt()
            .coerceIn(0, (hGeom.barsInSys - 1).coerceAtLeast(0))
        val barIdx = hGeom.startBar + bOffset
        val bLeft = measuresOriginX + bOffset * hGeom.singleBarW
        val (noteOriginX, usableW) = pageMeasureNoteLayout(
            melody = melody,
            barIndex = barIdx,
            bOffsetInSystem = bOffset,
            barLeftX = bLeft,
            singleBarW = hGeom.singleBarW,
            lineSpacing = layout.lineSpacing
        )
        val frac = ((touchX - noteOriginX) / usableW.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val inBarTick = if (scrubFineMode) {
            snapInBarTickForScrub(frac, barIdx, ticksPerBar, targetStaff, melody)
        } else {
            snapInBarTickForBlankTap(frac, ticksPerBar, melody)
        }
        val rawTick = barIdx * ticksPerBar + inBarTick
        val minStepUnit = melody.activeTuplet?.noteTicks ?: TICKS_PER_STEP
        val maxTick = (layout.totalBars.coerceAtLeast(1) * ticksPerBar - minStepUnit).coerceAtLeast(0)
        return rawTick.coerceIn(0, maxTick)
    }

    fun mapPageTouchToTickAndStaff(
        touchX: Float,
        touchY: Float,
        scrollY: Float,
        layout: PageStaffLayout,
        melody: MelodyStudioUiState,
        scrubFineMode: Boolean = false
    ): Pair<Int, StaffPart> {
        val staff = if (!layout.grandStaff) {
            StaffPart.TREBLE
        } else {
            val worldY = (touchY + scrollY).coerceAtLeast(0f)
            val sysIdx = resolvePageSystemIndexAtWorldY(worldY, layout)
            val trebleTopWorldY = layout.systemTrebleTopWorldY(sysIdx)
            val splitWorldY = trebleTopWorldY + layout.staffHeight + layout.grandGap * 0.5f
            if (worldY >= splitWorldY) StaffPart.BASS else StaffPart.TREBLE
        }
        val tick = mapPageTouchToTick(
            touchX = touchX,
            touchY = touchY,
            scrollY = scrollY,
            layout = layout,
            melody = melody,
            scrubFineMode = scrubFineMode,
            targetStaff = staff
        )
        return tick to staff
    }

    // ========================================================================
    // 2. Multi-Page Vector A4 PDF Sheet Music Exporter
    // ========================================================================

    fun exportScoreToPdfFile(
        melody: MelodyStudioUiState,
        outputPdfFile: File
    ): Boolean {
        outputPdfFile.parentFile?.mkdirs()

        // Standard A4 Portrait in PostScript points (595 x 842 vector points)
        val pageW = 595
        val pageH = 842
        val marginX = 40f
        val contentW = pageW - marginX * 2f

        val timeSig = melody.timeSignature
        val ticksPerBar = timeSig.ticksPerBar
        val quarterBeatsPerBar = timeSig.quarterBeatsPerBar.coerceAtLeast(0.25f)
        val maxBeat = melody.notes.maxOfOrNull { it.endBeat } ?: 0f
        val maxMarkTick = maxOf(
            melody.chordSymbols.maxOfOrNull { it.safeTick + TICKS_PER_STEP } ?: 0,
            melody.dynamicMarks.maxOfOrNull { it.tick } ?: 0,
            melody.hairpins.maxOfOrNull { it.safeEndTick } ?: 0,
            melody.tempoEvents.maxOfOrNull { it.safeEndTick } ?: 0
        )
        val maxKeyBar = (melody.keySignatureEvents.maxOfOrNull { it.safeBarIndex + 1 } ?: 1)
        val barsFromNotes = maxOf(
            ceil(maxBeat / quarterBeatsPerBar).toInt(),
            ceil(maxMarkTick / ticksPerBar.toFloat()).toInt(),
            maxKeyBar
        )
        val totalBars = max(
            if (melody.notes.isEmpty() && melody.chordSymbols.isEmpty()) melody.totalBars else barsFromNotes,
            min(melody.totalBars, max(1, barsFromNotes))
        ).coerceAtLeast(1)

        val grandStaff = isEffectiveGrandStaff(melody)
        val singleStaffBassClef = !grandStaff && melody.singleStaffUsesBassClef
        val hasPdfChords = melody.showChordSymbols && melody.chordSymbols.isNotEmpty()
        val barsPerSystem = if (ticksPerBar > 1200) 3 else 4
        val totalSystems = ceil(totalBars / barsPerSystem.toFloat()).toInt().coerceAtLeast(1)

        val lineSpacing = if (grandStaff) 5.8f else 6.6f
        val staffH = lineSpacing * 4f
        val grandGap = lineSpacing * 3.5f
        val systemBlockH = if (grandStaff) (staffH * 2f + grandGap) else staffH
        val chordExtraGap = if (hasPdfChords) 14f else 0f
        val systemRowSpacing = (if (grandStaff) (systemBlockH + 66f) else (systemBlockH + 62f)) + chordExtraGap

        val symbols = buildBarSymbols(
            notes = melody.notes,
            totalBars = totalBars,
            timeSignature = timeSig,
            grandStaff = grandStaff,
            fillAutoRestsInActiveBars = true,
            fillAllBarsWithAutoRests = true
        )

        val ink = Color.rgb(24, 22, 20)
        val staffInk = Color.rgb(72, 68, 62)
        val mutedInk = Color.rgb(110, 104, 96)

        val titleText = melody.scoreTitle.trim().ifBlank { "未命名乐谱" }
        val hasSlot0 = melody.notes.any { !it.isRest && it.keyboardSlot == 0 }
        val hasSlot1 = melody.notes.any { !it.isRest && it.keyboardSlot == 1 }
        val instLabel = when {
            (melody.dualKeyboardEnabled || hasSlot1) && hasSlot0 && melody.instrument != melody.secondaryInstrument ->
                "${melody.instrument.displayName} / ${melody.secondaryInstrument.displayName}"
            !hasSlot0 && hasSlot1 -> melody.secondaryInstrument.displayName
            else -> melody.instrument.displayName
        }

        val initialTempoMark = melody.tempoEvents.firstOrNull {
            it.safeStartTick == 0 && it.kind == TempoEventKind.STEADY_BPM
        }
        val headerTempoText = if (initialTempoMark != null) {
            initialTempoMark.term?.let { "${it.italianName} (${initialTempoMark.bpm} BPM)" }
                ?: "${initialTempoMark.bpm} BPM"
        } else {
            "${melody.bpm} BPM"
        }

        val doc = PdfDocument()
        try {
            var sysIdx = 0
            var pageNum = 1

            while (sysIdx < totalSystems) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
                val page = doc.startPage(pageInfo)
                val canvas = page.canvas

                val bgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
                canvas.drawRect(0f, 0f, pageW.toFloat(), pageH.toFloat(), bgPaint)

                var curY = if (pageNum == 1) {
                    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ink
                        textSize = 21f
                        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText(titleText, pageW * 0.5f, 52f, titlePaint)

                    val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = mutedInk
                        textSize = 10.5f
                        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                    }
                    val swingClause = melody.swingPreset.scoreMarking?.let { "  ·  $it" } ?: ""
                    val keyClause = "调号：${melody.initialKeySignature.shortPillLabel}"
                    canvas.drawText(
                        "$keyClause  ·  速度：$headerTempoText$swingClause  ·  ${timeSig.label} 拍",
                        marginX,
                        78f,
                        metaPaint
                    )
                    val rightMetaPaint = Paint(metaPaint).apply { textAlign = Paint.Align.RIGHT }
                    canvas.drawText(
                        "乐器：$instLabel",
                        pageW - marginX,
                        78f,
                        rightMetaPaint
                    )

                    val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(218, 212, 204)
                        strokeWidth = 0.8f
                    }
                    canvas.drawLine(marginX, 86f, pageW - marginX, 86f, rulePaint)
                    if (hasPdfChords) 134f else 126f
                } else {
                    val runHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = mutedInk
                        textSize = 10f
                        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                    }
                    canvas.drawText("$titleText（续）", marginX, 40f, runHeaderPaint)
                    if (hasPdfChords) 92f else 84f
                }

                val bottomLimit = pageH - 58f

                while (sysIdx < totalSystems && (curY + systemBlockH) <= bottomLimit) {
                    val startBar = sysIdx * barsPerSystem
                    val barsInSys = min(barsPerSystem, totalBars - startBar)
                    val endBar = startBar + barsInSys - 1
                    val isFirstSystem = (sysIdx == 0)

                    val sysStartKey = melody.keySignatureAtBar(startBar)
                    val sysStartKeyW = keySignatureGroupWidth(0, abs(sysStartKey.safeFifths), lineSpacing)
                    val clefZoneW = (if (isFirstSystem) lineSpacing * 5.2f else lineSpacing * 3.1f) + sysStartKeyW

                    // Courtesy Key Signature (`提示调号`) if the next system's opening measure has a key change
                    val nextSysKeyEv = if (endBar + 1 < totalBars) melody.keySignatureEventAtBar(endBar + 1) else null
                    val endBarKey = melody.keySignatureAtBar(endBar)
                    val courtesyCancelTreble = if (nextSysKeyEv != null) {
                        endBarKey.cancelledStaffStepsTo(nextSysKeyEv.keySignature, isBass = singleStaffBassClef)
                    } else {
                        emptyList()
                    }
                    val courtesyGroupW = if (nextSysKeyEv != null) {
                        keySignatureGroupWidth(
                            cancelledCount = courtesyCancelTreble.size,
                            newSigCount = abs(nextSysKeyEv.keySignature.safeFifths),
                            s = lineSpacing
                        )
                    } else {
                        0f
                    }
                    val courtesyZoneW = if (nextSysKeyEv != null) {
                        lineSpacing * 0.55f + max(courtesyGroupW, lineSpacing * 0.6f)
                    } else {
                        0f
                    }

                    val availBarsW = (contentW - clefZoneW - courtesyZoneW).coerceAtLeast(lineSpacing * 16f)
                    val singleBarW = availBarsW / barsPerSystem.toFloat()
                    val systemActualW = clefZoneW + singleBarW * barsInSys + courtesyZoneW

                    val trebleTopY = curY
                    val bassTopY = trebleTopY + staffH + grandGap
                    val sysBottomY = if (grandStaff) bassTopY + staffH else trebleTopY + staffH

                    val barNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = mutedInk
                        textSize = 8.5f
                        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                    }
                    val startBarModEv = if (startBar >= 1) melody.keySignatureEventAtBar(startBar) else null
                    val startBarHeaderLabel = if (startBarModEv != null) {
                        "${startBar + 1} · ${startBarModEv.keySignature.modulationBadgeText}"
                    } else {
                        "${startBar + 1}"
                    }
                    canvas.drawText(startBarHeaderLabel, marginX, trebleTopY - 7f, barNumPaint)

                    drawFiveLines(canvas, marginX, marginX + systemActualW, trebleTopY, lineSpacing, staffInk)
                    if (grandStaff) {
                        drawFiveLines(canvas, marginX, marginX + systemActualW, bassTopY, lineSpacing, staffInk)
                    }

                    val barLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ink
                        strokeWidth = 0.9f
                    }
                    val leftBracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ink
                        strokeWidth = if (grandStaff) 1.5f else 0.9f
                    }
                    canvas.drawLine(marginX, trebleTopY, marginX, sysBottomY, leftBracePaint)
                    if (grandStaff) {
                        drawPianoGrandBrace(
                            canvas = canvas,
                            barLineX = marginX,
                            topY = trebleTopY,
                            bottomY = sysBottomY,
                            lineSpacing = lineSpacing,
                            color = ink
                        )
                    }

                    if (singleStaffBassClef) {
                        drawBassClef(canvas, marginX + lineSpacing * 1.25f, trebleTopY, lineSpacing, ink)
                    } else {
                        drawTrebleClef(canvas, marginX + lineSpacing * 1.30f, trebleTopY, lineSpacing, ink)
                    }
                    if (grandStaff) {
                        drawBassClef(canvas, marginX + lineSpacing * 1.25f, bassTopY, lineSpacing, ink)
                    }

                    // Draw active key signature at the start of EVERY PDF system
                    val sysKeyEndX = if (sysStartKey.safeFifths != 0) {
                        val kLeft = marginX + lineSpacing * 2.48f
                        val endT = drawKeySignatureGroup(
                            canvas = canvas,
                            leftX = kLeft,
                            staffTopY = trebleTopY,
                            isBass = singleStaffBassClef,
                            s = lineSpacing,
                            cancelledSteps = emptyList(),
                            newKey = sysStartKey,
                            color = ink
                        )
                        if (grandStaff) {
                            drawKeySignatureGroup(
                                canvas = canvas,
                                leftX = kLeft,
                               staffTopY = bassTopY,
                                isBass = true,
                                s = lineSpacing,
                                cancelledSteps = emptyList(),
                                newKey = sysStartKey,
                                color = ink
                            )
                        }
                        endT
                    } else {
                        marginX + lineSpacing * 2.50f
                    }

                    if (isFirstSystem) {
                        val tsX = max(marginX + lineSpacing * 3.45f, sysKeyEndX + lineSpacing * 0.85f)
                        drawTimeSignature(
                            canvas = canvas,
                            centerX = tsX,
                            staffTopY = trebleTopY,
                            s = lineSpacing,
                            numerator = timeSig.safeNumerator,
                            denominator = timeSig.safeDenominator,
                            color = ink
                        )
                        if (grandStaff) {
                            drawTimeSignature(
                                canvas = canvas,
                                centerX = tsX,
                                staffTopY = bassTopY,
                                s = lineSpacing,
                                numerator = timeSig.safeNumerator,
                                denominator = timeSig.safeDenominator,
                                color = ink
                            )
                        }
                    }

                    val basePadL = lineSpacing * 1.25f
                    val padR = lineSpacing * 0.80f

                    val sysMeasureGeoms = ArrayList<MeasureSpanGeom>(barsInSys)
                    val sysChordAnchors = mutableListOf<RenderedChordAnchor>()
                    val sysTupletObstacles = mutableListOf<RenderedTupletObstacle>()
                    val sysPendingFermatas = mutableListOf<PendingFermataRender>()

                    for (bOffset in 0 until barsInSys) {
                        val bar = startBar + bOffset
                        val bLeft = marginX + clefZoneW + bOffset * singleBarW
                        val bRight = bLeft + singleBarW
                        val midSysKeyEv = if (bOffset > 0 && bar >= 1) melody.keySignatureEventAtBar(bar) else null
                        val midKeyW = if (midSysKeyEv != null) midBarKeyChangeWidth(melody, bar, lineSpacing) else 0f
                        val effPadL = if (midSysKeyEv != null) {
                            max(basePadL, lineSpacing * 0.45f + midKeyW + lineSpacing * 0.65f)
                        } else {
                            basePadL
                        }
                        val usableW = (singleBarW - effPadL - padR).coerceAtLeast(lineSpacing * 2.6f)
                        val barNoteOriginX = bLeft + effPadL

                        sysMeasureGeoms += MeasureSpanGeom(
                            barIndex = bar,
                            barLeftX = bLeft,
                            barRightX = bRight,
                            noteOriginX = barNoteOriginX,
                            usableWidth = usableW
                        )

                        if (midSysKeyEv != null) {
                            // Thin double barline + cancellation naturals (`♮`) + new key signature group
                            val thinPreX = bLeft - lineSpacing * 0.34f
                            canvas.drawLine(thinPreX, trebleTopY, thinPreX, sysBottomY, barLinePaint)
                            canvas.drawLine(bLeft, trebleTopY, bLeft, sysBottomY, barLinePaint)

                            val prevKey = melody.keySignatureAtBar(bar - 1)
                            val cancelT = prevKey.cancelledStaffStepsTo(midSysKeyEv.keySignature, isBass = singleStaffBassClef)
                            val kStart = bLeft + lineSpacing * 0.38f
                            drawKeySignatureGroup(
                                canvas = canvas,
                                leftX = kStart,
                                staffTopY = trebleTopY,
                                isBass = singleStaffBassClef,
                                s = lineSpacing,
                                cancelledSteps = cancelT,
                                newKey = midSysKeyEv.keySignature,
                                color = ink
                            )
                            if (grandStaff) {
                                val cancelB = prevKey.cancelledStaffStepsTo(midSysKeyEv.keySignature, isBass = true)
                                drawKeySignatureGroup(
                                    canvas = canvas,
                                    leftX = kStart,
                                    staffTopY = bassTopY,
                                    isBass = true,
                                    s = lineSpacing,
                                    cancelledSteps = cancelB,
                                    newKey = midSysKeyEv.keySignature,
                                    color = ink
                                )
                            }
                            canvas.drawText(
                                midSysKeyEv.keySignature.modulationBadgeText,
                                bLeft + lineSpacing * 0.25f,
                                trebleTopY - 7f,
                                barNumPaint
                            )
                        }

                        val barSyms = symbols.filter { it.barIndex == bar }
                        renderSingleMeasureSymbols(
                            canvas = canvas,
                            barSyms = barSyms,
                            timeSignature = timeSig,
                            activeKeySignature = melody.keySignatureAtBar(bar),
                            atNewSystemStart = (bOffset == 0),
                            noteOriginX = barNoteOriginX,
                            usableMeasureWidth = usableW,
                            trebleTopY = trebleTopY,
                            bassTopY = bassTopY,
                            lineSpacing = lineSpacing,
                            grandStaff = grandStaff,
                            singleStaffBassClef = singleStaffBassClef,
                            selectedNoteId = null,
                            paperBgColor = Color.WHITE,
                            slot0Color = ink,
                            slot1Color = ink,
                            selectedColor = ink,
                            autoRestColor = ink,
                            outHitTargets = null,
                            outChordAnchors = sysChordAnchors,
                            outTupletObstacles = sysTupletObstacles,
                            outPendingFermatas = sysPendingFermatas
                        )

                        val nextInSysHasKeyChange = (bOffset + 1 < barsInSys) &&
                            (melody.keySignatureEventAtBar(bar + 1) != null)
                        if (bar == totalBars - 1) {
                            val thinX = bRight - lineSpacing * 0.36f
                            canvas.drawLine(thinX, trebleTopY, thinX, sysBottomY, barLinePaint)
                            val thickPaint = Paint(barLinePaint).apply { strokeWidth = 2.2f }
                            canvas.drawLine(bRight, trebleTopY, bRight, sysBottomY, thickPaint)
                        } else if (bar == endBar && nextSysKeyEv != null) {
                            // End of PDF system followed immediately by a key change on the next system:
                            // draw thin double barline + Courtesy Key Signature (`提示调号`)!
                            val thinPreX = bRight - lineSpacing * 0.34f
                            canvas.drawLine(thinPreX, trebleTopY, thinPreX, sysBottomY, barLinePaint)
                            canvas.drawLine(bRight, trebleTopY, bRight, sysBottomY, barLinePaint)
                            val cStartX = bRight + lineSpacing * 0.36f
                            drawKeySignatureGroup(
                                canvas = canvas,
                                leftX = cStartX,
                                staffTopY = trebleTopY,
                                isBass = singleStaffBassClef,
                                s = lineSpacing,
                                cancelledSteps = courtesyCancelTreble,
                                newKey = nextSysKeyEv.keySignature,
                                color = ink
                            )
                            if (grandStaff) {
                                val courtesyCancelBass = endBarKey.cancelledStaffStepsTo(
                                    nextSysKeyEv.keySignature,
                                    isBass = true
                                )
                                drawKeySignatureGroup(
                                    canvas = canvas,
                                    leftX = cStartX,
                                    staffTopY = bassTopY,
                                    isBass = true,
                                    s = lineSpacing,
                                    cancelledSteps = courtesyCancelBass,
                                    newKey = nextSysKeyEv.keySignature,
                                    color = ink
                                )
                            }
                        } else if (!nextInSysHasKeyChange) {
                            canvas.drawLine(bRight, trebleTopY, bRight, sysBottomY, barLinePaint)
                        }
                    }

                    // Render Slurs (with automatic PDF line-break split & continuation!), Fermatas, Dynamics & Tempo Map
                    val sysStartTick = startBar * ticksPerBar
                    val sysEndTick = (endBar + 1) * ticksPerBar
                    val sysLeftContX = marginX + clefZoneW + lineSpacing * 0.30f
                    val sysRightContX = marginX + clefZoneW + singleBarW * barsInSys - lineSpacing * 0.28f

                    renderSystemPhraseSlurs(
                        canvas = canvas,
                        allScoreNotes = melody.notes,
                        grandStaff = grandStaff,
                        chordAnchors = sysChordAnchors,
                        tupletObstacles = sysTupletObstacles,
                        pendingFermatas = sysPendingFermatas,
                        measureGeoms = sysMeasureGeoms,
                        isContinuousTimeline = false,
                        ticksPerBar = ticksPerBar,
                        trebleTopY = trebleTopY,
                        bassTopY = bassTopY,
                        lineSpacing = lineSpacing,
                        defaultColor = ink,
                        selectedColor = ink,
                        selectedNoteId = null
                    )

                    renderPendingFermatas(
                        canvas = canvas,
                        pendingFermatas = sysPendingFermatas,
                        lineSpacing = lineSpacing
                    )

                    val pdfTickToX: (Int) -> Float = { tick ->
                        val clamped = tick.coerceIn(sysStartTick, sysEndTick)
                        if (clamped >= sysEndTick) {
                            sysRightContX
                        } else {
                            val bOff = ((clamped / ticksPerBar) - startBar).coerceIn(0, sysMeasureGeoms.lastIndex)
                            val rem = clamped % ticksPerBar
                            val mg = sysMeasureGeoms[bOff]
                            mg.noteOriginX + (rem.toFloat() / ticksPerBar.toFloat()) * mg.usableWidth
                        }
                    }

                    renderSystemDynamicsAndHairpins(
                        canvas = canvas,
                        dynamicMarks = melody.dynamicMarks,
                        hairpins = melody.hairpins,
                        sysStartTick = sysStartTick,
                        sysEndTick = sysEndTick,
                        isContinuousTimeline = false,
                        sysLeftContinuationX = sysLeftContX,
                        sysRightContinuationX = sysRightContX,
                        tickToX = pdfTickToX,
                        chordAnchors = sysChordAnchors,
                        bottomStaffBottomY = sysBottomY,
                        viewportMaxBottomY = sysBottomY + 26f,
                        lineSpacing = lineSpacing,
                        inkColor = ink,
                        selectedColor = ink,
                        selectedAnnotation = null,
                        outHitTargets = null
                    )

                    val hasPdfSysModLabel = (startBar..endBar).any { b ->
                        b >= 1 && melody.keySignatureEventAtBar(b) != null
                    }
                    val pdfChordTopY = if (hasPdfChords) {
                        renderSystemChordSymbols(
                            canvas = canvas,
                            chordSymbols = melody.chordSymbols,
                            sysStartTick = sysStartTick,
                            sysEndTick = sysEndTick,
                            isContinuousTimeline = false,
                            tickToX = pdfTickToX,
                            trebleTopY = trebleTopY,
                            hasModulationPills = hasPdfSysModLabel,
                            chordAnchors = sysChordAnchors,
                            pendingFermatas = sysPendingFermatas,
                            viewportMinTopY = trebleTopY - 38f,
                            lineSpacing = lineSpacing,
                            inkColor = ink,
                            selectedColor = ink,
                            selectedAnnotation = null,
                            outHitTargets = null
                        )
                    } else {
                        null
                    }

                    renderSystemTempoEvents(
                        canvas = canvas,
                        tempoEvents = melody.tempoEvents,
                        sysStartTick = sysStartTick,
                        sysEndTick = sysEndTick,
                        isContinuousTimeline = false,
                        sysLeftContinuationX = sysLeftContX,
                        sysRightContinuationX = sysRightContX,
                        tickToX = pdfTickToX,
                        trebleTopY = trebleTopY,
                        chordAnchors = sysChordAnchors,
                        pendingFermatas = sysPendingFermatas,
                        extraTopClearanceY = pdfChordTopY,
                        viewportMinTopY = trebleTopY - 42f,
                        lineSpacing = lineSpacing,
                        inkColor = ink,
                        selectedColor = ink,
                        selectedAnnotation = null,
                        outHitTargets = null
                    )

                    curY += systemRowSpacing
                    sysIdx++
                }

                val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(145, 138, 128)
                    textSize = 8.5f
                    typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(
                    "— 第 $pageNum 页 · SoundTracker 五线谱导出 —",
                    pageW * 0.5f,
                    pageH - 22f,
                    footerPaint
                )

                doc.finishPage(page)
                pageNum++
            }

            FileOutputStream(outputPdfFile).use { fos ->
                doc.writeTo(fos)
            }
            return outputPdfFile.exists() && outputPdfFile.length() > 0L
        } finally {
            doc.close()
        }
    }

    // ========================================================================
    // Shared Classical Notation Measure Renderer
    // ========================================================================

    private fun renderSingleMeasureSymbols(
        canvas: Canvas,
        barSyms: List<SlicedSymbol>,
        timeSignature: TimeSignature,
        activeKeySignature: KeySignature,
        atNewSystemStart: Boolean,
        noteOriginX: Float,
        usableMeasureWidth: Float,
        trebleTopY: Float,
        bassTopY: Float,
        lineSpacing: Float,
        grandStaff: Boolean,
        singleStaffBassClef: Boolean = false,
        selectedNoteId: String?,
        paperBgColor: Int,
        slot0Color: Int,
        slot1Color: Int,
        selectedColor: Int,
        autoRestColor: Int,
        outHitTargets: MutableList<StaffHitTarget>?,
        outChordAnchors: MutableList<RenderedChordAnchor>,
        outTupletObstacles: MutableList<RenderedTupletObstacle>,
        outPendingFermatas: MutableList<PendingFermataRender>
    ) {
        if (barSyms.isEmpty()) return
        val ticksPerBar = timeSignature.ticksPerBar

        for (staffIdx in 0..(if (grandStaff) 1 else 0)) {
            val isBass = (staffIdx == 1)
            val useBassClefSteps = isBass || (!grandStaff && singleStaffBassClef)
            val staffTopY = if (isBass) bassTopY else trebleTopY
            val staffBottomY = staffTopY + 4f * lineSpacing
            val refTopStep = if (useBassClefSteps) BASS_TOP_STEP else TREBLE_TOP_STEP
            val refBottomStep = if (useBassClefSteps) BASS_BOTTOM_STEP else TREBLE_BOTTOM_STEP
            val refMiddleStep = if (useBassClefSteps) BASS_MIDDLE_STEP else TREBLE_MIDDLE_STEP

            val staffSyms = barSyms.filter { it.useBassStaff == isBass }
            if (staffSyms.isEmpty()) continue

            // 1. Render Rests on this staff (+ optional Fermata above explicit rest)
            val rests = staffSyms.filter { it.isRest }
            for (rest in rests) {
                val rx = if (rest.durTicks >= ticksPerBar && rest.tuplet == null) {
                    noteOriginX + usableMeasureWidth * 0.46f
                } else {
                    noteOriginX + (rest.tickInBar / ticksPerBar.toFloat()) * usableMeasureWidth
                }
                val isSelected = rest.sourceNoteId != null && rest.sourceNoteId == selectedNoteId
                val rColor = when {
                    isSelected -> selectedColor
                    rest.isAutoRest -> autoRestColor
                    else -> slot0Color
                }
                val hitBox = drawRestGlyph(
                    canvas = canvas,
                    x = rx,
                    staffTopY = staffTopY,
                    lineSpacing = lineSpacing,
                    baseDenom = rest.baseDenom,
                    isDotted = rest.isDotted,
                    color = rColor
                )
                if (rest.hasFermata) {
                    outPendingFermatas += PendingFermataRender(
                        xCenter = rx,
                        staffTopY = staffTopY,
                        useBassStaff = isBass,
                        minClearanceTopY = staffTopY - lineSpacing * 0.45f,
                        color = if (isSelected) selectedColor else slot0Color
                    )
                }
                if (isSelected) {
                    val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(42, Color.red(selectedColor), Color.green(selectedColor), Color.blue(selectedColor))
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(hitBox, lineSpacing * 0.4f, lineSpacing * 0.4f, selPaint)
                }
                if (outHitTargets != null && rest.sourceNoteId != null) {
                    outHitTargets += StaffHitTarget(
                        noteId = rest.sourceNoteId,
                        bounds = hitBox,
                        startTick = rest.barIndex * ticksPerBar + rest.tickInBar
                    )
                }
            }

            // 2. Group pitched notes by `(tickInBar, durTicks, tuplet?.groupKey)` into Chords
            val pitched = staffSyms.filterNot { it.isRest }
            val chords = if (pitched.isEmpty()) {
                emptyList()
            } else {
                pitched
                    .groupBy { Triple(it.tickInBar, it.durTicks, it.tuplet?.groupKey) }
                    .entries
                    .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                    .map { (key, list) ->
                        val first = list.first()
                        ChordEvent(
                            barIndex = first.barIndex,
                            tickInBar = key.first,
                            durTicks = key.second,
                            baseDenom = first.baseDenom,
                            isDotted = first.isDotted,
                            useBassStaff = isBass,
                            tuplet = first.tuplet,
                            notes = list.sortedBy { it.midiNote }
                        )
                    }
            }

            val headRx = lineSpacing * 0.62f
            val headRy = lineSpacing * 0.44f
            val stemStroke = max(1.1f, lineSpacing * 0.13f)

            data class StemGeometry(
                val cx: Float,
                val stemX: Float,
                val anchorY: Float,
                val tipY: Float,
                val minNoteY: Float,
                val maxNoteY: Float,
                var topExtremeY: Float,
                var bottomExtremeY: Float,
                val stemUp: Boolean,
                val color: Int
            )
            val stemGeoms = arrayOfNulls<StemGeometry>(chords.size)
            val renderedBeams = mutableListOf<RenderedBeamInfo>()

            if (chords.isNotEmpty()) {
                val accidentalTracker = MeasureAccidentalTracker(activeKeySignature)

                val beamedChordIndices = HashSet<Int>()
                val beamGroups = mutableListOf<List<Int>>()
                var ci = 0
                while (ci < chords.size) {
                    val c0 = chords[ci]
                    if (c0.baseDenom >= 8) {
                        val group = mutableListOf(ci)
                        var cj = ci + 1
                        while (cj < chords.size) {
                            val prevC = chords[cj - 1]
                            val cn = chords[cj]
                            if (cn.baseDenom < 8) break

                            val hasRestBetween = rests.any { r ->
                                r.tickInBar >= (prevC.tickInBar + prevC.durTicks - 2) && r.tickInBar < cn.tickInBar
                            }
                            if (hasRestBetween) break

                            val sameGroup = if (c0.tuplet != null || cn.tuplet != null) {
                                c0.tuplet != null &&
                                    cn.tuplet?.groupKey == c0.tuplet.groupKey &&
                                    abs(cn.tickInBar - (prevC.tickInBar + prevC.durTicks)) <= 2
                            } else {
                                timeSignature.beamBucketForTick(cn.tickInBar) ==
                                    timeSignature.beamBucketForTick(c0.tickInBar)
                            }
                            if (sameGroup) {
                                group += cj
                                cj++
                            } else {
                                break
                            }
                        }
                        if (group.size >= 2) {
                            beamGroups += group
                            beamedChordIndices.addAll(group)
                        }
                        ci = cj
                    } else {
                        ci++
                    }
                }

                val chordStemUp = BooleanArray(chords.size)
                for ((idx, ch) in chords.withIndex()) {
                    val lowStep = activeKeySignature.spellMidi(ch.notes.first().midiNote).diatonicStep
                    val highStep = activeKeySignature.spellMidi(ch.notes.last().midiNote).diatonicStep
                    val avgDist = ((lowStep + highStep) * 0.5f) - refMiddleStep
                    chordStemUp[idx] = avgDist < 0f
                }
                for (grp in beamGroups) {
                    var sum = 0f
                    for (gIdx in grp) {
                        val ch = chords[gIdx]
                        val lowStep = activeKeySignature.spellMidi(ch.notes.first().midiNote).diatonicStep
                        val highStep = activeKeySignature.spellMidi(ch.notes.last().midiNote).diatonicStep
                        sum += ((lowStep + highStep) * 0.5f) - refMiddleStep
                    }
                    val groupUp = sum < 0f
                    for (gIdx in grp) {
                        chordStemUp[gIdx] = groupUp
                    }
                }

                for ((idx, chord) in chords.withIndex()) {
                    val cx = noteOriginX + (chord.tickInBar / ticksPerBar.toFloat()) * usableMeasureWidth
                    val stemUp = chordStemUp[idx]

                    val resolvedNotes = chord.notes.map { sym ->
                        accidentalTracker.resolveNote(
                            midiNote = sym.midiNote,
                            tiedFromPrev = sym.tiedFromPrev,
                            tickInBar = chord.tickInBar,
                            atNewSystemStart = atNewSystemStart && chord.tickInBar == 0
                        )
                    }
                    val stepList = resolvedNotes.map { it.first.diatonicStep }

                    val shiftedSide = BooleanArray(chord.notes.size)
                    if (stemUp) {
                        for (k in 1 until chord.notes.size) {
                            if (!shiftedSide[k - 1] && abs(stepList[k] - stepList[k - 1]) == 1) {
                                shiftedSide[k] = true
                            }
                        }
                    } else {
                        for (k in chord.notes.size - 2 downTo 0) {
                            if (!shiftedSide[k + 1] && abs(stepList[k + 1] - stepList[k]) == 1) {
                                shiftedSide[k] = true
                            }
                        }
                    }

                    // Ledger lines
                    val minStep = stepList.minOrNull() ?: refMiddleStep
                    val maxStep = stepList.maxOrNull() ?: refMiddleStep
                    val ledgerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = slot0Color
                        strokeWidth = max(1.1f, lineSpacing * 0.13f)
                    }
                    val ledgerHalfW = headRx * 1.68f
                    if (minStep <= refBottomStep - 2) {
                        var ls = refBottomStep - 2
                        while (ls >= minStep) {
                            val ly = stepToY(ls, refTopStep, staffTopY, lineSpacing)
                            canvas.drawLine(cx - ledgerHalfW, ly, cx + ledgerHalfW, ly, ledgerPaint)
                            ls -= 2
                        }
                    }
                    if (maxStep >= refTopStep + 2) {
                        var ls = refTopStep + 2
                        while (ls <= maxStep) {
                            val ly = stepToY(ls, refTopStep, staffTopY, lineSpacing)
                            canvas.drawLine(cx - ledgerHalfW, ly, cx + ledgerHalfW, ly, ledgerPaint)
                            ls += 2
                        }
                    }

                    var accSlotOffset = 0
                    for ((ni, sym) in chord.notes.withIndex()) {
                        val (sp, accSign) = resolvedNotes[ni]
                        val ny = stepToY(sp.diatonicStep, refTopStep, staffTopY, lineSpacing)
                        val shiftX = if (shiftedSide[ni]) {
                            if (stemUp) (headRx * 1.82f) else (-headRx * 1.82f)
                        } else {
                            0f
                        }
                        val headX = cx + shiftX

                        val isSel = sym.sourceNoteId != null && sym.sourceNoteId == selectedNoteId
                        val nColor = when {
                            isSel -> selectedColor
                            sym.keyboardSlot == 1 -> slot1Color
                            else -> slot0Color
                        }

                        if (isSel) {
                            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.argb(46, Color.red(selectedColor), Color.green(selectedColor), Color.blue(selectedColor))
                                style = Paint.Style.FILL
                            }
                            canvas.drawCircle(headX, ny, lineSpacing * 1.05f, haloPaint)
                        }

                        if (accSign != AccidentalSign.NONE) {
                            val accX = cx - headRx * 2.25f - (accSlotOffset % 2) * (lineSpacing * 0.95f)
                            accSlotOffset++
                            when (accSign) {
                                AccidentalSign.SHARP -> drawSharpSign(canvas, accX, ny, lineSpacing, nColor)
                                AccidentalSign.FLAT -> drawFlatSign(canvas, accX, ny, lineSpacing, nColor)
                                AccidentalSign.NATURAL -> drawNaturalSign(canvas, accX, ny, lineSpacing, nColor)
                                AccidentalSign.NONE -> Unit
                            }
                        }

                        drawNotehead(
                            canvas = canvas,
                            cx = headX,
                            cy = ny,
                            rx = headRx,
                            ry = headRy,
                            hollow = chord.baseDenom <= 2,
                            isWhole = chord.baseDenom == 1,
                            color = nColor
                        )

                        if (chord.isDotted) {
                            val dotX = cx + headRx * (if (shiftedSide.any { it } && stemUp) 3.1f else 1.65f)
                            val dotY = if (sp.diatonicStep % 2 == 0) ny - lineSpacing * 0.38f else ny
                            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = nColor
                                style = Paint.Style.FILL
                            }
                            canvas.drawCircle(dotX, dotY, max(1.6f, lineSpacing * 0.20f), dotPaint)
                        }

                        if (sym.tiedToNext) {
                            val tieStartX = headX + headRx * 0.85f
                            val nextX = noteOriginX + ((chord.tickInBar + chord.durTicks) / ticksPerBar.toFloat()) * usableMeasureWidth
                            val tieEndX = max(tieStartX + lineSpacing * 1.4f, nextX - headRx * 0.7f)
                            drawTieArc(
                                canvas = canvas,
                                x1 = tieStartX,
                                x2 = tieEndX,
                                y = ny,
                                curveAbove = !stemUp,
                                lineSpacing = lineSpacing,
                                color = nColor
                            )
                        }

                        if (outHitTargets != null && sym.sourceNoteId != null) {
                            val pad = lineSpacing * 0.85f
                            outHitTargets += StaffHitTarget(
                                noteId = sym.sourceNoteId,
                                bounds = RectF(headX - pad, ny - pad, headX + pad, ny + pad),
                                startTick = sym.barIndex * ticksPerBar + sym.tickInBar
                            )
                        }
                    }

                    val lowY = stepToY(minStep, refTopStep, staffTopY, lineSpacing)
                    val highY = stepToY(maxStep, refTopStep, staffTopY, lineSpacing)
                    val anySel = chord.notes.any { it.sourceNoteId != null && it.sourceNoteId == selectedNoteId }
                    val allSlot1 = chord.notes.all { it.keyboardSlot == 1 }
                    val sColor = when {
                        anySel -> selectedColor
                        allSlot1 -> slot1Color
                        else -> slot0Color
                    }

                    if (chord.baseDenom >= 2) {
                        val stemLen = lineSpacing * 3.35f
                        val stemX = if (stemUp) (cx + headRx * 0.90f) else (cx - headRx * 0.90f)
                        val anchorY = if (stemUp) lowY else highY
                        val tipY = if (stemUp) (highY - stemLen) else (lowY + stemLen)

                        stemGeoms[idx] = StemGeometry(
                            cx = cx,
                            stemX = stemX,
                            anchorY = anchorY,
                            tipY = tipY,
                            minNoteY = highY - headRy,
                            maxNoteY = lowY + headRy,
                            topExtremeY = min(highY - headRy, tipY),
                            bottomExtremeY = max(lowY + headRy, tipY),
                            stemUp = stemUp,
                            color = sColor
                        )

                        if (idx !in beamedChordIndices) {
                            val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = sColor
                                strokeWidth = stemStroke
                            }
                            canvas.drawLine(stemX, anchorY, stemX, tipY, stemPaint)

                            if (chord.baseDenom >= 8) {
                                drawNoteFlags(
                                    canvas = canvas,
                                    stemX = stemX,
                                    tipY = tipY,
                                    stemUp = stemUp,
                                    flagCount = if (chord.baseDenom >= 16) 2 else 1,
                                    lineSpacing = lineSpacing,
                                    color = sColor
                                )
                            }
                        }
                    } else {
                        stemGeoms[idx] = StemGeometry(
                            cx = cx,
                            stemX = cx,
                            anchorY = lowY,
                            tipY = highY,
                            minNoteY = highY - headRy,
                            maxNoteY = lowY + headRy,
                            topExtremeY = highY - headRy,
                            bottomExtremeY = lowY + headRy,
                            stemUp = true,
                            color = sColor
                        )
                    }
                }

                // 3. Render Beams (`符杠`)
                for (grp in beamGroups) {
                    val firstG = stemGeoms[grp.first()] ?: continue
                    val lastG = stemGeoms[grp.last()] ?: continue
                    val stemUp = firstG.stemUp
                    val beamThickness = lineSpacing * 0.44f
                    val beamGap = lineSpacing * 0.66f

                    var yStart = firstG.tipY
                    var yEnd = lastG.tipY
                    val rawSlope = yEnd - yStart
                    val maxTilt = lineSpacing * 0.95f
                    if (abs(rawSlope) > maxTilt) {
                        val mid = (yStart + yEnd) * 0.5f
                        val clampedDelta = if (rawSlope > 0f) maxTilt * 0.5f else -maxTilt * 0.5f
                        yStart = mid - clampedDelta
                        yEnd = mid + clampedDelta
                    }

                    val xSpan = (lastG.stemX - firstG.stemX).coerceAtLeast(1f)
                    var extraPush = 0f
                    for (gIdx in grp) {
                        val sg = stemGeoms[gIdx] ?: continue
                        val t = ((sg.stemX - firstG.stemX) / xSpan).coerceIn(0f, 1f)
                        val beamYAtNote = yStart + (yEnd - yStart) * t
                        if (stemUp) {
                            val needed = beamYAtNote - sg.tipY
                            if (needed > extraPush) extraPush = needed
                        } else {
                            val needed = sg.tipY - beamYAtNote
                            if (needed > extraPush) extraPush = needed
                        }
                    }
                    if (extraPush > 0f) {
                        if (stemUp) {
                            yStart -= extraPush
                            yEnd -= extraPush
                        } else {
                            yStart += extraPush
                            yEnd += extraPush
                        }
                    }

                    for (gIdx in grp) {
                        val sg = stemGeoms[gIdx] ?: continue
                        val t = ((sg.stemX - firstG.stemX) / xSpan).coerceIn(0f, 1f)
                        val meetY = yStart + (yEnd - yStart) * t
                        val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = sg.color
                            strokeWidth = stemStroke
                        }
                        canvas.drawLine(sg.stemX, sg.anchorY, sg.stemX, meetY, stemPaint)
                        stemGeoms[gIdx] = sg.copy(
                            tipY = meetY,
                            topExtremeY = min(sg.minNoteY, meetY),
                            bottomExtremeY = max(sg.maxNoteY, meetY)
                        )
                    }

                    drawSlantedBeam(
                        canvas = canvas,
                        x1 = firstG.stemX - stemStroke * 0.5f,
                        y1 = yStart,
                        x2 = lastG.stemX + stemStroke * 0.5f,
                        y2 = yEnd,
                        thickness = beamThickness,
                        downwardFromLine = stemUp,
                        color = firstG.color
                    )

                    val secOffset = if (stemUp) beamGap else -beamGap
                    for (pos in grp.indices) {
                        val gIdx = grp[pos]
                        if (chords[gIdx].baseDenom < 16) continue
                        val sg = stemGeoms[gIdx] ?: continue
                        val nextIs16 = pos + 1 < grp.size && chords[grp[pos + 1]].baseDenom >= 16
                        val prevIs16 = pos > 0 && chords[grp[pos - 1]].baseDenom >= 16

                        if (nextIs16) {
                            val sgNext = stemGeoms[grp[pos + 1]] ?: continue
                            val t1 = ((sg.stemX - firstG.stemX) / xSpan).coerceIn(0f, 1f)
                            val t2 = ((sgNext.stemX - firstG.stemX) / xSpan).coerceIn(0f, 1f)
                            drawSlantedBeam(
                                canvas = canvas,
                                x1 = sg.stemX - stemStroke * 0.5f,
                                y1 = yStart + (yEnd - yStart) * t1 + secOffset,
                                x2 = sgNext.stemX + stemStroke * 0.5f,
                                y2 = yStart + (yEnd - yStart) * t2 + secOffset,
                                thickness = beamThickness,
                                downwardFromLine = stemUp,
                                color = sg.color
                            )
                        } else if (!prevIs16) {
                            val stubLen = lineSpacing * 0.95f
                            val xA: Float
                            val xB: Float
                            if (pos == 0) {
                                xA = sg.stemX
                                xB = min(lastG.stemX, sg.stemX + stubLen)
                            } else {
                                xA = max(firstG.stemX, sg.stemX - stubLen)
                                xB = sg.stemX
                            }
                            val tA = ((xA - firstG.stemX) / xSpan).coerceIn(0f, 1f)
                            val tB = ((xB - firstG.stemX) / xSpan).coerceIn(0f, 1f)
                            drawSlantedBeam(
                                canvas = canvas,
                                x1 = xA,
                                y1 = yStart + (yEnd - yStart) * tA + secOffset,
                                x2 = xB,
                                y2 = yStart + (yEnd - yStart) * tB + secOffset,
                                thickness = beamThickness,
                                downwardFromLine = stemUp,
                                color = sg.color
                            )
                        }
                    }

                    renderedBeams += RenderedBeamInfo(
                        chordIndices = grp,
                        x1 = firstG.stemX,
                        y1 = yStart,
                        x2 = lastG.stemX,
                        y2 = yEnd,
                        stemUp = stemUp,
                        color = firstG.color
                    )
                }

                // 4. Render Close-to-Note Articulations (`.`, `▼`, `-`, `-.`) & Accents (`>`, `^`)
                for ((idx, chord) in chords.withIndex()) {
                    val sg = stemGeoms[idx] ?: continue
                    val articulation = chord.notes.firstNotNullOfOrNull { it.articulation }
                    val accent = chord.notes.firstNotNullOfOrNull { it.accent }
                    val hasFermata = chord.notes.any { it.hasFermata }

                    var curTopExt = sg.topExtremeY
                    var curBotExt = sg.bottomExtremeY

                    // Notehead-side placement (opposite stem; whole notes place staccato/tenuto below, marcato/fermata above)
                    val placeNoteSideAbove = !sg.stemUp
                    val baseHeadRefY = if (placeNoteSideAbove) sg.minNoteY else sg.maxNoteY

                    if (articulation != null) {
                        val artOuterY = drawNoteArticulationGlyph(
                            canvas = canvas,
                            cx = sg.cx,
                            noteBoundaryY = baseHeadRefY,
                            placeAbove = placeNoteSideAbove,
                            staffTopY = staffTopY,
                            staffBottomY = staffBottomY,
                            lineSpacing = lineSpacing,
                            articulation = articulation,
                            color = sg.color
                        )
                        if (placeNoteSideAbove) {
                            curTopExt = min(curTopExt, artOuterY)
                        } else {
                            curBotExt = max(curBotExt, artOuterY)
                        }
                    }

                    if (accent == NoteAccent.ACCENT) {
                        val refY = if (placeNoteSideAbove) {
                            min(curTopExt, staffTopY - lineSpacing * 0.15f)
                        } else {
                            max(curBotExt, staffBottomY + lineSpacing * 0.15f)
                        }
                        val accOuterY = drawAccentChevron(
                            canvas = canvas,
                            cx = sg.cx,
                            refY = refY,
                            placeAbove = placeNoteSideAbove,
                            lineSpacing = lineSpacing,
                            color = sg.color
                        )
                        if (placeNoteSideAbove) {
                            curTopExt = min(curTopExt, accOuterY)
                        } else {
                            curBotExt = max(curBotExt, accOuterY)
                        }
                    } else if (accent == NoteAccent.MARCATO) {
                        // Classical rule: Marcato (`^`) is always placed ABOVE the staff & upward stem
                        val refTop = min(curTopExt, staffTopY - lineSpacing * 0.25f)
                        val marcOuterTop = drawMarcatoWedge(
                            canvas = canvas,
                            cx = sg.cx,
                            refTopY = refTop,
                            lineSpacing = lineSpacing,
                            color = sg.color
                        )
                        curTopExt = min(curTopExt, marcOuterTop)
                    }

                    stemGeoms[idx] = sg.copy(topExtremeY = curTopExt, bottomExtremeY = curBotExt)

                    if (hasFermata) {
                        outPendingFermatas += PendingFermataRender(
                            xCenter = sg.cx,
                            staffTopY = staffTopY,
                            useBassStaff = isBass,
                            minClearanceTopY = min(curTopExt, staffTopY - lineSpacing * 0.35f),
                            color = sg.color
                        )
                    }
                }
            }

            // 5. Render Classical Tuplet Numerals & Broken Brackets (`2`, `3`, `5`, `6`)
            val tupletSymbolGroups = staffSyms
                .filter { it.tuplet != null }
                .groupBy { it.tuplet!!.groupKey }

            for ((gKey, groupSyms) in tupletSymbolGroups) {
                val tag = groupSyms.first().tuplet ?: continue
                val numText = "${tag.count}"

                val fullBeam = renderedBeams.firstOrNull { rb ->
                    rb.chordIndices.size == tag.count &&
                        groupSyms.none { it.isRest } &&
                        rb.chordIndices.all { chords[it].tuplet?.groupKey == gKey }
                }

                if (fullBeam != null) {
                    val midX = (fullBeam.x1 + fullBeam.x2) * 0.5f
                    val midBeamY = (fullBeam.y1 + fullBeam.y2) * 0.5f
                    val textBaselineY = if (fullBeam.stemUp) {
                        midBeamY - lineSpacing * 0.45f
                    } else {
                        midBeamY + lineSpacing * 1.32f
                    }
                    drawTupletNumeralWithHalo(
                        canvas = canvas,
                        numText = numText,
                        centerX = midX,
                        baselineY = textBaselineY,
                        lineSpacing = lineSpacing,
                        paperBgColor = paperBgColor,
                        inkColor = fullBeam.color
                    )
                    val obsTop = if (fullBeam.stemUp) textBaselineY - lineSpacing * 1.15f else min(fullBeam.y1, fullBeam.y2)
                    val obsBot = if (fullBeam.stemUp) max(fullBeam.y1, fullBeam.y2) else textBaselineY + lineSpacing * 0.35f
                    outTupletObstacles += RenderedTupletObstacle(
                        useBassStaff = isBass,
                        x1 = fullBeam.x1,
                        x2 = fullBeam.x2,
                        topY = obsTop,
                        bottomY = obsBot
                    )
                    for (cIdx in fullBeam.chordIndices) {
                        val sg = stemGeoms[cIdx] ?: continue
                        stemGeoms[cIdx] = sg.copy(
                            topExtremeY = min(sg.topExtremeY, obsTop),
                            bottomExtremeY = max(sg.bottomExtremeY, obsBot)
                        )
                    }
                } else {
                    val barIdxForBracket = staffSyms.first().barIndex
                    val gStartInBar = (tag.groupStartTick - barIdxForBracket * ticksPerBar)
                        .coerceIn(0, (ticksPerBar - 1).coerceAtLeast(0))
                    val lastSlotInBar = min(
                        (ticksPerBar - tag.noteTicks).coerceAtLeast(0),
                        gStartInBar + (tag.count - 1) * tag.noteTicks
                    ).coerceAtLeast(gStartInBar)
                    val xLeft = noteOriginX + (gStartInBar / ticksPerBar.toFloat()) * usableMeasureWidth - headRx * 0.95f
                    val xRight = max(
                        xLeft + lineSpacing * 2.1f,
                        noteOriginX + (lastSlotInBar / ticksPerBar.toFloat()) * usableMeasureWidth + headRx * 1.15f
                    )

                    val groupChordIndices = chords.indices.filter { chords[it].tuplet?.groupKey == gKey }
                    val upCount = groupChordIndices.count { stemGeoms[it]?.stemUp == true }
                    val placeAbove = groupChordIndices.isEmpty() || upCount * 2 >= groupChordIndices.size

                    var minTopY = staffTopY - lineSpacing * 0.65f
                    var maxBotY = staffBottomY + lineSpacing * 0.65f
                    var bracketColor = slot0Color
                    for (cIdx in groupChordIndices) {
                        val sg = stemGeoms[cIdx] ?: continue
                        minTopY = min(minTopY, sg.topExtremeY)
                        maxBotY = max(maxBotY, sg.bottomExtremeY)
                        if (sg.color == selectedColor) bracketColor = selectedColor
                    }

                    val bracketY = if (placeAbove) {
                        minTopY - lineSpacing * 0.78f
                    } else {
                        maxBotY + lineSpacing * 0.82f
                    }
                    drawTupletBracket(
                        canvas = canvas,
                        x1 = xLeft,
                        x2 = xRight,
                        y = bracketY,
                        placeAbove = placeAbove,
                        numText = numText,
                        lineSpacing = lineSpacing,
                        paperBgColor = paperBgColor,
                        color = bracketColor
                    )
                    val obsTop = if (placeAbove) bracketY - lineSpacing * 0.65f else minTopY
                    val obsBot = if (!placeAbove) bracketY + lineSpacing * 0.65f else maxBotY
                    outTupletObstacles += RenderedTupletObstacle(
                        useBassStaff = isBass,
                        x1 = xLeft,
                        x2 = xRight,
                        topY = obsTop,
                        bottomY = obsBot
                    )
                    for (cIdx in groupChordIndices) {
                        val sg = stemGeoms[cIdx] ?: continue
                        stemGeoms[cIdx] = sg.copy(
                            topExtremeY = min(sg.topExtremeY, obsTop),
                            bottomExtremeY = max(sg.bottomExtremeY, obsBot)
                        )
                    }
                }
            }

            // 6. Export RenderedChordAnchors for System-Level Phrase Slurs & Lane Clearance
            for ((idx, chord) in chords.withIndex()) {
                val sg = stemGeoms[idx] ?: continue
                val absStart = chord.barIndex * ticksPerBar + chord.tickInBar
                val absEnd = absStart + chord.durTicks
                outChordAnchors += RenderedChordAnchor(
                    barIndex = chord.barIndex,
                    absStartTick = absStart,
                    absEndTick = absEnd,
                    useBassStaff = isBass,
                    sourceNoteIds = chord.notes.mapNotNull { it.sourceNoteId }.toSet(),
                    slurGroupIds = chord.notes.mapNotNull { it.slurGroupId }.toSet(),
                    xCenter = sg.cx,
                    stemUp = sg.stemUp,
                    topNoteY = sg.minNoteY,
                    bottomNoteY = sg.maxNoteY,
                    topExtremeY = sg.topExtremeY,
                    bottomExtremeY = sg.bottomExtremeY,
                    color = sg.color
                )
            }
        }
    }

    // ========================================================================
    // System-Level Multi-Note Phrase Slur (`圆滑线`) Renderer
    // ========================================================================

    private fun renderSystemPhraseSlurs(
        canvas: Canvas,
        allScoreNotes: List<MelodyScoreNote>,
        grandStaff: Boolean,
        chordAnchors: MutableList<RenderedChordAnchor>,
        tupletObstacles: List<RenderedTupletObstacle>,
        pendingFermatas: MutableList<PendingFermataRender>,
        measureGeoms: List<MeasureSpanGeom>,
        isContinuousTimeline: Boolean,
        ticksPerBar: Int,
        trebleTopY: Float,
        bassTopY: Float,
        lineSpacing: Float,
        defaultColor: Int,
        selectedColor: Int,
        selectedNoteId: String?
    ) {
        if (measureGeoms.isEmpty()) return
        val sysStartBar = measureGeoms.first().barIndex
        val sysEndBar = measureGeoms.last().barIndex
        val sysStartTick = sysStartBar * ticksPerBar
        val sysEndTick = (sysEndBar + 1) * ticksPerBar

        val slurredGroups = allScoreNotes
            .asSequence()
            .filter { !it.isRest && it.slurGroupId != null }
            .groupBy { it.slurGroupId!! to it.resolvedStaffPart(grandStaff) }

        for ((slurStaffKey, groupNotes) in slurredGroups) {
            val (slurId, staffPart) = slurStaffKey
            val distinctOnsets = groupNotes.map { it.resolvedStartTick }.distinct().sorted()
            if (distinctOnsets.size < 2) continue

            val phraseFirstTick = distinctOnsets.first()
            val phraseMaxEndTick = groupNotes.maxOf { it.resolvedEndTick }
            val phraseLastNoteStartTick = distinctOnsets.last()
            if (phraseMaxEndTick <= sysStartTick || phraseFirstTick >= sysEndTick) continue

            val groupNoteIds = groupNotes.map { it.id }.toSet()
            val onBass = grandStaff && (staffPart == StaffPart.BASS)
            val matchingAnchors = chordAnchors
                .filter { anchor ->
                    anchor.useBassStaff == onBass &&
                        (slurId in anchor.slurGroupIds || anchor.sourceNoteIds.any { it in groupNoteIds })
                }
                .sortedBy { it.absStartTick }

            val staffTopY = if (onBass) bassTopY else trebleTopY
            val staffAnchors = chordAnchors
                .filter { it.useBassStaff == onBass }
                .sortedBy { it.xCenter }
            val phraseStaffAnchors = matchingAnchors
                .sortedBy { it.xCenter }

            val continuedFromPrevSystem = !isContinuousTimeline && phraseFirstTick < sysStartTick
            val continuesToNextSystem = !isContinuousTimeline && (
                phraseLastNoteStartTick >= sysEndTick ||
                    groupNotes.any { it.resolvedStartTick == phraseLastNoteStartTick && it.resolvedEndTick > sysEndTick }
                )

            if (phraseStaffAnchors.isEmpty() && !continuedFromPrevSystem) continue

            val anySelected = selectedNoteId != null && selectedNoteId in groupNoteIds
            val slurColor = if (anySelected) selectedColor else defaultColor

            // Classical direction rule:
            // - All stems UP -> slur curves BELOW noteheads (`curveAbove = false`)
            // - All stems DOWN or MIXED stems -> slur curves ABOVE (`curveAbove = true`)
            val allStemsUp = phraseStaffAnchors.isNotEmpty() && phraseStaffAnchors.all { it.stemUp }
            val curveAbove = !allStemsUp

            val firstGeom = measureGeoms.first()
            val lastGeom = measureGeoms.last()

            val x1: Float
            var y1: Float
            if (continuedFromPrevSystem) {
                x1 = firstGeom.barLeftX + lineSpacing * 0.28f
                val refY = phraseStaffAnchors.firstOrNull()?.let {
                    if (curveAbove) it.topNoteY - lineSpacing * 0.55f else it.bottomNoteY + lineSpacing * 0.55f
                } ?: (staffTopY + if (curveAbove) lineSpacing * 0.4f else lineSpacing * 3.6f)
                y1 = refY
            } else {
                val aFirst = phraseStaffAnchors.first()
                x1 = aFirst.xCenter + lineSpacing * 0.12f
                y1 = if (curveAbove) {
                    val base = if (!aFirst.stemUp) aFirst.topNoteY else min(aFirst.topNoteY, aFirst.topExtremeY + lineSpacing * 0.9f)
                    base - lineSpacing * 0.42f
                } else {
                    aFirst.bottomNoteY + lineSpacing * 0.42f
                }
            }

            val x2: Float
            var y2: Float
            if (continuesToNextSystem) {
                x2 = lastGeom.barRightX - lineSpacing * 0.30f
                val refY = phraseStaffAnchors.lastOrNull()?.let {
                    if (curveAbove) it.topNoteY - lineSpacing * 0.55f else it.bottomNoteY + lineSpacing * 0.55f
                } ?: y1
                y2 = refY
            } else {
                val aLast = phraseStaffAnchors.last()
                x2 = aLast.xCenter - lineSpacing * 0.08f
                y2 = if (curveAbove) {
                    val base = if (!aLast.stemUp) aLast.topNoteY else min(aLast.topNoteY, aLast.topExtremeY + lineSpacing * 0.9f)
                    base - lineSpacing * 0.42f
                } else {
                    aLast.bottomNoteY + lineSpacing * 0.42f
                }
            }

            if (x2 <= x1 + lineSpacing * 0.8f) continue
            val spanW = x2 - x1

            // Obstacle avoidance across all intermediate chords, stems, articulations, and tuplet brackets!
            var requiredArchMag = min(lineSpacing * 2.15f, lineSpacing * 0.95f + spanW * 0.065f)
            val clearance = lineSpacing * 0.52f

            for (obs in staffAnchors) {
                if (obs.xCenter < x1 - lineSpacing * 0.3f || obs.xCenter > x2 + lineSpacing * 0.3f) continue
                val tRaw = (obs.xCenter - x1) / spanW
                // Endpoints themselves only check notehead/stem if interior or mixed-stem collision
                val isStrictInterior = tRaw in 0.08f..0.92f
                if (!isStrictInterior) continue

                val t = tRaw.coerceIn(0.10f, 0.90f)
                val chordLineY = y1 + (y2 - y1) * t
                val bezierBasis = (3f * t * (1f - t)).coerceAtLeast(0.34f)

                if (curveAbove) {
                    val targetY = obs.topExtremeY - clearance
                    // If obstacle is very close to start or end, gently lift that endpoint too so slope stays graceful
                    if (tRaw < 0.25f && targetY < y1) {
                        y1 = min(y1, targetY + lineSpacing * 0.45f)
                    }
                    if (tRaw > 0.75f && targetY < y2) {
                        y2 = min(y2, targetY + lineSpacing * 0.45f)
                    }
                    val updatedChordY = y1 + (y2 - y1) * t
                    val needed = updatedChordY - targetY
                    if (needed > 0f) {
                        requiredArchMag = max(requiredArchMag, needed / bezierBasis)
                    }
                } else {
                    val targetY = obs.bottomExtremeY + clearance
                    if (tRaw < 0.25f && targetY > y1) {
                        y1 = max(y1, targetY - lineSpacing * 0.45f)
                    }
                    if (tRaw > 0.75f && targetY > y2) {
                        y2 = max(y2, targetY - lineSpacing * 0.45f)
                    }
                    val updatedChordY = y1 + (y2 - y1) * t
                    val needed = targetY - updatedChordY
                    if (needed > 0f) {
                        requiredArchMag = max(requiredArchMag, needed / bezierBasis)
                    }
                }
            }

            for (tupObs in tupletObstacles) {
                if (tupObs.useBassStaff != onBass) continue
                if (tupObs.x2 < x1 || tupObs.x1 > x2) continue
                val sampleXs = floatArrayOf(
                    max(x1, tupObs.x1),
                    (max(x1, tupObs.x1) + min(x2, tupObs.x2)) * 0.5f,
                    min(x2, tupObs.x2)
                )
                for (sx in sampleXs) {
                    val t = ((sx - x1) / spanW).coerceIn(0.12f, 0.88f)
                    val chordLineY = y1 + (y2 - y1) * t
                    val basis = (3f * t * (1f - t)).coerceAtLeast(0.36f)
                    if (curveAbove) {
                        val needed = chordLineY - (tupObs.topY - clearance * 0.85f)
                        if (needed > 0f) requiredArchMag = max(requiredArchMag, needed / basis)
                    } else {
                        val needed = (tupObs.bottomY + clearance * 0.85f) - chordLineY
                        if (needed > 0f) requiredArchMag = max(requiredArchMag, needed / basis)
                    }
                }
            }

            val clampedArchMag = requiredArchMag.coerceIn(lineSpacing * 0.85f, lineSpacing * 4.4f)
            val dir = if (curveAbove) -1f else 1f
            val archDelta = dir * clampedArchMag
            val midThickness = max(1.4f, lineSpacing * 0.24f)

            val cp1x = x1 + spanW * 0.24f
            val cp2x = x1 + spanW * 0.76f
            val outerCp1y = y1 + (y2 - y1) * 0.24f + archDelta
            val outerCp2y = y1 + (y2 - y1) * 0.76f + archDelta
            val innerCp1y = outerCp1y - dir * midThickness
            val innerCp2y = outerCp2y - dir * midThickness

            val slurPath = Path().apply {
                moveTo(x1, y1)
                cubicTo(cp1x, outerCp1y, cp2x, outerCp2y, x2, y2)
                cubicTo(cp2x, innerCp2y, cp1x, innerCp1y, x1, y1)
                close()
            }
            val slurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = slurColor
                style = Paint.Style.FILL
            }
            canvas.drawPath(slurPath, slurPaint)

            // Update chord & Fermata clearances so Fermata & Tempo marks sit cleanly outside the slur arch!
            for (anchor in staffAnchors) {
                if (anchor.xCenter in (x1 - 2f)..(x2 + 2f)) {
                    val t = ((anchor.xCenter - x1) / spanW).coerceIn(0f, 1f)
                    val curveY = y1 + (y2 - y1) * t + dir * (3f * t * (1f - t) * clampedArchMag)
                    if (curveAbove) {
                        anchor.topExtremeY = min(anchor.topExtremeY, curveY - lineSpacing * 0.2f)
                    } else {
                        anchor.bottomExtremeY = max(anchor.bottomExtremeY, curveY + lineSpacing * 0.2f)
                    }
                }
            }
            if (curveAbove) {
                for (pf in pendingFermatas) {
                    if (pf.useBassStaff == onBass &&
                        pf.xCenter in (x1 - lineSpacing * 0.5f)..(x2 + lineSpacing * 0.5f)
                    ) {
                        val t = ((pf.xCenter - x1) / spanW).coerceIn(0f, 1f)
                        val curveY = y1 + (y2 - y1) * t - (3f * t * (1f - t) * clampedArchMag)
                        pf.minClearanceTopY = min(pf.minClearanceTopY, curveY - lineSpacing * 0.25f)
                    }
                }
            }
        }
    }

    private fun renderPendingFermatas(
        canvas: Canvas,
        pendingFermatas: List<PendingFermataRender>,
        lineSpacing: Float
    ) {
        for (pf in pendingFermatas) {
            val baseBottomY = min(pf.minClearanceTopY, pf.staffTopY - lineSpacing * 0.35f) - lineSpacing * 0.35f
            val newTop = drawFermataGlyph(
                canvas = canvas,
                cx = pf.xCenter,
                baseY = baseBottomY,
                lineSpacing = lineSpacing,
                color = pf.color
            )
            pf.minClearanceTopY = newTop
        }
    }

    // ========================================================================
    // System-Level Dynamics (`pp..ff`, `sfz`, `fp`) & Hairpins (`<`, `>`)
    // ========================================================================

    private fun renderSystemDynamicsAndHairpins(
        canvas: Canvas,
        dynamicMarks: List<ScoreDynamicMark>,
        hairpins: List<ScoreHairpin>,
        sysStartTick: Int,
        sysEndTick: Int,
        isContinuousTimeline: Boolean,
        sysLeftContinuationX: Float,
        sysRightContinuationX: Float,
        tickToX: (Int) -> Float,
        chordAnchors: List<RenderedChordAnchor>,
        bottomStaffBottomY: Float,
        viewportMaxBottomY: Float,
        lineSpacing: Float,
        inkColor: Int,
        selectedColor: Int,
        selectedAnnotation: SelectedScoreAnnotation?,
        outHitTargets: MutableList<StaffHitTarget>?
    ) {
        if (dynamicMarks.isEmpty() && hairpins.isEmpty()) return

        // Compute clean horizontal lane Y below low notes/stems/slurs
        var maxBelowY = bottomStaffBottomY + lineSpacing * 1.45f
        for (anchor in chordAnchors) {
            maxBelowY = max(maxBelowY, anchor.bottomExtremeY + lineSpacing * 1.05f)
        }
        val laneCenterY = min(viewportMaxBottomY - lineSpacing * 0.45f, maxBelowY)
        val textBaselineY = laneCenterY + lineSpacing * 0.42f

        val dynTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = max(10.5f, lineSpacing * 1.55f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            textAlign = Paint.Align.CENTER
        }

        // Record X intervals occupied by dynamic text in this system so hairpins trim neatly around them
        data class TextSpanX(val tick: Int, val leftX: Float, val rightX: Float)
        val drawnTextSpans = mutableListOf<TextSpanX>()

        for (mark in dynamicMarks) {
            if (!isContinuousTimeline && (mark.tick < sysStartTick || mark.tick >= sysEndTick)) continue
            if (isContinuousTimeline && (mark.tick < sysStartTick || mark.tick > sysEndTick)) continue

            val x = tickToX(mark.tick)
            val isSel = (selectedAnnotation as? SelectedScoreAnnotation.Dynamic)?.id == mark.id
            dynTextPaint.color = if (isSel) selectedColor else inkColor
            val text = mark.level.marking
            val halfW = dynTextPaint.measureText(text) * 0.5f + lineSpacing * 0.35f

            val hitRect = RectF(
                x - max(halfW, lineSpacing * 1.1f),
                laneCenterY - lineSpacing * 1.05f,
                x + max(halfW, lineSpacing * 1.1f),
                laneCenterY + lineSpacing * 1.05f
            )
            if (isSel) {
                val selBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(40, Color.red(selectedColor), Color.green(selectedColor), Color.blue(selectedColor))
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(hitRect, lineSpacing * 0.35f, lineSpacing * 0.35f, selBg)
            }

            canvas.drawText(text, x, textBaselineY, dynTextPaint)
            drawnTextSpans += TextSpanX(mark.tick, x - halfW, x + halfW)

            outHitTargets?.add(
                StaffHitTarget(
                    annotation = SelectedScoreAnnotation.Dynamic(mark.id),
                    bounds = hitRect,
                    startTick = mark.tick
                )
            )
        }

        // Render `<` Crescendo and `>` Diminuendo Hairpin Wedges
        val wedgeHalfMouth = lineSpacing * 0.56f
        val hairpinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.1f, lineSpacing * 0.145f)
            strokeCap = Paint.Cap.ROUND
        }

        for (hp in hairpins) {
            val h0 = hp.safeStartTick
            val h1 = hp.safeEndTick
            if (h1 <= sysStartTick || h0 >= sysEndTick) continue

            val contLeft = !isContinuousTimeline && h0 < sysStartTick
            val contRight = !isContinuousTimeline && h1 > sysEndTick

            var xStart = if (contLeft) sysLeftContinuationX else tickToX(h0)
            var xEnd = if (contRight) sysRightContinuationX else tickToX(h1)

            // Trim away from any dynamic mark sitting right at `h0` or `h1`
            if (!contLeft) {
                drawnTextSpans.firstOrNull { abs(it.tick - h0) <= 15 }?.let { span ->
                    xStart = max(xStart, span.rightX + lineSpacing * 0.25f)
                }
            }
            if (!contRight) {
                drawnTextSpans.firstOrNull { abs(it.tick - h1) <= 15 }?.let { span ->
                    xEnd = min(xEnd, span.leftX - lineSpacing * 0.25f)
                }
            }
            if (xEnd <= xStart + lineSpacing * 0.8f) continue

            val isSel = (selectedAnnotation as? SelectedScoreAnnotation.Hairpin)?.id == hp.id
            hairpinPaint.color = if (isSel) selectedColor else inkColor

            val hitRect = RectF(
                xStart - lineSpacing * 0.3f,
                laneCenterY - wedgeHalfMouth - lineSpacing * 0.45f,
                xEnd + lineSpacing * 0.3f,
                laneCenterY + wedgeHalfMouth + lineSpacing * 0.45f
            )
            if (isSel) {
                val selBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(36, Color.red(selectedColor), Color.green(selectedColor), Color.blue(selectedColor))
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(hitRect, lineSpacing * 0.35f, lineSpacing * 0.35f, selBg)
            }

            if (hp.type == HairpinType.CRESCENDO) {
                val leftOpen = if (contLeft) wedgeHalfMouth * 0.38f else 0f
                val rightOpen = if (contRight) wedgeHalfMouth * 0.78f else wedgeHalfMouth
                canvas.drawLine(xStart, laneCenterY - leftOpen, xEnd, laneCenterY - rightOpen, hairpinPaint)
                canvas.drawLine(xStart, laneCenterY + leftOpen, xEnd, laneCenterY + rightOpen, hairpinPaint)
            } else {
                val leftOpen = if (contLeft) wedgeHalfMouth * 0.78f else wedgeHalfMouth
                val rightOpen = if (contRight) wedgeHalfMouth * 0.38f else 0f
                canvas.drawLine(xStart, laneCenterY - leftOpen, xEnd, laneCenterY - rightOpen, hairpinPaint)
                canvas.drawLine(xStart, laneCenterY + leftOpen, xEnd, laneCenterY + rightOpen, hairpinPaint)
            }

            outHitTargets?.add(
                StaffHitTarget(
                    annotation = SelectedScoreAnnotation.Hairpin(hp.id),
                    bounds = hitRect,
                    startTick = max(h0, sysStartTick)
                )
            )
        }
    }

    // ========================================================================
    // System-Level Above-Staff Letter Chord Symbols (`Cmaj7`, `G/B`, `F#m7-5`)
    // ========================================================================

    private data class ChordDisplayParts(
        val root: String,
        val quality: String,
        val slashBass: String
    )

    private fun splitChordDisplayParts(rawSymbol: String): ChordDisplayParts {
        val clean = rawSymbol.trim()
            .replace("♯", "#")
            .replace("♭", "b")
        if (clean.isEmpty()) return ChordDisplayParts("", "", "")
        val first = clean[0]
        if (first !in 'A'..'G' && first !in 'a'..'g') {
            return ChordDisplayParts(clean, "", "")
        }
        var rootEnd = 1
        while (rootEnd < clean.length && (clean[rootEnd] == '#' || clean[rootEnd] == 'b')) {
            rootEnd++
        }
        val root = clean[0].uppercaseChar() + clean.substring(1, rootEnd)
        val rest = clean.substring(rootEnd)

        val lastSlash = rest.lastIndexOf('/')
        if (lastSlash >= 0) {
            val before = rest.substring(0, lastSlash)
            val after = rest.substring(lastSlash + 1)
            val isSixNine = after == "9" && (before.endsWith("6") || before.endsWith("m6"))
            if (!isSixNine && after.isNotEmpty() && (after[0] in 'A'..'G' || after[0] in 'a'..'g')) {
                val normBass = after[0].uppercaseChar() + after.substring(1)
                return ChordDisplayParts(root, before, "/$normBass")
            }
        }
        return ChordDisplayParts(root, rest, "")
    }

    private fun renderSystemChordSymbols(
        canvas: Canvas,
        chordSymbols: List<ScoreChordSymbol>,
        sysStartTick: Int,
        sysEndTick: Int,
        isContinuousTimeline: Boolean,
        tickToX: (Int) -> Float,
        trebleTopY: Float,
        hasModulationPills: Boolean,
        chordAnchors: List<RenderedChordAnchor>,
        pendingFermatas: List<PendingFermataRender>,
        viewportMinTopY: Float,
        lineSpacing: Float,
        inkColor: Int,
        selectedColor: Int,
        selectedAnnotation: SelectedScoreAnnotation?,
        outHitTargets: MutableList<StaffHitTarget>?
    ): Float? {
        val visible = chordSymbols.filter { cs ->
            val t = cs.safeTick
            if (isContinuousTimeline) t in sysStartTick..sysEndTick else (t >= sysStartTick && t < sysEndTick)
        }.sortedBy { it.safeTick }
        if (visible.isEmpty()) return null

        var minAboveY = trebleTopY - lineSpacing * (if (hasModulationPills) 2.68f else 2.18f)
        for (anchor in chordAnchors) {
            if (!anchor.useBassStaff) {
                minAboveY = min(minAboveY, anchor.topExtremeY - lineSpacing * 0.72f)
            }
        }
        for (pf in pendingFermatas) {
            if (!pf.useBassStaff) {
                minAboveY = min(minAboveY, pf.minClearanceTopY - lineSpacing * 0.58f)
            }
        }
        val baselineY = max(viewportMinTopY, minAboveY)

        val rootTextSize = max(10.2f, lineSpacing * 1.34f)
        val qualTextSize = max(8.4f, lineSpacing * 1.04f)
        val slashTextSize = max(9.4f, lineSpacing * 1.20f)

        val rootPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = rootTextSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val qualPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = qualTextSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = slashTextSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }

        val defaultChordInk = if (outHitTargets == null) {
            // PDF crisp dark ink
            inkColor
        } else {
            // Warm studio teal-slate accent so chord symbols stand out legibly from bar numbers & tempo text
            Color.rgb(26, 92, 108)
        }

        var lastRightX = -10_000f
        for (cs in visible) {
            val parts = splitChordDisplayParts(cs.symbol)
            if (parts.root.isEmpty()) continue

            val isSel = (selectedAnnotation as? SelectedScoreAnnotation.ChordSymbol)?.id == cs.id
            val drawColor = if (isSel) selectedColor else defaultChordInk
            rootPaint.color = drawColor
            qualPaint.color = drawColor
            slashPaint.color = drawColor

            val rootW = rootPaint.measureText(parts.root)
            val qualW = if (parts.quality.isNotEmpty()) qualPaint.measureText(parts.quality) else 0f
            val slashW = if (parts.slashBass.isNotEmpty()) slashPaint.measureText(parts.slashBass) else 0f
            val totalW = rootW + qualW + slashW

            val anchorX = tickToX(cs.safeTick)
            val rawLeftX = anchorX - min(rootW * 0.36f, lineSpacing * 0.45f)
            val drawLeftX = max(rawLeftX, lastRightX + lineSpacing * 0.36f)
            val drawRightX = drawLeftX + totalW

            val padX = lineSpacing * 0.38f
            val hitHalfExtra = max(0f, (lineSpacing * 2.1f - (totalW + padX * 2f)) * 0.5f)
            val pillRect = RectF(
                drawLeftX - padX,
                baselineY - rootTextSize * 1.02f,
                drawRightX + padX,
                baselineY + lineSpacing * 0.34f
            )
            val hitRect = RectF(
                pillRect.left - hitHalfExtra,
                pillRect.top - lineSpacing * 0.22f,
                pillRect.right + hitHalfExtra,
                pillRect.bottom + lineSpacing * 0.25f
            )

            if (isSel) {
                drawSelectionPillBg(canvas, pillRect, lineSpacing, selectedColor)
            } else if (outHitTargets != null) {
                // Very subtle warm-cream backing pill on interactive screen so chords pop cleanly and invite tapping
                val subtleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(30, 26, 92, 108)
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(pillRect, lineSpacing * 0.32f, lineSpacing * 0.32f, subtleBg)
            }

            var cx = drawLeftX
            canvas.drawText(parts.root, cx, baselineY, rootPaint)
            cx += rootW
            if (parts.quality.isNotEmpty()) {
                canvas.drawText(parts.quality, cx, baselineY - lineSpacing * 0.08f, qualPaint)
                cx += qualW
            }
            if (parts.slashBass.isNotEmpty()) {
                canvas.drawText(parts.slashBass, cx, baselineY, slashPaint)
            }

            lastRightX = drawRightX + lineSpacing * 0.15f
            outHitTargets?.add(
                StaffHitTarget(
                    annotation = SelectedScoreAnnotation.ChordSymbol(cs.id),
                    bounds = hitRect,
                    startTick = cs.safeTick
                )
            )
        }

        return baselineY - rootTextSize * 1.08f
    }

    // ========================================================================
    // System-Level Tempo Map Marks (`Allegro ♩=xx`, `rit.`, `accel.`, `a tempo`)
    // ========================================================================

    private fun renderSystemTempoEvents(
        canvas: Canvas,
        tempoEvents: List<ScoreTempoEvent>,
        sysStartTick: Int,
        sysEndTick: Int,
        isContinuousTimeline: Boolean,
        sysLeftContinuationX: Float,
        sysRightContinuationX: Float,
        tickToX: (Int) -> Float,
        trebleTopY: Float,
        chordAnchors: List<RenderedChordAnchor>,
        pendingFermatas: List<PendingFermataRender>,
        extraTopClearanceY: Float? = null,
        viewportMinTopY: Float,
        lineSpacing: Float,
        inkColor: Int,
        selectedColor: Int,
        selectedAnnotation: SelectedScoreAnnotation?,
        outHitTargets: MutableList<StaffHitTarget>?
    ) {
        if (tempoEvents.isEmpty()) return

        var minAboveY = trebleTopY - lineSpacing * 1.95f
        for (anchor in chordAnchors) {
            if (!anchor.useBassStaff) {
                minAboveY = min(minAboveY, anchor.topExtremeY - lineSpacing * 0.85f)
            }
        }
        for (pf in pendingFermatas) {
            if (!pf.useBassStaff) {
                minAboveY = min(minAboveY, pf.minClearanceTopY - lineSpacing * 0.65f)
            }
        }
        if (extraTopClearanceY != null) {
            minAboveY = min(minAboveY, extraTopClearanceY - lineSpacing * 0.52f)
        }
        val baselineY = max(viewportMinTopY, minAboveY)

        val boldRomanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = max(9.5f, lineSpacing * 1.28f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val boldItalicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = max(9.5f, lineSpacing * 1.28f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            textAlign = Paint.Align.LEFT
        }

        for (ev in tempoEvents) {
            val t0 = ev.safeStartTick
            val t1 = ev.safeEndTick
            val isSpan = ev.kind == TempoEventKind.RITARDANDO || ev.kind == TempoEventKind.ACCELERANDO

            if (!isSpan) {
                if (!isContinuousTimeline && (t0 < sysStartTick || t0 >= sysEndTick)) continue
                if (isContinuousTimeline && (t0 < sysStartTick || t0 > sysEndTick)) continue
            } else {
                if (t1 <= sysStartTick || t0 >= sysEndTick) continue
            }

            val isSel = (selectedAnnotation as? SelectedScoreAnnotation.Tempo)?.id == ev.id
            val drawColor = if (isSel) selectedColor else inkColor
            boldRomanPaint.color = drawColor
            boldItalicPaint.color = drawColor

            when (ev.kind) {
                TempoEventKind.STEADY_BPM -> {
                    val x0 = tickToX(t0)
                    var curX = x0
                    if (ev.term != null) {
                        val termStr = "${ev.term.italianName} "
                        canvas.drawText(termStr, curX, baselineY, boldRomanPaint)
                        curX += boldRomanPaint.measureText(termStr)
                    }
                    // Pure vector miniature quarter-note glyph `♩` + `= bpm` (zero Unicode tofu risk!)
                    val noteGlyphW = drawMiniQuarterNoteInline(
                        canvas = canvas,
                        leftX = curX,
                        baselineY = baselineY,
                        height = boldRomanPaint.textSize * 0.96f,
                        color = drawColor
                    )
                    curX += noteGlyphW + lineSpacing * 0.22f
                    val eqBpmStr = "= ${ev.bpm.coerceIn(36, 240)}"
                    val numPaint = Paint(boldRomanPaint).apply {
                        textSize = boldRomanPaint.textSize * 0.90f
                        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                    }
                    canvas.drawText(eqBpmStr, curX, baselineY, numPaint)
                    val endX = curX + numPaint.measureText(eqBpmStr)

                    val hitRect = RectF(
                        x0 - lineSpacing * 0.35f,
                        baselineY - boldRomanPaint.textSize * 1.05f,
                        endX + lineSpacing * 0.35f,
                        baselineY + lineSpacing * 0.40f
                    )
                    if (isSel) {
                        drawSelectionPillBg(canvas, hitRect, lineSpacing, selectedColor)
                    }
                    outHitTargets?.add(
                        StaffHitTarget(
                            annotation = SelectedScoreAnnotation.Tempo(ev.id),
                            bounds = hitRect,
                            startTick = t0
                        )
                    )
                }

                TempoEventKind.A_TEMPO -> {
                    val x0 = tickToX(t0)
                    val label = "a tempo"
                    canvas.drawText(label, x0, baselineY, boldItalicPaint)
                    val w = boldItalicPaint.measureText(label)
                    val hitRect = RectF(
                        x0 - lineSpacing * 0.35f,
                        baselineY - boldItalicPaint.textSize * 1.05f,
                        x0 + w + lineSpacing * 0.35f,
                        baselineY + lineSpacing * 0.40f
                    )
                    if (isSel) {
                        drawSelectionPillBg(canvas, hitRect, lineSpacing, selectedColor)
                    }
                    outHitTargets?.add(
                        StaffHitTarget(
                            annotation = SelectedScoreAnnotation.Tempo(ev.id),
                            bounds = hitRect,
                            startTick = t0
                        )
                    )
                }

                TempoEventKind.RITARDANDO, TempoEventKind.ACCELERANDO -> {
                    val contLeft = !isContinuousTimeline && t0 < sysStartTick
                    val contRight = !isContinuousTimeline && t1 > sysEndTick
                    val xStart = if (contLeft) sysLeftContinuationX else tickToX(t0)
                    val xEnd = if (contRight) sysRightContinuationX else tickToX(t1)

                    val baseWord = if (ev.kind == TempoEventKind.RITARDANDO) "rit." else "accel."
                    val word = if (contLeft) "($baseWord)" else baseWord
                    canvas.drawText(word, xStart, baselineY, boldItalicPaint)
                    val wordW = boldItalicPaint.measureText(word)

                    val dashStartX = xStart + wordW + lineSpacing * 0.45f
                    val lineY = baselineY - boldItalicPaint.textSize * 0.28f
                    if (xEnd > dashStartX + lineSpacing * 0.8f) {
                        val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = drawColor
                            style = Paint.Style.STROKE
                            strokeWidth = max(1.0f, lineSpacing * 0.125f)
                            pathEffect = DashPathEffect(
                                floatArrayOf(lineSpacing * 0.62f, lineSpacing * 0.50f),
                                0f
                            )
                        }
                        canvas.drawLine(dashStartX, lineY, xEnd, lineY, dashPaint)
                        if (!contRight) {
                            val hookPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = drawColor
                                style = Paint.Style.STROKE
                                strokeWidth = max(1.0f, lineSpacing * 0.125f)
                            }
                            canvas.drawLine(xEnd, lineY, xEnd, lineY + lineSpacing * 0.48f, hookPaint)
                        }
                    }

                    val hitRect = RectF(
                        xStart - lineSpacing * 0.35f,
                        baselineY - boldItalicPaint.textSize * 1.05f,
                        max(xStart + wordW, xEnd) + lineSpacing * 0.35f,
                        baselineY + lineSpacing * 0.42f
                    )
                    if (isSel) {
                        drawSelectionPillBg(canvas, hitRect, lineSpacing, selectedColor)
                    }
                    outHitTargets?.add(
                        StaffHitTarget(
                            annotation = SelectedScoreAnnotation.Tempo(ev.id),
                            bounds = hitRect,
                            startTick = max(t0, sysStartTick)
                        )
                    )
                }
            }
        }
    }

    private fun drawSelectionPillBg(canvas: Canvas, rect: RectF, lineSpacing: Float, selectedColor: Int) {
        val selBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, Color.red(selectedColor), Color.green(selectedColor), Color.blue(selectedColor))
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, lineSpacing * 0.35f, lineSpacing * 0.35f, selBg)
    }

    // ========================================================================
    // Pure Vector Articulations, Accents, Fermata & Inline Note Primitives
    // ========================================================================

    /**
     * Snaps a close-to-note articulation Y coordinate into a clean staff space if it would otherwise
     * collide with one of the 5 horizontal staff lines.
     */
    private fun snapClearOfStaffLines(
        rawY: Float,
        placeAbove: Boolean,
        staffTopY: Float,
        staffBottomY: Float,
        lineSpacing: Float
    ): Float {
        if (rawY < staffTopY - lineSpacing * 0.30f || rawY > staffBottomY + lineSpacing * 0.30f) {
            return rawY
        }
        val relInSpaces = (rawY - staffTopY) / lineSpacing
        val nearestLineIdx = relInSpaces.roundToInt().coerceIn(0, 4)
        val distToLine = abs(relInSpaces - nearestLineIdx)
        return if (distToLine < 0.28f) {
            val shiftedSpace = if (placeAbove) (nearestLineIdx - 0.5f) else (nearestLineIdx + 0.5f)
            staffTopY + shiftedSpace * lineSpacing
        } else {
            rawY
        }
    }

    private fun drawNoteArticulationGlyph(
        canvas: Canvas,
        cx: Float,
        noteBoundaryY: Float,
        placeAbove: Boolean,
        staffTopY: Float,
        staffBottomY: Float,
        lineSpacing: Float,
        articulation: NoteArticulation,
        color: Int
    ): Float {
        val dir = if (placeAbove) -1f else 1f
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.3f, lineSpacing * 0.19f)
            strokeCap = Paint.Cap.ROUND
        }

        return when (articulation) {
            NoteArticulation.STACCATO -> {
                val rawY = noteBoundaryY + dir * lineSpacing * 0.58f
                val dotY = snapClearOfStaffLines(rawY, placeAbove, staffTopY, staffBottomY, lineSpacing)
                val r = max(1.5f, lineSpacing * 0.21f)
                canvas.drawCircle(cx, dotY, r, fillPaint)
                dotY + dir * (r + lineSpacing * 0.15f)
            }
            NoteArticulation.STACCATISSIMO -> {
                // Pointed wedge aiming toward the notehead
                val baseNearY = snapClearOfStaffLines(
                    noteBoundaryY + dir * lineSpacing * 0.45f,
                    placeAbove,
                    staffTopY,
                    staffBottomY,
                    lineSpacing
                )
                val tipNearNoteY = baseNearY
                val wideFarY = tipNearNoteY + dir * lineSpacing * 0.78f
                val halfW = lineSpacing * 0.25f
                val wedge = Path().apply {
                    moveTo(cx, tipNearNoteY)
                    lineTo(cx - halfW, wideFarY)
                    lineTo(cx + halfW, wideFarY)
                    close()
                }
                canvas.drawPath(wedge, fillPaint)
                wideFarY + dir * lineSpacing * 0.16f
            }
            NoteArticulation.TENUTO -> {
                val rawY = noteBoundaryY + dir * lineSpacing * 0.56f
                val barY = snapClearOfStaffLines(rawY, placeAbove, staffTopY, staffBottomY, lineSpacing)
                val halfW = lineSpacing * 0.62f
                canvas.drawLine(cx - halfW, barY, cx + halfW, barY, strokePaint)
                barY + dir * lineSpacing * 0.25f
            }
            NoteArticulation.PORTATO -> {
                // Staccato dot closest to notehead + Tenuto bar outside it
                val rawDotY = noteBoundaryY + dir * lineSpacing * 0.52f
                val dotY = snapClearOfStaffLines(rawDotY, placeAbove, staffTopY, staffBottomY, lineSpacing)
                val r = max(1.4f, lineSpacing * 0.19f)
                canvas.drawCircle(cx, dotY, r, fillPaint)

                val rawBarY = dotY + dir * lineSpacing * 0.56f
                val barY = snapClearOfStaffLines(rawBarY, placeAbove, staffTopY, staffBottomY, lineSpacing)
                val halfW = lineSpacing * 0.62f
                canvas.drawLine(cx - halfW, barY, cx + halfW, barY, strokePaint)
                barY + dir * lineSpacing * 0.25f
            }
        }
    }

    private fun drawAccentChevron(
        canvas: Canvas,
        cx: Float,
        refY: Float,
        placeAbove: Boolean,
        lineSpacing: Float,
        color: Int
    ): Float {
        val dir = if (placeAbove) -1f else 1f
        val centerY = refY + dir * lineSpacing * 0.62f
        val halfW = lineSpacing * 0.68f
        val halfH = lineSpacing * 0.36f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.2f, lineSpacing * 0.16f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val p = Path().apply {
            moveTo(cx - halfW, centerY - halfH)
            lineTo(cx + halfW, centerY)
            lineTo(cx - halfW, centerY + halfH)
        }
        canvas.drawPath(p, paint)
        return centerY + dir * (halfH + lineSpacing * 0.18f)
    }

    private fun drawMarcatoWedge(
        canvas: Canvas,
        cx: Float,
        refTopY: Float,
        lineSpacing: Float,
        color: Int
    ): Float {
        val baseBottomY = refTopY - lineSpacing * 0.32f
        val apexTopY = baseBottomY - lineSpacing * 0.85f
        val halfW = lineSpacing * 0.48f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.35f, lineSpacing * 0.185f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.MITER
        }
        val p = Path().apply {
            moveTo(cx - halfW, baseBottomY)
            lineTo(cx, apexTopY)
            lineTo(cx + halfW, baseBottomY)
        }
        canvas.drawPath(p, paint)
        return apexTopY - lineSpacing * 0.18f
    }

    private fun drawFermataGlyph(
        canvas: Canvas,
        cx: Float,
        baseY: Float,
        lineSpacing: Float,
        color: Int
    ): Float {
        val halfW = lineSpacing * 0.92f
        val archH = lineSpacing * 0.92f
        val thick = max(1.3f, lineSpacing * 0.24f)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val crescent = Path().apply {
            moveTo(cx - halfW, baseY)
            cubicTo(
                cx - halfW * 0.65f, baseY - archH,
                cx + halfW * 0.65f, baseY - archH,
                cx + halfW, baseY
            )
            cubicTo(
                cx + halfW * 0.58f, baseY - archH + thick,
                cx - halfW * 0.58f, baseY - archH + thick,
                cx - halfW, baseY
            )
            close()
        }
        canvas.drawPath(crescent, fillPaint)
        canvas.drawCircle(cx, baseY - archH * 0.20f, max(1.4f, lineSpacing * 0.20f), fillPaint)
        return baseY - archH - lineSpacing * 0.20f
    }

    private fun drawMiniQuarterNoteInline(
        canvas: Canvas,
        leftX: Float,
        baselineY: Float,
        height: Float,
        color: Int
    ): Float {
        val rx = height * 0.25f
        val ry = height * 0.18f
        val cx = leftX + rx * 1.05f
        val cy = baselineY - ry * 0.65f
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.0f, height * 0.11f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(-22f)
        canvas.drawOval(RectF(-rx, -ry, rx, ry), fillPaint)
        canvas.restore()

        val stemX = cx + rx * 0.85f
        canvas.drawLine(stemX, cy, stemX, cy - height * 0.78f, stemPaint)
        return rx * 2.15f
    }

    private fun drawTupletNumeralWithHalo(
        canvas: Canvas,
        numText: String,
        centerX: Float,
        baselineY: Float,
        lineSpacing: Float,
        paperBgColor: Int,
        inkColor: Int
    ) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = max(9.5f, lineSpacing * 1.28f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            textAlign = Paint.Align.CENTER
        }
        val textW = textPaint.measureText(numText)
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paperBgColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(
                centerX - textW * 0.68f,
                baselineY - textPaint.textSize * 0.86f,
                centerX + textW * 0.68f,
                baselineY + textPaint.textSize * 0.16f
            ),
            lineSpacing * 0.25f,
            lineSpacing * 0.25f,
            haloPaint
        )
        canvas.drawText(numText, centerX, baselineY, textPaint)
    }

    private fun drawTupletBracket(
        canvas: Canvas,
        x1: Float,
        x2: Float,
        y: Float,
        placeAbove: Boolean,
        numText: String,
        lineSpacing: Float,
        paperBgColor: Int,
        color: Int
    ) {
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.0f, lineSpacing * 0.125f)
            strokeCap = Paint.Cap.SQUARE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = max(9.5f, lineSpacing * 1.26f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            textAlign = Paint.Align.CENTER
        }

        val midX = (x1 + x2) * 0.5f
        val gapHalf = max(lineSpacing * 0.72f, textPaint.measureText(numText) * 0.75f)
        val hookDir = if (placeAbove) 1f else -1f
        val hookLen = lineSpacing * 0.56f

        canvas.drawLine(x1, y, x1, y + hookDir * hookLen, linePaint)
        if (midX - gapHalf > x1 + 1.5f) {
            canvas.drawLine(x1, y, midX - gapHalf, y, linePaint)
        }
        if (x2 > midX + gapHalf + 1.5f) {
            canvas.drawLine(midX + gapHalf, y, x2, y, linePaint)
        }
        canvas.drawLine(x2, y, x2, y + hookDir * hookLen, linePaint)

        val baselineY = y + textPaint.textSize * 0.36f
        drawTupletNumeralWithHalo(
            canvas = canvas,
            numText = numText,
            centerX = midX,
            baselineY = baselineY,
            lineSpacing = lineSpacing,
            paperBgColor = paperBgColor,
            inkColor = color
        )
    }

    // ========================================================================
    // Low-Level Vector Musical Primitives (Zero Font Dependency!)
    // ========================================================================

    private fun stepToY(
        diatonicStep: Int,
        refTopStep: Int,
        staffTopY: Float,
        lineSpacing: Float
    ): Float = staffTopY + (refTopStep - diatonicStep) * (lineSpacing * 0.5f)

    private fun drawFiveLines(
        canvas: Canvas,
        leftX: Float,
        rightX: Float,
        topY: Float,
        lineSpacing: Float,
        color: Int
    ) {
        if (rightX <= leftX) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = max(0.85f, lineSpacing * 0.105f)
        }
        for (i in 0..4) {
            val y = topY + i * lineSpacing
            canvas.drawLine(leftX, y, rightX, y, paint)
        }
    }

    private fun drawTrebleClef(
        canvas: Canvas,
        centerX: Float,
        staffTopY: Float,
        s: Float,
        color: Int
    ) {
        val gLineY = staffTopY + 3f * s
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.3f, s * 0.22f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        val path = Path().apply {
            moveTo(centerX + 0.16f * s, gLineY - 0.18f * s)
            cubicTo(
                centerX + 0.65f * s, gLineY - 0.72f * s,
                centerX + 0.85f * s, gLineY + 0.15f * s,
                centerX + 0.22f * s, staffTopY + 3.92f * s
            )
            cubicTo(
                centerX - 0.62f * s, staffTopY + 4.15f * s,
                centerX - 0.92f * s, gLineY - 0.15f * s,
                centerX - 0.12f * s, staffTopY + 1.85f * s
            )
            cubicTo(
                centerX + 0.72f * s, staffTopY + 0.65f * s,
                centerX + 0.62f * s, staffTopY - 1.15f * s,
                centerX + 0.05f * s, staffTopY - 1.25f * s
            )
            cubicTo(
                centerX - 0.28f * s, staffTopY - 1.25f * s,
                centerX - 0.12f * s, staffTopY + 0.45f * s,
                centerX + 0.08f * s, staffTopY + 4.92f * s
            )
            cubicTo(
                centerX + 0.12f * s, staffTopY + 5.48f * s,
                centerX - 0.48f * s, staffTopY + 5.52f * s,
                centerX - 0.56f * s, staffTopY + 5.05f * s
            )
        }
        canvas.drawPath(path, strokePaint)
        canvas.drawCircle(centerX - 0.46f * s, staffTopY + 4.98f * s, s * 0.26f, fillPaint)
    }

    private fun drawBassClef(
        canvas: Canvas,
        centerX: Float,
        staffTopY: Float,
        s: Float,
        color: Int
    ) {
        val fLineY = staffTopY + 1f * s
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.4f, s * 0.24f)
            strokeCap = Paint.Cap.ROUND
        }

        canvas.drawCircle(centerX - 0.45f * s, fLineY, s * 0.32f, fillPaint)

        val arch = Path().apply {
            moveTo(centerX - 0.45f * s, fLineY)
            cubicTo(
                centerX - 0.50f * s, staffTopY + 0.05f * s,
                centerX + 0.62f * s, staffTopY + 0.02f * s,
                centerX + 0.58f * s, staffTopY + 1.35f * s
            )
            cubicTo(
                centerX + 0.54f * s, staffTopY + 2.55f * s,
                centerX - 0.15f * s, staffTopY + 3.35f * s,
                centerX - 0.68f * s, staffTopY + 3.68f * s
            )
        }
        canvas.drawPath(arch, strokePaint)

        val dotR = max(1.2f, s * 0.17f)
        canvas.drawCircle(centerX + 0.96f * s, staffTopY + 0.52f * s, dotR, fillPaint)
        canvas.drawCircle(centerX + 0.96f * s, staffTopY + 1.48f * s, dotR, fillPaint)
    }

    private fun drawTimeSignature(
        canvas: Canvas,
        centerX: Float,
        staffTopY: Float,
        s: Float,
        numerator: Int,
        denominator: Int,
        color: Int
    ) {
        val numText = numerator.coerceIn(1, 32).toString()
        val denText = denominator.coerceIn(1, 32).toString()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = s * 2.15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        paint.textScaleX = if (numText.length >= 2) 0.80f else 1.0f
        canvas.drawText(numText, centerX, staffTopY + 1.85f * s, paint)
        paint.textScaleX = if (denText.length >= 2) 0.80f else 1.0f
        canvas.drawText(denText, centerX, staffTopY + 3.85f * s, paint)
    }

    private fun drawNotehead(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        hollow: Boolean,
        isWhole: Boolean,
        color: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.save()
        canvas.translate(cx, cy)
        if (!isWhole) {
            canvas.rotate(-22f)
        }
        val actualRx = if (isWhole) rx * 1.14f else rx
        if (!hollow) {
            canvas.drawOval(RectF(-actualRx, -ry, actualRx, ry), paint)
        } else {
            val ringPath = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addOval(RectF(-actualRx, -ry, actualRx, ry), Path.Direction.CW)
                val innerTilt = if (isWhole) -24f else 18f
                val innerMatrix = Matrix().apply { setRotate(innerTilt) }
                val hole = Path().apply {
                    addOval(
                        RectF(-actualRx * 0.56f, -ry * 0.50f, actualRx * 0.56f, ry * 0.50f),
                        Path.Direction.CW
                    )
                    transform(innerMatrix)
                }
                addPath(hole)
            }
            canvas.drawPath(ringPath, paint)
        }
        canvas.restore()
    }

    private fun drawNoteFlags(
        canvas: Canvas,
        stemX: Float,
        tipY: Float,
        stemUp: Boolean,
        flagCount: Int,
        lineSpacing: Float,
        color: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.4f, lineSpacing * 0.22f)
            strokeCap = Paint.Cap.ROUND
        }
        val dir = if (stemUp) 1f else -1f
        for (f in 0 until flagCount) {
            val y0 = tipY + dir * f * (lineSpacing * 0.72f)
            val path = Path().apply {
                moveTo(stemX, y0)
                cubicTo(
                    stemX + lineSpacing * 0.55f, y0 + dir * lineSpacing * 0.45f,
                    stemX + lineSpacing * 1.15f, y0 + dir * lineSpacing * 1.05f,
                    stemX + lineSpacing * 0.72f, y0 + dir * lineSpacing * 1.95f
                )
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun drawSlantedBeam(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        thickness: Float,
        downwardFromLine: Boolean,
        color: Int
    ) {
        val dy = if (downwardFromLine) thickness else -thickness
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
            lineTo(x2, y2 + dy)
            lineTo(x1, y1 + dy)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSharpSign(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        s: Float,
        color: Int
    ) {
        val vPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = max(1.0f, s * 0.12f)
        }
        val hPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = max(1.5f, s * 0.22f)
        }
        val dx = s * 0.22f
        val vHalf = s * 0.92f
        canvas.drawLine(cx - dx, cy - vHalf + s * 0.08f, cx - dx, cy + vHalf + s * 0.08f, vPaint)
        canvas.drawLine(cx + dx, cy - vHalf - s * 0.08f, cx + dx, cy + vHalf - s * 0.08f, vPaint)

        val bw = s * 0.44f
        canvas.drawLine(cx - bw, cy - s * 0.26f, cx + bw, cy - s * 0.44f, hPaint)
        canvas.drawLine(cx - bw, cy + s * 0.44f, cx + bw, cy + s * 0.26f, hPaint)
    }

    private fun drawFlatSign(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        s: Float,
        color: Int
    ) {
        val stemX = cx - s * 0.22f
        val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.05f, s * 0.13f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(stemX, cy - s * 1.16f, stemX, cy + s * 0.44f, stemPaint)

        val bellyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.35f, s * 0.19f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val belly = Path().apply {
            moveTo(stemX, cy - s * 0.20f)
            cubicTo(
                cx + s * 0.34f, cy - s * 0.42f,
                cx + s * 0.36f, cy + s * 0.14f,
                stemX, cy + s * 0.44f
            )
        }
        canvas.drawPath(belly, bellyPaint)
    }

    /**
     * Draws optional natural cancellation signs (`♮`) followed by [newKey]'s `♯` or `♭` group
     * on exact classical 5-line staff line/space coordinates. Returns the right edge X coordinate.
     */
    fun drawKeySignatureGroup(
        canvas: Canvas,
        leftX: Float,
        staffTopY: Float,
        isBass: Boolean,
        s: Float,
        cancelledSteps: List<Int>,
        newKey: KeySignature,
        color: Int
    ): Float {
        val refTopStep = if (isBass) BASS_TOP_STEP else TREBLE_TOP_STEP
        val newSteps = newKey.signatureStaffSteps(isBass)
        if (cancelledSteps.isEmpty() && newSteps.isEmpty()) return leftX

        val stepAdv = s * 0.68f
        var curX = leftX
        for (st in cancelledSteps) {
            val y = stepToY(st, refTopStep, staffTopY, s)
            drawNaturalSign(canvas, curX + s * 0.28f, y, s, color)
            curX += stepAdv
        }
        if (cancelledSteps.isNotEmpty() && newSteps.isNotEmpty()) {
            curX += s * 0.20f
        }
        val isSharp = newKey.safeFifths > 0
        for (st in newSteps) {
            val y = stepToY(st, refTopStep, staffTopY, s)
            if (isSharp) {
                drawSharpSign(canvas, curX + s * 0.28f, y, s, color)
            } else {
                drawFlatSign(canvas, curX + s * 0.28f, y, s, color)
            }
            curX += stepAdv
        }
        return curX + s * 0.26f
    }

    /**
     * Compact live 5-line Treble preview rendered inside `KeySignatureStudioDialog` showing
     * Clef + (optional double barline & cancellation `♮`) + active `♯`/`♭` key signature group.
     */
    fun drawMiniKeySignaturePreview(
        canvas: Canvas,
        width: Float,
        height: Float,
        keySignature: KeySignature,
        prevKeyForModulation: KeySignature?,
        inkColor: Int,
        staffLineColor: Int
    ) {
        if (width <= 20f || height <= 20f) return
        val s = (height / 6.8f).coerceIn(5.5f, 11.5f)
        val staffH = s * 4f
        val topY = (height - staffH) * 0.52f
        val marginL = s * 0.8f
        val marginR = width - s * 0.8f

        drawFiveLines(canvas, marginL, marginR, topY, s, staffLineColor)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            strokeWidth = max(1.1f, s * 0.13f)
        }
        canvas.drawLine(marginL, topY, marginL, topY + staffH, barPaint)
        drawTrebleClef(canvas, marginL + s * 1.35f, topY, s, inkColor)

        var xCursor = marginL + s * 2.70f
        if (prevKeyForModulation != null) {
            if (prevKeyForModulation.safeFifths != 0) {
                xCursor = drawKeySignatureGroup(
                    canvas = canvas,
                    leftX = xCursor,
                    staffTopY = topY,
                    isBass = false,
                    s = s,
                    cancelledSteps = emptyList(),
                    newKey = prevKeyForModulation,
                    color = Color.argb(145, Color.red(inkColor), Color.green(inkColor), Color.blue(inkColor))
                )
            }
            xCursor += s * 0.65f
            canvas.drawLine(xCursor - s * 0.34f, topY, xCursor - s * 0.34f, topY + staffH, barPaint)
            canvas.drawLine(xCursor, topY, xCursor, topY + staffH, barPaint)
            val cancelled = prevKeyForModulation.cancelledStaffStepsTo(keySignature, isBass = false)
            drawKeySignatureGroup(
                canvas = canvas,
                leftX = xCursor + s * 0.42f,
                staffTopY = topY,
                isBass = false,
                s = s,
                cancelledSteps = cancelled,
                newKey = keySignature,
                color = inkColor
            )
        } else if (keySignature.safeFifths != 0) {
            drawKeySignatureGroup(
                canvas = canvas,
                leftX = xCursor,
                staffTopY = topY,
                isBass = false,
                s = s,
                cancelledSteps = emptyList(),
                newKey = keySignature,
                color = inkColor
            )
        }
    }

    private fun drawNaturalSign(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        s: Float,
        color: Int
    ) {
        val vPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = max(1.0f, s * 0.12f)
        }
        val hPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = max(1.4f, s * 0.20f)
        }
        val dx = s * 0.20f
        canvas.drawLine(cx - dx, cy - s * 0.95f, cx - dx, cy + s * 0.42f, vPaint)
        canvas.drawLine(cx + dx, cy - s * 0.42f, cx + dx, cy + s * 0.95f, vPaint)
        canvas.drawLine(cx - dx, cy - s * 0.26f, cx + dx, cy - s * 0.42f, hPaint)
        canvas.drawLine(cx - dx, cy + s * 0.42f, cx + dx, cy + s * 0.26f, hPaint)
    }

    private fun drawTieArc(
        canvas: Canvas,
        x1: Float,
        x2: Float,
        y: Float,
        curveAbove: Boolean,
        lineSpacing: Float,
        color: Int
    ) {
        if (x2 <= x1 + 2f) return
        val dir = if (curveAbove) -1f else 1f
        val startY = y + dir * lineSpacing * 0.48f
        val archH = dir * min(lineSpacing * 1.15f, (x2 - x1) * 0.28f + lineSpacing * 0.35f)
        val path = Path().apply {
            moveTo(x1, startY)
            cubicTo(
                x1 + (x2 - x1) * 0.25f, startY + archH,
                x1 + (x2 - x1) * 0.75f, startY + archH,
                x2, startY
            )
            cubicTo(
                x1 + (x2 - x1) * 0.75f, startY + archH * 0.72f,
                x1 + (x2 - x1) * 0.25f, startY + archH * 0.72f,
                x1, startY
            )
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, paint)
    }

    fun drawRestGlyph(
        canvas: Canvas,
        x: Float,
        staffTopY: Float,
        lineSpacing: Float,
        baseDenom: Int,
        isDotted: Boolean,
        color: Int
    ): RectF {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1.3f, lineSpacing * 0.19f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val midY = staffTopY + 2.0f * lineSpacing
        when (baseDenom) {
            1 -> {
                val yTop = staffTopY + 1.0f * lineSpacing
                val w = lineSpacing * 1.15f
                val h = lineSpacing * 0.52f
                canvas.drawRect(x - w * 0.5f, yTop, x + w * 0.5f, yTop + h, fillPaint)
            }
            2 -> {
                val yBot = staffTopY + 2.0f * lineSpacing
                val w = lineSpacing * 1.15f
                val h = lineSpacing * 0.52f
                canvas.drawRect(x - w * 0.5f, yBot - h, x + w * 0.5f, yBot, fillPaint)
            }
            4 -> {
                val path = Path().apply {
                    moveTo(x - lineSpacing * 0.18f, staffTopY + 0.82f * lineSpacing)
                    lineTo(x + lineSpacing * 0.34f, staffTopY + 1.55f * lineSpacing)
                    lineTo(x - lineSpacing * 0.26f, staffTopY + 2.22f * lineSpacing)
                    lineTo(x + lineSpacing * 0.28f, staffTopY + 2.82f * lineSpacing)
                    cubicTo(
                        x - lineSpacing * 0.38f, staffTopY + 2.75f * lineSpacing,
                        x - lineSpacing * 0.42f, staffTopY + 3.42f * lineSpacing,
                        x + lineSpacing * 0.08f, staffTopY + 3.55f * lineSpacing
                    )
                }
                canvas.drawPath(path, strokePaint)
            }
            else -> {
                val is16 = baseDenom >= 16
                val topStemX = x + lineSpacing * 0.32f
                val topStemY = staffTopY + 1.25f * lineSpacing
                val botStemX = x - lineSpacing * (if (is16) 0.28f else 0.16f)
                val botStemY = staffTopY + (if (is16) 3.45f else 2.95f) * lineSpacing
                canvas.drawLine(topStemX, topStemY, botStemX, botStemY, strokePaint)

                val b1x = x - lineSpacing * 0.22f
                val b1y = staffTopY + 1.48f * lineSpacing
                canvas.drawCircle(b1x, b1y, lineSpacing * 0.24f, fillPaint)
                val h1 = Path().apply {
                    moveTo(b1x, b1y + lineSpacing * 0.12f)
                    quadTo(x + lineSpacing * 0.05f, b1y + lineSpacing * 0.22f, topStemX, topStemY)
                }
                canvas.drawPath(h1, strokePaint)

                if (is16) {
                    val b2x = x - lineSpacing * 0.34f
                    val b2y = staffTopY + 2.32f * lineSpacing
                    val midStemX = topStemX + (botStemX - topStemX) * 0.45f
                    val midStemY = topStemY + (botStemY - topStemY) * 0.45f
                    canvas.drawCircle(b2x, b2y, lineSpacing * 0.24f, fillPaint)
                    val h2 = Path().apply {
                        moveTo(b2x, b2y + lineSpacing * 0.12f)
                        quadTo(x - lineSpacing * 0.08f, b2y + lineSpacing * 0.20f, midStemX, midStemY)
                    }
                    canvas.drawPath(h2, strokePaint)
                }
            }
        }

        if (isDotted) {
            val dotX = x + lineSpacing * 0.82f
            val dotY = staffTopY + 1.5f * lineSpacing
            canvas.drawCircle(dotX, dotY, max(1.5f, lineSpacing * 0.19f), fillPaint)
        }

        val padX = lineSpacing * 0.85f
        val padY = lineSpacing * 1.45f
        return RectF(x - padX, midY - padY, x + padX, midY + padY)
    }

    /**
     * Classical engraved Piano Grand Staff curly brace (`{`) spanning [topY]..[bottomY]
     * immediately to the left of [barLineX].
     */
    private fun drawPianoGrandBrace(
        canvas: Canvas,
        barLineX: Float,
        topY: Float,
        bottomY: Float,
        lineSpacing: Float,
        color: Int
    ) {
        val totalH = bottomY - topY
        if (totalH <= lineSpacing * 4f) return

        val midY = (topY + bottomY) * 0.5f
        val halfH = totalH * 0.5f
        val rightEdgeX = barLineX - lineSpacing * 0.16f
        val tipLeftX = rightEdgeX - lineSpacing * 0.78f
        val outerBellyX = rightEdgeX - lineSpacing * 0.56f
        val innerBellyX = rightEdgeX - lineSpacing * 0.24f

        val path = Path().apply {
            // Top tip -> outer upper S-curve -> left-pointing center nib
            moveTo(rightEdgeX, topY)
            cubicTo(
                outerBellyX, topY + halfH * 0.14f,
                rightEdgeX - lineSpacing * 0.04f, midY - halfH * 0.28f,
                tipLeftX, midY
            )
            // Left-pointing center nib -> outer lower S-curve -> bottom tip
            cubicTo(
                rightEdgeX - lineSpacing * 0.04f, midY + halfH * 0.28f,
                outerBellyX, bottomY - halfH * 0.14f,
                rightEdgeX, bottomY
            )
            // Return up along the inner tapered contour -> inner center -> top tip
            cubicTo(
                innerBellyX, bottomY - halfH * 0.18f,
                rightEdgeX - lineSpacing * 0.26f, midY + halfH * 0.20f,
                tipLeftX + lineSpacing * 0.24f, midY
            )
            cubicTo(
                rightEdgeX - lineSpacing * 0.26f, midY - halfH * 0.20f,
                innerBellyX, topY + halfH * 0.18f,
                rightEdgeX, topY
            )
            close()
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, fillPaint)
    }
}
