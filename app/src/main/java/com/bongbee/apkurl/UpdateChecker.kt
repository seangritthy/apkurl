package com.bongbee.apkurl

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val TIMEOUT_MS = 10000

    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val releasesApiUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
            val connection = URL(releasesApiUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "apkurl-android")
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
                publishedAt = json.getString("published_at")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun findBestDownloadUrl(assets: org.json.JSONArray?): String? {
        if (assets == null || assets.length() == 0) {
            return null
        }

        var fallback: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val url = asset.optString("browser_download_url", "")
            if (url.isBlank()) {
                continue
            }

            if (url.endsWith(".apk", ignoreCase = true)) {
                return url
            }

            if (fallback == null) {
                fallback = url
            }
        }

        return fallback
    }

    fun isUpdateAvailable(context: Context, latestVersion: String): Boolean {
        try {
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "0.0.0"

            return compareVersions(latestVersion, currentVersion) > 0
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
    }

    fun showUpdateDialog(context: Context, updateInfo: UpdateInfo) {
        AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setMessage("Version ${updateInfo.versionTag}\n\n${updateInfo.releaseNotes}")
            .setPositiveButton("Download") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
                context.startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.trim().removePrefix("v").split(".")
        val parts2 = v2.trim().removePrefix("v").split(".")

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val num1 = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val num2 = parts2.getOrNull(i)?.toIntOrNull() ?: 0

            if (num1 != num2) {
                return num1.compareTo(num2)
            }
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

