package com.soundtracker.app.update

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Streaming binary delta patcher (`STPATCH1` format) that reconstructs a bit-for-bit identical
 * v2/v3-signed target `.apk` from the currently installed `/data/app/.../base.apk` plus a compact
 * `.stpatch` file.
 *
 * Peak memory footprint is `< 256 KB` (streaming 64 KB buffers + in-flight SHA-256 verification),
 * avoiding OOM on large 260MB+ APKs containing embedded AI / SoundFont assets.
 */
object ApkDeltaPatcher {

    private val MAGIC_BYTES = "STPATCH1".toByteArray(Charsets.US_ASCII)
    const val HEADER_SIZE_BYTES = 124
    private const val OP_COPY = 1
    private const val OP_ADD = 2
    private const val STREAM_BUFFER_BYTES = 64 * 1024

    data class InstalledApkInfo(
        val file: File,
        val sizeBytes: Long,
        val sha256: String
    )

    data class PatchHeader(
        val sourceSizeBytes: Long,
        val sourceSha256: String,
        val targetSizeBytes: Long,
        val targetSha256: String,
        val opCount: Int
    )

    private data class CachedInstalledApk(
        val path: String,
        val length: Long,
        val lastModified: Long,
        val info: InstalledApkInfo
    )

    private val cacheLock = Any()

    @Volatile
    private var cachedBaseApk: CachedInstalledApk? = null

    /**
     * Resolves the currently installed base APK (`/data/app/.../base.apk`) and computes its
     * SHA-256 hash, memoized across checks so repeated lookups take 0ms.
     */
    fun getInstalledApkInfo(context: Context): InstalledApkInfo? {
        val sourcePath = context.applicationInfo?.sourceDir ?: return null
        if (sourcePath.isBlank()) return null
        val file = File(sourcePath)
        if (!file.exists() || !file.isFile || !file.canRead()) return null
        val len = file.length()
        if (len <= 0L) return null
        val lastMod = file.lastModified()

        cachedBaseApk?.let { cached ->
            if (cached.path == file.absolutePath && cached.length == len && cached.lastModified == lastMod) {
                return cached.info
            }
        }

        return runCatching {
            val sha = computeSha256Hex(file)
            val info = InstalledApkInfo(
                file = file,
                sizeBytes = len,
                sha256 = sha
            )
            synchronized(cacheLock) {
                cachedBaseApk = CachedInstalledApk(
                    path = file.absolutePath,
                    length = len,
                    lastModified = lastMod,
                    info = info
                )
            }
            info
        }.getOrNull()
    }

    fun readHeader(patchFile: File): PatchHeader {
        if (!patchFile.exists() || patchFile.length() <= HEADER_SIZE_BYTES) {
            throw IllegalArgumentException("增量差分包文件不完整")
        }
        DataInputStream(BufferedInputStream(FileInputStream(patchFile), HEADER_SIZE_BYTES)).use { dis ->
            return parseHeaderFromStream(dis)
        }
    }

    private fun parseHeaderFromStream(dis: DataInputStream): PatchHeader {
        val magic = ByteArray(8)
        dis.readFully(magic)
        if (!magic.contentEquals(MAGIC_BYTES)) {
            throw IllegalArgumentException("增量差分包格式标识不匹配")
        }
        val sourceSize = dis.readLong()
        val sourceShaBytes = ByteArray(32)
        dis.readFully(sourceShaBytes)
        val targetSize = dis.readLong()
        val targetShaBytes = ByteArray(32)
        dis.readFully(targetShaBytes)
        val opCount = dis.readInt()
        val reserved = ByteArray(32)
        dis.readFully(reserved)

        if (sourceSize <= 0L || targetSize <= 0L || opCount < 0) {
            throw IllegalArgumentException("增量差分包头部元数据无效")
        }

        return PatchHeader(
            sourceSizeBytes = sourceSize,
            sourceSha256 = bytesToHex(sourceShaBytes),
            targetSizeBytes = targetSize,
            targetSha256 = bytesToHex(targetShaBytes),
            opCount = opCount
        )
    }

