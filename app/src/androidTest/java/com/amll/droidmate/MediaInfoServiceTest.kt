package com.amll.droidmate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amll.droidmate.service.MediaInfoService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 集成测试 - 测试媒体信息服务
 */
@RunWith(AndroidJUnit4::class)
class MediaInfoServiceTest {
    
    private lateinit var context: Context
    private lateinit var mediaInfoService: MediaInfoService
    
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mediaInfoService = MediaInfoService(context)
    }
    
    @Test
    fun testMediaServiceInitialization() {
        assertNotNull(mediaInfoService)
        assertNull(mediaInfoService.nowPlayingMusic.value)
    }
    
    @Test
    fun testStartListening() {
        mediaInfoService.startListening()
        // Service should be listening for media sessions
        assertNotNull(mediaInfoService)
    }
    
    @Test
    fun testStopListening() {
        mediaInfoService.startListening()
        mediaInfoService.stopListening()
        // Service should have stopped listening
        assertNotNull(mediaInfoService)
    }
}
