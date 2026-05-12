package iad1tya.echo.music.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import iad1tya.echo.music.models.MediaMetadata
import iad1tya.echo.music.models.toMediaMetadata
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object SpotifyImportHelper {
    private const val TAG = "SpotifyImportHelper"
    private val gson = Gson()
    private val client = OkHttpClient()

    data class SpotifyPlaylist(
        val id: String,
        val name: String,
        val owner: String,
        val totalTracks: Int,
        val imageUrl: String?,
    )

    data class SpotifyTrack(
        val id: String,
        val uri: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long,
        val imageUrl: String?,
    ) {
        val artistText: String get() = artists.joinToString(", ").ifBlank { "Spotify" }
    }

    suspend fun getUserPlaylists(context: Context): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val token = SpotifyAuthStore.getValidAccessToken(context) ?: return@withContext emptyList()
        val playlists = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        val limit = 50
        var total = Int.MAX_VALUE
        var guard = 0

        while (offset < total && guard++ < 1000) {
            val url = "https://api.spotify.com/v1/me/playlists?limit=$limit&offset=$offset"
            val json = fetchSpotifyApiPage(url, token) ?: break
            total = json.optInt("total", total)
            val items = json.getAsJsonArray("items") ?: break
            if (items.size() == 0) break

            items.forEach { element ->
                val item = element.asJsonObject
                val id = item.optString("id") ?: return@forEach
                val name = item.optString("name") ?: "Spotify Playlist"
                val owner = item.getAsJsonObject("owner")?.optString("display_name")
                    ?: item.getAsJsonObject("owner")?.optString("id")
                    ?: "Spotify"
                val tracks = item.getAsJsonObject("tracks")
                val totalTracks = tracks?.optInt("total", 0) ?: 0
                val image = item.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.optString("url")
                playlists.add(SpotifyPlaylist(id, name, owner, totalTracks, image))
            }
            offset += items.size()
        }

        playlists.distinctBy { it.id }
    }

    suspend fun fetchLikedSongsDetailed(context: Context): Pair<String, List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        val token = SpotifyAuthStore.getValidAccessToken(context) ?: return@withContext "Spotify Liked Songs" to emptyList()
        val songs = mutableListOf<SpotifyTrack>()
        var offset = 0
        val limit = 50
        var total = Int.MAX_VALUE
        var guard = 0

        while (offset < total && guard++ < 2000) {
            val url = "https://api.spotify.com/v1/me/tracks?limit=$limit&offset=$offset"
            val json = fetchSpotifyApiPage(url, token) ?: break
            total = json.optInt("total", total)
            val items = json.getAsJsonArray("items") ?: break
            if (items.size() == 0) break
            items.forEach { element ->
                parseTrack(element.asJsonObject.getAsJsonObject("track"))?.let { songs.add(it) }
            }
            offset += items.size()
        }

        "Spotify Liked Songs" to songs
    }

    suspend fun fetchPlaylistTracksDetailed(context: Context, playlistId: String): Pair<String, List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        val token = SpotifyAuthStore.getValidAccessToken(context) ?: return@withContext "Spotify Playlist" to emptyList()
        fetchTracksViaApi(playlistId, token)
    }

    suspend fun searchSpotify(context: Context, query: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val token = SpotifyAuthStore.getValidAccessToken(context) ?: return@withContext emptyList()
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val url = "https://api.spotify.com/v1/search?type=track&limit=30&q=${URLEncoder.encode(q, "UTF-8")}" 
        val json = fetchSpotifyApiPage(url, token) ?: return@withContext emptyList()
        val tracks = json.getAsJsonObject("tracks")?.getAsJsonArray("items") ?: return@withContext emptyList()
        tracks.mapNotNull { parseTrack(it.asJsonObject) }
    }

    suspend fun resolveToEchoMediaMetadata(track: SpotifyTrack): MediaMetadata? =
        searchYouTubeForSong(track.title, track.artistText)

    suspend fun searchYouTubeForSong(title: String, artist: String): MediaMetadata? = withContext(Dispatchers.IO) {
        try {
            val query = "$title $artist"
            val result = com.echo.innertube.YouTube.search(query, com.echo.innertube.YouTube.SearchFilter.FILTER_SONG)
            val items = result.getOrNull()?.items ?: return@withContext null
            val songItem = items.filterIsInstance<com.echo.innertube.models.SongItem>().firstOrNull()
            songItem?.toMediaMetadata()
        } catch (e: Exception) {
            Log.e(TAG, "Echo match failed for '$title - $artist': ${e.message}")
            null
        }
    }

    suspend fun getPlaylistSongs(url: String): Pair<String, List<Pair<String, String>>> =
        getPlaylistSongs(context = null, url = url)

    suspend fun getPlaylistSongs(context: Context?, url: String): Pair<String, List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        val playlistId = extractPlaylistId(url)
        if (playlistId == null) return@withContext "Spotify Import" to emptyList()
        if (context == null) return@withContext "Spotify Import" to emptyList()
        val (name, tracks) = fetchPlaylistTracksDetailed(context, playlistId)
        name to tracks.map { it.title to it.artistText }
    }

    suspend fun fetchLikedSongs(context: Context): Pair<String, List<Pair<String, String>>> {
        val (name, tracks) = fetchLikedSongsDetailed(context)
        return name to tracks.map { it.title to it.artistText }
    }

    suspend fun fetchPlaylistTracks(context: Context?, playlistId: String): Pair<String, List<Pair<String, String>>> {
        if (context == null) return "Spotify Import" to emptyList()
        val (name, tracks) = fetchPlaylistTracksDetailed(context, playlistId)
        return name to tracks.map { it.title to it.artistText }
    }

    fun extractPlaylistId(url: String): String? {
        val patterns = listOf(
            Regex("playlist/([a-zA-Z0-9]+)"),
            Regex("playlist%2F([a-zA-Z0-9]+)"),
            Regex("spotify:playlist:([a-zA-Z0-9]+)"),
            Regex("[?&]list=([a-zA-Z0-9]+)"),
        )
        for (pattern in patterns) pattern.find(url)?.let { return it.groupValues[1] }
        return null
    }

    private fun fetchTracksViaApi(playlistId: String, accessToken: String): Pair<String, List<SpotifyTrack>> {
        var playlistName = "Spotify Playlist"
        var expectedTotal = Int.MAX_VALUE

        val metaRequest = Request.Builder()
            .url("https://api.spotify.com/v1/playlists/$playlistId?fields=name,tracks(total)")
            .header("Authorization", "Bearer $accessToken")
            .build()
        client.newCall(metaRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful && body.isNotBlank()) {
                val json = gson.fromJson(body, JsonObject::class.java)
                playlistName = json.optString("name") ?: playlistName
                expectedTotal = json.getAsJsonObject("tracks")?.optInt("total", Int.MAX_VALUE) ?: Int.MAX_VALUE
            }
        }

        val songs = mutableListOf<SpotifyTrack>()
        var offset = 0
        val limit = 100
        var guard = 0

        while (offset < expectedTotal && guard++ < 3000) {
            val url = "https://api.spotify.com/v1/playlists/$playlistId/tracks" +
                "?offset=$offset&limit=$limit&fields=items(track(id,uri,name,artists(id,name),album(id,name,images(url)),duration_ms,explicit,type,is_local)),total,limit,offset,next"
            val json = fetchSpotifyApiPage(url, accessToken) ?: break
            val pageTotal = json.optInt("total", expectedTotal)
            if (pageTotal > 0 && pageTotal < expectedTotal) expectedTotal = pageTotal
            val items = json.getAsJsonArray("items") ?: break
            if (items.size() == 0) break

            items.forEach { item ->
                parseTrack(item.asJsonObject.getAsJsonObject("track"))?.let { songs.add(it) }
            }
            offset += items.size()
        }
        return playlistName to songs
    }

    private fun fetchSpotifyApiPage(url: String, accessToken: String): JsonObject? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "Spotify API failed: HTTP ${response.code} ${body.take(180)}")
                if (response.code == 401 || response.code == 403) return null
                throw IOException("Spotify API HTTP ${response.code}")
            }
            return gson.fromJson(body, JsonObject::class.java)
        }
    }

    private fun parseTrack(track: JsonObject?): SpotifyTrack? {
        if (track == null || track.isJsonNull) return null
        if (track.optString("type") != null && track.optString("type") != "track") return null
        if (track.get("is_local")?.takeUnless { it.isJsonNull }?.asBoolean == true) return null
        val title = track.optString("name")?.takeIf { it.isNotBlank() } ?: return null
        val id = track.optString("id").orEmpty()
        val uri = track.optString("uri") ?: if (id.isNotBlank()) "spotify:track:$id" else ""
        val artists = track.getAsJsonArray("artists")
            ?.mapNotNull { it.asJsonObject.optString("name")?.takeIf { name -> name.isNotBlank() } }
            ?: emptyList()
        val album = track.getAsJsonObject("album")
        val images = album?.getAsJsonArray("images")
        val imageUrl = images?.firstOrNull()?.asJsonObject?.optString("url")
        return SpotifyTrack(
            id = id,
            uri = uri,
            title = title,
            artists = artists,
            album = album?.optString("name"),
            durationMs = track.optLong("duration_ms", 0L),
            imageUrl = imageUrl,
        )
    }

    private fun JsonObject.optString(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.optInt(key: String, default: Int): Int = get(key)?.takeUnless { it.isJsonNull }?.asInt ?: default

    private fun JsonObject.optLong(key: String, default: Long): Long = get(key)?.takeUnless { it.isJsonNull }?.asLong ?: default
}
