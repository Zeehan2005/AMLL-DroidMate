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
    private val prefs: SharedPreferences = try {
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    } catch (e: Exception) {
        // In local unit tests, Context methods are not mocked, so fall back to an in-memory implementation.
        InMemorySharedPreferences()
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

        private class EditorImpl(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                updates[key] = value
                return this
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                updates[key] = values
                return this
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                updates[key] = value
                return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                updates[key] = value
                return this
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                updates[key] = value
                return this
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                updates[key] = value
                return this
            }
            override fun remove(key: String): SharedPreferences.Editor {
                updates[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                updates.clear()
                updates.putAll(map.mapValues { null })
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                updates.forEach { (k, v) ->
                    if (v == null) map.remove(k) else map[k] = v
                }
                updates.clear()
            }
        }
    }

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
