#!/usr/bin/env python3
from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path
from typing import Callable


TARGET_SENTINEL = Path("app/src/main/kotlin/iad1tya/echo/music/playback/MusicService.kt")


def resolve_repo_root(user_path: Path) -> Path:
    user_path = user_path.resolve()
    if user_path.is_file() and user_path.suffix.lower() == ".zip":
        raise RuntimeError("Please extract the ZIP first, then pass the repo folder path to this script.")
    candidates = [user_path]
    if user_path.is_dir():
        candidates.extend(p for p in user_path.iterdir() if p.is_dir())
    for candidate in candidates:
        if (candidate / TARGET_SENTINEL).exists():
            return candidate
    raise RuntimeError(
        f"Could not find repo root under {user_path}. Expected {TARGET_SENTINEL.as_posix()}"
    )


class PatchError(RuntimeError):
    pass


def replace_literal(text: str, old: str, new: str, *, path: Path, label: str) -> tuple[str, bool]:
    if old == new:
        return text, False
    if new in text:
        return text, False
    if old not in text:
        raise PatchError(f"{path}: expected snippet for {label!r} was not found")
    return text.replace(old, new, 1), True



def replace_regex(text: str, pattern: str, repl: str, *, path: Path, label: str, flags: int = re.S) -> tuple[str, bool]:
    if repl in text:
        return text, False
    new_text, count = re.subn(pattern, repl, text, count=1, flags=flags)
    if count == 0:
        raise PatchError(f"{path}: expected pattern for {label!r} was not found")
    return new_text, True



def ensure_after(text: str, anchor: str, insertion: str, *, path: Path, label: str) -> tuple[str, bool]:
    if insertion in text:
        return text, False
    idx = text.find(anchor)
    if idx == -1:
        raise PatchError(f"{path}: expected anchor for {label!r} was not found")
    insert_at = idx + len(anchor)
    return text[:insert_at] + insertion + text[insert_at:], True



def update_file(path: Path, updater: Callable[[str, Path], tuple[str, list[str]]]) -> list[str]:
    original = path.read_text(encoding="utf-8")
    updated, changes = updater(original, path)
    if updated != original:
        path.write_text(updated, encoding="utf-8")
    return changes



def patch_music_service(text: str, path: Path) -> tuple[str, list[str]]:
    changes: list[str] = []

    helper_block = """

    // CHATGPT_REFINED_WIDGET_GUARD
    private fun hasAnyPinnedWidgets(): Boolean {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        return appWidgetManager.getAppWidgetIds(ComponentName(this, MusicWidgetProvider::class.java)).isNotEmpty() ||
            appWidgetManager.getAppWidgetIds(ComponentName(this, WideMusicWidgetProvider::class.java)).isNotEmpty() ||
            appWidgetManager.getAppWidgetIds(ComponentName(this, ScalableMusicWidgetProvider::class.java)).isNotEmpty() ||
            appWidgetManager.getAppWidgetIds(ComponentName(this, AdaptiveMusicWidgetProvider::class.java)).isNotEmpty()
    }
"""
    if "private fun hasAnyPinnedWidgets(): Boolean" not in text:
        text, changed = ensure_after(
            text,
            "    private var widgetProgressJob: Job? = null\n",
            helper_block,
            path=path,
            label="widget pinned helper",
        )
        if changed:
            changes.append("widget pinned helper")

    for label, old, new in [
        (
            "queue persistence interval",
            "                delay(if (player.isPlaying) 120.seconds else 180.seconds)\n",
            "                delay(if (player.isPlaying) 180.seconds else 300.seconds)\n",
        ),
        (
            "widget idle poll interval",
            "                    delay(30000L)\n",
            "                    delay(60000L)\n",
        ),
        (
            "widget active poll interval",
            "                delay(if (player.isPlaying) 8000L else 20000L)\n",
            "                delay(if (player.isPlaying) 15000L else 60000L)\n",
        ),
    ]:
        try:
            text, changed = replace_literal(text, old, new, path=path, label=label)
        except PatchError:
            changed = False
        if changed:
            changes.append(label)

    if "private fun updateWidget() {\n        if (!hasAnyPinnedWidgets()) return\n" not in text:
        text, changed = replace_regex(
            text,
            r"private fun updateWidget\(\) \{\n",
            "private fun updateWidget() {\n        if (!hasAnyPinnedWidgets()) return\n",
            path=path,
            label="updateWidget early return",
        )
        if changed:
            changes.append("updateWidget early return")

    transition_replacement = """        // Update widget
        updateWidget()
        // CHATGPT_REFINED_RECOMMENDATIONS_AND_LYRICS_DISABLED
        playerRecommendations.value = emptyList()
        queueAudioPrefetchManager?.onQueuePositionChanged(player)
"""
    transition_pattern = (
        r"        // Update widget\n"
        r"        updateWidget\(\)\n"
        r"(?:        mediaItem\?\.mediaId\?\.let \{ recommendationMediaId ->\n"
        r"            scope\.launch\(SilentHandler\) \{\n"
        r"                refreshPlayerRecommendations\(recommendationMediaId\)\n"
        r"            \}\n"
        r"        \}\n)?"
        r"\n?"
        r"(?:        val queue = player\.mediaItems\.mapNotNull \{ it\.metadata \}\n"
        r"        if \(queue\.isNotEmpty\(\)\) \{\n"
        r"            lyricsPreloadManager\?\.onSongChanged\(player\.currentMediaItemIndex, queue\)\n"
        r"        \}\n)?"
        r"\n?"
        r"(?:        // CHATGPT_QUEUE_PREFETCH_PATCH\n)?"
        r"        queueAudioPrefetchManager\?\.onQueuePositionChanged\(player\)\n"
    )
    text, changed = replace_regex(
        text,
        transition_pattern,
        transition_replacement,
        path=path,
        label="disable player recommendations and lyrics preload on transitions",
    )
    if changed:
        changes.append("disable recommendations and lyrics on transitions")

    refresh_replacement = """    private suspend fun refreshPlayerRecommendations(mediaId: String) {
        // CHATGPT_REFINED_RECOMMENDATIONS_DISABLED
        playerRecommendations.value = emptyList()
    }


    suspend fun addRecommendationToYoutubePlaylist(
"""
    text, changed = replace_regex(
        text,
        r"    private suspend fun refreshPlayerRecommendations\(mediaId: String\) \{.*?\n    \}\n\n    suspend fun addRecommendationToYoutubePlaylist\(\n",
        refresh_replacement,
        path=path,
        label="disable refreshPlayerRecommendations body",
    )
    if changed:
        changes.append("disable refreshPlayerRecommendations body")

    return text, changes



