package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.DolbyVisionActiveAreaProbe.ActiveArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DolbyVisionActiveAreaProbeTest {

    private val scope = ActiveArea(left = 0, right = 0, top = 276, bottom = 276)
    private val full = ActiveArea(left = 0, right = 0, top = 0, bottom = 0)

    private fun probeReading(area: () -> ActiveArea?) = DolbyVisionActiveAreaProbe { _, _, _, out ->
        val next = area() ?: return@DolbyVisionActiveAreaProbe false
        out[0] = next.left; out[1] = next.right; out[2] = next.top; out[3] = next.bottom
        true
    }

    @Test
    fun `active area is unknown until enough RPUs were read`() {
        val probe = probeReading { scope }
        repeat(DolbyVisionActiveAreaProbe.SETTLED_RPUS - 1) { probe.onRpu(ByteArray(2), 0, 2) }
        assertNull(probe.activeArea)

        probe.onRpu(ByteArray(2), 0, 2)
        assertEquals(scope, probe.activeArea)
    }

    @Test
    fun `most common area wins over studio logos`() {
        var index = 0
        val probe = probeReading { if (index++ < 60) full else scope }
        repeat(DolbyVisionActiveAreaProbe.SETTLED_RPUS) { probe.onRpu(ByteArray(2), 0, 2) }

        assertEquals(scope, probe.activeArea)
    }

    @Test
    fun `stops looking when the RPUs carry no L5`() {
        val probe = probeReading { null }
        repeat(DolbyVisionActiveAreaProbe.MAX_MISSES) { probe.onRpu(ByteArray(2), 0, 2) }

        assertFalse(probe.wantsRpu)
        assertNull(probe.activeArea)
    }

    @Test
    fun `stops after its RPU budget`() {
        val probe = probeReading { scope }
        repeat(DolbyVisionActiveAreaProbe.MAX_RPUS) { probe.onRpu(ByteArray(2), 0, 2) }

        assertFalse(probe.wantsRpu)
    }
}
