package com.amll.droidmate.ui.viewmodel

import android.app.Application
import com.amll.droidmate.data.parser.LyricsFormat
import com.amll.droidmate.data.repository.LyricsCacheRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

// network/util imports used by some tests
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.headersOf
import com.amll.droidmate.data.repository.LyricsRepository
import kotlinx.coroutines.test.advanceUntilIdle

/**
 * Basic unit tests for [CustomLyricsViewModel].  Since the implementation
 * runs its logic on a coroutine scope we simply wait a short time after
 * calling to make sure the state flow has been updated.
 */
class CustomLyricsViewModelTest {
    @Before
    fun setUp() {
        // no-op
    }

    @After
    fun tearDown() {
        // no-op
    }

    @Test
    fun `sourceFromInput returns manual for plain text`() = runTest {
        val source = CustomLyricsViewModel.sourceFromInput("just some lyrics")
        assertEquals("manual", source)
    }

    @Test
    fun `sourceFromInput returns extension when detected`() = runTest {
        val ttml = """<?xml version=\"1.0\"?><tt></tt>"""
        val source = CustomLyricsViewModel.sourceFromInput(ttml)
        assertEquals(LyricsFormat.TTML.extension, source)

        val sampleLrc = """[00:00.00]line"""
        val source2 = CustomLyricsViewModel.sourceFromInput(sampleLrc)
        assertEquals(LyricsFormat.LRC.extension, source2)
    }

    @Test
    fun `autoSourceForCandidate includes provider title artist and id`() {
        val out = CustomLyricsViewModel.autoSourceForCandidate(
            provider = "kugou",
            title = "歌曲名",
            artist = "歌手名",
            id = "34824792348"
        )
        assertEquals("酷狗音乐：歌曲名 - 歌手名(34824792348)", out)

        // check another provider to ensure template is generic
        val out2 = CustomLyricsViewModel.autoSourceForCandidate(
            provider = "qq",
            title = "歌2",
            artist = "歌手2",
            id = "999"
        )
        assertEquals("QQ音乐：歌2 - 歌手2(999)", out2)
    }

    @Test
    fun `candidates sort by features when confidence close`() = runTest {
        val viewModel = CustomLyricsViewModel(Application())
        val c1 = CustomLyricsCandidate(
            provider = "qq",
            songId = "1",
            title = "T",
            artist = "A",
            confidence = 0.90f,
            matchType = "",
            displayName = "",
            features = emptySet()
        )
        val c2 = CustomLyricsCandidate(
            provider = "qq",
            songId = "2",
            title = "T",
            artist = "A",
            confidence = 0.88f,
            matchType = "",
            displayName = "",
            features = setOf(com.amll.droidmate.domain.model.LyricsFeature.TRANSLATION)
        )
        // when inserted separately comparator will reorder
        runTest {
            viewModel.searchCandidates("", "") // no-op but ensures flows initialized
        }
        // directly test comparator
        val sorted = listOf(c1, c2).sortedWith(viewModel.candidateComparator)
        assertEquals(c2, sorted.first())
    }

    @Test
    fun `cache candidate always ranks first`() = runTest {
        val vm = CustomLyricsViewModel(Application())
        val other = CustomLyricsCandidate("qq", "1", "T", "A", 1.0f, "", "", emptySet())
        val cache = CustomLyricsCandidate("cache", "x", "T", "A", 0.0f, "", "", emptySet())
        val list = listOf(other, cache)
        assertEquals(cache, list.sortedWith(vm.candidateComparator).first())
    }

    @Test
    fun `current source influences order after confidence and features`() = runTest {
        val vm = CustomLyricsViewModel(Application())
        vm.updateCurrentSource("网易云音乐")
        val a = CustomLyricsCandidate("qq", "1", "T", "A", 1f, "", "", emptySet())
        val b = CustomLyricsCandidate("netease", "2", "T", "A", 1f, "", "", emptySet())
        val sorted = listOf(a, b).sortedWith(vm.candidateComparator)
        assertEquals(b, sorted.first())
    }

    @Test
    fun `amll id prefix matching current source wins tie`() = runTest {
        val vm = CustomLyricsViewModel(Application())
        vm.updateCurrentSource("QQ Music")
        val amllMatch = CustomLyricsCandidate("amll", "qq:abc", "T", "A", 0.5f, "", "", emptySet())
        val other = CustomLyricsCandidate("amll", "netease:123", "T", "A", 0.5f, "", "", emptySet())
        val sorted = listOf(other, amllMatch).sortedWith(vm.candidateComparator)
        assertEquals(amllMatch, sorted.first())
    }

    @Test
    fun `qq vs kugou preference follows source when under tme`() = runTest {
        val vm = CustomLyricsViewModel(Application())
        val qq = CustomLyricsCandidate("qq", "1", "T", "A", 0.5f, "", "", emptySet())
        val kugou = CustomLyricsCandidate("kugou", "2", "T", "A", 0.5f, "", "", emptySet())

        // current source QQ -> qq should win
        vm.updateCurrentSource("QQ Music")
        val sorted1 = listOf(kugou, qq).sortedWith(vm.candidateComparator)
        assertEquals(qq, sorted1.first())

        // current source Kugou -> kugou should win
        vm.updateCurrentSource("酷狗播放器")
        val sorted2 = listOf(qq, kugou).sortedWith(vm.candidateComparator)
        assertEquals(kugou, sorted2.first())

        // if source mentions both, default back to qq
        vm.updateCurrentSource("QQ & 酷狗")
        val sorted3 = listOf(kugou, qq).sortedWith(vm.candidateComparator)
        assertEquals(qq, sorted3.first())
    }

