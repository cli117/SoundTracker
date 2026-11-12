package com.soundtracker.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import com.soundtracker.app.melody.DynamicLevel
import com.soundtracker.app.melody.HairpinType
import com.soundtracker.app.melody.KeySignature
import com.soundtracker.app.melody.KeyboardDeckGeometry
import com.soundtracker.app.melody.KeyboardLayoutPreferences
import com.soundtracker.app.melody.KeyboardLayoutProfile
import com.soundtracker.app.melody.MAX_MELODY_BARS
import com.soundtracker.app.melody.MelodyInputMode
import com.soundtracker.app.melody.MelodyProjectSummary
import com.soundtracker.app.melody.MelodyStudioUiState
import com.soundtracker.app.melody.MusicalNotationHelper
import com.soundtracker.app.melody.NoteAccent
import com.soundtracker.app.melody.NoteArticulation
import com.soundtracker.app.melody.NoteDurationPreset
import com.soundtracker.app.melody.PianoTouchGuardConfig
import com.soundtracker.app.melody.ScoreTempoEvent
import com.soundtracker.app.melody.SelectedScoreAnnotation
import com.soundtracker.app.melody.StaffHitTarget
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun MelodyStudioScreen(
    melody: MelodyStudioUiState,
    referenceTrackBpm: Int?,
    referenceTrackKeySignature: KeySignature?,
    referenceTrackName: String?,
    onScoreTitleChange: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearScore: () -> Unit,
    onLoadDemoMelody: () -> Unit,
    onExportMelodyToWorkspace: () -> Unit,
    onShareStaffPdfWeChat: (String) -> Unit,
    onShareStaffPdfSystem: (String) -> Unit,
    onSaveStaffPdfDownloads: (String) -> Unit,
    onSaveStaffPdfCustomFolder: (String) -> Unit,
    onSelectPrimaryInstrument: (StudioInstrument) -> Unit,
    onSelectSecondaryInstrument: (StudioInstrument) -> Unit,
    onToggleDualKeyboard: () -> Unit,
    onToggleCollapseScoreInDual: () -> Unit,
    onSetCollapseScoreInDual: (Boolean) -> Unit,
    onPrimaryScrollChange: (Float, Boolean) -> Unit,
    onSecondaryScrollChange: (Float, Boolean) -> Unit,
    onTimeSignatureChange: (TimeSignature) -> Unit,
    onInitialKeySignatureChange: (KeySignature) -> Unit,
    onUpsertKeySignatureEvent: (barIndex: Int, keySignature: KeySignature, editingId: String?) -> Unit,
    onDeleteKeySignatureEvent: (String) -> Unit,
    onBpmChange: (Int) -> Unit,
    onSwingPresetChange: (SwingPreset) -> Unit,
    onCycleReverb: () -> Unit,
    onTogglePrimarySustain: () -> Unit,
    onToggleSecondarySustain: () -> Unit,
    onPrimarySustainDurationChange: (Float) -> Unit,
    onSecondarySustainDurationChange: (Float) -> Unit,
    onSelectInputMode: (MelodyInputMode) -> Unit,
    onSelectNoteDuration: (NoteDurationPreset) -> Unit,
    onSelectActiveTuplet: (TupletPreset?) -> Unit,
    onToggleChordMode: () -> Unit,
    onInsertRest: (NoteDurationPreset?) -> Unit,
    onExtendPreviousNote: () -> Unit,
    onBackspaceNote: () -> Unit,
    onDeleteSelectedItem: () -> Unit,
    onQuantizeNotes: () -> Unit,
    onAddBars: () -> Unit,
    onToggleMetronome: () -> Unit,
    onToggleLoop: () -> Unit,
    onTogglePlayStop: () -> Unit,
    onToggleRecordStop: () -> Unit,
    onTapCursorTick: (Int) -> Unit,
    onSelectNote: (String?) -> Unit,
    onSelectAnnotation: (SelectedScoreAnnotation?) -> Unit,
    onTogglePhraseSlurMode: () -> Unit,
    onSlurSelectedOrExtendNext: () -> Unit,
    onApplySlurSpan: (Int, Int) -> Unit,
    onClearSelectedSlur: () -> Unit,
    onToggleArticulation: (NoteArticulation?) -> Unit,
    onToggleAccent: (NoteAccent?) -> Unit,
    onToggleFermata: () -> Unit,
    onUpsertDynamicMark: (Int, DynamicLevel, String?) -> Unit,
    onDeleteDynamicMark: (String) -> Unit,
    onUpsertHairpin: (Int, Int, HairpinType, String?) -> Unit,
    onDeleteHairpin: (String) -> Unit,
    onUpsertTempoEvent: (ScoreTempoEvent) -> Unit,
    onDeleteTempoEvent: (String) -> Unit,
    onNewBlankProject: () -> Unit,
    onSaveProject: (overwriteCurrent: Boolean, customTitle: String?) -> Unit,
    onLoadProject: (String) -> Unit,
    onRestoreAutoSaveDraft: () -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onShareProjectWeChat: (projectId: String?, customTitle: String?) -> Unit,
    onShareProjectSystem: (projectId: String?, customTitle: String?) -> Unit,
    onSaveProjectDownloads: (projectId: String?, customTitle: String?) -> Unit,
    onSaveProjectCustomFolder: (projectId: String?, customTitle: String?) -> Unit,
    onImportProjectClick: () -> Unit,
    onTouchGuardConfigChange: (PianoTouchGuardConfig) -> Unit,
    onPianoKeyDown: (slot: Int, midiNote: Int, velocity: Float, isGlissando: Boolean) -> Unit,
    onPianoKeyUp: (slot: Int, midiNote: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    var showClearConfirm by remember { mutableStateOf(false) }
    var showEditTitleDialog by remember { mutableStateOf(false) }
    var showExportPdfDialog by remember { mutableStateOf(false) }
    var showCustomTimeSigDialog by remember { mutableStateOf(false) }
    var showBpmQuickDialog by remember { mutableStateOf(false) }
    var showKeySigDialog by remember { mutableStateOf(false) }
    var showProjectManagerDialog by remember { mutableStateOf(false) }
    var showTouchGuardDialog by remember { mutableStateOf(false) }
    // null = closed, 0 = 奏法·连线, 1 = 力度·渐变, 2 = 速度·变速
    var expressionDialogTab by remember { mutableStateOf<Int?>(null) }
    // null = closed, 0 = primary keyboard sustain dialog, 1 = secondary keyboard sustain dialog
    var editingSustainSlot by remember { mutableStateOf<Int?>(null) }
    // null = closed, 0 = open on primary keyboard tab, 1 = open on secondary keyboard tab
    var activeResizeDialogSlot by remember { mutableStateOf<Int?>(null) }

    val activeFocusTick = if (melody.isPlaying || melody.isRecording) {
        (melody.playheadBeat * TICKS_PER_BEAT.toFloat()).roundToInt().coerceAtLeast(0)
    } else {
        melody.cursorTick.coerceAtLeast(0)
    }
    val activeKeyAtCursor = melody.keySignatureAtTick(activeFocusTick)
    val preferFlatsOnKeyboard = activeKeyAtCursor.prefersFlats

    val openSmartExpressionDialog: () -> Unit = {
        when (melody.selectedAnnotation) {
            is SelectedScoreAnnotation.KeyChange -> {
                showKeySigDialog = true
            }
            is SelectedScoreAnnotation.Dynamic,
            is SelectedScoreAnnotation.Hairpin -> {
                expressionDialogTab = 1
            }
            is SelectedScoreAnnotation.Tempo -> {
                expressionDialogTab = 2
            }
            null -> {
                expressionDialogTab = 0
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBg)
            .statusBarsPadding()
    ) {
        val availWidth = maxWidth
        val availHeight = maxHeight

        val layoutDescriptor = remember(
            configuration.smallestScreenWidthDp,
            availWidth.value.roundToInt(),
            availHeight.value.roundToInt(),
            melody.dualKeyboardEnabled
        ) {
            KeyboardLayoutPreferences.resolveDescriptor(
                smallestScreenWidthDp = configuration.smallestScreenWidthDp,
                availWidthDp = availWidth.value,
                availHeightDp = availHeight.value,
                dualKeyboard = melody.dualKeyboardEnabled
            )
        }

        var layoutProfile by remember(layoutDescriptor.key) {
            mutableStateOf(
                KeyboardLayoutPreferences.loadProfile(
                    context = context,
                    descriptor = layoutDescriptor,
                    availWidthDp = availWidth.value,
                    availHeightDp = availHeight.value
                )
            )
        }

        // Automatically restore saved scroll offsets & score-collapse state whenever entering a layout
        LaunchedEffect(layoutDescriptor.key) {
            val loaded = KeyboardLayoutPreferences.loadProfile(
                context = context,
                descriptor = layoutDescriptor,
                availWidthDp = availWidth.value,
                availHeightDp = availHeight.value
            )
            layoutProfile = loaded
            onPrimaryScrollChange(loaded.primary.scrollWhiteKey, false)
            onSecondaryScrollChange(loaded.secondary.scrollWhiteKey, false)
            if (layoutDescriptor.dualKeyboard) {
                onSetCollapseScoreInDual(loaded.collapseScore)
            }
        }

        val persistLayoutUpdate: ((KeyboardLayoutProfile) -> KeyboardLayoutProfile) -> Unit = { transform ->
            val updated = transform(layoutProfile)
            layoutProfile = updated
            KeyboardLayoutPreferences.saveProfile(context, updated)
        }

        val isTablet = layoutDescriptor.isTablet
        val isWideTablet = availWidth >= 840.dp
        val isShortLandscape = availHeight < 480.dp
        val isCompactDualPhone = melody.dualKeyboardEnabled && !isTablet && availHeight < 780.dp
        val scoreCollapsed = (isCompactDualPhone && melody.collapseScoreInDualMode) ||
            (isShortLandscape && melody.dualKeyboardEnabled && melody.collapseScoreInDualMode)

        // Reserve space for top toolbars & minimum staff view so user resizing never traps the UI
        val chromeAndMinStaffDp = when {
            scoreCollapsed -> 166f
            isShortLandscape -> 208f
            isCompactDualPhone -> 252f
            isWideTablet -> 280f
            else -> 276f
        }
        val maxTotalKeyboardDp = (availHeight.value - chromeAndMinStaffDp)
            .coerceAtLeast(if (melody.dualKeyboardEnabled) 184f else 96f)

        val rawPrimaryH = layoutProfile.primary.deckHeightDp
        val rawSecondaryH = layoutProfile.secondary.deckHeightDp
        val (effectivePrimaryHeightDp, effectiveSecondaryHeightDp) = if (!melody.dualKeyboardEnabled) {
            rawPrimaryH.coerceIn(96f, maxTotalKeyboardDp) to rawSecondaryH
        } else {
            val sum = rawPrimaryH + rawSecondaryH
            if (sum <= maxTotalKeyboardDp || sum <= 1f) {
                rawPrimaryH.coerceAtLeast(88f) to rawSecondaryH.coerceAtLeast(88f)
            } else {
                val scale = maxTotalKeyboardDp / sum
                (rawPrimaryH * scale).coerceAtLeast(86f) to (rawSecondaryH * scale).coerceAtLeast(86f)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // ================================================================
            // 1. Top Header Bar (Project & Export Actions; Undo/Redo moved to Thumb Dock!)
            // ================================================================
            Surface(
                color = StudioSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isShortLandscape) 42.dp else 46.dp)
                        .padding(horizontal = if (isTablet) 18.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "乐器打谱",
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isTablet) 18.sp else 15.sp,
                            color = TextPrimary,
                            maxLines = 1
                        )
                        MelodyHeaderIconBtn(
                            icon = Icons.Rounded.DeleteOutline,
                            contentDescription = "清空乐谱",
                            enabled = melody.notes.isNotEmpty() ||
                                melody.dynamicMarks.isNotEmpty() ||
                                melody.hairpins.isNotEmpty() ||
                                melody.tempoEvents.isNotEmpty() ||
                                melody.keySignatureEvents.isNotEmpty(),
                            onClick = { showClearConfirm = true }
                        )
                    }

                    val compactHeaderLabels = availWidth < 430.dp
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (compactHeaderLabels) 4.dp else 5.dp)
                    ) {
                        // Score Drafts & Local Multi-Project Save / Load (`草稿/工程`)
                        val projShape = RoundedCornerShape(9.dp)
                        val hasLinkedOrDirty = melody.currentProjectId != null || melody.isProjectDirty
                        Surface(
                            shape = projShape,
                            color = if (melody.isProjectDirty) {
                                WarmUmber.copy(alpha = 0.14f)
                            } else if (hasLinkedOrDirty) {
                                EmeraldLossless.copy(alpha = 0.13f)
                            } else {
                                StudioSurfaceElevated
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    melody.isProjectDirty -> WarmUmber.copy(alpha = 0.55f)
                                    hasLinkedOrDirty -> EmeraldLossless.copy(alpha = 0.48f)
                                    else -> StudioBorder
                                }
                            ),
                            modifier = Modifier
                                .height(31.dp)
                                .clip(projShape)
                                .clickable { showProjectManagerDialog = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = if (compactHeaderLabels) 6.dp else 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FolderOpen,
                                    contentDescription = null,
                                    tint = when {
                                        melody.isProjectDirty -> WarmUmber
                                        hasLinkedOrDirty -> EmeraldLossless
                                        else -> CyanPrimary
                                    },
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = when {
                                        compactHeaderLabels && melody.isProjectDirty -> "草稿*"
                                        compactHeaderLabels -> "草稿"
                                        melody.isProjectDirty -> "草稿/工程*"
                                        else -> "草稿/工程"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        melody.isProjectDirty -> WarmUmber
                                        hasLinkedOrDirty -> EmeraldLossless
                                        else -> TextPrimary
                                    },
                                    maxLines = 1
                                )
                            }
                        }

                        val demoShape = RoundedCornerShape(9.dp)
                        Surface(
                            shape = demoShape,
                            color = AmberMastering.copy(alpha = 0.13f),
                            border = BorderStroke(1.dp, AmberMastering.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .height(31.dp)
                                .clip(demoShape)
                                .clickable(onClick = onLoadDemoMelody)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = if (compactHeaderLabels) 6.dp else 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = AmberMastering,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (compactHeaderLabels) "示例" else "示例曲",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AmberMastering,
                                    maxLines = 1
                                )
                            }
                        }

                        // Export 5-Line Staff to PDF (WeChat or Save Locally)
                        val pdfShape = RoundedCornerShape(9.dp)
                        Surface(
                            shape = pdfShape,
                            color = VioletAccent.copy(alpha = 0.13f),
                            border = BorderStroke(1.dp, VioletAccent.copy(alpha = 0.48f)),
                            modifier = Modifier
                                .height(31.dp)
                                .clip(pdfShape)
                                .clickable { showExportPdfDialog = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = if (compactHeaderLabels) 6.dp else 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = null,
                                    tint = VioletAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (compactHeaderLabels) "PDF" else "导出PDF",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VioletAccent,
                                    maxLines = 1
                                )
                            }
                        }

                        Button(
                            onClick = onExportMelodyToWorkspace,
                            enabled = melody.hasPlayableNotes,
                            shape = RoundedCornerShape(9.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = OnAccentLight,
                                disabledContainerColor = StudioSurfaceElevated,
                                disabledContentColor = TextMuted
                            ),
                            contentPadding = PaddingValues(horizontal = if (compactHeaderLabels) 8.dp else 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(31.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "生成音轨",
                                fontSize = if (compactHeaderLabels) 11.sp else 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = StudioBorder)

            // ================================================================
            // 2. Instrument & Global Groove (`拍号` + `调号` + `BPM` + `Swing`) / FX / Sustain Bar
            // ================================================================
            Surface(
                color = StudioSurface.copy(alpha = 0.92f),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCompactDualPhone || isShortLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .padding(horizontal = 10.dp)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TimeSignatureSelectorPill(
                            timeSignature = melody.timeSignature,
                            onSelectTimeSignature = onTimeSignatureChange,
                            onOpenCustomDialog = { showCustomTimeSigDialog = true }
                        )
                        KeySignatureSelectorPill(
                            activeKeySignature = activeKeyAtCursor,
                            modulationCount = melody.keySignatureEvents.size,
                            onClick = { showKeySigDialog = true }
                        )
                        BpmCompactControl(
                            bpm = melody.bpm,
                            referenceTrackBpm = referenceTrackBpm,
                            onBpmChange = onBpmChange,
                            onOpenQuickDialog = { showBpmQuickDialog = true }
                        )
                        MelodyTinyToggle(
                            label = if (melody.tempoEvents.isNotEmpty()) {
                                "变速(${melody.tempoEvents.size})"
                            } else {
                                "速度记号"
                            },
                            active = melody.tempoEvents.isNotEmpty(),
                            activeColor = WarmUmber,
                            onClick = { expressionDialogTab = 2 }
                        )
                        SwingSelectorPill(
                            swingPreset = melody.swingPreset,
                            onSelectSwing = onSwingPresetChange
                        )
                        MelodyTinyToggle(
                            label = "节拍器",
                            active = melody.metronomeEnabled,
                            activeColor = WarmUmber,
                            onClick = onToggleMetronome
                        )
                        MelodyTinyToggle(
                            label = "混响:${melody.reverbPreset.label}",
                            active = true,
                            activeColor = VioletAccent,
                            onClick = onCycleReverb
                        )
                        TouchGuardSelectorPill(
                            config = melody.touchGuardConfig,
                            onClick = { showTouchGuardDialog = true }
                        )
                        MelodyTinyToggle(
                            label = if (melody.dualKeyboardEnabled) "双排键盘:开" else "双排键盘",
                            active = melody.dualKeyboardEnabled,
                            activeColor = CyanPrimary,
                            onClick = onToggleDualKeyboard
                        )
                        if (melody.dualKeyboardEnabled) {
                            val expandShape = RoundedCornerShape(7.dp)
                            Surface(
                                shape = expandShape,
                                color = if (scoreCollapsed) EmeraldLossless.copy(alpha = 0.16f) else StudioSurfaceElevated,
                                border = BorderStroke(
                                    1.dp,
                                    if (scoreCollapsed) EmeraldLossless else StudioBorder
                                ),
                                modifier = Modifier
                                    .height(27.dp)
                                    .clip(expandShape)
                                    .clickable {
                                        val nextCollapsed = !melody.collapseScoreInDualMode
                                        onToggleCollapseScoreInDual()
                                        persistLayoutUpdate { it.copy(collapseScore = nextCollapsed) }
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 7.dp)
                                ) {
                                    Icon(
                                        imageVector = if (scoreCollapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                                        contentDescription = null,
                                        tint = if (scoreCollapsed) EmeraldLossless else TextPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = if (scoreCollapsed) "展开五线谱" else "放大琴键",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (scoreCollapsed) EmeraldLossless else TextPrimary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        // Row 2A: Curated 9 Melodic Instruments Horizontal Strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(30.dp)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = if (isTablet) 18.dp else 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (melody.dualKeyboardEnabled) {
                                Text(
                                    text = "上排音色:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CyanPrimary,
                                    maxLines = 1
                                )
                            }
                            StudioInstrument.entries.forEach { inst ->
                                val selected = inst == melody.instrument
                                val pillShape = RoundedCornerShape(9.dp)
                                Surface(
                                    shape = pillShape,
                                    color = if (selected) CyanPrimary else StudioSurfaceElevated,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) CyanPrimary else StudioBorder
                                    ),
                                    modifier = Modifier
                                        .height(28.dp)
                                        .clip(pillShape)
                                        .clickable { onSelectPrimaryInstrument(inst) }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp)
                                    ) {
                                        Text(
                                            text = inst.displayName,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) OnAccentLight else TextPrimary,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        // Row 2B: Time Signature + Key Signature + BPM + Global Swing + Metronome + Reverb + Sustain + Dual Keyboard
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(30.dp)
                                .padding(horizontal = if (isTablet) 18.dp else 10.dp)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TimeSignatureSelectorPill(
                                timeSignature = melody.timeSignature,
                                onSelectTimeSignature = onTimeSignatureChange,
                                onOpenCustomDialog = { showCustomTimeSigDialog = true }
                            )
                            KeySignatureSelectorPill(
                                activeKeySignature = activeKeyAtCursor,
                                modulationCount = melody.keySignatureEvents.size,
                                onClick = { showKeySigDialog = true }
                            )
                            BpmCompactControl(
                                bpm = melody.bpm,
                                referenceTrackBpm = referenceTrackBpm,
                                onBpmChange = onBpmChange,
                                onOpenQuickDialog = { showBpmQuickDialog = true }
                            )
                            MelodyTinyToggle(
                                label = if (melody.tempoEvents.isNotEmpty()) {
                                    "变速(${melody.tempoEvents.size})"
                                } else {
                                    "速度记号"
                                },
                                active = melody.tempoEvents.isNotEmpty(),
                                activeColor = WarmUmber,
                                onClick = { expressionDialogTab = 2 }
                            )
                            SwingSelectorPill(
                                swingPreset = melody.swingPreset,
                                onSelectSwing = onSwingPresetChange
                            )
                            MelodyTinyToggle(
                                label = "节拍器",
                                active = melody.metronomeEnabled,
                                activeColor = WarmUmber,
                                onClick = onToggleMetronome
                            )
                            MelodyTinyToggle(
                                label = "混响:${melody.reverbPreset.label}",
                                active = true,
                                activeColor = VioletAccent,
                                onClick = onCycleReverb
                            )
                            if (!melody.dualKeyboardEnabled) {
                                SustainControlPair(
                                    sustainOn = melody.sustainPedal,
                                    sustainSec = melody.sustainDurationSec,
                                    accentColor = EmeraldLossless,
                                    compact = false,
                                    onToggleSustain = onTogglePrimarySustain,
                                    onEditDuration = { editingSustainSlot = 0 }
                                )
                            }
                            TouchGuardSelectorPill(
                                config = melody.touchGuardConfig,
                                onClick = { showTouchGuardDialog = true }
                            )
                            MelodyTinyToggle(
                                label = if (melody.dualKeyboardEnabled) "双排键盘:开" else "+ 双排键盘",
                                active = melody.dualKeyboardEnabled,
                                activeColor = CyanPrimary,
                                onClick = onToggleDualKeyboard
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = StudioBorder)

            // ================================================================
            // 3. Eye-Level Real 5-Line Staff (`五线谱`) + Custom Score Title Bar
            //    Placed ABOVE the Composing/Undo Dock so fingers never block the score!
            // ================================================================
            if (scoreCollapsed) {
                Surface(
                    color = StudioSurfaceElevated.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clickable {
                            onToggleCollapseScoreInDual()
                            persistLayoutUpdate { it.copy(collapseScore = false) }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val barIdx = melody.barNumberAtTick()
                        val beatIdx = melody.beatNumberAtTick()
                        Text(
                            text = if (melody.isRecording) {
                                "《${melody.scoreTitle}》(${activeKeyAtCursor.shortPillLabel} · ${melody.timeSignature.label})双排弹录中 · 已录 ${melody.notes.size} 个符号"
                            } else {
                                "《${melody.scoreTitle}》 · ${activeKeyAtCursor.shortPillLabel} · ${melody.timeSignature.label}拍 · 第${barIdx}小节第${beatIdx}拍 · 已写 ${melody.notes.size} 项"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (melody.isRecording) DangerRed else TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "展开五线谱 ▾",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    StaffTitleBanner(
                        scoreTitle = melody.scoreTitle,
                        activeKeySignature = activeKeyAtCursor,
                        timeSignature = melody.timeSignature,
                        bpm = melody.bpm,
                        swingPreset = melody.swingPreset,
                        barNum = melody.barNumberAtTick(),
                        beatNum = melody.beatNumberAtTick(),
                        isGrandStaff = StaffScoreEngraver.needsGrandStaff(melody.notes),
                        onEditTitleClick = { showEditTitleDialog = true },
                        onExportPdfClick = { showExportPdfDialog = true }
                    )
                    HorizontalDivider(color = StudioBorder.copy(alpha = 0.55f), thickness = 0.7.dp)
                    InteractiveStaffScoreCanvas(
                        melody = melody,
                        isTablet = isTablet,
                        onTapKeySignatureHeader = { showKeySigDialog = true },
                        onTapTimeSignatureHeader = { showCustomTimeSigDialog = true },
                        onTapCursorTick = onTapCursorTick,
                        onSelectNote = onSelectNote,
                        onSelectAnnotation = onSelectAnnotation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }

            HorizontalDivider(color = StudioBorder)

            // ================================================================
            // 4. Ergonomic Thumb-Zone Dock (Directly Above the Piano Keyboard!)
            //    Pins Undo (`撤销`), Redo (`重做`), Backspace (`退格`), Transport,
            //    Note Glyphs, Slurs (`乐句连线`), Expressions (`力度·奏法·速度`), & Rests!
            // ================================================================
            Surface(
                color = StudioSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isWideTablet) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TransportAndModeLeftCluster(
                                melody = melody,
                                compactModeLabels = false,
                                onTogglePlayStop = onTogglePlayStop,
                                onSelectInputMode = onSelectInputMode,
                                onToggleRecordStop = onToggleRecordStop
                            )
                            ThumbUndoRedoBackspaceCluster(
                                canUndo = melody.canUndo,
                                canRedo = melody.canRedo,
                                showBackspace = melody.inputMode == MelodyInputMode.STEP_INPUT,
                                hasSelectedNote = melody.selectedNoteId != null,
                                hasSelectedAnnotation = melody.selectedAnnotation != null,
                                showTextLabels = true,
                                onUndo = onUndo,
                                onRedo = onRedo,
                                onBackspace = onBackspaceNote,
                                onEditSelectedExpression = openSmartExpressionDialog,
                                onDeleteSelected = onDeleteSelectedItem
                            )
                        }

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            StepOrRecordControlsCluster(
                                melody = melody,
                                onSelectNoteDuration = onSelectNoteDuration,
                                onSelectActiveTuplet = onSelectActiveTuplet,
                                onInsertRest = onInsertRest,
                                onExtendPreviousNote = onExtendPreviousNote,
                                onTogglePhraseSlurMode = onTogglePhraseSlurMode,
                                onSlurSelectedOrExtendNext = onSlurSelectedOrExtendNext,
                                onClearSelectedSlur = onClearSelectedSlur,
                                onOpenExpressionStudio = openSmartExpressionDialog,
                                onOpenTouchGuard = { showTouchGuardDialog = true },
                                onToggleChordMode = onToggleChordMode,
                                onQuantizeNotes = onQuantizeNotes,
                                onToggleLoop = onToggleLoop,
                                onAddBars = onAddBars
                            )
                        }
                    }
                } else {
                    val showUndoLabels = availWidth >= 430.dp && melody.inputMode == MelodyInputMode.STEP_INPUT
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = if (isShortLandscape) 3.dp else 4.dp),
                        verticalArrangement = Arrangement.spacedBy(if (isShortLandscape) 3.dp else 4.dp)
                    ) {
                        // Row 4A: Always-Pinned Transport + Thumb Undo / Redo / Backspace (Zero Scroll!)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(30.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TransportAndModeLeftCluster(
                                melody = melody,
                                compactModeLabels = availWidth < 410.dp,
                                onTogglePlayStop = onTogglePlayStop,
                                onSelectInputMode = onSelectInputMode,
                                onToggleRecordStop = onToggleRecordStop
                            )
                            ThumbUndoRedoBackspaceCluster(
                                canUndo = melody.canUndo,
                                canRedo = melody.canRedo,
                                showBackspace = melody.inputMode == MelodyInputMode.STEP_INPUT,
                                hasSelectedNote = melody.selectedNoteId != null,
                                hasSelectedAnnotation = melody.selectedAnnotation != null,
                                showTextLabels = showUndoLabels,
                                onUndo = onUndo,
                                onRedo = onRedo,
                                onBackspace = onBackspaceNote,
                                onEditSelectedExpression = openSmartExpressionDialog,
                                onDeleteSelected = onDeleteSelectedItem
                            )
                        }

                        if (!scoreCollapsed) {
                            // Row 4B: Icon-Only Note Glyphs + Tuplets + Rests + Tie + Slur + Expressions
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                StepOrRecordControlsCluster(
                                    melody = melody,
                                    onSelectNoteDuration = onSelectNoteDuration,
                                    onSelectActiveTuplet = onSelectActiveTuplet,
                                    onInsertRest = onInsertRest,
                                    onExtendPreviousNote = onExtendPreviousNote,
                                    onTogglePhraseSlurMode = onTogglePhraseSlurMode,
                                    onSlurSelectedOrExtendNext = onSlurSelectedOrExtendNext,
                                    onClearSelectedSlur = onClearSelectedSlur,
                                    onOpenExpressionStudio = openSmartExpressionDialog,
                                    onOpenTouchGuard = { showTouchGuardDialog = true },
                                    onToggleChordMode = onToggleChordMode,
                                    onQuantizeNotes = onQuantizeNotes,
                                    onToggleLoop = onToggleLoop,
                                    onAddBars = onAddBars
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = StudioBorder)

            // ================================================================
            // 5. Resizable & Layout-Persisted Primary & Secondary Keyboards
            // ================================================================
            if (!melody.dualKeyboardEnabled) {
                IndependentKeyboardDeck(
                    deckTitle = null,
                    accentColor = CyanPrimary,
                    instrument = melody.instrument,
                    sustainOn = melody.sustainPedal,
                    sustainDurationSec = melody.sustainDurationSec,
                    preferFlats = preferFlatsOnKeyboard,
                    isRecording = melody.isRecording,
                    touchGuardConfig = melody.touchGuardConfig,
                    showInlineInstrumentAndSustain = isShortLandscape,
                    availContainerWidthDp = availWidth.value,
                    geometry = layoutProfile.primary.copy(
                        deckHeightDp = effectivePrimaryHeightDp,
                        scrollWhiteKey = melody.primaryScrollWhiteKey
                    ),
                    onSelectInstrument = onSelectPrimaryInstrument,
                    onToggleSustain = onTogglePrimarySustain,
                    onEditSustainDuration = { editingSustainSlot = 0 },
                    onOpenSizeConfig = { activeResizeDialogSlot = 0 },
                    onHeightDeltaDp = { deltaDp ->
                        persistLayoutUpdate { prof ->
                            val nextH = (effectivePrimaryHeightDp + deltaDp).coerceIn(96f, maxTotalKeyboardDp)
                            prof.copy(primary = prof.primary.copy(deckHeightDp = nextH).coerced())
                        }
                    },
                    onWidthFractionDelta = { deltaFrac ->
                        persistLayoutUpdate { prof ->
                            val nextW = (prof.primary.widthFraction + deltaFrac).coerceIn(0.45f, 1.0f)
                            prof.copy(primary = prof.primary.copy(widthFraction = nextW).coerced())
                        }
                    },
                    onScrollWhiteKeyChange = { newWhiteKey, snapFinished ->
                        onPrimaryScrollChange(newWhiteKey, snapFinished)
                        val savedScroll = if (snapFinished) newWhiteKey.roundToInt().toFloat() else newWhiteKey
                        persistLayoutUpdate { prof ->
                            prof.copy(primary = prof.primary.copy(scrollWhiteKey = savedScroll).coerced())
                        }
                    },
                    onPianoKeyDown = { midi, vel, isGlissando -> onPianoKeyDown(0, midi, vel, isGlissando) },
                    onPianoKeyUp = { midi -> onPianoKeyUp(0, midi) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(effectivePrimaryHeightDp.dp)
                )
            } else {
                // Upper Keyboard (Slot 0)
                IndependentKeyboardDeck(
                    deckTitle = "上排",
                    accentColor = CyanPrimary,
                    instrument = melody.instrument,
                    sustainOn = melody.sustainPedal,
                    sustainDurationSec = melody.sustainDurationSec,
                    preferFlats = preferFlatsOnKeyboard,
                    isRecording = melody.isRecording,
                    touchGuardConfig = melody.touchGuardConfig,
                    showInlineInstrumentAndSustain = true,
                    availContainerWidthDp = availWidth.value,
                    geometry = layoutProfile.primary.copy(
                        deckHeightDp = effectivePrimaryHeightDp,
                        scrollWhiteKey = melody.primaryScrollWhiteKey
                    ),
                    onSelectInstrument = onSelectPrimaryInstrument,
                    onToggleSustain = onTogglePrimarySustain,
                    onEditSustainDuration = { editingSustainSlot = 0 },
                    onOpenSizeConfig = { activeResizeDialogSlot = 0 },
                    onHeightDeltaDp = { deltaDp ->
                        persistLayoutUpdate { prof ->
                            val nextH = (prof.primary.deckHeightDp + deltaDp).coerceIn(88f, maxTotalKeyboardDp - 88f)
                            prof.copy(primary = prof.primary.copy(deckHeightDp = nextH).coerced())
                        }
                    },
                    onWidthFractionDelta = { deltaFrac ->
                        persistLayoutUpdate { prof ->
                            val nextW = (prof.primary.widthFraction + deltaFrac).coerceIn(0.45f, 1.0f)
                            prof.copy(primary = prof.primary.copy(widthFraction = nextW).coerced())
                        }
                    },
                    onScrollWhiteKeyChange = { newWhiteKey, snapFinished ->
                        onPrimaryScrollChange(newWhiteKey, snapFinished)
                        val savedScroll = if (snapFinished) newWhiteKey.roundToInt().toFloat() else newWhiteKey
                        persistLayoutUpdate { prof ->
                            prof.copy(primary = prof.primary.copy(scrollWhiteKey = savedScroll).coerced())
                        }
                    },
                    onPianoKeyDown = { midi, vel, isGlissando -> onPianoKeyDown(0, midi, vel, isGlissando) },
                    onPianoKeyUp = { midi -> onPianoKeyUp(0, midi) },
                    modifier = if (scoreCollapsed) {
                        Modifier
                            .fillMaxWidth()
                            .weight(layoutProfile.primary.deckHeightDp.coerceAtLeast(88f))
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(effectivePrimaryHeightDp.dp)
                    }
                )

                HorizontalDivider(color = StudioBorder, thickness = 1.dp)

                // Lower Keyboard (Slot 1)
                IndependentKeyboardDeck(
                    deckTitle = "下排",
                    accentColor = EmeraldLossless,
                    instrument = melody.secondaryInstrument,
                    sustainOn = melody.secondarySustainPedal,
                    sustainDurationSec = melody.secondarySustainDurationSec,
                    preferFlats = preferFlatsOnKeyboard,
                    isRecording = melody.isRecording,
                    touchGuardConfig = melody.touchGuardConfig,
                    showInlineInstrumentAndSustain = true,
                    availContainerWidthDp = availWidth.value,
                    geometry = layoutProfile.secondary.copy(
                        deckHeightDp = effectiveSecondaryHeightDp,
                        scrollWhiteKey = melody.secondaryScrollWhiteKey
                    ),
                    onSelectInstrument = onSelectSecondaryInstrument,
                    onToggleSustain = onToggleSecondarySustain,
                    onEditSustainDuration = { editingSustainSlot = 1 },
                    onOpenSizeConfig = { activeResizeDialogSlot = 1 },
                    onHeightDeltaDp = { deltaDp ->
                        persistLayoutUpdate { prof ->
                            val nextH = (prof.secondary.deckHeightDp + deltaDp).coerceIn(88f, maxTotalKeyboardDp - 88f)
                            prof.copy(secondary = prof.secondary.copy(deckHeightDp = nextH).coerced())
                        }
                    },
                    onWidthFractionDelta = { deltaFrac ->
                        persistLayoutUpdate { prof ->
                            val nextW = (prof.secondary.widthFraction + deltaFrac).coerceIn(0.45f, 1.0f)
                            prof.copy(secondary = prof.secondary.copy(widthFraction = nextW).coerced())
                        }
                    },
                    onScrollWhiteKeyChange = { newWhiteKey, snapFinished ->
                        onSecondaryScrollChange(newWhiteKey, snapFinished)
                        val savedScroll = if (snapFinished) newWhiteKey.roundToInt().toFloat() else newWhiteKey
                        persistLayoutUpdate { prof ->
                            prof.copy(secondary = prof.secondary.copy(scrollWhiteKey = savedScroll).coerced())
                        }
                    },
                    onPianoKeyDown = { midi, vel, isGlissando -> onPianoKeyDown(1, midi, vel, isGlissando) },
                    onPianoKeyUp = { midi -> onPianoKeyUp(1, midi) },
                    modifier = if (scoreCollapsed) {
                        Modifier
                            .fillMaxWidth()
                            .weight(layoutProfile.secondary.deckHeightDp.coerceAtLeast(88f))
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(effectiveSecondaryHeightDp.dp)
                    }
                )
            }
        }

        // ====================================================================
        // Dialogs (Sustain 0.xxx秒 Input, Keyboard Resize, Score Title, PDF Export)
        // ====================================================================

        editingSustainSlot?.let { slot ->
            val currentSec = if (slot == 1) melody.secondarySustainDurationSec else melody.sustainDurationSec
            val currentOn = if (slot == 1) melody.secondarySustainPedal else melody.sustainPedal
            val titlePrefix = if (!melody.dualKeyboardEnabled) {
                "延音长度设置"
            } else if (slot == 1) {
                "下排键盘 · 延音长度设置"
            } else {
                "上排键盘 · 延音长度设置"
            }
            SustainDurationInputDialog(
                dialogTitle = titlePrefix,
                initialSeconds = currentSec,
                sustainEnabled = currentOn,
                onToggleSustain = {
                    if (slot == 1) onToggleSecondarySustain() else onTogglePrimarySustain()
                },
                onConfirmSeconds = { sec ->
                    if (slot == 1) {
                        onSecondarySustainDurationChange(sec)
                    } else {
                        onPrimarySustainDurationChange(sec)
                    }
                    editingSustainSlot = null
                },
                onDismiss = { editingSustainSlot = null }
            )
        }

        activeResizeDialogSlot?.let { initialSlot ->
            KeyboardSizeConfigDialog(
                layoutProfile = layoutProfile,
                dualKeyboardEnabled = melody.dualKeyboardEnabled,
                initialSelectedSlot = initialSlot,
                availWidthDp = availWidth.value,
                maxDeckHeightDp = if (melody.dualKeyboardEnabled) {
                    (maxTotalKeyboardDp - 88f).coerceAtLeast(110f)
                } else {
                    maxTotalKeyboardDp
                },
                onUpdatePrimary = { newGeom ->
                    persistLayoutUpdate { it.copy(primary = newGeom.coerced()) }
                },
                onUpdateSecondary = { newGeom ->
                    persistLayoutUpdate { it.copy(secondary = newGeom.coerced()) }
                },
                onResetCurrentLayout = {
                    val reset = KeyboardLayoutPreferences.resetProfile(
                        context = context,
                        descriptor = layoutDescriptor,
                        availWidthDp = availWidth.value,
                        availHeightDp = availHeight.value
                    )
                    layoutProfile = reset
                    onPrimaryScrollChange(reset.primary.scrollWhiteKey, true)
                    onSecondaryScrollChange(reset.secondary.scrollWhiteKey, true)
                    if (layoutDescriptor.dualKeyboard) {
                        onSetCollapseScoreInDual(reset.collapseScore)
                    }
                },
                onDismiss = { activeResizeDialogSlot = null }
            )
        }
    }

    if (showEditTitleDialog) {
        EditScoreTitleDialog(
            initialTitle = melody.scoreTitle,
            onConfirm = { newTitle ->
                onScoreTitleChange(newTitle.trim().ifBlank { "未命名乐谱" })
                showEditTitleDialog = false
            },
            onDismiss = { showEditTitleDialog = false }
        )
    }

    if (showCustomTimeSigDialog) {
        CustomTimeSignatureDialog(
            currentTimeSignature = melody.timeSignature,
            onConfirm = { selectedSig ->
                onTimeSignatureChange(selectedSig)
                showCustomTimeSigDialog = false
            },
            onDismiss = { showCustomTimeSigDialog = false }
        )
    }

    if (showBpmQuickDialog) {
        BpmQuickInputDialog(
            currentBpm = melody.bpm,
            referenceTrackBpm = referenceTrackBpm,
            onBpmChange = onBpmChange,
            onDismiss = { showBpmQuickDialog = false }
        )
    }

    if (showKeySigDialog) {
        KeySignatureStudioDialog(
            melody = melody,
            referenceTrackKeySignature = referenceTrackKeySignature,
            referenceTrackName = referenceTrackName,
            onSetInitialKeySignature = onInitialKeySignatureChange,
            onUpsertMidKeySignature = onUpsertKeySignatureEvent,
            onDeleteMidKeySignature = onDeleteKeySignatureEvent,
            onTapCursorTick = onTapCursorTick,
            onSelectAnnotation = onSelectAnnotation,
            onDismiss = { showKeySigDialog = false }
        )
    }

    if (showTouchGuardDialog) {
        TouchGuardSettingsDialog(
            config = melody.touchGuardConfig,
            onConfigChange = onTouchGuardConfigChange,
            onDismiss = { showTouchGuardDialog = false }
        )
    }

    if (showProjectManagerDialog) {
        MelodyProjectManagerDialog(
            melody = melody,
            onUpdateTitle = onScoreTitleChange,
            onNewBlankProject = {
                showProjectManagerDialog = false
                onNewBlankProject()
            },
            onSaveProject = onSaveProject,
            onLoadProject = { projectId ->
                showProjectManagerDialog = false
                onLoadProject(projectId)
            },
            onRestoreAutoSaveDraft = {
                showProjectManagerDialog = false
                onRestoreAutoSaveDraft()
            },
            onRenameProject = onRenameProject,
            onDeleteProject = onDeleteProject,
            onShareProjectWeChat = { projectId, customTitle ->
                showProjectManagerDialog = false
                onShareProjectWeChat(projectId, customTitle)
            },
            onShareProjectSystem = { projectId, customTitle ->
                showProjectManagerDialog = false
                onShareProjectSystem(projectId, customTitle)
            },
            onSaveProjectDownloads = { projectId, customTitle ->
                onSaveProjectDownloads(projectId, customTitle)
            },
            onSaveProjectCustomFolder = { projectId, customTitle ->
                showProjectManagerDialog = false
                onSaveProjectCustomFolder(projectId, customTitle)
            },
            onImportProjectClick = {
                showProjectManagerDialog = false
                onImportProjectClick()
            },
            onDismiss = { showProjectManagerDialog = false }
        )
    }

    if (showExportPdfDialog) {
        ExportStaffPdfDialog(
            melody = melody,
            onUpdateTitle = onScoreTitleChange,
            onShareWeChat = { finalTitle ->
                showExportPdfDialog = false
                onShareStaffPdfWeChat(finalTitle)
            },
            onShareSystem = { finalTitle ->
                showExportPdfDialog = false
                onShareStaffPdfSystem(finalTitle)
            },
            onSaveLocalDownloads = { finalTitle ->
                showExportPdfDialog = false
                onSaveStaffPdfDownloads(finalTitle)
            },
            onSaveCustomFolder = { finalTitle ->
                showExportPdfDialog = false
                onSaveStaffPdfCustomFolder(finalTitle)
            },
            onDismiss = { showExportPdfDialog = false }
        )
    }

    expressionDialogTab?.let { initialTab ->
        ScoreExpressionStudioDialog(
            melody = melody,
            initialTab = initialTab,
            onTapCursorTick = onTapCursorTick,
            onSelectAnnotation = onSelectAnnotation,
            onTogglePhraseSlurMode = onTogglePhraseSlurMode,
            onSlurSelectedOrExtendNext = onSlurSelectedOrExtendNext,
            onApplySlurSpan = onApplySlurSpan,
            onClearSelectedSlur = onClearSelectedSlur,
            onToggleArticulation = onToggleArticulation,
            onToggleAccent = onToggleAccent,
            onToggleFermata = onToggleFermata,
            onUpsertDynamicMark = onUpsertDynamicMark,
            onDeleteDynamicMark = onDeleteDynamicMark,
            onUpsertHairpin = onUpsertHairpin,
            onDeleteHairpin = onDeleteHairpin,
            onUpsertTempoEvent = onUpsertTempoEvent,
            onDeleteTempoEvent = onDeleteTempoEvent,
            onDismiss = { expressionDialogTab = null }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = StudioSurface,
            title = {
                Text("清空当前五线谱？", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Text(
                    "将清空当前五线谱上的全部音符、休止符、中途变调及力度/奏法/速度记号（支持点击撤销恢复）。",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearScore()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DangerRed,
                        contentColor = OnAccentLight
                    )
                ) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }
}

// ============================================================================
// Sustain Toggle + Direct `0.xxx秒` Pill & Dialog (Points 1a, 1b, 1c)
// ============================================================================

@Composable
private fun SustainControlPair(
    sustainOn: Boolean,
    sustainSec: Float,
    accentColor: Color,
    compact: Boolean,
    onToggleSustain: () -> Unit,
    onEditDuration: () -> Unit
) {
    val pillH = if (compact) 23.dp else 27.dp
    val fontSz = if (compact) 10.sp else 11.sp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val susShape = RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp, topEnd = 4.dp, bottomEnd = 4.dp)
        Surface(
            shape = susShape,
            color = if (sustainOn) accentColor else StudioSurfaceElevated,
            border = BorderStroke(1.dp, if (sustainOn) accentColor else StudioBorder),
            modifier = Modifier
                .height(pillH)
                .clip(susShape)
                .clickable(onClick = onToggleSustain)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = if (compact) 6.dp else 8.dp)
            ) {
                Text(
                    text = if (sustainOn) "延音:开" else "延音:关",
                    fontSize = fontSz,
                    fontWeight = FontWeight.Bold,
                    color = if (sustainOn) OnAccentLight else TextSecondary,
                    maxLines = 1
                )
            }
        }

        val durShape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 7.dp, bottomEnd = 7.dp)
        Surface(
            shape = durShape,
            color = if (sustainOn) accentColor.copy(alpha = 0.14f) else StudioSurfaceElevated,
            border = BorderStroke(1.dp, if (sustainOn) accentColor.copy(alpha = 0.55f) else StudioBorder),
            modifier = Modifier
                .height(pillH)
                .clip(durShape)
                .clickable(onClick = onEditDuration)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = if (compact) 5.dp else 7.dp)
            ) {
                Text(
                    text = "${MusicalNotationHelper.formatSustainSeconds(sustainSec)}秒",
                    fontSize = fontSz,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (sustainOn) accentColor else TextPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Rounded.DriveFileRenameOutline,
                    contentDescription = "编辑延音时长",
                    tint = if (sustainOn) accentColor else TextMuted,
                    modifier = Modifier.size(if (compact) 11.dp else 12.dp)
                )
            }
        }
    }
}

