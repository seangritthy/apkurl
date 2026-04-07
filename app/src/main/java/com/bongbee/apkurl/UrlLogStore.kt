package com.bongbee.apkurl

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UrlLogStore {
    /**
     * Read all log rows for a specific package (or *ALL* for global).
     */
    @Synchronized
    fun readByPackage(context: Context, packageName: String): List<String> {
        val file = File(context.filesDir, LOG_FILE_NAME)
        if (!file.exists()) return emptyList()
        val allLines = file.readLines().filter { it.isNotBlank() }
        if (packageName == "*ALL*") return allLines
        return allLines.filter { it.split("|", limit = 3).getOrNull(1) == packageName }
    }

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

    @Synchronized
    fun exportToTxt(context: Context, uri: Uri): Boolean {
        return exportToTxtFiltered(context, uri) { true }
    }

    /**
     * Export captured URLs as a formatted .txt file with an optional filter.
     */
    @Synchronized
    fun exportToTxtFiltered(context: Context, uri: Uri, filter: (String) -> Boolean): Boolean {
        return try {
            val rows = readAll(context)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.appendLine("=== APK URL Extractor - Captured URLs ===")
                writer.appendLine("Exported: ${dateFormat.format(Date())}")
                
                val filteredRows = rows.filter { raw ->
                    val parts = raw.split("|", limit = 3)
                    if (parts.size >= 3) filter(parts[2]) else false
                }

                writer.appendLine("Total URLs: ${filteredRows.size}")
                writer.appendLine("=========================================")
                writer.appendLine()

                // Collect unique URLs
                val uniqueUrls = LinkedHashSet<String>()

                for (raw in filteredRows) {
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
