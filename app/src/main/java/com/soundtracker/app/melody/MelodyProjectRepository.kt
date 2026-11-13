package com.soundtracker.app.melody

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Versioned JSON serialization/deserialization, multi-project local storage (`filesDir/melody_projects`),
 * crash-safe atomic real-time auto-save protection, and `.stscore.json` export/share/import for Melody Studio (`乐器打谱`).
 */
object MelodyProjectRepository {

    const val SCHEMA_VERSION = 1
    const val PROJECT_FILE_EXT = ".stscore.json"
    private const val PROJECTS_DIR_NAME = "melody_projects"
    private const val AUTOSAVE_FILE_NAME = "autosave_latest.stscore.json"
    private const val SHARE_STAGING_SUBDIR = "wechat_share_staging"
    const val AUTOSAVE_PROJECT_ID = "__autosave_draft__"

    private val ioLock = Any()

    data class LoadedMelodyProject(
        val projectId: String?,
        val savedAtMs: Long,
        val state: MelodyStudioUiState
    )

    data class AutoPersistOutcome(
        val projectId: String?,
        val savedAtMs: Long,
        val savedProjects: List<MelodyProjectSummary>,
        val autoDraftSummary: MelodyProjectSummary?
    )

    private fun projectsDir(context: Context): File =
        File(context.filesDir, PROJECTS_DIR_NAME).apply { mkdirs() }

    private fun projectFileForId(context: Context, projectId: String): File {
        val safeId = projectId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(projectsDir(context), "proj_${safeId}$PROJECT_FILE_EXT")
    }

    private fun autoSaveFile(context: Context): File =
        File(projectsDir(context), AUTOSAVE_FILE_NAME)

