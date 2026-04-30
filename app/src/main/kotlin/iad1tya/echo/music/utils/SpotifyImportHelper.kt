package iad1tya.echo.music.utils

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

object SpotifyImportHelper {
    private const val TAG = "SpotifyImportHelper"
    private const val SPOTIFY_PAGE_LIMIT = 100
    private const val MAX_TRACK_PAGES = 500
    private const val PATCH_MARKER = "SPOTIFY_FULL_PLAYLIST_PAGINATION_PATCH_V2"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class ImportProgress(
        val playlistName: String,
        val totalTracks: Int,
        val processedTracks: Int,
        val foundSongIds: List<String>,
        val failedTracks: List<String>,
    )

    /**
     * Extracts song titles and artists from a Spotify playlist URL.
     *
     * Spotify playlist tracks are returned in 100-track pages. The old importer
     * could stop after the first page when it used Spotify embed/HTML data.
     * This version prefers the Spotify Web API and keeps requesting offset
     * pages until the playlist total is reached.
     */
    suspend fun getPlaylistSongs(url: String): Pair<String, List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Pair<String, String>>()
        var playlistName = "Spotify Import"

        try {
            val playlistId = extractPlaylistId(url)
            if (playlistId == null) {
                Log.e(TAG, "Could not extract playlist ID from URL: $url")
                return@withContext playlistName to emptyList()
            }

            // Strategy 1: Spotify Web API with anonymous web-player token.
            // This is the only path here that can reliably fetch more than the
            // 100 tracks exposed by Spotify's embed fallback.
            val accessToken = getSpotifyAccessToken()
            if (accessToken != null) {
                try {
                    val (name, apiSongs) = fetchTracksViaApi(playlistId, accessToken)
                    if (name.isNotBlank()) playlistName = name
                    if (apiSongs.isNotEmpty()) {
                        Log.i(TAG, "API method fetched ${apiSongs.size} songs from playlist '$playlistName'")
                        return@withContext playlistName to apiSongs
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "API method failed, falling back to embed", e)
                }
            } else {
                Log.w(TAG, "Could not get Spotify access token; falling back to embed/HTML. Large playlists may be incomplete.")
            }

            // Strategy 2: Embed page parsing fallback. This is often capped.
            try {
                val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
                val doc = Jsoup.connect(embedUrl)
                    .userAgent(desktopUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(20_000)
                    .get()

                val nextDataScript = doc.select("script#__NEXT_DATA__").first()
                if (nextDataScript != null) {
                    val json = nextDataScript.html()
                    val jsonObject = gson.fromJson(json, JsonObject::class.java)
                    val entity = jsonObject.obj("props")
                        ?.obj("pageProps")
                        ?.obj("state")
                        ?.obj("data")
                        ?.obj("entity")

                    if (entity != null) {
                        playlistName = entity.string("name")
                            ?: entity.string("title")
                            ?: playlistName

                        val trackList = entity.array("trackList")
                        if (trackList != null) {
                            for (element in trackList) {
                                val trackObj = element.asObjectOrNull() ?: continue
                                val title = trackObj.string("title") ?: continue
                                val subtitle = trackObj.string("subtitle") ?: ""
                                if (title.isNotBlank()) songs.add(title to subtitle)
                            }
                        }
                    }
                }
                if (songs.isNotEmpty()) {
                    Log.i(TAG, "Embed method fetched ${songs.size} songs. This may be a capped fallback result.")
                    return@withContext playlistName to songs.toList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Embed method failed, falling back to HTML", e)
            }

            // Strategy 3: HTML scraping fallback. Also often capped.
            try {
                val doc = Jsoup.connect("https://open.spotify.com/playlist/$playlistId")
                    .userAgent(desktopUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(20_000)
                    .get()

                val titleEl = doc.selectFirst("meta[property=og:title]")
                if (titleEl != null) {
                    playlistName = titleEl.attr("content")
                }

                val trackElements = doc.select("meta[name=music:song]")
                for (el in trackElements) {
                    val trackUrl = el.attr("content")
                    val trackDoc = try {
                        Jsoup.connect(trackUrl)
                            .userAgent(desktopUserAgent())
                            .timeout(15_000)
                            .get()
                    } catch (_: Exception) {
                        null
                    }
                    val trackTitle = trackDoc?.selectFirst("meta[property=og:title]")?.attr("content")
                    val trackArtist = trackDoc?.selectFirst("meta[property=og:description]")?.attr("content")
                    if (!trackTitle.isNullOrBlank()) {
                        songs.add(trackTitle to (trackArtist ?: ""))
                    }
                }
                Log.i(TAG, "HTML method fetched ${songs.size} songs. This may be a capped fallback result.")
            } catch (e: Exception) {
                Log.e(TAG, "All methods failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting playlist: ${e.message}", e)
        }

        playlistName to songs.toList()
    }

    /**
     * Search YouTube for a song by title and artist, returning the best match video ID.
     */
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

    private fun extractPlaylistId(url: String): String? {
        val decoded = try {
            URLDecoder.decode(url.trim(), StandardCharsets.UTF_8.toString())
        } catch (_: Exception) {
            url.trim()
        }

        val patterns = listOf(
            Regex("spotify:playlist:([a-zA-Z0-9]+)"),
            Regex("playlist/([a-zA-Z0-9]+)"),
            Regex("playlist%2F([a-zA-Z0-9]+)"),
        )
        for (pattern in patterns) {
            val match = pattern.find(decoded)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun getSpotifyAccessToken(): String? {
        val request = Request.Builder()
            .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
            .header("User-Agent", desktopUserAgent())
            .header("Accept", "application/json")
            .header("App-Platform", "WebPlayer")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Spotify access token: ${e.message}")
            return null
        }

        response.use {
            if (!it.isSuccessful) {
                Log.w(TAG, "Failed to get Spotify access token: HTTP ${it.code}")
                return null
            }
            val body = it.body?.string() ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)
            return json.string("accessToken") ?: json.string("access_token")
        }
    }

    private fun fetchTracksViaApi(
        playlistId: String,
        accessToken: String,
    ): Pair<String, List<Pair<String, String>>> {
        val songs = mutableListOf<Pair<String, String>>()
        val playlistName = fetchPlaylistNameViaApi(playlistId, accessToken)

        var offset = 0
        var pageIndex = 0
        var totalFromApi: Int? = null
        val seenOffsets = mutableSetOf<Int>()

        while (pageIndex < MAX_TRACK_PAGES) {
            if (!seenOffsets.add(offset)) {
                Log.w(TAG, "Stopping Spotify pagination because offset $offset repeated")
                break
            }

            val pageUrl = buildTracksPageUrl(playlistId, offset)
            val json = fetchSpotifyApiPage(pageUrl, accessToken) ?: break
            val pageTotal = json.int("total")
            if (pageTotal != null) totalFromApi = pageTotal

            val items = json.array("items") ?: break
            if (items.size() == 0) {
                Log.i(TAG, "Spotify page ${pageIndex + 1} was empty at offset $offset")
                break
            }

            var addedOnThisPage = 0
            for (itemElement in items) {
                val item = itemElement.asObjectOrNull() ?: continue
                val trackElement = item.get("track") ?: continue
                if (trackElement.isJsonNull) continue

                val track = trackElement.asObjectOrNull() ?: continue
                val trackType = track.string("type")
                if (trackType != null && trackType != "track") continue

                val name = track.string("name") ?: continue
                if (name.isBlank()) continue

                val artists = track.array("artists")
                    ?.mapNotNull { artistElement ->
                        artistElement.asObjectOrNull()?.string("name")?.takeIf(String::isNotBlank)
                    }
                    ?.joinToString(", ")
                    ?: ""

                songs.add(name to artists)
                addedOnThisPage++
            }

            Log.i(
                TAG,
                "Fetched Spotify page ${pageIndex + 1}: offset=$offset, items=${items.size()}, added=$addedOnThisPage, collected=${songs.size}, total=${totalFromApi ?: -1}"
            )

            pageIndex++
            val nextOffset = json.string("next")?.let(::extractOffsetFromUrl)
            val nextOffsetToUse = nextOffset ?: (offset + SPOTIFY_PAGE_LIMIT)
            val total = totalFromApi

            if (total != null && nextOffsetToUse >= total) break
            if (items.size() < SPOTIFY_PAGE_LIMIT && nextOffset == null) break

            offset = nextOffsetToUse
        }

        val total = totalFromApi
        if (total != null && songs.size < total) {
            Log.w(TAG, "Spotify API pagination returned ${songs.size}/$total tracks. Playlist may be private, unavailable, or rate-limited.")
        }

        return playlistName to songs
    }

    private fun buildTracksPageUrl(playlistId: String, offset: Int): String {
        val fields = "items(track(name,type,artists(name))),total,next"
        val encodedFields = URLEncoder.encode(fields, StandardCharsets.UTF_8.toString())
        return "https://api.spotify.com/v1/playlists/$playlistId/tracks" +
            "?offset=$offset&limit=$SPOTIFY_PAGE_LIMIT&market=from_token&fields=$encodedFields"
    }

    private fun fetchPlaylistNameViaApi(
        playlistId: String,
        accessToken: String,
    ): String {
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/playlists/$playlistId?fields=name")
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", desktopUserAgent())
            .header("Accept", "application/json")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (_: Exception) {
            return ""
        }

        response.use {
            if (!it.isSuccessful) return ""
            val body = it.body?.string() ?: return ""
            return gson.fromJson(body, JsonObject::class.java).string("name") ?: ""
        }
    }

    private fun fetchSpotifyApiPage(
        url: String,
        accessToken: String,
    ): JsonObject? {
        var attempts = 0
        while (attempts < 4) {
            attempts++
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", desktopUserAgent())
                .header("Accept", "application/json")
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                Log.w(TAG, "Spotify API page fetch failed before response: ${e.message}")
                return null
            }

            try {
                if (response.code == 429) {
                    val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull() ?: 1L
                    Log.w(TAG, "Spotify rate-limited page fetch, retrying in ${retryAfterSeconds}s (attempt $attempts)")
                    TimeUnit.SECONDS.sleep(retryAfterSeconds.coerceAtLeast(1L).coerceAtMost(10L))
                    continue
                }

                if (!response.isSuccessful) {
                    Log.w(TAG, "Spotify API page fetch failed: HTTP ${response.code}")
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

    private fun extractOffsetFromUrl(url: String): Int? {
        val match = Regex("[?&]offset=([0-9]+)").find(url)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun desktopUserAgent(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeIf { !it.isJsonNull }?.asInt

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(name: String): JsonArray? =
        get(name)?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject
}
