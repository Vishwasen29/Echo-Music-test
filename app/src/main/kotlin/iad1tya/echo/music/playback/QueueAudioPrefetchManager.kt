package iad1tya.echo.music.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import iad1tya.echo.music.constants.QueueAudioPrefetchCountKey
import iad1tya.echo.music.constants.QueueAudioPrefetchEnabledKey
import iad1tya.echo.music.utils.NetworkConnectivityObserver
import iad1tya.echo.music.utils.dataStore
import iad1tya.echo.music.utils.reportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class QueueAudioPrefetchManager(
    private val context: Context,
    private val networkConnectivity: NetworkConnectivityObserver,
    private val downloadUtil: DownloadUtil,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: Job? = null

    fun onQueuePositionChanged(player: Player) {
        prefetchJob?.cancel()

        if (player.currentMediaItemIndex == -1 || player.mediaItemCount <= 1) {
            return
        }

        prefetchJob = scope.launch {
            try {
                val preferences = context.dataStore.data.first()
                val enabled = preferences[QueueAudioPrefetchEnabledKey] ?: true
                if (!enabled) {
                    Log.d(TAG, "Queue audio prefetch is disabled")
                    return@launch
                }

                val prefetchCount = (preferences[QueueAudioPrefetchCountKey] ?: DEFAULT_PREFETCH_COUNT)
                    .coerceIn(0, MAX_PREFETCH_COUNT)
                if (prefetchCount <= 0) return@launch

                val isNetworkAvailable = try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (_: Exception) {
                    true
                }
                if (!isNetworkAvailable) {
                    Log.d(TAG, "Network unavailable, skipping queue audio prefetch")
                    return@launch
                }

                val nextMediaIds = getNextMediaIds(player, prefetchCount)
                if (nextMediaIds.isEmpty()) return@launch

                nextMediaIds.forEachIndexed { index, mediaId ->
                    val success = downloadUtil.prefetchToPlayerCache(
                        mediaId = mediaId,
                        maxBytes = PREFETCH_BYTES,
                    )
                    Log.d(TAG, "Prefetch ${index + 1}/${nextMediaIds.size} for $mediaId -> $success")
                    delay(PREFETCH_DELAY_MS)
                }
            } catch (e: Exception) {
                reportException(e)
            }
        }
    }

    private fun getNextMediaIds(player: Player, count: Int): List<String> {
        if (count <= 0 || player.currentMediaItemIndex == -1 || player.mediaItemCount <= 1) {
            return emptyList()
        }

        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()

        val result = linkedSetOf<String>()
        var index = player.currentMediaItemIndex
        var guard = 0
        while (result.size < count && guard < player.mediaItemCount + count) {
            guard += 1
            val nextIndex = timeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
            if (nextIndex == -1 || nextIndex == index) break
            index = nextIndex
            val mediaId = player.getMediaItemAt(index).mediaId
            if (mediaId.isNotBlank() && mediaId != player.currentMediaItem?.mediaId) {
                result.add(mediaId)
            }
        }
        return result.toList()
    }

    fun destroy() {
        prefetchJob?.cancel()
        prefetchJob = null
        scope.cancel()
    }

    private companion object {
        private const val TAG = "QueueAudioPrefetch"
        private const val DEFAULT_PREFETCH_COUNT = 10
        private const val MAX_PREFETCH_COUNT = 10
        private const val PREFETCH_BYTES = 8L * 1024L * 1024L
        private const val PREFETCH_DELAY_MS = 350L
    }
}
