package com.amll.droidmate.data.converter

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.domain.model.TTMLMetadata
import org.junit.Assert.*
import org.junit.Test

class TTMLConverterTest {
    
    @Test
    fun testTimeToMillis() {
        // Test mm:ss.ms format
        assertEquals(0L, TTMLConverter.timeToMillis("00:00.000"))
        assertEquals(1000L, TTMLConverter.timeToMillis("00:01.000"))
        assertEquals(61000L, TTMLConverter.timeToMillis("01:01.000"))
        assertEquals(61500L, TTMLConverter.timeToMillis("01:01.500"))
    }
    
    @Test
    fun testFormatTime() {
        assertEquals("00:00.000", TTMLConverter.formatTime(0L))
        assertEquals("00:01.000", TTMLConverter.formatTime(1000L))
        assertEquals("01:01.000", TTMLConverter.formatTime(61000L))
        assertEquals("01:01.500", TTMLConverter.formatTime(61500L))
    }
    
    @Test
    fun testToTTMLString() {
        val lines = listOf(
            LyricLine(0L, 1000L, "歌"),
            LyricLine(1000L, 2000L, "词")
        )
        
        val lyrics = TTMLLyrics(
            metadata = TTMLMetadata(
                title = "Test Song",
                artist = "Test Artist"
            ),
            lines = lines
        )
        
        val ttmlString = TTMLConverter.toTTMLString(lyrics)
        
        assertTrue(ttmlString.contains("<?xml version=\"1.0\""))
        assertTrue(ttmlString.contains("<tt xmlns"))
        assertTrue(ttmlString.contains("Test Song"))
        assertTrue(ttmlString.contains("Test Artist"))
        assertTrue(ttmlString.contains("<span begin=\"00:00.000\" end=\"00:01.000\">歌</span>"))
    }
    
    @Test
    fun testFromLRC() {
        val lrcContent = """
            [00:00.00]歌词第一行
            [00:02.00]歌词第二行
            [00:04.00]歌词第三行
        """.trimIndent()
        
        val result = TTMLConverter.fromLRC(lrcContent)
        
        assertNotNull(result)
        assertEquals(3, result?.lines?.size)
        assertEquals("歌词第一行", result?.lines?.get(0)?.text)
        assertEquals(0L, result?.lines?.get(0)?.startTime)
        assertEquals(2000L, result?.lines?.get(1)?.startTime)
    }
    
    @Test
    fun testFromLRCWithMilliseconds() {
        val lrcContent = """
            [00:00.500]歌词第一行
            [00:02.250]歌词第二行
        """.trimIndent()
        
        val result = TTMLConverter.fromLRC(lrcContent)
        
        assertNotNull(result)
        assertEquals(500L, result?.lines?.get(0)?.startTime)
        assertEquals(2250L, result?.lines?.get(1)?.startTime)
    }
}