def patch_queue_prefetch_manager(text: str, path: Path) -> tuple[str, list[str]]:
    changes: list[str] = []
    replacements = [
        (
            "queue prefetch default disabled",
            r"val enabled = preferences\[QueueAudioPrefetchEnabledKey\] \?: (true|false)",
            "val enabled = preferences[QueueAudioPrefetchEnabledKey] ?: false",
        ),
        (
            "queue prefetch player-is-playing guard",
            r"if \(!enabled\) return@launch\n",
            "if (!enabled) return@launch\n            if (!player.isPlaying) return@launch\n",
        ),
        (
            "queue prefetch default count",
            r"private const val DEFAULT_PREFETCH_COUNT = \d+",
            "private const val DEFAULT_PREFETCH_COUNT = 1",
        ),
        (
            "queue prefetch max count",
            r"private const val MAX_PREFETCH_COUNT = \d+",
            "private const val MAX_PREFETCH_COUNT = 2",
        ),
        (
            "queue prefetch bytes",
            r"private const val PREFETCH_BYTES = .*",
            "private const val PREFETCH_BYTES = 1L * 1024L * 1024L",
        ),
        (
            "queue prefetch delay",
            r"private const val PREFETCH_DELAY_MS = \d+L",
            "private const val PREFETCH_DELAY_MS = 2000L",
        ),
    ]

    for label, pattern, repl in replacements:
        try:
            text, changed = replace_regex(text, pattern, repl, path=path, label=label, flags=re.S)
        except PatchError:
            changed = False
        if changed:
            changes.append(label)

    return text, changes



def patch_lyrics_preload_manager(text: str, path: Path) -> tuple[str, list[str]]:
    changes: list[str] = []

    try:
        text, changed = replace_regex(
            text,
            r"fun onSongChanged\(currentIndex: Int, queue: List<MediaMetadata>\) \{.*?\n    \}\n\n    private fun getNextSongs",
            "fun onSongChanged(currentIndex: Int, queue: List<MediaMetadata>) {\n        // CHATGPT_REFINED_LYRICS_PRELOAD_DISABLED\n        preloadJob?.cancel()\n        return\n    }\n\n    private fun getNextSongs",
            path=path,
            label="disable lyrics preload manager",
        )
        if changed:
            changes.append("disable lyrics preload manager")
    except PatchError:
        pass

    try:
        text, changed = replace_regex(
            text,
            r"val isEnabled = preferences\[PreloadQueueLyricsEnabledKey\] \?: (true|false)",
            "val isEnabled = preferences[PreloadQueueLyricsEnabledKey] ?: false",
            path=path,
            label="lyrics preload default false",
        )
        if changed:
            changes.append("lyrics preload default false")
    except PatchError:
        pass

    try:
        text, changed = replace_regex(
            text,
            r"private const val DEFAULT_PRELOAD_COUNT = \d+",
            "private const val DEFAULT_PRELOAD_COUNT = 1",
            path=path,
            label="lyrics preload count",
        )
        if changed:
            changes.append("lyrics preload count")
    except PatchError:
        pass

    return text, changes



