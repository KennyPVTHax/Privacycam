package com.branchcam.hook

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
// XposedBridge only available in hook process — use safe wrapper below
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BranchCam License Manager
 *
 * License format (after decryption):
 *   DEVICE_HASH|EXPIRY_EPOCH|TIER
 *   e.g. "a3f9b2c1d4e5f6a7b8c9d0e1f2a3b4c5|1769817600|PRO"
 *
 * TIER values: BASIC, PRO
 *   BASIC — core camera replacement only
 *   PRO   — per-app video, profiles, stealth mode, resolution control
 *
 * ══ IMPORTANT — Change AES_KEY and AES_IV before distributing ══
 * Generate new ones:
 *   python3 -c "import os,base64; print(base64.b64encode(os.urandom(32)).decode())"
 *   python3 -c "import os,base64; print(base64.b64encode(os.urandom(16)).decode())"
 */
object LicenseManager {

    // ── Obfuscated key bytes (XOR with 0x5A to get real bytes at runtime) ──
    // Replace these with YOUR own key/iv — xor each byte with 0x5A yourself
    // or run generate_keys.py which does it for you.
    //
    // Current value decodes to a placeholder key — CHANGE BEFORE SHIPPING.
    private val KEY_OBF = byteArrayOf(
        0x1A.toByte(), 0x0B.toByte(), 0x48.toByte(), 0x0E.toByte(),
        0x41.toByte(), 0x0F.toByte(), 0x17.toByte(), 0x4A.toByte(),
        0x0C.toByte(), 0x17.toByte(), 0x0D.toByte(), 0x48.toByte(),
        0x11.toByte(), 0x0C.toByte(), 0x48.toByte(), 0x17.toByte(),
        0x0D.toByte(), 0x48.toByte(), 0x11.toByte(), 0x0C.toByte(),
        0x17.toByte(), 0x0D.toByte(), 0x48.toByte(), 0x11.toByte(),
        0x1A.toByte(), 0x0B.toByte(), 0x48.toByte(), 0x0E.toByte(),
        0x41.toByte(), 0x0F.toByte(), 0x17.toByte(), 0x4A.toByte()
    ) // 32 bytes

    private val IV_OBF = byteArrayOf(
        0x2F.toByte(), 0x0A.toByte(), 0x48.toByte(), 0x0F.toByte(),
        0x41.toByte(), 0x0F.toByte(), 0x17.toByte(), 0x4A.toByte(),
        0x0C.toByte(), 0x17.toByte(), 0x0D.toByte(), 0x48.toByte(),
        0x11.toByte(), 0x0C.toByte(), 0x48.toByte(), 0x17.toByte()
    ) // 16 bytes

    private const val XK = 0x5A

    // ── Package integrity check — your package name goes here ──────────────
    // If someone repacks the APK under a different name, hooks won't fire.
    private val EXPECTED_PKG_OBF = byteArrayOf(
        0x19.toByte(), 0x0B.toByte(), 0x14.toByte(), 0x09.toByte(),
        0x0E.toByte(), 0x04.toByte(), 0x14.toByte(), 0x14.toByte(),
        0x17.toByte(), 0x0D.toByte(), // "com.branchcam" XOR 0x5A
        0x57.toByte(), 0x2E.toByte(), 0x28.toByte(), 0x0F.toByte(),
        0x07.toByte(), 0x07.toByte(), 0x0E.toByte(), 0x06.toByte()  // ".xposed" XOR 0x5A
    )

    // ── Tier enum ──────────────────────────────────────────────────────────
    enum class Tier { NONE, BASIC, PRO }

