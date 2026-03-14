package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import timber.log.Timber

/**
 * Lyrics metadata stripper.
 *
 * This is adapted from Unilyric's metadata_stripper.rs.
 * It attempts to remove common metadata lines ("词：...", "作词：...", ".").
 */
object MetadataStripper {

    private val DEFAULT_KEYWORDS = listOf(
        "作曲",
        "作词",
        "编曲",
        "演唱",
        "歌手",
        "歌名",
        "专辑",
        "发行",
        "出品",
        "监制",
        "录音",
        "混音",
        "母带",
        "吉他",
        "贝斯",
        "鼓",
        "键盘",
        "弦乐",
        "和声",
        "版权",
        "制作",
        "制作人",
        "原唱",
        "翻唱",
        "词",
        "曲",
        "发行人",
        "发行公司",
        "录音制作",
        "制作团队",
        "音乐制作",
        "录音师",
        "混音工程师",
        "混音师",
        "母带工程师",
        "母带处理工程师",
        "制作统筹",
        "艺术指导",
        "出品团队",
        "发行方",
        "和声编写",
        "封面设计",
        "策划",
        "营销推广",
        "推广宣传",
        "特别鸣谢",
        "出品人",
        "出品公司",
        "联合出品",
        "词曲提供",
        "制作公司",
        "录音室",
        "混音室",
        "母带后期制作人",
        "母带后期处理工程师",
        "鸣谢",
        "工作室",
        "特别企划",
        "音频编辑",
        "词曲协力",
        "企划",
        "宣传",
        "统筹",
        "推广",
        "封面",
        "项目统筹",
        "制作公司/OP",
        "企划宣传",
        "宣传/推广",
        "出品/发行",
        "出品/发行方",
        "词Lyrics",
        "曲Composer",
        "制作人Record Producer",
        "吉他Guitar",
        "音乐制作Music Production",
        "录音师Recording Engineer",
        "混音工程师Mixing Engineer",
        "母带工程师Mastering Engineer",
        "和声Backing Vocal",
        "制作统筹Executive Producer",
        "艺术指导Art Director",
        "监制Chief Producer",
        "出品团队Production Team",
        "发行方Publisher",
        "词Lyricist",
        "编曲Arranger",
        "制作人Producer",
        "和声Backing Vocals",
        "和声编写Backing Vocals Design",
        "混音Mixing Engineer",
        "封面设计Cover Design",
        "策划Planner",
        "营销推广Marketing Promotion",
        "总策划Chref Planner",
        "特别鸣谢Acknowledgement",
        "出品人Chief Producer",
        "出品公司Production Company",
        "Productions",
        "Limited",
        "Productions Limited",
        "联合出品Co-produced by",
        "联合出品Jointly Produced by",
        "联合出品Co-production",
        "出品方Presenter",
        "出品方Presented by",
        "词曲提供Lyrics and Composition Provided by",
        "词曲提供Music and Lyrics Provided by",
        "词曲提供Lyrics & Composition Provided by",
        "词曲提供Words and Music by",
        "发行Distribution",
        "发行Release",
        "发行Distributed by",
        "发行Released by",
        "制作公司Produce Company",
        "推广策划Promotion Planning",
        "营销策略Marketing Strategy",
        "推广策略Promotion Strategy",
        "Strings",
        "First Violin",
        "Second Violin",
        "Viola",
        "Cello",
        "Vocal Producer",
        "Supervised production",
        "Copywriting",
        "Design",
        "Planner and coordinator",
        "Propaganda",
        "Arrangement",
        "Guitars",
        "Bass",
        "Drums",
        "Backing Vocal Arrangement",
        "Strings Arrangement",
        "Recording Studio",
        "OP/发行",
        "混音/母带工程师",
        "OP/SP",
        "词Lyrics"
    )

    private val DEFAULT_REGEX_PATTERNS = listOf(
        "(?:【.*?未经.*?】|\\(.*?未经.*?\\)|「.*?未经.*?」|（.*?未经.*?）|『.*?未经.*?』)",
        "(?:【.*?音乐人.*?】|\\(.*?音乐人.*?\\)|「.*?音乐人.*?」|（.*?音乐人.*?）|『.*?音乐人.*?』)",
        ".*?未经.*?许可.*?不得.*?使用.*?",
        ".*?未经.*?许可.*?不得.*?方式.*?",
        "未经著作权人书面许可，\\s*不得以任何方式\\s*[(\\u{FF08}]包括.*?等[)\\u{FF09}]\\s*使用",
        // common credit line patterns (company names)
        """^[A-Za-z0-9\s&\-]+(?:Music|Productions?|Limited|Inc\.?|LLC|Group)$"""
    )

    private const val METADATA_SCAN_LIMIT = 12

