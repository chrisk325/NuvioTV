package com.nuvio.tv.core.player

import android.os.SystemClock
import androidx.annotation.VisibleForTesting

/**
 * Tells the playback thread when the user is working the player UI, so loading can back off
 * while the buffer is already healthy.
 *
 * At 60-70 Mbps every second of top-up is TLS decrypt, extractor parsing and copies on the
 * same few cores that draw the controls. Holding that work while a menu is open or a key was
 * just pressed frees those cores for the UI; [InteractionAwareLoadControl] only honours the
 * hold above a comfortable buffer, so it can never starve playback.
 *
 * Written from the main thread, read from the playback thread.
 */
object PlaybackInteractionGate {

    /** How long a single key press keeps the gate held. */
    const val KEY_HOLD_MS = 4_000L

    @Volatile
    private var uiActive = false

    @Volatile
    private var holdUntilMs = 0L

    @VisibleForTesting
    @Volatile
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** Controls, a panel or a dialog is on screen. */
    fun setUiActive(active: Boolean) {
        uiActive = active
    }

    /** A key was pressed; hold for [KEY_HOLD_MS] from now. */
    fun noteInteraction() {
        holdUntilMs = clock() + KEY_HOLD_MS
    }

    fun isHolding(): Boolean = uiActive || clock() < holdUntilMs

    /** Leaving the player must never leave the next session's loading held. */
    fun reset() {
        uiActive = false
        holdUntilMs = 0L
    }
}
