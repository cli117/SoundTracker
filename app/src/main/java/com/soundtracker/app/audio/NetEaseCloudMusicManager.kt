package com.soundtracker.app.audio

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.soundtracker.app.storage.SessionWorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * Represents a downloaded track discovered in NetEase Cloud Music (网易云音乐) local directories.
 * If multiple files exist for the same song (e.g., MP3 + FLAC or NCM), the highest quality
 * version (FLAC > MP3) is automatically selected.
 */
data class NetEaseLocalTrack(
    val id: String,
    val file: File,
    val displayTitle: String,
    val songName: String,
    val artist: String,
    val album: String,
    val sourceExt: String,      // "ncm", "flac", or "mp3"
    val targetFormat: String,   // Always "flac" or "mp3" (decrypted inner format for .ncm)
    val bitrateKbps: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val lastModifiedMs: Long
) {
    val isNcm: Boolean get() = sourceExt.equals("ncm", ignoreCase = true)
    val isLosslessFlac: Boolean get() = targetFormat.equals("flac", ignoreCase = true)

    val badgeText: String
        get() = when {
            isNcm && isLosslessFlac -> "NCM → FLAC 无损"
            isNcm -> "NCM → MP3 ${if (bitrateKbps > 0) "${bitrateKbps}k" else "极高"}"
            isLosslessFlac -> "FLAC 无损"
            else -> "MP3 ${if (bitrateKbps > 0) "${bitrateKbps}k" else "极高"}"
        }

    /**
     * Quality score used to pick the best version when multiple files exist for the same track:
     * Lossless FLAC always beats MP3 ("哪个质量好选哪个"), then higher bitrate, then larger file size.
     */
    val qualityScore: Long
        get() {
            val formatTier = if (isLosslessFlac) 1_000_000_000L else 100_000_000L
            val bitrateTier = bitrateKbps.coerceIn(0, 9999) * 10_000L
            val sizeTier = (sizeBytes / 1024L).coerceIn(0L, 9_999L)
            return formatTier + bitrateTier + sizeTier
        }
}

data class NcmImportResult(
    val outputFile: File,
    val format: String,         // "flac" or "mp3"
    val displayTitle: String,
    val bitrateKbps: Int,
    val wasEncryptedNcm: Boolean
)

object NetEaseCloudMusicManager {

    // Standard AES-128-ECB Core Key & Meta Key used by NetEase Cloud Music (.ncm container)
    private val NCM_CORE_KEY = byteArrayOf(
        0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
        0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57
    )
    private val NCM_META_KEY = byteArrayOf(
        0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
        0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
    )

    private val CANDIDATE_RELATIVE_PATHS = listOf(
        "Download/netease/cloudmusic/Music",
        "Download/netease/cloudmusic/音质升级",
        "Download/netease/cloudmusic/Dj",
        "netease/cloudmusic/Music",
        "netease/cloudmusic/音质升级",
        "netease/cloudmusic/Dj",
        "Music/netease/cloudmusic/Music",
        "Music/CloudMusic",
        "CloudMusic",
        "Download/CloudMusic",
        "Android/media/com.netease.cloudmusic"
    )

