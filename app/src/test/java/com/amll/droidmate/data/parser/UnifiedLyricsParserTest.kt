package com.amll.droidmate.data.parser

import org.junit.Assert.*
import org.junit.Test

class UnifiedLyricsParserTest {

    @Test
    fun `parse KRC sample should not be misdetected as QRC`() {
        val sample = """
            [ti:Sample]
            [ar:Artist]
            [kana:aa(123,456)bb]
            [0,1000]<0,500,0>a<500,500,0>b
        """.trimIndent()

        val lyrics = UnifiedLyricsParser.parse(sample, title = "Sample", artist = "Artist")
        assertNotNull(lyrics)
        assertEquals("Sample", lyrics?.metadata?.title)
        assertEquals("Artist", lyrics?.metadata?.artist)
        assertEquals(1, lyrics?.lines?.size)
        // make sure it didn't end up empty due to QRC parser
        assertEquals("ab", lyrics?.lines?.get(0)?.text)
    }

    @Test
    fun `parse should preserve metadata lines when processing disabled`() {
        val sample = """
            [00:00.000] 词：张三
            [00:01.000] 作曲：李四
            [00:02.000] Hello
        """.trimIndent()

        val lyrics = UnifiedLyricsParser.parse(
            sample,
            title = "Sample",
            artist = "Artist",
            processMetadata = false
        )
        assertNotNull(lyrics)
        println("parsed lines: ${lyrics!!.lines.map { it.text }}")
        assertTrue(lyrics.lines.any { it.text.contains("词：") })
        assertTrue(lyrics.lines.any { it.text.contains("作曲：") })
    }
}
