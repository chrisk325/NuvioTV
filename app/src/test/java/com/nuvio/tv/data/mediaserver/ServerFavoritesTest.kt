package com.nuvio.tv.data.mediaserver

import android.content.Context
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerFavoritesTest {
    private var failureNotices = 0

    private class Harness(
        val provider: FakeServerProvider,
        val connection: ServerConnection,
        val favorites: ServerFavorites,
        val catalog: ServerCatalog
    )

    private fun harness(provider: FakeServerProvider = provider()): Harness {
        val (repository, connection) = fakeServerRepository(provider)
        val tmdbService = mockk<TmdbService> {
            coEvery { ensureTmdbId(any(), any(), any()) } returns null
            coEvery { tmdbToImdb(any(), any()) } returns null
        }
        val metaRepository = mockk<MetaRepository> {
            every { getCachedMeta(any(), any()) } returns null
            every { getMetaFromAllAddons(any(), any()) } returns flowOf()
        }
        val addonRepository = mockk<AddonRepository> { every { getInstalledAddons() } returns flowOf(emptyList()) }
        val context = mockk<Context>(relaxed = true) { every { getString(any()) } returns "Favorites" }
        val favorites = ServerFavorites(
            repository = repository,
            matcher = ServerMatcher(repository, tmdbService, metaRepository),
            scope = CoroutineScope(Dispatchers.Default),
            notifyFailure = { failureNotices++ }
        )
        return Harness(provider, connection, favorites, ServerCatalog(context, repository, addonRepository))
    }

    private fun provider(favorites: Boolean = true, catalogs: Boolean = false) =
        FakeServerProvider(favorites = favorites, catalogs = catalogs).apply {
            indexedIds["7"] = TrackingExternalIds(imdb = "tt0111161", tmdb = 278)
            indexedIds[FakeServerProvider.SHOW_ID] = TrackingExternalIds(imdb = "tt0944947", tmdb = 1399)
        }

    private fun <T> onServer(block: suspend () -> T): T = runBlocking { withTimeout(15_000L) { block() } }

    @Test
    fun favouritesACatalogMovieThroughTheServerCopy() {
        val harness = harness()

        val result = onServer { harness.favorites.setFavorite("tt0111161", "movie", favorite = true) }

        assertEquals(ServerFavoriteResult.ADDED, result)
        assertEquals(listOf("7" to true), harness.provider.favoriteChanges)
        assertTrue(harness.favorites.isFavorite("tt0111161", "movie"))
        assertEquals(1, harness.favorites.state.value.writes)
    }

    @Test
    fun favouritesTheShowRatherThanAnEpisode() {
        val harness = harness()

        val result = onServer { harness.favorites.setFavorite("tt0944947", "series", favorite = true) }

        assertEquals(ServerFavoriteResult.ADDED, result)
        assertEquals(listOf(FakeServerProvider.SHOW_ID to true), harness.provider.favoriteChanges)
    }

    @Test
    fun readsTheServersFavouritesByAnyOfTheirIds() {
        val harness = harness(provider().apply { favoriteIds += "7" })

        onServer { harness.favorites.refresh() }

        val favorites = harness.favorites
        assertTrue(favorites.isFavorite("tt0111161", "movie"))
        assertTrue(favorites.isFavorite("tmdb:278", "movie"))
        assertTrue(favorites.isFavorite(ServerItemRef(harness.connection.id, "7").encode(), "movie"))
        assertFalse("TMDB numbers a show and a movie separately", favorites.isFavorite("tmdb:278", "series"))
        assertFalse(favorites.isFavorite("tt0944947", "series"))
    }

    @Test
    fun unfavouritesWhatTheServerAlreadyHas() {
        val harness = harness(provider().apply { favoriteIds += "7" })
        onServer { harness.favorites.refresh() }

        val result = onServer { harness.favorites.setFavorite("tmdb:278", "movie", favorite = false) }

        assertEquals(ServerFavoriteResult.REMOVED, result)
        assertEquals(listOf("7" to false), harness.provider.favoriteChanges)
        assertFalse(harness.favorites.isFavorite("tt0111161", "movie"))
    }

    @Test
    fun flipsAServerTitleDirectly() {
        val harness = harness()
        val id = ServerItemRef(harness.connection.id, "7").encode()

        assertEquals(ServerFavoriteResult.ADDED, onServer { harness.favorites.setFavorite(id, "movie", favorite = true) })
        assertTrue(harness.favorites.isFavorite(id, "movie"))
        assertEquals(ServerFavoriteResult.REMOVED, onServer { harness.favorites.setFavorite(id, "movie", favorite = false) })

        assertEquals(listOf("7" to true, "7" to false), harness.provider.favoriteChanges)
    }

    @Test
    fun saysWhenNoServerHasTheTitle() {
        val harness = harness()

        val result = onServer { harness.favorites.setFavorite("tt9999999", "movie", favorite = true) }

        assertEquals(ServerFavoriteResult.NOT_FOUND, result)
        assertTrue(harness.provider.favoriteChanges.isEmpty())
    }

    @Test
    fun reportsAFailedWrite() {
        val harness = harness(provider().apply { failingFavorites += "7" })

        val result = onServer { harness.favorites.setFavorite("tt0111161", "movie", favorite = true) }

        assertEquals(ServerFavoriteResult.FAILED, result)
        assertFalse(harness.favorites.isFavorite("tt0111161", "movie"))
        assertEquals(0, harness.favorites.state.value.writes)
    }

    @Test
    fun staysOutOfTheWayOfServersWithoutFavourites() {
        val harness = harness(provider(favorites = false))

        assertFalse(harness.favorites.isAvailable())
        assertNull(harness.favorites.mirror("tt0111161", "movie", favorite = true))
        assertEquals(
            ServerFavoriteResult.UNAVAILABLE,
            onServer { harness.favorites.setFavorite("tt0111161", "movie", favorite = true) }
        )
        assertTrue(harness.catalog.favoriteLibraries().isEmpty())
    }

    @Test
    fun mirrorsALibraryChange() {
        val harness = harness()

        val job = harness.favorites.mirror("tt0111161", "movie", favorite = true)
        assertNotNull(job)
        onServer { job!!.join() }

        assertEquals(listOf("7" to true), harness.provider.favoriteChanges)
        assertEquals(0, failureNotices)
        assertNull(harness.favorites.mirror("tt0111161", "collection", favorite = true))
    }

    @Test
    fun saysWhenAMirroredChangeFails() {
        val harness = harness(provider().apply { failingFavorites += "7" })

        onServer { harness.favorites.mirror("tt0111161", "movie", favorite = true)!!.join() }

        assertEquals(1, failureNotices)
    }

    @Test
    fun libraryShowsOnlyFavouritesForACatalogServer() {
        val harness = harness(provider(catalogs = true))

        val shelves = onServer { harness.catalog.libraryShelves() }

        assertEquals(listOf(ServerCatalog.FAVORITES_ID), shelves.map { it.library.id })
        val homeCatalogs = onServer { harness.catalog.addons.first() }.single().catalogs.map { it.id }
        assertTrue("its catalogs stay on Home", homeCatalogs.containsAll(harness.connection.selectedLibraries.map { it.id }))
        assertFalse(ServerCatalog.FAVORITES_ID in homeCatalogs)
    }

    @Test
    fun libraryKeepsAMediaServersOwnLibraries() {
        val harness = harness()

        val shelves = onServer { harness.catalog.libraryShelves() }

        assertEquals(
            listOf(ServerCatalog.FAVORITES_ID) + harness.connection.selectedLibraries.map { it.id },
            shelves.map { it.library.id }
        )
    }

    @Test
    fun listsFavouritesAsALibraryShelf() {
        val harness = harness(provider().apply { favoriteIds += listOf("7", FakeServerProvider.SHOW_ID) })
        val connection = harness.connection

        val shelf = harness.catalog.favoriteLibraries().single()
        assertEquals(ServerCatalog.FAVORITES_ID, shelf.library.id)
        val row = onServer { harness.catalog.row(connection.id, ServerCatalog.FAVORITES_ID) }

        assertEquals(
            listOf(ServerItemRef(connection.id, "7").encode(), ServerItemRef(connection.id, FakeServerProvider.SHOW_ID).encode()),
            row.items.map { it.id }
        )
        assertFalse(row.hasMore)
    }
}
