package iad1tya.echo.music.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import iad1tya.echo.music.LocalDatabase
import iad1tya.echo.music.LocalPlayerConnection
import iad1tya.echo.music.R
import iad1tya.echo.music.db.entities.PlaylistEntity
import iad1tya.echo.music.db.entities.PlaylistSongMap
import iad1tya.echo.music.extensions.toMediaItem
import iad1tya.echo.music.models.MediaMetadata
import iad1tya.echo.music.playback.queues.ListQueue
import iad1tya.echo.music.ui.component.IconButton
import iad1tya.echo.music.ui.utils.backToMain
import iad1tya.echo.music.utils.SpotifyAuthStore
import iad1tya.echo.music.utils.SpotifyImportHelper
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SpotifySection { Playlists, Liked, Search }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyImportScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var clientId by rememberSaveable { mutableStateOf("") }
    var profile by remember { mutableStateOf<SpotifyAuthStore.SpotifyProfile?>(null) }
    var playlists by remember { mutableStateOf<List<SpotifyImportHelper.SpotifyPlaylist>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<SpotifyImportHelper.SpotifyTrack>>(emptyList()) }
    var loadedTitle by remember { mutableStateOf("Spotify") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf(SpotifySection.Playlists) }
    var statusText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableIntStateOf(0) }
    var totalTracks by remember { mutableIntStateOf(0) }

    suspend fun refreshSpotifyState(loadPlaylists: Boolean = false) {
        profile = SpotifyAuthStore.fetchCurrentUser(context)
        if (loadPlaylists && profile != null) {
            playlists = SpotifyImportHelper.getUserPlaylists(context)
        }
    }

    fun showTracks(name: String, newTracks: List<SpotifyImportHelper.SpotifyTrack>) {
        loadedTitle = name.ifBlank { "Spotify" }
        tracks = newTracks
        totalTracks = newTracks.size
        statusText = if (newTracks.isEmpty()) {
            "No Spotify tracks loaded. Check login scopes, playlist ownership/collaboration, or try reconnecting."
        } else {
            "Loaded ${newTracks.size} tracks from $loadedTitle. Tap a song to play it through Echo matching."
        }
    }

    fun openSpotifyUri(uri: String) {
        if (uri.isBlank()) return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Spotify app/browser could not be opened", Toast.LENGTH_SHORT).show()
        }
    }

    fun playEchoMatch(track: SpotifyImportHelper.SpotifyTrack) {
        if (playerConnection == null) {
            Toast.makeText(context, "Player is not connected yet", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            isLoading = true
            statusText = "Matching ${track.title} in Echo..."
            try {
                val match = SpotifyImportHelper.resolveToEchoMediaMetadata(track)
                if (match == null) {
                    statusText = "Could not match ${track.title}. Try Open Spotify or search manually."
                    Toast.makeText(context, "No Echo match found", Toast.LENGTH_SHORT).show()
                } else {
                    playerConnection.playQueue(
                        ListQueue(
                            title = "Spotify Match",
                            items = listOf(match.toMediaItem()),
                        ),
                    )
                    statusText = "Playing ${match.title} via Echo providers. Spotify itself is metadata-only here."
                }
            } catch (e: Exception) {
                statusText = "Playback match failed: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun playLoadedEchoMatches(limit: Int = 50) {
        if (playerConnection == null) {
            Toast.makeText(context, "Player is not connected yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (tracks.isEmpty()) {
            Toast.makeText(context, "No tracks loaded", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            isLoading = true
            importProgress = 0
            totalTracks = tracks.take(limit).size
            statusText = "Matching ${totalTracks} Spotify tracks for Echo playback..."
            val matches = mutableListOf<MediaMetadata>()
            try {
                for ((index, track) in tracks.take(limit).withIndex()) {
                    importProgress = index + 1
                    statusText = "Matching ${track.title} ($importProgress/$totalTracks)"
                    SpotifyImportHelper.resolveToEchoMediaMetadata(track)?.let { matches.add(it) }
                }
                if (matches.isEmpty()) {
                    statusText = "No playable Echo matches found."
                } else {
                    playerConnection.playQueue(
                        ListQueue(
                            title = loadedTitle,
                            items = matches.map { it.toMediaItem() },
                        ),
                    )
                    statusText = "Playing ${matches.size}/${totalTracks} matched tracks via Echo providers."
                }
            } catch (e: Exception) {
                statusText = "Play failed: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun importTracksToEcho(name: String, songs: List<SpotifyImportHelper.SpotifyTrack>) {
        if (songs.isEmpty()) {
            Toast.makeText(context, "No Spotify tracks loaded", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            isImporting = true
            importProgress = 0
            totalTracks = songs.size
            statusText = "Importing to Echo Music..."
            val found = mutableListOf<MediaMetadata>()
            val failed = mutableListOf<String>()

            try {
                for ((index, track) in songs.withIndex()) {
                    importProgress = index + 1
                    statusText = "Matching: ${track.title} ($importProgress/${songs.size})"
                    val match = SpotifyImportHelper.resolveToEchoMediaMetadata(track)
                    if (match != null) found.add(match) else failed.add("${track.title} - ${track.artistText}")
                }

                if (found.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        database.query {
                            val playlist = PlaylistEntity(
                                name = name.ifBlank { "Spotify Import" },
                                browseId = null,
                                bookmarkedAt = LocalDateTime.now(),
                                isEditable = true,
                            )
                            insert(playlist)
                            found.forEachIndexed { idx, metadata ->
                                insert(metadata)
                                insert(
                                    PlaylistSongMap(
                                        songId = metadata.id,
                                        playlistId = playlist.id,
                                        position = idx,
                                    ),
                                )
                            }
                        }
                    }
                }

                statusText = "Done. Imported ${found.size}/${songs.size} tracks" +
                    if (failed.isNotEmpty()) ". ${failed.size} tracks were not matched." else ""
                Toast.makeText(
                    context,
                    "Playlist \"${name.ifBlank { "Spotify Import" }}\" created with ${found.size} songs",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: Exception) {
                statusText = "Import failed: ${e.message}"
            } finally {
                isImporting = false
            }
        }
    }

    fun loadPlaylists() {
        scope.launch {
            isLoading = true
            statusText = "Loading Spotify library..."
            try {
                refreshSpotifyState(loadPlaylists = true)
                statusText = if (profile == null) {
                    "Not connected. Paste Client ID and tap Login with Spotify."
                } else {
                    "Loaded ${playlists.size} playlists."
                }
            } catch (e: Exception) {
                statusText = "Spotify library failed: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        clientId = SpotifyAuthStore.getClientId(context)
        refreshSpotifyState(loadPlaylists = true)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    refreshSpotifyState(loadPlaylists = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Spotify",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily(Font(R.font.zalando_sans_expanded)),
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SpotifyLoginCard(
                    profile = profile,
                    clientId = clientId,
                    isBusy = isLoading || isImporting,
                    onClientIdChange = { clientId = it.trim() },
                    onSaveClientId = {
                        scope.launch {
                            SpotifyAuthStore.setClientId(context, clientId)
                            statusText = "Spotify Client ID saved."
                        }
                    },
                    onLogin = {
                        scope.launch {
                            try {
                                SpotifyAuthStore.setClientId(context, clientId)
                                context.startActivity(SpotifyAuthStore.createLoginIntent(context, clientId))
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Spotify login failed", Toast.LENGTH_LONG).show()
                                statusText = e.message ?: "Spotify login failed"
                            }
                        }
                    },
                    onRefresh = { loadPlaylists() },
                    onSignOut = {
                        scope.launch {
                            SpotifyAuthStore.clearAuth(context)
                            profile = null
                            playlists = emptyList()
                            tracks = emptyList()
                            statusText = "Signed out of Spotify."
                        }
                    },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionButton("Playlists", section == SpotifySection.Playlists, Modifier.weight(1f)) {
                        section = SpotifySection.Playlists
                        loadPlaylists()
                    }
                    SectionButton("Liked", section == SpotifySection.Liked, Modifier.weight(1f)) {
                        section = SpotifySection.Liked
                        scope.launch {
                            isLoading = true
                            statusText = "Loading Spotify liked songs..."
                            try {
                                val (name, liked) = SpotifyImportHelper.fetchLikedSongsDetailed(context)
                                showTracks(name, liked)
                            } catch (e: Exception) {
                                statusText = "Liked songs failed: ${e.message}"
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                    SectionButton("Search", section == SpotifySection.Search, Modifier.weight(1f)) {
                        section = SpotifySection.Search
                    }
                }
            }

            if (section == SpotifySection.Search) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                label = { Text("Search Spotify") },
                                placeholder = { Text("Song, artist, album") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        isLoading = true
                                        statusText = "Searching Spotify..."
                                        try {
                                            showTracks("Search: $searchQuery", SpotifyImportHelper.searchSpotify(context, searchQuery))
                                        } catch (e: Exception) {
                                            statusText = "Spotify search failed: ${e.message}"
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                },
                                enabled = !isLoading && searchQuery.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.White),
                            ) {
                                Text("Search")
                            }
                        }
                    }
                }
            }

            if (section == SpotifySection.Playlists && playlists.isNotEmpty()) {
                item {
                    Text(
                        text = "Your Spotify playlists (${playlists.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(playlists, key = { it.id }) { playlist ->
                    SpotifyPlaylistRow(
                        playlist = playlist,
                        enabled = !isLoading && !isImporting,
                        onOpen = {
                            scope.launch {
                                isLoading = true
                                statusText = "Loading ${playlist.name}..."
                                try {
                                    val (name, newTracks) = SpotifyImportHelper.fetchPlaylistTracksDetailed(context, playlist.id)
                                    showTracks(name.ifBlank { playlist.name }, newTracks)
                                } catch (e: Exception) {
                                    statusText = "Playlist load failed: ${e.message}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                    )
                }
            }

            if (tracks.isNotEmpty()) {
                item {
                    LoadedTracksCard(
                        title = loadedTitle,
                        count = tracks.size,
                        isBusy = isLoading || isImporting,
                        importProgress = importProgress,
                        totalTracks = totalTracks,
                        showProgress = isLoading || isImporting,
                        onPlay = { playLoadedEchoMatches() },
                        onImport = { importTracksToEcho(loadedTitle, tracks) },
                    )
                }
                itemsIndexed(tracks, key = { index, item -> item.id.ifBlank { "${item.title}-$index" } }) { index, track ->
                    SpotifyTrackRow(
                        index = index,
                        track = track,
                        enabled = !isLoading && !isImporting,
                        onPlay = { playEchoMatch(track) },
                        onOpenSpotify = { openSpotifyUri(track.uri) },
                    )
                }
            }

            if (statusText.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.White),
        ) { Text(text) }
    } else {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(999.dp),
        ) { Text(text) }
    }
}

@Composable
private fun SpotifyLoginCard(
    profile: SpotifyAuthStore.SpotifyProfile?,
    clientId: String,
    isBusy: Boolean,
    onClientIdChange: (String) -> Unit,
    onSaveClientId: () -> Unit,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (profile?.imageUrl != null) {
                    AsyncImage(
                        model = profile.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(22.dp)),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_spotify),
                        contentDescription = null,
                        tint = Color(0xFF1DB954),
                        modifier = Modifier.size(40.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Spotify Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = profile?.let { "Connected as ${it.displayName}" } ?: "Paste Client ID, login, then browse playlists and liked songs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (profile == null) {
                OutlinedTextField(
                    value = clientId,
                    onValueChange = onClientIdChange,
                    label = { Text("Spotify Client ID") },
                    placeholder = { Text("From Spotify Developer Dashboard") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
            }

            Text(
                text = "Echo can show your Spotify library and match tracks to Echo playback. Spotify full audio streams are not exposed by the Web API, so use Open Spotify for native Spotify playback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onLogin,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.White),
                ) {
                    Text(if (profile == null) "Login" else "Reconnect")
                }
                FilledTonalButton(
                    onClick = if (profile == null) onSaveClientId else onRefresh,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (profile == null) "Save ID" else "Refresh")
                }
                if (profile != null) {
                    TextButton(onClick = onSignOut, enabled = !isBusy) { Text("Sign out") }
                }
            }
        }
    }
}

@Composable
private fun SpotifyPlaylistRow(
    playlist: SpotifyImportHelper.SpotifyPlaylist,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onOpen() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SpotifyArtwork(url = playlist.imageUrl, size = 52)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${playlist.totalTracks} tracks • ${playlist.owner}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpen, enabled = enabled) { Text("Open") }
        }
    }
}

@Composable
private fun LoadedTracksCard(
    title: String,
    count: Int,
    isBusy: Boolean,
    importProgress: Int,
    totalTracks: Int,
    showProgress: Boolean,
    onPlay: () -> Unit,
    onImport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("$count Spotify tracks loaded", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = onPlay,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.White),
                ) { Text("Play") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = onImport,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Import") }
            }
            if (showProgress) {
                LinearProgressIndicator(
                    progress = { if (totalTracks > 0) importProgress.toFloat() / totalTracks.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SpotifyTrackRow(
    index: Int,
    track: SpotifyImportHelper.SpotifyTrack,
    enabled: Boolean,
    onPlay: () -> Unit,
    onOpenSpotify: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onPlay() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        ListItem(
            headlineContent = {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    listOfNotNull(track.artistText, track.album).joinToString(" • "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${index + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SpotifyArtwork(url = track.imageUrl, size = 48)
                }
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onOpenSpotify, enabled = enabled) { Text("Spotify") }
                    Button(
                        onClick = onPlay,
                        enabled = enabled,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.White),
                    ) { Text("Play") }
                }
            },
        )
    }
}

@Composable
private fun SpotifyArtwork(url: String?, size: Int) {
    if (url.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1DB954).copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_spotify),
                contentDescription = null,
                tint = Color(0xFF1DB954),
                modifier = Modifier.size((size * 0.52f).dp),
            )
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    }
}
