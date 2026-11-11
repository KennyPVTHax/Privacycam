package com.branchcam.xposed.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.branchcam.hook.LicenseManager
import com.branchcam.xposed.Prefs
import com.branchcam.xposed.R
import com.branchcam.xposed.databinding.ActivityLicenseBinding
import java.text.SimpleDateFormat
import java.util.*

class LicenseActivity : AppCompatActivity() {

    private lateinit var b: ActivityLicenseBinding

    @SuppressLint("HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLicenseBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

        // Store device_id in prefs so the hook module can read it cross-process
        Prefs.get(this).edit().putString("device_id", androidId).apply()

        // Show device ID (for key generation)
        b.deviceIdText.text = androidId
        b.btnCopyId.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Device ID", androidId))
            Toast.makeText(this, "Device ID copied", Toast.LENGTH_SHORT).show()
        }

        // Activate button
        b.btnActivate.setOnClickListener { tryActivate(androidId) }
        b.keyInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) tryActivate(androidId)
            false
        }

        refreshLicenseStatus()
    }

    private fun tryActivate(androidId: String) {
        val key = b.keyInput.text?.toString()?.trim() ?: ""
        if (key.isEmpty()) {
            b.keyInput.error = "Enter a license key"
            return
        }

        // Save key to prefs
        Prefs.get(this).edit().putString(LicenseManager.KEY_LICENSE, key).apply()

        // Verify immediately
        val result = LicenseManager.verifyFromApp(this)
        if (result.valid) {
            Toast.makeText(this, "✓ License activated — ${result.tier}", Toast.LENGTH_LONG).show()
            b.keyInput.text?.clear()
            refreshLicenseStatus()
        } else {
            Prefs.get(this).edit().remove(LicenseManager.KEY_LICENSE).apply()
            val msg = when (result.error) {
                "DEVICE_MISMATCH" -> "This key is for a different device"
                "EXPIRED"         -> "License expired — renew your key"
                "DECRYPT_FAIL"    -> "Invalid key format"
                "BAD_FORMAT"      -> "Corrupt key — contact support"
                else              -> "Activation failed: ${result.error}"
            }
            b.licenseStatusText.text   = msg
            b.licenseStatusIcon.setImageResource(R.drawable.ic_error)
        }
    }

    private fun refreshLicenseStatus() {
        val result = LicenseManager.verifyFromApp(this)
        if (result.valid) {
            val expDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                .format(Date(result.expiryEpoch * 1000L))
            b.licenseStatusText.text = "✓ ${result.tier} — expires $expDate"
            b.licenseStatusIcon.setImageResource(R.drawable.ic_check)
            b.licenseTierChip.text = result.tier.name
            b.licenseTierChip.visibility = android.view.View.VISIBLE
        } else {
            b.licenseStatusText.text = when (result.error) {
                "NO_KEY"  -> "No license key entered"
                "EXPIRED" -> "License expired"
                else      -> "Not activated (${result.error})"
            }
            b.licenseStatusIcon.setImageResource(R.drawable.ic_error)
            b.licenseTierChip.visibility = android.view.View.GONE
        }
    }
}
