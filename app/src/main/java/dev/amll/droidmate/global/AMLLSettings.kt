package dev.amll.droidmate.global

import android.content.Context
import io.github.zeehan2005.scoremuse.components.PreferenceHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile


object AMLLSettings {
    private const val PREFS_NAME = "droidmate_settings"  // SharedPreferences 名称
    private const val KEY_AMLL_FONT_FAMILY = "amll_font_family"  // AMLL 字体族
    private const val KEY_AMLL_FONT_FILES = "amll_font_files"  // 已安装的字体列表
    private const val KEY_AMLL_ACTIVE_FONT_ID = "amll_active_font_id"  // 当前激活的字体 ID
    private const val KEY_AMLL_ENABLED_FONT_IDS = "amll_enabled_font_ids"  // 启用的字体 ID 列表

    /** 动画相关设置（控制歌词运动和动画效果） */
    private const val KEY_AMLL_ANIMATION_ENABLE_SPRING = "amll_animation_enable_spring"  // 启用弹簧动画
    private const val KEY_AMLL_ANIMATION_ENABLE_SCALE = "amll_animation_enable_scale"  // 启用缩放效果
    private const val KEY_AMLL_ANIMATION_ENABLE_BLUR = "amll_animation_enable_blur"  // 启用模糊效果

    // 歌词样式相关设置
    private const val KEY_AMLL_LYRIC_FONT_SIZE = "amll_lyric_font_size"  // 歌词字体大小（像素）
    private const val KEY_AMLL_ENABLE_TRANSLATION_LINE = "amll_enable_translation_line"  // 显示翻译歌词
    private const val KEY_AMLL_ENABLE_ROMAN_LINE = "amll_enable_roman_line"  // 显示音译歌词
    private const val KEY_AMLL_ADVANCE_DYNAMIC_LYRIC_TIME = "amll_advance_dynamic_lyric_time"  // 提前歌词行时序
    private const val KEY_AMLL_FONT_WEIGHT = "amll_font_weight"  // 字体字重
    private const val KEY_AMLL_LETTER_SPACING = "amll_letter_spacing"  // 字符间距

    // 歌词背景相关设置
    private const val KEY_AMLL_BACKGROUND_RENDERER = "amll_background_renderer"  // 背景渲染器类型
    private const val KEY_AMLL_BACKGROUND_RENDERER_ENABLED = "amll_background_renderer_enabled"  // 背景渲染开关
    private const val KEY_AMLL_BACKGROUND_FPS = "amll_background_fps"  // 背景渲染帧率
    private const val KEY_AMLL_BACKGROUND_RENDER_SCALE = "amll_background_render_scale"  // 背景渲染缩放
    private const val KEY_AMLL_BACKGROUND_STATIC_MODE = "amll_background_static_mode"  // 背景静态模式
    private const val KEY_AMLL_BACKGROUND_CSS_PROPERTY = "amll_background_css_property"  // CSS 背景属性
    private const val KEY_AMLL_ANIMATION_FPS = "amll_animation_fps"  // 动画帧率

    /** 默认 AMLL 歌词字体族（Apple Music 风格） */
    const val DEFAULT_AMLL_FONT_FAMILY = "\"SF Pro Display\", \"PingFang SC\", system-ui, -apple-system, \"Segoe UI\", sans-serif"

    /** 默认歌词字重（正常） */
    const val DEFAULT_AMLL_FONT_WEIGHT = 400

    /** 默认歌词字体大小（像素），仅在用户未显式设置时作为回退值使用 */
    const val DEFAULT_AMLL_LYRIC_FONT_SIZE = 28

    /**
     * AMLL 自定义字体文件
     *
     * 用于存储用户安装的第三方字体信息。
     * 每个字体包含唯一 ID、显示名称、完整路径和字体族名称。
     *
     * @param id 唯一标识符
     * @param displayName 用户可见的字体名称（如 "思源黑体"）
     * @param absolutePath 字体文件的绝对路径（用于加载字体）
     * @param fontFamilyName 字体族名称（CSS font-family 使用）
     */
    data class AmllFontFile(
        val id: String,
        val displayName: String,
        val absolutePath: String,
        val fontFamilyName: String
    )

    /** 辅助函数：获取 SharedPreferences 实例（避免重复代码）*/
    private fun prefs(context: Context) =
        PreferenceHelper(context, PREFS_NAME)

    private fun getBooleanOrNull(context: Context, key: String): Boolean? {
        val p = prefs(context)
        return if (p.contains(key)) p.getBoolean(key) else null
    }

    // === 字体设置 ===

    fun getDefaultAmllFontFamily(): String = DEFAULT_AMLL_FONT_FAMILY

    fun getAmllFontFamily(context: Context): String {
        return prefs(context).getString(KEY_AMLL_FONT_FAMILY, DEFAULT_AMLL_FONT_FAMILY)
            ?: DEFAULT_AMLL_FONT_FAMILY
    }

