package iad1tya.echo.music.playback

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.Player
import iad1tya.echo.music.constants.QueueAudioPrefetchCountKey
import iad1tya.echo.music.constants.QueueAudioPrefetchEnabledKey
import iad1tya.echo.music.extensions.metadata
import iad1tya.echo.music.utils.NetworkConnectivityObserver
import iad1tya.echo.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class QueueAudioPrefetchManager(
    private val context: Context,
    private val networkConnectivity: NetworkConnectivityObserver,
    private val downloadUtil: DownloadUtil,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: Job? = null

    fun onQueuePositionChanged(player: Player) {
        prefetchJob?.cancel()

        prefetchJob = scope.launch {
            val preferences = context.dataStore.data.first()
            val enabled = preferences[QueueAudioPrefetchEnabledKey] ?: false
            if (!enabled) return@launch

            val prefetchCount = (preferences[QueueAudioPrefetchCountKey] ?: DEFAULT_PREFETCH_COUNT)
                .coerceIn(1, MAX_PREFETCH_COUNT)

            if (!networkConnectivity.isCurrentlyConnected()) {
                Log.d(TAG, "Network unavailable, skipping queue audio prefetch")
                return@launch
            }

            val snapshot = capturePrefetchSnapshot(player, prefetchCount)
            if (!snapshot.shouldPrefetch) return@launch

            val nextTargets = snapshot.targets
            if (nextTargets.isEmpty()) return@launch

            nextTargets.forEachIndexed { index, target ->
                if (!isActive) return@launch
                runCatching {
                    downloadUtil.prefetchToPlayerCache(
                        mediaId = target.mediaId,
                        metadata = target.metadata,
                        maxBytes = PREFETCH_BYTES,
                    )
                }.onFailure {
                    Log.w(TAG, "Prefetch failed for ${target.mediaId}", it)
                }
                Log.d(TAG, "Prefetch scheduled ${index + 1}/${nextTargets.size} for ${target.mediaId}")
                delay(PREFETCH_DELAY_MS)
            }
        }
    }

    private data class PrefetchTarget(
        val mediaId: String,
        val metadata: iad1tya.echo.music.models.MediaMetadata?,
    )

    private data class PrefetchSnapshot(
        val shouldPrefetch: Boolean,
        val targets: List<PrefetchTarget>,
    )

    private suspend fun capturePrefetchSnapshot(player: Player, count: Int): PrefetchSnapshot =
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(player.applicationLooper)
            handler.post {
                if (!continuation.isActive) return@post
                val snapshot = runCatching {
                    val shouldPrefetch = player.isPlaying
                    val targets = if (shouldPrefetch) {
                        getNextMediaTargets(player, count)
                    } else {
                        emptyList()
                    }
                    PrefetchSnapshot(
                        shouldPrefetch = shouldPrefetch,
                        targets = targets,
                    )
                }.getOrElse {
                    Log.w(TAG, "Failed to capture player snapshot for prefetch", it)
                    PrefetchSnapshot(
                        shouldPrefetch = false,
                        targets = emptyList(),
                    )
                }
                continuation.resume(snapshot)
            }
        }

    private fun getNextMediaTargets(player: Player, count: Int): List<PrefetchTarget> {
        if (count <= 0 || player.currentMediaItemIndex == -1 || player.mediaItemCount <= 1) {
            return emptyList()
        }

        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()

        val currentMediaId = player.currentMediaItem?.mediaId
        val mediaItemCount = player.mediaItemCount
        val repeatMode = player.repeatMode
        val shuffleModeEnabled = player.shuffleModeEnabled

        val result = linkedMapOf<String, PrefetchTarget>()
        var index = player.currentMediaItemIndex
        var guard = 0
        while (result.size < count && guard < mediaItemCount + count + 4) {
            guard += 1
            val nextIndex = timeline.getNextWindowIndex(index, repeatMode, shuffleModeEnabled)
            if (nextIndex == -1 || nextIndex == index) break
            index = nextIndex
            val mediaItem = player.getMediaItemAt(index)
            val mediaId = mediaItem.mediaId
            if (mediaId.isNotBlank() && mediaId != currentMediaId) {
                result.putIfAbsent(
                    mediaId,
                    PrefetchTarget(
                        mediaId = mediaId,
                        metadata = mediaItem.metadata,
                    ),
                )
            }
        }
        return result.values.toList()
    }

    fun destroy() {
        prefetchJob?.cancel()
        prefetchJob = null
        scope.cancel()
    }

    private companion object {
        private const val TAG = "QueueAudioPrefetch"
        private const val DEFAULT_PREFETCH_COUNT = 1
        private const val MAX_PREFETCH_COUNT = 2
        private const val PREFETCH_BYTES = 2L * 1024L * 1024L
        private const val PREFETCH_DELAY_MS = 1200L
    }
}
