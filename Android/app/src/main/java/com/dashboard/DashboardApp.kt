package com.dashboard

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.dashboard.core.ConnectionManager
import com.dashboard.core.GoogleAccountSync
import com.dashboard.core.SettingsStore

/**
 * Global application container. Holds the [ConnectionManager],
 * [GoogleAccountSync] and [SettingsStore] shared by all screens.
 *
 * App ID on the Play Store: com.penbridge.tablet
 * Source namespace: com.dashboard
 */
class DashboardApp : Application() {
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val google: GoogleAccountSync by lazy { GoogleAccountSync(this) }
    val connection: ConnectionManager by lazy { ConnectionManager(this, google) }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("dashboard", MODE_PRIVATE)
    }

    /**
     * Short haptic pulse using [VibrationEffect] (API 26+, matches minSdk).
     * Uses [VibratorManager] on API 31+ to avoid deprecated [Vibrator] access.
     */
    fun vibrate(ms: Long = 12) {
        try {
            val effect = VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VibratorManager::class.java))
                    ?.defaultVibrator
                    ?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as? Vibrator)
                    ?.takeIf { it.hasVibrator() }
                    ?.vibrate(effect)
            }
        } catch (_: Exception) {}
    }
}