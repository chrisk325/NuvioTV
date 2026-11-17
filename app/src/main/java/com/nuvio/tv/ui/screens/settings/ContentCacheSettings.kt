package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.cache.CacheSettingsRepository
import com.nuvio.tv.core.cache.CacheType
import com.nuvio.tv.core.cache.ImageCache
import com.nuvio.tv.core.cache.MetadataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CacheSizeRefreshMillis = 3_000L

/** The storage limit is applied once the user stops changing it, because applying it reopens the image caches. */
private const val MaxStorageApplyDelayMillis = 800L

private suspend fun contentCacheSizeBytes(type: CacheType): Long =
    when (type) {
        CacheType.Metadata -> MetadataCache.sizeBytes()
        else -> withContext(Dispatchers.IO) { ImageCache.sizeBytes(type) }
    }

private suspend fun clearContentCache(type: CacheType, context: Context) {
    when (type) {
        CacheType.Metadata -> MetadataCache.clear()
        else -> ImageCache.clear(type, context)
    }
}

/** Formats a storage size given in MB as e.g. "512 MB", "1.25 GB" or "10 GB". */
private fun formatStorageMb(mb: Int): String {
    if (mb < 1024) return "$mb MB"
    val hundredths = mb * 100 / 1024
    val whole = hundredths / 100
    val fraction = hundredths % 100
    val number = when {
        fraction == 0 -> "$whole"
        fraction % 10 == 0 -> "$whole.${fraction / 10}"
        else -> "$whole.${fraction.toString().padStart(2, '0')}"
    }
    return "$number GB"
}

private val maxStorageValues: List<Int> = (
    CacheSettingsRepository.MIN_MAX_STORAGE_MB..CacheSettingsRepository.MAX_MAX_STORAGE_MB
    step CacheSettingsRepository.MAX_STORAGE_STEP_MB
).toList()

/**
 * Settings for the content caches (posters, background artwork and metadata): turn each one on,
 * see and clear its size, pick the refresh interval and the maximum storage (up to 10 GB).
 */
@Composable
internal fun ContentCacheSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val refreshIntervalHours by remember {
        CacheSettingsRepository.ensureLoaded()
        CacheSettingsRepository.refreshIntervalHours
    }.collectAsState()
    val postersEnabled by remember { CacheSettingsRepository.enabled(CacheType.Posters) }.collectAsState()
    val backgroundsEnabled by remember { CacheSettingsRepository.enabled(CacheType.Backgrounds) }.collectAsState()
    val metadataEnabled by remember { CacheSettingsRepository.enabled(CacheType.Metadata) }.collectAsState()
    var sizeRefreshKey by remember { mutableIntStateOf(0) }
    var clearingAll by remember { mutableStateOf(false) }
    var showRefreshDialog by remember { mutableStateOf(false) }
    val clearedAllMessage = stringResource(R.string.settings_cache_cleared_all)

    SettingsGroupCard(
        modifier = modifier.fillMaxWidth(),
        title = stringResource(R.string.settings_content_cache_title),
        subtitle = stringResource(R.string.settings_content_cache_description),
    ) {
        ContentCacheTypeRows(
            type = CacheType.Posters,
            title = stringResource(R.string.settings_poster_cache_enabled),
            description = stringResource(R.string.settings_poster_cache_description),
            clearTitle = stringResource(R.string.settings_poster_cache_clear),
            enabled = postersEnabled,
            sizeRefreshKey = sizeRefreshKey,
            onCleared = { sizeRefreshKey += 1 },
        )
        ContentCacheTypeRows(
            type = CacheType.Backgrounds,
            title = stringResource(R.string.settings_background_cache_enabled),
            description = stringResource(R.string.settings_background_cache_description),
            clearTitle = stringResource(R.string.settings_background_cache_clear),
            enabled = backgroundsEnabled,
            sizeRefreshKey = sizeRefreshKey,
            onCleared = { sizeRefreshKey += 1 },
        )
        ContentCacheTypeRows(
            type = CacheType.Metadata,
            title = stringResource(R.string.settings_metadata_cache_enabled),
            description = stringResource(R.string.settings_metadata_cache_description),
            clearTitle = stringResource(R.string.settings_metadata_cache_clear),
            enabled = metadataEnabled,
            sizeRefreshKey = sizeRefreshKey,
            onCleared = { sizeRefreshKey += 1 },
        )
        MaxCacheStorageRow()
        if (postersEnabled || backgroundsEnabled || metadataEnabled) {
            SettingsActionRow(
                title = stringResource(R.string.settings_cache_refresh),
                subtitle = stringResource(R.string.settings_cache_refresh_hint),
                value = stringResource(R.string.settings_cache_refresh_hours, refreshIntervalHours),
                onClick = { showRefreshDialog = true },
            )
        }
        SettingsActionRow(
            title = stringResource(R.string.settings_cache_clear_all),
            subtitle = stringResource(R.string.settings_cache_clear_all_subtitle),
            // Stays focusable while clearing: disabling a focused row would drop D-pad focus.
            onClick = {
                if (clearingAll) return@SettingsActionRow
                clearingAll = true
                scope.launch {
                    try {
                        CacheType.entries.forEach { type -> clearContentCache(type, context) }
                        Toast.makeText(context, clearedAllMessage, Toast.LENGTH_SHORT).show()
                    } finally {
                        clearingAll = false
                        sizeRefreshKey += 1
                    }
                }
            },
        )
    }

    if (showRefreshDialog) {
        val options = CacheSettingsRepository.refreshIntervalOptions.map { hours ->
            SettingsPickerOption(
                value = hours,
                title = stringResource(R.string.settings_cache_refresh_hours, hours),
            )
        }
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.settings_cache_refresh),
            subtitle = stringResource(R.string.settings_cache_refresh_hint),
            options = options,
            selectedValue = refreshIntervalHours,
            onOptionSelected = { hours ->
                CacheSettingsRepository.setRefreshIntervalHours(hours)
                showRefreshDialog = false
            },
            onDismiss = { showRefreshDialog = false },
        )
    }
}

