package com.example.pixelproximity

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Captures uncaught exceptions to a file so we can read the stack trace in-app
 * (handy when there's no adb/logcat available). Install once from App.onCreate.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val text = buildString {
                    append("Thread: ${thread.name}\n\n")
                    append(Log.getStackTraceString(throwable))
                }
                File(appContext.filesDir, FILE).writeText(text)
            } catch (_: Throwable) { /* ignore */ }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        return if (f.exists()) f.readText() else null
    }

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE).delete()
    }
}
