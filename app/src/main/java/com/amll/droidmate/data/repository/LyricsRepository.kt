package com.amll.droidmate.data.repository

import com.amll.droidmate.data.parser.mergeLyricLines
import com.amll.droidmate.data.parser.LyricsFormat
import com.amll.droidmate.data.network.NeteaseEapiCrypto
import com.amll.droidmate.data.network.QqMusicQrcCrypto
import com.amll.droidmate.domain.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.net.UnknownHostException
import timber.log.Timber

/**
 * 歌词仓库 - 从多个来源获取和管理歌词
 * 基于 Unilyric 的多源搜索逻辑实现
 */
class LyricsRepository(private val httpClient: HttpClient) {

    private enum class MatchType(val score: Int) {
        NONE(-1),
        VERY_LOW(10),
        LOW(30),
        MEDIUM(70),
        PRETTY_HIGH(90),
        HIGH(95),
        VERY_HIGH(99),
        PERFECT(100)
    }

    private enum class NameMatchType(val score: Int) {
        NO_MATCH(0), LOW(2), MEDIUM(4), HIGH(5), VERY_HIGH(6), PERFECT(7)
    }

    private enum class ArtistMatchType(val score: Int) {
        NO_MATCH(0), LOW(2), MEDIUM(4), HIGH(5), VERY_HIGH(6), PERFECT(7)
    }

    @Volatile
    private var lastAmlLError: String? = null

    /**
     * 从 QQ 音乐搜索歌词
     */
    suspend fun searchQQMusic(title: String, artist: String): LyricsSearchResult? {
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
                Timber.w("QQ Music search failed: ${searchResponse.status}")
                return null
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
                Timber.d("No QQ Music results found for: $keyword")
                return null
            }
            
