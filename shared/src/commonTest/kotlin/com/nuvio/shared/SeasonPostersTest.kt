package com.nuvio.shared

import com.nuvio.shared.data.parseMetaResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Android MetaMapper season posters: per-episode seasonPoster, then app_extras maps / lists. */
class SeasonPostersTest {
    private fun meta(extras: String, videos: String = """
        {"id":"tt1:1:1","season":1,"episode":1},{"id":"tt1:2:1","season":2,"episode":1},{"id":"tt1:0:1","season":0,"episode":1}""") =
        parseMetaResponse("""{"meta":{"id":"tt1","type":"series","name":"Show","poster":"show.jpg",
            "videos":[$videos],"app_extras":{$extras}}}""")!!

    @Test
    fun keyedMapBySeasonNumber() {
        val m = meta(""""seasonPosters":{"1":"s1.jpg","2":"s2.jpg"}""")
        assertEquals("s1.jpg", m.seasonPoster(1))
        assertEquals("s2.jpg", m.seasonPoster(2))
        assertNull(m.seasonPoster(0))
    }

    @Test
    fun listWithSpecialsPlaceholderFirst() {
        val m = meta(""""seasonPosters":[null,"s1.jpg","s2.jpg"]""")
        assertEquals("s1.jpg", m.seasonPoster(1))
        assertEquals("s2.jpg", m.seasonPoster(2))
    }

    @Test
    fun listForEverySeasonIncludingSpecials() {
        val m = meta(""""seasonPosterByNumber":{},"seasonPosters":["sp.jpg","s1.jpg","s2.jpg"]""")
        assertEquals("sp.jpg", m.seasonPoster(0))
        assertEquals("s2.jpg", m.seasonPoster(2))
    }

    @Test
    fun episodeSeasonPosterWins() {
        val m = meta(""""seasonPosters":{"1":"map.jpg"}""",
            """{"id":"tt1:1:1","season":1,"episode":1,"season_poster_path":"ep.jpg"}""")
        assertEquals("ep.jpg", m.seasonPoster(1))
    }
}