def patch_player_settings(text: str, path: Path) -> tuple[str, list[str]]:
    changes: list[str] = []

    enabled_pattern = re.compile(
        r"(QueueAudioPrefetchEnabledKey,\n\s*defaultValue = )(true|false)(\n\s*\))",
        re.S,
    )
    match = enabled_pattern.search(text)
    if match and match.group(2) != "false":
        text = enabled_pattern.sub(r"\1false\3", text, count=1)
        changes.append("player settings prefetch enabled default")

    count_pattern = re.compile(
        r"(QueueAudioPrefetchCountKey,\n\s*defaultValue = )(\d+)(\n\s*\))",
        re.S,
    )
    match = count_pattern.search(text)
    if match and match.group(2) != "1":
        text = count_pattern.sub(r"\g<1>1\3", text, count=1)
        changes.append("player settings prefetch count default")

    return text, changes


def patch_content_settings(text: str, path: Path) -> tuple[str, list[str]]:
    changes: list[str] = []
    idx = text.find("PreloadQueueLyricsEnabledKey")
    if idx != -1:
        segment = text[idx: idx + 220]
        old = "defaultValue = true)"
        new = "defaultValue = false)"
        if new not in segment:
            if old not in segment:
                raise PatchError(f"{path}: expected preload lyrics default near ContentSettings was not found")
            segment = segment.replace(old, new, 1)
            text = text[:idx] + segment + text[idx + 220:]
            changes.append("content settings lyrics preload default")
    return text, changes



