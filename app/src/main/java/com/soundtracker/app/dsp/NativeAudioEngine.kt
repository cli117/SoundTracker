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
    val waveformPeaks: List<Float>
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

fun interface NativeProgressListener {
    fun onProgress(progress: Float)
}

object NativeAudioEngine {

    private const val INST_HQ5_ASSET_PATH = "models/inst_hq5_fp16weights.onnx"
    private const val HTDEMUCS_FT_ASSET_PATH = "models/htdemucs_ft_vocals_fp16weights.onnx"

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
            if (raw.size < 9) return@withContext null
            val peaks = if (raw.size > 9) {
                raw.slice(9 until raw.size)
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
                waveformPeaks = peaks
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
}
