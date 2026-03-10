package com.amll.droidmate.ui.viewmodel

import android.app.Application
import com.amll.droidmate.data.parser.LyricsFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

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