def patch_saavn_audio_resolver(text: str, path: Path) -> tuple[str, list[str]]:
    changes: list[str] = []

    replacements: list[tuple[str, str, str]] = [
        (
            "resolve retries next playable candidate",
            r"    suspend fun resolve\(\n        mediaMetadata: MediaMetadata,\n        audioQuality: AudioQuality,\n    \): Result<ResolvedStream\?> = withContext\(Dispatchers\.IO\) \{.*?\n    \}\n\n\n    suspend fun resolveById\(\n",
            """    suspend fun resolve(
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

            ranked.asSequence()
                .filter { (candidate, candidateScore) ->
                    isStrongAccept(candidate, candidateScore, mediaMetadata)
                }
                .take(6)
                .mapNotNull { (candidate, _) ->
                    val hydrated = if (candidate.downloadLinks.isNotEmpty() && !candidate.thumbnailUrl.isNullOrBlank()) {
                        candidate
                    } else {
                        fetchSong(candidate.id) ?: candidate
                    }

                    val chosenLink = pickDownloadLink(hydrated.downloadLinks, audioQuality) ?: return@mapNotNull null
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
                        albumTitle = hydrated.albumName,
                        durationSeconds = hydrated.duration,
                    )
                }
                .firstOrNull()
        }
    }


    suspend fun resolveById(
""",
        ),
        (
            "searchSongs filters bad variants",
            r"    suspend fun searchSongs\(\n        query: String,\n        limit: Int = 12,\n    \): Result<List<SaavnSearchResult>> = withContext\(Dispatchers\.IO\) \{.*?\n    \}\n\n\n    private fun saavnSearchScore",
            """    suspend fun searchSongs(
        query: String,
        limit: Int = 12,
    ): Result<List<SaavnSearchResult>> = withContext(Dispatchers.IO) {
        runCatching {
            if (query.isBlank()) return@runCatching emptyList()

            search(query)
                .distinctBy { it.id }
                .filterNot { candidate -> hasUnexpectedPenaltyTerms(candidate, query) }
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


    private fun saavnSearchScore""",
        ),
        (
            "buildQueries drops title-only fallback when artist exists",
            r"    private fun buildQueries\(mediaMetadata: MediaMetadata\): List<String> \{.*?\n    \}\n\n    private fun search\(query: String\): List<Candidate> \{",
            """    private fun buildQueries(mediaMetadata: MediaMetadata): List<String> {
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

        return (strictQueries + fallbackQueries)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun search(query: String): List<Candidate> {""",
        ),
        (
            "parseArtists prioritizes primary artist",
            r"    private fun parseArtists\(json: JSONObject\): List<String> \{.*?\n    \}\n\n    private fun parseDownloadLinks",
            """    private fun parseArtists(json: JSONObject): List<String> {
        val artists = linkedSetOf<String>()

        val structured = json.optJSONObject("artists")
        if (structured != null) {
            val primary = structured.optJSONArray("primary")
            if (primary != null) {
                for (index in 0 until primary.length()) {
                    val artist = primary.optJSONObject(index) ?: continue
                    val name = artist.optString("name").trim()
                    if (name.isNotBlank()) artists += name
                }
            }

            if (artists.isEmpty()) {
                listOf("featured", "all").forEach { key ->
                    val array = structured.optJSONArray(key) ?: return@forEach
                    for (index in 0 until array.length()) {
                        val artist = array.optJSONObject(index) ?: continue
                        val name = artist.optString("name").trim()
                        if (name.isNotBlank()) artists += name
                    }
                }
            }
        }

        val primaryArtists = json.optString("primaryArtists").trim()
        if (primaryArtists.isNotBlank()) {
            primaryArtists
                .split(',', '&')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { artists += it }
        }

        if (artists.isEmpty()) {
            listOf(
                json.optString("singers"),
                json.optString("artists").takeIf { json.opt("artists") is String } ?: "",
            ).forEach { raw ->
                raw.split(',', '&').map { it.trim() }.filter { it.isNotBlank() }.forEach { artists += it }
            }
        }

        return artists.toList()
    }

    private fun parseDownloadLinks""",
        ),
        (
            "primary artist matching stricter",
            r"    private fun hasStrongPrimaryArtistMatch\(candidate: Candidate, requested: MediaMetadata\): Boolean \{.*?\n    \}\n\n    private fun isStrongAccept",
            """    private fun hasStrongPrimaryArtistMatch(candidate: Candidate, requested: MediaMetadata): Boolean {
        val requestedPrimaryArtist = requested.artists.firstOrNull()?.name?.let(::normalizeArtist).orEmpty()
        if (requestedPrimaryArtist.isBlank()) return true

        val candidatePrimaryArtist = candidate.artists.firstOrNull()?.let(::normalizeArtist).orEmpty()
        if (candidatePrimaryArtist.isBlank()) return false
        return candidatePrimaryArtist == requestedPrimaryArtist || artistNamesMatch(candidatePrimaryArtist, requestedPrimaryArtist)
    }

    private fun isStrongAccept""",
        ),
        (
            "isStrongAccept rejects wrong variants",
            r"    private fun isStrongAccept\(candidate: Candidate, score: Int, requested: MediaMetadata\): Boolean \{.*?\n    \}\n\n    private fun score",
            """    private fun isStrongAccept(candidate: Candidate, score: Int, requested: MediaMetadata): Boolean {
        val requestedPrimaryArtist = requested.artists.firstOrNull()?.name?.let(::normalizeArtist).orEmpty()
        val requestedTitleRaw = requested.title.trim()
        val requestedTitle = normalizeTitleCore(requestedTitleRaw)
        val candidateTitleRaw = candidate.title.trim()
        val candidateTitle = normalizeTitleCore(candidateTitleRaw)
        val titleStrong = candidateTitle == requestedTitle ||
            candidateTitleRaw.equals(requestedTitleRaw, ignoreCase = true) ||
            tokenSimilarity(candidateTitle, requestedTitle) >= 42 ||
            (requestedTitle.length >= 5 && candidateTitle.contains(requestedTitle)) ||
            (candidateTitle.length >= 5 && requestedTitle.contains(candidateTitle))
        val durationClose = requested.duration <= 0 ||
            candidate.duration == null ||
            kotlin.math.abs(candidate.duration - requested.duration) <= 8

        if (hasUnexpectedPenaltyTerms(candidate, requested.title)) return false

        return if (requestedPrimaryArtist.isBlank()) {
            score >= 140 && titleStrong && durationClose
        } else {
            score >= 150 && titleStrong && durationClose && hasStrongPrimaryArtistMatch(candidate, requested)
        }
    }

    private fun score""",
        ),
        (
            "penaltyScore strengthened",
            r"    private fun penaltyScore\(candidate: Candidate, requestedTitle: String\): Int \{.*?\n    \}\n\n    private fun extractPenaltyTerms",
            """    private fun penaltyScore(candidate: Candidate, requestedTitle: String): Int {
        val requestedPenaltyTerms = extractPenaltyTerms(requestedTitle)
        val candidateTerms = extractPenaltyTerms(
            normalizeTitleCore(candidate.title) + " " +
                normalizeTitleCore(candidate.albumName.orEmpty()) + " " +
                candidate.artists.joinToString(" ") { normalizeArtist(it) }
        )
        val extraTerms = candidateTerms - requestedPenaltyTerms
        var score = 0
        if ("cover" in extraTerms) score -= 180
        if ("karaoke" in extraTerms) score -= 220
        if ("tribute" in extraTerms) score -= 140
        if ("instrumental" in extraTerms) score -= 180
        if ("acoustic" in extraTerms) score -= 90
        if ("live" in extraTerms) score -= 85
        if ("remix" in extraTerms) score -= 120
        if ("slowed" in extraTerms || "reverb" in extraTerms) score -= 140
        if ("dj" in extraTerms || "mix" in extraTerms) score -= 95
        if ("version" in extraTerms) score -= 55
        if ("devotional" in extraTerms || "bhajan" in extraTerms || "aarti" in extraTerms) score -= 170
        return score
    }

    private fun extractPenaltyTerms""",
        ),
        (
            "penalty helpers",
            r"    private fun extractPenaltyTerms\(text: String\): Set<String> \{.*?\n    \}\n\n    private fun artistNamesMatch",
            """    private fun extractPenaltyTerms(text: String): Set<String> {
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

    private fun hasUnexpectedPenaltyTerms(candidate: Candidate, requestedTitle: String): Boolean {
        val requestedPenaltyTerms = extractPenaltyTerms(requestedTitle)
        val candidateTerms = extractPenaltyTerms(
            normalizeTitleCore(candidate.title) + " " +
                normalizeTitleCore(candidate.albumName.orEmpty()) + " " +
                candidate.artists.joinToString(" ") { normalizeArtist(it) }
        )
        val extraTerms = candidateTerms - requestedPenaltyTerms
        return extraTerms.any {
            it == "cover" ||
                it == "karaoke" ||
                it == "tribute" ||
                it == "instrumental" ||
                it == "acoustic" ||
                it == "live" ||
                it == "remix" ||
                it == "slowed" ||
                it == "reverb" ||
                it == "sped up" ||
                it == "sped" ||
                it == "nightcore" ||
                it == "lofi" ||
                it == "lo fi" ||
                it == "dj" ||
                it == "mix" ||
                it == "version" ||
                it == "devotional" ||
                it == "bhajan" ||
                it == "aarti"
        }
    }

    private fun artistNamesMatch""",
        ),
    ]

    for label, pattern, replacement in replacements:
        text, changed = replace_regex(text, pattern, replacement, path=path, label=label)
        if changed:
            changes.append(label)

    return text, changes



