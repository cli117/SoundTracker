package com.soundtracker.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundtracker.app.audio.AudioCodecHelper
import com.soundtracker.app.ui.MultiTrackArrangerScreen
import com.soundtracker.app.ui.StudioScreen
import com.soundtracker.app.ui.StudioUiEvent
import com.soundtracker.app.ui.StudioViewModel
import com.soundtracker.app.ui.theme.SoundTrackerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            handleIncomingAudioIntent(intent)
        }

        setContent {
            SoundTrackerTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                var pendingCustomSaveTrackId by remember { mutableStateOf<String?>(null) }

                // Picker for importing audio, video, or .ncm files
                val openAudioLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        viewModel.importAudioUri(uri)
                    }
                }

                // Launcher for Android 11+ All Files Access (needed to scan NetEase .ncm files)
                val manageAllFilesLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    viewModel.scanNetEaseCloudMusic()
                }

                // SAF "Save As" custom folder/file picker
                val createDocumentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("*/*")
                ) { targetUri: Uri? ->
                    val trackId = pendingCustomSaveTrackId
                    pendingCustomSaveTrackId = null
                    if (targetUri != null && trackId != null) {
                        viewModel.saveTrackToCustomUri(trackId, targetUri)
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is StudioUiEvent.ShowMessage -> {
                                snackbarHostState.showSnackbar(event.message)
                            }
                            is StudioUiEvent.LaunchShareIntent -> {
                                runCatching {
                                    startActivity(event.intent)
                                }.onFailure {
                                    snackbarHostState.showSnackbar("无法唤起分享面板：${it.message}")
                                }
                            }
                        }
                    }
                }

                StudioScreen(
                    uiState = uiState,
                    snackbarHostState = snackbarHostState,
                    onPickAudioClick = {
                        openAudioLauncher.launch(arrayOf("*/*"))
                    },
                    onScanNetEaseClick = viewModel::scanNetEaseCloudMusic,
                    onImportNetEaseTracks = viewModel::importNetEaseTracks,
                    onRequestAllFilesAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                manageAllFilesLauncher.launch(intent)
                            }.onFailure {
                                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                manageAllFilesLauncher.launch(fallback)
                            }
                        }
                    },
                    onSelectTrack = viewModel::selectTrack,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onSeekFraction = viewModel::seekToFraction,
                    onSkipMs = viewModel::skipByMs,
                    onToggleAbCompare = viewModel::toggleAbCompare,
                    onSelectTab = viewModel::selectTab,
                    onVocalEngineModeChange = viewModel::setVocalEngineMode,
                    onEnableDeepDenoiseChange = viewModel::setEnableDeepDenoise,
                    onKeepVocalChange = viewModel::setKeepVocalStemToo,
                    onRunVocalSeparation = viewModel::runVocalSeparation,
                    onPitchSemitonesChange = viewModel::setPitchSemitones,
                    onPitchCentsChange = viewModel::setPitchCents,
                    onTargetBpmChange = viewModel::setTargetBpm,
                    onAdjustTargetBpmBy = viewModel::adjustTargetBpmBy,
                    onCalibrateSourceBpm = viewModel::calibrateSourceBpm,
                    onPreserveTimbreChange = viewModel::setPreserveTimbre,
                    onLivePitchPreviewChange = viewModel::setLivePitchTempoPreview,
                    onRunPitchShift = viewModel::runPitchShift,
                    onVolumeGainChange = viewModel::setVolumeGainDb,
                    onLiveVolumePreviewChange = viewModel::setLiveVolumeBoostPreview,
                    onRunVolumeBoost = viewModel::runVolumeBoost,
                    onExportFormatChange = viewModel::setExportFormat,
                    onRenameTrack = viewModel::renameTrack,
                    onShareWeChat = { trackId -> viewModel.shareTrack(trackId, targetWeChatDirectly = true) },
                    onShareSystemMore = { trackId -> viewModel.shareTrack(trackId, targetWeChatDirectly = false) },
                    onQuickSaveMusic = viewModel::saveTrackToMusicFolder,
                    onCustomSaveDocument = { trackId ->
                        val track = uiState.tracks.firstOrNull { it.id == trackId }
                        if (track != null) {
                            pendingCustomSaveTrackId = trackId
                            val ext = uiState.exportFormat.extension
                            val suggested = "${AudioCodecHelper.sanitizeFileName(track.displayName)}.$ext"
                            createDocumentLauncher.launch(suggested)
                        }
                    },
                    onDeleteTrack = viewModel::deleteSessionTrack,
                    onDeleteResourceItem = viewModel::deleteStoredResourceItem,
                    onDeleteSelectedResources = viewModel::deleteSelectedStoredResources,
                    onClearWorkspace = viewModel::clearAllWorkspaceTracks,
                    onRefreshStorage = viewModel::refreshStorageInfo,
                    onCancelProcessing = viewModel::cancelActiveProcessing,
                    onWorkspaceModeChange = viewModel::setWorkspaceMode,
                    onSendTrackToArranger = { trackId ->
                        viewModel.addTrackToArranger(
                            sessionTrackId = trackId,
                            switchToArranger = true
                        )
                    },
                    arrangerContent = { bottomPadding, openNetEaseSheet ->
                        MultiTrackArrangerScreen(
                            arranger = uiState.arranger,
                            workspaceTracks = uiState.tracks,
                            onUndo = viewModel::undoArranger,
                            onRedo = viewModel::redoArranger,
                            onClearProject = viewModel::clearArrangerProject,
                            onExportMixdown = viewModel::exportArrangerMixdown,
                            onTogglePlayPause = viewModel::toggleArrangerPlayPause,
                            onSeekToMs = viewModel::seekArrangerToMs,
                            onZoomChange = viewModel::setArrangerZoom,
                            onAddTrack = viewModel::addArrangerTrack,
                            onSelectTrack = viewModel::selectArrangerTrack,
                            onToggleTrackMute = viewModel::toggleArrangerTrackMute,
                            onToggleTrackSolo = viewModel::toggleArrangerTrackSolo,
                            onTrackVolumeChange = viewModel::setArrangerTrackVolumeDb,
                            onRemoveTrack = viewModel::removeArrangerTrack,
                            onSelectClip = viewModel::selectArrangerClip,
                            onBeginGestureUndo = viewModel::beginArrangerGestureUndoSnapshot,
                            onAddClipFromWorkspace = { trackId, inMs, outMs, atPlayhead ->
                                viewModel.addTrackToArranger(
                                    sessionTrackId = trackId,
                                    customInMs = inMs,
                                    customOutMs = outMs,
                                    placeAtPlayhead = atPlayhead,
                                    switchToArranger = false
                                )
                            },
                            onOpenNetEaseImport = openNetEaseSheet,
                            onPickLocalAudio = {
                                openAudioLauncher.launch(arrayOf("*/*"))
                            },
                            onSplitAtPlayhead = viewModel::splitArrangerClipAtPlayhead,
                            onDeleteSelectedClip = viewModel::deleteSelectedArrangerClip,
                            onDuplicateSelectedClip = viewModel::duplicateSelectedArrangerClip,
                            onMoveClipRealtime = viewModel::moveArrangerClipRealtime,
                            onMoveSelectedClipTrack = viewModel::moveSelectedClipToAdjacentTrack,
                            onNudgeSelectedClipPos = viewModel::nudgeSelectedClipPosition,
                            onSnapSelectedToPlayhead = viewModel::snapSelectedClipToPlayhead,
                            onSnapSelectedToPrevious = viewModel::snapSelectedClipToPrevious,
                            onTrimClipRealtime = viewModel::trimArrangerClipRealtime,
                            onNudgeSelectedClipTrim = viewModel::nudgeSelectedClipTrim,
                            onSelectedClipGainChange = viewModel::setSelectedClipGainDb,
                            onSelectedClipFadesChange = viewModel::setSelectedClipFades,
                            modifier = Modifier.padding(bottom = bottomPadding)
                        )
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingAudioIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            viewModel.pausePlayback()
        }
    }

    private fun handleIncomingAudioIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri -> viewModel.importAudioUri(uri) }
            }
            Intent.ACTION_SEND -> {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                streamUri?.let { uri -> viewModel.importAudioUri(uri) }
            }
        }
    }
}
