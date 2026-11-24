package com.nuvio.tv.core.poster

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.NextUpInfo
import com.nuvio.tv.ui.screens.home.withAddonLandscapePosters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AddonArtworkTest {
    private fun artwork(poster: String? = "addon-poster", landscape: String? = "addon-landscape") = AddonArtwork(
        poster = poster,
        posterShape = PosterShape.POSTER,
        landscapePoster = landscape,
        background = "addon-bg",
        logo = "addon-logo"
    )

    private fun recommendation(id: String, rawPoster: String?) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = "$id-backdrop",
        posterShape = PosterShape.LANDSCAPE,
        background = "$id-backdrop",
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        landscapePoster = "$id-backdrop",
        rawPosterUrl = rawPoster
    )

    @Test
    fun `library entry takes the addon poster and keeps the old one as fallback`() {
        val entry = LibraryEntry(
            id = "tt1", type = "movie", name = "Movie", poster = "trakt-poster", background = null, logo = null,
            description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null
        )
        val result = entry.withAddonArtwork(artwork())
        assertEquals("addon-poster", result.poster)
        assertEquals("trakt-poster", result.rawPosterUrl)
        assertEquals("addon-landscape", result.landscapePoster)
        assertEquals("addon-bg", result.background)
    }

    @Test
    fun `library entry is unchanged without artwork`() {
        val entry = LibraryEntry(
            id = "tt1", type = "movie", name = "Movie", poster = "trakt-poster", background = null, logo = null,
            description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null
        )
        assertSame(entry, entry.withAddonArtwork(null))
    }

    @Test
    fun `a row with an addon poster turns portrait and falls back to source posters`() {
        val resolved = recommendation("tmdb:1", rawPoster = "tmdb-1-poster")
        val unresolved = recommendation("tmdb:2", rawPoster = "tmdb-2-poster")
        val rows = listOf(resolved, unresolved).withAddonPosters(
            mapOf(addonArtworkKey("movie", "tmdb:1") to artwork())
        )
        assertEquals(listOf(PosterShape.POSTER, PosterShape.POSTER), rows.map { it.posterShape })
        assertEquals("addon-poster", rows[0].poster)
        assertEquals("tmdb-1-poster", rows[0].rawPosterUrl)
        assertEquals("tmdb-2-poster", rows[1].poster)
    }

    @Test
    fun `a row without addon posters stays landscape`() {
        val items = listOf(recommendation("tmdb:1", rawPoster = "tmdb-1-poster"))
        val rows = items.withAddonPosters(
            mapOf(addonArtworkKey("movie", "tmdb:1") to artwork(poster = null))
        )
        assertEquals(PosterShape.LANDSCAPE, rows.single().posterShape)
        assertEquals("addon-landscape", rows.single().landscapePoster)
    }

    @Test
    fun `artwork keys treat tv and series alike`() {
        assertEquals(addonArtworkKey("series", "tt1"), addonArtworkKey("tv", "tt1"))
    }

    @Test
    fun `continue watching cards get the addon landscape poster except server next up`() {
        val inProgress = ContinueWatchingItem.InProgress(
            WatchProgress(
                contentId = "tt1", contentType = "movie", name = "Movie", poster = null, backdrop = null, logo = null,
                videoId = "tt1", season = null, episode = null, episodeTitle = null,
                position = 10, duration = 100, lastWatched = 1
            )
        )
        val nextUp = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tt2", contentType = "series", name = "Show", poster = null, backdrop = null, logo = null,
                videoId = "tt2:1:2", season = 1, episode = 2, episodeTitle = null, thumbnail = null,
                released = null, hasAired = true, lastWatched = 1, sortTimestamp = 1
            )
        )
        val result = listOf(inProgress, nextUp).withAddonLandscapePosters(
            mapOf("tt1" to "landscape-1", "tt2" to "landscape-2")
        )
        assertEquals("landscape-1", (result[0] as ContinueWatchingItem.InProgress).customLandscapePoster)
        assertEquals("landscape-2", (result[1] as ContinueWatchingItem.NextUp).customLandscapePoster)
    }

    @Test
    fun `a pattern without shape keeps the addon landscape poster`() {
        val item = ContinueWatchingItem.InProgress(
            WatchProgress(
                contentId = "tt1", contentType = "movie", name = "Movie", poster = "p", backdrop = null, logo = null,
                videoId = "tt1", season = null, episode = null, episodeTitle = null,
                position = 10, duration = 100, lastWatched = 1
            ),
            customLandscapePoster = "addon-landscape"
        )
        val result = item.withCustomPosterUrl("https://posters.example/{imdb_id}.jpg") as ContinueWatchingItem.InProgress
        assertEquals("addon-landscape", result.customLandscapePoster)
    }
}
