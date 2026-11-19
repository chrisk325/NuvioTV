package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerNextUpEntry
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A media server's Next Up entry and the card it shows as. */
internal data class ServerNextUpCard(
    val entry: ServerNextUpEntry,
    val item: ContinueWatchingItem.NextUp
)

internal fun ServerNextUpEntry.toNextUpItem(): ContinueWatchingItem.NextUp = ContinueWatchingItem.NextUp(
    NextUpInfo(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = videoId,
        season = season ?: 1,
        episode = episode ?: 1,
        episodeTitle = episodeTitle,
        episodeDescription = overview,
        thumbnail = thumbnail,
        released = released,
        hasAired = isEpisodeReleaseAired(released) ?: true,
        lastWatched = availableAt,
        sortTimestamp = availableAt,
        releaseTimestamp = parseEpisodeReleaseInstant(released)?.toEpochMilli(),
        seedSeason = season,
        seedEpisode = episode,
        serverItemRef = serverItemId
    )
)

/**
 * Puts the media servers' Next Up [cards] among the app's own Continue Watching [items].
 *
 * The server's entry for a show replaces the app's own Next Up card for it, unless the app's card is further
 * along: the app knows of episodes played here that the server may not have heard of yet. A show in progress
 * keeps its Continue Watching card instead. A dismissed entry shows nothing but still holds the show, so the
 * app's card for the same episode doesn't take its place. With [cards] null (no server offers Next Up), the
 * app's own cards stay as they are.
 */
internal fun mergeServerNextUp(
    items: List<ContinueWatchingItem>,
    cards: List<ServerNextUpCard>?
): List<ContinueWatchingItem> {
    val local = items.filterNot { it is ContinueWatchingItem.NextUp && it.info.fromServer }
    if (cards == null) return local
    val inProgress = local.filterIsInstance<ContinueWatchingItem.InProgress>()
        .mapTo(mutableSetOf()) { it.progress.contentId }
    val localNextUp = local.filterIsInstance<ContinueWatchingItem.NextUp>()
    val replaced = mutableSetOf<ContinueWatchingItem.NextUp>()
    val shown = mutableListOf<ContinueWatchingItem>()
    val claimed = mutableSetOf<String>()
    cards.forEach { card ->
        val ids = (card.entry.matchIds + card.entry.contentId).toSet()
        if (ids.any { it in inProgress } || ids.any { it in claimed }) return@forEach
        claimed += ids
        val mine = localNextUp.filter { it.info.contentId in ids }
        if (mine.any { it.info.isFurtherThan(card.item.info) }) return@forEach
        replaced += mine
        if (!card.entry.dismissed) shown += card.item
    }
    return local.filterNot { it in replaced } + shown
}

private fun NextUpInfo.isFurtherThan(other: NextUpInfo): Boolean =
    season > other.season || (season == other.season && episode > other.episode)

/**
 * The home rows for [items], with the servers' Next Up merged in. The app's own items are kept, so a change to
 * the servers' lists can be applied without running the whole Continue Watching pipeline again.
 */
internal fun HomeViewModel.continueWatchingRowsFor(
    items: List<ContinueWatchingItem>,
    mode: ContinueWatchingSortMode
): ContinueWatchingRows {
    val local = items.filterNot { it is ContinueWatchingItem.NextUp && it.info.fromServer }
    val cards = serverNextUpCards
    val merged = if (cards == null) local else sortContinueWatchingItems(mergeServerNextUp(local, cards), mode)
    return splitContinueWatchingRows(merged, mode, separateNextUpRow).copy(local = local)
}

/** [state] showing [rows]; remembers the app's own items behind them for [recomposeContinueWatchingRows]. */
internal fun HomeViewModel.applyContinueWatchingRows(state: HomeUiState, rows: ContinueWatchingRows): HomeUiState {
    rows.local?.let { cwLocalContinueWatchingItems = it }
    return state.withContinueWatchingRows(rows)
}

/** Splits what is on screen again, after a row setting or the servers' Next Up changed. */
internal fun HomeViewModel.recomposeContinueWatchingRows() {
    val mode = continueWatchingSortMode
    _uiState.update { state ->
        val items = cwLocalContinueWatchingItems ?: state.allContinueWatchingItems
        applyContinueWatchingRows(state, continueWatchingRowsFor(sortContinueWatchingItems(items, mode), mode))
    }
}

/** Drops removed items from the kept app items too, so a recompose doesn't bring them back. */
internal fun HomeViewModel.forgetLocalContinueWatching(removed: (ContinueWatchingItem) -> Boolean) {
    cwLocalContinueWatchingItems = cwLocalContinueWatchingItems?.filterNot(removed)
}

