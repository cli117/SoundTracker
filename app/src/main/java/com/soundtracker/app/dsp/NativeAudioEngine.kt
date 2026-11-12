package com.soundtracker.app.dsp

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class VocalEngineMode(
    val nativeOrdinal: Int,
    val title: String,
    val badge: String,
    val subtitle: String
) {
    INST_HQ5_DIRECT(
        nativeOrdinal = 0,
        title = "人声去除优先",
        badge = "默认推荐",
        subtitle = "适合大多数流行歌曲。主唱、和声与呼吸声去除最彻底，处理速度较快。"
    ),
    HTDEMUCS_FT(
        nativeOrdinal = 1,
        title = "乐器音质优先",
        badge = "适合鼓点/乐队伴奏",
        subtitle = "适合摇滚、乐队或鼓点贝斯较重的歌曲。鼓点和低音乐器更饱满，但可能保留少量和声。"
    ),
    HYBRID_ENSEMBLE(
        nativeOrdinal = 2,
        title = "双模型均衡模式",
        badge = "耗时约 2 倍",
        subtitle = "同时运行以上两种模型并结合结果：低音保留鼓点饱满度，中高音加强人声去除，耗时更长。"
    )
}

enum class ExportFormat(
    val label: String,
    val badge: String,
    val extension: String,
    val mimeType: String
) {
    MP3_320(
        label = "MP3 (320kbps)",
        badge = "推荐分享微信 · 兼容性最好",
        extension = "mp3",
        mimeType = "audio/mpeg"
    ),
    M4A_256(
        label = "M4A (256kbps AAC)",
        badge = "文件体积更小",
        extension = "m4a",
        mimeType = "audio/mp4"
    ),
    WAV_16(
        label = "WAV 无损格式 (16-bit)",
        badge = "无损不压缩 · 文件较大",
        extension = "wav",
        mimeType = "audio/x-wav"
    )
}

data class AudioMetrics(
    val integratedLufs: Float,
    val truePeakDbtp: Float,
    val samplePeakDbfs: Float,
    val rmsDbfs: Float,
    val pureLosslessHeadroomDb: Float,
    val durationSec: Float,
    val sampleRate: Int,
    val channels: Int,
    val detectedBpm: Float,
    val waveformPeaks: List<Float>,
    val detectedKeyFifths: Int = 0,
    val detectedKeyMinor: Boolean = false,
    val detectedKeyConfidence: Float = 0f
)

data class BoostReport(
    val wasPureLossless: Boolean,
    val postLufs: Float,
    val postTruePeakDbtp: Float,
    val maxGainReductionDb: Float
)

sealed interface VocalSeparationOutcome {
    data object Success : VocalSeparationOutcome
    data object Cancelled : VocalSeparationOutcome
    data class Error(val message: String) : VocalSeparationOutcome
}

data class NativeMixClip(
    val wavPath: String,
    val timelineStartFrame: Long,
    val durationFrames: Long,
    val sourceStartSec: Double,
    val linearGain: Float,
    val fadeInFrames: Long,
    val fadeOutFrames: Long
)

data class NativeSynthNote(
    val startFrame: Long,
    val durationFrames: Long,
    val midiNote: Int,
    val velocity: Float,
    val slot: Int = 0,
    val flags: Int = 0
)

fun interface NativeProgressListener {
    fun onProgress(progress: Float)
}

object NativeAudioEngine {

    private const val INST_HQ5_ASSET_PATH = "models/inst_hq5_fp16weights.onnx"
    private const val HTDEMUCS_FT_ASSET_PATH = "models/htdemucs_ft_vocals_fp16weights.onnx"
    const val STUDIO_SOUNDFONT_ASSET_PATH = "soundfonts/studio_instruments.sf2"

    init {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("soundtracker_dsp")
    }

    external fun nativeResetCancel()
    external fun nativeRequestCancel()

    private external fun nativeAnalyzeWav(wavPath: String, waveformBins: Int): FloatArray?

    private external fun nativeSeparateVocals(
        assetManager: AssetManager,
        instModelAssetPath: String,
        htdemucsModelAssetPath: String,
        modeOrdinal: Int,
        enableDenoise: Boolean,
        inWavPath: String,
        outAccWavPath: String,
        outVocWavPath: String,
        numThreads: Int,
        progressListener: NativeProgressListener?
    ): String?

