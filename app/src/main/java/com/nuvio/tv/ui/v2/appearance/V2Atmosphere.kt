package com.nuvio.tv.ui.v2.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.v2GlassSource
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate

/**
 * Still page background behind V2 screens. Cinematic Glass paints the last selected title artwork
 * under a cool shade and accent ambience; Pure Liquid Dark paints dark silk folds.
 * Draws nothing on the Original look.
 */
@Composable
fun V2Atmosphere(modifier: Modifier = Modifier, rich: Boolean = true) {
    val appearance = LocalV2Appearance.current ?: return
    val cinematic = appearance.visualStyle == VisualStyle.CINEMATIC_GLASS
    val showArtwork = rich && cinematic
    val accent = NuvioTheme.colors.Secondary
    val context = LocalContext.current
    val artwork = HeroBackdropState.atmosphereUrl
    val settledArtwork by produceState<String?>(null, artwork, showArtwork) {
        value = null
        if (showArtwork && artwork != null) {
            delay(250)
            value = artwork
        }
    }
    val request = remember(context, settledArtwork) {
        settledArtwork?.let {
            ImageRequest.Builder(context).data(it).size(960, 540)
                .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }
    Box(modifier.fillMaxSize().background(Color(0xFF040B13)).v2GlassSource().v2StaticBackdrop()) {
        if (request != null) {
            key(settledArtwork) {
                AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop,
                    alpha = .82f, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.fillMaxSize().drawWithCache {
            val ambience = Brush.radialGradient(
                listOf(accent.copy(alpha = if (rich) .15f else .045f), Color.Transparent),
                center = Offset(size.width * .77f, size.height * .15f), radius = size.width * .8f
            )
            val shade = Brush.horizontalGradient(listOf(Color(0xED040B13), Color(0x40040B13), Color(0x70040B13)))
            val folds = (0..2).map { i ->
                Path().apply {
                    val x = size.width * (.50f + i * .14f)
                    moveTo(x, size.height)
                    cubicTo(x - size.width * .24f, size.height * .56f, x + size.width * .34f, size.height * .38f, x + size.width * .12f, 0f)
                    lineTo(x + size.width * .17f, 0f)
                    cubicTo(x + size.width * .4f, size.height * .36f, x - size.width * .17f, size.height * .6f, x + size.width * .08f, size.height)
                    close()
                }
            }
            val silk = Brush.linearGradient(listOf(Color.Transparent, Color(0x123E6078), Color(0x07040B13)))
            onDrawBehind {
                drawRect(ambience)
                if (!cinematic) folds.forEach { drawPath(it, silk) }
                drawRect(shade)
            }
        })
    }
}

/** Paints a still page background once and reuses it while the page above it moves. */
fun Modifier.v2StaticBackdrop(): Modifier = graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)

/** Wraps a full page: on V2 looks the atmosphere is painted behind it, otherwise nothing changes. */
@Composable
fun V2PageBackdrop(modifier: Modifier = Modifier, rich: Boolean = true, content: @Composable () -> Unit) {
    if (LocalV2Appearance.current == null) {
        content()
    } else {
        Box(modifier.fillMaxSize()) {
            V2Atmosphere(rich = rich)
            content()
        }
    }
}

/** The page's own solid background on the Original look; transparent over the V2 atmosphere. */
@Composable
fun Modifier.v2PageBackground(original: Color): Modifier =
    if (LocalV2Appearance.current == null) background(original) else this

/**
 * Drop-in for a page's `background(original)`: the exact original fill on the Original look,
 * the V2 atmosphere (artwork or liquid folds) drawn behind the page on V2 looks.
 */
@Composable
fun Modifier.v2PageAtmosphere(original: Color): Modifier {
    val appearance = LocalV2Appearance.current ?: return background(original)
    val cinematic = appearance.visualStyle == VisualStyle.CINEMATIC_GLASS
    val accent = NuvioTheme.colors.Secondary
    val context = LocalContext.current
    val artwork = HeroBackdropState.atmosphereUrl.takeIf { cinematic }
    val request = remember(context, artwork) {
        artwork?.let {
            ImageRequest.Builder(context).data(it).size(960, 540)
                .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }
    val painter = coil3.compose.rememberAsyncImagePainter(request)
    return background(Color(0xFF040B13)).drawWithCache {
        val ambience = Brush.radialGradient(
            listOf(accent.copy(alpha = .15f), Color.Transparent),
            center = Offset(size.width * .77f, size.height * .15f), radius = size.width * .8f
        )
        val shade = Brush.horizontalGradient(listOf(Color(0xED040B13), Color(0x40040B13), Color(0x70040B13)))
        val folds = (0..2).map { i ->
            Path().apply {
                val x = size.width * (.50f + i * .14f)
                moveTo(x, size.height)
                cubicTo(x - size.width * .24f, size.height * .56f, x + size.width * .34f, size.height * .38f, x + size.width * .12f, 0f)
                lineTo(x + size.width * .17f, 0f)
                cubicTo(x + size.width * .4f, size.height * .36f, x - size.width * .17f, size.height * .6f, x + size.width * .08f, size.height)
                close()
            }
        }
        val silk = Brush.linearGradient(listOf(Color.Transparent, Color(0x123E6078), Color(0x07040B13)))
        onDrawBehind {
            if (request != null) {
                val intrinsic = painter.intrinsicSize
                if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
                    val scale = maxOf(size.width / intrinsic.width, size.height / intrinsic.height)
                    val drawn = androidx.compose.ui.geometry.Size(intrinsic.width * scale, intrinsic.height * scale)
                    clipRect {
                        translate((size.width - drawn.width) / 2f, (size.height - drawn.height) / 2f) {
                            with(painter) { draw(drawn, alpha = .82f) }
                        }
                    }
                }
            }
            drawRect(ambience)
            if (!cinematic) folds.forEach { drawPath(it, silk) }
            drawRect(shade)
        }
    }
}
