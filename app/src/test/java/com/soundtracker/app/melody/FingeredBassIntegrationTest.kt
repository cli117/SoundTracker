package com.soundtracker.app.melody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingeredBassIntegrationTest {

    @Test
    fun fingeredBassEnumHasExpectedPresetAndDryLowEndConfig() {
        val bass = StudioInstrument.FINGERED_BASS
        assertEquals(0, bass.bank)
        assertEquals(33, bass.presetNumber)
        assertEquals(2, bass.defaultOctave)
        assertEquals(1.8f, bass.gainDb, 0.001f)
        assertTrue(bass.defaultReverb in 0.14f..0.20f)
        assertEquals(0.0f, bass.chorusDepth, 0.0001f)

        val bassDefaultWhiteKey = MusicalNotationHelper.octaveToWhiteKeyIndex(bass.defaultOctave)
        assertEquals(7f, bassDefaultWhiteKey, 0.001f)
        assertEquals(36, MusicalNotationHelper.whiteKeyIndexToMidi(bassDefaultWhiteKey.toInt()))
    }

    @Test
    fun singleStaffUsesBassClefOnlyWhenSingleStaffBassDominates() {
        assertTrue(
            MelodyStudioUiState(
                grandStaffEnabled = false,
                dualKeyboardEnabled = false,
                instrument = StudioInstrument.FINGERED_BASS
            ).singleStaffUsesBassClef
        )

        // Grand Staff always uses Treble upper + Bass lower, never single-staff Bass Clef flag
        assertFalse(
            MelodyStudioUiState(
                grandStaffEnabled = true,
                dualKeyboardEnabled = false,
                instrument = StudioInstrument.FINGERED_BASS
            ).singleStaffUsesBassClef
        )

        // Single-staff Dual Keyboard with mixed Piano + Bass remains Treble to avoid misreading upper notes
        assertFalse(
            MelodyStudioUiState(
                grandStaffEnabled = false,
                dualKeyboardEnabled = true,
                instrument = StudioInstrument.FINGERED_BASS,
                secondaryInstrument = StudioInstrument.GRAND_PIANO
            ).singleStaffUsesBassClef
        )

        // Single-staff Dual Keyboard with Bass on both slots uses Bass Clef cleanly
        assertTrue(
            MelodyStudioUiState(
                grandStaffEnabled = false,
                dualKeyboardEnabled = true,
                instrument = StudioInstrument.FINGERED_BASS,
                secondaryInstrument = StudioInstrument.FINGERED_BASS
            ).singleStaffUsesBassClef
        )
    }

    @Test
    fun keySignatureStepsMapProperlyOntoBassClef() {
        val gMajor = KeySignature(fifths = 1)
        val bassSharps = gMajor.signatureStaffSteps(isBass = true)
        val trebleSharps = gMajor.signatureStaffSteps(isBass = false)
        assertEquals(listOf(24), bassSharps)   // F3 on 4th line of Bass Clef
        assertEquals(listOf(38), trebleSharps) // F5 on top line of Treble Clef

        val cancelSteps = gMajor.cancelledStaffStepsTo(KeySignature.C_MAJOR, isBass = true)
        assertEquals(listOf(24), cancelSteps)
    }

    @Test
    fun singleStaffBassCompilesConcertPitchAndRoutesChordsToPiano() {
        val melody = MelodyStudioUiState(
            grandStaffEnabled = false,
            dualKeyboardEnabled = false,
            instrument = StudioInstrument.FINGERED_BASS,
            secondaryInstrument = StudioInstrument.GRAND_PIANO,
            bpm = 120,
            totalBars = 2,
            chordPlaybackEnabled = true,
            chordSymbols = listOf(ScoreChordSymbol(tick = 0, symbol = "C")),
            notes = listOf(
                MelodyScoreNote(
                    id = "b_c2",
                    midiNote = 36,
                    startBeat = 0f,
                    durationBeats = 1f,
                    startTickExact = 0,
                    durationTicksExact = TICKS_PER_BEAT,
                    keyboardSlot = 0
                ),
                MelodyScoreNote(
                    id = "b_e2",
                    midiNote = 40,
                    startBeat = 1f,
                    durationBeats = 1f,
                    startTickExact = TICKS_PER_BEAT,
                    durationTicksExact = TICKS_PER_BEAT,
                    keyboardSlot = 0
                ),
                MelodyScoreNote(
                    id = "b_g2",
                    midiNote = 43,
                    startBeat = 2f,
                    durationBeats = 1f,
                    startTickExact = TICKS_PER_BEAT * 2,
                    durationTicksExact = TICKS_PER_BEAT,
                    keyboardSlot = 0
                ),
                MelodyScoreNote(
                    id = "b_c3",
                    midiNote = 48,
                    startBeat = 3f,
                    durationBeats = 1f,
                    startTickExact = TICKS_PER_BEAT * 3,
                    durationTicksExact = TICKS_PER_BEAT,
                    keyboardSlot = 0
                ),
                MelodyScoreNote(
                    id = "b_g3",
                    midiNote = 55,
                    startBeat = 4f,
                    durationBeats = 1f,
                    startTickExact = TICKS_PER_BEAT * 4,
                    durationTicksExact = TICKS_PER_BEAT,
                    keyboardSlot = 0
                )
            )
        )

        assertEquals(1, melody.resolveChordAccompanimentSlot())

        val compiled = ScoreExpressionCompiler.compilePlaybackSchedule(melody, MELODY_SAMPLE_RATE)
        val bassEvents = compiled.notes.filter { it.slot == 0 }
        val chordEvents = compiled.notes.filter { it.slot == 1 }

        assertEquals(listOf(36, 40, 43, 48, 55), bassEvents.map { it.midiNote })
        assertTrue(chordEvents.isNotEmpty())
    }

    @Test
    fun grandStaffPianoPlusBassRoutesBasslineToSlot1AndChordsToPianoSlot0() {
        val melody = MelodyStudioUiState(
            grandStaffEnabled = true,
            dualKeyboardEnabled = true,
            instrument = StudioInstrument.GRAND_PIANO,
            secondaryInstrument = StudioInstrument.FINGERED_BASS,
            bpm = 120,
            totalBars = 1,
            chordPlaybackEnabled = true,
            chordSymbols = listOf(ScoreChordSymbol(tick = 0, symbol = "Am7")),
            notes = listOf(
                MelodyScoreNote(
                    id = "rh_1",
                    midiNote = 64,
                    startBeat = 0f,
                    durationBeats = 1f,
                    startTickExact = 0,
                    durationTicksExact = TICKS_PER_BEAT,
                    keyboardSlot = 0,
                    targetStaff = StaffPart.TREBLE
                ),
                MelodyScoreNote(
                    id = "lh_1",
                    midiNote = 45,
                    startBeat = 0f,
                    durationBeats = 2f,
                    startTickExact = 0,
                    durationTicksExact = TICKS_PER_BEAT * 2,
                    keyboardSlot = 1,
                    targetStaff = StaffPart.BASS
                ),
                MelodyScoreNote(
                    id = "lh_2",
                    midiNote = 36,
                    startBeat = 2f,
                    durationBeats = 2f,
                    startTickExact = TICKS_PER_BEAT * 2,
                    durationTicksExact = TICKS_PER_BEAT * 2,
                    keyboardSlot = 1,
                    targetStaff = StaffPart.BASS
                )
            )
        )

        assertEquals(0, melody.resolveChordAccompanimentSlot())

        val compiled = ScoreExpressionCompiler.compilePlaybackSchedule(melody, MELODY_SAMPLE_RATE)
        val bassSlotNotes = compiled.notes.filter { it.slot == 1 }.sortedBy { it.startFrame }
        val pianoSlotNotes = compiled.notes.filter { it.slot == 0 }

        assertEquals(listOf(45, 36), bassSlotNotes.map { it.midiNote })
        assertTrue(pianoSlotNotes.any { it.midiNote == 64 })
        assertTrue(pianoSlotNotes.size > 1) // melody + Am7 chord voicing on Piano slot 0
    }

    @Test
    fun fingerprintAndUndoSnapshotTrackFingeredBassSelection() {
        val base = MelodyStudioUiState()
        val withBassPrimary = base.copy(instrument = StudioInstrument.FINGERED_BASS)
        val withBassSecondary = base.copy(secondaryInstrument = StudioInstrument.FINGERED_BASS)

        assertNotEquals(
            MelodyProjectRepository.computeContentFingerprint(base),
            MelodyProjectRepository.computeContentFingerprint(withBassPrimary)
        )
        assertNotEquals(
            MelodyProjectRepository.computeContentFingerprint(base),
            MelodyProjectRepository.computeContentFingerprint(withBassSecondary)
        )

        val snapshot = MelodySnapshot(
            notes = base.notes,
            cursorTick = 0,
            totalBars = 4,
            scoreTitle = "Bass Groove",
            grandStaffEnabled = true,
            activeStaff = StaffPart.BASS,
            dualKeyboardStaffRouting = DualKeyboardStaffRouting.SPLIT_BY_DECK,
            timeSignature = TimeSignature.FOUR_FOUR,
            initialKeySignature = KeySignature.C_MAJOR,
            keySignatureEvents = emptyList(),
            bpm = 100,
            swingPreset = SwingPreset.OFF,
            instrument = StudioInstrument.GRAND_PIANO,
            secondaryInstrument = StudioInstrument.FINGERED_BASS,
            dualKeyboardEnabled = true,
            reverbPreset = ReverbPreset.ROOM,
            sustainPedal = false,
            secondarySustainPedal = false,
            sustainDurationSec = DEFAULT_SUSTAIN_DURATION_SEC,
            secondarySustainDurationSec = DEFAULT_SUSTAIN_DURATION_SEC,
            chordSymbols = emptyList(),
            showChordSymbols = true,
            chordPlaybackEnabled = true,
            dynamicMarks = emptyList(),
            hairpins = emptyList(),
            tempoEvents = emptyList()
        )
        assertEquals(StudioInstrument.FINGERED_BASS, snapshot.secondaryInstrument)
        assertEquals(StaffPart.BASS, snapshot.activeStaff)
    }
}
