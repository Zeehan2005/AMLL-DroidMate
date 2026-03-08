package com.amll.droidmate.data.converter

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.domain.model.TTMLMetadata
import timber.log.Timber

/**
 * TTML 转换器 - 将歌词转换为 TTML 格式
 */
object TTMLConverter {

    /**
     * 将歌词行列表转换为 TTML 字符串
     */
    fun toTTMLString(
        lyrics: TTMLLyrics,
        formatted: Boolean = false
    ): String {
        val sb = StringBuilder()
        
        // XML 头
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        if (formatted) sb.append("\n")
        
        // TTML 核心
        val indent = if (formatted) "  " else ""
        val lineBreak = if (formatted) "\n" else ""
        
        sb.append("""<tt xmlns="http://www.w3.org/ns/ttml"""")
        sb.append(""" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"""")
        sb.append(""" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"""")
        sb.append(""" xmlns:amll="http://www.example.com/ns/amll"""")
        sb.append(""" xml:lang="ja"""")
        sb.append(""" itunes:timing="Word">$lineBreak""")
        
        // Head
        sb.append("${indent}<head>$lineBreak")
        sb.append("""${indent}${indent}<metadata>""")
        if (formatted) sb.append("\n")
        
        // Metadata
        with(lyrics.metadata) {
            sb.append("""${indent}${indent}${indent}<amll:meta key="title" value="$title" />""")
            if (formatted) sb.append("\n")
            sb.append("""${indent}${indent}${indent}<amll:meta key="artist" value="$artist" />""")
            if (formatted) sb.append("\n")
            album?.let {
                sb.append("""${indent}${indent}${indent}<amll:meta key="album" value="$album" />""")
                if (formatted) sb.append("\n")
            }
            sb.append("""${indent}${indent}${indent}<amll:meta key="language" value="$language" />""")
            if (formatted) sb.append("\n")
            sb.append("""${indent}${indent}${indent}<amll:meta key="source" value="$source" />""")
            if (formatted) sb.append("\n")
        }
        
        sb.append("""${indent}${indent}</metadata>$lineBreak""")
        sb.append("""${indent}</head>$lineBreak""")
        
        // Body
        val duration = formatTime(lyrics.lines.lastOrNull()?.endTime ?: 0L)
        sb.append("""${indent}<body dur="$duration">$lineBreak""")
        
        // Lyrics lines
        lyrics.lines.forEachIndexed { index, line ->
            val begin = formatTime(line.startTime)
            val end = formatTime(line.endTime)
            val lineNum = "L${index + 1}"
            
            sb.append("""${indent}${indent}<p begin="$begin" end="$end" itunes:key="$lineNum" ttm:agent="v1">""")
            if (formatted) sb.append("\n")
            
            // Main lyrics - 如果有 words 数组则逐词输出，否则整行输出
            if (line.words.isNotEmpty()) {
                // 逐词输出，空格语义对齐 Unilyrics:
                // - pretty(format=true): 词尾空格保留在 span 文本内
                // - compact(format=false): 词尾空格输出为 span 之间的纯文本空格
                line.words.forEachIndexed { index, word ->
                    val wordBegin = formatTime(word.startTime)
                    val wordEnd = formatTime(word.endTime)

                    val rawWord = word.word
                    val hasTrailingSpace = rawWord.lastOrNull()?.isWhitespace() == true
                    val cleanWord = rawWord.trim()

                    if (cleanWord.isEmpty()) {
                        // 对齐 Unilyrics: 紧凑模式下，空白词节点作为词间空格文本处理
                        if (!formatted && index < line.words.lastIndex) {
                            sb.append(" ")
                        }
                        return@forEachIndexed
                    }

                    val spanText = if (formatted && hasTrailingSpace) "$cleanWord " else cleanWord
                    sb.append("""${indent}${indent}${indent}<span begin="$wordBegin" end="$wordEnd">${escapeXML(spanText)}</span>""")

                    // 紧凑模式下，把尾随空格写成 span 间文本，和 Unilyrics 逻辑一致
                    if (!formatted && hasTrailingSpace && index < line.words.lastIndex) {
                        sb.append(" ")
                    }

                    if (formatted) sb.append("\n")
                }
            } else {
                // 整行输出
                sb.append("""${indent}${indent}${indent}<span begin="$begin" end="$end">${escapeXML(line.text)}</span>""")
                if (formatted) sb.append("\n")
            }
            
            // Translation if available
            line.translation?.let {
                sb.append("""${indent}${indent}${indent}<span ttm:role="x-translation" xml:lang="zh-CN">${escapeXML(it)}</span>""")
                if (formatted) sb.append("\n")
            }
            
            // Transliteration if available
            line.transliteration?.let {
                sb.append("""${indent}${indent}${indent}<span ttm:role="x-roman" xml:lang="ja-Latn">${escapeXML(it)}</span>""")
                if (formatted) sb.append("\n")
            }
            
            sb.append("""${indent}${indent}</p>""")
            if (formatted) sb.append("\n")
        }
        
        sb.append("""${indent}</body>$lineBreak""")
        sb.append("</tt>")
        
        return sb.toString()
    }

    /**
     * 将歌词行列表转换为完整的 TTML 对象
     */
    fun fromLyricLines(
        lines: List<LyricLine>,
        title: String = "Unknown",
        artist: String = "Unknown",
        album: String? = null
    ): TTMLLyrics {
        return TTMLLyrics(
            metadata = TTMLMetadata(
                title = title,
                artist = artist,
                album = album,
                language = "ja",
                duration = lines.lastOrNull()?.endTime ?: 0L,
                source = "DroidMate"
            ),
            lines = lines.sortedBy { it.startTime }
        )
    }

    /**
     * 格式化时间为 TTML 格式
     * 格式: mm:ss.msms
     */
    fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val ms = millis % 1000
        
        return String.format("%02d:%02d.%03d", minutes, seconds, ms)
    }

    /**
     * 将时间字符串转换为毫秒
     * 支持: mm:ss.mmm 或 mm:ss
     */
    fun timeToMillis(timeStr: String): Long {
        return try {
            val parts = timeStr.split(":")
            if (parts.size != 2) return 0L

            val minutes = parts[0].toLongOrNull() ?: return 0L
            val secParts = parts[1].split(".")
            val seconds = secParts[0].toLongOrNull() ?: return 0L
            val millis = if (secParts.size > 1) {
                secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            } else {
                0L
            }

            (minutes * 60 + seconds) * 1000 + millis
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 转义 XML 特殊字符
     */
    private fun escapeXML(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * 从多种格式解析歌词到 TTML（使用 Unilyric 规则）
     * 支持: LRC, Enhanced LRC, QRC, KRC, YRC
     * 
     * @param content 歌词内容
     * @param title 歌曲标题（可选）
     * @param artist 艺术家（可选）
     * @param album 专辑（可选）
     * @return TTMLLyrics 对象，如果解析失败则返回 null
     */
    fun fromLyrics(
        content: String,
        title: String = "Unknown",
        artist: String = "Unknown", 
        album: String? = null
    ): TTMLLyrics? {
        return try {
            com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
                content = content,
                title = title,
                artist = artist,
                album = album
            )
        } catch (e: Exception) {
            Timber.e(e, "Error parsing lyrics using Unilyric rules")
            null
        }
    }
    
    /**
     * 从 LRC 格式转换到 TTML（保留用于向后兼容）
     * @deprecated 使用 fromLyrics() 代替，它支持更多格式
     */
    @Deprecated(
        message = "Use fromLyrics() instead for better format support",
        replaceWith = ReplaceWith("fromLyrics(lrcContent)")
    )
    fun fromLRC(lrcContent: String): TTMLLyrics? {
        return fromLyrics(lrcContent)
    }
}
