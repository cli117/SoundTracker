package com.soundtracker.app.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import com.soundtracker.app.dsp.NativeAudioEngine
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Low-latency real-time polyphonic pitch-shifting & 1-BPM tempo-stretching Media3 [AudioProcessor].
 *
 * Design highlights:
 * - Powered by native Rubber Band v4.0 R3 Short-Window Real-Time engine ([NativeAudioEngine]).
 * - Stays wired in ExoPlayer's [AudioProcessingPipeline] so toggling preview or sweeping Pitch/BPM
 *   sliders NEVER triggers `AudioSink` flush/reconfigure gaps.
 * - Uses 100% bit-exact zero-CPU PCM pass-through whenever live preview is off or untouched at
 *   neutral `(0 semitones, 1.0x speed)`, with a 64-frame sample-accurate de-click ramp on mode switches.
 * - Tracks piecewise-continuous `(outputFrames, inputFrames, speed)` checkpoints so ExoPlayer's
 *   playhead (`exoPlayer.currentPosition`) and waveform cursor stay accurately synchronized during
 *   arbitrary real-time BPM sweeps.
 */
@OptIn(UnstableApi::class)
class RealtimePitchAudioProcessor : BaseAudioProcessor() {

    private data class SpeedCheckpoint(
        val outFrames: Double,
        val inFrames: Double,
        val speed: Double
    )

    private val handleLock = Any()
    private val checkpointLock = Any()

    private var nativeHandle: Long = 0L
    private var nativeSampleRate: Int = 0
    private var nativeChannels: Int = 0

    @Volatile
    private var previewEnabled: Boolean = false

    @Volatile
    private var targetSemitones: Float = 0f

    @Volatile
    private var targetTimeRatio: Double = 1.0

    @Volatile
    private var targetPreserveTimbre: Boolean = false

    @Volatile
    private var activeSampleRate: Int = 44100

    // Audio-thread state
    private var dspEngaged: Boolean = false
    private var neutralHoldFrames: Long = 0L
    private var hasPrevSample: Boolean = false
    private var prevSampleCh0: Short = 0
    private var prevSampleCh1: Short = 0
    private var pendingDeclickFrames: Int = 0
    private var declickDeltaCh0: Float = 0f
    private var declickDeltaCh1: Float = 0f
    private var declickNeedsInit: Boolean = false

    private var inScratch = ShortArray(8192)
    private var outScratch = ShortArray(32768)
    private var totalOutputFrames: Long = 0L

    private val speedCheckpoints = ArrayList<SpeedCheckpoint>(64)
    private var currentCheckpointSpeed: Double = 1.0

    init {
        resetCheckpoints(initialSpeed = 1.0)
    }

    /**
     * Lock-free parameter update safe to call from the UI thread on every slider drag tick.
     */
    fun updatePreviewConfig(
        enabled: Boolean,
        semitones: Float,
        timeRatio: Double,
        preserveTimbre: Boolean
    ) {
        val clampedSemi = semitones.coerceIn(-24f, 24f)
        val clampedRatio = timeRatio.coerceIn(0.25, 4.0)
        previewEnabled = enabled
        targetSemitones = clampedSemi
        targetTimeRatio = clampedRatio
        targetPreserveTimbre = preserveTimbre

        synchronized(handleLock) {
            if (nativeHandle != 0L) {
                NativeAudioEngine.nativeSetRealtimeShifterParams(
                    nativeHandle,
                    clampedSemi,
                    clampedRatio,
                    preserveTimbre
                )
            }
        }
    }

