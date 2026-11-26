package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.EpisodeOptionsOverlayStyle
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.SourceChipItem

/*
 * Narrow views of [PlayerUiState] for the big player composables.
 *
 * PlayerUiState has ~180 fields and changes many times a second during startup, buffering
 * and seeking. A composable that takes the whole state is re-run on every one of those
 * changes, even when none of the fields it draws moved. Each slice holds only what one
 * composable reads, and is equal whenever those fields are, so Compose skips the composable
 * otherwise. The field names match PlayerUiState so the composables' bodies read the same.
 *
 * When a composable starts reading a new field, add it to its slice and mapper here.
 */

@Immutable
internal data class PlayerControlsUiState(
    val title: String,
    val contentName: String?,
    val releaseYear: String?,
    val currentSeason: Int?,
    val currentEpisode: Int?,
    val currentEpisodeTitle: String?,
    val isPlaying: Boolean,
    val showMoreDialog: Boolean,
    val internalPlayerEngine: InternalPlayerEngine,
    val chapters: List<PlayerChapter>,
    val showChapterTitle: Boolean,
    val audioTracks: List<TrackInfo>,
    val subtitleTracks: List<TrackInfo>,
    val addonSubtitles: List<Subtitle>,
    val discTitles: List<DiscTitle>,
    val postPlayMode: PostPlayMode?,
    val nextEpisode: NextEpisodeInfo?,
    val activeSkipInterval: SkipInterval?,
    val pendingPreviewSeekPosition: Long?,
    val playbackIssueReportsEnabled: Boolean,
    val playbackIssueReportStatus: PlaybackIssueReportStatus,
    val playbackIssueReportId: String?
)

internal fun PlayerUiState.toControlsUiState() = PlayerControlsUiState(
    title = title,
    contentName = contentName,
    releaseYear = releaseYear,
    currentSeason = currentSeason,
    currentEpisode = currentEpisode,
    currentEpisodeTitle = currentEpisodeTitle,
    isPlaying = isPlaying,
    showMoreDialog = showMoreDialog,
    internalPlayerEngine = internalPlayerEngine,
    chapters = chapters,
    showChapterTitle = showChapterTitle,
    audioTracks = audioTracks,
    subtitleTracks = subtitleTracks,
    addonSubtitles = addonSubtitles,
    discTitles = discTitles,
    postPlayMode = postPlayMode,
    nextEpisode = nextEpisode,
    activeSkipInterval = activeSkipInterval,
    pendingPreviewSeekPosition = pendingPreviewSeekPosition,
    playbackIssueReportsEnabled = playbackIssueReportsEnabled,
    playbackIssueReportStatus = playbackIssueReportStatus,
    playbackIssueReportId = playbackIssueReportId
)

@Immutable
internal data class EpisodesPanelUiState(
    val currentSeason: Int?,
    val currentEpisode: Int?,
    val isLoadingEpisodes: Boolean,
    val episodesError: String?,
    val episodes: List<Video>,
    val episodesAvailableSeasons: List<Int>,
    val episodesSelectedSeason: Int?,
    val episodeWatchProgressMap: Map<Pair<Int, Int>, WatchProgress>,
    val watchedEpisodeKeys: Set<Pair<Int, Int>>,
    val blurUnwatchedEpisodes: Boolean,
    val episodeOptionsOverlayStyle: EpisodeOptionsOverlayStyle,
    val showEpisodeStreams: Boolean,
    val isLoadingEpisodeStreams: Boolean,
    val episodeStreamsError: String?,
    val episodeFilteredStreams: List<Stream>,
    val episodeAvailableAddons: List<String>,
    val episodeSelectedAddonFilter: String?,
    val episodeSourceChips: List<SourceChipItem>,
    val episodeStreamsForVideoId: String?,
    val episodeStreamsSeason: Int?,
    val episodeStreamsEpisode: Int?,
    val episodeStreamsTitle: String?,
    val showAddonLogo: Boolean,
    val showFileSizeBadges: Boolean,
    val streamBadgePlacement: StreamBadgePlacement
)

internal fun PlayerUiState.toEpisodesPanelUiState() = EpisodesPanelUiState(
    currentSeason = currentSeason,
    currentEpisode = currentEpisode,
    isLoadingEpisodes = isLoadingEpisodes,
    episodesError = episodesError,
    episodes = episodes,
    episodesAvailableSeasons = episodesAvailableSeasons,
    episodesSelectedSeason = episodesSelectedSeason,
    episodeWatchProgressMap = episodeWatchProgressMap,
    watchedEpisodeKeys = watchedEpisodeKeys,
    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
    episodeOptionsOverlayStyle = episodeOptionsOverlayStyle,
    showEpisodeStreams = showEpisodeStreams,
    isLoadingEpisodeStreams = isLoadingEpisodeStreams,
    episodeStreamsError = episodeStreamsError,
    episodeFilteredStreams = episodeFilteredStreams,
    episodeAvailableAddons = episodeAvailableAddons,
    episodeSelectedAddonFilter = episodeSelectedAddonFilter,
    episodeSourceChips = episodeSourceChips,
    episodeStreamsForVideoId = episodeStreamsForVideoId,
    episodeStreamsSeason = episodeStreamsSeason,
    episodeStreamsEpisode = episodeStreamsEpisode,
    episodeStreamsTitle = episodeStreamsTitle,
    showAddonLogo = showAddonLogo,
    showFileSizeBadges = showFileSizeBadges,
    streamBadgePlacement = streamBadgePlacement
)

@Immutable
internal data class SourcesPanelUiState(
    val title: String,
    val currentSeason: Int?,
    val currentEpisode: Int?,
    val currentEpisodeTitle: String?,
    val currentStreamName: String?,
    val currentStreamUrl: String?,
    val currentStreamInfoHash: String?,
    val currentStreamFileIdx: Int?,
    val currentStreamAddonName: String?,
    val currentStreamDescription: String?,
    val isLoadingSourceStreams: Boolean,
    val sourceStreamsError: String?,
    val sourceFilteredStreams: List<Stream>,
    val sourceAvailableAddons: List<String>,
    val sourceSelectedAddonFilter: String?,
    val sourceChips: List<SourceChipItem>,
    val showAddonLogo: Boolean,
    val showFileSizeBadges: Boolean,
    val streamBadgePlacement: StreamBadgePlacement
)

internal fun PlayerUiState.toSourcesPanelUiState() = SourcesPanelUiState(
    title = title,
    currentSeason = currentSeason,
    currentEpisode = currentEpisode,
    currentEpisodeTitle = currentEpisodeTitle,
    currentStreamName = currentStreamName,
    currentStreamUrl = currentStreamUrl,
    currentStreamInfoHash = currentStreamInfoHash,
    currentStreamFileIdx = currentStreamFileIdx,
    currentStreamAddonName = currentStreamAddonName,
    currentStreamDescription = currentStreamDescription,
    isLoadingSourceStreams = isLoadingSourceStreams,
    sourceStreamsError = sourceStreamsError,
    sourceFilteredStreams = sourceFilteredStreams,
    sourceAvailableAddons = sourceAvailableAddons,
    sourceSelectedAddonFilter = sourceSelectedAddonFilter,
    sourceChips = sourceChips,
    showAddonLogo = showAddonLogo,
    showFileSizeBadges = showFileSizeBadges,
    streamBadgePlacement = streamBadgePlacement
)
