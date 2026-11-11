package com.branchcam.xposed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import com.branchcam.hook.LicenseManager
import org.json.JSONArray
import java.io.FileNotFoundException

/** Opens the companion's persisted document for an explicitly selected target app. */
class SelectedVideoProvider : ContentProvider() {
    companion object {
        val BRIDGE_URI: Uri = Uri.parse("content://com.branchcam.xposed.selectedvideo/video")

        fun syncGrants(ctx: Context) {
            ctx.revokeUriPermission(BRIDGE_URI, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val prefs = Prefs.get(ctx)
            val targets = try { JSONArray(prefs.getString(Prefs.KEY_APPS, "[]") ?: "[]") }
                          catch (_: Exception) { return }
            for (index in 0 until targets.length()) {
                try {
                    val target = targets.getString(index)
                    if (prefs.getString("video_$target", null) == null &&
                        prefs.getString(Prefs.KEY_VIDEO, null) == null) continue
                    ctx.grantUriPermission(target, BRIDGE_URI,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                    // A removed target app cannot receive a grant.
                }
            }
        }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("Companion unavailable")
        if (uri.path != "/video" || mode != "r") throw FileNotFoundException("Unsupported request")

        val prefs = Prefs.get(ctx)
        if (!prefs.getBoolean(Prefs.KEY_ENABLED, false) || !LicenseManager.verifyFromApp(ctx).valid)
            throw FileNotFoundException("BranchCam is disabled")

        // A global target setting never exposes a document to every installed app.
        // The owner must select this particular target in the existing app picker.
        val targets = try { JSONArray(prefs.getString(Prefs.KEY_APPS, "[]") ?: "[]") }
                      catch (_: Exception) { throw FileNotFoundException("Invalid target list") }
        val callers = ctx.packageManager.getPackagesForUid(Binder.getCallingUid()) ?: emptyArray()
        val caller = callers.firstOrNull { candidate ->
            (0 until targets.length()).any { targets.optString(it) == candidate }
        } ?: throw SecurityException("Caller is not an explicit BranchCam target")

        val selected = prefs.getString("video_$caller", null)
            ?: prefs.getString(Prefs.KEY_VIDEO, null)
            ?: throw FileNotFoundException("No video selected")
        val document = Uri.parse(selected)
        if (document.scheme != "content") throw FileNotFoundException("Unsupported media URI")
        return ctx.contentResolver.openFileDescriptor(document, "r")
            ?: throw FileNotFoundException("Selected video unavailable")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = if (uri.path == "/video") "video/*" else null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
