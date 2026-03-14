package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine

/**
 * Recognize singer/agent labels in lyric lines such as:
 * "A: ..."  or "（B）: ..."  or "(C): ...".
 *
 * This mirrors Unilyric's agent_recognizer logic.
 */
object AgentRecognizer {
    private val AGENT_REGEX = Regex("^\\s*(?:\\((.+?)\\)|（(.+?)）|([^\\s:()（）]+))\\s*[:：]\\s*")

    fun recognizeAgents(lines: List<LyricLine>): List<LyricLine> {
        var currentAgentId: String? = null
        val nameToId = mutableMapOf<String, String>()
        var nextIdNum = 1

        // First pass: detect agents and annotate lines.
        val annotated = mutableListOf<LyricLine>()

        for (line in lines) {
            val text = line.text
            val match = AGENT_REGEX.find(text)
            if (match != null) {
                val agentName = (1..3)
                    .mapNotNull { idx -> match.groups[idx]?.value?.trim() }
                    .firstOrNull()

                val fullMatch = match.value
                val remaining = text.removePrefix(fullMatch).trimStart()

                if (!agentName.isNullOrBlank()) {
                    val agentId = nameToId.getOrPut(agentName) {
                        "v${nextIdNum++}"
                    }

                    if (remaining.isEmpty()) {
                        // block mode: this line is just an agent marker, skip it but update current
                        currentAgentId = agentId
                        continue
                    }

                    currentAgentId = agentId

                    val newLine = line.copy(
                        text = remaining,
                        agent = agentId,
                        words = adjustWordsForPrefix(line.words, fullMatch, remaining)
                    )
                    annotated += newLine
                    continue
                }
            }

            // no agent prefix found
            val agentForLine = line.agent ?: currentAgentId
            annotated += line.copy(agent = agentForLine)
        }

        // if multiple agents exist, mark duet lines
        val uniqueAgents = annotated.mapNotNull { it.agent }.distinct()
        if (uniqueAgents.size > 1) {
            return annotated.map { line ->
                if (!line.agent.isNullOrBlank()) {
                    line.copy(isDuet = true)
                } else {
                    line
                }
            }
        }

        return annotated
    }

    private fun adjustWordsForPrefix(
        words: List<com.amll.droidmate.domain.model.LyricWord>,
        prefix: String,
        remainingText: String
    ): List<com.amll.droidmate.domain.model.LyricWord> {
        if (words.isEmpty()) return listOf(
            com.amll.droidmate.domain.model.LyricWord(
                word = remainingText,
                startTime = 0L,
                endTime = 0L
            )
        )

        val first = words.first()
        val startTime = first.startTime
        val endTime = words.last().endTime

        val trimmedFirstWord = if (first.word.startsWith(prefix)) {
            first.word.removePrefix(prefix).trimStart()
        } else {
            remainingText
        }

        return listOf(
            com.amll.droidmate.domain.model.LyricWord(
                word = if (trimmedFirstWord.isNotEmpty()) trimmedFirstWord else remainingText,
                startTime = startTime,
                endTime = endTime
            )
        )
    }
}
