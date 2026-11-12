package com.soundtracker.app.melody

import java.util.UUID

/**
 * Above-staff letter chord symbol (`谱表上方字母和弦标记`, e.g. `Cmaj7`, `G/B`, `F#m7-5`, `Bbadd9`)
 * anchored at an exact score [tick].
 */
data class ScoreChordSymbol(
    val id: String = UUID.randomUUID().toString(),
    val tick: Int,
    val symbol: String
) {
    val safeTick: Int get() = tick.coerceAtLeast(0)
    val cleanSymbol: String get() = symbol.trim()
}

/**
 * Structured result of parsing any user-selected or hand-typed letter chord symbol.
 */
data class ParsedChordSymbol(
    val canonicalSymbol: String,
    val rootName: String,
    val rootPitchClass: Int,
    val intervalsSemitones: List<Int>,
    val bassName: String? = null,
    val bassPitchClass: Int? = null,
    val qualityLabelCn: String
)

data class RecommendedChordOption(
    val degreeBadge: String,
    val symbol: String,
    val subtitleCn: String
)

data class DiatonicChordRecommendations(
    val keyLabel: String,
    val triads: List<RecommendedChordOption>,
    val sevenths: List<RecommendedChordOption>
)

data class ChordQualityOption(
    val suffix: String,
    val buttonLabel: String,
    val descCn: String
)

/**
 * Universal Chord Symbol Parser, Key-Signature-Aware Diatonic Chord Recommender,
 * and Studio Accompaniment Voicer (`C3 ~ E4` warm mid-low voicing).
 */
object MelodyChordHelper {

    private val DIATONIC_LETTERS = arrayOf("C", "D", "E", "F", "G", "A", "B")
    private val LETTER_BASE_PC = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    val SHARP_ROOT_OPTIONS = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val FLAT_ROOT_OPTIONS = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    val BUILDER_QUALITIES: List<ChordQualityOption> = listOf(
        ChordQualityOption("", "大三 (Maj)", "大三和弦"),
        ChordQualityOption("m", "m", "小三和弦"),
        ChordQualityOption("7", "7", "属七和弦"),
        ChordQualityOption("maj7", "maj7", "大七和弦"),
        ChordQualityOption("m7", "m7", "小七和弦"),
        ChordQualityOption("sus4", "sus4", "挂四和弦"),
        ChordQualityOption("sus2", "sus2", "挂二和弦"),
        ChordQualityOption("add9", "add9", "加九和弦"),
        ChordQualityOption("6", "6", "大六和弦"),
        ChordQualityOption("m6", "m6", "小六和弦"),
        ChordQualityOption("dim", "dim", "减三和弦"),
        ChordQualityOption("dim7", "dim7", "减七和弦"),
        ChordQualityOption("m7b5", "m7b5", "半减七和弦"),
        ChordQualityOption("aug", "aug", "增三和弦"),
        ChordQualityOption("7sus4", "7sus4", "属七挂四"),
        ChordQualityOption("9", "9", "属九和弦"),
        ChordQualityOption("maj9", "maj9", "大九和弦"),
        ChordQualityOption("m9", "m9", "小九和弦")
    )

    fun rootOptionsForKey(keySignature: KeySignature): List<String> =
        if (keySignature.prefersFlats) FLAT_ROOT_OPTIONS else SHARP_ROOT_OPTIONS

