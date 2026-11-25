package com.nuvio.tv.core.player.libass

import android.util.Log
import com.nuvio.tv.libass.AssRender
import com.nuvio.tv.libass.media.AssHandler

/**
 * How many threads libass renders with. nuvio-libass's libass is the threaded build, and its
 * renderer asks for one thread per core (`ass_set_threads(renderer, 0)`). Its pool is started from the
 * subtitle worker, at that worker's display priority, so on a heavy .ass track every core of a TV's
 * SoC renders subtitles ahead of the loader thread, the network and software decoding. The stream
 * then can't keep its buffer and the video stutters. Half the cores leaves the rest to playback.
 *
 * The pool is resized at libass's next frame, so this applies to every libass render mode.
 */
internal object LibassRenderThreads {
    private const val TAG = "LibassRenderThreads"
    private const val MAX_THREADS = 4

    /** libass's threads for a device with `cores` cores: half of them, from 1 to [MAX_THREADS]. */
    fun forCores(cores: Int): Int = (cores / 2).coerceIn(1, MAX_THREADS)

    /** Caps the threads of every renderer `handler` creates from now on, and of the one it has. */
    fun install(handler: AssHandler) {
        val previous = handler.renderCallback
        handler.renderCallback = { render ->
            capThreads(render)
            previous?.invoke(render)
        }
        handler.render?.let(::capThreads)
    }

    private fun capThreads(render: AssRender) {
        if (!AssGpuBlendNative.loaded) return
        val access = LibassAccess.instance ?: return
        val threads = forCores(Runtime.getRuntime().availableProcessors())
        val lock = access.lock(render)
        lock.lock()
        try {
            if (render.released) return
            val nativeRender = access.nativeRender(render)
            if (nativeRender == 0L) return
            val set = AssGpuBlendNative.setThreads(nativeRender, threads)
            Log.i(TAG, "libass render threads: $set")
        } catch (error: Throwable) {
            // An older libnuvio_ass_gpu without setThreads, or a libass without threads.
            Log.w(TAG, "can't set libass's render threads", error)
        } finally {
            lock.unlock()
        }
    }
}
