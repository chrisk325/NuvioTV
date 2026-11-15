package com.nuvio.tv.ui.v2.appearance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.nuvio.tv.domain.model.V2AppearancePreferences

/** Null while the Original look is selected, so every V2 decoration is skipped. */
val LocalV2Appearance = staticCompositionLocalOf<V2AppearancePreferences?> { null }

/** True inside the player: glass there stays static (no live blur over decoded video). */
val LocalV2PlaybackActive = staticCompositionLocalOf { false }

/** Effect cost only: never contains card sizes, spacing, text sizes or focus destinations. */
@Immutable
data class GlassQualityTokens(
    val liveBlur: Boolean,
    val blurRadiusDp: Float,
    val inputScale: Float,
    val surfaceAlpha: Float,
    val noise: Float,
    val edgeLayers: Int,
    val focusBloomAlpha: Float,
    val shadowDp: Float
) {
    /** Scale only the blur budget: transparency, focus and capture resolution stay independent. */
    fun withBlurStrength(percent: Int): GlassQualityTokens {
        if (!liveBlur) return this
        val strength = percent.coerceIn(0, 100) / 100f
        return copy(
            liveBlur = strength > 0f,
            blurRadiusDp = blurRadiusDp * strength,
            inputScale = if (strength > 0f) inputScale else 0f,
            noise = noise * strength
        )
    }

    companion object {
        val Performance = GlassQualityTokens(false, 0f, 0f, 0.96f, 0f, 1, 0.04f, 2f)
        val Enhanced = GlassQualityTokens(true, 16f, 0.33f, 0.82f, 0.015f, 2, 0.10f, 6f)

        /** Conservative device policy: live blur only where the GPU path is cheap enough. */
        fun forDevice(context: Context): GlassQualityTokens {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memory = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
            val capable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                manager?.isLowRamDevice != true &&
                memory.totalMem >= 2_500_000_000L
            return if (capable) Enhanced else Performance.copy(edgeLayers = 2, focusBloomAlpha = 0.10f, shadowDp = 6f)
        }
    }
}

val LocalGlassTokens = staticCompositionLocalOf { GlassQualityTokens.Performance }