    fun setAmllFontFamily(context: Context, fontFamily: String) {
        prefs(context).putStringAsync(KEY_AMLL_FONT_FAMILY, fontFamily)
    }

    fun getAmllFontFiles(context: Context): List<AmllFontFile> {
        val helper = prefs(context)
        val raw = helper.getString(KEY_AMLL_FONT_FILES, null)
        if (raw.isNullOrBlank()) return emptyList()

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
                            displayName = displayName.ifBlank { "Imported Font" },
                            absolutePath = absolutePath,
                            fontFamilyName = fontFamilyName.ifBlank {
                                buildFontFamilyName(displayName, absolutePath)
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
        prefs(context).putString(KEY_AMLL_FONT_FILES, json.toString())
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
        val helper = prefs(context)
        val activeId = helper.getString(KEY_AMLL_ACTIVE_FONT_ID, null)
        if (!activeId.isNullOrBlank()) return activeId
        return null
    }

    fun setActiveAmllFontFileId(context: Context, fileId: String?) {
        if (fileId.isNullOrBlank()) {
            prefs(context).remove(KEY_AMLL_ACTIVE_FONT_ID)
        } else {
            prefs(context).putStringAsync(KEY_AMLL_ACTIVE_FONT_ID, fileId)
        }
    }

    fun getEnabledAmllFontFileIds(context: Context): List<String> {
        val helper = prefs(context)
        val raw = helper.getString(KEY_AMLL_ENABLED_FONT_IDS, null)
        if (raw.isNullOrBlank()) return emptyList()

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
        val json = JSONArray().apply {
            normalized.forEach { put(it) }
        }
        prefs(context).putStringAsync(KEY_AMLL_ENABLED_FONT_IDS, json.toString())
    }

    fun resetAmllFontSettings(context: Context) {
        setAmllFontFamily(context, DEFAULT_AMLL_FONT_FAMILY)
        prefs(context).remove(KEY_AMLL_ACTIVE_FONT_ID)
        prefs(context).remove(KEY_AMLL_ENABLED_FONT_IDS)
        prefs(context).remove(KEY_AMLL_FONT_FILES)
        prefs(context).remove(KEY_AMLL_FONT_WEIGHT)
        prefs(context).remove(KEY_AMLL_LYRIC_FONT_SIZE)
    }

    // === 歌词字重与字号设置 ===

    /**
     * 获取歌词字重。
     * @return 字重数值（通常 100-900），未设置时返回 null（前端使用 CSS 默认 400）
     */
    fun getAmllFontWeight(context: Context): Int? {
        val p = prefs(context)
        return if (p.contains(KEY_AMLL_FONT_WEIGHT)) p.getInt(KEY_AMLL_FONT_WEIGHT) else null
    }

    /**
     * 设置歌词字重。
     * @param weight 字重数值（建议 100-900 之间，如 400=常规、700=加粗）
     */
    fun setAmllFontWeight(context: Context, weight: Int) {
        prefs(context).putIntAsync(KEY_AMLL_FONT_WEIGHT, weight)
    }

    /**
     * 获取歌词字体大小（像素）。
     * @return 字号（px），未设置时返回 null（前端使用自适应默认大小）
     */
    fun getAmllLyricFontSize(context: Context): Int? {
        val p = prefs(context)
        return if (p.contains(KEY_AMLL_LYRIC_FONT_SIZE)) p.getInt(KEY_AMLL_LYRIC_FONT_SIZE) else null
    }

    /**
     * 设置歌词字体大小（像素）。
     * @param sizePx 字号（px），传入 <= 0 会清除该设置并恢复自适应默认大小
     */
    fun setAmllLyricFontSize(context: Context, sizePx: Int) {
        if (sizePx <= 0) {
            prefs(context).remove(KEY_AMLL_LYRIC_FONT_SIZE)
        } else {
            prefs(context).putIntAsync(KEY_AMLL_LYRIC_FONT_SIZE, sizePx)
        }
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

    /**
     * 解析 TrueType/OpenType 字体文件的 "name" 表，提取字体族名称（nameID = 1）。
     * 这是用户可见的字体名称，如 "微软雅黑"。
     *
     * @param file 字体文件
     * @return 字体族名称，解析失败返回 null
     */
    fun readFontFamilyName(file: File): String? {
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.readInt() // sfnt version
                val numTables = raf.readUnsignedShort()
                raf.skipBytes(6) // searchRange, entrySelector, rangeShift
                for (i in 0 until numTables) {
                    val tag = raf.readInt()
                    val _checkSum = raf.readInt()
                    val offset = raf.readInt()
                    val _length = raf.readInt()
                    // 'name' table tag = 0x6E616D65
                    if (tag == 0x6E616D65) {
                        raf.seek(offset.toLong())
                        val _format = raf.readUnsignedShort()
                        val count = raf.readUnsignedShort()
                        val stringOffset = raf.readUnsignedShort()
                        for (j in 0 until count) {
                            val platformID = raf.readUnsignedShort()
                            val _encodingID = raf.readUnsignedShort()
                            val _languageID = raf.readUnsignedShort()
                            val nameID = raf.readUnsignedShort()
                            val lengthEntry = raf.readUnsignedShort()
                            val offsetEntry = raf.readUnsignedShort()
                            if (nameID == 1) { // Font Family name
                                val pos = offset.toLong() + stringOffset + offsetEntry
                                raf.seek(pos)
                                val bytes = ByteArray(lengthEntry)
                                raf.readFully(bytes)
                                val encoding = when (platformID) {
                                    0, 3 -> Charsets.UTF_16BE
                                    else -> Charsets.ISO_8859_1
                                }
                                return try {
                                    String(bytes, encoding)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    // === 动画设置 ===

    fun isAmllAnimationSpringEnabled(context: Context): Boolean? =
        getBooleanOrNull(context, KEY_AMLL_ANIMATION_ENABLE_SPRING)

    fun setAmllAnimationSpringEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBooleanAsync(KEY_AMLL_ANIMATION_ENABLE_SPRING, enabled)
    }

    fun isAmllAnimationScaleEnabled(context: Context): Boolean? =
        getBooleanOrNull(context, KEY_AMLL_ANIMATION_ENABLE_SCALE)

    fun isAmllAnimationBlurEnabled(context: Context): Boolean? =
        getBooleanOrNull(context, KEY_AMLL_ANIMATION_ENABLE_BLUR)

    fun setAmllAnimationBlurEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBooleanAsync(KEY_AMLL_ANIMATION_ENABLE_BLUR, enabled)
    }

    // === 歌词背景设置 ===

    fun isAmllBackgroundRendererEnabled(context: Context): Boolean? =
        getBooleanOrNull(context, KEY_AMLL_BACKGROUND_RENDERER_ENABLED)

    fun setAmllBackgroundRendererEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBooleanAsync(KEY_AMLL_BACKGROUND_RENDERER_ENABLED, enabled)
    }

    fun getAmllBackgroundRenderer(context: Context): String {
        return prefs(context).getString(KEY_AMLL_BACKGROUND_RENDERER, "mesh") ?: "mesh"
    }

    fun setAmllBackgroundRenderer(context: Context, renderer: String) {
        prefs(context).putStringAsync(KEY_AMLL_BACKGROUND_RENDERER, renderer)
    }

    fun getAmllBackgroundFps(context: Context): Int? {
        val p = prefs(context)
        return if (p.contains(KEY_AMLL_BACKGROUND_FPS)) p.getInt(KEY_AMLL_BACKGROUND_FPS) else null
    }

    fun setAmllBackgroundFps(context: Context, fps: Int) {
        prefs(context).putIntAsync(KEY_AMLL_BACKGROUND_FPS, fps)
    }

    fun getAmllBackgroundRenderScale(context: Context): Float? {
        val p = prefs(context)
        return if (p.contains(KEY_AMLL_BACKGROUND_RENDER_SCALE)) p.getFloat(KEY_AMLL_BACKGROUND_RENDER_SCALE) else null
    }

    fun setAmllBackgroundRenderScale(context: Context, scale: Float) {
        prefs(context).putFloatAsync(KEY_AMLL_BACKGROUND_RENDER_SCALE, scale)
    }

    fun isAmllBackgroundStaticModeEnabled(context: Context): Boolean? =
        getBooleanOrNull(context, KEY_AMLL_BACKGROUND_STATIC_MODE)

    fun setAmllBackgroundStaticModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBooleanAsync(KEY_AMLL_BACKGROUND_STATIC_MODE, enabled)
    }

    fun getAmllCssBackgroundProperty(context: Context): String? {
        return prefs(context).getString(KEY_AMLL_BACKGROUND_CSS_PROPERTY, null)
    }

    fun setAmllCssBackgroundProperty(context: Context, cssProperty: String?) {
        if (cssProperty.isNullOrBlank()) {
            prefs(context).remove(KEY_AMLL_BACKGROUND_CSS_PROPERTY)
        } else {
            prefs(context).putStringAsync(KEY_AMLL_BACKGROUND_CSS_PROPERTY, cssProperty)
        }
    }

    fun getAmllAnimationFps(context: Context): Int? {
        val p = prefs(context)
        return if (p.contains(KEY_AMLL_ANIMATION_FPS)) p.getInt(KEY_AMLL_ANIMATION_FPS) else null
    }

    fun setAmllAnimationFps(context: Context, fps: Int) {
        prefs(context).putIntAsync(KEY_AMLL_ANIMATION_FPS, fps)
    }

}
