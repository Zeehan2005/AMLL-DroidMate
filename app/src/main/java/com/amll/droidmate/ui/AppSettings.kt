package com.amll.droidmate.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

enum class UpdateChannel(val value: String) {
    STABLE("stable"),
    PREVIEW("preview");

    companion object {
        fun fromValue(value: String?): UpdateChannel {
            return entries.firstOrNull { it.value == value } ?: STABLE
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
    private const val KEY_AMLL_FONT_FILES = "amll_font_files"
    private const val KEY_AMLL_ACTIVE_FONT_ID = "amll_active_font_id"
    private const val KEY_AMLL_ENABLED_FONT_IDS = "amll_enabled_font_ids"
    private const val KEY_AUTO_UPDATE_CHECK_ENABLED = "auto_update_check_enabled"
    private const val KEY_UPDATE_CHANNEL = "update_channel"
    private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"
    private const val KEY_SKIP_PREVIOUS_REWINDS = "skip_previous_rewinds"

    private const val DEFAULT_AMLL_FONT_FAMILY = "\"SF Pro Display\", \"PingFang SC\", system-ui, -apple-system, \"Segoe UI\", sans-serif"

    data class AmllFontFile(
        val id: String,
        val displayName: String,
        val absolutePath: String,
        val fontFamilyName: String
    )

    fun getDefaultAmllFontFamily(): String = DEFAULT_AMLL_FONT_FAMILY

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
        return getActiveAmllFontFile(context)?.absolutePath
    }

    fun getAmllFontFileName(context: Context): String? {
        return getActiveAmllFontFile(context)?.displayName
    }

    fun setAmllFontFile(context: Context, absolutePath: String, displayName: String) {
        val updatedList = upsertAmllFontFile(
            context = context,
            absolutePath = absolutePath,
            displayName = displayName
        )
        val added = updatedList.firstOrNull { it.absolutePath == absolutePath }
        if (added != null) {
            setActiveAmllFontFileId(context, added.id)
        }
    }

    fun clearAmllFontFile(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_AMLL_ACTIVE_FONT_ID)
            .remove(KEY_AMLL_ENABLED_FONT_IDS)
            .remove(KEY_AMLL_FONT_FILE_PATH)
            .remove(KEY_AMLL_FONT_FILE_NAME)
            .apply()
    }

