package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * An element header whose size does not fit in an int (damaged data) used to be skipped with the
 * size cast to an int. The negative length made [DefaultExtractorInput] throw
 * "ArrayIndexOutOfBoundsException: src.length=65536 srcPos=-1747899964 ..." and playback ended.
 * A zero byte where an element size starts threw "No valid varint length mask found", which also
 * ended playback; a zero byte where an element id starts resynced but left the Cluster open.
 * The reader now closes what was open under the Segment and resyncs to the next Cluster.
 *
 * A Block or SimpleBlock whose first byte (its track number) is zero made MatroskaExtractor throw
 * "No valid varint length mask found". The reader now skips that block.
 */
class MatroskaOversizedElementResyncTest {

    @Test
    fun `unknown element with a size beyond int range resyncs to the next cluster`() {
        val events = parse(stream(garbageId = intArrayOf(0x42, 0x86)))
        assertEquals(
            listOf(
                "start 18538067", "start 1f43b675", "int e7=5", "end 1f43b675",
                "start 1f43b675", "int e7=9", "bin a3 1", "end 1f43b675"
            ),
            events
        )
    }

    @Test
    fun `binary element with a size beyond int range resyncs to the next cluster`() {
        val events = parse(stream(garbageId = intArrayOf(SIMPLE_BLOCK)))
        assertEquals(
            listOf(
                "start 18538067", "start 1f43b675", "int e7=5", "end 1f43b675",
                "start 1f43b675", "int e7=9", "bin a3 1", "end 1f43b675"
            ),
            events
        )
    }

    @Test
    fun `an open block group is closed before the resync`() {
        val events = parse(stream(garbageId = intArrayOf(0x42, 0x86), inBlockGroup = true))
        assertEquals(
            listOf(
                "start 18538067", "start 1f43b675", "int e7=5", "start a0", "bin a1 2", "end a0",
                "end 1f43b675", "start 1f43b675", "int e7=9", "bin a3 1", "end 1f43b675"
            ),
            events
        )
    }

    @Test
    fun `a zero byte where an element id starts resyncs to the next cluster`() {
        val events = parse(stream(garbageId = intArrayOf(0x00), garbageSize = intArrayOf()))
        assertEquals(
            listOf(
                "start 18538067", "start 1f43b675", "int e7=5", "end 1f43b675",
                "start 1f43b675", "int e7=9", "bin a3 1", "end 1f43b675"
            ),
            events
        )
    }

    @Test
    fun `a zero byte where an element size starts resyncs to the next cluster`() {
        val events = parse(stream(garbageId = intArrayOf(0x42, 0x86), garbageSize = intArrayOf(0x00)))
        assertEquals(
            listOf(
                "start 18538067", "start 1f43b675", "int e7=5", "end 1f43b675",
                "start 1f43b675", "int e7=9", "bin a3 1", "end 1f43b675"
            ),
            events
        )
    }

    @Test
    fun `an open block group is closed before a resync on a zero byte`() {
        val events = parse(
            stream(garbageId = intArrayOf(0x00), garbageSize = intArrayOf(), inBlockGroup = true)
        )
        assertEquals(
            listOf(
                "start 18538067", "start 1f43b675", "int e7=5", "start a0", "bin a1 2", "end a0",
                "end 1f43b675", "start 1f43b675", "int e7=9", "bin a3 1", "end 1f43b675"
            ),
            events
        )
    }

    @Test
    fun `a read error during a resync on a zero byte resumes the search`() {
        val data = stream(garbageId = intArrayOf(0x42, 0x86), garbageSize = intArrayOf(0x00))
        val events = parse(data, failAt = 40)
        assertEquals(
            listOf(
                "start 18538067", "start 1f43b675", "int e7=5", "end 1f43b675", "retry",
                "start 1f43b675", "int e7=9", "bin a3 1", "end 1f43b675"
            ),
            events
        )
    }

    @Test
    fun `a read error during the resync resumes the search`() {
        val data = stream(garbageId = intArrayOf(0x42, 0x86))
        val events = parse(data, failAt = 40)
        assertEquals(
            listOf(
                "start 18538067", "start 1f43b675", "int e7=5", "end 1f43b675", "retry",
                "start 1f43b675", "int e7=9", "bin a3 1", "end 1f43b675"
            ),
            events
        )
    }

    @Test
    fun `a simple block with a zero track number is skipped`() {
        val events = parse(
            segment(
                cluster(
                    TIMECODE, 0x81, 0x05,
                    SIMPLE_BLOCK, 0x84, 0x00, 0x00, 0x00, 0x00,
                    SIMPLE_BLOCK, 0x84, 0x81, 0x00, 0x00, 0x00
                )
            )
        )
        assertEquals(
            listOf("start 18538067", "start 1f43b675", "int e7=5", "bin a3 4", "end 1f43b675"),
            events
        )
    }

    @Test
    fun `a block with a zero track number is skipped inside its block group`() {
        val events = parse(
            segment(
                cluster(
                    TIMECODE, 0x81, 0x05,
                    BLOCK_GROUP, 0x86, BLOCK, 0x84, 0x00, 0x00, 0x00, 0x00,
                    SIMPLE_BLOCK, 0x84, 0x81, 0x00, 0x00, 0x00
                )
            )
        )
        assertEquals(
            listOf(
                "start 18538067", "start 1f43b675", "int e7=5", "start a0", "end a0", "bin a3 4",
                "end 1f43b675"
            ),
            events
        )
    }

