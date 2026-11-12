package com.soundtracker.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.soundtracker.app.arranger.AppWorkspaceMode
import com.soundtracker.app.audio.NetEaseLocalTrack
import com.soundtracker.app.dsp.ExportFormat
import com.soundtracker.app.dsp.VocalEngineMode
import com.soundtracker.app.storage.EphemeralStorageInfo
import com.soundtracker.app.storage.StoredResourceCategory
import com.soundtracker.app.storage.StoredResourceItem
import com.soundtracker.app.update.AppUpdateUiState
import com.soundtracker.app.update.UpdateCheckStatus
import com.soundtracker.app.update.UpdateDownloadChannel
import com.soundtracker.app.ui.theme.AmberMastering
import com.soundtracker.app.ui.theme.CyanPrimary
import com.soundtracker.app.ui.theme.DangerRed
import com.soundtracker.app.ui.theme.EmeraldLossless
import com.soundtracker.app.ui.theme.OnAccentLight
import com.soundtracker.app.ui.theme.StudioBg
import com.soundtracker.app.ui.theme.StudioBorder
import com.soundtracker.app.ui.theme.StudioSurface
import com.soundtracker.app.ui.theme.StudioSurfaceElevated
import com.soundtracker.app.ui.theme.TextMuted
import com.soundtracker.app.ui.theme.TextPrimary
import com.soundtracker.app.ui.theme.TextSecondary
import com.soundtracker.app.ui.theme.VioletAccent
import com.soundtracker.app.ui.theme.WarmUmber
import com.soundtracker.app.ui.theme.WeChatGreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(
    uiState: StudioUiState,
    snackbarHostState: SnackbarHostState,
    onPickAudioClick: () -> Unit,
    onScanNetEaseClick: () -> Unit,
    onImportNetEaseTracks: (List<NetEaseLocalTrack>) -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onSelectTrack: (String) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onSkipMs: (Long) -> Unit,
    onToggleAbCompare: () -> Unit,
    onSelectTab: (StudioToolTab) -> Unit,
    onVocalEngineModeChange: (VocalEngineMode) -> Unit,
    onEnableDeepDenoiseChange: (Boolean) -> Unit,
    onKeepVocalChange: (Boolean) -> Unit,
    onRunVocalSeparation: () -> Unit,
    onPitchSemitonesChange: (Int) -> Unit,
    onPitchCentsChange: (Int) -> Unit,
    onTargetBpmChange: (Int) -> Unit,
    onAdjustTargetBpmBy: (Int) -> Unit,
    onCalibrateSourceBpm: (Int) -> Unit,
    onPreserveTimbreChange: (Boolean) -> Unit,
    onLivePitchPreviewChange: (Boolean) -> Unit,
    onRunPitchShift: () -> Unit,
    onVolumeGainChange: (Float) -> Unit,
    onLiveVolumePreviewChange: (Boolean) -> Unit,
    onRunVolumeBoost: () -> Unit,
    onExportFormatChange: (ExportFormat) -> Unit,
    onRenameTrack: (String, String) -> Unit,
    onShareWeChat: (String) -> Unit,
    onShareSystemMore: (String) -> Unit,
    onQuickSaveMusic: (String) -> Unit,
    onCustomSaveDocument: (String) -> Unit,
    onDeleteTrack: (String) -> Unit,
    onDeleteResourceItem: (String) -> Unit,
    onDeleteSelectedResources: (Set<String>) -> Unit,
    onClearWorkspace: () -> Unit,
    onRefreshStorage: () -> Unit,
    onCancelProcessing: () -> Unit,
    onWorkspaceModeChange: (AppWorkspaceMode) -> Unit,
    onSendTrackToArranger: (String) -> Unit,
    onCheckAppUpdate: () -> Unit,
    onSelectUpdateChannel: (UpdateDownloadChannel) -> Unit,
    onStartOrInstallUpdate: () -> Unit,
    onCancelUpdateDownload: () -> Unit,
    onOpenReleaseBrowser: () -> Unit,
    melodyContent: @Composable (bottomPadding: Dp) -> Unit,
    arrangerContent: @Composable (bottomPadding: Dp, onOpenNetEaseSheet: () -> Unit) -> Unit
) {
    var renameTargetTrack by remember { mutableStateOf<SessionTrack?>(null) }
    var saveTargetTrack by remember { mutableStateOf<SessionTrack?>(null) }
    var showStorageSheet by rememberSaveable { mutableStateOf(false) }
    var showNetEaseSheet by rememberSaveable { mutableStateOf(false) }
    var showUpdateSheet by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = uiState.workspaceMode != AppWorkspaceMode.SINGLE_TRACK) {
        onWorkspaceModeChange(AppWorkspaceMode.SINGLE_TRACK)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = StudioBg,
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState)
            },
            topBar = {
                if (uiState.workspaceMode == AppWorkspaceMode.SINGLE_TRACK) {
                    StudioTopBar(
                        ephemeralBytes = uiState.storageInfo.totalEphemeralBytes,
                        trackCount = uiState.tracks.size,
                        appUpdate = uiState.appUpdate,
                        onUpdatePillClick = {
                            if (uiState.appUpdate.checkStatus == UpdateCheckStatus.IDLE) {
                                onCheckAppUpdate()
                            }
                            showUpdateSheet = true
                        },
                        onStoragePillClick = {
                            onRefreshStorage()
                            showStorageSheet = true
                        },
                        onNetEaseClick = {
                            onScanNetEaseClick()
                            showNetEaseSheet = true
                        },
                        onImportClick = onPickAudioClick
                    )
                }
            },
            bottomBar = {
                StudioWorkspaceBottomBar(
                    currentMode = uiState.workspaceMode,
                    melodyNoteCount = uiState.melody.notes.size,
                    arrangerClipCount = uiState.arranger.totalClipCount,
                    onSelectMode = onWorkspaceModeChange
                )
            }
        ) { innerPadding ->
            when (uiState.workspaceMode) {
                AppWorkspaceMode.MULTI_TRACK_ARRANGER -> {
                    arrangerContent(innerPadding.calculateBottomPadding()) {
                        onScanNetEaseClick()
                        showNetEaseSheet = true
                    }
                }
                AppWorkspaceMode.MELODY_STUDIO -> {
                    melodyContent(innerPadding.calculateBottomPadding())
                }
                AppWorkspaceMode.SINGLE_TRACK -> {
                    val activeTrack = uiState.selectedTrack

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        val isWideTablet = maxWidth >= 840.dp && maxHeight >= 500.dp
                        val isMediumTablet = maxWidth >= 600.dp && !isWideTablet

                        if (isWideTablet && activeTrack != null) {
                            // Two-Pane Studio Workbench for Wide Android Tablets (Landscape / Large Foldable):
                            // Left Pane: Active Track Player + Workspace Track Tray
                            // Right Pane: Studio DSP Workbench (Vocal Separation / Pitch & Tempo / Maximizer)
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Row(
                                    modifier = Modifier
                                        .widthIn(max = 1360.dp)
                                        .fillMaxSize()
                                        .padding(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                                ) {
                                    LazyColumn(
                                        modifier = Modifier
                                            .weight(0.46f)
                                            .fillMaxHeight(),
                                        contentPadding = PaddingValues(vertical = 14.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        item {
                                            ActiveTrackDeckCard(
                                                track = activeTrack,
                                                hasOriginForAb = uiState.originComparisonTrack != null,
                                                isComparingOriginal = uiState.isComparingOriginal,
                                                livePreviewBadgeText = uiState.livePreviewSummaryLabel,
                                                isPlaying = uiState.isPlaying,
                                                positionMs = uiState.playbackPositionMs,
                                                durationMs = uiState.playbackDurationMs.takeIf { it > 0 }
                                                    ?: (activeTrack.metrics.durationSec * 1000f).toLong(),
                                                exportFormat = uiState.exportFormat,
                                                onExportFormatChange = onExportFormatChange,
                                                onTogglePlayPause = onTogglePlayPause,
                                                onSeekFraction = onSeekFraction,
                                                onSkipMs = onSkipMs,
                                                onToggleAbCompare = onToggleAbCompare,
                                                onRenameClick = { renameTargetTrack = activeTrack },
                                                onSendToArrangerClick = { onSendTrackToArranger(activeTrack.id) },
                                                onShareWeChatClick = { onShareWeChat(activeTrack.id) },
                                                onShareMoreClick = { onShareSystemMore(activeTrack.id) },
                                                onSaveAsClick = { saveTargetTrack = activeTrack }
                                            )
                                        }

                                        if (uiState.tracks.isNotEmpty()) {
                                            item {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 4.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column {
                                                        Text(
                                                            text = "工作台音轨 (${uiState.tracks.size})",
                                                            style = MaterialTheme.typography.titleMedium,
                                                            color = TextPrimary
                                                        )
                                                        Text(
                                                            text = "点音轨试听处理 · 点剪刀图标可直接送入多轨剪辑",
                                                            fontSize = 11.sp,
                                                            color = TextMuted
                                                        )
                                                    }
                                                    TextButton(onClick = {
                                                        onRefreshStorage()
                                                        showStorageSheet = true
                                                    }) {
                                                        Text("资源管理", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                                                    }
                                                }
                                            }

                                            items(items = uiState.tracks, key = { it.id }) { item ->
                                                SessionTrackItemCard(
                                                    track = item,
                                                    isSelected = item.id == activeTrack.id,
                                                    onClick = { onSelectTrack(item.id) },
                                                    onSendToArrangerClick = { onSendTrackToArranger(item.id) },
                                                    onRenameClick = { renameTargetTrack = item },
                                                    onShareWeChatClick = { onShareWeChat(item.id) },
                                                    onSaveAsClick = { saveTargetTrack = item },
                                                    onDeleteClick = { onDeleteTrack(item.id) }
                                                )
                                            }
                                        }

                                        item {
                                            Spacer(modifier = Modifier.height(16.dp))
                                        }
                                    }

                                    LazyColumn(
                                        modifier = Modifier
                                            .weight(0.54f)
                                            .fillMaxHeight(),
                                        contentPadding = PaddingValues(vertical = 14.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        item {
                                            StudioDspWorkbenchCard(
                                                uiState = uiState,
                                                activeTrack = activeTrack,
                                                onSelectTab = onSelectTab,
                                                onTogglePlayPause = onTogglePlayPause,
                                                onVocalEngineModeChange = onVocalEngineModeChange,
                                                onEnableDeepDenoiseChange = onEnableDeepDenoiseChange,
                                                onKeepVocalChange = onKeepVocalChange,
                                                onRunVocalSeparation = onRunVocalSeparation,
                                                onPitchSemitonesChange = onPitchSemitonesChange,
                                                onPitchCentsChange = onPitchCentsChange,
                                                onTargetBpmChange = onTargetBpmChange,
                                                onAdjustTargetBpmBy = onAdjustTargetBpmBy,
                                                onCalibrateSourceBpm = onCalibrateSourceBpm,
                                                onPreserveTimbreChange = onPreserveTimbreChange,
                                                onLivePitchPreviewChange = onLivePitchPreviewChange,
                                                onRunPitchShift = onRunPitchShift,
                                                onVolumeGainChange = onVolumeGainChange,
                                                onLiveVolumePreviewChange = onLiveVolumePreviewChange,
                                                onRunVolumeBoost = onRunVolumeBoost
                                            )
                                        }

                                        item {
                                            Spacer(modifier = Modifier.height(16.dp))
                                        }
                                    }
                                }
                            }
                        } else {
                            // Single-column layout for Phones, Portrait Tablets, or Empty Hero State on Tablets
                            val columnMaxW = when {
                                isWideTablet -> 680.dp
                                isMediumTablet -> 700.dp
                                else -> Dp.Unspecified
                            }
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                LazyColumn(
                                    modifier = (if (columnMaxW != Dp.Unspecified) Modifier.widthIn(max = columnMaxW) else Modifier)
                                        .fillMaxSize(),
                                    contentPadding = PaddingValues(
                                        horizontal = if (isWideTablet || isMediumTablet) 20.dp else 16.dp,
                                        vertical = 12.dp
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    if (activeTrack == null) {
                                        item {
                                            EmptyStudioHeroCard(
                                                onNetEaseImportClick = {
                                                    onScanNetEaseClick()
                                                    showNetEaseSheet = true
                                                },
                                                onImportClick = onPickAudioClick,
                                                onOpenMelodyClick = {
                                                    onWorkspaceModeChange(AppWorkspaceMode.MELODY_STUDIO)
                                                },
                                                onOpenArrangerClick = {
                                                    onWorkspaceModeChange(AppWorkspaceMode.MULTI_TRACK_ARRANGER)
                                                }
                                            )
                                        }
                                    } else {
                                        item {
                                            ActiveTrackDeckCard(
                                                track = activeTrack,
                                                hasOriginForAb = uiState.originComparisonTrack != null,
                                                isComparingOriginal = uiState.isComparingOriginal,
                                                livePreviewBadgeText = uiState.livePreviewSummaryLabel,
                                                isPlaying = uiState.isPlaying,
                                                positionMs = uiState.playbackPositionMs,
                                                durationMs = uiState.playbackDurationMs.takeIf { it > 0 }
                                                    ?: (activeTrack.metrics.durationSec * 1000f).toLong(),
                                                exportFormat = uiState.exportFormat,
                                                onExportFormatChange = onExportFormatChange,
                                                onTogglePlayPause = onTogglePlayPause,
                                                onSeekFraction = onSeekFraction,
                                                onSkipMs = onSkipMs,
                                                onToggleAbCompare = onToggleAbCompare,
                                                onRenameClick = { renameTargetTrack = activeTrack },
                                                onSendToArrangerClick = { onSendTrackToArranger(activeTrack.id) },
                                                onShareWeChatClick = { onShareWeChat(activeTrack.id) },
                                                onShareMoreClick = { onShareSystemMore(activeTrack.id) },
                                                onSaveAsClick = { saveTargetTrack = activeTrack }
                                            )
                                        }

                                        item {
                                            StudioDspWorkbenchCard(
                                                uiState = uiState,
                                                activeTrack = activeTrack,
                                                onSelectTab = onSelectTab,
                                                onTogglePlayPause = onTogglePlayPause,
                                                onVocalEngineModeChange = onVocalEngineModeChange,
                                                onEnableDeepDenoiseChange = onEnableDeepDenoiseChange,
                                                onKeepVocalChange = onKeepVocalChange,
                                                onRunVocalSeparation = onRunVocalSeparation,
                                                onPitchSemitonesChange = onPitchSemitonesChange,
                                                onPitchCentsChange = onPitchCentsChange,
                                                onTargetBpmChange = onTargetBpmChange,
                                                onAdjustTargetBpmBy = onAdjustTargetBpmBy,
                                                onCalibrateSourceBpm = onCalibrateSourceBpm,
                                                onPreserveTimbreChange = onPreserveTimbreChange,
                                                onLivePitchPreviewChange = onLivePitchPreviewChange,
                                                onRunPitchShift = onRunPitchShift,
                                                onVolumeGainChange = onVolumeGainChange,
                                                onLiveVolumePreviewChange = onLiveVolumePreviewChange,
                                                onRunVolumeBoost = onRunVolumeBoost
                                            )
                                        }
                                    }

                                    if (uiState.tracks.isNotEmpty()) {
                                        item {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        text = "工作台音轨 (${uiState.tracks.size})",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = TextPrimary
                                                    )
                                                    Text(
                                                        text = "点音轨试听处理 · 点剪刀图标可直接送入多轨剪辑",
                                                        fontSize = 11.sp,
                                                        color = TextMuted
                                                    )
                                                }
                                                TextButton(onClick = {
                                                    onRefreshStorage()
                                                    showStorageSheet = true
                                                }) {
                                                    Text("资源管理", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                                                }
                                            }
                                        }

                                        items(items = uiState.tracks, key = { it.id }) { item ->
                                            SessionTrackItemCard(
                                                track = item,
                                                isSelected = item.id == activeTrack?.id,
                                                onClick = { onSelectTrack(item.id) },
                                                onSendToArrangerClick = { onSendTrackToArranger(item.id) },
                                                onRenameClick = { renameTargetTrack = item },
                                                onShareWeChatClick = { onShareWeChat(item.id) },
                                                onSaveAsClick = { saveTargetTrack = item },
                                                onDeleteClick = { onDeleteTrack(item.id) }
                                            )
                                        }
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Rename Dialog
        renameTargetTrack?.let { target ->
            RenameTrackDialog(
                track = target,
                exportExtension = uiState.exportFormat.extension,
                onDismiss = { renameTargetTrack = null },
                onConfirm = { newName ->
                    onRenameTrack(target.id, newName)
                    renameTargetTrack = null
                }
            )
        }

        // Save As Local Sheet
        saveTargetTrack?.let { target ->
            SaveAsBottomSheet(
                track = target,
                currentFormat = uiState.exportFormat,
                onFormatChange = onExportFormatChange,
                onQuickSaveMusic = {
                    saveTargetTrack = null
                    onQuickSaveMusic(target.id)
                },
                onCustomFolderSave = {
                    val id = target.id
                    saveTargetTrack = null
                    onCustomSaveDocument(id)
                },
                onDismiss = { saveTargetTrack = null }
            )
        }

        // Interactive Storage Resource Manager Sheet
        if (showStorageSheet) {
            StorageResourceManagerSheet(
                storageInfo = uiState.storageInfo,
                onDeleteSingleItem = onDeleteResourceItem,
                onDeleteSelectedItems = onDeleteSelectedResources,
                onClearAll = {
                    onClearWorkspace()
                    showStorageSheet = false
                },
                onDismiss = { showStorageSheet = false }
            )
        }

        // NetEase Cloud Music Import Sheet (NCM -> FLAC / MP3)
        if (showNetEaseSheet) {
            NetEaseCloudMusicImportSheet(
                tracks = uiState.netEaseTracks,
                isScanning = uiState.isScanningNetEase,
                hasStorageAccess = uiState.hasNetEaseStorageAccess,
                onRefresh = onScanNetEaseClick,
                onRequestStorageAccess = onRequestAllFilesAccess,
                onPickManualNcmFile = {
                    showNetEaseSheet = false
                    onPickAudioClick()
                },
                onImportTracks = { selected ->
                    showNetEaseSheet = false
                    onImportNetEaseTracks(selected)
                },
                onDismiss = { showNetEaseSheet = false }
            )
        }

        // GitHub Releases OTA Version Update Sheet
        if (showUpdateSheet) {
            AppUpdateBottomSheet(
                appUpdate = uiState.appUpdate,
                onCheckNow = onCheckAppUpdate,
                onSelectChannel = onSelectUpdateChannel,
                onStartOrInstall = onStartOrInstallUpdate,
                onCancelDownload = onCancelUpdateDownload,
                onOpenInBrowser = onOpenReleaseBrowser,
                onDismiss = { showUpdateSheet = false }
            )
        }

        // Processing Progress Modal
        uiState.processingTask?.let { task ->
            ProcessingProgressDialog(
                task = task,
                onCancel = onCancelProcessing
            )
        }
    }
}

@Composable
private fun StudioWorkspaceBottomBar(
    currentMode: AppWorkspaceMode,
    melodyNoteCount: Int,
    arrangerClipCount: Int,
    onSelectMode: (AppWorkspaceMode) -> Unit
) {
    Surface(
        color = StudioSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            HorizontalDivider(color = StudioBorder)
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 580.dp)
                        .fillMaxWidth()
                        .height(54.dp)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WorkspaceModeTabItem(
                        label = "单曲处理",
                        icon = Icons.Rounded.Tune,
                        badgeText = null,
                        selected = currentMode == AppWorkspaceMode.SINGLE_TRACK,
                        onClick = { onSelectMode(AppWorkspaceMode.SINGLE_TRACK) },
                        modifier = Modifier.weight(1f)
                    )
                    WorkspaceModeTabItem(
                        label = "乐器打谱",
                        icon = Icons.Rounded.Piano,
                        badgeText = melodyNoteCount.takeIf { it > 0 }?.toString(),
                        selected = currentMode == AppWorkspaceMode.MELODY_STUDIO,
                        onClick = { onSelectMode(AppWorkspaceMode.MELODY_STUDIO) },
                        modifier = Modifier.weight(1f)
                    )
                    WorkspaceModeTabItem(
                        label = "多轨剪辑",
                        icon = Icons.Rounded.ContentCut,
                        badgeText = arrangerClipCount.takeIf { it > 0 }?.toString(),
                        selected = currentMode == AppWorkspaceMode.MULTI_TRACK_ARRANGER,
                        onClick = { onSelectMode(AppWorkspaceMode.MULTI_TRACK_ARRANGER) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceModeTabItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badgeText: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(11.dp)
    Surface(
        shape = shape,
        color = if (selected) CyanPrimary else StudioSurfaceElevated.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, if (selected) CyanPrimary else StudioBorder),
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) OnAccentLight else TextSecondary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) OnAccentLight else TextSecondary,
                maxLines = 1
            )
            if (badgeText != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (selected) OnAccentLight.copy(alpha = 0.22f) else CyanPrimary.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) OnAccentLight else CyanPrimary,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioTopBar(
    ephemeralBytes: Long,
    trackCount: Int,
    appUpdate: AppUpdateUiState,
    onUpdatePillClick: () -> Unit,
    onStoragePillClick: () -> Unit,
    onNetEaseClick: () -> Unit,
    onImportClick: () -> Unit
) {
    Surface(
        color = StudioBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            val inlineTabletImports = maxWidth >= 740.dp && trackCount > 0
            val horizontalPad = if (maxWidth >= 600.dp) 20.dp else 16.dp

            Column(modifier = Modifier.fillMaxWidth()) {
                // Row 1: App Title + (Inline Import Buttons on Wide Tablets) + Update Pill + Storage Pill
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPad, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CyanPrimary.copy(alpha = 0.14f))
                                .border(1.dp, CyanPrimary.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "SoundTracker",
                                style = MaterialTheme.typography.headlineSmall,
                                color = TextPrimary,
                                maxLines = 1
                            )
                            Text(
                                text = "伴奏 · 移调 · 打谱 · 多轨剪辑",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (inlineTabletImports) {
                            Surface(
                                onClick = onNetEaseClick,
                                shape = RoundedCornerShape(10.dp),
                                color = CyanPrimary.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.40f)),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CloudDownload,
                                        contentDescription = "从网易云音乐导入",
                                        tint = CyanPrimary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "从网易云导入",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = CyanPrimary,
                                        maxLines = 1
                                    )
                                }
                            }

                            Surface(
                                onClick = onImportClick,
                                shape = RoundedCornerShape(10.dp),
                                color = VioletAccent,
                                border = BorderStroke(1.dp, VioletAccent),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = null,
                                        tint = OnAccentLight,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "导入本地音频",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = OnAccentLight,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // Version / GitHub Release Update Pill (fixed 30.dp height, zero layout shift)
                        val updShape = RoundedCornerShape(16.dp)
                        val hasNew = appUpdate.hasUpdate
                        val dlPct = if (appUpdate.isDownloading && appUpdate.totalBytes > 0L) {
                            ((appUpdate.downloadedBytes * 100L) / appUpdate.totalBytes).toInt().coerceIn(0, 99)
                        } else {
                            null
                        }
                        Surface(
                            shape = updShape,
                            color = when {
                                appUpdate.isDownloading -> CyanPrimary.copy(alpha = 0.15f)
                                hasNew -> CyanPrimary
                                else -> StudioSurface
                            },
                            border = BorderStroke(
                                1.dp,
                                if (hasNew || appUpdate.isDownloading) CyanPrimary else StudioBorder
                            ),
                            modifier = Modifier
                                .height(30.dp)
                                .clip(updShape)
                                .clickable(onClick = onUpdatePillClick)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SystemUpdateAlt,
                                    contentDescription = "检查版本更新",
                                    tint = when {
                                        appUpdate.isDownloading -> CyanPrimary
                                        hasNew -> OnAccentLight
                                        else -> TextSecondary
                                    },
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = when {
                                        dlPct != null -> "$dlPct%"
                                        hasNew -> "新版"
                                        else -> "v${appUpdate.currentVersionName}"
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = if (hasNew) FontFamily.Default else FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        appUpdate.isDownloading -> CyanPrimary
                                        hasNew -> OnAccentLight
                                        else -> TextPrimary
                                    },
                                    maxLines = 1
                                )
                            }
                        }

                        // Broom + Storage Size Button -> Opens Resource Manager List
                        val broomShape = RoundedCornerShape(16.dp)
                        Surface(
                            shape = broomShape,
                            color = StudioSurface,
                            border = BorderStroke(1.dp, StudioBorder),
                            modifier = Modifier
                                .height(30.dp)
                                .clip(broomShape)
                                .clickable(onClick = onStoragePillClick)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CleaningServices,
                                    contentDescription = "存储资源管理",
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = formatBytesShort(ephemeralBytes),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Row 2: Quick Import Action Bar on Phones / Portrait Narrow Screens when tracks are loaded
                if (trackCount > 0 && !inlineTabletImports) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = horizontalPad, end = horizontalPad, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = onNetEaseClick,
                            shape = RoundedCornerShape(10.dp),
                            color = CyanPrimary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.40f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDownload,
                                    contentDescription = "从网易云音乐导入",
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "从网易云导入",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CyanPrimary,
                                    maxLines = 1
                                )
                            }
                        }

                        Surface(
                            onClick = onImportClick,
                            shape = RoundedCornerShape(10.dp),
                            color = VioletAccent,
                            border = BorderStroke(1.dp, VioletAccent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = OnAccentLight,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "导入本地音频",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OnAccentLight,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = StudioBorder.copy(alpha = 0.7f), thickness = 0.75.dp)
            }
        }
    }
}

