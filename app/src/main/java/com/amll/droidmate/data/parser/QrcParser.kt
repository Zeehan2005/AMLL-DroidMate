package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.LyricWord

object QrcParser {

    private val lyricTokenRegex = Regex("""(?<text>.*?)\((?<start>\d+),(?<duration>\d+)\)""")
    private val qrcLineTimestampRegex = Regex("""^\[\d+,\d+]""")

    fun parse(content: String): List<LyricLine> {
        val finalLines = mutableListOf<LyricLine>()
        val metadata = mutableMapOf<String, MutableList<String>>()

        for (raw in content.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (parseAndStoreMetadata(line, metadata)) continue

            parseSingleLine(line)?.let { finalLines.add(it) }
        }

        return finalLines
    }

    private fun parseSingleLine(line: String): LyricLine? {
        val lineContent = qrcLineTimestampRegex.replace(line, "")
        val words = mutableListOf<LyricWord>()

        for (capture in lyricTokenRegex.findAll(lineContent)) {
            val rawText = capture.groups["text"]?.value.orEmpty()
            val processed = processSyllableText(rawText, words) ?: continue
            val (cleanText, endsWithSpace) = processed

            val startMs = capture.groups["start"]?.value?.toLongOrNull() ?: continue
            val durationMs = capture.groups["duration"]?.value?.toLongOrNull() ?: continue

            val text = if (endsWithSpace) "$cleanText " else cleanText
            words.add(
                LyricWord(
                    word = text,
                    startTime = startMs,
                    endTime = startMs + durationMs
                )
            )
        }

        if (words.isEmpty()) return null

        return LyricLine(
            startTime = words.first().startTime,
            endTime = words.last().endTime,
            text = words.joinToString(separator = "") { it.word }.trimEnd(),
            words = words
        )
    }
}
