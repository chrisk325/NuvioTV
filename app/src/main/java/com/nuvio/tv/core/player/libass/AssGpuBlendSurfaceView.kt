package com.nuvio.tv.core.player.libass

import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.nuvio.tv.libass.AssRender
import com.nuvio.tv.libass.media.AssHandler
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * The Overlay (GPU Blend) libass render mode, beside nuvio-libass's AssCpuBlendSurfaceView and laid
 * out the same way: a SurfaceView over the video whose worker renders the AssHandler's libass at each
 * video frame and posts the result. Here the images become masks in an atlas that the GPU blends as
 * tinted quads, as NuvioMobile's libmpv libass overlay does, and each buffer carries the time its
 * video frame shows at, so the compositor shows both together. HDR output is kept, like the other
 * overlay modes. Where GL can't start, the masks are blended on the CPU instead.
 */
class AssGpuBlendSurfaceView(
    context: Context,
    private val assHandler: AssHandler
) : SurfaceView(context), SurfaceHolder.Callback {

    private var nativeHandle = 0L
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val pendingRequest = AtomicReference<LongArray?>(null)

    /** The worker's: the last video frame time asked for, which a forced redraw shows again. */
    private var lastPresentationTimeUs = 0L

    /** The worker's: the view size the window was last attached at. */
    private var attachedWidth = 0
    private var attachedHeight = 0

    @Volatile
    var renderCount = 0L
        private set

    @Volatile
    var changedRenderCount = 0L
        private set

    @Volatile
    var gpuActive = false
        private set

    /** The worker's. */
    private val pacer = LibassRenderPacer()

    private val renderRunnable = Runnable { doRender(force = false) }

    init {
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    /**
     * Draws what shows at `presentationTimeUs` (the video frame's time with the AssHandler's delays
     * applied), for the frame the screen shows at `releaseTimeNs`.
     */
    fun requestRender(presentationTimeUs: Long, releaseTimeNs: Long) {
        pendingRequest.set(longArrayOf(presentationTimeUs, releaseTimeNs))
        workerHandler?.post(renderRunnable)
    }

    /** Redraws the current moment, as after a layout or style change. */
    fun invalidateSubtitles() {
        workerHandler?.post { doRender(force = true) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        surfaceWidth = frame.width()
        surfaceHeight = frame.height()
        assHandler.setOverlaySurfaceSize(surfaceWidth, surfaceHeight)
        val thread = HandlerThread(TAG, LibassRenderThreads.WORKER_PRIORITY).also { it.start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        workerHandler = handler
        val small = isLowMemoryDevice(context)
        handler.post {
            if (!AssGpuBlendNative.loaded) {
                Log.e(TAG, "libnuvio_ass_gpu is missing")
                return@post
            }
            nativeHandle = AssGpuBlendNative.create(small)
            if (nativeHandle == 0L) Log.e(TAG, "libass is missing from nuvio-libass")
            // surfaceChanged follows with the window's size, which attaches it.
        }
        Log.i(TAG, "surfaceCreated ${surfaceWidth}x$surfaceHeight small=$small")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        assHandler.setOverlaySurfaceSize(width, height)
        workerHandler?.post {
            if (nativeHandle == 0L) return@post
            if (attachedWidth == width && attachedHeight == height) doRender(force = true)
            else attachWindow(holder, width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val thread = workerThread
        workerHandler?.post {
            if (nativeHandle != 0L) {
                AssGpuBlendNative.setWindow(nativeHandle, null, 0, 0)
                AssGpuBlendNative.release(nativeHandle)
                nativeHandle = 0L
            }
            attachedWidth = 0
            attachedHeight = 0
        }
        thread?.quitSafely()
        thread?.join(2000)
        workerThread = null
        workerHandler = null
        Log.i(TAG, "surfaceDestroyed renders=$renderCount changed=$changedRenderCount")
    }

    /** The worker's. Buffers are libass's frame size, which the compositor scales to the view. */
    private fun attachWindow(holder: SurfaceHolder, width: Int, height: Int) {
        val scale = renderScale()
        val bufferWidth = if (scale == 1f) width else (width * scale).roundToInt()
        val bufferHeight = if (scale == 1f) height else (height * scale).roundToInt()
        if (bufferWidth <= 0 || bufferHeight <= 0) return
        gpuActive = AssGpuBlendNative.setWindow(nativeHandle, holder.surface, bufferWidth, bufferHeight)
        attachedWidth = width
        attachedHeight = height
        Log.i(
            TAG,
            "window ${width}x$height buffers=${bufferWidth}x$bufferHeight gpu=$gpuActive " +
                "stamped=${gpuActive && AssGpuBlendNative.stamped(nativeHandle)}"
        )
        doRender(force = true)
    }

    /** The worker's. A request is paced by [pacer]; a forced redraw isn't. */
    private fun doRender(force: Boolean) {
        if (nativeHandle == 0L) return
        if (!force && pendingRequest.get() == null) return
        val startMs = SystemClock.uptimeMillis()
        if (!force) {
            val waitMs = pacer.waitMs(startMs)
            if (waitMs > 0) {
                // One render, of the newest frame by then, once libass has had its break.
                workerHandler?.let { handler ->
                    handler.removeCallbacks(renderRunnable)
                    handler.postAtTime(renderRunnable, startMs + waitMs)
                }
                return
            }
        }
        val request = pendingRequest.getAndSet(null)
        if (request == null && !force) return
        try {
            renderAndPost(request, force)
        } finally {
            val endMs = SystemClock.uptimeMillis()
            pacer.rendered(startMs, endMs)
            pacer.report(endMs)?.let { Log.i(TAG, it) }
        }
    }

    /** The worker's. */
    private fun renderAndPost(request: LongArray?, force: Boolean) {
        val presentationTimeUs = request?.get(0) ?: lastPresentationTimeUs
        val releaseTimeNs = request?.get(1) ?: 0L
        lastPresentationTimeUs = presentationTimeUs
        val assRender = assHandler.render ?: return
        if (assRender.released) return
        // AssHandler hands videoFrameCallback the subtitle time already, delays and calibration
        // applied, as the atlas overlay takes it; applying them again would double the delay.
        renderCount++
        val rendered = renderLocked(assRender, presentationTimeUs / 1000)
        if (rendered < 0 || (rendered == 0 && !force)) return
        // Drawn and swapped outside libass's lock, so the demuxer's lines don't wait on the GPU.
        if (AssGpuBlendNative.post(nativeHandle, releaseTimeNs)) changedRenderCount++
    }

    /** As AssRender.cpuBlendRenderAndPost: libass is used under the lock its Ass shares with its tracks. */
    private fun renderLocked(render: AssRender, timeMs: Long): Int {
        val access = LibassAccess.instance ?: return -1
        val lock = access.lock(render)
        lock.lock()
        try {
            if (render.released) return -1
            val nativeRender = access.nativeRender(render)
            if (nativeRender == 0L) return -1
            val track = access.track(render)
            val nativeTrack = if (track != null && !track.released) {
                access.drainPendingChunks(track)
                track.nativeAssTrack
            } else {
                0L
            }
            val videoSize = assHandler.videoSize
            return AssGpuBlendNative.render(
                nativeHandle, nativeRender, nativeTrack, timeMs,
                videoSize.width, videoSize.height
            )
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val TAG = "AssGpuBlend"

        /** AssHandler.setRenderScale's, which sizes libass's frame. */
        fun renderScale(): Float = runCatching {
            val config = Class.forName("com.nuvio.tv.libass.media.widget.AssAtlasPipelineConfig")
            val instance = config.getField("INSTANCE").get(null)
            config.getMethod("getRenderScale\$nuvio_libass_release").invoke(instance) as Float
        }.getOrDefault(1f).takeIf { it > 0f } ?: 1f

        fun isLowMemoryDevice(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return true
            if (activityManager.isLowRamDevice) return true
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            return info.totalMem < 3L * 1024 * 1024 * 1024
        }
    }
}
