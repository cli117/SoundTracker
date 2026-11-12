package com.soundtracker.app.melody

import java.util.UUID
import kotlin.math.abs

/**
 * Accidental glyph sign rendered before a notehead or inside a key-signature group.
 */
enum class AccidentalSign {
    NONE,
    SHARP,
    FLAT,
    NATURAL
}

/**
 * Enharmonic staff spelling for a MIDI note under an active [KeySignature].
 *
 * @param diatonicStep `octave * 7 + degree` (`C4` = `4 * 7 + 0 = 28`)
 * @param degree Diatonic letter index `0..6` (`C, D, E, F, G, A, B`)
 * @param octave Written pitch octave (accounts for enharmonic `B♯3 == MIDI 60` and `C♭4 == MIDI 59`)
 * @param alteration `-1` = Flat (`♭`), `0` = Natural (`♮`), `+1` = Sharp (`♯`)
 */
data class SpelledPitch(
    val diatonicStep: Int,
    val degree: Int,
    val octave: Int,
    val alteration: Int
)

/**
 * Complete Circle-of-Fifths Key Signature (`调号`, `-7♭ ~ 0 ~ +7♯`, Major & Relative Minor).
 */
data class KeySignature(
    val fifths: Int = 0,
    val isMinor: Boolean = false
) {
    val safeFifths: Int get() = fifths.coerceIn(-7, 7)

    /** True when the key signature contains flats (`-1..-7`), driving `bD/bE/bG/bA/bB` spelling & black keys. */
    val prefersFlats: Boolean get() = safeFifths < 0

    val majorTonicName: String get() = MAJOR_TONIC_NAMES[safeFifths + 7]
    val minorTonicName: String get() = MINOR_TONIC_NAMES[safeFifths + 7]

    val accidentalCountBadge: String
        get() = when {
            safeFifths > 0 -> "${safeFifths}♯"
            safeFifths < 0 -> "${-safeFifths}♭"
            else -> "0"
        }

    /** Compact Circle-of-Fifths pair label, e.g. `"C / a"`, `"G / e"`, `"B♭ / g"`. */
    val pairLabel: String get() = "$majorTonicName / $minorTonicName"

    /** Primary mode name in Chinese, e.g. `"C大调"`, `"a小调"`, `"B♭大调"`, `"f♯小调"`. */
    val modeNameCn: String
        get() = if (isMinor) "${minorTonicName}小调" else "${majorTonicName}大调"

    /** Compact top-bar pill label, e.g. `"C大调"`, `"G大调 1♯"`, `"B♭大调 2♭"`, `"a小调"`. */
    val shortPillLabel: String
        get() = if (safeFifths == 0) {
            modeNameCn
        } else {
            "$modeNameCn $accidentalCountBadge"
        }

    /** Staff modulation marker badge above double barline, e.g. `"1=G (1♯)"` or `"e小调 (1♯)"`. */
    val modulationBadgeText: String
        get() = if (isMinor) {
            if (safeFifths == 0) "${minorTonicName}小调" else "${minorTonicName}小调($accidentalCountBadge)"
        } else {
            if (safeFifths == 0) "1=$majorTonicName(还原)" else "1=$majorTonicName($accidentalCountBadge)"
        }

    /** Full descriptive title for dialogs & PDF metadata. */
    val fullDisplayName: String
        get() {
            val accDesc = when {
                safeFifths > 0 -> "${safeFifths}个升号 ($accidentalCountBadge)"
                safeFifths < 0 -> "${-safeFifths}个降号 ($accidentalCountBadge)"
                else -> "无升降号"
            }
            return if (isMinor) {
                "${minorTonicName}小调（关系${majorTonicName}大调 · $accDesc）"
            } else {
                "${majorTonicName}大调（关系${minorTonicName}小调 · $accDesc）"
            }
        }

    fun sanitized(): KeySignature = KeySignature(safeFifths, isMinor)

    /**
     * Returns `-1` (flat), `0` (natural), or `+1` (sharp) for diatonic letter `degree` (`0=C .. 6=B`)
     * according to this key signature.
     */
    fun degreeAlteration(degree: Int): Int {
        val deg = degree.mod(7)
        val f = safeFifths
        if (f > 0) {
            for (i in 0 until f) {
                if (SHARP_DEGREE_ORDER[i] == deg) return 1
            }
            return 0
        } else if (f < 0) {
            val count = -f
            for (i in 0 until count) {
                if (FLAT_DEGREE_ORDER[i] == deg) return -1
            }
            return 0
        }
        return 0
    }

    /**
     * Classical 5-line staff diatonic steps for this key signature's `♯` or `♭` group.
     */
    fun signatureStaffSteps(isBass: Boolean): List<Int> {
        val f = safeFifths
        return when {
            f > 0 -> (if (isBass) BASS_SHARP_STEPS else TREBLE_SHARP_STEPS).take(f)
            f < 0 -> (if (isBass) BASS_FLAT_STEPS else TREBLE_FLAT_STEPS).take(-f)
            else -> emptyList()
        }
    }

    /**
     * Classical natural cancellation steps (`♮`) needed when modulating from `this` key to [nextKey]:
     * Any sharp/flat present in `this` key that is NOT retained with the exact same sign in [nextKey]
     * is cancelled in canonical order before drawing [nextKey]'s `♯` / `♭` group.
     */
    fun cancelledStaffStepsTo(nextKey: KeySignature, isBass: Boolean): List<Int> {
        val fOld = safeFifths
        val fNew = nextKey.safeFifths
        if (fOld == 0 || fOld == fNew) return emptyList()

        return if (fOld > 0) {
            val oldSteps = (if (isBass) BASS_SHARP_STEPS else TREBLE_SHARP_STEPS).take(fOld)
            if (fNew > 0) {
                if (fNew < fOld) oldSteps.subList(fNew, fOld) else emptyList()
            } else {
                oldSteps
            }
        } else {
            val absOld = -fOld
            val oldSteps = (if (isBass) BASS_FLAT_STEPS else TREBLE_FLAT_STEPS).take(absOld)
            if (fNew < 0) {
                val absNew = -fNew
                if (absNew < absOld) oldSteps.subList(absNew, absOld) else emptyList()
            } else {
                oldSteps
            }
        }
    }

    /**
     * Spells any MIDI pitch (`12..108`) idiomatically under this key signature without altering MIDI pitch:
     * - All 7 in-key diatonic tones map to their exact scale degree with zero accidental needed.
     * - Out-of-key natural white keys spell with `alteration = 0` (`♮`).
     * - Out-of-key black keys follow this key's sharp/flat tendency (`♯` when `fifths >= 0`, `♭` when `fifths < 0`).
     */
    fun spellMidi(midiNote: Int): SpelledPitch {
        val clamped = midiNote.coerceIn(12, 108)
        val pc = clamped.mod(12)

        // 1. Check if `pc` is one of the 7 diatonic scale tones of this key signature
        for (deg in 0..6) {
            val keyAlt = degreeAlteration(deg)
            val scalePc = (NATURAL_SEMITONES[deg] + keyAlt).mod(12)
            if (scalePc == pc) {
                val oct = ((clamped - keyAlt - NATURAL_SEMITONES[deg]) / 12) - 1
                return SpelledPitch(
                    diatonicStep = oct * 7 + deg,
                    degree = deg,
                    octave = oct,
                    alteration = keyAlt
                )
            }
        }

        // 2. Check if `pc` is a natural white key cancelling an active sharp/flat in this key
        val natDeg = NATURAL_SEMITONES.indexOf(pc)
        if (natDeg >= 0) {
            val oct = ((clamped - NATURAL_SEMITONES[natDeg]) / 12) - 1
            return SpelledPitch(
                diatonicStep = oct * 7 + natDeg,
                degree = natDeg,
                octave = oct,
                alteration = 0
            )
        }

        // 3. Remaining black-key chromatic alterations follow key tendency (`♯` for >=0, `♭` for <0)
        val (chromDeg, chromAlt) = if (safeFifths >= 0) {
            when (pc) {
                1 -> 0 to 1   // C♯
                3 -> 1 to 1   // D♯
                6 -> 3 to 1   // F♯
                8 -> 4 to 1   // G♯
                else -> 5 to 1 // A♯ (pc == 10)
            }
        } else {
            when (pc) {
                1 -> 1 to -1   // D♭
                3 -> 2 to -1   // E♭
                6 -> 4 to -1   // G♭
                8 -> 5 to -1   // A♭
                else -> 6 to -1 // B♭ (pc == 10)
            }
        }
        val oct = ((clamped - chromAlt - NATURAL_SEMITONES[chromDeg]) / 12) - 1
        return SpelledPitch(
            diatonicStep = oct * 7 + chromDeg,
            degree = chromDeg,
            octave = oct,
            alteration = chromAlt
        )
    }

    companion object {
        val C_MAJOR = KeySignature(fifths = 0, isMinor = false)

        private val NATURAL_SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)

        // Circle-of-fifths order: F(3), C(0), G(4), D(1), A(5), E(2), B(6)
        val SHARP_DEGREE_ORDER = intArrayOf(3, 0, 4, 1, 5, 2, 6)
        // Reverse order for flats: B(6), E(2), A(5), D(1), G(4), C(0), F(3)
        val FLAT_DEGREE_ORDER = intArrayOf(6, 2, 5, 1, 4, 0, 3)

        // Classical staff line/space positions (diatonicStep = oct * 7 + degree):
        // Treble sharp steps: F5(38), C5(35), G5(39), D5(36), A4(33), E5(37), B4(34)
        val TREBLE_SHARP_STEPS = listOf(38, 35, 39, 36, 33, 37, 34)
        // Treble flat steps:  B4(34), E5(37), A4(33), D5(36), G4(32), C5(35), F4(31)
        val TREBLE_FLAT_STEPS = listOf(34, 37, 33, 36, 32, 35, 31)

        // Bass sharp steps:   F3(24), C3(21), G3(25), D3(22), A2(19), E3(23), B2(20)
        val BASS_SHARP_STEPS = listOf(24, 21, 25, 22, 19, 23, 20)
        // Bass flat steps:    B2(20), E3(23), A2(19), D3(22), G2(18), C3(21), F2(17)
        val BASS_FLAT_STEPS = listOf(20, 23, 19, 22, 18, 21, 17)

        // Indexed by `fifths + 7` (-7..+7)
        private val MAJOR_TONIC_NAMES = arrayOf(
            "C♭", "G♭", "D♭", "A♭", "E♭", "B♭", "F",
            "C",
            "G", "D", "A", "E", "B", "F♯", "C♯"
        )
        private val MINOR_TONIC_NAMES = arrayOf(
            "a♭", "e♭", "b♭", "f", "c", "g", "d",
            "a",
            "e", "b", "f♯", "c♯", "g♯", "d♯", "a♯"
        )

        val SHARP_FIFTHS_LIST = listOf(1, 2, 3, 4, 5, 6, 7)
        val FLAT_FIFTHS_LIST = listOf(-1, -2, -3, -4, -5, -6, -7)

        /**
         * Maps a detected `(tonicPitchClass 0..11, isMinor)` to its most idiomatic Circle-of-Fifths [KeySignature].
         */
        fun fromTonicPitchClass(tonicPc: Int, isMinor: Boolean): KeySignature {
            val relMajorPc = if (isMinor) (tonicPc + 3).mod(12) else tonicPc.mod(12)
            val fifths = when (relMajorPc) {
                0 -> 0    // C / a
                7 -> 1    // G / e
                2 -> 2    // D / b
                9 -> 3    // A / f♯
                4 -> 4    // E / c♯
                11 -> 5   // B / g♯
                6 -> if (isMinor) -6 else 6 // e♭ minor (-6) or F♯ major (+6)
                1 -> -5   // D♭ / b♭
                8 -> -4   // A♭ / f
                3 -> -3   // E♭ / c
                10 -> -2  // B♭ / g
                5 -> -1   // F / d
                else -> 0
            }
            return KeySignature(fifths = fifths, isMinor = isMinor)
        }

        /**
         * Optional helper that recognizes explicit key annotations in imported track titles
         * (e.g. `"F 调世界真细小伴奏"`, `"Bb调"`, `"#F调"`, `"eb小调"`).
         */
        fun parseTrackTitleHint(title: String): KeySignature? {
            if (title.isBlank()) return null
            val regex = Regex(
                """(?:1\s*=\s*)?([#♯b♭]?)\s*([A-Ga-g])\s*([#♯b♭]?)\s*(大调|小调|调|major|minor|maj|min)""",
                RegexOption.IGNORE_CASE
            )
            val m = regex.find(title) ?: return null
            val preAcc = m.groupValues[1]
            val letterRaw = m.groupValues[2]
            val postAcc = m.groupValues[3]
            val suffix = m.groupValues[4].lowercase()
            val accStr = preAcc.ifEmpty { postAcc }
            val isMinor = suffix.contains("小") || suffix == "minor" || suffix == "min" ||
                (suffix == "调" && letterRaw[0].isLowerCase() && accStr.isEmpty())

            val basePc = when (letterRaw.uppercase()) {
                "C" -> 0
                "D" -> 2
                "E" -> 4
                "F" -> 5
                "G" -> 7
                "A" -> 9
                "B" -> 11
                else -> return null
            }
            val accDelta = when (accStr) {
                "#", "♯" -> 1
                "b", "♭" -> -1
                else -> 0
            }
            val pc = (basePc + accDelta).mod(12)
            // Preserve exact sharp/flat preference when explicitly written in title (e.g. #F vs Gb, #C vs Db)
            if (!isMinor) {
                if (pc == 6 && accDelta == -1) return KeySignature(-6, false)
                if (pc == 6 && accDelta == 1) return KeySignature(6, false)
                if (pc == 1 && accDelta == 1) return KeySignature(7, false)
                if (pc == 11 && accDelta == -1) return KeySignature(-7, false)
            }
            return fromTonicPitchClass(pc, isMinor)
        }
    }
}

