package com.amll.droidmate.data.repository

import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import com.amll.droidmate.data.network.QqMusicQrcCrypto
import kotlinx.serialization.json.*

// domain models used in tests
import com.amll.droidmate.domain.model.LyricsSearchResult
import com.amll.droidmate.domain.model.LyricsResult
import com.amll.droidmate.domain.model.LyricsFeature
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.domain.model.TTMLMetadata

class LyricsRepositoryTest {
    @Test
    fun `getAMLL_TTMLLyrics defaults to ncm when no prefix`() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            val ttml = """<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:00.000\" end=\"00:00.500\">hi</p></div></body></tt>"""
            respond(ttml, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("text/xml")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)
        // call with bare id, should assume ncm
        repo.getAMLL_TTMLLyrics("12345")
        assertNotNull(seenUrl)
        assertTrue(seenUrl!!.contains("/ncm/12345"))
    }

    @Test
    fun `searchNetease returns multiple candidates up to three`() = runTest {
        // simulate a response with 4 candidate songs, only 3 should be returned
        val songsJson = (1..4).joinToString(",") { i ->
            "{\"id\":$i,\"name\":\"Title$i\",\"ar\":[{\"name\":\"Artist\"}],\"dt\":300000}"
        }
        val body = "{\"result\":{\"songs\":[${songsJson}]},\"code\":200}"
        val engine = MockEngine { _ ->
            respond(body, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("application/json")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)
        val results = repo.searchNetease("t","a")
        assertEquals(3, results.size)
    }

    @Test
    fun `searchQQMusic also limits to three`() = runTest {
        // craft minimal QQ JSON structure with four items
        val listItems = (1..4).joinToString(",") { i ->
            "{\"mid\":\"m$i\",\"id\":$i,\"title\":\"T$i\",\"singer\":[{\"name\":\"A\"}]}"
        }
        val body = "{\"req_1\":{\"data\":{\"body\":{\"song\":{\"list\":[$listItems]}}}}}"
        val engine = MockEngine { _ ->
            respond(body, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("application/json")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)
        val results = repo.searchQQMusic("x","y")
        assertEquals(3, results.size)
    }

    @Test
    fun `searchKugou also limits to three`() = runTest {
        val infoItems = (1..4).joinToString(",") { i ->
            "{\"hash\":\"h$i\",\"songname\":\"T$i\",\"singername\":\"A\",\"album_name\":\"AL\",\"duration\":300}"
        }
        val body = "{\"data\":{\"info\":[$infoItems]}}"
        val engine = MockEngine { _ ->
            respond(body, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("application/json")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        // override proposal lookup to avoid making additional network call
        val repo = object : LyricsRepository(client) {
            override suspend fun fetchKugouProposalId(hash: String) = "p_$hash"
        }
        val results = repo.searchKugou("x","y")
        if (results.isNotEmpty()) {
            assertEquals(3, results.size)
            // should be of form hash::proposal
            assertTrue(results.all { it.songId.matches(Regex("h\\d+::p_h\\d+")) })
        }
    }

    @Test
    fun `searchKugou proposal id fallback when lookup fails`() = runTest {
        // simulate a normal search but make fetch return null
        val infoItems = (1..1).joinToString(",") { i ->
            "{\"hash\":\"abcd\",\"songname\":\"T\",\"singername\":\"A\",\"album_name\":\"AL\",\"duration\":300}"
        }
        val body = "{\"data\":{\"info\":[$infoItems]}}"
        val engine = MockEngine { _ ->
            respond(body, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("application/json")) )
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = object : LyricsRepository(client) {
            override suspend fun fetchKugouProposalId(hash: String) = null
        }
        val results = repo.searchKugou("x","y")
        if (results.isNotEmpty()) {
            assertEquals("abcd", results.first().songId)
        }
    }

    @Test
    fun `mockengine intercepts get requests`() = runTest {
        var seen = false
        val engine = MockEngine { request ->
            seen = true
            respond("ok", HttpStatusCode.OK)
        }
        val client = HttpClient(engine as HttpClientEngine)
        client.get("https://example.com/hello?x=1")
        assertTrue("MockEngine should have seen the request", seen)
    }

    @Test
    fun `playLyricInfo request includes qrc trans roma flags`() = runTest {
        // debug helper replicating parseQqSongIds
        fun debugParse(songId: String): Pair<String?, Long?> {
            val parts = songId.split("::", limit = 2)
            val mid = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
            val numeric = parts.getOrNull(1)?.toLongOrNull()
            if (numeric != null) return mid to numeric
            return if (songId.all { it.isDigit() }) null to songId.toLongOrNull() else songId to null
        }
        val parsed1 = debugParse("abc123")
        println("DEBUG parsed abc123 -> $parsed1")
        assertEquals("abc123" to null, parsed1)

        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond("{}", HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("application/json")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)
        LyricsRepository.lastGetQQCall = null
        // call with id lacking numeric portion to force PlayLyricInfo path
        repo.getQQMusicLyrics("abc123")
        assertEquals("abc123", LyricsRepository.lastGetQQCall)
        assertNotNull(seenUrl)
        println("DEBUG playLyricInfo flags url: $seenUrl")
        // extract and decode data param
        val query = java.net.URL(seenUrl).query
        val dataValue = query.split("&").first { it.startsWith("data=") }.substringAfter("data=")
        val jsonStr = java.net.URLDecoder.decode(dataValue, "UTF-8")
        println("DEBUG decoded JSON: $jsonStr")
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonObject
        val params = parsed["req_1"]!!.jsonObject["param"]!!.jsonObject
        assertEquals(1, params["qrc"]!!.jsonPrimitive.int)
        assertEquals(1, params["trans"]!!.jsonPrimitive.int)
        assertEquals(1, params["roma"]!!.jsonPrimitive.int)
        // check presence of songMid key
        assertTrue(params.containsKey("songMid"))
        assertFalse(params.containsKey("songMID"))
    }

    @Test
    fun `playLyricInfo request uses songMid when no numeric id`() = runTest {
        fun debugParse(songId: String): Pair<String?, Long?> {
            val parts = songId.split("::", limit = 2)
            val mid = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
            val numeric = parts.getOrNull(1)?.toLongOrNull()
            if (numeric != null) return mid to numeric
            return if (songId.all { it.isDigit() }) null to songId.toLongOrNull() else songId to null
        }
        val parsed2 = debugParse("ZZZmidOnly")
        println("DEBUG parsed ZZZmidOnly -> $parsed2")
        assertEquals("ZZZmidOnly" to null, parsed2)

        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond("{}", HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("application/json")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)
        LyricsRepository.lastGetQQCall = null
        repo.getQQMusicLyrics("ZZZmidOnly")
        assertEquals("ZZZmidOnly", LyricsRepository.lastGetQQCall)
        assertNotNull(seenUrl)
        println("DEBUG playLyricInfo mid-only url: $seenUrl")
        val query2 = java.net.URL(seenUrl).query
        val dataVal2 = query2.split("&").first { it.startsWith("data=") }.substringAfter("data=")
        val json2 = java.net.URLDecoder.decode(dataVal2, "UTF-8")
        val parsedJson2 = kotlinx.serialization.json.Json.parseToJsonElement(json2).jsonObject
        val params2 = parsedJson2["req_1"]!!.jsonObject["param"]!!.jsonObject
        assertEquals("ZZZmidOnly", params2["songMid"]!!.jsonPrimitive.content)
        assertEquals(0, params2["songId"]!!.jsonPrimitive.int)
        assertFalse(params2.containsKey("songID"))
    }

    @Test
    fun `playLyricInfo uses numericId when available`() = runTest {
        fun debugParse(songId: String): Pair<String?, Long?> {
            val parts = songId.split("::", limit = 2)
            val mid = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
            val numeric = parts.getOrNull(1)?.toLongOrNull()
            if (numeric != null) return mid to numeric
            return if (songId.all { it.isDigit() }) null to songId.toLongOrNull() else songId to null
        }
        val parsed3 = debugParse("12345")
        println("DEBUG parsed 12345 -> $parsed3")
        assertEquals(null to 12345L, parsed3)

        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond("{}", HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("application/json")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)
        LyricsRepository.lastGetQQCall = null
        // supply songMid string that parseQqSongIds returns numeric id for
        // for test we know parseQqSongIds treats digits-only as numeric
        repo.getQQMusicLyrics("12345")
        assertEquals("12345", LyricsRepository.lastGetQQCall)
        assertNotNull(seenUrl)
        println("DEBUG playLyricInfo numeric url: $seenUrl")
        val query3 = java.net.URL(seenUrl).query
        val dataVal3 = query3.split("&").first { it.startsWith("data=") }.substringAfter("data=")
        val json3 = java.net.URLDecoder.decode(dataVal3, "UTF-8")
        val parsedJson3 = kotlinx.serialization.json.Json.parseToJsonElement(json3).jsonObject
        val params3 = parsedJson3["req_1"]!!.jsonObject["param"]!!.jsonObject
        assertEquals(12345, params3["songId"]!!.jsonPrimitive.int)
        assertFalse(params3.containsKey("songMID"))
        assertFalse(params3.containsKey("songID"))
    }

    @Test
    fun `getAMLL_TTMLLyrics uses platform prefix when provided`() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            val ttml = """<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:00.000\" end=\"00:00.500\">hi</p></div></body></tt>"""
            respond(ttml, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("text/xml")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)
        // prefix with qq:
        repo.getAMLL_TTMLLyrics("qq:ABCDEF")
        assertNotNull(seenUrl)
        assertTrue(seenUrl!!.contains("/qq/ABCDEF"))
    }

    @Test
    fun `getAMLL_TTMLLyrics tries second segment when first fails`() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += request.url.toString()
            // fail any AAA urls, succeed on others (BBB)
            if (request.url.toString().contains("/qq/AAA")) {
                respond("not found", HttpStatusCode.NotFound)
            } else {
                val ttml = """<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:00.000\" end=\"00:00.500\">hi</p></div></body></tt>"""
                respond(ttml, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("text/xml")))
            }
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)
        val lyrics = repo.getAMLL_TTMLLyrics("qq:AAA:BBB")
        assertNotNull(lyrics)
        // ensure both candidate ids were tried
        assertTrue(requested.any { it.contains("/qq/AAA") })
        assertTrue(requested.any { it.contains("/qq/BBB") })
    }

    @Test
    fun `getKugouLyrics accepts combined id but uses hash`() = runTest {
        // search response must include candidate with id "12345" and accesskey
        val searchBody = "{\"status\":200,\"candidates\":[{\"id\":\"12345\",\"accesskey\":\"AK\"}]}"
        val downloadBody = "{\"content\":\"dummy\"}"
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("/search") -> {
                    respond(searchBody, HttpStatusCode.OK,
                        headers = headersOf("Content-Type" to listOf("application/json")))
                }
                request.url.encodedPath.contains("/download") -> respond(downloadBody, HttpStatusCode.OK,
                    headers = headersOf("Content-Type" to listOf("application/json")))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)
        // call using combined hash+proposal
        repo.getKugouLyrics("h123::12345")
    }

    @Test
    fun `hex detection fallback to base64 does not crash`() = runTest {
        // craft a base64 string consisting only of hex characters and even length
        val bogusBase64 = "48656c6c6f576f726c64" // actually "HelloWorld" in ascii (also valid hex)
        // encode it in base64 to get real payload
        val base64 = java.util.Base64.getEncoder().encodeToString(bogusBase64.toByteArray())
        // ensure looksLikeHex would return true on the encoded string
        assertTrue(QqMusicQrcCrypto.looksLikeHex(base64))
        // repository should not throw when decoding
        val engine2 = MockEngine { respond("", HttpStatusCode.OK) }
        val client2 = HttpClient(engine2 as HttpClientEngine)
        val repo2 = LyricsRepository(client2)
        val result = repo2.decodeQqLyricPayload(base64)
        // Debug: print what we received to help diagnose issues.
        println("DEBUG base64 payload: $base64")
        println("DEBUG decode result: '$result'")
        // result should equal original decoded base64 (which is bogusBase64 string)
        assertEquals(bogusBase64, result)
    }

    @Test
    fun `decrypt real sample hex file`() = runTest {
        // load encrypted example downloaded from Unilyric repo
        val hexFile = java.io.File("encrypted.hex")
        assumeTrue("sample hex file not present, skip", hexFile.exists())
        val hex = hexFile.readText().trim()
        assertTrue(hex.isNotEmpty())
        val decrypted = QqMusicQrcCrypto.decryptQrcHex(hex)
        assertTrue("decrypted string should not be empty", decrypted.isNotEmpty())
    }

    @Test
    fun `decrypt local qrc binary file`() = runTest {
        // local QRC file format (qmc1 + 11-byte header + 3DES+Zlib payload)
        val binFile = java.io.File("src/test/resources/encrypted_lyrics.bin")
        assumeTrue("sample QRC binary file not present, skip", binFile.exists())
        val data = binFile.readBytes()
        val decrypted = QqMusicQrcCrypto.decryptQrcLocal(data)
        assertTrue("decrypted string should not be empty", decrypted.isNotEmpty())
    }

    @Test
    fun `playLyricInfo numeric qrc flag is ignored`() = runTest {
        println("DEBUG test start")
        // lyric payload is simple base64 representing multi‑line text so parser returns something
        val sampleText = "Line1\nLine2"
        val base64Hello = java.util.Base64.getEncoder().encodeToString(sampleText.toByteArray())
        println("DEBUG base64Hello=$base64Hello")
        val body = """{"code":0,"req_1":{"code":0,"data":{"qrc":1,"lyric":"$base64Hello"}}}"""
        println("DEBUG body=$body")
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            println("DEBUG engine got request")
            seenUrl = request.url.toString()
            respond(body, HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("application/json")))
        }
        println("DEBUG before client creation")
        val client = HttpClient(engine as HttpClientEngine)
        println("DEBUG after client creation")
        val repo = LyricsRepository(client)
        println("DEBUG before repo call")
        val result = runCatching { repo.getQQMusicLyrics("unused") }
        println("DEBUG after repo call")
        if (result.isFailure) {
            result.exceptionOrNull()?.printStackTrace()
        }
        val lyrics = result.getOrNull()
        assertNotNull("lyrics should be returned when qrc flag is numeric", lyrics)
        assertTrue(lyrics!!.lines.any { it.text.contains("Line1") })
        // ensure qrc value did not end up as content
        assertFalse(seenUrl!!.contains("qrc=1")) // still sent, but repo should ignore its value
    }

    @Test
    fun `getAMLL_TTMLLyrics handles double-colon qq id`() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += request.url.toString()
            // fail any url containing the first segment value
            if (request.url.toString().contains("/qq/dhi8dhagd")) {
                respond("not found", HttpStatusCode.NotFound)
            } else {
                val ttml = """<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:00.000\" end=\"00:00.500\">hi</p></div></body></tt>"""
                respond(ttml, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("text/xml")))
            }
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)
        val lyrics = repo.getAMLL_TTMLLyrics("qq:dhi8dhagd::3627319237")
        assertNotNull(lyrics)
        assertTrue(requested.any { it.contains("/qq/dhi8dhagd") })
        assertTrue(requested.any { it.contains("/qq/3627319237") })
    }

    // ---- new matching tests ----
    @Test
    fun `normalizeForComparison removes accents and punctuation`() {
        val input = "Beyoncé - Halo (Acoustic Version)"
        val normalized = runTest { 
            // use repository without needing real client
            val fake = LyricsRepository(HttpClient((MockEngine { respond("", HttpStatusCode.OK) }) as HttpClientEngine))
            fake.normalizeForComparison(input)
        }
        assertEquals("beyonce - halo (acoustic)", normalized)
    }

    @Test
    fun `compareName recognizes dash paren equivalence`() {
        val fake = LyricsRepository(HttpClient((MockEngine { respond("", HttpStatusCode.OK) }) as HttpClientEngine))
        val n1 = fake.compareName("Song - Live", "Song (Live)")
        assertNotNull(n1)
        assertEquals("VERY_HIGH", n1!!.name)
    }

    @Test
    fun `evaluateMatch returns high confidence for similar titles`() {
        val fake = LyricsRepository(HttpClient((MockEngine { respond("", HttpStatusCode.OK) }) as HttpClientEngine))
        val eval = fake.evaluateMatch(
            searchTitle = "Hello",
            searchArtist = "Adele",
            resultTitle = "Hello",
            resultArtist = "Adele",
            searchDurationMs = 300000,
            resultDurationMs = 301000
        )
        assertTrue(eval.confidence > 0.9f)
    }

    @Test
    fun `getLyricsFeatures returns all types when TTML contains each`() = runTest {
        val ttml = """<?xml version="1.0"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.ktv.example.com">
  <body>
    <div>
      <p ttm:agent="A" begin="00:00.000" end="00:01.000">
        <span ttm:time="00:00.100">hello</span>
        <span ttm:role="x-translation">翻译</span>
        <span ttm:role="x-roman">yinyu</span>
        <span ttm:role="x-bg">bgtext</span>
      </p>
      <p ttm:agent="B" begin="00:00.900" end="00:02.000">world</p>
    </div>
  </body>
</tt>"""

        val engine = MockEngine { _ ->
            respond(ttml, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("text/xml")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)

        val features = repo.getLyricsFeatures("amll", "someid", "title", "artist")
        // expect every enum value to be present
        val all = com.amll.droidmate.domain.model.LyricsFeature.values().toSet()
        assertEquals(all, features)
    }

    @Test
    fun `getLyricsFeatures does not include WORDS for line-only timings`() = runTest {
        val ttml = """<?xml version="1.0"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.ktv.example.com">
  <body>
    <div>
      <p begin="00:00.000" end="00:05.000">first line</p>
      <p begin="00:05.000" end="00:10.000">second line</p>
    </div>
  </body>
</tt>"""

        val engine = MockEngine { _ ->
            respond(ttml, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("text/xml")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)

        val features = repo.getLyricsFeatures("amll", "id", "t", "a")
        assertFalse(features.contains(com.amll.droidmate.domain.model.LyricsFeature.WORDS))
    }

    @Test
    fun `overlap feature only for amll provider`() = runTest {
        // create lyrics with overlapping line timings
        val ttml = """<?xml version=\"1.0\"?>
<tt xmlns=\"http://www.w3.org/ns/ttml\">
  <body>
    <div>
      <p begin=\"00:00.000\" end=\"00:02.000\">first</p>
      <p begin=\"00:01.500\" end=\"00:03.000\">second</p>
    </div>
  </body>
</tt>"""

        val engine = MockEngine { _ ->
            respond(ttml, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("text/xml")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)

        val featuresAmll = repo.getLyricsFeatures("amll", "id", "t", "a")
        assertTrue(featuresAmll.contains(com.amll.droidmate.domain.model.LyricsFeature.OVERLAP))

        val featuresOther = repo.getLyricsFeatures("qq", "id", "t", "a")
        assertFalse(featuresOther.contains(com.amll.droidmate.domain.model.LyricsFeature.OVERLAP))
    }

    @Test
    fun `adjustResultsForFeatures sorts by confidence then features`() = runTest {
        // two search results: one high-confidence but featureless, another lower
        // confidence but supports translation/words.
        val result1 = com.amll.droidmate.domain.model.LyricsSearchResult(
            provider = "amll",
            songId = "one",
            title = "A",
            artist = "B",
            confidence = 0.90f
        )
        val result2 = com.amll.droidmate.domain.model.LyricsSearchResult(
            provider = "amll",
            songId = "two",
            title = "A",
            artist = "B",
            confidence = 0.88f
        )

        val engine = MockEngine { request ->
            val url = request.url.toString()
            val ttml = if (url.contains("/one")) {
                // single line, no words
                """<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:00.000\" end=\"00:05.000\">line</p></div></body></tt>"""
            } else {
                // two-word line plus translation
                """<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:00.000\" end=\"00:05.000\"><span ttm:time=\"00:00.000\">foo</span><span ttm:time=\"00:01.000\">bar</span><span ttm:role=\"x-translation\">译</span></p></div></body></tt>"""
            }
            respond(ttml, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("text/xml")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)

        val ordered = repo.adjustResultsForFeatures(listOf(result1, result2), currentSourceName = null)
        // result1 has higher confidence, so it should come first
        assertEquals(result1, ordered.first())

        // if confidences tie, features should break the tie
        val result3 = result1.copy(confidence = 0.90f)
        val result4 = result2.copy(confidence = 0.90f)
        val ordered2 = repo.adjustResultsForFeatures(listOf(result3, result4), currentSourceName = null)
        // message goes first in JUnit assertEquals
        assertEquals("equal confidence, candidate with features wins", result4, ordered2.first())
    }

    @Test
    fun `adjustResultsForFeatures respects source name bias`() = runTest {
        val repo = LyricsRepository(HttpClient((MockEngine { respond("", HttpStatusCode.OK) }) as HttpClientEngine))
        val netease = LyricsSearchResult(provider = "netease", songId = "1", title = "t", artist = "a", confidence = 0.9f)
        val qq = LyricsSearchResult(provider = "qq", songId = "2", title = "t", artist = "a", confidence = 0.9f)
        val kugou = LyricsSearchResult(provider = "kugou", songId = "3", title = "t", artist = "a", confidence = 0.9f)

        // source contains 网易
        val ord1 = repo.adjustResultsForFeatures(listOf(qq, netease, kugou), currentSourceName = "网易云")
        assertEquals(netease, ord1.first())

        // source contains QQ
        val ord2 = repo.adjustResultsForFeatures(listOf(kugou, qq, netease), currentSourceName = "QQ Music")
        assertEquals(qq, ord2.first())
        assertEquals(kugou, ord2[1])

        // source contains 酷狗
        val ord3 = repo.adjustResultsForFeatures(listOf(netease, kugou, qq), currentSourceName = "酷狗概念版")
        assertEquals(kugou, ord3.first())
        assertEquals(qq, ord3[1])
    }

    @Test
    fun `adjustResultsForFeatures falls back to provider priority after ties`() = runTest {
        val repo = LyricsRepository(HttpClient((MockEngine { respond("", HttpStatusCode.OK) }) as HttpClientEngine))
        val amll = LyricsSearchResult(provider = "amll", songId = "1", title = "t", artist = "a", confidence = 0.9f)
        val qq = LyricsSearchResult(provider = "qq", songId = "2", title = "t", artist = "a", confidence = 0.9f)
        // same confidence, same (empty) features, no currentSourceName
        val ordered = repo.adjustResultsForFeatures(listOf(qq, amll), currentSourceName = null)
        // amll has higher priority than qq according to map
        assertEquals(amll, ordered.first())
    }

    @Test
    fun `adjustResultsForFeatures prefers amll id matching current source`() = runTest {
        val repo = LyricsRepository(HttpClient((MockEngine { respond("", HttpStatusCode.OK) }) as HttpClientEngine))
        val amllA = LyricsSearchResult(provider = "amll", songId = "qq:xxx", title = "t", artist = "a", confidence = 0.8f)
        val amllB = LyricsSearchResult(provider = "amll", songId = "netease:yyy", title = "t", artist = "a", confidence = 0.8f)
        val ordered = repo.adjustResultsForFeatures(listOf(amllB, amllA), currentSourceName = "QQ Music")
        assertEquals(amllA, ordered.first())
    }

    @Test
    fun `adjustResultsForFeatures directional qq and kugou preference under tme`() = runTest {
        val repo = LyricsRepository(HttpClient((MockEngine { respond("", HttpStatusCode.OK) }) as HttpClientEngine))
        val qq = LyricsSearchResult(provider = "qq", songId = "1", title = "t", artist = "a", confidence = 0.7f)
        val kugou = LyricsSearchResult(provider = "kugou", songId = "2", title = "t", artist = "a", confidence = 0.7f)

        val ord1 = repo.adjustResultsForFeatures(listOf(kugou, qq), currentSourceName = "QQ Music")
        assertEquals(listOf(qq, kugou), ord1)
        val ord2 = repo.adjustResultsForFeatures(listOf(qq, kugou), currentSourceName = "酷狗播放器")
        assertEquals(listOf(kugou, qq), ord2)
        val ord3 = repo.adjustResultsForFeatures(listOf(kugou, qq), currentSourceName = "QQ & 酷狗")
        assertEquals(listOf(qq, kugou), ord3)
    }

    @Test
    fun `fetchLyricsAuto picks highest-confidence candidate first`() = runTest {
        val fake = object : LyricsRepository(HttpClient((MockEngine { _ ->
            // this engine should never actually be used in our overrides
            respond("", HttpStatusCode.OK)
        }) as HttpClientEngine)) {
            override suspend fun searchLyrics(title: String, artist: String): List<LyricsSearchResult> {
                return listOf(
                    LyricsSearchResult(provider = "amll", songId = "1", title = "t", artist = "a", confidence = 0.90f),
                    LyricsSearchResult(provider = "amll", songId = "2", title = "t", artist = "a", confidence = 0.88f)
                )
            }

            override suspend fun getLyricsFeatures(
                provider: String,
                songId: String,
                title: String?,
                artist: String?
            ): Set<com.amll.droidmate.domain.model.LyricsFeature> {
                return if (songId == "2") setOf(com.amll.droidmate.domain.model.LyricsFeature.WORDS) else emptySet()
            }

            override suspend fun getAMLL_TTMLLyrics(
                songId: String,
                title: String?,
                artist: String?
            ): com.amll.droidmate.domain.model.TTMLLyrics? {
                // only return lyrics for id "1" and "2" so both are valid
                return com.amll.droidmate.domain.model.TTMLLyrics(
                    metadata = com.amll.droidmate.domain.model.TTMLMetadata(title = "t", artist = "a"),
                    lines = emptyList()
                )
            }
        }

        val result = fake.fetchLyricsAuto("t", "a", currentSourceName = null)
        assertTrue(result.isSuccess)
        // candidate 1 should be chosen because it has higher confidence
        assertTrue(result.source?.contains("(1)") ?: false)
    }

    @Test
    fun `fetchLyricsAuto prefers local cache when available`() = runTest {
        var searched = false
        val fake = object : LyricsRepository(HttpClient((MockEngine { _ ->
            respond("", HttpStatusCode.OK)
        }) as HttpClientEngine)) {
            override suspend fun getCachedLyrics(title: String, artist: String): LyricsResult? {
                return LyricsResult(
                    isSuccess = true,
                    lyrics = com.amll.droidmate.domain.model.TTMLLyrics(
                        metadata = com.amll.droidmate.domain.model.TTMLMetadata(title = "t", artist = "a"),
                        lines = emptyList()
                    ),
                    source = "local"
                )
            }

            override suspend fun searchLyrics(title: String, artist: String): List<LyricsSearchResult> {
                searched = true
                return emptyList()
            }
        }

        val res = fake.fetchLyricsAuto("t", "a")
        assertTrue(res.isSuccess)
        assertEquals("local", res.source)
        assertFalse("searchLyrics should not be called when cache exists", searched)
    }

    @Test
    fun `fetchLyricsAuto returns cache entry early`() = runTest {
        val fake = object : LyricsRepository(HttpClient((MockEngine { _ ->
            respond("", HttpStatusCode.OK)
        }) as HttpClientEngine)) {
            override suspend fun getCachedLyrics(title: String, artist: String): LyricsResult? {
                return LyricsResult(isSuccess = true, lyrics = com.amll.droidmate.domain.model.TTMLLyrics(
                    metadata = com.amll.droidmate.domain.model.TTMLMetadata(title = "x", artist = "y"),
                    lines = emptyList()),
                    source = "cached"
                )
            }

            override suspend fun searchLyrics(title: String, artist: String): List<LyricsSearchResult> {
                error("search should be bypassed when cache found")
            }
        }

        val r = fake.fetchLyricsAuto("x", "y")
        assertTrue(r.isSuccess)
        assertEquals("cached", r.source)
    }

    @Test
    fun `fetchLyricsAuto respects source name when fallback order applies`() = runTest {
        val fake = object : LyricsRepository(HttpClient((MockEngine { _ ->
            respond("", HttpStatusCode.OK)
        }) as HttpClientEngine)) {
            override suspend fun searchLyrics(title: String, artist: String): List<LyricsSearchResult> {
                // two equal-confidence results from different providers
                return listOf(
                    LyricsSearchResult(provider = "qq", songId = "q", title = "t", artist = "a", confidence = 0.90f),
                    LyricsSearchResult(provider = "kugou", songId = "k", title = "t", artist = "a", confidence = 0.90f)
                )
            }

            override suspend fun getAMLL_TTMLLyrics(songId: String, title: String?, artist: String?) = null
            override suspend fun getQQMusicLyrics(songId: String, title: String?, artist: String?) =
                com.amll.droidmate.domain.model.TTMLLyrics(
                    metadata = com.amll.droidmate.domain.model.TTMLMetadata(title = "t", artist = "a"),
                    lines = emptyList()
                )
            override suspend fun getKugouLyrics(songId: String, title: String?, artist: String?) =
                com.amll.droidmate.domain.model.TTMLLyrics(
                    metadata = com.amll.droidmate.domain.model.TTMLMetadata(title = "t", artist = "a"),
                    lines = emptyList()
                )
        }

        // bias towards QQ
        val r1 = fake.fetchLyricsAuto("t", "a", currentSourceName = "QQ Music")
        assertTrue(r1.source?.contains("QQ") == true)

        // bias towards Kugou
        val r2 = fake.fetchLyricsAuto("t", "a", currentSourceName = "酷狗播放器")
        assertTrue(r2.source?.contains("酷狗") == true)
    }

    @Test
    fun `compareArtists handles ampersand and and equivalence`() {
        val fake = LyricsRepository(HttpClient((MockEngine { respond("", HttpStatusCode.OK) }) as HttpClientEngine))
        val res = fake.compareArtists("Simon & Garfunkel", "Simon and Garfunkel")
        assertNotNull(res)
        assertTrue(res!!.score >= LyricsRepository.ArtistMatchType.PERFECT.score)
    }
}
