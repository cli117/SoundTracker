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
        title = "全频直出超净引擎 · Inst-HQ5",
        badge = "默认推荐 · 极净去人声",
        subtitle = "22.05kHz 全奈奎斯特频段直接合成纯伴奏（非相位相减），连气声、齿音与混响尾音深度剥离，告别过电声"
    ),
    HTDEMUCS_FT(
        nativeOrdinal = 1,
        title = "Meta 混合时频母带 · HTDemucs-FT v4",
        badge = "原声瞬态 · 零频谱断层",
        subtitle = "Meta AI 时域波形 + 复数频谱双分支混合 Transformer 专精模型，鼓点、贝斯与原声乐器相位 100% 饱满自然"
    ),
    HYBRID_ENSEMBLE(
        nativeOrdinal = 2,
        title = "双擎交叉融合 · Min-Spec 终极母带",
        badge = "录音棚双模融合",
        subtitle = "低频同相保留 HTDemucs-FT 饱满鼓贝斯瞬态，中高频执行 UVR Min-Spec 最小频谱交叉抑制，双重清除残余和声"
    )
}

enum class ExportFormat(
    val label: String,
    val badge: String,
    val extension: String,
    val mimeType: String
) {
    MP3_320(
        label = "MP3 320kbps 极高音质",
        badge = "推荐微信分享 · 通用性强",
        extension = "mp3",
        mimeType = "audio/mpeg"
    ),
    M4A_256(
        label = "M4A 256kbps 超清AAC",
        badge = "体积更小 · 听感通透",
        extension = "m4a",
        mimeType = "audio/mp4"
    ),
    WAV_16(
        label = "WAV 无损母带 (16-bit)",
        badge = "零压缩无损 · 体积较大",
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
