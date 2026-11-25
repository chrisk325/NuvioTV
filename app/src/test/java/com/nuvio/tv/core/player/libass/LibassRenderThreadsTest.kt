package com.nuvio.tv.core.player.libass

import org.junit.Assert.assertEquals
import org.junit.Test

class LibassRenderThreadsTest {

    @Test
    fun forCores_leavesHalfTheCoresToPlayback() {
        assertEquals(2, LibassRenderThreads.forCores(4))
        assertEquals(3, LibassRenderThreads.forCores(6))
        assertEquals(4, LibassRenderThreads.forCores(8))
    }

    @Test
    fun forCores_rendersOnOneThreadAtLeast() {
        assertEquals(1, LibassRenderThreads.forCores(1))
        assertEquals(1, LibassRenderThreads.forCores(2))
        assertEquals(1, LibassRenderThreads.forCores(0))
    }

    @Test
    fun forCores_takesFourThreadsAtMost() {
        assertEquals(4, LibassRenderThreads.forCores(16))
    }
}
