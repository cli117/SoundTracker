package com.soundtracker.app.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundtracker.app.arranger.ArrangerClip
import com.soundtracker.app.arranger.ArrangerTrack
import com.soundtracker.app.arranger.ArrangerUiState
import com.soundtracker.app.arranger.MAX_ARRANGER_TRACKS
import com.soundtracker.app.arranger.MIN_CLIP_DURATION_MS
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val CLIP_ACCENT_PALETTE = listOf(
    Color(0xFFC96442), // Terracotta
    Color(0xFF2D6A4F), // Botanical Sage
    Color(0xFFB56E22), // Warm Ochre
    Color(0xFF2F6673), // Deep Slate Teal
    Color(0xFF8F4557), // Muted Plum Berry
    Color(0xFF6B5344)  // Roasted Umber
)

private val TRACK_HEADER_WIDTH = 94.dp
private val TRACK_ROW_HEIGHT = 76.dp
private val ADD_TRACK_ROW_HEIGHT = 48.dp

private enum class TimelineTouchMode {
    UNDETERMINED,
    SCRUB_TIMELINE,
    SCROLL_VERTICAL,
    PINCH_ZOOM,
    MOVE_CLIP,
    TRIM_LEFT,
    TRIM_RIGHT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiTrackArrangerScreen(
    arranger: ArrangerUiState,
    workspaceTracks: List<SessionTrack>,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearProject: () -> Unit,
    onExportMixdown: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    onAddTrack: () -> Unit,
    onSelectTrack: (String) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onToggleTrackSolo: (String) -> Unit,
    onTrackVolumeChange: (String, Float) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onSelectClip: (String?) -> Unit,
    onBeginGestureUndo: () -> Unit,
    onAddClipFromWorkspace: (sessionTrackId: String, inMs: Long?, outMs: Long?, placeAtPlayhead: Boolean) -> Unit,
    onOpenNetEaseImport: () -> Unit,
    onPickLocalAudio: () -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onDeleteSelectedClip: () -> Unit,
    onDuplicateSelectedClip: () -> Unit,
    onMoveClipRealtime: (clipId: String, newStartMs: Long, targetTrackIdx: Int) -> Unit,
    onMoveSelectedClipTrack: (deltaTrack: Int) -> Unit,
    onNudgeSelectedClipPos: (deltaMs: Long) -> Unit,
    onSnapSelectedToPlayhead: () -> Unit,
    onSnapSelectedToPrevious: () -> Unit,
    onTrimClipRealtime: (clipId: String, newTimelineStartMs: Long, newSourceInMs: Long, newSourceOutMs: Long) -> Unit,
    onNudgeSelectedClipTrim: (isLeft: Boolean, deltaMs: Long) -> Unit,
    onSelectedClipGainChange: (Float) -> Unit,
    onSelectedClipFadesChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddClipSheet by rememberSaveable { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var inspectorSubTab by rememberSaveable { mutableIntStateOf(0) } // 0 = 裁剪与位置, 1 = 音量与淡化
    var fineTargetMode by rememberSaveable { mutableIntStateOf(0) }  // 0 = 位置, 1 = 起点, 2 = 终点

    val canClear = arranger.totalClipCount > 0 || arranger.tracks.size > 1

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBg)
            .statusBarsPadding()
    ) {
        val isTablet = maxWidth >= 720.dp
        val isShortLandscape = maxHeight < 480.dp
        val trackHeaderWidth = if (isTablet) 126.dp else TRACK_HEADER_WIDTH
        val trackRowHeight = when {
            isShortLandscape -> 62.dp
            isTablet -> 84.dp
            else -> TRACK_ROW_HEIGHT
        }
        val dockHeight = if (isShortLandscape) 136.dp else 154.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // ================================================================
            // 1. Fixed Top Header Bar (52.dp / 46.dp on short landscape)
            // ================================================================
            Surface(
                color = StudioSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isShortLandscape) 46.dp else 52.dp)
                        .padding(horizontal = if (isTablet) 18.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactHeaderBtn(
                        icon = Icons.AutoMirrored.Rounded.Undo,
                        label = "撤销",
                        enabled = arranger.canUndo,
                        onClick = onUndo
                    )
                    CompactHeaderBtn(
                        icon = Icons.AutoMirrored.Rounded.Redo,
                        label = "重做",
                        enabled = arranger.canRedo,
                        onClick = onRedo
                    )
                    val clearShape = RoundedCornerShape(8.dp)
                    Surface(
                        shape = clearShape,
                        color = StudioSurfaceElevated.copy(alpha = if (canClear) 0.85f else 0.4f),
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .height(32.dp)
                            .clip(clearShape)
                            .clickable(enabled = canClear) { showClearConfirm = true }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 9.dp)
                        ) {
                            Text(
                                text = "重置",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (canClear) TextSecondary else TextMuted.copy(alpha = 0.45f),
                                maxLines = 1
                            )
                        }
                    }
                }

                val hasClips = arranger.totalClipCount > 0
                val exportShape = RoundedCornerShape(10.dp)
                Surface(
                    shape = exportShape,
                    color = if (hasClips) CyanPrimary else StudioSurfaceElevated,
                    border = BorderStroke(1.dp, if (hasClips) CyanPrimary else StudioBorder),
                    modifier = Modifier
                        .height(34.dp)
                        .clip(exportShape)
                        .clickable(enabled = hasClips, onClick = onExportMixdown)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = if (hasClips) OnAccentLight else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (hasClips) "合成新音轨" else "合成音轨",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (hasClips) OnAccentLight else TextMuted,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = StudioBorder)

        // ====================================================================
        // 2. Fixed Transport & Zoom Bar (46.dp)
        // ====================================================================
        Surface(
            color = StudioBg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val rewindShape = RoundedCornerShape(8.dp)
                    Surface(
                        shape = rewindShape,
                        color = StudioSurface,
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .size(32.dp)
                            .clip(rewindShape)
                            .clickable { onSeekToMs(0L) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "回到开头",
                                tint = TextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    val playShape = CircleShape
                    Surface(
                        shape = playShape,
                        color = if (arranger.isPlaying) CyanPrimary else VioletAccent,
                        modifier = Modifier
                            .height(32.dp)
                            .clip(playShape)
                            .clickable(onClick = onTogglePlayPause)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = if (arranger.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = OnAccentLight,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (arranger.isPlaying) "暂停" else "播放",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OnAccentLight,
                                maxLines = 1
                            )
                        }
                    }

                    Text(
                        text = "${formatTimeTenths(arranger.playheadMs)} / ${formatTimeTenths(arranger.totalDurationMs)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MiniSquareBtn("−") { onZoomChange(arranger.zoomDpPerSec / 1.35f) }
                    MiniSquareBtn("+") { onZoomChange(arranger.zoomDpPerSec * 1.35f) }

                    val addClipShape = RoundedCornerShape(9.dp)
                    Surface(
                        shape = addClipShape,
                        color = CyanPrimary.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .height(32.dp)
                            .clip(addClipShape)
                            .clickable { showAddClipSheet = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 9.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "加片段",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = StudioBorder)

        // ====================================================================
        // 3. Synchronized 2D Multi-Track DAW Stage (weight = 1f)
        // ====================================================================
        MultiTrackTimelineStage(
            arranger = arranger,
            trackHeaderWidth = trackHeaderWidth,
            trackRowHeight = trackRowHeight,
            onSeekToMs = onSeekToMs,
            onZoomChange = onZoomChange,
            onAddTrack = onAddTrack,
            onSelectTrack = onSelectTrack,
            onToggleTrackMute = onToggleTrackMute,
            onToggleTrackSolo = onToggleTrackSolo,
            onRemoveTrack = onRemoveTrack,
            onSelectClip = onSelectClip,
            onOpenAddClipSheet = { showAddClipSheet = true },
            onBeginGestureUndo = onBeginGestureUndo,
            onMoveClipRealtime = onMoveClipRealtime,
            onTrimClipRealtime = onTrimClipRealtime,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        HorizontalDivider(color = StudioBorder)

        // ====================================================================
        // 4. Constant-Height Bottom Context Dock (Responsive 154.dp / 136.dp)
        // ====================================================================
        ArrangerConstantBottomDock(
            arranger = arranger,
            inspectorSubTab = inspectorSubTab,
            onInspectorSubTabChange = { inspectorSubTab = it },
            fineTargetMode = fineTargetMode,
            onFineTargetModeChange = { fineTargetMode = it },
            onSplitAtPlayhead = onSplitAtPlayhead,
            onOpenAddClipSheet = { showAddClipSheet = true },
            onAddTrack = onAddTrack,
            onTrackVolumeChange = onTrackVolumeChange,
            onRemoveOrClearTrack = { onRemoveTrack(arranger.selectedTrack.id) },
            onClearClipSelection = { onSelectClip(null) },
            onDeleteSelectedClip = onDeleteSelectedClip,
            onDuplicateSelectedClip = onDuplicateSelectedClip,
            onMoveSelectedClipTrack = onMoveSelectedClipTrack,
            onNudgeSelectedClipPos = onNudgeSelectedClipPos,
            onSnapSelectedToPlayhead = onSnapSelectedToPlayhead,
            onSnapSelectedToPrevious = onSnapSelectedToPrevious,
            onNudgeSelectedClipTrim = onNudgeSelectedClipTrim,
            onSelectedClipGainChange = onSelectedClipGainChange,
            onSelectedClipFadesChange = onSelectedClipFadesChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(dockHeight)
        )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = StudioSurface,
            title = {
                Text("重置多轨混剪台？", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Text(
                    "将清空当前所有轨道上的切片并恢复为默认的 1 条空音轨（单曲工作台里的原音频文件不会被删除，仍可点击撤销恢复）。",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearProject()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = OnAccentLight)
                ) {
                    Text("确认重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    if (showAddClipSheet) {
        AddArrangerClipBottomSheet(
            targetTrackName = arranger.selectedTrack.name,
            workspaceTracks = workspaceTracks,
            onAddClip = { trackId, inMs, outMs, atPlayhead ->
                showAddClipSheet = false
                onAddClipFromWorkspace(trackId, inMs, outMs, atPlayhead)
            },
            onOpenNetEase = {
                showAddClipSheet = false
                onOpenNetEaseImport()
            },
            onPickLocal = {
                showAddClipSheet = false
                onPickLocalAudio()
            },
            onDismiss = { showAddClipSheet = false }
        )
    }
}

@Composable
private fun MultiTrackTimelineStage(
    arranger: ArrangerUiState,
    trackHeaderWidth: Dp = TRACK_HEADER_WIDTH,
    trackRowHeight: Dp = TRACK_ROW_HEIGHT,
    onSeekToMs: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    onAddTrack: () -> Unit,
    onSelectTrack: (String) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onToggleTrackSolo: (String) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onSelectClip: (String?) -> Unit,
    onOpenAddClipSheet: () -> Unit,
    onBeginGestureUndo: () -> Unit,
    onMoveClipRealtime: (String, Long, Int) -> Unit,
    onTrimClipRealtime: (String, Long, Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val vScroll = rememberScrollState()

    val rowHeightPx = with(density) { trackRowHeight.toPx() }
    val addRowHeightPx = with(density) { ADD_TRACK_ROW_HEIGHT.toPx() }
    val pxPerSec = with(density) { arranger.zoomDpPerSec.dp.toPx() }.coerceAtLeast(1f)
    val pxPerMs = pxPerSec / 1000f

    var laneWidthPx by remember { mutableFloatStateOf(600f) }
    var snapGuideMs by remember { mutableStateOf<Long?>(null) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    val currentArranger by rememberUpdatedState(arranger)
    val currentRowHeightPx by rememberUpdatedState(rowHeightPx)
    val currentPxPerMs by rememberUpdatedState(pxPerMs)
    val currentLaneWidthPx by rememberUpdatedState(laneWidthPx)
    val currentOnSeek by rememberUpdatedState(onSeekToMs)
    val currentOnZoom by rememberUpdatedState(onZoomChange)
    val currentOnSelectTrack by rememberUpdatedState(onSelectTrack)
    val currentOnSelectClip by rememberUpdatedState(onSelectClip)
    val currentOnAddTrack by rememberUpdatedState(onAddTrack)
    val currentOnOpenAddClip by rememberUpdatedState(onOpenAddClipSheet)
    val currentOnBeginUndo by rememberUpdatedState(onBeginGestureUndo)
    val currentOnMoveClip by rememberUpdatedState(onMoveClipRealtime)
    val currentOnTrimClip by rememberUpdatedState(onTrimClipRealtime)

    Column(modifier = modifier.background(StudioSurfaceElevated.copy(alpha = 0.55f))) {
        // ====================================================================
        // Top Row: Track Counter Cell + Synchronized Time Ruler
        // ====================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(StudioSurface)
        ) {
            Box(
                modifier = Modifier
                    .width(trackHeaderWidth)
                    .fillMaxHeight()
                    .border(width = 0.5.dp, color = StudioBorder),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "音轨 ${arranger.tracks.size}/$MAX_ARRANGER_TRACKS",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    maxLines = 1
                )
            }

            // Interactive Scrubbable Time Ruler
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .onSizeChanged { if (it.width > 0) laneWidthPx = it.width.toFloat() }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            flingJob?.cancel()
                            var startMs = currentArranger.playheadMs
                            var totalDx = 0f
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val dx = ch.positionChange().x
                                totalDx += dx
                                ch.consume()
                                val target = (startMs - (totalDx / currentPxPerMs).roundToLong()).coerceAtLeast(0L)
                                currentOnSeek(target)
                            }
                        }
                    }
            ) {
                val centerX = size.width / 2f
                val majorStepMs = chooseMajorTickMs(arranger.zoomDpPerSec)
                val minorStepMs = majorStepMs / 5L
                val halfSpanMs = ((size.width / 2f) / pxPerMs).roundToLong() + majorStepMs
                val firstTickMs = (((arranger.playheadMs - halfSpanMs).coerceAtLeast(0L)) / minorStepMs) * minorStepMs
                val lastTickMs = arranger.playheadMs + halfSpanMs

                val textPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.argb(200, 94, 91, 82)
                    textSize = 10.sp.toPx()
                    typeface = android.graphics.Typeface.MONOSPACE
                }

                var t = firstTickMs
                while (t <= lastTickMs) {
                    val x = centerX + (t - arranger.playheadMs) * pxPerMs
                    if (x in -20f..(size.width + 20f)) {
                        val isMajor = (t % majorStepMs == 0L)
                        drawLine(
                            color = if (isMajor) TextSecondary.copy(alpha = 0.65f) else StudioBorder,
                            start = Offset(x, if (isMajor) size.height * 0.52f else size.height * 0.72f),
                            end = Offset(x, size.height),
                            strokeWidth = if (isMajor) 1.3f else 1f
                        )
                        if (isMajor) {
                            drawContext.canvas.nativeCanvas.drawText(
                                formatRulerTick(t),
                                x + 3.dp.toPx(),
                                size.height * 0.56f,
                                textPaint
                            )
                        }
                    }
                    t += minorStepMs
                }

                // Fixed Center Playhead Cap Triangle
                val capHalf = 5.dp.toPx()
                val tri = Path().apply {
                    moveTo(centerX - capHalf, 0f)
                    lineTo(centerX + capHalf, 0f)
                    lineTo(centerX, 7.dp.toPx())
                    close()
                }
                drawPath(tri, color = TextPrimary)
            }
        }

        HorizontalDivider(color = StudioBorder)

        // ====================================================================
        // Synchronized Left Headers + Right Multi-Track Waveform Canvas
        // ====================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // LEFT STICKY TRACK HEADERS COLUMN (Synced vertically via vScroll)
                Column(
                    modifier = Modifier
                        .width(trackHeaderWidth)
                        .fillMaxHeight()
                        .background(StudioSurface)
                        .verticalScroll(vScroll)
                ) {
                    val anySolo = arranger.tracks.any { it.solo }
                    arranger.tracks.forEachIndexed { index, track ->
                        val isTrackSelected = track.id == arranger.effectiveSelectedTrackId
                        val accent = CLIP_ACCENT_PALETTE[track.colorIndex % CLIP_ACCENT_PALETTE.size]
                        val effectivelyAudible = if (anySolo) track.solo else !track.muted

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(trackRowHeight)
                                .background(
                                    if (isTrackSelected) accent.copy(alpha = 0.10f) else StudioSurface
                                )
                                .border(width = 0.5.dp, color = StudioBorder)
                                .clickable { onSelectTrack(track.id) }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            // Left colored indicator bar
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (isTrackSelected) accent else accent.copy(alpha = 0.35f))
                            )

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 7.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = track.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isTrackSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (effectivelyAudible) TextPrimary else TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (arranger.tracks.size > 1) {
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable { onRemoveTrack(track.id) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "删除音轨",
                                                tint = TextMuted,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TrackMiniToggle(
                                        label = "静音",
                                        active = track.muted,
                                        activeColor = DangerRed,
                                        onClick = { onToggleTrackMute(track.id) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    TrackMiniToggle(
                                        label = "独奏",
                                        active = track.solo,
                                        activeColor = AmberMastering,
                                        onClick = { onToggleTrackSolo(track.id) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // Bottom "+" Add Track Header Cell (up to 10 tracks)
                    val canAddMore = arranger.tracks.size < MAX_ARRANGER_TRACKS
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ADD_TRACK_ROW_HEIGHT)
                            .background(StudioSurface)
                            .border(width = 0.5.dp, color = StudioBorder)
                            .clickable(enabled = canAddMore, onClick = onAddTrack),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = if (canAddMore) CyanPrimary else TextMuted.copy(alpha = 0.45f),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = if (canAddMore) "加音轨" else "已满10轨",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (canAddMore) CyanPrimary else TextMuted.copy(alpha = 0.5f),
                                maxLines = 1
                            )
                        }
                    }
                }

                // RIGHT MULTI-TRACK WAVEFORM LANES CANVAS (Unified Gesture Engine)
                val decaySpec = splineBasedDecay<Float>(density)
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .onSizeChanged { if (it.width > 0) laneWidthPx = it.width.toFloat() }
                        .pointerInput(Unit) {
                            val touchSlopPx = viewConfiguration.touchSlop
                            val trimHandleZonePx = 20.dp.toPx()
                            val snapThresholdPx = 10.dp.toPx()

                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                flingJob?.cancel()
                                snapGuideMs = null

                                val downX = down.position.x
                                val downY = down.position.y + vScroll.value
                                val centerX = currentLaneWidthPx / 2f
                                val initArr = currentArranger
                                val downRow = floor(downY / currentRowHeightPx).toInt()

                                // Check if user touched a clip or selected clip's left/right trim handle
                                var hitClip: ArrangerClip? = null
                                var hitTrackIdx = -1
                                var mode = TimelineTouchMode.UNDETERMINED

                                if (downRow in initArr.tracks.indices) {
                                    val trk = initArr.tracks[downRow]
                                    // First check selected clip trim handles on this row
                                    val selOnRow = trk.clips.firstOrNull { it.id == initArr.selectedClipId }
                                    if (selOnRow != null) {
                                        val cLeft = centerX + (selOnRow.timelineStartMs - initArr.playheadMs) * currentPxPerMs
                                        val cRight = centerX + (selOnRow.timelineEndMs - initArr.playheadMs) * currentPxPerMs
                                        if (downX in (cLeft - 8.dp.toPx())..(cLeft + trimHandleZonePx)) {
                                            hitClip = selOnRow
                                            hitTrackIdx = downRow
                                            mode = TimelineTouchMode.TRIM_LEFT
                                            currentOnBeginUndo()
                                        } else if (downX in (cRight - trimHandleZonePx)..(cRight + 8.dp.toPx())) {
                                            hitClip = selOnRow
                                            hitTrackIdx = downRow
                                            mode = TimelineTouchMode.TRIM_RIGHT
                                            currentOnBeginUndo()
                                        }
                                    }

                                    if (mode == TimelineTouchMode.UNDETERMINED) {
                                        // Hit test clip body (topmost = last drawn)
                                        val bodyHit = trk.clips.lastOrNull { c ->
                                            val cLeft = centerX + (c.timelineStartMs - initArr.playheadMs) * currentPxPerMs
                                            val cRight = centerX + (c.timelineEndMs - initArr.playheadMs) * currentPxPerMs
                                            downX in cLeft..cRight
                                        }
                                        if (bodyHit != null) {
                                            hitClip = bodyHit
                                            hitTrackIdx = downRow
                                        }
                                    }
                                }

                                val origClipStartMs = hitClip?.timelineStartMs ?: 0L
                                val origClipInMs = hitClip?.sourceInMs ?: 0L
                                val origClipOutMs = hitClip?.sourceOutMs ?: 0L
                                val origClipDurMs = hitClip?.durationMs ?: 0L
                                val origPlayheadMs = initArr.playheadMs

                                val downTimeMillis = System.currentTimeMillis()
                                val velocityTracker = VelocityTracker()
                                velocityTracker.addPosition(down.uptimeMillis, down.position)

                                var totalDx = 0f
                                var totalDy = 0f
                                var movedPastSlop = false
                                var activePointerId: PointerId = down.id

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressedChanges = event.changes.filter { it.pressed }
                                    if (pressedChanges.isEmpty()) break

                                    // Switch to 2-finger pinch zoom immediately when 2+ pointers are active
                                    if (pressedChanges.size >= 2 &&
                                        mode != TimelineTouchMode.MOVE_CLIP &&
                                        mode != TimelineTouchMode.TRIM_LEFT &&
                                        mode != TimelineTouchMode.TRIM_RIGHT
                                    ) {
                                        mode = TimelineTouchMode.PINCH_ZOOM
                                        val zoomFactor = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        if (zoomFactor != 1f && event.calculateCentroidSize(useCurrent = false) > 10f) {
                                            currentOnZoom(currentArranger.zoomDpPerSec * zoomFactor)
                                        }
                                        if (abs(pan.x) > 0.2f) {
                                            val nextPh = (currentArranger.playheadMs - (pan.x / currentPxPerMs).roundToLong())
                                                .coerceAtLeast(0L)
                                            currentOnSeek(nextPh)
                                        }
                                        event.changes.forEach { it.consume() }
                                        continue
                                    }

                                    if (mode == TimelineTouchMode.PINCH_ZOOM) {
                                        event.changes.forEach { it.consume() }
                                        continue
                                    }

                                    val change = pressedChanges.firstOrNull { it.id == activePointerId }
                                        ?: pressedChanges.first().also { activePointerId = it.id }

                                    val delta = change.positionChange()
                                    totalDx += delta.x
                                    totalDy += delta.y
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)

                                    // Upgrade press on clip to MOVE_CLIP if clip was already selected & dragged,
                                    // or if held >= 150ms before dragging past touch slop
                                    if (mode == TimelineTouchMode.UNDETERMINED && hitClip != null) {
                                        val heldMs = System.currentTimeMillis() - downTimeMillis
                                        val dist = kotlin.math.hypot(totalDx, totalDy)
                                        if ((hitClip.id == initArr.selectedClipId && dist > touchSlopPx) ||
                                            (heldMs >= 150L && dist > touchSlopPx * 0.6f)
                                        ) {
                                            mode = TimelineTouchMode.MOVE_CLIP
                                            movedPastSlop = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            currentOnSelectClip(hitClip.id)
                                            currentOnBeginUndo()
                                        }
                                    }

                                    if (mode == TimelineTouchMode.UNDETERMINED) {
                                        if (abs(totalDx) > touchSlopPx || abs(totalDy) > touchSlopPx) {
                                            movedPastSlop = true
                                            mode = if (abs(totalDx) >= abs(totalDy)) {
                                                TimelineTouchMode.SCRUB_TIMELINE
                                            } else {
                                                TimelineTouchMode.SCROLL_VERTICAL
                                            }
                                        }
                                    }

                                    when (mode) {
                                        TimelineTouchMode.SCRUB_TIMELINE -> {
                                            change.consume()
                                            val targetMs = (origPlayheadMs - (totalDx / currentPxPerMs).roundToLong())
                                                .coerceAtLeast(0L)
                                            currentOnSeek(targetMs)
                                        }

                                        TimelineTouchMode.SCROLL_VERTICAL -> {
                                            change.consume()
                                            vScroll.dispatchRawDelta(-delta.y)
                                        }

                                        TimelineTouchMode.MOVE_CLIP -> {
                                            change.consume()
                                            val c = hitClip ?: continue
                                            val rawStartMs = (origClipStartMs + (totalDx / currentPxPerMs).roundToLong())
                                                .coerceAtLeast(0L)
                                            val snapRangeMs = (snapThresholdPx / currentPxPerMs).roundToLong().coerceAtLeast(15L)
                                            val (snappedStart, guideMs) = computeMagneticSnap(
                                                movingClipId = c.id,
                                                rawStartMs = rawStartMs,
                                                durationMs = origClipDurMs,
                                                arranger = currentArranger,
                                                snapThresholdMs = snapRangeMs
                                            )
                                            snapGuideMs = guideMs

                                            val curFingerContentY = change.position.y + vScroll.value
                                            val dstTrackIdx = floor(curFingerContentY / currentRowHeightPx).toInt()
                                                .coerceIn(0, currentArranger.tracks.lastIndex)
                                            currentOnMoveClip(c.id, snappedStart, dstTrackIdx)
                                        }

                                        TimelineTouchMode.TRIM_LEFT -> {
                                            change.consume()
                                            val c = hitClip ?: continue
                                            val rawDeltaMs = (totalDx / currentPxPerMs).roundToLong()
                                            val maxRight = origClipDurMs - MIN_CLIP_DURATION_MS
                                            val maxLeft = minOf(origClipInMs, origClipStartMs)
                                            val clampedDelta = rawDeltaMs.coerceIn(-maxLeft, maxRight)
                                            currentOnTrimClip(
                                                c.id,
                                                origClipStartMs + clampedDelta,
                                                origClipInMs + clampedDelta,
                                                origClipOutMs
                                            )
                                        }

                                        TimelineTouchMode.TRIM_RIGHT -> {
                                            change.consume()
                                            val c = hitClip ?: continue
                                            val rawDeltaMs = (totalDx / currentPxPerMs).roundToLong()
                                            val nextOut = (origClipOutMs + rawDeltaMs).coerceIn(
                                                origClipInMs + MIN_CLIP_DURATION_MS,
                                                c.sourceDurationMs
                                            )
                                            currentOnTrimClip(
                                                c.id,
                                                origClipStartMs,
                                                origClipInMs,
                                                nextOut
                                            )
                                        }

                                        TimelineTouchMode.UNDETERMINED,
                                        TimelineTouchMode.PINCH_ZOOM -> Unit
                                    }
                                }

                                snapGuideMs = null

                                // Handle clean single tap vs timeline fling release
                                if (!movedPastSlop && mode == TimelineTouchMode.UNDETERMINED) {
                                    val tracksSize = currentArranger.tracks.size
                                    val addRowTop = tracksSize * currentRowHeightPx
                                    val addRowBottom = addRowTop + addRowHeightPx
                                    when {
                                        hitClip != null -> {
                                            currentOnSelectClip(hitClip.id)
                                        }
                                        downRow in 0 until tracksSize -> {
                                            val tappedTrack = currentArranger.tracks[downRow]
                                            currentOnSelectTrack(tappedTrack.id)
                                            if (currentArranger.selectedClipId != null) {
                                                currentOnSelectClip(null)
                                            } else if (tappedTrack.clips.isEmpty()) {
                                                // Tapping an already-selected empty track lane opens "+ 加片段" directly!
                                                if (tappedTrack.id == currentArranger.effectiveSelectedTrackId) {
                                                    currentOnOpenAddClip()
                                                }
                                            }
                                        }
                                        downY in addRowTop..addRowBottom && tracksSize < MAX_ARRANGER_TRACKS -> {
                                            currentOnAddTrack()
                                        }
                                        else -> {
                                            currentOnSelectClip(null)
                                        }
                                    }
                                } else if (mode == TimelineTouchMode.SCRUB_TIMELINE) {
                                    val vx = velocityTracker.calculateVelocity().x
                                    if (abs(vx) > 180f) {
                                        val startPh = currentArranger.playheadMs.toFloat()
                                        val ppm = currentPxPerMs
                                        flingJob = scope.launch {
                                            var lastPx = 0f
                                            var curMs = startPh
                                            AnimationState(initialValue = 0f, initialVelocity = vx)
                                                .animateDecay(decaySpec) {
                                                    val stepPx = value - lastPx
                                                    lastPx = value
                                                    curMs = (curMs - stepPx / ppm).coerceAtLeast(0f)
                                                    currentOnSeek(curMs.roundToLong())
                                                    if (curMs <= 0f) cancelAnimation()
                                                }
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    val centerX = size.width / 2f
                    val scrollY = vScroll.value.toFloat()
                    val majorStepMs = chooseMajorTickMs(arranger.zoomDpPerSec)
                    val halfSpanMs = ((size.width / 2f) / pxPerMs).roundToLong() + majorStepMs
                    val firstGridMs = (((arranger.playheadMs - halfSpanMs).coerceAtLeast(0L)) / majorStepMs) * majorStepMs
                    val lastGridMs = arranger.playheadMs + halfSpanMs

                    val clipTitlePaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 11.sp.toPx()
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    }
                    val hintPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.argb(150, 140, 135, 124)
                        textSize = 11.sp.toPx()
                    }

                    val anySolo = arranger.tracks.any { it.solo }

                    // 1. Draw Track Backgrounds & Dividers
                    arranger.tracks.forEachIndexed { idx, track ->
                        val topY = idx * rowHeightPx - scrollY
                        val bottomY = topY + rowHeightPx
                        if (bottomY >= 0f && topY <= size.height) {
                            val isSelTrack = track.id == arranger.effectiveSelectedTrackId
                            drawRect(
                                color = if (isSelTrack) StudioSurface.copy(alpha = 0.78f) else StudioBg.copy(alpha = 0.65f),
                                topLeft = Offset(0f, topY),
                                size = Size(size.width, rowHeightPx)
                            )
                            drawLine(
                                color = StudioBorder,
                                start = Offset(0f, bottomY),
                                end = Offset(size.width, bottomY),
                                strokeWidth = 1.dp.toPx()
                            )

                            if (track.clips.isEmpty()) {
                                val hint = if (isSelTrack) {
                                    "空音轨 · 点此处或右上角「+ 加片段」添加音乐"
                                } else {
                                    "点击选中此音轨"
                                }
                                drawContext.canvas.nativeCanvas.drawText(
                                    hint,
                                    14.dp.toPx(),
                                    topY + rowHeightPx * 0.56f,
                                    hintPaint
                                )
                            }
                        }
                    }

                    // 2. Draw "+ 新增音轨 (N/10)" Full-Width Lane Row Below Tracks
                    val addTopY = arranger.tracks.size * rowHeightPx - scrollY
                    if (addTopY <= size.height && addTopY + addRowHeightPx >= 0f) {
                        val canAdd = arranger.tracks.size < MAX_ARRANGER_TRACKS
                        drawRect(
                            color = StudioSurface.copy(alpha = 0.65f),
                            topLeft = Offset(0f, addTopY),
                            size = Size(size.width, addRowHeightPx)
                        )
                        drawLine(
                            color = StudioBorder,
                            start = Offset(0f, addTopY + addRowHeightPx),
                            end = Offset(size.width, addTopY + addRowHeightPx),
                            strokeWidth = 1.dp.toPx()
                        )
                        val addPaint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            color = if (canAdd) android.graphics.Color.rgb(201, 100, 66)
                            else android.graphics.Color.argb(130, 140, 135, 124)
                            textSize = 12.sp.toPx()
                            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        }
                        val msg = if (canAdd) {
                            "＋ 点击新增一条音轨（当前 ${arranger.tracks.size}/$MAX_ARRANGER_TRACKS，最多支持 $MAX_ARRANGER_TRACKS 条）"
                        } else {
                            "已达音轨数量上限（最多支持 $MAX_ARRANGER_TRACKS 条音轨）"
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            msg,
                            14.dp.toPx(),
                            addTopY + addRowHeightPx * 0.60f,
                            addPaint
                        )
                    }

                    // 3. Draw Vertical Time Grid Lines (Including 00:00 Origin Line)
                    val zeroX = centerX - arranger.playheadMs * pxPerMs
                    if (zeroX in 0f..size.width) {
                        drawLine(
                            color = WarmUmber.copy(alpha = 0.45f),
                            start = Offset(zeroX, 0f),
                            end = Offset(zeroX, size.height),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }
                    var gMs = firstGridMs
                    while (gMs <= lastGridMs) {
                        val gx = centerX + (gMs - arranger.playheadMs) * pxPerMs
                        if (gx in 0f..size.width) {
                            drawLine(
                                color = StudioBorder.copy(alpha = 0.55f),
                                start = Offset(gx, 0f),
                                end = Offset(gx, size.height),
                                strokeWidth = 1f
                            )
                        }
                        gMs += majorStepMs
                    }

                    // 4. Draw Clips + Real Sliced Waveforms + Trim Handles + Fade Curves
                    val vPad = 5.dp.toPx()
                    val cornerPx = 8.dp.toPx()
                    val handleWidthPx = 9.dp.toPx()

                    arranger.tracks.forEachIndexed { idx, track ->
                        val topY = idx * rowHeightPx - scrollY + vPad
                        val clipH = rowHeightPx - vPad * 2f
                        if (topY + clipH < 0f || topY > size.height) return@forEachIndexed

                        val audible = if (anySolo) track.solo else !track.muted
                        val alphaFactor = if (audible) 1f else 0.38f

                        track.clips.forEach { clip ->
                            val xStart = centerX + (clip.timelineStartMs - arranger.playheadMs) * pxPerMs
                            val xEnd = centerX + (clip.timelineEndMs - arranger.playheadMs) * pxPerMs
                            if (xEnd < -4f || xStart > size.width + 4f) return@forEach

                            val drawLeft = xStart.coerceAtLeast(-2f)
                            val drawRight = xEnd.coerceAtMost(size.width + 2f)
                            val drawW = (drawRight - drawLeft).coerceAtLeast(3f)
                            val isSelected = clip.id == arranger.selectedClipId
                            val accent = CLIP_ACCENT_PALETTE[clip.colorIndex % CLIP_ACCENT_PALETTE.size]

                            // Clip Card Fill
                            drawRoundRect(
                                color = accent.copy(alpha = (if (isSelected) 0.24f else 0.15f) * alphaFactor),
                                topLeft = Offset(drawLeft, topY),
                                size = Size(drawW, clipH),
                                cornerRadius = CornerRadius(cornerPx, cornerPx)
                            )

                            // Sliced Waveform Bars inside [sourceInMs .. sourceOutMs]
                            val peaks = clip.fullWaveformPeaks
                            if (peaks.isNotEmpty() && clip.sourceDurationMs > 0L) {
                                val stepPx = 3.5.dp.toPx()
                                val barW = 2.dp.toPx()
                                val waveTop = topY + 18.dp.toPx()
                                val waveBottom = topY + clipH - 4.dp.toPx()
                                val waveMid = (waveTop + waveBottom) / 2f
                                val maxAmpH = (waveBottom - waveTop).coerceAtLeast(6f)

                                var bx = maxOf(xStart + 3.dp.toPx(), 0f)
                                val bxEnd = minOf(xEnd - 3.dp.toPx(), size.width)
                                while (bx <= bxEnd) {
                                    val relMs = ((bx - xStart) / pxPerMs).toLong().coerceIn(0L, clip.durationMs)
                                    val srcMs = (clip.sourceInMs + relMs).coerceIn(0L, clip.sourceDurationMs)
                                    val binFloat = (srcMs.toFloat() / clip.sourceDurationMs.toFloat()) * (peaks.size - 1)
                                    val binIdx = binFloat.roundToInt().coerceIn(0, peaks.lastIndex)

                                    // Apply visual fade envelope preview on waveform bars!
                                    var env = 1f
                                    if (clip.fadeInMs > 0L && relMs < clip.fadeInMs) {
                                        env *= (relMs.toFloat() / clip.fadeInMs.toFloat()).coerceIn(0.08f, 1f)
                                    }
                                    val remMs = clip.durationMs - relMs
                                    if (clip.fadeOutMs > 0L && remMs < clip.fadeOutMs) {
                                        env *= (remMs.toFloat() / clip.fadeOutMs.toFloat()).coerceIn(0.08f, 1f)
                                    }

                                    val amp = (peaks[binIdx] * env).coerceIn(0.06f, 1f)
                                    val bh = (amp * maxAmpH * 0.92f).coerceAtLeast(2.5f)
                                    drawLine(
                                        color = accent.copy(alpha = 0.80f * alphaFactor),
                                        start = Offset(bx, waveMid - bh / 2f),
                                        end = Offset(bx, waveMid + bh / 2f),
                                        strokeWidth = barW
                                    )
                                    bx += stepPx
                                }
                            }

                            // Fade-In / Fade-Out Visual Curve Lines
                            if (clip.fadeInMs > 0L) {
                                val fadeEndX = (xStart + clip.fadeInMs * pxPerMs).coerceAtMost(xEnd)
                                if (fadeEndX > xStart + 2f) {
                                    drawLine(
                                        color = accent.copy(alpha = 0.9f * alphaFactor),
                                        start = Offset(xStart, topY + clipH),
                                        end = Offset(fadeEndX, topY + 2.dp.toPx()),
                                        strokeWidth = 1.5.dp.toPx()
                                    )
                                }
                            }
                            if (clip.fadeOutMs > 0L) {
                                val fadeStartX = (xEnd - clip.fadeOutMs * pxPerMs).coerceAtLeast(xStart)
                                if (xEnd > fadeStartX + 2f) {
                                    drawLine(
                                        color = accent.copy(alpha = 0.9f * alphaFactor),
                                        start = Offset(fadeStartX, topY + 2.dp.toPx()),
                                        end = Offset(xEnd, topY + clipH),
                                        strokeWidth = 1.5.dp.toPx()
                                    )
                                }
                            }

                            // Clip Border
                            drawRoundRect(
                                color = if (isSelected) accent else accent.copy(alpha = 0.60f * alphaFactor),
                                topLeft = Offset(drawLeft, topY),
                                size = Size(drawW, clipH),
                                cornerRadius = CornerRadius(cornerPx, cornerPx),
                                style = Stroke(width = if (isSelected) 2.dp.toPx() else 1.dp.toPx())
                            )

                            // Selected Clip Thick Left & Right Trim Handles
                            if (isSelected) {
                                if (xStart in -handleWidthPx..size.width) {
                                    drawRoundRect(
                                        color = accent,
                                        topLeft = Offset(xStart, topY),
                                        size = Size(handleWidthPx, clipH),
                                        cornerRadius = CornerRadius(cornerPx, cornerPx)
                                    )
                                    drawLine(
                                        color = OnAccentLight,
                                        start = Offset(xStart + handleWidthPx / 2f, topY + clipH * 0.32f),
                                        end = Offset(xStart + handleWidthPx / 2f, topY + clipH * 0.68f),
                                        strokeWidth = 1.6.dp.toPx()
                                    )
                                }
                                if (xEnd in 0f..(size.width + handleWidthPx)) {
                                    drawRoundRect(
                                        color = accent,
                                        topLeft = Offset(xEnd - handleWidthPx, topY),
                                        size = Size(handleWidthPx, clipH),
                                        cornerRadius = CornerRadius(cornerPx, cornerPx)
                                    )
                                    drawLine(
                                        color = OnAccentLight,
                                        start = Offset(xEnd - handleWidthPx / 2f, topY + clipH * 0.32f),
                                        end = Offset(xEnd - handleWidthPx / 2f, topY + clipH * 0.68f),
                                        strokeWidth = 1.6.dp.toPx()
                                    )
                                }
                            }

                            // Clip Title + Duration Label (Clipped inside visible rect)
                            val labelLeft = maxOf(xStart + (if (isSelected) handleWidthPx + 4.dp.toPx() else 6.dp.toPx()), 4.dp.toPx())
                            val availTextW = (minOf(xEnd, size.width) - labelLeft - 6.dp.toPx())
                            if (availTextW > 28.dp.toPx()) {
                                val durStr = String.format(Locale.US, "%.1fs", clip.durationMs / 1000f)
                                val gainStr = if (abs(clip.gainDb) >= 0.1f) {
                                    String.format(Locale.US, " %+.1fdB", clip.gainDb)
                                } else ""
                                val rawLabel = "${clip.sourceTitle} · $durStr$gainStr"
                                clipTitlePaint.color = android.graphics.Color.argb(
                                    (235 * alphaFactor).roundToInt(),
                                    20, 20, 19
                                )
                                val nativeCanvas = drawContext.canvas.nativeCanvas
                                nativeCanvas.save()
                                nativeCanvas.clipRect(
                                    labelLeft,
                                    topY,
                                    minOf(xEnd - (if (isSelected) handleWidthPx else 2.dp.toPx()), size.width),
                                    topY + 18.dp.toPx()
                                )
                                nativeCanvas.drawText(
                                    rawLabel,
                                    labelLeft,
                                    topY + 13.5.dp.toPx(),
                                    clipTitlePaint
                                )
                                nativeCanvas.restore()
                            }
                        }
                    }

                    // 5. Magnetic Snap Guide Line (when dragging clip near an edge)
                    snapGuideMs?.let { guideMs ->
                        val sx = centerX + (guideMs - arranger.playheadMs) * pxPerMs
                        if (sx in 0f..size.width) {
                            drawLine(
                                color = EmeraldLossless,
                                start = Offset(sx, 0f),
                                end = Offset(sx, size.height),
                                strokeWidth = 1.8.dp.toPx()
                            )
                        }
                    }

                    // 6. Fixed Center Playhead Line Across All Tracks
                    drawLine(
                        color = TextPrimary,
                        start = Offset(centerX, 0f),
                        end = Offset(centerX, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
        }
    }
}

@Composable
private fun ArrangerConstantBottomDock(
    arranger: ArrangerUiState,
    inspectorSubTab: Int,
    onInspectorSubTabChange: (Int) -> Unit,
    fineTargetMode: Int,
    onFineTargetModeChange: (Int) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onOpenAddClipSheet: () -> Unit,
    onAddTrack: () -> Unit,
    onTrackVolumeChange: (String, Float) -> Unit,
    onRemoveOrClearTrack: () -> Unit,
    onClearClipSelection: () -> Unit,
    onDeleteSelectedClip: () -> Unit,
    onDuplicateSelectedClip: () -> Unit,
    onMoveSelectedClipTrack: (Int) -> Unit,
    onNudgeSelectedClipPos: (Long) -> Unit,
    onSnapSelectedToPlayhead: () -> Unit,
    onSnapSelectedToPrevious: () -> Unit,
    onNudgeSelectedClipTrim: (Boolean, Long) -> Unit,
    onSelectedClipGainChange: (Float) -> Unit,
    onSelectedClipFadesChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedPair = arranger.selectedClipWithTrack

    Surface(
        color = StudioSurface,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 880.dp)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
            if (selectedPair == null) {
                // ============================================================
                // STATE A: Track & Global Arranger Dock (Locked 154.dp)
                // ============================================================
                val selTrack = arranger.selectedTrack

                // Row 1 (24.dp): Active Track Status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = buildString {
                            append("当前目标：${selTrack.name}")
                            if (selTrack.muted) append("（已静音）")
                            if (selTrack.solo) append("（独奏中）")
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "长按片段可自由拖动或跨轨移动",
                        fontSize = 11.sp,
                        color = TextMuted,
                        maxLines = 1
                    )
                }

                // Row 2 (40.dp): Selected Track Volume Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "本轨音量",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        maxLines = 1
                    )
                    MiniSquareBtn("−") {
                        onTrackVolumeChange(selTrack.id, selTrack.volumeDb - 0.5f)
                    }
                    Slider(
                        value = selTrack.volumeDb,
                        onValueChange = { onTrackVolumeChange(selTrack.id, it) },
                        valueRange = -18f..12f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = CyanPrimary,
                            activeTrackColor = CyanPrimary,
                            inactiveTrackColor = StudioBorder
                        )
                    )
                    MiniSquareBtn("+") {
                        onTrackVolumeChange(selTrack.id, selTrack.volumeDb + 0.5f)
                    }
                    val volShape = RoundedCornerShape(7.dp)
                    Surface(
                        shape = volShape,
                        color = StudioSurfaceElevated,
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .widthIn(min = 58.dp)
                            .clip(volShape)
                            .clickable { onTrackVolumeChange(selTrack.id, 0f) }
                    ) {
                        Text(
                            text = String.format(Locale.US, "%+.1fdB", selTrack.volumeDb),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (abs(selTrack.volumeDb) > 0.1f) CyanPrimary else TextPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }

                // Row 3 (44.dp): 4 Equal Primary Action Tiles
                val canAddMoreTracks = arranger.tracks.size < MAX_ARRANGER_TRACKS
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockActionTile(
                        label = "在指针处分割",
                        icon = Icons.Rounded.ContentCut,
                        accent = TextPrimary,
                        enabled = arranger.totalClipCount > 0,
                        onClick = onSplitAtPlayhead,
                        modifier = Modifier.weight(1f)
                    )
                    DockActionTile(
                        label = "添加片段",
                        icon = Icons.Rounded.Add,
                        accent = CyanPrimary,
                        highlighted = true,
                        enabled = true,
                        onClick = onOpenAddClipSheet,
                        modifier = Modifier.weight(1f)
                    )
                    DockActionTile(
                        label = if (canAddMoreTracks) "新增音轨" else "已满10轨",
                        icon = Icons.Rounded.Add,
                        accent = EmeraldLossless,
                        enabled = canAddMoreTracks,
                        onClick = onAddTrack,
                        modifier = Modifier.weight(1f)
                    )
                    DockActionTile(
                        label = if (arranger.tracks.size > 1) "删除此轨" else "清空此轨",
                        icon = Icons.Rounded.DeleteOutline,
                        accent = DangerRed,
                        enabled = arranger.tracks.size > 1 || selTrack.clips.isNotEmpty(),
                        onClick = onRemoveOrClearTrack,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                // ============================================================
                // STATE B: Selected Clip Precision Inspector (Locked 154.dp)
                // ============================================================
                val (ownerTrack, clip) = selectedPair
                val trackIndex = arranger.tracks.indexOfFirst { it.id == ownerTrack.id }

                // Row 1 (26.dp): Selected Clip Name + Sub-mode Switcher + Done
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${ownerTrack.name} · ${clip.sourceTitle}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SubTabChip(
                            label = "裁剪/位置",
                            selected = inspectorSubTab == 0,
                            onClick = { onInspectorSubTabChange(0) }
                        )
                        SubTabChip(
                            label = "音量/淡化",
                            selected = inspectorSubTab == 1,
                            onClick = { onInspectorSubTabChange(1) }
                        )
                        SubTabChip(
                            label = "完成",
                            selected = false,
                            onClick = onClearClipSelection
                        )
                    }
                }

                // Row 2 (42.dp): Precision Controls (No Height Change!)
                if (inspectorSubTab == 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Target selector: 位置 / 起点 / 终点
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf("位置", "起点", "终点").forEachIndexed { idx, txt ->
                                SubTabChip(
                                    label = txt,
                                    selected = fineTargetMode == idx,
                                    onClick = { onFineTargetModeChange(idx) }
                                )
                            }
                        }

                        val readoutMs = when (fineTargetMode) {
                            0 -> clip.timelineStartMs
                            1 -> clip.sourceInMs
                            else -> clip.sourceOutMs
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            MiniSquareBtn("−") {
                                when (fineTargetMode) {
                                    0 -> onNudgeSelectedClipPos(-100L)
                                    1 -> onNudgeSelectedClipTrim(true, -100L)
                                    else -> onNudgeSelectedClipTrim(false, -100L)
                                }
                            }
                            Text(
                                text = formatTimeTenths(readoutMs),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1
                            )
                            MiniSquareBtn("+") {
                                when (fineTargetMode) {
                                    0 -> onNudgeSelectedClipPos(100L)
                                    1 -> onNudgeSelectedClipTrim(true, 100L)
                                    else -> onNudgeSelectedClipTrim(false, 100L)
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SubTabChip(label = "贴前段", selected = false, onClick = onSnapSelectedToPrevious)
                            SubTabChip(label = "至指针", selected = false, onClick = onSnapSelectedToPlayhead)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("音量", fontSize = 11.sp, color = TextSecondary, maxLines = 1)
                            MiniSquareBtn("−") { onSelectedClipGainChange(clip.gainDb - 0.5f) }
                            val gShape = RoundedCornerShape(6.dp)
                            Surface(
                                shape = gShape,
                                color = StudioSurfaceElevated,
                                modifier = Modifier
                                    .clip(gShape)
                                    .clickable { onSelectedClipGainChange(0f) }
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%+.1fdB", clip.gainDb),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (abs(clip.gainDb) > 0.1f) CyanPrimary else TextPrimary,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                            MiniSquareBtn("+") { onSelectedClipGainChange(clip.gainDb + 0.5f) }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            val hasIn = clip.fadeInMs >= 400L
                            val hasOut = clip.fadeOutMs >= 400L
                            SubTabChip(
                                label = "无淡化",
                                selected = !hasIn && !hasOut,
                                onClick = { onSelectedClipFadesChange(0L, 0L) }
                            )
                            SubTabChip(
                                label = "淡入",
                                selected = hasIn && !hasOut,
                                onClick = { onSelectedClipFadesChange(800L, 0L) }
                            )
                            SubTabChip(
                                label = "淡出",
                                selected = !hasIn && hasOut,
                                onClick = { onSelectedClipFadesChange(0L, 800L) }
                            )
                            SubTabChip(
                                label = "首尾淡化",
                                selected = hasIn && hasOut,
                                onClick = { onSelectedClipFadesChange(800L, 800L) }
                            )
                        }
                    }
                }

                // Row 3 (42.dp): 5 Clip Action Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockActionTile(
                        label = "分割",
                        icon = Icons.Rounded.ContentCut,
                        accent = TextPrimary,
                        enabled = true,
                        onClick = onSplitAtPlayhead,
                        modifier = Modifier.weight(1f)
                    )
                    DockActionTile(
                        label = "复制",
                        icon = Icons.Rounded.ContentCopy,
                        accent = TextPrimary,
                        enabled = true,
                        onClick = onDuplicateSelectedClip,
                        modifier = Modifier.weight(1f)
                    )
                    DockActionTile(
                        label = "上移轨",
                        icon = Icons.Rounded.ArrowUpward,
                        accent = TextPrimary,
                        enabled = trackIndex > 0,
                        onClick = { onMoveSelectedClipTrack(-1) },
                        modifier = Modifier.weight(1f)
                    )
                    DockActionTile(
                        label = "下移轨",
                        icon = Icons.Rounded.ArrowDownward,
                        accent = EmeraldLossless,
                        enabled = trackIndex < MAX_ARRANGER_TRACKS - 1,
                        onClick = { onMoveSelectedClipTrack(1) },
                        modifier = Modifier.weight(1f)
                    )
                    DockActionTile(
                        label = "删除",
                        icon = Icons.Rounded.DeleteOutline,
                        accent = DangerRed,
                        enabled = true,
                        onClick = onDeleteSelectedClip,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddArrangerClipBottomSheet(
    targetTrackName: String,
    workspaceTracks: List<SessionTrack>,
    onAddClip: (sessionTrackId: String, inMs: Long?, outMs: Long?, placeAtPlayhead: Boolean) -> Unit,
    onOpenNetEase: () -> Unit,
    onPickLocal: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var expandedTrackId by remember { mutableStateOf(workspaceTracks.firstOrNull()?.id) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "添加音频片段到「$targetTrackName」",
                        fontFamily = FontFamily.Serif,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "可整首直接加入再分割，也可先拖选某一段区间加入（同一首歌可多次选不同段）",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Import New Song Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenNetEase,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f)),
                    contentPadding = PaddingValues(vertical = 9.dp, horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("从网易云导入新歌", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CyanPrimary, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onPickLocal,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, StudioBorder),
                    contentPadding = PaddingValues(vertical = 9.dp, horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("导入手机本地音频", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = StudioBorder)
            Spacer(modifier = Modifier.height(10.dp))

            if (workspaceTracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "工作台暂无音频，请点击上方按钮从网易云或手机本地导入音乐",
                        fontSize = 13.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Text(
                    text = "工作台已有音轨 (${workspaceTracks.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(items = workspaceTracks, key = { it.id }) { item ->
                        val isExpanded = expandedTrackId == item.id
                        val totalDurSec = item.metrics.durationSec.coerceAtLeast(1f)
                        var range by remember(item.id) { mutableStateOf(0f..totalDurSec) }

                        Surface(
                            shape = RoundedCornerShape(13.dp),
                            color = if (isExpanded) StudioBg else StudioSurfaceElevated.copy(alpha = 0.55f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isExpanded) CyanPrimary.copy(alpha = 0.65f) else StudioBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(13.dp))
                                .clickable { expandedTrackId = if (isExpanded) null else item.id }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = item.displayName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${item.badge} · 总长 ${formatTimeTenths((totalDurSec * 1000f).roundToLong())}",
                                            fontSize = 11.sp,
                                            color = TextSecondary,
                                            maxLines = 1
                                        )
                                    }

                                    if (!isExpanded) {
                                        val quickShape = RoundedCornerShape(8.dp)
                                        Surface(
                                            shape = quickShape,
                                            color = CyanPrimary,
                                            modifier = Modifier
                                                .clip(quickShape)
                                                .clickable {
                                                    onAddClip(item.id, null, null, false)
                                                }
                                        ) {
                                            Text(
                                                text = "+ 整首加入",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = OnAccentLight,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }

                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val inMs = (range.start * 1000f).roundToLong()
                                    val outMs = (range.endInclusive * 1000f).roundToLong()
                                        .coerceAtLeast(inMs + MIN_CLIP_DURATION_MS)
                                    val selDurSec = (outMs - inMs) / 1000f

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = String.format(
                                                Locale.US,
                                                "截取范围: %s ~ %s (%.1fs)",
                                                formatTimeTenths(inMs),
                                                formatTimeTenths(outMs),
                                                selDurSec
                                            ),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = CyanPrimary
                                        )
                                        if (inMs > 50L || outMs < (totalDurSec * 1000f).roundToLong() - 50L) {
                                            Text(
                                                text = "选回全曲",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextSecondary,
                                                modifier = Modifier.clickable { range = 0f..totalDurSec }
                                            )
                                        }
                                    }

                                    RangeSlider(
                                        value = range,
                                        onValueChange = { next ->
                                            if (next.endInclusive - next.start >= 0.3f) {
                                                range = next
                                            }
                                        },
                                        valueRange = 0f..totalDurSec,
                                        colors = SliderDefaults.colors(
                                            thumbColor = CyanPrimary,
                                            activeTrackColor = CyanPrimary,
                                            inactiveTrackColor = StudioBorder
                                        )
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { onAddClip(item.id, inMs, outMs, false) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(9.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = CyanPrimary,
                                                contentColor = OnAccentLight
                                            ),
                                            contentPadding = PaddingValues(vertical = 9.dp)
                                        ) {
                                            Text("接在本轨末尾", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        }

                                        OutlinedButton(
                                            onClick = { onAddClip(item.id, inMs, outMs, true) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(9.dp),
                                            border = BorderStroke(1.dp, EmeraldLossless),
                                            contentPadding = PaddingValues(vertical = 9.dp)
                                        ) {
                                            Text("放在当前指针处", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EmeraldLossless, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactHeaderBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape = shape,
        color = StudioSurfaceElevated.copy(alpha = if (enabled) 0.9f else 0.4f),
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) TextPrimary else TextMuted.copy(alpha = 0.45f),
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) TextPrimary else TextMuted.copy(alpha = 0.45f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MiniSquareBtn(
    symbol: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(7.dp)
    Surface(
        shape = shape,
        color = StudioSurface,
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun TrackMiniToggle(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(5.dp)
    Surface(
        shape = shape,
        color = if (active) activeColor else StudioSurfaceElevated,
        border = BorderStroke(0.8.dp, if (active) activeColor else StudioBorder),
        modifier = modifier
            .height(21.dp)
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) OnAccentLight else TextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SubTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        shape = shape,
        color = if (selected) CyanPrimary else StudioSurfaceElevated,
        border = BorderStroke(1.dp, if (selected) CyanPrimary else StudioBorder),
        modifier = Modifier
            .height(24.dp)
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 7.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) OnAccentLight else TextPrimary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DockActionTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    enabled: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(9.dp)
    Surface(
        shape = shape,
        color = when {
            !enabled -> StudioSurfaceElevated.copy(alpha = 0.35f)
            highlighted -> accent.copy(alpha = 0.14f)
            else -> StudioSurfaceElevated.copy(alpha = 0.75f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = when {
                !enabled -> StudioBorder.copy(alpha = 0.45f)
                highlighted -> accent.copy(alpha = 0.55f)
                else -> StudioBorder
            }
        ),
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) accent else TextMuted.copy(alpha = 0.45f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) accent else TextMuted.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun computeMagneticSnap(
    movingClipId: String,
    rawStartMs: Long,
    durationMs: Long,
    arranger: ArrangerUiState,
    snapThresholdMs: Long
): Pair<Long, Long?> {
    val anchorPoints = ArrayList<Long>()
    anchorPoints.add(0L)
    anchorPoints.add(arranger.playheadMs)
    for (t in arranger.tracks) {
        for (c in t.clips) {
            if (c.id == movingClipId) continue
            anchorPoints.add(c.timelineStartMs)
            anchorPoints.add(c.timelineEndMs)
        }
    }

    var bestStart = rawStartMs
    var bestGuide: Long? = null
    var bestDist = snapThresholdMs + 1L

    val rawEndMs = rawStartMs + durationMs
    for (anchor in anchorPoints) {
        val dStart = abs(rawStartMs - anchor)
        if (dStart < bestDist) {
            bestDist = dStart
            bestStart = anchor
            bestGuide = anchor
        }
        val dEnd = abs(rawEndMs - anchor)
        if (dEnd < bestDist && anchor - durationMs >= 0L) {
            bestDist = dEnd
            bestStart = anchor - durationMs
            bestGuide = anchor
        }
    }
    return bestStart.coerceAtLeast(0L) to bestGuide
}

private fun chooseMajorTickMs(zoomDpPerSec: Float): Long = when {
    zoomDpPerSec >= 75f -> 1_000L
    zoomDpPerSec >= 38f -> 2_000L
    zoomDpPerSec >= 20f -> 5_000L
    zoomDpPerSec >= 10f -> 10_000L
    else -> 30_000L
}

private fun formatRulerTick(ms: Long): String {
    val totalSec = ms / 1000L
    val m = totalSec / 60L
    val s = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", m, s)
}

private fun formatTimeTenths(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val m = safe / 60_000L
    val remSec = (safe % 60_000L) / 1000.0
    return String.format(Locale.US, "%02d:%04.1f", m, remSec)
}
