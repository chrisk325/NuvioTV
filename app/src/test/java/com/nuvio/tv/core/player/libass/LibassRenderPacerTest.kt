package com.nuvio.tv.core.player.libass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LibassRenderPacerTest {

    @Test
    fun lightRenders_areNotPaced() {
        val pacer = LibassRenderPacer()
        pacer.rendered(startMs = 1_000, endMs = 1_008)
        assertEquals(0, pacer.waitMs(1_008))
    }

    @Test
    fun heavyRender_waitsAsLongAgain() {
        val pacer = LibassRenderPacer()
        pacer.rendered(startMs = 1_000, endMs = 1_060)
        assertEquals(60, pacer.waitMs(1_060))
        assertEquals(20, pacer.waitMs(1_100))
        assertEquals(0, pacer.waitMs(1_120))
    }

    @Test
    fun wait_isAtMostMaxIdle() {
        val pacer = LibassRenderPacer()
        pacer.rendered(startMs = 1_000, endMs = 3_000)
        assertEquals(LibassRenderPacer.MAX_IDLE_MS, pacer.waitMs(3_000))
    }

    @Test
    fun lightRenderAfterHeavy_endsThePacing() {
        val pacer = LibassRenderPacer()
        pacer.rendered(startMs = 1_000, endMs = 1_100)
        pacer.rendered(startMs = 1_200, endMs = 1_202)
        assertEquals(0, pacer.waitMs(1_202))
    }

    @Test
    fun report_comesEveryReportPeriod_withTheRenderTimes() {
        val pacer = LibassRenderPacer()
        assertNull(pacer.report(1_000))
        pacer.rendered(startMs = 1_000, endMs = 1_040)
        pacer.waitMs(1_050)
        pacer.rendered(startMs = 1_100, endMs = 1_120)
        assertNull(pacer.report(5_000))
        val line = pacer.report(11_000)
        assertNotNull(line)
        assertEquals("renders=2 avg=30ms max=40ms deferred=1 in 10000ms", line)
        assertNull(pacer.report(12_000))
    }
}
