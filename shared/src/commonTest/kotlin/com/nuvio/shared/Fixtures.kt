package com.nuvio.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

object Fixtures {
    const val CINEMETA = "https://v3-cinemeta.strem.io"
    const val TORRENTIO = "https://torrentio.example/config=abc"

    val cinemetaManifest = """
        {"id":"com.linvo.cinemeta","version":"3.0.13","name":"Cinemeta","description":"meta",
         "resources":["catalog","meta","addon_catalog"],
         "types":["movie","series"],"idPrefixes":["tt"],
         "catalogs":[
           {"type":"movie","id":"top","name":"Popular","extra":[{"name":"genre","options":["Action"]},{"name":"search"},{"name":"skip"}]},
           {"type":"series","id":"top","name":"Popular","extraSupported":["search","skip"]},
           {"type":"movie","id":"year","name":"New","extra":[{"name":"genre","isRequired":true,"options":["2024"]}]}
         ]}
    """.trimIndent()

    val streamManifest = """
        {"id":"org.stream","version":"1.0.0","name":"Streamer",
         "resources":[{"name":"stream","types":["movie","series"],"idPrefixes":["tt","kitsu"]}],
         "types":["movie","series"],"catalogs":[]}
    """.trimIndent()

    val catalog = """
        {"metas":[
          {"id":"tt1","type":"movie","name":"One","poster":"https://img/1.jpg","imdbRating":7.4,"genres":["Action"]},
          {"id":"tt2","name":"Two","posterShape":"landscape","imdbRating":"N/A"},
          {"id":"tt1","type":"movie","name":"One dup"},
          {"name":"No id"},
          null
        ]}
    """.trimIndent()

    val seriesMeta = """
        {"meta":{"id":"tt9","type":"series","name":"Show","director":"Jane","writer":[{"name":"A"},"B"],
          "cast":["X","Y"],"imdbRating":"8.1","trailers":[{"source":"yt1","type":"Trailer"}],
          "behaviorHints":{"defaultVideoId":null},
          "videos":[
            {"id":"tt9:1:1","name":"Pilot","season":1,"episode":1,"released":"2020-01-01T00:00:00.000Z","runtime":"45 min"},
            {"id":"tt9:1:2","season":"1","number":2,"released":"2020-01-08"},
            {"id":"tt9:0:1","name":"Special","season":0,"episode":1},
            {"id":"tt9:2:1","name":"Future","season":2,"episode":1,"released":"2999-01-01"}
          ]}}
    """.trimIndent()

    val streams = """
        {"streams":[
          {"name":"Streamer\n1080p","title":"Movie.1080p.mkv","url":"https://cdn/1.mkv",
           "behaviorHints":{"bingeGroup":"g","proxyHeaders":{"request":{"Referer":"https://ref","Range":"bytes=0-"," ":"x"}}},
           "subtitles":[{"id":"s1","url":"https://sub/1.srt","lang":"eng"},{"url":"","lang":"x"}]},
          {"name":"Torrent 4K","infoHash":"0123456789abcdef0123456789abcdef01234567","fileIdx":"2"},
          {"name":"Ext","externalUrl":"https://site/watch"}
        ]}
    """.trimIndent()

    fun mockClient(routes: Map<String, String>, failing: Set<String> = emptySet()): HttpClient =
        HttpClient(MockEngine { request ->
            val url = request.url.toString()
            val body = routes[url]
            when {
                url in failing -> respond("boom", HttpStatusCode.InternalServerError)
                body != null -> respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                else -> respond("not found: $url", HttpStatusCode.NotFound)
            }
        })
}