@Composable
private fun MaxCacheStorageRow() {
    val maxStorageMb by remember {
        CacheSettingsRepository.ensureLoaded()
        CacheSettingsRepository.maxStorageMb
    }.collectAsState()
    // Follows the D-pad while the value is changed; the limit is only applied once the user
    // stops changing it, because applying it reopens the image caches.
    var draftMb by remember(maxStorageMb) { mutableIntStateOf(maxStorageMb) }

    LaunchedEffect(draftMb) {
        if (draftMb == maxStorageMb) return@LaunchedEffect
        delay(MaxStorageApplyDelayMillis)
        CacheSettingsRepository.setMaxStorageMb(draftMb)
    }

    SliderSettingsItem(
        title = stringResource(R.string.settings_cache_max_storage),
        subtitle = stringResource(R.string.settings_cache_max_storage_hint),
        values = maxStorageValues,
        selected = CacheSettingsRepository.normalizeMaxStorageMb(draftMb),
        valueText = formatStorageMb(CacheSettingsRepository.normalizeMaxStorageMb(draftMb)),
        onValueChange = { draftMb = it },
    )
}

@Composable
private fun ContentCacheTypeRows(
    type: CacheType,
    title: String,
    description: String,
    clearTitle: String,
    enabled: Boolean,
    sizeRefreshKey: Int,
    onCleared: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheSizeBytes by remember(type) { mutableStateOf<Long?>(null) }
    var isClearing by remember(type) { mutableStateOf(false) }
    val clearedMessage = stringResource(R.string.settings_cache_cleared)
    val maxStorageMb by CacheSettingsRepository.maxStorageMb.collectAsState()
    val limitBytes = remember(type, maxStorageMb) { CacheSettingsRepository.maxSizeBytes(type, maxStorageMb) }

    LaunchedEffect(type, sizeRefreshKey, enabled) {
        cacheSizeBytes = null
        // Keep the size live while the page is open: prefetches keep filling the cache.
        while (true) {
            cacheSizeBytes = contentCacheSizeBytes(type)
            delay(CacheSizeRefreshMillis)
        }
    }

    SettingsToggleRow(
        title = title,
        subtitle = description,
        checked = enabled,
        onToggle = { CacheSettingsRepository.setEnabled(type, !enabled) },
        expandSubtitleOnFocus = true,
    )
    SettingsActionRow(
        title = clearTitle,
        subtitle = cacheSizeBytes?.let { bytes ->
            stringResource(
                R.string.settings_cache_size,
                Formatter.formatShortFileSize(context, bytes),
                Formatter.formatShortFileSize(context, limitBytes),
            )
        } ?: stringResource(R.string.settings_cache_size_calculating),
        // Stays focusable while clearing: disabling a focused row would drop D-pad focus.
        onClick = {
            if (isClearing) return@SettingsActionRow
            isClearing = true
            scope.launch {
                try {
                    clearContentCache(type, context)
                    Toast.makeText(context, clearedMessage, Toast.LENGTH_SHORT).show()
                } finally {
                    isClearing = false
                    onCleared()
                }
            }
        },
    )
}