    @Test
    fun `provider priority used when all else equal`() = runTest {
        val vm = CustomLyricsViewModel(Application())
        val p1 = CustomLyricsCandidate("qq", "1", "T", "A", 0.5f, "", "", emptySet())
        val p2 = CustomLyricsCandidate("amll", "2", "T", "A", 0.5f, "", "", emptySet())
        val sorted = listOf(p1, p2).sortedWith(vm.candidateComparator)
        // amll has higher priority than qq according to map
        assertEquals(p2, sorted.first())
    }

    @Test
    fun `stable order preserved when fully tied`() = runTest {
        val vm = CustomLyricsViewModel(Application())
        val x = CustomLyricsCandidate("qq", "1", "T", "A", 0.5f, "", "", emptySet())
        val y = CustomLyricsCandidate("qq", "2", "T", "A", 0.5f, "", "", emptySet())
        val original = listOf(x, y)
        val sorted = original.sortedWith(vm.candidateComparator)
        assertEquals(original, sorted)
    }

    @Test
    fun `earlier seq candidate wins when fully tied`() = runTest {
        val vm = CustomLyricsViewModel(Application())
        // assign sequence numbers explicitly
        val a = CustomLyricsCandidate("qq", "1", "T", "A", 0.5f, "", "", emptySet(), seq = 1L)
        val b = CustomLyricsCandidate("qq", "2", "T", "A", 0.5f, "", "", emptySet(), seq = 2L)
        val sorted = listOf(a, b).sortedWith(vm.candidateComparator)
        assertEquals(a, sorted.first())
    }

    @Test
    fun `loadMore adds next batch of QQ results`() = runTest {
        // engine returns 5 QQ items whenever QQ endpoint is called, empty for others
        val qqItems = (1..5).joinToString(",") { i ->
            "{\"mid\":\"m$i\",\"id\":$i,\"title\":\"T$i\",\"singer\":[{\"name\":\"A\"}]}"
        }
        val qqBody = "{\"req_1\":{\"data\":{\"body\":{\"song\":{\"list\":[$qqItems]}}}}}"
        val engine = MockEngine { request ->
            if (request.url.toString().contains("musicu.fcg")) {
                respond(qqBody, HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("application/json")))
            } else {
                // generic empty payload for other sources
                respond("{\"result\":{\"songs\":[]},\"code\":200}", HttpStatusCode.OK, headers = headersOf("Content-Type" to listOf("application/json")))
            }
        }
        val client = HttpClient(engine as HttpClientEngine)
        val fakeRepo = LyricsRepository(client)
        val fakeCache = LyricsCacheRepository(Application())
        val viewModel = CustomLyricsViewModel(Application(), lyricsRepository = fakeRepo, lyricsCacheRepository = fakeCache)

        // perform initial search to populate lastSearchTitle/artist
        viewModel.searchCandidates("x", "y")
        advanceUntilIdle()

        // load first batch
        viewModel.loadMore("qq")
        advanceUntilIdle()
        assertEquals(3, viewModel.candidates.value.size)

        // load second batch
        viewModel.loadMore("qq")
        advanceUntilIdle()
        assertEquals(5, viewModel.candidates.value.size)
    }

    @Test
    fun `new search resets pagination offsets`() = runTest {
        val engine = MockEngine { request ->
            respond("{\"result\":{\"songs\":[]},\"code\":200}", HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("application/json")))
        }
        val client = HttpClient(engine as HttpClientEngine)
        val repo = LyricsRepository(client)
        val cache = LyricsCacheRepository(Application())
        val vm = CustomLyricsViewModel(Application(), lyricsRepository = repo, lyricsCacheRepository = cache)

        vm.searchCandidates("a", "b")
        advanceUntilIdle()
        vm.loadMore("qq")
        advanceUntilIdle()
        // offsets should now be nonzero
        vm.searchCandidates("a", "b")
        advanceUntilIdle()
        vm.loadMore("qq")
        advanceUntilIdle()
        assertTrue(vm.candidates.value.isEmpty())
    }

    @Test
    fun `formatAutoSource adds prefix and handles various providers`() {
        // provider only
        val s1 = com.amll.droidmate.data.repository.LyricsRepository.formatAutoSource(
            provider = "qq",
            title = "T",
            artist = "A",
            songId = "123"
        )
        assertTrue(s1.startsWith("自动识别:"))
        assertTrue(s1.contains("QQ音乐：T - A(123)"))

        // AMLL should use its special name
        val s2 = com.amll.droidmate.data.repository.LyricsRepository.formatAutoSource(
            provider = "amll",
            title = "X",
            artist = "Y",
            songId = "9999"
        )
        assertEquals("自动识别:AMLL TTML DB：X - Y(9999)", s2)

        // when the ID includes a platform prefix we expect the provider
        // name to carry the platform and the ID part to be stripped
        val s3 = com.amll.droidmate.data.repository.LyricsRepository.formatAutoSource(
            provider = "amll",
            title = "Foo",
            artist = "Bar",
            songId = "qq:abcd"
        )
        assertEquals("自动识别:AMLL TTML DB(QQ)：Foo - Bar(abcd)", s3)
    }

}