    /**
     * Generates diatonic triads & 7th chords spelled strictly according to [keySignature]
     * (`-7♭ .. 0 .. +7♯`, both Major and Minor modes).
     */
    fun recommendDiatonicChords(keySignature: KeySignature): DiatonicChordRecommendations {
        val key = keySignature.sanitized()
        val majorTonicDeg = (key.safeFifths * 4).mod(7)

        fun spelledRoot(deg: Int): String {
            val d = deg.mod(7)
            val acc = when (key.degreeAlteration(d)) {
                1 -> "#"
                -1 -> "b"
                else -> ""
            }
            return DIATONIC_LETTERS[d] + acc
        }

        if (!key.isMinor) {
            val triadRoma = arrayOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
            val triadSuffix = arrayOf("", "m", "m", "", "", "m", "dim")
            val triadNames = arrayOf("主大三", "上主小三", "中音小三", "下属大三", "属大三", "下中小三", "导减三")

            val sevRoma = arrayOf("Imaj7", "ii7", "iii7", "IVmaj7", "V7", "vi7", "viiø7")
            val sevSuffix = arrayOf("maj7", "m7", "m7", "maj7", "7", "m7", "m7b5")
            val sevNames = arrayOf("主大七", "二级小七", "三级小七", "下属大七", "属七", "六级小七", "导半减七")

            val triads = (0..6).map { step ->
                val root = spelledRoot(majorTonicDeg + step)
                RecommendedChordOption(
                    degreeBadge = triadRoma[step],
                    symbol = root + triadSuffix[step],
                    subtitleCn = triadNames[step]
                )
            }
            val sevenths = (0..6).map { step ->
                val root = spelledRoot(majorTonicDeg + step)
                RecommendedChordOption(
                    degreeBadge = sevRoma[step],
                    symbol = root + sevSuffix[step],
                    subtitleCn = sevNames[step]
                )
            }
            return DiatonicChordRecommendations(
                keyLabel = key.shortPillLabel,
                triads = triads,
                sevenths = sevenths
            )
        } else {
            val minorTonicDeg = (majorTonicDeg + 5) % 7
            val triadRoma = arrayOf("i", "ii°", "III", "iv", "v", "V(和声)", "VI", "VII")
            val triadStep = intArrayOf(0, 1, 2, 3, 4, 4, 5, 6)
            val triadSuffix = arrayOf("m", "dim", "", "m", "m", "", "", "")
            val triadNames = arrayOf("主小三", "二级减三", "关系大三", "下属小三", "自然属小", "和声属大", "六级大三", "七级大三")

            val sevRoma = arrayOf("im7", "iiø7", "IIImaj7", "iv7", "v7", "V7(和声)", "VImaj7", "VII7")
            val sevStep = intArrayOf(0, 1, 2, 3, 4, 4, 5, 6)
            val sevSuffix = arrayOf("m7", "m7b5", "maj7", "m7", "m7", "7", "maj7", "7")
            val sevNames = arrayOf("主小七", "二级半减七", "三级大七", "下属小七", "自然属小七", "和声属七", "六级大七", "七级属七")

            val triads = triadRoma.indices.map { i ->
                val root = spelledRoot(minorTonicDeg + triadStep[i])
                RecommendedChordOption(
                    degreeBadge = triadRoma[i],
                    symbol = root + triadSuffix[i],
                    subtitleCn = triadNames[i]
                )
            }
            val sevenths = sevRoma.indices.map { i ->
                val root = spelledRoot(minorTonicDeg + sevStep[i])
                RecommendedChordOption(
                    degreeBadge = sevRoma[i],
                    symbol = root + sevSuffix[i],
                    subtitleCn = sevNames[i]
                )
            }
            return DiatonicChordRecommendations(
                keyLabel = key.shortPillLabel,
                triads = triads,
                sevenths = sevenths
            )
        }
    }

    private fun parsePitchToken(token: String): Pair<String, Int>? {
        if (token.isEmpty()) return null
        val letterChar = token[0].uppercaseChar()
        val degIdx = DIATONIC_LETTERS.indexOfFirst { it[0] == letterChar }
        if (degIdx < 0) return null
        var pc = LETTER_BASE_PC[degIdx]
        val accBuilder = StringBuilder()
        var i = 1
        while (i < token.length) {
            when (token[i]) {
                '#', '♯' -> {
                    pc = (pc + 1).mod(12)
                    accBuilder.append('#')
                }
                'b', '♭' -> {
                    pc = (pc - 1).mod(12)
                    accBuilder.append('b')
                }
                else -> return null
            }
            i++
        }
        if (accBuilder.length > 2) return null
        return (letterChar.toString() + accBuilder.toString()) to pc
    }

