package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.mediaserver.mediabrowser.AioMetadataIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerNextUpImportTest {
    /** Packs an id the way AIOMetadata's idsCodec.ts tryPack does. */
    private fun packed(kind: Int, idType: Int, media: Int, numeric: Long, season: Int? = null, episode: Int? = null): String {
        val bytes = IntArray(16)
        bytes[0] = 0xA1
        bytes[1] = (kind shl 4) or idType
        bytes[2] = media
        for (index in 0 until 6) bytes[3 + index] = ((numeric shr (8 * (5 - index))) and 0xFF).toInt()
        val s = season ?: 0xFFFF
        val e = episode ?: 0xFFFF
        bytes[9] = s shr 8; bytes[10] = s and 0xFF
        bytes[11] = e shr 8; bytes[12] = e and 0xFF
        bytes[13] = 1
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private val gotSeries = packed(kind = 2, idType = 1, media = 2, numeric = 944947)
    private val gotEpisode = packed(kind = 4, idType = 1, media = 2, numeric = 944947, season = 3, episode = 9)
    private val animeSeries = packed(kind = 2, idType = 4, media = 3, numeric = 1376)
    private val animeEpisode = packed(kind = 4, idType = 4, media = 3, numeric = 1376, episode = 12)

    @Test
    fun `decodes AIOMetadata ids into Stremio ids`() {
        val episode = AioMetadataIds.decode(gotEpisode)!!
        assertEquals(AioMetadataIds.Kind.EPISODE, episode.kind)
        assertEquals("tt0944947", episode.base)
        assertEquals("tt0944947:3:9", episode.videoId)

        assertEquals("tt0944947", AioMetadataIds.decode(gotSeries)!!.base)
        assertEquals("kitsu:1376:12", AioMetadataIds.decode(animeEpisode)!!.videoId)
        assertEquals("tmdb:1399", AioMetadataIds.decode(packed(kind = 2, idType = 2, media = 2, numeric = 1399))!!.base)
        // Dashed GUID spelling of the same id.
        val dashed = gotEpisode.let { "${it.take(8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.drop(20)}" }
        assertEquals("tt0944947:3:9", AioMetadataIds.decode(dashed.uppercase())!!.videoId)
    }

    @Test
    fun `ids it can't read are left alone`() {
        assertNull(AioMetadataIds.decode("b2" + "0".repeat(30))) // hashed
        assertNull(AioMetadataIds.decode("5f1a6c2e9b8d4e7f8a1b2c3d4e5f6a7b")) // a plain Jellyfin id
        assertNull(AioMetadataIds.decode("not-an-id"))
        assertNull(AioMetadataIds.decode(null))
    }

    private fun show(id: String, name: String, ids: TrackingExternalIds = TrackingExternalIds()) = ServerTitle(
        MetaPreview(
            id = id, type = ContentType.SERIES, name = name, poster = "poster-$name", posterShape = PosterShape.POSTER,
            background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        ids
    )

    private fun listed(show: ServerTitle, episodeItemId: String, seriesItemId: String?, season: Int?, episode: Int?, at: Long?) =
        ServerNextUp(show, episodeItemId, seriesItemId, season, episode, "Episode $episode", null, "2020-01-01T00:00:00Z", null, at)

    @Test
    fun `AIOMetadata entries play by their Stremio ids`() {
        val got = show(ServerItemRef("c1", gotSeries).encode(), "GoT", TrackingExternalIds(imdb = "tt0944947", tmdb = 1399))
        val anime = show(ServerItemRef("c1", animeSeries).encode(), "Anime")
        val entries = nextUpEntries(
            connectionId = "c1",
            present = ServerTitle::preview,
            listed = listOf(
                listed(got, gotEpisode, gotSeries, 3, 9, at = 2_000L),
                listed(anime, animeEpisode, animeSeries, 1, 12, at = null)
            ),
            previous = emptyList(),
            nowMs = 9_000L
        )
        assertEquals(listOf("tt0944947", "kitsu:1376"), entries.map { it.contentId })
        assertEquals(listOf("tt0944947:3:9", "kitsu:1376:12"), entries.map { it.videoId })
        assertEquals(listOf(2_000L, 9_000L), entries.map { it.availableAt })
        assertEquals(ServerItemRef("c1", gotEpisode).encode(), entries[0].serverItemId)
        assertTrue("tmdb:1399" in entries[0].matchIds)
        // Absolute numbering has no season.
        assertNull(entries[1].season)
    }

    @Test
    fun `other servers play their own episodes and keep dismissals and first-seen times`() {
        val plain = show(ServerItemRef("c2", "series1").encode(), "Show")
        val first = nextUpEntries("c2", ServerTitle::preview, listOf(listed(plain, "ep5", "series1", 1, 5, at = null)), emptyList(), nowMs = 100L)
        assertEquals(ServerItemRef("c2", "ep5").encode(), first.single().videoId)

        val again = nextUpEntries(
            "c2", ServerTitle::preview,
            listOf(listed(plain, "ep5", "series1", 1, 5, at = null)),
            first.map { it.copy(dismissed = true) },
            nowMs = 500L
        )
        assertEquals(100L, again.single().availableAt)
        assertTrue(again.single().dismissed)

        // The server moved the show on: a new episode, no longer dismissed.
        val moved = nextUpEntries(
            "c2", ServerTitle::preview,
            listOf(listed(plain, "ep6", "series1", 1, 6, at = null)),
            again,
            nowMs = 900L
        )
        assertEquals(false, moved.single().dismissed)
    }
}
