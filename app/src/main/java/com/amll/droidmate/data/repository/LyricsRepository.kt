package com.amll.droidmate.data.repository

import com.amll.droidmate.data.parser.mergeLyricLines
import com.amll.droidmate.data.parser.LyricsFormat
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
            
            // 遍历所有候选，找到最佳匹配（对齐 Unilyric 逻辑）
            var bestMatch: LyricsSearchResult? = null
            var bestScore = -1
            
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
                
                // 计算匹配度
                val matchEval = evaluateMatch(title, artist, songTitle, singerName)
                val matchType = MatchType.valueOf(matchEval.matchType)
                
                // 只考虑达到最低门槛的候选（VERY_LOW 以上）
                if (matchType.score >= MatchType.VERY_LOW.score && matchType.score > bestScore) {
                    bestScore = matchType.score
                    bestMatch = LyricsSearchResult(
                        provider = "qq",
                        songId = if (songIdNum != null) "$songMid::$songIdNum" else songMid,
                        title = songTitle,
                        artist = singerName,
                        confidence = matchEval.confidence,
                        matchType = matchEval.matchType
                    )
                }
            }
            
            if (bestMatch != null) {
                Timber.i("Found QQ Music best match: ${bestMatch.title} - ${bestMatch.artist} (confidence: ${bestMatch.confidence}, match=${bestMatch.matchType})")
            } else {
                Timber.d("No qualifying QQ Music match found for: $keyword")
            }
            
            bestMatch
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
                    put("format", "json")
                }
                putJsonObject("req_1") {
                    put("module", "music.musichallSong.PlayLyricInfo")
                    put("method", "GetPlayLyricInfo")
                    putJsonObject("param") {
                        put("songMid", fallbackMid)  // 使用小写 mid (匹配 Unilyric)
                        put("qrc", 1)                 // 显式请求 QRC 格式
                        put("trans", 1)               // 请求翻译
                        put("roma", 1)                // 请求罗马音
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
            
            // TODO: QRC 逐词歌词功能暂时禁用
            // 原因：QQ Music QRC 使用自定义 3DES (非标准DES) + Zlib 加密方式
            // 当前 3DES 实现存在 S-Box 索引计算问题，导致 Zlib 解压失败
            // 已确认问题根源：QQ Music 使用自定义的 S-Box 索引: ((a&0x20)|((a&0x1f)>>1)|((a&0x01)<<4))
            // 修复时需要参考 Unilyric 项目的完整实现
            // 
            // val qrcContent = responseJson["req_1"]
            //     ?.jsonObject?.get("data")
            //     ?.jsonObject?.get("qrc")
            //     ?.jsonPrimitive?.contentOrNull
            val qrcContent: String? = null

            val transContent = responseJson["req_1"]
                ?.jsonObject?.get("data")
                ?.jsonObject?.get("trans")
                ?.jsonPrimitive?.contentOrNull

            val romaContent = responseJson["req_1"]
                ?.jsonObject?.get("data")
                ?.jsonObject?.get("roma")
                ?.jsonPrimitive?.contentOrNull
            
            // 诊断日志
            Timber.d("QQ Music API response fields:")
            Timber.d("  lyric present: ${!lyricContent.isNullOrBlank()}, length: ${lyricContent?.length ?: 0}")
            // QRC 已禁用，不再检查
            // Timber.d("  qrc present: ${!qrcContent.isNullOrBlank()}, length: ${qrcContent?.length ?: 0}")
            Timber.d("  trans present: ${!transContent.isNullOrBlank()}, length: ${transContent?.length ?: 0}")
            Timber.d("  roma present: ${!romaContent.isNullOrBlank()}, length: ${romaContent?.length ?: 0}")
            
            if (!lyricContent.isNullOrBlank()) {
                Timber.d("LRC content preview (first 500 chars): ${lyricContent.take(500)}")
            }
            
            if (lyricContent.isNullOrBlank()) {
                Timber.w("No lyrics content from QQ Music for: $fallbackMid")
                return null
            }
            
            // 跳过 QRC，直接使用 LRC（逐句歌词）
            // TODO: 待 3DES 实现修复后可恢复 QRC 优先权
            Timber.i("Using LRC (line-by-line) format for QQ Music (QRC disabled)")
            val contentToUse = lyricContent!!
            
            // 检查LRC内容是否有效（防止"0"或其他无效标记）
            if (contentToUse.length < 5 || contentToUse == "0") {
                Timber.w("LRC content from QQ Music is invalid (likely empty): length=${contentToUse.length}")
                return null
            }
            
            // QQ音乐歌词需要解密：QRC = Hex+3DES+Zlib, LRC = Base64
            // 使用 decodeQqLyricPayload 自动检测格式并解密
            val decodedLyric = try {
                decodeQqLyricPayload(contentToUse)
            } catch (e: Exception) {
                Timber.e(e, "Failed to decode QQ Music lyrics. Content length: ${contentToUse.length}")
                Timber.d("First 200 chars of content: ${contentToUse.take(200)}")
                // 解密失败时尝试直接使用原始内容
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
                        val decoded = decodeQqLyricPayload(it)
                        com.amll.droidmate.data.parser.LrcParser.parse(decoded)
                    }.getOrNull()
                }

            val romanizationLines = romaContent
                ?.takeIf { it.isNotBlank() && it != "0" && it.length >= 5 }
                ?.let {
                    runCatching {
                        val decoded = decodeQqLyricPayload(it)
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
        
        // 对齐 Unilyric 逻辑：先检查是否为 Hex（需要 3DES 解密）
        // 如果是纯 Hex 字符串，大概率是加密的 QRC
        if (QqMusicQrcCrypto.looksLikeHex(raw)) {
            return try {
                QqMusicQrcCrypto.decryptQrcHex(raw)
            } catch (e: Exception) {
                android.util.Log.e("LyricsRepository", "Hex+3DES decryption failed, trying Base64 fallback", e)
                // 解密失败，尝试 Base64（可能判断错了）
                try {
                    String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
                } catch (e2: Exception) {
                    throw IllegalStateException("Both Hex+3DES and Base64 decoding failed", e)
                }
            }
        } else {
            // 不是 Hex，直接 Base64 解码（标准 LRC）
            return String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
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
            
            // 遍历所有候选，找到最佳匹配（对齐 Unilyric 逻辑）
            var bestMatch: LyricsSearchResult? = null
            var bestScore = -1
            
            for (songElement in songList) {
                val song = songElement.jsonObject
                val songId = song["id"]?.jsonPrimitive?.long?.toString() ?: continue
                val songTitle = song["name"]?.jsonPrimitive?.content ?: continue
                val singerName = song["artists"]?.jsonArray
                    ?.joinToString(", ") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }
                    ?.takeIf { it.isNotBlank() } ?: continue
                val albumName = song["album"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                val durationMs = song["duration"]?.jsonPrimitive?.longOrNull
                
                // 计算匹配度
                val matchEval = evaluateMatch(
                    searchTitle = title,
                    searchArtist = artist,
                    resultTitle = songTitle,
                    resultArtist = singerName,
                    resultAlbum = albumName,
                    resultDurationMs = durationMs
                )
                val matchType = MatchType.valueOf(matchEval.matchType)
                
                // 只考虑达到最低门槛的候选（VERY_LOW 以上）
                if (matchType.score >= MatchType.VERY_LOW.score && matchType.score > bestScore) {
                    bestScore = matchType.score
                    bestMatch = LyricsSearchResult(
                        provider = "netease",
                        songId = songId,
                        title = songTitle,
                        artist = singerName,
                        confidence = matchEval.confidence,
                        matchType = matchEval.matchType
                    )
                }
            }
            
            if (bestMatch != null) {
                Timber.i("Found Netease best match: ${bestMatch.title} - ${bestMatch.artist} (id: ${bestMatch.songId}, confidence: ${bestMatch.confidence}, match=${bestMatch.matchType})")
            } else {
                Timber.d("No qualifying Netease match found for: $keyword")
            }
            
            bestMatch
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
            Timber.d("Kugou search starting for keyword: $keyword")
            
            // Step 1: 从 Kugou 搜歌曲获得哈希值（对齐 Unilyric）
            val searchResponse = httpClient.get("http://mobilecdn.kugou.com/api/v3/search/song") {
                parameter("keyword", keyword)
                parameter("page", "1")
                parameter("pagesize", "20")
                parameter("showtype", "1")
            }
            
            if (!searchResponse.status.isSuccess()) {
                Timber.w("Kugou song search failed: ${searchResponse.status}")
                return null
            }
            
            val json = Json { ignoreUnknownKeys = true }
            val searchBody = searchResponse.body<String>()
            Timber.d("Kugou search API raw response (first 2000 chars): ${searchBody.take(2000)}")
            
            val searchJson = json.parseToJsonElement(searchBody).jsonObject
            val dataElement = searchJson["data"]?.jsonObject?.get("info")?.jsonArray
            
            Timber.d("Kugou parsed data element: ${dataElement?.size} items")
            
            if (dataElement.isNullOrEmpty()) {
                Timber.w("No Kugou song results found for: $keyword")
                return null
            }
            
            // Step 2: 遍历候选歌曲，计算匹配度（不依赖歌词搜索是否成功）
            // 歌词搜索在 getKugouLyrics 时会重新进行，这里只负责找到最匹配的歌曲
            var bestMatch: LyricsSearchResult? = null
            var bestScore = -1
            
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
                
                // Step 3: 计算匹配度
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
                
                // 只考虑达到最低门槛的候选（VERY_LOW 以上）
                if (matchType.score < MatchType.VERY_LOW.score) {
                    Timber.d("  -> Score ${matchType.score} below threshold ${MatchType.VERY_LOW.score}, skipping")
                } else if (matchType.score > bestScore) {
                    bestScore = matchType.score
                    bestMatch = LyricsSearchResult(
                        provider = "kugou",
                        songId = songHash,
                        title = songTitle,
                        artist = singerName,
                        confidence = matchEval.confidence,
                        matchType = matchEval.matchType
                    )
                    Timber.d("  -> New best match found!")
                } else {
                    Timber.d("  -> Score ${matchType.score} not better than best ${bestScore}, skipping")
                }
            }
            
            if (bestMatch != null) {
                Timber.i("Found Kugou best match: ${bestMatch.title} - ${bestMatch.artist} (hash: ${bestMatch.songId}, confidence: ${bestMatch.confidence})")
            } else {
                Timber.w("No qualifying Kugou match found for: $keyword (tried ${dataElement.size} candidates)")
            }
            
            bestMatch
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
            // 基于 Unilyric 的实现
            // https://lyrics.kugou.com 是官方歌词 API
            
            // Step 1: 搜索歌词候选（使用 hash）
            val searchResponse = httpClient.get("https://lyrics.kugou.com/search") {
                parameter("ver", "1")
                parameter("man", "yes")
                parameter("client", "pc")
                parameter("keyword", "")
                parameter("hash", hash)
            }
            
            if (!searchResponse.status.isSuccess()) {
                Timber.w("Kugou lyrics search failed: ${searchResponse.status}")
                return null
            }
            
            val json = Json { ignoreUnknownKeys = true }
            val searchBody = searchResponse.body<String>()
            Timber.d("Kugou lyrics search response (first 1000 chars): ${searchBody.take(1000)}")
            
            val searchJson = json.parseToJsonElement(searchBody).jsonObject
            val status = searchJson["status"]?.jsonPrimitive?.intOrNull ?: -1
            val candidates = searchJson["candidates"]?.jsonArray
            
            if (status != 200 || candidates.isNullOrEmpty()) {
                Timber.w("Kugou lyrics search failed with status=$status or no candidates")
                return null
            }
            
            // Step 2: 获取第一个候选（最相关的）的 id 和 accesskey
            val candidate = candidates[0].jsonObject
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
                Timber.w("No lyrics content in download response")
                return null
            }
            
            // Step 4: 解密歌词内容（根据 Unilyric: Base64 + XOR + Zlib）
            val decryptedLyrics = KugouDecrypter.decryptKrc(encryptedContent)
            
            if (decryptedLyrics.isNullOrBlank()) {
                Timber.w("Failed to decrypt Kugou lyrics")
                return null
            }
            
            Timber.d("Decrypted Kugou lyrics (first 500 chars): ${decryptedLyrics.take(500)}")
            
            // Step 5: 使用统一解析器处理 KRC/LRC 格式
            val ttml = com.amll.droidmate.data.parser.UnifiedLyricsParser.parse(
                content = decryptedLyrics,
                title = title ?: "Unknown",
                artist = artist ?: "Unknown"
            )
            
            Timber.i("Successfully fetched and decrypted Kugou lyrics for: $hash")
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
     * 使用并行搜索加速（对齐 Unilyric）
     */
    suspend fun searchLyrics(
        title: String,
        artist: String
    ): List<LyricsSearchResult> = coroutineScope {
        Timber.i("Starting multi-source parallel lyrics search for: $title - $artist")
        
        // 并行搜索所有平台（对齐 Unilyric 的优化）
        val qqMusicDeferred = async { 
            Timber.d("QQ Music search starting...")
            runCatching { searchQQMusic(title, artist) }.getOrNull().also { result ->
                Timber.d("QQ Music search completed: ${if (result != null) "found" else "null"}")
            }
        }
        val kugouDeferred = async { 
            Timber.d("Kugou search starting...")
            runCatching { searchKugou(title, artist) }.getOrNull().also { result ->
                Timber.d("Kugou search completed: ${if (result != null) "found (${result.title} - ${result.artist})" else "null"}")
            }
        }
        val neteaseDeferred = async { 
            Timber.d("Netease search starting...")
            runCatching { searchNetease(title, artist) }.getOrNull().also { result ->
                Timber.d("Netease search completed: ${if (result != null) "found" else "null"}")
            }
        }
        
        // 等待所有搜索完成
        val results = mutableListOf<LyricsSearchResult>()
        
        val qqResult = qqMusicDeferred.await()
        Timber.d("QQ Music await result: $qqResult")
        qqResult?.let { 
            results.add(it)
            Timber.d("Added QQ Music result with confidence: ${it.confidence}")
        }
        
        val kugouResult = kugouDeferred.await()
        Timber.d("Kugou await result: $kugouResult")
        kugouResult?.let { 
            results.add(it)
            Timber.d("Added Kugou result with confidence: ${it.confidence}")
        }
        
        val neteaseResult = neteaseDeferred.await()
        Timber.d("Netease await result: $neteaseResult")
        neteaseResult?.let {
            results.add(it)
            Timber.d("Added Netease result with confidence: ${it.confidence}")
        }

        // 统一按匹配度降序排序，去重(provider + songId)
        val sortedResults = results
            .sortedByDescending { it.confidence }
            .distinctBy { "${it.provider.lowercase()}:${it.songId}" }

        Timber.i("Search completed: Found ${results.size} total results, ${sortedResults.size} after dedup")
        Timber.i("Results before sort: ${results.map { "${it.provider.uppercase()}(${it.confidence})" }}")
        Timber.i("Results after sort: ${sortedResults.map { "${it.provider.uppercase()}(${it.confidence})" }}")
        
        sortedResults
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
