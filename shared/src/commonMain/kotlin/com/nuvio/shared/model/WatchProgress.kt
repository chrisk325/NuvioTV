package com.nuvio.shared.model

import kotlinx.serialization.Serializable

/** Playback position for one video, persisted on device. */
@Serializable
data class WatchProgress(
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long
) {
    val fraction: Double
        get() = if (durationMs <= 0) 0.0 else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)

    val isCompleted: Boolean
        get() = durationMs > 0 && fraction >= COMPLETED_THRESHOLD

    /** Worth offering "Resume" for: past the opening seconds and not finished. */
    val isResumable: Boolean
        get() = positionMs >= MIN_RESUME_MS && !isCompleted

    companion object {
        const val COMPLETED_THRESHOLD = 0.92
        const val MIN_RESUME_MS = 30_000L
    }
}

/** A title the user saved to their on-device library. */
@Serializable
data class SavedItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val posterShape: String = "poster",
    val savedAtMs: Long
) {
    fun toPreview(): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = name,
        poster = poster,
        posterShape = PosterShape.fromString(posterShape),
        background = background,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