    /**
     * Checks whether the app has full file access needed to read `.ncm` encrypted files
     * inside NetEase Cloud Music's public download directories on Android 11+.
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Scans all NetEase Cloud Music download directories on the device, parses `.ncm` headers
     * to determine whether each `.ncm` wraps lossless `FLAC` or `MP3`, and deduplicates same-song
     * entries by keeping whichever file has the highest audio quality (`FLAC > MP3`).
     */
    suspend fun scanNetEaseLocalTracks(context: Context): List<NetEaseLocalTrack> = withContext(Dispatchers.IO) {
        val discoveredFiles = linkedMapOf<String, File>()
        val extRoot = Environment.getExternalStorageDirectory()

        // 1. Direct directory scan across all known NetEase Cloud Music folders
        for (rel in CANDIDATE_RELATIVE_PATHS) {
            val dir = File(extRoot, rel)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.length() > 16_384L) {
                        val ext = f.extension.lowercase()
                        if (ext == "ncm" || ext == "flac" || ext == "mp3") {
                            discoveredFiles[f.absolutePath] = f
                        }
                    }
                }
            }
        }

        // 2. Fallback MediaStore scan for any netease/cloudmusic audio files if direct listing was restricted
        runCatching {
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            val selection = "${MediaStore.Audio.Media.DATA} LIKE ? OR ${MediaStore.Audio.Media.DATA} LIKE ?"
            val args = arrayOf("%netease%", "%cloudmusic%")
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    if (dataIdx >= 0) {
                        val path = cursor.getString(dataIdx)
                        if (!path.isNullOrBlank()) {
                            val f = File(path)
                            val ext = f.extension.lowercase()
                            if (f.exists() && f.length() > 16_384L && (ext == "ncm" || ext == "flac" || ext == "mp3")) {
                                discoveredFiles[f.absolutePath] = f
                            }
                        }
                    }
                }
            }
        }

        // 3. Parse metadata and inner format ("flac" vs "mp3") for each candidate file
        //    and deduplicate by normalized song key, keeping the highest quality ("哪个质量好选哪个").
        val bestBySongKey = LinkedHashMap<String, NetEaseLocalTrack>()
        for (file in discoveredFiles.values) {
            coroutineContext.ensureActive()
            val parsed = parseNetEaseLocalFile(file) ?: continue
            val normKey = normalizeSongDedupKey(parsed.displayTitle)
            val existing = bestBySongKey[normKey]
            if (existing == null || parsed.qualityScore > existing.qualityScore) {
                bestBySongKey[normKey] = parsed
            }
        }

        bestBySongKey.values.sortedByDescending { it.lastModifiedMs }
    }

    /**
     * Decrypts a `.ncm` file (or copies `.flac` / `.mp3`) into the SoundTracker workspace
     * directly as a `.flac` or `.mp3` file (never inflating to `.wav`, preserving highest quality).
     */
    suspend fun importNetEaseTrackToWorkspace(
        context: Context,
        track: NetEaseLocalTrack,
        onProgress: (Float) -> Unit = {}
    ): Result<NcmImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceFile = track.file
            require(sourceFile.exists() && sourceFile.length() > 1024L) {
                "找不到网易云音乐源文件：${sourceFile.name}"
            }

            if (track.isNcm || isNcmFile(sourceFile)) {
                FileInputStream(sourceFile).use { fis ->
                    decryptNcmStreamToWorkspace(
                        context = context,
                        inputStream = fis,
                        totalInputBytes = sourceFile.length(),
                        fallbackTitle = track.displayTitle,
                        onProgress = onProgress
                    )
                }
            } else {
                val targetExt = if (sourceFile.extension.equals("flac", ignoreCase = true)) "flac" else "mp3"
                val outFile = SessionWorkspaceManager.createSessionTrackFile(context, "netease", targetExt)
                val totalLen = sourceFile.length().coerceAtLeast(1L)
                FileInputStream(sourceFile).use { fis ->
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = fis.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            copied += n
                            onProgress((copied.toFloat() / totalLen.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
                NcmImportResult(
                    outputFile = outFile,
                    format = targetExt,
                    displayTitle = track.displayTitle,
                    bitrateKbps = track.bitrateKbps,
                    wasEncryptedNcm = false
                )
            }
        }
    }

    /**
     * Also supports decrypting a `.ncm` Uri selected via the system document picker (`+ 本地导入`).
     */
    suspend fun decryptNcmUriToWorkspace(
        context: Context,
        uri: Uri,
        fallbackTitle: String,
        onProgress: (Float) -> Unit = {}
    ): Result<NcmImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val totalLen = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)
            val ins = context.contentResolver.openInputStream(uri)
                ?: error("无法读取所选的 NCM 文件")
            ins.use { stream ->
                decryptNcmStreamToWorkspace(
                    context = context,
                    inputStream = stream,
                    totalInputBytes = totalLen,
                    fallbackTitle = fallbackTitle,
                    onProgress = onProgress
                )
            }
        }
    }

    fun isNcmUri(context: Context, uri: Uri, displayName: String): Boolean {
        if (displayName.lowercase().endsWith(".ncm")) return true
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val magic = ByteArray(8)
                val n = ins.read(magic)
                n == 8 && isCtenfdamMagic(magic)
            } ?: false
        }.getOrDefault(false)
    }

    fun isNcmFile(file: File): Boolean {
        if (!file.exists() || file.length() < 64L) return false
        return runCatching {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(8)
                val n = fis.read(magic)
                n == 8 && isCtenfdamMagic(magic)
            }
        }.getOrDefault(false)
    }

    private fun isCtenfdamMagic(b: ByteArray): Boolean {
        return b.size >= 8 &&
            b[0] == 'C'.code.toByte() && b[1] == 'T'.code.toByte() &&
            b[2] == 'E'.code.toByte() && b[3] == 'N'.code.toByte() &&
            b[4] == 'F'.code.toByte() && b[5] == 'D'.code.toByte() &&
            b[6] == 'A'.code.toByte() && b[7] == 'M'.code.toByte()
    }

    private fun parseNetEaseLocalFile(file: File): NetEaseLocalTrack? {
        val ext = file.extension.lowercase()
        val rawStem = file.nameWithoutExtension.trim()
        val (fallbackArtist, fallbackSong) = splitArtistAndSong(rawStem)

        if (ext == "ncm" || isNcmFile(file)) {
            return runCatching {
                FileInputStream(file).use { fis ->
                    val magic = readExact(fis, 8)
                    if (!isCtenfdamMagic(magic)) return null
                    skipExact(fis, 2)

                    val keyLen = readIntLE(fis)
                    if (keyLen !in 16..65536) return null
                    val keyEnc = readExact(fis, keyLen)
                    for (i in 0 until keyLen) {
                        keyEnc[i] = (keyEnc[i].toInt() xor 0x64).toByte()
                    }

                    val metaLen = readIntLE(fis)
                    var songName = fallbackSong
                    var artistName = fallbackArtist
                    var albumName = ""
                    var innerFormat = ""
                    var bitrateKbps = 0
                    var durationMs = 0L

                    if (metaLen in 23..(512 * 1024)) {
                        val metaEnc = readExact(fis, metaLen)
                        for (i in 0 until metaLen) {
                            metaEnc[i] = (metaEnc[i].toInt() xor 0x63).toByte()
                        }
                        val metaJsonStr = decryptMetaJson(metaEnc)
                        if (!metaJsonStr.isNullOrBlank()) {
                            val json = JSONObject(metaJsonStr)
                            songName = json.optString("musicName", fallbackSong).ifBlank { fallbackSong }
                            albumName = json.optString("album", "")
                            innerFormat = json.optString("format", "").lowercase()
                            val br = json.optInt("bitrate", 0)
                            if (br > 0) bitrateKbps = br / 1000
                            durationMs = json.optLong("duration", 0L)
                            val artistArr = json.optJSONArray("artist")
                            if (artistArr != null && artistArr.length() > 0) {
                                val names = mutableListOf<String>()
                                for (idx in 0 until artistArr.length()) {
                                    val entry = artistArr.optJSONArray(idx)
                                    val name = entry?.optString(0)?.trim()
                                    if (!name.isNullOrEmpty()) names.add(name)
                                }
                                if (names.isNotEmpty()) {
                                    artistName = names.joinToString(" / ")
                                }
                            }
                        }
                    } else if (metaLen > 0) {
                        skipExact(fis, metaLen.toLong())
                    }

                    // If format wasn't in metadata JSON, sniff the first 4 decrypted bytes of audio stream
                    if (innerFormat != "flac" && innerFormat != "mp3") {
                        val keyBox = buildNcmKeyBox(decryptRc4Key(keyEnc))
                        readIntLE(fis) // crc
                        val gap5 = readExact(fis, 5)
                        val imgSpace = ByteBuffer.wrap(gap5, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        val imgSize = readIntLE(fis)
                        skipExact(fis, maxOf(imgSpace, imgSize).toLong().coerceAtLeast(0L))
                        val head = ByteArray(4)
                        if (fis.read(head) == 4) {
                            for (i in 0 until 4) {
                                head[i] = (head[i].toInt() xor keyBox[i].toInt()).toByte()
                            }
                            innerFormat = if (isFlacMagic(head)) "flac" else "mp3"
                        } else {
                            innerFormat = if (file.length() > 18 * 1024 * 1024L) "flac" else "mp3"
                        }
                    }

                    if (bitrateKbps <= 0) {
                        bitrateKbps = if (innerFormat == "flac") 900 else 320
                    }

                    val displayTitle = rawStem.ifBlank {
                        if (artistName.isNotBlank()) "$artistName - $songName" else songName
                    }

                    NetEaseLocalTrack(
                        id = file.absolutePath,
                        file = file,
                        displayTitle = displayTitle,
                        songName = songName,
                        artist = artistName,
                        album = albumName,
                        sourceExt = "ncm",
                        targetFormat = innerFormat,
                        bitrateKbps = bitrateKbps,
                        durationMs = durationMs,
                        sizeBytes = file.length(),
                        lastModifiedMs = file.lastModified()
                    )
                }
            }.getOrNull()
        }

        val targetFmt = if (ext == "flac") "flac" else "mp3"
        val estimatedKbps = if (targetFmt == "flac") 950 else 320
        return NetEaseLocalTrack(
            id = file.absolutePath,
            file = file,
            displayTitle = rawStem,
            songName = fallbackSong,
            artist = fallbackArtist,
            album = "",
            sourceExt = ext,
            targetFormat = targetFmt,
            bitrateKbps = estimatedKbps,
            durationMs = 0L,
            sizeBytes = file.length(),
            lastModifiedMs = file.lastModified()
        )
    }

    private suspend fun decryptNcmStreamToWorkspace(
        context: Context,
        inputStream: InputStream,
        totalInputBytes: Long,
        fallbackTitle: String,
        onProgress: (Float) -> Unit
    ): NcmImportResult {
        val magic = readExact(inputStream, 8)
        require(isCtenfdamMagic(magic)) { "不是有效的网易云 NCM 加密文件" }
        skipExact(inputStream, 2)

        val keyLen = readIntLE(inputStream)
        require(keyLen in 16..65536) { "NCM 密钥长度异常 ($keyLen)" }
        val keyEnc = readExact(inputStream, keyLen)
        for (i in 0 until keyLen) {
            keyEnc[i] = (keyEnc[i].toInt() xor 0x64).toByte()
        }
        val rc4Key = decryptRc4Key(keyEnc)
        val keyBox = buildNcmKeyBox(rc4Key)

        val metaLen = readIntLE(inputStream)
        var parsedTitle = fallbackTitle
        var bitrateKbps = 0
        if (metaLen in 23..(512 * 1024)) {
            val metaEnc = readExact(inputStream, metaLen)
            for (i in 0 until metaLen) {
                metaEnc[i] = (metaEnc[i].toInt() xor 0x63).toByte()
            }
            runCatching {
                val metaJsonStr = decryptMetaJson(metaEnc)
                if (!metaJsonStr.isNullOrBlank()) {
                    val json = JSONObject(metaJsonStr)
                    val br = json.optInt("bitrate", 0)
                    if (br > 0) bitrateKbps = br / 1000
                    val song = json.optString("musicName", "")
                    val artistArr = json.optJSONArray("artist")
                    val artists = mutableListOf<String>()
                    if (artistArr != null) {
                        for (idx in 0 until artistArr.length()) {
                            val name = artistArr.optJSONArray(idx)?.optString(0)?.trim()
                            if (!name.isNullOrEmpty()) artists.add(name)
                        }
                    }
                    if (parsedTitle.isBlank() && song.isNotBlank()) {
                        parsedTitle = if (artists.isNotEmpty()) "${artists.joinToString(" ")} - $song" else song
                    }
                }
            }
        } else if (metaLen > 0) {
            skipExact(inputStream, metaLen.toLong())
        }

        // Skip CRC (4 bytes) + 5-byte gap + cover art
        readIntLE(inputStream)
        val gap5 = readExact(inputStream, 5)
        val imgSpace = ByteBuffer.wrap(gap5, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val imgSize = readIntLE(inputStream)
        if (imgSize > 0) {
            skipExact(inputStream, imgSize.toLong())
        }
        if (imgSpace > imgSize) {
            skipExact(inputStream, (imgSpace - imgSize).toLong())
        }

        // Read and decrypt first chunk to detect exact audio format ("flac" vs "mp3")
        val buf = ByteArray(64 * 1024)
        val firstN = readUpTo(inputStream, buf)
        require(firstN >= 4) { "NCM 音频流为空" }
        for (i in 0 until firstN) {
            buf[i] = (buf[i].toInt() xor keyBox[i and 0xFF].toInt()).toByte()
        }

        val isFlac = isFlacMagic(buf)
        val ext = if (isFlac) "flac" else "mp3"
        if (bitrateKbps <= 0) {
            bitrateKbps = if (isFlac) 950 else 320
        }

        val outFile = SessionWorkspaceManager.createSessionTrackFile(context, "netease", ext)
        try {
            FileOutputStream(outFile).use { fos ->
                fos.write(buf, 0, firstN)
                var streamOffset = firstN.toLong()
                while (true) {
                    coroutineContext.ensureActive()
                    val n = inputStream.read(buf)
                    if (n <= 0) break
                    for (i in 0 until n) {
                        val keyIdx = ((streamOffset + i) and 0xFFL).toInt()
                        buf[i] = (buf[i].toInt() xor keyBox[keyIdx].toInt()).toByte()
                    }
                    fos.write(buf, 0, n)
                    streamOffset += n
                    if (totalInputBytes > 0L) {
                        onProgress((streamOffset.toFloat() / totalInputBytes.toFloat()).coerceIn(0.05f, 0.98f))
                    }
                }
            }
            onProgress(1.0f)
            return NcmImportResult(
                outputFile = outFile,
                format = ext,
                displayTitle = parsedTitle.ifBlank { "网易云音乐" },
                bitrateKbps = bitrateKbps,
                wasEncryptedNcm = true
            )
        } catch (t: Throwable) {
            outFile.delete()
            throw t
        }
    }

    private fun decryptRc4Key(keyEnc: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(NCM_CORE_KEY, "AES"))
        val dec = cipher.doFinal(keyEnc)
        // Strip 17-byte "neteasecloudmusic" header
        require(dec.size > 17) { "NCM 核心密钥长度不足" }
        return dec.copyOfRange(17, dec.size)
    }

    private fun decryptMetaJson(metaEnc: ByteArray): String? {
        if (metaEnc.size <= 22) return null
        val b64Bytes = metaEnc.copyOfRange(22, metaEnc.size)
        val cipherBytes = Base64.getDecoder().decode(b64Bytes)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(NCM_META_KEY, "AES"))
        val dec = cipher.doFinal(cipherBytes)
        if (dec.size <= 6) return null
        // Strip 6-byte "music:" prefix
        return String(dec, 6, dec.size - 6, Charsets.UTF_8)
    }

    private fun buildNcmKeyBox(rc4Key: ByteArray): ByteArray {
        val sbox = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + sbox[i] + (rc4Key[i % rc4Key.size].toInt() and 0xFF)) and 0xFF
            val tmp = sbox[i]
            sbox[i] = sbox[j]
            sbox[j] = tmp
        }
        val keyBox = ByteArray(256)
        for (i in 0 until 256) {
            val idx = (i + 1) and 0xFF
            val si = sbox[idx]
            val sj = sbox[(idx + si) and 0xFF]
            keyBox[i] = sbox[(si + sj) and 0xFF].toByte()
        }
        return keyBox
    }

    private fun isFlacMagic(b: ByteArray): Boolean {
        return b.size >= 4 &&
            b[0] == 0x66.toByte() && // 'f'
            b[1] == 0x4C.toByte() && // 'L'
            b[2] == 0x61.toByte() && // 'a'
            b[3] == 0x43.toByte()    // 'C'
    }

    private fun splitArtistAndSong(stem: String): Pair<String, String> {
        val idx = stem.indexOf(" - ")
        return if (idx > 0) {
            stem.substring(0, idx).trim() to stem.substring(idx + 3).trim()
        } else {
            "" to stem
        }
    }

    private fun normalizeSongDedupKey(title: String): String {
        return title.lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun readIntLE(ins: InputStream): Int {
        val b = readExact(ins, 4)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readExact(ins: InputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = ins.read(out, off, len - off)
            if (n < 0) throw IllegalStateException("Unexpected EOF while reading NCM header")
            off += n
        }
        return out
    }

    private fun readUpTo(ins: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        return off
    }

    private fun skipExact(ins: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(8192)
        while (remaining > 0L) {
            val skipped = ins.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val toRead = minOf(remaining, scratch.size.toLong()).toInt()
                val n = ins.read(scratch, 0, toRead)
                if (n < 0) break
                remaining -= n
            }
        }
    }
}
