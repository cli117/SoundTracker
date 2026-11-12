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
import com.soundtracker.app.ui.MelodyStudioScreen
import com.soundtracker.app.ui.MultiTrackArrangerScreen
import com.soundtracker.app.ui.StudioScreen
import com.soundtracker.app.ui.StudioUiEvent
import com.soundtracker.app.ui.StudioViewModel
import com.soundtracker.app.ui.theme.SoundTrackerTheme
import com.soundtracker.app.update.AppUpdateManager
import java.io.File

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
                var pendingPdfCustomSaveTitle by remember { mutableStateOf<String?>(null) }
                var pendingMelodyProjectSaveSpec by remember { mutableStateOf<Pair<String?, String?>?>(null) }
                var pendingInstallApkFile by remember { mutableStateOf<File?>(null) }

                val launchApkInstaller: (File) -> Unit = { apkFile ->
                    runCatching {
                        startActivity(AppUpdateManager.buildInstallApkIntent(this@MainActivity, apkFile))
                    }.onFailure { err ->
                        viewModel.refreshStorageInfo()
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "无法调起系统安装器：${err.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }

                // Launcher for first-time "Allow from this source" (Unknown App Sources) permission
                val unknownSourcesLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    val targetApk = pendingInstallApkFile
                    if (targetApk != null && targetApk.exists() &&
                        AppUpdateManager.canRequestInstallPackages(this@MainActivity)
                    ) {
                        pendingInstallApkFile = null
                        launchApkInstaller(targetApk)
                    }
                }

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

                // SAF "Save PDF Sheet Music As" custom folder/file picker
                val createPdfDocumentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/pdf")
                ) { targetUri: Uri? ->
                    val pdfTitle = pendingPdfCustomSaveTitle
                    pendingPdfCustomSaveTitle = null
                    if (targetUri != null && pdfTitle != null) {
                        viewModel.saveMelodyScorePdfToCustomUri(pdfTitle, targetUri)
                    }
                }

                // SAF "Save Melody Score Project (.stscore.json)" custom folder/file picker
                val createMelodyProjectLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/json")
                ) { targetUri: Uri? ->
                    val spec = pendingMelodyProjectSaveSpec
                    pendingMelodyProjectSaveSpec = null
                    if (targetUri != null && spec != null) {
                        viewModel.saveMelodyProjectFileToCustomUri(
                            projectId = spec.first,
                            customTitle = spec.second,
                            targetUri = targetUri
                        )
                    }
                }

                // SAF "Import Melody Score Project (.json)" picker
                val openMelodyProjectLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { srcUri: Uri? ->
                    if (srcUri != null) {
                        viewModel.importMelodyProjectFromUri(srcUri)
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
                            is StudioUiEvent.RequestInstallApk -> {
                                val apkFile = event.apkFile
                                if (!apkFile.exists()) {
                                    snackbarHostState.showSnackbar("更新安装包不存在，请重新下载")
                                } else if (AppUpdateManager.canRequestInstallPackages(this@MainActivity)) {
                                    launchApkInstaller(apkFile)
                                } else {
                                    pendingInstallApkFile = apkFile
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "首次应用内更新请先开启「允许来自此来源的应用」，返回后将自动弹出安装",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    runCatching {
                                        unknownSourcesLauncher.launch(
                                            AppUpdateManager.buildUnknownSourcesSettingsIntent(this@MainActivity)
                                        )
                                    }.onFailure {
                                        snackbarHostState.showSnackbar("请在系统设置中允许 SoundTracker 安装未知应用")
                                    }
                                }
                            }
                            is StudioUiEvent.OpenExternalUrl -> {
                                runCatching {
                                    startActivity(AppUpdateManager.buildBrowserIntent(event.url))
                                }.onFailure {
                                    snackbarHostState.showSnackbar("无法打开浏览器：${it.message}")
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
                    onCheckAppUpdate = { viewModel.checkForAppUpdate(manual = true) },
                    onSelectUpdateChannel = viewModel::setUpdateDownloadChannel,
                    onStartOrInstallUpdate = viewModel::startOrInstallAppUpdate,
                    onCancelUpdateDownload = viewModel::cancelAppUpdateDownload,
                    onOpenReleaseBrowser = viewModel::openGithubReleaseInBrowser,
                    melodyContent = { bottomPadding ->
                        MelodyStudioScreen(
                            melody = uiState.melody,
                            referenceTrackBpm = uiState.sourceBpm.takeIf { uiState.tracks.isNotEmpty() },
                            referenceTrackKeySignature = uiState.referenceTrackKeySignature,
                            referenceTrackName = uiState.selectedTrack?.displayName,
                            onScoreTitleChange = viewModel::setMelodyScoreTitle,
                            onUndo = viewModel::undoMelody,
                            onRedo = viewModel::redoMelody,
                            onClearScore = viewModel::clearMelodyScore,
                            onLoadDemoMelody = viewModel::loadMelodyDemoShowcase,
                            onExportMelodyToWorkspace = viewModel::exportMelodyToWorkspace,
                            onShareStaffPdfWeChat = { title ->
                                viewModel.shareMelodyScorePdf(title, targetWeChatDirectly = true)
                            },
                            onShareStaffPdfSystem = { title ->
                                viewModel.shareMelodyScorePdf(title, targetWeChatDirectly = false)
                            },
                            onSaveStaffPdfDownloads = viewModel::saveMelodyScorePdfToDownloads,
                            onSaveStaffPdfCustomFolder = { title ->
                                val clean = title.trim().ifBlank { "未命名乐谱" }
                                pendingPdfCustomSaveTitle = clean
                                val suggested = "${AudioCodecHelper.sanitizeFileName(clean)}.pdf"
                                createPdfDocumentLauncher.launch(suggested)
                            },
                            onSelectPrimaryInstrument = viewModel::selectPrimaryMelodyInstrument,
                            onSelectSecondaryInstrument = viewModel::selectSecondaryMelodyInstrument,
                            onToggleDualKeyboard = viewModel::toggleDualKeyboard,
                            onToggleCollapseScoreInDual = viewModel::toggleCollapseScoreInDual,
                            onSetCollapseScoreInDual = viewModel::setCollapseScoreInDual,
                            onPrimaryScrollChange = viewModel::setPrimaryKeyboardScroll,
                            onSecondaryScrollChange = viewModel::setSecondaryKeyboardScroll,
                            onTimeSignatureChange = viewModel::setMelodyTimeSignature,
                            onInitialKeySignatureChange = viewModel::setMelodyInitialKeySignature,
                            onUpsertKeySignatureEvent = viewModel::upsertMelodyKeySignatureEvent,
                            onDeleteKeySignatureEvent = viewModel::deleteMelodyKeySignatureEvent,
                            onBpmChange = viewModel::setMelodyBpm,
                            onSwingPresetChange = viewModel::setMelodySwingPreset,
                            onSelectReverb = viewModel::setMelodyReverbPreset,
                            onTogglePrimarySustain = viewModel::togglePrimaryMelodySustain,
                            onToggleSecondarySustain = viewModel::toggleSecondaryMelodySustain,
                            onPrimarySustainDurationChange = viewModel::setPrimaryMelodySustainDuration,
                            onSecondarySustainDurationChange = viewModel::setSecondaryMelodySustainDuration,
                            onSelectInputMode = viewModel::setMelodyInputMode,
                            onSelectNoteDuration = viewModel::setMelodyNoteDuration,
                            onSelectActiveTuplet = viewModel::setMelodyActiveTuplet,
                            onToggleChordMode = viewModel::toggleMelodyChordMode,
                            onInsertRest = viewModel::insertMelodyRest,
                            onExtendPreviousNote = viewModel::extendPreviousMelodyNote,
                            onBackspaceNote = viewModel::backspaceMelodyNote,
                            onDeleteSelectedItem = viewModel::deleteSelectedMelodyItem,
                            onQuantizeNotes = viewModel::quantizeMelodyNotes,
                            onAddBars = viewModel::addMelodyBars,
                            onToggleMetronome = viewModel::toggleMelodyMetronome,
                            onToggleLoop = viewModel::toggleMelodyLoop,
                            onTogglePlayStop = viewModel::toggleMelodyPlayStop,
                            onPlayFromStart = viewModel::playMelodyFromStart,
                            onToggleRecordStop = viewModel::toggleMelodyRecordStop,
                            onScoreViewModeChange = viewModel::setMelodyScoreViewMode,
                            onToggleGrandStaff = viewModel::toggleMelodyGrandStaff,
                            onSetActiveStaff = viewModel::setMelodyActiveStaff,
                            onToggleDualKeyboardStaffRouting = viewModel::toggleDualKeyboardStaffRouting,
                            onToggleMinimizeKeyboardForScore = viewModel::toggleMinimizeKeyboardForScore,
                            onTapCursorTick = viewModel::setMelodyCursorTick,
                            onTapCursorTickAndStaff = viewModel::setMelodyCursorTickAndStaff,
                            onSelectNote = viewModel::selectMelodyNote,
                            onSelectAnnotation = viewModel::selectMelodyAnnotation,
                            onUpsertChordSymbol = viewModel::upsertMelodyChordSymbol,
                            onDeleteChordSymbol = viewModel::deleteMelodyChordSymbol,
                            onToggleShowChordSymbols = viewModel::toggleShowMelodyChordSymbols,
                            onToggleChordPlayback = viewModel::toggleMelodyChordPlayback,
                            onPreviewChordSymbol = viewModel::previewMelodyChordSymbol,
                            onTogglePhraseSlurMode = viewModel::toggleMelodyPhraseSlurMode,
                            onSlurSelectedOrExtendNext = viewModel::slurSelectedOrExtendNext,
                            onApplySlurSpan = viewModel::applyMelodySlurSpan,
                            onClearSelectedSlur = { viewModel.clearSelectedMelodySlur() },
                            onToggleArticulation = viewModel::toggleMelodyArticulation,
                            onToggleAccent = viewModel::toggleMelodyAccent,
                            onToggleFermata = viewModel::toggleMelodyFermata,
                            onUpsertDynamicMark = viewModel::upsertMelodyDynamicMark,
                            onDeleteDynamicMark = viewModel::deleteMelodyDynamicMark,
                            onUpsertHairpin = viewModel::upsertMelodyHairpin,
                            onDeleteHairpin = viewModel::deleteMelodyHairpin,
                            onUpsertTempoEvent = viewModel::upsertMelodyTempoEvent,
                            onDeleteTempoEvent = viewModel::deleteMelodyTempoEvent,
                            onNewBlankProject = viewModel::createNewBlankMelodyProject,
                            onSaveProject = viewModel::saveCurrentMelodyProject,
                            onLoadProject = viewModel::loadMelodyProject,
                            onRestoreAutoSaveDraft = viewModel::restoreMelodyAutoSaveDraft,
                            onRenameProject = viewModel::renameMelodyProject,
                            onDeleteProject = viewModel::deleteMelodyProject,
                            onShareProjectWeChat = { projectId, customTitle ->
                                viewModel.shareMelodyProjectFile(
                                    projectId = projectId,
                                    customTitle = customTitle,
                                    targetWeChatDirectly = true
                                )
                            },
                            onShareProjectSystem = { projectId, customTitle ->
                                viewModel.shareMelodyProjectFile(
                                    projectId = projectId,
                                    customTitle = customTitle,
                                    targetWeChatDirectly = false
                                )
                            },
                            onSaveProjectDownloads = viewModel::saveMelodyProjectFileToDownloads,
                            onSaveProjectCustomFolder = { projectId, customTitle ->
                                val clean = (customTitle ?: uiState.melody.scoreTitle).trim().ifBlank { "未命名乐谱" }
                                pendingMelodyProjectSaveSpec = projectId to clean
                                val suggested = "${AudioCodecHelper.sanitizeFileName(clean)}.stscore.json"
                                createMelodyProjectLauncher.launch(suggested)
                            },
                            onImportProjectClick = {
                                openMelodyProjectLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            onTouchGuardConfigChange = viewModel::setMelodyTouchGuardConfig,
                            onPianoKeyDown = viewModel::onMelodyPianoKeyDown,
                            onPianoKeyUp = viewModel::onMelodyPianoKeyUp,
                            modifier = Modifier.padding(bottom = bottomPadding)
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

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onAppPaused()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
        if (!isChangingConfigurations) {
            viewModel.pausePlayback()
        }
    }

    private fun isMelodyScoreProjectUri(uri: Uri, intentType: String?): Boolean {
        val raw = uri.toString().lowercase()
        if (raw.endsWith(".stscore.json") || raw.endsWith(".json")) return true
        if (intentType?.lowercase() == "application/json") return true
        return false
    }

    private fun handleIncomingAudioIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    if (isMelodyScoreProjectUri(uri, intent.type)) {
                        viewModel.importMelodyProjectFromUri(uri)
                    } else {
                        viewModel.importAudioUri(uri)
                    }
                }
            }
            Intent.ACTION_SEND -> {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                streamUri?.let { uri ->
                    if (isMelodyScoreProjectUri(uri, intent.type)) {
                        viewModel.importMelodyProjectFromUri(uri)
                    } else {
                        viewModel.importAudioUri(uri)
                    }
                }
            }
        }
    }
}
