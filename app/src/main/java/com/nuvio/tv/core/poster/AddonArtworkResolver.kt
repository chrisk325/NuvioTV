package com.nuvio.tv.core.poster

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Artwork an installed meta addon publishes for a title. */
@Immutable
data class AddonArtwork(
    val poster: String?,
    val posterShape: PosterShape,
    val landscapePoster: String?,
    val background: String?,
    val logo: String?
) {
    val hasArtwork: Boolean
        get() = !poster.isNullOrBlank() || !landscapePoster.isNullOrBlank()
}

/** A title to look up artwork for. [imdbId] is tried when [id] alone does not resolve. */
data class AddonArtworkRequest(
    val type: String,
    val id: String,
    val imdbId: String? = null
) {
    val key: String get() = addonArtworkKey(type, id)
}

fun addonArtworkKey(type: String, id: String): String =
    "${normalizeArtworkType(type)}:$id"

private fun normalizeArtworkType(type: String): String = when (type.trim().lowercase()) {
    "tv", "show", "shows", "series" -> "series"
    else -> type.trim().lowercase()
}

fun Meta.toAddonArtwork(): AddonArtwork = AddonArtwork(
    poster = poster?.takeIf { it.isNotBlank() },
    posterShape = posterShape,
    landscapePoster = landscapePoster?.takeIf { it.isNotBlank() },
    background = background?.takeIf { it.isNotBlank() },
    logo = logo?.takeIf { it.isNotBlank() }
)

/**
 * Resolves the poster and landscape poster that the user's meta addons publish for titles
 * that came from somewhere else (tracking providers, TMDB, Trakt or Simkl recommendations),
 * so those surfaces show the same artwork as addon catalogs.
 *
 * Results are kept in memory for the process, misses for a few minutes, and the underlying
 * [MetaRepository] caches the meta responses themselves.
 */
