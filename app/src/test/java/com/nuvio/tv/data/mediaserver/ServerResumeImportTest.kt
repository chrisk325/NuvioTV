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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerResumeImportTest {
    private var now = 5_000L
    private val store = object : ServerResumeStore {
        val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

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
            store = store,
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

    private fun ServerResumeImport.entriesFor(
        harness: Harness,
        resume: List<ServerResume>,
        previous: ServerResumeCache = ServerResumeCache()
    ): List<WatchProgress> {
        val connection = harness.repository.connection(harness.connection.id)!!
        return runBlocking { entries(connection.id, harness.catalog.presenter(connection), resume, previous) }
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
        val kept = harness.import.diff(ServerResumeCache(), listOf(first), complete = true, fetchedAtMs = now).cache
        now = 9_000L
        val second = harness.import.entriesFor(harness, resume, kept).single()

        assertEquals(5_000L, first.lastWatched)
        assertEquals(5_000L, second.lastWatched)
    }

    @Test
    fun appliesOnlyWhatChangedSinceTheLastImport() {
        val import = harness().import
        val first = listOf(imported("c1", "ep1"), imported("c1", "ep2"))
        val kept = import.diff(ServerResumeCache(), first, complete = true, fetchedAtMs = 1_000L)
        assertEquals("the first import brings everything", listOf("ep1", "ep2"), kept.changed.map { it.videoId })

        val moved = imported("c1", "ep2").copy(position = 50L, lastWatched = 2_000L)
        val next = import.diff(kept.cache, listOf(imported("c1", "ep1"), moved, imported("c1", "ep3")), true, 3_000L)

        assertEquals(listOf("ep2", "ep3"), next.changed.map { it.videoId })
        assertTrue(next.dropped.isEmpty())
        assertEquals(3_000L, next.cache.fetchedAtMs)
    }

    @Test
    fun leavesContinueWatchingAloneWhenNothingChanged() {
        val import = harness().import
        val entries = listOf(imported("c1", "ep1"))
        val kept = import.diff(ServerResumeCache(), entries, complete = true, fetchedAtMs = 1_000L).cache

        val again = import.diff(kept, entries, complete = true, fetchedAtMs = 5_000L)

        assertTrue(again.changed.isEmpty())
        assertTrue(again.dropped.isEmpty())
    }

    @Test
    fun dropsOnlyWhatTheServerListedBefore() {
        val import = harness().import
        val kept = import.diff(ServerResumeCache(), listOf(imported("c1", "ep1"), imported("c1", "ep2")), true, 1_000L).cache

        val next = import.diff(kept, listOf(imported("c1", "ep2")), complete = true, fetchedAtMs = 2_000L)
        val cutOff = import.diff(kept, listOf(imported("c1", "ep2")), complete = false, fetchedAtMs = 2_000L)

        assertEquals(setOf("ep1"), next.dropped)
        assertEquals(setOf("ep2"), next.cache.items.keys)
        assertTrue("a full list may have cut ep1 off", cutOff.dropped.isEmpty())
        assertEquals(setOf("ep1", "ep2"), cutOff.cache.items.keys)
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
    fun importsTheServersListThenDropsOnlyWhatItStoppedListing() {
        val id = "cfake"
        val gone = ServerItemRef(id, "ep2").encode()
        // Progress the server put here for the episode it stops listing, and some the server never listed.
        val stored = listOf(
            imported(id, gone, season = 1, episode = 2),
            imported(id, "unrelated", season = 3, episode = 1).copy(contentId = "other")
        )
        val harness = harness(stored)
        harness.repository.setImportWatchState(id, true)
        val connection = harness.repository.connection(id)!!
        val second = episodeState(id).copy(videoId = gone, episode = 2)
        harness.provider.resume = listOf(ServerResume(show(id), episodeState(id)), ServerResume(show(id), second))

        runBlocking { harness.import.import(connection) }

        coVerify(exactly = 1) {
            harness.progress.saveProgressBatch(match { list -> list.size == 2 }, syncRemote = false)
        }
        coVerify(exactly = 0) { harness.preferences.removeProgressBatch(any(), any(), any()) }

        // Nothing changed: Continue watching is left alone.
        runBlocking { harness.import.import(connection) }
        coVerify(exactly = 1) { harness.progress.saveProgressBatch(any(), any()) }

        harness.provider.resume = listOf(ServerResume(show(id), episodeState(id)))
        runBlocking { harness.import.import(connection) }

        coVerify(exactly = 1) { harness.preferences.removeProgressBatch("show", listOf(1 to 2), 1) }
        coVerify(exactly = 1) { harness.progress.saveProgressBatch(any(), any()) }
    }

    @Test
    fun keepsTheLastListAcrossRestartsUntilForgotten() {
        now = 1_000_000L
        val id = "cfake"
        val first = harness()
        first.repository.setImportWatchState(id, true)
        first.provider.resume = listOf(ServerResume(show(id), episodeState(id)))
        runBlocking { first.import.import(first.repository.connection(id)!!) }

        // A new import, as after a restart, reads the kept list: the server isn't asked again so soon.
        val restarted = harness()
        restarted.repository.setImportWatchState(id, true)
        assertNull(restarted.import.refresh())

        restarted.import.forget(id)
        assertTrue(store.values.isEmpty())
        assertNotNull(restarted.import.refresh())
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
