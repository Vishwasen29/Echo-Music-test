package iad1tya.echo.music.ui.menu

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import iad1tya.echo.music.LocalDatabase
import iad1tya.echo.music.LocalPlayerConnection
import iad1tya.echo.music.R
import iad1tya.echo.music.extensions.metadata
import iad1tya.echo.music.extensions.toMediaItem
import iad1tya.echo.music.models.MediaMetadata
import iad1tya.echo.music.models.toMediaMetadata
import iad1tya.echo.music.ui.component.NewAction
import iad1tya.echo.music.ui.component.NewActionGrid
import iad1tya.echo.music.utils.SaavnAudioResolver
import com.echo.innertube.YouTube
import com.echo.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SaavnSongMenu(
    song: SaavnAudioResolver.SaavnSearchResult,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()

    var showChoosePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var isResolvingPlaylistMatch by rememberSaveable { mutableStateOf(false) }
    var matchedPlaylistSongId by rememberSaveable { mutableStateOf<String?>(null) }
    var matchedPlaylistMetadata by remember { mutableStateOf<MediaMetadata?>(null) }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog && matchedPlaylistSongId != null,
        onGetSong = { _ ->
            val songId = matchedPlaylistSongId ?: return@AddToPlaylistDialog emptyList()
            matchedPlaylistMetadata?.let { metadata ->
                database.transaction { insert(metadata) }
            }
            listOf(songId)
        },
        onDismiss = { showChoosePlaylistDialog = false },
    )

    ListItem(
        headlineContent = {
            Text(
                text = song.title,
                modifier = Modifier.basicMarquee(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = listOfNotNull(
                    song.artists.joinToString(", ").ifBlank { "Unknown artist" },
                    "JioSaavn",
                    song.duration?.takeIf { it > 0 }?.let { seconds ->
                        val min = seconds / 60
                        val sec = seconds % 60
                        "%d:%02d".format(min, sec)
                    }
                ).joinToString(" • ")
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFF242424), MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "S",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
    )

    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))

    LazyColumn(
        contentPadding = PaddingValues(
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item {
            NewActionGrid(
                actions = listOf(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.playlist_play),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = stringResource(R.string.play_next),
                        onClick = {
                            playerConnection.playNext(saavnSearchResultToMetadata(song).toMediaItem())
                            onDismiss()
                        },
                    ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.playlist_add),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = stringResource(R.string.add_to_playlist),
                        onClick = {
                            if (isResolvingPlaylistMatch) return@NewAction
                            isResolvingPlaylistMatch = true
                            coroutineScope.launch {
                                val match = withContext(Dispatchers.IO) {
                                    matchYoutubeSongForSaavn(song)
                                }
                                isResolvingPlaylistMatch = false
                                if (match == null) {
                                    Toast.makeText(
                                        context,
                                        "Couldn't map this JioSaavn result to YouTube Music for playlist add",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    matchedPlaylistSongId = match.id
                                    matchedPlaylistMetadata = match.toMediaMetadata()
                                    showChoosePlaylistDialog = true
                                }
                            }
                        },
                    ),
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.queue_music),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = stringResource(R.string.add_to_queue),
                        onClick = {
                            playerConnection.addToQueue(saavnSearchResultToMetadata(song).toMediaItem())
                            onDismiss()
                        },
                    ),
                ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
            )
        }

        item {
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.share)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.share),
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    if (isResolvingPlaylistMatch) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(text = "Matching")
                        }
                    }
                },
                modifier = Modifier.clickable {
                    val shareText = buildString {
                        append(song.title)
                        if (song.artists.isNotEmpty()) {
                            append(" — ")
                            append(song.artists.joinToString(", "))
                        }
                        append(" • JioSaavn")
                    }
                    val intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                    onDismiss()
                },
            )
        }
    }
}

private fun saavnSearchResultToMetadata(song: SaavnAudioResolver.SaavnSearchResult): MediaMetadata {
    return MediaMetadata(
        id = "saavn:${song.sourceSongId}",
        title = song.title,
        artists = song.artists.map { MediaMetadata.Artist(id = null, name = it) },
        duration = song.duration ?: -1,
        thumbnailUrl = null,
        album = song.albumName?.let { title -> MediaMetadata.Album(id = "saavn:${song.sourceSongId}", title = title) },
        setVideoId = null,
        explicit = false,
        isVideoSong = false,
        liked = false,
        likedDate = null,
        inLibrary = null,
        libraryAddToken = null,
        libraryRemoveToken = null,
    )
}

private suspend fun matchYoutubeSongForSaavn(
    source: SaavnAudioResolver.SaavnSearchResult,
): SongItem? {
    val query = listOf(source.title, source.artists.firstOrNull().orEmpty())
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .trim()
    if (query.isBlank()) return null

    return YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
        .getOrNull()
        ?.items
        ?.filterIsInstance<SongItem>()
        ?.map { candidate -> candidate to youtubeSongMatchScore(source, candidate) }
        ?.filter { (_, score) -> score >= 85 }
        ?.maxByOrNull { it.second }
        ?.first
}

private fun youtubeSongMatchScore(
    source: SaavnAudioResolver.SaavnSearchResult,
    candidate: SongItem,
): Int {
    fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("""[^a-z0-9\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    val sourceTitle = normalize(source.title)
    val candidateTitle = normalize(candidate.title)
    val sourceArtists = source.artists.map(::normalize).filter { it.isNotBlank() }
    val candidateArtists = candidate.artists.map { normalize(it.name) }.filter { it.isNotBlank() }

    val variantTerms = listOf(
        "remix", "mix", "version", "karaoke", "instrumental", "acoustic",
        "lofi", "slowed", "reverb", "live", "cover"
    )
    val sourceWantsVariant = variantTerms.any { it in sourceTitle }

    var score = 0
    if (!sourceWantsVariant && variantTerms.any { it in candidateTitle }) {
        score -= 120
    }

    when {
        sourceTitle == candidateTitle -> score += 140
        sourceTitle.isNotBlank() && (candidateTitle.contains(sourceTitle) || sourceTitle.contains(candidateTitle)) -> score += 85
        else -> {
            val sourceTokens = sourceTitle.split(" ").filter { it.isNotBlank() }.toSet()
            val candidateTokens = candidateTitle.split(" ").filter { it.isNotBlank() }.toSet()
            score += sourceTokens.intersect(candidateTokens).size * 14
        }
    }

    if (sourceArtists.isNotEmpty()) {
        score += when {
            candidateArtists.any { it in sourceArtists } -> 100
            candidateArtists.any { cand -> sourceArtists.any { src -> cand.contains(src) || src.contains(cand) } } -> 70
            else -> -35
        }
    }

    val sourceDuration = source.duration ?: 0
    val candidateDuration = candidate.duration ?: 0
    if (sourceDuration > 0 && candidateDuration > 0) {
        val diff = kotlin.math.abs(sourceDuration - candidateDuration)
        score += when {
            diff <= 2 -> 30
            diff <= 5 -> 20
            diff <= 10 -> 10
            diff <= 20 -> 0
            else -> -25
        }
    }

    return score
}
