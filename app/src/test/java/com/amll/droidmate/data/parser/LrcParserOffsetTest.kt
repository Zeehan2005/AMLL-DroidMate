package com.amll.droidmate.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserOffsetTest {

    @Test
    fun lrc_applies_global_offset() {
        val content = """
            [offset:200]
            [00:00.00]Hello
        """.trimIndent()

        val lines = LrcParser.parse(content)
        assertEquals(1, lines.size)
        assertEquals(200, lines[0].startTime)
        assertEquals(10200, lines[0].endTime) // default last line duration
    }

    @Test
    fun enhanced_lrc_applies_global_offset() {
        val content = """
            [offset:300]
            [00:00.00]<00:00.00>Hi<00:01.00>There
        """.trimIndent()

        val lines = EnhancedLrcParser.parse(content)
        assertEquals(1, lines.size)
        assertEquals(300, lines[0].startTime)
        assertEquals(1300, lines[0].endTime) // last word start + offset
        assertEquals(300, lines[0].words[0].startTime)
        assertEquals(1300, lines[0].words[1].startTime)
    }}