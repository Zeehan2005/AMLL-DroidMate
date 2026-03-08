package com.amll.droidmate.data.repository

import android.content.Context
import com.amll.droidmate.domain.model.CachedLyricEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class LyricsCacheRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun getAll(): List<CachedLyricEntry> {
        return readAll().sortedByDescending { it.updatedAt }
    }

    fun search(query: String): List<CachedLyricEntry> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return getAll()

        return getAll().filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.source.lowercase().contains(q)
        }
    }

    fun findBySong(title: String, artist: String): CachedLyricEntry? {
        val titleKey = normalize(title)
        val artistKey = normalize(artist)
        return readAll()
            .filter { normalize(it.title) == titleKey && normalize(it.artist) == artistKey }
            .maxByOrNull { it.updatedAt }
    }

    fun upsert(
        title: String,
        artist: String,
        source: String,
        ttmlContent: String
    ) {
        if (ttmlContent.isBlank()) return

        val all = readAll().toMutableList()
        val titleKey = normalize(title)
        val artistKey = normalize(artist)
        val sourceKey = normalize(source)

        val existingIndex = all.indexOfFirst {
            normalize(it.title) == titleKey &&
                normalize(it.artist) == artistKey &&
                normalize(it.source) == sourceKey
        }

        val newEntry = CachedLyricEntry(
            id = if (existingIndex >= 0) all[existingIndex].id else UUID.randomUUID().toString(),
            title = title,
            artist = artist,
            source = source,
            ttmlContent = ttmlContent,
            updatedAt = System.currentTimeMillis()
        )

        if (existingIndex >= 0) {
            all[existingIndex] = newEntry
        } else {
            all.add(newEntry)
        }

        writeAll(all)
    }

    fun deleteById(id: String) {
        val all = readAll().toMutableList()
        all.removeAll { it.id == id }
        writeAll(all)
    }

    fun clearAll() {
        prefs.edit().remove(KEY_CACHE_JSON).apply()
    }

    private fun readAll(): List<CachedLyricEntry> {
        val raw = prefs.getString(KEY_CACHE_JSON, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<CachedLyricEntry>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeAll(entries: List<CachedLyricEntry>) {
        prefs.edit().putString(KEY_CACHE_JSON, json.encodeToString(entries)).apply()
    }

    private fun normalize(value: String): String {
        return value.trim().lowercase()
    }

    private companion object {
        private const val PREFS_NAME = "droidmate_lyrics_cache"
        private const val KEY_CACHE_JSON = "lyrics_cache_json"
    }
}
