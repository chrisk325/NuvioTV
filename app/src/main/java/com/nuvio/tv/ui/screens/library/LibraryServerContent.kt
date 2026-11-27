package com.nuvio.tv.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LocalLandscapePosterMode
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle

internal fun LazyGridScope.libraryServerContent(
    shelves: List<LibraryServerShelf>?,
    asGrid: Boolean,
    posterCardStyle: PosterCardStyle,
    rowInset: Dp,
    focusTarget: Pair<String, Int>?,
    focusedIndexes: Map<String, Int>,
    onItemFocused: (String, Int) -> Unit,
    isWatched: (MetaPreview) -> Boolean,
    onItemClick: (String, String, String) -> Unit,
    onSeeAll: (CatalogRow) -> Unit,
    onItemLongPress: (MetaPreview, String) -> Unit
) {
    when {
        shelves == null -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(modifier = Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }

        shelves.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyScreenState(
                title = stringResource(R.string.library_servers_empty_title),
                subtitle = stringResource(R.string.library_servers_empty_message),
                icon = Icons.Default.Dns,
                height = 260.dp
            )
        }

        shelves.none { it.failure != null || it.row?.items?.isNotEmpty() == true } -> item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyScreenState(
                title = stringResource(R.string.library_servers_no_favorites_title),
                subtitle = stringResource(R.string.library_servers_no_favorites_message),
                icon = Icons.Default.Dns,
                height = 260.dp
            )
        }

        else -> shelves.forEach { shelf ->
            val row = shelf.row
            val failure = shelf.failure
            if (row != null && row.items.isNotEmpty() && asGrid) {
                serverShelfGrid(
                    shelfKey = shelf.key,
                    row = row,
                    posterCardStyle = posterCardStyle,
                    focusTarget = focusTarget,
                    onItemFocused = onItemFocused,
                    isWatched = isWatched,
                    onItemClick = onItemClick,
                    onSeeAll = onSeeAll,
                    onItemLongPress = onItemLongPress
                )
            } else if (row != null && row.items.isNotEmpty()) {
                item(key = "server_shelf_${shelf.key}", span = { GridItemSpan(maxLineSpan) }) {
                    CatalogRowSection(
                        catalogRow = row,
                        onItemClick = onItemClick,
                        onSeeAll = { onSeeAll(row) },
                        showSeeAll = row.hasMore,
                        posterCardStyle = posterCardStyle,
                        showAddonName = false,
                        showCatalogTypeSuffix = false,
                        isItemWatched = isWatched,
                        onItemLongPress = onItemLongPress,
                        modifier = Modifier.bleed(rowInset),
                        focusedItemIndex = focusTarget?.takeIf { it.first == shelf.key }?.second ?: -1,
                        restorerFocusedIndex = focusedIndexes[shelf.key] ?: -1,
                        onItemFocused = { index -> onItemFocused(shelf.key, index) }
                    )
                }
            } else if (failure != null) {
                item(key = "server_shelf_error_${shelf.key}", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyScreenState(
                        title = stringResource(R.string.library_server_load_failed, shelf.title),
                        subtitle = stringResource(failure.messageRes()),
                        icon = Icons.Default.Dns,
                        height = 180.dp
                    )
                }
            }
        }
    }
}

/**
 * One shelf as a grid: its title across the page, then its posters filling the grid's columns, wrapping onto as many
 * lines as they need, and a See all card at the end when the library holds more.
 */
private fun LazyGridScope.serverShelfGrid(
    shelfKey: String,
    row: CatalogRow,
    posterCardStyle: PosterCardStyle,
    focusTarget: Pair<String, Int>?,
    onItemFocused: (String, Int) -> Unit,
    isWatched: (MetaPreview) -> Boolean,
    onItemClick: (String, String, String) -> Unit,
    onSeeAll: (CatalogRow) -> Unit,
    onItemLongPress: (MetaPreview, String) -> Unit
) {
    item(key = "server_grid_title_$shelfKey", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text = row.catalogName,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = NuvioTheme.spacing.md)
        )
    }
    itemsIndexed(row.items, key = { index, item -> "server_grid_${shelfKey}_${index}_${item.id}" }) { index, item ->
        val focusRequester = remember { FocusRequester() }
        val restoreFocus = focusTarget?.first == shelfKey && focusTarget.second == index
        LaunchedEffect(restoreFocus) {
            if (restoreFocus) runCatching { focusRequester.requestFocus() }
        }
        GridContentCard(
            item = item,
            posterCardStyle = posterCardStyle,
            isWatched = isWatched(item),
            focusRequester = focusRequester,
            showLabel = true,
            onFocused = { onItemFocused(shelfKey, index) },
            onClick = { onItemClick(item.id, item.apiType, row.addonBaseUrl) },
            onLongPress = { onItemLongPress(item, row.addonBaseUrl) }
        )
    }
    if (row.hasMore) {
        item(key = "server_grid_see_all_$shelfKey") {
            ServerGridSeeAllCard(posterCardStyle = posterCardStyle, onClick = { onSeeAll(row) })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerGridSeeAllCard(posterCardStyle: PosterCardStyle, onClick: () -> Unit) {
    val landscape = LocalLandscapePosterMode.current
    val width = if (landscape) posterCardStyle.height else posterCardStyle.width
    val height = if (landscape) posterCardStyle.height / PosterShape.LANDSCAPE.aspectRatio() else posterCardStyle.height
    val shape = RoundedCornerShape(posterCardStyle.cornerRadius)
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .height(height),
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(border = NuvioTheme.focusRing.border(posterCardStyle.focusedBorderWidth), shape = shape)
        ),
        scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(NuvioTheme.spacing.xxl),
                tint = NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Text(
                text = stringResource(R.string.action_see_all),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

private fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}
