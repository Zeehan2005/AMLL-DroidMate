package com.amll.droidmate.domain.model

import kotlinx.serialization.Serializable

/**
 * 当前播放的音乐信息
 */
@Serializable
data class NowPlayingMusic(
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Long = 0L,
    val currentPosition: Long = 0L,
    val isPlaying: Boolean = false,
    val packageName: String? = null,
    val albumArtUri: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 单个词的信息
 */
@Serializable
data class LyricWord(
    val word: String,
    val startTime: Long,    // 毫秒
    val endTime: Long       // 毫秒
)

/**
 * 歌词行信息
 */
@Serializable
data class LyricLine(
    val startTime: Long,      // 毫秒
    val endTime: Long,        // 毫秒
    val text: String,
    val translation: String? = null,  // 翻译
    val transliteration: String? = null,  // 音译
    val words: List<LyricWord> = emptyList(),  // 逐词信息
    val isBG: Boolean = false,  // 是否为背景音声
    val isDuet: Boolean = false,  // 是否为合唱
    val agent: String? = null  // 原始agent信息（用于TTML导出）
)

/**
 * TTML 歌词结构
 */
@Serializable
data class TTMLLyrics(
    val metadata: TTMLMetadata,
    val lines: List<LyricLine>
)

/**
 * TTML 元数据
 */
@Serializable
data class TTMLMetadata(
    val title: String,
    val artist: String,
    val album: String? = null,
    val language: String = "ja",
    val duration: Long = 0L,
    val source: String = "DroidMate"
)

/**
 * 歌词搜索结果
 */
@Serializable
data class LyricsSearchResult(
    val provider: String,  // 来源: "qq", "netease", "amll", etc.
    val songId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val confidence: Float = 0f,  // 匹配度 0-1
    val matchType: String = "NONE" // 匹配分档: PERFECT/VERY_HIGH/HIGH...
)

/**
 * 歌词获取结果
 */
@Serializable
data class LyricsResult(
    val isSuccess: Boolean,
    val lyrics: TTMLLyrics? = null,
    val errorMessage: String? = null,
    val source: String? = null
)

/**
 * 本地缓存歌词条目
 */
@Serializable
data class CachedLyricEntry(
    val id: String,
    val title: String,
    val artist: String,
    val source: String,
    val ttmlContent: String,
    val updatedAt: Long
)
