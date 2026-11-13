package com.nuvio.shared

import com.nuvio.shared.data.StremioClient
import com.nuvio.shared.data.parseCatalogResponse
import com.nuvio.shared.data.parseManifest
import com.nuvio.shared.data.parseMetaResponse
import com.nuvio.shared.data.parseStreamResponse
import com.nuvio.shared.model.ContentType
import com.nuvio.shared.model.PosterShape
import com.nuvio.shared.util.canonicalizeAddonUrl
import com.nuvio.shared.util.encodeUrlComponent
import com.nuvio.shared.util.isReleaseInFuture
import com.nuvio.shared.util.parseReleaseEpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingTest {
    @Test
    fun manifestParsesMixedResourceAndExtraForms() {
        val addon = parseManifest(Fixtures.cinemetaManifest, Fixtures.CINEMETA)
        assertEquals("com.linvo.cinemeta", addon.id)
        assertEquals(listOf("catalog", "meta", "addon_catalog"), addon.resources.map { it.name })
        assertEquals(listOf("movie", "series"), addon.resources.first().types)
        assertEquals(3, addon.catalogs.size)
        // "year" has a required extra, so it's not a home catalog
        assertEquals(listOf("top", "top"), addon.homeCatalogs.map { it.id })
        assertEquals(2, addon.searchCatalogs.size)
        assertTrue(addon.catalogs[1].supportsExtra("skip"))
        assertEquals("${Fixtures.CINEMETA}/manifest.json", addon.manifestUrl)

        val stream = parseManifest(Fixtures.streamManifest, Fixtures.TORRENTIO)
        assertTrue(stream.supportsResource("stream", "movie", "tt123"))
        assertTrue(stream.supportsResource("stream", "series", "kitsu:1"))
        assertFalse(stream.supportsResource("stream", "movie", "tmdb:1"))
        assertFalse(stream.supportsResource("meta", "movie", "tt1"))
    }

    @Test
    fun catalogDropsInvalidAndDuplicateItems() {
        val items = parseCatalogResponse(Fixtures.catalog, "movie", Fixtures.CINEMETA)
        assertEquals(listOf("tt1", "tt2"), items.map { it.id })
        assertEquals("7.4", items[0].imdbRating)
        assertNull(items[1].imdbRating)
        assertEquals(ContentType.MOVIE, items[1].type) // falls back to catalog type
        assertEquals(PosterShape.LANDSCAPE, items[1].posterShape)
        assertEquals(Fixtures.CINEMETA, items[0].sourceAddonBaseUrl)
    }

    @Test
    fun metaCoercesPeopleAndEpisodes() {
        val meta = parseMetaResponse(Fixtures.seriesMeta)!!
        assertEquals(listOf("Jane"), meta.director)
        assertEquals(listOf("A", "B"), meta.writer)
        assertEquals(listOf("yt1"), meta.trailerYtIds)
        assertEquals(4, meta.videos.size)
        assertEquals(2, meta.videos[1].episode)
        assertEquals(1, meta.videos[1].season)
        assertEquals("Episode 2", meta.videos[1].title)
        assertEquals(45, meta.videos[0].runtimeMinutes)
        assertEquals(listOf(1, 2, 0), meta.seasons())
        assertEquals(listOf("tt9:1:1", "tt9:1:2"), meta.watchableEpisodes().map { it.id })
        assertTrue(meta.isSeriesLike)
        assertEquals("tt9", meta.defaultVideoId)
    }

    @Test
    fun streamsSanitizeHeadersAndClassify() {
        val streams = parseStreamResponse(Fixtures.streams, "Streamer", null)
        assertEquals(3, streams.size)
        val http = streams[0]
        assertEquals(mapOf("Referer" to "https://ref"), http.requestHeaders)
        assertEquals("https://cdn/1.mkv", http.directPlaybackUrl)
        assertEquals(1, http.subtitles.size)
        assertEquals(1080, http.qualityRank)
        val torrent = streams[1]
        assertTrue(torrent.isTorrent())
        assertFalse(torrent.isDirectlyPlayable)
        assertEquals(2, torrent.fileIdx)
        assertEquals(2160, torrent.qualityRank)
        assertTrue(streams[2].isExternal())
    }

    @Test
    fun urlsMatchAndroidShapes() {
        assertEquals("https://a.io/x", canonicalizeAddonUrl(" https://a.io/x/manifest.json/ "))
        assertEquals("https://a.io/x?k=1", canonicalizeAddonUrl("https://a.io/x/manifest.json?k=1"))
        assertEquals("https://a.io/x", canonicalizeAddonUrl("stremio://a.io/x/manifest.json"))
        assertEquals("a%20b%2Fc%3A%C3%A9*-_.", encodeUrlComponent("a b/c:é*-_."))
        assertEquals(
            "https://a.io/catalog/movie/top.json",
            StremioClient.buildCatalogUrl("https://a.io/", "movie", "top", 0, emptyMap())
        )
        assertEquals(
            "https://a.io/catalog/movie/top/skip=100.json",
            StremioClient.buildCatalogUrl("https://a.io", "movie", "top", 100, emptyMap())
        )
        assertEquals(
            "https://a.io/catalog/movie/top/search=the%20office&skip=20.json?k=1",
            StremioClient.buildCatalogUrl("https://a.io?k=1", "movie", "top", 20, mapOf("search" to "the office"))
        )
        assertEquals(
            "https://a.io/stream/series/tt1%3A1%3A2.json?k=1",
            StremioClient.buildResourceUrl("https://a.io/?k=1", "stream", "series", "tt1:1:2")
        )
    }

    @Test
    fun releaseDates() {
        assertEquals(0L, parseReleaseEpochMs("1970-01-01"))
        assertEquals(86_400_000L + 3_600_000L, parseReleaseEpochMs("1970-01-02T01:00:00.000Z"))
        assertEquals(951_782_400_000L, parseReleaseEpochMs("2000-02-29"))
        assertNull(parseReleaseEpochMs("TBA"))
        assertTrue(isReleaseInFuture("2999-01-01"))
        assertFalse(isReleaseInFuture("2001-01-01"))
        assertFalse(isReleaseInFuture(null))
    }
}