@Composable
private fun SustainDurationInputDialog(
    dialogTitle: String,
    initialSeconds: Float,
    sustainEnabled: Boolean,
    onToggleSustain: () -> Unit,
    onConfirmSeconds: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var textValue by remember(initialSeconds) {
        mutableStateOf(MusicalNotationHelper.formatSustainSeconds(initialSeconds))
    }
    val parsedSec = textValue.trim().toFloatOrNull()
    val isValid = parsedSec != null && parsedSec in 0.030f..10.000f

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = dialogTitle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                MelodyTinyToggle(
                    label = if (sustainEnabled) "延音已开启" else "延音已关闭",
                    active = sustainEnabled,
                    activeColor = EmeraldLossless,
                    onClick = onToggleSustain
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "直接输入松开琴键后的自然延音衰减时长（如 0.350、0.850、1.500 秒），全部 9 种乐器均按该时长平滑自然消失：",
                    fontSize = 12.sp,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = textValue,
                    onValueChange = { raw ->
                        // Allow digits and at most one decimal point
                        val filtered = raw.filter { it.isDigit() || it == '.' }.take(7)
                        if (filtered.count { it == '.' } <= 1) {
                            textValue = filtered
                        }
                    },
                    singleLine = true,
                    label = { Text("延音时长（0.xxx 秒）") },
                    suffix = { Text("秒", color = TextSecondary) },
                    isError = textValue.isNotBlank() && !isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                if (textValue.isNotBlank() && !isValid) {
                    Text(
                        text = "请输入 0.030 ~ 10.000 之间的秒数（例如 0.650）",
                        fontSize = 11.sp,
                        color = DangerRed
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0.250f, 0.500f, 0.850f, 1.500f, 2.500f).forEach { preset ->
                        val formatted = MusicalNotationHelper.formatSustainSeconds(preset)
                        val active = parsedSec != null && kotlin.math.abs(parsedSec - preset) < 0.001f
                        MelodyTinyToggle(
                            label = "${formatted}s",
                            active = active,
                            activeColor = CyanPrimary,
                            onClick = { textValue = formatted }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val v = parsedSec?.coerceIn(0.030f, 10.000f) ?: initialSeconds
                    onConfirmSeconds(v)
                },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                )
            ) {
                Text("应用时长")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

// ============================================================================
// Keyboard Width / Height / Key Width Config Dialog (Per-Layout Auto-Saved!)
// ============================================================================

@Composable
private fun KeyboardSizeConfigDialog(
    layoutProfile: KeyboardLayoutProfile,
    dualKeyboardEnabled: Boolean,
    initialSelectedSlot: Int,
    availWidthDp: Float,
    maxDeckHeightDp: Float,
    onUpdatePrimary: (KeyboardDeckGeometry) -> Unit,
    onUpdateSecondary: (KeyboardDeckGeometry) -> Unit,
    onResetCurrentLayout: () -> Unit,
    onDismiss: () -> Unit
) {
    var editingSlot by remember(initialSelectedSlot, dualKeyboardEnabled) {
        mutableIntStateOf(if (dualKeyboardEnabled) initialSelectedSlot.coerceIn(0, 1) else 0)
    }

    val currentGeom = if (editingSlot == 1) layoutProfile.secondary else layoutProfile.primary
    val updateGeom: (KeyboardDeckGeometry) -> Unit = { g ->
        if (editingSlot == 1) onUpdateSecondary(g) else onUpdatePrimary(g)
    }

    val actualDeckWidthDp = (availWidthDp * currentGeom.widthFraction).coerceAtLeast(140f)
    val approxWhiteKeys = (actualDeckWidthDp / currentGeom.keyWidthDp).coerceIn(3.5f, 46f)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "键盘宽高与单键宽度调节",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = CyanPrimary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "自动记忆布局：${layoutProfile.displayLabel}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CyanPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (dualKeyboardEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MelodyTinyToggle(
                            label = "调节上排键盘",
                            active = editingSlot == 0,
                            activeColor = CyanPrimary,
                            onClick = { editingSlot = 0 }
                        )
                        MelodyTinyToggle(
                            label = "调节下排键盘",
                            active = editingSlot == 1,
                            activeColor = EmeraldLossless,
                            onClick = { editingSlot = 1 }
                        )
                    }
                }

                // 1. Keyboard Width
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("键盘总宽度", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text(
                            text = "${(currentGeom.widthFraction * 100f).roundToInt()}% (${actualDeckWidthDp.roundToInt()} dp)",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CyanPrimary
                        )
                    }
                    Slider(
                        value = currentGeom.widthFraction,
                        onValueChange = { updateGeom(currentGeom.copy(widthFraction = it)) },
                        valueRange = 0.45f..1.00f,
                        colors = SliderDefaults.colors(
                            thumbColor = CyanPrimary,
                            activeTrackColor = CyanPrimary
                        )
                    )
                }

                // 2. Keyboard Height
                val safeMaxH = maxDeckHeightDp.coerceIn(120f, 420f)
                val minH = if (dualKeyboardEnabled) 88f else 96f
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("键盘高度", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text(
                            text = "${currentGeom.deckHeightDp.roundToInt()} dp",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CyanPrimary
                        )
                    }
                    Slider(
                        value = currentGeom.deckHeightDp.coerceIn(minH, safeMaxH),
                        onValueChange = { updateGeom(currentGeom.copy(deckHeightDp = it)) },
                        valueRange = minH..safeMaxH,
                        colors = SliderDefaults.colors(
                            thumbColor = CyanPrimary,
                            activeTrackColor = CyanPrimary
                        )
                    )
                }

                // 3. Individual White Key Width
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("单键宽度", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text(
                            text = "${currentGeom.keyWidthDp.roundToInt()} dp · 约 ${String.format(java.util.Locale.US, "%.1f", approxWhiteKeys)} 白键",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = EmeraldLossless
                        )
                    }
                    Slider(
                        value = currentGeom.keyWidthDp,
                        onValueChange = { updateGeom(currentGeom.copy(keyWidthDp = it)) },
                        valueRange = 22f..84f,
                        colors = SliderDefaults.colors(
                            thumbColor = EmeraldLossless,
                            activeTrackColor = EmeraldLossless
                        )
                    )
                }

                if (dualKeyboardEnabled) {
                    OutlinedButton(
                        onClick = {
                            if (editingSlot == 0) {
                                onUpdateSecondary(
                                    layoutProfile.secondary.copy(
                                        deckHeightDp = currentGeom.deckHeightDp,
                                        widthFraction = currentGeom.widthFraction,
                                        keyWidthDp = currentGeom.keyWidthDp
                                    )
                                )
                            } else {
                                onUpdatePrimary(
                                    layoutProfile.primary.copy(
                                        deckHeightDp = currentGeom.deckHeightDp,
                                        widthFraction = currentGeom.widthFraction,
                                        keyWidthDp = currentGeom.keyWidthDp
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("同步宽高与键宽到另一排键盘", fontSize = 12.sp, color = TextPrimary)
                    }
                }

                Text(
                    text = "提示：直接上下拖动键盘顶部横条可快速调高度，拖动键盘左右两侧手柄可调宽度。所有修改将自动绑定至「${layoutProfile.displayLabel}」，下次进入同布局自动恢复。",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                )
            ) {
                Text("完成")
            }
        },
        dismissButton = {
            TextButton(onClick = onResetCurrentLayout) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text("恢复默认", color = TextSecondary)
            }
        }
    )
}

