package com.nuvio.shared.collections

import com.nuvio.shared.model.MetaPreview
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

// Nuvio collections (port of the Android app's domain/model/Collection.kt and the JSON that
// CollectionsDataStore exports / imports). Read-only here: imported from JSON, shown on Home.

@Serializable
data class NuvioCollection(
    val id: String,
    val title: String,
    val backdropImageUrl: String? = null,
    val pinToTop: Boolean = false,
    /** "TABBED_GRID" (a tab per source), "ROWS" (a row per source) or "FOLLOW_LAYOUT". */
    val viewMode: String = "TABBED_GRID",
    val showAllTab: Boolean = true,
    val folders: List<NuvioCollectionFolder> = emptyList()
) {
    val showsRows: Boolean get() = !viewMode.equals("TABBED_GRID", ignoreCase = true)
}

@Serializable
data class NuvioCollectionFolder(
    val id: String,
    val title: String,
    val coverImageUrl: String? = null,
    val focusGifUrl: String? = null,
    val coverEmoji: String? = null,
    /** "POSTER", "LANDSCAPE" / "wide" or "SQUARE" (default). */
    val tileShape: String = "SQUARE",
    val hideTitle: Boolean = false,
    val sources: List<NuvioCollectionSource> = emptyList(),
    val heroBackdropUrl: String? = null,
    val titleLogoUrl: String? = null
) {
    /** "poster", "landscape" or "square", for the tile. */
    val shape: String
        get() = when (tileShape.lowercase()) {
            "poster" -> "poster"
            "landscape", "wide" -> "landscape"
            else -> "square"
        }
}

@Serializable
data class NuvioCollectionSource(
    /** "addon" (default), "tmdb" or "trakt". */
    val provider: String = "addon",
    val addonId: String? = null,
    val type: String? = null,
    val catalogId: String? = null,
    val genre: String? = null,
    /** TMDB: LIST, COLLECTION, COMPANY, NETWORK, DISCOVER, PERSON, DIRECTOR. */
    val tmdbSourceType: String? = null,
    val title: String? = null,
    val tmdbId: Int? = null,
    val traktListId: Long? = null,
    /** "MOVIE" or "TV". */
    val mediaType: String? = null,
    val sortBy: String? = null,
    val sortHow: String? = null,
    val filters: NuvioTmdbFilters? = null
) {
    val kind: String get() = provider.lowercase().let { if (it == "tmdb" || it == "trakt") it else "addon" }
    val isShow: Boolean get() = mediaType.equals("TV", ignoreCase = true) || mediaType.equals("series", ignoreCase = true)
}

@Serializable
data class NuvioTmdbFilters(
    val withGenres: String? = null,
    val withoutGenres: String? = null,
    val releaseDateGte: String? = null,
    val releaseDateLte: String? = null,
    val voteAverageGte: Double? = null,
    val voteAverageLte: Double? = null,
    val voteCountGte: Int? = null,
    val withOriginalLanguage: String? = null,
    val withOriginCountry: String? = null,
    val withKeywords: String? = null,
    val withoutKeywords: String? = null,
    val withCompanies: String? = null,
    val withoutCompanies: String? = null,
    val withNetworks: String? = null,
    val year: Int? = null,
    val watchRegion: String? = null,
    val withWatchProviders: String? = null,
    val withoutWatchProviders: String? = null
)

/** One page of a folder source; [nextCursor] is passed back for the next page. */
class CollectionPage(val items: List<MetaPreview>, val hasMore: Boolean, val nextCursor: Int)

/** Legacy folders listed add-on catalogs as "catalogSources". */
@Serializable
private data class LegacyCatalogSource(val addonId: String, val type: String, val catalogId: String, val genre: String? = null)

