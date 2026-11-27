package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class SsaTimecodeParserTest {

    private fun parse(text: String): Long {
        // Inside a sample, between the commas that bound the duration field.
        val bytes = "0,$text,Default".toByteArray()
        return parseSsaTimecodeUs(bytes, 2, 2 + text.toByteArray().size)
    }

    @Test
    fun `reads the durations Media3 writes`() {
        assertEquals(5_200_000L, parse("0:00:05.20"))
        assertEquals(5_025_670_000L, parse("1:23:45.67"))
        assertEquals(1_000_000L, parse(" 0:00:01.00 "))
        assertEquals(5_200_000L, parse("0:00:05:20"))
    }

    @Test
    fun `missing hours count as zero`() {
        assertEquals(5_200_000L, parse("00:05.20"))
    }

    @Test
    fun `anything else is not a timecode`() {
        for (text in listOf("", " ", "1:2", ":1:2.3", "1:2.3:4", "1.2:3", "1:2:3:4:5", "1:2:3.", "a:00:05.20",
            "1234567890:00:00.00")) {
            assertEquals(text, C.TIME_UNSET, parse(text))
        }
    }
}