    /**
     * Maps speaker playout duration since last seek/flush into media track duration,
     * keeping ExoPlayer's playhead cursor aligned across live BPM changes.
     */
    fun getMediaDurationUs(playoutDurationUs: Long): Long {
        val sr = activeSampleRate
        if (playoutDurationUs <= 0L || sr <= 0) {
            return playoutDurationUs.coerceAtLeast(0L)
        }
        val playoutFrames = playoutDurationUs.toDouble() * sr.toDouble() / 1_000_000.0
        synchronized(checkpointLock) {
            if (speedCheckpoints.isEmpty()) return playoutDurationUs
            var lo = 0
            var hi = speedCheckpoints.lastIndex
            var best = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (speedCheckpoints[mid].outFrames <= playoutFrames) {
                    best = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            val cp = speedCheckpoints[best]
            val mediaFrames = cp.inFrames + (playoutFrames - cp.outFrames).coerceAtLeast(0.0) * cp.speed
            return (mediaFrames * 1_000_000.0 / sr.toDouble()).roundToLong().coerceAtLeast(0L)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount !in 1..2 || inputAudioFormat.sampleRate !in 8000..192000) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remainingBytes = inputBuffer.remaining()
        if (remainingBytes <= 0) return

        val sr = inputAudioFormat.sampleRate
        val ch = inputAudioFormat.channelCount
        if (sr <= 0 || ch !in 1..2) {
            val out = replaceOutputBuffer(remainingBytes)
            out.put(inputBuffer)
            out.flip()
            return
        }
        activeSampleRate = sr

        val bytesPerFrame = ch * 2
        val inFrames = remainingBytes / bytesPerFrame
        if (inFrames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val enabled = previewEnabled
        val semi = targetSemitones
        val ratio = targetTimeRatio
        val preserve = targetPreserveTimbre
        val isNeutral = abs(semi) < 0.005f && abs(ratio - 1.0) < 0.0005

        // Decide whether Rubber Band DSP should be engaged for this buffer:
        // - Off immediately if previewEnabled == false
        // - Engage immediately as soon as previewEnabled && !isNeutral
        // - Stay engaged across brief zero-crossings while dragging sliders, returning to
        //   bit-exact pass-through only after ~1.5s of continuous neutral settings.
        val shouldUseDsp = if (!enabled) {
            neutralHoldFrames = 0L
            false
        } else if (!isNeutral) {
            neutralHoldFrames = 0L
            true
        } else if (dspEngaged) {
            neutralHoldFrames += inFrames
            neutralHoldFrames < (sr * 3L / 2L)
        } else {
            false
        }

        if (shouldUseDsp != dspEngaged) {
            dspEngaged = shouldUseDsp
            if (hasPrevSample) {
                pendingDeclickFrames = DECLICK_RAMP_FRAMES
                declickDeltaCh0 = prevSampleCh0.toFloat()
                declickDeltaCh1 = prevSampleCh1.toFloat()
                declickNeedsInit = true
            }
            if (shouldUseDsp) {
                val handle = ensureNativeShifterOnAudioThread(sr, ch)
                NativeAudioEngine.nativeSetRealtimeShifterParams(handle, semi, ratio, preserve)
                NativeAudioEngine.nativeResetRealtimeShifter(handle)
            }
        }

        if (!dspEngaged) {
            // 100% bit-exact PCM pass-through (zero DSP CPU)
            recordSegmentSpeedOnAudioThread(newSpeed = 1.0, outFramesAtChunkStart = totalOutputFrames)
            val totalSamples = inFrames * ch
            if (pendingDeclickFrames > 0) {
                ensureInScratchCapacity(totalSamples)
                inputBuffer.asShortBuffer().get(inScratch, 0, totalSamples)
                inputBuffer.position(inputBuffer.position() + inFrames * bytesPerFrame)
                applyBoundaryDeclick(inScratch, inFrames, ch)
                updatePrevSample(inScratch, inFrames, ch)
                val outBuf = replaceOutputBuffer(inFrames * bytesPerFrame)
                outBuf.asShortBuffer().put(inScratch, 0, totalSamples)
                outBuf.limit(inFrames * bytesPerFrame)
            } else {
                // Fast direct ByteBuffer copy, reading only the last frame for future de-click continuity
                val startPos = inputBuffer.position()
                val copyBytes = inFrames * bytesPerFrame
                val lastFrameBytePos = startPos + (inFrames - 1) * bytesPerFrame
                prevSampleCh0 = inputBuffer.getShort(lastFrameBytePos)
                prevSampleCh1 = if (ch == 2) inputBuffer.getShort(lastFrameBytePos + 2) else prevSampleCh0
                hasPrevSample = true

                val oldLimit = inputBuffer.limit()
                inputBuffer.limit(startPos + copyBytes)
                val outBuf = replaceOutputBuffer(copyBytes)
                outBuf.put(inputBuffer)
                outBuf.flip()
                inputBuffer.limit(oldLimit)
            }
            totalOutputFrames += inFrames
            return
        }

        // Active Rubber Band v4.0 R3 Real-Time DSP path
        val handle = ensureNativeShifterOnAudioThread(sr, ch)
        NativeAudioEngine.nativeSetRealtimeShifterParams(handle, semi, ratio, preserve)

        val effectiveSpeed = (1.0 / ratio).coerceIn(0.25, 4.0)
        recordSegmentSpeedOnAudioThread(newSpeed = effectiveSpeed, outFramesAtChunkStart = totalOutputFrames)

        val inSamples = inFrames * ch
        ensureInScratchCapacity(inSamples)
        inputBuffer.asShortBuffer().get(inScratch, 0, inSamples)
        inputBuffer.position(inputBuffer.position() + inFrames * bytesPerFrame)

        val estMaxOutFrames = max((inFrames * max(ratio, 1.0) * 1.6).toInt() + 4096, 8192)
        ensureOutScratchCapacity(estMaxOutFrames * ch)

        var totalProducedFrames = NativeAudioEngine.nativeProcessRealtimeShifter(
            handle = handle,
            inPcm = inScratch,
            inFrames = inFrames,
            outPcm = outScratch,
            maxOutFrames = estMaxOutFrames
        )

        // Drain any rare overflow frames if extreme slow-down exceeded initial estimate
        if (totalProducedFrames >= estMaxOutFrames) {
            val drainChunkFrames = 4096
            ensureInScratchCapacity(drainChunkFrames * ch)
            while (true) {
                val extra = NativeAudioEngine.nativeDrainRealtimeShifter(
                    handle = handle,
                    outPcm = inScratch,
                    maxOutFrames = drainChunkFrames
                )
                if (extra <= 0) break
                ensureOutScratchCapacity((totalProducedFrames + extra) * ch)
                System.arraycopy(inScratch, 0, outScratch, totalProducedFrames * ch, extra * ch)
                totalProducedFrames += extra
                if (extra < drainChunkFrames) break
            }
        }

        if (totalProducedFrames > 0) {
            applyBoundaryDeclick(outScratch, totalProducedFrames, ch)
            updatePrevSample(outScratch, totalProducedFrames, ch)
            val outBytes = totalProducedFrames * bytesPerFrame
            val outBuf = replaceOutputBuffer(outBytes)
            outBuf.asShortBuffer().put(outScratch, 0, totalProducedFrames * ch)
            outBuf.limit(outBytes)
            totalOutputFrames += totalProducedFrames
        }
    }

    override fun onQueueEndOfStream() {
        if (!dspEngaged || nativeHandle == 0L) return
        val ch = inputAudioFormat.channelCount
        if (ch !in 1..2) return
        val maxTailFrames = 16384
        ensureOutScratchCapacity(maxTailFrames * ch)
        val tailFrames = NativeAudioEngine.nativeFinishRealtimeShifter(
            handle = nativeHandle,
            outPcm = outScratch,
            maxOutFrames = maxTailFrames
        )
        if (tailFrames > 0) {
            updatePrevSample(outScratch, tailFrames, ch)
            val outBytes = tailFrames * ch * 2
            val outBuf = replaceOutputBuffer(outBytes)
            outBuf.asShortBuffer().put(outScratch, 0, tailFrames * ch)
            outBuf.limit(outBytes)
            totalOutputFrames += tailFrames
        }
    }

    override fun onFlush() {
        val sr = inputAudioFormat.sampleRate
        if (sr > 0) {
            activeSampleRate = sr
        }
        neutralHoldFrames = 0L
        hasPrevSample = false
        pendingDeclickFrames = 0
        declickNeedsInit = false

        if (nativeHandle != 0L) {
            NativeAudioEngine.nativeSetRealtimeShifterParams(
                nativeHandle,
                targetSemitones,
                targetTimeRatio,
                targetPreserveTimbre
            )
            NativeAudioEngine.nativeResetRealtimeShifter(nativeHandle)
        }

        val isNeutral = abs(targetSemitones) < 0.005f && abs(targetTimeRatio - 1.0) < 0.0005
        dspEngaged = previewEnabled && !isNeutral
        val initialSpeed = if (dspEngaged) (1.0 / targetTimeRatio).coerceIn(0.25, 4.0) else 1.0
        resetCheckpoints(initialSpeed = initialSpeed)
    }

    override fun onReset() {
        dspEngaged = false
        neutralHoldFrames = 0L
        hasPrevSample = false
        pendingDeclickFrames = 0
        destroyNativeShifterOnAudioThread()
        resetCheckpoints(initialSpeed = 1.0)
    }

    private fun ensureNativeShifterOnAudioThread(sampleRate: Int, channels: Int): Long {
        if (nativeHandle != 0L && nativeSampleRate == sampleRate && nativeChannels == channels) {
            return nativeHandle
        }
        destroyNativeShifterOnAudioThread()
        val created = NativeAudioEngine.nativeCreateRealtimeShifter(sampleRate, channels)
        NativeAudioEngine.nativeSetRealtimeShifterParams(
            created,
            targetSemitones,
            targetTimeRatio,
            targetPreserveTimbre
        )
        synchronized(handleLock) {
            nativeHandle = created
            nativeSampleRate = sampleRate
            nativeChannels = channels
        }
        return created
    }

    private fun destroyNativeShifterOnAudioThread() {
        synchronized(handleLock) {
            if (nativeHandle != 0L) {
                NativeAudioEngine.nativeDestroyRealtimeShifter(nativeHandle)
                nativeHandle = 0L
                nativeSampleRate = 0
                nativeChannels = 0
            }
        }
    }

    private fun resetCheckpoints(initialSpeed: Double) {
        val clamped = initialSpeed.coerceIn(0.25, 4.0)
        synchronized(checkpointLock) {
            totalOutputFrames = 0L
            currentCheckpointSpeed = clamped
            speedCheckpoints.clear()
            speedCheckpoints.add(SpeedCheckpoint(outFrames = 0.0, inFrames = 0.0, speed = clamped))
        }
    }

    private fun recordSegmentSpeedOnAudioThread(newSpeed: Double, outFramesAtChunkStart: Long) {
        val safeSpeed = newSpeed.coerceIn(0.25, 4.0)
        synchronized(checkpointLock) {
            if (speedCheckpoints.isEmpty()) {
                currentCheckpointSpeed = safeSpeed
                speedCheckpoints.add(SpeedCheckpoint(0.0, 0.0, safeSpeed))
                return
            }
            if (abs(safeSpeed - currentCheckpointSpeed) > 1e-4) {
                val last = speedCheckpoints.last()
                val curOut = outFramesAtChunkStart.toDouble().coerceAtLeast(last.outFrames)
                val curIn = last.inFrames + (curOut - last.outFrames) * last.speed
                speedCheckpoints.add(SpeedCheckpoint(curOut, curIn, safeSpeed))
                currentCheckpointSpeed = safeSpeed

                if (speedCheckpoints.size > 4096) {
                    val keepFrom = speedCheckpoints.size - 1024
                    speedCheckpoints.subList(0, keepFrom).clear()
                }
            }
        }
    }

    private fun applyBoundaryDeclick(pcm: ShortArray, frames: Int, channels: Int) {
        if (pendingDeclickFrames <= 0 || frames <= 0) return
        if (declickNeedsInit) {
            val firstCh0 = pcm[0].toFloat()
            val firstCh1 = if (channels == 2) pcm[1].toFloat() else firstCh0
            declickDeltaCh0 -= firstCh0
            declickDeltaCh1 -= firstCh1
            declickNeedsInit = false
        }

        val count = minOf(frames, pendingDeclickFrames)
        val delta0 = declickDeltaCh0
        val delta1 = declickDeltaCh1
        for (i in 0 until count) {
            val weight = pendingDeclickFrames.toFloat() / DECLICK_RAMP_FRAMES.toFloat()
            if (channels == 2) {
                val idx = i * 2
                val s0 = (pcm[idx].toFloat() + delta0 * weight).roundToInt().coerceIn(-32768, 32767)
                val s1 = (pcm[idx + 1].toFloat() + delta1 * weight).roundToInt().coerceIn(-32768, 32767)
                pcm[idx] = s0.toShort()
                pcm[idx + 1] = s1.toShort()
            } else {
                val s0 = (pcm[i].toFloat() + delta0 * weight).roundToInt().coerceIn(-32768, 32767)
                pcm[i] = s0.toShort()
            }
            pendingDeclickFrames--
        }
    }

    private fun updatePrevSample(pcm: ShortArray, frames: Int, channels: Int) {
        if (frames <= 0) return
        if (channels == 2) {
            val base = (frames - 1) * 2
            prevSampleCh0 = pcm[base]
            prevSampleCh1 = pcm[base + 1]
        } else {
            prevSampleCh0 = pcm[frames - 1]
            prevSampleCh1 = prevSampleCh0
        }
        hasPrevSample = true
    }

    private fun ensureInScratchCapacity(minSamples: Int) {
        if (inScratch.size < minSamples) {
            inScratch = ShortArray(max(minSamples, inScratch.size * 2))
        }
    }

    private fun ensureOutScratchCapacity(minSamples: Int) {
        if (outScratch.size < minSamples) {
            outScratch = ShortArray(max(minSamples, outScratch.size * 2))
        }
    }

    companion object {
        private const val DECLICK_RAMP_FRAMES = 64
    }
}

/**
 * Custom [AudioProcessorChain] routing audio through [RealtimePitchAudioProcessor] and delegating
 * media position duration scaling to [RealtimePitchAudioProcessor.getMediaDurationUs] without
 * invoking Android's lower-quality Sonic speech WSOLA processor.
 */
@OptIn(UnstableApi::class)
private class RealtimePitchAudioProcessorChain(
    private val pitchProcessor: RealtimePitchAudioProcessor
) : AudioProcessorChain {

    private val processors: Array<AudioProcessor> = arrayOf(pitchProcessor)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        // Keep ExoPlayer's PlaybackParameters at DEFAULT so DefaultAudioSink never flushes on slider drags
        return PlaybackParameters.DEFAULT
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long {
        return pitchProcessor.getMediaDurationUs(playoutDuration)
    }

    override fun getSkippedOutputFrameCount(): Long = 0L
}

/**
 * Builds an [ExoPlayer] configured with a low-latency (~80–110ms) [DefaultAudioSink] and
 * [RealtimePitchAudioProcessor] so live pitch/BPM slider movements respond nearly instantaneously.
 */
@OptIn(UnstableApi::class)
fun buildRealtimePreviewExoPlayer(
    context: Context,
    pitchProcessor: RealtimePitchAudioProcessor
): ExoPlayer {
    val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            val lowLatencyBufferProvider = DefaultAudioTrackBufferSizeProvider.Builder()
                .setMinPcmBufferDurationUs(60_000)   // 60ms min PCM buffer
                .setMaxPcmBufferDurationUs(110_000)  // 110ms max PCM buffer (vs 750ms default!)
                .setPcmBufferMultiplicationFactor(2)
                .build()

            return DefaultAudioSink.Builder(context)
                .setAudioProcessorChain(RealtimePitchAudioProcessorChain(pitchProcessor))
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(false)
                .setAudioTrackBufferSizeProvider(lowLatencyBufferProvider)
                .build()
        }
    }

    return ExoPlayer.Builder(context, renderersFactory).build()
}
