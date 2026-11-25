package com.nuvio.tv.core.player.libass

import android.view.Surface

/**
 * libass's Overlay (GPU Blend) render mode, from `libnuvio_ass_gpu.so`: each moment's images become
 * masks that the GPU blends as tinted quads. Every call but [create] takes its handle, on the one
 * thread that owns it.
 */
internal object AssGpuBlendNative {
    val loaded: Boolean = runCatching { System.loadLibrary("nuvio_ass_gpu") }.isSuccess

    /** Zero when nuvio-libass's libass can't be found. `small` for a device short of memory. */
    @JvmStatic
    external fun create(small: Boolean): Long

    /** False when the GPU can't draw into the window, and the CPU blends into it instead. */
    @JvmStatic
    external fun setWindow(handle: Long, surface: Surface?, width: Int, height: Int): Boolean

    /**
     * Renders with the AssHandler's raw libass pointers, under its lock, into masks copied out of
     * libass: 1 when the picture changed, 0 when it didn't, -1 when it couldn't be made. libass's
     * frame is pinned to the window's buffers and its storage to the video's size (0 when unknown).
     */
    @JvmStatic
    external fun render(
        handle: Long,
        renderer: Long,
        track: Long,
        timeMs: Long,
        videoWidth: Int,
        videoHeight: Int
    ): Int

    /** Draws the rendered picture, with no lock, for the screen to show at `atNanos`, or at once when 0. */
    @JvmStatic
    external fun post(handle: Long, atNanos: Long): Boolean

    /** Whether a posted picture carries the time its video frame shows at. */
    @JvmStatic
    external fun stamped(handle: Long): Boolean

    @JvmStatic
    external fun release(handle: Long)

    /**
     * Sets how many threads libass's `renderer` renders with, under its lock, from its next frame.
     * The count libass took, or 0 when its libass isn't the threaded build.
     */
    @JvmStatic
    external fun setThreads(renderer: Long, threads: Int): Int
}