    private external fun nativePitchShift(
        inWavPath: String,
        outWavPath: String,
        semitones: Float,
        timeRatio: Double,
        preserveTimbre: Boolean,
        progressListener: NativeProgressListener?
    ): Boolean

    private external fun nativeBoostVolume(
        inWavPath: String,
        outWavPath: String,
        gainDb: Float,
        ceilingDbtp: Float,
        progressListener: NativeProgressListener?
    ): FloatArray?

    private external fun nativeEncodeMp3(
        inWavPath: String,
        outMp3Path: String,
        bitrateKbps: Int,
        title: String,
        progressListener: NativeProgressListener?
    ): Boolean

    external fun nativeConvertToStandardWav(inWavPath: String, outWavPath: String): Boolean

    external fun nativeCreateRealtimeShifter(sampleRate: Int, channels: Int): Long
    external fun nativeDestroyRealtimeShifter(handle: Long)
    external fun nativeResetRealtimeShifter(handle: Long)
    external fun nativeSetRealtimeShifterParams(
        handle: Long,
        semitones: Float,
        timeRatio: Double,
        preserveTimbre: Boolean
    )
    external fun nativeProcessRealtimeShifter(
        handle: Long,
        inPcm: ShortArray?,
        inFrames: Int,
        outPcm: ShortArray,
        maxOutFrames: Int
    ): Int
    external fun nativeDrainRealtimeShifter(
        handle: Long,
        outPcm: ShortArray,
        maxOutFrames: Int
    ): Int
    external fun nativeFinishRealtimeShifter(
        handle: Long,
        outPcm: ShortArray,
        maxOutFrames: Int
    ): Int

    external fun nativeCreateRealtimeMaximizer(sampleRate: Int, channels: Int): Long
    external fun nativeDestroyRealtimeMaximizer(handle: Long)
    external fun nativeResetRealtimeMaximizer(handle: Long)
    external fun nativeSetRealtimeMaximizerGain(handle: Long, gainDb: Float)
    external fun nativeProcessRealtimeMaximizer(handle: Long, pcm: ShortArray, frames: Int)

    // Multi-Track Arranger / Mashup Engine (1..10 tracks)
    external fun nativeCreateMultiTrackEngine(outputSampleRate: Int): Long
    external fun nativeDestroyMultiTrackEngine(handle: Long)
    external fun nativeClearMultiTrackCache(handle: Long)
    external fun nativeResetMultiTrackEnvelope(handle: Long)
    external fun nativeSetMultiTrackSchedule(
        handle: Long,
        wavPaths: Array<String>,
        timelineStarts: LongArray,
        durations: LongArray,
        sourceStartSecs: DoubleArray,
        linearGains: FloatArray,
        fadeIns: LongArray,
        fadeOuts: LongArray,
        totalDurationFrames: Long
    )
    external fun nativeRenderMultiTrackPreviewBlock(
        handle: Long,
        timelineStartFrame: Long,
        outStereoPcm: ShortArray,
        numFrames: Int
    )
    private external fun nativeExtractFastWavPeaks(wavPath: String, numBins: Int): FloatArray?
    private external fun nativeRenderMultiTrackMixdown(
        wavPaths: Array<String>,
        timelineStarts: LongArray,
        durations: LongArray,
        sourceStartSecs: DoubleArray,
        linearGains: FloatArray,
        fadeIns: LongArray,
        fadeOuts: LongArray,
        totalDurationFrames: Long,
        outputSampleRate: Int,
        outWavPath: String,
        progressListener: NativeProgressListener?
    ): String?

