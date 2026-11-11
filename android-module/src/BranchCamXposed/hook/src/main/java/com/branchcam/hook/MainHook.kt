package com.branchcam.hook

import android.annotation.SuppressLint
import android.provider.Settings
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * BranchCam LSPosed Module — entry point.
 *
 * Hook chain:
 *   handleLoadPackage
 *     → license check (gate — BASIC / PRO / NONE)
 *     → per-app targeting check
 *     → Camera2Hook.install()
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG          = "BranchCam"
        const val PREFS_PKG    = "com.branchcam.xposed"
        const val PREFS_FILE   = "branchcam_prefs"
        const val KEY_ENABLED  = "hook_enabled"
        const val KEY_VIDEO    = "video_uri"
        const val KEY_APPS     = "target_apps"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == PREFS_PKG) return

        // Android 14+ compatible XSharedPreferences
        // LSPosed handles cross-process access via its own mechanism
        val prefs = XSharedPreferences(PREFS_PKG, PREFS_FILE)
        // makeWorldReadable() is a no-op on Android 14+ but LSPosed
        // grants cross-process read access automatically for modules
        @Suppress("DEPRECATION")
        try { prefs.makeWorldReadable() } catch (_: Exception) {}

        // Force reload to get latest values
        prefs.reload()

        if (!prefs.getBoolean(KEY_ENABLED, false)) return

        // ── License gate ───────────────────────────────────────────────────
        val androidId = getAndroidId(lpparam)
        val license   = LicenseManager.verify(prefs, androidId)

        if (!license.valid) {
            XposedBridge.log("[$TAG] License invalid (${license.error}) — hooks blocked for ${lpparam.packageName}")
            return
        }

        XposedBridge.log("[$TAG] License OK — tier=${license.tier} pkg=${lpparam.packageName}")

        // ── Per-app targeting ──────────────────────────────────────────────
        val targetAppsJson = prefs.getString(KEY_APPS, "[]") ?: "[]"
        if (!shouldHook(lpparam.packageName, targetAppsJson)) return

        // ── Resolve video for this app ─────────────────────────────────────
        val videoUri = if (license.tier == LicenseManager.Tier.PRO) {
            PerAppConfig.getVideoForPackage(prefs, lpparam.packageName)
        } else {
            prefs.getString(KEY_VIDEO, "") ?: ""
        }

        XposedBridge.log("[$TAG] Hooking camera for: ${lpparam.packageName} [${license.tier}]")
        Camera2Hook(lpparam.classLoader, videoUri).install()
    }

    private fun shouldHook(packageName: String, targetAppsJson: String): Boolean {
        val trimmed = targetAppsJson.trim()
        if (trimmed == "[]" || trimmed.isEmpty()) return true
        return trimmed.contains("\"$packageName\"")
    }

    /**
     * Read ANDROID_ID from prefs — companion app writes it on each launch.
     * Avoids needing a Context inside the hooked process.
     */
    private fun getAndroidId(lpparam: XC_LoadPackage.LoadPackageParam): String {
        return try {
            val p = XSharedPreferences(PREFS_PKG, PREFS_FILE)
            p.reload()
            p.getString("device_id", "") ?: ""
        } catch (_: Exception) { "" }
    }
}
