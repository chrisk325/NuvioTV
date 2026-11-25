package com.nuvio.tv.core.player.libass

import android.util.Log
import com.nuvio.tv.libass.AssRender
import com.nuvio.tv.libass.AssTrack
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.locks.ReentrantLock

/** nuvio-libass keeps AssRender's libass pointers to itself; this reads them as its CPU blend does. */
internal class LibassAccess private constructor(
    private val lockField: Field,
    private val nativeRenderField: Field,
    private val trackField: Field,
    private val drainMethod: Method
) {
    fun lock(render: AssRender) = lockField.get(render) as ReentrantLock
    fun nativeRender(render: AssRender) = nativeRenderField.getLong(render)
    fun track(render: AssRender) = trackField.get(render) as AssTrack?
    fun drainPendingChunks(track: AssTrack) {
        drainMethod.invoke(track)
    }

    companion object {
        private const val TAG = "LibassAccess"

        val instance: LibassAccess? by lazy {
            runCatching {
                val render = AssRender::class.java
                LibassAccess(
                    render.getDeclaredField("lock").apply { isAccessible = true },
                    render.getDeclaredField("nativeRender").apply { isAccessible = true },
                    render.getDeclaredField("track").apply { isAccessible = true },
                    AssTrack::class.java.getMethod("drainPendingChunks\$nuvio_libass_release")
                )
            }.onFailure { Log.e(TAG, "nuvio-libass's AssRender has changed", it) }.getOrNull()
        }
    }
}