internal object NuvioCollectionsJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    /**
     * Collections from exported JSON: an array of collections, or an object holding one under
     * "collections" / "collections_json". Invalid entries are skipped; duplicate ids keep the first.
     */
    fun parse(text: String): List<NuvioCollection> {
        val root = runCatching { json.parseToJsonElement(text.trim()) }.getOrElse {
            throw IllegalArgumentException("Not valid JSON: ${it.message ?: "parse error"}")
        }
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["collections"] ?: root["collections_json"]).let { inner ->
                when (inner) {
                    is JsonArray -> inner
                    is JsonPrimitive -> runCatching { json.parseToJsonElement(inner.content) as? JsonArray }.getOrNull()
                    else -> null
                }
            } ?: throw IllegalArgumentException("Expected a JSON array of collections")
            else -> throw IllegalArgumentException("Expected a JSON array of collections")
        }
        return array.mapNotNull { element -> collection(element) }.distinctBy { it.id }
    }

    private fun collection(element: JsonElement): NuvioCollection? {
        val obj = element as? JsonObject ?: return null
        val parsed = runCatching { json.decodeFromJsonElement<NuvioCollection>(stripFolders(obj)) }.getOrNull() ?: return null
        if (parsed.id.isBlank()) return null
        val folders = (obj["folders"] as? JsonArray).orEmpty().mapNotNull { folder(it) }
        return parsed.copy(folders = folders)
    }

    private fun stripFolders(obj: JsonObject) = JsonObject(obj - "folders")

    private fun folder(element: JsonElement): NuvioCollectionFolder? {
        val obj = element as? JsonObject ?: return null
        val base = runCatching {
            json.decodeFromJsonElement<NuvioCollectionFolder>(JsonObject(obj - "sources" - "catalogSources"))
        }.getOrNull() ?: return null
        if (base.id.isBlank()) return null
        val sources = (obj["sources"] as? JsonArray)?.mapNotNull { source(it) }
            ?: (obj["catalogSources"] as? JsonArray).orEmpty().mapNotNull { element2 ->
                runCatching { json.decodeFromJsonElement<LegacyCatalogSource>(element2) }.getOrNull()?.let {
                    NuvioCollectionSource(addonId = it.addonId, type = it.type, catalogId = it.catalogId, genre = it.genre)
                }
            }
        return base.copy(sources = sources)
    }

    private fun source(element: JsonElement): NuvioCollectionSource? {
        val source = runCatching { json.decodeFromJsonElement<NuvioCollectionSource>(element) }.getOrNull() ?: return null
        return when (source.kind) {
            "tmdb" -> source.takeIf { !it.tmdbSourceType.isNullOrBlank() }
            "trakt" -> source.takeIf { (it.traktListId ?: 0L) > 0L }
            else -> source.takeIf { !it.addonId.isNullOrBlank() && !it.type.isNullOrBlank() && !it.catalogId.isNullOrBlank() }
        }
    }

    fun encode(list: List<NuvioCollection>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(NuvioCollection.serializer()), list)
}

/** Today as "yyyy-MM-dd" (UTC), for TMDB network sources (shows aired so far). */
internal fun todayIso(nowMs: Long): String {
    val days = nowMs / 86_400_000L + 719_468
    val era = days / 146_097
    val doe = days - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    var y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    if (m <= 2) y += 1
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

/**
 * TMDB discover parameters for a source (Android TmdbCollectionSourceResolver.resolveDiscover):
 * company / network sources fill with_companies / with_networks; networks are TV only and list
 * shows that have aired, still running or ended.
 */
internal fun discoverParams(source: NuvioCollectionSource, isShow: Boolean, today: String): Map<String, String> {
    val f = source.filters ?: NuvioTmdbFilters()
    val type = source.tmdbSourceType?.uppercase()
    val sort = source.sortBy?.takeIf { it.isNotBlank() } ?: "popularity.desc"
    val p = LinkedHashMap<String, String>()
    fun put(key: String, value: Any?) { value?.toString()?.takeIf { it.isNotBlank() }?.let { p[key] = it } }
    if (isShow) {
        put("sort_by", if (sort == "primary_release_date.desc") "first_air_date.desc" else sort)
        put("with_companies", if (type == "COMPANY") source.tmdbId else f.withCompanies)
        put("with_networks", if (type == "NETWORK") source.tmdbId else f.withNetworks)
        put("first_air_date.lte", f.releaseDateLte ?: if (type == "NETWORK") today else null)
        if (type == "NETWORK") put("with_status", "0|3|4")
        put("first_air_date.gte", f.releaseDateGte)
        put("first_air_date_year", f.year)
    } else {
        put("sort_by", if (sort == "first_air_date.desc") "primary_release_date.desc" else sort)
        put("with_companies", if (type == "COMPANY") source.tmdbId else f.withCompanies)
        put("primary_release_date.lte", f.releaseDateLte)
        put("primary_release_date.gte", f.releaseDateGte)
        put("year", f.year)
    }
    put("vote_count.gte", f.voteCountGte)
    put("with_genres", f.withGenres)
    put("vote_average.gte", f.voteAverageGte)
    put("vote_average.lte", f.voteAverageLte)
    put("with_original_language", f.withOriginalLanguage)
    put("with_origin_country", f.withOriginCountry)
    put("with_keywords", f.withKeywords)
    put("without_keywords", f.withoutKeywords)
    if (!f.withWatchProviders.isNullOrBlank() || !f.withoutWatchProviders.isNullOrBlank()) {
        put("watch_region", f.watchRegion?.takeIf { it.isNotBlank() } ?: "US")
    }
    put("with_watch_providers", f.withWatchProviders)
    if (!f.withWatchProviders.isNullOrBlank()) put("with_watch_monetization_types", "flatrate|free|ads|rent|buy")
    put("without_companies", f.withoutCompanies)
    put("without_genres", f.withoutGenres)
    put("without_watch_providers", f.withoutWatchProviders)
    return p
}

/** Android sortedFor: list / collection / people sources sorted locally. */
internal fun List<MetaPreview>.sortedForCollection(sortBy: String?): List<MetaPreview> = when (sortBy) {
    "vote_average.desc" -> sortedWith(compareByDescending<MetaPreview> { it.imdbRating?.toDoubleOrNull() ?: -1.0 }.thenByDescending { it.releaseInfo ?: "" })
    "primary_release_date.desc", "first_air_date.desc" -> sortedByDescending { it.releaseInfo ?: "" }
    else -> this
}
