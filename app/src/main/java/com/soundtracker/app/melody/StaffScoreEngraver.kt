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

    // Notes below G3 (MIDI 55) automatically activate the connected Bass Staff (Grand Staff 大谱表)
    const val BASS_SPLIT_MIDI = 55

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
        var minClearanceTopY: Float,
        val color: Int
    )

    fun needsGrandStaff(notes: List<MelodyScoreNote>): Boolean =
        notes.any { !it.isRest && it.midiNote < BASS_SPLIT_MIDI }

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
            val onBass = !n.isRest && grandStaff && n.midiNote < BASS_SPLIT_MIDI
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

        // Fill silent gaps (both inside incomplete tuplet groups and between regular beats)
        if (fillAutoRestsInActiveBars || fillAllBarsWithAutoRests) {
            val maxActiveBar = symbols.maxOfOrNull { it.barIndex } ?: -1
            for (bar in 0 until totalBars) {
                val barSyms = symbols.filter { it.barIndex == bar }
                val shouldFill = fillAllBarsWithAutoRests || (barSyms.isNotEmpty() && bar <= maxActiveBar)
                if (!shouldFill) continue

                fillStaffGapsForMeasure(
                    out = symbols,
                    existingStaffBarSyms = barSyms.filter { !it.useBassStaff },
                    barIndex = bar,
                    timeSignature = timeSignature,
                    useBassStaff = false,
                    onlyBetweenOrFullPdf = fillAllBarsWithAutoRests
                )

                if (grandStaff && fillAllBarsWithAutoRests) {
                    fillStaffGapsForMeasure(
                        out = symbols,
                        existingStaffBarSyms = barSyms.filter { it.useBassStaff },
                        barIndex = bar,
                        timeSignature = timeSignature,
                        useBassStaff = true,
                        onlyBetweenOrFullPdf = true
                    )
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

    fun drawInteractiveTimelineStaff(
        canvas: Canvas,
        viewportWidth: Float,
        viewportHeight: Float,
        scrollX: Float,
        clefHeaderWidth: Float,
        barWidth: Float,
        melody: MelodyStudioUiState,
        palette: OnScreenPalette = OnScreenPalette()
    ): List<StaffHitTarget> {
        val hitTargets = mutableListOf<StaffHitTarget>()
        if (viewportWidth <= clefHeaderWidth + 20f || viewportHeight <= 36f) return hitTargets

        val timeSig = melody.timeSignature
        val ticksPerBar = timeSig.ticksPerBar
        val quarterBeatsPerBar = timeSig.quarterBeatsPerBar.coerceAtLeast(0.25f)
        val maxNoteBeat = melody.notes.maxOfOrNull { it.endBeat } ?: quarterBeatsPerBar
        val maxKeyBar = (melody.keySignatureEvents.maxOfOrNull { it.safeBarIndex + 1 } ?: 1)
        val totalBars = maxOf(
            melody.totalBars,
            ceil(maxNoteBeat / quarterBeatsPerBar).toInt(),
            maxKeyBar
        ).coerceAtLeast(1)
        val grandStaff = needsGrandStaff(melody.notes)
        val hasTopMarks = melody.tempoEvents.isNotEmpty() ||
            melody.keySignatureEvents.isNotEmpty() ||
            melody.notes.any { it.hasFermata || it.accent == NoteAccent.MARCATO }
        val hasBottomMarks = melody.dynamicMarks.isNotEmpty() || melody.hairpins.isNotEmpty()

        val verticalDivisor = when {
            grandStaff && (hasTopMarks || hasBottomMarks) -> 17.2f
            grandStaff -> 15.6f
            hasTopMarks || hasBottomMarks -> 11.2f
            else -> 9.6f
        }
        val lineSpacing = if (grandStaff) {
            (viewportHeight / verticalDivisor).coerceIn(5.6f, 11.8f)
        } else {
            (viewportHeight / verticalDivisor).coerceIn(6.6f, 13.8f)
        }
        val staffHeight = lineSpacing * 4f
        val grandGap = lineSpacing * 3.5f

        val topBias = when {
            hasTopMarks && !hasBottomMarks -> lineSpacing * 0.75f
            !hasTopMarks && hasBottomMarks -> -lineSpacing * 0.45f
            else -> lineSpacing * 0.15f
        }
        val trebleTopY: Float
        val bassTopY: Float
        if (grandStaff) {
            val totalBlockH = staffHeight * 2f + grandGap
            trebleTopY = ((viewportHeight - totalBlockH) * 0.5f + topBias)
                .coerceAtLeast(lineSpacing * 2.9f)
            bassTopY = trebleTopY + staffHeight + grandGap
        } else {
            trebleTopY = ((viewportHeight - staffHeight) * 0.5f + topBias)
                .coerceAtLeast(lineSpacing * 3.0f)
            bassTopY = trebleTopY
        }
        val bottomStaffBottomY = if (grandStaff) bassTopY + staffHeight else trebleTopY + staffHeight

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
                    val cancelTreble = prevKey.cancelledStaffStepsTo(midKeyEvent.keySignature, isBass = false)
                    val groupStartX = barLeft + lineSpacing * 0.42f
                    val groupEndX = drawKeySignatureGroup(
                        canvas = canvas,
                        leftX = groupStartX,
                        staffTopY = trebleTopY,
                        isBass = false,
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

        // System-level Multi-Note Phrase Slurs + Fermatas + Dynamics/Hairpins + Tempo Marks
        renderSystemPhraseSlurs(
            canvas = canvas,
            allScoreNotes = melody.notes,
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
            viewportMinTopY = lineSpacing * 1.35f,
            lineSpacing = lineSpacing,
            inkColor = palette.expressionInkColor,
            selectedColor = palette.selectedColor,
            selectedAnnotation = melody.selectedAnnotation,
            outHitTargets = hitTargets
        )

        // Draw Step Input Cursor at exact `cursorTick`
        if (!melody.isRecording) {
            val cursorBar = melody.cursorTick / ticksPerBar
            val tickInBar = melody.cursorTick % ticksPerBar
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
                val cursorColorActual = when {
                    melody.activeSlurGroupId != null -> Color.rgb(26, 122, 94)
                    melody.activeTuplet != null -> palette.selectedColor
                    else -> palette.cursorColor
                }
                val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = cursorColorActual
                    strokeWidth = max(1.8f, lineSpacing * 0.16f)
                    style = Paint.Style.STROKE
                    pathEffect = DashPathEffect(floatArrayOf(lineSpacing * 0.65f, lineSpacing * 0.45f), 0f)
                }
                canvas.drawLine(
                    cx,
                    (trebleTopY - lineSpacing * 1.1f).coerceAtLeast(4f),
                    cx,
                    min(viewportHeight - 4f, bottomStaffBottomY + lineSpacing * 1.1f),
                    cursorPaint
                )
                val triPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = cursorColorActual
                    style = Paint.Style.FILL
                }
                val tri = Path().apply {
                    val topY = (trebleTopY - lineSpacing * 1.35f).coerceAtLeast(2f)
                    val sz = lineSpacing * 0.48f
                    moveTo(cx - sz, topY)
                    lineTo(cx + sz, topY)
                    lineTo(cx, topY + sz * 1.15f)
                    close()
                }
                canvas.drawPath(tri, triPaint)
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
            melody.dynamicMarks.isEmpty() &&
            melody.hairpins.isEmpty() &&
            melody.tempoEvents.isEmpty() &&
            !melody.isRecording
        ) {
            val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(145, 112, 102, 90)
                textSize = max(20f, lineSpacing * 1.20f)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "点击下方琴键写五线谱音符（支持调号/转调、连线、力度/奏法/速度记号），或点右上角「示例曲」体验",
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

        val leftMargin = lineSpacing * 0.65f
        drawFiveLines(canvas, leftMargin, clefHeaderWidth, trebleTopY, lineSpacing, palette.staffLineColor)
        if (grandStaff) {
            drawFiveLines(canvas, leftMargin, clefHeaderWidth, bassTopY, lineSpacing, palette.staffLineColor)
            val bracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.inkColor
                strokeWidth = max(2.0f, lineSpacing * 0.22f)
            }
            canvas.drawLine(leftMargin, trebleTopY, leftMargin, bottomStaffBottomY, bracePaint)
        } else {
            canvas.drawLine(leftMargin, trebleTopY, leftMargin, trebleTopY + staffHeight, barLinePaint)
        }

        drawTrebleClef(canvas, leftMargin + lineSpacing * 1.35f, trebleTopY, lineSpacing, palette.inkColor)
        if (grandStaff) {
            drawBassClef(canvas, leftMargin + lineSpacing * 1.30f, bassTopY, lineSpacing, palette.inkColor)
        }

        val headerKey = melody.keySignatureAtBar(visibleFirstBar)
        val keyRightX = if (headerKey.safeFifths != 0) {
            val kStart = leftMargin + lineSpacing * 2.52f
            val endTreble = drawKeySignatureGroup(
                canvas = canvas,
                leftX = kStart,
                staffTopY = trebleTopY,
                isBass = false,
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
            leftMargin + lineSpacing * 2.55f
        }

        val timeSigX = max(leftMargin + lineSpacing * 3.55f, keyRightX + lineSpacing * 0.85f)
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

        return hitTargets
    }

    /**
     * Maps an X tap coordinate on the interactive timeline staff back to a 1/16th-aligned `cursorTick`
     * under [melody]'s time signature & mid-score key changes.
     */
    fun mapTouchXToTick(
        touchX: Float,
        scrollX: Float,
        clefHeaderWidth: Float,
        barWidth: Float,
        lineSpacingEstimate: Float,
        totalBars: Int,
        melody: MelodyStudioUiState
    ): Int {
        val timeSignature = melody.timeSignature
        val ticksPerBar = timeSignature.ticksPerBar
        val stepsPerBar = timeSignature.stepsPerBar
        val worldX = (touchX - clefHeaderWidth + scrollX).coerceAtLeast(0f)
        val barIdx = floor(worldX / barWidth).toInt().coerceAtLeast(0)
        val inBarX = worldX - barIdx * barWidth
        val (noteOriginOffset, usableW) = onScreenMeasureLayout(
            melody = melody,
            barIndex = barIdx,
            barLeft = 0f,
            barWidth = barWidth,
            lineSpacing = lineSpacingEstimate
        )
        val frac = ((inBarX - noteOriginOffset) / usableW).coerceIn(0f, 0.999f)
        val stepInBar = floor(frac * stepsPerBar).toInt().coerceIn(0, (stepsPerBar - 1).coerceAtLeast(0))
        val rawTick = barIdx * ticksPerBar + stepInBar * TICKS_PER_STEP
        val maxTick = (totalBars.coerceAtLeast(1) * ticksPerBar - TICKS_PER_STEP).coerceAtLeast(0)
        return rawTick.coerceIn(0, maxTick)
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
            if (melody.notes.isEmpty()) melody.totalBars else barsFromNotes,
            min(melody.totalBars, max(1, barsFromNotes))
        ).coerceAtLeast(1)

        val grandStaff = needsGrandStaff(melody.notes)
        val barsPerSystem = if (ticksPerBar > 1200) 3 else 4
        val totalSystems = ceil(totalBars / barsPerSystem.toFloat()).toInt().coerceAtLeast(1)

        val lineSpacing = if (grandStaff) 5.8f else 6.6f
        val staffH = lineSpacing * 4f
        val grandGap = lineSpacing * 3.5f
        val systemBlockH = if (grandStaff) (staffH * 2f + grandGap) else staffH
        val systemRowSpacing = if (grandStaff) (systemBlockH + 66f) else (systemBlockH + 62f)

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
                    126f
                } else {
                    val runHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = mutedInk
                        textSize = 10f
                        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                    }
                    canvas.drawText("$titleText（续）", marginX, 40f, runHeaderPaint)
                    84f
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
                        endBarKey.cancelledStaffStepsTo(nextSysKeyEv.keySignature, isBass = false)
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

                    drawTrebleClef(canvas, marginX + lineSpacing * 1.30f, trebleTopY, lineSpacing, ink)
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
                            isBass = false,
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
                            val cancelT = prevKey.cancelledStaffStepsTo(midSysKeyEv.keySignature, isBass = false)
                            val kStart = bLeft + lineSpacing * 0.38f
                            drawKeySignatureGroup(
                                canvas = canvas,
                                leftX = kStart,
                                staffTopY = trebleTopY,
                                isBass = false,
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
                                isBass = false,
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
                        viewportMinTopY = trebleTopY - 26f,
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
            val staffTopY = if (isBass) bassTopY else trebleTopY
            val staffBottomY = staffTopY + 4f * lineSpacing
            val refTopStep = if (isBass) BASS_TOP_STEP else TREBLE_TOP_STEP
            val refBottomStep = if (isBass) BASS_BOTTOM_STEP else TREBLE_BOTTOM_STEP
            val refMiddleStep = if (isBass) BASS_MIDDLE_STEP else TREBLE_MIDDLE_STEP

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
            .groupBy { it.slurGroupId!! }

        for ((slurId, groupNotes) in slurredGroups) {
            val distinctOnsets = groupNotes.map { it.resolvedStartTick }.distinct().sorted()
            if (distinctOnsets.size < 2) continue

            val phraseFirstTick = distinctOnsets.first()
            val phraseMaxEndTick = groupNotes.maxOf { it.resolvedEndTick }
            val phraseLastNoteStartTick = distinctOnsets.last()
            if (phraseMaxEndTick <= sysStartTick || phraseFirstTick >= sysEndTick) continue

            val groupNoteIds = groupNotes.map { it.id }.toSet()
            val matchingAnchors = chordAnchors
                .filter { anchor -> slurId in anchor.slurGroupIds || anchor.sourceNoteIds.any { it in groupNoteIds } }
                .sortedBy { it.absStartTick }

            // Choose predominant staff (`treble` vs `bass`) for this phrase
            val onBass = matchingAnchors.count { it.useBassStaff } * 2 > matchingAnchors.size
            val staffTopY = if (onBass) bassTopY else trebleTopY
            val staffAnchors = chordAnchors
                .filter { it.useBassStaff == onBass }
                .sortedBy { it.xCenter }
            val phraseStaffAnchors = matchingAnchors
                .filter { it.useBassStaff == onBass }
                .ifEmpty { matchingAnchors }
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
                    if (pf.xCenter in (x1 - lineSpacing * 0.5f)..(x2 + lineSpacing * 0.5f)) {
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
            minAboveY = min(minAboveY, pf.minClearanceTopY - lineSpacing * 0.65f)
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
}
