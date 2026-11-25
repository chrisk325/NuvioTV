package com.nuvio.shared

import com.nuvio.shared.model.Video
import com.nuvio.shared.util.parseReleaseEpochMs

/** A next-season premiere joins Upcoming this many days ahead (Android's 7-day window). */
internal const val UPCOMING_NEW_SEASON_WINDOW_DAYS = 7L
private const val DAY_MS = 86_400_000L

/**
 * The Upcoming row's episode for a show (port of Android resolveNextUpVideoFromMeta with unaired
 * next-up shown): in the show's episodes (specials left out, in season / episode order), the one
 * after the seed — the furthest episode watched — or the seed itself when [inclusive] (a tracker
 * already named the next episode). An episode of the same season counts whenever it airs; the
 * first episodes of the next season only within [UPCOMING_NEW_SEASON_WINDOW_DAYS] days, as on
 * Android. Returns the episode and its air time when it hasn't aired yet; null when the next
 * episode has already aired (that's Next Up, not Upcoming), has no date, or there is none.
 */
internal fun upcomingEpisodeAfter(
    videos: List<Video>,
    seedSeason: Int,
    seedEpisode: Int,
    seedVideoId: String?,
    inclusive: Boolean,
    nowMs: Long
): Pair<Video, Long>? {
    val episodes = videos
        .filter { it.season != null && it.episode != null && it.season != 0 }
        .sortedWith(compareBy<Video>({ it.season }, { it.episode }))
    if (episodes.isEmpty()) return null
    var index = episodes.indexOfFirst { it.season == seedSeason && it.episode == seedEpisode }
    if (index < 0 && !seedVideoId.isNullOrBlank()) index = episodes.indexOfFirst { it.id == seedVideoId }
    // Android's fallback: a season-1 number past season 1 counts through the whole show.
    if (index < 0 && seedSeason == 1 && seedEpisode > 0 && episodes.map { it.season }.toSet().size > 1 &&
        seedEpisode - 1 in episodes.indices
    ) index = seedEpisode - 1
    if (index < 0) return null
    // The season of the episode watched last (for a named next episode, the one before it).
    val watchedSeason = if (inclusive && index > 0) episodes[index - 1].season else episodes[index].season
    val today = nowMs.floorDiv(DAY_MS)
    for (video in episodes.drop(if (inclusive) index else index + 1)) {
        val released = parseReleaseEpochMs(video.released)
        if (video.season != watchedSeason) {
            // Next season: needs a date; shown within the window before its premiere.
            if (released == null) continue
            if (released <= nowMs) return null
            if (released.floorDiv(DAY_MS) - today <= UPCOMING_NEW_SEASON_WINDOW_DAYS) return video to released
            continue
        }
        if (released == null) {
            if (video.available == false) continue
            return null
        }
        return if (released > nowMs) video to released else null
    }
    return null
}
