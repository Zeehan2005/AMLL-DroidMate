package com.amll.droidmate.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amll.droidmate.data.converter.TTMLConverter
import com.amll.droidmate.data.network.HttpClientFactory
import com.amll.droidmate.data.repository.LyricsRepository
import com.amll.droidmate.domain.model.LyricsSearchResult
import com.amll.droidmate.domain.model.TTMLLyrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

data class CustomLyricsCandidate(
    val provider: String,
    val songId: String,
    val title: String,
    val artist: String,
    val confidence: Float,
    val matchType: String,
    val displayName: String
)

class CustomLyricsViewModel(application: Application) : AndroidViewModel(application) {

    // HTTP Client（使用统一配置，缓存存储在 cache 目录）
    private val httpClient = HttpClientFactory.create(application.applicationContext)

    private val lyricsRepository = LyricsRepository(httpClient)

    private val _candidates = MutableStateFlow<List<CustomLyricsCandidate>>(emptyList())
    val candidates: StateFlow<List<CustomLyricsCandidate>> = _candidates

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _isApplying = MutableStateFlow(false)
    val isApplying: StateFlow<Boolean> = _isApplying

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _appliedLyricsText = MutableStateFlow<String?>(null)
    val appliedLyricsText: StateFlow<String?> = _appliedLyricsText

    private val _appliedLyrics = MutableStateFlow<TTMLLyrics?>(null)
    val appliedLyrics: StateFlow<TTMLLyrics?> = _appliedLyrics


    fun searchCandidates(title: String, artist: String) {
        if (title.isBlank() && artist.isBlank()) return

        viewModelScope.launch {
            _isSearching.value = true
            _errorMessage.value = null
            try {
                val results = lyricsRepository.searchLyrics(title, artist)
                val candidates = buildCandidatesAsync(results)
                _candidates.value = candidates
            } catch (e: Exception) {
                Timber.e(e, "Failed to search candidates")
                _errorMessage.value = "搜索候选歌词失败: ${e.message}"
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun applyCandidate(candidate: CustomLyricsCandidate) {
        viewModelScope.launch {
            _isApplying.value = true
            _errorMessage.value = null
            try {
                // 传递候选歌词的 title 和 artist 以确保正确的元数据
                val result = lyricsRepository.getLyrics(
                    candidate.provider,
                    candidate.songId,
                    candidate.title,
                    candidate.artist
                )
                if (result.isSuccess && result.lyrics != null) {
                    // 直接返回 TTMLLyrics 对象，由 MainViewModel 直接应用，避免不必要的字符串往返
                    _appliedLyrics.value = result.lyrics
                } else {
                    _errorMessage.value = result.errorMessage ?: "应用候选歌词失败"
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to apply candidate")
                _errorMessage.value = "应用候选歌词失败: ${e.message}"
            } finally {
                _isApplying.value = false
            }
        }
    }

    fun applyManualInput(input: String, title: String, artist: String) {
        viewModelScope.launch {
            _isApplying.value = true
            _errorMessage.value = null
            try {
                val parsed = TTMLConverter.fromLyrics(
                    content = input,
                    title = if (title.isBlank()) "自选歌词" else title,
                    artist = if (artist.isBlank()) "Unknown" else artist
                )
                if (parsed != null) {
                    _appliedLyricsText.value = TTMLConverter.toTTMLString(parsed)
                } else {
                    _errorMessage.value = "无法识别歌词格式"
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse manual lyrics")
                _errorMessage.value = "解析歌词失败: ${e.message}"
            } finally {
                _isApplying.value = false
            }
        }
    }

    fun consumeAppliedLyricsText() {
        _appliedLyricsText.value = null
    }

    private suspend fun buildCandidatesAsync(results: List<LyricsSearchResult>): List<CustomLyricsCandidate> {
        val candidates = mutableListOf<CustomLyricsCandidate>()
        for (result in results) {
            // 对于网易云结果，先检查 AMLL TTML DB 是否真的有歌词
            if (result.provider == "netease") {
                val amllResult = lyricsRepository.getLyrics("amll", result.songId, result.title, result.artist)
                if (amllResult.isSuccess && amllResult.lyrics != null) {
                    // AMLL TTML DB 有歌词，添加到候选列表
                    candidates.add(
                        CustomLyricsCandidate(
                            provider = "amll",
                            songId = result.songId,
                            title = result.title,
                            artist = result.artist,
                            confidence = result.confidence,
                            matchType = result.matchType,
                            displayName = "AMLL TTML DB (置信度最高时推荐)"
                        )
                    )
                }
            }

            // 添加原始来源
            candidates.add(
                CustomLyricsCandidate(
                    provider = result.provider,
                    songId = result.songId,
                    title = result.title,
                    artist = result.artist,
                    confidence = result.confidence,
                    matchType = result.matchType,
                    displayName = providerDisplayName(result.provider)
                )
            )
        }
        return candidates
    }

    private fun providerDisplayName(provider: String): String {
        return when (provider.lowercase()) {
            "netease", "ncm" -> "网易云"
            "qq", "qqmusic" -> "QQ音乐"
            "kugou" -> "酷狗"
            "amll" -> "AMLL TTML DB"
            else -> provider.uppercase()
        }
    }

    override fun onCleared() {
        super.onCleared()
        httpClient.close()
    }
}
