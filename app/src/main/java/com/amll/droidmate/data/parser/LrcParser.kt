package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.LyricWord
import timber.log.Timber

private data class TempLrcEntry(
    val timestampMs: Long,
    val text: String
)

object LrcParser {

    private val lrcLineRegex = Regex("""^((?:\[\d{2,}:\d{2}[.:]\d{2,3}])+)(.*)$""")
    private val tsExtractRegex = Regex("""\[(\d{2,}):(\d{2})[.:](\d{2,3})]""")
    private const val DEFAULT_LAST_LINE_DURATION_MS = 10_000L

    fun parse(content: String): List<LyricLine> {
        val entries = mutableListOf<TempLrcEntry>()
        val metadata = mutableMapOf<String, MutableList<String>>()

        for ((index, raw) in content.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (parseAndStoreMetadata(line, metadata)) continue

            val lineCaps = lrcLineRegex.find(line) ?: continue
            val allTs = lineCaps.groupValues[1]
            val text = normalizeTextWhitespace(lineCaps.groupValues[2])

            for (ts in tsExtractRegex.findAll(allTs)) {
                val minutes = ts.groupValues[1].toLongOrNull() ?: continue
                val seconds = ts.groupValues[2].toLongOrNull() ?: continue
                if (seconds >= 60) {
                    Timber.e("Invalid LRC seconds on line ${index + 1}: $line")
                    continue
                }
                val fraction = ts.groupValues[3]
                val milliseconds = when (fraction.length) {
                    2 -> (fraction.toLongOrNull() ?: 0L) * 10L
                    3 -> fraction.toLongOrNull() ?: 0L
                    else -> 0L
                }
                entries.add(TempLrcEntry((minutes * 60 + seconds) * 1000 + milliseconds, text))
            }
        }

        // determine global offset from metadata
        val offsetMs = metadata["offset"]?.firstOrNull()?.toLongOrNull() ?: 0L

        val sorted = entries.sortedBy { it.timestampMs }
        val finalLines = mutableListOf<LyricLine>()
        var i = 0
        while (i < sorted.size) {
            val startMsOriginal = sorted[i].timestampMs
            val startMs = startMsOriginal + offsetMs
            val groupEndIndex = sorted.subList(i, sorted.size).indexOfFirst { it.timestampMs != startMsOriginal }
                .let { if (it == -1) sorted.size else i + it }

            val group = sorted.subList(i, groupEndIndex)
            val endMsOriginal = sorted.getOrNull(groupEndIndex)?.timestampMs?.coerceAtLeast(startMsOriginal)
                ?: (startMsOriginal + DEFAULT_LAST_LINE_DURATION_MS)
            val endMs = endMsOriginal + offsetMs

            val meaningful = group.filter { it.text.isNotEmpty() }
            if (meaningful.isNotEmpty()) {
                val mainText = meaningful[0].text
                val transText = meaningful.getOrNull(1)?.text
                val romanText = meaningful.getOrNull(2)?.text
                finalLines.add(
                    LyricLine(
                        startTime = startMs,
                        endTime = endMs,
                        text = mainText,
                        translation = transText,
                        transliteration = romanText,
                        words = listOf(
                            LyricWord(
                                word = mainText,
                                startTime = startMs,
                                endTime = endMs
                            )
                        )
                    )
                )
            }

            i = groupEndIndex
        }

        return finalLines
    }
}
