package com.soundtracker.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.soundtracker.app.audio.AudioCodecHelper
import com.soundtracker.app.audio.NetEaseCloudMusicManager
import com.soundtracker.app.audio.NetEaseLocalTrack
import com.soundtracker.app.audio.RealtimePitchAudioProcessor
import com.soundtracker.app.audio.buildRealtimePreviewExoPlayer
import com.soundtracker.app.dsp.AudioMetrics
import com.soundtracker.app.dsp.ExportFormat
import com.soundtracker.app.dsp.NativeAudioEngine
import com.soundtracker.app.dsp.VocalEngineMode
import com.soundtracker.app.dsp.VocalSeparationOutcome
import com.soundtracker.app.storage.EphemeralStorageInfo
import com.soundtracker.app.storage.SessionWorkspaceManager
import kotlinx.coroutines.CancellationException
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
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

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
    // Tool 3 options
    val volumeGainDb: Float = 4.0f,
    // Export format
    val exportFormat: ExportFormat = ExportFormat.MP3_320,
    // Processing overlay & storage stats
    val processingTask: ProcessingTaskState? = null,
    val storageInfo: EphemeralStorageInfo = EphemeralStorageInfo(0L, 0L, 0),
    // NetEase Cloud Music local scanner state
    val netEaseTracks: List<NetEaseLocalTrack> = emptyList(),
    val isScanningNetEase: Boolean = false,
    val hasNetEaseStorageAccess: Boolean = true
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

    val isLivePitchTempoPreviewActive: Boolean
        get() = activeTab == StudioToolTab.PITCH_SHIFT &&
            livePitchTempoPreview &&
            !isComparingOriginal &&
            (pitchSemitones != 0 || pitchCents != 0 || targetBpm != sourceBpm)

    val livePreviewSummaryLabel: String?
        get() {
            if (!isLivePitchTempoPreviewActive) return null
            val parts = buildList {
                if (pitchSemitones != 0 || pitchCents != 0) {
                    val semiStr = if (pitchSemitones > 0) "+$pitchSemitones" else "$pitchSemitones"
                    add("${semiStr}半音")
                }
                if (targetBpm != sourceBpm) {
                    add("${targetBpm}BPM")
                }
            }
            return if (parts.isEmpty()) null else "实时预览 · ${parts.joinToString(" · ")}"
        }
}

sealed interface StudioUiEvent {
    data class ShowMessage(val message: String) : StudioUiEvent
    data class LaunchShareIntent(val intent: Intent) : StudioUiEvent
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private val _events = Channel<StudioUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val realtimePitchProcessor = RealtimePitchAudioProcessor()
    private val exoPlayer: ExoPlayer = buildRealtimePreviewExoPlayer(appContext, realtimePitchProcessor)
    private var loadedPlayerFilePath: String? = null
    private var activeProcessingJob: Job? = null

    init {
        viewModelScope.launch {
            _uiState.collect { state ->
                syncRealtimePreviewProcessor(state)
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
                    volumeGainDb = suggestSmartGainDb(first.metrics)
                )
            }
            preparePlayerForTrack(first, seekMs = 0L, playImmediately = false)
        }
        refreshStorageInfo()
    }

    fun refreshStorageInfo() {
        val currentTracks = _uiState.value.tracks
        SessionWorkspaceManager.saveTracksIndex(appContext, currentTracks)
        val info = SessionWorkspaceManager.getStorageInfo(appContext, currentTracks)
        _uiState.update { it.copy(storageInfo = info) }
    }

    // ========================================================================
    // Playback & Instant A/B Original-vs-Processed Audition
    // ========================================================================

    fun selectTrack(trackId: String) {
        val track = _uiState.value.tracks.firstOrNull { it.id == trackId } ?: return
        pausePlayback()
        val smartDefaultGain = suggestSmartGainDb(track.metrics)
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
                volumeGainDb = smartDefaultGain
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
        val enabled = state.activeTab == StudioToolTab.PITCH_SHIFT &&
            state.livePitchTempoPreview &&
            !state.isComparingOriginal
        val semitones = state.pitchSemitones + (state.pitchCents / 100f)
        val srcBpm = state.sourceBpm.coerceIn(40, 240).toDouble()
        val dstBpm = state.targetBpm.coerceIn(40, 240).toDouble()
        val timeRatio = srcBpm / dstBpm
        realtimePitchProcessor.updatePreviewConfig(
            enabled = enabled,
            semitones = semitones,
            timeRatio = timeRatio,
            preserveTimbre = state.preserveTimbre
        )
    }

    fun setVolumeGainDb(gainDb: Float) {
        val rounded = (gainDb.coerceIn(0f, 18f) * 10f).roundToInt() / 10f
        _uiState.update { it.copy(volumeGainDb = rounded) }
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

                if (importedCount == 1) {
                    sendMessage("已从网易云导入「$lastImportedName.$lastFormat」· 速度 $lastBpm BPM")
                } else if (importedCount > 1) {
                    sendMessage("已从网易云成功导入 $importedCount 首音乐（自动转换为最佳 FLAC/MP3 格式）")
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
                sendMessage("已导入「${newTrack.displayName}.${workspaceFile.extension.lowercase()}」· 自动识别速度 ${detectedBpm} BPM")
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
                            title = "正在进行无损/防破音音量放大 ($gainStr)",
                            subtitle = "32-bit 浮点线性增益 + 4x超采样真峰值限幅保护...",
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
                    "100%纯线性无损增益"
                } else {
                    String.format(Locale.US, "母带防破音保护(峰值抑制%.1fdB)", report.maxGainReductionDb)
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
                sendMessage("音量放大完成（$modeDesc，零削波破音）")
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
                }
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
        val nextSelectedTrack = remaining.firstOrNull { it.id == state.selectedTrackId } ?: remaining.firstOrNull()
        val nextBpm = nextSelectedTrack?.metrics?.detectedBpm?.roundToInt()?.coerceIn(45, 220) ?: 120

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
                targetBpm = if (switchedTrack) nextBpm else it.targetBpm
            )
        }
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
        loadedPlayerFilePath = null
        SessionWorkspaceManager.wipeSessionWorkspace(appContext)
        _uiState.update {
            it.copy(
                tracks = emptyList(),
                selectedTrackId = null,
                isPlaying = false,
                isComparingOriginal = false,
                playbackPositionMs = 0L,
                playbackDurationMs = 0L,
                pitchSemitones = 0,
                pitchCents = 0
            )
        }
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
        val smartGain = suggestSmartGainDb(newTrack.metrics)
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
                volumeGainDb = smartGain
            )
        }
        syncRealtimePreviewProcessor()
        preparePlayerForTrack(newTrack, seekMs = 0L, playImmediately = false)
    }

    private fun suggestSmartGainDb(metrics: AudioMetrics): Float {
        return when {
            metrics.pureLosslessHeadroomDb >= 1.5f -> {
                ((metrics.pureLosslessHeadroomDb.coerceAtMost(12f) * 10f).roundToInt() / 10f)
            }
            metrics.integratedLufs < -12.5f && metrics.integratedLufs > -60f -> {
                (((-11.0f - metrics.integratedLufs).coerceIn(1.5f, 9.0f) * 10f).roundToInt() / 10f)
            }
            else -> 3.0f
        }
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

    override fun onCleared() {
        super.onCleared()
        runCatching { exoPlayer.release() }
    }
}