/**
 * Mid-score Key Signature Change (`中途变调 / 转调`) taking effect from the start of [barIndex] (`barIndex >= 1`,
 * i.e. 1-based Bar `barIndex + 1` onward until the next [ScoreKeySignatureEvent]).
 */
data class ScoreKeySignatureEvent(
    val id: String = UUID.randomUUID().toString(),
    val barIndex: Int,
    val keySignature: KeySignature
) {
    val safeBarIndex: Int get() = barIndex.coerceAtLeast(1)
    val barNumber: Int get() = safeBarIndex + 1
}

/**
 * Intra-measure Accidental State Machine (`同小节临时记号状态机 ♯ / ♭ / ♮`):
 * - Initializes every staff step (`diatonicStep`) to the active [keySignature]'s alteration (`-1, 0, +1`).
 * - Tracks chromatic/natural alterations per `diatonicStep` across the measure so repeated notes in the same
 *   measure & octave omit redundant accidentals, subsequent natural notes on that step automatically receive `♮`,
 *   and everything resets cleanly at the next barline.
 */
class MeasureAccidentalTracker(
    val keySignature: KeySignature
) {
    private val activeStepAlteration = HashMap<Int, Int>()

    private fun currentAlterationAtStep(diatonicStep: Int): Int {
        return activeStepAlteration[diatonicStep]
            ?: keySignature.degreeAlteration(diatonicStep.mod(7))
    }

    /**
     * Evaluates a note in left-to-right measure order and returns the accidental glyph to draw (`NONE`, `SHARP`, `FLAT`, `NATURAL`).
     *
     * @param tiedAcrossBarStart True when this slice is the continuation of a tie across a barline (`tickInBar == 0`),
     *   in which case classical engraving omits repeating the accidental unless [atNewSystemStart] is true.
     */
    fun resolveNote(
        midiNote: Int,
        tiedFromPrev: Boolean,
        tickInBar: Int,
        atNewSystemStart: Boolean = false
    ): Pair<SpelledPitch, AccidentalSign> {
        val spelled = keySignature.spellMidi(midiNote)
        val prevAlt = currentAlterationAtStep(spelled.diatonicStep)

        // Intra-bar rhythmic decomposition tie (`tickInBar > 0`): same pitch continues within the same measure
        if (tiedFromPrev && tickInBar > 0) {
            return spelled to AccidentalSign.NONE
        }

        // Cross-barline tie (`tickInBar == 0`): omit accidental on tied-over notehead unless starting a new PDF line
        if (tiedFromPrev && tickInBar == 0 && !atNewSystemStart) {
            return spelled to AccidentalSign.NONE
        }

        // At a new PDF system start, a tied-over accidental outside the key signature shows a courtesy reminder
        val needSign = if (tiedFromPrev && tickInBar == 0 && atNewSystemStart) {
            spelled.alteration != keySignature.degreeAlteration(spelled.degree)
        } else {
            spelled.alteration != prevAlt
        }

        if (!needSign) {
            return spelled to AccidentalSign.NONE
        }

        activeStepAlteration[spelled.diatonicStep] = spelled.alteration
        val sign = when (spelled.alteration) {
            1 -> AccidentalSign.SHARP
            -1 -> AccidentalSign.FLAT
            else -> AccidentalSign.NATURAL
        }
        return spelled to sign
    }
}
