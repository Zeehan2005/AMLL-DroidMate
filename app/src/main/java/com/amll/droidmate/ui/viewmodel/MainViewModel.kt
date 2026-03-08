package com.amll.droidmate.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amll.droidmate.data.converter.TTMLConverter
import com.amll.droidmate.data.network.HttpClientFactory
import com.amll.droidmate.data.repository.LyricsCacheRepository
import com.amll.droidmate.data.repository.LyricsRepository
import com.amll.droidmate.domain.model.NowPlayingMusic
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.service.LyricNotificationManager
import com.amll.droidmate.service.MediaInfoService
import com.amll.droidmate.ui.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 主视图模型 - 管理应用的状态和逻辑
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val context: Context = application.applicationContext
    
    // HTTP Client（使用统一配置，缓存存储在 cache 目录）
    private val httpClient = HttpClientFactory.create(context)
    
    // 仓库
    private val lyricsRepository = LyricsRepository(httpClient)
    private val lyricsCacheRepository = LyricsCacheRepository(context)
    
    // 服务
    private val mediaInfoService = MediaInfoService(context)
    private val lyricNotificationManager = LyricNotificationManager(context)
    
    // UI State
    private val _nowPlayingMusic = MutableStateFlow<NowPlayingMusic?>(null)
    val nowPlayingMusic: StateFlow<NowPlayingMusic?> = _nowPlayingMusic
    
    private val _lyrics = MutableStateFlow<TTMLLyrics?>(null)
    val lyrics: StateFlow<TTMLLyrics?> = _lyrics
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage
    
    init {
        setupMediaListener()
        observeLyricNotification()
        Timber.plant(Timber.DebugTree())
    }

    private fun observeLyricNotification() {
        viewModelScope.launch {
            combine(_lyrics, _nowPlayingMusic) { lyrics, music ->
                lyrics to music
            }.collect { (lyrics, music) ->
                updateLyricNotification(lyrics, music)
            }
        }
    }

    private fun updateLyricNotification(lyrics: TTMLLyrics?, music: NowPlayingMusic?) {
        if (!AppSettings.isLyricNotificationEnabled(context)) {
            lyricNotificationManager.cancel()
            return
        }

        if (lyrics == null || music == null) {
            lyricNotificationManager.cancel()
            return
        }

        val time = music.currentPosition
        val currentLine = lyrics.lines.firstOrNull { time in it.startTime..it.endTime }
            ?: lyrics.lines.lastOrNull { it.startTime <= time }

        lyricNotificationManager.showOrUpdate(currentLine)
    }

    fun refreshLyricNotification() {
        updateLyricNotification(_lyrics.value, _nowPlayingMusic.value)
    }
    
    /**
     * 设置媒体监听
     */
    private fun setupMediaListener() {
        viewModelScope.launch {
            mediaInfoService.nowPlayingMusic.collect { music ->
                // 检查是否为新歌曲（标题或歌手改变）
                val oldMusic = _nowPlayingMusic.value
                val isMusicChanged = oldMusic?.let { 
                    it.title != music?.title || it.artist != music?.artist 
                } ?: (music != null)
                
                _nowPlayingMusic.value = music
                Timber.d("Now playing: ${music?.title} - ${music?.artist}")
                
                // 如果歌曲确实改变且有有效的歌曲信息，自动获取歌词
                if (isMusicChanged && music != null) {
                    Timber.i("Music changed, auto-fetching lyrics...")
                    fetchLyrics()
                }
            }
        }
        mediaInfoService.startListening()
    }
    
    /**
     * 智能获取歌词 - 自动搜索多个来源并选择最佳结果
     * 基于 Unilyric 的多源搜索策略:
     * 1. 搜索网易云、QQ音乐、酷狗音乐
     * 2. 优先尝试 AMLL TTML DB (高质量逐字歌词)
     * 3. 回退到各平台的普通歌词
     */
    fun fetchLyrics() {
        val music = _nowPlayingMusic.value
        if (music == null) {
            _errorMessage.value = "未检测到播放信息"
            return
        }
        
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            try {
                Timber.i("Fetching lyrics for: ${music.title} - ${music.artist}")

                val cached = lyricsCacheRepository.findBySong(music.title, music.artist)
                if (cached != null) {
                    val parsed = LyricsRepository.parseTTML(cached.ttmlContent)
                    if (parsed != null) {
                        _lyrics.value = parsed
                        _errorMessage.value = null
                        Timber.i("Loaded lyrics from cache: ${cached.title} - ${cached.artist} (${cached.source})")
                        return@launch
                    }
                }
                
                // 使用智能多源搜索
                val result = lyricsRepository.fetchLyricsAuto(
                    title = music.title,
                    artist = music.artist
                )
                
                if (result.isSuccess && result.lyrics != null) {
                    _lyrics.value = result.lyrics
                    lyricsCacheRepository.upsert(
                        title = music.title,
                        artist = music.artist,
                        source = result.source ?: "auto",
                        ttmlContent = TTMLConverter.toTTMLString(result.lyrics)
                    )
                    Timber.i("Successfully fetched lyrics from ${result.source}")
                } else {
                    _errorMessage.value = result.errorMessage ?: "获取歌词失败"
                    Timber.w("Failed to fetch lyrics: ${result.errorMessage}")
                }
            } catch (e: Exception) {
                _errorMessage.value = "错误: ${e.message}"
                Timber.e(e, "Error fetching lyrics")
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * 从 LRC 文本生成 TTML
     */
    fun convertLRCToTTML(lrcContent: String, title: String, artist: String) {
        viewModelScope.launch {
            try {
                val ttml = TTMLConverter.fromLyrics(
                    content = lrcContent,
                    title = title,
                    artist = artist
                )
                if (ttml != null) {
                    _lyrics.value = ttml
                    Timber.d("Successfully converted LRC to TTML")
                } else {
                    _errorMessage.value = "LRC 转换失败"
                }
            } catch (e: Exception) {
                _errorMessage.value = "转换错误: ${e.message}"
                Timber.e(e, "Error converting LRC to TTML")
            }
        }
    }

    /**
     * 应用自选歌词输入，支持 TTML / LRC / 纯文本
     */
    fun applyCustomLyricsInput(content: String, title: String, artist: String) {
        viewModelScope.launch {
            try {
                val trimmed = content.trim()
                if (trimmed.isBlank()) {
                    _errorMessage.value = "歌词内容为空"
                    return@launch
                }

                val parsed: TTMLLyrics? = TTMLConverter.fromLyrics(
                    content = trimmed,
                    title = if (title.isBlank()) "自选歌词" else title,
                    artist = if (artist.isBlank()) "Unknown" else artist
                )

                if (parsed != null) {
                    _lyrics.value = parsed
                    _errorMessage.value = null
                    lyricsCacheRepository.upsert(
                        title = if (title.isBlank()) "自选歌词" else title,
                        artist = if (artist.isBlank()) "Unknown" else artist,
                        source = "manual",
                        ttmlContent = TTMLConverter.toTTMLString(parsed)
                    )
                } else {
                    _errorMessage.value = "无法识别歌词格式"
                }
            } catch (e: Exception) {
                _errorMessage.value = "应用歌词失败: ${e.message}"
                Timber.e(e, "Error applying custom lyrics input")
            }
        }
    }
    
    /**
     * 导出歌词为 TTML 文件
     */
    fun exportLyricsAsTTML(fileName: String = "lyrics.ttml"): String? {
        val currentLyrics = _lyrics.value ?: return null
        return TTMLConverter.toTTMLString(currentLyrics, formatted = true)
    }
    
    /**
     * 清除错误信息
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * 播放控制
     */
    fun play() {
        mediaInfoService.play()
    }
    
    fun pause() {
        mediaInfoService.pause()
    }
    
    fun skipToNext() {
        mediaInfoService.skipToNext()
    }
    
    fun skipToPrevious() {
        mediaInfoService.skipToPrevious()
    }
    
    fun seekTo(position: Long) {
        Timber.i("MainViewModel.seekTo(position=$position)")
        mediaInfoService.seekTo(position)
    }
    
    fun fastForward() {
        mediaInfoService.fastForward()
    }
    
    fun rewind() {
        mediaInfoService.rewind()
    }
    
    override fun onCleared() {
        super.onCleared()
        lyricNotificationManager.cancel()
        mediaInfoService.stopListening()
        httpClient.close()
    }
}
