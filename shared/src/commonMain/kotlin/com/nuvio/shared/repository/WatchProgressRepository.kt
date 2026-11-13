package com.nuvio.shared.repository

import com.nuvio.shared.model.SavedItem
import com.nuvio.shared.model.WatchProgress
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val storeJson = Json { ignoreUnknownKeys = true }

/** On-device watch progress, keyed by video id. Newest first. */
class WatchProgressRepository(private val store: KeyValueStore) {
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<WatchProgress>> = _entries.asStateFlow()

    private fun load(): List<WatchProgress> = store.getString(KEY)?.let {
        runCatching { storeJson.decodeFromString(ListSerializer(WatchProgress.serializer()), it) }.getOrNull()
    }.orEmpty()

    private fun save(list: List<WatchProgress>) {
        val trimmed = list.sortedByDescending { it.updatedAtMs }.take(MAX_ENTRIES)
        _entries.value = trimmed
        store.putString(KEY, storeJson.encodeToString(ListSerializer(WatchProgress.serializer()), trimmed))
    }

    fun progressFor(videoId: String): WatchProgress? = _entries.value.firstOrNull { it.videoId == videoId }

    /** Latest entry for a title (any episode). */
    fun latestFor(contentId: String): WatchProgress? = _entries.value.firstOrNull { it.contentId == contentId }

    fun update(progress: WatchProgress) {
        val stamped = if (progress.updatedAtMs > 0) progress else progress.copy(updatedAtMs = currentTimeMillis())
        save(listOf(stamped) + _entries.value.filterNot { it.videoId == progress.videoId })
    }

    fun remove(contentId: String) = save(_entries.value.filterNot { it.contentId == contentId })

    /** One card per title: its most recent unfinished video. */
    fun continueWatching(): List<WatchProgress> =
        _entries.value
            .groupBy { it.contentId }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.updatedAtMs } }
            .filter { it.isResumable }
            .sortedByDescending { it.updatedAtMs }

    private companion object {
        const val KEY = "watch_progress_v1"
        const val MAX_ENTRIES = 500
    }
}

/** On-device saved titles ("Library"). */
class LibraryRepository(private val store: KeyValueStore) {
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<SavedItem>> = _items.asStateFlow()

    private fun load(): List<SavedItem> = store.getString(KEY)?.let {
        runCatching { storeJson.decodeFromString(ListSerializer(SavedItem.serializer()), it) }.getOrNull()
    }.orEmpty()

    private fun save(list: List<SavedItem>) {
        _items.value = list
        store.putString(KEY, storeJson.encodeToString(ListSerializer(SavedItem.serializer()), list))
    }

    fun contains(id: String): Boolean = _items.value.any { it.id == id }

    fun toggle(item: SavedItem): Boolean {
        return if (contains(item.id)) {
            save(_items.value.filterNot { it.id == item.id }); false
        } else {
            val stamped = if (item.savedAtMs > 0) item else item.copy(savedAtMs = currentTimeMillis())
            save(listOf(stamped) + _items.value); true
        }
    }

    private companion object {
        const val KEY = "library_v1"
    }
}