// ============================================================================
// Score Title Dialog & PDF Export Dialog (WeChat / System / Save Local)
// ============================================================================

@Composable
private fun EditScoreTitleDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var titleText by remember(initialTitle) { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Text("自定义五线谱标题", fontWeight = FontWeight.Bold, color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "该标题将显示在五线谱正上方，并作为导出 PDF 曲谱的标题与文件名：",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it.replace('\n', ' ').take(50) },
                    singleLine = true,
                    label = { Text("曲谱标题") },
                    placeholder = { Text("例如：晴天 · 前奏旋律") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(titleText) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                )
            ) {
                Text("保存标题")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun ExportStaffPdfDialog(
    melody: MelodyStudioUiState,
    onUpdateTitle: (String) -> Unit,
    onShareWeChat: (String) -> Unit,
    onShareSystem: (String) -> Unit,
    onSaveLocalDownloads: (String) -> Unit,
    onSaveCustomFolder: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var titleInput by remember(melody.scoreTitle) { mutableStateOf(melody.scoreTitle) }
    val resolvedTitle = titleInput.trim().ifBlank { "未命名乐谱" }
    val staffTypeLabel = if (StaffScoreEngraver.needsGrandStaff(melody.notes)) {
        "高低音大谱表 (Grand Staff)"
    } else {
        "高音五线谱 (Treble Staff)"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Text(
                text = "导出五线谱为高清矢量 PDF",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = {
                        val cleaned = it.replace('\n', ' ').take(50)
                        titleInput = cleaned
                        onUpdateTitle(cleaned)
                    },
                    singleLine = true,
                    label = { Text("PDF 乐谱标题 / 文件名") },
                    suffix = { Text(".pdf", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = StudioSurfaceElevated,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "排版规格：A4 竖版印刷级矢量五线谱（放大不模糊）",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        val swingSummary = if (melody.swingPreset.isEnabled) {
                            " · ${melody.swingPreset.shortBadge}"
                        } else {
                            ""
                        }
                        val keySummary = if (melody.hasMidKeyChanges) {
                            "${melody.initialKeySignature.shortPillLabel} (含${melody.keySignatureEvents.size}处转调)"
                        } else {
                            melody.initialKeySignature.shortPillLabel
                        }
                        Text(
                            text = "谱表类型：$staffTypeLabel · $keySummary · ${melody.timeSignature.label} 拍 · ♩ = ${melody.bpm} BPM$swingSummary · 共 ${melody.totalBars} 小节",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                // Primary Action 1: Direct WeChat Share
                Button(
                    onClick = { onShareWeChat(resolvedTitle) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldLossless,
                        contentColor = OnAccentLight
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("发给微信好友 (.pdf)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // Primary Action 2: Save to Local Downloads
                Button(
                    onClick = { onSaveLocalDownloads(resolvedTitle) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = OnAccentLight
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SaveAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("存到手机本地 (Download/SoundTracker)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // Secondary Actions: Custom Folder SAF & System Share
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSaveCustomFolder(resolvedTitle) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("自选目录另存", fontSize = 11.5.sp, color = TextPrimary, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = { onShareSystem(resolvedTitle) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("系统更多分享", fontSize = 11.5.sp, color = TextPrimary, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = TextSecondary)
            }
        }
    )
}

// ============================================================================
// Time Signature (`拍号`), Global Swing, Tuplets & Thumb Undo/Redo Toolbar
// ============================================================================

@Composable
private fun TimeSignatureSelectorPill(
    timeSignature: TimeSignature,
    onSelectTimeSignature: (TimeSignature) -> Unit,
    onOpenCustomDialog: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val nonDefault = timeSignature != TimeSignature.FOUR_FOUR
    val shape = RoundedCornerShape(7.dp)

    Box {
        Surface(
            shape = shape,
            color = if (nonDefault) CyanPrimary.copy(alpha = 0.15f) else StudioSurfaceElevated,
            border = BorderStroke(1.dp, if (nonDefault) CyanPrimary else StudioBorder),
            modifier = Modifier
                .height(27.dp)
                .clip(shape)
                .clickable { expanded = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp)
            ) {
                Text(
                    text = "${timeSignature.label} 拍",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = if (nonDefault) CyanPrimary else TextPrimary,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = "选择或自定义拍号",
                    tint = if (nonDefault) CyanPrimary else TextSecondary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = StudioSurface
        ) {
            TimeSignature.PRESETS.forEach { option ->
                val selected = option.timeSignature == timeSignature
                DropdownMenuItem(
                    leadingIcon = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(22.dp)
                        ) {
                            Text(
                                text = "${option.timeSignature.safeNumerator}",
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                lineHeight = 11.sp,
                                color = if (selected) CyanPrimary else TextPrimary
                            )
                            Text(
                                text = "${option.timeSignature.safeDenominator}",
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                lineHeight = 11.sp,
                                color = if (selected) CyanPrimary else TextPrimary
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = option.title,
                                fontSize = 12.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) CyanPrimary else TextPrimary
                            )
                            Text(
                                text = option.subtitle,
                                fontSize = 10.5.sp,
                                color = TextMuted
                            )
                        }
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelectTimeSignature(option.timeSignature)
                    }
                )
            }

            HorizontalDivider(color = StudioBorder)
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = VioletAccent,
                        modifier = Modifier.size(16.dp)
                    )
                },
                text = {
                    Text(
                        text = "自定义拍号（任意分子 / 分母）...",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = VioletAccent
                    )
                },
                onClick = {
                    expanded = false
                    onOpenCustomDialog()
                }
            )
        }
    }
}

@Composable
private fun CustomTimeSignatureDialog(
    currentTimeSignature: TimeSignature,
    onConfirm: (TimeSignature) -> Unit,
    onDismiss: () -> Unit
) {
    var numerator by remember(currentTimeSignature) {
        mutableIntStateOf(currentTimeSignature.safeNumerator)
    }
    var denominator by remember(currentTimeSignature) {
        mutableIntStateOf(currentTimeSignature.safeDenominator)
    }
    var numeratorText by remember(currentTimeSignature) {
        mutableStateOf(currentTimeSignature.safeNumerator.toString())
    }

    val previewSig = TimeSignature(numerator, denominator).sanitized()
    val denomUnitName = when (previewSig.safeDenominator) {
        2 -> "二分音符"
        4 -> "四分音符"
        8 -> "八分音符"
        else -> "十六分音符"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Text(
                text = "设置乐谱拍号 (Time Signature)",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Live Classical Stacked Time Signature Preview Card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFAF7F0),
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(36.dp)
                                .background(StudioSurface, RoundedCornerShape(8.dp))
                                .border(1.dp, StudioBorder, RoundedCornerShape(8.dp))
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "${previewSig.safeNumerator}",
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                lineHeight = 19.sp,
                                color = TextPrimary
                            )
                            HorizontalDivider(
                                color = StudioBorder,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                            Text(
                                text = "${previewSig.safeDenominator}",
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                lineHeight = 19.sp,
                                color = TextPrimary
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "当前设置：${previewSig.label} 拍",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "以「$denomUnitName」为一拍，每小节共 ${previewSig.safeNumerator} 拍",
                                fontSize = 11.5.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = "五线谱小节线、连音符尾分组、节拍器强拍与 PDF 导出同步生效",
                                fontSize = 10.5.sp,
                                color = TextMuted
                            )
                        }
                    }
                }

                // Quick Preset Chips Row
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "常用拍号一键选择：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TimeSignature.PRESETS.forEach { preset ->
                            val selected = preset.timeSignature == previewSig
                            val chipShape = RoundedCornerShape(8.dp)
                            Surface(
                                shape = chipShape,
                                color = if (selected) CyanPrimary else StudioSurfaceElevated,
                                border = BorderStroke(1.dp, if (selected) CyanPrimary else StudioBorder),
                                modifier = Modifier
                                    .height(30.dp)
                                    .clip(chipShape)
                                    .clickable {
                                        numerator = preset.timeSignature.safeNumerator
                                        denominator = preset.timeSignature.safeDenominator
                                        numeratorText = numerator.toString()
                                    }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                ) {
                                    Text(
                                        text = preset.timeSignature.label,
                                        fontFamily = FontFamily.Serif,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (selected) OnAccentLight else TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Custom Numerator (`每小节拍数 1..16`)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "自定义分子（每小节几拍，支持 1 ~ 16）：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MelodySmallStepBtn("-") {
                            val next = (numerator - 1).coerceIn(1, 16)
                            numerator = next
                            numeratorText = next.toString()
                        }
                        OutlinedTextField(
                            value = numeratorText,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(2)
                                numeratorText = digits
                                digits.toIntOrNull()?.let { parsed ->
                                    numerator = parsed.coerceIn(1, 16)
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            suffix = { Text("拍/小节", fontSize = 11.sp, color = TextMuted) },
                            modifier = Modifier.weight(1f)
                        )
                        MelodySmallStepBtn("+") {
                            val next = (numerator + 1).coerceIn(1, 16)
                            numerator = next
                            numeratorText = next.toString()
                        }
                    }
                }

                // Custom Denominator (`以几分音符为一拍: 2 / 4 / 8 / 16`)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "自定义分母（以几分音符为一拍）：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TimeSignature.VALID_DENOMINATORS.forEach { den ->
                            val selected = denominator == den
                            val dShape = RoundedCornerShape(8.dp)
                            val denName = when (den) {
                                2 -> "2 (二分)"
                                4 -> "4 (四分)"
                                8 -> "8 (八分)"
                                else -> "16 (十六)"
                            }
                            Surface(
                                shape = dShape,
                                color = if (selected) CyanPrimary.copy(alpha = 0.16f) else StudioSurfaceElevated,
                                border = BorderStroke(1.dp, if (selected) CyanPrimary else StudioBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(dShape)
                                    .clickable { denominator = den }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                ) {
                                    MusicalNoteGlyphIcon(
                                        baseDenom = den,
                                        isDotted = false,
                                        tint = if (selected) CyanPrimary else TextPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = denName,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) CyanPrimary else TextSecondary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(previewSig) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                )
            ) {
                Text("应用 ${previewSig.label} 拍", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun SwingSelectorPill(
    swingPreset: SwingPreset,
    onSelectSwing: (SwingPreset) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val active = swingPreset.isEnabled
    val shape = RoundedCornerShape(7.dp)

    Box {
        Surface(
            shape = shape,
            color = if (active) AmberMastering.copy(alpha = 0.16f) else StudioSurfaceElevated,
            border = BorderStroke(1.dp, if (active) AmberMastering else StudioBorder),
            modifier = Modifier
                .height(27.dp)
                .clip(shape)
                .clickable { expanded = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp)
            ) {
                Text(
                    text = swingPreset.shortBadge,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) AmberMastering else TextSecondary,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = "选择全局 Swing 摇摆律动",
                    tint = if (active) AmberMastering else TextSecondary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = StudioSurface
        ) {
            SwingPreset.entries.forEach { preset ->
                val selected = preset == swingPreset
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = preset.label,
                                fontSize = 12.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) AmberMastering else TextPrimary
                            )
                            Text(
                                text = preset.menuSubtitle,
                                fontSize = 10.5.sp,
                                color = TextMuted
                            )
                        }
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = AmberMastering,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelectSwing(preset)
                    }
                )
            }
            HorizontalDivider(color = StudioBorder)
            Text(
                text = "按标准记谱惯例：谱面保持平直八分/十六分记谱并在谱头标注 Swing，试听与生成音轨全局生效",
                fontSize = 10.sp,
                color = TextMuted,
                modifier = Modifier
                    .widthIn(max = 268.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun TupletSelectorPill(
    activeTuplet: TupletPreset?,
    currentSlotNumber: Int,
    maxMeasureTicks: Int,
    timeSignatureLabel: String,
    onSelectTuplet: (TupletPreset?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        if (activeTuplet == null) {
            val shape = RoundedCornerShape(7.dp)
            Surface(
                shape = shape,
                color = VioletAccent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, VioletAccent.copy(alpha = 0.55f)),
                modifier = Modifier
                    .height(28.dp)
                    .clip(shape)
                    .clickable { expanded = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp)
                ) {
                    Text(
                        text = "连音",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = VioletAccent,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = "选择二连音/三连音/六连音",
                        tint = VioletAccent,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val leftShape = RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp)
                Surface(
                    shape = leftShape,
                    color = VioletAccent,
                    modifier = Modifier
                        .height(28.dp)
                        .clip(leftShape)
                        .clickable { expanded = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp, end = 4.dp)
                    ) {
                        Text(
                            text = "${activeTuplet.label} $currentSlotNumber/${activeTuplet.count}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = OnAccentLight,
                            maxLines = 1
                        )
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = "切换连音类型",
                            tint = OnAccentLight,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                val rightShape = RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)
                Surface(
                    shape = rightShape,
                    color = VioletAccent.copy(alpha = 0.84f),
                    modifier = Modifier
                        .height(28.dp)
                        .clip(rightShape)
                        .clickable { onSelectTuplet(null) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "退出连音模式",
                            tint = OnAccentLight,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = StudioSurface
        ) {
            if (activeTuplet != null) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    text = {
                        Text(
                            text = "关闭连音模式（恢复常规音符）",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelectTuplet(null)
                    }
                )
                HorizontalDivider(color = StudioBorder)
            }

            TupletPreset.entries.forEach { preset ->
                val selected = preset == activeTuplet
                val fitsInBar = preset.spanTicks <= maxMeasureTicks
                DropdownMenuItem(
                    enabled = fitsInBar,
                    leadingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            MusicalNoteGlyphIcon(
                                baseDenom = preset.writtenBaseDenom,
                                isDotted = false,
                                tint = when {
                                    selected -> VioletAccent
                                    fitsInBar -> TextPrimary
                                    else -> TextMuted.copy(alpha = 0.45f)
                                },
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "${preset.count}",
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = when {
                                    selected -> VioletAccent
                                    fitsInBar -> TextPrimary
                                    else -> TextMuted.copy(alpha = 0.45f)
                                }
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = preset.label,
                                fontSize = 12.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = when {
                                    selected -> VioletAccent
                                    fitsInBar -> TextPrimary
                                    else -> TextMuted.copy(alpha = 0.5f)
                                }
                            )
                            Text(
                                text = if (fitsInBar) {
                                    preset.menuSubtitle
                                } else {
                                    "超出当前 $timeSignatureLabel 拍单小节长度"
                                },
                                fontSize = 10.5.sp,
                                color = TextMuted
                            )
                        }
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = VioletAccent,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelectTuplet(preset)
                    }
                )
            }

            HorizontalDivider(color = StudioBorder)
            Text(
                text = "提示：点任意常规音符或右侧 ✕ 即可退出连音；1拍均分2音直接选「八分音符」",
                fontSize = 10.sp,
                color = TextMuted,
                modifier = Modifier
                    .widthIn(max = 272.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ThumbUndoRedoBackspaceCluster(
    canUndo: Boolean,
    canRedo: Boolean,
    showBackspace: Boolean,
    hasSelectedNote: Boolean,
    hasSelectedAnnotation: Boolean,
    showTextLabels: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBackspace: () -> Unit,
    onEditSelectedExpression: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (hasSelectedAnnotation) {
            MelodyTinyToggle(
                label = "改记号",
                active = true,
                activeColor = VioletAccent,
                onClick = onEditSelectedExpression
            )
            MelodyTinyToggle(
                label = "删记号",
                active = true,
                activeColor = DangerRed,
                onClick = onDeleteSelected
            )
        } else if (hasSelectedNote) {
            MelodyTinyToggle(
                label = "记号",
                active = true,
                activeColor = VioletAccent,
                onClick = onEditSelectedExpression
            )
            MelodyTinyToggle(
                label = "删选中",
                active = true,
                activeColor = DangerRed,
                onClick = onDeleteSelected
            )
        }

        val btnShape = RoundedCornerShape(8.dp)

        // Thumb-Zone Undo (`撤销`)
        Surface(
            shape = btnShape,
            color = if (canUndo) StudioSurfaceElevated else StudioSurfaceElevated.copy(alpha = 0.42f),
            border = BorderStroke(1.dp, if (canUndo) CyanPrimary.copy(alpha = 0.45f) else StudioBorder),
            modifier = Modifier
                .height(28.dp)
                .clip(btnShape)
                .clickable(enabled = canUndo, onClick = onUndo)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = if (showTextLabels) 8.dp else 9.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Undo,
                    contentDescription = "撤销",
                    tint = if (canUndo) CyanPrimary else TextMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(15.dp)
                )
                if (showTextLabels) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "撤销",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canUndo) TextPrimary else TextMuted.copy(alpha = 0.45f),
                        maxLines = 1
                    )
                }
            }
        }

        // Thumb-Zone Redo (`重做`)
        Surface(
            shape = btnShape,
            color = if (canRedo) StudioSurfaceElevated else StudioSurfaceElevated.copy(alpha = 0.42f),
            border = BorderStroke(1.dp, if (canRedo) CyanPrimary.copy(alpha = 0.45f) else StudioBorder),
            modifier = Modifier
                .height(28.dp)
                .clip(btnShape)
                .clickable(enabled = canRedo, onClick = onRedo)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = if (showTextLabels) 8.dp else 9.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Redo,
                    contentDescription = "重做",
                    tint = if (canRedo) CyanPrimary else TextMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(15.dp)
                )
                if (showTextLabels) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "重做",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canRedo) TextPrimary else TextMuted.copy(alpha = 0.45f),
                        maxLines = 1
                    )
                }
            }
        }

        // Thumb-Zone Backspace (`退格`)
        if (showBackspace) {
            Surface(
                shape = btnShape,
                color = StudioSurfaceElevated,
                border = BorderStroke(1.dp, StudioBorder),
                modifier = Modifier
                    .height(28.dp)
                    .clip(btnShape)
                    .clickable(onClick = onBackspace)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = if (showTextLabels) 8.dp else 9.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Backspace,
                        contentDescription = "退格删音",
                        tint = DangerRed,
                        modifier = Modifier.size(14.dp)
                    )
                    if (showTextLabels) {
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "退格",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepOrRecordControlsCluster(
    melody: MelodyStudioUiState,
    onSelectNoteDuration: (NoteDurationPreset) -> Unit,
    onSelectActiveTuplet: (TupletPreset?) -> Unit,
    onInsertRest: (NoteDurationPreset?) -> Unit,
    onExtendPreviousNote: () -> Unit,
    onTogglePhraseSlurMode: () -> Unit,
    onSlurSelectedOrExtendNext: () -> Unit,
    onClearSelectedSlur: () -> Unit,
    onOpenExpressionStudio: () -> Unit,
    onOpenTouchGuard: () -> Unit,
    onToggleChordMode: () -> Unit,
    onQuantizeNotes: () -> Unit,
    onToggleLoop: () -> Unit,
    onAddBars: () -> Unit
) {
    var showAllRestsMenu by remember { mutableStateOf(false) }

    val selNote = melody.selectedNote
    val activeBadgeSummary = buildString {
        if (selNote != null) {
            selNote.articulation?.let { append(it.symbolBadge) }
            selNote.accent?.let { append(it.symbolBadge) }
            if (selNote.hasFermata) append("延")
        } else {
            melody.pendingArticulation?.let { append(it.symbolBadge) }
            melody.pendingAccent?.let { append(it.symbolBadge) }
            if (melody.pendingFermata) append("延")
        }
    }
    val hasArmedOrSelectedExpr = activeBadgeSummary.isNotEmpty() || melody.selectedAnnotation != null
    val exprButtonLabel = when {
        melody.selectedAnnotation != null -> "改记号"
        selNote != null && activeBadgeSummary.isNotEmpty() -> "记号:$activeBadgeSummary"
        activeBadgeSummary.isNotEmpty() -> "下音:$activeBadgeSummary"
        else -> "记号·表情"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (melody.inputMode == MelodyInputMode.STEP_INPUT) {
            val isStraightMode = melody.activeTuplet == null

            // 1. Special Split Pill for Rests (`休止符`: compact Chinese label + live rest symbol)
            Box {
                val activeTuplet = melody.activeTuplet
                val restDenom = activeTuplet?.writtenBaseDenom ?: melody.noteDuration.baseDenom
                val restDotted = if (activeTuplet != null) false else melody.noteDuration.isDotted

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val leftRestShape = RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp)
                    Surface(
                        shape = leftRestShape,
                        color = WarmUmber.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, WarmUmber.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .height(29.dp)
                            .clip(leftRestShape)
                            .clickable { onInsertRest(null) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 7.dp)
                        ) {
                            MusicalRestGlyphIcon(
                                baseDenom = restDenom,
                                isDotted = restDotted,
                                tint = WarmUmber,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "休止符",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = WarmUmber,
                                maxLines = 1
                            )
                        }
                    }

                    val rightRestShape = RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)
                    Surface(
                        shape = rightRestShape,
                        color = WarmUmber.copy(alpha = 0.22f),
                        border = BorderStroke(1.dp, WarmUmber.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .height(29.dp)
                            .clip(rightRestShape)
                            .clickable { showAllRestsMenu = true }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowDropDown,
                                contentDescription = "选择任意休止符写入",
                                tint = WarmUmber,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                DropdownMenu(
                    expanded = showAllRestsMenu,
                    onDismissRequest = { showAllRestsMenu = false },
                    containerColor = StudioSurface
                ) {
                    if (melody.activeTuplet != null) {
                        val tup = melody.activeTuplet
                        DropdownMenuItem(
                            leadingIcon = {
                                MusicalRestGlyphIcon(
                                    baseDenom = tup.writtenBaseDenom,
                                    isDotted = false,
                                    tint = VioletAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            text = {
                                Text(
                                    text = "写入当前连音休止（${tup.restLabel}）",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VioletAccent
                                )
                            },
                            onClick = {
                                showAllRestsMenu = false
                                onInsertRest(null)
                            }
                        )
                        HorizontalDivider(color = StudioBorder)
                    }
                    NoteDurationPreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            leadingIcon = {
                                MusicalRestGlyphIcon(
                                    baseDenom = preset.baseDenom,
                                    isDotted = preset.isDotted,
                                    tint = WarmUmber,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            text = {
                                Text(
                                    text = "写入 ${preset.restLabel}",
                                    fontSize = 12.5.sp,
                                    color = TextPrimary
                                )
                            },
                            onClick = {
                                showAllRestsMenu = false
                                onInsertRest(preset)
                            }
                        )
                    }
                }
            }

            // 2. Icon-Only 8 Musical Note Duration Buttons (Zero Chinese Text, Wide Easy-Tap Targets!)
            NoteDurationPreset.entries.forEach { preset ->
                val active = isStraightMode && melody.noteDuration == preset
                val chipShape = RoundedCornerShape(8.dp)
                Surface(
                    shape = chipShape,
                    color = if (active) CyanPrimary.copy(alpha = 0.20f) else StudioSurfaceElevated,
                    border = BorderStroke(if (active) 1.4.dp else 1.dp, if (active) CyanPrimary else StudioBorder),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 38.dp)
                        .height(29.dp)
                        .clip(chipShape)
                        .clickable { onSelectNoteDuration(preset) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        MusicalNoteGlyphIcon(
                            baseDenom = preset.baseDenom,
                            isDotted = preset.isDotted,
                            tint = if (active) CyanPrimary else TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 3. Tuplet Selector (`二连音` / `三连音` / `五连音` / `六连音`)
            TupletSelectorPill(
                activeTuplet = melody.activeTuplet,
                currentSlotNumber = melody.currentTupletSlotNumber,
                maxMeasureTicks = melody.ticksPerBar,
                timeSignatureLabel = melody.timeSignature.label,
                onSelectTuplet = onSelectActiveTuplet
            )

            // 4. Same-Pitch Tie (`延音连线⌒`) + Multi-Pitch Phrase Slur (`乐句连线⌢`)
            MelodyTinyToggle(
                label = "延音连线⌒",
                active = false,
                activeColor = CyanPrimary,
                onClick = onExtendPreviousNote
            )
            PhraseSlurControlPill(
                melody = melody,
                onTogglePhraseSlurMode = onTogglePhraseSlurMode,
                onSlurSelectedOrExtendNext = onSlurSelectedOrExtendNext,
                onClearSelectedSlur = onClearSelectedSlur
            )

            // 5. Musical Expressions (`力度 · 奏法 · 速度`)
            MelodyTinyToggle(
                label = exprButtonLabel,
                active = hasArmedOrSelectedExpr,
                activeColor = VioletAccent,
                onClick = onOpenExpressionStudio
            )

            MelodyTinyToggle(
                label = if (melody.chordStepMode) "和弦:开" else "和弦",
                active = melody.chordStepMode,
                activeColor = AmberMastering,
                onClick = onToggleChordMode
            )
            MelodyTinyToggle(
                label = "循环",
                active = melody.loopPlayback,
                activeColor = CyanPrimary,
                onClick = onToggleLoop
            )
            MelodyTinyToggle(
                label = "+小节",
                active = false,
                activeColor = TextSecondary,
                onClick = onAddBars
            )
        } else {
            val qShape = RoundedCornerShape(7.dp)
            Surface(
                shape = qShape,
                color = CyanPrimary.copy(alpha = 0.13f),
                border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f)),
                modifier = Modifier
                    .height(28.dp)
                    .clip(qShape)
                    .clickable(enabled = melody.notes.isNotEmpty(), onClick = onQuantizeNotes)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GridOn,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "十六分自动对齐",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary,
                        maxLines = 1
                    )
                }
            }
            PhraseSlurControlPill(
                melody = melody,
                onTogglePhraseSlurMode = onTogglePhraseSlurMode,
                onSlurSelectedOrExtendNext = onSlurSelectedOrExtendNext,
                onClearSelectedSlur = onClearSelectedSlur
            )
            MelodyTinyToggle(
                label = exprButtonLabel,
                active = hasArmedOrSelectedExpr,
                activeColor = VioletAccent,
                onClick = onOpenExpressionStudio
            )
            MelodyTinyToggle(
                label = "循环",
                active = melody.loopPlayback,
                activeColor = CyanPrimary,
                onClick = onToggleLoop
            )
            MelodyTinyToggle(
                label = "+小节",
                active = false,
                activeColor = TextSecondary,
                onClick = onAddBars
            )
            TouchGuardSelectorPill(
                config = melody.touchGuardConfig,
                onClick = onOpenTouchGuard
            )
            Text(
                text = if (melody.isRecording) "跟着节拍弹奏，五线谱将实时生成音符" else "点「开始弹录」跟节拍弹奏",
                fontSize = 11.sp,
                color = if (melody.isRecording) DangerRed else TextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PhraseSlurControlPill(
    melody: MelodyStudioUiState,
    onTogglePhraseSlurMode: () -> Unit,
    onSlurSelectedOrExtendNext: () -> Unit,
    onClearSelectedSlur: () -> Unit
) {
    val liveSlurActive = melody.activeSlurGroupId != null
    val selectedPitched = melody.selectedNote?.takeIf { !it.isRest }

    if (liveSlurActive) {
        val shape = RoundedCornerShape(7.dp)
        Surface(
            shape = shape,
            color = EmeraldLossless,
            modifier = Modifier
                .height(28.dp)
                .clip(shape)
                .clickable(onClick = onTogglePhraseSlurMode)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 9.dp)
            ) {
                Text(
                    text = "乐句连线中⌢ · 结束",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = OnAccentLight,
                    maxLines = 1
                )
            }
        }
    } else if (selectedPitched != null) {
        val inSlur = selectedPitched.slurGroupId != null
        Row(verticalAlignment = Alignment.CenterVertically) {
            val leftShape = RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp)
            Surface(
                shape = leftShape,
                color = EmeraldLossless.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, EmeraldLossless),
                modifier = Modifier
                    .height(28.dp)
                    .clip(leftShape)
                    .clickable(onClick = if (inSlur) onSlurSelectedOrExtendNext else onTogglePhraseSlurMode)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = if (inSlur) "乐句+1音⌢" else "乐句连线⌢",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldLossless,
                        maxLines = 1
                    )
                }
            }
            val rightShape = RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)
            Surface(
                shape = rightShape,
                color = if (inSlur) DangerRed.copy(alpha = 0.14f) else EmeraldLossless.copy(alpha = 0.24f),
                border = BorderStroke(1.dp, if (inSlur) DangerRed.copy(alpha = 0.7f) else EmeraldLossless),
                modifier = Modifier
                    .height(28.dp)
                    .clip(rightShape)
                    .clickable(onClick = if (inSlur) onClearSelectedSlur else onSlurSelectedOrExtendNext)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 7.dp)
                ) {
                    Text(
                        text = if (inSlur) "取消" else "+连下音",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (inSlur) DangerRed else EmeraldLossless,
                        maxLines = 1
                    )
                }
            }
        }
    } else {
        MelodyTinyToggle(
            label = "乐句连线⌢",
            active = false,
            activeColor = EmeraldLossless,
            onClick = onTogglePhraseSlurMode
        )
    }
}

