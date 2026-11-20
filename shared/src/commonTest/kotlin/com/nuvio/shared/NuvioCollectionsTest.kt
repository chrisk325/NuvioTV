package com.nuvio.shared

import com.nuvio.shared.collections.NuvioCollectionsJson
import com.nuvio.shared.collections.discoverParams
import com.nuvio.shared.collections.todayIso
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NuvioCollectionsTest {
    private val exported = """[
      {"id":"c1","title":"Studios","pinToTop":true,"viewMode":"ROWS","folders":[
        {"id":"f1","title":"Marvel","tileShape":"POSTER","coverImageUrl":"https://img/m.jpg","sources":[
          {"provider":"tmdb","tmdbSourceType":"COMPANY","title":"Marvel Studios","tmdbId":420,"mediaType":"MOVIE","sortBy":"popularity.desc"},
          {"provider":"trakt","title":"MCU order","traktListId":12345,"mediaType":"MOVIE","sortBy":"rank","sortHow":"asc"},
          {"provider":"addon","addonId":"com.linvo.cinemeta","type":"movie","catalogId":"top","genre":"Action"},
          {"provider":"tmdb","title":"broken, no type"}
        ]},
        {"id":"f2","title":"Legacy","tileShape":"wide","catalogSources":[{"addonId":"a","type":"series","catalogId":"pop"}]}
      ]},
      {"id":"c1","title":"Duplicate id"},
      {"title":"No id"}
    ]"""

    @Test
    fun parsesAndroidExportSkippingInvalidEntries() {
        val list = NuvioCollectionsJson.parse(exported)
        assertEquals(listOf("c1"), list.map { it.id })
        val c = list.single()
        assertTrue(c.pinToTop)
        assertTrue(c.showsRows)
        assertEquals(listOf("f1", "f2"), c.folders.map { it.id })
        val sources = c.folders[0].sources
        assertEquals(listOf("tmdb", "trakt", "addon"), sources.map { it.kind })
        assertEquals("poster", c.folders[0].shape)
        assertEquals("landscape", c.folders[1].shape)
        assertEquals("pop", c.folders[1].sources.single().catalogId)
    }

    @Test
    fun acceptsAWrapperObjectAndRejectsNonsense() {
        assertEquals(1, NuvioCollectionsJson.parse("""{"collections":$exported}""").size)
        assertFailsWith<IllegalArgumentException> { NuvioCollectionsJson.parse("not json") }
        assertFailsWith<IllegalArgumentException> { NuvioCollectionsJson.parse("""{"other":1}""") }
    }

    @Test
    fun networkSourcesDiscoverAiredShows() {
        val source = NuvioCollectionsJson.parse("""[{"id":"c","title":"t","folders":[{"id":"f","title":"HBO","sources":[
            {"provider":"tmdb","tmdbSourceType":"NETWORK","tmdbId":49,"mediaType":"MOVIE"}]}]}]""").single().folders.single().sources.single()
        val params = discoverParams(source, isShow = true, today = "2026-11-20")
        assertEquals("49", params["with_networks"])
        assertEquals("2026-11-20", params["first_air_date.lte"])
        assertEquals("0|3|4", params["with_status"])
        assertEquals("popularity.desc", params["sort_by"])
        assertEquals("2026-11-20", todayIso(1_795_132_800_000L))
    }
}
