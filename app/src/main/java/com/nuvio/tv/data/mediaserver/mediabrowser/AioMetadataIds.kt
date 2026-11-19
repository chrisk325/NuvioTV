package com.nuvio.tv.data.mediaserver.mediabrowser

/**
 * Reads the item ids AIOMetadata's Jellyfin API hands out.
 *
 * AIOMetadata packs a title's Stremio id into its 32-hex Jellyfin id, so the id itself says which show and
 * episode it is: byte 0 is 0xA1, byte 1 holds the kind (high nibble) and id type (low nibble), byte 2 the media
 * type, bytes 3..8 the numeric id, 9..10 the season and 11..12 the episode (0xFFFF for none). Ids it can't pack
 * are hashed instead (byte 0 0xB2) and can't be read here; [decode] returns null for those and for ids of any
 * other server.
 */
internal object AioMetadataIds {
    data class Decoded(
        val kind: Kind,
        val mediaType: String,
        /** The Stremio id of the title, e.g. `tt0944947`, `tmdb:1399` or `kitsu:1376`. */
        val base: String,
        val season: Int?,
        val episode: Int?
    ) {
        /** The Stremio video id an episode plays as: `<base>:<season>:<episode>`, or `<base>:<episode>` without a season. */
        val videoId: String?
            get() = when {
                kind != Kind.EPISODE || episode == null -> null
                season == null -> "$base:$episode"
                else -> "$base:$season:$episode"
            }
    }

    enum class Kind { MOVIE, SERIES, SEASON, EPISODE }

    private const val PACKED = 0xA1
    private const val NONE16 = 0xFFFF

    private val KINDS = mapOf(1 to Kind.MOVIE, 2 to Kind.SERIES, 3 to Kind.SEASON, 4 to Kind.EPISODE)
    private val ID_PREFIXES = mapOf(
        1 to "tt",
        2 to "tmdb:",
        3 to "tvdb:",
        4 to "kitsu:",
        5 to "mal:",
        6 to "anilist:",
        7 to "anidb:",
        8 to "tvmaze:"
    )
    private val MEDIA_TYPES = mapOf(
        1 to "movie",
        2 to "series",
        3 to "anime",
        4 to "anime.movie",
        5 to "anime.series",
        6 to "collection",
        7 to "Trakt"
    )
    private val HEX32 = Regex("^[0-9a-f]{32}$")

    fun decode(raw: String?): Decoded? {
        val hex = raw?.replace("-", "")?.lowercase() ?: return null
        if (!HEX32.matches(hex)) return null
        val bytes = IntArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16) }
        if (bytes[0] != PACKED) return null
        val kind = KINDS[bytes[1] shr 4] ?: return null
        val prefix = ID_PREFIXES[bytes[1] and 0x0F] ?: return null
        val mediaType = MEDIA_TYPES[bytes[2]] ?: return null
        var numeric = 0L
        for (index in 3..8) numeric = (numeric shl 8) or bytes[index].toLong()
        val season = (bytes[9] shl 8) or bytes[10]
        val episode = (bytes[11] shl 8) or bytes[12]
        val base = if (prefix == "tt") "tt" + numeric.toString().padStart(7, '0') else "$prefix$numeric"
        return when (kind) {
            Kind.MOVIE, Kind.SERIES -> Decoded(kind, mediaType, base, null, null)
            Kind.SEASON -> if (season == NONE16) null else Decoded(kind, mediaType, base, season, null)
            Kind.EPISODE -> if (episode == NONE16) {
                null
            } else {
                Decoded(kind, mediaType, base, season.takeIf { it != NONE16 }, episode)
            }
        }
    }
}
