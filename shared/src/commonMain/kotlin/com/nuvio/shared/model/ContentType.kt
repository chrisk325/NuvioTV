package com.nuvio.shared.model

// Ported from app/.../domain/model/ContentType.kt
enum class ContentType {
    MOVIE,
    SERIES,
    CHANNEL,
    TV,
    UNKNOWN;

    companion object {
        fun fromString(value: String): ContentType = when (value.trim().lowercase()) {
            "movie" -> MOVIE
            "series" -> SERIES
            "channel" -> CHANNEL
            "tv" -> TV
            else -> UNKNOWN
        }
    }

    fun toApiString(fallbackType: String? = null): String = when (this) {
        MOVIE -> "movie"
        SERIES -> "series"
        CHANNEL -> "channel"
        TV -> "tv"
        UNKNOWN -> fallbackType
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "movie"
    }
}

/**
 * Whether items of this add-on type are shows with episodes. Items of any other type, such as
 * "movie" or an add-on's "youtube", are one video and are marked watched as a whole.
 */
fun isSeriesLikeType(apiType: String?): Boolean = when (apiType?.trim()?.lowercase()) {
    "series", "tv", "anime" -> true
    else -> false
}

enum class PosterShape {
    POSTER,
    LANDSCAPE,
    SQUARE;

    companion object {
        fun fromString(value: String?): PosterShape = when (value?.lowercase()) {
            "landscape" -> LANDSCAPE
            "square" -> SQUARE
            else -> POSTER
        }
    }

    /** Width divided by height. */
    fun aspectRatio(): Float = when (this) {
        POSTER -> 0.675f
        LANDSCAPE -> 1.78f
        SQUARE -> 1f
    }
}