/**
 * Custom vector-drawn musical note icon (`全音符`, `二分音符`, `四分音符`, `八分音符`, `十六分音符` + `附点`).
 */
@Composable
private fun MusicalNoteGlyphIcon(
    baseDenom: Int,
    isDotted: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val argb = tint.toArgb()
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val nc = drawContext.canvas.nativeCanvas
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = android.graphics.Paint.Style.FILL
        }
        val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = max(1.45f, w * 0.105f)
            strokeCap = android.graphics.Paint.Cap.ROUND
        }

        val cx = when {
            baseDenom == 1 && !isDotted -> w * 0.50f
            isDotted && baseDenom >= 8 -> w * 0.30f
            isDotted -> w * 0.35f
            baseDenom >= 8 -> w * 0.40f
            else -> w * 0.45f
        }
        val cy = if (baseDenom == 1) h * 0.54f else h * 0.73f
        val rx = if (baseDenom == 1) w * 0.26f else w * 0.22f
        val ry = if (baseDenom == 1) h * 0.17f else h * 0.155f

        nc.save()
        nc.translate(cx, cy)
        if (baseDenom != 1) nc.rotate(-22f)
        if (baseDenom <= 2) {
            strokePaint.strokeWidth = max(1.55f, w * 0.115f)
            nc.drawOval(android.graphics.RectF(-rx, -ry, rx, ry), strokePaint)
        } else {
            nc.drawOval(android.graphics.RectF(-rx, -ry, rx, ry), paint)
        }
        nc.restore()

        if (baseDenom >= 2) {
            val stemX = cx + rx * 0.86f
            val tipY = h * 0.11f
            nc.drawLine(stemX, cy, stemX, tipY, strokePaint)

            if (baseDenom >= 8) {
                val p1 = android.graphics.Path().apply {
                    moveTo(stemX, tipY)
                    cubicTo(
                        stemX + w * 0.25f, tipY + h * 0.09f,
                        stemX + w * 0.31f, tipY + h * 0.23f,
                        stemX + w * 0.17f, tipY + h * 0.36f
                    )
                }
                nc.drawPath(p1, strokePaint)
            }
            if (baseDenom >= 16) {
                val tipY2 = tipY + h * 0.18f
                val p2 = android.graphics.Path().apply {
                    moveTo(stemX, tipY2)
                    cubicTo(
                        stemX + w * 0.25f, tipY2 + h * 0.09f,
                        stemX + w * 0.31f, tipY2 + h * 0.23f,
                        stemX + w * 0.17f, tipY2 + h * 0.35f
                    )
                }
                nc.drawPath(p2, strokePaint)
            }
        }

        if (isDotted) {
            val dotX = if (baseDenom >= 8) cx + rx * 2.05f else cx + rx * 1.82f
            nc.drawCircle(dotX, cy - ry * 0.18f, max(1.8f, w * 0.105f), paint)
        }
    }
}

/**
 * Custom vector-drawn musical Rest icon (`全休止`, `二分休止`, `四分休止`, `八分休止`, `十六分休止` + `附点`).
 */
@Composable
private fun MusicalRestGlyphIcon(
    baseDenom: Int,
    isDotted: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val argb = tint.toArgb()
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val nc = drawContext.canvas.nativeCanvas
        val lineSpace = h * 0.22f
        val topY = h * 0.06f
        val rx = if (isDotted) w * 0.40f else w * 0.50f
        StaffScoreEngraver.drawRestGlyph(
            canvas = nc,
            x = rx,
            staffTopY = topY,
            lineSpacing = lineSpace,
            baseDenom = baseDenom,
            isDotted = isDotted,
            color = argb
        )
    }
}

// ============================================================================
// Sheet Title Header + Interactive Real 5-Line Staff Canvas (Point 4)
// ============================================================================

@Composable
private fun StaffTitleBanner(
    scoreTitle: String,
    activeKeySignature: KeySignature,
    timeSignature: TimeSignature,
    bpm: Int,
    swingPreset: SwingPreset,
    barNum: Int,
    beatNum: Int,
    isGrandStaff: Boolean,
    onEditTitleClick: () -> Unit,
    onExportPdfClick: () -> Unit
) {
    val swingTag = swingPreset.scoreMarking?.let { " · $it" } ?: ""
    Surface(
        color = Color(0xFFFAF7F0),
        modifier = Modifier
            .fillMaxWidth()
            .height(29.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onEditTitleClick)
                    .padding(vertical = 2.dp, horizontal = 3.dp)
            ) {
                Text(
                    text = "《${scoreTitle.ifBlank { "未命名乐谱" }}》",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.Rounded.DriveFileRenameOutline,
                    contentDescription = "编辑标题",
                    tint = CyanPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${if (isGrandStaff) "大谱表" else "高音谱表"} · ${activeKeySignature.shortPillLabel} · ${timeSignature.label}拍 · ♩=$bpm$swingTag · 第${barNum}小节第${beatNum}拍",
                    fontSize = 10.5.sp,
                    color = if (swingPreset.isEnabled) WarmUmber else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = "导出五线谱PDF ▸",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = VioletAccent,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onExportPdfClick)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun InteractiveStaffScoreCanvas(
    melody: MelodyStudioUiState,
    isTablet: Boolean,
    onTapKeySignatureHeader: () -> Unit,
    onTapTimeSignatureHeader: () -> Unit,
    onTapCursorTick: (Int) -> Unit,
    onSelectNote: (String?) -> Unit,
    onSelectAnnotation: (SelectedScoreAnnotation?) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val baseClefHeaderWidthPx = with(density) { (if (isTablet) 68.dp else 56.dp).toPx() }
    val baseBarWidthPx = with(density) { (if (isTablet) 248.dp else 196.dp).toPx() }
    val quarterBeatsPerBar = melody.quarterBeatsPerBar.coerceAtLeast(0.25f)
    val barWidthScale = (0.55f + 0.45f * (quarterBeatsPerBar / 4f)).coerceIn(0.68f, 1.48f)
    val barWidthPx = baseBarWidthPx * barWidthScale

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var lastHitTargets by remember { mutableStateOf<List<StaffHitTarget>>(emptyList()) }

    val maxKeyBar = (melody.keySignatureEvents.maxOfOrNull { it.safeBarIndex + 1 } ?: 1)
    val totalBars = maxOf(
        melody.totalBars,
        ceil((melody.notes.maxOfOrNull { it.endBeat } ?: quarterBeatsPerBar) / quarterBeatsPerBar).toInt(),
        maxKeyBar
    ).coerceAtLeast(1)

    val isGrandStaff = StaffScoreEngraver.needsGrandStaff(melody.notes)
    val estLineSpacing = if (canvasSize.height > 0) {
        if (isGrandStaff) {
            (canvasSize.height / 15.6f).coerceIn(5.6f, 11.8f)
        } else {
            (canvasSize.height / 9.6f).coerceIn(6.6f, 13.8f)
        }
    } else {
        with(density) { 9.5.dp.toPx() }
    }
    val visibleFirstBar = floor(scrollX / barWidthPx.coerceAtLeast(1f)).toInt().coerceIn(0, totalBars - 1)
    val viewportActiveKey = melody.keySignatureAtBar(visibleFirstBar)
    val effectiveClefHeaderWidthPx = StaffScoreEngraver.computeStickyHeaderWidthPx(
        baseHeaderPx = baseClefHeaderWidthPx,
        activeKeySignature = viewportActiveKey,
        lineSpacingEstimate = estLineSpacing
    )

    // Auto-follow live playhead or step cursor smoothly across measures
    val focusBeat = if (melody.isPlaying || melody.isRecording) {
        melody.playheadBeat
    } else {
        melody.cursorTick.toFloat() / TICKS_PER_BEAT.toFloat()
    }
    LaunchedEffect(focusBeat, canvasSize.width, totalBars, barWidthPx, quarterBeatsPerBar, effectiveClefHeaderWidthPx) {
        if (canvasSize.width <= 0) return@LaunchedEffect
        val viewportW = (canvasSize.width - effectiveClefHeaderWidthPx).coerceAtLeast(80f)
        val focusX = (focusBeat / quarterBeatsPerBar) * barWidthPx
        val totalContentW = totalBars * barWidthPx + barWidthPx * 0.25f
        val maxScrollX = (totalContentW - viewportW).coerceAtLeast(0f)
        if (focusX < scrollX + barWidthPx * 0.12f) {
            scrollX = (focusX - barWidthPx * 0.12f).coerceIn(0f, maxScrollX)
        } else if (focusX > scrollX + viewportW - barWidthPx * 0.22f) {
            scrollX = (focusX - viewportW * 0.45f).coerceIn(0f, maxScrollX)
        }
    }

    val latestMelody by rememberUpdatedState(melody)
    val latestScrollX by rememberUpdatedState(scrollX)
    val latestTotalBars by rememberUpdatedState(totalBars)
    val latestBarWidthPx by rememberUpdatedState(barWidthPx)
    val latestHeaderWidthPx by rememberUpdatedState(effectiveClefHeaderWidthPx)
    val latestLineSpacingPx by rememberUpdatedState(estLineSpacing)
    val latestOnTapKeySig by rememberUpdatedState(onTapKeySignatureHeader)
    val latestOnTapTimeSig by rememberUpdatedState(onTapTimeSignatureHeader)
    val latestOnSelectNote by rememberUpdatedState(onSelectNote)
    val latestOnSelectAnnotation by rememberUpdatedState(onSelectAnnotation)
    val latestOnTapCursorTick by rememberUpdatedState(onTapCursorTick)

    val palette = remember {
        StaffScoreEngraver.OnScreenPalette(
            inkColor = android.graphics.Color.rgb(40, 36, 32),
            staffLineColor = android.graphics.Color.rgb(122, 114, 102),
            barLineColor = android.graphics.Color.rgb(88, 80, 72),
            slot0Color = android.graphics.Color.rgb(38, 34, 30),
            slot1Color = android.graphics.Color.rgb(24, 118, 90),
            selectedColor = android.graphics.Color.rgb(122, 74, 204),
            cursorColor = android.graphics.Color.rgb(201, 100, 66),
            playheadColor = android.graphics.Color.rgb(196, 54, 44)
        )
    }

    Canvas(
        modifier = modifier
            .background(Color(0xFFFAF7F0))
            .clipToBounds()
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val viewportW = (size.width - latestHeaderWidthPx).coerceAtLeast(1f)
                    val bw = latestBarWidthPx
                    val maxX = (latestTotalBars * bw + bw * 0.25f - viewportW).coerceAtLeast(0f)
                    scrollX = (scrollX - dragAmount.x).coerceIn(0f, maxX)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val curHeaderW = latestHeaderWidthPx
                    if (pos.x < curHeaderW) {
                        val timeSigZoneLeft = curHeaderW - latestLineSpacingPx * 1.90f
                        if (pos.x >= timeSigZoneLeft) {
                            latestOnTapTimeSig()
                        } else {
                            latestOnTapKeySig()
                        }
                        return@detectTapGestures
                    }
                    val hit = lastHitTargets.firstOrNull { t ->
                        t.bounds.contains(pos.x, pos.y)
                    }
                    if (hit != null) {
                        if (hit.annotation != null) {
                            val alreadySelected = latestMelody.selectedAnnotation == hit.annotation
                            if (hit.annotation is SelectedScoreAnnotation.KeyChange) {
                                if (alreadySelected) {
                                    latestOnTapKeySig()
                                } else {
                                    latestOnSelectAnnotation(hit.annotation)
                                    latestOnTapCursorTick(hit.startTick)
                                }
                            } else {
                                latestOnSelectAnnotation(if (alreadySelected) null else hit.annotation)
                            }
                        } else if (hit.noteId != null) {
                            latestOnSelectNote(
                                if (latestMelody.selectedNoteId == hit.noteId) null else hit.noteId
                            )
                        }
                        return@detectTapGestures
                    }

                    latestOnSelectNote(null)
                    latestOnSelectAnnotation(null)
                    val tick = StaffScoreEngraver.mapTouchXToTick(
                        touchX = pos.x,
                        scrollX = latestScrollX,
                        clefHeaderWidth = curHeaderW,
                        barWidth = latestBarWidthPx,
                        lineSpacingEstimate = latestLineSpacingPx,
                        totalBars = latestTotalBars,
                        melody = latestMelody
                    )
                    latestOnTapCursorTick(tick)
                }
            }
    ) {
        val targets = StaffScoreEngraver.drawInteractiveTimelineStaff(
            canvas = drawContext.canvas.nativeCanvas,
            viewportWidth = size.width,
            viewportHeight = size.height,
            scrollX = scrollX,
            clefHeaderWidth = effectiveClefHeaderWidthPx,
            barWidth = barWidthPx,
            melody = melody,
            palette = palette
        )
        lastHitTargets = targets
    }
}

// ============================================================================
// Self-Contained Resizable Independent Keyboard Deck (Point 2)
//   - Top drag handle for free Height resizing
//   - Side grip handles (when < 100% or at edges) + `[尺寸]` button for Width & Key Width
//   - Continuous `visibleWhiteKeysFloat = actualWidthDp / keyWidthDp`
// ============================================================================

@Composable
private fun IndependentKeyboardDeck(
    deckTitle: String?,
    accentColor: Color,
    instrument: StudioInstrument,
    sustainOn: Boolean,
    sustainDurationSec: Float,
    preferFlats: Boolean,
    isRecording: Boolean,
    touchGuardConfig: PianoTouchGuardConfig,
    showInlineInstrumentAndSustain: Boolean,
    availContainerWidthDp: Float,
    geometry: KeyboardDeckGeometry,
    onSelectInstrument: (StudioInstrument) -> Unit,
    onToggleSustain: () -> Unit,
    onEditSustainDuration: () -> Unit,
    onOpenSizeConfig: () -> Unit,
    onHeightDeltaDp: (Float) -> Unit,
    onWidthFractionDelta: (Float) -> Unit,
    onScrollWhiteKeyChange: (newWhiteKey: Float, snapFinished: Boolean) -> Unit,
    onPianoKeyDown: (midiNote: Int, velocity: Float, isGlissando: Boolean) -> Unit,
    onPianoKeyUp: (midiNote: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var showInstDropdown by remember { mutableStateOf(false) }
    var activeKeyPointerCount by remember { mutableIntStateOf(0) }

    val deckChromeLocked = touchGuardConfig.isEnabled &&
        touchGuardConfig.lockChromeWhilePlaying &&
        (isRecording || activeKeyPointerCount > 0)
    val showRecordingLockBadge = touchGuardConfig.isEnabled &&
        touchGuardConfig.lockChromeWhilePlaying &&
        isRecording

    val latestChromeLocked by rememberUpdatedState(deckChromeLocked)
    val latestOnHeightDelta by rememberUpdatedState(onHeightDeltaDp)
    val latestOnWidthDelta by rememberUpdatedState(onWidthFractionDelta)

    val actualDeckWidthDp = (availContainerWidthDp * geometry.widthFraction).coerceIn(140f, availContainerWidthDp)
    val visibleWhiteKeysFloat = (actualDeckWidthDp / geometry.keyWidthDp).coerceIn(3.5f, 46f)
    val rangeLabel = MusicalNotationHelper.formatVisibleRangeLabel(geometry.scrollWhiteKey, visibleWhiteKeysFloat)

    val availWidthPx = with(density) { availContainerWidthDp.dp.toPx().coerceAtLeast(1f) }
    val showSideRails = geometry.widthFraction < 0.995f

    Box(
        modifier = modifier.background(Color(0xFFE7E0D4)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (showSideRails) {
                KeyboardSideWidthGrip(
                    accentColor = accentColor,
                    isLeft = true,
                    locked = deckChromeLocked,
                    onDragPx = { dx ->
                        // Dragging left edge left (dx < 0) widens symmetrically; right (dx > 0) narrows
                        latestOnWidthDelta((-2f * dx) / availWidthPx)
                    }
                )
            }

            Column(
                modifier = Modifier
                    .width(actualDeckWidthDp.dp)
                    .fillMaxHeight()
                    .background(StudioSurface)
                    .border(
                        width = if (showSideRails) 1.dp else 0.dp,
                        color = if (showSideRails) StudioBorder else Color.Transparent
                    )
            ) {
                // 1. Top Drag Handle Bar for Free Vertical Height Adjustment (8.dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(StudioSurfaceElevated)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, dragAmountPx ->
                                change.consume()
                                if (!latestChromeLocked) {
                                    val deltaDp = with(density) { (-dragAmountPx).toDp().value }
                                    latestOnHeightDelta(deltaDp)
                                }
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .width(34.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                accentColor.copy(alpha = if (deckChromeLocked) 0.22f else 0.55f)
                            )
                    )
                }

                // 2. Top Control + Draggable C1..C8 Range Strip + Size Config Button (29.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(29.dp)
                        .background(StudioSurfaceElevated.copy(alpha = 0.80f))
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (deckTitle != null) {
                        Text(
                            text = deckTitle,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            maxLines = 1
                        )
                    }

                    if (showInlineInstrumentAndSustain) {
                        Box {
                            val instShape = RoundedCornerShape(6.dp)
                            Surface(
                                shape = instShape,
                                color = accentColor.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .height(23.dp)
                                    .clip(instShape)
                                    .clickable(enabled = !deckChromeLocked) { showInstDropdown = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 6.dp, end = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Piano,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = instrument.shortName,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor,
                                        maxLines = 1
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowDropDown,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showInstDropdown,
                                onDismissRequest = { showInstDropdown = false },
                                containerColor = StudioSurface
                            ) {
                                StudioInstrument.entries.forEach { opt ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = opt.displayName,
                                                fontSize = 12.sp,
                                                fontWeight = if (opt == instrument) FontWeight.Bold else FontWeight.Normal,
                                                color = if (opt == instrument) accentColor else TextPrimary
                                            )
                                        },
                                        onClick = {
                                            showInstDropdown = false
                                            onSelectInstrument(opt)
                                        }
                                    )
                                }
                            }
                        }

                        SustainControlPair(
                            sustainOn = sustainOn,
                            sustainSec = sustainDurationSec,
                            accentColor = accentColor,
                            compact = true,
                            onToggleSustain = onToggleSustain,
                            onEditDuration = onEditSustainDuration
                        )
                    }

                    // Live Range Readout Pill
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = StudioSurface,
                        border = BorderStroke(0.8.dp, StudioBorder),
                        modifier = Modifier.height(22.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 5.dp)
                        ) {
                            if (showRecordingLockBadge) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = "弹录防误移已锁定",
                                    tint = EmeraldLossless,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                            }
                            Text(
                                text = rangeLabel,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1
                            )
                        }
                    }

                    // Full-Range C1..C8 Interactive Draggable Mini-Piano Overview Bar
                    MiniPianoRangeDragBar(
                        scrollWhiteKey = geometry.scrollWhiteKey,
                        visibleWhiteKeysFloat = visibleWhiteKeysFloat,
                        accentColor = accentColor,
                        locked = deckChromeLocked,
                        guardAccidentalOvershoot = touchGuardConfig.isEnabled,
                        onScrollChange = onScrollWhiteKeyChange,
                        modifier = Modifier
                            .weight(1f)
                            .height(22.dp)
                    )

                    // Width / Height / Key-Width Adjuster Button (`尺寸`)
                    val sizeBtnShape = RoundedCornerShape(6.dp)
                    Surface(
                        shape = sizeBtnShape,
                        color = StudioSurface,
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .height(23.dp)
                            .clip(sizeBtnShape)
                            .clickable(enabled = !deckChromeLocked, onClick = onOpenSizeConfig)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (showRecordingLockBadge) Icons.Rounded.Lock else Icons.Rounded.Tune,
                                contentDescription = "调节键盘宽高与键宽",
                                tint = if (deckChromeLocked) TextMuted else accentColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "尺寸",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (deckChromeLocked) TextMuted else TextPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }

                HorizontalDivider(color = StudioBorder.copy(alpha = 0.7f), thickness = 0.75.dp)

                // 3. Multi-Touch Polyphonic Velocity & Glissando-Aware Anti-Mistouch Piano Keys
                GarageBandPianoKeyboard(
                    scrollWhiteKey = geometry.scrollWhiteKey,
                    visibleWhiteKeysFloat = visibleWhiteKeysFloat,
                    preferFlats = preferFlats,
                    touchGuardConfig = touchGuardConfig,
                    accentColor = accentColor,
                    onActivePointerCountChange = { activeKeyPointerCount = it },
                    onPianoKeyDown = onPianoKeyDown,
                    onPianoKeyUp = onPianoKeyUp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            if (showSideRails) {
                KeyboardSideWidthGrip(
                    accentColor = accentColor,
                    isLeft = false,
                    locked = deckChromeLocked,
                    onDragPx = { dx ->
                        // Dragging right edge right (dx > 0) widens symmetrically; left (dx < 0) narrows
                        latestOnWidthDelta((2f * dx) / availWidthPx)
                    }
                )
            }
        }
    }
}

