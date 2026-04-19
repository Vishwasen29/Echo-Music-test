package iad1tya.echo.music.utils

import iad1tya.echo.music.constants.AudioQuality
import iad1tya.echo.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URLDecoder
import java.text.Normalizer
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

    private val suggestionPaths = listOf(
        "/api/songs/%s/suggestions",
        "/songs/%s/suggestions",
    )

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
            val queryVariants = buildQueries(mediaMetadata)
            val allCandidates = linkedMapOf<String, Candidate>()
            queryVariants.forEach { query ->
                search(query).forEach { candidate ->
                    allCandidates.putIfAbsent(candidate.id, candidate)
                }
            }
            if (allCandidates.isEmpty()) return@runCatching null

            val ranked = allCandidates.values
                .map { candidate -> candidate to score(candidate, mediaMetadata) }
                .sortedWith(
                    compareByDescending<Pair<Candidate, Int>> { it.second }
                        .thenByDescending { qualityScore(it.first.downloadLinks) }
                )

            for ((candidate, candidateScore) in ranked) {
                if (!isStrongAccept(candidate, candidateScore, mediaMetadata)) continue
                val hydrated = if (candidate.downloadLinks.isNotEmpty() && !candidate.thumbnailUrl.isNullOrBlank()) {
                    candidate
                } else {
                    fetchSong(candidate.id) ?: candidate
                }

                for (link in orderedDownloadLinks(hydrated.downloadLinks, audioQuality)) {
                    val cleanedUrl = normalizeDownloadUrl(link.url) ?: continue
                    return@runCatching ResolvedStream(
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
            for (link in orderedDownloadLinks(hydrated.downloadLinks, audioQuality)) {
                val cleanedUrl = normalizeDownloadUrl(link.url) ?: continue
                return@runCatching ResolvedStream(
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
            null
        }
    }

    suspend fun recommendations(
        mediaMetadata: MediaMetadata,
        limit: Int = 8,
    ): Result<List<RecommendationSeed>> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = resolve(mediaMetadata, AudioQuality.AUTO).getOrNull()
            val baseCandidates = resolved?.songId?.let(::fetchSuggestions).orEmpty()
            val fallbackCandidates = if (baseCandidates.isEmpty()) {
                searchFallbackRecommendations(mediaMetadata)
            } else {
                emptyList()
            }

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

            search(query)
                .distinctBy { it.id }
                .filter { !hasUnexpectedVariantTerms(it, query) }
                .sortedWith(
                    compareByDescending<Candidate> { saavnSearchScore(it, query) }
                        .thenByDescending { qualityScore(it.downloadLinks) }
                        .thenBy { normalizeTitleCore(it.title).length }
                )
                .take(limit)
                .map { candidate ->
                    SaavnSearchResult(
                        sourceSongId = candidate.id,
                        title = candidate.title,
                        artists = candidate.artists,
                        duration = candidate.duration,
                        language = candidate.language,
                        albumName = candidate.albumName,
                    )
                }
        }
    }


    private fun saavnSearchScore(candidate: Candidate, query: String): Int {
        val normalizedQuery = normalizeTitleCore(query)
        val normalizedTitle = normalizeTitleCore(candidate.title)
        val artistText = candidate.artists.joinToString(" ") { normalizeArtist(it) }
        var score = 0

        score += when {
            normalizedTitle == normalizedQuery -> 150
            normalizedQuery.contains(normalizedTitle) || normalizedTitle.contains(normalizedQuery) -> 90
            else -> tokenSimilarity(normalizedTitle, normalizedQuery)
        }

        val queryTokens = normalizedQuery.split(' ').filter { it.length > 1 }.toSet()
        val artistTokens = artistText.split(' ').filter { it.length > 1 }.toSet()
        score += queryTokens.intersect(artistTokens).size * 24
        score += qualityScore(candidate.downloadLinks) / 10000
        score += penaltyScore(candidate, normalizedQuery)
        return score
    }

    private fun buildQueries(mediaMetadata: MediaMetadata): List<String> {
        val title = mediaMetadata.title.trim()
        val primaryArtist = mediaMetadata.artists.firstOrNull()?.name?.trim().orEmpty()
        val secondaryArtist = mediaMetadata.artists.getOrNull(1)?.name?.trim().orEmpty()
        val album = mediaMetadata.album?.title?.trim().orEmpty()
        val strippedTitle = normalizeTitleCore(title)
        val hasArtist = primaryArtist.isNotBlank()

        val strictQueries = linkedSetOf<String>()
        strictQueries += listOf(title, primaryArtist).filter { it.isNotBlank() }.joinToString(" ").trim()
        strictQueries += listOf(strippedTitle, primaryArtist).filter { it.isNotBlank() }.joinToString(" ").trim()
        strictQueries += listOf(title, primaryArtist, secondaryArtist).filter { it.isNotBlank() }.joinToString(" ").trim()
        strictQueries += listOf(strippedTitle, primaryArtist, album).filter { it.isNotBlank() }.joinToString(" ").trim()

        val fallbackQueries = linkedSetOf<String>()
        if (!hasArtist) {
            fallbackQueries += title
            fallbackQueries += strippedTitle
        }

        return buildList {
            addAll(strictQueries.filter { it.isNotBlank() })
            addAll(fallbackQueries.filter { it.isNotBlank() && it !in strictQueries })
        }
    }

    private fun search(query: String): List<Candidate> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val paramVariants = listOf(
            "query=$encoded&limit=12",
            "q=$encoded&limit=12",
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

    private fun fetchSuggestions(songId: String): List<Candidate> {
        val encodedId = URLEncoder.encode(songId, Charsets.UTF_8.name())
        val all = linkedMapOf<String, Candidate>()
        for (base in baseUrls) {
            for (template in suggestionPaths) {
                val url = base.trimEnd('/') + template.format(encodedId)
                val json = fetchJson(url) ?: continue
                parseCandidates(json).forEach { all.putIfAbsent(it.id, it) }
            }
        }
        return all.values.toList()
    }

    private fun searchFallbackRecommendations(seed: MediaMetadata): List<Candidate> {
        val title = seed.title.trim()
        val primaryArtist = seed.artists.firstOrNull()?.name?.trim().orEmpty()
        val album = seed.album?.title?.trim().orEmpty()

        val queries = linkedSetOf(
            listOf(primaryArtist, album).filter { it.isNotBlank() }.joinToString(" ").trim(),
            listOf(title, primaryArtist).filter { it.isNotBlank() }.joinToString(" ").trim(),
            primaryArtist,
            album,
        ).filter { it.isNotBlank() }

        val all = linkedMapOf<String, Candidate>()
        queries.forEach { query ->
            search(query).forEach { candidate ->
                all.putIfAbsent(candidate.id, candidate)
            }
        }
        return all.values.toList()
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

        val albumName = json.optJSONObject("album")
            ?.optString("name")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val language = json.optString("language")
            .trim()
            .takeIf { it.isNotBlank() }

        return Candidate(
            id = id,
            title = title,
            artists = parseArtists(json),
            duration = duration,
            language = language,
            albumName = albumName,
            thumbnailUrl = parseThumbnail(json),
            downloadLinks = parseDownloadLinks(json),
        )
    }



    private fun parseThumbnail(json: JSONObject): String? {
        val direct = listOf(
            json.optString("image"),
            json.optString("thumbnail"),
            json.optString("thumbnailUrl"),
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }
        if (!direct.isNullOrBlank()) return upgradeImageUrl(direct)

        val imageObject = json.optJSONObject("image")
        if (imageObject != null) {
            listOf(
                imageObject.optString("url"),
                imageObject.optString("link"),
            ).map { it.trim() }.firstOrNull { it.isNotBlank() }?.let { return upgradeImageUrl(it) }
        }

        val imageArray = json.optJSONArray("image")
        if (imageArray != null) {
            for (index in imageArray.length() - 1 downTo 0) {
                val item = imageArray.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if (url.isNotBlank()) return upgradeImageUrl(url)
            }
        }

        val albumObject = json.optJSONObject("album")
        if (albumObject != null) {
            listOf(
                albumObject.optString("image"),
                albumObject.optString("thumbnail"),
                albumObject.optString("thumbnailUrl"),
            ).map { it.trim() }.firstOrNull { it.isNotBlank() }?.let { return upgradeImageUrl(it) }
        }

        return null
    }

    private fun upgradeImageUrl(value: String): String {
        return value
            .replace("50x50", "500x500")
            .replace("150x150", "500x500")
            .replace("_50x50", "_500x500")
            .replace("_150x150", "_500x500")
    }
    private fun parseArtists(json: JSONObject): List<String> {
        val primaryArtists = linkedSetOf<String>()
        val extraArtists = linkedSetOf<String>()

        val structured = json.optJSONObject("artists")
        if (structured != null) {
            listOf("primary").forEach { key ->
                val array = structured.optJSONArray(key) ?: return@forEach
                for (index in 0 until array.length()) {
                    val artist = array.optJSONObject(index) ?: continue
                    val name = artist.optString("name").trim()
                    if (name.isNotBlank()) primaryArtists += name
                }
            }
            listOf("featured", "all").forEach { key ->
                val array = structured.optJSONArray(key) ?: return@forEach
                for (index in 0 until array.length()) {
                    val artist = array.optJSONObject(index) ?: continue
                    val name = artist.optString("name").trim()
                    if (name.isNotBlank()) extraArtists += name
                }
            }
        }

        listOf(
            json.optString("primaryArtists"),
            json.optString("primary_artists"),
            json.optString("singers"),
            json.optString("music"),
            json.optString("artistMap"),
            json.optString("artists").takeIf { json.opt("artists") is String } ?: "",
        ).forEach { raw ->
            raw.split(',', '&').map { it.trim() }.filter { it.isNotBlank() }.forEachIndexed { index, name ->
                if (index == 0) primaryArtists += name else extraArtists += name
            }
        }

        return (primaryArtists + extraArtists).toList()
    }
    private fun parseDownloadLinks(json: JSONObject): List<DownloadLink> {
        val list = mutableListOf<DownloadLink>()
        val arrays = listOf(
            json.optJSONArray("downloadUrl"),
            json.optJSONArray("download_url"),
        )
        arrays.forEach { array ->
            if (array == null) return@forEach
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val rawUrl = item.optString("url").trim()
                val url = normalizeDownloadUrl(rawUrl) ?: continue
                val quality = item.optString("quality").ifBlank { item.optString("bitrate") }.trim()
                list += DownloadLink(
                    quality = quality,
                    url = url,
                    bitrate = parseBitrate(quality),
                )
            }
        }

        val directCandidates = listOf(
            json.optString("vlink").trim(),
            json.optString("media_preview_url").trim(),
            json.optString("preview_url").trim(),
        )
        directCandidates.forEach { raw ->
            val url = normalizeDownloadUrl(raw) ?: return@forEach
            list += DownloadLink(
                quality = "preview",
                url = url,
                bitrate = parseBitrate("96kbps"),
            )
        }

        return list.distinctBy { it.url }
    }

    private fun normalizeDownloadUrl(raw: String?): String? {
        var value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        repeat(2) {
            value = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
        return value.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun orderedDownloadLinks(
        links: List<DownloadLink>,
        audioQuality: AudioQuality,
    ): List<DownloadLink> {
        val normalized = links.mapNotNull { link ->
            normalizeDownloadUrl(link.url)?.let { cleaned -> link.copy(url = cleaned) }
        }.distinctBy { it.url }

        return when (audioQuality) {
            AudioQuality.LOW -> normalized.sortedWith(
                compareBy<DownloadLink> { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }
                    .thenBy { if (it.url.contains("saavncdn.com", ignoreCase = true)) 1 else 0 }
            )
            AudioQuality.AUTO, AudioQuality.HIGH -> normalized.sortedWith(
                compareByDescending<DownloadLink> { it.bitrate }
                    .thenBy { if (it.url.contains("saavncdn.com", ignoreCase = true)) 1 else 0 }
            )
        }
    }

    private fun pickDownloadLink(
        links: List<DownloadLink>,
        audioQuality: AudioQuality,
    ): DownloadLink? = orderedDownloadLinks(links, audioQuality).firstOrNull()

    private fun qualityScore(links: List<DownloadLink>): Int = links.maxOfOrNull { it.bitrate } ?: 0

    private fun hasStrongPrimaryArtistMatch(candidate: Candidate, requested: MediaMetadata): Boolean {
        val requestedArtists = requested.artists.map { normalizeArtist(it.name) }.filter { it.isNotBlank() }
        val candidateArtists = candidate.artists.map(::normalizeArtist).filter { it.isNotBlank() }
        val requestedPrimaryArtist = requestedArtists.firstOrNull().orEmpty()
        if (requestedPrimaryArtist.isBlank()) return true

        val candidatePrimaryArtist = candidateArtists.firstOrNull().orEmpty()
        if (candidatePrimaryArtist == requestedPrimaryArtist) return true
        if (candidateArtists.any { artistNamesMatch(it, requestedPrimaryArtist) }) return true

        return requestedArtists.any { wanted ->
            candidateArtists.any { found -> artistNamesMatch(found, wanted) }
        }
    }

    private fun isStrongAccept(candidate: Candidate, score: Int, requested: MediaMetadata): Boolean {
        if (hasUnexpectedVariantTerms(candidate, requested.title)) return false

        val requestedPrimaryArtist = requested.artists.firstOrNull()?.name?.let(::normalizeArtist).orEmpty()
        val requestedTitle = normalizeTitleCore(requested.title)
        val candidateTitle = normalizeTitleCore(candidate.title)
        val titleStrong = candidateTitle == requestedTitle ||
            candidateTitle.contains(requestedTitle) ||
            requestedTitle.contains(candidateTitle) ||
            tokenSimilarity(candidateTitle, requestedTitle) >= 32
        val durationClose = requested.duration <= 0 ||
            candidate.duration == null ||
            kotlin.math.abs(candidate.duration - requested.duration) <= 18

        return if (requestedPrimaryArtist.isBlank()) {
            score >= 92 && titleStrong && durationClose
        } else {
            score >= 96 && titleStrong && durationClose && hasStrongPrimaryArtistMatch(candidate, requested)
        }
    }

    private fun score(candidate: Candidate, requested: MediaMetadata): Int {
        val requestedTitleRaw = requested.title.trim()
        val candidateTitleRaw = candidate.title.trim()
        val requestedTitle = normalizeTitleCore(requestedTitleRaw)
        val candidateTitle = normalizeTitleCore(candidateTitleRaw)
        val requestedArtists = requested.artists.map { normalizeArtist(it.name) }.filter { it.isNotBlank() }
        val candidateArtists = candidate.artists.map(::normalizeArtist).filter { it.isNotBlank() }
        val requestedAlbum = normalizeTitleCore(requested.album?.title.orEmpty())
        val candidateAlbum = normalizeTitleCore(candidate.albumName.orEmpty())
        val requestedPrimaryArtist = requestedArtists.firstOrNull().orEmpty()
        val candidatePrimaryArtist = candidateArtists.firstOrNull().orEmpty()

        var score = 0

        when {
            candidateTitle == requestedTitle -> score += 135
            candidateTitleRaw.equals(requestedTitleRaw, ignoreCase = true) -> score += 125
            candidateTitle.contains(requestedTitle) || requestedTitle.contains(candidateTitle) -> score += 85
            else -> score += tokenSimilarity(candidateTitle, requestedTitle)
        }

        if (requestedPrimaryArtist.isNotBlank()) {
            score += when {
                candidatePrimaryArtist == requestedPrimaryArtist -> 125
                candidateArtists.any { artistNamesMatch(it, requestedPrimaryArtist) } -> 96
                candidateArtists.isNotEmpty() -> -55
                else -> -18
            }
        }

        val secondaryMatches = requestedArtists.drop(1).count { wanted ->
            candidateArtists.any { found -> artistNamesMatch(found, wanted) }
        }
        score += secondaryMatches * 24

        if (requestedArtists.isNotEmpty() && candidateArtists.isNotEmpty() && secondaryMatches == 0 && !candidateArtists.any { artistNamesMatch(it, requestedPrimaryArtist) }) {
            score -= 24
        }

        if (requestedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
            score += when {
                requestedAlbum == candidateAlbum -> 28
                candidateAlbum.contains(requestedAlbum) || requestedAlbum.contains(candidateAlbum) -> 14
                else -> -8
            }
        }

        if (requested.duration > 0 && candidate.duration != null && candidate.duration > 0) {
            val difference = abs(candidate.duration - requested.duration)
            score += when {
                difference <= 2 -> 30
                difference <= 5 -> 22
                difference <= 10 -> 12
                difference <= 18 -> 0
                else -> -28
            }
        }

        val requestedTitleScript = dominantScript(requested.title)
        val candidateTitleScript = dominantScript(candidate.title)
        if (requestedTitleScript != ScriptFamily.UNKNOWN && candidateTitleScript != ScriptFamily.UNKNOWN) {
            score += if (requestedTitleScript == candidateTitleScript) 12 else -18
        }

        if (requestedPrimaryArtist.isNotBlank()) {
            val requestedArtistScript = dominantScript(requested.artists.firstOrNull()?.name.orEmpty())
            val candidateArtistScript = dominantScript(candidate.artists.firstOrNull().orEmpty())
            if (requestedArtistScript != ScriptFamily.UNKNOWN && candidateArtistScript != ScriptFamily.UNKNOWN) {
                score += if (requestedArtistScript == candidateArtistScript) 8 else -12
            }
        }

        score += languageHintScore(candidate, requestedTitleScript)
        score += penaltyScore(candidate, requestedTitle)

        if (!candidate.thumbnailUrl.isNullOrBlank()) score += 4
        if (candidate.downloadLinks.isNotEmpty()) score += 6

        return score
    }

    private fun languageHintScore(candidate: Candidate, requestedTitleScript: ScriptFamily): Int {
        val normalizedLanguage = normalizeLanguage(candidate.language.orEmpty())
        if (normalizedLanguage.isBlank()) return 0
        return when (requestedTitleScript) {
            ScriptFamily.LATIN -> if (normalizedLanguage == "english") 5 else 0
            ScriptFamily.DEVANAGARI -> if (normalizedLanguage in setOf("hindi", "marathi", "nepali", "sanskrit")) 5 else 0
            ScriptFamily.BENGALI -> if (normalizedLanguage == "bengali") 6 else 0
            ScriptFamily.GURMUKHI -> if (normalizedLanguage == "punjabi") 6 else 0
            ScriptFamily.GUJARATI -> if (normalizedLanguage == "gujarati") 6 else 0
            ScriptFamily.TAMIL -> if (normalizedLanguage == "tamil") 6 else 0
            ScriptFamily.TELUGU -> if (normalizedLanguage == "telugu") 6 else 0
            ScriptFamily.KANNADA -> if (normalizedLanguage == "kannada") 6 else 0
            ScriptFamily.MALAYALAM -> if (normalizedLanguage == "malayalam") 6 else 0
            else -> 0
        }
    }

    private fun penaltyScore(candidate: Candidate, requestedTitle: String): Int {
        val requestedPenaltyTerms = extractPenaltyTerms(requestedTitle)
        val candidateTerms = extractPenaltyTerms(
            normalizeTitleCore(candidate.title) + " " +
                normalizeTitleCore(candidate.albumName.orEmpty()) + " " +
                candidate.artists.joinToString(" ") { normalizeArtist(it) }
        )
        val extraTerms = candidateTerms - requestedPenaltyTerms
        var score = 0
        if ("cover" in extraTerms) score -= 120
        if ("karaoke" in extraTerms) score -= 125
        if ("tribute" in extraTerms) score -= 95
        if ("instrumental" in extraTerms) score -= 100
        if ("acoustic" in extraTerms) score -= 80
        if ("live" in extraTerms) score -= 75
        if ("remix" in extraTerms) score -= 90
        if ("slowed" in extraTerms || "reverb" in extraTerms) score -= 95
        if ("nightcore" in extraTerms || "lofi" in extraTerms || "lo fi" in extraTerms) score -= 90
        if ("dj" in extraTerms || "mix" in extraTerms) score -= 60
        if ("devotional" in extraTerms || "bhajan" in extraTerms || "aarti" in extraTerms) score -= 90
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
            "dj",
            "mix",
            "devotional",
            "bhajan",
            "aarti",
        ).forEach { term ->
            if (normalized.contains(term)) terms += term
        }
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
                "devotional",
                "bhajan",
                "aarti",
            )
        }
    }

    private fun decodeSaavnUrl(value: String): String {
        return runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }
            .getOrDefault(value)
            .replace("\\/", "/")
    }

    private fun artistNamesMatch(left: String, right: String): Boolean {
        if (left == right) return true

        val leftTokens = left.split(' ').filter { it.length > 1 }.toSet()
        val rightTokens = right.split(' ').filter { it.length > 1 }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return false

        val overlap = leftTokens.intersect(rightTokens).size
        val minSize = minOf(leftTokens.size, rightTokens.size)
        return overlap >= minOf(2, minSize) || (minSize == 1 && overlap == 1 && left.length == right.length)
    }

    private fun tokenSimilarity(left: String, right: String): Int {
        val leftTokens = left.split(' ').filter { it.isNotBlank() }.toSet()
        val rightTokens = right.split(' ').filter { it.isNotBlank() }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0
        val overlap = leftTokens.intersect(rightTokens).size
        val denominator = max(leftTokens.size, rightTokens.size)
        return ((overlap.toDouble() / denominator.toDouble()) * 55.0).roundToInt()
    }

    private fun normalizeArtist(value: String): String {
        return normalizeBasic(value)
            .replace(Regex("""\b(feat|featuring|ft)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeTitleCore(value: String): String {
        return normalizeBasic(value)
            .replace(Regex("""\((official|lyric|lyrics|audio|video|visualizer|remaster|version|from .*?)\)"""), " ")
            .replace(Regex("""\[(official|lyric|lyrics|audio|video|visualizer|remaster|version|from .*?)\]"""), " ")
            .replace(Regex("""\b(feat|featuring|ft)\b.*$"""), " ")
            .replace(Regex("""\b(song|full song|official music video|official video|lyric video|audio)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeBasic(value: String): String {
        val stripped = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return stripped
            .replace('&', ' ')
            .replace(Regex("""[^\p{L}\p{N} ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeLanguage(value: String): String {
        return normalizeBasic(value)
            .replace(' ', '-')
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

    private fun parseBitrate(quality: String): Int {
        val number = Regex("""(\d+)""").find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return 0
        return number * 1000
    }

    private fun inferMimeType(url: String): String {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains(".m4a") || lower.contains("mime=audio/mp4") -> "audio/mp4"
            lower.contains(".aac") -> "audio/aac"
            else -> "audio/mpeg"
        }
    }
}
