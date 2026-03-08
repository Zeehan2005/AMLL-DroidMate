package com.amll.droidmate.ui

import android.content.Context

enum class CardClickAction(val value: String) {
    DIRECT_OPEN("direct_open"),
    ASK("ask"),
    NONE("none");

    companion object {
        fun fromValue(value: String?): CardClickAction {
            return entries.firstOrNull { it.value == value } ?: ASK
        }
    }
}

object AppSettings {
    private const val PREFS_NAME = "droidmate_settings"
    private const val KEY_CARD_CLICK_ACTION = "card_click_action"
    private const val KEY_LYRIC_NOTIFICATION_ENABLED = "lyric_notification_enabled"
    private const val KEY_AMLL_FONT_FAMILY = "amll_font_family"
    private const val KEY_AMLL_FONT_FILE_PATH = "amll_font_file_path"
    private const val KEY_AMLL_FONT_FILE_NAME = "amll_font_file_name"

    private const val DEFAULT_AMLL_FONT_FAMILY = "\"SF Pro Display\", \"PingFang SC\", system-ui, -apple-system, \"Segoe UI\", sans-serif"

    fun getCardClickAction(context: Context): CardClickAction {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_CARD_CLICK_ACTION, CardClickAction.ASK.value)
        return CardClickAction.fromValue(value)
    }

    fun setCardClickAction(context: Context, action: CardClickAction) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CARD_CLICK_ACTION, action.value).apply()
    }

    fun isLyricNotificationEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LYRIC_NOTIFICATION_ENABLED, false)
    }

    fun setLyricNotificationEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_LYRIC_NOTIFICATION_ENABLED, enabled).apply()
    }

    fun getAmllFontFamily(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AMLL_FONT_FAMILY, DEFAULT_AMLL_FONT_FAMILY)
            ?: DEFAULT_AMLL_FONT_FAMILY
    }

    fun setAmllFontFamily(context: Context, fontFamily: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AMLL_FONT_FAMILY, fontFamily).apply()
    }

    fun getAmllFontFilePath(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AMLL_FONT_FILE_PATH, null)
    }

    fun getAmllFontFileName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AMLL_FONT_FILE_NAME, null)
    }

    fun setAmllFontFile(context: Context, absolutePath: String, displayName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_AMLL_FONT_FILE_PATH, absolutePath)
            .putString(KEY_AMLL_FONT_FILE_NAME, displayName)
            .apply()
    }

    fun clearAmllFontFile(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_AMLL_FONT_FILE_PATH)
            .remove(KEY_AMLL_FONT_FILE_NAME)
            .apply()
    }
}
