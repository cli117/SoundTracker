package com.soundtracker.app.storage

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.soundtracker.app.audio.AudioCodecHelper
import com.soundtracker.app.dsp.AudioMetrics
import com.soundtracker.app.dsp.ExportFormat
import com.soundtracker.app.dsp.NativeAudioEngine
import com.soundtracker.app.ui.SessionTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class StoredResourceCategory(val label: String) {
    WORKSPACE_TRACK("工作台音轨"),
    SHARE_EXPORT("微信/分享导出"),
    TEMP_CACHE("临时转码缓存"),
    APK_UPDATE("更新安装包")
}

data class StoredResourceItem(
    val id: String,
    val filePath: String,
    val displayName: String,
    val subtitle: String,
    val category: StoredResourceCategory,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val linkedTrackId: String? = null
)

data class EphemeralStorageInfo(
    val workspaceBytes: Long,
    val shareStagingBytes: Long,
    val shareStagingFileCount: Int,
    val resources: List<StoredResourceItem> = emptyList()
) {
    val totalEphemeralBytes: Long get() = workspaceBytes + shareStagingBytes
}

object SessionWorkspaceManager {

    private const val WORKSPACE_DIR = "workspace_tracks"
    private const val LEGACY_CACHE_WORKSPACE_DIR = "workspace_session"
    private const val WORKSPACE_TMP_DIR = "workspace_tmp"
    private const val WECHAT_STAGING_DIR = "wechat_share_staging"
    private const val APK_UPDATES_DIR = "apk_updates"
    private const val TRACKS_INDEX_FILE = "workspace_tracks_index.json"
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val WECHAT_SHARE_UI = "com.tencent.mm.ui.tools.ShareImgUI"

    fun workspaceDir(context: Context): File =
        File(context.filesDir, WORKSPACE_DIR).apply { mkdirs() }

    fun tempDir(context: Context): File =
        File(context.cacheDir, WORKSPACE_TMP_DIR).apply { mkdirs() }

    fun shareStagingRoot(context: Context): File =
        File(context.cacheDir, WECHAT_STAGING_DIR).apply { mkdirs() }

    private fun tracksIndexFile(context: Context): File =
        File(context.filesDir, TRACKS_INDEX_FILE)

    fun createSessionTrackFile(context: Context, prefix: String, ext: String = "wav"): File {
        val dir = workspaceDir(context)
        return File(dir, "${prefix}_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext")
    }

    /**
     * Startup maintenance: only cleans transient decode scratch (`workspace_tmp`),
     * preserving all user workspace tracks and share exports until manually deleted in UI.
     */
    fun performColdStartSweep(context: Context) {
        runCatching {
            val tmp = File(context.cacheDir, WORKSPACE_TMP_DIR)
            if (tmp.exists()) tmp.deleteRecursively()
            tmp.mkdirs()
        }
    }

