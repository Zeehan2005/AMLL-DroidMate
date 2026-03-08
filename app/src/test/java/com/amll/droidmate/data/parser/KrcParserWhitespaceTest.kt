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
}
