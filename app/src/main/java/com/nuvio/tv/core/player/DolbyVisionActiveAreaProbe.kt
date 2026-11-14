package com.nuvio.tv.core.player

/**
 * Reads the Dolby Vision L5 active area (the letterbox the picture was mastered with) from the
 * RPUs the extractor parses. UHD Blu-ray carry the bars of a scope film inside a 16:9 frame, and
 * L5 says exactly where the picture is without reading a frame back from the decoder, which most
 * TV chips cannot do for HDR or Dolby Vision video.
 *
 * Fed from the extractor's loading thread, read from the main thread.
 */
class DolbyVisionActiveAreaProbe(
    private val reader: (ByteArray, Int, Int, IntArray) -> Boolean = DoviBridge::readActiveArea
) {

    /** Offsets in pixels of the decoded frame. */
    data class ActiveArea(val left: Int, val right: Int, val top: Int, val bottom: Int) {
        val hasLetterbox: Boolean get() = top > 0 || bottom > 0
    }

    private val counts = HashMap<ActiveArea, Int>()
    private val scratch = IntArray(4)
    private var observed = 0
    private var misses = 0

    @Volatile
    private var done = false

    /** The most common active area once enough RPUs were read, or null while unknown. */
    @Volatile
    var activeArea: ActiveArea? = null
        private set

    val wantsRpu: Boolean get() = !done

    fun onRpu(nal: ByteArray, offset: Int, length: Int) {
        if (done) return
        if (!reader(nal, offset, length, scratch)) {
            // No libdovi, or RPUs without L5: stop looking.
            if (++misses >= MAX_MISSES && observed == 0) done = true
            return
        }
        record(ActiveArea(scratch[0], scratch[1], scratch[2], scratch[3]))
    }

    @Synchronized
    internal fun record(area: ActiveArea) {
        if (done) return
        observed++
        counts[area] = (counts[area] ?: 0) + 1
        if (observed >= MAX_RPUS) done = true
        if (observed >= SETTLED_RPUS || done) {
            // Ties go to the smaller bars, so the picture is never cropped past its edge.
            activeArea = counts.entries
                .maxWith(compareBy<Map.Entry<ActiveArea, Int>> { it.value }.thenBy { -(it.key.top + it.key.bottom) })
                .key
        }
    }

    internal companion object {
        // About 10 s and 60 s of 24 fps video; the extractor reads that far ahead in seconds.
        const val SETTLED_RPUS = 240
        const val MAX_RPUS = 1_500
        const val MAX_MISSES = 48
    }
}
