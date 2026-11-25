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

    @Test
    fun resolve_automatic_isHalfTheCores() {
        assertEquals(2, LibassRenderThreads.resolve(0, 4))
        assertEquals(4, LibassRenderThreads.resolve(0, 12))
    }

    @Test
    fun resolve_chosenCount_isUsedAsGiven() {
        assertEquals(1, LibassRenderThreads.resolve(1, 4))
        assertEquals(2, LibassRenderThreads.resolve(2, 8))
        assertEquals(4, LibassRenderThreads.resolve(4, 8))
    }

    @Test
    fun resolve_chosenCount_isNeverMoreThanTheCores() {
        assertEquals(2, LibassRenderThreads.resolve(4, 2))
        assertEquals(1, LibassRenderThreads.resolve(4, 0))
    }
}
