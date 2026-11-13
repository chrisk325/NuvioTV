package com.nuvio.shared.tmdb

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.int
import com.nuvio.shared.data.nonBlank
import com.nuvio.shared.data.obj
import com.nuvio.shared.data.objects
import com.nuvio.shared.data.str
import com.nuvio.shared.model.ContentType
import com.nuvio.shared.model.MetaPreview
import com.nuvio.shared.model.PosterShape
import com.nuvio.shared.util.encodeUrlComponent
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

data class TmdbPerson(
    val id: Int,
    val name: String,
    val role: String?,
    val photo: String?
)

/** Extra metadata layered over add-on meta (port of the Android TmdbMetadataService subset). */
data class TmdbEnrichment(
    val tmdbId: Int,
    val isShow: Boolean,
    val logo: String?,
    val backdrop: String?,
    val localizedTitle: String?,
    val genres: List<String>,
    val rating: String?,
    val runtimeMinutes: Int?,
    val status: String?,
    val countries: List<String>,
    val language: String?,
    val productionCompanies: List<String>,
    val directors: List<String>,
    val writers: List<String>,
    val overview: String?,
    val tagline: String?,
    val cast: List<TmdbPerson>,
    val crew: List<TmdbPerson>,
    val recommendations: List<MetaPreview>,
    val collectionName: String?,
    val collectionParts: List<MetaPreview>,
    val certification: String?,
    val networks: List<String>,
    val trailerYoutubeKeys: List<String>
)

data class TmdbEpisode(
    val season: Int,
    val episode: Int,
    val title: String?,
    val overview: String?,
    val thumbnail: String?,
    val runtimeMinutes: Int?
)

data class TmdbPersonDetail(
    val id: Int,
    val name: String,
    val biography: String?,
    val photo: String?,
    val birthday: String?,
    val placeOfBirth: String?,
    val knownFor: String?,
    val credits: List<MetaPreview>
)

