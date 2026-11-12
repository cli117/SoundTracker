package com.soundtracker.app.melody

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import com.soundtracker.app.dsp.NativeAudioEngine
import com.soundtracker.app.dsp.NativeSynthNote
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Ultra-low-latency streaming player for the GarageBand-style live instrument keyboard
 * and score sequencer. Keeps a dedicated high-priority audio thread active only while
 * the user is on the `MELODY_STUDIO` screen so piano touches respond instantaneously.
 */
class MelodyRealtimePlayer(
    val sampleRate: Int = MELODY_SAMPLE_RATE,
    private val onTransportState: (transportFrame: Long, isPlaying: Boolean) -> Unit
) {
    private var nativeHandle: Long = NativeAudioEngine.nativeCreateMelodySynthEngine(sampleRate)
    private val streamActive = AtomicBoolean(false)
    @Volatile
    private var audioThread: Thread? = null

    private fun ensureHandle(): Long {
        if (nativeHandle == 0L) {
            nativeHandle = NativeAudioEngine.nativeCreateMelodySynthEngine(sampleRate)
        }
        return nativeHandle
    }

    /**
     * Starts the low-latency `AudioTrack` output stream when entering the Melody Studio tab.
     */
    @Synchronized
    fun startAudioEngine() {
        if (streamActive.get()) return
        val handle = ensureHandle()
        if (handle == 0L) return

        streamActive.set(true)
        audioThread = thread(start = true, isDaemon = true, name = "SoundTrackerSynthAudio") {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }

            val channelMask = AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
                .coerceAtLeast(2048)

            // Small 256-frame render blocks (~5.8ms @ 44.1kHz) for instant touch-to-sound keyboard response
            val blockFrames = 256
            val trackBufBytes = minBufBytes.coerceAtLeast(blockFrames * 4 * 2)

            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackBufBytes)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            val audioTrack = runCatching { trackBuilder.build() }.getOrNull() ?: run {
                streamActive.set(false)
                return@thread
            }

            val pcmBuffer = ShortArray(blockFrames * 2)
            var uiTick = 0
            var wasTransportPlaying = false

            try {
                audioTrack.play()
                while (streamActive.get()) {
                    val h = nativeHandle
                    if (h == 0L) break

                    NativeAudioEngine.nativeSynthRenderBlock(h, pcmBuffer, blockFrames)

                    var written = 0
                    val totalShorts = blockFrames * 2
                    while (written < totalShorts && streamActive.get()) {
                        val res = audioTrack.write(
                            pcmBuffer,
                            written,
                            totalShorts - written,
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (res <= 0) break
                        written += res
                    }

                    if (++uiTick >= 4) { // ~43 Hz playhead sync
                        uiTick = 0
                        val packed = NativeAudioEngine.nativeSynthGetTransportState(h)
                        val isPlayingNow = (packed and (1L shl 62)) != 0L
                        val curFrame = packed and ((1L shl 62) - 1L)
                        if (isPlayingNow || wasTransportPlaying) {
                            onTransportState(curFrame, isPlayingNow)
                            wasTransportPlaying = isPlayingNow
                        }
                    }
                }
            } finally {
                runCatching { audioTrack.pause() }
                runCatching { audioTrack.flush() }
                runCatching { audioTrack.release() }
            }
        }
    }

    /**
     * Stops the low-latency `AudioTrack` stream when leaving the Melody Studio tab or backgrounding the app,
     * consuming 0% CPU when idle.
     */
    @Synchronized
    fun stopAudioEngine() {
        val h = nativeHandle
        if (h != 0L) {
            NativeAudioEngine.nativeSynthSetTransportPlaying(h, false, 0L)
            NativeAudioEngine.nativeSynthAllNotesOff(h)
        }
        if (!streamActive.getAndSet(false)) return
        val t = audioThread
        audioThread = null
        if (t != null && t != Thread.currentThread()) {
            runCatching { t.join(250L) }
        }
        onTransportState(0L, false)
    }

    fun applyInstrument(
        slot: Int,
        instrument: StudioInstrument,
        reverbWet: Float,
        sustainPedal: Boolean,
        sustainDurationSec: Float
    ) {
        val h = ensureHandle()
        if (h == 0L) return
        NativeAudioEngine.nativeSynthSetInstrument(
            handle = h,
            slot = slot,
            bank = instrument.bank,
            presetNumber = instrument.presetNumber,
            gainDb = instrument.gainDb,
            reverbSend = reverbWet,
            chorusDepth = instrument.chorusDepth,
            sustainPedal = sustainPedal,
            sustainDurationSec = sustainDurationSec
        )
    }

    fun setReverbWet(reverbWet: Float) {
        val h = nativeHandle
        if (h != 0L) NativeAudioEngine.nativeSynthSetReverb(h, reverbWet)
    }

    fun setSustainPedal(slot: Int, sustainOn: Boolean, sustainDurationSec: Float) {
        val h = nativeHandle
        if (h != 0L) {
            NativeAudioEngine.nativeSynthSetSustain(h, slot, sustainOn, sustainDurationSec)
        }
    }

    fun noteOn(slot: Int, midiNote: Int, velocity: Float = 0.86f) {
        val h = nativeHandle
        if (h != 0L) NativeAudioEngine.nativeSynthLiveNoteOn(h, slot, midiNote, velocity)
    }

    fun noteOff(slot: Int, midiNote: Int) {
        val h = nativeHandle
        if (h != 0L) NativeAudioEngine.nativeSynthLiveNoteOff(h, slot, midiNote)
    }

    fun allNotesOff() {
        val h = nativeHandle
        if (h != 0L) NativeAudioEngine.nativeSynthAllNotesOff(h)
    }

    fun updateSequence(
        notes: List<NativeSynthNote>,
        totalLengthFrames: Long,
        loopEnabled: Boolean
    ) {
        val h = nativeHandle
        if (h != 0L) {
            NativeAudioEngine.updateMelodySynthSequence(h, notes, totalLengthFrames, loopEnabled)
        }
    }

    fun setMetronome(
        enabled: Boolean,
        bpm: Float,
        numerator: Int = 4,
        denominator: Int = 4,
        pulseFrames: LongArray? = null,
        pulseAccents: IntArray? = null
    ) {
        val h = nativeHandle
        if (h != 0L) {
            NativeAudioEngine.nativeSynthSetMetronome(
                h,
                enabled,
                bpm,
                numerator,
                denominator,
                pulseFrames,
                pulseAccents
            )
        }
    }

    fun setTransportPlaying(playing: Boolean, startFrame: Long = 0L) {
        val h = nativeHandle
        if (h != 0L) {
            NativeAudioEngine.nativeSynthSetTransportPlaying(h, playing, startFrame)
        }
    }

    fun seekTransport(frame: Long) {
        val h = nativeHandle
        if (h != 0L) {
            NativeAudioEngine.nativeSynthSeekTransport(h, frame)
        }
    }

    fun currentTransportFrame(): Long {
        val h = nativeHandle
        if (h == 0L) return 0L
        val packed = NativeAudioEngine.nativeSynthGetTransportState(h)
        return packed and ((1L shl 62) - 1L)
    }

    @Synchronized
    fun release() {
        stopAudioEngine()
        val h = nativeHandle
        nativeHandle = 0L
        if (h != 0L) {
            NativeAudioEngine.nativeDestroyMelodySynthEngine(h)
        }
    }
}
