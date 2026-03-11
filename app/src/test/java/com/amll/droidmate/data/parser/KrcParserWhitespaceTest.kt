package com.amll.droidmate.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class KrcParserWhitespaceTest {

    @Test
    fun keeps_leading_space_between_english_words() {
        val content = """[0,2000]<0,800,0>It's<800,1200,0> ridiculous"""

        val lines = KrcParser.parse(content)

        assertEquals(1, lines.size)
        assertEquals("It's ridiculous", lines[0].text)
        assertEquals("It's", lines[0].words[0].word)
        assertEquals(" ridiculous", lines[0].words[1].word)
    }

    @Test
    fun parses_embedded_language_translation_and_romanization() {
        val languageBase64 = "eyJjb250ZW50IjpbeyJ0eXBlIjoxLCJseXJpY0NvbnRlbnQiOltbIue/u+ivkeesrOS4gOihjCJdLFsi57+76K+R56ys5LqM6KGMIl1dfSx7InR5cGUiOjAsImx5cmljQ29udGVudCI6W1sicGluIHlpbiBvbmUiXSxbInBpbiB5aW4gdHdvIl1dfV19"
        val content = """
            [language:$languageBase64]
            [0,1200]<0,500,0>第<500,700,0>一行
            [1200,1200]<0,500,0>第<500,700,0>二行
        """.trimIndent()

        val lines = KrcParser.parse(content)

        assertEquals(2, lines.size)
        assertEquals("翻译第一行", lines[0].translation)
        assertEquals("翻译第二行", lines[1].translation)
        assertEquals("pin yin one", lines[0].transliteration)
        assertEquals("pin yin two", lines[1].transliteration)
    }

    @Test
    fun applies_global_offset_metadata() {
        val content = """
            [offset:500]
            [0,1000]<0,500,0>a<500,500,0>b
        """.trimIndent()

        val lines = KrcParser.parse(content)

        assertEquals(1, lines.size)
        assertEquals(500, lines[0].startTime)
        assertEquals(1500, lines[0].endTime)
        assertEquals(500, lines[0].words[0].startTime)
        assertEquals(1000, lines[0].words[0].endTime)
        assertEquals(1000, lines[0].words[1].startTime)
    }
}
