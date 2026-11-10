package com.soundtracker.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.soundtracker.app.dsp.ExportFormat
import com.soundtracker.app.dsp.VocalEngineMode
import com.soundtracker.app.storage.EphemeralStorageInfo
import com.soundtracker.app.storage.StoredResourceCategory
import com.soundtracker.app.storage.StoredResourceItem
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(
    uiState: StudioUiState,
    snackbarHostState: SnackbarHostState,
    onPickAudioClick: () -> Unit,
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
    onRunPitchShift: () -> Unit,
    onVolumeGainChange: (Float) -> Unit,
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
    onCancelProcessing: () -> Unit
) {
    var renameTargetTrack by remember { mutableStateOf<SessionTrack?>(null) }
    var saveTargetTrack by remember { mutableStateOf<SessionTrack?>(null) }
    var showStorageSheet by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = StudioBg,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding()
            )
        },
        topBar = {
            StudioTopBar(
                ephemeralBytes = uiState.storageInfo.totalEphemeralBytes,
                trackCount = uiState.tracks.size,
                onStoragePillClick = {
                    onRefreshStorage()
                    showStorageSheet = true
                },
                onImportClick = onPickAudioClick
            )
        }
    ) { innerPadding ->
        val activeTrack = uiState.selectedTrack

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (activeTrack == null) {
                item {
                    EmptyStudioHeroCard(onImportClick = onPickAudioClick)
                }
            } else {
                // 1. Active Waveform Player + A/B Audition + Delivery Actions
                item {
                    ActiveTrackDeckCard(
                        track = activeTrack,
                        hasOriginForAb = uiState.originComparisonTrack != null,
                        isComparingOriginal = uiState.isComparingOriginal,
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
                        onShareWeChatClick = { onShareWeChat(activeTrack.id) },
                        onShareMoreClick = { onShareSystemMore(activeTrack.id) },
                        onSaveAsClick = { saveTargetTrack = activeTrack }
                    )
                }

                // 2. Three Studio DSP Modules
                item {
                    StudioDspWorkbenchCard(
                        uiState = uiState,
                        activeTrack = activeTrack,
                        onSelectTab = onSelectTab,
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
                        onRunPitchShift = onRunPitchShift,
                        onVolumeGainChange = onVolumeGainChange,
                        onRunVolumeBoost = onRunVolumeBoost
                    )
                }
            }

            // 3. Persistent Workspace Track Tray
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
                                text = "点击任意音轨试听或继续处理 · 右上角扫把按钮可随时按需清理空间",
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
                        onRenameClick = { renameTargetTrack = item },
                        onShareWeChatClick = { onShareWeChat(item.id) },
                        onSaveAsClick = { saveTargetTrack = item },
                        onDeleteClick = { onDeleteTrack(item.id) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(28.dp))
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

    // Processing Progress Modal
    uiState.processingTask?.let { task ->
        ProcessingProgressDialog(
            task = task,
            onCancel = onCancelProcessing
        )
    }
}

@Composable
private fun StudioTopBar(
    ephemeralBytes: Long,
    trackCount: Int,
    onStoragePillClick: () -> Unit,
    onImportClick: () -> Unit
) {
    Surface(
        color = StudioBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CyanPrimary.copy(alpha = 0.14f))
                            .border(1.dp, CyanPrimary.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "SoundTracker",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "本地母带伴奏 · 移调变速工坊",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Broom + Storage Size Button -> Opens Resource Manager List
                    Surface(
                        onClick = onStoragePillClick,
                        shape = RoundedCornerShape(20.dp),
                        color = StudioSurface,
                        border = BorderStroke(1.dp, StudioBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CleaningServices,
                                contentDescription = "存储资源管理",
                                tint = CyanPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = formatBytesShort(ephemeralBytes),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }

                    if (trackCount > 0) {
                        Surface(
                            onClick = onImportClick,
                            shape = RoundedCornerShape(20.dp),
                            color = VioletAccent,
                            border = BorderStroke(1.dp, VioletAccent)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = OnAccentLight,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "导入",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OnAccentLight
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = StudioBorder.copy(alpha = 0.7f), thickness = 0.75.dp)
        }
    }
}

@Composable
private fun EmptyStudioHeroCard(onImportClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(CyanPrimary.copy(alpha = 0.12f))
                    .border(1.dp, CyanPrimary.copy(alpha = 0.32f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.LibraryMusic,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "导入音频，开启纯本地母带处理",
                fontFamily = FontFamily.Serif,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "支持 MP3 · M4A · FLAC · WAV · OGG 及视频提取音频\n自动识别原曲 BPM，支持去人声伴奏、1 BPM 精度变速与无电音移调",
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 6.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onImportClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 13.dp)
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("选择手机音频 / 视频文件", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(22.dp))
            HorizontalDivider(color = StudioBorder)
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FeaturePillBadge("双核母带去人声", CyanPrimary)
                FeaturePillBadge("1 BPM 移调变速", WarmUmber)
                FeaturePillBadge("无损防破音放大", EmeraldLossless)
            }
        }
    }
}

