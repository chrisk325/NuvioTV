package com.nuvio.shared

import com.nuvio.shared.livetv.LiveTvRepository
import com.nuvio.shared.storage.InMemoryStore
import com.nuvio.shared.util.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTvCacheTest {
    /** Epoch ms → XMLTV "yyyyMMddHHmmss +0000". */
    private fun xmltvTime(ms: Long): String {
        val secs = ms / 1000
        var days = secs / 86_400
        val rem = secs % 86_400
        // Civil from days (Howard Hinnant).
        days += 719_468
        val era = days / 146_097
        val doe = days - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        if (m <= 2) y += 1
        fun two(v: Long) = v.toString().padStart(2, '0')
        return "$y${two(m)}${two(d)}${two(rem / 3600)}${two(rem % 3600 / 60)}${two(rem % 60)} +0000"
    }

    @Test
    fun channelsAndGuideComeFromTheCacheWhenOffline() = runTest {
        val now = currentTimeMillis()
        val m3u = """#EXTM3U
            #EXTINF:-1 tvg-id="news.one" group-title="News",News One
            http://tv.example/news1.ts
        """.trimIndent()
        val xml = """<tv><programme start="${xmltvTime(now - 600_000)}" stop="${xmltvTime(now + 3_000_000)}" channel="news.one"><title>Headlines</title></programme></tv>"""
        var downloads = 0
        val online = HttpClient(MockEngine { request ->
            downloads++
            when (request.url.encodedPath) {
                "/list.m3u" -> respond(m3u, HttpStatusCode.OK)
                "/guide.xml" -> respond(xml, HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val store = InMemoryStore()
        val cache = InMemoryStore()
        val first = LiveTvRepository(online, store, cache)
        first.addSource("m3u", "Test", "http://tv.example/list.m3u", "", "", "", "http://tv.example/guide.xml")
        first.load(force = false)
        val channel = first.state.value.groups.flatMap { it.channels }.single()
        assertEquals("Headlines", first.nowPlaying(channel)?.title)

        // Next launch, server unreachable: channels and guide still show, from the cache.
        val offline = HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) })
        val second = LiveTvRepository(offline, store, cache)
        second.load(force = false)
        val cached = second.state.value.groups.flatMap { it.channels }.single()
        assertEquals("News One", cached.name)
        assertTrue(second.state.value.guideLoaded)
        assertEquals("Headlines", second.nowPlaying(cached)?.title)

        // A forced reload that fails keeps them too.
        second.load(force = true)
        assertEquals(1, second.state.value.groups.flatMap { it.channels }.size)
        assertEquals("Headlines", second.nowPlaying(cached)?.title)
    }
}
