package com.branchcam.xposed.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.branchcam.hook.LicenseManager
import com.branchcam.xposed.Prefs
import com.branchcam.xposed.SelectedVideoProvider
import com.branchcam.xposed.R
import com.branchcam.xposed.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { Prefs.get(this) }

    // ── Video picker launcher ──────────────────────────────────────────────
    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            Toast.makeText(this, "Unable to keep access to this video", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        prefs.edit().putString(Prefs.KEY_VIDEO, uri.toString()).apply()
        SelectedVideoProvider.syncGrants(this)
        refreshVideoUI(uri.toString())
        Toast.makeText(this, "Video selected ✓", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        // Store device ID for cross-process license check
        @SuppressLint("HardwareIds")
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        prefs.edit()
            .putString("device_id", androidId)
            .putBoolean("hook_active_self_test", false)
            .apply()

        setupStatusCard()
        setupToggle()
        setupVideoSection()
        setupLicenseCard()
        setupProFeatures()
        setupAppTargets()
    }

    override fun onResume() {
        super.onResume()
        SelectedVideoProvider.syncGrants(this)
        refreshStatusCard()
        refreshVideoUI(prefs.getString(Prefs.KEY_VIDEO, null))
        refreshAppCount()
        refreshLicenseCard()
        refreshProFeatures()
    }

    // ── Status card: check if module is active ─────────────────────────────

    private fun setupStatusCard() = refreshStatusCard()

    private fun refreshStatusCard() {
        // The legacy LSPosed callback writes this marker from our onResume
        // hook. A normal app launch cannot set it.
        val active = prefs.getBoolean("hook_active_self_test", false)
        if (active) {
            b.statusChip.text = "● Module Active"
            b.statusChip.setChipBackgroundColorResource(R.color.chip_active)
            b.statusDesc.text = "LSPosed is running BranchCam hooks"
        } else {
            b.statusChip.text = "○ Module Not Active"
            b.statusChip.setChipBackgroundColorResource(R.color.chip_inactive)
            b.statusDesc.text = "Enable BranchCam in LSPosed Manager → Modules"
        }
    }

    // ── Master toggle ──────────────────────────────────────────────────────

    private fun setupToggle() {
        val enabled = prefs.getBoolean(Prefs.KEY_ENABLED, false)
        b.switchEnable.isChecked = enabled
        updateToggleUI(enabled)

        b.switchEnable.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(Prefs.KEY_ENABLED, on).apply()
            updateToggleUI(on)
        }
    }

    private fun updateToggleUI(on: Boolean) {
        b.switchEnable.isChecked = on
        b.toggleLabel.text = if (on) "BranchCam Enabled" else "BranchCam Disabled"
        b.toggleSubtitle.text = if (on)
            "Camera feed replaced for targeted apps"
        else
            "Real camera will be used in all apps"
    }

    // ── Video section ──────────────────────────────────────────────────────

    private fun setupVideoSection() {
        b.btnPickVideo.setOnClickListener {
            checkPermAndPick()
        }
        b.btnClearVideo.setOnClickListener {
            prefs.edit().remove(Prefs.KEY_VIDEO).apply()
            SelectedVideoProvider.syncGrants(this)
            refreshVideoUI(null)
        }

        refreshVideoUI(prefs.getString(Prefs.KEY_VIDEO, null))
    }

    private fun checkPermAndPick() {
        launchVideoPicker()
    }

    private fun launchVideoPicker() = pickVideo.launch(arrayOf("video/*"))

    private fun refreshVideoUI(path: String?) {
        if (path.isNullOrEmpty()) {
            b.videoPreview.visibility    = View.GONE
            b.videoPlaceholder.visibility = View.VISIBLE
            b.videoFilename.text          = "No video — black screen fallback"
            b.btnClearVideo.isEnabled     = false
        } else {
            b.videoPreview.visibility    = View.VISIBLE
            b.videoPlaceholder.visibility = View.GONE
            b.videoFilename.text          = getVideoDisplayName(path)
            b.btnClearVideo.isEnabled     = true

            // Validate persisted URI access before handing it to VideoView.
            try {
                val uri = Uri.parse(path)
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { }
                    ?: throw IllegalStateException("Selected video is no longer available")
                b.videoPreview.setOnErrorListener { _, _, _ ->
                    b.videoPreview.visibility = View.GONE
                    b.videoPlaceholder.visibility = View.VISIBLE
                    b.videoFilename.text = "Selected video cannot be played"
                    true
                }
                b.videoPreview.setVideoURI(uri)
                b.videoPreview.start()
            } catch (_: Exception) {
                prefs.edit().remove(Prefs.KEY_VIDEO).apply()
                refreshVideoUI(null)
            }
        }
    }

    private fun getVideoDisplayName(path: String): String {
        val uri = Uri.parse(path)
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index)?.let { return it }
                    }
                }
            } catch (_: Exception) { }
        }
        return uri.lastPathSegment?.let(Uri::decode)?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() } ?: "Selected video"
    }

    // ── License card ──────────────────────────────────────────────────────

    private fun setupLicenseCard() {
        b.cardLicense.setOnClickListener {
            startActivity(Intent(this, LicenseActivity::class.java))
        }
        refreshLicenseCard()
    }

    private fun refreshLicenseCard() {
        val result = LicenseManager.verifyFromApp(this)
        b.licenseStatusShort.text = if (result.valid) {
            val expDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                .format(Date(result.expiryEpoch * 1000L))
            "✓ ${result.tier} — expires $expDate"
        } else {
            when (result.error) {
                "NO_KEY"  -> "Not activated — tap to enter key"
                "EXPIRED" -> "⚠ License expired — tap to renew"
                else      -> "Not activated (${result.error})"
            }
        }
    }

    // ── PRO features ──────────────────────────────────────────────────────

    private fun setupProFeatures() {
        // Audio passthrough
        b.switchAudio.isChecked = prefs.getBoolean("audio_passthrough", true)
        b.switchAudio.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("audio_passthrough", on).apply()
        }

        // Stealth mode (hide launcher icon)
        b.switchStealth.isChecked = prefs.getBoolean("stealth_mode", false)
        b.switchStealth.setOnCheckedChangeListener { _, on ->
            val isPro = LicenseManager.verifyFromApp(this).tier == LicenseManager.Tier.PRO
            if (!isPro) {
                b.switchStealth.isChecked = false
                Toast.makeText(this, "PRO license required for Stealth Mode", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("stealth_mode", on).apply()
            setLauncherIconVisible(!on)
            Toast.makeText(this, if (on) "Icon hidden — reopen via LSPosed" else "Icon restored", Toast.LENGTH_LONG).show()
        }

        refreshProFeatures()
    }

    private fun refreshProFeatures() {
        val isPro = LicenseManager.verifyFromApp(this).tier == LicenseManager.Tier.PRO
        b.switchAudio.isEnabled   = true          // available on all tiers
        b.switchStealth.isEnabled = isPro
        b.proLockText.visibility  = if (isPro) View.GONE else View.VISIBLE
    }

    private fun setLauncherIconVisible(visible: Boolean) {
        val state = if (visible)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(
            ComponentName(this, "${packageName}.ui.MainActivity"),
            state,
            PackageManager.DONT_KILL_APP
        )
    }

    // ── Per-app targeting ──────────────────────────────────────────────────

    private fun setupAppTargets() {
        b.cardApps.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        refreshAppCount()
    }

    private fun refreshAppCount() {
        val json = prefs.getString(Prefs.KEY_APPS, "[]") ?: "[]"
        val count = if (json == "[]") 0 else json.split("\"").count { it.contains(".") }
        b.appCountText.text = if (count == 0)
            "All apps (global mode)"
        else
            "$count app${if (count != 1) "s" else ""} targeted"
    }

    // ── Helpers ───────────────────────────────────────────────────────────

}
