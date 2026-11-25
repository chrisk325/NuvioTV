package com.nuvio.tv.core.player.libass

/**
 * Paces an overlay's libass renders. The overlays render at every video frame, so on heavy
 * typesetting, where a frame takes libass longer than the video's frame time, the worker and libass's
 * threads ran without a break and the stream's loader and decoders got what was left. After a render
 * longer than [freeMs], the next waits as long again (at most [maxIdleMs]): libass's threads are busy
 * half the time at most, and subtitles update less often instead of the stream running dry. Light
 * subtitles render at every frame as before.
 *
 * Also keeps the render times, which [report] logs. One thread uses it: the overlay's worker.
 */
internal class LibassRenderPacer(
    private val freeMs: Long = FREE_MS,
    private val maxIdleMs: Long = MAX_IDLE_MS
) {
    /** When the next render may start, on [android.os.SystemClock.uptimeMillis]'s clock. */
    var nextRenderAtMs = 0L
        private set

    private var renders = 0
    private var deferred = 0
    private var totalMs = 0L
    private var maxMs = 0L
    private var reportedAtMs = 0L

    /** How long a render asked for at `nowMs` has to wait; 0 to render now. */
    fun waitMs(nowMs: Long): Long {
        val wait = nextRenderAtMs - nowMs
        if (wait <= 0) return 0
        deferred++
        return wait
    }

    /** A render that ran from `startMs` to `endMs`. */
    fun rendered(startMs: Long, endMs: Long) {
        val took = (endMs - startMs).coerceAtLeast(0)
        renders++
        totalMs += took
        if (took > maxMs) maxMs = took
        nextRenderAtMs = if (took <= freeMs) 0 else endMs + took.coerceAtMost(maxIdleMs)
    }

    /**
     * Every [REPORT_MS], a line with the renders since the last: how many, their average and longest
     * times, and how many requests waited. Null when it isn't time yet or nothing rendered.
     */
    fun report(nowMs: Long): String? {
        if (reportedAtMs == 0L) reportedAtMs = nowMs
        if (nowMs - reportedAtMs < REPORT_MS || renders == 0) return null
        val line = "renders=$renders avg=${totalMs / renders}ms max=${maxMs}ms deferred=$deferred " +
            "in ${nowMs - reportedAtMs}ms"
        renders = 0
        deferred = 0
        totalMs = 0
        maxMs = 0
        reportedAtMs = nowMs
        return line
    }

    companion object {
        /** Half a 60 fps frame: a render this short leaves the cores free enough. */
        const val FREE_MS = 8L
        const val MAX_IDLE_MS = 500L
        const val REPORT_MS = 10_000L
    }
}
