package com.example.neareststationnotifier

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val FILE_NAME = "crash.txt"

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val header = "=== CRASH $ts ===\nThread: ${thread.name}\n"
                val stack = android.util.Log.getStackTraceString(throwable)
                val text = header + stack + "\n\n"

                val file = File(context.filesDir, FILE_NAME)
                file.appendText(text)
            } catch (_: Exception) {
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun read(context: Context): String? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }
}
