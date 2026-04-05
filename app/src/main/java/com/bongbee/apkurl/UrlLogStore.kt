package com.bongbee.apkurl

import android.content.Context
import java.io.File

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
        if (!file.exists()) {
            return emptyList()
        }

        return file.readLines().filter { it.isNotBlank() }
    }

    @Synchronized
    fun clear(context: Context) {
        context.deleteFile(LOG_FILE_NAME)
    }
}