@Composable
private fun KeyboardSideWidthGrip(
    accentColor: Color,
    isLeft: Boolean,
    locked: Boolean,
    onDragPx: (Float) -> Unit
) {
    val latestDrag by rememberUpdatedState(onDragPx)
    val latestLocked by rememberUpdatedState(locked)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(12.dp)
            .fillMaxHeight()
            .pointerInput(isLeft) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    if (!latestLocked) {
                        latestDrag(dragAmount)
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor.copy(alpha = if (locked) 0.22f else 0.55f))
        )
    }
}

@Composable
private fun MiniPianoRangeDragBar(
    scrollWhiteKey: Float,
    visibleWhiteKeysFloat: Float,
    accentColor: Color,
    locked: Boolean,
    guardAccidentalOvershoot: Boolean,
    onScrollChange: (Float, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val maxScroll = (TOTAL_VIRTUAL_WHITE_KEYS.toFloat() - visibleWhiteKeysFloat).coerceAtLeast(0f)
    val clampedScroll = scrollWhiteKey.coerceIn(0f, maxScroll)

    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val latestScroll by rememberUpdatedState(clampedScroll)
    val latestVisible by rememberUpdatedState(visibleWhiteKeysFloat)
    val latestMaxScroll by rememberUpdatedState(maxScroll)
    val latestLocked by rememberUpdatedState(locked)
    val latestGuard by rememberUpdatedState(guardAccidentalOvershoot)
    val latestOnScroll by rememberUpdatedState(onScrollChange)

    val horizSlopPx = with(density) { 3.5.dp.toPx() }
    val maxTapSlipYPx = with(density) { 9.dp.toPx() }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFE9E3D8))
            .border(1.dp, StudioBorder, RoundedCornerShape(6.dp))
            .onSizeChanged { barSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (latestLocked) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ch.consume()
                            if (!ch.pressed) break
                        }
                        return@awaitEachGesture
                    }

                    val wPx = barSize.width.toFloat().coerceAtLeast(1f)
                    val hPx = barSize.height.toFloat().coerceAtLeast(1f)
                    val downPos = down.position
                    val downTimeMs = SystemClock.uptimeMillis()
                    val downWhitePos = (downPos.x / wPx) * TOTAL_VIRTUAL_WHITE_KEYS.toFloat()

                    val curStart = latestScroll
                    val curEnd = curStart + latestVisible
                    val insideWindow = downWhitePos in (curStart - 1.2f)..(curEnd + 1.2f)
                    val grabOffset = if (insideWindow) {
                        downWhitePos - curStart
                    } else {
                        latestVisible / 2f
                    }

                    if (!latestGuard && !insideWindow) {
                        val jumped = (downWhitePos - latestVisible / 2f).coerceIn(0f, latestMaxScroll)
                        latestOnScroll(jumped, false)
                    }

                    var isDragging = !latestGuard
                    var lastVal = if (!latestGuard && !insideWindow) {
                        (downWhitePos - latestVisible / 2f).coerceIn(0f, latestMaxScroll)
                    } else {
                        latestScroll
                    }
                    var maxAbsDy = 0f
                    var finalPos = downPos

                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        finalPos = ch.position
                        maxAbsDy = max(maxAbsDy, abs(ch.position.y - downPos.y))
                        if (!ch.pressed) {
                            ch.consume()
                            break
                        }
                        val absDx = abs(ch.position.x - downPos.x)
                        if (!isDragging && absDx >= horizSlopPx && absDx >= abs(ch.position.y - downPos.y) * 0.7f) {
                            isDragging = true
                        }
                        if (isDragging) {
                            val curWhitePos = (ch.position.x / wPx) * TOTAL_VIRTUAL_WHITE_KEYS.toFloat()
                            val next = (curWhitePos - grabOffset).coerceIn(0f, latestMaxScroll)
                            lastVal = next
                            latestOnScroll(next, false)
                        }
                        ch.consume()
                    }

                    if (isDragging) {
                        latestOnScroll(lastVal.roundToInt().toFloat().coerceIn(0f, latestMaxScroll), true)
                    } else if (!insideWindow) {
                        // Guarded mode: jump on deliberate clean tap inside bar, ignore downward black-key overshoot
                        val tapDurationMs = SystemClock.uptimeMillis() - downTimeMs
                        val stayedInBar = finalPos.y in (-maxTapSlipYPx)..(hPx + maxTapSlipYPx) &&
                            maxAbsDy <= maxTapSlipYPx * 1.25f
                        if (tapDurationMs <= 340L && stayedInBar) {
                            val jumped = (downWhitePos - latestVisible / 2f).roundToInt().toFloat()
                                .coerceIn(0f, latestMaxScroll)
                            latestOnScroll(jumped, true)
                        }
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val unitW = w / TOTAL_VIRTUAL_WHITE_KEYS.toFloat()

        val labelPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 8.sp.toPx()
            color = android.graphics.Color.argb(175, 92, 85, 76)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        for (i in 0 until TOTAL_VIRTUAL_WHITE_KEYS) {
            val x = i * unitW
            val isOctaveC = (i % 7 == 0)
            drawLine(
                color = if (isOctaveC) Color(0xFFB5ACA0) else Color(0xFFD6CFC3),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = if (isOctaveC) 1.2f else 0.6f
            )
            if (isOctaveC && i in 7..42 && unitW * 7f >= 22.dp.toPx()) {
                val octNum = (i / 7) + 1
                drawContext.canvas.nativeCanvas.drawText(
                    "C$octNum",
                    x + 2.dp.toPx(),
                    h - 3.dp.toPx(),
                    labelPaint
                )
            }
            if (MusicalNotationHelper.hasSharpAfterWhiteKey(i)) {
                val bx = (i + 1) * unitW - unitW * 0.3f
                drawRect(
                    color = Color(0xFF433F3A),
                    topLeft = Offset(bx, 0f),
                    size = Size(unitW * 0.6f, h * 0.46f)
                )
            }
        }

        val winX = (clampedScroll / TOTAL_VIRTUAL_WHITE_KEYS.toFloat()) * w
        val winW = ((visibleWhiteKeysFloat / TOTAL_VIRTUAL_WHITE_KEYS.toFloat()) * w)
            .coerceAtMost(w - winX)

        drawRoundRect(
            color = accentColor.copy(alpha = if (locked) 0.14f else 0.24f),
            topLeft = Offset(winX, 1f),
            size = Size(winW, h - 2f),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
        )
        drawRoundRect(
            color = if (locked) accentColor.copy(alpha = 0.55f) else accentColor,
            topLeft = Offset(winX, 1f),
            size = Size(winW, h - 2f),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
            style = Stroke(width = 1.8.dp.toPx())
        )

        val gripCenterX = winX + winW / 2f
        if (winW >= 18.dp.toPx()) {
            for (d in -1..1) {
                val gx = gripCenterX + d * 3.dp.toPx()
                drawLine(
                    color = accentColor.copy(alpha = if (locked) 0.45f else 0.85f),
                    start = Offset(gx, h * 0.28f),
                    end = Offset(gx, h * 0.72f),
                    strokeWidth = 1.2.dp.toPx()
                )
            }
        }
    }
}

// ============================================================================
// Multi-Touch GarageBand Piano Keyboard (Glissando-Preserving Anti-Mistouch!)
// ============================================================================

private data class PianoKeyBox(
    val midiNote: Int,
    val isBlack: Boolean,
    val rect: Rect
)

private data class PointerTouchState(
    val downPos: Offset,
    val downTimeMs: Long,
    val initialMidi: Int,
    val initialIsBlack: Boolean,
    val currentMidi: Int,
    val currentIsBlack: Boolean,
    val isGlissandoUnlocked: Boolean = false,
    val lastSwitchPos: Offset = downPos,
    val lastSwitchTimeMs: Long = downTimeMs,
    val previousMidiBeforeSwitch: Int = -1
)

