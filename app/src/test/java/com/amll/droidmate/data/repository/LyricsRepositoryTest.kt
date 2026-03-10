package com.amll.droidmate.data.repository

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LyricsRepositoryTest {
    @Test
    fun `getAMLL_TTMLLyrics defaults to ncm when no prefix`() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            val ttml = """<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:00.000\" end=\"00:00.500\">hi</p></div></body></tt>"""
            respond(ttml, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("text/xml")))
        }
        val client = HttpClient(engine)
        val repo = LyricsRepository(client)
        // call with bare id, should assume ncm
        repo.getAMLL_TTMLLyrics("12345")
        assertNotNull(seenUrl)
        assertTrue(seenUrl!!.contains("/ncm/12345"))
    }

    @Test
    fun `getAMLL_TTMLLyrics uses platform prefix when provided`() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            val ttml = """<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:00.000\" end=\"00:00.500\">hi</p></div></body></tt>"""
            respond(ttml, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("text/xml")))
        }
        val client = HttpClient(engine)
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
        val client = HttpClient(engine)
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
        val client = HttpClient(engine)
        val repo = LyricsRepository(client)
        val lyrics = repo.getAMLL_TTMLLyrics("qq:dhi8dhagd::3627319237")
        assertNotNull(lyrics)
        assertTrue(requested.any { it.contains("/qq/dhi8dhagd") })
        assertTrue(requested.any { it.contains("/qq/3627319237") })
    }
}
