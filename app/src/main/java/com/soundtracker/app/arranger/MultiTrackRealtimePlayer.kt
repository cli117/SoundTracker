package com.soundtracker.app.arranger

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import com.soundtracker.app.dsp.NativeAudioEngine
import com.soundtracker.app.dsp.NativeMixClip
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

class MultiTrackRealtimePlayer(
    private val sampleRate: Int = ARRANGER_SAMPLE_RATE,
    private val onPositionUpdate: (playheadMs: Long, isPlaying: Boolean) -> Unit
) {
    private val engineHandle: Long = NativeAudioEngine.nativeCreateMultiTrackEngine(sampleRate)
    private val isReleased = AtomicBoolean(false)
    private val wantPlaying = AtomicBoolean(false)
    private val pendingSeekFrame = AtomicLong(-1L)
    private val totalDurationFramesRef = AtomicLong(0L)
    private val currentAudibleFrame = AtomicLong(0L)

    @Volatile
    private var workerThread: Thread? = null
    private val stateLock = Any()

    fun updateSchedule(clips: List<NativeMixClip>, totalDurationFrames: Long) {
        if (isReleased.get() || engineHandle == 0L) return
        totalDurationFramesRef.set(totalDurationFrames.coerceAtLeast(0L))
        NativeAudioEngine.updateMultiTrackEngineSchedule(
            handle = engineHandle,
            clips = clips,
            totalDurationFrames = totalDurationFrames
        )
    }

    fun clearReaderCache() {
        if (isReleased.get() || engineHandle == 0L) return
        NativeAudioEngine.nativeClearMultiTrackCache(engineHandle)
    }

    fun seekToMs(ms: Long) {
        if (isReleased.get()) return
        val maxFrames = totalDurationFramesRef.get()
        val rawFrame = ((ms.coerceAtLeast(0L) / 1000.0) * sampleRate).roundToLong()
        val clamped = if (maxFrames > 0L) rawFrame.coerceIn(0L, maxFrames) else rawFrame.coerceAtLeast(0L)
        currentAudibleFrame.set(clamped)
        pendingSeekFrame.set(clamped)
    }

    fun play(startMs: Long) {
        if (isReleased.get() || engineHandle == 0L) return
        val totalFrames = totalDurationFramesRef.get()
        if (totalFrames <= 0L) return

        val startFrameRaw = ((startMs.coerceAtLeast(0L) / 1000.0) * sampleRate).roundToLong()
        val startFrame = if (startFrameRaw >= totalFrames - (sampleRate / 20)) 0L else startFrameRaw
        currentAudibleFrame.set(startFrame)
        pendingSeekFrame.set(startFrame)
        wantPlaying.set(true)

        synchronized(stateLock) {
            if (workerThread?.isAlive != true) {
                val t = Thread(::audioLoop, "SoundTracker-MultiTrackAudio").apply {
                    isDaemon = true
                    start()
                }
                workerThread = t
            }
        }
        onPositionUpdate((startFrame * 1000L) / sampleRate, true)
    }

    fun pause() {
        val wasPlaying = wantPlaying.getAndSet(false)
        if (wasPlaying) {
            val ms = (currentAudibleFrame.get() * 1000L) / sampleRate
            onPositionUpdate(ms, false)
        }
    }

    val isPlaying: Boolean
        get() = wantPlaying.get()

    val currentPositionMs: Long
        get() = (currentAudibleFrame.get() * 1000L) / sampleRate

    fun release() {
        if (!isReleased.compareAndSet(false, true)) return
        wantPlaying.set(false)
        val t = synchronized(stateLock) {
            val cur = workerThread
            workerThread = null
            cur
        }
        runCatching { t?.join(350L) }
        if (engineHandle != 0L) {
            NativeAudioEngine.nativeDestroyMultiTrackEngine(engineHandle)
        }
    }

    private fun buildAudioTrack(bufferBytes: Int): AudioTrack {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    private fun audioLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }

        val blockFrames = 1024
        val blockBytes = blockFrames * 4 // stereo 16-bit
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(blockBytes * 2)
        val trackBufBytes = (minBuf * 2).coerceAtLeast(blockBytes * 4)

        val audioTrack = runCatching { buildAudioTrack(trackBufBytes) }.getOrNull() ?: run {
            wantPlaying.set(false)
            onPositionUpdate(currentPositionMs, false)
            return
        }

        val pcmBuf = ShortArray(blockFrames * 2)
        var renderCursorFrame = currentAudibleFrame.get()
        var baseSeekFrame = renderCursorFrame
        var baseHeadPos = 0L
        var writtenFramesSinceFlush = 0L
        var lastUiNotifyNs = 0L

        try {
            audioTrack.play()
            baseHeadPos = audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL

            while (!isReleased.get() && wantPlaying.get()) {
                val seekReq = pendingSeekFrame.getAndSet(-1L)
                if (seekReq >= 0L) {
                    runCatching {
                        audioTrack.pause()
                        audioTrack.flush()
                        NativeAudioEngine.nativeResetMultiTrackEnvelope(engineHandle)
                        audioTrack.play()
                    }
                    renderCursorFrame = seekReq
                    baseSeekFrame = seekReq
                    writtenFramesSinceFlush = 0L
                    baseHeadPos = audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    currentAudibleFrame.set(seekReq)
                }

                val totalFrames = totalDurationFramesRef.get()
                if (totalFrames <= 0L) {
                    wantPlaying.set(false)
                    currentAudibleFrame.set(0L)
                    onPositionUpdate(0L, false)
                    break
                }

                if (renderCursorFrame < totalFrames) {
                    val framesToRender = minOf(blockFrames.toLong(), totalFrames - renderCursorFrame).toInt()
                    NativeAudioEngine.nativeRenderMultiTrackPreviewBlock(
                        engineHandle,
                        renderCursorFrame,
                        pcmBuf,
                        framesToRender
                    )
                    val shortsToWrite = framesToRender * 2
                    var offset = 0
                    while (offset < shortsToWrite && wantPlaying.get() && !isReleased.get()) {
                        if (pendingSeekFrame.get() >= 0L) break
                        val written = audioTrack.write(
                            pcmBuf,
                            offset,
                            shortsToWrite - offset,
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (written <= 0) break
                        offset += written
                    }
                    val actualFramesWritten = (offset / 2).toLong()
                    renderCursorFrame += actualFramesWritten
                    writtenFramesSinceFlush += actualFramesWritten
                } else {
                    // Wait smoothly for AudioTrack hardware ring buffer to finish playing remaining samples
                    Thread.sleep(12L)
                }

                val rawHead = audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val playedSinceFlush = (rawHead - baseHeadPos).coerceIn(0L, writtenFramesSinceFlush)
                val audible = (baseSeekFrame + playedSinceFlush).coerceAtMost(totalFrames)
                currentAudibleFrame.set(audible)

                val nowNs = System.nanoTime()
                if (nowNs - lastUiNotifyNs >= 25_000_000L) { // ~40 fps smooth playhead updates
                    lastUiNotifyNs = nowNs
                    onPositionUpdate((audible * 1000L) / sampleRate, true)
                }

                if (renderCursorFrame >= totalFrames && playedSinceFlush >= writtenFramesSinceFlush - 64L) {
                    // Reached end of multi-track arrangement
                    wantPlaying.set(false)
                    currentAudibleFrame.set(0L)
                    onPositionUpdate(0L, false)
                    break
                }
            }
        } catch (_: Throwable) {
            wantPlaying.set(false)
            onPositionUpdate(currentPositionMs, false)
        } finally {
            runCatching {
                audioTrack.pause()
                audioTrack.flush()
                audioTrack.release()
            }
        }
    }
}