class TmdbService(private val http: HttpClient, private val apiKey: String) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    private val cache = LinkedHashMap<String, TmdbEnrichment>()
    private val mutex = Mutex()

    /** Finds the TMDB id for an IMDb id. Returns null when unknown. */
    suspend fun findByImdb(imdbId: String, isShow: Boolean): Int? {
        val body = get("find/${encodeUrlComponent(imdbId)}?external_source=imdb_id") ?: return null
        val list = body.objects(if (isShow) "tv_results" else "movie_results")
        return list.firstOrNull()?.int("id")
    }

    /** IMDb id for a TMDB id (used to open "tmdb:123" recommendations with Cinemeta). */
    @Throws(Exception::class)
    suspend fun imdbIdFor(tmdbId: Int, isShow: Boolean): String? =
        get("${if (isShow) "tv" else "movie"}/$tmdbId/external_ids")?.nonBlank("imdb_id")

    @Throws(Exception::class)
    suspend fun enrich(id: String, imdbId: String?, isShow: Boolean, language: String): TmdbEnrichment? {
        if (!isConfigured) return null
        val key = "$id|$isShow|$language"
        mutex.withLock { cache[key] }?.let { return it }
        val tmdbId = when {
            id.startsWith("tmdb:") -> id.removePrefix("tmdb:").substringBefore(':').toIntOrNull()
            imdbId != null -> findByImdb(imdbId, isShow)
            id.startsWith("tt") -> findByImdb(id.substringBefore(':'), isShow)
            else -> null
        } ?: return null
        val kind = if (isShow) "tv" else "movie"
        val lang = language.ifBlank { "en-US" }
        val append = if (isShow) "aggregate_credits,images,recommendations,content_ratings,videos"
        else "credits,images,recommendations,release_dates,videos"
        val body = get(
            "$kind/$tmdbId?language=${encodeUrlComponent(lang)}&append_to_response=$append" +
                "&include_image_language=${encodeUrlComponent(lang.substringBefore('-'))},en,null"
        ) ?: return null

        val credits = body.obj(if (isShow) "aggregate_credits" else "credits")
        val cast = credits?.objects("cast").orEmpty().take(30).mapNotNull { c ->
            val name = c.nonBlank("name") ?: return@mapNotNull null
            val role = c.nonBlank("character") ?: c.objects("roles").firstOrNull()?.nonBlank("character")
            TmdbPerson(c.int("id") ?: return@mapNotNull null, name, role, image(c.str("profile_path"), "w185"))
        }
        val crew = credits?.objects("crew").orEmpty()
            .filter { (it.str("job") ?: it.objects("jobs").firstOrNull()?.str("job")) in setOf("Director", "Screenplay", "Writer", "Creator") }
            .distinctBy { it.int("id") }
            .take(8)
            .mapNotNull { c ->
                val name = c.nonBlank("name") ?: return@mapNotNull null
                TmdbPerson(c.int("id") ?: return@mapNotNull null, name, c.str("job") ?: c.objects("jobs").firstOrNull()?.str("job"), image(c.str("profile_path"), "w185"))
            }
        val logos = body.obj("images")?.objects("logos").orEmpty()
        val logo = (logos.firstOrNull { it.str("iso_639_1") == lang.substringBefore('-') } ?: logos.firstOrNull { it.str("iso_639_1") == "en" } ?: logos.firstOrNull())
            ?.str("file_path")
        val certification = if (isShow) {
            body.obj("content_ratings")?.objects("results")?.firstOrNull { it.str("iso_3166_1") == lang.substringAfter('-', "US") }?.nonBlank("rating")
        } else {
            body.obj("release_dates")?.objects("results")?.firstOrNull { it.str("iso_3166_1") == lang.substringAfter('-', "US") }
                ?.objects("release_dates")?.firstNotNullOfOrNull { it.nonBlank("certification") }
        }
        val collection = body.obj("belongs_to_collection")
        val parts = collection?.int("id")?.let { cid -> get("collection/$cid?language=${encodeUrlComponent(lang)}") }
            ?.objects("parts").orEmpty().sortedBy { it.str("release_date") ?: "9999" }.mapNotNull { it.toPreview(false) }

        val crewJobs = credits?.objects("crew").orEmpty()
        fun crewNames(jobs: Set<String>) = crewJobs.filter {
            (it.str("job") ?: it.objects("jobs").firstOrNull()?.str("job")) in jobs
        }.mapNotNull { it.nonBlank("name") }.distinct()
        val runtime = body.int("runtime") ?: body["episode_run_time"]?.let { com.nuvio.shared.data.coerceStringList(it) }?.firstOrNull()?.toIntOrNull()
        val result = TmdbEnrichment(
            tmdbId = tmdbId,
            isShow = isShow,
            logo = image(logo, "w500"),
            backdrop = image(body.str("backdrop_path"), "original"),
            localizedTitle = body.nonBlank(if (isShow) "name" else "title"),
            genres = body.objects("genres").mapNotNull { it.nonBlank("name") },
            rating = body.str("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { ((it * 10).toInt() / 10.0).toString() },
            runtimeMinutes = runtime?.takeIf { it > 0 },
            status = body.nonBlank("status"),
            countries = body.objects("production_countries").mapNotNull { it.nonBlank("iso_3166_1") }
                .ifEmpty { com.nuvio.shared.data.coerceStringList(body["origin_country"]) },
            language = body.nonBlank("original_language"),
            productionCompanies = body.objects("production_companies").mapNotNull { it.nonBlank("name") },
            directors = crewNames(setOf("Director")) + body.objects("created_by").mapNotNull { it.nonBlank("name") },
            writers = crewNames(setOf("Screenplay", "Writer")),
            overview = body.nonBlank("overview"),
            tagline = body.nonBlank("tagline"),
            cast = cast,
            crew = crew,
            recommendations = body.obj("recommendations")?.objects("results").orEmpty().take(20).mapNotNull { it.toPreview(isShow) },
            collectionName = collection?.nonBlank("name"),
            collectionParts = parts,
            certification = certification,
            networks = body.objects("networks").mapNotNull { it.nonBlank("name") }.distinct(),
            trailerYoutubeKeys = body.obj("videos")?.objects("results").orEmpty()
                .filter { it.str("site") == "YouTube" && it.str("type") in setOf("Trailer", "Teaser") }
                .mapNotNull { it.nonBlank("key") }
        )
        mutex.withLock {
            cache[key] = result
            if (cache.size > 50) cache.remove(cache.keys.first())
        }
        return result
    }

    /** One season's episodes (Android useEpisodes: titles, overviews, stills, runtimes). */
    @Throws(Exception::class)
    suspend fun seasonEpisodes(tmdbId: Int, season: Int, language: String): List<TmdbEpisode> =
        get("tv/$tmdbId/season/$season?language=${encodeUrlComponent(language.ifBlank { "en-US" })}")
            ?.objects("episodes").orEmpty().mapNotNull { e ->
                TmdbEpisode(
                    season = season,
                    episode = e.int("episode_number") ?: return@mapNotNull null,
                    title = e.nonBlank("name"),
                    overview = e.nonBlank("overview"),
                    thumbnail = image(e.str("still_path"), "w500"),
                    runtimeMinutes = e.int("runtime")
                )
            }

    @Throws(Exception::class)
    suspend fun person(id: Int, language: String): TmdbPersonDetail? {
        val body = get("person/$id?language=${encodeUrlComponent(language.ifBlank { "en-US" })}&append_to_response=combined_credits") ?: return null
        val credits = (body.obj("combined_credits")?.objects("cast").orEmpty() + body.obj("combined_credits")?.objects("crew").orEmpty())
            .distinctBy { it.int("id") to it.str("media_type") }
            .sortedByDescending { it.str("popularity")?.toDoubleOrNull() ?: 0.0 }
            .take(40)
            .mapNotNull { it.toPreview(it.str("media_type") == "tv") }
        return TmdbPersonDetail(
            id = id,
            name = body.nonBlank("name") ?: return null,
            biography = body.nonBlank("biography"),
            photo = image(body.str("profile_path"), "h632"),
            birthday = body.nonBlank("birthday"),
            placeOfBirth = body.nonBlank("place_of_birth"),
            knownFor = body.nonBlank("known_for_department"),
            credits = credits
        )
    }

    private fun JsonObject.toPreview(isShow: Boolean): MetaPreview? {
        val id = int("id") ?: return null
        val name = nonBlank(if (isShow) "name" else "title") ?: nonBlank("title") ?: nonBlank("name") ?: return null
        return MetaPreview(
            id = "tmdb:$id",
            type = if (isShow) ContentType.SERIES else ContentType.MOVIE,
            name = name,
            poster = image(str("poster_path"), "w342"),
            posterShape = PosterShape.POSTER,
            background = image(str("backdrop_path"), "w1280"),
            logo = null,
            description = str("overview"),
            releaseInfo = (str("release_date") ?: str("first_air_date"))?.take(4),
            imdbRating = str("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { ((it * 10).toInt() / 10.0).toString() },
            genres = emptyList()
        )
    }

    private fun image(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/$size$it" }

    private suspend fun get(path: String): JsonObject? {
        if (!isConfigured) return null
        val isBearer = apiKey.length > 40
        val url = "$API/$path" + if (isBearer) "" else (if ('?' in path) "&" else "?") + "api_key=${encodeUrlComponent(apiKey)}"
        val response = runCatching {
            http.get(url) { if (isBearer) header("Authorization", "Bearer $apiKey") }
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return runCatching { AddonJson.parseToJsonElement(response.bodyAsText()).asObject() }.getOrNull()
    }

    private companion object { const val API = "https://api.themoviedb.org/3" }
}
