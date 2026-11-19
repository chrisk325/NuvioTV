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
import com.nuvio.tv.core.tracking.mergeExternalProgress
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
        val kept = harness.import.next(ServerResumeCache(), listOf(first), complete = true, fetchedAtMs = now)
        now = 9_000L
        val second = harness.import.entriesFor(harness, resume, kept).single()

        assertEquals(5_000L, first.lastWatched)
        assertEquals("an unchanged item comes back identical, so Continue watching doesn't change", first, second)
    }

    private fun imported(
        connectionId: String,
        videoId: String,
        position: Long = 10L,
        updatedAt: Long = 1_000L,
        season: Int? = 1,
        episode: Int? = 4
    ) = WatchProgress(
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
        position = position,
        duration = 100L,
        lastWatched = updatedAt,
        addonBaseUrl = ServerCatalog.baseUrl(connectionId)
    )

    @Test
    fun keepsTheServersListAsItIs() {
        val import = harness().import
        val listed = listOf(imported("c1", "ep1"), imported("c1", "ep2"))

        val kept = import.next(ServerResumeCache(), listed, complete = true, fetchedAtMs = 3_000L)

        assertEquals(listed, kept.shown)
        assertEquals(3_000L, kept.fetchedAtMs)
    }

    @Test
    fun dropsWhatTheServerNoLongerListsUnlessTheListWasCutOff() {
        val import = harness().import
        val kept = import.next(ServerResumeCache(), listOf(imported("c1", "ep1"), imported("c1", "ep2")), true, 1_000L)

        val complete = import.next(kept, listOf(imported("c1", "ep2")), complete = true, fetchedAtMs = 2_000L)
        val cutOff = import.next(kept, listOf(imported("c1", "ep2")), complete = false, fetchedAtMs = 2_000L)

        assertEquals(listOf("ep2"), complete.shown.map { it.videoId })
        assertEquals(setOf("ep1", "ep2"), cutOff.shown.mapTo(mutableSetOf()) { it.videoId })
    }

    @Test
    fun aDismissedItemStaysOutUntilTheServerChangesIt() {
        val import = harness().import
        val first = import.next(ServerResumeCache(), listOf(imported("c1", "ep1"), imported("c1", "ep2")), true, 1_000L)
        val dismissed = first.copy(dismissed = setOf("ep1"))

        val unchanged = import.next(dismissed, listOf(imported("c1", "ep1"), imported("c1", "ep2")), true, 2_000L)
        val watchedFurther = import.next(
            dismissed,
            listOf(imported("c1", "ep1", position = 40L, updatedAt = 1_500L), imported("c1", "ep2")),
            complete = true,
            fetchedAtMs = 2_000L
        )

        assertEquals(listOf("ep2"), unchanged.shown.map { it.videoId })
        assertEquals(listOf("ep1", "ep2"), watchedFurther.shown.map { it.videoId })
        assertTrue(watchedFurther.dismissed.isEmpty())
    }

    @Test
    fun aDismissedItemTheServerDroppedIsForgotten() {
        val import = harness().import
        val dismissed = import.next(ServerResumeCache(), listOf(imported("c1", "ep1")), true, 1_000L)
            .copy(dismissed = setOf("ep1"))

        val next = import.next(dismissed, emptyList(), complete = true, fetchedAtMs = 2_000L)

        assertTrue(next.dismissed.isEmpty())
    }

    @Test
    fun keepsTheServersListApartFromTheAppsOwnProgress() {
        val own = imported("c1", "ep1", updatedAt = 5_000L).copy(addonBaseUrl = null)
        val movie = imported("c1", "m1", season = null, episode = null).copy(contentId = "movie", contentType = "movie")
        val fromServer = listOf(imported("c1", "ep1", updatedAt = 1_000L), movie)

        val merged = mergeExternalProgress(listOf(own), fromServer)

        // Playing the show here is newer, so it wins; the movie only the server has.
        assertEquals(listOf(own, movie), merged)
        assertEquals(
            "with a tracker that has the movie, the tracker is the source for it",
            listOf(own),
            mergeExternalProgress(listOf(own), fromServer, trackedContentIds = setOf("movie"))
        )
    }

    @Test
    fun showsTheServersListWithoutStoringIt() {
        val harness = harness()
        val id = harness.connection.id
        harness.repository.setImportWatchState(id, true)
        harness.provider.resume = listOf(ServerResume(show(id), episodeState(id)))

        runBlocking { harness.import.import(harness.repository.connection(id)!!) }

        verify { harness.progress.setExternalProgress(match { list -> list.single().videoId == ServerItemRef(id, "ep1").encode() }) }
        coVerify(exactly = 0) { harness.progress.saveProgressBatch(any(), any()) }
    }

    @Test
    fun aRemovedItemStaysOutOfContinueWatching() {
        val harness = harness()
        val id = harness.connection.id
        harness.repository.setImportWatchState(id, true)
        harness.provider.resume = listOf(ServerResume(show(id), episodeState(id)))
        val connection = harness.repository.connection(id)!!
        runBlocking { harness.import.import(connection) }
        val shownEach = mutableListOf<List<WatchProgress>>()
        verify { harness.progress.setExternalProgress(capture(shownEach)) }
        val shown = shownEach.last()
        assertEquals(1, shown.size)

        harness.import.dismiss(shown)
        // Asked again, the server reports it unchanged, so it stays out.
        now += 10 * 60_000L
        runBlocking { harness.import.import(connection) }

        shownEach.clear()
        verify { harness.progress.setExternalProgress(capture(shownEach)) }
        assertTrue(shownEach.last().isEmpty())
    }

    @Test
    fun aRemovedItemIsTakenOutOfTheServersListToo() {
        val harness = harness()
        val id = harness.connection.id
        harness.repository.setImportWatchState(id, true)
        harness.provider.resume = listOf(ServerResume(show(id), episodeState(id)))
        runBlocking { harness.import.import(harness.repository.connection(id)!!) }
        val shown = mutableListOf<List<WatchProgress>>()
        verify { harness.progress.setExternalProgress(capture(shown)) }

        harness.import.dismiss(shown.last())

        assertEquals(listOf("ep1"), harness.provider.resumeRemovals)
    }

    @Test
    fun aRemovedShowWithAddonMetadataClearsTheServersEpisode() {
        val harness = harness()
        val id = harness.connection.id
        harness.repository.setImportWatchState(id, true)
        harness.repository.setCatalogMetadata(id, true)
        harness.provider.resume = listOf(ServerResume(show(id), episodeState(id)))
        runBlocking { harness.import.import(harness.repository.connection(id)!!) }
        val shown = mutableListOf<List<WatchProgress>>()
        verify { harness.progress.setExternalProgress(capture(shown)) }
        assertEquals("tmdb:37854:1:4", shown.last().single().videoId)

        harness.import.dismiss(shown.last())

        assertEquals(listOf("ep1"), harness.provider.resumeRemovals)
    }

    @Test
    fun takesOutWhatEarlierVersionsStoredWithTheSyncedProgress() {
        val id = "cfake"
        val stored = listOf(
            imported(id, ServerItemRef(id, "ep1").encode(), season = 1, episode = 4),
            imported(id, "played-here", season = 1, episode = 5).copy(contentId = "other", addonBaseUrl = null)
        )
        val harness = harness(stored)
        harness.repository.setImportWatchState(id, true)

        harness.import.show()

        coVerify(exactly = 1) { harness.preferences.removeProgressBatch("show", listOf(1 to 4), 1) }
        coVerify(exactly = 0) { harness.preferences.removeProgressBatch("other", any(), any()) }
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
