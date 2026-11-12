package com.soundtracker.app.arranger

import com.soundtracker.app.dsp.NativeMixClip
import java.io.File
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToLong

const val MAX_ARRANGER_TRACKS = 10
const val MIN_CLIP_DURATION_MS = 300L
const val ARRANGER_SAMPLE_RATE = 44100

enum class AppWorkspaceMode {
    SINGLE_TRACK,
    MELODY_STUDIO,
    MULTI_TRACK_ARRANGER
}

data class ArrangerClip(
    val id: String = UUID.randomUUID().toString(),
    val sourceTrackId: String,
    val sourceTitle: String,
    val wavFile: File,
    val sourceDurationMs: Long,
    val timelineStartMs: Long,
    val sourceInMs: Long,
    val sourceOutMs: Long,
    val gainDb: Float = 0f,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val colorIndex: Int = 0,
    val fullWaveformPeaks: List<Float> = emptyList()
) {
    val durationMs: Long
        get() = (sourceOutMs - sourceInMs).coerceAtLeast(MIN_CLIP_DURATION_MS)

    val timelineEndMs: Long
        get() = timelineStartMs + durationMs
}

data class ArrangerTrack(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val volumeDb: Float = 0f,
    val colorIndex: Int = 0,
    val clips: List<ArrangerClip> = emptyList()
)

data class ArrangerSnapshot(
    val tracks: List<ArrangerTrack>,
    val selectedTrackId: String,
    val selectedClipId: String?
)

data class ArrangerUiState(
    val tracks: List<ArrangerTrack> = listOf(
        ArrangerTrack(name = "音轨 1", colorIndex = 0)
    ),
    val selectedTrackId: String = "",
    val selectedClipId: String? = null,
    val isPlaying: Boolean = false,
    val playheadMs: Long = 0L,
    val zoomDpPerSec: Float = 22f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
) {
    val effectiveSelectedTrackId: String
        get() = tracks.firstOrNull { it.id == selectedTrackId }?.id ?: tracks.first().id

    val selectedTrack: ArrangerTrack
        get() = tracks.firstOrNull { it.id == selectedTrackId } ?: tracks.first()

    val selectedClipWithTrack: Pair<ArrangerTrack, ArrangerClip>?
        get() {
            val cid = selectedClipId ?: return null
            for (t in tracks) {
                val c = t.clips.firstOrNull { it.id == cid }
                if (c != null) return t to c
            }
            return null
        }

    val totalClipCount: Int
        get() = tracks.sumOf { it.clips.size }

    val totalDurationMs: Long
        get() = tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .maxOfOrNull { it.timelineEndMs } ?: 0L

    fun buildNativeSchedule(sampleRate: Int = ARRANGER_SAMPLE_RATE): Pair<List<NativeMixClip>, Long> {
        val totalMs = totalDurationMs
        if (totalMs <= 0L) return emptyList<NativeMixClip>() to 0L

        val anySolo = tracks.any { it.solo }
        val totalFrames = ((totalMs / 1000.0) * sampleRate).roundToLong().coerceAtLeast(1L)
        val out = ArrayList<NativeMixClip>()

        for (track in tracks) {
            val audible = if (anySolo) track.solo else !track.muted
            if (!audible) continue

            for (clip in track.clips) {
                if (!clip.wavFile.exists()) continue
                val totalDb = (track.volumeDb + clip.gainDb).coerceIn(-48f, 24f)
                val linGain = 10.0.pow(totalDb / 20.0).toFloat()
                val startFrame = ((clip.timelineStartMs / 1000.0) * sampleRate).roundToLong().coerceAtLeast(0L)
                val durFrames = ((clip.durationMs / 1000.0) * sampleRate).roundToLong().coerceAtLeast(1L)
                val maxHalfFadeMs = clip.durationMs / 2
                val fadeInFrames = ((clip.fadeInMs.coerceIn(0L, maxHalfFadeMs) / 1000.0) * sampleRate).roundToLong()
                val fadeOutFrames = ((clip.fadeOutMs.coerceIn(0L, maxHalfFadeMs) / 1000.0) * sampleRate).roundToLong()

                out.add(
                    NativeMixClip(
                        wavPath = clip.wavFile.absolutePath,
                        timelineStartFrame = startFrame,
                        durationFrames = durFrames,
                        sourceStartSec = clip.sourceInMs / 1000.0,
                        linearGain = linGain,
                        fadeInFrames = fadeInFrames,
                        fadeOutFrames = fadeOutFrames
                    )
                )
            }
        }
        return out to totalFrames
    }
}
