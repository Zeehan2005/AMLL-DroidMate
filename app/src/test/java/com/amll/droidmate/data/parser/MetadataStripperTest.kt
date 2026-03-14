package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class MetadataStripperTest {
    @Test
    fun `stripMetadataLines removes common metadata lines at start and end`() {
        val lines = listOf(
            createLine("词：张三"),
            createLine("曲：李四"),
            createLine("真正的歌词第一行"),
            createLine("第二行歌词"),
            createLine("制作：某某"),
            createLine("版权所有，未经许可不得使用")
        )

        val result = MetadataStripper.stripMetadataLines(lines)
        assertEquals(2, result.size)
        assertEquals("真正的歌词第一行", result[0].text)
        assertEquals("第二行歌词", result[1].text)
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
