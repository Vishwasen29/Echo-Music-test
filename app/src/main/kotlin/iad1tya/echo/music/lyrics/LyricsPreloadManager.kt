package iad1tya.echo.music.lyrics

import android.content.Context
import android.util.Log
import iad1tya.echo.music.constants.PreloadQueueLyricsEnabledKey
import iad1tya.echo.music.constants.QueueLyricsPreloadCountKey
import iad1tya.echo.music.db.MusicDatabase
import iad1tya.echo.music.db.entities.LyricsEntity
import iad1tya.echo.music.models.MediaMetadata
import iad1tya.echo.music.utils.NetworkConnectivityObserver
import iad1tya.echo.music.utils.dataStore
import iad1tya.echo.music.utils.reportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LyricsPreloadManager(
    private val context: Context,
    private val database: MusicDatabase,
    private val networkConnectivity: NetworkConnectivityObserver,
    private val lyricsHelper: LyricsHelper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preloadJob: Job? = null

    fun onSongChanged(currentIndex: Int, queue: List<MediaMetadata>) {
        // CHATGPT_REFINED_LYRICS_PRELOAD_DISABLED
        preloadJob?.cancel()
        return
    }

    private fun getNextSongs(queue: List<MediaMetadata>, currentIndex: Int, count: Int): List<MediaMetadata> {
        if (queue.isEmpty() || currentIndex < 0 || count <= 0) return emptyList()

        val startIndex = currentIndex + 1
        if (startIndex >= queue.size) return emptyList()

        val endIndex = minOf(startIndex + count, queue.size)
        return queue.subList(startIndex, endIndex)
    }

    private fun preloadLyrics(songs: List<MediaMetadata>) {
        preloadJob = scope.launch {
            try {
                songs.forEach { song ->
                    val existingLyrics = database.lyrics(song.id).first()
                    if (existingLyrics != null && existingLyrics.lyrics != LyricsEntity.LYRICS_NOT_FOUND) {
                        return@forEach
                    }

                    runCatching {
                        lyricsHelper.getLyrics(song)
                    }.onSuccess { lyrics ->
                        if (!lyrics.isNullOrBlank() && lyrics != LyricsEntity.LYRICS_NOT_FOUND) {
                            database.query {
                                upsert(LyricsEntity(id = song.id, lyrics = lyrics))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                reportException(e)
            }
        }
    }

    fun destroy() {
        preloadJob?.cancel()
        preloadJob = null
        scope.cancel()
    }

    private companion object {
        private const val TAG = "LyricsPreloadManager"
        private const val DEFAULT_PRELOAD_COUNT = 1
    }
}
