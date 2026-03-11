package com.amll.droidmate.data.repository

import com.amll.droidmate.data.parser.mergeLyricLines
import com.amll.droidmate.data.parser.LyricsFormat
import com.amll.droidmate.data.parser.TTMLParser
import com.amll.droidmate.data.network.NeteaseEapiCrypto
import com.amll.droidmate.data.network.QqMusicQrcCrypto
import com.amll.droidmate.data.network.KugouDecrypter
import com.amll.droidmate.domain.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.UnknownHostException
import timber.log.Timber

/**
 * 歌词仓库 - 从多个来源获取和管理歌词
 * 基于 Unilyric 的多源搜索逻辑实现
 */
open class LyricsRepository(
    private val httpClient: HttpClient,
    private val cacheRepo: LyricsCacheRepository? = null
) {

    // exposed for testing
    internal enum class MatchType(val score: Int) {
        NONE(-1),
        VERY_LOW(10),
        LOW(30),
        MEDIUM(70),
        PRETTY_HIGH(90),
        HIGH(95),
        VERY_HIGH(99),
        PERFECT(100)
    }

    internal enum class NameMatchType(val score: Int) {
        NO_MATCH(0), LOW(2), MEDIUM(4), HIGH(5), VERY_HIGH(6), PERFECT(7)
    }

    internal enum class ArtistMatchType(val score: Int) {
        NO_MATCH(0), LOW(2), MEDIUM(4), HIGH(5), VERY_HIGH(6), PERFECT(7)
    }

    @Volatile
    private var lastAmlLError: String? = null

    /**
     * 从 QQ 音乐搜索歌词
     */
    /**
     * 从 QQ 音乐搜索歌词，返回最多三个最佳候选。
     */
    suspend fun searchQQMusic(title: String, artist: String): List<LyricsSearchResult> {
        return try {
            val keyword = "$title $artist".trim()

            // 对齐 Unilyric-lite: req_1 + POST JSON 到 musicu.fcg
            val requestBody = buildJsonObject {
                putJsonObject("req_1") {
                    put("method", "DoSearchForQQMusicDesktop")
                    put("module", "music.search.SearchCgiService")
                    putJsonObject("param") {
                        put("num_per_page", 20)
                        put("page_num", 1)
                        put("query", keyword)
                        put("search_type", 0)
                    }
                }
            }

            val searchResponse = httpClient.post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }

            if (!searchResponse.status.isSuccess()) {
                Timber.e("QQ Music search failed: ${searchResponse.status}")
                return emptyList()
            }

            val json = Json { ignoreUnknownKeys = true }
            val responseBody = searchResponse.body<String>()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject

            // 解析搜索结果
            val songList = responseJson["req_1"]
                ?.jsonObject?.get("data")
                ?.jsonObject?.get("body")
                ?.jsonObject?.get("song")
                ?.jsonObject?.get("list")
                ?.jsonArray

            if (songList.isNullOrEmpty()) {
                Timber.e("No QQ Music results found for: $keyword")
                return emptyList()
            }

            // 收集所有候选并评估匹配度
            val candidates = mutableListOf<Pair<LyricsSearchResult, Int>>()
            for (songElement in songList) {
                val song = songElement.jsonObject
                val songMid = song["mid"]?.jsonPrimitive?.content ?: continue
                val songIdNum = song["id"]?.jsonPrimitive?.longOrNull
                val songTitle = song["title"]?.jsonPrimitive?.content
                    ?: song["name"]?.jsonPrimitive?.content
                    ?: continue
                val singerName = song["singer"]?.jsonArray
                    ?.joinToString(", ") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }
                    ?.takeIf { it.isNotBlank() } ?: continue

                val matchEval = evaluateMatch(title, artist, songTitle, singerName)
                val matchType = MatchType.valueOf(matchEval.matchType)

                if (matchType.score >= MatchType.VERY_LOW.score) {
                    candidates += LyricsSearchResult(
                        provider = "qq",
                        songId = if (songIdNum != null) "$songMid::$songIdNum" else songMid,
                        title = songTitle,
                        artist = singerName,
                        confidence = matchEval.confidence,
                        matchType = matchEval.matchType
                    ) to matchType.score
                }
            }

            // 返回得分最高的前三个
            candidates.sortedByDescending { it.second }
                .take(3)
                .map { it.first }
        } catch (e: Exception) {
            Timber.e(e, "Error searching QQ Music")
            emptyList()
        }
    }
    
    /**
     * 从 QQ 音乐获取歌词内容
     */
    open suspend fun getQQMusicLyrics(songMid: String, title: String? = null, artist: String? = null): TTMLLyrics? {
        return try {
            val (mid, numericId) = parseQqSongIds(songMid)

            // 对齐 Unilyric-lite: 优先 lyric_download 接口
            if (numericId != null) {
                val fromLyricDownload = getQQMusicLyricsViaLyricDownload(numericId, title, artist)
                if (fromLyricDownload != null) {
                    return fromLyricDownload
                }
            }

            val fallbackMid = mid ?: songMid
            val lyricData = buildJsonObject {
                putJsonObject("comm") {
                    put("ct", 19)
                    put("cv", 1859)
                }
                putJsonObject("req_1") {
                    put("module", "music.musichallSong.PlayLyricInfo")
                    put("method", "GetPlayLyricInfo")
                    putJsonObject("param") {
                        put("songMID", fallbackMid)
                        put("songID", 0)
                    }
                }
            }
            
            val response = httpClient.get("https://u.y.qq.com/cgi-bin/musicu.fcg") {
                parameter("data", lyricData.toString())
                parameter("format", "json")
            }
            
            if (!response.status.isSuccess()) {
                Timber.e("QQ Music lyrics fetch failed: ${response.status}")
                return null
            }
            
            val json = Json { ignoreUnknownKeys = true }
            val responseBody = response.body<String>()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            // 获取标准LRC格式歌词
            val lyricContent = responseJson["req_1"]
                ?.jsonObject?.get("data")
                ?.jsonObject?.get("lyric")
                ?.jsonPrimitive?.contentOrNull
            
            // 获取QRC逐字格式歌词（优先使用）
            val qrcContent = responseJson["req_1"]
                ?.jsonObject?.get("data")
                ?.jsonObject?.get("qrc")
                ?.jsonPrimitive?.contentOrNull

            val transContent = responseJson["req_1"]
                ?.jsonObject?.get("data")
                ?.jsonObject?.get("trans")
                ?.jsonPrimitive?.contentOrNull

            val romaContent = responseJson["req_1"]
                ?.jsonObject?.get("data")
                ?.jsonObject?.get("roma")
                ?.jsonPrimitive?.contentOrNull
            
            if (lyricContent.isNullOrBlank() && qrcContent.isNullOrBlank()) {
                Timber.e("No lyrics content from QQ Music for: $fallbackMid")
                return null
            }
            
            // 优先使用QRC逐字格式，如果不存在则使用LRC格式
            val contentToUse = if (!qrcContent.isNullOrBlank()) {
                Timber.i("Using QRC (word-by-word) format for QQ Music")
                qrcContent
            } else {
                Timber.i("Using LRC (line-by-line) format for QQ Music")
                lyricContent!!
            }
            
            // 检查内容是否有效（防止"0"或其他无效标记）
            if (contentToUse.length < 5 || contentToUse == "0") {
                Timber.e("Content from QQ Music is invalid (likely empty): '$contentToUse'")
                // 检查是否有备选LRC内容
                if (contentToUse != lyricContent && !lyricContent.isNullOrBlank() && lyricContent.length >= 5 && lyricContent != "0") {
                    Timber.i("Fallback to LRC format from QQ Music")
                    // 重新使用LRC
                    val fallbackDecoded = try {
                        String(android.util.Base64.decode(lyricContent, android.util.Base64.DEFAULT))
                    } catch (e: IllegalArgumentException) {
                        lyricContent
                    }
                    if (fallbackDecoded.length > 5 && fallbackDecoded != "0") {
                        val fallbackLyrics = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
                            content = fallbackDecoded,
                            title = title ?: "Unknown",
                            artist = artist ?: "Unknown"
                        )
                        return fallbackLyrics
                    }
                }
                return null
            }
            
            // QQ音乐歌词是base64编码的LRC/QRC格式，使用新的统一解析器
            // 添加错误处理，以防Base64数据无效
            val decodedLyric = try {
                String(android.util.Base64.decode(contentToUse, android.util.Base64.DEFAULT))
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "Failed to decode Base64 content from QQ Music. Content length: ${contentToUse.length}")
                Timber.d("First 200 chars of content: ${contentToUse.take(200)}")
                // 尝试直接使用原始内容（可能不是Base64编码）
                contentToUse
            }
            
            // 调试日志：显示解码后的前500个字符
            Timber.d("Decoded QQ Music lyrics (first 500 chars): ${decodedLyric.take(500)}")
            
            // 使用统一解析器处理多种格式（QRC、LRC等）
            val mainLyrics = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
                content = decodedLyric,
                title = title ?: "Unknown",
                artist = artist ?: "Unknown"
            ) ?: return null

            val translationLines = transContent
                ?.takeIf { it.isNotBlank() && it != "0" && it.length >= 5 }
                ?.let {
                    runCatching {
                        val decoded = try {
                            String(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
                        } catch (e: IllegalArgumentException) {
                            it
                        }
                        com.amll.droidmate.data.parser.LrcParser.parse(decoded)
                    }.getOrNull()
                }

            val romanizationLines = romaContent
                ?.takeIf { it.isNotBlank() && it != "0" && it.length >= 5 }
                ?.let {
                    runCatching {
                        val decoded = try {
                            String(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
                        } catch (e: IllegalArgumentException) {
                            it
                        }
                        com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(decoded)?.lines
                    }.getOrNull()
                }

            val merged = mergeLyricLines(mainLyrics.lines, translationLines, romanizationLines)
            Timber.i("Successfully fetched QQ Music lyrics for: $fallbackMid")
            return mainLyrics.copy(lines = merged)
            
        } catch (e: Exception) {
            Timber.e(e, "Error fetching QQ Music lyrics")
            null
        }
    }

    private fun parseQqSongIds(songId: String): Pair<String?, Long?> {
        val parts = songId.split("::", limit = 2)
        val mid = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val numeric = parts.getOrNull(1)?.toLongOrNull()
        if (numeric != null) return mid to numeric
        return if (songId.all { it.isDigit() }) null to songId.toLongOrNull() else songId to null
    }

    /**
     * Parse a song identifier that may include an AMLL platform prefix.
     * Examples:
     *   "ncm:12345" -> ("ncm","12345")
     *   "qq:ABCDEFGHI" -> ("qq","ABCDEFGHI")
     *   "98765" -> ("ncm","98765")  // default to ncm for backward compatibility
     */
    private fun parseAmlLId(raw: String): Pair<String, String> {
        val parts = raw.split(":", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank()) {
            parts[0].lowercase() to parts[1]
        } else {
            "ncm" to raw
        }
    }

    private suspend fun getQQMusicLyricsViaLyricDownload(
        musicId: Long,
        title: String?,
        artist: String?
    ): TTMLLyrics? {
        return try {
            val response = httpClient.post("https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg") {
                header("Referer", "https://y.qq.com/")
                setBody(FormDataContent(Parameters.build {
                    append("version", "15")
                    append("miniversion", "82")
                    append("lrctype", "4")
                    append("musicid", musicId.toString())
                }))
            }

            if (!response.status.isSuccess()) return null
            val body = response.body<String>().trim().removePrefix("<!--").removeSuffix("-->").trim()

            val mainEncrypted = extractXmlCData(body, "content") ?: return null
            val transEncrypted = extractXmlCData(body, "contentts")
            val romaEncrypted = extractXmlCData(body, "contentroma")

            val mainLyrics = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
                content = decodeQqLyricPayload(mainEncrypted),
                title = title ?: "Unknown",
                artist = artist ?: "Unknown"
            ) ?: return null

            val translationLines = transEncrypted?.let {
                runCatching {
                    val decoded = decodeQqLyricPayload(it)
                    com.amll.droidmate.data.parser.LrcParser.parse(decoded)
                }.getOrNull()
            }

            val romanizationLines = romaEncrypted?.let {
                runCatching {
                    val decoded = decodeQqLyricPayload(it)
                    com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(decoded)?.lines
                }.getOrNull()
            }

            mainLyrics.copy(lines = mergeLyricLines(mainLyrics.lines, translationLines, romanizationLines))
        } catch (e: Exception) {
            Timber.e(e, "QQ lyric_download failed, fallback to PlayLyricInfo")
            null
        }
    }

    private fun extractXmlCData(xml: String, tagName: String): String? {
        val regex = Regex("""<$tagName><!\\[CDATA\\[(.*?)]]></$tagName>""", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun decodeQqLyricPayload(payload: String): String {
        val raw = payload.trim()
        return if (QqMusicQrcCrypto.looksLikeHex(raw)) {
            QqMusicQrcCrypto.decryptQrcHex(raw)
        } else {
            String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT))
        }
    }

    /**
     * 从网易云音乐搜索歌词
     */
    suspend fun searchNetease(title: String, artist: String): List<LyricsSearchResult> {
        return try {
            val keyword = "$title $artist".trim()
            Timber.i("Netease search starting for keyword: $keyword")

            val payload = buildJsonObject {
                put("s", keyword)
                put("type", "1")
                put("limit", 30)
                put("offset", 0)
                put("total", true)
            }

            val encryptedParams = NeteaseEapiCrypto.prepareEapiParams(
                "/api/cloudsearch/pc",
                payload.toString()
            )

            val response = httpClient.post("https://interface.music.163.com/eapi/cloudsearch/pc") {
                setBody(FormDataContent(Parameters.build {
                    append("params", encryptedParams)
                }))
            }

            if (!response.status.isSuccess()) {
                Timber.e("Netease search failed: ${response.status}")
                return emptyList()
            }

            val responseJson = Json.parseToJsonElement(response.body<String>()).jsonObject
            val code = responseJson["code"]?.jsonPrimitive?.intOrNull
            if (code != null && code != 200) return emptyList()

            val songList = responseJson["result"]?.jsonObject?.get("songs")?.jsonArray
            if (songList.isNullOrEmpty()) return emptyList()

            val candidates = mutableListOf<Pair<LyricsSearchResult, Int>>()

            for (songElement in songList) {
                val song = songElement.jsonObject
                val songId = song["id"]?.jsonPrimitive?.long?.toString() ?: continue
                val songTitle = song["name"]?.jsonPrimitive?.content ?: continue
                val singerName = (song["ar"]?.jsonArray ?: song["artists"]?.jsonArray)
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content?.trim()?.takeIf { n -> n.isNotEmpty() } }
                    ?.joinToString(", ")
                    ?.takeIf { it.isNotBlank() }
                    ?: continue
                val albumName = song["al"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    ?: song["album"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                val durationMs = song["dt"]?.jsonPrimitive?.longOrNull
                    ?: song["duration"]?.jsonPrimitive?.longOrNull

                val matchEval = evaluateMatch(
                    searchTitle = title,
                    searchArtist = artist,
                    resultTitle = songTitle,
                    resultArtist = singerName,
                    resultAlbum = albumName,
                    resultDurationMs = durationMs
                )
                val matchType = MatchType.valueOf(matchEval.matchType)

                if (matchType.score >= MatchType.VERY_LOW.score) {
                    candidates += LyricsSearchResult(
                        provider = "netease",
                        songId = songId,
                        title = songTitle,
                        artist = singerName,
                        confidence = matchEval.confidence,
                        matchType = matchEval.matchType
                    ) to matchType.score
                }
            }

            candidates.sortedByDescending { it.second }
                .take(3)
                .map { it.first }
        } catch (e: Exception) {
            Timber.e(e, "Error searching Netease")
            emptyList()
        }
    }

    /**
     * AMLL 轻量搜索：尝试通过已知音乐服务的 ID 查找数据库，以避免本地索引匹配造成卡顿。
     *
     * 当仅给定网易云候选时会默认使用 ncm 平台（这是最常见的情况），
     * 但是后台也会在获得 QQ 结果后额外向 `qq` 平台发起探测；
     * 将来若需要可扩展至 spotify、am 等其他平台。
     */
    suspend fun searchAMLL(title: String, artist: String): LyricsSearchResult? {
        return try {
            val neteaseCandidate = searchNetease(title, artist).firstOrNull() ?: return null
            // prefix with platform so that callers later know which path to hit
            val amllId = "ncm:${neteaseCandidate.songId}"
            val amllLyrics = getAMLL_TTMLLyrics(
                amllId,
                title = neteaseCandidate.title,
                artist = neteaseCandidate.artist
            ) ?: return null
            neteaseCandidate.copy(
                provider = "amll",
                songId = amllId,
                title = amllLyrics.metadata.title.takeIf { it.isNotBlank() } ?: neteaseCandidate.title,
                artist = amllLyrics.metadata.artist.takeIf { it.isNotBlank() } ?: neteaseCandidate.artist,
                album = amllLyrics.metadata.album ?: neteaseCandidate.album
            )
        } catch (e: Exception) {
            Timber.e(e, "Error searching AMLL")
            null
        }
    }
    
    /**
     * 从网易云音乐获取歌词内容
     */
    open suspend fun getNeteaseLyrics(songId: String, title: String? = null, artist: String? = null): TTMLLyrics? {
        return try {
            val payload = buildJsonObject {
                put("id", songId)
                put("cp", "false")
                put("lv", "0")
                put("kv", "0")
                put("tv", "0")
                put("rv", "0")
                put("yv", "0")
                put("ytv", "0")
                put("yrv", "0")
                put("csrf_token", "")
            }

            val encryptedParams = NeteaseEapiCrypto.prepareEapiParams(
                "/api/song/lyric/v1",
                payload.toString()
            )

            val response = httpClient.post("https://interface3.music.163.com/eapi/song/lyric/v1") {
                setBody(FormDataContent(Parameters.build {
                    append("params", encryptedParams)
                }))
            }
            
            if (!response.status.isSuccess()) {
                Timber.e("Netease lyrics fetch failed: ${response.status}")
                return null
            }
            
            val json = Json { ignoreUnknownKeys = true }
            val responseBody = response.body<String>()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            val code = responseJson["code"]?.jsonPrimitive?.intOrNull
            if (code != null && code != 200) {
                Timber.e("Netease lyrics API returned code=$code")
                return null
            }
            
            // 原词 (LRC)
            val lyricContent = responseJson["lrc"]
                ?.jsonObject?.get("lyric")
                ?.jsonPrimitive?.contentOrNull

            // 逐词 (YRC)
            val yrcContent = responseJson["yrc"]
                ?.jsonObject?.get("lyric")
                ?.jsonPrimitive?.contentOrNull

            // 翻译/音译
            val translationContent = responseJson["tlyric"]
                ?.jsonObject?.get("lyric")
                ?.jsonPrimitive?.contentOrNull
            val transliterationContent = responseJson["romalrc"]
                ?.jsonObject?.get("lyric")
                ?.jsonPrimitive?.contentOrNull

            // 调试：记录各字段状态
            Timber.d("Netease response fields - yrc: ${if (yrcContent.isNullOrBlank()) "EMPTY" else "HAS_DATA(${yrcContent.length})"}, lrc: ${if (lyricContent.isNullOrBlank()) "EMPTY" else "HAS_DATA(${lyricContent.length})"}")
            if (!lyricContent.isNullOrBlank()) {
                val preview = lyricContent.take(500)
                Timber.d("LRC field content preview (500 chars): $preview")
                val detectedFormat = LyricsFormat.detect(lyricContent)
                Timber.d("LRC field detected format: $detectedFormat")
                // 查找第一个歌词行（非元数据行）
                val firstLyricLine = lyricContent.lines().find { 
                    it.trim().startsWith("[") && !it.trim().startsWith("{")
                }
                if (firstLyricLine != null) {
                    Timber.d("First lyric line found: ${firstLyricLine.take(80)}")
                } else {
                    Timber.w("No lyric lines found in content (only metadata?)")
                }
            }
            if (!yrcContent.isNullOrBlank()) {
                val preview = yrcContent.take(200)
                Timber.d("YRC field content preview: $preview")
            }

            if (lyricContent.isNullOrBlank() && yrcContent.isNullOrBlank()) {
                Timber.e("No lyrics content from Netease for: $songId")
                return null
            }

            // 优先使用 YRC（逐字格式），然后是 LRC
            val mainContent = if (!yrcContent.isNullOrBlank()) {
                Timber.i("Using YRC (word-by-word) format for Netease")
                Timber.d("YRC content (first 500 chars): ${yrcContent.take(500)}")
                yrcContent
            } else {
                Timber.i("Using LRC (line-by-line) format for Netease")
                lyricContent.orEmpty()
            }
            
            // 使用统一解析器处理 YRC、LRC 等多种格式
            val mainLyrics = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
                content = mainContent,
                title = title ?: "Unknown",
                artist = artist ?: "Unknown"
            ) ?: return null

            val mergedLines = mergeNeteaseAuxiliaryLines(
                mainLines = mainLyrics.lines,
                translationContent = translationContent,
                transliterationContent = transliterationContent
            )
            val result = mainLyrics.copy(lines = mergedLines)
            
            Timber.i("Successfully fetched Netease lyrics for: $songId")
            return result
            
        } catch (e: Exception) {
            Timber.e(e, "Error fetching Netease lyrics")
            null
        }
    }
    
    /**
     * 从酷狗音乐搜索歌词
     */
    // cache for lazily mapping song hashes -> proposal id (first lookup may issue network request)
    private val kugouHashToProposalCache = mutableMapOf<String, String?>()

    /**
     * Helper used by both the public search and internal flows. Given a
     * kugou song hash (the value returned by the v3/search/song API), this
     * will invoke the lyrics search endpoint once and return the "id"
     * field of the first candidate.  The ID is what the official lyrics API
     * refers to as the proposal/lyric id; we store it as the songId in
     * LyricsSearchResult instead of the hash.
     *
     * The implementation is `open` so tests can override and avoid network
     * calls.
     */
    protected open suspend fun fetchKugouProposalId(hash: String): String? {
        // quick cache hit first
        kugouHashToProposalCache[hash]?.let { return it }

        try {
            val json = Json { ignoreUnknownKeys = true }
            val resp = httpClient.get("https://lyrics.kugou.com/search") {
                parameter("ver", "1")
                parameter("man", "yes")
                parameter("client", "pc")
                parameter("keyword", "")
                parameter("hash", hash)
            }
            if (!resp.status.isSuccess()) return null
            val body = resp.body<String>()
            val searchJson = json.parseToJsonElement(body).jsonObject
            val candidates = searchJson["candidates"]?.jsonArray
            val id = candidates?.getOrNull(0)?.jsonObject?.get("id")?.jsonPrimitive?.content
            kugouHashToProposalCache[hash] = id
            return id
        } catch (e: Exception) {
            Timber.e(e, "Error fetching proposal id for kugou hash $hash")
            kugouHashToProposalCache[hash] = null
            return null
        }
    }

    suspend fun searchKugou(title: String, artist: String): List<LyricsSearchResult> {
        return try {
            val keyword = "$title $artist".trim()
            Timber.i("Kugou search starting for keyword: $keyword")

            // Step 1: 从 Kugou 搜歌曲获得哈希值（对齐 Unilyric）
            val searchResponse = httpClient.get("http://mobilecdn.kugou.com/api/v3/search/song") {
                parameter("keyword", keyword)
                parameter("page", "1")
                parameter("pagesize", "20")
                parameter("showtype", "1")
            }

            if (!searchResponse.status.isSuccess()) {
                Timber.e("Kugou song search failed: ${searchResponse.status}")
                return emptyList()
            }

            val json = Json { ignoreUnknownKeys = true }
            val searchBody = searchResponse.body<String>()
            Timber.d("Kugou search API raw response (first 2000 chars): ${searchBody.take(2000)}")

            val searchJson = json.parseToJsonElement(searchBody).jsonObject
            val dataElement = searchJson["data"]?.jsonObject?.get("info")?.jsonArray

            Timber.d("Kugou parsed data element: ${dataElement?.size} items")

            if (dataElement.isNullOrEmpty()) {
                Timber.e("No Kugou song results found for: $keyword")
                return emptyList()
            }

            val candidates = mutableListOf<Pair<LyricsSearchResult, Int>>()

            Timber.d("Kugou found ${dataElement.size} song candidates, evaluating match...")

            for ((index, songElement) in dataElement.withIndex()) {
                val songObj = songElement.jsonObject
                val songHash = songObj["hash"]?.jsonPrimitive?.content
                val songTitle = songObj["songname"]?.jsonPrimitive?.content
                val singerName = songObj["singername"]?.jsonPrimitive?.content
                val albumName = songObj["album_name"]?.jsonPrimitive?.content
                val durationSec = songObj["duration"]?.jsonPrimitive?.longOrNull


                // 检查必要字段
                if (songHash == null || songTitle == null || singerName == null || durationSec == null) {
                    Timber.d("Candidate #$index: Missing required fields (hash=$songHash, title=$songTitle, artist=$singerName, dur=$durationSec)")
                    continue
                }

                val durationMs = durationSec * 1000

                Timber.d("Candidate #$index: $songTitle - $singerName (hash=$songHash, duration=${durationMs}ms)")

                val matchEval = evaluateMatch(
                    searchTitle = title,
                    searchArtist = artist,
                    resultTitle = songTitle,
                    resultArtist = singerName,
                    resultAlbum = albumName,
                    resultDurationMs = durationMs
                )
                val matchType = MatchType.valueOf(matchEval.matchType)

                Timber.d("  -> Evaluation: confidence=${matchEval.confidence}, match=${matchEval.matchType}(score=${matchType.score})")

                if (matchType.score >= MatchType.VERY_LOW.score) {
                    // always store hash internally; for display we append proposal if available
                    val proposal = fetchKugouProposalId(songHash)
                    // format: "<hash>" or "<hash>::<proposal>" (numeric id)
                    val idToStore = if (!proposal.isNullOrBlank()) {
                        "$songHash::$proposal"
                    } else songHash
                    if (proposal != null) {
                        Timber.d("Converted kugou hash $songHash -> proposal id $proposal")
                    }
                    candidates += LyricsSearchResult(
                        provider = "kugou",
                        songId = idToStore,
                        title = songTitle,
                        artist = singerName,
                        confidence = matchEval.confidence,
                        matchType = matchEval.matchType
                    ) to matchType.score
                }
            }

            candidates.sortedByDescending { it.second }
                .take(3)
                .map { it.first }
        } catch (e: Exception) {
            Timber.e(e, "Error searching Kugou")
            emptyList()
        }
    }
    
    /**
     * 从酷狗音乐获取歌词内容
     */
    /**
     * `hash` parameter historically was the 32‑char song hash returned by the
     * v3/search/song endpoint.  After searchKugou switched to storing the
     * lyrics API "proposal"/id we may receive either that numeric id or an
     * old-style hash here.  The code below handles both: if the string is
     * all digits we treat it as a proposal and later make sure to pick the
     * matching candidate; otherwise we still query by hash and take the
     * first result as before.
     */
    open suspend fun getKugouLyrics(idOrHash: String, title: String? = null, artist: String? = null): TTMLLyrics? {
        return try {
            // 基于 Unilyric 的实现
            // https://lyrics.kugou.com 是官方歌词 API
            
            // if the caller passed a combined id, split it; the first segment is
            // always the original song hash that the service expects for queries.
            val hash = idOrHash.substringBefore("::")
            val displayPart = idOrHash.substringAfter("::", "")

            val isNumericId = displayPart.all { it.isDigit() }
            // Step 1: 搜索歌词候选（使用 hash）
            val searchResponse = httpClient.get("https://lyrics.kugou.com/search") {
                parameter("ver", "1")
                parameter("man", "yes")
                parameter("client", "pc")
                parameter("keyword", "")
                parameter("hash", hash)
            }
            
            if (!searchResponse.status.isSuccess()) {
                Timber.e("Kugou lyrics search failed: ${searchResponse.status}")
                return null
            }
            
            val json = Json { ignoreUnknownKeys = true }
            val searchBody = searchResponse.body<String>()
            Timber.d("Kugou lyrics search response (first 1000 chars): ${searchBody.take(1000)}")
            
            val searchJson = json.parseToJsonElement(searchBody).jsonObject
            val status = searchJson["status"]?.jsonPrimitive?.intOrNull ?: -1
            val candidates = searchJson["candidates"]?.jsonArray
            
            if (status != 200 || candidates.isNullOrEmpty()) {
                Timber.e("Kugou lyrics search failed with status=$status or no candidates")
                return null
            }
            
            // Step 2: 选出合适的候选。如果调用方提供了数字 ID，尝试匹配；
            // 否则直接取第一个。
            val candidate = if (isNumericId && displayPart.isNotEmpty()) {
                candidates.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == displayPart }
                    ?.jsonObject
                    ?: run {
                        Timber.w("Numeric kugou ID $displayPart not found in search results")
                        return null
                    }
            } else {
                candidates[0].jsonObject
            }
            val lyricId = candidate["id"]?.jsonPrimitive?.content ?: run {
                Timber.w("No lyrics ID in candidate")
                return null
            }
            val accesskey = candidate["accesskey"]?.jsonPrimitive?.content ?: run {
                Timber.w("No accesskey in candidate")
                return null
            }
            
            Timber.d("Kugou lyrics candidate - id: $lyricId, accesskey: $accesskey")
            
            // Step 3: 下载歌词（KRC 格式）
            val downloadResponse = httpClient.get("https://lyrics.kugou.com/download") {
                parameter("ver", "1")
                parameter("client", "pc")
                parameter("id", lyricId)
                parameter("accesskey", accesskey)
                parameter("fmt", "krc")
                parameter("charset", "utf8")
            }
            
            if (!downloadResponse.status.isSuccess()) {
                Timber.w("Kugou lyrics download failed: ${downloadResponse.status}")
                return null
            }
            
            val downloadBody = downloadResponse.body<String>()
            Timber.d("Kugou lyrics download response (first 500 chars): ${downloadBody.take(500)}")
            
            val downloadJson = json.parseToJsonElement(downloadBody).jsonObject
            val encryptedContent = downloadJson["content"]?.jsonPrimitive?.content
            
            if (encryptedContent.isNullOrBlank()) {
                Timber.e("No lyrics content in download response")
                return null
            }
            
            // Step 4: 解密歌词内容（根据 Unilyric: Base64 + XOR + Zlib）
            val decryptedLyrics = KugouDecrypter.decryptKrc(encryptedContent)
            
            if (decryptedLyrics.isNullOrBlank()) {
                Timber.e("Failed to decrypt Kugou lyrics")
                return null
            }
            
            Timber.d("Decrypted Kugou lyrics (first 500 chars): ${decryptedLyrics.take(500)}")
            
            // Step 5: 使用统一解析器处理 KRC/LRC 格式
            val ttml = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
                content = decryptedLyrics,
                title = title ?: "Unknown",
                artist = artist ?: "Unknown"
            )
            
            Timber.i("Successfully fetched and decrypted Kugou lyrics for: $idOrHash")
            return ttml
            
        } catch (e: Exception) {
            Timber.e(e, "Error fetching Kugou lyrics")
            null
        }
    }
    
    /**
     * 规范化名称用于比较（基于 Unilyric 的 normalize_name_for_comparison）
     */
    private fun stripAccents(input: String): String {
        // remove diacritics (e.g. é -> e) to make comparison accent-insensitive
        val normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(normalized, "")
    }

    internal fun normalizeForComparison(name: String): String {
        // apply accent stripping first
        var s = stripAccents(name)
        s = s
            .replace('’', '\'')                           // 特殊单引号 -> 普通单引号
            .replace("，", ",")                          // 全角逗号 -> 半角逗号
            .replace("（", " (")                         // 全角左括号 -> 半角+空格
            .replace("）", ") ")                         // 全角右括号 -> 半角+空格
            .replace("【", " (")                         // 中文方括号 -> 半角括号
            .replace("】", ") ")
            .replace("[", "(")                           // 方括号 -> 圆括号
            .replace("]", ")")
            // remove common punctuation that does not affect semantics
            .replace(Regex("[\"'.,!?؛:・]"), "")
            .replace("acoustic version", "acoustic", ignoreCase = true)  // 语义简化
            .replace(Regex("feat\\.?|ft\\.?|featuring", RegexOption.IGNORE_CASE), "") // strip featured tags
            .replace(Regex("\\b(the|a|an)\\b", RegexOption.IGNORE_CASE), "") // drop leading articles
            // eliminate spaces between Latin and Han to treat "G.E.M. 邓紫棋" same as "G.E.M.邓紫棋"
            .replace(Regex("(?<=\\p{Latin})\\s+(?=\\p{IsHan})"), "")
            .replace(Regex("(?<=\\p{IsHan})\\s+(?=\\p{Latin})"), "")
            .replace(Regex("\\s+"), " ")                 // 多个空格 -> 单个空格
            .trim()
        return s
    }

    internal data class MatchEvaluation(
        val confidence: Float,
        val matchType: String
    )

    internal fun evaluateMatch(
        searchTitle: String,
        searchArtist: String,
        resultTitle: String,
        resultArtist: String,
        searchAlbum: String? = null,
        resultAlbum: String? = null,
        searchDurationMs: Long? = null,
        resultDurationMs: Long? = null
    ): MatchEvaluation {
        val matchType = compareTrack(
            searchTitle = searchTitle,
            searchArtist = searchArtist,
            resultTitle = resultTitle,
            resultArtist = resultArtist,
            searchAlbum = searchAlbum,
            resultAlbum = resultAlbum,
            searchDurationMs = searchDurationMs,
            resultDurationMs = resultDurationMs
        )

        return MatchEvaluation(
            confidence = (matchType.score / 100f).coerceIn(0f, 1f),
            matchType = matchType.name
        )
    }

    /**
     * 规范化标题用于匹配（基于 Unilyric 的 compare_name）
     */
    internal fun normalizeTitle(title: String): String {
        return normalizeForComparison(title.lowercase())
    }

    internal fun normalizeArtistList(artist: String): List<String> {
        return artist.split(Regex("[/、,;]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeForComparison(it.lowercase()) }
    }

    internal fun computeTextSame(text1: String, text2: String): Double {
        val maxLen = maxOf(text1.length, text2.length)
        if (maxLen == 0) return 100.0
        val distance = levenshteinDistance(text1, text2)
        return (1.0 - distance.toDouble() / maxLen.toDouble()) * 100.0
    }

    internal fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            for (j in prev.indices) prev[j] = curr[j]
        }
        return prev[b.length]
    }

    /**
     * 检查 dash-paren 等价性（基于 Unilyric）
     * 例如: "Song - Live" ≈ "Song (Live)"
     */
    internal fun checkDashParenEquivalence(dashText: String, parenText: String): Boolean {
        val hasDash = dashText.contains(" - ") && !dashText.contains('(')
        val hasParen = parenText.contains('(') && !parenText.contains(" - ")

        if (!hasDash || !hasParen) return false
        val parts = dashText.split(" - ", limit = 2)
        if (parts.size != 2) return false
        val converted = "${parts[0].trim()} (${parts[1].trim()})"
        return converted == parenText
    }

    // version-related keywords that often indicate alternate mixes/edits/releases
    private fun extractVersionKeywords(s: String): Set<String> {
        val keywords = listOf(
            // English terms
            "remix", "live", "concert","dj", "edit", "mix", "acoustic",
            "instrumental", "extended", "karaoke", "remastered", "rework",
            "re-edit", "unplugged", "piano", "strings",
            "orchestral", "demo", "remaster", "ringtone",
            // Chinese equivalents / additional keywords
            "混音", "现场", "演唱会", "晚会", "无伴奏", "广播", "卡拉OK", "纯音乐", "加长", "重混",
            "重制", "改编", "重编辑", "不插电", "钢琴", "弦乐", "管弦乐", "演示", "重新制作", "片段",
            "铃声"
        )
        val lower = s.lowercase()

        // start with any keyword that appears in the string
        val matches = keywords.filter { lower.contains(it) }.toMutableSet()

        // additionally detect numeric tempo/multiplier markers like "0.6x", "1.5x" etc.
        // such tokens are commonly used to denote speed changes and should count as version keywords.
        val multiplierRegex = Regex("\\b\\d+(?:\\.\\d+)?x\\b")
        multiplierRegex.findAll(lower).forEach { matches.add(it.value) }

        // extra: capture parenthesized segments that likely indicate a version
        // marker, e.g. "(国语版)" or "(remix version)".  we only consider cases
        // where the content ends with 版 or contains the word "version" to
        // prevent false positives such as "(版面)".
        val parenthesized = Regex("\\(([^)]+)\\)").findAll(lower)
            .map { it.groupValues[1] }
            .toList()
        for (content in parenthesized) {
            val likelyVersion = content.contains("version") || content.endsWith("版")
            if (likelyVersion &&
                !content.contains("album version") && !content.contains("single version") &&
                !content.contains("专辑版本") && !content.contains("单曲版本")) {
                matches.add(content)
            }
        }

        // exclude unhelpful combinations such as "album version" or "single version".
        // these phrases are very generic and should not influence matching, so remove
        // the individual words when they occur together.
        if (lower.contains("album version")) {
            matches.remove("album")
            matches.remove("version")
        }
        if (lower.contains("single version")) {
            matches.remove("single")
            matches.remove("version")
        }

        // if both album and single somehow appeared together with version (e.g.
        // "album version single version"), the above logic will have already
        // stripped all three words. remaining set may be empty.
        return matches
    }

    internal fun compareName(name1: String?, name2: String?): NameMatchType? {
        val raw1 = name1?.trim().orEmpty()
        val raw2 = name2?.trim().orEmpty()
        if (raw1.isEmpty() || raw2.isEmpty()) return null

        val lowered1 = raw1.lowercase()
        val lowered2 = raw2.lowercase()

        if (lowered1 == lowered2) return NameMatchType.PERFECT

        val n1 = normalizeForComparison(lowered1)
        val n2 = normalizeForComparison(lowered2)
        if (n1 == n2) return NameMatchType.PERFECT

        // if the normalized names contain differing version/mix tags,
        // treat as a much weaker match since they are likely separate releases
        val v1 = extractVersionKeywords(n1)
        val v2 = extractVersionKeywords(n2)
        if (v1.isNotEmpty() || v2.isNotEmpty()) {
            if (v1 != v2) {
                Timber.d("      version keyword mismatch: $v1 vs $v2 -> LOW")
                return NameMatchType.LOW
            }
        }

        if (checkDashParenEquivalence(n1, n2) || checkDashParenEquivalence(n2, n1)) {
            return NameMatchType.VERY_HIGH
        }

        val specialSuffixes = listOf("deluxe", "explicit", "special edition", "bonus track", "feat", "with")
        for (suffix in specialSuffixes) {
            val marker = "($suffix"
            val rule1 = n1.contains(marker) && !n2.contains(marker) && n2 == n1.split(marker).first().trim()
            val rule2 = n2.contains(marker) && !n1.contains(marker) && n1 == n2.split(marker).first().trim()
            if (rule1 || rule2) return NameMatchType.VERY_HIGH
        }

        if (n1.contains('(') && n2.contains('(')) {
            val b1 = n1.substringBefore('(').trim()
            val b2 = n2.substringBefore('(').trim()
            if (b1.isNotEmpty() && b1 == b2) return NameMatchType.HIGH
        }

        val oneHasParen = (n1.contains('(') && !n2.contains('(')) || (n2.contains('(') && !n1.contains('('))
        if (oneHasParen) {
            val b1 = n1.substringBefore('(').trim()
            val b2 = n2.substringBefore('(').trim()
            if (b1 == b2) {
                // examine the text inside the parentheses of whichever name contains it
                val parenText = if (n1.contains('(')) {
                    n1.substringAfter('(').substringBefore(')')
                } else {
                    n2.substringAfter('(').substringBefore(')')
                }
                // if the parenthesized text contains any of the version/mix keywords
                // (live, remix, edit etc.) we treat it as a weaker LOW match. otherwise
                // it's likely just a translation or annotation, so consider it perfect.
                val keywordsInParen = extractVersionKeywords(parenText)
                return if (keywordsInParen.isNotEmpty()) {
                    NameMatchType.LOW
                } else {
                    NameMatchType.PERFECT
                }
            }
        }

        if (n1.length == n2.length) {
            val equalCount = n1.zip(n2).count { it.first == it.second }
            val ratio = equalCount.toDouble() / n1.length.toDouble()
            if ((ratio >= 0.8 && n1.length >= 4) || (ratio >= 0.5 && n1.length in 2..3)) {
                return NameMatchType.HIGH
            }
        }

        val sim = computeTextSame(n1, n2)
        val result = when {
            sim > 90.0 -> NameMatchType.VERY_HIGH
            sim > 80.0 -> NameMatchType.HIGH
            sim > 68.0 -> NameMatchType.MEDIUM
            sim > 55.0 -> NameMatchType.LOW
            else -> NameMatchType.NO_MATCH
        }
        Timber.d("        compareName: '$n1'(from '$raw1') vs '$n2'(from '$raw2') -> similarity=$sim, result=${result.name}(${result.score})")
        return result
    }

    /**
     * 艺术家匹配（近似 Unilyric compare_artists）。
     * 当前未做繁简转换，其他规则保持一致。
     */
    internal fun compareArtists(artists1: String?, artists2: String?): ArtistMatchType? {
        val list1 = normalizeArtistList(artists1.orEmpty())
        val list2 = normalizeArtistList(artists2.orEmpty())
        // normalize common conjunctions in artist names
        fun normalizeConjunctions(list: List<String>) = list.map { it.replace(" & ", " and ") }
        val n1 = normalizeConjunctions(list1)
        val n2 = normalizeConjunctions(list2)
        if (list1.isEmpty() || list2.isEmpty()) return null

        val l1Various = list1.any { it.contains("various") || it.contains("群星") }
        val l2Various = list2.any { it.contains("various") || it.contains("群星") }
        if ((l1Various && (l2Various || list2.size > 4)) || (l2Various && list1.size > 4)) {
            return ArtistMatchType.HIGH
        }

        val used = mutableSetOf<Int>()
        var intersection = 0
        for (a in n1) {
            var matchedIdx: Int? = null
            for ((idx, b) in n2.withIndex()) {
                if (used.contains(idx)) continue
                if (b.contains(a) || a.contains(b) || computeTextSame(a, b) > 88.0) {
                    matchedIdx = idx
                    break
                }
            }
            if (matchedIdx != null) {
                intersection += 1
                used.add(matchedIdx)
            }
        }

        val union = n1.size + n2.size - intersection
        if (union == 0) return ArtistMatchType.PERFECT
        val jaccard = intersection.toDouble() / union.toDouble()

        return when {
            jaccard >= 0.99 -> ArtistMatchType.PERFECT
            jaccard >= 0.80 -> ArtistMatchType.VERY_HIGH
            jaccard >= 0.60 -> ArtistMatchType.HIGH
            jaccard >= 0.40 -> ArtistMatchType.MEDIUM
            jaccard >= 0.15 -> ArtistMatchType.LOW
            else -> ArtistMatchType.NO_MATCH
        }
    }

    internal fun compareDuration(duration1: Long?, duration2: Long?): NameMatchType? {
        val d1 = duration1 ?: return null
        val d2 = duration2 ?: return null
        val diff = kotlin.math.abs(d1 - d2).toDouble() / 1000.0

        // 高斯衰减近似 Unilyric 的时长比较
        val sigma = 3.0
        val similarity = kotlin.math.exp(-(diff * diff) / (2.0 * sigma * sigma))
        return when {
            similarity > 0.98 -> NameMatchType.PERFECT
            similarity > 0.90 -> NameMatchType.VERY_HIGH
            similarity > 0.75 -> NameMatchType.HIGH
            similarity > 0.50 -> NameMatchType.MEDIUM
            similarity > 0.30 -> NameMatchType.LOW
            else -> NameMatchType.NO_MATCH
        }
    }

    internal fun compareTrack(
        searchTitle: String,
        searchArtist: String,
        resultTitle: String,
        resultArtist: String,
        searchAlbum: String? = null,
        resultAlbum: String? = null,
        searchDurationMs: Long? = null,
        resultDurationMs: Long? = null
    ): MatchType {
        val titleMatch = compareName(searchTitle, resultTitle)
        val artistMatch = compareArtists(searchArtist, resultArtist)
        val albumMatch = compareName(searchAlbum, resultAlbum)
        val durationMatch = compareDuration(searchDurationMs, resultDurationMs)
        // weights can be tweaked later or exposed via configuration
        val titleWeight = 1.0
        val artistWeight = 1.0
        val albumWeight = 0.5   // slightly higher importance for album
        val durationWeight = 0.8 // use duration but not dominant
        val maxSingle = 7.0

        Timber.d("    compareTrack details:")
        Timber.d("      Title comparison: '$searchTitle' vs '$resultTitle' -> ${titleMatch?.name}(${titleMatch?.score ?: 0})")
        Timber.d("      Artist comparison: '$searchArtist' vs '$resultArtist' -> ${artistMatch?.name}(${artistMatch?.score ?: 0})")
        Timber.d("      Album comparison: '$searchAlbum' vs '$resultAlbum' -> ${albumMatch?.name}(${albumMatch?.score ?: 0})")
        Timber.d("      Duration comparison: ${searchDurationMs}ms vs ${resultDurationMs}ms -> ${durationMatch?.name}(${durationMatch?.score ?: 0})")

        var totalScore = (durationMatch?.score ?: 0).toDouble() * durationWeight
        totalScore += (albumMatch?.score ?: 0).toDouble() * albumWeight
        totalScore += (artistMatch?.score ?: 0).toDouble() * artistWeight
        totalScore += (titleMatch?.score ?: 0).toDouble() * titleWeight

        var possibleScore = maxSingle * (titleWeight + artistWeight)
        if (albumMatch != null) possibleScore += maxSingle * albumWeight
        if (durationMatch != null) possibleScore += maxSingle * durationWeight

        val fullScoreBase = maxSingle * (titleWeight + artistWeight + albumWeight + durationWeight)
        val normalizedScore = if (possibleScore > 0.0 && possibleScore < fullScoreBase) {
            totalScore * (fullScoreBase / possibleScore)
        } else {
            totalScore
        }

        Timber.d("      totalScore (raw): $totalScore, possibleScore: $possibleScore, normalized: $normalizedScore")

        // thresholds adjusted after tuning to reduce false positives
        val thresholds = listOf(
            22.0 to MatchType.PERFECT,
            20.0 to MatchType.VERY_HIGH,
            17.5 to MatchType.HIGH,
            15.0 to MatchType.PRETTY_HIGH,
            11.0 to MatchType.MEDIUM,
            7.0 to MatchType.LOW,
            3.0 to MatchType.VERY_LOW
        )

        for ((threshold, mt) in thresholds) {
            if (normalizedScore > threshold) {
                Timber.d("      -> Result: $normalizedScore > $threshold = ${mt.name}(${mt.score})")
                return mt
            }
        }
        Timber.d("      -> Result: $normalizedScore below all thresholds = NONE(0)")
        return MatchType.NONE
    }
    
    /**
     * 解析LRC格式歌词
     * 支持翻译和音译（可选）
     * 采用与unilyrics一致的转换规则
     * 使用新的 UnifiedLyricsParser
     */
    fun parseLRC(
        lrcContent: String,
        translationLrc: String? = null,
        transliterationLrc: String? = null
    ): TTMLLyrics? {
        return try {
            // 使用新的 UnifiedLyricsParser
            val ttml = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(lrcContent)
                ?: return null
            
            val merged = mergeLyricLines(
                ttml.lines,
                translationLrc?.let { com.amll.droidmate.data.parser.LrcParser.parse(it) },
                transliterationLrc?.let { com.amll.droidmate.data.parser.LrcParser.parse(it) }
            )
            ttml.copy(lines = merged)
        } catch (e: Exception) {
            Timber.e(e, "Error parsing LRC")
            null
        }
    }

    /**
     * 解析 YRC 格式歌词（网易云逐字格式）
     * 支持翻译和音译（可选）
     * 使用新的 UnifiedLyricsParser
     */
    private fun parseYRC(
        yrcContent: String,
        translationLrc: String? = null,
        transliterationLrc: String? = null
    ): TTMLLyrics? {
        return try {
            // 使用新的 UnifiedLyricsParser，它会自动检测YRC格式
            val ttml = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(yrcContent)
                ?: return null
            
            val merged = mergeLyricLines(
                ttml.lines,
                translationLrc?.let { com.amll.droidmate.data.parser.LrcParser.parse(it) },
                transliterationLrc?.let { com.amll.droidmate.data.parser.LrcParser.parse(it) }
            )
            ttml.copy(lines = merged)
        } catch (e: Exception) {
            Timber.e(e, "Error parsing YRC")
            null
        }
    }

    private fun parseTimedLRCMap(lrcContent: String?): Map<Long, String> {
        if (lrcContent.isNullOrBlank()) return emptyMap()

        val result = mutableMapOf<Long, String>()
        val timeTagRegex = Regex("""\[(\d+):(\d+(?:\.\d+)?)\]""")

        for (rawLine in lrcContent.lines()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            if (line.startsWith("[ti:") || line.startsWith("[ar:") || line.startsWith("[al:")) continue

            val tags = timeTagRegex.findAll(line).toList()
            if (tags.isEmpty()) continue

            val text = line.substring(tags.last().range.last + 1).trim()
            if (text.isBlank()) continue

            for (tag in tags) {
                val minutes = tag.groupValues[1].toLongOrNull() ?: continue
                val seconds = tag.groupValues[2].toDoubleOrNull() ?: continue
                val startTime = (minutes * 60 * 1000) + (seconds * 1000).toLong()
                result[startTime] = text
            }
        }

        return result
    }

    private fun findNearestTimedText(timedMap: Map<Long, String>, targetStart: Long): String? {
        if (timedMap.isEmpty()) return null
        timedMap[targetStart]?.let { return it }

        val nearest = timedMap.minByOrNull { kotlin.math.abs(it.key - targetStart) } ?: return null
        val delta = kotlin.math.abs(nearest.key - targetStart)
        return if (delta <= 800L) nearest.value else null
    }

    private fun mergeNeteaseAuxiliaryLines(
        mainLines: List<LyricLine>,
        translationContent: String?,
        transliterationContent: String?
    ): List<LyricLine> {
        val translationLines = translationContent
            ?.takeIf { it.isNotBlank() }
            ?.let { com.amll.droidmate.data.parser.LrcParser.parse(it) }
        val romanizationLines = transliterationContent
            ?.takeIf { it.isNotBlank() }
            ?.let { com.amll.droidmate.data.parser.LrcParser.parse(it) }

        val strictMerged = mergeLyricLines(mainLines, translationLines, romanizationLines)

        val translationMap = parseTimedLRCMap(translationContent)
        val romanizationMap = parseTimedLRCMap(transliterationContent)

        val beforeTranslation = strictMerged.count { !it.translation.isNullOrBlank() }
        val beforeRomanization = strictMerged.count { !it.transliteration.isNullOrBlank() }

        val completed = strictMerged.map { line ->
            val nearestTranslation = if (line.translation.isNullOrBlank()) {
                findNearestTimedText(translationMap, line.startTime)
                    ?.let(::decodeCommonEntities)
                    ?.takeIf { it.isNotBlank() }
            } else {
                null
            }

            val nearestRomanization = if (line.transliteration.isNullOrBlank()) {
                findNearestTimedText(romanizationMap, line.startTime)
                    ?.let(::decodeCommonEntities)
                    ?.takeIf { it.isNotBlank() }
            } else {
                null
            }

            line.copy(
                translation = line.translation ?: nearestTranslation,
                transliteration = line.transliteration ?: nearestRomanization
            )
        }

        val afterTranslation = completed.count { !it.translation.isNullOrBlank() }
        val afterRomanization = completed.count { !it.transliteration.isNullOrBlank() }
        if (afterTranslation > beforeTranslation || afterRomanization > beforeRomanization) {
            Timber.d(
                "Netease nearest-time fallback filled lines: translation $beforeTranslation->$afterTranslation, romanization $beforeRomanization->$afterRomanization"
            )
        }

        return completed
    }

    private fun decodeCommonEntities(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&#32;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    /**
     * 从 AMLL TTML DB 获取歌词 (通过网易云音乐ID)
     */
    open suspend fun getAMLL_TTMLLyrics(
        songId: String,
        title: String? = null,
        artist: String? = null
    ): TTMLLyrics? {
        // allow callers to pass a platform prefix (e.g. "qq:12345") or omit for
        // backwards compatibility.  default platform is ncm (网易云).
        val (platform, rawId) = parseAmlLId(songId)
        // for QQ IDs we may receive "id1::id2".
        // which is the typical Unilyric format, so split on
        // "::" first to avoid producing an empty string for the second part.
        val candidateIds = when {
            platform == "qq" && rawId.contains("::") -> rawId.split("::", limit = 2)
            else -> listOf(rawId)
        }

        var unknownHostCount = 0
        var totalEndpoints = 0
        lastAmlLError = null

        for (normalizedSongId in candidateIds) {
            // build the list of endpoints; the mirrors we had before only host the ncm
            // archive so they should only be used when platform == "ncm".  the new
            // public service covers all supported platforms.
            val endpoints = mutableListOf<String>()
            endpoints += "https://amll-ttml-db.stevexmh.net/$platform/$normalizedSongId"
            if (platform == "ncm") {
                endpoints += "https://amlldb.bikonoo.com/ncm-lyrics/$normalizedSongId.ttml"
                endpoints += "https://amll.mirror.dimeta.top/api/db/ncm-lyrics/$normalizedSongId.ttml"
                endpoints += "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/ncm-lyrics/$normalizedSongId.ttml"
            }

            totalEndpoints += endpoints.size

            for (url in endpoints) {
                try {
                    val response = httpClient.get(url)
                    if (!response.status.isSuccess()) {
                        Timber.w("AMLL endpoint non-success status ${response.status.value}: $url")
                        continue
                    }

                    val content = response.body<String>()
                    if (!content.contains("<tt", ignoreCase = true)) {
                        Timber.w("AMLL endpoint returned non-TTML payload: $url")
                        continue
                    }

                    val parsed = parseTTML(content, title, artist)
                    if (parsed != null && parsed.lines.isNotEmpty()) {
                        Timber.i("Fetched AMLL lyrics from: $url")
                        lastAmlLError = null
                        return parsed
                    }
                    Timber.w("TTML parse yielded empty result: $url")
                } catch (e: UnknownHostException) {
                    unknownHostCount += 1
                    Timber.w(e, "AMLL host unresolved: $url")
                } catch (e: Exception) {
                    Timber.w(e, "AMLL endpoint failed: $url")
                }
            }
        }

        lastAmlLError = if (unknownHostCount == totalEndpoints) {
            "All AMLL hosts are unreachable (DNS)."
        } else {
            "Lyrics not found on AMLL mirrors for songId=$rawId"
        }

        Timber.w("Error fetching AMLL TTML lyrics: $lastAmlLError")
        return null
    }

    /**
     * 智能综合搜索歌词 - 尝试多个来源并选择最佳结果
     * 基于 Unilyric 的多源搜索策略
     * 使用并行搜索加速（对齐 Unilyric）
     */
    open suspend fun searchLyrics(
        title: String,
        artist: String
    ): List<LyricsSearchResult> = searchLyricsIncremental(title, artist) { }

    /**
     * 增量搜索歌词：某个来源先返回就先通知上层，避免 UI 必须等待全部来源结束。
     */
    open suspend fun searchLyricsIncremental(
        title: String,
        artist: String,
        onResult: suspend (LyricsSearchResult) -> Unit
    ): List<LyricsSearchResult> = coroutineScope {
        Timber.i("Starting multi-source parallel lyrics search for: $title - $artist")

        val results = mutableListOf<LyricsSearchResult>()
        val dedupKeys = mutableSetOf<String>()
        val mutex = Mutex()

        suspend fun tryPublish(result: LyricsSearchResult?) {
            if (result == null) return
            val isNew = mutex.withLock {
                val key = "${result.provider.lowercase()}:${result.songId}"
                if (!dedupKeys.add(key)) {
                    false
                } else {
                    results.add(result)
                    true
                }
            }
            if (isNew) {
                Timber.d("Incremental search result: ${result.provider}(${result.confidence}) ${result.title} - ${result.artist}")
                onResult(result)
            }
        }

        val amllJob = launch {
            Timber.i("AMLL search starting...")
            val result = runCatching { searchAMLL(title, artist) }.getOrNull()
            Timber.i("AMLL search completed: ${if (result != null) "found" else "null"}")
            tryPublish(result)
        }

        val qqMusicJob = launch {
            Timber.i("QQ Music search starting...")
            val list = runCatching { searchQQMusic(title, artist) }.getOrNull() ?: emptyList()
            Timber.i("QQ Music search completed: found ${list.size} items")
            for (r in list) tryPublish(r)

            // additionally probe AMLL DB using each QQ id if we found candidates
            for (result in list) {
                val rawId = result.songId
                val segments = rawId.split("::")
                var published = false
                for (seg in segments) {
                    if (seg.isBlank()) continue
                    val prefixId = "qq:$seg"
                    Timber.d("Probing AMLL DB for QQ candidate segment: $prefixId (raw=$rawId)")
                    val amllResult = runCatching {
                        getAMLL_TTMLLyrics(prefixId, title = result.title, artist = result.artist)
                    }.getOrNull()
                    if (amllResult != null) {
                        val wrapped = result.copy(
                            provider = "amll",
                            songId = prefixId,
                            title = amllResult.metadata.title.takeIf { it.isNotBlank() } ?: result.title,
                            artist = amllResult.metadata.artist.takeIf { it.isNotBlank() } ?: result.artist,
                            album = amllResult.metadata.album ?: result.album
                        )
                        Timber.d("QQ AMLL probe succeeded with segment '$seg', publishing amll result")
                        tryPublish(wrapped)
                        published = true
                        break // stop after first successful match
                    } else {
                        Timber.d("QQ AMLL probe for segment '$seg' returned no data")
                    }
                }
                if (!published) {
                    Timber.d("QQ AMLL probe found no matching segments for rawId=$rawId")
                }
            }
        }

        val kugouJob = launch {
            Timber.i("Kugou search starting...")
            val list = runCatching { searchKugou(title, artist) }.getOrNull() ?: emptyList()
            Timber.i("Kugou search completed: found ${list.size} items")
            for (r in list) tryPublish(r)
        }

        val neteaseJob = launch {
            Timber.i("Netease search starting...")
            val list = runCatching { searchNetease(title, artist) }.getOrNull() ?: emptyList()
            Timber.i("Netease search completed: found ${list.size} items")
            for (r in list) tryPublish(r)

            // additionally probe AMLL DB using each Netease id
            for (result in list) {
                val prefixId = "ncm:${result.songId}"
                Timber.d("Probing AMLL DB for Netease candidate: $prefixId")
                val amllResult = runCatching {
                    getAMLL_TTMLLyrics(prefixId, title = result.title, artist = result.artist)
                }.getOrNull()?.let { lyrics ->
                    result.copy(
                        provider = "amll",
                        songId = prefixId,
                        title = lyrics.metadata.title.takeIf { it.isNotBlank() } ?: result.title,
                        artist = lyrics.metadata.artist.takeIf { it.isNotBlank() } ?: result.artist,
                        album = lyrics.metadata.album ?: result.album
                    )
                }
                if (amllResult != null) {
                    Timber.i("Netease AMLL probe succeeded, publishing amll result")
                    tryPublish(amllResult)
                } else {
                    Timber.i("Netease AMLL probe returned no data")
                }
            }
        }

        amllJob.join()
        qqMusicJob.join()
        kugouJob.join()
        neteaseJob.join()

        val sortedResults = results.sortedByDescending { it.confidence }

        Timber.i("Search completed: Found ${results.size} total results, ${sortedResults.size} after dedup")
        Timber.i("Results after sort: ${sortedResults.map { "${it.provider.uppercase()}(${it.confidence})" }}")

        sortedResults
    }
    
    /**
     * 智能获取歌词 - 自动搜索并选择最佳来源
     * 这是推荐的歌词获取方式,模仿 Unilyric 的工作流程
     *
     * 优先级说明：如果本地缓存（AMLL 数据库）包含候选歌词，会在
     * 其他在线来源之前尝试返回该缓存结果。
     *
     * @param currentSourceName 可选的播放来源名称（例如播放器应用名）。
     *   若传入且搜索候选在置信度/功能上完全相等，则有助于优先选择
     *   与当前来源相关的结果（如网易、QQ、酷狗）。
     */
    suspend fun fetchLyricsAuto(
        title: String,
        artist: String,
        currentSourceName: String? = null
    ): LyricsResult {
        // simplified flow: search and immediately use the top candidate
        // 新增: 优先检查应用本地缓存（LyricsCacheRepository），如果存在则直接返回。
        // 这是用户保存的歌词缓存，与 AM MLL DB 等来源无关。
        try {
            Timber.i("Auto-fetching lyrics for: $title - $artist")

            // check local cached lyrics first
            cacheRepo?.let {
                getCachedLyrics(title, artist)?.let { cachedResult ->
                    Timber.i("Returning lyrics from local cache: ${cachedResult.source}")
                    return cachedResult
                }
            }

            var searchResults = searchLyrics(title, artist)
            if (searchResults.isEmpty()) {
                Timber.w("No search results found for: $title - $artist")
                return LyricsResult(
                    isSuccess = false,
                    errorMessage = "未找到歌曲,请检查歌曲名和歌手名"
                )
            }

            // 优先尝试来自 AMLL TTML 数据库的候选（provider=="amll"），
            // 该库被视为高质量“缓存/权威来源”，但此时已经确认本地缓存不存在。
            val localCandidates = searchResults.filter { it.provider.equals("amll", ignoreCase = true) }
            if (localCandidates.isNotEmpty()) {
                // 按置信度降序遍历，成功则直接返回
                for (candidate in localCandidates.sortedByDescending { it.confidence }) {
                    Timber.i("尝试使用本地缓存候选: ${candidate.songId} conf=${candidate.confidence}")
                    val cached = getLyrics(candidate.provider, candidate.songId, candidate.title, candidate.artist)
                    if (cached.isSuccess) {
                        return cached.copy(
                            source = formatAutoSource(
                                provider = candidate.provider,
                                title = candidate.title,
                                artist = candidate.artist,
                                songId = candidate.songId
                            )
                        )
                    }
                    Timber.w("本地缓存候选获取失败, 将尝试其它来源: id=${candidate.songId}")
                }
                // 如果所有本地候选都失败了，则继续走正常流程
            }

            if (searchResults.size > 1) {
                searchResults = adjustResultsForFeatures(searchResults, currentSourceName)
            }

            val top = searchResults.first()
            Timber.d("Selected top candidate after sorting: ${top.provider} id=${top.songId} conf=${top.confidence}")

            // fetch using generic getter
            val result = getLyrics(top.provider, top.songId, top.title, top.artist)
            if (result.isSuccess) {
                // ensure source matches previous "自动识别:" style
                return result.copy(
                    source = formatAutoSource(
                        provider = top.provider,
                        title = top.title,
                        artist = top.artist,
                        songId = top.songId
                    )
                )
            }
            return result
        } catch (e: Exception) {
            Timber.e(e, "Error in auto-fetch lyrics")
            return LyricsResult(
                isSuccess = false,
                errorMessage = "获取歌词时发生错误: ${e.message}"
            )
        }
    }

    /**
     * 获取歌词 (通过指定provider和songId)
     * @param title 歌曲标题（可选，用于更新元数据）
     * @param artist 歌手名（可选，用于更新元数据）
     */
    /**
     * 如果本地缓存（LyricsCacheRepository）有匹配项，则返回该结果。
     * 子类可覆盖以模拟测试行为。
     */
    protected open suspend fun getCachedLyrics(title: String, artist: String): LyricsResult? {
        val entry = cacheRepo?.findBySong(title, artist) ?: return null
        val parsed = entry.ttmlContent.takeIf { it.isNotBlank() }
            ?.let { parseTTML(it, entry.title, entry.artist) }
            ?: return null
        return LyricsResult(
            isSuccess = true,
            lyrics = parsed,
            source = entry.source
        )
    }

    suspend fun getLyrics(
        provider: String,
        songId: String,
        title: String? = null,
        artist: String? = null
    ): LyricsResult {
        return try {
            val normalizedProvider = provider.lowercase()
            val lyrics = when (normalizedProvider) {
                "amll" -> getAMLL_TTMLLyrics(songId, title, artist)
                "netease", "ncm" -> getNeteaseLyrics(songId, title, artist)
                "qq", "qqmusic" -> getQQMusicLyrics(songId, title, artist)
                "kugou" -> getKugouLyrics(songId, title, artist)
                else -> {
                    Timber.e("Unknown provider: $provider")
                    return LyricsResult(
                        isSuccess = false,
                        errorMessage = "不支持的歌词来源: $provider"
                    )
                }
            }
            
            if (lyrics != null) {
                LyricsResult(
                    isSuccess = true,
                    lyrics = lyrics,
                    source = provider.uppercase()
                )
            } else {
                LyricsResult(
                    isSuccess = false,
                    errorMessage = if (normalizedProvider == "amll") {
                        lastAmlLError ?: "从 $provider 获取歌词失败"
                    } else {
                        "从 $provider 获取歌词失败"
                    }
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting lyrics from $provider")
            LyricsResult(
                isSuccess = false,
                errorMessage = "获取歌词出错: ${e.message}"
            )
        }
    }

    /**
     * Fetch the raw lyrics for a candidate and analyze which features it supports.
     * Used by UI when displaying custom candidates so we can show extra hints.
     * This is intentionally lightweight – it reuses the same provider-specific
     * fetch routines used by [getLyrics], then runs a quick scan on the parsed
     * TTML object.
     */
    open suspend fun getLyricsFeatures(
        provider: String,
        songId: String,
        title: String? = null,
        artist: String? = null
    ): Set<LyricsFeature> {
        val normalizedProvider = provider.lowercase()
        val lyrics: TTMLLyrics? = when (normalizedProvider) {
            "amll" -> getAMLL_TTMLLyrics(songId, title, artist)
            "netease", "ncm" -> getNeteaseLyrics(songId, title, artist)
            "qq", "qqmusic" -> getQQMusicLyrics(songId, title, artist)
            "kugou" -> getKugouLyrics(songId, title, artist)
            else -> null
        }
        return lyrics?.let { analyzeFeatures(it) } ?: emptySet()
    }

    /**
     * 重新排序搜索结果，使匹配度相差不大的时功能多的靠前。
     * 这是一个独立的辅助手段，主要给 fetchLyricsAuto 和测试使用。
     */
    /**
     * 重新排序搜索结果，使匹配度相差不大的时功能多的靠前。
     * 这是一个独立的辅助手段，主要给 fetchLyricsAuto 和测试使用。
     *
     * @param currentSourceName 可选的当前播放来源名称（如应用名）。
     *   在候选置信度与功能完全相同时，如果该字符串包含特定关键词，
     *   会按以下优先级调整：
     *     - 包含“网易”时优先网易云
     *     - 包含“QQ”时优先QQ音乐，其次酷狗
     *     - 包含“酷狗”时优先酷狗，其次QQ音乐
     */
    internal suspend fun adjustResultsForFeatures(
        results: List<LyricsSearchResult>,
        currentSourceName: String? = null
    ): List<LyricsSearchResult> {
        if (results.size <= 1) return results
        val decorated = results.map { result ->
            val features = getLyricsFeatures(result.provider, result.songId, result.title, result.artist)
            result to features
        }
        return decorated.sortedWith(Comparator { a, b ->
            // 2. confidence
            val diff = a.first.confidence - b.first.confidence
            if (diff != 0f) return@Comparator -diff.compareTo(0f)

            // 3. features
            val featureDiff = b.second.size - a.second.size
            if (featureDiff != 0) return@Comparator featureDiff

            // 4. current source bias
            if (!currentSourceName.isNullOrBlank()) {
                val lower = currentSourceName.lowercase()
                val priorityList = when {
                    lower.contains("网易") -> listOf("netease")
                    lower.contains("qq") -> listOf("qq", "kugou")
                    lower.contains("酷狗") -> listOf("kugou", "qq")
                    else -> emptyList()
                }
                if (priorityList.isNotEmpty()) {
                    val aIndex = priorityList.indexOf(a.first.provider.lowercase()).let { if (it < 0) Int.MAX_VALUE else it }
                    val bIndex = priorityList.indexOf(b.first.provider.lowercase()).let { if (it < 0) Int.MAX_VALUE else it }
                    if (aIndex != bIndex) return@Comparator aIndex.compareTo(bIndex)
                }

                // 4b. AMLL prefix check
                fun amllMatch(r: LyricsSearchResult): Boolean {
                    if (!r.provider.equals("amll", true)) return false
                    val parts = r.songId.split(":", limit = 2)
                    if (parts.size < 2) return false
                    val prefix = parts[0].lowercase()
                    return when {
                        lower.contains("网易") -> prefix == "netease" || prefix == "ncm"
                        lower.contains("qq") -> prefix == "qq" || prefix == "qqmusic"
                        lower.contains("酷狗") -> prefix == "kugou"
                        else -> false
                    }
                }
                val aMatch = amllMatch(a.first)
                val bMatch = amllMatch(b.first)
                if (aMatch != bMatch) return@Comparator if (aMatch) -1 else 1
            }

            // 5. fixed provider priority with TME rules
            val bothTme = setOf("qq", "kugou").contains(a.first.provider.lowercase()) &&
                    setOf("qq", "kugou").contains(b.first.provider.lowercase())
            val tmeSource = currentSourceName?.lowercase()?.let { it.contains("qq") || it.contains("酷狗") } ?: false
            if (bothTme && tmeSource) {
                val lowerSource = currentSourceName?.lowercase() ?: ""
                val preferKugou = lowerSource.contains("酷狗") && !lowerSource.contains("qq")
                val preferQQ = lowerSource.contains("qq") && !lowerSource.contains("酷狗")
                if (preferKugou) {
                    if (a.first.provider.lowercase() == "kugou" && b.first.provider.lowercase() == "qq") return@Comparator -1
                    if (a.first.provider.lowercase() == "qq" && b.first.provider.lowercase() == "kugou") return@Comparator 1
                } else if (preferQQ) {
                    if (a.first.provider.lowercase() == "qq" && b.first.provider.lowercase() == "kugou") return@Comparator -1
                    if (a.first.provider.lowercase() == "kugou" && b.first.provider.lowercase() == "qq") return@Comparator 1
                }
                // else equal
            } else {
                val providerPriority = mapOf(
                    "cache" to -1,
                    "amll" to 0,
                    "kugou" to 1,
                    "netease" to 2,
                    "ncm" to 2,
                    "qq" to 3,
                    "qqmusic" to 3
                )
                val pa = providerPriority[a.first.provider.lowercase()] ?: Int.MAX_VALUE
                val pb = providerPriority[b.first.provider.lowercase()] ?: Int.MAX_VALUE
                if (pa != pb) return@Comparator pa - pb
            }

            // 6. equal -> preserve arrival order
            0
        }).map { it.first }
    }

    private fun analyzeFeatures(lyrics: TTMLLyrics): Set<LyricsFeature> {
        val features = mutableSetOf<LyricsFeature>()
        val lines = lyrics.lines
        if (lines.any { it.isDuet }) features.add(LyricsFeature.DUET)
        if (lines.any { it.isBG }) features.add(LyricsFeature.BACKGROUND)
        if (lines.any { it.translation?.isNotBlank() == true }) features.add(LyricsFeature.TRANSLATION)
        if (lines.any { it.transliteration?.isNotBlank() == true }) features.add(LyricsFeature.TRANSLITERATION)
        // treat 'words' feature as present only if there is at least one line
        // with more than one word, or the single word does not exactly match the
        // line timing.  This prevents ordinary "line‑timed" lyrics from being
        // flagged as word‑level.
        val hasRealWords = lines.any { line ->
            val w = line.words
            if (w.isEmpty()) return@any false
            // if there is only one word and its timing equals the line's timing,
            // that's just a line marker, not a true word-level transcript.
            if (w.size == 1 && w[0].startTime == line.startTime && w[0].endTime == line.endTime) {
                false
            } else {
                true
            }
        }
        if (hasRealWords) features.add(LyricsFeature.WORDS)
        if (lines.zipWithNext().any { it.second.startTime < it.first.endTime }) {
            features.add(LyricsFeature.OVERLAP)
        }
        return features
    }

    companion object {
        /**
         * Format a human-readable source string for lyrics that were obtained
         * automatically after a track change.  This adds the "自动识别:" prefix
         * and includes title/artist/id similar to candidate labels.
         */
        fun formatAutoSource(
            provider: String,
            title: String,
            artist: String,
            songId: String? = null
        ): String {
            var providerName = when (provider.lowercase()) {
                "amll" -> "AMLL TTML DB"
                "netease", "ncm" -> "网易云音乐"
                "qq", "qqmusic" -> "QQ音乐"
                "kugou" -> "酷狗音乐"
                else -> provider.uppercase()
            }
            var idPart = songId ?: ""
            if (provider.lowercase() == "amll" && !idPart.isNullOrBlank() && idPart.contains(":")) {
                val parts = idPart.split(":", limit = 2)
                val plat = parts[0].uppercase()
                idPart = parts[1]
                providerName = "$providerName($plat)"
            }
            return "自动识别:$providerName：$title - $artist($idPart)"
        }

        /**
         * 简单的 TTML 解析器
         *
         * @param title 可选歌曲标题，用于覆盖 TTML 文件中可能缺失的元数据
         * @param artist 可选歌手名，用于覆盖 TTML 文件中可能缺失的元数据
         */
        fun parseTTML(
            ttmlContent: String,
            title: String? = null,
            artist: String? = null
        ): TTMLLyrics? {
            return try {
                Timber.d("[BG-LYRICS-DEBUG] LyricsRepository.parseTTML input: length=${ttmlContent.length}, hasXbg=${ttmlContent.contains("ttm:role=\"x-bg\"")}, hasXTranslation=${ttmlContent.contains("ttm:role=\"x-translation\"")}")
                val lines = TTMLParser.parse(ttmlContent)
                val bgLines = lines.filter { it.isBG }
                val bgWithTranslation = bgLines.count { !it.translation.isNullOrBlank() }
                val sampleBg = bgLines.firstOrNull()
                Timber.d("[BG-LYRICS-DEBUG] LyricsRepository.parseTTML output: total=${lines.size}, bg=${bgLines.size}, bgWithTrans=$bgWithTranslation, sampleBg='${sampleBg?.text?.take(40) ?: ""}', sampleTrans='${sampleBg?.translation?.take(40) ?: ""}'")
                TTMLLyrics(
                    metadata = TTMLMetadata(
                        title = title ?: "Unknown",
                        artist = artist ?: "Unknown"
                    ),
                    lines = lines.sortedBy { it.startTime }
                )
            } catch (e: Exception) {
                Timber.e(e, "Error parsing TTML")
                null
            }
        }

        private fun isWhitespaceDelimiterWithoutNewline(text: String): Boolean {
            if (text.isEmpty()) return false
            if (!text.all { it.isWhitespace() }) return false
            val hasNewline = text.any { it == '\n' || it == '\r' }
            if (hasNewline) return false
            return text.any { it.isWhitespace() }
        }

        private fun extractAttribute(attributes: String, attrName: String): String? {
            val regex = Regex("""\b${Regex.escape(attrName)}\s*=\s*"([^"]*)""", RegexOption.IGNORE_CASE)
            return regex.find(attributes)?.groupValues?.getOrNull(1)
        }

        private fun decodeXmlEntities(text: String): String {
            return text
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&#32;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }

        private fun appendSpaceToPreviousWord(words: MutableList<LyricWord>) {
            if (words.isEmpty()) return
            val last = words.last()
            if (last.word.isNotEmpty() && !last.word.last().isWhitespace()) {
                words[words.lastIndex] = last.copy(word = "${last.word} ")
            }
        }

        /**
         * TTML <p>/<span> 空格保留策略：
         * 1. span 之间的外部空白补到前一个词尾；
         * 2. span 内文本（包括前后空格）按原样保留。
         */
        private fun normalizeWordText(
            rawText: String,
            hasExternalSpaceBefore: Boolean,
            words: MutableList<LyricWord>
        ): String? {
            // TTML <p>/<span> 中空格是可见内容，不能用 trim 抹掉。
            if (hasExternalSpaceBefore) {
                appendSpaceToPreviousWord(words)
            }

            if (rawText.isEmpty()) {
                return null
            }

            return rawText
        }
        
        /**
         * 将时间字符串转换为毫秒
         * 支持格式: "mm:ss.ms" 或 "mm:ss.mss" 或 "00:01.500s"
         */
        fun timeToMillis(timeStr: String): Long {
            return try {
                val cleanStr = timeStr.replace("[sS]$".toRegex(), "")
                val parts = cleanStr.split(":")
                if (parts.size < 2) return 0L
                
                val minutes = parts[0].toLongOrNull() ?: 0L
                val secondsParts = parts[1].split(".")
                val seconds = secondsParts.getOrNull(0)?.toLongOrNull() ?: 0L
                val millis = secondsParts.getOrNull(1)?.let {
                    // 补齐到3位数字
                    it.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                } ?: 0L
                
                (minutes * 60 * 1000) + (seconds * 1000) + millis
            } catch (e: Exception) {
                Timber.e("Error parsing time: $timeStr")
                0L
            }
        }
    }
}
