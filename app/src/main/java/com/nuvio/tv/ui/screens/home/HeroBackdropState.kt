package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object HeroBackdropState {
    @Volatile
    var currentHeroBackdropUrl: String? = null
        private set

    /** Last backdrop URL that was actually displayed — survives navigation for seamless back transitions. */
    @Volatile
    var lastDisplayedUrl: String? = null

    /** Observable copy of the last selected artwork; only the V2 page backdrop reads it. */
    var atmosphereUrl by mutableStateOf<String?>(null)
        private set

    fun update(url: String?) {
        currentHeroBackdropUrl = url
        if (!url.isNullOrBlank()) {
            lastDisplayedUrl = url
            if (atmosphereUrl != url) atmosphereUrl = url
        }
    }

    fun consumeAndClear(): String? {
        val url = currentHeroBackdropUrl
        currentHeroBackdropUrl = null
        return url
    }
}
