package com.nuvio.tv.core.cache

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.LocalAlwaysBackdropWithLogo
import com.nuvio.tv.ui.components.LocalLandscapePosterMode

/**
 * The poster cache target for [this] item as a poster card shows it (unfocused): the poster, or
 * in landscape poster mode the landscape poster / backdrop. Mirrors the image choice of
 * `ContentCard` and `GridContentCard` so the prefetched copy is the one the card loads.
 */
internal fun MetaPreview.posterCacheTarget(
    landscapeMode: Boolean,
    alwaysBackdropWithLogo: Boolean,
): ImageCacheTarget? {
    val imageUrl = if (landscapeMode) {
        val landscape = if (alwaysBackdropWithLogo) null else landscapePoster
        landscape ?: background ?: poster
    } else {
        poster
    }?.takeIf { it.isNotBlank() } ?: return null
    val fallback = rawPosterUrl?.takeIf { it.isNotBlank() && it != imageUrl }
    return ImageCacheTarget(imageUrl = imageUrl, fallbackImageUrl = fallback)
}

/**
 * Poster cache: downloads every poster of [items] in the background, including the ones that are
 * not on screen yet (no-op while the poster cache is disabled).
 */
@Composable
fun PosterCachePrefetchEffect(items: List<MetaPreview>) {
    val landscapeMode = LocalLandscapePosterMode.current
    val alwaysBackdropWithLogo = LocalAlwaysBackdropWithLogo.current
    val targets = remember(items, landscapeMode, alwaysBackdropWithLogo) {
        items.mapNotNull { it.posterCacheTarget(landscapeMode, alwaysBackdropWithLogo) }
    }
    ImageCachePrefetchEffect(CacheType.Posters, targets)
}
