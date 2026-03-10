package com.amll.droidmate.ui.viewmodel




import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amll.droidmate.data.converter.TTMLConverter
import com.amll.droidmate.data.repository.LyricsRepository
import com.amll.droidmate.di.ServiceLocator
import com.amll.droidmate.domain.model.LyricsSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // 当前歌曲唯一标识（title + artist）
    private var currentSongKey: String? = null

    private val providerPriority = mapOf(
        "cache" to -1,   // 本地缓存最高优先级
        "amll" to 0,
        "kugou" to 1,
        "netease" to 2,
        "ncm" to 2,
        "qq" to 3,
        "qqmusic" to 3
    )

    // HTTP client & repositories from ServiceLocator (can be overridden in tests)
    private val httpClient = ServiceLocator.provideHttpClient(application.applicationContext)
    private val lyricsRepository = ServiceLocator.provideLyricsRepository(application.applicationContext)
    private val lyricsCacheRepository = ServiceLocator.provideLyricsCacheRepository(application.applicationContext)

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

    // tag the origin of the applied lyrics so the caller can distinguish manual vs candidate
    private val _appliedLyricsSource = MutableStateFlow<String?>(null)
    val appliedLyricsSource: StateFlow<String?> = _appliedLyricsSource

    fun searchCandidates(title: String, artist: String) {
        if (title.isBlank() && artist.isBlank()) return

        // 更新当前歌曲唯一标识
        val songKey = "$title-$artist"
        currentSongKey = songKey

        viewModelScope.launch {
            _isSearching.value = true
            _errorMessage.value = null
            _candidates.value = emptyList()
            val mutex = Mutex()
            try {
                // 优先插入缓存歌词，但仍然继续搜索远端候选
                val cached = lyricsCacheRepository.findBySong(title, artist)
                if (cached != null) {
                    val candidate = CustomLyricsCandidate(
                        provider = "cache",
                        songId = cached.id,
                        title = cached.title,
                        artist = cached.artist,
                        confidence = 1.0f,
                        matchType = "", // 不需要显示
                        displayName = "本地缓存"
                    )
                    appendCandidate(candidate, mutex)
                }

                // 无本地缓存，继续增量搜索远端候选
                lyricsRepository.searchLyricsIncremental(title, artist) { result ->
                    // 只在歌曲未切换时追加候选
                    if (currentSongKey == songKey) {
                        appendCandidate(
                            candidate = result.toCandidate(),
                            mutex = mutex
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to search candidates")
                _errorMessage.value = "搜索候选歌词失败: ${e.message}"
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun applyCandidate(candidate: CustomLyricsCandidate) {
        // 更新当前歌曲唯一标识
        val songKey = "${candidate.title}-${candidate.artist}"
        currentSongKey = songKey

        viewModelScope.launch {
            _isApplying.value = true
            _errorMessage.value = null
            try {
                if (candidate.provider == "cache") {
                    // 直接读取缓存内容
                    val cached = lyricsCacheRepository.findBySong(candidate.title, candidate.artist)
                    if (cached != null && cached.ttmlContent.isNotBlank()) {
                        // 只在歌曲未切换时应用歌词
                        if (currentSongKey == songKey) {
                            _appliedLyricsSource.value = cached.source
                            _appliedLyricsText.value = cached.ttmlContent
                        }
                    } else {
                        _errorMessage.value = "缓存歌词不存在或内容为空"
                    }
                } else {
                    // 传递候选歌词的 title 和 artist 以确保正确的元数据
                    val result = lyricsRepository.getLyrics(
                        candidate.provider,
                        candidate.songId,
                        candidate.title,
                        candidate.artist
                    )
                    if (result.isSuccess && result.lyrics != null) {
                        // 转换为TTML格式以保留words数组(逐词同步数据)
                        // 只在歌曲未切换时应用歌词
                        if (currentSongKey == songKey) {
                            // format the source string with provider-specific rules
                            _appliedLyricsSource.value = autoSourceForCandidate(
                                provider = candidate.provider,
                                title = candidate.title,
                                artist = candidate.artist,
                                id = candidate.songId
                            )
                            _appliedLyricsText.value = TTMLConverter.toTTMLString(result.lyrics)
                        }
                    } else {
                        _errorMessage.value = result.errorMessage ?: "应用候选歌词失败"
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to apply candidate")
                _errorMessage.value = "应用候选歌词失败: ${e.message}"
            } finally {
                _isApplying.value = false
            }
        }
    }

    companion object {
        /**
         * Given raw lyrics input return an appropriate "source" label that will
         * later be stored in the view model / message intent.  Files are tagged
         * with their detected format extension (ttml/lrc/etc); free‑form plain
         * text is still labelled as "manual" for backwards compatibility.
         */
        fun sourceFromInput(input: String): String {
            val format = com.amll.droidmate.data.parser.LyricsFormat.detect(input)
            return if (format == com.amll.droidmate.data.parser.LyricsFormat.PLAIN_TEXT) {
                "manual"
            } else {
                format.extension
            }
        }

        /**
         * Constructs the source string for lyrics obtained from an external
         * provider (candidate or cache) – always marks it as auto‑recognized.
         */
        fun autoSourceForCandidate(
            provider: String,
            title: String,
            artist: String,
            id: String?
        ): String {
            // every provider uses the same template: 服务商：歌曲名 - 歌手名(id)
            val providerName = providerDisplayName(provider)
            return "$providerName：$title - $artist(${id ?: ""})"
        }

        /**
         * Human-friendly name for a lyrics provider.
         *
         * If an ID is supplied (e.g. AMLL songId) it will be appended in parentheses
         * for providers where that makes sense.
         */
        private fun providerDisplayName(provider: String): String {
            // The UI list only shows a friendly name; IDs should not be
            // appended directly after the provider name.  Previously we added
            // the AMLL songId here which caused the title to read
            // "AMLL TTML DB (12345)".  That was confusing and the ID is still
            // surfaced elsewhere if needed so drop it from the display name.
            val base = when (provider.lowercase()) {
                "netease", "ncm" -> "网易云音乐"
                "qq", "qqmusic" -> "QQ音乐"
                "kugou" -> "酷狗音乐"
                "amll" -> "AMLL TTML DB"
                else -> provider.uppercase()
            }
            return base
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
                    _appliedLyricsSource.value = sourceFromInput(input)
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
        _appliedLyricsSource.value = null
    }

    private suspend fun appendCandidate(candidate: CustomLyricsCandidate, mutex: Mutex) {
        mutex.withLock {
            val key = "${candidate.provider.lowercase()}:${candidate.songId}"
            val exists = _candidates.value.any { "${it.provider.lowercase()}:${it.songId}" == key }
            if (exists) return

            _candidates.value = (_candidates.value + candidate)
                .sortedWith(
                    compareBy<CustomLyricsCandidate> {
                        providerPriority[it.provider.lowercase()] ?: Int.MAX_VALUE
                    }.thenByDescending { it.confidence }
                )
        }
    }

    private fun LyricsSearchResult.toCandidate(): CustomLyricsCandidate {
        // matchType may contain verbose labels such as PERFECT/VERY_HIGH
        // those are not useful in the UI, so clear them.
        return CustomLyricsCandidate(
            provider = provider,
            songId = songId,
            title = title,
            artist = artist,
            confidence = confidence,
            matchType = "",
            displayName = Companion.providerDisplayName(provider)
        )
    }


    override fun onCleared() {
        super.onCleared()
        httpClient.close()
    }
}