@Composable
private fun FeaturePillBadge(text: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveTrackDeckCard(
    track: SessionTrack,
    hasOriginForAb: Boolean,
    isComparingOriginal: Boolean,
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
    onShareWeChatClick: () -> Unit,
    onShareMoreClick: () -> Unit,
    onSaveAsClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isComparingOriginal) CyanPrimary else StudioBorder
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
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
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
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onRenameClick)
                    ) {
                        Text(
                            text = "${track.displayName}.${exportFormat.extension}",
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

                OutlinedButton(
                    onClick = onRenameClick,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, StudioBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DriveFileRenameOutline,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重命名", fontSize = 12.sp, color = TextPrimary)
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
    onRunPitchShift: () -> Unit,
    onVolumeGainChange: (Float) -> Unit,
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
                        semitones = uiState.pitchSemitones,
                        cents = uiState.pitchCents,
                        sourceBpm = uiState.sourceBpm,
                        targetBpm = uiState.targetBpm,
                        preserveTimbre = uiState.preserveTimbre,
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
                        gainDb = uiState.volumeGainDb,
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
                    "双核 AI 母带级去人声提取伴奏",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "彻底告别老一代模型人声残影与过电声 · 22.05kHz 全频段高保真重建",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        // 3 Studio Separation Architecture Cards
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
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = CyanPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = mode.title,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
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
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) CyanPrimary else TextMuted,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = mode.subtitle,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = selectedMode != VocalEngineMode.HTDEMUCS_FT) {
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("双向对称消噪增强", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text(
                            if (enableDeepDenoise) "已开启正反双遍推理抵消偏置噪声，静音前奏更纯净（耗时约翻倍）"
                            else "默认单遍极速推理已具备超净抑人声表现，遇到强底噪曲目可开启",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                    Switch(
                        checked = enableDeepDenoise,
                        onCheckedChange = onEnableDeepDenoiseChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnAccentLight,
                            checkedTrackColor = CyanPrimary
                        )
                    )
                }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("同时输出纯净清唱人声轨", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        if (keepVocalStem) "将同时生成「伴奏」与「纯人声」两条音轨"
                        else "仅生成高质量纯伴奏，保持工作台精简清爽",
                        fontSize = 11.sp,
                        color = TextMuted
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
                text = "对「$trackName」提取高质量伴奏",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PitchShiftAndTempoPanel(
    semitones: Int,
    cents: Int,
    sourceBpm: Int,
    targetBpm: Int,
    preserveTimbre: Boolean,
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = CyanPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        "母带级移调与 1 BPM 变速",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "拖动滑杆快速定位，点击左右 - / + 按 1 BPM 或 1 半音精调",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (hasPitchChange || hasBpmChange) {
                TextButton(
                    onClick = {
                        onSemitonesChange(0)
                        onCentsChange(0)
                        onTargetBpmChange(sourceBpm)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("全部复位", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CyanPrimary)
                }
            }
        }

        // ====================================================================
        // PART 1: Tempo (BPM) — [ - ]  ━━━━━━●━━━━━━  [ + ]
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
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "速度调节 (BPM)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (hasBpmChange) {
                                val sign = if (deltaBpm > 0) "+$deltaBpm" else "$deltaBpm"
                                String.format(Locale.US, "原速 %d BPM · 变速 %s BPM (%.2fx)", sourceBpm, sign, speedRatio)
                            } else {
                                "识别原速: $sourceBpm BPM (1.00x · 不变速)"
                            },
                            fontSize = 11.sp,
                            color = if (hasBpmChange) CyanPrimary else TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (hasBpmChange) {
                            Surface(
                                onClick = { onTargetBpmChange(sourceBpm) },
                                shape = RoundedCornerShape(7.dp),
                                color = StudioSurface,
                                border = BorderStroke(1.dp, StudioBorder)
                            ) {
                                Text(
                                    text = "原速",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (hasBpmChange) CyanPrimary else StudioSurface,
                            border = BorderStroke(1.dp, if (hasBpmChange) CyanPrimary else StudioBorder)
                        ) {
                            Text(
                                text = "$targetBpm BPM",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (hasBpmChange) OnAccentLight else TextPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
        // PART 2: Pitch (Semitone Key) — [ - ]  ━━━━━━●━━━━━━  [ + ]
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "半音升降调 (Key)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = describePitchShift(semitones, 0),
                            fontSize = 11.sp,
                            color = if (hasPitchChange) CyanPrimary else TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (hasPitchChange) {
                            Surface(
                                onClick = {
                                    onSemitonesChange(0)
                                    onCentsChange(0)
                                },
                                shape = RoundedCornerShape(7.dp),
                                color = StudioSurface,
                                border = BorderStroke(1.dp, StudioBorder)
                            ) {
                                Text(
                                    text = "原调",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (hasPitchChange) CyanPrimary else StudioSurface,
                            border = BorderStroke(1.dp, if (hasPitchChange) CyanPrimary else StudioBorder)
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
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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

        // Formant Preservation Switch
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("人声共振峰保护（纯伴奏请保持关闭）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        if (preserveTimbre) "已开启 R3 多频段共振峰锁定：适合含主唱原曲防变声；对纯伴奏建议关闭"
                        else "默认关闭（推荐纯伴奏）：严格锁定全频段整数倍泛音比例，彻底告别电音染色",
                        fontSize = 11.sp,
                        color = TextMuted
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
                "移调 + 变速一次完成 ($pStr · ${sourceBpm}→${targetBpm} BPM)"
            }
            hasPitchChange -> {
                val pStr = if (semitones > 0) "+$semitones 半音" else "$semitones 半音"
                "执行母带移调 ($pStr · 保持 $sourceBpm BPM)"
            }
            hasBpmChange -> {
                "执行变速不变调 ($sourceBpm → $targetBpm BPM · 原调)"
            }
            else -> "执行移调 / 变速（请先调整上方速度或升降调）"
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
    gainDb: Float,
    onGainChange: (Float) -> Unit,
    onRun: () -> Unit
) {
    val metrics = track.metrics
    val headroom = metrics.pureLosslessHeadroomDb
    val isPureLosslessZone = gainDb <= headroom + 0.05f

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = EmeraldLossless)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    "尽量无损的母带级音量放大",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "余量内 100% 纯线性零失真放大 · 超余量启动 5ms 前瞻真峰值防破音保护",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
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
                label = "4x真峰值",
                value = String.format(Locale.US, "%.1f dBTP", metrics.truePeakDbtp),
                accent = CyanPrimary,
                modifier = Modifier.weight(1f)
            )
            MetricMiniCard(
                label = "纯无损余量",
                value = String.format(Locale.US, "+%.1f dB", headroom),
                accent = EmeraldLossless,
                modifier = Modifier.weight(1f)
            )
        }

        // Smart 1-Tap Presets
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    onGainChange(if (headroom >= 0.2f) headroom else 1.5f)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, EmeraldLossless.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
            ) {
                Text("纯无损满幅", fontSize = 11.sp, color = EmeraldLossless, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = {
                    val target = if (metrics.integratedLufs > -60f) {
                        (-11.0f - metrics.integratedLufs).coerceIn(1.5f, 14f)
                    } else 4.5f
                    onGainChange(target)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
            ) {
                Text("标准伴奏响度", fontSize = 11.sp, color = CyanPrimary, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = {
                    val target = if (metrics.integratedLufs > -60f) {
                        (-8.5f - metrics.integratedLufs).coerceIn(3.0f, 16f)
                    } else 7.5f
                    onGainChange(target)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AmberMastering.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
            ) {
                Text("舞台强劲响度", fontSize = 11.sp, color = AmberMastering, fontWeight = FontWeight.SemiBold)
            }
        }

        // Gain Slider
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(Locale.US, "放大增益: +%.1f dB", gainDb),
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isPureLosslessZone) EmeraldLossless else AmberMastering
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { onGainChange(gainDb - 0.5f) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("-0.5dB", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = { onGainChange(gainDb + 0.5f) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("+0.5dB", fontSize = 11.sp) }
                }
            }

            Slider(
                value = gainDb,
                onValueChange = onGainChange,
                valueRange = 0f..18f,
                colors = SliderDefaults.colors(
                    thumbColor = if (isPureLosslessZone) EmeraldLossless else AmberMastering,
                    activeTrackColor = if (isPureLosslessZone) EmeraldLossless else AmberMastering,
                    inactiveTrackColor = StudioBorder
                )
            )
        }

        // Dynamic Lossless vs True-Peak Limiter Status Banner
        val modeColor = if (isPureLosslessZone) EmeraldLossless else AmberMastering
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = modeColor.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, modeColor.copy(alpha = 0.35f))
        ) {
            Text(
                text = if (isPureLosslessZone) {
                    "当前处于【100% 纯线性无损区】：峰值未触及上限，仅执行高精度浮点比例放大，动态范围与波形比例零改变。"
                } else {
                    "当前处于【母带前瞻防破音保护区】：安静段落纯线性放大，仅对超出 -0.2 dBTP 的鼓点瞬态执行 5ms 前瞻平滑限幅，音量响亮且绝不爆音。"
                },
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = modeColor,
                modifier = Modifier.padding(11.dp)
            )
        }

        Button(
            onClick = onRun,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = EmeraldLossless,
                contentColor = OnAccentLight
            ),
            contentPadding = PaddingValues(vertical = 13.dp)
        ) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = String.format(Locale.US, "执行音量放大 (+%.1f dB · 零破音)", gainDb),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
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
            Text(label, fontSize = 10.sp, color = TextMuted)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.displayName,
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
                    IconButton(onClick = onRenameClick, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.DriveFileRenameOutline,
                            contentDescription = "重命名",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onShareWeChatClick, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "分享微信",
                            tint = WeChatGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onSaveAsClick, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.SaveAlt,
                            contentDescription = "另存为本地",
                            tint = CyanPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDeleteClick, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "删除音轨",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
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

    // Prune any paths that were already deleted
    LaunchedEffect(resources) {
        val valid = resources.mapTo( hashSetOf() ) { it.filePath }
        selectedPaths = selectedPaths.intersect(valid)
    }

    val allSelected = resources.isNotEmpty() && selectedPaths.size == resources.size
    val selectedBytes = resources.filter { it.filePath in selectedPaths }.sumOf { it.sizeBytes }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        color = TextPrimary
                    )
                    Text(
                        text = "共 ${resources.size} 个音频资源 · 总计占用 ${formatBytesShort(storageInfo.totalEphemeralBytes)}",
                        fontSize = 12.sp,
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
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
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

            Spacer(modifier = Modifier.height(6.dp))
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
            modifier = Modifier.fillMaxWidth()
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
        return "当前保持原调 (0 半音)"
    }
    val main = when {
        semitones > 0 -> "升高 $semitones 个半音 (${if (semitones == 2) "升1全音" else "+$semitones key"})"
        semitones < 0 -> "降低 ${abs(semitones)} 个半音 (${if (semitones == -2) "降1全音 · 轻松唱高音" else "$semitones key"})"
        else -> "半音不变"
    }
    return if (cents == 0) main else "$main · 微调 ${if (cents > 0) "+$cents" else "$cents"} 音分"
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