@Composable
private fun GarageBandPianoKeyboard(
    scrollWhiteKey: Float,
    visibleWhiteKeysFloat: Float,
    preferFlats: Boolean,
    touchGuardConfig: PianoTouchGuardConfig,
    accentColor: Color,
    onActivePointerCountChange: (Int) -> Unit,
    onPianoKeyDown: (midiNote: Int, velocity: Float, isGlissando: Boolean) -> Unit,
    onPianoKeyUp: (midiNote: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val activePointers = remember { mutableStateMapOf<PointerId, PointerTouchState>() }

    val latestGuard by rememberUpdatedState(touchGuardConfig)
    val latestOnActiveCount by rememberUpdatedState(onActivePointerCountChange)
    val latestOnDown by rememberUpdatedState(onPianoKeyDown)
    val latestOnUp by rememberUpdatedState(onPianoKeyUp)

    LaunchedEffect(activePointers.size) {
        latestOnActiveCount(activePointers.size)
    }

    DisposableEffect(Unit) {
        onDispose {
            val held = activePointers.values.map { it.currentMidi }.toSet()
            activePointers.clear()
            latestOnActiveCount(0)
            held.forEach { latestOnUp(it) }
        }
    }

    val safeVisibleFloat = visibleWhiteKeysFloat.coerceIn(3f, 48f)
    val maxScroll = (TOTAL_VIRTUAL_WHITE_KEYS.toFloat() - safeVisibleFloat).coerceAtLeast(0f)
    val effectiveScroll = scrollWhiteKey.coerceIn(0f, maxScroll)

    val keyLayout = remember(boxSize, effectiveScroll, safeVisibleFloat) {
        val w = boxSize.width.toFloat()
        val h = boxSize.height.toFloat()
        if (w <= 0f || h <= 0f || safeVisibleFloat <= 0f) {
            emptyList()
        } else {
            val whiteW = w / safeVisibleFloat
            val blackW = whiteW * 0.60f
            val blackH = h * 0.58f
            val firstW = floor(effectiveScroll).toInt().coerceAtLeast(0)
            val lastW = (firstW + ceil(safeVisibleFloat).toInt() + 1).coerceAtMost(TOTAL_VIRTUAL_WHITE_KEYS - 1)

            val whites = mutableListOf<PianoKeyBox>()
            val blacks = mutableListOf<PianoKeyBox>()

            for (wi in firstW..lastW) {
                val left = (wi - effectiveScroll) * whiteW
                val right = left + whiteW
                if (right > -1f && left < w + 1f) {
                    val midi = MusicalNotationHelper.whiteKeyIndexToMidi(wi)
                    whites += PianoKeyBox(
                        midiNote = midi,
                        isBlack = false,
                        rect = Rect(left, 0f, right, h)
                    )
                }

                if (MusicalNotationHelper.hasSharpAfterWhiteKey(wi)) {
                    val boundaryX = (wi + 1 - effectiveScroll) * whiteW
                    val offsetFactor = when (wi % 7) {
                        0 -> -0.05f // #C
                        1 ->  0.05f // #D
                        3 -> -0.06f // #F
                        4 ->  0.00f // #G
                        5 ->  0.06f // #A
                        else -> 0f
                    }
                    val cx = boundaryX + whiteW * offsetFactor
                    val bLeft = cx - blackW / 2f
                    val bRight = cx + blackW / 2f
                    if (bRight > -1f && bLeft < w + 1f) {
                        val sharpMidi = MusicalNotationHelper.whiteKeyIndexToMidi(wi) + 1
                        blacks += PianoKeyBox(
                            midiNote = sharpMidi,
                            isBlack = true,
                            rect = Rect(bLeft, 0f, bRight, blackH)
                        )
                    }
                }
            }
            whites + blacks
        }
    }

    val latestKeyLayout by rememberUpdatedState(keyLayout)

    val minInsetPxStd = with(density) { 1.2.dp.toPx() }
    val maxInsetPxStd = with(density) { 3.0.dp.toPx() }
    val minInsetPxStrict = with(density) { 1.8.dp.toPx() }
    val maxInsetPxStrict = with(density) { 4.4.dp.toPx() }
    val minBottomInsetStd = with(density) { 1.4.dp.toPx() }
    val maxBottomInsetStd = with(density) { 4.0.dp.toPx() }
    val minBottomInsetStrict = with(density) { 2.2.dp.toPx() }
    val maxBottomInsetStrict = with(density) { 6.2.dp.toPx() }
    val topForgivenessPx = with(density) { 10.dp.toPx() }
    val holdOutBoundsForgivenessPx = with(density) { 40.dp.toPx() }
    val minWhiteUnlockStdPx = with(density) { 13.dp.toPx() }
    val minWhiteUnlockStrictPx = with(density) { 18.dp.toPx() }
    val minBlackUnlockStdPx = with(density) { 20.dp.toPx() }
    val minBlackUnlockStrictPx = with(density) { 26.dp.toPx() }
    val fallbackWhiteWPx = with(density) { 38.dp.toPx() }

    fun velocityForKey(key: PianoKeyBox, y: Float): Float {
        val relY = ((y - key.rect.top) / key.rect.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return if (key.isBlack) {
            (0.68f + relY * 0.30f).coerceIn(0.55f, 0.98f)
        } else {
            (0.64f + relY * 0.34f).coerceIn(0.50f, 1.0f)
        }
    }

    fun matchesBlackKeyTouchBox(key: PianoKeyBox, pos: Offset, mode: TouchGuardMode): Boolean {
        val r = key.rect
        if (mode == TouchGuardMode.OFF) {
            return r.contains(pos)
        }
        val sideInset = if (mode == TouchGuardMode.STRICT) {
            (r.width * 0.105f).coerceIn(minInsetPxStrict, maxInsetPxStrict)
        } else {
            (r.width * 0.065f).coerceIn(minInsetPxStd, maxInsetPxStd)
        }
        val bottomInset = if (mode == TouchGuardMode.STRICT) {
            (r.height * 0.070f).coerceIn(minBottomInsetStrict, maxBottomInsetStrict)
        } else {
            (r.height * 0.042f).coerceIn(minBottomInsetStd, maxBottomInsetStd)
        }
        val inLeft = r.left + sideInset
        val inRight = (r.right - sideInset).coerceAtLeast(inLeft + 2f)
        val inTop = r.top - topForgivenessPx
        val inBottom = (r.bottom - bottomInset).coerceAtLeast(r.top + 4f)
        if (pos.x < inLeft || pos.x > inRight || pos.y < inTop || pos.y > inBottom) {
            return false
        }
        // Rounded bottom-tip chamfer prevents fast white-key hits from snagging black-key corners
        val cornerFrac = if (mode == TouchGuardMode.STRICT) 0.30f else 0.22f
        val cornerR = ((inRight - inLeft) * cornerFrac).coerceAtLeast(2f)
        if (pos.y > inBottom - cornerR) {
            if (pos.x < inLeft + cornerR) {
                val dx = pos.x - (inLeft + cornerR)
                val dy = pos.y - (inBottom - cornerR)
                if (dx * dx + dy * dy > cornerR * cornerR) return false
            } else if (pos.x > inRight - cornerR) {
                val dx = pos.x - (inRight - cornerR)
                val dy = pos.y - (inBottom - cornerR)
                if (dx * dx + dy * dy > cornerR * cornerR) return false
            }
        }
        return true
    }

    fun hitTestInitialKey(pos: Offset, mode: TouchGuardMode): Pair<PianoKeyBox, Float>? {
        val keys = latestKeyLayout
        if (keys.isEmpty()) return null
        if (pos.y < -topForgivenessPx || pos.y > boxSize.height + topForgivenessPx * 1.2f) return null

        val hitBlack = keys.firstOrNull { it.isBlack && matchesBlackKeyTouchBox(it, pos, mode) }
        if (hitBlack != null) {
            return hitBlack to velocityForKey(hitBlack, pos.y)
        }

        val clampedX = pos.x.coerceIn(0.5f, (boxSize.width - 0.5f).coerceAtLeast(0.5f))
        val hitWhite = keys.firstOrNull { !it.isBlack && clampedX >= it.rect.left && clampedX <= it.rect.right }
        if (hitWhite != null) {
            return hitWhite to velocityForKey(hitWhite, pos.y)
        }
        return null
    }

    fun hitTestGlissandoCandidate(
        pos: Offset,
        prev: PointerTouchState,
        config: PianoTouchGuardConfig
    ): Pair<PianoKeyBox, Float>? {
        val keys = latestKeyLayout
        if (keys.isEmpty()) return null
        if (pos.y < -holdOutBoundsForgivenessPx || pos.y > boxSize.height + holdOutBoundsForgivenessPx) {
            return null
        }

        if (!config.glissandoSamePlaneLock) {
            return hitTestInitialKey(
                pos = Offset(pos.x, pos.y.coerceIn(0f, boxSize.height.toFloat().coerceAtLeast(1f))),
                mode = config.mode
            )
        }

        val clampedX = pos.x.coerceIn(0.5f, (boxSize.width - 0.5f).coerceAtLeast(0.5f))
        val blackH = (boxSize.height * 0.58f).coerceAtLeast(1f)

        return if (!prev.initialIsBlack) {
            // Started on a white key: glide cleanly across white keys without snagging black-key tips!
            val whiteKey = keys.firstOrNull {
                !it.isBlack && clampedX >= it.rect.left && clampedX <= it.rect.right
            }
            whiteKey?.let { it to velocityForKey(it, pos.y) }
        } else {
            // Started on a black key: pentatonic black-key glissando along upper zone; white keys only if pulled far down
            if (pos.y <= blackH * 1.15f) {
                val blackKey = keys.firstOrNull {
                    it.isBlack && clampedX >= it.rect.left && clampedX <= it.rect.right
                }
                blackKey?.let { it to velocityForKey(it, pos.y) }
            } else {
                val whiteKey = keys.firstOrNull {
                    !it.isBlack && clampedX >= it.rect.left && clampedX <= it.rect.right
                }
                whiteKey?.let { it to velocityForKey(it, pos.y) }
            }
        }
    }

    fun isInsideHorizontalCore(x: Float, rect: Rect, insetFraction: Float): Boolean {
        val margin = (rect.width * insetFraction).coerceAtMost(rect.width * 0.42f)
        return x >= (rect.left + margin) && x <= (rect.right - margin)
    }

    val pressedMidiSet = activePointers.values.map { it.currentMidi }.toSet()

    Canvas(
        modifier = modifier
            .background(Color(0xFFDDD6CA))
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        val nowMs = SystemClock.uptimeMillis()
                        val config = latestGuard
                        val sampleWhiteW = latestKeyLayout.firstOrNull { !it.isBlack }?.rect?.width
                            ?: fallbackWhiteWPx

                        for (change in event.changes) {
                            val pid = change.id
                            val pos = change.position
                            if (change.pressed) {
                                val prev = activePointers[pid]
                                if (prev == null) {
                                    val hit = hitTestInitialKey(pos, config.mode)
                                    if (hit != null) {
                                        val (keyBox, vel) = hit
                                        activePointers[pid] = PointerTouchState(
                                            downPos = pos,
                                            downTimeMs = nowMs,
                                            initialMidi = keyBox.midiNote,
                                            initialIsBlack = keyBox.isBlack,
                                            currentMidi = keyBox.midiNote,
                                            currentIsBlack = keyBox.isBlack
                                        )
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        latestOnDown(keyBox.midiNote, vel, false)
                                    }
                                } else if (!config.isEnabled) {
                                    // Raw legacy zero-deadzone mode (`防误触:关`)
                                    val hit = hitTestInitialKey(pos, TouchGuardMode.OFF)
                                    if (hit != null) {
                                        val (keyBox, vel) = hit
                                        if (keyBox.midiNote != prev.currentMidi) {
                                            val oldMidi = prev.currentMidi
                                            activePointers[pid] = prev.copy(
                                                currentMidi = keyBox.midiNote,
                                                currentIsBlack = keyBox.isBlack,
                                                isGlissandoUnlocked = true,
                                                lastSwitchPos = pos,
                                                lastSwitchTimeMs = nowMs,
                                                previousMidiBeforeSwitch = oldMidi
                                            )
                                            if (activePointers.values.none { it.currentMidi == oldMidi }) {
                                                latestOnUp(oldMidi)
                                            }
                                            latestOnDown(
                                                keyBox.midiNote,
                                                (vel * 0.92f).coerceIn(0.45f, 0.96f),
                                                true
                                            )
                                        }
                                    } else {
                                        val oldMidi = prev.currentMidi
                                        activePointers.remove(pid)
                                        if (activePointers.values.none { it.currentMidi == oldMidi }) {
                                            latestOnUp(oldMidi)
                                        }
                                    }
                                } else {
                                    // Glissando-Preserving Anti-Mistouch Engine (`标准` / `强力`)
                                    val farOutOfBounds = pos.y < -holdOutBoundsForgivenessPx * 1.5f ||
                                        pos.y > boxSize.height + holdOutBoundsForgivenessPx * 1.5f
                                    if (farOutOfBounds) {
                                        val oldMidi = prev.currentMidi
                                        activePointers.remove(pid)
                                        if (activePointers.values.none { it.currentMidi == oldMidi }) {
                                            latestOnUp(oldMidi)
                                        }
                                    } else if (!prev.isGlissandoUnlocked) {
                                        val absDx = abs(pos.x - prev.downPos.x)
                                        val absDy = abs(pos.y - prev.downPos.y)
                                        val elapsedMs = (nowMs - prev.downTimeMs).coerceAtLeast(0L)

                                        val unlockDistPx = if (prev.initialIsBlack) {
                                            if (config.mode == TouchGuardMode.STRICT) {
                                                max(sampleWhiteW * 0.78f, minBlackUnlockStrictPx)
                                            } else {
                                                max(sampleWhiteW * 0.62f, minBlackUnlockStdPx)
                                            }
                                        } else {
                                            if (config.mode == TouchGuardMode.STRICT) {
                                                max(sampleWhiteW * 0.54f, minWhiteUnlockStrictPx)
                                            } else {
                                                max(sampleWhiteW * 0.40f, minWhiteUnlockStdPx)
                                            }
                                        }
                                        val minHorizRatio = if (prev.initialIsBlack) 1.15f else 0.80f
                                        val stabilizeMs = if (config.mode == TouchGuardMode.STRICT) 52L else 34L

                                        val passedStabilize = elapsedMs >= stabilizeMs ||
                                            absDx >= unlockDistPx * 1.28f
                                        val passedSwipeVector = absDx >= unlockDistPx &&
                                            absDx >= absDy * minHorizRatio

                                        if (passedStabilize && passedSwipeVector) {
                                            val candidate = hitTestGlissandoCandidate(pos, prev, config)
                                            if (candidate != null && candidate.first.midiNote != prev.currentMidi) {
                                                val (targetBox, vel) = candidate
                                                val coreInset = if (config.mode == TouchGuardMode.STRICT) 0.18f else 0.13f
                                                val clampedX = pos.x.coerceIn(
                                                    0.5f,
                                                    (boxSize.width - 0.5f).coerceAtLeast(0.5f)
                                                )
                                                if (isInsideHorizontalCore(clampedX, targetBox.rect, coreInset)) {
                                                    val oldMidi = prev.currentMidi
                                                    activePointers[pid] = prev.copy(
                                                        currentMidi = targetBox.midiNote,
                                                        currentIsBlack = targetBox.isBlack,
                                                        isGlissandoUnlocked = true,
                                                        lastSwitchPos = pos,
                                                        lastSwitchTimeMs = nowMs,
                                                        previousMidiBeforeSwitch = oldMidi
                                                    )
                                                    if (activePointers.values.none { it.currentMidi == oldMidi }) {
                                                        latestOnUp(oldMidi)
                                                    }
                                                    latestOnDown(
                                                        targetBox.midiNote,
                                                        (vel * 0.92f).coerceIn(0.45f, 0.96f),
                                                        true
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        // Active Glissando (`刮奏已解锁`): fluid multi-octave sweep with anti-bounce hysteresis
                                        val candidate = hitTestGlissandoCandidate(pos, prev, config)
                                        if (candidate != null && candidate.first.midiNote != prev.currentMidi) {
                                            val (targetBox, vel) = candidate
                                            val clampedX = pos.x.coerceIn(
                                                0.5f,
                                                (boxSize.width - 0.5f).coerceAtLeast(0.5f)
                                            )
                                            val isBouncingBack = targetBox.midiNote == prev.previousMidiBeforeSwitch
                                            val allowSwitch = if (isBouncingBack) {
                                                isInsideHorizontalCore(clampedX, targetBox.rect, 0.22f) &&
                                                    (nowMs - prev.lastSwitchTimeMs) >= 22L &&
                                                    abs(pos.x - prev.lastSwitchPos.x) >= sampleWhiteW * 0.22f
                                            } else {
                                                val forwardInset = if (config.mode == TouchGuardMode.STRICT) 0.14f else 0.10f
                                                isInsideHorizontalCore(clampedX, targetBox.rect, forwardInset)
                                            }
                                            if (allowSwitch) {
                                                val oldMidi = prev.currentMidi
                                                activePointers[pid] = prev.copy(
                                                    currentMidi = targetBox.midiNote,
                                                    currentIsBlack = targetBox.isBlack,
                                                    lastSwitchPos = pos,
                                                    lastSwitchTimeMs = nowMs,
                                                    previousMidiBeforeSwitch = oldMidi
                                                )
                                                if (activePointers.values.none { it.currentMidi == oldMidi }) {
                                                    latestOnUp(oldMidi)
                                                }
                                                latestOnDown(
                                                    targetBox.midiNote,
                                                    (vel * 0.92f).coerceIn(0.45f, 0.96f),
                                                    true
                                                )
                                            }
                                        }
                                    }
                                }
                                change.consume()
                            } else {
                                val prev = activePointers.remove(pid)
                                if (prev != null && activePointers.values.none { it.currentMidi == prev.currentMidi }) {
                                    latestOnUp(prev.currentMidi)
                                }
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (activePointers.isNotEmpty()) {
                        val remaining = activePointers.values.map { it.currentMidi }.toSet()
                        activePointers.clear()
                        remaining.forEach { latestOnUp(it) }
                    }
                }
            }
    ) {
        if (keyLayout.isEmpty()) return@Canvas

        val sampleWhiteW = keyLayout.firstOrNull { !it.isBlack }?.rect?.width ?: 40.dp.toPx()
        val whiteFontSp = when {
            sampleWhiteW < 28.dp.toPx() -> 9.sp
            sampleWhiteW < 34.dp.toPx() -> 10.sp
            else -> 11.5.sp
        }
        val blackFontSp = when {
            sampleWhiteW < 30.dp.toPx() -> 7.5.sp
            sampleWhiteW < 36.dp.toPx() -> 8.5.sp
            else -> 9.5.sp
        }

        val whitePitchPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = whiteFontSp.toPx()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val blackPitchPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = blackFontSp.toPx()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        // 1. Draw White Keys (Pitch + Octave ONLY e.g. "C4", "D4")
        keyLayout.filter { !it.isBlack }.forEach { key ->
            val pressed = key.midiNote in pressedMidiSet
            val isRootC = MusicalNotationHelper.isRootC(key.midiNote)

            val fill = when {
                pressed -> accentColor.copy(alpha = 0.30f)
                isRootC -> Color(0xFFFFFBF4)
                else -> Color(0xFFFDFAF4)
            }
            val r = key.rect
            val pad = 1.dp.toPx()

            drawRoundRect(
                color = fill,
                topLeft = Offset(r.left + pad, r.top),
                size = Size((r.width - pad * 2f).coerceAtLeast(2f), (r.height - 2.dp.toPx()).coerceAtLeast(4f)),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
            drawRoundRect(
                color = if (pressed) accentColor else Color(0xFFC9C1B4),
                topLeft = Offset(r.left + pad, r.top),
                size = Size((r.width - pad * 2f).coerceAtLeast(2f), (r.height - 2.dp.toPx()).coerceAtLeast(4f)),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = if (pressed) 1.8.dp.toPx() else 1.dp.toPx())
            )

            val cx = r.center.x
            if (isRootC) {
                drawCircle(
                    color = accentColor.copy(alpha = if (pressed) 0.95f else 0.55f),
                    radius = 2.6.dp.toPx(),
                    center = Offset(cx, r.bottom - 24.dp.toPx())
                )
            }

            whitePitchPaint.color = when {
                pressed -> android.graphics.Color.argb(255, 35, 31, 28)
                isRootC -> android.graphics.Color.argb(245, 180, 82, 48)
                else -> android.graphics.Color.argb(215, 92, 85, 76)
            }
            drawContext.canvas.nativeCanvas.drawText(
                MusicalNotationHelper.midiToWhiteKeyLabel(key.midiNote),
                cx,
                r.bottom - 9.dp.toPx(),
                whitePitchPaint
            )
        }

        // 2. Draw Black Keys ("#C/#D/#F/#G/#A" in sharp/neutral keys, "bD/bE/bG/bA/bB" in flat keys)
        keyLayout.filter { it.isBlack }.forEach { key ->
            val pressed = key.midiNote in pressedMidiSet
            val r = key.rect

            drawRoundRect(
                color = Color(0x28000000),
                topLeft = Offset(r.left + 1.dp.toPx(), r.top),
                size = Size(r.width, r.height + 2.dp.toPx()),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            )
            drawRoundRect(
                color = if (pressed) accentColor else Color(0xFF282522),
                topLeft = Offset(r.left, r.top),
                size = Size(r.width, r.height),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            )
            if (!pressed) {
                drawRoundRect(
                    color = Color(0xFF3E3A36),
                    topLeft = Offset(r.left + 2.dp.toPx(), r.top + 1.dp.toPx()),
                    size = Size((r.width - 4.dp.toPx()).coerceAtLeast(2f), (r.height - 6.dp.toPx()).coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }

            if (r.width >= 13.dp.toPx()) {
                blackPitchPaint.color = if (pressed) {
                    android.graphics.Color.WHITE
                } else {
                    android.graphics.Color.argb(225, 232, 226, 216)
                }
                drawContext.canvas.nativeCanvas.drawText(
                    MusicalNotationHelper.midiToBlackKeyLabel(key.midiNote, preferFlats),
                    r.center.x,
                    r.bottom - 8.dp.toPx(),
                    blackPitchPaint
                )
            }
        }
    }
}

// ============================================================================
// Shared Compact Controls (Key Signature Pill & Fast-Scrub / Tap BPM Control)
// ============================================================================

@Composable
private fun KeySignatureSelectorPill(
    activeKeySignature: KeySignature,
    modulationCount: Int,
    onClick: () -> Unit
) {
    val nonDefault = activeKeySignature != KeySignature.C_MAJOR || modulationCount > 0
    val shape = RoundedCornerShape(7.dp)
    val labelText = if (modulationCount > 0) {
        "${activeKeySignature.shortPillLabel}·转$modulationCount"
    } else {
        activeKeySignature.shortPillLabel
    }

    Surface(
        shape = shape,
        color = if (nonDefault) VioletAccent.copy(alpha = 0.14f) else StudioSurfaceElevated,
        border = BorderStroke(1.dp, if (nonDefault) VioletAccent else StudioBorder),
        modifier = Modifier
            .height(27.dp)
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 4.dp)
        ) {
            Text(
                text = labelText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                color = if (nonDefault) VioletAccent else TextPrimary,
                maxLines = 1
            )
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = "设置调号或中途变调",
                tint = if (nonDefault) VioletAccent else TextSecondary,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun BpmCompactControl(
    bpm: Int,
    referenceTrackBpm: Int?,
    onBpmChange: (Int) -> Unit,
    onOpenQuickDialog: () -> Unit
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val stepPx = with(density) { 5.2.dp.toPx().coerceAtLeast(4f) }

    var isScrubbing by remember { mutableStateOf(false) }
    var dragAccumPx by remember { mutableFloatStateOf(0f) }
    val latestBpm by rememberUpdatedState(bpm)
    val latestOnBpmChange by rememberUpdatedState(onBpmChange)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        MelodySmallStepBtn("−") { onBpmChange(bpm - 1) }

        val pillShape = RoundedCornerShape(7.dp)
        Surface(
            shape = pillShape,
            color = if (isScrubbing) CyanPrimary.copy(alpha = 0.16f) else StudioSurfaceElevated,
            border = BorderStroke(1.dp, if (isScrubbing) CyanPrimary else StudioBorder),
            modifier = Modifier
                .height(27.dp)
                .clip(pillShape)
                .clickable(onClick = onOpenQuickDialog)
                .pointerInput(stepPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            isScrubbing = true
                            dragAccumPx = 0f
                        },
                        onDragEnd = {
                            isScrubbing = false
                            dragAccumPx = 0f
                        },
                        onDragCancel = {
                            isScrubbing = false
                            dragAccumPx = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumPx += dragAmount
                            val deltaBpm = (dragAccumPx / stepPx).toInt()
                            if (deltaBpm != 0) {
                                dragAccumPx -= deltaBpm * stepPx
                                val next = (latestBpm + deltaBpm).coerceIn(40, 220)
                                if (next != latestBpm) {
                                    if (next % 5 == 0) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    latestOnBpmChange(next)
                                }
                            }
                        }
                    )
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 7.dp)
            ) {
                Text(
                    text = "${bpm} BPM",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isScrubbing) CyanPrimary else TextPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.Rounded.DriveFileRenameOutline,
                    contentDescription = "点击快速修改 BPM 或左右滑动调速",
                    tint = if (isScrubbing) CyanPrimary else TextMuted,
                    modifier = Modifier.size(11.dp)
                )
            }
        }

        MelodySmallStepBtn("+") { onBpmChange(bpm + 1) }

        if (referenceTrackBpm != null && referenceTrackBpm != bpm) {
            val syncShape = RoundedCornerShape(7.dp)
            Surface(
                shape = syncShape,
                color = CyanPrimary.copy(alpha = 0.13f),
                border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.4f)),
                modifier = Modifier
                    .height(27.dp)
                    .clip(syncShape)
                    .clickable { onBpmChange(referenceTrackBpm) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = "同原曲($referenceTrackBpm)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CyanPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportAndModeLeftCluster(
    melody: MelodyStudioUiState,
    compactModeLabels: Boolean = false,
    onTogglePlayStop: () -> Unit,
    onSelectInputMode: (MelodyInputMode) -> Unit,
    onToggleRecordStop: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        val playShape = RoundedCornerShape(8.dp)
        val playing = melody.isPlaying && !melody.isRecording
        Surface(
            shape = playShape,
            color = if (playing) WarmUmber else VioletAccent,
            modifier = Modifier
                .height(28.dp)
                .clip(playShape)
                .clickable(onClick = onTogglePlayStop)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = if (compactModeLabels) 8.dp else 9.dp)
            ) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = OnAccentLight,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = if (playing) "停止" else "试听",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OnAccentLight,
                    maxLines = 1
                )
            }
        }

        MelodyInputMode.entries.forEach { mode ->
            val selected = melody.inputMode == mode
            val mShape = RoundedCornerShape(8.dp)
            val modeText = if (compactModeLabels) {
                when (mode) {
                    MelodyInputMode.STEP_INPUT -> "步进"
                    MelodyInputMode.LIVE_RECORD -> "弹录"
                }
            } else {
                mode.label
            }
            Surface(
                shape = mShape,
                color = if (selected) CyanPrimary.copy(alpha = 0.15f) else StudioSurfaceElevated,
                border = BorderStroke(1.dp, if (selected) CyanPrimary else StudioBorder),
                modifier = Modifier
                    .height(28.dp)
                    .clip(mShape)
                    .clickable { onSelectInputMode(mode) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = if (compactModeLabels) 7.dp else 8.dp)
                ) {
                    Text(
                        text = modeText,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) CyanPrimary else TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }

        if (melody.inputMode == MelodyInputMode.LIVE_RECORD) {
            val recShape = RoundedCornerShape(8.dp)
            val recLabel = when {
                melody.isRecording && compactModeLabels -> "结束"
                melody.isRecording -> "结束弹录"
                compactModeLabels -> "开始"
                else -> "开始弹录"
            }
            Surface(
                shape = recShape,
                color = if (melody.isRecording) DangerRed else DangerRed.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, DangerRed),
                modifier = Modifier
                    .height(28.dp)
                    .clip(recShape)
                    .clickable(onClick = onToggleRecordStop)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = if (compactModeLabels) 7.dp else 8.dp)
                ) {
                    Icon(
                        imageVector = if (melody.isRecording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                        contentDescription = null,
                        tint = if (melody.isRecording) OnAccentLight else DangerRed,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = recLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (melody.isRecording) OnAccentLight else DangerRed,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun MelodyHeaderIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape = shape,
        color = StudioSurfaceElevated.copy(alpha = if (enabled) 0.9f else 0.4f),
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier
            .size(29.dp)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) TextPrimary else TextMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun MelodySmallStepBtn(
    symbol: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(7.dp)
    Surface(
        shape = shape,
        color = StudioSurfaceElevated,
        border = BorderStroke(1.dp, StudioBorder),
        modifier = Modifier
            .size(27.dp)
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun MelodyTinyToggle(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(7.dp)
    Surface(
        shape = shape,
        color = if (active) activeColor.copy(alpha = 0.15f) else StudioSurfaceElevated,
        border = BorderStroke(1.dp, if (active) activeColor else StudioBorder),
        modifier = Modifier
            .height(27.dp)
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) activeColor else TextSecondary,
                maxLines = 1
            )
        }
    }
}

// ============================================================================
// Musical Expression Studio Dialog (`力度 · 奏法 · 速度`)
// Supports inserting, selecting, modifying, and deleting Articulations, Slurs,
// Fermatas, Accents, Dynamics, Hairpins, & Tempo Map events with Undo/Redo!
// ============================================================================

@Composable
private fun FermataVectorBadge(
    tint: Color,
    modifier: Modifier = Modifier
) {
    val argb = tint.toArgb()
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val nc = drawContext.canvas.nativeCanvas
        val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = max(1.6f, w * 0.11f)
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            style = android.graphics.Paint.Style.FILL
        }
        val arcRect = android.graphics.RectF(w * 0.10f, h * 0.22f, w * 0.90f, h * 0.96f)
        nc.drawArc(arcRect, 192f, 156f, false, strokePaint)
        nc.drawCircle(w * 0.50f, h * 0.64f, max(1.8f, w * 0.11f), fillPaint)
    }
}

private fun formatScorePositionLabel(tick: Int, melody: MelodyStudioUiState): String {
    val bar = melody.barNumberAtTick(tick)
    val beat = melody.beatNumberAtTick(tick)
    return "第${bar}小节·第${beat}拍"
}

@Composable
private fun ScoreExpressionStudioDialog(
    melody: MelodyStudioUiState,
    initialTab: Int,
    onTapCursorTick: (Int) -> Unit,
    onSelectAnnotation: (SelectedScoreAnnotation?) -> Unit,
    onTogglePhraseSlurMode: () -> Unit,
    onSlurSelectedOrExtendNext: () -> Unit,
    onApplySlurSpan: (Int, Int) -> Unit,
    onClearSelectedSlur: () -> Unit,
    onToggleArticulation: (NoteArticulation?) -> Unit,
    onToggleAccent: (NoteAccent?) -> Unit,
    onToggleFermata: () -> Unit,
    onUpsertDynamicMark: (Int, DynamicLevel, String?) -> Unit,
    onDeleteDynamicMark: (String) -> Unit,
    onUpsertHairpin: (Int, Int, HairpinType, String?) -> Unit,
    onDeleteHairpin: (String) -> Unit,
    onUpsertTempoEvent: (ScoreTempoEvent) -> Unit,
    onDeleteTempoEvent: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    val unitTicks = melody.timeSignature.unitTicks.coerceAtLeast(TICKS_PER_STEP)
    val beatsPerBar = melody.timeSignature.safeNumerator.coerceAtLeast(1)
    val ticksPerBar = melody.ticksPerBar.coerceAtLeast(unitTicks)

    val editingDynamic = (melody.selectedAnnotation as? SelectedScoreAnnotation.Dynamic)?.let { ann ->
        melody.dynamicMarks.firstOrNull { it.id == ann.id }
    }
    val editingHairpin = (melody.selectedAnnotation as? SelectedScoreAnnotation.Hairpin)?.let { ann ->
        melody.hairpins.firstOrNull { it.id == ann.id }
    }
    val editingTempo = (melody.selectedAnnotation as? SelectedScoreAnnotation.Tempo)?.let { ann ->
        melody.tempoEvents.firstOrNull { it.id == ann.id }
    }

    val anchorTick = when {
        selectedTab == 1 && editingDynamic != null -> editingDynamic.tick
        selectedTab == 1 && editingHairpin != null -> editingHairpin.safeStartTick
        selectedTab == 2 && editingTempo != null -> editingTempo.safeStartTick
        melody.selectedNote != null -> melody.selectedNote!!.resolvedStartTick
        else -> melody.cursorTick
    }.coerceAtLeast(0)

    var hairpinSpanBeats by remember(editingHairpin?.id, beatsPerBar) {
        val initial = editingHairpin?.let {
            ((it.safeEndTick - it.safeStartTick).toFloat() / unitTicks.toFloat()).roundToInt().coerceIn(1, 32)
        } ?: beatsPerBar
        mutableIntStateOf(initial)
    }

    var customBpm by remember(editingTempo?.id, melody.bpm) {
        val initial = if (editingTempo?.kind == TempoEventKind.STEADY_BPM) {
            editingTempo.bpm.coerceIn(36, 240)
        } else {
            melody.bpm.coerceIn(36, 240)
        }
        mutableIntStateOf(initial)
    }
    var customBpmText by remember(editingTempo?.id, melody.bpm) {
        val initial = if (editingTempo?.kind == TempoEventKind.STEADY_BPM) {
            editingTempo.bpm.coerceIn(36, 240)
        } else {
            melody.bpm.coerceIn(36, 240)
        }
        mutableStateOf(initial.toString())
    }
    var selectedTempoTerm by remember(editingTempo?.id) {
        mutableStateOf(editingTempo?.term)
    }
    var rampSpanBeats by remember(editingTempo?.id, beatsPerBar) {
        val initial = if (editingTempo != null &&
            (editingTempo.kind == TempoEventKind.RITARDANDO || editingTempo.kind == TempoEventKind.ACCELERANDO)
        ) {
            ((editingTempo.safeEndTick - editingTempo.safeStartTick).toFloat() / unitTicks.toFloat())
                .roundToInt()
                .coerceIn(1, 32)
        } else {
            beatsPerBar
        }
        mutableIntStateOf(initial)
    }
    var useCustomRampTarget by remember(editingTempo?.id) {
        mutableStateOf(editingTempo?.targetBpm != null)
    }
    var customRampTargetBpm by remember(editingTempo?.id, melody.bpm) {
        mutableIntStateOf((editingTempo?.targetBpm ?: (melody.bpm * 0.72f).roundToInt()).coerceIn(36, 240))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "力度 · 奏法 · 速度记号",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = formatScorePositionLabel(anchorTick, melody),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary
                    )
                }

                // Quick Position Stepper Bar so users can step across beats/bars without closing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val navItems = listOf(
                        "小节首" to ((anchorTick / ticksPerBar) * ticksPerBar),
                        "◀ 上一拍" to (anchorTick - unitTicks).coerceAtLeast(0),
                        "下一拍 ▶" to (anchorTick + unitTicks).coerceAtMost((melody.totalTicks - TICKS_PER_STEP).coerceAtLeast(0))
                    )
                    navItems.forEach { (lbl, dstTick) ->
                        val chipShape = RoundedCornerShape(7.dp)
                        Surface(
                            shape = chipShape,
                            color = StudioSurfaceElevated,
                            border = BorderStroke(1.dp, StudioBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(chipShape)
                                .clickable {
                                    onSelectAnnotation(null)
                                    onTapCursorTick(dstTick)
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = lbl,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                // 3 Compact Studio Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val tabs = listOf(
                        0 to "奏法·连线",
                        1 to "力度·渐变",
                        2 to "速度·变速"
                    )
                    tabs.forEach { (idx, title) ->
                        val active = selectedTab == idx
                        val tabShape = RoundedCornerShape(8.dp)
                        Surface(
                            shape = tabShape,
                            color = if (active) VioletAccent else StudioSurfaceElevated,
                            border = BorderStroke(1.dp, if (active) VioletAccent else StudioBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(31.dp)
                                .clip(tabShape)
                                .clickable { selectedTab = idx }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (active) OnAccentLight else TextPrimary
                                )
                            }
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        val selNote = melody.selectedNote
                        val currentArt = if (selNote != null && !selNote.isRest) selNote.articulation else melody.pendingArticulation
                        val currentAcc = if (selNote != null && !selNote.isRest) selNote.accent else melody.pendingAccent
                        val currentFermata = selNote?.hasFermata ?: melody.pendingFermata

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = VioletAccent.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, VioletAccent.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = when {
                                    selNote != null && !selNote.isRest ->
                                        "当前已选中音符 (${MusicalNotationHelper.midiToNoteName(selNote.midiNote)})：点击下方记号直接添加/取消，谱面与试听实时同步"
                                    selNote != null && selNote.isRest ->
                                        "当前已选中休止符：支持添加/取消「延长记号 (Fermata)」"
                                    else ->
                                        "当前未选中音符：点选奏法/重音后将自动加在下一个弹入的音符上"
                                },
                                fontSize = 11.sp,
                                color = TextPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }

                        // 1. Articulations (`.` `▼` `−` `−.`)
                        Text(
                            text = "奏法与延长记号（谱面保持标准记谱，试听与导出 WAV 真实演绎）：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        NoteArticulation.entries.chunked(2).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowItems.forEach { art ->
                                    val active = currentArt == art
                                    val cShape = RoundedCornerShape(8.dp)
                                    Surface(
                                        shape = cShape,
                                        color = if (active) VioletAccent.copy(alpha = 0.18f) else StudioSurfaceElevated,
                                        border = BorderStroke(1.2.dp, if (active) VioletAccent else StudioBorder),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(cShape)
                                            .clickable { onToggleArticulation(art) }
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Text(
                                                    text = art.symbolBadge,
                                                    fontFamily = FontFamily.Serif,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 15.sp,
                                                    color = if (active) VioletAccent else CyanPrimary
                                                )
                                                Text(
                                                    text = art.label.substringBefore(" "),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (active) VioletAccent else TextPrimary
                                                )
                                            }
                                            Text(
                                                text = art.subtitle,
                                                fontSize = 10.sp,
                                                color = TextMuted,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Fermata (`𝄐`) with Vector Glyph
                        val fermataShape = RoundedCornerShape(8.dp)
                        Surface(
                            shape = fermataShape,
                            color = if (currentFermata) AmberMastering.copy(alpha = 0.18f) else StudioSurfaceElevated,
                            border = BorderStroke(1.2.dp, if (currentFermata) AmberMastering else StudioBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(fermataShape)
                                .clickable(onClick = onToggleFermata)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FermataVectorBadge(
                                    tint = if (currentFermata) AmberMastering else TextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "延长记号 (Fermata)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (currentFermata) AmberMastering else TextPrimary
                                    )
                                    Text(
                                        text = "音符与休止符通用 · 试听与导出音频自动自然延展本拍约 1.8 倍时值",
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        // 2. Single-Note Accents (`>` `^`)
                        Text(
                            text = "单音 / 单拍突强重音：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            NoteAccent.entries.forEach { acc ->
                                val active = currentAcc == acc
                                val aShape = RoundedCornerShape(8.dp)
                                Surface(
                                    shape = aShape,
                                    color = if (active) CyanPrimary.copy(alpha = 0.18f) else StudioSurfaceElevated,
                                    border = BorderStroke(1.2.dp, if (active) CyanPrimary else StudioBorder),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(aShape)
                                        .clickable { onToggleAccent(acc) }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = "${acc.symbolBadge}  ${acc.label.substringBefore(" ")}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (active) CyanPrimary else TextPrimary
                                        )
                                        Text(
                                            text = acc.subtitle,
                                            fontSize = 10.sp,
                                            color = TextMuted
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = StudioBorder)

                        // 3. Phrase Slur (`乐句连线 / 圆滑线 Legato`)
                        Text(
                            text = "乐句连线 / 圆滑线（Slur 异音连奏 Legato）：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        val liveSlur = melody.activeSlurGroupId != null
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MelodyTinyToggle(
                                label = if (liveSlur) "连续乐句连线中⌢ (点此结束)" else "开启连续乐句连线模式⌢",
                                active = liveSlur,
                                activeColor = EmeraldLossless,
                                onClick = onTogglePhraseSlurMode
                            )
                            MelodyTinyToggle(
                                label = "+连下一音",
                                active = false,
                                activeColor = EmeraldLossless,
                                onClick = onSlurSelectedOrExtendNext
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val barStart = (anchorTick / ticksPerBar) * ticksPerBar
                            MelodyTinyToggle(
                                label = "连当前起2拍",
                                active = false,
                                activeColor = EmeraldLossless,
                                onClick = { onApplySlurSpan(anchorTick, anchorTick + unitTicks * 2) }
                            )
                            MelodyTinyToggle(
                                label = "连整小节",
                                active = false,
                                activeColor = EmeraldLossless,
                                onClick = { onApplySlurSpan(barStart, barStart + ticksPerBar) }
                            )
                            MelodyTinyToggle(
                                label = "连2小节乐句",
                                active = false,
                                activeColor = EmeraldLossless,
                                onClick = { onApplySlurSpan(barStart, barStart + ticksPerBar * 2) }
                            )
                            MelodyTinyToggle(
                                label = "取消当前连线",
                                active = false,
                                activeColor = DangerRed,
                                onClick = onClearSelectedSlur
                            )
                        }
                    }

                    1 -> {
                        val existingAtAnchor = melody.dynamicMarks.firstOrNull {
                            kotlin.math.abs(it.tick - anchorTick) <= 10
                        }
                        val sectionalDynamics = listOf(
                            DynamicLevel.PP,
                            DynamicLevel.P,
                            DynamicLevel.MP,
                            DynamicLevel.MF,
                            DynamicLevel.F,
                            DynamicLevel.FF
                        )

                        Text(
                            text = "段落力度（从 ${formatScorePositionLabel(anchorTick, melody)} 起持续生效，再点同级取消）：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            sectionalDynamics.forEach { lv ->
                                val active = existingAtAnchor?.level == lv
                                val dShape = RoundedCornerShape(8.dp)
                                Surface(
                                    shape = dShape,
                                    color = if (active) CyanPrimary else StudioSurfaceElevated,
                                    border = BorderStroke(1.2.dp, if (active) CyanPrimary else StudioBorder),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(dShape)
                                        .clickable {
                                            if (active) {
                                                onDeleteDynamicMark(existingAtAnchor.id)
                                            } else {
                                                onUpsertDynamicMark(anchorTick, lv, existingAtAnchor?.id)
                                            }
                                        }
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = lv.marking,
                                            fontFamily = FontFamily.Serif,
                                            fontStyle = FontStyle.Italic,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            color = if (active) OnAccentLight else TextPrimary
                                        )
                                        Text(
                                            text = lv.cnName.substringBefore(" "),
                                            fontSize = 9.sp,
                                            color = if (active) OnAccentLight.copy(alpha = 0.9f) else TextMuted
                                        )
                                    }
                                }
                            }
                        }

                        // Sudden Single-Beat Dynamics (`sfz`, `fp`)
                        Text(
                            text = "单拍突强记号（sfz / fp）：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(DynamicLevel.SFZ, DynamicLevel.FP).forEach { lv ->
                                val active = existingAtAnchor?.level == lv
                                val sShape = RoundedCornerShape(8.dp)
                                Surface(
                                    shape = sShape,
                                    color = if (active) WarmUmber.copy(alpha = 0.20f) else StudioSurfaceElevated,
                                    border = BorderStroke(1.2.dp, if (active) WarmUmber else StudioBorder),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(sShape)
                                        .clickable {
                                            if (active) {
                                                onDeleteDynamicMark(existingAtAnchor.id)
                                            } else {
                                                onUpsertDynamicMark(anchorTick, lv, existingAtAnchor?.id)
                                            }
                                        }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = "${lv.marking} · ${lv.cnName.substringBefore(" ")}",
                                            fontFamily = FontFamily.Serif,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            color = if (active) WarmUmber else TextPrimary
                                        )
                                        Text(
                                            text = lv.subtitle,
                                            fontSize = 10.sp,
                                            color = TextMuted
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = StudioBorder)

                        // Gradual Hairpins (`< cresc.` / `> dim.`)
                        Text(
                            text = if (editingHairpin != null) {
                                "修改已选渐变发夹线（起点 ${formatScorePositionLabel(anchorTick, melody)}，跨度 $hairpinSpanBeats 拍）："
                            } else {
                                "渐变力度发夹线（从 ${formatScorePositionLabel(anchorTick, melody)} 起平滑插值 $hairpinSpanBeats 拍）："
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            val presets = listOf(
                                "2拍" to 2,
                                "1小节" to beatsPerBar,
                                "2小节" to beatsPerBar * 2,
                                "4小节" to beatsPerBar * 4
                            ).distinctBy { it.second }
                            presets.forEach { (lbl, bCount) ->
                                MelodyTinyToggle(
                                    label = lbl,
                                    active = hairpinSpanBeats == bCount,
                                    activeColor = VioletAccent,
                                    onClick = { hairpinSpanBeats = bCount }
                                )
                            }
                            MelodySmallStepBtn("-") { hairpinSpanBeats = (hairpinSpanBeats - 1).coerceIn(1, 32) }
                            Text(
                                text = "${hairpinSpanBeats}拍",
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            MelodySmallStepBtn("+") { hairpinSpanBeats = (hairpinSpanBeats + 1).coerceIn(1, 32) }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            HairpinType.entries.forEach { hpType ->
                                val isEditingThis = editingHairpin?.type == hpType
                                val hShape = RoundedCornerShape(8.dp)
                                Surface(
                                    shape = hShape,
                                    color = if (isEditingThis) VioletAccent else VioletAccent.copy(alpha = 0.14f),
                                    border = BorderStroke(1.2.dp, VioletAccent),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(hShape)
                                        .clickable {
                                            val endTick = anchorTick + hairpinSpanBeats * unitTicks
                                            onUpsertHairpin(anchorTick, endTick, hpType, editingHairpin?.id)
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                                        Text(
                                            text = if (editingHairpin != null) {
                                                "设为 ${hpType.label}"
                                            } else {
                                                "+ ${hpType.label}"
                                            },
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isEditingThis) OnAccentLight else VioletAccent,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        if (melody.dynamicMarks.isNotEmpty() || melody.hairpins.isNotEmpty()) {
                            HorizontalDivider(color = StudioBorder)
                            Text(
                                text = "已添加的力度记号（点击定位修改，点 ✕ 删除）：",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                melody.dynamicMarks.forEach { dm ->
                                    val selected = editingDynamic?.id == dm.id
                                    ExpressionAnnotationRowItem(
                                        badge = dm.level.marking,
                                        title = "${formatScorePositionLabel(dm.tick, melody)} · ${dm.level.cnName}",
                                        selected = selected,
                                        onSelect = { onSelectAnnotation(SelectedScoreAnnotation.Dynamic(dm.id)) },
                                        onDelete = { onDeleteDynamicMark(dm.id) }
                                    )
                                }
                                melody.hairpins.forEach { hp ->
                                    val selected = editingHairpin?.id == hp.id
                                    ExpressionAnnotationRowItem(
                                        badge = hp.type.wedgeSymbol,
                                        title = "${formatScorePositionLabel(hp.safeStartTick, melody)} → ${formatScorePositionLabel(hp.safeEndTick, melody)} · ${hp.type.label}",
                                        selected = selected,
                                        onSelect = { onSelectAnnotation(SelectedScoreAnnotation.Hairpin(hp.id)) },
                                        onDelete = { onDeleteHairpin(hp.id) }
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        val editingSteadyId = editingTempo?.takeIf { it.kind == TempoEventKind.STEADY_BPM }?.id
                        val editingRampId = editingTempo?.takeIf {
                            it.kind == TempoEventKind.RITARDANDO || it.kind == TempoEventKind.ACCELERANDO
                        }?.id

                        Text(
                            text = "常用速度术语（点击在 ${formatScorePositionLabel(anchorTick, melody)} 设为对应速度）：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        TempoTerm.entries.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                pair.forEach { term ->
                                    val active = selectedTempoTerm == term && kotlin.math.abs(customBpm - term.defaultBpm) <= 8
                                    val tShape = RoundedCornerShape(8.dp)
                                    Surface(
                                        shape = tShape,
                                        color = if (active) CyanPrimary.copy(alpha = 0.18f) else StudioSurfaceElevated,
                                        border = BorderStroke(1.1.dp, if (active) CyanPrimary else StudioBorder),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(tShape)
                                            .clickable {
                                                selectedTempoTerm = term
                                                customBpm = term.defaultBpm
                                                customBpmText = term.defaultBpm.toString()
                                                onUpsertTempoEvent(
                                                    ScoreTempoEvent(
                                                        id = editingSteadyId ?: UUID.randomUUID().toString(),
                                                        startTick = anchorTick,
                                                        kind = TempoEventKind.STEADY_BPM,
                                                        bpm = term.defaultBpm,
                                                        term = term
                                                    )
                                                )
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(
                                                    text = "${term.italianName} · ${term.cnName}",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (active) CyanPrimary else TextPrimary
                                                )
                                                Text(
                                                    text = term.typicalRange,
                                                    fontSize = 9.5.sp,
                                                    color = TextMuted
                                                )
                                            }
                                            Text(
                                                text = "${term.defaultBpm}",
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = CyanPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Mid-Score Custom `♩ = xx` BPM Change
                        Text(
                            text = "自定义速度变更（♩ = 36 ~ 240 BPM）：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MelodySmallStepBtn("-5") {
                                val next = (customBpm - 5).coerceIn(36, 240)
                                customBpm = next
                                customBpmText = next.toString()
                            }
                            MelodySmallStepBtn("-") {
                                val next = (customBpm - 1).coerceIn(36, 240)
                                customBpm = next
                                customBpmText = next.toString()
                            }
                            OutlinedTextField(
                                value = customBpmText,
                                onValueChange = { raw ->
                                    val digits = raw.filter { it.isDigit() }.take(3)
                                    customBpmText = digits
                                    digits.toIntOrNull()?.let { parsed ->
                                        customBpm = parsed.coerceIn(36, 240)
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                suffix = { Text("BPM", fontSize = 10.5.sp, color = TextMuted) },
                                modifier = Modifier.weight(1f)
                            )
                            MelodySmallStepBtn("+") {
                                val next = (customBpm + 1).coerceIn(36, 240)
                                customBpm = next
                                customBpmText = next.toString()
                            }
                            MelodySmallStepBtn("+5") {
                                val next = (customBpm + 5).coerceIn(36, 240)
                                customBpm = next
                                customBpmText = next.toString()
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val applyAtAnchorShape = RoundedCornerShape(8.dp)
                            Surface(
                                shape = applyAtAnchorShape,
                                color = CyanPrimary,
                                modifier = Modifier
                                    .weight(1.25f)
                                    .height(34.dp)
                                    .clip(applyAtAnchorShape)
                                    .clickable {
                                        val matchedTerm = selectedTempoTerm?.takeIf {
                                            kotlin.math.abs(it.defaultBpm - customBpm) <= 14
                                        }
                                        onUpsertTempoEvent(
                                            ScoreTempoEvent(
                                                id = editingSteadyId ?: UUID.randomUUID().toString(),
                                                startTick = anchorTick,
                                                kind = TempoEventKind.STEADY_BPM,
                                                bpm = customBpm,
                                                term = matchedTerm
                                            )
                                        )
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "在此位置设为 ♩ = $customBpm",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = OnAccentLight
                                    )
                                }
                            }
                            if (anchorTick > 0) {
                                val startScoreShape = RoundedCornerShape(8.dp)
                                Surface(
                                    shape = startScoreShape,
                                    color = StudioSurfaceElevated,
                                    border = BorderStroke(1.dp, CyanPrimary),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(startScoreShape)
                                        .clickable {
                                            val matchedTerm = selectedTempoTerm?.takeIf {
                                                kotlin.math.abs(it.defaultBpm - customBpm) <= 14
                                            }
                                            onUpsertTempoEvent(
                                                ScoreTempoEvent(
                                                    startTick = 0,
                                                    kind = TempoEventKind.STEADY_BPM,
                                                    bpm = customBpm,
                                                    term = matchedTerm
                                                )
                                            )
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "设为曲首速度",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CyanPrimary
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = StudioBorder)

                        // Gradual Tempo Ramps (`rit.`, `accel.`, `a tempo`)
                        Text(
                            text = "渐慢 rit. · 渐快 accel. · 回原速 a tempo（节拍器与播放指针同步变速）：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            val presets = listOf(
                                "2拍" to 2,
                                "1小节" to beatsPerBar,
                                "2小节" to beatsPerBar * 2,
                                "4小节" to beatsPerBar * 4
                            ).distinctBy { it.second }
                            presets.forEach { (lbl, bCount) ->
                                MelodyTinyToggle(
                                    label = lbl,
                                    active = rampSpanBeats == bCount,
                                    activeColor = WarmUmber,
                                    onClick = { rampSpanBeats = bCount }
                                )
                            }
                            MelodySmallStepBtn("-") { rampSpanBeats = (rampSpanBeats - 1).coerceIn(1, 32) }
                            Text(
                                text = "${rampSpanBeats}拍",
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            MelodySmallStepBtn("+") { rampSpanBeats = (rampSpanBeats + 1).coerceIn(1, 32) }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MelodyTinyToggle(
                                label = if (useCustomRampTarget) "指定终点速度:开" else "自动自然变速幅度",
                                active = useCustomRampTarget,
                                activeColor = WarmUmber,
                                onClick = { useCustomRampTarget = !useCustomRampTarget }
                            )
                            if (useCustomRampTarget) {
                                MelodySmallStepBtn("-5") {
                                    customRampTargetBpm = (customRampTargetBpm - 5).coerceIn(32, 240)
                                }
                                Text(
                                    text = "至 ♩=$customRampTargetBpm",
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = WarmUmber
                                )
                                MelodySmallStepBtn("+5") {
                                    customRampTargetBpm = (customRampTargetBpm + 5).coerceIn(32, 240)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val rampEnd = anchorTick + rampSpanBeats * unitTicks
                            val ritBtnShape = RoundedCornerShape(8.dp)
                            Surface(
                                shape = ritBtnShape,
                                color = WarmUmber.copy(alpha = 0.16f),
                                border = BorderStroke(1.2.dp, WarmUmber),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(ritBtnShape)
                                    .clickable {
                                        onUpsertTempoEvent(
                                            ScoreTempoEvent(
                                                id = editingRampId ?: UUID.randomUUID().toString(),
                                                startTick = anchorTick,
                                                endTick = rampEnd,
                                                kind = TempoEventKind.RITARDANDO,
                                                targetBpm = if (useCustomRampTarget) customRampTargetBpm else null
                                            )
                                        )
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "+ 渐慢 rit.",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = WarmUmber
                                    )
                                }
                            }

                            Surface(
                                shape = ritBtnShape,
                                color = EmeraldLossless.copy(alpha = 0.15f),
                                border = BorderStroke(1.2.dp, EmeraldLossless),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(ritBtnShape)
                                    .clickable {
                                        onUpsertTempoEvent(
                                            ScoreTempoEvent(
                                                id = editingRampId ?: UUID.randomUUID().toString(),
                                                startTick = anchorTick,
                                                endTick = rampEnd,
                                                kind = TempoEventKind.ACCELERANDO,
                                                targetBpm = if (useCustomRampTarget) customRampTargetBpm else null
                                            )
                                        )
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "+ 渐快 accel.",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldLossless
                                    )
                                }
                            }

                            Surface(
                                shape = ritBtnShape,
                                color = VioletAccent.copy(alpha = 0.15f),
                                border = BorderStroke(1.2.dp, VioletAccent),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(ritBtnShape)
                                    .clickable {
                                        onUpsertTempoEvent(
                                            ScoreTempoEvent(
                                                startTick = anchorTick,
                                                kind = TempoEventKind.A_TEMPO
                                            )
                                        )
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "回原速 a tempo",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = VioletAccent,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        if (melody.tempoEvents.isNotEmpty()) {
                            HorizontalDivider(color = StudioBorder)
                            Text(
                                text = "已添加的速度记号（点击定位修改，点 ✕ 删除）：",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                melody.tempoEvents.forEach { ev ->
                                    val selected = editingTempo?.id == ev.id
                                    val posLabel = if (ev.kind == TempoEventKind.RITARDANDO || ev.kind == TempoEventKind.ACCELERANDO) {
                                        "${formatScorePositionLabel(ev.safeStartTick, melody)} → ${formatScorePositionLabel(ev.safeEndTick, melody)}"
                                    } else {
                                        formatScorePositionLabel(ev.safeStartTick, melody)
                                    }
                                    ExpressionAnnotationRowItem(
                                        badge = ev.kind.shortLabel.take(4),
                                        title = "$posLabel · ${ev.summaryText()}",
                                        selected = selected,
                                        onSelect = { onSelectAnnotation(SelectedScoreAnnotation.Tempo(ev.id)) },
                                        onDelete = { onDeleteTempoEvent(ev.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                )
            ) {
                Text("完成", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun ExpressionAnnotationRowItem(
    badge: String,
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape = shape,
        color = if (selected) VioletAccent.copy(alpha = 0.14f) else StudioSurfaceElevated,
        border = BorderStroke(1.dp, if (selected) VioletAccent else StudioBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = badge,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (selected) VioletAccent else CyanPrimary
                )
                Text(
                    text = title,
                    fontSize = 11.5.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "删除此记号",
                tint = DangerRed,
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onDelete)
                    .padding(2.dp)
            )
        }
    }
}

// ============================================================================
// Quick BPM Modification Dialog (Direct Numeric Input, Slider, Steppers, Tap Tempo & Presets)
// ============================================================================

@Composable
private fun BpmQuickInputDialog(
    currentBpm: Int,
    referenceTrackBpm: Int?,
    onBpmChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var draftBpm by remember { mutableIntStateOf(currentBpm.coerceIn(40, 220)) }
    var textValue by remember { mutableStateOf(currentBpm.coerceIn(40, 220).toString()) }
    var tapTimestamps by remember { mutableStateOf<List<Long>>(emptyList()) }

    val applyBpmValue: (Int) -> Unit = { raw ->
        val clamped = raw.coerceIn(40, 220)
        draftBpm = clamped
        textValue = clamped.toString()
        onBpmChange(clamped)
    }

    val parsedTyped = textValue.trim().toIntOrNull()
    val isTypedOutOfRange = parsedTyped != null && parsedTyped !in 40..220

    AlertDialog(
        onDismissRequest = {
            if (parsedTyped != null) {
                onBpmChange(parsedTyped.coerceIn(40, 220))
            }
            onDismiss()
        },
        containerColor = StudioSurface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "速度设置 (BPM 快速修改)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                if (referenceTrackBpm != null) {
                    MelodyTinyToggle(
                        label = "同原曲 ($referenceTrackBpm)",
                        active = draftBpm == referenceTrackBpm,
                        activeColor = CyanPrimary,
                        onClick = { applyBpmValue(referenceTrackBpm) }
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Direct Numeric Input
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(3)
                        textValue = digits
                        val num = digits.toIntOrNull()
                        if (num != null && num in 40..220) {
                            draftBpm = num
                            onBpmChange(num)
                        }
                    },
                    singleLine = true,
                    label = { Text("直接输入速度数值 (40 ~ 220 BPM)") },
                    suffix = { Text("BPM", color = TextSecondary) },
                    isError = isTypedOutOfRange,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (isTypedOutOfRange) {
                    Text(
                        text = "有效速度范围为 40 ~ 220 BPM（点击完成将自动对齐到有效范围）",
                        fontSize = 11.sp,
                        color = WarmUmber
                    )
                }

                // 2. Multi-Step Quick Nudges (-10 / -5 / -1 / +1 / +5 / +10)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    listOf(-10, -5, -1, 1, 5, 10).forEach { delta ->
                        val stepLabel = if (delta > 0) "+$delta" else "$delta"
                        val stepShape = RoundedCornerShape(8.dp)
                        Surface(
                            shape = stepShape,
                            color = StudioSurfaceElevated,
                            border = BorderStroke(1.dp, StudioBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(stepShape)
                                .clickable { applyBpmValue(draftBpm + delta) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stepLabel,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (abs(delta) >= 5) CyanPrimary else TextPrimary
                                )
                            }
                        }
                    }
                }

                // 3. Smooth Continuous Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "拖动滑杆调速（顶栏 BPM 胶囊也支持直接左右滑动）",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Text(
                            text = "♩ = $draftBpm",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary
                        )
                    }
                    Slider(
                        value = draftBpm.toFloat(),
                        onValueChange = { applyBpmValue(it.roundToInt()) },
                        valueRange = 40f..220f,
                        colors = SliderDefaults.colors(
                            thumbColor = CyanPrimary,
                            activeTrackColor = CyanPrimary
                        )
                    )
                }

                // 4. Tap Tempo (`连续点拍测速`)
                val tapShape = RoundedCornerShape(10.dp)
                Surface(
                    shape = tapShape,
                    color = VioletAccent.copy(alpha = 0.13f),
                    border = BorderStroke(1.2.dp, VioletAccent.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(tapShape)
                        .clickable {
                            val now = SystemClock.elapsedRealtime()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val prev = tapTimestamps.lastOrNull()
                            val updated = if (prev == null || (now - prev) > 2600L) {
                                listOf(now)
                            } else {
                                (tapTimestamps + now).takeLast(7)
                            }
                            tapTimestamps = updated
                            if (updated.size >= 2) {
                                val intervals = (1 until updated.size).map { updated[it] - updated[it - 1] }
                                val avgMs = intervals.average().coerceAtLeast(1.0)
                                val measuredBpm = (60000.0 / avgMs).roundToInt().coerceIn(40, 220)
                                applyBpmValue(measuredBpm)
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "连续点拍测速 (Tap Tempo)",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = VioletAccent
                        )
                        Text(
                            text = when {
                                tapTimestamps.isEmpty() -> "跟着节拍连续点击此处"
                                tapTimestamps.size == 1 -> "再点 1 下即出速度..."
                                else -> "已点 ${tapTimestamps.size} 拍 → $draftBpm BPM"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (tapTimestamps.size >= 2) VioletAccent else TextSecondary
                        )
                    }
                }

                // 5. Common BPM Presets
                Text(
                    text = "常用速度预设：",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(52, 68, 80, 92, 104, 112, 120, 132, 144, 160, 180).forEach { preset ->
                        MelodyTinyToggle(
                            label = "$preset",
                            active = draftBpm == preset,
                            activeColor = CyanPrimary,
                            onClick = { applyBpmValue(preset) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalVal = parsedTyped?.coerceIn(40, 220) ?: draftBpm
                    onBpmChange(finalVal)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                )
            ) {
                Text("完成", fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ============================================================================
// Unified Key Signature & Mid-Score Modulation Studio Dialog (`可选调号 + 中途变调`)
// ============================================================================

@Composable
private fun KeySignatureStudioDialog(
    melody: MelodyStudioUiState,
    referenceTrackKeySignature: KeySignature?,
    referenceTrackName: String?,
    onSetInitialKeySignature: (KeySignature) -> Unit,
    onUpsertMidKeySignature: (barIndex: Int, keySignature: KeySignature, editingId: String?) -> Unit,
    onDeleteMidKeySignature: (String) -> Unit,
    onTapCursorTick: (Int) -> Unit,
    onSelectAnnotation: (SelectedScoreAnnotation?) -> Unit,
    onDismiss: () -> Unit
) {
    val ticksPerBar = melody.ticksPerBar.coerceAtLeast(1)
    val cursorBarIndex = (melody.cursorTick.coerceAtLeast(0) / ticksPerBar).coerceAtLeast(0)
    val selectedModEvent = (melody.selectedAnnotation as? SelectedScoreAnnotation.KeyChange)?.let { ann ->
        melody.keySignatureEvents.firstOrNull { it.id == ann.id }
    }

    var isMidScoreScope by remember(selectedModEvent?.id) {
        mutableStateOf(selectedModEvent != null)
    }
    var targetBarIndex by remember(selectedModEvent?.id, cursorBarIndex) {
        mutableIntStateOf(
            (selectedModEvent?.safeBarIndex ?: max(1, cursorBarIndex))
                .coerceIn(1, MAX_MELODY_BARS - 1)
        )
    }

    val existingModAtTarget = selectedModEvent?.takeIf { it.safeBarIndex == targetBarIndex }
        ?: melody.keySignatureEventAtBar(targetBarIndex)
    val effectiveCurrentKeyForScope = if (isMidScoreScope) {
        existingModAtTarget?.keySignature ?: melody.keySignatureAtBar(targetBarIndex)
    } else {
        melody.initialKeySignature
    }

    var draftFifths by remember(isMidScoreScope, targetBarIndex, effectiveCurrentKeyForScope) {
        mutableIntStateOf(effectiveCurrentKeyForScope.safeFifths)
    }
    var draftMinor by remember(isMidScoreScope, targetBarIndex, effectiveCurrentKeyForScope) {
        mutableStateOf(effectiveCurrentKeyForScope.isMinor)
    }

    val draftKey = KeySignature(draftFifths, draftMinor)
    val prevKeyBeforeMod = if (isMidScoreScope) {
        melody.keySignatureAtBar((targetBarIndex - 1).coerceAtLeast(0))
    } else {
        null
    }

    val commitKeySelection: (KeySignature) -> Unit = { chosenKey ->
        if (!isMidScoreScope) {
            onSetInitialKeySignature(chosenKey)
        } else {
            onUpsertMidKeySignature(targetBarIndex, chosenKey, existingModAtTarget?.id)
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "调号与中途变调设置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "无损音高拼写",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = EmeraldLossless
                    )
                }

                // Unified Scope Selector: Initial Score Key (Bar 1) vs Mid-Score Modulation (Bar N)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val initShape = RoundedCornerShape(8.dp)
                    Surface(
                        shape = initShape,
                        color = if (!isMidScoreScope) CyanPrimary else StudioSurfaceElevated,
                        border = BorderStroke(1.dp, if (!isMidScoreScope) CyanPrimary else StudioBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(initShape)
                            .clickable { isMidScoreScope = false }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Text(
                                text = "全曲初始调号 (第1小节起)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!isMidScoreScope) OnAccentLight else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    val midLabel = if (cursorBarIndex >= 1 || selectedModEvent != null) {
                        "从当前第${targetBarIndex + 1}小节起变调"
                    } else {
                        "插入中途变调 (第${targetBarIndex + 1}小节起)"
                    }
                    Surface(
                        shape = initShape,
                        color = if (isMidScoreScope) VioletAccent else StudioSurfaceElevated,
                        border = BorderStroke(1.dp, if (isMidScoreScope) VioletAccent else StudioBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(initShape)
                            .clickable {
                                if (!isMidScoreScope) {
                                    targetBarIndex = (selectedModEvent?.safeBarIndex ?: max(1, cursorBarIndex))
                                        .coerceIn(1, MAX_MELODY_BARS - 1)
                                    isMidScoreScope = true
                                }
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Text(
                                text = midLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isMidScoreScope) OnAccentLight else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                // 1-Tap Sync with Workspace Reference Track Key Signature
                if (referenceTrackKeySignature != null) {
                    val syncShape = RoundedCornerShape(9.dp)
                    Surface(
                        shape = syncShape,
                        color = CyanPrimary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(syncShape)
                            .clickable {
                                draftFifths = referenceTrackKeySignature.safeFifths
                                draftMinor = referenceTrackKeySignature.isMinor
                                commitKeySelection(referenceTrackKeySignature)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "一键同步参考曲调号：${referenceTrackKeySignature.shortPillLabel}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!referenceTrackName.isNullOrBlank()) {
                                    Text(
                                        text = "工作台曲目：$referenceTrackName",
                                        fontSize = 10.sp,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                text = "立即同步 ▸",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary
                            )
                        }
                    }
                }

                // Target Bar Stepper & Delete Action when in Mid-Score Modulation mode
                if (isMidScoreScope) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = VioletAccent.copy(alpha = 0.09f),
                        border = BorderStroke(1.dp, VioletAccent.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = "变调位置:",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                MelodySmallStepBtn("−") {
                                    targetBarIndex = (targetBarIndex - 1).coerceIn(1, MAX_MELODY_BARS - 1)
                                }
                                Text(
                                    text = "第 ${targetBarIndex + 1} 小节起",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VioletAccent
                                )
                                MelodySmallStepBtn("+") {
                                    targetBarIndex = (targetBarIndex + 1).coerceIn(1, max(melody.totalBars + 4, targetBarIndex + 1).coerceAtMost(MAX_MELODY_BARS - 1))
                                }
                            }

                            if (existingModAtTarget != null) {
                                val delShape = RoundedCornerShape(6.dp)
                                Surface(
                                    shape = delShape,
                                    color = DangerRed.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.55f)),
                                    modifier = Modifier
                                        .clip(delShape)
                                        .clickable {
                                            onDeleteMidKeySignature(existingModAtTarget.id)
                                            onDismiss()
                                        }
                                ) {
                                    Text(
                                        text = "删除变调(恢复前调)",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DangerRed,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                    )
                                }
                            } else {
                                Text(
                                    text = "前调: ${prevKeyBeforeMod?.shortPillLabel ?: "C大调"}",
                                    fontSize = 10.5.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                // Live 5-Line Treble Staff Key Signature + Cancellation Preview
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = Color(0xFFFAF7F0),
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .width(if (isMidScoreScope) 156.dp else 112.dp)
                                .height(54.dp)
                        ) {
                            StaffScoreEngraver.drawMiniKeySignaturePreview(
                                canvas = drawContext.canvas.nativeCanvas,
                                width = size.width,
                                height = size.height,
                                keySignature = draftKey,
                                prevKeyForModulation = prevKeyBeforeMod,
                                inkColor = android.graphics.Color.rgb(40, 36, 32),
                                staffLineColor = android.graphics.Color.rgb(126, 118, 106)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = draftKey.fullDisplayName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = if (draftKey.prefersFlats) {
                                    "琴键黑键联动：bD · bE · bG · bA · bB"
                                } else {
                                    "琴键黑键联动：#C · #D · #F · #G · #A"
                                },
                                fontSize = 10.5.sp,
                                color = TextSecondary
                            )
                            if (isMidScoreScope && prevKeyBeforeMod != null) {
                                val cancelCnt = prevKeyBeforeMod.cancelledStaffStepsTo(draftKey, isBass = false).size
                                Text(
                                    text = if (cancelCnt > 0) {
                                        "细双小节线 + 自动补 ${cancelCnt} 个还原抵消号 ♮"
                                    } else {
                                        "细双小节线 + 绘制新调号记谱"
                                    },
                                    fontSize = 10.sp,
                                    color = VioletAccent
                                )
                            }
                        }
                    }
                }

                // Major vs Relative Minor Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "五度圈调号选择（-7♭ ~ 0 ~ +7♯）：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MelodyTinyToggle(
                            label = "大调 (Major)",
                            active = !draftMinor,
                            activeColor = CyanPrimary,
                            onClick = { draftMinor = false }
                        )
                        MelodyTinyToggle(
                            label = "关系小调 (Minor)",
                            active = draftMinor,
                            activeColor = VioletAccent,
                            onClick = { draftMinor = true }
                        )
                    }
                }

                // 0 Sharps / 0 Flats (C Major / a minor)
                KeySignatureFifthsGridRow(
                    fifthsList = listOf(0),
                    selectedFifths = draftFifths,
                    isMinor = draftMinor,
                    onSelectFifths = { draftFifths = it }
                )

                // Sharp Keys (+1♯ ~ +7♯) in crisp 4 + 3 rows
                Text(
                    text = "升号调 (1♯ ~ 7♯)：",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
                KeySignatureFifthsGridRow(
                    fifthsList = listOf(1, 2, 3, 4),
                    selectedFifths = draftFifths,
                    isMinor = draftMinor,
                    onSelectFifths = { draftFifths = it }
                )
                KeySignatureFifthsGridRow(
                    fifthsList = listOf(5, 6, 7),
                    selectedFifths = draftFifths,
                    isMinor = draftMinor,
                    onSelectFifths = { draftFifths = it }
                )

                // Flat Keys (-1♭ ~ -7♭) in crisp 4 + 3 rows
                Text(
                    text = "降号调 (1♭ ~ 7♭)：",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
                KeySignatureFifthsGridRow(
                    fifthsList = listOf(-1, -2, -3, -4),
                    selectedFifths = draftFifths,
                    isMinor = draftMinor,
                    onSelectFifths = { draftFifths = it }
                )
                KeySignatureFifthsGridRow(
                    fifthsList = listOf(-5, -6, -7),
                    selectedFifths = draftFifths,
                    isMinor = draftMinor,
                    onSelectFifths = { draftFifths = it }
                )

                // Existing Mid-Score Key Changes List
                if (melody.keySignatureEvents.isNotEmpty()) {
                    HorizontalDivider(color = StudioBorder)
                    Text(
                        text = "已添加的中途变调（点击定位修改，点 ✕ 删除并恢复前调）：",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        melody.keySignatureEvents.forEach { ev ->
                            val isSelected = isMidScoreScope && targetBarIndex == ev.safeBarIndex
                            val prev = melody.keySignatureAtBar((ev.safeBarIndex - 1).coerceAtLeast(0))
                            ExpressionAnnotationRowItem(
                                badge = "第${ev.barNumber}小节",
                                title = "${prev.shortPillLabel} → ${ev.keySignature.shortPillLabel}",
                                selected = isSelected,
                                onSelect = {
                                    onSelectAnnotation(SelectedScoreAnnotation.KeyChange(ev.id))
                                    onTapCursorTick(ev.safeBarIndex * ticksPerBar)
                                    targetBarIndex = ev.safeBarIndex
                                    isMidScoreScope = true
                                    draftFifths = ev.keySignature.safeFifths
                                    draftMinor = ev.keySignature.isMinor
                                },
                                onDelete = { onDeleteMidKeySignature(ev.id) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { commitKeySelection(draftKey) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMidScoreScope) VioletAccent else CyanPrimary,
                    contentColor = OnAccentLight
                )
            ) {
                Text(
                    text = if (isMidScoreScope) {
                        if (existingModAtTarget != null) {
                            "更新第 ${targetBarIndex + 1} 小节变调"
                        } else {
                            "从第 ${targetBarIndex + 1} 小节起变调"
                        }
                    } else {
                        "应用初始调号"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun KeySignatureFifthsGridRow(
    fifthsList: List<Int>,
    selectedFifths: Int,
    isMinor: Boolean,
    onSelectFifths: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        fifthsList.forEach { f ->
            val ks = KeySignature(f, isMinor)
            val active = selectedFifths == f
            val accent = if (isMinor) VioletAccent else CyanPrimary
            val chipShape = RoundedCornerShape(8.dp)
            Surface(
                shape = chipShape,
                color = if (active) accent else StudioSurfaceElevated,
                border = BorderStroke(1.1.dp, if (active) accent else StudioBorder),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(chipShape)
                    .clickable { onSelectFifths(f) }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 3.dp)
                ) {
                    Text(
                        text = if (f == 0) {
                            "${ks.modeNameCn} (无升降)"
                        } else {
                            "${ks.modeNameCn} · ${ks.accidentalCountBadge}"
                        },
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) OnAccentLight else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isMinor) "关系 ${ks.majorTonicName}大调" else "关系 ${ks.minorTonicName}小调",
                        fontSize = 9.sp,
                        color = if (active) OnAccentLight.copy(alpha = 0.88f) else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ============================================================================
// Score Draft Serialization & Multi-Project Manager Dialog (`乐谱草稿保存与读取`)
// ============================================================================

private sealed interface PendingScoreSwitchAction {
    data object NewBlank : PendingScoreSwitchAction
    data object RestoreAutoDraft : PendingScoreSwitchAction
    data class LoadSaved(val project: MelodyProjectSummary) : PendingScoreSwitchAction
}

@Composable
private fun MelodyProjectManagerDialog(
    melody: MelodyStudioUiState,
    onUpdateTitle: (String) -> Unit,
    onNewBlankProject: () -> Unit,
    onSaveProject: (overwriteCurrent: Boolean, customTitle: String?) -> Unit,
    onLoadProject: (String) -> Unit,
    onRestoreAutoSaveDraft: () -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onShareProjectWeChat: (projectId: String?, customTitle: String?) -> Unit,
    onShareProjectSystem: (projectId: String?, customTitle: String?) -> Unit,
    onSaveProjectDownloads: (projectId: String?, customTitle: String?) -> Unit,
    onSaveProjectCustomFolder: (projectId: String?, customTitle: String?) -> Unit,
    onImportProjectClick: () -> Unit,
    onDismiss: () -> Unit
) {
    var titleInput by remember(melody.scoreTitle) { mutableStateOf(melody.scoreTitle) }
    val cleanTitle = titleInput.trim().ifBlank { "未命名乐谱" }

    var pendingSwitch by remember { mutableStateOf<PendingScoreSwitchAction?>(null) }
    var renamingProject by remember { mutableStateOf<MelodyProjectSummary?>(null) }
    var deletingProject by remember { mutableStateOf<MelodyProjectSummary?>(null) }

    val executeSwitchAction: (PendingScoreSwitchAction) -> Unit = { action ->
        when (action) {
            PendingScoreSwitchAction.NewBlank -> onNewBlankProject()
            PendingScoreSwitchAction.RestoreAutoDraft -> onRestoreAutoSaveDraft()
            is PendingScoreSwitchAction.LoadSaved -> onLoadProject(action.project.id)
        }
    }

    val requestSwitchWithDirtyGuard: (PendingScoreSwitchAction) -> Unit = { action ->
        if (melody.isProjectDirty) {
            pendingSwitch = action
        } else {
            executeSwitchAction(action)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "乐谱草稿与工程管理",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (melody.isProjectDirty) {
                        WarmUmber.copy(alpha = 0.14f)
                    } else {
                        EmeraldLossless.copy(alpha = 0.13f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (melody.isProjectDirty) WarmUmber.copy(alpha = 0.5f) else EmeraldLossless.copy(alpha = 0.45f)
                    )
                ) {
                    Text(
                        text = when {
                            melody.isProjectDirty -> "有未保存修改 · 自动保护中"
                            melody.currentProjectId != null -> "已保存同步"
                            else -> "实时自动草稿保护"
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (melody.isProjectDirty) WarmUmber else EmeraldLossless,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Current Score Save / Save-As / New Blank Card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioSurfaceElevated,
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = titleInput,
                            onValueChange = { raw ->
                                val sanitized = raw.replace('\n', ' ').take(50)
                                titleInput = sanitized
                                onUpdateTitle(sanitized)
                            },
                            singleLine = true,
                            label = { Text("当前乐谱名称") },
                            placeholder = { Text("例如：晴天 · 前奏打谱草稿") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (melody.currentProjectId != null) {
                                Button(
                                    onClick = { onSaveProject(true, cleanTitle) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyanPrimary,
                                        contentColor = OnAccentLight
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                ) {
                                    Text("保存覆盖", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { onSaveProject(false, cleanTitle) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                ) {
                                    Text("另存为新谱", fontSize = 11.5.sp, color = TextPrimary, maxLines = 1)
                                }
                            } else {
                                Button(
                                    onClick = { onSaveProject(false, cleanTitle) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyanPrimary,
                                        contentColor = OnAccentLight
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier
                                        .weight(1.35f)
                                        .height(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SaveAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("保存到本地草稿箱", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }

                            OutlinedButton(
                                onClick = { requestSwitchWithDirtyGuard(PendingScoreSwitchAction.NewBlank) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("新建空白谱", fontSize = 11.5.sp, color = TextSecondary, maxLines = 1)
                            }
                        }
                    }
                }

                // 2. Portable `.stscore.json` Project Export & Local Import
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioSurfaceElevated.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, StudioBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(
                            text = "工程文件备份与跨设备传输 (.stscore.json，完整保留可编辑音符/记号/音色)：",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MelodyTinyToggle(
                                label = "发微信好友 (.json)",
                                active = true,
                                activeColor = EmeraldLossless,
                                onClick = { onShareProjectWeChat(null, cleanTitle) }
                            )
                            MelodyTinyToggle(
                                label = "存到 Download",
                                active = false,
                                activeColor = CyanPrimary,
                                onClick = { onSaveProjectDownloads(null, cleanTitle) }
                            )
                            MelodyTinyToggle(
                                label = "自选目录另存",
                                active = false,
                                activeColor = CyanPrimary,
                                onClick = { onSaveProjectCustomFolder(null, cleanTitle) }
                            )
                            MelodyTinyToggle(
                                label = "系统分享",
                                active = false,
                                activeColor = VioletAccent,
                                onClick = { onShareProjectSystem(null, cleanTitle) }
                            )
                        }
                        OutlinedButton(
                            onClick = onImportProjectClick,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(35.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = VioletAccent,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "从手机本地导入乐谱工程文件 (.json)",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = VioletAccent
                            )
                        }
                    }
                }

                // 3. Auto-Save Recovery Card
                melody.autoSaveDraftSummary?.let { autoDraft ->
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = AmberMastering.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, AmberMastering.copy(alpha = 0.42f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "自动保存草稿：《${autoDraft.title}》",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AmberMastering,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${autoDraft.keySignatureLabel} · ${autoDraft.timeSignatureLabel}拍 · ♩=${autoDraft.bpm} · ${autoDraft.pitchedNoteCount}音 · ${formatProjectTimestamp(autoDraft.updatedAtMs)}",
                                    fontSize = 10.sp,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            val restoreShape = RoundedCornerShape(7.dp)
                            Surface(
                                shape = restoreShape,
                                color = AmberMastering.copy(alpha = 0.20f),
                                border = BorderStroke(1.dp, AmberMastering),
                                modifier = Modifier
                                    .clip(restoreShape)
                                    .clickable {
                                        requestSwitchWithDirtyGuard(PendingScoreSwitchAction.RestoreAutoDraft)
                                    }
                            ) {
                                Text(
                                    text = "恢复草稿",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AmberMastering,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                // 4. Saved Local Projects List
                HorizontalDivider(color = StudioBorder)
                Text(
                    text = "本地已保存乐谱 (${melody.savedProjects.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                if (melody.savedProjects.isEmpty()) {
                    Text(
                        text = "暂无手动存档的乐谱。写到一半点上方「保存到本地草稿箱」，下次可随时读取继续打谱。",
                        fontSize = 11.5.sp,
                        color = TextMuted
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        melody.savedProjects.forEach { proj ->
                            val isCurrent = proj.id == melody.currentProjectId
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (isCurrent) CyanPrimary.copy(alpha = 0.10f) else StudioSurfaceElevated,
                                border = BorderStroke(1.dp, if (isCurrent) CyanPrimary else StudioBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = "《${proj.title}》",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isCurrent) CyanPrimary else TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isCurrent) {
                                                Text(
                                                    text = "当前打开",
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = CyanPrimary
                                                )
                                            }
                                        }
                                        Text(
                                            text = formatProjectTimestamp(proj.updatedAtMs),
                                            fontSize = 10.sp,
                                            color = TextMuted
                                        )
                                    }

                                    Text(
                                        text = "${proj.keySignatureLabel} · ${proj.timeSignatureLabel}拍 · ♩=${proj.bpm} BPM · ${proj.totalBars}小节 · ${proj.pitchedNoteCount}个音符",
                                        fontSize = 10.5.sp,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val loadShape = RoundedCornerShape(7.dp)
                                        Surface(
                                            shape = loadShape,
                                            color = CyanPrimary,
                                            modifier = Modifier
                                                .height(28.dp)
                                                .clip(loadShape)
                                                .clickable {
                                                    requestSwitchWithDirtyGuard(PendingScoreSwitchAction.LoadSaved(proj))
                                                }
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(horizontal = 12.dp)
                                            ) {
                                                Text(
                                                    text = "读取",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = OnAccentLight
                                                )
                                            }
                                        }

                                        MelodyTinyToggle(
                                            label = "重命名",
                                            active = false,
                                            activeColor = CyanPrimary,
                                            onClick = { renamingProject = proj }
                                        )
                                        MelodyTinyToggle(
                                            label = "导出分享",
                                            active = false,
                                            activeColor = EmeraldLossless,
                                            onClick = { onShareProjectSystem(proj.id, proj.title) }
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "删除此存档",
                                            tint = DangerRed,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { deletingProject = proj }
                                                .padding(3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", fontWeight = FontWeight.Bold, color = CyanPrimary)
            }
        }
    )

    // Unsaved-Changes Confirmation Guard Sub-Dialog
    pendingSwitch?.let { switchTarget ->
        val targetDesc = when (switchTarget) {
            PendingScoreSwitchAction.NewBlank -> "新建空白乐谱"
            PendingScoreSwitchAction.RestoreAutoDraft -> "恢复自动保存草稿"
            is PendingScoreSwitchAction.LoadSaved -> "读取《${switchTarget.project.title}》"
        }
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            containerColor = StudioSurface,
            title = {
                Text("当前乐谱有未保存修改", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Text(
                    text = "即将${targetDesc}。是否先保存当前正在编辑的《$cleanTitle》，以免丢失最新修改？",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveProject(melody.currentProjectId != null, cleanTitle)
                        pendingSwitch = null
                        executeSwitchAction(switchTarget)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = OnAccentLight
                    )
                ) {
                    Text("先保存再继续", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { pendingSwitch = null }) {
                        Text("取消", color = TextSecondary)
                    }
                    TextButton(
                        onClick = {
                            pendingSwitch = null
                            executeSwitchAction(switchTarget)
                        }
                    ) {
                        Text("直接切换(可撤销)", color = WarmUmber, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        )
    }

    // Rename Saved Project Sub-Dialog
    renamingProject?.let { targetProj ->
        var newName by remember(targetProj.id) { mutableStateOf(targetProj.title) }
        AlertDialog(
            onDismissRequest = { renamingProject = null },
            containerColor = StudioSurface,
            title = {
                Text("重命名已存乐谱", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.replace('\n', ' ').take(50) },
                    singleLine = true,
                    label = { Text("新乐谱名称") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newName.trim().ifBlank { "未命名乐谱" }
                        onRenameProject(targetProj.id, trimmed)
                        if (targetProj.id == melody.currentProjectId) {
                            titleInput = trimmed
                        }
                        renamingProject = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = OnAccentLight
                    )
                ) {
                    Text("确认重命名")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingProject = null }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }

    // Delete Saved Project Confirmation Sub-Dialog
    deletingProject?.let { targetProj ->
        AlertDialog(
            onDismissRequest = { deletingProject = null },
            containerColor = StudioSurface,
            title = {
                Text("删除本地存档？", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Text(
                    text = "确定从本地草稿箱删除《${targetProj.title}》吗？删除后无法恢复该存档文件。",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteProject(targetProj.id)
                        deletingProject = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DangerRed,
                        contentColor = OnAccentLight
                    )
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingProject = null }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }
}

private fun formatProjectTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochMs))
    }.getOrDefault("")
}

// ============================================================================
// Glissando-Aware Anti-Mistouch Controls (`防误触 · 保留顺滑刮奏`)
// ============================================================================

@Composable
private fun TouchGuardSelectorPill(
    config: PianoTouchGuardConfig,
    onClick: () -> Unit
) {
    val activeColor = when (config.mode) {
        TouchGuardMode.OFF -> TextMuted
        TouchGuardMode.STANDARD -> EmeraldLossless
        TouchGuardMode.STRICT -> AmberMastering
    }
    val isActive = config.isEnabled
    val pillShape = RoundedCornerShape(7.dp)

    Surface(
        shape = pillShape,
        color = if (isActive) activeColor.copy(alpha = 0.15f) else StudioSurfaceElevated,
        border = BorderStroke(
            1.dp,
            if (isActive) activeColor.copy(alpha = 0.65f) else StudioBorder
        ),
        modifier = Modifier
            .height(27.dp)
            .clip(pillShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 7.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = "防误触设置",
                tint = if (isActive) activeColor else TextMuted,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "防误触:${config.mode.shortLabel}",
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) activeColor else TextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TouchGuardSettingsDialog(
    config: PianoTouchGuardConfig,
    onConfigChange: (PianoTouchGuardConfig) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = EmeraldLossless,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "琴键防误触与刮奏保护",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "零延迟落指 · 防快弹擦键 · 完整保留横滑刮奏（Glissando）",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    text = "防误触强度",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary
                )

                TouchGuardMode.entries.forEach { mode ->
                    val selected = config.mode == mode
                    val modeAccent = when (mode) {
                        TouchGuardMode.OFF -> TextSecondary
                        TouchGuardMode.STANDARD -> EmeraldLossless
                        TouchGuardMode.STRICT -> AmberMastering
                    }
                    val cardShape = RoundedCornerShape(10.dp)
                    Surface(
                        shape = cardShape,
                        color = if (selected) modeAccent.copy(alpha = 0.14f) else StudioSurfaceElevated,
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) modeAccent else StudioBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .clickable {
                                onConfigChange(config.copy(mode = mode))
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) modeAccent else TextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = mode.subtitle,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                            if (selected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = modeAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                if (config.isEnabled) {
                    HorizontalDivider(color = StudioBorder)

                    val planeShape = RoundedCornerShape(10.dp)
                    Surface(
                        shape = planeShape,
                        color = StudioSurfaceElevated,
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(planeShape)
                            .clickable {
                                onConfigChange(
                                    config.copy(glissandoSamePlaneLock = !config.glissandoSamePlaneLock)
                                )
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "刮奏同层导轨（白刮白 / 黑刮黑）",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "从白键横滑全程走白键不误挂黑键角；从黑键沿上排横滑可弹黑键五声刮奏。关闭则允许半音混合刮奏。",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = config.glissandoSamePlaneLock,
                                onCheckedChange = {
                                    onConfigChange(config.copy(glissandoSamePlaneLock = it))
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = OnAccentLight,
                                    checkedTrackColor = EmeraldLossless
                                )
                            )
                        }
                    }

                    val chromeShape = RoundedCornerShape(10.dp)
                    Surface(
                        shape = chromeShape,
                        color = StudioSurfaceElevated,
                        border = BorderStroke(1.dp, StudioBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(chromeShape)
                            .clickable {
                                onConfigChange(
                                    config.copy(lockChromeWhilePlaying = !config.lockChromeWhilePlaying)
                                )
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "弹录/按键时锁定八度条与尺寸条",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "实时弹录进行中或手指按住琴键时，防快弹高处黑键越界误移八度或误改键盘高度。",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = config.lockChromeWhilePlaying,
                                onCheckedChange = {
                                    onConfigChange(config.copy(lockChromeWhilePlaying = it))
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = OnAccentLight,
                                    checkedTrackColor = EmeraldLossless
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = OnAccentLight
                )
            ) {
                Text("完成")
            }
        }
    )
}
