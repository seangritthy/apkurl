package com.bongbee.apkurl

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val GITHUB_REPO = "seang/apkurl"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val TIMEOUT_MS = 10000

    suspend fun checkForUpdates(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(RELEASES_API_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connect()

            if (connection.responseCode != 200) {
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)

            val tagName = json.getString("tag_name")
            val downloadUrl = json.getJSONArray("assets")
                .getJSONObject(0)
                .getString("browser_download_url")
            val releaseNotes = json.optString("body", "No release notes available")

            UpdateInfo(
                versionTag = tagName,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes,
                publishedAt = json.getString("published_at")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun isUpdateAvailable(context: Context, latestVersion: String): Boolean {
        try {
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "0.0.0"

            return compareVersions(latestVersion, currentVersion) > 0
        } catch (e: PackageManager.NameNotFoundException) {
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

