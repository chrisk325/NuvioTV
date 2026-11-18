package com.nuvio.tv.core.tracking

import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingProgressProjectionTest {
    @Test
    fun `projection keeps eligible local entries without replacing provider entries`() {
        val providerEntry = progress("tt100", 1, 1, 10L, 25L)
        val duplicateLocal = progress("tt100", 1, 1, 30L, 75L)
        val retainedLocal = progress("kitsu:44", 1, 2, 20L, 50L)
        val excludedLocal = progress("tt200", null, null, 40L, 60L)

        val result = mergeProgressProjectionWithRetainedLocal(
            providerEntries = listOf(providerEntry),
            localEntries = listOf(duplicateLocal, retainedLocal, excludedLocal),
            retainsLocalProgress = { contentId -> contentId.startsWith("kitsu:") }
        )

        assertEquals(listOf(retainedLocal, providerEntry), result)
    }

    @Test
    fun `projection stays provider exclusive when no local entries are retained`() {
        val providerEntry = progress("simkl:100", null, null, 10L, 25L)
        val localEntry = progress("kitsu:44", null, null, 20L, 50L)

        val result = mergeProgressProjectionWithRetainedLocal(
            providerEntries = listOf(providerEntry),
            localEntries = listOf(localEntry),
            retainsLocalProgress = { false }
        )

        assertEquals(listOf(providerEntry), result)
    }

    @Test
    fun `projection keeps progress imported from a media server unless the provider tracks the title`() {
        val providerEntry = progress("tt100", 1, 1, 10L, 25L)
        val fromServer = progress("tt200", null, null, 30L, 50L).copy(addonBaseUrl = ServerCatalog.baseUrl("c1"))
        val alsoTracked = progress("tt100", 1, 2, 40L, 50L).copy(addonBaseUrl = ServerCatalog.baseUrl("c1"))
        val local = progress("tt300", null, null, 20L, 50L)

        val result = mergeProgressProjectionWithRetainedLocal(
            providerEntries = listOf(providerEntry),
            localEntries = listOf(fromServer, alsoTracked, local),
            retainsLocalProgress = { false }
        )

        assertEquals(listOf(fromServer, providerEntry), result)
    }

    @Test
    fun `watched episode projection unions only provider retained local episodes`() {
        val providerEpisodes = mapOf("tt100" to setOf(1 to 1))
        val retained = watchedItem("tt100", "series", 1, 2)
        val retainedShow = watchedItem("tt200", "tv", 2, 3)
        val excluded = watchedItem("tt300", "movie", null, null)

        val result = mergeWatchedEpisodeProjection(
            providerEpisodes = providerEpisodes,
            localItems = listOf(retained, retainedShow, excluded),
            retainsLocalWatchedEpisode = { item -> item.contentType != "movie" }
        )

        assertEquals(setOf(1 to 1, 1 to 2), result["tt100"])
        assertEquals(setOf(2 to 3), result["tt200"])
        assertEquals(null, result["tt300"])
    }

    private fun progress(
        contentId: String,
        season: Int?,
        episode: Int?,
        lastWatched: Long,
        position: Long
    ) = WatchProgress(
        contentId = contentId,
        contentType = if (season == null) "movie" else "series",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = 100L,
        lastWatched = lastWatched
    )

    private fun watchedItem(
        contentId: String,
        contentType: String,
        season: Int?,
        episode: Int?
    ) = WatchedItem(
        contentId = contentId,
        contentType = contentType,
        title = contentId,
        season = season,
        episode = episode,
        watchedAt = 1L
    )
}