    // Melody Synthesizer & GarageBand-Style Keyboard Studio (SoundFont2 + Stereo Reverb/Chorus)
    external fun nativeEnsureSoundFontLoaded(
        assetManager: AssetManager,
        assetPath: String
    ): Boolean
    external fun nativeCreateMelodySynthEngine(sampleRate: Int): Long
    external fun nativeDestroyMelodySynthEngine(handle: Long)
    external fun nativeSynthSetInstrument(
        handle: Long,
        slot: Int,
        bank: Int,
        presetNumber: Int,
        gainDb: Float,
        reverbSend: Float,
        chorusDepth: Float,
        sustainPedal: Boolean,
        sustainDurationSec: Float
    )
    external fun nativeSynthSetReverb(handle: Long, reverbWet: Float)
    external fun nativeSynthSetSustain(
        handle: Long,
        slot: Int,
        sustainOn: Boolean,
        sustainDurationSec: Float
    )
    external fun nativeSynthLiveNoteOn(handle: Long, slot: Int, midiNote: Int, velocity: Float)
    external fun nativeSynthLiveNoteOff(handle: Long, slot: Int, midiNote: Int)
    external fun nativeSynthAllNotesOff(handle: Long)
    external fun nativeSynthSetSequence(
        handle: Long,
        starts: LongArray,
        durations: LongArray,
        midiNotes: IntArray,
        velocities: FloatArray,
        slots: IntArray,
        flags: IntArray,
        totalLengthFrames: Long,
        loopEnabled: Boolean
    )
    external fun nativeSynthSetMetronome(
        handle: Long,
        enabled: Boolean,
        bpm: Float,
        numerator: Int,
        denominator: Int,
        pulseFrames: LongArray?,
        pulseAccents: IntArray?
    )
    external fun nativeSynthSetTransportPlaying(handle: Long, playing: Boolean, startFrame: Long)
    external fun nativeSynthSeekTransport(handle: Long, frame: Long)
    external fun nativeSynthGetTransportState(handle: Long): Long
    external fun nativeSynthRenderBlock(handle: Long, outStereoPcm: ShortArray, numFrames: Int)
    private external fun nativeRenderMelodyToWav(
        bank0: Int,
        preset0: Int,
        gainDb0: Float,
        reverbSend: Float,
        chorus0: Float,
        sustain0: Boolean,
        sustainSec0: Float,
        bank1: Int,
        preset1: Int,
        gainDb1: Float,
        chorus1: Float,
        sustain1: Boolean,
        sustainSec1: Float,
        starts: LongArray,
        durations: LongArray,
        midiNotes: IntArray,
        velocities: FloatArray,
        slots: IntArray,
        flags: IntArray,
        sequenceDurationFrames: Long,
        outputSampleRate: Int,
        outWavPath: String,
        progressListener: NativeProgressListener?
    ): String?

