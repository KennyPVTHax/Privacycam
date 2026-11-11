package com.branchcam.hook

import de.robv.android.xposed.XSharedPreferences

/**
 * Per-app configuration — PRO tier feature.
 *
 * Allows different video files for different apps.
 * Stored as separate prefs keys: "video_<packageName>"
 *
 * Falls back to global video if no per-app video set.
 */
object PerAppConfig {

    fun getVideoForPackage(prefs: XSharedPreferences, packageName: String): String {
        // Per-app override key
        val perAppKey = "video_$packageName"
        val perApp = prefs.getString(perAppKey, "")
        if (!perApp.isNullOrEmpty()) return perApp

        // Global fallback
        return prefs.getString(MainHook.KEY_VIDEO, "") ?: ""
    }

    fun getResolutionForPackage(prefs: XSharedPreferences, packageName: String): Resolution {
        val key = "res_$packageName"
        val global = "res_global"
        val value = prefs.getString(key, null)
            ?: prefs.getString(global, Resolution.ORIGINAL.name)
            ?: Resolution.ORIGINAL.name
        return try { Resolution.valueOf(value) } catch (_: Exception) { Resolution.ORIGINAL }
    }

    fun isAudioPassthroughEnabled(prefs: XSharedPreferences): Boolean =
        prefs.getBoolean("audio_passthrough", true)

    enum class Resolution(val label: String) {
        ORIGINAL("Original"),
        P480("480p"),
        P720("720p"),
        P1080("1080p")
    }
}