    /**
     * Persists the current session track list metadata so reopening the app restores all tracks.
     */
    fun saveTracksIndex(context: Context, tracks: List<SessionTrack>) {
        runCatching {
            val root = JSONArray()
            for (t in tracks) {
                if (!t.audioFile.exists()) continue
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("displayName", t.displayName)
                    put("badge", t.badge)
                    put("detailNote", t.detailNote)
                    put("audioPath", t.audioFile.absolutePath)
                    put("originTrackId", t.originTrackId ?: "")
                    put("savedLocalPath", t.savedLocalPath ?: "")
                    put("sharedWeChatCount", t.sharedWeChatCount)
                    put("createdAt", t.createdAt)
                    val m = t.metrics
                    val mObj = JSONObject().apply {
                        put("integratedLufs", m.integratedLufs.toDouble())
                        put("truePeakDbtp", m.truePeakDbtp.toDouble())
                        put("samplePeakDbfs", m.samplePeakDbfs.toDouble())
                        put("rmsDbfs", m.rmsDbfs.toDouble())
                        put("pureLosslessHeadroomDb", m.pureLosslessHeadroomDb.toDouble())
                        put("durationSec", m.durationSec.toDouble())
                        put("sampleRate", m.sampleRate)
                        put("channels", m.channels)
                        put("detectedBpm", m.detectedBpm.toDouble())
                        val peaksArr = JSONArray()
                        for (p in m.waveformPeaks) {
                            peaksArr.put(((p * 1000f).toInt() / 1000.0))
                        }
                        put("waveformPeaks", peaksArr)
                    }
                    put("metrics", mObj)
                }
                root.put(obj)
            }
            tracksIndexFile(context).writeText(root.toString())
        }
    }

    /**
     * Loads persisted workspace tracks on app launch.
     */
    fun loadTracksIndex(context: Context): List<SessionTrack> {
        return runCatching {
            val idxFile = tracksIndexFile(context)
            if (!idxFile.exists()) return emptyList()
            val arr = JSONArray(idxFile.readText())
            val out = mutableListOf<SessionTrack>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val audioFile = File(obj.optString("audioPath"))
                if (!audioFile.exists() || audioFile.length() <= 0L) continue
                val mObj = obj.optJSONObject("metrics")
                val peaks = mutableListOf<Float>()
                val peaksArr = mObj?.optJSONArray("waveformPeaks")
                if (peaksArr != null) {
                    for (k in 0 until peaksArr.length()) {
                        peaks.add(peaksArr.optDouble(k, 0.2).toFloat())
                    }
                }
                val metrics = AudioMetrics(
                    integratedLufs = mObj?.optDouble("integratedLufs", -16.0)?.toFloat() ?: -16.0f,
                    truePeakDbtp = mObj?.optDouble("truePeakDbtp", -1.0)?.toFloat() ?: -1.0f,
                    samplePeakDbfs = mObj?.optDouble("samplePeakDbfs", -1.0)?.toFloat() ?: -1.0f,
                    rmsDbfs = mObj?.optDouble("rmsDbfs", -18.0)?.toFloat() ?: -18.0f,
                    pureLosslessHeadroomDb = mObj?.optDouble("pureLosslessHeadroomDb", 0.8)?.toFloat() ?: 0.8f,
                    durationSec = mObj?.optDouble("durationSec", 0.0)?.toFloat() ?: 0.0f,
                    sampleRate = mObj?.optInt("sampleRate", 44100) ?: 44100,
                    channels = mObj?.optInt("channels", 2) ?: 2,
                    detectedBpm = mObj?.optDouble("detectedBpm", 120.0)?.toFloat() ?: 120.0f,
                    waveformPeaks = peaks
                )
                val originRaw = obj.optString("originTrackId")
                val savedRaw = obj.optString("savedLocalPath")
                out.add(
                    SessionTrack(
                        id = obj.optString("id"),
                        displayName = obj.optString("displayName", audioFile.nameWithoutExtension),
                        badge = obj.optString("badge", "工作台音轨"),
                        detailNote = obj.optString("detailNote", ""),
                        audioFile = audioFile,
                        originTrackId = originRaw.takeIf { it.isNotBlank() },
                        metrics = metrics,
                        savedLocalPath = savedRaw.takeIf { it.isNotBlank() },
                        sharedWeChatCount = obj.optInt("sharedWeChatCount", 0),
                        createdAt = obj.optLong("createdAt", audioFile.lastModified())
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * Builds the complete inventory of all audio/cache resources currently stored by the App.
     */
    fun getStorageInfo(
        context: Context,
        activeTracks: List<SessionTrack> = emptyList()
    ): EphemeralStorageInfo {
        val trackByPath = activeTracks.associateBy { it.audioFile.absolutePath }
        val items = mutableListOf<StoredResourceItem>()
        var workspaceBytes = 0L
        var stagingBytes = 0L
        var stagingCount = 0

        // 1. Scan primary workspace_tracks + any legacy workspace_session directory
        val wsDirs = listOf(
            workspaceDir(context),
            File(context.cacheDir, LEGACY_CACHE_WORKSPACE_DIR)
        )
        for (dir in wsDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown()
                .filter { it.isFile && it.length() > 0L }
                .forEach { file ->
                    val len = file.length()
                    workspaceBytes += len
                    val matchedTrack = trackByPath[file.absolutePath]
                    val ext = file.extension.lowercase().ifBlank { "wav" }
                    val friendlyTitle = if (matchedTrack != null) {
                        "${matchedTrack.displayName}.$ext"
                    } else {
                        file.name
                    }
                    val sub = if (matchedTrack != null) {
                        "${matchedTrack.badge} · ${ext.uppercase()}"
                    } else {
                        "工作台音频缓存 · ${ext.uppercase()}"
                    }
                    items.add(
                        StoredResourceItem(
                            id = file.absolutePath,
                            filePath = file.absolutePath,
                            displayName = friendlyTitle,
                            subtitle = sub,
                            category = StoredResourceCategory.WORKSPACE_TRACK,
                            sizeBytes = len,
                            lastModifiedMs = matchedTrack?.createdAt ?: file.lastModified(),
                            linkedTrackId = matchedTrack?.id
                        )
                    )
                }
        }

        // 2. Scan WeChat / system share exported files
        val stRoot = File(context.cacheDir, WECHAT_STAGING_DIR)
        if (stRoot.exists()) {
            stRoot.walkTopDown()
                .filter { it.isFile && it.length() > 0L }
                .forEach { file ->
                    val len = file.length()
                    stagingBytes += len
                    stagingCount++
                    val ext = file.extension.uppercase()
                    items.add(
                        StoredResourceItem(
                            id = file.absolutePath,
                            filePath = file.absolutePath,
                            displayName = file.name,
                            subtitle = "微信/系统分享导出件 · $ext",
                            category = StoredResourceCategory.SHARE_EXPORT,
                            sizeBytes = len,
                            lastModifiedMs = file.lastModified(),
                            linkedTrackId = null
                        )
                    )
                }
        }

        // 3. Scan any leftover temporary decode files
        val tmpRoot = File(context.cacheDir, WORKSPACE_TMP_DIR)
        if (tmpRoot.exists()) {
            tmpRoot.walkTopDown()
                .filter { it.isFile && it.length() > 0L }
                .forEach { file ->
                    val len = file.length()
                    workspaceBytes += len
                    items.add(
                        StoredResourceItem(
                            id = file.absolutePath,
                            filePath = file.absolutePath,
                            displayName = file.name,
                            subtitle = "临时解码缓存",
                            category = StoredResourceCategory.TEMP_CACHE,
                            sizeBytes = len,
                            lastModifiedMs = file.lastModified(),
                            linkedTrackId = null
                        )
                    )
                }
        }

        // 4. Scan downloaded OTA APK update packages (if any waiting for installation)
        val apkRoot = File(context.cacheDir, APK_UPDATES_DIR)
        if (apkRoot.exists()) {
            apkRoot.walkTopDown()
                .filter { it.isFile && it.length() > 0L }
                .forEach { file ->
                    val len = file.length()
                    stagingBytes += len
                    val isPart = file.name.endsWith(".part", ignoreCase = true)
                    items.add(
                        StoredResourceItem(
                            id = file.absolutePath,
                            filePath = file.absolutePath,
                            displayName = file.name,
                            subtitle = if (isPart) "未完成的版本更新缓存" else "已下载的版本更新安装包",
                            category = StoredResourceCategory.APK_UPDATE,
                            sizeBytes = len,
                            lastModifiedMs = file.lastModified(),
                            linkedTrackId = null
                        )
                    )
                }
        }

        val sortedItems = items.sortedByDescending { it.lastModifiedMs }
        return EphemeralStorageInfo(
            workspaceBytes = workspaceBytes,
            shareStagingBytes = stagingBytes,
            shareStagingFileCount = stagingCount,
            resources = sortedItems
        )
    }

    /**
     * Deletes specific resource files selected by the user (single or batch).
     * Returns the total bytes freed.
     */
    fun deleteStoredResources(context: Context, filePaths: Collection<String>): Long {
        var freedBytes = 0L
        val stRootPath = File(context.cacheDir, WECHAT_STAGING_DIR).absolutePath
        for (path in filePaths) {
            runCatching {
                val f = File(path)
                if (f.exists() && f.isFile) {
                    val sz = f.length()
                    if (f.delete()) {
                        freedBytes += sz
                    }
                    // If parent was a `share_<timestamp>` slot directory and is now empty, remove it
                    val parent = f.parentFile
                    if (parent != null && parent.absolutePath.startsWith(stRootPath) && parent.absolutePath != stRootPath) {
                        if (parent.listFiles().isNullOrEmpty()) {
                            parent.delete()
                        }
                    }
                }
            }
        }
        return freedBytes
    }

    /**
     * Wipes ALL stored workspace tracks, share staging exports, and temporary files.
     */
    fun wipeSessionWorkspace(context: Context) {
        runCatching {
            File(context.filesDir, WORKSPACE_DIR).let {
                if (it.exists()) it.deleteRecursively()
                it.mkdirs()
            }
            File(context.cacheDir, LEGACY_CACHE_WORKSPACE_DIR).let {
                if (it.exists()) it.deleteRecursively()
            }
            File(context.cacheDir, WORKSPACE_TMP_DIR).let {
                if (it.exists()) it.deleteRecursively()
                it.mkdirs()
            }
            File(context.cacheDir, WECHAT_STAGING_DIR).let {
                if (it.exists()) it.deleteRecursively()
                it.mkdirs()
            }
            File(context.cacheDir, APK_UPDATES_DIR).let {
                if (it.exists()) it.deleteRecursively()
            }
            tracksIndexFile(context).delete()
        }
    }

    fun sweepExpiredShareStaging(context: Context) {
        // Kept as no-op for manual user control in Storage Manager
    }

    /**
     * Exports [sourceWavFile] into the requested [format] ([ExportFormat.MP3_320], [ExportFormat.M4A_256], or [ExportFormat.WAV_16]).
     */
    suspend fun renderExportFile(
        sourceWavFile: File,
        destFile: File,
        format: ExportFormat,
        title: String,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        when (format) {
            ExportFormat.MP3_320 -> NativeAudioEngine.encodeMp3(
                inputWav = sourceWavFile,
                outputMp3 = destFile,
                bitrateKbps = 320,
                title = title,
                onProgress = onProgress
            )

            ExportFormat.M4A_256 -> AudioCodecHelper.encodeWavToM4a(
                inputWav = sourceWavFile,
                outputM4a = destFile,
                bitrateBps = 256_000,
                onProgress = onProgress
            )

            ExportFormat.WAV_16 -> {
                runCatching {
                    sourceWavFile.inputStream().buffered(256 * 1024).use { ins ->
                        destFile.outputStream().buffered(256 * 1024).use { outs ->
                            ins.copyTo(outs, 256 * 1024)
                        }
                    }
                    onProgress?.invoke(1.0f)
                    true
                }.getOrDefault(false)
            }
        }
    }

    /**
     * Stages an audio file with the user's exact custom name in `wechat_share_staging/share_<ts>/<Name>.<ext>`
     * and returns a launchable Share Intent (targeting WeChat directly when [targetWeChatDirectly] is true).
     */
    suspend fun prepareShareIntent(
        context: Context,
        sourceAudioFile: File,
        customDisplayName: String,
        format: ExportFormat,
        targetWeChatDirectly: Boolean,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Intent> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = AudioCodecHelper.sanitizeFileName(customDisplayName)
            val fileName = "$safeName.${format.extension}"
            val slotDir = File(shareStagingRoot(context), "share_${System.currentTimeMillis()}")
            slotDir.mkdirs()
            val stagedFile = File(slotDir, fileName)

            val ok = if (sourceAudioFile.extension.equals(format.extension, ignoreCase = true)) {
                sourceAudioFile.copyTo(stagedFile, overwrite = true)
                onProgress?.invoke(1.0f)
                true
            } else {
                renderExportFile(
                    sourceWavFile = sourceAudioFile,
                    destFile = stagedFile,
                    format = format,
                    title = safeName,
                    onProgress = onProgress
                )
            }
            check(ok && stagedFile.exists() && stagedFile.length() > 0L) {
                "导出分享音频失败"
            }
            slotDir.setLastModified(System.currentTimeMillis())

            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, stagedFile)

            val baseIntent = Intent(Intent.ACTION_SEND).apply {
                type = format.mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_TITLE, fileName)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                clipData = ClipData.newRawUri(fileName, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (targetWeChatDirectly && isWeChatInstalled(context)) {
                context.grantUriPermission(
                    WECHAT_PACKAGE,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val weChatDirect = Intent(baseIntent).apply {
                    setClassName(WECHAT_PACKAGE, WECHAT_SHARE_UI)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val canResolveDirect = context.packageManager
                    .queryIntentActivities(weChatDirect, PackageManager.MATCH_DEFAULT_ONLY)
                    .isNotEmpty()

                if (canResolveDirect) {
                    weChatDirect
                } else {
                    Intent(baseIntent).apply {
                        setPackage(WECHAT_PACKAGE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            } else {
                Intent.createChooser(baseIntent, "分享「$fileName」").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
    }

    fun isWeChatInstalled(context: Context): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    WECHAT_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(WECHAT_PACKAGE, 0)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Permanently saves the chosen track into `Music/SoundTracker/<CustomName>.<ext>` via MediaStore.
     */
    suspend fun saveToPublicMusicFolder(
        context: Context,
        sourceAudioFile: File,
        customDisplayName: String,
        format: ExportFormat,
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = AudioCodecHelper.sanitizeFileName(customDisplayName)
            val fileName = "$safeName.${format.extension}"

            val tmpRendered = File(tempDir(context), "export_${System.nanoTime()}.${format.extension}")
            try {
                val ok = if (sourceAudioFile.extension.equals(format.extension, ignoreCase = true)) {
                    sourceAudioFile.copyTo(tmpRendered, overwrite = true)
                    onProgress?.invoke(0.9f)
                    true
                } else {
                    renderExportFile(sourceAudioFile, tmpRendered, format, safeName) { p ->
                        onProgress?.invoke(p * 0.9f)
                    }
                }
                check(ok && tmpRendered.exists()) { "音频编码失败" }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Audio.Media.TITLE, safeName)
                        put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/SoundTracker")
                        put(MediaStore.Audio.Media.IS_MUSIC, 1)
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                    val itemUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("无法创建系统媒体库条目")
                    try {
                        resolver.openOutputStream(itemUri)?.buffered(256 * 1024)?.use { outs ->
                            tmpRendered.inputStream().buffered(256 * 1024).use { ins ->
                                ins.copyTo(outs, 256 * 1024)
                            }
                        } ?: error("无法写入目标音频文件")

                        val doneValues = ContentValues().apply {
                            put(MediaStore.Audio.Media.IS_PENDING, 0)
                        }
                        resolver.update(itemUri, doneValues, null, null)
                    } catch (e: Exception) {
                        resolver.delete(itemUri, null, null)
                        throw e
                    }
                    onProgress?.invoke(1.0f)
                    "Music/SoundTracker/$fileName"
                } else {
                    @Suppress("DEPRECATION")
                    val musicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    val appMusicDir = File(musicRoot, "SoundTracker").apply { mkdirs() }
                    val finalFile = resolveUniqueFile(appMusicDir, safeName, format.extension)
                    tmpRendered.copyTo(finalFile, overwrite = true)
                    onProgress?.invoke(1.0f)
                    "Music/SoundTracker/${finalFile.name}"
                }
            } finally {
                if (tmpRendered.exists()) tmpRendered.delete()
            }
        }
    }

    /**
     * Permanently saves to a user-chosen SAF document URI (`ACTION_CREATE_DOCUMENT`).
     */
    suspend fun saveToCustomDocumentUri(
        context: Context,
        sourceAudioFile: File,
        targetUri: Uri,
        customDisplayName: String,
        format: ExportFormat,
        onProgress: ((Float) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = AudioCodecHelper.sanitizeFileName(customDisplayName)
            val tmpRendered = File(tempDir(context), "saf_${System.nanoTime()}.${format.extension}")
            try {
                val ok = if (sourceAudioFile.extension.equals(format.extension, ignoreCase = true)) {
                    sourceAudioFile.copyTo(tmpRendered, overwrite = true)
                    onProgress?.invoke(0.9f)
                    true
                } else {
                    renderExportFile(sourceAudioFile, tmpRendered, format, safeName) { p ->
                        onProgress?.invoke(p * 0.9f)
                    }
                }
                check(ok && tmpRendered.exists()) { "导出编码失败" }

                context.contentResolver.openOutputStream(targetUri, "wt")?.buffered(256 * 1024)?.use { outs ->
                    tmpRendered.inputStream().buffered(256 * 1024).use { ins ->
                        ins.copyTo(outs, 256 * 1024)
                    }
                } ?: error("无法写入所选位置")

                onProgress?.invoke(1.0f)
                AudioCodecHelper.resolveDisplayName(context, targetUri) + ".${format.extension}"
            } finally {
                if (tmpRendered.exists()) tmpRendered.delete()
            }
        }
    }

    private fun resolveUniqueFile(dir: File, baseName: String, ext: String): File {
        var candidate = File(dir, "$baseName.$ext")
        var idx = 1
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_($idx).$ext")
            idx++
        }
        return candidate
    }
}