    /**
     * Crash-safe atomic write: writes UTF-8 JSON to `<target>.tmp`, flushes kernel buffers (`fd.sync()`),
     * then atomically renames over [target] so killing the app mid-write never corrupts or truncates data.
     */
    private fun writeTextAtomic(target: File, content: String) {
        synchronized(ioLock) {
            val dir = target.parentFile?.apply { mkdirs() } ?: return
            val tmp = File(dir, "${target.name}.tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun readTextAtomicSafe(target: File): String? {
        synchronized(ioLock) {
            if (target.exists() && target.length() > 0L) {
                runCatching { target.readText(Charsets.UTF_8) }.getOrNull()?.let { text ->
                    if (text.isNotBlank()) return text
                }
            }
            val tmp = File(target.parentFile ?: return null, "${target.name}.tmp")
            if (tmp.exists() && tmp.length() > 0L) {
                return runCatching { tmp.readText(Charsets.UTF_8) }.getOrNull()
            }
            return null
        }
    }

    fun sanitizeDisplayTitle(rawTitle: String?): String {
        val t = rawTitle?.trim().orEmpty()
        return if (t.isEmpty()) "未命名乐谱" else t.take(64)
    }

    private fun sanitizeExportFileBaseName(rawTitle: String?): String {
        val clean = sanitizeDisplayTitle(rawTitle)
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
        return clean.ifBlank { "SoundTracker_乐谱" }
    }

    /**
     * Checks whether [state] has any user-authored content worth auto-saving or guarding on overwrite.
     */
    fun hasMeaningfulContent(state: MelodyStudioUiState): Boolean {
        return state.notes.isNotEmpty() ||
            state.chordSymbols.isNotEmpty() ||
            state.dynamicMarks.isNotEmpty() ||
            state.hairpins.isNotEmpty() ||
            state.tempoEvents.isNotEmpty() ||
            state.keySignatureEvents.isNotEmpty() ||
            state.grandStaffEnabled ||
            state.initialKeySignature != KeySignature.C_MAJOR ||
            state.scoreTitle.trim().let { it.isNotEmpty() && it != "未命名乐谱" }
    }

    /**
     * Deterministic content fingerprint used to detect unsaved edits (`isProjectDirty`) and trigger debounced auto-save.
     */
    fun computeContentFingerprint(state: MelodyStudioUiState): Int {
        var h = 17
        h = 31 * h + state.scoreTitle.hashCode()
        h = 31 * h + state.grandStaffEnabled.hashCode()
        h = 31 * h + state.dualKeyboardStaffRouting.ordinal
        h = 31 * h + state.timeSignature.hashCode()
        h = 31 * h + state.initialKeySignature.hashCode()
        h = 31 * h + state.keySignatureEvents.hashCode()
        h = 31 * h + state.bpm
        h = 31 * h + state.swingPreset.ordinal
        h = 31 * h + state.totalBars
        h = 31 * h + state.instrument.ordinal
        h = 31 * h + state.secondaryInstrument.ordinal
        h = 31 * h + state.dualKeyboardEnabled.hashCode()
        h = 31 * h + state.collapseScoreInDualMode.hashCode()
        h = 31 * h + state.primaryScrollWhiteKey.toBits()
        h = 31 * h + state.secondaryScrollWhiteKey.toBits()
        h = 31 * h + state.sustainPedal.hashCode()
        h = 31 * h + state.secondarySustainPedal.hashCode()
        h = 31 * h + state.sustainDurationSec.toBits()
        h = 31 * h + state.secondarySustainDurationSec.toBits()
        h = 31 * h + state.reverbPreset.ordinal
        h = 31 * h + state.metronomeEnabled.hashCode()
        h = 31 * h + state.loopPlayback.hashCode()
        h = 31 * h + state.notes.hashCode()
        h = 31 * h + state.dynamicMarks.hashCode()
        h = 31 * h + state.hairpins.hashCode()
        h = 31 * h + state.tempoEvents.hashCode()
        h = 31 * h + state.chordSymbols.hashCode()
        h = 31 * h + state.showChordSymbols.hashCode()
        h = 31 * h + state.chordPlaybackEnabled.hashCode()
        return h
    }

    /**
     * Extended session fingerprint including cursor position and score view mode (`整页谱`/`单行谱`),
     * so auto-save remembers even where on the page the user was reading/playing before app exit.
     */
    fun computeSessionPersistenceFingerprint(state: MelodyStudioUiState): Int {
        var h = computeContentFingerprint(state)
        h = 31 * h + state.cursorTick
        h = 31 * h + state.activeStaff.ordinal
        h = 31 * h + state.scoreViewMode.ordinal
        h = 31 * h + (state.currentProjectId?.hashCode() ?: 0)
        return h
    }

    /**
     * Serializes the complete [MelodyStudioUiState] into a versioned, human-readable JSON string (`schemaVersion = 1`).
     */
    fun serializeToJson(
        state: MelodyStudioUiState,
        projectId: String? = state.currentProjectId,
        savedAtMs: Long = System.currentTimeMillis()
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("appFormat", "SoundTrackerMelodyScore")
        if (!projectId.isNullOrBlank()) {
            root.put("projectId", projectId)
        }
        root.put("savedAtMs", savedAtMs)
        root.put("scoreTitle", sanitizeDisplayTitle(state.scoreTitle))
        root.put("scoreViewMode", state.scoreViewMode.name)
        root.put("grandStaffEnabled", state.grandStaffEnabled)
        root.put("activeStaff", state.activeStaff.name)
        root.put("dualKeyboardStaffRouting", state.dualKeyboardStaffRouting.name)
        root.put("showChordSymbols", state.showChordSymbols)
        root.put("chordPlaybackEnabled", state.chordPlaybackEnabled)

        // Time signature
        root.put("timeSignature", JSONObject().apply {
            put("numerator", state.timeSignature.safeNumerator)
            put("denominator", state.timeSignature.safeDenominator)
        })

        // Initial key signature & mid-score key changes
        root.put("initialKeySignature", JSONObject().apply {
            put("fifths", state.initialKeySignature.safeFifths)
            put("isMinor", state.initialKeySignature.isMinor)
        })
        root.put("keySignatureEvents", JSONArray().apply {
            for (ev in state.keySignatureEvents.sortedBy { it.safeBarIndex }) {
                put(JSONObject().apply {
                    put("id", ev.id)
                    put("barIndex", ev.safeBarIndex)
                    put("fifths", ev.keySignature.safeFifths)
                    put("isMinor", ev.keySignature.isMinor)
                })
            }
        })

        // Global timing & transport preferences
        root.put("bpm", state.bpm.coerceIn(40, 220))
        root.put("swingPreset", state.swingPreset.name)
        root.put("totalBars", state.totalBars.coerceIn(1, 64))
        root.put("cursorTick", state.cursorTick.coerceAtLeast(0))
        root.put("tupletAnchorTick", state.tupletAnchorTick.coerceAtLeast(0))
        root.put("inputMode", state.inputMode.name)
        root.put("noteDuration", state.noteDuration.name)
        if (state.activeTuplet != null) {
            root.put("activeTuplet", state.activeTuplet.name)
        }
        root.put("chordStepMode", state.chordStepMode)
        root.put("metronomeEnabled", state.metronomeEnabled)
        root.put("loopPlayback", state.loopPlayback)

        // Instruments, keyboards, sustain & reverb
        root.put("instrument", state.instrument.name)
        root.put("secondaryInstrument", state.secondaryInstrument.name)
        root.put("dualKeyboardEnabled", state.dualKeyboardEnabled)
        root.put("collapseScoreInDualMode", state.collapseScoreInDualMode)
        root.put("primaryScrollWhiteKey", state.primaryScrollWhiteKey.toDouble())
        root.put("secondaryScrollWhiteKey", state.secondaryScrollWhiteKey.toDouble())
        root.put("sustainPedal", state.sustainPedal)
        root.put("secondarySustainPedal", state.secondarySustainPedal)
        root.put("sustainDurationSec", state.sustainDurationSec.toDouble())
        root.put("secondarySustainDurationSec", state.secondarySustainDurationSec.toDouble())
        root.put("reverbPreset", state.reverbPreset.name)

        // Notes & rests (exact ticks, tuplets, slurs, articulations, accents, fermatas, keyboard slot)
        root.put("notes", JSONArray().apply {
            for (n in state.notes) {
                put(JSONObject().apply {
                    put("id", n.id)
                    put("midiNote", n.midiNote)
                    put("startBeat", n.startBeat.toDouble())
                    put("durationBeats", n.durationBeats.toDouble())
                    put("velocity", n.velocity.toDouble())
                    put("keyboardSlot", n.keyboardSlot)
                    put("isRest", n.isRest)
                    if (n.startTickExact != null) put("startTickExact", n.startTickExact)
                    if (n.durationTicksExact != null) put("durationTicksExact", n.durationTicksExact)
                    if (n.tuplet != null) {
                        put("tuplet", JSONObject().apply {
                            put("count", n.tuplet.count)
                            put("normalCount", n.tuplet.normalCount)
                            put("writtenBaseDenom", n.tuplet.writtenBaseDenom)
                            put("groupStartTick", n.tuplet.groupStartTick)
                            put("spanTicks", n.tuplet.spanTicks)
                        })
                    }
                    if (!n.slurGroupId.isNullOrBlank()) put("slurGroupId", n.slurGroupId)
                    if (n.articulation != null) put("articulation", n.articulation.name)
                    if (n.accent != null) put("accent", n.accent.name)
                    if (n.hasFermata) put("hasFermata", true)
                    if (n.targetStaff != null) put("targetStaff", n.targetStaff.name)
                })
            }
        })

        // Above-staff letter chord symbols (`谱表上方字母和弦标记`)
        root.put("chordSymbols", JSONArray().apply {
            for (cs in state.chordSymbols.sortedBy { it.safeTick }) {
                put(JSONObject().apply {
                    put("id", cs.id)
                    put("tick", cs.safeTick)
                    put("symbol", cs.cleanSymbol)
                })
            }
        })

        // Dynamic marks
        root.put("dynamicMarks", JSONArray().apply {
            for (dm in state.dynamicMarks) {
                put(JSONObject().apply {
                    put("id", dm.id)
                    put("tick", dm.tick)
                    put("level", dm.level.name)
                })
            }
        })

        // Hairpins (`cresc.` / `dim.`)
        root.put("hairpins", JSONArray().apply {
            for (hp in state.hairpins) {
                put(JSONObject().apply {
                    put("id", hp.id)
                    put("startTick", hp.startTick)
                    put("endTick", hp.endTick)
                    put("type", hp.type.name)
                })
            }
        })

        // Tempo map events (`♩=xx`, `rit.`, `accel.`, `a tempo`)
        root.put("tempoEvents", JSONArray().apply {
            for (te in state.tempoEvents) {
                put(JSONObject().apply {
                    put("id", te.id)
                    put("startTick", te.startTick)
                    put("endTick", te.endTick)
                    put("kind", te.kind.name)
                    put("bpm", te.bpm)
                    if (te.targetBpm != null) put("targetBpm", te.targetBpm)
                    if (te.term != null) put("term", te.term.name)
                })
            }
        })

        return root.toString(2)
    }

    /**
     * Deserializes a `.stscore.json` payload with full forward/backward-compatible fallbacks.
     */
    fun deserializeFromJson(jsonText: String): LoadedMelodyProject? {
        return runCatching {
            val root = JSONObject(jsonText)
            val savedAtMs = root.optLong("savedAtMs", System.currentTimeMillis())
            val rawProjectId = root.optString("projectId", "").takeIf { it.isNotBlank() }
            val title = sanitizeDisplayTitle(root.optString("scoreTitle", "未命名乐谱"))

            val tsObj = root.optJSONObject("timeSignature")
            val timeSignature = if (tsObj != null) {
                TimeSignature(
                    numerator = tsObj.optInt("numerator", 4),
                    denominator = tsObj.optInt("denominator", 4)
                ).sanitized()
            } else {
                TimeSignature.FOUR_FOUR
            }

            val initKsObj = root.optJSONObject("initialKeySignature")
            val initialKeySignature = if (initKsObj != null) {
                KeySignature(
                    fifths = initKsObj.optInt("fifths", 0).coerceIn(-7, 7),
                    isMinor = initKsObj.optBoolean("isMinor", false)
                )
            } else {
                KeySignature.C_MAJOR
            }

            val keySignatureEvents = mutableListOf<ScoreKeySignatureEvent>()
            val ksArr = root.optJSONArray("keySignatureEvents")
            if (ksArr != null) {
                val byBar = linkedMapOf<Int, ScoreKeySignatureEvent>()
                for (i in 0 until ksArr.length()) {
                    val obj = ksArr.optJSONObject(i) ?: continue
                    val bIdx = obj.optInt("barIndex", -1)
                    if (bIdx < 1) continue
                    val ev = ScoreKeySignatureEvent(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        barIndex = bIdx,
                        keySignature = KeySignature(
                            fifths = obj.optInt("fifths", 0).coerceIn(-7, 7),
                            isMinor = obj.optBoolean("isMinor", false)
                        )
                    )
                    byBar[bIdx] = ev
                }
                keySignatureEvents.addAll(byBar.values.sortedBy { it.safeBarIndex })
            }

            val bpm = root.optInt("bpm", 120).coerceIn(40, 220)
            val swingPreset = safeEnumValueOf(root.optString("swingPreset"), SwingPreset.OFF)
            val rawTotalBars = root.optInt("totalBars", 4).coerceIn(1, 64)
            val inputMode = safeEnumValueOf(root.optString("inputMode"), MelodyInputMode.STEP_INPUT)
            val noteDuration = safeEnumValueOf(root.optString("noteDuration"), NoteDurationPreset.QUARTER)
            val activeTupletName = root.optString("activeTuplet", "")
            val activeTuplet = if (activeTupletName.isNotBlank()) {
                runCatching { TupletPreset.valueOf(activeTupletName) }.getOrNull()
            } else {
                null
            }

            val instrument = safeEnumValueOf(root.optString("instrument"), StudioInstrument.GRAND_PIANO)
            val secondaryInstrument = safeEnumValueOf(root.optString("secondaryInstrument"), StudioInstrument.GRAND_PIANO)
            val dualKeyboardEnabled = root.optBoolean("dualKeyboardEnabled", false)
            val collapseScoreInDualMode = root.optBoolean("collapseScoreInDualMode", false)
            val primaryScrollWhiteKey = root.optDouble("primaryScrollWhiteKey", 21.0).toFloat().coerceIn(0f, 47f)
            val secondaryScrollWhiteKey = root.optDouble("secondaryScrollWhiteKey", 14.0).toFloat().coerceIn(0f, 47f)
            val sustainPedal = root.optBoolean("sustainPedal", false)
            val secondarySustainPedal = root.optBoolean("secondarySustainPedal", false)
            val sustainDurationSec = root.optDouble(
                "sustainDurationSec",
                DEFAULT_SUSTAIN_DURATION_SEC.toDouble()
            ).toFloat().coerceIn(0.030f, 10.000f)
            val secondarySustainDurationSec = root.optDouble(
                "secondarySustainDurationSec",
                DEFAULT_SUSTAIN_DURATION_SEC.toDouble()
            ).toFloat().coerceIn(0.030f, 10.000f)
            val reverbPreset = safeEnumValueOf(root.optString("reverbPreset"), ReverbPreset.ROOM)
            val chordStepMode = root.optBoolean("chordStepMode", false)
            val metronomeEnabled = root.optBoolean("metronomeEnabled", true)
            val loopPlayback = root.optBoolean("loopPlayback", false)

            val notes = mutableListOf<MelodyScoreNote>()
            val notesArr = root.optJSONArray("notes")
            if (notesArr != null) {
                for (i in 0 until notesArr.length()) {
                    val obj = notesArr.optJSONObject(i) ?: continue
                    val tupObj = obj.optJSONObject("tuplet")
                    val tuplet = if (tupObj != null) {
                        NoteTupletTag(
                            count = tupObj.optInt("count", 3).coerceAtLeast(2),
                            normalCount = tupObj.optInt("normalCount", 2).coerceAtLeast(1),
                            writtenBaseDenom = tupObj.optInt("writtenBaseDenom", 8),
                            groupStartTick = tupObj.optInt("groupStartTick", 0).coerceAtLeast(0),
                            spanTicks = tupObj.optInt("spanTicks", TICKS_PER_BEAT).coerceAtLeast(12)
                        )
                    } else {
                        null
                    }
                    val startExact = if (obj.has("startTickExact") && !obj.isNull("startTickExact")) {
                        obj.optInt("startTickExact").coerceAtLeast(0)
                    } else {
                        null
                    }
                    val durExact = if (obj.has("durationTicksExact") && !obj.isNull("durationTicksExact")) {
                        obj.optInt("durationTicksExact").coerceAtLeast(1)
                    } else {
                        null
                    }
                    val artName = obj.optString("articulation", "")
                    val articulation = if (artName.isNotBlank()) {
                        runCatching { NoteArticulation.valueOf(artName) }.getOrNull()
                    } else {
                        null
                    }
                    val accName = obj.optString("accent", "")
                    val accent = if (accName.isNotBlank()) {
                        runCatching { NoteAccent.valueOf(accName) }.getOrNull()
                    } else {
                        null
                    }
                    val staffName = obj.optString("targetStaff", "")
                    val targetStaff = if (staffName.isNotBlank()) {
                        runCatching { StaffPart.valueOf(staffName) }.getOrNull()
                    } else {
                        null
                    }
                    notes.add(
                        MelodyScoreNote(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            midiNote = obj.optInt("midiNote", 60).coerceIn(PIANO_MIN_MIDI, PIANO_MAX_MIDI),
                            startBeat = obj.optDouble("startBeat", 0.0).toFloat().coerceAtLeast(0f),
                            durationBeats = obj.optDouble("durationBeats", 1.0).toFloat().coerceAtLeast(0.02f),
                            velocity = obj.optDouble("velocity", 0.85).toFloat().coerceIn(0.05f, 1.0f),
                            keyboardSlot = obj.optInt("keyboardSlot", 0).coerceIn(0, 1),
                            isRest = obj.optBoolean("isRest", false),
                            startTickExact = startExact,
                            durationTicksExact = durExact,
                            tuplet = tuplet,
                            slurGroupId = obj.optString("slurGroupId", "").takeIf { it.isNotBlank() },
                            articulation = articulation,
                            accent = accent,
                            hasFermata = obj.optBoolean("hasFermata", false),
                            targetStaff = targetStaff
                        )
                    )
                }
            }
            notes.sortWith(compareBy<MelodyScoreNote> { it.resolvedStartTick }.thenBy { it.midiNote })

            val chordSymbols = mutableListOf<ScoreChordSymbol>()
            val csArr = root.optJSONArray("chordSymbols")
            if (csArr != null) {
                for (i in 0 until csArr.length()) {
                    val obj = csArr.optJSONObject(i) ?: continue
                    val sym = obj.optString("symbol", "").trim()
                    if (sym.isEmpty()) continue
                    chordSymbols.add(
                        ScoreChordSymbol(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            tick = obj.optInt("tick", 0).coerceAtLeast(0),
                            symbol = sym
                        )
                    )
                }
            }
            chordSymbols.sortBy { it.safeTick }

            val dynamicMarks = mutableListOf<ScoreDynamicMark>()
            val dmArr = root.optJSONArray("dynamicMarks")
            if (dmArr != null) {
                for (i in 0 until dmArr.length()) {
                    val obj = dmArr.optJSONObject(i) ?: continue
                    dynamicMarks.add(
                        ScoreDynamicMark(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            tick = obj.optInt("tick", 0).coerceAtLeast(0),
                            level = safeEnumValueOf(obj.optString("level"), DynamicLevel.MF)
                        )
                    )
                }
            }
            dynamicMarks.sortBy { it.tick }

            val hairpins = mutableListOf<ScoreHairpin>()
            val hpArr = root.optJSONArray("hairpins")
            if (hpArr != null) {
                for (i in 0 until hpArr.length()) {
                    val obj = hpArr.optJSONObject(i) ?: continue
                    val st = obj.optInt("startTick", 0).coerceAtLeast(0)
                    val et = obj.optInt("endTick", st + TICKS_PER_BEAT).coerceAtLeast(st + TICKS_PER_STEP)
                    hairpins.add(
                        ScoreHairpin(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            startTick = st,
                            endTick = et,
                            type = safeEnumValueOf(obj.optString("type"), HairpinType.CRESCENDO)
                        )
                    )
                }
            }
            hairpins.sortBy { it.startTick }

            val tempoEvents = mutableListOf<ScoreTempoEvent>()
            val teArr = root.optJSONArray("tempoEvents")
            if (teArr != null) {
                for (i in 0 until teArr.length()) {
                    val obj = teArr.optJSONObject(i) ?: continue
                    val st = obj.optInt("startTick", 0).coerceAtLeast(0)
                    val endTick = if (obj.has("endTick") && !obj.isNull("endTick")) {
                        obj.optInt("endTick").coerceAtLeast(st)
                    } else {
                        st
                    }
                    val targetBpm = if (obj.has("targetBpm") && !obj.isNull("targetBpm")) {
                        obj.optInt("targetBpm").coerceIn(32, 240)
                    } else {
                        null
                    }
                    val termName = obj.optString("term", "")
                    val term = if (termName.isNotBlank()) {
                        runCatching { TempoTerm.valueOf(termName) }.getOrNull()
                    } else {
                        null
                    }
                    tempoEvents.add(
                        ScoreTempoEvent(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            startTick = st,
                            endTick = endTick,
                            kind = safeEnumValueOf(obj.optString("kind"), TempoEventKind.STEADY_BPM),
                            bpm = obj.optInt("bpm", bpm).coerceIn(36, 240),
                            targetBpm = targetBpm,
                            term = term
                        )
                    )
                }
            }
            tempoEvents.sortBy { it.startTick }

            // Ensure totalBars covers all notes, chord symbols & mid-score key changes
            val maxNoteEndTick = maxOf(
                notes.maxOfOrNull { it.resolvedEndTick } ?: 0,
                chordSymbols.maxOfOrNull { it.safeTick + TICKS_PER_STEP } ?: 0
            )
            val minBarsForNotes = if (maxNoteEndTick > 0) {
                ((maxNoteEndTick + timeSignature.ticksPerBar - 1) / timeSignature.ticksPerBar).coerceAtLeast(1)
            } else {
                1
            }
            val minBarsForKeys = (keySignatureEvents.maxOfOrNull { it.safeBarIndex + 1 } ?: 1)
            val totalBars = maxOf(rawTotalBars, minBarsForNotes, minBarsForKeys).coerceIn(1, 64)
            val maxTick = (totalBars * timeSignature.ticksPerBar - TICKS_PER_STEP).coerceAtLeast(0)
            val cursorTick = root.optInt("cursorTick", 0).coerceIn(0, maxTick)
            val tupletAnchorTick = root.optInt("tupletAnchorTick", 0).coerceIn(0, maxTick)

            val scoreViewMode = safeEnumValueOf(
                root.optString("scoreViewMode"),
                ScoreViewMode.PAGE_SCROLL
            )
            // Backward compatibility with legacy archives: if `grandStaffEnabled` was not stored yet,
            // auto-enable Dual-Row Piano Grand Staff when the archive contains low bass notes (< G#3)
            val grandStaffEnabled = if (root.has("grandStaffEnabled")) {
                root.optBoolean("grandStaffEnabled", false)
            } else {
                instrument != StudioInstrument.FINGERED_BASS &&
                    notes.any { it.targetStaff == StaffPart.BASS || (!it.isRest && it.midiNote < 56) }
            }
            val activeStaff = safeEnumValueOf(root.optString("activeStaff"), StaffPart.TREBLE)
            val dualKeyboardStaffRouting = safeEnumValueOf(
                root.optString("dualKeyboardStaffRouting"),
                DualKeyboardStaffRouting.SPLIT_BY_DECK
            )
            val showChordSymbols = root.optBoolean("showChordSymbols", true)
            val chordPlaybackEnabled = root.optBoolean("chordPlaybackEnabled", true)

            val restoredState = MelodyStudioUiState(
                scoreTitle = title,
                scoreViewMode = scoreViewMode,
                grandStaffEnabled = grandStaffEnabled,
                activeStaff = activeStaff,
                dualKeyboardStaffRouting = dualKeyboardStaffRouting,
                instrument = instrument,
                secondaryInstrument = secondaryInstrument,
                dualKeyboardEnabled = dualKeyboardEnabled,
                collapseScoreInDualMode = collapseScoreInDualMode,
                primaryScrollWhiteKey = primaryScrollWhiteKey,
                secondaryScrollWhiteKey = secondaryScrollWhiteKey,
                inputMode = inputMode,
                timeSignature = timeSignature,
                initialKeySignature = initialKeySignature,
                keySignatureEvents = keySignatureEvents,
                bpm = bpm,
                swingPreset = swingPreset,
                totalBars = totalBars,
                noteDuration = noteDuration,
                activeTuplet = activeTuplet,
                tupletAnchorTick = tupletAnchorTick,
                chordStepMode = chordStepMode,
                reverbPreset = reverbPreset,
                sustainPedal = sustainPedal,
                secondarySustainPedal = secondarySustainPedal,
                sustainDurationSec = sustainDurationSec,
                secondarySustainDurationSec = secondarySustainDurationSec,
                metronomeEnabled = metronomeEnabled,
                loopPlayback = loopPlayback,
                cursorTick = cursorTick,
                notes = notes,
                dynamicMarks = dynamicMarks,
                hairpins = hairpins,
                tempoEvents = tempoEvents,
                chordSymbols = chordSymbols,
                showChordSymbols = showChordSymbols,
                chordPlaybackEnabled = chordPlaybackEnabled
            )
            LoadedMelodyProject(
                projectId = rawProjectId?.takeIf { it != AUTOSAVE_PROJECT_ID },
                savedAtMs = savedAtMs,
                state = restoredState
            )
        }.getOrNull()
    }

    private fun parseSummaryFromFile(file: File, fallbackId: String): MelodyProjectSummary? {
        val rawText = readTextAtomicSafe(file) ?: return null
        val loaded = runCatching { deserializeFromJson(rawText) }.getOrNull() ?: return null
        val st = loaded.state
        val keyLabel = if (st.keySignatureEvents.isEmpty()) {
            st.initialKeySignature.shortPillLabel
        } else {
            "${st.initialKeySignature.shortPillLabel} (+${st.keySignatureEvents.size}转调)"
        }
        return MelodyProjectSummary(
            id = loaded.projectId ?: fallbackId,
            title = st.scoreTitle,
            keySignatureLabel = keyLabel,
            timeSignatureLabel = st.timeSignature.label,
            bpm = st.bpm,
            totalBars = st.totalBars,
            pitchedNoteCount = st.notes.count { !it.isRest },
            updatedAtMs = loaded.savedAtMs.takeIf { it > 0L } ?: file.lastModified()
        )
    }

    fun listProjects(context: Context): List<MelodyProjectSummary> {
        synchronized(ioLock) {
            val dir = projectsDir(context)
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith("proj_") && f.name.endsWith(PROJECT_FILE_EXT)
            } ?: return emptyList()

            return files.mapNotNull { file ->
                val fallbackId = file.name.removePrefix("proj_").removeSuffix(PROJECT_FILE_EXT)
                parseSummaryFromFile(file, fallbackId)
            }.sortedByDescending { it.updatedAtMs }
        }
    }

    fun saveProject(
        context: Context,
        state: MelodyStudioUiState,
        existingProjectId: String? = null,
        customTitle: String? = null
    ): MelodyProjectSummary? {
        return runCatching {
            synchronized(ioLock) {
                val id = existingProjectId?.takeIf { it.isNotBlank() && it != AUTOSAVE_PROJECT_ID }
                    ?: UUID.randomUUID().toString()
                val cleanTitle = sanitizeDisplayTitle(customTitle ?: state.scoreTitle)
                val now = System.currentTimeMillis()
                val updatedState = state.copy(scoreTitle = cleanTitle, currentProjectId = id)
                val json = serializeToJson(updatedState, projectId = id, savedAtMs = now)
                val outFile = projectFileForId(context, id)
                writeTextAtomic(outFile, json)
                parseSummaryFromFile(outFile, id)
            }
        }.getOrNull()
    }

    fun loadProject(context: Context, projectId: String): LoadedMelodyProject? {
        val file = projectFileForId(context, projectId)
        val rawText = readTextAtomicSafe(file) ?: return null
        return runCatching {
            deserializeFromJson(rawText)?.copy(projectId = projectId)
        }.getOrNull()
    }

    fun renameProject(context: Context, projectId: String, newTitle: String): Boolean {
        return synchronized(ioLock) {
            val file = projectFileForId(context, projectId)
            val loaded = loadProject(context, projectId) ?: return@synchronized false
            val cleanTitle = sanitizeDisplayTitle(newTitle)
            val json = serializeToJson(
                loaded.state.copy(scoreTitle = cleanTitle),
                projectId = projectId,
                savedAtMs = System.currentTimeMillis()
            )
            runCatching {
                writeTextAtomic(file, json)
                true
            }.getOrDefault(false)
        }
    }

    fun deleteProject(context: Context, projectId: String): Boolean {
        return synchronized(ioLock) {
            val file = projectFileForId(context, projectId)
            val tmp = File(file.parentFile ?: projectsDir(context), "${file.name}.tmp")
            if (tmp.exists()) tmp.delete()
            if (file.exists()) file.delete() else false
        }
    }

    fun writeAutoSaveDraft(context: Context, state: MelodyStudioUiState) {
        runCatching {
            synchronized(ioLock) {
                val file = autoSaveFile(context)
                if (!hasMeaningfulContent(state) && state.currentProjectId == null) {
                    if (file.exists()) file.delete()
                    return@synchronized
                }
                val json = serializeToJson(
                    state = state,
                    projectId = state.currentProjectId ?: AUTOSAVE_PROJECT_ID,
                    savedAtMs = System.currentTimeMillis()
                )
                writeTextAtomic(file, json)
            }
        }
    }

    /**
     * Zero-manual-save real-time persistence:
     * - Automatically promotes any meaningful score to a durable `proj_<id>.stscore.json` file
     *   AND updates `autosave_latest.stscore.json` atomically (`tmp` + `fsync` + `rename`).
     * - If the workspace is completely blank and unlinked (`currentProjectId == null`), clears stale auto-draft
     *   so reopening after intentional "New Blank Score" opens blank cleanly while keeping all saved projects intact.
     */
    fun autoPersistActiveSession(context: Context, state: MelodyStudioUiState): AutoPersistOutcome {
        return synchronized(ioLock) {
            val now = System.currentTimeMillis()
            val meaningful = hasMeaningfulContent(state)
            val activeId = state.currentProjectId?.takeIf { it.isNotBlank() && it != AUTOSAVE_PROJECT_ID }
                ?: if (meaningful) UUID.randomUUID().toString() else null

            val autoFile = autoSaveFile(context)
            if (activeId != null) {
                val stateWithId = state.copy(currentProjectId = activeId)
                val json = serializeToJson(stateWithId, projectId = activeId, savedAtMs = now)
                writeTextAtomic(projectFileForId(context, activeId), json)
                writeTextAtomic(autoFile, json)
            } else {
                if (autoFile.exists()) autoFile.delete()
            }

            AutoPersistOutcome(
                projectId = activeId,
                savedAtMs = if (activeId != null) now else 0L,
                savedProjects = listProjects(context),
                autoDraftSummary = readAutoSaveSummary(context)
            )
        }
    }

    fun readAutoSaveDraft(context: Context): LoadedMelodyProject? {
        val file = autoSaveFile(context)
        val rawText = readTextAtomicSafe(file) ?: return null
        return runCatching { deserializeFromJson(rawText) }.getOrNull()
    }

    fun readAutoSaveSummary(context: Context): MelodyProjectSummary? {
        val file = autoSaveFile(context)
        return parseSummaryFromFile(file, AUTOSAVE_PROJECT_ID)?.copy(id = AUTOSAVE_PROJECT_ID)
    }

    /**
     * Stages `.stscore.json` into `cacheDir/wechat_share_staging` and returns a share [Intent]
     * (supporting both WeChat direct send and system chooser).
     */
    fun prepareProjectShareIntent(
        context: Context,
        jsonContent: String,
        displayTitle: String,
        targetWeChatDirectly: Boolean
    ): Intent? {
        return runCatching {
            val stagingRoot = File(context.cacheDir, SHARE_STAGING_SUBDIR).apply { mkdirs() }
            val slotDir = File(stagingRoot, "share_${System.currentTimeMillis()}").apply { mkdirs() }
            val fileName = "${sanitizeExportFileBaseName(displayTitle)}$PROJECT_FILE_EXT"
            val outFile = File(slotDir, fileName)
            outFile.writeText(jsonContent)

            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, outFile)

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_TITLE, fileName)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                clipData = ClipData.newRawUri(fileName, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val weChatInstalled = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo("com.tencent.mm", PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo("com.tencent.mm", 0)
                }
                true
            }.getOrDefault(false)

            if (targetWeChatDirectly && weChatInstalled) {
                context.grantUriPermission(
                    "com.tencent.mm",
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val weChatDirect = Intent(sendIntent).apply {
                    setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val canResolveDirect = context.packageManager
                    .queryIntentActivities(weChatDirect, PackageManager.MATCH_DEFAULT_ONLY)
                    .isNotEmpty()
                if (canResolveDirect) {
                    weChatDirect
                } else {
                    Intent(sendIntent).apply {
                        setPackage("com.tencent.mm")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            } else {
                Intent.createChooser(sendIntent, "分享乐谱工程文件 ($fileName)")
            }
        }.getOrNull()
    }

    /**
     * Exports `.stscore.json` directly into public `Download/SoundTracker/` via MediaStore (API 29+) or legacy Downloads.
     */
    fun saveProjectJsonToDownloads(
        context: Context,
        jsonContent: String,
        displayTitle: String
    ): String? {
        val fileName = "${sanitizeExportFileBaseName(displayTitle)}_${System.currentTimeMillis() % 100000}$PROJECT_FILE_EXT"
        val bytes = jsonContent.toByteArray(Charsets.UTF_8)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/SoundTracker"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: return null
                val doneValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                "Download/SoundTracker/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "SoundTracker"
                ).apply { mkdirs() }
                val dest = File(dir, fileName)
                dest.writeBytes(bytes)
                "Download/SoundTracker/$fileName"
            }
        }.getOrNull()
    }

    fun saveProjectJsonToUri(context: Context, jsonContent: String, destUri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openOutputStream(destUri, "wt")?.use { out ->
                out.write(jsonContent.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return false
            true
        }.getOrDefault(false)
    }

    fun importProjectFromUri(context: Context, srcUri: Uri): LoadedMelodyProject? {
        return runCatching {
            val text = context.contentResolver.openInputStream(srcUri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return null
            deserializeFromJson(text)
        }.getOrNull()
    }

    private inline fun <reified T : Enum<T>> safeEnumValueOf(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }
}