    @Test
    fun `a block resumed after a read error is not checked again`() {
        val data = segment(
            cluster(
                TIMECODE, 0x81, 0x05,
                SIMPLE_BLOCK, 0x84, 0x81, 0x00, 0x00, 0x00,
                SIMPLE_BLOCK, 0x82, 0x82, 0x00
            )
        )
        // Fail just after the track number, where the rest of the block starts with a zero byte.
        val events = parse(data, failAt = 23)
        assertEquals(
            listOf(
                "start 18538067", "start 1f43b675", "int e7=5", "bin a3 4", "retry", "bin a3 2",
                "end 1f43b675"
            ),
            events
        )
    }

    private fun parse(data: ByteArray, failAt: Int = -1): List<String> {
        val reader = DefaultEbmlReader()
        val processor = RecordingProcessor()
        reader.init(processor)
        var input: ExtractorInput =
            DefaultExtractorInput(ChunkedReader(data, 0, failAt), 0, data.size.toLong())
        while (true) {
            try {
                if (!reader.read(input)) break
            } catch (e: IOException) {
                if (e.message != DROP) throw e
                // As the loader does: a new input that continues where the failed one stopped.
                val position = input.position
                input = DefaultExtractorInput(
                    ChunkedReader(data, position.toInt(), -1),
                    position,
                    data.size.toLong()
                )
                processor.events += "retry"
            }
        }
        return processor.events
    }

    /** Segment > Cluster with a damaged element header, noise, then an intact Cluster. */
    private fun stream(
        garbageId: IntArray,
        garbageSize: IntArray = OVERSIZED,
        inBlockGroup: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun put(vararg bytes: Int) = bytes.forEach(out::write)
        put(0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
        put(0x1F, 0x43, 0xB6, 0x75, 0x40, 0x7F)
        put(TIMECODE, 0x81, 0x05)
        if (inBlockGroup) {
            put(BLOCK_GROUP, 0x40, 0x60)
            put(BLOCK, 0x82, 0x81, 0x00)
        }
        put(*garbageId)
        put(*garbageSize)
        repeat(40) { put((it * 37 + 11) and 0x7F) }
        put(0x1F, 0x43, 0xB6, 0x75, 0x86)
        put(TIMECODE, 0x81, 0x09)
        put(SIMPLE_BLOCK, 0x81, 0x81)
        return out.toByteArray()
    }

    /** A Segment of unknown size holding [content]. */
    private fun segment(content: IntArray): ByteArray =
        (intArrayOf(0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF) + content)
            .map(Int::toByte)
            .toByteArray()

    /** A Cluster holding [content], which must be shorter than 127 bytes. */
    private fun cluster(vararg content: Int): IntArray =
        intArrayOf(0x1F, 0x43, 0xB6, 0x75, 0x80 or content.size) + content

    /** Serves a few bytes per read; throws once when reaching [failAt] (negative: never). */
    private class ChunkedReader(
        private val data: ByteArray,
        private var position: Int,
        private val failAt: Int
    ) : DataReader {
        private var failed = false

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            if (failAt >= 0 && !failed && position >= failAt) {
                failed = true
                throw IOException(DROP)
            }
            var count = minOf(length, 7, data.size - position)
            if (failAt >= 0 && !failed) count = minOf(count, failAt - position)
            System.arraycopy(data, position, buffer, offset, count)
            position += count
            return count
        }
    }

    private class RecordingProcessor : EbmlProcessor {
        val events = mutableListOf<String>()

        override fun getElementType(id: Int): Int = when (id) {
            SEGMENT, CLUSTER, BLOCK_GROUP -> EbmlProcessor.ELEMENT_TYPE_MASTER
            SIMPLE_BLOCK, BLOCK -> EbmlProcessor.ELEMENT_TYPE_BINARY
            TIMECODE -> EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT
            else -> EbmlProcessor.ELEMENT_TYPE_UNKNOWN
        }

        override fun isLevel1Element(id: Int) = id == CLUSTER

        override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
            events += "start ${Integer.toHexString(id)}"
        }

        override fun endMasterElement(id: Int) {
            events += "end ${Integer.toHexString(id)}"
        }

        override fun integerElement(id: Int, value: Long) {
            events += "int ${Integer.toHexString(id)}=$value"
        }

        override fun floatElement(id: Int, value: Double) = Unit

        override fun stringElement(id: Int, value: String) = Unit

        /** Bytes of the current binary element left to skip after a read error, or -1. */
        private var pending = -1

        /** Like MatroskaExtractor: reads the track number first and resumes after a read error. */
        override fun binaryElement(id: Int, contentsSize: Int, input: ExtractorInput) {
            if (pending < 0) {
                events += "bin ${Integer.toHexString(id)} $contentsSize"
                if (contentsSize > 0) input.readFully(ByteArray(1), 0, 1)
                pending = maxOf(contentsSize - 1, 0)
            }
            input.skipFully(pending)
            pending = -1
        }
    }

    private companion object {
        const val DROP = "connection dropped"
        const val SEGMENT = 0x18538067
        const val CLUSTER = 0x1F43B675
        const val BLOCK_GROUP = 0xA0
        const val BLOCK = 0xA1
        const val SIMPLE_BLOCK = 0xA3
        const val TIMECODE = 0xE7

        /** 8-byte size 0x97D129C4 = 2547067332, which wraps to -1747899964 as an int. */
        val OVERSIZED = intArrayOf(0x01, 0x00, 0x00, 0x00, 0x97, 0xD1, 0x29, 0xC4)
    }
}
