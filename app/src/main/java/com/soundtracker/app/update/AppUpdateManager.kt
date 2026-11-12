package com.soundtracker.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.soundtracker.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.coroutineContext

enum class UpdateDownloadChannel(val label: String, val description: String) {
    DIRECT_GITHUB(
        label = "GitHub 官方直连",
        description = "直接从 GitHub Release 下载（网络通畅或开启代理时推荐）"
    ),
    MIRROR_ACCELERATED(
        label = "国内镜像加速",
        description = "通过加速节点中转下载并自动校验安装包完整性"
    )
}

enum class UpdateCheckStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    ERROR
}

data class ApkDeltaPatchAsset(
    val fromVersion: String,
    val patchAssetName: String,
    val patchDownloadUrl: String,
    val patchSizeBytes: Long,
    val patchSha256: String?,
    val sourceSizeBytes: Long,
    val sourceSha256: String?
)

data class GithubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val publishedAtText: String,
    val apkAssetName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String?,
    val htmlReleaseUrl: String,
    val deltaPatch: ApkDeltaPatchAsset? = null
) {
    val isIncrementalAvailable: Boolean
        get() = deltaPatch != null &&
            deltaPatch.patchSizeBytes > 0L &&
            (apkSizeBytes <= 0L || deltaPatch.patchSizeBytes < apkSizeBytes)

    val effectiveDownloadBytes: Long
        get() = if (isIncrementalAvailable) deltaPatch!!.patchSizeBytes else apkSizeBytes

    val savedBytes: Long
        get() = if (isIncrementalAvailable && apkSizeBytes > deltaPatch!!.patchSizeBytes) {
            apkSizeBytes - deltaPatch.patchSizeBytes
        } else {
            0L
        }
}

data class AppUpdateUiState(
    val currentVersionName: String = BuildConfig.VERSION_NAME,
    val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    val checkStatus: UpdateCheckStatus = UpdateCheckStatus.IDLE,
    val latestRelease: GithubReleaseInfo? = null,
    val hasUpdate: Boolean = false,
    val errorMessage: String? = null,
    val lastCheckedTimeText: String? = null,
    val downloadChannel: UpdateDownloadChannel = UpdateDownloadChannel.DIRECT_GITHUB,
    val isDownloading: Boolean = false,
    val activeDownloadIsDelta: Boolean = false,
    val isMergingPatch: Boolean = false,
    val mergeProgressFraction: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val readyApkPath: String? = null
) {
    val hasReadyApk: Boolean
        get() = !readyApkPath.isNullOrBlank() && File(readyApkPath).exists()
}

object AppUpdateManager {

    const val GITHUB_OWNER = "cli117"
    const val GITHUB_REPO = "SoundTracker"
    const val RELEASES_HOME_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"

    private const val RELEASES_API_LATEST =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    private const val RELEASES_WEB_LATEST =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    private const val UPDATE_MANIFEST_FILE_NAME = "update-manifest.json"
    private const val APK_UPDATES_CACHE_DIR = "apk_updates"

