package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.LyricWord
import timber.log.Timber

/**
 * KRC 格式解析器（酷狗音乐逐字歌词格式）
 * 
 * 格式说明：
 * - 时间戳: [起始时间,持续时间]
 * - 逐字: <偏移,持续,0>字<偏移,持续,0>符<偏移,持续,0>串
 * - 元数据: [language:...], [id:...], [hash:...] 等
 * 
 * 示例：
 * [0,3500]<0,500,0>在<500,300,0>世<800,400,0>界<1200,300,0>的<1500,400,0>尽<1900,600,0>头
 * 
 * 参考: https://github.com/apoint123/Unilyric/tree/main/lyrics_helper_rs/src/converter/parsers/krc_parser.rs
 */
object KrcParser {
    
    // KRC 行时间戳正则: [起始ms,持续ms]
    private val LINE_TIMESTAMP_REGEX = Regex("""^\[(\d+),(\d+)]""")
    
    // KRC 逐字时间戳正则: <偏移ms,持续ms,0>文本
    private val SYLLABLE_REGEX = Regex("""<(\d+),(\d+),\d+>([^<]+)""")
    
    /**
     * 解析 KRC 格式内容
     */
    fun parse(content: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val contentLines = content.lines()
        
        for ((index, lineStr) in contentLines.withIndex()) {
            val trimmed = lineStr.trim()
            if (trimmed.isEmpty()) continue
            
            // 跳过元数据行
            if (isMetadataLine(trimmed)) continue
            
            try {
                parseSingleLine(trimmed, index)?.let { lines.add(it) }
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse KRC line $index: $trimmed")
            }
        }
        
        return lines
    }
    
    /**
     * 解析单行 KRC 歌词
     */
    private fun parseSingleLine(line: String, lineNumber: Int): LyricLine? {
        // 匹配行时间戳
        val lineMatch = LINE_TIMESTAMP_REGEX.find(line) ?: return null
        val lineStartMs = lineMatch.groupValues[1].toLongOrNull() ?: return null
        val lineDurationMs = lineMatch.groupValues[2].toLongOrNull() ?: return null
        val lineEndMs = lineStartMs + lineDurationMs
        
        // 提取行时间戳后的内容
        val contentAfterTimestamp = line.substring(lineMatch.range.last + 1)
        
        // 解析逐字内容
        val words = mutableListOf<LyricWord>()
        var fullText = ""
        
        for (match in SYLLABLE_REGEX.findAll(contentAfterTimestamp)) {
            val offsetMs = match.groupValues[1].toLongOrNull() ?: 0L
            val durationMs = match.groupValues[2].toLongOrNull() ?: 0L
            val text = match.groupValues[3]
            
            // 绝对时间 = 行起始时间 + 音节偏移时间
            val syllableStartMs = lineStartMs + offsetMs
            val syllableEndMs = syllableStartMs + durationMs
            
            // KRC 音节文本里的空格是可见语义（尤其英文单词间分隔），不能 trim 掉。
            val normalizedWordText = normalizeWordText(text, words)
            if (normalizedWordText != null) {
                words.add(
                    LyricWord(
                        word = normalizedWordText,
                        startTime = syllableStartMs,
                        endTime = syllableEndMs
                    )
                )
                fullText += normalizedWordText
            }
        }
        
        // 如果没有解析到任何词，尝试提取纯文本
        if (words.isEmpty()) {
            // 移除所有时间标签，获取纯文本
            val plainText = SYLLABLE_REGEX.replace(contentAfterTimestamp) { it.groupValues[3] }
            if (plainText.isNotEmpty()) {
                fullText = plainText
                words.add(
                    LyricWord(
                        word = plainText,
                        startTime = lineStartMs,
                        endTime = lineEndMs
                    )
                )
            }
        }
        
        return LyricLine(
            startTime = lineStartMs,
            endTime = lineEndMs,
            // KRC 行文本保持原始可见空格，不做 trim。
            text = fullText,
            words = words
        )
    }

    /**
     * KRC 空格保留策略：
     * 1. 纯空白音节并入前一个词尾，避免产生“空白词”；
     * 2. 其他文本（包含前后空格）原样保留。
     */
    private fun normalizeWordText(rawText: String, words: MutableList<LyricWord>): String? {
        if (rawText.isEmpty()) return null

        if (rawText.isBlank()) {
            if (words.isNotEmpty()) {
                val last = words.last()
                words[words.lastIndex] = last.copy(word = last.word + rawText)
            }
            return null
        }

        return rawText
    }
    
    /**
     * 检查是否是元数据行
     */
    private fun isMetadataLine(line: String): Boolean {
        return line.startsWith("[language:") ||
               line.startsWith("[id:") ||
               line.startsWith("[hash:") ||
               line.startsWith("[total:") ||
               line.startsWith("[qq:") ||
               line.startsWith("[offset:") ||
               line.startsWith("[sign:") ||
               line.startsWith("[ti:") ||
               line.startsWith("[ar:") ||
               line.startsWith("[al:") ||
               line.startsWith("[by:")
    }
}
