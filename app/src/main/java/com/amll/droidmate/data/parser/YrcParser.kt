package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.LyricWord
import org.json.JSONObject
import timber.log.Timber

object YrcParser {

    private val yrcLineTimestampRegex = Regex("""^\[(?<start>\d+),(?<duration>\d+)]""")
    private val yrcSyllableTimestampRegex = Regex("""\((?<start>\d+),(?<duration>\d+),(?<zero>0)\)""")

    fun parse(content: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val metadata = mutableMapOf<String, MutableList<String>>()
        
        val contentLines = content.lines()
        Timber.d("YrcParser: parsing ${contentLines.size} lines")

        for ((index, raw) in contentLines.withIndex()) {
            val lineNum = index + 1
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("{\"t\":")) {
                parseJsonMetadataLine(line, metadata, lineNum)
                continue
            }

            if (yrcLineTimestampRegex.containsMatchIn(line)) {
                try {
                    parseYrcLine(line, lineNum)?.let {
                        lines.add(it)
                        if (lines.size <= 3) {
                            Timber.d("YrcParser: parsed line $lineNum - ${it.text.take(30)}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse YRC line $lineNum")
                }
            } else {
                if (lineNum <= 5 || (lineNum > contentLines.size - 3)) {
                    Timber.d("YrcParser: skipping line $lineNum (not matching YRC format): ${line.take(50)}")
                }
            }
        }
        
        Timber.d("YrcParser: parsed ${lines.size} lyric lines total")
        return lines
    }

    private fun parseJsonMetadataLine(
        line: String,
        metadata: MutableMap<String, MutableList<String>>,
        lineNum: Int
    ) {
        try {
            val json = JSONObject(line)
            val cArray = json.optJSONArray("c") ?: return
            if (cArray.length() == 0) return

            val keyPart = cArray.optJSONObject(0)?.optString("tx").orEmpty().trim()
            val key = keyPart.trimEnd { it == ':' || it == '：' || it.isWhitespace() }
            if (key.isEmpty()) return

            val values = mutableListOf<String>()
            for (i in 1 until cArray.length()) {
                val value = cArray.optJSONObject(i)?.optString("tx").orEmpty().trim()
                if (value.isNotEmpty() && value != "/") {
                    values.add(value)
                }
            }

            if (values.isNotEmpty()) {
                metadata.getOrPut(key) { mutableListOf() }.add(values.joinToString(", "))
            }
        } catch (e: Exception) {
            Timber.w(e, "YRC metadata parse failed on line $lineNum")
        }
    }

    private fun parseYrcLine(line: String, lineNum: Int): LyricLine? {
        val lineTs = yrcLineTimestampRegex.find(line) ?: return null
        val lineStart = lineTs.groups["start"]?.value?.toLongOrNull() ?: return null
        val lineDuration = lineTs.groups["duration"]?.value?.toLongOrNull() ?: return null
        val afterTs = line.substring(lineTs.range.last + 1)

        val words = mutableListOf<LyricWord>()
        val matches = yrcSyllableTimestampRegex.findAll(afterTs).toList()
        if (matches.isEmpty()) return null

        for ((i, tsMatch) in matches.withIndex()) {
            val textStart = tsMatch.range.last + 1
            val textEnd = if (i + 1 < matches.size) matches[i + 1].range.first else afterTs.length
            val rawText = afterTs.substring(textStart, textEnd)
            val processed = processSyllableText(rawText, words) ?: continue
            val (cleanText, endsWithSpace) = processed

            val sylStart = tsMatch.groups["start"]?.value?.toLongOrNull() ?: continue
            val sylDuration = tsMatch.groups["duration"]?.value?.toLongOrNull() ?: continue
            val text = if (endsWithSpace) "$cleanText " else cleanText

            words.add(
                LyricWord(
                    word = text,
                    startTime = sylStart,
                    endTime = sylStart + sylDuration
                )
            )
        }

        if (words.isEmpty()) return null

        return LyricLine(
            startTime = lineStart,
            endTime = lineStart + lineDuration,
            text = words.joinToString(separator = "") { it.word }.trimEnd(),
            words = words
        )
    }
}