    /**
     * Parses arbitrary standard or hand-typed letter chord symbols, e.g.:
     * `C`, `Dm`, `F#m7-5`, `Bbadd9`, `Dsus4`, `Cmaj7`, `G/B`, `C/E`, `Am7/G`, `Eb7sus4`, `G7b9`, `E7#9`, `C6/9`.
     */
    fun parseChordSymbol(rawInput: String): ParsedChordSymbol? {
        val trimmed = rawInput.trim().replace(" ", "")
        if (trimmed.isEmpty()) return null

        // 1. Parse root letter + optional accidental (# / b / ♯ / ♭)
        val rootLetterChar = trimmed[0].uppercaseChar()
        val rootDeg = DIATONIC_LETTERS.indexOfFirst { it[0] == rootLetterChar }
        if (rootDeg < 0) return null

        var cursor = 1
        var rootPc = LETTER_BASE_PC[rootDeg]
        val rootAcc = StringBuilder()
        while (cursor < trimmed.length && rootAcc.length < 2) {
            val ch = trimmed[cursor]
            if (ch == '#' || ch == '♯') {
                rootPc = (rootPc + 1).mod(12)
                rootAcc.append('#')
                cursor++
            } else if (ch == 'b' || ch == '♭') {
                rootPc = (rootPc - 1).mod(12)
                rootAcc.append('b')
                cursor++
            } else {
                break
            }
        }
        val rootName = rootLetterChar.toString() + rootAcc.toString()
        var remainder = trimmed.substring(cursor)

        // 2. Extract optional trailing `/Bass` inversion (preserving `6/9`!)
        var bassName: String? = null
        var bassPc: Int? = null
        val lastSlash = remainder.lastIndexOf('/')
        if (lastSlash >= 0) {
            val afterSlash = remainder.substring(lastSlash + 1)
            val beforeSlash = remainder.substring(0, lastSlash)
            val isSixNine = afterSlash == "9" && beforeSlash.endsWith("6")
            if (!isSixNine) {
                val parsedBass = parsePitchToken(afterSlash) ?: return null
                bassName = parsedBass.first
                bassPc = parsedBass.second
                remainder = beforeSlash
            }
        }

        // 3. Normalize Unicode chord shorthand inside quality body
        var q = remainder
            .replace("♯", "#")
            .replace("♭", "b")
            .replace("−", "-")
            .replace("–", "-")
            .replace("Δ", "maj")
            .replace("△", "maj")
            .replace("ø", "m7b5")
            .replace("Ø", "m7b5")
            .replace("°", "dim")
            .replace("º", "dim")

        // Strip parentheses so `Cm7(b5)`, `C(add9)`, `G7(#9)` parse seamlessly
        q = q.replace("(", "").replace(")", "")

        data class BaseQuality(val intervals: List<Int>, val labelCn: String)

        fun matchCoreQuality(core: String): BaseQuality? = when (core) {
            "", "M", "maj", "Maj" -> BaseQuality(listOf(0, 4, 7), "大三和弦")
            "m", "min", "-" -> BaseQuality(listOf(0, 3, 7), "小三和弦")
            "5", "no3" -> BaseQuality(listOf(0, 7), "五度和弦")
            "dim", "o" -> BaseQuality(listOf(0, 3, 6), "减三和弦")
            "dim7", "o7" -> BaseQuality(listOf(0, 3, 6, 9), "减七和弦")
            "m7b5", "m7-5", "min7b5", "min7-5", "-7b5", "-7-5" ->
                BaseQuality(listOf(0, 3, 6, 10), "半减七和弦")
            "aug", "+", "+5", "#5" -> BaseQuality(listOf(0, 4, 8), "增三和弦")
            "aug7", "+7", "7#5", "7+5" -> BaseQuality(listOf(0, 4, 8, 10), "增属七和弦")
            "maj7#5", "M7#5", "maj7+5" -> BaseQuality(listOf(0, 4, 8, 11), "增大七和弦")
            "sus2", "2" -> BaseQuality(listOf(0, 2, 7), "挂二和弦")
            "sus4", "sus", "4" -> BaseQuality(listOf(0, 5, 7), "挂四和弦")
            "7sus4", "7sus", "sus7" -> BaseQuality(listOf(0, 5, 7, 10), "属七挂四")
            "7sus2" -> BaseQuality(listOf(0, 2, 7, 10), "属七挂二")
            "9sus4", "9sus" -> BaseQuality(listOf(0, 5, 7, 10, 14), "属九挂四")
            "6", "M6", "maj6" -> BaseQuality(listOf(0, 4, 7, 9), "大六和弦")
            "m6", "min6", "-6" -> BaseQuality(listOf(0, 3, 7, 9), "小六和弦")
            "6/9", "69", "6add9" -> BaseQuality(listOf(0, 4, 7, 9, 14), "六九和弦")
            "m6/9", "m69", "m6add9" -> BaseQuality(listOf(0, 3, 7, 9, 14), "小六九和弦")
            "7", "dom7" -> BaseQuality(listOf(0, 4, 7, 10), "属七和弦")
            "maj7", "M7", "Maj7", "ma7" -> BaseQuality(listOf(0, 4, 7, 11), "大七和弦")
            "m7", "min7", "-7" -> BaseQuality(listOf(0, 3, 7, 10), "小七和弦")
            "mM7", "mMaj7", "mmaj7", "minMaj7", "minmaj7" ->
                BaseQuality(listOf(0, 3, 7, 11), "小大七和弦")
            "add9", "add2" -> BaseQuality(listOf(0, 4, 7, 14), "加九和弦")
            "madd9", "minadd9", "madd2" -> BaseQuality(listOf(0, 3, 7, 14), "小加九和弦")
            "add11", "add4" -> BaseQuality(listOf(0, 4, 7, 17), "加十一和弦")
            "madd11", "minadd11", "madd4" -> BaseQuality(listOf(0, 3, 7, 17), "小加十一和弦")
            "9" -> BaseQuality(listOf(0, 4, 7, 10, 14), "属九和弦")
            "maj9", "M9", "Maj9", "ma9" -> BaseQuality(listOf(0, 4, 7, 11, 14), "大九和弦")
            "m9", "min9", "-9" -> BaseQuality(listOf(0, 3, 7, 10, 14), "小九和弦")
            "11" -> BaseQuality(listOf(0, 5, 7, 10, 14, 17), "属十一和弦")
            "maj11", "M11", "Maj11" -> BaseQuality(listOf(0, 4, 7, 11, 14, 17), "大十一和弦")
            "m11", "min11", "-11" -> BaseQuality(listOf(0, 3, 7, 10, 14, 17), "小十一和弦")
            "13" -> BaseQuality(listOf(0, 4, 7, 10, 14, 21), "属十三和弦")
            "maj13", "M13", "Maj13" -> BaseQuality(listOf(0, 4, 7, 11, 14, 21), "大十三和弦")
            "m13", "min13", "-13" -> BaseQuality(listOf(0, 3, 7, 10, 14, 21), "小十三和弦")
            else -> null
        }

        val modifierTokens = listOf(
            "add11", "add9", "add4", "add2",
            "sus4", "sus2", "no3", "no5", "alt",
            "#11", "+11", "b13", "-13",
            "b9", "-9", "#9", "+9",
            "b5", "-5", "#5", "+5"
        )

        var workingCore = q
        val extractedMods = mutableListOf<String>()
        var baseQuality = matchCoreQuality(workingCore)
        while (baseQuality == null && workingCore.isNotEmpty()) {
            val matchedMod = modifierTokens.firstOrNull { workingCore.endsWith(it, ignoreCase = true) }
                ?: break
            extractedMods.add(0, matchedMod.lowercase())
            workingCore = workingCore.dropLast(matchedMod.length)
            baseQuality = matchCoreQuality(workingCore)
        }
        // Fallback case-insensitive match for core (e.g. user typed "cmaj7" or "dm7" or "bdim")
        if (baseQuality == null) {
            val lower = workingCore.lowercase()
            if (lower != workingCore && lower != "m") {
                baseQuality = matchCoreQuality(lower)
            }
        }
        val resolvedBase = baseQuality ?: return null

        val intervalSet = resolvedBase.intervals.toMutableSet()
        for (mod in extractedMods) {
            when (mod) {
                "b5", "-5" -> {
                    intervalSet.remove(7)
                    intervalSet.add(6)
                }
                "#5", "+5" -> {
                    intervalSet.remove(7)
                    intervalSet.add(8)
                }
                "b9", "-9" -> intervalSet.add(13)
                "#9", "+9" -> intervalSet.add(15)
                "add9", "add2" -> intervalSet.add(14)
                "add11", "add4" -> intervalSet.add(17)
                "#11", "+11" -> intervalSet.add(18)
                "b13", "-13" -> intervalSet.add(20)
                "sus4" -> {
                    intervalSet.remove(3)
                    intervalSet.remove(4)
                    intervalSet.add(5)
                }
                "sus2" -> {
                    intervalSet.remove(3)
                    intervalSet.remove(4)
                    intervalSet.add(2)
                }
                "no3" -> {
                    intervalSet.remove(3)
                    intervalSet.remove(4)
                }
                "no5" -> intervalSet.remove(7)
                "alt" -> {
                    intervalSet.remove(7)
                    intervalSet.add(8)
                    intervalSet.add(15)
                }
            }
        }

        val sortedIntervals = intervalSet.toList().sorted()
        val canonicalBody = remainder.trim()
        val canonicalSymbol = buildString {
            append(rootName)
            append(canonicalBody)
            if (bassName != null) {
                append('/')
                append(bassName)
            }
        }
        val descLabel = if (bassName != null) {
            "${resolvedBase.labelCn} / 低音$bassName"
        } else {
            resolvedBase.labelCn
        }

        return ParsedChordSymbol(
            canonicalSymbol = canonicalSymbol,
            rootName = rootName,
            rootPitchClass = rootPc,
            intervalsSemitones = sortedIntervals,
            bassName = bassName,
            bassPitchClass = bassPc,
            qualityLabelCn = descLabel
        )
    }

