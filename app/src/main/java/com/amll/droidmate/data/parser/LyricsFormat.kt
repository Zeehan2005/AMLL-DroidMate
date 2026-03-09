package com.amll.droidmate.data.parser

/**
 * 歌词格式枚举
 * 参考: https://github.com/apoint123/Unilyric/tree/main/lyrics_helper_rs
 */
enum class LyricsFormat(val extension: String, val displayName: String) {
    /** 标准 LRC 格式 */
    LRC("lrc", "LRC"),
    
    /** 增强型 LRC 格式（支持逐字时间戳） */
    ENHANCED_LRC("lrc", "Enhanced LRC"),
    
    /** QQ音乐 QRC 格式（逐字时间戳） */
    QRC("qrc", "QRC"),
    
    /** 酷狗音乐 KRC 格式（逐字时间戳） */
    KRC("krc", "KRC"),
    
    /** 网易云音乐 YRC 格式（逐字时间戳） */
    YRC("yrc", "YRC"),
    
    /** Apple Music 式 TTML 格式 */
    TTML("ttml", "TTML"),
    
    /** 纯文本格式 */
    PLAIN_TEXT("txt", "Plain Text");

    companion object {
        @Suppress("unused")
        /**
         * 根据文件扩展名或内容特征检测格式
         */
        fun detect(content: String): LyricsFormat {
            val trimmed = content.trim()
            
            // TTML
            if (trimmed.startsWith("<?xml") || trimmed.startsWith("<tt")) {
                return TTML
            }
            
            // YRC: 元数据 JSON 行或 [start,duration] + (start,duration,0)
            if (trimmed.lines().any { it.trim().startsWith("{\"t\":") } ||
                (Regex("""^\[\d+,\d+]""", RegexOption.MULTILINE).containsMatchIn(trimmed) &&
                    Regex("""\(\d+,\d+,0\)""").containsMatchIn(trimmed))) {
                return YRC
            }
            
            // QRC: 标准 [lineTs] + (start,duration)
            if (Regex("""^\[\d+,\d+]""", RegexOption.MULTILINE).containsMatchIn(trimmed) &&
                Regex("""\(\d+,\d+\)""").containsMatchIn(trimmed)) {
                return QRC
            }
            
            // KRC: 两种识别方式
            // 1. 带 metadata: [language:], [id:], [hash:] 等
            // 2. 不带 metadata 但有特征: [毫秒,毫秒]<毫秒,毫秒,0>（酷狗解密后的格式）
            if (trimmed.lines().any { 
                it.startsWith("[language:") || 
                it.startsWith("[id:") ||
                it.startsWith("[hash:")
            } || 
            (Regex("""^\[\d{4,},\d+]""", RegexOption.MULTILINE).containsMatchIn(trimmed) &&
                Regex("""<\d+,\d+,0>""").containsMatchIn(trimmed))) {
                return KRC
            }
            
            // Enhanced LRC - 逐字时间戳 <mm:ss.ms>word
            if (Regex("""<\d{2}:\d{2}\.\d{2,3}>""").containsMatchIn(trimmed)) {
                return ENHANCED_LRC
            }
            
            // 标准 LRC
            if (Regex("""\[\d{1,2}:\d{1,2}(?:[.:]\d{1,3})?]""").containsMatchIn(trimmed)) {
                return LRC
            }
            
            // 默认纯文本
            return PLAIN_TEXT
        }
        
        /**
         * 从文件扩展名获取格式
         */
        fun fromExtension(ext: String): LyricsFormat? {
            val normalized = ext.lowercase().trimStart('.')
            return entries.find { it.extension == normalized }
        }
    }
}
