package com.nuvio.shared

import com.nuvio.shared.livetv.XmlTvStreamParser
import com.nuvio.shared.livetv.Utf8ChunkDecoder
import com.nuvio.shared.livetv.parseXmlTv
import kotlin.test.Test
import kotlin.test.assertEquals

class XmlTvStreamTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <tv generator-info-name="x">
          <channel id="Kanal7.al"><display-name lang="sq">Kanal 7</display-name></channel>
          <channel id="other"><display-name>Other</display-name></channel>
          <channel id="byname"><display-name>Télé Café ☕</display-name></channel>
          <programme start="19700101010000 +0000" stop="19700101020000 +0000" channel="Kanal7.al"><title lang="sq">Lajmet — Mëngjes</title><desc>Përmbledhje ☕</desc><icon src="https://img/a.png" /></programme>
          <programme start="19700101020000 +0000" stop="19700101030000 +0000" channel="other"><title>Skip</title></programme>
          <programme start="19700101020000 +0000" stop="19700101030000 +0000" channel="byname"><title>Café du matin</title></programme>
          <programme start="19700101030000 +0000" stop="19700101040000 +0000" channel="Kanal7.al"/>
        </tv>"""

    private val wanted = setOf("kanal7.al")
    private val byName = mapOf("tele cafe" to "tele-key")

    @Test
    fun pieceByPieceMatchesWholeParse() {
        val whole = parseXmlTv(xml, wanted, byName, 0, Long.MAX_VALUE)
        val bytes = xml.encodeToByteArray()
        // Every piece size from 1 byte up: tags and multi-byte characters cut everywhere.
        for (size in listOf(1, 2, 3, 5, 7, 13, 64)) {
            val parser = XmlTvStreamParser(wanted, byName, 0, Long.MAX_VALUE)
            val decoder = Utf8ChunkDecoder()
            var offset = 0
            while (offset < bytes.size) {
                val n = minOf(size, bytes.size - offset)
                parser.feed(decoder.decode(bytes.copyOfRange(offset, offset + n), n))
                offset += n
            }
            parser.feed(decoder.finish())
            assertEquals(whole, parser.finish(), "piece size $size")
        }
        assertEquals("Lajmet — Mëngjes", whole["kanal7.al"]?.single()?.title)
        assertEquals("Përmbledhje ☕", whole["kanal7.al"]?.single()?.description)
        assertEquals("https://img/a.png", whole["kanal7.al"]?.single()?.image)
    }

    @Test
    fun decoderKeepsCharactersWhole() {
        val text = "aé☕😀z"
        val bytes = text.encodeToByteArray()
        val decoder = Utf8ChunkDecoder()
        val out = StringBuilder()
        for (b in bytes) out.append(decoder.decode(byteArrayOf(b), 1))
        out.append(decoder.finish())
        assertEquals(text, out.toString())
    }
}