    /**
     * Reclaims any legacy extracted `.onnx` files from `noBackupFilesDir/engine_models/`.
     * Models are now zero-copy mmap'd directly from APK `assets/models/` via `AAssetManager`,
     * consuming 0 MB of duplicate internal app storage.
     */
    suspend fun cleanupLegacyModelCache(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val legacyDir = File(context.noBackupFilesDir, "engine_models")
            if (legacyDir.exists()) {
                legacyDir.deleteRecursively()
            }
        }
    }

    suspend fun analyzeWav(wavFile: File, waveformBins: Int = 180): AudioMetrics? =
        withContext(Dispatchers.Default) {
            val raw = nativeAnalyzeWav(wavFile.absolutePath, waveformBins) ?: return@withContext null
            if (raw.size < 12) return@withContext null
            val peaks = if (raw.size > 12) {
                raw.slice(12 until raw.size)
            } else {
                emptyList()
            }
            AudioMetrics(
                integratedLufs = raw[0],
                truePeakDbtp = raw[1],
                samplePeakDbfs = raw[2],
                rmsDbfs = raw[3],
                pureLosslessHeadroomDb = raw[4],
                durationSec = raw[5],
                sampleRate = raw[6].toInt(),
                channels = raw[7].toInt(),
                detectedBpm = raw[8].coerceIn(40f, 240f),
                waveformPeaks = peaks,
                detectedKeyFifths = raw[9].toInt().coerceIn(-7, 7),
                detectedKeyMinor = raw[10] >= 0.5f,
                detectedKeyConfidence = raw[11].coerceIn(0f, 1f)
            )
        }

    suspend fun separateVocals(
        context: Context,
        mode: VocalEngineMode,
        enableDeepDenoise: Boolean,
        inputWav: File,
        outAccompanimentWav: File,
        outVocalsWav: File?,
        onProgress: (Float) -> Unit
    ): VocalSeparationOutcome = withContext(Dispatchers.Default) {
        val cores = Runtime.getRuntime().availableProcessors()
        val optimalThreads = (cores / 2 + 1).coerceIn(2, 6)
        val err = nativeSeparateVocals(
            assetManager = context.assets,
            instModelAssetPath = INST_HQ5_ASSET_PATH,
            htdemucsModelAssetPath = HTDEMUCS_FT_ASSET_PATH,
            modeOrdinal = mode.nativeOrdinal,
            enableDenoise = enableDeepDenoise,
            inWavPath = inputWav.absolutePath,
            outAccWavPath = outAccompanimentWav.absolutePath,
            outVocWavPath = outVocalsWav?.absolutePath.orEmpty(),
            numThreads = optimalThreads,
            progressListener = { p -> onProgress(p.coerceIn(0f, 1f)) }
        )
        when {
            err == null -> VocalSeparationOutcome.Success
            err == "Cancelled" -> VocalSeparationOutcome.Cancelled
            else -> VocalSeparationOutcome.Error(err)
        }
    }

    suspend fun pitchShift(
        inputWav: File,
        outputWav: File,
        semitones: Float,
        timeRatio: Double = 1.0,
        preserveTimbre: Boolean,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.Default) {
        nativePitchShift(
            inWavPath = inputWav.absolutePath,
            outWavPath = outputWav.absolutePath,
            semitones = semitones,
            timeRatio = timeRatio,
            preserveTimbre = preserveTimbre,
            progressListener = { p -> onProgress(p.coerceIn(0f, 1f)) }
        )
    }

    suspend fun boostVolume(
        inputWav: File,
        outputWav: File,
        gainDb: Float,
        ceilingDbtp: Float = -0.2f,
        onProgress: (Float) -> Unit
    ): BoostReport? = withContext(Dispatchers.Default) {
        val arr = nativeBoostVolume(
            inWavPath = inputWav.absolutePath,
            outWavPath = outputWav.absolutePath,
            gainDb = gainDb,
            ceilingDbtp = ceilingDbtp,
            progressListener = { p -> onProgress(p.coerceIn(0f, 1f)) }
        ) ?: return@withContext null
        if (arr.size < 4) return@withContext null
        BoostReport(
            wasPureLossless = arr[0] > 0.5f,
            postLufs = arr[1],
            postTruePeakDbtp = arr[2],
            maxGainReductionDb = arr[3]
        )
    }

    suspend fun encodeMp3(
        inputWav: File,
        outputMp3: File,
        bitrateKbps: Int = 320,
        title: String = "",
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.Default) {
        nativeEncodeMp3(
            inWavPath = inputWav.absolutePath,
            outMp3Path = outputMp3.absolutePath,
            bitrateKbps = bitrateKbps,
            title = title,
            progressListener = if (onProgress != null) {
                NativeProgressListener { p -> onProgress(p.coerceIn(0f, 1f)) }
            } else {
                null
            }
        )
    }

    suspend fun extractFastWavPeaks(wavFile: File, numBins: Int = 480): List<Float>? =
        withContext(Dispatchers.IO) {
            nativeExtractFastWavPeaks(wavFile.absolutePath, numBins)?.toList()
        }

    fun updateMultiTrackEngineSchedule(
        handle: Long,
        clips: List<NativeMixClip>,
        totalDurationFrames: Long
    ) {
        if (handle == 0L) return
        val n = clips.size
        val paths = Array(n) { clips[it].wavPath }
        val starts = LongArray(n) { clips[it].timelineStartFrame }
        val durs = LongArray(n) { clips[it].durationFrames }
        val srcSecs = DoubleArray(n) { clips[it].sourceStartSec }
        val gains = FloatArray(n) { clips[it].linearGain }
        val fadeIns = LongArray(n) { clips[it].fadeInFrames }
        val fadeOuts = LongArray(n) { clips[it].fadeOutFrames }
        nativeSetMultiTrackSchedule(
            handle = handle,
            wavPaths = paths,
            timelineStarts = starts,
            durations = durs,
            sourceStartSecs = srcSecs,
            linearGains = gains,
            fadeIns = fadeIns,
            fadeOuts = fadeOuts,
            totalDurationFrames = totalDurationFrames
        )
    }

    suspend fun renderMultiTrackMixdown(
        clips: List<NativeMixClip>,
        totalDurationFrames: Long,
        outputSampleRate: Int = 44100,
        outputWav: File,
        onProgress: (Float) -> Unit
    ): VocalSeparationOutcome = withContext(Dispatchers.Default) {
        val n = clips.size
        val paths = Array(n) { clips[it].wavPath }
        val starts = LongArray(n) { clips[it].timelineStartFrame }
        val durs = LongArray(n) { clips[it].durationFrames }
        val srcSecs = DoubleArray(n) { clips[it].sourceStartSec }
        val gains = FloatArray(n) { clips[it].linearGain }
        val fadeIns = LongArray(n) { clips[it].fadeInFrames }
        val fadeOuts = LongArray(n) { clips[it].fadeOutFrames }
        val err = nativeRenderMultiTrackMixdown(
            wavPaths = paths,
            timelineStarts = starts,
            durations = durs,
            sourceStartSecs = srcSecs,
            linearGains = gains,
            fadeIns = fadeIns,
            fadeOuts = fadeOuts,
            totalDurationFrames = totalDurationFrames,
            outputSampleRate = outputSampleRate,
            outWavPath = outputWav.absolutePath,
            progressListener = { p -> onProgress(p.coerceIn(0f, 1f)) }
        )
        when {
            err == null -> VocalSeparationOutcome.Success
            err == "Cancelled" -> VocalSeparationOutcome.Cancelled
            else -> VocalSeparationOutcome.Error(err)
        }
    }

    suspend fun ensureStudioSoundFontLoaded(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            nativeEnsureSoundFontLoaded(context.assets, STUDIO_SOUNDFONT_ASSET_PATH)
        }

    fun updateMelodySynthSequence(
        handle: Long,
        notes: List<NativeSynthNote>,
        totalLengthFrames: Long,
        loopEnabled: Boolean
    ) {
        if (handle == 0L) return
        val n = notes.size
        val starts = LongArray(n) { notes[it].startFrame }
        val durs = LongArray(n) { notes[it].durationFrames }
        val keys = IntArray(n) { notes[it].midiNote }
        val vels = FloatArray(n) { notes[it].velocity }
        val slots = IntArray(n) { notes[it].slot }
        val flags = IntArray(n) { notes[it].flags }
        nativeSynthSetSequence(
            handle = handle,
            starts = starts,
            durations = durs,
            midiNotes = keys,
            velocities = vels,
            slots = slots,
            flags = flags,
            totalLengthFrames = totalLengthFrames,
            loopEnabled = loopEnabled
        )
    }

    suspend fun renderMelodyToWav(
        context: Context,
        bank0: Int,
        preset0: Int,
        gainDb0: Float,
        reverbSend: Float,
        chorus0: Float,
        sustain0: Boolean,
        sustainSec0: Float,
        bank1: Int,
        preset1: Int,
        gainDb1: Float,
        chorus1: Float,
        sustain1: Boolean,
        sustainSec1: Float,
        notes: List<NativeSynthNote>,
        sequenceDurationFrames: Long,
        outputSampleRate: Int = 44100,
        outputWav: File,
        onProgress: (Float) -> Unit
    ): VocalSeparationOutcome = withContext(Dispatchers.Default) {
        if (!nativeEnsureSoundFontLoaded(context.assets, STUDIO_SOUNDFONT_ASSET_PATH)) {
            return@withContext VocalSeparationOutcome.Error("乐器采样音源加载失败")
        }
        val n = notes.size
        val starts = LongArray(n) { notes[it].startFrame }
        val durs = LongArray(n) { notes[it].durationFrames }
        val keys = IntArray(n) { notes[it].midiNote }
        val vels = FloatArray(n) { notes[it].velocity }
        val slots = IntArray(n) { notes[it].slot }
        val flags = IntArray(n) { notes[it].flags }
        val err = nativeRenderMelodyToWav(
            bank0 = bank0,
            preset0 = preset0,
            gainDb0 = gainDb0,
            reverbSend = reverbSend,
            chorus0 = chorus0,
            sustain0 = sustain0,
            sustainSec0 = sustainSec0,
            bank1 = bank1,
            preset1 = preset1,
            gainDb1 = gainDb1,
            chorus1 = chorus1,
            sustain1 = sustain1,
            sustainSec1 = sustainSec1,
            starts = starts,
            durations = durs,
            midiNotes = keys,
            velocities = vels,
            slots = slots,
            flags = flags,
            sequenceDurationFrames = sequenceDurationFrames,
            outputSampleRate = outputSampleRate,
            outWavPath = outputWav.absolutePath,
            progressListener = { p -> onProgress(p.coerceIn(0f, 1f)) }
        )
        when {
            err == null -> VocalSeparationOutcome.Success
            err == "Cancelled" -> VocalSeparationOutcome.Cancelled
            else -> VocalSeparationOutcome.Error(err)
        }
    }
}
