package com.nuvio.tv.ui.v2.components

object V2Motion {
    const val FocusResponseMs = 100
    const val FocusSettleMs = 160
    const val PanelInMs = 220
    const val PanelOutMs = 180
}

/** Cinematic focus: a gentle lift, matching the Nuvio V2 cards. */
internal object V2FocusTransform {
    const val scale = 1.05f
    const val liftDp = 3f
}
