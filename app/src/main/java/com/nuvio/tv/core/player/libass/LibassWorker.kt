package com.nuvio.tv.core.player.libass

import android.os.Process

/**
 * The thread libass renders on. Laskco's libass also renders on up to three threads it starts from
 * it (four in all, never more than the cores), and they take its priority. At the display's priority
 * heavy .ass tracks took the CPU the stream's loader needs, and at the loader's own priority the
 * buffer still stopped growing on heavy openings, with libass's threads outnumbering the loader on
 * the cores (Overlay, CPU Blend and GPU Blend alike). So libass renders at nice 5: below the loader
 * (the default, 0) and the threads it waits on, the parallel download workers and the VOD cache
 * writer (nice 2, ParallelRangeDataSource and VodCacheWriteSink), and above
 * [Process.THREAD_PRIORITY_BACKGROUND] (10), from which Android also moves a thread to the
 * background scheduling group. With the cores free libass still gets all of them; under load
 * subtitles come out late rather than the stream running dry.
 * nuvio-libass's Overlay and CPU Blend workers get the same priority from the AAR patch
 * (app/libs/nuvio-libass-patch).
 */
internal object LibassWorker {
    const val PRIORITY = Process.THREAD_PRIORITY_DEFAULT + 5
}
