package com.nuvio.tv.ui.screens.player

import android.graphics.Matrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import kotlin.math.abs

@androidx.annotation.OptIn(UnstableApi::class)
internal class KeystoneZoomEffect : MatrixTransformation {
    @Volatile
    private var videoFraction = AspectScale(scaleX = 1f, scaleY = 1f)

    fun setVideoFraction(fraction: AspectScale) {
        videoFraction = fraction
    }

    /** True while frames are drawn unscaled, so the surface shows the source frame as decoded. */
    val isIdentity: Boolean
        get() = videoFraction.let { abs(it.scaleX - 1f) < 0.001f && abs(it.scaleY - 1f) < 0.001f }

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val fraction = videoFraction
        return Matrix().apply { setScale(fraction.scaleX, fraction.scaleY) }
    }
}
