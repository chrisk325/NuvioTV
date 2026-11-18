package com.nuvio.tv.data.mediaserver

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerResumeImportTest {
    private var now = 5_000L

    private class Harness(
        val provider: FakeServerProvider,
        val repository: ServerRepository,
        val connection: ServerConnection,
        val catalog: ServerCatalog,
        val progress: WatchProgressRepository,
        val preferences: WatchProgressPreferences,
        val import: ServerResumeImport
    )

    private fun harness(stored: List<WatchProgress> = emptyList()): Harness {
        val provider = FakeServerProvider()
        val (repository, connection) = fakeServerRepository(provider)
        val addonRepository = mockk<AddonRepository> { every { getInstalledAddons() } returns flowOf(emptyList()) }
        val catalog = ServerCatalog(mockk<Context>(relaxed = true), repository, addonRepository)
        val progress = mockk<WatchProgressRepository>(relaxed = true)
        val preferences = mockk<WatchProgressPreferences>(relaxed = true) {
            every { observeAllRawProgress(any()) } returns flowOf(stored)
            every { getEpisodeProgress(any(), any(), any(), any()) } returns flowOf(null)
            every { getProgress(any(), any()) } returns flowOf(null)
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val import = ServerResumeImport(
            repository = repository,
            catalog = catalog,
            watchProgressRepository = progress,
            watchProgressPreferences = preferences,
            profileManager = profiles,
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowMs = { now }
        )
        return Harness(provider, repository, connection, catalog, progress, preferences, import)
    }

    private fun preview(id: String, type: ContentType, name: String) = MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun show(connectionId: String) = ServerTitle(
        preview = preview(ServerItemRef(connectionId, "500").encode(), ContentType.SERIES, "One Piece"),
        externalIds = TrackingExternalIds(imdb = "tt0388629", tmdb = 37854)
    )

    private fun episodeState(connectionId: String, played: Boolean = false, lastPlayed: Long? = 1_000L) = ServerUserState(
        videoId = ServerItemRef(connectionId, "ep1").encode(),
        positionMs = 600_000L,
        durationMs = 1_440_000L,
        played = played,
        lastPlayedEpochMs = lastPlayed,
        season = 1,
        episode = 4,
        title = "Luffy's Past!"
    )

    private fun ServerResumeImport.entriesFor(harness: Harness, resume: List<ServerResume>): List<WatchProgress> {
        val connection = harness.repository.connection(harness.connection.id)!!
        return runBlocking { entries(connection.id, harness.catalog.presenter(connection), resume) }
    }

    @Test
    fun aServerShowResumesItsEpisodeFromTheServer() {
        val harness = harness()
        val id = harness.connection.id

        val entry = harness.import.entriesFor(harness, listOf(ServerResume(show(id), episodeState(id)))).single()

        assertEquals(ServerItemRef(id, "500").encode(), entry.contentId)
        assertEquals("series", entry.contentType)
        assertEquals(ServerItemRef(id, "ep1").encode(), entry.videoId)
        assertEquals(1 to 4, entry.season to entry.episode)
        assertEquals("Luffy's Past!", entry.episodeTitle)
        assertEquals(600_000L to 1_440_000L, entry.position to entry.duration)
        assertEquals(1_000L, entry.lastWatched)
        assertEquals(ServerCatalog.baseUrl(id), entry.addonBaseUrl)
    }

    @Test
    fun aShowWithAddonMetadataResumesAsACatalogEpisode() {
        val harness = harness()
        val id = harness.connection.id
        harness.repository.setCatalogMetadata(id, true)

        val entry = harness.import.entriesFor(harness, listOf(ServerResume(show(id), episodeState(id)))).single()

        assertEquals("tmdb:37854", entry.contentId)
        assertEquals("tmdb:37854:1:4", entry.videoId)
    }

    @Test
    fun aMovieResumesItself() {
        val harness = harness()
        val id = harness.connection.id
        val movie = ServerTitle(preview(ServerItemRef(id, "7").encode(), ContentType.MOVIE, "Heat"))
        val state = episodeState(id).copy(videoId = movie.preview.id, season = null, episode = null)

        val entry = harness.import.entriesFor(harness, listOf(ServerResume(movie, state))).single()

        assertEquals(movie.preview.id, entry.videoId)
        assertNull(entry.season)
    }

    @Test
    fun skipsFinishedAndUnstartedItems() {
        val harness = harness()
        val id = harness.connection.id
        val finished = episodeState(id, played = true)
        val unstarted = episodeState(id).copy(positionMs = 0L)

        val entries = harness.import.entriesFor(harness, listOf(ServerResume(show(id), finished), ServerResume(show(id), unstarted)))

        assertTrue(entries.isEmpty())
    }

    @Test
    fun keepsTheFirstSeenTimeWhenTheServerSendsNoLastPlayedDate() {
        val harness = harness()
        val id = harness.connection.id
        val resume = listOf(ServerResume(show(id), episodeState(id, lastPlayed = null)))

        val first = harness.import.entriesFor(harness, resume).single()
        now = 9_000L
        val second = harness.import.entriesFor(harness, resume).single()

        assertEquals(5_000L, first.lastWatched)
        assertEquals(5_000L, second.lastWatched)
    }

    private fun imported(connectionId: String, videoId: String, updatedAt: Long = 1_000L, season: Int? = 1, episode: Int? = 4) =
        WatchProgress(
            contentId = "show",
            contentType = "series",
            name = "One Piece",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 10L,
            duration = 100L,
            lastWatched = updatedAt,
            addonBaseUrl = ServerCatalog.baseUrl(connectionId)
        )

    @Test
    fun dropsWhatTheServerNoLongerLists() {
        val import = harness().import

        assertTrue(import.isStale(imported("c1", "ep1"), "c1", listed = setOf("ep2"), fetchedAtMs = 5_000L))
        assertFalse(import.isStale(imported("c1", "ep2"), "c1", listed = setOf("ep2"), fetchedAtMs = 5_000L))
    }

    @Test
    fun leavesOtherProgressAlone() {
        val import = harness().import
        val listed = emptySet<String>()

        // Another server's, one played here (saved without the server as its source), and one being played now.
        assertFalse(import.isStale(imported("c2", "ep1"), "c1", listed, fetchedAtMs = 5_000L))
        assertFalse(import.isStale(imported("c1", "ep1").copy(addonBaseUrl = null), "c1", listed, 5_000L))
        assertFalse(import.isStale(imported("c1", "ep1", updatedAt = 6_000L), "c1", listed, 5_000L))
    }

    @Test
    fun importsTheServersListAndDropsWhatItFinished() {
        val finished = imported("cfake", "gone", season = 1, episode = 2)
        val harness = harness(stored = listOf(finished))
        val id = harness.connection.id
        harness.repository.setImportWatchState(id, true)
        harness.provider.resume = listOf(ServerResume(show(id), episodeState(id)))

        runBlocking { harness.import.import(harness.repository.connection(id)!!) }

        coVerify {
            harness.progress.saveProgressBatch(
                match { list -> list.single().videoId == ServerItemRef(id, "ep1").encode() },
                syncRemote = false
            )
        }
        coVerify { harness.preferences.removeProgressBatch("show", listOf(1 to 2), 1) }
    }

    @Test
    fun importingServersDropTheirOwnContinueWatchingRow() = runBlocking {
        val harness = harness()
        val id = harness.connection.id
        assertTrue("nothing is imported until the setting is on", harness.import.connections().isEmpty())
        assertTrue(harness.catalog.addons.first().single().catalogs.any { it.id == "resume" })

        harness.repository.setImportWatchState(id, true)

        assertEquals(listOf(id), harness.import.connections().map { it.id })
        assertTrue(harness.catalog.addons.first().single().catalogs.none { it.id == "resume" })
    }
}
