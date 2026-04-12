package iad1tya.echo.music.utils

import iad1tya.echo.music.constants.AudioQuality
import iad1tya.echo.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object SaavnAudioResolver {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val baseUrls = listOf(
        "https://saavn.sumit.co",
        "https://saavn.dev",
    )

    private val searchPaths = listOf(
        "/api/search/songs",
        "/search/songs",
    )

    private val detailPaths = listOf(
        "/api/songs/%s",
        "/songs/%s",
    )

    data class ResolvedStream(
        val url: String,
        val bitrate: Int?,
        val mimeType: String,
        val sampleRate: Int?,
        val provider: String,
        val songId: String,
        val matchedTitle: String,
        val matchedArtists: List<String>,
        val thumbnailUrl: String?,
        val albumTitle: String?,
        val durationSeconds: Int?,
    )

    private data class DownloadLink(
        val quality: String,
        val url: String,
        val bitrate: Int,
    )

    private data class Candidate(
        val id: String,
        val title: String,
        val artists: List<String>,
        val duration: Int?,
        val downloadLinks: List<DownloadLink>,
        val thumbnailUrl: String?,
        val albumTitle: String?,
    )

    suspend fun resolve(
        mediaMetadata: MediaMetadata,
        audioQuality: AudioQuality,
    ): Result<ResolvedStream?> = withContext(Dispatchers.IO) {
        runCatching {
            val query = buildQuery(mediaMetadata)
            val candidates = search(query)
            if (candidates.isEmpty()) return@runCatching null

            val best = candidates
                .map { candidate -> candidate to score(candidate, mediaMetadata) }
                .sortedByDescending { it.second }
                .firstOrNull()
                ?.takeIf { it.second >= 45 }
                ?.first
                ?: return@runCatching null

            val hydrated = if (best.downloadLinks.isNotEmpty() && best.thumbnailUrl != null) {
                best
            } else {
                fetchSong(best.id) ?: best
            }

            val chosenLink = pickDownloadLink(hydrated.downloadLinks, audioQuality) ?: return@runCatching null
            ResolvedStream(
                url = chosenLink.url,
                bitrate = chosenLink.bitrate.takeIf { it > 0 },
                mimeType = inferMimeType(chosenLink.url),
                sampleRate = 44100,
                provider = "Saavn",
                songId = hydrated.id,
                matchedTitle = hydrated.title,
                matchedArtists = hydrated.artists,
                thumbnailUrl = hydrated.thumbnailUrl,
                albumTitle = hydrated.albumTitle,
                durationSeconds = hydrated.duration,
            )
        }
    }

    private fun buildQuery(mediaMetadata: MediaMetadata): String {
        val artists = mediaMetadata.artists.take(2).joinToString(" ") { it.name }
        return listOf(mediaMetadata.title, artists)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
    }

    private fun search(query: String): List<Candidate> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val paramVariants = listOf(
            "query=$encoded&limit=10",
            "q=$encoded&limit=10",
            "query=$encoded",
            "q=$encoded",
        )
        val all = linkedMapOf<String, Candidate>()
        for (base in baseUrls) {
            for (path in searchPaths) {
                for (params in paramVariants) {
                    val url = base.trimEnd('/') + path + "?" + params
                    val json = fetchJson(url) ?: continue
                    parseCandidates(json).forEach { all.putIfAbsent(it.id, it) }
                    if (all.isNotEmpty()) return all.values.toList()
                }
            }
        }
        return all.values.toList()
    }

    private fun fetchSong(songId: String): Candidate? {
        val encodedId = URLEncoder.encode(songId, Charsets.UTF_8.name())
        for (base in baseUrls) {
            for (template in detailPaths) {
                val url = base.trimEnd('/') + template.format(encodedId)
                val json = fetchJson(url) ?: continue
                parseCandidates(json).firstOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun fetchJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "EchoMusic/1.0 (Android)")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string()?.trim().orEmpty()
                if (body.isBlank()) return null
                when {
                    body.startsWith("{") -> JSONObject(body)
                    body.startsWith("[") -> JSONObject().put("data", JSONArray(body))
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCandidates(root: JSONObject): List<Candidate> {
        val results = mutableListOf<Candidate>()

        fun collectFromArray(array: JSONArray?) {
            if (array == null) return
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parseCandidate(item)?.let(results::add)
            }
        }

        fun collectFromObject(data: JSONObject?) {
            if (data == null) return
            when {
                data.has("results") -> collectFromArray(data.optJSONArray("results"))
                data.has("songs") -> collectFromArray(data.optJSONArray("songs"))
                data.has("id") -> parseCandidate(data)?.let(results::add)
            }
        }

        collectFromObject(root.optJSONObject("data"))
        collectFromArray(root.optJSONArray("data"))
        collectFromArray(root.optJSONArray("songs"))
        collectFromObject(root)

        return results.distinctBy { it.id }
    }

    private fun parseCandidate(json: JSONObject): Candidate? {
        val id = json.optString("id").trim()
        if (id.isBlank()) return null

        val title = json.optString("name")
            .ifBlank { json.optString("song") }
            .ifBlank { json.optString("title") }
            .trim()
        if (title.isBlank()) return null

        val duration = when (val raw = json.opt("duration")) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }

        val artists = parseArtists(json)
        val downloadLinks = parseDownloadLinks(json)

        return Candidate(
            id = id,
            title = title,
            artists = artists,
            duration = duration,
            downloadLinks = downloadLinks,
            thumbnailUrl = parseThumbnail(json),
            albumTitle = parseAlbumTitle(json),
        )
    }


    private fun parseThumbnailUrl(json: JSONObject): String? {
        val direct = listOf(
            json.optString("image"),
            json.optString("imageUrl"),
            json.optString("thumbnail"),
            json.optString("thumbnailUrl"),
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }
        if (!direct.isNullOrBlank()) return direct.replace("150x150", "500x500")

        val imageObj = json.optJSONObject("image")
        if (imageObj != null) {
            val nested = listOf(
                imageObj.optString("url"),
                imageObj.optString("link"),
            ).map { it.trim() }.firstOrNull { it.isNotBlank() }
            if (!nested.isNullOrBlank()) return nested.replace("150x150", "500x500")
        }
        return null
    }

    private fun parseAlbumName(json: JSONObject): String? {
        val direct = listOf(
            json.optString("album"),
            json.optString("albumName"),
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }
        if (!direct.isNullOrBlank()) return direct

        val albumObj = json.optJSONObject("album")
        if (albumObj != null) {
            val nested = listOf(
                albumObj.optString("name"),
                albumObj.optString("title"),
            ).map { it.trim() }.firstOrNull { it.isNotBlank() }
            if (!nested.isNullOrBlank()) return nested
        }
        return null
    }

    private fun parseArtists(json: JSONObject): List<String> {
        val artists = linkedSetOf<String>()

        val structured = json.optJSONObject("artists")
        if (structured != null) {
            listOf("primary", "featured", "all").forEach { key ->
                val array = structured.optJSONArray(key) ?: return@forEach
                for (index in 0 until array.length()) {
                    val artist = array.optJSONObject(index) ?: continue
                    val name = artist.optString("name").trim()
                    if (name.isNotBlank()) artists += decodeMaybe(name)
                }
            }
        }

        listOf(
            json.optString("primaryArtists"),
            json.optString("primary_artists"),
        ).forEach { raw ->
            raw.split(',')
                .map { decodeMaybe(it.trim()) }
                .filter { it.isNotBlank() }
                .forEach { artists += it }
        }

        val artistMap = json.optJSONObject("artistMap")
        if (artistMap != null) {
            val keys = artistMap.keys()
            while (keys.hasNext()) {
                val name = decodeMaybe(keys.next().trim())
                if (name.isNotBlank()) artists += name
            }
        }

        return artists.toList()
    }

    private fun parseThumbnail(json: JSONObject): String? {
        val direct = listOf(
            json.optString("image"),
            json.optString("thumbnail"),
            json.optString("thumbnailUrl"),
        ).firstOrNull { it.isNotBlank() }
        if (!direct.isNullOrBlank()) return upgradeImageUrl(decodeMaybe(direct))

        val imageObject = json.optJSONObject("image")
        if (imageObject != null) {
            listOf(
                imageObject.optString("url"),
                imageObject.optString("link"),
            ).firstOrNull { it.isNotBlank() }?.let { return upgradeImageUrl(decodeMaybe(it)) }
        }

        val imageArray = json.optJSONArray("image")
        if (imageArray != null) {
            for (index in imageArray.length() - 1 downTo 0) {
                val item = imageArray.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if (url.isNotBlank()) return upgradeImageUrl(decodeMaybe(url))
            }
        }

        return null
    }

    private fun parseAlbumTitle(json: JSONObject): String? {
        val direct = listOf(
            json.optString("album"),
            json.optString("album_name"),
            json.optString("albumTitle"),
        ).map(::decodeMaybe).firstOrNull { it.isNotBlank() }
        if (!direct.isNullOrBlank()) return direct

        val albumObject = json.optJSONObject("album")
        if (albumObject != null) {
            listOf(
                albumObject.optString("name"),
                albumObject.optString("title"),
            ).map(::decodeMaybe).firstOrNull { it.isNotBlank() }?.let { return it }
        }

        return null
    }

    private fun parseDownloadLinks(json: JSONObject): List<DownloadLink> {
        val links = mutableListOf<DownloadLink>()
        val rawArray = json.optJSONArray("downloadUrl")
        if (rawArray != null) {
            for (index in 0 until rawArray.length()) {
                val item = rawArray.optJSONObject(index) ?: continue
                val quality = item.optString("quality").trim()
                val url = item.optString("url").trim()
                if (url.isBlank()) continue
                links += DownloadLink(quality = quality, url = decodeMaybe(url), bitrate = parseBitrate(quality))
            }
        }

        val fallbackVlink = decodeMaybe(json.optString("vlink").trim())
        if (links.isEmpty() && fallbackVlink.isNotBlank()) {
            links += DownloadLink(
                quality = "96kbps",
                url = fallbackVlink,
                bitrate = 96_000,
            )
        }

        val preview = decodeMaybe(json.optString("media_preview_url").trim())
        if (links.isEmpty() && preview.isNotBlank()) {
            links += DownloadLink(
                quality = "preview",
                url = preview,
                bitrate = 96_000,
            )
        }

        return links.distinctBy { it.url }
    }

    private fun pickDownloadLink(
        links: List<DownloadLink>,
        audioQuality: AudioQuality,
    ): DownloadLink? {
        if (links.isEmpty()) return null
        val sorted = links.sortedBy { it.bitrate }
        return when (audioQuality) {
            AudioQuality.LOW -> sorted.firstOrNull()
            AudioQuality.AUTO,
            AudioQuality.HIGH,
            -> sorted.lastOrNull()
        }
    }

    private fun score(
        candidate: Candidate,
        requested: MediaMetadata,
    ): Int {
        val requestedTitle = normalizeTitle(requested.title)
        val candidateTitle = normalizeTitle(candidate.title)
        val requestedArtists = requested.artists.map { normalizeTitle(it.name) }.filter { it.isNotBlank() }
        val candidateArtists = candidate.artists.map(::normalizeTitle).filter { it.isNotBlank() }

        var score = 0
        when {
            candidateTitle == requestedTitle -> score += 75
            candidateTitle.contains(requestedTitle) || requestedTitle.contains(candidateTitle) -> score += 55
            else -> score += similarityScore(candidateTitle, requestedTitle)
        }

        val matchingArtists = requestedArtists.count { wanted ->
            candidateArtists.any { found -> found == wanted || found.contains(wanted) || wanted.contains(found) }
        }
        score += matchingArtists * 18

        if (requested.duration > 0 && candidate.duration != null && candidate.duration > 0) {
            val difference = abs(candidate.duration - requested.duration)
            score += when {
                difference <= 2 -> 25
                difference <= 6 -> 18
                difference <= 12 -> 10
                difference <= 20 -> 0
                else -> -20
            }
        }

        if (containsPenaltyTerms(candidate.title)) score -= 35
        if (candidate.downloadLinks.isNotEmpty()) score += 8
        if (!candidate.thumbnailUrl.isNullOrBlank()) score += 4

        return score
    }

    private fun similarityScore(left: String, right: String): Int {
        val leftTokens = left.split(' ').filter { it.isNotBlank() }.toSet()
        val rightTokens = right.split(' ').filter { it.isNotBlank() }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0
        val overlap = leftTokens.intersect(rightTokens).size
        val denominator = max(leftTokens.size, rightTokens.size)
        return ((overlap.toDouble() / denominator.toDouble()) * 40.0).roundToInt()
    }

    private fun containsPenaltyTerms(value: String): Boolean {
        val normalized = normalizeTitle(value)
        return listOf(
            "karaoke",
            "instrumental",
            "cover",
            "tribute",
            "lofi",
            "slowed",
            "reverb",
            "remix",
            "dj",
            "8d",
        ).any { normalized.contains(it) }
    }

    private fun normalizeTitle(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace("&", "and")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseBitrate(quality: String): Int {
        val normalized = quality.lowercase(Locale.ROOT)
        return Regex("(\\d{2,3})").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { it * 1000 }
            ?: when {
                normalized.contains("lossless") -> 1411_000
                normalized.contains("high") -> 320_000
                normalized.contains("medium") -> 160_000
                else -> 96_000
            }
    }

    private fun inferMimeType(url: String): String = when {
        url.contains(".m4a", ignoreCase = true) || url.contains("aac", ignoreCase = true) -> "audio/mp4"
        url.contains(".ogg", ignoreCase = true) || url.contains("opus", ignoreCase = true) -> "audio/ogg"
        else -> "audio/mpeg"
    }

    private fun decodeMaybe(value: String): String {
        if (value.isBlank()) return value
        return runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
    }

    private fun upgradeImageUrl(value: String): String {
        if (value.isBlank()) return value
        return value
            .replace("50x50", "500x500", ignoreCase = true)
            .replace("150x150", "500x500", ignoreCase = true)
    }
}
