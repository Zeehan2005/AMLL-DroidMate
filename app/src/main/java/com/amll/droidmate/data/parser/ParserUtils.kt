package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.LyricWord

private val METADATA_TAG_REGEX = Regex("""^\[(?<key>[a-zA-Z]+):(?<value>.*)]$""")
private const val TOLERANCE_MS: Long = 50

fun normalizeTextWhitespace(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.split(Regex("\\s+")).joinToString(" ")
}

fun parseAndStoreMetadata(line: String, rawMetadata: MutableMap<String, MutableList<String>>): Boolean {
    val match = METADATA_TAG_REGEX.find(line) ?: return false
    val key = match.groups["key"]?.value?.trim().orEmpty()
    if (key.isEmpty()) return false
    val value = normalizeTextWhitespace(match.groups["value"]?.value.orEmpty())
    rawMetadata.getOrPut(key) { mutableListOf() }.add(value)
    return true
}

fun processSyllableText(rawTextSlice: String, words: MutableList<LyricWord>): Pair<String, Boolean>? {
    val hasLeadingSpace = rawTextSlice.firstOrNull()?.isWhitespace() == true
    val hasTrailingSpace = rawTextSlice.lastOrNull()?.isWhitespace() == true
    val cleanText = rawTextSlice.trim()

    if (hasLeadingSpace && words.isNotEmpty()) {
        val last = words.last()
        if (!last.word.endsWith(" ")) {
            words[words.lastIndex] = last.copy(word = "${last.word} ")
        }
    }

    if (cleanText.isEmpty()) {
        return null
    }

    return cleanText to hasTrailingSpace
}

fun mergeLyricLines(
    mainLines: List<LyricLine>,
    translationLines: List<LyricLine>?,
    romanizationLines: List<LyricLine>?
): List<LyricLine> {
    if (translationLines.isNullOrEmpty() && romanizationLines.isNullOrEmpty()) {
        return mainLines
    }

    var transIndex = 0
    var romanIndex = 0

    val trans = translationLines.orEmpty().sortedBy { it.startTime }
    val roman = romanizationLines.orEmpty().sortedBy { it.startTime }

    return mainLines.map { main ->
        while (transIndex < trans.size && trans[transIndex].startTime + TOLERANCE_MS < main.startTime) {
            transIndex += 1
        }
        while (romanIndex < roman.size && roman[romanIndex].startTime + TOLERANCE_MS < main.startTime) {
            romanIndex += 1
        }

        val translation = trans.getOrNull(transIndex)
            ?.takeIf { kotlin.math.abs(it.startTime - main.startTime) <= TOLERANCE_MS }
            ?.text

        val romanization = roman.getOrNull(romanIndex)
            ?.takeIf { kotlin.math.abs(it.startTime - main.startTime) <= TOLERANCE_MS }
            ?.text

        if (translation != null) transIndex += 1
        if (romanization != null) romanIndex += 1

        main.copy(
            translation = translation ?: main.translation,
            transliteration = romanization ?: main.transliteration
        )
    }
}
