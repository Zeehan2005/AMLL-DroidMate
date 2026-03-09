package com.amll.droidmate.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.domain.model.TTMLMetadata
import com.amll.droidmate.domain.model.NowPlayingMusic
import com.amll.droidmate.service.LyricNotificationManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for [MainViewModel] related to lyric notifications.
 */
@RunWith(AndroidJUnit4::class)
class MainViewModelTest {
    private lateinit var context: Context
    private lateinit var fakeManager: TestLyricNotificationManager
    private lateinit var viewModel: MainViewModel

    private class TestLyricNotificationManager(context: Context) : LyricNotificationManager(context) {
        var shownTimes = 0
        var lastLine: LyricLine? = null
        var lastOngoing: Boolean? = null
        var canceledTimes = 0

        override fun showOrUpdate(currentLine: LyricLine?, ongoing: Boolean) {
            shownTimes++
            lastLine = currentLine
            lastOngoing = ongoing
        }

        override fun cancel() {
            canceledTimes++
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fakeManager = TestLyricNotificationManager(context)
        viewModel = MainViewModel(context as android.app.Application)
        viewModel.lyricNotificationManager = fakeManager
    }

    private fun sampleLyrics(): TTMLLyrics {
        return TTMLLyrics(
            metadata = TTMLMetadata(title = "t", artist = "a"),
            lines = listOf(
                LyricLine(startTime = 0, endTime = 1000, text = "first"),
                LyricLine(startTime = 1000, endTime = 2000, text = "second")
            )
        )
    }

    @Test
    fun pauseMakesNotificationDismissable() {
        val lyrics = sampleLyrics()
        val playing = NowPlayingMusic(
            title = "t", artist = "a", currentPosition = 500, isPlaying = true
        )

        viewModel.updateLyricNotification(lyrics, playing)
        assertEquals(1, fakeManager.shownTimes)
        assertNotNull(fakeManager.lastLine)
        assertTrue(fakeManager.lastOngoing == true)

        // now simulate pause - should update notification again with ongoing=false
        val paused = playing.copy(isPlaying = false)
        viewModel.updateLyricNotification(lyrics, paused)
        assertEquals(2, fakeManager.shownTimes)
        assertFalse(fakeManager.lastOngoing == true)
        // should not cancel in this case
        assertEquals(0, fakeManager.canceledTimes)
    }

    @Test
    fun noMusicOrLyricsCancels() {
        viewModel.updateLyricNotification(null, null)
        assertEquals(1, fakeManager.canceledTimes)

        val lyrics = sampleLyrics()
        viewModel.updateLyricNotification(lyrics, null)
        assertEquals(2, fakeManager.canceledTimes)

        val playing = NowPlayingMusic(title = "t", artist = "a", currentPosition = 0, isPlaying = true)
        viewModel.updateLyricNotification(null, playing)
        assertEquals(3, fakeManager.canceledTimes)
    }

    @Test
    fun clearedWhilePausedPreventsSubsequentUpdates() {
        val lyrics = sampleLyrics()
        val paused = NowPlayingMusic(
            title = "t", artist = "a", currentPosition = 500, isPlaying = false
        )

        // initial show in paused state
        viewModel.updateLyricNotification(lyrics, paused)
        assertEquals(1, fakeManager.shownTimes)
        assertFalse(fakeManager.lastOngoing == true)

        // on first pause we get a non-ongoing notification
        // (user swipe doesn't matter for suppression now)

        // update again while still paused; should NOT produce a second notification
        viewModel.updateLyricNotification(lyrics, paused)
        assertEquals(1, fakeManager.shownTimes)

        // simulate swipe; shouldn't enable further updates, only cancel
        viewModel.onNotificationDeletedByUser()
        assertEquals(1, fakeManager.canceledTimes)
        viewModel.updateLyricNotification(lyrics, paused)
        assertEquals(1, fakeManager.shownTimes)

        // if lyrics change even while paused we should still suppress
        val modified = lyrics.copy(lines = lyrics.lines + LyricLine(2000,3000,"third"))
        viewModel.updateLyricNotification(modified, paused)
        assertEquals(1, fakeManager.shownTimes) // no new notification

        // resume playback - flag should clear and update should reappear
        val playing = paused.copy(isPlaying = true)
        viewModel.updateLyricNotification(lyrics, playing)
        assertEquals(2, fakeManager.shownTimes)
        assertTrue(fakeManager.lastOngoing == true)
    }
}
