package com.nuvio.tv.ui.v2.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.createThemeBrush
import com.nuvio.tv.ui.v2.appearance.LocalGlassTokens
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance

/**
 * Cinematic focus: an accent bloom with a bright rim and a small lift. Decorates an existing
 * focus target without adding another focus node. No-op on the Original look.
 * [hardwareShadow] false for see-through glass: a hardware shadow shows through it as a polygon.
 */
@Composable
fun Modifier.nuvioV2Focus(
    focused: Boolean,
    shape: Shape,
    accentOverride: Color? = null,
    hardwareShadow: Boolean = true,
    stationary: Boolean = false
): Modifier {
    if (stationary && !focused) return this
    LocalV2Appearance.current ?: return this
    val tokens = LocalGlassTokens.current
    val density = LocalDensity.current
    val accent = accentOverride ?: NuvioTheme.colors.Secondary
    val focusColors = accentOverride?.let { listOf(it) }
        ?: NuvioTheme.palette.focusRingGradient.takeIf { it.size > 1 } ?: listOf(accent)
    val progress = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (stationary) snap() else tween(if (focused) V2Motion.FocusResponseMs else V2Motion.FocusSettleMs),
        label = "v2Focus"
    )
    val visible = remember(progress) { derivedStateOf { progress.value > 0f } }
    val transformLayer = if (stationary) Modifier else Modifier.graphicsLayer {
        val p = progress.value
        scaleX = 1f + (V2FocusTransform.scale - 1f) * p
        scaleY = scaleX
        translationY = -V2FocusTransform.liftDp * density.density * p
        shadowElevation = if (hardwareShadow) tokens.shadowDp * density.density * p else 0f
        this.shape = shape
        clip = false
        ambientShadowColor = if (p > 0f && hardwareShadow) accent.copy(alpha = tokens.focusBloomAlpha) else Color.Transparent
        spotShadowColor = Color.Black
    }
    return then(transformLayer).drawWithCache {
        if (!visible.value) return@drawWithCache onDrawWithContent { drawContent() }
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(2.dp.toPx())
        // Closely spaced, low-alpha layers avoid visible concentric bands on TV panels.
        val bloomWidths = when {
            tokens.edgeLayers <= 1 -> listOf(10, 6, 3)
            tokens.edgeLayers == 2 -> listOf(12, 8, 5, 3)
            else -> listOf(20, 14, 10, 7, 5, 3)
        }
        val bloomAlpha = tokens.focusBloomAlpha.coerceAtLeast(.10f)
        val blooms = bloomWidths.map { width ->
            val falloff = (25f - width) / 22f
            Stroke(width.dp.toPx()) to (.015f + .16f * falloff * falloff) * (bloomAlpha / .10f)
        }
        val bloomBrush = createThemeBrush(focusColors)
        val edgeBrush = createThemeBrush(focusColors.map { lerp(it, Color.White, .7f) })
        onDrawWithContent {
            drawContent()
            val p = progress.value
            if (p > 0f) {
                blooms.forEach { (width, alpha) -> drawOutline(outline, bloomBrush, alpha = p * alpha, style = width) }
                drawOutline(outline, edgeBrush, alpha = p, style = stroke)
            }
        }
    }
}

/** Adds shared V2 decoration to an existing control without replacing its key/click handling. */
@Composable
fun Modifier.nuvioControlSurface(shape: Shape, role: GlassRole = GlassRole.CONTROL): Modifier {
    if (LocalV2Appearance.current == null) return this
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.hasFocus }
        .nuvioV2Focus(focused, shape, stationary = true)
        .nuvioGlass(role, focused, shape)
}