            // 取第一个结果
            val firstSong = songList[0].jsonObject
            val songMid = firstSong["mid"]?.jsonPrimitive?.content ?: return null
            val songIdNum = firstSong["id"]?.jsonPrimitive?.longOrNull
            val songTitle = firstSong["title"]?.jsonPrimitive?.content
                ?: firstSong["name"]?.jsonPrimitive?.content
                ?: title
            val singerName = firstSong["singer"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content ?: artist
            
            // 计算匹配度和匹配等级（对齐 Unilyric 打分模型）
            val matchEval = evaluateMatch(title, artist, songTitle, singerName)
            
            Timber.i("Found QQ Music song: $songTitle - $singerName (mid: $songMid, confidence: ${matchEval.confidence}, match=${matchEval.matchType})")
            
            LyricsSearchResult(
                provider = "qq",
                songId = if (songIdNum != null) "$songMid::$songIdNum" else songMid,
                title = songTitle,
                artist = singerName,
                confidence = matchEval.confidence,
                matchType = matchEval.matchType
            )
        } catch (e: Exception) {
            Timber.e(e, "Error searching QQ Music")
            null
        }
    }
    
    /**
     * 从 QQ 音乐获取歌词内容
     */
    suspend fun getQQMusicLyrics(songMid: String, title: String? = null, artist: String? = null): TTMLLyrics? {
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
                Timber.w("QQ Music lyrics fetch failed: ${response.status}")
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
                Timber.w("No lyrics content from QQ Music for: $fallbackMid")
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
            
            // QQ音乐歌词是base64编码的LRC/QRC格式，使用新的统一解析器
            val decodedLyric = String(android.util.Base64.decode(contentToUse, android.util.Base64.DEFAULT))
            
            // 调试日志：显示解码后的前500个字符
            Timber.d("Decoded QQ Music lyrics (first 500 chars): ${decodedLyric.take(500)}")
            
            // 使用统一解析器处理多种格式（QRC、LRC等）
            val mainLyrics = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
                content = decodedLyric,
                title = title ?: "Unknown",
                artist = artist ?: "Unknown"
            ) ?: return null

            val translationLines = transContent
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    runCatching {
                        val decoded = String(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
                        com.amll.droidmate.data.parser.LrcParser.parse(decoded)
                    }.getOrNull()
                }

            val romanizationLines = romaContent
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    runCatching {
                        val decoded = String(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
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
            Timber.w(e, "QQ lyric_download failed, fallback to PlayLyricInfo")
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
    suspend fun searchNetease(title: String, artist: String): LyricsSearchResult? {
        return try {
            val keyword = "$title $artist".trim()

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
                Timber.w("Netease search failed: ${response.status}")
                return null
            }
            
            val json = Json { ignoreUnknownKeys = true }
            val responseBody = response.body<String>()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            val code = responseJson["code"]?.jsonPrimitive?.intOrNull
            if (code != null && code != 200) return null
            
            // 解析搜索结果
            val songList = responseJson["result"]
                ?.jsonObject?.get("songs")
                ?.jsonArray
            
            if (songList.isNullOrEmpty()) {
                Timber.d("No Netease results found for: $keyword")
                return null
            }
            
            // 取第一个结果
            val firstSong = songList[0].jsonObject
            val songId = firstSong["id"]?.jsonPrimitive?.long?.toString() ?: return null
            val songTitle = firstSong["name"]?.jsonPrimitive?.content ?: title
            val singerName = firstSong["artists"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content ?: artist
            
            // 计算匹配度和匹配等级（对齐 Unilyric 打分模型）
            val matchEval = evaluateMatch(title, artist, songTitle, singerName)
            
            Timber.i("Found Netease song: $songTitle - $singerName (id: $songId, confidence: ${matchEval.confidence}, match=${matchEval.matchType})")
            
            LyricsSearchResult(
                provider = "netease",
                songId = songId,
                title = songTitle,
                artist = singerName,
                confidence = matchEval.confidence,
                matchType = matchEval.matchType
            )
        } catch (e: Exception) {
            Timber.e(e, "Error searching Netease")
            null
        }
    }
    
    /**
     * 从网易云音乐获取歌词内容
     */
    suspend fun getNeteaseLyrics(songId: String, title: String? = null, artist: String? = null): TTMLLyrics? {
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
                Timber.w("Netease lyrics fetch failed: ${response.status}")
                return null
            }
            
            val json = Json { ignoreUnknownKeys = true }
            val responseBody = response.body<String>()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            val code = responseJson["code"]?.jsonPrimitive?.intOrNull
            if (code != null && code != 200) {
                Timber.w("Netease lyrics API returned code=$code")
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
                Timber.w("No lyrics content from Netease for: $songId")
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

            val translationLines = translationContent
                ?.takeIf { it.isNotBlank() }
                ?.let { com.amll.droidmate.data.parser.LrcParser.parse(it) }
            val romanizationLines = transliterationContent
                ?.takeIf { it.isNotBlank() }
                ?.let { com.amll.droidmate.data.parser.LrcParser.parse(it) }

            val mergedLines = mergeLyricLines(mainLyrics.lines, translationLines, romanizationLines)
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
    suspend fun searchKugou(title: String, artist: String): LyricsSearchResult? {
        return try {
            val keyword = "$title $artist".trim()
            
            // 酷狗搜索API
            val response = httpClient.get("http://mobilecdn.kugou.com/api/v3/search/song") {
                parameter("keyword", keyword)
                parameter("page", "1")
                parameter("pagesize", "5")
                parameter("showtype", "1")
            }
            
            if (!response.status.isSuccess()) {
                Timber.w("Kugou search failed: ${response.status}")
                return null
            }
            
            val json = Json { ignoreUnknownKeys = true }
            val responseBody = response.body<String>()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            // 解析搜索结果
            val songList = responseJson["data"]
                ?.jsonObject?.get("info")
                ?.jsonArray
            
            if (songList.isNullOrEmpty()) {
                Timber.d("No Kugou results found for: $keyword")
                return null
            }
            
            // 取第一个结果
            val firstSong = songList[0].jsonObject
            val hash = firstSong["hash"]?.jsonPrimitive?.content ?: return null
            val songTitle = firstSong["songname"]?.jsonPrimitive?.content ?: title
            val singerName = firstSong["singername"]?.jsonPrimitive?.content ?: artist
            
            // 计算匹配度和匹配等级（对齐 Unilyric 打分模型）
            val matchEval = evaluateMatch(title, artist, songTitle, singerName)
            
            Timber.i("Found Kugou song: $songTitle - $singerName (hash: $hash, confidence: ${matchEval.confidence}, match=${matchEval.matchType})")
            
            LyricsSearchResult(
                provider = "kugou",
                songId = hash,
                title = songTitle,
                artist = singerName,
                confidence = matchEval.confidence,
                matchType = matchEval.matchType
            )
        } catch (e: Exception) {
            Timber.e(e, "Error searching Kugou")
            null
        }
    }
    
    /**
     * 从酷狗音乐获取歌词内容
     */
    suspend fun getKugouLyrics(hash: String, title: String? = null, artist: String? = null): TTMLLyrics? {
        return try {
            val response = httpClient.get("http://www.kugou.com/yy/index.php") {
                parameter("r", "play/getdata")
                parameter("hash", hash)
            }
            
            if (!response.status.isSuccess()) {
                Timber.w("Kugou lyrics fetch failed: ${response.status}")
                return null
            }
            
            val json = Json { ignoreUnknownKeys = true }
            val responseBody = response.body<String>()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            val lyricContent = responseJson["data"]
                ?.jsonObject?.get("lyrics")
                ?.jsonPrimitive?.contentOrNull
            
            if (lyricContent.isNullOrBlank()) {
                Timber.w("No lyrics content from Kugou for: $hash")
                return null
            }
            
            // 酷狗歌词需要base64解码后是KRC/LRC格式，使用新的统一解析器
            val decodedLyric = try {
                String(android.util.Base64.decode(lyricContent, android.util.Base64.DEFAULT))
            } catch (e: Exception) {
                lyricContent // 如果解码失败,直接使用原文
            }
            
            // 使用统一解析器处理多种格式（KRC、LRC等）
            val ttml = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
                content = decodedLyric,
                title = title ?: "Unknown",
                artist = artist ?: "Unknown"
            )
            Timber.i("Successfully fetched Kugou lyrics for: $hash")
            return ttml
            
        } catch (e: Exception) {
            Timber.e(e, "Error fetching Kugou lyrics")
            null
        }
    }
    
    /**
     * 规范化名称用于比较（基于 Unilyric 的 normalize_name_for_comparison）
     */
    private fun normalizeForComparison(name: String): String {
        return name
            .replace('’', '\'')                           // 特殊单引号 -> 普通单引号
            .replace("，", ",")                          // 全角逗号 -> 半角逗号
            .replace("（", " (")                         // 全角左括号 -> 半角+空格
            .replace("）", ") ")                         // 全角右括号 -> 半角+空格
            .replace("【", " (")                         // 中文方括号 -> 半角括号
            .replace("】", ") ")
            .replace("[", "(")                           // 方括号 -> 圆括号
            .replace("]", ")")
            .replace("acoustic version", "acoustic", ignoreCase = true)  // 语义简化
            .replace(Regex("\\s+"), " ")                 // 多个空格 -> 单个空格
            .trim()
    }

    private data class MatchEvaluation(
        val confidence: Float,
        val matchType: String
    )

    private fun evaluateMatch(
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
    private fun normalizeTitle(title: String): String {
        return normalizeForComparison(title.lowercase())
    }

    private fun normalizeArtistList(artist: String): List<String> {
        return artist.split(Regex("[/、,;]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeForComparison(it.lowercase()) }
    }

    private fun computeTextSame(text1: String, text2: String): Double {
        val maxLen = maxOf(text1.length, text2.length)
        if (maxLen == 0) return 100.0
        val distance = levenshteinDistance(text1, text2)
        return (1.0 - distance.toDouble() / maxLen.toDouble()) * 100.0
    }

    private fun levenshteinDistance(a: String, b: String): Int {
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
    private fun checkDashParenEquivalence(dashText: String, parenText: String): Boolean {
        val hasDash = dashText.contains(" - ") && !dashText.contains('(')
        val hasParen = parenText.contains('(') && !parenText.contains(" - ")

        if (!hasDash || !hasParen) return false
        val parts = dashText.split(" - ", limit = 2)
        if (parts.size != 2) return false
        val converted = "${parts[0].trim()} (${parts[1].trim()})"
        return converted == parenText
    }

    private fun compareName(name1: String?, name2: String?): NameMatchType? {
        val raw1 = name1?.trim().orEmpty()
        val raw2 = name2?.trim().orEmpty()
        if (raw1.isEmpty() || raw2.isEmpty()) return null

        val lowered1 = raw1.lowercase()
        val lowered2 = raw2.lowercase()

        if (lowered1 == lowered2) return NameMatchType.PERFECT

        val n1 = normalizeForComparison(lowered1)
        val n2 = normalizeForComparison(lowered2)
        if (n1 == n2) return NameMatchType.PERFECT

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
            if (b1 == b2) return NameMatchType.LOW
        }

        if (n1.length == n2.length) {
            val equalCount = n1.zip(n2).count { it.first == it.second }
            val ratio = equalCount.toDouble() / n1.length.toDouble()
            if ((ratio >= 0.8 && n1.length >= 4) || (ratio >= 0.5 && n1.length in 2..3)) {
                return NameMatchType.HIGH
            }
        }

        val sim = computeTextSame(n1, n2)
        return when {
            sim > 90.0 -> NameMatchType.VERY_HIGH
            sim > 80.0 -> NameMatchType.HIGH
            sim > 68.0 -> NameMatchType.MEDIUM
            sim > 55.0 -> NameMatchType.LOW
            else -> NameMatchType.NO_MATCH
        }
    }

    /**
     * 艺术家匹配（近似 Unilyric compare_artists）。
     * 当前未做繁简转换，其他规则保持一致。
     */
    private fun compareArtists(artists1: String?, artists2: String?): ArtistMatchType? {
        val list1 = normalizeArtistList(artists1.orEmpty())
        val list2 = normalizeArtistList(artists2.orEmpty())
        if (list1.isEmpty() || list2.isEmpty()) return null

        val l1Various = list1.any { it.contains("various") || it.contains("群星") }
        val l2Various = list2.any { it.contains("various") || it.contains("群星") }
        if ((l1Various && (l2Various || list2.size > 4)) || (l2Various && list1.size > 4)) {
            return ArtistMatchType.HIGH
        }

        val used = mutableSetOf<Int>()
        var intersection = 0
        for (a in list1) {
            var matchedIdx: Int? = null
            for ((idx, b) in list2.withIndex()) {
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

        val union = list1.size + list2.size - intersection
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

    private fun compareDuration(duration1: Long?, duration2: Long?): NameMatchType? {
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

    private fun compareTrack(
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

        val titleWeight = 1.0
        val artistWeight = 1.0
        val albumWeight = 0.4
        val durationWeight = 1.0
        val maxSingle = 7.0

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

        val thresholds = listOf(
            21.0 to MatchType.PERFECT,
            19.0 to MatchType.VERY_HIGH,
            17.0 to MatchType.HIGH,
            15.0 to MatchType.PRETTY_HIGH,
            11.0 to MatchType.MEDIUM,
            6.5 to MatchType.LOW,
            2.5 to MatchType.VERY_LOW
        )

        for ((threshold, mt) in thresholds) {
            if (normalizedScore > threshold) return mt
        }
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
    suspend fun getAMLL_TTMLLyrics(songId: String): TTMLLyrics? {
        val normalizedSongId = songId.removeSuffix(".ttml")
        val endpoints = listOf(
            "https://amll-ttml-db.stevexmh.net/ncm/$normalizedSongId",
            "https://amlldb.bikonoo.com/ncm-lyrics/$normalizedSongId.ttml",
            "https://amll.mirror.dimeta.top/api/db/ncm-lyrics/$normalizedSongId.ttml",
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/ncm-lyrics/$normalizedSongId.ttml"
        )

        var unknownHostCount = 0
        lastAmlLError = null

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

                val parsed = parseTTML(content)
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

        lastAmlLError = if (unknownHostCount == endpoints.size) {
            "All AMLL hosts are unreachable (DNS)."
        } else {
            "Lyrics not found on AMLL mirrors for songId=$normalizedSongId"
        }

        Timber.e("Error fetching AMLL TTML lyrics: $lastAmlLError")
        return null
    }

    /**
     * 智能综合搜索歌词 - 尝试多个来源并选择最佳结果
     * 基于 Unilyric 的多源搜索策略
     */
    suspend fun searchLyrics(
        title: String,
        artist: String
    ): List<LyricsSearchResult> {
        val results = mutableListOf<LyricsSearchResult>()
        
        Timber.i("Starting multi-source lyrics search for: $title - $artist")
        
        // 搜索各个平台。
        // 注：当前实现每个平台取一个最佳候选，再统一排序。

        // 1. QQ 音乐
        searchQQMusic(title, artist)?.let { 
            results.add(it)
            Timber.d("Added QQ Music result with confidence: ${it.confidence}")
        }
        
        // 2. 酷狗音乐
        searchKugou(title, artist)?.let { 
            results.add(it)
            Timber.d("Added Kugou result with confidence: ${it.confidence}")
        }

        // 3. 网易云音乐
        searchNetease(title, artist)?.let {
            results.add(it)
            Timber.d("Added Netease result with confidence: ${it.confidence}")
        }

        // 统一按匹配度降序排序，去重(provider + songId)。
        val sortedResults = results
            .sortedByDescending { it.confidence }
            .distinctBy { "${it.provider.lowercase()}:${it.songId}" }

        Timber.i("Found ${sortedResults.size} results, best confidence: ${sortedResults.firstOrNull()?.confidence}")
        
        return sortedResults
    }
    
    /**
     * 智能获取歌词 - 自动搜索并选择最佳来源
     * 这是推荐的歌词获取方式,模仿 Unilyric 的工作流程
     */
    suspend fun fetchLyricsAuto(
        title: String,
        artist: String
    ): LyricsResult {
        try {
            Timber.i("Auto-fetching lyrics for: $title - $artist")
            
            // 1. 先搜索所有来源
            val searchResults = searchLyrics(title, artist)
            
            if (searchResults.isEmpty()) {
                Timber.w("No search results found for: $title - $artist")
                return LyricsResult(
                    isSuccess = false,
                    errorMessage = "未找到歌曲,请检查歌曲名和歌手名"
                )
            }
            
            // 2. 先尝试 AMLL（仅针对高匹配的网易云候选，近似 Unilyric 的 PrettyHigh 门槛）
            val highConfidenceNeteaseCandidates = searchResults.filter {
                it.provider.equals("netease", ignoreCase = true) &&
                    (it.matchType == "PERFECT" ||
                        it.matchType == "VERY_HIGH" ||
                        it.matchType == "HIGH" ||
                        it.matchType == "PRETTY_HIGH")
            }
            for (neteaseResult in highConfidenceNeteaseCandidates) {
                Timber.d("Trying AMLL TTML DB first for high-confidence Netease ID: ${neteaseResult.songId}")
                val amllLyrics = getAMLL_TTMLLyrics(neteaseResult.songId)
                if (amllLyrics != null) {
                    Timber.i("Successfully fetched from AMLL TTML DB")
                    return LyricsResult(
                        isSuccess = true,
                        lyrics = amllLyrics,
                        source = "AMLL TTML DB (网易云 ${neteaseResult.songId})"
                    )
                }
            }

            // 3. 按候选排序顺序逐个尝试直连来源（对齐 Unilyric comprehensive 的逐候选尝试）
            for (result in searchResults) {
                Timber.d("Trying ${result.provider} with ID: ${result.songId}")
                val lyrics = when (result.provider) {
                    "netease" -> getNeteaseLyrics(result.songId, result.title, result.artist)
                    "qq" -> getQQMusicLyrics(result.songId, result.title, result.artist)
                    "kugou" -> getKugouLyrics(result.songId, result.title, result.artist)
                    else -> null
                }
                
                if (lyrics != null) {
                    Timber.i("Successfully fetched from ${result.provider}")
                    return LyricsResult(
                        isSuccess = true,
                        lyrics = lyrics,
                        source = "${result.provider.uppercase()} (${result.title} - ${result.artist})"
                    )
                }
            }
            
            // 4. 所有候选来源都失败
            Timber.w("All sources failed for: $title - $artist")
            return LyricsResult(
                isSuccess = false,
                errorMessage = "找到歌曲但无法获取歌词,可能该歌曲暂无歌词"
            )
            
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
    suspend fun getLyrics(
        provider: String,
        songId: String,
        title: String? = null,
        artist: String? = null
    ): LyricsResult {
        return try {
            val normalizedProvider = provider.lowercase()
            val lyrics = when (normalizedProvider) {
                "amll" -> getAMLL_TTMLLyrics(songId)
                "netease", "ncm" -> getNeteaseLyrics(songId, title, artist)
                "qq", "qqmusic" -> getQQMusicLyrics(songId, title, artist)
                "kugou" -> getKugouLyrics(songId, title, artist)
                else -> {
                    Timber.w("Unknown provider: $provider")
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

    companion object {
        /**
         * 简单的 TTML 解析器
         */
        fun parseTTML(ttmlContent: String): TTMLLyrics? {
            return try {
                val lines = mutableListOf<LyricLine>()
                
                // 正则表达式提取歌词行
                val pTagRegex = Regex("""<p\b[^>]*begin="([^"]+)"[^>]*end="([^"]+)"[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                val spanTagRegex = Regex("""<span\b([^>]*)>(.*?)</span>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                
                for (match in pTagRegex.findAll(ttmlContent)) {
                    val beginStr = match.groupValues[1]
                    val endStr = match.groupValues[2]
                    val content = match.groupValues[3]
                    
                    val beginMs = timeToMillis(beginStr)
                    val endMs = timeToMillis(endStr)
                    
                    // 提取所有 span 内容（逐词信息）
                    // 规则对齐 Unilyrics：仅在 span 之间存在“非换行空白”时保留空格；格式化换行不算空格。
                    val words = mutableListOf<LyricWord>()
                    var lineTranslation: String? = null
                    var lineTransliteration: String? = null
                    var previousSpanEnd = 0
                    for (spanMatch in spanTagRegex.findAll(content)) {
                        val betweenText = if (spanMatch.range.first > previousSpanEnd && previousSpanEnd <= content.length) {
                            content.substring(previousSpanEnd, spanMatch.range.first)
                        } else {
                            ""
                        }
                        val hasExternalSpaceBefore = isWhitespaceDelimiterWithoutNewline(betweenText)

                        val attrs = spanMatch.groupValues[1]
                        val role = extractAttribute(attrs, "ttm:role") ?: extractAttribute(attrs, "role")
                        val rawSpanText = decodeXmlEntities(
                            spanMatch.groupValues[2].replace(Regex("""<[^>]+>"""), "")
                        ).trim()

                        if (!role.isNullOrBlank()) {
                            when (role.lowercase()) {
                                "x-translation" -> {
                                    if (rawSpanText.isNotBlank()) lineTranslation = rawSpanText
                                    previousSpanEnd = spanMatch.range.last + 1
                                    continue
                                }

                                "x-roman", "x-romanization" -> {
                                    if (rawSpanText.isNotBlank()) lineTransliteration = rawSpanText
                                    previousSpanEnd = spanMatch.range.last + 1
                                    continue
                                }
                            }
                        }

                        val spanBeginStr = extractAttribute(attrs, "begin")
                        val spanEndStr = extractAttribute(attrs, "end")
                        if (spanBeginStr.isNullOrBlank() || spanEndStr.isNullOrBlank()) {
                            previousSpanEnd = spanMatch.range.last + 1
                            continue
                        }

                        val spanBegin = timeToMillis(spanBeginStr)
                        val spanEnd = timeToMillis(spanEndStr)
                        val rawText = rawSpanText
                        val normalizedWordText = normalizeWordText(rawText, hasExternalSpaceBefore, words)
                        if (normalizedWordText != null) {
                            words.add(LyricWord(word = normalizedWordText, startTime = spanBegin, endTime = spanEnd))
                        }
                        previousSpanEnd = spanMatch.range.last + 1
                    }
                    
                    // 生成整行文本：按逐词文本原样拼接（空格已在 normalizeWordText 中处理）
                    val fullText = if (words.isNotEmpty()) {
                        words.joinToString(separator = "") { it.word }.trimEnd()
                    } else {
                        val contentWithoutAux = content.replace(
                            Regex(
                                """<span\b[^>]*(?:ttm:role|role)\s*=\s*"x-(?:translation|roman(?:ization)?)"[^>]*>.*?</span>""",
                                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                            ),
                            ""
                        )
                        decodeXmlEntities(contentWithoutAux.replace(Regex("<[^>]+>"), "")).trim()
                    }
                    
                    if (fullText.isNotEmpty()) {
                        lines.add(
                            LyricLine(
                                startTime = beginMs,
                                endTime = endMs,
                                text = fullText,
                                translation = lineTranslation,
                                transliteration = lineTransliteration,
                                words = words
                            )
                        )
                        // 调试日志：检查逐词数据
                        if (words.isNotEmpty()) {
                            Timber.d("TTML Line: '$fullText' (${words.size} words)")
                            words.take(3).forEach { w ->
                                Timber.d("  Word: '${w.word}' [${w.startTime}-${w.endTime}]")
                            }
                        }
                    }
                }
                
                TTMLLyrics(
                    metadata = TTMLMetadata(
                        title = "Unknown",
                        artist = "Unknown"
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
         * 对齐 Unilyrics 的空格语义：
         * 1. 前导空白/外部空白归并到上一个词尾；
         * 2. 尾随空白保留在当前词尾；
         * 3. 纯空白 span 不作为词，仅作为空格语义。
         */
        private fun normalizeWordText(
            rawText: String,
            hasExternalSpaceBefore: Boolean,
            words: MutableList<LyricWord>
        ): String? {
            val hasLeadingSpace = rawText.firstOrNull()?.isWhitespace() == true
            val hasTrailingSpace = rawText.lastOrNull()?.isWhitespace() == true
            val trimmed = rawText.trim()

            if (hasExternalSpaceBefore || hasLeadingSpace) {
                appendSpaceToPreviousWord(words)
            }

            if (trimmed.isEmpty()) {
                if (hasTrailingSpace) {
                    appendSpaceToPreviousWord(words)
                }
                return null
            }

            return if (hasTrailingSpace) "$trimmed " else trimmed
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
                Timber.w("Error parsing time: $timeStr")
                0L
            }
        }
    }
}
