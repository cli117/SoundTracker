package com.soundtracker.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.soundtracker.app.arranger.ARRANGER_SAMPLE_RATE
import com.soundtracker.app.arranger.AppWorkspaceMode
import com.soundtracker.app.arranger.ArrangerClip
import com.soundtracker.app.arranger.ArrangerSnapshot
import com.soundtracker.app.arranger.ArrangerTrack
import com.soundtracker.app.arranger.ArrangerUiState
import com.soundtracker.app.arranger.MAX_ARRANGER_TRACKS
import com.soundtracker.app.arranger.MIN_CLIP_DURATION_MS
import com.soundtracker.app.arranger.MultiTrackRealtimePlayer
import com.soundtracker.app.audio.AudioCodecHelper
import com.soundtracker.app.audio.NetEaseCloudMusicManager
import com.soundtracker.app.audio.NetEaseLocalTrack
import com.soundtracker.app.audio.RealtimePitchAudioProcessor
import com.soundtracker.app.audio.RealtimeVolumeAudioProcessor
import com.soundtracker.app.audio.buildRealtimePreviewExoPlayer
import com.soundtracker.app.dsp.AudioMetrics
import com.soundtracker.app.dsp.ExportFormat
import com.soundtracker.app.dsp.NativeAudioEngine
import com.soundtracker.app.dsp.VocalEngineMode
import com.soundtracker.app.dsp.VocalSeparationOutcome
import com.soundtracker.app.melody.DEFAULT_REST_MIDI
import com.soundtracker.app.melody.DualKeyboardStaffRouting
import com.soundtracker.app.melody.DynamicLevel
import com.soundtracker.app.melody.HairpinType
import com.soundtracker.app.melody.KeySignature
import com.soundtracker.app.melody.KeyboardLayoutPreferences
import com.soundtracker.app.melody.MAX_MELODY_BARS
import com.soundtracker.app.melody.MELODY_SAMPLE_RATE
import com.soundtracker.app.melody.MelodyChordHelper
import com.soundtracker.app.melody.MelodyInputMode
import com.soundtracker.app.melody.MelodyProjectRepository
import com.soundtracker.app.melody.MelodyRealtimePlayer
import com.soundtracker.app.melody.MelodyScoreNote
import com.soundtracker.app.melody.MelodySnapshot
import com.soundtracker.app.melody.MelodyStudioUiState
import com.soundtracker.app.melody.MusicalNotationHelper
import com.soundtracker.app.melody.NoteAccent
import com.soundtracker.app.melody.NoteArticulation
import com.soundtracker.app.melody.NoteDurationPreset
import com.soundtracker.app.melody.NoteTupletTag
import com.soundtracker.app.melody.PIANO_MAX_MIDI
import com.soundtracker.app.melody.PIANO_MIN_MIDI
import com.soundtracker.app.melody.PianoTouchGuardConfig
import com.soundtracker.app.melody.ReverbPreset
import com.soundtracker.app.melody.STEPS_PER_BEAT
import com.soundtracker.app.melody.ScoreChordSymbol
import com.soundtracker.app.melody.ScoreDynamicMark
import com.soundtracker.app.melody.ScoreExpressionCompiler
import com.soundtracker.app.melody.ScoreHairpin
import com.soundtracker.app.melody.ScoreKeySignatureEvent
import com.soundtracker.app.melody.ScoreTempoEvent
import com.soundtracker.app.melody.ScoreTempoMap
import com.soundtracker.app.melody.ScoreViewMode
import com.soundtracker.app.melody.SelectedScoreAnnotation
import com.soundtracker.app.melody.StaffPart
import com.soundtracker.app.melody.StaffScoreEngraver
import com.soundtracker.app.melody.StudioInstrument
import com.soundtracker.app.melody.SwingPreset
import com.soundtracker.app.melody.TICKS_PER_BEAT
import com.soundtracker.app.melody.TICKS_PER_STEP
import com.soundtracker.app.melody.TOTAL_VIRTUAL_WHITE_KEYS
import com.soundtracker.app.melody.TempoEventKind
import com.soundtracker.app.melody.TempoTerm
import com.soundtracker.app.melody.TimeSignature
import com.soundtracker.app.melody.TouchGuardMode
import com.soundtracker.app.melody.TupletPreset
import com.soundtracker.app.storage.EphemeralStorageInfo
import com.soundtracker.app.storage.SessionWorkspaceManager
import com.soundtracker.app.update.AppUpdateManager
import com.soundtracker.app.update.AppUpdateUiState
import com.soundtracker.app.update.UpdateCheckStatus
import com.soundtracker.app.update.UpdateDownloadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class StudioToolTab(val title: String, val subtitle: String) {
    VOCAL_REMOVE("去人声提伴奏", "提取伴奏或人声"),
    PITCH_SHIFT("移调与变速", "升降调 · 1 BPM 变速"),
    VOLUME_BOOST("音量放大", "无损放大与防破音")
}

