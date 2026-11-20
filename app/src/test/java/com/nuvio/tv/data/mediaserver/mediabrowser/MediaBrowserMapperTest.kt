package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.domain.model.ContentType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBrowserMapperTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapper = MediaBrowserMapper("https://media.example.com/jellyfin", "cabc")

    private val movie = json.decodeFromString(
        BaseItem.serializer(),
        """
        {
          "Id": "f1c9a0",
          "Name": "Director's Cut",
          "Type": "Movie",
          "ProductionYear": 2007,
          "CommunityRating": 7.84,
          "RunTimeTicks": 72000000000,
          "ProviderIds": {"Imdb": "tt0111161", "Tmdb": "278", "Tvdb": null},
          "ImageTags": {"Primary": "p1"},
          "BackdropImageTags": ["b1"],
          "MediaSources": [
            {"Id": "ms1", "Name": "4K Remux", "Path": "/media/movie.mkv", "Container": "mkv", "Size": 50000000000,
             "MediaStreams": [{"Type": "Video", "Width": 3840, "Height": 1600, "DisplayTitle": "4K HEVC HDR"}]},
            {"Id": "ms2", "Name": "1080p", "Path": "/media/movie-1080p.mp4", "Container": "mp4"}
          ]
        }
        """
    )

    @Test
    fun mapsPreviewWithNativeIdentityAndUnauthenticatedArtwork() {
        val preview = mapper.preview(movie)!!
        assertEquals(ServerItemRef("cabc", "f1c9a0"), ServerItemRef.parse(preview.id))
        assertEquals(ContentType.MOVIE, preview.type)
        assertEquals("Director's Cut", preview.name)
        assertEquals("2007", preview.releaseInfo)
        assertEquals(7.8f, preview.imdbRating)
        assertTrue(preview.poster!!.startsWith("https://media.example.com/jellyfin/Items/f1c9a0/Images/Primary?tag=p1"))
        assertFalse(preview.poster!!.contains("api_key"))
    }

    @Test
    fun keepsExternalIdsSeparateFromNativeId() {
        val ids = movie.externalIds()
        assertEquals("tt0111161", ids.imdb)
        assertEquals(278L, ids.tmdb)
        assertNull(ids.tvdb)
        assertEquals("tt0111161", mapper.details(movie, emptyList()).imdbId)
    }

    @Test
    fun listsEveryVersionAsCandidate() {
        val candidates = mapper.candidates(movie)
        assertEquals(listOf("ms1", "ms2"), candidates.map { it.target.mediaSourceId })
        assertEquals("4K Remux", candidates.first().title)
        assertEquals("movie.mkv", candidates.first().filename)
    }

    @Test
    fun listsAddonNoticesAsInformationOnly() {
        val withNotices = movie.copy(
            mediaSources = movie.mediaSources + listOf(
                """{"Id": "9b1d", "Type": "Placeholder", "Name": "📊 Statistics\nFound 42 streams in 1.2s"}""",
                """{"Id": "notice", "Type": "Placeholder", "Name": "Torrentio: rate limited"}"""
            ).map { json.decodeFromString(MediaSource.serializer(), it) }
        )
        val candidates = mapper.candidates(withNotices)
        assertEquals(listOf("ms1", "ms2", "9b1d", "notice"), candidates.map { it.target.mediaSourceId })
        assertEquals(listOf(false, false, true, true), candidates.map { it.informational })
        assertEquals("📊 Statistics\nFound 42 streams in 1.2s", candidates[2].title)
        // The playable versions keep their own naming; notices do not count as versions.
        assertEquals("4K Remux", candidates.first().title)
    }

    @Test
    fun skipsTheServersOwnPlaceholders() {
        val placeholders = movie.copy(
            mediaSources = listOf(
                """{"Id": "f1c9a0", "Type": "Placeholder", "Name": "Streams load when played"}""",
                """{"Id": "mk-f1c9a0", "Type": "Placeholder", "Name": "Load the stream list"}""",
                """{"Id": "f1c9a0", "Type": "Placeholder", "Name": "No streams found for this title"}""",
                """{"Id": "blank", "Type": "Placeholder", "Name": " "}"""
            ).map { json.decodeFromString(MediaSource.serializer(), it) }
        )
        assertTrue(mapper.candidates(placeholders).isEmpty())
    }

    @Test
    fun missingItemsHaveNoCandidates() {
        assertTrue(mapper.candidates(movie.copy(locationType = "Virtual")).isEmpty())
    }

    @Test
    fun mapsEpisodesWithNativeIdsAndAvailability() {
        val episode = json.decodeFromString(
            BaseItem.serializer(),
            """{"Id": "e42", "Name": "Pilot", "Type": "Episode", "ParentIndexNumber": 0, "IndexNumber": 1, "LocationType": "Virtual"}"""
        )
        val video = mapper.video(episode)
        assertEquals(ServerItemRef("cabc", "e42").encode(), video.id)
        assertEquals(0, video.season)
        assertEquals(1, video.episode)
        assertEquals(false, video.available)
    }

    @Test
    fun mapsCollectionsAndCollectionLibraries() {
        assertEquals("collection", mapper.preview(movie.copy(type = "BoxSet"))!!.apiType)
        assertEquals("collection", mapper.preview(movie.copy(type = "Folder"))!!.apiType)
        assertEquals(ServerMediaKind.COLLECTION, libraryKind("boxsets"))
        assertNull(libraryKind("music"))
        assertEquals(ServerMediaKind.MIXED, libraryKind(null, "CollectionFolder"))
        assertEquals(ServerMediaKind.MIXED, libraryKind("mixed", "CollectionFolder"))
        assertNull(libraryKind(null, "UserView"))
    }

    @Test
    fun readsEveryKnownProviderId() {
        val ids = movie.copy(
            providerIds = mapOf("Imdb" to "tt1", "AniList" to "21", "Kitsu" to "7442", "MyAnimeList" to "5114", "Zap2It" to "EP1")
        ).externalIds()
        assertEquals("tt1", ids.imdb)
        assertEquals(21L, ids.anilist)
        assertEquals(7442L, ids.kitsu)
        assertEquals(5114L, ids.mal)
        assertNull(ids.trakt)
    }

    @Test
    fun mapsThumbImageToLandscapePoster() {
        assertNull(mapper.preview(movie)!!.landscapePoster)
        val preview = mapper.preview(movie.copy(imageTags = mapOf("Primary" to "p1", "Thumb" to "t1")))!!
        assertTrue(preview.landscapePoster!!.startsWith("https://media.example.com/jellyfin/Items/f1c9a0/Images/Thumb?tag=t1"))
    }

    @Test
    fun usesSeriesThumbAsLandscapePosterForResumedEpisode() {
        val episode = json.decodeFromString(
            BaseItem.serializer(),
            """
            {"Id": "e1", "Name": "Pilot", "Type": "Episode", "SeriesId": "s1", "SeriesName": "Show",
             "SeriesPrimaryImageTag": "sp", "ParentThumbItemId": "s1", "ParentThumbImageTag": "st"}
            """
        )
        val preview = mapper.resumeTitle(episode)!!.preview
        assertTrue(preview.landscapePoster!!.startsWith("https://media.example.com/jellyfin/Items/s1/Images/Thumb?tag=st"))
    }

    @Test
    fun ignoresUnsupportedItemTypes() {
        assertNull(mapper.preview(movie.copy(type = "MusicAlbum")))
    }
}
