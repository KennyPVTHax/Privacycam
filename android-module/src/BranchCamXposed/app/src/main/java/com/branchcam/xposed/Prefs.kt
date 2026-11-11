package com.branchcam.xposed

import android.content.Context
import android.content.SharedPreferences
import com.branchcam.hook.MainHook

/**
 * Shared preferences that the hook module reads via XSharedPreferences.
 *
 * We write to MODE_WORLD_READABLE so LSPosed can cross-read them without
 * a ContentProvider.  On modern Android this triggers a lint warning but
 * LSPosed explicitly supports this pattern for Xposed prefs.
 */
object Prefs {

    private const val FILE = MainHook.PREFS_FILE

    @Suppress("DEPRECATION")
    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE)

    // Keys
    const val KEY_ENABLED = MainHook.KEY_ENABLED
    const val KEY_VIDEO   = MainHook.KEY_VIDEO
    const val KEY_APPS    = MainHook.KEY_APPS
}