    data class LicenseResult(
        val valid: Boolean,
        val tier: Tier = Tier.NONE,
        val expiryEpoch: Long = 0L,
        val error: String = ""
    )

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Verifies the license key stored in prefs.
     * Called from [MainHook] in the hooked process context.
     * Returns [LicenseResult] — if !valid, hooks are NOT applied.
     */
    fun verify(prefs: de.robv.android.xposed.XSharedPreferences, androidId: String): LicenseResult {
        val encodedKey = prefs.getString(KEY_LICENSE, "") ?: ""
        if (encodedKey.isEmpty()) return LicenseResult(false, error = "NO_KEY")

        return try {
            val decrypted = decrypt(encodedKey) ?: return LicenseResult(false, error = "DECRYPT_FAIL")
            val parts = decrypted.split("|")
            if (parts.size < 3) return LicenseResult(false, error = "BAD_FORMAT")

            val deviceHash = parts[0]
            val expiry     = parts[1].toLongOrNull() ?: 0L
            val tierStr    = parts[2]

            // 1. Device binding check
            val myHash = hashDeviceId(androidId)
            if (deviceHash != myHash) return LicenseResult(false, error = "DEVICE_MISMATCH")

            // 2. Expiry check
            val now = System.currentTimeMillis() / 1000L
            if (now > expiry) return LicenseResult(false, error = "EXPIRED")

            val tier = when (tierStr.uppercase()) {
                "PRO"   -> Tier.PRO
                "BASIC" -> Tier.BASIC
                else    -> return LicenseResult(false, error = "BAD_TIER")
            }

            LicenseResult(true, tier, expiry)
        } catch (e: Throwable) {
            try { de.robv.android.xposed.XposedBridge.log("[BranchCam] License verify error: ${e.message}") } catch (_: Throwable) {}
            LicenseResult(false, error = "EXCEPTION")
        }
    }

    /**
     * Same logic, for use in the companion app (using regular context).
     */
    fun verifyFromApp(ctx: Context): LicenseResult {
        val prefs = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val encodedKey = prefs.getString(KEY_LICENSE, "") ?: ""
        if (encodedKey.isEmpty()) return LicenseResult(false, error = "NO_KEY")

        @SuppressLint("HardwareIds")
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

        return try {
            val decrypted = decrypt(encodedKey) ?: return LicenseResult(false, error = "DECRYPT_FAIL")
            val parts = decrypted.split("|")
            if (parts.size < 3) return LicenseResult(false, error = "BAD_FORMAT")

            val deviceHash = parts[0]
            val expiry     = parts[1].toLongOrNull() ?: 0L
            val tierStr    = parts[2]

            val myHash = hashDeviceId(androidId)
            if (deviceHash != myHash) return LicenseResult(false, error = "DEVICE_MISMATCH")

            val now = System.currentTimeMillis() / 1000L
            if (now > expiry) return LicenseResult(false, error = "EXPIRED")

            val tier = when (tierStr.uppercase()) {
                "PRO"   -> Tier.PRO
                "BASIC" -> Tier.BASIC
                else    -> return LicenseResult(false, error = "BAD_TIER")
            }

            LicenseResult(true, tier, expiry)
        } catch (e: Throwable) {
            LicenseResult(false, error = "EXCEPTION:${e.message}")
        }
    }

    // ── Crypto helpers ─────────────────────────────────────────────────────

    private fun decrypt(base64Ciphertext: String): String? {
        return try {
            val key = ByteArray(KEY_OBF.size) { (KEY_OBF[it].toInt() xor XK).toByte() }
            val iv  = ByteArray(IV_OBF.size)  { (IV_OBF[it].toInt()  xor XK).toByte() }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val raw = android.util.Base64.decode(base64Ciphertext, android.util.Base64.DEFAULT)
            String(cipher.doFinal(raw), Charsets.UTF_8)
        } catch (e: Throwable) { null }
    }

    fun hashDeviceId(androidId: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val salted = "BC_SALT_$androidId"
        val bytes = md.digest(salted.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    // ── Constants ──────────────────────────────────────────────────────────
    const val PREFS_FILE  = "branchcam_prefs"
    const val KEY_LICENSE = "license_key"
}
