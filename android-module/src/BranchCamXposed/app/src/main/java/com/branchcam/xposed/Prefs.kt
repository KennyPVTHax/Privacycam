package com.branchcam.xposed

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import com.branchcam.hook.MainHook
import java.io.File
import java.io.FileInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Shared preferences that the hook module reads via XSharedPreferences.
 *
 * We write to MODE_WORLD_READABLE so LSPosed can cross-read them without
 * a ContentProvider.  On modern Android this triggers a lint warning but
 * LSPosed explicitly supports this pattern for Xposed prefs.
 */
object Prefs {

    private const val FILE = MainHook.PREFS_FILE

    // LSPosed's xposedsharedprefs extension grants cross-process reads when
    // this mode is requested. Stock Android rejects it, so retain the normal
    // private preference path on devices without a compatible framework.
    @Suppress("DEPRECATION")
    fun get(ctx: Context): SharedPreferences = try {
        val shared = ctx.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE)
        migratePrivatePrefs(ctx, shared)
        MirroredPrefs(shared, ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    } catch (_: SecurityException) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun migratePrivatePrefs(ctx: Context, dest: SharedPreferences) {
        val old = File(ctx.applicationInfo.dataDir, "shared_prefs/$FILE.xml")
        if (!old.isFile) return
        try {
            val edit = dest.edit()
            FileInputStream(old).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "UTF-8")
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    val name = parser.getAttributeValue(null, "name") ?: continue
                    if (dest.contains(name)) continue
                    when (parser.name) {
                        "string" -> edit.putString(name, parser.nextText())
                        "boolean" -> edit.putBoolean(name, parser.getAttributeValue(null, "value").toBoolean())
                        "int" -> parser.getAttributeValue(null, "value")?.toIntOrNull()?.let { edit.putInt(name, it) }
                        "long" -> parser.getAttributeValue(null, "value")?.toLongOrNull()?.let { edit.putLong(name, it) }
                        "float" -> parser.getAttributeValue(null, "value")?.toFloatOrNull()?.let { edit.putFloat(name, it) }
                    }
                }
            }
            edit.commit()
        } catch (_: Exception) {
            // Retain the original file and retry on the next launch.
        }
    }

    // LSPosed may recreate its redirected preference file on module update.
    // Keep the Android private copy current so missing values can be restored.
    private class MirroredPrefs(private val shared: SharedPreferences,
                                private val privateCopy: SharedPreferences) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = shared.all
        override fun getString(key: String?, defValue: String?): String? = shared.getString(key, defValue)
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            shared.getStringSet(key, defValues)
        override fun getInt(key: String?, defValue: Int): Int = shared.getInt(key, defValue)
        override fun getLong(key: String?, defValue: Long): Long = shared.getLong(key, defValue)
        override fun getFloat(key: String?, defValue: Float): Float = shared.getFloat(key, defValue)
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = shared.getBoolean(key, defValue)
        override fun contains(key: String?): Boolean = shared.contains(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
            shared.registerOnSharedPreferenceChangeListener(listener)
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
            shared.unregisterOnSharedPreferenceChangeListener(listener)
        override fun edit(): SharedPreferences.Editor = MirroredEditor(shared.edit(), privateCopy.edit())
    }

    private class MirroredEditor(private val shared: SharedPreferences.Editor,
                                 private val privateCopy: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { shared.putString(key, value); privateCopy.putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
            shared.putStringSet(key, values); privateCopy.putStringSet(key, values)
        }
        override fun putInt(key: String?, value: Int) = apply { shared.putInt(key, value); privateCopy.putInt(key, value) }
        override fun putLong(key: String?, value: Long) = apply { shared.putLong(key, value); privateCopy.putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = apply { shared.putFloat(key, value); privateCopy.putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { shared.putBoolean(key, value); privateCopy.putBoolean(key, value) }
        override fun remove(key: String?) = apply { shared.remove(key); privateCopy.remove(key) }
        override fun clear() = apply { shared.clear(); privateCopy.clear() }
        override fun commit(): Boolean {
            val privateSaved = privateCopy.commit()
            val sharedSaved = shared.commit()
            return privateSaved && sharedSaved
        }
        override fun apply() { privateCopy.apply(); shared.apply() }
    }

    // Keys
    const val KEY_ENABLED = MainHook.KEY_ENABLED
    const val KEY_VIDEO   = MainHook.KEY_VIDEO
    const val KEY_APPS    = MainHook.KEY_APPS
}
