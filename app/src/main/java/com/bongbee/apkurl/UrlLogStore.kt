package com.bongbee.apkurl

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UrlLogStore {

    private const val LOG_FILE_NAME = "captured_urls.log"

    @Synchronized
    fun append(context: Context, row: String) {
        context.openFileOutput(LOG_FILE_NAME, Context.MODE_APPEND).bufferedWriter().use {
            it.appendLine(row)
        }
    }

    @Synchronized
    fun readAll(context: Context): List<String> {
        val file = File(context.filesDir, LOG_FILE_NAME)
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }
    }

    @Synchronized
    fun clear(context: Context) {
        context.deleteFile(LOG_FILE_NAME)
    }

    /**
     * Export all captured URLs as a formatted .txt file.
     * Returns the Uri of the exported file, or null on failure.
     */
    @Synchronized
    fun exportToTxt(context: Context, uri: Uri): Boolean {
        return try {
            val rows = readAll(context)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.appendLine("=== APK URL Extractor - Captured URLs ===")
                writer.appendLine("Exported: ${dateFormat.format(Date())}")
                writer.appendLine("Total URLs: ${rows.size}")
                writer.appendLine("=========================================")
                writer.appendLine()

                // Collect unique URLs
                val uniqueUrls = LinkedHashSet<String>()

                for (raw in rows) {
                    val parts = raw.split("|", limit = 3)
                    if (parts.size >= 3) {
                        val timestamp = parts[0].toLongOrNull() ?: 0L
                        val pkg = parts[1]
                        val url = parts[2]
                        val timeText = if (timestamp > 0) dateFormat.format(Date(timestamp)) else "unknown"
                        writer.appendLine("[$timeText] $pkg")
                        writer.appendLine(url)
                        writer.appendLine()
                        uniqueUrls.add(url)
                    } else {
                        writer.appendLine(raw)
                        writer.appendLine()
                    }
                }

                // Summary of unique URLs at end
                writer.appendLine()
                writer.appendLine("=== Unique URLs (${uniqueUrls.size}) ===")
                for (url in uniqueUrls) {
                    writer.appendLine(url)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