data class SessionTrack(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val badge: String,
    val detailNote: String,
    val audioFile: File,
    val originTrackId: String?, // Points to root imported track for instant A/B audition
    val metrics: AudioMetrics,
    val savedLocalPath: String? = null,
    val sharedWeChatCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

data class ProcessingTaskState(
    val title: String,
    val subtitle: String,
    val progress: Float,
    val cancellable: Boolean = true
)

data class StudioUiState(
    val workspaceMode: AppWorkspaceMode = AppWorkspaceMode.SINGLE_TRACK,
    val melody: MelodyStudioUiState = MelodyStudioUiState(),
    val arranger: ArrangerUiState = run {
        val defaultTrack = ArrangerTrack(name = "音轨 1", colorIndex = 0)
        ArrangerUiState(tracks = listOf(defaultTrack), selectedTrackId = defaultTrack.id)
    },
    val tracks: List<SessionTrack> = emptyList(),
    val selectedTrackId: String? = null,
    val activeTab: StudioToolTab = StudioToolTab.VOCAL_REMOVE,
    val isPlaying: Boolean = false,
    val isComparingOriginal: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
    // Tool 1 options
    val vocalEngineMode: VocalEngineMode = VocalEngineMode.INST_HQ5_DIRECT,
    val enableDeepDenoise: Boolean = false,
    val keepVocalStemToo: Boolean = false,
    // Tool 2 options (Pitch + 1-BPM Tempo + Real-time "边听边调" preview)
    val pitchSemitones: Int = 0,
    val pitchCents: Int = 0,
    val sourceBpm: Int = 120,
    val targetBpm: Int = 120,
    val preserveTimbre: Boolean = false,
    val livePitchTempoPreview: Boolean = true,
    // Tool 3 options (Volume Boost + Real-time "边听边调" preview)
    val volumeGainDb: Float = 0.0f,
    val liveVolumeBoostPreview: Boolean = true,
    // Export format
    val exportFormat: ExportFormat = ExportFormat.MP3_320,
    // Processing overlay & storage stats
    val storageInfo: EphemeralStorageInfo = EphemeralStorageInfo(0L, 0L, 0),
    val processingTask: ProcessingTaskState? = null,
    // NetEase Cloud Music local scanner state
    val netEaseTracks: List<NetEaseLocalTrack> = emptyList(),
    val isScanningNetEase: Boolean = false,
    val hasNetEaseStorageAccess: Boolean = true,
    // GitHub Releases OTA self-update state
    val appUpdate: AppUpdateUiState = AppUpdateUiState()
) {
    val selectedTrack: SessionTrack?
        get() = tracks.firstOrNull { it.id == selectedTrackId } ?: tracks.firstOrNull()

    val originComparisonTrack: SessionTrack?
        get() {
            val cur = selectedTrack ?: return null
            val rootId = cur.originTrackId ?: return null
            if (rootId == cur.id) return null
            return tracks.firstOrNull { it.id == rootId && it.audioFile.exists() }
        }

    /**
     * Detected musical tonality (`调号`) of the selected workspace reference song (combining explicit title hint
     * and C++ HPCP chroma key detection).
     */
    val referenceTrackKeySignature: KeySignature?
        get() {
            val cur = selectedTrack ?: return null
            KeySignature.parseTrackTitleHint(cur.displayName)?.let { return it }
            if (cur.metrics.detectedKeyConfidence >= 0.12f) {
                return KeySignature(
                    fifths = cur.metrics.detectedKeyFifths,
                    isMinor = cur.metrics.detectedKeyMinor
                ).sanitized()
            }
            return null
        }

    val isLivePitchTempoPreviewActive: Boolean
        get() = activeTab == StudioToolTab.PITCH_SHIFT &&
            livePitchTempoPreview &&
            !isComparingOriginal &&
            (pitchSemitones != 0 || pitchCents != 0 || targetBpm != sourceBpm)

    val isLiveVolumeBoostPreviewActive: Boolean
        get() = activeTab == StudioToolTab.VOLUME_BOOST &&
            liveVolumeBoostPreview &&
            !isComparingOriginal &&
            volumeGainDb > 0.05f

    val livePreviewSummaryLabel: String?
        get() = when {
            isLivePitchTempoPreviewActive -> {
                val parts = buildList {
                    if (pitchSemitones != 0 || pitchCents != 0) {
                        val semiStr = if (pitchSemitones > 0) "+$pitchSemitones" else "$pitchSemitones"
                        add("${semiStr}半音")
                    }
                    if (targetBpm != sourceBpm) {
                        add("${targetBpm}BPM")
                    }
                }
                if (parts.isEmpty()) null else "实时预览 · ${parts.joinToString(" · ")}"
            }
            isLiveVolumeBoostPreviewActive -> {
                String.format(Locale.US, "实时预览 · +%.1fdB", volumeGainDb)
            }
            else -> null
        }
}

sealed interface StudioUiEvent {
    data class ShowMessage(val message: String) : StudioUiEvent
    data class LaunchShareIntent(val intent: Intent) : StudioUiEvent
    data class RequestInstallApk(val apkFile: File) : StudioUiEvent
    data class OpenExternalUrl(val url: String) : StudioUiEvent
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private val _events = Channel<StudioUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val realtimePitchProcessor = RealtimePitchAudioProcessor()
    private val realtimeVolumeProcessor = RealtimeVolumeAudioProcessor()
    private val exoPlayer: ExoPlayer = buildRealtimePreviewExoPlayer(
        appContext,
        realtimePitchProcessor,
        realtimeVolumeProcessor
    )
    private val arrangerPlayer = MultiTrackRealtimePlayer(
        sampleRate = ARRANGER_SAMPLE_RATE,
        onPositionUpdate = { posMs, playing ->
            _uiState.update { st ->
                st.copy(
                    arranger = st.arranger.copy(
                        playheadMs = posMs,
                        isPlaying = playing
                    )
                )
            }
        }
    )
    private val arrangerUndoStack = ArrayDeque<ArrangerSnapshot>()
    private val arrangerRedoStack = ArrayDeque<ArrangerSnapshot>()
    private val highResPeaksCache = HashMap<String, List<Float>>()
    private var mashupExportCounter = 1

    @Volatile
    private var latestMelodyTempoMap: ScoreTempoMap? = null

    @Volatile
    private var latestCompiledTotalFrames: Long = 0L

    private fun currentMelodyTempoMap(mel: MelodyStudioUiState = _uiState.value.melody): ScoreTempoMap {
        return latestMelodyTempoMap ?: ScoreExpressionCompiler.buildTempoMap(mel, MELODY_SAMPLE_RATE)
    }

    private fun tickToSeekFrame(tempoMap: ScoreTempoMap, tick: Int): Long {
        if (tick <= 0) return 0L
        return (tempoMap.tickToFrame(tick.toDouble()) - 4L).coerceAtLeast(0L)
    }

    private fun stopAuditionAtScoreEnd() {
        melodyPlayer.setTransportPlaying(false, 0L)
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    isPlaying = false,
                    playheadBeat = st.melody.cursorTick.toFloat() / TICKS_PER_BEAT.toFloat()
                )
            )
        }
    }

    private val melodyPlayer: MelodyRealtimePlayer = MelodyRealtimePlayer(
        sampleRate = MELODY_SAMPLE_RATE,
        onTransportState = { curFrame, playing ->
            val curMel = _uiState.value.melody
            // Cleanly auto-stop audition (`试听`) at score end even when metronome is ON (while keeping live-record endless)
            if (playing &&
                !curMel.isRecording &&
                !curMel.loopPlayback &&
                curMel.hasPlayableNotes &&
                latestCompiledTotalFrames > 0L &&
                curFrame >= latestCompiledTotalFrames
            ) {
                stopAuditionAtScoreEnd()
                return@MelodyRealtimePlayer
            }

            val tempoMap = currentMelodyTempoMap(curMel)
            val curTick = tempoMap.frameToTick(curFrame)
            val beat = (curTick / TICKS_PER_BEAT.toDouble()).toFloat().coerceAtLeast(0f)
            if (curMel.isRecording && !playing) {
                finalizeAllActiveRecordedNotes(endBeatOverride = curMel.totalQuarterBeats)
            }
            _uiState.update { st ->
                val resolvedBeat = if (!playing && !st.melody.isRecording) {
                    st.melody.cursorTick.toFloat() / TICKS_PER_BEAT.toFloat()
                } else {
                    beat
                }
                st.copy(
                    melody = st.melody.copy(
                        playheadBeat = resolvedBeat,
                        isPlaying = playing,
                        isRecording = if (!playing) false else st.melody.isRecording
                    )
                )
            }
        }
    )
    private data class ActiveRecordedNote(
        val startBeat: Float,
        val velocity: Float,
        val downWallMs: Long,
        val isGlissando: Boolean,
        val targetStaff: StaffPart?
    )

    private data class RecentLiveStrike(
        val midi: Int,
        val downWallMs: Long,
        val isGlissando: Boolean
    )

    private val melodyUndoStack = ArrayDeque<MelodySnapshot>()
    private val melodyRedoStack = ArrayDeque<MelodySnapshot>()
    private val activeRecordedNotes = ConcurrentHashMap<Pair<Int, Int>, ActiveRecordedNote>()
    private val recentSlotStrikes = ConcurrentHashMap<Int, RecentLiveStrike>()
    private var melodyExportCounter = 1
    private var lastStepKeyDownWallMs = 0L
    private var lastStepPlacedTick = 0
    private var lastStepSlot = 0
    private var lastStepMidi = -1
    private var previewNoteJob: Job? = null

    private var loadedPlayerFilePath: String? = null
    private var activeProcessingJob: Job? = null
    private var updateCheckJob: Job? = null
    private var updateDownloadJob: Job? = null

    private val autoSaveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "st-melody-autosave").apply { isDaemon = false }
    }

    @Volatile
    private var lastSavedMelodyFingerprint: Int =
        MelodyProjectRepository.computeContentFingerprint(MelodyStudioUiState())

    @Volatile
    private var lastPersistedSessionFingerprint: Int =
        MelodyProjectRepository.computeSessionPersistenceFingerprint(MelodyStudioUiState())

    @Volatile
    private var lastObservedMelodyFingerprint: Int = lastPersistedSessionFingerprint

    @Volatile
    private var hasPendingAutoSaveFlush: Boolean = false

    private var autoSaveDebounceJob: Job? = null

    init {
        // Restore saved Melody Studio projects, touch-guard config & latest auto-saved active score on cold start
        runCatching {
            val savedTouchGuard = KeyboardLayoutPreferences.loadTouchGuardConfig(appContext)
            val autoDraft = MelodyProjectRepository.readAutoSaveDraft(appContext)
            if (autoDraft != null &&
                (MelodyProjectRepository.hasMeaningfulContent(autoDraft.state) || autoDraft.projectId != null)
            ) {
                // Ensure any pre-upgrade or unlinked draft is promoted to a durable `proj_<id>.stscore.json`
                val restoredWithId = autoDraft.state.copy(
                    touchGuardConfig = savedTouchGuard,
                    currentProjectId = autoDraft.projectId ?: UUID.randomUUID().toString(),
                    playheadBeat = autoDraft.state.cursorTick.toFloat() / TICKS_PER_BEAT.toFloat()
                )
                val persisted = MelodyProjectRepository.autoPersistActiveSession(appContext, restoredWithId)
                val finalState = restoredWithId.copy(
                    currentProjectId = persisted.projectId,
                    isProjectDirty = false,
                    lastAutoSavedAtMs = autoDraft.savedAtMs.takeIf { it > 0L } ?: persisted.savedAtMs,
                    savedProjects = persisted.savedProjects,
                    autoSaveDraftSummary = persisted.autoDraftSummary
                )
                lastSavedMelodyFingerprint = MelodyProjectRepository.computeContentFingerprint(finalState)
                lastPersistedSessionFingerprint =
                    MelodyProjectRepository.computeSessionPersistenceFingerprint(finalState)
                lastObservedMelodyFingerprint = lastPersistedSessionFingerprint
                hasPendingAutoSaveFlush = false
                _uiState.update { st -> st.copy(melody = finalState) }
            } else {
                val savedProjects = MelodyProjectRepository.listProjects(appContext)
                val autoSummary = MelodyProjectRepository.readAutoSaveSummary(appContext)
                _uiState.update { st ->
                    val nextMel = st.melody.copy(
                        touchGuardConfig = savedTouchGuard,
                        savedProjects = savedProjects,
                        autoSaveDraftSummary = autoSummary
                    )
                    lastSavedMelodyFingerprint = MelodyProjectRepository.computeContentFingerprint(nextMel)
                    lastPersistedSessionFingerprint =
                        MelodyProjectRepository.computeSessionPersistenceFingerprint(nextMel)
                    lastObservedMelodyFingerprint = lastPersistedSessionFingerprint
                    hasPendingAutoSaveFlush = false
                    st.copy(melody = nextMel)
                }
            }
        }

        viewModelScope.launch {
            _uiState.collect { state ->
                syncRealtimePreviewProcessor(state)
                onMelodyStateObservedForAutoSave(state.melody)
            }
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = exoPlayer.duration.takeIf { it > 0 } ?: 0L
                if (playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0L)
                    exoPlayer.pause()
                    _uiState.update {
                        it.copy(isPlaying = false, playbackPositionMs = 0L, playbackDurationMs = dur)
                    }
                } else if (dur > 0L) {
                    _uiState.update { it.copy(playbackDurationMs = dur) }
                }
            }
        })

        // Playhead position ticker (40ms = 25fps smooth waveform cursor)
        viewModelScope.launch {
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val dur = exoPlayer.duration.takeIf { it > 0 }
                        ?: ((_uiState.value.selectedTrack?.metrics?.durationSec ?: 0f) * 1000f).toLong()
                    _uiState.update {
                        it.copy(playbackPositionMs = pos, playbackDurationMs = dur)
                    }
                }
                delay(40L)
            }
        }

        // Restore persisted workspace tracks from disk
        val restored = SessionWorkspaceManager.loadTracksIndex(appContext)
        if (restored.isNotEmpty()) {
            val first = restored.first()
            val detectedBpm = first.metrics.detectedBpm.roundToInt().coerceIn(45, 220)
            _uiState.update {
                it.copy(
                    tracks = restored,
                    selectedTrackId = first.id,
                    sourceBpm = detectedBpm,
                    targetBpm = detectedBpm,
                    playbackDurationMs = (first.metrics.durationSec * 1000f).toLong(),
                    volumeGainDb = 0f
                )
            }
            preparePlayerForTrack(first, seekMs = 0L, playImmediately = false)
            backfillMissingTrackKeySignatures(restored)
        }
        refreshStorageInfo()

        // Silent non-blocking GitHub Release update check after initial UI settles
        viewModelScope.launch {
            delay(1_200L)
            checkForAppUpdate(manual = false)
        }
    }

    fun refreshStorageInfo() {
        val currentTracks = _uiState.value.tracks
        SessionWorkspaceManager.saveTracksIndex(appContext, currentTracks)
        val info = SessionWorkspaceManager.getStorageInfo(appContext, currentTracks)
        _uiState.update { st ->
            val currentReadyPath = st.appUpdate.readyApkPath
            val verifiedReadyPath = currentReadyPath?.takeIf { File(it).exists() }
            st.copy(
                storageInfo = info,
                appUpdate = st.appUpdate.copy(readyApkPath = verifiedReadyPath)
            )
        }
    }

    // ========================================================================
    // Playback & Instant A/B Original-vs-Processed Audition
    // ========================================================================

    fun selectTrack(trackId: String) {
        val track = _uiState.value.tracks.firstOrNull { it.id == trackId } ?: return
        pausePlayback()
        val detectedBpm = track.metrics.detectedBpm.roundToInt().coerceIn(45, 220)
        _uiState.update {
            it.copy(
                selectedTrackId = track.id,
                isComparingOriginal = false,
                playbackPositionMs = 0L,
                playbackDurationMs = (track.metrics.durationSec * 1000f).toLong(),
                pitchSemitones = 0,
                pitchCents = 0,
                sourceBpm = detectedBpm,
                targetBpm = detectedBpm,
                volumeGainDb = 0f
            )
        }
        syncRealtimePreviewProcessor()
        preparePlayerForTrack(track, seekMs = 0L, playImmediately = false)
    }

    fun togglePlayPause() {
        val state = _uiState.value
        val target = if (state.isComparingOriginal) {
            state.originComparisonTrack ?: state.selectedTrack
        } else {
            state.selectedTrack
        } ?: return

        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            arrangerPlayer.pause()
            if (loadedPlayerFilePath != target.audioFile.absolutePath) {
                preparePlayerForTrack(target, seekMs = state.playbackPositionMs, playImmediately = true)
            } else {
                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0L)
                }
                exoPlayer.play()
            }
        }
    }

    fun pausePlayback() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        }
        arrangerPlayer.pause()
        stopMelodyPlaybackAndEngine()
    }

    fun onAppForegrounded() {
        if (_uiState.value.workspaceMode == AppWorkspaceMode.MELODY_STUDIO) {
            ensureMelodyStudioReady()
        }
    }

    fun onAppPaused() {
        flushMelodyAutoSaveImmediate()
    }

    fun onAppBackgrounded() {
        flushMelodyAutoSaveImmediate()
    }

    private fun backfillMissingTrackKeySignatures(tracksToCheck: List<SessionTrack>) {
        val candidates = tracksToCheck.filter {
            it.metrics.detectedKeyConfidence <= 0.001f && it.audioFile.exists()
        }
        if (candidates.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var anyUpdated = false
            for (cand in candidates) {
                val analyzed = NativeAudioEngine.analyzeWav(cand.audioFile, waveformBins = 80) ?: continue
                if (analyzed.detectedKeyConfidence > 0.001f) {
                    anyUpdated = true
                    _uiState.update { st ->
                        st.copy(
                            tracks = st.tracks.map { t ->
                                if (t.id == cand.id) {
                                    t.copy(
                                        metrics = t.metrics.copy(
                                            detectedKeyFifths = analyzed.detectedKeyFifths,
                                            detectedKeyMinor = analyzed.detectedKeyMinor,
                                            detectedKeyConfidence = analyzed.detectedKeyConfidence
                                        )
                                    )
                                } else {
                                    t
                                }
                            }
                        )
                    }
                }
            }
            if (anyUpdated) {
                SessionWorkspaceManager.saveTracksIndex(appContext, _uiState.value.tracks)
            }
        }
    }

    fun seekToFraction(fraction: Float) {
        val state = _uiState.value
        val track = state.selectedTrack ?: return
        val durMs = state.playbackDurationMs.takeIf { it > 0 }
            ?: (track.metrics.durationSec * 1000f).toLong()
        val targetMs = (durMs * fraction.coerceIn(0f, 1f)).toLong()
        if (loadedPlayerFilePath == null) {
            preparePlayerForTrack(track, seekMs = targetMs, playImmediately = false)
        } else {
            exoPlayer.seekTo(targetMs)
        }
        _uiState.update { it.copy(playbackPositionMs = targetMs, playbackDurationMs = durMs) }
    }

    fun skipByMs(deltaMs: Long) {
        val state = _uiState.value
        val track = state.selectedTrack ?: return
        val durMs = state.playbackDurationMs.takeIf { it > 0 }
            ?: (track.metrics.durationSec * 1000f).toLong()
        val nextMs = (state.playbackPositionMs + deltaMs).coerceIn(0L, durMs)
        if (loadedPlayerFilePath != null) {
            exoPlayer.seekTo(nextMs)
        }
        _uiState.update { it.copy(playbackPositionMs = nextMs) }
    }

    /**
     * Seamless A/B toggle between the processed track and its original imported track at the exact same timestamp.
     */
    fun toggleAbCompare() {
        val state = _uiState.value
        val curTrack = state.selectedTrack ?: return
        val origTrack = state.originComparisonTrack ?: return

        val wasPlaying = exoPlayer.isPlaying
        val currentPos = exoPlayer.currentPosition.coerceAtLeast(state.playbackPositionMs)
        val nextCompare = !state.isComparingOriginal
        val nextTrack = if (nextCompare) origTrack else curTrack

        _uiState.update { it.copy(isComparingOriginal = nextCompare, playbackPositionMs = currentPos) }
        syncRealtimePreviewProcessor()
        preparePlayerForTrack(nextTrack, seekMs = currentPos, playImmediately = wasPlaying)
    }

    private fun preparePlayerForTrack(track: SessionTrack, seekMs: Long, playImmediately: Boolean) {
        if (!track.audioFile.exists()) return
        loadedPlayerFilePath = track.audioFile.absolutePath
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(track.audioFile)))
        exoPlayer.prepare()
        if (seekMs > 0L) {
            exoPlayer.seekTo(seekMs)
        }
        exoPlayer.playWhenReady = playImmediately
    }

    // ========================================================================
    // UI Parameter Controls
    // ========================================================================

    fun selectTab(tab: StudioToolTab) {
        _uiState.update { it.copy(activeTab = tab) }
        syncRealtimePreviewProcessor()
    }

    fun setVocalEngineMode(mode: VocalEngineMode) {
        _uiState.update { it.copy(vocalEngineMode = mode) }
    }

    fun setEnableDeepDenoise(enable: Boolean) {
        _uiState.update { it.copy(enableDeepDenoise = enable) }
    }

    fun setKeepVocalStemToo(keep: Boolean) {
        _uiState.update { it.copy(keepVocalStemToo = keep) }
    }

    fun setPitchSemitones(semitones: Int) {
        _uiState.update { it.copy(pitchSemitones = semitones.coerceIn(-12, 12)) }
        syncRealtimePreviewProcessor()
    }

    fun setPitchCents(cents: Int) {
        _uiState.update { it.copy(pitchCents = cents.coerceIn(-50, 50)) }
        syncRealtimePreviewProcessor()
    }

    fun setTargetBpm(bpm: Int) {
        _uiState.update { it.copy(targetBpm = bpm.coerceIn(40, 240)) }
        syncRealtimePreviewProcessor()
    }

    fun adjustTargetBpmBy(delta: Int) {
        _uiState.update { it.copy(targetBpm = (it.targetBpm + delta).coerceIn(40, 240)) }
        syncRealtimePreviewProcessor()
    }

    fun calibrateSourceBpm(newSourceBpm: Int) {
        val clamped = newSourceBpm.coerceIn(40, 240)
        _uiState.update { it.copy(sourceBpm = clamped, targetBpm = clamped) }
        syncRealtimePreviewProcessor()
    }

    fun setPreserveTimbre(preserve: Boolean) {
        _uiState.update { it.copy(preserveTimbre = preserve) }
        syncRealtimePreviewProcessor()
    }

    fun setLivePitchTempoPreview(enabled: Boolean) {
        _uiState.update { it.copy(livePitchTempoPreview = enabled) }
        syncRealtimePreviewProcessor()
    }

    private fun syncRealtimePreviewProcessor(state: StudioUiState = _uiState.value) {
        val pitchEnabled = state.activeTab == StudioToolTab.PITCH_SHIFT &&
            state.livePitchTempoPreview &&
            !state.isComparingOriginal
        val semitones = state.pitchSemitones + (state.pitchCents / 100f)
        val srcBpm = state.sourceBpm.coerceIn(40, 240).toDouble()
        val dstBpm = state.targetBpm.coerceIn(40, 240).toDouble()
        val timeRatio = srcBpm / dstBpm
        realtimePitchProcessor.updatePreviewConfig(
            enabled = pitchEnabled,
            semitones = semitones,
            timeRatio = timeRatio,
            preserveTimbre = state.preserveTimbre
        )

        realtimeVolumeProcessor.updatePreviewConfig(
            isVolumeTabActive = state.activeTab == StudioToolTab.VOLUME_BOOST && !state.isComparingOriginal,
            previewEnabled = state.liveVolumeBoostPreview,
            gainDb = state.volumeGainDb
        )
    }

    fun setVolumeGainDb(gainDb: Float) {
        val rounded = (gainDb.coerceIn(0f, 18f) * 10f).roundToInt() / 10f
        _uiState.update { it.copy(volumeGainDb = rounded) }
        syncRealtimePreviewProcessor()
    }

    fun setLiveVolumeBoostPreview(enabled: Boolean) {
        _uiState.update { it.copy(liveVolumeBoostPreview = enabled) }
        syncRealtimePreviewProcessor()
    }

    fun setExportFormat(format: ExportFormat) {
        _uiState.update { it.copy(exportFormat = format) }
    }

    fun cancelActiveProcessing() {
        NativeAudioEngine.nativeRequestCancel()
        activeProcessingJob?.cancel()
        _uiState.update { it.copy(processingTask = null) }
    }

    // ========================================================================
    // Import (Local & NetEase Cloud Music NCM/FLAC/MP3) & 3 Studio Engines
    // ========================================================================

    fun scanNetEaseCloudMusic() {
        viewModelScope.launch {
            val hasAccess = NetEaseCloudMusicManager.hasAllFilesAccess()
            _uiState.update {
                it.copy(
                    isScanningNetEase = true,
                    hasNetEaseStorageAccess = hasAccess
                )
            }
            val found = NetEaseCloudMusicManager.scanNetEaseLocalTracks(appContext)
            _uiState.update {
                it.copy(
                    netEaseTracks = found,
                    isScanningNetEase = false,
                    hasNetEaseStorageAccess = NetEaseCloudMusicManager.hasAllFilesAccess()
                )
            }
        }
    }

    /**
     * Imports one or more tracks selected from the NetEase Cloud Music download list.
     * - `.ncm` files are decrypted directly into `.flac` or `.mp3` inside the App workspace (zero transcoding loss).
     * - `.flac` / `.mp3` files are copied directly in their original format (FLAC > MP3).
     */
    fun importNetEaseTracks(selectedTracks: List<NetEaseLocalTrack>) {
        if (selectedTracks.isEmpty() || _uiState.value.processingTask != null) return
        pausePlayback()

        activeProcessingJob = viewModelScope.launch {
            var importedCount = 0
            var lastImportedName = ""
            var lastFormat = ""
            var lastBpm = 120
            try {
                NativeAudioEngine.nativeResetCancel()
                val total = selectedTracks.size
                for ((index, item) in selectedTracks.withIndex()) {
                    var workspaceAudioFile: File? = null
                    val tempAnalysisWav = File(
                        SessionWorkspaceManager.tempDir(appContext),
                        "netease_analyze_${System.nanoTime()}.wav"
                    )
                    try {
                        val stepPrefix = if (total > 1) "(${index + 1}/$total) " else ""
                        _uiState.update {
                            it.copy(
                                processingTask = ProcessingTaskState(
                                    title = "${stepPrefix}正在从网易云导入「${item.displayTitle}」",
                                    subtitle = if (item.isNcm) {
                                        "正在无损解密 NCM → ${item.targetFormat.uppercase()}..."
                                    } else {
                                        "正在导入 ${item.targetFormat.uppercase()} 音轨..."
                                    },
                                    progress = 0.08f,
                                    cancellable = true
                                )
                            )
                        }

                        val importRes = NetEaseCloudMusicManager.importNetEaseTrackToWorkspace(
                            context = appContext,
                            track = item
                        ) { p ->
                            _uiState.update { st ->
                                st.copy(
                                    processingTask = st.processingTask?.copy(
                                        progress = (p * 0.45f).coerceIn(0.05f, 0.45f)
                                    )
                                )
                            }
                        }.getOrThrow()

                        workspaceAudioFile = importRes.outputFile
                        val fmtUpper = importRes.format.uppercase()

                        _uiState.update { st ->
                            st.copy(
                                processingTask = st.processingTask?.copy(
                                    subtitle = "已生成 $fmtUpper · 正在分析响度与自动识别 BPM...",
                                    progress = 0.50f
                                )
                            )
                        }

                        val decodedOk = AudioCodecHelper.decodeUriToWav(
                            context = appContext,
                            uri = Uri.fromFile(workspaceAudioFile),
                            tempDir = SessionWorkspaceManager.tempDir(appContext),
                            targetWav = tempAnalysisWav
                        ) { p ->
                            _uiState.update { st ->
                                st.copy(
                                    processingTask = st.processingTask?.copy(
                                        progress = (0.50f + p * 0.42f).coerceIn(0.50f, 0.92f)
                                    )
                                )
                            }
                        }

                        if (!decodedOk || !tempAnalysisWav.exists()) {
                            workspaceAudioFile.delete()
                            continue
                        }

                        val metrics = NativeAudioEngine.analyzeWav(tempAnalysisWav)
                        if (metrics == null) {
                            workspaceAudioFile.delete()
                            continue
                        }

                        val detectedBpm = metrics.detectedBpm.roundToInt()
                        val sourceNote = if (importRes.wasEncryptedNcm) {
                            "网易云 NCM→$fmtUpper"
                        } else {
                            "网易云 $fmtUpper"
                        }
                        val newTrack = SessionTrack(
                            displayName = AudioCodecHelper.sanitizeFileName(importRes.displayTitle),
                            badge = "$fmtUpper · ${detectedBpm}BPM",
                            detailNote = "$sourceNote · ${formatMetricsSummary(metrics)}",
                            audioFile = workspaceAudioFile,
                            originTrackId = null,
                            metrics = metrics
                        ).let { it.copy(originTrackId = it.id) }

                        appendTrackToWorkspace(newTrack)
                        if (_uiState.value.workspaceMode == AppWorkspaceMode.MULTI_TRACK_ARRANGER) {
                            appendImportedTrackToArrangerDirect(
                                newTrack = newTrack,
                                decodedTempWav = tempAnalysisWav,
                                recordUndo = importedCount == 0
                            )
                        }
                        importedCount++
                        lastImportedName = newTrack.displayName
                        lastFormat = fmtUpper
                        lastBpm = detectedBpm
                    } catch (ce: CancellationException) {
                        workspaceAudioFile?.delete()
                        throw ce
                    } catch (e: Exception) {
                        workspaceAudioFile?.delete()
                        if (total == 1) {
                            sendMessage("网易云音乐导入失败：${e.message ?: "格式异常"}")
                            return@launch
                        }
                    } finally {
                        if (tempAnalysisWav.exists()) tempAnalysisWav.delete()
                    }
                }

                val inArranger = _uiState.value.workspaceMode == AppWorkspaceMode.MULTI_TRACK_ARRANGER
                if (importedCount == 1) {
                    if (inArranger) {
                        sendMessage("已导入「$lastImportedName」并放入「${_uiState.value.arranger.selectedTrack.name}」")
                    } else {
                        sendMessage("已从网易云导入「$lastImportedName.$lastFormat」· 速度 $lastBpm BPM")
                    }
                } else if (importedCount > 1) {
                    if (inArranger) {
                        sendMessage("已从网易云导入 $importedCount 首音乐并依次放入「${_uiState.value.arranger.selectedTrack.name}」")
                    } else {
                        sendMessage("已从网易云成功导入 $importedCount 首音乐（自动转换为最佳 FLAC/MP3 格式）")
                    }
                }
            } catch (_: CancellationException) {
                sendMessage("已取消网易云导入")
            } finally {
                _uiState.update { it.copy(processingTask = null) }
                refreshStorageInfo()
            }
        }
    }

    fun importAudioUri(uri: Uri) {
        if (_uiState.value.processingTask != null) return
        pausePlayback()

        activeProcessingJob = viewModelScope.launch {
            val rawNameWithExt = AudioCodecHelper.resolveDisplayNameWithExt(appContext, uri)
            val rawName = AudioCodecHelper.stripExtension(rawNameWithExt).ifBlank { "未命名音轨" }
            val lowerExt = rawNameWithExt.substringAfterLast('.', "").lowercase()
            val isNcm = NetEaseCloudMusicManager.isNcmUri(appContext, uri, rawNameWithExt)

            var workspaceFile: File? = null
            val tempAnalysisWav = File(
                SessionWorkspaceManager.tempDir(appContext),
                "analyze_${System.nanoTime()}.wav"
            )
            try {
                NativeAudioEngine.nativeResetCancel()
                _uiState.update {
                    it.copy(
                        processingTask = ProcessingTaskState(
                            title = if (isNcm) "正在解密网易云 NCM「$rawName」" else "正在导入并分析「$rawName」",
                            subtitle = if (isNcm) "正在无损提取 NCM 内置 FLAC / MP3 音频流..." else "正在分析音频流并自动识别 BPM...",
                            progress = 0.05f,
                            cancellable = true
                        )
                    )
                }

                var finalTitle = rawName
                var badgePrefix = "原曲"
                var notePrefix = ""

                if (isNcm) {
                    val ncmRes = NetEaseCloudMusicManager.decryptNcmUriToWorkspace(
                        context = appContext,
                        uri = uri,
                        fallbackTitle = rawName
                    ) { p ->
                        _uiState.update { st ->
                            st.copy(
                                processingTask = st.processingTask?.copy(
                                    progress = (p * 0.45f).coerceIn(0.05f, 0.45f)
                                )
                            )
                        }
                    }.getOrThrow()

                    workspaceFile = ncmRes.outputFile
                    finalTitle = ncmRes.displayTitle
                    val fmtUpper = ncmRes.format.uppercase()
                    badgePrefix = fmtUpper
                    notePrefix = "网易云 NCM→$fmtUpper · "

                    val ok = AudioCodecHelper.decodeUriToWav(
                        context = appContext,
                        uri = Uri.fromFile(workspaceFile),
                        tempDir = SessionWorkspaceManager.tempDir(appContext),
                        targetWav = tempAnalysisWav
                    ) { p ->
                        _uiState.update { st ->
                            st.copy(
                                processingTask = st.processingTask?.copy(
                                    progress = (0.45f + p * 0.45f).coerceIn(0.45f, 0.90f)
                                )
                            )
                        }
                    }
                    if (!ok || !tempAnalysisWav.exists()) {
                        workspaceFile.delete()
                        sendMessage("NCM 解密后解码失败")
                        return@launch
                    }
                } else if (lowerExt == "flac" || lowerExt == "mp3") {
                    // Store directly as .flac or .mp3 in workspace
                    val directFile = SessionWorkspaceManager.createSessionTrackFile(appContext, "src", lowerExt)
                    workspaceFile = directFile
                    appContext.contentResolver.openInputStream(uri)?.use { ins ->
                        directFile.outputStream().buffered(256 * 1024).use { outs ->
                            ins.copyTo(outs, 256 * 1024)
                        }
                    }
                    badgePrefix = lowerExt.uppercase()

                    val ok = AudioCodecHelper.decodeUriToWav(
                        context = appContext,
                        uri = Uri.fromFile(directFile),
                        tempDir = SessionWorkspaceManager.tempDir(appContext),
                        targetWav = tempAnalysisWav
                    ) { p ->
                        _uiState.update { state ->
                            state.copy(
                                processingTask = state.processingTask?.copy(progress = (p * 0.85f).coerceIn(0.05f, 0.85f))
                            )
                        }
                    }
                    if (!ok || !tempAnalysisWav.exists()) {
                        directFile.delete()
                        sendMessage("无法解码该音频文件")
                        return@launch
                    }
                } else {
                    val targetWav = SessionWorkspaceManager.createSessionTrackFile(appContext, "src", "wav")
                    workspaceFile = targetWav
                    val ok = AudioCodecHelper.decodeUriToWav(
                        context = appContext,
                        uri = uri,
                        tempDir = SessionWorkspaceManager.tempDir(appContext),
                        targetWav = targetWav
                    ) { p ->
                        _uiState.update { state ->
                            state.copy(
                                processingTask = state.processingTask?.copy(progress = (p * 0.85f).coerceIn(0.05f, 0.85f))
                            )
                        }
                    }
                    if (!ok || !targetWav.exists()) {
                        targetWav.delete()
                        sendMessage("无法解码该音频文件，请选择 NCM / MP3 / FLAC / M4A / WAV / MP4 格式")
                        return@launch
                    }
                }

                val wavToAnalyze = if (tempAnalysisWav.exists()) tempAnalysisWav else workspaceFile!!
                val metrics = NativeAudioEngine.analyzeWav(wavToAnalyze) ?: run {
                    workspaceFile?.delete()
                    sendMessage("音频响度与 BPM 分析失败")
                    return@launch
                }

                val detectedBpm = metrics.detectedBpm.roundToInt()
                val newTrack = SessionTrack(
                    displayName = AudioCodecHelper.sanitizeFileName(finalTitle),
                    badge = "$badgePrefix · ${detectedBpm}BPM",
                    detailNote = "$notePrefix${formatMetricsSummary(metrics)}",
                    audioFile = workspaceFile!!,
                    originTrackId = null,
                    metrics = metrics
                ).let { it.copy(originTrackId = it.id) }

                appendTrackToWorkspace(newTrack)
                if (_uiState.value.workspaceMode == AppWorkspaceMode.MULTI_TRACK_ARRANGER) {
                    appendImportedTrackToArrangerDirect(
                        newTrack = newTrack,
                        decodedTempWav = tempAnalysisWav,
                        recordUndo = true
                    )
                    sendMessage("已导入「${newTrack.displayName}」并放入「${_uiState.value.arranger.selectedTrack.name}」")
                } else {
                    sendMessage("已导入「${newTrack.displayName}.${workspaceFile.extension.lowercase()}」· 自动识别速度 ${detectedBpm} BPM")
                }
            } catch (_: CancellationException) {
                workspaceFile?.delete()
                sendMessage("已取消导入")
            } catch (e: Exception) {
                workspaceFile?.delete()
                sendMessage("导入失败：${e.message ?: "格式不支持"}")
            } finally {
                if (tempAnalysisWav.exists()) tempAnalysisWav.delete()
                _uiState.update { it.copy(processingTask = null) }
                refreshStorageInfo()
            }
        }
    }

    /**
     * Feature 1: Studio Multi-Architecture Vocal Removal -> Pure Accompaniment
     */
    fun runVocalSeparation() {
        val state = _uiState.value
        val sourceTrack = state.selectedTrack ?: run {
            sendMessage("请先导入一首音乐")
            return
        }
        if (state.processingTask != null) return
        pausePlayback()

        val mode = state.vocalEngineMode
        val deepDenoise = state.enableDeepDenoise
        val keepVocals = state.keepVocalStemToo
        val engineShortLabel = when (mode) {
            VocalEngineMode.INST_HQ5_DIRECT -> if (deepDenoise) "人声去除优先(二次降噪)" else "人声去除优先"
            VocalEngineMode.HTDEMUCS_FT -> "乐器音质优先"
            VocalEngineMode.HYBRID_ENSEMBLE -> "双模型均衡模式"
        }

        activeProcessingJob = viewModelScope.launch {
            val accWav = SessionWorkspaceManager.createSessionTrackFile(appContext, "accompaniment", "wav")
            val vocWav = if (keepVocals) {
                SessionWorkspaceManager.createSessionTrackFile(appContext, "vocals", "wav")
            } else {
                null
            }
            var tempDecodedInput: File? = null

            try {
                NativeAudioEngine.nativeResetCancel()
                _uiState.update {
                    it.copy(
                        processingTask = ProcessingTaskState(
                            title = "正在提取伴奏",
                            subtitle = "正在加载分离模型（$engineShortLabel）...",
                            progress = 0.02f,
                            cancellable = true
                        )
                    )
                }

                val inputWav = ensureWavInput(sourceTrack.audioFile) { tempDecodedInput = it }
                val outcome = NativeAudioEngine.separateVocals(
                    context = appContext,
                    mode = mode,
                    enableDeepDenoise = deepDenoise,
                    inputWav = inputWav,
                    outAccompanimentWav = accWav,
                    outVocalsWav = vocWav
                ) { p ->
                    val pct = (p * 100).roundToInt()
                    val phaseHint = if (mode == VocalEngineMode.HYBRID_ENSEMBLE) {
                        if (p < 0.5f) "第 1/2 步：人声去除模型" else "第 2/2 步：乐器保真模型"
                    } else {
                        engineShortLabel
                    }
                    _uiState.update { st ->
                        st.copy(
                            processingTask = st.processingTask?.copy(
                                progress = p,
                                subtitle = "$phaseHint · 已处理 $pct%"
                            )
                        )
                    }
                }

                when (outcome) {
                    VocalSeparationOutcome.Cancelled -> {
                        accWav.delete()
                        vocWav?.delete()
                        sendMessage("已取消去人声任务")
                        return@launch
                    }
                    is VocalSeparationOutcome.Error -> {
                        accWav.delete()
                        vocWav?.delete()
                        sendMessage("去人声失败：${outcome.message}")
                        return@launch
                    }
                    VocalSeparationOutcome.Success -> Unit
                }

                if (!accWav.exists()) {
                    vocWav?.delete()
                    sendMessage("伴奏生成文件缺失")
                    return@launch
                }

                val rawAccMetrics = NativeAudioEngine.analyzeWav(accWav) ?: error("伴奏分析失败")
                // Keep exact source BPM if original track already had a locked BPM
                val accMetrics = rawAccMetrics.copy(detectedBpm = sourceTrack.metrics.detectedBpm)
                val bpmInt = accMetrics.detectedBpm.roundToInt()
                val baseTitle = stripKnownSuffix(sourceTrack.displayName, "_原曲")
                val accName = appendSmartTag(baseTitle, "伴奏")

                val accTrack = SessionTrack(
                    displayName = accName,
                    badge = "AI 伴奏 · ${bpmInt}BPM",
                    detailNote = "$engineShortLabel · ${formatMetricsSummary(accMetrics)}",
                    audioFile = accWav,
                    originTrackId = sourceTrack.originTrackId ?: sourceTrack.id,
                    metrics = accMetrics
                )

                if (keepVocals && vocWav != null && vocWav.exists()) {
                    val rawVocMetrics = NativeAudioEngine.analyzeWav(vocWav)
                    if (rawVocMetrics != null) {
                        val vocMetrics = rawVocMetrics.copy(detectedBpm = sourceTrack.metrics.detectedBpm)
                        val vocTrack = SessionTrack(
                            displayName = appendSmartTag(baseTitle, "纯人声"),
                            badge = "AI 纯人声 · ${bpmInt}BPM",
                            detailNote = "$engineShortLabel 干声 · ${formatMetricsSummary(vocMetrics)}",
                            audioFile = vocWav,
                            originTrackId = sourceTrack.originTrackId ?: sourceTrack.id,
                            metrics = vocMetrics
                        )
                        _uiState.update { it.copy(tracks = listOf(vocTrack) + it.tracks) }
                    } else {
                        vocWav.delete()
                    }
                }

                appendTrackToWorkspace(accTrack)
                sendMessage("伴奏提取完成！可点击「对比原曲」试听去人声效果")
            } catch (_: CancellationException) {
                accWav.delete()
                vocWav?.delete()
                sendMessage("已取消去人声任务")
            } catch (e: Exception) {
                accWav.delete()
                vocWav?.delete()
                sendMessage("去人声失败：${e.message ?: "未知错误"}")
            } finally {
                tempDecodedInput?.delete()
                _uiState.update { it.copy(processingTask = null) }
                refreshStorageInfo()
            }
        }
    }

    /**
     * Feature 2: Studio Polyphonic Pitch Shifting & 1-BPM Granularity Time Stretching
     * Powered by Rubber Band Library v4.0 R3 Finer Offline Engine (single unified pass).
     */
    fun runPitchShift() {
        val state = _uiState.value
        val sourceTrack = state.selectedTrack ?: run {
            sendMessage("请先导入或选择一首音轨")
            return
        }
        val totalSemitones = state.pitchSemitones + (state.pitchCents / 100f)
        val srcBpm = state.sourceBpm.coerceIn(40, 240)
        val dstBpm = state.targetBpm.coerceIn(40, 240)
        val hasPitchChange = abs(totalSemitones) >= 0.01f
        val hasBpmChange = dstBpm != srcBpm

        if (!hasPitchChange && !hasBpmChange) {
            sendMessage("请先调整升降调半音数或目标 BPM 速度（支持 1 BPM 精度微调）")
            return
        }
        if (state.processingTask != null) return
        pausePlayback()

        val preserveTimbre = state.preserveTimbre
        val semitonesInt = state.pitchSemitones
        val centsInt = state.pitchCents
        // timeRatio = outputDuration / inputDuration = srcBpm / dstBpm
        val timeRatio = if (hasBpmChange) srcBpm.toDouble() / dstBpm.toDouble() else 1.0

        activeProcessingJob = viewModelScope.launch {
            val outWav = SessionWorkspaceManager.createSessionTrackFile(appContext, "r3_master", "wav")
            var tempDecodedInput: File? = null
            try {
                NativeAudioEngine.nativeResetCancel()
                val pitchTag = if (hasPitchChange) formatPitchTag(semitonesInt, centsInt) else ""
                val bpmTag = if (hasBpmChange) "${dstBpm}BPM" else ""
                val combinedTag = listOf(pitchTag, bpmTag).filter { it.isNotEmpty() }.joinToString("_")
                val modeActionTitle = when {
                    hasPitchChange && hasBpmChange -> "移调 + 变速 ($pitchTag · ${srcBpm}→${dstBpm} BPM)"
                    hasPitchChange -> "移调 ($pitchTag · 保持 ${srcBpm} BPM)"
                    else -> "变速不变调 (${srcBpm} → ${dstBpm} BPM)"
                }

                _uiState.update {
                    it.copy(
                        processingTask = ProcessingTaskState(
                            title = "正在执行 $modeActionTitle",
                            subtitle = "正在准备音频数据...",
                            progress = 0.04f,
                            cancellable = true
                        )
                    )
                }

                val inputWav = ensureWavInput(sourceTrack.audioFile) { tempDecodedInput = it }
                val ok = NativeAudioEngine.pitchShift(
                    inputWav = inputWav,
                    outputWav = outWav,
                    semitones = totalSemitones,
                    timeRatio = timeRatio,
                    preserveTimbre = preserveTimbre
                ) { p ->
                    val phaseText = if (p < 0.25f) {
                        "第 1/2 步：分析音频节拍与音高..."
                    } else {
                        "第 2/2 步：正在生成移调/变速后的音频..."
                    }
                    _uiState.update { st ->
                        st.copy(
                            processingTask = st.processingTask?.copy(
                                subtitle = phaseText,
                                progress = p
                            )
                        )
                    }
                }

                if (!ok || !outWav.exists()) {
                    outWav.delete()
                    sendMessage("处理已取消或未完成")
                    return@launch
                }

                val rawMetrics = NativeAudioEngine.analyzeWav(outWav) ?: error("输出音频分析失败")
                val finalMetrics = rawMetrics.copy(detectedBpm = dstBpm.toFloat())
                val newName = appendSmartTag(sourceTrack.displayName, combinedTag)
                val badgeText = when {
                    hasPitchChange && hasBpmChange -> "$pitchTag · ${dstBpm}BPM"
                    hasPitchChange -> "移调 $pitchTag · ${dstBpm}BPM"
                    else -> "变速 ${srcBpm}→${dstBpm}BPM"
                }
                val shiftedTrack = SessionTrack(
                    displayName = newName,
                    badge = badgeText,
                    detailNote = "${if (preserveTimbre) "保持人声音色" else "伴奏模式"} · ${formatMetricsSummary(finalMetrics)}",
                    audioFile = outWav,
                    originTrackId = sourceTrack.originTrackId ?: sourceTrack.id,
                    metrics = finalMetrics
                )

                appendTrackToWorkspace(shiftedTrack)
                sendMessage("已完成 $modeActionTitle")
            } catch (_: CancellationException) {
                outWav.delete()
                sendMessage("已取消移调/变速")
            } catch (e: Exception) {
                outWav.delete()
                sendMessage("处理失败：${e.message ?: "未知错误"}")
            } finally {
                tempDecodedInput?.delete()
                _uiState.update { it.copy(processingTask = null) }
                refreshStorageInfo()
            }
        }
    }

    /**
     * Feature 4: Lossless / Mastering True-Peak Volume Maximizer
     */
    fun runVolumeBoost() {
        val state = _uiState.value
        val sourceTrack = state.selectedTrack ?: run {
            sendMessage("请先导入或选择一首音轨")
            return
        }
        val gainDb = state.volumeGainDb
        if (gainDb <= 0.05f) {
            sendMessage("请设置大于 0 dB 的放大增益")
            return
        }
        if (state.processingTask != null) return
        pausePlayback()

        activeProcessingJob = viewModelScope.launch {
            val outWav = SessionWorkspaceManager.createSessionTrackFile(appContext, "boost", "wav")
            var tempDecodedInput: File? = null
            try {
                NativeAudioEngine.nativeResetCancel()
                val gainStr = String.format(Locale.US, "+%.1fdB", gainDb)
                _uiState.update {
                    it.copy(
                        processingTask = ProcessingTaskState(
                            title = "正在生成音量放大音轨 ($gainStr)",
                            subtitle = "正在提高整体音量并进行防破音峰值保护...",
                            progress = 0.08f,
                            cancellable = true
                        )
                    )
                }

                val inputWav = ensureWavInput(sourceTrack.audioFile) { tempDecodedInput = it }
                val report = NativeAudioEngine.boostVolume(
                    inputWav = inputWav,
                    outputWav = outWav,
                    gainDb = gainDb,
                    ceilingDbtp = -0.2f
                ) { p ->
                    _uiState.update { st ->
                        st.copy(processingTask = st.processingTask?.copy(progress = p))
                    }
                }

                if (report == null || !outWav.exists()) {
                    outWav.delete()
                    sendMessage("音量放大已取消或失败")
                    return@launch
                }

                val rawPostMetrics = NativeAudioEngine.analyzeWav(outWav) ?: error("放大后分析失败")
                val postMetrics = rawPostMetrics.copy(detectedBpm = sourceTrack.metrics.detectedBpm)
                val bpmInt = postMetrics.detectedBpm.roundToInt()
                val modeDesc = if (report.wasPureLossless) {
                    "无损等比放大"
                } else {
                    String.format(Locale.US, "防破音保护(压制峰值%.1fdB)", report.maxGainReductionDb)
                }
                val newName = appendSmartTag(sourceTrack.displayName, "音量$gainStr")
                val boostedTrack = SessionTrack(
                    displayName = newName,
                    badge = "放大 $gainStr · ${bpmInt}BPM",
                    detailNote = "$modeDesc · 响度 ${formatLufs(sourceTrack.metrics.integratedLufs)} → ${formatLufs(postMetrics.integratedLufs)}",
                    audioFile = outWav,
                    originTrackId = sourceTrack.originTrackId ?: sourceTrack.id,
                    metrics = postMetrics
                )

                appendTrackToWorkspace(boostedTrack)
                sendMessage("已生成放大音轨（$modeDesc，无破音失真）")
            } catch (_: CancellationException) {
                outWav.delete()
                sendMessage("已取消音量放大")
            } catch (e: Exception) {
                outWav.delete()
                sendMessage("音量放大失败：${e.message ?: "未知错误"}")
            } finally {
                tempDecodedInput?.delete()
                _uiState.update { it.copy(processingTask = null) }
                refreshStorageInfo()
            }
        }
    }

    // ========================================================================
    // Feature 3: Rename, WeChat Share, Save As Local, & Resource Manager
    // ========================================================================

    fun renameTrack(trackId: String, newNameRaw: String) {
        val stripped = AudioCodecHelper.stripExtension(newNameRaw)
        val sanitized = AudioCodecHelper.sanitizeFileName(stripped)
        _uiState.update { state ->
            state.copy(
                tracks = state.tracks.map { t ->
                    if (t.id == trackId) t.copy(displayName = sanitized) else t
                },
                arranger = state.arranger.copy(
                    tracks = state.arranger.tracks.map { arrTrack ->
                        arrTrack.copy(
                            clips = arrTrack.clips.map { clip ->
                                if (clip.sourceTrackId == trackId) clip.copy(sourceTitle = sanitized) else clip
                            }
                        )
                    }
                )
            )
        }
        refreshStorageInfo()
        sendMessage("已重命名为「$sanitized」")
    }

    fun shareTrack(trackId: String, targetWeChatDirectly: Boolean) {
        val state = _uiState.value
        val track = state.tracks.firstOrNull { it.id == trackId } ?: return
        if (state.processingTask != null) return
        val format = state.exportFormat

        viewModelScope.launch {
            var tempDecodedInput: File? = null
            try {
                _uiState.update {
                    it.copy(
                        processingTask = ProcessingTaskState(
                            title = if (targetWeChatDirectly) "正在准备微信分享文件" else "正在准备分享音频",
                            subtitle = "生成「${track.displayName}.${format.extension}」...",
                            progress = 0.1f,
                            cancellable = false
                        )
                    )
                }

                val sourceForExport = if (format == ExportFormat.MP3_320 && track.audioFile.extension.equals("mp3", true)) {
                    track.audioFile
                } else {
                    ensureWavInput(track.audioFile) { tempDecodedInput = it }
                }

                val intentRes = SessionWorkspaceManager.prepareShareIntent(
                    context = appContext,
                    sourceAudioFile = sourceForExport,
                    customDisplayName = track.displayName,
                    format = format,
                    targetWeChatDirectly = targetWeChatDirectly
                ) { p ->
                    _uiState.update { st ->
                        st.copy(processingTask = st.processingTask?.copy(progress = 0.1f + p * 0.85f))
                    }
                }

                intentRes.fold(
                    onSuccess = { shareIntent ->
                        _uiState.update { st ->
                            st.copy(
                                tracks = st.tracks.map { t ->
                                    if (t.id == trackId) t.copy(sharedWeChatCount = t.sharedWeChatCount + 1) else t
                                }
                            )
                        }
                        if (targetWeChatDirectly && !SessionWorkspaceManager.isWeChatInstalled(appContext)) {
                            sendMessage("未检测到微信客户端，已打开系统分享面板")
                        }
                        _events.send(StudioUiEvent.LaunchShareIntent(shareIntent))
                    },
                    onFailure = { err ->
                        sendMessage("准备分享失败：${err.message}")
                    }
                )
            } finally {
                tempDecodedInput?.delete()
                _uiState.update { it.copy(processingTask = null) }
                refreshStorageInfo()
            }
        }
    }

    fun saveTrackToMusicFolder(trackId: String) {
        val state = _uiState.value
        val track = state.tracks.firstOrNull { it.id == trackId } ?: return
        if (state.processingTask != null) return
        val format = state.exportFormat

        viewModelScope.launch {
            var tempDecodedInput: File? = null
            try {
                _uiState.update {
                    it.copy(
                        processingTask = ProcessingTaskState(
                            title = "正在另存到本地手机存储",
                            subtitle = "导出「${track.displayName}.${format.extension}」至 Music/SoundTracker...",
                            progress = 0.1f,
                            cancellable = false
                        )
                    )
                }

                val sourceForExport = if (format == ExportFormat.MP3_320 && track.audioFile.extension.equals("mp3", true)) {
                    track.audioFile
                } else {
                    ensureWavInput(track.audioFile) { tempDecodedInput = it }
                }

                val res = SessionWorkspaceManager.saveToPublicMusicFolder(
                    context = appContext,
                    sourceAudioFile = sourceForExport,
                    customDisplayName = track.displayName,
                    format = format
                ) { p ->
                    _uiState.update { st ->
                        st.copy(processingTask = st.processingTask?.copy(progress = p))
                    }
                }

                res.fold(
                    onSuccess = { savedPath ->
                        _uiState.update { st ->
                            st.copy(
                                tracks = st.tracks.map { t ->
                                    if (t.id == trackId) t.copy(savedLocalPath = savedPath) else t
                                }
                            )
                        }
                        sendMessage("已保存至手机本地：$savedPath")
                    },
                    onFailure = { err ->
                        sendMessage("另存为失败：${err.message}")
                    }
                )
            } finally {
                tempDecodedInput?.delete()
                _uiState.update { it.copy(processingTask = null) }
                refreshStorageInfo()
            }
        }
    }

    fun saveTrackToCustomUri(trackId: String, targetUri: Uri) {
        val state = _uiState.value
        val track = state.tracks.firstOrNull { it.id == trackId } ?: return
        val format = state.exportFormat

        viewModelScope.launch {
            var tempDecodedInput: File? = null
            try {
                _uiState.update {
                    it.copy(
                        processingTask = ProcessingTaskState(
                            title = "正在写入指定位置",
                            subtitle = "导出「${track.displayName}.${format.extension}」...",
                            progress = 0.1f,
                            cancellable = false
                        )
                    )
                }

                val sourceForExport = if (format == ExportFormat.MP3_320 && track.audioFile.extension.equals("mp3", true)) {
                    track.audioFile
                } else {
                    ensureWavInput(track.audioFile) { tempDecodedInput = it }
                }

                val res = SessionWorkspaceManager.saveToCustomDocumentUri(
                    context = appContext,
                    sourceAudioFile = sourceForExport,
                    targetUri = targetUri,
                    customDisplayName = track.displayName,
                    format = format
                ) { p ->
                    _uiState.update { st ->
                        st.copy(processingTask = st.processingTask?.copy(progress = p))
                    }
                }

                res.fold(
                    onSuccess = { savedName ->
                        _uiState.update { st ->
                            st.copy(
                                tracks = st.tracks.map { t ->
                                    if (t.id == trackId) t.copy(savedLocalPath = "自选目录/$savedName") else t
                                }
                            )
                        }
                        sendMessage("已另存至自选目录：$savedName")
                    },
                    onFailure = { err ->
                        sendMessage("另存失败：${err.message}")
                    }
                )
            } finally {
                tempDecodedInput?.delete()
                _uiState.update { it.copy(processingTask = null) }
                refreshStorageInfo()
            }
        }
    }

    fun deleteSessionTrack(trackId: String) {
        val target = _uiState.value.tracks.firstOrNull { it.id == trackId } ?: return
        deleteStoredResourcesByPaths(setOf(target.audioFile.absolutePath))
    }

    /**
     * Deletes a single resource item from the Storage Resource Manager list.
     */
    fun deleteStoredResourceItem(filePath: String) {
        deleteStoredResourcesByPaths(setOf(filePath))
    }

    /**
     * Batch-deletes multiple checked resource items from the Storage Resource Manager list.
     */
    fun deleteSelectedStoredResources(filePaths: Set<String>) {
        if (filePaths.isEmpty()) return
        deleteStoredResourcesByPaths(filePaths)
    }

    private fun deleteStoredResourcesByPaths(filePaths: Set<String>) {
        if (filePaths.isEmpty()) return
        val state = _uiState.value
        if (loadedPlayerFilePath != null && loadedPlayerFilePath in filePaths) {
            exoPlayer.stop()
            loadedPlayerFilePath = null
        }

        val freedBytes = SessionWorkspaceManager.deleteStoredResources(appContext, filePaths)
        val remaining = state.tracks.filterNot { it.audioFile.absolutePath in filePaths || !it.audioFile.exists() }
        val remainingIds = remaining.map { it.id }.toSet()
        val nextSelectedTrack = remaining.firstOrNull { it.id == state.selectedTrackId } ?: remaining.firstOrNull()
        val nextBpm = nextSelectedTrack?.metrics?.detectedBpm?.roundToInt()?.coerceIn(45, 220) ?: 120

        val cleanedArrangerTracks = state.arranger.tracks.map { aTrack ->
            aTrack.copy(
                clips = aTrack.clips.filter { clip ->
                    clip.sourceTrackId in remainingIds && clip.wavFile.exists() && clip.wavFile.absolutePath !in filePaths
                }
            )
        }
        val cleanedSelectedClipId = state.arranger.selectedClipId?.takeIf { cid ->
            cleanedArrangerTracks.any { t -> t.clips.any { it.id == cid } }
        }

        val switchedTrack = nextSelectedTrack?.id != state.selectedTrackId
        _uiState.update {
            it.copy(
                tracks = remaining,
                selectedTrackId = nextSelectedTrack?.id,
                isPlaying = if (loadedPlayerFilePath == null) false else it.isPlaying,
                isComparingOriginal = false,
                playbackPositionMs = if (switchedTrack) 0L else it.playbackPositionMs,
                playbackDurationMs = ((nextSelectedTrack?.metrics?.durationSec ?: 0f) * 1000f).toLong(),
                pitchSemitones = if (switchedTrack) 0 else it.pitchSemitones,
                pitchCents = if (switchedTrack) 0 else it.pitchCents,
                sourceBpm = if (switchedTrack) nextBpm else it.sourceBpm,
                targetBpm = if (switchedTrack) nextBpm else it.targetBpm,
                volumeGainDb = if (switchedTrack) 0f else it.volumeGainDb,
                arranger = it.arranger.copy(
                    tracks = cleanedArrangerTracks,
                    selectedClipId = cleanedSelectedClipId
                )
            )
        }
        arrangerPlayer.clearReaderCache()
        syncArrangerPlayerSchedule()
        syncRealtimePreviewProcessor()
        refreshStorageInfo()
        val freedMb = String.format(Locale.US, "%.1f MB", freedBytes / (1024.0 * 1024.0))
        sendMessage("已删除 ${filePaths.size} 项资源，释放 $freedMb")
    }

    /**
     * One-tap "一键全清": deletes all stored workspace tracks, share staging exports, and temp files.
     */
    fun clearAllWorkspaceTracks() {
        exoPlayer.stop()
        arrangerPlayer.pause()
        arrangerPlayer.clearReaderCache()
        arrangerUndoStack.clear()
        arrangerRedoStack.clear()
        highResPeaksCache.clear()
        loadedPlayerFilePath = null
        SessionWorkspaceManager.wipeSessionWorkspace(appContext)
        val freshTrack = ArrangerTrack(name = "音轨 1", colorIndex = 0)
        _uiState.update {
            it.copy(
                tracks = emptyList(),
                selectedTrackId = null,
                isPlaying = false,
                isComparingOriginal = false,
                playbackPositionMs = 0L,
                playbackDurationMs = 0L,
                pitchSemitones = 0,
                pitchCents = 0,
                volumeGainDb = 0f,
                arranger = ArrangerUiState(
                    tracks = listOf(freshTrack),
                    selectedTrackId = freshTrack.id
                )
            )
        }
        syncArrangerPlayerSchedule()
        syncRealtimePreviewProcessor()
        refreshStorageInfo()
        sendMessage("已一键清空全部应用内音频资源")
    }

    // ========================================================================
    // Internal Helpers
    // ========================================================================

    private suspend fun ensureWavInput(file: File, registerTemp: (File) -> Unit): File {
        if (file.extension.equals("wav", ignoreCase = true)) {
            return file
        }
        val decoded = File(SessionWorkspaceManager.tempDir(appContext), "rehydrate_${System.nanoTime()}.wav")
        registerTemp(decoded)
        val ok = AudioCodecHelper.decodeUriToWav(
            context = appContext,
            uri = Uri.fromFile(file),
            tempDir = SessionWorkspaceManager.tempDir(appContext),
            targetWav = decoded,
            onProgress = {}
        )
        check(ok && decoded.exists()) { "无法读取历史音轨" }
        return decoded
    }

    private fun appendTrackToWorkspace(newTrack: SessionTrack) {
        val detectedBpm = newTrack.metrics.detectedBpm.roundToInt().coerceIn(45, 220)
        val updatedList = listOf(newTrack) + _uiState.value.tracks

        _uiState.update {
            it.copy(
                tracks = updatedList,
                selectedTrackId = newTrack.id,
                isComparingOriginal = false,
                playbackPositionMs = 0L,
                playbackDurationMs = (newTrack.metrics.durationSec * 1000f).toLong(),
                pitchSemitones = 0,
                pitchCents = 0,
                sourceBpm = detectedBpm,
                targetBpm = detectedBpm,
                volumeGainDb = 0f
            )
        }
        syncRealtimePreviewProcessor()
        preparePlayerForTrack(newTrack, seekMs = 0L, playImmediately = false)
    }

    private fun formatPitchTag(semitones: Int, cents: Int): String {
        val base = when {
            semitones > 0 -> "升${semitones}调"
            semitones < 0 -> "降${abs(semitones)}调"
            else -> "微调"
        }
        return if (cents == 0) {
            base
        } else {
            val cSign = if (cents > 0) "+$cents" else "$cents"
            "${base}${cSign}音分"
        }
    }

    private fun stripKnownSuffix(name: String, suffix: String): String =
        if (name.endsWith(suffix)) name.removeSuffix(suffix) else name

    private fun appendSmartTag(baseName: String, tag: String): String {
        val clean = AudioCodecHelper.sanitizeFileName(baseName)
        return AudioCodecHelper.sanitizeFileName("${clean}_$tag")
    }

    private fun formatLufs(lufs: Float): String =
        if (lufs <= -68f) "静音" else String.format(Locale.US, "%.1f LUFS", lufs)

    private fun formatMetricsSummary(m: AudioMetrics): String {
        val bpm = "${m.detectedBpm.roundToInt()} BPM"
        val srKhz = String.format(Locale.US, "%.1fkHz", m.sampleRate / 1000f)
        val lufs = formatLufs(m.integratedLufs)
        val peak = String.format(Locale.US, "峰值 %.1f dBTP", m.truePeakDbtp)
        return "$bpm · $srKhz · $lufs · $peak"
    }

    private fun sendMessage(msg: String) {
        _events.trySend(StudioUiEvent.ShowMessage(msg))
    }

    // ========================================================================
    // Feature 5: Multi-Track DAW Arranger & Splicing Studio (1..10 Tracks)
    // ========================================================================

    fun setWorkspaceMode(mode: AppWorkspaceMode) {
        if (_uiState.value.workspaceMode == mode) return
        if (_uiState.value.workspaceMode == AppWorkspaceMode.MELODY_STUDIO) {
            flushMelodyAutoSaveNow()
        }
        when (mode) {
            AppWorkspaceMode.SINGLE_TRACK -> {
                arrangerPlayer.pause()
                stopMelodyPlaybackAndEngine()
            }
            AppWorkspaceMode.MELODY_STUDIO -> {
                if (exoPlayer.isPlaying) exoPlayer.pause()
                arrangerPlayer.pause()
                _uiState.update { it.copy(workspaceMode = mode) }
                ensureMelodyStudioReady()
                return
            }
            AppWorkspaceMode.MULTI_TRACK_ARRANGER -> {
                if (exoPlayer.isPlaying) exoPlayer.pause()
                stopMelodyPlaybackAndEngine()
                syncArrangerPlayerSchedule()
            }
        }
        _uiState.update { it.copy(workspaceMode = mode) }
    }

    private fun pushArrangerUndoSnapshot() {
        val arr = _uiState.value.arranger
        arrangerUndoStack.addLast(
            ArrangerSnapshot(
                tracks = arr.tracks,
                selectedTrackId = arr.effectiveSelectedTrackId,
                selectedClipId = arr.selectedClipId
            )
        )
        while (arrangerUndoStack.size > 30) {
            arrangerUndoStack.removeFirst()
        }
        arrangerRedoStack.clear()
        updateArrangerUndoFlags()
    }

    private fun updateArrangerUndoFlags() {
        _uiState.update { st ->
            st.copy(
                arranger = st.arranger.copy(
                    canUndo = arrangerUndoStack.isNotEmpty(),
                    canRedo = arrangerRedoStack.isNotEmpty()
                )
            )
        }
    }

    fun beginArrangerGestureUndoSnapshot() {
        pushArrangerUndoSnapshot()
    }

    fun undoArranger() {
        val snap = arrangerUndoStack.removeLastOrNull() ?: return
        val cur = _uiState.value.arranger
        arrangerRedoStack.addLast(
            ArrangerSnapshot(
                tracks = cur.tracks,
                selectedTrackId = cur.effectiveSelectedTrackId,
                selectedClipId = cur.selectedClipId
            )
        )
        _uiState.update { st ->
            st.copy(
                arranger = st.arranger.copy(
                    tracks = snap.tracks,
                    selectedTrackId = snap.selectedTrackId,
                    selectedClipId = snap.selectedClipId,
                    canUndo = arrangerUndoStack.isNotEmpty(),
                    canRedo = arrangerRedoStack.isNotEmpty()
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    fun redoArranger() {
        val snap = arrangerRedoStack.removeLastOrNull() ?: return
        val cur = _uiState.value.arranger
        arrangerUndoStack.addLast(
            ArrangerSnapshot(
                tracks = cur.tracks,
                selectedTrackId = cur.effectiveSelectedTrackId,
                selectedClipId = cur.selectedClipId
            )
        )
        _uiState.update { st ->
            st.copy(
                arranger = st.arranger.copy(
                    tracks = snap.tracks,
                    selectedTrackId = snap.selectedTrackId,
                    selectedClipId = snap.selectedClipId,
                    canUndo = arrangerUndoStack.isNotEmpty(),
                    canRedo = arrangerRedoStack.isNotEmpty()
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    private fun syncArrangerPlayerSchedule(state: StudioUiState = _uiState.value) {
        val (nativeClips, totalFrames) = state.arranger.buildNativeSchedule(ARRANGER_SAMPLE_RATE)
        arrangerPlayer.updateSchedule(nativeClips, totalFrames)
    }

    fun toggleArrangerPlayPause() {
        val arr = _uiState.value.arranger
        if (arrangerPlayer.isPlaying) {
            arrangerPlayer.pause()
        } else {
            if (arr.totalDurationMs <= 0L) {
                sendMessage("请先点击「+ 添加片段」把音乐放到音轨上")
                return
            }
            if (exoPlayer.isPlaying) exoPlayer.pause()
            syncArrangerPlayerSchedule()
            arrangerPlayer.play(arr.playheadMs)
        }
    }

    fun seekArrangerToMs(targetMs: Long) {
        val arr = _uiState.value.arranger
        val maxAllowed = (arr.totalDurationMs + 30_000L).coerceAtLeast(60_000L)
        val clamped = targetMs.coerceIn(0L, maxAllowed)
        arrangerPlayer.seekToMs(clamped)
        _uiState.update { st ->
            st.copy(arranger = st.arranger.copy(playheadMs = clamped))
        }
    }

    fun setArrangerZoom(dpPerSec: Float) {
        val clamped = dpPerSec.coerceIn(6f, 120f)
        _uiState.update { st ->
            st.copy(arranger = st.arranger.copy(zoomDpPerSec = clamped))
        }
    }

    fun addArrangerTrack() {
        val curTracks = _uiState.value.arranger.tracks
        if (curTracks.size >= MAX_ARRANGER_TRACKS) {
            sendMessage("最多支持添加 $MAX_ARRANGER_TRACKS 条音轨")
            return
        }
        pushArrangerUndoSnapshot()
        val usedNumbers = curTracks.mapNotNull { t ->
            t.name.removePrefix("音轨 ").trim().toIntOrNull()
        }.toSet()
        var nextNum = curTracks.size + 1
        while (nextNum in usedNumbers) nextNum++
        val newTrack = ArrangerTrack(
            name = "音轨 $nextNum",
            colorIndex = (nextNum - 1) % 6
        )
        _uiState.update { st ->
            st.copy(
                arranger = st.arranger.copy(
                    tracks = st.arranger.tracks + newTrack,
                    selectedTrackId = newTrack.id,
                    selectedClipId = null
                )
            )
        }
    }

    fun selectArrangerTrack(trackId: String) {
        _uiState.update { st ->
            val keepClip = st.arranger.selectedClipWithTrack?.let { (t, c) ->
                if (t.id == trackId) c.id else null
            }
            st.copy(
                arranger = st.arranger.copy(
                    selectedTrackId = trackId,
                    selectedClipId = keepClip
                )
            )
        }
    }

    fun selectArrangerClip(clipId: String?) {
        _uiState.update { st ->
            if (clipId == null) {
                st.copy(arranger = st.arranger.copy(selectedClipId = null))
            } else {
                val ownerTrack = st.arranger.tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }
                st.copy(
                    arranger = st.arranger.copy(
                        selectedClipId = clipId,
                        selectedTrackId = ownerTrack?.id ?: st.arranger.effectiveSelectedTrackId
                    )
                )
            }
        }
    }

    fun toggleArrangerTrackMute(trackId: String) {
        pushArrangerUndoSnapshot()
        _uiState.update { st ->
            st.copy(
                arranger = st.arranger.copy(
                    tracks = st.arranger.tracks.map { t ->
                        if (t.id == trackId) t.copy(muted = !t.muted) else t
                    }
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    fun toggleArrangerTrackSolo(trackId: String) {
        pushArrangerUndoSnapshot()
        _uiState.update { st ->
            st.copy(
                arranger = st.arranger.copy(
                    tracks = st.arranger.tracks.map { t ->
                        if (t.id == trackId) t.copy(solo = !t.solo) else t
                    }
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    fun setArrangerTrackVolumeDb(trackId: String, gainDb: Float) {
        val rounded = ((gainDb.coerceIn(-18f, 12f) * 2f).roundToInt()) / 2f
        _uiState.update { st ->
            st.copy(
                arranger = st.arranger.copy(
                    tracks = st.arranger.tracks.map { t ->
                        if (t.id == trackId) t.copy(volumeDb = rounded) else t
                    }
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    fun removeArrangerTrack(trackId: String) {
        val cur = _uiState.value.arranger
        pushArrangerUndoSnapshot()
        if (cur.tracks.size <= 1) {
            val single = cur.tracks.first().copy(clips = emptyList(), muted = false, solo = false, volumeDb = 0f)
            _uiState.update { st ->
                st.copy(
                    arranger = st.arranger.copy(
                        tracks = listOf(single),
                        selectedTrackId = single.id,
                        selectedClipId = null
                    )
                )
            }
            syncArrangerPlayerSchedule()
            sendMessage("已清空音轨片段")
            return
        }

        val idx = cur.tracks.indexOfFirst { it.id == trackId }
        val remaining = cur.tracks.filterNot { it.id == trackId }
        val nextSelected = remaining.getOrElse((idx - 1).coerceAtLeast(0)) { remaining.first() }
        val keepClip = cur.selectedClipId?.takeIf { cid ->
            remaining.any { t -> t.clips.any { it.id == cid } }
        }
        _uiState.update { st ->
            st.copy(
                arranger = st.arranger.copy(
                    tracks = remaining,
                    selectedTrackId = nextSelected.id,
                    selectedClipId = keepClip
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    fun clearArrangerProject() {
        arrangerPlayer.pause()
        pushArrangerUndoSnapshot()
        val fresh = ArrangerTrack(name = "音轨 1", colorIndex = 0)
        _uiState.update { st ->
            st.copy(
                arranger = st.arranger.copy(
                    tracks = listOf(fresh),
                    selectedTrackId = fresh.id,
                    selectedClipId = null,
                    playheadMs = 0L,
                    isPlaying = false
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    private suspend fun appendImportedTrackToArrangerDirect(
        newTrack: SessionTrack,
        decodedTempWav: File,
        recordUndo: Boolean
    ) {
        val backingWav: File = if (newTrack.audioFile.extension.equals("wav", ignoreCase = true)) {
            newTrack.audioFile
        } else {
            val cachedWav = File(
                SessionWorkspaceManager.tempDir(appContext),
                "arranger_pcm_${newTrack.id}.wav"
            )
            if (decodedTempWav.exists()) {
                if (!decodedTempWav.renameTo(cachedWav)) {
                    runCatching { decodedTempWav.copyTo(cachedWav, overwrite = true) }
                    decodedTempWav.delete()
                }
            }
            if (cachedWav.exists() && cachedWav.length() > 44L) cachedWav else return
        }

        val highResPeaks = NativeAudioEngine.extractFastWavPeaks(backingWav, 480)?.also {
            highResPeaksCache[newTrack.id] = it
        } ?: newTrack.metrics.waveformPeaks

        val fullDurMs = ((newTrack.metrics.durationSec * 1000f).roundToInt().toLong())
            .coerceAtLeast(MIN_CLIP_DURATION_MS)
        if (recordUndo) {
            pushArrangerUndoSnapshot()
        }

        val arr = _uiState.value.arranger
        val targetTrack = arr.selectedTrack
        val startMs = targetTrack.clips.maxOfOrNull { it.timelineEndMs } ?: 0L
        val distinctColor = (arr.totalClipCount + arr.tracks.indexOfFirst { it.id == targetTrack.id }.coerceAtLeast(0)) % 6

        val newClip = ArrangerClip(
            sourceTrackId = newTrack.id,
            sourceTitle = newTrack.displayName,
            wavFile = backingWav,
            sourceDurationMs = fullDurMs,
            timelineStartMs = startMs,
            sourceInMs = 0L,
            sourceOutMs = fullDurMs,
            colorIndex = distinctColor,
            fullWaveformPeaks = highResPeaks
        )

        _uiState.update { st ->
            val updatedTracks = st.arranger.tracks.map { t ->
                if (t.id == targetTrack.id) {
                    t.copy(clips = (t.clips + newClip).sortedBy { it.timelineStartMs })
                } else {
                    t
                }
            }
            st.copy(
                arranger = st.arranger.copy(
                    tracks = updatedTracks,
                    selectedTrackId = targetTrack.id,
                    selectedClipId = newClip.id
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    private suspend fun ensureArrangerBackingWav(track: SessionTrack): File? {
        if (!track.audioFile.exists()) return null
        if (track.audioFile.extension.equals("wav", ignoreCase = true)) {
            return track.audioFile
        }
        val cachedWav = File(
            SessionWorkspaceManager.tempDir(appContext),
            "arranger_pcm_${track.id}.wav"
        )
        if (cachedWav.exists() && cachedWav.length() > 44L) {
            return cachedWav
        }
        val ok = AudioCodecHelper.decodeUriToWav(
            context = appContext,
            uri = Uri.fromFile(track.audioFile),
            tempDir = SessionWorkspaceManager.tempDir(appContext),
            targetWav = cachedWav,
            onProgress = { p ->
                _uiState.update { st ->
                    st.copy(
                        processingTask = st.processingTask?.copy(
                            progress = (0.1f + p * 0.85f).coerceIn(0.1f, 0.95f)
                        )
                    )
                }
            }
        )
        return if (ok && cachedWav.exists()) cachedWav else null
    }

    /**
     * Adds a full song or a pre-selected slice [customInMs..customOutMs] onto an Arranger track.
     * - [placeAtPlayhead]: if true, places at current playheadMs; if false, appends right after the end of the target track.
     */
    fun addTrackToArranger(
        sessionTrackId: String,
        customInMs: Long? = null,
        customOutMs: Long? = null,
        placeAtPlayhead: Boolean = false,
        switchToArranger: Boolean = true
    ) {
        val sourceTrack = _uiState.value.tracks.firstOrNull { it.id == sessionTrackId } ?: return
        if (_uiState.value.processingTask != null) return

        viewModelScope.launch {
            val needsDecode = !sourceTrack.audioFile.extension.equals("wav", ignoreCase = true) &&
                !File(SessionWorkspaceManager.tempDir(appContext), "arranger_pcm_${sourceTrack.id}.wav").exists()

            try {
                if (needsDecode) {
                    _uiState.update {
                        it.copy(
                            processingTask = ProcessingTaskState(
                                title = "正在准备剪辑音频",
                                subtitle = "正在解码「${sourceTrack.displayName}」以便毫秒级顺滑剪切...",
                                progress = 0.1f,
                                cancellable = false
                            )
                        )
                    }
                }

                val wavFile = ensureArrangerBackingWav(sourceTrack) ?: run {
                    sendMessage("无法加载该音频到多轨剪辑台")
                    return@launch
                }

                val highResPeaks = highResPeaksCache[sourceTrack.id]
                    ?: NativeAudioEngine.extractFastWavPeaks(wavFile, 480)?.also {
                        highResPeaksCache[sourceTrack.id] = it
                    }
                    ?: sourceTrack.metrics.waveformPeaks

                val fullDurMs = ((sourceTrack.metrics.durationSec * 1000f).roundToInt().toLong())
                    .coerceAtLeast(MIN_CLIP_DURATION_MS)
                val inMs = (customInMs ?: 0L).coerceIn(0L, (fullDurMs - MIN_CLIP_DURATION_MS).coerceAtLeast(0L))
                val outMs = (customOutMs ?: fullDurMs).coerceIn(inMs + MIN_CLIP_DURATION_MS, fullDurMs)

                pushArrangerUndoSnapshot()
                val arr = _uiState.value.arranger
                val targetTrack = arr.selectedTrack
                val startMs = if (placeAtPlayhead) {
                    arr.playheadMs.coerceAtLeast(0L)
                } else {
                    targetTrack.clips.maxOfOrNull { it.timelineEndMs } ?: 0L
                }

                val distinctColor = (_uiState.value.tracks.indexOfFirst { it.id == sourceTrack.id }.coerceAtLeast(0) +
                    arr.tracks.indexOfFirst { it.id == targetTrack.id }.coerceAtLeast(0)) % 6

                val newClip = ArrangerClip(
                    sourceTrackId = sourceTrack.id,
                    sourceTitle = sourceTrack.displayName,
                    wavFile = wavFile,
                    sourceDurationMs = fullDurMs,
                    timelineStartMs = startMs,
                    sourceInMs = inMs,
                    sourceOutMs = outMs,
                    colorIndex = distinctColor,
                    fullWaveformPeaks = highResPeaks
                )

                if (switchToArranger && exoPlayer.isPlaying) {
                    exoPlayer.pause()
                }

                _uiState.update { st ->
                    val updatedTracks = st.arranger.tracks.map { t ->
                        if (t.id == targetTrack.id) {
                            t.copy(clips = (t.clips + newClip).sortedBy { it.timelineStartMs })
                        } else {
                            t
                        }
                    }
                    st.copy(
                        workspaceMode = if (switchToArranger) AppWorkspaceMode.MULTI_TRACK_ARRANGER else st.workspaceMode,
                        arranger = st.arranger.copy(
                            tracks = updatedTracks,
                            selectedTrackId = targetTrack.id,
                            selectedClipId = newClip.id
                        )
                    )
                }
                syncArrangerPlayerSchedule()
                if (needsDecode) refreshStorageInfo()
                sendMessage("已添加到「${targetTrack.name}」")
            } finally {
                if (needsDecode) {
                    _uiState.update { it.copy(processingTask = null) }
                }
            }
        }
    }

    fun splitArrangerClipAtPlayhead() {
        val arr = _uiState.value.arranger
        val ph = arr.playheadMs

        // Candidate priority: 1) Selected clip, 2) Clip on selected track, 3) Unique clip across any track
        val targetPair: Pair<ArrangerTrack, ArrangerClip>? = run {
            arr.selectedClipWithTrack?.let { (t, c) ->
                if (ph >= c.timelineStartMs + MIN_CLIP_DURATION_MS && ph <= c.timelineEndMs - MIN_CLIP_DURATION_MS) {
                    return@run t to c
                }
            }
            val onSelectedTrack = arr.selectedTrack.clips.firstOrNull { c ->
                ph >= c.timelineStartMs + MIN_CLIP_DURATION_MS && ph <= c.timelineEndMs - MIN_CLIP_DURATION_MS
            }
            if (onSelectedTrack != null) return@run arr.selectedTrack to onSelectedTrack

            val acrossAll = arr.tracks.flatMap { t ->
                t.clips.filter { c ->
                    ph >= c.timelineStartMs + MIN_CLIP_DURATION_MS && ph <= c.timelineEndMs - MIN_CLIP_DURATION_MS
                }.map { t to it }
            }
            acrossAll.firstOrNull()
        }

        if (targetPair == null) {
            sendMessage("请先滑动波形，把中间指针停在要切开的音频片段内部")
            return
        }

        val (track, clip) = targetPair
        val cutOffsetMs = ph - clip.timelineStartMs
        val splitSourceMs = clip.sourceInMs + cutOffsetMs

        val leftClip = clip.copy(
            id = UUID.randomUUID().toString(),
            sourceOutMs = splitSourceMs,
            fadeOutMs = 0L
        )
        val rightClip = clip.copy(
            id = UUID.randomUUID().toString(),
            timelineStartMs = ph,
            sourceInMs = splitSourceMs,
            fadeInMs = 0L
        )

        pushArrangerUndoSnapshot()
        _uiState.update { st ->
            val updatedTracks = st.arranger.tracks.map { t ->
                if (t.id == track.id) {
                    val replaced = t.clips.flatMap { c ->
                        if (c.id == clip.id) listOf(leftClip, rightClip) else listOf(c)
                    }.sortedBy { it.timelineStartMs }
                    t.copy(clips = replaced)
                } else {
                    t
                }
            }
            st.copy(
                arranger = st.arranger.copy(
                    tracks = updatedTracks,
                    selectedTrackId = track.id,
                    selectedClipId = rightClip.id
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    fun deleteSelectedArrangerClip() {
        val cid = _uiState.value.arranger.selectedClipId ?: return
        pushArrangerUndoSnapshot()
        _uiState.update { st ->
            val updatedTracks = st.arranger.tracks.map { t ->
                t.copy(clips = t.clips.filterNot { it.id == cid })
            }
            st.copy(
                arranger = st.arranger.copy(
                    tracks = updatedTracks,
                    selectedClipId = null
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    fun duplicateSelectedArrangerClip() {
        val (track, clip) = _uiState.value.arranger.selectedClipWithTrack ?: return
        pushArrangerUndoSnapshot()
        val copyClip = clip.copy(
            id = UUID.randomUUID().toString(),
            timelineStartMs = clip.timelineEndMs
        )
        _uiState.update { st ->
            val updatedTracks = st.arranger.tracks.map { t ->
                if (t.id == track.id) {
                    t.copy(clips = (t.clips + copyClip).sortedBy { it.timelineStartMs })
                } else {
                    t
                }
            }
            st.copy(
                arranger = st.arranger.copy(
                    tracks = updatedTracks,
                    selectedClipId = copyClip.id
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    fun moveArrangerClipRealtime(
        clipId: String,
        newTimelineStartMs: Long,
        targetTrackIndex: Int
    ) {
        val clampedStart = newTimelineStartMs.coerceAtLeast(0L)
        _uiState.update { st ->
            val tracks = st.arranger.tracks
            if (tracks.isEmpty()) return@update st
            val dstIdx = targetTrackIndex.coerceIn(0, tracks.lastIndex)

            var movingClip: ArrangerClip? = null
            for (t in tracks) {
                val found = t.clips.firstOrNull { it.id == clipId }
                if (found != null) {
                    movingClip = found
                    break
                }
            }
            val updatedClip = movingClip?.copy(timelineStartMs = clampedStart) ?: return@update st

            val newTracks = tracks.mapIndexed { idx, t ->
                val without = t.clips.filterNot { it.id == clipId }
                if (idx == dstIdx) {
                    t.copy(clips = (without + updatedClip).sortedBy { it.timelineStartMs })
                } else {
                    t.copy(clips = without)
                }
            }

            st.copy(
                arranger = st.arranger.copy(
                    tracks = newTracks,
                    selectedTrackId = newTracks[dstIdx].id,
                    selectedClipId = clipId
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    fun moveSelectedClipToAdjacentTrack(deltaTrack: Int) {
        val arr = _uiState.value.arranger
        val (curTrack, clip) = arr.selectedClipWithTrack ?: return
        val curIdx = arr.tracks.indexOfFirst { it.id == curTrack.id }
        if (curIdx < 0) return

        val dstIdx = curIdx + deltaTrack
        if (dstIdx < 0) return

        if (dstIdx >= arr.tracks.size) {
            if (arr.tracks.size >= MAX_ARRANGER_TRACKS) {
                sendMessage("已达最大音轨上限 ($MAX_ARRANGER_TRACKS 条)")
                return
            }
            // Auto-create next track and move clip directly onto it!
            pushArrangerUndoSnapshot()
            val nextNum = arr.tracks.size + 1
            val createdTrack = ArrangerTrack(
                name = "音轨 $nextNum",
                colorIndex = (nextNum - 1) % 6,
                clips = listOf(clip)
            )
            _uiState.update { st ->
                val stripped = st.arranger.tracks.map { t ->
                    if (t.id == curTrack.id) t.copy(clips = t.clips.filterNot { it.id == clip.id }) else t
                }
                st.copy(
                    arranger = st.arranger.copy(
                        tracks = stripped + createdTrack,
                        selectedTrackId = createdTrack.id,
                        selectedClipId = clip.id
                    )
                )
            }
            syncArrangerPlayerSchedule()
            return
        }

        pushArrangerUndoSnapshot()
        moveArrangerClipRealtime(clip.id, clip.timelineStartMs, dstIdx)
    }

    fun nudgeSelectedClipPosition(deltaMs: Long) {
        val (track, clip) = _uiState.value.arranger.selectedClipWithTrack ?: return
        val trackIdx = _uiState.value.arranger.tracks.indexOfFirst { it.id == track.id }
        if (trackIdx < 0) return
        pushArrangerUndoSnapshot()
        moveArrangerClipRealtime(clip.id, (clip.timelineStartMs + deltaMs).coerceAtLeast(0L), trackIdx)
    }

    fun snapSelectedClipToPlayhead() {
        val arr = _uiState.value.arranger
        val (track, clip) = arr.selectedClipWithTrack ?: return
        val trackIdx = arr.tracks.indexOfFirst { it.id == track.id }
        if (trackIdx < 0) return
        pushArrangerUndoSnapshot()
        moveArrangerClipRealtime(clip.id, arr.playheadMs.coerceAtLeast(0L), trackIdx)
    }

    fun snapSelectedClipToPrevious() {
        val arr = _uiState.value.arranger
        val (track, clip) = arr.selectedClipWithTrack ?: return
        val trackIdx = arr.tracks.indexOfFirst { it.id == track.id }
        if (trackIdx < 0) return
        val prevEnd = track.clips
            .filter { it.id != clip.id && it.timelineStartMs < clip.timelineStartMs }
            .maxOfOrNull { it.timelineEndMs } ?: 0L
        pushArrangerUndoSnapshot()
        moveArrangerClipRealtime(clip.id, prevEnd, trackIdx)
    }

    fun trimArrangerClipRealtime(
        clipId: String,
        newTimelineStartMs: Long,
        newSourceInMs: Long,
        newSourceOutMs: Long
    ) {
        _uiState.update { st ->
            val updatedTracks = st.arranger.tracks.map { t ->
                val idx = t.clips.indexOfFirst { it.id == clipId }
                if (idx < 0) {
                    t
                } else {
                    val c = t.clips[idx]
                    val safeIn = newSourceInMs.coerceIn(0L, (c.sourceDurationMs - MIN_CLIP_DURATION_MS).coerceAtLeast(0L))
                    val safeOut = newSourceOutMs.coerceIn(safeIn + MIN_CLIP_DURATION_MS, c.sourceDurationMs)
                    val trimmed = c.copy(
                        timelineStartMs = newTimelineStartMs.coerceAtLeast(0L),
                        sourceInMs = safeIn,
                        sourceOutMs = safeOut
                    )
                    t.copy(
                        clips = t.clips.map { if (it.id == clipId) trimmed else it }.sortedBy { it.timelineStartMs }
                    )
                }
            }
            st.copy(arranger = st.arranger.copy(tracks = updatedTracks))
        }
        syncArrangerPlayerSchedule()
    }

    fun nudgeSelectedClipTrim(isLeftHandle: Boolean, deltaMs: Long) {
        val (_, clip) = _uiState.value.arranger.selectedClipWithTrack ?: return
        pushArrangerUndoSnapshot()
        if (isLeftHandle) {
            val maxShiftRight = clip.durationMs - MIN_CLIP_DURATION_MS
            val maxShiftLeft = minOf(clip.sourceInMs, clip.timelineStartMs)
            val actualDelta = deltaMs.coerceIn(-maxShiftLeft, maxShiftRight)
            trimArrangerClipRealtime(
                clipId = clip.id,
                newTimelineStartMs = clip.timelineStartMs + actualDelta,
                newSourceInMs = clip.sourceInMs + actualDelta,
                newSourceOutMs = clip.sourceOutMs
            )
        } else {
            val nextOut = (clip.sourceOutMs + deltaMs).coerceIn(
                clip.sourceInMs + MIN_CLIP_DURATION_MS,
                clip.sourceDurationMs
            )
            trimArrangerClipRealtime(
                clipId = clip.id,
                newTimelineStartMs = clip.timelineStartMs,
                newSourceInMs = clip.sourceInMs,
                newSourceOutMs = nextOut
            )
        }
    }

    fun setSelectedClipGainDb(gainDb: Float) {
        val cid = _uiState.value.arranger.selectedClipId ?: return
        val rounded = ((gainDb.coerceIn(-18f, 12f) * 2f).roundToInt()) / 2f
        _uiState.update { st ->
            st.copy(
                arranger = st.arranger.copy(
                    tracks = st.arranger.tracks.map { t ->
                        t.copy(
                            clips = t.clips.map { c ->
                                if (c.id == cid) c.copy(gainDb = rounded) else c
                            }
                        )
                    }
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    fun setSelectedClipFades(fadeInMs: Long, fadeOutMs: Long) {
        val cid = _uiState.value.arranger.selectedClipId ?: return
        pushArrangerUndoSnapshot()
        _uiState.update { st ->
            st.copy(
                arranger = st.arranger.copy(
                    tracks = st.arranger.tracks.map { t ->
                        t.copy(
                            clips = t.clips.map { c ->
                                if (c.id == cid) {
                                    val half = c.durationMs / 2
                                    c.copy(
                                        fadeInMs = fadeInMs.coerceIn(0L, half),
                                        fadeOutMs = fadeOutMs.coerceIn(0L, half)
                                    )
                                } else c
                            }
                        )
                    }
                )
            )
        }
        syncArrangerPlayerSchedule()
    }

    fun exportArrangerMixdown() {
        val state = _uiState.value
        val arr = state.arranger
        if (arr.totalClipCount == 0 || arr.totalDurationMs <= 0L) {
            sendMessage("请先在音轨上添加音频片段后再合成")
            return
        }
        if (state.processingTask != null) return
        pausePlayback()

        val (nativeClips, totalFrames) = arr.buildNativeSchedule(ARRANGER_SAMPLE_RATE)
        if (nativeClips.isEmpty()) {
            sendMessage("当前没有可输出的有声片段（请检查音轨是否已全部静音）")
            return
        }

        val activeTrackCount = arr.tracks.count { it.clips.isNotEmpty() }
        val clipCount = arr.totalClipCount

        activeProcessingJob = viewModelScope.launch {
            val outWav = SessionWorkspaceManager.createSessionTrackFile(appContext, "mashup", "wav")
            try {
                NativeAudioEngine.nativeResetCancel()
                _uiState.update {
                    it.copy(
                        processingTask = ProcessingTaskState(
                            title = "正在合成多轨混剪音频",
                            subtitle = "正在混合 $activeTrackCount 条音轨（共 $clipCount 段）并进行防破音保护...",
                            progress = 0.05f,
                            cancellable = true
                        )
                    )
                }

                val outcome = NativeAudioEngine.renderMultiTrackMixdown(
                    clips = nativeClips,
                    totalDurationFrames = totalFrames,
                    outputSampleRate = ARRANGER_SAMPLE_RATE,
                    outputWav = outWav
                ) { p ->
                    val pct = (p * 100).roundToInt()
                    _uiState.update { st ->
                        st.copy(
                            processingTask = st.processingTask?.copy(
                                progress = p,
                                subtitle = "正在合成多轨音频与防破音保护 · $pct%"
                            )
                        )
                    }
                }

                when (outcome) {
                    VocalSeparationOutcome.Cancelled -> {
                        outWav.delete()
                        sendMessage("已取消多轨合成")
                        return@launch
                    }
                    is VocalSeparationOutcome.Error -> {
                        outWav.delete()
                        sendMessage("多轨合成失败：${outcome.message}")
                        return@launch
                    }
                    VocalSeparationOutcome.Success -> Unit
                }

                if (!outWav.exists()) {
                    sendMessage("多轨合成输出文件缺失")
                    return@launch
                }

                val metrics = NativeAudioEngine.analyzeWav(outWav) ?: error("合成音频分析失败")
                val bpmInt = metrics.detectedBpm.roundToInt()
                val idxStr = String.format(Locale.US, "%02d", mashupExportCounter++)
                val mashupTrack = SessionTrack(
                    displayName = "多轨混剪_$idxStr",
                    badge = "多轨混剪 · ${activeTrackCount}轨${clipCount}段",
                    detailNote = "${activeTrackCount}条音轨合成 · ${formatMetricsSummary(metrics)}",
                    audioFile = outWav,
                    originTrackId = null,
                    metrics = metrics
                ).let { it.copy(originTrackId = it.id) }

                appendTrackToWorkspace(mashupTrack)
                _uiState.update { it.copy(workspaceMode = AppWorkspaceMode.SINGLE_TRACK) }
                sendMessage("多轨混剪合成完成！已切换至单曲工作台，可直接试听、调音或分享到微信")
            } catch (_: CancellationException) {
                outWav.delete()
                sendMessage("已取消多轨合成")
            } catch (e: Exception) {
                outWav.delete()
                sendMessage("多轨合成失败：${e.message ?: "未知错误"}")
            } finally {
                _uiState.update { it.copy(processingTask = null) }
                refreshStorageInfo()
            }
        }
    }

    // ========================================================================
    // Feature 6: GitHub Releases Free OTA Update Check, Download & Install
    // ========================================================================

    fun checkForAppUpdate(manual: Boolean = true) {
        if (_uiState.value.appUpdate.checkStatus == UpdateCheckStatus.CHECKING) return
        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch {
            _uiState.update { st ->
                st.copy(
                    appUpdate = st.appUpdate.copy(
                        checkStatus = UpdateCheckStatus.CHECKING,
                        errorMessage = null
                    )
                )
            }

            val result = AppUpdateManager.fetchLatestRelease(appContext)
            val timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA))

            result.fold(
                onSuccess = { release ->
                    val curVer = _uiState.value.appUpdate.currentVersionName
                    val newer = release != null && AppUpdateManager.isNewerVersion(release.versionName, curVer)
                    val readyFile = if (newer) {
                        AppUpdateManager.findReadyApkFile(appContext, release)
                    } else {
                        null
                    }
                    _uiState.update { st ->
                        st.copy(
                            appUpdate = st.appUpdate.copy(
                                checkStatus = if (newer) UpdateCheckStatus.UPDATE_AVAILABLE else UpdateCheckStatus.UP_TO_DATE,
                                latestRelease = release,
                                hasUpdate = newer,
                                errorMessage = null,
                                lastCheckedTimeText = timeStr,
                                readyApkPath = readyFile?.absolutePath
                            )
                        )
                    }
                    if (manual) {
                        if (newer) {
                            if (release.isIncrementalAvailable) {
                                val mbStr = String.format(
                                    Locale.US,
                                    "%.1f MB",
                                    release.effectiveDownloadBytes / (1024.0 * 1024.0)
                                )
                                sendMessage("发现新版本 ${release.tagName}（支持增量更新，仅需 $mbStr）")
                            } else {
                                sendMessage("发现新版本 ${release.tagName}，可直接下载更新")
                            }
                        } else {
                            sendMessage("当前已是最新版本 v$curVer")
                        }
                    }
                },
                onFailure = { err ->
                    val errText = err.message ?: "网络连接失败，请稍后重试"
                    _uiState.update { st ->
                        st.copy(
                            appUpdate = st.appUpdate.copy(
                                checkStatus = UpdateCheckStatus.ERROR,
                                errorMessage = errText,
                                lastCheckedTimeText = timeStr
                            )
                        )
                    }
                    if (manual) {
                        sendMessage("检查更新失败：$errText")
                    }
                }
            )
        }
    }

    fun setUpdateDownloadChannel(channel: UpdateDownloadChannel) {
        if (_uiState.value.appUpdate.isDownloading) return
        _uiState.update { st ->
            st.copy(
                appUpdate = st.appUpdate.copy(
                    downloadChannel = channel,
                    errorMessage = null
                )
            )
        }
    }

    fun startOrInstallAppUpdate() {
        val upd = _uiState.value.appUpdate
        if (upd.isDownloading) return

        // If already downloaded & verified on disk, jump straight to system PackageInstaller!
        val existingPath = upd.readyApkPath
        if (!existingPath.isNullOrBlank()) {
            val existingFile = File(existingPath)
            if (existingFile.exists() && existingFile.length() > 0L) {
                _events.trySend(StudioUiEvent.RequestInstallApk(existingFile))
                return
            }
        }

        val release = upd.latestRelease ?: run {
            checkForAppUpdate(manual = true)
            return
        }
        val channel = upd.downloadChannel

        updateDownloadJob?.cancel()
        updateDownloadJob = viewModelScope.launch {
            _uiState.update { st ->
                st.copy(
                    appUpdate = st.appUpdate.copy(
                        isDownloading = true,
                        activeDownloadIsDelta = release.isIncrementalAvailable,
                        isMergingPatch = false,
                        mergeProgressFraction = 0f,
                        downloadedBytes = 0L,
                        totalBytes = release.effectiveDownloadBytes,
                        speedBytesPerSec = 0L,
                        errorMessage = null
                    )
                )
            }

            try {
                val res = AppUpdateManager.downloadReleaseApk(
                    context = appContext,
                    release = release,
                    channel = channel,
                    onStageChanged = { isDelta, isMerging, mergeFrac ->
                        _uiState.update { st ->
                            st.copy(
                                appUpdate = st.appUpdate.copy(
                                    activeDownloadIsDelta = isDelta,
                                    isMergingPatch = isMerging,
                                    mergeProgressFraction = mergeFrac
                                )
                            )
                        }
                    }
                ) { downloaded, total, speed ->
                    _uiState.update { st ->
                        st.copy(
                            appUpdate = st.appUpdate.copy(
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                speedBytesPerSec = speed
                            )
                        )
                    }
                }

                res.fold(
                    onSuccess = { apkFile ->
                        _uiState.update { st ->
                            st.copy(
                                appUpdate = st.appUpdate.copy(
                                    isDownloading = false,
                                    isMergingPatch = false,
                                    mergeProgressFraction = 1f,
                                    downloadedBytes = apkFile.length(),
                                    totalBytes = apkFile.length(),
                                    speedBytesPerSec = 0L,
                                    readyApkPath = apkFile.absolutePath,
                                    errorMessage = null
                                )
                            )
                        }
                        refreshStorageInfo()
                        _events.trySend(StudioUiEvent.RequestInstallApk(apkFile))
                    },
                    onFailure = { err ->
                        val msg = err.message ?: "下载更新包失败"
                        _uiState.update { st ->
                            st.copy(
                                appUpdate = st.appUpdate.copy(
                                    isDownloading = false,
                                    isMergingPatch = false,
                                    speedBytesPerSec = 0L,
                                    errorMessage = "$msg（可尝试切换下载通道或用浏览器打开）"
                                )
                            )
                        }
                        refreshStorageInfo()
                    }
                )
            } catch (_: CancellationException) {
                _uiState.update { st ->
                    st.copy(
                        appUpdate = st.appUpdate.copy(
                            isDownloading = false,
                            isMergingPatch = false,
                            speedBytesPerSec = 0L
                        )
                    )
                }
                refreshStorageInfo()
                sendMessage("已暂停下载（保留已下载进度）")
            }
        }
    }

    fun cancelAppUpdateDownload() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
    }

    fun openGithubReleaseInBrowser() {
        val url = _uiState.value.appUpdate.latestRelease?.htmlReleaseUrl
            ?: AppUpdateManager.RELEASES_HOME_URL
        _events.trySend(StudioUiEvent.OpenExternalUrl(url))
    }

    // ========================================================================
    // Feature 6: GarageBand-Style Instrument Keyboard & Melody Score Studio
    // ========================================================================

    private fun ensureMelodyStudioReady() {
        viewModelScope.launch {
            val loaded = if (_uiState.value.melody.soundFontLoaded) {
                true
            } else {
                val ok = NativeAudioEngine.ensureStudioSoundFontLoaded(appContext)
                _uiState.update { st -> st.copy(melody = st.melody.copy(soundFontLoaded = ok)) }
                if (!ok) {
                    sendMessage("乐器采样音源加载失败")
                }
                ok
            }
            if (loaded && _uiState.value.workspaceMode == AppWorkspaceMode.MELODY_STUDIO) {
                syncMelodyInstrumentToEngine()
                syncMelodySequenceToEngine()
                melodyPlayer.startAudioEngine()
            }
        }
    }

    private fun stopMelodyPlaybackAndEngine() {
        previewNoteJob?.cancel()
        if (_uiState.value.melody.isRecording) {
            finalizeAllActiveRecordedNotes()
        } else {
            activeRecordedNotes.clear()
        }
        melodyPlayer.stopAudioEngine()
        _uiState.update { st ->
            if (st.melody.isPlaying || st.melody.isRecording) {
                st.copy(
                    melody = st.melody.copy(
                        isPlaying = false,
                        isRecording = false
                    )
                )
            } else {
                st
            }
        }
    }

    private fun syncMelodyInstrumentToEngine(mel: MelodyStudioUiState = _uiState.value.melody) {
        melodyPlayer.applyInstrument(
            slot = 0,
            instrument = mel.instrument,
            reverbWet = mel.effectiveReverbSend,
            sustainPedal = mel.sustainPedal,
            sustainDurationSec = mel.sustainDurationSec
        )
        melodyPlayer.applyInstrument(
            slot = 1,
            instrument = mel.secondaryInstrument,
            reverbWet = mel.effectiveReverbSend,
            sustainPedal = mel.secondarySustainPedal,
            sustainDurationSec = mel.secondarySustainDurationSec
        )
    }

    private fun syncMelodySequenceToEngine(mel: MelodyStudioUiState = _uiState.value.melody) {
        val compiled = mel.compileSchedule(MELODY_SAMPLE_RATE)
        latestMelodyTempoMap = compiled.tempoMap
        latestCompiledTotalFrames = compiled.totalFrames
        melodyPlayer.setMetronome(
            enabled = mel.metronomeEnabled && (mel.isPlaying || mel.isRecording),
            bpm = compiled.tempoMap.tailBpm,
            numerator = mel.timeSignature.safeNumerator,
            denominator = mel.timeSignature.safeDenominator,
            pulseFrames = compiled.metronomePulseFrames,
            pulseAccents = compiled.metronomePulseAccents
        )
        melodyPlayer.updateSequence(
            notes = compiled.notes,
            totalLengthFrames = compiled.totalFrames,
            loopEnabled = mel.loopPlayback
        )
    }

    private fun captureMelodySnapshot(mel: MelodyStudioUiState): MelodySnapshot = MelodySnapshot(
        notes = mel.notes,
        cursorTick = mel.cursorTick,
        totalBars = mel.totalBars,
        scoreTitle = mel.scoreTitle,
        grandStaffEnabled = mel.grandStaffEnabled,
        activeStaff = mel.activeStaff,
        dualKeyboardStaffRouting = mel.dualKeyboardStaffRouting,
        timeSignature = mel.timeSignature,
        initialKeySignature = mel.initialKeySignature,
        keySignatureEvents = mel.keySignatureEvents,
        bpm = mel.bpm,
        swingPreset = mel.swingPreset,
        instrument = mel.instrument,
        secondaryInstrument = mel.secondaryInstrument,
        dualKeyboardEnabled = mel.dualKeyboardEnabled,
        reverbPreset = mel.reverbPreset,
        sustainPedal = mel.sustainPedal,
        secondarySustainPedal = mel.secondarySustainPedal,
        sustainDurationSec = mel.sustainDurationSec,
        secondarySustainDurationSec = mel.secondarySustainDurationSec,
        chordSymbols = mel.chordSymbols,
        showChordSymbols = mel.showChordSymbols,
        chordPlaybackEnabled = mel.chordPlaybackEnabled,
        dynamicMarks = mel.dynamicMarks,
        hairpins = mel.hairpins,
        tempoEvents = mel.tempoEvents
    )

    private fun sanitizeOrphanSlurGroups(
        notes: List<MelodyScoreNote>,
        activeSlurGroupId: String?,
        grandStaffEnabled: Boolean = _uiState.value.melody.grandStaffEnabled
    ): List<MelodyScoreNote> {
        val onsetCounts = HashMap<Pair<String, StaffPart>, MutableSet<Int>>()
        for (n in notes) {
            val gid = n.slurGroupId ?: continue
            if (n.isRest) continue
            val key = gid to n.resolvedStaffPart(grandStaffEnabled)
            onsetCounts.getOrPut(key) { HashSet() }.add(n.resolvedStartTick)
        }
        val orphanPairs = onsetCounts.entries
            .filter { (key, onsets) -> key.first != activeSlurGroupId && onsets.size < 2 }
            .map { it.key }
            .toSet()
        if (orphanPairs.isEmpty()) return notes
        return notes.map { n ->
            val gid = n.slurGroupId
            if (gid != null && (gid to n.resolvedStaffPart(grandStaffEnabled)) in orphanPairs) {
                n.copy(slurGroupId = null)
            } else {
                n
            }
        }
    }

    private fun pushMelodyUndoSnapshot() {
        val mel = _uiState.value.melody
        melodyUndoStack.addLast(captureMelodySnapshot(mel))
        while (melodyUndoStack.size > 40) {
            melodyUndoStack.removeFirst()
        }
        melodyRedoStack.clear()
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    canUndo = melodyUndoStack.isNotEmpty(),
                    canRedo = false
                )
            )
        }
    }

    fun undoMelody() {
        val snap = melodyUndoStack.removeLastOrNull() ?: return
        val cur = _uiState.value.melody
        melodyRedoStack.addLast(captureMelodySnapshot(cur))
        val keptActiveSlur = cur.activeSlurGroupId?.takeIf { gid ->
            snap.notes.any { it.slurGroupId == gid }
        }
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    notes = snap.notes,
                    cursorTick = snap.cursorTick,
                    totalBars = snap.totalBars,
                    scoreTitle = snap.scoreTitle,
                    grandStaffEnabled = snap.grandStaffEnabled,
                    activeStaff = snap.activeStaff,
                    dualKeyboardStaffRouting = snap.dualKeyboardStaffRouting,
                    timeSignature = snap.timeSignature,
                    initialKeySignature = snap.initialKeySignature,
                    keySignatureEvents = snap.keySignatureEvents,
                    bpm = snap.bpm,
                    swingPreset = snap.swingPreset,
                    instrument = snap.instrument,
                    secondaryInstrument = snap.secondaryInstrument,
                    dualKeyboardEnabled = snap.dualKeyboardEnabled,
                    reverbPreset = snap.reverbPreset,
                    sustainPedal = snap.sustainPedal,
                    secondarySustainPedal = snap.secondarySustainPedal,
                    sustainDurationSec = snap.sustainDurationSec,
                    secondarySustainDurationSec = snap.secondarySustainDurationSec,
                    chordSymbols = snap.chordSymbols,
                    showChordSymbols = snap.showChordSymbols,
                    chordPlaybackEnabled = snap.chordPlaybackEnabled,
                    dynamicMarks = snap.dynamicMarks,
                    hairpins = snap.hairpins,
                    tempoEvents = snap.tempoEvents,
                    activeSlurGroupId = keptActiveSlur,
                    selectedNoteId = null,
                    selectedAnnotation = null,
                    canUndo = melodyUndoStack.isNotEmpty(),
                    canRedo = melodyRedoStack.isNotEmpty()
                )
            )
        }
        syncMelodyInstrumentToEngine()
        syncMelodySequenceToEngine()
    }

    fun redoMelody() {
        val snap = melodyRedoStack.removeLastOrNull() ?: return
        val cur = _uiState.value.melody
        melodyUndoStack.addLast(captureMelodySnapshot(cur))
        val keptActiveSlur = cur.activeSlurGroupId?.takeIf { gid ->
            snap.notes.any { it.slurGroupId == gid }
        }
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    notes = snap.notes,
                    cursorTick = snap.cursorTick,
                    totalBars = snap.totalBars,
                    scoreTitle = snap.scoreTitle,
                    grandStaffEnabled = snap.grandStaffEnabled,
                    activeStaff = snap.activeStaff,
                    dualKeyboardStaffRouting = snap.dualKeyboardStaffRouting,
                    timeSignature = snap.timeSignature,
                    initialKeySignature = snap.initialKeySignature,
                    keySignatureEvents = snap.keySignatureEvents,
                    bpm = snap.bpm,
                    swingPreset = snap.swingPreset,
                    instrument = snap.instrument,
                    secondaryInstrument = snap.secondaryInstrument,
                    dualKeyboardEnabled = snap.dualKeyboardEnabled,
                    reverbPreset = snap.reverbPreset,
                    sustainPedal = snap.sustainPedal,
                    secondarySustainPedal = snap.secondarySustainPedal,
                    sustainDurationSec = snap.sustainDurationSec,
                    secondarySustainDurationSec = snap.secondarySustainDurationSec,
                    chordSymbols = snap.chordSymbols,
                    showChordSymbols = snap.showChordSymbols,
                    chordPlaybackEnabled = snap.chordPlaybackEnabled,
                    dynamicMarks = snap.dynamicMarks,
                    hairpins = snap.hairpins,
                    tempoEvents = snap.tempoEvents,
                    activeSlurGroupId = keptActiveSlur,
                    selectedNoteId = null,
                    selectedAnnotation = null,
                    canUndo = melodyUndoStack.isNotEmpty(),
                    canRedo = melodyRedoStack.isNotEmpty()
                )
            )
        }
        syncMelodyInstrumentToEngine()
        syncMelodySequenceToEngine()
    }

    fun setMelodyScoreTitle(title: String) {
        val trimmed = title.replace('\n', ' ').take(60)
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(scoreTitle = trimmed))
        }
    }

    fun clearMelodyScore() {
        val cur = _uiState.value.melody
        if (cur.notes.isEmpty() &&
            cur.chordSymbols.isEmpty() &&
            cur.dynamicMarks.isEmpty() &&
            cur.hairpins.isEmpty() &&
            cur.tempoEvents.isEmpty() &&
            cur.keySignatureEvents.isEmpty() &&
            cur.cursorTick == 0
        ) return
        if (cur.isPlaying || cur.isRecording) {
            finalizeAllActiveRecordedNotes()
            melodyPlayer.setTransportPlaying(false, 0L)
            melodyPlayer.allNotesOff()
        }
        pushMelodyUndoSnapshot()
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    notes = emptyList(),
                    chordSymbols = emptyList(),
                    dynamicMarks = emptyList(),
                    hairpins = emptyList(),
                    tempoEvents = emptyList(),
                    keySignatureEvents = emptyList(),
                    activeSlurGroupId = null,
                    pendingArticulation = null,
                    pendingAccent = null,
                    pendingFermata = false,
                    selectedNoteId = null,
                    selectedAnnotation = null,
                    cursorTick = 0,
                    tupletAnchorTick = 0,
                    totalBars = 4,
                    playheadBeat = 0f,
                    isPlaying = false,
                    isRecording = false
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun loadMelodyDemoShowcase() {
        val cur = _uiState.value.melody
        if (cur.isRecording) {
            finalizeAllActiveRecordedNotes()
        }
        // Safely auto-save any existing user score into local projects BEFORE loading the demo song
        if (cur.currentProjectId != null || MelodyProjectRepository.hasMeaningfulContent(cur)) {
            flushMelodyAutoSaveImmediate()
        }
        pushMelodyUndoSnapshot()
        val demo = MusicalNotationHelper.buildDemoShowcaseBundle()
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    scoreTitle = "晨光序曲",
                    currentProjectId = UUID.randomUUID().toString(),
                    grandStaffEnabled = true,
                    activeStaff = StaffPart.TREBLE,
                    timeSignature = TimeSignature.FOUR_FOUR,
                    initialKeySignature = KeySignature.C_MAJOR,
                    keySignatureEvents = emptyList(),
                    bpm = 112,
                    totalBars = 4,
                    cursorTick = 0,
                    tupletAnchorTick = 0,
                    notes = demo.notes,
                    chordSymbols = demo.chordSymbols,
                    showChordSymbols = true,
                    chordPlaybackEnabled = true,
                    dynamicMarks = demo.dynamicMarks,
                    hairpins = demo.hairpins,
                    tempoEvents = demo.tempoEvents,
                    activeSlurGroupId = null,
                    pendingArticulation = null,
                    pendingAccent = null,
                    pendingFermata = false,
                    selectedNoteId = null,
                    selectedAnnotation = null,
                    isRecording = false,
                    isPlaying = true,
                    playheadBeat = 0f
                )
            )
        }
        val updated = _uiState.value.melody
        if (updated.soundFontLoaded) {
            melodyPlayer.startAudioEngine()
        }
        syncMelodyInstrumentToEngine(updated)
        syncMelodySequenceToEngine(updated)
        melodyPlayer.setTransportPlaying(true, 0L)
    }

    fun toggleMelodyGrandStaff() {
        pushMelodyUndoSnapshot()
        var notesChanged = false
        _uiState.update { st ->
            val cur = st.melody
            val nextGrand = !cur.grandStaffEnabled
            val cleanedNotes = if (cur.activeSlurGroupId != null) {
                sanitizeOrphanSlurGroups(cur.notes, activeSlurGroupId = null, grandStaffEnabled = nextGrand)
            } else {
                cur.notes
            }
            notesChanged = (cleanedNotes != cur.notes)
            st.copy(
                melody = cur.copy(
                    grandStaffEnabled = nextGrand,
                    activeStaff = if (nextGrand) cur.activeStaff else StaffPart.TREBLE,
                    activeSlurGroupId = if (!nextGrand) null else cur.activeSlurGroupId,
                    notes = cleanedNotes
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun setMelodyActiveStaff(staff: StaffPart) {
        var notesChanged = false
        _uiState.update { st ->
            val cur = st.melody
            if (!cur.grandStaffEnabled || cur.activeStaff == staff) return@update st
            val cleanedNotes = if (cur.activeSlurGroupId != null) {
                sanitizeOrphanSlurGroups(cur.notes, activeSlurGroupId = null, grandStaffEnabled = true)
            } else {
                cur.notes
            }
            notesChanged = (cleanedNotes != cur.notes)
            st.copy(
                melody = cur.copy(
                    activeStaff = staff,
                    activeSlurGroupId = null,
                    selectedNoteId = null,
                    notes = cleanedNotes
                )
            )
        }
        if (notesChanged) {
            syncMelodySequenceToEngine()
        }
    }

    fun toggleDualKeyboardStaffRouting() {
        _uiState.update { st ->
            val nextRouting = when (st.melody.dualKeyboardStaffRouting) {
                DualKeyboardStaffRouting.SPLIT_BY_DECK -> DualKeyboardStaffRouting.FOLLOW_ACTIVE_STAFF
                DualKeyboardStaffRouting.FOLLOW_ACTIVE_STAFF -> DualKeyboardStaffRouting.SPLIT_BY_DECK
            }
            st.copy(melody = st.melody.copy(dualKeyboardStaffRouting = nextRouting))
        }
        val mode = _uiState.value.melody.dualKeyboardStaffRouting
        sendMessage(
            if (mode == DualKeyboardStaffRouting.SPLIT_BY_DECK) {
                "双排键盘联动：上排默认写上行高音谱 · 下排默认写下行低音谱"
            } else {
                "双排键盘联动：上下两排键盘均写入当前选中的谱表行"
            }
        )
    }

    private fun resolveInputStaffForSlot(cur: MelodyStudioUiState, slot: Int): StaffPart {
        if (!cur.grandStaffEnabled) return StaffPart.TREBLE
        return if (cur.dualKeyboardEnabled && cur.dualKeyboardStaffRouting == DualKeyboardStaffRouting.SPLIT_BY_DECK) {
            if (slot == 1) StaffPart.BASS else StaffPart.TREBLE
        } else {
            cur.activeStaff
        }
    }

    fun setMelodyScoreViewMode(mode: ScoreViewMode) {
        _uiState.update { st ->
            if (st.melody.scoreViewMode == mode) st
            else st.copy(melody = st.melody.copy(scoreViewMode = mode))
        }
    }

    fun toggleMinimizeKeyboardForScore() {
        _uiState.update { st ->
            val next = !st.melody.keyboardMinimizedForScore
            st.copy(
                melody = st.melody.copy(
                    keyboardMinimizedForScore = next,
                    collapseScoreInDualMode = if (next) false else st.melody.collapseScoreInDualMode
                )
            )
        }
    }

    fun setMinimizeKeyboardForScore(minimized: Boolean) {
        _uiState.update { st ->
            if (st.melody.keyboardMinimizedForScore == minimized) st
            else st.copy(
                melody = st.melody.copy(
                    keyboardMinimizedForScore = minimized,
                    collapseScoreInDualMode = if (minimized) false else st.melody.collapseScoreInDualMode
                )
            )
        }
    }

    /**
     * Switches primary keyboard instrument WITHOUT altering user's sustain ON/OFF state,
     * sustain duration, or manually customized keyboard layout, while auto-focusing Bass octave (`C2`).
     */
    fun selectPrimaryMelodyInstrument(instrument: StudioInstrument) {
        val cur = _uiState.value.melody
        if (cur.instrument == instrument) return
        pushMelodyUndoSnapshot()
        val nextScroll = when {
            instrument == StudioInstrument.FINGERED_BASS && cur.primaryScrollWhiteKey > 11f ->
                MusicalNotationHelper.octaveToWhiteKeyIndex(instrument.defaultOctave)
            cur.instrument == StudioInstrument.FINGERED_BASS &&
                instrument != StudioInstrument.FINGERED_BASS &&
                kotlin.math.abs(cur.primaryScrollWhiteKey - 7f) < 0.6f -> 21f
            else -> cur.primaryScrollWhiteKey
        }
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    instrument = instrument,
                    primaryScrollWhiteKey = nextScroll
                )
            )
        }
        val updated = _uiState.value.melody
        if (updated.soundFontLoaded) {
            melodyPlayer.startAudioEngine()
        }
        syncMelodyInstrumentToEngine(updated)
        if (updated.isPlaying) {
            syncMelodySequenceToEngine(updated)
        }

        if (!updated.isPlaying && !updated.isRecording && updated.soundFontLoaded) {
            previewNoteJob?.cancel()
            val previewMidi = ((instrument.defaultOctave + 1) * 12).coerceIn(PIANO_MIN_MIDI, 96)
            val previewHoldMs = when (instrument) {
                StudioInstrument.STRINGS_ENSEMBLE -> 560L
                StudioInstrument.FINGERED_BASS -> 380L
                else -> 320L
            }
            previewNoteJob = viewModelScope.launch {
                melodyPlayer.noteOn(0, previewMidi, 0.78f)
                delay(previewHoldMs)
                melodyPlayer.noteOff(0, previewMidi)
            }
        }
    }

    /**
     * Switches secondary keyboard instrument WITHOUT altering user's sustain ON/OFF state,
     * sustain duration, or manually customized keyboard layout, while auto-focusing Bass octave (`C2`).
     */
    fun selectSecondaryMelodyInstrument(instrument: StudioInstrument) {
        val cur = _uiState.value.melody
        if (cur.secondaryInstrument == instrument) return
        pushMelodyUndoSnapshot()
        val nextScroll = when {
            instrument == StudioInstrument.FINGERED_BASS && cur.secondaryScrollWhiteKey > 11f ->
                MusicalNotationHelper.octaveToWhiteKeyIndex(instrument.defaultOctave)
            cur.secondaryInstrument == StudioInstrument.FINGERED_BASS &&
                instrument != StudioInstrument.FINGERED_BASS &&
                kotlin.math.abs(cur.secondaryScrollWhiteKey - 7f) < 0.6f -> 14f
            else -> cur.secondaryScrollWhiteKey
        }
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    secondaryInstrument = instrument,
                    secondaryScrollWhiteKey = nextScroll
                )
            )
        }
        val updated = _uiState.value.melody
        if (updated.soundFontLoaded) {
            melodyPlayer.startAudioEngine()
        }
        syncMelodyInstrumentToEngine(updated)
        if (updated.isPlaying) {
            syncMelodySequenceToEngine(updated)
        }

        if (!updated.isPlaying && !updated.isRecording && updated.soundFontLoaded) {
            previewNoteJob?.cancel()
            val previewMidi = if (instrument == StudioInstrument.FINGERED_BASS) {
                ((instrument.defaultOctave + 1) * 12).coerceIn(PIANO_MIN_MIDI, 96)
            } else {
                MusicalNotationHelper
                    .whiteKeyIndexToMidi(updated.secondaryScrollWhiteKey.roundToInt())
                    .coerceIn(PIANO_MIN_MIDI, 96)
            }
            val previewHoldMs = when (instrument) {
                StudioInstrument.STRINGS_ENSEMBLE -> 560L
                StudioInstrument.FINGERED_BASS -> 380L
                else -> 320L
            }
            previewNoteJob = viewModelScope.launch {
                melodyPlayer.noteOn(1, previewMidi, 0.78f)
                delay(previewHoldMs)
                melodyPlayer.noteOff(1, previewMidi)
            }
        }
    }

    fun toggleDualKeyboard() {
        val next = !_uiState.value.melody.dualKeyboardEnabled
        if (!next) {
            val slot1Active = activeRecordedNotes.keys.filter { it.first == 1 }
            for (key in slot1Active) {
                onMelodyPianoKeyUp(key.first, key.second)
            }
            for (midi in PIANO_MIN_MIDI..PIANO_MAX_MIDI) {
                melodyPlayer.noteOff(1, midi)
            }
        }
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    dualKeyboardEnabled = next,
                    collapseScoreInDualMode = if (!next) false else st.melody.collapseScoreInDualMode
                )
            )
        }
        syncMelodyInstrumentToEngine()
    }

    fun toggleCollapseScoreInDual() {
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    collapseScoreInDualMode = !st.melody.collapseScoreInDualMode
                )
            )
        }
    }

    fun setCollapseScoreInDual(collapsed: Boolean) {
        _uiState.update { st ->
            if (st.melody.collapseScoreInDualMode == collapsed) {
                st
            } else {
                st.copy(melody = st.melody.copy(collapseScoreInDualMode = collapsed))
            }
        }
    }

    fun setPrimaryKeyboardScroll(whiteKey: Float, snapFinished: Boolean) {
        val clamped = whiteKey.coerceIn(0f, (TOTAL_VIRTUAL_WHITE_KEYS - 1).toFloat())
        val target = if (snapFinished) clamped.roundToInt().toFloat() else clamped
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(primaryScrollWhiteKey = target))
        }
    }

    fun setSecondaryKeyboardScroll(whiteKey: Float, snapFinished: Boolean) {
        val clamped = whiteKey.coerceIn(0f, (TOTAL_VIRTUAL_WHITE_KEYS - 1).toFloat())
        val target = if (snapFinished) clamped.roundToInt().toFloat() else clamped
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(secondaryScrollWhiteKey = target))
        }
    }

    fun setMelodyBpm(bpm: Int) {
        val clamped = bpm.coerceIn(40, 220)
        _uiState.update { st ->
            val updatedTempoEvents = st.melody.tempoEvents.map { ev ->
                if (ev.kind == TempoEventKind.STEADY_BPM && ev.safeStartTick == 0) {
                    val keptTerm = ev.term?.takeIf { abs(it.defaultBpm - clamped) <= 16 }
                    ev.copy(bpm = clamped, term = keptTerm)
                } else {
                    ev
                }
            }
            st.copy(
                melody = st.melody.copy(
                    bpm = clamped,
                    tempoEvents = updatedTempoEvents
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    /**
     * Updates the score's musical Time Signature (`拍号`, e.g. `4/4`, `3/4`, `2/4`, `6/8`, `12/8`, `5/4`, `7/8`, or custom `N/D`),
     * automatically adjusting measure count & metronome accents while preserving all recorded/written notes.
     */
    fun setMelodyTimeSignature(rawTimeSignature: TimeSignature) {
        val nextSig = rawTimeSignature.sanitized()
        val cur = _uiState.value.melody
        if (cur.timeSignature == nextSig) return

        pushMelodyUndoSnapshot()
        val newTicksPerBar = nextSig.ticksPerBar
        val maxEndTick = cur.notes.maxOfOrNull { it.resolvedEndTick } ?: 0
        val neededBars = if (maxEndTick > 0) {
            ((maxEndTick + newTicksPerBar - 1) / newTicksPerBar).coerceAtLeast(1)
        } else {
            cur.totalBars
        }
        val nextBars = maxOf(cur.totalBars, neededBars).coerceIn(1, MAX_MELODY_BARS)

        val keptTuplet = cur.activeTuplet?.takeIf { it.spanTicks <= newTicksPerBar }
        val rawCursor = if (cur.activeTuplet != null && keptTuplet == null) {
            snapCursorOutOfActiveTuplet(cur).first
        } else {
            cur.cursorTick
        }
        val minUnit = keptTuplet?.noteTicks ?: TICKS_PER_STEP
        val maxTick = (nextBars * newTicksPerBar - minUnit).coerceAtLeast(0)
        val clampedCursor = rawCursor.coerceIn(0, maxTick)

        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    timeSignature = nextSig,
                    totalBars = nextBars,
                    activeTuplet = keptTuplet,
                    cursorTick = clampedCursor,
                    tupletAnchorTick = if (keptTuplet == null) clampedCursor else st.melody.tupletAnchorTick.coerceIn(0, maxTick)
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    /**
     * Updates global Swing (`摇摆律动`) preset and immediately syncs the real-time synthesis sequence
     * so live audition (`试听`) and offline `.wav` export (`生成音轨`) reflect the Swing feel immediately.
     */
    fun setMelodySwingPreset(preset: SwingPreset) {
        if (_uiState.value.melody.swingPreset == preset) return
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(swingPreset = preset))
        }
        syncMelodySequenceToEngine()
    }

    fun setMelodyReverbPreset(preset: ReverbPreset) {
        if (_uiState.value.melody.reverbPreset == preset) return
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(reverbPreset = preset))
        }
        melodyPlayer.setReverbWet(_uiState.value.melody.effectiveReverbSend)
    }

    fun cycleMelodyReverb() {
        val presets = ReverbPreset.entries
        val cur = _uiState.value.melody.reverbPreset
        val next = presets[(presets.indexOf(cur) + 1) % presets.size]
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(reverbPreset = next))
        }
        melodyPlayer.setReverbWet(_uiState.value.melody.effectiveReverbSend)
    }

    fun togglePrimaryMelodySustain() {
        val next = !_uiState.value.melody.sustainPedal
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(sustainPedal = next))
        }
        melodyPlayer.setSustainPedal(0, next, _uiState.value.melody.sustainDurationSec)
    }

    fun toggleSecondaryMelodySustain() {
        val next = !_uiState.value.melody.secondarySustainPedal
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(secondarySustainPedal = next))
        }
        melodyPlayer.setSustainPedal(1, next, _uiState.value.melody.secondarySustainDurationSec)
    }

    fun setPrimaryMelodySustainDuration(seconds: Float) {
        val clamped = ((seconds.coerceIn(0.030f, 10.000f) * 1000f).roundToInt()) / 1000f
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(sustainDurationSec = clamped))
        }
        val mel = _uiState.value.melody
        melodyPlayer.setSustainPedal(0, mel.sustainPedal, mel.sustainDurationSec)
    }

    fun setSecondaryMelodySustainDuration(seconds: Float) {
        val clamped = ((seconds.coerceIn(0.030f, 10.000f) * 1000f).roundToInt()) / 1000f
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(secondarySustainDurationSec = clamped))
        }
        val mel = _uiState.value.melody
        melodyPlayer.setSustainPedal(1, mel.secondarySustainPedal, mel.secondarySustainDurationSec)
    }

    fun setMelodyInputMode(mode: MelodyInputMode) {
        val cur = _uiState.value.melody
        if (cur.inputMode == mode) return
        if (cur.isRecording && mode != MelodyInputMode.LIVE_RECORD) {
            finalizeAllActiveRecordedNotes()
            melodyPlayer.setTransportPlaying(false, 0L)
            _uiState.update { st ->
                st.copy(
                    melody = st.melody.copy(
                        inputMode = mode,
                        isRecording = false,
                        isPlaying = false
                    )
                )
            }
            syncMelodySequenceToEngine()
        } else {
            _uiState.update { st ->
                st.copy(melody = st.melody.copy(inputMode = mode))
            }
        }
    }

    /**
     * If the step cursor is currently partway inside an incomplete tuplet group,
     * snaps it cleanly to the end of that tuplet span (a regular 1/16th or beat boundary)
     * so subsequent regular notes or new tuplets never drift off-grid.
     */
    private fun snapCursorOutOfActiveTuplet(mel: MelodyStudioUiState): Pair<Int, Int> {
        val prevTup = mel.activeTuplet
        val ticksPerBar = mel.ticksPerBar
        var snappedTick = mel.cursorTick.coerceAtLeast(0)
        if (prevTup != null) {
            val rel = (snappedTick - mel.tupletAnchorTick).mod(prevTup.spanTicks)
            if (rel > 0) {
                snappedTick += (prevTup.spanTicks - rel)
            }
        }
        if (snappedTick % TICKS_PER_STEP != 0) {
            snappedTick = ((snappedTick + TICKS_PER_STEP - 1) / TICKS_PER_STEP) * TICKS_PER_STEP
        }
        var nextBars = mel.totalBars
        if (snappedTick >= mel.totalTicks && mel.totalBars < MAX_MELODY_BARS) {
            nextBars = (mel.totalBars + 2).coerceAtMost(MAX_MELODY_BARS)
        }
        val maxTick = (nextBars * ticksPerBar - TICKS_PER_STEP).coerceAtLeast(0)
        return snappedTick.coerceIn(0, maxTick) to nextBars
    }

    fun setMelodyNoteDuration(preset: NoteDurationPreset) {
        _uiState.update { st ->
            val (nextTick, nextBars) = if (st.melody.activeTuplet != null) {
                snapCursorOutOfActiveTuplet(st.melody)
            } else {
                st.melody.cursorTick to st.melody.totalBars
            }
            st.copy(
                melody = st.melody.copy(
                    noteDuration = preset,
                    activeTuplet = null,
                    cursorTick = nextTick,
                    tupletAnchorTick = nextTick,
                    totalBars = nextBars
                )
            )
        }
    }

    fun setMelodyActiveTuplet(tuplet: TupletPreset?) {
        val curMel = _uiState.value.melody
        if (tuplet != null && tuplet.spanTicks > curMel.ticksPerBar) {
            sendMessage("当前 ${curMel.timeSignature.label} 拍单小节时值不足以容纳「${tuplet.label}」，请选更短连音或切换拍号")
            return
        }
        _uiState.update { st ->
            val cur = st.melody
            if (cur.activeTuplet == tuplet) return@update st
            val ticksPerBar = cur.ticksPerBar
            val (snappedTick, grownBars) = snapCursorOutOfActiveTuplet(cur)
            var anchorTick = snappedTick
            var finalBars = grownBars
            if (tuplet != null && tuplet.spanTicks <= ticksPerBar) {
                val remInBar = ticksPerBar - (anchorTick % ticksPerBar)
                if (remInBar < tuplet.spanTicks) {
                    anchorTick = ((anchorTick / ticksPerBar) + 1) * ticksPerBar
                    if (anchorTick >= finalBars * ticksPerBar && finalBars < MAX_MELODY_BARS) {
                        finalBars = (finalBars + 2).coerceAtMost(MAX_MELODY_BARS)
                    }
                }
            }
            val maxTick = (finalBars * ticksPerBar - (tuplet?.noteTicks ?: TICKS_PER_STEP)).coerceAtLeast(0)
            val clampedTick = anchorTick.coerceIn(0, maxTick)
            st.copy(
                melody = cur.copy(
                    activeTuplet = tuplet,
                    cursorTick = clampedTick,
                    tupletAnchorTick = clampedTick,
                    totalBars = finalBars
                )
            )
        }
    }

    fun toggleMelodyChordMode() {
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(chordStepMode = !st.melody.chordStepMode))
        }
    }

    private fun resolveStepPlacement(
        cur: MelodyStudioUiState,
        rawTargetTick: Int,
        activeTuplet: TupletPreset?,
        advanceCursor: Boolean
    ): Triple<Int, Int, NoteTupletTag?> {
        if (activeTuplet == null) {
            val snapped = (rawTargetTick / TICKS_PER_STEP) * TICKS_PER_STEP
            val durTicks = cur.noteDuration.ticks
            return Triple(snapped, durTicks, null)
        }

        val ticksPerBar = cur.ticksPerBar
        var targetTick = rawTargetTick.coerceAtLeast(0)
        var anchor = cur.tupletAnchorTick
        if (targetTick < anchor || (targetTick - anchor) % activeTuplet.noteTicks != 0) {
            anchor = targetTick
        }
        val groupIdx = (targetTick - anchor) / activeTuplet.spanTicks
        var groupStartTick = anchor + groupIdx * activeTuplet.spanTicks

        // Ensure a newly started tuplet group never straddles a measure barline
        if (activeTuplet.spanTicks <= ticksPerBar) {
            if (targetTick == groupStartTick) {
                val remInBar = ticksPerBar - (groupStartTick % ticksPerBar)
                if (remInBar < activeTuplet.spanTicks) {
                    groupStartTick = ((groupStartTick / ticksPerBar) + 1) * ticksPerBar
                    targetTick = groupStartTick
                }
            } else if ((targetTick / ticksPerBar) != (groupStartTick / ticksPerBar)) {
                groupStartTick = (targetTick / ticksPerBar) * ticksPerBar
                targetTick = groupStartTick
            }
        }

        val tag = NoteTupletTag(
            count = activeTuplet.count,
            normalCount = activeTuplet.normalCount,
            writtenBaseDenom = activeTuplet.writtenBaseDenom,
            groupStartTick = groupStartTick,
            spanTicks = activeTuplet.spanTicks
        )
        return Triple(targetTick, activeTuplet.noteTicks, tag)
    }

    fun setMelodyTouchGuardConfig(config: PianoTouchGuardConfig) {
        KeyboardLayoutPreferences.saveTouchGuardConfig(appContext, config)
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(touchGuardConfig = config))
        }
    }

    fun onMelodyPianoKeyDown(
        slot: Int,
        midiNote: Int,
        velocity: Float,
        isGlissando: Boolean = false
    ) {
        val safeSlot = slot.coerceIn(0, 1)
        val safeMidi = midiNote.coerceIn(12, 108)
        val safeVel = velocity.coerceIn(0.15f, 1.0f)
        val cur = _uiState.value.melody
        val guard = cur.touchGuardConfig
        val nowMs = SystemClock.uptimeMillis()

        // Suppress simultaneous fat-finger 1-semitone seam split when chord mode is off
        if (guard.isEnabled && !isGlissando && !cur.chordStepMode) {
            val prevStrike = recentSlotStrikes[safeSlot]
            val clashWindowMs = if (guard.mode == TouchGuardMode.STRICT) 46L else 28L
            if (prevStrike != null &&
                !prevStrike.isGlissando &&
                (nowMs - prevStrike.downWallMs) in 0L..clashWindowMs &&
                abs(safeMidi - prevStrike.midi) == 1
            ) {
                return
            }
        }
        recentSlotStrikes[safeSlot] = RecentLiveStrike(
            midi = safeMidi,
            downWallMs = nowMs,
            isGlissando = isGlissando
        )

        if (cur.soundFontLoaded) {
            melodyPlayer.startAudioEngine()
        }
        melodyPlayer.noteOn(safeSlot, safeMidi, safeVel)

        val inputStaff = resolveInputStaffForSlot(cur, safeSlot)
        val noteTargetStaff = if (cur.grandStaffEnabled) inputStaff else null

        if (cur.isRecording) {
            val curTick = currentMelodyTempoMap(cur).frameToTick(melodyPlayer.currentTransportFrame())
            val curBeat = (curTick / TICKS_PER_BEAT.toDouble()).toFloat().coerceAtLeast(0f)
            activeRecordedNotes[safeSlot to safeMidi] = ActiveRecordedNote(
                startBeat = curBeat,
                velocity = safeVel,
                downWallMs = nowMs,
                isGlissando = isGlissando,
                targetStaff = noteTargetStaff
            )
            return
        }

        if (cur.inputMode == MelodyInputMode.STEP_INPUT && !cur.isPlaying) {
            val intervalOkForAutoChord = !guard.isEnabled ||
                safeSlot != lastStepSlot ||
                abs(safeMidi - lastStepMidi) >= 3
            val autoChord = !cur.chordStepMode &&
                !isGlissando &&
                intervalOkForAutoChord &&
                (nowMs - lastStepKeyDownWallMs) in 0L..65L &&
                cur.notes.any { !it.isRest }

            if (!autoChord) {
                pushMelodyUndoSnapshot()
            }

            val baseTargetTick = when {
                cur.chordStepMode -> cur.cursorTick
                autoChord -> lastStepPlacedTick
                else -> cur.cursorTick
            }
            val (targetTick, durTicks, tupletTag) = resolveStepPlacement(
                cur = cur,
                rawTargetTick = baseTargetTick,
                activeTuplet = cur.activeTuplet,
                advanceCursor = !cur.chordStepMode && !autoChord
            )

            val startBeat = targetTick.toFloat() / TICKS_PER_BEAT.toFloat()
            val durBeats = durTicks.toFloat() / TICKS_PER_BEAT.toFloat()
            val chordMate = cur.notes.firstOrNull {
                !it.isRest &&
                    abs(it.resolvedStartTick - targetTick) <= 6 &&
                    (!cur.grandStaffEnabled || it.resolvedStaffPart(true) == inputStaff)
            }
            val switchedStaffOnDeck = cur.grandStaffEnabled && (cur.activeStaff != inputStaff)
            val effectiveActiveSlurId = if (switchedStaffOnDeck) null else cur.activeSlurGroupId

            val newNote = MelodyScoreNote(
                midiNote = safeMidi,
                startBeat = startBeat,
                durationBeats = durBeats,
                velocity = safeVel,
                keyboardSlot = safeSlot,
                isRest = false,
                targetStaff = noteTargetStaff,
                startTickExact = targetTick,
                durationTicksExact = durTicks,
                tuplet = tupletTag,
                slurGroupId = effectiveActiveSlurId ?: chordMate?.slurGroupId,
                articulation = cur.pendingArticulation ?: chordMate?.articulation,
                accent = cur.pendingAccent ?: chordMate?.accent,
                hasFermata = cur.pendingFermata || (chordMate?.hasFermata == true)
            )

            val rawNextTick = when {
                cur.chordStepMode -> targetTick
                autoChord -> cur.cursorTick
                else -> targetTick + durTicks
            }

            val ticksPerBar = cur.ticksPerBar
            var nextBars = cur.totalBars
            if (rawNextTick >= nextBars * ticksPerBar && nextBars < MAX_MELODY_BARS) {
                nextBars = (nextBars + 2).coerceAtMost(MAX_MELODY_BARS)
            }
            val minStepUnit = cur.activeTuplet?.noteTicks ?: TICKS_PER_STEP
            val maxTickAllowed = (nextBars * ticksPerBar - minStepUnit).coerceAtLeast(0)
            val clampedNextTick = rawNextTick.coerceIn(0, maxTickAllowed)
            val nextAnchor = tupletTag?.groupStartTick ?: clampedNextTick
            val cursorAdvances = !cur.chordStepMode && !autoChord

            if (cursorAdvances) {
                lastStepKeyDownWallMs = nowMs
                lastStepPlacedTick = targetTick
                lastStepSlot = safeSlot
                lastStepMidi = safeMidi
            }

            _uiState.update { st ->
                val filtered = st.melody.notes.filterNot { existing ->
                    val sameStaff = !st.melody.grandStaffEnabled ||
                        existing.resolvedStaffPart(true) == inputStaff
                    if (!sameStaff) {
                        false
                    } else {
                        val exStart = existing.resolvedStartTick
                        val exEnd = existing.resolvedEndTick
                        if (existing.isRest) {
                            exStart < (targetTick + durTicks) && exEnd > targetTick
                        } else {
                            abs(exStart - targetTick) <= 6 &&
                                existing.midiNote == safeMidi &&
                                existing.keyboardSlot == safeSlot
                        }
                    }
                }
                val rawNotes = (filtered + newNote).sortedWith(
                    compareBy<MelodyScoreNote> { it.resolvedStartTick }
                        .thenBy { it.resolvedStaffPart(st.melody.grandStaffEnabled).ordinal }
                        .thenBy { it.isRest }
                        .thenBy { it.keyboardSlot }
                        .thenBy { it.midiNote }
                )
                val updatedNotes = if (switchedStaffOnDeck && st.melody.activeSlurGroupId != null) {
                    sanitizeOrphanSlurGroups(rawNotes, activeSlurGroupId = null, grandStaffEnabled = st.melody.grandStaffEnabled)
                } else {
                    rawNotes
                }
                st.copy(
                    melody = st.melody.copy(
                        notes = updatedNotes,
                        activeStaff = if (st.melody.grandStaffEnabled) inputStaff else st.melody.activeStaff,
                        activeSlurGroupId = effectiveActiveSlurId,
                        selectedNoteId = newNote.id,
                        selectedAnnotation = null,
                        pendingArticulation = if (cursorAdvances) null else st.melody.pendingArticulation,
                        pendingAccent = if (cursorAdvances) null else st.melody.pendingAccent,
                        pendingFermata = if (cursorAdvances) false else st.melody.pendingFermata,
                        cursorTick = clampedNextTick,
                        tupletAnchorTick = nextAnchor,
                        totalBars = nextBars
                    )
                )
            }
            syncMelodySequenceToEngine()
        }
    }

    fun onMelodyPianoKeyUp(slot: Int, midiNote: Int) {
        val safeSlot = slot.coerceIn(0, 1)
        val safeMidi = midiNote.coerceIn(12, 108)
        melodyPlayer.noteOff(safeSlot, safeMidi)

        val recordedStart = activeRecordedNotes.remove(safeSlot to safeMidi) ?: return
        val cur = _uiState.value.melody
        val guard = cur.touchGuardConfig
        val nowMs = SystemClock.uptimeMillis()
        val heldWallMs = (nowMs - recordedStart.downWallMs).coerceAtLeast(0L)

        // Live-Record ultra-short neighbor-key graze filter (preserves 100% of glissandos & fast scales!)
        if (guard.isEnabled && !recordedStart.isGlissando) {
            val ghostMaxMs = if (guard.mode == TouchGuardMode.STRICT) 46L else 30L
            if (heldWallMs <= ghostMaxMs) {
                val hasConcurrentNeighbor = activeRecordedNotes.entries.any { (key, other) ->
                    key.first == safeSlot &&
                        abs(key.second - safeMidi) in 1..2 &&
                        !other.isGlissando
                } || recentSlotStrikes[safeSlot]?.let { recent ->
                    recent.midi != safeMidi &&
                        abs(recent.midi - safeMidi) in 1..2 &&
                        !recent.isGlissando &&
                        (nowMs - recent.downWallMs) <= 50L
                } == true
                if (hasConcurrentNeighbor) {
                    return
                }
            }
        }

        val rawEndTick = currentMelodyTempoMap(cur).frameToTick(melodyPlayer.currentTransportFrame())
        val rawEndBeat = (rawEndTick / TICKS_PER_BEAT.toDouble()).toFloat()
        val startBeat = recordedStart.startBeat
        val vel = recordedStart.velocity
        val endBeat = if (rawEndBeat < startBeat) cur.totalQuarterBeats else rawEndBeat
        val minDurBeats = when {
            recordedStart.isGlissando -> 0.08f
            guard.isEnabled -> 0.16f
            else -> 0.20f
        }
        val durBeats = (endBeat - startBeat).coerceAtLeast(minDurBeats)

        val recordedNote = MelodyScoreNote(
            midiNote = safeMidi,
            startBeat = startBeat,
            durationBeats = durBeats,
            velocity = vel,
            keyboardSlot = safeSlot,
            isRest = false,
            targetStaff = recordedStart.targetStaff,
            slurGroupId = cur.activeSlurGroupId
        )

        _uiState.update { st ->
            val updated = (st.melody.notes + recordedNote).sortedWith(
                compareBy<MelodyScoreNote> { it.resolvedStartTick }
                    .thenBy { it.resolvedStaffPart(st.melody.grandStaffEnabled).ordinal }
                    .thenBy { it.isRest }
                    .thenBy { it.keyboardSlot }
                    .thenBy { it.midiNote }
            )
            st.copy(
                melody = st.melody.copy(
                    notes = updated,
                    selectedNoteId = recordedNote.id,
                    selectedAnnotation = null
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    private fun finalizeAllActiveRecordedNotes(endBeatOverride: Float? = null) {
        if (activeRecordedNotes.isEmpty()) return
        val cur = _uiState.value.melody
        val nowBeat = endBeatOverride
            ?: (currentMelodyTempoMap(cur).frameToTick(melodyPlayer.currentTransportFrame()) / TICKS_PER_BEAT.toDouble()).toFloat()

        val finalized = mutableListOf<MelodyScoreNote>()
        val entries = activeRecordedNotes.entries.toList()
        activeRecordedNotes.clear()
        for ((key, rec) in entries) {
            val (slot, midi) = key
            melodyPlayer.noteOff(slot, midi)
            val startBeat = rec.startBeat
            val vel = rec.velocity
            val endB = if (nowBeat <= startBeat) cur.totalQuarterBeats else nowBeat
            val minDur = if (rec.isGlissando) 0.08f else 0.16f
            val dur = (endB - startBeat).coerceAtLeast(minDur)
            finalized.add(
                MelodyScoreNote(
                    midiNote = midi,
                    startBeat = startBeat,
                    durationBeats = dur,
                    velocity = vel,
                    keyboardSlot = slot,
                    isRest = false,
                    targetStaff = rec.targetStaff,
                    slurGroupId = cur.activeSlurGroupId
                )
            )
        }
        if (finalized.isNotEmpty()) {
            _uiState.update { st ->
                val updated = (st.melody.notes + finalized).sortedWith(
                    compareBy<MelodyScoreNote> { it.resolvedStartTick }
                        .thenBy { it.resolvedStaffPart(st.melody.grandStaffEnabled).ordinal }
                        .thenBy { it.isRest }
                        .thenBy { it.keyboardSlot }
                        .thenBy { it.midiNote }
                )
                st.copy(melody = st.melody.copy(notes = updated))
            }
        }
    }

    /**
     * Inserts an explicit silent musical Rest (`休止符`) directly onto the 5-line staff:
     * - When [presetOverride] is null and [MelodyStudioUiState.activeTuplet] is active, inserts a Tuplet Rest
     *   inside the current tuplet group (e.g. Triplet Rest) and advances by one tuplet slot!
     * - Otherwise inserts a standard/dotted rest of [presetOverride] or `noteDuration`.
     */
    fun insertMelodyRest(presetOverride: NoteDurationPreset? = null) {
        val cur = _uiState.value.melody
        pushMelodyUndoSnapshot()

        val useTuplet = if (presetOverride != null) null else cur.activeTuplet
        val baseTick = if (presetOverride != null && cur.activeTuplet != null) {
            snapCursorOutOfActiveTuplet(cur).first
        } else {
            cur.cursorTick
        }

        val targetTick: Int
        val durTicks: Int
        val tupletTag: NoteTupletTag?
        if (useTuplet != null) {
            val resolved = resolveStepPlacement(
                cur = cur,
                rawTargetTick = baseTick,
                activeTuplet = useTuplet,
                advanceCursor = true
            )
            targetTick = resolved.first
            durTicks = resolved.second
            tupletTag = resolved.third
        } else {
            val preset = presetOverride ?: cur.noteDuration
            targetTick = (baseTick / TICKS_PER_STEP) * TICKS_PER_STEP
            durTicks = preset.ticks
            tupletTag = null
        }

        val startBeat = targetTick.toFloat() / TICKS_PER_BEAT.toFloat()
        val durBeats = durTicks.toFloat() / TICKS_PER_BEAT.toFloat()

        val restStaff = if (cur.grandStaffEnabled) cur.activeStaff else null
        val restNote = MelodyScoreNote(
            midiNote = DEFAULT_REST_MIDI,
            startBeat = startBeat,
            durationBeats = durBeats,
            velocity = 0f,
            keyboardSlot = 0,
            isRest = true,
            targetStaff = restStaff,
            startTickExact = targetTick,
            durationTicksExact = durTicks,
            tuplet = tupletTag,
            hasFermata = cur.pendingFermata
        )

        val ticksPerBar = cur.ticksPerBar
        val rawNextTick = targetTick + durTicks
        var nextBars = cur.totalBars
        if (rawNextTick >= nextBars * ticksPerBar && nextBars < MAX_MELODY_BARS) {
            nextBars = (nextBars + 2).coerceAtMost(MAX_MELODY_BARS)
        }
        val minStepUnit = useTuplet?.noteTicks ?: TICKS_PER_STEP
        val maxTick = (nextBars * ticksPerBar - minStepUnit).coerceAtLeast(0)
        val clampedNext = rawNextTick.coerceIn(0, maxTick)

        _uiState.update { st ->
            val filtered = st.melody.notes.filterNot { existing ->
                val sameStaff = !st.melody.grandStaffEnabled ||
                    existing.resolvedStaffPart(true) == st.melody.activeStaff
                existing.isRest && sameStaff && abs(existing.resolvedStartTick - targetTick) <= 6
            }
            val updated = (filtered + restNote).sortedWith(
                compareBy<MelodyScoreNote> { it.resolvedStartTick }
                    .thenBy { it.resolvedStaffPart(st.melody.grandStaffEnabled).ordinal }
                    .thenBy { it.isRest }
                    .thenBy { it.keyboardSlot }
                    .thenBy { it.midiNote }
            )
            st.copy(
                melody = st.melody.copy(
                    noteDuration = presetOverride ?: st.melody.noteDuration,
                    activeTuplet = useTuplet,
                    notes = updated,
                    selectedNoteId = restNote.id,
                    selectedAnnotation = null,
                    pendingFermata = false,
                    cursorTick = clampedNext,
                    tupletAnchorTick = tupletTag?.groupStartTick ?: clampedNext,
                    totalBars = nextBars
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun extendPreviousMelodyNote() {
        val cur = _uiState.value.melody
        val staffNotes = cur.notes.filter { !cur.grandStaffEnabled || it.resolvedStaffPart(true) == cur.activeStaff }
        if (staffNotes.isEmpty()) {
            val staffLabel = if (cur.grandStaffEnabled) {
                if (cur.activeStaff == StaffPart.BASS) "下行低音谱" else "上行高音谱"
            } else {
                "谱面"
            }
            sendMessage("${staffLabel}还没有音符或休止符，请先弹入后再点「延音连线」拉长时值")
            return
        }
        val endingAtCursor = staffNotes.filter { abs(it.resolvedEndTick - cur.cursorTick) <= 12 }
        val targetIds: Set<String> = when {
            endingAtCursor.isNotEmpty() -> endingAtCursor.map { it.id }.toSet()
            cur.selectedNoteId != null && staffNotes.any { it.id == cur.selectedNoteId } -> setOf(cur.selectedNoteId)
            else -> setOf(staffNotes.last().id)
        }

        pushMelodyUndoSnapshot()
        val addTicks = cur.activeTuplet?.noteTicks ?: cur.noteDuration.ticks
        val addBeats = addTicks.toFloat() / TICKS_PER_BEAT.toFloat()

        val rawNextTick = if (endingAtCursor.isNotEmpty()) {
            cur.cursorTick + addTicks
        } else {
            staffNotes.filter { it.id in targetIds }.maxOf { it.resolvedEndTick + addTicks }
        }

        val ticksPerBar = cur.ticksPerBar
        var nextBars = cur.totalBars
        if (rawNextTick >= nextBars * ticksPerBar && nextBars < MAX_MELODY_BARS) {
            nextBars = (nextBars + 2).coerceAtMost(MAX_MELODY_BARS)
        }
        val minStepUnit = cur.activeTuplet?.noteTicks ?: TICKS_PER_STEP
        val maxTick = (nextBars * ticksPerBar - minStepUnit).coerceAtLeast(0)

        _uiState.update { st ->
            val updatedNotes = st.melody.notes.map { n ->
                if (n.id in targetIds) {
                    val newDurTicks = n.resolvedDurationTicks + addTicks
                    n.copy(
                        durationBeats = n.durationBeats + addBeats,
                        durationTicksExact = newDurTicks,
                        // Extending across standard beats turns a tuplet note into a sustained note
                        tuplet = if (n.tuplet != null && newDurTicks > n.tuplet.noteTicks) null else n.tuplet
                    )
                } else {
                    n
                }
            }
            st.copy(
                melody = st.melody.copy(
                    notes = updatedNotes,
                    cursorTick = rawNextTick.coerceIn(0, maxTick),
                    totalBars = nextBars
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun backspaceMelodyNote() {
        val cur = _uiState.value.melody
        val staffNotes = cur.notes.filter { !cur.grandStaffEnabled || it.resolvedStaffPart(true) == cur.activeStaff }
        if (staffNotes.isEmpty()) {
            if (cur.cursorTick > 0) {
                val stepBack = cur.activeTuplet?.noteTicks ?: cur.noteDuration.ticks
                val prev = (cur.cursorTick - stepBack).coerceAtLeast(0)
                _uiState.update { st -> st.copy(melody = st.melody.copy(cursorTick = prev)) }
            }
            return
        }

        pushMelodyUndoSnapshot()
        val candidates = staffNotes.filter { it.resolvedStartTick <= cur.cursorTick + 6 }
        val targetStartTick = (if (candidates.isNotEmpty()) candidates else staffNotes)
            .maxOf { it.resolvedStartTick }

        val deletedNotes = staffNotes.filter { abs(it.resolvedStartTick - targetStartTick) <= 6 }
        val deletedIds = deletedNotes.map { it.id }.toSet()
        val rawRemaining = cur.notes.filterNot { it.id in deletedIds }
        val remaining = sanitizeOrphanSlurGroups(rawRemaining, cur.activeSlurGroupId, cur.grandStaffEnabled)
        val deletedTupletGroupAnchor = deletedNotes.firstNotNullOfOrNull { it.tuplet?.groupStartTick }

        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    notes = remaining,
                    selectedNoteId = null,
                    selectedAnnotation = null,
                    cursorTick = targetStartTick.coerceAtLeast(0),
                    tupletAnchorTick = deletedTupletGroupAnchor ?: st.melody.tupletAnchorTick
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun deleteSelectedMelodyNote() {
        val cur = _uiState.value.melody
        val targetId = cur.selectedNoteId ?: return
        val target = cur.notes.firstOrNull { it.id == targetId } ?: return
        pushMelodyUndoSnapshot()
        val backTick = target.resolvedStartTick.coerceAtLeast(0)
        _uiState.update { st ->
            val rawRemaining = st.melody.notes.filterNot { it.id == targetId }
            val remaining = sanitizeOrphanSlurGroups(rawRemaining, st.melody.activeSlurGroupId, st.melody.grandStaffEnabled)
            st.copy(
                melody = st.melody.copy(
                    notes = remaining,
                    selectedNoteId = null,
                    cursorTick = backTick,
                    tupletAnchorTick = target.tuplet?.groupStartTick ?: st.melody.tupletAnchorTick
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun deleteSelectedMelodyItem() {
        val cur = _uiState.value.melody
        if (cur.selectedNoteId != null) {
            deleteSelectedMelodyNote()
            return
        }
        when (val ann = cur.selectedAnnotation) {
            is SelectedScoreAnnotation.Dynamic -> deleteMelodyDynamicMark(ann.id)
            is SelectedScoreAnnotation.Hairpin -> deleteMelodyHairpin(ann.id)
            is SelectedScoreAnnotation.Tempo -> deleteMelodyTempoEvent(ann.id)
            is SelectedScoreAnnotation.KeyChange -> deleteMelodyKeySignatureEvent(ann.id)
            is SelectedScoreAnnotation.ChordSymbol -> deleteMelodyChordSymbol(ann.id)
            null -> Unit
        }
    }

    fun quantizeMelodyNotes() {
        val cur = _uiState.value.melody
        if (cur.notes.isEmpty()) {
            sendMessage("当前还没有音符可对齐")
            return
        }
        pushMelodyUndoSnapshot()
        val quantized = cur.notes.map { n ->
            if (n.tuplet != null) {
                n
            } else {
                val qStepStart = ((n.startBeat * STEPS_PER_BEAT).roundToInt().coerceAtLeast(0))
                val qStepDur = ((n.durationBeats * STEPS_PER_BEAT).roundToInt().coerceAtLeast(1))
                n.copy(
                    startBeat = qStepStart / STEPS_PER_BEAT.toFloat(),
                    durationBeats = qStepDur / STEPS_PER_BEAT.toFloat(),
                    startTickExact = qStepStart * TICKS_PER_STEP,
                    durationTicksExact = qStepDur * TICKS_PER_STEP
                )
            }
        }
        val deduped = quantized
            .groupBy {
                listOf(
                    it.resolvedStaffPart(cur.grandStaffEnabled),
                    it.isRest,
                    it.keyboardSlot,
                    it.midiNote,
                    it.resolvedStartTick
                )
            }
            .values
            .map { group -> group.maxBy { it.resolvedDurationTicks } }
            .sortedWith(
                compareBy<MelodyScoreNote> { it.resolvedStartTick }
                    .thenBy { it.resolvedStaffPart(cur.grandStaffEnabled).ordinal }
                    .thenBy { it.isRest }
                    .thenBy { it.keyboardSlot }
                    .thenBy { it.midiNote }
            )

        _uiState.update { st ->
            st.copy(melody = st.melody.copy(notes = deduped))
        }
        syncMelodySequenceToEngine()
        sendMessage("已自动对齐 ${deduped.size} 个符号（保留连音时值）")
    }

    fun addMelodyBars() {
        val cur = _uiState.value.melody
        if (cur.totalBars >= MAX_MELODY_BARS) {
            sendMessage("单段打谱最多支持 $MAX_MELODY_BARS 小节（导出后可在多轨剪辑中无限拼接）")
            return
        }
        pushMelodyUndoSnapshot()
        val nextBars = (cur.totalBars + 4).coerceAtMost(MAX_MELODY_BARS)
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(totalBars = nextBars))
        }
        syncMelodySequenceToEngine()
    }

    fun toggleMelodyMetronome() {
        val next = !_uiState.value.melody.metronomeEnabled
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(metronomeEnabled = next))
        }
        syncMelodySequenceToEngine()
    }

    fun toggleMelodyLoop() {
        val next = !_uiState.value.melody.loopPlayback
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(loopPlayback = next))
        }
        syncMelodySequenceToEngine()
    }

    private fun resolveAuditionStartTick(cur: MelodyStudioUiState): Int {
        val effectiveEndTick = if (cur.hasPlayableNotes) cur.maxPlayableEndTick else cur.totalTicks
        return if (cur.cursorTick in 1 until effectiveEndTick) cur.cursorTick else 0
    }

    fun toggleMelodyPlayStop() {
        val cur = _uiState.value.melody
        if (cur.isPlaying || cur.isRecording) {
            if (cur.isRecording) {
                finalizeAllActiveRecordedNotes()
            }
            melodyPlayer.setTransportPlaying(false, 0L)
            melodyPlayer.allNotesOff()
            _uiState.update { st ->
                st.copy(
                    melody = st.melody.copy(
                        isPlaying = false,
                        isRecording = false,
                        playheadBeat = st.melody.cursorTick.toFloat() / TICKS_PER_BEAT.toFloat()
                    )
                )
            }
            syncMelodySequenceToEngine()
        } else {
            if (!cur.hasPlayableNotes && !cur.metronomeEnabled) {
                sendMessage("请先在下方键盘弹入音符，或点右上角「示例曲」试听")
                return
            }
            if (cur.soundFontLoaded) {
                melodyPlayer.startAudioEngine()
            }
            val startTick = resolveAuditionStartTick(cur)
            val startBeat = startTick.toFloat() / TICKS_PER_BEAT.toFloat()
            _uiState.update { st ->
                st.copy(
                    melody = st.melody.copy(
                        isPlaying = true,
                        isRecording = false,
                        playheadBeat = startBeat
                    )
                )
            }
            val updated = _uiState.value.melody
            syncMelodyInstrumentToEngine(updated)
            syncMelodySequenceToEngine(updated)
            val startFrame = tickToSeekFrame(currentMelodyTempoMap(updated), startTick)
            melodyPlayer.setTransportPlaying(true, startFrame)
        }
    }

    fun playMelodyFromStart() {
        val cur = _uiState.value.melody
        if (cur.isRecording) {
            finalizeAllActiveRecordedNotes()
        }
        if (!cur.hasPlayableNotes && !cur.metronomeEnabled) {
            setMelodyCursorTick(0)
            sendMessage("已回到第 1 小节开头")
            return
        }
        if (cur.soundFontLoaded) {
            melodyPlayer.startAudioEngine()
        }
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    cursorTick = 0,
                    tupletAnchorTick = 0,
                    selectedNoteId = null,
                    selectedAnnotation = null,
                    isPlaying = true,
                    isRecording = false,
                    playheadBeat = 0f
                )
            )
        }
        val updated = _uiState.value.melody
        syncMelodyInstrumentToEngine(updated)
        syncMelodySequenceToEngine(updated)
        melodyPlayer.setTransportPlaying(true, 0L)
    }

    fun toggleMelodyRecordStop() {
        val cur = _uiState.value.melody
        if (cur.isRecording) {
            finalizeAllActiveRecordedNotes()
            melodyPlayer.setTransportPlaying(false, 0L)
            melodyPlayer.allNotesOff()
            _uiState.update { st ->
                st.copy(
                    melody = st.melody.copy(
                        isRecording = false,
                        isPlaying = false,
                        playheadBeat = st.melody.cursorTick.toFloat() / TICKS_PER_BEAT.toFloat()
                    )
                )
            }
            syncMelodySequenceToEngine()
        } else {
            pushMelodyUndoSnapshot()
            activeRecordedNotes.clear()
            if (cur.soundFontLoaded) {
                melodyPlayer.startAudioEngine()
            }
            val startTick = cur.cursorTick.coerceAtLeast(0)
            val startBeat = startTick.toFloat() / TICKS_PER_BEAT.toFloat()
            _uiState.update { st ->
                st.copy(
                    melody = st.melody.copy(
                        inputMode = MelodyInputMode.LIVE_RECORD,
                        keyboardMinimizedForScore = false,
                        isRecording = true,
                        isPlaying = true,
                        playheadBeat = startBeat,
                        selectedNoteId = null,
                        selectedAnnotation = null
                    )
                )
            }
            val updated = _uiState.value.melody
            syncMelodyInstrumentToEngine(updated)
            syncMelodySequenceToEngine(updated)
            val startFrame = tickToSeekFrame(currentMelodyTempoMap(updated), startTick)
            melodyPlayer.setTransportPlaying(true, startFrame)
        }
    }

    fun setMelodyCursorTick(tick: Int) {
        setMelodyCursorTickAndStaff(tick = tick, staff = null)
    }

    fun nudgeMelodyCursor(direction: Int, fineSixteenth: Boolean = false) {
        val cur = _uiState.value.melody
        if (cur.isRecording) return
        val nextTick = cur.computeNudgedCursorTick(direction = direction, fineSixteenth = fineSixteenth)
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    selectedNoteId = null,
                    selectedAnnotation = null
                )
            )
        }
        setMelodyCursorTickAndStaff(tick = nextTick, staff = null)
    }

    fun jumpMelodyCursorToBarBeat(barNumber1Based: Int, beatNumber1Based: Int = 1) {
        val cur = _uiState.value.melody
        if (cur.isRecording) return
        val safeBar = barNumber1Based.coerceIn(1, MAX_MELODY_BARS)
        val safeBeat = beatNumber1Based.coerceIn(1, cur.timeSignature.safeNumerator)
        val expandedBars = safeBar > cur.totalBars
        if (expandedBars) {
            pushMelodyUndoSnapshot()
            _uiState.update { st ->
                st.copy(
                    melody = st.melody.copy(
                        totalBars = safeBar,
                        selectedNoteId = null,
                        selectedAnnotation = null
                    )
                )
            }
            syncMelodySequenceToEngine()
        } else {
            _uiState.update { st ->
                st.copy(
                    melody = st.melody.copy(
                        selectedNoteId = null,
                        selectedAnnotation = null
                    )
                )
            }
        }
        val tpb = _uiState.value.melody.ticksPerBar.coerceAtLeast(1)
        val unit = _uiState.value.melody.timeSignature.unitTicks.coerceAtLeast(1)
        val targetTick = (safeBar - 1) * tpb + (safeBeat - 1) * unit
        setMelodyCursorTickAndStaff(tick = targetTick, staff = null)
    }

    fun setMelodyCursorTickAndStaff(tick: Int, staff: StaffPart?) {
        val cur = _uiState.value.melody
        val minStepUnit = cur.activeTuplet?.noteTicks ?: TICKS_PER_STEP
        val tpb = cur.ticksPerBar.coerceAtLeast(1)
        val maxContentTick = maxOf(
            cur.totalTicks,
            cur.notes.maxOfOrNull { it.resolvedEndTick } ?: 0,
            cur.chordSymbols.maxOfOrNull { ((it.safeTick / tpb) + 1) * tpb } ?: 0,
            cur.keySignatureEvents.maxOfOrNull { (it.safeBarIndex + 1) * tpb } ?: 0
        )
        val clamped = tick.coerceIn(0, (maxContentTick - minStepUnit).coerceAtLeast(0))
        val nextBeat = clamped.toFloat() / TICKS_PER_BEAT.toFloat()
        var notesChanged = false
        _uiState.update { st ->
            val m = st.melody
            val nextStaff = if (m.grandStaffEnabled && staff != null) staff else m.activeStaff
            val switchedStaff = m.grandStaffEnabled && (nextStaff != m.activeStaff)
            val cleanedNotes = if (switchedStaff && m.activeSlurGroupId != null) {
                sanitizeOrphanSlurGroups(m.notes, activeSlurGroupId = null, grandStaffEnabled = true)
            } else {
                m.notes
            }
            notesChanged = (cleanedNotes != m.notes)
            st.copy(
                melody = m.copy(
                    cursorTick = clamped,
                    tupletAnchorTick = clamped,
                    activeStaff = nextStaff,
                    activeSlurGroupId = if (switchedStaff) null else m.activeSlurGroupId,
                    selectedNoteId = if (switchedStaff) null else m.selectedNoteId,
                    notes = cleanedNotes,
                    playheadBeat = if (m.isPlaying && !m.isRecording) nextBeat else m.playheadBeat
                )
            )
        }
        if (notesChanged) {
            syncMelodySequenceToEngine()
        }
        if (cur.isPlaying && !cur.isRecording) {
            val targetFrame = tickToSeekFrame(currentMelodyTempoMap(cur), clamped)
            melodyPlayer.seekTransport(targetFrame)
        }
    }

    fun selectMelodyNote(noteId: String?) {
        var seekToTick: Int? = null
        var notesChanged = false
        _uiState.update { st ->
            val m = st.melody
            val note = noteId?.let { id -> m.notes.firstOrNull { it.id == id } }
            val nextTick = note?.resolvedStartTick ?: m.cursorTick
            val minStepUnit = m.activeTuplet?.noteTicks ?: TICKS_PER_STEP
            val clampedTick = nextTick.coerceIn(0, (m.totalTicks - minStepUnit).coerceAtLeast(0))
            val nextAnchor = note?.tuplet?.groupStartTick ?: m.tupletAnchorTick
            val nextStaff = if (note != null && m.grandStaffEnabled) {
                note.resolvedStaffPart(true)
            } else {
                m.activeStaff
            }
            val switchedStaff = m.grandStaffEnabled && (nextStaff != m.activeStaff)
            val cleanedNotes = if (switchedStaff && m.activeSlurGroupId != null) {
                sanitizeOrphanSlurGroups(m.notes, activeSlurGroupId = null, grandStaffEnabled = true)
            } else {
                m.notes
            }
            notesChanged = (cleanedNotes != m.notes)
            if (note != null && m.isPlaying && !m.isRecording) {
                seekToTick = clampedTick
            }
            st.copy(
                melody = m.copy(
                    selectedNoteId = noteId,
                    selectedAnnotation = if (noteId != null) null else m.selectedAnnotation,
                    activeStaff = nextStaff,
                    activeSlurGroupId = if (switchedStaff) null else m.activeSlurGroupId,
                    notes = cleanedNotes,
                    cursorTick = clampedTick,
                    tupletAnchorTick = nextAnchor,
                    playheadBeat = if (seekToTick != null) {
                        clampedTick.toFloat() / TICKS_PER_BEAT.toFloat()
                    } else {
                        m.playheadBeat
                    }
                )
            )
        }
        if (notesChanged) {
            syncMelodySequenceToEngine()
        }
        seekToTick?.let { targetTick ->
            val cur = _uiState.value.melody
            melodyPlayer.seekTransport(tickToSeekFrame(currentMelodyTempoMap(cur), targetTick))
            return
        }
        val selected = noteId?.let { id -> _uiState.value.melody.notes.firstOrNull { it.id == id } }
        if (selected != null && !selected.isRest && !_uiState.value.melody.isPlaying && !_uiState.value.melody.isRecording) {
            previewNoteJob?.cancel()
            val slot = selected.keyboardSlot.coerceIn(0, 1)
            val activeInst = if (slot == 1) _uiState.value.melody.secondaryInstrument else _uiState.value.melody.instrument
            val previewHoldMs = if (activeInst == StudioInstrument.STRINGS_ENSEMBLE) 420L else 240L
            previewNoteJob = viewModelScope.launch {
                melodyPlayer.noteOn(slot, selected.midiNote, selected.velocity)
                delay(previewHoldMs)
                melodyPlayer.noteOff(slot, selected.midiNote)
            }
        }
    }

    fun selectMelodyAnnotation(annotation: SelectedScoreAnnotation?) {
        var seekToTick: Int? = null
        _uiState.update { st ->
            val cur = st.melody
            val jumpTick = when (annotation) {
                is SelectedScoreAnnotation.Dynamic ->
                    cur.dynamicMarks.firstOrNull { it.id == annotation.id }?.tick
                is SelectedScoreAnnotation.Hairpin ->
                    cur.hairpins.firstOrNull { it.id == annotation.id }?.safeStartTick
                is SelectedScoreAnnotation.Tempo ->
                    cur.tempoEvents.firstOrNull { it.id == annotation.id }?.safeStartTick
                is SelectedScoreAnnotation.KeyChange ->
                    cur.keySignatureEvents.firstOrNull { it.id == annotation.id }?.let {
                        it.safeBarIndex * cur.ticksPerBar
                    }
                is SelectedScoreAnnotation.ChordSymbol ->
                    cur.chordSymbols.firstOrNull { it.id == annotation.id }?.safeTick
                null -> null
            }
            val minStepUnit = cur.activeTuplet?.noteTicks ?: TICKS_PER_STEP
            val nextTick = (jumpTick ?: cur.cursorTick)
                .coerceIn(0, (cur.totalTicks - minStepUnit).coerceAtLeast(0))
            if (jumpTick != null && cur.isPlaying && !cur.isRecording) {
                seekToTick = nextTick
            }
            st.copy(
                melody = cur.copy(
                    selectedAnnotation = annotation,
                    selectedNoteId = if (annotation != null) null else cur.selectedNoteId,
                    cursorTick = nextTick,
                    tupletAnchorTick = if (jumpTick != null) nextTick else cur.tupletAnchorTick,
                    playheadBeat = if (seekToTick != null) {
                        nextTick.toFloat() / TICKS_PER_BEAT.toFloat()
                    } else {
                        cur.playheadBeat
                    }
                )
            )
        }
        seekToTick?.let { targetTick ->
            val cur = _uiState.value.melody
            melodyPlayer.seekTransport(tickToSeekFrame(currentMelodyTempoMap(cur), targetTick))
            return
        }
        if (annotation is SelectedScoreAnnotation.ChordSymbol) {
            val cur = _uiState.value.melody
            if (!cur.isPlaying && !cur.isRecording) {
                cur.chordSymbols.firstOrNull { it.id == annotation.id }?.let { cs ->
                    previewMelodyChordSymbol(cs.symbol)
                }
            }
        }
    }

    // ========================================================================
    // Phrase Slur (`乐句连线 / 圆滑线`), Articulations, Accents, Fermata,
    // Dynamics (`pp..ff`, `sfz`, `fp`), Hairpins (`<` / `>`), & Tempo Map
    // ========================================================================

    fun toggleMelodyPhraseSlurMode() {
        val cur = _uiState.value.melody
        if (cur.activeSlurGroupId != null) {
            val cleaned = sanitizeOrphanSlurGroups(cur.notes, activeSlurGroupId = null)
            val notesChanged = cleaned != cur.notes
            _uiState.update { st ->
                st.copy(
                    melody = st.melody.copy(
                        activeSlurGroupId = null,
                        notes = cleaned
                    )
                )
            }
            if (notesChanged) {
                syncMelodySequenceToEngine()
            }
        } else {
            val freshGroupId = UUID.randomUUID().toString()
            _uiState.update { st ->
                st.copy(melody = st.melody.copy(activeSlurGroupId = freshGroupId))
            }
        }
    }

    /**
     * Retroactively links the selected pitched note (or note at cursor) with the next pitched note
     * into a Phrase Slur (`乐句连线`), or extends an existing slur by one more subsequent note onset!
     * If the selected note is currently the very last note on the score, connects it into live slur mode
     * so the next piano note played automatically joins this slur!
     */
    fun slurSelectedOrExtendNext() {
        val cur = _uiState.value.melody
        val selPitched = cur.selectedNote?.takeIf { !it.isRest }
        val targetStaffPart = if (cur.grandStaffEnabled) {
            selPitched?.resolvedStaffPart(true) ?: cur.activeStaff
        } else {
            StaffPart.TREBLE
        }
        val pitchedNotes = cur.notes
            .filterNot { it.isRest }
            .filter { !cur.grandStaffEnabled || it.resolvedStaffPart(true) == targetStaffPart }
            .sortedBy { it.resolvedStartTick }
        if (pitchedNotes.isEmpty()) {
            toggleMelodyPhraseSlurMode()
            return
        }

        val anchorNote = selPitched
            ?: pitchedNotes.firstOrNull { abs(it.resolvedStartTick - cur.cursorTick) <= 6 }
            ?: pitchedNotes.lastOrNull { it.resolvedStartTick <= cur.cursorTick + 6 }
            ?: pitchedNotes.first()

        val existingGid = anchorNote.slurGroupId
        if (existingGid != null) {
            val currentMaxOnset = pitchedNotes
                .filter { it.slurGroupId == existingGid }
                .maxOfOrNull { it.resolvedStartTick } ?: anchorNote.resolvedStartTick
            val nextOnset = pitchedNotes
                .firstOrNull { it.resolvedStartTick > currentMaxOnset + 6 }
                ?.resolvedStartTick
            if (nextOnset == null) {
                _uiState.update { st ->
                    st.copy(
                        melody = st.melody.copy(
                            activeSlurGroupId = existingGid,
                            activeStaff = if (st.melody.grandStaffEnabled) targetStaffPart else st.melody.activeStaff
                        )
                    )
                }
                sendMessage("已激活当前乐句连线，继续弹奏下一个音符将自动连入本乐句")
                return
            }
            pushMelodyUndoSnapshot()
            val absorbIds = pitchedNotes
                .filter { abs(it.resolvedStartTick - nextOnset) <= 6 }
                .map { it.id }
                .toSet()
            _uiState.update { st ->
                val rawUpdated = st.melody.notes.map { n ->
                    if (n.id in absorbIds) n.copy(slurGroupId = existingGid) else n
                }
                val cleaned = sanitizeOrphanSlurGroups(rawUpdated, st.melody.activeSlurGroupId, st.melody.grandStaffEnabled)
                st.copy(melody = st.melody.copy(notes = cleaned))
            }
            syncMelodySequenceToEngine()
            return
        }

        val anchorOnset = anchorNote.resolvedStartTick
        val nextOnset = pitchedNotes
            .firstOrNull { it.resolvedStartTick > anchorOnset + 6 }
            ?.resolvedStartTick
        val newSlurId = UUID.randomUUID().toString()
        pushMelodyUndoSnapshot()

        if (nextOnset == null) {
            val anchorIds = pitchedNotes
                .filter { abs(it.resolvedStartTick - anchorOnset) <= 6 }
                .map { it.id }
                .toSet()
            _uiState.update { st ->
                val updated = st.melody.notes.map { n ->
                    if (n.id in anchorIds) n.copy(slurGroupId = newSlurId) else n
                }
                st.copy(
                    melody = st.melody.copy(
                        notes = updated,
                        activeSlurGroupId = newSlurId,
                        activeStaff = if (st.melody.grandStaffEnabled) targetStaffPart else st.melody.activeStaff
                    )
                )
            }
            syncMelodySequenceToEngine()
            sendMessage("已从当前音符开启乐句连线，请继续弹奏后续音符")
            return
        }

        val targetNextExistingGroup = pitchedNotes
            .firstOrNull { abs(it.resolvedStartTick - nextOnset) <= 6 }
            ?.slurGroupId
        val mergedGroupId = targetNextExistingGroup ?: newSlurId
        val joinIds = pitchedNotes
            .filter {
                abs(it.resolvedStartTick - anchorOnset) <= 6 ||
                    abs(it.resolvedStartTick - nextOnset) <= 6
            }
            .map { it.id }
            .toSet()

        _uiState.update { st ->
            val rawUpdated = st.melody.notes.map { n ->
                if (n.id in joinIds) n.copy(slurGroupId = mergedGroupId) else n
            }
            val cleaned = sanitizeOrphanSlurGroups(rawUpdated, st.melody.activeSlurGroupId, st.melody.grandStaffEnabled)
            st.copy(melody = st.melody.copy(notes = cleaned))
        }
        syncMelodySequenceToEngine()
    }

    /**
     * Wraps all pitched notes starting within `[startTick, endTick]` on the active staff into a single Phrase Slur (`乐句连线`).
     */
    fun applyMelodySlurSpan(startTick: Int, endTick: Int) {
        val cur = _uiState.value.melody
        val lo = minOf(startTick, endTick).coerceAtLeast(0)
        val hi = maxOf(startTick, endTick).coerceAtLeast(lo + TICKS_PER_STEP)
        val matching = cur.notes.filter {
            !it.isRest &&
                (!cur.grandStaffEnabled || it.resolvedStaffPart(true) == cur.activeStaff) &&
                it.resolvedStartTick >= lo - 6 &&
                it.resolvedStartTick < hi - 4
        }
        val distinctOnsets = matching.map { it.resolvedStartTick }.distinct()
        if (distinctOnsets.size < 2) {
            sendMessage("当前谱表行所选区间内至少需要 2 个发声音符才能添加乐句连线")
            return
        }
        pushMelodyUndoSnapshot()
        val newSlurId = UUID.randomUUID().toString()
        val targetIds = matching.map { it.id }.toSet()
        _uiState.update { st ->
            val rawUpdated = st.melody.notes.map { n ->
                if (n.id in targetIds) n.copy(slurGroupId = newSlurId) else n
            }
            val cleaned = sanitizeOrphanSlurGroups(rawUpdated, st.melody.activeSlurGroupId, st.melody.grandStaffEnabled)
            st.copy(melody = st.melody.copy(notes = cleaned))
        }
        syncMelodySequenceToEngine()
        sendMessage("已为 ${distinctOnsets.size} 个音符添加乐句连线 (Legato)")
    }

    fun clearSelectedMelodySlur(slurGroupIdOverride: String? = null) {
        val cur = _uiState.value.melody
        val targetGroupId = slurGroupIdOverride
            ?: cur.selectedNote?.slurGroupId
            ?: cur.notes.firstOrNull {
                !it.isRest &&
                    (!cur.grandStaffEnabled || it.resolvedStaffPart(true) == cur.activeStaff) &&
                    abs(it.resolvedStartTick - cur.cursorTick) <= 6
            }?.slurGroupId
        if (targetGroupId == null) {
            if (cur.activeSlurGroupId != null) {
                toggleMelodyPhraseSlurMode()
            } else {
                sendMessage("当前选中的音符没有乐句连线")
            }
            return
        }
        pushMelodyUndoSnapshot()
        _uiState.update { st ->
            val updated = st.melody.notes.map { n ->
                if (n.slurGroupId == targetGroupId) n.copy(slurGroupId = null) else n
            }
            st.copy(
                melody = st.melody.copy(
                    notes = updated,
                    activeSlurGroupId = if (st.melody.activeSlurGroupId == targetGroupId) null else st.melody.activeSlurGroupId
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun toggleMelodyArticulation(articulation: NoteArticulation?) {
        val cur = _uiState.value.melody
        val sel = cur.selectedNote?.takeIf { !it.isRest }
        if (sel != null) {
            pushMelodyUndoSnapshot()
            val nextArt = if (articulation == null || sel.articulation == articulation) null else articulation
            val selStaff = sel.resolvedStaffPart(cur.grandStaffEnabled)
            val chordIds = cur.notes
                .filter {
                    !it.isRest &&
                        abs(it.resolvedStartTick - sel.resolvedStartTick) <= 6 &&
                        it.resolvedStaffPart(cur.grandStaffEnabled) == selStaff &&
                        it.keyboardSlot == sel.keyboardSlot
                }
                .map { it.id }
                .toSet()
            _uiState.update { st ->
                val updated = st.melody.notes.map { n ->
                    if (n.id in chordIds) n.copy(articulation = nextArt) else n
                }
                st.copy(melody = st.melody.copy(notes = updated))
            }
            syncMelodySequenceToEngine()
        } else {
            _uiState.update { st ->
                val nextPending = if (articulation == null || st.melody.pendingArticulation == articulation) null else articulation
                st.copy(melody = st.melody.copy(pendingArticulation = nextPending))
            }
        }
    }

    fun toggleMelodyAccent(accent: NoteAccent?) {
        val cur = _uiState.value.melody
        val sel = cur.selectedNote?.takeIf { !it.isRest }
        if (sel != null) {
            pushMelodyUndoSnapshot()
            val nextAcc = if (accent == null || sel.accent == accent) null else accent
            val selStaff = sel.resolvedStaffPart(cur.grandStaffEnabled)
            val chordIds = cur.notes
                .filter {
                    !it.isRest &&
                        abs(it.resolvedStartTick - sel.resolvedStartTick) <= 6 &&
                        it.resolvedStaffPart(cur.grandStaffEnabled) == selStaff &&
                        it.keyboardSlot == sel.keyboardSlot
                }
                .map { it.id }
                .toSet()
            _uiState.update { st ->
                val updated = st.melody.notes.map { n ->
                    if (n.id in chordIds) n.copy(accent = nextAcc) else n
                }
                st.copy(melody = st.melody.copy(notes = updated))
            }
            syncMelodySequenceToEngine()
        } else {
            _uiState.update { st ->
                val nextPending = if (accent == null || st.melody.pendingAccent == accent) null else accent
                st.copy(melody = st.melody.copy(pendingAccent = nextPending))
            }
        }
    }

    fun toggleMelodyFermata() {
        val cur = _uiState.value.melody
        val sel = cur.selectedNote
        if (sel != null) {
            pushMelodyUndoSnapshot()
            val nextFermata = !sel.hasFermata
            val selStaff = sel.resolvedStaffPart(cur.grandStaffEnabled)
            val onsetIds = cur.notes
                .filter {
                    abs(it.resolvedStartTick - sel.resolvedStartTick) <= 6 &&
                        (!cur.grandStaffEnabled || it.resolvedStaffPart(true) == selStaff)
                }
                .map { it.id }
                .toSet()
            _uiState.update { st ->
                val updated = st.melody.notes.map { n ->
                    if (n.id in onsetIds) n.copy(hasFermata = nextFermata) else n
                }
                st.copy(melody = st.melody.copy(notes = updated))
            }
            syncMelodySequenceToEngine()
        } else {
            _uiState.update { st ->
                st.copy(melody = st.melody.copy(pendingFermata = !st.melody.pendingFermata))
            }
        }
    }

    fun upsertMelodyDynamicMark(tick: Int, level: DynamicLevel, editingId: String? = null) {
        val cur = _uiState.value.melody
        pushMelodyUndoSnapshot()
        val cleanTick = tick.coerceAtLeast(0)
        val ticksPerBar = cur.ticksPerBar
        var nextBars = cur.totalBars
        if (cleanTick >= nextBars * ticksPerBar && nextBars < MAX_MELODY_BARS) {
            nextBars = ((cleanTick / ticksPerBar) + 1).coerceIn(cur.totalBars, MAX_MELODY_BARS)
        }
        val clampedTick = cleanTick.coerceAtMost((nextBars * ticksPerBar - TICKS_PER_STEP).coerceAtLeast(0))
        val mark = ScoreDynamicMark(
            id = editingId ?: UUID.randomUUID().toString(),
            tick = clampedTick,
            level = level
        )
        _uiState.update { st ->
            val filtered = st.melody.dynamicMarks.filterNot {
                it.id == mark.id || abs(it.tick - clampedTick) <= 10
            }
            val updated = (filtered + mark).sortedBy { it.tick }
            st.copy(
                melody = st.melody.copy(
                    dynamicMarks = updated,
                    totalBars = nextBars,
                    selectedAnnotation = SelectedScoreAnnotation.Dynamic(mark.id),
                    selectedNoteId = null
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun deleteMelodyDynamicMark(id: String) {
        val cur = _uiState.value.melody
        if (cur.dynamicMarks.none { it.id == id }) return
        pushMelodyUndoSnapshot()
        _uiState.update { st ->
            val clearedSel = if ((st.melody.selectedAnnotation as? SelectedScoreAnnotation.Dynamic)?.id == id) {
                null
            } else {
                st.melody.selectedAnnotation
            }
            st.copy(
                melody = st.melody.copy(
                    dynamicMarks = st.melody.dynamicMarks.filterNot { it.id == id },
                    selectedAnnotation = clearedSel
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun upsertMelodyHairpin(
        startTick: Int,
        endTick: Int,
        type: HairpinType,
        editingId: String? = null
    ) {
        val cur = _uiState.value.melody
        pushMelodyUndoSnapshot()
        val safeStart = minOf(startTick, endTick).coerceAtLeast(0)
        val safeEnd = maxOf(endTick, safeStart + TICKS_PER_STEP * 2)
        val ticksPerBar = cur.ticksPerBar
        var nextBars = cur.totalBars
        if (safeEnd > nextBars * ticksPerBar && nextBars < MAX_MELODY_BARS) {
            nextBars = ((safeEnd + ticksPerBar - 1) / ticksPerBar).coerceIn(cur.totalBars, MAX_MELODY_BARS)
        }
        val maxScoreTick = nextBars * ticksPerBar
        val clampedStart = safeStart.coerceAtMost((maxScoreTick - TICKS_PER_STEP * 2).coerceAtLeast(0))
        val clampedEnd = safeEnd.coerceIn(clampedStart + TICKS_PER_STEP, maxScoreTick)
        val hairpin = ScoreHairpin(
            id = editingId ?: UUID.randomUUID().toString(),
            startTick = clampedStart,
            endTick = clampedEnd,
            type = type
        )
        _uiState.update { st ->
            val filtered = st.melody.hairpins.filterNot { existing ->
                existing.id == hairpin.id ||
                    (existing.safeStartTick < clampedEnd - 6 && existing.safeEndTick > clampedStart + 6)
            }
            val updated = (filtered + hairpin).sortedBy { it.safeStartTick }
            st.copy(
                melody = st.melody.copy(
                    hairpins = updated,
                    totalBars = nextBars,
                    selectedAnnotation = SelectedScoreAnnotation.Hairpin(hairpin.id),
                    selectedNoteId = null
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun deleteMelodyHairpin(id: String) {
        val cur = _uiState.value.melody
        if (cur.hairpins.none { it.id == id }) return
        pushMelodyUndoSnapshot()
        _uiState.update { st ->
            val clearedSel = if ((st.melody.selectedAnnotation as? SelectedScoreAnnotation.Hairpin)?.id == id) {
                null
            } else {
                st.melody.selectedAnnotation
            }
            st.copy(
                melody = st.melody.copy(
                    hairpins = st.melody.hairpins.filterNot { it.id == id },
                    selectedAnnotation = clearedSel
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun upsertMelodyTempoEvent(rawEvent: ScoreTempoEvent) {
        val cur = _uiState.value.melody
        pushMelodyUndoSnapshot()
        val ticksPerBar = cur.ticksPerBar
        val safeStart = rawEvent.safeStartTick
        val rawEnd = rawEvent.safeEndTick
        var nextBars = cur.totalBars
        if (maxOf(safeStart + TICKS_PER_STEP, rawEnd) > nextBars * ticksPerBar && nextBars < MAX_MELODY_BARS) {
            nextBars = ((maxOf(safeStart + TICKS_PER_STEP, rawEnd) + ticksPerBar - 1) / ticksPerBar)
                .coerceIn(cur.totalBars, MAX_MELODY_BARS)
        }
        val maxScoreTick = nextBars * ticksPerBar
        val clampedStart = safeStart.coerceAtMost((maxScoreTick - TICKS_PER_STEP).coerceAtLeast(0))
        val clampedEnd = when (rawEvent.kind) {
            TempoEventKind.RITARDANDO, TempoEventKind.ACCELERANDO ->
                rawEnd.coerceIn(clampedStart + TICKS_PER_BEAT, maxScoreTick)
            else -> clampedStart
        }
        val cleanBpm = rawEvent.bpm.coerceIn(36, 240)
        val cleanTarget = rawEvent.targetBpm?.coerceIn(32, 240)
        val event = rawEvent.copy(
            startTick = clampedStart,
            endTick = clampedEnd,
            bpm = cleanBpm,
            targetBpm = cleanTarget
        )
        val isRamp = event.kind == TempoEventKind.RITARDANDO || event.kind == TempoEventKind.ACCELERANDO

        _uiState.update { st ->
            val filtered = st.melody.tempoEvents.filterNot { existing ->
                if (existing.id == event.id) return@filterNot true
                val exRamp = existing.kind == TempoEventKind.RITARDANDO || existing.kind == TempoEventKind.ACCELERANDO
                if (isRamp && exRamp) {
                    existing.safeStartTick < clampedEnd - 6 && existing.safeEndTick > clampedStart + 6
                } else if (!isRamp && !exRamp) {
                    abs(existing.safeStartTick - clampedStart) <= 10
                } else {
                    false
                }
            }
            val updated = (filtered + event).sortedBy { it.safeStartTick }
            val nextTopBpm = if (event.kind == TempoEventKind.STEADY_BPM && clampedStart == 0) {
                cleanBpm.coerceIn(40, 220)
            } else {
                st.melody.bpm
            }
            st.copy(
                melody = st.melody.copy(
                    bpm = nextTopBpm,
                    tempoEvents = updated,
                    totalBars = nextBars,
                    selectedAnnotation = SelectedScoreAnnotation.Tempo(event.id),
                    selectedNoteId = null
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun deleteMelodyTempoEvent(id: String) {
        val cur = _uiState.value.melody
        if (cur.tempoEvents.none { it.id == id }) return
        pushMelodyUndoSnapshot()
        _uiState.update { st ->
            val clearedSel = if ((st.melody.selectedAnnotation as? SelectedScoreAnnotation.Tempo)?.id == id) {
                null
            } else {
                st.melody.selectedAnnotation
            }
            st.copy(
                melody = st.melody.copy(
                    tempoEvents = st.melody.tempoEvents.filterNot { it.id == id },
                    selectedAnnotation = clearedSel
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    // ========================================================================
    // Key Signature & Mid-Score Modulation (`调号 + 中途变调`, Lossless Pitch)
    // ========================================================================

    fun setMelodyInitialKeySignature(rawKeySignature: KeySignature) {
        val nextKey = rawKeySignature.sanitized()
        val cur = _uiState.value.melody
        if (cur.initialKeySignature == nextKey) return
        pushMelodyUndoSnapshot()
        _uiState.update { st ->
            st.copy(
                melody = st.melody.copy(
                    initialKeySignature = nextKey,
                    selectedAnnotation = null
                )
            )
        }
    }

    fun upsertMelodyKeySignatureEvent(
        barIndex: Int,
        rawKeySignature: KeySignature,
        editingId: String? = null
    ) {
        val nextKey = rawKeySignature.sanitized()
        if (barIndex <= 0) {
            setMelodyInitialKeySignature(nextKey)
            return
        }
        val cleanBar = barIndex.coerceIn(1, MAX_MELODY_BARS - 1)
        val cur = _uiState.value.melody
        pushMelodyUndoSnapshot()
        val nextBars = maxOf(cur.totalBars, cleanBar + 1).coerceIn(1, MAX_MELODY_BARS)
        val ev = ScoreKeySignatureEvent(
            id = editingId ?: UUID.randomUUID().toString(),
            barIndex = cleanBar,
            keySignature = nextKey
        )
        _uiState.update { st ->
            val filtered = st.melody.keySignatureEvents.filterNot {
                it.id == ev.id || it.safeBarIndex == cleanBar
            }
            val updated = (filtered + ev).sortedBy { it.safeBarIndex }
            st.copy(
                melody = st.melody.copy(
                    keySignatureEvents = updated,
                    totalBars = nextBars,
                    selectedAnnotation = SelectedScoreAnnotation.KeyChange(ev.id),
                    selectedNoteId = null
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun deleteMelodyKeySignatureEvent(id: String) {
        val cur = _uiState.value.melody
        if (cur.keySignatureEvents.none { it.id == id }) return
        pushMelodyUndoSnapshot()
        _uiState.update { st ->
            val clearedSel = if ((st.melody.selectedAnnotation as? SelectedScoreAnnotation.KeyChange)?.id == id) {
                null
            } else {
                st.melody.selectedAnnotation
            }
            st.copy(
                melody = st.melody.copy(
                    keySignatureEvents = st.melody.keySignatureEvents.filterNot { it.id == id },
                    selectedAnnotation = clearedSel
                )
            )
        }
    }

    // ========================================================================
    // Above-Staff Letter Chord Symbols (`谱表上方字母和弦标记` + Real Audio Voicing)
    // ========================================================================

    fun upsertMelodyChordSymbol(
        tick: Int,
        rawSymbol: String,
        editingId: String? = null
    ): Boolean {
        val parsed = MelodyChordHelper.parseChordSymbol(rawSymbol)
        if (parsed == null) {
            sendMessage("无法识别和弦「${rawSymbol.trim()}」，请输入如 C、Dm7、F#m7-5、Bbadd9、Dsus4、G/B")
            return false
        }
        val cur = _uiState.value.melody
        pushMelodyUndoSnapshot()

        val cleanTick = ((tick.coerceAtLeast(0) / TICKS_PER_STEP) * TICKS_PER_STEP)
        val ticksPerBar = cur.ticksPerBar
        var nextBars = cur.totalBars
        if (cleanTick >= nextBars * ticksPerBar && nextBars < MAX_MELODY_BARS) {
            nextBars = ((cleanTick / ticksPerBar) + 1).coerceIn(cur.totalBars, MAX_MELODY_BARS)
        }
        val clampedTick = cleanTick.coerceAtMost((nextBars * ticksPerBar - TICKS_PER_STEP).coerceAtLeast(0))

        val item = ScoreChordSymbol(
            id = editingId ?: UUID.randomUUID().toString(),
            tick = clampedTick,
            symbol = parsed.canonicalSymbol
        )
        _uiState.update { st ->
            val filtered = st.melody.chordSymbols.filterNot {
                it.id == item.id || abs(it.safeTick - clampedTick) <= 10
            }
            val updated = (filtered + item).sortedBy { it.safeTick }
            st.copy(
                melody = st.melody.copy(
                    chordSymbols = updated,
                    showChordSymbols = true,
                    totalBars = nextBars,
                    cursorTick = clampedTick,
                    tupletAnchorTick = clampedTick,
                    selectedAnnotation = SelectedScoreAnnotation.ChordSymbol(item.id),
                    selectedNoteId = null
                )
            )
        }
        syncMelodySequenceToEngine()
        return true
    }

    fun deleteMelodyChordSymbol(id: String) {
        val cur = _uiState.value.melody
        if (cur.chordSymbols.none { it.id == id }) return
        pushMelodyUndoSnapshot()
        _uiState.update { st ->
            val clearedSel = if ((st.melody.selectedAnnotation as? SelectedScoreAnnotation.ChordSymbol)?.id == id) {
                null
            } else {
                st.melody.selectedAnnotation
            }
            st.copy(
                melody = st.melody.copy(
                    chordSymbols = st.melody.chordSymbols.filterNot { it.id == id },
                    selectedAnnotation = clearedSel
                )
            )
        }
        syncMelodySequenceToEngine()
    }

    fun toggleShowMelodyChordSymbols() {
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(showChordSymbols = !st.melody.showChordSymbols))
        }
    }

    fun toggleMelodyChordPlayback() {
        _uiState.update { st ->
            st.copy(melody = st.melody.copy(chordPlaybackEnabled = !st.melody.chordPlaybackEnabled))
        }
        syncMelodySequenceToEngine()
    }

    fun previewMelodyChordSymbol(rawSymbol: String) {
        val parsed = MelodyChordHelper.parseChordSymbol(rawSymbol) ?: return
        val cur = _uiState.value.melody
        if (cur.isPlaying || cur.isRecording || !cur.soundFontLoaded) return

        val voiced = MelodyChordHelper.voiceChordForAccompaniment(parsed)
        if (voiced.isEmpty()) return

        melodyPlayer.startAudioEngine()
        val slot = cur.resolveChordAccompanimentSlot()

        previewNoteJob?.cancel()
        melodyPlayer.allNotesOff()
        previewNoteJob = viewModelScope.launch {
            for ((idx, pair) in voiced.withIndex()) {
                val (midi, weight) = pair
                if (idx > 0) delay(12L) // gentle natural piano roll feel
                melodyPlayer.noteOn(slot, midi, (weight * 1.18f).coerceIn(0.30f, 0.72f))
            }
            delay(460L)
            for ((midi, _) in voiced) {
                melodyPlayer.noteOff(slot, midi)
            }
        }
    }

    // ========================================================================
    // Score Draft Serialization, Multi-Project CRUD, Auto-Save & File I/O
    // ========================================================================

    private fun onMelodyStateObservedForAutoSave(mel: MelodyStudioUiState) {
        // Automatically assign a permanent project ID the moment a blank score receives meaningful content
        if (mel.currentProjectId == null && MelodyProjectRepository.hasMeaningfulContent(mel)) {
            val assignedId = UUID.randomUUID().toString()
            _uiState.update { st ->
                if (st.melody.currentProjectId != null) st
                else st.copy(melody = st.melody.copy(currentProjectId = assignedId, isProjectDirty = false))
            }
            return
        }

        val sessionFp = MelodyProjectRepository.computeSessionPersistenceFingerprint(mel)
        if (sessionFp == lastObservedMelodyFingerprint) return
        lastObservedMelodyFingerprint = sessionFp
        hasPendingAutoSaveFlush = (sessionFp != lastPersistedSessionFingerprint)

        autoSaveDebounceJob?.cancel()
        autoSaveDebounceJob = viewModelScope.launch {
            delay(180L)
            autoSaveExecutor.execute {
                persistLatestMelodySnapshotSync()
            }
        }
    }

    private fun persistLatestMelodySnapshotSync() {
        runCatching {
            val snapshot = _uiState.value.melody
            val snapSessionFp = MelodyProjectRepository.computeSessionPersistenceFingerprint(snapshot)
            if (!hasPendingAutoSaveFlush && snapSessionFp == lastPersistedSessionFingerprint) {
                return
            }
            val outcome = MelodyProjectRepository.autoPersistActiveSession(appContext, snapshot)
            val contentFp = MelodyProjectRepository.computeContentFingerprint(snapshot)
            lastSavedMelodyFingerprint = contentFp
            lastPersistedSessionFingerprint = snapSessionFp
            hasPendingAutoSaveFlush = false

            _uiState.update { st ->
                val cur = st.melody
                if (cur.currentProjectId != snapshot.currentProjectId &&
                    cur.currentProjectId != null &&
                    outcome.projectId != cur.currentProjectId
                ) {
                    // User already switched to a different project in the meantime; only refresh list
                    st.copy(
                        melody = cur.copy(
                            savedProjects = outcome.savedProjects,
                            autoSaveDraftSummary = outcome.autoDraftSummary
                        )
                    )
                } else {
                    st.copy(
                        melody = cur.copy(
                            currentProjectId = outcome.projectId,
                            isProjectDirty = false,
                            lastAutoSavedAtMs = if (outcome.savedAtMs > 0L) outcome.savedAtMs else cur.lastAutoSavedAtMs,
                            savedProjects = outcome.savedProjects,
                            autoSaveDraftSummary = outcome.autoDraftSummary
                        )
                    )
                }
            }
        }
    }

    /**
     * Immediately flushes any pending edits to disk (`tmp` + `fsync` + atomic `rename`) BEFORE
     * `onPause()` / `onStop()` / `onCleared()` or project switching completes, guaranteeing zero data loss
     * even if the user immediately kills the app from Android Recents.
     */
    fun flushMelodyAutoSaveImmediate() {
        autoSaveDebounceJob?.cancel()
        val cur = _uiState.value.melody
        val curSessionFp = MelodyProjectRepository.computeSessionPersistenceFingerprint(cur)
        if (hasPendingAutoSaveFlush || curSessionFp != lastPersistedSessionFingerprint) {
            persistLatestMelodySnapshotSync()
        }
    }

    fun flushMelodyAutoSaveNow() {
        autoSaveDebounceJob?.cancel()
        autoSaveExecutor.execute {
            persistLatestMelodySnapshotSync()
        }
    }

    private fun applyLoadedMelodyState(
        loadedState: MelodyStudioUiState,
        projectId: String?,
        markClean: Boolean
    ) {
        val cur = _uiState.value.melody
        if (cur.isPlaying || cur.isRecording) {
            if (cur.isRecording) finalizeAllActiveRecordedNotes()
            melodyPlayer.setTransportPlaying(false, 0L)
            melodyPlayer.allNotesOff()
        }
        // Flush previous score's latest edits safely before switching
        flushMelodyAutoSaveImmediate()
        pushMelodyUndoSnapshot()

        val resolvedId = projectId ?: if (MelodyProjectRepository.hasMeaningfulContent(loadedState)) {
            UUID.randomUUID().toString()
        } else {
            null
        }
        val stateToApply = loadedState.copy(
            touchGuardConfig = cur.touchGuardConfig,
            currentProjectId = resolvedId,
            isProjectDirty = false,
            soundFontLoaded = cur.soundFontLoaded,
            canUndo = melodyUndoStack.isNotEmpty(),
            canRedo = melodyRedoStack.isNotEmpty(),
            isPlaying = false,
            isRecording = false,
            playheadBeat = loadedState.cursorTick.toFloat() / TICKS_PER_BEAT.toFloat()
        )
        val outcome = MelodyProjectRepository.autoPersistActiveSession(appContext, stateToApply)
        val finalState = stateToApply.copy(
            currentProjectId = outcome.projectId,
            lastAutoSavedAtMs = outcome.savedAtMs,
            savedProjects = outcome.savedProjects,
            autoSaveDraftSummary = outcome.autoDraftSummary
        )
        val contentFp = MelodyProjectRepository.computeContentFingerprint(finalState)
        val sessionFp = MelodyProjectRepository.computeSessionPersistenceFingerprint(finalState)
        if (markClean) {
            lastSavedMelodyFingerprint = contentFp
        }
        lastPersistedSessionFingerprint = sessionFp
        lastObservedMelodyFingerprint = sessionFp
        hasPendingAutoSaveFlush = false

        _uiState.update { st -> st.copy(melody = finalState) }

        val updated = _uiState.value.melody
        if (updated.soundFontLoaded && _uiState.value.workspaceMode == AppWorkspaceMode.MELODY_STUDIO) {
            melodyPlayer.startAudioEngine()
        }
        syncMelodyInstrumentToEngine(updated)
        syncMelodySequenceToEngine(updated)
    }

    fun createNewBlankMelodyProject() {
        flushMelodyAutoSaveImmediate()
        val cur = _uiState.value.melody
        val blank = MelodyStudioUiState(
            scoreViewMode = cur.scoreViewMode,
            grandStaffEnabled = cur.grandStaffEnabled,
            dualKeyboardStaffRouting = cur.dualKeyboardStaffRouting,
            showChordSymbols = cur.showChordSymbols,
            chordPlaybackEnabled = cur.chordPlaybackEnabled,
            instrument = cur.instrument,
            secondaryInstrument = cur.secondaryInstrument,
            dualKeyboardEnabled = cur.dualKeyboardEnabled,
            primaryScrollWhiteKey = cur.primaryScrollWhiteKey,
            secondaryScrollWhiteKey = cur.secondaryScrollWhiteKey,
            touchGuardConfig = cur.touchGuardConfig,
            sustainPedal = cur.sustainPedal,
            secondarySustainPedal = cur.secondarySustainPedal,
            sustainDurationSec = cur.sustainDurationSec,
            secondarySustainDurationSec = cur.secondarySustainDurationSec,
            reverbPreset = cur.reverbPreset,
            metronomeEnabled = cur.metronomeEnabled
        )
        applyLoadedMelodyState(loadedState = blank, projectId = null, markClean = true)
        sendMessage("已新建空白乐谱（上一份乐谱已自动保存至本地列表）")
    }

    fun saveCurrentMelodyProject(
        overwriteCurrent: Boolean,
        customTitle: String? = null
    ) {
        val cur = _uiState.value.melody
        val cleanTitle = MelodyProjectRepository.sanitizeDisplayTitle(customTitle ?: cur.scoreTitle)
        val targetId = if (overwriteCurrent) cur.currentProjectId else null
        viewModelScope.launch {
            if (!overwriteCurrent && (cur.currentProjectId != null || MelodyProjectRepository.hasMeaningfulContent(cur))) {
                withContext(Dispatchers.IO) { flushMelodyAutoSaveImmediate() }
            }
            val savedSummary = withContext(Dispatchers.IO) {
                MelodyProjectRepository.saveProject(
                    context = appContext,
                    state = cur.copy(scoreTitle = cleanTitle),
                    existingProjectId = targetId,
                    customTitle = cleanTitle
                )
            }
            if (savedSummary == null) {
                sendMessage("保存乐谱失败")
                return@launch
            }
            val nowMs = System.currentTimeMillis()
            val updatedMel = _uiState.value.melody.copy(
                scoreTitle = cleanTitle,
                currentProjectId = savedSummary.id,
                isProjectDirty = false,
                lastAutoSavedAtMs = nowMs
            )
            val outcome = withContext(Dispatchers.IO) {
                MelodyProjectRepository.autoPersistActiveSession(appContext, updatedMel)
            }
            val finalMel = updatedMel.copy(
                savedProjects = outcome.savedProjects,
                autoSaveDraftSummary = outcome.autoDraftSummary
            )
            lastSavedMelodyFingerprint = MelodyProjectRepository.computeContentFingerprint(finalMel)
            lastPersistedSessionFingerprint = MelodyProjectRepository.computeSessionPersistenceFingerprint(finalMel)
            lastObservedMelodyFingerprint = lastPersistedSessionFingerprint
            hasPendingAutoSaveFlush = false
            _uiState.update { st -> st.copy(melody = finalMel) }
            sendMessage(
                if (overwriteCurrent) "已立即同步保存「$cleanTitle」"
                else "已另存为新乐谱副本「$cleanTitle」"
            )
        }
    }

    fun loadMelodyProject(projectId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { flushMelodyAutoSaveImmediate() }
            val loaded = withContext(Dispatchers.IO) {
                MelodyProjectRepository.loadProject(appContext, projectId)
            }
            if (loaded == null) {
                sendMessage("读取乐谱失败：文件不存在或已损坏")
                return@launch
            }
            applyLoadedMelodyState(
                loadedState = loaded.state,
                projectId = projectId,
                markClean = true
            )
            sendMessage("已读取乐谱「${loaded.state.scoreTitle}」")
        }
    }

    fun restoreMelodyAutoSaveDraft() {
        viewModelScope.launch {
            val draft = withContext(Dispatchers.IO) {
                MelodyProjectRepository.readAutoSaveDraft(appContext)
            }
            if (draft == null) {
                sendMessage("暂无可恢复的自动保存草稿")
                return@launch
            }
            applyLoadedMelodyState(
                loadedState = draft.state,
                projectId = draft.projectId,
                markClean = true
            )
            sendMessage("已恢复乐谱「${draft.state.scoreTitle}」")
        }
    }

    fun renameMelodyProject(projectId: String, newTitle: String) {
        val clean = MelodyProjectRepository.sanitizeDisplayTitle(newTitle)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                MelodyProjectRepository.renameProject(appContext, projectId, clean)
            }
            if (!ok) {
                sendMessage("重命名乐谱失败")
                return@launch
            }
            val projects = withContext(Dispatchers.IO) {
                MelodyProjectRepository.listProjects(appContext)
            }
            _uiState.update { st ->
                val isCurrent = st.melody.currentProjectId == projectId
                val nextMel = st.melody.copy(
                    scoreTitle = if (isCurrent) clean else st.melody.scoreTitle,
                    savedProjects = projects
                )
                if (isCurrent) {
                    lastSavedMelodyFingerprint = MelodyProjectRepository.computeContentFingerprint(nextMel)
                    lastPersistedSessionFingerprint =
                        MelodyProjectRepository.computeSessionPersistenceFingerprint(nextMel)
                    lastObservedMelodyFingerprint = lastPersistedSessionFingerprint
                    hasPendingAutoSaveFlush = false
                }
                st.copy(melody = nextMel)
            }
            if (_uiState.value.melody.currentProjectId == projectId) {
                flushMelodyAutoSaveNow()
            }
            sendMessage("已重命名为「$clean」")
        }
    }

    fun deleteMelodyProject(projectId: String) {
        viewModelScope.launch {
            val wasCurrent = _uiState.value.melody.currentProjectId == projectId
            if (wasCurrent) {
                autoSaveDebounceJob?.cancel()
                hasPendingAutoSaveFlush = false
            }
            val ok = withContext(Dispatchers.IO) {
                MelodyProjectRepository.deleteProject(appContext, projectId)
            }
            if (!ok) return@launch
            if (wasCurrent) {
                val cur = _uiState.value.melody
                val blank = MelodyStudioUiState(
                    scoreViewMode = cur.scoreViewMode,
                    grandStaffEnabled = cur.grandStaffEnabled,
                    dualKeyboardStaffRouting = cur.dualKeyboardStaffRouting,
                    showChordSymbols = cur.showChordSymbols,
                    chordPlaybackEnabled = cur.chordPlaybackEnabled,
                    instrument = cur.instrument,
                    secondaryInstrument = cur.secondaryInstrument,
                    dualKeyboardEnabled = cur.dualKeyboardEnabled,
                    primaryScrollWhiteKey = cur.primaryScrollWhiteKey,
                    secondaryScrollWhiteKey = cur.secondaryScrollWhiteKey,
                    touchGuardConfig = cur.touchGuardConfig,
                    sustainPedal = cur.sustainPedal,
                    secondarySustainPedal = cur.secondarySustainPedal,
                    sustainDurationSec = cur.sustainDurationSec,
                    secondarySustainDurationSec = cur.secondarySustainDurationSec,
                    reverbPreset = cur.reverbPreset,
                    metronomeEnabled = cur.metronomeEnabled,
                    soundFontLoaded = cur.soundFontLoaded
                )
                val outcome = withContext(Dispatchers.IO) {
                    MelodyProjectRepository.autoPersistActiveSession(appContext, blank)
                }
                val resetState = blank.copy(
                    savedProjects = outcome.savedProjects,
                    autoSaveDraftSummary = outcome.autoDraftSummary
                )
                lastSavedMelodyFingerprint = MelodyProjectRepository.computeContentFingerprint(resetState)
                lastPersistedSessionFingerprint =
                    MelodyProjectRepository.computeSessionPersistenceFingerprint(resetState)
                lastObservedMelodyFingerprint = lastPersistedSessionFingerprint
                hasPendingAutoSaveFlush = false
                _uiState.update { st -> st.copy(melody = resetState) }
                syncMelodySequenceToEngine(resetState)
            } else {
                val projects = withContext(Dispatchers.IO) {
                    MelodyProjectRepository.listProjects(appContext)
                }
                _uiState.update { st ->
                    st.copy(melody = st.melody.copy(savedProjects = projects))
                }
            }
            sendMessage("已删除已存乐谱")
        }
    }

    private suspend fun resolveProjectJsonForExport(
        projectId: String?,
        customTitle: String?
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (!projectId.isNullOrBlank()) {
            val loaded = MelodyProjectRepository.loadProject(appContext, projectId) ?: return@withContext null
            val title = MelodyProjectRepository.sanitizeDisplayTitle(customTitle ?: loaded.state.scoreTitle)
            val json = MelodyProjectRepository.serializeToJson(
                state = loaded.state.copy(scoreTitle = title),
                projectId = projectId
            )
            json to title
        } else {
            val cur = _uiState.value.melody
            val title = MelodyProjectRepository.sanitizeDisplayTitle(customTitle ?: cur.scoreTitle)
            val json = MelodyProjectRepository.serializeToJson(
                state = cur.copy(scoreTitle = title),
                projectId = cur.currentProjectId
            )
            json to title
        }
    }

    fun shareMelodyProjectFile(
        projectId: String?,
        customTitle: String?,
        targetWeChatDirectly: Boolean
    ) {
        viewModelScope.launch {
            val pair = resolveProjectJsonForExport(projectId, customTitle) ?: run {
                sendMessage("导出乐谱工程失败")
                return@launch
            }
            val (json, title) = pair
            val intent = withContext(Dispatchers.IO) {
                MelodyProjectRepository.prepareProjectShareIntent(
                    context = appContext,
                    jsonContent = json,
                    displayTitle = title,
                    targetWeChatDirectly = targetWeChatDirectly
                )
            }
            refreshStorageInfo()
            if (intent != null) {
                _events.trySend(StudioUiEvent.LaunchShareIntent(intent))
            } else {
                sendMessage("创建工程分享失败")
            }
        }
    }

    fun saveMelodyProjectFileToDownloads(projectId: String?, customTitle: String?) {
        viewModelScope.launch {
            val pair = resolveProjectJsonForExport(projectId, customTitle) ?: run {
                sendMessage("导出乐谱工程失败")
                return@launch
            }
            val (json, title) = pair
            val relPath = withContext(Dispatchers.IO) {
                MelodyProjectRepository.saveProjectJsonToDownloads(appContext, json, title)
            }
            if (relPath != null) {
                sendMessage("乐谱工程已导出至：$relPath")
            } else {
                sendMessage("导出乐谱工程到本地失败")
            }
        }
    }

    fun saveMelodyProjectFileToCustomUri(
        projectId: String?,
        customTitle: String?,
        targetUri: Uri
    ) {
        viewModelScope.launch {
            val pair = resolveProjectJsonForExport(projectId, customTitle) ?: run {
                sendMessage("导出乐谱工程失败")
                return@launch
            }
            val (json, title) = pair
            val ok = withContext(Dispatchers.IO) {
                MelodyProjectRepository.saveProjectJsonToUri(appContext, json, targetUri)
            }
            if (ok) {
                sendMessage("已保存乐谱工程「$title」到所选位置")
            } else {
                sendMessage("另存乐谱工程失败")
            }
        }
    }

    fun importMelodyProjectFromUri(srcUri: Uri) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                MelodyProjectRepository.importProjectFromUri(appContext, srcUri)
            }
            if (imported == null) {
                sendMessage("导入失败：请选择有效的 SoundTracker 乐谱工程文件 (.json)")
                return@launch
            }
            val savedSummary = withContext(Dispatchers.IO) {
                MelodyProjectRepository.saveProject(
                    context = appContext,
                    state = imported.state,
                    existingProjectId = null,
                    customTitle = imported.state.scoreTitle
                )
            }
            if (_uiState.value.workspaceMode != AppWorkspaceMode.MELODY_STUDIO) {
                setWorkspaceMode(AppWorkspaceMode.MELODY_STUDIO)
            }
            applyLoadedMelodyState(
                loadedState = imported.state,
                projectId = savedSummary?.id,
                markClean = savedSummary != null
            )
            sendMessage("已导入并打开乐谱「${imported.state.scoreTitle}」")
        }
    }

    // ========================================================================
    // Export 5-Line Staff Sheet Music as Vector PDF (WeChat / System / Local)
    // ========================================================================

    fun shareMelodyScorePdf(customTitle: String, targetWeChatDirectly: Boolean) {
        val cleanTitle = customTitle.trim().ifBlank { "未命名乐谱" }
        setMelodyScoreTitle(cleanTitle)
        val snapshotMel = _uiState.value.melody.copy(scoreTitle = cleanTitle)

        viewModelScope.launch {
            val tmpPdf = File(SessionWorkspaceManager.tempDir(appContext), "staff_${System.nanoTime()}.pdf")
            try {
                val rendered = withContext(Dispatchers.IO) {
                    StaffScoreEngraver.exportScoreToPdfFile(snapshotMel, tmpPdf)
                }
                if (!rendered) {
                    sendMessage("五线谱 PDF 生成失败")
                    return@launch
                }
                val shareRes = SessionWorkspaceManager.preparePdfShareIntent(
                    context = appContext,
                    sourcePdfFile = tmpPdf,
                    customTitle = cleanTitle,
                    targetWeChatDirectly = targetWeChatDirectly
                )
                refreshStorageInfo()
                shareRes.fold(
                    onSuccess = { intent ->
                        _events.trySend(StudioUiEvent.LaunchShareIntent(intent))
                    },
                    onFailure = { err ->
                        sendMessage("分享五线谱 PDF 失败：${err.message ?: "未知错误"}")
                    }
                )
            } finally {
                if (tmpPdf.exists()) tmpPdf.delete()
            }
        }
    }

    fun saveMelodyScorePdfToDownloads(customTitle: String) {
        val cleanTitle = customTitle.trim().ifBlank { "未命名乐谱" }
        setMelodyScoreTitle(cleanTitle)
        val snapshotMel = _uiState.value.melody.copy(scoreTitle = cleanTitle)

        viewModelScope.launch {
            val tmpPdf = File(SessionWorkspaceManager.tempDir(appContext), "staff_dl_${System.nanoTime()}.pdf")
            try {
                val rendered = withContext(Dispatchers.IO) {
                    StaffScoreEngraver.exportScoreToPdfFile(snapshotMel, tmpPdf)
                }
                if (!rendered) {
                    sendMessage("五线谱 PDF 生成失败")
                    return@launch
                }
                val res = SessionWorkspaceManager.savePdfToPublicDownloads(
                    context = appContext,
                    sourcePdfFile = tmpPdf,
                    customTitle = cleanTitle
                )
                res.fold(
                    onSuccess = { relPath ->
                        sendMessage("五线谱 PDF 已存到本地：$relPath")
                    },
                    onFailure = { err ->
                        sendMessage("保存 PDF 失败：${err.message ?: "未知错误"}")
                    }
                )
            } finally {
                if (tmpPdf.exists()) tmpPdf.delete()
            }
        }
    }

    fun saveMelodyScorePdfToCustomUri(customTitle: String, targetUri: Uri) {
        val cleanTitle = customTitle.trim().ifBlank { "未命名乐谱" }
        setMelodyScoreTitle(cleanTitle)
        val snapshotMel = _uiState.value.melody.copy(scoreTitle = cleanTitle)

        viewModelScope.launch {
            val tmpPdf = File(SessionWorkspaceManager.tempDir(appContext), "staff_saf_${System.nanoTime()}.pdf")
            try {
                val rendered = withContext(Dispatchers.IO) {
                    StaffScoreEngraver.exportScoreToPdfFile(snapshotMel, tmpPdf)
                }
                if (!rendered) {
                    sendMessage("五线谱 PDF 生成失败")
                    return@launch
                }
                val res = SessionWorkspaceManager.savePdfToCustomUri(
                    context = appContext,
                    sourcePdfFile = tmpPdf,
                    targetUri = targetUri
                )
                res.fold(
                    onSuccess = { name ->
                        sendMessage("五线谱 PDF 已保存到所选目录：$name")
                    },
                    onFailure = { err ->
                        sendMessage("另存 PDF 失败：${err.message ?: "未知错误"}")
                    }
                )
            } finally {
                if (tmpPdf.exists()) tmpPdf.delete()
            }
        }
    }

    /**
     * Renders the composed melody offline with both independent keyboard SoundFont2 slots + Stereo Reverb/Chorus
     * into a 44.1kHz lossless `.wav` file and places it directly into `工作台音轨` (same list as imported songs).
     */
    fun exportMelodyToWorkspace() {
        val cur = _uiState.value.melody
        if (!cur.hasPlayableNotes) {
            sendMessage("请先在键盘上输入发声音符（或点「示例曲」体验）再生成音轨")
            return
        }
        if (_uiState.value.processingTask != null) return

        if (cur.isPlaying || cur.isRecording) {
            finalizeAllActiveRecordedNotes()
            melodyPlayer.setTransportPlaying(false, 0L)
            melodyPlayer.allNotesOff()
            _uiState.update { st ->
                st.copy(melody = st.melody.copy(isPlaying = false, isRecording = false))
            }
        }

        val snapshotMel = _uiState.value.melody
        val inst0 = snapshotMel.instrument
        val inst1 = snapshotMel.secondaryInstrument
        val pitchedNotes = snapshotMel.notes.filterNot { it.isRest }
        val compiledSchedule = snapshotMel.compileSchedule(MELODY_SAMPLE_RATE)
        val nativeNotes = compiledSchedule.notes
        val seqFrames = compiledSchedule.totalFrames
        val openingBpm = compiledSchedule.tempoMap.bpmAtTick(0)
        val hasSlot0 = nativeNotes.any { it.slot == 0 }
        val hasSlot1 = nativeNotes.any { it.slot == 1 }
        val instSummaryTitle = when {
            hasSlot0 && hasSlot1 && inst0 != inst1 -> "${inst0.displayName}+${inst1.displayName}"
            !hasSlot0 && hasSlot1 -> inst1.displayName
            else -> inst0.displayName
        }
        val instShortSummary = when {
            hasSlot0 && hasSlot1 && inst0 != inst1 -> "${inst0.shortName}+${inst1.shortName}"
            !hasSlot0 && hasSlot1 -> inst1.shortName
            else -> inst0.shortName
        }

        activeProcessingJob = viewModelScope.launch {
            val outWav = SessionWorkspaceManager.createSessionTrackFile(appContext, "melody", "wav")
            try {
                NativeAudioEngine.nativeResetCancel()
                _uiState.update {
                    it.copy(
                        processingTask = ProcessingTaskState(
                            title = "正在生成「$instSummaryTitle」旋律音轨",
                            subtitle = "正在渲染真实采样音源与自然延音尾韵...",
                            progress = 0.08f,
                            cancellable = true
                        )
                    )
                }

                val outcome = NativeAudioEngine.renderMelodyToWav(
                    context = appContext,
                    bank0 = inst0.bank,
                    preset0 = inst0.presetNumber,
                    gainDb0 = inst0.gainDb,
                    reverbSend = snapshotMel.effectiveReverbSend,
                    chorus0 = inst0.chorusDepth,
                    sustain0 = snapshotMel.sustainPedal,
                    sustainSec0 = snapshotMel.sustainDurationSec,
                    bank1 = inst1.bank,
                    preset1 = inst1.presetNumber,
                    gainDb1 = inst1.gainDb,
                    chorus1 = inst1.chorusDepth,
                    sustain1 = snapshotMel.secondarySustainPedal,
                    sustainSec1 = snapshotMel.secondarySustainDurationSec,
                    notes = nativeNotes,
                    sequenceDurationFrames = seqFrames,
                    outputSampleRate = MELODY_SAMPLE_RATE,
                    outputWav = outWav
                ) { p ->
                    _uiState.update { st ->
                        st.copy(
                            processingTask = st.processingTask?.copy(
                                progress = (0.08f + p * 0.80f).coerceIn(0.08f, 0.88f)
                            )
                        )
                    }
                }

                when (outcome) {
                    is VocalSeparationOutcome.Cancelled -> {
                        outWav.delete()
                        sendMessage("已取消生成旋律音轨")
                        return@launch
                    }
                    is VocalSeparationOutcome.Error -> {
                        outWav.delete()
                        sendMessage("生成旋律失败：${outcome.message}")
                        return@launch
                    }
                    is VocalSeparationOutcome.Success -> Unit
                }

                _uiState.update { st ->
                    st.copy(
                        processingTask = st.processingTask?.copy(
                            subtitle = "正在写入工作台音轨列表...",
                            progress = 0.93f
                        )
                    )
                }

                val rawMetrics = NativeAudioEngine.analyzeWav(outWav) ?: run {
                    outWav.delete()
                    sendMessage("旋律音频分析失败")
                    return@launch
                }
                val exactMetrics = rawMetrics.copy(detectedBpm = openingBpm)

                val idx = melodyExportCounter++
                val filePrefix = when {
                    hasSlot0 && hasSlot1 && inst0 != inst1 -> "${inst0.shortName}_${inst1.shortName}"
                    !hasSlot0 && hasSlot1 -> inst1.displayName
                    else -> inst0.displayName
                }
                val customTitlePart = snapshotMel.scoreTitle.trim()
                    .takeIf { it.isNotBlank() && it != "未命名乐谱" }
                val rawTrackName = if (customTitlePart != null) {
                    "${customTitlePart}_${filePrefix}"
                } else {
                    "${filePrefix}_旋律${String.format(Locale.US, "%02d", idx)}"
                }
                val displayName = AudioCodecHelper.sanitizeFileName(rawTrackName)
                val swingNote = if (snapshotMel.swingPreset.isEnabled) " · ${snapshotMel.swingPreset.shortBadge}" else ""
                val chordNote = if (snapshotMel.chordPlaybackEnabled && snapshotMel.chordSymbols.isNotEmpty()) {
                    " · ${snapshotMel.chordSymbols.size}和弦"
                } else {
                    ""
                }
                val timeSigLabel = snapshotMel.timeSignature.label
                val bpmInt = openingBpm.roundToInt()
                val newTrack = SessionTrack(
                    displayName = displayName,
                    badge = "打谱 · $instShortSummary · ${timeSigLabel}拍 · ${bpmInt}BPM$swingNote",
                    detailNote = "${pitchedNotes.size}音$chordNote · ${timeSigLabel}拍$swingNote · ${snapshotMel.reverbPreset.label} · ${formatMetricsSummary(exactMetrics)}",
                    audioFile = outWav,
                    originTrackId = null,
                    metrics = exactMetrics
                ).let { it.copy(originTrackId = it.id) }

                stopMelodyPlaybackAndEngine()
                _uiState.update { st -> st.copy(workspaceMode = AppWorkspaceMode.SINGLE_TRACK) }
                appendTrackToWorkspace(newTrack)
                refreshStorageInfo()
                sendMessage("已将「$displayName」放入工作台音轨，可直接试听或点剪刀送入多轨剪辑")
            } catch (_: CancellationException) {
                outWav.delete()
            } catch (e: Throwable) {
                outWav.delete()
                sendMessage("生成旋律异常：${e.message ?: "未知错误"}")
            } finally {
                _uiState.update { it.copy(processingTask = null) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { flushMelodyAutoSaveImmediate() }
        runCatching { autoSaveExecutor.shutdown() }
        previewNoteJob?.cancel()
        updateCheckJob?.cancel()
        updateDownloadJob?.cancel()
        runCatching { melodyPlayer.release() }
        runCatching { arrangerPlayer.release() }
        runCatching { exoPlayer.release() }
    }
}
