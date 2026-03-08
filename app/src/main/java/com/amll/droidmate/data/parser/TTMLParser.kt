package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.LyricWord
import timber.log.Timber
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TTML 格式解析器
 * 从 TTML XML 格式中提取歌词行和词级时间戳
 */
object TTMLParser {
    
    private val factory = DocumentBuilderFactory.newInstance()
    
    fun parse(content: String): List<LyricLine> {
        return try {
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(content.byteInputStream())
            parseTTMLDocument(doc)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse TTML content")
            emptyList()
        }
    }
    
    private fun parseTTMLDocument(doc: Document): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        
        try {
            // 获取 body 元素
            val body = doc.getElementsByTagName("body").item(0) as? Element ?: return emptyList()
            
            // 遍历所有 <p> 标签（每一行）
            val paragraphs = body.getElementsByTagName("p")
            for (i in 0 until paragraphs.length) {
                val pElement = paragraphs.item(i) as? Element ?: continue
                parseParagraph(pElement)?.let { lines.add(it) }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse TTML document structure")
        }
        
        return lines
    }
    
    private fun parseParagraph(pElement: Element): LyricLine? {
        try {
            // 获取时间信息
            val beginStr = pElement.getAttribute("begin") ?: ""
            val endStr = pElement.getAttribute("end") ?: ""
            
            val startTime = timeStrToMillis(beginStr)
            var endTime = timeStrToMillis(endStr)
            
            // 如果没有结束时间，使用起始时间+3秒作为默认值
            if (endTime <= startTime) {
                endTime = startTime + 3000L
            }
            
            // 解析词级时间戳（<span> 标签）
            val words = mutableListOf<LyricWord>()
            val spans = pElement.getElementsByTagName("span")
            
            for (j in 0 until spans.length) {
                val span = spans.item(j) as? Element ?: continue
                
                // 跳过翻译和音译标签
                val role = span.getAttribute("ttm:role") ?: ""
                if (role.isNotEmpty() && role != "word") {
                    continue
                }
                
                val text = span.textContent ?: ""
                if (text.isBlank()) continue
                
                val spanBegin = span.getAttribute("begin") ?: ""
                val spanEnd = span.getAttribute("end") ?: ""
                
                if (spanBegin.isNotEmpty() && spanEnd.isNotEmpty()) {
                    val spanStartTime = timeStrToMillis(spanBegin)
                    val spanEndTime = timeStrToMillis(spanEnd)
                    
                    words.add(
                        LyricWord(
                            word = text,
                            startTime = spanStartTime,
                            endTime = spanEndTime
                        )
                    )
                }
            }
            
            // 如果没有词级时间戳，使用整行文本
            if (words.isEmpty()) {
                val fullText = pElement.textContent?.trim() ?: return null
                if (fullText.isBlank()) return null
                
                return LyricLine(
                    startTime = startTime,
                    endTime = endTime,
                    text = fullText,
                    words = emptyList()
                )
            }
            
            // 构建完整文本
            val fullText = words.joinToString(separator = "") { it.word }.trim()
            
            return LyricLine(
                startTime = startTime,
                endTime = endTime,
                text = fullText,
                words = words
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse paragraph")
            return null
        }
    }
    
    /**
     * 将 TTML 时间格式转换为毫秒
     * 格式: mm:ss.mmm (例: 00:12.345)
     */
    private fun timeStrToMillis(timeStr: String): Long {
        return try {
            if (timeStr.isBlank()) return 0L
            
            val trimmed = timeStr.trim()
            val parts = trimmed.split(":")
            
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
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse time string: $timeStr")
            0L
        }
    }
}
