package com.branchcam.hook

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * AadhaarHook — bypass layer for Aadhaar Face RD (in.gov.uidai.facerd)
 * and any UIDAI auth app that uses the same security stack.
 *
 * What this patches:
 *
 *  1. NativeSecurity.checkAdbSecure  → always returns 0 (ADB not detected)
 *  2. NativeSecurity.checkRootSecure → always returns 0 (root not detected)
 *  3. Location.isFromMockProvider    → always returns false (no mock flag)
 *  4. PackageManager queries         → hides known virtual-camera packages
 *     (DroidCam, OBS Camera, KVirtualCamera, Streamlabs) from app's view
 *
 * Camera frame injection is handled by Camera2Hook — this file only deals
 * with the security / detection layers.
 */
class AadhaarHook(private val classLoader: ClassLoader) {

    private val tag = MainHook.TAG

    /** Install all hooks. Called from MainHook when UIDAI package is loaded. */
    fun install() {
        hookNativeSecurity()
        hookMockLocation()
        hookPackageManagerForVirtualCam()
    }

    // ── 1. Native security hooks ───────────────────────────────────────────

    /**
     * libsecurity_check.so exposes two JNI methods via NativeSecurity:
     *   checkAdbSecure()  → int  (0 = safe, non-0 = ADB detected)
     *   checkRootSecure() → int  (0 = safe, non-0 = root detected)
     *
     * We replace both with a simple return-0.
     */
    private fun hookNativeSecurity() {
        val nativeSecurityClass = "in.gov.uidai.security.security.NativeSecurity"

        listOf("checkAdbSecure", "checkRootSecure").forEach { methodName ->
            try {
                XposedHelpers.findAndHookMethod(
                    nativeSecurityClass,
                    classLoader,
                    methodName,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any = 0
                    }
                )
                XposedBridge.log("[$tag] Aadhaar: hooked $methodName → 0")
            } catch (e: Throwable) {
                XposedBridge.log("[$tag] Aadhaar: $methodName hook failed: ${e.message}")
            }
        }

        // Also hook the companion Java method that wraps the native call,
        // in case there's an extra Java-side check layer.
        try {
            XposedHelpers.findAndHookMethod(
                "in.gov.uidai.security.SecurityActivity",
                classLoader,
                "isDeviceSecure",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = true
                }
            )
            XposedBridge.log("[$tag] Aadhaar: hooked isDeviceSecure → true")
        } catch (_: Throwable) { /* method may not exist in all versions */ }
    }

    // ── 2. Mock location hook ──────────────────────────────────────────────

    /**
     * android.location.Location.isFromMockProvider() is what the geolocator
     * plugin (and CameraX location path) calls to flag a mock position.
     * We always return false so the app never sees the mock flag.
     */
    private fun hookMockLocation() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "isFromMockProvider",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )
            XposedBridge.log("[$tag] Aadhaar: hooked Location.isFromMockProvider → false")
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] Aadhaar: isFromMockProvider hook failed: ${e.message}")
        }

        // Android 12+ added isMock as a separate field/getter — patch that too
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "isMock",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )
            XposedBridge.log("[$tag] Aadhaar: hooked Location.isMock → false")
        } catch (_: Throwable) { /* isMock added in API 31; not present on older devices */ }
    }

    // ── 3. PackageManager hooks — hide virtual camera apps ─────────────────

    /**
     * The app scans installed packages looking for virtual-camera apps:
     *   com.dev47apps.droidcam
     *   com.dev47apps.obs_camera
     *   com.iqsoft.droidcamx
     *   com.kvirtualcamera
     *   com.streamlabs.virtualcamera
     *
     * We hook the three PackageManager query methods the app likely uses
     * and strip those packages from every result.
     */
    private fun hookPackageManagerForVirtualCam() {
        hookGetInstalledPackages()
        hookGetInstalledApplications()
        hookGetApplicationInfo()
        hookGetPackageInfo()
    }

    private fun hookGetInstalledPackages() {
        try {
            // getInstalledPackages(int flags)
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                classLoader,
                "getInstalledPackages",
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = (param.result as? List<PackageInfo>) ?: return
                        param.result = list.filter { !isVirtualCamPkg(it.packageName) }
                    }
                }
            )
            XposedBridge.log("[$tag] Aadhaar: hooked getInstalledPackages")
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] Aadhaar: getInstalledPackages hook failed: ${e.message}")
        }
    }

    private fun hookGetInstalledApplications() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                classLoader,
                "getInstalledApplications",
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = (param.result as? List<ApplicationInfo>) ?: return
                        param.result = list.filter { !isVirtualCamPkg(it.packageName) }
                    }
                }
            )
            XposedBridge.log("[$tag] Aadhaar: hooked getInstalledApplications")
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] Aadhaar: getInstalledApplications hook failed: ${e.message}")
        }
    }

    private fun hookGetApplicationInfo() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                classLoader,
                "getApplicationInfo",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkgName = param.args[0] as? String ?: return
                        if (isVirtualCamPkg(pkgName)) {
                            // Throw NameNotFoundException so the app thinks it's not installed
                            param.throwable = PackageManager.NameNotFoundException(pkgName)
                        }
                    }
                }
            )
            XposedBridge.log("[$tag] Aadhaar: hooked getApplicationInfo")
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] Aadhaar: getApplicationInfo hook failed: ${e.message}")
        }
    }

    private fun hookGetPackageInfo() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                classLoader,
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkgName = param.args[0] as? String ?: return
                        if (isVirtualCamPkg(pkgName)) {
                            param.throwable = PackageManager.NameNotFoundException(pkgName)
                        }
                    }
                }
            )
            XposedBridge.log("[$tag] Aadhaar: hooked getPackageInfo")
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] Aadhaar: getPackageInfo hook failed: ${e.message}")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun isVirtualCamPkg(packageName: String?): Boolean {
        if (packageName == null) return false
        return VIRTUAL_CAM_PACKAGES.any { packageName.startsWith(it) }
    }

    companion object {
        /** Package prefixes for virtual-camera apps to hide from UIDAI apps. */
        val VIRTUAL_CAM_PACKAGES = setOf(
            "com.dev47apps.droidcam",
            "com.dev47apps.obs_camera",
            "com.iqsoft.droidcamx",
            "com.kvirtualcamera",
            "com.streamlabs.virtualcamera"
        )

        /** UIDAI / Aadhaar package prefixes — any package starting with these gets Aadhaar hooks. */
        val UIDAI_PACKAGE_PREFIXES = listOf(
            "in.gov.uidai",
            "com.uidai"
        )

        fun isUidaiPackage(packageName: String): Boolean =
            UIDAI_PACKAGE_PREFIXES.any { packageName.startsWith(it) }
    }
}
