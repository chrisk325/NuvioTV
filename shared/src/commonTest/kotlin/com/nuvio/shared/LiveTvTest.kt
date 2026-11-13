package com.nuvio.shared

import com.nuvio.shared.livetv.CatchupKind
import com.nuvio.shared.livetv.LiveTvProgramme
import com.nuvio.shared.livetv.LiveTvSource
import com.nuvio.shared.livetv.catchupUrl
import com.nuvio.shared.livetv.parseM3u
import com.nuvio.shared.livetv.parseXmlTv
import com.nuvio.shared.livetv.parseXmlTvTime
import com.nuvio.shared.util.utcFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTvTest {
    @Test
    fun m3uParsesAttributesHeadersAndCatchup() {
        val text = """
            #EXTM3U url-tvg="https://epg.example/guide.xml.gz" catchup="shift" catchup-days="3"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One HD" tvg-logo="https://logo/bbc.png" group-title="UK",BBC One
            #EXTVLCOPT:http-user-agent=TestUA
            https://stream.example/bbc1.m3u8
            #EXTINF:-1,#### SPORTS ####
            https://stream.example/sep
            #EXTINF:-1 group-title="News",CNN
            https://stream.example/cnn.ts|Referer=https://ref.example
            https://stream.example/bbc1.m3u8
        """.trimIndent()
        val parsed = parseM3u(text, "s1")
        assertEquals(listOf("https://epg.example/guide.xml.gz"), parsed.epgUrls)
        assertEquals(listOf("BBC One", "CNN"), parsed.channels.map { it.name })
        val bbc = parsed.channels[0]
        assertEquals("bbc1.uk", bbc.guideKey)
        assertEquals("TestUA", bbc.headers["User-Agent"])
        assertEquals(CatchupKind.SHIFT, bbc.catchup?.kind)
        assertEquals(3, bbc.catchup?.days)
        assertEquals("https://ref.example", parsed.channels[1].headers["Referer"])
        assertEquals("https://stream.example/cnn.ts", parsed.channels[1].streamUrl)
    }

    @Test
    fun xmltvTimesAndProgrammes() {
        assertEquals(0L, parseXmlTvTime("19700101000000 +0000"))
        assertEquals(-3_600_000L, parseXmlTvTime("19700101000000 +0100"))
        val xml = """
            <tv><channel id="BBC1.uk"><display-name>BBC One</display-name></channel>
            <channel id="other"><display-name>CNN HD</display-name></channel>
            <programme start="19700101010000 +0000" stop="19700101020000 +0000" channel="BBC1.uk"><title lang="en">News &amp; Weather</title><desc>Today</desc></programme>
            <programme start="19700101020000 +0000" stop="19700101030000 +0000" channel="other"><title>Live</title></programme>
            </tv>
        """.trimIndent()
        val guide = parseXmlTv(xml, setOf("bbc1.uk", "cnn"), mapOf("cnn" to "cnn"), 0, 10_000_000)
        assertEquals("News & Weather", guide["bbc1.uk"]?.single()?.title)
        assertEquals("Live", guide["cnn"]?.single()?.title) // matched by display name
    }

    @Test
    fun catchupLinks() {
        assertEquals(listOf("2001", "09", "09", "01", "46", "40"), utcFields(1_000_000_000))
        val parsed = parseM3u("#EXTM3U\n#EXTINF:-1 catchup=\"shift\",A\nhttps://s/a.m3u8", "s")
        val programme = LiveTvProgramme("P", 1_000_000_000_000, 1_000_003_600_000)
        val url = catchupUrl(parsed.channels.single(), null, programme, 1_000_010_000_000)
        assertEquals("https://s/a.m3u8?utc=1000000000&lutc=1000010000", url)
        val xc = parsed.channels.single().copy(catchup = com.nuvio.shared.livetv.LiveTvCatchup(CatchupKind.XTREAM, 1), xtreamStreamId = "55")
        val src = LiveTvSource("s", "xtream", "X", "http://panel:8080", "u", "p")
        assertTrue(catchupUrl(xc, src, programme, 0)!!.startsWith("http://panel:8080/timeshift/u/p/60/2001-09-09:01-46/55.ts"))
    }
}
