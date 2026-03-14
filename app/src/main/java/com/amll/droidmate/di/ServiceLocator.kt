package com.amll.droidmate.di

import android.content.Context
import com.amll.droidmate.data.network.HttpClientFactory
import com.amll.droidmate.data.repository.LyricsCacheRepository
import com.amll.droidmate.data.repository.LyricsRepository

/**
 * Simple manual service locator to avoid scattering `HttpClientFactory` and
 * repository construction all over the codebase.  Clients may still pass in
 * alternate implementations for testing if necessary.
 */
object ServiceLocator {
    fun provideHttpClient(context: Context) = HttpClientFactory.create(context)

    fun provideLyricsRepository(context: Context): LyricsRepository =
        LyricsRepository(provideHttpClient(context), provideLyricsCacheRepository(context), context)

    fun provideLyricsCacheRepository(context: Context): LyricsCacheRepository =
        LyricsCacheRepository(context)
}
