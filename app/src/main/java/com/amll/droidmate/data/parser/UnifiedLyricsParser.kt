package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.domain.model.TTMLMetadata
import timber.log.Timber

/**
 * 统一歌词解析器
 * 
 * 根据内容自动检测格式并使用相应的解析器
 * 支持多种格式：LRC, Enhanced LRC, QRC, KRC, YRC
 * 
 * 参考: https://github.com/apoint123/Unilyric/tree/main/lyrics_helper_rs
 */
object UnifiedLyricsParser {
    
    /**
     * 解析歌词内容为 TTMLLyrics 对象
     * 
     * @param content 歌词内容
     * @param title 歌曲标题（可选）
     * @param artist 艺术家（可选）
     * @param album 专辑（可选）
     * @return TTMLLyrics 对象，如果解析失败则返回 null
     */
    fun parse(
        content: String,
        title: String = "Unknown",
        artist: String = "Unknown",
        album: String? = null
    ): TTMLLyrics? {
        if (content.isBlank()) {
            Timber.w("Empty lyrics content")
            return null
        }
        
        return try {
            // 检测格式
            val format = LyricsFormat.detect(content)
            Timber.d("Detected lyrics format: $format")
            
            // 使用相应的解析器解析
            val lines = when (format) {
                LyricsFormat.QRC -> QrcParser.parse(content)
                LyricsFormat.KRC -> KrcParser.parse(content)
                LyricsFormat.YRC -> YrcParser.parse(content)
                LyricsFormat.ENHANCED_LRC -> EnhancedLrcParser.parse(content)
                LyricsFormat.LRC -> LrcParser.parse(content)
                LyricsFormat.TTML -> {
                    // TTML 格式需要单独处理，这里返回 null 让其他地方处理
                    Timber.d("TTML format should be parsed separately")
                    return null
                }
                LyricsFormat.PLAIN_TEXT -> {
                    // 纯文本格式转换为简单行
                    parsePlainText(content)
                }
            }
            
            if (lines.isEmpty()) {
                Timber.w("No lyrics lines parsed")
                return null
            }
            
            // 构建 TTMLLyrics 对象
            val sortedLines = lines.sortedBy { it.startTime }
            val duration = sortedLines.lastOrNull()?.endTime ?: 0L
            
            TTMLLyrics(
                metadata = TTMLMetadata(
                    title = title,
                    artist = artist,
                    album = album,
                    language = detectLanguage(content),
                    duration = duration,
                    source = "DroidMate (${format.displayName})"
                ),
                lines = sortedLines
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse lyrics")
            null
        }
    }
    
    /**
     * 解析纯文本内容
     */
    private fun parsePlainText(content: String): List<LyricLine> {
        val lines = content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        
        if (lines.isEmpty()) return emptyList()
        
        return lines.mapIndexed { index, text ->
            val startTime = index * 2000L
            LyricLine(
                startTime = startTime,
                endTime = startTime + 2000L,
                text = text,
                words = emptyList()
            )
        }
    }
    
    /**
     * 检测歌词语言
     */
    private fun detectLanguage(content: String): String {
        val hasChinese = content.any { it.code in 0x4E00..0x9FFF }
        val hasJapanese = content.any { 
            it.code in 0x3040..0x309F ||  // 平假名
            it.code in 0x30A0..0x30FF      // 片假名
        }
        val hasKorean = content.any { it.code in 0xAC00..0xD7AF }
        
        return when {
            hasJapanese -> "ja"
            hasKorean -> "ko"
            hasChinese -> "zh"
            else -> "en"
        }
    }
    
    /**
     * 解析指定格式的歌词
     * 
     * @param content 歌词内容
     * @param format 指定的格式
     * @return 歌词行列表
     */
    fun parseWithFormat(content: String, format: LyricsFormat): List<LyricLine> {
        return when (format) {
            LyricsFormat.QRC -> QrcParser.parse(content)
            LyricsFormat.KRC -> KrcParser.parse(content)
            LyricsFormat.YRC -> YrcParser.parse(content)
            LyricsFormat.ENHANCED_LRC -> EnhancedLrcParser.parse(content)
            LyricsFormat.LRC -> LrcParser.parse(content)
            LyricsFormat.PLAIN_TEXT -> parsePlainText(content)
            LyricsFormat.TTML -> emptyList() // TTML 需要单独处理
        }
    }
}