    fun stripMetadataLines(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines

        val rules = StrippingRules.default()
        if (!rules.hasRules()) return lines

        val firstIndex = findFirstLyricLineIndex(lines, rules, METADATA_SCAN_LIMIT)
        val lastExclusive = findLastLyricLineExclusiveIndex(lines, firstIndex, rules, METADATA_SCAN_LIMIT)

        if (firstIndex >= lastExclusive) return emptyList()
        return lines.subList(firstIndex, lastExclusive)
    }

    private data class StrippingRules(
        val keywords: List<String>,
        val regexes: List<Regex>
    ) {
        fun hasRules(): Boolean = keywords.isNotEmpty() || regexes.isNotEmpty()

        companion object {
            fun default(): StrippingRules {
                val compiled = DEFAULT_REGEX_PATTERNS.mapNotNull { pattern ->
                    try {
                        Regex(pattern, RegexOption.IGNORE_CASE)
                    } catch (e: Exception) {
                        Timber.w("[MetadataStripper] Failed to compile regex '$pattern': $e")
                        null
                    }
                }
                return StrippingRules(DEFAULT_KEYWORDS.map { it.lowercase() }, compiled)
            }
        }
    }

    private fun cleanTextForCheck(line: String): String {
        var text = line.trim()

        val brackets = listOf('(' to ')', '（' to '）', '【' to '】')

        for ((open, close) in brackets) {
            if (text.startsWith(open)) {
                if (text.endsWith(close) && text.length > 1) {
                    val stripped = text.removePrefix(open.toString()).removeSuffix(close.toString()).trim()
                    text = stripped
                    break
                }
                val closeIndex = text.indexOf(close)
                if (closeIndex >= 0) {
                    val remainder = text.substring(closeIndex + close.toString().length).trimStart()
                    text = remainder
                    break
                }
            }
        }

        return text
    }

    private fun lineContainsCopyrightTerms(line: String): Boolean {
        val lower = line.lowercase()
        val keywords = listOf(
            "版权所有",
            "许可",
            "权利",
            "必究",
            "使用",
            "未经",
            "同意",
            "禁止",
            "复制",
            "传播"
        )

        val brackets = listOf('(' to ')', '（' to '）', '【' to '】', '「' to '」')

        for ((open, close) in brackets) {
            val start = lower.indexOf(open)
            if (start >= 0) {
                val end = lower.indexOf(close, start + 1)
                if (end > start) {
                    val inner = lower.substring(start + 1, end)
                    if (keywords.any { inner.contains(it) }) return true
                }
            }
        }

        if (keywords.count { lower.contains(it) } >= 3) return true
        return false
    }

    private fun lineMatchesRules(line: String, rules: StrippingRules): Boolean {
        val cleaned = cleanTextForCheck(line)
        val lower = cleaned.lowercase()

        for (keyword in rules.keywords) {
            if (lower.startsWith(keyword)) {
                val rest = lower.removePrefix(keyword).trimStart()
                if (rest.startsWith(":") || rest.startsWith("：")) {
                    return true
                }
            }
        }

        if (lineContainsCopyrightTerms(line)) return true

        if (rules.regexes.any { it.containsMatchIn(line) }) return true

        return false
    }

    private fun lineLooksLikeMetadata(line: String): Boolean {
        return line.contains(':') || line.contains('：') || line.contains('-')
    }

    private fun findFirstLyricLineIndex(
        lines: List<LyricLine>,
        rules: StrippingRules,
        limit: Int
    ): Int {
        var lastValidMetadataIndex: Int? = null
        for ((i, line) in lines.withIndex().take(limit)) {
            val text = line.text
            val strictMatch = lineMatchesRules(text, rules)
            if (!strictMatch && !lineLooksLikeMetadata(text)) {
                break
            }
            if (strictMatch) {
                lastValidMetadataIndex = i
            }
        }
        return (lastValidMetadataIndex ?: -1).let { if (it < 0) 0 else it + 1 }
    }

    private fun findLastLyricLineExclusiveIndex(
        lines: List<LyricLine>,
        firstLyricIndex: Int,
        rules: StrippingRules,
        limit: Int
    ): Int {
        if (firstLyricIndex >= lines.size) return firstLyricIndex

        val footerStart = (lines.size - limit).coerceAtLeast(firstLyricIndex)
        var firstValidFooterIndex: Int? = null

        for (i in lines.size - 1 downTo footerStart) {
            val text = lines[i].text
            val strictMatch = lineMatchesRules(text, rules)
            if (!strictMatch && !lineLooksLikeMetadata(text)) {
                break
            }
            if (strictMatch) {
                firstValidFooterIndex = i
            }
        }

        return firstValidFooterIndex ?: lines.size
    }
}
