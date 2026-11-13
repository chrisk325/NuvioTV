package com.nuvio.shared

import com.nuvio.shared.data.StremioClient
import com.nuvio.shared.data.parseManifest
import com.nuvio.shared.model.WatchProgress
import com.nuvio.shared.repository.AddonRepository
import com.nuvio.shared.repository.DuplicateAddonException
import com.nuvio.shared.repository.MetaRepository
import com.nuvio.shared.repository.StreamRepository
import com.nuvio.shared.repository.WatchProgressRepository
import com.nuvio.shared.storage.InMemoryStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RepositoryTest {
    private val routes = mapOf(
        "${Fixtures.CINEMETA}/manifest.json" to Fixtures.cinemetaManifest,
        "https://torrentio.example/config=abc/manifest.json" to Fixtures.streamManifest,
        "https://torrentio.example/config=abc/stream/movie/tt1.json" to Fixtures.streams,
        "${Fixtures.CINEMETA}/meta/series/tt9.json" to Fixtures.seriesMeta,
        "${Fixtures.CINEMETA}/catalog/movie/top.json" to Fixtures.catalog,
    )

    @Test
    fun addonLifecyclePersists() = runTest {
        val store = InMemoryStore()
        val client = StremioClient(Fixtures.mockClient(routes))
        val repo = AddonRepository(client, store)
        assertEquals(AddonRepository.DEFAULT_ADDONS, repo.installedUrls)
        assertTrue(repo.currentAddons.isEmpty()) // manifests not fetched yet

        val failed = repo.refreshAll()
        assertEquals(listOf("https://opensubtitles-v3.strem.io"), failed)
        assertEquals(listOf("Cinemeta"), repo.currentAddons.map { it.name })

        repo.install("https://torrentio.example/config=abc/manifest.json")
        assertFailsWith<DuplicateAddonException> { repo.install("https://TORRENTIO.example/config=abc/") }
        repo.move("https://torrentio.example/config=abc", -10)
        assertEquals("https://torrentio.example/config=abc", repo.installedUrls.first())
        repo.setEnabled(Fixtures.CINEMETA, false)

        // A fresh repository over the same store sees the cached manifests and settings offline.
        val reloaded = AddonRepository(StremioClient(Fixtures.mockClient(emptyMap())), store)
        assertEquals(listOf("Streamer", "Cinemeta"), reloaded.currentAddons.map { it.name })
        assertEquals(listOf("Streamer"), reloaded.enabledAddons.map { it.name })
        reloaded.remove("https://torrentio.example/config=abc/")
        assertEquals(listOf("Cinemeta"), reloaded.currentAddons.map { it.name })
    }

    @Test
    fun metaCandidatesPreferPrefixMatches() = runTest {
        val cinemeta = parseManifest(Fixtures.cinemetaManifest, Fixtures.CINEMETA)
        val streamer = parseManifest(Fixtures.streamManifest, Fixtures.TORRENTIO)
        val candidates = MetaRepository.metaCandidates(listOf(streamer, cinemeta), "series", "tt9")
        assertEquals(listOf("Cinemeta" to "series"), candidates.map { it.first.name to it.second })
        // No prefix match: Android's legacy last resort still tries add-ons that declare the type.
        assertEquals(
            listOf("Cinemeta" to "series"),
            MetaRepository.metaCandidates(listOf(streamer, cinemeta), "series", "kitsu:1").map { it.first.name to it.second }
        )
        assertEquals("series", MetaRepository.inferCanonicalType("tv", "x"))

        val meta = MetaRepository(StremioClient(Fixtures.mockClient(routes))).getMeta(listOf(cinemeta), "series", "tt9")
        assertEquals("Show", meta.name)
    }

    @Test
    fun streamsArriveProgressively() = runTest {
        val cinemeta = parseManifest(Fixtures.cinemetaManifest, Fixtures.CINEMETA)
        val good = parseManifest(Fixtures.streamManifest, Fixtures.TORRENTIO)
        val broken = parseManifest(Fixtures.streamManifest, "https://broken.example")
        val client = StremioClient(Fixtures.mockClient(routes, failing = setOf("https://broken.example/stream/movie/tt1.json")))
        val updates = StreamRepository(client).streams(listOf(cinemeta, good, broken), "movie", "tt1").toList()
        assertEquals(3, updates.size) // initial + one per stream add-on (cinemeta has no stream resource)
        val last = updates.last()
        assertTrue(last.isComplete)
        assertEquals(listOf("Streamer"), last.groups.map { it.addonName })
        assertEquals(3, last.groups.single().streams.size)
        assertEquals(listOf("Streamer"), last.failedAddons) // the broken copy shares the name
    }

    @Test
    fun continueWatchingKeepsLatestUnfinishedPerTitle() {
        val repo = WatchProgressRepository(InMemoryStore())
        fun p(content: String, video: String, pos: Long, at: Long) =
            WatchProgress(content, "series", video, content, positionMs = pos, durationMs = 100_000L, updatedAtMs = at)
        repo.update(p("a", "a:1:1", 50_000, 1))
        repo.update(p("a", "a:1:2", 40_000, 2))
        repo.update(p("b", "b", 99_000, 3)) // finished
        repo.update(p("c", "c", 10_000, 4)) // too early to resume
        assertEquals(listOf("a:1:2"), repo.continueWatching().map { it.videoId })
        assertEquals(50_000, repo.progressFor("a:1:1")?.positionMs)
    }

    @Test
    fun coreWiresRepositories() = runTest {
        val core = NuvioCore(InMemoryStore(), Fixtures.mockClient(routes), StandardTestDispatcher(testScheduler))
        core.refreshAddons()
        val slots = core.homeSlots()
        assertEquals(listOf("Popular - Movie", "Popular - Series"), slots.map { it.title })
        val row = core.loadCatalog(slots.first(), 0)
        assertEquals(2, row.items.size)
        assertTrue(row.hasMore)
        core.close()
    }
}