    private val MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/"
    )

    private val SHA256_NOTE_REGEX = Regex("""(?i)sha256\s*[:=]\s*`?([0-9a-f]{64})`?""")

    private data class RemoteAssetEntry(
        val name: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String?
    )

    fun updatesDir(context: Context): File =
        File(context.cacheDir, APK_UPDATES_CACHE_DIR).apply { mkdirs() }

    /**
     * Cleans up already-installed `.apk` packages (`<= currentVersionName`), temporary `.tmp` merge
     * files, and stale `.part` / `.stpatch` files on cold start so update packages never waste storage.
     */
    fun cleanupInstalledOrStaleApks(
        context: Context,
        currentVersionName: String = BuildConfig.VERSION_NAME
    ) {
        runCatching {
            val dir = File(context.cacheDir, APK_UPDATES_CACHE_DIR)
            if (!dir.exists()) return
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val lower = file.name.lowercase(Locale.US)
                when {
                    lower.endsWith(".tmp") -> {
                        file.delete()
                    }
                    lower.endsWith(".part") -> {
                        if (now - file.lastModified() > 24L * 3600_000L) {
                            file.delete()
                        }
                    }
                    lower.endsWith(".stpatch") -> {
                        val targetVer = file.nameWithoutExtension
                            .substringAfter("-to-", file.nameWithoutExtension)
                            .removePrefix("SoundTracker-")
                            .removePrefix("SoundTracker_")
                        if (!isNewerVersion(targetVer, currentVersionName) ||
                            now - file.lastModified() > 24L * 3600_000L
                        ) {
                            file.delete()
                        }
                    }
                    lower.endsWith(".apk") -> {
                        val extractedVer = file.nameWithoutExtension
                            .removePrefix("SoundTracker-")
                            .removePrefix("SoundTracker_")
                        if (!isNewerVersion(extractedVer, currentVersionName)) {
                            file.delete()
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns an already-downloaded and size-verified APK file for [release] if present.
     */
    fun findReadyApkFile(context: Context, release: GithubReleaseInfo): File? {
        val target = targetApkFile(context, release)
        if (!target.exists() || !target.isFile || target.length() <= 0L) return null
        if (release.apkSizeBytes > 0L && target.length() != release.apkSizeBytes) {
            return null
        }
        return target
    }

    private fun targetApkFile(context: Context, release: GithubReleaseInfo): File {
        val safeTag = release.tagName.replace(Regex("""[^A-Za-z0-9._+-]"""), "_")
        return File(updatesDir(context), "SoundTracker-$safeTag.apk")
    }

    private fun targetPatchFile(context: Context, release: GithubReleaseInfo): File {
        val safeTag = release.tagName.replace(Regex("""[^A-Za-z0-9._+-]"""), "_")
        return File(updatesDir(context), "SoundTracker-$safeTag.stpatch")
    }

    /**
     * Queries GitHub Releases for the latest stable release and matches an incremental `.stpatch`
     * binary delta package when the installed `/data/app/.../base.apk` matches a published base APK.
     *
     * - Returns `Result.success(null)` if the repository currently has no published releases yet.
     * - Automatically falls back to parsing the web `/releases/latest` 302 redirect + `update-manifest.json`
     *   if `api.github.com` is rate-limited (HTTP 403) or unreachable.
     */
    suspend fun fetchLatestRelease(context: Context? = null): Result<GithubReleaseInfo?> =
        withContext(Dispatchers.IO) {
            try {
                val installedApk = context?.let { ApkDeltaPatcher.getInstalledApkInfo(it) }

                val apiAttempt = runCatching { fetchViaGithubApi(installedApk) }
                if (apiAttempt.isSuccess) {
                    return@withContext Result.success(apiAttempt.getOrNull())
                }

                // Fallback when api.github.com hits shared-IP unauthenticated rate limit or DNS block
                val webFallback = runCatching { fetchViaWebRedirectFallback(installedApk) }
                if (webFallback.isSuccess) {
                    return@withContext Result.success(webFallback.getOrNull())
                }

                val primaryErr = apiAttempt.exceptionOrNull() ?: webFallback.exceptionOrNull()
                Result.failure(primaryErr ?: IllegalStateException("无法连接 GitHub Release 服务"))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun fetchViaGithubApi(
        installedApk: ApkDeltaPatcher.InstalledApkInfo?
    ): GithubReleaseInfo? {
        val conn = (URL(RELEASES_API_LATEST).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_500
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "SoundTracker-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return null
            }
            if (code !in 200..299) {
                throw IllegalStateException("GitHub API 响应异常 (HTTP $code)")
            }

            val bodyJson = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(bodyJson)
            if (root.optBoolean("draft", false)) return null

            val tagName = root.optString("tag_name", "").trim()
            if (tagName.isEmpty()) return null

            val versionName = normalizeVersionString(tagName)
            val releaseTitle = root.optString("name", "").trim().ifBlank { "SoundTracker $tagName" }
            val rawNotes = root.optString("body", "").trim()
            val htmlUrl = root.optString("html_url", "$RELEASES_HOME_URL/tag/$tagName")
            val publishedAt = formatIsoTimestamp(root.optString("published_at", ""))

            val assets = root.optJSONArray("assets")
            var chosenApkAsset: RemoteAssetEntry? = null
            var manifestAssetUrl: String? = null
            val patchAssetsByName = LinkedHashMap<String, RemoteAssetEntry>()

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "").trim()
                    val url = asset.optString("browser_download_url", "").trim()
                    if (name.isEmpty() || url.isEmpty()) continue
                    val size = asset.optLong("size", 0L)
                    val digestHex = parseDigestSha256(asset.optString("digest", ""))
                    val entry = RemoteAssetEntry(name = name, url = url, sizeBytes = size, sha256 = digestHex)

                    when {
                        chosenApkAsset == null && name.endsWith(".apk", ignoreCase = true) -> {
                            chosenApkAsset = entry
                        }
                        name.equals(UPDATE_MANIFEST_FILE_NAME, ignoreCase = true) -> {
                            manifestAssetUrl = url
                        }
                        name.endsWith(".stpatch", ignoreCase = true) -> {
                            patchAssetsByName[name] = entry
                        }
                    }
                }
            }

            val apkAsset = chosenApkAsset ?: return null
            val sha256FromNotes =
                SHA256_NOTE_REGEX.find(rawNotes)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)
            var resolvedApkSha256 = apkAsset.sha256 ?: sha256FromNotes

            var matchedDelta: ApkDeltaPatchAsset? = null
            if (installedApk != null && isNewerVersion(versionName, BuildConfig.VERSION_NAME)) {
                var manifestLoaded = false
                if (!manifestAssetUrl.isNullOrBlank()) {
                    val manifestJson = runCatching { fetchSmallJsonWithRedirects(manifestAssetUrl) }.getOrNull()
                    if (manifestJson != null) {
                        manifestLoaded = true
                        if (resolvedApkSha256 == null) {
                            val manifestApkSha = manifestJson.optJSONObject("apk")
                                ?.optString("sha256", "")
                                ?.trim()
                                ?.lowercase(Locale.US)
                                ?.takeIf { it.length == 64 }
                            if (manifestApkSha != null) {
                                resolvedApkSha256 = manifestApkSha
                            }
                        }
                        matchedDelta = matchDeltaFromManifest(manifestJson, installedApk, patchAssetsByName)
                    }
                }

                // Fallback name match only if update-manifest.json could not be fetched
                if (!manifestLoaded && matchedDelta == null) {
                    val curNorm = normalizeVersionString(BuildConfig.VERSION_NAME)
                    val expectedPatchName = "SoundTracker-v$curNorm-to-$tagName.stpatch"
                    val byName = patchAssetsByName[expectedPatchName]
                    if (byName != null && byName.sizeBytes > 0L) {
                        matchedDelta = ApkDeltaPatchAsset(
                            fromVersion = curNorm,
                            patchAssetName = byName.name,
                            patchDownloadUrl = byName.url,
                            patchSizeBytes = byName.sizeBytes,
                            patchSha256 = byName.sha256,
                            sourceSizeBytes = 0L,
                            sourceSha256 = null
                        )
                    }
                }
            }

            val cleanedNotes = cleanReleaseNotesForDisplay(rawNotes)

            return GithubReleaseInfo(
                tagName = tagName,
                versionName = versionName,
                releaseTitle = releaseTitle,
                releaseNotes = cleanedNotes,
                publishedAtText = publishedAt,
                apkAssetName = apkAsset.name,
                apkDownloadUrl = apkAsset.url,
                apkSizeBytes = apkAsset.sizeBytes,
                apkSha256 = resolvedApkSha256,
                htmlReleaseUrl = htmlUrl,
                deltaPatch = matchedDelta
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchViaWebRedirectFallback(
        installedApk: ApkDeltaPatcher.InstalledApkInfo?
    ): GithubReleaseInfo? {
        val conn = (URL(RELEASES_WEB_LATEST).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_500
            readTimeout = 8_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "SoundTracker-Android/${BuildConfig.VERSION_NAME}")
        }
        val tagName: String
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            val location = conn.getHeaderField("Location") ?: return null
            if (!location.contains("/releases/tag/")) {
                return null
            }
            tagName = Uri.decode(location.substringAfter("/releases/tag/").substringBefore('?').trim('/'))
            if (tagName.isBlank()) return null
        } finally {
            conn.disconnect()
        }

        val versionName = normalizeVersionString(tagName)

        // Try update-manifest.json first so rate-limited clients still get full notes, SHA-256 & delta patch!
        val manifestUrl =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/$UPDATE_MANIFEST_FILE_NAME"
        val manifestJson = runCatching { fetchSmallJsonWithRedirects(manifestUrl) }.getOrNull()
        if (manifestJson != null) {
            val apkObj = manifestJson.optJSONObject("apk")
            val apkName = apkObj?.optString("name", "")?.trim().orEmpty()
            val apkUrl = apkObj?.optString("url", "")?.trim().orEmpty().ifBlank {
                if (apkName.isNotBlank()) {
                    "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/$apkName"
                } else {
                    ""
                }
            }
            if (apkUrl.isNotBlank()) {
                val apkSize = apkObj?.optLong("sizeBytes", 0L) ?: 0L
                val apkSha = apkObj?.optString("sha256", "")
                    ?.trim()
                    ?.lowercase(Locale.US)
                    ?.takeIf { it.length == 64 }
                val title = manifestJson.optString("releaseTitle", "").trim()
                    .ifBlank { "SoundTracker $tagName" }
                val rawNotes = manifestJson.optString("releaseNotes", "").trim()
                val publishedAt = formatIsoTimestamp(manifestJson.optString("publishedAt", ""))
                val matchedDelta = if (installedApk != null && isNewerVersion(versionName, BuildConfig.VERSION_NAME)) {
                    matchDeltaFromManifest(manifestJson, installedApk, emptyMap())
                } else {
                    null
                }
                return GithubReleaseInfo(
                    tagName = tagName,
                    versionName = versionName,
                    releaseTitle = title,
                    releaseNotes = if (rawNotes.isNotBlank()) {
                        cleanReleaseNotesForDisplay(rawNotes)
                    } else {
                        "可点击下方按钮直接下载更新，或在浏览器查看完整更新说明。"
                    },
                    publishedAtText = publishedAt,
                    apkAssetName = apkName.ifBlank { "SoundTracker-$tagName.apk" },
                    apkDownloadUrl = apkUrl,
                    apkSizeBytes = apkSize,
                    apkSha256 = apkSha,
                    htmlReleaseUrl = "$RELEASES_HOME_URL/tag/$tagName",
                    deltaPatch = matchedDelta
                )
            }
        }

        val candidateNames = listOf(
            "SoundTracker-$tagName.apk",
            "SoundTracker.apk",
            "app-release.apk"
        )
        for (assetName in candidateNames) {
            val candidateUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/$assetName"
            val size = probeRemoteAssetSize(candidateUrl)
            if (size != null && size >= 0L) {
                return GithubReleaseInfo(
                    tagName = tagName,
                    versionName = versionName,
                    releaseTitle = "SoundTracker $tagName",
                    releaseNotes = "可点击下方按钮直接下载更新，或在浏览器查看完整更新说明。",
                    publishedAtText = "",
                    apkAssetName = assetName,
                    apkDownloadUrl = candidateUrl,
                    apkSizeBytes = size,
                    apkSha256 = null,
                    htmlReleaseUrl = "$RELEASES_HOME_URL/tag/$tagName",
                    deltaPatch = null
                )
            }
        }
        return null
    }

    private fun matchDeltaFromManifest(
        manifestJson: JSONObject,
        installedApk: ApkDeltaPatcher.InstalledApkInfo,
        apiAssetsByName: Map<String, RemoteAssetEntry>
    ): ApkDeltaPatchAsset? {
        val patches = manifestJson.optJSONArray("patches") ?: return null
        val tagName = manifestJson.optString("tagName", "").trim()
        for (i in 0 until patches.length()) {
            val item = patches.optJSONObject(i) ?: continue
            val srcSha = item.optString("sourceSha256", "").trim().lowercase(Locale.US)
            val srcSize = item.optLong("sourceSizeBytes", 0L)
            if (srcSha.length != 64 || !srcSha.equals(installedApk.sha256, ignoreCase = true)) {
                continue
            }
            if (srcSize > 0L && srcSize != installedApk.sizeBytes) {
                continue
            }
            val patchName = item.optString("name", "").trim()
            if (patchName.isEmpty()) continue
            val apiAsset = apiAssetsByName[patchName]
            val patchUrl = apiAsset?.url
                ?: item.optString("url", "").trim().ifBlank {
                    if (tagName.isNotBlank()) {
                        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/$patchName"
                    } else {
                        ""
                    }
                }
            if (patchUrl.isBlank()) continue
            val patchSize = apiAsset?.sizeBytes?.takeIf { it > 0L } ?: item.optLong("patchSizeBytes", 0L)
            if (patchSize <= 0L) continue
            val patchSha = apiAsset?.sha256
                ?: item.optString("patchSha256", "").trim().lowercase(Locale.US).takeIf { it.length == 64 }
            val fromVer = item.optString("fromVersion", "")
                .ifBlank { normalizeVersionString(item.optString("fromTag", BuildConfig.VERSION_NAME)) }

            return ApkDeltaPatchAsset(
                fromVersion = fromVer,
                patchAssetName = patchName,
                patchDownloadUrl = patchUrl,
                patchSizeBytes = patchSize,
                patchSha256 = patchSha,
                sourceSizeBytes = srcSize,
                sourceSha256 = srcSha
            )
        }
        return null
    }

    private fun fetchSmallJsonWithRedirects(initialUrl: String): JSONObject? {
        var currentUrl = initialUrl
        repeat(6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_500
                readTimeout = 7_500
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SoundTracker-Android/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                when (code) {
                    in 200..299 -> {
                        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        return JSONObject(text)
                    }
                    301, 302, 303, 307, 308 -> {
                        val loc = conn.getHeaderField("Location") ?: return null
                        currentUrl = URL(URL(currentUrl), loc).toString()
                    }
                    else -> return null
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun parseDigestSha256(rawDigest: String): String? {
        if (!rawDigest.startsWith("sha256:", ignoreCase = true)) return null
        val hex = rawDigest.substringAfter(':').trim().lowercase(Locale.US)
        return hex.takeIf { it.length == 64 }
    }

    private fun probeRemoteAssetSize(url: String): Long? {
        var currentUrl = url
        repeat(5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 6_000
                readTimeout = 6_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SoundTracker-Android/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                when (code) {
                    in 200..299 -> return conn.contentLengthLong.coerceAtLeast(0L)
                    301, 302, 303, 307, 308 -> {
                        val loc = conn.getHeaderField("Location") ?: return null
                        currentUrl = URL(URL(currentUrl), loc).toString()
                    }
                    else -> return null
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /**
     * Downloads and prepares the release `.apk`:
     * 1. When [GithubReleaseInfo.isIncrementalAvailable] is true and the local `base.apk` matches,
     *    downloads only the compact `.stpatch` delta (~1% of full APK size) and reconstructs the
     *    bit-exact signed target `.apk` locally with SHA-256 verification.
     * 2. If delta download or local patching fails for any non-cancellation reason, seamlessly falls
     *    back to downloading the full `.apk`.
     */
    suspend fun downloadReleaseApk(
        context: Context,
        release: GithubReleaseInfo,
        channel: UpdateDownloadChannel,
        onStageChanged: (isDelta: Boolean, isMerging: Boolean, mergeFraction: Float) -> Unit = { _, _, _ -> },
        onProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val finalFile = targetApkFile(context, release)

        try {
            // Fast path if full target APK is already completely downloaded/reconstructed and verified
            if (finalFile.exists() && (release.apkSizeBytes <= 0L || finalFile.length() == release.apkSizeBytes)) {
                val expectedSha = release.apkSha256
                val hashOk = expectedSha == null ||
                    computeFileSha256(finalFile).equals(expectedSha, ignoreCase = true)
                if (hashOk) {
                    onStageChanged(false, false, 1f)
                    onProgress(finalFile.length(), finalFile.length(), 0L)
                    return@withContext Result.success(finalFile)
                } else {
                    finalFile.delete()
                }
            }

            // Incremental Delta Update fast path
            val patch = release.deltaPatch
            if (patch != null && release.isIncrementalAvailable) {
                val installedApk = ApkDeltaPatcher.getInstalledApkInfo(context)
                val baseMatches = installedApk != null &&
                    (patch.sourceSizeBytes <= 0L || installedApk.sizeBytes == patch.sourceSizeBytes) &&
                    (patch.sourceSha256.isNullOrBlank() || installedApk.sha256.equals(patch.sourceSha256, ignoreCase = true))

                if (installedApk != null && baseMatches) {
                    val patchFile = targetPatchFile(context, release)
                    val patchPartFile = File(patchFile.parentFile, "${patchFile.name}.part")
                    val mergedTempFile = File(finalFile.parentFile, "${finalFile.name}.merged.tmp")

                    try {
                        onStageChanged(true, false, 0f)
                        onProgress(0L, patch.patchSizeBytes, 0L)

                        val patchUrls = when (channel) {
                            UpdateDownloadChannel.DIRECT_GITHUB -> listOf(patch.patchDownloadUrl)
                            UpdateDownloadChannel.MIRROR_ACCELERATED ->
                                MIRROR_PREFIXES.map { it + patch.patchDownloadUrl } + patch.patchDownloadUrl
                        }

                        downloadVerifiedAsset(
                            candidateUrls = patchUrls,
                            finalAssetFile = patchFile,
                            partFile = patchPartFile,
                            expectedSizeBytes = patch.patchSizeBytes,
                            expectedSha256 = patch.patchSha256,
                            allowRangeResumeOnFirst = channel == UpdateDownloadChannel.DIRECT_GITHUB,
                            assetLabel = "增量差分包",
                            onProgress = onProgress
                        )

                        val outerJob = coroutineContext.job
                        outerJob.ensureActive()
                        onStageChanged(true, true, 0f)

                        ApkDeltaPatcher.applyPatch(
                            baseApk = installedApk,
                            patchFile = patchFile,
                            outputApkTempFile = mergedTempFile,
                            expectedTargetSize = release.apkSizeBytes,
                            expectedTargetSha256 = release.apkSha256,
                            checkCancelled = {
                                outerJob.ensureActive()
                            },
                            onMergeProgress = { written, total ->
                                val frac = if (total > 0L) {
                                    (written.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                onStageChanged(true, true, frac)
                            }
                        )

                        if (finalFile.exists()) finalFile.delete()
                        if (!mergedTempFile.renameTo(finalFile)) {
                            mergedTempFile.copyTo(finalFile, overwrite = true)
                            mergedTempFile.delete()
                        }
                        runCatching { patchFile.delete() }
                        runCatching { patchPartFile.delete() }
                        onStageChanged(true, false, 1f)
                        return@withContext Result.success(finalFile)
                    } catch (ce: CancellationException) {
                        runCatching { mergedTempFile.delete() }
                        throw ce
                    } catch (_: Exception) {
                        // Clean up partial delta artifacts and fall back seamlessly to full APK download
                        runCatching { patchFile.delete() }
                        runCatching { patchPartFile.delete() }
                        runCatching { mergedTempFile.delete() }
                    }
                }
            }

            // Full APK Download path (primary when no matching delta exists, or automatic fallback)
            val partFile = File(finalFile.parentFile, "${finalFile.name}.part")
            onStageChanged(false, false, 0f)
            onProgress(0L, release.apkSizeBytes, 0L)

            val fullUrls = when (channel) {
                UpdateDownloadChannel.DIRECT_GITHUB -> listOf(release.apkDownloadUrl)
                UpdateDownloadChannel.MIRROR_ACCELERATED -> MIRROR_PREFIXES.map { it + release.apkDownloadUrl }
            }

            downloadVerifiedAsset(
                candidateUrls = fullUrls,
                finalAssetFile = finalFile,
                partFile = partFile,
                expectedSizeBytes = release.apkSizeBytes,
                expectedSha256 = release.apkSha256,
                allowRangeResumeOnFirst = channel == UpdateDownloadChannel.DIRECT_GITHUB,
                assetLabel = "安装包",
                onProgress = onProgress
            )

            Result.success(finalFile)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun downloadVerifiedAsset(
        candidateUrls: List<String>,
        finalAssetFile: File,
        partFile: File,
        expectedSizeBytes: Long,
        expectedSha256: String?,
        allowRangeResumeOnFirst: Boolean,
        assetLabel: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
    ) {
        if (finalAssetFile.exists() &&
            (expectedSizeBytes <= 0L || finalAssetFile.length() == expectedSizeBytes)
        ) {
            val hashOk = expectedSha256 == null ||
                computeFileSha256(finalAssetFile).equals(expectedSha256, ignoreCase = true)
            if (hashOk) {
                onProgress(finalAssetFile.length(), finalAssetFile.length(), 0L)
                return
            } else {
                finalAssetFile.delete()
            }
        }

        var lastError: Exception? = null
        candidateUrls.forEachIndexed { idx, candidateUrl ->
            coroutineContext.ensureActive()
            try {
                streamDownloadWithRedirects(
                    initialUrl = candidateUrl,
                    partFile = partFile,
                    expectedTotalBytes = expectedSizeBytes,
                    allowRangeResume = allowRangeResumeOnFirst && idx == 0,
                    onProgress = onProgress
                )

                if (expectedSizeBytes > 0L && partFile.length() != expectedSizeBytes) {
                    val actual = partFile.length()
                    partFile.delete()
                    throw IllegalStateException("${assetLabel}大小不完整（已收 $actual 字节，预期 $expectedSizeBytes 字节）")
                }

                if (expectedSha256 != null) {
                    val actualSha = computeFileSha256(partFile)
                    if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                        partFile.delete()
                        throw IllegalStateException("${assetLabel} SHA-256 校验未通过，已自动丢弃损坏文件，请重试或切换下载通道")
                    }
                }

                if (finalAssetFile.exists()) finalAssetFile.delete()
                if (!partFile.renameTo(finalAssetFile)) {
                    partFile.copyTo(finalAssetFile, overwrite = true)
                    partFile.delete()
                }
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw (lastError ?: IllegalStateException("下载${assetLabel}失败"))
    }

    private suspend fun streamDownloadWithRedirects(
        initialUrl: String,
        partFile: File,
        expectedTotalBytes: Long,
        allowRangeResume: Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
    ) {
        val existingBytes = if (allowRangeResume && partFile.exists()) {
            val len = partFile.length()
            if (expectedTotalBytes > 0L && len >= expectedTotalBytes) {
                partFile.delete()
                0L
            } else {
                len
            }
        } else {
            if (partFile.exists()) partFile.delete()
            0L
        }

        var currentUrl = initialUrl
        var activeConn: HttpURLConnection? = null

        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                runCatching { activeConn?.disconnect() }
            }
        }

        try {
            var redirectCount = 0
            while (true) {
                coroutineContext.ensureActive()
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12_000
                    readTimeout = 25_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "SoundTracker-Android/${BuildConfig.VERSION_NAME}")
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }
                activeConn = conn

                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank() || redirectCount >= 6) {
                        throw IllegalStateException("下载链接重定向异常")
                    }
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    redirectCount++
                    continue
                }

                val isResuming = (code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L)
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IllegalStateException("下载服务器返回错误 (HTTP $code)")
                }

                val contentLen = conn.contentLengthLong
                val totalBytes = when {
                    expectedTotalBytes > 0L -> expectedTotalBytes
                    isResuming && contentLen > 0L -> existingBytes + contentLen
                    contentLen > 0L -> contentLen
                    else -> 0L
                }

                var downloaded = if (isResuming) existingBytes else 0L
                onProgress(downloaded, totalBytes, 0L)

                var lastReportNs = System.nanoTime()
                var lastReportBytes = downloaded
                var smoothedSpeed = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(partFile, isResuming).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            downloaded += read

                            val nowNs = System.nanoTime()
                            val elapsedNs = nowNs - lastReportNs
                            if (elapsedNs >= 160_000_000L) {
                                val deltaBytes = downloaded - lastReportBytes
                                val instantSpeed = (deltaBytes * 1_000_000_000L) / elapsedNs
                                smoothedSpeed = if (smoothedSpeed == 0L) {
                                    instantSpeed
                                } else {
                                    (smoothedSpeed * 6L + instantSpeed * 4L) / 10L
                                }
                                lastReportNs = nowNs
                                lastReportBytes = downloaded
                                onProgress(downloaded, totalBytes, smoothedSpeed)
                            }
                        }
                        output.fd.sync()
                    }
                }
                onProgress(downloaded, totalBytes, smoothedSpeed)
                conn.disconnect()
                break
            }
        } finally {
            cancelHandle.dispose()
            runCatching { activeConn?.disconnect() }
        }
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Semantic version comparison supporting `v2.7.0`, `2.10.1`, `2.7.0+9`, etc.
     * Returns true strictly when [remoteVersion] > [localVersion].
     */
    fun isNewerVersion(remoteVersion: String, localVersion: String): Boolean {
        val rParts = parseVersionNumbers(remoteVersion)
        val lParts = parseVersionNumbers(localVersion)
        if (rParts.isEmpty() || lParts.isEmpty()) return false
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val rv = rParts.getOrElse(i) { 0 }
            val lv = lParts.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    fun normalizeVersionString(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    private fun parseVersionNumbers(raw: String): List<Int> {
        val core = normalizeVersionString(raw)
            .substringBefore('-')
            .replace('+', '.')
        return core.split('.')
            .mapNotNull { seg -> seg.filter { it.isDigit() }.toIntOrNull() }
    }

    private fun cleanReleaseNotesForDisplay(raw: String): String {
        val stripped = raw.lines()
            .filterNot { line -> SHA256_NOTE_REGEX.containsMatchIn(line) }
            .joinToString("\n")
            .trim()
        return stripped.ifBlank { "常规体验优化与问题修复。" }
    }

    private fun formatIsoTimestamp(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            val instant = Instant.parse(iso)
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }.getOrDefault("")
    }

    fun canRequestInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildUnknownSourcesSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun buildInstallApkIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildBrowserIntent(url: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
