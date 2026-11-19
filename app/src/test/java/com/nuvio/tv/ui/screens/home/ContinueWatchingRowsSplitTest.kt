package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingRowsSplitTest {
    private fun inProgress(id: String, lastWatched: Long) = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = id, contentType = "series", name = id, poster = null, backdrop = null, logo = null,
            videoId = "$id:1:1", season = 1, episode = 1, episodeTitle = null,
            position = 10, duration = 100, lastWatched = lastWatched
        )
    )

    private fun nextUp(
        id: String,
        lastWatched: Long,
        sortTimestamp: Long = lastWatched,
        hasAired: Boolean = true,
        released: String? = null
    ) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = id, contentType = "series", name = id, poster = null, backdrop = null, logo = null,
            videoId = "$id:1:2", season = 1, episode = 2, episodeTitle = null, thumbnail = null,
            released = released, hasAired = hasAired, lastWatched = lastWatched, sortTimestamp = sortTimestamp
        )
    )

    private val resume = inProgress("resume", lastWatched = 500)
    private val older = nextUp("older", lastWatched = 100)
    private val newer = nextUp("newer", lastWatched = 300)
    // Watched long ago, but its next episode aired recently: it became available most recently.
    private val freshlyAired = nextUp("aired", lastWatched = 50, sortTimestamp = 400)
    private val later = nextUp("later", lastWatched = 200, hasAired = false, released = "2999-03-01")
    private val soon = nextUp("soon", lastWatched = 250, hasAired = false, released = "2999-01-01")
    private val items = listOf(resume, older, newer, freshlyAired, later, soon)

    @Test
    fun `without a separate row everything stays in continue watching`() {
        val rows = splitContinueWatchingRows(items, ContinueWatchingSortMode.DEFAULT, separateNextUp = false)
        assertEquals(items, rows.main)
        assertTrue(rows.nextUp.isEmpty())
        assertTrue(rows.upcoming.isEmpty())
    }

    @Test
    fun `next up row holds next episodes ordered by when they became available`() {
        val rows = splitContinueWatchingRows(items, ContinueWatchingSortMode.DEFAULT, separateNextUp = true)
        assertEquals(listOf(resume), rows.main)
        assertEquals(listOf(freshlyAired, newer, older, soon, later), rows.nextUp)
        assertTrue(rows.upcoming.isEmpty())
    }

    @Test
    fun `split upcoming takes unaired episodes out of the next up row`() {
        val rows = splitContinueWatchingRows(items, ContinueWatchingSortMode.SPLIT_UPCOMING, separateNextUp = true)
        assertEquals(listOf(resume), rows.main)
        assertEquals(listOf(freshlyAired, newer, older), rows.nextUp)
        assertEquals(listOf(soon, later), rows.upcoming)
    }

    @Test
    fun `split upcoming alone keeps aired next episodes in continue watching`() {
        val rows = splitContinueWatchingRows(items, ContinueWatchingSortMode.SPLIT_UPCOMING, separateNextUp = false)
        assertEquals(listOf(resume, older, newer, freshlyAired), rows.main)
        assertTrue(rows.nextUp.isEmpty())
        assertEquals(listOf(soon, later), rows.upcoming)
    }

    @Test
    fun `ui state helpers round trip the rows`() {
        val rows = splitContinueWatchingRows(items, ContinueWatchingSortMode.SPLIT_UPCOMING, separateNextUp = true)
        val state = HomeUiState().withContinueWatchingRows(rows)
        assertTrue(state.hasContinueWatchingRows(rows))
        assertEquals(items.size, state.allContinueWatchingItems.size)
        assertEquals("next_up_section", state.continueWatchingRowOf(newer).first)
        assertEquals("upcoming_section", state.continueWatchingRowOf(soon).first)
        assertEquals("continue_watching", state.continueWatchingRowOf(resume).first)
    }
}
