@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.nuvio.tv.ui.v2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.createThemeBrush
import com.nuvio.tv.ui.v2.appearance.GlassQualityTokens
import com.nuvio.tv.ui.v2.appearance.LocalGlassTokens
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.appearance.LocalV2PlaybackActive
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

enum class GlassRole { NAVIGATION, CONTROL, PANEL, MODAL, HUD, CARD_FOCUS, TOOLTIP }

/** Shared page backdrop that frosted panels sample from (Cinematic Glass only). */
val LocalGlassBackdrop = staticCompositionLocalOf<HazeState?> { null }

/** Attach to still background layers so glass above them can frost what is behind. */
@Composable
fun Modifier.v2GlassSource(): Modifier {
    val state = LocalGlassBackdrop.current
    return if (state != null && LocalGlassTokens.current.liveBlur && !LocalV2PlaybackActive.current &&
        LocalV2Appearance.current?.visualStyle == VisualStyle.CINEMATIC_GLASS) hazeSource(state) else this
}

/** One bounded blur policy shared by surfaces. */
fun Modifier.v2GlassBlur(role: GlassRole, tokens: GlassQualityTokens, state: HazeState?): Modifier {
    if (role == GlassRole.CARD_FOCUS || !tokens.liveBlur || state == null) return this
    return hazeEffect(state = state) {
        backgroundColor = Color(0xFF080A0C)
        tints = listOf(HazeTint(Color.Transparent))
        blurRadius = tokens.blurRadiusDp.dp
        inputScale = HazeInputScale.Fixed(tokens.inputScale)
        noiseFactor = tokens.noise
    }
}

/** Smoked glass remains useful without blur; screens choose a role, never blur parameters. */
@Composable
fun NuvioGlassSurface(
    role: GlassRole,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
    hazeState: HazeState? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.nuvioGlass(role, focused, shape, hazeState), content = content)
}

/**
 * Cinematic Glass: translucent, frosted where a still backdrop exists, accent-tinted, lit rim.
 * Pure Liquid Dark: deep blue-black solid body with a quiet rim. No-op on the Original look.
 */
@Composable
fun Modifier.nuvioGlass(
    role: GlassRole,
    focused: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
    hazeState: HazeState? = null,
    trailingEdgeOnly: Boolean = false,
    neutralBackdrop: Boolean = false
): Modifier {
    val appearance = LocalV2Appearance.current ?: return this
    val playback = LocalV2PlaybackActive.current
    val tokens = if (playback) GlassQualityTokens.Performance else LocalGlassTokens.current
    val accent = NuvioTheme.colors.Secondary
    val dark = appearance.visualStyle == VisualStyle.PURE_LIQUID_DARK
    val cinematic = appearance.visualStyle == VisualStyle.CINEMATIC_GLASS
    val playbackGlass = playback && !dark
    val source = (hazeState ?: LocalGlassBackdrop.current).takeIf { !neutralBackdrop && !playback && !dark }
    val frosted = tokens.liveBlur && source != null
    val accentTint = !neutralBackdrop && !playbackGlass
    val accentColors = NuvioTheme.palette.accentGradient.takeIf { it.size > 1 } ?: listOf(NuvioTheme.palette.secondary)
    val focusColors = NuvioTheme.palette.focusRingGradient.takeIf { it.size > 1 } ?: listOf(accent)
    val transparency = appearance.glassTransparencyPercent
    val bodyAlpha = remember(dark, role, frosted, playback, transparency, cinematic, neutralBackdrop) {
        val legacyAlpha = when (role) {
            GlassRole.HUD -> if (playbackGlass) .38f else if (playback) .82f else .91f
            GlassRole.CONTROL -> if (playbackGlass) .18f else if (playback) .55f else if (dark) .80f else if (frosted) .48f else .84f
            GlassRole.NAVIGATION -> if (frosted) .48f else .94f
            GlassRole.MODAL -> if (frosted) .62f else .97f
            else -> if (playbackGlass) .28f else if (playback) .70f else if (dark) .94f else if (frosted) .50f else .94f
        }
        val alpha = if (neutralBackdrop) .96f
        else if (cinematic) glassBodyAlpha(role, playback, legacyAlpha, transparency) else legacyAlpha
        // Nothing to frost behind a modal (its own window): keep text contrast high.
        if (role == GlassRole.MODAL && !frosted) alpha.coerceAtLeast(.88f) else alpha
    }
    val background = remember(dark, playbackGlass, neutralBackdrop, bodyAlpha) {
        val alpha = bodyAlpha
        Brush.verticalGradient(
            if (neutralBackdrop) listOf(Color(0xFF202125).copy(alpha = alpha), Color(0xFF121317).copy(alpha = alpha))
            else if (playbackGlass) listOf(Color(0xFF353B40).copy(alpha = alpha), Color(0xFF101418).copy(alpha = alpha))
            else if (dark) listOf(Color(0xFF102332).copy(alpha = alpha), Color(0xFF040D16).copy(alpha = alpha))
            else listOf(Color(0xFF202225).copy(alpha = alpha), Color(0xFF0C0E11).copy(alpha = alpha))
        )
    }
    val edge = remember(tokens.edgeLayers, dark, playbackGlass) {
        Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (playbackGlass) .50f else if (dark) 0.12f else 0.18f),
            Color.White.copy(alpha = if (tokens.edgeLayers > 1) 0.08f else 0.03f),
            if (playbackGlass) Color.White.copy(alpha = .23f) else Color.Black.copy(alpha = 0.35f)
        ))
    }
    return clip(shape)
        .then(if (frosted) Modifier.v2GlassBlur(role, tokens, source) else Modifier)
        .background(background, shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val stroke = Stroke(1.dp.toPx())
            val accentBrush = createThemeBrush(accentColors)
            val focusBrush = createThemeBrush(focusColors)
            val reflection = if (playbackGlass) Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (role == GlassRole.CONTROL) .22f else .14f),
                .38f to Color.White.copy(alpha = .025f),
                .72f to Color.Transparent,
                1f to Color.White.copy(alpha = .06f)
            ) else Brush.linearGradient(listOf(
                Color.White.copy(alpha = .12f), Color.Transparent, Color.White.copy(alpha = .025f)))
            val rimInset = 1.5.dp.toPx()
            val innerOutline = if (!trailingEdgeOnly && playbackGlass && size.minDimension > rimInset * 2) {
                shape.createOutline(Size(size.width - rimInset * 2, size.height - rimInset * 2), layoutDirection, this)
            } else null
            val innerRim = Brush.verticalGradient(listOf(Color.White.copy(alpha = .13f), Color.Transparent, Color.Black.copy(alpha = .18f)))
            onDrawWithContent {
                if (accentTint) drawOutline(outline, accentBrush, alpha = if (focused) .20f else .09f)
                if (focused) drawOutline(outline, accentBrush, alpha = .16f)
                if (playback || (cinematic && tokens.edgeLayers > 1)) drawOutline(outline, reflection)
                drawContent()
                if (trailingEdgeOnly) {
                    // Edge-attached navigation has a divider, not a rectangular frame.
                    val x = if (layoutDirection == LayoutDirection.Ltr) size.width - stroke.width / 2f else stroke.width / 2f
                    drawLine(edge, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke.width)
                } else drawOutline(outline, edge, style = stroke)
                if (innerOutline != null) translate(rimInset, rimInset) {
                    drawOutline(innerOutline, innerRim, style = stroke)
                }
                if (focused) drawOutline(outline, focusBrush, alpha = .8f, style = stroke)
            }
        }
}