@Composable
private fun EmptyStudioHeroCard(
    onNetEaseImportClick: () -> Unit,
    onImportClick: () -> Unit,
    onOpenMelodyClick: () -> Unit,
    onOpenArrangerClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(CyanPrimary.copy(alpha = 0.12f))
                    .border(1.dp, CyanPrimary.copy(alpha = 0.32f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.LibraryMusic,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "导入音乐开始处理",
                fontFamily = FontFamily.Serif,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "支持从网易云音乐导入已下载歌曲（NCM 自动转换为 FLAC 或 MP3）\n也支持直接选择手机中的 MP3、FLAC、M4A、WAV 或视频文件",
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = onNetEaseImportClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 13.dp)
            ) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("从网易云音乐导入 (NCM → FLAC/MP3)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onImportClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StudioBorder),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("选择手机本地音频 / 视频文件", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = StudioBorder)
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FeaturePillBadge("提取伴奏", CyanPrimary)
                FeaturePillBadge("移调变速", WarmUmber)
                FeaturePillBadge("乐器打谱", AmberMastering, onClick = onOpenMelodyClick)
                FeaturePillBadge("多轨剪辑", VioletAccent, onClick = onOpenArrangerClick)
            }
        }
    }
}

@Composable
private fun FeaturePillBadge(
    text: String,
    accent: Color,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape = shape,
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
        modifier = if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveTrackDeckCard(
    track: SessionTrack,
    hasOriginForAb: Boolean,
    isComparingOriginal: Boolean,
    livePreviewBadgeText: String?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    exportFormat: ExportFormat,
    onExportFormatChange: (ExportFormat) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onSkipMs: (Long) -> Unit,
    onToggleAbCompare: () -> Unit,
    onRenameClick: () -> Unit,
    onSendToArrangerClick: () -> Unit,
    onShareWeChatClick: () -> Unit,
    onShareMoreClick: () -> Unit,
    onSaveAsClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isComparingOriginal || livePreviewBadgeText != null) CyanPrimary else StudioBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Title + Rename row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isComparingOriginal) WarmUmber.copy(alpha = 0.15f) else CyanPrimary.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = if (isComparingOriginal) "A/B 正在试听原曲" else track.badge,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isComparingOriginal) WarmUmber else CyanPrimary,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = StudioSurfaceElevated
                        ) {
                            Text(
                                text = "${track.metrics.detectedBpm.roundToInt()} BPM",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        if (livePreviewBadgeText != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = CyanPrimary
                            ) {
                                Text(
                                    text = livePreviewBadgeText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OnAccentLight,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (track.savedLocalPath != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = EmeraldLossless.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "已另存本地",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = EmeraldLossless,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    val trackFileExt = track.audioFile.extension.lowercase().ifBlank { exportFormat.extension }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onRenameClick)
                    ) {
                        Text(
                            text = "${track.displayName}.$trackFileExt",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.DriveFileRenameOutline,
                            contentDescription = "重命名",
                            tint = CyanPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Text(
                        text = track.detailNote,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val sendClipShape = RoundedCornerShape(10.dp)
                Surface(
                    shape = sendClipShape,
                    color = CyanPrimary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .height(32.dp)
                        .clip(sendClipShape)
                        .clickable(onClick = onSendToArrangerClick)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 9.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCut,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "送去剪辑",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CyanPrimary,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Interactive Seekable Acoustic Waveform
            val progressFraction = if (durationMs > 0L) {
                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

            WaveformSeekBar(
                peaks = track.metrics.waveformPeaks,
                progress = progressFraction,
                activeColor = if (isComparingOriginal) WarmUmber else CyanPrimary,
                onSeek = onSeekFraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatDurationMs(positionMs),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
                Text(
                    formatDurationMs(durationMs),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Playback Transport + A/B Compare
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(onClick = { onSkipMs(-5000L) }) {
                        Icon(Icons.Rounded.Replay5, contentDescription = "后退5秒", tint = TextSecondary)
                    }

                    Button(
                        onClick = onTogglePlayPause,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isComparingOriginal) WarmUmber else VioletAccent,
                            contentColor = OnAccentLight
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isPlaying) "暂停" else "试听",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }

                    IconButton(onClick = { onSkipMs(5000L) }) {
                        Icon(Icons.Rounded.Forward5, contentDescription = "快进5秒", tint = TextSecondary)
                    }
                }

                if (hasOriginForAb) {
                    FilledTonalButton(
                        onClick = onToggleAbCompare,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (isComparingOriginal) CyanPrimary else StudioSurfaceElevated,
                            contentColor = if (isComparingOriginal) OnAccentLight else TextPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.CompareArrows,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isComparingOriginal) "切回成品" else "对比原曲",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = StudioBorder)
            Spacer(modifier = Modifier.height(10.dp))

            // Export format compact pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("导出格式:", fontSize = 11.sp, color = TextMuted)
                ExportFormat.entries.forEach { fmt ->
                    val selected = fmt == exportFormat
                    FilterChip(
                        selected = selected,
                        onClick = { onExportFormatChange(fmt) },
                        label = {
                            Text(
                                text = when (fmt) {
                                    ExportFormat.MP3_320 -> "MP3 320k (推荐)"
                                    ExportFormat.M4A_256 -> "M4A 256k"
                                    ExportFormat.WAV_16 -> "WAV 无损"
                                },
                                fontSize = 11.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanPrimary.copy(alpha = 0.16f),
                            selectedLabelColor = CyanPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Primary Delivery Actions: Share WeChat + More Share + Save As Local
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onShareWeChatClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WeChatGreen,
                        contentColor = OnAccentLight
                    ),
                    contentPadding = PaddingValues(vertical = 11.dp, horizontal = 10.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("分享到微信", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = onShareMoreClick,
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, StudioBorder),
                    contentPadding = PaddingValues(vertical = 11.dp, horizontal = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "更多分享",
                        tint = TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Button(
                    onClick = onSaveAsClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = OnAccentLight
                    ),
                    contentPadding = PaddingValues(vertical = 11.dp, horizontal = 10.dp)
                ) {
                    Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("另存为本地", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun WaveformSeekBar(
    peaks: List<Float>,
    progress: Float,
    activeColor: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val displayPeaks = remember(peaks) {
        if (peaks.isEmpty()) List(120) { 0.25f } else peaks
    }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(StudioSurfaceElevated)
            .border(1.dp, StudioBorder, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    if (size.width > 0) {
                        onSeek((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        val barCount = displayPeaks.size
        val step = size.width / barCount.toFloat()
        val barWidth = (step * 0.64f).coerceAtLeast(1.5f)
        val centerY = size.height / 2f
        val playheadX = progress * size.width

        for (i in 0 until barCount) {
            val x = i * step
            val amp = displayPeaks[i].coerceIn(0.06f, 1.0f)
            val barHeight = (amp * size.height * 0.90f).coerceAtLeast(3f)
            val isPlayed = x <= playheadX
            drawRoundRect(
                color = if (isPlayed) activeColor else TextMuted.copy(alpha = 0.35f),
                topLeft = Offset(x, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }

        // Editorial charcoal playhead indicator line
        drawLine(
            color = TextPrimary,
            start = Offset(playheadX.coerceIn(0f, size.width), 0f),
            end = Offset(playheadX.coerceIn(0f, size.width), size.height),
            strokeWidth = 2.dp.toPx()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioDspWorkbenchCard(
    uiState: StudioUiState,
    activeTrack: SessionTrack,
    onSelectTab: (StudioToolTab) -> Unit,
    onTogglePlayPause: () -> Unit,
    onVocalEngineModeChange: (VocalEngineMode) -> Unit,
    onEnableDeepDenoiseChange: (Boolean) -> Unit,
    onKeepVocalChange: (Boolean) -> Unit,
    onRunVocalSeparation: () -> Unit,
    onPitchSemitonesChange: (Int) -> Unit,
    onPitchCentsChange: (Int) -> Unit,
    onTargetBpmChange: (Int) -> Unit,
    onAdjustTargetBpmBy: (Int) -> Unit,
    onCalibrateSourceBpm: (Int) -> Unit,
    onPreserveTimbreChange: (Boolean) -> Unit,
    onLivePitchPreviewChange: (Boolean) -> Unit,
    onRunPitchShift: () -> Unit,
    onVolumeGainChange: (Float) -> Unit,
    onLiveVolumePreviewChange: (Boolean) -> Unit,
    onRunVolumeBoost: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Segmented Tab Selector (Claude Warm Stone / Ivory pill switcher)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StudioSurfaceElevated)
                    .border(1.dp, StudioBorder, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StudioToolTab.entries.forEach { tab ->
                    val selected = uiState.activeTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (selected) StudioSurface else Color.Transparent
                            )
                            .border(
                                width = if (selected) 1.dp else 0.dp,
                                color = if (selected) StudioBorder else Color.Transparent,
                                shape = RoundedCornerShape(9.dp)
                            )
                            .clickable { onSelectTab(tab) }
                            .padding(vertical = 9.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.title,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) CyanPrimary else TextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (uiState.activeTab) {
                StudioToolTab.VOCAL_REMOVE -> {
                    VocalRemovalPanel(
                        trackName = activeTrack.displayName,
                        selectedMode = uiState.vocalEngineMode,
                        enableDeepDenoise = uiState.enableDeepDenoise,
                        keepVocalStem = uiState.keepVocalStemToo,
                        onModeChange = onVocalEngineModeChange,
                        onEnableDeepDenoiseChange = onEnableDeepDenoiseChange,
                        onKeepVocalChange = onKeepVocalChange,
                        onRun = onRunVocalSeparation
                    )
                }

                StudioToolTab.PITCH_SHIFT -> {
                    PitchShiftAndTempoPanel(
                        isPlaying = uiState.isPlaying,
                        isComparingOriginal = uiState.isComparingOriginal,
                        livePreviewEnabled = uiState.livePitchTempoPreview,
                        semitones = uiState.pitchSemitones,
                        cents = uiState.pitchCents,
                        sourceBpm = uiState.sourceBpm,
                        targetBpm = uiState.targetBpm,
                        preserveTimbre = uiState.preserveTimbre,
                        onTogglePlayPause = onTogglePlayPause,
                        onLivePreviewChange = onLivePitchPreviewChange,
                        onSemitonesChange = onPitchSemitonesChange,
                        onCentsChange = onPitchCentsChange,
                        onTargetBpmChange = onTargetBpmChange,
                        onAdjustTargetBpmBy = onAdjustTargetBpmBy,
                        onCalibrateSourceBpm = onCalibrateSourceBpm,
                        onPreserveTimbreChange = onPreserveTimbreChange,
                        onRun = onRunPitchShift
                    )
                }

                StudioToolTab.VOLUME_BOOST -> {
                    VolumeBoostPanel(
                        track = activeTrack,
                        isPlaying = uiState.isPlaying,
                        isComparingOriginal = uiState.isComparingOriginal,
                        livePreviewEnabled = uiState.liveVolumeBoostPreview,
                        gainDb = uiState.volumeGainDb,
                        onTogglePlayPause = onTogglePlayPause,
                        onLivePreviewChange = onLiveVolumePreviewChange,
                        onGainChange = onVolumeGainChange,
                        onRun = onRunVolumeBoost
                    )
                }
            }
        }
    }
}

@Composable
private fun VocalRemovalPanel(
    trackName: String,
    selectedMode: VocalEngineMode,
    enableDeepDenoise: Boolean,
    keepVocalStem: Boolean,
    onModeChange: (VocalEngineMode) -> Unit,
    onEnableDeepDenoiseChange: (Boolean) -> Unit,
    onKeepVocalChange: (Boolean) -> Unit,
    onRun: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = CyanPrimary)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    "提取伴奏（去除人声）",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "根据歌曲类型选择分离模式，将主唱和人声从音乐中去除",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        // 3 Separation Mode Cards
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VocalEngineMode.entries.forEach { mode ->
                val isSelected = mode == selectedMode
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) CyanPrimary.copy(alpha = 0.08f) else StudioSurfaceElevated.copy(alpha = 0.65f),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) CyanPrimary else StudioBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onModeChange(mode) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isSelected) CyanPrimary else TextMuted.copy(alpha = 0.55f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = mode.title,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) CyanPrimary.copy(alpha = 0.16f) else StudioSurface
                            ) {
                                Text(
                                    text = mode.badge,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) CyanPrimary else TextMuted,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = mode.subtitle,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        val denoiseSupported = selectedMode != VocalEngineMode.HTDEMUCS_FT
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = StudioSurfaceElevated.copy(alpha = if (denoiseSupported) 0.65f else 0.38f),
            border = BorderStroke(1.dp, StudioBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Text(
                        text = "二次降噪处理",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (denoiseSupported) TextPrimary else TextMuted,
                        maxLines = 1
                    )
                    Text(
                        text = when {
                            !denoiseSupported -> "「乐器音质优先」模式无需二次降噪，切换其他模式时可开启"
                            enableDeepDenoise -> "已开启：将对音频处理两遍以减少伴奏底噪（耗时约增加一倍）"
                            else -> "若分离后安静段落有沙沙底噪可开启，处理耗时约增加一倍"
                        },
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = TextMuted,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = denoiseSupported && enableDeepDenoise,
                    onCheckedChange = onEnableDeepDenoiseChange,
                    enabled = denoiseSupported,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnAccentLight,
                        checkedTrackColor = CyanPrimary
                    )
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = StudioSurfaceElevated.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, StudioBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Text(
                        text = "同时保存清唱人声",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = if (keepVocalStem) "已开启：同时生成伴奏和纯人声两条音轨"
                        else "默认关闭：只生成伴奏音轨",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = TextMuted,
                        minLines = 1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = keepVocalStem,
                    onCheckedChange = onKeepVocalChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnAccentLight,
                        checkedTrackColor = CyanPrimary
                    )
                )
            }
        }

        Button(
            onClick = onRun,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanPrimary,
                contentColor = OnAccentLight
            ),
            contentPadding = PaddingValues(vertical = 13.dp)
        ) {
            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (keepVocalStem) "开始提取伴奏与人声" else "开始提取伴奏",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PitchShiftAndTempoPanel(
    isPlaying: Boolean,
    isComparingOriginal: Boolean,
    livePreviewEnabled: Boolean,
    semitones: Int,
    cents: Int,
    sourceBpm: Int,
    targetBpm: Int,
    preserveTimbre: Boolean,
    onTogglePlayPause: () -> Unit,
    onLivePreviewChange: (Boolean) -> Unit,
    onSemitonesChange: (Int) -> Unit,
    onCentsChange: (Int) -> Unit,
    onTargetBpmChange: (Int) -> Unit,
    onAdjustTargetBpmBy: (Int) -> Unit,
    onCalibrateSourceBpm: (Int) -> Unit,
    onPreserveTimbreChange: (Boolean) -> Unit,
    onRun: () -> Unit
) {
    val hasPitchChange = semitones != 0 || cents != 0
    val hasBpmChange = targetBpm != sourceBpm
    val speedRatio = if (sourceBpm > 0) targetBpm.toFloat() / sourceBpm.toFloat() else 1.0f
    val deltaBpm = targetBpm - sourceBpm
    val isLiveAuditioningModified = livePreviewEnabled && !isComparingOriginal && (hasPitchChange || hasBpmChange)

    val hasAnyChange = hasPitchChange || hasBpmChange

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = CyanPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "升降调与变速",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = "可边播边调试听，调好后生成新音轨",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        minLines = 1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            val resetShape = RoundedCornerShape(8.dp)
            Surface(
                shape = resetShape,
                color = if (hasAnyChange) CyanPrimary.copy(alpha = 0.12f) else StudioSurfaceElevated.copy(alpha = 0.5f),
                border = BorderStroke(
                    1.dp,
                    if (hasAnyChange) CyanPrimary.copy(alpha = 0.45f) else StudioBorder.copy(alpha = 0.6f)
                ),
                modifier = Modifier
                    .clip(resetShape)
                    .clickable(enabled = hasAnyChange) {
                        onSemitonesChange(0)
                        onCentsChange(0)
                        onTargetBpmChange(sourceBpm)
                    }
            ) {
                Text(
                    text = "全部复位",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hasAnyChange) CyanPrimary else TextMuted.copy(alpha = 0.45f),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
        }

        // ====================================================================
        // Real-Time Audition ("边听边调") Control Bar — Locked Constant Height
        // ====================================================================
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isLiveAuditioningModified) CyanPrimary.copy(alpha = 0.08f) else StudioSurfaceElevated.copy(alpha = 0.65f),
            border = BorderStroke(
                width = 1.dp,
                color = if (isLiveAuditioningModified) CyanPrimary.copy(alpha = 0.55f) else StudioBorder
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "边听边调实时预览",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            minLines = 1,
                            maxLines = 1
                        )
                        Text(
                            text = if (livePreviewEnabled) {
                                "播放时拖动滑杆或点 − / + 可直接听到变调和变速效果；确定后点最下方按钮生成音轨"
                            } else {
                                "实时预览已关闭，播放时试听未处理的原音轨；调整参数后点最下方按钮生成音轨"
                            },
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = TextMuted,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Switch(
                        checked = livePreviewEnabled,
                        onCheckedChange = onLivePreviewChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnAccentLight,
                            checkedTrackColor = CyanPrimary
                        )
                    )
                }

                HorizontalDivider(color = StudioBorder.copy(alpha = 0.7f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val playBtnShape = RoundedCornerShape(9.dp)
                    Surface(
                        shape = playBtnShape,
                        color = if (isPlaying) CyanPrimary else StudioSurface,
                        border = BorderStroke(1.dp, if (isPlaying) CyanPrimary else StudioBorder),
                        modifier = Modifier
                            .height(32.dp)
                            .clip(playBtnShape)
                            .clickable(onClick = onTogglePlayPause)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = if (isPlaying) OnAccentLight else CyanPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isPlaying) "暂停试听" else "播放试听",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isPlaying) OnAccentLight else TextPrimary,
                                maxLines = 1
                            )
                        }
                    }

                    val liveStatusText = when {
                        !livePreviewEnabled -> "已关闭：播放原音轨"
                        isComparingOriginal -> "当前正在对比原曲"
                        hasPitchChange || hasBpmChange -> {
                            val parts = buildList {
                                if (hasPitchChange) {
                                    add(if (semitones > 0) "+$semitones 半音" else "$semitones 半音")
                                }
                                if (hasBpmChange) {
                                    add("$targetBpm BPM")
                                }
                            }.joinToString(" · ")
                            if (isPlaying) "生效中：$parts" else "当前设定：$parts"
                        }
                        isPlaying -> "正在原调原速播放"
                        else -> "点左侧播放即可边听边调"
                    }

                    Text(
                        text = liveStatusText,
                        fontSize = 11.sp,
                        fontWeight = if (isLiveAuditioningModified) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isLiveAuditioningModified) CyanPrimary else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        }

        // ====================================================================
        // PART 1: Tempo (BPM) — [ - ]  ━━━━━━●━━━━━━  [ + ] (Constant Height)
        // ====================================================================
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = StudioSurfaceElevated.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, if (hasBpmChange) CyanPrimary.copy(alpha = 0.55f) else StudioBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = "速度调节 (BPM)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                        Text(
                            text = if (hasBpmChange) {
                                val sign = if (deltaBpm > 0) "+$deltaBpm" else "$deltaBpm"
                                String.format(Locale.US, "原速 %d · %s BPM (%.2fx)", sourceBpm, sign, speedRatio)
                            } else {
                                "原速 $sourceBpm BPM · 未变速"
                            },
                            fontSize = 11.sp,
                            color = if (hasBpmChange) CyanPrimary else TextMuted,
                            minLines = 1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val resetBpmShape = RoundedCornerShape(7.dp)
                        Surface(
                            shape = resetBpmShape,
                            color = StudioSurface,
                            border = BorderStroke(
                                1.dp,
                                if (hasBpmChange) StudioBorder else StudioBorder.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .clip(resetBpmShape)
                                .clickable(enabled = hasBpmChange) { onTargetBpmChange(sourceBpm) }
                        ) {
                            Text(
                                text = "原速",
                                fontSize = 11.sp,
                                color = if (hasBpmChange) TextSecondary else TextMuted.copy(alpha = 0.45f),
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (hasBpmChange) CyanPrimary else StudioSurface,
                            border = BorderStroke(1.dp, if (hasBpmChange) CyanPrimary else StudioBorder),
                            modifier = Modifier.widthIn(min = 78.dp)
                        ) {
                            Text(
                                text = "$targetBpm BPM",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (hasBpmChange) OnAccentLight else TextPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // [ - ]  Slider  [ + ] (1 BPM step)
                val sliderMin = 45f
                val sliderMax = 220f
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StepperIconButton(
                        symbol = "−",
                        enabled = targetBpm > 45,
                        onClick = { onAdjustTargetBpmBy(-1) }
                    )

                    Slider(
                        value = targetBpm.toFloat().coerceIn(sliderMin, sliderMax),
                        onValueChange = { onTargetBpmChange(it.roundToInt()) },
                        valueRange = sliderMin..sliderMax,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = CyanPrimary,
                            activeTrackColor = CyanPrimary,
                            inactiveTrackColor = StudioBorder
                        )
                    )

                    StepperIconButton(
                        symbol = "+",
                        enabled = targetBpm < 220,
                        onClick = { onAdjustTargetBpmBy(1) }
                    )
                }
            }
        }

        // ====================================================================
        // PART 2: Pitch (Semitone Key) — [ - ]  ━━━━━━●━━━━━━  [ + ] (Constant Height)
        // ====================================================================
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = StudioSurfaceElevated.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, if (hasPitchChange) CyanPrimary.copy(alpha = 0.55f) else StudioBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = "升降调 (半音)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                        Text(
                            text = describePitchShift(semitones, 0),
                            fontSize = 11.sp,
                            color = if (hasPitchChange) CyanPrimary else TextMuted,
                            minLines = 1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val resetKeyShape = RoundedCornerShape(7.dp)
                        Surface(
                            shape = resetKeyShape,
                            color = StudioSurface,
                            border = BorderStroke(
                                1.dp,
                                if (hasPitchChange) StudioBorder else StudioBorder.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .clip(resetKeyShape)
                                .clickable(enabled = hasPitchChange) {
                                    onSemitonesChange(0)
                                    onCentsChange(0)
                                }
                        ) {
                            Text(
                                text = "原调",
                                fontSize = 11.sp,
                                color = if (hasPitchChange) TextSecondary else TextMuted.copy(alpha = 0.45f),
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (hasPitchChange) CyanPrimary else StudioSurface,
                            border = BorderStroke(1.dp, if (hasPitchChange) CyanPrimary else StudioBorder),
                            modifier = Modifier.widthIn(min = 72.dp)
                        ) {
                            val semitoneLabel = when {
                                semitones > 0 -> "+$semitones 半音"
                                semitones < 0 -> "$semitones 半音"
                                else -> "0 半音"
                            }
                            Text(
                                text = semitoneLabel,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (hasPitchChange) OnAccentLight else TextPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // [ - ]  Slider  [ + ] (1 Semitone step)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StepperIconButton(
                        symbol = "−",
                        enabled = semitones > -12,
                        onClick = {
                            onCentsChange(0)
                            onSemitonesChange((semitones - 1).coerceIn(-12, 12))
                        }
                    )

                    Slider(
                        value = semitones.toFloat(),
                        onValueChange = {
                            onCentsChange(0)
                            onSemitonesChange(it.roundToInt())
                        },
                        valueRange = -12f..12f,
                        steps = 23,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = CyanPrimary,
                            activeTrackColor = CyanPrimary,
                            inactiveTrackColor = StudioBorder
                        )
                    )

                    StepperIconButton(
                        symbol = "+",
                        enabled = semitones < 12,
                        onClick = {
                            onCentsChange(0)
                            onSemitonesChange((semitones + 1).coerceIn(-12, 12))
                        }
                    )
                }
            }
        }

        // Formant Preservation Switch (Constant 2-line subtitle height)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = StudioSurfaceElevated.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, StudioBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Text(
                        text = "保持人声音色（含人声歌曲适用）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = if (preserveTimbre) "已开启：对含人声的原曲升降调时，避免人声变尖或变粗"
                        else "默认关闭（适合纯伴奏）：对伴奏升降调时保持关闭，乐器音质更自然",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = TextMuted,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = preserveTimbre,
                    onCheckedChange = onPreserveTimbreChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnAccentLight,
                        checkedTrackColor = CyanPrimary
                    )
                )
            }
        }

        val actionButtonText = when {
            hasPitchChange && hasBpmChange -> {
                val pStr = if (semitones > 0) "+$semitones 半音" else "$semitones 半音"
                "生成新音轨 ($pStr · ${sourceBpm}→${targetBpm} BPM)"
            }
            hasPitchChange -> {
                val pStr = if (semitones > 0) "+$semitones 半音" else "$semitones 半音"
                "生成移调音轨 ($pStr · 保持 $sourceBpm BPM)"
            }
            hasBpmChange -> {
                "生成变速音轨 ($sourceBpm → $targetBpm BPM)"
            }
            else -> "请先调整上方速度或升降调"
        }

        Button(
            onClick = onRun,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasPitchChange || hasBpmChange) CyanPrimary else VioletAccent,
                contentColor = OnAccentLight
            ),
            contentPadding = PaddingValues(vertical = 13.dp)
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = actionButtonText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StepperIconButton(
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.size(38.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (enabled) TextPrimary else TextMuted.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun VolumeBoostPanel(
    track: SessionTrack,
    isPlaying: Boolean,
    isComparingOriginal: Boolean,
    livePreviewEnabled: Boolean,
    gainDb: Float,
    onTogglePlayPause: () -> Unit,
    onLivePreviewChange: (Boolean) -> Unit,
    onGainChange: (Float) -> Unit,
    onRun: () -> Unit
) {
    val metrics = track.metrics
    val headroom = metrics.pureLosslessHeadroomDb
    val hasGainChange = gainDb > 0.05f
    val isPureLosslessZone = !hasGainChange || gainDb <= headroom + 0.05f
    val isLiveAuditioningBoost = livePreviewEnabled && !isComparingOriginal && hasGainChange
    val activeAccentColor = if (isPureLosslessZone) EmeraldLossless else AmberMastering

    val losslessPresetGain = ((headroom.coerceIn(0f, 18f) * 10f).roundToInt() / 10f)
    val moderatePresetGain = ((if (metrics.integratedLufs > -60f) {
        (-11.0f - metrics.integratedLufs).coerceIn(1.5f, 14f)
    } else 4.5f) * 10f).roundToInt() / 10f
    val loudPresetGain = ((if (metrics.integratedLufs > -60f) {
        (-8.5f - metrics.integratedLufs).coerceIn(3.0f, 16f)
    } else 7.5f) * 10f).roundToInt() / 10f

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header Row — Locked Constant Height (42.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = EmeraldLossless)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "音量放大",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = "可边播边调试听，调好后生成新音轨",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        minLines = 1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            val resetShape = RoundedCornerShape(8.dp)
            Surface(
                shape = resetShape,
                color = if (hasGainChange) EmeraldLossless.copy(alpha = 0.12f) else StudioSurfaceElevated.copy(alpha = 0.5f),
                border = BorderStroke(
                    1.dp,
                    if (hasGainChange) EmeraldLossless.copy(alpha = 0.45f) else StudioBorder.copy(alpha = 0.6f)
                ),
                modifier = Modifier
                    .clip(resetShape)
                    .clickable(enabled = hasGainChange) { onGainChange(0f) }
            ) {
                Text(
                    text = "归零",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hasGainChange) EmeraldLossless else TextMuted.copy(alpha = 0.45f),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        // ====================================================================
        // Real-Time Audition ("边听边调") Control Bar — Locked Constant Height
        // ====================================================================
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isLiveAuditioningBoost) activeAccentColor.copy(alpha = 0.08f) else StudioSurfaceElevated.copy(alpha = 0.65f),
            border = BorderStroke(
                width = 1.dp,
                color = if (isLiveAuditioningBoost) activeAccentColor.copy(alpha = 0.55f) else StudioBorder
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "边听边调实时预览",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            minLines = 1,
                            maxLines = 1
                        )
                        Text(
                            text = if (livePreviewEnabled) {
                                "播放时拖动滑杆、点 − / + 或预设可直接听到放大效果；确定后点最下方按钮生成音轨"
                            } else {
                                "实时预览已关闭，播放时试听未放大的原音轨；调整增益后点最下方按钮生成音轨"
                            },
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = TextMuted,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Switch(
                        checked = livePreviewEnabled,
                        onCheckedChange = onLivePreviewChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnAccentLight,
                            checkedTrackColor = EmeraldLossless
                        )
                    )
                }

                HorizontalDivider(color = StudioBorder.copy(alpha = 0.7f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val playBtnShape = RoundedCornerShape(9.dp)
                    Surface(
                        shape = playBtnShape,
                        color = if (isPlaying) EmeraldLossless else StudioSurface,
                        border = BorderStroke(1.dp, if (isPlaying) EmeraldLossless else StudioBorder),
                        modifier = Modifier
                            .height(32.dp)
                            .clip(playBtnShape)
                            .clickable(onClick = onTogglePlayPause)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = if (isPlaying) OnAccentLight else EmeraldLossless,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isPlaying) "暂停试听" else "播放试听",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isPlaying) OnAccentLight else TextPrimary,
                                maxLines = 1
                            )
                        }
                    }

                    val liveStatusText = when {
                        !livePreviewEnabled -> "已关闭：播放原音轨"
                        isComparingOriginal -> "当前正在对比原曲"
                        hasGainChange -> {
                            val gainText = String.format(Locale.US, "+%.1f dB", gainDb)
                            if (isPlaying) "生效中：$gainText" else "当前设定：$gainText"
                        }
                        isPlaying -> "正在原音量播放"
                        else -> "点左侧播放即可边听边调"
                    }

                    Text(
                        text = liveStatusText,
                        fontSize = 11.sp,
                        fontWeight = if (isLiveAuditioningBoost) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isLiveAuditioningBoost) activeAccentColor else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        }

        // Acoustic Metrics Readout Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricMiniCard(
                label = "当前响度",
                value = if (metrics.integratedLufs <= -68f) "静音"
                else String.format(Locale.US, "%.1f LUFS", metrics.integratedLufs),
                accent = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            MetricMiniCard(
                label = "最高峰值",
                value = String.format(Locale.US, "%.1f dBTP", metrics.truePeakDbtp),
                accent = CyanPrimary,
                modifier = Modifier.weight(1f)
            )
            MetricMiniCard(
                label = "无损可放大",
                value = String.format(Locale.US, "+%.1f dB", headroom),
                accent = EmeraldLossless,
                modifier = Modifier.weight(1f)
            )
        }

        // ====================================================================
        // Gain Adjustment Card — [ − ]  ━━━━━━●━━━━━━  [ + ] + Presets (Constant Height)
        // ====================================================================
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = StudioSurfaceElevated.copy(alpha = 0.65f),
            border = BorderStroke(
                width = 1.dp,
                color = if (hasGainChange) activeAccentColor.copy(alpha = 0.55f) else StudioBorder
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = "放大增益 (dB)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                        Text(
                            text = when {
                                !hasGainChange -> "当前保持原曲音量（未放大）"
                                isPureLosslessZone -> "无损放大区间 · 等比例放大不失真"
                                else -> "防破音保护中 · 自动压制峰值防爆音"
                            },
                            fontSize = 11.sp,
                            color = if (hasGainChange) activeAccentColor else TextMuted,
                            minLines = 1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (hasGainChange) activeAccentColor else StudioSurface,
                        border = BorderStroke(1.dp, if (hasGainChange) activeAccentColor else StudioBorder),
                        modifier = Modifier.widthIn(min = 80.dp)
                    ) {
                        Text(
                            text = if (hasGainChange) {
                                String.format(Locale.US, "+%.1f dB", gainDb)
                            } else {
                                "0.0 dB"
                            },
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (hasGainChange) OnAccentLight else TextPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }

                // [ − ]  Slider  [ + ] (0.5 dB step)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StepperIconButton(
                        symbol = "−",
                        enabled = gainDb > 0.01f,
                        onClick = { onGainChange(gainDb - 0.5f) }
                    )

                    Slider(
                        value = gainDb,
                        onValueChange = onGainChange,
                        valueRange = 0f..18f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = activeAccentColor,
                            activeTrackColor = activeAccentColor,
                            inactiveTrackColor = StudioBorder
                        )
                    )

                    StepperIconButton(
                        symbol = "+",
                        enabled = gainDb < 17.99f,
                        onClick = { onGainChange(gainDb + 0.5f) }
                    )
                }

                // Quick 1-Tap Presets — Constant 32.dp Height (no M3 48dp expansion)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val presetShape = RoundedCornerShape(9.dp)
                    val losslessEnabled = losslessPresetGain >= 0.1f || hasGainChange
                    val isLosslessSelected = hasGainChange && losslessPresetGain >= 0.1f && abs(gainDb - losslessPresetGain) < 0.06f
                    val isModerateSelected = hasGainChange && abs(gainDb - moderatePresetGain) < 0.06f
                    val isLoudSelected = hasGainChange && abs(gainDb - loudPresetGain) < 0.06f

                    Surface(
                        shape = presetShape,
                        color = if (isLosslessSelected) EmeraldLossless.copy(alpha = 0.14f) else StudioSurface,
                        border = BorderStroke(
                            1.dp,
                            if (losslessEnabled) EmeraldLossless.copy(alpha = 0.55f) else StudioBorder.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(presetShape)
                            .clickable(enabled = losslessEnabled) {
                                onGainChange(losslessPresetGain)
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "无损最大",
                                fontSize = 11.sp,
                                color = if (losslessEnabled) EmeraldLossless else TextMuted.copy(alpha = 0.45f),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    Surface(
                        shape = presetShape,
                        color = if (isModerateSelected) CyanPrimary.copy(alpha = 0.14f) else StudioSurface,
                        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(presetShape)
                            .clickable { onGainChange(moderatePresetGain) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "适中音量",
                                fontSize = 11.sp,
                                color = CyanPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    Surface(
                        shape = presetShape,
                        color = if (isLoudSelected) AmberMastering.copy(alpha = 0.14f) else StudioSurface,
                        border = BorderStroke(1.dp, AmberMastering.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(presetShape)
                            .clickable { onGainChange(loudPresetGain) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "较大音量",
                                fontSize = 11.sp,
                                color = AmberMastering,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Dynamic Lossless vs Limiter Status Banner (Constant 2-line height)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = activeAccentColor.copy(alpha = if (hasGainChange) 0.10f else 0.06f),
            border = BorderStroke(1.dp, activeAccentColor.copy(alpha = if (hasGainChange) 0.35f else 0.22f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = when {
                    !hasGainChange -> {
                        "拖动滑杆、点击 − / +（每次 0.5 dB）或点选预设即可边听边调；超出无损空间时自动防破音。"
                    }
                    isPureLosslessZone -> {
                        "当前处于无损放大区间：仅等比例调大整体音量，不会产生任何失真或破音。"
                    }
                    else -> {
                        "已超出原始无损空间：自动启用防破音保护，提高音量的同时压制峰值避免爆音。"
                    }
                },
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = if (hasGainChange) activeAccentColor else TextSecondary,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(11.dp)
            )
        }

        Button(
            onClick = onRun,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasGainChange) EmeraldLossless else VioletAccent,
                contentColor = OnAccentLight
            ),
            contentPadding = PaddingValues(vertical = 13.dp)
        ) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (hasGainChange) {
                    String.format(Locale.US, "生成放大音轨 (+%.1f dB)", gainDb)
                } else {
                    "请先调整上方放大增益"
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MetricMiniCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = StudioSurfaceElevated.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, StudioBorder)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 10.sp, color = TextMuted, maxLines = 1)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                value,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SessionTrackItemCard(
    track: SessionTrack,
    isSelected: Boolean,
    onClick: () -> Unit,
    onSendToArrangerClick: () -> Unit,
    onRenameClick: () -> Unit,
    onShareWeChatClick: () -> Unit,
    onSaveAsClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) StudioSurface else StudioSurface.copy(alpha = 0.85f),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) CyanPrimary else StudioBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = if (isSelected) CyanPrimary else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val itemExt = track.audioFile.extension.lowercase().ifBlank { "wav" }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${track.displayName}.$itemExt",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${track.badge} · ${formatDurationMs((track.metrics.durationSec * 1000f).toLong())} · ${formatBytesShort(track.audioFile.length())}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSendToArrangerClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.ContentCut,
                            contentDescription = "送去多轨剪辑",
                            tint = CyanPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(onClick = onRenameClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.DriveFileRenameOutline,
                            contentDescription = "重命名",
                            tint = TextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(onClick = onShareWeChatClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "分享微信",
                            tint = WeChatGreen,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(onClick = onSaveAsClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.SaveAlt,
                            contentDescription = "另存为本地",
                            tint = CyanPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "删除音轨",
                            tint = TextMuted,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = track.savedLocalPath != null) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = EmeraldLossless,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "已另存至手机：${track.savedLocalPath}",
                        fontSize = 11.sp,
                        color = EmeraldLossless,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenameTrackDialog(
    track: SessionTrack,
    exportExtension: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(track.id) { mutableStateOf(track.displayName) }
    val quickTags = listOf("_伴奏", "_纯伴奏", "_升调", "_降调", "_变速", "_母带增益")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Text("重命名音频", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("文件名（无需输入后缀）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "分享微信 / 另存为时将显示为：${text.trim().ifBlank { "未命名" }}.$exportExtension",
                    fontSize = 11.sp,
                    color = CyanPrimary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickTags.forEach { suffix ->
                        Surface(
                            onClick = {
                                if (!text.endsWith(suffix)) text = text.trim() + suffix
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = StudioSurfaceElevated,
                            border = BorderStroke(1.dp, StudioBorder)
                        ) {
                            Text(
                                text = "+ $suffix",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Surface(
                        onClick = {
                            val firstUnderscore = text.indexOf('_')
                            if (firstUnderscore > 0) text = text.substring(0, firstUnderscore)
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = StudioSurfaceElevated,
                        border = BorderStroke(1.dp, StudioBorder)
                    ) {
                        Text(
                            text = "去除后缀",
                            fontSize = 11.sp,
                            color = AmberMastering,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                )
            ) {
                Text("确认重命名", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveAsBottomSheet(
    track: SessionTrack,
    currentFormat: ExportFormat,
    onFormatChange: (ExportFormat) -> Unit,
    onQuickSaveMusic: () -> Unit,
    onCustomFolderSave: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "另存为本地手机存储",
                fontFamily = FontFamily.Serif,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "选择导出格式并将「${track.displayName}」导出到系统音乐目录或自定义文件夹。",
                fontSize = 12.sp,
                color = TextSecondary
            )

            // Format selection
            ExportFormat.entries.forEach { fmt ->
                val selected = fmt == currentFormat
                Surface(
                    onClick = { onFormatChange(fmt) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) CyanPrimary.copy(alpha = 0.10f) else StudioSurfaceElevated,
                    border = BorderStroke(1.dp, if (selected) CyanPrimary else StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(fmt.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(fmt.badge, fontSize = 11.sp, color = TextSecondary)
                        }
                        Text(
                            text = ".${fmt.extension}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) CyanPrimary else TextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onQuickSaveMusic,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                ),
                contentPadding = PaddingValues(vertical = 13.dp)
            ) {
                Icon(Icons.Rounded.SaveAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "一键保存至手机音乐 (Music/SoundTracker)",
                    fontWeight = FontWeight.SemiBold
                )
            }

            OutlinedButton(
                onClick = onCustomFolderSave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StudioBorder),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("自选手机文件夹另存为...", color = TextPrimary)
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

private enum class SheetStage {
    HIDDEN,
    HALF, // 五分屏 (56% screen height)
    FULL  // 九分屏 (90% screen height)
}

/**
 * Custom two-stage bottom sheet (五分屏 56% / 九分屏 90% / 收起) with deterministic
 * LazyColumn scroll arbitration:
 * - Swiping up or down inside the list ALWAYS scrolls the list while the list can scroll in that direction.
 * - When scrolling back towards the top of the list (回滑, finger moving top-to-bottom), even if the
 *   swipe or fling reaches item 0 mid-gesture, the leftover delta/velocity is absorbed at the top of the
 *   list and NEVER drags the sheet down.
 * - ONLY when a downward swipe starts while the list is ALREADY at the very top (cannot scroll back
 *   any further) does dragging down pull the panel down (9 -> 5, or 5 -> 收起).
 * - Dragging on non-list areas (drag handle, header, search bar, filter chips, bottom bar) always
 *   drags the panel smoothly between 5 <-> 9 <-> 收起.
 */
@Composable
private fun TwoStageBottomSheet(
    listState: LazyListState,
    onDismissRequest: () -> Unit,
    containerColor: Color = StudioSurface,
    content: @Composable (
        currentStage: SheetStage,
        onToggleStage: () -> Unit,
        listScrollModifier: Modifier
    ) -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val halfHeightPx = screenHeightPx * 0.58f
        val fullHeightPx = screenHeightPx * 0.90f
        val flingVelocityThresholdPx = with(density) { 420.dp.toPx() }

        var currentStage by remember { mutableStateOf(SheetStage.HALF) }
        var sheetHeightPx by remember { mutableFloatStateOf(0f) }
        var animJob by remember { mutableStateOf<Job?>(null) }

        fun animateSheetTo(targetStage: SheetStage, initialVelocityY: Float = 0f) {
            animJob?.cancel()
            val targetPx = when (targetStage) {
                SheetStage.HIDDEN -> 0f
                SheetStage.HALF -> halfHeightPx
                SheetStage.FULL -> fullHeightPx
            }
            if (targetStage != SheetStage.HIDDEN) {
                currentStage = targetStage
            }
            animJob = scope.launch {
                val startPx = sheetHeightPx
                if (abs(startPx - targetPx) > 0.5f) {
                    animate(
                        initialValue = startPx,
                        targetValue = targetPx,
                        initialVelocity = -initialVelocityY,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) { value, _ ->
                        sheetHeightPx = value
                    }
                } else {
                    sheetHeightPx = targetPx
                }
                sheetHeightPx = targetPx
                if (targetStage == SheetStage.HIDDEN) {
                    onDismissRequest()
                }
            }
        }

        LaunchedEffect(screenHeightPx) {
            if (sheetHeightPx == 0f && currentStage != SheetStage.HIDDEN) {
                animateSheetTo(SheetStage.HALF)
            }
        }

        BackHandler(enabled = true) {
            animateSheetTo(SheetStage.HIDDEN)
        }

        fun applySheetDragDelta(deltaY: Float, startStage: SheetStage): Float {
            if (deltaY == 0f) return 0f
            animJob?.cancel()
            // deltaY > 0 means finger moves downward (shrinking sheetHeightPx)
            // deltaY < 0 means finger moves upward (growing sheetHeightPx)
            val minAllowed = when {
                startStage == SheetStage.FULL && deltaY > 0f -> halfHeightPx
                else -> 0f
            }
            val oldHeight = sheetHeightPx
            val newHeight = (oldHeight - deltaY).coerceIn(minAllowed, fullHeightPx)
            sheetHeightPx = newHeight
            return oldHeight - newHeight
        }

        fun settleSheetAfterDrag(velocityY: Float, startStage: SheetStage) {
            val h = sheetHeightPx
            val targetStage = when (startStage) {
                SheetStage.FULL -> {
                    // Dragged from 九分屏 (9): downward drag transitions 9 -> 5 (never 9 -> 0 directly)
                    if (velocityY > flingVelocityThresholdPx) {
                        SheetStage.HALF
                    } else if (velocityY < -flingVelocityThresholdPx) {
                        SheetStage.FULL
                    } else {
                        val mid = (halfHeightPx + fullHeightPx) * 0.5f
                        if (h < mid) SheetStage.HALF else SheetStage.FULL
                    }
                }
                SheetStage.HALF -> {
                    if (h > halfHeightPx + 1f) {
                        // Dragged upward between 5 and 9
                        if (velocityY < -flingVelocityThresholdPx) {
                            SheetStage.FULL
                        } else if (velocityY > flingVelocityThresholdPx) {
                            SheetStage.HALF
                        } else {
                            val mid = (halfHeightPx + fullHeightPx) * 0.5f
                            if (h >= mid) SheetStage.FULL else SheetStage.HALF
                        }
                    } else {
                        // Dragged downward between 5 and 收起
                        if (velocityY > flingVelocityThresholdPx) {
                            SheetStage.HIDDEN
                        } else if (velocityY < -flingVelocityThresholdPx) {
                            SheetStage.HALF
                        } else {
                            if (h < halfHeightPx * 0.72f) SheetStage.HIDDEN else SheetStage.HALF
                        }
                    }
                }
                SheetStage.HIDDEN -> SheetStage.HIDDEN
            }
            animateSheetTo(targetStage, velocityY)
        }

        // Gesture tracking state for the LazyColumn touch area
        var gestureStartedAtTop by remember { mutableStateOf(true) }
        var gestureStartedAtBottom by remember { mutableStateOf(false) }
        var listScrolledDuringGesture by remember { mutableStateOf(false) }
        var sheetDraggedByListGesture by remember { mutableStateOf(false) }
        var listDragStartStage by remember { mutableStateOf(SheetStage.HALF) }
        var settledByPreFling by remember { mutableStateOf(false) }

        val listNestedScrollConnection = remember(screenHeightPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    val dy = available.y
                    if (dy == 0f) return Offset.Zero

                    // If this touch gesture has ALREADY started dragging the sheet, keep routing
                    // subsequent vertical movement in the same gesture to the sheet so the list
                    // doesn't scroll while the sheet is mid-drag.
                    if (sheetDraggedByListGesture) {
                        applySheetDragDelta(dy, listDragStartStage)
                        return Offset(0f, dy)
                    }

                    // If user swipes up (dy < 0) and the list can scroll forward, mark that the
                    // list is scrolling in this gesture and let LazyColumn consume it.
                    if (dy < 0f && listState.canScrollForward) {
                        listScrolledDuringGesture = true
                        return Offset.Zero
                    }

                    // If user swipes down (dy > 0) and the list can scroll backward, mark that the
                    // list is scrolling in this gesture and let LazyColumn consume it.
                    if (dy > 0f && listState.canScrollBackward) {
                        listScrolledDuringGesture = true
                        return Offset.Zero
                    }

                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset {
                    if (consumed.y != 0f) {
                        listScrolledDuringGesture = true
                    }
                    if (source != NestedScrollSource.UserInput) {
                        return Offset(0f, available.y)
                    }
                    val dy = available.y
                    if (dy == 0f) return Offset.Zero

                    if (dy > 0f) {
                        // Finger moving top-to-bottom (回滑):
                        // ONLY drag the panel down if this touch gesture started when the list was
                        // ALREADY at the very top and did NOT scroll the list at all during this gesture.
                        val isAtTopNow = !listState.canScrollBackward &&
                            listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0
                        if (gestureStartedAtTop && !listScrolledDuringGesture && isAtTopNow) {
                            sheetDraggedByListGesture = true
                            applySheetDragDelta(dy, listDragStartStage)
                        }
                        // Always consume leftover downward delta so it never leaks.
                        return Offset(0f, dy)
                    } else {
                        // Finger moving bottom-to-top (dy < 0):
                        // If in 五分屏 and gesture started at the bottom of the list (or list fits on screen),
                        // pulling up expands 5 -> 9.
                        if (currentStage == SheetStage.HALF &&
                            gestureStartedAtBottom &&
                            !listState.canScrollForward &&
                            !listScrolledDuringGesture
                        ) {
                            sheetDraggedByListGesture = true
                            applySheetDragDelta(dy, listDragStartStage)
                        }
                        return Offset(0f, dy)
                    }
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (sheetDraggedByListGesture) {
                        settledByPreFling = true
                        sheetDraggedByListGesture = false
                        settleSheetAfterDrag(available.y, listDragStartStage)
                        return available
                    }
                    return Velocity.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (sheetDraggedByListGesture && !settledByPreFling) {
                        sheetDraggedByListGesture = false
                        settleSheetAfterDrag(available.y, listDragStartStage)
                    }
                    // Consume any leftover fling velocity from the list so it never moves the sheet.
                    return available
                }
            }
        }

        val listScrollModifier = Modifier
            .pointerInput(listState, screenHeightPx) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val atTopNow = !listState.canScrollBackward &&
                        listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0
                    val atBottomNow = !listState.canScrollForward
                    gestureStartedAtTop = atTopNow
                    gestureStartedAtBottom = atBottomNow
                    listScrolledDuringGesture = !atTopNow
                    sheetDraggedByListGesture = false
                    listDragStartStage = currentStage
                    settledByPreFling = false

                    do {
                        val event = awaitPointerEvent(pass = PointerEventPass.Final)
                    } while (event.changes.any { it.pressed })

                    if (sheetDraggedByListGesture && !settledByPreFling) {
                        settledByPreFling = true
                        sheetDraggedByListGesture = false
                        settleSheetAfterDrag(0f, listDragStartStage)
                    }
                }
            }
            .nestedScroll(listNestedScrollConnection)

        // Draggable state for non-list areas (Drag Handle, Header, Search Bar, Filter Chips, Bottom Bar)
        var surfaceDragStartStage by remember { mutableStateOf(SheetStage.HALF) }
        val surfaceDraggableState = rememberDraggableState { deltaY ->
            applySheetDragDelta(deltaY, surfaceDragStartStage)
        }

        val scrimProgress = (sheetHeightPx / halfHeightPx).coerceIn(0f, 1f)

        // Full-screen Backdrop Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f * scrimProgress))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { animateSheetTo(SheetStage.HIDDEN) })
                }
        )

        val visibleHeightPx = sheetHeightPx.coerceAtLeast(halfHeightPx)
        val translationYPx = if (sheetHeightPx < halfHeightPx) {
            (halfHeightPx - sheetHeightPx).roundToInt()
        } else {
            0
        }

        Surface(
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = containerColor,
            tonalElevation = 6.dp,
            shadowElevation = 20.dp,
            border = BorderStroke(1.dp, StudioBorder),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { visibleHeightPx.toDp() })
                .offset { IntOffset(0, translationYPx) }
                .draggable(
                    state = surfaceDraggableState,
                    orientation = Orientation.Vertical,
                    startDragImmediately = false,
                    onDragStarted = {
                        animJob?.cancel()
                        surfaceDragStartStage = currentStage
                    },
                    onDragStopped = { velocity ->
                        settleSheetAfterDrag(velocity, surfaceDragStartStage)
                    }
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Drag Handle Bar (supports both drag and tap to toggle 五分屏/九分屏)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val nextStage = if (currentStage == SheetStage.HALF) {
                                SheetStage.FULL
                            } else {
                                SheetStage.HALF
                            }
                            animateSheetTo(nextStage)
                        }
                        .padding(top = 10.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 38.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TextMuted.copy(alpha = 0.45f))
                    )
                }

                content(
                    currentStage,
                    {
                        val nextStage = if (currentStage == SheetStage.HALF) {
                            SheetStage.FULL
                        } else {
                            SheetStage.HALF
                        }
                        animateSheetTo(nextStage)
                    },
                    listScrollModifier
                )
            }
        }
    }
}

/**
 * Interactive Storage Resource Manager Sheet:
 * - Lists every stored audio/cache resource in the App with its exact size
 * - Checkbox on each cell for multi-select batch deletion + Select All / Deselect All
 * - Dedicated single-delete button on each cell
 * - One-tap "一键全清" button to wipe all stored resources
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageResourceManagerSheet(
    storageInfo: EphemeralStorageInfo,
    onDeleteSingleItem: (String) -> Unit,
    onDeleteSelectedItems: (Set<String>) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val resources = storageInfo.resources
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()

    // Prune any paths that were already deleted
    LaunchedEffect(resources) {
        val valid = resources.mapTo(hashSetOf()) { it.filePath }
        selectedPaths = selectedPaths.intersect(valid)
    }

    val allSelected = resources.isNotEmpty() && selectedPaths.size == resources.size
    val selectedBytes = resources.filter { it.filePath in selectedPaths }.sumOf { it.sizeBytes }

    TwoStageBottomSheet(
        listState = listState,
        onDismissRequest = onDismiss,
        containerColor = StudioSurface
    ) { currentStage, onToggleStage, listScrollModifier ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "应用内存储资源管理",
                        fontFamily = FontFamily.Serif,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "共 ${resources.size} 个音频资源 · 占用 ${formatBytesShort(storageInfo.totalEphemeralBytes)}",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(onClick = onToggleStage) {
                    Text(
                        text = if (currentStage == SheetStage.HALF) "展开九分屏" else "收为五分屏",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                if (resources.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            selectedPaths = if (allSelected) {
                                emptySet()
                            } else {
                                resources.mapTo(linkedSetOf()) { it.filePath }
                            }
                        }
                    ) {
                        Text(
                            text = if (allSelected) "取消全选" else "全选 (${resources.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CyanPrimary
                        )
                    }
                }
            }

            HorizontalDivider(color = StudioBorder)

            if (resources.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioSurfaceElevated.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CleaningServices,
                                contentDescription = null,
                                tint = EmeraldLossless,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "当前应用内暂无任何存储资源 (0 KB)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "导入或处理后的音轨及分享缓存会在此处列出，方便随时按需清理",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .then(listScrollModifier)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = resources, key = { it.id }) { item ->
                            val isChecked = item.filePath in selectedPaths
                            StoredResourceRowCell(
                                item = item,
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedPaths = if (checked) {
                                        selectedPaths + item.filePath
                                    } else {
                                        selectedPaths - item.filePath
                                    }
                                },
                                onDeleteClick = {
                                    selectedPaths = selectedPaths - item.filePath
                                    onDeleteSingleItem(item.filePath)
                                }
                            )
                        }
                    }
                }
            }

            // Bottom Action Bar: Batch Delete Selected + One-Tap Clear All
            if (resources.isNotEmpty()) {
                HorizontalDivider(color = StudioBorder)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val toDelete = selectedPaths
                            selectedPaths = emptySet()
                            onDeleteSelectedItems(toDelete)
                        },
                        enabled = selectedPaths.isNotEmpty(),
                        modifier = Modifier.weight(1.1f),
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(
                            1.dp,
                            if (selectedPaths.isNotEmpty()) CyanPrimary else StudioBorder
                        ),
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = if (selectedPaths.isNotEmpty()) CyanPrimary else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (selectedPaths.isEmpty()) {
                                "勾选后批量删除"
                            } else {
                                "删除已选 (${selectedPaths.size}项 · ${formatBytesShort(selectedBytes)})"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selectedPaths.isNotEmpty()) CyanPrimary else TextMuted,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = {
                            selectedPaths = emptySet()
                            onClearAll()
                        },
                        modifier = Modifier.weight(0.9f),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DangerRed,
                            contentColor = OnAccentLight
                        ),
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "一键全清",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun StoredResourceRowCell(
    item: StoredResourceItem,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDeleteClick: () -> Unit
) {
    val categoryColor = when (item.category) {
        StoredResourceCategory.WORKSPACE_TRACK -> CyanPrimary
        StoredResourceCategory.SHARE_EXPORT -> WeChatGreen
        StoredResourceCategory.TEMP_CACHE -> AmberMastering
        StoredResourceCategory.APK_UPDATE -> VioletAccent
    }

    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(12.dp),
        color = if (checked) CyanPrimary.copy(alpha = 0.08f) else StudioSurfaceElevated.copy(alpha = 0.65f),
        border = BorderStroke(
            width = 1.dp,
            color = if (checked) CyanPrimary else StudioBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = CyanPrimary,
                    checkmarkColor = OnAccentLight,
                    uncheckedColor = TextMuted
                )
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = categoryColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = item.category.label,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = categoryColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = formatBytesShort(item.sizeBytes),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                    Text(
                        text = "· ${item.subtitle}",
                        fontSize = 10.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            OutlinedButton(
                onClick = onDeleteClick,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.45f)),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "删除",
                    tint = DangerRed,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "删除",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DangerRed
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetEaseCloudMusicImportSheet(
    tracks: List<NetEaseLocalTrack>,
    isScanning: Boolean,
    hasStorageAccess: Boolean,
    onRefresh: () -> Unit,
    onRequestStorageAccess: () -> Unit,
    onPickManualNcmFile: () -> Unit,
    onImportTracks: (List<NetEaseLocalTrack>) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Filter: 0 = All, 1 = NCM, 2 = FLAC, 3 = MP3
    var filterTab by rememberSaveable { mutableStateOf(0) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()

    val filteredTracks = remember(tracks, searchQuery, filterTab) {
        val q = searchQuery.trim().lowercase()
        tracks.filter { item ->
            val matchesQuery = q.isEmpty() ||
                item.displayTitle.lowercase().contains(q) ||
                item.songName.lowercase().contains(q) ||
                item.artist.lowercase().contains(q) ||
                item.album.lowercase().contains(q)
            val matchesFilter = when (filterTab) {
                1 -> item.isNcm
                2 -> item.isLosslessFlac
                3 -> !item.isLosslessFlac
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    val allFilteredSelected = filteredTracks.isNotEmpty() && filteredTracks.all { it.id in selectedIds }

    TwoStageBottomSheet(
        listState = listState,
        onDismissRequest = onDismiss,
        containerColor = StudioSurface
    ) { currentStage, onToggleStage, listScrollModifier ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "从网易云音乐导入",
                            fontFamily = FontFamily.Serif,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "NCM 自动无损解密为 FLAC 或 MP3（同名多版本自动优选最高音质）",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                TextButton(onClick = onToggleStage) {
                    Text(
                        text = if (currentStage == SheetStage.HALF) "九分屏" else "五分屏",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "重新扫描",
                        tint = CyanPrimary
                    )
                }
            }

            // Permission Banner if Android 11+ MANAGE_EXTERNAL_STORAGE is not granted
            if (!hasStorageAccess) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AmberMastering.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AmberMastering.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "开启文件访问权限以扫描 .ncm 加密音乐",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "安卓系统默认隐藏第三方应用的 .ncm 文件，授权后即可一键列出全部网易云下载歌曲",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onRequestStorageAccess,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = OnAccentLight
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("去授权", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Search Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索歌名、歌手或专辑...", fontSize = 13.sp, color = TextMuted) },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清除", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Filter Chips + Select All
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val ncmCount = tracks.count { it.isNcm }
                val flacCount = tracks.count { it.isLosslessFlac }
                val mp3Count = tracks.size - flacCount
                val tabs = listOf(
                    0 to "全部 (${tracks.size})",
                    1 to "NCM 加密 ($ncmCount)",
                    2 to "FLAC 无损 ($flacCount)",
                    3 to "MP3 ($mp3Count)"
                )
                tabs.forEach { (idx, title) ->
                    val selected = filterTab == idx
                    FilterChip(
                        selected = selected,
                        onClick = { filterTab = idx },
                        label = { Text(title, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanPrimary.copy(alpha = 0.16f),
                            selectedLabelColor = CyanPrimary
                        )
                    )
                }

                if (filteredTracks.isNotEmpty()) {
                    val selectAllShape = RoundedCornerShape(8.dp)
                    Surface(
                        shape = selectAllShape,
                        color = CyanPrimary.copy(alpha = 0.10f),
                        modifier = Modifier
                            .clip(selectAllShape)
                            .clickable {
                                selectedIds = if (allFilteredSelected) {
                                    selectedIds - filteredTracks.map { it.id }.toSet()
                                } else {
                                    selectedIds + filteredTracks.map { it.id }.toSet()
                                }
                            }
                    ) {
                        Text(
                            text = if (allFilteredSelected) "取消全选" else "全选当前",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CyanPrimary,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            if (isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = CyanPrimary,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("正在扫描网易云音乐下载目录并解析 NCM 音质...", fontSize = 13.sp, color = TextSecondary)
                    }
                }
            } else if (filteredTracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioSurfaceElevated.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (tracks.isEmpty()) "未扫描到网易云音乐已下载歌曲" else "没有匹配的搜索结果",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "默认扫描路径：Download/netease/cloudmusic/Music 及 netease/cloudmusic/Music",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            OutlinedButton(
                                onClick = onPickManualNcmFile,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, StudioBorder)
                            ) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("手动从文件夹选择 .ncm / .flac / .mp3", fontSize = 12.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .then(listScrollModifier)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = filteredTracks, key = { it.id }) { item ->
                            val isChecked = item.id in selectedIds
                            NetEaseTrackRowCell(
                                item = item,
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                                },
                                onSingleImportClick = {
                                    onImportTracks(listOf(item))
                                }
                            )
                        }
                    }
                }
            }

            // Bottom Batch Import Bar
            if (tracks.isNotEmpty()) {
                HorizontalDivider(color = StudioBorder)
                val selectedList = tracks.filter { it.id in selectedIds }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onPickManualNcmFile,
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, StudioBorder),
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 12.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("自选文件", fontSize = 12.sp, color = TextPrimary)
                    }

                    Button(
                        onClick = { onImportTracks(selectedList) },
                        enabled = selectedList.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            contentColor = OnAccentLight
                        ),
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 12.dp)
                    ) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (selectedList.isEmpty()) {
                                "勾选曲目可批量导入（或直接点右侧「导入」）"
                            } else {
                                "批量导入已选 (${selectedList.size} 首 → 自动转 FLAC/MP3)"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun NetEaseTrackRowCell(
    item: NetEaseLocalTrack,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onSingleImportClick: () -> Unit
) {
    val badgeAccent = if (item.isLosslessFlac) EmeraldLossless else CyanPrimary

    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(12.dp),
        color = if (checked) CyanPrimary.copy(alpha = 0.08f) else StudioSurfaceElevated.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, if (checked) CyanPrimary else StudioBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = CyanPrimary,
                    checkmarkColor = OnAccentLight,
                    uncheckedColor = TextMuted
                )
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.displayTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = badgeAccent.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = item.badgeText,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = badgeAccent,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }

                    Text(
                        text = formatBytesShort(item.sizeBytes),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )

                    if (item.album.isNotBlank()) {
                        Text(
                            text = "· ${item.album}",
                            fontSize = 10.sp,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Button(
                onClick = onSingleImportClick,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                ),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 4.dp),
                modifier = Modifier.height(31.dp)
            ) {
                Text(
                    text = "导入 .${item.targetFormat}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUpdateBottomSheet(
    appUpdate: AppUpdateUiState,
    onCheckNow: () -> Unit,
    onSelectChannel: (UpdateDownloadChannel) -> Unit,
    onStartOrInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val release = appUpdate.latestRelease
    val isChecking = appUpdate.checkStatus == UpdateCheckStatus.CHECKING

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 6.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "应用版本更新",
                        fontFamily = FontFamily.Serif,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = buildString {
                            append("当前安装版本 v${appUpdate.currentVersionName}")
                            if (!appUpdate.lastCheckedTimeText.isNullOrBlank()) {
                                append(" · 上次检查 ${appUpdate.lastCheckedTimeText}")
                            }
                        },
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                val checkBtnShape = RoundedCornerShape(10.dp)
                Surface(
                    shape = checkBtnShape,
                    color = StudioSurfaceElevated,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier
                        .height(32.dp)
                        .clip(checkBtnShape)
                        .clickable(enabled = !isChecking && !appUpdate.isDownloading, onClick = onCheckNow)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.8.dp,
                                color = CyanPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isChecking) "检查中" else "检查更新",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Main Content Card
            when {
                appUpdate.hasUpdate && release != null -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioBg,
                        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = CyanPrimary
                                    ) {
                                        Text(
                                            text = "发现新版本 ${release.tagName}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = OnAccentLight,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                    if (release.apkSizeBytes > 0L) {
                                        Text(
                                            text = formatBytesShort(release.apkSizeBytes),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextSecondary
                                        )
                                    }
                                }

                                if (release.publishedAtText.isNotBlank()) {
                                    Text(
                                        text = release.publishedAtText,
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = release.releaseTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 150.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = release.releaseNotes,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Download Channel Selector (GitHub Direct vs Mirror Accelerated)
                    Text(
                        text = "下载路线（免流量服务器费用 · 基于 GitHub Release）",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        UpdateDownloadChannel.entries.forEach { ch ->
                            val selected = appUpdate.downloadChannel == ch
                            val chipShape = RoundedCornerShape(10.dp)
                            Surface(
                                shape = chipShape,
                                color = if (selected) CyanPrimary.copy(alpha = 0.14f) else StudioSurfaceElevated.copy(alpha = 0.7f),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) CyanPrimary else StudioBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(chipShape)
                                    .clickable(enabled = !appUpdate.isDownloading) {
                                        onSelectChannel(ch)
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = ch.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) CyanPrimary else TextPrimary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = appUpdate.downloadChannel.description,
                        fontSize = 11.sp,
                        color = TextMuted
                    )

                    if (!appUpdate.errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DangerRed.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = appUpdate.errorMessage,
                                fontSize = 11.sp,
                                color = DangerRed,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (appUpdate.isDownloading) {
                        val frac = if (appUpdate.totalBytes > 0L) {
                            (appUpdate.downloadedBytes.toFloat() / appUpdate.totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val pctInt = (frac * 100f).roundToInt()
                        val speedStr = if (appUpdate.speedBytesPerSec > 0L) {
                            "${formatBytesShort(appUpdate.speedBytesPerSec)}/s"
                        } else {
                            "正在连接..."
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "正在下载安装包 · $pctInt% ($speedStr)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CyanPrimary
                            )
                            Text(
                                text = if (appUpdate.totalBytes > 0L) {
                                    "${formatBytesShort(appUpdate.downloadedBytes)} / ${formatBytesShort(appUpdate.totalBytes)}"
                                } else {
                                    formatBytesShort(appUpdate.downloadedBytes)
                                },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = CyanPrimary,
                            trackColor = StudioSurfaceElevated
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onCancelDownload,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(11.dp),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f))
                        ) {
                            Text("暂停 / 取消下载", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DangerRed)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onStartOrInstall,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(11.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (appUpdate.hasReadyApk) EmeraldLossless else CyanPrimary,
                                    contentColor = OnAccentLight
                                ),
                                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = if (appUpdate.hasReadyApk) Icons.Rounded.CheckCircle else Icons.Rounded.SystemUpdateAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (appUpdate.hasReadyApk) {
                                        "安装包已就绪 · 立即安装"
                                    } else if (release.apkSizeBytes > 0L) {
                                        "下载并更新 (${formatBytesShort(release.apkSizeBytes)})"
                                    } else {
                                        "立即下载并更新"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }

                            OutlinedButton(
                                onClick = onOpenInBrowser,
                                shape = RoundedCornerShape(11.dp),
                                border = BorderStroke(1.dp, StudioBorder),
                                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.OpenInBrowser,
                                    contentDescription = "浏览器打开",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }

                appUpdate.checkStatus == UpdateCheckStatus.ERROR -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioBg,
                        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.40f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "暂时无法连接 GitHub 检查更新",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = appUpdate.errorMessage ?: "请检查手机网络连接，或点击下方按钮在浏览器查看 GitHub Release 页面。",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onCheckNow,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyanPrimary,
                                        contentColor = OnAccentLight
                                    )
                                ) {
                                    Text("重新检查", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                OutlinedButton(
                                    onClick = onOpenInBrowser,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, StudioBorder)
                                ) {
                                    Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text("浏览器打开 GitHub", fontSize = 12.sp, color = TextPrimary)
                                }
                            }
                        }
                    }
                }

                isChecking -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioBg,
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.2.dp,
                                color = CyanPrimary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "正在连接 GitHub Release 查询最新版本...",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }

                else -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioBg,
                        border = BorderStroke(1.dp, EmeraldLossless.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldLossless,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "当前已是最新版本 (v${appUpdate.currentVersionName})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "后续发布新版本后，打开 App 右上角会自动亮起「新版」提示，点一下即可在应用内直接下载覆盖升级（所有本地音轨都会保留）。",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onOpenInBrowser,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, StudioBorder)
                            ) {
                                Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("查看 GitHub Releases 页面", fontSize = 12.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProcessingProgressDialog(
    task: ProcessingTaskState,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = BorderStroke(1.dp, StudioBorder),
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = task.title,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(task.progress * 100).roundToInt()}%",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = CyanPrimary
                    )
                }

                Text(
                    text = task.subtitle,
                    fontSize = 12.sp,
                    color = TextSecondary
                )

                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = CyanPrimary,
                    trackColor = StudioSurfaceElevated
                )

                if (task.cancellable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onCancel) {
                            Text("取消处理", fontWeight = FontWeight.SemiBold, color = DangerRed)
                        }
                    }
                }
            }
        }
    }
}

private fun describePitchShift(semitones: Int, cents: Int): String {
    if (semitones == 0 && cents == 0) {
        return "原曲音调 · 未升降调"
    }
    val main = when {
        semitones > 0 -> "已升高 $semitones 个半音${if (semitones == 2) " (升1全音)" else ""}"
        semitones < 0 -> "已降低 ${abs(semitones)} 个半音${if (semitones == -2) " (降1全音)" else ""}"
        else -> "半音不变"
    }
    return if (cents == 0) main else "$main · ${if (cents > 0) "+$cents" else "$cents"}音分"
}

private fun formatDurationMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", min, sec)
}

private fun formatBytesShort(bytes: Long): String {
    if (bytes < 1024L) return "0 KB"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