def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: python3 fix_echo_battery_saavn_refined.py /path/to/Echo-Music-test-main", file=sys.stderr)
        return 2

    repo_root = resolve_repo_root(Path(sys.argv[1]))

    targets: list[tuple[Path, Callable[[str, Path], tuple[str, list[str]]]]] = [
        (repo_root / "app/src/main/kotlin/iad1tya/echo/music/playback/MusicService.kt", patch_music_service),
        (repo_root / "app/src/main/kotlin/iad1tya/echo/music/playback/QueueAudioPrefetchManager.kt", patch_queue_prefetch_manager),
        (repo_root / "app/src/main/kotlin/iad1tya/echo/music/lyrics/LyricsPreloadManager.kt", patch_lyrics_preload_manager),
        (repo_root / "app/src/main/kotlin/iad1tya/echo/music/ui/screens/settings/PlayerSettings.kt", patch_player_settings),
        (repo_root / "app/src/main/kotlin/iad1tya/echo/music/ui/screens/settings/ContentSettings.kt", patch_content_settings),
        (repo_root / "app/src/main/kotlin/iad1tya/echo/music/utils/SaavnAudioResolver.kt", patch_saavn_audio_resolver),
    ]

    all_changes: list[str] = []
    for path, updater in targets:
        if not path.exists():
            raise RuntimeError(f"Missing expected file: {path}")
        changes = update_file(path, updater)
        for change in changes:
            all_changes.append(f"{path.relative_to(repo_root)} :: {change}")

    if all_changes:
        print("Applied changes:")
        for change in all_changes:
            print(f"- {change}")
    else:
        print("No changes needed. Repo already matches the refined patch.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
