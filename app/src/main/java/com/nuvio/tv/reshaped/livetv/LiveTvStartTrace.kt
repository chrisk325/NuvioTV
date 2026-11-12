package com.nuvio.tv.reshaped.livetv

import android.util.Log

/**
 * Times a channel's start, from OK (or a channel change in the player) to its first picture, and
 * writes one line to the log: `adb logcat -s LiveTvStart`. Each step is the time since the start,
 * so the gap between two steps is where the wait was:
 * `player` the player screen began starting it, `prepare` the player was built and asked for the
 * stream, `connected` the stream's connection was there (opened, opened ahead, or taken over from
 * the preview), `tracks` its video and sound were recognised (the first key frame arrived),
 * `picture` the first frame was on screen.
 */
internal object LiveTvStartTrace {
    private const val TAG = "LiveTvStart"
    private var startNanos = 0L
    private val steps = ArrayList<Pair<String, Long>>(8)

    @Synchronized
    fun begin() {
        startNanos = System.nanoTime()
        steps.clear()
    }

    /** Notes [name] once per start; nothing outside a start (the preview opening a channel). */
    @Synchronized
    fun step(name: String) {
        if (startNanos == 0L || steps.any { it.first == name }) return
        steps += name to (System.nanoTime() - startNanos) / 1_000_000L
    }

    /** The first picture is on screen: logs the start. */
    @Synchronized
    fun picture() {
        if (startNanos == 0L) return
        step("picture")
        Log.i(TAG, steps.joinToString(", ") { (name, ms) -> "$name $ms ms" })
        startNanos = 0L
    }
}
