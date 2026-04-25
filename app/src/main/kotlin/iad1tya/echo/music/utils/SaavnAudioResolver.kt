package iad1tya.echo.music.utils

import android.text.Html
import android.util.Base64
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
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object SaavnAudioResolver {
    private const val PATCH_MARKER = "chatgpt-saavn-mapping-v6-title-artist-direct-api"
    private const val SAAVN_DES_KEY = "38346591"
    private const val API_BASE = "https://www.jiosaavn.com/api.php"
    private const val MAX_RESOLVE_CANDIDATES = 12
    private const val MAX_SEARCH_CANDIDATES = 20

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()

    private val mirrorBaseUrls = listOf(
        "https://saavn.sumit.co",
        "https://saavn.dev",
    )

    private val mirrorSearchPaths = listOf(
        "/api/search/songs",
        "/search/songs",
    )

    private val mirrorDetailPaths = listOf(
        "/api/songs/%s",
        "/songs/%s",
    )

    private val mirrorSuggestionPaths = listOf(
        "/api/songs/%s/suggestions",
        "/songs/%s/suggestions",
    )

    private val searchCache = linkedMapOf<String, List<Candidate>>()
    private val songCache = linkedMapOf<String, Candidate?>()

    private fun <T> putBoundedCache(cache: LinkedHashMap<String, T>, key: String, value: T, maxSize: Int) {
        if (!cache.containsKey(key) && cache.size >= maxSize) {
            cache.remove(cache.keys.firstOrNull())
        }
        cache[key] = value
    }

    private enum class ScriptFamily {
        LATIN,
        DEVANAGARI,
        ARABIC,
        CYRILLIC,
        BENGALI,
        GURMUKHI,
        GUJARATI,
        ORIYA,
        TAMIL,
        TELUGU,
        KANNADA,
        MALAYALAM,
        THAI,
        HEBREW,
        HANGUL,
        CJK,
        KANA,
        UNKNOWN,
    }

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

    data class SaavnSearchResult(
        val sourceSongId: String,
        val title: String,
        val artists: List<String>,
        val duration: Int?,
        val language: String?,
        val albumName: String?,
        val thumbnailUrl: String?,
    )

    data class RecommendationSeed(
        val title: String,
        val artists: List<String>,
        val duration: Int?,
        val albumName: String?,
        val language: String?,
        val sourceSongId: String,
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
        val language: String?,
        val albumName: String?,
        val downloadLinks: List<DownloadLink>,
        val thumbnailUrl: String?,
    )

    suspend fun resolve(
        mediaMetadata: MediaMetadata,
        audioQuality: AudioQuality,
    ): Result<ResolvedStream?> = withContext(Dispatchers.IO) {
        runCatching {
            val allCandidates = linkedMapOf<String, Candidate>()
            for (query in buildResolveQueries(mediaMetadata)) {
                search(query, MAX_RESOLVE_CANDIDATES).forEach { candidate ->
                    allCandidates.putIfAbsent(candidate.id, candidate)
                }

                bestAcceptedCandidate(allCandidates.values, mediaMetadata, early = true)?.let { candidate ->
                    buildResolvedStream(candidate, mediaMetadata, audioQuality)?.let { return@runCatching it }
                }
            }

            bestAcceptedCandidate(allCandidates.values, mediaMetadata, early = false)?.let { candidate ->
                buildResolvedStream(candidate, mediaMetadata, audioQuality)?.let { return@runCatching it }
            }

            null
        }
    }

    suspend fun resolveById(
        sourceSongId: String,
        audioQuality: AudioQuality,
    ): Result<ResolvedStream?> = withContext(Dispatchers.IO) {
        runCatching {
            val hydrated = fetchSong(sourceSongId) ?: return@runCatching null
            buildResolvedStream(hydrated, null, audioQuality)
        }
    }

    suspend fun recommendations(
        mediaMetadata: MediaMetadata,
        limit: Int = 8,
    ): Result<List<RecommendationSeed>> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolve(mediaMetadata, AudioQuality.AUTO).getOrNull()
            val baseCandidates = resolved?.songId?.let(::fetchSuggestions).orEmpty()
            val fallbackCandidates = if (baseCandidates.isEmpty()) searchFallbackRecommendations(mediaMetadata) else emptyList()
            val requestedTitle = normalizeTitleCore(mediaMetadata.title)
            val requestedPrimaryArtist = normalizeArtist(mediaMetadata.artists.firstOrNull()?.name.orEmpty())

            (baseCandidates + fallbackCandidates)
                .distinctBy { it.id }
                .filter { candidate ->
                    val candidateTitle = normalizeTitleCore(candidate.title)
                    val candidatePrimaryArtist = normalizeArtist(candidate.artists.firstOrNull().orEmpty())
                    !(candidateTitle == requestedTitle && candidatePrimaryArtist == requestedPrimaryArtist)
                }
                .sortedWith(
                    compareByDescending<Candidate> { score(it, mediaMetadata) }
                        .thenByDescending { qualityScore(it.downloadLinks) }
                )
                .take(limit)
                .map { candidate ->
                    RecommendationSeed(
                        title = candidate.title,
                        artists = candidate.artists,
                        duration = candidate.duration,
                        albumName = candidate.albumName,
                        language = candidate.language,
                        sourceSongId = candidate.id,
                    )
                }
        }
    }

    suspend fun searchSongs(
        query: String,
        limit: Int = 12,
    ): Result<List<SaavnSearchResult>> = withContext(Dispatchers.IO) {
        runCatching {
            if (query.isBlank()) return@runCatching emptyList()

            val candidates = buildSearchQueries(query)
                .flatMap { search(it, MAX_SEARCH_CANDIDATES) }
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<Candidate> { saavnSearchScore(it, query) }
                        .thenByDescending { qualityScore(it.downloadLinks) }
                        .thenBy { normalizeTitleCore(it.title).length }
                )
                .filter { candidate ->
                    !hasUnexpectedVariantTerms(candidate, query) || saavnSearchScore(candidate, query) >= 95
                }
                .take(limit)

            candidates.map { candidate ->
                val hydrated = if (candidate.thumbnailUrl.isNullOrBlank()) fetchSong(candidate.id) ?: candidate else candidate
                SaavnSearchResult(
                    sourceSongId = hydrated.id,
                    title = hydrated.title,
                    artists = hydrated.artists,
                    duration = hydrated.duration,
                    language = hydrated.language,
                    albumName = hydrated.albumName,
                    thumbnailUrl = hydrated.thumbnailUrl,
                )
            }
        }
    }

    private fun buildResolvedStream(
        candidate: Candidate,
        requested: MediaMetadata?,
        audioQuality: AudioQuality,
    ): ResolvedStream? {
        val hydrated = if (candidate.downloadLinks.isEmpty() || candidate.thumbnailUrl.isNullOrBlank()) {
            fetchSong(candidate.id) ?: candidate
        } else {
            candidate
        }

        if (requested != null) {
            val hydratedScore = score(hydrated, requested)
            if (!isStrongAccept(hydrated, hydratedScore, requested)) return null
        }

        val link = orderedDownloadLinks(hydrated.downloadLinks, audioQuality).firstOrNull() ?: return null
        val cleanedUrl = normalizeDownloadUrl(link.url) ?: return null
        return ResolvedStream(
            url = cleanedUrl,
            bitrate = link.bitrate.takeIf { it > 0 },
            mimeType = inferMimeType(cleanedUrl),
            sampleRate = 44100,
            provider = "Saavn",
            songId = hydrated.id,
            matchedTitle = hydrated.title,
            matchedArtists = hydrated.artists,
            thumbnailUrl = hydrated.thumbnailUrl,
            albumTitle = hydrated.albumName,
            durationSeconds = hydrated.duration,
        )
    }

    private fun bestAcceptedCandidate(
        candidates: Collection<Candidate>,
        requested: MediaMetadata,
        early: Boolean,
    ): Candidate? {
        if (candidates.isEmpty()) return null
        return candidates
            .asSequence()
            .map { candidate -> candidate to score(candidate, requested) }
            .filter { (candidate, candidateScore) -> isStrongAccept(candidate, candidateScore, requested) }
            .sortedWith(
                compareByDescending<Pair<Candidate, Int>> { it.second }
                    .thenByDescending { qualityScore(it.first.downloadLinks) }
            )
            .firstOrNull { (_, candidateScore) -> !early || candidateScore >= 250 }
            ?.first
    }

    private fun buildResolveQueries(mediaMetadata: MediaMetadata): List<String> {
        val titleRaw = mediaMetadata.title.trim()
        val primaryArtistRaw = mediaMetadata.artists.firstOrNull()?.name?.trim().orEmpty()
        val secondaryArtistRaw = mediaMetadata.artists.getOrNull(1)?.name?.trim().orEmpty()
        val albumRaw = mediaMetadata.album?.title?.trim().orEmpty()
        val lookupTitle = cleanupLookupTitle(titleRaw, primaryArtistRaw).ifBlank { titleRaw }
        val normalizedLookupTitle = normalizeTitleCore(lookupTitle)
        val primaryArtist = cleanupArtistForQuery(primaryArtistRaw)
        val secondaryArtist = cleanupArtistForQuery(secondaryArtistRaw)
        val album = cleanupLookupTitle(albumRaw, primaryArtistRaw)

        val queries = linkedSetOf<String>()
        fun add(vararg parts: String) {
            val query = parts.map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ").trim()
            if (query.isNotBlank()) queries += query
        }

        add(lookupTitle, primaryArtist)
        add(normalizedLookupTitle, primaryArtist)
        add(primaryArtist, lookupTitle)
        add(lookupTitle, primaryArtist, secondaryArtist)
        if (album.isNotBlank() && !normalizeTitleCore(album).contains(normalizedLookupTitle)) {
            add(lookupTitle, album, primaryArtist)
        }
        if (primaryArtist.isBlank()) {
            add(lookupTitle)
            add(normalizedLookupTitle)
        }

        return queries.take(6)
    }

    private fun buildSearchQueries(query: String): List<String> {
        val cleaned = cleanupLookupTitle(query, "")
        val normalized = normalizeTitleCore(cleaned)
        return linkedSetOf(query.trim(), cleaned, normalized)
            .filter { it.isNotBlank() }
            .take(3)
    }

    private fun search(query: String, maxResults: Int = MAX_RESOLVE_CANDIDATES): List<Candidate> {
        val cacheKey = "$query#$maxResults"
        searchCache[cacheKey]?.let { return it }

        val all = linkedMapOf<String, Candidate>()
        directSearch(query, maxResults).forEach { all.putIfAbsent(it.id, it) }
        if (all.size < maxResults) {
            mirrorSearch(query, maxResults - all.size).forEach { all.putIfAbsent(it.id, it) }
        }

        val results = all.values.take(maxResults).toList()
        putBoundedCache(searchCache, cacheKey, results, maxSize = 96)
        return results
    }

    private fun directSearch(query: String, maxResults: Int): List<Candidate> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "$API_BASE?__call=search.getResults&_format=json&_marker=0&ctx=web6dot0&p=1&n=$maxResults&q=$encoded"
        return fetchJson(url, saavnWebHeaders = true)?.let(::parseCandidates).orEmpty()
    }

    private fun mirrorSearch(query: String, maxResults: Int): List<Candidate> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val params = listOf("query=$encoded&limit=$maxResults", "q=$encoded&limit=$maxResults")
        val all = linkedMapOf<String, Candidate>()
        for (base in mirrorBaseUrls) {
            for (path in mirrorSearchPaths) {
                for (param in params) {
                    val url = base.trimEnd('/') + path + "?" + param
                    val json = fetchJson(url) ?: continue
                    parseCandidates(json).forEach { all.putIfAbsent(it.id, it) }
                    if (all.size >= maxResults) return all.values.take(maxResults).toList()
                }
            }
        }
        return all.values.take(maxResults).toList()
    }

    private fun fetchSong(songId: String): Candidate? {
        if (songCache.containsKey(songId)) return songCache[songId]

        val direct = fetchDirectSong(songId)
        if (direct != null) {
            putBoundedCache(songCache, songId, direct, maxSize = 160)
            return direct
        }

        val encodedId = URLEncoder.encode(songId, Charsets.UTF_8.name())
        for (base in mirrorBaseUrls) {
            for (template in mirrorDetailPaths) {
                val url = base.trimEnd('/') + template.format(encodedId)
                val candidate = fetchJson(url)?.let(::parseCandidates)?.firstOrNull()
                if (candidate != null) {
                    putBoundedCache(songCache, songId, candidate, maxSize = 160)
                    return candidate
                }
            }
        }

        putBoundedCache(songCache, songId, null, maxSize = 160)
        return null
    }

    private fun fetchDirectSong(songId: String): Candidate? {
        val encodedId = URLEncoder.encode(songId, Charsets.UTF_8.name())
        val url = "$API_BASE?__call=song.getDetails&_format=json&_marker=0&ctx=web6dot0&pids=$encodedId"
        return fetchJson(url, saavnWebHeaders = true)?.let(::parseCandidates)?.firstOrNull()
    }

    private fun fetchSuggestions(songId: String): List<Candidate> {
        val encodedId = URLEncoder.encode(songId, Charsets.UTF_8.name())
        val all = linkedMapOf<String, Candidate>()
        for (base in mirrorBaseUrls) {
            for (template in mirrorSuggestionPaths) {
                val url = base.trimEnd('/') + template.format(encodedId)
                val json = fetchJson(url) ?: continue
                parseCandidates(json).forEach { all.putIfAbsent(it.id, it) }
            }
        }
        return all.values.toList()
    }

    private fun searchFallbackRecommendations(seed: MediaMetadata): List<Candidate> {
        val title = cleanupLookupTitle(seed.title, seed.artists.firstOrNull()?.name.orEmpty())
        val primaryArtist = cleanupArtistForQuery(seed.artists.firstOrNull()?.name.orEmpty())
        val album = seed.album?.title?.trim().orEmpty()
        val queries = linkedSetOf(
            listOf(primaryArtist, album).filter { it.isNotBlank() }.joinToString(" ").trim(),
            listOf(title, primaryArtist).filter { it.isNotBlank() }.joinToString(" ").trim(),
            primaryArtist,
            album,
        ).filter { it.isNotBlank() }
        val all = linkedMapOf<String, Candidate>()
        queries.forEach { query -> search(query).forEach { all.putIfAbsent(it.id, it) } }
        return all.values.toList()
    }

    private fun fetchJson(url: String, saavnWebHeaders: Boolean = false): JSONObject? {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; EchoMusic) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        if (saavnWebHeaders) {
            builder
                .header("Referer", "https://www.jiosaavn.com/")
                .header("Origin", "https://www.jiosaavn.com")
                .header("Cookie", "L=english,hindi; gdpr_acceptance=true")
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string()?.trim().orEmpty()
                when {
                    body.isBlank() -> null
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
        val seenObjects = mutableSetOf<Int>()

        fun looksLikeSong(obj: JSONObject): Boolean {
            val type = obj.optString("type").lowercase(Locale.ROOT)
            val hasId = obj.optString("id").isNotBlank()
            val hasTitle = obj.optString("title").isNotBlank() || obj.optString("name").isNotBlank() || obj.optString("song").isNotBlank()
            return hasId && hasTitle && (type.isBlank() || type == "song" || obj.has("more_info") || obj.has("downloadUrl"))
        }

        fun collectObject(obj: JSONObject) {
            val identity = System.identityHashCode(obj)
            if (!seenObjects.add(identity)) return
            if (looksLikeSong(obj)) parseCandidate(obj)?.let(results::add)

            listOf("results", "songs", "data", "topquery").forEach { key ->
                val array = obj.optJSONArray(key)
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val item = array.opt(index)
                        if (item is JSONObject) collectObject(item)
                    }
                }
            }
            obj.optJSONObject("data")?.let { collectObject(it) }
        }

        collectObject(root)
        return results.distinctBy { it.id }
    }

    private fun parseCandidate(json: JSONObject): Candidate? {
        val moreInfo = json.optJSONObject("more_info")
        val id = firstNonBlank(
            json.optString("id"),
            json.optString("songid"),
            moreInfo?.optString("song_id").orEmpty(),
            moreInfo?.optString("id").orEmpty(),
        )
        if (id.isBlank()) return null

        val title = cleanDisplayText(firstNonBlank(
            json.optString("title"),
            json.optString("name"),
            json.optString("song"),
            moreInfo?.optString("song").orEmpty(),
        ))
        if (title.isBlank()) return null

        val duration = parseDuration(json.opt("duration") ?: moreInfo?.opt("duration"))
        val albumName = cleanDisplayText(firstNonBlank(
            json.optJSONObject("album")?.optString("name").orEmpty(),
            json.optString("album"),
            moreInfo?.optJSONObject("album")?.optString("name").orEmpty(),
            moreInfo?.optString("album").orEmpty(),
        )).takeIf { it.isNotBlank() }
        val language = cleanDisplayText(firstNonBlank(
            json.optString("language"),
            moreInfo?.optString("language").orEmpty(),
        )).takeIf { it.isNotBlank() }

        return Candidate(
            id = id,
            title = title,
            artists = parseArtists(json, moreInfo),
            duration = duration,
            language = language,
            albumName = albumName,
            downloadLinks = parseDownloadLinks(json, moreInfo),
            thumbnailUrl = parseThumbnail(json, moreInfo),
        )
    }

    private fun parseDuration(raw: Any?): Int? {
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }?.takeIf { it > 0 }
    }

    private fun parseThumbnail(json: JSONObject, moreInfo: JSONObject?): String? {
        val direct = firstNonBlank(
            json.optString("image"),
            json.optString("thumbnail"),
            json.optString("thumbnailUrl"),
            json.optString("image_url"),
            moreInfo?.optString("image").orEmpty(),
            moreInfo?.optString("thumbnail").orEmpty(),
            moreInfo?.optString("image_url").orEmpty(),
        )
        if (direct.isNotBlank()) return upgradeImageUrl(decodeSaavnUrl(direct))

        listOf(json.optJSONObject("image"), moreInfo?.optJSONObject("image"), json.optJSONObject("album"), moreInfo?.optJSONObject("album")).forEach { obj ->
            val nested = firstNonBlank(obj?.optString("url").orEmpty(), obj?.optString("link").orEmpty(), obj?.optString("image").orEmpty())
            if (nested.isNotBlank()) return upgradeImageUrl(decodeSaavnUrl(nested))
        }

        listOf(json.optJSONArray("image"), moreInfo?.optJSONArray("image")).forEach { array ->
            if (array == null) return@forEach
            for (index in array.length() - 1 downTo 0) {
                val item = array.optJSONObject(index) ?: continue
                val url = firstNonBlank(item.optString("url"), item.optString("link"))
                if (url.isNotBlank()) return upgradeImageUrl(decodeSaavnUrl(url))
            }
        }

        return null
    }

    private fun upgradeImageUrl(value: String): String {
        val decoded = decodeSaavnUrl(value).let { if (it.startsWith("//")) "https:$it" else it }
        return decoded
            .replace("50x50", "500x500")
            .replace("150x150", "500x500")
            .replace("_50x50", "_500x500")
            .replace("_150x150", "_500x500")
    }

    private fun parseArtists(json: JSONObject, moreInfo: JSONObject?): List<String> {
        val primaryArtists = linkedSetOf<String>()
        val extraArtists = linkedSetOf<String>()

        fun addRaw(raw: String, primary: Boolean) {
            cleanDisplayText(raw)
                .split(',', '&', ';')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { if (primary) primaryArtists += it else extraArtists += it }
        }

        fun collectStructured(obj: JSONObject?) {
            if (obj == null) return
            val structured = obj.optJSONObject("artists") ?: obj.optJSONObject("artistMap")
            if (structured != null) {
                listOf("primary", "primary_artists").forEach { key ->
                    val array = structured.optJSONArray(key) ?: return@forEach
                    for (index in 0 until array.length()) {
                        val name = cleanDisplayText(array.optJSONObject(index)?.optString("name").orEmpty())
                        if (name.isNotBlank()) primaryArtists += name
                    }
                }
                listOf("featured", "all", "artists").forEach { key ->
                    val array = structured.optJSONArray(key) ?: return@forEach
                    for (index in 0 until array.length()) {
                        val name = cleanDisplayText(array.optJSONObject(index)?.optString("name").orEmpty())
                        if (name.isNotBlank()) extraArtists += name
                    }
                }
            }
        }

        collectStructured(json)
        collectStructured(moreInfo)

        listOf(
            json.optString("primaryArtists"),
            json.optString("primary_artists"),
            moreInfo?.optString("primary_artists").orEmpty(),
            moreInfo?.optString("primaryArtists").orEmpty(),
        ).forEach { addRaw(it, primary = true) }

        listOf(
            json.optString("singers"),
            json.optString("music"),
            json.optString("artists").takeIf { json.opt("artists") is String }.orEmpty(),
            moreInfo?.optString("singers").orEmpty(),
            moreInfo?.optString("music").orEmpty(),
            moreInfo?.optString("artistMap").takeIf { moreInfo?.opt("artistMap") is String }.orEmpty(),
        ).forEach { addRaw(it, primary = primaryArtists.isEmpty()) }

        return (primaryArtists + extraArtists).toList().distinctBy { normalizeArtist(it) }
    }

    private fun parseDownloadLinks(json: JSONObject, moreInfo: JSONObject?): List<DownloadLink> {
        val links = mutableListOf<DownloadLink>()

        listOf(
            json.optString("encrypted_media_url"),
            json.optString("encryptedMediaUrl"),
            moreInfo?.optString("encrypted_media_url").orEmpty(),
            moreInfo?.optString("encryptedMediaUrl").orEmpty(),
        ).forEach { encrypted -> links += decryptEncryptedMediaLadder(encrypted) }

        listOf(
            json.optJSONArray("downloadUrl"),
            json.optJSONArray("download_url"),
            moreInfo?.optJSONArray("downloadUrl"),
            moreInfo?.optJSONArray("download_url"),
        ).forEach { array ->
            if (array == null) return@forEach
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = normalizeDownloadUrl(item.optString("url")) ?: continue
                val quality = firstNonBlank(item.optString("quality"), item.optString("bitrate"), item.optString("label"))
                val bitrate = parseBitrate(quality).takeIf { it > 0 } ?: inferBitrateFromUrl(url)
                links += DownloadLink(quality = quality, url = url, bitrate = bitrate)
            }
        }

        listOf(
            json.optString("vlink"),
            moreInfo?.optString("vlink").orEmpty(),
            json.optString("media_url"),
            moreInfo?.optString("media_url").orEmpty(),
            json.optString("media_preview_url"),
            moreInfo?.optString("media_preview_url").orEmpty(),
            json.optString("preview_url"),
            moreInfo?.optString("preview_url").orEmpty(),
        ).forEach { raw ->
            val url = normalizeDownloadUrl(raw) ?: return@forEach
            links += DownloadLink(quality = "direct", url = url, bitrate = inferBitrateFromUrl(url).takeIf { it > 0 } ?: 96_000)
        }

        return links.distinctBy { it.url }
    }

    private fun orderedDownloadLinks(links: List<DownloadLink>, audioQuality: AudioQuality): List<DownloadLink> {
        val normalized = links.mapNotNull { link -> normalizeDownloadUrl(link.url)?.let { link.copy(url = it) } }.distinctBy { it.url }
        return when (audioQuality) {
            AudioQuality.LOW -> normalized.sortedWith(
                compareBy<DownloadLink> { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }
                    .thenBy { if (it.url.contains(".mp4", ignoreCase = true)) 0 else 1 }
            )
            AudioQuality.AUTO, AudioQuality.HIGH -> normalized.sortedWith(
                compareByDescending<DownloadLink> { if (it.url.contains(".mp4", ignoreCase = true)) it.bitrate + 1_000_000 else it.bitrate }
                    .thenBy { if (it.url.contains("saavncdn.com", ignoreCase = true)) 0 else 1 }
            )
        }
    }

    private fun qualityScore(links: List<DownloadLink>): Int = links.maxOfOrNull { it.bitrate } ?: 0

    private fun saavnSearchScore(candidate: Candidate, query: String): Int {
        val normalizedQuery = normalizeTitleCore(query)
        val normalizedTitle = normalizeTitleCore(candidate.title)
        val artistText = candidate.artists.joinToString(" ") { normalizeArtist(it) }
        var score = 0
        score += when {
            normalizedTitle == normalizedQuery -> 160
            normalizedTitle.contains(normalizedQuery) || normalizedQuery.contains(normalizedTitle) -> 95
            else -> tokenSimilarity(normalizedTitle, normalizedQuery)
        }
        val queryTokens = normalizedQuery.split(' ').filter { it.length > 1 }.toSet()
        val artistTokens = artistText.split(' ').filter { it.length > 1 }.toSet()
        score += queryTokens.intersect(artistTokens).size * 28
        score += qualityScore(candidate.downloadLinks) / 10000
        score += penaltyScore(candidate, normalizedQuery)
        return score
    }

    private fun hasStrongPrimaryArtistMatch(candidate: Candidate, requested: MediaMetadata): Boolean {
        val requestedArtists = requested.artists.map { normalizeArtist(it.name) }.filter { it.isNotBlank() }
        if (requestedArtists.isEmpty()) return true
        val requestedPrimaryArtist = requestedArtists.first()
        val candidateArtists = candidate.artists.map(::normalizeArtist).filter { it.isNotBlank() }
        if (candidateArtists.isEmpty()) return false
        val candidatePrimaryArtist = candidateArtists.first()

        if (artistNamesMatch(candidatePrimaryArtist, requestedPrimaryArtist)) return true
        val requestedPrimaryAppears = candidateArtists.any { artistNamesMatch(it, requestedPrimaryArtist) }
        if (!requestedPrimaryAppears) return false

        return requestedArtists.drop(1).any { artistNamesMatch(candidatePrimaryArtist, it) }
    }

    private fun isStrongAccept(candidate: Candidate, score: Int, requested: MediaMetadata): Boolean {
        if (hasUnexpectedVariantTerms(candidate, requested.title)) return false
        val requestedTitle = normalizeTitleCore(cleanupLookupTitle(requested.title, requested.artists.firstOrNull()?.name.orEmpty()))
        val candidateTitle = normalizeTitleCore(candidate.title)
        if (requestedTitle.isBlank() || candidateTitle.isBlank()) return false

        val titleSimilarity = tokenSimilarity(candidateTitle, requestedTitle)
        val titleExact = candidateTitle == requestedTitle
        val titleStrong = titleExact || titleSimilarity >= 68 || strongContains(candidateTitle, requestedTitle)
        if (!titleStrong) return false

        val durationDiff = if (requested.duration > 0 && candidate.duration != null) abs(candidate.duration - requested.duration) else 0
        val durationClose = requested.duration <= 0 || candidate.duration == null || durationDiff <= 22
        if (!durationClose) return false

        val requestedPrimaryArtist = requested.artists.firstOrNull()?.name?.let(::normalizeArtist).orEmpty()
        if (requestedPrimaryArtist.isBlank()) return score >= 105

        return hasStrongPrimaryArtistMatch(candidate, requested) && score >= (if (titleExact) 205 else 225)
    }

    private fun score(candidate: Candidate, requested: MediaMetadata): Int {
        val requestedTitleRaw = cleanupLookupTitle(requested.title, requested.artists.firstOrNull()?.name.orEmpty())
        val requestedTitle = normalizeTitleCore(requestedTitleRaw)
        val candidateTitle = normalizeTitleCore(candidate.title)
        val requestedArtists = requested.artists.map { normalizeArtist(it.name) }.filter { it.isNotBlank() }
        val candidateArtists = candidate.artists.map(::normalizeArtist).filter { it.isNotBlank() }
        val requestedAlbum = normalizeTitleCore(requested.album?.title.orEmpty())
        val candidateAlbum = normalizeTitleCore(candidate.albumName.orEmpty())
        val requestedPrimaryArtist = requestedArtists.firstOrNull().orEmpty()
        val candidatePrimaryArtist = candidateArtists.firstOrNull().orEmpty()

        var score = 0
        score += when {
            candidateTitle == requestedTitle -> 150
            strongContains(candidateTitle, requestedTitle) -> 92
            else -> tokenSimilarity(candidateTitle, requestedTitle)
        }

        if (requestedPrimaryArtist.isNotBlank()) {
            score += when {
                artistNamesMatch(candidatePrimaryArtist, requestedPrimaryArtist) -> 180
                candidateArtists.any { artistNamesMatch(it, requestedPrimaryArtist) } &&
                    requestedArtists.drop(1).any { artistNamesMatch(candidatePrimaryArtist, it) } -> 112
                candidateArtists.any { artistNamesMatch(it, requestedPrimaryArtist) } -> 35
                candidateArtists.isNotEmpty() -> -140
                else -> -45
            }
        }

        requestedArtists.drop(1).forEach { wanted ->
            if (candidateArtists.any { found -> artistNamesMatch(found, wanted) }) score += 24
        }

        if (requestedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
            score += when {
                requestedAlbum == candidateAlbum -> 28
                strongContains(candidateAlbum, requestedAlbum) -> 12
                else -> 0
            }
        }

        if (requested.duration > 0 && candidate.duration != null && candidate.duration > 0) {
            val difference = abs(candidate.duration - requested.duration)
            score += when {
                difference <= 3 -> 30
                difference <= 8 -> 20
                difference <= 15 -> 8
                difference <= 22 -> 0
                else -> -35
            }
        }

        val requestedTitleScript = dominantScript(requested.title)
        val candidateTitleScript = dominantScript(candidate.title)
        if (requestedTitleScript != ScriptFamily.UNKNOWN && candidateTitleScript != ScriptFamily.UNKNOWN) {
            score += if (requestedTitleScript == candidateTitleScript) 10 else -25
        }

        score += languageHintScore(candidate, requestedTitleScript, requestedPrimaryArtist)
        score += penaltyScore(candidate, requestedTitle)
        if (!candidate.thumbnailUrl.isNullOrBlank()) score += 4
        if (candidate.downloadLinks.isNotEmpty()) score += 10
        return score
    }

    private fun languageHintScore(candidate: Candidate, requestedTitleScript: ScriptFamily, requestedPrimaryArtist: String): Int {
        val normalizedLanguage = normalizeLanguage(candidate.language.orEmpty())
        if (normalizedLanguage.isBlank()) return 0
        return when (requestedTitleScript) {
            ScriptFamily.LATIN -> when {
                normalizedLanguage == "english" -> 14
                requestedPrimaryArtist.any { it in 'a'..'z' } && normalizedLanguage in setOf("hindi", "bhojpuri", "punjabi", "marathi", "tamil", "telugu", "kannada", "malayalam") -> -8
                else -> 0
            }
            ScriptFamily.DEVANAGARI -> if (normalizedLanguage in setOf("hindi", "marathi", "nepali", "sanskrit")) 8 else 0
            ScriptFamily.BENGALI -> if (normalizedLanguage == "bengali") 8 else 0
            ScriptFamily.GURMUKHI -> if (normalizedLanguage == "punjabi") 8 else 0
            ScriptFamily.GUJARATI -> if (normalizedLanguage == "gujarati") 8 else 0
            ScriptFamily.TAMIL -> if (normalizedLanguage == "tamil") 8 else 0
            ScriptFamily.TELUGU -> if (normalizedLanguage == "telugu") 8 else 0
            ScriptFamily.KANNADA -> if (normalizedLanguage == "kannada") 8 else 0
            ScriptFamily.MALAYALAM -> if (normalizedLanguage == "malayalam") 8 else 0
            else -> 0
        }
    }

    private fun penaltyScore(candidate: Candidate, requestedTitle: String): Int {
        val requestedTerms = extractPenaltyTerms(requestedTitle)
        val candidateText = normalizeTitleCore(candidate.title) + " " +
            normalizeTitleCore(candidate.albumName.orEmpty()) + " " +
            candidate.artists.joinToString(" ") { normalizeArtist(it) }
        val extraTerms = extractPenaltyTerms(candidateText) - requestedTerms
        var score = 0
        if ("cover" in extraTerms) score -= 140
        if ("karaoke" in extraTerms) score -= 140
        if ("tribute" in extraTerms) score -= 115
        if ("instrumental" in extraTerms) score -= 125
        if ("acoustic" in extraTerms) score -= 90
        if ("live" in extraTerms) score -= 80
        if ("remix" in extraTerms) score -= 105
        if ("slowed" in extraTerms || "reverb" in extraTerms) score -= 110
        if ("nightcore" in extraTerms || "lofi" in extraTerms || "lo fi" in extraTerms) score -= 100
        if ("dubbed" in extraTerms || "hindi dubbed" in extraTerms) score -= 160
        if ("devotional" in extraTerms || "bhajan" in extraTerms || "aarti" in extraTerms) score -= 100
        return score
    }

    private fun extractPenaltyTerms(text: String): Set<String> {
        val normalized = normalizeTitleCore(text)
        val terms = linkedSetOf<String>()
        listOf(
            "cover",
            "karaoke",
            "tribute",
            "instrumental",
            "acoustic",
            "live",
            "remix",
            "slowed",
            "reverb",
            "sped up",
            "sped",
            "nightcore",
            "lofi",
            "lo fi",
            "version",
            "dubbed",
            "hindi dubbed",
            "dj",
            "mix",
            "devotional",
            "bhajan",
            "aarti",
        ).forEach { term -> if (normalized.contains(term)) terms += term }
        return terms
    }

    private fun hasUnexpectedVariantTerms(candidate: Candidate, requestedTitle: String): Boolean {
        val requestedTerms = extractPenaltyTerms(requestedTitle)
        val candidateTerms = extractPenaltyTerms(
            normalizeTitleCore(candidate.title) + " " +
                normalizeTitleCore(candidate.albumName.orEmpty()) + " " +
                candidate.artists.joinToString(" ") { normalizeArtist(it) }
        )
        val extraTerms = candidateTerms - requestedTerms
        return extraTerms.any {
            it in setOf(
                "cover",
                "karaoke",
                "tribute",
                "instrumental",
                "acoustic",
                "live",
                "remix",
                "slowed",
                "reverb",
                "nightcore",
                "lofi",
                "lo fi",
                "dubbed",
                "hindi dubbed",
                "devotional",
                "bhajan",
                "aarti",
            )
        }
    }

    private fun artistNamesMatch(left: String, right: String): Boolean {
        val l = normalizeArtist(left)
        val r = normalizeArtist(right)
        if (l.isBlank() || r.isBlank()) return false
        if (l == r) return true
        val leftTokens = l.split(' ').filter { it.length > 1 }.toSet()
        val rightTokens = r.split(' ').filter { it.length > 1 }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return false
        val overlap = leftTokens.intersect(rightTokens).size
        val minSize = minOf(leftTokens.size, rightTokens.size)
        if (minSize == 1) return overlap == 1 && leftTokens.first() == rightTokens.first()
        return overlap >= minOf(2, minSize) || overlap.toDouble() / minSize.toDouble() >= 0.75
    }

    private fun tokenSimilarity(left: String, right: String): Int {
        val leftTokens = left.split(' ').filter { it.isNotBlank() }.toSet()
        val rightTokens = right.split(' ').filter { it.isNotBlank() }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0
        val overlap = leftTokens.intersect(rightTokens).size
        val denominator = max(leftTokens.size, rightTokens.size)
        return ((overlap.toDouble() / denominator.toDouble()) * 100.0).roundToInt()
    }

    private fun strongContains(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        val shorter = if (left.length <= right.length) left else right
        val longer = if (left.length > right.length) left else right
        return shorter.length >= 4 && longer.contains(shorter)
    }

    private fun cleanupLookupTitle(value: String, primaryArtist: String): String {
        var raw = cleanDisplayText(value).trim()
        if (raw.isBlank()) return raw
        raw = raw
            .replace(Regex("""\s+[•·|]\s+.*$"""), " ")
            .replace(Regex("""\b(official music video|official video|lyric video|lyrics|audio|visualizer|hd|4k)\b""", RegexOption.IGNORE_CASE), " ")
            .trim()

        val normalizedArtist = normalizeArtist(primaryArtist)
        listOf(" - ", " – ", " — ").forEach { separator ->
            if (raw.contains(separator)) {
                val parts = raw.split(separator, limit = 2).map { it.trim() }
                if (parts.size == 2) {
                    val left = parts[0]
                    val right = parts[1]
                    val leftIsArtist = normalizedArtist.isNotBlank() && artistNamesMatch(left, normalizedArtist)
                    val rightIsArtist = normalizedArtist.isNotBlank() && artistNamesMatch(right, normalizedArtist)
                    return when {
                        leftIsArtist && !rightIsArtist -> right
                        rightIsArtist && !leftIsArtist -> left
                        else -> raw
                    }
                }
            }
        }
        return raw.replace(Regex("""\s+"""), " ").trim()
    }

    private fun cleanupArtistForQuery(value: String): String {
        return cleanDisplayText(value)
            .replace(Regex("""(?i)\s*-\s*topic$"""), "")
            .replace(Regex("""(?i)\s*vevo$"""), "")
            .replace(Regex("""(?i)\s*official$"""), "")
            .replace(Regex("""(?i)\s*records$"""), "")
            .replace(Regex("""(?i)\s*music$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeArtist(value: String): String {
        return normalizeBasic(cleanupArtistForQuery(value))
            .replace(Regex("""\b(feat|featuring|ft|with|and)\b"""), " ")
            .replace(Regex("""\b(the)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeTitleCore(value: String): String {
        return normalizeBasic(value)
            .replace(Regex("""\b(official|lyric|lyrics|audio|video|visualizer|remaster|from|full|song|hd|4k)\b"""), " ")
            .replace(Regex("""\b(feat|featuring|ft)\b.*$"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeBasic(value: String): String {
        val stripped = Normalizer.normalize(cleanDisplayText(value).lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return stripped
            .replace('&', ' ')
            .replace(Regex("""[^\p{L}\p{N} ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeLanguage(value: String): String = normalizeBasic(value).replace(' ', '-')

    private fun cleanDisplayText(value: String): String {
        if (value.isBlank()) return ""
        val decoded = runCatching { Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString() }.getOrDefault(value)
        return decoded
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .trim()
    }

    private fun decodeSaavnUrl(value: String): String {
        var decoded = value.trim()
        repeat(2) {
            decoded = runCatching { URLDecoder.decode(decoded, Charsets.UTF_8.name()) }.getOrDefault(decoded)
        }
        return decoded.replace("\\/", "/")
    }

    private fun normalizeDownloadUrl(raw: String?): String? {
        val value = decodeSaavnUrl(raw.orEmpty())
        if (value.isBlank()) return null
        val withScheme = if (value.startsWith("//")) "https:$value" else value
        return withScheme.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun decryptEncryptedMediaLadder(value: String): List<DownloadLink> {
        if (value.isBlank()) return emptyList()
        val decrypted = runCatching {
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(SAAVN_DES_KEY.toByteArray(Charsets.UTF_8), "DES"))
            val decoded = Base64.decode(decodeSaavnUrl(value).trim(), Base64.DEFAULT)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        }.getOrNull() ?: return emptyList()

        val cleaned = normalizeDownloadUrl(decrypted) ?: return emptyList()
        val template = if (Regex("""_(48|96|160|320)\.mp4(?=\?|$)""").containsMatchIn(cleaned)) {
            cleaned
        } else {
            cleaned.replace(".mp4", "_96.mp4")
        }
        val qualities = listOf(320, 160, 96, 48)
        return qualities.map { bitrate ->
            val url = template.replace(Regex("""_(48|96|160|320)\.mp4(?=\?|$)"""), "_${bitrate}.mp4")
            DownloadLink(quality = "${bitrate}kbps", url = url, bitrate = bitrate * 1000)
        }.distinctBy { it.url }
    }

    private fun inferBitrateFromUrl(url: String): Int {
        val match = Regex("""_(48|96|160|320)\.mp4""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(48|96|128|160|192|256|320)k""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return 0
        return match * 1000
    }

    private fun parseBitrate(quality: String): Int {
        val number = Regex("""(\d+)""").find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return 0
        return number * 1000
    }

    private fun inferMimeType(url: String): String {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains(".mp4") || lower.contains(".m4a") || lower.contains("mime=audio/mp4") -> "audio/mp4"
            lower.contains(".aac") -> "audio/aac"
            lower.contains(".webm") || lower.contains("opus") -> "audio/webm"
            else -> "audio/mpeg"
        }
    }

    private fun dominantScript(value: String): ScriptFamily {
        val text = value.trim()
        if (text.isBlank()) return ScriptFamily.UNKNOWN
        for (ch in text) {
            if (!Character.isLetter(ch)) continue
            return when (Character.UnicodeBlock.of(ch)) {
                Character.UnicodeBlock.BASIC_LATIN,
                Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                Character.UnicodeBlock.LATIN_EXTENDED_A,
                Character.UnicodeBlock.LATIN_EXTENDED_B,
                Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL -> ScriptFamily.LATIN
                Character.UnicodeBlock.DEVANAGARI,
                Character.UnicodeBlock.DEVANAGARI_EXTENDED -> ScriptFamily.DEVANAGARI
                Character.UnicodeBlock.ARABIC,
                Character.UnicodeBlock.ARABIC_SUPPLEMENT,
                Character.UnicodeBlock.ARABIC_EXTENDED_A -> ScriptFamily.ARABIC
                Character.UnicodeBlock.CYRILLIC,
                Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY,
                Character.UnicodeBlock.CYRILLIC_EXTENDED_A,
                Character.UnicodeBlock.CYRILLIC_EXTENDED_B -> ScriptFamily.CYRILLIC
                Character.UnicodeBlock.BENGALI -> ScriptFamily.BENGALI
                Character.UnicodeBlock.GURMUKHI -> ScriptFamily.GURMUKHI
                Character.UnicodeBlock.GUJARATI -> ScriptFamily.GUJARATI
                Character.UnicodeBlock.ORIYA -> ScriptFamily.ORIYA
                Character.UnicodeBlock.TAMIL -> ScriptFamily.TAMIL
                Character.UnicodeBlock.TELUGU -> ScriptFamily.TELUGU
                Character.UnicodeBlock.KANNADA -> ScriptFamily.KANNADA
                Character.UnicodeBlock.MALAYALAM -> ScriptFamily.MALAYALAM
                Character.UnicodeBlock.THAI -> ScriptFamily.THAI
                Character.UnicodeBlock.HEBREW -> ScriptFamily.HEBREW
                Character.UnicodeBlock.HANGUL_SYLLABLES,
                Character.UnicodeBlock.HANGUL_JAMO,
                Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> ScriptFamily.HANGUL
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
                Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS -> ScriptFamily.CJK
                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA,
                Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS -> ScriptFamily.KANA
                else -> ScriptFamily.UNKNOWN
            }
        }
        return ScriptFamily.UNKNOWN
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()
}
