package com.amll.droidmate.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amll.droidmate.data.converter.TTMLConverter
import com.amll.droidmate.data.repository.LyricsCacheRepository
import com.amll.droidmate.data.repository.LyricsRepository
import com.amll.droidmate.ui.screens.getAppNameFromPackage
import com.amll.droidmate.di.ServiceLocator
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

    // HTTP Client & 仓库（由 ServiceLocator 提供以便集中管理）
    private val httpClient = ServiceLocator.provideHttpClient(context)
    // make this mutable so tests can inject a fake repository if needed
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var lyricsRepository = ServiceLocator.provideLyricsRepository(context)
    private val lyricsCacheRepository = ServiceLocator.provideLyricsCacheRepository(context)
    
    // 服务
    private val mediaInfoService = MediaInfoService(context)

    /**
     * Real notification manager; tests can replace via the internal var below.
     */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var lyricNotificationManager: LyricNotificationManager =
        LyricNotificationManager(context)

    // UI State
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal val _nowPlayingMusic = MutableStateFlow<NowPlayingMusic?>(null)
    val nowPlayingMusic: StateFlow<NowPlayingMusic?> = _nowPlayingMusic
    
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal val _lyrics = MutableStateFlow<TTMLLyrics?>(null)
    val lyrics: StateFlow<TTMLLyrics?> = _lyrics

    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage
    
    // tracks whether we've already shown the paused notification. After a
    // pause occurs we'll send one update with ongoing=false, then refrain from
    // sending additional updates until playback resumes.  This ignores whether
    // the user actually swipes it away.
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var pausedNotificationSent: Boolean = false

    private val deleteReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == LyricNotificationManager.ACTION_LYRIC_NOTIFICATION_DISMISSED) {
                Timber.i("Lyric notification deleted by user, cancelling")
                lyricNotificationManager.cancel()
            }
        }
    }

    init {
        setupMediaListener()
        observeLyricNotification()
        Timber.plant(Timber.DebugTree())

        // listen for user dismissals. Android 13+ requires an explicit export flag
        // when registering receivers that aren't for system broadcasts.
        context.registerReceiver(
            deleteReceiver,
            android.content.IntentFilter(LyricNotificationManager.ACTION_LYRIC_NOTIFICATION_DISMISSED),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Called when the lyric notification is explicitly removed by the user (swipe
     * away or clear all).  Extracted to a method so tests can simulate the
     * behaviour without needing to construct an Intent.
     */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun onNotificationDeletedByUser() {
        Timber.i("Lyric notification deleted by user (test helper)")
        lyricNotificationManager.cancel()
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

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun updateLyricNotification(lyrics: TTMLLyrics?, music: NowPlayingMusic?) {
        if (!AppSettings.isLyricNotificationEnabled(context)) {
            lyricNotificationManager.cancel()
            return
        }

        // if we have no music or no lyrics, just clear
        if (music == null || lyrics == null) {
            lyricNotificationManager.cancel()
            return
        }

        // compute current line first, since we'll need it in both branches
        val time = music.currentPosition
        val currentLine = lyrics.lines.firstOrNull { time in it.startTime..it.endTime }
            ?: lyrics.lines.lastOrNull { it.startTime <= time }

        if (!music.isPlaying) {
            // paused state: send only one update with ongoing=false
            if (!pausedNotificationSent) {
                lyricNotificationManager.showOrUpdate(currentLine, ongoing = false)
                pausedNotificationSent = true
            }
            return
        }

        // playback resumed – clear flag and send ongoing notifications again
        pausedNotificationSent = false
        lyricNotificationManager.showOrUpdate(currentLine, ongoing = true)
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
                val isMusicChanged =
                    oldMusic?.title != music?.title ||
                    oldMusic?.artist != music?.artist
                
                _nowPlayingMusic.value = music
                Timber.d("Now playing: ${music?.title} - ${music?.artist}")
                
                // 如果歌曲确实改变且有有效的歌曲信息，先清除旧歌词并尝试使用缓存
                if (isMusicChanged && music != null) {
                    // 清空上一首歌曲的歌词，以便 UI 可以在新的歌词加载期间显示加载动画
                    _lyrics.value = null

                    // 兼容老旧酷狗缓存需要刷新空格的问题
                    val cached = lyricsCacheRepository.findBySong(music.title, music.artist)
                    if (cached != null) {
                        val shouldBypassCache = cached.source.contains("kugou", ignoreCase = true) ||
                            cached.source.contains("酷狗")
                        if (!shouldBypassCache) {
                            val parsed = LyricsRepository.parseTTML(cached.ttmlContent)
                            if (parsed != null) {
                                _lyrics.value = parsed
                                _errorMessage.value = null
                                Timber.i("Loaded lyrics from cache (startup): ${cached.title} - ${cached.artist} (${cached.source})")
                                // 已经拿到缓存，跳过后续搜索
                                return@collect
                            }
                        }
                    }

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
                    // 兼容修复前的酷狗缓存：旧数据可能已经丢失英文词间空格，需强制走一次在线刷新。
                    val shouldBypassCache = cached.source.contains("kugou", ignoreCase = true) ||
                        cached.source.contains("酷狗")
                    if (shouldBypassCache) {
                        Timber.d("Bypassing stale Kugou cache to refresh whitespace-fixed lyrics")
                    } else {
                    val parsed = LyricsRepository.parseTTML(cached.ttmlContent)
                    if (parsed != null) {
                        _lyrics.value = parsed
                        _errorMessage.value = null
                        Timber.d("Loaded lyrics from cache: ${cached.title} - ${cached.artist} (${cached.source})")
                        return@launch
                    }
                    }
                }
                
                // 使用智能多源搜索，向仓库提供当前播放源名称以便打分
                val sourceName = getAppNameFromPackage(context, music.packageName)
                val result = lyricsRepository.fetchLyricsAuto(
                    title = music.title,
                    artist = music.artist,
                    currentSourceName = sourceName
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
                    Timber.f("Failed to fetch lyrics: ${result.errorMessage}")
                }
            } catch (e: Exception) {
                _errorMessage.value = "错误: ${e.message}"
                Timber.f(e, "Error fetching lyrics")
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
                    Timber.i("Successfully converted LRC to TTML")
                } else {
                    _errorMessage.value = "LRC 转换失败"
                }
            } catch (e: Exception) {
                _errorMessage.value = "转换错误: ${e.message}"
                Timber.f(e, "Error converting LRC to TTML")
            }
        }
    }

    /**
     * 应用自选歌词输入，支持 TTML / LRC / 纯文本
     */
    fun applyCustomLyricsInput(content: String, title: String, artist: String, source: String = "manual") {
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
                        source = source,
                        ttmlContent = TTMLConverter.toTTMLString(parsed)
                    )
                } else {
                    _errorMessage.value = "无法识别歌词格式"
                }
            } catch (e: Exception) {
                _errorMessage.value = "应用歌词失败: ${e.message}"
                Timber.f(e, "Error applying custom lyrics input")
            }
        }
    }
    
    /**
     * 导出歌词为 TTML 文件
     */
    fun exportLyricsAsTTML(): String? {
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
        Timber.d("MainViewModel.seekTo(position=$position)")
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
        // unregister broadcast listener added earlier
        context.unregisterReceiver(deleteReceiver)
        lyricNotificationManager.cancel()
        mediaInfoService.stopListening()
        httpClient.close()
    }
}