@Singleton
class AddonArtworkResolver @Inject constructor(
    private val metaRepository: MetaRepository,
    private val profileManager: ProfileManager
) {
    private val resolved = ConcurrentHashMap<String, AddonArtwork>()
    private val misses = ConcurrentHashMap<String, Long>()
    private val semaphore = Semaphore(MAX_CONCURRENCY)

    // Profiles can have different addons, so lookups are kept per profile.
    private fun cacheKey(key: String): String = "${profileManager.activeProfileId.value}|$key"

    fun cached(type: String, id: String): AddonArtwork? = resolved[cacheKey(addonArtworkKey(type, id))]

    fun cached(requests: List<AddonArtworkRequest>): Map<String, AddonArtwork> =
        requests.mapNotNull { request -> resolved[cacheKey(request.key)]?.let { request.key to it } }.toMap()

    suspend fun resolve(request: AddonArtworkRequest): AddonArtwork? {
        val key = cacheKey(request.key)
        resolved[key]?.let { return it }
        misses[key]?.let { missedAt ->
            if (SystemClock.elapsedRealtime() - missedAt < MISS_TTL_MS) return null
            misses.remove(key)
        }
        val artwork = semaphore.withPermit {
            resolved[key] ?: withContext(Dispatchers.IO) { fetch(request) }
        }
        if (artwork != null) {
            resolved[key] = artwork
            misses.remove(key)
        } else {
            misses[key] = SystemClock.elapsedRealtime()
        }
        return artwork
    }

    /**
     * Resolves [requests] in order and hands each finished batch to [onBatch], so a long list
     * fills in progressively instead of all at once at the end. Cached entries arrive first.
     */
    suspend fun resolveInBatches(
        requests: List<AddonArtworkRequest>,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        onBatch: suspend (Map<String, AddonArtwork>) -> Unit
    ) {
        val unique = requests.distinctBy { it.key }
        val cachedBatch = cached(unique)
        if (cachedBatch.isNotEmpty()) onBatch(cachedBatch)
        val pending = unique.filter { it.key !in cachedBatch }
        pending.chunked(batchSize.coerceAtLeast(1)).forEach { chunk ->
            val batch = coroutineScope {
                chunk.map { request ->
                    async { resolve(request)?.let { request.key to it } }
                }.awaitAll().filterNotNull().toMap()
            }
            if (batch.isNotEmpty()) onBatch(batch)
        }
    }

    /** Resolves every request and returns what was found, giving up on the rest after [timeoutMs]. */
    suspend fun resolveAll(
        requests: List<AddonArtworkRequest>,
        timeoutMs: Long
    ): Map<String, AddonArtwork> {
        val found = ConcurrentHashMap<String, AddonArtwork>()
        withTimeoutOrNull(timeoutMs) {
            resolveInBatches(requests, batchSize = requests.size.coerceAtLeast(1)) { batch ->
                found.putAll(batch)
            }
        }
        // Lookups still running when the timeout hit keep filling the cache for next time.
        found.putAll(cached(requests))
        return found
    }

    private suspend fun fetch(request: AddonArtworkRequest): AddonArtwork? {
        val idCandidates = buildList {
            add(request.id)
            request.imdbId?.takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()
        val type = normalizeArtworkType(request.type)
        for (id in idCandidates) {
            fetchFrom(type, id, primaryOnly = true)?.let { return it }
        }
        for (id in idCandidates) {
            fetchFrom(type, id, primaryOnly = false)?.let { return it }
        }
        return null
    }

    private suspend fun fetchFrom(type: String, id: String, primaryOnly: Boolean): AddonArtwork? {
        val result = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                val flow = if (primaryOnly) {
                    metaRepository.getMetaFromPrimaryAddon(type = type, id = id)
                } else {
                    metaRepository.getMetaFromAllAddons(type = type, id = id)
                }
                flow.first { it !is NetworkResult.Loading }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val meta = (result as? NetworkResult.Success<*>)?.data as? Meta ?: return null
        return meta.toAddonArtwork().takeIf { it.hasArtwork }
    }

    private companion object {
        const val MAX_CONCURRENCY = 4
        const val DEFAULT_BATCH_SIZE = 12
        const val REQUEST_TIMEOUT_MS = 6_000L
        const val MISS_TTL_MS = 5 * 60_000L
    }
}

/**
 * Swaps in the addon poster, keeping the original as [LibraryEntry.rawPosterUrl] so the
 * image loader can fall back to it if the addon image fails.
 */
fun LibraryEntry.withAddonArtwork(artwork: AddonArtwork?): LibraryEntry {
    if (artwork == null || !artwork.hasArtwork) return this
    val addonPoster = artwork.poster
    return copy(
        poster = addonPoster ?: poster,
        posterShape = if (addonPoster != null) artwork.posterShape else posterShape,
        rawPosterUrl = rawPosterUrl ?: poster.takeIf { addonPoster != null && it != addonPoster },
        landscapePoster = artwork.landscapePoster ?: landscapePoster,
        background = background ?: artwork.background,
        logo = logo ?: artwork.logo
    )
}

/** Same as [LibraryEntry.withAddonArtwork], for previews such as recommendations. */
fun MetaPreview.withAddonArtwork(artwork: AddonArtwork?): MetaPreview {
    if (artwork == null || !artwork.hasArtwork) return this
    val addonPoster = artwork.poster
    return copy(
        poster = addonPoster ?: poster,
        posterShape = if (addonPoster != null) artwork.posterShape else posterShape,
        rawPosterUrl = rawPosterUrl ?: poster.takeIf { addonPoster != null && it != addonPoster },
        landscapePoster = artwork.landscapePoster ?: landscapePoster,
        background = background ?: artwork.background,
        logo = logo ?: artwork.logo
    )
}

fun MetaPreview.toAddonArtworkRequest(): AddonArtworkRequest =
    AddonArtworkRequest(type = apiType, id = id, imdbId = imdbId)

/**
 * Applies addon posters to a row of previews. Recommendation sources hand out landscape art,
 * so once any title has an addon poster the whole row switches to portrait cards, with titles
 * the addons do not know falling back to their source's own poster.
 */
fun List<MetaPreview>.withAddonPosters(artwork: Map<String, AddonArtwork>): List<MetaPreview> {
    if (artwork.isEmpty()) return this
    var hasAddonPoster = false
    val enriched = map { preview ->
        val match = artwork[addonArtworkKey(preview.apiType, preview.id)]
        if (match?.poster != null && match.posterShape == PosterShape.POSTER) hasAddonPoster = true
        preview.withAddonArtwork(match)
    }
    if (!hasAddonPoster) return enriched
    return enriched.map { preview ->
        if (preview.posterShape == PosterShape.POSTER) {
            preview
        } else {
            preview.copy(
                poster = preview.rawPosterUrl ?: preview.poster,
                posterShape = PosterShape.POSTER
            )
        }
    }
}
