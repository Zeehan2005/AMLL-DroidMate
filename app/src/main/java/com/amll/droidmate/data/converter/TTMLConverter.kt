package com.amll.droidmate.data.converter

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.domain.model.TTMLMetadata
import timber.log.Timber

/**
 * TTML 转换器 - 将歌词转换为 TTML 格式
 */
@Suppress("unused")
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
        lyrics.lines.forEachIndexed outer@{ lineIndex, line ->
            val begin = formatTime(line.startTime)
            val end = formatTime(line.endTime)
            val lineNum = "L${lineIndex + 1}"
            val agentAttr = line.agent?.let { " ttm:agent=\"$it\"" } ?: ""
            
            sb.append("""${indent}${indent}<p begin="$begin" end="$end" itunes:key="$lineNum"$agentAttr>""")
            if (formatted) sb.append("\n")

            val lineContentIndent = "${indent}${indent}${indent}"
            val lineWrapPrefix = if (line.isBG) "<span ttm:role=\"x-bg\">" else ""
            val lineWrapSuffix = if (line.isBG) "</span>" else ""

            if (line.isBG) {
                sb.append("""$lineContentIndent$lineWrapPrefix""")
                if (formatted) sb.append("\n")
            }

            val spanIndent = if (line.isBG) {
                "${indent}${indent}${indent}${indent}"
            } else {
                lineContentIndent
            }
            
            // Main lyrics - 如果有 words 数组则逐词输出，否则整行输出
            if (line.words.isNotEmpty()) {
                // 警示后人：<p>/<span> 内空格是可见歌词语义，不能对词文本做 trim。
                // 这里最多仅清理换行控制字符，避免导出后把 "a b" 变成 "ab"。
                line.words.forEachIndexed inner@{ wordIndex, word ->
                    val wordBegin = formatTime(word.startTime)
                    val wordEnd = formatTime(word.endTime)

                    val spanText = word.word
                        .replace("\r", "")
                        .replace("\n", "")

                    if (spanText.isEmpty()) {
                        // 保留空白词节点的最小分隔语义，避免词间被完全粘连。
                        if (!formatted && wordIndex < line.words.lastIndex) {
                            sb.append(" ")
                        }
                        return@inner
                    }

                    sb.append("""$spanIndent<span begin="$wordBegin" end="$wordEnd">${escapeXML(spanText)}</span>""")

                    if (formatted) sb.append("\n")
                }
            } else {
                // 整行输出
                sb.append("""$spanIndent<span begin="$begin" end="$end">${escapeXML(line.text)}</span>""")
                if (formatted) sb.append("\n")
            }

            if (line.isBG) {
                // BG 行的翻译与音译应作为 x-bg 的子节点，避免二次解析时被当作主歌词翻译。
                line.translation?.let {
                    sb.append("""$spanIndent<span ttm:role="x-translation" xml:lang="zh-CN">${escapeXML(it)}</span>""")
                    if (formatted) sb.append("\n")
                }

                line.transliteration?.let {
                    sb.append("""$spanIndent<span ttm:role="x-roman" xml:lang="ja-Latn">${escapeXML(it)}</span>""")
                    if (formatted) sb.append("\n")
                }

                sb.append("""$lineContentIndent$lineWrapSuffix""")
                if (formatted) sb.append("\n")
            } else {
                // Translation if available
                line.translation?.let {
                    sb.append("""$lineContentIndent<span ttm:role="x-translation" xml:lang="zh-CN">${escapeXML(it)}</span>""")
                    if (formatted) sb.append("\n")
                }

                // Transliteration if available
                line.transliteration?.let {
                    sb.append("""$lineContentIndent<span ttm:role="x-roman" xml:lang="ja-Latn">${escapeXML(it)}</span>""")
                    if (formatted) sb.append("\n")
                }
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
            Timber.f(e, "Error parsing lyrics using Unilyric rules")
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