    /**
     * Voices a [ParsedChordSymbol] into a warm, balanced studio accompaniment register (`G2 .. E4`,
     * upper voices centered in `C3 .. E4` = MIDI `48 .. 64`) with per-voice relative velocity weights
     * so chord playback sounds lush and supportive without masking the right-hand main melody.
     */
    fun voiceChordForAccompaniment(parsed: ParsedChordSymbol): List<Pair<Int, Float>> {
        val intervals = parsed.intervalsSemitones.toMutableList()
        // Keep 4-5 voice clarity on rich extended chords by omitting redundant perfect 5th
        if (intervals.size >= 5 && intervals.contains(7) && (intervals.contains(10) || intervals.contains(11))) {
            intervals.remove(7)
        }

        val effectiveBassPc = parsed.bassPitchClass ?: parsed.rootPitchClass
        // Place bass root cleanly in G2(43) .. F#3(54)
        val bassMidi = (effectiveBassPc - 7).mod(12) + 43

        // Collect upper chord pitch classes (include root in upper voicing if slash bass differs from root)
        val upperPcs = LinkedHashSet<Int>()
        for (semi in intervals) {
            val pc = (parsed.rootPitchClass + semi).mod(12)
            if (semi == 0 && parsed.bassPitchClass == null) continue
            upperPcs.add(pc)
        }
        // Ensure at least 2 upper tones for power/dyad chords
        if (upperPcs.size < 2) {
            upperPcs.add(parsed.rootPitchClass)
        }

        // Minimum gap above low bass so low-register intervals never sound muddy
        val minUpperMidi = maxOf(48, if (bassMidi < 48) bassMidi + 5 else bassMidi + 3)
        val voicedUpper = mutableListOf<Int>()
        for (pc in upperPcs) {
            var candidate = 48 + pc // C3(48) .. B3(59)
            while (candidate < minUpperMidi) {
                candidate += 12
            }
            while (candidate > 64 && candidate - 12 >= minUpperMidi) {
                candidate -= 12
            }
            // Keep upper extensions <= F4(65)
            if (candidate > 65 && candidate - 12 > bassMidi) {
                candidate -= 12
            }
            if (candidate != bassMidi) {
                voicedUpper.add(candidate)
            }
        }
        val sortedUpper = voicedUpper.distinct().sorted().take(4)

        val result = ArrayList<Pair<Int, Float>>(1 + sortedUpper.size)
        // Warm foundational bass + gentle velvet upper chord pad
        result.add(bassMidi to 0.54f)
        sortedUpper.forEachIndexed { idx, midi ->
            val weight = if (idx == sortedUpper.lastIndex) 0.46f else 0.43f
            result.add(midi to weight)
        }
        return result
    }

    /**
     * Formats voiced MIDI notes as a readable note list (e.g. `C3 · E3 · G3 · B3`) for live UI feedback.
     */
    fun formatVoicedNoteNames(parsed: ParsedChordSymbol, preferFlats: Boolean = false): String {
        val voiced = voiceChordForAccompaniment(parsed)
        return voiced.joinToString(" · ") { (midi, _) ->
            MusicalNotationHelper.midiToNoteName(midi, preferFlats)
        }
    }
}
