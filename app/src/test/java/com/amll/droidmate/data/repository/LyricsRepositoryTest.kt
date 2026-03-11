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
        val repo = LyricsRepository(client)
        val results = repo.searchKugou("x","y")
        assertEquals(3, results.size)
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
            // first URL -> 404, second -> success
            if (requested.size == 1) {
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
    fun `getAMLL_TTMLLyrics handles double-colon qq id`() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += request.url.toString()
            // simulate a hit on second candidate
            if (requested.size == 1) {
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
        assertTrue(result.source?.contains("(1)") == true)
    }

    @Test
    fun `fetchLyricsAuto prefers local cache when available`() = runTest {
        val fake = object : LyricsRepository(HttpClient((MockEngine { _ ->
            respond("", HttpStatusCode.OK)
        }) as HttpClientEngine)) {
            override suspend fun searchLyrics(title: String, artist: String): List<LyricsSearchResult> {
                // local candidate has lower confidence than network one
                return listOf(
                    LyricsSearchResult(provider = "netease", songId = "n", title = "t", artist = "a", confidence = 0.99f),
                    LyricsSearchResult(provider = "amll", songId = "a", title = "t", artist = "a", confidence = 0.50f)
                )
            }

            override suspend fun getAMLL_TTMLLyrics(
                songId: String,
                title: String?,
                artist: String?
            ) = com.amll.droidmate.domain.model.TTMLLyrics(
                metadata = com.amll.droidmate.domain.model.TTMLMetadata(title = "t", artist = "a"),
                lines = emptyList()
            )

            override suspend fun getNeteaseLyrics(songId: String, title: String?, artist: String?) =
                com.amll.droidmate.domain.model.TTMLLyrics(
                    metadata = com.amll.droidmate.domain.model.TTMLMetadata(title = "t", artist = "a"),
                    lines = emptyList()
                )
        }

        val res = fake.fetchLyricsAuto("t", "a")
        assertTrue(res.isSuccess)
        assertTrue(res.source?.contains("AMLL") == true, "expected local cache to win even with lower confidence")
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
