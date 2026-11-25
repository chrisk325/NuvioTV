package com.nuvio.tv.core.player.libass

import android.os.Process

/**
 * The thread libass renders on. Laskco's libass starts its event threads from it, and they take its
 * priority. At the display's priority, heavy .ass tracks took the CPU the stream's loader and decoders
 * need, and playback buffered. So subtitles render at the loader's priority, and under load they come
 * out late rather than the stream running dry. nuvio-libass's own workers get the same priority from
 * the AAR patch (app/libs/nuvio-libass-patch).
 */
internal object LibassWorker {
    const val PRIORITY = Process.THREAD_PRIORITY_DEFAULT
}
