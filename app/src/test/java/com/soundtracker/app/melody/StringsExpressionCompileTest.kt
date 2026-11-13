package com.soundtracker.app.melody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringsExpressionCompileTest {

    @Test
    fun contiguousNotesOnSlotSetContiguousNextAndGappedNotesDoNot() {
        val melody = MelodyStudioUiState(
            instrument = StudioInstrument.STRINGS_ENSEMBLE,
            bpm = 120,
            totalBars = 2,
            notes = listOf(
                MelodyScoreNote(
                    id = "n1",
                    midiNote = 60,
                    startBeat = 0f,
                    durationBeats = 1f,
                    startTickExact = 0,
                    durationTicksExact = TICKS_PER_BEAT
                ),
                MelodyScoreNote(
                    id = "n2",
                    midiNote = 62,
                    startBeat = 1f,
                    durationBeats = 1f,
                    startTickExact = TICKS_PER_BEAT,
                    durationTicksExact = TICKS_PER_BEAT
                ),
                // Gap of 1 beat (rest between tick 480 and tick 720)
                MelodyScoreNote(
                    id = "n3",
                    midiNote = 64,
                    startBeat = 3f,
                    durationBeats = 1f,
                    startTickExact = TICKS_PER_BEAT * 3,
                    durationTicksExact = TICKS_PER_BEAT
                )
            )
        )

        val compiled = ScoreExpressionCompiler.compilePlaybackSchedule(melody, MELODY_SAMPLE_RATE)
        assertEquals(3, compiled.notes.size)

        val n1 = compiled.notes[0]
        val n2 = compiled.notes[1]
        val n3 = compiled.notes[2]

        assertTrue((n1.flags and NoteSynthFlags.CONTIGUOUS_NEXT) != 0)
        assertEquals(0, n2.flags and NoteSynthFlags.CONTIGUOUS_NEXT)
        assertEquals(0, n3.flags and NoteSynthFlags.CONTIGUOUS_NEXT)
    }

    @Test
    fun stringsSlurEndHandlesContiguousAndRestEndUnlikePiano() {
        val contiguousNotes = listOf(
            MelodyScoreNote(
                id = "s1",
                midiNote = 60,
                startBeat = 0f,
                durationBeats = 1f,
                startTickExact = 0,
                durationTicksExact = TICKS_PER_BEAT,
                slurGroupId = "slur_1"
            ),
            MelodyScoreNote(
                id = "s2",
                midiNote = 64,
                startBeat = 1f,
                durationBeats = 1f,
                startTickExact = TICKS_PER_BEAT,
                durationTicksExact = TICKS_PER_BEAT,
                slurGroupId = "slur_1"
            ),
            MelodyScoreNote(
                id = "next",
                midiNote = 67,
                startBeat = 2f,
                durationBeats = 1f,
                startTickExact = TICKS_PER_BEAT * 2,
                durationTicksExact = TICKS_PER_BEAT
            )
        )
        val restEndNotes = contiguousNotes.subList(0, 2)

        val stringsContig = ScoreExpressionCompiler.compilePlaybackSchedule(
            MelodyStudioUiState(
                instrument = StudioInstrument.STRINGS_ENSEMBLE,
                bpm = 120,
                totalBars = 2,
                notes = contiguousNotes
            ),
            MELODY_SAMPLE_RATE
        )
        val stringsRestEnd = ScoreExpressionCompiler.compilePlaybackSchedule(
            MelodyStudioUiState(
                instrument = StudioInstrument.STRINGS_ENSEMBLE,
                bpm = 120,
                totalBars = 2,
                notes = restEndNotes
            ),
            MELODY_SAMPLE_RATE
        )
        val pianoRestEnd = ScoreExpressionCompiler.compilePlaybackSchedule(
            MelodyStudioUiState(
                instrument = StudioInstrument.GRAND_PIANO,
                bpm = 120,
                totalBars = 2,
                notes = restEndNotes
            ),
            MELODY_SAMPLE_RATE
        )

        // At 120 BPM & 44100 Hz, 1 beat = 22050 frames
        val stringsContigEnd = stringsContig.notes[1]
        assertEquals(22050L, stringsContigEnd.durationFrames)
        assertEquals(0, stringsContigEnd.flags and NoteSynthFlags.PORTATO_RELEASE)
        assertTrue((stringsContigEnd.flags and NoteSynthFlags.CONTIGUOUS_NEXT) != 0)

        val stringsRestSlurEnd = stringsRestEnd.notes[1]
        assertEquals(20727L, stringsRestSlurEnd.durationFrames) // 22050 * 0.94
        assertEquals(0, stringsRestSlurEnd.flags and NoteSynthFlags.PORTATO_RELEASE)
        assertEquals(0, stringsRestSlurEnd.flags and NoteSynthFlags.CONTIGUOUS_NEXT)

        val pianoSlurEnd = pianoRestEnd.notes[1]
        assertEquals(19404L, pianoSlurEnd.durationFrames) // 22050 * 0.88
        assertTrue((pianoSlurEnd.flags and NoteSynthFlags.PORTATO_RELEASE) != 0)
    }

    @Test
    fun stringsChordSymbolsKeepFullDurationEvenOnFinalChordWithoutPortato() {
        val melody = MelodyStudioUiState(
            instrument = StudioInstrument.STRINGS_ENSEMBLE,
            bpm = 120,
            totalBars = 1,
            chordPlaybackEnabled = true,
            chordSymbols = listOf(
                ScoreChordSymbol(tick = 0, symbol = "C"),
                ScoreChordSymbol(tick = TICKS_PER_BEAT, symbol = "G")
            )
        )

        val compiled = ScoreExpressionCompiler.compilePlaybackSchedule(melody, MELODY_SAMPLE_RATE)
        val firstChordNotes = compiled.notes.filter { it.startFrame == 0L }
        val finalChordNotes = compiled.notes.filter { it.startFrame == 22050L }
        assertTrue(firstChordNotes.isNotEmpty())
        assertTrue(finalChordNotes.isNotEmpty())

        firstChordNotes.forEach { note ->
            assertEquals(22050L, note.durationFrames)
            assertEquals(0, note.flags and NoteSynthFlags.PORTATO_RELEASE)
            assertTrue((note.flags and NoteSynthFlags.CONTIGUOUS_NEXT) != 0)
        }
        // Second chord spans beats 1..4 (3 beats = 66150 frames) with full sustain & no PORTATO_RELEASE
        finalChordNotes.forEach { note ->
            assertEquals(66150L, note.durationFrames)
            assertEquals(0, note.flags and NoteSynthFlags.PORTATO_RELEASE)
            assertEquals(0, note.flags and NoteSynthFlags.CONTIGUOUS_NEXT)
        }
    }

    @Test
    fun staccatoAndMarcatoOnStringsPreserveCrispReleaseAndClearContiguousNext() {
        val melody = MelodyStudioUiState(
            instrument = StudioInstrument.STRINGS_ENSEMBLE,
            bpm = 120,
            totalBars = 2,
            notes = listOf(
                MelodyScoreNote(
                    id = "stacc",
                    midiNote = 60,
                    startBeat = 0f,
                    durationBeats = 1f,
                    startTickExact = 0,
                    durationTicksExact = TICKS_PER_BEAT,
                    articulation = NoteArticulation.STACCATO
                ),
                MelodyScoreNote(
                    id = "marc",
                    midiNote = 62,
                    startBeat = 1f,
                    durationBeats = 1f,
                    startTickExact = TICKS_PER_BEAT,
                    durationTicksExact = TICKS_PER_BEAT,
                    accent = NoteAccent.MARCATO
                ),
                MelodyScoreNote(
                    id = "tail",
                    midiNote = 64,
                    startBeat = 2f,
                    durationBeats = 1f,
                    startTickExact = TICKS_PER_BEAT * 2,
                    durationTicksExact = TICKS_PER_BEAT
                )
            )
        )

        val compiled = ScoreExpressionCompiler.compilePlaybackSchedule(melody, MELODY_SAMPLE_RATE)
        assertEquals(3, compiled.notes.size)

        val stacc = compiled.notes[0]
        val marc = compiled.notes[1]

        assertTrue((stacc.flags and NoteSynthFlags.CRISP_RELEASE) != 0)
        assertEquals(0, stacc.flags and NoteSynthFlags.CONTIGUOUS_NEXT)

        assertTrue((marc.flags and NoteSynthFlags.CRISP_RELEASE) != 0)
        assertEquals(0, marc.flags and NoteSynthFlags.CONTIGUOUS_NEXT)
    }
}
