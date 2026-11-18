package com.nuvio.shared.tracking

import com.nuvio.shared.data.AddonJson
import com.nuvio.shared.data.asArray
import com.nuvio.shared.data.asObject
import com.nuvio.shared.data.bool
import com.nuvio.shared.data.int
import com.nuvio.shared.data.long
import com.nuvio.shared.data.obj
import com.nuvio.shared.data.objects
import com.nuvio.shared.data.str
import com.nuvio.shared.util.encodeUrlComponent
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * "intro", "recap", "outro" (episodes) or "movie-credits" (movies). Times in seconds.
 * [toPostCredits]: the credits skip stops where a post-credits scene starts.
 */
data class SkipSegment(
    val type: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val toPostCredits: Boolean = false
) {
    val label: String
        get() = when (type) {
            "recap" -> "Skip recap"
            "outro" -> "Skip credits"
            "movie-credits" -> if (toPostCredits) "Skip to post-credits scene" else "Skip credits"
            else -> "Skip intro"
        }
}

/** Port of the Android skip-intro sources: IntroDB (IMDb ids) and AniSkip (anime via MAL ids). */
class SkipSegmentsService(private val http: HttpClient, introDbBaseUrl: String) {
    private val introDb = introDbBaseUrl.trim().trimEnd('/')

    /**
     * Skip segments for one movie or episode, like Android SkipIntroRepository: IntroDB by IMDb id
     * (season / episode for episodes) and AniSkip by MAL id (directly, or from a Kitsu id), merged
     * per category with IntroDB first, so an IntroDB intro and an AniSkip ending can both show.
     */
    @Throws(Exception::class)
    suspend fun segments(query: SkipQuery, episodeLengthSeconds: Double): List<SkipSegment> {
        val fromIntroDb = query.imdbId?.let { imdb ->
            when {
                query.isMovie -> introDbSegments(imdb, null, null)
                query.season != null && query.episode != null -> introDbSegments(imdb, query.season, query.episode)
                else -> null
            }
        }.orEmpty()
        if (query.isMovie) return fromIntroDb
        val malId = query.malId ?: query.kitsuId?.let { malIdForKitsu(it) }
        val fromAniSkip = if (malId != null && query.animeEpisode != null) {
            aniSkip(malId, query.animeEpisode, episodeLengthSeconds)
        } else emptyList()
        return mergeByCategory(fromIntroDb, fromAniSkip)
    }

    private suspend fun introDbSegments(imdbId: String, season: Int?, episode: Int?): List<SkipSegment> {
        if (introDb.isEmpty()) return emptyList()
        val isMovie = season == null || episode == null
        val query = buildString {
            append("$introDb/segments?imdb_id=${encodeUrlComponent(imdbId)}")
            if (isMovie) append("&is_movie=true") else append("&season=$season&episode=$episode")
        }
        val body = fetch(query).asObject() ?: return emptyList()
        return introDbIntervals(body, isMovie = isMovie)
    }

    private suspend fun malIdForKitsu(kitsuId: String): String? {
        val mapping = fetch("https://arm.haglund.dev/api/v2/ids?source=kitsu&id=${encodeUrlComponent(kitsuId)}")
        return (mapping.asObject() ?: mapping.asArray()?.firstOrNull().asObject())?.int("myanimelist")?.toString()
    }

    private suspend fun aniSkip(malId: String, episode: Int, length: Double): List<SkipSegment> {
        val body = fetch(
            "https://api.aniskip.com/v2/skip-times/$malId/$episode?types[]=op&types[]=ed&types[]=recap&episodeLength=${length.toInt()}"
        ).asObject() ?: return emptyList()
        if (body.bool("found") != true) return emptyList()
        return body.objects("results").mapNotNull { r ->
            val interval = r.obj("interval") ?: return@mapNotNull null
            val start = interval.str("startTime")?.toDoubleOrNull() ?: return@mapNotNull null
            val end = interval.str("endTime")?.toDoubleOrNull() ?: return@mapNotNull null
            val type = when (r.str("skipType")) { "op", "mixed-op" -> "intro"; "ed", "mixed-ed" -> "outro"; "recap" -> "recap"; else -> return@mapNotNull null }
            SkipSegment(type, start, end)
        }
    }

    private suspend fun fetch(url: String): kotlinx.serialization.json.JsonElement? {
        val response = runCatching { http.get(url) }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return runCatching { AddonJson.parseToJsonElement(response.bodyAsText()) }.getOrNull()
    }
}

/**
 * IntroDB /segments response → skip buttons, as Android IntroDbSegmentsResponse.toSkipIntervals:
 * episodes get intro / recap / outro; movies get their intro / recap when IntroDB has one, and the
 * end credits, cut short so they never skip over a post-credits scene (the button then jumps to
 * the scene).
 */
internal fun introDbIntervals(body: kotlinx.serialization.json.JsonObject, isMovie: Boolean): List<SkipSegment> {
    fun segment(key: String): Pair<Double, Double>? {
        val seg = body.obj(key) ?: return null
        val start = seg.str("start_sec")?.toDoubleOrNull() ?: seg.long("start_ms")?.div(1000.0) ?: return null
        val end = seg.str("end_sec")?.toDoubleOrNull() ?: seg.long("end_ms")?.div(1000.0) ?: return null
        return if (start >= 0 && end > start && start.isFinite() && end.isFinite()) start to end else null
    }
    if (!isMovie) {
        return listOf("intro", "recap", "outro").mapNotNull { type ->
            segment(type)?.let { (start, end) -> SkipSegment(type, start, end) }
        }
    }
    // Movies: an intro / recap when IntroDB has one (opening titles, "previously…"), then the credits.
    val opening = listOf("intro", "recap").mapNotNull { type ->
        segment(type)?.let { (start, end) -> SkipSegment(type, start, end) }
    }
    val credits = segment("outro") ?: return opening
    val scene = segment("post_credits")
    if (scene != null && scene.first < credits.second && scene.second > credits.first) {
        return opening + if (scene.first > credits.first) listOf(SkipSegment("movie-credits", credits.first, scene.first, toPostCredits = true))
        else emptyList()
    }
    return opening + SkipSegment("movie-credits", credits.first, credits.second, toPostCredits = scene != null && scene.first >= credits.second)
}

/**
 * What to look skip times up by. [imdbId] with [season] / [episode] for IntroDB; [malId] or
 * [kitsuId] with [animeEpisode] (the anime entry's own episode number) for AniSkip.
 */
data class SkipQuery(
    val isMovie: Boolean,
    val imdbId: String?,
    val season: Int?,
    val episode: Int?,
    val kitsuId: String? = null,
    val malId: String? = null,
    val animeEpisode: Int? = null
)

/** First provider wins per category (intro, recap, outro / credits), as Android mergeByPriority. */
internal fun mergeByCategory(vararg results: List<SkipSegment>): List<SkipSegment> {
    val chosen = LinkedHashMap<String, SkipSegment>()
    results.forEach { list ->
        list.forEach { segment ->
            val category = if (segment.type == "movie-credits") "outro" else segment.type
            if (category !in chosen) chosen[category] = segment
        }
    }
    return chosen.values.toList()
}
