package iad1tya.echo.music.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

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

    data class ImportProgress(
        val playlistName: String,
        val totalTracks: Int,
        val processedTracks: Int,
        val foundSongIds: List<String>,
        val failedTracks: List<String>,
    )

    /** Backward-compatible entry point for public playlist URLs. */
    suspend fun getPlaylistSongs(url: String): Pair<String, List<Pair<String, String>>> =
        getPlaylistSongs(context = null, url = url)

    /**
     * Extracts song titles and artists from a Spotify playlist URL.
     * If the user is logged in, it uses Spotify Web API OAuth and paginates every 100-track page.
     * Otherwise, it falls back to anonymous public-token/embed/HTML methods.
     */
    suspend fun getPlaylistSongs(context: Context?, url: String): Pair<String, List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        val playlistId = extractPlaylistId(url)
        if (playlistId == null) {
            Log.e(TAG, "Could not extract playlist ID from URL: $url")
            return@withContext "Spotify Import" to emptyList()
        }
        fetchPlaylistTracks(context, playlistId)
    }

    suspend fun getUserPlaylists(context: Context): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val token = SpotifyAuthStore.getValidAccessToken(context) ?: return@withContext emptyList()
        val playlists = mutableListOf<SpotifyPlaylist>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/playlists?limit=50&offset=0"
        var guard = 0

        while (!nextUrl.isNullOrBlank() && guard++ < 400) {
            val json = fetchSpotifyApiPage(nextUrl, token) ?: break
            json.getAsJsonArray("items")?.forEach { element ->
                val item = element.asJsonObject
                val id = item.optString("id") ?: return@forEach
                val name = item.optString("name") ?: "Spotify Playlist"
                val owner = item.getAsJsonObject("owner")?.optString("display_name") ?: "Spotify"
                val total = item.getAsJsonObject("tracks")?.optInt("total", 0) ?: 0
                val image = item.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.optString("url")
                playlists.add(SpotifyPlaylist(id, name, owner, total, image))
            }
            nextUrl = json.optString("next")
        }

        playlists.distinctBy { it.id }
    }

    suspend fun fetchLikedSongs(context: Context): Pair<String, List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        val token = SpotifyAuthStore.getValidAccessToken(context) ?: return@withContext "Spotify Liked Songs" to emptyList()
        val songs = mutableListOf<Pair<String, String>>()
        var nextUrl: String? =
            "https://api.spotify.com/v1/me/tracks?limit=50&offset=0&fields=items(track(name,artists(name),id)),next,total"
        var guard = 0

        while (!nextUrl.isNullOrBlank() && guard++ < 400) {
            val json = fetchSpotifyApiPage(nextUrl, token) ?: break
            json.getAsJsonArray("items")?.forEach { element ->
                parseTrackPair(element.asJsonObject.getAsJsonObject("track"))?.let { songs.add(it) }
            }
            nextUrl = json.optString("next")
        }

        "Spotify Liked Songs" to songs.distinct()
    }

    suspend fun fetchPlaylistTracks(context: Context?, playlistId: String): Pair<String, List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        var playlistName = "Spotify Import"

        // Strategy 1: real OAuth token from user login.
        val userToken = context?.let { SpotifyAuthStore.getValidAccessToken(it) }
        if (!userToken.isNullOrBlank()) {
            try {
                val result = fetchTracksViaApi(playlistId, userToken)
                if (result.second.isNotEmpty()) return@withContext result
            } catch (e: Exception) {
                Log.w(TAG, "OAuth Spotify API method failed, trying public fallbacks", e)
            }
        }

        // Strategy 2: anonymous web-player token for public playlists.
        val anonymousToken = getSpotifyAccessToken()
        if (!anonymousToken.isNullOrBlank()) {
            try {
                val result = fetchTracksViaApi(playlistId, anonymousToken)
                if (result.first.isNotBlank()) playlistName = result.first
                if (result.second.isNotEmpty()) {
                    Log.i(TAG, "Anonymous API method fetched ${result.second.size} songs")
                    return@withContext playlistName to result.second
                }
            } catch (e: Exception) {
                Log.w(TAG, "Anonymous API method failed, falling back to embed", e)
            }
        }

        // Strategy 3: embed page parsing. This may still be capped by Spotify, but it is a fallback only.
        val embedSongs = mutableListOf<Pair<String, String>>()
        try {
            val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
            val doc = Jsoup.connect(embedUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()

            val nextDataScript = doc.select("script#__NEXT_DATA__").first()
            if (nextDataScript != null) {
                val json = nextDataScript.html()
                val jsonObject = gson.fromJson(json, JsonObject::class.java)
                val entity = jsonObject.getAsJsonObject("props")
                    ?.getAsJsonObject("pageProps")
                    ?.getAsJsonObject("state")
                    ?.getAsJsonObject("data")
                    ?.getAsJsonObject("entity")

                if (entity != null) {
                    playlistName = entity.optString("name")
                        ?: entity.optString("title")
                        ?: playlistName

                    entity.getAsJsonArray("trackList")?.forEach { element ->
                        val trackObj = element.asJsonObject
                        val title = trackObj.optString("title") ?: return@forEach
                        val subtitle = trackObj.optString("subtitle") ?: ""
                        if (title.isNotBlank()) embedSongs.add(title to subtitle)
                    }
                }
            }
            if (embedSongs.isNotEmpty()) {
                Log.i(TAG, "Embed method fetched ${embedSongs.size} songs")
                return@withContext playlistName to embedSongs.distinct()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Embed method failed, falling back to HTML", e)
        }

        // Strategy 4: HTML scraping fallback.
        val htmlSongs = mutableListOf<Pair<String, String>>()
        try {
            val doc = Jsoup.connect("https://open.spotify.com/playlist/$playlistId")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()

            doc.selectFirst("meta[property=og:title]")?.let { playlistName = it.attr("content") }
            val trackElements = doc.select("meta[name=music:song]")
            for (el in trackElements) {
                val trackUrl = el.attr("content")
                val trackDoc = try {
                    Jsoup.connect(trackUrl).userAgent("Mozilla/5.0").get()
                } catch (_: Exception) {
                    null
                }
                val trackTitle = trackDoc?.selectFirst("meta[property=og:title]")?.attr("content")
                val trackArtist = trackDoc?.selectFirst("meta[property=og:description]")?.attr("content")
                if (!trackTitle.isNullOrBlank()) htmlSongs.add(trackTitle to (trackArtist ?: ""))
            }
            Log.i(TAG, "HTML method fetched ${htmlSongs.size} songs")
        } catch (e: Exception) {
            Log.e(TAG, "All Spotify playlist fetch methods failed", e)
        }

        playlistName to htmlSongs.distinct()
    }

    /** Search YouTube Music for a song by title and artist, returning the best match video ID. */
    suspend fun searchYouTubeForSong(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = "$title $artist"
            val result = com.echo.innertube.YouTube.search(query, com.echo.innertube.YouTube.SearchFilter.FILTER_SONG)
            val items = result.getOrNull()?.items ?: return@withContext null
            val songItem = items.filterIsInstance<com.echo.innertube.models.SongItem>().firstOrNull()
            songItem?.id
        } catch (e: Exception) {
            Log.e(TAG, "YouTube search failed for '$title - $artist': ${e.message}")
            null
        }
    }

    fun extractPlaylistId(url: String): String? {
        val patterns = listOf(
            Regex("playlist/([a-zA-Z0-9]+)"),
            Regex("playlist%2F([a-zA-Z0-9]+)"),
            Regex("spotify:playlist:([a-zA-Z0-9]+)"),
        )
        for (pattern in patterns) {
            pattern.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun getSpotifyAccessToken(): String? {
        return try {
            val request = Request.Builder()
                .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                val json = gson.fromJson(body, JsonObject::class.java)
                json.optString("accessToken")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Spotify anonymous access token: ${e.message}")
            null
        }
    }

    private fun fetchTracksViaApi(playlistId: String, accessToken: String): Pair<String, List<Pair<String, String>>> {
        val songs = mutableListOf<Pair<String, String>>()
        var playlistName = "Spotify Import"

        try {
            val nameRequest = Request.Builder()
                .url("https://api.spotify.com/v1/playlists/$playlistId?fields=name")
                .header("Authorization", "Bearer $accessToken")
                .build()
            client.newCall(nameRequest).execute().use { response ->
                val nameBody = response.body?.string()
                if (response.isSuccessful && nameBody != null) {
                    playlistName = gson.fromJson(nameBody, JsonObject::class.java).optString("name") ?: playlistName
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch Spotify playlist name", e)
        }

        var nextUrl: String? =
            "https://api.spotify.com/v1/playlists/$playlistId/tracks?offset=0&limit=100&fields=items(track(name,artists(name),id)),total,next"
        var guard = 0

        while (!nextUrl.isNullOrBlank() && guard++ < 400) {
            val json = fetchSpotifyApiPage(nextUrl, accessToken) ?: break
            json.getAsJsonArray("items")?.forEach { item ->
                parseTrackPair(item.asJsonObject.getAsJsonObject("track"))?.let { songs.add(it) }
            }
            nextUrl = json.optString("next")
        }

        return playlistName to songs.distinct()
    }

    private fun parseTrackPair(track: JsonObject?): Pair<String, String>? {
        if (track == null || track.isJsonNull) return null
        val name = track.optString("name") ?: return null
        val artists = track.getAsJsonArray("artists")
            ?.mapNotNull { it.asJsonObject.optString("name") }
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
            ?: ""
        return if (name.isBlank()) null else name to artists
    }

    private fun fetchSpotifyApiPage(url: String, accessToken: String): JsonObject? {
        var attempts = 0
        while (attempts < 4) {
            attempts++
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()

            val response = client.newCall(request).execute()
            try {
                if (response.code == 429) {
                    val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull() ?: 1L
                    Log.w(TAG, "Spotify rate-limited page fetch, retrying in ${retryAfterSeconds}s (attempt $attempts)")
                    TimeUnit.SECONDS.sleep(retryAfterSeconds.coerceAtLeast(1L))
                    continue
                }

                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    Log.w(TAG, "Spotify API page fetch failed: HTTP ${response.code} ${body.take(160)}")
                    return null
                }

                val body = response.body?.string() ?: return null
                return gson.fromJson(body, JsonObject::class.java)
            } finally {
                response.close()
            }
        }
        return null
    }

    private fun JsonObject.optString(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.optInt(key: String, default: Int): Int =
        get(key)?.takeUnless { it.isJsonNull }?.asInt ?: default
}
