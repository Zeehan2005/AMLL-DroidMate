package com.amll.droidmate.data.parser

import com.amll.droidmate.data.repository.LyricsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TTMLWhitespacePreserveTest {

    @Test
    fun parser_keeps_visible_spaces_in_plain_p_text() {
        val ttml = """<?xml version="1.0" encoding="UTF-8"?><tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:00.000" end="00:01.000">  Hello  World  </p></div></body></tt>"""

        val lines = TTMLParser.parse(ttml)

        assertEquals(1, lines.size)
        assertEquals("  Hello  World  ", lines[0].text)
    }

    @Test
    fun repository_parser_keeps_visible_spaces_in_span_text() {
        val ttml = """<?xml version="1.0" encoding="UTF-8"?><tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div><p begin="00:00.000" end="00:01.000"><span begin="00:00.000" end="00:00.500"> Hello</span><span begin="00:00.500" end="00:01.000">World </span></p></div></body></tt>"""

        val parsed = LyricsRepository.parseTTML(ttml)

        assertNotNull(parsed)
        assertEquals(1, parsed?.lines?.size)
        assertEquals(" HelloWorld ", parsed?.lines?.get(0)?.text)
    }
}
