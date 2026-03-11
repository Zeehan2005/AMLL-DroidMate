package com.amll.droidmate.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsFormatDetectTest {

    @Test
    fun `detects KRC even when kana metadata contains parentheses`() {
        val sample = """
            [ti:Some Title]
            [ar:Some Artist]
            [kana:aa(123,456)bb]
            [0,1000]<0,500,0>a<500,500,0>b
        """.trimIndent()

        val format = LyricsFormat.detect(sample)
        assertEquals(LyricsFormat.KRC, format)
    }

    @Test
    fun `detects QRC only when timestamp and parenthesis appear on same line`() {
        val sample = """
            [100,500]foo(100,200)bar(300,200)
            [200,400]baz(200,100)
        """.trimIndent()

        val format = LyricsFormat.detect(sample)
        assertEquals(LyricsFormat.QRC, format)
    }

    @Test
    fun `does not mistake plain LRC for QRC`() {
        val sample = """
            [00:10.00]Hello world
            [00:20.00]Another line
        """.trimIndent()

        val format = LyricsFormat.detect(sample)
        assertEquals(LyricsFormat.LRC, format)
    }
}
