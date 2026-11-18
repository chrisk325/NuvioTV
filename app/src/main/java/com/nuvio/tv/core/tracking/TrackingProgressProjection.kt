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
