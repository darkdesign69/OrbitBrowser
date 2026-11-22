package com.redowan.orbit

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class OrbitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyNightMode(Prefs(this).int(K.THEME))
    }

    companion object {
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