    /**
     * Applies [patchFile] onto [baseApk] to reconstruct [outputApkTempFile], verifying both source
     * and reconstructed target SHA-256 digests in a single streaming pass.
     */
    fun applyPatch(
        baseApk: InstalledApkInfo,
        patchFile: File,
        outputApkTempFile: File,
        expectedTargetSize: Long = 0L,
        expectedTargetSha256: String? = null,
        checkCancelled: () -> Unit = {},
        onMergeProgress: (writtenBytes: Long, totalTargetBytes: Long) -> Unit = { _, _ -> }
    ) {
        if (outputApkTempFile.exists()) {
            outputApkTempFile.delete()
        }

        try {
            FileInputStream(patchFile).use { rawPatchFis ->
                val headerDis = DataInputStream(rawPatchFis)
                val header = parseHeaderFromStream(headerDis)

                if (baseApk.sizeBytes != header.sourceSizeBytes ||
                    !baseApk.sha256.equals(header.sourceSha256, ignoreCase = true)
                ) {
                    throw IllegalStateException("本机旧版安装包校验不一致，无法应用该增量包")
                }
                if (expectedTargetSize > 0L && header.targetSizeBytes != expectedTargetSize) {
                    throw IllegalStateException("增量包目标大小与新版本信息不一致")
                }
                if (!expectedTargetSha256.isNullOrBlank() &&
                    !header.targetSha256.equals(expectedTargetSha256, ignoreCase = true)
                ) {
                    throw IllegalStateException("增量包目标哈希与新版本信息不一致")
                }

                val targetDigest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var writtenBytes = 0L
                var lastProgressReportBytes = 0L
                onMergeProgress(0L, header.targetSizeBytes)

                RandomAccessFile(baseApk.file, "r").use { baseRaf ->
                    DataInputStream(
                        BufferedInputStream(
                            GZIPInputStream(rawPatchFis, STREAM_BUFFER_BYTES),
                            STREAM_BUFFER_BYTES
                        )
                    ).use { patchStream ->
                        FileOutputStream(outputApkTempFile, false).use { outFos ->
                            BufferedOutputStream(outFos, STREAM_BUFFER_BYTES).use { outBos ->
                                for (opIdx in 0 until header.opCount) {
                                    checkCancelled()
                                    val opCode = patchStream.readUnsignedByte()
                                    when (opCode) {
                                        OP_COPY -> {
                                            val oldOffset = patchStream.readLong()
                                            val length = patchStream.readInt()
                                            if (oldOffset < 0L || length <= 0 ||
                                                oldOffset + length.toLong() > header.sourceSizeBytes
                                            ) {
                                                throw IllegalStateException("增量包 COPY 区间越界")
                                            }
                                            baseRaf.seek(oldOffset)
                                            var remaining = length
                                            while (remaining > 0) {
                                                checkCancelled()
                                                val chunk = minOf(remaining, buffer.size)
                                                baseRaf.readFully(buffer, 0, chunk)
                                                outBos.write(buffer, 0, chunk)
                                                targetDigest.update(buffer, 0, chunk)
                                                remaining -= chunk
                                                writtenBytes += chunk
                                                if (writtenBytes - lastProgressReportBytes >= 3L * 1024L * 1024L) {
                                                    lastProgressReportBytes = writtenBytes
                                                    onMergeProgress(writtenBytes, header.targetSizeBytes)
                                                }
                                            }
                                        }

                                        OP_ADD -> {
                                            val length = patchStream.readInt()
                                            if (length <= 0) {
                                                throw IllegalStateException("增量包 ADD 长度异常")
                                            }
                                            var remaining = length
                                            while (remaining > 0) {
                                                checkCancelled()
                                                val chunk = minOf(remaining, buffer.size)
                                                patchStream.readFully(buffer, 0, chunk)
                                                outBos.write(buffer, 0, chunk)
                                                targetDigest.update(buffer, 0, chunk)
                                                remaining -= chunk
                                                writtenBytes += chunk
                                                if (writtenBytes - lastProgressReportBytes >= 3L * 1024L * 1024L) {
                                                    lastProgressReportBytes = writtenBytes
                                                    onMergeProgress(writtenBytes, header.targetSizeBytes)
                                                }
                                            }
                                        }

                                        else -> throw IllegalStateException("未知增量合成指令 ($opCode)")
                                    }
                                }
                                outBos.flush()
                                outFos.fd.sync()
                            }
                        }
                    }
                }

                if (writtenBytes != header.targetSizeBytes ||
                    outputApkTempFile.length() != header.targetSizeBytes
                ) {
                    throw IllegalStateException(
                        "合成后安装包大小不匹配（实际 $writtenBytes 字节，预期 ${header.targetSizeBytes} 字节）"
                    )
                }

                val actualTargetSha = bytesToHex(targetDigest.digest())
                if (!actualTargetSha.equals(header.targetSha256, ignoreCase = true)) {
                    throw IllegalStateException("合成后安装包 SHA-256 校验未通过")
                }

                onMergeProgress(header.targetSizeBytes, header.targetSizeBytes)
            }
        } catch (t: Throwable) {
            runCatching {
                if (outputApkTempFile.exists()) outputApkTempFile.delete()
            }
            throw t
        }
    }

    private fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return bytesToHex(digest.digest())
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hexDigits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = hexDigits[v ushr 4]
            chars[i * 2 + 1] = hexDigits[v and 0x0F]
        }
        return String(chars).lowercase(Locale.US)
    }
}
