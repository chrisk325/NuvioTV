package com.nuvio.shared.tracking

import com.nuvio.shared.model.Meta
import com.nuvio.shared.model.Video
import com.nuvio.shared.storage.KeyValueStore
import com.nuvio.shared.util.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Device-code / PIN sign-in prompt (Trakt, Simkl). */
data class DeviceSignIn(
    val providerId: String,
    val userCode: String,
    val verificationUrl: String,
    val deviceCode: String,
    val intervalSeconds: Int
)

/** What is being watched, in the ids trackers understand. */
data class TrackedMedia(
    val isEpisode: Boolean,
    val imdbId: String?,
    val tmdbId: Int?,
    val kitsuId: String?,
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?
) {
    companion object {
        /** Derives tracker ids from Stremio ids ("tt123", "tt123:1:2", "kitsu:5:3", "tmdb:42"). */
        fun from(meta: Meta, video: Video?): TrackedMedia {
            val parts = (video?.id ?: meta.id).split(':')
            val imdb = (meta.imdbId ?: meta.id.takeIf { it.startsWith("tt") })?.substringBefore(':')
            val kitsu = if (parts.firstOrNull() == "kitsu") parts.getOrNull(1) else null
            val tmdb = if (parts.firstOrNull() == "tmdb") parts.getOrNull(1)?.toIntOrNull() else null
            val year = (meta.releaseInfo ?: meta.released)?.take(4)?.toIntOrNull()
            val isEpisode = meta.isSeriesLike && video?.season != null && video.episode != null
            return TrackedMedia(
                isEpisode = isEpisode,
                imdbId = imdb,
                tmdbId = tmdb,
                kitsuId = kitsu,
                title = meta.name,
                year = year,
                season = if (isEpisode) video.season else null,
                episode = if (isEpisode) video.episode else null
            )
        }
    }
}

enum class ScrobbleAction(val path: String) { START("start"), PAUSE("pause"), STOP("stop") }

private val listJson = Json { ignoreUnknownKeys = true }

/**
 * Locally known watched items: "movie:<id>" and "<videoId>" for episodes. Filled when playback
 * completes and from Trakt/Simkl history sync.
 */
class WatchedRepository(private val store: KeyValueStore) {
    private val _watched = MutableStateFlow(load())
    val watched: StateFlow<Set<String>> = _watched.asStateFlow()

    private fun load(): Set<String> = store.getString(KEY)?.let {
        runCatching { listJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
    }.orEmpty().toSet()

    private fun save(set: Set<String>) {
        _watched.value = set
        store.putString(KEY, listJson.encodeToString(ListSerializer(String.serializer()), set.toList()))
    }

    fun isWatched(key: String): Boolean = key in _watched.value

    fun mark(key: String, watched: Boolean) =
        save(if (watched) _watched.value + key else _watched.value - key)

    fun addAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        save(_watched.value + keys)
    }

    private companion object { const val KEY = "watched_v1" }
}

/** OAuth token persisted per tracker. */
@Serializable
internal data class StoredToken(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long = 0,
    val username: String? = null
) {
    val isExpired: Boolean get() = expiresAtMs > 0 && currentTimeMillis() > expiresAtMs - 60_000
}

internal fun KeyValueStore.readToken(key: String): StoredToken? =
    getString(key)?.let { runCatching { listJson.decodeFromString(StoredToken.serializer(), it) }.getOrNull() }

internal fun KeyValueStore.writeToken(key: String, token: StoredToken?) =
    putString(key, token?.let { listJson.encodeToString(StoredToken.serializer(), it) })
