package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.mediaserver.ServerNextUpEntry
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerNextUpMergeTest {
    private fun local(id: String, season: Int, episode: Int) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = id, contentType = "series", name = id, poster = null, backdrop = null, logo = null,
            videoId = "$id:$season:$episode", season = season, episode = episode, episodeTitle = null, thumbnail = null,
            lastWatched = 10, sortTimestamp = 10, seedSeason = season, seedEpisode = episode - 1
        )
    )

    private fun inProgress(id: String) = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = id, contentType = "series", name = id, poster = null, backdrop = null, logo = null,
            videoId = "$id:1:1", season = 1, episode = 1, episodeTitle = null,
            position = 10, duration = 100, lastWatched = 50
        )
    )

    private fun server(
        id: String,
        season: Int,
        episode: Int,
        matchIds: List<String> = listOf(id),
        dismissed: Boolean = false
    ): ServerNextUpCard {
        val entry = ServerNextUpEntry(
            connectionId = "c1", contentId = id, name = id, videoId = "$id:$season:$episode", season = season,
            episode = episode, availableAt = 100, serverItemId = "srv1:c1:$id-$season-$episode",
            matchIds = matchIds, dismissed = dismissed
        )
        return ServerNextUpCard(entry, entry.toNextUpItem())
    }

    @Test
    fun `without servers the app's own cards stay`() {
        val items = listOf(inProgress("a"), local("b", 1, 2))
        assertEquals(items, mergeServerNextUp(items, null))
    }

    @Test
    fun `server entries replace the app's cards and add shows the app doesn't know`() {
        val merged = mergeServerNextUp(
            listOf(local("b", 1, 2)),
            listOf(server("b", 1, 2), server("c", 2, 1))
        )
        val nextUp = merged.filterIsInstance<ContinueWatchingItem.NextUp>()
        assertEquals(listOf("b", "c"), nextUp.map { it.info.contentId })
        assertTrue(nextUp.all { it.info.fromServer })
    }

    @Test
    fun `the app's card wins when it is further along`() {
        val further = local("b", 1, 5)
        val merged = mergeServerNextUp(listOf(further), listOf(server("b", 1, 4)))
        assertEquals(listOf(further), merged)
    }

    @Test
    fun `shows in progress keep their continue watching card`() {
        val resume = inProgress("a")
        assertEquals(listOf(resume), mergeServerNextUp(listOf(resume), listOf(server("a", 1, 2))))
    }

    @Test
    fun `shows match across id spaces`() {
        val merged = mergeServerNextUp(
            listOf(local("tt0944947", 3, 9)),
            listOf(server("tmdb:1399", 3, 9, matchIds = listOf("tmdb:1399", "tt0944947")))
        )
        assertEquals(listOf("tmdb:1399"), merged.map { (it as ContinueWatchingItem.NextUp).info.contentId })
    }

    @Test
    fun `a dismissed entry hides the show without the app's card coming back`() {
        val merged = mergeServerNextUp(listOf(local("b", 1, 2)), listOf(server("b", 1, 2, dismissed = true)))
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `server cards already on screen are replaced, not doubled`() {
        val first = mergeServerNextUp(emptyList(), listOf(server("c", 2, 1)))
        val again = mergeServerNextUp(first, listOf(server("c", 2, 2)))
        assertEquals(listOf("c:2:2"), again.map { (it as ContinueWatchingItem.NextUp).info.videoId })
    }
}
