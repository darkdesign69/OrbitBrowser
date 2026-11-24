package com.redowan.orbit

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class OrbitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyNightMode(Prefs(this).int(K.THEME))
        // keep the stack trace of a crash; MainActivity shows it on the next start
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { java.io.File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e)) } catch (_: Exception) {}
            old?.uncaughtException(t, e)
        }
    }

    companion object {
        /** The saved crash trace (once), or null. */
        fun takeCrash(c: android.content.Context): String? {
            val f = java.io.File(c.filesDir, "crash.txt")
            if (!f.exists()) return null
            val t = try { f.readText() } catch (_: Exception) { null }
            f.delete()
            return t?.takeIf { it.isNotBlank() }
        }

        /** 0 = follow system, 1 = light, 2 = dark */
        fun applyNightMode(choice: Int) {
            AppCompatDelegate.setDefaultNightMode(
                when (choice) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
