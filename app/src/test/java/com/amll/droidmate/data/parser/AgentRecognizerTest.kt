package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class AgentRecognizerTest {
    @Test
    fun `recognizeAgents detects inline agent prefixes and sets agent field`() {
        val lines = listOf(
            createLine("A: 你好"),
            createLine("A: 再见"),
            createLine("B: 早上好"),
            createLine("B: 晚安")
        )

        val result = AgentRecognizer.recognizeAgents(lines)
        assertTrue(result.all { it.agent != null })
        val agents = result.mapNotNull { it.agent }.distinct()
        assertEquals(2, agents.size)
        assertTrue(result[0].isDuet)
        assertTrue(result[2].isDuet)
    }

    private fun createLine(text: String): LyricLine {
        return LyricLine(
            startTime = 0,
            endTime = 1000,
            text = text,
            words = listOf(LyricWord(text, 0, 1000))
        )
    }
}