/** The server entry behind a card, when the card came from a server's Next Up. */
internal fun HomeViewModel.serverNextUpEntryFor(item: ContinueWatchingItem): ServerNextUpEntry? {
    val ref = (item as? ContinueWatchingItem.NextUp)?.info?.serverItemRef ?: return null
    return serverNextUpCards?.firstOrNull { it.entry.serverItemId == ref }?.entry
}

/** The server entry for a show's Next Up card, by the ids the removal names it with. */
internal fun HomeViewModel.serverNextUpEntryFor(contentId: String, season: Int?, episode: Int?): ServerNextUpEntry? =
    serverNextUpCards?.firstOrNull { card ->
        !card.entry.dismissed &&
            card.item.info.contentId == contentId &&
            (season == null || card.entry.season == season) &&
            (episode == null || card.entry.episode == episode)
    }?.entry

/**
 * Follows the servers' Next Up lists: shows their entries at once, then fills them in with the show's metadata
 * from the app's addons (artwork, episode thumbnail and description), as the app's own cards are.
 */
internal fun HomeViewModel.observeServerNextUp() {
    viewModelScope.launch {
        serverNextUpSource.entries.collectLatest { entries ->
            if (entries == null) {
                if (serverNextUpCards != null) {
                    serverNextUpCards = null
                    recomposeContinueWatchingRows()
                }
                return@collectLatest
            }
            val cards = entries.map { entry ->
                ServerNextUpCard(entry, serverNextUpEnriched[entry.videoId]?.withEntryState(entry) ?: entry.toNextUpItem())
            }
            serverNextUpCards = cards
            recomposeContinueWatchingRows()
            val missing = cards.filter { !serverNextUpEnriched.containsKey(it.entry.videoId) && !it.entry.dismissed }
            if (missing.isEmpty()) return@collectLatest
            val enriched = withContext(Dispatchers.IO) { enrichServerNextUp(missing) }
            if (enriched.isEmpty()) return@collectLatest
            serverNextUpCards = cards.map { card -> enriched[card.entry.videoId]?.let { card.copy(item = it) } ?: card }
            recomposeContinueWatchingRows()
        }
    }
}

private fun ContinueWatchingItem.NextUp.withEntryState(entry: ServerNextUpEntry): ContinueWatchingItem.NextUp =
    copy(info = info.copy(lastWatched = entry.availableAt, sortTimestamp = entry.availableAt))

private suspend fun HomeViewModel.enrichServerNextUp(cards: List<ServerNextUpCard>): Map<String, ContinueWatchingItem.NextUp> {
    val permits = Semaphore(SERVER_NEXT_UP_ENRICH_CONCURRENCY)
    return coroutineScope {
        cards.map { card ->
            async {
                permits.withPermit {
                    try {
                        enrichServerNextUpCard(card)?.let { card.entry.videoId to it }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(HomeViewModel.TAG, "Filling in server Next Up for ${card.entry.contentId} failed", error)
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }.also { serverNextUpEnriched.putAll(it) }
}

private suspend fun HomeViewModel.enrichServerNextUpCard(card: ServerNextUpCard): ContinueWatchingItem.NextUp? {
    val info = card.item.info
    // A show kept on the server has no addon metadata to fill it in with.
    if (ServerItemRef.isServerId(info.contentId)) return null
    val meta = resolveCwMeta(
        WatchProgress(
            contentId = info.contentId,
            contentType = info.contentType,
            name = info.name,
            poster = info.poster,
            backdrop = info.backdrop,
            logo = info.logo,
            videoId = info.videoId,
            season = info.season,
            episode = info.episode,
            episodeTitle = info.episodeTitle,
            position = 0L,
            duration = 0L,
            lastWatched = info.lastWatched
        )
    ) ?: return null
    val video = meta.videos.firstOrNull { it.id == info.videoId }
        ?: meta.videos.firstOrNull { it.season == card.entry.season && it.episode == card.entry.episode }
    val released = info.released ?: video?.released
    return card.item.copy(
        info = info.copy(
            name = meta.name.ifBlank { info.name },
            poster = meta.poster ?: info.poster,
            backdrop = meta.backdropUrl ?: info.backdrop,
            logo = meta.logo ?: info.logo,
            episodeTitle = info.episodeTitle ?: video?.title,
            episodeDescription = info.episodeDescription ?: video?.overview,
            thumbnail = video?.thumbnail ?: info.thumbnail,
            released = released,
            hasAired = isEpisodeReleaseAired(released) ?: info.hasAired,
            imdbRating = meta.imdbRating,
            genres = meta.genres,
            releaseInfo = meta.releaseInfo,
            contentLanguage = meta.language
        )
    )
}

private const val SERVER_NEXT_UP_ENRICH_CONCURRENCY = 4