    fun getAmllFontFiles(context: Context): List<AmllFontFile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_AMLL_FONT_FILES, null)
        if (raw.isNullOrBlank()) {
            val legacyPath = prefs.getString(KEY_AMLL_FONT_FILE_PATH, null)
            val legacyName = prefs.getString(KEY_AMLL_FONT_FILE_NAME, null)
            if (!legacyPath.isNullOrBlank()) {
                val fallbackName = legacyName ?: "Imported Font"
                return listOf(
                    AmllFontFile(
                        id = stableFontId(legacyPath),
                        displayName = fallbackName,
                        absolutePath = legacyPath,
                        fontFamilyName = buildFontFamilyName(fallbackName, legacyPath)
                    )
                )
            }
            return emptyList()
        }

        return try {
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    val item = json.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val displayName = item.optString("displayName")
                    val absolutePath = item.optString("absolutePath")
                    val fontFamilyName = item.optString("fontFamilyName")
                    if (id.isBlank() || absolutePath.isBlank()) continue
                    add(
                        AmllFontFile(
                            id = id,
                            displayName = if (displayName.isBlank()) "Imported Font" else displayName,
                            absolutePath = absolutePath,
                            fontFamilyName = if (fontFamilyName.isBlank()) {
                                buildFontFamilyName(displayName, absolutePath)
                            } else {
                                fontFamilyName
                            }
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setAmllFontFiles(context: Context, files: List<AmllFontFile>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONArray().apply {
            files.forEach { file ->
                put(
                    JSONObject().apply {
                        put("id", file.id)
                        put("displayName", file.displayName)
                        put("absolutePath", file.absolutePath)
                        put("fontFamilyName", file.fontFamilyName)
                    }
                )
            }
        }
        prefs.edit()
            .putString(KEY_AMLL_FONT_FILES, json.toString())
            .apply()
    }

    fun upsertAmllFontFile(context: Context, absolutePath: String, displayName: String): List<AmllFontFile> {
        val existing = getAmllFontFiles(context).toMutableList()
        val existingIndex = existing.indexOfFirst { it.absolutePath == absolutePath }
        val next = AmllFontFile(
            id = stableFontId(absolutePath),
            displayName = displayName,
            absolutePath = absolutePath,
            fontFamilyName = buildFontFamilyName(displayName, absolutePath)
        )

        if (existingIndex >= 0) {
            existing[existingIndex] = next
        } else {
            existing.add(next)
        }

        setAmllFontFiles(context, existing)
        return existing
    }

    fun removeAmllFontFile(context: Context, fileId: String): List<AmllFontFile> {
        val remaining = getAmllFontFiles(context).filterNot { it.id == fileId }
        setAmllFontFiles(context, remaining)

        val activeId = getActiveAmllFontFileId(context)
        if (activeId == fileId) {
            setActiveAmllFontFileId(context, null)
        }

        val enabled = getEnabledAmllFontFileIds(context).filterNot { it == fileId }
        setEnabledAmllFontFileIds(context, enabled)
        return remaining
    }

    fun getActiveAmllFontFileId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_AMLL_ACTIVE_FONT_ID, null)
        if (!activeId.isNullOrBlank()) return activeId

        val legacyPath = prefs.getString(KEY_AMLL_FONT_FILE_PATH, null)
        return legacyPath?.takeIf { it.isNotBlank() }?.let(::stableFontId)
    }

    fun setActiveAmllFontFileId(context: Context, fileId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (fileId.isNullOrBlank()) {
                remove(KEY_AMLL_ACTIVE_FONT_ID)
            } else {
                putString(KEY_AMLL_ACTIVE_FONT_ID, fileId)
            }
        }.apply()
    }

    fun getEnabledAmllFontFileIds(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_AMLL_ENABLED_FONT_IDS, null)
        if (raw.isNullOrBlank()) {
            val legacyActive = getActiveAmllFontFileId(context)
            return if (legacyActive.isNullOrBlank()) emptyList() else listOf(legacyActive)
        }

        return try {
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    val id = json.optString(i)
                    if (id.isNotBlank()) add(id)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setEnabledAmllFontFileIds(context: Context, fileIds: List<String>) {
        val normalized = fileIds.filter { it.isNotBlank() }.distinct()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONArray().apply {
            normalized.forEach { put(it) }
        }
        prefs.edit().putString(KEY_AMLL_ENABLED_FONT_IDS, json.toString()).apply()
    }

    fun getActiveAmllFontFile(context: Context): AmllFontFile? {
        val fonts = getAmllFontFiles(context)
        if (fonts.isEmpty()) return null

        val activeId = getActiveAmllFontFileId(context)
        return fonts.firstOrNull { it.id == activeId }
    }

    fun resetAmllFontSettings(context: Context) {
        setAmllFontFamily(context, DEFAULT_AMLL_FONT_FAMILY)
        clearAmllFontFile(context)
    }

    private fun stableFontId(absolutePath: String): String {
        return "font_" + absolutePath.hashCode().toUInt().toString(16)
    }

    private fun buildFontFamilyName(displayName: String, absolutePath: String): String {
        val base = displayName
            .substringBeforeLast('.')
            .ifBlank { absolutePath.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.') }
        val safe = base.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "AMLL_$safe"
    }

    fun isAutoUpdateCheckEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_UPDATE_CHECK_ENABLED, true)
    }

    fun setAutoUpdateCheckEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK_ENABLED, enabled).apply()
    }

    fun getUpdateChannel(context: Context): UpdateChannel {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_UPDATE_CHANNEL, UpdateChannel.STABLE.value)
        return UpdateChannel.fromValue(value)
    }

    fun setUpdateChannel(context: Context, channel: UpdateChannel) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_UPDATE_CHANNEL, channel.value).apply()
    }

    fun getLastUpdateCheckAt(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)
    }

    fun setLastUpdateCheckAt(context: Context, timestampMillis: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, timestampMillis).apply()
    }

    fun isSkipPreviousRewindsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SKIP_PREVIOUS_REWINDS, false)
    }

    fun setSkipPreviousRewindsEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SKIP_PREVIOUS_REWINDS, enabled).apply()
    }
}
