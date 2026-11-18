package com.nuvio.tv.data.mediaserver

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class ServerUserStateProjection @Inject constructor(
    private val catalog: ServerCatalog,
    private val repository: ServerRepository,
    private val resumeImport: ServerResumeImport,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            catalog.detailsLoaded.collect { details ->
                try {
                    apply(details)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to apply server user state", error)
                }
            }
        }
    }

    suspend fun apply(details: ServerItemDetails) {
        val meta = details.meta
        val ref = ServerItemRef.parse(meta.id) ?: return
        val connection = repository.connection(ref.connectionId)?.takeIf { it.importWatchState } ?: return
        val isSeries = meta.type == ContentType.SERIES
        val profileId = profileManager.activeProfileId.value

        // Progress comes from the server's continue-watching list, kept apart from the app's synced progress.
        if (details.userStates.any { !it.played && it.positionMs > 0L }) resumeImport.refreshSoon(connection.id)

        val states = details.userStates.filter { !isSeries || (it.season != null && it.episode != null) }
        val watchedEpisodes = if (isSeries) watchedItemsPreferences.getWatchedEpisodesForContent(meta.id, profileId).first() else emptySet()
        val movieWatched = !isSeries && watchedItemsPreferences.observeAllItems(profileId).first()
            .any { it.contentId == meta.id && it.season == null && it.episode == null }
        fun ServerUserState.isWatchedLocally(): Boolean =
            if (isSeries) (season!! to episode!!) in watchedEpisodes else movieWatched

        val (played, unplayed) = states.partition { it.played }
        watchedItemsPreferences.markAsWatchedBatch(
            played.filterNot { it.isWatchedLocally() }.map { it.toWatchedItem(meta, isSeries) },
            profileId
        )
        val unwatched = unplayed.filter { it.isWatchedLocally() }
        if (isSeries) {
            watchedItemsPreferences.unmarkAsWatchedBatch(meta.id, unwatched.map { it.season!! to it.episode!! }, profileId)
        } else if (unwatched.isNotEmpty()) {
            watchedItemsPreferences.unmarkAsWatched(meta.id, profileId = profileId)
        }
    }

    private fun ServerUserState.toWatchedItem(meta: Meta, isSeries: Boolean) = WatchedItem(
        contentId = meta.id,
        contentType = meta.apiType,
        title = meta.name,
        season = season.takeIf { isSeries },
        episode = episode.takeIf { isSeries },
        watchedAt = lastPlayedEpochMs ?: System.currentTimeMillis(),
        poster = meta.poster
    )

    private companion object {
        const val TAG = "ServerUserState"
    }
}
