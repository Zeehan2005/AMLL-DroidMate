package com.amll.droidmate.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple wrapper around [SharedPreferences] to reduce boilerplate when
 * accessing named preferences.  Usage examples:
 *
 * ```kotlin
 * val prefs = PreferenceHelper(context, "my_prefs")
 * prefs.putString("key", value)
 * val existing = prefs.getString("key", "")
 * prefs.remove("key")
 * ```
 */
class PreferenceHelper(context: Context, name: String) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun getString(key: String, default: String? = null): String? =
        prefs.getString(key, default)

    fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        prefs.getBoolean(key, default)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getLong(key: String, default: Long = 0L): Long =
        prefs.getLong(key, default)

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Perform multiple editor operations in a single transaction.
     */
    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }
}
