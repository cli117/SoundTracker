package com.soundtracker.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import com.soundtracker.app.dsp.NativeAudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioCodecHelper {

    fun resolveDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val raw = cursor.getString(idx)
                            if (!raw.isNullOrBlank()) {
                                return stripExtension(raw)
                            }
                        }
                    }
                }
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名音轨"
        return stripExtension(fallback).ifBlank { "未命名音轨" }
    }

    fun stripExtension(fileName: String): String {
        val trimmed = fileName.trim()
        val dot = trimmed.lastIndexOf('.')
        return if (dot > 0) trimmed.substring(0, dot) else trimmed
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
        return cleaned.ifBlank { "SoundTracker_音频" }.take(96)
    }

    /**
     * Decodes any Android-supported audio/video file (MP3, M4A, AAC, FLAC, WAV, OGG, OPUS, MP4)
     * into a clean stereo 16-bit 44.1kHz/48kHz WAV file inside [targetWav].
     */
    suspend fun decodeUriToWav(
        context: Context,
        uri: Uri,
        tempDir: File,
        targetWav: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        tempDir.mkdirs()

        // Fast path: check if input starts with RIFF....WAVE
        val rawCopyTmp = File(tempDir, "sniff_${System.nanoTime()}.wav")
        try {
            val isRiffWave = context.contentResolver.openInputStream(uri)?.use { ins ->
                val hdr = ByteArray(12)
                val n = ins.read(hdr)
                n == 12 &&
                    hdr[0] == 'R'.code.toByte() && hdr[1] == 'I'.code.toByte() &&
                    hdr[2] == 'F'.code.toByte() && hdr[3] == 'F'.code.toByte() &&
                    hdr[8] == 'W'.code.toByte() && hdr[9] == 'A'.code.toByte() &&
                    hdr[10] == 'V'.code.toByte() && hdr[11] == 'E'.code.toByte()
            } ?: false

            if (isRiffWave) {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    rawCopyTmp.outputStream().buffered(256 * 1024).use { outs ->
                        ins.copyTo(outs, 256 * 1024)
                    }
                }
                onProgress(0.6f)
                if (NativeAudioEngine.nativeConvertToStandardWav(rawCopyTmp.absolutePath, targetWav.absolutePath)) {
                    onProgress(1.0f)
                    return@withContext true
                }
            }
        } finally {
            if (rawCopyTmp.exists()) rawCopyTmp.delete()
        }

        // Universal hardware/platform MediaExtractor + MediaCodec decode path
        val decodedRawWav = File(tempDir, "decoded_raw_${System.nanoTime()}.wav")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return@withContext false
            pfd.use {
                extractor.setDataSource(it.fileDescriptor)
            }

            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }

            if (audioTrackIndex < 0 || trackFormat == null) {
                return@withContext false
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext false
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            } else {
                0L
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            var outSampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            var outChannels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            RandomAccessFile(decodedRawWav, "rw").use { raf ->
                raf.setLength(0L)
                // Reserve 44-byte WAV header
                raf.write(ByteArray(44))
                var totalPcmBytesWritten = 0L

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var chunkScratch = ByteArray(65536)

                while (!outputDone) {
                    ensureActive()

                    if (!inputDone) {
                        val inBufIndex = codec.dequeueInputBuffer(10_000L)
                        if (inBufIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inBufIndex)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val ptsUs = extractor.sampleTime
                                codec.queueInputBuffer(inBufIndex, 0, sampleSize, ptsUs, 0)
                                extractor.advance()
                                if (durationUs > 0 && ptsUs > 0) {
                                    onProgress((ptsUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 0.88f))
                                }
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFmt = codec.outputFormat
                            if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                outSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                outChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            if (newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }

                        outIndex >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)

                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    // Convert float32 samples to 16-bit LE PCM on the fly
                                    val floatCount = bufferInfo.size / 4
                                    val needed = floatCount * 2
                                    if (chunkScratch.size < needed) {
                                        chunkScratch = ByteArray(needed)
                                    }
                                    outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                    val fb = outBuf.asFloatBuffer()
                                    var bytePos = 0
                                    for (i in 0 until floatCount) {
                                        val f = fb.get(i).coerceIn(-1f, 1f)
                                        val s = (f * 32767f).toInt().coerceIn(-32768, 32767)
                                        chunkScratch[bytePos++] = (s and 0xFF).toByte()
                                        chunkScratch[bytePos++] = ((s shr 8) and 0xFF).toByte()
                                    }
                                    raf.write(chunkScratch, 0, needed)
                                    totalPcmBytesWritten += needed
                                } else {
                                    if (chunkScratch.size < bufferInfo.size) {
                                        chunkScratch = ByteArray(bufferInfo.size)
                                    }
                                    outBuf.get(chunkScratch, 0, bufferInfo.size)
                                    raf.write(chunkScratch, 0, bufferInfo.size)
                                    totalPcmBytesWritten += bufferInfo.size
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }

                if (totalPcmBytesWritten <= 0L) {
                    return@withContext false
                }

                // Finalize RIFF WAVE header
                raf.seek(0L)
                raf.write(
                    buildWavHeader16Bit(
                        dataBytes = totalPcmBytesWritten,
                        sampleRate = outSampleRate,
                        channels = outChannels.coerceAtLeast(1)
                    )
                )
            }

            onProgress(0.92f)
            // Normalize to standard stereo 44.1kHz/48kHz WAV via Kaiser-windowed sinc resampler
            val ok = NativeAudioEngine.nativeConvertToStandardWav(
                decodedRawWav.absolutePath,
                targetWav.absolutePath
            )
            onProgress(1.0f)
            ok
        } catch (_: Exception) {
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            if (decodedRawWav.exists()) decodedRawWav.delete()
        }
    }

    /**
     * Hardware-accelerated M4A (AAC-LC 256kbps) encoder from a 16-bit WAV file.
     */
    suspend fun encodeWavToM4a(
        inputWav: File,
        outputM4a: File,
        bitrateBps: Int = 256_000,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!inputWav.exists() || inputWav.length() <= 44L) return@withContext false

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            FileInputStream(inputWav).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) != 44) return@withContext false
                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val channels = bb.getShort(22).toInt().coerceIn(1, 2)
                val sampleRate = bb.getInt(24)
                val bitsPerSample = bb.getShort(34).toInt()
                if (bitsPerSample != 16 || sampleRate <= 0) return@withContext false

                val totalDataBytes = (inputWav.length() - 44L).coerceAtLeast(1L)
                val bytesPerFrame = channels * 2

                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    sampleRate,
                    channels
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
                }

                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec!!.start()

                if (outputM4a.exists()) outputM4a.delete()
                muxer = MediaMuxer(outputM4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val readBuf = ByteArray(16384)
                val bufferInfo = MediaCodec.BufferInfo()
                var muxTrackIndex = -1
                var inputDone = false
                var outputDone = false
                var totalFramesSubmitted = 0L
                var totalBytesRead = 0L

                while (!outputDone) {
                    ensureActive()

                    if (!inputDone) {
                        val inIndex = codec!!.dequeueInputBuffer(10_000L)
                        if (inIndex >= 0) {
                            val inBuf = codec!!.getInputBuffer(inIndex)!!
                            inBuf.clear()
                            val maxRead = minOf(readBuf.size, inBuf.remaining()) - (minOf(readBuf.size, inBuf.remaining()) % bytesPerFrame)
                            val got = fis.read(readBuf, 0, maxRead.coerceAtLeast(bytesPerFrame))
                            if (got <= 0) {
                                val ptsUs = (totalFramesSubmitted * 1_000_000L) / sampleRate
                                codec!!.queueInputBuffer(
                                    inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                inBuf.put(readBuf, 0, got)
                                val ptsUs = (totalFramesSubmitted * 1_000_000L) / sampleRate
                                codec!!.queueInputBuffer(inIndex, 0, got, ptsUs, 0)
                                totalFramesSubmitted += got / bytesPerFrame
                                totalBytesRead += got
                                onProgress?.invoke((totalBytesRead.toFloat() / totalDataBytes.toFloat()).coerceIn(0f, 0.95f))
                            }
                        }
                    }

                    val outIndex = codec!!.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxTrackIndex = muxer!!.addTrack(codec!!.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }

                        outIndex >= 0 -> {
                            val outBuf = codec!!.getOutputBuffer(outIndex)
                            val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (!isCodecConfig && outBuf != null && bufferInfo.size > 0 && muxerStarted) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer!!.writeSampleData(muxTrackIndex, outBuf, bufferInfo)
                            }
                            codec!!.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            onProgress?.invoke(1.0f)
            outputM4a.exists() && outputM4a.length() > 256L
        } catch (_: Exception) {
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }

    private fun buildWavHeader16Bit(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
        val clampedData = dataBytes.coerceAtMost(0xFFFF_FF00L).toInt()
        val riffSize = 36 + clampedData
        val blockAlign = (channels * 2).toShort()
        val byteRate = sampleRate * channels * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(clampedData)
        }.array()
    }
}
