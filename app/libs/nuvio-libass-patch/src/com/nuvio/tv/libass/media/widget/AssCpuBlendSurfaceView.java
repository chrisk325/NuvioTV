package com.nuvio.tv.libass.media.widget;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.media3.common.util.Size;
import com.nuvio.tv.libass.AssRender;
import com.nuvio.tv.libass.media.AssHandler;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Overlay (CPU Blend) libass render mode: a SurfaceView over the video whose worker renders the
 * AssHandler's libass at each video frame and NEON-blends the images straight into the window's
 * buffers (AssRender.nativeCpuBlend*).
 *
 * Patched in NuvioTV, replacing the class built from AssCpuBlendSurfaceView.kt with the same API:
 * - The time AssHandler hands videoFrameCallback already has the subtitle delay, audio delay and
 *   calibration applied; it is used as given, as the atlas overlay does, not shifted a second time.
 * - A forced redraw with no new frame shows the last frame's moment again, not the file's start.
 * - The window's buffers are libass's frame size, the surface scaled by the render scale, which the
 *   compositor scales to the view; full-size buffers put scaled subtitles in a corner.
 * - libass's frame is pinned to those buffers and its storage to the video, as the atlas overlay
 *   pins its frame: AssHandler can keep the video surface's size, placing subtitles off the video.
 */
public final class AssCpuBlendSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "AssCpuBlend";

    static {
        System.loadLibrary("asskt");
    }

    private final AssHandler assHandler;
    private long nativeHandle;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private int surfaceWidth;
    private int surfaceHeight;
    private final AtomicReference<long[]> pendingRequest = new AtomicReference<>(null);
    private volatile long renderCount;
    private volatile long changedRenderCount;
    private final Runnable renderRunnable = () -> doRender(false);

    // The worker's.
    private long lastSubtitleTimeUs;
    private int bufferWidth;
    private int bufferHeight;
    private AssRender pinnedRender;
    private int pinnedGeneration;
    private int pinnedWidth;
    private int pinnedHeight;
    private int pinnedVideoWidth;
    private int pinnedVideoHeight;

    public AssCpuBlendSurfaceView(Context context, AssHandler assHandler) {
        super(context);
        if (assHandler == null) throw new NullPointerException("assHandler");
        this.assHandler = assHandler;
        setZOrderMediaOverlay(true);
        getHolder().setFormat(-3 /* PixelFormat.TRANSLUCENT */);
        getHolder().addCallback(this);
    }

    public long getRenderCount() {
        return renderCount;
    }

    public long getChangedRenderCount() {
        return changedRenderCount;
    }

    /** `subtitleTimeUs` is the time AssHandler hands videoFrameCallback, its delays applied. */
    public void requestRender(long subtitleTimeUs, long releaseTimeNs) {
        pendingRequest.set(new long[] {subtitleTimeUs, releaseTimeNs});
        Handler handler = workerHandler;
        if (handler != null) handler.post(renderRunnable);
    }

    public void invalidateSubtitles() {
        Handler handler = workerHandler;
        if (handler != null) handler.post(() -> doRender(true));
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Rect frame = holder.getSurfaceFrame();
        surfaceWidth = frame.width();
        surfaceHeight = frame.height();
        nativeHandle = AssRender.Companion.nativeCpuBlendCreate();
        setWindow(holder, surfaceWidth, surfaceHeight);
        assHandler.setOverlaySurfaceSize(surfaceWidth, surfaceHeight);
        HandlerThread thread = new HandlerThread(TAG, -4 /* THREAD_PRIORITY_DISPLAY */);
        thread.start();
        workerThread = thread;
        workerHandler = new Handler(thread.getLooper());
        Log.i(TAG, "surface created " + surfaceWidth + "x" + surfaceHeight +
                " buffers=" + bufferWidth + "x" + bufferHeight);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        assHandler.setOverlaySurfaceSize(width, height);
        Handler handler = workerHandler;
        if (handler != null) {
            handler.post(() -> {
                if (nativeHandle == 0L) return;
                setWindow(holder, width, height);
                doRender(true);
            });
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        HandlerThread thread = workerThread;
        Handler handler = workerHandler;
        if (handler != null) {
            handler.post(() -> {
                if (nativeHandle != 0L) AssRender.Companion.nativeCpuBlendSetWindow(nativeHandle, null, 0, 0);
            });
        }
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workerThread = null;
        workerHandler = null;
        if (nativeHandle != 0L) {
            AssRender.Companion.nativeCpuBlendRelease(nativeHandle);
            nativeHandle = 0L;
        }
        pinnedRender = null;
        Log.i(TAG, "surface destroyed renders=" + renderCount + " changed=" + changedRenderCount);
    }

    /** Buffers at libass's frame size: the surface by the render scale, as AssHandler sizes the frame. */
    private void setWindow(SurfaceHolder holder, int width, int height) {
        int w = AssAtlasPipelineConfig.INSTANCE.scaledForRender$nuvio_libass_release(width);
        int h = AssAtlasPipelineConfig.INSTANCE.scaledForRender$nuvio_libass_release(height);
        if (w <= 0 || h <= 0) return;
        bufferWidth = w;
        bufferHeight = h;
        pinnedRender = null;
        AssRender.Companion.nativeCpuBlendSetWindow(nativeHandle, holder.getSurface(), w, h);
    }

    /** The worker's: lays libass out on the buffers' frame and the video's storage, when either moved. */
    private void pin(AssRender render) {
        if (bufferWidth <= 0 || bufferHeight <= 0) return;
        Size video = assHandler.getVideoSize();
        int videoWidth = video != null ? video.getWidth() : 0;
        int videoHeight = video != null ? video.getHeight() : 0;
        if (render == pinnedRender && render.getStateGeneration() == pinnedGeneration &&
                pinnedWidth == bufferWidth && pinnedHeight == bufferHeight &&
                pinnedVideoWidth == videoWidth && pinnedVideoHeight == videoHeight) {
            return;
        }
        // libass does nothing for a size it already has.
        render.setFrameSize(bufferWidth, bufferHeight);
        if (videoWidth > 0 && videoHeight > 0) render.setStorageSize(videoWidth, videoHeight);
        pinnedRender = render;
        pinnedGeneration = render.getStateGeneration();
        pinnedWidth = bufferWidth;
        pinnedHeight = bufferHeight;
        pinnedVideoWidth = videoWidth;
        pinnedVideoHeight = videoHeight;
    }

    private void doRender(boolean force) {
        if (nativeHandle == 0L) return;
        long[] request = pendingRequest.getAndSet(null);
        if (request == null && !force) return;
        long subtitleTimeUs = request != null ? request[0] : lastSubtitleTimeUs;
        lastSubtitleTimeUs = subtitleTimeUs;
        AssRender render = assHandler.getRender();
        if (render == null || render.getReleased()) return;
        pin(render);
        renderCount++;
        if (render.cpuBlendRenderAndPost(nativeHandle, subtitleTimeUs / 1000, force)) changedRenderCount++;
    }
}
