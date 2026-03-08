package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.LyricWord
import timber.log.Timber

/**
 * 增强型 LRC 格式解析器（支持逐字时间戳）
 * 
 * 格式说明：
 * - 基本时间戳: [mm:ss.ms] 歌词内容
 * - 逐字时间戳: [mm:ss.ms]<mm:ss.ms>词1<mm:ss.ms>词2<mm:ss.ms>词3
 * 
 * 示例：
 * [00:12.50]<00:12.50>在<00:13.00>世<00:13.30>界<00:13.70>的<00:14.00>尽<00:14.40>头
 * 
 * 参考: https://github.com/apoint123/Unilyric/tree/main/lyrics_helper_rs/src/converter/parsers/enhanced_lrc_parser.rs
 */
object EnhancedLrcParser {
    
    // LRC 行时间戳正则: [mm:ss.ms] 或 [mm:ss]
    private val LINE_TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    
    // 增强型 LRC 逐字时间戳正则: <mm:ss.ms>词
    private val WORD_TIMESTAMP_REGEX = Regex("""<(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?>([^<]+)""")
    
    /**
     * 解析增强型 LRC 格式内容
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
                parseSingleLine(trimmed, contentLines, index)?.let { lines.add(it) }
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse Enhanced LRC line $index: $trimmed")
            }
        }
        
        return lines.sortedBy { it.startTime }
    }
    
    /**
     * 解析单行增强型 LRC 歌词
     */
    private fun parseSingleLine(
        line: String,
        allLines: List<String>,
        lineNumber: Int
    ): LyricLine? {
        // 匹配行时间戳
        val lineMatch = LINE_TIMESTAMP_REGEX.find(line) ?: return null
        val lineStartMs = parseTimeToMillis(
            lineMatch.groupValues[1],
            lineMatch.groupValues[2],
            lineMatch.groupValues[3]
        )
        
        // 提取行时间戳后的内容
        val contentAfterTimestamp = line.substring(lineMatch.range.last + 1)
        
        // 检查是否包含逐字时间戳
        val hasWordTimestamps = WORD_TIMESTAMP_REGEX.containsMatchIn(contentAfterTimestamp)
        
        if (hasWordTimestamps) {
            // 解析逐字时间戳
            return parseLineWithWordTimestamps(lineStartMs, contentAfterTimestamp, allLines, lineNumber)
        } else {
            // 普通 LRC 行，没有逐字时间戳
            return parseSimpleLine(lineStartMs, contentAfterTimestamp, allLines, lineNumber)
        }
    }
    
    /**
     * 解析带逐字时间戳的行
     */
    private fun parseLineWithWordTimestamps(
        lineStartMs: Long,
        content: String,
        allLines: List<String>,
        lineNumber: Int
    ): LyricLine {
        val words = mutableListOf<LyricWord>()
        var fullText = ""
        var lastEndTime = lineStartMs
        
        for (match in WORD_TIMESTAMP_REGEX.findAll(content)) {
            val wordStartMs = parseTimeToMillis(
                match.groupValues[1],
                match.groupValues[2],
                match.groupValues[3]
            )
            val text = match.groupValues[4]
            
            if (text.trim().isEmpty()) continue
            
            // 词的结束时间是下一个词的开始时间，或估算
            words.add(
                LyricWord(
                    word = text,
                    startTime = wordStartMs,
                    endTime = wordStartMs // 临时设置，后续会更新
                )
            )
            fullText += text
            lastEndTime = wordStartMs
        }
        
        // 更新每个词的结束时间
        for (i in words.indices) {
            if (i < words.size - 1) {
                // 当前词的结束时间是下一个词的开始时间
                words[i] = words[i].copy(endTime = words[i + 1].startTime)
            } else {
                // 最后一个词的结束时间
                val nextLineStartMs = findNextLineStartTime(allLines, lineNumber)
                words[i] = words[i].copy(
                    endTime = nextLineStartMs ?: (words[i].startTime + 1000)
                )
            }
        }
        
        val lineEndMs = words.lastOrNull()?.endTime ?: (lineStartMs + 2000)
        
        return LyricLine(
            startTime = lineStartMs,
            endTime = lineEndMs,
            text = fullText.trim(),
            words = words
        )
    }
    
    /**
     * 解析普通 LRC 行（无逐字时间戳）
     */
    private fun parseSimpleLine(
        lineStartMs: Long,
        text: String,
        allLines: List<String>,
        lineNumber: Int
    ): LyricLine {
        val nextLineStartMs = findNextLineStartTime(allLines, lineNumber)
        val lineEndMs = nextLineStartMs ?: (lineStartMs + 2000)
        
        val trimmedText = text.trim()
        val words = if (trimmedText.isNotEmpty()) {
            listOf(
                LyricWord(
                    word = trimmedText,
                    startTime = lineStartMs,
                    endTime = lineEndMs
                )
            )
        } else {
            emptyList()
        }
        
        return LyricLine(
            startTime = lineStartMs,
            endTime = lineEndMs,
            text = trimmedText,
            words = words
        )
    }
    
    /**
     * 查找下一行的起始时间
     */
    private fun findNextLineStartTime(allLines: List<String>, currentLineNumber: Int): Long? {
        for (i in (currentLineNumber + 1) until allLines.size) {
            val nextLine = allLines[i].trim()
            if (nextLine.isEmpty()) continue
            
            val match = LINE_TIMESTAMP_REGEX.find(nextLine)
            if (match != null) {
                return parseTimeToMillis(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3]
                )
            }
        }
        return null
    }
    
    /**
     * 将时间字符串转换为毫秒
     */
    private fun parseTimeToMillis(minutes: String, seconds: String, millisStr: String): Long {
        val min = minutes.toLongOrNull() ?: 0L
        val sec = seconds.toLongOrNull() ?: 0L
        val millis = if (millisStr.isEmpty()) {
            0L
        } else {
            // 处理不同长度的毫秒部分
            when (millisStr.length) {
                1 -> millisStr.toLongOrNull()?.times(100) ?: 0L  // .5 -> 500ms
                2 -> millisStr.toLongOrNull()?.times(10) ?: 0L   // .50 -> 500ms
                3 -> millisStr.toLongOrNull() ?: 0L              // .500 -> 500ms
                else -> 0L
            }
        }
        
        return (min * 60 + sec) * 1000 + millis
    }
    
    /**
     * 检查是否是元数据行
     */
    private fun isMetadataLine(line: String): Boolean {
        return line.startsWith("[ti:") ||
               line.startsWith("[ar:") ||
               line.startsWith("[al:") ||
               line.startsWith("[by:") ||
               line.startsWith("[offset:") ||
               line.startsWith("[length:")
    }
}
