package iad1tya.echo.music.ui.screens.search

// CHATGPT_SEARCH_MIX_IMPORT_REPAIR
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.echo.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.echo.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import com.echo.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import com.echo.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import com.echo.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.echo.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import com.echo.innertube.models.AlbumItem
import com.echo.innertube.models.ArtistItem
import com.echo.innertube.models.EpisodeItem
import com.echo.innertube.models.PlaylistItem
import com.echo.innertube.models.PodcastItem
import com.echo.innertube.models.SongItem
import com.echo.innertube.models.WatchEndpoint
import com.echo.innertube.models.YTItem
import iad1tya.echo.music.LocalDatabase
import iad1tya.echo.music.LocalPlayerAwareWindowInsets
import iad1tya.echo.music.LocalPlayerConnection
import iad1tya.echo.music.R
import iad1tya.echo.music.constants.AppBarHeight
import iad1tya.echo.music.constants.SearchFilterHeight
import iad1tya.echo.music.db.entities.FormatEntity
import iad1tya.echo.music.extensions.togglePlayPause
import iad1tya.echo.music.models.toMediaMetadata
import iad1tya.echo.music.playback.queues.SaavnQueue
import iad1tya.echo.music.playback.queues.YouTubeQueue
import iad1tya.echo.music.ui.component.ChipsRow
import iad1tya.echo.music.ui.component.EmptyPlaceholder
import iad1tya.echo.music.ui.component.LocalMenuState
import iad1tya.echo.music.ui.component.NavigationTitle
import iad1tya.echo.music.ui.component.YouTubeListItem
import iad1tya.echo.music.ui.component.shimmer.ListItemPlaceHolder
import iad1tya.echo.music.ui.component.shimmer.ShimmerHost
import iad1tya.echo.music.ui.menu.SaavnSongMenu
import iad1tya.echo.music.ui.menu.YouTubeAlbumMenu
import iad1tya.echo.music.ui.menu.YouTubeArtistMenu
import iad1tya.echo.music.ui.menu.YouTubePlaylistMenu
import iad1tya.echo.music.ui.menu.YouTubeSongMenu
import iad1tya.echo.music.utils.SaavnAudioResolver
import iad1tya.echo.music.viewmodels.OnlineSearchViewModel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnlineSearchResult(
    navController: NavController,
    viewModel: OnlineSearchViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val searchFilter by viewModel.filter.collectAsState()
    val saavnResults by viewModel.saavnResults.collectAsState()
    val searchSummary = viewModel.summaryPage
    val itemsPage by remember(searchFilter) {
        derivedStateOf {
            searchFilter?.value?.let {
                viewModel.viewStateMap[it]
            }
        }
    }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo.any { it.key == "loading" }
        }.collect { shouldLoadMore ->
            if (!shouldLoadMore) return@collect
            viewModel.loadMore()
        }
    }

    LaunchedEffect(searchSummary, viewModel.autoplay) {
        if (viewModel.autoplay && searchSummary != null) {
            val item = searchSummary!!.summaries.flatMap { it.items }
                .firstOrNull { it is SongItem }
                ?: searchSummary!!.summaries.flatMap { it.items }.firstOrNull()

            if (item is SongItem && item.id != mediaMetadata?.id) {
                playerConnection.playQueue(
                    YouTubeQueue(
                        WatchEndpoint(videoId = item.id),
                        item.toMediaMetadata(),
                    )
                )
                viewModel.autoplay = false
            }
        }
    }

    val ytItemContent: @Composable LazyItemScope.(YTItem) -> Unit = { item: YTItem ->
        val longClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menuState.show {
                when (item) {
                    is SongItem -> YouTubeSongMenu(song = item, navController = navController, onDismiss = menuState::dismiss)
                    is AlbumItem -> YouTubeAlbumMenu(albumItem = item, navController = navController, onDismiss = menuState::dismiss)
                    is ArtistItem -> YouTubeArtistMenu(artist = item, onDismiss = menuState::dismiss)
                    is PlaylistItem -> YouTubePlaylistMenu(playlist = item, coroutineScope = coroutineScope, onDismiss = menuState::dismiss)
                    is EpisodeItem -> YouTubeSongMenu(song = item.asSongItem(), navController = navController, onDismiss = menuState::dismiss)
                    is PodcastItem -> YouTubePlaylistMenu(playlist = item.asPlaylistItem(), coroutineScope = coroutineScope, onDismiss = menuState::dismiss)
                }
            }
        }

        YouTubeListItem(
            item = item,
            isActive = when (item) {
                is SongItem -> mediaMetadata?.id == item.id
                is AlbumItem -> mediaMetadata?.album?.id == item.id
                else -> false
            },
            isPlaying = isPlaying,
            trailingContent = {
                when (item) {
                    is SongItem -> YouTubeSearchTrailing(songId = item.id, onMenuClick = longClick)
                    else -> {
                        IconButton(onClick = longClick) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = null,
                            )
                        }
                    }
                }
            },
            modifier = Modifier
                .combinedClickable(
                    onClick = {
                        when (item) {
                            is SongItem -> {
                                if (item.id == mediaMetadata?.id) {
                                    playerConnection.player.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        YouTubeQueue(
                                            WatchEndpoint(videoId = item.id),
                                            item.toMediaMetadata(),
                                        )
                                    )
                                }
                            }

                            is AlbumItem -> navController.navigate("album/${item.id}")
                            is ArtistItem -> navController.navigate("artist/${item.id}")
                            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                            is EpisodeItem -> playerConnection.playQueue(
                                YouTubeQueue(
                                    WatchEndpoint(videoId = item.id),
                                    item.asSongItem().toMediaMetadata(),
                                )
                            )
                            is PodcastItem -> navController.navigate("podcast/${item.id}")
                        }
                    },
                    onLongClick = longClick,
                )
                .animateItem(),
        )
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = LocalPlayerAwareWindowInsets.current
            .add(WindowInsets(top = SearchFilterHeight))
            .add(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
            .asPaddingValues(),
    ) {
        if ((searchFilter == null || searchFilter == FILTER_SONG) && saavnResults.isNotEmpty()) {
            item(key = "saavn_header") {
                NavigationTitle("JioSaavn")
            }
            items(
                items = saavnResults,
                key = { "saavn_${it.sourceSongId}" },
            ) { saavnSong ->
                val saavnLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuState.show {
                        SaavnSongMenu(
                            song = saavnSong,
                            onDismiss = menuState::dismiss,
                        )
                    }
                }
                SaavnSearchRow(
                    song = saavnSong,
                    isActive = mediaMetadata?.id == "saavn:${saavnSong.sourceSongId}",
                    onClick = {
                        playerConnection.playQueue(SaavnQueue(saavnSong))
                    },
                    onLongClick = saavnLongClick,
                    onMenuClick = saavnLongClick,
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (searchFilter == null) {
            searchSummary?.summaries?.forEach { summary ->
                item {
                    NavigationTitle(summary.title)
                }

                items(
                    items = summary.items,
                    key = { "${summary.title}/${it.id}/${summary.items.indexOf(it)}" },
                    itemContent = ytItemContent,
                )
            }

            if (searchSummary?.summaries?.isEmpty() == true) {
                item {
                    EmptyPlaceholder(
                        icon = R.drawable.search,
                        text = stringResource(R.string.no_results_found),
                    )
                }
            }
        } else {
            if (searchFilter == FILTER_SONG && itemsPage?.items?.isNotEmpty() == true) {
                item(key = "youtube_music_header") {
                    NavigationTitle("YouTube Music")
                }
            }
            items(
                items = itemsPage?.items.orEmpty().distinctBy { it.id },
                key = { "filtered_${it.id}" },
                itemContent = ytItemContent,
            )

            if (itemsPage?.continuation != null) {
                item(key = "loading") {
                    ShimmerHost {
                        repeat(3) {
                            ListItemPlaceHolder()
                        }
                    }
                }
            }

            if (itemsPage?.items?.isEmpty() == true) {
                item {
                    EmptyPlaceholder(
                        icon = R.drawable.search,
                        text = stringResource(R.string.no_results_found),
                    )
                }
            }
        }

        if (searchFilter == null && searchSummary == null || searchFilter != null && itemsPage == null) {
            item {
                ShimmerHost {
                    repeat(8) {
                        ListItemPlaceHolder()
                    }
                }
            }
        }
    }

    ChipsRow(
        chips = listOf(
            null to stringResource(R.string.filter_all),
            FILTER_SONG to stringResource(R.string.filter_songs),
            FILTER_VIDEO to stringResource(R.string.filter_videos),
            FILTER_ALBUM to stringResource(R.string.filter_albums),
            FILTER_ARTIST to stringResource(R.string.filter_artists),
            FILTER_COMMUNITY_PLAYLIST to stringResource(R.string.filter_community_playlists),
            FILTER_FEATURED_PLAYLIST to stringResource(R.string.filter_featured_playlists),
        ),
        currentValue = searchFilter,
        onValueUpdate = {
            if (viewModel.filter.value != it) {
                viewModel.filter.value = it
            }
            coroutineScope.launch {
                lazyListState.animateScrollToItem(0)
            }
        },
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(
                WindowInsets.systemBars
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                    .add(WindowInsets(top = AppBarHeight)),
            )
            .fillMaxWidth(),
    )
}

@Composable
private fun YouTubeSearchTrailing(
    songId: String,
    onMenuClick: () -> Unit,
) {
    val database = LocalDatabase.current
    val format by produceState<FormatEntity?>(initialValue = null, songId) {
        value = database.format(songId).firstOrNull()
    }

    val bitrateLabel = when {
        format?.playbackUrl?.startsWith("saavn://") == true || (format?.itag ?: 0) < 0 -> "Adaptive"
        (format?.bitrate ?: 0) > 0 -> "${(format?.bitrate ?: 0) / 1000} kbps"
        else -> "Adaptive"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SearchMetaPill(text = "YT Music", accent = true)
        Spacer(Modifier.width(4.dp))
        SearchMetaPill(text = bitrateLabel)
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onMenuClick) {
            Icon(
                painter = painterResource(R.drawable.more_vert),
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun SearchMetaPill(
    text: String,
    accent: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun SaavnSearchRow(
    song: SaavnAudioResolver.SaavnSearchResult,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            )
        },
        supportingContent = {
            val artistText = song.artists.joinToString(", ").ifBlank { "Unknown artist" }
            val details = listOfNotNull(
                artistText,
                song.duration?.takeIf { it > 0 }?.let { seconds ->
                    val min = seconds / 60
                    val sec = seconds % 60
                    "%d:%02d".format(min, sec)
                },
            ).joinToString(" • ")
            Text(
                text = details,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isActive) MaterialTheme.colorScheme.primary else Color(0xFF242424)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "S",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchMetaPill(text = "Saavn", accent = true)
                Spacer(Modifier.width(4.dp))
                SearchMetaPill(text = "320 kbps")
                Spacer(Modifier.width(8.dp))
                if (onMenuClick != null) {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                        )
                    }
                } else {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = null,
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    )
}
