package com.nuvio.tv.core.tracking

import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal fun mergeProgressProjectionWithRetainedLocal(
    providerEntries: List<WatchProgress>,
    localEntries: List<WatchProgress>,
    retainsLocalProgress: (String) -> Boolean
): List<WatchProgress> {
    val providerKeys = providerEntries.mapTo(mutableSetOf(), WatchProgress::projectionKey)
    val tracked = providerEntries.mapTo(mutableSetOf(), WatchProgress::contentId)
    return buildList {
        addAll(providerEntries)
        localEntries.forEach { progress ->
            // Progress imported from a media server lives only in the app, so it stays unless the tracker has the title too.
            val retained = retainsLocalProgress(progress.contentId) ||
                (progress.isFromMediaServer && progress.contentId !in tracked)
            if (retained && progress.projectionKey() !in providerKeys) {
                add(progress)
            }
        }
    }.sortedByDescending(WatchProgress::lastWatched)
}

/** True for progress a media server put in the app (its server's continue watching, or a title opened from it). */
internal val WatchProgress.isFromMediaServer: Boolean
    get() = ServerCatalog.isServerAddon(addonBaseUrl)

private fun WatchProgress.projectionKey() = Triple(contentId, season, episode)

internal fun mergeWatchedEpisodeProjection(
    providerEpisodes: Map<String, Set<Pair<Int, Int>>>,
    localItems: List<WatchedItem>,
    retainsLocalWatchedEpisode: (WatchedItem) -> Boolean
): Map<String, Set<Pair<Int, Int>>> {
    val merged = providerEpisodes.mapValuesTo(linkedMapOf()) { (_, episodes) -> episodes.toMutableSet() }
    localItems.forEach { item ->
        val season = item.season
        val episode = item.episode
        if (season != null && episode != null && retainsLocalWatchedEpisode(item)) {
            merged.getOrPut(item.contentId, ::linkedSetOf).add(season to episode)
        }
    }
    return merged
}

/**
 * Adds [external] entries (a media server's continue-watching list) to [entries], one per title. One for a title
 * already shown replaces it only when fresher, so playing it here takes over; one for a title the tracker has
 * ([trackedContentIds]) is left out, as the tracker is the source then.
 */
internal fun mergeExternalProgress(
    entries: List<WatchProgress>,
    external: List<WatchProgress>,
    trackedContentIds: Set<String> = emptySet()
): List<WatchProgress> {
    if (external.isEmpty()) return entries
    val newestShown = entries.groupBy(WatchProgress::contentId).mapValues { (_, items) -> items.maxOf(WatchProgress::lastWatched) }
    val replaced = mutableSetOf<String>()
    val added = external
        .filter { it.contentId !in trackedContentIds }
        .groupBy(WatchProgress::contentId)
        .mapNotNull { (_, items) -> items.maxByOrNull(WatchProgress::lastWatched) }
        .filter { entry ->
            val shown = newestShown[entry.contentId] ?: return@filter true
            (entry.lastWatched > shown).also { fresher -> if (fresher) replaced += entry.contentId }
        }
    if (added.isEmpty()) return entries
    return (entries.filterNot { it.contentId in replaced } + added).sortedByDescending(WatchProgress::lastWatched)
}
