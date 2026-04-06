package com.bongbee.apkurl

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val API_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_TIMEOUT_MS = 120_000

    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val releasesApiUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
            connection = (URL(releasesApiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = API_TIMEOUT_MS
                readTimeout = API_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "apkurl-android/${BuildConfig.VERSION_NAME}")
                instanceFollowRedirects = true
            }
            connection.connect()

            if (connection.responseCode != 200) {
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)

            val tagName = json.getString("tag_name")
            val assets = json.optJSONArray("assets")
            val downloadUrl = findBestDownloadUrl(assets) ?: json.optString("html_url", "")
            if (downloadUrl.isBlank()) {
                return@withContext null
            }
            val releaseNotes = json.optString("body", "No release notes available")

            UpdateInfo(
                versionTag = tagName,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes,
                publishedAt = json.optString("published_at", "")
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun findBestDownloadUrl(assets: org.json.JSONArray?): String? {
        if (assets == null || assets.length() == 0) return null

        var fallback: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val url = asset.optString("browser_download_url", "")
            if (url.isBlank()) continue
            if (url.endsWith(".apk", ignoreCase = true)) return url
            if (fallback == null) fallback = url
        }
        return fallback
    }

    fun isUpdateAvailable(context: Context, latestVersion: String): Boolean {
        return try {
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "0.0.0"
            compareVersions(latestVersion, currentVersion) > 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Download APK from GitHub release with redirect support.
     * [onProgress] called with (bytesDownloaded, totalBytes) — totalBytes may be -1 if unknown.
     */
    suspend fun downloadUpdateApk(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val destination = File(context.cacheDir, "updates/${safeTag(updateInfo.versionTag)}.apk")
        destination.parentFile?.mkdirs()
        // Delete stale partial download
        if (destination.exists()) destination.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(updateInfo.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = DOWNLOAD_TIMEOUT_MS
                readTimeout = DOWNLOAD_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "apkurl-android/${BuildConfig.VERSION_NAME}")
                instanceFollowRedirects = true
            }
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext null
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloadedBytes += n
                        onProgress?.invoke(downloadedBytes, totalBytes)
                    }
                }
            }

            // Sanity: file should be a reasonable APK size
            if (destination.length() < 10_000) {
                destination.delete()
                return@withContext null
            }

            destination
        } catch (_: Exception) {
            destination.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun launchInAppInstaller(context: Context, apkFile: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            context.startActivity(installIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun safeTag(tag: String): String {
        return tag.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            .ifBlank { "latest" }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.trim().removePrefix("v").split(".")
        val parts2 = v2.trim().removePrefix("v").split(".")
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val num1 = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val num2 = parts2.getOrNull(i)?.toIntOrNull() ?: 0
            if (num1 != num2) return num1.compareTo(num2)
        }
        return 0
    }

    data class UpdateInfo(
        val versionTag: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val publishedAt: String
    )
}
