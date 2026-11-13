package com.nuvio.shared.model

import com.nuvio.shared.util.isReleaseInFuture

// Ported from app/.../domain/model/MetaPreview.kt and Meta.kt
data class MetaPreview(
    val id: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    /** IMDb rating as text ("7.4"), kept as String so Swift doesn't need boxed floats. */
    val imdbRating: String?,
    val genres: List<String>,
    val runtime: String? = null,
    val released: String? = null,
    val imdbId: String? = null,
    val landscapePoster: String? = null,
    val trailerYtIds: List<String> = emptyList(),
    val sourceAddonBaseUrl: String? = null
) {
    val apiType: String
        get() = type.toApiString(rawType)

    val backdropUrl: String?
        get() = background ?: landscapePoster ?: poster
}

data class Meta(
    val id: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val status: String? = null,
    val imdbRating: String?,
    val genres: List<String>,
    val runtime: String?,
    val director: List<String>,
    val writer: List<String> = emptyList(),
    val cast: List<String>,
    val castMembers: List<MetaCastMember> = emptyList(),
    val videos: List<Video>,
    val productionCompanies: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val ageRating: String? = null,
    val country: String?,
    val awards: String?,
    val language: String?,
    val links: List<MetaLink>,
    val trailerYtIds: List<String> = emptyList(),
    val imdbId: String? = null,
    val released: String? = null,
    val landscapePoster: String? = null,
    val behaviorHints: MetaBehaviorHints? = null
) {
    val apiType: String
        get() = type.toApiString(rawType)

    val backdropUrl: String?
        get() = background ?: landscapePoster ?: poster

    val isSeriesLike: Boolean
        get() = isSeriesLikeType(apiType) || videos.any { it.season != null && it.episode != null }

    /** Whether this title is marked watched as a whole, like a movie, instead of per episode. */
    fun isWatchedAsOneItem(): Boolean =
        apiType.equals("movie", ignoreCase = true) || (!isSeriesLikeType(apiType) && videos.isEmpty())

    /**
     * Non-special episodes (season > 0), excluding seasons whose first episode is not yet
     * available (flag or future release date).
     */
    fun watchableEpisodes(): List<Video> = videos.watchableEpisodes()

    /** Season numbers in display order: regular seasons first, specials (0) last. */
    fun seasons(): List<Int> {
        val all = videos.mapNotNull { it.season }.distinct().sorted()
        return all.filter { it > 0 } + all.filter { it == 0 }
    }

    fun episodesForSeason(season: Int): List<Video> =
        videos.filter { it.season == season }.sortedBy { it.episode ?: Int.MAX_VALUE }

    /** Video id used to request streams for a movie-like title. */
    val defaultVideoId: String
        get() = behaviorHints?.defaultVideoId ?: if (videos.size == 1) videos.first().id else id

    fun toPreview(sourceAddonBaseUrl: String? = null): MetaPreview = MetaPreview(
        id = id,
        type = type,
        rawType = rawType,
        name = name,
        poster = poster,
        posterShape = posterShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        runtime = runtime,
        released = released,
        imdbId = imdbId,
        landscapePoster = landscapePoster,
        trailerYtIds = trailerYtIds,
        sourceAddonBaseUrl = sourceAddonBaseUrl
    )
}

fun List<Video>.watchableEpisodes(): List<Video> {
    val candidates = filter { it.season != null && it.episode != null && it.season > 0 }
    val unavailableSeasons = candidates.groupBy { it.season }
        .filter { (_, eps) ->
            val first = eps.minByOrNull { it.episode ?: Int.MAX_VALUE } ?: return@filter false
            first.available == false || isReleaseInFuture(first.released)
        }.keys
    return candidates
        .filter { it.season !in unavailableSeasons }
        .filter { it.available != false && !isReleaseInFuture(it.released) }
}

data class MetaCastMember(
    val name: String,
    val character: String? = null,
    val photo: String? = null,
    val tmdbId: Int? = null
)

data class Video(
    val id: String,
    val title: String,
    val released: String?,
    val thumbnail: String?,
    val streams: List<Stream> = emptyList(),
    val season: Int?,
    val episode: Int?,
    val overview: String?,
    /** Episode runtime in minutes, 0 when unknown. */
    val runtimeMinutes: Int = 0,
    val available: Boolean? = null
) {
    val isReleased: Boolean
        get() = available != false && !isReleaseInFuture(released)

    /** "S1 E2"-style label, or null for non-episodic videos. */
    val episodeLabel: String?
        get() {
            val s = season ?: return null
            val e = episode ?: return null
            return "S$s E$e"
        }
}

data class MetaLink(
    val name: String,
    val category: String,
    val url: String
)

data class MetaBehaviorHints(
    val defaultVideoId: String? = null,
    val hasScheduledVideos: Boolean? = null
)
